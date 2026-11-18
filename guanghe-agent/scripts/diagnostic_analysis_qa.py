"""One signed, delivery-only QA call. Never publish, collect, settle or write a DB.

Run as a module from the deployed checkout, using its existing environment. The
default is offline validation; --execute requires the fixed test host/root scope.
An exclusive persistent guard is created BEFORE the only POST. Any failure or
unknown outcome leaves it intact; there is no replay, retry or guard deletion.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import socket
import stat
import time
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import httpx

from app.common.settings import AppSettings
from app.geo.diagnostic_analysis import (
    _MAX_ANALYSIS_SECONDS,  # pyright: ignore[reportPrivateUsage]
    _MAX_INITIAL_TOKENS,  # pyright: ignore[reportPrivateUsage]
    _MAX_INPUT_TOKENS,  # pyright: ignore[reportPrivateUsage]
    _MAX_OUTPUT_TOKENS,  # pyright: ignore[reportPrivateUsage]
    _REPAIR,  # pyright: ignore[reportPrivateUsage]
    PROMPT_VERSION,
    DiagnosticAnalysisRequest,
    DiagnosticAnalysisResponse,
    _grounded_schema,  # pyright: ignore[reportPrivateUsage]
    _project_evidence,  # pyright: ignore[reportPrivateUsage]
)
from app.harness.runtime import estimate_model_messages
from app.model_gateway.gateway import (
    ModelMessage,
    diagnostic_analysis_json_schema_messages,
    diagnostic_json_request_budget_messages,
    diagnostic_structured_repair_reserve,
)
from app.security.internal_hmac import (
    CONTENT_SHA256_HEADER,
    KEY_ID_HEADER,
    NONCE_HEADER,
    SIGNATURE_HEADER,
    TIMESTAMP_HEADER,
    TRACEPARENT_HEADER,
    sign_content,
)

# Public placeholders only. Preserve the fixed-host and private-root safeguards.
TEST_HOST = "example-isolated-qa-host"
PRIVATE_ROOT = Path("/opt/guanghe-example/private/diagnostic-qa")
JOB_ID = "100000000000000001"
PATH = "/internal/v1/geo/diagnostic-analyses"
ENDPOINT = "http://127.0.0.1:8000" + PATH
REQUEST_FILE = "quality-analysis-request.json"
RESPONSE_FILE = "quality-analysis-response.json"
GUARD_FILE = "quality-analysis-one-call.guard.json"


class QaRejected(RuntimeError):
    """Only code-owned reasons may reach stdout."""


def _private_scope(directory: Path) -> Path:
    if directory.is_symlink() or directory.resolve(strict=True) != PRIVATE_ROOT:
        raise QaRejected("QA_PRIVATE_SCOPE_INVALID")
    info = directory.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o700:
        raise QaRejected("QA_PRIVATE_SCOPE_INVALID")
    return directory


def _load_request(directory: Path) -> DiagnosticAnalysisRequest:
    source = directory / REQUEST_FILE
    info = source.lstat()
    if (
        not stat.S_ISREG(info.st_mode)
        or info.st_uid != 0
        or stat.S_IMODE(info.st_mode) != 0o600
        or info.st_size > 262144
    ):
        raise QaRejected("QA_INPUT_PRIVATE_FILE_INVALID")
    request = DiagnosticAnalysisRequest.model_validate_json(source.read_bytes())
    if (
        request.job_id != JOB_ID
        or request.evidence.metrics.expected_samples != 6
        or request.evidence.metrics.valid_samples != 6
        or any(s.matched_brand_ids is None for s in request.evidence.samples)
    ):
        raise QaRejected("QA_FROZEN_SIX_SAMPLE_SCOPE_INVALID")
    # Root's Java helper derives flags from saved FULL observations. Python never
    # computes flags from excerpts, changes inputHash, or reconstructs old evidence.
    return request.model_copy(
        update={"deadline_at": datetime.now(UTC) + timedelta(seconds=_MAX_ANALYSIS_SECONDS)}
    )


def _projection_summary(payload: DiagnosticAnalysisRequest) -> dict[str, object]:
    messages, shown = _project_evidence(payload)
    schema = _grounded_schema(shown).model_json_schema(by_alias=True)
    json_messages = diagnostic_analysis_json_schema_messages(messages, schema)
    repaired = (
        ModelMessage(
            role="system",
            content=json_messages[0].content
            + "\n\n[STRUCTURED OUTPUT REPAIR]\n"
            + diagnostic_structured_repair_reserve(_REPAIR, schema, output_mode="JSON_OBJECT"),
        ),
        *json_messages[1:],
    )
    view = json.loads(messages[1].content)["projection"]
    return {
        "initialEstimatedTokens": estimate_model_messages(messages),
        "outputMode": "JSON_OBJECT",
        "jsonRequestEstimatedTokens": estimate_model_messages(
            diagnostic_json_request_budget_messages(json_messages)
        ),
        "worstRepairEstimatedTokens": estimate_model_messages(
            diagnostic_json_request_budget_messages(repaired)
        ),
        "answerCharLimit": view["answerCharLimit"],
        "sourceCharLimit": view["sourceCharLimit"],
        "omittedSources": view["omittedSources"],
        "omittedEffectiveAnswers": view["omittedEffectiveAnswers"],
        "matrixUnits": len(payload.evidence.samples),
        "knownMatchUnits": sum(s.matched_brand_ids is not None for s in payload.evidence.samples),
        "initialLimit": _MAX_INITIAL_TOKENS,
        "jsonRequestRepairLimit": _MAX_INPUT_TOKENS,
        "maxModelHttpCalls": 2,
        "maxOutputTokensPerCall": _MAX_OUTPUT_TOKENS,
    }


def _create_private_json(path: Path, value: dict[str, object]) -> None:
    flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, separators=(",", ":"))
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    directory_flag = int(getattr(os, "O_DIRECTORY", 0))
    if directory_flag:
        parent_descriptor = os.open(path.parent, os.O_RDONLY | directory_flag)
        try:
            os.fsync(parent_descriptor)
        finally:
            os.close(parent_descriptor)


def run_qa(
    directory: Path,
    *,
    execute: bool,
    settings: AppSettings | None = None,
    client: httpx.Client | None = None,
) -> dict[str, object]:
    """Injectable transport is solely for offline tests; CLI exposes no override."""
    logging.disable(logging.CRITICAL)
    directory = _private_scope(directory)
    if any(
        (directory / name).exists() or (directory / name).is_symlink()
        for name in (GUARD_FILE, RESPONSE_FILE)
    ):
        raise QaRejected("QA_EXISTING_OUTCOME_DO_NOT_REPLAY")
    payload = _load_request(directory)
    projection = _projection_summary(payload)
    safe: dict[str, object] = {
        "scope": "QA_ONLY_NOT_PUBLISHED",
        "promptVersion": PROMPT_VERSION,
        "projectionLimits": projection,
        "executed": False,
    }
    if not execute:
        return safe
    if socket.gethostname() != TEST_HOST or getattr(os, "geteuid", lambda: -1)() != 0:
        raise QaRejected("QA_EXECUTION_HOST_INVALID")
    resolved = settings or AppSettings()
    if (
        resolved.environment != "test"
        or not resolved.internal_hmac_key_id
        or not resolved.internal_hmac_secret
    ):
        raise QaRejected("QA_INTERNAL_IDENTITY_UNAVAILABLE")
    secret = resolved.internal_hmac_secret.get_secret_value().encode()
    if len(secret) < 32:
        raise QaRejected("QA_INTERNAL_IDENTITY_UNAVAILABLE")
    body = payload.model_dump_json(by_alias=True).encode("utf-8")
    timestamp, nonce = str(int(time.time())), str(uuid4())
    signed = sign_content(
        secret=secret,
        timestamp=timestamp,
        nonce=nonce,
        method="POST",
        raw_path_with_query=PATH,
        body=body,
    )
    headers = {
        KEY_ID_HEADER: resolved.internal_hmac_key_id,
        TIMESTAMP_HEADER: timestamp,
        NONCE_HEADER: nonce,
        CONTENT_SHA256_HEADER: signed.body_sha256,
        SIGNATURE_HEADER: signed.signature_base64,
        TRACEPARENT_HEADER: f"00-{uuid4().hex}-{uuid4().hex[:16]}-01",
        "Content-Type": "application/json",
    }
    _create_private_json(
        directory / GUARD_FILE,
        {
            "scope": "QA_ONLY_NOT_PUBLISHED",
            "promptVersion": PROMPT_VERSION,
            "requestSha256": signed.body_sha256,
            "outcome": "MAY_HAVE_SENT_DO_NOT_REPLAY",
        },
    )

    def send(http: httpx.Client) -> httpx.Response:
        # Exactly ONE API request; the deployed gateway alone owns its bounded
        # one-repair/two-model-call behavior. No POST retry, polling, redirect or DB.
        return http.post(
            ENDPOINT, content=body, headers=headers, timeout=130, follow_redirects=False
        )

    if client is None:
        with httpx.Client(trust_env=False, transport=httpx.HTTPTransport(retries=0)) as http:
            response = send(http)
    else:
        response = send(client)
    if response.status_code != 200:
        raise QaRejected("QA_API_NON_SUCCESS_DO_NOT_REPLAY")
    result = DiagnosticAnalysisResponse.model_validate_json(response.content)
    if (
        result.job_id != payload.job_id
        or result.input_hash != payload.input_hash
        or result.synthesis_usage.provider != "DEEPSEEK"
        or not 1 <= result.synthesis_usage.provider_call_count <= 2
    ):
        raise QaRejected("QA_RESPONSE_IDENTITY_OR_BUDGET_INVALID")
    _, shown = _project_evidence(payload)
    _grounded_schema(shown).model_validate(result.report.model_dump(mode="json", by_alias=True))
    _create_private_json(directory / RESPONSE_FILE, result.model_dump(mode="json", by_alias=True))
    safe.update(
        executed=True,
        usage={
            "provider": result.synthesis_usage.provider,
            "inputTokens": result.synthesis_usage.input_tokens,
            "outputTokens": result.synthesis_usage.output_tokens,
            "providerCallCount": result.synthesis_usage.provider_call_count,
            "latencyMs": result.synthesis_usage.latency_ms,
        },
    )
    return safe


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-directory", required=True, type=Path)
    parser.add_argument("--execute", action="store_true")
    arguments = parser.parse_args()
    try:
        result = run_qa(arguments.private_directory, execute=arguments.execute)
    except QaRejected as exc:
        result = {"scope": "QA_ONLY_NOT_PUBLISHED", "code": str(exc), "retryAllowed": False}
    except Exception:
        # No upstream body, exception text, values, credentials or traceback.
        result = {
            "scope": "QA_ONLY_NOT_PUBLISHED",
            "code": "QA_FAILED_OR_UNKNOWN_DO_NOT_REPLAY",
            "retryAllowed": False,
        }
    print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
    return int("code" in result)


if __name__ == "__main__":
    raise SystemExit(main())
