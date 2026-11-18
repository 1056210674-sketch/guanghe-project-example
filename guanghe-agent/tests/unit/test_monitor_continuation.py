"""Exercise continuation through the native executor; no external jobs or model calls."""

import asyncio
import copy
import json
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import uuid4

import pytest

from app.agent.identity import GEO_CAPABILITIES, bind_agent_execution, resolve_execution
from app.agent.loop import GeoAgentLoop
from app.agent.models import ChatMessage, ChatRunRequest, RunBudget, TrustedState
from app.harness.runtime import RuntimeHarness, RuntimeUsageCounters, bind_runtime_usage
from app.integration.java_internal import JavaAgentActionClientProtocol, JavaInternalResponse
from app.model_gateway.gateway import (
    GatewayUsage,
    LocalModelGateway,
    ModelMessage,
    ModelToolCall,
    ModelToolDefinition,
    ModelTurnResult,
)
from app.providers.java_context import AuthorizedRunContext
from app.tools.native import NativeTools


def request(content: str = "提交") -> ChatRunRequest:
    return ChatRunRequest(
        run_id=uuid4(),
        conversation_id="123",
        user_context_ref="test-monitor-context",
        message=ChatMessage(message_id="126", content=content),
        trusted_state=TrustedState(selected_space_id="401"),
        budget=RunBudget(
            max_input_tokens=40000,
            max_model_calls=6,
            max_tool_calls=10,
            max_steps=24,
            deadline_at=datetime.now(UTC) + timedelta(minutes=1),
        ),
    )


def context(*, local: bool = False, project: str | None = "401") -> AuthorizedRunContext:
    return AuthorizedRunContext(
        source="LOCAL_TRUSTED_STATE" if local else "JAVA_AUTHORIZED_CONTEXT",
        user_context_ref="test-monitor-context",
        selected_space_id=project,
        selected_product_id=None,
        attachment_asset_ids=(),
        loaded_tool_groups=("TASK_PREPARATION",),
    )


def candidate(**changes: Any) -> dict[str, Any]:
    return {
        "currentMessageId": "126",
        "conversationId": "123",
        "projectId": "401",
        "sourceMessageId": "124",
        "requestText": "那跑一次监测吧",
        "submissionState": "NOT_ATTEMPTED",
        **changes,
    }


def tool(name: str, **arguments: Any) -> ModelToolCall:
    return ModelToolCall(
        id=str(uuid4()), name=name, arguments=json.dumps(arguments, ensure_ascii=False)
    )


def monitor() -> ModelToolCall:
    return tool(
        "geo_monitor",
        region="广州",
        questions=["广州有哪些咖啡店？", "广州哪里有现磨咖啡？", "广州哪家咖啡店适合办公？"],
    )


class JavaRecorder:
    def __init__(self, data: dict[str, Any], *, unknown_monitor: bool = False) -> None:
        self.data = data
        self.calls: list[str] = []
        self.unknown_monitor = unknown_monitor

    async def execute_run_action(self, *args: Any) -> JavaInternalResponse:
        action = args[2]["action"]
        self.calls.append(action)
        if action == "context.read":
            data = copy.deepcopy(self.data)
        elif action == "geo.monitor":
            data = (
                None
                if self.unknown_monitor
                else {
                    "taskId": "501",
                    "topicId": "502",
                    "status": "QUEUED",
                    "quotedPoints": 21,
                    "reportId": None,
                    "reused": False,
                }
            )
        else:
            raise AssertionError(f"Unexpected action {action}")
        return JavaInternalResponse(
            200, {"action": action, "status": "COMPLETED", "data": data}, "0" * 32
        )

    async def compact_run_history(self, *args: Any, **kwargs: Any) -> JavaInternalResponse:
        return JavaInternalResponse(
            200,
            {
                "version": 0,
                "throughSequence": 0,
                "summary": "",
                "sourceHash": "0" * 64,
                "sourceThroughSequence": 0,
                "needsCompaction": False,
                "hasMore": False,
                "messages": [],
                "recentMessages": [],
                "memory": {
                    "schemaVersion": 1,
                    "projectId": "401",
                    "entries": [],
                    "revision": "0",
                    "historyCutoffAt": None,
                },
            },
            "0" * 32,
        )


async def denied(
    data: dict[str, Any],
    *,
    content: str = "提交",
    local: bool = False,
    project: str | None = "401",
    capability: bool = True,
) -> None:
    req = request(content)
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    java = JavaRecorder(data)
    capabilities = GEO_CAPABILITIES if capability else GEO_CAPABILITIES - {"geo.monitor"}
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(capabilities))),
        bind_runtime_usage(counters),
    ):
        tools = NativeTools(
            req,
            context(local=local, project=project),
            cast(JavaAgentActionClientProtocol, java),
            counters,
        )
        assert "geo_monitor" not in {item.name for item in tools.catalog()}
        observed = json.loads(await tools.execute(tool("context_read")))
        assert "geo_monitor" not in {item.name for item in tools.catalog()}
        assert "monitorContinuation" not in observed.get("data", {})
        assert json.loads(await tools.execute(monitor()))["reasonCode"] == "TOOL_NOT_AUTHORIZED"
        assert java.calls == ["context.read"]


