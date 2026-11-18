package com.guanghe.backend.agentplatform;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class MonitorExecutionRecords {
    private MonitorExecutionRecords() { }
    public record Policy(long userId, int version, boolean enabled, int maxPointsPerAction,
            int maxPointsPerDay, LocalDate effectiveShanghaiDate, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    public record Grant(long id,long userId,long conversationId,long installationId,String agentKey,
            String agentVersion,String definitionHash,Long projectId,long topicId,String scopeSnapshotJson,
            int version,String status,OffsetDateTime expiresAt,OffsetDateTime revokedAt,
            OffsetDateTime createdAt,OffsetDateTime updatedAt) { }
    public record Admission(long id,long userId,long conversationId,UUID runId,long requestMessageId,
            long draftId,Long grantId,int grantVersion,String frozenIdentityJson,String intentSnapshotJson,
            String intentHash,String inputHash,long pricingVersionId,int quotedPoints,String status,
            String reasonCode,Long taskId,String executionSource,OffsetDateTime admittedAt,
            OffsetDateTime createdAt,OffsetDateTime updatedAt) { }
}
