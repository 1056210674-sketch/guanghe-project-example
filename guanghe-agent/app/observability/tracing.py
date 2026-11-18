"""Bounded OpenTelemetry assembly and safe manual span helpers."""

from __future__ import annotations

import hashlib
import logging
from collections.abc import Generator, Mapping
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from enum import StrEnum
from threading import Lock
from time import monotonic
from typing import TYPE_CHECKING

from opentelemetry import trace
from opentelemetry.context import Context
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.metrics.export import MetricReader
from opentelemetry.sdk.resources import SERVICE_NAME, SERVICE_VERSION, Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import (
    BatchSpanProcessor,
    SimpleSpanProcessor,
    SpanExporter,
)
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased
from opentelemetry.trace import Link, SpanKind, Status, StatusCode
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

from app.observability.failures import classify_failure
from app.observability.metrics import (
    MetricsRuntime,
    bind_metric_attributes,
    configure_metrics,
    record_span,
    shutdown_metrics,
)

if TYPE_CHECKING:
    from app.common.settings import AppSettings

logger = logging.getLogger(__name__)

type AttributeValue = str | bool | int | float
_PROPAGATOR = TraceContextTextMapPropagator()
_runtime_lock = Lock()
_trace_attributes: ContextVar[Mapping[str, AttributeValue] | None] = ContextVar(
    "otel_safe_trace_attributes",
    default=None,
)


class LangfuseExportStatus(StrEnum):
    DISABLED = "DISABLED"
    NOT_CONFIGURED = "NOT_CONFIGURED"
    READY = "READY"
    DEGRADED = "DEGRADED"


@dataclass(frozen=True, slots=True)
class LangfuseExportState:
    """Safe deployment state; this never claims a remote ingestion result."""

    status: LangfuseExportStatus
    reason_code: str | None


@dataclass(frozen=True, slots=True)
class TracingRuntime:
    """Process-owned tracer assembly; never participates in business readiness."""

    enabled: bool
    reason_code: str
    service_name: str
    provider: TracerProvider | None = None
    metrics: MetricsRuntime | None = None

    def force_flush(self, timeout_millis: int = 5_000) -> bool:
        trace_flushed = self.provider is None or self.provider.force_flush(
            timeout_millis=timeout_millis
        )
        metrics_flushed = self.metrics is None or self.metrics.force_flush(
            timeout_millis=timeout_millis
        )
        return trace_flushed and metrics_flushed

    def shutdown(self) -> None:
        if self.metrics is not None:
            shutdown_metrics(self.metrics)
        if self.provider is not None:
            self.provider.shutdown()


def resolve_langfuse_export_state(
    settings: AppSettings,
    runtime: TracingRuntime,
) -> LangfuseExportState:
    """Resolve non-secret Collector wiring state without probing the remote backend."""
    if not settings.langfuse_export_enabled:
        return LangfuseExportState(
            LangfuseExportStatus.DISABLED,
            "LANGFUSE_EXPORT_DISABLED",
        )
    if not settings.langfuse_export_collector_configured:
        return LangfuseExportState(
            LangfuseExportStatus.NOT_CONFIGURED,
            "LANGFUSE_EXPORT_NOT_CONFIGURED",
        )
    if not runtime.enabled:
        return LangfuseExportState(
            LangfuseExportStatus.DEGRADED,
            runtime.reason_code,
        )
    return LangfuseExportState(LangfuseExportStatus.READY, None)


_runtime = TracingRuntime(
    enabled=False,
    reason_code="OTEL_DISABLED",
    service_name="guanghe-agent",
)


