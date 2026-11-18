"""Explicit, paid DeepSeek intent-quality comparison; inert unless --execute is set."""

import argparse
import asyncio
import hashlib
import json
from collections import defaultdict
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Literal, cast

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.agent.models import Intent
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings
from app.model_gateway.deepseek import CompletionDiagnostic, DeepSeekAdapter
from app.model_gateway.gateway import (
    ModelGatewayError,
    StructuredOutputMode,
    deterministic_intent,
    intent_is_explicitly_negated,
)
from app.observability.trace import reset_current_trace_id, set_current_trace_id
from app.prompts.assembly import CommercePromptSet
from evals.intent_quality import IntentQualityCase, build_intent_quality_cases

_TASK_INTENTS = frozenset((Intent.IMAGE, Intent.VIDEO, Intent.GEO, Intent.ARTICLE))
_BARE_CONFIRMATIONS = frozenset(
    ("确认", "确定", "开始", "执行", "确认一下", "开始吧", "confirm", "go")
)


class _ReportModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class ModeMetrics(_ReportModel):
    mode: StructuredOutputMode
    total: int
    model_evaluated: int
    first_structured_successes: int
    first_structured_success_rate: float
    retry_recoveries: int
    retry_recovery_rate: float
    safe_fallbacks: int
    safe_fallback_rate: float
    final_correct: int
    final_intent_accuracy: float
    task_cases: int
    task_correct: int
    task_type_accuracy: float
    non_task_cases: int
    prepare_false_triggers: int
    prepare_false_trigger_rate: float
    commit_false_triggers: int = 0
    commit_false_trigger_rate: float = 0.0
    model_calls: int
    average_model_calls: float
    input_tokens: int
    output_tokens: int
    provider_latency_ms: int
    average_provider_latency_ms: float
    mismatched_case_ids: tuple[str, ...]


class IntentQualityReport(_ReportModel):
    suite_version: Literal["intent-quality-v1"] = "intent-quality-v1"
    provider: Literal["DEEPSEEK"] = "DEEPSEEK"
    model: str
    started_at: datetime
    completed_at: datetime
    synthetic_case_count: int = Field(ge=200)
    metrics: tuple[ModeMetrics, ...]
    recommended_mode: StructuredOutputMode
    quality_status: Literal["PASS", "DEGRADED"]


@dataclass(frozen=True, slots=True)
class _CaseResult:
    case_id: str
    expected: Intent
    predicted: Intent
    model_evaluated: bool
    first_succeeded: bool
    recovered: bool
    fallback: bool
    model_calls: int
    input_tokens: int
    output_tokens: int
    latency_ms: int


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run paid DeepSeek intent quality comparison")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument(
        "--modes",
        choices=("both", "json", "tool"),
        default="both",
    )
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--report", type=Path, default=None)
    return parser


