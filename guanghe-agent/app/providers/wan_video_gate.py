"""Explicit, durable, one-shot remote Gate for the Wan Video Provider."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import math
import os
import stat
from collections.abc import Mapping, Sequence
from contextlib import suppress
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Literal, NoReturn, Protocol
from urllib.parse import urlsplit
from uuid import UUID, uuid4

import httpx
from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    model_validator,
)
from pydantic.alias_generators import to_camel

from app.common.enums import TaskType
from app.common.settings import AppSettings, ProviderSettings
from app.creative.models import CreativeReferenceAsset, ProductCreativeBrief
from app.harness.runtime import record_provider_call
from app.providers.models import (
    SubmissionState,
    TaskProviderError,
    TaskProviderRequest,
    TaskProviderResult,
    WanVideoProviderContinuation,
)
from app.providers.router import RoutedTaskProvider
from app.providers.wan_video import WanVideoAdapter

_GATE_MODEL = "wan3.0-video"
_GATE_PROMPT = "白色背景上，一只无文字无商标的简约陶瓷马克杯缓慢旋转，柔和棚拍光线。"
_GATE_DURATION_SECONDS = 2
_GATE_RESOLUTION = "480P"
_GATE_RATIO = "16:9"
_GATE_REGION = "cn-beijing"
_MAX_DOWNLOAD_BYTES = 250 * 1024 * 1024
_MAX_STATE_BYTES = 4_096
_PROBE_TASK_ID = "00000000-0000-0000-0000-000000000000"
_TASK_ID_PATTERN = r"^[A-Za-z0-9_-]{1,256}$"
_GATE_REFERENCE_ID = "1900012345678901001"


class WanVideoGateError(RuntimeError):
    """Sanitized Gate failure which never contains Provider response data."""


class _ProviderCallCounts(Protocol):
    probe_queries: int
    submissions: int
    polls: int
    downloads: int


class WanVideoGateAttemptError(WanVideoGateError):
    """Provider failure plus non-sensitive HTTP call counts for one Gate process."""

    def __init__(
        self,
        provider_error: TaskProviderError,
        *,
        counter: _ProviderCallCounts,
        recovery_attempts: int,
    ) -> None:
        super().__init__(provider_error.code)
        self.provider_error = provider_error
        self.probe_queries = counter.probe_queries
        self.submissions = counter.submissions
        self.polls = counter.polls
        self.downloads = counter.downloads
        self.recovery_attempts = recovery_attempts


class WanVideoGateState(BaseModel):
    """Minimal durable continuation identity; never contains content or credentials."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        strict=True,
    )

    schema_version: Literal[1] = 1
    provider: Literal["WAN_VIDEO"] = "WAN_VIDEO"
    model: Literal["wan3.0-video"] = "wan3.0-video"
    provider_request_id: UUID
    provider_task_id: str = Field(pattern=_TASK_ID_PATTERN)
    submission_state: Literal[SubmissionState.UNKNOWN] = SubmissionState.UNKNOWN
    created_at: AwareDatetime
    updated_at: AwareDatetime
    region: Literal["cn-beijing"] = _GATE_REGION
    endpoint_host: str = Field(min_length=1, max_length=253)

    @model_validator(mode="after")
    def validate_state_relationships(self) -> WanVideoGateState:
        if self.updated_at < self.created_at:
            raise ValueError("updatedAt cannot precede createdAt")
        if not _is_approved_beijing_host(self.endpoint_host):
            raise ValueError("endpointHost is not an approved Beijing host")
        return self


@dataclass(frozen=True, slots=True)
class WanVideoGateReport:
    provider: str
    model: str
    provider_request_id: str
    provider_task_id: str
    probe_queries: int
    submissions: int
    polls: int
    recovery_attempts: int
    downloads: int
    final_status: str
    video_count: int
    container_format: str
    codec: str
    width: int
    height: int
    duration_seconds: float
    reported_duration_seconds: int
    size_bytes: int
    sha256: str
    generation_latency_ms: int
    provider_cost: str | None
    currency: str | None
    state_file_deleted: bool
    temporary_file_deleted: bool

    def to_public_dict(self) -> dict[str, object]:
        payload = asdict(self)
        payload["provider_request_id"] = _redact_identifier(self.provider_request_id)
        payload["provider_task_id"] = _redact_identifier(self.provider_task_id)
        return payload


