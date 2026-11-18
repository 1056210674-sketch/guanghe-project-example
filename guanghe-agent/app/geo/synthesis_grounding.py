"""Fact admission for interpretation; Java alone owns full-answer name matching."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from typing import Self

from pydantic import BaseModel, ConfigDict, model_validator

from app.geo.merchant_text import PLATFORM_NAMES
from app.geo.models import (
    GeoObservation,
    GeoObservationStatus,
    GeoSynthesisAnalysis,
    GeoVisibilityEvidence,
)
from app.geo.worker_models import GeoSynthesisContext


def validate_visibility_evidence(
    observations: tuple[GeoObservation, ...],
    context: BaseModel | None,
    evidence: GeoVisibilityEvidence,
) -> None:
    """Require the complete saved matrix, not a page, a fresh catalog or excerpts."""
    keys = [(item.question_id, item.platform_code.value, item.sample_no) for item in observations]
    unit_keys = [(item.question_id, item.platform_code, item.sample_no) for item in evidence.units]
    if not keys or len(set(keys)) != len(keys) or set(keys) != set(unit_keys):
        raise ValueError("GEO_SYNTHESIS_VISIBILITY_MATRIX_INVALID")
    if len(set(unit_keys)) != len(unit_keys):
        raise ValueError("GEO_SYNTHESIS_VISIBILITY_MATRIX_INVALID")
    available = evidence.target_status == "AVAILABLE"
    resolved = isinstance(context, GeoSynthesisContext) and context.monitoring_target is not None
    if available != resolved:
        raise ValueError("GEO_SYNTHESIS_VISIBILITY_TARGET_INVALID")
    flags = dict(zip(unit_keys, evidence.units, strict=True))
    for item, key in zip(observations, keys, strict=True):
        valid = item.status is GeoObservationStatus.SUCCEEDED and bool(
            (item.answer_text or "").strip()
        )
        if (flags[key].target_mentioned is not None) != (available and valid):
            raise ValueError("GEO_SYNTHESIS_VISIBILITY_STATUS_INVALID")


@dataclass(frozen=True, slots=True)
class SynthesisPlatformFacts:
    code: str
    valid: int
    failed: int
    mentioned: int | None

    @property
    def rate(self) -> float | None:
        # Java's method publishes a percentage rounded to two decimal places.
        return (
            None
            if self.mentioned is None or self.valid == 0
            else _mention_rate(self.mentioned, self.valid)
        )

    def projection(self) -> dict[str, object]:
        return {
            "platformCode": self.code,
            "platformName": PLATFORM_NAMES[self.code],
            "validSamples": self.valid,
            "failedSamples": self.failed,
            "mentionedSamples": self.mentioned,
            "mentionRate": self.rate,
        }


@dataclass(frozen=True, slots=True)
class SynthesisFacts:
    platforms: tuple[SynthesisPlatformFacts, ...]
    target_status: str

    def projection(self) -> dict[str, object]:
        return {
            "platformNames": PLATFORM_NAMES,
            "assessmentStatus": self.target_status,
            "matchingVersion": "explicit-name-mention-v1"
            if self.target_status != "UNVERIFIED"
            else None,
            "platforms": [item.projection() for item in self.platforms],
            "metricDefinition": (
                "明确提及的有效回答数 / 有效回答数；失败不作未提及；不等于推荐、排名或市场份额。"
            ),
            "authority": "JAVA_SAVED_FULL_ANSWER_FLAGS"
            if self.target_status != "UNVERIFIED"
            else "NO_FULL_ANSWER_FLAGS",
        }


def canonical_synthesis_facts(
    observations: tuple[GeoObservation, ...],
    context: BaseModel | None,
    evidence: GeoVisibilityEvidence | None,
) -> SynthesisFacts:
    if evidence is not None:
        validate_visibility_evidence(observations, context, evidence)
    flags = (
        {}
        if evidence is None
        else {
            (item.question_id, item.platform_code, item.sample_no): item.target_mentioned
            for item in evidence.units
        }
    )
    available = evidence is not None and evidence.target_status == "AVAILABLE"
    platforms: list[SynthesisPlatformFacts] = []
    for code in dict.fromkeys(item.platform_code.value for item in observations):
        samples = tuple(item for item in observations if item.platform_code.value == code)
        valid = tuple(
            item
            for item in samples
            if item.status is GeoObservationStatus.SUCCEEDED and (item.answer_text or "").strip()
        )
        platforms.append(
            SynthesisPlatformFacts(
                code=code,
                valid=len(valid),
                failed=sum(item.status is GeoObservationStatus.FAILED for item in samples),
                mentioned=sum(
                    flags[(item.question_id, code, item.sample_no)] is True for item in valid
                )
                if available and valid
                else None,
            )
        )
    status = (
        "UNVERIFIED"
        if evidence is None
        else (
            "UNAVAILABLE_TARGET"
            if not available
            else "AVAILABLE"
            if any(item.valid for item in platforms)
            else "NO_VALID_SAMPLES"
        )
    )
    return SynthesisFacts(tuple(platforms), status)


def grounded_analysis_schema(facts: SynthesisFacts) -> type[GeoSynthesisAnalysis]:
    """Same eight wire fields, fact-bound validators and schema guidance per invocation."""
    description = (
        "Interpret supplied saved evidence. Platform identity and full-answer metrics are fixed "
        "by canonicalFacts; never rename platforms, deny verified flags, recalculate from excerpts "
        "or treat failed/unresolved answers as zero. Canonical facts: "
        + json.dumps(facts.projection(), ensure_ascii=False, separators=(",", ":"))
    )

    class GroundedGeoSynthesisAnalysis(GeoSynthesisAnalysis):
        model_config = ConfigDict(
            hide_input_in_errors=True,
            json_schema_extra={"description": description},
        )

        @model_validator(mode="after")
        def require_consistent_facts(self) -> Self:
            validate_analysis_facts(self, facts)
            return self

    return GroundedGeoSynthesisAnalysis


def _platform_pattern(code: str) -> str:
    # One canonical registry applies to every platform, not a special DeepSeek patch.
    return "(?:" + "|".join(re.escape(value) for value in {code, PLATFORM_NAMES[code]}) + ")"


def _mention_rate(mentioned: int, valid: int) -> float:
    return float(
        (Decimal(100) * mentioned / valid).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
    )


def _scope(
    value: str, facts: SynthesisFacts, position: int
) -> tuple[tuple[SynthesisPlatformFacts, ...], bool] | None:
    """Only resolve explicit single-platform, total or each-platform clauses.

    A nearest-name guess is unsafe for mixed sentences. Ambiguous prose is not
    mechanically certified; the independent structured metric remains authoritative.
    """
    named = tuple(
        item
        for item in facts.platforms
        if re.search(_platform_pattern(item.code), value, re.IGNORECASE)
    )
    prefix = value[:position]
    if re.search(r"(?:各|均)\D{0,8}$", prefix):
        return (named or facts.platforms), True
    if re.search(r"(?:共有|合计|总计|总体|全部|本次|共)\D{0,8}$", prefix):
        return (named or facts.platforms), False
    if len(named) == 1:
        return named, False
    if not named and len(facts.platforms) == 1:
        return facts.platforms, False
    return None


def _future_claim(value: str, position: int) -> bool:
    prefix = value[:position]
    return bool(re.search(r"目标是|希望|预期|假设|下一次|下次", prefix)) and not bool(
        re.search(r"本次|当前|目前|本轮|已取得|已核验", prefix)
    )


def validate_analysis_facts(analysis: GeoSynthesisAnalysis, facts: SynthesisFacts) -> None:
    """Reject supported contradiction classes, never repair prose by string substitution.

    This is not an NLP proof of every free-text assertion. Structured metrics remain
    authoritative; these conservative guards cover identities, status and numeric claims.
    Quotes in competitorCandidates continue through their separate exact-evidence verifier.
    """
    values = (
        analysis.summary,
        *analysis.coverage_gaps,
        *analysis.citation_structure,
        *analysis.competitor_signals,
        *analysis.findings,
        *analysis.content_blueprint,
        *(item.title for item in analysis.recommendations),
        *(item.rationale for item in analysis.recommendations),
    )
    for value in values:
        # A parenthesized registry alias cannot mean a different registered platform.
        for code in PLATFORM_NAMES:
            for other in PLATFORM_NAMES:
                if code == other:
                    continue
                if re.search(
                    _platform_pattern(code)
                    + r"\s*(?:[\uff08(]\s*|(?:就是|即|又名|又称|对应平台为)\s*)"
                    + _platform_pattern(other)
                    + r"(?:\s*[\uff09)]|\b|(?=[\u3000-\u9fff]))",
                    value,
                    re.IGNORECASE,
                ):
                    raise ValueError("GEO_SYNTHESIS_PLATFORM_IDENTITY_CONTRADICTION")
        for sentence in re.split(r"[。；;\n，,]", value):
            if facts.target_status == "AVAILABLE" and (
                re.search(
                    r"(?:未提供|未收到|缺少|缺失|没有).{0,24}(?:全文|完整|名称).{0,16}(?:核验|核对|提及).{0,8}(?:结果|证据)",
                    sentence,
                )
                or re.search(
                    r"(?:无法|不能)(?:据此)?(?:断言|判断|确认|核验).{0,20}(?:完整回答.{0,8}提及|可见度(?:为|是)?\s*0)",
                    sentence,
                )
            ):
                raise ValueError("GEO_SYNTHESIS_VISIBILITY_STATUS_CONTRADICTION")
            if facts.target_status != "AVAILABLE" and re.search(
                r"零提及|全文(?:均)?(?:未|没有)(?:明确)?提及", sentence
            ):
                raise ValueError("GEO_SYNTHESIS_UNVERIFIED_MENTION_CONTRADICTION")
            if re.search(r"\d+\s*(?:条|个)?(?:成功|有效)(?:回答|样本)|可见度|提及率", sentence):
                for code in PLATFORM_NAMES:
                    if (
                        not any(item.code == code for item in facts.platforms)
                        and re.search(_platform_pattern(code), sentence, re.IGNORECASE)
                        and not re.search(r"未监测|未覆盖|未取得|没有.{0,8}数据|尚未", sentence)
                    ):
                        raise ValueError("GEO_SYNTHESIS_UNOBSERVED_PLATFORM_CONTRADICTION")
            for match in re.finditer(
                r"(\d+)\s*(?:条|个)?(?:可核验的)?(?:有效回答|成功回答|有效样本|成功样本)", sentence
            ):
                bound = _scope(sentence, facts, match.start())
                if bound is None or _future_claim(sentence, match.start()):
                    continue
                scope, each = bound
                expected = (
                    {item.valid for item in scope} if each else {sum(item.valid for item in scope)}
                )
                if expected != {int(match[1])}:
                    raise ValueError("GEO_SYNTHESIS_VALID_SAMPLE_CONTRADICTION")
            for match in re.finditer(r"(\d+)\s*(?:条|个)?(?:明确)?提及", sentence):
                bound = _scope(sentence, facts, match.start())
                if (
                    bound is None
                    or _future_claim(sentence, match.start())
                    or re.match(r".{0,5}(?:竞品|竞争|其他)", sentence[match.end() :])
                ):
                    continue
                scope, each = bound
                expected = (
                    {item.mentioned for item in scope}
                    if each
                    else {sum(item.mentioned or 0 for item in scope)}
                )
                if any(item.mentioned is None for item in scope) or expected != {int(match[1])}:
                    raise ValueError("GEO_SYNTHESIS_MENTION_COUNT_CONTRADICTION")
            for match in re.finditer(r"(?:可见度|提及率).{0,10}?(\d+(?:\.\d+)?)\s*%", sentence):
                bound = _scope(sentence, facts, match.start())
                if bound is None or _future_claim(sentence, match.start()):
                    continue
                scope, each = bound
                valid = sum(item.valid for item in scope)
                rates = (
                    {item.rate for item in scope}
                    if each
                    else {
                        None
                        if not valid or any(item.mentioned is None for item in scope)
                        else _mention_rate(sum(item.mentioned or 0 for item in scope), valid)
                    }
                )
                if None in rates or any(
                    abs(float(match[1]) - rate) > 0.005 for rate in rates if rate is not None
                ):
                    raise ValueError("GEO_SYNTHESIS_MENTION_RATE_CONTRADICTION")
