"""W3C Trace Context continuation tests."""

import json
import logging
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import cast

import pytest
import yaml
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.trace import ReadableSpan
from opentelemetry.sdk.trace.export import SpanExporter, SpanExportResult
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from app.common.settings import AppSettings
from app.harness.runtime import record_provider_call
from app.observability.logging import JsonFormatter
from app.observability.metrics import (
    adjust_worker_inflight,
    record_tool_policy_denial,
    record_worker_task,
)
from app.observability.trace import (
    child_trace_context,
    current_span_id,
    current_trace_id,
    reset_current_trace_id,
    resolve_trace_context,
    set_current_trace_id,
)
from app.observability.tracing import (
    LangfuseExportStatus,
    add_current_span_event,
    bind_trace_attributes,
    configure_tracing,
    resolve_langfuse_export_state,
    set_current_span_attribute,
    shutdown_tracing,
    start_span,
)

ROOT = Path(__file__).resolve().parents[2]


class _UnavailableExporter(SpanExporter):
    def export(self, spans: Sequence[ReadableSpan]) -> SpanExportResult:
        del spans
        return SpanExportResult.FAILURE

    def shutdown(self) -> None:
        return None


def test_valid_inbound_trace_is_continued_with_new_child_spans() -> None:
    inbound = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
    context = resolve_trace_context(inbound)
    token = set_current_trace_id(context.trace_id)
    try:
        first = child_trace_context()
        second = child_trace_context()
        assert current_trace_id() == "0123456789abcdef0123456789abcdef"
        assert context.traceparent == inbound
        assert first.traceparent.startswith("00-0123456789abcdef0123456789abcdef-")
        assert first.traceparent.endswith("-01")
        assert second.traceparent.startswith("00-0123456789abcdef0123456789abcdef-")
        assert first.traceparent != second.traceparent
    finally:
        reset_current_trace_id(token)


def test_invalid_or_zero_traceparent_starts_a_safe_nonzero_trace() -> None:
    invalid_values = (
        None,
        "not-a-trace",
        "ff-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        "00-00000000000000000000000000000000-0123456789abcdef-01",
        "00-0123456789abcdef0123456789abcdef-0000000000000000-01",
    )

    for value in invalid_values:
        context = resolve_trace_context(value)
        assert len(context.trace_id) == 32
        assert context.trace_id != "0" * 32
        assert context.traceparent.startswith(f"00-{context.trace_id}-")


def test_nested_bind_reset_restores_previous_trace() -> None:
    outer = resolve_trace_context("00-11111111111111111111111111111111-1111111111111111-01")
    inner = resolve_trace_context("00-22222222222222222222222222222222-2222222222222222-01")
    outer_token = set_current_trace_id(outer.trace_id)
    try:
        inner_token = set_current_trace_id(inner.trace_id)
        try:
            assert current_trace_id() == "2" * 32
        finally:
            reset_current_trace_id(inner_token)
        assert current_trace_id() == "1" * 32
    finally:
        reset_current_trace_id(outer_token)


def test_otel_parent_child_logs_and_safe_error_are_correlated() -> None:
    exporter = InMemorySpanExporter()
    runtime = configure_tracing(
        AppSettings(otel_enabled=True, otel_sample_ratio=1),
        process_role="api",
        span_exporter=exporter,
        synchronous_export=True,
    )
    inbound = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
    try:
        with start_span("python.chat.run", parent_traceparent=inbound):
            parent_span_id = current_span_id()
            assert current_trace_id() == "0123456789abcdef0123456789abcdef"
            record = logging.LogRecord("unit", logging.INFO, __file__, 1, "safe", (), None)
            log_payload = json.loads(JsonFormatter().format(record))
            assert log_payload["traceId"] == "0123456789abcdef0123456789abcdef"
            assert log_payload["spanId"] == parent_span_id
            with (
                pytest.raises(ValueError, match="sensitive-provider-body"),
                start_span("agent.context.load"),
            ):
                raise ValueError("sensitive-provider-body")

        spans = {span.name: span for span in exporter.get_finished_spans()}
        root = spans["python.chat.run"]
        child = spans["agent.context.load"]
        assert root.parent is not None
        assert f"{root.parent.span_id:016x}" == "0123456789abcdef"
        assert child.parent is not None
        assert f"{child.parent.span_id:016x}" == parent_span_id
        assert root.context is not None
        assert child.context is not None
        assert root.context.trace_id == child.context.trace_id
        assert child.events == ()
        assert child.attributes is not None
        rendered = repr(child.attributes)
        assert "sensitive-provider-body" not in rendered
        assert child.attributes["stableErrorCode"] == "UNHANDLED_ERROR"
    finally:
        shutdown_tracing(runtime)


