"""Bounded Rabbit workflow executor backed exclusively by Java business truth."""

from __future__ import annotations

import asyncio
import logging
from contextlib import suppress
from datetime import UTC, datetime
from typing import Literal, cast
from uuid import UUID

from pydantic import BaseModel, ValidationError

from app.common.settings import AppSettings
from app.harness.runtime import HarnessViolation, RuntimeUsageCounters
from app.observability.metrics import adjust_worker_inflight, record_worker_task
from app.observability.tracing import current_otel_traceparent, safe_ref, start_span
from app.providers.java_context import authorized_run_context_from_snapshot
from app.tools.readonly import (
    CatalogInspectOutput,
    CompetitorCompareOutput,
    EvidenceAggregateOutput,
    MemorySearchOutput,
    ReadOnlyToolBindings,
    TaskWorkspaceInspectOutput,
    identity_input,
)
from app.tools.registry import (
    DEFAULT_TOOL_REGISTRY,
    RiskLevel,
    ToolExecutionError,
    ToolExecutor,
    ToolGroup,
    ToolPolicy,
)
from app.workers.control_plane import ControlPlaneRejected, ControlPlaneUnavailable
from app.workers.models import WorkerStatusSnapshot
from app.workers.transport import (
    MessageBroker,
    MessageBrokerUnavailable,
    RawDelivery,
    settle_poison_delivery,
)
from app.workers.workflow_control_plane import WorkflowClaim, WorkflowControlPlane
from app.workers.workflow_models import (
    WorkflowExecutionContext,
    WorkflowExecutionMessage,
    WorkflowObservation,
    WorkflowStep,
    stable_workflow_uuid,
)

logger = logging.getLogger(__name__)

_ALLOWED_TOOLS = frozenset(
    {
        "context.catalog.inspect",
        "context.memory.search",
        "context.task_workspace.inspect",
        "analysis.competitor.compare",
        "analysis.evidence.aggregate",
        "task_draft.propose",
    }
)


class WorkflowBudgetCounter:
    """Per-execution counters checked before every local or model action."""

    def __init__(self, context: WorkflowExecutionContext) -> None:
        self.context = context
        self.steps = sum(step.status not in {"PENDING", "READY"} for step in context.steps)
        self.models = 0
        self.tools = len(context.observations)
        # One bound Task means Java already consumed one downstream Provider/Task
        # authorization slot.  This survives every Worker process and lease.
        self.providers = len({step.task_id for step in context.steps if step.task_id is not None})
        self.replans = min(max(context.plan_version - 1, 0), context.budget.max_replans)

    def before_step(self) -> None:
        self._deadline()
        if self.steps + 1 > self.context.budget.max_steps:
            raise HarnessViolation("WORKFLOW_STEP_BUDGET_EXCEEDED", "workflow step budget exceeded")
        self.steps += 1

    def before_tool_call(self) -> None:
        self._deadline()
        if self.tools + 1 > self.context.budget.max_tool_calls:
            raise HarnessViolation("WORKFLOW_TOOL_BUDGET_EXCEEDED", "workflow tool budget exceeded")
        self.tools += 1

    def before_model_call(self) -> None:
        self._deadline()
        if self.models + 1 > self.context.budget.max_model_calls:
            raise HarnessViolation(
                "WORKFLOW_MODEL_BUDGET_EXCEEDED", "workflow model budget exceeded"
            )
        self.models += 1

    def before_provider_call(self) -> None:
        self._deadline()
        if self.providers + 1 > self.context.budget.max_provider_calls:
            raise HarnessViolation(
                "WORKFLOW_PROVIDER_BUDGET_EXCEEDED", "workflow Task authorization exceeded"
            )
        self.providers += 1

    def before_replan(self) -> None:
        self._deadline()
        if self.replans + 1 > self.context.budget.max_replans:
            raise HarnessViolation(
                "WORKFLOW_REPLAN_BUDGET_EXCEEDED", "workflow replan budget exceeded"
            )
        self.replans += 1

    def _deadline(self) -> None:
        if datetime.now(UTC) >= self.context.budget.deadline_at:
            raise HarnessViolation("WORKFLOW_DEADLINE_EXCEEDED", "workflow deadline exceeded")


