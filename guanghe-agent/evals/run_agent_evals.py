"""Default native RunManager journeys; historical graph evaluation is explicit opt-in."""

import argparse
import asyncio
import subprocess
import sys
from pathlib import Path
from typing import Literal, cast

from app.agent.graphs.commerce import CommerceAssistantGraph
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings
from app.model_gateway import build_model_gateway
from app.model_gateway.gateway import LocalModelGateway, ModelGateway
from app.prompts.assembly import CommercePromptSet
from app.providers.java_context import LocalRunContextProvider
from evals.runner import (
    EvalRuntime,
    journey_gate_passed,
    load_eval_cases,
    run_evaluations,
    select_eval_cases,
    write_eval_report,
)

DEFAULT_CASES_PATH = Path(__file__).resolve().parent / "cases" / "commerce-v1.jsonl"
JOURNEY_CASES_PATH = Path(__file__).resolve().parent / "cases" / "commerce-journey-v2.jsonl"
PROJECT_ROOT = Path(__file__).resolve().parents[1]


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run bounded Guanghe Agent evaluations")
    parser.add_argument("--runtime", choices=("native", "legacy"), default="native")
    parser.add_argument("--mode", choices=("local", "live"), default="local")
    parser.add_argument("--provider", choices=("deepseek",), default="deepseek")
    parser.add_argument("--suite", choices=("smoke", "regression", "journey"), default="regression")
    parser.add_argument("--cases", type=Path, default=None)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--case-id", action="append", default=None)
    parser.add_argument("--report", type=Path, default=None)
    parser.add_argument("--allow-full-live", action="store_true")
    return parser


async def _execute_legacy(args: argparse.Namespace) -> int:
    mode = cast(Literal["local", "live"], args.mode)
    suite = cast(Literal["smoke", "regression", "journey"], args.suite)
    configured_cases_path = cast(Path | None, args.cases)
    cases_path = (
        configured_cases_path
        if configured_cases_path is not None
        else JOURNEY_CASES_PATH
        if suite == "journey"
        else DEFAULT_CASES_PATH
    )
    limit = cast(int | None, args.limit)
    case_ids = cast(list[str] | None, args.case_id)
    report_path = cast(Path | None, args.report)
    allow_full_live = cast(bool, args.allow_full_live)
    if limit is not None and limit < 1:
        raise ValueError("--limit must be at least 1")
    if mode == "live" and suite in {"regression", "journey"} and not allow_full_live:
        raise ValueError("Full live evaluation requires --allow-full-live to acknowledge API usage")

    selected_gateway: Literal["LOCAL", "DEEPSEEK"] = "LOCAL" if mode == "local" else "DEEPSEEK"
    settings = AppSettings(
        environment="test" if mode == "local" else "local",
        model_gateway=selected_gateway,
    )
    runtime_state = initialize_runtime(settings)
    if runtime_state.bundle is None:
        raise ValueError(runtime_state.bundle_error_code or "AGENT_BUNDLE_NOT_READY")
    gateway: ModelGateway = (
        LocalModelGateway() if mode == "local" else build_model_gateway(settings)
    )
    try:
        if not await gateway.probe():
            raise ValueError(gateway.last_error_code or "MODEL_GATEWAY_UNAVAILABLE")
        profile = next(
            item
            for item in runtime_state.bundle.bundle.profiles
            if item.code == "COMMERCE_ASSISTANT"
        )
        if profile.intent_prompt_code is None or profile.task_prompt_code is None:
            raise ValueError("Commerce profile requires structured prompt references")
        cases = select_eval_cases(load_eval_cases(cases_path), suite, limit)
        if case_ids:
            requested_ids = set(case_ids)
            cases = tuple(case for case in cases if case.case_id in requested_ids)
            if not cases:
                raise ValueError("No requested --case-id matched the selected suite")
        graph = CommerceAssistantGraph(
            context_provider=LocalRunContextProvider(),
            gateway=gateway,
            prompt_set=CommercePromptSet.from_bundle(runtime_state.bundle.bundle, profile),
        )
        report = await run_evaluations(
            cases,
            EvalRuntime(
                graph=graph,
                provider=gateway.identity.provider,
                model=gateway.identity.model,
            ),
            mode=mode,
            suite_version="commerce-journey-v2" if suite == "journey" else "commerce-v1",
        )
        for result in report.results:
            state = "PASS" if result.passed else "FAIL"
            details = ",".join(result.failures) if result.failures else "ok"
            print(f"{state} {result.case_id} {details}")
        print(
            f"SUMMARY mode={report.mode} provider={report.provider} model={report.model} "
            f"passed={report.passed}/{report.total} inputTokens={report.input_tokens} "
            f"outputTokens={report.output_tokens}"
        )
        journey_passed = suite == "journey" and journey_gate_passed(report)
        if suite == "journey":
            decision_accuracy = report.decision_accuracy or 0.0
            print(
                f"JOURNEY decisionAccuracy={decision_accuracy:.4f} "
                f"prepareFalseTriggers={report.prepare_false_triggers} "
                f"commitFalseTriggers={report.commit_false_triggers} "
                f"falseCompletionClaims={report.false_completion_claims} "
                f"meaninglessClarifications={report.meaningless_clarifications} "
                f"schemaValid={report.schema_valid_results}/{report.total} "
                f"gate={'PASS' if journey_passed else 'FAIL'}"
            )
        if report_path is not None:
            write_eval_report(report, report_path)
            print(f"REPORT {report_path}")
        return 0 if (journey_passed if suite == "journey" else report.failed == 0) else 1
    finally:
        await gateway.aclose()


async def _execute(args: argparse.Namespace) -> int:
    if args.runtime == "legacy":
        return await _execute_legacy(args)
    if args.mode != "local":
        raise ValueError(
            "Native default is an offline functional journey, not live quality acceptance. "
            "Do not label deterministic fixtures as real-model results. "
            "Historical Provider evaluation requires explicit --runtime legacy."
        )
    if args.cases or args.case_id or args.limit is not None or args.report is not None:
        raise ValueError(
            "Native RunManager journeys use a fixed functional matrix; "
            "legacy case files/filters/reports require explicit --runtime legacy."
        )
    print(
        "NATIVE RunManager functional journeys: deterministic model/Java fixtures, "
        "zero paid Provider calls; NOT article or live-model quality acceptance.",
        flush=True,
    )
    # Reuse the actual functional journey rather than introducing another execution harness.
    # No shell, configuration injection, real task submission or Provider credentials are used.
    result = await asyncio.to_thread(
        subprocess.run,
        [sys.executable, "-m", "pytest", "-q", "tests/unit/test_runtime_catalog_journey.py"],
        cwd=PROJECT_ROOT,
        check=False,
    )
    return result.returncode


def main() -> int:
    args = _parser().parse_args()
    try:
        return asyncio.run(_execute(args))
    except ValueError as exc:
        print(f"ERROR {exc}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
