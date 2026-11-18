"""Qwen-Image Adapter tests using only httpx's in-memory transport."""

import asyncio
import json
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import httpx
import pytest
from pydantic import SecretStr

from app.agent.models import ChatMessage, ChatRunRequest, RunBudget, TrustedState
from app.common.enums import ProviderStatus, TaskType
from app.common.settings import ProviderSettings
from app.harness.runtime import HarnessViolation, RuntimeHarness, bind_runtime_usage
from app.providers.models import (
    ProviderErrorCategory,
    ProviderUsage,
    QwenImageProviderContinuation,
    SubmissionState,
    TaskProviderError,
    TaskProviderRequest,
)
from app.providers.qwen_image import QwenImageAdapter

SUBMIT_REQUEST_ID = "123e4567-e89b-42d3-a456-426614174101"
RESULT_REQUEST_ID = "123e4567-e89b-42d3-a456-426614174102"


def _settings(
    endpoint: str = (
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation"
    ),
    *,
    model: str = "qwen-image-3.0",
) -> ProviderSettings:
    return ProviderSettings(
        credential=SecretStr("unit-test-dashscope-key"),
        model=model,
        endpoint=endpoint,
        timeout_seconds=2,
        probe_timeout_seconds=1,
        max_retries=0,
    )


def _request(
    *,
    task_type: TaskType = TaskType.IMAGE,
    parameters: dict[str, object] | None = None,
) -> TaskProviderRequest:
    reference_id = "1900012345678901200"
    return TaskProviderRequest.model_validate_json(
        json.dumps(
            {
                "requestId": str(uuid4()),
                "taskId": "task-image-1",
                "taskType": task_type,
                "executionNo": 1,
                "parameters": parameters or {},
                "creativeBrief": {
                    "sourceMode": "CURRENT_ATTACHMENTS",
                    "productId": None,
                    "referenceAssetIds": [reference_id],
                    "productFacts": None,
                    "creativeDirection": {
                        "requestText": (parameters or {}).get("requestText", "生成商品图")
                    },
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
                        "readUrl": "https://asset.test/current.png?signature=secret",
                        "expiresAt": "2030-09-14T10:00:00+08:00",
                    }
                ],
            }
        )
    )


def _traced_request(*, parameters: dict[str, object]) -> TaskProviderRequest:
    return _request(parameters=parameters).model_copy(
        update={"traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}
    )


def test_provider_usage_requires_cost_and_currency_as_a_pair() -> None:
    with pytest.raises(ValueError):
        ProviderUsage(images=1, provider_cost="0.0000")
    with pytest.raises(ValueError):
        ProviderUsage(images=1, currency="CNY")
    assert ProviderUsage(images=1).provider_cost is None


def test_probe_uses_non_billable_task_diagnostic_without_models() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        assert request.method == "GET"
        assert request.url.path == "/api/v1/tasks/00000000-0000-0000-0000-000000000000"
        return httpx.Response(
            200,
            json={
                "output": {
                    "task_id": "00000000-0000-0000-0000-000000000000",
                    "task_status": "UNKNOWN",
                }
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        assert await adapter.probe() is True
        assert adapter.status is ProviderStatus.AVAILABLE
        assert adapter.last_error_code is None
        await client.aclose()

    asyncio.run(scenario())
    assert len(requests) == 1
    assert all("/models" not in request.url.path for request in requests)


def test_reference_mode_is_not_registered_for_an_unverified_model() -> None:
    async def scenario() -> None:
        calls = 0

        def handler(_: httpx.Request) -> httpx.Response:
            nonlocal calls
            calls += 1
            return httpx.Response(500)

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(model="qwen-image-plus"), client=client)
        assert not adapter.capabilities
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(_request(parameters={"requestText": "生成商品图"}))
        assert captured.value.code == "REFERENCE_IMAGE_MODE_UNAVAILABLE"
        assert calls == 0
        await client.aclose()

    asyncio.run(scenario())


def test_reference_mode_never_degrades_to_text_only() -> None:
    async def scenario() -> None:
        calls = 0

        def handler(_: httpx.Request) -> httpx.Response:
            nonlocal calls
            calls += 1
            return httpx.Response(500)

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        request = _request(parameters={"requestText": "生成商品图"}).model_copy(
            update={"creative_brief": None, "reference_assets": ()}
        )
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(request)
        assert captured.value.code == "REFERENCE_IMAGE_MODE_UNAVAILABLE"
        assert calls == 0
        await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "endpoint",
    (
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation",
        "https://dashscope.aliyuncs.com:443/api/v1/services/aigc/image-generation/generation/",
        (
            "https://llm-workspace-123.cn-beijing.maas.aliyuncs.com/"
            "api/v1/services/aigc/image-generation/generation"
        ),
    ),
)
def test_endpoint_validation_allows_beijing_hosts(endpoint: str) -> None:
    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(500)))
        adapter = QwenImageAdapter(_settings(endpoint), client=client)
        assert adapter.status is ProviderStatus.CONFIGURED
        await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "endpoint",
    (
        "http://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation",
        "https:///api/v1/services/aigc/image-generation/generation",
        "https://example.com/api/v1/services/aigc/image-generation/generation",
        "https://dashscope.aliyuncs.com.example/api/v1/services/aigc/image-generation/generation",
        (
            "https://llm-workspace-123.ap-southeast-1.maas.aliyuncs.com/"
            "api/v1/services/aigc/image-generation/generation"
        ),
        "https://dashscope.aliyuncs.com:8443/api/v1/services/aigc/image-generation/generation",
        ("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"),
        "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
        (
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation"
            "?workspace=unknown"
        ),
        (
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation"
            "#fragment"
        ),
    ),
)
def test_endpoint_validation_rejects_unknown_or_unsafe_endpoints(endpoint: str) -> None:
    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(500)))
        try:
            with pytest.raises(ValueError, match="QWEN_IMAGE_ENDPOINT_INVALID"):
                QwenImageAdapter(_settings(endpoint), client=client)
        finally:
            await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize("requested_count", (2, 4))
