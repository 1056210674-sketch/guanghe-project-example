package com.guanghe.backend.geo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class GeoDiagnosticReportDtos {
    private GeoDiagnosticReportDtos() { }
    public record Submit(@NotNull @Min(1) Integer version,@NotNull UUID quoteId,
        @NotBlank @Pattern(regexp="[0-9a-f]{64}") String inputHash,@NotNull UUID requestId) {
        @JsonAnySetter public void unknown(String field,JsonNode value){throw new IllegalArgumentException("Unknown diagnostic submit field");}
    }
    public record Retry(@NotNull @Min(1) Integer version,@NotNull UUID requestId) {
        @JsonAnySetter public void unknown(String field,JsonNode value){throw new IllegalArgumentException("Unknown report retry field");}
    }
    public record Delivery(String taskId,String runId,String status,String samplingStatus,boolean hasNoData,
        String errorCode,boolean canRetry,int progressPercent,int reportVersion) { }
    public record Mention(String brandId,String name,int mentionedSamples,Double mentionRate) { }
    public record Discovery(String platformCode,int expectedSamples,int validSamples,int failedSamples,int missingSamples,List<Mention> mentions) { }
    public record Reputation(String platformCode,int expectedSamples,int validSamples,int failedSamples,int missingSamples,int recognizedSamples) { }
    public record Metrics(int expectedSamples,int validSamples,int failedSamples,int missingSamples,List<Discovery> discovery,List<Reputation> reputation) { }
    public record Sample(String unitKey,String questionId,String category,String questionText,String platformCode,String status,
        String answerExcerpt,boolean answerTruncated,String stableErrorCode,List<String> sourceIds,List<String> matchedBrandIds) {
        public Sample(String unitKey,String questionId,String category,String questionText,String platformCode,String status,
            String answerExcerpt,boolean answerTruncated,String stableErrorCode,List<String> sourceIds) {
            this(unitKey,questionId,category,questionText,platformCode,status,answerExcerpt,answerTruncated,stableErrorCode,sourceIds,null);
        }
    }
    public record Evidence(String unitKey,String sourceId,String quote) { }
    public record Finding(String section,String heading,String description,List<Evidence> evidence) { }
    public record Recommendation(String priority,String title,String action,List<Evidence> basisEvidence,
        String currentState,String businessImpact,String deliverable,String acceptance,String ownerRole,String timeframe) {
        public Recommendation(String priority,String title,String action,List<Evidence> basisEvidence) {
            this(priority,title,action,basisEvidence,null,null,null,null,null,null);
        }
    }
    public record ContentTopic(String title,String audienceQuestion,String angle,List<String> coveredBrandTerms,List<Evidence> basisEvidence,String intentLayer) {
        public ContentTopic(String title,String audienceQuestion,String angle,List<String> coveredBrandTerms,List<Evidence> basisEvidence) {
            this(title,audienceQuestion,angle,coveredBrandTerms,basisEvidence,null);
        }
    }
    public record CoreJudgment(String headline,String description,List<Evidence> evidence) { }
    public record CognitionFinding(String title,String observation,String businessImpact,List<Evidence> evidence) { }
    public record KeywordLayer(String layer,List<String> questionIds,String assessment,String nextAction,List<Evidence> evidence) { }
    public record RoadmapStage(String stage,String objective,List<Integer> recommendationIndexes) { }
    public record Strategy(String version,CoreJudgment coreJudgment,List<CognitionFinding> cognitionFindings,
        List<KeywordLayer> keywordLayers,List<RoadmapStage> roadmap) { }
    public record Analysis(String title,String summary,List<Finding> findings,List<Recommendation> recommendations,List<ContentTopic> contentTopics,List<String> limitations,Strategy strategy) {
        public Analysis(String title,String summary,List<Finding> findings,List<Recommendation> recommendations,List<ContentTopic> contentTopics,List<String> limitations) {
            this(title,summary,findings,recommendations,contentTopics,limitations,null);
        }
    }
    public record Pdf(String filename,long sizeBytes,String sha256) { }
    public record Detail(String id,int version,int reportVersion,String taskId,String runId,String status,String samplingStatus,boolean hasNoData,
        int analysisAttempts,int pdfAttempts,boolean canRetry,String errorCode,int quotedPoints,int settledPoints,int releasedPoints,
        GeoDiagnosticDtos.Profile profile,List<String> platformCodes,Metrics metrics,Analysis analysis,List<Sample> samples,
        List<GeoDiagnosticDtos.Source> sources,List<String> limitations,OffsetDateTime snapshotAt,Pdf pdf,List<GeoDiagnosticDtos.Competitor> competitors) {
        public Detail(String id,int version,int reportVersion,String taskId,String runId,String status,String samplingStatus,boolean hasNoData,
            int analysisAttempts,int pdfAttempts,boolean canRetry,String errorCode,int quotedPoints,int settledPoints,int releasedPoints,
            GeoDiagnosticDtos.Profile profile,List<String> platformCodes,Metrics metrics,Analysis analysis,List<Sample> samples,
            List<GeoDiagnosticDtos.Source> sources,List<String> limitations,OffsetDateTime snapshotAt,Pdf pdf) {
            this(id,version,reportVersion,taskId,runId,status,samplingStatus,hasNoData,analysisAttempts,pdfAttempts,canRetry,errorCode,
                quotedPoints,settledPoints,releasedPoints,profile,platformCodes,metrics,analysis,samples,sources,limitations,snapshotAt,pdf,List.of());
        }
    }
    public record Download(String downloadUrl,OffsetDateTime expiresAt,String filename) { }
}
