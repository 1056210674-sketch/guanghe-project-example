"""Native SSE functional boundaries against the real adapter, with no provider fees."""

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from pydantic import SecretStr

from app.common.settings import AppSettings, ProviderSettings
from app.harness.runtime import bind_model_call_boundary
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import (
    NATIVE_TOOL_PROTOCOL_FAILURE_STAGES,
    ModelGatewayError,
    ModelMessage,
    ModelMessages,
    ModelToolDefinition,
    ModelTurnResult,
    NativeStreamingCompletionGateway,
    NativeToolCompletionGateway,
)
from app.observability.logging import JsonFormatter
from app.observability.tracing import configure_tracing, shutdown_tracing

_TOOLS = (
    ModelToolDefinition("context_read", "Read authorized context", {"type": "object"}),
    ModelToolDefinition("memory_read", "Read authorized memory", {"type": "object"}),
)
_MESSAGES: ModelMessages = (
    ModelMessage(role="system", content="Use the authorized tools."),
    ModelMessage(role="user", content="Help me understand the report."),
)
_USAGE = {"prompt_tokens": 23, "completion_tokens": 11}


def _frame(payload: dict[str, Any] | str) -> bytes:
    data = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False)
    return ("data: " + data + "\n\n").encode()


def _delta(delta: dict[str, Any], *, finish: str | None = None, index: int = 0) -> dict[str, Any]:
    return {
        "id": "stream-id",
        "choices": [{"index": index, "delta": delta, "finish_reason": finish}],
    }


def _call(
    index: int = 0, *, name: str = "context_read", call_id: str = "call-0", arguments: str = "{}"
) -> dict[str, Any]:
    return {
        "index": index,
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": arguments},
    }


def _tail(finish: str = "stop", *, usage: dict[str, Any] | None = _USAGE) -> list[bytes]:
    frames = [_frame(_delta({}, finish=finish))]
    if usage is not None:
        frames.append(_frame({"choices": [], "usage": usage}))
    return [*frames, _frame("[DONE]")]


class _Chunks(httpx.AsyncByteStream):
    def __init__(self, chunks: list[bytes], error: Exception | None = None) -> None:
        self.chunks = chunks
        self.error = error
        self.closed = False

    async def __aiter__(self) -> AsyncIterator[bytes]:
        for chunk in self.chunks:
            yield chunk
        if self.error is not None:
            raise self.error

    async def aclose(self) -> None:
        self.closed = True


def _adapter(client: httpx.AsyncClient) -> DeepSeekAdapter:
    return DeepSeekAdapter(
        ProviderSettings(
            credential=SecretStr("credential-secret"), model="synthetic-model", max_retries=2
        ),
        client=client,
    )


async def _complete(
    stream: httpx.AsyncByteStream,
    deltas: list[str],
    requests: list[httpx.Request],
    *,
    thinking: bool = True,
    tools: tuple[ModelToolDefinition, ...] = _TOOLS,
) -> ModelTurnResult:
    def handle(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, stream=stream)

    async def collect(text: str) -> None:
        deltas.append(text)

    async with httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handle)
    ) as client:
        adapter = _adapter(client)
        assert isinstance(adapter, NativeToolCompletionGateway)
        assert isinstance(adapter, NativeStreamingCompletionGateway)
        return await adapter.complete_turn_stream(
            _MESSAGES, tools=tools, thinking=thinking, on_delta=collect
        )


