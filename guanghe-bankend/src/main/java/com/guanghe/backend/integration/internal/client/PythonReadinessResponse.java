package com.guanghe.backend.integration.internal.client;

import java.util.List;
import java.util.Objects;

public record PythonReadinessResponse(
        Status status,
        String service,
        String serviceVersion,
        List<Check> checks
) {

    public PythonReadinessResponse {
        Objects.requireNonNull(status, "status");
        requireText(service, "service");
        requireText(serviceVersion, "serviceVersion");
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
    }

    public enum Status {
        READY,
        NOT_READY
    }

    public record Check(String name, Status status, String reasonCode) {

        public Check {
            requireText(name, "name");
            Objects.requireNonNull(status, "status");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
