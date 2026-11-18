"""Strict Java worker-control adapter over the shared signed HTTP client."""

from __future__ import annotations

import ipaddress
import json
import logging
import math
import re
from collections.abc import Awaitable
from datetime import UTC, datetime
from typing import Annotated, Literal, NoReturn, cast
from urllib.parse import urlsplit
from uuid import UUID, uuid4

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    JsonValue,
    StrictBool,
    StrictInt,
    StrictStr,
    StringConstraints,
    ValidationError,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

from app.article.models import (
    ArticleConfirmedFacts,
    ArticleExecutionBudget,
    ArticleExecutionRequest,
    ArticleGeoRecommendation,
    ArticleGeoReportSummary,
    ArticleOutlineItem,
    ArticlePlan,
    ArticleSourceAssetSummary,
    ArticleStage,
    ArticleTargetLength,
)
from app.article.worker_models import (
    ArticleCompletion,
    ArticleExecutionContext,
    ArticleOutlineSnapshot,
    parse_canonical_article_body,
)
from app.common.enums import TaskType
from app.common.settings import AppSettings
from app.creative.geo_visual_plan import validated_frozen_plan
from app.creative.models import CreativeReferenceAsset, CreativeSourceMode, ProductCreativeBrief
from app.geo.models import (
    GeoCitation,
    GeoObservation,
    GeoObservationStatus,
    GeoPlatformCode,
    GeoProviderUsage,
    GeoSynthesis,
    GeoVisibilityEvidence,
    GeoVisibilityEvidenceUnit,
)
from app.geo.worker_models import (
    GeoCompetitorSnapshot,
    GeoCompletion,
    GeoDiagnosticSynthesisContext,
    GeoDiagnosticTaskParameters,
    GeoExecutionContext,
    GeoMonitoringTarget,
    GeoObservationDisposition,
    GeoQuestionSnapshot,
    GeoSampleMode,
    GeoSynthesisContext,
    GeoTaskParameters,
    GeoTopicSnapshot,
)
from app.integration.java_internal import (
    JavaInternalClientError,
    JavaInternalErrorCode,
    JavaInternalResponse,
    JavaWorkerInternalClientProtocol,
    UnavailableJavaInternalClient,
    build_java_internal_client,
)
from app.providers.java_context import CompetitorSnapshot, ProductSnapshot, SpaceSnapshot
from app.providers.knowledge_context import KnowledgeContext
from app.providers.models import (
    QwenImageProviderContinuation,
    SubmissionState,
    TaskProviderResult,
    WanImageEditProviderContinuation,
    WanVideoProviderContinuation,
)
from app.providers.web_research import WebResearchEvidence
from app.workers.control_plane import (
    ClaimDisposition,
    ClaimResult,
    ControlPlaneConfigurationError,
    ControlPlaneRejected,
    ControlPlaneUnavailable,
    ExecutionContext,
    JavaTaskStatus,
    WorkerControlPlane,
)
from app.workers.models import TaskExecutionMessage

_MAX_SIGNED_LONG_TEXT = "9223372036854775807"
_STAGE_PATTERN = r"^[A-Z][A-Z0-9_]{0,63}$"
_LOGGER = logging.getLogger(__name__)


def _signed_long_range(value: str) -> str:
    if len(value) > len(_MAX_SIGNED_LONG_TEXT) or (
        len(value) == len(_MAX_SIGNED_LONG_TEXT) and value > _MAX_SIGNED_LONG_TEXT
    ):
        raise ValueError("Snowflake ID exceeds the Java signed long range")
    return value


SnowflakeId = Annotated[
    StrictStr,
    StringConstraints(pattern=r"^[1-9][0-9]*$"),
    AfterValidator(_signed_long_range),
]
Text128 = Annotated[StrictStr, StringConstraints(min_length=1, max_length=128)]
ExecutionToken = Annotated[StrictStr, StringConstraints(min_length=1, max_length=512)]
StageCode = Annotated[StrictStr, StringConstraints(pattern=_STAGE_PATTERN)]
PositiveInt = Annotated[StrictInt, Field(ge=1)]
GeoPlatform = Literal["DOUBAO", "DEEPSEEK", "QWEN", "WENXIN", "KIMI", "YUANBAO", "ZHIPU"]
GeoUnitKey = Annotated[
    StrictStr,
    StringConstraints(
        pattern=(
            r"^[1-9][0-9]{0,18}:"
            r"(DOUBAO|DEEPSEEK|QWEN|WENXIN|KIMI|YUANBAO|ZHIPU):[1-3]$"
        ),
        max_length=256,
    ),
]


class _JavaWorkerModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        hide_input_in_errors=True,
        strict=True,
    )


class _TaskClaimRequest(_JavaWorkerModel):
    consumer_name: Text128
    event_id: SnowflakeId
    worker_id: Text128
    attempt: Annotated[StrictInt, Field(ge=1, le=32)]
    supported_bundle_version: Text128


class _TaskClaimResponse(_JavaWorkerModel):
    claimed: StrictBool
    disposition: Literal["CLAIMED", "DUPLICATE", "TERMINAL"]
    execution_no: PositiveInt | None
    execution_token: Annotated[StrictStr, StringConstraints(max_length=512)] | None
    lease_expires_at: StrictStr | None

    @field_validator("lease_expires_at")
    @classmethod
    def lease_must_be_an_offset_datetime(cls, value: str | None) -> str | None:
        if value is not None:
            _parse_offset_datetime(value)
        return value

    @model_validator(mode="after")
    def disposition_must_match_claim_fields(self) -> _TaskClaimResponse:
        if self.disposition == "CLAIMED":
            if (
                self.claimed is not True
                or self.execution_no is None
                or not self.execution_token
                or self.lease_expires_at is None
            ):
                raise ValueError("CLAIMED must include a complete execution identity")
        elif self.claimed is not False:
            raise ValueError("Non-CLAIMED dispositions must set claimed=false")
        if self.disposition == "TERMINAL" and any(
            value is not None
            for value in (
                self.execution_no,
                self.execution_token,
                self.lease_expires_at,
            )
        ):
            raise ValueError("TERMINAL must not include an execution identity")
        return self


class _TaskPricing(_JavaWorkerModel):
    pricing_version_id: SnowflakeId
    quoted_points: PositiveInt


class _TaskBudget(_JavaWorkerModel):
    max_provider_calls: Annotated[StrictInt, Field(ge=1, le=1_000)]
    max_images: Annotated[StrictInt, Field(ge=1, le=4)]


class _VideoTaskBudget(_JavaWorkerModel):
    max_provider_calls: Annotated[StrictInt, Field(ge=1, le=1_000)]
    max_images: None = None
    max_videos: Literal[1]
    max_video_seconds: Literal[30]


class _QwenImageProviderContinuation(_JavaWorkerModel):
    resume_only: Literal[True]
    provider: Literal["QWEN_IMAGE", "WAN_IMAGE_EDIT"]
    model: Literal["qwen-image-3.0", "wanx2.1-imageedit"]
    provider_request_id: StrictStr
    provider_task_id: Annotated[
        StrictStr,
        StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,256}$"),
    ]
    submission_state: Literal[SubmissionState.UNKNOWN]

    @model_validator(mode="after")
    def provider_model_pair_must_match(self) -> _QwenImageProviderContinuation:
        if (self.provider, self.model) not in {
            ("QWEN_IMAGE", "qwen-image-3.0"),
            ("WAN_IMAGE_EDIT", "wanx2.1-imageedit"),
        }:
            raise ValueError("provider and model must identify the same adapter")
        return self

    @field_validator("provider_request_id")
    @classmethod
    def provider_request_id_must_be_canonical_uuid(cls, value: str) -> str:
        try:
            parsed = UUID(value)
        except ValueError as exc:
            raise ValueError("providerRequestId must be a UUID") from exc
        if str(parsed) != value:
            raise ValueError("providerRequestId must use canonical UUID text")
        return value


class _WanVideoProviderContinuation(_JavaWorkerModel):
    resume_only: Literal[True]
    provider: Literal["WAN_VIDEO"]
    model: Literal["wan3.0-video"]
    provider_request_id: StrictStr
    provider_task_id: Annotated[
        StrictStr,
        StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,256}$"),
    ]
    submission_state: Literal[SubmissionState.UNKNOWN]

    @field_validator("provider_request_id")
    @classmethod
    def provider_request_id_must_be_canonical_uuid(cls, value: str) -> str:
        try:
            parsed = UUID(value)
        except ValueError as exc:
            raise ValueError("providerRequestId must be a UUID") from exc
        if str(parsed) != value:
            raise ValueError("providerRequestId must use canonical UUID text")
        return value


class _UnknownProviderFailure(_JavaWorkerModel):
    """Real submission uncertainty, including the absence of queryable receipts."""

    provider: Literal["QWEN_IMAGE", "WAN_IMAGE_EDIT", "WAN_VIDEO", "DEEPSEEK"]
    model: (
        Literal["qwen-image-3.0", "wanx2.1-imageedit", "wan3.0-video"]
        | Annotated[StrictStr, StringConstraints(pattern=r"^deepseek-[A-Za-z0-9._-]{1,112}$")]
    )
    provider_request_id: StrictStr | None
    provider_task_id: (
        Annotated[StrictStr, StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,256}$")] | None
    )
    submission_state: Literal[SubmissionState.UNKNOWN]

    @field_validator("provider_request_id")
    @classmethod
    def validate_receipt_uuid(cls, value: str | None) -> str | None:
        if value is not None and str(UUID(value)) != value:
            raise ValueError("providerRequestId must use canonical UUID text")
        return value

    @model_validator(mode="after")
    def validate_provider_identity(self) -> _UnknownProviderFailure:
        if self.provider == "DEEPSEEK":
            if (
                re.fullmatch(r"deepseek-[A-Za-z0-9._-]{1,112}", self.model) is None
                or self.provider_request_id is not None
                or self.provider_task_id is not None
            ):
                raise ValueError("ARTICLE uncertainty cannot authorize queryable continuation")
            return self
        if (self.provider, self.model) not in {
            ("QWEN_IMAGE", "qwen-image-3.0"),
            ("WAN_IMAGE_EDIT", "wanx2.1-imageedit"),
            ("WAN_VIDEO", "wan3.0-video"),
        }:
            raise ValueError("unknown submission identity must match its adapter")
        return self


class _AuthorizedTaskContext(_JavaWorkerModel):
    space: SpaceSnapshot | None
    product: ProductSnapshot | None
    competitors: tuple[CompetitorSnapshot, ...]
    attachment_asset_ids: tuple[SnowflakeId, ...] = Field(max_length=3)

    @field_validator("competitors", "attachment_asset_ids", mode="before")
    @classmethod
    def arrays_must_be_frozen_after_json_decoding(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def relationships_must_stay_inside_the_authorized_space(self) -> _AuthorizedTaskContext:
        if self.product is not None and (
            self.space is None or self.product.space_id != self.space.id
        ):
            raise ValueError("product must belong to the authorized space")
        if any(
            self.space is None or competitor.space_id != self.space.id
            for competitor in self.competitors
        ):
            raise ValueError("competitors must belong to the authorized space")
        return self


class _VideoInputSnapshot(_JavaWorkerModel):
    request_text: Annotated[StrictStr, StringConstraints(min_length=1, max_length=16_000)]
    mode: Literal["AGENT_ASSISTED"]
    agent_summary: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)]
    source_mode: Literal[
        "CATALOG_PRODUCT",
        "CURRENT_ATTACHMENTS",
        "PREVIOUS_USER_ATTACHMENT",
        "PREVIOUS_IMAGE_RESULT",
        "TEXT_ONLY",
        "SELECTED_ASSETS",
        "PRODUCT_MATERIALS",
        "GEO_REPORT",
    ]
    reference_asset_ids: tuple[SnowflakeId, ...] = Field(max_length=3)
    geo_report_id: SnowflakeId | None = None
    creative_plan: dict[str, JsonValue] | None = None
    scene: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)]
    style: Annotated[StrictStr, StringConstraints(min_length=1, max_length=256)] | None = None
    duration_seconds: Annotated[StrictInt, Field(ge=2, le=30)]
    aspect_ratio: Literal["adaptive", "16:9", "4:3", "1:1", "3:4", "9:16"]
    camera_motion: Annotated[StrictStr, StringConstraints(min_length=1, max_length=256)] | None = (
        None
    )
    resolution: Literal["480P", "720P", "1080P"]
    audio: StrictBool
    seed: Annotated[StrictInt, Field(ge=-1, le=2_147_483_647)]
    prompt_extend: StrictBool
    watermark: StrictBool

    @field_validator("reference_asset_ids", mode="before")
    @classmethod
    def reference_asset_ids_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class _ImageTaskExecutionContextResponse(_JavaWorkerModel):
    task_id: SnowflakeId
    task_type: Literal["IMAGE"]
    provider: Literal["QWEN_IMAGE", "WAN_IMAGE_EDIT"]
    model: Literal["qwen-image-3.0", "wanx2.1-imageedit"]
    execution_no: PositiveInt
    snapshot_schema_version: Literal[1]
    input_snapshot: dict[str, JsonValue]
    authorized_context: dict[str, JsonValue]
    product_creative_brief: ProductCreativeBrief
    reference_assets: tuple[CreativeReferenceAsset, ...] = Field(max_length=3)
    controlled_mask_asset: CreativeReferenceAsset | None = None
    resume_only: StrictBool
    provider_continuation: _QwenImageProviderContinuation | None
    pricing: _TaskPricing
    budget: _TaskBudget

    @field_validator("input_snapshot", "authorized_context", mode="before")
    @classmethod
    def snapshots_must_be_json_objects(cls, value: object) -> object:
        _validate_json_value(value, require_object=True)
        return value

    @field_validator("reference_assets", mode="before")
    @classmethod
    def reference_assets_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def continuation_must_match_resume_mode(self) -> _ImageTaskExecutionContextResponse:
        if self.resume_only != (self.provider_continuation is not None):
            raise ValueError("resumeOnly and providerContinuation must agree")
        region_edit = self.product_creative_brief.source_mode is CreativeSourceMode.REGION_EDIT
        if not region_edit and self.budget.max_provider_calls != 1:
            raise ValueError("ordinary image tasks authorize exactly one submission")
        expected_identity = (
            ("WAN_IMAGE_EDIT", "wanx2.1-imageedit")
            if region_edit
            else ("QWEN_IMAGE", "qwen-image-3.0")
        )
        if (self.provider, self.model) != expected_identity:
            raise ValueError("image provider identity must match the authorized mode")
        if (
            self.provider_continuation is not None
            and (self.provider_continuation.provider, self.provider_continuation.model)
            != expected_identity
        ):
            raise ValueError("continuation identity must match the frozen provider")
        if region_edit:
            mask = self.controlled_mask_asset
            if (
                mask is None
                or mask.asset_id != self.input_snapshot.get("maskAssetId")
                or mask.asset_id
                != self.product_creative_brief.creative_direction.get("maskAssetId")
            ):
                raise ValueError("region edit mask must match its authorized snapshot")
        elif self.controlled_mask_asset is not None:
            raise ValueError("mask is valid only for region edit")
        if self.product_creative_brief.source_mode is CreativeSourceMode.PRODUCT_MATERIALS:
            raise ValueError("video-only source cannot execute as image")
        if self.product_creative_brief.source_mode is CreativeSourceMode.GEO_REPORT:
            report_id = self.input_snapshot.get("geoReportId")
            if not isinstance(report_id, str) or not report_id.isdecimal():
                raise ValueError("GEO image requires its authorized report ID")
            plan = validated_frozen_plan(
                self.product_creative_brief.creative_direction.get("creativePlan"), "IMAGE"
            )
            if plan.report_id != report_id:
                raise ValueError("GEO image report must match the frozen plan")
        elif self.input_snapshot.get("geoReportId") is not None:
            raise ValueError("report ID is valid only for GEO report mode")
        self.product_creative_brief.validate_reference_assets(self.reference_assets)
        if self.input_snapshot.get("sourceMode") != self.product_creative_brief.source_mode.value:
            raise ValueError("input sourceMode must match productCreativeBrief")
        if self.input_snapshot.get("referenceAssetIds") != list(
            self.product_creative_brief.reference_asset_ids
        ):
            raise ValueError("input referenceAssetIds must match productCreativeBrief")
        return self


