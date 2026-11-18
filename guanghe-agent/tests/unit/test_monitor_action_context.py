"""Current Java-selected monitor range, without model authorization or paid work."""

import asyncio
import copy
from dataclasses import replace
from typing import Any
from unittest.mock import AsyncMock, PropertyMock, patch

import pytest
from pydantic import ValidationError

from app.agent.graphs.commerce import CommerceAssistantGraph
from app.agent.graphs.domains import (
    DomainGraphError,
    _MonitorTaskDraftInput,  # pyright: ignore[reportPrivateUsage] - test runtime-only boundary
    prepare_task_draft,
    prepare_task_draft_input,
)
from app.agent.identity import AgentIdentityError, bind_agent_execution, resolve_execution
from app.agent.loop import NativeAgentLoop
from app.agent.models import ChatRunRequest, DraftOperation, Intent
from app.agent.run_manager import ChatRunManager
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings
from app.harness.runtime import HarnessViolation, RuntimeHarness
from app.model_gateway.gateway import LocalModelGateway
from app.providers.java_context import (
    JavaRunContextResponse,
    RunContextUnavailableError,
    authorized_run_context_from_snapshot,
)
from app.tools.registry import (
    DEFAULT_TOOL_REGISTRY,
    TaskDraftPrepareInput,
    ToolExecutionError,
    ToolObservation,
)
from tests.unit.test_chat_run_isolation import (
    _collect,  # pyright: ignore[reportPrivateUsage] - bounded existing SSE fixture
)
from tests.unit.test_java_run_context_provider import (
    _payload,  # pyright: ignore[reportPrivateUsage] - reuse the existing context fixture
    _provider_for_payload,  # pyright: ignore[reportPrivateUsage]
    _request,  # pyright: ignore[reportPrivateUsage]
)


def _monitor_payload(request: ChatRunRequest) -> dict[str, Any]:
    payload: dict[str, Any] = _payload(request)
    payload["agentIdentity"] = {
        "installationId": "501",
        "agentKey": "geo",
        "agentVersion": "1.0.0",
        "name": "GEO",
        "projectId": request.trusted_state.selected_space_id,
        "capabilities": ["chat", "resources.read", "geo.read", "geo.monitor"],
    }
    payload["monitorActionContext"] = {
        "action": "MONITOR_RUN",
        "spaceId": request.trusted_state.selected_space_id,
        "productId": request.trusted_state.selected_product_id,
        "parameters": {
            "mode": "MONITOR_RUN",
            "topicId": "601",
            "questionIds": ["701", "702", "703"],
            "platformCodes": ["DOUBAO", "QWEN"],
            "sampleMode": "STANDARD",
            "region": "上海",
        },
        "questions": [
            {"id": str(701 + i), "category": "BRAND_DISCOVERY", "text": f"合成测试问题{i + 1}？"}
            for i in range(3)
        ],
    }
    return payload


@pytest.mark.parametrize("product", [True, False])
def test_java_monitor_context_preserves_exact_order_and_scope(product: bool) -> None:
    request = _request(
        content="本轮选择的监测", product_id=None if not product else "1900012345678901200"
    )
    payload = _monitor_payload(request)
    context = asyncio.run(_provider_for_payload(payload).load(request))
    monitor = context.monitor_action_context
    assert monitor is not None
    assert (
        monitor.parameters.model_dump(mode="json", by_alias=True)
        == payload["monitorActionContext"]["parameters"]
    )
    assert tuple(q.id for q in monitor.questions) == monitor.parameters.question_ids
    assert monitor.space_id == context.selected_space_id
    assert monitor.product_id == context.selected_product_id
    # Neither the choice nor its semantic authority is a model-visible parameter.
    assert "monitorActionContext" not in context.model_context_json()
    assert "合成测试问题" not in context.model_context_json()