@dataclass(frozen=True, slots=True)
class _DownloadedVideo:
    container_format: str
    codec: str
    width: int
    height: int
    duration_seconds: float
    size_bytes: int
    sha256: str


class _FfprobeModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class _FfprobeStream(_FfprobeModel):
    codec_type: str
    codec_name: str
    width: int = Field(gt=0)
    height: int = Field(gt=0)


class _FfprobeFormat(_FfprobeModel):
    format_name: str
    duration: str
    size: str


class _FfprobeOutput(_FfprobeModel):
    streams: tuple[_FfprobeStream, ...] = Field(min_length=1)
    format: _FfprobeFormat


class _ProviderCallCounter:
    def __init__(self, provider_host: str) -> None:
        self._provider_host = provider_host
        self.probe_queries = 0
        self.submissions = 0
        self.polls = 0
        self.downloads = 0

    async def __call__(self, request: httpx.Request) -> None:
        if request.method == "POST" and request.url.host == self._provider_host:
            self.submissions += 1
        elif request.method == "GET" and request.url.host == self._provider_host:
            if request.url.path.endswith(f"/{_PROBE_TASK_ID}"):
                self.probe_queries += 1
            elif request.url.path.startswith("/api/v1/tasks/"):
                self.polls += 1
        elif request.method == "GET":
            self.downloads += 1


