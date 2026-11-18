"""Actual interpretation boundary, fixed platform identity and Java-owned full-text metrics."""

from __future__ import annotations

import asyncio
import copy
import json
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.api.application import create_app
from app.geo.merchant_text import PLATFORM_NAMES
from app.geo.models import GeoSynthesisAnalysis, GeoVisibilityEvidence
from app.geo.recovery import GeoSynthesisRecoveryRequest
from app.geo.synthesis_grounding import canonical_synthesis_facts, grounded_analysis_schema
from tests.internal_security import FakeInternalNonceStore, internal_settings
from tests.unit.test_geo_synthesis_recovery import (
    _analysis,  # pyright: ignore[reportPrivateUsage]
    _gateway,  # pyright: ignore[reportPrivateUsage]
    _payload,  # pyright: ignore[reportPrivateUsage]
    _post,  # pyright: ignore[reportPrivateUsage]
    _response,  # pyright: ignore[reportPrivateUsage]
)


def _three_zero_mentions() -> dict[str, Any]:
    payload = _payload()
    payload["observations"] = [
        {
            **payload["observations"][0],
            "questionId": str(question_id),
            "answerText": "回答只包含其他公开门店的名单。",
        }
        for question_id in (101, 102, 103)
    ]
    payload["visibilityEvidence"]["units"] = [
        {
            "questionId": str(question_id),
            "platformCode": "DEEPSEEK",
            "sampleNo": 1,
            "targetMentioned": False,
        }
        for question_id in (101, 102, 103)
    ]
    return payload


def _consistent_analysis() -> dict[str, Any]:
    return {
        **_analysis(),
        "summary": "DeepSeek 的 3 个有效回答中，0 个明确提及监测对象，"
        "名称提及可见度为 0%。这是本次样本，不等于排名或市场份额。",
        "coverageGaps": [],
        "findings": ["已有回答列出了其他公开门店。"],
    }


def _schema(payload: dict[str, Any]) -> type[GeoSynthesisAnalysis]:
    source = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(payload))
    facts = canonical_synthesis_facts(
        source.observations, source.authorized_context, source.visibility_evidence
    )
    return grounded_analysis_schema(facts)


@pytest.mark.parametrize("code", PLATFORM_NAMES)
def test_each_platform_accepts_its_canonical_alias_and_exact_verified_zero(code: str) -> None:
    payload = _three_zero_mentions()
    for item in payload["observations"]:
        item["platformCode"] = item["provider"] = code
    for unit in payload["visibilityEvidence"]["units"]:
        unit["platformCode"] = code
    _schema(payload).model_validate(
        {
            **_consistent_analysis(),
            "summary": f"{PLATFORM_NAMES[code]}({code})取得3个有效回答，"
            "0个明确提及目标，名称提及可见度为0%。不能据此推断市场份额或未来推荐。",
        }
    )


def _two_platforms(second_samples: int) -> dict[str, Any]:
    payload = _three_zero_mentions()
    payload["observations"] += [
        {**payload["observations"][index], "platformCode": "DOUBAO", "provider": "DOUBAO"}
        for index in range(second_samples)
    ]
    payload["visibilityEvidence"]["units"] += [
        {**payload["visibilityEvidence"]["units"][index], "platformCode": "DOUBAO"}
        for index in range(second_samples)
    ]
    return payload


@pytest.mark.parametrize(
    "count,summary",
    [
        (3, "DeepSeek和豆包各3个有效回答，共6个有效回答。"),
        (3, "DeepSeek和豆包均取得3个有效回答，本次共有6个有效回答。"),
        (1, "DeepSeek 3、豆包1，总计4个有效回答。"),
        (1, "DeepSeek的3个有效回答和豆包的1个有效回答均未明确提及监测名称。"),
        (3, "DeepSeek名称提及率为0%。无法确认是否将来会被推荐、被引用或市场份额增加。"),
        (3, "本次共6个有效回答，其中3条提及了竞品名单。"),
        (3, "希望下一次监测DeepSeek可见度达到100%。本次名称提及可见度为0%。"),
    ],
)
def test_mixed_platform_and_normal_business_caveats_are_not_false_rejections(
    count: int, summary: str
) -> None:
    _schema(_two_platforms(count)).model_validate({**_consistent_analysis(), "summary": summary})


