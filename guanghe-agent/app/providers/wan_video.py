"""Bounded Wan 3.0 text-to-video Provider adapter."""

import asyncio
import logging
import math
from collections.abc import Awaitable, Callable, Mapping
from time import monotonic
from typing import Literal
from urllib.parse import quote, urlsplit, urlunsplit
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, Field, JsonValue, ValidationError
from pydantic.alias_generators import to_camel

from app.common.enums import ProviderStatus, TaskType
from app.common.settings import ProviderSettings
from app.creative.brief import creative_grounding_instruction
from app.creative.models import CreativeReferenceAsset, CreativeSourceMode, ProductCreativeBrief
from app.harness.runtime import ProviderCallBoundaryError, record_provider_call
from app.observability.trace import parse_traceparent
from app.observability.tracing import (
    add_current_span_event,
    current_otel_traceparent,
    set_current_span_attribute,
    start_span,
)
from app.providers.models import (
    ProviderArtifact,
    ProviderErrorCategory,
    ProviderUsage,
    SubmissionState,
    TaskProviderCapability,
    TaskProviderContinuation,
    TaskProviderError,
    TaskProviderRequest,
    TaskProviderResult,
    WanVideoProviderContinuation,
)

DEFAULT_WAN_VIDEO_ENDPOINT = (
    "https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis"
)
_ASYNC_TASK_PATH_MARKER = "/api/v1/"
_PROBE_TASK_ID = "00000000-0000-0000-0000-000000000000"
_SUPPORTED_MODELS = {"wan3.0-video", "wan3.0-video-prime"}
_PENDING_STATUSES = {"PENDING", "RUNNING"}
_TASK_ID_PATTERN = r"^[A-Za-z0-9_-]{1,256}$"

Sleep = Callable[[float], Awaitable[None]]
SubmissionObserver = Callable[[UUID, str], None]
TaskStatus = Literal["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED", "UNKNOWN"]

logger = logging.getLogger(__name__)


