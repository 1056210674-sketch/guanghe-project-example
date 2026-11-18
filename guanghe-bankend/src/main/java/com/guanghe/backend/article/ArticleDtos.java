package com.guanghe.backend.article;

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

public final class ArticleDtos {
    private ArticleDtos() { }

    public enum Status { DRAFT, OUTLINING, BODY_QUEUED, BODY_GENERATING, COMPLETED, FAILED, ARCHIVED }
    public enum WorkflowStage {
        TITLE_INPUT, TITLE_GENERATING, TITLE_AWAITING_CONFIRMATION,
        PLAN_READY, PLAN_GENERATING, PLAN_AWAITING_CONFIRMATION,
        BODY_READY_TO_GENERATE, BODY_GENERATING,
        BODY_READY, EDITING, READY_TO_SUBMIT
    }
    public enum TitleSource { SELECTED, EDITED, CUSTOM, AUTO_POLICY }
    public enum SubmissionGoal { BALANCED, INDUSTRY_MATCH, COST_EFFECTIVE, BROAD_REACH }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String spaceId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String productId,
            @Size(max = 300) String title
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article create field");
        }
    }

    public static final class UpdateRequest {
        @NotNull @Min(1) private Integer version;
        private String title;
        private String bodyHtml;
        private Status status;
        private boolean titlePresent;
        private boolean bodyHtmlPresent;
        private boolean statusPresent;
        public Integer getVersion() { return version; }
        public void setVersion(Integer value) { version = value; }
        @Size(max = 300) public String getTitle() { return title; }
        public void setTitle(String value) { titlePresent = true; title = value; }
        @Size(max = 524288) public String getBodyHtml() { return bodyHtml; }
        public void setBodyHtml(String value) { bodyHtmlPresent = true; bodyHtml = value; }
        public Status getStatus() { return status; }
        public void setStatus(Status value) { statusPresent = true; status = value; }
        public boolean isTitlePresent() { return titlePresent; }
        public boolean isBodyHtmlPresent() { return bodyHtmlPresent; }
        public boolean isStatusPresent() { return statusPresent; }
        public boolean hasChanges() { return titlePresent || bodyHtmlPresent || statusPresent; }
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article update field");
        }
    }

    public record OutlineItem(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$") String key,
            @NotNull @Min(1) @Max(6) Integer level,
            @NotBlank @Size(max = 300) String heading,
            @Size(max = 1000) String brief
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article outline item field");
        }
    }

    public record ReplaceOutlineRequest(@NotNull @Min(1) Integer version,
                                        @NotNull @Size(max = 100) List<@Valid OutlineItem> items) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article outline field");
        }
    }

    public record AssetInput(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String assetId,
            @NotBlank @Size(max = 128)
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String positionKey,
            @NotNull @Min(1) @Max(10) Integer sortOrder,
            @Size(max = 500) String altText
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article asset field");
        }
    }

    public record ReplaceAssetsRequest(@NotNull @Min(1) Integer version,
                                       @NotNull @Size(max = 10) List<@Valid AssetInput> items) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article assets field");
        }
    }

    public record CreateSnapshotRequest(@NotNull @Min(1) Integer articleVersion) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article snapshot field");
        }
    }

    public record SaveToAssetsRequest(@NotNull @Min(1) Integer version) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article collection field");
        }
    }

    public record ConfirmTitleRequest(
            @NotNull @Min(1) Integer version,
            @NotNull @Min(1) Integer titleBatchVersion,
            @NotBlank @Size(max = 300) String title,
            @NotNull TitleSource source,
            @Min(1) @Max(5) Integer candidateIndex
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article title confirmation field");
        }
    }

    public record CreativePlanInput(
            @NotBlank @Size(max = 1000) String summary,
            @NotBlank @Size(max = 2000) String coreMessage,
            @NotBlank @Size(max = 1000) String targetAudience,
            @NotNull @Size(min = 2, max = 12) List<@Valid OutlineItem> sections,
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String tone,
            @NotBlank @Pattern(regexp = "^(SHORT|MEDIUM|LONG)$") String targetLength,
            @Size(max = 100) String targetPlatform,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 500) String> riskNotes
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article creative plan field");
        }
    }

    public record SaveCreativePlanRequest(
            @NotNull @Min(1) Integer version,
            @NotNull @Min(1) Integer basePlanVersion,
            @NotNull @Valid CreativePlanInput plan
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article creative plan save field");
        }
    }

    public record ConfirmCreativePlanRequest(
            @NotNull @Min(1) Integer version,
            @NotNull @Min(1) Integer planVersion
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article creative plan confirmation field");
        }
    }

    public record ConfirmBodyRequest(
            @NotNull @Min(1) Integer version,
            @NotNull SubmissionGoal goal,
            @Size(max = 120) String preferredRegion,
            @Min(1) Integer maxTotalPoints,
            @NotNull Boolean requiresLink
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown article body confirmation field");
        }
    }

    public record OutlineResponse(String key, int sequence, int level, String heading, String brief) { }
    public record AssetResponse(String assetId, String positionKey, int sortOrder, String altText) { }
    public record TitleCandidateBatchResponse(int version, List<String> candidates,
                                              OffsetDateTime generatedAt) { }
    public record ConfirmedTitleResponse(String value, TitleSource source, int titleBatchVersion,
                                         Integer candidateIndex, OffsetDateTime confirmedAt) { }
    public record CreativePlanResponse(int version, String summary, String coreMessage,
                                       String targetAudience, List<OutlineResponse> sections,
                                       String tone, String targetLength, String targetPlatform,
                                       List<String> riskNotes, boolean confirmed,
                                       OffsetDateTime updatedAt) { }
    public record Summary(String id, String spaceId, String productId, String title, Status status,
                          int version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          boolean savedToAssets, OffsetDateTime savedToAssetsAt, Integer savedArticleVersion) { }
    public record Response(String id, String spaceId, String productId, String title,
                           String bodyHtml, String bodyText, List<OutlineResponse> outline,
                           List<AssetResponse> assets, Status status, String sourceTaskId,
                           int version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                           WorkflowStage workflowStage,
                           TitleCandidateBatchResponse titleCandidateBatch,
                           ConfirmedTitleResponse confirmedTitle,
                           CreativePlanResponse creativePlan, boolean savedToAssets,
                           OffsetDateTime savedToAssetsAt, Integer savedArticleVersion) {
        public Response(String id, String spaceId, String productId, String title,
                        String bodyHtml, String bodyText, List<OutlineResponse> outline,
                        List<AssetResponse> assets, Status status, String sourceTaskId,
                        int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this(id, spaceId, productId, title, bodyHtml, bodyText, outline, assets, status,
                    sourceTaskId, version, createdAt, updatedAt, WorkflowStage.TITLE_INPUT,
                    null, null, null, false, null, null);
        }
    }
    public record SubmissionSnapshotResponse(String id, String articleId, String spaceId,
                                             String productId, int articleVersion, String title,
                                             String bodyHtml, String bodyText, String bodyHash,
                                             List<OutlineResponse> outline, List<AssetResponse> assets,
                                             OffsetDateTime createdAt) { }
}
