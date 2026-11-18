"""Independent bounded Worker runtime with Claim-before-ACK semantics."""

import asyncio
import logging
import random
from collections import OrderedDict
from contextlib import nullcontext, suppress
from datetime import UTC, datetime, timedelta
from time import monotonic
from typing import Literal, cast
from uuid import uuid4

from pydantic import JsonValue

from app.article.worker import ArticleWorkerBackend, ArticleWorkerControlPlane
from app.article.worker_models import ArticleExecutionContext
from app.common.enums import TaskType
from app.common.runtime import RuntimeState
from app.creative.geo_visual_plan import validated_frozen_plan
from app.creative.models import CreativeSourceMode
from app.geo.worker import GeoWorkerBackend, GeoWorkerControlPlane
from app.geo.worker_models import GeoExecutionContext
from app.harness.runtime import bind_provider_call_boundary
from app.observability.metrics import adjust_worker_inflight, record_worker_task
from app.observability.trace import parse_traceparent
from app.observability.tracing import safe_ref, start_span
from app.providers.models import (
    ProviderArtifact,
    ProviderErrorCategory,
    ResumableTaskProviderAdapter,
    SubmissionState,
    TaskProviderAdapter,
    TaskProviderCapability,
    TaskProviderContinuation,
    TaskProviderError,
    TaskProviderRequest,
    TaskProviderResult,
)
from app.providers.router import RoutedTaskProvider
from app.workers.control_plane import (
    ClaimDisposition,
    ControlPlaneRejected,
    ControlPlaneUnavailable,
    ExecutionContext,
    LocalWorkerControlPlane,
    WorkerControlPlane,
)
from app.workers.lease import ExecutionLeaseMonitor
from app.workers.models import (
    TaskExecutionMessage,
    WorkerStatusSnapshot,
    WorkerTaskSnapshot,
    WorkerTaskStatus,
)
from app.workers.schema_validation import (
    RabbitMessageSchemaInvalid,
    ensure_task_execution_schema_ready,
    parse_task_execution_message,
)
from app.workers.transport import (
    LocalMessageBroker,
    MessageBroker,
    MessageBrokerUnavailable,
    RawDelivery,
    settle_poison_delivery,
)

logger = logging.getLogger(__name__)

_REFERENCE_URL_MINIMUM_TTL = timedelta(minutes=20)