@pytest.mark.parametrize(
    "summary",
    [
        "DeepSeek和豆包各2个有效回答，共4个有效回答。",
        "本次DeepSeek可见度100%，下一次继续监测。",
        "DeepSeek当前可见度100%但下一次继续观察。",
        "本次DeepSeek有2个有效回答，希望下一次补齐。",
    ],
)
def test_future_intention_does_not_bypass_current_observation_checks(summary: str) -> None:
    with pytest.raises(ValidationError):
        _schema(_two_platforms(3)).model_validate({**_consistent_analysis(), "summary": summary})


@pytest.mark.parametrize(
    "left,right",
    [(left, right) for left in PLATFORM_NAMES for right in PLATFORM_NAMES if left != right],
)
def test_every_registered_platform_rejects_cross_platform_alias(left: str, right: str) -> None:
    schema = _schema(_three_zero_mentions())
    output = {
        **_consistent_analysis(),
        "summary": f"{PLATFORM_NAMES[left]}\uff08{right}\uff09的回答。",
    }
    with pytest.raises(ValidationError, match="PLATFORM_IDENTITY_CONTRADICTION"):
        schema.model_validate(output)


@pytest.mark.parametrize(
    "field,claim",
    [
        ("summary", "本次仅取得豆包\uff08DeepSeek\uff093条成功回答。"),
        ("summary", "本次未提供完整的名称提及核验结果，只能判断节选。"),
        ("coverageGaps", "缺少完整的名称提及核验结果。"),
        ("findings", "不能据此断言完整回答未提及、可见度为0。"),
        ("findings", "DeepSeek取得2个有效回答。"),
        ("summary", "DeepSeek的可见度为100%。"),
        ("summary", "DeepSeek有1个明确提及监测对象。"),
        ("summary", "豆包取得3个成功回答，可见度为0%。"),
    ],
)
def test_verified_zero_report_cannot_deny_identity_flags_or_metrics(field: str, claim: str) -> None:
    output = {**_consistent_analysis(), field: claim if field == "summary" else [claim]}
    with pytest.raises(ValidationError):
        _schema(_three_zero_mentions()).model_validate(output)


def test_failed_answers_never_change_valid_denominator_and_matching_is_not_recomputed() -> None:
    payload = _payload()
    # The name is outside the model excerpt, but Java's saved full-answer flag is true.
    payload["observations"][0]["answerText"] = "节选里不包含商品的公开名称。"
    source = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(payload))
    facts = canonical_synthesis_facts(
        source.observations, source.authorized_context, source.visibility_evidence
    )
    deepseek, failed = facts.platforms
    assert (deepseek.valid, deepseek.mentioned, deepseek.rate) == (1, 1, 100)
    assert (failed.valid, failed.failed, failed.mentioned, failed.rate) == (0, 1, None, None)
    schema = grounded_analysis_schema(facts)
    schema.model_validate({**_analysis(), "summary": "DeepSeek有1个有效回答，名称提及率100%。"})
    with pytest.raises(ValidationError, match="MENTION_RATE_CONTRADICTION"):
        schema.model_validate({**_analysis(), "summary": "DeepSeek的名称提及率50%。"})


