"""Real task Provider routing tests without any external requests."""

import asyncio
from uuid import uuid4

import pytest
from pydantic import SecretStr

from app.common.enums import ProviderStatus, TaskType
from app.common.settings import AppSettings, ProviderSettings
from app.providers import build_task_provider
from app.providers.models import (
    ProviderArtifact,
    ProviderUsage,
    QwenImageProviderContinuation,
    TaskProviderCapability,
    TaskProviderError,
    TaskProviderRequest,
    TaskProviderResult,
)
from app.providers.router import RoutedTaskProvider


class _FakeAdapter:
    is_mock = False
    capabilities = frozenset[TaskProviderCapability]()

    def __init__(self, code: str, model: str) -> None:
        self.code = code
        self.model = model
        self.status = ProviderStatus.CONFIGURED
        self.calls: list[TaskType] = []
        self.closed = False

    async def probe(self) -> bool:
        self.status = ProviderStatus.AVAILABLE
        return True

    async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
        self.calls.append(request.task_type)
        artifact_type = "IMAGE" if request.task_type is TaskType.IMAGE else "VIDEO"
        return TaskProviderResult(
            provider=self.code,
            model=self.model,
            request_id=request.request_id,
            provider_task_id=f"provider-{request.request_id}",
            artifacts=(
                ProviderArtifact(
                    artifact_type=artifact_type,
                    provider_url=f"https://provider.test/{request.request_id}",
                ),
            ),
            usage=ProviderUsage(),
            latency_ms=1,
        )

    async def aclose(self) -> None:
        self.closed = True


class _ResumableImageAdapter(_FakeAdapter):
    def __init__(self) -> None:
        super().__init__("QWEN_IMAGE", "qwen-image-3.0")
        self.resume_calls: list[QwenImageProviderContinuation] = []

    async def resume(
        self,
        request: TaskProviderRequest,
        continuation: QwenImageProviderContinuation,
    ) -> TaskProviderResult:
        self.resume_calls.append(continuation)
        return await self.execute(request)


def _request(task_type: TaskType) -> TaskProviderRequest:
    return TaskProviderRequest(
        request_id=uuid4(),
        task_id=f"task-{task_type.value.casefold()}",
        task_type=task_type,
        execution_no=1,
        parameters={"scene": "test"},
    )


def test_router_probes_routes_and_closes_configured_adapters() -> None:
    async def scenario() -> None:
        image = _FakeAdapter("QWEN_IMAGE", "qwen-image-test")
        video = _FakeAdapter("WAN_VIDEO", "wan-video-test")
        router = RoutedTaskProvider({TaskType.IMAGE: image, TaskType.VIDEO: video})

        assert router.status is ProviderStatus.CONFIGURED
        assert await router.probe() is True
        assert router.status is ProviderStatus.AVAILABLE
        result = await router.execute(_request(TaskType.VIDEO))
        assert result.provider == "WAN_VIDEO"
        assert image.calls == []
        assert video.calls == [TaskType.VIDEO]

        await router.aclose()
        assert image.closed is True
        assert video.closed is True

    asyncio.run(scenario())


def test_router_never_falls_back_for_an_unconfigured_domain() -> None:
    async def scenario() -> None:
        router = RoutedTaskProvider({TaskType.IMAGE: _FakeAdapter("QWEN_IMAGE", "qwen-image-test")})
        with pytest.raises(TaskProviderError) as captured:
            await router.execute(_request(TaskType.ARTICLE))
        assert captured.value.code == "REAL_PROVIDER_NOT_CONFIGURED_FOR_ARTICLE"
        assert captured.value.retryable is False

    asyncio.run(scenario())


def test_single_image_router_exposes_exact_provider_identity() -> None:
    adapter = _FakeAdapter("QWEN_IMAGE", "qwen-image-3.0")
    router = RoutedTaskProvider({TaskType.IMAGE: adapter})

    assert router.code == "QWEN_IMAGE"
    assert router.model == "qwen-image-3.0"


def test_router_forwards_resume_only_to_the_registered_image_adapter() -> None:
    async def scenario() -> None:
        adapter = _ResumableImageAdapter()
        router = RoutedTaskProvider({TaskType.IMAGE: adapter})
        continuation = QwenImageProviderContinuation(
            provider="QWEN_IMAGE",
            model="qwen-image-3.0",
            provider_request_id=uuid4(),
            provider_task_id="qwen-task-1",
        )
        request = _request(TaskType.IMAGE).model_copy(
            update={"request_id": continuation.provider_request_id}
        )

        result = await router.resume(request, continuation)

        assert result.request_id == continuation.provider_request_id
        assert adapter.resume_calls == [continuation]
        assert adapter.calls == [TaskType.IMAGE]
        await router.aclose()

    asyncio.run(scenario())


def test_factory_builds_real_media_router_without_opening_external_connections() -> None:
    settings = AppSettings(
        environment="test",
        worker_provider="REAL",
        worker_task_type="IMAGE_VIDEO",
        qwen_image=ProviderSettings(
            credential=SecretStr("image-test-key"),
            model="qwen-image-test",
            endpoint=(
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation"
            ),
        ),
        wan_video=ProviderSettings(
            credential=SecretStr("video-test-key"),
            model="wan3.0-video",
            endpoint=(
                "https://dashscope.aliyuncs.com/"
                "api/v1/services/aigc/video-generation/video-synthesis"
            ),
        ),
    )

    provider = build_task_provider(settings)

    assert isinstance(provider, RoutedTaskProvider)
    assert provider.is_mock is False
    assert provider.model == "IMAGE=qwen-image-test,VIDEO=wan3.0-video"
    asyncio.run(provider.aclose())


def test_factory_rejects_missing_real_provider_instead_of_using_mock() -> None:
    with pytest.raises(TaskProviderError) as captured:
        build_task_provider(
            AppSettings(
                environment="test",
                worker_provider="REAL",
                worker_task_type="VIDEO",
            )
        )

    assert captured.value.code == "WAN_VIDEO_NOT_CONFIGURED"


def test_factory_rejects_unknown_qwen_image_host_without_falling_back() -> None:
    settings = AppSettings(
        environment="test",
        worker_provider="REAL",
        worker_task_type="IMAGE",
        qwen_image=ProviderSettings(
            credential=SecretStr("image-test-key"),
            model="qwen-image-3.0",
            endpoint=("https://unknown.example/api/v1/services/aigc/image-generation/generation"),
        ),
    )

    with pytest.raises(TaskProviderError) as captured:
        build_task_provider(settings)

    assert captured.value.code == "QWEN_IMAGE_CONFIGURATION_INVALID"
