package com.guanghe.backend.media;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class MediaDtos {
    private MediaDtos() { }

    public record ResourceSummary(String id, String providerCode, String upstreamModelId,
                                  String upstreamResourceId, String name, String status,
                                  String resourceType, String channelCategory, String region,
                                  Integer estimatedDays, JsonNode capabilities,
                                  int saleAmountCents, int chargedPoints,
                                  OffsetDateTime quoteExpiresAt, OffsetDateTime syncedAt) { }

    public record Recommendation(int rank, String mediaResourceId, String reasonCode,
                                 String reason, int score, List<String> evidence,
                                 ResourceSummary resource) { }

    public record RecommendationStrategy(String goal, String preferredRegion,
                                         Integer maxTotalPoints, boolean requiresLink,
                                         List<String> detectedCategories, int candidateCount,
                                         List<String> dataLimitations) { }

    public record RecommendationSet(String articleId, int articleVersion,
                                    OffsetDateTime generatedAt, boolean submissionEnabled,
                                    RecommendationStrategy strategy,
                                    List<Recommendation> items) { }

    public record QuoteRequest(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String articleId,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String articleSnapshotId,
            @NotEmpty @Size(max = 5) List<@Valid QuoteItem> items) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown media quote field");
        }
    }

    public record QuoteItem(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String mediaResourceId,
            @Size(max = 1500) String remark) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown media quote item field");
        }
    }

    public record SubmissionSummary(String id, String batchId, String taskId, String articleId,
                                    String articleSnapshotId, String mediaResourceId, String mediaName,
                                    String status, int saleAmountCents, int chargedPoints,
                                    String publishedUrl, List<String> publishedUrls, String stableErrorCode,
                                    OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record SubmissionDetail(String id, String batchId, String taskId, String articleId,
                                   String articleSnapshotId, String mediaResourceId, String mediaName,
                                   String status, int saleAmountCents, int chargedPoints,
                                   String remark, String publishedUrl, List<String> publishedUrls,
                                   String stableErrorCode,
                                   List<SubmissionEvent> events, OffsetDateTime createdAt,
                                   OffsetDateTime updatedAt) { }

    public record SubmissionEvent(String id, long sequence, String eventType, String fromStatus,
                                  String toStatus, String stableErrorCode, OffsetDateTime occurredAt) { }

    record PreparedQuote(long articleId, long articleSnapshotId, long spaceId, Long productId,
                         JsonNode parameters, JsonNode inputSnapshot, List<PreparedItem> items,
                         int quotedPoints, long pricingVersionId, OffsetDateTime expiresAt) { }
    record PreparedItem(MediaRecords.Resource resource, MediaRecords.Price price, String remark,
                        MediaImagePricing.Rule imageRule, int imageCount) { }
}
