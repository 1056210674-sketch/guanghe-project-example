"""Signed, saved-evidence recovery through the actual gateway HTTP envelope; zero quota."""

import asyncio
import copy
import json
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.api.application import create_app
from app.common.enums import ProviderStatus
from app.common.settings import ProviderSettings
from app.geo.recovery import GeoSynthesisRecoveryRequest, regenerate_synthesis
from app.geo.runtime import GeoSynthesisError
from app.model_gateway.deepseek import DeepSeekAdapter
from tests.internal_security import FakeInternalNonceStore, internal_settings, signed_headers

_PATH = "/internal/v1/geo/syntheses"
_CANARY = "PRIVATE_EVIDENCE_DO_NOT_LOG"


def _payload() -> dict[str, Any]:
    now = datetime.now(UTC).isoformat()
    observation = {
        "platformCode": "DEEPSEEK",
        "questionId": "101",
        "sampleNo": 1,
        "status": "SUCCEEDED",
        "answerText": f"商品A适合通勤。{_CANARY}",
        "citations": [{"url": "https://evidence.example/a", "observedAt": now}],
        "provider": "DEEPSEEK",
        "model": "saved-model",
        "startedAt": now,
        "finishedAt": now,
        "latencyMs": 1,
        "providerCallCount": 2,
    }
    failed = {
        **observation,
        "platformCode": "DOUBAO",
        "provider": "DOUBAO",
        "status": "FAILED",
        "answerText": None,
        "citations": [],
        "stableErrorCode": "GEO_PROVIDER_TIMEOUT",
    }
    return {
        "jobId": "recovery-1",
        "attempt": 1,
        "inputHash": "a" * 64,
        "deadlineAt": (datetime.now(UTC) + timedelta(seconds=60)).isoformat(),
        "maxProviderCalls": 2,
        "authorizedContext": {
            "topic": {
                "id": "201",
                "spaceId": "301",
                "productId": "401",
                "name": "通勤场景专题",
                "region": "全国",
                "version": 1,
            },
            "questions": [
                {"id": str(i), "category": "BRAND_DISCOVERY", "text": f"通勤问题{i}"}
                for i in (101, 102, 103)
            ],
            "competitors": [],
            "monitoringTarget": {"kind": "PRODUCT", "name": "商品A", "aliases": []},
        },
        "observations": [observation, failed],
        "evidenceProjection": {
            "answerCharLimit": 6000,
            "maxCitationsPerObservation": 3,
            "truncatedAnswerCount": 0,
            "omittedCitationCount": 0,
        },
        "visibilityEvidence": {
            "targetStatus": "AVAILABLE",
            "matchingVersion": "explicit-name-mention-v1",
            "units": [
                {
                    "questionId": "101",
                    "platformCode": "DEEPSEEK",
                    "sampleNo": 1,
                    "targetMentioned": True,
                },
                {
                    "questionId": "101",
                    "platformCode": "DOUBAO",
                    "sampleNo": 1,
                    "targetMentioned": None,
                },
            ],
        },
    }


def _analysis() -> dict[str, Any]:
    return {
        "summary": "已保存回答提到商品A；豆包未完成，不能据此推断可见度下降。",
        "coverageGaps": ["豆包样本未完成"],
        "citationStructure": ["一个成功回答带来源"],
        "competitorSignals": [],
        "competitorCandidates": [],
        "findings": ["成功回答提到通勤用途"],
        "recommendations": [],
        "contentBlueprint": [],
    }


def _response(output: dict[str, Any]) -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "choices": [
                {
                    "finish_reason": "tool_calls",
                    "message": {
                        "content": None,
                        "tool_calls": [
                            {
                                "type": "function",
                                "function": {
                                    "name": "synthesize_geo_report",
                                    "arguments": json.dumps(output),
                                },
                            }
                        ],
                    },
                }
            ],
            "usage": {"prompt_tokens": 20, "completion_tokens": 10},
        },
    )


