"""Functional BODY/REWRITE schema repair through the existing gateway and kernel."""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest
from pydantic import BaseModel, ConfigDict

from app.article.models import ArticleStage
from app.article.runtime import ArticleExecutionError, ArticleExecutionKernel
from app.harness.runtime import bind_model_call_boundary, estimate_model_messages
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import ModelGatewayError, ModelMessage, serialize_messages
from app.observability.logging import JsonFormatter
from tests.unit.test_article_execution import (
    _article_messages_for_budget,  # pyright: ignore[reportPrivateUsage]
    _budget,  # pyright: ignore[reportPrivateUsage]
    _completion_response,  # pyright: ignore[reportPrivateUsage]
    _deepseek_settings,  # pyright: ignore[reportPrivateUsage]
    _prompts,  # pyright: ignore[reportPrivateUsage]
    _request,  # pyright: ignore[reportPrivateUsage]
)

_SCHEMA_MARKER = "[ARTICLE BLOCK OUTPUT JSON SCHEMA]"
_FEEDBACK_MARKER = "[SAFE SCHEMA VALIDATION FEEDBACK]"
_SECRET_KEY = "provider-private-field-do-not-replay"
_SECRET_VALUE = "provider-private-value-do-not-replay"
_OLD_TEXT = "这段只属于校验失败的草稿。"
_NEW_TEXT = "先明确使用场景，再核对已确认的资料。"


def _payload(mode: ArticleStage, *, invalid: bool = False) -> dict[str, Any]:
    blocks: list[dict[str, Any]] = [
        {"key": "section-1", "heading": "选择标准", "markdown": _NEW_TEXT},
        {"key": "section-2", "heading": "使用建议", "markdown": "根据实际需求审慎选择。"},
    ]
    if mode is ArticleStage.REWRITE:
        blocks = blocks[1:]
    if invalid:
        blocks[0]["markdown"] = _OLD_TEXT
        blocks[0][_SECRET_KEY] = {"input": _SECRET_VALUE}
    return {"blocks" if mode is ArticleStage.BODY else "replacements": blocks, "warnings": []}


