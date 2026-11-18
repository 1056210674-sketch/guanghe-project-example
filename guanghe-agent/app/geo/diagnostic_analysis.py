"""Narrative-only diagnostic delivery over Java-frozen metrics and saved evidence."""

from __future__ import annotations

import asyncio
import json
import math
import re
from datetime import UTC, datetime, timedelta
from functools import lru_cache
from pathlib import Path
from typing import Annotated, Literal, Self, cast

import yaml
from pydantic import (
    Field,
    StrictBool,
    StrictFloat,
    StrictInt,
    StrictStr,
    StringConstraints,
    field_validator,
    model_validator,
)
from pydantic_core import PydanticCustomError

from app.geo.diagnostic_models import (
    DiagnosticCompetitor,
    DiagnosticConfirmedProfile,
    DiagnosticSource,
    DiagnosticWire,
    Name,
    SourceId,
)
from app.geo.worker_models import GeoDiagnosticQuestionSnapshot, GeoSynthesisUsage, SnowflakeId
from app.harness.runtime import HarnessViolation, bind_model_call_boundary, estimate_model_messages
from app.model_gateway.gateway import (
    DiagnosticValidationCode,
    ModelGateway,
    ModelGatewayError,
    ModelMessage,
    ModelMessages,
    StructuredCompletionGateway,
    diagnostic_analysis_json_schema_messages,
    diagnostic_json_request_budget_messages,
    diagnostic_structured_repair_reserve,
)
from app.observability.tracing import start_span

PROMPT_VERSION = "GEO_DIAGNOSTIC_ANALYSIS_V11"
OPERATION = "readonly_geo_diagnostic_analysis"
_PROMPT_PATH = (
    Path(__file__).resolve().parents[1] / "prompts/bundles/geo-diagnostic-analysis-v11.yaml"
)
_MAX_INPUT_TOKENS = 24000
_MAX_INITIAL_TOKENS = 18000
_MAX_OUTPUT_TOKENS = 16384
_MAX_ANALYSIS_SECONDS = 120
# Exact Unicode punctuation is part of Java's code-owned stage tag.
_PREPARATION_NOTE_PREFIX = "准备阶段提示（不代表最终诊断报告的全部证据）："  # noqa: RUF001
_LINK = re.compile(r"(?:[a-z][a-z0-9+.-]*://|www\.|\]\(|<|>|```|~~~)", re.IGNORECASE)
_REPAIR = (
    "七字段 JSON，按 Schema/分类反馈重生成；"
    "标准 JSON 转义双引号/反斜杠/换行，无围栏/尾逗号/尾随字符；引文不改。"
)
Platform = Literal["DOUBAO", "DEEPSEEK", "QWEN", "WENXIN", "KIMI", "YUANBAO", "ZHIPU"]
UnitKey = Annotated[
    StrictStr,
    StringConstraints(
        pattern=r"^[1-9][0-9]{0,18}:(DOUBAO|DEEPSEEK|QWEN|WENXIN|KIMI|YUANBAO|ZHIPU):1$"
    ),
]
Count = Annotated[StrictInt, Field(ge=0, le=70)]
Limitation = Annotated[StrictStr, StringConstraints(min_length=1, max_length=1000)]


class _Counts(DiagnosticWire):
    expected_samples: Count
    valid_samples: Count
    failed_samples: Count
    missing_samples: Count

    @model_validator(mode="after")
    def coherent_counts(self) -> Self:
        if self.expected_samples != self.valid_samples + self.failed_samples + self.missing_samples:
            raise ValueError("Diagnostic counts must preserve failed and missing units")
        return self


class DiagnosticMention(DiagnosticWire):
    brand_id: SourceId
    name: Name
    mentioned_samples: Count
    mention_rate: Annotated[StrictFloat, Field(ge=0, le=1, allow_inf_nan=False)] | None


