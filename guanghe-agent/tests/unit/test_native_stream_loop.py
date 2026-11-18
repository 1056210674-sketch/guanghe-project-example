"""Functional native loop: visible phases, provisional text reset and stop semantics."""

import asyncio
import json
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime, timedelta
from typing import Any, cast

import httpx
import pytest
from pydantic import SecretStr, ValidationError

from app.agent.identity import GEO_CAPABILITIES, bind_agent_execution, resolve_execution
from app.agent.loop import GeoAgentLoop
from app.agent.models import ChatRunRequest, MessageDeltaPayload, RunOutcome, Usage
from app.common.settings import ProviderSettings
from app.harness.runtime import (
    RuntimeHarness,
    RuntimeUsageCounters,
    bind_runtime_usage,
    record_model_call,
)
from app.integration.java_internal import JavaAgentActionClientProtocol
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import (
    GatewayUsage,
    LocalModelGateway,
    ModelGatewayError,
    ModelMessage,
    ModelToolCall,
    ModelTurnResult,
)
from tests.unit.test_monitor_continuation import JavaRecorder, context, monitor, request


class StreamingGateway(LocalModelGateway):
    def __init__(self, first: str) -> None:
        self.first = first
        self.calls = 0

    async def complete_turn(self, *_: Any, **__: Any) -> ModelTurnResult:
        raise AssertionError("Native streaming capability must be selected")

    async def complete_turn_stream(self, messages: Any, **kwargs: Any) -> ModelTurnResult:
        record_model_call(input_messages=messages)
        self.calls += 1
        callback = kwargs["on_delta"]
        if self.calls == 1 and self.first != "answer":
            await callback("这是一段临时说明。")
            if self.first == "tool":
                return ModelTurnResult(
                    ModelMessage(
                        "assistant",
                        "这是一段临时说明。",
                        (ModelToolCall("read-1", "context_read", "{}"),),
                        reasoning_content="internal-only",
                    ),
                    "tool_calls",
                    GatewayUsage(request_count=1),
                )
            raise ModelGatewayError(
                "DEEPSEEK_NATIVE_TOOL_CALL_INVALID"
                if self.first == "repair"
                else "DEEPSEEK_STREAM_INCOMPLETE",
                failure_stage="native_tool_unavailable" if self.first == "repair" else "stream",
                usage=GatewayUsage(request_count=1),
            )
        await callback("## 优化方案\n\n")
        await callback("先补充品牌资料。")
        return ModelTurnResult(
            ModelMessage(
                "assistant", "## 优化方案\n\n先补充品牌资料。", reasoning_content="internal-only"
            ),
            "stop",
            GatewayUsage(request_count=1),
        )


@pytest.mark.parametrize("first", ["answer", "tool", "repair", "interrupted"])
def test_native_stream_user_journey(first: str) -> None:
    async def run() -> None:
        req = request("用已有信息给优化方案，不要监测、写文章或投稿")
        gateway = StreamingGateway(first)
        java = JavaRecorder({"proof": "scoped-context"})
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        shown = ""
        events: list[tuple[str, str]] = []

        async def append(text: str) -> None:
            nonlocal shown
            shown += text
            events.append(("delta", text))

        async def replace(text: str) -> None:
            nonlocal shown
            shown = text
            events.append(("replace", text))

        async def progress(stage: Any, status: Any, *_: Any) -> None:
            events.append((stage.value, status.value))

        with (
            bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
            bind_runtime_usage(counters),
        ):
            call = GeoAgentLoop(gateway, cast(JavaAgentActionClientProtocol, java), "test").ainvoke(
                req,
                authorized_context=context(),
                usage_counters=counters,
                on_delta=append,
                on_replace=replace,
                on_progress=progress,
            )
            if first == "interrupted":
                with pytest.raises(ModelGatewayError, match="DEEPSEEK_STREAM_INCOMPLETE"):
                    await call
                assert shown == "这是一段临时说明。"
                assert gateway.calls == 1
                assert events[-1] == ("COMPOSING_RESPONSE", "FAILED")
                return
            result = await call
        assert shown == result.message == "## 优化方案\n\n先补充品牌资料。"
        first_delta = next(i for i, event in enumerate(events) if event[0] == "delta")
        assert events.index(("PLANNING_WORKFLOW", "RUNNING")) < first_delta
        assert events.index(("COMPOSING_RESPONSE", "RUNNING")) < first_delta
        assert events[-1] == ("COMPOSING_RESPONSE", "SUCCEEDED")
        assert "internal-only" not in str(events)
        assert [e for e in events if e[0] == "replace"] == (
            [] if first == "answer" else [("replace", "")]
        )
        assert java.calls == (["context.read"] if first == "tool" else [])
        assert gateway.calls == (1 if first == "answer" else 2)

    asyncio.run(run())


