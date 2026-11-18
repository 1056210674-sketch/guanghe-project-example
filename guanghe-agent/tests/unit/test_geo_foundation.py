from __future__ import annotations

import json
import logging
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from _pytest.logging import LogCaptureFixture
from pydantic import BaseModel, SecretStr, ValidationError

from app.common.enums import ProviderStatus
from app.common.settings import AppSettings, GeoDoubaoPolicySettings, ProviderSettings
from app.geo.models import (
    GeoCapabilityStatus,
    GeoCitation,
    GeoObservation,
    GeoObservationStatus,
    GeoPlatformCode,
    GeoProviderUsage,
)
from app.geo.providers import (
    DeepSeekGeoAdapter,
    DoubaoGeoAdapter,
    GeoProviderError,
    GeoProviderRequest,
    GeoProviderResult,
    KimiGeoAdapter,
    QwenGeoAdapter,
    UnconfiguredGeoAdapter,
    WenxinGeoAdapter,
    YuanbaoGeoAdapter,
    YuanbaoNativeGeoAdapter,
    ZhipuGeoAdapter,
    build_geo_adapters,
)
from app.geo.runtime import (
    GeoProviderRegistry,
    GeoProviderRegistryError,
    GeoSampleExecutor,
    GeoSynthesizer,
    capability_from_observation,
)
from app.harness.runtime import record_provider_call
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import (
    GatewayUsage,
    ModelGateway,
    ModelIdentity,
    ModelMessages,
    TextResult,
)


class _Adapter:
    online_search_supported = True
    collection_method = "mock-v1"

    def __init__(
        self,
        code: GeoPlatformCode,
        *,
        succeed: bool = True,
        call_twice: bool = False,
    ) -> None:
        self.platform_code = code
        self.provider = code.value
        self.model = "bounded-model"
        self.endpoint_host: str | None = "provider.example"
        self.configured = True
        self._succeed = succeed
        self._call_twice = call_twice

    async def observe(self, request: GeoProviderRequest) -> GeoProviderResult:
        del request
        record_provider_call()
        if self._call_twice:
            record_provider_call()
        if not self._succeed:
            raise RuntimeError("test adapter failure")
        now = datetime.now(UTC)
        return GeoProviderResult(
            answer_text="真实平台回答",
            citations=(
                GeoCitation(
                    title="来源",
                    url="https://example.com/source",
                    domain="example.com",
                    snippet="公开摘要",
                    observed_at=now,
                ),
            ),
            provider=self.provider,
            model=self.model,
            usage=GeoProviderUsage(input_tokens=8, output_tokens=4, search_requests=1),
            provider_call_count=1,
            latency_ms=3,
        )

    async def aclose(self) -> None:
        return None


class _DeepSeekSynthesisGateway:
    identity = ModelIdentity(provider="DEEPSEEK", model="test", version="1")
    is_mock = True
    status = ProviderStatus.AVAILABLE
    last_error_code: str | None = None

    def __init__(self) -> None:
        self.messages: ModelMessages | None = None

    async def answer_general(
        self,
        messages: ModelMessages,
        *,
        on_delta: object | None = None,
    ) -> TextResult:
        del messages, on_delta
        raise AssertionError("GEO synthesis must use the bounded structured completion path")

    async def complete_structured(
        self,
        messages: ModelMessages,
        response_model: type[BaseModel],
        *,
        operation: str,
        max_tokens: int,
        error_code: str,
        repair_instruction: str,
    ) -> tuple[BaseModel, GatewayUsage]:
        assert operation == "geo_synthesis"
        assert max_tokens == 4_096
        assert error_code == "DEEPSEEK_GEO_SYNTHESIS_RESPONSE_INVALID"
        assert "observation data" in repair_instruction
        assert "missingSamples" not in response_model.model_json_schema(by_alias=True)["properties"]
        assert "methodVersion" not in response_model.model_json_schema(by_alias=True)["properties"]
        self.messages = messages
        parsed = response_model.model_validate(
            {
                "summary": "部分平台成功，结论有边界。",
                "coverageGaps": ["DEEPSEEK 样本缺失"],
                "citationStructure": ["保留原始引用"],
                "competitorSignals": [],
                "competitorCandidates": [],
                "findings": ["失败平台仍在分母中"],
                "recommendations": [{"priority": "P0", "title": "补样", "rationale": "覆盖不足"}],
                "contentBlueprint": ["明确方法和样本数"],
            }
        )
        return parsed, GatewayUsage(input_tokens=10, output_tokens=20)

    async def aclose(self) -> None:
        return None


def _deadline() -> datetime:
    return datetime.now(UTC) + timedelta(seconds=10)


def test_registry_is_exact_and_never_falls_back_to_another_platform() -> None:
    doubao = _Adapter(GeoPlatformCode.DOUBAO)
    registry = GeoProviderRegistry([doubao])

    assert registry.require(GeoPlatformCode.DOUBAO) is doubao
    with pytest.raises(GeoProviderRegistryError, match="GEO_PROVIDER_NOT_REGISTERED"):
        registry.require(GeoPlatformCode.QWEN)


def test_default_factory_preserves_the_fixed_seven_platform_matrix() -> None:
    adapters = build_geo_adapters(AppSettings())

    assert tuple(adapter.platform_code for adapter in adapters) == tuple(GeoPlatformCode)
    assert isinstance(adapters[1], UnconfiguredGeoAdapter)
    assert adapters[1].online_search_supported is True
    assert all(adapter.provider == adapter.platform_code.value for adapter in adapters)


