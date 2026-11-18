package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded projection of existing facts for one conversation Run; never starts an analysis. */
@Component
public class GeoChatContextFactory {
    private static final Logger log = LoggerFactory.getLogger(GeoChatContextFactory.class);
    static final int MAX_CHARACTERS = 16_384;
    private final AccountMapper accounts;
    private final GeoTaskMapper records;
    private final GeoService topics;
    private final GeoTaskService reports;
    private final GeoTrendService trends;
    private final GeoCompetitiveService competitive;
    private final ObjectMapper json;
    private TransactionTemplate readTransaction;

    private record Projection(ObjectNode context, Long trendAnchorRunId) { }

    @Autowired
    public GeoChatContextFactory(ObjectProvider<AccountMapper> accounts, ObjectProvider<GeoTaskMapper> records,
            ObjectProvider<GeoService> topics, ObjectProvider<GeoTaskService> reports,
            ObjectProvider<GeoTrendService> trends, ObjectProvider<GeoCompetitiveService> competitive,
            ObjectMapper json, ObjectProvider<PlatformTransactionManager> transactionManager) {
        this(accounts.getIfAvailable(), records.getIfAvailable(), topics.getIfAvailable(),
                reports.getIfAvailable(), trends.getIfAvailable(), competitive.getIfAvailable(), json);
        if (transactionManager.getIfAvailable() != null) {
            readTransaction = new TransactionTemplate(transactionManager.getObject());
            readTransaction.setReadOnly(true);
            readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            readTransaction.setTimeout(10);
        }
    }

    GeoChatContextFactory(AccountMapper accounts, GeoTaskMapper records, GeoService topics,
            GeoTaskService reports, GeoTrendService trends, GeoCompetitiveService competitive, ObjectMapper json) {
        this.accounts = accounts; this.records = records; this.topics = topics; this.reports = reports;
        this.trends = trends; this.competitive = competitive; this.json = json;
    }

    public JsonNode create(long userId, Long spaceId, Long productId, Long reportId) {
        try {
            var projection = readTransaction == null ? project(userId, spaceId, productId, reportId)
                    : readTransaction.execute(status -> project(userId, spaceId, productId, reportId));
            var result = projection == null ? null : projection.context();
            if (result != null && "READY".equals(result.path("status").asText()) && trends != null) {
                try {
                    // Trend failure cannot mark the already completed report projection (or send transaction) rollback-only.
                    java.util.function.Supplier<ObjectNode> loadTrend = () -> {
                        var identity = accounts.findIdentityById(userId).orElseThrow(GeoChatContextFactory::notFound);
                        if (identity.role() != AccountRole.USER || identity.status() != AccountStatus.ACTIVE) throw notFound();
                        return trend(trends.trendAtRun(AuthenticatedAccount.from(identity),
                                Long.parseLong(result.at("/report/topicId").asText()), 30, "DISCOVERY",
                                projection.trendAnchorRunId()));
                    };
                    result.set("trend", readTransaction == null ? loadTrend.get() : readTransaction.execute(status -> loadTrend.get()));
                } catch (RuntimeException exception) {
                    rethrowAccessError(exception);
                    logUnavailable("trend", exception);
                    result.putObject("trend").put("days",30).put("status","UNAVAILABLE")
                            .put("metricScope","SELF_DISCOVERY_COMMON_BASELINE")
                            .put("historyTruncated",false).putArray("platforms");
                }
            }
            return result == null ? empty("UNAVAILABLE") : bounded(result);
        } catch (BusinessException exception) {
            if (exception.errorCode() == ErrorCode.RESOURCE_NOT_FOUND || exception.errorCode() == ErrorCode.FORBIDDEN
                    || exception.errorCode() == ErrorCode.VALIDATION_FAILED) throw exception;
            logUnavailable("context", exception);
            return empty("UNAVAILABLE");
        } catch (RuntimeException exception) {
            logUnavailable("context", exception);
            return empty("UNAVAILABLE");
        }
    }

