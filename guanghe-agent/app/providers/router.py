"""Task-type router for configured real Worker Provider adapters."""

import asyncio
from collections.abc import Mapping

from app.common.enums import ProviderStatus, TaskType
from app.creative.models import CreativeSourceMode
from app.providers.models import (
    ProviderErrorCategory,
    ResumableTaskProviderAdapter,
    SubmissionState,
    TaskProviderAdapter,
    TaskProviderCapability,
    TaskProviderContinuation,
    TaskProviderError,
    TaskProviderRequest,
    TaskProviderResult,
)


class RoutedTaskProvider:
    """Dispatch a Worker request only to the explicitly configured domain adapter."""

    is_mock = False

    def __init__(
        self,
        adapters: Mapping[TaskType, TaskProviderAdapter],
        *,
        image_edit_adapter: TaskProviderAdapter | None = None,
    ) -> None:
        if not adapters:
            raise TaskProviderError(
                "REAL_TASK_PROVIDER_NOT_CONFIGURED",
                category=ProviderErrorCategory.AUTH,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )
        self._adapters = dict(adapters)
        self._image_edit_adapter = image_edit_adapter

    def _all_adapters(self) -> tuple[TaskProviderAdapter, ...]:
        return (
            *self._adapters.values(),
            *((self._image_edit_adapter,) if self._image_edit_adapter is not None else ()),
        )

    def resolve(
        self, task_type: TaskType, source_mode: CreativeSourceMode | None
    ) -> TaskProviderAdapter:
        adapter = (
            self._image_edit_adapter
            if source_mode is CreativeSourceMode.REGION_EDIT
            else self._adapters.get(task_type)
        )
        if adapter is None or (
            source_mode is CreativeSourceMode.REGION_EDIT and task_type is not TaskType.IMAGE
        ):
            raise TaskProviderError(
                "WAN_IMAGE_EDIT_NOT_CONFIGURED"
                if source_mode is CreativeSourceMode.REGION_EDIT
                else f"REAL_PROVIDER_NOT_CONFIGURED_FOR_{task_type.value}",
                category=ProviderErrorCategory.UPSTREAM,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )
        return adapter

    @property
    def code(self) -> str:
        if len(self._adapters) == 1:
            return next(iter(self._adapters.values())).code
        return "DASHSCOPE_TASK_ROUTER"

    @property
    def model(self) -> str:
        if len(self._adapters) == 1:
            return next(iter(self._adapters.values())).model
        return ",".join(
            f"{task_type.value}={adapter.model}"
            for task_type, adapter in sorted(self._adapters.items(), key=lambda item: item[0].value)
        )

    @property
    def status(self) -> ProviderStatus:
        statuses = {adapter.status for adapter in self._all_adapters()}
        if ProviderStatus.UNAVAILABLE in statuses:
            return ProviderStatus.UNAVAILABLE
        if ProviderStatus.DISABLED in statuses:
            return ProviderStatus.DISABLED
        if statuses == {ProviderStatus.AVAILABLE}:
            return ProviderStatus.AVAILABLE
        return ProviderStatus.CONFIGURED

    @property
    def capabilities(self) -> frozenset[TaskProviderCapability]:
        return frozenset(
            capability for adapter in self._all_adapters() for capability in adapter.capabilities
        )

    async def probe(self) -> bool:
        results = await asyncio.gather(
            *(adapter.probe() for adapter in self._all_adapters()),
            return_exceptions=True,
        )
        return all(result is True for result in results)

    async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
        adapter = self.resolve(
            request.task_type,
            request.creative_brief.source_mode if request.creative_brief is not None else None,
        )
        return await adapter.execute(request)

    async def resume(
        self,
        request: TaskProviderRequest,
        continuation: TaskProviderContinuation,
    ) -> TaskProviderResult:
        adapter = self.resolve(
            request.task_type,
            request.creative_brief.source_mode if request.creative_brief is not None else None,
        )
        if not isinstance(adapter, ResumableTaskProviderAdapter):
            raise TaskProviderError(
                "REAL_PROVIDER_CONTINUATION_UNSUPPORTED",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.UNKNOWN,
                provider_request_id=str(continuation.provider_request_id),
                provider_task_id=continuation.provider_task_id,
            )
        return await adapter.resume(request, continuation)

    async def aclose(self) -> None:
        await asyncio.gather(
            *(adapter.aclose() for adapter in self._all_adapters()),
            return_exceptions=True,
        )