def _gateway(client: httpx.AsyncClient, retries: int = 0) -> DeepSeekAdapter:
    return DeepSeekAdapter(
        ProviderSettings(
            credential=SecretStr("test-only"),
            model="deepseek-test",
            max_retries=retries,
        ),
        client=client,
    )


def _post(client: TestClient, payload: dict[str, Any]) -> Any:
    body = json.dumps(payload, ensure_ascii=False).encode()
    return client.post(
        _PATH,
        content=body,
        headers={
            **signed_headers("POST", _PATH, body=body),
            "Content-Type": "application/json",
        },
    )


def test_signed_recovery_uses_eight_field_tool_preserves_system_on_repair_and_real_usage() -> None:
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/chat/completions"
        requests.append(json.loads(request.content))
        return _response({"summary": "missing fields"} if len(requests) == 1 else _analysis())

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    payload = _payload()
    original = copy.deepcopy(payload)
    with TestClient(app) as client:
        app.state.geo_visual_gateway = _gateway(transport)
        result = _post(client, payload)
    asyncio.run(transport.aclose())
    assert result.status_code == 200, result.text
    assert payload == original
    assert len(requests) == 2
    first, repair = requests
    assert first["tool_choice"] == {
        "type": "function",
        "function": {"name": "synthesize_geo_report"},
    }
    schema = first["tools"][0]["function"]["parameters"]
    assert set(schema["properties"]) == set(_analysis()) == set(schema["required"])
    assert schema["additionalProperties"] is False
    assert "response_format" not in first
    assert first["thinking"] == {"type": "disabled"}
    assert first["messages"][0]["content"] in repair["messages"][0]["content"]
    assert "monitoringTarget" in repair["messages"][0]["content"]
    assert "[STRUCTURED OUTPUT REPAIR]" in repair["messages"][0]["content"]
    assert first["messages"][1] == repair["messages"][1]
    evidence = json.loads(first["messages"][1]["content"])
    assert (
        evidence["evidenceProjection"]["savedEvidenceProjection"] == payload["evidenceProjection"]
    )
    assert len(evidence["observations"]) == 2
    output = result.json()
    assert output["jobId"] == payload["jobId"]
    assert output["inputHash"] == payload["inputHash"]
    assert output["synthesis"]["missingSamples"] == ["101:DOUBAO:1"]
    assert output["synthesis"]["methodVersion"] == "geo-synthesis-v1"
    assert output["synthesisUsage"]["provider"] == "DEEPSEEK"
    assert output["synthesisUsage"]["providerCallCount"] == 2
    assert output["synthesisUsage"]["inputTokens"] == 40
    assert output["synthesisUsage"]["outputTokens"] == 20


def test_invalid_output_stops_after_one_repair_with_safe_diagnostics_no_fallback(
    capsys: pytest.CaptureFixture[str],
) -> None:
    requests = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        return _response({**_analysis(), "recommendations": [_CANARY]})

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    with TestClient(app) as client:
        app.state.geo_visual_gateway = _gateway(transport)
        result = _post(client, _payload())
    asyncio.run(transport.aclose())
    assert result.status_code == 503
    assert result.json()["detail"]["code"] == "DEEPSEEK_GEO_SYNTHESIS_RESPONSE_INVALID"
    assert "synthesis" not in result.json()
    assert requests == 2
    rendered = capsys.readouterr().out
    assert '"path":"recommendations.[]"' in rendered
    assert _CANARY not in rendered + result.text
    assert "invalid_schema" in rendered