class _Stream(httpx.AsyncByteStream):
    def __init__(self, content: str, *, incomplete: bool = False) -> None:
        self.content = content
        self.incomplete = incomplete

    async def __aiter__(self) -> AsyncIterator[bytes]:
        for part in (
            self.content[: len(self.content) // 2],
            self.content[len(self.content) // 2 :],
        ):
            # Exercise the production preview throttle with real incremental arrivals.
            await asyncio.sleep(0.51)
            yield (
                "data: "
                + json.dumps({"choices": [{"delta": {"content": part}, "finish_reason": None}]})
                + "\n\n"
            ).encode()
        if self.incomplete:
            return
        yield b'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n'
        yield b'data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":3}}\n\n'
        yield b"data: [DONE]\n\n"


def _response(content: str, *, streaming: bool, incomplete: bool = False) -> httpx.Response:
    if streaming:
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            stream=_Stream(content, incomplete=incomplete),
        )
    return _completion_response(content)


@pytest.mark.parametrize(
    ("mode", "streaming"),
    [(ArticleStage.BODY, False), (ArticleStage.BODY, True), (ArticleStage.REWRITE, False)],
)
def test_extra_block_field_gets_one_safe_repair_and_preserves_authorized_material(
    mode: ArticleStage, streaming: bool, caplog: pytest.LogCaptureFixture
) -> None:
    requests: list[dict[str, Any]] = []
    previews: list[tuple[int, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        content = json.dumps(_payload(mode, invalid=len(requests) == 1), ensure_ascii=False)
        return _response(content, streaming=streaming)

    async def preview(attempt: int, markdown: str) -> None:
        previews.append((attempt, markdown))

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            result = await ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(max_retries=3), client=client), _prompts()
            ).execute(_request(mode), on_preview=preview if streaming else None)
            assert result.body is not None
            assert _OLD_TEXT not in result.body.markdown
            assert result.usage.model_call_count == 2
            assert result.usage.input_tokens == 22
            assert result.usage.output_tokens == 6
            if streaming:
                assert any(attempt == 1 and markdown for attempt, markdown in previews)
                assert (2, "") in previews  # The old attempt is cleared, not concatenated.
                assert previews[-1] == (2, result.body.markdown)

    with caplog.at_level(logging.INFO, logger="app.model_gateway.deepseek"):
        asyncio.run(scenario())
    assert len(requests) == 2
    first, repaired = requests
    assert repaired["messages"][1:] == first["messages"][1:]
    assert repaired["messages"][0]["content"].startswith(first["messages"][0]["content"])
    assert first["thinking"] == repaired["thinking"] == {"type": "disabled"}
    assert first["max_tokens"] == repaired["max_tokens"] == 2048
    assert first["response_format"] == repaired["response_format"] == {"type": "json_object"}
    first_system = first["messages"][0]["content"]
    schema = json.loads(first_system.rsplit("\n", 1)[1])
    assert schema["additionalProperties"] is False
    block_schema = schema["$defs"]["ArticleBodyBlock"]
    assert block_schema["additionalProperties"] is False
    assert set(block_schema["properties"]) == {"key", "heading", "markdown"}
    assert "do not copy their sequence, level or brief" in first_system
    repaired_system = repaired["messages"][0]["content"]
    assert repaired_system.count(_SCHEMA_MARKER) == 1
    assert _FEEDBACK_MARKER in repaired_system
    prefix = "blocks" if mode is ArticleStage.BODY else "replacements"
    assert f'"path":"{prefix}.[].<unknown>","type":"extra_forbidden"' in repaired_system
    logs = json.dumps([json.loads(JsonFormatter().format(record)) for record in caplog.records])
    for private in (_SECRET_KEY, _SECRET_VALUE, _OLD_TEXT):
        assert private not in json.dumps(repaired, ensure_ascii=False)
        assert private not in logs


@pytest.mark.parametrize("streaming", [False, True])
def test_repeated_extra_fields_fail_strictly_after_two_requests(streaming: bool) -> None:
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return _response(json.dumps(_payload(ArticleStage.BODY, invalid=True)), streaming=streaming)

    async def preview(attempt: int, markdown: str) -> None:
        del attempt, markdown

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            with pytest.raises(ArticleExecutionError) as captured:
                await ArticleExecutionKernel(
                    DeepSeekAdapter(_deepseek_settings(max_retries=3), client=client), _prompts()
                ).execute(_request(ArticleStage.BODY), on_preview=preview if streaming else None)
            assert captured.value.code == "DEEPSEEK_ARTICLE_BODY_RESPONSE_INVALID"
            assert captured.value.usage.request_count == 2
            assert captured.value.usage.input_tokens == 22
            assert captured.value.usage.output_tokens == 6

    asyncio.run(scenario())
    assert len(requests) == 2


@pytest.mark.parametrize("streaming", [False, True])
def test_schema_bytes_are_counted_before_first_http(streaming: bool) -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return _response(json.dumps(_payload(ArticleStage.BODY)), streaming=streaming)

    async def preview(attempt: int, markdown: str) -> None:
        del attempt, markdown

    request = _request(ArticleStage.BODY)
    original = _article_messages_for_budget(request)
    request = request.model_copy(
        update={"budget": _budget(max_input_tokens=estimate_model_messages(original) + 20)}
    )

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            with pytest.raises(ArticleExecutionError) as captured:
                await ArticleExecutionKernel(
                    DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
                ).execute(request, on_preview=preview if streaming else None)
            assert captured.value.code == "ARTICLE_INPUT_TOKEN_BUDGET_EXCEEDED"
            assert captured.value.usage.request_count == 0

    asyncio.run(scenario())
    assert requests == []


@pytest.mark.parametrize("streaming", [False, True])
def test_schema_repair_feedback_cannot_exceed_real_input_budget(streaming: bool) -> None:
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return _response(json.dumps(_payload(ArticleStage.BODY, invalid=True)), streaming=streaming)

    async def preview(attempt: int, markdown: str) -> None:
        del attempt, markdown

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            kernel = ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
            )
            # Capture the real first request, including its generated schema.
            with pytest.raises(
                ArticleExecutionError, match="DEEPSEEK_ARTICLE_BODY_RESPONSE_INVALID"
            ):
                await kernel.execute(
                    _request(
                        ArticleStage.BODY, budget=_budget(max_model_calls=1, max_repair_retries=0)
                    ),
                    on_preview=preview if streaming else None,
                )
            sent = tuple(ModelMessage(**item) for item in requests[0]["messages"])
            requests.clear()
            with pytest.raises(ArticleExecutionError) as captured:
                await kernel.execute(
                    _request(
                        ArticleStage.BODY,
                        budget=_budget(max_input_tokens=estimate_model_messages(sent) + 20),
                    ),
                    on_preview=preview if streaming else None,
                )
            assert captured.value.code == "ARTICLE_INPUT_TOKEN_BUDGET_EXCEEDED"
            assert captured.value.usage.request_count == 1
            assert captured.value.usage.input_tokens == 11
            assert captured.value.usage.output_tokens == 3

    asyncio.run(scenario())
    assert len(requests) == 1


