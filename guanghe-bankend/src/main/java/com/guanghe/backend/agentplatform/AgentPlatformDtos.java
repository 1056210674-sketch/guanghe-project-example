package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class AgentPlatformDtos {
    private AgentPlatformDtos() { }

    public record AddInstallationRequest(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{0,63}$") String agentKey) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown agent installation field");
        }
    }

    public record UpdateInstallationRequest(@NotNull @Min(1) Integer version, @NotNull Boolean enabled) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown agent installation field");
        }
    }

    public record CreateProjectRequest(@NotBlank @Size(max = 100) String name) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown project field");
        }
    }

    public record UpdateProjectRequest(@NotNull @Min(1) Integer version,
            @Size(min = 1, max = 100) String name, @Pattern(regexp = "^(ACTIVE|ARCHIVED)$") String status) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown project field");
        }
    }

    public record AgentDefinitionView(String agentKey, String version, String name, String description,
                                      List<String> capabilities,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) String installedInstallationId,
                                      String status) {
        public AgentDefinitionView { capabilities = List.copyOf(capabilities); }
    }

    public record AgentInstallationView(String id, String agentKey, String agentVersion, String name,
                                         boolean enabled, int version, OffsetDateTime createdAt) { }

    public record ProjectView(String id, String name, String status, int version,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record CreativeStorageView(String spaceId) { }

    /** Server-owned immutable identity; a legacy read projection has no installation or project. */
    public record AgentIdentity(String installationId, String agentKey, String agentVersion, String name,
                                String projectId, List<String> capabilities,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String executionRelease,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String catalogHash) {
        public AgentIdentity { capabilities = List.copyOf(capabilities); }
        public AgentIdentity(String installationId, String agentKey, String agentVersion, String name,
                String projectId, List<String> capabilities) {
            this(installationId, agentKey, agentVersion, name, projectId, capabilities, null, null);
        }
    }
}
