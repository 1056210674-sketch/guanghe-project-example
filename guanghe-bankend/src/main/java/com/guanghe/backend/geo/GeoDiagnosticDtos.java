package com.guanghe.backend.geo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Browser-owned preparation; none of these requests creates or funds a monitoring task. */
public final class GeoDiagnosticDtos {
    private GeoDiagnosticDtos() { }
    public enum Phase { BRAND_RESEARCH, COMPETITORS, DISCOVERY_QUESTIONS, REPUTATION_QUESTIONS }
    public record Create(@NotNull UUID requestId, @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String conversationId) {
        @JsonAnySetter public void unknown(String field, JsonNode value) { throw new IllegalArgumentException("Unknown diagnostic field"); }
    }
    public record Profile(@NotNull @Size(max=200) String brandName, @Size(max=2048) String website,
                          @NotNull @Size(max=6000) String introduction,
                          @NotNull @Size(max=50) List<@NotBlank @Size(max=200) String> brandTerms,
                          @NotNull @Size(max=100) String region) { }
    public record Competitor(@NotBlank @Size(max=128) String id, @NotBlank @Size(max=200) String name,
                             @NotNull @Size(max=50) List<@NotBlank @Size(max=200) String> brandTerms,
                             @NotNull @Size(max=1000) String reason,
                             @NotNull @Size(max=8) List<@NotBlank @Size(max=128) String> sourceIds,
                             @NotBlank @Pattern(regexp="AI|USER") String origin, @NotNull Boolean edited) { }
    public record Question(@NotBlank @Size(max=128) String id, @NotBlank @Size(max=1000) String text,
                           @NotBlank @Pattern(regexp="AI|USER") String origin, @NotNull Boolean edited) { }
    public record Source(String id, String title, String url, String snippet) { }
    public record Snapshot(int version, Profile profile, List<Competitor> competitors,
                           List<Question> discoveryQuestions, List<Question> reputationQuestions,
                           List<String> platformCodes, List<Source> sources, List<String> warnings) { }
    public record Card(int step, String messageId, Snapshot snapshot) { }
    public record Generation(Phase phase, String status, String errorCode) { }
    public record Quote(String id, String inputHash, int version, int expectedSamples, int points,
                        OffsetDateTime expiresAt,String blockedReason,Integer availablePoints,Integer maxPointsPerAction,Integer maxPointsPerDay) {
        public Quote(String id,String inputHash,int version,int expectedSamples,int points,OffsetDateTime expiresAt) {
            this(id,inputHash,version,expectedSamples,points,expiresAt,null,null,null,null);
        }
    }
    public record Detail(String id, int version, String projectId, String conversationId, int step,
                         String brandName, Profile profile, List<Competitor> competitors,
                         List<Question> discoveryQuestions, List<Question> reputationQuestions,
                         List<String> platformCodes, List<Source> sources, List<String> warnings, Generation generation,
                         List<Card> cards, Quote quote, boolean submissionEnabled,GeoDiagnosticReportDtos.Delivery delivery) { }
    public record Patch(@NotNull @Min(1) Integer version, @NotNull @Min(1) @Max(6) Integer step,
                        @Valid Profile profile, @Size(max=5) List<@Valid Competitor> competitors,
                        @Size(max=10) List<@Valid Question> discoveryQuestions,
                        @Size(max=10) List<@Valid Question> reputationQuestions,
                        @Size(max=7) List<@NotBlank String> platformCodes, @NotNull Boolean advance) {
        @JsonAnySetter public void unknown(String field, JsonNode value) { throw new IllegalArgumentException("Unknown diagnostic patch field"); }
    }
    public record Generate(@NotNull @Min(1) Integer version, @NotNull Phase phase, @NotNull UUID requestId) {
        @JsonAnySetter public void unknown(String field, JsonNode value) { throw new IllegalArgumentException("Unknown diagnostic generation field"); }
    }
    public record QuoteRequest(@NotNull @Min(1) Integer version,
                               @NotNull @Size(min=1,max=7) List<@NotBlank String> platformCodes) {
        @JsonAnySetter public void unknown(String field, JsonNode value) { throw new IllegalArgumentException("Unknown diagnostic quote field"); }
    }
}