def test_public_delta_arrives_before_stream_eof_and_reasoning_stays_private() -> None:
    async def scenario() -> None:
        visible = asyncio.Event()
        release = asyncio.Event()
        deltas: list[str] = []
        requests: list[dict[str, Any]] = []

        class PausedStream(httpx.AsyncByteStream):
            async def __aiter__(self) -> AsyncIterator[bytes]:
                yield _frame(_delta({"reasoning_content": "reasoning-secret"}))
                # Split inside a multi-byte Chinese character and an SSE data line.
                data = _frame(_delta({"content": "第一段"}))
                split = data.index("第".encode()) + 1
                yield data[:split]
                yield data[split:]
                await release.wait()
                yield _frame(_delta({"content": "，继续。"}))
                for frame in _tail():
                    yield frame

        def handle(request: httpx.Request) -> httpx.Response:
            requests.append(json.loads(request.content))
            return httpx.Response(200, stream=PausedStream())

        async def collect(text: str) -> None:
            deltas.append(text)
            visible.set()

        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handle)
        ) as client:
            pending = asyncio.create_task(
                _adapter(client).complete_turn_stream(_MESSAGES, tools=_TOOLS, on_delta=collect)
            )
            try:
                await asyncio.wait_for(visible.wait(), 2)
                assert not pending.done(), "The adapter must not buffer until EOF."
                assert deltas == ["第一段"]
            finally:
                release.set()
            result = await pending
        assert deltas == ["第一段", "，继续。"]
        assert result.message.content == "第一段，继续。"
        assert result.message.reasoning_content == "reasoning-secret"
        assert result.usage_reported
        assert (result.usage.input_tokens, result.usage.output_tokens) == (23, 11)
        assert result.usage.request_count == len(requests) == 1
        assert requests[0]["stream"] is True
        assert requests[0]["stream_options"] == {"include_usage": True}
        assert requests[0]["thinking"] == {"type": "enabled"}
        assert requests[0]["tool_choice"] == "auto"

    asyncio.run(scenario())


def test_interleaved_indexed_tool_fragments_are_assembled_without_public_leaks(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger="app.model_gateway.deepseek")
    deltas: list[str] = []
    requests: list[httpx.Request] = []
    frames = [
        _frame(_delta({"content": "正在查阅", "reasoning_content": "reasoning-"})),
        _frame(
            _delta(
                {
                    "content": "hidden-content-secret",
                    "reasoning_content": "secret",
                    "tool_calls": [
                        _call(1, name="memory_", call_id="call-1", arguments='{"q":"'),
                        _call(0, name="context_read", call_id="call-", arguments="{"),
                    ],
                }
            )
        ),
        _frame(
            _delta(
                {
                    "content": "more-hidden-secret",
                    "tool_calls": [
                        {"index": 0, "id": "0", "function": {"arguments": "}"}},
                        {
                            "index": 1,
                            "function": {"name": "read", "arguments": 'argument-secret"}'},
                        },
                    ],
                }
            )
        ),
        *_tail("tool_calls"),
    ]
    stream = _Chunks(frames)
    result = asyncio.run(_complete(stream, deltas, requests))
    assert stream.closed
    assert deltas == ["正在查阅"]
    assert [call.id for call in result.message.tool_calls] == ["call-0", "call-1"]
    assert [call.name for call in result.message.tool_calls] == ["context_read", "memory_read"]
    assert [call.arguments for call in result.message.tool_calls] == [
        "{}",
        '{"q":"argument-secret"}',
    ]
    assert result.message.reasoning_content == "reasoning-secret"
    assert result.finish_reason == "tool_calls"
    assert result.message.content == "正在查阅hidden-content-secretmore-hidden-secret"
    rendered = "\n".join(JsonFormatter().format(record) for record in caplog.records)
    assert "secret" not in rendered
    assert "reasoning-secret" not in repr(result.message)
    assert "argument-secret" not in repr(result.message.tool_calls)
    diagnostics = [r for r in caplog.records if r.getMessage() == "deepseek_completion_diagnostic"]
    assert len(diagnostics) == 1
    assert json.loads(JsonFormatter().format(diagnostics[0]))["toolCallCount"] == 2


