"""QA helper uses a single signed local POST and never replays an unknown outcome."""

import json
import logging
import stat
from collections.abc import Iterator
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import httpx
import pytest
from pydantic import ValidationError

from app.geo.diagnostic_analysis import DiagnosticAnalysisRequest, DiagnosticAnalysisResponse
from app.security.internal_hmac import sign_content
from scripts import diagnostic_analysis_qa as qa
from tests.internal_security import TEST_SECRET_TEXT, internal_settings
from tests.unit.test_geo_diagnostic_analysis_v2 import (
    _six_sample_payload,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_geo_diagnostic_delivery import (
    _report,  # pyright: ignore[reportPrivateUsage]
)


@pytest.fixture
def scoped_input(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> Iterator[tuple[dict[str, Any], DiagnosticAnalysisRequest, Path]]:
    raw = _six_sample_payload()
    raw["jobId"] = qa.JOB_ID
    parsed = DiagnosticAnalysisRequest.model_validate(raw)

    def private_scope(directory: Path) -> Path:
        return directory

    def load_request(directory: Path) -> DiagnosticAnalysisRequest:
        return parsed

    monkeypatch.setattr(qa, "_private_scope", private_scope)
    monkeypatch.setattr(qa, "_load_request", load_request)
    monkeypatch.setattr(qa.socket, "gethostname", lambda: qa.TEST_HOST)
    monkeypatch.setattr(qa.os, "geteuid", lambda: 0, raising=False)
    previous = logging.root.manager.disable
    yield raw, parsed, tmp_path
    logging.disable(previous)


def _response(raw: dict[str, Any]) -> dict[str, Any]:
    return {
        "jobId": raw["jobId"],
        "inputHash": raw["inputHash"],
        "promptVersion": "GEO_DIAGNOSTIC_ANALYSIS_V11",
        "report": _report(raw),
        "synthesisUsage": {
            "provider": "DEEPSEEK",
            "model": "test-model",
            "inputTokens": 20,
            "outputTokens": 10,
            "providerCallCount": 2,
            "latencyMs": 300,
        },
    }


def test_qa_dry_check_calls_no_http_and_creates_no_guard(scoped_input: Any) -> None:
    assert Path("/opt/zhixiaohe/backups/geo-diagnostic-quality-1014-v9-r1/qa") == qa.PRIVATE_ROOT
    _, _, directory = scoped_input
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        raise AssertionError("Offline check must not call any endpoint")

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = qa.run_qa(directory, execute=False, client=client)
    assert result["scope"] == "QA_ONLY_NOT_PUBLISHED" and not result["executed"]
    assert calls == [] and list(directory.iterdir()) == []
    limits = cast(dict[str, int], result["projectionLimits"])
    assert limits["worstRepairEstimatedTokens"] <= 24000
    assert limits["initialEstimatedTokens"] <= 18000
    assert limits["knownMatchUnits"] == limits["matrixUnits"] == 6
    assert limits["outputMode"] == "JSON_OBJECT"
    assert limits["jsonRequestEstimatedTokens"] <= limits["jsonRequestRepairLimit"] == 24000


def test_qa_one_signed_call_persists_only_validated_response_and_no_values_to_stdout(
    scoped_input: Any,
) -> None:
    raw, parsed, directory = scoped_input
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        assert (directory / qa.GUARD_FILE).exists()
        assert str(request.url) == qa.ENDPOINT and request.method == "POST"
        expected = sign_content(
            secret=TEST_SECRET_TEXT.encode(),
            timestamp=request.headers[qa.TIMESTAMP_HEADER],
            nonce=request.headers[qa.NONCE_HEADER],
            method="POST",
            raw_path_with_query=qa.PATH,
            body=request.content,
        )
        assert request.headers[qa.CONTENT_SHA256_HEADER] == expected.body_sha256
        assert request.headers[qa.SIGNATURE_HEADER] == expected.signature_base64
        posted = json.loads(request.content)
        assert posted["inputHash"] == parsed.input_hash
        assert posted["evidence"] == parsed.evidence.model_dump(mode="json", by_alias=True)
        return httpx.Response(200, json=_response(raw))

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = qa.run_qa(directory, execute=True, client=client, settings=internal_settings())
        with pytest.raises(qa.QaRejected, match="DO_NOT_REPLAY"):
            qa.run_qa(directory, execute=True, client=client, settings=internal_settings())
    saved = json.loads((directory / qa.RESPONSE_FILE).read_text(encoding="utf-8"))
    assert saved == DiagnosticAnalysisResponse.model_validate(_response(raw)).model_dump(
        mode="json", by_alias=True
    )
    assert len(calls) == 1
    safe = json.dumps(result)
    assert "report" not in safe and "http" not in safe and "实际回答" not in safe
    assert "test-model" not in safe and TEST_SECRET_TEXT not in safe
    assert result["executed"]


@pytest.mark.parametrize(
    "outcome", ["timeout", "redirect", "503", "badquote", "wronghash", "threecalls"]
)
def test_unknown_failure_or_invalid_result_leaves_guard_and_never_retries(
    scoped_input: Any, outcome: str
) -> None:
    raw, _, directory = scoped_input
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if outcome == "timeout":
            raise httpx.ReadTimeout("private upstream body", request=request)
        if outcome == "redirect":
            return httpx.Response(307, headers={"Location": "https://provider.invalid/private"})
        if outcome == "503":
            return httpx.Response(503, text="private upstream body")
        response = _response(raw)
        if outcome == "badquote":
            response["report"]["findings"][0]["evidence"][0]["quote"] = "invented"
        elif outcome == "wronghash":
            response["inputHash"] = "b" * 64
        else:
            response["synthesisUsage"]["providerCallCount"] = 3
        return httpx.Response(200, json=response)

    with httpx.Client(transport=httpx.MockTransport(handler), follow_redirects=True) as client:
        with pytest.raises((qa.QaRejected, httpx.ReadTimeout, ValidationError)):
            qa.run_qa(directory, execute=True, settings=internal_settings(), client=client)
        with pytest.raises(qa.QaRejected, match="DO_NOT_REPLAY"):
            qa.run_qa(directory, execute=True, settings=internal_settings(), client=client)
    assert len(calls) == 1
    assert (directory / qa.GUARD_FILE).exists()
    assert not (directory / qa.RESPONSE_FILE).exists()


def test_response_create_new_never_overwrites_existing_report(scoped_input: Any) -> None:
    _, _, directory = scoped_input
    prior = directory / qa.RESPONSE_FILE
    prior.write_text("prior private QA report", encoding="utf-8")
    with pytest.raises(qa.QaRejected, match="DO_NOT_REPLAY"):
        qa.run_qa(directory, execute=True, settings=internal_settings())
    assert prior.read_text(encoding="utf-8") == "prior private QA report"
    assert not (directory / qa.GUARD_FILE).exists()
    with pytest.raises(FileExistsError):
        qa._create_private_json(prior, {"new": True})  # pyright: ignore[reportPrivateUsage]


def test_wrong_host_or_environment_stops_before_guard_or_post(
    scoped_input: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    _, _, directory = scoped_input
    monkeypatch.setattr(qa.socket, "gethostname", lambda: "different-host")
    with pytest.raises(qa.QaRejected, match="HOST_INVALID"):
        qa.run_qa(directory, execute=True, settings=internal_settings())
    monkeypatch.setattr(qa.socket, "gethostname", lambda: qa.TEST_HOST)
    with pytest.raises(qa.QaRejected, match="IDENTITY_UNAVAILABLE"):
        qa.run_qa(directory, execute=True, settings=internal_settings(environment="production"))
    assert list(directory.iterdir()) == []


@pytest.mark.parametrize("change", ["wrongpath", "symlink", "owner", "permissions"])
def test_private_scope_is_exact_root_owned_0700_and_not_symlink(change: str) -> None:
    info = SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_uid=0)
    if change == "owner":
        info.st_uid = 1
    if change == "permissions":
        info.st_mode = stat.S_IFDIR | 0o755

    def resolve(*, strict: bool) -> Path:
        return Path("/different") if change == "wrongpath" else qa.PRIVATE_ROOT

    directory = cast(
        Path,
        SimpleNamespace(
            is_symlink=lambda: change == "symlink",
            resolve=resolve,
            stat=lambda: info,
        ),
    )
    with pytest.raises(qa.QaRejected, match="PRIVATE_SCOPE_INVALID"):
        qa._private_scope(directory)  # pyright: ignore[reportPrivateUsage]


@pytest.mark.parametrize("change", ["job", "unknownflags", "fiveunits", "permissions"])
def test_qa_input_requires_exact_frozen_six_known_samples(
    change: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    raw = deepcopy(_six_sample_payload())
    raw["jobId"] = qa.JOB_ID if change != "job" else "12345"
    if change == "unknownflags":
        raw["evidence"]["samples"][0]["matchedBrandIds"] = None
    if change == "fiveunits":
        raw["evidence"]["samples"].pop()
    source = SimpleNamespace(
        lstat=lambda: SimpleNamespace(
            st_mode=stat.S_IFREG | (0o644 if change == "permissions" else 0o600),
            st_uid=0,
            st_size=100,
        ),
        read_bytes=lambda: json.dumps(raw).encode(),
    )

    def join_path(self: Path, name: str) -> SimpleNamespace:
        return source

    monkeypatch.setattr(Path, "__truediv__", join_path)
    with pytest.raises((qa.QaRejected, ValidationError)):
        qa._load_request(Path("/unused"))  # pyright: ignore[reportPrivateUsage]
