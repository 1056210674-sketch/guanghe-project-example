"""Explicit single-image provider Gate; never a business task or asset-ingestion Gate."""

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit
from uuid import UUID, uuid4

import httpx
from pydantic import BaseModel, ConfigDict, ValidationError

from app.common.settings import AppSettings, ProviderSettings
from app.providers.models import (
    SubmissionState,
    TaskProviderError,
    TaskProviderRequest,
    WanImageEditProviderContinuation,
)
from app.providers.wan_image_edit import WanImageEditAdapter


class WanImageEditGateError(RuntimeError):
    """Only stable codes may cross the CLI boundary."""


class _Receipt(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    provider: Literal["WAN_IMAGE_EDIT"] = "WAN_IMAGE_EDIT"
    model: Literal["wanx2.1-imageedit"] = "wanx2.1-imageedit"
    endpoint_host: str
    request_fingerprint: str
    phase: Literal["SUBMISSION_UNCERTAIN", "QUERY_ONLY", "TERMINAL"]
    provider_request_id: UUID | None = None
    provider_task_id: str | None = None


def _write_receipt(path: Path, receipt: _Receipt, *, new: bool) -> None:
    destination = path if new else path.with_name(f".{path.name}.{uuid4().hex}.tmp")
    try:
        descriptor = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(receipt.model_dump_json())
            output.flush()
            os.fsync(output.fileno())
        if not new:
            os.replace(destination, path)
        if os.name == "posix":
            directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
    except OSError:
        raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_RECEIPT_WRITE_FAILED") from None


def _fingerprint(request: TaskProviderRequest) -> str:
    payload = request.model_dump(mode="json", by_alias=True)
    # Java can refresh short-lived URLs for a query-only resume. Identity,
    # source, mask hash, parameters and reference order may never change.
    for asset in [*payload["referenceAssets"], payload.get("controlledMaskAsset")]:
        if asset is not None:
            asset.pop("readUrl")
            asset.pop("expiresAt")
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()


def _prepare_receipt(
    settings: ProviderSettings,
    request: TaskProviderRequest,
    receipt_path: Path,
    *,
    resume: bool = False,
) -> tuple[_Receipt, TaskProviderRequest, WanImageEditProviderContinuation | None]:
    if not settings.enabled or settings.credential is None or settings.model != "wanx2.1-imageedit":
        raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_NOT_CONFIGURED")
    if (
        not receipt_path.is_absolute()
        or not receipt_path.parent.is_dir()
        or receipt_path.is_symlink()
    ):
        raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_RECEIPT_PATH_INVALID")
    repository = Path(__file__).resolve().parents[2]
    if receipt_path.resolve().is_relative_to(repository):
        raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_RECEIPT_MUST_BE_OUTSIDE_REPOSITORY")
    if request.parameters.get("count", 1) != 1:
        raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_REQUIRES_ONE_IMAGE")
    endpoint_host = urlsplit(settings.endpoint or WanImageEditAdapter.DEFAULT_ENDPOINT).hostname
    if endpoint_host is None:
        raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_ENDPOINT_INVALID")
    receipt = _Receipt(
        endpoint_host=endpoint_host,
        request_fingerprint=_fingerprint(request),
        phase="SUBMISSION_UNCERTAIN",
    )
    continuation = None
    if resume:
        try:
            if receipt_path.stat().st_size > 4096:
                raise ValueError("oversized receipt")
            stored = _Receipt.model_validate_json(receipt_path.read_bytes())
        except (OSError, ValueError):
            raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_RECEIPT_INVALID") from None
        if (
            stored.endpoint_host != endpoint_host
            or stored.request_fingerprint != receipt.request_fingerprint
        ):
            raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_IDENTITY_MISMATCH")
        if (
            stored.phase != "QUERY_ONLY"
            or stored.provider_request_id is None
            or stored.provider_task_id is None
        ):
            raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_RECONCILIATION_REQUIRED")
        continuation = WanImageEditProviderContinuation(
            provider="WAN_IMAGE_EDIT",
            model="wanx2.1-imageedit",
            provider_request_id=stored.provider_request_id,
            provider_task_id=stored.provider_task_id,
        )
        request = request.model_copy(update={"request_id": stored.provider_request_id})
        receipt = stored
    elif receipt_path.exists():
        raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_RECEIPT_ALREADY_EXISTS")
    return receipt, request, continuation


async def execute_gate(
    settings: ProviderSettings,
    request: TaskProviderRequest,
    receipt_path: Path,
    *,
    resume: bool = False,
    transport: httpx.AsyncBaseTransport | None = None,
) -> dict[str, object]:
    """One paid POST maximum per receipt; uncertain work can only be queried.

    A pre-submit marker is persisted before network I/O. A process death before
    an identity is recovered therefore blocks resubmission instead of charging
    again. Operator reconciliation is required for such an identity-less receipt.
    """
    receipt, request, continuation = await asyncio.to_thread(
        _prepare_receipt, settings, request, receipt_path, resume=resume
    )

    async with httpx.AsyncClient(
        transport=transport, timeout=settings.timeout_seconds, trust_env=settings.trust_env
    ) as client:
        adapter = WanImageEditAdapter(settings, client=client)
        adapter.validate_request(request)
        if not resume:
            await asyncio.to_thread(_write_receipt, receipt_path, receipt, new=True)
        try:
            result = (
                await adapter.execute(request)
                if continuation is None
                else await adapter.resume(request, continuation)
            )
        except TaskProviderError as error:
            identity = error.provider_request_id and error.provider_task_id
            phase = (
                "QUERY_ONLY"
                if identity and error.submission_state is SubmissionState.UNKNOWN
                else "SUBMISSION_UNCERTAIN"
            )
            if error.submission_state in {SubmissionState.CONFIRMED, SubmissionState.NOT_SUBMITTED}:
                phase = "TERMINAL"
            receipt = receipt.model_copy(
                update={
                    "phase": phase,
                    "provider_request_id": UUID(error.provider_request_id)
                    if error.provider_request_id and identity
                    else None,
                    "provider_task_id": error.provider_task_id if identity else None,
                }
            )
            await asyncio.to_thread(_write_receipt, receipt_path, receipt, new=False)
            raise
        await asyncio.to_thread(
            _write_receipt,
            receipt_path,
            receipt.model_copy(
                update={
                    "phase": "TERMINAL",
                    "provider_request_id": result.request_id,
                    "provider_task_id": result.provider_task_id,
                }
            ),
            new=False,
        )
        return {
            "provider": result.provider,
            "model": result.model,
            "status": "PROVIDER_RESULT_RECEIVED",
            "images": result.usage.images,
            "submissions": 0 if resume else 1,
            "assetIngestionVerified": False,
            "unmaskedPixelsVerified": False,
            "receiptRetained": True,
        }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--execute", action="store_true")
    action.add_argument("--resume", action="store_true")
    parser.add_argument("--request-file", type=Path)
    parser.add_argument("--receipt-file", type=Path)
    args = parser.parse_args()
    if not args.execute and not args.resume:
        print("WAN_IMAGE_EDIT_GATE_EXPLICIT_ACTION_REQUIRED")
        return 2
    try:
        settings = AppSettings()
        if settings.environment != "test" or settings.worker_provider != "MOCK":
            raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_TEST_MOCK_ENVIRONMENT_REQUIRED")
        if args.request_file is None or args.receipt_file is None:
            raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_INPUT_REQUIRED")
        if args.request_file.stat().st_size > 65536:
            raise WanImageEditGateError("WAN_IMAGE_EDIT_GATE_INPUT_TOO_LARGE")
        request = TaskProviderRequest.model_validate_json(args.request_file.read_bytes())
        report = asyncio.run(
            execute_gate(settings.wan_image_edit, request, args.receipt_file, resume=args.resume)
        )
        print(json.dumps(report, sort_keys=True))
        return 0
    except TaskProviderError as error:
        print(
            json.dumps(
                {
                    "errorCode": error.code,
                    "submissionState": error.submission_state.value,
                    "retrySubmission": False,
                }
            )
        )
    except WanImageEditGateError as error:
        print(str(error))
    except (OSError, ValidationError, ValueError):
        print("WAN_IMAGE_EDIT_GATE_INPUT_OR_CONFIGURATION_INVALID")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