def test_full_native_batch_is_validated_after_stream_not_partially_returned() -> None:
    deltas: list[str] = []
    requests: list[httpx.Request] = []
    stream = _Chunks(
        [
            _frame(_delta({"reasoning_content": ""})),
            _frame(
                _delta(
                    {
                        "tool_calls": [
                            _call(),
                            _call(1, name="unauthorized-secret", call_id="second"),
                        ]
                    }
                )
            ),
            *_tail("tool_calls"),
        ]
    )
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(stream, deltas, requests))
    assert captured.value.code == "DEEPSEEK_NATIVE_TOOL_CALL_INVALID"
    assert captured.value.failure_stage == "native_tool_unavailable"
    assert not captured.value.retryable
    assert captured.value.usage.request_count == len(requests) == 1
    assert deltas == []


@pytest.mark.parametrize(
    ("frames", "code"),
    [
        ([], "DEEPSEEK_STREAM_INCOMPLETE"),
        ([_frame("[DONE]")], "DEEPSEEK_STREAM_INCOMPLETE"),
        ([_frame(_delta({}, finish="stop"))], "DEEPSEEK_STREAM_INCOMPLETE"),
        (_tail("length"), "DEEPSEEK_RESPONSE_TRUNCATED"),
        (_tail("content_filter"), "DEEPSEEK_RESPONSE_REFUSED"),
        (_tail("aborted"), "DEEPSEEK_RESPONSE_INTERRUPTED"),
        (_tail("unexpected"), "DEEPSEEK_NATIVE_RESPONSE_INVALID"),
        ([_frame("{bad-json-secret")], "DEEPSEEK_STREAM_RESPONSE_INVALID"),
        ([_frame({"error": {"message": "provider-secret"}})], "DEEPSEEK_STREAM_RESPONSE_INVALID"),
        ([_frame(_delta({"content": 12}))], "DEEPSEEK_STREAM_RESPONSE_INVALID"),
        ([_frame(_delta({}, index=1))], "DEEPSEEK_STREAM_RESPONSE_INVALID"),
        (
            [_frame(_delta({}, finish="stop")), *_tail()],
            "DEEPSEEK_STREAM_RESPONSE_INVALID",
        ),
        (
            [_frame(_delta({}, finish="stop")), _frame(_delta({"content": "late-secret"}))],
            "DEEPSEEK_STREAM_RESPONSE_INVALID",
        ),
        (
            [_frame({"id": "different", "choices": []}), *_tail()],
            "DEEPSEEK_STREAM_RESPONSE_INVALID",
        ),
        (
            [
                _frame(_delta({"tool_calls": [{"index": 1, "function": {"arguments": "{}"}}]})),
                *_tail("tool_calls"),
            ],
            "DEEPSEEK_STREAM_RESPONSE_INVALID",
        ),
        (
            [_frame(_delta({"tool_calls": [_call(index=1)]})), *_tail("tool_calls")],
            "DEEPSEEK_STREAM_RESPONSE_INVALID",
        ),
        ([_frame(_delta({"tool_calls": [_call(index=-1)]}))], "DEEPSEEK_STREAM_RESPONSE_INVALID"),
        ([_frame(_delta({"tool_calls": [_call(index=128)]}))], "DEEPSEEK_STREAM_RESPONSE_INVALID"),
        (
            [_frame(_delta({"tool_calls": [{**_call(), "index": True}]}))],
            "DEEPSEEK_STREAM_RESPONSE_INVALID",
        ),
        (
            [_frame(_delta({"tool_calls": [{**_call(), "index": "0"}]}))],
            "DEEPSEEK_STREAM_RESPONSE_INVALID",
        ),
        (
            [_frame(_delta({"tool_calls": [_call()] * 129}))],
            "DEEPSEEK_STREAM_RESPONSE_INVALID",
        ),
        ([b"data: \xff\n\n"], "DEEPSEEK_STREAM_RESPONSE_INVALID"),
    ],
)
def test_invalid_or_incomplete_native_stream_never_succeeds_or_retries(
    frames: list[bytes], code: str
) -> None:
    requests: list[httpx.Request] = []
    deltas: list[str] = []
    stream = _Chunks([_frame(_delta({"content": "已到达", "reasoning_content": ""})), *frames])
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(stream, deltas, requests))
    assert captured.value.code == code
    assert not captured.value.retryable
    assert captured.value.failure_stage not in NATIVE_TOOL_PROTOCOL_FAILURE_STAGES
    assert len(requests) == captured.value.usage.request_count == 1
    assert stream.closed
    assert deltas == ["已到达"]