def test_incomplete_stream_is_not_repaired_or_accepted() -> None:
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return _response(json.dumps(_payload(ArticleStage.BODY)), streaming=True, incomplete=True)

    async def preview(attempt: int, markdown: str) -> None:
        del attempt, markdown

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            with pytest.raises(ArticleExecutionError, match="DEEPSEEK_STREAM_INCOMPLETE"):
                await ArticleExecutionKernel(
                    DeepSeekAdapter(_deepseek_settings(max_retries=3), client=client), _prompts()
                ).execute(_request(ArticleStage.BODY), on_preview=preview)

    asyncio.run(scenario())
    assert len(requests) == 1


class _OtherPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")
    result: str


@pytest.mark.parametrize("operation", ["article_title", "article_outline", "unrelated_json"])
def test_other_operations_keep_exact_original_messages_and_repair(operation: str) -> None:
    requests: list[dict[str, Any]] = []
    messages = (
        ModelMessage(role="system", content="Original factual rules."),
        ModelMessage(role="user", content="Original authorized material."),
    )

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        content = '{"result":"fine","extra":true}' if len(requests) == 1 else '{"result":"fine"}'
        return _completion_response(content)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            parsed, _ = await DeepSeekAdapter(
                _deepseek_settings(), client=client
            ).complete_structured(
                messages,
                _OtherPayload,
                operation=operation,
                max_tokens=100,
                error_code="OTHER_INVALID",
                repair_instruction="Original repair instruction.",
            )
            assert parsed.result == "fine"

    asyncio.run(scenario())
    assert requests[0]["messages"] == serialize_messages(messages)
    expected = (
        "Original factual rules.\n\n[STRUCTURED OUTPUT REPAIR]\nOriginal repair instruction."
        if operation.startswith("article_")
        else "Original repair instruction."
    )
    assert requests[1]["messages"] == [
        {"role": "system", "content": expected},
        {"role": "user", "content": "Original authorized material."},
    ]
    assert _SCHEMA_MARKER not in json.dumps(requests)
    assert _FEEDBACK_MARKER not in json.dumps(requests)


def test_one_call_budget_still_prevents_schema_repair_http() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return _completion_response('{"result":"fine","extra":true}')

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            with bind_model_call_boundary(
                max_calls=1, deadline_at=datetime.now(UTC) + timedelta(seconds=10)
            ):
                with pytest.raises(
                    ModelGatewayError, match="MODEL_CALL_BUDGET_EXCEEDED"
                ) as captured:
                    await DeepSeekAdapter(_deepseek_settings(), client=client).complete_structured(
                        (ModelMessage(role="system", content="Original rules."),),
                        _OtherPayload,
                        operation="article_body",
                        max_tokens=100,
                        error_code="ARTICLE_INVALID",
                        repair_instruction="Repair JSON.",
                    )
                assert captured.value.usage.request_count == 1

    asyncio.run(scenario())
    assert len(requests) == 1
