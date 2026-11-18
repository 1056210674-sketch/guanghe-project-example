package com.guanghe.backend.admin.diagnostics;

import java.time.OffsetDateTime;

final class TaskDiagnosticsRecords {
    private TaskDiagnosticsRecords() { }

    record Task(long id, String taskType, String status, String stageCode, int quotedPoints,
                int settledPoints, String errorCode, String traceId, OffsetDateTime queuedAt,
                OffsetDateTime startedAt, OffsetDateTime finishedAt,
                OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    record Event(String eventType, String fromStatus, String toStatus, String source,
                 String errorCode, OffsetDateTime occurredAt) { }

    record Execution(int executionNo, String status, String stageCode, String providerCode,
                     String providerModel, String submissionState, Integer latencyMs,
                     String errorCode, OffsetDateTime lastHeartbeatAt, OffsetDateTime startedAt,
                     OffsetDateTime finishedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    record Outbox(int executionAttempt, String status, int attempts, String lastErrorCode,
                  OffsetDateTime availableAt, OffsetDateTime publishedAt,
                  OffsetDateTime createdAt) { }

    record Inbox(String disposition, OffsetDateTime createdAt) { }

    record Reservation(String status, int quotedPoints, int frozenPoints, int settledPoints,
                       int releasedPoints, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    record Ledger(String entryType, int availableDelta, int frozenDelta, OffsetDateTime createdAt) { }

    record Ingestion(String status, int attempt, int maxAttempts, String providerCode,
                     String providerModel, String lastErrorCode, OffsetDateTime availableAt,
                     OffsetDateTime completedAt, OffsetDateTime updatedAt) { }

    record Geo(long id, String status, int expectedUnits, int succeededUnits, int failedUnits,
               int missingUnits, OffsetDateTime startedAt, OffsetDateTime finishedAt,
               OffsetDateTime updatedAt) { }

    record Article(long id, String status, int version, OffsetDateTime updatedAt) { }

    record MediaBatch(String status, int quotedPoints, int settledPoints, int releasedPoints,
                      OffsetDateTime finishedAt, OffsetDateTime updatedAt) { }

    record MediaSubmission(String status, String submissionState, String providerCode,
                           String upstreamModelId, String stableErrorCode,
                           OffsetDateTime updatedAt) { }
}
