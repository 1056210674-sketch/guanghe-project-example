package com.guanghe.backend.workflow;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.AutomapConstructor;

public final class WorkflowPlanRecords {
    private WorkflowPlanRecords() { }

    public record Plan(long id, long userId, long conversationId, UUID sourceRunId,
                       long spaceId, Long productId, UUID proposalId, String requestHash,
                       String goalSummary, String status, int version, int stepCount,
                       int totalQuotedPoints, int maxSteps, int maxReplans, int maxModelCalls,
                       int maxToolCalls, int maxProviderCalls, int maxPointCost,
                       OffsetDateTime deadlineAt, OffsetDateTime confirmationExpiresAt,
                       OffsetDateTime abandonedAt, OffsetDateTime createdAt,
                       OffsetDateTime updatedAt, String authorizedContextSnapshotJson,
                       String authorizedContextHash, Integer authorizedContextSchemaVersion,
                       boolean authorizedContextDataGap) {
        @AutomapConstructor
        public Plan {
        }

        public Plan(long id, long userId, long conversationId, UUID sourceRunId,
                    long spaceId, Long productId, UUID proposalId, String requestHash,
                    String goalSummary, String status, int version, int stepCount,
                    int totalQuotedPoints, int maxSteps, int maxReplans, int maxModelCalls,
                    int maxToolCalls, int maxProviderCalls, int maxPointCost,
                    OffsetDateTime deadlineAt, OffsetDateTime confirmationExpiresAt,
                    OffsetDateTime abandonedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this(id, userId, conversationId, sourceRunId, spaceId, productId, proposalId,
                    requestHash, goalSummary, status, version, stepCount, totalQuotedPoints,
                    maxSteps, maxReplans, maxModelCalls, maxToolCalls, maxProviderCalls,
                    maxPointCost, deadlineAt, confirmationExpiresAt, abandonedAt, createdAt,
                    updatedAt, null, null, null, true);
        }
    }

    public record Step(long id, long planId, long userId, long conversationId,
                       String stepKey, int ordinal, String stepType, String toolName,
                       String toolVersion, String riskLevel, String dependsOnJson,
                       String successCriteriaJson, String inputRefsJson, String outputRef,
                       Long taskDraftId, String taskType, Long pricingVersionId,
                       int quotedPoints, String status, OffsetDateTime createdAt,
                       Long taskId, String taskStatus, String taskResultRef) {
        @AutomapConstructor
        public Step {
        }

        public Step(long id, long planId, long userId, long conversationId,
                    String stepKey, int ordinal, String stepType, String toolName,
                    String toolVersion, String riskLevel, String dependsOnJson,
                    String successCriteriaJson, String inputRefsJson, String outputRef,
                    Long taskDraftId, String taskType, Long pricingVersionId,
                    int quotedPoints, String status, OffsetDateTime createdAt) {
            this(id, planId, userId, conversationId, stepKey, ordinal, stepType, toolName,
                    toolVersion, riskLevel, dependsOnJson, successCriteriaJson, inputRefsJson,
                    outputRef, taskDraftId, taskType, pricingVersionId, quotedPoints, status,
                    createdAt, null, null, null);
        }
    }

    public record Confirmation(long id, long planId, long userId, long conversationId,
                               int planVersion, String tokenHash, int authorizedMaxPoints,
                               String status, OffsetDateTime expiresAt, UUID confirmationIdempotencyKey,
                               String requestHash,
                               OffsetDateTime consumedAt, OffsetDateTime invalidatedAt,
                               OffsetDateTime createdAt) { }

    public record Event(long id, long planId, long userId, long conversationId,
                        long sequenceNo, String eventType, String fromStatus,
                        String toStatus, String stableErrorCode,
                        OffsetDateTime createdAt) { }
}
