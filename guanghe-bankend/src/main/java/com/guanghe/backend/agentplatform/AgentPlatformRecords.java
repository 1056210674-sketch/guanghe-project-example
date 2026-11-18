package com.guanghe.backend.agentplatform;

import java.time.OffsetDateTime;

public final class AgentPlatformRecords {
    private AgentPlatformRecords() { }

    public record Definition(String agentKey, String version, String name, String description,
                             String capabilitiesJson, String adapterKey, String visibility, String status) { }

    public record Installation(long id, long userId, String agentKey, String agentVersion,
                               boolean enabled, int version, OffsetDateTime createdAt,
                               OffsetDateTime updatedAt, OffsetDateTime deletedAt) { }

    public record Project(long id, long userId, String name, String nameNormalized, String status,
                          int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record Binding(long conversationId, long userId, long installationId,
                          String agentKey, String agentVersion, String name, Long projectId,
                          String capabilitiesJson, OffsetDateTime createdAt) { }
}
