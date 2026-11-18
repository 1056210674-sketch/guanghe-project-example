"""Safe response models for internal system endpoints."""

from typing import Literal

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from app.common.enums import ProviderStatus, TaskType
from app.observability.tracing import LangfuseExportStatus


class ApiModel(BaseModel):
    """Serialize Python names as lowerCamelCase at the HTTP boundary."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ReadinessCheck(ApiModel):
    name: str
    status: Literal["READY", "NOT_READY", "DISABLED", "NOT_CONFIGURED", "DEGRADED"]
    reason_code: str | None = None


class ReadinessResponse(ApiModel):
    status: Literal["READY", "NOT_READY"]
    service: str
    service_version: str
    checks: tuple[ReadinessCheck, ...]


class ProviderCapability(ApiModel):
    code: str
    status: ProviderStatus
    model: str | None = None


class ModelGatewayCapability(ApiModel):
    mode: Literal["MOCK", "REAL", "UNAVAILABLE"]
    provider: str | None
    model: str | None
    status: ProviderStatus
    reason_code: str | None = None


class IntentRouterCapability(ApiModel):
    router_provider: str
    router_model: str | None
    router_mode: Literal["NATIVE_LOOP", "DEEPSEEK_PRIMARY", "QWEN_SHADOW", "QWEN_PRIMARY"]
    available: bool
    schema_version: str
    status: ProviderStatus
    reason_code: str | None = None


class LangfuseExportCapability(ApiModel):
    status: LangfuseExportStatus
    reason_code: str | None = None


class ProfileCapability(ApiModel):
    profile_code: str
    profile_version: str
    task_types: tuple[TaskType, ...]


class CapabilitiesResponse(ApiModel):
    service_version: str
    bundle_version: str
    bundle_hash: str
    prompt_hash: str
    git_commit: str
    model_gateway: ModelGatewayCapability
    intent_router: IntentRouterCapability
    langfuse_export: LangfuseExportCapability
    supported_profiles: tuple[ProfileCapability, ...]
    providers: tuple[ProviderCapability, ...]


class InternalErrorResponse(ApiModel):
    code: str
    message: str