def configure_tracing(
    settings: AppSettings,
    *,
    process_role: str,
    span_exporter: SpanExporter | None = None,
    synchronous_export: bool = False,
    metric_reader: MetricReader | None = None,
) -> TracingRuntime:
    """Build the sole SDK/OTLP pipeline used by one API or Worker process."""
    global _runtime
    service_name = _service_name(settings.service_name, process_role)
    resource = Resource(
        {
            SERVICE_NAME: service_name,
            SERVICE_VERSION: settings.git_commit or settings.service_version,
            "deployment.environment.name": settings.environment,
            "service.instance.role": process_role,
        }
    )
    metrics = configure_metrics(
        settings,
        process_role=process_role,
        resource=resource,
        metric_reader=metric_reader,
    )
    if not settings.otel_enabled:
        configured = TracingRuntime(False, "OTEL_DISABLED", service_name, metrics=metrics)
    elif span_exporter is None and settings.otel_otlp_endpoint is None:
        configured = TracingRuntime(
            False,
            "OTEL_EXPORTER_NOT_CONFIGURED",
            service_name,
            metrics=metrics,
        )
    else:
        sample_ratio = 1.0 if settings.environment == "test" else settings.otel_sample_ratio
        provider = TracerProvider(
            resource=resource,
            sampler=ParentBased(TraceIdRatioBased(sample_ratio)),
        )
        exporter = span_exporter or OTLPSpanExporter(
            endpoint=settings.otel_otlp_endpoint,
            timeout=settings.otel_export_timeout_seconds,
        )
        processor = (
            SimpleSpanProcessor(exporter)
            if synchronous_export
            else BatchSpanProcessor(
                exporter,
                max_queue_size=settings.otel_batch_max_queue_size,
                max_export_batch_size=settings.otel_batch_max_export_batch_size,
                schedule_delay_millis=settings.otel_batch_schedule_delay_ms,
                export_timeout_millis=round(settings.otel_export_timeout_seconds * 1_000),
            )
        )
        provider.add_span_processor(processor)
        configured = TracingRuntime(True, "READY", service_name, provider, metrics)
    with _runtime_lock:
        previous = _runtime
        _runtime = configured
    if previous.provider is not None and previous.provider is not configured.provider:
        previous.shutdown()
    return configured


def current_tracing_runtime() -> TracingRuntime:
    return _runtime


def shutdown_tracing(runtime: TracingRuntime | None = None) -> None:
    """Flush one process pipeline and restore the safe no-op runtime."""
    global _runtime
    target = runtime or _runtime
    with _runtime_lock:
        if target is _runtime:
            _runtime = TracingRuntime(False, "OTEL_DISABLED", target.service_name)
    target.shutdown()


@dataclass(slots=True)
class SafeSpan:
    """Small allowlisted wrapper that cannot record exception text or payloads."""

    _span: trace.Span | None
    _started: float
    _name: str
    _attributes: dict[str, AttributeValue]
    _failed: bool = False
    _stable_error_code: str | None = None
    _finished: bool = False

    @property
    def failed(self) -> bool:
        return self._failed

    def set(self, name: str, value: AttributeValue | None) -> None:
        if value is not None:
            self._attributes[name] = value
        if self._span is not None and value is not None:
            self._span.set_attribute(name, value)
            for mapped_name, mapped_value in _mapped_attribute(name, value).items():
                self._span.set_attribute(mapped_name, mapped_value)

    def event(
        self,
        name: str,
        attributes: Mapping[str, AttributeValue] | None = None,
    ) -> None:
        if self._span is not None:
            self._span.add_event(name, attributes=dict(attributes or {}))

    def fail(self, stable_error_code: str) -> None:
        self._failed = True
        self._stable_error_code = stable_error_code
        if self._span is not None:
            self._span.set_attribute("stableErrorCode", stable_error_code)
            self._span.set_attribute("error.type", stable_error_code)
            self._span.set_attribute("langfuse.observation.level", "ERROR")
            self._span.set_attribute(
                "langfuse.observation.status_message",
                stable_error_code,
            )
            category, retry_strategy = classify_failure(stable_error_code)
            retryable = retry_strategy != "NEVER"
            failure_stage = self._attributes.get("workflow.stage")
            self._span.set_attribute("error.code", stable_error_code)
            self._span.set_attribute("error.category", category)
            self._span.set_attribute("error.retry_strategy", retry_strategy)
            self._span.set_attribute("failure.category", category)
            self._span.set_attribute("failure.retryStrategy", retry_strategy)
            self._span.set_attribute("failure.retryable", retryable)
            if failure_stage is not None:
                self._span.set_attribute("failure.stage", failure_stage)
            self._span.set_attribute(
                "langfuse.observation.metadata.stable_error_code",
                stable_error_code,
            )
            self._span.set_attribute(
                "langfuse.observation.metadata.error_category",
                category,
            )
            self._span.set_attribute(
                "langfuse.observation.metadata.retry_strategy",
                retry_strategy,
            )
            self._span.set_attribute("status", "ERROR")
            self._span.set_status(Status(StatusCode.ERROR))

    def finish(self, status: str = "OK") -> None:
        if self._finished:
            return
        self._finished = True
        latency_ms = max(0, round((monotonic() - self._started) * 1_000))
        self._attributes["status"] = "ERROR" if self._failed else status
        record_span(
            self._name,
            attributes=self._attributes,
            latency_ms=latency_ms,
            failed=self._failed,
            stable_error_code=self._stable_error_code,
        )
        if self._span is not None:
            self._span.set_attribute("status", "ERROR" if self._failed else status)
            self._span.set_attribute(
                "durationMs",
                latency_ms,
            )
            self._span.set_attribute(
                "langfuse.observation.metadata.latency_ms",
                str(latency_ms),
            )