class DiagnosticDiscoveryMetrics(_Counts):
    platform_code: Platform
    mentions: tuple[DiagnosticMention, ...] = Field(min_length=1, max_length=6)

    @field_validator("mentions", mode="before")
    @classmethod
    def freeze_mentions(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def valid_denominator(self) -> Self:
        for item in self.mentions:
            if item.mentioned_samples > self.valid_samples:
                raise ValueError("Mention counts exceed the valid discovery denominator")
            if self.valid_samples == 0:
                if item.mentioned_samples or item.mention_rate is not None:
                    raise ValueError("Unavailable discovery visibility must remain null")
            elif item.mention_rate is None or not math.isclose(
                item.mention_rate,
                item.mentioned_samples / self.valid_samples,
                abs_tol=0.000001,
            ):
                raise ValueError("Discovery rates must match Java's valid-only denominator")
        return self


class DiagnosticReputationMetrics(_Counts):
    platform_code: Platform
    recognized_samples: Count

    @model_validator(mode="after")
    def bounded_recognition(self) -> Self:
        if self.recognized_samples > self.valid_samples:
            raise ValueError("Reputation recognition cannot exceed effective answers")
        return self


class DiagnosticMetrics(_Counts):
    discovery: tuple[DiagnosticDiscoveryMetrics, ...] = Field(min_length=1, max_length=7)
    reputation: tuple[DiagnosticReputationMetrics, ...] = Field(min_length=1, max_length=7)

    @field_validator("discovery", "reputation", mode="before")
    @classmethod
    def freeze_metrics(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class DiagnosticSample(DiagnosticWire):
    unit_key: UnitKey
    question_id: SnowflakeId
    category: Literal["BRAND_DISCOVERY", "BRAND_REPUTATION"]
    platform_code: Platform
    status: Literal["SUCCEEDED", "FAILED", "MISSING"]
    answer_excerpt: str | None = Field(max_length=1600)
    answer_truncated: StrictBool
    stable_error_code: (
        Annotated[StrictStr, StringConstraints(pattern=r"^[A-Z][A-Z0-9_]{0,127}$")] | None
    )
    source_ids: tuple[SourceId, ...] = Field(max_length=50)
    matched_brand_ids: tuple[SourceId, ...] | None = Field(
        default=None,
        max_length=6,
        description=(
            "Java full-answer matches: OWN and selected competitor IDs. New snapshots always "
            "send an array; FAILED/MISSING send []. Missing/null is unknown ONLY for immutable "
            "legacy V1 retry evidence, never zero and never recomputed from excerpts."
        ),
    )

    @field_validator("source_ids", "matched_brand_ids", mode="before")
    @classmethod
    def freeze_sources(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def actual_sample(self) -> Self:
        if self.unit_key != f"{self.question_id}:{self.platform_code}:1":
            raise ValueError("Diagnostic sample unit identity is inconsistent")
        if self.status == "SUCCEEDED":
            if not self.answer_excerpt or not self.answer_excerpt.strip() or self.stable_error_code:
                raise ValueError("Effective samples require actual answer evidence")
        elif (
            self.answer_excerpt is not None
            or self.answer_truncated
            or self.source_ids
            or self.matched_brand_ids
        ):
            raise ValueError("Failed or missing samples cannot masquerade as answer evidence")
        if len(set(self.source_ids)) != len(self.source_ids):
            raise ValueError("Sample source IDs must be unique")
        if self.matched_brand_ids is not None and len(set(self.matched_brand_ids)) != len(
            self.matched_brand_ids
        ):
            raise ValueError("Full-answer matched brand IDs must be unique")
        return self


class DiagnosticAnalysisSource(DiagnosticSource):
    snippet: str = Field(max_length=500)


class DiagnosticAnalysisEvidence(DiagnosticWire):
    profile: DiagnosticConfirmedProfile
    competitors: tuple[DiagnosticCompetitor, ...] = Field(max_length=5)
    questions: tuple[GeoDiagnosticQuestionSnapshot, ...] = Field(min_length=3, max_length=10)
    platform_codes: tuple[Platform, ...] = Field(min_length=1, max_length=7)
    metrics: DiagnosticMetrics
    samples: tuple[DiagnosticSample, ...] = Field(min_length=1, max_length=70)
    sources: tuple[DiagnosticAnalysisSource, ...] = Field(max_length=100)
    limitations: tuple[Limitation, ...] = Field(max_length=20)

    @field_validator(
        "competitors",
        "questions",
        "platform_codes",
        "samples",
        "sources",
        "limitations",
        mode="before",
    )
    @classmethod
    def freeze_arrays(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def frozen_matrix(self) -> Self:
        for values in (self.competitors, self.questions, self.sources):
            if len({item.id for item in values}) != len(values):
                raise ValueError("Diagnostic evidence identities must be unique")
        if len(set(self.platform_codes)) != len(self.platform_codes):
            raise ValueError("Diagnostic platforms must be unique")
        questions = {item.id: item for item in self.questions}
        expected = {
            f"{question.id}:{platform}:1"
            for question in self.questions
            for platform in self.platform_codes
        }
        if (
            len({item.unit_key for item in self.samples}) != len(self.samples)
            or {item.unit_key for item in self.samples} != expected
        ):
            raise ValueError("Diagnostic evidence must preserve the full single-sample matrix")
        source_ids = {item.id for item in self.sources}
        for item in self.samples:
            question = questions.get(item.question_id)
            if (
                question is None
                or question.category != item.category
                or not set(item.source_ids).issubset(source_ids)
            ):
                raise ValueError("Diagnostic sample must match its frozen question and sources")
        if not any(item.status == "SUCCEEDED" for item in self.samples):
            raise ValueError("All-unavailable delivery is deterministic in Java, not a model call")
        brand_names = {"OWN": self.profile.brand_name, **{c.id: c.name for c in self.competitors}}
        if "OWN" in {item.id for item in self.competitors}:
            raise ValueError("Competitor identity cannot replace the own-brand metric")
        if any(
            s.matched_brand_ids is not None and not set(s.matched_brand_ids).issubset(brand_names)
            for s in self.samples
        ):
            raise ValueError("Full-answer matched brand IDs must be frozen known brands")
        for category, rows in (
            ("BRAND_DISCOVERY", self.metrics.discovery),
            ("BRAND_REPUTATION", self.metrics.reputation),
        ):
            if {row.platform_code for row in rows} != set(self.platform_codes) or len(rows) != len(
                self.platform_codes
            ):
                raise ValueError("Classified metrics must cover each platform exactly once")
            for row in rows:
                samples = tuple(
                    s
                    for s in self.samples
                    if s.category == category and (s.platform_code == row.platform_code)
                )
                _match_counts(row, samples)
                if isinstance(row, DiagnosticDiscoveryMetrics) and (
                    {item.brand_id: item.name for item in row.mentions} != brand_names
                    or len(row.mentions) != len(brand_names)
                ):
                    raise ValueError("Discovery mentions must match the frozen brand identities")
                if isinstance(row, DiagnosticDiscoveryMetrics):
                    for mention in row.mentions:
                        _match_full_answer_signals(
                            samples, mention.brand_id, mention.mentioned_samples
                        )
                else:
                    _match_full_answer_signals(samples, "OWN", row.recognized_samples)
        _match_counts(self.metrics, self.samples)
        return self


def _match_counts(counts: _Counts, samples: tuple[DiagnosticSample, ...]) -> None:
    if (
        counts.expected_samples != len(samples)
        or counts.valid_samples != sum(s.status == "SUCCEEDED" for s in samples)
        or counts.failed_samples != sum(s.status == "FAILED" for s in samples)
        or counts.missing_samples != sum(s.status == "MISSING" for s in samples)
    ):
        raise ValueError("Java diagnostic counts must describe the frozen classified matrix")


def _match_full_answer_signals(
    samples: tuple[DiagnosticSample, ...], brand_id: str, aggregate: int
) -> None:
    valid = tuple(s for s in samples if s.status == "SUCCEEDED")
    known = sum(brand_id in s.matched_brand_ids for s in valid if s.matched_brand_ids is not None)
    unknown = sum(s.matched_brand_ids is None for s in valid)
    # Unknown legacy flags stay unknown. Complete known groups must agree exactly;
    # partial groups bound the aggregate without guessing any missing unit flags.
    if not known <= aggregate <= known + unknown:
        raise ValueError("Full-answer match flags conflict with Java aggregate metrics")


class DiagnosticAnalysisRequest(DiagnosticWire):
    """Frozen classified matrix; maximum 262144 UTF-8 bytes, no new collection."""

    job_id: SnowflakeId
    input_hash: Annotated[StrictStr, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
    deadline_at: datetime = Field(strict=False)
    title: Name
    evidence: DiagnosticAnalysisEvidence

    @field_validator("deadline_at", mode="before")
    @classmethod
    def iso_deadline(cls, value: object) -> object:
        if not isinstance(value, (str, datetime)):
            raise ValueError("Diagnostic deadline requires an ISO timestamp")
        return value

    @model_validator(mode="after")
    def bounded_request(self) -> Self:
        if self.deadline_at.utcoffset() != timedelta(0):
            raise ValueError("Diagnostic analysis deadline must be UTC")
        # Omitted legacy match flags must not inflate the old frozen wire with
        # newly defaulted null fields and reject a previously valid byte boundary.
        if len(self.model_dump_json(by_alias=True, exclude_unset=True).encode("utf-8")) > 262144:
            raise ValueError("Diagnostic analysis exceeds its frozen evidence byte budget")
        return self


class DiagnosticReportEvidence(DiagnosticWire):
    unit_key: UnitKey | None
    source_id: SourceId | None
    quote: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1000)]

    @model_validator(mode="after")
    def identified_quote(self) -> Self:
        if self.unit_key is None and self.source_id is None:
            raise PydanticCustomError(
                DiagnosticValidationCode.CITATION_SET.value, "Invalid evidence"
            )
        if not self.quote.strip():
            raise PydanticCustomError(
                DiagnosticValidationCode.QUOTE_ABSENT.value, "Invalid evidence"
            )
        return self


class DiagnosticFinding(DiagnosticWire):
    section: Literal["DISCOVERY", "REPUTATION", "COMPETITION", "PUBLIC_RESEARCH"]
    heading: Name
    description: Annotated[StrictStr, StringConstraints(min_length=1, max_length=2000)]
    evidence: tuple[DiagnosticReportEvidence, ...] = Field(min_length=1, max_length=5)

    @field_validator("evidence", mode="before")
    @classmethod
    def freeze_evidence(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class DiagnosticRecommendation(DiagnosticWire):
    priority: Literal["P0", "P1", "P2"]
    title: Name
    action: Annotated[StrictStr, StringConstraints(min_length=1, max_length=2000)]
    basis_evidence: tuple[DiagnosticReportEvidence, ...] = Field(min_length=1, max_length=5)
    current_state: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1000)] | None = (
        None
    )
    business_impact: (
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=800)] | None
    ) = None
    deliverable: Annotated[StrictStr, StringConstraints(min_length=1, max_length=600)] | None = None
    acceptance: Annotated[StrictStr, StringConstraints(min_length=1, max_length=600)] | None = None
    owner_role: Annotated[StrictStr, StringConstraints(min_length=1, max_length=120)] | None = None
    timeframe: Annotated[StrictStr, StringConstraints(min_length=1, max_length=120)] | None = None

    @field_validator("basis_evidence", mode="before")
    @classmethod
    def freeze_evidence(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


DiagnosticIntentLayer = Literal["BRAND", "CATEGORY", "SCENARIO", "QUESTION", "DECISION"]


class DiagnosticContentTopic(DiagnosticWire):
    title: Name
    audience_question: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1000)]
    angle: Annotated[StrictStr, StringConstraints(min_length=1, max_length=2000)]
    covered_brand_terms: tuple[Name, ...] = Field(max_length=50)
    basis_evidence: tuple[DiagnosticReportEvidence, ...] = Field(min_length=1, max_length=5)
    intent_layer: DiagnosticIntentLayer | None = None

    @field_validator("covered_brand_terms", "basis_evidence", mode="before")
    @classmethod
    def freeze_arrays(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class DiagnosticCoreJudgment(DiagnosticWire):
    headline: Annotated[StrictStr, StringConstraints(min_length=1, max_length=120)]
    description: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1600)]
    evidence: tuple[DiagnosticReportEvidence, ...] = Field(min_length=1, max_length=5)

    @field_validator("evidence", mode="before")
    @classmethod
    def freeze_evidence(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class DiagnosticCognitionFinding(DiagnosticWire):
    title: Annotated[StrictStr, StringConstraints(min_length=1, max_length=120)]
    observation: Annotated[StrictStr, StringConstraints(min_length=1, max_length=1200)]
    business_impact: Annotated[StrictStr, StringConstraints(min_length=1, max_length=800)]
    evidence: tuple[DiagnosticReportEvidence, ...] = Field(min_length=1, max_length=5)

    @field_validator("evidence", mode="before")
    @classmethod
    def freeze_evidence(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class DiagnosticKeywordLayer(DiagnosticWire):
    layer: DiagnosticIntentLayer
    question_ids: tuple[SnowflakeId, ...] = Field(min_length=1, max_length=10)
    assessment: Annotated[StrictStr, StringConstraints(min_length=1, max_length=800)]
    next_action: Annotated[StrictStr, StringConstraints(min_length=1, max_length=800)]
    evidence: tuple[DiagnosticReportEvidence, ...] = Field(min_length=1, max_length=5)

    @field_validator("question_ids", "evidence", mode="before")
    @classmethod
    def freeze_arrays(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class DiagnosticRoadmapStage(DiagnosticWire):
    stage: Annotated[StrictStr, StringConstraints(min_length=1, max_length=80)]
    objective: Annotated[StrictStr, StringConstraints(min_length=1, max_length=800)]
    recommendation_indexes: tuple[Annotated[StrictInt, Field(ge=0, le=14)], ...] = Field(
        min_length=1, max_length=15
    )

    @field_validator("recommendation_indexes", mode="before")
    @classmethod
    def freeze_indexes(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class DiagnosticStrategy(DiagnosticWire):
    version: Literal["consulting-v1"]
    core_judgment: DiagnosticCoreJudgment
    cognition_findings: tuple[DiagnosticCognitionFinding, ...] = Field(max_length=5)
    keyword_layers: tuple[DiagnosticKeywordLayer, ...] = Field(max_length=5)
    roadmap: tuple[DiagnosticRoadmapStage, ...] = Field(min_length=1, max_length=4)

    @field_validator("cognition_findings", "keyword_layers", "roadmap", mode="before")
    @classmethod
    def freeze_arrays(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value


class DiagnosticReport(DiagnosticWire):
    title: Name
    summary: Annotated[StrictStr, StringConstraints(min_length=1, max_length=4000)]
    findings: tuple[DiagnosticFinding, ...] = Field(min_length=1, max_length=20)
    recommendations: tuple[DiagnosticRecommendation, ...] = Field(min_length=1, max_length=15)
    content_topics: tuple[DiagnosticContentTopic, ...] = Field(min_length=1, max_length=10)
    limitations: tuple[Limitation, ...] = Field(max_length=20)
    # Optional on persisted historical reports; V11 generation requires a strategy below.
    strategy: DiagnosticStrategy | None = None

    @field_validator("findings", "recommendations", "content_topics", "limitations", mode="before")
    @classmethod
    def freeze_arrays(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def narrative_only(self) -> Self:
        text = [self.title, self.summary, *self.limitations]
        text.extend(value for item in self.findings for value in (item.heading, item.description))
        text.extend(value for item in self.recommendations for value in (item.title, item.action))
        text.extend(
            value
            for item in self.recommendations
            for value in (
                item.current_state,
                item.business_impact,
                item.deliverable,
                item.acceptance,
                item.owner_role,
                item.timeframe,
            )
            if value is not None
        )
        text.extend(
            value
            for item in self.content_topics
            for value in (item.title, item.audience_question, item.angle)
        )
        if self.strategy is not None:
            text.extend(
                (self.strategy.core_judgment.headline, self.strategy.core_judgment.description)
            )
            text.extend(
                value
                for item in self.strategy.cognition_findings
                for value in (item.title, item.observation, item.business_impact)
            )
            text.extend(
                value
                for item in self.strategy.keyword_layers
                for value in (item.assessment, item.next_action)
            )
            text.extend(
                value for item in self.strategy.roadmap for value in (item.stage, item.objective)
            )
        if any(not item.strip() or _LINK.search(item) for item in text):
            raise PydanticCustomError(
                DiagnosticValidationCode.PHASE_SHAPE.value, "Invalid narrative"
            )
        return self


class DiagnosticAnalysisResponse(DiagnosticWire):
    job_id: SnowflakeId
    input_hash: Annotated[StrictStr, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
    prompt_version: Literal["GEO_DIAGNOSTIC_ANALYSIS_V11"] = PROMPT_VERSION
    report: DiagnosticReport
    synthesis_usage: GeoSynthesisUsage


class DiagnosticAnalysisError(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@lru_cache(maxsize=1)
def diagnostic_analysis_prompt() -> str:
    try:
        loaded: object = yaml.safe_load(_PROMPT_PATH.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exc:
        raise DiagnosticAnalysisError("GEO_DIAGNOSTIC_ANALYSIS_PROMPT_INVALID") from exc
    if not isinstance(loaded, dict):
        raise DiagnosticAnalysisError("GEO_DIAGNOSTIC_ANALYSIS_PROMPT_INVALID")
    bundle = cast(dict[str, object], loaded)
    prompt = bundle.get("systemPrompt")
    if bundle.get("version") != PROMPT_VERSION or not isinstance(prompt, str) or not prompt.strip():
        raise DiagnosticAnalysisError("GEO_DIAGNOSTIC_ANALYSIS_PROMPT_INVALID")
    return prompt.strip()


class _ConsultingRecommendation(DiagnosticRecommendation):
    """Required V11 generation fields; persisted legacy recommendations stay readable."""

    # DiagnosticWire is frozen at runtime; Pyright cannot infer that through its
    # inherited ConfigDict. These deliberate, required refinements cannot be mutated.
    current_state: (  # pyright: ignore[reportGeneralTypeIssues, reportIncompatibleVariableOverride]
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=1000)]
    ) = Field(...)
    business_impact: (  # pyright: ignore[reportGeneralTypeIssues, reportIncompatibleVariableOverride]
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=800)]
    ) = Field(...)
    deliverable: (  # pyright: ignore[reportGeneralTypeIssues, reportIncompatibleVariableOverride]
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=600)]
    ) = Field(...)
    acceptance: (  # pyright: ignore[reportGeneralTypeIssues, reportIncompatibleVariableOverride]
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=600)]
    ) = Field(...)
    owner_role: (  # pyright: ignore[reportGeneralTypeIssues, reportIncompatibleVariableOverride]
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=120)]
    ) = Field(...)
    timeframe: (  # pyright: ignore[reportGeneralTypeIssues, reportIncompatibleVariableOverride]
        Annotated[StrictStr, StringConstraints(min_length=1, max_length=120)]
    ) = Field(...)


