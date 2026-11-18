"""Mask-only Wan image editing using the existing bounded DashScope image transport.

Only payload/result projections differ from Qwen. Submission certainty, endpoint
allowlisting, GET-only continuation, polling and safe errors are reused.
"""

from datetime import UTC, datetime, timedelta
from time import monotonic
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, Field, StrictInt, ValidationError

from app.creative.models import CreativeSourceMode
from app.providers.models import (
    ProviderArtifact,
    ProviderUsage,
    SubmissionState,
    TaskProviderCapability,
    TaskProviderContinuation,
    TaskProviderError,
    TaskProviderRequest,
    TaskProviderResult,
    WanImageEditProviderContinuation,
)
from app.providers.qwen_image import DashScopeImageResponse, QwenImageAdapter


class _EditResult(BaseModel):
    model_config = ConfigDict(extra="ignore")
    url: str | None = None
    code: str | None = None


class _EditOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")
    task_id: str | None = None
    task_status: str | None = None
    code: str | None = None
    results: list[_EditResult] = Field(default_factory=lambda: list[_EditResult]())


class _EditUsage(BaseModel):
    model_config = ConfigDict(extra="ignore")
    image_count: StrictInt | None = Field(default=None, ge=0)


class _EditResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")
    request_id: str | None = None
    code: str | None = None
    output: _EditOutput | None = None
    usage: _EditUsage | None = None


