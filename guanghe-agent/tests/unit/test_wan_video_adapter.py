"""Wan video adapter tests use MockTransport and never create paid tasks."""

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from typing import cast
from uuid import UUID, uuid4

import httpx
import pytest
from pydantic import JsonValue, SecretStr, ValidationError

from app.common.enums import ProviderStatus, TaskType
from app.common.settings import ProviderSettings
from app.harness.runtime import bind_provider_call_boundary
from app.providers.models import (
    ProviderErrorCategory,
    QwenImageProviderContinuation,
    SubmissionState,
    TaskProviderError,
    TaskProviderRequest,
    WanVideoProviderContinuation,
)
from app.providers.wan_video import WanVideoAdapter

ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis"
WORKSPACE_ENDPOINT = (
    "https://workspace-1.cn-beijing.maas.aliyuncs.com/"
    "api/v1/services/aigc/video-generation/video-synthesis"
)
PROVIDER_REQUEST_ID = UUID("11111111-1111-4111-8111-111111111111")
RESULT_REQUEST_ID = UUID("22222222-2222-4222-8222-222222222222")
TRACEPARENT = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"


def _settings(*, endpoint: str = ENDPOINT) -> ProviderSettings:
    return ProviderSettings(
        credential=SecretStr("test-only-dashscope-key"),
        model="wan3.0-video",
        endpoint=endpoint,
        timeout_seconds=1,
        probe_timeout_seconds=1,
    )


def _request(parameters: dict[str, JsonValue] | None = None) -> TaskProviderRequest:
    reference_id = "1900012345678901200"
    return TaskProviderRequest.model_validate_json(
        json.dumps(
            {
                "requestId": str(uuid4()),
                "taskId": "task-video-1",
                "taskType": TaskType.VIDEO,
                "executionNo": 1,
                "traceparent": TRACEPARENT,
                "parameters": parameters
                or {
                    "scene": "咖啡机在清晨厨房台面上工作",
                    "style": "电影级产品广告",
                    "durationSeconds": 5,
                    "aspectRatio": "16:9",
                    "cameraMotion": "缓慢推进",
                    "resolution": "720P",
                },
                "creativeBrief": {
                    "sourceMode": "CURRENT_ATTACHMENTS",
                    "productId": None,
                    "referenceAssetIds": [reference_id],
                    "productFacts": None,
                    "creativeDirection": {"requestText": "生成商品视频"},
                    "prohibitedClaims": [],
                    "provenance": {
                        "conversationId": "1900012345678901001",
                        "messageId": "1900012345678901002",
                    },
                },
                "referenceAssets": [
                    {
                        "assetId": reference_id,
                        "contentType": "image/png",
                        "sizeBytes": 1024,
                        "sha256": "a" * 64,
                        "width": 1024,
                        "height": 1024,
                        "readUrl": "https://asset.test/first-frame.png?signature=secret",
                        "expiresAt": "2030-09-14T10:00:00+08:00",
                    },
                ],
            },
        )
    )


async def _no_sleep(_: float) -> None:
    return None


def _connect_error(request: httpx.Request) -> httpx.RequestError:
    return httpx.ConnectError("connect", request=request)


def _read_timeout(request: httpx.Request) -> httpx.RequestError:
    return httpx.ReadTimeout("read", request=request)


def _make_video_url_unsafe(value: dict[str, object]) -> None:
    cast(dict[str, object], value["output"])["video_url"] = "http://unsafe.example/video.mp4"


def _make_video_count_invalid(value: dict[str, object]) -> None:
    cast(dict[str, object], value["usage"])["video_count"] = 2


def _make_duration_mismatch(value: dict[str, object]) -> None:
    usage = cast(dict[str, object], value["usage"])
    usage["duration"] = 8.0
    usage["output_video_duration"] = 8.0


