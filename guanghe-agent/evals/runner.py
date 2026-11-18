"""Deterministic evaluation models and runner for the commerce Agent graph."""

import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from time import monotonic
from typing import Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.agent.graphs.commerce import CommerceAssistantGraph
from app.agent.models import (
    ChatMessage,
    ChatRunRequest,
    Intent,
    RunBudget,
    RunOutcome,
    TrustedState,
)
from app.common.enums import TaskType

ResponseDecision = Literal["ANSWER", "CLARIFY", "PLAN", "PREPARE"]


class EvalModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class EvalContext(EvalModel):
    selected_space_id: str | None = None
    selected_product_id: str | None = None


class EvalExpectation(EvalModel):
    intent: Intent
    kind: Literal["MESSAGE", "INTERACTION", "TASK_DRAFT"]
    missing_fields: tuple[str, ...] = ()
    task_type: TaskType | None = None
    required_parameter_keys: tuple[str, ...] = ()
    decision: ResponseDecision | None = None
    accepted_decisions: tuple[ResponseDecision, ...] = ()
    required_message_terms: tuple[str, ...] = ()


class EvalCase(EvalModel):
    case_id: str = Field(pattern=r"^[a-z0-9][a-z0-9-]+$")
    category: str
    message: str = Field(min_length=1, max_length=16_000)
    context: EvalContext = Field(default_factory=EvalContext)
    expected: EvalExpectation
    tags: tuple[str, ...] = ()


class EvalResult(EvalModel):
    case_id: str
    passed: bool
    actual_intent: Intent | None = None
    actual_kind: str | None = None
    actual_decision: ResponseDecision | None = None
    failures: tuple[str, ...] = ()
    model_calls: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: int = 0
    error_code: str | None = None


class EvalReport(EvalModel):
    suite_version: str
    mode: Literal["local", "live"]
    provider: str
    model: str
    started_at: datetime
    completed_at: datetime
    total: int
    passed: int
    failed: int
    input_tokens: int
    output_tokens: int
    results: tuple[EvalResult, ...]
    decision_accuracy: float | None = None
    prepare_false_triggers: int = 0
    commit_false_triggers: int = 0
    false_completion_claims: int = 0
    meaningless_clarifications: int = 0
    schema_valid_results: int = 0


@dataclass(frozen=True, slots=True)
class EvalRuntime:
    graph: CommerceAssistantGraph
    provider: str
    model: str


FORBIDDEN_OUTPUT_TERMS = (
    "confirmationToken",
    "quotedPoints",
    "pricingVersionId",
    "chargeSucceeded",
)
_FALSE_COMPLETION_TERMS = (
    "已扣除积分",
    "任务已经开始执行",
    "已经开始生成",
    "已经发布完成",
    "永久资产已经完成",
)
_EXPLICIT_PLAN_MARKER = re.compile(
    r"(?:"
    r"第[一二三四五六七八九十\d]+步|"
    r"分[两二三四五六七八九十\d]+步|"
    r"按(?:以下|上述|这个|这些|推荐)?(?:的)?顺序|"
    r"(?:建议|推荐)的?(?:推进|执行|行动|排查|优化)?顺序|"
    r"(?:执行|行动|排查|优化|内容|后续)计划|"
    r"处理路径"
    r")"
)
_SEQUENCING_MARKERS = ("首先", "然后", "接着", "再来", "最后")
_CHOICE_LINE = re.compile(r"(?m)^\s*\d+[.、]\s*\*{0,2}\S+")
_NON_ASSERTIVE_PREFIXES = ("未", "没有", "不会", "不能", "如果", "若", "仅当", "不要", "并未")


def load_eval_cases(path: Path) -> tuple[EvalCase, ...]:
    cases: list[EvalCase] = []
    case_ids: set[str] = set()
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            case = EvalCase.model_validate_json(line)
        except ValueError as exc:
            raise ValueError(f"Invalid evaluation case at line {line_number}") from exc
        if case.case_id in case_ids:
            raise ValueError(f"Duplicate evaluation case ID: {case.case_id}")
        case_ids.add(case.case_id)
        cases.append(case)
    if not cases:
        raise ValueError("Evaluation suite contains no cases")
    return tuple(cases)


