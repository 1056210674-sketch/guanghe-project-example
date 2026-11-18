"""Environment settings tests."""

from pathlib import Path

import pytest
from pydantic import SecretStr, ValidationError

from app.common.build_info import is_full_git_commit
from app.common.settings import AppSettings, ProviderSettings


def test_defaults_bind_only_to_loopback() -> None:
    settings = AppSettings()
    assert settings.bind_host == "127.0.0.1"
    assert settings.environment == "local"
    assert settings.run_context_provider == "AUTO"
    assert settings.bundle_path.is_file()
    assert settings.worker_bind_host == "127.0.0.1"
    assert settings.worker_bind_port == 8010
    assert settings.rabbitmq_queue_name == "zhixiaohe.agent.image"
    assert settings.rabbitmq_routing_key is None
    assert settings.otel_enabled is False
    assert settings.otel_otlp_endpoint is None
    assert settings.otel_metrics_enabled is False
    assert settings.otel_metrics_otlp_endpoint is None
    assert settings.otel_sample_ratio == 0.1
    assert settings.langfuse_export_enabled is False
    assert settings.langfuse_export_collector_configured is False
    assert is_full_git_commit(settings.git_commit)


def test_otel_settings_are_prefixed_bounded_and_credential_free(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("GUANGHE_AGENT_OTEL_ENABLED", "true")
    monkeypatch.setenv(
        "GUANGHE_AGENT_OTEL_OTLP_ENDPOINT",
        "http://127.0.0.1:4318/v1/traces",
    )
    monkeypatch.setenv("GUANGHE_AGENT_OTEL_SAMPLE_RATIO", "1")
    monkeypatch.setenv("GUANGHE_AGENT_OTEL_METRICS_ENABLED", "true")
    monkeypatch.setenv(
        "GUANGHE_AGENT_OTEL_METRICS_OTLP_ENDPOINT",
        "http://127.0.0.1:4318/v1/metrics",
    )
    settings = AppSettings()
    assert settings.otel_enabled is True
    assert settings.otel_otlp_endpoint == "http://127.0.0.1:4318/v1/traces"
    assert settings.otel_sample_ratio == 1
    assert settings.otel_metrics_enabled is True
    assert settings.otel_metrics_otlp_endpoint == "http://127.0.0.1:4318/v1/metrics"

    enabled = AppSettings(
        langfuse_export_enabled=True,
        langfuse_export_collector_configured=True,
    )
    assert enabled.langfuse_export_enabled is True
    assert enabled.langfuse_export_collector_configured is True

    with pytest.raises(ValidationError, match="credential-free"):
        AppSettings(otel_otlp_endpoint="https://user:secret@collector.invalid/v1/traces")
    with pytest.raises(ValidationError, match="batch size"):
        AppSettings(
            otel_batch_max_queue_size=128,
            otel_batch_max_export_batch_size=129,
        )
    with pytest.raises(ValidationError, match="requires export to be enabled"):
        AppSettings(langfuse_export_collector_configured=True)


def test_provider_secret_is_masked() -> None:
    provider = ProviderSettings(credential=SecretStr("never-log-this"), model="model-id")
    assert "never-log-this" not in repr(provider)
    assert provider.credential is not None
    assert provider.credential.get_secret_value() == "never-log-this"


@pytest.mark.parametrize(
    "mode",
    ("DEEPSEEK_PRIMARY", "QWEN_SHADOW", "QWEN_PRIMARY"),
)
def test_intent_router_uses_independent_prefixed_configuration(
    mode: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("GUANGHE_AGENT_INTENT_ROUTER_MODE", mode)
    monkeypatch.setenv("GUANGHE_AGENT_INTENT_ROUTER_QWEN__CREDENTIAL", "router-only-secret")
    monkeypatch.setenv("GUANGHE_AGENT_INTENT_ROUTER_QWEN__MODEL", "trained-router-32b")
    monkeypatch.setenv(
        "GUANGHE_AGENT_INTENT_ROUTER_QWEN__ENDPOINT",
        "https://router-bailian.unit.test/compatible-mode/v1",
    )

    settings = AppSettings()

    assert settings.intent_router_mode == mode
    assert settings.intent_router_qwen.model == "trained-router-32b"
    assert settings.intent_router_qwen.credential is not None
    assert settings.qwen.credential is None
    assert settings.qwen_image.credential is None
    assert "router-only-secret" not in repr(settings)


def test_rabbitmq_url_is_masked_and_uses_prefixed_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv(
        "GUANGHE_AGENT_RABBITMQ_URL",
        "amqps://synthetic-user:synthetic-password@rabbit.unit.test/vhost",
    )
    monkeypatch.setenv("GUANGHE_AGENT_RABBITMQ_QUEUE_NAME", "zhixiaohe.agent.image")
    monkeypatch.setenv("GUANGHE_AGENT_RABBITMQ_PREFETCH_COUNT", "2")
    monkeypatch.setenv("GUANGHE_AGENT_RABBITMQ_CONNECTION_TIMEOUT_SECONDS", "4")
    monkeypatch.setenv("GUANGHE_AGENT_WORKER_BROKER", "RABBITMQ")
    monkeypatch.setenv("GUANGHE_AGENT_WORKER_CONSUMER_NAME", "image-worker-v1")

    settings = AppSettings()

    assert settings.worker_broker == "RABBITMQ"
    assert settings.worker_consumer_name == "image-worker-v1"
    assert settings.rabbitmq_url is not None
    assert settings.rabbitmq_url.get_secret_value().startswith("amqps://synthetic-user:")
    assert settings.rabbitmq_queue_name == "zhixiaohe.agent.image"
    assert settings.rabbitmq_prefetch_count == 2
    assert settings.rabbitmq_connection_timeout_seconds == 4
    assert "synthetic-password" not in repr(settings)


@pytest.mark.parametrize(
    "url",
    (
        "http://rabbit.unit.test",
        "amqp:///missing-host",
        "amqp://rabbit.unit.test/vhost#fragment",
    ),
)
def test_invalid_rabbitmq_urls_are_rejected(url: str) -> None:
    with pytest.raises(ValidationError, match="RabbitMQ URL"):
        AppSettings(rabbitmq_url=SecretStr(url))


def test_rabbitmq_queue_and_routing_key_are_limited_to_java_owned_routes() -> None:
    assert (
        AppSettings.model_validate(
            {"rabbitmq_queue_name": "zhixiaohe.agent.video"}
        ).rabbitmq_queue_name
        == "zhixiaohe.agent.video"
    )
    geo = AppSettings.model_validate(
        {
            "rabbitmq_queue_name": "zhixiaohe.agent.geo",
            "rabbitmq_routing_key": "agent.geo",
        }
    )
    assert geo.rabbitmq_queue_name == "zhixiaohe.agent.geo"
    assert geo.rabbitmq_routing_key == "agent.geo"
    article = AppSettings.model_validate(
        {
            "rabbitmq_queue_name": "zhixiaohe.agent.article",
            "rabbitmq_routing_key": "agent.article",
        }
    )
    assert article.rabbitmq_queue_name == "zhixiaohe.agent.article"
    assert article.rabbitmq_routing_key == "agent.article"
    with pytest.raises(ValidationError, match=r"zhixiaohe\.agent\.(image|video|geo|article)"):
        AppSettings.model_validate({"rabbitmq_queue_name": "agent.image"})


def test_empty_provider_values_remain_not_configured() -> None:
    provider = ProviderSettings.model_validate({"credential": "  ", "model": ""})
    assert provider.credential is None
    assert provider.model is None


def test_m1_settings_use_only_prefixed_environment_variables(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    values = {
        "GUANGHE_AGENT_JAVA_INTERNAL_BASE_URL": "http://127.0.0.1:8080",
        "GUANGHE_AGENT_RUN_CONTEXT_PROVIDER": "JAVA",
        "GUANGHE_AGENT_INTERNAL_HMAC_KEY_ID": "synthetic-key-id",
        "GUANGHE_AGENT_INTERNAL_HMAC_SECRET": "synthetic-secret-32-bytes-long!!",
        "GUANGHE_AGENT_INTERNAL_HMAC_ALLOWED_CLOCK_SKEW_SECONDS": "60",
        "GUANGHE_AGENT_INTERNAL_HMAC_NONCE_TTL_SECONDS": "120",
        "GUANGHE_AGENT_INTERNAL_HMAC_MAX_BODY_BYTES": "1048576",
        "GUANGHE_AGENT_REDIS_HOST": "redis.unit.test",
        "GUANGHE_AGENT_REDIS_PORT": "6380",
        "GUANGHE_AGENT_REDIS_USERNAME": "synthetic-user",
        "GUANGHE_AGENT_REDIS_PASSWORD": "synthetic-password",
        "GUANGHE_AGENT_REDIS_SSL_ENABLED": "true",
        "GUANGHE_AGENT_REDIS_CA_CERT_PATH": "C:/synthetic/test-ca.pem",
        "GUANGHE_AGENT_REDIS_KEY_PREFIX": "zhixiaohe:test:unit:",
    }
    for name, value in values.items():
        monkeypatch.setenv(name, value)

    settings = AppSettings()

    assert settings.java_internal_base_url == "http://127.0.0.1:8080"
    assert settings.run_context_provider == "JAVA"
    assert settings.internal_hmac_key_id == "synthetic-key-id"
    assert settings.internal_hmac_secret is not None
    assert (
        settings.internal_hmac_secret.get_secret_value()
        == values["GUANGHE_AGENT_INTERNAL_HMAC_SECRET"]
    )
    assert settings.internal_hmac_allowed_clock_skew_seconds == 60
    assert settings.internal_hmac_nonce_ttl_seconds == 120
    assert settings.internal_hmac_max_body_bytes == 1_048_576
    assert settings.redis_host == "redis.unit.test"
    assert settings.redis_port == 6380
    assert settings.redis_username == "synthetic-user"
    assert settings.redis_password is not None
    assert settings.redis_password.get_secret_value() == "synthetic-password"
    assert settings.redis_ssl_enabled is True
    assert settings.redis_ca_cert_path == Path("C:/synthetic/test-ca.pem")
    assert settings.redis_key_prefix == "zhixiaohe:test:unit:"
    assert "synthetic-password" not in repr(settings)


def test_empty_redis_ca_cert_path_is_not_current_directory(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("GUANGHE_AGENT_REDIS_CA_CERT_PATH", "   ")

    assert AppSettings().redis_ca_cert_path is None


def test_invalid_run_context_provider_is_rejected() -> None:
    with pytest.raises(ValidationError):
        AppSettings.model_validate({"run_context_provider": "REMOTE"})


def test_nonce_ttl_must_cover_full_accepted_clock_window() -> None:
    with pytest.raises(ValidationError, match="nonce TTL must cover"):
        AppSettings(
            internal_hmac_allowed_clock_skew_seconds=61,
            internal_hmac_nonce_ttl_seconds=120,
        )


def test_internal_hmac_secret_must_not_be_blank_or_short() -> None:
    with pytest.raises(ValidationError, match="must not be blank"):
        AppSettings(internal_hmac_secret=SecretStr(" " * 32))
    with pytest.raises(ValidationError, match="at least 32 UTF-8 bytes"):
        AppSettings(internal_hmac_secret=SecretStr("synthetic-short-secret"))


def test_provider_example_uses_mock_and_unverified_placeholders() -> None:
    example_path = Path(__file__).resolve().parents[2] / ".env.example"
    values = {
        name: value
        for line in example_path.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
        for name, value in (line.split("=", maxsplit=1),)
    }

    assert values["GUANGHE_AGENT_WORKER_PROVIDER"] == "MOCK"
    for provider in ("DEEPSEEK", "QWEN", "QWEN_IMAGE", "WAN_VIDEO"):
        assert values[f"GUANGHE_AGENT_{provider}__CREDENTIAL"] == ""
        assert values[f"GUANGHE_AGENT_{provider}__MODEL"].startswith("<")
        assert values[f"GUANGHE_AGENT_{provider}__ENDPOINT"].startswith("<")
