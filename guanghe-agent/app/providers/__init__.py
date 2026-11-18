"""Provider-neutral task adapters, real routing, and local deterministic mocks."""

from app.common.enums import TaskType
from app.common.settings import AppSettings, ProviderSettings
from app.prompts.runtime import load_commerce_prompt_set
from app.providers.mock import LocalTaskProviderMock
from app.providers.models import (
    ProviderErrorCategory,
    SubmissionState,
    TaskProviderAdapter,
    TaskProviderError,
)
from app.providers.qwen_image import QwenImageAdapter
from app.providers.router import RoutedTaskProvider
from app.providers.wan_image_edit import WanImageEditAdapter
from app.providers.wan_video import WanVideoAdapter


def build_task_provider(settings: AppSettings) -> TaskProviderAdapter:
    """Build only the explicitly requested Worker Provider mode; never fall back silently."""
    if settings.worker_provider == "MOCK":
        return LocalTaskProviderMock(settings.provider_mock)

    selected = settings.worker_task_type
    if selected in {"GEO", "ARTICLE"}:
        raise _configuration_error(f"REAL_PROVIDER_NOT_IMPLEMENTED_FOR_{selected}")
    if selected == "ALL":
        raise _configuration_error("REAL_PROVIDER_ALL_DOMAINS_NOT_IMPLEMENTED")

    adapters: dict[TaskType, TaskProviderAdapter] = {}
    image_edit_adapter: TaskProviderAdapter | None = None
    prompts = load_commerce_prompt_set(settings.bundle_path)
    if selected in {"IMAGE", "IMAGE_VIDEO"} and _provider_is_configured(settings.qwen_image):
        try:
            adapters[TaskType.IMAGE] = QwenImageAdapter(
                settings.qwen_image,
                execution_prompt=prompts.for_execution(TaskType.IMAGE),
            )
        except ValueError as exc:
            raise _configuration_error("QWEN_IMAGE_CONFIGURATION_INVALID") from exc
    if selected in {"VIDEO", "IMAGE_VIDEO"} and _provider_is_configured(settings.wan_video):
        try:
            adapters[TaskType.VIDEO] = WanVideoAdapter(
                settings.wan_video,
                execution_prompt=prompts.for_execution(TaskType.VIDEO),
            )
        except ValueError as exc:
            raise _configuration_error("WAN_VIDEO_CONFIGURATION_INVALID") from exc
    if selected in {"IMAGE", "IMAGE_VIDEO"} and settings.wan_image_edit.enabled:
        if (
            not _provider_is_configured(settings.wan_image_edit)
            or settings.wan_image_edit.model != "wanx2.1-imageedit"
        ):
            raise _configuration_error("WAN_IMAGE_EDIT_NOT_CONFIGURED")
        try:
            image_edit_adapter = WanImageEditAdapter(
                settings.wan_image_edit,
                execution_prompt=prompts.for_execution(TaskType.IMAGE),
            )
        except ValueError as exc:
            raise _configuration_error("WAN_IMAGE_EDIT_CONFIGURATION_INVALID") from exc

    if selected == "IMAGE" and TaskType.IMAGE not in adapters:
        raise _configuration_error("QWEN_IMAGE_NOT_CONFIGURED")
    if selected == "VIDEO" and TaskType.VIDEO not in adapters:
        raise _configuration_error("WAN_VIDEO_NOT_CONFIGURED")
    if selected == "IMAGE_VIDEO" and set(adapters) != {TaskType.IMAGE, TaskType.VIDEO}:
        raise _configuration_error("IMAGE_VIDEO_PROVIDERS_NOT_CONFIGURED")
    if not adapters:
        raise _configuration_error("REAL_TASK_PROVIDER_NOT_CONFIGURED")
    return RoutedTaskProvider(adapters, image_edit_adapter=image_edit_adapter)


def _provider_is_configured(provider: ProviderSettings) -> bool:
    return provider.enabled and provider.credential is not None and provider.model is not None


def _configuration_error(code: str) -> TaskProviderError:
    return TaskProviderError(
        code,
        category=ProviderErrorCategory.AUTH,
        retryable=False,
        submission_state=SubmissionState.NOT_SUBMITTED,
    )


__all__ = ["LocalTaskProviderMock", "TaskProviderAdapter", "build_task_provider"]