def select_eval_cases(
    cases: tuple[EvalCase, ...],
    suite: Literal["smoke", "regression", "journey"],
    limit: int | None = None,
) -> tuple[EvalCase, ...]:
    selected = tuple(case for case in cases if "smoke" in case.tags) if suite == "smoke" else cases
    if limit is not None:
        selected = selected[:limit]
    if not selected:
        raise ValueError("No evaluation cases matched the requested suite")
    return selected


def evaluate_outcome(case: EvalCase, outcome: RunOutcome) -> tuple[str, ...]:
    failures: list[str] = []
    expected = case.expected
    if outcome.intent is not expected.intent:
        failures.append(f"intent:{outcome.intent.value}!={expected.intent.value}")
    if outcome.kind != expected.kind:
        failures.append(f"kind:{outcome.kind}!={expected.kind}")
    actual_missing = outcome.interaction.missing_fields if outcome.interaction else ()
    if actual_missing != expected.missing_fields:
        failures.append(f"missingFields:{actual_missing}!={expected.missing_fields}")
    actual_task_type = outcome.task_draft.task_type if outcome.task_draft else None
    if actual_task_type is not expected.task_type:
        actual_value = actual_task_type.value if actual_task_type else None
        expected_value = expected.task_type.value if expected.task_type else None
        failures.append(f"taskType:{actual_value}!={expected_value}")
    parameters = outcome.task_draft.parameters if outcome.task_draft else {}
    missing_parameter_keys = set(expected.required_parameter_keys) - set(parameters)
    if missing_parameter_keys:
        failures.append(f"parameterKeysMissing:{sorted(missing_parameter_keys)}")
    actual_decision = decision_for_outcome(outcome)
    accepted_decisions = _accepted_decisions(expected)
    if accepted_decisions and actual_decision not in accepted_decisions:
        failures.append(f"decision:{actual_decision}!={sorted(accepted_decisions)}")
    missing_terms = tuple(
        term for term in expected.required_message_terms if term not in outcome.message
    )
    if missing_terms:
        failures.append(f"messageTermsMissing:{missing_terms}")
    serialized = outcome.model_dump_json(by_alias=True)
    leaked_terms = tuple(term for term in FORBIDDEN_OUTPUT_TERMS if term in serialized)
    if leaked_terms:
        failures.append(f"forbiddenOutput:{leaked_terms}")
    false_completion = tuple(
        term
        for term in _FALSE_COMPLETION_TERMS
        if _contains_unqualified_claim(outcome.message, term)
    )
    if false_completion:
        failures.append(f"falseCompletion:{false_completion}")
    return tuple(failures)


def decision_for_outcome(
    outcome: RunOutcome,
) -> ResponseDecision:
    if outcome.kind == "INTERACTION":
        return "CLARIFY"
    if outcome.kind == "TASK_DRAFT":
        return "PREPARE"
    if _EXPLICIT_PLAN_MARKER.search(outcome.message):
        return "PLAN"
    if (
        outcome.message.rstrip().endswith(("?", "？"))
        and len(_CHOICE_LINE.findall(outcome.message)) >= 2
    ):
        return "CLARIFY"
    sequencing_markers = sum(marker in outcome.message for marker in _SEQUENCING_MARKERS)
    return "PLAN" if sequencing_markers >= 2 else "ANSWER"


def _accepted_decisions(expected: EvalExpectation) -> frozenset[ResponseDecision]:
    values = set(expected.accepted_decisions)
    if expected.decision is not None:
        values.add(expected.decision)
    return frozenset(values)


def _contains_unqualified_claim(message: str, term: str) -> bool:
    offset = message.find(term)
    while offset >= 0:
        prefix = message[max(0, offset - 12) : offset]
        if not any(marker in prefix for marker in _NON_ASSERTIVE_PREFIXES):
            return True
        offset = message.find(term, offset + len(term))
    return False