def test_rabbit_redelivery_uses_link_and_provider_poll_uses_events() -> None:
    exporter = InMemorySpanExporter()
    runtime = configure_tracing(
        AppSettings(otel_enabled=True, otel_sample_ratio=1),
        process_role="image-worker",
        span_exporter=exporter,
        synchronous_export=True,
    )
    inbound = "00-11111111111111111111111111111111-2222222222222222-01"
    try:
        with (
            start_span(
                "rabbit.consume",
                link_traceparent=inbound,
                attributes={"rabbit.redelivered": True},
            ),
            start_span("provider.poll"),
        ):
            for poll_no in range(1, 4):
                set_current_span_attribute("pollCount", poll_no)
                add_current_span_event("provider.poll.attempt", {"pollNo": poll_no})

        spans = {span.name: span for span in exporter.get_finished_spans()}
        consume = spans["rabbit.consume"]
        poll = spans["provider.poll"]
        assert consume.parent is None
        assert len(consume.links) == 1
        assert f"{consume.links[0].context.trace_id:032x}" == "1" * 32
        assert poll.parent is not None
        assert poll.attributes is not None
        assert poll.attributes["pollCount"] == 3
        event_attributes = [event.attributes for event in poll.events]
        assert all(item is not None for item in event_attributes)
        assert [item["pollNo"] for item in event_attributes if item is not None] == [1, 2, 3]
    finally:
        shutdown_tracing(runtime)


def test_disabled_and_unavailable_exporters_never_block_business_logic() -> None:
    disabled_exporter = InMemorySpanExporter()
    disabled = configure_tracing(
        AppSettings(),
        process_role="api",
        span_exporter=disabled_exporter,
        synchronous_export=True,
    )
    try:
        with start_span("python.chat.run"):
            result = "business-completed"
        assert result == "business-completed"
        assert disabled_exporter.get_finished_spans() == ()
    finally:
        shutdown_tracing(disabled)

    unavailable = configure_tracing(
        AppSettings(otel_enabled=True, otel_sample_ratio=1),
        process_role="api",
        span_exporter=_UnavailableExporter(),
    )
    try:
        with start_span("python.chat.run"):
            result = "business-completed"
        assert result == "business-completed"
    finally:
        shutdown_tracing(unavailable)


def test_langfuse_otel_semantics_propagate_only_safe_metadata() -> None:
    exporter = InMemorySpanExporter()
    runtime = configure_tracing(
        AppSettings(otel_enabled=True, otel_sample_ratio=1),
        process_role="api",
        span_exporter=exporter,
        synchronous_export=True,
    )
    safe_session = "sha256:0123456789abcdef"
    try:
        with (
            bind_trace_attributes(
                {
                    "langfuse.session.id": safe_session,
                    "langfuse.trace.metadata.run_ref": "sha256:fedcba9876543210",
                    "langfuse.trace.metadata.prompt_bundle_version": "agent-bundle-v1.10.4",
                    "langfuse.trace.metadata.prompt_hash": "synthetic-prompt-hash",
                }
            ),
            start_span("python.chat.run"),
        ):
            with start_span(
                "model.deepseek.intent",
                attributes={
                    "model.provider": "DEEPSEEK",
                    "model.name": "deepseek-chat",
                    "model.operation": "intent",
                },
            ) as model_span:
                model_span.set("usage.inputTokens", 120)
                model_span.set("usage.outputTokens", 8)
                model_span.set("usage.requestCount", 2)
                model_span.set("provider.latencyMs", 345)
                model_span.set("attemptCount", 2)
            with start_span(
                "tool.task_draft.propose",
                attributes={
                    "tool.name": "task_draft.propose",
                    "tool.version": "1",
                    "tool.riskLevel": "PREPARE",
                    "tool.attempt": 1,
                    "tool.status": "SUCCEEDED",
                },
            ):
                pass

        spans = {span.name: span for span in exporter.get_finished_spans()}
        root = spans["python.chat.run"]
        model = spans["model.deepseek.intent"]
        tool = spans["tool.task_draft.propose"]
        assert root.attributes is not None
        assert model.attributes is not None
        assert tool.attributes is not None
        assert root.attributes["langfuse.session.id"] == safe_session
        assert model.attributes["langfuse.session.id"] == safe_session
        assert model.attributes["langfuse.observation.type"] == "generation"
        assert model.attributes["gen_ai.provider.name"] == "deepseek"
        assert model.attributes["gen_ai.request.model"] == "deepseek-chat"
        assert model.attributes["gen_ai.usage.input_tokens"] == 120
        assert model.attributes["gen_ai.usage.output_tokens"] == 8
        assert model.attributes["langfuse.observation.metadata.retry_count"] == "1"
        assert model.attributes["langfuse.observation.metadata.structure_repair_count"] == "1"
        assert tool.attributes["langfuse.observation.type"] == "tool"
        assert tool.attributes["langfuse.observation.metadata.tool_risk"] == "PREPARE"
        rendered = repr(tuple(spans.values()))
        for forbidden in ("prompt body", "Authorization", "userContextRef", "api-key"):
            assert forbidden not in rendered
    finally:
        shutdown_tracing(runtime)


