"""Same-call competitor candidates require traceable, visible answer evidence."""

import asyncio
import json
from typing import Any
from uuid import UUID

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.api.application import create_app
from app.geo.competitor_discovery import verified_competitor_candidates
from app.geo.models import GeoCompetitorCandidate, GeoSynthesis, GeoSynthesisAnalysis
from app.geo.recovery import GeoSynthesisRecoveryRequest
from app.workers.java_control_plane import (
    _GeoCompletionRequest,  # pyright: ignore[reportPrivateUsage]
    _json_payload,  # pyright: ignore[reportPrivateUsage]
)
from tests.internal_security import FakeInternalNonceStore, internal_settings
from tests.unit.test_geo_synthesis_recovery import (
    _analysis,  # pyright: ignore[reportPrivateUsage]
    _gateway,  # pyright: ignore[reportPrivateUsage]
    _payload,  # pyright: ignore[reportPrivateUsage]
    _post,  # pyright: ignore[reportPrivateUsage]
    _response,  # pyright: ignore[reportPrivateUsage]
)


def _candidate(name: str, quote: str, unit: str = "101:DEEPSEEK:1") -> dict[str, Any]:
    return {
        "name": name,
        "entityKind": "BRAND",
        "evidence": [{"unitKey": unit, "quote": quote, "signal": "MENTION"}],
    }


def _verify(answer: str, candidates: list[dict[str, Any]]) -> tuple[GeoCompetitorCandidate, ...]:
    payload = _payload()
    payload["observations"][0]["answerText"] = answer
    payload["authorizedContext"]["monitoringTarget"]["aliases"] = ["自家别名"]
    request = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(payload))
    return verified_competitor_candidates(
        tuple(GeoCompetitorCandidate.model_validate_json(json.dumps(item)) for item in candidates),
        request.observations,
        request.authorized_context,
    )


@pytest.mark.parametrize(
    ("answer", "name", "quote", "unit"),
    [
        ("虎牌杯身较轻。", "虎牌", "虎牌杯身较轻。", "101:DOUBAO:1"),
        ("虎牌杯身较轻。", "虎牌", "虎牌杯身较轻。", "102:DEEPSEEK:1"),
        ("虎牌杯身较轻。", "虎牌", "虎牌保温更好。", "101:DEEPSEEK:1"),
        ("虎牌。中间文字。杯身轻。", "虎牌", "虎牌。杯身轻。", "101:DEEPSEEK:1"),
        ("[来源](https://example.test/虎牌)", "虎牌", "虎牌", "101:DEEPSEEK:1"),
        ('<a title="虎牌">网站</a>', "虎牌", "虎牌", "101:DEEPSEEK:1"),
        ('虎牌被提及。<a title="虎牌杯身轻">来源</a>', "虎牌", "虎牌杯身轻", "101:DEEPSEEK:1"),
        (
            "虎牌被提及。[来源](https://example.test/虎牌杯身轻)",
            "虎牌",
            "虎牌杯身轻",
            "101:DEEPSEEK:1",
        ),
        ("<div hidden><div>隐藏</div>虎牌</div>正常", "虎牌", "虎牌", "101:DEEPSEEK:1"),
        ("回答正文。\n参考资料\uff1a\n虎牌官网", "虎牌", "虎牌官网", "101:DEEPSEEK:1"),
        ("回答正文。\n1. [虎牌官网](https://example.test)", "虎牌", "虎牌官网", "101:DEEPSEEK:1"),
        ("Pineapple is mentioned.", "Apple", "Pineapple", "101:DEEPSEEK:1"),
        ("商品A较轻。", "商品A", "商品A较轻。", "101:DEEPSEEK:1"),
        ("自家别名较轻。", "自家别名", "自家别名较轻。", "101:DEEPSEEK:1"),
        ("京东保温杯售价199元。", "京东", "京东保温杯售价199元。", "101:DEEPSEEK:1"),
        ("京东保温杯售价199元。", "保温杯", "京东保温杯售价199元。", "101:DEEPSEEK:1"),
        ("建议访问shop.example.com。", "shop.example.com", "shop.example.com", "101:DEEPSEEK:1"),
        ("参考图片", "虎牌", "虎牌", "101:DEEPSEEK:1"),
    ],
)
def test_rejects_untrusted_candidate_locally(answer: str, name: str, quote: str, unit: str) -> None:
    assert _verify(answer, [_candidate(name, quote, unit)]) == ()