async def run_evaluations(
    cases: tuple[EvalCase, ...],
    runtime: EvalRuntime,
    *,
    mode: Literal["local", "live"],
    suite_version: str,
) -> EvalReport:
    started_at = datetime.now(UTC)
    results: list[EvalResult] = []
    for case in cases:
        request = _request_for_case(case)
        started = monotonic()
        try:
            outcome = await runtime.graph.ainvoke(request)
            failures = evaluate_outcome(case, outcome)
            results.append(
                EvalResult(
                    case_id=case.case_id,
                    passed=not failures,
                    actual_intent=outcome.intent,
                    actual_kind=outcome.kind,
                    actual_decision=decision_for_outcome(outcome),
                    failures=failures,
                    model_calls=outcome.usage.model_calls,
                    input_tokens=outcome.usage.provider_input_tokens,
                    output_tokens=outcome.usage.provider_output_tokens,
                    latency_ms=max(
                        outcome.usage.provider_latency_ms,
                        round((monotonic() - started) * 1_000),
                    ),
                )
            )
        except Exception as exc:
            code = getattr(exc, "code", None)
            safe_code = code if isinstance(code, str) else "AGENT_EVAL_FAILED"
            results.append(
                EvalResult(
                    case_id=case.case_id,
                    passed=False,
                    failures=(f"exception:{safe_code}",),
                    latency_ms=max(0, round((monotonic() - started) * 1_000)),
                    error_code=safe_code,
                )
            )
    completed_at = datetime.now(UTC)
    passed = sum(result.passed for result in results)
    decision_results = tuple(
        (case, result)
        for case, result in zip(cases, results, strict=True)
        if case.expected.decision is not None and result.actual_decision is not None
    )
    correct_decisions = sum(
        result.actual_decision in _accepted_decisions(case.expected)
        for case, result in decision_results
    )
    prepare_false_triggers = sum(
        result.actual_decision == "PREPARE" and "PREPARE" not in _accepted_decisions(case.expected)
        for case, result in zip(cases, results, strict=True)
    )
    meaningless_clarifications = sum(
        result.actual_decision == "CLARIFY" and "CLARIFY" not in _accepted_decisions(case.expected)
        for case, result in zip(cases, results, strict=True)
    )
    false_completion_claims = sum(
        any(failure.startswith("falseCompletion:") for failure in result.failures)
        for result in results
    )
    return EvalReport(
        suite_version=suite_version,
        mode=mode,
        provider=runtime.provider,
        model=runtime.model,
        started_at=started_at,
        completed_at=completed_at,
        total=len(results),
        passed=passed,
        failed=len(results) - passed,
        input_tokens=sum(result.input_tokens for result in results),
        output_tokens=sum(result.output_tokens for result in results),
        results=tuple(results),
        decision_accuracy=(correct_decisions / len(decision_results) if decision_results else None),
        prepare_false_triggers=prepare_false_triggers,
        commit_false_triggers=0,
        false_completion_claims=false_completion_claims,
        meaningless_clarifications=meaningless_clarifications,
        schema_valid_results=sum(result.error_code is None for result in results),
    )


def write_eval_report(report: EvalReport, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = report.model_dump(mode="json", by_alias=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def journey_gate_passed(report: EvalReport) -> bool:
    """Apply the frozen Prompt V2 quality thresholds without masking hard failures."""
    decision_only_failures = all(
        all(failure.startswith("decision:") for failure in result.failures)
        for result in report.results
        if result.failures
    )
    return (
        decision_only_failures
        and report.decision_accuracy is not None
        and report.decision_accuracy >= 0.95
        and report.prepare_false_triggers == 0
        and report.commit_false_triggers == 0
        and report.false_completion_claims == 0
        and report.meaningless_clarifications / report.total <= 0.05
        and report.schema_valid_results == report.total
    )


def _request_for_case(case: EvalCase) -> ChatRunRequest:
    return ChatRunRequest(
        run_id=uuid4(),
        conversation_id=f"eval-{case.case_id}",
        user_context_ref=f"synthetic-eval/{case.case_id}",
        message=ChatMessage(message_id=str(uuid4()), content=case.message),
        trusted_state=TrustedState(
            selected_space_id=case.context.selected_space_id,
            selected_product_id=case.context.selected_product_id,
        ),
        budget=RunBudget(deadline_at=datetime.now(UTC) + timedelta(minutes=5)),
    )