class _WanModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class _VideoParameters(BaseModel):
    """Strict Provider-specific projection of the existing VIDEO parameter schema."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        strict=True,
    )

    scene: str = Field(min_length=1, max_length=1_000)
    style: str | None = Field(default=None, min_length=1, max_length=256)
    duration_seconds: int = Field(default=5, ge=2, le=30)
    aspect_ratio: Literal["adaptive", "16:9", "4:3", "1:1", "3:4", "9:16"] = "adaptive"
    camera_motion: str | None = Field(default=None, min_length=1, max_length=256)
    resolution: Literal["480P", "720P", "1080P"] = "720P"
    audio: bool = False
    seed: int = Field(default=-1, ge=-1, le=2_147_483_647)
    prompt_extend: bool = True
    watermark: bool = False


class _TaskOutput(_WanModel):
    task_id: str = Field(pattern=_TASK_ID_PATTERN)
    task_status: TaskStatus
    video_url: str | None = Field(default=None, max_length=2_048)
    code: str | None = Field(default=None, max_length=256)


class _TaskUsage(_WanModel):
    duration: float | None = Field(default=None, ge=0)
    input_video_duration: float | None = Field(default=None, ge=0)
    output_video_duration: float | None = Field(default=None, ge=0)
    video_count: int | None = Field(default=None, ge=0)
    fps: int | None = Field(default=None, ge=0)
    resolution: int | None = Field(default=None, alias="SR", ge=0)
    ratio: str | None = Field(default=None, max_length=16)


class _TaskEnvelope(_WanModel):
    output: _TaskOutput
    request_id: UUID | None = None
    usage: _TaskUsage | None = None
    code: str | None = Field(default=None, max_length=256)


class _ErrorEnvelope(_WanModel):
    code: str | None = Field(default=None, max_length=256)


class _IdentifierOutput(_WanModel):
    task_id: str | None = Field(default=None, max_length=256)


class _IdentifierEnvelope(_WanModel):
    request_id: str | None = Field(default=None, max_length=256)
    output: _IdentifierOutput | None = None


class WanVideoAdapter:
    """Submit at most once, then boundedly poll or resume one Wan video job."""

    code = "WAN_VIDEO"
    is_mock = False
    capabilities = frozenset(
        (
            TaskProviderCapability.IMAGE_TO_VIDEO,
            TaskProviderCapability.TEXT_TO_VIDEO,
            TaskProviderCapability.REFERENCE_TO_VIDEO,
        )
    )

    def __init__(
        self,
        settings: ProviderSettings,
        *,
        client: httpx.AsyncClient | None = None,
        sleep: Sleep = asyncio.sleep,
        poll_interval_seconds: float = 15.0,
        poll_timeout_seconds: float = 900.0,
        max_poll_attempts: int = 60,
        submission_observer: SubmissionObserver | None = None,
        execution_prompt: str | None = None,
    ) -> None:
        if not settings.enabled:
            raise self._local_error("WAN_VIDEO_DISABLED", ProviderErrorCategory.UPSTREAM)
        if settings.credential is None:
            raise self._local_error(
                "WAN_VIDEO_CREDENTIAL_NOT_CONFIGURED", ProviderErrorCategory.AUTH
            )
        if settings.model not in _SUPPORTED_MODELS:
            raise self._local_error(
                "WAN_VIDEO_MODEL_NOT_SUPPORTED",
                ProviderErrorCategory.INVALID_RESPONSE,
            )
        if poll_interval_seconds <= 0 or poll_timeout_seconds <= 0 or max_poll_attempts <= 0:
            raise ValueError("Wan polling interval, timeout, and attempt limit must be positive")

        endpoint = (settings.endpoint or DEFAULT_WAN_VIDEO_ENDPOINT).rstrip("/")
        self._poll_base_url = self._derive_poll_base_url(endpoint)
        self._settings = settings
        self._model = settings.model
        if execution_prompt is None:
            from app.common.settings import default_bundle_path
            from app.prompts.runtime import load_execution_prompt

            execution_prompt = load_execution_prompt(default_bundle_path(), TaskType.VIDEO)
        self._execution_prompt = execution_prompt.strip()
        if not self._execution_prompt:
            raise ValueError("WAN_VIDEO_EXECUTION_PROMPT_REQUIRED")
        self._endpoint = endpoint
        self._headers = {
            "Authorization": f"Bearer {settings.credential.get_secret_value()}",
            "Content-Type": "application/json",
        }
        self._sleep = sleep
        self._poll_interval_seconds = poll_interval_seconds
        self._poll_timeout_seconds = poll_timeout_seconds
        self._max_poll_attempts = max_poll_attempts
        self._submission_observer = submission_observer
        self._status = ProviderStatus.CONFIGURED
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(settings.timeout_seconds),
            trust_env=settings.trust_env,
        )

    @property
    def model(self) -> str:
        return self._model

    @property
    def status(self) -> ProviderStatus:
        return self._status

    async def probe(self) -> bool:
        """Verify credential and the Beijing query endpoint without submitting a task."""
        try:
            response = await self._client.get(
                self._task_url(_PROBE_TASK_ID),
                headers=self._headers,
                timeout=self._settings.probe_timeout_seconds,
            )
            if not response.is_success:
                raise self._http_error(response, during_submission=False)
            envelope = _TaskEnvelope.model_validate_json(response.content)
            available = (
                envelope.output.task_id == _PROBE_TASK_ID
                and envelope.output.task_status == "UNKNOWN"
            )
        except (httpx.TimeoutException, httpx.RequestError, ValidationError, TaskProviderError):
            self._status = ProviderStatus.UNAVAILABLE
            return False
        self._status = ProviderStatus.AVAILABLE if available else ProviderStatus.UNAVAILABLE
        return available

    async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
        self._validate_execution_request(request)
        parameters = self._validate_parameters(request)
        references, brief = self._reference_input(request)
        grounded_prompt = self._grounded_prompt(parameters, brief)
        started = monotonic()
        headers = self._request_headers(request)
        envelope = await self._submit(
            parameters,
            references=references,
            source_mode=brief.source_mode,
            prompt=grounded_prompt,
            headers=headers,
        )
        provider_task_id = envelope.output.task_id
        provider_request_id = envelope.request_id
        if provider_request_id is None:
            raise self._unknown_error(
                "WAN_VIDEO_SUBMISSION_RESPONSE_INVALID",
                ProviderErrorCategory.INVALID_RESPONSE,
                provider_task_id=provider_task_id,
            )
        try:
            if self._submission_observer is not None:
                try:
                    self._submission_observer(provider_request_id, provider_task_id)
                except Exception as exc:
                    raise self._unknown_error(
                        "WAN_VIDEO_SUBMISSION_STATE_PERSIST_FAILED",
                        ProviderErrorCategory.UPSTREAM,
                        provider_request_id=str(provider_request_id),
                        provider_task_id=provider_task_id,
                    ) from exc
            completed = await self._wait_for_completion(
                provider_task_id,
                provider_request_id=provider_request_id,
                envelope=envelope,
                headers=headers,
                deadline=started + self._poll_timeout_seconds,
            )
            result = self._result(
                request,
                parameters,
                completed,
                provider_request_id=provider_request_id,
                started=started,
            )
        except TaskProviderError:
            raise
        except Exception as exc:
            raise self._unknown_error(
                "WAN_VIDEO_EXECUTION_UNEXPECTED",
                ProviderErrorCategory.INVALID_RESPONSE,
                provider_request_id=str(provider_request_id),
                provider_task_id=provider_task_id,
            ) from exc
        self._status = ProviderStatus.AVAILABLE
        return result

    async def resume(
        self,
        request: TaskProviderRequest,
        continuation: TaskProviderContinuation,
    ) -> TaskProviderResult:
        """GET-only recovery of one explicitly authorized uncertain Wan task."""
        self._validate_execution_request(request)
        if not isinstance(continuation, WanVideoProviderContinuation) or (
            continuation.provider != self.code
            or continuation.model != self.model
            or continuation.provider_request_id != request.request_id
        ):
            raise self._continuation_error(continuation)
        try:
            parameters = self._validate_parameters(request)
            started = monotonic()
            envelope = _TaskEnvelope(
                request_id=continuation.provider_request_id,
                output=_TaskOutput(
                    task_id=continuation.provider_task_id,
                    task_status="PENDING",
                ),
            )
            completed = await self._wait_for_completion(
                continuation.provider_task_id,
                provider_request_id=continuation.provider_request_id,
                envelope=envelope,
                headers=self._request_headers(request),
                deadline=started + self._poll_timeout_seconds,
            )
            result = self._result(
                request,
                parameters,
                completed,
                provider_request_id=continuation.provider_request_id,
                started=started,
            )
        except TaskProviderError:
            raise
        except Exception as exc:
            raise self._unknown_error(
                "WAN_VIDEO_EXECUTION_UNEXPECTED",
                ProviderErrorCategory.INVALID_RESPONSE,
                provider_request_id=str(continuation.provider_request_id),
                provider_task_id=continuation.provider_task_id,
            ) from exc
        self._status = ProviderStatus.AVAILABLE
        return result

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    def _validate_execution_request(self, request: TaskProviderRequest) -> None:
        if request.task_type is not TaskType.VIDEO:
            raise self._local_error(
                "WAN_VIDEO_TASK_TYPE_UNSUPPORTED",
                ProviderErrorCategory.INVALID_RESPONSE,
            )

    def _request_headers(self, request: TaskProviderRequest) -> dict[str, str]:
        headers = dict(self._headers)
        if request.traceparent is not None:
            headers["traceparent"] = current_otel_traceparent() or request.traceparent
        return headers

    async def _submit(
        self,
        parameters: _VideoParameters,
        *,
        references: tuple[CreativeReferenceAsset, ...],
        source_mode: CreativeSourceMode,
        prompt: str,
        headers: Mapping[str, str],
    ) -> _TaskEnvelope:
        provider_input: dict[str, object] = {"prompt": prompt}
        if source_mode is not CreativeSourceMode.TEXT_ONLY:
            reference_type = (
                "reference_image"
                if source_mode is CreativeSourceMode.PRODUCT_MATERIALS
                else "first_frame"
            )
            provider_input["media"] = [
                {"type": reference_type, "url": item.read_url} for item in references
            ]
        payload = {
            "model": self._model,
            "input": provider_input,
            "parameters": {
                "duration": parameters.duration_seconds,
                "ratio": parameters.aspect_ratio,
                "resolution": parameters.resolution,
                "audio": parameters.audio,
                "seed": parameters.seed,
                "prompt_extend": parameters.prompt_extend,
                "watermark": parameters.watermark,
            },
        }
        try:
            try:
                record_provider_call()
            except ProviderCallBoundaryError as exc:
                raise self._local_error(
                    f"WAN_VIDEO_{exc.code}",
                    ProviderErrorCategory.TIMEOUT,
                ) from None
            self._log_http_call(headers, operation="SUBMIT", attempt=1)
            response = await self._client.post(
                self._endpoint,
                headers={**headers, "X-DashScope-Async": "enable"},
                json=payload,
            )
        except httpx.RequestError as exc:
            self._status = ProviderStatus.UNAVAILABLE
            raise self._submission_transport_error(exc) from exc
        if not response.is_success:
            error = self._http_error(response, during_submission=True)
            self._status = ProviderStatus.UNAVAILABLE
            raise error
        try:
            envelope = _TaskEnvelope.model_validate_json(response.content)
        except ValidationError as exc:
            provider_request_id, provider_task_id = self._extract_identifiers(response)
            self._status = ProviderStatus.UNAVAILABLE
            raise self._unknown_error(
                "WAN_VIDEO_SUBMISSION_RESPONSE_INVALID",
                ProviderErrorCategory.INVALID_RESPONSE,
                provider_request_id=provider_request_id,
                provider_task_id=provider_task_id,
            ) from exc
        except Exception as exc:
            provider_request_id, provider_task_id = self._extract_identifiers(response)
            if provider_request_id is None or provider_task_id is None:
                raise
            raise self._unknown_error(
                "WAN_VIDEO_SUBMISSION_RESPONSE_INVALID",
                ProviderErrorCategory.INVALID_RESPONSE,
                provider_request_id=str(provider_request_id),
                provider_task_id=provider_task_id,
            ) from exc
        return envelope

    async def _wait_for_completion(
        self,
        provider_task_id: str,
        *,
        provider_request_id: UUID,
        envelope: _TaskEnvelope,
        headers: Mapping[str, str],
        deadline: float,
    ) -> _TaskEnvelope:
        with start_span(
            "provider.poll",
            attributes={
                "provider.name": self.code,
                "provider.model": self.model,
                "provider.operation": "poll",
            },
        ) as span:
            try:
                return await self._wait_for_completion_impl(
                    provider_task_id,
                    provider_request_id=provider_request_id,
                    envelope=envelope,
                    headers=headers,
                    deadline=deadline,
                )
            except TaskProviderError as exc:
                span.fail(exc.code)
                raise

    async def _wait_for_completion_impl(
        self,
        provider_task_id: str,
        *,
        provider_request_id: UUID,
        envelope: _TaskEnvelope,
        headers: Mapping[str, str],
        deadline: float,
    ) -> _TaskEnvelope:
        current = self._terminal_or_none(
            provider_task_id,
            envelope,
            provider_request_id=provider_request_id,
        )
        if current is not None:
            return current

        attempts = 0
        while attempts < self._max_poll_attempts:
            await self._wait_until_next_poll(
                deadline,
                provider_request_id=provider_request_id,
                provider_task_id=provider_task_id,
            )
            attempts += 1
            set_current_span_attribute("pollCount", attempts)
            add_current_span_event("provider.poll.attempt", {"pollNo": attempts})
            try:
                try:
                    record_provider_call()
                except ProviderCallBoundaryError as exc:
                    raise self._unknown_error(
                        f"WAN_VIDEO_{exc.code}",
                        ProviderErrorCategory.TIMEOUT,
                        provider_request_id=str(provider_request_id),
                        provider_task_id=provider_task_id,
                    ) from None
                self._log_http_call(headers, operation="POLL", attempt=attempts)
                response = await self._client.get(
                    self._task_url(provider_task_id),
                    headers=headers,
                )
            except (httpx.TimeoutException, httpx.RequestError):
                self._status = ProviderStatus.UNAVAILABLE
                continue
            if not response.is_success:
                error = self._http_error(
                    response,
                    during_submission=False,
                    provider_request_id=str(provider_request_id),
                    provider_task_id=provider_task_id,
                )
                if error.category in {
                    ProviderErrorCategory.RATE_LIMIT,
                    ProviderErrorCategory.UPSTREAM,
                }:
                    self._status = ProviderStatus.UNAVAILABLE
                    continue
                raise error
            try:
                current_envelope = _TaskEnvelope.model_validate_json(response.content)
            except ValidationError as exc:
                raise self._unknown_error(
                    "WAN_VIDEO_POLL_RESPONSE_INVALID",
                    ProviderErrorCategory.INVALID_RESPONSE,
                    provider_request_id=str(provider_request_id),
                    provider_task_id=provider_task_id,
                ) from exc
            current = self._terminal_or_none(
                provider_task_id,
                current_envelope,
                provider_request_id=provider_request_id,
            )
            if current is not None:
                return current

        raise self._poll_timeout(provider_request_id, provider_task_id)

    def _log_http_call(
        self,
        headers: Mapping[str, str],
        *,
        operation: Literal["SUBMIT", "POLL"],
        attempt: int,
    ) -> None:
        trace = parse_traceparent(headers.get("traceparent"))
        logger.info(
            "wan_video_http_call_started",
            extra={
                "traceId": trace.trace_id if trace is not None else None,
                "provider": self.code,
                "operation": operation,
                "attempt": attempt,
            },
        )

    def _terminal_or_none(
        self,
        provider_task_id: str,
        envelope: _TaskEnvelope,
        *,
        provider_request_id: UUID,
    ) -> _TaskEnvelope | None:
        output = envelope.output
        if output.task_id != provider_task_id:
            raise self._unknown_error(
                "WAN_VIDEO_TASK_ID_MISMATCH",
                ProviderErrorCategory.INVALID_RESPONSE,
                provider_request_id=str(provider_request_id),
                provider_task_id=provider_task_id,
            )
        if output.task_status in _PENDING_STATUSES:
            return None
        if output.task_status == "SUCCEEDED":
            return envelope
        if output.task_status == "FAILED":
            provider_code = output.code or envelope.code
            if self._is_safety_code(provider_code):
                raise self._confirmed_error(
                    "WAN_VIDEO_SAFETY_REJECTED",
                    ProviderErrorCategory.SAFETY_REJECTED,
                    provider_request_id,
                    provider_task_id,
                )
            raise self._confirmed_error(
                "WAN_VIDEO_TASK_FAILED",
                ProviderErrorCategory.UPSTREAM,
                provider_request_id,
                provider_task_id,
            )
        if output.task_status == "CANCELED":
            raise self._confirmed_error(
                "WAN_VIDEO_TASK_CANCELED",
                ProviderErrorCategory.UPSTREAM,
                provider_request_id,
                provider_task_id,
            )
        raise self._unknown_error(
            "WAN_VIDEO_TASK_UNKNOWN",
            ProviderErrorCategory.INVALID_RESPONSE,
            provider_request_id=str(provider_request_id),
            provider_task_id=provider_task_id,
        )

    async def _wait_until_next_poll(
        self,
        deadline: float,
        *,
        provider_request_id: UUID,
        provider_task_id: str,
    ) -> None:
        remaining = deadline - monotonic()
        if remaining <= 0:
            raise self._poll_timeout(provider_request_id, provider_task_id)
        await self._sleep(min(self._poll_interval_seconds, remaining))
        if monotonic() >= deadline:
            raise self._poll_timeout(provider_request_id, provider_task_id)

    def _result(
        self,
        request: TaskProviderRequest,
        parameters: _VideoParameters,
        envelope: _TaskEnvelope,
        *,
        provider_request_id: UUID,
        started: float,
    ) -> TaskProviderResult:
        output = envelope.output
        usage = envelope.usage
        if output.video_url is None or usage is None or usage.video_count != 1:
            raise self._confirmed_error(
                "WAN_VIDEO_SUCCESS_RESPONSE_INVALID",
                ProviderErrorCategory.INVALID_RESPONSE,
                provider_request_id,
                output.task_id,
            )
        video_url = self._validate_artifact_url(output.video_url)
        duration = usage.output_video_duration
        if duration is None:
            duration = usage.duration
        if duration is None or duration <= 0 or abs(duration - parameters.duration_seconds) > 0.5:
            raise self._confirmed_error(
                "WAN_VIDEO_DURATION_MISMATCH",
                ProviderErrorCategory.INVALID_RESPONSE,
                provider_request_id,
                output.task_id,
            )
        video_seconds = math.ceil(duration)
        metadata: dict[str, JsonValue] = {
            "durationSeconds": duration,
            "requestedDurationSeconds": parameters.duration_seconds,
            "requestedAspectRatio": parameters.aspect_ratio,
            "requestedResolution": parameters.resolution,
            "mock": False,
            "providerRequestId": str(provider_request_id),
        }
        if usage.fps is not None:
            metadata["fps"] = usage.fps
        if usage.resolution is not None:
            metadata["resolution"] = f"{usage.resolution}P"
        if usage.ratio is not None:
            metadata["aspectRatio"] = usage.ratio
        if envelope.request_id is not None and envelope.request_id != provider_request_id:
            metadata["providerResultRequestId"] = str(envelope.request_id)
        return TaskProviderResult(
            provider=self.code,
            model=self._model,
            request_id=provider_request_id,
            provider_task_id=output.task_id,
            artifacts=(
                ProviderArtifact(
                    artifact_type="VIDEO",
                    unit_key="video:1",
                    provider_url=video_url,
                    content_type="video/mp4",
                    expires_at=None,
                    metadata=metadata,
                ),
            ),
            usage=ProviderUsage(video_seconds=video_seconds),
            latency_ms=max(0, round((monotonic() - started) * 1_000)),
        )

    @staticmethod
    def _validate_parameters(request: TaskProviderRequest) -> _VideoParameters:
        try:
            parameters = _VideoParameters.model_validate(
                {key: value for key, value in request.parameters.items() if key != "geoReportId"}
            )
        except ValidationError as exc:
            raise WanVideoAdapter._local_error(
                "WAN_VIDEO_PARAMETERS_INVALID",
                ProviderErrorCategory.INVALID_RESPONSE,
            ) from exc
        if len(WanVideoAdapter._build_prompt(parameters)) > 20_000:
            raise WanVideoAdapter._local_error(
                "WAN_VIDEO_PROMPT_TOO_LONG",
                ProviderErrorCategory.INVALID_RESPONSE,
            )
        return parameters

    @classmethod
    def _reference_input(
        cls,
        request: TaskProviderRequest,
    ) -> tuple[tuple[CreativeReferenceAsset, ...], ProductCreativeBrief]:
        brief = request.creative_brief
        references = request.reference_assets
        if brief is None:
            raise cls._local_error(
                "IMAGE_TO_VIDEO_MODE_UNAVAILABLE",
                ProviderErrorCategory.INVALID_RESPONSE,
            )
        try:
            brief.validate_reference_assets(references)
        except ValueError as exc:
            raise cls._local_error(
                "REFERENCE_ASSET_NOT_READY",
                ProviderErrorCategory.INVALID_RESPONSE,
            ) from exc
        text_only = brief.source_mode is CreativeSourceMode.TEXT_ONLY
        materials = brief.source_mode is CreativeSourceMode.PRODUCT_MATERIALS
        if (
            brief.source_mode is CreativeSourceMode.REGION_EDIT
            or (text_only and references)
            or (materials and not 1 <= len(references) <= 3)
            or (not text_only and not materials and len(references) != 1)
        ):
            raise cls._local_error(
                "IMAGE_TO_VIDEO_MODE_UNAVAILABLE", ProviderErrorCategory.INVALID_RESPONSE
            )
        if any(
            reference.content_type
            not in {
                "image/jpeg",
                "image/jpg",
                "image/png",
                "image/bmp",
                "image/webp",
            }
            or reference.size_bytes > 20 * 1024 * 1024
            or not 240 <= reference.width <= 8000
            or not 240 <= reference.height <= 8000
            or max(reference.width, reference.height) / min(reference.width, reference.height) > 8
            for reference in references
        ):
            raise cls._local_error(
                "REFERENCE_ASSET_NOT_READY",
                ProviderErrorCategory.INVALID_RESPONSE,
            )
        return references, brief

    def _grounded_prompt(
        self,
        parameters: _VideoParameters,
        brief: ProductCreativeBrief,
    ) -> str:
        prompt = creative_grounding_instruction(brief, self._execution_prompt)
        if brief.source_mode is not CreativeSourceMode.GEO_REPORT:
            prompt = f"{self._build_prompt(parameters)}。{prompt}"
        if len(prompt) > 20_000:
            raise self._local_error(
                "WAN_VIDEO_PROMPT_TOO_LONG",
                ProviderErrorCategory.INVALID_RESPONSE,
            )
        return prompt

    @staticmethod
    def _build_prompt(parameters: _VideoParameters) -> str:
        sections = [parameters.scene.strip()]
        if parameters.style is not None:
            sections.append(f"风格: {parameters.style.strip()}")
        if parameters.camera_motion is not None:
            sections.append(f"运镜: {parameters.camera_motion.strip()}")
        return "。".join(sections)

    @classmethod
    def _http_error(
        cls,
        response: httpx.Response,
        *,
        during_submission: bool,
        provider_request_id: str | None = None,
        provider_task_id: str | None = None,
    ) -> TaskProviderError:
        provider_code = cls._provider_error_code(response)
        if response.status_code in {401, 403} or cls._is_auth_code(provider_code):
            code = "WAN_VIDEO_AUTH_FAILED"
            category = ProviderErrorCategory.AUTH
        elif cls._is_safety_code(provider_code):
            code = "WAN_VIDEO_SAFETY_REJECTED"
            category = ProviderErrorCategory.SAFETY_REJECTED
        elif response.status_code == 429 or cls._is_rate_limit_code(provider_code):
            code = "WAN_VIDEO_RATE_LIMITED"
            category = ProviderErrorCategory.RATE_LIMIT
        elif response.status_code >= 500:
            code = "WAN_VIDEO_UPSTREAM_ERROR"
            category = ProviderErrorCategory.UPSTREAM
        else:
            code = "WAN_VIDEO_REQUEST_REJECTED"
            category = ProviderErrorCategory.UPSTREAM

        if during_submission:
            state = (
                SubmissionState.UNKNOWN
                if response.status_code >= 500
                else SubmissionState.NOT_SUBMITTED
            )
        elif category is ProviderErrorCategory.SAFETY_REJECTED:
            state = SubmissionState.CONFIRMED
        else:
            state = SubmissionState.UNKNOWN
        return TaskProviderError(
            code,
            category=category,
            retryable=(
                state is SubmissionState.NOT_SUBMITTED
                and category is ProviderErrorCategory.RATE_LIMIT
            ),
            retry_after_seconds=cls._retry_after(response),
            submission_state=state,
            provider_request_id=provider_request_id,
            provider_task_id=provider_task_id,
        )

    @staticmethod
    def _submission_transport_error(exc: httpx.RequestError) -> TaskProviderError:
        definitely_not_submitted = isinstance(
            exc,
            (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout),
        )
        timed_out = isinstance(exc, httpx.TimeoutException)
        return TaskProviderError(
            "WAN_VIDEO_SUBMISSION_TIMEOUT"
            if timed_out
            else "WAN_VIDEO_SUBMISSION_CONNECTION_FAILED",
            category=(
                ProviderErrorCategory.TIMEOUT if timed_out else ProviderErrorCategory.UPSTREAM
            ),
            retryable=definitely_not_submitted,
            submission_state=(
                SubmissionState.NOT_SUBMITTED
                if definitely_not_submitted
                else SubmissionState.UNKNOWN
            ),
        )

    @staticmethod
    def _provider_error_code(response: httpx.Response) -> str | None:
        try:
            return _ErrorEnvelope.model_validate_json(response.content).code
        except ValidationError:
            return None

    @staticmethod
    def _extract_identifiers(response: httpx.Response) -> tuple[str | None, str | None]:
        try:
            payload = _IdentifierEnvelope.model_validate_json(response.content)
        except ValidationError:
            return None, None
        task_id = payload.output.task_id if payload.output is not None else None
        return payload.request_id, task_id

    @staticmethod
    def _is_auth_code(code: str | None) -> bool:
        if code is None:
            return False
        normalized = code.casefold()
        return any(
            marker in normalized
            for marker in ("invalidapikey", "invalid_api_key", "accessdenied", "arrearage")
        )

    @staticmethod
    def _is_rate_limit_code(code: str | None) -> bool:
        if code is None:
            return False
        normalized = code.casefold()
        return any(marker in normalized for marker in ("throttling", "ratequota", "limitrequests"))

    @staticmethod
    def _is_safety_code(code: str | None) -> bool:
        if code is None:
            return False
        normalized = code.casefold()
        return any(
            marker in normalized
            for marker in ("datainspectionfailed", "data_inspection_failed", "customroleblocked")
        )

    @staticmethod
    def _retry_after(response: httpx.Response) -> float | None:
        value = response.headers.get("Retry-After")
        if value is None:
            return None
        try:
            seconds = float(value)
        except ValueError:
            return None
        return seconds if seconds >= 0 else None

    @staticmethod
    def _derive_poll_base_url(endpoint: str) -> str:
        parsed = urlsplit(endpoint)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username is not None:
            raise ValueError("Wan endpoint must be an absolute HTTPS URL")
        host = parsed.hostname.casefold()
        allowed = host == "dashscope.aliyuncs.com" or host.endswith(".cn-beijing.maas.aliyuncs.com")
        if not allowed or parsed.query or parsed.fragment:
            raise ValueError("Wan endpoint must use an approved Beijing DashScope host")
        marker_index = parsed.path.find(_ASYNC_TASK_PATH_MARKER)
        if marker_index < 0 or not parsed.path.endswith("/video-generation/video-synthesis"):
            raise ValueError("Wan endpoint path is not the asynchronous video synthesis API")
        prefix = parsed.path[:marker_index]
        task_path = f"{prefix}/api/v1/tasks"
        return urlunsplit((parsed.scheme, parsed.netloc, task_path, "", ""))

    @staticmethod
    def _validate_artifact_url(value: str) -> str:
        parsed = urlsplit(value)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username is not None:
            raise WanVideoAdapter._local_error(
                "WAN_VIDEO_ARTIFACT_URL_INVALID",
                ProviderErrorCategory.INVALID_RESPONSE,
                state=SubmissionState.CONFIRMED,
            )
        return value

    def _task_url(self, provider_task_id: str) -> str:
        return f"{self._poll_base_url}/{quote(provider_task_id, safe='')}"

    @staticmethod
    def _local_error(
        code: str,
        category: ProviderErrorCategory,
        *,
        state: SubmissionState = SubmissionState.NOT_SUBMITTED,
    ) -> TaskProviderError:
        return TaskProviderError(
            code,
            category=category,
            retryable=False,
            submission_state=state,
        )

    @staticmethod
    def _unknown_error(
        code: str,
        category: ProviderErrorCategory,
        *,
        provider_request_id: str | None = None,
        provider_task_id: str | None = None,
    ) -> TaskProviderError:
        return TaskProviderError(
            code,
            category=category,
            retryable=False,
            submission_state=SubmissionState.UNKNOWN,
            provider_request_id=provider_request_id,
            provider_task_id=provider_task_id,
        )

    @staticmethod
    def _confirmed_error(
        code: str,
        category: ProviderErrorCategory,
        provider_request_id: UUID,
        provider_task_id: str,
    ) -> TaskProviderError:
        return TaskProviderError(
            code,
            category=category,
            retryable=False,
            submission_state=SubmissionState.CONFIRMED,
            provider_request_id=str(provider_request_id),
            provider_task_id=provider_task_id,
        )

    @staticmethod
    def _poll_timeout(provider_request_id: UUID, provider_task_id: str) -> TaskProviderError:
        return WanVideoAdapter._unknown_error(
            "WAN_VIDEO_POLL_TIMEOUT",
            ProviderErrorCategory.TIMEOUT,
            provider_request_id=str(provider_request_id),
            provider_task_id=provider_task_id,
        )

    @staticmethod
    def _continuation_error(continuation: TaskProviderContinuation) -> TaskProviderError:
        return WanVideoAdapter._unknown_error(
            "WAN_VIDEO_CONTINUATION_INVALID",
            ProviderErrorCategory.INVALID_RESPONSE,
            provider_request_id=str(continuation.provider_request_id),
            provider_task_id=continuation.provider_task_id,
        )
