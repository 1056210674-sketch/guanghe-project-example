"""Explicit media source modes and mask editing, with no real network or cost."""

import asyncio
import json
from copy import deepcopy
from pathlib import Path
from typing import Any, cast
from uuid import UUID

import httpx
import pytest
from pydantic import SecretStr, ValidationError

from app.common.enums import TaskType
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings, ProviderSettings
from app.creative.models import CreativeSourceMode
from app.harness.runtime import bind_provider_call_boundary
from app.providers.models import (
    QwenImageProviderContinuation,
    SubmissionState,
    TaskProviderError,
    TaskProviderRequest,
    WanImageEditProviderContinuation,
)
from app.providers.qwen_image import QwenImageAdapter
from app.providers.router import RoutedTaskProvider
from app.providers.wan_image_edit import WanImageEditAdapter
from app.providers.wan_image_edit_gate import WanImageEditGateError, execute_gate
from app.providers.wan_video import WanVideoAdapter
from app.workers.control_plane import ExecutionContext, LocalWorkerControlPlane
from app.workers.java_control_plane import (
    JavaWorkerControlPlane,
    _ImageTaskExecutionContextResponse,  # pyright: ignore[reportPrivateUsage]
)
from app.workers.runtime import WorkerRuntime
from app.workers.transport import LocalMessageBroker, RawDelivery
from tests.unit.test_java_worker_control_plane import (
    EVENT_ID,
    _context_payload,  # pyright: ignore[reportPrivateUsage]
    _FakeWorkerJavaClient,  # pyright: ignore[reportPrivateUsage]
    _message,  # pyright: ignore[reportPrivateUsage]
    _response,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_qwen_image_adapter import (
    _settings as _qwen_settings,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_wan_video_adapter import (
    _response as _video_response,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_wan_video_adapter import (
    _settings as _video_settings,  # pyright: ignore[reportPrivateUsage]
)

REQUEST_ID = "123e4567-e89b-42d3-a456-426614174101"
PLAN_PROOF = "v1." + "A" * 43


def _settings() -> ProviderSettings:
    return ProviderSettings(
        model="wanx2.1-imageedit",
        endpoint=WanImageEditAdapter.DEFAULT_ENDPOINT,
        credential=SecretStr("test-only-mask-key"),
        timeout_seconds=1,
    )


def _request_payload(mode: str, *, video: bool = False, count: int = 1) -> dict[str, Any]:
    references = [
        {
            "assetId": str(1900012345678901300 + index),
            "contentType": "image/png",
            "sizeBytes": 1024,
            "sha256": "a" * 64,
            "width": 1024,
            "height": 1024,
            "readUrl": f"https://assets.test/{index}.png?signature=private",
            "expiresAt": "2030-01-01T00:00:00Z",
        }
        for index in range(0 if mode == "TEXT_ONLY" else 3 if mode == "PRODUCT_MATERIALS" else 1)
    ]
    product = mode in {"PRODUCT_MATERIALS", "GEO_REPORT", "CATALOG_PRODUCT"}
    parameters: dict[str, Any] = {"scene": "把背景改为绿色", "count": count}
    if video:
        parameters = {"scene": "清晨使用咖啡杯", "durationSeconds": 5, "aspectRatio": "16:9"}
    direction: dict[str, Any] = {"requestText": parameters["scene"]}
    payload: dict[str, Any] = {
        "requestId": REQUEST_ID,
        "taskId": "creative-mode-test",
        "taskType": "VIDEO" if video else "IMAGE",
        "executionNo": 1,
        "parameters": parameters,
        "referenceAssets": references,
        "creativeBrief": {
            "sourceMode": mode,
            "productId": "1900012345678901200" if product else None,
            "productFacts": {"name": "陶瓷马克杯"} if product else None,
            "referenceAssetIds": [r["assetId"] for r in references],
            "creativeDirection": direction,
            "prohibitedClaims": [],
            "provenance": {
                "conversationId": None,
                "messageId": None,
                **(
                    {"referenceMessageId": "1900012345678901500"}
                    if mode == "PREVIOUS_USER_ATTACHMENT"
                    else {}
                ),
            },
        },
    }
    if mode == "REGION_EDIT":
        mask = {
            **references[0],
            "assetId": "1900012345678901400",
            "readUrl": "https://assets.test/mask.png",
        }
        payload["controlledMaskAsset"] = mask
        parameters["maskAssetId"] = mask["assetId"]
        direction["maskAssetId"] = mask["assetId"]
    if mode == "GEO_REPORT":
        parameters["geoReportId"] = "1900012345678901999"
        direction["requestText"] = "内部报告原文不可进入供应商提示词"
        direction["creativePlan"] = {
            "version": "geo-visual-plan-v1",
            "taskType": "VIDEO" if video else "IMAGE",
            "reportId": "1900012345678901999",
            "recommendationId": None,
            "proof": PLAN_PROOF,
            "opportunity": "缺少通勤使用场景",
            "audience": "通勤人群",
            "message": "展示陶瓷马克杯的日常使用",
            "evidenceKeys": ["sample-1"],
            "scene": "清晨通勤桌面上的陶瓷马克杯",
            "visualFocus": "杯子主体和桌面环境",
            "shotBeats": ["展示清晨桌面", "镜头聚焦杯子"] if video else [],
            "limitations": ["不得暗示平台推荐或提升排名"],
        }
    return payload


def _request(mode: str, **kwargs: Any) -> TaskProviderRequest:
    return TaskProviderRequest.model_validate(_request_payload(mode, **kwargs))


def _edit_response(count: int = 1) -> dict[str, Any]:
    return {
        "request_id": REQUEST_ID,
        "output": {
            "task_id": "mask-task-1",
            "task_status": "SUCCEEDED",
            "results": [{"url": f"https://results.test/{i}.image"} for i in range(count)],
        },
        "usage": {"image_count": count},
    }


@pytest.mark.parametrize(
    "mode",
    [
        "TEXT_ONLY",
        "SELECTED_ASSETS",
        "PREVIOUS_USER_ATTACHMENT",
        "PREVIOUS_IMAGE_RESULT",
        "GEO_REPORT",
    ],
)
def test_qwen_modes_are_explicit_not_missing_reference_fallback(mode: str) -> None:
    submissions: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        submissions.append(json.loads(request.content))
        return httpx.Response(400)

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = QwenImageAdapter(_qwen_settings(), client=client)
            with pytest.raises(TaskProviderError):
                await adapter.execute(_request(mode))

    asyncio.run(scenario())
    contents = submissions[0]["input"]["messages"][0]["content"]
    images = [item["image"] for item in contents if "image" in item]
    assert len(images) == (0 if mode == "TEXT_ONLY" else 1)
    assert any("text" in item for item in contents)
    if mode == "GEO_REPORT":
        prompt = next(item["text"] for item in contents if "text" in item)
        assert "清晨通勤桌面上的陶瓷马克杯" in prompt
        assert "内部报告原文不可进入供应商提示词" not in prompt
        assert PLAN_PROOF not in prompt
        assert "1900012345678901999" not in prompt


@pytest.mark.parametrize(
    "mode,media_type,media_count",
    [
        ("TEXT_ONLY", None, 0),
        ("SELECTED_ASSETS", "first_frame", 1),
        ("PREVIOUS_USER_ATTACHMENT", "first_frame", 1),
        ("PREVIOUS_IMAGE_RESULT", "first_frame", 1),
        ("PRODUCT_MATERIALS", "reference_image", 3),
        ("GEO_REPORT", "first_frame", 1),
    ],
)
def test_wan_video_modes_send_actual_upstream_media_semantics(
    mode: str, media_type: str | None, media_count: int
) -> None:
    submissions: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            submissions.append(json.loads(request.content))
        return httpx.Response(200, json=_video_response())

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            await WanVideoAdapter(_video_settings(), client=client).execute(
                _request(mode, video=True)
            )

    asyncio.run(scenario())
    assert len(submissions) == 1
    value = submissions[0]["input"]
    assert len(value.get("media", [])) == media_count
    if media_type is None:
        assert "media" not in value
    else:
        assert [item["type"] for item in value["media"]] == [media_type] * media_count
    if mode == "GEO_REPORT":
        assert "清晨通勤桌面上的陶瓷马克杯" in value["prompt"]
        assert "内部报告原文不可进入供应商提示词" not in value["prompt"]
        assert PLAN_PROOF not in value["prompt"]
        assert "1900012345678901999" not in json.dumps(submissions[0])
        assert "陶瓷马克杯" in value["prompt"]


@pytest.mark.parametrize("actual_count", [1, 2, 4])
def test_mask_edit_submits_true_mask_and_reports_only_measured_count(actual_count: int) -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if request.method == "POST":
            return httpx.Response(
                200,
                json={
                    "request_id": REQUEST_ID,
                    "output": {"task_id": "mask-task-1", "task_status": "PENDING"},
                },
            )
        return httpx.Response(200, json=_edit_response(actual_count))

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            result = await WanImageEditAdapter(_settings(), client=client).execute(
                _request("REGION_EDIT", count=4)
            )
        assert result.provider == "WAN_IMAGE_EDIT"
        assert result.model == "wanx2.1-imageedit"
        assert result.usage.images == actual_count
        assert result.usage.provider_cost is None and result.usage.currency is None
        for artifact in result.artifacts:
            assert (
                artifact.content_type is None
                and artifact.size_bytes is None
                and artifact.sha256 is None
            )
            assert artifact.metadata["width"] is None and artifact.metadata["height"] is None
            assert "outputImageType" not in artifact.metadata
            assert artifact.expires_at is not None

    asyncio.run(scenario())
    assert [call.method for call in calls] == ["POST", "GET"]
    body = json.loads(calls[0].content)
    assert body == {
        "model": "wanx2.1-imageedit",
        "input": {
            "function": "description_edit_with_mask",
            "prompt": "把背景改为绿色",
            "base_image_url": "https://assets.test/0.png?signature=private",
            "mask_image_url": "https://assets.test/mask.png",
        },
        "parameters": {"n": 4, "watermark": False},
    }
    assert calls[0].url.path == WanImageEditAdapter.ENDPOINT_PATH


@pytest.mark.parametrize(
    "mutation", ["no_mask", "dimensions", "oversize", "mime", "count", "prompt", "source"]
)
def test_invalid_region_request_is_rejected_before_network(mutation: str) -> None:
    payload = _request_payload("REGION_EDIT")
    if mutation == "no_mask":
        payload.pop("controlledMaskAsset")
    elif mutation == "dimensions":
        payload["controlledMaskAsset"]["width"] = 512
    elif mutation == "oversize":
        payload["controlledMaskAsset"]["sizeBytes"] = 10 * 1024 * 1024 + 1
    elif mutation == "mime":
        payload["controlledMaskAsset"]["contentType"] = "image/jpeg"
    elif mutation == "count":
        payload["parameters"]["count"] = 5
    elif mutation == "prompt":
        payload["parameters"]["scene"] = "字" * 801
    else:
        payload = _request_payload("SELECTED_ASSETS")
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(500)

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with pytest.raises(TaskProviderError) as captured:
                await WanImageEditAdapter(_settings(), client=client).execute(
                    TaskProviderRequest.model_validate(payload)
                )
            assert captured.value.submission_state is SubmissionState.NOT_SUBMITTED

    asyncio.run(scenario())
    assert not calls


def test_mask_timeout_unknown_and_resume_only_queries_same_identity() -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if request.method == "POST":
            raise httpx.ReadTimeout("secret response omitted", request=request)
        return httpx.Response(200, json=_edit_response())

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = WanImageEditAdapter(_settings(), client=client)
            request = _request("REGION_EDIT")
            with pytest.raises(TaskProviderError) as captured:
                await adapter.execute(request)
            assert captured.value.submission_state is SubmissionState.UNKNOWN
            assert captured.value.code.startswith("WAN_IMAGE_EDIT_")
            # Only Java can supply a recovered task identity after an unknown POST.
            continuation = WanImageEditProviderContinuation(
                provider="WAN_IMAGE_EDIT",
                model="wanx2.1-imageedit",
                provider_request_id=UUID(REQUEST_ID),
                provider_task_id="mask-task-1",
            )
            await adapter.resume(request, continuation)
            wrong = QwenImageProviderContinuation(
                provider="QWEN_IMAGE",
                model="qwen-image-3.0",
                provider_request_id=UUID(REQUEST_ID),
                provider_task_id="mask-task-1",
            )
            with pytest.raises(TaskProviderError):
                await adapter.resume(request, wrong)

    asyncio.run(scenario())
    assert [call.method for call in calls] == ["POST", "GET"]


def test_missing_region_adapter_never_falls_back_to_qwen() -> None:
    async def scenario() -> None:
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda _: httpx.Response(500))
        ) as client:
            qwen = QwenImageAdapter(_qwen_settings(), client=client)
            router = RoutedTaskProvider({TaskType.IMAGE: qwen})
            assert router.resolve(TaskType.IMAGE, CreativeSourceMode.TEXT_ONLY) is qwen
            assert (
                router.resolve(TaskType.IMAGE, CreativeSourceMode.PREVIOUS_USER_ATTACHMENT) is qwen
            )
            assert router.resolve(TaskType.IMAGE, CreativeSourceMode.PREVIOUS_IMAGE_RESULT) is qwen
            with pytest.raises(TaskProviderError, match="WAN_IMAGE_EDIT_NOT_CONFIGURED"):
                router.resolve(TaskType.IMAGE, CreativeSourceMode.REGION_EDIT)

    asyncio.run(scenario())


def _region_context() -> dict[str, Any]:
    payload = _context_payload()
    request = _request_payload("REGION_EDIT")
    payload.update(
        provider="WAN_IMAGE_EDIT",
        model="wanx2.1-imageedit",
        productCreativeBrief=request["creativeBrief"],
        referenceAssets=request["referenceAssets"],
        controlledMaskAsset=request["controlledMaskAsset"],
    )
    payload["inputSnapshot"] = {
        **request["parameters"],
        "sourceMode": "REGION_EDIT",
        "referenceAssetIds": request["creativeBrief"]["referenceAssetIds"],
    }
    payload["budget"] = {"maxProviderCalls": 61, "maxImages": 1}
    return payload


def _previous_image_context(*, recovery: bool = False) -> dict[str, Any]:
    payload = cast(dict[str, Any], _context_payload())
    request = _request_payload("PREVIOUS_IMAGE_RESULT")
    payload.update(
        executionNo=2 if recovery else 1,
        productCreativeBrief=request["creativeBrief"],
        referenceAssets=request["referenceAssets"],
        controlledMaskAsset=None,
        resumeOnly=recovery,
        providerContinuation=(
            {
                "resumeOnly": True,
                "provider": "QWEN_IMAGE",
                "model": "qwen-image-3.0",
                "providerRequestId": REQUEST_ID,
                "providerTaskId": "qwen-task-previous-image",
                "submissionState": "UNKNOWN",
            }
            if recovery
            else None
        ),
    )
    payload["inputSnapshot"] = {
        **request["parameters"],
        "sourceMode": "PREVIOUS_IMAGE_RESULT",
        "referenceAssetIds": request["creativeBrief"]["referenceAssetIds"],
    }
    payload["budget"] = {"maxProviderCalls": 1, "maxImages": 1}
    return payload


def test_java_consumer_keeps_provider_mask_and_source_bound_together() -> None:
    valid = _region_context()
    assert _ImageTaskExecutionContextResponse.model_validate(valid).provider == "WAN_IMAGE_EDIT"
    for field, value in [
        ("controlledMaskAsset", None),
        ("provider", "QWEN_IMAGE"),
        ("model", "qwen-image-3.0"),
    ]:
        broken = deepcopy(valid)
        broken[field] = value
        with pytest.raises(ValidationError):
            _ImageTaskExecutionContextResponse.model_validate(broken)
    qwen = _context_payload()
    qwen["controlledMaskAsset"] = valid["controlledMaskAsset"]
    with pytest.raises(ValidationError):
        _ImageTaskExecutionContextResponse.model_validate(qwen)


def test_previous_image_result_keeps_qwen_identity_without_a_mask() -> None:
    initial = _ImageTaskExecutionContextResponse.model_validate(_previous_image_context())
    assert (initial.provider, initial.model) == ("QWEN_IMAGE", "qwen-image-3.0")
    assert initial.controlled_mask_asset is None
    assert initial.budget.max_provider_calls == 1
    assert initial.provider_continuation is None

    recovered = _ImageTaskExecutionContextResponse.model_validate(
        _previous_image_context(recovery=True)
    )
    assert recovered.provider_continuation is not None
    assert (recovered.provider_continuation.provider, recovered.provider_continuation.model) == (
        "QWEN_IMAGE",
        "qwen-image-3.0",
    )

    wrong_provider = _previous_image_context(recovery=True)
    wrong_provider.update(provider="WAN_IMAGE_EDIT", model="wanx2.1-imageedit")
    wrong_provider["providerContinuation"] = {
        **wrong_provider["providerContinuation"],
        "provider": "WAN_IMAGE_EDIT",
        "model": "wanx2.1-imageedit",
    }
    with pytest.raises(ValidationError):
        _ImageTaskExecutionContextResponse.model_validate(wrong_provider)

    unexpected_mask = _previous_image_context()
    unexpected_mask["controlledMaskAsset"] = _region_context()["controlledMaskAsset"]
    with pytest.raises(ValidationError):
        _ImageTaskExecutionContextResponse.model_validate(unexpected_mask)


def test_java_consumer_accepts_only_frozen_geo_image_report_context() -> None:
    valid = cast(dict[str, Any], _context_payload())
    valid["inputSnapshot"]["sourceMode"] = "GEO_REPORT"
    valid["inputSnapshot"]["geoReportId"] = "1900012345678901999"
    valid["productCreativeBrief"]["sourceMode"] = "GEO_REPORT"
    valid["productCreativeBrief"]["creativeDirection"]["creativePlan"] = {
        "version": "geo-visual-plan-v1",
        "taskType": "IMAGE",
        "reportId": "1900012345678901999",
        "recommendationId": None,
        "proof": PLAN_PROOF,
        "opportunity": "缺少咖啡场景展示",
        "audience": "咖啡用户",
        "message": "展示杯子外形",
        "evidenceKeys": ["sample-1"],
        "scene": "杯子摆放在咖啡桌上",
        "visualFocus": "杯子外形",
        "shotBeats": [],
        "limitations": ["不夸大商品性能"],
    }
    assert _ImageTaskExecutionContextResponse.model_validate(valid).provider == "QWEN_IMAGE"
    broken = deepcopy(valid)
    del broken["inputSnapshot"]["geoReportId"]
    with pytest.raises(ValidationError):
        _ImageTaskExecutionContextResponse.model_validate(broken)
    broken = deepcopy(valid)
    del broken["productCreativeBrief"]["creativeDirection"]["creativePlan"]
    with pytest.raises(ValidationError):
        _ImageTaskExecutionContextResponse.model_validate(broken)


def test_geo_image_worker_rejects_legacy_plan_before_provider_submission() -> None:
    async def scenario() -> None:
        request = _request("GEO_REPORT")
        assert request.creative_brief is not None
        legacy_brief = request.creative_brief.model_copy(
            update={"creative_direction": {"requestText": "legacy raw report"}}
        )
        context = ExecutionContext(
            task_id="creative-mode-test",
            task_type=TaskType.IMAGE,
            execution_no=1,
            parameters=request.parameters,
            creative_brief=legacy_brief,
            reference_assets=request.reference_assets,
        )
        calls: list[httpx.Request] = []

        def handler(http_request: httpx.Request) -> httpx.Response:
            calls.append(http_request)
            return httpx.Response(500)

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            worker = WorkerRuntime(
                initialize_runtime(AppSettings(environment="test")),
                provider=QwenImageAdapter(_qwen_settings(), client=client),
                control_plane=LocalWorkerControlPlane(),
                broker=LocalMessageBroker(),
            )
            with pytest.raises(TaskProviderError, match="GEO_VISUAL_PLAN_INVALID"):
                worker._enforce_creative_mode(TaskType.IMAGE, context)  # pyright: ignore[reportPrivateUsage]
            assert not calls

    asyncio.run(scenario())


@pytest.mark.parametrize("mode", ["TEXT_ONLY", "SELECTED_ASSETS", "REGION_EDIT"])
def test_non_catalog_sources_cannot_smuggle_product_facts(mode: str) -> None:
    request = _request_payload(mode)
    request["creativeBrief"]["productFacts"] = {"name": "unrelated product"}
    request["creativeBrief"]["productId"] = "123"
    with pytest.raises(ValidationError):
        TaskProviderRequest.model_validate(request)


def test_mask_gate_retains_receipt_and_refuses_duplicate_submission(tmp_path: Path) -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json=_edit_response())

    async def scenario() -> None:
        receipt = tmp_path / "mask-gate.json"
        report = await execute_gate(
            _settings(), _request("REGION_EDIT"), receipt, transport=httpx.MockTransport(handler)
        )
        assert report["status"] == "PROVIDER_RESULT_RECEIVED"
        assert report["assetIngestionVerified"] is False
        assert report["unmaskedPixelsVerified"] is False
        with pytest.raises(WanImageEditGateError, match="ALREADY_EXISTS"):
            await execute_gate(
                _settings(),
                _request("REGION_EDIT"),
                receipt,
                transport=httpx.MockTransport(handler),
            )

    asyncio.run(scenario())
    assert [call.method for call in calls] == ["POST"]
    content = (tmp_path / "mask-gate.json").read_text()
    assert (
        "private" not in content and "把背景" not in content and "test-only-mask-key" not in content
    )