def test_deepseek_geo_reuses_one_credential_for_search_and_model() -> None:
    model = ProviderSettings(
        credential=SecretStr("not-a-real-model-secret"), model="deepseek-v4-flash"
    )
    configured = build_geo_adapters(AppSettings(geo_deepseek=model))
    assert configured[1].configured is True
    assert configured[1].online_search_supported is True
    without_deepseek = build_geo_adapters(
        AppSettings(
            geo_bocha=ProviderSettings(credential=SecretStr("not-a-real-search-secret")),
        )
    )
    assert without_deepseek[1].configured is False
    __import__("asyncio").run(configured[1].aclose())


def test_success_and_explicit_failure_observations_are_both_preserved() -> None:
    async def scenario() -> tuple[GeoObservation, GeoObservation]:
        success = _Adapter(GeoPlatformCode.DOUBAO)
        deepseek = UnconfiguredGeoAdapter(
            GeoPlatformCode.DEEPSEEK,
            model="deepseek-chat",
            endpoint_host="api.deepseek.com",
        )
        executor = GeoSampleExecutor(GeoProviderRegistry([success, deepseek]))
        first = await executor.execute(
            platform_code=GeoPlatformCode.DOUBAO,
            question_id="1900012345678901001",
            sample_no=1,
            question="不写入日志的测试问题",
            max_provider_calls=1,
            deadline_at=_deadline(),
        )
        second = await executor.execute(
            platform_code=GeoPlatformCode.DEEPSEEK,
            question_id="1900012345678901001",
            sample_no=1,
            question="不写入日志的测试问题",
            max_provider_calls=1,
            deadline_at=_deadline(),
        )
        return first, second

    first, second = __import__("asyncio").run(scenario())
    assert first.status is GeoObservationStatus.SUCCEEDED
    assert first.provider == "DOUBAO"
    assert first.collection_method == "mock-v1"
    assert second.status is GeoObservationStatus.FAILED
    assert second.answer_text is None
    assert second.stable_error_code == "GEO_DEEPSEEK_CONFIG_MISSING"
    assert second.collection_method is None


def test_deepseek_uses_official_search_then_answers_via_model_gateway() -> None:
    async def scenario() -> tuple[GeoObservation, GeoObservation, object]:
        model_calls = 0

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal model_calls
            payload = json.loads(request.content)
            assert request.url.host == "api.deepseek.com"
            assert request.headers["Authorization"] == "Bearer not-a-real-model-secret"
            assert request.headers["Content-Type"] == "application/json"
            if request.url.path == "/v1/tools/search":
                assert payload == {
                    "query": "搜索样本",
                    "count": 8,
                    "need_content": False,
                }
                return httpx.Response(
                    200,
                    json={
                        "results": [
                            {
                                "title": "公开资料",
                                "url": "https://source.example/article",
                                "summary": "可靠的长摘要。",
                                "snippet": "不应优先使用的短摘要。",
                                "site_name": "权威信源",
                            },
                            {
                                "title": "重复资料",
                                "url": "https://source.example/article",
                                "summary": "同一 URL 不得重复。",
                                "site_name": "重复信源",
                            },
                            {
                                "title": "仅短摘要资料",
                                "url": "https://second.example/page",
                                "summary": "",
                                "snippet": "摘要缺失时使用 snippet。",
                                "site_name": "第二信源",
                            },
                        ]
                    },
                )
            assert request.url.path == "/chat/completions"
            model_calls += 1
            assert payload["model"] == "deepseek-v4-flash"
            assert "tools" not in payload
            assert "source.example/article" in payload["messages"][1]["content"]
            assert '"sourceName":"权威信源"' in payload["messages"][1]["content"]
            assert "可靠的长摘要" in payload["messages"][1]["content"]
            assert "不应优先使用的短摘要" not in payload["messages"][1]["content"]
            assert "摘要缺失时使用 snippet" in payload["messages"][1]["content"]
            stream = (
                'data: {"choices":[{"delta":{"content":"基于来源回答[1]"}}]}\n\n'
                'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n'
                'data: {"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":4}}\n\n'
                "data: [DONE]\n\n"
            )
            return httpx.Response(200, text=stream, headers={"content-type": "text/event-stream"})

        transport = httpx.MockTransport(handler)
        client = httpx.AsyncClient(transport=transport)
        gateway_client = httpx.AsyncClient(
            base_url="https://api.deepseek.com",
            headers={
                "Authorization": "Bearer not-a-real-model-secret",
                "Content-Type": "application/json",
            },
            transport=transport,
        )
        model_settings = ProviderSettings(
            credential=SecretStr("not-a-real-model-secret"),
            model="deepseek-v4-flash",
            max_retries=0,
        )
        adapter = DeepSeekGeoAdapter(
            model_settings,
            client=client,
            gateway=DeepSeekAdapter(model_settings, client=gateway_client),
        )
        executor = GeoSampleExecutor(GeoProviderRegistry([adapter]))
        try:
            rejected = await executor.execute(
                platform_code=GeoPlatformCode.DEEPSEEK,
                question_id="geo-search",
                sample_no=1,
                question="搜索样本",
                max_provider_calls=1,
                deadline_at=_deadline(),
            )
            audited = await executor.execute(
                platform_code=GeoPlatformCode.DEEPSEEK,
                question_id="geo-search-2",
                sample_no=1,
                question="搜索样本",
                max_provider_calls=2,
                deadline_at=_deadline(),
            )
            capability = capability_from_observation(adapter, audited)
            assert model_calls == 1
            return rejected, audited, capability
        finally:
            await client.aclose()
            await gateway_client.aclose()

    rejected, audited, capability = __import__("asyncio").run(scenario())
    assert rejected.stable_error_code == "PROVIDER_CALL_BUDGET_EXCEEDED"
    assert rejected.provider_call_count == 1
    assert rejected.collection_method == "deepseek-official-search-then-chat-v1"
    assert audited.status is GeoObservationStatus.SUCCEEDED
    assert audited.collection_method == "deepseek-official-search-then-chat-v1"
    assert audited.provider_call_count == 2
    assert audited.usage == GeoProviderUsage(input_tokens=9, output_tokens=4, search_requests=1)
    assert audited.citations[0].title == "公开资料"
    assert audited.citations[0].snippet == "可靠的长摘要。"
    assert audited.citations[1].snippet == "摘要缺失时使用 snippet。"
    assert capability.status is GeoCapabilityStatus.DIRECT_SEARCH_WITH_CITATIONS
    assert capability.online_search_supported is True


