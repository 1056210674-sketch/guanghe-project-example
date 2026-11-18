"""Offline tests for the explicitly authorized Wan Video remote Gate."""

import asyncio
import json
import os
import stat
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest
from pydantic import SecretStr

from app.common.settings import AppSettings, ProviderSettings
from app.providers.models import ProviderErrorCategory, SubmissionState, TaskProviderError
from app.providers.wan_video import WanVideoAdapter
from app.providers.wan_video_gate import (
    WanVideoGateAttemptError,
    WanVideoGateError,
    execute_gate,
    main,
    resume_gate,
)

ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis"
SUBMIT_REQUEST_ID = "123e4567-e89b-42d3-a456-426614174101"
RESULT_REQUEST_ID = "123e4567-e89b-42d3-a456-426614174102"
TASK_ID = "provider-video-task-123456"
VIDEO_URL = (
    "https://dashscope-result-test.oss-cn-beijing.aliyuncs.com/"
    "gate.mp4?sensitive-signature=must-not-leak"
)
VIDEO = b"\x00\x00\x00\x18ftypmp42" + (b"synthetic-mp4-byte" * 10)
REFERENCE_URL = "https://reference.example/product.png?signature=test"
STATE_KEYS = {
    "schemaVersion",
    "provider",
    "model",
    "providerRequestId",
    "providerTaskId",
    "submissionState",
    "createdAt",
    "updatedAt",
    "region",
    "endpointHost",
}


@dataclass
class _Counter:
    probe_queries: int
    submissions: int
    polls: int
    downloads: int


def _settings() -> ProviderSettings:
    return ProviderSettings(
        credential=SecretStr("unit-test-only-credential"),
        model="wan3.0-video",
        endpoint=ENDPOINT,
        timeout_seconds=2,
        probe_timeout_seconds=1,
        max_retries=0,
    )


async def _no_wait(
    _self: WanVideoAdapter,
    _deadline: float,
    *,
    provider_request_id: object,
    provider_task_id: str,
) -> None:
    assert provider_request_id is not None
    assert provider_task_id


async def _fake_ffprobe(path: Path, *, ffprobe_path: str) -> SimpleNamespace:
    assert await asyncio.to_thread(path.read_bytes) == VIDEO
    assert ffprobe_path == "unit-test-ffprobe"
    return SimpleNamespace(
        container_format="MP4",
        codec="h264",
        width=832,
        height=480,
        duration_seconds=2.0,
        size_bytes=len(VIDEO),
        sha256="",
    )


def _provider_response(status: str) -> dict[str, object]:
    output: dict[str, object] = {"task_id": TASK_ID, "task_status": status}
    response: dict[str, object] = {
        "request_id": RESULT_REQUEST_ID if status == "SUCCEEDED" else SUBMIT_REQUEST_ID,
        "output": output,
    }
    if status == "SUCCEEDED":
        output["video_url"] = VIDEO_URL
        response["usage"] = {
            "video_count": 1,
            "duration": 2.0,
            "output_video_duration": 2.0,
            "fps": 30,
            "SR": 480,
            "ratio": "16:9",
        }
    return response


def _probe_response() -> dict[str, object]:
    return {
        "request_id": RESULT_REQUEST_ID,
        "output": {
            "task_id": "00000000-0000-0000-0000-000000000000",
            "task_status": "UNKNOWN",
        },
    }


def _state_payload(**overrides: object) -> dict[str, object]:
    now = datetime.now(UTC).isoformat()
    payload: dict[str, object] = {
        "schemaVersion": 1,
        "provider": "WAN_VIDEO",
        "model": "wan3.0-video",
        "providerRequestId": SUBMIT_REQUEST_ID,
        "providerTaskId": TASK_ID,
        "submissionState": "UNKNOWN",
        "createdAt": now,
        "updatedAt": now,
        "region": "cn-beijing",
        "endpointHost": "dashscope.aliyuncs.com",
    }
    payload.update(overrides)
    return payload


def _write_state(path: Path, payload: dict[str, object] | None = None) -> None:
    data = json.dumps(payload or _state_payload()).encode()
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(descriptor, data)
    finally:
        os.close(descriptor)
    os.chmod(path, 0o600)