def test_half_percentage_rounding_matches_java_not_python_bankers_rounding() -> None:
    payload = _payload()
    payload["observations"] = [
        {**payload["observations"][0], "questionId": str(question_id)}
        for question_id in range(101, 133)
    ]
    # Local immutable matrix helper is independent from the public 10-question admission bound.
    from app.geo.models import GeoObservation

    observations = tuple(
        GeoObservation.model_validate_json(json.dumps(item)) for item in payload["observations"]
    )
    source = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(_payload()))
    flags = GeoVisibilityEvidence.model_validate(
        {
            "targetStatus": "AVAILABLE",
            "matchingVersion": "explicit-name-mention-v1",
            "units": [
                {
                    "questionId": item.question_id,
                    "platformCode": "DEEPSEEK",
                    "sampleNo": 1,
                    "targetMentioned": index == 0,
                }
                for index, item in enumerate(observations)
            ],
        }
    )
    facts = canonical_synthesis_facts(observations, source.authorized_context, flags)
    assert facts.platforms[0].rate == 3.13


def test_unresolved_or_missing_flags_are_not_zero_and_preserve_unknown_failed_units() -> None:
    source = GeoSynthesisRecoveryRequest.model_validate_json(json.dumps(_payload()))
    facts = canonical_synthesis_facts(source.observations, source.authorized_context, None)
    assert facts.target_status == "UNVERIFIED"
    assert all(item.mentioned is None and item.rate is None for item in facts.platforms)
    with pytest.raises(ValidationError, match="MENTION_RATE_CONTRADICTION"):
        grounded_analysis_schema(facts).model_validate(
            {**_analysis(), "summary": "DeepSeek可见度为0%。"}
        )
    grounded_analysis_schema(facts).model_validate(
        {**_analysis(), "summary": "提供的节选中未观察到目标，不能从节选判断全文。"}
    )


def test_api_repairs_real_fault_classes_once_and_returns_correct_full_answer_facts() -> None:
    requests: list[dict[str, Any]] = []
    payload = _three_zero_mentions()
    original = copy.deepcopy(payload)

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return _response(
            {
                **_consistent_analysis(),
                "summary": "本次取得豆包\uff08DeepSeek\uff093条回答，未提供完整名称提及核验结果。",
            }
            if len(requests) == 1
            else _consistent_analysis()
        )

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    with TestClient(app) as client:
        app.state.geo_visual_gateway = _gateway(transport)
        result = _post(client, payload)
    asyncio.run(transport.aclose())
    assert result.status_code == 200, result.text
    assert len(requests) == 2 and payload == original
    evidence = json.loads(requests[0]["messages"][1]["content"])
    assert evidence["canonicalFacts"]["platformNames"] == PLATFORM_NAMES
    assert evidence["canonicalFacts"]["assessmentStatus"] == "AVAILABLE"
    assert evidence["canonicalFacts"]["platforms"] == [
        {
            "platformCode": "DEEPSEEK",
            "platformName": "DeepSeek",
            "validSamples": 3,
            "failedSamples": 0,
            "mentionedSamples": 0,
            "mentionRate": 0.0,
        }
    ]
    assert all(item["platformName"] == "DeepSeek" for item in evidence["observations"])
    schema = requests[0]["tools"][0]["function"]["parameters"]
    assert set(schema["properties"]) == set(_analysis())
    assert "canonicalFacts" in schema["description"]
    summary = result.json()["synthesis"]["summary"]
    assert "3 个可核验的有效回答" in summary and "未明确提及" in summary
    assert "未提供" not in summary and "豆包" not in summary
    assert result.json()["synthesisUsage"]["providerCallCount"] == 2


def test_persistent_contradiction_fails_closed_with_two_calls_no_prose_leak(capsys: Any) -> None:
    calls = 0
    canary = "PRIVATE_MODEL_FAULT_DO_NOT_LOG"

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return _response(
            {**_consistent_analysis(), "findings": ["本次未提供完整的名称提及核验结果。" + canary]}
        )

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    with TestClient(app) as client:
        app.state.geo_visual_gateway = _gateway(transport)
        result = _post(client, _three_zero_mentions())
    asyncio.run(transport.aclose())
    assert result.status_code == 503 and calls == 2
    assert result.json()["detail"]["code"] == "DEEPSEEK_GEO_SYNTHESIS_RESPONSE_INVALID"
    assert "synthesis" not in result.json()
    assert canary not in capsys.readouterr().out + result.text