@pytest.mark.parametrize(
    ("code", "category", "retry_strategy"),
    (
        ("DEEPSEEK_AUTHENTICATION_FAILED", "MODEL_CONFIGURATION", "NEVER"),
        ("DEEPSEEK_RATE_LIMITED", "MODEL_RATE_LIMIT", "SAFE_RETRY"),
        ("DEEPSEEK_TIMEOUT", "MODEL_TRANSPORT", "SAFE_RETRY"),
        ("DEEPSEEK_INTENT_RESPONSE_INVALID", "MODEL_OUTPUT", "BOUNDED_REPAIR"),
        ("JAVA_RUN_CONTEXT_CONNECTION_FAILED", "CONTEXT", "SAFE_RETRY"),
        ("TOOL_GROUP_NOT_AUTHORIZED", "TOOL_POLICY", "NEVER"),
        ("TOOL_EXECUTION_FAILED", "TOOL_EXECUTION", "BOUNDED_RETRY"),
        ("MODEL_CALL_BUDGET_EXCEEDED", "BUDGET", "NEVER"),
        ("RUN_DEADLINE_EXCEEDED", "DEADLINE", "NEVER"),
        ("PROVIDER_SUBMISSION_UNKNOWN", "PROVIDER_UNKNOWN", "RECOVERY_ONLY"),
        ("STRUCTURED_REPAIR_EXHAUSTED", "MODEL_OUTPUT", "BOUNDED_REPAIR"),
        ("WORKFLOW_FENCING_REJECTED", "WORKER_FENCING", "RECOVERY_ONLY"),
        ("WORKFLOW_JAVA_CONTROL_UNAVAILABLE", "WORKER_CONTROL", "SAFE_RETRY"),
        ("WORKFLOW_CLAIM_CONFLICT", "WORKER_CONTROL", "SAFE_RETRY"),
        ("WORKFLOW_LEASE_EXPIRED", "WORKER_FENCING", "RECOVERY_ONLY"),
        ("WORKFLOW_WAIT_TIMEOUT", "WORKFLOW_WAIT", "SAFE_RETRY"),
        ("WORKFLOW_REPLAN_EXHAUSTED", "WORKFLOW_REPLAN", "NEVER"),
        ("QWEN_IMAGE_BALANCE_INSUFFICIENT", "PROVIDER_CONFIGURATION", "NEVER"),
        ("WAN_VIDEO_RATE_LIMITED", "PROVIDER_RATE_LIMIT", "SAFE_RETRY"),
    ),
)
def test_safe_failures_have_stable_langfuse_classification(
    code: str,
    category: str,
    retry_strategy: str,
) -> None:
    exporter = InMemorySpanExporter()
    runtime = configure_tracing(
        AppSettings(otel_enabled=True, otel_sample_ratio=1),
        process_role="api",
        span_exporter=exporter,
        synchronous_export=True,
    )
    try:
        with start_span("model.deepseek.intent") as span:
            span.fail(code)
        finished = exporter.get_finished_spans()[0]
        assert finished.attributes is not None
        assert finished.attributes["error.category"] == category
        assert finished.attributes["error.retry_strategy"] == retry_strategy
        assert finished.attributes["error.code"] == code
        assert finished.attributes["failure.category"] == category
        assert finished.attributes["failure.retryStrategy"] == retry_strategy
        assert finished.attributes["failure.retryable"] == (retry_strategy != "NEVER")
        assert finished.attributes["langfuse.observation.status_message"] == code
    finally:
        shutdown_tracing(runtime)


