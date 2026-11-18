package com.guanghe.backend.adminplatform;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Deliberately metadata-only. Do not extend these views with user content, source URLs or credentials. */
public final class AdminPlatformDtos {
    private AdminPlatformDtos() { }

    public record AgentDefinitionView(String agentKey, String version, String name, String adapterKey,
            String visibility, String status, String definitionHash, OffsetDateTime publishedAt, long installationCount) { }

    public record UpdateDefinitionRequest(
            @NotBlank @Pattern(regexp = "^(PUBLISHED|DISABLED|WITHDRAWN)$") String expectedStatus,
            @NotBlank @Pattern(regexp = "^(PUBLIC|INTERNAL)$") String expectedVisibility,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String definitionHash,
            @Pattern(regexp = "^(PUBLISHED|DISABLED|WITHDRAWN)$") String status,
            @Pattern(regexp = "^(PUBLIC|INTERNAL)$") String visibility) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown agent governance field");
        }
    }

    public record PlanView(String id, String userId, String projectId, String topicId, String status,
            String scheduleType, int estimatedPointsPerRun, int maxPointsPerRun, String pricingVersionId,
            OffsetDateTime nextRunAt, OffsetDateTime lastScheduledSlot, String pauseReason, int version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record PlanSlotView(String id, String planId, String userId, OffsetDateTime scheduledSlot,
            String status, String taskId, Integer quotedPoints, String errorCode, OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    public record PausePlanRequest(@NotNull @Min(1) Integer version, @NotBlank @Size(max = 200) String reason) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown plan governance field");
        }
    }
    public record ResumePlanRequest(@NotNull @Min(1) Integer version) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown plan governance field");
        }
    }

    public record KnowledgeDocumentMetadata(String id, String userId, String projectId, String sourceType,
            String status, int version, String sha256, String contentType, Long sizeBytes, int paragraphCount,
            Integer pageCount, String failureCode, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime deletedAt) { }

    public record MemoryPreferenceMetadata(String id, String userId, String projectId, String scope,
            String sourceType, String status, int version, String contentHash, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, OffsetDateTime deletedAt) { }

    public record MetadataSummary(long knowledgeDocumentCount, long readyKnowledgeDocumentCount,
            long failedKnowledgeDocumentCount, long deletedKnowledgeDocumentCount,
            long activePersonalPreferenceCount, long activeProjectPreferenceCount, long deletedPreferenceCount) { }
}
