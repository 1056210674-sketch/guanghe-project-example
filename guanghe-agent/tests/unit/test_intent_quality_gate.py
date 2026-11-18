"""Deterministic coverage for the opt-in paid DeepSeek intent-quality gate."""

import argparse
import asyncio

from app.agent.models import Intent
from evals.intent_quality import build_intent_quality_cases
from evals.run_intent_quality_gate import (
    ModeMetrics,
    _post_route,  # pyright: ignore[reportPrivateUsage]
    execute_gate,
    recommend_mode,
)


def test_intent_quality_corpus_covers_required_safe_and_multiturn_cases() -> None:
    cases = build_intent_quality_cases()
    case_ids = {case.case_id for case in cases}
    messages = {case.message for case in cases}

    assert len(cases) == 275
    assert len(case_ids) == len(cases)
    assert {case.expected_intent for case in cases} == set(Intent)
    assert "第二个改成蓝色" in messages
    assert "继续刚才那个" in messages
    assert "确认" in messages
    assert "执行" in messages
    assert any(
        "background" in message.casefold() or "image" in message.casefold() for message in messages
    )
    assert any(case.case_id.startswith("negated-") for case in cases)
    assert any(case.case_id.startswith("vague-") for case in cases)
    assert sum(case.expected_intent is Intent.MEDIA for case in cases) == 8
    multi_turn = tuple(case for case in cases if case.case_id.startswith("multi-"))
    assert len(multi_turn) == 20
    assert all(len(case.history) == 40 for case in multi_turn)
    assert all(case.history[-1].role == "assistant" for case in multi_turn)


def test_copy_boundary_cases_do_not_invent_active_drafts_or_override_general() -> None:
    cases = build_intent_quality_cases()
    boundaries = [case for case in cases if case.case_id.startswith("chat-copy-boundary-")]
    assert len(boundaries) == 6
    for case in boundaries:
        assert case.expected_intent is Intent.GENERAL
        assert not case.expects_prepare
        assert '"activeDraftId":null' in case.model_messages("test")[0].content
        assert _post_route(case, Intent.GENERAL) is Intent.GENERAL
    active_case = next(case for case in cases if case.has_active_draft)
    assert _post_route(active_case, Intent.GENERAL) is Intent.GENERAL


def test_intent_quality_gate_is_inert_without_explicit_execute() -> None:
    args = argparse.Namespace(execute=False, concurrency=1, modes="both", report=None)
    assert asyncio.run(execute_gate(args)) == 2


def test_translation_target_cases_are_general_with_ordered_original_user_targets() -> None:
    cases = [
        case
        for case in build_intent_quality_cases()
        if case.case_id.startswith("translation-target-")
    ]
    assert len(cases) == 3
    assert [len(case.history) for case in cases] == [0, 2, 4]
    assert cases[1].message == "英文就行" and cases[2].message == "再翻回中文，只给译文"
    for index, case in enumerate(cases):
        assert case.expected_intent is Intent.GENERAL and not case.expects_prepare
        assert not case.has_active_draft
        assert _post_route(case, Intent.GENERAL) is Intent.GENERAL
        assert case.model_messages("test")[-1].content == case.message
        for prior_index in range(index):
            assert case.history[prior_index * 2].role == "user"
            assert case.history[prior_index * 2].content == cases[prior_index].message


def test_tool_call_mode_is_recommended_only_for_material_safe_improvement() -> None:
    def metrics(
        mode: str,
        *,
        final_correct: int,
        final_accuracy: float,
        prepare_false_triggers: int,
        prepare_false_rate: float,
    ) -> ModeMetrics:
        return ModeMetrics.model_validate(
            {
                "mode": mode,
                "total": 258,
                "modelEvaluated": 250,
                "firstStructuredSuccesses": 250,
                "firstStructuredSuccessRate": 1.0,
                "retryRecoveries": 0,
                "retryRecoveryRate": 0.0,
                "safeFallbacks": 0,
                "safeFallbackRate": 0.0,
                "finalCorrect": final_correct,
                "finalIntentAccuracy": final_accuracy,
                "taskCases": 180,
                "taskCorrect": 180,
                "taskTypeAccuracy": 1.0,
                "nonTaskCases": 78,
                "prepareFalseTriggers": prepare_false_triggers,
                "prepareFalseTriggerRate": prepare_false_rate,
                "commitFalseTriggers": 0,
                "commitFalseTriggerRate": 0.0,
                "modelCalls": 250,
                "averageModelCalls": 0.969,
                "inputTokens": 1,
                "outputTokens": 1,
                "providerLatencyMs": 1,
                "averageProviderLatencyMs": 0.004,
                "mismatchedCaseIds": [],
            }
        )

    json_metrics = metrics(
        "JSON_OBJECT",
        final_correct=227,
        final_accuracy=0.8798,
        prepare_false_triggers=24,
        prepare_false_rate=0.3077,
    )
    tool_metrics = metrics(
        "TOOL_CALL",
        final_correct=249,
        final_accuracy=0.9651,
        prepare_false_triggers=0,
        prepare_false_rate=0.0,
    )

    assert recommend_mode((json_metrics, tool_metrics)) == "TOOL_CALL"
    unsafe_tool = tool_metrics.model_copy(update={"prepare_false_trigger_rate": 0.4})
    assert recommend_mode((json_metrics, unsafe_tool)) == "JSON_OBJECT"