def _grounded_schema(evidence: DiagnosticAnalysisEvidence) -> type[DiagnosticReport]:
    samples = {s.unit_key: s for s in evidence.samples}
    sources = {s.id: s for s in evidence.sources}

    def check(ref: DiagnosticReportEvidence, section: str | None = None) -> None:
        sample = samples.get(ref.unit_key) if ref.unit_key is not None else None
        source = sources.get(ref.source_id) if ref.source_id is not None else None
        if (section in {"DISCOVERY", "REPUTATION", "COMPETITION"} and ref.unit_key is None) or (
            section == "PUBLIC_RESEARCH" and (ref.unit_key is not None or ref.source_id is None)
        ):
            raise PydanticCustomError(
                DiagnosticValidationCode.PHASE_SHAPE.value, "Invalid evidence"
            )
        if ref.unit_key is not None:
            if sample is None or sample.status != "SUCCEEDED":
                raise PydanticCustomError(
                    DiagnosticValidationCode.SOURCE_MISSING.value, "Invalid evidence"
                )
            expected = {
                "DISCOVERY": "BRAND_DISCOVERY",
                "REPUTATION": "BRAND_REPUTATION",
                "COMPETITION": "BRAND_DISCOVERY",
            }.get(section or "")
            if expected is not None and sample.category != expected:
                raise PydanticCustomError(
                    DiagnosticValidationCode.PHASE_SHAPE.value, "Invalid evidence"
                )
            if ref.quote not in (sample.answer_excerpt or ""):
                raise PydanticCustomError(
                    DiagnosticValidationCode.QUOTE_ABSENT.value, "Invalid evidence"
                )
        if ref.source_id is not None:
            if source is None:
                raise PydanticCustomError(
                    DiagnosticValidationCode.SOURCE_MISSING.value, "Invalid evidence"
                )
            if ref.quote not in source.snippet:
                raise PydanticCustomError(
                    DiagnosticValidationCode.QUOTE_ABSENT.value, "Invalid evidence"
                )
            if sample is not None and ref.source_id not in sample.source_ids:
                raise PydanticCustomError(
                    DiagnosticValidationCode.CITATION_SET.value, "Invalid evidence"
                )

    class _GroundedDiagnosticReport(DiagnosticReport):
        # As above, generation narrows the frozen legacy DTO without changing it.
        strategy: (  # pyright: ignore[reportGeneralTypeIssues, reportIncompatibleVariableOverride]
            DiagnosticStrategy
        ) = Field(...)
        recommendations: (  # pyright: ignore[reportIncompatibleVariableOverride]
            tuple[_ConsultingRecommendation, ...]
        ) = Field(min_length=1, max_length=15)

        @model_validator(mode="after")
        def actual_evidence(self) -> Self:
            if not _required_sections(evidence).issubset({item.section for item in self.findings}):
                raise PydanticCustomError(
                    DiagnosticValidationCode.PHASE_SHAPE.value, "Required sections are missing"
                )
            for finding in self.findings:
                for ref in finding.evidence:
                    check(ref, finding.section)
            for recommendation in self.recommendations:
                for ref in recommendation.basis_evidence:
                    check(ref)
            allowed_terms = {evidence.profile.brand_name, *evidence.profile.brand_terms}
            for topic in self.content_topics:
                if not set(topic.covered_brand_terms).issubset(allowed_terms):
                    raise PydanticCustomError(
                        DiagnosticValidationCode.ALIAS_PROOF.value, "Invalid topic terms"
                    )
                for ref in topic.basis_evidence:
                    check(ref)
            for ref in self.strategy.core_judgment.evidence:
                check(ref)
            for finding in self.strategy.cognition_findings:
                for ref in finding.evidence:
                    check(ref)
            question_by_id = {question.id: question for question in evidence.questions}
            layers: set[str] = set()
            for layer in self.strategy.keyword_layers:
                expected_category = (
                    "BRAND_REPUTATION" if layer.layer == "BRAND" else "BRAND_DISCOVERY"
                )
                if (
                    layer.layer in layers
                    or len(set(layer.question_ids)) != len(layer.question_ids)
                    or any(
                        question_id not in question_by_id
                        or question_by_id[question_id].category != expected_category
                        for question_id in layer.question_ids
                    )
                ):
                    raise PydanticCustomError(
                        DiagnosticValidationCode.PHASE_SHAPE.value,
                        "Keyword layers must identify sampled questions of the matching category",
                    )
                layers.add(layer.layer)
                for ref in layer.evidence:
                    check(ref, "REPUTATION" if layer.layer == "BRAND" else "DISCOVERY")
                    if (
                        ref.unit_key is None
                        or samples[ref.unit_key].question_id not in layer.question_ids
                    ):
                        raise PydanticCustomError(
                            DiagnosticValidationCode.CITATION_SET.value,
                            "Keyword evidence must belong to its selected questions",
                        )
            for stage in self.strategy.roadmap:
                if len(set(stage.recommendation_indexes)) != len(
                    stage.recommendation_indexes
                ) or any(
                    index >= len(self.recommendations) for index in stage.recommendation_indexes
                ):
                    raise PydanticCustomError(
                        DiagnosticValidationCode.PHASE_SHAPE.value,
                        "Roadmap indexes must resolve to distinct recommendations",
                    )
            return self

    return _GroundedDiagnosticReport


