"""System endpoint behavior tests."""

from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.api.application import create_app
from app.common.build_info import discover_git_commit
from app.common.runtime import initialize_runtime
from app.common.settings import ProviderSettings
from tests.internal_security import FakeInternalNonceStore, internal_settings, signed_headers


def test_liveness_and_readiness_are_distinct() -> None:
    with TestClient(
        create_app(
            internal_settings(),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        assert client.get("/health").json() == {
            "status": "ok",
            "service": "guanghe-agent",
        }
        ready = client.get("/internal/v1/health/ready")
        assert ready.status_code == 200
        assert ready.json()["status"] == "READY"
        checks = {item["name"]: item for item in ready.json()["checks"]}
        assert checks["langfuseExport"] == {
            "name": "langfuseExport",
            "status": "DISABLED",
            "reasonCode": "LANGFUSE_EXPORT_DISABLED",
        }


def test_capabilities_do_not_fake_provider_availability() -> None:
    settings = internal_settings(
        model_gateway="LOCAL",
        deepseek=ProviderSettings(
            credential=SecretStr("test-only"),
            model="deepseek-test-model",
        ),
    )
    with TestClient(
        create_app(settings, internal_nonce_store=FakeInternalNonceStore()),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        response = client.get(
            "/internal/v1/capabilities",
            headers=signed_headers("GET", "/internal/v1/capabilities"),
        )
    assert response.status_code == 200
    payload = response.json()
    assert payload["gitCommit"] == discover_git_commit()
    statuses = {item["code"]: item["status"] for item in payload["providers"]}
    assert statuses == {
        "DEEPSEEK": "CONFIGURED",
        "QWEN": "NOT_CONFIGURED",
        "BAILIAN_QWEN_ROUTER": "NOT_CONFIGURED",
        "QWEN_IMAGE": "NOT_CONFIGURED",
        "WAN_VIDEO": "NOT_CONFIGURED",
    }
    loaded = initialize_runtime(settings).bundle
    assert loaded is not None
    assert payload["bundleVersion"] == loaded.bundle.bundle_version
    assert payload["bundleHash"] == loaded.bundle_hash
    assert payload["modelGateway"] == {
        "mode": "MOCK",
        "provider": "LOCAL",
        "model": "deterministic-router",
        "status": "AVAILABLE",
        "reasonCode": None,
    }
    assert payload["intentRouter"] == {
        "routerProvider": "LOCAL",
        "routerModel": "deterministic-router",
        "routerMode": "NATIVE_LOOP",
        "available": True,
        "schemaVersion": "native-tool-loop-v1",
        "status": "AVAILABLE",
        "reasonCode": None,
    }
    assert payload["langfuseExport"] == {
        "status": "DISABLED",
        "reasonCode": "LANGFUSE_EXPORT_DISABLED",
    }
    assert len(payload["bundleHash"]) == 64
    assert "test-only" not in response.text


def test_retired_router_configuration_does_not_block_native_gateway() -> None:
    settings = internal_settings(
        model_gateway="LOCAL",
        intent_router_mode="QWEN_PRIMARY",
    )
    with TestClient(
        create_app(settings, internal_nonce_store=FakeInternalNonceStore()),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        capabilities = client.get(
            "/internal/v1/capabilities",
            headers=signed_headers("GET", "/internal/v1/capabilities"),
        )

    assert readiness.status_code == 200
    checks = {item["name"]: item for item in readiness.json()["checks"]}
    assert checks["intentRouter"] == {
        "name": "intentRouter",
        "status": "READY",
        "reasonCode": None,
    }
    assert capabilities.status_code == 200
    router = capabilities.json()["intentRouter"]
    assert router == {
        "routerProvider": "LOCAL",
        "routerModel": "deterministic-router",
        "routerMode": "NATIVE_LOOP",
        "available": True,
        "schemaVersion": "native-tool-loop-v1",
        "status": "AVAILABLE",
        "reasonCode": None,
    }
    providers = {item["code"]: item for item in capabilities.json()["providers"]}
    assert providers["BAILIAN_QWEN_ROUTER"]["status"] == "NOT_CONFIGURED"


@pytest.mark.parametrize(
    ("provider", "reason"),
    (
        ("DEEPSEEK", "DEEPSEEK_CREDENTIAL_NOT_CONFIGURED"),
        ("QWEN", "QWEN_CONVERSATION_GATEWAY_DISABLED"),
    ),
)
def test_unavailable_native_gateway_still_fails_closed(provider: str, reason: str) -> None:
    with TestClient(
        create_app(
            internal_settings(model_gateway=provider),
            internal_nonce_store=FakeInternalNonceStore(),
        ),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        response = client.get(
            "/internal/v1/capabilities",
            headers=signed_headers("GET", "/internal/v1/capabilities"),
        )
    assert readiness.status_code == 503
    checks = {item["name"]: item for item in readiness.json()["checks"]}
    for name in ("modelGateway", "intentRouter"):
        assert checks[name]["status"] == "NOT_READY"
    assert checks["modelGateway"]["reasonCode"] == reason
    assert checks["intentRouter"]["reasonCode"] == "INTENT_ROUTER_NOT_READY"
    assert response.status_code == 200
    router = response.json()["intentRouter"]
    assert router["routerProvider"] == provider
    assert router["routerMode"] == "NATIVE_LOOP"
    assert router["available"] is False
    assert router["status"] == "UNAVAILABLE"
    assert router["reasonCode"] == reason


def test_invalid_bundle_makes_readiness_fail_safely(tmp_path: Path) -> None:
    missing_path = tmp_path / "server-secret-path" / "missing.yaml"
    settings = internal_settings(bundle_path=missing_path)
    with TestClient(
        create_app(settings, internal_nonce_store=FakeInternalNonceStore()),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        capabilities = client.get(
            "/internal/v1/capabilities",
            headers=signed_headers("GET", "/internal/v1/capabilities"),
        )
    assert readiness.status_code == 503
    assert readiness.json()["checks"][0]["reasonCode"] == "BUNDLE_FILE_NOT_FOUND"
    assert str(missing_path) not in readiness.text
    assert capabilities.status_code == 503
    assert capabilities.json()["code"] == "AGENT_BUNDLE_NOT_READY"


@pytest.mark.parametrize(
    "configured_commit",
    ("f" * 40, "0" * 40, "not-a-full-commit"),
)
def test_untrusted_build_commit_is_not_ready_and_never_serializes_null(
    configured_commit: str,
) -> None:
    settings = internal_settings(git_commit=configured_commit)
    with TestClient(
        create_app(settings, internal_nonce_store=FakeInternalNonceStore()),
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 50000),
    ) as client:
        readiness = client.get("/internal/v1/health/ready")
        capabilities = client.get(
            "/internal/v1/capabilities",
            headers=signed_headers("GET", "/internal/v1/capabilities"),
        )
    checks = {item["name"]: item for item in readiness.json()["checks"]}
    assert readiness.status_code == 503
    assert checks["buildCommit"]["reasonCode"] == "BUILD_COMMIT_NOT_READY"
    assert capabilities.status_code == 503
    assert capabilities.json()["code"] == "BUILD_COMMIT_NOT_READY"
    assert '"gitCommit":null' not in capabilities.text
