package com.guanghe.backend.geo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

public final class GeoTrendDtos {
    private GeoTrendDtos() { }

    public record Response(String topicId, int days, String questionScope, String methodVersion,
                           OffsetDateTime from, OffsetDateTime to, boolean historyTruncated,
                           int pointLimit, int baselineScanLimit, List<Platform> platforms,
                           List<History> history) { }
    public record Platform(String platformCode, String baselineReportId, OffsetDateTime baselineObservedAt,
                           int baselineQuestionCount, int baselineSampleCount, List<Point> points) { }
    public record Point(String reportId, String runId, OffsetDateTime observedAt,
                        GeoTaskDtos.VisibilityTarget target, String region, int questionCount,
                        int baselineQuestionCount, int explorationQuestionCount,
                        int expectedSamples, int validSamples, Integer mentionedSamples, Double mentionRate,
                        int baselineExpectedSamples, int baselineValidSamples,
                        Integer baselineMentionedSamples, Double baselineMentionRate,
                        int failedSamples, int missingSamples, boolean evidenceTruncated,
                        String comparisonStatus, String comparisonReason,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String missingPeriod,
                        boolean connectToPrevious,
                        Comparison comparison, List<QuestionChange> questionChanges) { }
    public record Comparison(String previousReportId, String currentReportId, int commonQuestions,
                             int commonSamples, int previousMentionedSamples, int currentMentionedSamples,
                             double previousMentionRate, double currentMentionRate,
                             double deltaPercentagePoints) { }
    public record QuestionChange(String questionId, String questionVersion, String questionText,
                                 boolean inBaseline, String change, String reason,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String missingPeriod,
                                 String previousReportId, String currentReportId,
                                 Integer previousMentionedSamples, int previousValidSamples,
                                 Integer currentMentionedSamples, int currentValidSamples,
                                 List<String> previousUnitKeys, List<String> currentUnitKeys) { }
    public record History(String reportId, String runId, OffsetDateTime observedAt, int questionCount,
                          int addedQuestionCount, int removedQuestionCount, int changedQuestionCount,
                          String changeStatus) { }
}
