package com.guanghe.backend.admin.diagnostics;

import java.time.OffsetDateTime;
import java.util.List;

public final class RunDiagnosticsDtos {
    private RunDiagnosticsDtos() { }

    public record Summary(String runId, String conversationId, String status,
                          String stableErrorCode, TaskDiagnosticsDtos.Decision decision,
                          String traceId, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          OffsetDateTime completedAt) { }

    public record Usage(Integer workflowStepCount, Integer modelCallCount,
                        Integer contextLoadCount, Integer toolCallCount,
                        Integer providerCallCount, Integer estimatedInputTokens,
                        Integer providerInputTokens, Integer providerOutputTokens,
                        Integer providerLatencyMs, String provider, String model) { }

    public record ContextBudget(Integer targetInputTokens, Integer reservedOutputTokens,
                                Integer reservedToolTokens, Integer reservedSafetyTokens,
                                Integer availableHistoryTokens, Integer estimatedHistoryTokens,
                                Boolean truncated, String contextSource, Boolean cacheHit) { }

    public record TimelineEntry(String eventType, long sequence,
                                String stableErrorCode, OffsetDateTime occurredAt) { }

    public record Detail(String runId, String conversationId, String status,
                         long lastPythonSequence, String stableErrorCode,
                         TaskDiagnosticsDtos.Decision decision, String traceId,
                         OffsetDateTime stopRequestedAt, OffsetDateTime startedAt,
                         OffsetDateTime completedAt, OffsetDateTime createdAt,
                         OffsetDateTime updatedAt, Usage usage, ContextBudget contextBudget,
                         String linkedTaskId, List<TimelineEntry> timeline,
                         List<String> dataGaps) { }
}
