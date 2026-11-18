package com.guanghe.backend.integration.internal.client;

public record PythonReverseTraceProbeRequest(String probe) {

    public PythonReverseTraceProbeRequest {
        if (probe == null || probe.isBlank() || probe.length() > 64) {
            throw new IllegalArgumentException("probe must contain between 1 and 64 characters");
        }
    }
}
