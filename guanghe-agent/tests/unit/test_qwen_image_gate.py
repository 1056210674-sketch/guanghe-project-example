"""Offline tests for the explicitly authorized Qwen Image remote Gate."""

import asyncio
import json
import struct
import zlib

import httpx
import pytest
from pydantic import SecretStr

from app.common.settings import AppSettings, ProviderSettings
from app.providers.qwen_image import QwenImageAdapter
from app.providers.qwen_image_gate import QwenImageGateError, execute_gate, main

SUBMIT_REQUEST_ID = "123e4567-e89b-42d3-a456-426614174101"
REFERENCE_URL = "https://reference.example/product.png?signature=test"


def _settings() -> ProviderSettings:
    return ProviderSettings(
        credential=SecretStr("unit-test-only-credential"),
        model="qwen-image-3.0",
        endpoint=(
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation"
        ),
        timeout_seconds=2,
        probe_timeout_seconds=1,
        max_retries=0,
    )


def _png(width: int, height: int) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        payload = kind + data
        return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IEND", b"")


def test_gate_refuses_to_read_configuration_without_explicit_execute(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    def fail_if_loaded() -> None:
        raise AssertionError("default-safe path must not load credentials")

    monkeypatch.setattr("app.providers.qwen_image_gate.AppSettings", fail_if_loaded)

    assert main([]) == 2
    output = json.loads(capsys.readouterr().out)
    assert output == {
        "code": "QWEN_IMAGE_GATE_REQUIRES_EXPLICIT_EXECUTE",
        "status": "NOT_EXECUTED",
    }


@pytest.mark.parametrize(
    ("updates", "expected_code"),
    (
        (
            {"environment": "local", "model_gateway": "DEEPSEEK"},
            "QWEN_IMAGE_GATE_TEST_ENVIRONMENT_REQUIRED",
        ),
        (
            {"environment": "test", "model_gateway": "LOCAL"},
            "QWEN_IMAGE_GATE_DEEPSEEK_CONFIGURATION_REQUIRED",
        ),
        (
            {
                "environment": "test",
                "model_gateway": "DEEPSEEK",
                "worker_provider": "REAL",
            },
            "QWEN_IMAGE_GATE_WORKER_MUST_REMAIN_MOCK",
        ),
    ),
)
def test_gate_requires_safe_test_runtime_before_loading_provider(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    updates: dict[str, str],
    expected_code: str,
) -> None:
    settings = AppSettings().model_copy(update=updates)
    monkeypatch.setattr("app.providers.qwen_image_gate.AppSettings", lambda: settings)

    with pytest.raises(SystemExit) as captured:
        main(["--execute", "--reference-image-url", REFERENCE_URL])

    assert captured.value.code == 1
    assert json.loads(capsys.readouterr().out) == {"code": expected_code, "status": "FAILED"}


def test_gate_reuses_router_adapter_and_client_then_reports_sanitized_png_stats(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    png = _png(1_024, 1_024)
    calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path))
        if request.method == "POST":
            assert request.headers["x-dashscope-async"] == "enable"
            payload = json.loads(request.content)
            assert payload["model"] == "qwen-image-3.0"
            content = payload["input"]["messages"][0]["content"]
            assert content[0] == {"image": REFERENCE_URL}
            assert len(content) == 2
            assert "白色背景上的简约陶瓷马克杯" in content[1]["text"]
            assert "仅使用本次消息参考图片" in content[1]["text"]
            assert payload["parameters"]["n"] == 1
            assert payload["parameters"]["size"] == "1024*1024"
            return httpx.Response(
                200,
                json={
                    "output": {"task_status": "PENDING", "task_id": "provider-task-123456"},
                    "request_id": SUBMIT_REQUEST_ID,
                },
            )
        if request.url.path == "/api/v1/tasks/provider-task-123456":
            return httpx.Response(
                200,
                json={
                    "output": {
                        "task_status": "SUCCEEDED",
                        "task_id": "provider-task-123456",
                        "choices": [
                            {
                                "message": {
                                    "content": [
                                        {
                                            "image": (
                                                "https://dashscope-result-test.oss-cn-beijing."
                                                "aliyuncs.com/gate.png?"
                                                "sensitive-signature=must-not-leak"
                                            )
                                        }
                                    ]
                                }
                            }
                        ],
                    },
                    "usage": {
                        "output_width": 1_024,
                        "output_height": 1_024,
                        "output_image_count": 1,
                    },
                },
            )
        assert request.url.host == "dashscope-result-test.oss-cn-beijing.aliyuncs.com"
        return httpx.Response(200, content=png, headers={"Content-Type": "image/png"})

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)

    async def scenario():
        return await execute_gate(
            _settings(),
            reference_image_url=REFERENCE_URL,
            transport=httpx.MockTransport(handler),
        )

    report = asyncio.run(scenario())
    public = report.to_public_dict()

    assert calls == [
        ("POST", "/api/v1/services/aigc/image-generation/generation"),
        ("GET", "/api/v1/tasks/provider-task-123456"),
        ("GET", "/gate.png"),
    ]
    assert report.provider_request_id == SUBMIT_REQUEST_ID
    assert report.provider_task_id == "provider-task-123456"
    assert report.polls == 1
    assert report.downloads == 1
    assert report.image_count == 1
    assert (report.image_format, report.width, report.height) == ("PNG", 1_024, 1_024)
    assert report.size_bytes == len(png)
    assert len(report.sha256) == 64
    assert report.temporary_file_deleted is True
    serialized = json.dumps(public)
    assert public["provider_request_id"] == "123e...4101"
    assert public["provider_task_id"] == "prov...3456"
    assert SUBMIT_REQUEST_ID not in serialized
    assert "provider-task-123456" not in serialized
    assert "sensitive-signature" not in serialized
    assert "unit-test-only-credential" not in serialized


