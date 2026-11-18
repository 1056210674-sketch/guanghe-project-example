package com.guanghe.backend.admin.agentversion;

import java.time.OffsetDateTime;

final class AgentVersionRecords {
    private AgentVersionRecords() { }

    record VersionAggregate(String bundleVersion, OffsetDateTime firstObservedAt,
                            OffsetDateTime lastObservedAt, long runCount) { }

    record AssociatedRun(long executionId, String source, long parentId, String taskType,
                         String status, String stageCode, String providerCode,
                         String providerModel, String stableErrorCode,
                         OffsetDateTime startedAt, OffsetDateTime finishedAt) { }
}