def test_langfuse_status_and_collector_configs_are_non_secret_and_bounded() -> None:
    runtime = configure_tracing(
        AppSettings(),
        process_role="api",
        synchronous_export=True,
    )
    try:
        disabled = resolve_langfuse_export_state(AppSettings(), runtime)
        missing = resolve_langfuse_export_state(
            AppSettings(langfuse_export_enabled=True),
            runtime,
        )
        degraded = resolve_langfuse_export_state(
            AppSettings(
                langfuse_export_enabled=True,
                langfuse_export_collector_configured=True,
            ),
            runtime,
        )
        assert disabled.status is LangfuseExportStatus.DISABLED
        assert missing.status is LangfuseExportStatus.NOT_CONFIGURED
        assert degraded.status is LangfuseExportStatus.DEGRADED
    finally:
        shutdown_tracing(runtime)

    local_text = (ROOT / "deploy" / "otel-collector-test.yaml").read_text(encoding="utf-8")
    remote_text = (ROOT / "deploy" / "otel-collector-langfuse-test.yaml").read_text(
        encoding="utf-8"
    )
    local = yaml.safe_load(local_text)
    remote = yaml.safe_load(remote_text)
    assert local["service"]["pipelines"]["traces"]["exporters"] == ["file"]
    assert remote["service"]["pipelines"]["traces/local"]["exporters"] == ["file"]
    assert local["service"]["pipelines"]["metrics"]["exporters"] == ["prometheus"]
    assert remote["service"]["pipelines"]["metrics"]["exporters"] == ["prometheus"]
    assert local["exporters"]["prometheus"]["endpoint"] == "127.0.0.1:9464"
    assert remote["exporters"]["prometheus"]["endpoint"] == "127.0.0.1:9464"
    langfuse = remote["exporters"]["otlphttp/langfuse"]
    assert langfuse["endpoint"] == "${env:LANGFUSE_OTLP_ENDPOINT}"
    assert langfuse["headers"]["Authorization"] == "${env:LANGFUSE_OTLP_AUTHORIZATION}"
    assert langfuse["retry_on_failure"]["max_elapsed_time"] == "60s"
    assert langfuse["sending_queue"]["queue_size"] == 512
    for forbidden in (
        "gen_ai.input.messages",
        "gen_ai.output.messages",
        "langfuse.observation.input",
        "langfuse.observation.output",
        "http.request.header.authorization",
        "user.id",
    ):
        assert forbidden in local_text
        assert forbidden in remote_text
    assert "public_key" not in remote_text.lower()
    assert "secret_key" not in remote_text.lower()
    rules = yaml.safe_load(
        (ROOT / "deploy" / "prometheus-alert-rules.yaml").read_text(encoding="utf-8")
    )
    alerts = {item["alert"]: item for item in rules["groups"][0]["rules"]}
    assert {
        "P0PythonModelConfigurationFailure",
        "P1PythonModelSustainedFailure",
        "P1PythonContextOrMemoryFailure",
        "P2PythonToolFailureRate",
        "P1PythonProviderSubmissionUnknown",
        "P2PythonWorkerNoProcessing",
        "P2PythonWorkerFailures",
        "P1PythonResultHandoffFailure",
        "P1PythonWorkflowSustainedFailureRate",
        "P1PythonWorkflowControlPlaneUnavailable",
        "P1PythonWorkflowLeaseFencingSpike",
        "P1PythonWorkflowWaitingTaskBacklog",
        "P2PythonWorkflowReplanFailure",
        "P1PythonWorkflowProviderUnknownBacklog",
    } <= alerts.keys()
    rendered_rules = repr(rules)
    for forbidden_label in ("userId", "runId", "taskId", "conversationId", "traceId"):
        assert forbidden_label not in rendered_rules


