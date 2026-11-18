package com.guanghe.backend.geo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
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

public final class GeoTaskDtos {
    private GeoTaskDtos() { }

    public record PlatformResponse(String code, String name, boolean configured,
                                   boolean selectable, String liveStatus) { }

    public record TopicSnapshot(String id, String spaceId, String productId, String name,
                                String region, int version) { }
    public record QuestionSnapshot(String id, String category, String text) { }
    public record CompetitorSnapshot(String id, String name, List<String> aliases,
                                     String competitorType) { }

    public record RunSummary(String id, String taskId, String topicId, String status,
                             String sampleMode, int expectedUnits, int succeededUnits,
                             int failedUnits, int missingUnits, OffsetDateTime createdAt,
                             OffsetDateTime finishedAt) { }

    /**
     * A read-only projection for long-running GEO polling.  The run table remains the
     * source of truth; this response only combines its persisted counters with the
     * existing business-task stage so clients never have to infer progress from chat
     * text or a stale browser event.
     */
    public record RunProgressResponse(String runId, String taskId, String status,
                                      String stageCode, int percent, int completedUnits,
                                      int totalUnits, int succeededUnits, int failedUnits,
                                      int missingUnits, String reportId, boolean terminal,
                                      boolean retryable, OffsetDateTime updatedAt,
                                      OffsetDateTime finishedAt) { }

    public record RunDetail(String id, String taskId, String topicId, String spaceId,
                            String productId, String status, String sampleMode,
                            int expectedUnits, int succeededUnits, int failedUnits,
                            int missingUnits, TopicSnapshot topic,
                            List<QuestionSnapshot> questions, List<String> platformCodes,
                            Integer competitorSetVersion, List<CompetitorSnapshot> competitors,
                            String metricVersion, String methodVersion,
                            OffsetDateTime startedAt, OffsetDateTime finishedAt,
                            OffsetDateTime createdAt) { }

    public record CitationResponse(int sequence, String title, String url, String domain,
                                   String snippet, OffsetDateTime observedAt) { }

    public record UsageResponse(int inputTokens, int outputTokens, int searchRequests,
                                String monetaryCost, String currency) { }

    public record ObservationResponse(String unitKey, String questionId, String questionText,
                                      String platformCode, int sampleNo, String status,
                                      String answerText, String stableErrorCode,
                                      String provider, String model, OffsetDateTime startedAt,
                                      OffsetDateTime finishedAt, int latencyMs,
                                      UsageResponse usage, int providerCallCount,
                                      List<CitationResponse> citations, String collectionMethod) {
        public ObservationResponse(String unitKey, String questionId, String questionText, String platformCode,
                                   int sampleNo, String status, String answerText, String stableErrorCode,
                                   String provider, String model, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                                   int latencyMs, UsageResponse usage, int providerCallCount,
                                   List<CitationResponse> citations) {
            this(unitKey, questionId, questionText, platformCode, sampleNo, status, answerText, stableErrorCode,
                    provider, model, startedAt, finishedAt, latencyMs, usage, providerCallCount, citations, null);
        }
    }

    public record RecommendationResponse(String id, String priority, String title,
                                         String rationale) {
        /** Compatibility constructor for callers that only have the persisted payload. */
        public RecommendationResponse(String priority, String title, String rationale) {
            this(null, priority, title, rationale);
        }
    }

    public record SynthesisResponse(String summary, List<String> coverageGaps,
                                    List<String> citationStructure, List<String> competitorSignals,
                                    List<String> findings, List<RecommendationResponse> recommendations,
                                    List<String> contentBlueprint, List<String> missingSamples,
                                    String methodVersion) { }

    public record ReportSummary(String id, String runId, String taskId, String topicId,
                                String status, String summary, int sampleSize,
                                int settledPoints, int releasedPoints,
                                OffsetDateTime generatedAt, OffsetDateTime archivedAt,
                                String topicName, List<String> platformCodes) {
        public ReportSummary(String id, String runId, String taskId, String topicId,
                String status, String summary, int sampleSize, int settledPoints, int releasedPoints,
                OffsetDateTime generatedAt, OffsetDateTime archivedAt) {
            this(id, runId, taskId, topicId, status, summary, sampleSize, settledPoints,
                    releasedPoints, generatedAt, archivedAt, null, List.of());
        }
    }

    public record ReportDetail(String id, String runId, String taskId, String topicId,
                               String status, TopicSnapshot topic,
                               List<QuestionSnapshot> questions, List<String> platformCodes,
                               int expectedUnits, int succeededUnits, int failedUnits,
                               int missingUnits, List<ObservationResponse> observations,
                               SynthesisResponse synthesis, int settledPoints,
                               int releasedPoints, OffsetDateTime generatedAt,
                               String methodVersion, OffsetDateTime archivedAt,
                               VisibilityResponse visibility, ReportInterpretation interpretation) { }

    public record ReportInterpretation(String status, boolean canRetry, int attempts, int maxAttempts,
                                       OffsetDateTime requestedAt, OffsetDateTime finishedAt,
                                       String failureCode, String unavailableReason, OffsetDateTime retryAfter) { }

    public record VisibilityTarget(String kind, String id, String name, List<String> aliases) { }
    public record VisibilityPlatform(String platformCode, int expectedSamples, int validSamples,
                                     Integer mentionedSamples, int failedSamples, int missingSamples,
                                     Double mentionRate) { }
    public record VisibilitySample(String unitKey, Boolean mentioned, List<String> matchedTerms) { }
    public record VisibilityResponse(String methodVersion, String assessmentStatus,
                                     String unavailableReason, VisibilityTarget target,
                                     List<VisibilityPlatform> platforms,
                                     List<VisibilitySample> samples) { }

