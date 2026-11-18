"""Strict, provider-neutral GEO observations and synthesis output."""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Annotated, Literal, cast
from urllib.parse import urlsplit

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    PrivateAttr,
    StrictBool,
    StrictInt,
    field_validator,
    model_validator,
)


def _to_camel(value: str) -> str:
    first, *rest = value.split("_")
    return first + "".join(part.capitalize() for part in rest)


class _GeoModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=_to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        strict=True,
    )


class GeoPlatformCode(StrEnum):
    DOUBAO = "DOUBAO"
    DEEPSEEK = "DEEPSEEK"
    QWEN = "QWEN"
    WENXIN = "WENXIN"
    KIMI = "KIMI"
    YUANBAO = "YUANBAO"
    ZHIPU = "ZHIPU"


class GeoObservationStatus(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


class GeoVisibilityEvidenceUnit(_GeoModel):
    # Java's full-answer name check, never recomputed from an analysis excerpt.

    question_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    platform_code: str = Field(pattern=r"^(DOUBAO|DEEPSEEK|QWEN|WENXIN|KIMI|YUANBAO|ZHIPU)$")
    sample_no: StrictInt = Field(ge=1, le=3)
    target_mentioned: StrictBool | None

    @field_validator("question_id")
    @classmethod
    def require_signed_long(cls, value: str) -> str:
        if int(value) > 9_223_372_036_854_775_807:
            raise ValueError("Snowflake ID exceeds the Java signed long range")
        return value


class GeoVisibilityEvidence(_GeoModel):
    target_status: Literal["AVAILABLE", "TARGET_UNRESOLVED"]
    matching_version: Literal["explicit-name-mention-v1"]
    # Public recovery requests require at least one saved observation; only the
    # signed page metadata subtype may describe an initially empty saved matrix.
    units: tuple[GeoVisibilityEvidenceUnit, ...] = Field(min_length=1, max_length=210)

    @field_validator("units", mode="before")
    @classmethod
    def freeze_units(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class GeoCapabilityStatus(StrEnum):
    DIRECT_SEARCH_WITH_CITATIONS = "DIRECT_SEARCH_WITH_CITATIONS"
    DIRECT_SEARCH_NO_STRUCTURED_CITATIONS = "DIRECT_SEARCH_NO_STRUCTURED_CITATIONS"
    DIRECT_MODEL_NO_SEARCH = "DIRECT_MODEL_NO_SEARCH"
    RATE_LIMITED = "RATE_LIMITED"
    CONFIG_MISSING = "CONFIG_MISSING"
    AUTH_FAILED = "AUTH_FAILED"
    CONTRACT_UNSUPPORTED = "CONTRACT_UNSUPPORTED"


class GeoCitation(_GeoModel):
    title: str | None = Field(default=None, max_length=500)
    url: str = Field(min_length=8, max_length=2_048)
    domain: str | None = Field(default=None, max_length=253)
    snippet: str | None = Field(default=None, max_length=2_000)
    observed_at: datetime

    @field_validator("observed_at", mode="before")
    @classmethod
    def decode_iso_time(cls, value: object) -> object:
        # GeoObservation freezes the JSON citation list into a tuple before nested
        # validation; decode only ISO strings (never numeric epochs) at this boundary.
        return datetime.fromisoformat(value) if isinstance(value, str) else value

    @field_validator("url")
    @classmethod
    def validate_url(cls, value: str) -> str:
        parsed = urlsplit(value)
        if (
            parsed.scheme not in {"http", "https"}
            or parsed.hostname is None
            or parsed.username is not None
            or parsed.password is not None
        ):
            raise ValueError("citation URL must be a credential-free HTTP(S) URL")
        return value

    @field_validator("domain")
    @classmethod
    def normalize_domain(cls, value: str | None) -> str | None:
        return value.casefold() if value is not None else None

    @field_validator("observed_at")
    @classmethod
    def require_aware_time(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("observedAt must include a timezone")
        return value


class GeoProviderUsage(_GeoModel):
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    search_requests: int = Field(default=0, ge=0)
    monetary_cost: str | None = Field(default=None, max_length=64, pattern=r"^\d+(\.\d+)?$")
    currency: str | None = Field(default=None, pattern=r"^[A-Z]{3}$")

    @model_validator(mode="after")
    def cost_and_currency_move_together(self) -> GeoProviderUsage:
        if (self.monetary_cost is None) != (self.currency is None):
            raise ValueError("monetaryCost and currency must both be present or absent")
        return self

    def __add__(self, other: GeoProviderUsage) -> GeoProviderUsage:
        # Monetary cost cannot be summed unless both Providers report the same
        # verifiable currency. Current platform adapters intentionally leave it unknown.
        cost: str | None = None
        currency: str | None = None
        if (
            self.monetary_cost is not None
            and other.monetary_cost is not None
            and self.currency == other.currency
        ):
            from decimal import Decimal

            cost = str(Decimal(self.monetary_cost) + Decimal(other.monetary_cost))
            currency = self.currency
        return GeoProviderUsage(
            input_tokens=self.input_tokens + other.input_tokens,
            output_tokens=self.output_tokens + other.output_tokens,
            search_requests=self.search_requests + other.search_requests,
            monetary_cost=cost,
            currency=currency,
        )


class GeoObservation(_GeoModel):
    platform_code: GeoPlatformCode
    question_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9._-]+$")
    sample_no: int = Field(ge=1, le=3)
    status: GeoObservationStatus
    answer_text: str | None = Field(default=None, min_length=1, max_length=100_000)
    citations: tuple[GeoCitation, ...] = Field(default=(), max_length=50)
    provider: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z0-9_]+$")
    model: str = Field(min_length=1, max_length=128)
    collection_method: str | None = Field(
        default=None, max_length=128, pattern=r"^[a-z][a-z0-9-]{0,127}$"
    )
    started_at: datetime
    finished_at: datetime
    latency_ms: int = Field(ge=0, le=3_600_000)
    stable_error_code: str | None = Field(
        default=None,
        min_length=1,
        max_length=128,
        pattern=r"^[A-Z][A-Z0-9_]{0,127}$",
    )
    usage: GeoProviderUsage = Field(default_factory=GeoProviderUsage)
    provider_call_count: int = Field(ge=0, le=100)
    _retry_not_before: datetime | None = PrivateAttr(default=None)
    _retry_wait_limit_seconds: float = PrivateAttr(default=120.0)

    @property
    def retry_not_before(self) -> datetime | None:
        return self._retry_not_before

    @property
    def retry_wait_limit_seconds(self) -> float:
        return self._retry_wait_limit_seconds

    def set_retry_window(self, not_before: datetime | None, wait_limit_seconds: float) -> None:
        # PrivateAttrs are deliberately excluded from JSON, schema and persistence.
        self._retry_not_before = not_before
        self._retry_wait_limit_seconds = wait_limit_seconds

    @field_validator("citations", mode="before")
    @classmethod
    def normalize_citations(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @field_validator("started_at", "finished_at")
    @classmethod
    def require_aware_time(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("observation timestamps must include a timezone")
        return value

    @model_validator(mode="after")
    def validate_terminal_shape(self) -> GeoObservation:
        if self.finished_at < self.started_at:
            raise ValueError("finishedAt cannot precede startedAt")
        if self.status is GeoObservationStatus.SUCCEEDED:
            if self.answer_text is None or self.stable_error_code is not None:
                raise ValueError("successful observation requires answer and no error")
        elif self.answer_text is not None or self.citations or self.stable_error_code is None:
            raise ValueError("failed observation requires only a stable error")
        return self


class GeoCapability(_GeoModel):
    platform_code: GeoPlatformCode
    status: GeoCapabilityStatus
    provider: str
    model: str | None = None
    endpoint_host: str | None = None
    direct_api_available: bool = False
    online_search_supported: bool = False
    citation_supported: bool = False
    real_request_count: int = Field(default=0, ge=0)
    sample_succeeded: bool = False
    stable_error_code: str | None = None


class GeoRecommendation(_GeoModel):
    priority: str = Field(pattern=r"^P[012]$")
    title: str = Field(min_length=1, max_length=300)
    rationale: str = Field(min_length=1, max_length=1_000)


GeoSynthesisText = Annotated[str, Field(min_length=1, max_length=1_000)]
GeoMissingSample = Annotated[
    str,
    Field(
        min_length=1,
        max_length=256,
        pattern=(
            r"^[1-9][0-9]{0,18}:"
            r"(DOUBAO|DEEPSEEK|QWEN|WENXIN|KIMI|YUANBAO|ZHIPU):[1-3]$"
        ),
    ),
]


class GeoCompetitorEvidence(_GeoModel):
    unit_key: GeoMissingSample
    quote: str = Field(min_length=1, max_length=240)
    signal: Literal["MENTION", "POSITIVE_ATTRIBUTE", "NEGATIVE_ATTRIBUTE"]


class GeoCompetitorCandidate(_GeoModel):
    name: str = Field(min_length=2, max_length=150)
    entity_kind: Literal["BRAND", "PRODUCT"]
    evidence: tuple[GeoCompetitorEvidence, ...] = Field(min_length=1, max_length=2)

    @field_validator("evidence", mode="before")
    @classmethod
    def normalize_evidence(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class GeoSynthesisAnalysis(_GeoModel):
    """Model-owned analysis only; runtime protocol fields are never model generated."""

    summary: str = Field(min_length=1, max_length=2_000)
    coverage_gaps: tuple[GeoSynthesisText, ...] = Field(max_length=12)
    citation_structure: tuple[GeoSynthesisText, ...] = Field(max_length=12)
    competitor_signals: tuple[GeoSynthesisText, ...] = Field(max_length=12)
    findings: tuple[GeoSynthesisText, ...] = Field(max_length=16)
    recommendations: tuple[GeoRecommendation, ...] = Field(max_length=8)
    content_blueprint: tuple[GeoSynthesisText, ...] = Field(max_length=12)
    competitor_candidates: tuple[GeoCompetitorCandidate, ...] = Field(max_length=6)

    @field_validator(
        "coverage_gaps",
        "citation_structure",
        "competitor_signals",
        "findings",
        "recommendations",
        "content_blueprint",
        "competitor_candidates",
        mode="before",
    )
    @classmethod
    def normalize_json_arrays(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class GeoSynthesis(GeoSynthesisAnalysis):
    # Stored legacy reports may omit optional arrays; new model output may not.
    coverage_gaps: tuple[GeoSynthesisText, ...] = Field(default=(), max_length=12)
    citation_structure: tuple[GeoSynthesisText, ...] = Field(default=(), max_length=12)
    competitor_signals: tuple[GeoSynthesisText, ...] = Field(default=(), max_length=12)
    findings: tuple[GeoSynthesisText, ...] = Field(default=(), max_length=16)
    recommendations: tuple[GeoRecommendation, ...] = Field(default=(), max_length=8)
    content_blueprint: tuple[GeoSynthesisText, ...] = Field(default=(), max_length=12)
    competitor_candidates: tuple[GeoCompetitorCandidate, ...] = Field(default=(), max_length=6)
    competitor_discovery_version: Literal["geo-competitor-discovery-v1"] | None = None
    missing_samples: tuple[GeoMissingSample, ...] = Field(default=(), max_length=100)
    method_version: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9._-]+$")

    @field_validator("missing_samples", mode="before")
    @classmethod
    def normalize_missing_samples(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def validate_discovery_provenance(self) -> GeoSynthesis:
        if self.competitor_candidates and self.competitor_discovery_version is None:
            raise ValueError("competitor candidates require a discovery version")
        if self.method_version == "geo-deterministic-v1" and self.competitor_discovery_version:
            raise ValueError("fallback cannot claim model competitor discovery")
        return self