    private Projection project(long userId, Long spaceId, Long productId, Long reportId) {
        if (spaceId == null) {
            if (reportId != null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            return new Projection(empty("NEEDS_SCOPE"), null);
        }
        if (accounts == null || records == null || topics == null || reports == null)
            return new Projection(empty("UNAVAILABLE"), null);
        try {
            var identity = accounts.findIdentityById(userId).orElseThrow(GeoChatContextFactory::notFound);
            if (identity.role() != AccountRole.USER || identity.status() != AccountStatus.ACTIVE) throw notFound();
            var account = AuthenticatedAccount.from(identity);
            var result = empty("READY");
            Long selectedReport = reportId;
            if (selectedReport == null) {
                var page = topics.list(account, spaceId, productId, null, 1, 6);
                result.put("topicsTruncated", page.total() > 6);
                for (var topic : page.items()) {
                    var choice = result.withArray("topicChoices").addObject();
                    choice.put("topicId", topic.id()).put("spaceId", topic.spaceId());
                    put(choice, "productId", topic.productId()); choice.put("name", clipped(topic.name(), 100));
                    choice.put("href", "/app/geo?topicId=" + topic.id());
                }
                if (page.total() != 1 || page.items().size() != 1) {
                    result.put("status", page.total() == 0 ? "NO_REPORT" : "NEEDS_TOPIC");
                    return new Projection(result, null);
                }
                var latest = reports.reports(account, page.items().getFirst().id(), false, 1, 1);
                if (latest.items().isEmpty()) {
                    result.put("status", "NO_REPORT");
                    return new Projection(result, null);
                }
                selectedReport = Long.valueOf(latest.items().getFirst().id());
                result.put("selectionSource", "AUTO_SINGLE_TOPIC");
            } else result.put("selectionSource", "EXPLICIT_REPORT");

            // Both checks precede detail reads; report ownership alone is not scope authorization.
            var saved = records.findReport(selectedReport, userId).orElseThrow(GeoChatContextFactory::notFound);
            var run = records.findRun(saved.runId(), userId).orElseThrow(GeoChatContextFactory::notFound);
            if (saved.archivedAt() != null || !java.util.Set.of("SUCCEEDED", "PARTIAL").contains(saved.status())
                    || !java.util.Set.of("SUCCEEDED", "PARTIAL").contains(run.status())
                    || !records.activeProject(userId, run.spaceId())
                    || run.spaceId() != spaceId || saved.topicId() != run.topicId()
                    || (productId != null && !Objects.equals(productId, run.productId()))) {
                if (reportId == null) return new Projection(empty("UNAVAILABLE"), null);
                throw notFound();
            }
            var detail = reports.report(account, selectedReport);
            var report = result.putObject("report");
            report.put("reportId", detail.id()).put("topicId", detail.topicId());
            report.put("spaceId", Long.toString(run.spaceId()));
            put(report, "productId", run.productId() == null ? null : Long.toString(run.productId()));
            String articleUnavailable = saved.archivedAt() != null ? "REPORT_ARCHIVED"
                    : !java.util.Set.of("SUCCEEDED", "PARTIAL").contains(saved.status()) ? "REPORT_NOT_TERMINAL"
                    : !"geo-synthesis-v1".equals(saved.methodVersion()) || saved.sampleSize() <= 0
                    ? "REPORT_ANALYSIS_UNAVAILABLE" : null;
            report.put("canCreateArticle", articleUnavailable == null);
            put(report, "articleUnavailableReason", articleUnavailable);
            report.put("title", clipped(detail.topic().name(), 100));
            put(report, "generatedAt", detail.generatedAt()); report.put("status", detail.status());
            report.put("interpretationStatus", detail.interpretation().status());
            report.set("target", target(detail.visibility().target()));
            report.put("interpretationScope", "ALL_REPORT_QUESTIONS");
            report.put("metricScope", "COMPETITIVE_DISCOVERY");
            var readableNarrative = readableNarrative(detail);
            var summary = readableNarrative.apply(detail.synthesis().summary());
            var findings = detail.synthesis().findings() == null ? List.<String>of()
                    : detail.synthesis().findings().stream().map(readableNarrative).toList();
            report.put("summary", clipped(summary, 1200));
            texts(report.putArray("findings"), findings, 3, 240);
            if (shortened(summary,1200) || shortened(findings,3,240)
                    || shortened(detail.topic().name(),100) || (detail.visibility().target()!=null
                    && (shortened(detail.visibility().target().name(),200) || shortened(detail.visibility().target().aliases(),10,200)))) {
                result.put("truncated",true);
            }
            report.putArray("platforms");
            var links = (ObjectNode) result.get("links");
            links.put("overviewPath", "/app/geo?topicId=" + detail.topicId() + "&days=30&questionScope=DISCOVERY");
            links.put("reportPath", "/app/geo/reports/" + selectedReport);
            links.put("competitivePath", "/app/geo/competitors?topicId=" + detail.topicId() + "&reportId=" + selectedReport);
            // This service calculates natural-discovery denominators even when cached analysis is absent.
            if (competitive != null) {
                var insight = competitive.get(account, selectedReport, "DISCOVERY");
                result.set("competitors", competitors(insight, result));
                for (var platform : insight.platforms()) {
                    var own = platform.entities().stream().filter(item -> "OWN".equals(item.role())).findFirst().orElse(null);
                    var value = report.withArray("platforms").addObject();
                    value.put("platformCode", platform.platformCode()).put("expectedSamples", platform.expectedUnits())
                            .put("validSamples", platform.validUnits()).put("failedSamples", platform.failedUnits())
                            .put("missingSamples", platform.missingUnits());
                    value.set("mentionedSamples", json.valueToTree(own == null ? null : own.mentionedUnits()));
                    value.set("mentionRate", json.valueToTree(own == null ? null : own.mentionRate()));
                }
            }
            return new Projection(bounded(result), run.id());
        } catch (BusinessException exception) {
            if (exception.errorCode() == ErrorCode.RESOURCE_NOT_FOUND || exception.errorCode() == ErrorCode.FORBIDDEN
                    || exception.errorCode() == ErrorCode.VALIDATION_FAILED) throw exception;
            logUnavailable("report", exception);
            return new Projection(empty("UNAVAILABLE"), null);
        } catch (RuntimeException exception) {
            // Optional business evidence must not break a normal conversation. No internal errors enter the model.
            logUnavailable("report", exception);
            return new Projection(empty("UNAVAILABLE"), null);
        }
    }

    private ObjectNode empty(String status) {
        var value = json.createObjectNode();
        value.put("schemaVersion", 1).put("status", status).put("selectionSource", "NONE")
                .put("readOnly", true).put("questionScope", "DISCOVERY").put("topicsTruncated", false).put("truncated", false);
        value.putArray("topicChoices"); value.putNull("report"); value.putNull("trend"); value.putNull("competitors");
        value.putObject("links").put("overviewPath", "/app/geo").putNull("reportPath").putNull("competitivePath");
        return value;
    }

    private JsonNode target(GeoTaskDtos.VisibilityTarget target) {
        if (target == null) return json.nullNode();
        var value = json.createObjectNode().put("kind", target.kind()).put("id", target.id()).put("name", clipped(target.name(), 200));
        texts(value.putArray("aliases"), target.aliases(), 10, 200); return value;
    }

    private static UnaryOperator<String> readableNarrative(GeoTaskDtos.ReportDetail detail) {
        // Only translate exact references from this already-authorized frozen report. Never
        // redact arbitrary numbers (prices, dates or SKUs), or alter the persisted synthesis.
        var questions = new LinkedHashMap<String, String>();
        for (var question : detail.questions()) {
            if (question.id() != null && !question.id().isBlank()
                    && question.text() != null && !question.text().isBlank()) {
                questions.putIfAbsent(question.id(), "问题「" + question.text() + "」");
            }
        }
        var references = new LinkedHashMap<>(questions);
        for (var observation : detail.observations()) {
            var question = questions.get(observation.questionId());
            if (question != null && observation.unitKey() != null && !observation.unitKey().isBlank()) {
                references.put(observation.unitKey(), question + "（" + observation.platformCode()
                        + "，第" + observation.sampleNo() + "轮）");
            }
        }
        if (references.isEmpty()) return UnaryOperator.identity();
        var alternatives = references.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        // The allowlisted literals must not match inside a longer number or ASCII SKU.
        var pattern = Pattern.compile("(?<![A-Za-z0-9_])(?:" + alternatives + ")(?![A-Za-z0-9_])");
        return text -> {
            if (text == null) return null;
            // One pass: question text is data, not another replacement expression or reference.
            return pattern.matcher(text).replaceAll(match -> Matcher.quoteReplacement(references.get(match.group())));
        };
    }

    private ObjectNode competitors(GeoCompetitiveDtos.Insights source, ObjectNode context) {
        var value = json.createObjectNode().put("status", source.status()).put("evidenceTruncated", source.evidenceTruncated())
                .put("metricScope", "COMPETITIVE_DISCOVERY");
        var platforms = value.putArray("platforms");
        if (source.platforms().size()>7) context.put("truncated",true);
        for (var platform : source.platforms().stream().limit(7).toList()) {
            var node = platforms.addObject().put("platformCode", platform.platformCode()).put("expectedUnits", platform.expectedUnits())
                    .put("validUnits", platform.validUnits()).put("failedUnits", platform.failedUnits()).put("missingUnits", platform.missingUnits());
            var entities = node.putArray("entities");
            if (platform.entities().size() > 7) context.put("truncated", true);
            var selected = platform.entities().stream().sorted(Comparator.comparingInt(entity ->
                    "OWN".equals(entity.role()) ? 0 : "DISCOVERED".equals(entity.role()) ? 1 : 2)).limit(7).toList();
            for (var entity : selected) {
                if (shortened(entity.name(),200)) context.put("truncated",true);
                var item = entities.addObject().put("name", clipped(entity.name(), 200)).put("entityKind", entity.entityKind())
                        .put("role", entity.role()).put("recognitionStatus", entity.recognitionStatus());
                item.set("mentionedUnits", json.valueToTree(entity.mentionedUnits()));
                item.set("mentionRate", json.valueToTree(entity.mentionRate()));
            }
        }
        var candidates = value.putArray("candidates");
        if (source.candidates().size() > 6) context.put("truncated", true);
        for (var candidate : source.candidates().stream().limit(6).toList()) {
            if (shortened(candidate.name(),200) || shortened(candidate.learningSuggestions(),3,240)
                    || candidate.evidence().size()>2) context.put("truncated",true);
            var item = candidates.addObject().put("candidateId", candidate.id()).put("name", clipped(candidate.name(), 200))
                    .put("entityKind", candidate.entityKind()).put("status", candidate.status());
            var evidence = item.putArray("evidence");
            for (var proof : candidate.evidence().stream().limit(2).toList()) {
                evidence.addObject().put("reportId", proof.reportId()).put("unitKey", clipped(proof.unitKey(), 128))
                        .put("quote", clipped(proof.quote(), 240)).put("signal", proof.signal());
                if (shortened(proof.quote(),240) || shortened(proof.unitKey(),128)) context.put("truncated", true);
            }
            texts(item.putArray("learningSuggestions"), candidate.learningSuggestions(), 3, 240);
        }
        return value;
    }

    private ObjectNode trend(GeoTrendDtos.Response source) {
        var value = json.createObjectNode().put("days", 30).put("status", "READY").put("historyTruncated", source.historyTruncated())
                .put("metricScope", "SELF_DISCOVERY_COMMON_BASELINE");
        var platforms = value.putArray("platforms");
        for (var platform : source.platforms().stream().limit(7).toList()) {
            if (platform.points().isEmpty()) continue;
            var point = platform.points().getLast(); var comparison = point.comparison();
            var item = platforms.addObject().put("platformCode", platform.platformCode()).put("currentReportId", point.reportId())
                    .put("comparisonStatus", point.comparisonStatus());
            put(item, "comparisonReason", point.comparisonReason()); put(item, "currentObservedAt", point.observedAt());
            if (point.missingPeriod() != null) item.put("missingPeriod", point.missingPeriod());
            put(item, "previousReportId", comparison == null ? null : comparison.previousReportId());
            // Do not let Java unbox the nullable unassessed point when the other ternary branch is primitive double.
            Double currentMentionRate = point.mentionRate();
            if (comparison != null) currentMentionRate = comparison.currentMentionRate();
            item.set("currentMentionRate", json.valueToTree(currentMentionRate));
            item.set("previousMentionRate", json.valueToTree(comparison == null ? null : comparison.previousMentionRate()));
            item.set("deltaPercentagePoints", json.valueToTree(comparison == null ? null : comparison.deltaPercentagePoints()));
            item.set("commonSamples", json.valueToTree(comparison == null ? null : comparison.commonSamples()));
        }
        return value;
    }

    private ObjectNode bounded(ObjectNode value) {
        if (value.toString().length() <= MAX_CHARACTERS) return value;
        value.put("truncated", true);
        var report = (ObjectNode) value.get("report"); report.put("summary", ""); report.putArray("findings");
        for (var candidate : value.path("competitors").path("candidates")) {
            ((ObjectNode) candidate).putArray("evidence"); ((ObjectNode) candidate).putArray("learningSuggestions");
        }
        for (var platform : value.path("competitors").path("platforms")) {
            var entities = (ArrayNode) platform.get("entities");
            while (entities.size() > 1 && value.toString().length() > MAX_CHARACTERS) entities.remove(entities.size() - 1);
        }
        if (value.toString().length() > MAX_CHARACTERS) {
            var fallback = empty("UNAVAILABLE"); fallback.put("truncated", true); return fallback;
        }
        return value;
    }

    private void texts(ArrayNode target, List<String> source, int limit, int length) {
        if (source != null) source.stream().limit(limit).forEach(text -> target.add(clipped(text, length)));
    }
    private static void put(ObjectNode node, String field, Object value) {
        if (value == null) node.putNull(field);
        else if (value instanceof java.time.OffsetDateTime timestamp) {
            node.put(field, timestamp.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } else node.put(field, value.toString());
    }
    private static String clipped(String value, int length) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), length));
    }
    private static boolean shortened(String value,int length) { return value!=null && value.length()>length; }
    private static boolean shortened(List<String> value,int items,int length) {
        return value!=null && (value.size()>items || value.stream().anyMatch(text -> shortened(text,length)));
    }
    private static void rethrowAccessError(RuntimeException exception) {
        if (exception instanceof BusinessException business && (business.errorCode() == ErrorCode.RESOURCE_NOT_FOUND
                || business.errorCode() == ErrorCode.FORBIDDEN || business.errorCode() == ErrorCode.VALIDATION_FAILED)) throw business;
    }
    private static void logUnavailable(String phase, RuntimeException exception) {
        // Trace/span already comes from the request MDC. Never emit IDs, payload, message or provider text.
        log.warn("geo_chat_projection_unavailable phase={} reason={}", phase, exception.getClass().getSimpleName());
    }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
