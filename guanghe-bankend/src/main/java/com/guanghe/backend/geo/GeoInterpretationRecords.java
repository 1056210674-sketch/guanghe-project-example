package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class GeoInterpretationRecords {
    private GeoInterpretationRecords() { }

    public record Work(long id, long reportId, long userId, String inputHash, String status,
                       int attempts, UUID claimToken, OffsetDateTime leaseExpiresAt,
                       OffsetDateTime deadlineAt, OffsetDateTime requestedAt,
                       OffsetDateTime startedAt, OffsetDateTime finishedAt,
                       String lastErrorCode, String traceparent,
                       OffsetDateTime availableAt, OffsetDateTime capacityWaitUntil) { }

    public record Claim(Work work, ObjectNode request, long taskId, long runId) { }
}