class _RecoveryStateStore:
    def __init__(self, path: Path, *, endpoint_host: str) -> None:
        if not path.is_absolute():
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_PATH_NOT_ABSOLUTE")
        self.path = path
        self._endpoint_host = endpoint_host.casefold()

    def require_new_destination(self) -> None:
        if not self.path.parent.is_dir():
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_PARENT_INVALID")
        if self.path.exists() or self.path.is_symlink():
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_ALREADY_EXISTS")

    def save_submission(self, provider_request_id: UUID, provider_task_id: str) -> None:
        if self.path.exists() or self.path.is_symlink():
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_ALREADY_EXISTS")
        now = datetime.now(UTC)
        state = WanVideoGateState(
            provider_request_id=provider_request_id,
            provider_task_id=provider_task_id,
            created_at=now,
            updated_at=now,
            endpoint_host=self._endpoint_host,
        )
        self._atomic_write(state)

    def load(self) -> WanVideoGateState:
        if self.path.is_symlink() or not self.path.is_file():
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_FILE_INVALID")
        file_stat = self.path.stat()
        if file_stat.st_size < 1 or file_stat.st_size > _MAX_STATE_BYTES:
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_FILE_INVALID")
        if os.name == "posix" and stat.S_IMODE(file_stat.st_mode) != 0o600:
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_PERMISSIONS_INVALID")
        try:
            state = WanVideoGateState.model_validate_json(self.path.read_bytes())
        except (OSError, ValidationError):
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_FILE_INVALID") from None
        if state.endpoint_host.casefold() != self._endpoint_host:
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_ENDPOINT_MISMATCH")
        return state

    def preserve_unknown(self, error: TaskProviderError) -> None:
        state = self.load()
        if (
            error.provider_request_id != str(state.provider_request_id)
            or error.provider_task_id != state.provider_task_id
        ):
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_IDENTITY_MISMATCH")
        updated_at = datetime.now(UTC)
        if updated_at <= state.updated_at:
            updated_at = state.updated_at + timedelta(microseconds=1)
        self._atomic_write(state.model_copy(update={"updated_at": updated_at}))

    def delete_terminal(self) -> None:
        try:
            self.path.unlink()
        except FileNotFoundError:
            return
        except OSError:
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_DELETE_FAILED") from None

    def _atomic_write(self, state: WanVideoGateState) -> None:
        data = state.model_dump_json(by_alias=True).encode("utf-8")
        if len(data) > _MAX_STATE_BYTES:
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_FILE_INVALID")
        temporary = self.path.with_name(f".{self.path.name}.{uuid4().hex}.tmp")
        descriptor = -1
        try:
            descriptor = os.open(
                temporary,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                0o600,
            )
            view = memoryview(data)
            while view:
                written = os.write(descriptor, view)
                if written <= 0:
                    raise OSError("state write made no progress")
                view = view[written:]
            os.fsync(descriptor)
            os.close(descriptor)
            descriptor = -1
            os.replace(temporary, self.path)
            os.chmod(self.path, 0o600)
            self._fsync_parent()
        except OSError:
            raise WanVideoGateError("WAN_VIDEO_GATE_STATE_WRITE_FAILED") from None
        finally:
            if descriptor >= 0:
                os.close(descriptor)
            with suppress(FileNotFoundError):
                temporary.unlink()

    def _fsync_parent(self) -> None:
        if os.name != "posix":
            return
        descriptor = os.open(self.path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def _validated_gate_settings(settings: ProviderSettings) -> str:
    if not settings.enabled:
        raise WanVideoGateError("WAN_VIDEO_GATE_PROVIDER_DISABLED")
    if settings.credential is None:
        raise WanVideoGateError("WAN_VIDEO_GATE_CREDENTIAL_NOT_CONFIGURED")
    if settings.model != _GATE_MODEL:
        raise WanVideoGateError("WAN_VIDEO_GATE_MODEL_NOT_WAN_3_0_VIDEO")
    if settings.endpoint is None:
        raise WanVideoGateError("WAN_VIDEO_GATE_ENDPOINT_NOT_CONFIGURED")
    endpoint_host = urlsplit(settings.endpoint).hostname
    if endpoint_host is None or not _is_approved_beijing_host(endpoint_host):
        raise WanVideoGateError("WAN_VIDEO_GATE_ENDPOINT_INVALID")
    return endpoint_host.casefold()


def _validated_gate_runtime(settings: AppSettings) -> None:
    if settings.environment != "test":
        raise WanVideoGateError("WAN_VIDEO_GATE_TEST_ENVIRONMENT_REQUIRED")


def _gate_request(
    *,
    reference_image_url: str | None = None,
    provider_request_id: UUID | None = None,
) -> TaskProviderRequest:
    creative_brief = None
    reference_assets: tuple[CreativeReferenceAsset, ...] = ()
    if reference_image_url is not None:
        creative_brief = ProductCreativeBrief.model_validate(
            {
                "sourceMode": "CURRENT_ATTACHMENTS",
                "productId": None,
                "referenceAssetIds": [_GATE_REFERENCE_ID],
                "productFacts": None,
                "creativeDirection": {"requestText": _GATE_PROMPT},
                "prohibitedClaims": [],
                "provenance": {"conversationId": None, "messageId": None},
            }
        )
        reference_assets = (
            CreativeReferenceAsset(
                asset_id=_GATE_REFERENCE_ID,
                content_type="image/png",
                size_bytes=1,
                sha256="0" * 64,
                width=1_024,
                height=1_024,
                read_url=reference_image_url,
                expires_at=datetime.now(UTC) + timedelta(hours=1),
            ),
        )
    return TaskProviderRequest(
        request_id=provider_request_id or uuid4(),
        task_id="wan-video-provider-gate",
        task_type=TaskType.VIDEO,
        execution_no=1,
        parameters={
            "scene": _GATE_PROMPT,
            "durationSeconds": _GATE_DURATION_SECONDS,
            "resolution": _GATE_RESOLUTION,
            "aspectRatio": _GATE_RATIO,
            "audio": False,
            "seed": -1,
            "promptExtend": True,
            "watermark": False,
        },
        creative_brief=creative_brief,
        reference_assets=reference_assets,
    )


async def execute_gate(
    settings: ProviderSettings,
    *,
    state_file: Path,
    reference_image_url: str,
    transport: httpx.AsyncBaseTransport | None = None,
    ffprobe_path: str = "ffprobe",
) -> WanVideoGateReport:
    """Probe, persist one submission, poll, inspect one MP4, then delete state."""
    endpoint_host = _validated_gate_settings(settings)
    state_store = _RecoveryStateStore(state_file, endpoint_host=endpoint_host)
    state_store.require_new_destination()
    counter = _ProviderCallCounter(endpoint_host)
    async with _gate_client(settings, transport=transport, counter=counter) as client:
        adapter = WanVideoAdapter(
            settings,
            client=client,
            submission_observer=state_store.save_submission,
        )
        router = RoutedTaskProvider({TaskType.VIDEO: adapter})
        try:
            if not await router.probe():
                raise WanVideoGateError("WAN_VIDEO_GATE_PROBE_FAILED")
            try:
                result = await router.execute(
                    _gate_request(reference_image_url=reference_image_url)
                )
            except TaskProviderError as error:
                _handle_provider_error(state_store, error)
                raise WanVideoGateAttemptError(
                    error,
                    counter=counter,
                    recovery_attempts=0,
                ) from error
            if counter.submissions != 1:
                raise WanVideoGateError("WAN_VIDEO_GATE_SUBMISSION_COUNT_MISMATCH")
            state_store.delete_terminal()
            return await _validate_success(
                result,
                client=client,
                counter=counter,
                recovery_attempts=0,
                ffprobe_path=ffprobe_path,
                state_store=state_store,
            )
        finally:
            await router.aclose()


async def resume_gate(
    settings: ProviderSettings,
    *,
    resume_from: Path,
    transport: httpx.AsyncBaseTransport | None = None,
    ffprobe_path: str = "ffprobe",
) -> WanVideoGateReport:
    """Load strict state and perform GET-only recovery of its exact Provider task."""
    endpoint_host = _validated_gate_settings(settings)
    state_store = _RecoveryStateStore(resume_from, endpoint_host=endpoint_host)
    state = state_store.load()
    if state.provider != "WAN_VIDEO" or state.model != settings.model:
        raise WanVideoGateError("WAN_VIDEO_GATE_STATE_PROVIDER_MODEL_MISMATCH")
    counter = _ProviderCallCounter(endpoint_host)
    async with _gate_client(settings, transport=transport, counter=counter) as client:
        adapter = WanVideoAdapter(settings, client=client)
        router = RoutedTaskProvider({TaskType.VIDEO: adapter})
        continuation = WanVideoProviderContinuation(
            provider=state.provider,
            model=state.model,
            provider_request_id=state.provider_request_id,
            provider_task_id=state.provider_task_id,
            submission_state=state.submission_state,
        )
        try:
            try:
                result = await router.resume(
                    _gate_request(provider_request_id=state.provider_request_id),
                    continuation,
                )
            except TaskProviderError as error:
                _handle_provider_error(state_store, error)
                raise WanVideoGateAttemptError(
                    error,
                    counter=counter,
                    recovery_attempts=1,
                ) from error
            if counter.submissions != 0:
                raise WanVideoGateError("WAN_VIDEO_GATE_RESUME_SUBMITTED")
            state_store.delete_terminal()
            return await _validate_success(
                result,
                client=client,
                counter=counter,
                recovery_attempts=1,
                ffprobe_path=ffprobe_path,
                state_store=state_store,
            )
        finally:
            await router.aclose()


def _gate_client(
    settings: ProviderSettings,
    *,
    transport: httpx.AsyncBaseTransport | None,
    counter: _ProviderCallCounter,
) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        timeout=httpx.Timeout(settings.timeout_seconds),
        trust_env=settings.trust_env,
        transport=transport,
        event_hooks={"request": [counter]},
    )


