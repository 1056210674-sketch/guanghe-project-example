package com.guanghe.backend.media;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

final class MediaRecords {
    private MediaRecords() { }

    record Resource(long id, String providerCode, String upstreamModelId, String upstreamResourceId,
                    String name, String status, String resourceType, String channelCategory, String region,
                    Integer estimatedDays, String capabilitiesJson, BigDecimal upstreamPrice,
                    String rawJson, OffsetDateTime lastSeenAt, OffsetDateTime syncedAt, int version) { }

    record Price(long id, long mediaResourceId, String providerCode, String upstreamModelId,
                 String upstreamResourceId, BigDecimal upstreamPrice, BigDecimal markupRate,
                 int saleAmountCents, int chargedPoints, OffsetDateTime quotedAt,
                 OffsetDateTime expiresAt) { }

    record Batch(long id, long taskId, long draftId, long userId, long spaceId, Long productId,
                 long articleId, long articleSnapshotId, String status, int quotedPoints,
                 int settledPoints, int releasedPoints, OffsetDateTime createdAt,
                 OffsetDateTime updatedAt, OffsetDateTime finishedAt) { }

    record Submission(long id, long batchId, long taskId, long userId, long spaceId,
                      long articleId, long articleSnapshotId, long mediaResourceId,
                      long priceSnapshotId, String providerCode, String upstreamModelId,
                      String upstreamResourceId, String mediaName, BigDecimal upstreamPrice,
                      BigDecimal markupRate, int saleAmountCents, int chargedPoints, String remark,
                      String customerOrderId, String upstreamOrderId, String upstreamOrderNo,
                      String upstreamStatus, String status, String submissionState,
                      String publishedUrl, String publishedUrlsJson, String stableErrorCode,
                      OffsetDateTime submitAttemptedAt,
                      OffsetDateTime nextPollAt, OffsetDateTime publishedAt,
                      OffsetDateTime rejectedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    record Event(long id, long submissionId, long batchId, long userId, long sequenceNo,
                 String eventType, String fromStatus, String toStatus, String stableErrorCode,
                 OffsetDateTime createdAt) { }

    record LocalOutbox(long id, long userId, long taskId, String payloadJson, String traceparent,
                       int attempts, OffsetDateTime createdAt) { }

    /**
     * The operator's view of a callback receipt.
     *
     * `payloadHash` is deliberately absent: it is a digest of the raw upstream body, and carrying it
     * into the back office would put a value derived from an unredacted payload one accidental log
     * line away from disclosure. The receipt id is the identifier operators get instead.
     */
    record Callback(long id, String providerCode, String customerOrderId, String upstreamModelId,
                    String upstreamResourceId, String eventType, String status, int attempts,
                    String stableErrorCode, OffsetDateTime receivedAt, OffsetDateTime processedAt,
                    OffsetDateTime updatedAt) { }

    record SyncState(String providerCode, String upstreamModelId, String status,
                     OffsetDateTime lastAttemptedAt, OffsetDateTime lastSucceededAt,
                     String lastErrorCode, int lastResourceCount, int version,
                     OffsetDateTime updatedAt) { }
}
