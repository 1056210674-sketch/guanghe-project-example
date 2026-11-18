package com.guanghe.backend.common.observability;

import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilitySupportTests {

    @Test
    void preservesParentChildAndCreatesLinkedRecoveryTrace() {
        SimpleTracer tracer = new SimpleTracer();
        ObservabilitySupport support = new ObservabilitySupport(tracer);

        try (ObservabilitySupport.Operation parent = support.start("java.task.confirm", "CONFIRM")) {
            support.observe("java.points.freeze", "FREEZE", span -> span.tag("taskType", "IMAGE"), () -> { });
        }

        List<SimpleSpan> spans = List.copyOf(tracer.getSpans());
        SimpleSpan parent = named(spans, "java.task.confirm");
        SimpleSpan child = named(spans, "java.points.freeze");
        assertThat(child.getTraceId()).isEqualTo(parent.getTraceId());
        assertThat(child.getParentId()).isEqualTo(parent.getSpanId());

        String carried = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01";
        try (ObservabilitySupport.Operation ignored = support.startConsumer(
                "java.task.recover", "EXECUTION_LEASE_EXPIRED", carried, true)) {
            // linked recovery intentionally has no remote parent
        }
        SimpleSpan recovery = named(List.copyOf(tracer.getSpans()), "java.task.recover");
        assertThat(recovery.getTraceId()).isNotEqualTo("a".repeat(32));
        assertThat(recovery.getParentId()).isEmpty();
        assertThat(recovery.getLinks()).singleElement().satisfies(link ->
                assertThat(link.getTraceContext().traceId()).isEqualTo("a".repeat(32)));
    }

    @Test
    void rejectsSensitiveAttributesAndRecordsOnlyStableFailureData() {
        SimpleTracer tracer = new SimpleTracer();
        ObservabilitySupport support = new ObservabilitySupport(tracer);

        assertThatThrownBy(() -> support.observe("java.media.sync", "FETCH_AND_PERSIST", span -> {
            span.tag("provider", "https://provider.example/path?signature=secret");
            span.tag("not.allowed", "also-secret");
        }, () -> {
            throw new IllegalStateException("raw secret exception text");
        })).isInstanceOf(IllegalStateException.class);

        SimpleSpan span = named(List.copyOf(tracer.getSpans()), "java.media.sync");
        assertThat(span.getTags())
                .containsEntry("telemetry.attribute.rejected", "true")
                .containsEntry("errorCode", "UNEXPECTED_FAILURE")
                .containsEntry("stage", "FETCH_AND_PERSIST")
                .doesNotContainKeys("provider", "not.allowed");
        assertThat(span.getTags().values()).noneMatch(value ->
                value.contains("provider.example") || value.contains("secret"));
        assertThat(span.getError()).isNull();
    }

    private SimpleSpan named(List<SimpleSpan> spans, String name) {
        return spans.stream().filter(span -> name.equals(span.getName())).findFirst().orElseThrow();
    }
}