def test_kimi_search_pro_supplies_real_sources_to_chat_and_counts_both_calls() -> None:
    async def scenario() -> tuple[GeoObservation, GeoObservation, int]:
        chat_calls = 0

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal chat_calls
            assert request.headers["Authorization"] == "Bearer not-a-real-secret"
            payload = json.loads(request.content)
            if request.url.path == "/v1/tools/search_pro":
                assert request.url.host == "api.moonshot.ai"
                assert payload == {"text_query": "搜索样本", "limit": 5, "timeout_seconds": 25}
                return httpx.Response(
                    200,
                    json={
                        "search_results": [
                            {
                                "title": "官方资料",
                                "url": "https://source.example/kimi",
                                "snippet": "摘要内容",
                                "chunks": [{"text": "与问题相关的网页段落", "score": 1.2}],
                            }
                        ]
                    },
                )
            assert request.url.path == "/v1/chat/completions"
            assert request.url.host == "api.moonshot.ai"
            chat_calls += 1
            assert payload["model"] == "kimi-test"
            assert "tools" not in payload
            assert "source.example/kimi" in payload["messages"][1]["content"]
            assert "与问题相关的网页段落" in payload["messages"][1]["content"]
            return httpx.Response(
                200,
                json={
                    "choices": [{"message": {"content": "据来源回答[1]"}}],
                    "usage": {"prompt_tokens": 12, "completion_tokens": 5},
                },
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = KimiGeoAdapter(
            ProviderSettings(
                credential=SecretStr("not-a-real-secret"),
                model="kimi-test",
                endpoint="https://api.moonshot.ai/v1/chat/completions",
            ),
            client=client,
        )
        executor = GeoSampleExecutor(GeoProviderRegistry([adapter]))
        try:
            denied = await executor.execute(
                platform_code=GeoPlatformCode.KIMI,
                question_id="kimi-search-budget",
                sample_no=1,
                question="搜索样本",
                max_provider_calls=1,
                deadline_at=_deadline(),
            )
            success = await executor.execute(
                platform_code=GeoPlatformCode.KIMI,
                question_id="kimi-search-success",
                sample_no=1,
                question="搜索样本",
                max_provider_calls=2,
                deadline_at=_deadline(),
            )
            return denied, success, chat_calls
        finally:
            await client.aclose()

    denied, success, chat_calls = __import__("asyncio").run(scenario())
    assert denied.stable_error_code == "PROVIDER_CALL_BUDGET_EXCEEDED"
    assert denied.provider_call_count == 1
    assert success.status is GeoObservationStatus.SUCCEEDED
    assert success.provider_call_count == 2
    assert success.usage == GeoProviderUsage(input_tokens=12, output_tokens=5, search_requests=1)
    assert success.citations[0].url == "https://source.example/kimi"
    assert success.citations[0].snippet == "摘要内容\n与问题相关的网页段落"
    assert chat_calls == 1


@pytest.mark.parametrize(
    ("search", "expected_code"),
    (
        ({}, "GEO_KIMI_SEARCH_RESPONSE_INVALID"),
        ({"search_results": []}, "GEO_KIMI_SEARCH_EMPTY"),
        (
            {"search_results": [{"url": "https://source.example/no-evidence"}]},
            "GEO_KIMI_SEARCH_EMPTY",
        ),
        (
            {"search_results": [{"url": "javascript:unsafe", "snippet": "text"}]},
            "GEO_KIMI_SEARCH_EMPTY",
        ),
    ),
)
def test_kimi_invalid_search_pro_response_never_calls_chat(
    search: dict[str, object], expected_code: str
) -> None:
    async def scenario() -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            assert request.url.path == "/v1/tools/search_pro"
            return httpx.Response(200, json=search)

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = KimiGeoAdapter(
            ProviderSettings(credential=SecretStr("not-a-real-secret"), model="kimi-test"),
            client=client,
        )
        try:
            with pytest.raises(GeoProviderError) as captured:
                await adapter.observe(GeoProviderRequest("q", 1, "问题"))
            assert captured.value.code == expected_code
            assert captured.value.request_count == 1
        finally:
            await client.aclose()

    __import__("asyncio").run(scenario())


def test_kimi_chat_failure_includes_search_and_chat_attempts() -> None:
    async def scenario() -> None:
        attempts = 0

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal attempts
            if request.url.path == "/v1/tools/search_pro":
                return httpx.Response(
                    200,
                    json={
                        "search_results": [{"url": "https://source.example/a", "snippet": "有证据"}]
                    },
                )
            attempts += 1
            return httpx.Response(503)

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = KimiGeoAdapter(
            ProviderSettings(
                credential=SecretStr("not-a-real-secret"), model="kimi-test", max_retries=3
            ),
            client=client,
        )
        try:
            with pytest.raises(GeoProviderError) as captured:
                await adapter.observe(GeoProviderRequest("q", 1, "问题"))
            assert captured.value.code == "GEO_PROVIDER_MAINTENANCE"
            assert captured.value.request_count == 3
            assert attempts == 2
        finally:
            await client.aclose()

    __import__("asyncio").run(scenario())


def test_kimi_china_endpoint_is_registered_with_matching_search_host() -> None:
    async def scenario() -> None:
        adapters = build_geo_adapters(
            AppSettings(
                geo_kimi=ProviderSettings(
                    credential=SecretStr("not-a-real-secret"),
                    model="kimi-test",
                    endpoint="https://api.moonshot.cn/v1/chat/completions",
                )
            )
        )
        kimi = next(
            adapter for adapter in adapters if adapter.platform_code is GeoPlatformCode.KIMI
        )
        try:
            assert len(adapters) == 7
            assert isinstance(kimi, KimiGeoAdapter)
            assert kimi.configured is True
            assert kimi.endpoint_host == "api.moonshot.cn"
        finally:
            await kimi.aclose()

    __import__("asyncio").run(scenario())


@pytest.mark.parametrize(
    "endpoint",
    (
        "https://api.moonshot.ai:8443/v1/chat/completions",
        "https://api.moonshot.ai/v1/tools/search_pro",
        "https://api.moonshot.cn:8443/v1/chat/completions",
        "https://api.moonshot.cn/v1/tools/search_pro",
        "https://untrusted.example/v1/chat/completions",
    ),
)
def test_kimi_rejects_non_documented_chat_endpoints(endpoint: str) -> None:
    with pytest.raises(ValueError, match="GEO_KIMI_ENDPOINT_INVALID"):
        KimiGeoAdapter(
            ProviderSettings(
                credential=SecretStr("not-a-real-secret"), model="kimi-test", endpoint=endpoint
            )
        )


@pytest.mark.parametrize(
    ("response", "expected_code"),
    (
        ({"unexpected": []}, "GEO_DEEPSEEK_SEARCH_RESPONSE_INVALID"),
        ({"results": ["not-an-object"]}, "GEO_DEEPSEEK_SEARCH_RESPONSE_INVALID"),
        ({"results": []}, "GEO_DEEPSEEK_SEARCH_EMPTY"),
        (
            {"results": [{"title": "不安全链接", "url": "javascript:unsafe"}]},
            "GEO_DEEPSEEK_SEARCH_EMPTY",
        ),
    ),
)
def test_deepseek_search_failure_or_empty_results_never_call_chat(
    response: dict[str, object], expected_code: str
) -> None:
    async def scenario() -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            assert request.url == "https://api.deepseek.com/v1/tools/search"
            return httpx.Response(200, json=response)

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        model_settings = ProviderSettings(
            credential=SecretStr("not-a-real-model-secret"), model="deepseek-v4-flash"
        )
        adapter = DeepSeekGeoAdapter(
            model_settings,
            client=client,
            gateway=DeepSeekAdapter(model_settings, client=client),
        )
        try:
            with pytest.raises(GeoProviderError) as captured:
                await adapter.observe(GeoProviderRequest("q", 1, "问题"))
            assert captured.value.code == expected_code
            assert captured.value.request_count == 1
        finally:
            await client.aclose()

    __import__("asyncio").run(scenario())


def test_deepseek_search_trims_and_bounds_query_to_128_unicode_characters() -> None:
    async def scenario() -> None:
        seen: list[dict[str, object]] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            seen.append(json.loads(request.content))
            return httpx.Response(200, json={"results": []})

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        settings = ProviderSettings(
            credential=SecretStr("not-a-real-model-secret"),
            model="deepseek-v4-flash",
            max_retries=0,
        )
        adapter = DeepSeekGeoAdapter(
            settings,
            client=client,
            gateway=DeepSeekAdapter(settings, client=client),
        )
        try:
            result = await GeoSampleExecutor(GeoProviderRegistry([adapter])).execute(
                platform_code=GeoPlatformCode.DEEPSEEK,
                question_id="geo-search-bounded",
                sample_no=1,
                question="  " + "监测" * 100 + "  ",
                max_provider_calls=1,
                deadline_at=_deadline(),
            )
            assert result.stable_error_code == "GEO_DEEPSEEK_SEARCH_EMPTY"
            assert seen == [{"query": "监测" * 64, "count": 8, "need_content": False}]
        finally:
            await client.aclose()

    __import__("asyncio").run(scenario())


def test_deepseek_blank_search_query_fails_before_http() -> None:
    async def scenario() -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            raise AssertionError(f"blank query must not call {request.url}")

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        settings = ProviderSettings(
            credential=SecretStr("not-a-real-model-secret"), model="deepseek-v4-flash"
        )
        adapter = DeepSeekGeoAdapter(
            settings,
            client=client,
            gateway=DeepSeekAdapter(settings, client=client),
        )
        try:
            with pytest.raises(GeoProviderError) as captured:
                await adapter.observe(GeoProviderRequest("q", 1, " \t\n "))
            assert captured.value.code == "GEO_DEEPSEEK_SEARCH_QUERY_INVALID"
            assert captured.value.request_count == 0
        finally:
            await client.aclose()

    __import__("asyncio").run(scenario())


def test_deepseek_official_search_retries_http_408_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def no_wait(_delay: float) -> None:
        return None

    monkeypatch.setattr("app.geo.providers.wait_geo_retry", no_wait)

    async def scenario() -> None:
        calls = 0

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal calls
            calls += 1
            assert request.url == "https://api.deepseek.com/v1/tools/search"
            if calls == 1:
                return httpx.Response(408)
            return httpx.Response(200, json={"results": []})

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        settings = ProviderSettings(
            credential=SecretStr("not-a-real-model-secret"),
            model="deepseek-v4-flash",
            max_retries=1,
        )
        adapter = DeepSeekGeoAdapter(
            settings,
            client=client,
            gateway=DeepSeekAdapter(settings, client=client),
        )
        try:
            result = await GeoSampleExecutor(GeoProviderRegistry([adapter])).execute(
                platform_code=GeoPlatformCode.DEEPSEEK,
                question_id="geo-search-retry",
                sample_no=1,
                question="重试测试",
                max_provider_calls=2,
                deadline_at=_deadline(),
            )
            assert result.status is GeoObservationStatus.FAILED
            assert result.stable_error_code == "GEO_DEEPSEEK_SEARCH_EMPTY"
            assert result.provider_call_count == 2
            assert calls == 2
        finally:
            await client.aclose()

    __import__("asyncio").run(scenario())


def test_geo_transport_caps_retries_to_one_even_if_shared_setting_allows_three() -> None:
    async def scenario() -> None:
        calls = 0

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal calls
            assert request.url.host == "ark.cn-beijing.volces.com"
            calls += 1
            return httpx.Response(503)

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = DoubaoGeoAdapter(
            ProviderSettings(
                credential=SecretStr("not-a-real-secret"), model="test-model", max_retries=3
            ),
            client=client,
            # This case verifies attempt limits; real backoff is covered separately.
            policy=GeoDoubaoPolicySettings(retry_base_seconds=0, retry_max_seconds=0),
        )
        try:
            with pytest.raises(GeoProviderError) as captured:
                await adapter.observe(GeoProviderRequest("q", 1, "问题"))
            assert captured.value.code == "GEO_PROVIDER_MAINTENANCE"
            assert captured.value.request_count == 2
            assert calls == 2
        finally:
            await client.aclose()

    __import__("asyncio").run(scenario())


@pytest.mark.parametrize(
    "endpoint",
    (
        "https://api.deepseek.com/v1/tools/search",
        "https://api.deepseek.com:8443",
        "https://example.com",
    ),
)
def test_deepseek_requires_a_documented_chat_endpoint(endpoint: str) -> None:
    with pytest.raises(ValueError, match="GEO_DEEPSEEK_ENDPOINT_INVALID"):
        DeepSeekGeoAdapter(
            ProviderSettings(
                credential=SecretStr("not-a-real-model-secret"),
                model="deepseek",
                endpoint=endpoint,
            )
        )


@pytest.mark.parametrize(
    "endpoint",
    (
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions",
        "https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions",
    ),
)
def test_qwen_official_endpoints_preserve_forced_search_and_model(endpoint: str) -> None:
    async def scenario() -> tuple[GeoProviderResult, dict[str, object]]:
        seen: dict[str, object] = {}

        async def handler(request: httpx.Request) -> httpx.Response:
            seen.update(json.loads(request.content))
            assert str(request.url) == endpoint
            assert request.headers["Authorization"] == "Bearer not-a-real-secret"
            return httpx.Response(
                200,
                json={
                    "choices": [{"message": {"content": "联网回答但兼容协议不返回来源"}}],
                    "usage": {"prompt_tokens": 12, "completion_tokens": 6},
                },
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenGeoAdapter(
            ProviderSettings(
                credential=SecretStr("not-a-real-secret"),
                model="qwen-plus",
                endpoint=endpoint,
                max_retries=0,
            ),
            client=client,
        )
        try:
            result = await adapter.observe(GeoProviderRequest("q", 1, "问题"))
            return result, seen
        finally:
            await client.aclose()

    result, payload = __import__("asyncio").run(scenario())
    assert payload["model"] == "qwen-plus"
    assert payload["messages"] == [{"role": "user", "content": "问题"}]
    assert payload["enable_search"] is True
    assert payload["search_options"] == {"forced_search": True, "search_strategy": "turbo"}
    assert result.citations == ()
    assert result.usage == GeoProviderUsage(
        input_tokens=12,
        output_tokens=6,
        search_requests=1,
    )


def test_wenxin_requires_search_and_preserves_official_references() -> None:
    async def scenario() -> tuple[GeoProviderResult, dict[str, object]]:
        seen: dict[str, object] = {}

        async def handler(request: httpx.Request) -> httpx.Response:
            seen.update(json.loads(request.content))
            assert request.headers["X-Appbuilder-Authorization"] == "Bearer not-a-real-secret"
            return httpx.Response(
                200,
                json={
                    "choices": [{"message": {"content": "有来源的回答"}}],
                    "references": [
                        {
                            "title": "公开来源",
                            "url": "https://source.example/a",
                            "content": "来源摘要",
                        }
                    ],
                },
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = WenxinGeoAdapter(
            ProviderSettings(credential=SecretStr("not-a-real-secret"), model="ernie-test"),
            client=client,
        )
        try:
            return await adapter.observe(GeoProviderRequest("q", 1, "问题")), seen
        finally:
            await client.aclose()

    result, payload = __import__("asyncio").run(scenario())
    assert payload["search_source"] == "baidu_search_v2"
    assert payload["search_mode"] == "required"
    assert result.citations[0].url == "https://source.example/a"
    assert result.citations[0].snippet == "来源摘要"


def test_wenxin_response_without_search_evidence_fails() -> None:
    async def scenario() -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            del request
            return httpx.Response(200, json={"choices": [{"message": {"content": "普通回答"}}]})

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = WenxinGeoAdapter(
            ProviderSettings(credential=SecretStr("not-a-real-secret"), model="ernie-test"),
            client=client,
        )
        try:
            with pytest.raises(GeoProviderError) as captured:
                await adapter.observe(GeoProviderRequest("q", 1, "问题"))
            assert captured.value.code == "GEO_PROVIDER_SEARCH_NOT_OBSERVED"
            assert captured.value.request_count == 1
        finally:
            await client.aclose()

    __import__("asyncio").run(scenario())


def test_yuanbao_native_rejects_usage_without_structured_sources() -> None:
    async def scenario() -> dict[str, object]:
        seen: dict[str, object] = {}

        async def handler(request: httpx.Request) -> httpx.Response:
            seen.update(json.loads(request.content))
            return httpx.Response(
                200,
                json={
                    "choices": [{"message": {"content": "联网回答"}}],
                    "usage": {
                        "prompt_tokens": 7,
                        "completion_tokens": 3,
                        "tool_usage": {"web_search_call": 1},
                    },
                },
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = YuanbaoNativeGeoAdapter(
            ProviderSettings(
                credential=SecretStr("not-a-real-secret"),
                model="hy3",
                max_retries=0,
            ),
            client=client,
        )
        try:
            with pytest.raises(GeoProviderError, match="GEO_PROVIDER_SEARCH_NOT_OBSERVED"):
                await adapter.observe(GeoProviderRequest("q", 1, "问题"))
            return seen
        finally:
            await client.aclose()

    payload = __import__("asyncio").run(scenario())
    assert payload["web_search_options"] == {"enable": True, "search_source": "lite"}


def test_yuanbao_preserves_documented_chat_search_result_title() -> None:
    async def scenario() -> GeoProviderResult:
        async def handler(request: httpx.Request) -> httpx.Response:
            del request
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "content": "引用回答[1]",
                                "search_results": [
                                    {
                                        "name": "官方来源",
                                        "url": "https://source.example/tencent",
                                        "snippet": "来源摘要",
                                    }
                                ],
                            }
                        }
                    ],
                    "usage": {"tool_usage": {"web_search_call": 1}},
                },
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = YuanbaoNativeGeoAdapter(
            ProviderSettings(credential=SecretStr("not-a-real-secret"), model="hy3"),
            client=client,
        )
        try:
            return await adapter.observe(GeoProviderRequest("q", 1, "问题"))
        finally:
            await client.aclose()

    result = __import__("asyncio").run(scenario())
    assert result.citations[0].title == "官方来源"
    assert result.citations[0].url == "https://source.example/tencent"


