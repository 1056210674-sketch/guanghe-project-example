package com.guanghe.backend.task;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.AutomapConstructor;

public final class TaskRecords {
    private TaskRecords() { }

    public record Draft(long id, long userId, Long conversationId, UUID agentRunId,
                        Long previousDraftId, String draftSource, String operationScope,
                        UUID idempotencyKey, String requestHash,
                        String taskType, int parameterSchemaVersion, long spaceId, Long productId,
                        String parametersJson, String inputSnapshotJson, String inputHash,
                        long pricingVersionId, int quotedPoints, String warningsJson, String status,
                        OffsetDateTime expiresAt, OffsetDateTime confirmedAt,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record Confirmation(long id, long draftId, long userId, String tokenHash,
                               String inputHash, long pricingVersionId, String status,
                               OffsetDateTime expiresAt, OffsetDateTime usedAt,
                               OffsetDateTime createdAt) { }

    public record PointAccount(long userId, int availablePoints, int frozenPoints, int version,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record TaskFunding(long taskId, int frozenPoints, int settledPoints, int releasedPoints) { }

    public record PointLedgerEntry(long id, long userId, int availableDelta, int frozenDelta,
                                   int availableAfter, int frozenAfter, String entryType,
                                   String businessType, long businessId, OffsetDateTime createdAt) { }

    public record ActiveArticle(long articleId, int articleVersion, String workflowStage,
                                String title, Long productId) { }

    public record Task(long id, long userId, long spaceId, Long productId, Long conversationId,
                       Long draftId, String taskType, String status, String stageCode,
                       int snapshotSchemaVersion, String inputSnapshotJson, String inputHash,
                       long pricingVersionId, int quotedPoints, int settledPoints,
                       String resultSummaryJson, String errorCode, String operationScope, UUID idempotencyKey,
                       String requestHash, OffsetDateTime queuedAt, OffsetDateTime startedAt,
                       OffsetDateTime finishedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                       Long planId, OffsetDateTime scheduledSlot) {
        @AutomapConstructor
        public Task {
        }

        public Task(long id, long userId, long spaceId, Long productId, long conversationId,
                    long draftId, String taskType, String status, String stageCode,
                    int snapshotSchemaVersion, String inputSnapshotJson, String inputHash,
                    long pricingVersionId, int quotedPoints, int settledPoints,
                    String resultSummaryJson, String errorCode, String operationScope,
                    UUID idempotencyKey, String requestHash, OffsetDateTime queuedAt,
                    OffsetDateTime startedAt, OffsetDateTime finishedAt, OffsetDateTime createdAt,
                    OffsetDateTime updatedAt) {
            this(id, userId, spaceId, productId, Long.valueOf(conversationId), Long.valueOf(draftId), taskType, status,
                    stageCode, snapshotSchemaVersion, inputSnapshotJson, inputHash,
                    pricingVersionId, quotedPoints, settledPoints, resultSummaryJson, errorCode,
                    operationScope, idempotencyKey, requestHash, queuedAt, startedAt, finishedAt,
                    createdAt, updatedAt, null, null);
        }
    }

    public record Execution(long id, long taskId, long userId, int executionNo, String status,
                            String consumerName, String workerId, String bundleVersion,
                            String executionTokenHash, OffsetDateTime leaseExpiresAt, String stageCode,
                            String providerCode, String providerModel, UUID providerRequestId,
                            String providerTaskId, String submissionState, Integer latencyMs,
                            String usageJson, String errorCode, OffsetDateTime lastHeartbeatAt,
                            OffsetDateTime startedAt, OffsetDateTime finishedAt,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record Outbox(long id, long userId, long aggregateId, int executionAttempt, String payloadJson,
                         String traceparent, String exchangeName, String routingKey, String status, int attempts,
                         OffsetDateTime availableAt, OffsetDateTime createdAt) {
        public Outbox(long id, long userId, long aggregateId, int executionAttempt, String payloadJson,
                      String traceparent, String status, int attempts,
                      OffsetDateTime availableAt, OffsetDateTime createdAt) {
            this(id, userId, aggregateId, executionAttempt, payloadJson, traceparent,
                    null, null, status, attempts, availableAt, createdAt);
        }
    }

    public record Inbox(long id, String consumerName, long eventId, long taskId, long userId,
                        Long executionId, String disposition, OffsetDateTime createdAt) { }
}