def test_low_cardinality_metrics_count_real_failures_retries_and_worker_state() -> None:
    reader = InMemoryMetricReader()
    runtime = configure_tracing(
        AppSettings(otel_metrics_enabled=True),
        process_role="image-worker",
        metric_reader=reader,
    )
    try:
        with start_span(
            "model.deepseek.intent",
            attributes={
                "model.provider": "DEEPSEEK",
                "model.name": "deepseek-flash",
                "model.operation": "intent",
            },
        ) as span:
            span.set("usage.requestCount", 2)
            span.set("usage.inputTokens", 120)
            span.set("usage.outputTokens", 7)
            span.set("attemptCount", 2)
            span.fail("DEEPSEEK_TIMEOUT")
        with start_span("agent.context.load") as span:
            span.set("context.source", "JAVA")
            span.fail("JAVA_RUN_CONTEXT_BUDGET_EXCEEDED")
        with start_span(
            "provider.poll",
            attributes={
                "provider.name": "QWEN_IMAGE",
                "provider.model": "qwen-image-3.0",
                "provider.operation": "poll",
            },
        ) as span:
            for _ in range(3):
                record_provider_call()
            span.set("provider.submissionState", "UNKNOWN")
            span.fail("PROVIDER_SUBMISSION_UNKNOWN")
        with start_span("java.result.handoff") as span:
            span.fail("JAVA_RESULT_HANDOFF_FAILED")
        record_tool_policy_denial(
            "TOOL_GROUP_NOT_AUTHORIZED",
            tool_name="task_draft.propose",
            tool_version="1",
            risk_level="PREPARE",
        )
        adjust_worker_inflight(1, task_type="IMAGE")
        record_worker_task("FAILED", task_type="IMAGE")
        adjust_worker_inflight(-1, task_type="IMAGE")

        points = _metric_points(reader)
        data = reader.get_metrics_data()
        assert data is not None
        assert all(
            "service.instance.id" not in resource_metrics.resource.attributes
            for resource_metrics in data.resource_metrics
        )
        assert _metric_sum(points, "guanghe.model.requests") == 2
        assert _metric_sum(points, "guanghe.model.retries") == 1
        assert _metric_sum(points, "guanghe.model.repairs") == 1
        assert _metric_sum(points, "guanghe.model.input_tokens") == 120
        assert _metric_sum(points, "guanghe.model.output_tokens") == 7
        model_labels = {point[0].get("model") for point in points["guanghe.model.requests"]}
        assert model_labels == {"DEEPSEEK-FLASH"}
        assert _metric_sum(points, "guanghe.context.loads") == 1
        assert _metric_sum(points, "guanghe.provider.calls") == 3
        assert _metric_sum(points, "guanghe.tool.policy_denials") == 1
        assert _metric_sum(points, "guanghe.worker.tasks") == 1
        assert _metric_sum(points, "guanghe.worker.inflight") == 0
        failure_categories = {
            point[0]["error.category"]
            for point in points["guanghe.failures"]
            if "error.category" in point[0]
        }
        assert failure_categories == {
            "MODEL_TRANSPORT",
            "BUDGET",
            "PROVIDER_UNKNOWN",
            "RESULT_HANDOFF",
            "TOOL_POLICY",
        }
    finally:
        shutdown_tracing(runtime)


def test_metrics_drop_sensitive_or_high_cardinality_attributes() -> None:
    reader = InMemoryMetricReader()
    runtime = configure_tracing(
        AppSettings(otel_metrics_enabled=True),
        process_role="api",
        metric_reader=reader,
    )
    try:
        with start_span(
            "model.deepseek.intent",
            attributes={
                "model.provider": "DEEPSEEK",
                "model.name": "deepseek-chat",
                "model.operation": "intent",
                "run.ref": "sha256:0123456789abcdef",
                "task.ref": "sha256:fedcba9876543210",
                "traceId": "1" * 32,
                "prompt": "sensitive prompt body",
                "Authorization": "Bearer secret",
                "providerTaskId": "provider-sensitive-id",
            },
        ) as span:
            span.set("usage.requestCount", 1)
        rendered = repr(_metric_points(reader))
        for forbidden in (
            "family",
            "run.ref",
            "task.ref",
            "traceId",
            "sensitive prompt body",
            "Bearer secret",
            "provider-sensitive-id",
            "Authorization",
        ):
            assert forbidden not in rendered
    finally:
        shutdown_tracing(runtime)


