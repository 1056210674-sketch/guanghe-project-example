"""Strict domain values shared by the GEO Worker and Java control-plane adapter."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum
from typing import Annotated, Literal, cast

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    StrictInt,
    StrictStr,
    StringConstraints,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

from app.geo.diagnostic_models import (
    DiagnosticCompetitor,
    DiagnosticConfirmedProfile,
    DiagnosticSource,
    Name,
    SourceId,
)
from app.geo.models import GeoPlatformCode, GeoSynthesis
from app.model_gateway.gateway import GatewayUsage

_MAX_SIGNED_LONG_TEXT = "9223372036854775807"


def _signed_long_range(value: str) -> str:
    if len(value) > len(_MAX_SIGNED_LONG_TEXT) or (
        len(value) == len(_MAX_SIGNED_LONG_TEXT) and value > _MAX_SIGNED_LONG_TEXT
    ):
        raise ValueError("Snowflake ID exceeds the Java signed long range")
    return value


SnowflakeId = Annotated[
    StrictStr,
    StringConstraints(pattern=r"^[1-9][0-9]{0,18}$"),
    AfterValidator(_signed_long_range),
]
Text100 = Annotated[StrictStr, StringConstraints(min_length=1, max_length=100)]
Text150 = Annotated[StrictStr, StringConstraints(min_length=1, max_length=150)]
Text1000 = Annotated[StrictStr, StringConstraints(min_length=1, max_length=1_000)]


class _GeoWorkerModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        strict=True,
        hide_input_in_errors=True,
    )


class GeoSampleMode(StrEnum):
    STANDARD = "STANDARD"
    DEEP = "DEEP"

    @property
    def samples_per_combination(self) -> int:
        return 1 if self is GeoSampleMode.STANDARD else 3


class GeoTaskParameters(_GeoWorkerModel):
    """The unchanged six-field monitor matrix shared by proposals and Workers."""

    mode: Literal["MONITOR_RUN"]
    topic_id: SnowflakeId
    question_ids: tuple[SnowflakeId, ...] = Field(min_length=3, max_length=10)
    platform_codes: tuple[
        Literal["DOUBAO", "DEEPSEEK", "QWEN", "WENXIN", "KIMI", "YUANBAO", "ZHIPU"], ...
    ] = Field(min_length=1, max_length=7)
    sample_mode: Literal["STANDARD", "DEEP"]
    region: Text100

    @field_validator("question_ids", "platform_codes", mode="before")
    @classmethod
    def arrays_must_be_frozen(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def arrays_must_be_unique(self) -> GeoTaskParameters:
        if len(self.question_ids) != len(set(self.question_ids)) or len(self.platform_codes) != len(
            set(self.platform_codes)
        ):
            raise ValueError("GEO task matrix values must be unique")
        return self


class GeoDiagnosticTaskParameters(GeoTaskParameters):
    # Frozen Pydantic wire variants are immutable; changing the literal narrows
    # this separate worker-only branch without widening ordinary chat proposals.
    mode: Literal["DIAGNOSTIC_RUN"]  # pyright: ignore[reportIncompatibleVariableOverride]
    sample_mode: Literal["STANDARD"]  # pyright: ignore[reportIncompatibleVariableOverride]

    @model_validator(mode="after")
    def standard_only(self) -> GeoDiagnosticTaskParameters:
        if self.sample_mode != "STANDARD":
            raise ValueError("Diagnostic sampling must use STANDARD mode")
        return self


class GeoObservationDisposition(StrEnum):
    CREATED = "CREATED"
    DUPLICATE = "DUPLICATE"


class GeoTopicSnapshot(_GeoWorkerModel):
    id: SnowflakeId
    space_id: SnowflakeId
    product_id: SnowflakeId | None
    name: Text100
    region: str | None = Field(max_length=100)
    version: StrictInt = Field(ge=1)


class GeoQuestionSnapshot(_GeoWorkerModel):
    id: SnowflakeId
    category: Literal["BRAND_DISCOVERY"]
    text: Text1000


class GeoCompetitorSnapshot(_GeoWorkerModel):
    id: SnowflakeId
    name: Text150
    aliases: tuple[Text150, ...]
    competitor_type: Literal["DIRECT", "INDIRECT", "SUBSTITUTE"]

    @field_validator("aliases", mode="before")
    @classmethod
    def freeze_aliases(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class GeoMonitoringTarget(_GeoWorkerModel):
    """Identity resolved by Java from this run's immutable catalog snapshot."""

    kind: Literal["PRODUCT", "BRAND"]
    name: Text150
    aliases: tuple[Text150, ...] = Field(max_length=50)

    @field_validator("aliases", mode="before")
    @classmethod
    def freeze_aliases(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class GeoSynthesisContext(_GeoWorkerModel):
    topic: GeoTopicSnapshot
    questions: tuple[GeoQuestionSnapshot, ...] = Field(min_length=3, max_length=10)
    competitor_set_version: StrictInt | None = Field(default=None, ge=1)
    competitors: tuple[GeoCompetitorSnapshot, ...]
    # Older Java contexts remain readable; absence is not permission to treat
    # the topic title or question wording as a product/brand identity.
    monitoring_target: GeoMonitoringTarget | None = None

    @field_validator("questions", "competitors", mode="before")
    @classmethod
    def freeze_arrays(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class GeoDiagnosticQuestionSnapshot(_GeoWorkerModel):
    id: SnowflakeId
    category: Literal["BRAND_DISCOVERY", "BRAND_REPUTATION"]
    text: Text1000


class GeoDiagnosticCompetitorSnapshot(_GeoWorkerModel):
    id: SourceId
    name: Name
    aliases: tuple[Name, ...] = Field(max_length=50)
    competitor_type: Literal["DIRECT"]

    @field_validator("aliases", mode="before")
    @classmethod
    def freeze_aliases(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class GeoDiagnosticMonitoringTarget(_GeoWorkerModel):
    kind: Literal["BRAND"]
    name: Name
    aliases: tuple[Name, ...] = Field(max_length=50)

    @field_validator("aliases", mode="before")
    @classmethod
    def freeze_aliases(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class GeoDiagnosticSnapshot(_GeoWorkerModel):
    diagnostic_id: SnowflakeId
    profile: DiagnosticConfirmedProfile
    competitors: tuple[DiagnosticCompetitor, ...] = Field(max_length=5)
    sources: tuple[DiagnosticSource, ...] = Field(max_length=32)
    warnings: tuple[Annotated[StrictStr, StringConstraints(max_length=1000)], ...] = Field(
        max_length=20
    )

    @field_validator("competitors", "sources", "warnings", mode="before")
    @classmethod
    def freeze_arrays(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def unique_sources(self) -> GeoDiagnosticSnapshot:
        if len({item.id for item in self.sources}) != len(self.sources) or len(
            {item.id for item in self.competitors}
        ) != len(self.competitors):
            raise ValueError("Diagnostic snapshot IDs must be unique")
        return self


class GeoDiagnosticTopicSnapshot(_GeoWorkerModel):
    id: SnowflakeId
    space_id: SnowflakeId
    product_id: None
    name: Text100
    region: Text100
    version: StrictInt = Field(ge=1)


class GeoDiagnosticSynthesisContext(_GeoWorkerModel):
    """Separate from ordinary discovery so reputation never enters its denominator."""

    topic: GeoDiagnosticTopicSnapshot
    questions: tuple[GeoDiagnosticQuestionSnapshot, ...] = Field(min_length=3, max_length=10)
    competitor_set_version: None
    competitors: tuple[GeoDiagnosticCompetitorSnapshot, ...] = Field(max_length=5)
    monitoring_target: GeoDiagnosticMonitoringTarget
    diagnostic: GeoDiagnosticSnapshot

    @field_validator("questions", "competitors", mode="before")
    @classmethod
    def freeze_arrays(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def same_confirmed_identities(self) -> GeoDiagnosticSynthesisContext:
        profile = self.diagnostic.profile
        frozen = {item.id: item for item in self.diagnostic.competitors}
        if (
            self.monitoring_target.name != profile.brand_name
            or not set(self.monitoring_target.aliases).issubset(
                {profile.brand_name, *profile.brand_terms}
            )
            or {item.id for item in self.competitors} != set(frozen)
            or any(
                item.name != frozen[item.id].name
                or not set(item.aliases).issubset(
                    {frozen[item.id].name, *frozen[item.id].brand_terms}
                )
                for item in self.competitors
            )
        ):
            raise ValueError("Diagnostic execution identities must match the confirmed snapshot")
        return self


class GeoSynthesisUsage(_GeoWorkerModel):
    provider: Literal["DEEPSEEK", "SYSTEM"] = "DEEPSEEK"
    model: StrictStr
    input_tokens: StrictInt = Field(ge=0)
    output_tokens: StrictInt = Field(ge=0)
    provider_call_count: StrictInt = Field(ge=0, le=10)
    latency_ms: StrictInt = Field(ge=0, le=3_600_000)

    @classmethod
    def from_gateway(cls, model: str, usage: GatewayUsage) -> GeoSynthesisUsage:
        return cls(
            model=model,
            input_tokens=usage.input_tokens,
            output_tokens=usage.output_tokens,
            provider_call_count=usage.request_count,
            latency_ms=usage.latency_ms,
        )

    @classmethod
    def deterministic(cls) -> GeoSynthesisUsage:
        return cls(
            provider="SYSTEM",
            model="deterministic-geo-fallback-v1",
            input_tokens=0,
            output_tokens=0,
            provider_call_count=0,
            latency_ms=0,
        )

    @classmethod
    def diagnostic_sampling(cls) -> GeoSynthesisUsage:
        return cls(
            provider="SYSTEM",
            model="deterministic-geo-diagnostic-sampling-v1",
            input_tokens=0,
            output_tokens=0,
            provider_call_count=0,
            latency_ms=0,
        )


@dataclass(frozen=True, slots=True)
class GeoExecutionContext:
    task_id: str
    execution_no: int
    topic_id: str
    question_ids: tuple[str, ...]
    platform_codes: tuple[GeoPlatformCode, ...]
    sample_mode: GeoSampleMode
    region: str
    synthesis_context: GeoSynthesisContext | GeoDiagnosticSynthesisContext
    completed_unit_keys: frozenset[str]
    max_provider_calls: int
    max_geo_questions: int
    max_geo_platforms: int
    samples_per_combination: int
    max_concurrency: int
    deadline_at: datetime
    mode: Literal["MONITOR_RUN", "DIAGNOSTIC_RUN"] = "MONITOR_RUN"

    def __post_init__(self) -> None:
        if self.mode == "DIAGNOSTIC_RUN":
            if (
                not isinstance(self.synthesis_context, GeoDiagnosticSynthesisContext)
                or self.sample_mode is not GeoSampleMode.STANDARD
            ):
                raise ValueError("Diagnostic sampling requires its frozen single-sample context")
        elif self.mode != "MONITOR_RUN" or not isinstance(
            self.synthesis_context, GeoSynthesisContext
        ):
            raise ValueError("Ordinary GEO requires its discovery-only context")
        bounded_values = (
            ("executionNo", self.execution_no, 1, 2_147_483_647),
            ("maxProviderCalls", self.max_provider_calls, 1, 1_000),
            ("maxGeoQuestions", self.max_geo_questions, 1, 10),
            ("maxGeoPlatforms", self.max_geo_platforms, 1, 7),
            ("maxConcurrency", self.max_concurrency, 1, 20),
        )
        for name, value, minimum, maximum in bounded_values:
            if isinstance(value, bool) or not minimum <= value <= maximum:
                raise ValueError(f"{name} is outside the frozen GEO bounds")
        if not self.question_ids or not self.platform_codes:
            raise ValueError("GEO matrix must include questions and platforms")
        expected_samples = self.sample_mode.samples_per_combination
        if self.samples_per_combination != expected_samples:
            raise ValueError("samplesPerCombination must match sampleMode")
        if len(self.question_ids) > self.max_geo_questions:
            raise ValueError("questionIds exceed maxGeoQuestions")
        if len(self.platform_codes) > self.max_geo_platforms:
            raise ValueError("platformCodes exceed maxGeoPlatforms")
        questions = self.synthesis_context.questions
        if tuple(question.id for question in questions) != self.question_ids:
            raise ValueError("questionIds must match authorized questions in order")
        if self.synthesis_context.topic.id != self.topic_id:
            raise ValueError("topicId must match authorized topic")
        if self.synthesis_context.topic.region != self.region:
            raise ValueError("region must match the authorized topic")
        if len(set(self.question_ids)) != len(self.question_ids):
            raise ValueError("questionIds must be unique")
        if len(set(self.platform_codes)) != len(self.platform_codes):
            raise ValueError("platformCodes must be unique")
        competitors = self.synthesis_context.competitors
        if len({item.id for item in competitors}) != len(competitors):
            raise ValueError("competitors must be unique")
        if self.deadline_at.tzinfo is None or self.deadline_at.utcoffset() is None:
            raise ValueError("deadlineAt must include an offset")
        expected_keys = self.unit_keys
        if not self.completed_unit_keys.issubset(expected_keys):
            raise ValueError("completedUnitKeys contain an unauthorized unit")

    @property
    def unit_keys(self) -> frozenset[str]:
        return frozenset(
            f"{question_id}:{platform.value}:{sample_no}"
            for question_id in self.question_ids
            for platform in self.platform_codes
            for sample_no in range(1, self.samples_per_combination + 1)
        )

    def units(self) -> tuple[tuple[str, GeoPlatformCode, int], ...]:
        return tuple(
            (question_id, platform, sample_no)
            for question_id in self.question_ids
            for platform in self.platform_codes
            for sample_no in range(1, self.samples_per_combination + 1)
        )

    def question_text(self, question_id: str) -> str:
        for question in self.synthesis_context.questions:
            if question.id == question_id:
                return question.text
        raise ValueError("questionId is not authorized")


@dataclass(frozen=True, slots=True)
class GeoCompletion:
    synthesis: GeoSynthesis
    synthesis_usage: GeoSynthesisUsage


@dataclass(frozen=True, slots=True)
class GeoExecutionOutcome:
    succeeded: bool
    java_task_status: str | None = None
    stable_error_code: str | None = None