class _VideoTaskExecutionContextResponse(_JavaWorkerModel):
    controlled_mask_asset: None = None
    task_id: SnowflakeId
    task_type: Literal["VIDEO"]
    provider: Literal["WAN_VIDEO"]
    model: Literal["wan3.0-video"]
    execution_no: PositiveInt
    snapshot_schema_version: Literal[1]
    input_snapshot: _VideoInputSnapshot
    authorized_context: _AuthorizedTaskContext
    product_creative_brief: ProductCreativeBrief
    reference_assets: tuple[CreativeReferenceAsset, ...] = Field(max_length=3)
    resume_only: StrictBool
    provider_continuation: _WanVideoProviderContinuation | None
    pricing: _TaskPricing
    budget: _VideoTaskBudget

    @field_validator("reference_assets", mode="before")
    @classmethod
    def reference_assets_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def continuation_must_match_resume_mode(self) -> _VideoTaskExecutionContextResponse:
        if self.resume_only != (self.provider_continuation is not None):
            raise ValueError("resumeOnly and providerContinuation must agree")
        self.product_creative_brief.validate_reference_assets(self.reference_assets)
        if self.product_creative_brief.source_mode is CreativeSourceMode.GEO_REPORT:
            if self.input_snapshot.geo_report_id is None:
                raise ValueError("GEO video requires its authorized report ID")
            input_plan = validated_frozen_plan(self.input_snapshot.creative_plan, "VIDEO")
            brief_plan = validated_frozen_plan(
                self.product_creative_brief.creative_direction.get("creativePlan"), "VIDEO"
            )
            if (
                input_plan != brief_plan
                or input_plan.report_id != self.input_snapshot.geo_report_id
            ):
                raise ValueError("GEO video report must match the frozen plan")
        elif (
            self.input_snapshot.geo_report_id is not None
            or self.input_snapshot.creative_plan is not None
        ):
            raise ValueError("report ID and creative plan are valid only for GEO report mode")
        if self.input_snapshot.source_mode != self.product_creative_brief.source_mode.value:
            raise ValueError("input sourceMode must match productCreativeBrief")
        if (
            self.input_snapshot.reference_asset_ids
            != self.product_creative_brief.reference_asset_ids
        ):
            raise ValueError("input referenceAssetIds must match productCreativeBrief")
        if self.product_creative_brief.source_mode is CreativeSourceMode.CURRENT_ATTACHMENTS:
            if (
                self.authorized_context.attachment_asset_ids
                != self.input_snapshot.reference_asset_ids
            ):
                raise ValueError("current attachment IDs must match the frozen references")
        elif self.authorized_context.attachment_asset_ids:
            raise ValueError("attachment IDs are valid only for current attachment mode")
        return self


class _GeoInputSnapshot(_JavaWorkerModel):
    parameters: GeoTaskParameters | GeoDiagnosticTaskParameters


class _GeoTopicSnapshot(_JavaWorkerModel):
    id: SnowflakeId
    space_id: SnowflakeId
    product_id: SnowflakeId | None
    name: Annotated[StrictStr, StringConstraints(min_length=1, max_length=100)]
    region: Annotated[StrictStr, StringConstraints(max_length=100)] | None
    version: PositiveInt


class _GeoQuestionSnapshot(_JavaWorkerModel):
    id: SnowflakeId
    category: Literal["BRAND_DISCOVERY"]
    text: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)]


class _GeoCompetitorSnapshot(_JavaWorkerModel):
    id: SnowflakeId
    name: Annotated[StrictStr, StringConstraints(min_length=1, max_length=150)]
    aliases: tuple[Annotated[StrictStr, StringConstraints(min_length=1, max_length=150)], ...]
    competitor_type: Literal["DIRECT", "INDIRECT", "SUBSTITUTE"]

    @field_validator("aliases", mode="before")
    @classmethod
    def aliases_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class _GeoAuthorizedContext(_JavaWorkerModel):
    topic: _GeoTopicSnapshot
    questions: tuple[_GeoQuestionSnapshot, ...] = Field(min_length=3, max_length=10)
    competitor_set_version: PositiveInt | None
    competitors: tuple[_GeoCompetitorSnapshot, ...]
    monitoring_target: GeoMonitoringTarget | None = None
    completed_unit_keys: tuple[GeoUnitKey, ...] = Field(max_length=210)

    @field_validator("questions", "competitors", "completed_unit_keys", mode="before")
    @classmethod
    def arrays_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def identities_must_be_unique(self) -> _GeoAuthorizedContext:
        if len(self.completed_unit_keys) != len(set(self.completed_unit_keys)):
            raise ValueError("completedUnitKeys must be unique")
        if len({item.id for item in self.questions}) != len(self.questions):
            raise ValueError("GEO question IDs must be unique")
        if len({item.id for item in self.competitors}) != len(self.competitors):
            raise ValueError("GEO competitor IDs must be unique")
        return self


class _GeoTaskBudget(_JavaWorkerModel):
    max_provider_calls: Annotated[StrictInt, Field(ge=1, le=1_000)]
    max_geo_questions: Literal[10]
    max_geo_platforms: Literal[7]
    samples_per_combination: Annotated[StrictInt, Field(ge=1, le=3)]
    max_concurrency: Annotated[StrictInt, Field(ge=1, le=20)]
    deadline_at: StrictStr

    @field_validator("deadline_at")
    @classmethod
    def deadline_must_be_an_offset_datetime(cls, value: str) -> str:
        _parse_offset_datetime(value)
        return value


class _GeoDiagnosticAuthorizedContext(GeoDiagnosticSynthesisContext):
    completed_unit_keys: tuple[
        Annotated[
            StrictStr,
            StringConstraints(
                pattern=r"^[1-9][0-9]*:(DOUBAO|DEEPSEEK|QWEN|WENXIN|KIMI|YUANBAO|ZHIPU):1$"
            ),
        ],
        ...,
    ] = Field(max_length=70)

    @field_validator("completed_unit_keys", mode="before")
    @classmethod
    def freeze_completed(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def unique_identities(self) -> _GeoDiagnosticAuthorizedContext:
        if (
            len(set(self.completed_unit_keys)) != len(self.completed_unit_keys)
            or len({item.id for item in self.questions}) != len(self.questions)
            or len({item.id for item in self.competitors}) != len(self.competitors)
        ):
            raise ValueError("Diagnostic snapshot identities must be unique")
        return self


class _GeoTaskExecutionContextResponse(_JavaWorkerModel):
    controlled_mask_asset: None = None
    task_id: SnowflakeId
    task_type: Literal["GEO"]
    execution_no: PositiveInt
    snapshot_schema_version: Literal[1]
    input_snapshot: _GeoInputSnapshot
    authorized_context: _GeoAuthorizedContext | _GeoDiagnosticAuthorizedContext
    reference_assets: tuple[CreativeReferenceAsset, ...] = Field(max_length=0)
    resume_only: Literal[False]
    provider_continuation: None
    pricing: _TaskPricing
    budget: _GeoTaskBudget

    @field_validator("reference_assets", mode="before")
    @classmethod
    def reference_assets_must_be_empty_and_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def matrix_must_match_authorized_context(self) -> _GeoTaskExecutionContextResponse:
        parameters = self.input_snapshot.parameters
        context = self.authorized_context
        diagnostic = parameters.mode == "DIAGNOSTIC_RUN"
        if diagnostic != isinstance(context, _GeoDiagnosticAuthorizedContext):
            raise ValueError("GEO mode must match its authorized context")
        if parameters.topic_id != context.topic.id:
            raise ValueError("topicId must match the authorized topic")
        if parameters.question_ids != tuple(item.id for item in context.questions):
            raise ValueError("questionIds must match authorized questions in order")
        expected_samples = 1 if parameters.sample_mode == "STANDARD" else 3
        if self.budget.samples_per_combination != expected_samples:
            raise ValueError("samplesPerCombination must match sampleMode")
        return self


ArticleKey = Annotated[
    StrictStr,
    StringConstraints(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$"),
]
TextMax300 = Annotated[StrictStr, StringConstraints(max_length=300)]


class _ArticleOutlineItem(_JavaWorkerModel):
    key: ArticleKey
    sequence: Annotated[StrictInt, Field(ge=1, le=100)]
    level: Annotated[StrictInt, Field(ge=1, le=6)]
    heading: Annotated[StrictStr, StringConstraints(min_length=1, max_length=300)]
    brief: Annotated[StrictStr, StringConstraints(max_length=1_000)] | None


class _ArticlePlanSection(_JavaWorkerModel):
    key: ArticleKey
    sequence: Annotated[StrictInt, Field(ge=1, le=12)]
    level: Annotated[StrictInt, Field(ge=1, le=6)]
    heading: Annotated[StrictStr, StringConstraints(min_length=1, max_length=300)]
    brief: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)]


class _ArticlePlan(_JavaWorkerModel):
    summary: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)]
    core_message: Annotated[StrictStr, StringConstraints(min_length=1, max_length=2_000)]
    target_audience: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)]
    sections: tuple[_ArticlePlanSection, ...] = Field(min_length=2, max_length=12)
    tone: StageCode
    target_length: Literal["SHORT", "MEDIUM", "LONG"]
    target_platform: Annotated[StrictStr, StringConstraints(min_length=1, max_length=100)] | None
    risk_notes: tuple[
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=500)], ...
    ] = Field(max_length=20)

    @field_validator("sections", "risk_notes", mode="before")
    @classmethod
    def arrays_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def sections_must_be_ordered_and_unique(self) -> _ArticlePlan:
        if tuple(item.sequence for item in self.sections) != tuple(
            range(1, len(self.sections) + 1)
        ):
            raise ValueError("ARTICLE plan section sequence must be contiguous")
        if len({item.key for item in self.sections}) != len(self.sections):
            raise ValueError("ARTICLE plan section keys must be unique")
        if len(self.risk_notes) != len(set(self.risk_notes)):
            raise ValueError("ARTICLE plan risk notes must be unique")
        return self


