"""Environment-only application configuration."""

from functools import lru_cache
from pathlib import Path
from typing import Literal, Self, cast
from urllib.parse import urlsplit

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    SecretStr,
    ValidationInfo,
    field_validator,
    model_validator,
)
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.common.build_info import discover_git_commit


def default_bundle_path() -> Path:
    return (
        Path(__file__).resolve().parents[1] / "prompts" / "bundles" / "commerce-assistant-v1.yaml"
    )


class ProviderSettings(BaseModel):
    """Generic Provider configuration; secrets remain masked by Pydantic."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    enabled: bool = True
    credential: SecretStr | None = None
    model: str | None = None
    endpoint: str | None = None
    timeout_seconds: float = Field(default=30.0, gt=0, le=120)
    probe_timeout_seconds: float = Field(default=5.0, gt=0, le=30)
    max_retries: int = Field(default=1, ge=0, le=3)
    trust_env: bool = False

    @field_validator("credential", "model", "endpoint", mode="before")
    @classmethod
    def normalize_empty_values(cls, value: object) -> object:
        if isinstance(value, str):
            stripped = value.strip()
            return stripped or None
        return value


class GeoDoubaoPolicySettings(BaseModel):
    """GEO-only transport policy; the provider's timeout_seconds is its read limit."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    connect_timeout_seconds: float = Field(default=5.0, gt=0, le=30)
    pool_timeout_seconds: float = Field(default=5.0, gt=0, le=30)
    write_timeout_seconds: float = Field(default=10.0, gt=0, le=60)
    request_timeout_seconds: float = Field(default=150.0, gt=0, le=300)
    max_concurrency: int = Field(default=2, ge=1, le=20)
    retry_base_seconds: float = Field(default=15.0, ge=0, le=60)
    retry_max_seconds: float = Field(default=30.0, ge=0, le=120)
    max_retry_wait_seconds: float = Field(default=120.0, gt=0, le=300)

    @model_validator(mode="after")
    def validate_retry_range(self) -> Self:
        if not self.retry_base_seconds <= self.retry_max_seconds <= self.max_retry_wait_seconds:
            raise ValueError("GEO Doubao retry intervals must fit the wait budget")
        return self