def _handle_provider_error(
    state_store: _RecoveryStateStore,
    error: TaskProviderError,
) -> None:
    if error.submission_state is SubmissionState.UNKNOWN and state_store.path.exists():
        state_store.preserve_unknown(error)
    elif error.submission_state is SubmissionState.CONFIRMED:
        state_store.delete_terminal()


async def _validate_success(
    result: TaskProviderResult,
    *,
    client: httpx.AsyncClient,
    counter: _ProviderCallCounter,
    recovery_attempts: int,
    ffprobe_path: str,
    state_store: _RecoveryStateStore,
) -> WanVideoGateReport:
    expected_submissions = 0 if recovery_attempts else 1
    if counter.submissions != expected_submissions:
        raise WanVideoGateError("WAN_VIDEO_GATE_SUBMISSION_COUNT_MISMATCH")
    if result.provider != "WAN_VIDEO" or result.model != _GATE_MODEL:
        raise WanVideoGateError("WAN_VIDEO_GATE_PROVIDER_IDENTITY_MISMATCH")
    if len(result.artifacts) != 1 or result.usage.video_seconds != 2:
        raise WanVideoGateError("WAN_VIDEO_GATE_VIDEO_COUNT_OR_DURATION_MISMATCH")
    provider_task_id = result.provider_task_id
    if not provider_task_id:
        raise WanVideoGateError("WAN_VIDEO_GATE_TASK_ID_MISSING")
    if counter.polls < 1:
        raise WanVideoGateError("WAN_VIDEO_GATE_POLL_MISSING")
    artifact = result.artifacts[0]
    provider_request_id = artifact.metadata.get("providerRequestId")
    if not isinstance(provider_request_id, str) or not provider_request_id:
        raise WanVideoGateError("WAN_VIDEO_GATE_REQUEST_ID_MISSING")
    artifact_url = artifact.provider_url
    if artifact_url is None or not _is_safe_artifact_url(artifact_url):
        raise WanVideoGateError("WAN_VIDEO_GATE_ARTIFACT_URL_INVALID")

    temporary_directory: Path
    with TemporaryDirectory(prefix="zhixiaohe-wan-video-gate-") as directory:
        temporary_directory = Path(directory)
        video_path = temporary_directory / "wan-video-gate.mp4"
        downloaded = await _download_mp4(
            client,
            artifact_url,
            video_path,
            ffprobe_path=ffprobe_path,
        )
        if counter.downloads != 1:
            raise WanVideoGateError("WAN_VIDEO_GATE_DOWNLOAD_COUNT_MISMATCH")
        _validate_media_against_provider(downloaded, artifact.metadata)

    return WanVideoGateReport(
        provider=result.provider,
        model=result.model,
        provider_request_id=provider_request_id,
        provider_task_id=provider_task_id,
        probe_queries=counter.probe_queries,
        submissions=counter.submissions,
        polls=counter.polls,
        recovery_attempts=recovery_attempts,
        downloads=counter.downloads,
        final_status="SUCCEEDED",
        video_count=len(result.artifacts),
        container_format=downloaded.container_format,
        codec=downloaded.codec,
        width=downloaded.width,
        height=downloaded.height,
        duration_seconds=downloaded.duration_seconds,
        reported_duration_seconds=result.usage.video_seconds,
        size_bytes=downloaded.size_bytes,
        sha256=downloaded.sha256,
        generation_latency_ms=result.latency_ms,
        provider_cost=result.usage.provider_cost,
        currency=result.usage.currency,
        state_file_deleted=not state_store.path.exists(),
        temporary_file_deleted=not temporary_directory.exists(),
    )


