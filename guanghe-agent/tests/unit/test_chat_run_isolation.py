"""Deterministic Chat Run isolation, Stop, SSE, and capacity tests."""

import asyncio
import json
import logging
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import UUID, uuid4

import httpx2
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import JsonValue
from starlette.requests import Request
from starlette.types import Scope

from app.agent.models import (
    ChatMessage,
    ChatRunEvent,
    ChatRunRequest,
    RunBudget,
    RunStatus,
    TrustedState,
)
from app.agent.run_manager import (
    ChatRunManager,
    RunCapacityError,
    RunNotFoundError,
    _RunRecord,  # pyright: ignore[reportPrivateUsage]
)
from app.api.application import create_app
from app.api.routes.chat_runs import stream_chat_run_events
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings
from app.harness.runtime import record_context_load, record_model_call
from app.model_gateway.gateway import (
    DeltaHandler,
    GatewayUsage,
    LocalModelGateway,
    ModelGatewayError,
    ModelMessage,
    ModelMessages,
    ModelToolDefinition,
    ModelTurnResult,
    last_user_content,
)
from app.observability.logging import JsonFormatter
from app.providers.java_context import (
    AgentIdentity,
    AuthorizedRunContext,
    LocalRunContextProvider,
    MonitorActionContext,
    ProtocolFeatures,
    RunContextUnavailableError,
)
from tests.internal_security import FakeInternalNonceStore, internal_settings, signed_headers

_TEST_TIMEOUT_SECONDS = 5.0
_SENSITIVE_MARKERS = (
    "fake-secret-sk-do-not-log",
    "FULL_SYSTEM_PROMPT_DO_NOT_LOG",
    "PRIVATE_CHAIN_OF_THOUGHT_DO_NOT_LOG",
    "eyJhbGciOiJIUzI1NiJ9.fake-jwt.signature",
    "FULL_PROVIDER_RESPONSE_DO_NOT_LOG",
)


class _BusinessOutcomeContextProvider(LocalRunContextProvider):
    async def load(self, request: ChatRunRequest) -> AuthorizedRunContext:
        return replace(
            await super().load(request),
            protocol_features=ProtocolFeatures(business_outcome=True),
        )


class _MonitorContextProvider(_BusinessOutcomeContextProvider):
    """Explicit monitor authority belongs to one current Run, not its chat history."""

    def __init__(self, authorized_run_id: UUID, *, block: bool = False) -> None:
        self.authorized_run_id = authorized_run_id
        self.block = block
        self.entered = asyncio.Event()
        self.release = asyncio.Event()

    async def load(self, request: ChatRunRequest) -> AuthorizedRunContext:
        context = await super().load(request)
        if request.run_id != self.authorized_run_id:
            return context
        self.entered.set()
        if self.block:
            await _wait(self.release)
        return replace(
            context,
            source="JAVA_AUTHORIZED_CONTEXT",
            monitor_action_context=MonitorActionContext.model_validate(
                {
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
                        {
                            "id": str(701 + i),
                            "category": "BRAND_DISCOVERY",
                            "text": f"测试问题{i}？",
                        }
                        for i in range(3)
                    ],
                }
            ),
        )


class _NativeGateway(LocalModelGateway):
    """A deterministic native protocol fixture; no legacy router is invoked."""

    async def complete_turn(
        self,
        messages: ModelMessages,
        *,
        tools: tuple[ModelToolDefinition, ...],
        max_tokens: int = 4096,
        thinking: bool = True,
        operation: str = "agent_loop",
    ) -> ModelTurnResult:
        del tools, max_tokens, thinking
        assert operation == "native_agent_loop"
        record_model_call(input_messages=messages)
        return ModelTurnResult(
            ModelMessage("assistant", "已回应本轮请求: " + last_user_content(messages)),
            "stop",
            GatewayUsage(request_count=1, input_tokens=30, output_tokens=4, latency_ms=40),
        )


async def _wait(event: asyncio.Event) -> None:
    await asyncio.wait_for(event.wait(), timeout=_TEST_TIMEOUT_SECONDS)