class ProviderMockSettings(BaseModel):
    """Deterministic task Provider used only by local/test Worker processes."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    enabled: bool = True
    latency_ms: int = Field(default=25, ge=0, le=10_000)
    failure_mode: Literal[
        "NONE",
        "AUTH",
        "RATE_LIMIT",
        "UPSTREAM_5XX",
        "TIMEOUT",
        "INVALID_RESPONSE",
        "SAFETY_REJECTED",
        "UNKNOWN_SUBMIT",
    ] = "NONE"
    artifact_ttl_seconds: int = Field(default=3_600, ge=60, le=86_400)


class AppSettings(BaseSettings):
    """Settings read only from controlled environment variables."""

    model_config = SettingsConfigDict(
        env_prefix="GUANGHE_AGENT_",
        env_nested_delimiter="__",
        extra="ignore",
        frozen=True,
    )

    service_name: str = "guanghe-agent"
    service_version: str = "0.1.0"
    environment: Literal["local", "test", "production"] = "local"
    model_gateway: Literal["AUTO", "LOCAL", "DEEPSEEK", "QWEN"] = "AUTO"
    intent_router_mode: Literal[
        "DEEPSEEK_PRIMARY",
        "QWEN_SHADOW",
        "QWEN_PRIMARY",
    ] = "DEEPSEEK_PRIMARY"
    intent_router_shadow_timeout_seconds: float = Field(default=5.0, gt=0, le=30)
    run_context_provider: Literal["AUTO", "LOCAL", "JAVA"] = "AUTO"
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = "INFO"
    otel_enabled: bool = False
    otel_otlp_endpoint: str | None = None
    otel_metrics_enabled: bool = False
    otel_metrics_otlp_endpoint: str | None = None
    otel_metrics_export_interval_ms: int = Field(default=5_000, ge=1_000, le=60_000)
    otel_otlp_protocol: Literal["http/protobuf"] = "http/protobuf"
    otel_sample_ratio: float = Field(default=0.1, ge=0.0, le=1.0)
    otel_export_timeout_seconds: float = Field(default=5.0, gt=0, le=30)
    otel_batch_max_queue_size: int = Field(default=2_048, ge=128, le=65_536)
    otel_batch_max_export_batch_size: int = Field(default=512, ge=1, le=8_192)
    otel_batch_schedule_delay_ms: int = Field(default=5_000, ge=100, le=60_000)
    langfuse_export_enabled: bool = False
    langfuse_export_collector_configured: bool = False
    bind_host: Literal["127.0.0.1", "::1"] = "127.0.0.1"
    bind_port: int = Field(default=8000, ge=1, le=65535)
    worker_bind_host: Literal["127.0.0.1", "::1"] = "127.0.0.1"
    worker_bind_port: int = Field(default=8010, ge=1, le=65535)
    worker_id: str = Field(default="local-agent-worker-01", min_length=1, max_length=128)
    worker_consumer_name: str = Field(
        default="local-agent-worker-v1",
        min_length=1,
        max_length=128,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$",
    )
    worker_task_type: Literal[
        "ALL", "GEO", "IMAGE", "VIDEO", "ARTICLE", "IMAGE_VIDEO", "WORKFLOW"
    ] = "ALL"
    worker_broker: Literal["LOCAL", "RABBITMQ"] = "LOCAL"
    worker_control_plane: Literal["LOCAL", "JAVA"] = "LOCAL"
    worker_provider: Literal["MOCK", "REAL"] = "MOCK"
    worker_max_concurrency: int = Field(default=4, ge=1, le=32)
    worker_graceful_timeout_seconds: float = Field(default=30.0, gt=0, le=300)
    worker_provider_max_attempts: int = Field(default=3, ge=1, le=5)
    worker_provider_retry_base_seconds: float = Field(default=0.1, ge=0, le=10)
    worker_provider_retry_max_seconds: float = Field(default=2.0, ge=0, le=60)
    worker_provider_retry_budget_seconds: float = Field(default=10.0, gt=0, le=300)
    enable_dev_console: bool = True
    mock_event_delay_ms: int = Field(default=80, ge=0, le=2_000)
    max_local_runs: int = Field(default=100, ge=1, le=1_000)
    business_outcome_enabled: bool = False
    resource_lookup_enabled: bool = False
    workflow_plan_max_point_cost: int = Field(default=1_000, ge=1, le=1_000_000)
    workflow_plan_deadline_seconds: int = Field(default=86_400, ge=120, le=2_592_000)
    git_commit: str | None = Field(default_factory=discover_git_commit)
    bundle_path: Path = Field(default_factory=default_bundle_path)

    java_internal_base_url: str | None = None
    internal_hmac_key_id: str | None = Field(default=None, max_length=128)
    internal_hmac_secret: SecretStr | None = None
    internal_hmac_allowed_clock_skew_seconds: int = Field(default=60, ge=1, le=300)
    internal_hmac_nonce_ttl_seconds: int = Field(default=120, ge=120, le=3_600)
    internal_hmac_max_body_bytes: int = Field(default=1_048_576, ge=1, le=16_777_216)
    redis_host: str | None = Field(default=None, max_length=253)
    redis_port: int = Field(default=6379, ge=1, le=65_535)
    redis_username: str | None = Field(default=None, max_length=128)
    redis_password: SecretStr | None = None
    redis_ssl_enabled: bool = False
    redis_ca_cert_path: Path | None = None
    redis_key_prefix: str | None = Field(default=None, max_length=256)
    rabbitmq_url: SecretStr | None = None
    rabbitmq_queue_name: Literal[
        "zhixiaohe.agent.image",
        "zhixiaohe.agent.video",
        "zhixiaohe.agent.geo",
        "zhixiaohe.agent.article",
        "zhixiaohe.agent.workflow",
    ] = "zhixiaohe.agent.image"
    rabbitmq_routing_key: (
        Literal[
            "agent.image",
            "agent.video",
            "agent.geo",
            "agent.article",
            "agent.workflow",
        ]
        | None
    ) = None
    rabbitmq_prefetch_count: int = Field(default=1, ge=1, le=32)
    rabbitmq_connection_timeout_seconds: float = Field(default=5.0, gt=0, le=30)

    deepseek: ProviderSettings = Field(default_factory=ProviderSettings)
    qwen: ProviderSettings = Field(default_factory=ProviderSettings)
    intent_router_qwen: ProviderSettings = Field(default_factory=ProviderSettings)
    qwen_image: ProviderSettings = Field(default_factory=ProviderSettings)
    wan_image_edit: ProviderSettings = Field(
        default_factory=lambda: ProviderSettings(enabled=False, model="wanx2.1-imageedit")
    )
    wan_video: ProviderSettings = Field(default_factory=ProviderSettings)
    geo_doubao: ProviderSettings = Field(
        default_factory=lambda: ProviderSettings(timeout_seconds=120.0)
    )
    geo_doubao_policy: GeoDoubaoPolicySettings = Field(default_factory=GeoDoubaoPolicySettings)
    geo_bocha: ProviderSettings = Field(default_factory=ProviderSettings)
    geo_deepseek: ProviderSettings = Field(default_factory=ProviderSettings)
    geo_qwen: ProviderSettings = Field(default_factory=ProviderSettings)
    geo_wenxin: ProviderSettings = Field(default_factory=ProviderSettings)
    geo_kimi: ProviderSettings = Field(default_factory=ProviderSettings)
    geo_yuanbao: ProviderSettings = Field(default_factory=ProviderSettings)
    geo_tencent_search: ProviderSettings = Field(default_factory=ProviderSettings)
    geo_zhipu: ProviderSettings = Field(default_factory=ProviderSettings)
    # Retained only for backward-compatible configuration parsing.
    volcano_image: ProviderSettings = Field(default_factory=ProviderSettings)
    volcano_video: ProviderSettings = Field(default_factory=ProviderSettings)
    provider_mock: ProviderMockSettings = Field(default_factory=ProviderMockSettings)

    @field_validator("geo_doubao", mode="before")
    @classmethod
    def default_geo_doubao_read_timeout(cls, value: object) -> object:
        # Nested environment dictionaries bypass the field default_factory.
        # Preserve explicit legacy overrides; only the GEO Doubao default changes.
        if isinstance(value, dict):
            return {"timeout_seconds": 120.0, **cast(dict[str, object], value)}
        if isinstance(value, ProviderSettings) and "timeout_seconds" not in value.model_fields_set:
            return value.model_copy(update={"timeout_seconds": 120.0})
        return value

    @field_validator(
        "internal_hmac_key_id",
        "git_commit",
        "redis_host",
        "redis_username",
        "redis_key_prefix",
        "rabbitmq_queue_name",
        "rabbitmq_routing_key",
        mode="before",
    )
    @classmethod
    def normalize_optional_text(cls, value: object) -> object:
        if isinstance(value, str):
            stripped = value.strip()
            return stripped or None
        return value

    @field_validator(
        "internal_hmac_secret",
        "redis_password",
        "rabbitmq_url",
        mode="before",
    )
    @classmethod
    def normalize_optional_secrets(cls, value: object) -> object:
        if isinstance(value, str):
            return value if value else None
        return value

    @field_validator("rabbitmq_url")
    @classmethod
    def validate_rabbitmq_url(cls, value: SecretStr | None) -> SecretStr | None:
        if value is None:
            return None
        parsed = urlsplit(value.get_secret_value())
        if parsed.scheme not in {"amqp", "amqps"} or parsed.hostname is None or parsed.fragment:
            raise ValueError("RabbitMQ URL must be an amqp/amqps URL with a host")
        return value

    @field_validator("redis_ca_cert_path", mode="before")
    @classmethod
    def normalize_optional_path(cls, value: object) -> object:
        if isinstance(value, str):
            stripped = value.strip()
            return stripped or None
        return value

    @field_validator("java_internal_base_url", mode="before")
    @classmethod
    def validate_java_internal_base_url(cls, value: object) -> object:
        if value is None:
            return None
        if not isinstance(value, str):
            return value
        normalized = value.strip().rstrip("/")
        if not normalized:
            return None
        parsed = urlsplit(normalized)
        if (
            parsed.scheme not in {"http", "https"}
            or parsed.hostname not in {"127.0.0.1", "::1", "localhost"}
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
            or parsed.path not in {"", "/"}
        ):
            raise ValueError("Java internal base URL must be a credential-free loopback HTTP URL")
        return normalized

    @field_validator("otel_otlp_endpoint", "otel_metrics_otlp_endpoint", mode="before")
    @classmethod
    def validate_otel_otlp_endpoint(cls, value: object) -> object:
        if value is None:
            return None
        if not isinstance(value, str):
            return value
        normalized = value.strip().rstrip("/")
        if not normalized:
            return None
        parsed = urlsplit(normalized)
        if (
            parsed.scheme not in {"http", "https"}
            or parsed.hostname is None
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("OTLP endpoint must be a credential-free HTTP URL")
        return normalized

    @field_validator("redis_key_prefix")
    @classmethod
    def validate_redis_key_prefix(
        cls,
        value: str | None,
        info: ValidationInfo,
    ) -> str | None:
        if value is not None and not value.startswith("zhixiaohe:"):
            raise ValueError("Redis key prefix must be namespaced for zhixiaohe")
        if (
            value is not None
            and info.data.get("environment") == "test"
            and not value.startswith("zhixiaohe:test:")
        ):
            raise ValueError("Test Redis key prefix must start with zhixiaohe:test:")
        return value

    @model_validator(mode="after")
    def validate_nonce_ttl_covers_clock_window(self) -> Self:
        if self.internal_hmac_secret is not None:
            secret = self.internal_hmac_secret.get_secret_value()
            if not secret.strip():
                raise ValueError("Internal HMAC secret must not be blank")
            if len(secret.encode("utf-8")) < 32:
                raise ValueError("Internal HMAC secret must contain at least 32 UTF-8 bytes")
        if self.internal_hmac_nonce_ttl_seconds < 2 * self.internal_hmac_allowed_clock_skew_seconds:
            raise ValueError("Internal HMAC nonce TTL must cover the full accepted clock window")
        if self.otel_batch_max_export_batch_size > self.otel_batch_max_queue_size:
            raise ValueError("OTel export batch size must not exceed queue size")
        if self.langfuse_export_collector_configured and not self.langfuse_export_enabled:
            raise ValueError("Langfuse Collector configuration requires export to be enabled")
        return self

    @property
    def dev_console_enabled(self) -> bool:
        return self.environment != "production" and self.enable_dev_console


@lru_cache(maxsize=1)
def get_settings() -> AppSettings:
    return AppSettings()