class WorkflowWorkerRuntime:
    """Consume the frozen workflow envelope and execute READY steps topologically."""

    def __init__(
        self,
        *,
        settings: AppSettings,
        bundle_version: str,
        control_plane: WorkflowControlPlane,
        broker: MessageBroker,
        heartbeat_interval_seconds: float = 30.0,
        dependency_recovery_base_seconds: float = 1.0,
        dependency_recovery_max_seconds: float = 30.0,
    ) -> None:
        if not 0 < dependency_recovery_base_seconds <= dependency_recovery_max_seconds:
            raise ValueError("Workflow dependency recovery interval is invalid")
        self._settings = settings
        self._bundle_version = bundle_version
        self._control = control_plane
        self._broker = broker
        self._heartbeat_seconds = heartbeat_interval_seconds
        self._status: Literal["STARTING", "READY", "DRAINING", "STOPPED", "NOT_READY"] = "STARTING"
        self._reason: str | None = None
        self._processed = 0
        self._failed = 0
        self._consumer: asyncio.Task[None] | None = None
        self._in_flight: set[asyncio.Task[None]] = set()
        self._semaphore = asyncio.Semaphore(settings.worker_max_concurrency)
        self._dependency_recovery_base_seconds = dependency_recovery_base_seconds
        self._dependency_recovery_max_seconds = dependency_recovery_max_seconds
        self._dependency_recovery_task: asyncio.Task[None] | None = None
        self._dependency_recovery_lock = asyncio.Lock()
        self._dependency_generation = 0

    @property
    def is_ready(self) -> bool:
        return self._status == "READY"

    async def start(self) -> bool:
        try:
            if not await self._control.probe():
                raise ControlPlaneUnavailable("WORKFLOW_JAVA_CONTROL_UNAVAILABLE")
            await self._broker.start()
        except (ControlPlaneUnavailable, ControlPlaneRejected) as exc:
            self._status, self._reason = "NOT_READY", exc.code
            self._ensure_dependency_recovery()
            return False
        except MessageBrokerUnavailable:
            self._status, self._reason = "NOT_READY", "RABBITMQ_UNAVAILABLE"
            self._ensure_dependency_recovery()
            return False
        self._status = "READY"
        self._consumer = asyncio.create_task(self._consume(), name="workflow-worker-consumer")
        return True

    async def process_delivery(self, delivery: RawDelivery) -> None:
        try:
            message = WorkflowExecutionMessage.model_validate_json(delivery.body)
        except (ValidationError, ValueError):
            with start_span(
                "rabbit.consume",
                attributes={"task.type": "WORKFLOW", "workflow.stage": "CONSUME"},
            ) as span:
                span.fail("WORKFLOW_MESSAGE_INVALID")
                await delivery.reject()
            self._record_failed()
            return
        with start_span(
            "rabbit.consume",
            parent_traceparent=None if delivery.redelivered else message.traceparent,
            link_traceparent=message.traceparent if delivery.redelivered else None,
            attributes={
                "task.type": "WORKFLOW",
                "workflow.stage": "CONSUME",
                "workflow.planRef": safe_ref(message.plan_id),
                "execution.attempt": message.attempt,
            },
        ) as span:
            await self._process(message, delivery)
            span.set("workflow.outcome", "CONSUMED")

    async def _process(self, message: WorkflowExecutionMessage, delivery: RawDelivery) -> None:
        with start_span(
            "workflow.claim",
            attributes={"task.type": "WORKFLOW", "workflow.stage": "CLAIM"},
        ) as span:
            try:
                claim_message = message.model_copy(update={"traceparent": _traceparent(message)})
                claim = await self._control.claim(
                    claim_message,
                    consumer_name=self._settings.worker_consumer_name,
                    worker_id=self._settings.worker_id,
                    bundle_version=self._bundle_version,
                )
                span.set("workflow.outcome", claim.disposition)
            except ControlPlaneUnavailable as exc:
                span.fail(exc.code)
                await delivery.nack(requeue=True)
                self._record_failed()
                await self._stop_not_ready(exc.code)
                return
            except ControlPlaneRejected as exc:
                span.fail(exc.code)
                await delivery.reject()
                self._record_failed()
                return
        if claim.disposition in {"DUPLICATE", "TERMINAL"}:
            await delivery.ack()
            self._record_processed()
            return
        await delivery.ack()
        await self._execute_claimed(message, claim)

    async def _execute_claimed(
        self, message: WorkflowExecutionMessage, claim: WorkflowClaim
    ) -> None:
        execution_no = cast(int, claim.execution_no)
        token = cast(str, claim.execution_token)
        heartbeat: asyncio.Task[None] | None = None
        lease_lost = asyncio.Event()
        with start_span(
            "workflow.execution",
            attributes={
                "task.type": "WORKFLOW",
                "workflow.stage": "EXECUTE",
                "workflow.planRef": safe_ref(message.plan_id),
                "execution.attempt": execution_no,
                "agent.plan.version": message.plan_version,
            },
        ) as execution_span:
            try:
                with start_span(
                    "workflow.context.load",
                    attributes={
                        "task.type": "WORKFLOW",
                        "workflow.stage": "CONTEXT",
                        "context.source": "JAVA",
                    },
                ) as context_span:
                    try:
                        context = await self._control.context(
                            message.plan_id,
                            execution_no=execution_no,
                            execution_token=token,
                            traceparent=_traceparent(message),
                        )
                        context.assert_matches(message, execution_no)
                        context_span.set("workflow.outcome", "LOADED")
                    except (ControlPlaneUnavailable, ControlPlaneRejected) as exc:
                        context_span.fail(exc.code)
                        raise
                    except ValueError:
                        context_span.fail("WORKFLOW_CONTEXT_INVALID")
                        raise
                if context.resume_only:
                    with start_span(
                        "workflow.resume",
                        attributes={
                            "task.type": "WORKFLOW",
                            "workflow.stage": "RESUME",
                            "workflow.outcome": "RESUMED",
                        },
                    ):
                        pass
                heartbeat = asyncio.create_task(
                    self._heartbeat(message, execution_no, token, lease_lost),
                    name=f"workflow-heartbeat-{safe_ref(message.plan_id)}",
                )
                try:
                    completion_status = await self._run_steps(message, context, token, lease_lost)
                    if lease_lost.is_set():
                        execution_span.fail("WORKFLOW_LEASE_LOST")
                        return
                    if completion_status == "WAITING_TASK":
                        execution_span.set("workflow.outcome", "WAITING_TASK")
                        self._record_processed()
                        return
                    await self._complete(
                        message, execution_no, token, completion_status=completion_status
                    )
                    execution_span.set("workflow.outcome", completion_status)
                    self._record_processed()
                finally:
                    heartbeat.cancel()
                    with suppress(asyncio.CancelledError):
                        await heartbeat
            except ControlPlaneUnavailable as exc:
                execution_span.fail(exc.code)
                self._record_failed()
                await self._stop_not_ready(exc.code)
            except ControlPlaneRejected as exc:
                # A stale token or lost lease fences this execution. Never attempt
                # a second write with the same stale authority.
                execution_span.fail(exc.code)
                self._record_failed()
            except (HarnessViolation, ValueError, ToolExecutionError) as exc:
                code = getattr(exc, "code", "WORKFLOW_EXECUTION_INVALID")
                execution_span.fail(code)
                self._record_failed()
                if not lease_lost.is_set():
                    with suppress(ControlPlaneRejected, ControlPlaneUnavailable):
                        await self._complete(
                            message,
                            execution_no,
                            token,
                            completion_status="FAILED",
                            stable_error_code=code,
                        )

    async def _run_steps(
        self,
        message: WorkflowExecutionMessage,
        context: WorkflowExecutionContext,
        token: str,
        lease_lost: asyncio.Event,
    ) -> Literal["SUCCEEDED", "WAITING_INPUT", "WAITING_TASK"]:
        if any(step.status == "FAILED" for step in context.steps):
            raise ToolExecutionError("WORKFLOW_STEP_FAILED")
        if any(step.status == "WAITING" and step.type == "PREPARE" for step in context.steps):
            return "WAITING_TASK"
        if any(step.status == "WAITING" for step in context.steps):
            return "WAITING_INPUT"
        completed = {item.step_id for item in context.observations if item.status == "SUCCEEDED"}
        completed.update(
            step.id for step in context.steps if step.status in {"SUCCEEDED", "SKIPPED"}
        )
        counters = WorkflowBudgetCounter(context)
        executor, bindings = _executor(context)
        _restore_bindings(context, bindings)
        while len(completed) < len(context.steps):
            if lease_lost.is_set():
                raise ControlPlaneRejected("WORKFLOW_LEASE_LOST")
            step = next(
                (
                    item
                    for item in context.steps
                    if item.id not in completed
                    and item.status in {"PENDING", "READY", "RUNNING"}
                    and set(item.depends_on) <= completed
                ),
                None,
            )
            if step is None:
                raise ValueError("WORKFLOW_STEP_DEPENDENCY_BLOCKED")
            with start_span(
                "workflow.step",
                attributes={
                    "task.type": "WORKFLOW",
                    "workflow.stage": "STEP",
                    "workflow.stepId": step.id,
                    "workflow.stepType": step.type,
                    "tool.name": step.tool_name,
                    "tool.version": step.tool_version,
                },
            ) as step_span:
                try:
                    if step.type == "PREPARE":
                        definition = DEFAULT_TOOL_REGISTRY.resolve(
                            step.tool_name, step.tool_version
                        )
                        _workflow_policy().authorize(
                            definition, frozenset({ToolGroup.TASK_PREPARATION})
                        )
                        counters.before_provider_call()
                    if step.status in {"PENDING", "READY"}:
                        counters.before_step()
                    await self._control.step_state(
                        message.plan_id,
                        step.id,
                        execution_no=context.execution_no,
                        execution_token=token,
                        status="RUNNING",
                        event_id=stable_workflow_uuid(
                            message.plan_id, context.execution_no, step.id, "running"
                        ),
                        traceparent=_traceparent(message),
                    )
                    if step.type == "PREPARE":
                        await self._activate_prepare(message, context, step, token)
                        step_span.set("workflow.outcome", "WAITING_TASK")
                        return "WAITING_TASK"
                    observation = await self._execute_step(
                        message, context, step, counters, executor, bindings
                    )
                    with start_span(
                        "workflow.observation.handoff",
                        attributes={
                            "task.type": "WORKFLOW",
                            "workflow.stage": "OBSERVATION",
                            "workflow.outcome": observation.status,
                        },
                    ) as observation_span:
                        try:
                            accepted = await self._control.observation(
                                message.plan_id,
                                execution_no=context.execution_no,
                                execution_token=token,
                                observation=observation,
                                traceparent=_traceparent(message),
                            )
                            if accepted.disposition not in {"RECORDED", "DUPLICATE"}:
                                raise ControlPlaneRejected("WORKFLOW_OBSERVATION_REJECTED")
                        except (ControlPlaneRejected, ControlPlaneUnavailable) as exc:
                            observation_span.fail(exc.code)
                            raise
                    if observation.status != "SUCCEEDED":
                        code = observation.stable_error_code or "WORKFLOW_STEP_FAILED"
                        step_span.fail(code)
                        with start_span(
                            "workflow.replan",
                            attributes={
                                "task.type": "WORKFLOW",
                                "workflow.stage": "REPLAN",
                                "workflow.outcome": "NOT_SAFE_AFTER_FAILED_STEP",
                                "agent.replan.count": counters.replans,
                            },
                        ):
                            pass
                        raise ToolExecutionError(code)
                    completed.add(step.id)
                    step_span.set("workflow.outcome", "SUCCEEDED")
                except (ControlPlaneRejected, ControlPlaneUnavailable, HarnessViolation) as exc:
                    step_span.fail(exc.code)
                    raise
                except (ValueError, ToolExecutionError) as exc:
                    if not step_span.failed:
                        step_span.fail(getattr(exc, "code", "WORKFLOW_STEP_INVALID"))
                    raise
        return "SUCCEEDED"

    async def _activate_prepare(
        self,
        message: WorkflowExecutionMessage,
        context: WorkflowExecutionContext,
        step: WorkflowStep,
        token: str,
    ) -> None:
        task_draft_id = step.task_draft_id
        if task_draft_id is None or step.task_id is not None:
            raise ValueError("WORKFLOW_TASK_ACTIVATION_INVALID")
        # The activation idempotency key is intentionally independent of the lease
        # generation.  A timeout before Java receives the request may be retried by
        # a later process without creating a different logical activation.
        event_id = stable_workflow_uuid(message.plan_id, 0, step.id, "task-activation")
        with start_span(
            "workflow.task.activate",
            attributes={
                "task.type": "WORKFLOW",
                "workflow.stage": "ACTIVATE",
                "workflow.planRef": safe_ref(message.plan_id),
                "workflow.stepId": step.id,
                "execution.attempt": context.execution_no,
            },
        ) as activation_span:
            try:
                activation = await self._control.activate_task(
                    message.plan_id,
                    step.id,
                    execution_no=context.execution_no,
                    execution_token=token,
                    plan_version=context.plan_version,
                    task_draft_id=task_draft_id,
                    event_id=event_id,
                    traceparent=_traceparent(message),
                )
                activation_span.set("workflow.outcome", activation.disposition)
            except (ControlPlaneRejected, ControlPlaneUnavailable) as exc:
                activation_span.fail(exc.code)
                raise
        if activation.disposition not in {"ACTIVATED", "DUPLICATE"}:
            raise ControlPlaneRejected("WORKFLOW_TASK_ACTIVATION_REJECTED")
        with start_span(
            "workflow.wait",
            attributes={
                "task.type": "WORKFLOW",
                "workflow.stage": "WAIT",
                "workflow.outcome": "ENTERED",
                "workflow.planRef": safe_ref(message.plan_id),
                "workflow.stepId": step.id,
                "workflow.activationDisposition": activation.disposition,
                "task.ref": safe_ref(activation.task_id),
            },
        ):
            pass

    async def _execute_step(
        self,
        message: WorkflowExecutionMessage,
        context: WorkflowExecutionContext,
        step: WorkflowStep,
        counters: WorkflowBudgetCounter,
        executor: ToolExecutor,
        bindings: ReadOnlyToolBindings,
    ) -> WorkflowObservation:
        attempt = 1 + max(
            (item.step_attempt for item in context.observations if item.step_id == step.id),
            default=0,
        )
        event_id = stable_workflow_uuid(
            message.plan_id, context.execution_no, step.id, f"observation-{attempt}"
        )
        tool_call_id = stable_workflow_uuid(
            message.plan_id, context.execution_no, step.id, f"tool-{attempt}"
        )
        payload = _payload_for(step, context)
        try:
            result = await executor.execute(
                run_id=UUID(
                    stable_workflow_uuid(message.plan_id, context.execution_no, step.id, "run").hex
                ),
                name=step.tool_name,
                version=step.tool_version,
                payload=payload,
                counters=cast(RuntimeUsageCounters, counters),
                authorized_tool_groups=(ToolGroup.TASK_PREPARATION,),
                step_number=step.ordinal,
            )
        except (ToolExecutionError, HarnessViolation) as exc:
            return WorkflowObservation(
                id=message.plan_id,
                step_id=step.id,
                event_id=event_id,
                tool_call_id=tool_call_id,
                step_attempt=attempt,
                status="FAILED",
                result_type="TOOL_EXECUTION_FAILED",
                summary="Bounded workflow tool execution failed.",
                facts={},
                stable_error_code=getattr(exc, "code", "TOOL_EXECUTION_FAILED"),
                retryable=False,
                completed_at=datetime.now(UTC),
            )
        bindings.remember(step.id, result.output)
        output = result.output.model_dump(mode="json", by_alias=True)
        return WorkflowObservation(
            id=message.plan_id,
            step_id=step.id,
            event_id=event_id,
            # Java idempotency must survive a Worker restart.  ToolExecutor's local
            # invocation UUID is intentionally process-local telemetry; the control
            # plane receives the deterministic execution/step/attempt UUID instead.
            tool_call_id=tool_call_id,
            step_attempt=attempt,
            status="SUCCEEDED",
            result_type="TOOL_OBSERVATION",
            result_ref=step.output_ref or result.observation.output_ref,
            summary="Bounded workflow tool completed.",
            facts={"output": output},
            retryable=False,
            completed_at=datetime.now(UTC),
        )

    async def _heartbeat(
        self,
        message: WorkflowExecutionMessage,
        execution_no: int,
        token: str,
        lease_lost: asyncio.Event,
    ) -> None:
        while True:
            await asyncio.sleep(self._heartbeat_seconds)
            with start_span(
                "workflow.lease.heartbeat",
                attributes={
                    "task.type": "WORKFLOW",
                    "workflow.stage": "LEASE",
                },
            ) as span:
                try:
                    await self._control.heartbeat(
                        message.plan_id,
                        execution_no=execution_no,
                        execution_token=token,
                        stage_code="WORKFLOW_EXECUTING",
                        traceparent=_traceparent(message),
                    )
                    span.set("workflow.outcome", "RENEWED")
                except (ControlPlaneRejected, ControlPlaneUnavailable) as exc:
                    span.fail(exc.code)
                    lease_lost.set()
                    return

    async def _consume(self) -> None:
        try:
            while self.is_ready and self._broker.accepting:
                delivery = await self._broker.get()
                if delivery is None:
                    return
                task = asyncio.create_task(self._bounded(delivery))
                self._in_flight.add(task)
                task.add_done_callback(self._in_flight.discard)
        except MessageBrokerUnavailable as exc:
            await self._mark_broker_unavailable(exc)

    async def _bounded(self, delivery: RawDelivery) -> None:
        async with self._semaphore:
            adjust_worker_inflight(1, task_type="WORKFLOW")
            try:
                await self.process_delivery(delivery)
            except MessageBrokerUnavailable as exc:
                await self._mark_broker_unavailable(exc)
            except Exception:
                await self._handle_poison_delivery(delivery)
            finally:
                adjust_worker_inflight(-1, task_type="WORKFLOW")

    async def _handle_poison_delivery(self, delivery: RawDelivery) -> None:
        try:
            disposition = await settle_poison_delivery(delivery)
        except MessageBrokerUnavailable as exc:
            await self._mark_broker_unavailable(exc)
            return
        self._record_failed()
        message: WorkflowExecutionMessage | None = None
        with suppress(ValidationError, ValueError):
            message = WorkflowExecutionMessage.model_validate_json(delivery.body)
        logger.error(
            "workflow_delivery_failed",
            extra={
                "traceId": None,
                "taskId": None if message is None else safe_ref(message.plan_id),
                "agentRunId": None,
                "errorCode": (
                    "WORKFLOW_DELIVERY_POISONED"
                    if disposition == "REJECTED"
                    else "WORKFLOW_DELIVERY_FAILED"
                ),
            },
        )

    async def _complete(
        self,
        message: WorkflowExecutionMessage,
        execution_no: int,
        token: str,
        *,
        completion_status: Literal["SUCCEEDED", "FAILED", "WAITING_INPUT"],
        stable_error_code: str | None = None,
    ) -> None:
        with start_span(
            "workflow.complete",
            attributes={
                "task.type": "WORKFLOW",
                "workflow.stage": "COMPLETE",
                "workflow.outcome": completion_status,
            },
        ) as span:
            try:
                await self._control.complete(
                    message.plan_id,
                    execution_no=execution_no,
                    execution_token=token,
                    status=completion_status,
                    stable_error_code=stable_error_code,
                    event_id=stable_workflow_uuid(
                        message.plan_id,
                        execution_no,
                        "step-0",
                        "failed" if completion_status == "FAILED" else "complete",
                    ),
                    traceparent=_traceparent(message),
                )
            except (ControlPlaneRejected, ControlPlaneUnavailable) as exc:
                span.fail(exc.code)
                raise

    def _record_processed(self) -> None:
        self._processed += 1
        record_worker_task("PROCESSED", task_type="WORKFLOW")

    def _record_failed(self) -> None:
        self._failed += 1
        record_worker_task("FAILED", task_type="WORKFLOW")

    async def _mark_broker_unavailable(self, error: MessageBrokerUnavailable) -> None:
        if not await self._broker.invalidate(error.generation):
            return
        await self._stop_not_ready("RABBITMQ_UNAVAILABLE")

    async def _stop_not_ready(self, code: str) -> None:
        async with self._dependency_recovery_lock:
            if self._status in {"DRAINING", "STOPPED"}:
                return
            self._status, self._reason = "NOT_READY", code
            self._dependency_generation += 1
            await self._broker.stop_accepting()
            self._ensure_dependency_recovery()

    def _ensure_dependency_recovery(self) -> None:
        if self._status in {"DRAINING", "STOPPED"}:
            return
        if self._dependency_recovery_task is not None and not self._dependency_recovery_task.done():
            return
        task = asyncio.create_task(
            self._recover_dependencies(), name="workflow-dependency-recovery"
        )
        self._dependency_recovery_task = task
        task.add_done_callback(self._dependency_recovery_finished)

    def _dependency_recovery_finished(self, task: asyncio.Task[None]) -> None:
        if self._dependency_recovery_task is not task:
            return
        self._dependency_recovery_task = None
        if not task.cancelled() and task.exception() is not None:
            logger.error(
                "workflow_dependency_recovery_failed",
                extra={"errorCode": "WORKFLOW_DEPENDENCY_RECOVERY_FAILED"},
            )
        if self._status == "NOT_READY":
            self._ensure_dependency_recovery()

    async def _recover_dependencies(self) -> None:
        consumer = self._consumer
        if consumer is not None and consumer is not asyncio.current_task():
            await asyncio.gather(asyncio.shield(consumer), return_exceptions=True)
        self._consumer = None
        delay = self._dependency_recovery_base_seconds
        while self._status not in {"DRAINING", "STOPPED"}:
            await asyncio.sleep(delay)
            generation = self._dependency_generation
            try:
                control_ready = await self._control.probe()
            except (ControlPlaneUnavailable, ControlPlaneRejected) as exc:
                self._reason = exc.code
            else:
                if not control_ready:
                    self._reason = "WORKFLOW_JAVA_CONTROL_UNAVAILABLE"
                else:
                    async with self._dependency_recovery_lock:
                        if generation != self._dependency_generation:
                            continue
                        try:
                            await self._broker.restart()
                        except MessageBrokerUnavailable:
                            self._reason = "RABBITMQ_UNAVAILABLE"
                        else:
                            if self._status in {"DRAINING", "STOPPED"}:
                                return
                            self._status, self._reason = "READY", None
                            self._consumer = asyncio.create_task(
                                self._consume(), name="workflow-worker-consumer"
                            )
                            return
            delay = min(delay * 2, self._dependency_recovery_max_seconds)

    def status_snapshot(self) -> WorkerStatusSnapshot:
        return WorkerStatusSnapshot(
            status=self._status,
            service=f"{self._settings.service_name}-worker",
            service_version=self._settings.service_version,
            worker_id=self._settings.worker_id,
            task_type="WORKFLOW",
            control_plane="JAVA",
            provider="TOOL_RUNTIME",
            provider_model=self._bundle_version,
            mock_mode=False,
            accepting=self.is_ready and self._broker.accepting,
            queue_depth=self._broker.depth,
            in_flight=len(self._in_flight),
            processed=self._processed,
            failed=self._failed,
            reason_code=self._reason,
        )

    async def close(self) -> None:
        self._status = "DRAINING"
        recovery_task = self._dependency_recovery_task
        if recovery_task is not None:
            recovery_task.cancel()
            await asyncio.gather(recovery_task, return_exceptions=True)
            self._dependency_recovery_task = None
        await self._broker.stop_accepting()
        if self._consumer is not None:
            self._consumer.cancel()
            with suppress(asyncio.CancelledError):
                await self._consumer
        if self._in_flight:
            done, pending = await asyncio.wait(
                self._in_flight, timeout=self._settings.worker_graceful_timeout_seconds
            )
            del done
            for task in pending:
                task.cancel()
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)
        await self._broker.aclose()
        await self._control.aclose()
        self._status = "STOPPED"


