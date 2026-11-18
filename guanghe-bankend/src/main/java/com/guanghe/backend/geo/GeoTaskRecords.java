package com.guanghe.backend.geo;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.AutomapConstructor;

public final class GeoTaskRecords {
    private GeoTaskRecords() { }

    public record Run(long id, long taskId, long userId, long spaceId, Long productId,
                      long topicId, String status, String sampleMode, int expectedUnits,
                      int succeededUnits, int failedUnits, int missingUnits,
                      String contextSnapshotJson, String metricVersion, String methodVersion,
                      OffsetDateTime startedAt, OffsetDateTime finishedAt,
                      OffsetDateTime createdAt, OffsetDateTime updatedAt,
                      int quotedPoints, int settledPoints, int releasedPoints) { }

    public record Observation(long id, long runId, long taskId, long userId,
                              UUID sourceEventId, String requestHash, String unitKey,
                              int unitSequence, long questionId, String questionTextSnapshot,
                              String platformCode, int sampleNo, String status,
                              String answerText, String stableErrorCode, String provider,
                              String model, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                              int latencyMs, String usageJson, int providerCallCount,
                              OffsetDateTime createdAt, String collectionMethod) {
        // The legacy overload is source compatibility only; JDBC must use the complete record shape.
        @AutomapConstructor
        public Observation { }

        public Observation(long id, long runId, long taskId, long userId, UUID sourceEventId,
                           String requestHash, String unitKey, int unitSequence, long questionId,
                           String questionTextSnapshot, String platformCode, int sampleNo, String status,
                           String answerText, String stableErrorCode, String provider, String model,
                           OffsetDateTime startedAt, OffsetDateTime finishedAt, int latencyMs,
                           String usageJson, int providerCallCount, OffsetDateTime createdAt) {
            this(id, runId, taskId, userId, sourceEventId, requestHash, unitKey, unitSequence, questionId,
                    questionTextSnapshot, platformCode, sampleNo, status, answerText, stableErrorCode,
                    provider, model, startedAt, finishedAt, latencyMs, usageJson, providerCallCount, createdAt, null);
        }
    }

    public record Citation(long id, long observationId, long runId, long userId,
                           int sequence, String title, String url, String domain,
                           String snippet, OffsetDateTime observedAt,
                           OffsetDateTime createdAt) { }

    public record InterpretationCitation(long observationId, String title, String url, String domain,
            String snippet, OffsetDateTime observedAt, int totalForObservation) { }

    public record Report(long id, long runId, long taskId, long userId, long topicId,
                         String status, String synthesisJson, String synthesisUsageJson,
                         String summary, String methodVersion, int sampleSize,
                         OffsetDateTime generatedAt, OffsetDateTime createdAt,
                         int settledPoints, int releasedPoints, OffsetDateTime archivedAt) {
        public Report(long id, long runId, long taskId, long userId, long topicId,
                      String status, String synthesisJson, String synthesisUsageJson,
                      String summary, String methodVersion, int sampleSize,
                      OffsetDateTime generatedAt, OffsetDateTime createdAt,
                      int settledPoints, int releasedPoints) {
            this(id, runId, taskId, userId, topicId, status, synthesisJson, synthesisUsageJson,
                    summary, methodVersion, sampleSize, generatedAt, createdAt,
                    settledPoints, releasedPoints, null);
        }
    }

    public record CompetitorSnapshot(long id, String name, String type, String aliasesJson) { }
}