def test_zhipu_forced_search_has_query_and_counted_without_inventing_citations() -> None:
    async def scenario() -> tuple[GeoProviderResult, dict[str, object]]:
        seen: dict[str, object] = {}

        async def handler(request: httpx.Request) -> httpx.Response:
            seen.update(json.loads(request.content))
            return httpx.Response(
                200,
                json={
                    "choices": [{"message": {"content": "联网回答"}}],
                    "usage": {
                        "prompt_tokens": 8,
                        "completion_tokens": 5,
                        "tool_usage": {"web_search_call": 1},
                    },
                },
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = ZhipuGeoAdapter(
            ProviderSettings(
                credential=SecretStr("not-a-real-secret"),
                model="glm-test",
                max_retries=0,
            ),
            client=client,
        )
        try:
            result = await adapter.observe(GeoProviderRequest("q", 1, "问题"))
            return result, seen
        finally:
            await client.aclose()

    result, payload = __import__("asyncio").run(scenario())
    assert payload["tools"] == [
        {
            "type": "web_search",
            "web_search": {
                "enable": True,
                "search_engine": "search_std",
                "search_result": True,
                "search_query": "问题",
                "require_search": True,
            },
        }
    ]
    assert result.citations == ()
    assert result.usage.search_requests == 1


@pytest.mark.parametrize(
    "adapter_type", (DoubaoGeoAdapter, YuanbaoNativeGeoAdapter, ZhipuGeoAdapter)
)
def test_search_enabled_response_without_observed_search_is_not_a_geo_sample(
    adapter_type: type[DoubaoGeoAdapter | YuanbaoNativeGeoAdapter | ZhipuGeoAdapter],
) -> None:
    async def scenario() -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            del request
            return httpx.Response(
                200,
                json={
                    "output_text": "普通回答",
                    "choices": [{"message": {"content": "普通回答"}}],
                    "usage": {"prompt_tokens": 3, "completion_tokens": 2},
                },
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = adapter_type(
            ProviderSettings(credential=SecretStr("not-a-real-secret"), model="test-model"),
            client=client,
        )
        try:
            with pytest.raises(GeoProviderError) as captured:
                await adapter.observe(GeoProviderRequest("q", 1, "问题"))
            assert captured.value.code == "GEO_PROVIDER_SEARCH_NOT_OBSERVED"
            assert captured.value.request_count == 1
        finally:
            await client.aclose()

    __import__("asyncio").run(scenario())


def test_qwen_rejects_unapproved_or_credential_bearing_endpoints() -> None:
    for endpoint in (
        "https://example.com/compatible-mode/v1/chat/completions",
        "https://user:pass@dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
        "https://maas.qianwenaiapi.com.example.com/compatible-mode/v1/chat/completions",
        "https://example.maas.qianwenaiapi.com/compatible-mode/v1/chat/completions",
        "http://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions",
        "https://user:pass@maas.qianwenaiapi.com/compatible-mode/v1/chat/completions",
        "https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions?key=secret",
        "https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions#fragment",
    ):
        with pytest.raises(ValueError, match="GEO_QWEN_ENDPOINT_INVALID"):
            QwenGeoAdapter(
                ProviderSettings(
                    credential=SecretStr("not-a-real-secret"),
                    model="qwen-plus",
                    endpoint=endpoint,
                )
            )


def test_canonical_observation_rejects_invalid_answer_and_citation_shapes() -> None:
    now = datetime.now(UTC)
    with pytest.raises(ValidationError):
        GeoCitation(url="javascript:alert(1)", observed_at=now)
    with pytest.raises(ValidationError):
        GeoObservation(
            platform_code=GeoPlatformCode.DOUBAO,
            question_id="geo-q",
            sample_no=1,
            status=GeoObservationStatus.SUCCEEDED,
            answer_text=None,
            provider="DOUBAO",
            model="m",
            started_at=now,
            finished_at=now,
            latency_ms=0,
            provider_call_count=1,
        )
    with pytest.raises(ValidationError):
        GeoObservation(
            platform_code=GeoPlatformCode.QWEN,
            question_id="geo-q",
            sample_no=1,
            status=GeoObservationStatus.FAILED,
            answer_text="模板补位",
            provider="QWEN",
            model="m",
            started_at=now,
            finished_at=now,
            latency_ms=0,
            stable_error_code="GEO_PROVIDER_FAILED",
            provider_call_count=1,
        )


@pytest.mark.parametrize(
    ("adapter", "method"),
    [
        (DoubaoGeoAdapter, "doubao-responses-web-search-v1"),
        (DeepSeekGeoAdapter, "deepseek-official-search-then-chat-v1"),
        (QwenGeoAdapter, "qwen-forced-turbo-search-v1"),
        (WenxinGeoAdapter, "wenxin-baidu-search-v2-required-v1"),
        (YuanbaoGeoAdapter, "tencent-searchpro-then-hy3-v1"),
        (ZhipuGeoAdapter, "zhipu-search-std-v2"),
        (KimiGeoAdapter, "kimi-search-pro-then-instant-chat-v2"),
    ],
)
def test_collection_method_declares_the_actual_adapter_search_path(
    adapter: type[DoubaoGeoAdapter]
    | type[DeepSeekGeoAdapter]
    | type[QwenGeoAdapter]
    | type[WenxinGeoAdapter]
    | type[YuanbaoGeoAdapter]
    | type[ZhipuGeoAdapter]
    | type[KimiGeoAdapter],
    method: str,
) -> None:
    assert adapter.collection_method == method


def test_provider_call_budget_is_checked_before_each_real_action() -> None:
    async def scenario() -> GeoObservation:
        adapter = _Adapter(GeoPlatformCode.KIMI, call_twice=True)
        return await GeoSampleExecutor(GeoProviderRegistry([adapter])).execute(
            platform_code=GeoPlatformCode.KIMI,
            question_id="geo-budget",
            sample_no=1,
            question="预算测试",
            max_provider_calls=1,
            deadline_at=_deadline(),
        )

    result = __import__("asyncio").run(scenario())
    assert result.status is GeoObservationStatus.FAILED
    assert result.stable_error_code == "PROVIDER_CALL_BUDGET_EXCEEDED"


def test_doubao_responses_mapping_keeps_answer_citations_and_usage() -> None:
    async def scenario() -> GeoProviderResult:
        async def handler(request: httpx.Request) -> httpx.Response:
            assert request.url.host == "ark.cn-beijing.volces.com"
            return httpx.Response(
                200,
                json={
                    "output_text": "联网回答",
                    "output": [
                        {
                            "type": "web_search_call",
                            "action": {
                                "sources": [
                                    {
                                        "title": "公开来源",
                                        "url": "https://source.example/a",
                                        "snippet": "来源摘要",
                                    }
                                ]
                            },
                        }
                    ],
                    "usage": {"input_tokens": 12, "output_tokens": 6},
                },
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = DoubaoGeoAdapter(
            ProviderSettings(
                credential=SecretStr("not-a-real-secret"),
                model="doubao-test",
                max_retries=0,
            ),
            client=client,
        )
        try:
            return await adapter.observe(GeoProviderRequest("q", 1, "问题"))
        finally:
            await client.aclose()

    result = __import__("asyncio").run(scenario())
    assert result.answer_text == "联网回答"
    assert [item.domain for item in result.citations] == ["source.example"]
    assert result.usage == GeoProviderUsage(
        input_tokens=12,
        output_tokens=6,
        search_requests=1,
    )
    capability = capability_from_observation(
        _Adapter(GeoPlatformCode.DOUBAO),
        GeoObservation(
            platform_code=GeoPlatformCode.DOUBAO,
            question_id="q",
            sample_no=1,
            status=GeoObservationStatus.SUCCEEDED,
            answer_text=result.answer_text,
            citations=result.citations,
            provider=result.provider,
            model=result.model,
            started_at=datetime.now(UTC),
            finished_at=datetime.now(UTC),
            latency_ms=result.latency_ms,
            usage=result.usage,
            provider_call_count=result.provider_call_count,
        ),
    )
    assert capability.status is GeoCapabilityStatus.DIRECT_SEARCH_WITH_CITATIONS


def test_partial_failure_synthesis_keeps_inputs_immutable_and_missing_sample() -> None:
    now = datetime.now(UTC)
    observations = (
        GeoObservation(
            platform_code=GeoPlatformCode.DOUBAO,
            question_id="1900012345678901001",
            sample_no=1,
            status=GeoObservationStatus.SUCCEEDED,
            answer_text="真实样本",
            provider="DOUBAO",
            model="model-a",
            started_at=now,
            finished_at=now,
            latency_ms=1,
            provider_call_count=1,
        ),
        GeoObservation(
            platform_code=GeoPlatformCode.DEEPSEEK,
            question_id="1900012345678901001",
            sample_no=1,
            status=GeoObservationStatus.FAILED,
            provider="DEEPSEEK",
            model="deepseek-chat",
            started_at=now,
            finished_at=now,
            latency_ms=0,
            stable_error_code="GEO_DEEPSEEK_ONLINE_SEARCH_UNSUPPORTED",
            provider_call_count=0,
        ),
    )
    before = tuple(item.model_dump_json() for item in observations)
    gateway = _DeepSeekSynthesisGateway()

    from typing import cast

    synthesis = __import__("asyncio").run(
        GeoSynthesizer(cast(ModelGateway, gateway)).synthesize(observations)
    )

    assert tuple(item.model_dump_json() for item in observations) == before
    assert synthesis.missing_samples == ("1900012345678901001:DEEPSEEK:1",)
    assert synthesis.method_version == "geo-synthesis-v1"
    assert gateway.messages is not None
    assert "untrusted data" in gateway.messages[0].content
    assert '"status":"FAILED"' in gateway.messages[1].content
    assert '"startedAt"' not in gateway.messages[1].content


def test_synthesis_derives_missing_units_from_observations_not_model_output() -> None:
    now = datetime.now(UTC)
    failed = GeoObservation(
        platform_code=GeoPlatformCode.WENXIN,
        question_id="1900012345678901002",
        sample_no=1,
        status=GeoObservationStatus.FAILED,
        provider="WENXIN",
        model="model-b",
        started_at=now,
        finished_at=now,
        latency_ms=1,
        stable_error_code="GEO_PROVIDER_TIMEOUT",
        provider_call_count=1,
    )
    gateway = _DeepSeekSynthesisGateway()

    from typing import cast

    synthesis = __import__("asyncio").run(
        GeoSynthesizer(cast(ModelGateway, gateway)).synthesize((failed,))
    )

    assert synthesis.missing_samples == ("1900012345678901002:WENXIN:1",)
    assert synthesis.method_version == "geo-synthesis-v1"


def test_capability_and_logs_expose_no_question_secret_or_full_sensitive_id(
    caplog: LogCaptureFixture,
) -> None:
    secret_question = "SENSITIVE_QUESTION_BODY"
    full_question_id = "geo-private-identifier-12345678"

    async def scenario() -> GeoObservation:
        adapter = _Adapter(GeoPlatformCode.DOUBAO)
        return await GeoSampleExecutor(GeoProviderRegistry([adapter])).execute(
            platform_code=GeoPlatformCode.DOUBAO,
            question_id=full_question_id,
            sample_no=1,
            question=secret_question,
            max_provider_calls=1,
            deadline_at=_deadline(),
        )

    with caplog.at_level(logging.INFO, logger="app.geo.runtime"):
        observation = __import__("asyncio").run(scenario())
    capability = capability_from_observation(_Adapter(GeoPlatformCode.DOUBAO), observation)

    assert capability.direct_api_available is True
    serialized_logs = "\n".join(
        json.dumps(record.__dict__, ensure_ascii=False, default=str) for record in caplog.records
    )
    assert secret_question not in serialized_logs
    assert full_question_id not in serialized_logs
    assert "not-a-real-secret" not in serialized_logs
