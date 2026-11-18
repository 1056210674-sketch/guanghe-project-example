"""Independent FastAPI process for local Worker execution and diagnostics."""

from collections.abc import AsyncGenerator, Callable
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Request, Response, status
from fastapi.responses import FileResponse
from pydantic import JsonValue

from app import __version__
from app.article.runtime import ArticleExecutionError
from app.article.worker import ArticleWorkerBackend, build_article_worker_backend
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings, get_settings
from app.geo.runtime import GeoSynthesisError
from app.geo.worker import GeoWorkerBackend, build_geo_worker_backend
from app.model_gateway.gateway import ModelGatewayError
from app.observability.logging import configure_logging
from app.observability.tracing import (
    configure_tracing,
    resolve_langfuse_export_state,
    shutdown_tracing,
)
from app.providers import build_task_provider
from app.providers.models import TaskProviderAdapter, TaskProviderError
from app.security.internal_hmac import InternalHmacMiddleware
from app.security.nonce import (
    InternalNonceStore,
    build_internal_nonce_store,
)
from app.workers.control_plane import (
    ControlPlaneConfigurationError,
    LocalWorkerControlPlane,
    WorkerControlPlane,
)
from app.workers.java_control_plane import build_worker_control_plane
from app.workers.models import (
    DevWorkerTaskAccepted,
    DevWorkerTaskRequest,
    TaskExecutionMessage,
    WorkerStatusSnapshot,
    WorkerTaskSnapshot,
)
from app.workers.runtime import WorkerNotReadyError, WorkerRuntime
from app.workers.transport import (
    LocalMessageBroker,
    MessageBroker,
    MessageBrokerConfigurationError,
    build_message_broker,
)
from app.workers.workflow_control_plane import (
    WorkflowControlPlane,
    build_workflow_control_plane,
)
from app.workers.workflow_runtime import WorkflowWorkerRuntime

ProviderFactory = Callable[[AppSettings], TaskProviderAdapter]
GeoBackendFactory = Callable[[AppSettings], GeoWorkerBackend]
ArticleBackendFactory = Callable[[AppSettings], ArticleWorkerBackend]
ControlPlaneFactory = Callable[[AppSettings], WorkerControlPlane]
BrokerFactory = Callable[[AppSettings], MessageBroker]
WorkflowControlPlaneFactory = Callable[[AppSettings], WorkflowControlPlane]