def test_merchant_projection_does_not_retry_model_for_valid_but_technical_prose() -> None:
    requests = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        return _response(
            {
                **_analysis(),
                "summary": "Java的targetMentioned=true，questionId=101；GEO_PROVIDER_TIMEOUT。",
            }
        )

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    with TestClient(app) as client:
        app.state.geo_visual_gateway = _gateway(transport)
        result = _post(client, _payload())
    asyncio.run(transport.aclose())
    assert result.status_code == 200
    assert requests == 1
    synthesis = result.json()["synthesis"]
    assert "Java" not in synthesis["summary"] and "GEO_PROVIDER_TIMEOUT" not in synthesis["summary"]
    assert "问题1" in synthesis["summary"]
    assert synthesis["missingSamples"] == ["101:DOUBAO:1"]
    assert synthesis["methodVersion"] == "geo-synthesis-v1"


def test_full_answer_mention_survives_middle_text_projection_and_is_not_recomputed() -> None:
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return _response(_analysis())

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    payload = _payload()
    payload["observations"][0]["answerText"] = "前文" * 1000 + "商品A" + "后文" * 1000
    with TestClient(app) as client:
        app.state.geo_visual_gateway = _gateway(transport)
        result = _post(client, payload)
    asyncio.run(transport.aclose())
    assert result.status_code == 200
    evidence = json.loads(requests[0]["messages"][1]["content"])
    assert "商品A" not in evidence["observations"][0]["answerText"]
    assert evidence["visibilityEvidence"] == payload["visibilityEvidence"]
    assert evidence["visibilityEvidence"]["units"][0]["targetMentioned"] is True
    system = requests[0]["messages"][0]["content"]
    assert "完整已保存回答" in system
    assert "visibilityEvidence 缺失时" in system
    assert "严禁断言完整回答未提及" in system


@pytest.mark.parametrize(
    "invalid",
    [
        "missing",
        "duplicate",
        "foreign",
        "failed_true",
        "success_null",
        "unresolved_true",
        "no_target",
    ],
)
def test_visibility_matrix_cannot_override_failed_or_unowned_units(invalid: str) -> None:
    payload = _payload()
    flags = payload["visibilityEvidence"]
    if invalid == "missing":
        del payload["visibilityEvidence"]
    elif invalid == "duplicate":
        flags["units"].append(flags["units"][0])
    elif invalid == "foreign":
        flags["units"][0]["questionId"] = "999"
    elif invalid == "failed_true":
        flags["units"][1]["targetMentioned"] = True
    elif invalid == "success_null":
        flags["units"][0]["targetMentioned"] = None
    elif invalid == "unresolved_true":
        flags["targetStatus"] = "TARGET_UNRESOLVED"
    else:
        payload["authorizedContext"]["monitoringTarget"] = None
    with TestClient(
        create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    ) as client:
        result = _post(client, payload)
    assert result.status_code == 422
    assert _CANARY not in result.text


@pytest.mark.parametrize(
    "mutation",
    [
        {"attempt": 3},
        {"attempt": True},
        {"maxProviderCalls": 3},
        {"inputHash": "raw"},
        {"deadlineAt": 123},
        {"deadlineAt": "2026-01-01T00:00:00"},
        {"extra": _CANARY},
    ],
)
def test_wire_rejects_invalid_limits_without_echoing_evidence(mutation: dict[str, Any]) -> None:
    with TestClient(
        create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    ) as client:
        result = _post(client, {**_payload(), **mutation})
    assert result.status_code == 422
    assert result.json()["detail"]["code"] == "GEO_SYNTHESIS_EVIDENCE_INVALID"
    assert _CANARY not in result.text


@pytest.mark.parametrize(
    "invalid", ["duplicate", "unowned", "provider", "empty", "answer_limit", "citation_limit"]
)
def test_saved_evidence_identity_and_size_rejected_before_model(invalid: str) -> None:
    payload = _payload()
    if invalid == "duplicate":
        payload["observations"].append(payload["observations"][0])
    elif invalid == "unowned":
        payload["observations"][0]["questionId"] = "999"
    elif invalid == "provider":
        payload["observations"][0]["provider"] = "DOUBAO"
    elif invalid == "empty":
        payload["observations"] = [payload["observations"][1]]
    elif invalid == "answer_limit":
        payload["evidenceProjection"]["answerCharLimit"] = 1
    else:
        payload["observations"][0]["citations"] *= 4
    with TestClient(
        create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    ) as client:
        result = _post(client, payload)
    assert result.status_code == 422
    assert _CANARY not in result.text


