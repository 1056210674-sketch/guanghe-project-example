package com.guanghe.backend.task;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhixiaohe.paid-task")
public class PaidTaskProperties {
    private boolean enabled;
    private String tokenSecret = "";
    private Duration confirmationTtl = Duration.ofMinutes(10);
    private Duration executionLease = Duration.ofMinutes(2);
    private Duration maxQueueAge = Duration.ofMinutes(30);
    private int maxExecutionAttempts = 2;
    private long imageMockPricingVersionId;
    private int imageMockPoints;
    private long videoTestPricingVersionId;
    private int videoTestPoints;
    private int videoMaxProviderCalls = 61;
    private boolean referenceImageGenerationEnabled;
    private boolean imageRegionEditEnabled;
    private boolean imageToVideoEnabled;
    private boolean geoExecutionEnabled;
    private boolean geoInterpretationEnabled;
    private boolean geoDiagnosticEnabled;
    private boolean geoDiagnosticSubmissionEnabled;
    private int geoDiagnosticMaxProviderCalls = 200;
    private long geoTestPricingVersionId;
    private int geoTestUnitPoints;
    private int geoMaxProviderCalls = 20;
    private int geoMaxAttemptsPerUnit = 2;
    private int geoMaxConcurrency = 3;
    private Duration geoExecutionDeadline = Duration.ofMinutes(30);
    private Set<String> geoConfiguredPlatforms = new LinkedHashSet<>();
    private boolean geoPlanEnabled;
    private int geoPlanMinIntervalDays = 1;
    private int geoPlanMaxIntervalDays = 365;
    private boolean articleExecutionEnabled;
    private long articleTestPricingVersionId;
    private int articleTitleTestPoints;
    private int articleOutlineTestPoints;
    private int articleBodyTestPoints;
    private int articleRewriteTestPoints;
    private int articleMaxModelCalls = 2;
    private int articleMaxRepairRetries = 1;
    private int articleMaxInputTokens = 128000;
    private int articleMaxOutputTokens = 16000;
    private Duration articleExecutionDeadline = Duration.ofMinutes(10);
    private boolean outboxPublisherEnabled;
    private boolean executionWatchdogEnabled = true;
    private String exchange = "zhixiaohe.task";
    private String imageRoutingKey = "agent.image";
    private String imageQueue = "zhixiaohe.agent.image";
    private String videoRoutingKey = "agent.video";
    private String videoQueue = "zhixiaohe.agent.video";
    private String geoRoutingKey = "agent.geo";
    private String geoQueue = "zhixiaohe.agent.geo";
    private String articleRoutingKey = "agent.article";
    private String articleQueue = "zhixiaohe.agent.article";
    private String deadLetterExchange = "zhixiaohe.task.dlx";
    private String imageDeadLetterQueue = "zhixiaohe.agent.image.dlq";
    private String videoDeadLetterQueue = "zhixiaohe.agent.video.dlq";
    private String geoDeadLetterQueue = "zhixiaohe.agent.geo.dlq";
    private String articleDeadLetterQueue = "zhixiaohe.agent.article.dlq";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTokenSecret() { return tokenSecret; }
    public void setTokenSecret(String tokenSecret) { this.tokenSecret = tokenSecret; }
    public Duration getConfirmationTtl() { return confirmationTtl; }
    public void setConfirmationTtl(Duration confirmationTtl) { this.confirmationTtl = confirmationTtl; }
    public Duration getExecutionLease() { return executionLease; }
    public void setExecutionLease(Duration executionLease) { this.executionLease = executionLease; }
    public Duration getMaxQueueAge() { return maxQueueAge; }
    public void setMaxQueueAge(Duration maxQueueAge) { this.maxQueueAge = maxQueueAge; }
    public int getMaxExecutionAttempts() { return maxExecutionAttempts; }
    public void setMaxExecutionAttempts(int maxExecutionAttempts) { this.maxExecutionAttempts = maxExecutionAttempts; }
    public long getImageMockPricingVersionId() { return imageMockPricingVersionId; }
    public void setImageMockPricingVersionId(long value) { this.imageMockPricingVersionId = value; }
    public int getImageMockPoints() { return imageMockPoints; }
    public void setImageMockPoints(int value) { this.imageMockPoints = value; }
    public long getVideoTestPricingVersionId() { return videoTestPricingVersionId; }
    public void setVideoTestPricingVersionId(long value) { this.videoTestPricingVersionId = value; }
    public int getVideoTestPoints() { return videoTestPoints; }
    public void setVideoTestPoints(int value) { this.videoTestPoints = value; }
    public int getVideoMaxProviderCalls() { return videoMaxProviderCalls; }
    public void setVideoMaxProviderCalls(int value) { videoMaxProviderCalls = value; }
    public boolean isReferenceImageGenerationEnabled() { return referenceImageGenerationEnabled; }
    public void setReferenceImageGenerationEnabled(boolean value) { referenceImageGenerationEnabled = value; }
    public boolean isImageRegionEditEnabled() { return imageRegionEditEnabled; }
    public void setImageRegionEditEnabled(boolean value) { imageRegionEditEnabled = value; }
    public boolean isImageToVideoEnabled() { return imageToVideoEnabled; }
    public void setImageToVideoEnabled(boolean value) { imageToVideoEnabled = value; }
    public boolean isGeoExecutionEnabled() { return geoExecutionEnabled; }
    public void setGeoExecutionEnabled(boolean value) { geoExecutionEnabled = value; }
    public boolean isGeoInterpretationEnabled() { return geoInterpretationEnabled; }
    public void setGeoInterpretationEnabled(boolean value) { geoInterpretationEnabled = value; }
    public boolean isGeoDiagnosticEnabled() { return geoDiagnosticEnabled; }
    public void setGeoDiagnosticEnabled(boolean value) { geoDiagnosticEnabled = value; }
    public boolean isGeoDiagnosticSubmissionEnabled() { return geoDiagnosticSubmissionEnabled; }
    public void setGeoDiagnosticSubmissionEnabled(boolean value) { geoDiagnosticSubmissionEnabled = value; }
    public int getGeoDiagnosticMaxProviderCalls() { return geoDiagnosticMaxProviderCalls; }
    public void setGeoDiagnosticMaxProviderCalls(int value) { geoDiagnosticMaxProviderCalls = value; }
    public long getGeoTestPricingVersionId() { return geoTestPricingVersionId; }
    public void setGeoTestPricingVersionId(long value) { geoTestPricingVersionId = value; }
    public int getGeoTestUnitPoints() { return geoTestUnitPoints; }
    public void setGeoTestUnitPoints(int value) { geoTestUnitPoints = value; }
    public int getGeoMaxProviderCalls() { return geoMaxProviderCalls; }
    public void setGeoMaxProviderCalls(int value) { geoMaxProviderCalls = value; }
    public int getGeoMaxAttemptsPerUnit() { return geoMaxAttemptsPerUnit; }
    public void setGeoMaxAttemptsPerUnit(int value) { geoMaxAttemptsPerUnit = value; }
    public int getGeoMaxConcurrency() { return geoMaxConcurrency; }
    public void setGeoMaxConcurrency(int value) { geoMaxConcurrency = value; }
    public Duration getGeoExecutionDeadline() { return geoExecutionDeadline; }
    public void setGeoExecutionDeadline(Duration value) { geoExecutionDeadline = value; }
    public Set<String> getGeoConfiguredPlatforms() { return Set.copyOf(geoConfiguredPlatforms); }
    public void setGeoConfiguredPlatforms(Set<String> value) {
        geoConfiguredPlatforms = value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value);
    }
    public int getGeoMaxDiscoveryQuestions() {
        if (!geoExecutionEnabled || geoConfiguredPlatforms.isEmpty()) return 0;
        int callsPerQuestion = geoRequiredProviderCalls(1, geoConfiguredPlatforms, 1);
        return Math.min(10, geoMaxProviderCalls / callsPerQuestion);
    }
    /** Search-backed samples use both a search call and an answer call on every attempt. */
    public int geoRequiredProviderCalls(int questions, java.util.Collection<String> platforms, int rounds) {
        int callsPerAttempt = platforms.stream()
                .mapToInt(code -> Set.of("DEEPSEEK", "KIMI", "YUANBAO").contains(code) ? 2 : 1).sum();
        return Math.multiplyExact(Math.multiplyExact(questions, rounds),
                Math.multiplyExact(callsPerAttempt, geoMaxAttemptsPerUnit));
    }
    public boolean isGeoPlanEnabled() { return geoPlanEnabled; }
    public void setGeoPlanEnabled(boolean value) { geoPlanEnabled = value; }
    public int getGeoPlanMinIntervalDays() { return geoPlanMinIntervalDays; }
    public void setGeoPlanMinIntervalDays(int value) { geoPlanMinIntervalDays = value; }
    public int getGeoPlanMaxIntervalDays() { return geoPlanMaxIntervalDays; }
    public void setGeoPlanMaxIntervalDays(int value) { geoPlanMaxIntervalDays = value; }
    public boolean isArticleExecutionEnabled() { return articleExecutionEnabled; }
    public void setArticleExecutionEnabled(boolean value) { articleExecutionEnabled = value; }
    public long getArticleTestPricingVersionId() { return articleTestPricingVersionId; }
    public void setArticleTestPricingVersionId(long value) { articleTestPricingVersionId = value; }
    public int getArticleTitleTestPoints() { return articleTitleTestPoints; }
    public void setArticleTitleTestPoints(int value) { articleTitleTestPoints = value; }
    public int getArticleOutlineTestPoints() { return articleOutlineTestPoints; }
    public void setArticleOutlineTestPoints(int value) { articleOutlineTestPoints = value; }
    public int getArticleBodyTestPoints() { return articleBodyTestPoints; }
    public void setArticleBodyTestPoints(int value) { articleBodyTestPoints = value; }
    public int getArticleRewriteTestPoints() { return articleRewriteTestPoints; }
    public void setArticleRewriteTestPoints(int value) { articleRewriteTestPoints = value; }
    public int getArticleMaxModelCalls() { return articleMaxModelCalls; }
    public void setArticleMaxModelCalls(int value) { articleMaxModelCalls = value; }
    public int getArticleMaxRepairRetries() { return articleMaxRepairRetries; }
    public void setArticleMaxRepairRetries(int value) { articleMaxRepairRetries = value; }
    public int getArticleMaxInputTokens() { return articleMaxInputTokens; }
    public void setArticleMaxInputTokens(int value) { articleMaxInputTokens = value; }
    public int getArticleMaxOutputTokens() { return articleMaxOutputTokens; }
    public void setArticleMaxOutputTokens(int value) { articleMaxOutputTokens = value; }
    public Duration getArticleExecutionDeadline() { return articleExecutionDeadline; }
    public void setArticleExecutionDeadline(Duration value) { articleExecutionDeadline = value; }
    public boolean isOutboxPublisherEnabled() { return outboxPublisherEnabled; }
    public void setOutboxPublisherEnabled(boolean value) { this.outboxPublisherEnabled = value; }
    public boolean isExecutionWatchdogEnabled() { return executionWatchdogEnabled; }
    public void setExecutionWatchdogEnabled(boolean value) { this.executionWatchdogEnabled = value; }
    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public String getImageRoutingKey() { return imageRoutingKey; }
    public void setImageRoutingKey(String imageRoutingKey) { this.imageRoutingKey = imageRoutingKey; }
    public String getImageQueue() { return imageQueue; }
    public void setImageQueue(String imageQueue) { this.imageQueue = imageQueue; }
    public String getVideoRoutingKey() { return videoRoutingKey; }
    public void setVideoRoutingKey(String value) { videoRoutingKey = value; }
    public String getVideoQueue() { return videoQueue; }
    public void setVideoQueue(String value) { videoQueue = value; }
    public String getGeoRoutingKey() { return geoRoutingKey; }
    public void setGeoRoutingKey(String value) { geoRoutingKey = value; }
    public String getGeoQueue() { return geoQueue; }
    public void setGeoQueue(String value) { geoQueue = value; }
    public String getArticleRoutingKey() { return articleRoutingKey; }
    public void setArticleRoutingKey(String value) { articleRoutingKey = value; }
    public String getArticleQueue() { return articleQueue; }
    public void setArticleQueue(String value) { articleQueue = value; }
    public String getDeadLetterExchange() { return deadLetterExchange; }
    public void setDeadLetterExchange(String deadLetterExchange) { this.deadLetterExchange = deadLetterExchange; }
    public String getImageDeadLetterQueue() { return imageDeadLetterQueue; }
    public void setImageDeadLetterQueue(String imageDeadLetterQueue) { this.imageDeadLetterQueue = imageDeadLetterQueue; }
    public String getVideoDeadLetterQueue() { return videoDeadLetterQueue; }
    public void setVideoDeadLetterQueue(String value) { videoDeadLetterQueue = value; }
    public String getGeoDeadLetterQueue() { return geoDeadLetterQueue; }
    public void setGeoDeadLetterQueue(String value) { geoDeadLetterQueue = value; }
    public String getArticleDeadLetterQueue() { return articleDeadLetterQueue; }
    public void setArticleDeadLetterQueue(String value) { articleDeadLetterQueue = value; }

    public long pricingVersionId(String taskType) {
        return switch (taskType) {
            case "VIDEO" -> videoTestPricingVersionId;
            case "GEO" -> geoTestPricingVersionId;
            case "ARTICLE" -> articleTestPricingVersionId;
            default -> imageMockPricingVersionId;
        };
    }

    public int quotedPoints(String taskType) {
        return "VIDEO".equals(taskType) ? videoTestPoints : imageMockPoints;
    }

    public int articleQuotedPoints(String mode) {
        return switch (mode) {
            case "TITLE" -> articleTitleTestPoints;
            case "OUTLINE" -> articleOutlineTestPoints;
            case "BODY" -> articleBodyTestPoints;
            case "REWRITE" -> articleRewriteTestPoints;
            default -> 0;
        };
    }

    /** Java-owned stage pricing, shared by manual tasks and full-article preparation. */
    public ArticleStageQuote articleStageQuote(String mode) {
        if (mode == null || !Set.of("TITLE", "OUTLINE", "BODY", "REWRITE").contains(mode)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        int points = articleQuotedPoints(mode);
        if (points <= 0 || articleTestPricingVersionId <= 0) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        return new ArticleStageQuote(mode, articleTestPricingVersionId, points);
    }

    /** Matches the bounded ARTICLE execution contract; values are copied, not live configuration. */
    public ArticleStageBudget articleStageBudget() {
        if (!articleStageBudgetConfigured()) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        return new ArticleStageBudget(articleMaxModelCalls, articleMaxRepairRetries,
                articleMaxInputTokens, articleMaxOutputTokens, articleExecutionDeadline);
    }

    private boolean articleStageBudgetConfigured() {
        return !(articleMaxModelCalls < 1 || articleMaxModelCalls > 10
                || articleMaxRepairRetries < 0 || articleMaxRepairRetries > 5
                || articleMaxInputTokens < 1 || articleMaxInputTokens > 200_000
                || articleMaxOutputTokens < 1 || articleMaxOutputTokens > 64_000
                || articleExecutionDeadline == null || articleExecutionDeadline.isZero()
                || articleExecutionDeadline.isNegative());
    }

    public record ArticleStageQuote(String mode, long pricingVersionId, int quotedPoints) { }
    public record ArticleStageBudget(int maxModelCalls, int maxRepairRetries, int maxInputTokens,
                                     int maxOutputTokens, Duration executionDeadline) { }

    public String routingKey(String taskType) {
        return switch (taskType) {
            case "VIDEO" -> videoRoutingKey;
            case "GEO" -> geoRoutingKey;
            case "ARTICLE" -> articleRoutingKey;
            default -> imageRoutingKey;
        };
    }

    public void validate() {
        if (!enabled) return;
        if (tokenSecret == null || tokenSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("TASK_CONTROL_TOKEN_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (confirmationTtl == null || confirmationTtl.isNegative() || confirmationTtl.isZero()
                || executionLease == null || executionLease.isNegative() || executionLease.isZero()
                || maxQueueAge == null || maxQueueAge.isNegative() || maxQueueAge.isZero()) {
            throw new IllegalStateException("Paid task token durations must be positive");
        }
        if (imageMockPricingVersionId <= 0 || imageMockPoints <= 0
                || videoTestPricingVersionId <= 0 || videoTestPoints <= 0
                || videoMaxProviderCalls < 1 || videoMaxProviderCalls > 1000 || maxExecutionAttempts < 2) {
            throw new IllegalStateException(
                    "Explicit IMAGE and VIDEO test pricing must be configured when paid tasks are enabled");
        }
        Set<String> allowedGeoPlatforms = Set.of(
                "DOUBAO", "DEEPSEEK", "QWEN", "WENXIN", "KIMI", "YUANBAO", "ZHIPU");
        if (!allowedGeoPlatforms.containsAll(geoConfiguredPlatforms)) {
            throw new IllegalStateException("GEO configured platforms contain an unsupported code");
        }
        if (geoDiagnosticEnabled && (geoDiagnosticMaxProviderCalls < 1 || geoDiagnosticMaxProviderCalls > 1000))
            throw new IllegalStateException("Diagnostic provider call budget must be between 1 and 1000");
        if (geoExecutionEnabled && (geoTestPricingVersionId <= 0 || geoTestUnitPoints <= 0
                || geoMaxProviderCalls < 1 || geoMaxProviderCalls > 1000
                || geoMaxAttemptsPerUnit < 1 || geoMaxAttemptsPerUnit > 4
                || geoMaxConcurrency < 1 || geoMaxConcurrency > 20
                || geoExecutionDeadline == null || geoExecutionDeadline.isZero()
                || geoExecutionDeadline.isNegative()
                || geoConfiguredPlatforms.isEmpty() || getGeoMaxDiscoveryQuestions() < 3)) {
            throw new IllegalStateException("Explicit GEO test execution and pricing must be configured");
        }
        if (geoPlanEnabled && (!geoExecutionEnabled || geoPlanMinIntervalDays < 1
                || geoPlanMaxIntervalDays < geoPlanMinIntervalDays
                || geoPlanMaxIntervalDays > 3650)) {
            throw new IllegalStateException(
                    "Automatic GEO plans require enabled GEO execution and a valid interval range");
        }
        boolean articleConfigured = articleExecutionEnabled || articleTestPricingVersionId > 0
                || articleTitleTestPoints > 0 || articleOutlineTestPoints > 0
                || articleBodyTestPoints > 0 || articleRewriteTestPoints > 0;
        if (articleConfigured && (articleTestPricingVersionId <= 0 || articleTitleTestPoints <= 0
                || articleOutlineTestPoints <= 0 || articleBodyTestPoints <= 0
                || articleRewriteTestPoints <= 0 || !articleStageBudgetConfigured())) {
            throw new IllegalStateException("Explicit ARTICLE test pricing and budgets must be configured");
        }
        if (isBlank(exchange) || isBlank(imageRoutingKey) || isBlank(imageQueue)
                || isBlank(videoRoutingKey) || isBlank(videoQueue)
                || isBlank(geoRoutingKey) || isBlank(geoQueue)
                || isBlank(articleRoutingKey) || isBlank(articleQueue)
                || isBlank(deadLetterExchange) || isBlank(imageDeadLetterQueue)
                || isBlank(videoDeadLetterQueue) || isBlank(geoDeadLetterQueue)
                || isBlank(articleDeadLetterQueue)) {
            throw new IllegalStateException("Paid task RabbitMQ topology must be configured");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
