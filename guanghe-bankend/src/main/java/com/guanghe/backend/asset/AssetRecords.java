package com.guanghe.backend.asset;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.AutomapConstructor;

public final class AssetRecords {
    private AssetRecords() { }

    public record Asset(long id, long userId, long spaceId, Long primaryProductId,
                        String assetType, String sourceType, Long sourceTaskId, Integer sourceArtifactIndex, String name,
                        String objectKey, String contentType, long sizeBytes, String sha256,
                        Integer width, Integer height, String durationSeconds, String status,
                        String safetyStatus, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        @AutomapConstructor
        public Asset { }

        public Asset(long id, long userId, long spaceId, Long primaryProductId,
                     String assetType, String sourceType, Long sourceTaskId, String name,
                     String objectKey, String contentType, long sizeBytes, String sha256,
                     Integer width, Integer height, String durationSeconds, String status,
                     String safetyStatus, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this(id, userId, spaceId, primaryProductId, assetType, sourceType, sourceTaskId,
                    sourceTaskId == null ? null : 1, name, objectKey, contentType, sizeBytes, sha256,
                    width, height, durationSeconds, status, safetyStatus, createdAt, updatedAt);
        }
    }

    public record UploadReceipt(long id, long userId, UUID idempotencyKey,
                                String requestHash, long assetId, long spaceId,
                                OffsetDateTime createdAt) { }

    /**
     * Server-resolved attachment provenance for the immediately preceding USER message.
     * assetId is present only when that message contains exactly one attachment.
     */
    public record PreviousUserAttachment(long messageId, int attachmentCount, Long assetId) { }

    /** Read-only provenance for a bounded, same-project creative continuation. */
    public record ContinuationImage(long assetId, long sourceMessageId) { }

    public record IngestionWork(long id, long assetId, long taskId, long executionId,
                                int executionNo, long userId, long spaceId, Long productId,
                                int artifactIndex, String providerCode, String providerModel,
                                UUID providerRequestId, String providerTaskId, String providerUrl,
                                OffsetDateTime providerUrlExpiresAt, String expectedContentType,
                                Long expectedSizeBytes, String expectedSha256, Integer expectedWidth,
                                Integer expectedHeight, BigDecimal expectedDurationSeconds,
                                String expectedAspectRatio, String expectedResolution,
                                OffsetDateTime ingestionDeadlineAt,
                                String objectKey, String status, int attempt,
                                int maxAttempts, OffsetDateTime availableAt,
                                OffsetDateTime leaseExpiresAt, UUID claimToken, String lastErrorCode,
                                OffsetDateTime completedAt, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt) {
        @AutomapConstructor
        public IngestionWork {
        }

        public IngestionWork(long id, long assetId, long taskId, long executionId,
                             int executionNo, long userId, long spaceId, Long productId,
                             int artifactIndex, String providerCode, String providerModel,
                             UUID providerRequestId, String providerTaskId, String providerUrl,
                             OffsetDateTime providerUrlExpiresAt, String expectedContentType,
                             Long expectedSizeBytes, String expectedSha256, int expectedWidth,
                             int expectedHeight, String objectKey, String status, int attempt,
                             int maxAttempts, OffsetDateTime availableAt,
                             OffsetDateTime leaseExpiresAt, UUID claimToken, String lastErrorCode,
                             OffsetDateTime completedAt, OffsetDateTime createdAt,
                             OffsetDateTime updatedAt) {
            this(id, assetId, taskId, executionId, executionNo, userId, spaceId, productId,
                    artifactIndex, providerCode, providerModel, providerRequestId, providerTaskId,
                    providerUrl, providerUrlExpiresAt, expectedContentType, expectedSizeBytes,
                    expectedSha256, expectedWidth, expectedHeight, null, null, null, null, objectKey, status,
                    attempt, maxAttempts, availableAt, leaseExpiresAt, claimToken, lastErrorCode,
                    completedAt, createdAt, updatedAt);
        }
    }
}