class _ArticleTaskParameters(_JavaWorkerModel):
    mode: Literal["TITLE", "OUTLINE", "BODY", "REWRITE"]
    article_id: SnowflakeId
    article_version: PositiveInt
    brief: Annotated[StrictStr, StringConstraints(min_length=1, max_length=4_000)]
    selected_title: TextMax300 | None
    confirmed_outline: tuple[_ArticleOutlineItem, ...] = Field(max_length=100)
    confirmed_plan: _ArticlePlan | None
    rewrite_instruction: Annotated[StrictStr, StringConstraints(max_length=4_000)] | None
    rewrite_block_ids: tuple[ArticleKey, ...] = Field(max_length=100)
    target_length: Literal["SHORT", "MEDIUM", "LONG"]
    tone: StageCode
    target_platform: Annotated[StrictStr, StringConstraints(min_length=1, max_length=100)] | None
    source_asset_ids: tuple[SnowflakeId, ...] = Field(max_length=10)
    geo_report_id: SnowflakeId | None
    # Java validates this recommendation against its frozen GEO report before
    # dispatch. Keep it as authorization metadata, not model prompt input.
    geo_recommendation_id: Text128 | None = None

    @field_validator("confirmed_outline", "rewrite_block_ids", "source_asset_ids", mode="before")
    @classmethod
    def arrays_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def stage_shape_and_collections_must_match(self) -> _ArticleTaskParameters:
        if len({item.key for item in self.confirmed_outline}) != len(self.confirmed_outline):
            raise ValueError("ARTICLE outline keys must be unique")
        if tuple(item.sequence for item in self.confirmed_outline) != tuple(
            range(1, len(self.confirmed_outline) + 1)
        ):
            raise ValueError("ARTICLE outline sequence must be contiguous")
        if len(self.rewrite_block_ids) != len(set(self.rewrite_block_ids)) or len(
            self.source_asset_ids
        ) != len(set(self.source_asset_ids)):
            raise ValueError("ARTICLE identifiers must be unique")
        selected = self.selected_title is not None and bool(self.selected_title.strip())
        rewrite = self.rewrite_instruction is not None and bool(self.rewrite_instruction.strip())
        if self.mode == "TITLE":
            if (
                selected
                or self.confirmed_outline
                or self.confirmed_plan is not None
                or rewrite
                or self.rewrite_block_ids
            ):
                raise ValueError("TITLE parameters include later-stage state")
        elif self.mode == "OUTLINE":
            if (
                not selected
                or self.confirmed_outline
                or self.confirmed_plan is not None
                or rewrite
                or self.rewrite_block_ids
            ):
                raise ValueError("OUTLINE parameters are invalid")
        elif self.mode == "BODY":
            if (
                not selected
                or not self.confirmed_outline
                or self.confirmed_plan is None
                or rewrite
                or self.rewrite_block_ids
            ):
                raise ValueError("BODY parameters are invalid")
        elif (
            not selected
            or not self.confirmed_outline
            or self.confirmed_plan is None
            or not rewrite
            or not self.rewrite_block_ids
            or not set(self.rewrite_block_ids).issubset(
                {item.key for item in self.confirmed_outline}
            )
        ):
            raise ValueError("REWRITE parameters are invalid")
        if self.confirmed_plan is not None:
            plan_sections = tuple(
                (item.key, item.sequence, item.level, item.heading, item.brief)
                for item in self.confirmed_plan.sections
            )
            outline_sections = tuple(
                (item.key, item.sequence, item.level, item.heading, item.brief)
                for item in self.confirmed_outline
            )
            if plan_sections != outline_sections:
                raise ValueError("ARTICLE confirmed plan sections must match confirmed outline")
            if (
                self.confirmed_plan.tone != self.tone
                or self.confirmed_plan.target_length != self.target_length
                or self.confirmed_plan.target_platform != self.target_platform
            ):
                raise ValueError("ARTICLE confirmed plan constraints must match parameters")
        return self


class _ArticleInputSnapshot(_JavaWorkerModel):
    parameters: _ArticleTaskParameters


class _ArticleAuthorizedArticle(_JavaWorkerModel):
    id: SnowflakeId
    version: PositiveInt
    title: TextMax300
    outline: tuple[_ArticleOutlineItem, ...] = Field(max_length=100)
    confirmed_plan: _ArticlePlan | None
    current_body: _ArticleCurrentBody | None = None
    current_version_ref: PositiveInt | None = None
    rewrite_block_ids: tuple[StrictStr, ...] = Field(default=(), min_length=0, max_length=100)

    @field_validator("outline", "rewrite_block_ids", mode="before")
    @classmethod
    def arrays_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def optional_objects_must_be_omitted_instead_of_null(self) -> _ArticleAuthorizedArticle:
        for field_name in ("current_body", "current_version_ref"):
            if field_name in self.model_fields_set and getattr(self, field_name) is None:
                raise ValueError("ARTICLE optional authority fields cannot be null")
        return self


class _ArticleCurrentBody(_JavaWorkerModel):
    html: Annotated[StrictStr, StringConstraints(max_length=524_288)]
    text: Annotated[StrictStr, StringConstraints(max_length=524_288)]


class _ArticleSourceAsset(_JavaWorkerModel):
    id: SnowflakeId
    asset_type: Literal["IMAGE"]
    name: Annotated[StrictStr, StringConstraints(min_length=1, max_length=300)]
    content_type: Annotated[StrictStr, StringConstraints(pattern=r"^image/")]
    sha256: Annotated[StrictStr, StringConstraints(pattern=r"^[0-9a-f]{64}$")]


class _ArticleGeoRecommendation(_JavaWorkerModel):
    # Java's saved-report projection adds an opaque recommendation reference.
    # Older snapshots may omit it; it is checked against the frozen report below,
    # never forwarded to the article model or used as execution authority.
    id: (
        Annotated[
            StrictStr,
            StringConstraints(pattern=r"^[1-9][0-9]{0,18}:recommendation:(?:[1-9]|[12][0-9]|30)$"),
        ]
        | None
    ) = None
    priority: Literal["P0", "P1", "P2"]
    title: Annotated[StrictStr, StringConstraints(min_length=1, max_length=300)]
    rationale: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)]


class _ArticleGeoReport(_JavaWorkerModel):
    id: SnowflakeId
    summary: Annotated[StrictStr, StringConstraints(min_length=1, max_length=4_000)]
    method_version: Annotated[StrictStr, StringConstraints(min_length=1, max_length=128)]
    coverage_gaps: tuple[
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)], ...
    ] = Field(default=(), max_length=20)
    findings: tuple[
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)], ...
    ] = Field(default=(), max_length=50)
    recommendations: tuple[_ArticleGeoRecommendation, ...] = Field(default=(), max_length=30)
    content_blueprint: tuple[
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)], ...
    ] = Field(default=(), max_length=50)
    missing_samples: tuple[
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)], ...
    ] = Field(default=(), max_length=50)

    @field_validator(
        "coverage_gaps",
        "findings",
        "recommendations",
        "content_blueprint",
        "missing_samples",
        mode="before",
    )
    @classmethod
    def arrays_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def recommendations_must_reference_frozen_report(self) -> _ArticleGeoReport:
        if any(
            item.id is not None and item.id.split(":", 1)[0] != self.id
            for item in self.recommendations
        ):
            raise ValueError("ARTICLE recommendation must belong to the authorized GEO report")
        return self


class _ArticleAuthorizedContext(_JavaWorkerModel):
    space: SpaceSnapshot
    product: ProductSnapshot | None
    article: _ArticleAuthorizedArticle
    source_assets: tuple[_ArticleSourceAsset, ...] = Field(max_length=10)
    geo_report: _ArticleGeoReport | None
    knowledge: KnowledgeContext | None = None
    untrusted_research: WebResearchEvidence | None = None
    confirmed_facts: tuple[ArticleConfirmedFacts, ...] = Field(default=(), max_length=3)

    @field_validator("source_assets", "confirmed_facts", mode="before")
    @classmethod
    def arrays_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def relationships_must_be_authorized(self) -> _ArticleAuthorizedContext:
        if self.product is not None and self.product.space_id != self.space.id:
            raise ValueError("ARTICLE product must belong to the authorized space")
        if self.knowledge is not None:
            self.knowledge.validate_project_binding(self.space.id)
        for evidence in self.confirmed_facts:
            if evidence.project_id != self.space.id:
                raise ValueError("confirmed article facts must belong to the authorized project")
            if (
                self.knowledge is not None
                and self.knowledge.memory is not None
                and self.knowledge.memory.history_cutoff_at is not None
                and datetime.fromisoformat(evidence.source_created_at)
                < self.knowledge.memory.history_cutoff_at
            ):
                raise ValueError("confirmed article facts predate the memory boundary")
        if len({item.id for item in self.source_assets}) != len(self.source_assets):
            raise ValueError("ARTICLE source assets must be unique")
        return self


class _ArticleTaskBudget(_JavaWorkerModel):
    max_provider_calls: Literal[0]
    max_model_calls: Annotated[StrictInt, Field(ge=1, le=10)]
    max_repair_retries: Annotated[StrictInt, Field(ge=0, le=5)]
    max_input_tokens: Annotated[StrictInt, Field(ge=1, le=200_000)]
    max_output_tokens: Annotated[StrictInt, Field(ge=1, le=64_000)]
    deadline_at: StrictStr

    @field_validator("deadline_at")
    @classmethod
    def deadline_must_be_an_offset_datetime(cls, value: str) -> str:
        _parse_offset_datetime(value)
        return value


class _ArticleTaskExecutionContextResponse(_JavaWorkerModel):
    controlled_mask_asset: None = None
    task_id: SnowflakeId
    task_type: Literal["ARTICLE"]
    provider: Literal["DEEPSEEK"]
    model: Annotated[StrictStr, StringConstraints(min_length=1, max_length=128)] | None = None
    execution_no: PositiveInt
    snapshot_schema_version: Literal[2]
    # Java 4fee5b6 documents {"parameters": ...}, while its committed
    # TaskControlService serializes snapshot.path("parameters") directly.
    # Keep both exact, closed shapes until Java removes the wire-level drift.
    input_snapshot: _ArticleInputSnapshot | _ArticleTaskParameters
    authorized_context: _ArticleAuthorizedContext
    reference_assets: tuple[CreativeReferenceAsset, ...] = Field(max_length=0)
    resume_only: Literal[False]
    provider_continuation: None
    pricing: _TaskPricing
    budget: _ArticleTaskBudget

    @field_validator("reference_assets", mode="before")
    @classmethod
    def reference_assets_must_be_empty_and_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def authority_must_match_parameters(self) -> _ArticleTaskExecutionContextResponse:
        if "model" in self.model_fields_set and self.model is None:
            raise ValueError("ARTICLE optional model cannot be null")
        parameters = (
            self.input_snapshot.parameters
            if isinstance(self.input_snapshot, _ArticleInputSnapshot)
            else self.input_snapshot
        )
        authorized = self.authorized_context
        article = authorized.article
        if parameters.article_id != article.id or parameters.article_version != article.version:
            raise ValueError("ARTICLE identity/version mismatch")
        if parameters.source_asset_ids != tuple(item.id for item in authorized.source_assets):
            raise ValueError("ARTICLE source assets mismatch")
        report_id = None if authorized.geo_report is None else authorized.geo_report.id
        if parameters.geo_report_id != report_id:
            raise ValueError("ARTICLE GEO report mismatch")
        recommendation_id = parameters.geo_recommendation_id
        if recommendation_id is not None:
            prefix = f"{report_id}:recommendation:"
            if report_id is None or not recommendation_id.startswith(prefix):
                raise ValueError("ARTICLE GEO recommendation/report mismatch")
            ordinal = recommendation_id[len(prefix) :]
            digits = ordinal.removeprefix("+")
            if (
                not digits.isascii()
                or not digits.isdecimal()
                or not 1 <= int(digits) <= 2_147_483_647
            ):
                raise ValueError("ARTICLE GEO recommendation ordinal is invalid")
        if parameters.mode in {"OUTLINE", "BODY", "REWRITE"} and (
            parameters.selected_title != article.title
        ):
            raise ValueError("ARTICLE selected title mismatch")
        if parameters.mode in {"BODY", "REWRITE"}:
            if parameters.confirmed_outline != article.outline:
                raise ValueError("ARTICLE confirmed outline mismatch")
            if parameters.confirmed_plan != article.confirmed_plan:
                raise ValueError("ARTICLE confirmed plan mismatch")
        elif parameters.confirmed_outline:
            raise ValueError("ARTICLE unexpected confirmed outline")
        elif parameters.confirmed_plan is not None or article.confirmed_plan is not None:
            raise ValueError("ARTICLE unexpected confirmed plan")
        rewrite_fields = (
            article.current_body is not None,
            article.current_version_ref is not None,
            bool(article.rewrite_block_ids),
        )
        if parameters.mode == "REWRITE":
            if not all(rewrite_fields):
                raise ValueError("ARTICLE rewrite authority is incomplete")
            if article.current_version_ref != article.version:
                raise ValueError("ARTICLE rewrite version mismatch")
            if parameters.rewrite_block_ids != article.rewrite_block_ids:
                raise ValueError("ARTICLE rewrite scope mismatch")
        elif any(rewrite_fields):
            raise ValueError("ARTICLE rewrite authority leaked into another stage")
        return self


_TaskExecutionContextResponse = (
    _ImageTaskExecutionContextResponse
    | _VideoTaskExecutionContextResponse
    | _GeoTaskExecutionContextResponse
    | _ArticleTaskExecutionContextResponse
)


class _TaskHeartbeatRequest(_JavaWorkerModel):
    execution_no: PositiveInt
    execution_token: ExecutionToken
    stage_code: StageCode


class _TaskExecutionIdentity(_JavaWorkerModel):
    execution_no: PositiveInt
    execution_token: ExecutionToken


class _TaskHeartbeatResponse(_JavaWorkerModel):
    accepted: StrictBool
    lease_expires_at: StrictStr

    @field_validator("lease_expires_at")
    @classmethod
    def lease_must_be_an_offset_datetime(cls, value: str) -> str:
        _parse_offset_datetime(value)
        return value

    @model_validator(mode="after")
    def heartbeat_must_be_accepted(self) -> _TaskHeartbeatResponse:
        if not self.accepted:
            raise ValueError("Java rejected the heartbeat")
        return self


class _ArticlePreview(_JavaWorkerModel):
    attempt: Annotated[StrictInt, Field(ge=1, le=10)]
    sequence: Annotated[StrictInt, Field(ge=1, le=10_000)]
    markdown: Annotated[StrictStr, StringConstraints(max_length=120_000)]


