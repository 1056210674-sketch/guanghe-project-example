package com.guanghe.backend.task;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.guanghe.backend.integration.internal.contract.AgentBundleVersionFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class TaskDtos {
    private TaskDtos() { }

    public record DraftResponse(String id, String previousDraftId, String draftSource,
                                String taskType, int parameterSchemaVersion, String status, String spaceId,
                                String productId, String sourceMode, List<String> referenceAssetIds,
                                JsonNode parameters, JsonNode warnings,
                                String inputHash, String pricingVersionId, int quotedPoints,
                                String confirmationToken, OffsetDateTime expiresAt,
                                OffsetDateTime createdAt) {
        public DraftResponse(String id, String previousDraftId, String draftSource,
                             String taskType, int parameterSchemaVersion, String status,
                             String spaceId, String productId, JsonNode parameters, JsonNode warnings,
                             String inputHash, String pricingVersionId, int quotedPoints,
                             String confirmationToken, OffsetDateTime expiresAt, OffsetDateTime createdAt) {
            this(id, previousDraftId, draftSource, taskType, parameterSchemaVersion, status,
                    spaceId, productId, null, List.of(), parameters, warnings, inputHash,
                    pricingVersionId, quotedPoints, confirmationToken, expiresAt, createdAt);
        }
    }

    public record CreateDraftRequest(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String conversationId,
            @NotBlank @Pattern(regexp = "^(IMAGE|VIDEO|GEO|ARTICLE)$") String taskType,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String spaceId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String productId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String sourceMessageId,
            @NotNull @Min(1) @Max(2) Integer parameterSchemaVersion,
            @NotNull JsonNode parameters
    ) {
        public CreateDraftRequest(String conversationId, String taskType, String spaceId,
                                  String productId, Integer parameterSchemaVersion, JsonNode parameters) {
            this(conversationId, taskType, spaceId, productId, null, parameterSchemaVersion, parameters);
        }

        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown task draft field");
        }
    }

    public record ReviseDraftRequest(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String spaceId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String productId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String sourceMessageId,
            @NotNull @Min(1) @Max(2) Integer parameterSchemaVersion,
            @NotNull JsonNode parameters
    ) {
        public ReviseDraftRequest(String spaceId, String productId,
                                  Integer parameterSchemaVersion, JsonNode parameters) {
            this(spaceId, productId, null, parameterSchemaVersion, parameters);
        }

        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown task draft revision field");
        }
    }

    public record ConfirmRequest(
            @NotBlank @Size(max = 512) String confirmationToken,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String inputHash,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String pricingVersionId
    ) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown task confirmation field");
        }
    }

    public record ConfirmResponse(String taskId, String status, int quotedPoints,
                                  Integer frozenPoints, OffsetDateTime createdAt) { }

    public record TaskProgressResponse(int percent, int completedUnits, int totalUnits) { }

    public record TaskResponse(String id, String taskType, String status, String stageCode,
                               String stageLabel, String spaceId, String productId,
                               int quotedPoints, Integer settledPoints, Integer frozenPoints,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer releasedPoints,
                               TaskProgressResponse progress, JsonNode result, String errorCode,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String articlePreparationId) {
        public TaskResponse(String id,String taskType,String status,String stageCode,String stageLabel,String spaceId,String productId,
                int quotedPoints,Integer settledPoints,Integer frozenPoints,Integer releasedPoints,TaskProgressResponse progress,
                JsonNode result,String errorCode,OffsetDateTime createdAt,OffsetDateTime updatedAt) {
            this(id,taskType,status,stageCode,stageLabel,spaceId,productId,quotedPoints,settledPoints,frozenPoints,releasedPoints,
                    progress,result,errorCode,createdAt,updatedAt,null);
        }
    }

    public record TaskEventResponse(String id, long sequence, String eventType,
                                    String status, String stageCode, String errorCode,
                                    OffsetDateTime occurredAt) { }

    public record PointAccountResponse(String userId, int availablePoints, int frozenPoints,
                                       int version, OffsetDateTime updatedAt) { }

    public record PointLedgerEntryResponse(String id, String entryType, String title,
                                           String businessType, String businessId, int amountPoints,
                                           int availableDelta, int frozenDelta,
                                           int availableAfter, int frozenAfter,
                                           OffsetDateTime createdAt) { }

    public record ClaimRequest(
            @NotBlank @Size(max = 128) String consumerName,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String eventId,
            @NotBlank @Size(max = 128) String workerId,
            @NotNull @Min(1) @Max(32) Integer attempt,
            @NotBlank @Pattern(regexp = AgentBundleVersionFormat.REGEX)
            String supportedBundleVersion
    ) { @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
        throw new IllegalArgumentException("Unknown task claim field"); } }

    public record ClaimResponse(boolean claimed, String disposition, Integer executionNo,
                                String executionToken, OffsetDateTime leaseExpiresAt) { }

    public record ExecutionContextResponse(String taskId, String taskType,
                                           @JsonInclude(JsonInclude.Include.NON_NULL) String provider,
                                           @JsonInclude(JsonInclude.Include.NON_NULL) String model,
                                           int executionNo,
                                           int snapshotSchemaVersion, JsonNode inputSnapshot,
                                           JsonNode authorizedContext,
                                           @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode productCreativeBrief,
                                           List<ControlledReferenceAsset> referenceAssets,
                                           @JsonInclude(JsonInclude.Include.NON_NULL) ControlledReferenceAsset controlledMaskAsset,
                                           boolean resumeOnly,
                                           ProviderContinuation providerContinuation, Pricing pricing,
                                           Budget budget) {
        public ExecutionContextResponse(String taskId, String taskType, String provider, String model,
                                        int executionNo, int snapshotSchemaVersion, JsonNode inputSnapshot,
                                        JsonNode authorizedContext, boolean resumeOnly,
                                        ProviderContinuation providerContinuation, Pricing pricing, Budget budget) {
            this(taskId, taskType, provider, model, executionNo, snapshotSchemaVersion, inputSnapshot,
                    authorizedContext, null, List.of(), null, resumeOnly, providerContinuation, pricing, budget);
        }

        public ExecutionContextResponse(String taskId, String taskType, String provider, String model,
                                        int executionNo, int snapshotSchemaVersion, JsonNode inputSnapshot,
                                        JsonNode authorizedContext, JsonNode productCreativeBrief,
                                        List<ControlledReferenceAsset> referenceAssets, boolean resumeOnly,
                                        ProviderContinuation providerContinuation, Pricing pricing, Budget budget) {
            this(taskId, taskType, provider, model, executionNo, snapshotSchemaVersion, inputSnapshot,
                    authorizedContext, productCreativeBrief, referenceAssets, null, resumeOnly,
                    providerContinuation, pricing, budget);
        }

        public ExecutionContextResponse {
            referenceAssets = referenceAssets == null ? List.of() : List.copyOf(referenceAssets);
        }

        public record ControlledReferenceAsset(String assetId, String contentType, long sizeBytes,
                                               String sha256, int width, int height,
                                               String readUrl, OffsetDateTime expiresAt) { }
        public record ProviderContinuation(boolean resumeOnly, String provider, String model,
                                           String providerRequestId, String providerTaskId,
                                           String submissionState) { }
        public record Pricing(String pricingVersionId, int quotedPoints) { }
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Budget(int maxProviderCalls, Integer maxImages,
                             Integer maxVideos, Integer maxVideoSeconds,
                             Integer maxGeoQuestions, Integer maxGeoPlatforms,
                             Integer samplesPerCombination, Integer maxConcurrency,
                             OffsetDateTime deadlineAt, Integer maxModelCalls,
                             Integer maxRepairRetries, Integer maxInputTokens,
                             Integer maxOutputTokens) {
            public Budget(int maxProviderCalls, int maxImages) {
                this(maxProviderCalls, maxImages, null, null,
                        null, null, null, null, null, null, null, null, null);
            }
            public Budget(int maxProviderCalls, Integer maxImages,
                          Integer maxVideos, Integer maxVideoSeconds) {
                this(maxProviderCalls, maxImages, maxVideos, maxVideoSeconds,
                        null, null, null, null, null, null, null, null, null);
            }
            public Budget(int maxProviderCalls, Integer maxImages,
                          Integer maxVideos, Integer maxVideoSeconds,
                          Integer maxGeoQuestions, Integer maxGeoPlatforms,
                          Integer samplesPerCombination, Integer maxConcurrency,
                          OffsetDateTime deadlineAt) {
                this(maxProviderCalls, maxImages, maxVideos, maxVideoSeconds,
                        maxGeoQuestions, maxGeoPlatforms, samplesPerCombination, maxConcurrency,
                        deadlineAt, null, null, null, null);
            }
        }
    }

    public record HeartbeatRequest(
            @NotNull @Min(1) Integer executionNo,
            @NotBlank @Size(max = 512) String executionToken,
            @NotBlank @Size(max = 64) String stageCode
    ) { @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
        throw new IllegalArgumentException("Unknown task heartbeat field"); } }

    public record HeartbeatResponse(boolean accepted, OffsetDateTime leaseExpiresAt) { }

    public record ExecutionEventRequest(
            @NotNull java.util.UUID eventId,
            @NotNull @Min(1) Integer executionNo,
            @NotBlank @Size(max = 512) String executionToken,
            @NotBlank @Size(max = 64) String eventType,
            @NotBlank @Size(max = 64) String stageCode,
            JsonNode details
    ) { @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
        throw new IllegalArgumentException("Unknown task event field"); } }

    public record ResultArtifactsRequest(
            @NotNull java.util.UUID eventId,
            @NotNull @Min(1) Integer executionNo,
            @NotBlank @Size(max = 512) String executionToken,
            @NotBlank @Size(max = 64) String provider,
            @NotBlank @Size(max = 128) String model,
            @NotNull java.util.UUID requestId,
            @Size(max = 256) String providerTaskId,
            @NotBlank @Pattern(regexp = "^CONFIRMED$") String submissionState,
            @NotNull @Size(min = 1, max = 4) List<JsonNode> artifacts,
            @NotNull JsonNode usage,
            @NotNull @Min(0) Integer latencyMs
    ) { @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
        throw new IllegalArgumentException("Unknown task result field"); } }

    public record InternalAccepted(boolean accepted, String taskStatus) { }
}
