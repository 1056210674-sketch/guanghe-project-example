"""Low-cardinality OpenTelemetry metrics derived from real runtime boundaries."""

from __future__ import annotations

import logging
import re
from collections.abc import Generator, Mapping
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from threading import Lock
from typing import TYPE_CHECKING, Any

from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import MetricReader, PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource

from app.observability.failures import classify_failure

if TYPE_CHECKING:
    from app.common.settings import AppSettings

logger = logging.getLogger(__name__)
_lock = Lock()
_SAFE_VALUE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}$")
_PROVIDERS = frozenset(
    {
        "DEEPSEEK",
        "QWEN",
        "QWEN_IMAGE",
        "WAN_VIDEO",
        "DOUBAO",
        "WENXIN",
        "KIMI",
        "YUANBAO",
        "ZHIPU",
        "MOCK",
        "LOCAL",
        "JAVA",
    }
)
_MODELS = frozenset(
    {
        "DEEPSEEK-CHAT",
        "DEEPSEEK-FLASH",
        "DEEPSEEK-V4-FLASH",
        "QWEN3.8-FLASH",
        "QWEN-PLUS",
        "QWEN-IMAGE-3.0",
        "WAN3.0-VIDEO",
        "DETERMINISTIC-ROUTER",
    }
)
_metric_attributes: ContextVar[Mapping[str, str | bool | int | float] | None] = ContextVar(
    "otel_metric_attributes",
    default=None,
)


@dataclass(frozen=True, slots=True)
class MetricsRuntime:
    enabled: bool
    reason_code: str
    process_role: str
    provider: MeterProvider | None = None
    instruments: _Instruments | None = None

    def force_flush(self, timeout_millis: int = 5_000) -> bool:
        return self.provider is None or self.provider.force_flush(timeout_millis=timeout_millis)

    def shutdown(self) -> None:
        if self.provider is not None:
            self.provider.shutdown()


@dataclass(frozen=True, slots=True)
class _Instruments:
    operations: Any
    duration: Any
    failures: Any
    model_requests: Any
    model_retries: Any
    model_repairs: Any
    model_input_tokens: Any
    model_output_tokens: Any
    context_loads: Any
    tool_calls: Any
    tool_policy_denials: Any
    provider_calls: Any
    worker_tasks: Any
    worker_inflight: Any
    workflow_events: Any
    workflow_stage_duration: Any


_runtime = MetricsRuntime(False, "OTEL_METRICS_DISABLED", "api")


def configure_metrics(
    settings: AppSettings,
    *,
    process_role: str,
    resource: Resource,
    metric_reader: MetricReader | None = None,
) -> MetricsRuntime:
    """Configure the only MeterProvider for this process; never affects readiness."""
    global _runtime
    if not settings.otel_metrics_enabled:
        configured = MetricsRuntime(False, "OTEL_METRICS_DISABLED", process_role)
    else:
        endpoint = settings.otel_metrics_otlp_endpoint or _metrics_endpoint(
            settings.otel_otlp_endpoint
        )
        if metric_reader is None and endpoint is None:
            configured = MetricsRuntime(False, "OTEL_METRICS_EXPORTER_NOT_CONFIGURED", process_role)
        else:
            reader = metric_reader or PeriodicExportingMetricReader(
                OTLPMetricExporter(
                    endpoint=endpoint,
                    timeout=settings.otel_export_timeout_seconds,
                ),
                export_interval_millis=settings.otel_metrics_export_interval_ms,
                export_timeout_millis=round(settings.otel_export_timeout_seconds * 1_000),
            )
            provider = MeterProvider(resource=resource, metric_readers=(reader,))
            meter = provider.get_meter("guanghe-agent", settings.service_version)
            configured = MetricsRuntime(
                True,
                "READY",
                process_role,
                provider,
                _Instruments(
                    operations=meter.create_counter("guanghe.operations", unit="{operation}"),
                    duration=meter.create_histogram("guanghe.operation.duration", unit="ms"),
                    failures=meter.create_counter("guanghe.failures", unit="{failure}"),
                    model_requests=meter.create_counter("guanghe.model.requests", unit="{request}"),
                    model_retries=meter.create_counter("guanghe.model.retries", unit="{retry}"),
                    model_repairs=meter.create_counter("guanghe.model.repairs", unit="{repair}"),
                    model_input_tokens=meter.create_counter(
                        "guanghe.model.input_tokens", unit="{token}"
                    ),
                    model_output_tokens=meter.create_counter(
                        "guanghe.model.output_tokens", unit="{token}"
                    ),
                    context_loads=meter.create_counter("guanghe.context.loads", unit="{load}"),
                    tool_calls=meter.create_counter("guanghe.tool.calls", unit="{call}"),
                    tool_policy_denials=meter.create_counter(
                        "guanghe.tool.policy_denials", unit="{denial}"
                    ),
                    provider_calls=meter.create_counter("guanghe.provider.calls", unit="{call}"),
                    worker_tasks=meter.create_counter("guanghe.worker.tasks", unit="{task}"),
                    worker_inflight=meter.create_up_down_counter(
                        "guanghe.worker.inflight", unit="{task}"
                    ),
                    workflow_events=meter.create_counter("guanghe.workflow.events", unit="{event}"),
                    workflow_stage_duration=meter.create_histogram(
                        "guanghe.workflow.stage.duration", unit="ms"
                    ),
                ),
            )
    with _lock:
        _runtime = configured
    return configured


