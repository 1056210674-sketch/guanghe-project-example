package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class GeoCompetitiveRecords {
    private GeoCompetitiveRecords() { }
    public record Candidate(long id, long userId, long spaceId, long topicId, String name,
            String nameNormalized, String entityKind, String status, int version, Long competitorId,
            long firstReportId, long lastReportId, String evidenceJson, OffsetDateTime firstSeenAt,
            OffsetDateTime lastSeenAt) { }
    public record Insight(long id, long reportId, long userId, String inputHash, String status, int attempts,
            UUID claimToken, OffsetDateTime leaseExpiresAt, OffsetDateTime deadlineAt, String resultJson,
            String lastErrorCode, String traceparent, OffsetDateTime availableAt,
            OffsetDateTime capacityWaitUntil, OffsetDateTime finishedAt) { }
    public record Claim(Insight work, ObjectNode request, long taskId, long runId) { }
    public record CatalogMatch(String nameNormalized, long competitorId) { }
}