@pytest.mark.parametrize("complete", [True, False])
def test_length_stage_requires_done_and_never_returns_partial_calls(
    complete: bool, caplog: pytest.LogCaptureFixture
) -> None:
    requests: list[httpx.Request] = []
    deltas: list[str] = []
    caplog.set_level(logging.INFO, logger="app.model_gateway.deepseek")
    tail = _tail("length")
    stream = _Chunks(
        [
            _frame(_delta({"content": "临时正文", "reasoning_content": "reasoning-secret"})),
            _frame(_delta({"tool_calls": [_call(arguments='{"argument-secret":"')]})),
            *(tail if complete else tail[:-1]),
        ]
    )
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(stream, deltas, requests))
    error = captured.value
    assert error.code == "DEEPSEEK_RESPONSE_TRUNCATED"
    assert error.failure_stage == ("native_turn_truncated" if complete else "native_stream")
    assert error.failure_stage not in NATIVE_TOOL_PROTOCOL_FAILURE_STAGES
    assert not error.retryable
    assert error.usage.request_count == len(requests) == 1
    assert (error.usage.input_tokens, error.usage.output_tokens) == (23, 11)
    assert stream.closed
    assert deltas == ["临时正文"]
    rendered = "\n".join(JsonFormatter().format(record) for record in caplog.records)
    assert "secret" not in rendered
    assert "临时正文" not in rendered


@pytest.mark.parametrize("malformed", ["json", "multiple_choices", "post_finish_content"])
def test_length_marker_does_not_make_a_malformed_stream_recoverable(malformed: str) -> None:
    terminal = _delta({}, finish="length")
    terminal["usage"] = _USAGE
    if malformed == "json":
        frames = [_frame(terminal), _frame("{bad-json-secret")]
    elif malformed == "multiple_choices":
        terminal["choices"].append(_delta({}, finish="stop")["choices"][0])
        frames = [_frame(terminal)]
    else:
        frames = [_frame(terminal), _frame(_delta({"content": "late-content-secret"}))]
    requests: list[httpx.Request] = []
    stream = _Chunks([*frames, _frame("[DONE]")])
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(stream, [], requests))
    assert captured.value.code == "DEEPSEEK_STREAM_RESPONSE_INVALID"
    assert captured.value.failure_stage == "native_stream"
    assert not captured.value.retryable
    assert captured.value.usage.request_count == len(requests) == 1
    assert stream.closed


@pytest.mark.parametrize(
    "field", ["content", "reasoning_content", "arguments", "id", "name", "line"]
)
def test_native_stream_fragment_accumulation_is_bounded(field: str) -> None:
    if field in {"content", "reasoning_content"}:
        frames = [_frame(_delta({field: "x" * 600_000}))] * 2
    elif field == "arguments":
        frames = [
            _frame(_delta({"tool_calls": [_call(arguments="x" * 150_000)]})),
            _frame(
                _delta({"tool_calls": [{"index": 0, "function": {"arguments": "x" * 150_000}}]})
            ),
        ]
    elif field == "line":
        frames = [b"data: " + b"x" * 2_200_000, b"x" * 2_200_000]
    else:
        limit = 256 if field == "id" else 128
        fragment = (
            {"index": 0, "id": "x" * limit}
            if field == "id"
            else {"index": 0, "function": {"name": "x" * limit}}
        )
        frames = [_frame(_delta({"tool_calls": [fragment]}))] * 2
    requests: list[httpx.Request] = []
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(_Chunks(frames), [], requests))
    assert captured.value.code == "DEEPSEEK_STREAM_RESPONSE_TOO_LARGE"
    assert not captured.value.retryable
    assert len(requests) == 1