def _request(
    content: str,
    *,
    conversation_id: str = "conversation-shared",
    space_id: str | None = None,
    product_id: str | None = None,
    attachment_ids: tuple[str, ...] = (),
    pending_interaction_id: str | None = None,
    draft_id: str | None = None,
) -> ChatRunRequest:
    return ChatRunRequest(
        run_id=uuid4(),
        conversation_id=conversation_id,
        user_context_ref=f"opaque-{uuid4()}",
        message=ChatMessage(
            message_id=str(uuid4()),
            content=content,
            attachment_asset_ids=attachment_ids,
        ),
        trusted_state=TrustedState(
            selected_space_id=space_id,
            selected_product_id=product_id,
            pending_interaction_id=pending_interaction_id,
            draft_id=draft_id,
        ),
        budget=RunBudget(deadline_at=datetime.now(UTC) + timedelta(minutes=1)),
    )


def _manager(
    gateway: LocalModelGateway | None = None,
    *,
    context_provider: LocalRunContextProvider | None = None,
    max_local_runs: int = 100,
    business_outcome_enabled: bool = False,
) -> ChatRunManager:
    runtime = initialize_runtime(
        AppSettings(
            max_local_runs=max_local_runs,
            mock_event_delay_ms=0,
            business_outcome_enabled=business_outcome_enabled,
        )
    )
    return ChatRunManager(
        runtime,
        gateway or _NativeGateway(),
        context_provider or LocalRunContextProvider(),
    )


@pytest.mark.parametrize("output_enabled", (False, True))
@pytest.mark.parametrize("authorized", (None, False, True))
def test_business_outcome_requires_both_frozen_authorization_and_output_flag(
    output_enabled: bool, authorized: bool | None
) -> None:
    class FeatureContextProvider(LocalRunContextProvider):
        async def load(self, request: ChatRunRequest) -> AuthorizedRunContext:
            context = await super().load(request)
            return replace(
                context,
                protocol_features=None
                if authorized is None
                else ProtocolFeatures(business_outcome=authorized),
            )

    async def scenario() -> None:
        manager = _manager(
            context_provider=FeatureContextProvider(),
            business_outcome_enabled=output_enabled,
        )
        request = _request("你好，介绍一下你能帮我做什么")
        try:
            await manager.start(request)
            events = await _collect(manager, request.run_id)
            completed = _payload(events, "run.completed")
            assert completed["status"] == "COMPLETED"
            if output_enabled and authorized:
                assert completed["businessOutcome"] == {
                    "schemaVersion": 1,
                    "status": "ANSWERED",
                }
            else:
                assert "businessOutcome" not in completed
            replay = await _collect(manager, request.run_id)
            assert _payload(replay, "run.completed") == completed
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_python_proposes_draft_but_never_claims_java_draft_is_ready() -> None:
    async def scenario() -> None:
        request = _request("按已选择的范围监测一次", space_id="401", product_id="402")
        manager = _manager(
            context_provider=_MonitorContextProvider(request.run_id),
            business_outcome_enabled=True,
        )
        try:
            await manager.start(request)
            events = await _collect(manager, request.run_id)
            assert "task-draft.proposed" in {event.event_type for event in events}
            proposal = _payload(events, "task-draft.proposed")
            assert proposal["taskType"] == "GEO"
            assert proposal["spaceId"] == "401"
            assert proposal["productId"] == "402"
            assert _payload(events, "run.completed")["businessOutcome"] == {
                "schemaVersion": 1,
                "status": "ANSWERED",
            }
            assert not {"taskId", "confirmedAt", "chargedPoints"} & proposal.keys()
            assert _payload(events, "usage.reported")["modelCallCount"] == 0
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_protocol_features_are_frozen_after_first_authorized_context() -> None:
    async def scenario() -> None:
        request = _request("随便聊聊")
        record = _RunRecord(request=request, trace_id="test", traceparent="test")
        context = await LocalRunContextProvider().load(request)
        freeze = ChatRunManager._freeze_protocol_features  # pyright: ignore[reportPrivateUsage]
        freeze(record, context)
        freeze(record, replace(context, protocol_features=ProtocolFeatures(business_outcome=True)))
        assert record.protocol_features_frozen
        assert not record.business_outcome_authorized

    asyncio.run(scenario())


async def _collect(
    manager: ChatRunManager,
    run_id: UUID,
    after_sequence: int = 0,
) -> tuple[ChatRunEvent, ...]:
    events: list[ChatRunEvent] = []
    async with asyncio.timeout(_TEST_TIMEOUT_SECONDS):
        async for event in manager.events(run_id, after_sequence=after_sequence):
            events.append(event)
    return tuple(events)


def _payload(
    events: tuple[ChatRunEvent, ...],
    event_type: str,
) -> dict[str, JsonValue]:
    return next(event.payload for event in events if event.event_type == event_type)


