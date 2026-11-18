"""Liveness, readiness, and capability routes."""

from fastapi import APIRouter, Request, Response, status
from fastapi.responses import JSONResponse

from app.agent.run_manager import ChatRunManager
from app.api.models import (
    CapabilitiesResponse,
    IntentRouterCapability,
    InternalErrorResponse,
    LangfuseExportCapability,
    ModelGatewayCapability,
    ProfileCapability,
    ProviderCapability,
    ReadinessCheck,
    ReadinessResponse,
)
from app.common.enums import ProviderStatus
from app.common.runtime import RuntimeState
from app.common.settings import ProviderSettings
from app.observability.tracing import resolve_langfuse_export_state
from app.providers.java_context import RunContextProvider
from app.security.nonce import InternalNonceStore

router = APIRouter(tags=["system"])


def _runtime(request: Request) -> RuntimeState:
    runtime: RuntimeState = request.app.state.runtime
    return runtime


def _provider_status(provider: ProviderSettings) -> ProviderStatus:
    if not provider.enabled:
        return ProviderStatus.DISABLED
    if provider.credential is None:
        return ProviderStatus.NOT_CONFIGURED
    if provider.model is None:
        return ProviderStatus.MODEL_NOT_CONFIGURED
    return ProviderStatus.CONFIGURED


def _provider_capability(
    code: str,
    provider: ProviderSettings,
    manager: ChatRunManager | None,
) -> ProviderCapability:
    provider_status = _provider_status(provider)
    if manager is not None and manager.gateway_provider == code:
        provider_status = manager.gateway_status
    return ProviderCapability(
        code=code,
        status=provider_status,
        model=provider.model,
    )


@router.get("/health", operation_id="getAgentLiveness")
async def health_check() -> dict[str, str]:
    """Process liveness; this does not claim Provider readiness."""
    return {"status": "ok", "service": "guanghe-agent"}


@router.get(
    "/internal/v1/health/ready",
    response_model=ReadinessResponse,
    operation_id="getAgentReadiness",
    responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ReadinessResponse}},
)
async def readiness(request: Request, response: Response) -> ReadinessResponse:
    """Report safe readiness without exposing configuration or file paths."""
    runtime = _runtime(request)
    bundle_ready = runtime.bundle is not None
    build_commit_ready = runtime.git_commit is not None
    manager: ChatRunManager | None = request.app.state.run_manager
    gateway_ready = manager is not None and manager.gateway_status is ProviderStatus.AVAILABLE
    router_ready = manager is not None and manager.router_status is ProviderStatus.AVAILABLE
    context_provider: RunContextProvider = request.app.state.run_context_provider
    context_ready = context_provider.is_ready
    secret = runtime.settings.internal_hmac_secret
    internal_hmac_configured = (
        runtime.settings.internal_hmac_key_id is not None
        and secret is not None
        and len(secret.get_secret_value().encode("utf-8")) >= 32
    )
    nonce_store: InternalNonceStore = request.app.state.internal_nonce_store
    nonce_store_ready = internal_hmac_configured and await nonce_store.is_available()
    internal_auth_ready = internal_hmac_configured and nonce_store_ready
    langfuse_export = resolve_langfuse_export_state(
        runtime.settings,
        request.app.state.tracing_runtime,
    )
    overall_ready = (
        bundle_ready
        and build_commit_ready
        and gateway_ready
        and router_ready
        and context_ready
        and internal_auth_ready
    )
    if not overall_ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return ReadinessResponse(
        status="READY" if overall_ready else "NOT_READY",
        service=runtime.settings.service_name,
        service_version=runtime.settings.service_version,
        checks=(
            ReadinessCheck(
                name="promptBundle",
                status="READY" if bundle_ready else "NOT_READY",
                reason_code=runtime.bundle_error_code,
            ),
            ReadinessCheck(
                name="buildCommit",
                status="READY" if build_commit_ready else "NOT_READY",
                reason_code=None if build_commit_ready else "BUILD_COMMIT_NOT_READY",
            ),
            ReadinessCheck(
                name="modelGateway",
                status="READY" if gateway_ready else "NOT_READY",
                reason_code=(
                    None
                    if gateway_ready
                    else (
                        manager.gateway_error_code
                        if manager is not None
                        else request.app.state.gateway_error_code
                    )
                ),
            ),
            ReadinessCheck(
                name="intentRouter",
                status="READY" if router_ready else "NOT_READY",
                reason_code=(
                    None
                    if router_ready
                    else (
                        manager.router_error_code
                        if manager is not None
                        else "INTENT_ROUTER_NOT_READY"
                    )
                ),
            ),
            ReadinessCheck(
                name="authorizedContext",
                status="READY" if context_ready else "NOT_READY",
                reason_code=(
                    None
                    if context_ready
                    else (context_provider.reason_code or "AUTHORIZED_CONTEXT_NOT_READY")
                ),
            ),
            ReadinessCheck(
                name="internalAuthentication",
                status="READY" if internal_auth_ready else "NOT_READY",
                reason_code=(
                    None
                    if internal_auth_ready
                    else (
                        "INTERNAL_HMAC_NOT_CONFIGURED"
                        if not internal_hmac_configured
                        else "INTERNAL_NONCE_STORE_UNAVAILABLE"
                    )
                ),
            ),
            ReadinessCheck(
                name="langfuseExport",
                status=langfuse_export.status.value,
                reason_code=langfuse_export.reason_code,
            ),
        ),
    )