@contextmanager
def start_span(
    name: str,
    *,
    parent_traceparent: str | None = None,
    link_traceparent: str | None = None,
    kind: SpanKind = SpanKind.INTERNAL,
    attributes: Mapping[str, AttributeValue] | None = None,
) -> Generator[SafeSpan]:
    """Start a manual span without ever attaching exception messages or stack traces."""
    runtime = _runtime
    started = monotonic()
    span_attributes = dict(_trace_attributes.get() or {})
    span_attributes.update(dict(attributes or {}))
    if not runtime.enabled or runtime.provider is None:
        safe_span = SafeSpan(None, started, name, span_attributes)
        with bind_metric_attributes(span_attributes):
            try:
                yield safe_span
            except BaseException:
                if not safe_span.failed:
                    safe_span.fail("UNHANDLED_ERROR")
                safe_span.finish("ERROR")
                raise
            else:
                safe_span.finish()
        return
    parent = _extract_context(parent_traceparent)
    links = _links(link_traceparent)
    span_attributes.update(_observation_attributes(name, span_attributes))
    tracer = runtime.provider.get_tracer("guanghe-agent", runtime.service_name)
    with tracer.start_as_current_span(
        name,
        context=parent,
        kind=kind,
        attributes=span_attributes,
        links=links,
        record_exception=False,
        set_status_on_exception=False,
    ) as raw_span:
        safe_span = SafeSpan(raw_span, started, name, span_attributes)
        with bind_metric_attributes(span_attributes):
            try:
                yield safe_span
            except BaseException:
                if not safe_span.failed:
                    safe_span.fail("UNHANDLED_ERROR")
                safe_span.finish("ERROR")
                raise
            else:
                safe_span.finish()


@contextmanager
def bind_trace_attributes(
    attributes: Mapping[str, AttributeValue],
) -> Generator[None]:
    """Propagate only pre-sanitized trace/session metadata to every child span."""
    merged = dict(_trace_attributes.get() or {})
    merged.update(attributes)
    token = _trace_attributes.set(merged)
    current = trace.get_current_span()
    if current.is_recording():
        for name, value in attributes.items():
            current.set_attribute(name, value)
    try:
        yield
    finally:
        _trace_attributes.reset(token)


def current_otel_trace_id() -> str | None:
    context = trace.get_current_span().get_span_context()
    if not context.is_valid:
        return None
    return trace.format_trace_id(context.trace_id)


def current_otel_span_id() -> str | None:
    context = trace.get_current_span().get_span_context()
    if not context.is_valid:
        return None
    return trace.format_span_id(context.span_id)


def current_otel_traceparent() -> str | None:
    context = trace.get_current_span().get_span_context()
    if not context.is_valid:
        return None
    flags = "01" if context.trace_flags.sampled else "00"
    trace_id = trace.format_trace_id(context.trace_id)
    span_id = trace.format_span_id(context.span_id)
    return f"00-{trace_id}-{span_id}-{flags}"


def set_current_span_attribute(name: str, value: AttributeValue) -> None:
    span = trace.get_current_span()
    if span.is_recording():
        span.set_attribute(name, value)