def _response(
    *,
    status: str = "SUCCEEDED",
    request_id: UUID = PROVIDER_REQUEST_ID,
    task_id: str = "wan-task-1",
    duration: float = 5.0,
) -> dict[str, object]:
    output: dict[str, object] = {
        "task_id": task_id,
        "task_status": status,
    }
    response: dict[str, object] = {
        "request_id": str(request_id),
        "output": output,
    }
    if status == "SUCCEEDED":
        output["video_url"] = "https://dashscope-result.example/video.mp4?Expires=9999999999"
        response["usage"] = {
            "video_count": 1,
            "duration": duration,
            "input_video_duration": 0.0,
            "output_video_duration": duration,
            "fps": 30,
            "SR": 720,
            "ratio": "16:9",
        }
    return response


def _adapter(
    handler: Callable[[httpx.Request], httpx.Response],
    *,
    sleep: Callable[[float], Awaitable[None]] = _no_sleep,
    poll_timeout_seconds: float = 1,
    max_poll_attempts: int = 60,
) -> tuple[WanVideoAdapter, httpx.AsyncClient]:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return (
        WanVideoAdapter(
            _settings(),
            client=client,
            sleep=sleep,
            poll_interval_seconds=0.001,
            poll_timeout_seconds=poll_timeout_seconds,
            max_poll_attempts=max_poll_attempts,
        ),
        client,
    )


def test_execute_submits_once_polls_only_with_get_and_preserves_submit_identity(
    caplog: pytest.LogCaptureFixture,
) -> None:
    requests: list[httpx.Request] = []
    poll_responses = iter(
        (
            httpx.Response(200, json=_response(status="RUNNING", request_id=RESULT_REQUEST_ID)),
            httpx.Response(200, json=_response(request_id=RESULT_REQUEST_ID)),
        )
    )

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.method == "POST":
            return httpx.Response(200, json=_response(status="PENDING"))
        return next(poll_responses)

    adapter, client = _adapter(handler)
    provider_request = _request()
    with caplog.at_level(logging.INFO, logger="app.providers.wan_video"):
        result = asyncio.run(adapter.execute(provider_request))
    asyncio.run(client.aclose())

    assert [request.method for request in requests] == ["POST", "GET", "GET"]
    assert sum(request.method == "POST" for request in requests) == 1
    assert requests[0].headers["X-DashScope-Async"] == "enable"
    assert requests[0].headers["traceparent"] == TRACEPARENT
    assert requests[1].headers["traceparent"] == TRACEPARENT
    assert requests[1].url.path == "/api/v1/tasks/wan-task-1"
    body = cast(dict[str, object], json.loads(requests[0].content))
    assert body["model"] == "wan3.0-video"
    assert body["parameters"] == {
        "duration": 5,
        "ratio": "16:9",
        "resolution": "720P",
        "audio": False,
        "seed": -1,
        "prompt_extend": True,
        "watermark": False,
    }
    provider_input = cast(dict[str, object], body["input"])
    assert provider_input["media"] == [
        {
            "type": "first_frame",
            "url": "https://asset.test/first-frame.png?signature=secret",
        }
    ]
    assert "咖啡机在清晨厨房台面上工作。风格: 电影级产品广告。运镜: 缓慢推进" in str(
        provider_input["prompt"]
    )
    assert "仅使用本次消息参考图片" in str(provider_input["prompt"])
    assert result.provider == "WAN_VIDEO"
    assert result.model == "wan3.0-video"
    assert result.request_id == PROVIDER_REQUEST_ID
    assert result.provider_task_id == "wan-task-1"
    assert result.submission_state is SubmissionState.CONFIRMED
    assert result.usage.video_seconds == 5
    assert result.usage.provider_cost is None
    assert result.usage.currency is None
    assert len(result.artifacts) == 1
    assert result.artifacts[0].artifact_type == "VIDEO"
    assert result.artifacts[0].unit_key == "video:1"
    assert result.artifacts[0].content_type == "video/mp4"
    assert result.artifacts[0].expires_at is None
    assert result.artifacts[0].metadata["mock"] is False
    assert result.artifacts[0].metadata["providerRequestId"] == str(PROVIDER_REQUEST_ID)
    assert result.artifacts[0].metadata["providerResultRequestId"] == str(RESULT_REQUEST_ID)
    assert adapter.status is ProviderStatus.AVAILABLE
    call_records = [
        record for record in caplog.records if record.message == "wan_video_http_call_started"
    ]
    assert [getattr(record, "operation", None) for record in call_records] == [
        "SUBMIT",
        "POLL",
        "POLL",
    ]
    assert [getattr(record, "attempt", None) for record in call_records] == [1, 1, 2]
    assert all(
        getattr(record, "traceId", None) == TRACEPARENT.split("-")[1] for record in call_records
    )
    log_text = caplog.text
    assert "wan-task-1" not in log_text
    assert str(PROVIDER_REQUEST_ID) not in log_text
    assert "video_url" not in log_text