def _required_sections(evidence: DiagnosticAnalysisEvidence) -> frozenset[str]:
    sections: set[str] = set()
    if any(row.valid_samples for row in evidence.metrics.discovery):
        sections.add("DISCOVERY")
        if evidence.competitors:
            sections.add("COMPETITION")
    if any(row.valid_samples for row in evidence.metrics.reputation):
        sections.add("REPUTATION")
    return frozenset(sections)


def _measured_facts(evidence: DiagnosticAnalysisEvidence) -> tuple[str, ...]:
    """Human-readable counts from ORIGINAL Java metrics, never excerpt matching."""
    brand_ids = ("OWN", *(c.id for c in evidence.competitors))
    labels = ("自有", *(f"竞品{i}" for i in range(1, len(brand_ids))))

    def shares(counts: dict[str, int], valid: int) -> str:
        return "；".join(
            f"{label}:{counts[brand_id]}/{valid}份" if valid else f"{label}未知(无有效回答)"
            for brand_id, label in zip(brand_ids, labels, strict=True)
        )

    facts = ["被提及计数是有效回答份数，不是词出现次数；自然发现分母仅为该分类有效回答。"]
    total_valid = sum(row.valid_samples for row in evidence.metrics.discovery)
    total_mentions = dict.fromkeys(brand_ids, 0)
    for row in evidence.metrics.discovery:
        counts = {m.brand_id: m.mentioned_samples for m in row.mentions}
        facts.append(f"自然发现{row.platform_code}被提及: {shares(counts, row.valid_samples)}。")
        for brand_id in brand_ids:
            total_mentions[brand_id] += counts[brand_id]
    facts.append(f"自然发现合计(按回答份数加权): {shares(total_mentions, total_valid)}。")
    cited = sum(s.status == "SUCCEEDED" and bool(s.source_ids) for s in evidence.samples)
    facts.append(
        f"本次解读使用的证据保留来源{len(evidence.sources)}项；至少{cited}份有效回答在其中保留引用；"
        "完整原始引用数量以最终附录为准。供解读使用的摘录进一步裁剪不改变这些计数。"
    )
    return tuple(facts)