async def execute_gate(args: argparse.Namespace) -> int:
    if not cast(bool, args.execute):
        print("ERROR INTENT_QUALITY_GATE_REQUIRES_EXPLICIT_EXECUTE")
        return 2
    concurrency = cast(int, args.concurrency)
    if not 1 <= concurrency <= 8:
        print("ERROR INTENT_QUALITY_GATE_CONCURRENCY_INVALID")
        return 2

    settings = AppSettings(environment="local", model_gateway="DEEPSEEK")
    runtime = initialize_runtime(settings)
    if runtime.bundle is None:
        print(f"ERROR {runtime.bundle_error_code or 'AGENT_BUNDLE_NOT_READY'}")
        return 2
    profile = runtime.bundle.bundle.profiles[0]
    if profile.intent_prompt_code is None:
        print("ERROR AGENT_INTENT_PROMPT_NOT_READY")
        return 2
    intent_prompt = CommercePromptSet.from_bundle(
        runtime.bundle.bundle,
        profile,
    ).for_intent_classifier()
    cases = build_intent_quality_cases()
    modes = _selected_modes(cast(str, args.modes))
    diagnostics: dict[str, list[CompletionDiagnostic]] = defaultdict(list)

    def observe(diagnostic: CompletionDiagnostic) -> None:
        if diagnostic.trace_id is not None:
            diagnostics[diagnostic.trace_id].append(diagnostic)

    adapter = DeepSeekAdapter(settings.deepseek, diagnostic_observer=observe)
    started_at = datetime.now(UTC)
    try:
        if not await adapter.probe():
            print(f"ERROR {adapter.last_error_code or 'DEEPSEEK_UNAVAILABLE'}")
            return 2
        metrics: list[ModeMetrics] = []
        semaphore = asyncio.Semaphore(concurrency)
        for mode in modes:
            results = await asyncio.gather(
                *(
                    _evaluate_case(
                        adapter,
                        case,
                        mode=mode,
                        intent_prompt=intent_prompt,
                        diagnostics=diagnostics,
                        semaphore=semaphore,
                    )
                    for case in cases
                )
            )
            metrics.append(_summarize(mode, tuple(results)))
    except ModelGatewayError as exc:
        print(f"ERROR {exc.code}")
        return 2
    finally:
        await adapter.aclose()

    recommended = recommend_mode(tuple(metrics))
    selected = next(item for item in metrics if item.mode == recommended)
    quality_status: Literal["PASS", "DEGRADED"] = (
        "PASS"
        if selected.final_intent_accuracy >= 0.95 and selected.commit_false_trigger_rate == 0
        else "DEGRADED"
    )
    report = IntentQualityReport(
        model=adapter.identity.model,
        started_at=started_at,
        completed_at=datetime.now(UTC),
        synthetic_case_count=len(cases),
        metrics=tuple(metrics),
        recommended_mode=recommended,
        quality_status=quality_status,
    )
    report_path = cast(Path | None, args.report)
    if report_path is not None:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(
            json.dumps(report.model_dump(mode="json", by_alias=True), ensure_ascii=False, indent=2)
            + "\n",
            encoding="utf-8",
        )
    for item in report.metrics:
        print(
            "MODE "
            f"{item.mode} first={item.first_structured_success_rate:.4f} "
            f"retryRecovery={item.retry_recovery_rate:.4f} "
            f"fallback={item.safe_fallback_rate:.4f} "
            f"final={item.final_intent_accuracy:.4f} "
            f"task={item.task_type_accuracy:.4f} "
            f"prepareFalse={item.prepare_false_trigger_rate:.4f} "
            f"commitFalse={item.commit_false_trigger_rate:.4f} "
            f"calls={item.model_calls} inputTokens={item.input_tokens} "
            f"outputTokens={item.output_tokens} latencyMs={item.provider_latency_ms}"
        )
    print(
        f"SUMMARY cases={report.synthetic_case_count} recommended={recommended} "
        f"quality={quality_status} model={report.model}"
    )
    return 0 if quality_status == "PASS" else 1


async def _evaluate_case(
    adapter: DeepSeekAdapter,
    case: IntentQualityCase,
    *,
    mode: StructuredOutputMode,
    intent_prompt: str,
    diagnostics: dict[str, list[CompletionDiagnostic]],
    semaphore: asyncio.Semaphore,
) -> _CaseResult:
    if case.message.casefold().strip() in _BARE_CONFIRMATIONS:
        return _CaseResult(
            case_id=case.case_id,
            expected=case.expected_intent,
            predicted=Intent.AMBIGUOUS_CONFIRMATION,
            model_evaluated=False,
            first_succeeded=True,
            recovered=False,
            fallback=False,
            model_calls=0,
            input_tokens=0,
            output_tokens=0,
            latency_ms=0,
        )
    trace_id = hashlib.sha256(f"{mode}:{case.case_id}".encode()).hexdigest()[:32]
    token = set_current_trace_id(trace_id)
    try:
        async with semaphore:
            try:
                result = await adapter.classify_intent_with_mode(
                    case.model_messages(intent_prompt),
                    mode,
                )
            except ModelGatewayError as exc:
                if not exc.structured_response_failure:
                    raise
                predicted = _fallback(case)
                first_succeeded = False
                recovered = False
                fallback = True
                input_tokens = exc.usage.input_tokens
                output_tokens = exc.usage.output_tokens
            else:
                predicted = _post_route(case, result.intent)
                diagnosis = result.diagnostics
                first_succeeded = bool(diagnosis and diagnosis.first_attempt_succeeded)
                recovered = bool(diagnosis and diagnosis.recovered_by_retry)
                fallback = False
                input_tokens = result.usage.input_tokens
                output_tokens = result.usage.output_tokens
        observed = diagnostics.pop(trace_id, [])
        return _CaseResult(
            case_id=case.case_id,
            expected=case.expected_intent,
            predicted=predicted,
            model_evaluated=True,
            first_succeeded=first_succeeded,
            recovered=recovered,
            fallback=fallback,
            model_calls=len(observed),
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            latency_ms=sum(item.latency_ms for item in observed),
        )
    finally:
        reset_current_trace_id(token)


