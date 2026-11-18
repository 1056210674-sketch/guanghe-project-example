"""Real native adapter boundaries with synthetic HTTP responses and no paid calls."""

import asyncio
import json
import logging
from typing import Any

import httpx
import pytest
from pydantic import SecretStr

from app.common.settings import ProviderSettings
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import (
    NATIVE_TOOL_PROTOCOL_FAILURE_STAGES,
    ModelGatewayError,
    ModelMessage,
    ModelMessages,
    ModelToolCall,
    ModelToolDefinition,
)
from app.observability.logging import JsonFormatter

_TOOLS = (
    ModelToolDefinition("context_read", "Read authorized context", {"type": "object"}),
    ModelToolDefinition("memory_read", "Read authorized memory", {"type": "object"}),
)
_MESSAGES: ModelMessages = (
    ModelMessage(role="system", content="Use only the supplied tools."),
    ModelMessage(role="user", content="Yes, those facts are accurate."),
)
_HISTORY: ModelMessages = (
    *_MESSAGES,
    ModelMessage(
        role="assistant",
        content="",
        reasoning_content="",
        tool_calls=(ModelToolCall("prior-call", "context_read", "{}"),),
    ),
    ModelMessage(role="tool", content="{}", tool_call_id="prior-call"),
)


def _call(call_id: str | None = "new-call", name: str = "context_read") -> dict[str, Any]:
    return {
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": '{"private":"argument-secret"}'},
    }


def _completion(
    calls: list[dict[str, Any]], *, finish_reason: str = "tool_calls"
) -> dict[str, Any]:
    return {
        "choices": [
            {
                "message": {
                    "content": "content-secret",
                    "reasoning_content": "reasoning-secret",
                    "tool_calls": calls,
                },
                "finish_reason": finish_reason,
            }
        ],
        "usage": {"prompt_tokens": 23, "completion_tokens": 11},
    }


async def _complete(
    payload: dict[str, Any], requests: list[httpx.Request], messages: ModelMessages = _MESSAGES
) -> Any:
    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json=payload)

    async with httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    ) as client:
        adapter = DeepSeekAdapter(
            ProviderSettings(
                credential=SecretStr("credential-secret"),
                model="synthetic-model",
                # Even a positive transport retry limit must not replay complete responses.
                max_retries=2,
            ),
            client=client,
        )
        return await adapter.complete_turn(messages, tools=_TOOLS, thinking=True)


@pytest.mark.parametrize(
    ("calls", "stage", "messages"),
    [
        ([], "native_tool_calls_missing", _MESSAGES),
        (
            [{key: value for key, value in _call().items() if key != "id"}],
            "native_tool_call_id_invalid",
            _MESSAGES,
        ),
        ([_call(None)], "native_tool_call_id_invalid", _MESSAGES),
        ([_call("")], "native_tool_call_id_invalid", _MESSAGES),
        ([_call("invalid id")], "native_tool_call_id_invalid", _MESSAGES),
        ([_call("非ascii")], "native_tool_call_id_invalid", _MESSAGES),
        ([_call("x" * 257)], "native_tool_call_id_invalid", _MESSAGES),
        (
            [_call("duplicate-secret"), _call("duplicate-secret", "memory_read")],
            "native_tool_call_id_duplicate",
            _MESSAGES,
        ),
        ([_call("prior-call")], "native_tool_call_id_duplicate", _HISTORY),
        (
            [_call("valid-first"), _call("private-id", "unavailable-private-function")],
            "native_tool_unavailable",
            _MESSAGES,
        ),
        (
            [_call(f"call-{index}") for index in range(129)],
            "native_tool_call_limit_exceeded",
            _MESSAGES,
        ),
    ],
)
def test_invalid_native_batch_is_rejected_once_with_safe_stage_and_usage(
    calls: list[dict[str, Any]],
    stage: str,
    messages: ModelMessages,
    caplog: pytest.LogCaptureFixture,
) -> None:
    requests: list[httpx.Request] = []
    caplog.set_level(logging.INFO, logger="app.model_gateway.deepseek")
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(_completion(calls), requests, messages))

    error = captured.value
    assert error.code == "DEEPSEEK_NATIVE_TOOL_CALL_INVALID"
    assert error.failure_stage == stage
    assert error.failure_stage in NATIVE_TOOL_PROTOCOL_FAILURE_STAGES
    assert not error.retryable
    assert len(requests) == error.usage.request_count == 1
    assert error.usage.input_tokens == 23
    assert error.usage.output_tokens == 11
    rejections = [r for r in caplog.records if r.getMessage() == "deepseek_native_turn_rejected"]
    assert len(rejections) == 1
    diagnosis = json.loads(JsonFormatter().format(rejections[0]))
    assert diagnosis["failureStage"] == stage
    assert diagnosis["toolCallCount"] == len(calls)
    assert diagnosis["stableErrorCode"] == error.code
    rendered = "\n".join(JsonFormatter().format(record) for record in caplog.records)
    assert "secret" not in rendered
    assert "unavailable-private-function" not in rendered
    assert "private-id" not in rendered
    assert "toolName" not in diagnosis