class WorkerNotReadyError(RuntimeError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class WorkerRuntime:
    """Consume messages independently and drain in-flight tasks on shutdown."""

    def __init__(
        self,
        runtime: RuntimeState,
        *,
        provider: TaskProviderAdapter | None,
        geo_backend: GeoWorkerBackend | None = None,
        article_backend: ArticleWorkerBackend | None = None,
        control_plane: WorkerControlPlane,
        broker: MessageBroker,
        heartbeat_interval_seconds: float = 30.0,
        dependency_recovery_base_seconds: float = 1.0,
        dependency_recovery_max_seconds: float = 30.0,
    ) -> None:
        if heartbeat_interval_seconds <= 0:
            raise ValueError("Worker heartbeat interval must be positive")
        if not 0 < dependency_recovery_base_seconds <= dependency_recovery_max_seconds:
            raise ValueError("Worker dependency recovery interval is invalid")
        if sum(item is not None for item in (provider, geo_backend, article_backend)) != 1:
            raise ValueError("Worker requires exactly one execution backend")
        self._runtime = runtime
        self._provider = provider
        self._geo_backend = geo_backend
        self._article_backend = article_backend
        if article_backend is not None:
            self._execution_backend: (
                TaskProviderAdapter | GeoWorkerBackend | ArticleWorkerBackend
            ) = article_backend
        elif geo_backend is not None:
            self._execution_backend = geo_backend
        elif provider is not None:
            self._execution_backend = provider
        else:  # pragma: no cover - guarded above
            raise ValueError("Worker execution backend is missing")
        self._control_plane = control_plane
        self._broker = broker
        self._status: Literal["STARTING", "READY", "DRAINING", "STOPPED", "NOT_READY"] = "STARTING"
        self._reason_code: str | None = None
        self._consumer_task: asyncio.Task[None] | None = None
        self._in_flight: set[asyncio.Task[None]] = set()
        self._snapshots: OrderedDict[str, WorkerTaskSnapshot] = OrderedDict()
        self._processed = 0
        self._failed = 0
        self._semaphore = asyncio.Semaphore(runtime.settings.worker_max_concurrency)
        self._start_lock = asyncio.Lock()
        self._control_plane_recovery_lock = asyncio.Lock()
        self._heartbeat_interval_seconds = heartbeat_interval_seconds
        self._dependency_recovery_base_seconds = dependency_recovery_base_seconds
        self._dependency_recovery_max_seconds = dependency_recovery_max_seconds
        self._dependency_recovery_task: asyncio.Task[None] | None = None
        self._dependency_generation = 0

    def _record_processed(self, task_type: TaskType | str | None = None) -> None:
        self._processed += 1
        record_worker_task("PROCESSED", task_type=self._metric_task_type(task_type))

    def _record_failed(self, task_type: TaskType | str | None = None) -> None:
        self._failed += 1
        record_worker_task("FAILED", task_type=self._metric_task_type(task_type))

    def _metric_task_type(self, task_type: TaskType | str | None) -> str:
        if isinstance(task_type, TaskType):
            return task_type.value
        return task_type or self._runtime.settings.worker_task_type

    @property
    def is_ready(self) -> bool:
        return self._status == "READY"

    async def start(self) -> bool:
        async with self._start_lock:
            if self._status == "READY":
                return True
            if self._status != "STARTING":
                return False
            return await self._start_once()

    async def _start_once(self) -> bool:
        if self._runtime.bundle is None:
            self._status = "NOT_READY"
            self._reason_code = self._runtime.bundle_error_code or "AGENT_BUNDLE_NOT_READY"
            return False
        try:
            ensure_task_execution_schema_ready()
        except RuntimeError:
            self._status = "NOT_READY"
            self._reason_code = "RABBIT_CONTRACT_SCHEMA_NOT_READY"
            return False
        try:
            control_plane_ready = await self._control_plane.probe()
        except (ControlPlaneUnavailable, ControlPlaneRejected) as exc:
            self._status = "NOT_READY"
            self._reason_code = exc.code
            self._ensure_dependency_recovery()
            return False
        if not control_plane_ready:
            self._status = "NOT_READY"
            self._reason_code = "JAVA_CONTROL_PLANE_UNAVAILABLE"
            self._ensure_dependency_recovery()
            return False
        if not await self._execution_backend.probe():
            self._status = "NOT_READY"
            self._reason_code = "TASK_PROVIDER_UNAVAILABLE"
            self._ensure_dependency_recovery()
            return False
        try:
            await self._broker.start()
        except MessageBrokerUnavailable:
            self._status = "NOT_READY"
            self._reason_code = "RABBITMQ_UNAVAILABLE"
            self._ensure_dependency_recovery()
            return False
        self._status = "READY"
        self._consumer_task = asyncio.create_task(self._consume(), name="worker-consumer")
        return True

    async def submit_local(
        self,
        message: TaskExecutionMessage,
        parameters: dict[str, JsonValue],
    ) -> WorkerTaskSnapshot:
        if not self.is_ready or not self._broker.accepting:
            raise WorkerNotReadyError(self._reason_code or "WORKER_NOT_READY")
        if not isinstance(self._control_plane, LocalWorkerControlPlane):
            raise WorkerNotReadyError("LOCAL_CONTROL_PLANE_REQUIRED")
        if not isinstance(self._broker, LocalMessageBroker):
            raise WorkerNotReadyError("LOCAL_BROKER_REQUIRED")
        self._control_plane.register_context(message.task_id, message.task_type, parameters)
        delivery = await self._broker.publish(message.model_dump_json(by_alias=True).encode())
        snapshot = WorkerTaskSnapshot(
            event_id=message.event_id,
            task_id=message.task_id,
            task_type=message.task_type,
            status=WorkerTaskStatus.QUEUED,
            delivery_state=delivery.state,
            updated_at=datetime.now(UTC),
        )
        self._put_snapshot(snapshot)
        return snapshot

    async def process_delivery(self, delivery: RawDelivery) -> None:
        """Restore the Rabbit parent or link a redelivery before running existing logic."""
        try:
            message = parse_task_execution_message(delivery.body)
        except RabbitMessageSchemaInvalid:
            message = None
        parent = None if delivery.redelivered or message is None else message.traceparent
        link = message.traceparent if delivery.redelivered and message is not None else None
        attributes: dict[str, str | bool | int] = {
            "rabbit.redelivered": delivery.redelivered,
        }
        if message is not None:
            attributes.update(
                {
                    "task.ref": safe_ref(message.task_id),
                    "execution.attempt": message.attempt,
                    "task.type": message.task_type.value,
                }
            )
        with start_span(
            "rabbit.consume",
            parent_traceparent=parent,
            link_traceparent=link,
            attributes=attributes,
        ) as span:
            await self._process_delivery_impl(delivery)
            span.set("rabbit.deliveryState", delivery.state)
            if delivery.state in {"NACKED", "REJECTED"}:
                span.event(
                    "rabbit.delivery.settled",
                    {"state": delivery.state, "requeue": delivery.requeue},
                )

    async def _process_delivery_impl(self, delivery: RawDelivery) -> None:
        """Process one raw delivery; public for contract and failure-path tests."""
        try:
            message = parse_task_execution_message(delivery.body)
        except RabbitMessageSchemaInvalid:
            await delivery.reject()
            self._record_failed()
            self._put_snapshot(
                WorkerTaskSnapshot(
                    event_id=delivery.delivery_id,
                    status=WorkerTaskStatus.REJECTED,
                    delivery_state=delivery.state,
                    error_code="RABBIT_MESSAGE_SCHEMA_INVALID",
                    updated_at=datetime.now(UTC),
                )
            )
            return
        snapshot = self._snapshot_for(message, WorkerTaskStatus.CLAIMING, delivery)
        if message.task_type is TaskType.MEDIA:
            await delivery.reject()
            self._record_failed(message.task_type)
            self._update_snapshot(
                snapshot,
                status=WorkerTaskStatus.REJECTED,
                delivery_state=delivery.state,
                error_code="MEDIA_ROUTE_FORBIDDEN",
            )
            return
        configured_type = self._runtime.settings.worker_task_type
        matches_configured_type = (
            configured_type == "ALL"
            or message.task_type.value == configured_type
            or (
                configured_type == "IMAGE_VIDEO"
                and message.task_type in {TaskType.IMAGE, TaskType.VIDEO}
            )
        )
        if not matches_configured_type:
            await delivery.reject()
            self._record_failed(message.task_type)
            self._update_snapshot(
                snapshot,
                status=WorkerTaskStatus.REJECTED,
                delivery_state=delivery.state,
                error_code="WORKER_TASK_TYPE_MISMATCH",
            )
            return
        bundle = self._runtime.bundle
        if bundle is None:
            await delivery.nack(requeue=True)
            self._record_failed(message.task_type)
            self._update_snapshot(
                snapshot,
                status=WorkerTaskStatus.FAILED,
                delivery_state=delivery.state,
                error_code="AGENT_BUNDLE_NOT_READY",
            )
            return
        try:
            with start_span(
                "worker.claim",
                attributes={
                    "task.ref": safe_ref(message.task_id),
                    "execution.attempt": message.attempt,
                },
            ) as span:
                try:
                    claim = await self._control_plane.claim(
                        message,
                        consumer_name=self._runtime.settings.worker_consumer_name,
                        worker_id=self._runtime.settings.worker_id,
                        bundle_version=bundle.bundle.bundle_version,
                    )
                except (ControlPlaneUnavailable, ControlPlaneRejected) as exc:
                    span.fail(exc.code)
                    raise
                span.set("claim.disposition", claim.disposition.value)
        except ControlPlaneUnavailable as exc:
            await delivery.nack(requeue=True)
            self._record_failed(message.task_type)
            self._update_snapshot(
                snapshot,
                status=WorkerTaskStatus.FAILED,
                delivery_state=delivery.state,
                error_code=exc.code,
            )
            await self._stop_for_control_plane(exc.code)
            return
        except ControlPlaneRejected as exc:
            await delivery.reject()
            self._record_failed(message.task_type)
            self._update_snapshot(
                snapshot,
                status=WorkerTaskStatus.REJECTED,
                delivery_state=delivery.state,
                error_code=exc.code,
            )
            return
        if claim.disposition is not ClaimDisposition.CLAIMED:
            await delivery.ack()
            self._record_processed(message.task_type)
            self._update_snapshot(
                snapshot,
                status=WorkerTaskStatus.DUPLICATE,
                delivery_state=delivery.state,
            )
            return
        execution_no = claim.execution_no
        execution_token = claim.execution_token
        if execution_no is None or execution_token is None:
            await delivery.nack(requeue=True)
            self._record_failed(message.task_type)
            self._update_snapshot(
                snapshot,
                status=WorkerTaskStatus.FAILED,
                delivery_state=delivery.state,
                error_code="CLAIM_RESPONSE_INVALID",
            )
            return
        await delivery.ack()
        self._update_snapshot(
            snapshot,
            status=WorkerTaskStatus.RUNNING,
            delivery_state=delivery.state,
            execution_no=execution_no,
        )
        lease_monitor: ExecutionLeaseMonitor | None = None
        selected_provider = self._provider
        execution_phase = "CONTEXT_LOAD"
        provider_call_started = False
        provider_result: TaskProviderResult | None = None
        continuation: TaskProviderContinuation | None = None
        try:
            with start_span(
                "worker.context.load",
                attributes={
                    "task.ref": safe_ref(message.task_id),
                    "executionNo": execution_no,
                },
            ) as span:
                try:
                    context = await self._control_plane.get_execution_context(
                        message.task_id,
                        execution_no=execution_no,
                        execution_token=execution_token,
                        traceparent=message.traceparent,
                    )
                except (ControlPlaneUnavailable, ControlPlaneRejected) as exc:
                    span.fail(exc.code)
                    raise
                span.set("context.source", "JAVA")
            if context.task_id != message.task_id:
                raise WorkerNotReadyError("EXECUTION_CONTEXT_TASK_ID_MISMATCH")
            if context.execution_no != execution_no:
                raise WorkerNotReadyError("EXECUTION_CONTEXT_EXECUTION_NO_MISMATCH")
            if isinstance(context, GeoExecutionContext):
                if message.task_type is not TaskType.GEO or self._geo_backend is None:
                    raise WorkerNotReadyError("EXECUTION_CONTEXT_TASK_TYPE_MISMATCH")
                execution_phase = "GEO_EXECUTION"
                outcome = await self._geo_backend.execute(
                    message,
                    context,
                    execution_token=execution_token,
                    control_plane=cast(GeoWorkerControlPlane, self._control_plane),
                    heartbeat_interval_seconds=self._heartbeat_interval_seconds,
                )
                if outcome.succeeded:
                    self._record_processed(message.task_type)
                    self._update_snapshot(
                        snapshot,
                        status=(
                            WorkerTaskStatus.HANDOFF_ACCEPTED
                            if context.mode == "DIAGNOSTIC_RUN"
                            else WorkerTaskStatus.RESULT_READY
                        ),
                        provider=self._geo_backend.code,
                        provider_model=(
                            "deterministic-geo-diagnostic-sampling-v1"
                            if context.mode == "DIAGNOSTIC_RUN"
                            else self._geo_backend.model
                        ),
                        artifact_count=0,
                    )
                else:
                    self._record_failed(message.task_type)
                    self._update_snapshot(
                        snapshot,
                        status=WorkerTaskStatus.FAILED,
                        error_code=outcome.stable_error_code or "GEO_EXECUTION_FAILED",
                    )
                return
            if isinstance(context, ArticleExecutionContext):
                if message.task_type is not TaskType.ARTICLE or self._article_backend is None:
                    raise WorkerNotReadyError("EXECUTION_CONTEXT_TASK_TYPE_MISMATCH")
                execution_phase = "ARTICLE_EXECUTION"
                outcome = await self._article_backend.execute(
                    message,
                    context,
                    execution_token=execution_token,
                    control_plane=cast(ArticleWorkerControlPlane, self._control_plane),
                    heartbeat_interval_seconds=self._heartbeat_interval_seconds,
                )
                if outcome.succeeded:
                    self._record_processed(message.task_type)
                    self._update_snapshot(
                        snapshot,
                        status=WorkerTaskStatus.RESULT_READY,
                        provider=self._article_backend.code,
                        provider_model=self._article_backend.model,
                        artifact_count=1,
                    )
                else:
                    self._record_failed(message.task_type)
                    self._update_snapshot(
                        snapshot,
                        status=WorkerTaskStatus.FAILED,
                        error_code=outcome.stable_error_code or "ARTICLE_EXECUTION_FAILED",
                    )
                return
            if context.task_type is not message.task_type:
                raise WorkerNotReadyError("EXECUTION_CONTEXT_TASK_TYPE_MISMATCH")
            if self._provider is None:
                raise WorkerNotReadyError("MEDIA_PROVIDER_NOT_CONFIGURED")
            selected_provider = (
                self._provider.resolve(
                    message.task_type,
                    context.creative_brief.source_mode
                    if context.creative_brief is not None
                    else None,
                )
                if isinstance(self._provider, RoutedTaskProvider)
                else self._provider
            )
            # Explicit local/mock workers consume Java-shaped fixtures without
            # impersonating a live provider. Real adapters must match authority.
            if not selected_provider.is_mock and (
                (context.provider is not None and context.provider != selected_provider.code)
                or (context.model is not None and context.model != selected_provider.model)
            ):
                raise WorkerNotReadyError("PROVIDER_CONTEXT_IDENTITY_MISMATCH")
            if message.task_type is TaskType.VIDEO and execution_no > 1 and not context.resume_only:
                raise WorkerNotReadyError("VIDEO_RETRY_CONTINUATION_REQUIRED")
            if not self._provider.is_mock or context.creative_brief is not None:
                with start_span(
                    "creative.grounding",
                    attributes={
                        "task.type": message.task_type.value,
                        "creative.referenceAssetCount": len(context.reference_assets),
                    },
                ) as span:
                    try:
                        self._enforce_creative_mode(
                            message.task_type, context, provider=selected_provider
                        )
                    except TaskProviderError as exc:
                        span.fail(exc.code)
                        raise
                    brief = context.creative_brief
                    source_mode = "UNKNOWN" if brief is None else brief.source_mode.value
                    if brief is not None:
                        span.set("creative.sourceMode", source_mode)
                    for reference in context.reference_assets:
                        span.event(
                            "creative.reference.asset",
                            {
                                "asset.ref": safe_ref(reference.asset_id),
                                "asset.sourceMode": source_mode,
                            },
                        )
            self._enforce_request_budget(
                message.task_type,
                context.parameters,
                max_images=context.max_images,
                max_videos=context.max_videos,
                max_video_seconds=context.max_video_seconds,
            )
            stage_code = (
                "PROVIDER_RESULT_RECOVERY" if context.resume_only else "PROVIDER_GENERATING"
            )
            execution_phase = "MEDIA_EXECUTION"
            await self._control_plane.heartbeat(
                message.task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                stage_code=stage_code,
                traceparent=message.traceparent,
            )
            await self._control_plane.report_progress(
                message.task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                stage_code=stage_code,
                traceparent=message.traceparent,
            )
            lease_monitor = ExecutionLeaseMonitor(
                lambda: self._renew_execution_lease(
                    message.task_id,
                    execution_no=execution_no,
                    execution_token=execution_token,
                    stage_code=stage_code,
                    traceparent=message.traceparent,
                ),
                interval_seconds=self._heartbeat_interval_seconds,
            )
            lease_monitor.start(name=f"worker-heartbeat-{message.task_id}")
            continuation = context.provider_continuation
            provider_request = TaskProviderRequest(
                request_id=(
                    continuation.provider_request_id
                    if context.resume_only and continuation is not None
                    else uuid4()
                ),
                task_id=message.task_id,
                task_type=message.task_type,
                execution_no=execution_no,
                parameters=context.parameters,
                creative_brief=context.creative_brief,
                reference_assets=context.reference_assets,
                controlled_mask_asset=context.controlled_mask_asset,
                traceparent=message.traceparent,
            )
            provider_boundary = (
                bind_provider_call_boundary(
                    max_calls=context.max_provider_calls or 0,
                    deadline_at=context.deadline_at,
                )
                if (
                    message.task_type is TaskType.VIDEO
                    or (
                        context.creative_brief is not None
                        and context.creative_brief.source_mode is CreativeSourceMode.REGION_EDIT
                    )
                )
                and context.max_provider_calls is not None
                else nullcontext()
            )
            with (
                provider_boundary,
                start_span(
                    "provider.submit",
                    attributes={
                        "provider.name": selected_provider.code,
                        "provider.model": selected_provider.model,
                        "provider.operation": (
                            "resume" if context.resume_only else "submit-and-poll"
                        ),
                        "executionNo": execution_no,
                        "creative.sourceMode": (
                            "NONE"
                            if context.creative_brief is None
                            else context.creative_brief.source_mode.value
                        ),
                        "creative.referenceAssetCount": len(context.reference_assets),
                    },
                ) as span,
            ):
                try:
                    provider_call_started = True
                    execution_phase = (
                        "PROVIDER_RECOVERY" if context.resume_only else "PROVIDER_SUBMISSION"
                    )
                    provider_operation = (
                        (
                            lambda: self._resume_provider(
                                provider_request,
                                continuation=continuation,
                                max_provider_calls=context.max_provider_calls,
                                provider=selected_provider,
                            )
                        )
                        if context.resume_only
                        else (
                            lambda: self._execute_provider(
                                provider_request,
                                max_provider_calls=context.max_provider_calls,
                                provider=selected_provider,
                            )
                        )
                    )
                    result = await lease_monitor.run(
                        provider_operation,
                        name=f"worker-provider-{message.task_id}",
                    )
                    provider_result = result
                    execution_phase = "PROVIDER_RESULT_VALIDATION"
                except TaskProviderError as exc:
                    span.set("provider.submissionState", exc.submission_state.value)
                    span.fail(exc.code)
                    raise
            if (result.provider, result.model) != (selected_provider.code, selected_provider.model):
                raise TaskProviderError(
                    "PROVIDER_RESULT_IDENTITY_MISMATCH",
                    category=ProviderErrorCategory.INVALID_RESPONSE,
                    retryable=False,
                    submission_state=SubmissionState.CONFIRMED,
                )
            self._enforce_result_budget(
                message.task_type,
                result,
                max_images=context.max_images,
                max_videos=context.max_videos,
                max_video_seconds=context.max_video_seconds,
            )
            lease_monitor.raise_if_lost()
            with start_span(
                "java.result.handoff",
                attributes={
                    "task.ref": safe_ref(message.task_id),
                    "executionNo": execution_no,
                },
            ) as span:
                execution_phase = "RESULT_HANDOFF"
                try:
                    java_task_status = await self._control_plane.report_result(
                        message.task_id,
                        execution_no=execution_no,
                        execution_token=execution_token,
                        result=result,
                        traceparent=message.traceparent,
                    )
                except (ControlPlaneUnavailable, ControlPlaneRejected) as exc:
                    span.fail(exc.code)
                    raise
                span.set("java.taskStatus", java_task_status)
            trace = parse_traceparent(message.traceparent)
            logger.info(
                "worker_result_handoff_accepted",
                extra={
                    "traceId": trace.trace_id if trace is not None else None,
                    "taskId": message.task_id,
                    "provider": result.provider,
                    "operation": "RESULT_HANDOFF",
                    "status": "ACCEPTED",
                    "javaTaskStatus": java_task_status,
                },
            )
            self._record_processed(message.task_type)
            handoff_status = (
                WorkerTaskStatus.HANDOFF_ACCEPTED
                if result.provider != "LOCAL_TASK_PROVIDER" and java_task_status == "RUNNING"
                else WorkerTaskStatus.RESULT_READY
            )
            self._update_snapshot(
                snapshot,
                status=handoff_status,
                provider=result.provider,
                provider_model=result.model,
                provider_task_id=result.provider_task_id,
                artifact_count=len(result.artifacts),
                artifacts=result.artifacts,
            )
        except TaskProviderError as exc:
            reported = await self._report_failure_best_effort(
                message.task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                code=exc.code,
                submission_state=exc.submission_state,
                traceparent=message.traceparent,
                provider=(
                    selected_provider.code
                    if selected_provider is not None
                    and exc.submission_state is SubmissionState.UNKNOWN
                    else None
                ),
                model=(
                    selected_provider.model
                    if selected_provider is not None
                    and exc.submission_state is SubmissionState.UNKNOWN
                    else None
                ),
                provider_request_id=exc.provider_request_id,
                provider_task_id=exc.provider_task_id,
            )
            self._record_failed(message.task_type)
            self._update_snapshot(
                snapshot,
                status=(
                    WorkerTaskStatus.SUBMISSION_UNKNOWN
                    if exc.submission_state is SubmissionState.UNKNOWN
                    else WorkerTaskStatus.FAILED
                ),
                error_code=exc.code if reported else "JAVA_FAILURE_REPORT_UNAVAILABLE",
                provider_task_id=exc.provider_task_id,
            )
        except ControlPlaneUnavailable as exc:
            trace = parse_traceparent(message.traceparent)
            logger.error(
                "worker_control_plane_failed",
                extra={
                    "traceId": trace.trace_id if trace is not None else None,
                    "taskId": message.task_id,
                    "attempt": execution_no,
                    "failureStage": execution_phase,
                    "stableErrorCode": exc.code,
                },
            )
            # A successfully received but contract-invalid execution context
            # is deterministic for this frozen task. No provider call has
            # occurred, so report a terminal failure now instead of burning
            # two leases on the same malformed snapshot. Network/control-plane
            # outages retain the dependency-recovery path below.
            if (
                execution_phase == "CONTEXT_LOAD"
                and exc.code == "JAVA_CONTROL_PLANE_RESPONSE_INVALID"
            ):
                reported = await self._report_failure_best_effort(
                    message.task_id,
                    execution_no=execution_no,
                    execution_token=execution_token,
                    code=exc.code,
                    submission_state=SubmissionState.NOT_SUBMITTED,
                    traceparent=message.traceparent,
                )
                self._record_failed(message.task_type)
                self._update_snapshot(
                    snapshot,
                    status=WorkerTaskStatus.FAILED,
                    error_code=exc.code if reported else "JAVA_FAILURE_REPORT_UNAVAILABLE",
                )
                return
            self._record_failed(message.task_type)
            self._update_snapshot(
                snapshot,
                status=WorkerTaskStatus.FAILED,
                error_code=exc.code,
            )
            await self._stop_for_control_plane(exc.code)
        except ControlPlaneRejected as exc:
            trace = parse_traceparent(message.traceparent)
            logger.error(
                "worker_control_plane_failed",
                extra={
                    "traceId": trace.trace_id if trace is not None else None,
                    "taskId": message.task_id,
                    "attempt": execution_no,
                    "failureStage": execution_phase,
                    "stableErrorCode": exc.code,
                },
            )
            # Java validates the live execution token before checking frozen
            # ARTICLE sources. Only a first, pre-provider execution is safe to
            # close as NOT_SUBMITTED; a recovery attempt may have unknown costs.
            # Claim conflicts and late/expired-token rejections must stay fenced.
            if (
                execution_phase == "CONTEXT_LOAD"
                and message.task_type is TaskType.ARTICLE
                and execution_no == 1
                and exc.code == "KNOWLEDGE_SOURCE_CHANGED"
            ):
                reported = await self._report_failure_best_effort(
                    message.task_id,
                    execution_no=execution_no,
                    execution_token=execution_token,
                    code=exc.code,
                    submission_state=SubmissionState.NOT_SUBMITTED,
                    traceparent=message.traceparent,
                )
                self._record_failed(message.task_type)
                self._update_snapshot(
                    snapshot,
                    status=WorkerTaskStatus.FAILED,
                    error_code=exc.code if reported else "JAVA_FAILURE_REPORT_UNAVAILABLE",
                )
                return
            self._record_failed(message.task_type)
            self._update_snapshot(
                snapshot,
                status=WorkerTaskStatus.FAILED,
                error_code=exc.code,
            )
        except Exception as exc:
            safe_code = (
                exc.code if isinstance(exc, WorkerNotReadyError) else "WORKER_EXECUTION_FAILED"
            )
            submission_state = SubmissionState.CONFIRMED
            provider_request_id: str | None = None
            provider_task_id: str | None = None
            if message.task_type in {TaskType.IMAGE, TaskType.VIDEO}:
                if not provider_call_started:
                    # Context, grounding, budget and progress preparation are all
                    # before the first Provider operation.  A failure here is the
                    # only catch-all path that can prove no paid request was sent.
                    submission_state = SubmissionState.NOT_SUBMITTED
                elif continuation is not None:
                    # Recovery already carries Java-authorized queryable evidence.
                    # Preserve it verbatim so the next attempt remains GET-only.
                    submission_state = SubmissionState.UNKNOWN
                    provider_request_id = str(continuation.provider_request_id)
                    provider_task_id = continuation.provider_task_id
                elif provider_result is not None and provider_result.provider_task_id is not None:
                    # The adapter completed but a later validation/handoff step
                    # failed.  Its receipt is evidence for GET-only recovery.
                    submission_state = SubmissionState.UNKNOWN
                    provider_request_id = str(provider_result.request_id)
                    provider_task_id = provider_result.provider_task_id
                elif provider_result is not None:
                    # A completed result proves submission, but without a queryable
                    # task receipt Java cannot authorize a recovery GET.
                    submission_state = SubmissionState.CONFIRMED
                else:
                    # The Provider operation started, but the adapter supplied no
                    # queryable receipt.  Report UNKNOWN with null identifiers;
                    # Java deliberately fails closed instead of dispatching a new
                    # POST.  Never manufacture an identifier from the local request.
                    submission_state = SubmissionState.UNKNOWN
                    safe_code = "PROVIDER_SUBMISSION_EVIDENCE_UNAVAILABLE"
            trace = parse_traceparent(message.traceparent)
            logger.error(
                "worker_execution_failed",
                extra={
                    "traceId": trace.trace_id if trace is not None else None,
                    "taskId": message.task_id,
                    "agentRunId": None,
                    "stableErrorCode": safe_code,
                    "failureStage": execution_phase,
                    "attempt": execution_no,
                },
            )
            reported = await self._report_failure_best_effort(
                message.task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                code=safe_code,
                submission_state=submission_state,
                traceparent=message.traceparent,
                provider=(
                    selected_provider.code
                    if selected_provider is not None and submission_state is SubmissionState.UNKNOWN
                    else None
                ),
                model=(
                    selected_provider.model
                    if selected_provider is not None and submission_state is SubmissionState.UNKNOWN
                    else None
                ),
                provider_request_id=provider_request_id,
                provider_task_id=provider_task_id,
            )
            self._record_failed(message.task_type)
            self._update_snapshot(
                snapshot,
                status=(
                    WorkerTaskStatus.SUBMISSION_UNKNOWN
                    if submission_state is SubmissionState.UNKNOWN
                    else WorkerTaskStatus.FAILED
                ),
                error_code=safe_code if reported else "JAVA_FAILURE_REPORT_UNAVAILABLE",
                provider_task_id=provider_task_id,
            )
        finally:
            if lease_monitor is not None:
                await lease_monitor.close()

    async def _renew_execution_lease(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        stage_code: str,
        traceparent: str,
    ) -> None:
        await self._control_plane.heartbeat(
            task_id,
            execution_no=execution_no,
            execution_token=execution_token,
            stage_code=stage_code,
            traceparent=traceparent,
        )

    async def _report_failure_best_effort(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        code: str,
        submission_state: SubmissionState,
        traceparent: str,
        provider: str | None = None,
        model: str | None = None,
        provider_request_id: str | None = None,
        provider_task_id: str | None = None,
    ) -> bool:
        try:
            await self._control_plane.report_failure(
                task_id,
                execution_no=execution_no,
                execution_token=execution_token,
                code=code,
                submission_state=submission_state,
                traceparent=traceparent,
                provider=provider,
                model=model,
                provider_request_id=provider_request_id,
                provider_task_id=provider_task_id,
            )
        except ControlPlaneUnavailable as exc:
            await self._stop_for_control_plane(exc.code)
            trace = parse_traceparent(traceparent)
            logger.error(
                "worker_failure_report_unavailable",
                extra={
                    "traceId": trace.trace_id if trace is not None else None,
                    "taskId": task_id,
                    "agentRunId": None,
                    "stableErrorCode": "JAVA_FAILURE_REPORT_UNAVAILABLE",
                    "failureStage": "FAILURE_REPORT",
                    "attempt": execution_no,
                },
            )
            return False
        except Exception:
            trace = parse_traceparent(traceparent)
            logger.error(
                "worker_failure_report_unavailable",
                extra={
                    "traceId": trace.trace_id if trace is not None else None,
                    "taskId": task_id,
                    "agentRunId": None,
                    "stableErrorCode": "JAVA_FAILURE_REPORT_UNAVAILABLE",
                    "failureStage": "FAILURE_REPORT",
                    "attempt": execution_no,
                },
            )
            return False
        return True

    async def _resume_provider(
        self,
        request: TaskProviderRequest,
        *,
        continuation: TaskProviderContinuation | None,
        max_provider_calls: int | None,
        provider: TaskProviderAdapter | None = None,
    ) -> TaskProviderResult:
        if continuation is None:
            raise WorkerNotReadyError("PROVIDER_CONTINUATION_MISSING")
        if max_provider_calls is not None and max_provider_calls < 1:
            raise self._continuation_error(
                "PROVIDER_CALL_BUDGET_INVALID",
                continuation,
            )
        provider = provider or self._provider
        if (
            provider is None
            or provider.code != continuation.provider
            or provider.model != continuation.model
            or not isinstance(provider, ResumableTaskProviderAdapter)
        ):
            raise self._continuation_error(
                "PROVIDER_CONTINUATION_IDENTITY_MISMATCH",
                continuation,
            )
        return await provider.resume(request, continuation)

    @staticmethod
    def _continuation_error(
        code: str,
        continuation: TaskProviderContinuation,
    ) -> TaskProviderError:
        return TaskProviderError(
            code,
            category=ProviderErrorCategory.INVALID_RESPONSE,
            retryable=False,
            submission_state=SubmissionState.UNKNOWN,
            provider_request_id=str(continuation.provider_request_id),
            provider_task_id=continuation.provider_task_id,
        )

    async def _execute_provider(
        self,
        request: TaskProviderRequest,
        *,
        max_provider_calls: int | None = None,
        provider: TaskProviderAdapter | None = None,
    ) -> TaskProviderResult:
        settings = self._runtime.settings
        provider = provider or self._provider
        if provider is None:
            raise WorkerNotReadyError("MEDIA_PROVIDER_NOT_CONFIGURED")
        if max_provider_calls is not None and max_provider_calls < 1:
            raise WorkerNotReadyError("PROVIDER_CALL_BUDGET_INVALID")
        attempt_limit = settings.worker_provider_max_attempts
        if max_provider_calls is not None:
            attempt_limit = min(attempt_limit, max_provider_calls)
        started = monotonic()
        for attempt_index in range(attempt_limit):
            try:
                return await provider.execute(request)
            except TaskProviderError as exc:
                last_attempt = attempt_index + 1 >= attempt_limit
                safe_to_retry = (
                    exc.retryable
                    and exc.submission_state is SubmissionState.NOT_SUBMITTED
                    and not last_attempt
                )
                if not safe_to_retry:
                    raise
                exponential = min(
                    settings.worker_provider_retry_max_seconds,
                    settings.worker_provider_retry_base_seconds * (2**attempt_index),
                )
                retry_after = exc.retry_after_seconds or 0
                jitter = random.uniform(
                    0,
                    settings.worker_provider_retry_base_seconds,
                )
                delay = max(exponential, retry_after) + jitter
                if monotonic() - started + delay > settings.worker_provider_retry_budget_seconds:
                    raise
                await asyncio.sleep(delay)
        raise RuntimeError("Provider retry loop completed without a result")

    def _enforce_creative_mode(
        self,
        task_type: TaskType,
        context: ExecutionContext,
        *,
        provider: TaskProviderAdapter | None = None,
    ) -> None:
        if task_type not in {TaskType.IMAGE, TaskType.VIDEO}:
            return
        provider = provider or self._provider
        if provider is None:
            raise WorkerNotReadyError("MEDIA_PROVIDER_NOT_CONFIGURED")
        brief = context.creative_brief
        references = context.reference_assets
        source = brief.source_mode if brief is not None else None
        required = (
            TaskProviderCapability.REFERENCE_IMAGE_GENERATION
            if task_type is TaskType.IMAGE
            else TaskProviderCapability.IMAGE_TO_VIDEO
        )
        if source is CreativeSourceMode.TEXT_ONLY:
            required = (
                TaskProviderCapability.TEXT_TO_IMAGE
                if task_type is TaskType.IMAGE
                else TaskProviderCapability.TEXT_TO_VIDEO
            )
        elif source is CreativeSourceMode.PRODUCT_MATERIALS:
            required = TaskProviderCapability.REFERENCE_TO_VIDEO
        elif source is CreativeSourceMode.REGION_EDIT:
            required = TaskProviderCapability.MASK_IMAGE_EDIT
        if required not in provider.capabilities:
            raise TaskProviderError(
                "REFERENCE_IMAGE_MODE_UNAVAILABLE"
                if task_type is TaskType.IMAGE
                else "IMAGE_TO_VIDEO_MODE_UNAVAILABLE",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )
        text_only = source is CreativeSourceMode.TEXT_ONLY
        multiple = task_type is TaskType.IMAGE or source is CreativeSourceMode.PRODUCT_MATERIALS
        invalid_count = (
            bool(references) if text_only else not 1 <= len(references) <= (3 if multiple else 1)
        )
        if source in {
            CreativeSourceMode.PREVIOUS_USER_ATTACHMENT,
            CreativeSourceMode.PREVIOUS_IMAGE_RESULT,
        }:
            invalid_count = len(references) != 1
        invalid_mode = (
            source is CreativeSourceMode.PRODUCT_MATERIALS and task_type is not TaskType.VIDEO
        ) or (source is CreativeSourceMode.REGION_EDIT and task_type is not TaskType.IMAGE)
        if brief is None or invalid_count or invalid_mode:
            raise TaskProviderError(
                "REFERENCE_IMAGE_MODE_UNAVAILABLE"
                if task_type is TaskType.IMAGE
                else "IMAGE_TO_VIDEO_MODE_UNAVAILABLE",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )
        if source is CreativeSourceMode.GEO_REPORT:
            try:
                validated_frozen_plan(
                    brief.creative_direction.get("creativePlan"),
                    "IMAGE" if task_type is TaskType.IMAGE else "VIDEO",
                )
            except ValueError as exc:
                raise TaskProviderError(
                    "GEO_VISUAL_PLAN_INVALID",
                    category=ProviderErrorCategory.INVALID_RESPONSE,
                    retryable=False,
                    submission_state=SubmissionState.NOT_SUBMITTED,
                ) from exc
        try:
            brief.validate_reference_assets(references)
        except ValueError as exc:
            raise TaskProviderError(
                "REFERENCE_ASSET_NOT_READY",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            ) from exc
        mask = context.controlled_mask_asset
        if source is CreativeSourceMode.REGION_EDIT:
            if (
                mask is None
                or len(references) != 1
                or mask.asset_id != context.parameters.get("maskAssetId")
                or mask.asset_id != brief.creative_direction.get("maskAssetId")
            ):
                raise TaskProviderError(
                    "WAN_IMAGE_EDIT_MASK_INVALID",
                    category=ProviderErrorCategory.INVALID_RESPONSE,
                    retryable=False,
                    submission_state=SubmissionState.NOT_SUBMITTED,
                )
        elif mask is not None:
            raise TaskProviderError(
                "UNEXPECTED_MASK_ASSET",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )
        if not context.resume_only and any(
            reference.expires_at < datetime.now(UTC) + _REFERENCE_URL_MINIMUM_TTL
            for reference in (*references, *((mask,) if mask is not None else ()))
        ):
            raise TaskProviderError(
                "REFERENCE_ASSET_NOT_READY",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )

    @staticmethod
    def _enforce_request_budget(
        task_type: TaskType,
        parameters: dict[str, JsonValue],
        *,
        max_images: int | None,
        max_videos: int | None,
        max_video_seconds: int | None,
    ) -> None:
        if task_type is TaskType.VIDEO:
            WorkerRuntime._enforce_video_request_budget(
                parameters,
                max_videos=max_videos,
                max_video_seconds=max_video_seconds,
            )
            return
        if max_images is None:
            return
        if not 1 <= max_images <= 4:
            raise TaskProviderError(
                "PROVIDER_IMAGE_BUDGET_INVALID",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )
        requested_count = parameters.get("count", 1)
        if (
            isinstance(requested_count, bool)
            or not isinstance(requested_count, int)
            or requested_count != max_images
        ):
            raise TaskProviderError(
                "PROVIDER_IMAGE_BUDGET_INVALID",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )

    @staticmethod
    def _enforce_result_budget(
        task_type: TaskType,
        result: TaskProviderResult,
        *,
        max_images: int | None,
        max_videos: int | None,
        max_video_seconds: int | None,
    ) -> None:
        if task_type is TaskType.VIDEO:
            WorkerRuntime._enforce_video_result_budget(
                result,
                max_videos=max_videos,
                max_video_seconds=max_video_seconds,
            )
            return
        if max_images is None:
            return
        if not 1 <= max_images <= 4:
            raise WorkerNotReadyError("PROVIDER_IMAGE_BUDGET_INVALID")
        image_artifacts = sum(artifact.artifact_type == "IMAGE" for artifact in result.artifacts)
        if (
            not 1 <= image_artifacts <= max_images
            or image_artifacts != len(result.artifacts)
            or result.usage.images != image_artifacts
        ):
            raise TaskProviderError(
                "PROVIDER_BUDGET_EXCEEDED",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.CONFIRMED,
            )

    @staticmethod
    def _enforce_video_request_budget(
        parameters: dict[str, JsonValue],
        *,
        max_videos: int | None,
        max_video_seconds: int | None,
    ) -> None:
        if max_videos is None and max_video_seconds is None:
            return
        duration = parameters.get("durationSeconds")
        if (
            max_videos != 1
            or max_video_seconds is None
            or max_video_seconds < 2
            or isinstance(duration, bool)
            or not isinstance(duration, int)
            or duration < 2
            or duration > max_video_seconds
        ):
            raise TaskProviderError(
                "PROVIDER_VIDEO_BUDGET_INVALID",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )

    @staticmethod
    def _enforce_video_result_budget(
        result: TaskProviderResult,
        *,
        max_videos: int | None,
        max_video_seconds: int | None,
    ) -> None:
        if max_videos is None and max_video_seconds is None:
            return
        video_artifacts = sum(artifact.artifact_type == "VIDEO" for artifact in result.artifacts)
        if (
            max_videos != 1
            or video_artifacts != max_videos
            or max_video_seconds is None
            or result.usage.video_seconds < 1
            or result.usage.video_seconds > max_video_seconds
            or result.usage.images != 0
        ):
            raise TaskProviderError(
                "PROVIDER_BUDGET_EXCEEDED",
                category=ProviderErrorCategory.INVALID_RESPONSE,
                retryable=False,
                submission_state=SubmissionState.CONFIRMED,
            )

    def get_task(self, event_id: str) -> WorkerTaskSnapshot | None:
        return self._snapshots.get(event_id)

    def status_snapshot(self) -> WorkerStatusSnapshot:
        settings = self._runtime.settings
        return WorkerStatusSnapshot(
            status=self._status,
            service=f"{settings.service_name}-worker",
            service_version=settings.service_version,
            worker_id=settings.worker_id,
            task_type=settings.worker_task_type,
            control_plane=settings.worker_control_plane,
            provider=self._execution_backend.code,
            provider_model=self._execution_backend.model,
            mock_mode=self._execution_backend.is_mock,
            accepting=self._broker.accepting and self.is_ready,
            queue_depth=self._broker.depth,
            in_flight=len(self._in_flight),
            processed=self._processed,
            failed=self._failed,
            reason_code=self._reason_code,
        )

    async def close(self) -> None:
        if self._status == "STOPPED":
            return
        self._status = "DRAINING"
        recovery_task = self._dependency_recovery_task
        if recovery_task is not None:
            recovery_task.cancel()
            await asyncio.gather(recovery_task, return_exceptions=True)
            self._dependency_recovery_task = None
        await self._broker.stop_accepting()
        if self._consumer_task is not None:
            await self._consumer_task
        if self._in_flight:
            tasks = tuple(self._in_flight)
            try:
                await asyncio.wait_for(
                    asyncio.gather(*tasks, return_exceptions=True),
                    timeout=self._runtime.settings.worker_graceful_timeout_seconds,
                )
            except TimeoutError:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
        await self._broker.aclose()
        await asyncio.gather(
            self._execution_backend.aclose(),
            self._control_plane.aclose(),
            return_exceptions=True,
        )
        self._status = "STOPPED"

    async def _consume(self) -> None:
        try:
            while True:
                delivery = await self._broker.get()
                if delivery is None:
                    return
                task = asyncio.create_task(
                    self._bounded_process(delivery), name=delivery.delivery_id
                )
                self._in_flight.add(task)
                adjust_worker_inflight(1, task_type=self._runtime.settings.worker_task_type)
                task.add_done_callback(self._finish_inflight)
        except MessageBrokerUnavailable as exc:
            await self._mark_broker_unavailable(exc)

    async def _bounded_process(self, delivery: RawDelivery) -> None:
        async with self._semaphore:
            try:
                await self.process_delivery(delivery)
            except MessageBrokerUnavailable as exc:
                await self._mark_broker_unavailable(exc)
            except Exception:
                await self._handle_poison_delivery(delivery)

    async def _handle_poison_delivery(self, delivery: RawDelivery) -> None:
        try:
            disposition = await settle_poison_delivery(delivery)
        except MessageBrokerUnavailable as exc:
            await self._mark_broker_unavailable(exc)
            return
        error_code = (
            "WORKER_DELIVERY_POISONED" if disposition == "REJECTED" else "WORKER_DELIVERY_FAILED"
        )
        message: TaskExecutionMessage | None = None
        with suppress(RabbitMessageSchemaInvalid):
            message = parse_task_execution_message(delivery.body)
        self._record_failed(None if message is None else message.task_type)
        if message is not None:
            snapshot = self._snapshots.get(message.event_id)
            if snapshot is None:
                snapshot = self._snapshot_for(message, WorkerTaskStatus.FAILED, delivery)
            self._update_snapshot(
                snapshot,
                status=(
                    WorkerTaskStatus.REJECTED
                    if disposition == "REJECTED"
                    else WorkerTaskStatus.FAILED
                ),
                delivery_state=delivery.state,
                error_code=error_code,
            )
        trace = None if message is None else parse_traceparent(message.traceparent)
        logger.error(
            "worker_delivery_failed",
            extra={
                "traceId": trace.trace_id if trace is not None else None,
                "taskId": None if message is None else safe_ref(message.task_id),
                "agentRunId": None,
                "stableErrorCode": error_code,
            },
        )

    def _finish_inflight(self, task: asyncio.Task[None]) -> None:
        self._in_flight.discard(task)
        adjust_worker_inflight(-1, task_type=self._runtime.settings.worker_task_type)

    async def _mark_broker_unavailable(self, error: MessageBrokerUnavailable) -> None:
        if not await self._broker.invalidate(error.generation):
            return
        await self._pause_for_dependency("RABBITMQ_UNAVAILABLE")

    async def _stop_for_control_plane(self, reason_code: str) -> None:
        await self._pause_for_dependency(reason_code)

    async def _pause_for_dependency(self, reason_code: str) -> None:
        async with self._control_plane_recovery_lock:
            if self._status in {"DRAINING", "STOPPED"}:
                return
            self._status = "NOT_READY"
            self._reason_code = reason_code
            self._dependency_generation += 1
            await self._broker.stop_accepting()
            self._ensure_dependency_recovery()

    def _ensure_dependency_recovery(self) -> None:
        if self._status in {"DRAINING", "STOPPED"}:
            return
        if self._dependency_recovery_task is not None and not self._dependency_recovery_task.done():
            return
        task = asyncio.create_task(self._recover_dependencies(), name="worker-dependency-recovery")
        self._dependency_recovery_task = task
        task.add_done_callback(self._dependency_recovery_finished)

    def _dependency_recovery_finished(self, task: asyncio.Task[None]) -> None:
        if self._dependency_recovery_task is not task:
            return
        self._dependency_recovery_task = None
        if not task.cancelled() and task.exception() is not None:
            logger.error(
                "worker_dependency_recovery_failed",
                extra={"stableErrorCode": "WORKER_DEPENDENCY_RECOVERY_FAILED"},
            )
        if self._status == "NOT_READY":
            self._ensure_dependency_recovery()

    async def _recover_dependencies(self) -> None:
        consumer_task = self._consumer_task
        if consumer_task is not None and consumer_task is not asyncio.current_task():
            await asyncio.gather(asyncio.shield(consumer_task), return_exceptions=True)
        self._consumer_task = None
        delay = self._dependency_recovery_base_seconds
        while self._status not in {"DRAINING", "STOPPED"}:
            await asyncio.sleep(delay)
            generation = self._dependency_generation
            try:
                control_plane_ready = await self._control_plane.probe()
            except (ControlPlaneUnavailable, ControlPlaneRejected) as exc:
                self._reason_code = exc.code
            else:
                if not control_plane_ready:
                    self._reason_code = "JAVA_CONTROL_PLANE_UNAVAILABLE"
                else:
                    try:
                        backend_ready = await self._execution_backend.probe()
                    except TaskProviderError:
                        backend_ready = False
                    if not backend_ready:
                        self._reason_code = "TASK_PROVIDER_UNAVAILABLE"
                        delay = min(delay * 2, self._dependency_recovery_max_seconds)
                        continue
                    async with self._control_plane_recovery_lock:
                        if generation != self._dependency_generation:
                            continue
                        try:
                            await self._broker.restart()
                        except MessageBrokerUnavailable:
                            self._reason_code = "RABBITMQ_UNAVAILABLE"
                        else:
                            if self._status in {"DRAINING", "STOPPED"}:
                                return
                            self._status = "READY"
                            self._reason_code = None
                            self._consumer_task = asyncio.create_task(
                                self._consume(), name="worker-consumer"
                            )
                            return
            delay = min(delay * 2, self._dependency_recovery_max_seconds)

    def _snapshot_for(
        self,
        message: TaskExecutionMessage,
        status: WorkerTaskStatus,
        delivery: RawDelivery,
    ) -> WorkerTaskSnapshot:
        snapshot = WorkerTaskSnapshot(
            event_id=message.event_id,
            task_id=message.task_id,
            task_type=message.task_type,
            status=status,
            delivery_state=delivery.state,
            updated_at=datetime.now(UTC),
        )
        self._put_snapshot(snapshot)
        return snapshot

    def _update_snapshot(
        self,
        snapshot: WorkerTaskSnapshot,
        *,
        status: WorkerTaskStatus,
        delivery_state: Literal["PENDING", "ACKED", "NACKED", "REJECTED"] | None = None,
        execution_no: int | None = None,
        provider: str | None = None,
        provider_model: str | None = None,
        provider_task_id: str | None = None,
        artifact_count: int | None = None,
        artifacts: tuple[ProviderArtifact, ...] | None = None,
        error_code: str | None = None,
    ) -> None:
        update: dict[str, object] = {
            "status": status,
            "updated_at": datetime.now(UTC),
        }
        optional = {
            "delivery_state": delivery_state,
            "execution_no": execution_no,
            "provider": provider,
            "provider_model": provider_model,
            "provider_task_id": provider_task_id,
            "artifact_count": artifact_count,
            "artifacts": artifacts,
            "error_code": error_code,
        }
        update.update({key: value for key, value in optional.items() if value is not None})
        current = self._snapshots.get(snapshot.event_id, snapshot)
        updated = current.model_copy(update=update)
        self._put_snapshot(updated)

    def _put_snapshot(self, snapshot: WorkerTaskSnapshot) -> None:
        self._snapshots[snapshot.event_id] = snapshot
        self._snapshots.move_to_end(snapshot.event_id)
        while len(self._snapshots) > self._runtime.settings.max_local_runs:
            self._snapshots.popitem(last=False)