@pytest.mark.parametrize("entity_kind", ["BRAND", "PRODUCT"])
@pytest.mark.parametrize(
    "name",
    [
        "虎牌旗舰店",
        "虎牌专卖店",
        "虎牌专营店",
        "虎牌直营店",
        "虎牌官方店",
        "虎牌官方网店",
        "虎牌自营店",
        "虎牌官方商城",
        "东方商城",
        "东方购物中心",
        "东方购物广场",
        "东方百货商场",
        "都市新闻网",
        "都市新闻网站",
        "都市门户网站",
        "都市资讯网",
        "都市媒体网",
        "虎牌官方网站",
        "虎牌官网",
        "优品店铺",
        "优品购物平台",
        "人民日报",
        "新华社",
        "央视新闻",
        "人民网",
        "新华网",
        "中新网",
        "中国新闻网",
        "澎湃新闻",
        "腾讯新闻",
        "新浪新闻",
        "网易新闻",
        "搜狐新闻",
        "今日头条",
        "百度百科",
        "WWW.Example.com",
        "shop.example.cn",
    ],
)
def test_store_and_media_names_are_not_competitors_even_if_model_labels_them_products(
    name: str,
    entity_kind: str,
) -> None:
    quote = f"可以在{name}查看相关资料。"
    candidate = {**_candidate(name, quote), "entityKind": entity_kind}
    assert _verify(quote, [candidate]) == ()


@pytest.mark.parametrize(
    "name", ["京东京造", "商城牌保温杯", "旗舰店系列保温杯", "媒体杯", "山间咖啡店", "Acme2.0"]
)
def test_partial_store_or_media_words_do_not_erase_concrete_names(name: str) -> None:
    quote = f"回答提到了{name}。"
    verified = _verify(quote, [_candidate(name, quote)])
    assert [candidate.name for candidate in verified] == [name]


@pytest.mark.parametrize(
    "own_name,candidate_name,accepted",
    [
        ("禾序", "禾序保温杯", False),
        ("禾序", "和序保温杯", True),
        ("abc", "abc Pro", False),
        ("abc", "xabc", True),
        ("Apple", "Apple Watch", False),
        ("Apple", "Pineapple", True),
    ],
)
@pytest.mark.parametrize("as_alias", [False, True])
def test_own_extended_products_use_name_boundaries_not_loose_latin_substrings(
    own_name: str,
    candidate_name: str,
    accepted: bool,
    as_alias: bool,
) -> None:
    payload = _payload()
    quote = f"回答提到了{candidate_name}。"
    payload["observations"][0]["answerText"] = quote
    payload["authorizedContext"]["monitoringTarget"] = {
        "kind": "PRODUCT",
        "name": "自家商品" if as_alias else own_name,
        "aliases": [own_name] if as_alias else [],
    }
    request = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(payload))
    candidate = GeoCompetitorCandidate.model_validate_json(
        json.dumps(_candidate(candidate_name, quote))
    )
    verified = verified_competitor_candidates(
        (candidate,),
        request.observations,
        request.authorized_context,
    )
    assert bool(verified) is accepted


def test_valid_concrete_names_and_signals_keep_original_evidence_and_remove_duplicates() -> None:
    quote = "京东京造售价199元；**虎牌**杯身轻，但容量偏小。"
    first = _candidate("虎牌", quote)
    first["evidence"][0]["signal"] = "POSITIVE_ATTRIBUTE"
    first["evidence"].append({**first["evidence"][0], "unitKey": "101:DOUBAO:1"})
    second = _candidate("京东京造", quote)
    verified = _verify(quote, [first, first, second])
    assert [item.name for item in verified] == ["虎牌", "京东京造"]
    assert verified[0].evidence[0].quote == quote
    assert verified[0].evidence[0].signal == "POSITIVE_ATTRIBUTE"
    assert len(verified[0].evidence) == 1


def test_visible_markdown_link_and_negative_attribute_remain_exact() -> None:
    quote = "推荐关注[虎牌](https://example.test/products)，但虎牌容量偏小。"
    candidate = _candidate("虎牌", quote)
    candidate["evidence"][0]["signal"] = "NEGATIVE_ATTRIBUTE"
    verified = _verify(quote, [candidate])
    assert len(verified) == 1
    assert verified[0].evidence[0].quote == quote
    assert verified[0].evidence[0].signal == "NEGATIVE_ATTRIBUTE"


def test_zero_target_recovery_keeps_competitors_without_extra_call() -> None:
    requests: list[dict[str, Any]] = []
    quote = "虎牌杯身较轻。"
    candidate = _candidate("虎牌", quote)
    invalid = _candidate("京东", "京东")

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return _response({**_analysis(), "competitorCandidates": [candidate, invalid]})

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    payload = _payload()
    payload["observations"][0]["answerText"] = quote
    payload["visibilityEvidence"]["units"][0]["targetMentioned"] = False
    with TestClient(app) as client:
        app.state.geo_visual_gateway = _gateway(transport)
        result = _post(client, payload)
    asyncio.run(transport.aclose())
    assert result.status_code == 200, result.text
    synthesis = result.json()["synthesis"]
    assert synthesis["competitorCandidates"] == [candidate]
    assert synthesis["competitorDiscoveryVersion"] == "geo-competitor-discovery-v1"
    assert synthesis["recommendations"] == synthesis["contentBlueprint"] == []
    assert "公开名称" in synthesis["summary"]
    assert len(requests) == 1
    assert requests[0]["max_tokens"] == 4096
    assert result.json()["synthesisUsage"]["providerCallCount"] == 1
    schema = requests[0]["tools"][0]["function"]["parameters"]
    assert "competitorCandidates" in schema["required"]
    assert "competitorDiscoveryVersion" not in schema["properties"]