async def _download_mp4(
    client: httpx.AsyncClient,
    artifact_url: str,
    destination: Path,
    *,
    ffprobe_path: str,
) -> _DownloadedVideo:
    digest = hashlib.sha256()
    size_bytes = 0
    first_bytes = bytearray()
    try:
        record_provider_call()
        async with client.stream("GET", artifact_url, follow_redirects=False) as response:
            if urlsplit(str(response.url)).scheme != "https":
                raise WanVideoGateError("WAN_VIDEO_GATE_DOWNLOAD_SCHEME_INVALID")
            if not response.is_success:
                raise WanVideoGateError("WAN_VIDEO_GATE_DOWNLOAD_HTTP_FAILED")
            media_type = response.headers.get("Content-Type", "").partition(";")[0].lower()
            if media_type != "video/mp4":
                raise WanVideoGateError("WAN_VIDEO_GATE_CONTENT_TYPE_INVALID")
            with destination.open("xb") as output:
                async for chunk in response.aiter_bytes():
                    if len(first_bytes) < 12:
                        first_bytes.extend(chunk[: 12 - len(first_bytes)])
                    size_bytes += len(chunk)
                    if size_bytes > _MAX_DOWNLOAD_BYTES:
                        raise WanVideoGateError("WAN_VIDEO_GATE_VIDEO_TOO_LARGE")
                    digest.update(chunk)
                    output.write(chunk)
    except httpx.RequestError:
        raise WanVideoGateError("WAN_VIDEO_GATE_DOWNLOAD_FAILED") from None

    if size_bytes == 0:
        raise WanVideoGateError("WAN_VIDEO_GATE_VIDEO_EMPTY")
    if len(first_bytes) < 12 or bytes(first_bytes[4:8]) != b"ftyp":
        raise WanVideoGateError("WAN_VIDEO_GATE_MP4_SIGNATURE_INVALID")
    media = await _run_ffprobe(destination, ffprobe_path=ffprobe_path)
    if media.size_bytes != size_bytes:
        raise WanVideoGateError("WAN_VIDEO_GATE_FFPROBE_SIZE_MISMATCH")
    return _DownloadedVideo(
        container_format=media.container_format,
        codec=media.codec,
        width=media.width,
        height=media.height,
        duration_seconds=media.duration_seconds,
        size_bytes=size_bytes,
        sha256=digest.hexdigest(),
    )


