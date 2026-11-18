package com.guanghe.backend.common.web;

import java.security.SecureRandom;
import java.util.HexFormat;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;

public final class TraceIdHolder {

    public static final String MDC_KEY = "traceId";
    static final String REQUEST_MDC_KEY = "requestTraceId";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIdHolder() {
    }

    public static String currentTraceId() {
        String requestTraceId = requestTraceId();
        if (validTraceId(requestTraceId)) {
            return requestTraceId;
        }
        SpanContext context = Span.current().getSpanContext();
        if (context.isValid()) {
            return context.getTraceId();
        }
        return requestTraceId == null ? "unavailable" : requestTraceId;
    }

    public static String outboundTraceparent() {
        String requestTraceId = requestTraceId();
        SpanContext context = Span.current().getSpanContext();
        if (context.isValid() && (!validTraceId(requestTraceId)
                || requestTraceId.equals(context.getTraceId()))) {
            return "00-" + context.getTraceId() + "-" + context.getSpanId()
                    + (context.isSampled() ? "-01" : "-00");
        }
        String traceId = currentTraceId();
        if (!validTraceId(traceId)) {
            traceId = randomHex(16);
        }
        return "00-" + traceId + "-" + randomHex(8) + "-01";
    }

    private static String requestTraceId() {
        String isolatedRequestTraceId = MDC.get(REQUEST_MDC_KEY);
        return validTraceId(isolatedRequestTraceId)
                ? isolatedRequestTraceId
                : MDC.get(MDC_KEY);
    }

    private static boolean validTraceId(String value) {
        return value != null && value.matches("[0-9a-f]{32}")
                && !value.chars().allMatch(character -> character == '0');
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        do {
            RANDOM.nextBytes(bytes);
        } while (allZero(bytes));
        return HexFormat.of().formatHex(bytes);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }
}