def test_unexpected_poll_failure_keeps_queryable_video_receipt() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.method == "POST":
            return httpx.Response(200, json=_response(status="PENDING"))
        raise RuntimeError("unexpected poll implementation failure")

    adapter, client = _adapter(handler)
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.execute(_request()))
    asyncio.run(client.aclose())

    assert captured.value.code == "WAN_VIDEO_EXECUTION_UNEXPECTED"
    assert captured.value.submission_state is SubmissionState.UNKNOWN
    assert captured.value.provider_request_id == str(PROVIDER_REQUEST_ID)
    assert captured.value.provider_task_id == "wan-task-1"
    assert [request.method for request in requests] == ["POST", "GET"]


def test_image_to_video_never_degrades_to_text_only() -> None:
    calls = 0

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(500)

    adapter, client = _adapter(handler)
    request = _request().model_copy(update={"creative_brief": None, "reference_assets": ()})
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.execute(request))
    asyncio.run(client.aclose())

    assert captured.value.code == "IMAGE_TO_VIDEO_MODE_UNAVAILABLE"
    assert calls == 0


@pytest.mark.parametrize(
    "parameters",
    (
        {"scene": "商品展示", "durationSeconds": 1},
        {"scene": "商品展示", "durationSeconds": 31},
        {"scene": "商品展示", "durationSeconds": "5"},
        {"scene": "商品展示", "aspectRatio": "21:9"},
        {"scene": "商品展示", "resolution": "4K"},
        {"scene": "商品展示", "seed": 2_147_483_648},
        {"scene": "商品展示", "negativePrompt": "不需要"},
        {"scene": "商品展示", "audio": "false"},
    ),
)
def test_invalid_parameters_are_rejected_before_http(parameters: dict[str, JsonValue]) -> None:
    called = False

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal called
        called = True
        return httpx.Response(500)

    adapter, client = _adapter(handler)
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.execute(_request(parameters)))
    asyncio.run(client.aclose())

    assert called is False
    assert captured.value.code == "WAN_VIDEO_PARAMETERS_INVALID"
    assert captured.value.submission_state is SubmissionState.NOT_SUBMITTED


def test_non_video_task_is_rejected_before_submission() -> None:
    adapter, client = _adapter(lambda _: httpx.Response(500))
    request = _request().model_copy(update={"task_type": TaskType.IMAGE})
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.execute(request))
    asyncio.run(client.aclose())
    assert captured.value.code == "WAN_VIDEO_TASK_TYPE_UNSUPPORTED"


@pytest.mark.parametrize(
    ("exception_factory", "code", "state", "retryable"),
    (
        (
            _connect_error,
            "WAN_VIDEO_SUBMISSION_CONNECTION_FAILED",
            SubmissionState.NOT_SUBMITTED,
            True,
        ),
        (
            _read_timeout,
            "WAN_VIDEO_SUBMISSION_TIMEOUT",
            SubmissionState.UNKNOWN,
            False,
        ),
    ),
)
def test_submission_transport_certainty_is_bounded(
    exception_factory: Callable[[httpx.Request], httpx.RequestError],
    code: str,
    state: SubmissionState,
    retryable: bool,
) -> None:
    methods: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        methods.append(request.method)
        raise exception_factory(request)

    adapter, client = _adapter(handler)
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.execute(_request()))
    asyncio.run(client.aclose())
    assert methods == ["POST"]
    assert captured.value.code == code
    assert captured.value.submission_state is state
    assert captured.value.retryable is retryable


