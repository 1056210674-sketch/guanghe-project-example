package com.guanghe.backend.integration.internal.client;

import java.util.UUID;

public record PythonStopRunResponse(UUID runId, boolean accepted, String status) {
}