def test_mask_gate_recovers_poll_timeout_without_another_post(tmp_path: Path) -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if request.method == "POST":
            return httpx.Response(
                200,
                json={
                    "request_id": REQUEST_ID,
                    "output": {"task_id": "mask-task-1", "task_status": "PENDING"},
                },
            )
        if len(calls) == 2:
            raise httpx.ReadTimeout("not logged", request=request)
        return httpx.Response(200, json=_edit_response())

    async def scenario() -> None:
        receipt = tmp_path / "recover.json"
        transport = httpx.MockTransport(handler)
        with pytest.raises(TaskProviderError):
            await execute_gate(_settings(), _request("REGION_EDIT"), receipt, transport=transport)
        different = _request_payload("REGION_EDIT")
        different["parameters"]["scene"] = "changed"
        with pytest.raises(WanImageEditGateError, match="IDENTITY_MISMATCH"):
            await execute_gate(
                _settings(),
                TaskProviderRequest.model_validate(different),
                receipt,
                resume=True,
                transport=transport,
            )
        report = await execute_gate(
            _settings(), _request("REGION_EDIT"), receipt, resume=True, transport=transport
        )
        assert report["submissions"] == 0

    asyncio.run(scenario())
    assert [call.method for call in calls] == ["POST", "GET", "GET"]