def _sse_sequences(raw: str) -> list[int]:
    return [int(line.removeprefix("id: ")) for line in raw.splitlines() if line.startswith("id: ")]


class _JsonCaptureHandler(logging.Handler):
    def __init__(self) -> None:
        super().__init__()
        self.messages: list[str] = []
        self.setFormatter(JsonFormatter())

    def emit(self, record: logging.LogRecord) -> None:
        self.messages.append(self.format(record))


class _RecordingContextProvider(LocalRunContextProvider):
    def __init__(self) -> None:
        self.loaded: dict[UUID, AuthorizedRunContext] = {}

    async def load(self, request: ChatRunRequest) -> AuthorizedRunContext:
        context = await super().load(request)
        if request.trusted_state.selected_space_id == "402":
            context = replace(
                context,
                agent_identity=AgentIdentity(
                    installation_id="123",
                    agent_key="creative",
                    agent_version="1.0.0",
                    name="创作",
                    project_id="402",
                    capabilities=("chat", "resources.read", "image.generate", "video.generate"),
                ),
            )
        self.loaded[request.run_id] = context
        return context


class _CountingGateway(_NativeGateway):
    def __init__(self) -> None:
        self.turn_calls = 0

    async def complete_turn(self, messages: ModelMessages, **kwargs: Any) -> ModelTurnResult:
        self.turn_calls += 1
        return await super().complete_turn(messages, **kwargs)


def test_native_tool_parameter_system_failure_is_terminal_failed_not_a_user_question() -> None:
    class InvalidParametersGateway(_NativeGateway):
        async def complete_turn(self, messages: ModelMessages, **kwargs: Any) -> ModelTurnResult:
            del kwargs
            record_model_call(input_messages=messages)
            record_model_call()
            raise ModelGatewayError(
                "DEEPSEEK_NATIVE_TOOL_CALL_INVALID",
                structured_response_failure=True,
                failure_stage="empty_tool_arguments",
                attempt_count=2,
                usage=GatewayUsage(input_tokens=30, output_tokens=5),
            )

    async def scenario() -> None:
        manager = _manager(InvalidParametersGateway())
        request = _request("读取当前品牌的监测报告", space_id="space-1", product_id="product-1")
        try:
            await manager.start(request)
            events = await _collect(manager, request.run_id)
            assert events[-1].event_type == "run.failed"
            assert events[-1].payload["code"] == "DEEPSEEK_NATIVE_TOOL_CALL_INVALID"
            assert manager.get(request.run_id).status is RunStatus.FAILED
            assert not any(
                event.event_type in {"interaction.required", "task-draft.proposed", "run.completed"}
                for event in events
            )
            usage_events = [event for event in events if event.event_type == "usage.reported"]
            assert len(usage_events) == 1
            usage = usage_events[0].payload
            assert usage["modelCallCount"] == 2
            assert usage["toolCallCount"] == 0
            assert usage["providerInputTokens"] == 30
            assert usage["providerOutputTokens"] == 5
        finally:
            await manager.close()

    asyncio.run(scenario())


class _BlockingFailureGateway(_NativeGateway):
    stopped_content = "需要停止的普通对话"

    def __init__(self) -> None:
        self.entered = asyncio.Event()
        self.release = asyncio.Event()

    async def complete_turn(self, messages: ModelMessages, **kwargs: Any) -> ModelTurnResult:
        content = last_user_content(messages)
        if content == self.stopped_content:
            self.entered.set()
            await _wait(self.release)
            raise ModelGatewayError("TEST_MODEL_TIMEOUT", retryable=True)
        return await super().complete_turn(messages, **kwargs)


class _SelectiveFailureGateway(_NativeGateway):
    async def complete_turn(self, messages: ModelMessages, **kwargs: Any) -> ModelTurnResult:
        content = last_user_content(messages)
        if content == "仅此运行失败":
            raise ModelGatewayError("TEST_MODEL_INVALID_RESPONSE")
        return await super().complete_turn(messages, **kwargs)


class _UsageFailureGateway(_NativeGateway):
    async def complete_turn(self, messages: ModelMessages, **kwargs: Any) -> ModelTurnResult:
        del kwargs
        record_model_call(input_messages=messages)
        record_model_call()
        raise ModelGatewayError(
            "DEEPSEEK_EMPTY_RESPONSE",
            # Both bounded HTTP attempts are already aggregated by the adapter.
            usage=GatewayUsage(input_tokens=55, output_tokens=4, latency_ms=90, request_count=2),
        )