def test_replacement_wire_contract_preserves_append_and_rejects_ambiguous_payloads() -> None:
    assert MessageDeltaPayload.model_validate({"messageId": "1", "delta": "正文"}).replace is False
    assert (
        MessageDeltaPayload.model_validate({"messageId": "1", "delta": "", "replace": True}).delta
        == ""
    )
    for payload in (
        {"messageId": "1", "delta": ""},
        {"messageId": "1", "delta": "正文", "replace": "true"},
        {"messageId": "1", "delta": "正文", "replace": 1},
        {"messageId": "1", "delta": "正文", "reasoning_content": "private"},
        {"messageId": "1", "delta": "a" * 16001},
    ):
        with pytest.raises(ValidationError):
            MessageDeltaPayload.model_validate(payload)


def _frame(payload: dict[str, Any] | str) -> bytes:
    data = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False)
    return ("data: " + data + "\n\n").encode()


def _delta(delta: dict[str, Any], *, finish: str | None = None) -> dict[str, Any]:
    return {"choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}


def _call(*, name: str, arguments: str = "{}", call_id: str = "call-0") -> dict[str, Any]:
    return {
        "index": 0,
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": arguments},
    }


def _tail(finish: str = "stop", *, usage: dict[str, int] | None = None) -> list[bytes]:
    return [
        _frame(_delta({}, finish=finish)),
        _frame({"choices": [], "usage": usage or {"prompt_tokens": 23, "completion_tokens": 11}}),
        _frame("[DONE]"),
    ]


def _truncated_frames(*, partial_tool: bool = False) -> list[bytes]:
    return [
        _frame(_delta({"reasoning_content": "private-incomplete-reasoning"})),
        _frame(_delta({"content": "这段残稿不能拼进新的回答。"})),
        *(
            [_frame(_delta({"tool_calls": [_call(name="geo_monitor", arguments='{"region":')]}))]
            if partial_tool
            else []
        ),
        *_tail("length", usage={"prompt_tokens": 18432, "completion_tokens": 4096}),
    ]


def _answer_frames() -> list[bytes]:
    return [
        _frame(_delta({"content": "## 优化方案\n\n"})),
        _frame(_delta({"content": "先补充品牌资料。"})),
        *_tail(),
    ]


def _protocol_error_frames() -> list[bytes]:
    return [
        _frame(_delta({"reasoning_content": "private-rejected-reasoning"})),
        _frame(_delta({"tool_calls": [_call(name="not_authorized")]})),
        *_tail("tool_calls"),
    ]


class _RecoveryJourney:
    """Exercise the real SSE adapter and executor without sending an external request."""

    def __init__(
        self,
        scripts: list[list[bytes]],
        *,
        req: ChatRunRequest | None = None,
        unknown_monitor: bool = False,
    ) -> None:
        self.scripts = scripts
        self.req = req or request("用已有信息给优化方案，不要监测、写文章或投稿")
        self.counters = RuntimeUsageCounters(self.req, RuntimeHarness())
        self.java = JavaRecorder({"proof": "scoped-context"}, unknown_monitor=unknown_monitor)
        self.requests: list[dict[str, Any]] = []
        self.shown = ""
        self.events: list[tuple[str, str]] = []
        self.before_reply: Callable[[int], None] | None = None
        self.on_reset: Callable[[], Awaitable[None]] | None = None

    async def invoke(self) -> RunOutcome:
        def respond(http_request: httpx.Request) -> httpx.Response:
            self.requests.append(json.loads(http_request.content))
            count = len(self.requests)
            assert count <= len(self.scripts), "Unexpected additional model HTTP call"
            if self.before_reply is not None:
                self.before_reply(count)
            return httpx.Response(200, content=b"".join(self.scripts[count - 1]))

        async def append(text: str) -> None:
            self.shown += text
            self.events.append(("delta", text))

        async def replace(text: str) -> None:
            self.shown = text
            self.events.append(("replace", text))
            if self.on_reset is not None:
                await self.on_reset()

        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(respond)
        ) as client:
            with (
                bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
                bind_runtime_usage(self.counters),
            ):
                gateway = DeepSeekAdapter(
                    ProviderSettings(credential=SecretStr("test-only"), model="synthetic-model"),
                    client=client,
                )
                return await GeoAgentLoop(
                    gateway, cast(JavaAgentActionClientProtocol, self.java), "test"
                ).ainvoke(
                    self.req,
                    authorized_context=context(),
                    usage_counters=self.counters,
                    on_delta=append,
                    on_replace=replace,
                )


