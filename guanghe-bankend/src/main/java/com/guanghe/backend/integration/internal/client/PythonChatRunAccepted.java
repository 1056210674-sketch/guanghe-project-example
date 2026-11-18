package com.guanghe.backend.integration.internal.client;

import java.util.UUID;

public record PythonChatRunAccepted(UUID runId, String status, String eventsUrl) {
}