class _FailingContextProvider(LocalRunContextProvider):
    async def load(self, request: ChatRunRequest) -> AuthorizedRunContext:
        del request
        record_context_load()
        raise RunContextUnavailableError("JAVA_RUN_CONTEXT_TIMEOUT")


class _SensitiveFailure(RuntimeError):
    code = "SAFE_MODEL_FAILURE"


class _SensitiveFailureGateway(_NativeGateway):
    async def complete_turn(self, messages: ModelMessages, **kwargs: Any) -> ModelTurnResult:
        del messages, kwargs
        try:
            raise RuntimeError(f"provider cause: {' | '.join(_SENSITIVE_MARKERS)}")
        except RuntimeError as cause:
            raise _SensitiveFailure(
                f"provider exception: {' | '.join(_SENSITIVE_MARKERS)}"
            ) from cause


class _CapacityGateway(_NativeGateway):
    def __init__(self, blocked_contents: set[str]) -> None:
        self.entered = {content: asyncio.Event() for content in blocked_contents}
        self.release = {content: asyncio.Event() for content in blocked_contents}

    async def complete_turn(self, messages: ModelMessages, **kwargs: Any) -> ModelTurnResult:
        content = last_user_content(messages)
        if content in self.entered:
            self.entered[content].set()
            await _wait(self.release[content])
        return await super().complete_turn(messages, **kwargs)


class _TerminalBeforeTaskDoneManager(ChatRunManager):
    def __init__(self) -> None:
        runtime = initialize_runtime(AppSettings(max_local_runs=1, mock_event_delay_ms=0))
        super().__init__(runtime, LocalModelGateway(), LocalRunContextProvider())
        self.terminal_status_set = asyncio.Event()
        self.allow_task_return = asyncio.Event()

    async def _execute(self, record: _RunRecord) -> None:
        record.status = RunStatus.COMPLETED
        self.terminal_status_set.set()
        await _wait(self.allow_task_return)


def test_geo_and_creative_runs_keep_state_context_and_events_isolated() -> None:
    class ContextRecordingGateway(_NativeGateway):
        def __init__(self) -> None:
            self.turns: dict[str, tuple[ModelMessages, tuple[ModelToolDefinition, ...]]] = {}

        async def complete_turn(
            self, messages: ModelMessages, *, tools: tuple[ModelToolDefinition, ...], **kwargs: Any
        ) -> ModelTurnResult:
            self.turns[last_user_content(messages)] = messages, tools
            return await super().complete_turn(messages, tools=tools, **kwargs)

    async def scenario() -> None:
        context_provider = _RecordingContextProvider()
        gateway = ContextRecordingGateway()
        manager = _manager(gateway, context_provider=context_provider)
        await manager.initialize()
        geo = _request(
            "执行GEO咖啡机关键词分析",
            space_id="401",
            attachment_ids=("asset-geo",),
            pending_interaction_id="interaction-geo",
        )
        image = _request(
            "为当前商品生成厨房暖光主图",
            space_id="402",
            product_id="product-image",
            attachment_ids=("asset-image",),
            draft_id="draft-image",
        )
        try:
            await manager.start(geo)
            await manager.start(image)
            geo_events, image_events = await asyncio.gather(
                _collect(manager, geo.run_id),
                _collect(manager, image.run_id),
            )

            assert all(event.run_id == geo.run_id for event in geo_events)
            assert all(event.run_id == image.run_id for event in image_events)
            assert [event.sequence for event in geo_events] == list(range(1, len(geo_events) + 1))
            assert [event.sequence for event in image_events] == list(
                range(1, len(image_events) + 1)
            )

            geo_messages, geo_tools = gateway.turns[geo.message.content]
            image_messages, image_tools = gateway.turns[image.message.content]
            assert not {"image_generate", "video_generate"} & {tool.name for tool in geo_tools}
            assert "image_generate" in {tool.name for tool in image_tools}
            # Creative capability visibility is stable; per-action execution
            # still requires the current-user fence or a scoped intent review.
            assert "video_generate" in {tool.name for tool in image_tools}
            assert not {"geo_monitor", "article_generate"} & {tool.name for tool in image_tools}
            assert "asset-geo" in geo_messages[0].content
            assert "asset-image" not in geo_messages[0].content
            assert "asset-image" in image_messages[0].content
            assert "asset-geo" not in image_messages[0].content
            assert geo_messages[-1].content == geo.message.content
            assert image_messages[-1].content == image.message.content
            assert _payload(geo_events, "run.completed")["domainGraph"] == "native-tool-loop-v1"
            assert _payload(image_events, "run.completed")["domainGraph"] == "native-tool-loop-v1"

            assert context_provider.loaded[geo.run_id].attachment_asset_ids == ("asset-geo",)
            assert context_provider.loaded[image.run_id].attachment_asset_ids == ("asset-image",)
            assert manager.get(geo.run_id).status is RunStatus.COMPLETED
            assert manager.get(image.run_id).status is RunStatus.COMPLETED
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_queued_stop_skips_native_loop_and_model_calls() -> None:
    async def scenario() -> None:
        gateway = _CountingGateway()
        manager = _manager(gateway)
        await manager.initialize()
        request = _request("立即停止的普通对话")
        try:
            await manager.start(request)
            accepted, status_at_stop = await manager.stop(request.run_id)
            events = await _collect(manager, request.run_id)

            assert accepted is True
            assert status_at_stop is RunStatus.QUEUED
            assert gateway.turn_calls == 0
            assert [event.event_type for event in events] == ["run.completed"]
            assert events[-1].payload["status"] == "STOPPED"
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_stop_wins_over_late_model_failure_without_affecting_parallel_run() -> None:
    async def scenario() -> None:
        gateway = _BlockingFailureGateway()
        manager = _manager(gateway)
        await manager.initialize()
        stopped = _request(gateway.stopped_content)
        healthy = _request("另一个正常普通对话")
        try:
            await manager.start(stopped)
            await manager.start(healthy)
            await _wait(gateway.entered)

            accepted, _ = await manager.stop(stopped.run_id)
            gateway.release.set()
            stopped_events, healthy_events = await asyncio.gather(
                _collect(manager, stopped.run_id),
                _collect(manager, healthy.run_id),
            )

            assert accepted is True
            assert manager.get(stopped.run_id).status is RunStatus.STOPPED
            assert stopped_events[-1].payload["status"] == "STOPPED"
            assert "run.failed" not in {event.event_type for event in stopped_events}
            assert manager.get(healthy.run_id).status is RunStatus.COMPLETED
            assert healthy_events[-1].payload["status"] == "COMPLETED"
            assert all(event.run_id == healthy.run_id for event in healthy_events)
        finally:
            gateway.release.set()
            await manager.close()

    asyncio.run(scenario())


