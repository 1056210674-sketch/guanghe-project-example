"""In-memory local Chat Run lifecycle and stable public event streaming."""

import asyncio
import logging
from collections import OrderedDict
from collections.abc import AsyncIterator
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime
from time import monotonic
from types import MappingProxyType
from typing import ClassVar, cast
from uuid import UUID, uuid4

from opentelemetry.trace import SpanKind
from pydantic import JsonValue

from app.agent.explicit_actions import ExplicitActionAdapter
from app.agent.identity import (
    GEO_CAPABILITIES,
    AgentIdentityError,
    bind_agent_execution,
    resolve_execution,
)
from app.agent.loop import NativeAgentLoop
from app.agent.models import (
    BusinessOutcome,
    ChatRunEvent,
    ChatRunRequest,
    ChatRunSnapshot,
    EventType,
    Intent,
    RunOutcome,
    RunProgressPayload,
    RunProgressStage,
    RunProgressStatus,
    RunStatus,
    Usage,
)
from app.agent.summary import SummaryTextAdapter
from app.agents.behavior import NativeBehavior
from app.agents.catalog import RuntimeCatalog, load_catalog
from app.agents.geo import GeoBehavior
from app.common.enums import ProviderStatus
from app.common.runtime import RuntimeState
from app.geo.providers import BochaSearchAdapter
from app.harness.runtime import RuntimeHarness, bind_runtime_usage
from app.integration.java_internal import (
    JavaAgentActionClientProtocol,
    JavaWorkflowPlanClientProtocol,
)
from app.model_gateway.gateway import DeltaHandler, ModelGateway
from app.model_gateway.router import (
    RouterCapabilityState,
    RouterMode,
)
from app.observability.trace import child_trace_context, current_trace_id, resolve_trace_context
from app.observability.tracing import bind_trace_attributes, safe_ref, start_span
from app.providers.java_context import AuthorizedRunContext, RunContextProvider

logger = logging.getLogger(__name__)


class RunManagerError(RuntimeError):
    pass


class RunNotFoundError(RunManagerError):
    pass


class DuplicateRunError(RunManagerError):
    pass


class RunCapacityError(RunManagerError):
    pass