def test_route_requires_hmac_and_real_gateway_and_reuses_capacity() -> None:
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    with TestClient(app) as client:
        assert client.post(_PATH, json=_payload()).status_code == 401
        assert _post(client, _payload()).status_code == 503
        app.state.geo_visual_semaphore = asyncio.Semaphore(0)
        result = _post(client, _payload())
    assert result.status_code == 429
    assert result.json()["detail"]["code"] == "GEO_SYNTHESIS_CAPACITY_FULL"


def test_deadline_expiry_does_not_submit_and_error_code_is_preserved() -> None:
    payload = _payload()
    payload["deadlineAt"] = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
    transport = httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: pytest.fail("no request"))
    )
    with pytest.raises(GeoSynthesisError) as error:
        asyncio.run(
            regenerate_synthesis(
                GeoSynthesisRecoveryRequest.model_validate(payload),
                _gateway(transport),
                received_at=datetime.now(UTC),
            )
        )
    asyncio.run(transport.aclose())
    assert error.value.code == "GEO_SYNTHESIS_DEADLINE_EXCEEDED"


def test_unknown_historical_target_stays_unknown_without_blocking_saved_answer_analysis() -> None:
    payload = _payload()
    payload["authorizedContext"]["monitoringTarget"] = None
    payload["visibilityEvidence"]["targetStatus"] = "TARGET_UNRESOLVED"
    payload["visibilityEvidence"]["units"][0]["targetMentioned"] = None
    parsed = GeoSynthesisRecoveryRequest.model_validate(payload)
    assert parsed.visibility_evidence.target_status == "TARGET_UNRESOLVED"
    assert all(unit.target_mentioned is None for unit in parsed.visibility_evidence.units)


def test_request_deadline_caps_inflight_model_and_releases_shared_capacity() -> None:
    requests = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        await asyncio.sleep(1)
        return _response(_analysis())

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    with TestClient(app) as client:
        app.state.geo_visual_gateway = _gateway(transport)
        payload = _payload()
        payload["deadlineAt"] = (datetime.now(UTC) + timedelta(milliseconds=100)).isoformat()
        result = _post(client, payload)
        assert app.state.geo_visual_semaphore._value == 2
    asyncio.run(transport.aclose())
    assert requests == 1
    assert result.status_code == 504
    assert result.json()["detail"]["code"] == "GEO_SYNTHESIS_DEADLINE_EXCEEDED"


def test_transport_retries_and_structure_repair_share_two_call_budget() -> None:
    requests = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        return httpx.Response(429) if requests == 1 else _response({"summary": "incomplete"})

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    with TestClient(app) as client:
        app.state.geo_visual_gateway = _gateway(transport, retries=3)
        result = _post(client, _payload())
    asyncio.run(transport.aclose())
    assert requests == 2
    assert result.status_code == 503
    assert result.json()["detail"]["code"] == "MODEL_CALL_BUDGET_EXCEEDED"


def test_unknown_submission_is_not_blindly_retried_but_next_authorized_attempt_can_recover() -> (
    None
):
    requests = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        if requests == 1:
            raise httpx.ReadTimeout(_CANARY)
        return _response(_analysis())

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    gateway = _gateway(transport, retries=3)
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    with TestClient(app) as client:
        app.state.geo_visual_gateway = gateway
        first = _post(client, _payload())
        assert gateway.status is ProviderStatus.UNAVAILABLE
        second = _post(client, {**_payload(), "attempt": 2})
    asyncio.run(transport.aclose())
    assert first.status_code == 503
    assert first.json()["detail"]["code"] == "DEEPSEEK_SUBMISSION_UNKNOWN"
    assert _CANARY not in first.text
    assert second.status_code == 200
    assert requests == 2