def test_accepted_stop_before_outcome_prevents_task_draft() -> None:
    async def scenario() -> None:
        request = _request("按已选择的范围监测一次", space_id="401", product_id="402")
        provider = _MonitorContextProvider(request.run_id, block=True)
        manager = _manager(
            context_provider=provider,
            business_outcome_enabled=True,
        )
        await manager.initialize()
        try:
            await manager.start(request)
            await _wait(provider.entered)
            accepted, _ = await manager.stop(request.run_id)
            provider.release.set()
            events = await _collect(manager, request.run_id)

            assert accepted is True
            assert manager.get(request.run_id).status is RunStatus.STOPPED
            assert events[-1].payload["status"] == "STOPPED"
            assert "task-draft.proposed" not in {event.event_type for event in events}
            assert "businessOutcome" not in events[-1].payload
            assert "usage.reported" in {event.event_type for event in events}
        finally:
            provider.release.set()
            await manager.close()

    asyncio.run(scenario())


def test_stop_cancels_blocked_model_without_waiting_for_another_token() -> None:
    class BlockingGateway(_BlockingFailureGateway):
        def __init__(self) -> None:
            super().__init__()
            self.cancelled = asyncio.Event()

        @property
        def is_mock(self) -> bool:
            return False

        async def complete_turn_stream(
            self, messages: ModelMessages, *, on_delta: DeltaHandler, **kwargs: Any
        ) -> ModelTurnResult:
            del kwargs
            record_model_call(input_messages=messages)
            # Nothing has arrived yet. Stop must interrupt the read, not wait for a token.
            assert callable(on_delta)
            self.entered.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                self.cancelled.set()
                raise
            raise AssertionError("The blocked model must be cancelled")

    async def scenario() -> None:
        gateway = BlockingGateway()
        manager = _manager(gateway)
        await manager.initialize()
        request = _request("解释一下商品定位")
        try:
            await manager.start(request)
            await _wait(gateway.entered)
            assert (await manager.stop(request.run_id))[0] is True
            assert (await manager.stop(request.run_id))[0] is True
            await _wait(gateway.cancelled)
            events = await _collect(manager, request.run_id)
            terminals = [event for event in events if event.event_type == "run.completed"]
            assert len(terminals) == 1
            assert terminals[0].payload["status"] == "STOPPED"
            assert manager.get(request.run_id).status is RunStatus.STOPPED
            assert not any(event.event_type == "message.delta" for event in events)
            assert _payload(events, "usage.reported")["modelCallCount"] == 1
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_failed_run_does_not_fail_or_hide_another_run() -> None:
    async def scenario() -> None:
        manager = _manager(_SelectiveFailureGateway())
        await manager.initialize()
        failed = _request("仅此运行失败")
        healthy = _request("正常普通对话")
        try:
            await manager.start(failed)
            await manager.start(healthy)
            failed_events, healthy_events = await asyncio.gather(
                _collect(manager, failed.run_id),
                _collect(manager, healthy.run_id),
            )

            assert manager.get(failed.run_id).status is RunStatus.FAILED
            assert failed_events[-1].event_type == "run.failed"
            assert failed_events[-1].payload["code"] == "TEST_MODEL_INVALID_RESPONSE"
            assert manager.get(healthy.run_id).status is RunStatus.COMPLETED
            assert healthy_events[-1].payload["status"] == "COMPLETED"
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_failed_model_run_reports_aggregated_usage_once_before_run_failed() -> None:
    async def scenario() -> tuple[ChatRunEvent, ...]:
        manager = _manager(_UsageFailureGateway())
        await manager.initialize()
        request = _request("触发带用量的模型失败")
        try:
            await manager.start(request)
            return await _collect(manager, request.run_id)
        finally:
            await manager.close()

    events = asyncio.run(scenario())
    event_types = [event.event_type for event in events]
    assert event_types[-2:] == ["usage.reported", "run.failed"]
    assert event_types.count("usage.reported") == 1
    usage = _payload(events, "usage.reported")
    assert usage["modelCallCount"] == 2
    assert usage["modelCalls"] == 2
    assert usage["steps"] == usage["workflowStepCount"]
    assert usage["toolCalls"] == usage["toolCallCount"]
    assert usage["providerInputTokens"] == 55
    assert usage["providerOutputTokens"] == 4
    assert usage["providerLatencyMs"] == 90
    failed_progress = [
        event.payload
        for event in events
        if event.event_type == "run.progress" and event.payload["status"] == "FAILED"
    ]
    assert failed_progress == [
        {
            "stageCode": "PLANNING_WORKFLOW",
            "status": "FAILED",
            "ordinal": 1,
            "elapsedMs": failed_progress[0]["elapsedMs"],
            "current": None,
            "total": None,
        }
    ]


