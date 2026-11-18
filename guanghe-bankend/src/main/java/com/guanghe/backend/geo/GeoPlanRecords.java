package com.guanghe.backend.geo;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class GeoPlanRecords {
    private GeoPlanRecords() { }

    public record Plan(long id, long userId, long spaceId, Long productId, long topicId,
                       long sourceDraftId, UUID createIdempotencyKey, String requestHash,
                       String name, String nameNormalized, String status, String scheduleType,
                       String scheduleJson, String inputSnapshotJson, String inputHash,
                       long pricingVersionId, int estimatedPointsPerRun, int maxPointsPerRun,
                       OffsetDateTime nextRunAt, OffsetDateTime lastScheduledSlot,
                       String pauseReason, int version, OffsetDateTime createdAt,
                       OffsetDateTime updatedAt) { }

    public record Slot(long id, long planId, long userId, OffsetDateTime scheduledSlot,
                       String status, Long taskId, Integer quotedPoints, String errorCode,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record DispatchResult(String disposition, Long taskId, Integer quotedPoints,
                                 String errorCode) { }
}
