"""Explicit, one-shot remote Gate for the Qwen Image Provider."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import struct
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import NoReturn
from urllib.parse import urlsplit
from uuid import uuid4

import httpx

from app.common.enums import TaskType
from app.common.settings import AppSettings, ProviderSettings
from app.creative.models import CreativeReferenceAsset, ProductCreativeBrief
from app.harness.runtime import record_provider_call
from app.providers.models import TaskProviderError, TaskProviderRequest
from app.providers.qwen_image import QwenImageAdapter
from app.providers.router import RoutedTaskProvider

_GATE_MODEL = "qwen-image-3.0"
_GATE_PROMPT = "白色背景上的简约陶瓷马克杯电商产品图，柔和棚拍光线，无文字，无商标。"
_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
_MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024
_GATE_REFERENCE_ID = "1900012345678901001"


class QwenImageGateError(RuntimeError):
    """Sanitized Gate failure which never contains Provider response data."""


@dataclass(frozen=True, slots=True)
class QwenImageGateReport:
    provider: str
    model: str
    provider_request_id: str
    provider_task_id: str
    submissions: int
    polls: int
    downloads: int
    final_status: str
    image_count: int
    image_format: str
    width: int
    height: int
    size_bytes: int
    sha256: str
    generation_latency_ms: int
    temporary_file_deleted: bool

    def to_public_dict(self) -> dict[str, object]:
        """Return only redacted identifiers and non-sensitive Gate measurements."""
        payload = asdict(self)
        payload["provider_request_id"] = _redact_identifier(self.provider_request_id)
        payload["provider_task_id"] = _redact_identifier(self.provider_task_id)
        return payload


@dataclass(frozen=True, slots=True)
class _DownloadedImage:
    width: int
    height: int
    size_bytes: int
    sha256: str


class _ProviderCallCounter:
    def __init__(self, provider_host: str) -> None:
        self._provider_host = provider_host
        self.submissions = 0
        self.polls = 0
        self.downloads = 0

    async def __call__(self, request: httpx.Request) -> None:
        if request.method == "POST" and request.url.host == self._provider_host:
            self.submissions += 1
        elif (
            request.method == "GET"
            and request.url.host == self._provider_host
            and request.url.path.startswith("/api/v1/tasks/")
        ):
            self.polls += 1
        elif request.method == "GET":
            self.downloads += 1


def _validated_gate_settings(settings: ProviderSettings) -> None:
    if not settings.enabled:
        raise QwenImageGateError("QWEN_IMAGE_GATE_PROVIDER_DISABLED")
    if settings.credential is None:
        raise QwenImageGateError("QWEN_IMAGE_GATE_CREDENTIAL_NOT_CONFIGURED")
    if settings.model != _GATE_MODEL:
        raise QwenImageGateError("QWEN_IMAGE_GATE_MODEL_NOT_QWEN_IMAGE_3_0")
    if settings.endpoint is None:
        raise QwenImageGateError("QWEN_IMAGE_GATE_ENDPOINT_NOT_CONFIGURED")


def _validated_gate_runtime(settings: AppSettings) -> None:
    if settings.environment != "test":
        raise QwenImageGateError("QWEN_IMAGE_GATE_TEST_ENVIRONMENT_REQUIRED")
    if settings.model_gateway != "DEEPSEEK":
        raise QwenImageGateError("QWEN_IMAGE_GATE_DEEPSEEK_CONFIGURATION_REQUIRED")
    if settings.worker_provider != "MOCK":
        raise QwenImageGateError("QWEN_IMAGE_GATE_WORKER_MUST_REMAIN_MOCK")


async def execute_gate(
    settings: ProviderSettings,
    *,
    reference_image_url: str,
    transport: httpx.AsyncBaseTransport | None = None,
) -> QwenImageGateReport:
    """Run exactly one generation and verify its temporary PNG artifact."""
    _validated_gate_settings(settings)
    endpoint = settings.endpoint
    assert endpoint is not None
    provider_host = urlsplit(endpoint).hostname
    if provider_host is None:
        raise QwenImageGateError("QWEN_IMAGE_GATE_ENDPOINT_INVALID")
    call_counter = _ProviderCallCounter(provider_host)
    timeout = httpx.Timeout(settings.timeout_seconds)
    async with httpx.AsyncClient(
        timeout=timeout,
        trust_env=settings.trust_env,
        transport=transport,
        event_hooks={"request": [call_counter]},
    ) as client:
        adapter = QwenImageAdapter(settings, client=client)
        router = RoutedTaskProvider({TaskType.IMAGE: adapter})
        request = TaskProviderRequest(
            request_id=uuid4(),
            task_id="qwen-image-provider-gate",
            task_type=TaskType.IMAGE,
            execution_no=1,
            parameters={
                "prompt": _GATE_PROMPT,
                "count": 1,
                "resolution": "1K",
                "aspectRatio": "1:1",
            },
            creative_brief=ProductCreativeBrief.model_validate(
                {
                    "sourceMode": "CURRENT_ATTACHMENTS",
                    "productId": None,
                    "referenceAssetIds": [_GATE_REFERENCE_ID],
                    "productFacts": None,
                    "creativeDirection": {"requestText": _GATE_PROMPT},
                    "prohibitedClaims": [],
                    "provenance": {"conversationId": None, "messageId": None},
                }
            ),
            reference_assets=(
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
            ),
        )
        try:
            result = await router.execute(request)
            if call_counter.submissions != 1:
                raise QwenImageGateError("QWEN_IMAGE_GATE_SUBMISSION_COUNT_MISMATCH")
            if result.provider != "QWEN_IMAGE" or result.model != _GATE_MODEL:
                raise QwenImageGateError("QWEN_IMAGE_GATE_PROVIDER_IDENTITY_MISMATCH")
            if len(result.artifacts) != 1 or result.usage.images != 1:
                raise QwenImageGateError("QWEN_IMAGE_GATE_IMAGE_COUNT_MISMATCH")
            provider_task_id = result.provider_task_id
            if not provider_task_id:
                raise QwenImageGateError("QWEN_IMAGE_GATE_TASK_ID_MISSING")
            if call_counter.polls < 1:
                raise QwenImageGateError("QWEN_IMAGE_GATE_POLL_MISSING")
            artifact = result.artifacts[0]
            provider_request_id = artifact.metadata.get("providerRequestId")
            if not isinstance(provider_request_id, str) or not provider_request_id:
                raise QwenImageGateError("QWEN_IMAGE_GATE_REQUEST_ID_MISSING")
            artifact_url = artifact.provider_url
            if artifact_url is None or not _is_safe_artifact_url(artifact_url):
                raise QwenImageGateError("QWEN_IMAGE_GATE_ARTIFACT_URL_INVALID")

            temporary_directory: Path
            with TemporaryDirectory(prefix="zhixiaohe-qwen-image-gate-") as directory:
                temporary_directory = Path(directory)
                image_path = temporary_directory / "qwen-image-gate.png"
                downloaded = await _download_png(client, artifact_url, image_path)
                if call_counter.downloads != 1:
                    raise QwenImageGateError("QWEN_IMAGE_GATE_DOWNLOAD_COUNT_MISMATCH")

            return QwenImageGateReport(
                provider=result.provider,
                model=result.model,
                provider_request_id=provider_request_id,
                provider_task_id=provider_task_id,
                submissions=call_counter.submissions,
                polls=call_counter.polls,
                downloads=call_counter.downloads,
                final_status="SUCCEEDED",
                image_count=len(result.artifacts),
                image_format="PNG",
                width=downloaded.width,
                height=downloaded.height,
                size_bytes=downloaded.size_bytes,
                sha256=downloaded.sha256,
                generation_latency_ms=result.latency_ms,
                temporary_file_deleted=not temporary_directory.exists(),
            )
        finally:
            await router.aclose()


async def _download_png(
    client: httpx.AsyncClient,
    artifact_url: str,
    destination: Path,
) -> _DownloadedImage:
    digest = hashlib.sha256()
    size_bytes = 0
    try:
        record_provider_call()
        async with client.stream("GET", artifact_url, follow_redirects=False) as response:
            if urlsplit(str(response.url)).scheme != "https":
                raise QwenImageGateError("QWEN_IMAGE_GATE_DOWNLOAD_SCHEME_INVALID")
            if not response.is_success:
                raise QwenImageGateError("QWEN_IMAGE_GATE_DOWNLOAD_HTTP_FAILED")
            media_type = response.headers.get("Content-Type", "").partition(";")[0].lower()
            if media_type != "image/png":
                raise QwenImageGateError("QWEN_IMAGE_GATE_CONTENT_TYPE_INVALID")
            with destination.open("xb") as output:
                async for chunk in response.aiter_bytes():
                    size_bytes += len(chunk)
                    if size_bytes > _MAX_DOWNLOAD_BYTES:
                        raise QwenImageGateError("QWEN_IMAGE_GATE_IMAGE_TOO_LARGE")
                    digest.update(chunk)
                    output.write(chunk)
    except httpx.RequestError:
        raise QwenImageGateError("QWEN_IMAGE_GATE_DOWNLOAD_FAILED") from None

    if size_bytes == 0:
        raise QwenImageGateError("QWEN_IMAGE_GATE_IMAGE_EMPTY")
    width, height = _read_png_dimensions(destination)
    if (width, height) != (1_024, 1_024):
        raise QwenImageGateError("QWEN_IMAGE_GATE_DIMENSIONS_MISMATCH")
    return _DownloadedImage(
        width=width,
        height=height,
        size_bytes=size_bytes,
        sha256=digest.hexdigest(),
    )


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


def _read_png_dimensions(path: Path) -> tuple[int, int]:
    with path.open("rb") as image:
        header = image.read(24)
    if (
        len(header) != 24
        or header[:8] != _PNG_SIGNATURE
        or header[8:12] != struct.pack(">I", 13)
        or header[12:16] != b"IHDR"
    ):
        raise QwenImageGateError("QWEN_IMAGE_GATE_PNG_INVALID")
    width, height = struct.unpack(">II", header[16:24])
    if width < 1 or height < 1:
        raise QwenImageGateError("QWEN_IMAGE_GATE_PNG_DIMENSIONS_INVALID")
    return width, height


def _redact_identifier(value: str) -> str:
    if len(value) <= 8:
        return "***"
    return f"{value[:4]}...{value[-4:]}"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run one paid Qwen Image Provider Gate")
    parser.add_argument(
        "--execute",
        action="store_true",
        help="explicitly authorize one remote image generation",
    )
    parser.add_argument(
        "--reference-image-url",
        help="short-lived HTTPS URL for the reference product image",
    )
    return parser


def _fail(code: str) -> NoReturn:
    print(json.dumps({"status": "FAILED", "code": code}, sort_keys=True))
    raise SystemExit(1)


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if not args.execute:
        print(
            json.dumps(
                {
                    "status": "NOT_EXECUTED",
                    "code": "QWEN_IMAGE_GATE_REQUIRES_EXPLICIT_EXECUTE",
                },
                sort_keys=True,
            )
        )
        return 2
    if not args.reference_image_url:
        _fail("QWEN_IMAGE_GATE_REFERENCE_IMAGE_REQUIRED")
    try:
        app_settings = AppSettings()
        _validated_gate_runtime(app_settings)
        report = asyncio.run(
            execute_gate(
                app_settings.qwen_image,
                reference_image_url=args.reference_image_url,
            )
        )
    except (QwenImageGateError, TaskProviderError) as exc:
        _fail(exc.code if isinstance(exc, TaskProviderError) else str(exc))
    except Exception:
        _fail("QWEN_IMAGE_GATE_UNEXPECTED_FAILURE")
    print(json.dumps(report.to_public_dict(), ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
