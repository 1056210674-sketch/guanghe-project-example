package com.guanghe.backend.admin.diagnostics;

import java.time.OffsetDateTime;
import java.util.List;

public final class TaskDiagnosticsDtos {
    private TaskDiagnosticsDtos() { }

    public enum FailureCategory {
        MODEL, CONTEXT, TOOL_POLICY, WORKER, MESSAGING, PROVIDER,
        ASSET_INGESTION, BILLING, MEDIA_SUBMISSION, UNKNOWN
    }

    public enum Retryability {
        NEVER, SAFE_RETRY, IDEMPOTENT_RETRY, RECOVERY_ONLY,
        MANUAL_RECONCILIATION, UNKNOWN
    }

    public record Decision(FailureCategory failureCategory, String stableErrorCode,
                           Retryability retryability, boolean retryable,
                           boolean waitingExternalRecovery) { }

    public record Summary(String taskId, String taskType, String taskStatus, String stage,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          OffsetDateTime finishedAt, Decision decision, String traceId) { }

    public record TimelineEntry(String eventType, String fromStatus, String toStatus,
                                String source, String stableErrorCode,
                                OffsetDateTime occurredAt) { }

    public record ExecutionSummary(int executionNo, String status, String stage,
                                   String provider, String model, String submissionState,
                                   Integer latencyMs, String stableErrorCode,
                                   OffsetDateTime lastHeartbeatAt, OffsetDateTime startedAt,
                                   OffsetDateTime finishedAt) { }

    public record OutboxSummary(int executionAttempt, String status, int attempts,
                                String stableErrorCode, OffsetDateTime availableAt,
                                OffsetDateTime publishedAt, OffsetDateTime createdAt) { }

    public record InboxSummary(String disposition, OffsetDateTime createdAt) { }

    public record LedgerEntry(String entryType, int availableDelta, int frozenDelta,
                              OffsetDateTime createdAt) { }

    public record BillingSummary(String reservationStatus, int quotedPoints, int frozenPoints,
                                 int settledPoints, int releasedPoints,
                                 List<LedgerEntry> ledger) { }

    public record AssetIngestionSummary(String status, int attempt, int maxAttempts,
                                        String provider, String model, String stableErrorCode,
                                        OffsetDateTime availableAt, OffsetDateTime completedAt,
                                        OffsetDateTime updatedAt) { }

    public record GeoSummary(String runId, String status, int expectedUnits,
                             int succeededUnits, int failedUnits, int missingUnits,
                             OffsetDateTime startedAt, OffsetDateTime finishedAt,
                             OffsetDateTime updatedAt) { }

    public record ArticleSummary(String articleId, String status, int version,
                                 OffsetDateTime updatedAt) { }

    public record MediaSubmissionSummary(String status, String submissionState,
                                         String provider, String model,
                                         String stableErrorCode, OffsetDateTime updatedAt) { }

    public record MediaSummary(String status, int quotedPoints, int settledPoints,
                               int releasedPoints, OffsetDateTime finishedAt,
                               OffsetDateTime updatedAt,
                               List<MediaSubmissionSummary> submissions) { }

    public record Detail(String taskId, String taskType, String taskStatus, String stage,
                         int quotedPoints, int settledPoints, OffsetDateTime queuedAt,
                         OffsetDateTime startedAt, OffsetDateTime finishedAt,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt,
                         Decision decision, String traceId, List<TimelineEntry> timeline,
                         List<ExecutionSummary> executions, List<OutboxSummary> outbox,
                         List<InboxSummary> inbox, BillingSummary billing,
                         List<AssetIngestionSummary> assetIngestion, GeoSummary geo,
                         ArticleSummary article, MediaSummary media,
                         List<String> dataGaps) { }
}