def test_context_failure_reports_context_count_and_zero_model_usage() -> None:
    async def scenario() -> tuple[ChatRunEvent, ...]:
        manager = _manager(context_provider=_FailingContextProvider())
        await manager.initialize()
        request = _request("上下文加载失败")
        try:
            await manager.start(request)
            return await _collect(manager, request.run_id)
        finally:
            await manager.close()

    events = asyncio.run(scenario())
    assert [event.event_type for event in events][-2:] == ["usage.reported", "run.failed"]
    usage = _payload(events, "usage.reported")
    assert usage["contextLoadCount"] == 1
    assert usage["modelCallCount"] == 0
    assert usage["providerInputTokens"] == 0
    assert usage["providerOutputTokens"] == 0


def test_confirmation_run_cannot_consume_another_runs_draft() -> None:
    async def scenario() -> None:
        draft = _request(
            "按已选择的范围监测一次",
            space_id="401",
            product_id="402",
        )
        provider = _MonitorContextProvider(draft.run_id)
        gateway = _CountingGateway()
        manager = _manager(gateway, context_provider=provider)
        await manager.initialize()
        confirmation = _request(
            "确认",
            space_id="401",
            product_id="402",
            pending_interaction_id="interaction-from-previous-run",
            draft_id="draft-from-previous-run",
        )
        try:
            await manager.start(draft)
            draft_events = await _collect(manager, draft.run_id)
            await manager.start(confirmation)
            confirmation_events = await _collect(manager, confirmation.run_id)

            assert "task-draft.proposed" in {event.event_type for event in draft_events}
            assert "task-draft.proposed" not in {event.event_type for event in confirmation_events}
            assert _payload(draft_events, "run.completed")["domainGraph"] == "explicit-action-v1"
            assert (
                _payload(confirmation_events, "run.completed")["domainGraph"]
                == "native-tool-loop-v1"
            )
            assert gateway.turn_calls == 1
            assert _payload(confirmation_events, "usage.reported")["toolCallCount"] == 0
            assert manager.get(draft.run_id).status is RunStatus.COMPLETED
            assert manager.get(confirmation.run_id).status is RunStatus.COMPLETED
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_sse_sequence_resume_is_exclusive_and_bound_stream_survives_eviction() -> None:
    async def scenario() -> None:
        manager = _manager(max_local_runs=1)
        await manager.initialize()
        first = _request("第一个普通对话")
        second = _request("第二个普通对话")
        try:
            await manager.start(first)
            all_events = await _collect(manager, first.run_id)
            assert [event.sequence for event in all_events] == list(range(1, len(all_events) + 1))

            cursor = all_events[2].sequence
            resumed = await _collect(manager, first.run_id, after_sequence=cursor)
            assert [event.sequence for event in resumed] == [
                event.sequence for event in all_events if event.sequence > cursor
            ]

            manager.get(first.run_id)
            bound_before_eviction = manager.events(first.run_id)
            await manager.start(second)
            with pytest.raises(RunNotFoundError):
                manager.get(first.run_id)
            replayed: list[ChatRunEvent] = []
            async for event in bound_before_eviction:
                replayed.append(event)
            replayed_after_eviction = tuple(replayed)
            assert [event.sequence for event in replayed_after_eviction] == [
                event.sequence for event in all_events
            ]
            await _collect(manager, second.run_id)
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_sse_last_event_id_header_returns_only_newer_events_and_rejects_invalid_value() -> None:
    settings = internal_settings(mock_event_delay_ms=0)
    with TestClient(
        create_app(
            settings,
            gateway_factory=lambda _: _NativeGateway(),
            internal_nonce_store=FakeInternalNonceStore(),
        )
    ) as client:
        accepted = client.post("/dev/v1/runs", json={"content": "普通对话续传测试"})
        assert accepted.status_code == 202
        events_url = cast(str, accepted.json()["eventsUrl"])

        complete = client.get(events_url, headers=signed_headers("GET", events_url))
        all_sequences = _sse_sequences(complete.text)
        assert all_sequences == list(range(1, len(all_sequences) + 1))
        cursor = all_sequences[2]

        resume_headers = signed_headers("GET", events_url)
        resume_headers["Last-Event-ID"] = str(cursor)
        resumed = client.get(events_url, headers=resume_headers)

        invalid_headers = signed_headers("GET", events_url)
        invalid_headers["Last-Event-ID"] = "not-a-sequence"
        invalid = client.get(events_url, headers=invalid_headers)

    assert resumed.status_code == 200
    assert resumed.headers["content-type"].startswith("text/event-stream")
    assert _sse_sequences(resumed.text) == [
        sequence for sequence in all_sequences if sequence > cursor
    ]
    assert all(sequence > cursor for sequence in _sse_sequences(resumed.text))
    assert invalid.status_code == 400
    assert invalid.json()["detail"]["code"] == "INVALID_LAST_EVENT_ID"