async def _run_ffprobe(path: Path, *, ffprobe_path: str) -> _DownloadedVideo:
    try:
        process = await asyncio.create_subprocess_exec(
            ffprobe_path,
            "-v",
            "error",
            "-show_entries",
            "stream=codec_type,codec_name,width,height",
            "-show_entries",
            "format=format_name,duration,size",
            "-of",
            "json",
            str(path),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except OSError:
        raise WanVideoGateError("WAN_VIDEO_GATE_FFPROBE_NOT_AVAILABLE") from None
    stdout, _stderr = await process.communicate()
    if process.returncode != 0:
        raise WanVideoGateError("WAN_VIDEO_GATE_FFPROBE_FAILED")
    try:
        parsed = _FfprobeOutput.model_validate_json(stdout)
        stream = next(item for item in parsed.streams if item.codec_type == "video")
        duration = float(parsed.format.duration)
        size_bytes = int(parsed.format.size)
    except (ValidationError, StopIteration, ValueError, OverflowError):
        raise WanVideoGateError("WAN_VIDEO_GATE_FFPROBE_RESPONSE_INVALID") from None
    if not math.isfinite(duration) or duration <= 0:
        raise WanVideoGateError("WAN_VIDEO_GATE_FFPROBE_DURATION_INVALID")
    if not any(name in parsed.format.format_name.split(",") for name in ("mp4", "mov")):
        raise WanVideoGateError("WAN_VIDEO_GATE_FFPROBE_FORMAT_INVALID")
    return _DownloadedVideo(
        container_format="MP4",
        codec=stream.codec_name,
        width=stream.width,
        height=stream.height,
        duration_seconds=duration,
        size_bytes=size_bytes,
        sha256="",
    )


def _validate_media_against_provider(
    media: _DownloadedVideo,
    metadata: Mapping[str, object],
) -> None:
    if abs(media.duration_seconds - _GATE_DURATION_SECONDS) > 0.75:
        raise WanVideoGateError("WAN_VIDEO_GATE_DURATION_MISMATCH")
    if min(media.width, media.height) != 480:
        raise WanVideoGateError("WAN_VIDEO_GATE_RESOLUTION_MISMATCH")
    if metadata.get("resolution") != _GATE_RESOLUTION:
        raise WanVideoGateError("WAN_VIDEO_GATE_PROVIDER_RESOLUTION_MISMATCH")
    if metadata.get("aspectRatio") != _GATE_RATIO:
        raise WanVideoGateError("WAN_VIDEO_GATE_PROVIDER_RATIO_MISMATCH")


def _is_safe_artifact_url(value: str) -> bool:
    parsed = urlsplit(value)
    hostname = parsed.hostname
    try:
        port = parsed.port
    except ValueError:
        return False
    return (
        parsed.scheme == "https"
        and hostname is not None
        and hostname.endswith(".aliyuncs.com")
        and parsed.username is None
        and parsed.password is None
        and port in {None, 443}
        and not parsed.fragment
    )


def _is_approved_beijing_host(value: str) -> bool:
    host = value.casefold()
    return host == "dashscope.aliyuncs.com" or host.endswith(".cn-beijing.maas.aliyuncs.com")


def _redact_identifier(value: str) -> str:
    if len(value) <= 8:
        return "***"
    return f"{value[:4]}...{value[-4:]}"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run or resume one Wan Video Provider Gate")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--execute",
        action="store_true",
        help="explicitly authorize one remote video generation",
    )
    mode.add_argument(
        "--resume-from",
        type=Path,
        help="absolute path to an existing 0600 recovery-state file",
    )
    parser.add_argument(
        "--state-file",
        type=Path,
        help="absolute path that receives state immediately after a new submit",
    )
    parser.add_argument(
        "--reference-image-url",
        help="short-lived HTTPS URL for the reference product image",
    )
    parser.add_argument(
        "--ffprobe",
        default="ffprobe",
        help="ffprobe executable used to inspect the temporary MP4",
    )
    return parser


