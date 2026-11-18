package com.guanghe.backend.common.observability;

import com.guanghe.backend.common.api.BusinessException;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Small, provider-neutral tracing facade. It deliberately accepts only a fixed
 * set of low-sensitive attributes so business payloads cannot accidentally be
 * exported as span data.
 */
@Component
public final class ObservabilitySupport {
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^00-([0-9a-f]{32})-([0-9a-f]{16})-(0[01])$");
    private static final Set<String> ATTRIBUTES = Set.of(
            "operation", "stage", "status", "errorCode", "retryNo", "executionNo",
            "runIdRef", "taskIdRef", "conversationIdRef", "userIdHash", "contextSource",
            "planIdRef", "planVersion", "contextBytes", "contextItems", "estimatedTokens", "dataGap",
            "taskType", "eventType", "provider", "model", "modelOperation",
            "resourceCount", "pageCount", "cache.result", "messaging.system",
            "messaging.destination.name", "messaging.operation.type",
            "telemetry.attribute.rejected");
    private static final Set<String> SENSITIVE_MARKERS = Set.of(
            "authorization", "bearer ", "password", "secret", "app_key", "appkey",
            "signature=", "token=", "x-internal-signature", "x-user-context-ref");

    private final Tracer tracer;

    @Autowired
    public ObservabilitySupport(ObjectProvider<Tracer> tracerProvider) {
        this(tracerProvider.getIfAvailable(() -> Tracer.NOOP));
    }

    public ObservabilitySupport(Tracer tracer) {
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
    }

    public static ObservabilitySupport noop() {
        return new ObservabilitySupport(Tracer.NOOP);
    }

    public <T> T observe(String name, String stage, Consumer<Operation> attributes, Supplier<T> action) {
        try (Operation operation = start(name, stage)) {
            attributes.accept(operation);
            try {
                return action.get();
            } catch (BusinessException exception) {
                operation.fail(exception.errorCode().name(), stage);
                throw exception;
            } catch (RuntimeException exception) {
                operation.fail("UNEXPECTED_FAILURE", stage);
                throw exception;
            }
        }
    }

    public void observe(String name, String stage, Consumer<Operation> attributes, Runnable action) {
        observe(name, stage, attributes, () -> {
            action.run();
            return null;
        });
    }

    public Operation start(String name, String stage) {
        return start(name, stage, null, false, null);
    }

    public Operation startClient(String name, String stage, String parentTraceparent) {
        return start(name, stage, parentTraceparent, false, Span.Kind.CLIENT);
    }

    public Operation startConsumer(String name, String stage, String parentTraceparent, boolean linkOnly) {
        return start(name, stage, parentTraceparent, linkOnly, Span.Kind.CONSUMER);
    }

    public Operation startProducer(String name, String stage, String parentTraceparent, boolean linkOnly) {
        return start(name, stage, parentTraceparent, linkOnly, Span.Kind.PRODUCER);
    }

    public String currentTraceparent() {
        Span current = tracer.currentSpan();
        if (current == null || current.isNoop() || !valid(current.context())) {
            return null;
        }
        return "00-" + current.context().traceId() + "-" + current.context().spanId()
                + (Boolean.TRUE.equals(current.context().sampled()) ? "-01" : "-00");
    }

    public String reference(Object value) {
        if (value == null) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to build telemetry reference", exception);
        }
    }

    private Operation start(String name, String stage, String traceparent, boolean linkOnly, Span.Kind kind) {
        Span.Builder builder = tracer.spanBuilder().name(name);
        if (kind != null) {
            builder.kind(kind);
        }
        TraceContext carried = parse(traceparent);
        if (carried != null) {
            if (linkOnly) {
                builder.setNoParent().addLink(new Link(carried));
            } else {
                builder.setParent(carried);
            }
        } else if (linkOnly) {
            builder.setNoParent();
        }
        Span span = builder.start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        Operation operation = new Operation(span, scope);
        operation.tag("operation", name);
        operation.tag("stage", stage);
        return operation;
    }

    private TraceContext parse(String value) {
        if (value == null) {
            return null;
        }
        var matcher = TRACEPARENT.matcher(value.toLowerCase(Locale.ROOT));
        if (!matcher.matches() || matcher.group(1).chars().allMatch(c -> c == '0')
                || matcher.group(2).chars().allMatch(c -> c == '0')) {
            return null;
        }
        int flags = Integer.parseInt(matcher.group(3), 16);
        return tracer.traceContextBuilder().traceId(matcher.group(1)).spanId(matcher.group(2))
                .sampled((flags & 1) == 1).build();
    }

    private boolean valid(TraceContext context) {
        return context != null && context.traceId() != null && context.spanId() != null
                && context.traceId().matches("[0-9a-f]{32}")
                && context.spanId().matches("[0-9a-f]{16}");
    }

    public static final class Operation implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope scope;
        private boolean failed;

        private Operation(Span span, Tracer.SpanInScope scope) {
            this.span = span;
            this.scope = scope;
        }

        public Operation tag(String key, Object value) {
            if (value == null) {
                return this;
            }
            String text = String.valueOf(value);
            if (!ATTRIBUTES.contains(key) || unsafe(text)) {
                span.tag("telemetry.attribute.rejected", "true");
                return this;
            }
            span.tag(key, text);
            return this;
        }

        public void fail(String errorCode, String stage) {
            failed = true;
            tag("status", "ERROR");
            tag("errorCode", errorCode);
            tag("stage", stage);
        }

        @Override
        public void close() {
            if (!failed) {
                tag("status", "OK");
            }
            scope.close();
            span.end();
        }

        private boolean unsafe(String value) {
            if (value.length() > 128 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                    || value.contains("://") || value.matches("(?i)^eyJ[A-Za-z0-9_-]+\\..*")) {
                return true;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            return SENSITIVE_MARKERS.stream().anyMatch(lower::contains);
        }
    }
}
