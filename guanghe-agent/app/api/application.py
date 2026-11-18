"""FastAPI application factory."""

import asyncio
from collections.abc import AsyncGenerator, Callable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import cast

from fastapi import FastAPI

from app import __version__
from app.agent.run_manager import ChatRunManager
from app.api.openapi import configure_internal_openapi
from app.api.routes.chat_runs import router as chat_runs_router
from app.api.routes.dev import router as dev_router
from app.api.routes.geo_diagnostic_analyses import router as geo_diagnostic_analyses_router
from app.api.routes.geo_diagnostic_preparations import router as geo_diagnostic_preparations_router
from app.api.routes.geo_period_report_summaries import router as geo_period_report_summaries_router
from app.api.routes.geo_syntheses import router as geo_syntheses_router
from app.api.routes.geo_visual_plans import router as geo_visual_plans_router
from app.api.routes.internal_probe import router as internal_probe_router
from app.api.routes.system import router as system_router
from app.common.enums import TaskType
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings, get_settings
from app.integration.java_internal import (
    JavaInternalClientProtocol,
    JavaWorkflowPlanClientProtocol,
    build_java_internal_client,
)
from app.model_gateway import build_model_gateway
from app.model_gateway.gateway import ModelGateway, ModelGatewayError
from app.observability.logging import configure_logging
from app.observability.tracing import configure_tracing, shutdown_tracing
from app.prompts.assembly import CommercePromptSet
from app.providers.java_context import build_run_context_provider
from app.security.internal_hmac import InternalHmacMiddleware
from app.security.nonce import (
    InternalNonceStore,
    build_internal_nonce_store,
)

JavaClientFactory = Callable[[AppSettings], JavaInternalClientProtocol]


def create_app(
    settings: AppSettings | None = None,
    *,
    gateway_factory: Callable[[AppSettings], ModelGateway] = build_model_gateway,
    internal_nonce_store: InternalNonceStore | None = None,
    java_client_factory: JavaClientFactory = build_java_internal_client,
) -> FastAPI:
    """Create an API instance without opening external connections on import."""
    resolved_settings = settings or get_settings()
    resolved_nonce_store = (
        internal_nonce_store
        if internal_nonce_store is not None
        else build_internal_nonce_store(resolved_settings)
    )
    dev_console_path = Path(__file__).resolve().parent / "static" / "agent-console.html"

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncGenerator[None]:
        configure_logging(resolved_settings.log_level)
        tracing_runtime = configure_tracing(resolved_settings, process_role="api")
        application.state.tracing_runtime = tracing_runtime
        runtime = initialize_runtime(resolved_settings)
        application.state.runtime = runtime
        application.state.geo_synthesis_prompt = (
            CommercePromptSet.from_bundle(
                runtime.bundle.bundle, runtime.bundle.bundle.profiles[0]
            ).for_execution(TaskType.GEO)
            if runtime.bundle is not None
            else None
        )
        java_client = java_client_factory(resolved_settings)
        application.state.java_internal_client = java_client
        context_provider = build_run_context_provider(resolved_settings, java_client)
        application.state.run_context_provider = context_provider
        manager: ChatRunManager | None = None
        gateway: ModelGateway | None = None
        gateway_error_code: str | None = None
        if runtime.bundle is not None:
            try:
                gateway = gateway_factory(resolved_settings)
                manager = ChatRunManager(
                    runtime,
                    gateway,
                    context_provider,
                    cast(JavaWorkflowPlanClientProtocol, java_client),
                )
                await manager.initialize()
            except ModelGatewayError as exc:
                gateway_error_code = exc.code
        application.state.run_manager = manager
        application.state.geo_visual_gateway = gateway
        application.state.geo_diagnostic_search = (
            manager.web_search if manager is not None else None
        )
        application.state.gateway_error_code = gateway_error_code
        application.state.dev_console_path = dev_console_path
        try:
            yield
        finally:
            try:
                manager = application.state.run_manager
                if manager is not None:
                    await manager.close()
            finally:
                try:
                    await java_client.aclose()
                finally:
                    try:
                        await resolved_nonce_store.aclose()
                    finally:
                        shutdown_tracing(tracing_runtime)

    application = FastAPI(
        title="Guanghe Agent Internal API",
        version=__version__,
        description="Internal loopback-only API for the bounded Python Agent service.",
        servers=[{"url": "http://127.0.0.1:8000"}],
        lifespan=lifespan,
    )
    application.state.internal_nonce_store = resolved_nonce_store
    application.state.geo_visual_semaphore = asyncio.Semaphore(2)
    application.state.geo_visual_gateway = None
    application.state.geo_diagnostic_search = None
    application.include_router(system_router)
    application.include_router(chat_runs_router)
    application.include_router(geo_visual_plans_router)
    application.include_router(geo_syntheses_router)
    application.include_router(geo_period_report_summaries_router)
    application.include_router(geo_diagnostic_preparations_router)
    application.include_router(geo_diagnostic_analyses_router)
    application.include_router(internal_probe_router)
    application.include_router(dev_router)
    configure_internal_openapi(application)
    application.add_middleware(
        InternalHmacMiddleware,
        settings=resolved_settings,
        nonce_store=resolved_nonce_store,
    )
    return application
