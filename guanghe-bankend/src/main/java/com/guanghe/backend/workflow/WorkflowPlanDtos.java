package com.guanghe.backend.workflow;

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
import java.util.UUID;

public final class WorkflowPlanDtos {
    private WorkflowPlanDtos() { }

    public record ProposalRequest(
            @NotNull UUID proposalId,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String conversationId,
            @NotBlank @Size(max = 500) String goalSummary,
            @NotNull @Valid BudgetRequest budget,
            @NotNull @Size(min = 2, max = 12) List<@Valid StepProposal> steps
    ) {
        @JsonAnySetter public void rejectUnknownField(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow plan proposal field");
        }
    }

    public record BudgetRequest(
            @NotNull @Min(2) @Max(12) Integer maxSteps,
            @NotNull @Min(0) @Max(2) Integer maxReplans,
            @NotNull @Min(1) @Max(32) Integer maxModelCalls,
            @NotNull @Min(2) @Max(32) Integer maxToolCalls,
            @NotNull @Min(0) @Max(12) Integer maxProviderCalls,
            @NotNull @Min(1) @Max(1_000_000) Integer maxPointCost,
            @NotNull OffsetDateTime deadlineAt
    ) {
        @JsonAnySetter public void rejectUnknownField(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow budget field");
        }
    }

    public record StepProposal(
            @NotBlank @Pattern(regexp = "^step-[1-9][0-9]?$") String stepId,
            @NotNull @Min(1) @Max(12) Integer ordinal,
            @NotBlank @Pattern(regexp = "^(QUERY|ANALYZE|PREPARE|SYNTHESIZE)$") String type,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_.-]{0,127}$") String toolName,
            @NotBlank @Pattern(regexp = "^1$") String toolVersion,
            @NotBlank @Pattern(regexp = "^(QUERY|ANALYZE|PREPARE)$") String risk,
            @NotNull @Size(max = 11) List<@Pattern(regexp = "^step-[1-9][0-9]?$") String> dependsOn,
            @NotNull @Size(min = 1, max = 5) List<@NotBlank @Size(max = 200) String> successCriteria,
            @NotNull @Size(max = 8) List<@Pattern(regexp = "^(context|observation|task|asset|geo|article):[A-Za-z0-9._-]{1,100}$") String> inputRefs,
            @Pattern(regexp = "^(observation|draft):[A-Za-z0-9._-]{1,100}$") String outputRef,
            @Valid TaskDraftProposal taskDraft
    ) {
        @JsonAnySetter public void rejectUnknownField(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow step field");
        }
    }

    public record TaskDraftProposal(
            @NotBlank @Pattern(regexp = "^(IMAGE|VIDEO|GEO|ARTICLE)$") String taskType,
            @NotNull @Min(1) @Max(2) Integer parameterSchemaVersion,
            @NotNull JsonNode parameters
    ) {
        @JsonAnySetter public void rejectUnknownField(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow task draft field");
        }
    }

    public record AbandonRequest(@NotNull @Min(1) Integer version) {
        @JsonAnySetter public void rejectUnknownField(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow abandon field");
        }
    }

    public record ConfirmationPreviewRequest(@NotNull @Min(1) Integer planVersion) {
        @JsonAnySetter public void rejectUnknownField(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow confirmation preview field");
        }
    }

    public record ConfirmRequest(
            @NotNull @Min(1) Integer planVersion,
            @NotNull @Min(1) @Max(1_000_000) Integer authorizedMaxPoints,
            @NotBlank @Size(max = 512) String confirmationToken
    ) {
        @JsonAnySetter public void rejectUnknownField(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow confirmation field");
        }
    }

    public record Budget(int maxSteps, int maxReplans, int maxModelCalls, int maxToolCalls,
                         int maxProviderCalls, int maxPointCost, OffsetDateTime deadlineAt) { }

    public record DraftReference(String draftId, String taskType, String pricingVersionId,
                                 int quotedPoints) { }

    public record Step(String stepId, int ordinal, String type, String toolName,
                       String toolVersion, String risk, List<String> dependsOn,
                       List<String> successCriteria, List<String> inputRefs, String outputRef,
                       DraftReference taskDraft, String status, String taskId,
                       String taskStatus, String taskResultRef) {
        public Step(String stepId, int ordinal, String type, String toolName,
                    String toolVersion, String risk, List<String> dependsOn,
                    List<String> successCriteria, List<String> inputRefs, String outputRef,
                    DraftReference taskDraft, String status) {
            this(stepId, ordinal, type, toolName, toolVersion, risk, dependsOn,
                    successCriteria, inputRefs, outputRef, taskDraft, status, null, null, null);
        }
    }

    public record ConfirmationPreview(int planVersion, int quotedPoints, int maxPointCost,
                                      OffsetDateTime expiresAt, boolean consumptionEnabled) { }

    public record ConfirmationAuthorization(String planId, int planVersion, int totalQuotedPoints,
                                            int maxPointCost, OffsetDateTime expiresAt,
                                            boolean executionEnabled, String confirmationToken) { }

    public record ConfirmResponse(String planId, int planVersion, String status,
                                  int totalQuotedPoints, int authorizedMaxPoints,
                                  OffsetDateTime confirmedAt, boolean executionEnabled,
                                  boolean executionStarted) { }

    public record Event(long sequence, String eventType, String fromStatus, String toStatus,
                        String stableErrorCode, OffsetDateTime occurredAt) { }

    public record Summary(String id, String conversationId, String goalSummary, String status,
                          int version, int stepCount, int totalQuotedPoints, int maxPointCost,
                          OffsetDateTime confirmationExpiresAt, OffsetDateTime createdAt,
                          OffsetDateTime updatedAt) { }

    public record Detail(String id, String conversationId, String spaceId, String productId,
                         String goalSummary, String status, int version, Budget budget,
                         int totalQuotedPoints, List<Step> steps,
                         ConfirmationPreview confirmation, List<Event> events,
                         OffsetDateTime abandonedAt, OffsetDateTime createdAt,
                         OffsetDateTime updatedAt, boolean executionEnabled,
                         boolean executionStarted, String lastStableError) {
        public Detail(String id, String conversationId, String spaceId, String productId,
                      String goalSummary, String status, int version, Budget budget,
                      int totalQuotedPoints, List<Step> steps,
                      ConfirmationPreview confirmation, List<Event> events,
                      OffsetDateTime abandonedAt, OffsetDateTime createdAt,
                      OffsetDateTime updatedAt) {
            this(id, conversationId, spaceId, productId, goalSummary, status, version, budget,
                    totalQuotedPoints, steps, confirmation, events, abandonedAt, createdAt,
                    updatedAt, false, false, null);
        }
    }
}
