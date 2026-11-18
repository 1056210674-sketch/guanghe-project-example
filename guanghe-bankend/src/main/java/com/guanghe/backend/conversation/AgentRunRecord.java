package com.guanghe.backend.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentRunRecord(
        UUID id,
        long conversationId,
        long userId,
        long requestMessageId,
        Long responseMessageId,
        Long selectedSpaceId,
        Long selectedProductId,
        String attachmentAssetIdsJson,
        String status,
        int contextVersion,
        String contextSnapshotJson,
        String contextRefHash,
        OffsetDateTime contextExpiresAt,
        String pythonEventsUrl,
        long lastPythonSequence,
        String usageJson,
        String errorCode,
        String traceId,
        OffsetDateTime stopRequestedAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deadlineAt
) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public AgentRunRecord {
    }

    public AgentRunRecord(UUID id, long conversationId, long userId, long requestMessageId,
                          Long responseMessageId, Long selectedSpaceId, Long selectedProductId,
                          String attachmentAssetIdsJson, String status, int contextVersion,
                          String contextSnapshotJson, String contextRefHash, OffsetDateTime contextExpiresAt,
                          String pythonEventsUrl, long lastPythonSequence, String usageJson, String errorCode,
                          String traceId, OffsetDateTime stopRequestedAt, OffsetDateTime startedAt,
                          OffsetDateTime completedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, conversationId, userId, requestMessageId, responseMessageId, selectedSpaceId,
                selectedProductId, attachmentAssetIdsJson, status, contextVersion, contextSnapshotJson,
                contextRefHash, contextExpiresAt, pythonEventsUrl, lastPythonSequence, usageJson,
                errorCode, traceId, stopRequestedAt, startedAt, completedAt, createdAt, updatedAt, null);
    }

    public OffsetDateTime effectiveDeadline() {
        return deadlineAt == null ? contextExpiresAt : deadlineAt;
    }
}