    public record CitationInput(@Size(max = 500) String title,
                                @NotBlank @Size(max = 2048) String url,
                                @Size(max = 253) String domain,
                                @Size(max = 2000) String snippet,
                                @NotNull OffsetDateTime observedAt) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO citation field");
        }
    }

    public record UsageInput(@NotNull @Min(0) Integer inputTokens,
                             @NotNull @Min(0) Integer outputTokens,
                             @NotNull @Min(0) Integer searchRequests,
                             @Size(max = 64) @Pattern(regexp = "^\\d+(\\.\\d+)?$") String monetaryCost,
                             @Pattern(regexp = "^[A-Z]{3}$") String currency) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO usage field");
        }
    }

    public record ObservationInput(
            @NotBlank @Pattern(regexp = "^(DOUBAO|DEEPSEEK|QWEN|WENXIN|KIMI|YUANBAO|ZHIPU)$")
            String platformCode,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String questionId,
            @NotNull @Min(1) @Max(3) Integer sampleNo,
            @NotBlank @Pattern(regexp = "^(SUCCEEDED|FAILED)$") String status,
            @Size(max = 100000) String answerText,
            @NotNull @Size(max = 50) List<@Valid CitationInput> citations,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String provider,
            @NotBlank @Size(max = 128) String model,
            @NotNull OffsetDateTime startedAt,
            @NotNull OffsetDateTime finishedAt,
            @NotNull @Min(0) @Max(3600000) Integer latencyMs,
            @Size(max = 128) @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,127}$") String stableErrorCode,
            @NotNull @Valid UsageInput usage,
            @NotNull @Min(0) @Max(100) Integer providerCallCount,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Pattern(regexp = "^[a-z][a-z0-9-]{0,127}$") String collectionMethod) {
        public ObservationInput(String platformCode, String questionId, Integer sampleNo, String status,
                                String answerText, List<CitationInput> citations, String provider, String model,
                                OffsetDateTime startedAt, OffsetDateTime finishedAt, Integer latencyMs,
                                String stableErrorCode, UsageInput usage, Integer providerCallCount) {
            this(platformCode, questionId, sampleNo, status, answerText, citations, provider, model,
                    startedAt, finishedAt, latencyMs, stableErrorCode, usage, providerCallCount, null);
        }
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO observation field");
        }
    }

    public record ObservationRequest(@NotNull UUID eventId,
                                     @NotNull @Min(1) Integer executionNo,
                                     @NotBlank @Size(max = 512) String executionToken,
                                     @NotNull @Valid ObservationInput observation) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO observation request field");
        }
    }

    public record ObservationAccepted(boolean accepted, String disposition, String taskStatus) { }

    public record RecommendationInput(
            @NotBlank @Pattern(regexp = "^P[012]$") String priority,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 1000) String rationale) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO recommendation field");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SynthesisInput(
            @NotBlank @Size(max = 4000) String summary,
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 1000) String> coverageGaps,
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 1000) String> citationStructure,
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 1000) String> competitorSignals,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 1000) String> findings,
            @NotNull @Size(max = 30) List<@Valid RecommendationInput> recommendations,
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 1000) String> contentBlueprint,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 256) String> missingSamples,
            @NotBlank @Pattern(regexp = "^(geo-synthesis-v1|geo-deterministic-v1|geo-diagnostic-sampling-v1)$") String methodVersion,
            List<JsonNode> competitorCandidates, String competitorDiscoveryVersion) {
        public SynthesisInput(String summary, List<String> coverageGaps, List<String> citationStructure,
                List<String> competitorSignals, List<String> findings, List<RecommendationInput> recommendations,
                List<String> contentBlueprint, List<String> missingSamples, String methodVersion) {
            this(summary, coverageGaps, citationStructure, competitorSignals, findings, recommendations,
                    contentBlueprint, missingSamples, methodVersion, null, null);
        }
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO synthesis field");
        }
    }

    public record SynthesisUsageInput(
            @NotBlank @Pattern(regexp = "^(DEEPSEEK|SYSTEM)$") String provider,
            @NotBlank @Size(max = 128) String model,
            @NotNull @Min(0) Integer inputTokens,
            @NotNull @Min(0) Integer outputTokens,
            @NotNull @Min(0) @Max(10) Integer providerCallCount,
            @NotNull @Min(0) @Max(3600000) Integer latencyMs) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO synthesis usage field");
        }
    }

    public record CompletionRequest(@NotNull UUID eventId,
                                    @NotNull @Min(1) Integer executionNo,
                                    @NotBlank @Size(max = 512) String executionToken,
                                    @NotNull @Valid SynthesisInput synthesis,
                                    @NotNull @Valid SynthesisUsageInput synthesisUsage) {
        @JsonAnySetter public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO completion request field");
        }
    }

    public record CompletionAccepted(boolean accepted, String taskStatus, String runStatus,
                                     String reportId, int settledPoints, int releasedPoints) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InternalObservationPage(List<ObservationResponse> items, int page, int size,
                                          long total, int totalPages,
                                          VisibilityEvidence visibilityEvidence) { }

    /** Full saved-unit lexical truth, independent of the current observation page. */
    public record VisibilityEvidence(String targetStatus, String matchingVersion,
                                     List<VisibilityEvidenceUnit> units) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VisibilityEvidenceUnit(String questionId, String platformCode, int sampleNo,
                                         Boolean targetMentioned) { }
}