class _TaskExecutionEventRequest(_JavaWorkerModel):
    event_id: UUID
    execution_no: PositiveInt
    execution_token: ExecutionToken
    event_type: Literal[
        "TASK_PROGRESS",
        "PROVIDER_ACCEPTED",
        "USAGE_REPORTED",
        "TASK_FAILED",
    ]
    stage_code: StageCode
    details: dict[str, JsonValue] | None = Field(default=None, max_length=64)

    @field_validator("details", mode="before")
    @classmethod
    def details_must_be_a_json_object(cls, value: object) -> object:
        if value is not None:
            _validate_json_value(value, require_object=True)
        return value

    @model_validator(mode="after")
    def failure_details_must_match_java_contract(self) -> _TaskExecutionEventRequest:
        if self.stage_code == "ARTICLE_BODY_STREAMING":
            if (
                self.event_type != "TASK_PROGRESS"
                or self.details is None
                or set(self.details) != {"articlePreview"}
            ):
                raise ValueError("ARTICLE_BODY_STREAMING requires exact preview details")
            _ArticlePreview.model_validate(self.details["articlePreview"])
        if self.event_type == "TASK_FAILED":
            if self.details is None:
                raise ValueError("TASK_FAILED requires exact stable details")
            code = self.details.get("code")
            submission = self.details.get("submissionState")
            if not isinstance(code, str) or not _matches_stage_code(code):
                raise ValueError("TASK_FAILED details are invalid")
            if submission == "UNKNOWN":
                if self.stage_code != "PROVIDER_RESULT_UNKNOWN" or set(self.details) != {
                    "code",
                    "provider",
                    "model",
                    "providerRequestId",
                    "providerTaskId",
                    "submissionState",
                }:
                    raise ValueError("UNKNOWN failure requires exact Provider identity")
                failure_payload = {
                    "provider": self.details["provider"],
                    "model": self.details["model"],
                    "providerRequestId": self.details["providerRequestId"],
                    "providerTaskId": self.details["providerTaskId"],
                    "submissionState": submission,
                }
                failure = _UnknownProviderFailure.model_validate(failure_payload)
                if failure.provider == "DEEPSEEK" and code not in {
                    "ARTICLE_DEADLINE_EXCEEDED",
                    "DEEPSEEK_SUBMISSION_UNKNOWN",
                }:
                    raise ValueError("ARTICLE uncertainty requires an exact stable failure code")
            elif (
                submission not in {"NOT_SUBMITTED", "CONFIRMED"}
                or self.stage_code != "FAILED"
                or set(self.details) != {"code", "submissionState"}
            ):
                raise ValueError("terminal failure requires exact stable details")
        return self


class _ImageMockMetadata(_JavaWorkerModel):
    width: Literal[1024]
    height: Literal[1024]
    mock: Literal[True]


class _ImageMockArtifact(_JavaWorkerModel):
    artifact_type: Literal["IMAGE"]
    unit_key: Annotated[StrictStr, StringConstraints(max_length=256)] | None = None
    schema_version: Literal[1]
    provider_url: Annotated[StrictStr, StringConstraints(max_length=2_048)]
    content_type: Literal["image/png"]
    size_bytes: Annotated[StrictInt, Field(ge=0)] | None = None
    sha256: Annotated[StrictStr, StringConstraints(pattern=r"^[0-9a-f]{64}$")] | None = None
    expires_at: datetime | None = None
    inline_data: None = None
    metadata: _ImageMockMetadata

    @field_validator("expires_at")
    @classmethod
    def expiry_must_include_a_timezone(cls, value: datetime | None) -> datetime | None:
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("expiresAt must include a timezone")
        return value


class _ImageMockUsage(_JavaWorkerModel):
    input_tokens: Literal[0]
    output_tokens: Literal[0]
    images: Annotated[StrictInt, Field(ge=1, le=4)]
    video_seconds: Literal[0]
    provider_cost: Literal["0.0000"]
    currency: Literal["CNY"]


class _ImageMockResultRequest(_JavaWorkerModel):
    event_id: UUID
    execution_no: PositiveInt
    execution_token: ExecutionToken
    provider: Literal["LOCAL_TASK_PROVIDER"]
    model: Literal["deterministic-provider-mock-v1"]
    request_id: UUID
    provider_task_id: Annotated[
        StrictStr,
        StringConstraints(
            max_length=256,
            pattern=(
                r"^mock-image-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                r"[0-9a-f]{4}-[0-9a-f]{12}$"
            ),
        ),
    ]
    submission_state: Literal[SubmissionState.CONFIRMED]
    artifacts: tuple[_ImageMockArtifact, ...] = Field(min_length=1, max_length=4)
    usage: _ImageMockUsage
    latency_ms: Annotated[StrictInt, Field(ge=0)]

    @model_validator(mode="after")
    def identifiers_and_url_must_refer_to_the_same_request(self) -> _ImageMockResultRequest:
        request_id = str(self.request_id)
        if self.provider_task_id != f"mock-image-{request_id}":
            raise ValueError("providerTaskId does not match requestId")
        if self.usage.images != len(self.artifacts):
            raise ValueError("usage.images must match actual mock artifacts")
        for index, artifact in enumerate(self.artifacts, start=1):
            suffix = "" if index == 1 else f"-{index}"
            if artifact.provider_url != (
                f"https://mock-provider.invalid/image/{request_id}{suffix}.png"
            ) or artifact.unit_key not in (None, f"image:{index}"):
                raise ValueError("Mock artifact URL and unitKey must match requestId and index")
        return self


class _ImageRealMetadata(_JavaWorkerModel):
    index: Annotated[StrictInt, Field(ge=1, le=4)]
    width: Annotated[StrictInt, Field(ge=8, le=8_192)] | None
    height: Annotated[StrictInt, Field(ge=8, le=8_192)] | None
    mock: Literal[False]
    output_image_type: (
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=128)] | None
    ) = None
    provider_request_id: Annotated[StrictStr, StringConstraints(min_length=1, max_length=256)]
    provider_result_request_id: (
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=256)] | None
    ) = None


class _ImageRealArtifact(_JavaWorkerModel):
    artifact_type: Literal["IMAGE"]
    unit_key: Annotated[StrictStr, StringConstraints(pattern=r"^image:[1-4]$")] | None = None
    schema_version: Literal[1]
    provider_url: Annotated[StrictStr, StringConstraints(max_length=2_048)]
    content_type: Literal["image/png"] | None
    size_bytes: Annotated[StrictInt, Field(ge=0)] | None = None
    sha256: Annotated[StrictStr, StringConstraints(pattern=r"^[0-9a-f]{64}$")] | None = None
    expires_at: datetime
    inline_data: None = None
    metadata: _ImageRealMetadata

    @field_validator("provider_url")
    @classmethod
    def provider_url_must_be_safe_https(cls, value: str) -> str:
        parsed = urlsplit(value)
        if (
            parsed.scheme != "https"
            or parsed.hostname is None
            or parsed.username is not None
            or parsed.password is not None
        ):
            raise ValueError("providerUrl must be a credential-free HTTPS URL")
        return value

    @field_validator("expires_at")
    @classmethod
    def expiry_must_include_a_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("expiresAt must include a timezone")
        if value <= datetime.now(UTC):
            raise ValueError("expiresAt must be in the future")
        return value


class _ImageRealUsage(_JavaWorkerModel):
    input_tokens: Literal[0]
    output_tokens: Literal[0]
    images: Annotated[StrictInt, Field(ge=1, le=4)]
    video_seconds: Literal[0]
    provider_cost: None
    currency: None


class _ImageRealResultRequest(_JavaWorkerModel):
    event_id: UUID
    execution_no: PositiveInt
    execution_token: ExecutionToken
    provider: Literal["QWEN_IMAGE", "WAN_IMAGE_EDIT"]
    model: Literal["qwen-image-3.0", "wanx2.1-imageedit"]
    request_id: UUID
    provider_task_id: Annotated[
        StrictStr,
        StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,256}$"),
    ]
    submission_state: Literal[SubmissionState.CONFIRMED]
    artifacts: tuple[_ImageRealArtifact, ...] = Field(min_length=1, max_length=4)
    usage: _ImageRealUsage
    latency_ms: Annotated[StrictInt, Field(ge=0)]

    @model_validator(mode="after")
    def image_evidence_must_match_actual_artifacts(self) -> _ImageRealResultRequest:
        if (self.provider, self.model) not in {
            ("QWEN_IMAGE", "qwen-image-3.0"),
            ("WAN_IMAGE_EDIT", "wanx2.1-imageedit"),
        }:
            raise ValueError("provider and model must identify the same adapter")
        if self.usage.images != len(self.artifacts):
            raise ValueError("usage.images must match actual artifacts")
        if len({artifact.provider_url for artifact in self.artifacts}) != len(self.artifacts):
            raise ValueError("image artifact URLs must be unique")
        for index, artifact in enumerate(self.artifacts, start=1):
            if self.provider == "QWEN_IMAGE" and (
                artifact.content_type != "image/png"
                or artifact.metadata.width is None
                or artifact.metadata.height is None
            ):
                raise ValueError("Qwen results require known image dimensions and PNG MIME")
            if self.provider == "WAN_IMAGE_EDIT" and any(
                value is not None
                for value in (
                    artifact.content_type,
                    artifact.size_bytes,
                    artifact.sha256,
                    artifact.metadata.width,
                    artifact.metadata.height,
                    artifact.metadata.output_image_type,
                )
            ):
                raise ValueError("Wan edit must preserve unreported metadata as unknown")
            if artifact.metadata.index != index or artifact.unit_key not in (
                None,
                f"image:{index}",
            ):
                raise ValueError("image artifact indices must be contiguous and ordered")
            if artifact.metadata.provider_request_id != str(self.request_id):
                raise ValueError("image artifact must belong to the same request")
        return self


class _WanVideoMetadata(_JavaWorkerModel):
    duration_seconds: Annotated[float, Field(gt=0, le=30.75)]
    requested_duration_seconds: Annotated[StrictInt, Field(ge=2, le=30)]
    requested_aspect_ratio: Literal["adaptive", "16:9", "4:3", "1:1", "3:4", "9:16"]
    requested_resolution: Literal["480P", "720P", "1080P"]
    mock: Literal[False]
    provider_request_id: StrictStr
    provider_result_request_id: (
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=256)] | None
    ) = None
    fps: Annotated[float, Field(gt=0)] | None = None
    resolution: Annotated[StrictStr, StringConstraints(min_length=1, max_length=32)] | None = None
    aspect_ratio: Annotated[StrictStr, StringConstraints(min_length=1, max_length=32)] | None = None

    @field_validator("provider_request_id")
    @classmethod
    def provider_request_id_must_be_canonical_uuid(cls, value: str) -> str:
        try:
            parsed = UUID(value)
        except ValueError as exc:
            raise ValueError("providerRequestId must be a UUID") from exc
        if str(parsed) != value:
            raise ValueError("providerRequestId must use canonical UUID text")
        return value


class _WanVideoArtifact(_JavaWorkerModel):
    artifact_type: Literal["VIDEO"]
    unit_key: Literal["video:1"] | None = None
    schema_version: Literal[1]
    provider_url: Annotated[StrictStr, StringConstraints(max_length=2_048)]
    content_type: Literal["video/mp4"]
    size_bytes: Annotated[StrictInt, Field(ge=0)] | None = None
    sha256: Annotated[StrictStr, StringConstraints(pattern=r"^[0-9a-f]{64}$")] | None = None
    expires_at: None = None
    inline_data: None = None
    metadata: _WanVideoMetadata

    @field_validator("provider_url")
    @classmethod
    def provider_url_must_be_safe_https(cls, value: str) -> str:
        parsed = urlsplit(value)
        if (
            parsed.scheme != "https"
            or parsed.hostname is None
            or parsed.username is not None
            or parsed.password is not None
        ):
            raise ValueError("providerUrl must be a credential-free HTTPS URL")
        return value


class _WanVideoUsage(_JavaWorkerModel):
    input_tokens: Literal[0]
    output_tokens: Literal[0]
    images: Literal[0]
    video_seconds: Annotated[StrictInt, Field(ge=2, le=30)]
    provider_cost: None
    currency: None


class _WanVideoResultRequest(_JavaWorkerModel):
    event_id: UUID
    execution_no: PositiveInt
    execution_token: ExecutionToken
    provider: Literal["WAN_VIDEO"]
    model: Literal["wan3.0-video"]
    request_id: UUID
    provider_task_id: Annotated[
        StrictStr,
        StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,256}$"),
    ]
    submission_state: Literal[SubmissionState.CONFIRMED]
    artifacts: tuple[_WanVideoArtifact, ...] = Field(min_length=1, max_length=1)
    usage: _WanVideoUsage
    latency_ms: Annotated[StrictInt, Field(ge=0)]

    @model_validator(mode="after")
    def provider_identity_and_duration_must_be_consistent(self) -> _WanVideoResultRequest:
        metadata = self.artifacts[0].metadata
        if metadata.provider_request_id != str(self.request_id):
            raise ValueError("providerRequestId must match requestId")
        if metadata.requested_duration_seconds != self.usage.video_seconds:
            raise ValueError("Provider usage must match the requested video duration")
        if abs(metadata.duration_seconds - self.usage.video_seconds) > 0.75:
            raise ValueError("Provider duration must match usage within the Java tolerance")
        return self


class _ArticleResultOutlineItem(_JavaWorkerModel):
    key: ArticleKey
    sequence: Annotated[StrictInt, Field(ge=1, le=100)]
    level: Annotated[StrictInt, Field(ge=1, le=6)]
    heading: Annotated[StrictStr, StringConstraints(min_length=1, max_length=300)]
    brief: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)]


class _ArticleBodyBlock(_JavaWorkerModel):
    key: ArticleKey
    heading: Annotated[StrictStr, StringConstraints(min_length=1, max_length=300)]
    markdown: Annotated[StrictStr, StringConstraints(min_length=1, max_length=131_072)]