def test_workflow_metrics_use_only_low_cardinality_stage_labels() -> None:
    reader = InMemoryMetricReader()
    runtime = configure_tracing(
        AppSettings(otel_metrics_enabled=True),
        process_role="workflow-worker",
        metric_reader=reader,
    )
    try:
        with start_span(
            "workflow.claim",
            attributes={
                "task.type": "WORKFLOW",
                "workflow.stage": "CLAIM",
                "workflow.planRef": "sha256:0123456789abcdef",
                "workflow.stepId": "step-987654321",
                "execution.attempt": 31,
                "traceId": "1" * 32,
            },
        ) as span:
            span.fail("WORKFLOW_JAVA_CONTROL_UNAVAILABLE")
        with start_span(
            "workflow.resume",
            attributes={
                "task.type": "WORKFLOW",
                "workflow.stage": "RESUME",
                "workflow.outcome": "RESUMED",
            },
        ):
            pass

        points = _metric_points(reader)
        assert _metric_sum(points, "guanghe.workflow.events") == 2
        labels = [item[0] for item in points["guanghe.workflow.events"]]
        assert {item["workflow.stage"] for item in labels} == {"CLAIM", "RESUME"}
        failed = next(item for item in labels if item["workflow.stage"] == "CLAIM")
        assert failed["error.category"] == "WORKER_CONTROL"
        assert failed["retry.strategy"] == "SAFE_RETRY"
        assert failed["retryable"] == "TRUE"
        rendered = repr(labels)
        for forbidden in (
            "workflow.planRef",
            "workflow.stepId",
            "execution.attempt",
            "traceId",
            "0123456789abcdef",
            "987654321",
        ):
            assert forbidden not in rendered
    finally:
        shutdown_tracing(runtime)


def test_workflow_failure_span_records_stage_without_exception_detail() -> None:
    exporter = InMemorySpanExporter()
    runtime = configure_tracing(
        AppSettings(otel_enabled=True, otel_sample_ratio=1),
        process_role="workflow-worker",
        span_exporter=exporter,
        synchronous_export=True,
    )
    try:
        with start_span(
            "workflow.claim",
            attributes={"task.type": "WORKFLOW", "workflow.stage": "CLAIM"},
        ) as span:
            span.fail("WORKFLOW_JAVA_CONTROL_UNAVAILABLE")
        finished = exporter.get_finished_spans()[0]
        assert finished.attributes is not None
        assert finished.attributes["failure.stage"] == "CLAIM"
        assert finished.attributes["failure.category"] == "WORKER_CONTROL"
        assert finished.attributes["failure.retryStrategy"] == "SAFE_RETRY"
        assert "response-body" not in repr(finished)
    finally:
        shutdown_tracing(runtime)


def test_failure_metric_taxonomy_covers_deterministic_failure_gates() -> None:
    reader = InMemoryMetricReader()
    runtime = configure_tracing(
        AppSettings(otel_metrics_enabled=True),
        process_role="api",
        metric_reader=reader,
    )
    cases = (
        ("model.deepseek.intent", "DEEPSEEK_AUTHENTICATION_FAILED"),
        ("model.deepseek.intent", "DEEPSEEK_BALANCE_INSUFFICIENT"),
        ("model.deepseek.intent", "DEEPSEEK_INTENT_RESPONSE_INVALID"),
        ("agent.context.load", "PROCESS_MEMORY_PRESSURE"),
        ("tool.catalog.inspect", "TOOL_EXECUTION_FAILED"),
    )
    try:
        for operation, code in cases:
            with start_span(operation) as span:
                if operation.startswith("model."):
                    span.set("usage.requestCount", 1)
                span.fail(code)
        points = _metric_points(reader)
        categories = {item[0]["error.category"]: item[1] for item in points["guanghe.failures"]}
        assert categories["MODEL_CONFIGURATION"] == 2
        assert categories["MODEL_OUTPUT"] == 1
        assert categories["CONTEXT"] == 1
        assert categories["TOOL_EXECUTION"] == 1
        assert _metric_sum(points, "guanghe.model.requests") == 3
        assert _metric_sum(points, "guanghe.tool.calls") == 1
    finally:
        shutdown_tracing(runtime)


def _metric_points(
    reader: InMemoryMetricReader,
) -> dict[str, list[tuple[dict[str, object], int | float]]]:
    collected: dict[str, list[tuple[dict[str, object], int | float]]] = {}
    data = reader.get_metrics_data()
    assert data is not None
    for resource_metrics in data.resource_metrics:
        for scope_metrics in resource_metrics.scope_metrics:
            for metric in scope_metrics.metrics:
                points: list[tuple[dict[str, object], int | float]] = []
                for point in metric.data.data_points:
                    value = getattr(point, "value", None)
                    attributes = dict(cast(Mapping[str, object], point.attributes))
                    points.append((attributes, value if value is not None else 0))
                collected[metric.name] = points
    return collected


def _metric_sum(
    points: dict[str, list[tuple[dict[str, object], int | float]]],
    name: str,
) -> int | float:
    return sum(value for _attributes, value in points[name])
