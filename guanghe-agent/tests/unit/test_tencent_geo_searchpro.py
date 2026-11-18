import asyncio
import json
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from pydantic import SecretStr

from app.common.settings import AppSettings, ProviderSettings
from app.geo.models import GeoObservation, GeoPlatformCode
from app.geo.providers import (
    GeoProviderError,
    GeoProviderRequest,
    GeoProviderResult,
    YuanbaoGeoAdapter,
    YuanbaoNativeGeoAdapter,
    build_geo_adapters,
)
from app.geo.runtime import GeoProviderRegistry, GeoSampleExecutor
from app.model_gateway.tencent import TencentHy3Adapter


def _settings():
    return ProviderSettings(credential=SecretStr("chat-test-key"), model="hy3", max_retries=0)


def _page():
    return json.dumps(
        {"url": "https://source.example/a", "title": "来源", "passage": "商品公开事实"}
    )


def _run(
    search: dict[str, object] | None = None, finish: str = "stop", budget: int | None = None
) -> tuple[GeoObservation | GeoProviderResult, list[tuple[str, object]]]:
    seen: list[tuple[str, object]] = []

    async def scenario():
        async def handler(request: httpx.Request) -> httpx.Response:
            payload = json.loads(request.content)
            seen.append((request.url.host, payload))
            if request.url.host == "api.wsa.cloud.tencent.com":
                assert request.headers["Authorization"] == "Bearer search-test-key"
                assert payload == {"Query": "普通问题", "Mode": 0}
                return httpx.Response(
                    200,
                    json=search
                    if search is not None
                    else {"Response": {"Pages": [_page()], "Version": "standard"}},
                )
            assert request.url == "https://tokenhub.tencentmaas.com/v1/chat/completions"
            assert request.headers["Authorization"] == "Bearer chat-test-key"
            assert payload["thinking"] == {"type": "disabled"}
            assert "web_search_options" not in payload
            assert "sources" in payload["messages"][1]["content"]
            return httpx.Response(
                200,
                json={
                    "choices": [{"message": {"content": "事实回答[1]"}, "finish_reason": finish}],
                    "usage": {"prompt_tokens": 8, "completion_tokens": 3},
                },
            )

        async with (
            httpx.AsyncClient(transport=httpx.MockTransport(handler)) as search_client,
            httpx.AsyncClient(
                base_url="https://tokenhub.tencentmaas.com/v1",
                headers={"Authorization": "Bearer chat-test-key"},
                transport=httpx.MockTransport(handler),
            ) as chat_client,
        ):
            adapter = YuanbaoGeoAdapter(
                _settings(),
                ProviderSettings(credential=SecretStr("search-test-key")),
                client=search_client,
                gateway=TencentHy3Adapter(_settings(), client=chat_client),
            )
            if budget is not None:
                return await GeoSampleExecutor(GeoProviderRegistry((adapter,))).execute(
                    platform_code=GeoPlatformCode.YUANBAO,
                    question_id="q",
                    sample_no=1,
                    question="普通问题",
                    max_provider_calls=budget,
                    deadline_at=datetime.now(UTC) + timedelta(seconds=30),
                )
            return await adapter.observe(GeoProviderRequest("q", 1, "普通问题"))

    return asyncio.run(scenario()), seen


def test_two_stages_use_separate_credentials_and_real_search_sources():
    result, seen = _run()
    assert len(seen) == result.provider_call_count == 2
    assert result.usage.search_requests == 1
    assert result.usage.input_tokens == 8
    assert result.citations[0].url == "https://source.example/a"


@pytest.mark.parametrize(
    "search",
    [
        {"Response": {"Error": {"Code": "UnauthorizedOperation"}}},
        {"Response": {}},
        {"Response": {"Pages": []}},
        {"Response": {"Pages": ["broken", json.dumps({"url": "https://example.com"})]}},
    ],
)
def test_http_200_without_usable_evidence_never_calls_hy3(search: dict[str, object]):
    with pytest.raises(GeoProviderError) as error:
        _run(search=search)
    assert error.value.request_count == 1


def test_second_call_is_admitted_by_authoritative_budget():
    result, seen = _run(budget=1)
    assert isinstance(result, GeoObservation)
    assert result.status == "FAILED"
    assert len(seen) == result.provider_call_count == 1


def test_truncated_answer_not_saved_as_success():
    with pytest.raises(GeoProviderError, match="GEO_YUANBAO_ANSWER_INVALID") as error:
        _run(finish="length")
    assert error.value.request_count == 2


def test_runtime_requires_search_key_and_does_not_fallback_to_native():
    async def scenario():
        adapters = build_geo_adapters(AppSettings.model_construct(geo_yuanbao=_settings()))
        try:
            assert not next(
                a for a in adapters if a.platform_code == GeoPlatformCode.YUANBAO
            ).configured
        finally:
            for a in adapters:
                await a.aclose()

    asyncio.run(scenario())


def test_native_search_requires_both_sources_and_call_count():
    async def scenario():
        async def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "content": "答案含网址https://fake.example",
                                "search_results": [{"url": "https://source.example/a"}],
                            }
                        }
                    ]
                },
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with pytest.raises(GeoProviderError, match="GEO_PROVIDER_SEARCH_NOT_OBSERVED"):
                await YuanbaoNativeGeoAdapter(_settings(), client=client).observe(
                    GeoProviderRequest("q", 1, "问题")
                )

    asyncio.run(scenario())