def _workflow_policy() -> ToolPolicy:
    return ToolPolicy(
        allowed_tool_names=_ALLOWED_TOOLS,
        allowed_tool_groups=frozenset({ToolGroup.TASK_PREPARATION}),
        allowed_risk_levels=frozenset({RiskLevel.QUERY, RiskLevel.ANALYZE, RiskLevel.PREPARE}),
    )


def _executor(context: WorkflowExecutionContext) -> tuple[ToolExecutor, ReadOnlyToolBindings]:
    authorized = authorized_run_context_from_snapshot(context.authorized_context)
    bindings = ReadOnlyToolBindings(authorized)
    names = _ALLOWED_TOOLS - {"task_draft.propose"}
    authorized_groups = frozenset(ToolGroup(item) for item in authorized.loaded_tool_groups)
    handlers = {name: handler for name, handler in bindings.handlers.items() if name in names}
    return ToolExecutor(
        DEFAULT_TOOL_REGISTRY,
        ToolPolicy(
            allowed_tool_names=names,
            allowed_tool_groups=authorized_groups,
            allowed_risk_levels=frozenset({RiskLevel.QUERY, RiskLevel.ANALYZE}),
        ),
        {name: identity_input for name in names},
        handlers,
    ), bindings


_OUTPUT_MODELS: dict[str, type[BaseModel]] = {
    "context.catalog.inspect": CatalogInspectOutput,
    "context.memory.search": MemorySearchOutput,
    "context.task_workspace.inspect": TaskWorkspaceInspectOutput,
    "analysis.competitor.compare": CompetitorCompareOutput,
    "analysis.evidence.aggregate": EvidenceAggregateOutput,
}


