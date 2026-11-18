package com.guanghe.backend.workflow;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.AutomapConstructor;

public final class WorkflowExecutionRecords {
    private WorkflowExecutionRecords() { }

    public record PlanState(long id, long userId, long conversationId, long spaceId, Long productId, int version,
                            String status, int totalQuotedPoints, int maxPointCost,
                            int maxSteps, int maxReplans, int maxModelCalls,
                            int maxToolCalls, int maxProviderCalls, OffsetDateTime deadlineAt,
                            UUID startIdempotencyKey, String startRequestHash,
                            OffsetDateTime executionStartedAt, OffsetDateTime finishedAt,
                            String lastErrorCode, int leaseRecoveryCount, String goalSummary,
                            String authorizedContextSnapshotJson, String authorizedContextHash,
                            Integer authorizedContextSchemaVersion, boolean authorizedContextDataGap) {
        @AutomapConstructor
        public PlanState {
        }

        public PlanState(long id, long userId, long conversationId, long spaceId, Long productId, int version,
                         String status, int totalQuotedPoints, int maxPointCost,
                         int maxSteps, int maxReplans, int maxModelCalls,
                         int maxToolCalls, int maxProviderCalls, OffsetDateTime deadlineAt,
                         UUID startIdempotencyKey, String startRequestHash,
                         OffsetDateTime executionStartedAt, OffsetDateTime finishedAt,
                         String lastErrorCode, int leaseRecoveryCount) {
            this(id, userId, conversationId, spaceId, productId, version, status,
                    totalQuotedPoints, maxPointCost, maxSteps, maxReplans, maxModelCalls,
                    maxToolCalls, maxProviderCalls, deadlineAt, startIdempotencyKey,
                    startRequestHash, executionStartedAt, finishedAt, lastErrorCode,
                    leaseRecoveryCount, "workflow goal", null, null, null, true);
        }

        public PlanState(long id, long userId, long conversationId, int version, String status,
                         int totalQuotedPoints, int maxPointCost, int maxSteps, int maxReplans,
                         int maxModelCalls, int maxToolCalls, int maxProviderCalls,
                         OffsetDateTime deadlineAt, UUID startIdempotencyKey, String startRequestHash,
                         OffsetDateTime executionStartedAt, OffsetDateTime finishedAt,
                         String lastErrorCode) {
            this(id, userId, conversationId, 1L, null, version, status, totalQuotedPoints,
                    maxPointCost, maxSteps, maxReplans, maxModelCalls, maxToolCalls,
                    maxProviderCalls, deadlineAt, startIdempotencyKey, startRequestHash,
                    executionStartedAt, finishedAt, lastErrorCode, 0,
                    "workflow goal", null, null, null, true);
        }
    }

    public record StepState(long id, long planId, long userId, long conversationId,
                            String stepKey, int ordinal, String stepType, String toolName,
                            String toolVersion, String riskLevel, String dependsOnJson,
                            String successCriteriaJson, String inputRefsJson, String outputRef,
                            Long taskDraftId, String taskType, Long pricingVersionId,
                            int quotedPoints, String status, int attempt, Long taskId,
                            String lastErrorCode, OffsetDateTime startedAt,
                            OffsetDateTime finishedAt, String authorizedInputHash,
                            OffsetDateTime authorizedDraftExpiresAt, UUID activationEventId,
                            String activationRequestHash, String taskResultRef,
                            String taskStatus) {
        @AutomapConstructor
        public StepState {
        }

        public StepState(long id, long planId, long userId, long conversationId,
                         String stepKey, int ordinal, String stepType, String toolName,
                         String toolVersion, String riskLevel, String dependsOnJson,
                         String successCriteriaJson, String inputRefsJson, String outputRef,
                         Long taskDraftId, String taskType, Long pricingVersionId,
                         int quotedPoints, String status, int attempt, Long taskId,
                         String lastErrorCode, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
            this(id, planId, userId, conversationId, stepKey, ordinal, stepType, toolName,
                    toolVersion, riskLevel, dependsOnJson, successCriteriaJson, inputRefsJson,
                    outputRef, taskDraftId, taskType, pricingVersionId, quotedPoints, status,
                    attempt, taskId, lastErrorCode, startedAt, finishedAt,
                    null, null, null, null, null, null);
        }
    }

    public record Execution(long id, long planId, long userId, long conversationId,
                            int planVersion, int executionNo, long claimEventId,
                            String status, String consumerName, String workerId,
                            String bundleVersion, String executionTokenHash,
                            String contextSnapshotJson, OffsetDateTime leaseExpiresAt,
                            OffsetDateTime lastHeartbeatAt, int replanCount,
                            String stableErrorCode, OffsetDateTime startedAt,
                            OffsetDateTime finishedAt, OffsetDateTime createdAt,
                            OffsetDateTime updatedAt) { }

    public record Observation(long id, long planId, long stepId, long executionId,
                              long userId, long conversationId, UUID eventId,
                              UUID toolCallId, String requestHash, int stepAttempt, String status,
                              String resultType, String resultRef, String summary,
                              String factsJson, String stableErrorCode, boolean retryable,
                              OffsetDateTime completedAt, OffsetDateTime createdAt) { }

    public record Reservation(long id, long planId, long userId, int authorizedPoints,
                              int frozenPoints, int allocatedPoints, int settledPoints,
                              int releasedPoints, String status, OffsetDateTime createdAt,
                              OffsetDateTime updatedAt) { }

    public record PointAccount(long userId, int availablePoints, int frozenPoints,
                               int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
}