def test_missing_usage_is_unknown_and_repeated_usage_is_not_double_counted() -> None:
    for usage, expected, reported in [(None, (0, 0), False), (_USAGE, (23, 11), True)]:
        requests: list[httpx.Request] = []
        frames = [_frame(_delta({"content": "回答", "reasoning_content": ""}))]
        if usage is not None:
            frames.append(_frame({"choices": [], "usage": usage}))
        result = asyncio.run(_complete(_Chunks([*frames, *_tail(usage=usage)]), [], requests))
        assert (result.usage.input_tokens, result.usage.output_tokens) == expected
        assert result.usage_reported is reported
        assert result.usage.request_count == 1


@pytest.mark.parametrize(
    "usage",
    [
        {"prompt_tokens": -1},
        {"prompt_tokens": True},
        {"prompt_tokens": 20, "completion_tokens": 10},
    ],
)
def test_invalid_or_regressing_usage_fails_with_last_valid_telemetry(usage: dict[str, Any]) -> None:
    frames = [
        _frame(_delta({"content": "回答", "reasoning_content": ""})),
        _frame({"choices": [], "usage": _USAGE}),
        _frame({"choices": [], "usage": usage}),
        *_tail(),
    ]
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(_Chunks(frames), [], []))
    assert captured.value.code == "DEEPSEEK_STREAM_RESPONSE_INVALID"
    assert captured.value.usage.input_tokens == 23
    assert captured.value.usage.output_tokens == 11


@pytest.mark.parametrize(
    "error", [httpx.ReadError("private network error"), httpx.ReadTimeout("private timeout")]
)
def test_network_failure_after_delta_keeps_usage_but_never_replays(error: Exception) -> None:
    async def scenario() -> None:
        requests: list[httpx.Request] = []
        deltas: list[str] = []
        with bind_model_call_boundary(
            max_calls=3, deadline_at=datetime.now(UTC) + timedelta(seconds=10)
        ) as boundary:
            with pytest.raises(ModelGatewayError) as captured:
                await _complete(
                    _Chunks(
                        [
                            _frame(_delta({"content": "部分文字", "reasoning_content": ""})),
                            _frame({"choices": [], "usage": _USAGE}),
                        ],
                        error,
                    ),
                    deltas,
                    requests,
                )
            assert captured.value.code == "DEEPSEEK_SUBMISSION_UNKNOWN"
            assert not captured.value.retryable
            assert captured.value.usage.input_tokens == 23
            assert captured.value.usage.output_tokens == 11
            assert boundary.observed_usage.input_tokens == 23
            assert boundary.observed_usage.output_tokens == 11
            assert boundary.call_count == len(requests) == 1
            assert deltas == ["部分文字"]

    asyncio.run(scenario())


def test_streaming_keeps_original_model_call_budget() -> None:
    async def scenario() -> None:
        requests: list[httpx.Request] = []
        with bind_model_call_boundary(
            max_calls=1, deadline_at=datetime.now(UTC) + timedelta(seconds=10)
        ) as boundary:
            first = await _complete(
                _Chunks([_frame(_delta({"content": "回答", "reasoning_content": ""})), *_tail()]),
                [],
                requests,
            )
            assert first.usage.request_count == 1
            with pytest.raises(ModelGatewayError) as captured:
                await _complete(_Chunks([]), [], requests)
            assert captured.value.code == "MODEL_CALL_BUDGET_EXCEEDED"
            assert captured.value.usage.request_count == 0
            assert boundary.call_count == len(requests) == 1
            assert boundary.observed_usage.input_tokens == 23

    asyncio.run(scenario())