def test_valid_native_batch_preserves_order_and_reasoning_without_logging_content(
    caplog: pytest.LogCaptureFixture,
) -> None:
    requests: list[httpx.Request] = []
    caplog.set_level(logging.INFO, logger="app.model_gateway.deepseek")
    result = asyncio.run(
        _complete(_completion([_call("first"), _call("second", "memory_read")]), requests)
    )
    assert [call.id for call in result.message.tool_calls] == ["first", "second"]
    assert [call.name for call in result.message.tool_calls] == ["context_read", "memory_read"]
    assert result.message.reasoning_content == "reasoning-secret"
    assert result.usage.request_count == len(requests) == 1
    assert result.usage_reported
    rendered = "\n".join(JsonFormatter().format(record) for record in caplog.records)
    assert "secret" not in rendered
    assert "deepseek_native_turn_rejected" not in rendered


@pytest.mark.parametrize("finish_reason", ["length", "content_filter", "aborted", "unknown"])
def test_other_native_response_failures_are_not_eligible_for_protocol_redecision(
    finish_reason: str,
) -> None:
    requests: list[httpx.Request] = []
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(_completion([], finish_reason=finish_reason), requests))
    assert captured.value.failure_stage not in NATIVE_TOOL_PROTOCOL_FAILURE_STAGES
    assert captured.value.code != "DEEPSEEK_NATIVE_TOOL_CALL_INVALID"
    assert len(requests) == captured.value.usage.request_count == 1


def test_missing_reasoning_is_not_classified_as_safe_tool_protocol_redecision() -> None:
    payload = _completion([_call()])
    del payload["choices"][0]["message"]["reasoning_content"]
    requests: list[httpx.Request] = []
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(payload, requests))
    assert captured.value.code == "DEEPSEEK_REASONING_CONTENT_MISSING"
    assert captured.value.failure_stage not in NATIVE_TOOL_PROTOCOL_FAILURE_STAGES
    assert len(requests) == 1


def test_complete_length_response_is_identified_without_returning_partial_tools(
    caplog: pytest.LogCaptureFixture,
) -> None:
    payload = _completion([_call()], finish_reason="length")
    payload["choices"][0]["message"]["tool_calls"][0]["function"]["arguments"] = '{"partial":"'
    requests: list[httpx.Request] = []
    caplog.set_level(logging.INFO, logger="app.model_gateway.deepseek")
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(payload, requests))
    error = captured.value
    assert error.code == "DEEPSEEK_RESPONSE_TRUNCATED"
    assert error.failure_stage == "native_turn_truncated"
    assert error.failure_stage not in NATIVE_TOOL_PROTOCOL_FAILURE_STAGES
    assert not error.retryable
    assert error.usage.request_count == len(requests) == 1
    assert (error.usage.input_tokens, error.usage.output_tokens) == (23, 11)
    rendered = "\n".join(JsonFormatter().format(record) for record in caplog.records)
    assert "secret" not in rendered
    assert "partial" not in rendered


@pytest.mark.parametrize("first_finish", ["length", "stop", "content_filter"])
def test_multiple_choices_never_authorize_truncation_redecision(first_finish: str) -> None:
    payload = _completion([], finish_reason=first_finish)
    payload["choices"].append(_completion([_call()])["choices"][0])
    requests: list[httpx.Request] = []
    with pytest.raises(ModelGatewayError) as captured:
        asyncio.run(_complete(payload, requests))
    assert captured.value.code == "DEEPSEEK_NATIVE_RESPONSE_INVALID"
    assert captured.value.failure_stage == "native_turn"
    assert not captured.value.retryable
    assert captured.value.usage.request_count == len(requests) == 1