@pytest.mark.parametrize("partial_tool", [False, True])
def test_complete_length_recovers_once_without_replaying_truncated_parts(
    partial_tool: bool,
) -> None:
    async def run() -> None:
        journey = _RecoveryJourney([_truncated_frames(partial_tool=partial_tool), _answer_frames()])
        result = await journey.invoke()
        assert journey.shown == result.message == "## 优化方案\n\n先补充品牌资料。"
        assert [e for e in journey.events if e[0] == "replace"] == [("replace", "")]
        assert journey.java.calls == []
        assert journey.counters.tool_call_count == 0
        assert result.usage.model_call_count == len(journey.requests) == 2
        assert result.usage.provider_input_tokens == 18432 + 23
        assert result.usage.provider_output_tokens == 4096 + 11
        first, second = journey.requests
        assert first["thinking"] == {"type": "enabled"}
        assert second["thinking"] == {"type": "disabled"}
        assert first["max_tokens"] == second["max_tokens"] == 4096
        assert first["model"] == second["model"]
        assert first["tools"] == second["tools"]
        assert first["messages"] == second["messages"]
        assert "private-incomplete-reasoning" not in str(journey.events)
        assert '"region":' not in str(second["messages"])

    asyncio.run(run())


def test_length_recovery_keeps_non_thinking_mode_after_a_valid_tool_round() -> None:
    async def run() -> None:
        read_without_reasoning = [
            _frame(_delta({"tool_calls": [_call(name="context_read", call_id="fresh-read")]})),
            *_tail("tool_calls"),
        ]
        journey = _RecoveryJourney([_truncated_frames(), read_without_reasoning, _answer_frames()])
        result = await journey.invoke()
        assert journey.java.calls == ["context.read"]
        assert result.usage.model_call_count == 3
        assert result.usage.tool_call_count == 1
        assert [item["thinking"]["type"] for item in journey.requests] == [
            "enabled",
            "disabled",
            "disabled",
        ]
        assert journey.requests[-1]["messages"][-1]["role"] == "tool"
        assert journey.shown == result.message

    asyncio.run(run())


@pytest.mark.parametrize("sequence", ["length-length", "length-protocol", "protocol-length"])
def test_length_and_protocol_errors_share_one_recovery_allowance(sequence: str) -> None:
    async def run() -> None:
        frames = {"length": _truncated_frames, "protocol": _protocol_error_frames}
        journey = _RecoveryJourney([frames[item]() for item in sequence.split("-")])
        expected = (
            "DEEPSEEK_NATIVE_TOOL_CALL_INVALID"
            if sequence.endswith("protocol")
            else "DEEPSEEK_RESPONSE_TRUNCATED"
        )
        with pytest.raises(ModelGatewayError, match=expected) as captured:
            await journey.invoke()
        assert len(journey.requests) == journey.counters.model_call_count == 2
        assert journey.java.calls == []
        usage = cast(Usage, cast(object, captured.value.usage))
        assert usage.model_call_count == 2
        length_count = sequence.count("length")
        assert usage.provider_output_tokens == length_count * 4096 + (2 - length_count) * 11
        assert usage.provider_input_tokens == length_count * 18432 + (2 - length_count) * 23

    asyncio.run(run())


@pytest.mark.parametrize("boundary", ["model_calls", "steps", "deadline"])
def test_length_recovery_preserves_original_run_limits(boundary: str) -> None:
    async def run() -> None:
        req = request("给我一个优化方案")
        updates = {"max_model_calls": 1} if boundary == "model_calls" else {"max_steps": 5}
        req = req.model_copy(update={"budget": req.budget.model_copy(update=updates)})
        journey = _RecoveryJourney([_truncated_frames()], req=req)
        if boundary == "steps":
            for _ in range(4):
                journey.counters.before_workflow_step()
        if boundary == "deadline":

            def expire(_: int) -> None:
                req.budget.deadline_at = datetime.now(UTC) - timedelta(seconds=1)

            journey.before_reply = expire
        with pytest.raises(ModelGatewayError, match="DEEPSEEK_RESPONSE_TRUNCATED"):
            await journey.invoke()
        assert len(journey.requests) == journey.counters.model_call_count == 1
        assert journey.java.calls == []
        assert not any(e[0] == "replace" for e in journey.events)
        assert journey.counters.provider_output_tokens == 4096

    asyncio.run(run())