def _success_transport(
    calls: list[tuple[str, str]],
    *,
    state_file: Path | None = None,
    captured_state: list[dict[str, object]] | None = None,
) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path))
        if request.url.path.endswith("/00000000-0000-0000-0000-000000000000"):
            return httpx.Response(200, json=_probe_response())
        if request.method == "POST":
            assert request.headers["X-DashScope-Async"] == "enable"
            payload = json.loads(request.content)
            assert payload["model"] == "wan3.0-video"
            assert payload["input"]["media"] == [{"type": "first_frame", "url": REFERENCE_URL}]
            assert "仅使用本次消息参考图片" in payload["input"]["prompt"]
            assert payload["parameters"] == {
                "duration": 2,
                "ratio": "16:9",
                "resolution": "480P",
                "audio": False,
                "seed": -1,
                "prompt_extend": True,
                "watermark": False,
            }
            return httpx.Response(200, json=_provider_response("PENDING"))
        if request.url.path == f"/api/v1/tasks/{TASK_ID}":
            if state_file is not None:
                assert state_file.exists(), "state must exist before the first poll"
                state = json.loads(state_file.read_bytes())
                assert set(state) == STATE_KEYS
                assert state["providerRequestId"] == SUBMIT_REQUEST_ID
                assert state["providerTaskId"] == TASK_ID
                if captured_state is not None:
                    captured_state.append(state)
            return httpx.Response(200, json=_provider_response("SUCCEEDED"))
        return httpx.Response(200, content=VIDEO, headers={"Content-Type": "video/mp4"})

    return httpx.MockTransport(handler)