def create_worker_app(
    settings: AppSettings | None = None,
    *,
    provider_factory: ProviderFactory = build_task_provider,
    geo_backend_factory: GeoBackendFactory = build_geo_worker_backend,
    article_backend_factory: ArticleBackendFactory = build_article_worker_backend,
    control_plane_factory: ControlPlaneFactory = build_worker_control_plane,
    broker_factory: BrokerFactory = build_message_broker,
    workflow_control_plane_factory: WorkflowControlPlaneFactory = build_workflow_control_plane,
    internal_nonce_store: InternalNonceStore | None = None,
) -> FastAPI:
    """Create a Worker process without importing or starting the Chat API."""
    resolved = settings or get_settings()
    resolved_nonce_store = (
        internal_nonce_store
        if internal_nonce_store is not None
        else build_internal_nonce_store(resolved)
    )
    console_path = Path(__file__).resolve().parent / "static" / "worker-console.html"

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncGenerator[None]:
        configure_logging(resolved.log_level)
        tracing_runtime = configure_tracing(
            resolved,
            process_role=f"{resolved.worker_task_type.lower()}-worker",
        )
        application.state.tracing_runtime = tracing_runtime
        runtime = initialize_runtime(resolved)
        application.state.runtime = runtime
        application.state.worker_console_path = console_path
        worker: WorkerRuntime | WorkflowWorkerRuntime | None = None
        error_code: str | None = None
        if runtime.bundle is None:
            error_code = runtime.bundle_error_code or "AGENT_BUNDLE_NOT_READY"
        elif resolved.environment == "production" and (
            resolved.worker_control_plane == "LOCAL"
            or resolved.worker_broker == "LOCAL"
            or resolved.worker_provider == "MOCK"
        ):
            error_code = "LOCAL_WORKER_COMPONENTS_FORBIDDEN_IN_PRODUCTION"
        elif resolved.worker_broker == "RABBITMQ" and resolved.worker_control_plane != "JAVA":
            error_code = "RABBITMQ_REQUIRES_JAVA_CONTROL_PLANE"
        elif resolved.worker_control_plane == "JAVA" and resolved.worker_task_type not in {
            "IMAGE",
            "VIDEO",
            "GEO",
            "ARTICLE",
            "WORKFLOW",
        }:
            error_code = "JAVA_CONTROL_PLANE_REQUIRES_SINGLE_MEDIA_TASK_TYPE"
        elif resolved.worker_broker == "RABBITMQ" and resolved.rabbitmq_queue_name != {
            "IMAGE": "zhixiaohe.agent.image",
            "VIDEO": "zhixiaohe.agent.video",
            "GEO": "zhixiaohe.agent.geo",
            "ARTICLE": "zhixiaohe.agent.article",
            "WORKFLOW": "zhixiaohe.agent.workflow",
        }.get(resolved.worker_task_type):
            error_code = "RABBITMQ_QUEUE_TASK_TYPE_MISMATCH"
        elif resolved.worker_broker == "RABBITMQ" and (
            (
                resolved.worker_task_type in {"GEO", "ARTICLE", "WORKFLOW"}
                and resolved.rabbitmq_routing_key
                != {
                    "GEO": "agent.geo",
                    "ARTICLE": "agent.article",
                    "WORKFLOW": "agent.workflow",
                }[resolved.worker_task_type]
            )
            or (
                resolved.rabbitmq_routing_key is not None
                and resolved.rabbitmq_routing_key
                != {
                    "IMAGE": "agent.image",
                    "VIDEO": "agent.video",
                    "GEO": "agent.geo",
                    "ARTICLE": "agent.article",
                    "WORKFLOW": "agent.workflow",
                }.get(resolved.worker_task_type)
            )
        ):
            error_code = "RABBITMQ_ROUTING_KEY_TASK_TYPE_MISMATCH"
        elif (
            resolved.worker_control_plane == "JAVA"
            and resolved.worker_task_type == "GEO"
            and resolved.worker_provider != "REAL"
        ):
            error_code = "JAVA_GEO_WORKER_REQUIRES_REAL_PROVIDER"
        elif (
            resolved.worker_control_plane == "JAVA"
            and resolved.worker_task_type == "ARTICLE"
            and (resolved.worker_provider != "REAL" or resolved.model_gateway != "DEEPSEEK")
        ):
            error_code = "JAVA_ARTICLE_WORKER_REQUIRES_DEEPSEEK"
        elif (
            resolved.worker_control_plane == "JAVA"
            and resolved.worker_provider == "REAL"
            and resolved.worker_task_type == "IMAGE"
            and resolved.qwen_image.model != "qwen-image-3.0"
        ):
            error_code = "JAVA_REAL_IMAGE_REQUIRES_QWEN_IMAGE_3_0"
        elif (
            resolved.worker_control_plane == "JAVA"
            and resolved.worker_provider == "REAL"
            and resolved.worker_task_type == "VIDEO"
            and resolved.wan_video.model != "wan3.0-video"
        ):
            error_code = "JAVA_REAL_VIDEO_REQUIRES_WAN_3_0_VIDEO"
        else:
            if resolved.worker_task_type == "WORKFLOW":
                workflow_control: WorkflowControlPlane | None = None
                try:
                    workflow_control = workflow_control_plane_factory(resolved)
                    workflow_broker = broker_factory(resolved)
                    worker = WorkflowWorkerRuntime(
                        settings=resolved,
                        bundle_version=runtime.bundle.bundle.bundle_version,
                        control_plane=workflow_control,
                        broker=workflow_broker,
                    )
                    if not await worker.start():
                        error_code = worker.status_snapshot().reason_code
                except (ControlPlaneConfigurationError, MessageBrokerConfigurationError) as exc:
                    error_code = getattr(exc, "code", "WORKFLOW_WORKER_CONFIGURATION_INVALID")
                    if workflow_control is not None:
                        await workflow_control.aclose()
                application.state.worker_runtime = worker
                application.state.worker_error_code = error_code
                try:
                    yield
                finally:
                    if worker is not None:
                        await worker.close()
                    await resolved_nonce_store.aclose()
                    shutdown_tracing(tracing_runtime)
                return
            provider: TaskProviderAdapter | None = None
            geo_backend: GeoWorkerBackend | None = None
            article_backend: ArticleWorkerBackend | None = None
            try:
                if resolved.worker_task_type == "GEO" and resolved.worker_provider == "REAL":
                    geo_backend = geo_backend_factory(resolved)
                elif resolved.worker_task_type == "ARTICLE" and resolved.worker_provider == "REAL":
                    article_backend = article_backend_factory(resolved)
                else:
                    provider = provider_factory(resolved)
            except TaskProviderError as exc:
                error_code = exc.code
            except (ArticleExecutionError, GeoSynthesisError, ModelGatewayError, ValueError) as exc:
                error_code = getattr(exc, "code", "WORKER_CONFIGURATION_INVALID")
            else:
                try:
                    control_plane = (
                        control_plane_factory(resolved)
                        if resolved.worker_control_plane == "JAVA"
                        else LocalWorkerControlPlane()
                    )
                except ControlPlaneConfigurationError as exc:
                    error_code = exc.code
                    backend = article_backend or geo_backend or provider
                    if backend is not None:
                        await backend.aclose()
                else:
                    try:
                        broker = broker_factory(resolved)
                    except MessageBrokerConfigurationError as exc:
                        error_code = exc.code
                        await control_plane.aclose()
                        backend = article_backend or geo_backend or provider
                        if backend is not None:
                            await backend.aclose()
                        broker = None
                    if (
                        broker is not None
                        and resolved.environment == "production"
                        and (
                            (backend := article_backend or geo_backend or provider) is None
                            or backend.is_mock
                            or isinstance(control_plane, LocalWorkerControlPlane)
                            or isinstance(broker, LocalMessageBroker)
                        )
                    ):
                        error_code = "LOCAL_WORKER_COMPONENTS_FORBIDDEN_IN_PRODUCTION"
                        await broker.aclose()
                        await control_plane.aclose()
                        backend = article_backend or geo_backend or provider
                        if backend is not None:
                            await backend.aclose()
                    elif broker is not None:
                        worker = WorkerRuntime(
                            runtime,
                            provider=provider,
                            geo_backend=geo_backend,
                            article_backend=article_backend,
                            control_plane=control_plane,
                            broker=broker,
                        )
                        if not await worker.start():
                            error_code = worker.status_snapshot().reason_code
        application.state.worker_runtime = worker
        application.state.worker_error_code = error_code
        try:
            yield
        finally:
            current: WorkerRuntime | WorkflowWorkerRuntime | None = application.state.worker_runtime
            try:
                if current is not None:
                    await current.close()
            finally:
                try:
                    await resolved_nonce_store.aclose()
                finally:
                    shutdown_tracing(tracing_runtime)

    application = FastAPI(
        title="Guanghe Agent Worker",
        version=__version__,
        lifespan=lifespan,
    )
    application.state.internal_nonce_store = resolved_nonce_store

    @application.get("/health", tags=["worker-system"])
    async def health() -> dict[str, str]:  # pyright: ignore[reportUnusedFunction]
        return {"status": "ok", "service": f"{resolved.service_name}-worker"}

    @application.get("/internal/v1/health/ready", tags=["worker-system"])
    async def readiness(  # pyright: ignore[reportUnusedFunction]
        request: Request, response: Response
    ) -> dict[str, object]:
        worker = _worker_or_none(request)
        worker_snapshot = worker.status_snapshot() if worker is not None else None
        runtime = request.app.state.runtime
        build_commit_ready = runtime.git_commit is not None
        secret = resolved.internal_hmac_secret
        internal_hmac_configured = (
            resolved.internal_hmac_key_id is not None
            and secret is not None
            and len(secret.get_secret_value().encode("utf-8")) >= 32
        )
        nonce_store_ready = internal_hmac_configured and await resolved_nonce_store.is_available()
        internal_auth_ready = internal_hmac_configured and nonce_store_ready
        langfuse_export = resolve_langfuse_export_state(
            resolved,
            request.app.state.tracing_runtime,
        )
        ready = (
            worker is not None and worker.is_ready and build_commit_ready and internal_auth_ready
        )
        if not ready:
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return {
            "status": "READY" if ready else "NOT_READY",
            "service": f"{resolved.service_name}-worker",
            "serviceVersion": resolved.service_version,
            "gitCommit": runtime.git_commit if build_commit_ready else "UNAVAILABLE",
            "checks": [
                {
                    "name": "workerRuntime",
                    "status": ("READY" if worker is not None and worker.is_ready else "NOT_READY"),
                    "reasonCode": (
                        None
                        if worker is not None and worker.is_ready
                        else (
                            worker_snapshot.reason_code
                            if worker_snapshot is not None
                            else request.app.state.worker_error_code
                        )
                    ),
                },
                {
                    "name": "buildCommit",
                    "status": "READY" if build_commit_ready else "NOT_READY",
                    "reasonCode": None if build_commit_ready else "BUILD_COMMIT_NOT_READY",
                },
                {
                    "name": "internalAuthentication",
                    "status": "READY" if internal_auth_ready else "NOT_READY",
                    "reasonCode": (
                        None
                        if internal_auth_ready
                        else (
                            "INTERNAL_HMAC_NOT_CONFIGURED"
                            if not internal_hmac_configured
                            else "INTERNAL_NONCE_STORE_UNAVAILABLE"
                        )
                    ),
                },
                {
                    "name": "langfuseExport",
                    "status": langfuse_export.status.value,
                    "reasonCode": langfuse_export.reason_code,
                },
            ],
        }

    @application.get(
        "/internal/v1/worker/status",
        response_model=WorkerStatusSnapshot,
        tags=["worker-system"],
    )
    async def worker_status(  # pyright: ignore[reportUnusedFunction]
        request: Request,
    ) -> WorkerStatusSnapshot:
        worker = _worker_or_none(request)
        if worker is not None:
            return worker.status_snapshot()
        return WorkerStatusSnapshot(
            status="NOT_READY",
            service=f"{resolved.service_name}-worker",
            service_version=resolved.service_version,
            worker_id=resolved.worker_id,
            task_type=resolved.worker_task_type,
            control_plane=resolved.worker_control_plane,
            provider="UNAVAILABLE",
            provider_model="UNAVAILABLE",
            mock_mode=resolved.worker_provider == "MOCK",
            accepting=False,
            queue_depth=0,
            in_flight=0,
            processed=0,
            failed=0,
            reason_code=request.app.state.worker_error_code,
        )

    @application.get("/dev/worker", include_in_schema=False)
    async def worker_console(  # pyright: ignore[reportUnusedFunction]
        request: Request,
    ) -> FileResponse:
        _ensure_dev_enabled(request)
        return FileResponse(request.app.state.worker_console_path, media_type="text/html")

    @application.post(
        "/dev/v1/tasks",
        response_model=DevWorkerTaskAccepted,
        status_code=status.HTTP_202_ACCEPTED,
        tags=["worker-local-development"],
    )
    async def submit_task(  # pyright: ignore[reportUnusedFunction]
        payload: DevWorkerTaskRequest,
        request: Request,
    ) -> DevWorkerTaskAccepted:
        _ensure_dev_enabled(request)
        worker = _ready_worker(request)
        event_id = str(uuid4().int)
        task_id = str(uuid4().int)
        message = TaskExecutionMessage(
            schema_version=1,
            event_id=event_id,
            event_type="TASK_EXECUTION_REQUESTED",
            task_id=task_id,
            task_type=payload.task_type,
            user_id="1",
            space_id="1",
            attempt=1,
            traceparent=f"00-{uuid4().hex}-{uuid4().hex[:16]}-01",
            created_at=datetime.now(UTC),
        )
        try:
            local_parameters: dict[str, JsonValue] = dict(payload.parameters)
            snapshot = await worker.submit_local(
                message,
                local_parameters,
            )
        except WorkerNotReadyError as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail={"code": exc.code, "message": "Worker is not ready"},
            ) from exc
        return DevWorkerTaskAccepted(
            event_id=event_id,
            task_id=task_id,
            status=snapshot.status,
            status_url=f"/dev/v1/tasks/{event_id}",
        )

    @application.get(
        "/dev/v1/tasks/{event_id}",
        response_model=WorkerTaskSnapshot,
        tags=["worker-local-development"],
    )
    async def task_status(  # pyright: ignore[reportUnusedFunction]
        event_id: str, request: Request
    ) -> WorkerTaskSnapshot:
        _ensure_dev_enabled(request)
        snapshot = _ready_worker(request).get_task(event_id)
        if snapshot is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")
        return snapshot

    application.add_middleware(
        InternalHmacMiddleware,
        settings=resolved,
        nonce_store=resolved_nonce_store,
    )
    return application


def _worker_or_none(request: Request) -> WorkerRuntime | WorkflowWorkerRuntime | None:
    worker: WorkerRuntime | WorkflowWorkerRuntime | None = request.app.state.worker_runtime
    return worker


def _ready_worker(request: Request) -> WorkerRuntime:
    worker = _worker_or_none(request)
    if worker is None or not worker.is_ready:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": request.app.state.worker_error_code or "WORKER_NOT_READY",
                "message": "Worker runtime is not ready",
            },
        )
    if not isinstance(worker, WorkerRuntime):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")
    return worker


def _ensure_dev_enabled(request: Request) -> None:
    if not request.app.state.runtime.settings.dev_console_enabled:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")