@pytest.mark.parametrize(
    "mutation",
    [
        "grant",
        "extra_parameter",
        "mode",
        "question_mismatch",
        "duplicate_question",
        "unknown_platform",
        "duplicate_platform",
        "region_blank",
        "question_blank",
        "question_too_long",
        "question_category",
        "numeric_id",
        "null_parameters",
        "space",
        "product",
        "project",
        "summary_identity",
        "missing_identity",
        "missing_installation",
        "missing_capability",
        "attachments",
    ],
)
def test_monitor_context_rejects_malformed_or_cross_scope_selection(mutation: str) -> None:
    request = _request()
    payload = _monitor_payload(request)
    monitor = payload["monitorActionContext"]
    parameters = monitor["parameters"]
    if mutation == "grant":
        monitor["grantId"] = "99"
    elif mutation == "extra_parameter":
        parameters["quotedPoints"] = 1
    elif mutation == "mode":
        parameters["mode"] = "QUESTION_DISCOVERY"
    elif mutation == "question_mismatch":
        monitor["questions"].reverse()
    elif mutation == "duplicate_question":
        parameters["questionIds"][1] = parameters["questionIds"][0]
        monitor["questions"][1] = monitor["questions"][0]
    elif mutation == "unknown_platform":
        parameters["platformCodes"] = ["UNKNOWN"]
    elif mutation == "duplicate_platform":
        parameters["platformCodes"] = ["DOUBAO", "DOUBAO"]
    elif mutation == "region_blank":
        parameters["region"] = " "
    elif mutation == "question_blank":
        monitor["questions"][0]["text"] = " "
    elif mutation == "question_too_long":
        monitor["questions"][0]["text"] = "x" * 1001
    elif mutation == "question_category":
        monitor["questions"][0]["category"] = "DIRECT_BRAND_PROMPT"
    elif mutation == "numeric_id":
        parameters["topicId"] = 601
    elif mutation == "null_parameters":
        monitor["parameters"] = None
    elif mutation in {"space", "product"}:
        monitor[mutation + "Id"] = "99"
    elif mutation == "project":
        payload["agentIdentity"]["projectId"] = "99"
    elif mutation == "summary_identity":
        payload["agentIdentity"]["agentKey"] = "summary"
    elif mutation == "missing_identity":
        del payload["agentIdentity"]
    elif mutation == "missing_installation":
        payload["agentIdentity"]["installationId"] = None
    elif mutation == "missing_capability":
        payload["agentIdentity"]["capabilities"] = ["chat"]
    else:
        payload["attachmentAssetIds"] = ["801"]
        payload["taskWorkspace"]["referenceAssetIds"] = ["801"]
    with pytest.raises(RunContextUnavailableError) as caught:
        asyncio.run(_provider_for_payload(payload).load(request))
    assert caught.value.code == "JAVA_RUN_CONTEXT_INVALID_RESPONSE"


@pytest.mark.parametrize("mutation", ["run", "conversation", "message", "role", "sequence", "text"])
def test_monitor_choice_still_requires_current_original_user_request(mutation: str) -> None:
    request = _request()
    payload = _monitor_payload(request)
    memory = payload["conversationMemory"]
    current = memory["messages"][0]
    if mutation == "run":
        payload["runId"] = "99999999-9999-4999-8999-999999999999"
    elif mutation == "conversation":
        payload["conversationId"] = "99"
    elif mutation == "message":
        current["messageId"] = "99"
    elif mutation == "role":
        current["role"] = "ASSISTANT"
    elif mutation == "sequence":
        memory["lastMessageSequence"] = 2
        memory["messages"].append({**copy.deepcopy(current), "messageId": "99", "sequence": 2})
    else:
        current["content"]["text"] = "不同请求"
    with pytest.raises(RunContextUnavailableError) as caught:
        asyncio.run(_provider_for_payload(payload).load(request))
    assert caught.value.code == "JAVA_RUN_CONTEXT_AUTHORIZATION_MISMATCH"


def test_null_or_missing_choice_and_history_never_restore_monitor_authority() -> None:
    request = _request()
    payload = _monitor_payload(request)
    original = payload.pop("monitorActionContext")
    payload["conversationMemory"]["messages"][0]["content"]["monitorActionContext"] = original
    variants: tuple[dict[str, Any], ...] = (payload, {**payload, "monitorActionContext": None})
    for current in variants:
        context = asyncio.run(_provider_for_payload(current).load(request))
        assert context.monitor_action_context is None
    payload["monitorActionContext"] = original
    snapshot = JavaRunContextResponse.model_validate(payload)
    assert authorized_run_context_from_snapshot(snapshot).monitor_action_context is None
    assert (
        asyncio.run(_provider_for_payload(payload).load(request))
        .current_attachment_only()
        .monitor_action_context
        is None
    )