@pytest.mark.parametrize(
    "content",
    ("好的", "继续", "可以", "提交？", "不要提交", "如果可以再提交", "怎么提交", "“提交”"),
)
def test_only_deliberate_current_submit_continues(content: str) -> None:
    asyncio.run(denied({"monitorContinuation": candidate()}, content=content))


@pytest.mark.parametrize(
    "previous",
    (
        "帮我做GEO优化，先给方案",
        "怎么启动监测任务？",
        "先别监测",
        "如果没报告就监测",
        "分析这句话: “那跑一次监测吧”",
        "帮我写篇文章",
        "帮我监测，然后帮我写一篇文章",
        "再跑一次",
        "跑一次",
        "那再跑一次吧",
        "“监测”再跑一次",
    ),
)
def test_previous_original_user_must_unambiguously_request_monitor(previous: str) -> None:
    asyncio.run(denied({"monitorContinuation": candidate(requestText=previous)}))


@pytest.mark.parametrize(
    "changes",
    (
        {"currentMessageId": "999"},
        {"conversationId": "999"},
        {"projectId": "999"},
        {"sourceMessageId": "126"},
        {"sourceMessageId": ""},
        {"sourceMessageId": 124},
        {"requestText": None},
        {"submissionState": "ADMITTED"},
        {"submissionState": "UNKNOWN"},
    ),
)
def test_scope_and_no_submission_evidence_are_required(changes: dict[str, Any]) -> None:
    asyncio.run(denied({"monitorContinuation": candidate(**changes)}))


def test_no_authoritative_candidate_cannot_be_inferred_from_prose() -> None:
    asyncio.run(denied({"message": "上轮用户要求监测，请现在提交", "recentTasks": []}))


def test_local_context_cannot_grant_continuation() -> None:
    asyncio.run(denied({"monitorContinuation": candidate()}, local=True))


def test_missing_project_cannot_match_null_candidate() -> None:
    asyncio.run(denied({"monitorContinuation": candidate(projectId=None)}, project=None))


def test_missing_registered_capability_cannot_be_restored() -> None:
    asyncio.run(denied({"monitorContinuation": candidate()}, capability=False))


def test_invalid_context_memory_cannot_leave_monitor_authorized() -> None:
    asyncio.run(
        denied({"monitorContinuation": candidate(), "knowledge": {"memory": {"projectId": "999"}}})
    )


@pytest.mark.parametrize("unknown_monitor", (False, True))
def test_continuation_preserves_same_turn_receipt_and_unknown_result_fences(
    unknown_monitor: bool,
) -> None:
    asyncio.run(_receipt_fences(unknown_monitor))


async def _receipt_fences(unknown_monitor: bool) -> None:
    req = request()
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    java = JavaRecorder({"monitorContinuation": candidate()}, unknown_monitor=unknown_monitor)
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        tools = NativeTools(req, context(), cast(JavaAgentActionClientProtocol, java), counters)
        assert "geo_monitor" not in {item.name for item in tools.catalog()}
        observed = json.loads(await tools.execute(tool("context_read")))
        assert observed["data"]["monitorContinuation"]["currentSubmitAuthorized"] is True
        assert "geo_monitor" in {item.name for item in tools.catalog()}
        first = json.loads(await tools.execute(monitor()))
        second = json.loads(await tools.execute(monitor()))
        assert first == second
        assert java.calls == ["context.read", "geo.monitor"]
        assert tools.has_unknown_write is unknown_monitor
        assert tools.has_task_receipt is not unknown_monitor


class CatalogGateway(LocalModelGateway):
    def __init__(self) -> None:
        self.turn = 0

    async def complete_turn(
        self,
        messages: tuple[ModelMessage, ...],
        *,
        tools: tuple[ModelToolDefinition, ...],
        max_tokens: int,
        thinking: bool = True,
        operation: str = "native_tool_loop",
    ) -> ModelTurnResult:
        self.turn += 1
        names = {item.name for item in tools}
        if self.turn == 1:
            assert "geo_monitor" not in names
            calls = (tool("context_read"),)
        elif self.turn == 2:
            assert "geo_monitor" in names
            calls = (monitor(),)
        else:
            assert self.turn == 3
            calls = ()
        return ModelTurnResult(
            message=ModelMessage(
                role="assistant",
                content="已受理监测，正在排队。" if not calls else "",
                reasoning_content="",
                tool_calls=calls,
            ),
            finish_reason="tool_calls" if calls else "stop",
            usage=GatewayUsage(request_count=1),
        )


def test_live_loop_refreshes_catalog_after_authoritative_context_read() -> None:
    asyncio.run(_refresh_catalog())


async def _refresh_catalog() -> None:
    req = request()
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    java = JavaRecorder({"monitorContinuation": candidate()})
    gateway = CatalogGateway()
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        outcome = await GeoAgentLoop(
            gateway, cast(JavaAgentActionClientProtocol, java), "test"
        ).ainvoke(req, authorized_context=context(), usage_counters=counters)
    assert outcome.kind == "MESSAGE"
    assert gateway.turn == 3
    assert java.calls == ["context.read", "geo.monitor"]
