"""Bounded GEO creative planning without a real model or paid media request."""

import asyncio
import json
from typing import Any, cast

import pytest
from fastapi.testclient import TestClient
from pydantic import BaseModel, ValidationError

from app.api.application import create_app
from app.common.enums import ProviderStatus
from app.creative.geo_visual_plan import (
    GeoVisualPlanRequest,
    GeoVisualPlanUnavailable,
    create_geo_visual_plan,
    validated_frozen_plan,
)
from app.model_gateway.gateway import (
    GatewayUsage,
    ModelGateway,
    ModelGatewayError,
    ModelIdentity,
    ModelMessages,
)
from tests.internal_security import FakeInternalNonceStore, internal_settings, signed_headers

_PATH = "/internal/v1/creative/geo-plans"


def _request_payload() -> dict[str, Any]:
    return {
        "taskType": "IMAGE",
        "report": {
            "reportId": "1900012345678901999",
            "methodVersion": "geo-synthesis-v1",
            "summary": "仅有限样本中的通勤场景缺口。REPORT_ONLY_CANARY",
            "status": "PARTIAL",
            "sampleSize": 3,
            "recommendation": None,
            "coverageGaps": ["通勤场景缺口"],
            "findings": ["样本提问关注携带方式"],
            "contentBlueprint": ["展示真实外观"],
            "missingSamples": ["一个平台未返回"],
        },
        "evidence": [
            {
                "key": "e1",
                "questionText": "咖啡杯如何携带？",
                "platformCode": "DOUBAO",
                "answerExcerpt": "回答没有呈现通勤场景。",
            }
        ],
        "productFacts": {
            "name": "陶瓷马克杯",
            "category": "杯具",
            "modelSpec": None,
            "sellingPoints": [],
            "parameters": {},
            "audience": [],
            "usageScenarios": [],
        },
        "prohibitedClaims": ["不可宣称官方推荐"],
        "userDirection": "暖色晨光",
    }


def _plan_payload(*, evidence_keys: list[str] | None = None) -> dict[str, Any]:
    return {
        "version": "geo-visual-plan-v1",
        "taskType": "IMAGE",
        "opportunity": "通勤场景展示不足",
        "audience": "通勤用户",
        "message": "展示杯子外观和携带情境",
        "evidenceKeys": evidence_keys or ["e1"],
        "scene": "清晨桌面上的陶瓷马克杯",
        "visualFocus": "真实杯体外观",
        "shotBeats": [],
        "limitations": ["有限样本，不能宣称排名改善"],
    }


class _FakeStructuredGateway:
    identity = ModelIdentity(provider="DEEPSEEK", model="unit-test", version="1")
    is_mock = False
    status = ProviderStatus.AVAILABLE
    last_error_code: str | None = None

    def __init__(self, output: dict[str, Any] | None = None, *, fail: bool = False) -> None:
        self.output = output or _plan_payload()
        self.fail = fail
        self.calls: list[ModelMessages] = []

    async def complete_structured(
        self,
        messages: ModelMessages,
        response_model: type[BaseModel],
        *,
        operation: str,
        max_tokens: int,
        error_code: str,
        repair_instruction: str,
        repair_attempts: int = 1,
    ) -> tuple[BaseModel, GatewayUsage]:
        assert operation == "readonly_geo_visual_plan"
        assert max_tokens == 900
        assert error_code == "GEO_VISUAL_PLAN_RESPONSE_INVALID"
        assert repair_attempts == 0
        assert "evidenceKeys" in repair_instruction
        self.calls.append(messages)
        if self.fail:
            raise ModelGatewayError(error_code, structured_response_failure=True)
        return response_model.model_validate(self.output), GatewayUsage(request_count=1)