@pytest.mark.parametrize(
    ("status_code", "body", "code", "category", "state", "retryable"),
    (
        (
            401,
            {"code": "InvalidApiKey"},
            "WAN_VIDEO_AUTH_FAILED",
            ProviderErrorCategory.AUTH,
            SubmissionState.NOT_SUBMITTED,
            False,
        ),
        (
            403,
            {"code": "AccessDenied"},
            "WAN_VIDEO_AUTH_FAILED",
            ProviderErrorCategory.AUTH,
            SubmissionState.NOT_SUBMITTED,
            False,
        ),
        (
            429,
            {"code": "Throttling.RateQuota"},
            "WAN_VIDEO_RATE_LIMITED",
            ProviderErrorCategory.RATE_LIMIT,
            SubmissionState.NOT_SUBMITTED,
            True,
        ),
        (
            400,
            {"code": "DataInspectionFailed"},
            "WAN_VIDEO_SAFETY_REJECTED",
            ProviderErrorCategory.SAFETY_REJECTED,
            SubmissionState.NOT_SUBMITTED,
            False,
        ),
        (
            503,
            {"code": "InternalError"},
            "WAN_VIDEO_UPSTREAM_ERROR",
            ProviderErrorCategory.UPSTREAM,
            SubmissionState.UNKNOWN,
            False,
        ),
    ),
)
def test_submit_http_errors_have_safe_mappings(
    status_code: int,
    body: dict[str, str],
    code: str,
    category: ProviderErrorCategory,
    state: SubmissionState,
    retryable: bool,
) -> None:
    adapter, client = _adapter(lambda _: httpx.Response(status_code, json=body))
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.execute(_request()))
    asyncio.run(client.aclose())
    assert captured.value.code == code
    assert captured.value.category is category
    assert captured.value.submission_state is state
    assert captured.value.retryable is retryable
    assert str(captured.value) == code


@pytest.mark.parametrize(
    ("status", "provider_code", "expected"),
    (
        ("FAILED", "DataInspectionFailed", "WAN_VIDEO_SAFETY_REJECTED"),
        ("FAILED", "InternalError", "WAN_VIDEO_TASK_FAILED"),
        ("CANCELED", None, "WAN_VIDEO_TASK_CANCELED"),
    ),
)
def test_explicit_terminal_failure_is_confirmed(
    status: str,
    provider_code: str | None,
    expected: str,
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        response = _response(status=status)
        if provider_code is not None:
            cast(dict[str, object], response["output"])["code"] = provider_code
        return httpx.Response(200, json=response)

    adapter, client = _adapter(handler)
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.execute(_request()))
    asyncio.run(client.aclose())
    assert captured.value.code == expected
    assert captured.value.submission_state is SubmissionState.CONFIRMED
    assert captured.value.provider_request_id == str(PROVIDER_REQUEST_ID)
    assert captured.value.provider_task_id == "wan-task-1"


def test_transient_poll_error_retries_get_without_resubmitting() -> None:
    methods: list[str] = []
    poll_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal poll_count
        methods.append(request.method)
        if request.method == "POST":
            return httpx.Response(200, json=_response(status="PENDING"))
        poll_count += 1
        if poll_count == 1:
            return httpx.Response(503, json={"code": "InternalError"})
        return httpx.Response(200, json=_response())

    adapter, client = _adapter(handler)
    result = asyncio.run(adapter.execute(_request()))
    asyncio.run(client.aclose())
    assert result.provider_task_id == "wan-task-1"
    assert methods == ["POST", "GET", "GET"]


def test_poll_attempt_limit_returns_unknown_and_preserves_both_ids() -> None:
    methods: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        methods.append(request.method)
        return httpx.Response(200, json=_response(status="PENDING"))

    adapter, client = _adapter(handler, max_poll_attempts=2)
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.execute(_request()))
    asyncio.run(client.aclose())
    assert methods == ["POST", "GET", "GET"]
    assert captured.value.code == "WAN_VIDEO_POLL_TIMEOUT"
    assert captured.value.submission_state is SubmissionState.UNKNOWN
    assert captured.value.provider_request_id == str(PROVIDER_REQUEST_ID)
    assert captured.value.provider_task_id == "wan-task-1"