def test_sse_route_binds_iterator_before_response_survives_record_eviction() -> None:
    async def scenario() -> None:
        manager = _manager(max_local_runs=1)
        await manager.initialize()
        first = _request("路由绑定前的终态运行")
        second = _request("触发终态回收的新运行")
        try:
            await manager.start(first)
            first_events = await _collect(manager, first.run_id)

            application = FastAPI()
            application.state.run_manager = manager
            request = Request(
                cast(
                    Scope,
                    {
                        "type": "http",
                        "app": application,
                        "headers": [],
                    },
                )
            )
            response = await stream_chat_run_events(first.run_id, request, None)

            await manager.start(second)
            with pytest.raises(RunNotFoundError):
                manager.get(first.run_id)

            chunks: list[bytes] = []
            async with asyncio.timeout(_TEST_TIMEOUT_SECONDS):
                async for chunk in response.body_iterator:
                    chunks.append(chunk.encode() if isinstance(chunk, str) else bytes(chunk))
            body = b"".join(chunks).decode("utf-8")
            assert _sse_sequences(body) == [event.sequence for event in first_events]
            await _collect(manager, second.run_id)
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_internal_start_api_returns_explicit_capacity_error() -> None:
    settings = internal_settings(max_local_runs=1, mock_event_delay_ms=2_000)
    first = _request("占满本地运行容量的普通对话")
    overflow = _request("容量已满时的新运行")

    def post_run(client: TestClient, payload: ChatRunRequest) -> httpx2.Response:
        path = "/internal/v1/chat-runs"
        body = payload.model_dump_json(by_alias=True).encode("utf-8")
        headers = signed_headers("POST", path, body=body)
        headers["Content-Type"] = "application/json"
        return client.post(path, content=body, headers=headers)

    with TestClient(
        create_app(
            settings,
            gateway_factory=lambda _: _NativeGateway(),
            internal_nonce_store=FakeInternalNonceStore(),
        )
    ) as client:
        accepted = post_run(client, first)
        rejected = post_run(client, overflow)

    assert accepted.status_code == 202
    assert rejected.status_code == 503
    assert rejected.json()["detail"]["code"] == "RUN_CAPACITY_FULL"