class GatewayUnavailableError(RunManagerError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class AuthorizedContextUnavailableError(RunManagerError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class _RunStoppedByUser(Exception):
    pass


@dataclass(slots=True)
class _RunRecord:
    request: ChatRunRequest
    trace_id: str
    traceparent: str
    business_outcome_output_enabled: bool = False
    business_outcome_authorized: bool = False
    protocol_features_frozen: bool = False
    business_outcome: BusinessOutcome | None = None
    status: RunStatus = RunStatus.QUEUED
    events: list[ChatRunEvent] = field(default_factory=lambda: list[ChatRunEvent]())
    stop_requested: asyncio.Event = field(default_factory=asyncio.Event)
    changed: asyncio.Condition = field(default_factory=asyncio.Condition)
    lifecycle_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    task: asyncio.Task[None] | None = None
    usage_emitted: bool = False
    agent_key: str | None = None
    agent_version: str | None = None
    domain_graph: str | None = None
    progress_started_at: float = field(default_factory=monotonic)
    progress_ordinal: int = 0
    progress_active_stage: RunProgressStage | None = None
    progress_active_ordinal: int | None = None
    progress_last_elapsed_ms: int = 0
    progress_finished_stages: set[RunProgressStage] = field(
        default_factory=lambda: set[RunProgressStage]()
    )
    progress_event_keys: set[tuple[RunProgressStage, RunProgressStatus, int]] = field(
        default_factory=lambda: set[tuple[RunProgressStage, RunProgressStatus, int]]()
    )


class ChatRunManager:
    """Own only ephemeral local Run state; never represent Java business truth."""

    _terminal_statuses: ClassVar[frozenset[RunStatus]] = frozenset(
        (RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.STOPPED)
    )

    def __init__(
        self,
        runtime: RuntimeState,
        gateway: ModelGateway,
        context_provider: RunContextProvider,
        java_client: JavaWorkflowPlanClientProtocol | None = None,
        *,
        catalog: RuntimeCatalog | None = None,
    ) -> None:
        if runtime.bundle is None:
            raise ValueError("A ready Prompt Bundle is required")
        self._runtime = runtime
        self._catalog = catalog or load_catalog()
        self._catalog.validate_bundle(runtime.bundle)
        self._gateway = gateway
        self._explicit_actions = ExplicitActionAdapter(gateway)
        self._context_provider = context_provider
        self._web_search = BochaSearchAdapter(runtime.settings.geo_bocha)
        self._prompts = {prompt.code: prompt for prompt in runtime.bundle.bundle.prompts}
        adapters: dict[tuple[str, str], NativeAgentLoop | SummaryTextAdapter] = {}
        for key, registration in self._catalog.agents.items():
            prompt = self._prompts[registration.prompt_code]
            if registration.runtime_kind == "summary":
                adapters[key] = SummaryTextAdapter(gateway, prompt.system)
                continue
            # These are closed code-owned policy names, not dynamic imports or URLs.
            adapters[key] = NativeAgentLoop(
                gateway,
                cast(JavaAgentActionClientProtocol, java_client),
                prompt.system,
                search=self._web_search if "web.search" in registration.allowed_tools else None,
                article_review_prompt=self._prompts["ARTICLE_ACTION_REVIEW_V1"].system
                if "article.generate" in registration.allowed_tools
                else None,
                creative_review_prompt=self._prompts["CREATIVE_ACTION_REVIEW_V1"].system
                if {"image.generate", "video.generate"} & set(registration.allowed_tools)
                else None,
                intent=Intent(registration.intent),
                context_behavior=GeoBehavior()
                if registration.context_policy == "geo-brand"
                else NativeBehavior(),
                continuation_behavior=GeoBehavior()
                if registration.continuation_policy == "geo-article"
                else NativeBehavior(),
            )
        self._adapters = MappingProxyType(adapters)
        self._harness = RuntimeHarness()
        self._records: OrderedDict[UUID, _RunRecord] = OrderedDict()
        self._records_lock = asyncio.Lock()
        self._probe_lock = asyncio.Lock()

    @property
    def gateway_status(self) -> ProviderStatus:
        return self._gateway.status

    @property
    def web_search(self) -> BochaSearchAdapter:
        """The process-owned search adapter, shared by signed preparation requests."""
        return self._web_search

    @property
    def gateway_error_code(self) -> str | None:
        return self._gateway.last_error_code

    @property
    def gateway_provider(self) -> str:
        return self._gateway.identity.provider

    @property
    def gateway_model(self) -> str:
        return self._gateway.identity.model

    @property
    def is_mock(self) -> bool:
        return self._gateway.is_mock

    @property
    def is_ready(self) -> bool:
        return self.gateway_status is ProviderStatus.AVAILABLE and self.context_ready

    @property
    def router_capability(self) -> RouterCapabilityState:
        return RouterCapabilityState(
            provider=self.gateway_provider,
            model=self.gateway_model,
            mode=RouterMode.NATIVE_LOOP,
            status=self.gateway_status,
            available=self.gateway_status is ProviderStatus.AVAILABLE,
            schema_version="native-tool-loop-v1",
            reason_code=self.gateway_error_code,
        )

    @property
    def router_status(self) -> ProviderStatus:
        return self.gateway_status

    @property
    def router_error_code(self) -> str | None:
        return self.gateway_error_code

    @property
    def context_ready(self) -> bool:
        return self._context_provider.is_ready

    @property
    def context_error_code(self) -> str | None:
        return self._context_provider.reason_code

    async def initialize(self) -> bool:
        gateway_ready = await self._gateway.probe()
        return gateway_ready

    async def start(
        self,
        request: ChatRunRequest,
        *,
        traceparent: str | None = None,
    ) -> ChatRunSnapshot:
        if not self.context_ready:
            raise AuthorizedContextUnavailableError(
                self.context_error_code or "JAVA_RUN_CONTEXT_NOT_READY"
            )
        if not self._gateway_is_ready():
            async with self._probe_lock:
                if not self._gateway_is_ready() and not await self._gateway.probe():
                    raise GatewayUnavailableError(
                        self.gateway_error_code or "MODEL_GATEWAY_UNAVAILABLE"
                    )
        async with self._records_lock:
            if request.run_id in self._records:
                raise DuplicateRunError("Run ID already exists")
            self._evict_terminal_runs()
            if len(self._records) >= self._runtime.settings.max_local_runs:
                raise RunCapacityError("Local Run capacity is full")
            trace = (
                resolve_trace_context(traceparent)
                if traceparent is not None
                else child_trace_context()
            )
            record = _RunRecord(
                request=request,
                trace_id=current_trace_id() or trace.trace_id,
                traceparent=trace.traceparent,
                business_outcome_output_enabled=self._runtime.settings.business_outcome_enabled,
            )
            self._records[request.run_id] = record
            record.task = asyncio.create_task(
                self._execute(record), name=f"chat-run-{request.run_id}"
            )
        return self._snapshot(record)

    def _gateway_is_ready(self) -> bool:
        return self.gateway_status is ProviderStatus.AVAILABLE

    def _router_is_ready(self) -> bool:
        return self.router_status is ProviderStatus.AVAILABLE

    def get(self, run_id: UUID) -> ChatRunSnapshot:
        return self._snapshot(self._record(run_id))

    async def stop(self, run_id: UUID) -> tuple[bool, RunStatus]:
        record = self._record(run_id)
        async with record.lifecycle_lock:
            if record.status in self._terminal_statuses:
                return False, record.status
            if record.stop_requested.is_set():
                return True, record.status
            record.stop_requested.set()
            if record.status is RunStatus.RUNNING and record.task is not None:
                record.task.cancel()
            return True, record.status

    def events(self, run_id: UUID, after_sequence: int = 0) -> AsyncIterator[ChatRunEvent]:
        """Bind a stream to its record before an HTTP streaming response starts."""

        record = self._record(run_id)
        return self._events_for_record(record, after_sequence)

    async def _events_for_record(
        self,
        record: _RunRecord,
        after_sequence: int,
    ) -> AsyncIterator[ChatRunEvent]:
        cursor = after_sequence
        while True:
            async with record.changed:
                pending = tuple(event for event in record.events if event.sequence > cursor)
                terminal = record.status in self._terminal_statuses
                if not pending and not terminal:
                    await record.changed.wait()
                    continue
            for event in pending:
                cursor = event.sequence
                yield event
            if terminal:
                return

    async def close(self) -> None:
        tasks = tuple(
            record.task
            for record in self._records.values()
            if record.task is not None and not record.task.done()
        )
        for record in self._records.values():
            already_stopping = record.stop_requested.is_set()
            record.stop_requested.set()
            if (
                not already_stopping
                and record.status is RunStatus.RUNNING
                and record.task is not None
            ):
                record.task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        await self._gateway.aclose()
        await self._web_search.aclose()

    async def _execute(self, record: _RunRecord) -> None:
        bundle = self._runtime.bundle
        attributes = {
            "run.ref": safe_ref(record.request.run_id),
            "conversation.ref": safe_ref(record.request.conversation_id),
            "prompt.version": bundle.bundle.bundle_version if bundle is not None else "unknown",
            "model.provider": self.gateway_provider,
            "model.name": self.gateway_model,
        }
        trace_attributes = {
            "langfuse.session.id": safe_ref(record.request.conversation_id),
            "langfuse.trace.name": "python.chat.run",
            "langfuse.release": self._runtime.git_commit or self._runtime.settings.service_version,
            "langfuse.version": bundle.bundle.bundle_version if bundle is not None else "unknown",
            "langfuse.trace.metadata.run_ref": safe_ref(record.request.run_id),
            "langfuse.trace.metadata.prompt_bundle_version": (
                bundle.bundle.bundle_version if bundle is not None else "unknown"
            ),
            "langfuse.trace.metadata.prompt_bundle_hash": (
                bundle.bundle_hash if bundle is not None else "unknown"
            ),
            "langfuse.trace.metadata.prompt_hash": (
                bundle.prompt_hash if bundle is not None else "unknown"
            ),
        }
        with (
            bind_trace_attributes(trace_attributes),
            start_span(
                "python.chat.run",
                parent_traceparent=record.traceparent,
                kind=SpanKind.SERVER,
                attributes=attributes,
            ) as span,
        ):
            await self._execute_run(record)
            span.set("run.status", record.status.value)
            if record.business_outcome is not None:
                span.set("run.business.status", record.business_outcome.status.value)
                if record.business_outcome.reason_code is not None:
                    span.set("run.business.reason", record.business_outcome.reason_code)
                if record.business_outcome.failure_stage is not None:
                    span.set("run.business.failure_stage", record.business_outcome.failure_stage)
            if record.status is RunStatus.FAILED:
                code = (
                    str(record.events[-1].payload.get("code"))
                    if record.events
                    else "AGENT_RUN_FAILED"
                )
                span.fail(code)

    async def _execute_run(self, record: _RunRecord) -> None:
        try:
            if record.stop_requested.is_set():
                await self._finish_stopped(record)
                return
            record.status = RunStatus.RUNNING
            bundle = self._runtime.bundle
            if bundle is None:
                raise RuntimeError("Prompt Bundle became unavailable")
            await self._emit(
                record,
                "run.started",
                {
                    "status": RunStatus.RUNNING.value,
                    "conversationId": record.request.conversation_id,
                    "traceId": record.trace_id,
                    "bundleVersion": bundle.bundle.bundle_version,
                    "promptHash": bundle.prompt_hash,
                    "modelVersion": (
                        f"{self.gateway_provider}/{self.gateway_model}:"
                        f"{self._gateway.identity.version}"
                    ),
                    "mockMode": self.is_mock,
                },
            )
            streamed = False
            pending_delta = ""
            last_delta_at = 0.0

            async def flush_delta() -> None:
                nonlocal pending_delta, last_delta_at
                if pending_delta:
                    await self._emit(
                        record,
                        "message.delta",
                        {
                            "messageId": record.request.message.message_id,
                            "delta": pending_delta,
                        },
                    )
                    pending_delta = ""
                    last_delta_at = monotonic()

            async def emit_model_delta(delta: str) -> None:
                nonlocal streamed, pending_delta
                if record.stop_requested.is_set():
                    raise _RunStoppedByUser
                if not delta:
                    return
                streamed = True
                pending_delta += delta
                # Flush the first token immediately, then coalesce real provider chunks
                # to avoid one Java database transaction per token. Never fake typing.
                if len(pending_delta) >= 256 or monotonic() - last_delta_at >= 0.08:
                    await flush_delta()

            async def replace_model_text(text: str) -> None:
                nonlocal streamed, pending_delta, last_delta_at
                if record.stop_requested.is_set():
                    raise _RunStoppedByUser
                pending_delta = ""
                streamed = True
                last_delta_at = monotonic()
                await self._emit(
                    record,
                    "message.delta",
                    {
                        "messageId": record.request.message.message_id,
                        "delta": text,
                        "replace": True,
                    },
                )

            try:
                outcome = await self._invoke_agent(record, emit_model_delta, replace_model_text)
            finally:
                # Keep already generated partial text on interruption, before the terminal
                # event. Protocol resets above intentionally discard rejected previews.
                await flush_delta()
            if not streamed:
                for delta in self._chunks(outcome.message):
                    if await self._stop_or_delay(record):
                        await self._finish_stopped(record, outcome.usage)
                        return
                    await self._emit(
                        record,
                        "message.delta",
                        {"messageId": record.request.message.message_id, "delta": delta},
                    )
            await self._finish_outcome(record, outcome)
        except _RunStoppedByUser as exc:
            await self._finish_stopped(record, getattr(exc, "usage", None))
        except asyncio.CancelledError as exc:
            if not record.stop_requested.is_set():
                raise
            # Cancel the blocked provider read, not just the next token callback.
            # This stops only this response; no paid task cancellation is issued.
            await self._finish_stopped(record, getattr(exc, "usage", None))
        except Exception as exc:
            failed = await self._finish_failed_or_stopped(record, exc)
            if failed:
                logger.error(
                    "chat_run_failed",
                    extra={
                        "traceId": record.trace_id,
                        "taskId": record.request.trusted_state.task_id,
                        "agentRunId": str(record.request.run_id),
                        "stableErrorCode": self._safe_error_code(exc),
                        "agentKey": record.agent_key,
                        "agentVersion": record.agent_version,
                        "failureStage": getattr(exc, "failure_stage", None),
                        "attemptCount": getattr(exc, "attempt_count", None),
                        "status": RunStatus.FAILED.value,
                    },
                )

    async def _invoke_agent(
        self,
        record: _RunRecord,
        on_delta: DeltaHandler,
        on_replace: DeltaHandler | None = None,
    ) -> RunOutcome:
        counters = self._harness.new_usage_counters(record.request)
        try:
            with bind_runtime_usage(counters):
                async with self._harness.enforce_deadline(record.request):
                    estimated = self._harness.validate_request(record.request)
                    counters.observe_estimated_input_tokens(estimated)
                    # Fetch authorization exactly once, before adapter selection. Never
                    # cache this context on a process-wide adapter or shared provider.
                    with start_span(
                        "agent.context.load",
                        attributes={"context.loadCount": counters.context_load_count + 1},
                    ) as span:
                        context = await self._context_provider.load(record.request)
                        span.set("context.source", context.source)
                    self._freeze_protocol_features(record, context)
                    identity = context.agent_identity
                    if identity is not None and identity.installation_id is None:
                        raise AgentIdentityError("AGENT_INSTALLATION_REQUIRED")
                    execution = resolve_execution(
                        "geo" if identity is None else identity.agent_key,
                        "1.0.0" if identity is None else identity.agent_version,
                        tuple(GEO_CAPABILITIES) if identity is None else identity.capabilities,
                        catalog=self._catalog,
                        execution_release=None if identity is None else identity.execution_release,
                        catalog_hash=None if identity is None else identity.catalog_hash,
                    )
                    record.agent_key = execution.agent_key
                    record.agent_version = execution.agent_version
                    if "resources.read" not in execution.capabilities:
                        context = replace(
                            context,
                            selected_space_id=None,
                            selected_product_id=None,
                            attachment_asset_ids=(),
                            space=None,
                            product=None,
                            competitors=(),
                            conversation_memory=None,
                            task_workspace=None,
                            resource_selection=None,
                            last_read_context=None,
                            pending_resource_context=None,
                            last_report_read_context=None,
                            geo_insight_context=None,
                            knowledge_context=None,
                            monitor_action_context=None,
                            article_preparation_mode=None,
                            task_preparation_constraints=None,
                            loaded_tool_groups=(),
                        )
                    elif "geo.read" not in execution.capabilities:
                        context = replace(
                            context, geo_insight_context=None, last_report_read_context=None
                        )
                    if "memory.read" not in execution.capabilities and context.knowledge_context:
                        context = replace(
                            context,
                            knowledge_context=context.knowledge_context.model_copy(
                                update={"memory": None, "preferences": ()}
                            ),
                        )
                    adapter: NativeAgentLoop | SummaryTextAdapter | ExplicitActionAdapter = (
                        self._adapters[(execution.agent_key, execution.agent_version)]
                    )
                    registration = execution.registration
                    # Existing explicit paid-entry cards keep their frozen admission protocol.
                    # Ordinary GEO conversations always use the native tool loop.
                    explicit_action = (
                        "ARTICLE_FULL_V1"
                        if context.article_preparation_mode is not None
                        else "MONITOR_RUN"
                        if context.monitor_action_context is not None
                        else None
                    )
                    if explicit_action is not None:
                        if explicit_action not in registration.explicit_actions:
                            raise AgentIdentityError("AGENT_CAPABILITY_DENIED")
                        adapter = self._explicit_actions
                    prompt = self._prompts[registration.prompt_code]
                    trace_identity = {
                        "agent.key": execution.agent_key,
                        "agent.version": execution.agent_version,
                        "agent.legacy": identity is None,
                        "agent.execution_release": registration.execution_release,
                        "agent.catalog_hash": self._catalog.catalog_hash,
                        "langfuse.trace.metadata.agent_key": execution.agent_key,
                        "langfuse.trace.metadata.agent_version": execution.agent_version,
                        "langfuse.trace.metadata.profile_code": prompt.code,
                        "langfuse.trace.metadata.profile_version": prompt.version,
                    }
                    with bind_agent_execution(execution), bind_trace_attributes(trace_identity):

                        async def progress(
                            stage: RunProgressStage,
                            status: RunProgressStatus,
                            current: int | None,
                            total: int | None,
                        ) -> None:
                            await self._emit_progress(
                                record,
                                stage,
                                status,
                                current=current,
                                total=total,
                            )

                        if isinstance(adapter, NativeAgentLoop):
                            outcome = await adapter.ainvoke(
                                record.request,
                                authorized_context=context,
                                usage_counters=counters,
                                on_delta=None if self.is_mock else on_delta,
                                on_replace=None if self.is_mock else on_replace,
                                on_progress=progress,
                            )
                        else:
                            outcome = await adapter.ainvoke(
                                record.request,
                                authorized_context=context,
                                usage_counters=counters,
                                on_delta=None if self.is_mock else on_delta,
                                on_progress=progress,
                            )
                        record.domain_graph = adapter.graph_for_intent(outcome.intent)
                        return outcome
        except (Exception, asyncio.CancelledError) as exc:
            if not isinstance(getattr(exc, "usage", None), Usage):
                exc.usage = counters.usage(  # type: ignore[attr-defined]
                    provider=self.gateway_provider, model=self.gateway_model
                )
            raise

    async def _finish_outcome(self, record: _RunRecord, outcome: RunOutcome) -> None:
        async with record.lifecycle_lock:
            if record.status in self._terminal_statuses:
                return
            if record.stop_requested.is_set():
                await self._finish_stopped_locked(record, outcome.usage)
                return
            await self._emit_outcome(record, outcome)
            await self._emit_usage(record, outcome)
            record.business_outcome = outcome.business_outcome
            record.status = RunStatus.COMPLETED
            await self._emit(
                record,
                "run.completed",
                {
                    "status": RunStatus.COMPLETED.value,
                    "intent": outcome.intent.value,
                    "domainGraph": record.domain_graph or "native-tool-loop-v1",
                    **({"geoContextUsed": True} if outcome.geo_context_used else {}),
                    **(
                        {
                            "businessOutcome": cast(
                                JsonValue,
                                outcome.business_outcome.model_dump(
                                    mode="json", by_alias=True, exclude_none=True
                                ),
                            )
                        }
                        if record.business_outcome_output_enabled
                        and record.business_outcome_authorized
                        and outcome.business_outcome is not None
                        else {}
                    ),
                },
            )

    @staticmethod
    def _freeze_protocol_features(record: _RunRecord, context: AuthorizedRunContext) -> None:
        if record.protocol_features_frozen:
            return
        record.protocol_features_frozen = True
        record.business_outcome_authorized = bool(
            context.protocol_features is not None and context.protocol_features.business_outcome
        )

    async def _finish_failed_or_stopped(
        self,
        record: _RunRecord,
        exc: Exception,
    ) -> bool:
        async with record.lifecycle_lock:
            if record.status in self._terminal_statuses:
                return record.status is RunStatus.FAILED
            if record.stop_requested.is_set():
                await self._finish_stopped_locked(record, getattr(exc, "usage", None))
                return False
            usage = getattr(exc, "usage", None)
            if isinstance(usage, Usage):
                await self._emit_usage_payload(record, usage)
            await self._fail_active_progress(record)
            record.status = RunStatus.FAILED
            await self._emit(
                record,
                "run.failed",
                {"status": RunStatus.FAILED.value, "code": self._safe_error_code(exc)},
            )
            return True

    async def _emit_outcome(self, record: _RunRecord, outcome: RunOutcome) -> None:
        if outcome.interaction is not None:
            payload = cast(
                dict[str, JsonValue],
                outcome.interaction.model_dump(mode="json", by_alias=True),
            )
            await self._emit(record, "interaction.required", payload)
        elif outcome.task_draft is not None:
            payload = cast(
                dict[str, JsonValue],
                outcome.task_draft.model_dump(mode="json", by_alias=True),
            )
            await self._emit(record, "task-draft.proposed", payload)

    async def _emit_usage(self, record: _RunRecord, outcome: RunOutcome) -> None:
        await self._emit_usage_payload(record, outcome.usage)

    async def _emit_usage_payload(self, record: _RunRecord, usage: Usage) -> None:
        if record.usage_emitted:
            return
        payload = cast(dict[str, JsonValue], usage.model_dump(mode="json", by_alias=True))
        await self._emit(record, "usage.reported", payload)
        logger.info(
            "chat_run_usage",
            extra={
                "traceId": record.trace_id,
                "agentRunId": str(record.request.run_id),
                "agentKey": record.agent_key,
                "agentVersion": record.agent_version,
                "modelCallCount": usage.model_call_count,
                "toolCallCount": usage.tool_call_count,
                "contextLoadCount": usage.context_load_count,
                "providerInputTokens": usage.provider_input_tokens,
                "providerOutputTokens": usage.provider_output_tokens,
            },
        )
        record.usage_emitted = True

    async def _finish_stopped(self, record: _RunRecord, usage: Usage | None = None) -> None:
        async with record.lifecycle_lock:
            await self._finish_stopped_locked(record, usage)

    async def _finish_stopped_locked(self, record: _RunRecord, usage: Usage | None = None) -> None:
        if record.status in self._terminal_statuses:
            return
        if isinstance(usage, Usage):
            await self._emit_usage_payload(record, usage)
        record.status = RunStatus.STOPPED
        await self._emit(
            record,
            "run.completed",
            {"status": RunStatus.STOPPED.value, "reason": "USER_REQUESTED"},
        )

    async def _emit(
        self,
        record: _RunRecord,
        event_type: EventType,
        payload: dict[str, JsonValue],
    ) -> None:
        event = ChatRunEvent(
            event_id=uuid4(),
            event_type=event_type,
            run_id=record.request.run_id,
            occurred_at=datetime.now(UTC),
            sequence=len(record.events) + 1,
            payload=payload,
        )
        async with record.changed:
            record.events.append(event)
            record.changed.notify_all()

    async def _emit_progress(
        self,
        record: _RunRecord,
        stage: RunProgressStage,
        status: RunProgressStatus,
        *,
        current: int | None = None,
        total: int | None = None,
    ) -> None:
        if status is RunProgressStatus.RUNNING:
            if record.progress_active_stage is not None:
                if record.progress_active_stage is stage:
                    return
                logger.warning(
                    "run_progress_overlap_ignored",
                    extra={
                        "traceId": record.trace_id,
                        "activeStage": record.progress_active_stage.value,
                        "ignoredStage": stage.value,
                    },
                )
                return
            if record.progress_ordinal >= 32:
                logger.warning(
                    "run_progress_limit_reached",
                    extra={"traceId": record.trace_id},
                )
                return
            record.progress_ordinal += 1
            record.progress_active_stage = stage
            record.progress_active_ordinal = record.progress_ordinal
        elif record.progress_active_stage is not stage:
            return

        ordinal = record.progress_active_ordinal
        if ordinal is None:
            return
        event_key = (stage, status, ordinal)
        if event_key in record.progress_event_keys:
            return
        elapsed_ms = max(
            record.progress_last_elapsed_ms,
            round((monotonic() - record.progress_started_at) * 1_000),
        )
        payload = RunProgressPayload(
            stage_code=stage,
            status=status,
            ordinal=ordinal,
            elapsed_ms=elapsed_ms,
            current=current,
            total=total,
        )
        await self._emit(
            record,
            "run.progress",
            cast(dict[str, JsonValue], payload.model_dump(mode="json", by_alias=True)),
        )
        record.progress_event_keys.add(event_key)
        record.progress_last_elapsed_ms = elapsed_ms
        if status in {RunProgressStatus.SUCCEEDED, RunProgressStatus.FAILED}:
            record.progress_finished_stages.add(stage)
            record.progress_active_stage = None
            record.progress_active_ordinal = None

    async def _fail_active_progress(self, record: _RunRecord) -> None:
        stage = record.progress_active_stage
        if stage is not None:
            await self._emit_progress(record, stage, RunProgressStatus.FAILED)

    async def _stop_or_delay(self, record: _RunRecord) -> bool:
        if record.stop_requested.is_set():
            return True
        delay_seconds = self._runtime.settings.mock_event_delay_ms / 1_000 if self.is_mock else 0
        if delay_seconds == 0:
            await asyncio.sleep(0)
            return record.stop_requested.is_set()
        try:
            await asyncio.wait_for(record.stop_requested.wait(), timeout=delay_seconds)
        except TimeoutError:
            return False
        return True

    def _record(self, run_id: UUID) -> _RunRecord:
        try:
            return self._records[run_id]
        except KeyError as exc:
            raise RunNotFoundError("Run was not found") from exc

    def _evict_terminal_runs(self) -> None:
        while len(self._records) >= self._runtime.settings.max_local_runs:
            terminal_id = next(
                (
                    run_id
                    for run_id, record in self._records.items()
                    if record.status in self._terminal_statuses
                    and record.task is not None
                    and record.task.done()
                ),
                None,
            )
            if terminal_id is None:
                return
            del self._records[terminal_id]

    @staticmethod
    def _snapshot(record: _RunRecord) -> ChatRunSnapshot:
        return ChatRunSnapshot(
            run_id=record.request.run_id,
            conversation_id=record.request.conversation_id,
            status=record.status,
            event_count=len(record.events),
            last_event=record.events[-1] if record.events else None,
        )

    @staticmethod
    def _chunks(content: str, size: int = 18) -> tuple[str, ...]:
        return tuple(content[index : index + size] for index in range(0, len(content), size))

    @staticmethod
    def _safe_error_code(exc: Exception) -> str:
        code = getattr(exc, "code", None)
        return code if isinstance(code, str) else "AGENT_RUN_FAILED"
