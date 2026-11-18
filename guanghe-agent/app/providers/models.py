"""Provider-neutral contracts for long-running task execution."""

from datetime import datetime
from enum import StrEnum
from typing import Literal, Protocol, runtime_checkable
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, JsonValue, model_validator
from pydantic.alias_generators import to_camel

from app.common.enums import ProviderStatus, TaskType
from app.creative.models import CreativeReferenceAsset, ProductCreativeBrief


class ProviderModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class SubmissionState(StrEnum):
    NOT_SUBMITTED = "NOT_SUBMITTED"
    CONFIRMED = "CONFIRMED"
    UNKNOWN = "UNKNOWN"


class ProviderErrorCategory(StrEnum):
    AUTH = "AUTH"
    RATE_LIMIT = "RATE_LIMIT"
    UPSTREAM = "UPSTREAM"
    TIMEOUT = "TIMEOUT"
    INVALID_RESPONSE = "INVALID_RESPONSE"
    SAFETY_REJECTED = "SAFETY_REJECTED"


class TaskProviderCapability(StrEnum):
    REFERENCE_IMAGE_GENERATION = "REFERENCE_IMAGE_GENERATION"
    IMAGE_TO_VIDEO = "IMAGE_TO_VIDEO"
    TEXT_TO_IMAGE = "TEXT_TO_IMAGE"
    TEXT_TO_VIDEO = "TEXT_TO_VIDEO"
    REFERENCE_TO_VIDEO = "REFERENCE_TO_VIDEO"
    MASK_IMAGE_EDIT = "MASK_IMAGE_EDIT"


class TaskProviderRequest(ProviderModel):
    request_id: UUID
    task_id: str = Field(min_length=1, max_length=128)
    task_type: TaskType
    execution_no: int = Field(ge=1)
    parameters: dict[str, JsonValue] = Field(default_factory=dict)
    creative_brief: ProductCreativeBrief | None = None
    reference_assets: tuple[CreativeReferenceAsset, ...] = Field(default=(), max_length=3)
    controlled_mask_asset: CreativeReferenceAsset | None = None
    traceparent: str | None = Field(
        default=None,
        pattern=r"^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$",
    )

    @model_validator(mode="after")
    def reject_media(self) -> "TaskProviderRequest":
        if self.task_type is TaskType.MEDIA:
            raise ValueError("MEDIA is owned by Java")
        if self.task_type not in {TaskType.IMAGE, TaskType.VIDEO} and (
            self.creative_brief is not None or self.reference_assets or self.controlled_mask_asset
        ):
            raise ValueError("creative references are valid only for IMAGE or VIDEO")
        if self.creative_brief is not None:
            self.creative_brief.validate_reference_assets(self.reference_assets)
        if self.controlled_mask_asset is not None and (
            self.task_type is not TaskType.IMAGE
            or self.creative_brief is None
            or self.creative_brief.source_mode.value != "REGION_EDIT"
            or self.creative_brief.creative_direction.get("maskAssetId")
            != self.controlled_mask_asset.asset_id
            or self.parameters.get("maskAssetId") != self.controlled_mask_asset.asset_id
        ):
            raise ValueError("mask asset must match the authorized region-edit brief")
        return self


class QwenImageProviderContinuation(ProviderModel):
    """Java-authorized identity for polling one previously submitted image task."""

    provider: Literal["QWEN_IMAGE"]
    model: Literal["qwen-image-3.0"]
    provider_request_id: UUID
    provider_task_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,256}$")
    submission_state: Literal[SubmissionState.UNKNOWN] = SubmissionState.UNKNOWN


class WanVideoProviderContinuation(ProviderModel):
    """Authorized identity for polling one previously submitted Wan video task."""

    provider: Literal["WAN_VIDEO"]
    model: Literal["wan3.0-video", "wan3.0-video-prime"]
    provider_request_id: UUID
    provider_task_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,256}$")
    submission_state: Literal[SubmissionState.UNKNOWN] = SubmissionState.UNKNOWN