@router.get(
    "/internal/v1/capabilities",
    response_model=CapabilitiesResponse,
    operation_id="getAgentCapabilities",
    responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": InternalErrorResponse}},
)
async def capabilities(request: Request) -> CapabilitiesResponse | JSONResponse:
    """Describe only validated Bundle and explicitly configured Provider capabilities."""
    runtime = _runtime(request)
    if runtime.git_commit is None:
        error = InternalErrorResponse(
            code="BUILD_COMMIT_NOT_READY",
            message="Build commit metadata is not ready",
        )
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content=error.model_dump(mode="json", by_alias=True),
        )
    if runtime.bundle is None:
        error = InternalErrorResponse(
            code="AGENT_BUNDLE_NOT_READY",
            message="Agent capability bundle is not ready",
        )
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content=error.model_dump(mode="json", by_alias=True),
        )

    settings = runtime.settings
    git_commit = runtime.git_commit
    assert git_commit is not None
    loaded = runtime.bundle
    langfuse_export = resolve_langfuse_export_state(
        settings,
        request.app.state.tracing_runtime,
    )
    manager: ChatRunManager | None = request.app.state.run_manager
    gateway_error_code: str | None = request.app.state.gateway_error_code
    selected_provider: str | None = None
    selected_model: str | None = None
    if manager is None:
        selected_provider = (
            settings.model_gateway if settings.model_gateway in {"DEEPSEEK", "QWEN"} else None
        )
        selected_model = (
            settings.deepseek.model
            if selected_provider == "DEEPSEEK"
            else settings.qwen.model
            if selected_provider == "QWEN"
            else None
        )
        gateway_capability = ModelGatewayCapability(
            mode="UNAVAILABLE",
            provider=selected_provider,
            model=selected_model,
            status=ProviderStatus.UNAVAILABLE,
            reason_code=gateway_error_code,
        )
    else:
        gateway_capability = ModelGatewayCapability(
            mode="MOCK" if manager.is_mock else "REAL",
            provider=manager.gateway_provider,
            model=manager.gateway_model,
            status=manager.gateway_status,
            reason_code=manager.gateway_error_code,
        )
    if manager is None:
        router_capability = IntentRouterCapability(
            router_provider=selected_provider or "DEEPSEEK",
            router_model=selected_model,
            router_mode="NATIVE_LOOP",
            available=False,
            schema_version="native-tool-loop-v1",
            status=ProviderStatus.UNAVAILABLE,
            reason_code=gateway_error_code or "INTENT_ROUTER_NOT_READY",
        )
    else:
        router_state = manager.router_capability
        router_capability = IntentRouterCapability(
            router_provider=router_state.provider,
            router_model=router_state.model,
            router_mode=router_state.mode.value,
            available=router_state.available,
            schema_version=router_state.schema_version,
            status=router_state.status,
            reason_code=router_state.reason_code,
        )
    return CapabilitiesResponse(
        service_version=settings.service_version,
        bundle_version=loaded.bundle.bundle_version,
        bundle_hash=loaded.bundle_hash,
        prompt_hash=loaded.prompt_hash,
        git_commit=git_commit,
        model_gateway=gateway_capability,
        intent_router=router_capability,
        langfuse_export=LangfuseExportCapability(
            status=langfuse_export.status,
            reason_code=langfuse_export.reason_code,
        ),
        supported_profiles=tuple(
            ProfileCapability(
                profile_code=profile.code,
                profile_version=profile.version,
                task_types=profile.task_types,
            )
            for profile in loaded.bundle.profiles
        ),
        providers=(
            _provider_capability("DEEPSEEK", settings.deepseek, manager),
            _provider_capability("QWEN", settings.qwen, manager),
            _provider_capability(
                "BAILIAN_QWEN_ROUTER",
                settings.intent_router_qwen,
                None,
            ),
            _provider_capability("QWEN_IMAGE", settings.qwen_image, manager),
            _provider_capability("WAN_VIDEO", settings.wan_video, manager),
        ),
    )