def add_current_span_event(
    name: str,
    attributes: Mapping[str, AttributeValue] | None = None,
) -> None:
    span = trace.get_current_span()
    if span.is_recording():
        span.add_event(name, attributes=dict(attributes or {}))


def safe_ref(value: object) -> str:
    """Create a process-independent irreversible reference for a high-cardinality ID."""
    return f"sha256:{hashlib.sha256(str(value).encode('utf-8')).hexdigest()[:16]}"


def _observation_attributes(
    span_name: str,
    attributes: Mapping[str, AttributeValue],
) -> dict[str, AttributeValue]:
    mapped: dict[str, AttributeValue] = {}
    if span_name.startswith(("model.", "provider.")):
        mapped["langfuse.observation.type"] = "generation"
        mapped["gen_ai.operation.name"] = (
            "chat" if span_name.startswith("model.") else "generate_content"
        )
    elif span_name.startswith("tool."):
        mapped["langfuse.observation.type"] = "tool"
        mapped["gen_ai.operation.name"] = "execute_tool"
    elif span_name == "python.chat.run" or span_name.startswith("agent."):
        mapped["langfuse.observation.type"] = "agent"
        mapped["gen_ai.operation.name"] = "invoke_agent"
    for name, value in attributes.items():
        mapped.update(_mapped_attribute(name, value))
    return mapped


def _mapped_attribute(name: str, value: AttributeValue) -> dict[str, AttributeValue]:
    if name in {"model.provider", "provider.name"}:
        return {
            "gen_ai.provider.name": str(value).lower(),
            "langfuse.observation.metadata.model_provider": str(value),
        }
    if name in {"model.name", "provider.model"}:
        return {
            "gen_ai.request.model": str(value),
            "langfuse.observation.model.name": str(value),
        }
    if name in {"model.operation", "provider.operation"}:
        return {"langfuse.observation.metadata.model_operation": str(value)}
    if name == "usage.inputTokens":
        return {"gen_ai.usage.input_tokens": int(value)}
    if name == "usage.outputTokens":
        return {"gen_ai.usage.output_tokens": int(value)}
    if name == "usage.requestCount":
        count = int(value)
        return {
            "langfuse.observation.metadata.request_count": str(count),
            "langfuse.observation.metadata.retry_count": str(max(0, count - 1)),
        }
    if name == "provider.latencyMs":
        return {"langfuse.observation.metadata.latency_ms": str(value)}
    if name == "attemptCount":
        count = int(value)
        return {
            "langfuse.observation.metadata.structure_attempt_count": str(count),
            "langfuse.observation.metadata.structure_repair_count": str(max(0, count - 1)),
        }
    if name == "tool.name":
        return {
            "gen_ai.tool.name": str(value),
            "langfuse.observation.metadata.tool_name": str(value),
        }
    safe_metadata_names = {
        "tool.version": "tool_version",
        "tool.riskLevel": "tool_risk",
        "tool.status": "tool_status",
        "tool.attempt": "tool_attempt",
        "provider.submissionState": "provider_submission_state",
        "agent.plan.version": "plan_version",
        "agent.plan.stepCount": "plan_step_count",
        "agent.replan.count": "replan_count",
        "agent.step.status": "agent_step_status",
    }
    mapped_name = safe_metadata_names.get(name)
    if mapped_name is not None:
        return {f"langfuse.observation.metadata.{mapped_name}": str(value)}
    if name in {"run.ref", "task.ref", "conversation.ref"}:
        return {f"langfuse.trace.metadata.{name.replace('.', '_')}": str(value)}
    return {}


def _extract_context(traceparent: str | None) -> Context | None:
    if traceparent is None:
        return None
    return _PROPAGATOR.extract({"traceparent": traceparent})


def _links(traceparent: str | None) -> tuple[Link, ...]:
    context = _extract_context(traceparent)
    if context is None:
        return ()
    span_context = trace.get_current_span(context).get_span_context()
    return (Link(span_context),) if span_context.is_valid else ()


def _service_name(base: str, role: str) -> str:
    normalized = role.strip().lower().replace("_", "-")
    return f"{base}-{normalized}"