@pytest.mark.parametrize(
    "text", ["本轮监测", "怎么开始", "看上次报告", "确认", "监测之后写文章并投稿"]
)
def test_explicit_java_choice_bypasses_models_pending_and_workflow_expansion(text: str) -> None:
    request = _request(content=text, pending_interaction_id="801", draft_id="802")
    payload = _monitor_payload(request)
    observations: list[ToolObservation] = []
    graph = CommerceAssistantGraph(
        _provider_for_payload(payload),
        gateway=LocalModelGateway(),
        tool_observer=observations.append,
    )
    with (
        patch.object(
            graph._intent_router,  # pyright: ignore[reportPrivateUsage] - assert zero route calls
            "classify",
            new=AsyncMock(side_effect=AssertionError("NO_ROUTER")),
        ),
        patch.object(
            graph._gateway,  # pyright: ignore[reportPrivateUsage] - assert zero parameter calls
            "suggest_task_parameters",
            new=AsyncMock(side_effect=AssertionError("NO_PARAMETERS")),
        ),
        patch.object(
            type(graph._workflow_plan),  # pyright: ignore[reportPrivateUsage] - exercise route bypass
            "available",
            new_callable=PropertyMock,
            return_value=True,
        ),
    ):
        outcome = asyncio.run(graph.ainvoke(request))
    assert outcome.kind == "TASK_DRAFT" and outcome.intent is Intent.GEO
    draft = outcome.task_draft
    assert draft is not None
    assert draft.parameters == payload["monitorActionContext"]["parameters"]
    assert set(draft.parameters) == {
        "mode",
        "topicId",
        "questionIds",
        "platformCodes",
        "sampleMode",
        "region",
    }
    assert draft.draft_operation is DraftOperation.NEW and draft.previous_draft_id is None
    assert draft.space_id == request.trusted_state.selected_space_id
    assert draft.product_id == request.trusted_state.selected_product_id
    assert "尚未开始执行" in outcome.message and "已受理" not in outcome.message
    assert outcome.usage.model_call_count == outcome.usage.provider_call_count == 0
    assert outcome.usage.tool_call_count == outcome.usage.context_load_count == 1
    assert len(observations) == 1 and observations[0].tool_name == "task_draft.propose"
    assert observations[0].attempt == 1


def test_monitor_action_still_obeys_tool_policy_and_effective_capability() -> None:
    request = _request()
    payload = _monitor_payload(request)
    payload["loadedToolGroups"] = []
    with pytest.raises(ToolExecutionError) as caught:
        asyncio.run(CommerceAssistantGraph(_provider_for_payload(payload)).ainvoke(request))
    assert caught.value.code == "TOOL_GROUP_NOT_AUTHORIZED"
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", ("chat", "image.generate"))),
        pytest.raises(AgentIdentityError, match="AGENT_CAPABILITY_DENIED"),
    ):
        asyncio.run(
            CommerceAssistantGraph(_provider_for_payload(_monitor_payload(request))).ainvoke(
                request
            )
        )


def test_monitor_action_obeys_tool_budget_without_fallback_or_retry() -> None:
    request = _request()
    counters = RuntimeHarness().new_usage_counters(request)
    for _ in range(request.budget.max_tool_calls):
        counters.before_tool_call()
    graph = CommerceAssistantGraph(_provider_for_payload(_monitor_payload(request)))
    with pytest.raises(HarnessViolation, match="tool budget"):
        asyncio.run(graph.ainvoke(request, usage_counters=counters))


def test_local_context_cannot_mint_current_java_action() -> None:
    request = _request()
    context = asyncio.run(_provider_for_payload(_monitor_payload(request)).load(request))
    graph = CommerceAssistantGraph(_provider_for_payload(_monitor_payload(request)))
    with pytest.raises(DomainGraphError, match="GEO_PARAMETERS_INVALID"):
        asyncio.run(
            graph.ainvoke(
                request, authorized_context=replace(context, source="LOCAL_TRUSTED_STATE")
            )
        )


