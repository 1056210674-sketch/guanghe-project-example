package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** A direct project-report use case, separate from Agent intent routing and paid task execution. */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class GeoPeriodReportService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final GeoPeriodReportMapper mapper;
    private final GeoTrendService trends;
    private final ObjectMapper json;
    private final SnowflakeIdGenerator ids;
    private final TransactionTemplate snapshots;

    public GeoPeriodReportService(GeoPeriodReportMapper mapper, GeoTrendService trends,
                                  ObjectMapper json, SnowflakeIdGenerator ids, PlatformTransactionManager transactions) {
        this.mapper = mapper; this.trends = trends; this.json = json; this.ids = ids;
        this.snapshots = new TransactionTemplate(transactions);
        this.snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public GeoPeriodReportDtos.Detail create(AuthenticatedAccount account, GeoPeriodReportDtos.Create request) {
        // A concurrent identical admission may win after our repeatable-read snapshot was opened.
        // Retry only the local rolled-back snapshot transaction; no provider call occurs here.
        try { return snapshots.execute(unused -> createSnapshot(account, request)); }
        catch (CannotSerializeTransactionException | DataIntegrityViolationException concurrent) {
            return snapshots.execute(unused -> createSnapshot(account, request));
        }
    }

    private GeoPeriodReportDtos.Detail createSnapshot(AuthenticatedAccount account, GeoPeriodReportDtos.Create request) {
        long userId = CatalogAccess.requireUser(account);
        long projectId = id(request.projectId());
        OffsetDateTime now = mapper.snapshotTime();
        validateDates(request.startDate(), request.endDate(), now);
        String title = clean(request.title(), 100);
        String focus = clean(request.focus(), 500);
        String inputHash = hash(write(List.of(request.projectId(), request.startDate().toString(),
                request.endDate().toString(), title, focus)));
        mapper.lockOwner(userId).orElseThrow(this::notFound);
        String projectName = mapper.project(userId, projectId).orElseThrow(this::notFound);
        var existing = mapper.byRequest(userId, request.requestId()).orElse(null);
        if (existing != null) {
            if (!inputHash.equals(existing.inputHash())) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return detail(existing);
        }
        if (mapper.active(userId) > 0) throw new BusinessException(ErrorCode.RATE_LIMITED,
                "已有阶段报告正在生成，请稍后再试", Map.of("reason", "PERIOD_REPORT_ACTIVE"));
        OffsetDateTime from = request.startDate().atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime requestedTo = request.endDate().plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime to = requestedTo.isAfter(now) ? now : requestedTo;
        var counts = mapper.runs(userId, projectId, from, to);
        long articleCount = mapper.articleCount(userId, projectId, from, to);
        String articleStatusCounts = mapper.articleStatusCounts(userId, projectId, from, to);
        var articles = mapper.articles(userId, projectId, from, to);
        var published = mapper.publicationCount(userId, projectId, from, to);
        long links = mapper.linkCount(userId, projectId, from, to);
        var publicationRows = mapper.publications(userId, projectId, from, to);
        var publications = publicationRows.stream().limit(100).map(row -> new GeoPeriodReportDtos.Publication(
                Long.toString(row.id()), Long.toString(row.articleId()), row.articleTitle(), row.mediaName(),
                row.status(), row.createdAt(), row.publishedAt(), safeLinks(row))).toList();
        List<String> limits = new ArrayList<>(List.of(
                "本报告呈现已完成的工作和已保存监测样本中的变化，不证明发文导致可见度提升，不代表市场份额或推荐排名。",
                "日期按北京时间自然日统计；数据固定于快照时刻，后续完成、修改或回执不自动更新本报告。",
                "新建文章数按周期内创建的不同文章统计，包含草稿，不等于成稿数；文章标题和版本为本次快照时的保存值。发布次数按媒体投递计，同文多媒体分别计次。",
                "已结束监测按完成时间归入周期，趋势横轴也是完成时间；周期前基线必须在周期开始前已完成。仍在执行的监测按周期内创建且快照时仍未结束单列，未作成功计数。",
                "发布按系统首次确认成功时间统计，不冒充媒体原始发布时间；周期内新建且尚未成功的投稿单列。",
                "趋势只评估未在问题中直接写出目标名称的发现型问题；失败和缺失不当成未提及，未采样平台不补零。",
                "期初优先选周期前最近有有效样本的已保存批次，无则使用周期内首个有效批次并明确标注；不同专题不合并计算提升。"
        ));
        if (requestedTo.isAfter(now)) limits.add("结束日为今天，今天尚未发生的时间不在本次统计中。");
        if (articleCount > articles.size()) limits.add("文章明细仅展示最早100篇；汇总数量包含完整符合条件的数据。");
        if (publicationRows.size() > 100) limits.add("投稿明细仅展示最早100条；汇总和回链数量独立按完整数据统计。");
        var topicRows = mapper.topics(userId, projectId, from, to);
        if (topicRows.size() > 6) limits.add("趋势只展示最早6个专题；监测汇总仍包含本项目该周期全部专题。");
        List<GeoPeriodReportDtos.Topic> topics = new ArrayList<>();
        for (var topic : topicRows.stream().limit(6).toList()) {
            var batches = mapper.batches(userId, projectId, topic.id(), from, to);
            if (batches.size() > 100) limits.add("专题「" + topic.name() + "」超过100个批次，仅展示前100个；不据此计算整个周期的首尾提升。");
            var baseline = mapper.baseline(userId, projectId, topic.id(), from).orElse(null);
            var projection = trends.period(userId, projectId, baseline, batches.stream().limit(100).toList());
            if (projection.evidenceTruncated()) limits.add("专题「" + topic.name() + "」部分原始证据超过处理上限，受影响对比标记不可比。");
            List<GeoPeriodReportDtos.Platform> platforms = projection.platforms();
            if (batches.size() > 100) platforms = platforms.stream().map(value -> new GeoPeriodReportDtos.Platform(
                    value.platformCode(), value.baselineReportId(), value.baselineObservedAt(), value.endReportId(),
                    value.endObservedAt(), null, null, null, 0, "NOT_COMPARABLE", "PERIOD_TRUNCATED", value.points())).toList();
            topics.add(new GeoPeriodReportDtos.Topic(Long.toString(topic.id()), topic.name(), "DISCOVERY", projection.baselineKind(), platforms));
        }
        var metrics = new GeoPeriodReportDtos.Metrics(counts.total(), counts.completed(), counts.partial(), counts.failed(),
                counts.running(), counts.expected(), counts.valid(), counts.failedSamples(), counts.missing(),
                articleCount, published.published(), published.articles(), links, published.pending(), published.failed());
        boolean empty = counts.total() == 0 && articleCount == 0 && publications.isEmpty();
        String reportTitle = title.isBlank() ? projectName.substring(0, Math.min(80, projectName.length())) + " · GEO 阶段报告" : title;
        var snapshot = new GeoPeriodReportDtos.Snapshot(Long.toString(projectId), projectName, reportTitle, focus,
                request.startDate(), request.endDate(), now, empty, metrics, List.copyOf(topics), articles, publications, List.copyOf(limits));
        ObjectNode evidence = evidence(snapshot);
        try { evidence.set("articleStatusCounts", json.readTree(articleStatusCounts)); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid article status projection"); }
        evidence.put("articleStatusAsOf", now.toString());
        String evidenceJson = write(evidence);
        if (evidenceJson.getBytes(StandardCharsets.UTF_8).length > 48 * 1024) throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "本次报告资料过多，请缩短日期范围", Map.of("reason", "PERIOD_REPORT_LIMIT"));
        String snapshotJson = write(snapshot);
        if (snapshotJson.getBytes(StandardCharsets.UTF_8).length > 1_900_000) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        long reportId = ids.nextId();
        var row = new GeoPeriodReportMapper.Row(reportId, userId, projectId, request.requestId(), inputHash,
                snapshotJson, evidenceJson, hash(evidenceJson), empty ? "READY" : "QUEUED",
                empty ? "所选项目在此日期范围内暂无已保存的监测、文章或投稿记录，暂无可评估的阶段成果。" : null,
                null, 0, null, null, 1, now, now);
        mapper.insert(row);
        return detail(row);
    }

    @Transactional(readOnly = true)
    public GeoPeriodReportDtos.Detail get(AuthenticatedAccount account, long reportId) {
        long userId = CatalogAccess.requireUser(account);
        var row = mapper.find(userId, reportId).orElseThrow(this::notFound);
        mapper.project(userId, row.spaceId()).orElseThrow(this::notFound);
        return detail(row);
    }

    @Transactional(readOnly = true)
    public PageResponse<GeoPeriodReportDtos.Summary> list(AuthenticatedAccount account, long projectId, int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        mapper.project(userId, projectId).orElseThrow(this::notFound);
        var rows = mapper.list(userId, projectId, size, (long) (page - 1) * size);
        return PageResponse.of(rows.stream().map(row -> {
            var value = snapshot(row);
            return new GeoPeriodReportDtos.Summary(Long.toString(row.id()), row.version(), value.projectId(),
                    value.projectName(), value.title(), value.startDate(), value.endDate(), value.snapshotAt(), row.status(), value.empty());
        }).toList(), page, size, mapper.count(userId, projectId));
    }

    @Transactional
    public GeoPeriodReportDtos.Detail retry(AuthenticatedAccount account, long reportId) {
        long userId = CatalogAccess.requireUser(account);
        mapper.lockOwner(userId).orElseThrow(this::notFound);
        var row = mapper.lock(userId, reportId).orElseThrow(this::notFound);
        mapper.project(userId, row.spaceId()).orElseThrow(this::notFound);
        if (Set.of("READY", "QUEUED", "RUNNING").contains(row.status())) return detail(row);
        if (row.summaryAttempts() >= 2) throw new BusinessException(ErrorCode.RATE_LIMITED,
                "该报告的总结尝试次数已用完，原始统计和PDF仍可查看", Map.of("reason", "PERIOD_REPORT_ATTEMPT_LIMIT"));
        if (mapper.active(userId) > 0) throw new BusinessException(ErrorCode.RATE_LIMITED);
        mapper.retry(reportId, userId, now());
        return detail(mapper.find(userId, reportId).orElseThrow());
    }

    record Claim(long id, UUID token, ObjectNode request) { }
    @Transactional
    public Optional<Claim> claimNext() {
        OffsetDateTime now = now();
        mapper.expire(now);
        var row = mapper.next().orElse(null);
        if (row == null) return Optional.empty();
        UUID token = UUID.randomUUID();
        if (mapper.claim(row.id(), token, now.plusSeconds(90), now) != 1) return Optional.empty();
        var snapshot = snapshot(row);
        ObjectNode request = json.createObjectNode().put("jobId", Long.toString(row.id()))
                .put("inputHash", row.evidenceHash()).put("deadlineAt", now.plusSeconds(60).toString())
                .put("title", snapshot.title());
        if (!snapshot.focus().isBlank()) request.put("focus", snapshot.focus());
        try { request.set("evidence", json.readTree(row.evidenceJson())); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid saved period report evidence"); }
        return Optional.of(new Claim(row.id(), token, request));
    }

    @Transactional
    public void completed(Claim claim, JsonNode result) {
        String summary = result == null ? "" : result.path("summaryMarkdown").asText("").strip();
        if (result == null || !Long.toString(claim.id()).equals(result.path("jobId").asText())
                || !claim.request().path("inputHash").asText().equals(result.path("inputHash").asText())
                || !"GEO_PERIOD_REPORT_SUMMARY_V1".equals(result.path("promptVersion").asText())
                || summary.isBlank() || summary.length() > 6000 || summary.contains("http://") || summary.contains("https://")) {
            failed(claim, "SUMMARY_INVALID_RESULT"); return;
        }
        mapper.finish(claim.id(), claim.token(), "READY", summary, null, now());
    }

    @Transactional
    public void failed(Claim claim, String error) {
        mapper.finish(claim.id(), claim.token(), "SUMMARY_UNAVAILABLE", null, error, now());
    }

    private GeoPeriodReportDtos.Detail detail(GeoPeriodReportMapper.Row row) {
        var data = snapshot(row);
        return new GeoPeriodReportDtos.Detail(Long.toString(row.id()), row.version(), data.projectId(), data.projectName(),
                data.title(), data.focus(), data.startDate(), data.endDate(), ZONE.getId(), data.snapshotAt(), row.status(),
                row.summaryAttempts(), "SUMMARY_UNAVAILABLE".equals(row.status()) && row.summaryAttempts() < 2,
                row.summaryMarkdown(), row.summaryError(), data.empty(), data.metrics(), data.topics(), data.articles(),
                data.publications(), data.limitations());
    }
    private GeoPeriodReportDtos.Snapshot snapshot(GeoPeriodReportMapper.Row row) {
        try { return json.readValue(row.snapshotJson(), GeoPeriodReportDtos.Snapshot.class); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid saved period report snapshot"); }
    }

    private ObjectNode evidence(GeoPeriodReportDtos.Snapshot snapshot) {
        ObjectNode root = json.createObjectNode();
        root.put("projectName", snapshot.projectName()).put("startDate", snapshot.startDate().toString())
                .put("endDate", snapshot.endDate().toString()).put("timezone", ZONE.getId());
        root.set("metrics", json.valueToTree(snapshot.metrics()));
        root.set("limitations", json.valueToTree(snapshot.limitations()));
        ArrayNode topics = root.putArray("topics");
        Set<String> reasons = new LinkedHashSet<>();
        boolean hasComparable = false;
        for (var topic : snapshot.topics()) {
            ObjectNode item = topics.addObject().put("topicId", topic.topicId()).put("name", topic.topicName())
                    .put("baselineKind", topic.baselineKind());
            ArrayNode platforms = item.putArray("platforms");
            for (var platform : topic.platforms()) {
                ObjectNode value = platforms.addObject().put("platformCode", platform.platformCode())
                        .put("comparisonStatus", platform.comparisonStatus()).put("comparisonReason", platform.comparisonReason())
                        .put("commonSamples", platform.commonSamples()).put("baselineReportId", platform.baselineReportId())
                        .put("endReportId", platform.endReportId());
                value.set("baselineMentionRate", json.valueToTree(platform.baselineMentionRate()));
                value.set("endMentionRate", json.valueToTree(platform.endMentionRate()));
                value.set("deltaPercentagePoints", json.valueToTree(platform.deltaPercentagePoints()));
                if (!platform.points().isEmpty()) {
                    var point = platform.points().getLast();
                    LocalDate observedDate = point.observedAt().atZoneSameInstant(ZONE).toLocalDate();
                    ObjectNode observation = value.putObject("latestIncludedObservation")
                            .put("reportId", point.reportId()).put("runId", point.runId())
                            .put("observedAt", point.observedAt().toString())
                            .put("validSamples", point.validSamples()).put("failedSamples", point.failedSamples())
                            .put("missingSamples", point.missingSamples())
                            .put("isLatestInPeriod", !observedDate.isBefore(snapshot.startDate())
                                    && !observedDate.isAfter(snapshot.endDate())
                                    && !"PERIOD_TRUNCATED".equals(platform.comparisonReason()));
                    observation.set("mentionRate", json.valueToTree(point.mentionRate()));
                }
                if ("COMPARABLE".equals(platform.comparisonStatus())) hasComparable = true;
                else reasons.add(platform.comparisonReason() == null ? "NOT_COMPARABLE" : platform.comparisonReason());
            }
        }
        ObjectNode comparison = root.putObject("comparison");
        comparison.put("comparable", hasComparable && reasons.isEmpty());
        if (!hasComparable) reasons.add("NO_COMPARABLE_PLATFORM");
        comparison.set("reasons", json.valueToTree(reasons));
        ArrayNode articles = root.putArray("articleExamples");
        snapshot.articles().stream().limit(12).forEach(article -> articles.addObject().put("articleId", article.id()).put("title", article.title()));
        root.put("instruction", "仅作定性总结、局限和建议；数值与图表由程序展示，不输出数字、表格、网址，不声称优化造成因果提升。各专题平台分别解读。");
        return root;
    }
    private List<String> safeLinks(GeoPeriodReportMapper.Publication row) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        try {
            JsonNode values = row.linksJson() == null ? json.createArrayNode() : json.readTree(row.linksJson());
            if (values.isArray()) for (JsonNode value : values) if (value.isTextual()) addLink(links, value.asText());
            if (links.isEmpty() && row.link() != null) addLink(links, row.link());
        } catch (Exception ignored) { /* A missing receipt URL is not fabricated or fetched. */ }
        return List.copyOf(links);
    }
    private static void addLink(Set<String> links, String value) {
        if (links.size() >= 20 || value.length() > 2048) return;
        try {
            var uri = java.net.URI.create(value);
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null) links.add(uri.toASCIIString());
        } catch (IllegalArgumentException ignored) { }
    }
    private static void validateDates(LocalDate from, LocalDate to, OffsetDateTime now) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 92
                || to.isAfter(now.atZoneSameInstant(ZONE).toLocalDate()))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择不超过92天、且不含未来日期的范围", Map.of("reason", "PERIOD_REPORT_DATE_RANGE"));
    }
    private static long id(String value) {
        try { long id = Long.parseLong(value); if (id <= 0) throw new NumberFormatException(); return id; }
        catch (RuntimeException invalid) { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
    }
    private static String clean(String value, int max) {
        if (value == null) return "";
        String result = value.strip();
        if (result.length() > max) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return result;
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Could not encode period report"); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