def test_missing_discovery_array_uses_only_existing_one_structural_repair() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        output = _analysis()
        if calls == 1:
            output.pop("competitorCandidates")
        return _response(output)

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    with TestClient(app) as client:
        app.state.geo_visual_gateway = _gateway(transport)
        result = _post(client, _payload())
    asyncio.run(transport.aclose())
    assert result.status_code == 200, result.text
    assert calls == 2
    assert result.json()["synthesis"]["competitorCandidates"] == []
    assert result.json()["synthesis"]["competitorDiscoveryVersion"] == "geo-competitor-discovery-v1"


@pytest.mark.parametrize("method", ["geo-synthesis-v1", "geo-deterministic-v1"])
def test_legacy_completion_canonical_shape_is_unchanged(method: str) -> None:
    old: dict[str, Any] = {**_analysis(), "missingSamples": [], "methodVersion": method}
    old.pop("competitorCandidates")
    synthesis = GeoSynthesis.model_validate_json(json.dumps(old))
    assert synthesis.competitor_candidates == ()
    assert synthesis.competitor_discovery_version is None
    request = _GeoCompletionRequest.model_validate(
        {
            "eventId": UUID("00000000-0000-0000-0000-000000000001"),
            "executionNo": 1,
            "executionToken": "a" * 32,
            "synthesis": synthesis,
            "synthesisUsage": {
                "provider": "SYSTEM",
                "model": "deterministic-geo-fallback-v1",
                "inputTokens": 0,
                "outputTokens": 0,
                "providerCallCount": 0,
                "latencyMs": 0,
            },
        }
    )
    assert _json_payload(request)["synthesis"] == old
    # Output OpenAPI remains strict, unlike a generic serializer returning a dict.
    schema = GeoSynthesis.model_json_schema(mode="serialization", by_alias=True)
    assert schema["additionalProperties"] is False
    assert "competitorCandidates" in schema["properties"]
    new = request.model_copy(
        update={
            "synthesis": synthesis.model_copy(
                update={
                    "method_version": "geo-synthesis-v1",
                    "competitor_discovery_version": "geo-competitor-discovery-v1",
                }
            )
        }
    )
    assert _json_payload(new)["synthesis"] == {
        **old,
        "methodVersion": "geo-synthesis-v1",
        "competitorCandidates": [],
        "competitorDiscoveryVersion": "geo-competitor-discovery-v1",
    }


def test_new_model_contract_strictness_and_provenance() -> None:
    old = _analysis()
    old.pop("competitorCandidates")
    with pytest.raises(ValidationError):
        GeoSynthesisAnalysis.model_validate_json(json.dumps(old))
    candidate = _candidate("虎牌", "虎牌")
    invalid_candidates: list[dict[str, Any]] = [
        {**candidate, "aliases": []},
        {**candidate, "evidence": [{**candidate["evidence"][0], "confidence": 0.9}]},
        {**candidate, "evidence": [{**candidate["evidence"][0], "unitKey": "999:UNKNOWN:1"}]},
    ]
    for invalid in invalid_candidates:
        with pytest.raises(ValidationError):
            GeoCompetitorCandidate.model_validate_json(json.dumps(invalid))
    with pytest.raises(ValidationError):
        GeoSynthesisAnalysis.model_validate_json(
            json.dumps(
                {
                    **_analysis(),
                    "competitorCandidates": [candidate] * 7,
                }
            )
        )
    with pytest.raises(ValidationError):
        GeoCompetitorCandidate.model_validate_json(
            json.dumps(
                {
                    **candidate,
                    "evidence": candidate["evidence"] * 3,
                }
            )
        )
    with pytest.raises(ValidationError):
        GeoSynthesis.model_validate_json(
            json.dumps(
                {
                    **_analysis(),
                    "competitorCandidates": [candidate],
                    "methodVersion": "geo-synthesis-v1",
                }
            )
        )
    with pytest.raises(ValidationError):
        GeoSynthesis.model_validate_json(
            json.dumps(
                {
                    **_analysis(),
                    "competitorDiscoveryVersion": "geo-competitor-discovery-v1",
                    "methodVersion": "geo-deterministic-v1",
                }
            )
        )