def test_plan_binds_evidence_and_uses_only_bounded_structured_call() -> None:
    gateway = _FakeStructuredGateway()
    gateway.status = ProviderStatus.CONFIGURED
    request = GeoVisualPlanRequest.model_validate(_request_payload())
    plan = asyncio.run(create_geo_visual_plan(request, cast(ModelGateway, gateway)))
    assert plan.scene == "清晨桌面上的陶瓷马克杯"
    assert len(gateway.calls) == 1
    assert gateway.calls[0][0].role == "system"
    assert gateway.calls[0][1].role == "user"
    assert "REPORT_ONLY_CANARY" in gateway.calls[0][1].content
    assert "Do not restate report sample counts" in gateway.calls[0][0].content
    assert "do not infer that no image exists" in gateway.calls[0][0].content
    assert "wire version remains geo-visual-plan-v1" in gateway.calls[0][0].content


def test_model_cannot_author_limitations_about_runtime_counts_or_missing_images() -> None:
    output = _plan_payload()
    output["limitations"] = ["18 个计划样本只有 9 个成功，商品缺少实拍参考图"]
    gateway = _FakeStructuredGateway(output)
    request = GeoVisualPlanRequest.model_validate(_request_payload())

    plan = asyncio.run(create_geo_visual_plan(request, cast(ModelGateway, gateway)))

    assert plan.limitations == ("使用边界由系统根据已授权报告和商品资料补充。",)
    assert plan.version == "geo-visual-plan-v1"
    assert plan.evidence_keys == ("e1",)
    assert output["limitations"] == ["18 个计划样本只有 9 个成功，商品缺少实拍参考图"]


def test_plan_rejects_unobserved_evidence_and_structured_failure() -> None:
    request = GeoVisualPlanRequest.model_validate(_request_payload())
    for gateway in (
        _FakeStructuredGateway(_plan_payload(evidence_keys=["unknown"])),
        _FakeStructuredGateway(fail=True),
    ):
        with pytest.raises(GeoVisualPlanUnavailable) as error:
            asyncio.run(create_geo_visual_plan(request, cast(ModelGateway, gateway)))
        assert str(error.value) == "GEO_VISUAL_PLAN_RESPONSE_INVALID"


def test_request_rejects_report_without_sufficient_evidence_and_extra_fields() -> None:
    wrong_method = _request_payload()
    wrong_method["report"]["methodVersion"] = "geo-deterministic-v1"
    no_evidence = _request_payload()
    no_evidence["evidence"] = []
    extra_claim = _request_payload()
    extra_claim["report"]["unreviewedClaim"] = "true"
    for payload in (wrong_method, no_evidence, extra_claim):
        with pytest.raises(ValidationError):
            GeoVisualPlanRequest.model_validate(payload)


def test_frozen_plan_requires_provenance_and_correct_media_shape() -> None:
    plan = _plan_payload()
    with pytest.raises(ValidationError):
        validated_frozen_plan(plan, "IMAGE")
    plan.update(reportId="1900012345678901999", recommendationId=None, proof="v1." + "A" * 43)
    assert validated_frozen_plan(plan, "IMAGE").report_id == "1900012345678901999"
    invalid_proof = {**plan, "proof": "v1.not-a-valid-signature"}
    with pytest.raises(ValidationError):
        validated_frozen_plan(invalid_proof, "IMAGE")
    with pytest.raises(ValueError):
        validated_frozen_plan(plan, "VIDEO")


def test_planner_endpoint_requires_internal_signature_and_fails_closed_without_model() -> None:
    body = json.dumps(_request_payload(), ensure_ascii=False).encode()
    with TestClient(
        create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    ) as client:
        unauthorized = client.post(_PATH, content=body)
        unavailable = client.post(
            _PATH,
            content=body,
            headers={
                **signed_headers("POST", _PATH, body=body),
                "Content-Type": "application/json",
            },
        )
    assert unauthorized.status_code == 401
    assert unavailable.status_code == 503
    assert unavailable.json()["detail"]["code"] == "GEO_VISUAL_PLAN_MODEL_UNAVAILABLE"
