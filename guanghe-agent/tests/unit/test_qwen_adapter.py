"""Qwen adapter contract tests without using a real credential or API quota."""

import asyncio
import json
import logging

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.agent.models import Intent
from app.api.application import create_app
from app.common.enums import ProviderStatus
from app.common.settings import AppSettings, ProviderSettings
from app.model_gateway import build_model_gateway
from app.model_gateway.gateway import (
    ModelGatewayError,
    ModelMessage,
    ModelMessages,
    default_intent_route,
)
from app.model_gateway.qwen import QwenAdapter
from app.observability.logging import JsonFormatter
from tests.internal_security import FakeInternalNonceStore, internal_settings, signed_headers


def _settings(*, max_retries: int = 0) -> ProviderSettings:
    return ProviderSettings(
        credential=SecretStr("unit-test-secret"),
        model="qwen3.8-flash",
        endpoint="https://qwen.test/compatible-mode/v1",
        max_retries=max_retries,
    )


def _messages(system: str, user: str) -> ModelMessages:
    return (
        ModelMessage(role="system", content=system),
        ModelMessage(role="user", content=user),
    )


def _router_payload(intent: Intent = Intent.GENERAL) -> str:
    return default_intent_route(intent).model_dump_json(by_alias=True)


def test_intent_router_repairs_once_and_preserves_usage() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if len(requests) == 1:
            return httpx.Response(
                200,
                json={
                    "choices": [{"message": {"content": "not-json"}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 3, "completion_tokens": 1},
                },
            )
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": _router_payload()}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 4, "completion_tokens": 2},
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(
            base_url="https://qwen.test/compatible-mode/v1",
            transport=httpx.MockTransport(handler),
        )
        adapter = QwenAdapter(
            _settings(),
            client=client,
            identity_provider="BAILIAN_QWEN",
        )
        try:
            result = await adapter.classify_intent(_messages("router policy", "envelope"))
        finally:
            await client.aclose()
        assert adapter.identity.provider == "BAILIAN_QWEN"
        assert result.usage.input_tokens == 7
        assert result.usage.output_tokens == 3
        assert result.usage.request_count == 2
        assert result.diagnostics is not None
        assert result.diagnostics.recovered_by_retry is True

    asyncio.run(scenario())

    assert len(requests) == 2
    retry_payload = json.loads(requests[1].content)
    assert "intent-router-v2.2" in retry_payload["messages"][0]["content"]
    assert retry_payload["messages"][1]["content"] == "envelope"


def test_probe_classification_streaming_and_task_parameters() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/compatible-mode/v1/models":
            return httpx.Response(
                200,
                json={"object": "list", "data": [{"id": "qwen3.8-flash"}]},
            )
        payload = json.loads(request.content)
        assert payload["enable_thinking"] is False
        assert payload["preserve_thinking"] is False
        assert "thinking" not in payload
        if payload.get("stream"):
            body = "\n".join(
                (
                    'data: {"choices":[{"delta":{"content":"你好"}}]}',
                    'data: {"choices":[{"delta":{"content":"，我是光禾AI智能体框架。"}}]}',
                    'data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":6}}',
                    "data: [DONE]",
                    "",
                )
            )
            return httpx.Response(200, text=body)
        if payload.get("max_tokens") == 512:
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "summary": "自然光厨房商品主图",
                                        "parameters": {
                                            "scene": "厨房台面",
                                            "lighting": "自然光",
                                            "negativePrompt": "",
                                            "style": "   ",
                                            "productId": "must-be-dropped",
                                            "price": 99,
                                            "points": 88,
                                            "confirmationToken": "must-be-dropped",
                                            "userId": "must-be-dropped",
                                        },
                                    },
                                    ensure_ascii=False,
                                )
                            }
                        }
                    ],
                    "usage": {"prompt_tokens": 14, "completion_tokens": 8},
                },
            )
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": _router_payload()}}],
                "usage": {"prompt_tokens": 8, "completion_tokens": 2},
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(
            base_url="https://qwen.test/compatible-mode/v1",
            headers={"Authorization": "Bearer unit-test-secret"},
            transport=httpx.MockTransport(handler),
        )
        adapter = QwenAdapter(_settings(), client=client)
        assert adapter.identity.provider == "QWEN"
        assert adapter.identity.model == "qwen3.8-flash"
        assert await adapter.probe() is True
        classified = await adapter.classify_intent(
            _messages("Return a bounded JSON intent.", "你好")
        )
        deltas: list[str] = []

        async def collect(delta: str) -> None:
            deltas.append(delta)

        answer = await adapter.answer_general(
            _messages("Bounded assistant", "你好"),
            on_delta=collect,
        )
        task_parameters = await adapter.suggest_task_parameters(
            Intent.IMAGE,
            _messages("Bounded assistant", "厨房台面商品主图，自然光"),
        )
        await client.aclose()

        assert classified.intent is Intent.GENERAL
        assert classified.usage.input_tokens == 8
        assert answer.text == "你好，我是光禾AI智能体框架。"
        assert answer.usage.output_tokens == 6
        assert deltas == ["你好", "，我是光禾AI智能体框架。"]
        assert task_parameters.parameters == {
            "scene": "厨房台面",
            "lighting": "自然光",
        }
        assert task_parameters.usage.input_tokens == 14
        assert task_parameters.warnings == ("有些不适用的内容没有加入草稿，请在确认前核对。",)
        assert adapter.status is ProviderStatus.AVAILABLE

    asyncio.run(scenario())
    assert [request.url.path for request in requests] == [
        "/compatible-mode/v1/models",
        "/compatible-mode/v1/chat/completions",
        "/compatible-mode/v1/chat/completions",
        "/compatible-mode/v1/chat/completions",
    ]
    assert all(
        request.headers["authorization"] == "Bearer unit-test-secret" for request in requests
    )