@contextmanager
def bind_metric_attributes(
    attributes: Mapping[str, str | bool | int | float],
) -> Generator[None]:
    """Bind safe operation metadata for exact call counters in nested boundaries."""
    merged = dict(_metric_attributes.get() or {})
    merged.update(attributes)
    token = _metric_attributes.set(merged)
    try:
        yield
    finally:
        _metric_attributes.reset(token)


def shutdown_metrics(runtime: MetricsRuntime | None = None) -> None:
    global _runtime
    target = runtime or _runtime
    with _lock:
        if target is _runtime:
            _runtime = MetricsRuntime(False, "OTEL_METRICS_DISABLED", target.process_role)
    target.shutdown()


def record_span(
    name: str,
    *,
    attributes: dict[str, str | bool | int | float],
    latency_ms: int,
    failed: bool,
    stable_error_code: str | None,
) -> None:
    instruments = _runtime.instruments
    if not _runtime.enabled or instruments is None:
        return
    try:
        family = _family(name, attributes)
        labels = _labels(name, attributes, failed, stable_error_code)
        instruments.operations.add(1, labels)
        instruments.duration.record(latency_ms, labels)
        if failed:
            instruments.failures.add(1, labels)
        if family == "model":
            requests = _non_negative_int(attributes.get("usage.requestCount"))
            if requests:
                instruments.model_requests.add(requests, labels)
                if requests > 1:
                    instruments.model_retries.add(requests - 1, labels)
            attempts = _non_negative_int(attributes.get("attemptCount"))
            if attempts > 1:
                instruments.model_repairs.add(attempts - 1, labels)
            input_tokens = _non_negative_int(attributes.get("usage.inputTokens"))
            output_tokens = _non_negative_int(attributes.get("usage.outputTokens"))
            if input_tokens:
                instruments.model_input_tokens.add(input_tokens, labels)
            if output_tokens:
                instruments.model_output_tokens.add(output_tokens, labels)
        if "context.load" in name:
            instruments.context_loads.add(1, labels)
        if family == "tool":
            instruments.tool_calls.add(1, labels)
        if family == "workflow":
            instruments.workflow_events.add(1, labels)
            instruments.workflow_stage_duration.record(latency_ms, labels)
    except Exception:
        logger.warning(
            "otel_metric_record_failed",
            extra={"errorCode": "OTEL_METRIC_RECORD_FAILED"},
        )


def record_tool_policy_denial(
    stable_error_code: str,
    *,
    tool_name: str,
    tool_version: str,
    risk_level: str,
) -> None:
    instruments = _runtime.instruments
    if not _runtime.enabled or instruments is None:
        return
    try:
        category, retry = classify_failure(stable_error_code)
        labels = {
            "process.role": _safe(_runtime.process_role),
            "tool.name": _safe(tool_name),
            "tool.version": _safe(tool_version),
            "risk.level": _safe(risk_level),
            "outcome": "DENIED",
            "error.category": category,
            "retry.strategy": retry,
        }
        instruments.tool_policy_denials.add(1, labels)
        instruments.failures.add(1, {**labels, "operation": "TOOL.POLICY"})
    except Exception:
        logger.warning(
            "otel_metric_record_failed",
            extra={"errorCode": "OTEL_METRIC_RECORD_FAILED"},
        )


def record_provider_request() -> None:
    """Count one actual external Provider request after its budget check."""
    instruments = _runtime.instruments
    if not _runtime.enabled or instruments is None:
        return
    try:
        attributes = dict(_metric_attributes.get() or {})
        labels = _labels("provider.request", attributes, False, None)
        instruments.provider_calls.add(1, labels)
    except Exception:
        logger.warning(
            "otel_metric_record_failed",
            extra={"errorCode": "OTEL_METRIC_RECORD_FAILED"},
        )


