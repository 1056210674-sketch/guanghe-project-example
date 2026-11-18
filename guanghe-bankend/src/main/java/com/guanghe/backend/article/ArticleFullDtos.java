package com.guanghe.backend.article;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** One explicit full-article action; no browser-supplied identity, stage, pricing or provider. */
public final class ArticleFullDtos {
    private ArticleFullDtos() { }
    public record AuthorizationCreate(
            @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String spaceId,
            @Pattern(regexp="^[1-9][0-9]{0,18}$") String productId,
            @Min(1) @Max(100000) int maxPoints,
            @NotNull OffsetDateTime expiresAt) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) { throw new IllegalArgumentException("Unknown article authorization field"); }
    }
    public record AuthorizationView(String id, String action, int version, String status,
            String spaceId, String productId, int maxPoints, OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }
    public record StartRequest(
            @NotNull UUID sourceRunId,
            @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String grantId,
            @Min(1) int grantVersion,
            @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String spaceId,
            @Pattern(regexp="^[1-9][0-9]{0,18}$") String productId,
            @NotBlank @Size(max=4000) String requestText,
            @Pattern(regexp="^[1-9][0-9]{0,18}$") String geoReportId,
            @Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String tone,
            @Min(100) @Max(20000) Integer targetLength) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) { throw new IllegalArgumentException("Unknown article full field"); }
    }
    public record StageView(int ordinal, String mode, String status, String taskId,
            int quotedPoints, String errorCode) { }
    public record PreviewView(String preparationId,String articleId,int articleVersion,String taskId,
            Integer executionNo,int attempt,int sequence,String status,String title,
            String contentMarkdown,OffsetDateTime updatedAt) { }
    public record PreparationView(String id, String templateCode, String status,
            String conversationId, String articleId, Integer articleVersion,
            int quotedPoints, int settledPoints, int releasedPoints,
            OffsetDateTime deadlineAt, String errorCode, List<StageView> stages,
            boolean publicationRequiresConfirmation, boolean knowledgeSourcesCurrent) {
        public PreparationView { stages=List.copyOf(stages); }
    }
}
