package com.guanghe.backend.geo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A saved customer-facing report. Monetary and supplier fields are deliberately absent. */
public final class GeoPeriodReportDtos {
    private GeoPeriodReportDtos() { }

    public record Create(@NotNull UUID requestId,
                         @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String projectId,
                         @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                         @Size(max = 100) String title, @Size(max = 500) String focus) { }
    public record Detail(String id, int version, String projectId, String projectName,
                         String title, String focus, LocalDate startDate, LocalDate endDate,
                         String timezone, OffsetDateTime snapshotAt, String status, int summaryAttempts,
                         boolean canRetrySummary, String summaryMarkdown, String summaryError,
                         boolean empty, Metrics metrics, List<Topic> topics, List<Article> articles,
                         List<Publication> publications, List<String> limitations) { }
    public record Summary(String id, int version, String projectId, String projectName, String title,
                          LocalDate startDate, LocalDate endDate, OffsetDateTime snapshotAt,
                          String status, boolean empty) { }
    public record Metrics(long monitorRuns, long completedMonitorRuns, long partialMonitorRuns,
                          long failedMonitorRuns, long runningMonitorRuns,
                          long expectedSamples, long validSamples, long failedSamples, long missingSamples,
                          long createdArticles, long publishedPlacements, long publishedArticles,
                          long publishedLinks, long pendingSubmissions, long failedSubmissions) { }
    public record Topic(String topicId, String topicName, String questionScope, String baselineKind,
                        List<Platform> platforms) { }
    public record Platform(String platformCode, String baselineReportId, OffsetDateTime baselineObservedAt,
                           String endReportId, OffsetDateTime endObservedAt,
                           Double baselineMentionRate, Double endMentionRate, Double deltaPercentagePoints,
                           int commonSamples, String comparisonStatus, String comparisonReason,
                           List<Point> points) { }
    public record Point(String reportId, String runId, OffsetDateTime observedAt, Double mentionRate,
                        int validSamples, int failedSamples, int missingSamples,
                        String comparisonStatus, String comparisonReason, boolean connectToPrevious) { }
    public record Article(String id, String title, int version, OffsetDateTime createdAt) { }
    public record Publication(String id, String articleId, String articleTitle, String mediaName,
                              String status, OffsetDateTime createdAt, OffsetDateTime confirmedPublishedAt,
                              List<String> publishedUrls) { }
    /** Immutable data snapshot; summary retry never queries business source tables again. */
    public record Snapshot(String projectId, String projectName, String title, String focus,
                           LocalDate startDate, LocalDate endDate, OffsetDateTime snapshotAt,
                           boolean empty, Metrics metrics, List<Topic> topics, List<Article> articles,
                           List<Publication> publications, List<String> limitations) { }
}
