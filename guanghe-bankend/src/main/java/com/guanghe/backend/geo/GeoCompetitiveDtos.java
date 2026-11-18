package com.guanghe.backend.geo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.guanghe.backend.catalog.competitor.CompetitorType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.util.List;

public final class GeoCompetitiveDtos {
    private GeoCompetitiveDtos() { }
    public record Evidence(String reportId, String unitKey, String quote, String signal) { }
    public record Candidate(String id, String topicId, String name, String entityKind, String status,
            int version, String competitorId, String existingCompetitorId, String firstReportId,
            String lastReportId, OffsetDateTime firstSeenAt, OffsetDateTime lastSeenAt,
            List<Evidence> evidence, List<String> learningSuggestions) { }
    public record Entity(String candidateId, String competitorId, String name, String entityKind,
            String role, String recognitionStatus, Integer mentionedUnits, Double mentionRate,
            List<Evidence> evidence) { }
    public record Platform(String platformCode, int expectedUnits, int validUnits, int failedUnits,
            int missingUnits, List<Entity> entities) { }
    public record Insights(String reportId, String topicId, String status, boolean retryAllowed,
            int attempts, int maxAttempts, String reasonCode, String questionScope, String methodVersion,
            boolean evidenceTruncated, List<Platform> platforms, List<Candidate> candidates) { }
    public record ConfirmRequest(@NotNull @Min(1) Integer version, @NotNull @Min(1) Integer topicVersion,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String existingCompetitorId,
            @NotNull CompetitorType type, @NotNull Boolean addToTopic) {
        @JsonAnySetter public void unknown(String key, JsonNode value) { throw new IllegalArgumentException("Unknown confirmation field"); }
    }
    public record IgnoreRequest(@NotNull @Min(1) Integer version) {
        @JsonAnySetter public void unknown(String key, JsonNode value) { throw new IllegalArgumentException("Unknown ignore field"); }
    }
    public record Confirmation(Candidate candidate, int topicVersion, Integer competitorSetVersion,
            boolean existingPlansChanged) { }
}