def _restore_bindings(context: WorkflowExecutionContext, bindings: ReadOnlyToolBindings) -> None:
    """Rebuild only derived tool state from Java-persisted observations/results."""
    by_id = {step.id: step for step in context.steps}
    for observation in context.observations:
        if observation.status != "SUCCEEDED":
            continue
        step = by_id[observation.step_id]
        raw = observation.facts.get("output")
        output_model = _OUTPUT_MODELS.get(step.tool_name)
        if output_model is not None and isinstance(raw, dict):
            try:
                bindings.remember(step.id, output_model.model_validate(raw))
                continue
            except ValidationError:
                pass
        reference = observation.result_ref
        if reference is not None:
            bindings.remember(
                step.id,
                EvidenceAggregateOutput(
                    facts=(f"observationRef={reference}",), inferences=(), missing=()
                ),
            )
    for step in context.steps:
        if step.task_result_ref is not None:
            bindings.remember(
                step.id,
                EvidenceAggregateOutput(
                    facts=(f"taskResultRef={step.task_result_ref}",),
                    inferences=(),
                    missing=(),
                ),
            )


def _payload_for(step: WorkflowStep, context: WorkflowExecutionContext) -> dict[str, object]:
    if step.tool_name == "context.catalog.inspect":
        return {"entities": ["brand", "space", "product", "competitors"], "maxItems": 6}
    if step.tool_name == "context.memory.search":
        current_goal = context.authorized_context.task_workspace.current_goal.strip()
        query = (current_goal or context.plan.goal_summary)[:200]
        return {"query": query, "limit": 5}
    if step.tool_name == "context.task_workspace.inspect":
        return {
            "fields": [
                "currentGoal",
                "currentIntent",
                "confirmedConstraints",
                "unresolvedQuestions",
                "pendingInteraction",
                "activeDraftId",
                "activeTaskId",
                "runningTasks",
                "referenceAssetIds",
            ]
        }
    if step.tool_name == "analysis.competitor.compare":
        return {
            "dimensions": ["sellingPoints", "audience", "category", "parameters"],
            "maxCompetitors": min(max(len(context.authorized_context.competitors), 1), 5),
        }
    if step.tool_name == "analysis.evidence.aggregate":
        return {"stepIds": list(step.depends_on[-4:]) or ["step-1"]}
    raise ToolExecutionError("WORKFLOW_TOOL_NOT_EXECUTABLE")


def _traceparent(message: WorkflowExecutionMessage) -> str:
    """Use the active child span for Java calls while retaining the inbound root."""
    return current_otel_traceparent() or message.traceparent


__all__ = ["WorkflowBudgetCounter", "WorkflowWorkerRuntime"]
