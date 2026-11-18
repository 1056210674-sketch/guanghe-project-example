package com.guanghe.backend.admin.diagnostics;

import java.time.OffsetDateTime;
import java.util.UUID;

final class RunDiagnosticsRecords {
    private RunDiagnosticsRecords() { }

    record Run(UUID id, long conversationId, String status, long lastPythonSequence,
               String errorCode, String traceId, Long linkedTaskId,
               Integer workflowStepCount, Integer modelCallCount, Integer contextLoadCount,
               Integer toolCallCount, Integer providerCallCount, Integer estimatedInputTokens,
               Integer providerInputTokens, Integer providerOutputTokens, Integer providerLatencyMs,
               String provider, String model, Integer targetInputTokens, Integer reservedOutputTokens,
               Integer reservedToolTokens, Integer reservedSafetyTokens, Integer availableHistoryTokens,
               Integer estimatedHistoryTokens, Boolean contextTruncated, String contextSource,
               OffsetDateTime stopRequestedAt, OffsetDateTime startedAt, OffsetDateTime completedAt,
               OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    record Event(String eventType, long sequence, String stableErrorCode,
                 OffsetDateTime occurredAt) { }
}
