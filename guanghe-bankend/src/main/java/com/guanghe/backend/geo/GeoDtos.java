package com.guanghe.backend.geo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class GeoDtos {
    private GeoDtos() { }

    public enum KeywordType { CORE, DISTILLED, BRAND, SCENE }
    public enum QuestionCategory { BRAND_DISCOVERY, BRAND_REPUTATION }
    public enum QuestionSource { MANUAL }

    public record CreateTopicRequest(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String spaceId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String productId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String region
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO topic request field");
        }
    }

    public static final class UpdateTopicRequest {
        @NotNull @Min(1) @Max(Integer.MAX_VALUE)
        private Integer version;
        private String productId;
        private String name;
        private String region;
        private boolean productIdPresent;
        private boolean namePresent;
        private boolean regionPresent;

        public Integer getVersion() { return version; }
        public void setVersion(Integer version) { this.version = version; }
        @Pattern(regexp = "^[1-9][0-9]{0,18}$")
        public String getProductId() { return productId; }
        public void setProductId(String productId) { productIdPresent = true; this.productId = productId; }
        @Size(max = 100)
        public String getName() { return name; }
        public void setName(String name) { namePresent = true; this.name = name; }
        @Size(max = 100)
        public String getRegion() { return region; }
        public void setRegion(String region) { regionPresent = true; this.region = region; }
        public boolean isProductIdPresent() { return productIdPresent; }
        public boolean isNamePresent() { return namePresent; }
        public boolean isRegionPresent() { return regionPresent; }
        public boolean hasChanges() { return productIdPresent || namePresent || regionPresent; }
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO topic request field");
        }
    }

    public record KeywordInput(
            @NotNull KeywordType type,
            @NotBlank @Size(max = 200) String text
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO keyword field");
        }
    }

    public record ReplaceKeywordsRequest(
            @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer version,
            @NotNull List<@Valid KeywordInput> keywords
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO keyword request field");
        }
    }

    public record QuestionInput(
            @NotNull QuestionCategory category,
            @NotBlank @Size(max = 1000) String text,
            @NotNull QuestionSource source,
            @NotNull Boolean enabled
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO question field");
        }
    }

    public record ReplaceQuestionsRequest(
            @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer version,
            @NotNull List<@Valid QuestionInput> questions
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO question request field");
        }
    }

    public record ReplaceCompetitorsRequest(
            @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer version,
            @NotNull @Size(max = 20)
            List<@NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String> competitorIds
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO competitor-set request field");
        }
    }

    public record KeywordResponse(String id, int sequence, KeywordType type, String text) { }

    public record QuestionResponse(
            String id,
            int sequence,
            QuestionCategory category,
            String text,
            QuestionSource source,
            boolean enabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) { }

    public record TopicSummary(
            String id,
            String spaceId,
            String productId,
            String name,
            String region,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) { }

    public record TopicResponse(
            String id,
            String spaceId,
            String productId,
            String name,
            String region,
            List<KeywordResponse> keywords,
            List<QuestionResponse> questions,
            List<String> competitorIds,
            Integer competitorSetVersion,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public TopicResponse {
            keywords = List.copyOf(keywords);
            questions = List.copyOf(questions);
            competitorIds = List.copyOf(competitorIds);
        }
    }
}