class WanImageEditAdapter(QwenImageAdapter):
    """An explicitly selected mask provider, never a fallback for ordinary images."""

    code = "WAN_IMAGE_EDIT"
    DEFAULT_ENDPOINT = (
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/image2image/image-synthesis"
    )
    ENDPOINT_PATH = "/api/v1/services/aigc/image2image/image-synthesis"
    SUPPORTED_MODELS = frozenset(("wanx2.1-imageedit",))
    SUPPORTED_CAPABILITIES = frozenset((TaskProviderCapability.MASK_IMAGE_EDIT,))
    CONTINUATION_TYPE = WanImageEditProviderContinuation

    def validate_request(self, request: TaskProviderRequest) -> None:
        """Preflight for the explicit Gate; performs no upstream requests."""
        self._validate_execution_request(request)
        self._build_payload(request)

    async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
        try:
            return await super().execute(request)
        except TaskProviderError as exc:
            raise self._translate_error(exc) from None

    async def resume(
        self, request: TaskProviderRequest, continuation: TaskProviderContinuation
    ) -> TaskProviderResult:
        try:
            return await super().resume(request, continuation)
        except TaskProviderError as exc:
            raise self._translate_error(exc) from None

    def _mark_unavailable(self, code: str) -> None:
        super()._mark_unavailable(code.replace("QWEN_IMAGE_", "WAN_IMAGE_EDIT_", 1))

    @staticmethod
    def _translate_error(error: TaskProviderError) -> TaskProviderError:
        return TaskProviderError(
            error.code.replace("QWEN_IMAGE_", "WAN_IMAGE_EDIT_", 1),
            category=error.category,
            retryable=error.retryable,
            submission_state=error.submission_state,
            retry_after_seconds=error.retry_after_seconds,
            provider_request_id=error.provider_request_id,
            provider_task_id=error.provider_task_id,
        )

    def _build_payload(self, request: TaskProviderRequest) -> tuple[dict[str, object], None, int]:
        brief = request.creative_brief
        mask = request.controlled_mask_asset
        if (
            self.model not in self.SUPPORTED_MODELS
            or brief is None
            or brief.source_mode is not CreativeSourceMode.REGION_EDIT
            or len(request.reference_assets) != 1
            or mask is None
            or mask.asset_id != brief.creative_direction.get("maskAssetId")
            or mask.asset_id != request.parameters.get("maskAssetId")
        ):
            raise self._local_error("WAN_IMAGE_EDIT_SOURCE_INVALID")
        brief.validate_reference_assets(request.reference_assets)
        base = request.reference_assets[0]
        if (
            (base.width, base.height) != (mask.width, mask.height)
            or mask.content_type != "image/png"
            or any(
                item.content_type
                not in {
                    "image/png",
                    "image/jpeg",
                    "image/jpg",
                    "image/bmp",
                    "image/tiff",
                    "image/webp",
                }
                or item.size_bytes > 10 * 1024 * 1024
                or not 512 <= item.width <= 4096
                or not 512 <= item.height <= 4096
                for item in (base, mask)
            )
        ):
            raise self._local_error("WAN_IMAGE_EDIT_MASK_INVALID")
        prompt = self._optional_string(request.parameters, "prompt", max_length=800)
        if prompt is None:
            prompt = self._optional_string(request.parameters, "scene", max_length=800)
        if not prompt:
            raise self._local_error("WAN_IMAGE_EDIT_PROMPT_REQUIRED")
        count = self._optional_int(request.parameters, "count", default=1, minimum=1, maximum=4)
        return (
            {
                "model": self.model,
                "input": {
                    "function": "description_edit_with_mask",
                    "prompt": prompt,
                    "base_image_url": base.read_url,
                    "mask_image_url": mask.read_url,
                },
                "parameters": {"n": count, "watermark": False},
            },
            None,
            count,
        )

    def _parse_response(
        self, response: httpx.Response, *, submission_state: SubmissionState
    ) -> DashScopeImageResponse:
        try:
            parsed = _EditResponse.model_validate_json(response.content)
        except ValidationError:
            raise self._invalid_response(submission_state) from None
        output = parsed.output
        if output is not None and any(
            item.url is not None and item.code is not None for item in output.results
        ):
            raise self._invalid_response(submission_state)
        normalized = {
            "request_id": parsed.request_id,
            "code": parsed.code,
            "output": None
            if output is None
            else {
                "task_id": output.task_id,
                "task_status": output.task_status,
                "code": output.code,
                "choices": [
                    {"message": {"content": [{"image": item.url}]}}
                    for item in output.results
                    if item.url is not None
                ],
            },
            "usage": None
            if parsed.usage is None
            else {
                "output_image_count": parsed.usage.image_count,
            },
        }
        try:
            return DashScopeImageResponse.model_validate(normalized)
        except ValidationError:
            raise self._invalid_response(submission_state) from None

    def _result(
        self,
        request: TaskProviderRequest,
        response: DashScopeImageResponse,
        *,
        provider_task_id: str,
        provider_request_id: UUID,
        requested_size: str | None,
        requested_count: int,
        started: float,
    ) -> TaskProviderResult:
        output = response.output
        urls = (
            []
            if output is None
            else [
                item.image
                for choice in output.choices
                for item in choice.message.content
                if item.image is not None
            ]
        )
        count = len(urls)
        if (
            not 1 <= count <= requested_count
            or len(set(urls)) != count
            or response.usage is None
            or response.usage.output_image_count != count
            or any(not self._is_artifact_url(url) for url in urls)
        ):
            raise self._result_count_mismatch(
                provider_request_id=str(provider_request_id),
                provider_task_id=provider_task_id,
            )
        return TaskProviderResult(
            provider=self.code,
            model=self.model,
            request_id=provider_request_id,
            provider_task_id=provider_task_id,
            artifacts=tuple(
                ProviderArtifact(
                    artifact_type="IMAGE",
                    unit_key=f"image:{index}",
                    provider_url=url,
                    # The API does not report dimensions, hashes, byte size or MIME.
                    # Java sniffs and validates downloaded content before compositing.
                    expires_at=datetime.now(UTC) + timedelta(hours=24),
                    metadata={
                        "index": index,
                        "mock": False,
                        "width": None,
                        "height": None,
                        "providerRequestId": str(provider_request_id),
                    },
                )
                for index, url in enumerate(urls, start=1)
            ),
            usage=ProviderUsage(images=count),
            latency_ms=max(0, round((monotonic() - started) * 1000)),
        )