class _ArticleBodyResult(_JavaWorkerModel):
    markdown: Annotated[StrictStr, StringConstraints(min_length=1, max_length=524_288)]
    blocks: tuple[_ArticleBodyBlock, ...] = Field(min_length=1, max_length=100)

    @field_validator("blocks", mode="before")
    @classmethod
    def blocks_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class _ArticleResultUsage(_JavaWorkerModel):
    model_call_count: Annotated[StrictInt, Field(ge=1, le=10)]
    input_tokens: Annotated[StrictInt, Field(ge=0)]
    output_tokens: Annotated[StrictInt, Field(ge=0)]
    latency_ms: Annotated[StrictInt, Field(ge=0, le=3_600_000)]
    monetary_cost: (
        Annotated[StrictStr, StringConstraints(pattern=r"^[0-9]+(?:\.[0-9]{1,4})?$")] | None
    )
    currency: Annotated[StrictStr, StringConstraints(pattern=r"^[A-Z]{3}$")] | None

    @model_validator(mode="after")
    def cost_and_currency_must_move_together(self) -> _ArticleResultUsage:
        if (self.monetary_cost is None) != (self.currency is None):
            raise ValueError("ARTICLE cost and currency must move together")
        return self


class _ArticleResultMetadata(_JavaWorkerModel):
    proposal_only: Literal[True]
    persisted: Literal[False]
    published: Literal[False]
    authorized_rewrite_block_ids: tuple[ArticleKey, ...] = Field(max_length=100)

    @field_validator("authorized_rewrite_block_ids", mode="before")
    @classmethod
    def identifiers_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class _ArticleContentResult(_JavaWorkerModel):
    mode: Literal["TITLE", "OUTLINE", "BODY", "REWRITE"]
    article_version: PositiveInt
    title_candidates: tuple[
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=300)], ...
    ] = Field(max_length=5)
    title: TextMax300 | None
    plan: _ArticlePlan | None
    outline: tuple[_ArticleResultOutlineItem, ...] = Field(max_length=100)
    body: _ArticleBodyResult | None
    provider: Literal["DEEPSEEK"]
    model: Annotated[
        StrictStr,
        StringConstraints(pattern=r"^deepseek-[A-Za-z0-9._-]{1,112}$"),
    ]
    model_version: Annotated[StrictStr, StringConstraints(min_length=1, max_length=128)]
    prompt_version: Literal[
        "agent-bundle-v1.11.0",
        "agent-bundle-v1.12.0",
        "agent-bundle-v1.13.0",
        "agent-bundle-v1.14.0",
        "agent-bundle-v1.15.0",
        "agent-bundle-v1.16.0",
        "agent-bundle-v1.17.0",
        "agent-bundle-v1.18.0",
        "agent-bundle-v1.19.0",
        "agent-bundle-v1.20.0",
        "agent-bundle-v1.21.0",
        "agent-bundle-v1.22.0",
        "agent-bundle-v1.23.0",
        "agent-bundle-v1.24.0",
        "agent-bundle-v1.25.0",
        "agent-bundle-v1.26.0",
        "agent-bundle-v1.27.0",
        "agent-bundle-v1.27.1",
        "agent-bundle-v1.28.0",
        "agent-bundle-v1.29.0",
        "agent-bundle-v1.30.0",
        "agent-bundle-v1.31.0",
        "agent-bundle-v1.32.0",
        "agent-bundle-v1.33.0",
        "agent-bundle-v1.33.1",
        "agent-bundle-v1.33.2",
        "agent-bundle-v1.33.3",
        "agent-bundle-v1.33.4",
        "agent-bundle-v1.33.5",
        "agent-bundle-v1.33.6",
        "agent-bundle-v1.33.7",
        "agent-bundle-v1.33.8",
        "agent-bundle-v1.33.9",
        "agent-bundle-v1.33.10",
        "agent-bundle-v1.33.11",
        "agent-bundle-v1.33.12",
        "agent-bundle-v1.33.13",
        "agent-bundle-v1.40.0",
        "agent-bundle-v1.41.0",
        "agent-bundle-v1.42.0",
        "agent-bundle-v1.46.3",
        "agent-bundle-v1.46.4",
        "agent-bundle-v1.47.0",
        "agent-bundle-v1.48.0",
        "agent-bundle-v1.48.1",
        "agent-bundle-v1.48.2",
        "agent-bundle-v1.48.3",
        "agent-bundle-v1.49.0",
        "agent-bundle-v1.50.0",
        "agent-bundle-v1.51.0",
        "agent-bundle-v1.52.0",
        "agent-bundle-v1.53.0",
        "agent-bundle-v1.54.0",
        "agent-bundle-v1.55.0",
        "agent-bundle-v1.56.0",
        "agent-bundle-v1.58.0",
        "agent-bundle-v1.59.0",
        "agent-bundle-v1.60.0",
        "agent-bundle-v1.61.0",
        "agent-bundle-v1.62.0",
        "agent-bundle-v1.62.1",
    ]
    usage: _ArticleResultUsage
    warnings: tuple[Annotated[StrictStr, StringConstraints(min_length=1, max_length=500)], ...] = (
        Field(max_length=20)
    )
    metadata: _ArticleResultMetadata

    @field_validator("title_candidates", "outline", "warnings", mode="before")
    @classmethod
    def arrays_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def stage_shape_must_match(self) -> _ArticleContentResult:
        if len(self.title_candidates) != len(set(self.title_candidates)):
            raise ValueError("ARTICLE title candidates must be unique")
        if tuple(item.sequence for item in self.outline) != tuple(range(1, len(self.outline) + 1)):
            raise ValueError("ARTICLE outline sequence must be contiguous")
        if len({item.key for item in self.outline}) != len(self.outline):
            raise ValueError("ARTICLE outline keys must be unique")
        if self.mode == "TITLE":
            valid = (
                len(self.title_candidates) == 5
                and self.title is None
                and self.plan is None
                and not self.outline
                and self.body is None
            )
        elif self.mode == "OUTLINE":
            valid = (
                not self.title_candidates
                and bool(self.title)
                and self.plan is not None
                and bool(self.outline)
                and tuple(
                    (item.key, item.sequence, item.level, item.heading, item.brief)
                    for item in self.plan.sections
                )
                == tuple(
                    (item.key, item.sequence, item.level, item.heading, item.brief)
                    for item in self.outline
                )
                and self.body is None
            )
        else:
            valid = (
                not self.title_candidates
                and bool(self.title)
                and self.plan is None
                and bool(self.outline)
                and self.body is not None
            )
        if not valid:
            raise ValueError("ARTICLE result stage shape is invalid")
        body_keys = () if self.body is None else tuple(item.key for item in self.body.blocks)
        if self.mode == "REWRITE" and body_keys != self.metadata.authorized_rewrite_block_ids:
            raise ValueError("ARTICLE rewrite result scope is invalid")
        if self.mode != "REWRITE" and self.metadata.authorized_rewrite_block_ids:
            raise ValueError("ARTICLE rewrite scope leaked into another result")
        return self


class _ArticleTopLevelUsage(_JavaWorkerModel):
    input_tokens: Annotated[StrictInt, Field(ge=0)]
    output_tokens: Annotated[StrictInt, Field(ge=0)]
    images: Literal[0]
    video_seconds: Literal[0]
    provider_cost: (
        Annotated[StrictStr, StringConstraints(pattern=r"^[0-9]+(?:\.[0-9]{1,4})?$")] | None
    )
    currency: Annotated[StrictStr, StringConstraints(pattern=r"^[A-Z]{3}$")] | None

    @model_validator(mode="after")
    def cost_and_currency_must_move_together(self) -> _ArticleTopLevelUsage:
        if (self.provider_cost is None) != (self.currency is None):
            raise ValueError("ARTICLE cost and currency must move together")
        return self


class _ArticleArtifact(_JavaWorkerModel):
    artifact_type: Literal["ARTICLE_CONTENT"]
    unit_key: Literal["article:title", "article:outline", "article:body", "article:rewrite"]
    schema_version: Literal[2]
    inline_data: _ArticleContentResult


class _ArticleContentResultRequest(_JavaWorkerModel):
    event_id: UUID
    execution_no: PositiveInt
    execution_token: ExecutionToken
    provider: Literal["DEEPSEEK"]
    model: Annotated[
        StrictStr,
        StringConstraints(pattern=r"^deepseek-[A-Za-z0-9._-]{1,112}$"),
    ]
    request_id: UUID
    provider_task_id: None
    submission_state: Literal[SubmissionState.CONFIRMED]
    artifacts: tuple[_ArticleArtifact, ...] = Field(min_length=1, max_length=1)
    usage: _ArticleTopLevelUsage
    latency_ms: Annotated[StrictInt, Field(ge=0, le=3_600_000)]

    @field_validator("artifacts", mode="before")
    @classmethod
    def artifacts_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def evidence_must_be_consistent(self) -> _ArticleContentResultRequest:
        result = self.artifacts[0].inline_data
        if self.artifacts[0].unit_key != f"article:{result.mode.casefold()}":
            raise ValueError("ARTICLE unit key does not match mode")
        if result.model != self.model or result.provider != self.provider:
            raise ValueError("ARTICLE Provider identity is inconsistent")
        if (
            result.usage.input_tokens != self.usage.input_tokens
            or result.usage.output_tokens != self.usage.output_tokens
            or result.usage.latency_ms != self.latency_ms
            or result.usage.monetary_cost != self.usage.provider_cost
            or result.usage.currency != self.usage.currency
        ):
            raise ValueError("ARTICLE usage evidence is inconsistent")
        return self


class _InternalTaskAccepted(_JavaWorkerModel):
    accepted: StrictBool
    task_status: JavaTaskStatus

    @model_validator(mode="after")
    def response_must_be_accepted(self) -> _InternalTaskAccepted:
        if not self.accepted:
            raise ValueError("Java rejected task evidence")
        return self


class _GeoObservationInput(_JavaWorkerModel):
    platform_code: GeoPlatform
    question_id: SnowflakeId
    sample_no: Annotated[StrictInt, Field(ge=1, le=3)]
    status: Literal["SUCCEEDED", "FAILED"]
    answer_text: Annotated[StrictStr, StringConstraints(min_length=1, max_length=100_000)] | None
    citations: tuple[GeoCitation, ...] = Field(max_length=50)
    provider: GeoPlatform
    model: Annotated[StrictStr, StringConstraints(min_length=1, max_length=128)]
    collection_method: (
        Annotated[StrictStr, StringConstraints(pattern=r"^[a-z][a-z0-9-]{0,127}$")] | None
    ) = None
    started_at: datetime
    finished_at: datetime
    latency_ms: Annotated[StrictInt, Field(ge=0, le=3_600_000)]
    stable_error_code: (
        Annotated[StrictStr, StringConstraints(pattern=r"^[A-Z][A-Z0-9_]{0,127}$")] | None
    )
    usage: GeoProviderUsage
    provider_call_count: Annotated[StrictInt, Field(ge=0, le=100)]

    @field_validator("citations", mode="before")
    @classmethod
    def citations_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def shape_must_match_java(self) -> _GeoObservationInput:
        if self.provider != self.platform_code or self.finished_at < self.started_at:
            raise ValueError("GEO observation identity or timing is invalid")
        if self.status == "SUCCEEDED":
            if self.answer_text is None or self.stable_error_code is not None:
                raise ValueError("successful GEO observation shape is invalid")
        elif self.answer_text is not None or self.citations or self.stable_error_code is None:
            raise ValueError("failed GEO observation shape is invalid")
        return self


class _GeoObservationRequest(_JavaWorkerModel):
    event_id: UUID
    execution_no: PositiveInt
    execution_token: ExecutionToken
    observation: _GeoObservationInput


class _GeoObservationAccepted(_JavaWorkerModel):
    accepted: Literal[True]
    disposition: Literal["CREATED", "DUPLICATE"]
    task_status: Literal["RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "SYSTEM_TERMINATED"]


class _GeoStoredCitation(_JavaWorkerModel):
    title: Annotated[StrictStr, StringConstraints(max_length=500)] | None
    url: Annotated[StrictStr, StringConstraints(min_length=8, max_length=2_048)]
    domain: Annotated[StrictStr, StringConstraints(max_length=253)] | None
    snippet: Annotated[StrictStr, StringConstraints(max_length=2_000)] | None
    observed_at: StrictStr
    sequence: Annotated[StrictInt, Field(ge=1, le=50)]

    @field_validator("observed_at")
    @classmethod
    def timestamp_must_have_an_offset(cls, value: str) -> str:
        _parse_offset_datetime(value)
        return value


class _GeoStoredObservation(_JavaWorkerModel):
    platform_code: GeoPlatform
    question_id: SnowflakeId
    sample_no: Annotated[StrictInt, Field(ge=1, le=3)]
    status: Literal["SUCCEEDED", "FAILED"]
    answer_text: Annotated[StrictStr, StringConstraints(min_length=1, max_length=100_000)] | None
    citations: tuple[_GeoStoredCitation, ...] = Field(max_length=50)
    provider: GeoPlatform
    model: Annotated[StrictStr, StringConstraints(min_length=1, max_length=128)]
    collection_method: (
        Annotated[StrictStr, StringConstraints(pattern=r"^[a-z][a-z0-9-]{0,127}$")] | None
    ) = None
    started_at: StrictStr
    finished_at: StrictStr
    latency_ms: Annotated[StrictInt, Field(ge=0, le=3_600_000)]
    stable_error_code: (
        Annotated[StrictStr, StringConstraints(pattern=r"^[A-Z][A-Z0-9_]{0,127}$")] | None
    )
    usage: GeoProviderUsage
    provider_call_count: Annotated[StrictInt, Field(ge=0, le=100)]
    unit_key: GeoUnitKey
    question_text: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)]

    @field_validator("citations", mode="before")
    @classmethod
    def citations_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @field_validator("started_at", "finished_at")
    @classmethod
    def timestamps_must_have_an_offset(cls, value: str) -> str:
        _parse_offset_datetime(value)
        return value

    @model_validator(mode="after")
    def persisted_shape_must_be_canonical(self) -> _GeoStoredObservation:
        expected_key = f"{self.question_id}:{self.platform_code}:{self.sample_no}"
        if self.unit_key != expected_key or self.provider != self.platform_code:
            raise ValueError("persisted GEO observation identity is invalid")
        if tuple(item.sequence for item in self.citations) != tuple(
            range(1, len(self.citations) + 1)
        ):
            raise ValueError("citation sequences must be contiguous")
        if self.status == "SUCCEEDED":
            if self.answer_text is None or self.stable_error_code is not None:
                raise ValueError("successful observation shape is invalid")
        elif self.answer_text is not None or self.citations or self.stable_error_code is None:
            raise ValueError("failed observation shape is invalid")
        return self

    def to_canonical(self) -> GeoObservation:
        return GeoObservation(
            platform_code=GeoPlatformCode(self.platform_code),
            question_id=self.question_id,
            sample_no=self.sample_no,
            status=GeoObservationStatus(self.status),
            answer_text=self.answer_text,
            citations=tuple(
                GeoCitation(
                    title=item.title,
                    url=item.url,
                    domain=item.domain,
                    snippet=item.snippet,
                    observed_at=_parse_offset_datetime(item.observed_at),
                )
                for item in self.citations
            ),
            provider=self.provider,
            model=self.model,
            collection_method=self.collection_method,
            started_at=_parse_offset_datetime(self.started_at),
            finished_at=_parse_offset_datetime(self.finished_at),
            latency_ms=self.latency_ms,
            stable_error_code=self.stable_error_code,
            usage=self.usage,
            provider_call_count=self.provider_call_count,
        )