def _project_evidence(
    payload: DiagnosticAnalysisRequest,
) -> tuple[ModelMessages, DiagnosticAnalysisEvidence]:
    """Clip only the model view, while preserving all metrics and sample identities."""
    schema = _grounded_schema(payload.evidence).model_json_schema(by_alias=True)
    repair = diagnostic_structured_repair_reserve(_REPAIR, schema, output_mode="JSON_OBJECT")
    answer_limit, source_limit, context_limit, question_limit = 1600, 500, 2000, 500
    groups: dict[tuple[str, str], list[DiagnosticSample]] = {}
    for sample in payload.evidence.samples:
        if sample.status == "SUCCEEDED":
            groups.setdefault((sample.category, sample.platform_code), []).append(sample)
    # Deterministic round-robin across categories/platforms, never cherry-pick
    # mention-positive answers. Full metrics and all original samples remain Java's.
    candidates: list[DiagnosticSample] = []
    for index in range(max(len(group) for group in groups.values())):
        candidates.extend(group[index] for group in groups.values() if index < len(group))
    sample_count = len(candidates)
    # First preserve one real citation for every category/platform with sources.
    # Then take one citation per sample per round, not all citations from the
    # first sample. Selection remains independent of brand mentions.
    source_by_id = {source.id: source for source in payload.evidence.sources}
    group_sources = tuple(
        dict.fromkeys(
            source_id
            for group in groups.values()
            if (source_id := next((s.source_ids[0] for s in group if s.source_ids), None))
            is not None
        )
    )
    source_order = tuple(
        dict.fromkeys(
            (
                *group_sources,
                *[
                    sample.source_ids[index]
                    for index in range(max((len(s.source_ids) for s in candidates), default=0))
                    for sample in candidates
                    if index < len(sample.source_ids)
                ],
                *source_by_id,
            )
        )
    )
    ordered_sources = tuple(source_by_id[source_id] for source_id in source_order)
    source_count = len(ordered_sources)
    minimum_sources = min(1, source_count)
    grouped_source_floor = max(minimum_sources, len(group_sources))
    # Retain a broad first round before clipping source snippets. If needed,
    # reduce redundant sources down to group coverage before touching answers.
    preferred_sources = min(source_count, max(grouped_source_floor, min(16, sample_count)))
    longest_answer = max((len(s.answer_excerpt or "") for s in candidates), default=0)

    def build() -> tuple[ModelMessages, DiagnosticAnalysisEvidence]:
        original = payload.evidence
        # Java marks preparation warnings with this exact code-owned prefix.
        # Preserve frozen evidence/hash/history; omit only these prior-stage notes
        # from the narrative model view, never other sampling/legacy limitations.
        model_limitations = tuple(
            text for text in original.limitations if not text.startswith(_PREPARATION_NOTE_PREFIX)
        )
        sources = tuple(
            s.model_copy(update={"snippet": s.snippet[:source_limit]})
            for s in ordered_sources[:source_count]
        )
        source_ids = {s.id for s in sources}
        samples = tuple(
            s.model_copy(
                update={
                    "answer_excerpt": s.answer_excerpt[:answer_limit] if s.answer_excerpt else None,
                    "answer_truncated": s.answer_truncated
                    or bool(s.answer_excerpt and len(s.answer_excerpt) > answer_limit),
                    "source_ids": tuple(i for i in s.source_ids if i in source_ids),
                }
            )
            for s in candidates[:sample_count]
        )
        shown = original.model_copy(update={"samples": samples, "sources": sources})
        facts = shown.model_dump(mode="json", by_alias=True)
        # These semantic facts are not identity/output templates. Full original
        # aliases and all manually edited records remain authoritative in Java.
        facts["profile"] = {
            "brandName": original.profile.brand_name,
            "website": original.profile.website,
            "introduction": original.profile.introduction[:context_limit],
            "brandTerms": original.profile.brand_terms[:8],
            "region": original.profile.region,
        }
        facts["competitors"] = [
            {"brandIndex": index, "name": c.name, "reason": c.reason[: min(context_limit, 300)]}
            for index, c in enumerate(original.competitors, start=1)
        ]
        facts["questions"] = [
            {"id": q.id, "category": q.category, "text": q.text[:question_limit]}
            for q in original.questions
        ]
        # Unit keys encode question/platform identity; do not duplicate long
        # question prose, public URLs or brand labels in every matrix row.
        brand_id_order = ("OWN", *(c.id for c in original.competitors))
        brand_indices = {brand_id: index for index, brand_id in enumerate(brand_id_order)}
        question_indices = {question.id: index for index, question in enumerate(original.questions)}
        platform_indices = {
            platform: index for index, platform in enumerate(original.platform_codes)
        }
        facts["brandIdOrder"] = brand_id_order
        measured_facts = _measured_facts(original)
        # Under the tightest projection, omit duplicated prose for each platform,
        # not the authoritative per-platform metrics or any matrix unit/brand flag.
        facts["measuredFacts"] = (
            (measured_facts[0], *measured_facts[-2:]) if context_limit <= 100 else measured_facts
        )
        # Stable IDs may be 128 characters. Send each once, then reversibly encode
        # ALL unit match flags, including omitted excerpts and failed/missing units.
        facts["matrixByStatus"] = {
            status: {
                f"{question_indices[s.question_id]}:{platform_indices[s.platform_code]}": [
                    brand_indices[brand_id] for brand_id in s.matched_brand_ids
                ]
                if s.matched_brand_ids is not None
                else None
                for s in original.samples
                if s.status == status
            }
            for status in ("SUCCEEDED", "FAILED", "MISSING")
        }
        facts["samples"] = [
            {
                "unitKey": s.unit_key,
                "answerExcerpt": s.answer_excerpt,
                "excerptClipped": s.answer_truncated,
                **({"sourceIds": s.source_ids} if s.source_ids else {}),
            }
            for s in samples
        ]
        facts["limitations"] = [text[: min(context_limit, 300)] for text in model_limitations]
        facts["sources"] = [
            {"id": s.id, "title": s.title[:120], "url": s.url, "snippet": s.snippet}
            for s in sources
        ]

        def counts(row: _Counts) -> list[int]:
            return [
                row.expected_samples,
                row.valid_samples,
                row.failed_samples,
                row.missing_samples,
            ]

        # Field order is defined once in the immutable system bundle, not as an
        # output template. Every original metric value remains reversibly present.
        facts["metrics"] = {
            "counts": counts(original.metrics),
            "discovery": {
                row.platform_code: {
                    "counts": counts(row),
                    "mentions": [
                        [mention.mentioned_samples, mention.mention_rate]
                        for mention in sorted(row.mentions, key=lambda m: brand_indices[m.brand_id])
                    ],
                }
                for row in original.metrics.discovery
            },
            "reputation": {
                row.platform_code: {
                    "counts": counts(row),
                    "recognizedSamples": row.recognized_samples,
                }
                for row in original.metrics.reputation
            },
        }
        clipped = (
            any(s.answer_truncated for s in samples)
            or source_count < len(original.sources)
            or any(len(s.snippet) > source_limit for s in original.sources)
            or len(original.profile.introduction) > context_limit
            or any(len(c.reason) > min(context_limit, 300) for c in original.competitors)
            or any(len(q.text) > question_limit for q in original.questions)
            or sample_count < len(candidates)
            or len(model_limitations) < len(original.limitations)
            or any(len(text) > min(context_limit, 300) for text in model_limitations)
        )
        messages = (
            ModelMessage(
                role="system",
                content=diagnostic_analysis_prompt()
                + "\n[REQUIRED DIAGNOSTIC SECTIONS]\n"
                + ",".join(sorted(_required_sections(original)))
                + "\n以上每个 section 必须有独立 finding 和该分类真实证据；不可用公开研究替代。",
            ),
            ModelMessage(
                role="user",
                content=json.dumps(
                    {
                        "title": payload.title,
                        "evidence": facts,
                        "projection": {
                            "clipped": clipped,
                            "answerCharLimit": answer_limit,
                            "sourceCharLimit": source_limit,
                            "omittedSources": len(original.sources) - source_count,
                            "omittedEffectiveAnswers": len(candidates) - sample_count,
                            "sampleSelection": "CATEGORY_PLATFORM_ROUND_ROBIN_NOT_MENTION_SELECTED",
                            "quoteScope": "EXACT_DISPLAYED_ANSWER_EXCERPT_OR_SOURCE_SNIPPET_ONLY",
                        },
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
            ),
        )
        return messages, shown

    def fits(messages: ModelMessages) -> bool:
        json_messages = diagnostic_analysis_json_schema_messages(messages, schema)
        repaired = (
            ModelMessage(
                role="system",
                content=json_messages[0].content + "\n\n[STRUCTURED OUTPUT REPAIR]\n" + repair,
            ),
            *json_messages[1:],
        )
        return (
            estimate_model_messages(messages) <= _MAX_INITIAL_TOKENS
            and max(
                estimate_model_messages(diagnostic_json_request_budget_messages(json_messages)),
                estimate_model_messages(diagnostic_json_request_budget_messages(repaired)),
            )
            <= _MAX_INPUT_TOKENS
        )

    while True:
        messages, shown = build()
        if fits(messages):
            return messages, shown
        if source_count > preferred_sources:
            source_count = max(preferred_sources, source_count // 2)
        elif source_limit > 120:
            source_limit = max(120, source_limit // 2)
        elif source_count > grouped_source_floor:
            source_count -= 1
        elif answer_limit > 200:
            answer_limit = max(200, min(answer_limit - 50, math.ceil(longest_answer / 50) * 50))
        elif sample_count > min(14, len(candidates)):
            sample_count = max(min(14, len(candidates)), sample_count // 2)
        elif context_limit > 100:
            context_limit = max(100, context_limit // 2)
        elif question_limit > 50:
            question_limit = max(50, question_limit // 2)
        elif source_count > minimum_sources:
            # Only an extreme wire can exhaust the budget with one source per
            # group and minimal excerpts. Keep at least one actual source; full
            # metrics and all matrix units still remain present and unchanged.
            source_count -= 1
        elif answer_limit > 100:
            # Last resort for a maximal 70-unit matrix with long frozen IDs:
            # preserve every metric and group representative instead of dropping
            # the matrix, sources or expanding the fixed input budget.
            answer_limit -= 50
        else:
            raise DiagnosticAnalysisError("GEO_DIAGNOSTIC_ANALYSIS_INPUT_BUDGET_EXCEEDED")


async def analyze_diagnostic(
    payload: DiagnosticAnalysisRequest,
    gateway: ModelGateway,
    *,
    received_at: datetime,
) -> DiagnosticAnalysisResponse:
    if (
        gateway.identity.provider != "DEEPSEEK"
        or gateway.is_mock
        or not isinstance(gateway, StructuredCompletionGateway)
    ):
        raise DiagnosticAnalysisError("GEO_DIAGNOSTIC_ANALYSIS_MODEL_UNAVAILABLE")
    deadline = min(payload.deadline_at, received_at + timedelta(seconds=_MAX_ANALYSIS_SECONDS))
    remaining = (deadline - datetime.now(UTC)).total_seconds()
    if remaining <= 0:
        raise DiagnosticAnalysisError("GEO_DIAGNOSTIC_ANALYSIS_DEADLINE_EXCEEDED")
    messages, shown = _project_evidence(payload)
    with start_span(
        "geo.diagnostic.analysis",
        attributes={
            "model.name": gateway.identity.model,
            "geo.sampleCount": len(payload.evidence.samples),
            "prompt.version": PROMPT_VERSION,
        },
    ) as span:
        try:
            with bind_model_call_boundary(
                max_calls=2, deadline_at=deadline, max_input_tokens=_MAX_INPUT_TOKENS
            ):
                async with asyncio.timeout(remaining):
                    report, usage = await gateway.complete_structured(
                        messages,
                        _grounded_schema(shown),
                        operation=OPERATION,
                        max_tokens=_MAX_OUTPUT_TOKENS,
                        error_code="GEO_DIAGNOSTIC_ANALYSIS_RESPONSE_INVALID",
                        repair_instruction=_REPAIR,
                    )
        except TimeoutError as exc:
            span.fail("GEO_DIAGNOSTIC_ANALYSIS_DEADLINE_EXCEEDED")
            raise DiagnosticAnalysisError("GEO_DIAGNOSTIC_ANALYSIS_DEADLINE_EXCEEDED") from exc
        except (ModelGatewayError, HarnessViolation) as exc:
            span.fail(exc.code)
            raise DiagnosticAnalysisError(exc.code) from exc
        # Validation occurs inside the gateway, allowing exactly its existing one
        # repair; never strip unknown fields, unwrap payloads or drop bad evidence.
        span.set("usage.inputTokens", usage.input_tokens)
        span.set("usage.outputTokens", usage.output_tokens)
        span.set("usage.requestCount", usage.request_count)
        limitations = [
            "AI 口碑是本次平台回答的定性呈现，不代表消费者真实评价或市场份额。",
            "本报告是单次同批截面，不证明优化提升、前后变化或因果效果。",
            "失败和缺失样本是未知，不计为零；自然可见度仅使用发现问题的有效回答。",
            "正文引用来自已保存的回答摘录与来源；完整回答和来源可在报告附录核查。",
        ]
        if not any(row.valid_samples for row in payload.evidence.metrics.discovery):
            limitations.append("自然发现没有有效回答，无法判断本批自然可见度或竞品对照。")
        if not any(row.valid_samples for row in payload.evidence.metrics.reputation):
            limitations.append("口碑问题没有有效回答，本批无法形成 AI 口碑判断。")
        if any(sample.matched_brand_ids is None for sample in payload.evidence.samples):
            limitations.append(
                "部分历史样本没有记录全文品牌提及情况，不能仅凭短摘录判断全文是否提及。"
            )
        if (
            len(shown.samples) < payload.evidence.metrics.valid_samples
            or len(shown.sources) < len(payload.evidence.sources)
            or any(sample.answer_truncated for sample in shown.samples)
            or any(
                source.snippet
                != next(s.snippet for s in payload.evidence.sources if s.id == source.id)
                for source in shown.sources
            )
        ):
            limitations.append(
                "供解读使用的部分回答与来源摘录已裁剪或省略；原始回答并未因此截断，完整内容见附录。"
            )
        limitations.extend(report.limitations)
        report = report.model_copy(update={"limitations": tuple(dict.fromkeys(limitations))[:20]})
        return DiagnosticAnalysisResponse(
            job_id=payload.job_id,
            input_hash=payload.input_hash,
            report=report,
            synthesis_usage=GeoSynthesisUsage.from_gateway(gateway.identity.model, usage),
        )