def test_no_thinking_no_tools_stream_and_missing_required_reasoning() -> None:
    requests: list[httpx.Request] = []
    frames = [_frame(_delta({"content": "回答"})), *_tail()]
    result = asyncio.run(_complete(_Chunks(frames), [], requests, thinking=False, tools=()))
    assert result.message.content == "回答"
    assert result.message.reasoning_content is None
    payload = json.loads(requests[0].content)
    assert payload["thinking"] == {"type": "disabled"}
    assert "tools" not in payload
    assert "tool_choice" not in payload
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(_Chunks(frames), [], []))
    assert captured.value.code == "DEEPSEEK_REASONING_CONTENT_MISSING"
    assert captured.value.failure_stage not in NATIVE_TOOL_PROTOCOL_FAILURE_STAGES


def test_reasoning_is_replayed_in_next_model_round_but_never_in_logs_or_traces(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger="app.model_gateway.deepseek")
    exporter = InMemorySpanExporter()
    runtime = configure_tracing(
        AppSettings(environment="test", otel_enabled=True),
        process_role="api",
        span_exporter=exporter,
        synchronous_export=True,
    )

    async def scenario() -> None:
        requests: list[dict[str, Any]] = []
        deltas: list[str] = []

        def handle(request: httpx.Request) -> httpx.Response:
            requests.append(json.loads(request.content))
            if len(requests) == 1:
                frames = [
                    _frame(_delta({"reasoning_content": "private-reasoning-secret"})),
                    _frame(_delta({"tool_calls": [_call(arguments='{"q":"argument-secret"}')]})),
                    *_tail("tool_calls"),
                ]
            else:
                assert (
                    requests[-1]["messages"][-2]["reasoning_content"] == "private-reasoning-secret"
                )
                assert requests[-1]["messages"][-2]["tool_calls"][0]["id"] == "call-0"
                assert requests[-1]["messages"][-1]["tool_call_id"] == "call-0"
                final = _delta(
                    {"content": "结果已经整理好。", "reasoning_content": ""}, finish="stop"
                )
                final["usage"] = _USAGE
                frames = [_frame(final), _frame("[DONE]")]
            return httpx.Response(200, stream=_Chunks(frames))

        async def collect(text: str) -> None:
            deltas.append(text)

        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handle)
        ) as client:
            adapter = _adapter(client)
            first = await adapter.complete_turn_stream(_MESSAGES, tools=_TOOLS, on_delta=collect)
            assert deltas == []
            second = await adapter.complete_turn_stream(
                (
                    *_MESSAGES,
                    first.message,
                    ModelMessage(role="tool", content="{}", tool_call_id="call-0"),
                ),
                tools=_TOOLS,
                on_delta=collect,
            )
            assert deltas == ["结果已经整理好。"]
            assert second.usage_reported
            assert second.usage.input_tokens == 23
            assert second.usage.output_tokens == 11

    try:
        asyncio.run(scenario())
        spans = exporter.get_finished_spans()
        assert len(spans) == 2
        serialized_spans = "\n".join(span.to_json() for span in spans)
        serialized_logs = "\n".join(JsonFormatter().format(record) for record in caplog.records)
        assert "secret" not in serialized_logs + serialized_spans
    finally:
        shutdown_tracing(runtime)


def test_cancelled_stream_closes_transport_and_records_observed_usage_once() -> None:
    async def scenario() -> None:
        waiting = asyncio.Event()
        requests: list[httpx.Request] = []

        class PausedStream(_Chunks):
            async def __aiter__(self) -> AsyncIterator[bytes]:
                yield _frame(_delta({"reasoning_content": ""}))
                yield _frame({"choices": [], "usage": _USAGE})
                waiting.set()
                await asyncio.Event().wait()

        stream = PausedStream([])
        with bind_model_call_boundary(
            max_calls=3, deadline_at=datetime.now(UTC) + timedelta(seconds=10)
        ) as boundary:
            pending = asyncio.create_task(_complete(stream, [], requests))
            await asyncio.wait_for(waiting.wait(), 2)
            pending.cancel()
            with pytest.raises(asyncio.CancelledError):
                await pending
            assert stream.closed
            assert boundary.call_count == len(requests) == 1
            assert boundary.observed_usage.input_tokens == 23
            assert boundary.observed_usage.output_tokens == 11

    asyncio.run(scenario())