def _fail(
    code: str,
    *,
    provider_error: TaskProviderError | None = None,
    attempt_error: WanVideoGateAttemptError | None = None,
) -> NoReturn:
    payload: dict[str, object] = {"status": "FAILED", "code": code}
    if provider_error is not None:
        payload["submissionState"] = provider_error.submission_state.value
        if provider_error.provider_request_id is not None:
            payload["providerRequestId"] = _redact_identifier(provider_error.provider_request_id)
        if provider_error.provider_task_id is not None:
            payload["providerTaskId"] = _redact_identifier(provider_error.provider_task_id)
    if attempt_error is not None:
        payload.update(
            {
                "probeQueries": attempt_error.probe_queries,
                "submissions": attempt_error.submissions,
                "polls": attempt_error.polls,
                "downloads": attempt_error.downloads,
                "recoveryAttempts": attempt_error.recovery_attempts,
            }
        )
    print(json.dumps(payload, sort_keys=True))
    raise SystemExit(1)


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if not args.execute and args.resume_from is None:
        print(
            json.dumps(
                {
                    "status": "NOT_EXECUTED",
                    "code": "WAN_VIDEO_GATE_REQUIRES_EXPLICIT_MODE",
                },
                sort_keys=True,
            )
        )
        return 2
    if args.execute and args.state_file is None:
        _fail("WAN_VIDEO_GATE_STATE_FILE_REQUIRED")
    if args.execute and not args.reference_image_url:
        _fail("WAN_VIDEO_GATE_REFERENCE_IMAGE_REQUIRED")
    if args.resume_from is not None and args.state_file is not None:
        _fail("WAN_VIDEO_GATE_STATE_ARGUMENT_CONFLICT")
    try:
        app_settings = AppSettings()
        _validated_gate_runtime(app_settings)
        if args.resume_from is not None:
            report = asyncio.run(
                resume_gate(
                    app_settings.wan_video,
                    resume_from=args.resume_from,
                    ffprobe_path=args.ffprobe,
                )
            )
        else:
            assert args.state_file is not None
            report = asyncio.run(
                execute_gate(
                    app_settings.wan_video,
                    state_file=args.state_file,
                    reference_image_url=args.reference_image_url,
                    ffprobe_path=args.ffprobe,
                )
            )
    except WanVideoGateAttemptError as exc:
        _fail(
            exc.provider_error.code,
            provider_error=exc.provider_error,
            attempt_error=exc,
        )
    except TaskProviderError as exc:
        _fail(exc.code, provider_error=exc)
    except WanVideoGateError as exc:
        _fail(str(exc))
    except Exception:
        _fail("WAN_VIDEO_GATE_UNEXPECTED_FAILURE")
    print(json.dumps(report.to_public_dict(), ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
