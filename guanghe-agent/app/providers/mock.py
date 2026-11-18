"""Deterministic local Provider Mock for every Python-owned task domain."""

import asyncio
from datetime import UTC, datetime, timedelta
from time import monotonic

from app.common.enums import ProviderStatus, TaskType
from app.common.settings import ProviderMockSettings
from app.providers.models import (
    ProviderArtifact,
    ProviderErrorCategory,
    ProviderUsage,
    SubmissionState,
    TaskProviderCapability,
    TaskProviderError,
    TaskProviderRequest,
    TaskProviderResult,
)


class LocalTaskProviderMock:
    """Return clearly synthetic artifacts and deterministic safe failures."""

    code = "LOCAL_TASK_PROVIDER"
    model = "deterministic-provider-mock-v1"
    is_mock = True
    capabilities = frozenset(
        (
            TaskProviderCapability.REFERENCE_IMAGE_GENERATION,
            TaskProviderCapability.IMAGE_TO_VIDEO,
            TaskProviderCapability.TEXT_TO_IMAGE,
            TaskProviderCapability.TEXT_TO_VIDEO,
            TaskProviderCapability.REFERENCE_TO_VIDEO,
            TaskProviderCapability.MASK_IMAGE_EDIT,
        )
    )

    def __init__(self, settings: ProviderMockSettings | None = None) -> None:
        self._settings = settings or ProviderMockSettings()

    @property
    def status(self) -> ProviderStatus:
        return ProviderStatus.AVAILABLE if self._settings.enabled else ProviderStatus.DISABLED

    async def probe(self) -> bool:
        return self._settings.enabled

    async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
        if not self._settings.enabled:
            raise TaskProviderError(
                "PROVIDER_MOCK_DISABLED",
                category=ProviderErrorCategory.UPSTREAM,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )
        started = monotonic()
        if self._settings.latency_ms:
            await asyncio.sleep(self._settings.latency_ms / 1_000)
        self._raise_configured_failure()
        count = request.parameters.get("count", 1) if request.task_type is TaskType.IMAGE else 1
        if isinstance(count, bool) or not isinstance(count, int) or not 1 <= count <= 4:
            raise TaskProviderError(
                "PROVIDER_IMAGE_BUDGET_INVALID",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )
        artifacts = tuple(self._artifact(request, index=index) for index in range(1, count + 1))
        usage = self._usage(request.task_type)
        if request.task_type is TaskType.IMAGE:
            usage = usage.model_copy(update={"images": count})
        return TaskProviderResult(
            provider=self.code,
            model=self.model,
            request_id=request.request_id,
            provider_task_id=f"mock-{request.task_type.value.casefold()}-{request.request_id}",
            artifacts=artifacts,
            usage=usage,
            latency_ms=max(0, round((monotonic() - started) * 1_000)),
        )

    async def aclose(self) -> None:
        return None

    def _artifact(self, request: TaskProviderRequest, *, index: int = 1) -> ProviderArtifact:
        task_type = request.task_type
        if task_type is TaskType.IMAGE:
            suffix = "" if index == 1 else f"-{index}"
            return ProviderArtifact(
                artifact_type="IMAGE",
                unit_key=f"image:{index}",
                provider_url=f"https://mock-provider.invalid/image/{request.request_id}{suffix}.png",
                content_type="image/png",
                expires_at=datetime.now(UTC)
                + timedelta(seconds=self._settings.artifact_ttl_seconds),
                metadata={"width": 1024, "height": 1024, "mock": True},
            )
        if task_type is TaskType.VIDEO:
            return ProviderArtifact(
                artifact_type="VIDEO",
                provider_url=f"https://mock-provider.invalid/video/{request.request_id}.mp4",
                content_type="video/mp4",
                expires_at=datetime.now(UTC)
                + timedelta(seconds=self._settings.artifact_ttl_seconds),
                metadata={"durationSeconds": 5, "mock": True},
            )
        if task_type is TaskType.GEO:
            return ProviderArtifact(
                artifact_type="GEO_PLATFORM_SAMPLE",
                unit_key="synthetic-question:MOCK:1",
                inline_data={
                    "platformCode": "MOCK",
                    "collectionMethod": "mock-v1",
                    "round": 1,
                    "answer": "本地 Provider Mock 合成样本，不代表任何真实平台回答。",
                    "citations": [],
                    "mock": True,
                },
                metadata={"mock": True},
            )
        if task_type is TaskType.ARTICLE:
            return ProviderArtifact(
                artifact_type="ARTICLE_CONTENT",
                inline_data={
                    "title": "本地 Provider Mock 文章",
                    "outline": ["用途说明", "合成内容"],
                    "body": "这是用于验证 Worker 链路的合成内容，不是正式生成结果。",
                    "mock": True,
                },
                metadata={"mock": True},
            )
        raise TaskProviderError(
            "PROVIDER_MOCK_TASK_TYPE_UNSUPPORTED",
            category=ProviderErrorCategory.INVALID_RESPONSE,
            retryable=False,
            submission_state=SubmissionState.NOT_SUBMITTED,
        )

    @staticmethod
    def _usage(task_type: TaskType) -> ProviderUsage:
        if task_type is TaskType.IMAGE:
            return ProviderUsage(images=1, provider_cost="0.0000", currency="CNY")
        if task_type is TaskType.VIDEO:
            return ProviderUsage(
                video_seconds=5,
                provider_cost="0.0000",
                currency="CNY",
            )
        return ProviderUsage(
            input_tokens=16,
            output_tokens=32,
            provider_cost="0.0000",
            currency="CNY",
        )

    def _raise_configured_failure(self) -> None:
        mode = self._settings.failure_mode
        failures = {
            "AUTH": TaskProviderError(
                "PROVIDER_AUTH_FAILED",
                category=ProviderErrorCategory.AUTH,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            ),
            "RATE_LIMIT": TaskProviderError(
                "PROVIDER_RATE_LIMITED",
                category=ProviderErrorCategory.RATE_LIMIT,
                retryable=True,
                retry_after_seconds=1,
                submission_state=SubmissionState.NOT_SUBMITTED,
            ),
            "UPSTREAM_5XX": TaskProviderError(
                "PROVIDER_UPSTREAM_ERROR",
                category=ProviderErrorCategory.UPSTREAM,
                retryable=True,
                submission_state=SubmissionState.NOT_SUBMITTED,
            ),
            "TIMEOUT": TaskProviderError(
                "PROVIDER_TIMEOUT",
                category=ProviderErrorCategory.TIMEOUT,
                retryable=True,
                submission_state=SubmissionState.NOT_SUBMITTED,
            ),
            "INVALID_RESPONSE": TaskProviderError(
                "PROVIDER_INVALID_RESPONSE",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.CONFIRMED,
            ),
            "SAFETY_REJECTED": TaskProviderError(
                "PROVIDER_SAFETY_REJECTED",
                category=ProviderErrorCategory.SAFETY_REJECTED,
                retryable=False,
                submission_state=SubmissionState.CONFIRMED,
            ),
            "UNKNOWN_SUBMIT": TaskProviderError(
                "PROVIDER_SUBMISSION_UNKNOWN",
                category=ProviderErrorCategory.TIMEOUT,
                retryable=False,
                submission_state=SubmissionState.UNKNOWN,
            ),
        }
        if mode != "NONE":
            raise failures[mode]
