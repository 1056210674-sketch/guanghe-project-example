package com.guanghe.backend.workflow;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.guanghe.backend.integration.internal.contract.AgentBundleVersionFormat;
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
import com.guanghe.backend.conversation.context.InternalRunContextDtos;

public final class WorkflowExecutionDtos {
    private WorkflowExecutionDtos() { }

    public record StartRequest(@NotNull @Min(1) Integer planVersion) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow start field");
        }
    }

    public record StartResponse(String planId, int planVersion, String status,
                                boolean executionEnabled, boolean executionStarted,
                                Integer executionNo) { }

    public record ClaimRequest(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String eventId,
            @NotBlank @Size(max = 128) String consumerName,
            @NotBlank @Size(max = 128) String workerId,
            @NotBlank @Pattern(regexp = AgentBundleVersionFormat.REGEX)
            String supportedBundleVersion,
            @NotNull @Min(1) @Max(32) Integer executionNo,
            @NotNull @Min(1) Integer planVersion) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow claim field");
        }
    }

    public record ClaimResponse(boolean claimed, String disposition, Integer executionNo,
                                String executionToken, OffsetDateTime leaseExpiresAt) { }

    public record ExecutionContext(String planId, int planVersion, int executionNo,
                                   JsonNode plan, List<JsonNode> steps,
                                   List<Observation> observations, Budget budget,
                                   boolean resumeOnly,
                                   InternalRunContextDtos.RunContext authorizedContext,
                                   int authorizedContextSchemaVersion,
                                   String authorizedContextHash,
                                   String authorizedContextSource) {
        public record Budget(int maxSteps, int maxReplans, int maxModelCalls,
                             int maxToolCalls, int maxProviderCalls,
                             int authorizedMaxPoints, OffsetDateTime deadlineAt) { }
    }

    public record HeartbeatRequest(@NotNull @Min(1) Integer executionNo,
                                   @NotBlank @Size(max = 512) String executionToken,
                                   @NotBlank @Size(max = 64) String stageCode) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow heartbeat field");
        }
    }
    public record HeartbeatResponse(boolean accepted, OffsetDateTime leaseExpiresAt) { }

    public record ObservationRequest(
            @NotNull UUID eventId,
            @NotNull UUID toolCallId,
            @NotNull @Min(1) Integer executionNo,
            @NotBlank @Size(max = 512) String executionToken,
            @NotBlank @Pattern(regexp = "^step-[1-9][0-9]?$" ) String stepId,
            @NotNull @Min(1) @Max(10) Integer stepAttempt,
            @NotBlank @Size(max = 16) String status,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String resultType,
            @Size(max = 256) String resultRef,
            @NotBlank @Size(max = 1000) String summary,
            @NotNull JsonNode facts,
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String stableErrorCode,
            boolean retryable,
            @NotNull OffsetDateTime completedAt) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow observation field");
        }
    }

    public record Observation(String id, String stepId, String eventId, String toolCallId,
                              int stepAttempt, String status, String resultType,
                              String resultRef, String summary, JsonNode facts,
                              String stableErrorCode, boolean retryable,
                              OffsetDateTime completedAt) { }
    public record ObservationAccepted(boolean accepted, String disposition,
                                      String planStatus, long sequence) { }

    public record StepStateRequest(@NotNull UUID eventId,
                                   @NotNull @Min(1) Integer executionNo,
                                   @NotBlank @Size(max = 512) String executionToken,
                                   @NotBlank @Size(max = 16) String status,
                                   @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String stableErrorCode) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow step state field");
        }
    }

    public record TaskActivationRequest(
            @NotNull UUID eventId,
            @NotNull @Min(1) Integer executionNo,
            @NotBlank @Size(max = 512) String executionToken,
            @NotNull @Min(1) Integer planVersion,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String taskDraftId) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow task activation field");
        }
    }

    public record TaskActivationResponse(boolean accepted, String disposition,
                                         String planId, String stepId, String taskId,
                                         String taskStatus, String stepStatus,
                                         boolean waitingForTask, int executionNo) { }

    public record ReplanRequest(@NotNull UUID eventId,
                                @NotNull @Min(1) Integer executionNo,
                                @NotBlank @Size(max = 512) String executionToken,
                                @NotNull @Min(1) Integer expectedPlanVersion,
                                @NotNull @Min(2) Integer newPlanVersion,
                                @NotNull @Size(min = 1, max = 12)
                                List<@Valid StepRevision> steps) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow replan field");
        }
    }
    public record StepRevision(@NotBlank @Pattern(regexp = "^step-[1-9][0-9]?$" ) String stepId,
                               @NotNull @Size(max = 12) List<@Pattern(
                                       regexp = "^step-[1-9][0-9]?$" ) String> dependsOn,
                               @NotNull @Size(min = 1, max = 8) List<@NotBlank @Size(max = 300) String> successCriteria,
                               @NotNull @Size(max = 16) List<@NotBlank @Size(max = 128) String> inputRefs,
                               @Size(max = 128) String outputRef) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow step revision field");
        }
    }

    public record CompletionRequest(@NotNull UUID eventId,
                                    @NotNull @Min(1) Integer executionNo,
                                    @NotBlank @Size(max = 512) String executionToken,
                                    @NotBlank @Size(max = 24) String status,
                                    @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String stableErrorCode) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown workflow completion field");
        }
    }

    public record Accepted(boolean accepted, String disposition, String planStatus,
                           int planVersion) { }
}
