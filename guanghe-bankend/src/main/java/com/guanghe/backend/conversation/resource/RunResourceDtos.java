package com.guanghe.backend.conversation.resource;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** Server-issued references, not arbitrary catalog IDs or model-selected SQL projections. */
public final class RunResourceDtos {
    private RunResourceDtos() { }

    public record SearchRequest(
            @NotBlank @Size(max = 128) String toolCallId,
            @NotBlank @Pattern(regexp = "SPACE|PRODUCT|GEO_TOPIC") String kind,
            @NotBlank @Size(max = 200) String keyword,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String scopeRef
    ) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown resource search field");
        }
    }

    public record Candidate(String choiceId, String kind, String resourceId, String name,
            @JsonInclude(JsonInclude.Include.NON_NULL) String spaceId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String spaceName,
            boolean hasReport, boolean exact) { }

    public record SearchResponse(String candidateSetId, String kind, String status, long totalMatches,
            boolean complete, List<Candidate> candidates, OffsetDateTime expiresAt) {
        public SearchResponse { candidates = List.copyOf(candidates); }
    }

    public record ReadRequest(
            @NotBlank @Size(max = 128) String toolCallId,
            @NotBlank @Pattern(regexp = "CATALOG_SUMMARY|LATEST_GEO_REPORT|GEO_ANSWER_EVIDENCE|GEO_DIAGNOSTIC_REPORT|ASSET_INVENTORY") String purpose,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String candidateSetId,
            @Size(min = 1, max = 128) String choiceId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String observationId,
            @Pattern(regexp = "LATEST_ATTEMPT|LATEST_USABLE") String reportPolicy,
            @Size(max = 3) List<@NotBlank @Size(max = 200) String> sampleRefs
    ) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown resource read field");
        }
    }

    public record ReportAttempt(String runId, String status, OffsetDateTime createdAt,
            boolean newerFailedAttempt) { }

    public record ReadResponse(String observationId, String kind, String purpose, String resourceId,
            String name, String spaceId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String productId,
            OffsetDateTime observedAt, String sourceHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) String reportId,
            @JsonInclude(JsonInclude.Include.NON_NULL) ReportAttempt reportAttempt,
            JsonNode projection, boolean truncated) { }

    public record Selection(String kind, @JsonSerialize(using = ToStringSerializer.class) long resourceId,
            String name, @JsonSerialize(using = ToStringSerializer.class) long spaceId,
            @JsonSerialize(using = ToStringSerializer.class) Long productId,
            String candidateSetId, String choiceId) { }
}