class _PageVisibilityEvidence(GeoVisibilityEvidence):
    # Only the signed read page may describe the initially empty persisted run.
    # Recovery/synthesis's public evidence model retains its nonempty contract.
    units: tuple[GeoVisibilityEvidenceUnit, ...] = Field(max_length=210)


class _GeoObservationPage(_JavaWorkerModel):
    items: tuple[_GeoStoredObservation, ...] = Field(max_length=50)
    page: PositiveInt
    size: Annotated[StrictInt, Field(ge=1, le=50)]
    total: Annotated[StrictInt, Field(ge=0, le=210)]
    total_pages: Annotated[StrictInt, Field(ge=0, le=210)]
    visibility_evidence: _PageVisibilityEvidence

    @field_validator("items", mode="before")
    @classmethod
    def items_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def pagination_must_be_consistent(self) -> _GeoObservationPage:
        expected_pages = (self.total + self.size - 1) // self.size
        if self.total_pages != expected_pages or self.page > max(1, self.total_pages):
            raise ValueError("GEO observation pagination is invalid")
        if len(self.items) > self.size:
            raise ValueError("GEO observation page exceeds requested size")
        if self.visibility_evidence:
            units = self.visibility_evidence.units
            keys = {(unit.question_id, unit.platform_code, unit.sample_no) for unit in units}
            if len(keys) != len(units) or len(units) != self.total:
                raise ValueError("GEO visibility evidence must describe the full saved matrix")
            if not all(
                (item.question_id, item.platform_code, item.sample_no) in keys
                for item in self.items
            ):
                raise ValueError("GEO visibility evidence does not cover page identities")
            flags = {
                (unit.question_id, unit.platform_code, unit.sample_no): unit.target_mentioned
                for unit in units
            }
            for item in self.items:
                assessable = self.visibility_evidence.target_status == "AVAILABLE"
                effective = item.status == "SUCCEEDED" and bool((item.answer_text or "").strip())
                flag = flags[(item.question_id, item.platform_code, item.sample_no)]
                if (flag is not None) != (assessable and effective):
                    raise ValueError(
                        "GEO visibility flags must preserve invalid and unresolved units"
                    )
        return self


class _GeoSynthesisUsageRequest(_JavaWorkerModel):
    provider: Literal["DEEPSEEK", "SYSTEM"]
    model: Literal[
        "deepseek-v4-flash",
        "deepseek-flash",
        "deterministic-geo-fallback-v1",
        "deterministic-geo-diagnostic-sampling-v1",
    ]
    input_tokens: Annotated[StrictInt, Field(ge=0)]
    output_tokens: Annotated[StrictInt, Field(ge=0)]
    provider_call_count: Annotated[StrictInt, Field(ge=0, le=10)]
    latency_ms: Annotated[StrictInt, Field(ge=0, le=3_600_000)]


class _GeoCompletionRequest(_JavaWorkerModel):
    event_id: UUID
    execution_no: PositiveInt
    execution_token: ExecutionToken
    synthesis: GeoSynthesis
    synthesis_usage: _GeoSynthesisUsageRequest

    @model_validator(mode="after")
    def method_version_must_match_java(self) -> _GeoCompletionRequest:
        if self.synthesis.method_version not in {
            "geo-synthesis-v1",
            "geo-deterministic-v1",
            "geo-diagnostic-sampling-v1",
        }:
            raise ValueError("GEO synthesis method version is invalid")
        diagnostic = self.synthesis.method_version == "geo-diagnostic-sampling-v1"
        usage = self.synthesis_usage
        if diagnostic:
            if (
                usage.provider != "SYSTEM"
                or usage.model != "deterministic-geo-diagnostic-sampling-v1"
                or any(
                    (
                        usage.input_tokens,
                        usage.output_tokens,
                        usage.provider_call_count,
                        usage.latency_ms,
                    )
                )
                or any(
                    (
                        self.synthesis.coverage_gaps,
                        self.synthesis.citation_structure,
                        self.synthesis.competitor_signals,
                        self.synthesis.findings,
                        self.synthesis.recommendations,
                        self.synthesis.content_blueprint,
                        self.synthesis.competitor_candidates,
                        self.synthesis.competitor_discovery_version,
                    )
                )
            ):
                raise ValueError("Diagnostic handoff is sampling-only with zero model usage")
        elif usage.model == "deterministic-geo-diagnostic-sampling-v1":
            raise ValueError("Diagnostic usage requires its sampling method")
        return self


class _GeoCompletionAccepted(_JavaWorkerModel):
    accepted: Literal[True]
    task_status: Literal["SUCCEEDED", "PARTIAL"]
    run_status: Literal["SUCCEEDED", "PARTIAL"]
    report_id: SnowflakeId
    settled_points: PositiveInt
    released_points: Annotated[StrictInt, Field(ge=0)]

    @model_validator(mode="after")
    def terminal_statuses_must_match(self) -> _GeoCompletionAccepted:
        if self.task_status != self.run_status:
            raise ValueError("GEO task and run terminal states must match")
        return self


