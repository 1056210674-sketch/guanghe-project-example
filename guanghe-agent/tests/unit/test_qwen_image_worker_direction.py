"""Frozen Java user requirements survive the Worker projection into real Qwen HTTP."""

import asyncio
import json
from copy import deepcopy
from typing import cast
from uuid import uuid4

import httpx
import pytest

from app.providers.models import TaskProviderError, TaskProviderRequest
from app.providers.qwen_image import QwenImageAdapter
from app.workers.control_plane import ExecutionContext
from app.workers.java_control_plane import JavaWorkerControlPlane
from tests.internal_security import TEST_TRACEPARENT
from tests.unit.test_image_visual_brief import visual_brief
from tests.unit.test_java_worker_control_plane import (
    EXECUTION_TOKEN,
    SUBMIT_REQUEST_ID,
    TASK_ID,
    _context_payload,  # pyright: ignore[reportPrivateUsage]
    _FakeWorkerJavaClient,  # pyright: ignore[reportPrivateUsage]
    _response,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_qwen_image_adapter import _settings  # pyright: ignore[reportPrivateUsage]


async def _worker_request(payload: dict[str, object]) -> TaskProviderRequest:
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)
    context = await JavaWorkerControlPlane(client).get_execution_context(
        TASK_ID,
        execution_no=1,
        execution_token=EXECUTION_TOKEN,
        traceparent=TEST_TRACEPARENT,
    )
    assert isinstance(context, ExecutionContext)
    # Match WorkerRuntime's projection, rather than restoring fields for the test.
    assert "requestText" not in context.parameters
    return TaskProviderRequest(
        request_id=uuid4(),
        task_id=context.task_id,
        task_type=context.task_type,
        execution_no=context.execution_no,
        parameters=context.parameters,
        creative_brief=context.creative_brief,
        reference_assets=context.reference_assets,
        controlled_mask_asset=context.controlled_mask_asset,
        traceparent=TEST_TRACEPARENT,
    )


def _image_payload(source_mode: str, request_text: str) -> dict[str, object]:
    payload = _context_payload()
    snapshot = cast(dict[str, object], payload["inputSnapshot"])
    brief = cast(dict[str, object], payload["productCreativeBrief"])
    visual = {**visual_brief(), "textPolicy": "USER_SUPPLIED", "copyLines": ["春日上新"]}
    snapshot.update(
        requestText=request_text,
        sourceMode=source_mode,
        scene="展示马克杯的桌面场景",
        style="简洁商品摄影",
        visualBrief=visual,
    )
    brief.update(
        sourceMode=source_mode,
        creativeDirection={
            **snapshot,
            # Control-plane metadata may exist in the frozen envelope, but is
            # not an image requirement and must never enter the text prompt.
            "providerReadUrl": "https://asset.test/private?signature=direction-secret",
            "internalNote": "INTERNAL_DIRECTION_CANARY",
        },
    )
    if source_mode != "CATALOG_PRODUCT":
        brief.update(productId=None, productFacts=None)
    if source_mode == "TEXT_ONLY":
        snapshot["referenceAssetIds"] = []
        cast(dict[str, object], brief["creativeDirection"])["referenceAssetIds"] = []
        brief["referenceAssetIds"] = []
        payload["referenceAssets"] = []
    return payload


def _success() -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "request_id": SUBMIT_REQUEST_ID,
            "output": {
                "task_id": "synthetic-image-result",
                "task_status": "SUCCEEDED",
                "choices": [{"message": {"content": [{"image": "https://result.test/image.png"}]}}],
            },
            "usage": {"output_image_count": 1, "output_width": 1024, "output_height": 1024},
        },
    )