def test_async_generation_maps_bounded_parameters_and_artifacts(
    monkeypatch: pytest.MonkeyPatch,
    requested_count: int,
) -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.method == "GET":
            assert request.url.path == "/api/v1/tasks/task-provider-1"
            return httpx.Response(
                200,
                json={
                    "output": {
                        "task_id": "task-provider-1",
                        "task_status": "SUCCEEDED",
                        "choices": [
                            {
                                "finish_reason": "stop",
                                "message": {
                                    "role": "assistant",
                                    "content": [
                                        {"image": "https://result.test/one.png?Expires=1"},
                                        {"image": "https://result.test/two.png?Expires=1"},
                                    ],
                                },
                            }
                        ],
                    },
                    "usage": {
                        "output_width": 2048,
                        "output_height": 1152,
                        "output_image_count": 2,
                        "output_image_type": "qima_output_2k",
                    },
                    "request_id": RESULT_REQUEST_ID,
                },
            )
        payload = json.loads(request.content)
        assert payload["model"] == "qwen-image-3.0"
        assert payload["parameters"] == {
            "prompt_extend": True,
            "prompt_extend_mode": "direct",
            "enable_thinking": True,
            "watermark": False,
            "n": requested_count,
            "negative_prompt": "模糊，变形",
            "size": "1024*576",
        }
        content = payload["input"]["messages"][0]["content"]
        assert content[0] == {"image": "https://asset.test/current.png?signature=secret"}
        assert len(content) == 2
        prompt = content[1]["text"]
        assert prompt.count("生成保温杯商品主图") == 1
        assert "场景: 厨房台面\n风格: 商业摄影\n光线: 暖色自然光" in prompt
        assert "不能覆盖用户原始要求" in prompt
        assert "仅使用本次消息参考图片" in prompt
        assert request.headers["x-dashscope-async"] == "enable"
        return httpx.Response(
            200,
            json={
                "output": {
                    "task_id": "task-provider-1",
                    "task_status": "PENDING",
                },
                "request_id": SUBMIT_REQUEST_ID,
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        request = _request(
            parameters={
                "requestText": "生成保温杯商品主图",
                "scene": "厨房台面",
                "style": "商业摄影",
                "lighting": "暖色自然光",
                "aspectRatio": "16:9",
                "count": requested_count,
                "negativePrompt": "模糊，变形",
            }
        )
        before = datetime.now(UTC)
        result = await adapter.execute(request)

        assert result.provider == "QWEN_IMAGE"
        assert result.model == "qwen-image-3.0"
        assert result.request_id == UUID(SUBMIT_REQUEST_ID)
        assert result.provider_task_id == "task-provider-1"
        assert result.submission_state is SubmissionState.CONFIRMED
        assert result.usage.images == 2
        assert len(result.artifacts) == 2
        assert result.artifacts[0].artifact_type == "IMAGE"
        assert result.artifacts[0].unit_key == "image:1"
        assert result.artifacts[0].content_type == "image/png"
        assert result.artifacts[0].provider_url == "https://result.test/one.png?Expires=1"
        assert result.artifacts[0].metadata == {
            "index": 1,
            "mock": False,
            "width": 2048,
            "height": 1152,
            "outputImageType": "qima_output_2k",
            "providerRequestId": SUBMIT_REQUEST_ID,
            "providerResultRequestId": RESULT_REQUEST_ID,
        }
        assert result.artifacts[0].expires_at is not None
        assert result.artifacts[0].expires_at > before
        assert adapter.status is ProviderStatus.AVAILABLE
        assert adapter.last_error_code is None
        await client.aclose()

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)
    asyncio.run(scenario())
    assert [request.url.path for request in requests] == [
        "/api/v1/services/aigc/image-generation/generation",
        "/api/v1/tasks/task-provider-1",
    ]
    assert all(
        request.headers["authorization"] == "Bearer unit-test-dashscope-key" for request in requests
    )


def test_chat_run_provider_budget_blocks_submit_before_the_first_http_request(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        assert request.method == "POST"
        return httpx.Response(
            200,
            json={
                "output": {"task_id": "task-provider-1", "task_status": "PENDING"},
                "request_id": SUBMIT_REQUEST_ID,
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        run_request = ChatRunRequest(
            run_id=uuid4(),
            conversation_id="900000000000000001",
            user_context_ref="opaque-test-context",
            message=ChatMessage(message_id="message-1", content="生成商品主图"),
            trusted_state=TrustedState(),
            budget=RunBudget(deadline_at=datetime.now(UTC) + timedelta(minutes=1)),
        )
        counters = RuntimeHarness().new_usage_counters(run_request)
        with bind_runtime_usage(counters), pytest.raises(HarnessViolation) as captured:
            await adapter.execute(_request(parameters={"requestText": "生成商品主图"}))
        assert captured.value.code == "PROVIDER_CALL_BUDGET_EXCEEDED"
        assert counters.provider_call_count == 0
        await client.aclose()

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)
    asyncio.run(scenario())
    assert requests == []


def test_async_generation_polls_the_confirmed_task(monkeypatch: pytest.MonkeyPatch) -> None:
    endpoint = "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation"
    poll_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal poll_count
        if request.method == "POST":
            assert request.headers["x-dashscope-async"] == "enable"
            return httpx.Response(
                200,
                json={
                    "output": {"task_status": "PENDING", "task_id": "task-provider-1"},
                    "request_id": SUBMIT_REQUEST_ID,
                },
            )
        assert request.url.path == "/api/v1/tasks/task-provider-1"
        poll_count += 1
        if poll_count == 1:
            return httpx.Response(
                200,
                json={"output": {"task_status": "RUNNING", "task_id": "task-provider-1"}},
            )
        return httpx.Response(
            200,
            json={
                "output": {
                    "task_status": "SUCCEEDED",
                    "task_id": "task-provider-1",
                    "choices": [
                        {"message": {"content": [{"image": "https://result.test/async.png"}]}}
                    ],
                },
                "usage": {
                    "output_width": 2048,
                    "output_height": 2048,
                    "output_image_count": 1,
                },
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(endpoint), client=client)
        result = await adapter.execute(
            _request(parameters={"requestText": "生成白底商品图", "aspectRatio": "1:1"})
        )
        assert result.provider_task_id == "task-provider-1"
        assert result.artifacts[0].provider_url == "https://result.test/async.png"
        assert result.request_id == UUID(SUBMIT_REQUEST_ID)
        assert result.artifacts[0].metadata == {
            "index": 1,
            "mock": False,
            "width": 2048,
            "height": 2048,
            "providerRequestId": SUBMIT_REQUEST_ID,
        }
        await client.aclose()

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)
    asyncio.run(scenario())
    assert poll_count == 2


def test_unexpected_poll_failure_keeps_queryable_image_receipt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.method == "POST":
            return httpx.Response(
                200,
                json={
                    "output": {"task_status": "PENDING", "task_id": "task-provider-1"},
                    "request_id": SUBMIT_REQUEST_ID,
                },
            )
        raise RuntimeError("unexpected poll implementation failure")

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(_request(parameters={"requestText": "商品图", "count": 1}))
        assert captured.value.submission_state is SubmissionState.UNKNOWN
        assert captured.value.provider_request_id == SUBMIT_REQUEST_ID
        assert captured.value.provider_task_id == "task-provider-1"
        await client.aclose()

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)
    asyncio.run(scenario())
    assert [request.method for request in requests] == ["POST", "GET"]


def test_async_submission_requires_its_own_request_id() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.method != "POST":
            raise AssertionError("Missing submit requestId must stop before polling")
        return httpx.Response(
            200,
            json={"output": {"task_status": "PENDING", "task_id": "task-provider-1"}},
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(_request(parameters={"requestText": "商品图", "count": 1}))
        assert captured.value.code == "QWEN_IMAGE_INVALID_RESPONSE"
        assert captured.value.submission_state is SubmissionState.UNKNOWN
        await client.aclose()

    asyncio.run(scenario())
    assert [request.method for request in requests] == ["POST"]


@pytest.mark.parametrize("returned_task_id", (None, "different-task-id"))
def test_poll_requires_the_submitted_task_id(
    monkeypatch: pytest.MonkeyPatch,
    returned_task_id: str | None,
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            return httpx.Response(
                200,
                json={
                    "output": {"task_status": "PENDING", "task_id": "submitted-task-id"},
                    "request_id": SUBMIT_REQUEST_ID,
                },
            )
        return httpx.Response(
            200,
            json={
                "output": {
                    "task_status": "SUCCEEDED",
                    "task_id": returned_task_id,
                    "choices": [
                        {"message": {"content": [{"image": "https://result.test/image.png"}]}}
                    ],
                },
                "usage": {"output_image_count": 1},
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(_request(parameters={"requestText": "商品图", "count": 1}))
        assert captured.value.code == "QWEN_IMAGE_INVALID_RESPONSE"
        assert captured.value.category is ProviderErrorCategory.INVALID_RESPONSE
        assert captured.value.retryable is False
        assert captured.value.submission_state is SubmissionState.UNKNOWN
        await client.aclose()

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)
    asyncio.run(scenario())


def test_terminal_model_unavailable_has_a_stable_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            return httpx.Response(
                200,
                json={
                    "output": {"task_status": "PENDING", "task_id": "task-provider-1"},
                    "request_id": SUBMIT_REQUEST_ID,
                },
            )
        return httpx.Response(
            200,
            json={
                "output": {
                    "task_status": "FAILED",
                    "task_id": "task-provider-1",
                    "code": "ModelUnavailable",
                    "message": "upstream details must not leak",
                }
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(_request(parameters={"requestText": "商品图", "count": 1}))
        assert captured.value.code == "QWEN_IMAGE_MODEL_UNAVAILABLE"
        assert captured.value.category is ProviderErrorCategory.UPSTREAM
        assert captured.value.retryable is False
        assert captured.value.submission_state is SubmissionState.CONFIRMED
        assert captured.value.provider_request_id == SUBMIT_REQUEST_ID
        assert captured.value.provider_task_id == "task-provider-1"
        assert "upstream details" not in str(captured.value)
        await client.aclose()

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)
    asyncio.run(scenario())


@pytest.mark.parametrize(
    ("requested_count", "image_urls", "usage_count"),
    (
        (1, (), 0),
        (1, ("https://result.test/one.png", "https://result.test/two.png"), 2),
        (1, ("https://result.test/one.png",), 0),
        (1, ("https://result.test/one.png",), 2),
        (4, ("https://result.test/one.png", "https://result.test/one.png"), 2),
        (4, ("https://result.test/one.png",), 4),
        (4, ("https://result.test/one.png",), None),
    ),
)
def test_result_count_must_fit_request_and_match_actual_usage(
    monkeypatch: pytest.MonkeyPatch,
    requested_count: int,
    image_urls: tuple[str, ...],
    usage_count: int | None,
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            return httpx.Response(
                200,
                json={
                    "output": {"task_status": "PENDING", "task_id": "task-provider-1"},
                    "request_id": SUBMIT_REQUEST_ID,
                },
            )
        return httpx.Response(
            200,
            json={
                "output": {
                    "task_status": "SUCCEEDED",
                    "task_id": "task-provider-1",
                    "choices": [
                        {"message": {"content": [{"image": image_url} for image_url in image_urls]}}
                    ],
                },
                "usage": {"output_image_count": usage_count},
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(
                _request(parameters={"requestText": "商品图", "count": requested_count})
            )
        assert captured.value.code == "QWEN_IMAGE_RESULT_COUNT_MISMATCH"
        assert captured.value.category is ProviderErrorCategory.INVALID_RESPONSE
        assert captured.value.retryable is False
        assert captured.value.submission_state is SubmissionState.CONFIRMED
        assert captured.value.provider_request_id == SUBMIT_REQUEST_ID
        assert captured.value.provider_task_id == "task-provider-1"
        await client.aclose()

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)
    asyncio.run(scenario())


@pytest.mark.parametrize(
    (
        "status_code",
        "body",
        "expected_code",
        "category",
        "submission_state",
        "retryable",
    ),
    (
        (
            401,
            {"code": "InvalidApiKey", "message": "secret must not leak"},
            "QWEN_IMAGE_AUTH_FAILED",
            ProviderErrorCategory.AUTH,
            SubmissionState.NOT_SUBMITTED,
            False,
        ),
        (
            403,
            {"code": "Workspace.AccessDenied", "message": "secret must not leak"},
            "QWEN_IMAGE_AUTH_FAILED",
            ProviderErrorCategory.AUTH,
            SubmissionState.NOT_SUBMITTED,
            False,
        ),
        (
            404,
            {"code": "ModelNotFound", "message": "model details must not leak"},
            "QWEN_IMAGE_MODEL_UNAVAILABLE",
            ProviderErrorCategory.UPSTREAM,
            SubmissionState.NOT_SUBMITTED,
            False,
        ),
        (
            429,
            {"code": "Throttling"},
            "QWEN_IMAGE_RATE_LIMITED",
            ProviderErrorCategory.RATE_LIMIT,
            SubmissionState.NOT_SUBMITTED,
            True,
        ),
        (
            503,
            {"code": "InternalError"},
            "QWEN_IMAGE_PROVIDER_UNAVAILABLE",
            ProviderErrorCategory.UPSTREAM,
            SubmissionState.UNKNOWN,
            False,
        ),
        (
            400,
            {"code": "DataInspectionFailed"},
            "QWEN_IMAGE_SAFETY_REJECTED",
            ProviderErrorCategory.SAFETY_REJECTED,
            SubmissionState.CONFIRMED,
            False,
        ),
    ),
)
def test_http_failures_are_sanitized_for_worker_retry_policy(
    status_code: int,
    body: dict[str, str],
    expected_code: str,
    category: ProviderErrorCategory,
    submission_state: SubmissionState,
    retryable: bool,
) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(status_code, json=body, headers={"Retry-After": "2"})

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(_request(parameters={"requestText": "商品图"}))
        assert captured.value.code == expected_code
        assert captured.value.category is category
        assert captured.value.retryable is retryable
        assert captured.value.submission_state is submission_state
        assert "secret" not in str(captured.value)
        if status_code == 429:
            assert captured.value.retry_after_seconds == 2
        await client.aclose()

    asyncio.run(scenario())


def test_post_read_timeout_is_unknown_and_must_not_be_retried() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("response was lost after submit", request=request)

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(_request(parameters={"requestText": "商品图"}))
        assert captured.value.code == "QWEN_IMAGE_TIMEOUT"
        assert captured.value.category is ProviderErrorCategory.TIMEOUT
        assert captured.value.retryable is False
        assert captured.value.submission_state is SubmissionState.UNKNOWN
        await client.aclose()

    asyncio.run(scenario())


def test_poll_uncertainty_preserves_provider_ids_and_trace_without_resubmit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        assert request.headers["traceparent"] == (
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        )
        if request.method == "POST":
            return httpx.Response(
                200,
                json={
                    "output": {"task_status": "PENDING", "task_id": "task-provider-1"},
                    "request_id": SUBMIT_REQUEST_ID,
                },
            )
        raise httpx.ReadTimeout("synthetic poll timeout", request=request)

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(_traced_request(parameters={"requestText": "商品图", "count": 1}))
        error = captured.value
        assert error.code == "QWEN_IMAGE_RESULT_TIMEOUT"
        assert error.submission_state is SubmissionState.UNKNOWN
        assert error.retryable is False
        assert error.provider_request_id == SUBMIT_REQUEST_ID
        assert error.provider_task_id == "task-provider-1"
        await client.aclose()

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)
    asyncio.run(scenario())
    assert [request.method for request in requests] == ["POST", "GET"]


@pytest.mark.parametrize("requested_count", (1, 4))
def test_uncertain_submission_resumes_by_get_with_original_ids_and_no_second_post(
    monkeypatch: pytest.MonkeyPatch,
    requested_count: int,
) -> None:
    requests: list[httpx.Request] = []
    poll_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal poll_count
        requests.append(request)
        assert request.headers["traceparent"] == (
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        )
        if request.method == "POST":
            return httpx.Response(
                200,
                json={
                    "output": {"task_status": "PENDING", "task_id": "task-provider-1"},
                    "request_id": SUBMIT_REQUEST_ID,
                },
            )
        assert request.url.path == "/api/v1/tasks/task-provider-1"
        poll_count += 1
        if poll_count <= 2:
            raise httpx.ReadTimeout("synthetic uncertain result", request=request)
        return httpx.Response(
            200,
            json={
                "output": {
                    "task_status": "SUCCEEDED",
                    "task_id": "task-provider-1",
                    "choices": [
                        {"message": {"content": [{"image": "https://result.test/resumed.png"}]}}
                    ],
                },
                "usage": {
                    "output_width": 1024,
                    "output_height": 1024,
                    "output_image_count": 1,
                },
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        first_request = _traced_request(
            parameters={"requestText": "商品图", "count": requested_count}
        )
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(first_request)
        assert captured.value.submission_state is SubmissionState.UNKNOWN
        assert captured.value.provider_request_id == SUBMIT_REQUEST_ID
        assert captured.value.provider_task_id == "task-provider-1"

        continuation = QwenImageProviderContinuation(
            provider="QWEN_IMAGE",
            model="qwen-image-3.0",
            provider_request_id=UUID(SUBMIT_REQUEST_ID),
            provider_task_id="task-provider-1",
        )
        resumed_request = first_request.model_copy(
            update={"request_id": UUID(SUBMIT_REQUEST_ID), "execution_no": 2}
        )
        with pytest.raises(TaskProviderError) as resumed_unknown:
            await adapter.resume(resumed_request, continuation)
        assert resumed_unknown.value.submission_state is SubmissionState.UNKNOWN
        assert resumed_unknown.value.provider_task_id == "task-provider-1"

        result = await adapter.resume(
            resumed_request.model_copy(update={"execution_no": 3}),
            continuation,
        )
        assert result.request_id == UUID(SUBMIT_REQUEST_ID)
        assert result.provider_task_id == "task-provider-1"
        assert result.artifacts[0].provider_url == "https://result.test/resumed.png"
        assert len(result.artifacts) == result.usage.images == 1
        await client.aclose()

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)
    asyncio.run(scenario())
    assert [request.method for request in requests].count("POST") == 1
    assert [request.method for request in requests].count("GET") == 3


@pytest.mark.parametrize(
    ("status", "provider_code", "expected_code", "category"),
    (
        ("FAILED", None, "QWEN_IMAGE_TASK_FAILED", ProviderErrorCategory.UPSTREAM),
        ("CANCELED", None, "QWEN_IMAGE_TASK_CANCELED", ProviderErrorCategory.UPSTREAM),
        (
            "FAILED",
            "DataInspectionFailed",
            "QWEN_IMAGE_SAFETY_REJECTED",
            ProviderErrorCategory.SAFETY_REJECTED,
        ),
    ),
)
def test_resume_explicit_failure_is_confirmed_without_submit(
    status: str,
    provider_code: str | None,
    expected_code: str,
    category: ProviderErrorCategory,
) -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        output: dict[str, object] = {
            "task_status": status,
            "task_id": "task-provider-1",
        }
        if provider_code is not None:
            output["code"] = provider_code
        return httpx.Response(200, json={"output": output})

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        continuation = QwenImageProviderContinuation(
            provider="QWEN_IMAGE",
            model="qwen-image-3.0",
            provider_request_id=UUID(SUBMIT_REQUEST_ID),
            provider_task_id="task-provider-1",
        )
        request = _traced_request(parameters={"requestText": "商品图", "count": 1}).model_copy(
            update={"request_id": UUID(SUBMIT_REQUEST_ID), "execution_no": 2}
        )
        with pytest.raises(TaskProviderError) as captured:
            await adapter.resume(request, continuation)
        assert captured.value.code == expected_code
        assert captured.value.category is category
        assert captured.value.submission_state is SubmissionState.CONFIRMED
        assert captured.value.provider_request_id == SUBMIT_REQUEST_ID
        assert captured.value.provider_task_id == "task-provider-1"
        await client.aclose()

    asyncio.run(scenario())
    assert [request.method for request in requests] == ["GET"]


@pytest.mark.parametrize("failure_mode", ("UNKNOWN_STATUS", "READ_TIMEOUT"))
def test_resume_uncertainty_stays_unknown_and_preserves_ids_without_submit(
    failure_mode: str,
) -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if failure_mode == "READ_TIMEOUT":
            raise httpx.ReadTimeout("synthetic recovery timeout", request=request)
        return httpx.Response(
            200,
            json={
                "output": {
                    "task_status": "UNKNOWN",
                    "task_id": "task-provider-1",
                }
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        continuation = QwenImageProviderContinuation(
            provider="QWEN_IMAGE",
            model="qwen-image-3.0",
            provider_request_id=UUID(SUBMIT_REQUEST_ID),
            provider_task_id="task-provider-1",
        )
        request = _request(parameters={"requestText": "商品图", "count": 1}).model_copy(
            update={"request_id": UUID(SUBMIT_REQUEST_ID), "execution_no": 2}
        )
        with pytest.raises(TaskProviderError) as captured:
            await adapter.resume(request, continuation)
        assert captured.value.submission_state is SubmissionState.UNKNOWN
        assert captured.value.provider_request_id == SUBMIT_REQUEST_ID
        assert captured.value.provider_task_id == "task-provider-1"
        await client.aclose()

    asyncio.run(scenario())
    assert [request.method for request in requests] == ["GET"]


def test_resume_rejects_mismatched_request_identity_before_http() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("Invalid continuation must not reach DashScope")

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        continuation = QwenImageProviderContinuation(
            provider="QWEN_IMAGE",
            model="qwen-image-3.0",
            provider_request_id=UUID(SUBMIT_REQUEST_ID),
            provider_task_id="task-provider-1",
        )
        with pytest.raises(TaskProviderError) as captured:
            await adapter.resume(
                _request(parameters={"requestText": "商品图", "count": 1}),
                continuation,
            )
        assert captured.value.code == "QWEN_IMAGE_CONTINUATION_INVALID"
        assert captured.value.submission_state is SubmissionState.UNKNOWN
        assert captured.value.provider_request_id == SUBMIT_REQUEST_ID
        assert captured.value.provider_task_id == "task-provider-1"
        await client.aclose()

    asyncio.run(scenario())


def test_invalid_success_response_is_confirmed_and_not_retried() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "output": {
                    "task_id": "task-provider-1",
                    "task_status": "SUCCEEDED",
                    "choices": [
                        {"message": {"content": [{"image": "http://unsafe.test/image.png"}]}}
                    ],
                },
                "usage": {"output_image_count": 1},
                "request_id": SUBMIT_REQUEST_ID,
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        with pytest.raises(TaskProviderError) as captured:
            await adapter.execute(_request(parameters={"requestText": "商品图"}))
        assert captured.value.code == "QWEN_IMAGE_INVALID_RESPONSE"
        assert captured.value.category is ProviderErrorCategory.INVALID_RESPONSE
        assert captured.value.retryable is False
        assert captured.value.submission_state is SubmissionState.CONFIRMED
        assert captured.value.provider_request_id == SUBMIT_REQUEST_ID
        assert captured.value.provider_task_id == "task-provider-1"
        await client.aclose()

    asyncio.run(scenario())


def test_adapter_rejects_non_image_and_invalid_image_parameters_without_http() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("Invalid local requests must not reach DashScope")

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = QwenImageAdapter(_settings(), client=client)
        with pytest.raises(TaskProviderError, match="QWEN_IMAGE_TASK_TYPE_UNSUPPORTED"):
            await adapter.execute(
                _request(task_type=TaskType.VIDEO, parameters={"requestText": "视频"})
            )
        with pytest.raises(TaskProviderError, match="QWEN_IMAGE_PARAMETERS_INVALID"):
            await adapter.execute(_request(parameters={"requestText": "图片", "count": 7}))
        with pytest.raises(TaskProviderError, match="QWEN_IMAGE_ASPECT_RATIO_INVALID"):
            await adapter.execute(
                _request(parameters={"requestText": "图片", "aspectRatio": "20:1"})
            )
        await client.aclose()

    asyncio.run(scenario())