class WanImageEditProviderContinuation(ProviderModel):
    """Authorized identity for polling one previously submitted mask-edit job."""

    provider: Literal["WAN_IMAGE_EDIT"]
    model: Literal["wanx2.1-imageedit"]
    provider_request_id: UUID
    provider_task_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,256}$")
    submission_state: Literal[SubmissionState.UNKNOWN] = SubmissionState.UNKNOWN


TaskProviderContinuation = (
    QwenImageProviderContinuation | WanVideoProviderContinuation | WanImageEditProviderContinuation
)


class ProviderUsage(ProviderModel):
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    images: int = Field(default=0, ge=0)
    video_seconds: int = Field(default=0, ge=0)
    provider_cost: str | None = Field(default=None, pattern=r"^\d+\.\d{4}$")
    currency: Literal["CNY"] | None = None

    @model_validator(mode="after")
    def cost_and_currency_are_both_reported_or_both_unknown(self) -> "ProviderUsage":
        if (self.provider_cost is None) != (self.currency is None):
            raise ValueError("provider cost and currency must both be reported or both be unknown")
        return self


class ProviderArtifact(ProviderModel):
    artifact_type: Literal[
        "GEO_PLATFORM_SAMPLE",
        "GEO_REPORT",
        "ARTICLE_CONTENT",
        "IMAGE",
        "VIDEO",
    ]
    unit_key: str | None = Field(default=None, max_length=256)
    schema_version: Literal[1] = 1
    provider_url: str | None = Field(default=None, max_length=2_048)
    content_type: str | None = Field(default=None, max_length=128)
    size_bytes: int | None = Field(default=None, ge=0)
    sha256: str | None = Field(default=None, pattern=r"^[a-f0-9]{64}$")
    expires_at: datetime | None = None
    inline_data: dict[str, JsonValue] | None = None
    metadata: dict[str, JsonValue] = Field(default_factory=dict)

    @model_validator(mode="after")
    def require_exactly_one_payload(self) -> "ProviderArtifact":
        if (self.provider_url is None) == (self.inline_data is None):
            raise ValueError("artifact requires exactly one URL or inline payload")
        return self


class TaskProviderResult(ProviderModel):
    provider: str = Field(min_length=1, max_length=64)
    model: str = Field(min_length=1, max_length=128)
    request_id: UUID
    provider_task_id: str | None = Field(default=None, max_length=256)
    submission_state: Literal[SubmissionState.CONFIRMED] = SubmissionState.CONFIRMED
    artifacts: tuple[ProviderArtifact, ...] = Field(min_length=1, max_length=100)
    usage: ProviderUsage = Field(default_factory=ProviderUsage)
    latency_ms: int = Field(ge=0)


class TaskProviderError(RuntimeError):
    """Sanitized Provider failure with explicit retry and submit certainty."""

    def __init__(
        self,
        code: str,
        *,
        category: ProviderErrorCategory,
        retryable: bool,
        submission_state: SubmissionState,
        retry_after_seconds: float | None = None,
        provider_request_id: str | None = None,
        provider_task_id: str | None = None,
    ) -> None:
        self.code = code
        self.category = category
        self.retryable = retryable
        self.submission_state = submission_state
        self.retry_after_seconds = retry_after_seconds
        self.provider_request_id = provider_request_id
        self.provider_task_id = provider_task_id
        super().__init__(code)


class TaskProviderAdapter(Protocol):
    @property
    def code(self) -> str: ...

    @property
    def model(self) -> str: ...

    @property
    def is_mock(self) -> bool: ...

    @property
    def status(self) -> ProviderStatus: ...

    @property
    def capabilities(self) -> frozenset[TaskProviderCapability]: ...

    async def probe(self) -> bool: ...

    async def execute(self, request: TaskProviderRequest) -> TaskProviderResult: ...

    async def aclose(self) -> None: ...


@runtime_checkable
class ResumableTaskProviderAdapter(Protocol):
    """Optional capability for adapters that can resume without another submit."""

    async def resume(
        self,
        request: TaskProviderRequest,
        continuation: TaskProviderContinuation,
    ) -> TaskProviderResult: ...