def test_cancellation_during_preview_reset_does_not_send_a_recovery_request() -> None:
    async def run() -> None:
        journey = _RecoveryJourney([_truncated_frames()])

        async def stop() -> None:
            raise asyncio.CancelledError

        journey.on_reset = stop
        with pytest.raises(asyncio.CancelledError):
            await journey.invoke()
        assert len(journey.requests) == 1
        assert journey.java.calls == []
        assert journey.counters.provider_output_tokens == 4096

    asyncio.run(run())


@pytest.mark.parametrize("failure", ["length_without_done", "interrupted", "malformed"])
def test_only_complete_valid_length_is_recoverable(failure: str) -> None:
    async def run() -> None:
        partial = _frame(_delta({"content": "已生成的部分回答"}))
        scripts = {
            "length_without_done": _truncated_frames()[:-1],
            "interrupted": [partial],
            "malformed": [partial, _frame({"choices": "not-an-array"})],
        }
        journey = _RecoveryJourney([scripts[failure]])
        with pytest.raises(ModelGatewayError):
            await journey.invoke()
        assert len(journey.requests) == 1
        assert journey.java.calls == []

    asyncio.run(run())


@pytest.mark.parametrize("action", ["context_read", "monitor_receipt", "unknown_monitor"])
def test_executed_tools_are_never_replayed_after_truncation(action: str) -> None:
    async def run() -> None:
        call = (
            monitor() if action != "context_read" else ModelToolCall("read", "context_read", "{}")
        )
        first = [
            _frame(_delta({"reasoning_content": "private-internal"})),
            _frame(_delta({"tool_calls": [_call(name=call.name, arguments=call.arguments)]})),
            *_tail("tool_calls"),
        ]
        journey = _RecoveryJourney(
            [first, _truncated_frames()],
            req=request("现在开始一次监测，范围广州"),
            unknown_monitor=action == "unknown_monitor",
        )
        if action == "context_read":
            with pytest.raises(ModelGatewayError, match="DEEPSEEK_RESPONSE_TRUNCATED"):
                await journey.invoke()
            assert journey.java.calls == ["context.read"]
        else:
            result = await journey.invoke()
            assert journey.java.calls == ["geo.monitor"]
            assert result.business_outcome is not None
            assert result.business_outcome.status.value == "PARTIAL"
            assert "这段残稿" not in result.message
        assert len(journey.requests) == journey.counters.model_call_count == 2
        assert journey.counters.tool_call_count == 1
        assert all(item["thinking"] == {"type": "enabled"} for item in journey.requests)

    asyncio.run(run())


def test_non_streaming_native_entry_uses_the_same_bounded_recovery() -> None:
    async def run() -> None:
        req = request("基于已有资料继续分析，不要启动监测")
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        java = JavaRecorder({})
        requests: list[dict[str, Any]] = []

        def respond(http_request: httpx.Request) -> httpx.Response:
            requests.append(json.loads(http_request.content))
            assert len(requests) <= 2
            first = len(requests) == 1
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "finish_reason": "length" if first else "stop",
                            "message": {
                                "role": "assistant",
                                "content": "残稿" if first else "完整优化方案。",
                                **({"reasoning_content": "private-reasoning"} if first else {}),
                            },
                        }
                    ],
                    "usage": {"prompt_tokens": 23, "completion_tokens": 4096 if first else 11},
                },
            )

        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(respond)
        ) as client:
            gateway = DeepSeekAdapter(
                ProviderSettings(credential=SecretStr("test-only"), model="synthetic-model"),
                client=client,
            )
            with (
                bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
                bind_runtime_usage(counters),
            ):
                result = await GeoAgentLoop(
                    gateway, cast(JavaAgentActionClientProtocol, java), "test"
                ).ainvoke(req, authorized_context=context(), usage_counters=counters)
        assert result.message == "完整优化方案。"
        assert result.usage.model_call_count == 2
        assert result.usage.provider_output_tokens == 4096 + 11
        assert requests[0]["messages"] == requests[1]["messages"]
        assert requests[0]["thinking"] == {"type": "enabled"}
        assert requests[1]["thinking"] == {"type": "disabled"}
        assert all(item["stream"] is False for item in requests)
        assert java.calls == []

    asyncio.run(run())
