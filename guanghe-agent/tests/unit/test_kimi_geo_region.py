"""Kimi Search Pro and Chat must stay in the configured API Key region.

Kimi's China Search Pro documentation uses api.moonshot.cn, and its help center
states that China and international API Keys belong to separate regions.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Callable, Coroutine

import httpx
import pytest
from pydantic import SecretStr

from app.common.settings import AppSettings, ProviderSettings
from app.geo.models import GeoPlatformCode, GeoProviderUsage
from app.geo.providers import (
    GeoProviderError,
    GeoProviderRequest,
    GeoProviderResult,
    KimiGeoAdapter,
    build_geo_adapters,
)

_TEST_TOKEN = "unit-test-placeholder"
_SOURCE_URL = "https://source.example/kimi"
_EMPTY_SEARCH_PAYLOADS: tuple[dict[str, object], ...] = (
    {"search_results": []},
    {"search_results": [{"url": _SOURCE_URL, "snippet": "", "chunks": []}]},
)


def _settings(host: str) -> ProviderSettings:
    return ProviderSettings(
        credential=SecretStr(_TEST_TOKEN),
        model="kimi-k2.6",
        endpoint=f"https://{host}/v1/chat/completions",
        max_retries=0,
    )


def _search_result() -> dict[str, object]:
    return {
        "search_results": [
            {
                "title": "公开来源",
                "url": _SOURCE_URL,
                "snippet": "页面摘要",
                "chunks": [{"text": "可核对的正文", "score": 1.2}],
            }
        ]
    }


def _run_with_mock(
    host: str,
    handler: Callable[[httpx.Request], Coroutine[None, None, httpx.Response]],
) -> GeoProviderResult:
    async def scenario() -> GeoProviderResult:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = KimiGeoAdapter(_settings(host), client=client)
            return await adapter.observe(GeoProviderRequest("q-region", 1, "测试问题"))

    return asyncio.run(scenario())


@pytest.mark.parametrize("host", ("api.moonshot.cn", "api.moonshot.ai"))
def test_kimi_search_and_chat_use_one_configured_region(host: str) -> None:
    seen: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if request.url.path == "/v1/tools/search_pro":
            return httpx.Response(200, json=_search_result())
        if request.url.path == "/v1/chat/completions":
            return httpx.Response(
                200,
                json={
                    "choices": [{"message": {"content": "依据公开来源回答[1]"}}],
                    "usage": {"prompt_tokens": 12, "completion_tokens": 5},
                },
            )
        pytest.fail(f"Unexpected Kimi path: {request.url.path}")

    result = _run_with_mock(host, handler)

    assert [str(request.url) for request in seen] == [
        f"https://{host}/v1/tools/search_pro",
        f"https://{host}/v1/chat/completions",
    ]
    assert [request.headers["Authorization"] for request in seen] == [
        f"Bearer {_TEST_TOKEN}",
        f"Bearer {_TEST_TOKEN}",
    ]
    assert json.loads(seen[0].content) == {
        "text_query": "测试问题",
        "limit": 5,
        "timeout_seconds": 25,
    }
    chat_payload = json.loads(seen[1].content)
    assert chat_payload["model"] == "kimi-k2.6"
    assert chat_payload["thinking"] == {"type": "disabled"}
    assert chat_payload["max_tokens"] == 1024
    assert "tools" not in chat_payload
    assert _SOURCE_URL in seen[1].content.decode()
    assert "可核对的正文" in seen[1].content.decode()
    assert result.answer_text == "依据公开来源回答[1]"
    assert result.provider_call_count == 2
    assert result.usage == GeoProviderUsage(input_tokens=12, output_tokens=5, search_requests=1)
    assert len(result.citations) == 1
    assert result.citations[0].title == "公开来源"
    assert result.citations[0].url == _SOURCE_URL
    assert result.citations[0].snippet == "页面摘要\n可核对的正文"


def test_kimi_older_model_omits_k26_only_instant_extension() -> None:
    seen_chat: list[dict[str, object]] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/v1/tools/search_pro":
            return httpx.Response(200, json=_search_result())
        seen_chat.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "依据来源[1]"}}]},
        )

    async def scenario() -> GeoProviderResult:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            old = _settings("api.moonshot.cn").model_copy(update={"model": "moonshot-v1-8k"})
            adapter = KimiGeoAdapter(old, client=client)
            assert adapter.collection_method == "kimi-search-pro-then-bounded-chat-v2"
            return await adapter.observe(GeoProviderRequest("older", 1, "测试问题"))

    result = asyncio.run(scenario())
    assert result.answer_text == "依据来源[1]"
    assert len(seen_chat) == 1
    assert seen_chat[0]["max_tokens"] == 1024
    assert "thinking" not in seen_chat[0]


def test_kimi_length_finish_does_not_count_a_truncated_answer_as_success() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/v1/tools/search_pro":
            return httpx.Response(200, json=_search_result())
        assert request.url.path == "/v1/chat/completions"
        return httpx.Response(
            200,
            json={
                "choices": [{"finish_reason": "length", "message": {"content": "截断的回答[1]"}}],
                "usage": {"prompt_tokens": 12, "completion_tokens": 1024},
            },
        )

    with pytest.raises(GeoProviderError) as captured:
        _run_with_mock("api.moonshot.cn", handler)
    assert captured.value.code == "GEO_KIMI_ANSWER_TRUNCATED"
    assert captured.value.request_count == 2


def test_legacy_cn_chat_configuration_is_registered_as_usable() -> None:
    # model_construct avoids reading environment credentials while exercising assembly.
    settings = AppSettings.model_construct(geo_kimi=_settings("api.moonshot.cn"))
    adapters = build_geo_adapters(settings)
    try:
        kimi = next(
            adapter for adapter in adapters if adapter.platform_code is GeoPlatformCode.KIMI
        )
        assert len(adapters) == 7
        assert isinstance(kimi, KimiGeoAdapter)
        assert kimi.configured is True
        assert kimi.endpoint_host == "api.moonshot.cn"
    finally:

        async def close_adapters() -> None:
            for adapter in adapters:
                await adapter.aclose()

        asyncio.run(close_adapters())


@pytest.mark.parametrize(
    "endpoint",
    (
        "http://api.moonshot.cn/v1/chat/completions",
        "https://api.moonshot.cn:8443/v1/chat/completions",
        "https://api.moonshot.cn.evil.example/v1/chat/completions",
        "https://api.moonshot.cn@evil.example/v1/chat/completions",
        "https://api.moonshot.cn/v1/tools/search_pro",
        "https://api.moonshot.ai/v1/chat/completions?redirect=1",
        "https://api.moonshot.ai/v1/chat/completions#fragment",
    ),
)
def test_kimi_invalid_chat_endpoint_fails_before_network(endpoint: str) -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=_search_result())

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(ValueError, match="GEO_KIMI_ENDPOINT_INVALID"):
            KimiGeoAdapter(
                ProviderSettings(
                    credential=SecretStr(_TEST_TOKEN),
                    model="kimi-unit-test",
                    endpoint=endpoint,
                ),
                client=client,
            )
    finally:
        asyncio.run(client.aclose())
    assert seen == []


@pytest.mark.parametrize("host", ("api.moonshot.cn", "api.moonshot.ai"))
@pytest.mark.parametrize("search_payload", _EMPTY_SEARCH_PAYLOADS)
def test_kimi_empty_search_stops_before_chat(host: str, search_payload: dict[str, object]) -> None:
    seen: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        seen.append(str(request.url))
        return httpx.Response(200, json=search_payload)

    with pytest.raises(GeoProviderError) as captured:
        _run_with_mock(host, handler)

    assert captured.value.code == "GEO_KIMI_SEARCH_EMPTY"
    assert captured.value.request_count == 1
    assert seen == [f"https://{host}/v1/tools/search_pro"]


@pytest.mark.parametrize("host", ("api.moonshot.cn", "api.moonshot.ai"))
def test_kimi_empty_chat_answer_accounts_for_both_requests(host: str) -> None:
    seen: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        seen.append(str(request.url))
        if request.url.path == "/v1/tools/search_pro":
            return httpx.Response(200, json=_search_result())
        return httpx.Response(200, json={"choices": [{"message": {"content": "  "}}]})

    with pytest.raises(GeoProviderError) as captured:
        _run_with_mock(host, handler)

    assert captured.value.code == "GEO_PROVIDER_ANSWER_EMPTY"
    assert captured.value.request_count == 2
    assert seen == [
        f"https://{host}/v1/tools/search_pro",
        f"https://{host}/v1/chat/completions",
    ]