def test_poll_deadline_returns_unknown_and_preserves_both_ids() -> None:
    adapter, client = _adapter(
        lambda _: httpx.Response(200, json=_response(status="PENDING")),
        sleep=cast(Callable[[float], Awaitable[None]], asyncio.sleep),
        poll_timeout_seconds=0.001,
    )
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.execute(_request()))
    asyncio.run(client.aclose())
    assert captured.value.code == "WAN_VIDEO_POLL_TIMEOUT"
    assert captured.value.submission_state is SubmissionState.UNKNOWN
    assert captured.value.provider_request_id == str(PROVIDER_REQUEST_ID)
    assert captured.value.provider_task_id == "wan-task-1"


@pytest.mark.parametrize("mode", ("invalid-json", "mismatched-task", "unknown-status"))
def test_uncertain_poll_responses_fail_closed_with_original_ids(mode: str) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            return httpx.Response(200, json=_response(status="PENDING"))
        if mode == "invalid-json":
            return httpx.Response(200, content=b"not-json")
        if mode == "mismatched-task":
            return httpx.Response(200, json=_response(task_id="other-task"))
        return httpx.Response(200, json=_response(status="UNKNOWN"))

    adapter, client = _adapter(handler)
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.execute(_request()))
    asyncio.run(client.aclose())
    assert captured.value.submission_state is SubmissionState.UNKNOWN
    assert captured.value.provider_request_id == str(PROVIDER_REQUEST_ID)
    assert captured.value.provider_task_id == "wan-task-1"


def test_resume_uses_only_get_and_original_task_identity() -> None:
    methods: list[str] = []
    request = _request()
    continuation = WanVideoProviderContinuation(
        provider="WAN_VIDEO",
        model="wan3.0-video",
        provider_request_id=request.request_id,
        provider_task_id="wan-task-original",
    )

    def handler(http_request: httpx.Request) -> httpx.Response:
        methods.append(http_request.method)
        assert http_request.url.path.endswith("/wan-task-original")
        return httpx.Response(
            200,
            json=_response(task_id="wan-task-original", request_id=RESULT_REQUEST_ID),
        )

    adapter, client = _adapter(handler)
    result = asyncio.run(adapter.resume(request, continuation))
    asyncio.run(client.aclose())
    assert methods == ["GET"]
    assert result.provider_task_id == "wan-task-original"
    assert result.artifacts[0].metadata["providerRequestId"] == str(request.request_id)


def test_java_provider_call_budget_counts_post_and_get_before_each_request() -> None:
    methods: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        methods.append(request.method)
        return httpx.Response(200, json=_response(status="PENDING"))

    adapter, client = _adapter(handler)
    with (
        bind_provider_call_boundary(max_calls=1, deadline_at=None),
        pytest.raises(TaskProviderError) as captured,
    ):
        asyncio.run(adapter.execute(_request()))
    asyncio.run(client.aclose())

    assert methods == ["POST"]
    assert captured.value.code == "WAN_VIDEO_PROVIDER_CALL_BUDGET_EXCEEDED"
    assert captured.value.submission_state is SubmissionState.UNKNOWN
    assert captured.value.provider_request_id == str(PROVIDER_REQUEST_ID)
    assert captured.value.provider_task_id == "wan-task-1"


@pytest.mark.parametrize(
    ("response", "code", "state"),
    (
        (
            _response(status="FAILED", task_id="wan-task-original"),
            "WAN_VIDEO_TASK_FAILED",
            SubmissionState.CONFIRMED,
        ),
        (
            _response(status="UNKNOWN", task_id="wan-task-original"),
            "WAN_VIDEO_TASK_UNKNOWN",
            SubmissionState.UNKNOWN,
        ),
    ),
)
def test_resume_classifies_confirmed_failure_and_repeated_unknown(
    response: dict[str, object],
    code: str,
    state: SubmissionState,
) -> None:
    request = _request()
    continuation = WanVideoProviderContinuation(
        provider="WAN_VIDEO",
        model="wan3.0-video",
        provider_request_id=request.request_id,
        provider_task_id="wan-task-original",
    )
    adapter, client = _adapter(lambda _: httpx.Response(200, json=response))
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.resume(request, continuation))
    asyncio.run(client.aclose())
    assert captured.value.code == code
    assert captured.value.submission_state is state
    assert captured.value.provider_task_id == "wan-task-original"


