package com.guanghe.backend.integration.internal.client;

import java.util.regex.Pattern;

public record PythonReverseTraceProbeResponse(
        boolean accepted,
        String callerKeyId,
        String traceId
) {

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    public PythonReverseTraceProbeResponse {
        if (!accepted) {
            throw new IllegalArgumentException("reverse probe response must be accepted");
        }
        if (callerKeyId == null || callerKeyId.isBlank()) {
            throw new IllegalArgumentException("callerKeyId must not be blank");
        }
        if (traceId == null || !TRACE_ID.matcher(traceId).matches()) {
            throw new IllegalArgumentException("traceId must be a lowercase 32 character hexadecimal value");
        }
    }
}
