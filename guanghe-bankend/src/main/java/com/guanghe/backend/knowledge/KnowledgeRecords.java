package com.guanghe.backend.knowledge;

import java.time.OffsetDateTime;

public final class KnowledgeRecords {
    private KnowledgeRecords() { }

    public record Document(long id, long userId, long projectId, String name, String sourceType,
            String status, int currentVersion, String failureCode, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, OffsetDateTime deletedAt) { }
    public record DocumentVersion(long documentId, long userId, long projectId, int version,
            String sha256, String contentType, long sizeBytes, String sourceUrl, String objectKey,
            String paragraphsJson, Integer pageCount, OffsetDateTime createdAt) { }
    public record DocumentProjection(long id, long userId, long projectId, String name, String sourceType,
            String status, int currentVersion, String failureCode, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, String sha256, String contentType, long sizeBytes,
            String sourceUrl, String paragraphsJson, Integer pageCount) { }
    public record Preference(long id, long userId, Long projectId, String content, String contentHash,
            String sourceType, Long sourceMessageId, int version, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, OffsetDateTime deletedAt) { }
    public record Counts(long writtenArticleCount, long publishedArticleCount, long publishedPlacementCount) { }
}