def test_resume_rejects_wrong_provider_without_http() -> None:
    called = False

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal called
        called = True
        return httpx.Response(500)

    request = _request()
    wrong = QwenImageProviderContinuation(
        provider="QWEN_IMAGE",
        model="qwen-image-3.0",
        provider_request_id=request.request_id,
        provider_task_id="image-task",
    )
    adapter, client = _adapter(handler)
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.resume(request, wrong))
    asyncio.run(client.aclose())
    assert called is False
    assert captured.value.code == "WAN_VIDEO_CONTINUATION_INVALID"


def test_continuation_model_rejects_bad_identifiers_and_state() -> None:
    with pytest.raises(ValidationError):
        WanVideoProviderContinuation.model_validate(
            {
                "provider": "WAN_VIDEO",
                "model": "wan3.0-video",
                "providerRequestId": str(uuid4()),
                "providerTaskId": "contains spaces",
                "submissionState": "CONFIRMED",
            }
        )


@pytest.mark.parametrize(
    ("mutate", "expected"),
    (
        (
            _make_video_url_unsafe,
            "WAN_VIDEO_ARTIFACT_URL_INVALID",
        ),
        (
            _make_video_count_invalid,
            "WAN_VIDEO_SUCCESS_RESPONSE_INVALID",
        ),
        (
            _make_duration_mismatch,
            "WAN_VIDEO_DURATION_MISMATCH",
        ),
    ),
)
def test_success_result_validates_url_count_and_duration(
    mutate: Callable[[dict[str, object]], None],
    expected: str,
) -> None:
    response = _response()
    mutate(response)
    adapter, client = _adapter(lambda _: httpx.Response(200, json=response))
    with pytest.raises(TaskProviderError) as captured:
        asyncio.run(adapter.execute(_request()))
    asyncio.run(client.aclose())
    assert captured.value.code == expected
    assert captured.value.submission_state is SubmissionState.CONFIRMED


def test_probe_queries_unknown_task_without_posting() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(
            200,
            json={
                "request_id": str(PROVIDER_REQUEST_ID),
                "output": {
                    "task_id": "00000000-0000-0000-0000-000000000000",
                    "task_status": "UNKNOWN",
                },
            },
        )

    adapter, client = _adapter(handler)
    assert asyncio.run(adapter.probe()) is True
    asyncio.run(client.aclose())
    assert adapter.status is ProviderStatus.AVAILABLE
    assert [request.method for request in seen] == ["GET"]


def test_cancellation_stops_polling_without_another_submit() -> None:
    methods: list[str] = []

    async def cancelled(_: float) -> None:
        raise asyncio.CancelledError

    def handler(request: httpx.Request) -> httpx.Response:
        methods.append(request.method)
        return httpx.Response(200, json=_response(status="PENDING"))

    adapter, client = _adapter(handler, sleep=cancelled)
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(adapter.execute(_request()))
    asyncio.run(client.aclose())
    assert methods == ["POST"]


@pytest.mark.parametrize(
    "endpoint",
    (
        "http://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis",
        "https://unknown.example/api/v1/services/aigc/video-generation/video-synthesis",
        "https://dashscope.aliyuncs.com/api/v1/tasks",
        "https:///api/v1/services/aigc/video-generation/video-synthesis",
    ),
)
def test_endpoint_validation_rejects_unknown_or_malformed_hosts(endpoint: str) -> None:
    with pytest.raises(ValueError):
        WanVideoAdapter(_settings(endpoint=endpoint))


def test_workspace_beijing_endpoint_is_supported() -> None:
    adapter = WanVideoAdapter(_settings(endpoint=WORKSPACE_ENDPOINT))
    asyncio.run(adapter.aclose())