def _post_route(case: IntentQualityCase, classified: Intent) -> Intent:
    if classified in _TASK_INTENTS and intent_is_explicitly_negated(case.message, classified):
        return Intent.GENERAL
    # A semantic GENERAL answer must not be scored as task continuation merely
    # because an old workspace exists; production only continues explicit PREPARE.
    return classified


def _fallback(case: IntentQualityCase) -> Intent:
    explicit = deterministic_intent(case.message)
    if explicit in _TASK_INTENTS and not intent_is_explicitly_negated(case.message, explicit):
        return explicit
    contextual = _post_route(case, Intent.GENERAL)
    return contextual if contextual in _TASK_INTENTS else Intent.GENERAL


def _summarize(mode: StructuredOutputMode, results: tuple[_CaseResult, ...]) -> ModeMetrics:
    model_results = tuple(result for result in results if result.model_evaluated)
    first_successes = sum(result.first_succeeded for result in model_results)
    recoveries = sum(result.recovered for result in model_results)
    first_failures = len(model_results) - first_successes
    fallbacks = sum(result.fallback for result in model_results)
    correct = sum(result.predicted is result.expected for result in results)
    task_results = tuple(result for result in results if result.expected in _TASK_INTENTS)
    non_task_results = tuple(result for result in results if result.expected not in _TASK_INTENTS)
    task_correct = sum(result.predicted is result.expected for result in task_results)
    prepare_false = sum(result.predicted in _TASK_INTENTS for result in non_task_results)
    model_calls = sum(result.model_calls for result in results)
    latency = sum(result.latency_ms for result in results)
    return ModeMetrics(
        mode=mode,
        total=len(results),
        model_evaluated=len(model_results),
        first_structured_successes=first_successes,
        first_structured_success_rate=_rate(first_successes, len(model_results)),
        retry_recoveries=recoveries,
        retry_recovery_rate=_rate(recoveries, first_failures),
        safe_fallbacks=fallbacks,
        safe_fallback_rate=_rate(fallbacks, len(model_results)),
        final_correct=correct,
        final_intent_accuracy=_rate(correct, len(results)),
        task_cases=len(task_results),
        task_correct=task_correct,
        task_type_accuracy=_rate(task_correct, len(task_results)),
        non_task_cases=len(non_task_results),
        prepare_false_triggers=prepare_false,
        prepare_false_trigger_rate=_rate(prepare_false, len(non_task_results)),
        model_calls=model_calls,
        average_model_calls=_average(model_calls, len(results)),
        input_tokens=sum(result.input_tokens for result in results),
        output_tokens=sum(result.output_tokens for result in results),
        provider_latency_ms=latency,
        average_provider_latency_ms=_average(latency, max(1, model_calls)),
        mismatched_case_ids=tuple(
            result.case_id for result in results if result.predicted is not result.expected
        ),
    )


def recommend_mode(metrics: tuple[ModeMetrics, ...]) -> StructuredOutputMode:
    if len(metrics) == 1:
        return metrics[0].mode
    json_metrics = next(item for item in metrics if item.mode == "JSON_OBJECT")
    tool_metrics = next(item for item in metrics if item.mode == "TOOL_CALL")
    tool_is_clearly_better = (
        tool_metrics.final_intent_accuracy >= json_metrics.final_intent_accuracy + 0.05
        and tool_metrics.first_structured_success_rate >= json_metrics.first_structured_success_rate
        and tool_metrics.safe_fallback_rate <= json_metrics.safe_fallback_rate
        and tool_metrics.prepare_false_trigger_rate <= json_metrics.prepare_false_trigger_rate
        and tool_metrics.commit_false_triggers == 0
    )
    return "TOOL_CALL" if tool_is_clearly_better else "JSON_OBJECT"


def _selected_modes(value: str) -> tuple[StructuredOutputMode, ...]:
    if value == "json":
        return ("JSON_OBJECT",)
    if value == "tool":
        return ("TOOL_CALL",)
    return ("JSON_OBJECT", "TOOL_CALL")


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def _average(total: int, denominator: int) -> float:
    return round(total / denominator, 3) if denominator else 0.0


def main() -> int:
    return asyncio.run(execute_gate(_parser().parse_args()))


if __name__ == "__main__":
    raise SystemExit(main())