@pytest.mark.parametrize(
    "source_mode", ["CURRENT_ATTACHMENTS", "PREVIOUS_IMAGE_RESULT", "CATALOG_PRODUCT", "TEXT_ONLY"]
)
def test_worker_user_copy_font_layout_and_exclusions_reach_actual_qwen_http(
    source_mode: str,
) -> None:
    original = "改为正方形，内容不变。标题逐字写“春日上新”，用宋体左对齐，禁用紫红色和促销角标。"
    payload = _image_payload(source_mode, original)
    seen: list[dict[str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        body = json.loads(request.content)
        seen.append(body)
        content = body["input"]["messages"][0]["content"]
        prompt = content[-1]["text"]
        assert original in prompt
        assert prompt.count(original) == 1
        assert "宋体左对齐" in prompt and "禁用紫红色和促销角标" in prompt
        assert prompt.count("commerce-image-v1") == 1
        assert "不得重绘成另一款商品" in prompt
        assert "不得声称销量第一" in prompt
        for excluded in (
            "INTERNAL_DIRECTION_CANARY",
            "direction-secret",
            "https://asset.test",
            "1900012345678901300",
            "1900012345678901200",
            "1900012345678901003",
            "1900012345678901004",
        ):
            assert excluded not in prompt
        assert len(content) == (1 if source_mode == "TEXT_ONLY" else 2)
        if source_mode != "TEXT_ONLY":
            assert content[0]["image"] == "https://asset.test/product.png?signature=secret"
        assert body["parameters"]["size"] == "1024*1024"
        return _success()

    async def scenario() -> None:
        request = await _worker_request(payload)
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = QwenImageAdapter(_settings(), client=client)
            result = await adapter.execute(request)
            assert result.usage.images == 1

    asyncio.run(scenario())
    assert len(seen) == 1


def test_frozen_original_is_not_replaced_by_synthesized_prompt() -> None:
    original = "内容不变，保留旧包装，禁用红底，只改字体为楷体。"
    payload = _image_payload("CURRENT_ATTACHMENTS", original)
    snapshot = cast(dict[str, object], payload["inputSnapshot"])
    snapshot.update(prompt="推测的红色背景", scene="推测的新包装")

    def handler(request: httpx.Request) -> httpx.Response:
        prompt = json.loads(request.content)["input"]["messages"][0]["content"][-1]["text"]
        assert original in prompt
        assert prompt.index(original) < prompt.index("推测的红色背景")
        assert "不能覆盖用户原始要求" in prompt
        assert "不是商品事实" in prompt
        return _success()

    async def scenario() -> None:
        request = await _worker_request(payload)
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            await QwenImageAdapter(_settings(), client=client).execute(request)

    asyncio.run(scenario())


def test_frozen_original_keeps_existing_total_prompt_limit_without_silent_truncation() -> None:
    payload = _image_payload("CURRENT_ATTACHMENTS", "保留指定文字。" * 2_500)

    def reject_network(_: httpx.Request) -> httpx.Response:
        raise AssertionError("an oversized composed prompt must fail before submission")

    async def scenario() -> None:
        request = await _worker_request(payload)
        async with httpx.AsyncClient(transport=httpx.MockTransport(reject_network)) as client:
            adapter = QwenImageAdapter(_settings(), client=client)
            with pytest.raises(TaskProviderError, match="QWEN_IMAGE_PROMPT_TOO_LONG"):
                await adapter.execute(request)

    asyncio.run(scenario())


def test_geo_worker_uses_signed_visual_plan_without_raw_direction_or_report_ids() -> None:
    payload = _image_payload("GEO_REPORT", "RAW_GEO_TEXT_MUST_NOT_OVERRIDE_SIGNED_PLAN")
    plan = {
        "version": "geo-visual-plan-v1",
        "taskType": "IMAGE",
        "reportId": "1900012345678901999",
        "recommendationId": None,
        "proof": "v1." + "A" * 43,
        "opportunity": "通勤场景展示不足",
        "audience": "通勤用户",
        "message": "展示杯子外观和携带情境",
        "evidenceKeys": ["e1"],
        "scene": "清晨桌面上的陶瓷马克杯",
        "visualFocus": "真实杯体外观",
        "shotBeats": [],
        "limitations": ["有限样本，不能宣称排名改善"],
    }
    snapshot = cast(dict[str, object], payload["inputSnapshot"])
    snapshot["geoReportId"] = plan["reportId"]
    brief = cast(dict[str, object], payload["productCreativeBrief"])
    baseline_brief = cast(dict[str, object], _context_payload()["productCreativeBrief"])
    brief.update(productId=baseline_brief["productId"], productFacts=baseline_brief["productFacts"])
    cast(dict[str, object], brief["creativeDirection"])["creativePlan"] = deepcopy(plan)

    def handler(request: httpx.Request) -> httpx.Response:
        prompt = json.loads(request.content)["input"]["messages"][0]["content"][-1]["text"]
        assert plan["scene"] in prompt
        assert "RAW_GEO_TEXT_MUST_NOT_OVERRIDE_SIGNED_PLAN" not in prompt
        assert plan["reportId"] not in prompt
        assert plan["proof"] not in prompt
        return _success()

    async def scenario() -> None:
        request = await _worker_request(payload)
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            await QwenImageAdapter(_settings(), client=client).execute(request)

    asyncio.run(scenario())