def record_worker_task(outcome: str, *, task_type: str) -> None:
    instruments = _runtime.instruments
    if not _runtime.enabled or instruments is None:
        return
    try:
        instruments.worker_tasks.add(
            1,
            {
                "process.role": _safe(_runtime.process_role),
                "task.type": _safe(task_type),
                "outcome": _safe(outcome),
            },
        )
    except Exception:
        logger.warning(
            "otel_metric_record_failed",
            extra={"errorCode": "OTEL_METRIC_RECORD_FAILED"},
        )


def adjust_worker_inflight(delta: int, *, task_type: str) -> None:
    instruments = _runtime.instruments
    if not _runtime.enabled or instruments is None:
        return
    try:
        instruments.worker_inflight.add(
            delta,
            {
                "process.role": _safe(_runtime.process_role),
                "task.type": _safe(task_type),
            },
        )
    except Exception:
        logger.warning(
            "otel_metric_record_failed",
            extra={"errorCode": "OTEL_METRIC_RECORD_FAILED"},
        )


def _family(name: str, attributes: Mapping[str, object] | None = None) -> str:
    if name.startswith("workflow.") or (
        str((attributes or {}).get("task.type", "")).upper() == "WORKFLOW"
        and (attributes or {}).get("workflow.stage") is not None
    ):
        return "workflow"
    if name == "python.chat.run":
        return "chat"
    if name.startswith("model."):
        return "model"
    if "context." in name:
        return "context"
    if name.startswith("tool."):
        return "tool"
    if name.startswith("provider.") or name == "geo.sample":
        return "provider"
    if name.startswith(("worker.", "rabbit.", "java.result.")):
        return "worker"
    if name.startswith("agent."):
        return "agent"
    return "internal"


def _labels(
    name: str,
    attributes: dict[str, str | bool | int | float],
    failed: bool,
    stable_error_code: str | None,
) -> dict[str, str]:
    labels = {
        "process.role": _safe(_runtime.process_role),
        "operation": _safe(_operation(name, attributes)),
        "outcome": (
            "FAILED"
            if failed
            else _safe(
                str(
                    attributes.get("workflow.outcome")
                    or attributes.get("run.status")
                    or attributes.get("tool.status")
                    or attributes.get("agent.step.status")
                    or attributes.get("status", "SUCCEEDED")
                )
            )
        ),
    }
    mappings = {
        "task.type": "task.type",
        "intent": "intent",
        "agent.router.intent": "intent",
        "classifiedIntent": "intent",
        "agent.router.complexity": "complexity",
        "router.complexity": "complexity",
        "model.provider": "provider",
        "provider.name": "provider",
        "geo.platform": "provider",
        "model.name": "model",
        "provider.model": "model",
        "provider.submissionState": "submission.state",
        "context.source": "context.source",
        "tool.name": "tool.name",
        "tool.version": "tool.version",
        "tool.riskLevel": "risk.level",
        "workflow.stage": "workflow.stage",
    }
    for source, target in mappings.items():
        value = attributes.get(source)
        if value is not None:
            normalized = _safe(str(value))
            if target == "provider":
                normalized = normalized if normalized in _PROVIDERS else "OTHER"
            elif target == "model":
                normalized = normalized if normalized in _MODELS else "OTHER"
            labels[target] = normalized
    if stable_error_code is not None:
        if str(attributes.get("provider.submissionState", "")) == "UNKNOWN":
            category, retry = "PROVIDER_UNKNOWN", "RECOVERY_ONLY"
        else:
            category, retry = classify_failure(stable_error_code)
        labels["error.category"] = category
        labels["retry.strategy"] = retry
        labels["retryable"] = "FALSE" if retry == "NEVER" else "TRUE"
    return labels


def _operation(name: str, attributes: dict[str, str | bool | int | float]) -> str:
    candidate = attributes.get("model.operation") or attributes.get("provider.operation")
    return str(candidate or name)


def _safe(value: str) -> str:
    normalized = value.strip().upper()
    return normalized if _SAFE_VALUE.fullmatch(normalized) else "OTHER"


def _non_negative_int(value: object) -> int:
    if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
        return int(value)
    return 0


def _metrics_endpoint(trace_endpoint: str | None) -> str | None:
    if trace_endpoint is None:
        return None
    if trace_endpoint.endswith("/v1/traces"):
        return f"{trace_endpoint.removesuffix('/v1/traces')}/v1/metrics"
    return None