def test_gate_refuses_to_load_credentials_without_explicit_mode(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    def fail_if_loaded() -> None:
        raise AssertionError("default-safe path must not load credentials")

    monkeypatch.setattr("app.providers.wan_video_gate.AppSettings", fail_if_loaded)
    assert main([]) == 2
    assert json.loads(capsys.readouterr().out) == {
        "code": "WAN_VIDEO_GATE_REQUIRES_EXPLICIT_MODE",
        "status": "NOT_EXECUTED",
    }


def test_gate_requires_state_file_before_loading_credentials(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    def fail_if_loaded() -> None:
        raise AssertionError("missing state destination must fail before credentials")

    monkeypatch.setattr("app.providers.wan_video_gate.AppSettings", fail_if_loaded)
    with pytest.raises(SystemExit) as captured:
        main(["--execute"])
    assert captured.value.code == 1
    assert json.loads(capsys.readouterr().out) == {
        "code": "WAN_VIDEO_GATE_STATE_FILE_REQUIRED",
        "status": "FAILED",
    }


def test_post_persists_minimal_state_atomically_before_first_poll(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    calls: list[tuple[str, str]] = []
    captured_state: list[dict[str, object]] = []
    replace_calls: list[tuple[Path, Path]] = []
    open_modes: list[int] = []
    state_file = tmp_path / ".wan-video-gate-state.json"
    real_replace = os.replace
    real_open = os.open

    def replace(source: Path, destination: Path) -> None:
        replace_calls.append((Path(source), Path(destination)))
        real_replace(source, destination)

    def secure_open(
        path: str | bytes | os.PathLike[str] | os.PathLike[bytes],
        flags: int,
        mode: int = 0o777,
    ) -> int:
        if str(path).endswith(".tmp"):
            open_modes.append(mode)
        return real_open(path, flags, mode)

    monkeypatch.setattr("app.providers.wan_video_gate.os.replace", replace)
    monkeypatch.setattr("app.providers.wan_video_gate.os.open", secure_open)
    monkeypatch.setattr(WanVideoAdapter, "_wait_until_next_poll", _no_wait)
    monkeypatch.setattr("app.providers.wan_video_gate._run_ffprobe", _fake_ffprobe)
    report = asyncio.run(
        execute_gate(
            _settings(),
            state_file=state_file,
            reference_image_url=REFERENCE_URL,
            transport=_success_transport(
                calls,
                state_file=state_file,
                captured_state=captured_state,
            ),
            ffprobe_path="unit-test-ffprobe",
        )
    )

    assert calls == [
        ("GET", "/api/v1/tasks/00000000-0000-0000-0000-000000000000"),
        ("POST", "/api/v1/services/aigc/video-generation/video-synthesis"),
        ("GET", f"/api/v1/tasks/{TASK_ID}"),
        ("GET", "/gate.mp4"),
    ]
    assert len(replace_calls) == 1
    assert replace_calls[0][1] == state_file
    assert replace_calls[0][0].parent == state_file.parent
    assert open_modes == [0o600]
    assert captured_state and set(captured_state[0]) == STATE_KEYS
    serialized_state = json.dumps(captured_state[0])
    for forbidden in ("unit-test-only-credential", "白色背景", VIDEO_URL, "Authorization"):
        assert forbidden not in serialized_state
    assert report.submissions == 1
    assert report.polls == 1
    assert report.downloads == 1
    assert report.recovery_attempts == 0
    assert report.state_file_deleted is True
    assert report.temporary_file_deleted is True
    assert not state_file.exists()
    assert not list(tmp_path.glob("*.tmp"))


def test_unknown_preserves_same_state_and_reports_one_post_one_get(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    state_file = tmp_path / ".wan-video-gate-state.json"
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.method)
        if request.url.path.endswith("/00000000-0000-0000-0000-000000000000"):
            return httpx.Response(200, json=_probe_response())
        if request.method == "POST":
            return httpx.Response(200, json=_provider_response("PENDING"))
        return httpx.Response(200, json=_provider_response("UNKNOWN"))

    monkeypatch.setattr(WanVideoAdapter, "_wait_until_next_poll", _no_wait)
    with pytest.raises(WanVideoGateAttemptError) as captured:
        asyncio.run(
            execute_gate(
                _settings(),
                state_file=state_file,
                reference_image_url=REFERENCE_URL,
                transport=httpx.MockTransport(handler),
            )
        )

    error = captured.value
    state = json.loads(state_file.read_bytes())
    assert error.submissions == 1
    assert error.polls == 1
    assert error.recovery_attempts == 0
    assert calls.count("POST") == 1
    assert state["providerRequestId"] == SUBMIT_REQUEST_ID
    assert state["providerTaskId"] == TASK_ID
    assert state["submissionState"] == "UNKNOWN"
    assert set(state) == STATE_KEYS
    assert not list(tmp_path.glob("*.tmp"))


def test_new_process_resume_is_get_only_and_validates_video(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    state_file = tmp_path / ".wan-video-gate-state.json"
    _write_state(state_file)
    calls: list[tuple[str, str]] = []
    monkeypatch.setattr(WanVideoAdapter, "_wait_until_next_poll", _no_wait)
    monkeypatch.setattr("app.providers.wan_video_gate._run_ffprobe", _fake_ffprobe)
    report = asyncio.run(
        resume_gate(
            _settings(),
            resume_from=state_file,
            transport=_success_transport(calls),
            ffprobe_path="unit-test-ffprobe",
        )
    )

    assert calls == [("GET", f"/api/v1/tasks/{TASK_ID}"), ("GET", "/gate.mp4")]
    assert report.probe_queries == 0
    assert report.submissions == 0
    assert report.polls == 1
    assert report.downloads == 1
    assert report.recovery_attempts == 1
    assert report.container_format == "MP4"
    assert (report.width, report.height) == (832, 480)
    assert report.duration_seconds == 2.0
    assert report.size_bytes == len(VIDEO)
    assert len(report.sha256) == 64
    assert report.provider_cost is None
    assert report.currency is None
    assert report.state_file_deleted is True
    assert not state_file.exists()


def test_resume_unknown_keeps_identity_and_never_posts(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    state_file = tmp_path / ".wan-video-gate-state.json"
    _write_state(state_file)
    original = json.loads(state_file.read_bytes())
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.method)
        return httpx.Response(200, json=_provider_response("UNKNOWN"))

    monkeypatch.setattr(WanVideoAdapter, "_wait_until_next_poll", _no_wait)
    with pytest.raises(WanVideoGateAttemptError) as captured:
        asyncio.run(
            resume_gate(
                _settings(),
                resume_from=state_file,
                transport=httpx.MockTransport(handler),
            )
        )

    state = json.loads(state_file.read_bytes())
    assert captured.value.submissions == 0
    assert captured.value.polls == 1
    assert captured.value.recovery_attempts == 1
    assert calls == ["GET"]
    assert state["providerRequestId"] == original["providerRequestId"]
    assert state["providerTaskId"] == original["providerTaskId"]
    assert datetime.fromisoformat(state["createdAt"]) == datetime.fromisoformat(
        original["createdAt"]
    )
    assert state["updatedAt"] >= original["updatedAt"]


def test_confirmed_failure_deletes_recovery_state(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    state_file = tmp_path / ".wan-video-gate-state.json"
    _write_state(state_file)

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_provider_response("FAILED"))

    monkeypatch.setattr(WanVideoAdapter, "_wait_until_next_poll", _no_wait)
    with pytest.raises(WanVideoGateAttemptError) as captured:
        asyncio.run(
            resume_gate(
                _settings(),
                resume_from=state_file,
                transport=httpx.MockTransport(handler),
            )
        )
    assert captured.value.provider_error.submission_state is SubmissionState.CONFIRMED
    assert captured.value.submissions == 0
    assert captured.value.polls == 1
    assert not state_file.exists()


@pytest.mark.parametrize(
    "payload",
    (
        b"not-json",
        json.dumps(_state_payload(unexpected=True)).encode(),
        json.dumps(_state_payload(provider="QWEN_IMAGE")).encode(),
        json.dumps(_state_payload(model="wan3.0-video-prime")).encode(),
        json.dumps(_state_payload(providerTaskId="bad task id")).encode(),
        json.dumps(_state_payload(providerRequestId="not-a-uuid")).encode(),
        json.dumps(_state_payload(submissionState="CONFIRMED")).encode(),
        json.dumps(_state_payload(endpointHost="unknown.example.com")).encode(),
    ),
)
def test_invalid_recovery_state_is_rejected_before_http(
    tmp_path: Path,
    payload: bytes,
) -> None:
    state_file = tmp_path / ".wan-video-gate-state.json"
    descriptor = os.open(state_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(descriptor, payload)
    finally:
        os.close(descriptor)
    os.chmod(state_file, 0o600)
    http_calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal http_calls
        http_calls += 1
        return httpx.Response(500)

    with pytest.raises(WanVideoGateError, match="WAN_VIDEO_GATE_STATE_FILE_INVALID"):
        asyncio.run(
            resume_gate(
                _settings(),
                resume_from=state_file,
                transport=httpx.MockTransport(handler),
            )
        )
    assert http_calls == 0


def test_recovery_endpoint_identity_mismatch_is_rejected_before_http(
    tmp_path: Path,
) -> None:
    state_file = tmp_path / ".wan-video-gate-state.json"
    _write_state(
        state_file,
        _state_payload(endpointHost="workspace.cn-beijing.maas.aliyuncs.com"),
    )
    http_calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal http_calls
        http_calls += 1
        return httpx.Response(500)

    with pytest.raises(WanVideoGateError, match="WAN_VIDEO_GATE_STATE_ENDPOINT_MISMATCH"):
        asyncio.run(
            resume_gate(
                _settings(),
                resume_from=state_file,
                transport=httpx.MockTransport(handler),
            )
        )
    assert http_calls == 0


@pytest.mark.skipif(os.name != "posix", reason="POSIX mode enforcement is Linux-specific")
def test_recovery_state_requires_mode_0600_on_linux(tmp_path: Path) -> None:
    state_file = tmp_path / ".wan-video-gate-state.json"
    _write_state(state_file)
    state_file.chmod(0o644)
    assert stat.S_IMODE(state_file.stat().st_mode) == 0o644
    with pytest.raises(WanVideoGateError, match="WAN_VIDEO_GATE_STATE_PERMISSIONS_INVALID"):
        asyncio.run(
            resume_gate(
                _settings(),
                resume_from=state_file,
                transport=httpx.MockTransport(lambda _request: httpx.Response(500)),
            )
        )


def test_resume_cli_failure_redacts_ids_and_reports_zero_posts(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    tmp_path: Path,
) -> None:
    settings = AppSettings(environment="test").model_copy(update={"wan_video": _settings()})
    provider_error = TaskProviderError(
        "WAN_VIDEO_POLL_TIMEOUT",
        category=ProviderErrorCategory.TIMEOUT,
        retryable=False,
        submission_state=SubmissionState.UNKNOWN,
        provider_request_id=SUBMIT_REQUEST_ID,
        provider_task_id=TASK_ID,
    )
    counter = _Counter(probe_queries=0, submissions=0, polls=3, downloads=0)

    async def fail_with_unknown(
        _settings_value: ProviderSettings,
        *,
        resume_from: Path,
        ffprobe_path: str,
    ) -> object:
        assert resume_from.is_absolute()
        assert ffprobe_path == "ffprobe"
        raise WanVideoGateAttemptError(
            provider_error,
            counter=counter,
            recovery_attempts=1,
        )

    monkeypatch.setattr("app.providers.wan_video_gate.AppSettings", lambda: settings)
    monkeypatch.setattr("app.providers.wan_video_gate.resume_gate", fail_with_unknown)
    with pytest.raises(SystemExit) as captured:
        main(["--resume-from", str(tmp_path / ".wan-video-gate-state.json")])
    assert captured.value.code == 1
    output = json.loads(capsys.readouterr().out)
    assert output == {
        "code": "WAN_VIDEO_POLL_TIMEOUT",
        "downloads": 0,
        "polls": 3,
        "probeQueries": 0,
        "providerRequestId": "123e...4101",
        "providerTaskId": "prov...3456",
        "recoveryAttempts": 1,
        "status": "FAILED",
        "submissionState": "UNKNOWN",
        "submissions": 0,
    }
    serialized = json.dumps(output)
    assert SUBMIT_REQUEST_ID not in serialized
    assert TASK_ID not in serialized
    assert "unit-test-only-credential" not in serialized


@pytest.mark.parametrize(
    ("content_type", "body", "expected_code"),
    (
        ("application/octet-stream", VIDEO, "WAN_VIDEO_GATE_CONTENT_TYPE_INVALID"),
        ("video/mp4", b"not-an-mp4", "WAN_VIDEO_GATE_MP4_SIGNATURE_INVALID"),
    ),
)
def test_gate_rejects_invalid_download_without_exposing_url(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    content_type: str,
    body: bytes,
    expected_code: str,
) -> None:
    state_file = tmp_path / ".wan-video-gate-state.json"

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/00000000-0000-0000-0000-000000000000"):
            return httpx.Response(200, json=_probe_response())
        if request.method == "POST":
            return httpx.Response(200, json=_provider_response("PENDING"))
        if request.url.path.startswith("/api/v1/tasks/"):
            return httpx.Response(200, json=_provider_response("SUCCEEDED"))
        return httpx.Response(200, content=body, headers={"Content-Type": content_type})

    monkeypatch.setattr(WanVideoAdapter, "_wait_until_next_poll", _no_wait)
    with pytest.raises(WanVideoGateError, match=expected_code) as captured:
        asyncio.run(
            execute_gate(
                _settings(),
                state_file=state_file,
                reference_image_url=REFERENCE_URL,
                transport=httpx.MockTransport(handler),
            )
        )
    assert "sensitive-signature" not in str(captured.value)
    assert not state_file.exists(), "provider reached a definitive terminal state"


def test_gate_rejects_any_model_other_than_fixed_standard_model(tmp_path: Path) -> None:
    settings = _settings().model_copy(update={"model": "wan3.0-video-prime"})
    with pytest.raises(WanVideoGateError, match="WAN_VIDEO_GATE_MODEL_NOT_WAN_3_0_VIDEO"):
        asyncio.run(
            execute_gate(
                settings,
                state_file=tmp_path / ".wan-video-gate-state.json",
                reference_image_url=REFERENCE_URL,
                transport=httpx.MockTransport(lambda _request: httpx.Response(500)),
            )
        )
