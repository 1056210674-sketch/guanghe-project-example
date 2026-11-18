package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A projection over immutable collection facts, never today's topic questions or catalog. */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.geo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GeoTrendService {
    static final int POINT_LIMIT = 100;
    static final int BASELINE_LIMIT = 100;
    static final int EVIDENCE_LIMIT = (POINT_LIMIT + 1) * 210;
    static final long ANSWER_BYTE_LIMIT = 8 * 1024 * 1024;
    private static final Set<String> PLATFORMS = Set.of("DOUBAO", "DEEPSEEK", "KIMI", "QWEN", "WENXIN", "YUANBAO", "ZHIPU");
    private final GeoMapper topics;
    private final GeoTrendMapper mapper;
    private final ObjectMapper json;

    public GeoTrendService(GeoMapper topics, GeoTrendMapper mapper, ObjectMapper json) {
        this.topics = topics;
        this.mapper = mapper;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public GeoTrendDtos.Response trend(AuthenticatedAccount account, long topicId, int days, String questionScope) {
        return trend(account, topicId, days, questionScope, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    GeoTrendDtos.Response trendAtRun(AuthenticatedAccount account, long topicId, int days, String scope,
                                    long anchorRunId) {
        return trend(account, topicId, days, scope, OffsetDateTime.now(ZoneOffset.UTC), anchorRunId);
    }

    GeoTrendDtos.Response trend(AuthenticatedAccount account, long topicId, int days, String scope, OffsetDateTime to) {
        return trend(account, topicId, days, scope, to, null);
    }

    private GeoTrendDtos.Response trend(AuthenticatedAccount account, long topicId, int days, String scope,
                                        OffsetDateTime to, Long anchorRunId) {
        long userId = CatalogAccess.requireUser(account);
        if (!Set.of(7, 30, 90).contains(days) || !Set.of("DISCOVERY", "BRAND_VALIDATION").contains(scope)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        topics.findTopic(topicId, userId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        GeoTrendMapper.Batch anchor = anchorRunId == null ? null : mapper.anchorBatch(userId, topicId, anchorRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (anchor != null) to = anchor.observedAt();
        OffsetDateTime from = to.minusDays(days);
        List<GeoTrendMapper.Batch> first = anchor == null
                ? mapper.firstBatches(userId, topicId, BASELINE_LIMIT + 1)
                : mapper.firstBatchesThrough(userId, topicId, anchor.observedAt(), anchor.runId(), BASELINE_LIMIT + 1);
        List<GeoTrendMapper.Batch> recent = anchor == null
                ? mapper.recentBatches(userId, topicId, from, to, POINT_LIMIT + 1)
                : mapper.recentBatchesThrough(userId, topicId, from, anchor.observedAt(), anchor.runId(), POINT_LIMIT + 1);
        boolean truncated = first.size() > BASELINE_LIMIT || recent.size() > POINT_LIMIT;
        List<GeoTrendMapper.Batch> ordered = new ArrayList<>(recent.stream().limit(POINT_LIMIT).toList());
        ordered.sort(Comparator.comparing(GeoTrendMapper.Batch::observedAt).thenComparingLong(GeoTrendMapper.Batch::runId));
        List<GeoTrendMapper.Batch> preceding = ordered.isEmpty() ? List.of()
                : mapper.precedingBatch(userId, topicId, ordered.getFirst().observedAt(), ordered.getFirst().runId());
        List<GeoTrendMapper.Batch> loaded = new ArrayList<>(preceding);
        loaded.addAll(ordered);
        Set<Long> displayedRunIds = new LinkedHashSet<>(ordered.stream().map(GeoTrendMapper.Batch::runId).toList());
        Map<Long, List<GeoTrendMapper.Evidence>> evidence = new LinkedHashMap<>();
        if (!loaded.isEmpty()) {
            List<GeoTrendMapper.Evidence> rows = mapper.evidence(userId,
                    loaded.stream().map(GeoTrendMapper.Batch::runId).toList(), ANSWER_BYTE_LIMIT, EVIDENCE_LIMIT + 1);
            truncated |= rows.size() > EVIDENCE_LIMIT || rows.stream().anyMatch(GeoTrendMapper.Evidence::evidenceTruncated);
            for (var row : rows.stream().limit(EVIDENCE_LIMIT).toList()) {
                evidence.computeIfAbsent(row.runId(), unused -> new ArrayList<>()).add(row);
            }
        }
        Map<String, State> baselines = new LinkedHashMap<>();
        List<State> earliestStates = first.stream().limit(BASELINE_LIMIT).map(batch -> state(batch, scope, List.of())).toList();
        for (State state : earliestStates) {
            if (state.target.unavailableReason() == null && state.questionsValid && !state.questions.isEmpty()) {
                for (String platform : state.platforms) baselines.putIfAbsent(platform, state);
            }
        }
        List<State> states = loaded.stream().map(batch -> state(batch, scope,
                evidence.getOrDefault(batch.runId(), List.of()))).toList();
        Map<String, Long> firstSeen = new LinkedHashMap<>();
        List<State> knownHistory = new ArrayList<>(earliestStates);
        knownHistory.addAll(states);
        knownHistory.sort(Comparator.comparing((State state) -> state.batch.observedAt()).thenComparingLong(state -> state.batch.runId()));
        for (State state : knownHistory) {
            for (String platform : state.platforms) for (Question question : state.questions.values()) {
                firstSeen.putIfAbsent(platform + ":" + question.key(), state.batch.runId());
            }
        }
        Set<Long> baselineScanIds = new LinkedHashSet<>(earliestStates.stream().map(state -> state.batch.runId()).toList());
        Set<String> platforms = new LinkedHashSet<>(baselines.keySet());
        states.forEach(state -> platforms.addAll(state.platforms));
        List<GeoTrendDtos.Platform> result = new ArrayList<>();
        for (String platform : platforms) {
            State baseline = baselines.get(platform);
            List<GeoTrendDtos.Point> points = new ArrayList<>();
            State previous = null;
            for (State state : states) {
                if (displayedRunIds.contains(state.batch.runId())) points.add(point(platform, baseline, previous, state,
                        firstSeen, first.size() <= BASELINE_LIMIT || baselineScanIds.contains(state.batch.runId())));
                previous = state;
            }
            result.add(new GeoTrendDtos.Platform(platform, baseline == null ? null : reportId(baseline),
                    baseline == null ? null : baseline.batch.observedAt(),
                    baseline == null ? 0 : baseline.questions.size(),
                    baseline == null ? 0 : baseline.questions.size() * baseline.rounds, List.copyOf(points)));
        }
        List<GeoTrendDtos.History> history = new ArrayList<>();
        State previous = null;
        for (State state : states) {
            if (displayedRunIds.contains(state.batch.runId())) history.add(history(previous, state));
            previous = state;
        }
        return new GeoTrendDtos.Response(Long.toString(topicId), days, scope, GeoVisibilityProjection.METHOD,
                from, to, truncated, POINT_LIMIT, BASELINE_LIMIT, List.copyOf(result), List.copyOf(history));
    }

    private record Question(String id, String text, String version) {
        String key() { return id + ":" + version; }
    }
    record PeriodProjection(String baselineKind, List<GeoPeriodReportDtos.Platform> platforms,
                            boolean evidenceTruncated) { }

    /** Reuses exactly the ordinary trend's frozen identity, sampling and paired-cell rules. */
    PeriodProjection period(long userId, long projectId, GeoTrendMapper.Batch before,
                            List<GeoTrendMapper.Batch> selected) {
        if (selected.isEmpty()) return new PeriodProjection("NONE", List.of(), false);
        List<GeoTrendMapper.Batch> batches = new ArrayList<>();
        if (before != null) batches.add(before);
        batches.addAll(selected);
        if (batches.size() > POINT_LIMIT + 1 || batches.stream().anyMatch(batch -> batch.spaceId() != projectId))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        List<GeoTrendMapper.Evidence> rows = mapper.evidence(userId,
                batches.stream().map(GeoTrendMapper.Batch::runId).toList(), ANSWER_BYTE_LIMIT, EVIDENCE_LIMIT + 1);
        boolean truncated = rows.size() > EVIDENCE_LIMIT || rows.stream().anyMatch(GeoTrendMapper.Evidence::evidenceTruncated);
        Map<Long, List<GeoTrendMapper.Evidence>> evidence = new LinkedHashMap<>();
        for (var row : rows.stream().limit(EVIDENCE_LIMIT).toList())
            evidence.computeIfAbsent(row.runId(), unused -> new ArrayList<>()).add(row);
        List<State> states = batches.stream().map(batch -> state(batch, "DISCOVERY",
                evidence.getOrDefault(batch.runId(), List.of()))).toList();
        State baseline = before == null ? states.stream().filter(value -> value.questionsValid
                && value.target.unavailableReason() == null && !valid(cellsForAll(value)).isEmpty()).findFirst().orElse(null)
                : states.getFirst();
        String kind = baseline == null ? "NONE" : before == null ? "PERIOD_FIRST" : "PRE_PERIOD";
        Set<String> platforms = new LinkedHashSet<>();
        states.forEach(value -> platforms.addAll(value.platforms));
        List<GeoPeriodReportDtos.Platform> result = new ArrayList<>();
        for (String platform : platforms) {
            List<GeoPeriodReportDtos.Point> points = new ArrayList<>();
            State previous = before == null ? null : states.getFirst();
            for (int index = before == null ? 0 : 1; index < states.size(); index++) {
                State value = states.get(index);
                var item = point(platform, baseline, previous, value, Map.of(), false);
                points.add(new GeoPeriodReportDtos.Point(item.reportId(), item.runId(), item.observedAt(),
                        item.mentionRate(), item.validSamples(), item.failedSamples(), item.missingSamples(),
                        item.comparisonStatus(), item.comparisonReason(), item.connectToPrevious()));
                previous = value;
            }
            State end = states.getLast();
            var endPoint = point(platform, baseline, baseline, end, Map.of(), false);
            GeoTrendDtos.Comparison comparison = baseline == null || baseline.batch.runId() == end.batch.runId()
                    ? null : endPoint.comparison();
            result.add(new GeoPeriodReportDtos.Platform(platform,
                    baseline == null ? null : reportId(baseline), baseline == null ? null : baseline.batch.observedAt(),
                    reportId(end), end.batch.observedAt(), comparison == null ? null : comparison.previousMentionRate(),
                    comparison == null ? null : comparison.currentMentionRate(),
                    comparison == null ? null : comparison.deltaPercentagePoints(),
                    comparison == null ? 0 : comparison.commonSamples(), comparison == null ? "NOT_COMPARABLE" : "COMPARABLE",
                    comparison == null ? baseline == null ? "BASELINE_UNAVAILABLE"
                            : baseline.batch.runId() == end.batch.runId() ? "BASELINE_ONLY" : endPoint.comparisonReason() : null,
                    List.copyOf(points)));
        }
        return new PeriodProjection(kind, List.copyOf(result), truncated);
    }

    private static List<Cell> cellsForAll(State state) { return new ArrayList<>(state.cells.values()); }
    private record Cell(String key, String unitKey, Question question, int round, boolean valid,
                        boolean failed, Boolean mentioned, String provider, String model, String method,
                        boolean truncated) { }
    private record State(GeoTrendMapper.Batch batch, GeoVisibilityProjection.Target target, String identity,
                         String region, String metric, int rounds, boolean questionsValid,
                         Map<String, Question> questions, Set<String> platforms, Map<String, Cell> cells) { }

    private State state(GeoTrendMapper.Batch batch, String scope, List<GeoTrendMapper.Evidence> rows) {
        JsonNode snapshot;
        try { snapshot = json.readTree(batch.contextSnapshotJson()); }
        catch (Exception ignored) { snapshot = json.createObjectNode(); }
        if (snapshot == null) snapshot = json.createObjectNode();
        var run = new GeoTaskRecords.Run(batch.runId(), 0, 0, batch.spaceId(), batch.productId(), 0,
                "PARTIAL", "STANDARD", 0, 0, 0, 0, "{}", batch.metricVersion(), null,
                null, null, batch.observedAt(), batch.observedAt(), 0, 0, 0);
        var target = GeoVisibilityProjection.target(run, snapshot);
        String identity = target.value() == null ? null : digest(target.value().kind() + "\n" + target.value().id()
                + "\n" + target.value().name() + "\n" + target.value().aliases().stream().sorted().toList()
                + "\n" + target.terms().stream().sorted().toList());
        String mode = snapshot.path("parameters").path("sampleMode").asText();
        int rounds = "DEEP".equals(mode) ? 3 : "STANDARD".equals(mode) ? 1 : 0;
        String region = text(snapshot.path("parameters").path("region"));
        Set<String> platforms = new LinkedHashSet<>();
        for (JsonNode node : snapshot.path("parameters").path("platformCodes")) {
            if (PLATFORMS.contains(node.asText())) platforms.add(node.asText());
        }
        List<String> promptTerms = new ArrayList<>(target.terms());
        var brandRun = new GeoTaskRecords.Run(batch.runId(), 0, 0, batch.spaceId(), null, 0,
                "PARTIAL", "STANDARD", 0, 0, 0, 0, "{}", batch.metricVersion(), null,
                null, null, batch.observedAt(), batch.observedAt(), 0, 0, 0);
        promptTerms.addAll(GeoVisibilityProjection.target(brandRun, snapshot).terms());
        var promptTarget = new GeoVisibilityProjection.Target(target.value(), List.copyOf(promptTerms), target.unavailableReason());
        Map<String, Question> questions = new LinkedHashMap<>();
        JsonNode frozenQuestions = snapshot.path("authorizedContext").path("questions");
        boolean valid = frozenQuestions.isArray() && !frozenQuestions.isEmpty() && frozenQuestions.size() <= 10 && rounds > 0;
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode node : frozenQuestions) {
            String id = text(node.path("id"));
            String questionText = text(node.path("text"));
            String category = text(node.path("category"));
            if (id == null || !id.matches("[1-9][0-9]{0,18}") || questionText == null
                    || category == null || !ids.add(id)) { valid = false; continue; }
            Boolean validation = GeoVisibilityProjection.mentioned(promptTarget, "SUCCEEDED", questionText);
            if (validation == null || ("BRAND_VALIDATION".equals(scope) != validation)) continue;
            Question question = new Question(id, questionText, digest(category + "\n" + questionText));
            questions.put(id, question);
        }
        if (!valid) questions.clear();
        Map<String, Cell> cells = new LinkedHashMap<>();
        for (var row : rows) {
            Question question = questions.get(Long.toString(row.questionId()));
            if (question == null || !question.text.equals(row.questionText()) || !platforms.contains(row.platformCode())
                    || row.sampleNo() < 1 || row.sampleNo() > rounds
                    || !row.unitKey().equals(question.id + ":" + row.platformCode() + ":" + row.sampleNo())) continue;
            boolean success = "SUCCEEDED".equals(row.status()) && row.hasAnswer();
            cells.putIfAbsent(row.unitKey(), new Cell(question.key() + ":" + row.sampleNo(), row.unitKey(), question,
                    row.sampleNo(), success, "FAILED".equals(row.status()),
                    GeoVisibilityProjection.mentioned(target, row.status(), row.answerText()), row.provider(),
                    row.model(), row.collectionMethod(), row.evidenceTruncated()));
        }
        return new State(batch, target, identity, region, batch.metricVersion(), rounds, valid,
                questions, platforms, cells);
    }

    private GeoTrendDtos.Point point(String platform, State baseline, State previous, State current,
                                     Map<String, Long> firstSeen, boolean historyComplete) {
        List<Cell> all = cells(current, platform);
        Set<String> baselineQuestions = baseline == null ? Set.of() : questionKeys(baseline);
        List<Cell> fixed = all.stream().filter(cell -> baselineQuestions.contains(cell.question.key())
                && cell.round <= baseline.rounds).toList();
        boolean baselineIdentity = baseline != null && Objects.equals(baseline.identity, current.identity);
        int expected = current.platforms.contains(platform) ? current.questions.size() * current.rounds : 0;
        int valid = valid(all).size();
        int fixedValid = valid(fixed).size();
        int failed = (int) all.stream().filter(Cell::failed).count();
        Integer mentions = mentions(all, current.target.unavailableReason() == null);
        Integer fixedMentions = mentions(fixed, baselineIdentity);
        String reason = reason(platform, baseline, previous, current);
        List<Cell> oldFixed = previous == null ? List.of() : cells(previous, platform).stream()
                .filter(cell -> baselineQuestions.contains(cell.question.key()) && cell.round <= baseline.rounds).toList();
        Map<String, Cell> oldFixedValid = byKey(valid(oldFixed));
        Map<String, Cell> newFixedValid = byKey(valid(fixed));
        // Pairwise change uses the adjacent runs' shared frozen question versions.
        // An earlier edit to the topic must not make all later adjacent runs incomparable.
        Map<String, Cell> oldValid = previous == null ? Map.of() : byKey(valid(cells(previous, platform)));
        Map<String, Cell> newValid = byKey(valid(all));
        List<String> common = newValid.keySet().stream().filter(oldValid::containsKey).toList();
        if (reason == null) reason = methodReason(oldValid, newValid, common);
        if (reason == null && common.isEmpty() && previous != null) reason = "NO_COMMON_VALID_SAMPLES";
        GeoTrendDtos.Comparison comparison = null;
        boolean connect = false;
        if (reason == null && previous != null) {
            int before = (int) common.stream().filter(key -> Boolean.TRUE.equals(oldValid.get(key).mentioned)).count();
            int after = (int) common.stream().filter(key -> Boolean.TRUE.equals(newValid.get(key).mentioned)).count();
            int commonQuestions = (int) common.stream().map(key -> newValid.get(key).question.key()).distinct().count();
            double beforeRate = rate(before, common.size());
            double afterRate = rate(after, common.size());
            comparison = new GeoTrendDtos.Comparison(reportId(previous), reportId(current), commonQuestions,
                    common.size(), before, after, beforeRate, afterRate, round(afterRate - beforeRate));
            connect = !oldFixedValid.isEmpty() && oldFixedValid.keySet().equals(newFixedValid.keySet())
                    && baselineIdentity
                    && Objects.equals(baseline.identity, previous.identity);
        }
        List<GeoTrendDtos.QuestionChange> changes = questionChanges(platform, baselineQuestions,
                baseline == null ? 0 : baseline.rounds, previous, current,
                contextReason(platform, previous, current), firstSeen, historyComplete);
        int baseQuestions = (int) current.questions.values().stream().filter(question -> baselineQuestions.contains(question.key())).count();
        return new GeoTrendDtos.Point(reportId(current), Long.toString(current.batch.runId()), current.batch.observedAt(),
                current.target.value(), current.region, current.questions.size(), baseQuestions,
                current.questions.size() - baseQuestions, expected, valid, mentions, nullableRate(mentions, valid),
                baseline == null ? 0 : baseline.questions.size() * baseline.rounds, fixedValid, fixedMentions,
                nullableRate(fixedMentions, fixedValid), failed, Math.max(0, expected - valid - failed),
                all.stream().anyMatch(Cell::truncated), previous == null ? "FIRST_OBSERVATION"
                : comparison != null ? "COMPARABLE" : "NOT_COMPARABLE", reason,
                "PLATFORM_NOT_SAMPLED".equals(reason) ? missingPeriod(platform, previous, current) : null, connect,
                comparison, changes);
    }

    private String reason(String platform, State baseline, State previous, State current) {
        String reason = contextReason(platform, previous, current);
        if (reason != null) return reason;
        if (baseline == null) return "BASELINE_UNAVAILABLE";
        if (!Objects.equals(current.identity, baseline.identity)) return "TARGET_CHANGED";
        if (cells(current, platform).stream().anyMatch(Cell::truncated)
                || (previous != null && cells(previous, platform).stream().anyMatch(Cell::truncated))) return "EVIDENCE_LIMIT";
        return null;
    }

    private String contextReason(String platform, State previous, State current) {
        if (current.target.unavailableReason() != null) return current.target.unavailableReason();
        if (!current.questionsValid || (previous != null && !previous.questionsValid)) return "MISSING_QUESTION_METADATA";
        if (previous == null) return null;
        if (!current.platforms.contains(platform) || !previous.platforms.contains(platform)) return "PLATFORM_NOT_SAMPLED";
        if (previous.target.unavailableReason() != null) return previous.target.unavailableReason();
        if (!Objects.equals(current.identity, previous.identity)) return "TARGET_CHANGED";
        if (current.region == null || previous.region == null || current.metric == null || previous.metric == null) return "MISSING_SAMPLING_METADATA";
        if (!current.region.equals(previous.region)) return "REGION_CHANGED";
        if (!current.metric.equals(previous.metric) || current.rounds != previous.rounds) return "SAMPLING_CHANGED";
        return null;
    }

    private String missingPeriod(String platform, State previous, State current) {
        if (previous == null) return null;
        boolean before = previous.platforms.contains(platform);
        boolean after = current.platforms.contains(platform);
        return !before && !after ? "BOTH" : !before ? "PREVIOUS" : !after ? "CURRENT" : null;
    }

    private String methodReason(Map<String, Cell> previous, Map<String, Cell> current, List<String> common) {
        for (String key : common) {
            Cell before = previous.get(key);
            Cell after = current.get(key);
            if (blank(before.provider) || blank(after.provider) || blank(before.model) || blank(after.model)
                    || blank(before.method) || blank(after.method)) return "MISSING_SAMPLING_METADATA";
            if (!before.provider.equals(after.provider) || !before.model.equals(after.model)
                    || !before.method.equals(after.method)) return "SAMPLING_CHANGED";
        }
        return null;
    }

    private List<GeoTrendDtos.QuestionChange> questionChanges(String platform, Set<String> baselineQuestions, int baselineRounds,
                                                            State previous, State current, String globalReason,
                                                            Map<String, Long> firstSeen, boolean historyComplete) {
        Map<String, Question> questions = new LinkedHashMap<>(current.questions);
        if (previous != null) previous.questions.forEach(questions::putIfAbsent);
        List<GeoTrendDtos.QuestionChange> result = new ArrayList<>();
        for (Question question : questions.values()) {
            Question oldQuestion = previous == null ? null : previous.questions.get(question.id);
            Question newQuestion = current.questions.get(question.id);
            List<Cell> oldCells = previous == null ? List.of() : valid(cells(previous, platform)).stream()
                    .filter(cell -> cell.question.id.equals(question.id)).toList();
            List<Cell> newCells = valid(cells(current, platform)).stream()
                    .filter(cell -> cell.question.id.equals(question.id)).toList();
            String reason = globalReason;
            String change = "NOT_COMPARABLE";
            boolean inBaseline = baselineQuestions.contains(question.key());
            if (inBaseline) {
                oldCells = oldCells.stream().filter(cell -> cell.round <= baselineRounds).toList();
                newCells = newCells.stream().filter(cell -> cell.round <= baselineRounds).toList();
            }
            if (oldCells.stream().anyMatch(Cell::truncated) || newCells.stream().anyMatch(Cell::truncated)) reason = "EVIDENCE_LIMIT";
            boolean absentBefore = oldQuestion == null && newQuestion != null;
            boolean first = absentBefore && historyComplete
                    && Objects.equals(firstSeen.get(platform + ":" + question.key()), current.batch.runId());
            if (first) { change = "NEW_EXPLORATION"; reason = null; }
            else if (newQuestion == null || oldQuestion == null || !oldQuestion.key().equals(newQuestion.key())) reason = "QUESTION_CHANGED";
            if (absentBefore && !first && !historyComplete
                    && Objects.equals(firstSeen.get(platform + ":" + question.key()), current.batch.runId())) reason = "MISSING_QUESTION_METADATA";
            Map<String, Cell> before = byKey(oldCells);
            Map<String, Cell> after = byKey(newCells);
            List<String> common = after.keySet().stream().filter(before::containsKey).toList();
            if (!first && reason == null) reason = methodReason(before, after, common);
            if (!first && reason == null && common.isEmpty()) reason = "NO_COMMON_VALID_SAMPLES";
            if (!first && reason == null) {
                oldCells = common.stream().map(before::get).toList();
                newCells = common.stream().map(after::get).toList();
                boolean oldMention = oldCells.stream().anyMatch(cell -> Boolean.TRUE.equals(cell.mentioned));
                boolean newMention = newCells.stream().anyMatch(cell -> Boolean.TRUE.equals(cell.mentioned));
                change = newMention ? oldMention ? "CONTINUED_MENTION" : "NEWLY_MENTIONED"
                        : oldMention ? "NO_LONGER_MENTIONED" : "NEVER_MENTIONED";
            }
            result.add(new GeoTrendDtos.QuestionChange(question.id, question.version, question.text,
                    inBaseline, change, reason,
                    "PLATFORM_NOT_SAMPLED".equals(reason) ? missingPeriod(platform, previous, current) : null,
                    previous == null ? null : reportId(previous), reportId(current),
                    mentions(oldCells, previous != null && previous.target.unavailableReason() == null), oldCells.size(),
                    mentions(newCells, current.target.unavailableReason() == null), newCells.size(),
                    oldCells.stream().map(Cell::unitKey).toList(), newCells.stream().map(Cell::unitKey).toList()));
        }
        return List.copyOf(result);
    }

    private GeoTrendDtos.History history(State previous, State current) {
        int added = 0, removed = 0, changed = 0;
        if (previous != null) {
            for (Question question : current.questions.values()) {
                Question before = previous.questions.get(question.id);
                if (before == null) added++;
                else if (!before.key().equals(question.key())) changed++;
            }
            for (String id : previous.questions.keySet()) if (!current.questions.containsKey(id)) removed++;
        }
        String status = !current.questionsValid || (previous != null && !previous.questionsValid) ? "UNAVAILABLE"
                : previous == null ? "FIRST_OBSERVATION" : added + removed + changed == 0 ? "UNCHANGED" : "CHANGED";
        return new GeoTrendDtos.History(reportId(current), Long.toString(current.batch.runId()), current.batch.observedAt(),
                current.questions.size(), added, removed, changed, status);
    }

    private static List<Cell> cells(State state, String platform) {
        return state.cells.values().stream().filter(cell -> cell.unitKey.contains(":" + platform + ":")).toList();
    }
    private static List<Cell> valid(List<Cell> cells) { return cells.stream().filter(Cell::valid).toList(); }
    private static Map<String, Cell> byKey(List<Cell> cells) {
        Map<String, Cell> result = new LinkedHashMap<>();
        cells.forEach(cell -> result.put(cell.key, cell));
        return result;
    }
    private static Set<String> questionKeys(State state) {
        Set<String> result = new LinkedHashSet<>();
        state.questions.values().forEach(question -> result.add(question.key()));
        return result;
    }
    private static Integer mentions(List<Cell> cells, boolean assessable) {
        List<Cell> valid = valid(cells);
        return !assessable || valid.isEmpty() || valid.stream().anyMatch(cell -> cell.mentioned == null) ? null
                : (int) valid.stream().filter(cell -> Boolean.TRUE.equals(cell.mentioned)).count();
    }
    private static Double nullableRate(Integer mentions, int valid) { return mentions == null || valid == 0 ? null : rate(mentions, valid); }
    private static double rate(int mentions, int valid) { return round(100.0 * mentions / valid); }
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private static String reportId(State state) { return state.batch.reportId() == null ? null : Long.toString(state.batch.reportId()); }
    private static String text(JsonNode node) { return node.isTextual() && !node.asText().isBlank() ? node.asText() : null; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