class _GeoDiagnosticCompletionAccepted(_JavaWorkerModel):
    accepted: Literal[True]
    # An idempotent handoff replay may arrive after Java's separate delivery
    # has finished. Accept its real task state without treating it as PDF-ready.
    task_status: Literal["RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "SYSTEM_TERMINATED"]
    run_status: Literal["SUCCEEDED", "PARTIAL", "FAILED", "SYSTEM_TERMINATED"]
    report_id: None
    settled_points: Annotated[StrictInt, Field(ge=0)]
    released_points: Annotated[StrictInt, Field(ge=0)]

    @model_validator(mode="after")
    def failed_sampling_releases_all(self) -> _GeoDiagnosticCompletionAccepted:
        if self.run_status == "FAILED" and self.settled_points != 0:
            raise ValueError("Failed diagnostic sampling cannot settle successful samples")
        return self


class JavaWorkerControlPlane:
    """Map Worker operations to the frozen Java Internal task API."""

    def __init__(self, client: JavaWorkerInternalClientProtocol) -> None:
        self._client = client

    async def probe(self) -> bool:
        try:
            await self._client.reverse_call()
        except JavaInternalClientError as exc:
            _raise_control_plane_error(exc, claim=False)
        return True

    async def claim(
        self,
        message: TaskExecutionMessage,
        *,
        consumer_name: str,
        worker_id: str,
        bundle_version: str,
    ) -> ClaimResult:
        if message.task_type not in {
            TaskType.IMAGE,
            TaskType.VIDEO,
            TaskType.GEO,
            TaskType.ARTICLE,
        }:
            raise ControlPlaneRejected("JAVA_CONTROL_PLANE_REQUEST_INVALID")
        _validate_task_id(message.task_id)
        request = _validate_request(
            _TaskClaimRequest,
            {
                "consumerName": consumer_name,
                "eventId": message.event_id,
                "workerId": worker_id,
                "attempt": message.attempt,
                "supportedBundleVersion": bundle_version,
            },
        )
        response = await self._invoke(
            self._client.claim_task(
                message.task_id,
                _json_payload(request),
                message.traceparent,
            ),
            claim=True,
        )
        parsed = _validate_response(_TaskClaimResponse, response.payload)
        return ClaimResult(
            disposition=ClaimDisposition(parsed.disposition),
            execution_no=parsed.execution_no,
            execution_token=parsed.execution_token,
            lease_expires_at=(
                None
                if parsed.lease_expires_at is None
                else _parse_offset_datetime(parsed.lease_expires_at)
            ),
        )

    async def get_execution_context(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str,
    ) -> ExecutionContext | GeoExecutionContext | ArticleExecutionContext:
        _validate_task_id(task_id)
        _validate_request(
            _TaskExecutionIdentity,
            {
                "executionNo": execution_no,
                "executionToken": execution_token,
            },
        )
        response = await self._invoke(
            self._client.get_task_execution_context(
                task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                traceparent=traceparent,
            )
        )
        parsed = _validate_execution_context_response(response.payload)
        if parsed.task_id != task_id or parsed.execution_no != execution_no:
            raise ControlPlaneRejected("JAVA_EXECUTION_CONTEXT_IDENTITY_MISMATCH")
        if isinstance(parsed, _GeoTaskExecutionContextResponse):
            parameters = parsed.input_snapshot.parameters
            authorized = parsed.authorized_context
            return GeoExecutionContext(
                task_id=parsed.task_id,
                execution_no=parsed.execution_no,
                topic_id=parameters.topic_id,
                question_ids=parameters.question_ids,
                platform_codes=tuple(GeoPlatformCode(value) for value in parameters.platform_codes),
                sample_mode=GeoSampleMode(parameters.sample_mode),
                region=parameters.region,
                synthesis_context=GeoDiagnosticSynthesisContext.model_validate(
                    authorized.model_dump(exclude={"completed_unit_keys"})
                )
                if isinstance(authorized, _GeoDiagnosticAuthorizedContext)
                else GeoSynthesisContext(
                    topic=GeoTopicSnapshot(
                        id=authorized.topic.id,
                        space_id=authorized.topic.space_id,
                        product_id=authorized.topic.product_id,
                        name=authorized.topic.name,
                        region=authorized.topic.region,
                        version=authorized.topic.version,
                    ),
                    questions=tuple(
                        GeoQuestionSnapshot(
                            id=item.id,
                            category=item.category,
                            text=item.text,
                        )
                        for item in authorized.questions
                    ),
                    competitor_set_version=authorized.competitor_set_version,
                    competitors=tuple(
                        GeoCompetitorSnapshot(
                            id=item.id,
                            name=item.name,
                            aliases=item.aliases,
                            competitor_type=item.competitor_type,
                        )
                        for item in authorized.competitors
                    ),
                    monitoring_target=authorized.monitoring_target,
                ),
                completed_unit_keys=frozenset(authorized.completed_unit_keys),
                max_provider_calls=parsed.budget.max_provider_calls,
                max_geo_questions=parsed.budget.max_geo_questions,
                max_geo_platforms=parsed.budget.max_geo_platforms,
                samples_per_combination=parsed.budget.samples_per_combination,
                max_concurrency=parsed.budget.max_concurrency,
                deadline_at=_parse_offset_datetime(parsed.budget.deadline_at),
                mode=parameters.mode,
            )
        if isinstance(parsed, _ArticleTaskExecutionContextResponse):
            return _article_execution_context(parsed)
        if isinstance(parsed, _VideoTaskExecutionContextResponse):
            # Java authorizes the complete proposal snapshot, including fields used only
            # for audit and confirmation.  The Provider receives only its frozen Wan
            # parameter surface; otherwise its strict adapter correctly rejects the
            # proposal-only requestText/mode/agentSummary envelope as unknown input.
            parameters = _json_payload(parsed.input_snapshot, exclude_none=True)
            for control_plane_field in (
                "requestText",
                "mode",
                "agentSummary",
                "sourceMode",
                "referenceAssetIds",
                "creativePlan",
            ):
                parameters.pop(control_plane_field, None)
            continuation = parsed.provider_continuation
            provider_continuation = (
                None
                if continuation is None
                else WanVideoProviderContinuation(
                    provider=continuation.provider,
                    model=continuation.model,
                    provider_request_id=UUID(continuation.provider_request_id),
                    provider_task_id=continuation.provider_task_id,
                    submission_state=continuation.submission_state,
                )
            )
            max_images = None
            max_videos = parsed.budget.max_videos
            max_video_seconds = parsed.budget.max_video_seconds
        else:
            parameters = dict(parsed.input_snapshot)
            for control_plane_field in (
                "requestText",
                "mode",
                "agentSummary",
                "sourceMode",
                "referenceAssetIds",
            ):
                parameters.pop(control_plane_field, None)
            continuation = parsed.provider_continuation
            provider_continuation = (
                None
                if continuation is None
                else (
                    WanImageEditProviderContinuation
                    if continuation.provider == "WAN_IMAGE_EDIT"
                    else QwenImageProviderContinuation
                ).model_validate(
                    {
                        "provider": continuation.provider,
                        "model": continuation.model,
                        "providerRequestId": UUID(continuation.provider_request_id),
                        "providerTaskId": continuation.provider_task_id,
                        "submissionState": continuation.submission_state,
                    }
                )
            )
            max_images = parsed.budget.max_images
            max_videos = None
            max_video_seconds = None
        return ExecutionContext(
            task_id=parsed.task_id,
            task_type=TaskType(parsed.task_type),
            execution_no=parsed.execution_no,
            parameters=cast(dict[str, JsonValue], parameters),
            creative_brief=parsed.product_creative_brief,
            reference_assets=parsed.reference_assets,
            controlled_mask_asset=(
                parsed.controlled_mask_asset
                if isinstance(parsed, _ImageTaskExecutionContextResponse)
                else None
            ),
            provider=parsed.provider,
            model=parsed.model,
            max_provider_calls=parsed.budget.max_provider_calls,
            max_images=max_images,
            max_videos=max_videos,
            max_video_seconds=max_video_seconds,
            resume_only=parsed.resume_only,
            provider_continuation=provider_continuation,
        )

    async def heartbeat(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        stage_code: str,
        traceparent: str,
    ) -> None:
        _validate_task_id(task_id)
        request = _validate_request(
            _TaskHeartbeatRequest,
            {
                "executionNo": execution_no,
                "executionToken": execution_token,
                "stageCode": stage_code,
            },
        )
        response = await self._invoke(
            self._client.heartbeat_task(task_id, _json_payload(request), traceparent)
        )
        _validate_response(_TaskHeartbeatResponse, response.payload)

    async def report_progress(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        stage_code: str,
        traceparent: str,
    ) -> None:
        _validate_task_id(task_id)
        request = _validate_request(
            _TaskExecutionEventRequest,
            {
                "eventId": uuid4(),
                "executionNo": execution_no,
                "executionToken": execution_token,
                "eventType": "TASK_PROGRESS",
                "stageCode": stage_code,
            },
        )
        response = await self._invoke(
            self._client.report_task_execution_event(
                task_id,
                _json_payload(request, exclude_none=True),
                traceparent,
            )
        )
        parsed = _validate_response(_InternalTaskAccepted, response.payload)
        if parsed.task_status != "RUNNING":
            raise ControlPlaneRejected("JAVA_TASK_PROGRESS_RESPONSE_INVALID")

    async def report_result(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        result: TaskProviderResult,
        traceparent: str,
    ) -> JavaTaskStatus:
        _validate_task_id(task_id)
        result_model: (
            type[_ImageMockResultRequest]
            | type[_ImageRealResultRequest]
            | type[_WanVideoResultRequest]
        )
        if result.provider == "LOCAL_TASK_PROVIDER":
            result_model = _ImageMockResultRequest
        elif result.provider in {"QWEN_IMAGE", "WAN_IMAGE_EDIT"}:
            result_model = _ImageRealResultRequest
        elif result.provider == "WAN_VIDEO":
            result_model = _WanVideoResultRequest
        else:
            raise ControlPlaneRejected("JAVA_CONTROL_PLANE_RESULT_PROVIDER_INVALID")
        request = _validate_request(
            result_model,
            {
                "eventId": result.request_id,
                "executionNo": execution_no,
                "executionToken": execution_token,
                **result.model_dump(mode="python", by_alias=True),
            },
        )
        payload = _json_payload(request)
        if isinstance(request, (_ImageRealResultRequest, _WanVideoResultRequest)):
            artifacts = cast(list[dict[str, object]], payload["artifacts"])
            optional_fields = (
                ("outputImageType", "providerResultRequestId")
                if isinstance(request, _ImageRealResultRequest)
                else ("providerResultRequestId", "fps", "resolution", "aspectRatio")
            )
            for artifact in artifacts:
                metadata = cast(dict[str, object], artifact["metadata"])
                for optional_field in optional_fields:
                    if metadata.get(optional_field) is None:
                        metadata.pop(optional_field, None)
        try:
            response = await self._client.report_task_result(task_id, payload, traceparent)
        except JavaInternalClientError as exc:
            _log_result_handoff_failure(
                task_id=task_id,
                execution_no=execution_no,
                result=result,
                error_code=_safe_client_error_code(exc),
                http_status=exc.status_code,
            )
            _raise_control_plane_error(exc, claim=False)
        if response.status_code != 200:
            _log_result_handoff_failure(
                task_id=task_id,
                execution_no=execution_no,
                result=result,
                error_code="JAVA_CONTROL_PLANE_RESPONSE_INVALID",
                http_status=response.status_code,
            )
            raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID")
        parsed = _validate_response(_InternalTaskAccepted, response.payload)
        if isinstance(request, _ImageMockResultRequest):
            allowed_statuses = frozenset({"SUCCEEDED"})
            invalid_status_code = "JAVA_TASK_RESULT_RESPONSE_INVALID"
        elif isinstance(request, _ImageRealResultRequest):
            allowed_statuses = frozenset({"RUNNING", "SUCCEEDED", "PARTIAL"})
            invalid_status_code = "JAVA_IMAGE_RESULT_HANDOFF_RESPONSE_INVALID"
        else:
            # RUNNING is the normal first handoff. SUCCEEDED/PARTIAL are valid
            # idempotent replays after Java has already ingested the artifact.
            allowed_statuses = frozenset({"RUNNING", "SUCCEEDED", "PARTIAL"})
            invalid_status_code = "JAVA_VIDEO_RESULT_HANDOFF_RESPONSE_INVALID"
        if parsed.task_status not in allowed_statuses:
            raise ControlPlaneRejected(invalid_status_code)
        return parsed.task_status

    async def report_article_preview(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        attempt: int,
        sequence: int,
        markdown: str,
        traceparent: str,
    ) -> None:
        _validate_task_id(task_id)
        request = _validate_request(
            _TaskExecutionEventRequest,
            {
                "eventId": uuid4(),
                "executionNo": execution_no,
                "executionToken": execution_token,
                "eventType": "TASK_PROGRESS",
                "stageCode": "ARTICLE_BODY_STREAMING",
                "details": {
                    "articlePreview": {
                        "attempt": attempt,
                        "sequence": sequence,
                        "markdown": markdown,
                    }
                },
            },
        )
        response = await self._invoke(
            self._client.report_task_execution_event(
                task_id, _json_payload(request, exclude_none=True), traceparent
            )
        )
        parsed = _validate_response(_InternalTaskAccepted, response.payload)
        if parsed.task_status != "RUNNING":
            raise ControlPlaneRejected("JAVA_ARTICLE_PREVIEW_RESPONSE_INVALID")

    async def report_article_completion(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        completion: ArticleCompletion,
        traceparent: str,
    ) -> JavaTaskStatus:
        _validate_task_id(task_id)
        result = completion.result
        request_id = uuid4()
        inline_data = {
            "mode": result.mode.value,
            "articleVersion": completion.article_version,
            "titleCandidates": [item.text for item in result.title_candidates],
            "title": result.title,
            "plan": (
                None
                if result.plan is None
                else result.plan.model_dump(mode="python", by_alias=True)
            ),
            "outline": [
                {
                    "key": item.key,
                    "sequence": item.sequence,
                    "level": item.level,
                    "heading": item.heading,
                    "brief": item.brief,
                }
                for item in completion.outline
            ],
            "body": (
                None
                if completion.body is None
                else completion.body.model_dump(mode="python", by_alias=True)
            ),
            "provider": result.provider,
            "model": result.model,
            "modelVersion": result.model_version,
            "promptVersion": result.prompt_version,
            "usage": result.usage.model_dump(mode="python", by_alias=True),
            "warnings": list(result.warnings),
            "metadata": result.metadata.model_dump(mode="python", by_alias=True),
        }
        request = _validate_request(
            _ArticleContentResultRequest,
            {
                "eventId": request_id,
                "executionNo": execution_no,
                "executionToken": execution_token,
                "provider": result.provider,
                "model": result.model,
                "requestId": request_id,
                "providerTaskId": None,
                "submissionState": SubmissionState.CONFIRMED,
                "artifacts": [
                    {
                        "artifactType": "ARTICLE_CONTENT",
                        "unitKey": f"article:{result.mode.value.casefold()}",
                        "schemaVersion": 2,
                        "inlineData": inline_data,
                    }
                ],
                "usage": {
                    "inputTokens": result.usage.input_tokens,
                    "outputTokens": result.usage.output_tokens,
                    "images": 0,
                    "videoSeconds": 0,
                    "providerCost": result.usage.monetary_cost,
                    "currency": result.usage.currency,
                },
                "latencyMs": result.usage.latency_ms,
            },
        )
        response = await self._invoke(
            self._client.report_task_result(task_id, _json_payload(request), traceparent)
        )
        parsed = _validate_response(_InternalTaskAccepted, response.payload)
        if parsed.task_status not in {"SUCCEEDED", "FAILED"}:
            raise ControlPlaneRejected("JAVA_ARTICLE_RESULT_RESPONSE_INVALID")
        return parsed.task_status

    async def list_geo_observations(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str,
    ) -> tuple[GeoObservation, ...]:
        _validate_task_id(task_id)
        _validate_request(
            _TaskExecutionIdentity,
            {"executionNo": execution_no, "executionToken": execution_token},
        )
        items: list[GeoObservation] = []
        expected_total: int | None = None
        page = 1
        while True:
            response = await self._invoke(
                self._client.get_task_geo_observations(
                    task_id,
                    execution_no=execution_no,
                    execution_token=execution_token,
                    page=page,
                    size=50,
                    traceparent=traceparent,
                )
            )
            parsed = _validate_response(_GeoObservationPage, response.payload)
            if parsed.page != page or parsed.size != 50:
                raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID")
            if expected_total is None:
                expected_total = parsed.total
            elif expected_total != parsed.total:
                raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID")
            items.extend(item.to_canonical() for item in parsed.items)
            if page >= parsed.total_pages:
                break
            page += 1
            if page > 5:
                raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID")
        if len(items) != expected_total:
            raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID")
        return tuple(items)

    async def get_geo_visibility_evidence(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str,
    ) -> GeoVisibilityEvidence:
        """Read Java's frozen-name/full-answer flags; no new search or matching."""
        _validate_task_id(task_id)
        _validate_request(
            _TaskExecutionIdentity,
            {"executionNo": execution_no, "executionToken": execution_token},
        )
        response = await self._invoke(
            self._client.get_task_geo_observations(
                task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                page=1,
                size=50,
                traceparent=traceparent,
            )
        )
        parsed = _validate_response(_GeoObservationPage, response.payload)
        if parsed.page != 1 or parsed.size != 50 or not parsed.visibility_evidence.units:
            raise ControlPlaneUnavailable("JAVA_GEO_VISIBILITY_EVIDENCE_INVALID")
        return parsed.visibility_evidence

    async def report_geo_observation(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        observation: GeoObservation,
        traceparent: str,
    ) -> GeoObservationDisposition:
        _validate_task_id(task_id)
        if observation.provider != observation.platform_code.value:
            raise ControlPlaneRejected("JAVA_CONTROL_PLANE_REQUEST_INVALID")
        request = _validate_request(
            _GeoObservationRequest,
            {
                "eventId": uuid4(),
                "executionNo": execution_no,
                "executionToken": execution_token,
                "observation": observation.model_dump(mode="python", by_alias=True),
            },
        )
        response = await self._invoke(
            self._client.report_task_geo_observation(
                task_id,
                _json_payload(request),
                traceparent,
            )
        )
        parsed = _validate_response(_GeoObservationAccepted, response.payload)
        if parsed.disposition == "CREATED" and parsed.task_status != "RUNNING":
            raise ControlPlaneRejected("JAVA_GEO_OBSERVATION_RESPONSE_INVALID")
        return GeoObservationDisposition(parsed.disposition)

    async def report_geo_completion(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        completion: GeoCompletion,
        traceparent: str,
    ) -> JavaTaskStatus:
        _validate_task_id(task_id)
        usage = completion.synthesis_usage
        request = _validate_request(
            _GeoCompletionRequest,
            {
                "eventId": uuid4(),
                "executionNo": execution_no,
                "executionToken": execution_token,
                "synthesis": completion.synthesis.model_dump(mode="python", by_alias=True),
                "synthesisUsage": usage.model_dump(mode="python", by_alias=True),
            },
        )
        response = await self._invoke(
            self._client.report_task_geo_completion(
                task_id,
                _json_payload(request),
                traceparent,
            )
        )
        parsed = (
            _validate_response(_GeoDiagnosticCompletionAccepted, response.payload)
            if completion.synthesis.method_version == "geo-diagnostic-sampling-v1"
            else _validate_response(_GeoCompletionAccepted, response.payload)
        )
        return parsed.task_status

    async def report_failure(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        code: str,
        submission_state: SubmissionState,
        traceparent: str,
        provider: str | None = None,
        model: str | None = None,
        provider_request_id: str | None = None,
        provider_task_id: str | None = None,
    ) -> None:
        _validate_task_id(task_id)
        stage_code = (
            "PROVIDER_RESULT_UNKNOWN" if submission_state is SubmissionState.UNKNOWN else "FAILED"
        )
        details: dict[str, object] = {
            "code": code,
            "submissionState": submission_state.value,
        }
        if submission_state is SubmissionState.UNKNOWN:
            details.update(
                {
                    "provider": provider,
                    "model": model,
                    "providerRequestId": provider_request_id,
                    "providerTaskId": provider_task_id,
                }
            )
        elif any(
            value is not None for value in (provider, model, provider_request_id, provider_task_id)
        ):
            raise ControlPlaneRejected("JAVA_CONTROL_PLANE_REQUEST_INVALID")
        request = _validate_request(
            _TaskExecutionEventRequest,
            {
                "eventId": uuid4(),
                "executionNo": execution_no,
                "executionToken": execution_token,
                "eventType": "TASK_FAILED",
                "stageCode": stage_code,
                "details": details,
            },
        )
        response = await self._invoke(
            self._client.report_task_execution_event(
                task_id,
                _json_payload(request),
                traceparent,
            )
        )
        parsed = _validate_response(_InternalTaskAccepted, response.payload)
        has_receipt = provider_request_id is not None and provider_task_id is not None
        expected = (
            {"RETRY_WAIT", "SYSTEM_TERMINATED"}
            if submission_state is SubmissionState.UNKNOWN and has_receipt
            else {"SYSTEM_TERMINATED"}
            if submission_state is SubmissionState.UNKNOWN
            else {"FAILED"}
        )
        if parsed.task_status not in expected:
            raise ControlPlaneRejected("JAVA_TASK_FAILURE_RESPONSE_INVALID")

    async def aclose(self) -> None:
        await self._client.aclose()

    @staticmethod
    async def _invoke(
        awaitable: Awaitable[JavaInternalResponse],
        *,
        claim: bool = False,
    ) -> JavaInternalResponse:
        try:
            response = await awaitable
        except JavaInternalClientError as exc:
            _raise_control_plane_error(exc, claim=claim)
        if response.status_code != 200:
            raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID")
        return response


def build_worker_control_plane(settings: AppSettings) -> WorkerControlPlane:
    """Create the configured Java adapter without opening a network connection."""
    client = build_java_internal_client(settings)
    if isinstance(client, UnavailableJavaInternalClient):
        raise ControlPlaneConfigurationError("JAVA_CONTROL_PLANE_CONFIGURATION_UNAVAILABLE")
    return JavaWorkerControlPlane(cast(JavaWorkerInternalClientProtocol, client))


def _article_execution_context(
    parsed: _ArticleTaskExecutionContextResponse,
) -> ArticleExecutionContext:
    parameters = (
        parsed.input_snapshot.parameters
        if isinstance(parsed.input_snapshot, _ArticleInputSnapshot)
        else parsed.input_snapshot
    )
    authorized = parsed.authorized_context
    outline = tuple(
        ArticleOutlineSnapshot(
            key=item.key,
            sequence=item.sequence,
            level=item.level,
            heading=item.heading,
            brief=cast(str, item.brief),
        )
        for item in parameters.confirmed_outline
    )
    internal_outline = tuple(
        ArticleOutlineItem(
            key=item.key,
            sequence=item.sequence,
            level=item.level,
            heading=item.heading,
            brief=cast(str, item.brief),
        )
        for item in parameters.confirmed_outline
    )
    internal_plan = (
        None
        if parameters.confirmed_plan is None
        else ArticlePlan(
            summary=parameters.confirmed_plan.summary,
            core_message=parameters.confirmed_plan.core_message,
            target_audience=parameters.confirmed_plan.target_audience,
            sections=internal_outline,
            tone=parameters.confirmed_plan.tone,
            target_length=ArticleTargetLength(parameters.confirmed_plan.target_length),
            target_platform=parameters.confirmed_plan.target_platform,
            risk_notes=parameters.confirmed_plan.risk_notes,
        )
    )
    current_body = None
    current_version_ref = None
    if parameters.mode == "REWRITE":
        raw_body = authorized.article.current_body
        if raw_body is None:
            raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID")
        try:
            current_body = parse_canonical_article_body(raw_body.html, outline)
        except ValueError:
            raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID") from None
        current_version_ref = str(authorized.article.current_version_ref)

    source_summaries = tuple(
        ArticleSourceAssetSummary(
            asset_id=item.id,
            summary=_bounded_summary(
                {
                    "name": item.name,
                    "assetType": item.asset_type,
                    "contentType": item.content_type,
                    "contentInspected": False,
                },
                2_000,
            ),
        )
        for item in authorized.source_assets
    )
    report_summary = (
        None
        if authorized.geo_report is None
        else ArticleGeoReportSummary(
            geo_report_id=authorized.geo_report.id,
            summary=authorized.geo_report.summary,
            method_version=authorized.geo_report.method_version,
            coverage_gaps=authorized.geo_report.coverage_gaps,
            findings=authorized.geo_report.findings,
            recommendations=tuple(
                ArticleGeoRecommendation(
                    priority=item.priority,
                    title=item.title,
                    rationale=item.rationale,
                )
                for item in authorized.geo_report.recommendations
            ),
            content_blueprint=authorized.geo_report.content_blueprint,
            missing_samples=authorized.geo_report.missing_samples,
        )
    )
    brand = authorized.space.brand
    product = authorized.product
    try:
        request = ArticleExecutionRequest(
            mode=ArticleStage(parameters.mode),
            article_id=parameters.article_id,
            brief=parameters.brief,
            selected_title=parameters.selected_title,
            confirmed_outline=internal_outline,
            confirmed_plan=internal_plan,
            rewrite_instruction=parameters.rewrite_instruction,
            target_length=ArticleTargetLength(parameters.target_length),
            tone=parameters.tone,
            target_platform=parameters.target_platform,
            source_asset_ids=parameters.source_asset_ids,
            geo_report_id=parameters.geo_report_id,
            space_summary=_bounded_summary({"name": authorized.space.name}, 2_000),
            brand_summary=_bounded_summary(
                {
                    "name": brand.name,
                    "aliases": brand.aliases,
                    "description": brand.description,
                    "tone": brand.tone,
                    "audience": brand.audience,
                    "sellingPoints": brand.selling_points,
                    "prohibitedClaims": brand.prohibited_claims,
                },
                4_000,
            ),
            product_summary=(
                None
                if product is None
                else _bounded_summary(
                    {
                        "name": product.name,
                        "aliases": product.aliases,
                        "category": product.category,
                        "modelSpec": product.model_spec,
                        "sellingPoints": product.selling_points,
                        "parameters": product.parameters,
                        "audience": product.audience,
                        "usageScenarios": product.usage_scenarios,
                        "prohibitedClaims": product.prohibited_claims,
                    },
                    4_000,
                )
            ),
            source_asset_summaries=source_summaries,
            geo_report_summary=report_summary,
            knowledge_context=authorized.knowledge,
            untrusted_research=authorized.untrusted_research,
            confirmed_facts=authorized.confirmed_facts,
            current_body=current_body,
            current_version_ref=current_version_ref,
            rewrite_block_ids=parameters.rewrite_block_ids,
            budget=ArticleExecutionBudget(
                deadline_at=_parse_offset_datetime(parsed.budget.deadline_at),
                max_model_calls=parsed.budget.max_model_calls,
                max_repair_retries=min(parsed.budget.max_repair_retries, 1),
                max_input_tokens=parsed.budget.max_input_tokens,
                max_output_tokens=parsed.budget.max_output_tokens,
            ),
        )
    except (TypeError, ValueError):
        raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID") from None
    return ArticleExecutionContext(
        task_id=parsed.task_id,
        execution_no=parsed.execution_no,
        article_version=parameters.article_version,
        request=request,
        authorized_outline=outline,
    )


def _bounded_summary(payload: object, maximum: int) -> str:
    value = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    if len(value) <= maximum:
        return value
    return value[: maximum - 1] + "…"


def _raise_control_plane_error(error: JavaInternalClientError, *, claim: bool) -> NoReturn:
    if error.code in {
        JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE,
        JavaInternalErrorCode.CONNECTION_FAILED,
        JavaInternalErrorCode.TIMEOUT,
        JavaInternalErrorCode.UPSTREAM_5XX,
    }:
        raise ControlPlaneUnavailable(_safe_client_error_code(error)) from None
    if claim and error.status_code in {404, 409, 422}:
        raise ControlPlaneRejected(_safe_client_error_code(error)) from None
    if error.code in {
        JavaInternalErrorCode.AUTHENTICATION_REJECTED,
        JavaInternalErrorCode.INVALID_REQUEST,
        JavaInternalErrorCode.INVALID_RESPONSE,
    }:
        raise ControlPlaneUnavailable(_safe_client_error_code(error)) from None
    if claim:
        raise ControlPlaneUnavailable(_safe_client_error_code(error)) from None
    raise ControlPlaneRejected(_safe_client_error_code(error)) from None


def _safe_client_error_code(error: JavaInternalClientError) -> str:
    if (
        error.code is JavaInternalErrorCode.HTTP_ERROR
        and error.status_code == 409
        and error.business_code == "KNOWLEDGE_SOURCE_CHANGED"
    ):
        return "KNOWLEDGE_SOURCE_CHANGED"
    if error.code is JavaInternalErrorCode.CONFIGURATION_UNAVAILABLE:
        return "JAVA_CONTROL_PLANE_CONFIGURATION_UNAVAILABLE"
    if error.code is JavaInternalErrorCode.CONNECTION_FAILED:
        return "JAVA_CONTROL_PLANE_CONNECTION_FAILED"
    if error.code is JavaInternalErrorCode.TIMEOUT:
        return "JAVA_CONTROL_PLANE_TIMEOUT"
    if error.code is JavaInternalErrorCode.AUTHENTICATION_REJECTED:
        return "JAVA_CONTROL_PLANE_AUTHENTICATION_REJECTED"
    if error.code is JavaInternalErrorCode.UPSTREAM_5XX:
        return "JAVA_CONTROL_PLANE_UPSTREAM_UNAVAILABLE"
    if error.code is JavaInternalErrorCode.INVALID_REQUEST:
        return "JAVA_CONTROL_PLANE_REQUEST_INVALID"
    if error.code is JavaInternalErrorCode.INVALID_RESPONSE:
        return "JAVA_CONTROL_PLANE_RESPONSE_INVALID"
    statuses = {
        403: "JAVA_CONTROL_PLANE_FORBIDDEN",
        404: "JAVA_CONTROL_PLANE_NOT_FOUND",
        409: "JAVA_CONTROL_PLANE_CONFLICT",
        422: "JAVA_CONTROL_PLANE_REQUEST_REJECTED",
    }
    if error.status_code is not None:
        return statuses.get(error.status_code, "JAVA_CONTROL_PLANE_REJECTED")
    return "JAVA_CONTROL_PLANE_REJECTED"


def _safe_artifact_hostname(result: TaskProviderResult) -> str | None:
    if not result.artifacts or result.artifacts[0].provider_url is None:
        return None
    try:
        hostname = urlsplit(result.artifacts[0].provider_url).hostname
    except ValueError:
        return None
    if hostname is None or len(hostname) > 253 or not hostname.isascii():
        return None
    normalized = hostname.lower()
    try:
        ipaddress.ip_address(normalized)
    except ValueError:
        if not all(
            re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
            for label in normalized.split(".")
        ):
            return None
    return normalized


def _log_result_handoff_failure(
    *,
    task_id: str,
    execution_no: int,
    result: TaskProviderResult,
    error_code: str,
    http_status: int | None,
) -> None:
    _LOGGER.error(
        "worker_result_handoff_failed",
        extra={
            "taskId": task_id,
            "attempt": execution_no,
            "failureStage": "RESULT_HANDOFF",
            "stableErrorCode": error_code,
            "httpStatus": (
                http_status if http_status is not None and 100 <= http_status <= 599 else None
            ),
            "provider": result.provider,
            "artifactHost": _safe_artifact_hostname(result),
        },
    )


def _validate_request[ModelT: _JavaWorkerModel](model: type[ModelT], payload: object) -> ModelT:
    try:
        return model.model_validate(payload)
    except ValidationError:
        raise ControlPlaneRejected("JAVA_CONTROL_PLANE_REQUEST_INVALID") from None


def _validate_response[ModelT: _JavaWorkerModel](model: type[ModelT], payload: object) -> ModelT:
    try:
        return model.model_validate(payload)
    except ValidationError:
        raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID") from None


def _validate_execution_context_response(payload: object) -> _TaskExecutionContextResponse:
    if not isinstance(payload, dict):
        raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID")
    object_payload = cast(dict[str, object], payload)
    task_type = object_payload.get("taskType")
    if task_type == "IMAGE":
        return _validate_response(_ImageTaskExecutionContextResponse, object_payload)
    if task_type == "VIDEO":
        return _validate_response(_VideoTaskExecutionContextResponse, object_payload)
    if task_type == "GEO":
        return _validate_response(_GeoTaskExecutionContextResponse, object_payload)
    if task_type == "ARTICLE":
        return _validate_response(_ArticleTaskExecutionContextResponse, object_payload)
    raise ControlPlaneUnavailable("JAVA_CONTROL_PLANE_RESPONSE_INVALID")


def _json_payload(
    model: _JavaWorkerModel,
    *,
    exclude_none: bool = False,
) -> dict[str, object]:
    payload = cast(
        dict[str, object],
        model.model_dump(mode="json", by_alias=True, exclude_none=exclude_none),
    )
    if (
        isinstance(model, _GeoCompletionRequest)
        and model.synthesis.competitor_discovery_version is None
    ):
        # A retry of a legacy/fallback completion keeps its original canonical hash.
        synthesis = cast(dict[str, object], payload["synthesis"])
        synthesis.pop("competitorCandidates", None)
        synthesis.pop("competitorDiscoveryVersion", None)
    return payload


def _validate_task_id(value: str) -> None:
    if not value.isascii() or not value.isdecimal() or value.startswith("0"):
        raise ControlPlaneRejected("JAVA_CONTROL_PLANE_REQUEST_INVALID")
    try:
        _signed_long_range(value)
    except ValueError:
        raise ControlPlaneRejected("JAVA_CONTROL_PLANE_REQUEST_INVALID") from None


def _parse_offset_datetime(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError("date-time is invalid") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("date-time must include an offset")
    return parsed


def _matches_stage_code(value: str) -> bool:
    return (
        1 <= len(value) <= 64
        and value[0].isalpha()
        and value.isascii()
        and all(char.isupper() or char.isdigit() or char == "_" for char in value)
    )


def _validate_json_value(value: object, *, require_object: bool = False) -> None:
    if require_object and not isinstance(value, dict):
        raise ValueError("value must be a JSON object")
    if value is None or isinstance(value, (str, bool, int)):
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("JSON numbers must be finite")
        return
    if isinstance(value, list):
        for item in cast(list[object], value):
            _validate_json_value(item)
        return
    if isinstance(value, dict):
        object_value = cast(dict[object, object], value)
        if not all(isinstance(key, str) for key in object_value):
            raise ValueError("JSON object keys must be strings")
        for item in object_value.values():
            _validate_json_value(item)
        return
    raise ValueError("value is not JSON-compatible")


__all__ = ["JavaWorkerControlPlane", "build_worker_control_plane"]