def _format_safe_failure(error: ModelGatewayError) -> str:
    record = logging.LogRecord(
        name="app.agent.run_manager",
        level=logging.ERROR,
        pathname=__file__,
        lineno=1,
        msg="chat_run_failed",
        args=(),
        exc_info=(type(error), error, error.__traceback__),
    )
    return JsonFormatter().format(record)


@pytest.mark.parametrize(
    ("failure", "expected_code"),
    (
        ("timeout", "QWEN_TIMEOUT"),
        ("invalid-json", "QWEN_COMPLETION_RESPONSE_INVALID"),
        ("rate-limit", "QWEN_RATE_LIMITED"),
        ("connection", "QWEN_CONNECTION_FAILED"),
    ),
)
def test_provider_failures_use_stable_codes_without_leaking_response(
    failure: str,
    expected_code: str,
) -> None:
    leak_marker = f"qwen-provider-private-{failure}"

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["authorization"] == "Bearer unit-test-secret"
        if failure == "timeout":
            raise httpx.ReadTimeout(leak_marker, request=request)
        if failure == "connection":
            raise httpx.ConnectError(leak_marker, request=request)
        if failure == "rate-limit":
            return httpx.Response(429, text=leak_marker)
        return httpx.Response(200, text=f"not-json:{leak_marker}")

    async def scenario() -> None:
        client = httpx.AsyncClient(
            base_url="https://qwen.test/compatible-mode/v1",
            headers={"Authorization": "Bearer unit-test-secret"},
            transport=httpx.MockTransport(handler),
        )
        adapter = QwenAdapter(_settings(), client=client)
        try:
            with pytest.raises(ModelGatewayError) as captured:
                await adapter.classify_intent(_messages("Return a bounded JSON intent.", "hello"))

            assert captured.value.code == expected_code
            assert adapter.last_error_code == expected_code
            assert adapter.status is ProviderStatus.UNAVAILABLE
            assert leak_marker not in str(captured.value)
            assert leak_marker not in _format_safe_failure(captured.value)
            assert "unit-test-secret" not in _format_safe_failure(captured.value)
        finally:
            await client.aclose()

    asyncio.run(scenario())


def test_auth_failure_exposes_only_safe_error_code() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, text="provider body containing unit-test-secret")

    async def scenario() -> None:
        client = httpx.AsyncClient(
            base_url="https://qwen.test/compatible-mode/v1",
            transport=httpx.MockTransport(handler),
        )
        adapter = QwenAdapter(_settings(), client=client)
        assert await adapter.probe() is False
        assert adapter.status is ProviderStatus.UNAVAILABLE
        assert adapter.last_error_code == "QWEN_AUTH_FAILED"
        try:
            await adapter.classify_intent(_messages("Return a bounded JSON intent.", "hello"))
        except ModelGatewayError as exc:
            assert exc.code == "QWEN_AUTH_FAILED"
            assert "unit-test-secret" not in str(exc)
        else:
            raise AssertionError("Expected a safe gateway error")
        await client.aclose()

    asyncio.run(scenario())


def test_auto_uses_deepseek_and_never_selects_configured_qwen() -> None:
    qwen = _settings()
    deepseek = ProviderSettings(
        credential=SecretStr("deepseek-unit-test-secret"),
        model="deepseek-v4-flash",
        endpoint="https://deepseek.test",
    )

    async def scenario() -> None:
        preferred = build_model_gateway(
            AppSettings(model_gateway="AUTO", qwen=qwen, deepseek=deepseek)
        )
        assert preferred.identity.provider == "DEEPSEEK"
        await preferred.aclose()

        local = build_model_gateway(
            AppSettings(model_gateway="AUTO", qwen=qwen, deepseek=ProviderSettings())
        )
        assert local.identity.provider == "LOCAL"
        assert local.is_mock is True
        await local.aclose()

    asyncio.run(scenario())


def test_explicit_qwen_is_disabled_for_conversation_gateway() -> None:
    settings = AppSettings(
        model_gateway="QWEN",
        qwen=_settings(),
    )
    try:
        build_model_gateway(settings)
    except ModelGatewayError as exc:
        assert exc.code == "QWEN_CONVERSATION_GATEWAY_DISABLED"
    else:
        raise AssertionError("Expected the Qwen conversation gateway to be disabled")


def test_unavailable_qwen_is_identified_in_capabilities() -> None:
    settings = internal_settings(
        model_gateway="QWEN",
        qwen=ProviderSettings(enabled=False, model="qwen3.8-flash"),
    )
    with TestClient(create_app(settings, internal_nonce_store=FakeInternalNonceStore())) as client:
        response = client.get(
            "/internal/v1/capabilities",
            headers=signed_headers("GET", "/internal/v1/capabilities"),
        )

    assert response.status_code == 200
    assert response.json()["modelGateway"] == {
        "mode": "UNAVAILABLE",
        "provider": "QWEN",
        "model": "qwen3.8-flash",
        "status": "UNAVAILABLE",
        "reasonCode": "QWEN_CONVERSATION_GATEWAY_DISABLED",
    }