def test_capacity_rejects_when_full_then_recycles_terminal_without_evicting_running() -> None:
    async def scenario() -> None:
        blocked = {"保持运行一", "保持运行二"}
        gateway = _CapacityGateway(blocked)
        manager = _manager(gateway, max_local_runs=2)
        await manager.initialize()
        first = _request("保持运行一")
        second = _request("保持运行二")
        overflow = _request("容量溢出")
        replacement = _request("回收后的新运行")
        try:
            await manager.start(first)
            await manager.start(second)
            await asyncio.gather(
                _wait(gateway.entered[first.message.content]),
                _wait(gateway.entered[second.message.content]),
            )

            with pytest.raises(RunCapacityError):
                await manager.start(overflow)
            assert manager.get(first.run_id).status is RunStatus.RUNNING
            assert manager.get(second.run_id).status is RunStatus.RUNNING

            gateway.release[first.message.content].set()
            await _collect(manager, first.run_id)
            await manager.start(replacement)

            with pytest.raises(RunNotFoundError):
                manager.get(first.run_id)
            assert manager.get(second.run_id).status is RunStatus.RUNNING
            replacement_events = await _collect(manager, replacement.run_id)
            assert replacement_events[-1].payload["status"] == "COMPLETED"
        finally:
            for release in gateway.release.values():
                release.set()
            await manager.close()

    asyncio.run(scenario())


def test_run_manager_json_log_and_failure_event_never_expose_exception_secrets() -> None:
    handler = _JsonCaptureHandler()
    manager_logger = logging.getLogger("app.agent.run_manager")
    previous_level = manager_logger.level
    previous_propagate = manager_logger.propagate
    manager_logger.addHandler(handler)
    manager_logger.setLevel(logging.ERROR)
    manager_logger.propagate = False

    async def scenario() -> tuple[ChatRunEvent, ...]:
        manager = _manager(_SensitiveFailureGateway())
        await manager.initialize()
        request = _request("触发安全失败事件")
        try:
            await manager.start(request)
            return await _collect(manager, request.run_id)
        finally:
            await manager.close()

    try:
        events = asyncio.run(scenario())
    finally:
        manager_logger.removeHandler(handler)
        manager_logger.setLevel(previous_level)
        manager_logger.propagate = previous_propagate

    assert events[-1].event_type == "run.failed"
    assert events[-1].payload == {
        "status": "FAILED",
        "code": "SAFE_MODEL_FAILURE",
    }
    assert len(handler.messages) == 1
    logged = cast(dict[str, object], json.loads(handler.messages[0]))
    assert logged["message"] == "chat_run_failed"
    assert "exceptionType" not in logged
    serialized_log = handler.messages[0]
    serialized_event = events[-1].model_dump_json(by_alias=True)
    for marker in _SENSITIVE_MARKERS:
        assert marker not in serialized_log
        assert marker not in serialized_event


def test_terminal_status_is_not_recyclable_until_task_is_done() -> None:
    async def scenario() -> None:
        manager = _TerminalBeforeTaskDoneManager()
        await manager.initialize()
        first = _request("终态写入但任务尚未返回")
        replacement = _request("替换运行")
        try:
            await manager.start(first)
            await _wait(manager.terminal_status_set)
            first_task = manager._record(first.run_id).task  # pyright: ignore[reportPrivateUsage]
            assert first_task is not None
            assert manager.get(first.run_id).status is RunStatus.COMPLETED
            assert first_task.done() is False

            with pytest.raises(RunCapacityError):
                await manager.start(replacement)

            manager.allow_task_return.set()
            await first_task
            await manager.start(replacement)
            with pytest.raises(RunNotFoundError):
                manager.get(first.run_id)
        finally:
            manager.allow_task_return.set()
            await manager.close()

    asyncio.run(scenario())