def test_mask_gate_ambiguous_submit_without_identity_blocks_reexecution(tmp_path: Path) -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        raise httpx.ReadTimeout("not logged", request=request)

    async def scenario() -> None:
        receipt = tmp_path / "unknown.json"
        transport = httpx.MockTransport(handler)
        with pytest.raises(TaskProviderError):
            await execute_gate(_settings(), _request("REGION_EDIT"), receipt, transport=transport)
        with pytest.raises(WanImageEditGateError, match="RECONCILIATION_REQUIRED"):
            await execute_gate(
                _settings(), _request("REGION_EDIT"), receipt, resume=True, transport=transport
            )
        with pytest.raises(WanImageEditGateError, match="ALREADY_EXISTS"):
            await execute_gate(_settings(), _request("REGION_EDIT"), receipt, transport=transport)

    asyncio.run(scenario())
    assert len(calls) == 1


@pytest.mark.parametrize("timeout", [False, True])
def test_region_worker_routes_and_hands_off_actual_provider_identity(timeout: bool) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("00000000-0000-0000-0000-000000000000"):
            return httpx.Response(
                200,
                json={
                    "output": {
                        "task_id": "00000000-0000-0000-0000-000000000000",
                        "task_status": "UNKNOWN",
                    }
                },
            )
        if request.method == "POST":
            assert request.url.path == WanImageEditAdapter.ENDPOINT_PATH
            return httpx.Response(
                200,
                json={
                    "request_id": REQUEST_ID,
                    "output": {"task_id": "mask-task-1", "task_status": "PENDING"},
                },
            )
        if timeout:
            raise httpx.ReadTimeout("not logged", request=request)
        return httpx.Response(200, json=_edit_response())

    async def scenario() -> None:
        java = _FakeWorkerJavaClient()
        java.context_result = _response(_region_context())
        java.failure_event_result = _response({"accepted": True, "taskStatus": "RUNNING"})
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            router = RoutedTaskProvider(
                {TaskType.IMAGE: QwenImageAdapter(_qwen_settings(), client=client)},
                image_edit_adapter=WanImageEditAdapter(_settings(), client=client),
            )
            worker = WorkerRuntime(
                initialize_runtime(AppSettings(environment="test")),
                provider=router,
                control_plane=JavaWorkerControlPlane(java),
                broker=LocalMessageBroker(),
            )
            assert await worker.start()
            await worker.process_delivery(
                RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
            )
            snapshot = worker.get_task(EVENT_ID)
            assert snapshot is not None
            kind, _, payload, _ = java.calls[-1]
            if timeout:
                assert kind == "event"
                details = payload["details"]
                assert isinstance(details, dict)
                assert details["submissionState"] == "UNKNOWN"
                assert details["provider"] == "WAN_IMAGE_EDIT"
                assert details["model"] == "wanx2.1-imageedit"
                assert details["providerTaskId"] == "mask-task-1"
            else:
                assert snapshot.status.value == "RESULT_READY"
                assert kind == "result" and payload["provider"] == "WAN_IMAGE_EDIT"
            await worker.close()

    asyncio.run(scenario())