def test_runtime_monitor_subtype_cannot_be_requested_through_model_tool_schema() -> None:
    request = _request()
    context = asyncio.run(_provider_for_payload(_monitor_payload(request)).load(request))
    monitor = context.monitor_action_context
    assert monitor is not None
    public = DEFAULT_TOOL_REGISTRY.resolve("task_draft.propose", "1").input_model
    assert "monitorActionContext" not in str(public.model_json_schema())
    ordinary = TaskDraftPrepareInput(
        intent=Intent.GEO, request_text="本轮监测", candidate_summary="监测"
    )
    with pytest.raises(ValidationError):
        public.model_validate({**ordinary.model_dump(), "monitor_action_context": monitor})
    private = _MonitorTaskDraftInput(
        **ordinary.model_dump(
            exclude={"selected_space_id", "selected_product_id", "candidate_parameters"}
        ),
        selected_space_id=monitor.space_id,
        selected_product_id=monitor.product_id,
        candidate_parameters=monitor.parameters.model_dump(mode="json", by_alias=True),
        monitor_action_context=monitor,
    )
    mutated = copy.deepcopy(private.candidate_parameters)
    mutated["platformCodes"] = ["DEEPSEEK"]
    with pytest.raises(DomainGraphError, match="GEO_PARAMETERS_INVALID"):
        prepare_task_draft_input(private.model_copy(update={"candidate_parameters": mutated}))


def test_plain_language_cannot_propose_existing_monitor_matrix() -> None:
    request = _request(content="帮我监测当前商品在 AI 平台的表现")
    outcome = asyncio.run(
        CommerceAssistantGraph(_provider_for_payload(_payload(request))).ainvoke(request)
    )
    assert (
        outcome.task_draft is None or outcome.task_draft.parameters["mode"] == "QUESTION_DISCOVERY"
    )
    assert outcome.usage.provider_call_count == 0


def test_run_manager_emits_one_monitor_proposal_without_router_probe_or_model() -> None:
    async def scenario() -> None:
        request = _request(content="本轮明确选择的监测")
        payload = _monitor_payload(request)
        manager = ChatRunManager(
            initialize_runtime(AppSettings(mock_event_delay_ms=0)),
            LocalModelGateway(),
            _provider_for_payload(payload),
        )
        try:
            with (
                patch.object(manager, "_router_is_ready", return_value=False),
                patch.object(
                    NativeAgentLoop,
                    "ainvoke",
                    new=AsyncMock(side_effect=AssertionError("NO_NATIVE_MODEL_LOOP")),
                ),
                patch.object(
                    manager._gateway,  # pyright: ignore[reportPrivateUsage]
                    "probe",
                    new=AsyncMock(side_effect=AssertionError("NO_MODEL_PROBE")),
                ),
            ):
                await manager.start(request)
                events = await _collect(manager, request.run_id)
            assert events[-1].event_type == "run.completed"
            proposals = [event for event in events if event.event_type == "task-draft.proposed"]
            assert len(proposals) == 1
            assert (
                proposals[0].payload["parameters"] == payload["monitorActionContext"]["parameters"]
            )
            usage = next(event.payload for event in events if event.event_type == "usage.reported")
            assert usage["modelCallCount"] == usage["providerCallCount"] == 0
            assert usage["contextLoadCount"] == usage["toolCallCount"] == 1
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_stop_during_monitor_prepare_never_emits_proposal_or_success() -> None:
    async def scenario() -> None:
        entered, release = asyncio.Event(), asyncio.Event()

        async def blocked(payload: Any):
            entered.set()
            await release.wait()
            return prepare_task_draft(payload)

        request = _request()
        with patch("app.agent.explicit_actions.prepare_task_draft", new=blocked):
            manager = ChatRunManager(
                initialize_runtime(AppSettings(mock_event_delay_ms=0)),
                LocalModelGateway(),
                _provider_for_payload(_monitor_payload(request)),
            )
            try:
                await manager.start(request)
                await asyncio.wait_for(entered.wait(), 2)
                await manager.stop(request.run_id)
                events = await _collect(manager, request.run_id)
                kinds = [event.event_type for event in events]
                assert events[-1].event_type == "run.completed"
                assert events[-1].payload["status"] == "STOPPED"
                assert "task-draft.proposed" not in kinds
                assert "message.delta" not in kinds
            finally:
                release.set()
                await manager.close()

    asyncio.run(scenario())