def test_gate_requires_at_least_one_async_poll() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.method)
        if request.method != "POST":
            raise AssertionError("Gate must reject before downloading a zero-poll result")
        return httpx.Response(
            200,
            json={
                "output": {
                    "task_status": "SUCCEEDED",
                    "task_id": "provider-task-123456",
                    "choices": [
                        {
                            "message": {
                                "content": [
                                    {
                                        "image": (
                                            "https://dashscope-result-test.oss-cn-beijing."
                                            "aliyuncs.com/gate.png"
                                        )
                                    }
                                ]
                            }
                        }
                    ],
                },
                "usage": {"output_image_count": 1},
                "request_id": SUBMIT_REQUEST_ID,
            },
        )

    with pytest.raises(QwenImageGateError, match="QWEN_IMAGE_GATE_POLL_MISSING"):
        asyncio.run(
            execute_gate(
                _settings(),
                reference_image_url=REFERENCE_URL,
                transport=httpx.MockTransport(handler),
            )
        )
    assert calls == ["POST"]


@pytest.mark.parametrize(
    "artifact_url",
    (
        "https://127.0.0.1/private.png",
        "https://localhost/private.png",
        "https://artifacts.example/private.png",
        "https://aliyuncs.com/private.png",
    ),
)
def test_gate_rejects_non_provider_artifact_hosts_before_download(
    monkeypatch: pytest.MonkeyPatch,
    artifact_url: str,
) -> None:
    calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path))
        if request.method == "POST":
            return httpx.Response(
                200,
                json={
                    "output": {"task_status": "PENDING", "task_id": "provider-task-123456"},
                    "request_id": SUBMIT_REQUEST_ID,
                },
            )
        if request.url.path.startswith("/api/v1/tasks/"):
            return httpx.Response(
                200,
                json={
                    "output": {
                        "task_status": "SUCCEEDED",
                        "task_id": "provider-task-123456",
                        "choices": [{"message": {"content": [{"image": artifact_url}]}}],
                    },
                    "usage": {"output_image_count": 1},
                },
            )
        raise AssertionError("Unsafe artifact URL must not be downloaded")

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)
    with pytest.raises(QwenImageGateError, match="QWEN_IMAGE_GATE_ARTIFACT_URL_INVALID"):
        asyncio.run(
            execute_gate(
                _settings(),
                reference_image_url=REFERENCE_URL,
                transport=httpx.MockTransport(handler),
            )
        )
    assert len(calls) == 2


@pytest.mark.parametrize(
    ("content_type", "body", "expected_code"),
    (
        ("application/octet-stream", _png(10, 10), "QWEN_IMAGE_GATE_CONTENT_TYPE_INVALID"),
        ("image/png", b"not-a-png", "QWEN_IMAGE_GATE_PNG_INVALID"),
        ("image/png", _png(10, 10), "QWEN_IMAGE_GATE_DIMENSIONS_MISMATCH"),
    ),
)
def test_gate_rejects_non_png_downloads_without_exposing_the_url(
    monkeypatch: pytest.MonkeyPatch,
    content_type: str,
    body: bytes,
    expected_code: str,
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            return httpx.Response(
                200,
                json={
                    "output": {"task_status": "PENDING", "task_id": "provider-task-123456"},
                    "request_id": SUBMIT_REQUEST_ID,
                },
            )
        if request.url.path.startswith("/api/v1/tasks/"):
            return httpx.Response(
                200,
                json={
                    "output": {
                        "task_status": "SUCCEEDED",
                        "task_id": "provider-task-123456",
                        "choices": [
                            {
                                "message": {
                                    "content": [
                                        {
                                            "image": (
                                                "https://dashscope-result-test.oss-cn-beijing."
                                                "aliyuncs.com/gate.png?secret=hidden"
                                            )
                                        }
                                    ]
                                }
                            }
                        ],
                    },
                },
            )
        return httpx.Response(200, content=body, headers={"Content-Type": content_type})

    monkeypatch.setattr(QwenImageAdapter, "POLL_INTERVAL_SECONDS", 0)
    with pytest.raises(QwenImageGateError, match=expected_code) as captured:
        asyncio.run(
            execute_gate(
                _settings(),
                reference_image_url=REFERENCE_URL,
                transport=httpx.MockTransport(handler),
            )
        )
    assert "secret=hidden" not in str(captured.value)


def test_gate_rejects_any_model_other_than_the_fixed_gate_model() -> None:
    settings = _settings().model_copy(update={"model": "qwen-image-3.0-pro"})

    with pytest.raises(QwenImageGateError, match="QWEN_IMAGE_GATE_MODEL_NOT_QWEN_IMAGE_3_0"):
        asyncio.run(
            execute_gate(
                settings,
                reference_image_url=REFERENCE_URL,
                transport=httpx.MockTransport(lambda _: httpx.Response(500)),
            )
        )