def test_mask_budget_exhaustion_preserves_identity_and_resumes_get_only() -> None:
    calls: list[httpx.Request] = []
    recovery = False

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if recovery:
            assert request.method == "GET"
            return httpx.Response(200, json=_edit_response())
        return httpx.Response(
            200,
            json={
                "request_id": REQUEST_ID,
                "output": {"task_id": "mask-task-1", "task_status": "RUNNING"},
            },
        )

    async def scenario() -> None:
        nonlocal recovery
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = WanImageEditAdapter(_settings(), client=client)
            adapter.POLL_INTERVAL_SECONDS = 0
            with (
                bind_provider_call_boundary(max_calls=2, deadline_at=None),
                pytest.raises(TaskProviderError) as captured,
            ):
                await adapter.execute(_request("REGION_EDIT"))
            error = captured.value
            assert error.code == "WAN_IMAGE_EDIT_PROVIDER_CALL_BUDGET_EXCEEDED"
            assert error.submission_state is SubmissionState.UNKNOWN
            assert error.provider_request_id == REQUEST_ID
            assert error.provider_task_id == "mask-task-1"
            recovery = True
            continuation = WanImageEditProviderContinuation(
                provider="WAN_IMAGE_EDIT",
                model="wanx2.1-imageedit",
                provider_request_id=UUID(REQUEST_ID),
                provider_task_id="mask-task-1",
            )
            with bind_provider_call_boundary(max_calls=1, deadline_at=None):
                result = await adapter.resume(_request("REGION_EDIT"), continuation)
            assert result.usage.images == 1

    asyncio.run(scenario())
    assert [call.method for call in calls] == ["POST", "GET", "GET"]
