package com.guanghe.backend.integration.internal.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PythonChatRunEvent(
        int schemaVersion,
        UUID eventId,
        String eventType,
        UUID runId,
        OffsetDateTime occurredAt,
        long sequence,
        JsonNode payload
) {
}
