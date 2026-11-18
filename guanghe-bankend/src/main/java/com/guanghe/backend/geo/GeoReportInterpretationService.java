package com.guanghe.backend.geo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskControlMapper;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recovery of a downgraded interpretation, never another paid collection task. */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class GeoReportInterpretationService {
    private static final String FALLBACK = "geo-deterministic-v1";
    private static final String MODEL = "geo-synthesis-v1";
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "PARTIAL");
    private static final Set<String> FAILURES = Set.of("GEO_INTERPRETATION_TIMEOUT",
            "GEO_INTERPRETATION_PROVIDER_UNAVAILABLE", "GEO_INTERPRETATION_INVALID_RESULT",
            "GEO_INTERPRETATION_INPUT_CHANGED", "GEO_INTERPRETATION_REPORT_UNAVAILABLE",
            "GEO_INTERPRETATION_LEASE_EXPIRED");
    private final GeoTaskMapper reports;
    private final GeoInterpretationMapper work;
    private final TaskControlMapper tasks;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final PaidTaskProperties properties;
    private final boolean clientAvailable;
    private final Validator validator;
    private final ObservabilitySupport observability;
    private final Clock clock;
    private ObjectProvider<GeoCompetitiveService> competitive;

    @Autowired(required = false)
    void setCompetitive(ObjectProvider<GeoCompetitiveService> provider) { competitive = provider; }

    @Autowired
    public GeoReportInterpretationService(ObjectProvider<GeoTaskMapper> reports,
            ObjectProvider<GeoInterpretationMapper> work, ObjectProvider<TaskControlMapper> tasks,
            SnowflakeIdGenerator ids, ObjectMapper json, PaidTaskProperties properties,
            ObjectProvider<PythonInternalClient> python, Validator validator, ObservabilitySupport observability) {
        this(reports.getIfAvailable(), work.getIfAvailable(), tasks.getIfAvailable(), ids, json,
                properties, python.getIfAvailable() != null, validator, observability, Clock.systemUTC());
    }

    GeoReportInterpretationService(GeoTaskMapper reports, GeoInterpretationMapper work, TaskControlMapper tasks,
            SnowflakeIdGenerator ids, ObjectMapper json, PaidTaskProperties properties, boolean clientAvailable,
            Validator validator, ObservabilitySupport observability, Clock clock) {
        this.reports = reports; this.work = work; this.tasks = tasks; this.ids = ids; this.json = json;
        this.properties = properties; this.clientAvailable = clientAvailable; this.validator = validator;
        this.observability = observability; this.clock = clock;
    }

    @Transactional
    public GeoTaskDtos.ReportInterpretation request(AuthenticatedAccount account, long reportId) {
        long userId = CatalogAccess.requireUser(account);
        if (reports == null || work == null || tasks == null) throw unavailable();
        reports.lockOwner(userId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        reports.lockReport(reportId, userId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        GeoTaskRecords.Report report = requireReport(reportId, userId);
        GeoTaskRecords.Run run = requireRun(report, userId);
        List<GeoTaskRecords.Observation> observations = reports.findObservationPage(run.id(), 0, 210);
        String reason = eligibility(report, run, observations);
        if (reason != null) throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "当前报告暂不能重新生成解读", Map.of("reason", reason));
        GeoInterpretationRecords.Work existing = work.lock(reportId, userId).orElse(null);
        if (MODEL.equals(report.methodVersion()) || active(existing)) return projection(report, run, observations, existing);
        if (!work.executionEligible(userId, report.taskId())) throw conflict();
        if (!available()) throw unavailable();
        if (existing != null && existing.attempts() >= 2 && !capacityOnlyFailure(existing)) throw new BusinessException(ErrorCode.RATE_LIMITED,
                "该报告的解读恢复次数已用完，原监测结果仍可查看", Map.of("reason", "ATTEMPT_LIMIT_REACHED"));
        if (capacityOnlyFailure(existing) && existing.availableAt().isAfter(now()))
            throw new BusinessException(ErrorCode.RATE_LIMITED,"解读服务繁忙，请稍后重试",Map.of("reason","CAPACITY_COOLDOWN"));
        if (work.countActive(userId) > 0) throw new BusinessException(ErrorCode.RATE_LIMITED,
                "已有报告正在生成解读，请完成后再试", Map.of("reason", "USER_INTERPRETATION_ACTIVE"));
        if (competitive != null && competitive.getIfAvailable() != null && competitive.getObject().hasActive(userId))
            throw new BusinessException(ErrorCode.RATE_LIMITED, "已有报告正在分析竞品，请完成后再试", Map.of("reason", "USER_ANALYSIS_ACTIVE"));
        ObjectNode evidence = evidence(run, observations);
        String hash = hash(evidence);
        if (existing != null && !existing.inputHash().equals(hash)) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        OffsetDateTime now = now();
        long jobId = existing == null ? ids.nextId() : existing.id();
        int changed = existing == null
                ? work.enqueue(jobId, reportId, userId, hash, observability.currentTraceparent(), now)
                : work.requeue(jobId, observability.currentTraceparent(), now);
        if (changed != 1) throw conflict();
        event(report, jobId, existing == null ? 1 : existing.attempts() + (capacityOnlyFailure(existing) ? 0 : 1),
                "GEO_INTERPRETATION_REQUESTED", "USER", null, now);
        return projection(report, run, observations, work.find(reportId, userId).orElseThrow());
    }

    /** Called by report GET: no admission, recovery, or provider call can originate here. */
    public GeoTaskDtos.ReportInterpretation status(GeoTaskRecords.Report report, GeoTaskRecords.Run run,
                                                   List<GeoTaskRecords.Observation> observations) {
        return projection(report, run, observations,
                work == null ? null : work.find(report.id(), report.userId()).orElse(null));
    }

    @Transactional
    public Optional<GeoInterpretationRecords.Claim> claimNext() {
        if (!available()) return Optional.empty();
        GeoInterpretationRecords.Work next = work.lockNext(now()).orElse(null);
        if (next == null) return Optional.empty();
        OffsetDateTime now = now();
        GeoTaskRecords.Report report = reports.findReport(next.reportId(), next.userId()).orElse(null);
        GeoTaskRecords.Run run = report == null ? null : reports.findRun(report.runId(), next.userId()).orElse(null);
        List<GeoTaskRecords.Observation> observations = run == null ? List.of()
                : reports.findObservationPage(run.id(), 0, 210);
        if (report == null || run == null || eligibility(report, run, observations) != null
                || !FALLBACK.equals(report.methodVersion()) || !work.executionEligible(next.userId(), report.taskId())) {
            work.rejectOrExpire(next.id(), "GEO_INTERPRETATION_REPORT_UNAVAILABLE", now);
            return Optional.empty();
        }
        ObjectNode request;
        try { request = evidence(run, observations); }
        catch (BusinessException invalidInput) {
            work.rejectOrExpire(next.id(), "GEO_INTERPRETATION_INPUT_CHANGED", now);
            return Optional.empty();
        }
        if (!next.inputHash().equals(hash(request))) {
            work.rejectOrExpire(next.id(), "GEO_INTERPRETATION_INPUT_CHANGED", now);
            return Optional.empty();
        }
        UUID token = UUID.randomUUID();
        OffsetDateTime deadline = now.plusSeconds(60);
        if (work.claim(next.id(), token, now.plusSeconds(90), deadline, now) != 1) return Optional.empty();
        request.put("jobId", Long.toString(next.id()));
        request.put("attempt", next.attempts());
        request.put("inputHash", next.inputHash());
        request.put("deadlineAt", deadline.toString());
        request.put("maxProviderCalls", 2);
        GeoInterpretationRecords.Work claimed = work.find(next.reportId(), next.userId()).orElseThrow();
        event(report, claimed.id(), claimed.attempts(), "GEO_INTERPRETATION_STARTED", "JAVA", null, now);
        return Optional.of(new GeoInterpretationRecords.Claim(claimed, request, report.taskId(), run.id()));
    }

    @Transactional
    public void expireOne() {
        if (work == null) return;
        OffsetDateTime now = now();
        GeoInterpretationRecords.Work expired = work.lockExpired(now).orElse(null);
        if (expired == null) return;
        if (work.rejectOrExpire(expired.id(), "GEO_INTERPRETATION_LEASE_EXPIRED", now) != 1) throw conflict();
        reports.findReport(expired.reportId(), expired.userId()).ifPresent(report -> event(report,
                expired.id(), expired.attempts(), "GEO_INTERPRETATION_FAILED", "JAVA",
                "GEO_INTERPRETATION_LEASE_EXPIRED", now));
    }

    @Transactional
    public void capacityRejected(GeoInterpretationRecords.Claim claim) {
        var w=claim.work();
        if (work.capacityRejected(w.id(),w.attempts(),w.claimToken(),now())!=1) return;
        var state=work.find(w.reportId(),w.userId()).orElseThrow();
        reports.findReport(w.reportId(),w.userId()).ifPresent(report -> event(report,w.id(),w.attempts(),
                "FAILED".equals(state.status()) ? "GEO_INTERPRETATION_FAILED" : "GEO_INTERPRETATION_CAPACITY_WAIT",
                "JAVA","GEO_INTERPRETATION_CAPACITY_FULL",now()));
    }

    @Transactional
    public void failed(GeoInterpretationRecords.Claim claim, String code) {
        String safe = FAILURES.contains(code) ? code : "GEO_INTERPRETATION_PROVIDER_UNAVAILABLE";
        GeoInterpretationRecords.Work w = claim.work();
        OffsetDateTime now = now();
        if (work.finish(w.id(), w.attempts(), w.claimToken(), "FAILED", safe, null, now) != 1) return;
        reports.findReport(w.reportId(), w.userId()).ifPresent(report ->
                event(report, w.id(), w.attempts(), "GEO_INTERPRETATION_FAILED", "JAVA", safe, now));
    }

    @Transactional
    public String completed(GeoInterpretationRecords.Claim claim, JsonNode response) {
        GeoInterpretationRecords.Work expected = claim.work();
        // All paths that acquire both locks use report -> work to avoid a retry/completion deadlock.
        if (reports.lockReport(expected.reportId(), expected.userId()).isEmpty()) return "GEO_INTERPRETATION_REPORT_UNAVAILABLE";
        GeoInterpretationRecords.Work current = work.lock(expected.reportId(), expected.userId()).orElse(null);
        OffsetDateTime now = now();
        if (!ownsLease(current, expected, now)) return "GEO_INTERPRETATION_LEASE_EXPIRED";
        GeoTaskRecords.Report report = requireReport(expected.reportId(), expected.userId());
        GeoTaskRecords.Run run = requireRun(report, expected.userId());
        List<GeoTaskRecords.Observation> observations = reports.findObservationPage(run.id(), 0, 210);
        String error = null;
        if (eligibility(report, run, observations) != null || !FALLBACK.equals(report.methodVersion())
                || !work.executionEligible(expected.userId(), report.taskId()))
            error = "GEO_INTERPRETATION_REPORT_UNAVAILABLE";
        else if (!expected.inputHash().equals(hash(evidence(run, observations))))
            error = "GEO_INTERPRETATION_INPUT_CHANGED";
        ValidResult result = error == null ? validResult(expected, response) : null;
        if (error == null && result == null) error = "GEO_INTERPRETATION_INVALID_RESULT";
        if (error != null) {
            if (work.finish(current.id(), current.attempts(), current.claimToken(), "FAILED", error, null, now) != 1)
                throw conflict();
            event(report, current.id(), current.attempts(), "GEO_INTERPRETATION_FAILED", "JAVA", error, now);
            return error;
        }
        if (work.publish(report.id(), report.userId(), result.synthesisJson(), result.usageJson(),
                result.synthesis().summary().trim()) != 1
                || work.finish(current.id(), current.attempts(), current.claimToken(), "SUCCEEDED", null,
                result.usageJson(), now) != 1) throw conflict();
        event(report, current.id(), current.attempts(), "GEO_INTERPRETATION_SUCCEEDED", "JAVA", null, now);
        if (competitive != null) competitive.ifAvailable(value -> value.ingest(report, run, observations, result.synthesis()));
        return null;
    }

    private ValidResult validResult(GeoInterpretationRecords.Work expected, JsonNode response) {
        if (response == null || !response.isObject() || response.size() != 5
                || !Long.toString(expected.id()).equals(response.path("jobId").textValue())
                || !response.path("attempt").isIntegralNumber() || response.path("attempt").intValue() != expected.attempts()
                || !expected.inputHash().equals(response.path("inputHash").textValue())) return null;
        try {
            GeoTaskDtos.SynthesisInput synthesis = json.treeToValue(response.path("synthesis"), GeoTaskDtos.SynthesisInput.class);
            GeoTaskDtos.SynthesisUsageInput usage = json.treeToValue(response.path("synthesisUsage"), GeoTaskDtos.SynthesisUsageInput.class);
            if (synthesis == null || usage == null || !validator.validate(synthesis).isEmpty() || !validator.validate(usage).isEmpty()
                    || !MODEL.equals(synthesis.methodVersion()) || !"DEEPSEEK".equals(usage.provider())
                    || !Set.of("deepseek-flash", "deepseek-v4-flash").contains(usage.model())
                    || usage.providerCallCount() < 1 || usage.providerCallCount() > 2) return null;
            String synthesisJson = json.writeValueAsString(synthesis);
            String usageJson = json.writeValueAsString(usage);
            if (synthesisJson.getBytes(StandardCharsets.UTF_8).length > 131_072
                    || usageJson.getBytes(StandardCharsets.UTF_8).length > 4096) return null;
            return new ValidResult(synthesis, synthesisJson, usageJson);
        } catch (JsonProcessingException | IllegalArgumentException invalid) { return null; }
    }

    ObjectNode evidence(GeoTaskRecords.Run run, List<GeoTaskRecords.Observation> observations) {
        JsonNode snapshot = read(run.contextSnapshotJson());
        JsonNode frozen = snapshot.path("authorizedContext");
        ObjectNode packet = json.createObjectNode();
        ObjectNode context = packet.putObject("authorizedContext");
        for (String key : List.of("topic", "questions", "competitorSetVersion", "competitors"))
            context.set(key, frozen.path(key).deepCopy());
        GeoVisibilityProjection.Target target = GeoVisibilityProjection.target(run, snapshot);
        packet.set("visibilityEvidence", json.valueToTree(GeoVisibilityProjection.evidence(run, snapshot, observations)));
        if (target.unavailableReason() == null) {
            ObjectNode identity = context.putObject("monitoringTarget");
            identity.put("kind", target.value().kind());
            identity.put("name", target.terms().getFirst());
            identity.set("aliases", json.valueToTree(target.terms().subList(1, target.terms().size())));
        }
        int valid = (int) observations.stream().filter(GeoReportInterpretationService::validAnswer).count();
        int answerLimit = Math.min(6000, 180_000 / Math.max(1, valid));
        int truncatedAnswers = 0, omittedCitations = 0;
        Map<Long, List<GeoTaskRecords.InterpretationCitation>> citationGroups = reports.findInterpretationCitations(run.id(), run.userId())
                .stream().collect(java.util.stream.Collectors.groupingBy(GeoTaskRecords.InterpretationCitation::observationId));
        ArrayNode answers = packet.putArray("observations");
        for (GeoTaskRecords.Observation observation : observations) {
            ObjectNode out = answers.addObject();
            out.put("questionId", Long.toString(observation.questionId()));
            out.put("platformCode", observation.platformCode()); out.put("sampleNo", observation.sampleNo());
            out.put("status", observation.status());
            if (observation.answerText() == null) out.putNull("answerText");
            else {
                out.put("answerText", cut(observation.answerText(), answerLimit));
                if (observation.answerText().length() > answerLimit) truncatedAnswers++;
            }
            out.put("provider", observation.provider()); out.put("model", observation.model());
            out.put("startedAt", observation.startedAt().toString()); out.put("finishedAt", observation.finishedAt().toString());
            out.put("latencyMs", observation.latencyMs()); out.put("stableErrorCode", observation.stableErrorCode());
            out.set("usage", read(observation.usageJson())); out.put("providerCallCount", observation.providerCallCount());
            ArrayNode citations = out.putArray("citations");
            List<GeoTaskRecords.InterpretationCitation> saved = citationGroups.getOrDefault(observation.id(), List.of());
            omittedCitations += saved.isEmpty() ? 0 : Math.max(0, saved.getFirst().totalForObservation() - 3);
            for (GeoTaskRecords.InterpretationCitation citation : saved) {
                ObjectNode item = citations.addObject();
                item.put("title", citation.title()); item.put("url", citation.url()); item.put("domain", citation.domain());
                item.put("snippet", cut(citation.snippet(), 200)); item.put("observedAt", citation.observedAt().toString());
            }
        }
        ObjectNode projection = packet.putObject("evidenceProjection");
        projection.put("answerCharLimit", answerLimit); projection.put("maxCitationsPerObservation", 3);
        projection.put("truncatedAnswerCount", truncatedAnswers); projection.put("omittedCitationCount", omittedCitations);
        if (packet.toString().getBytes(StandardCharsets.UTF_8).length > 899 * 1024)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "报告证据超过本次解读处理范围，原报告仍可查看",
                    Map.of("reason", "EVIDENCE_TOO_LARGE"));
        return packet;
    }

    private GeoTaskDtos.ReportInterpretation projection(GeoTaskRecords.Report report, GeoTaskRecords.Run run,
            List<GeoTaskRecords.Observation> observations, GeoInterpretationRecords.Work existing) {
        int attempts = existing == null ? 0 : existing.attempts();
        String status = MODEL.equals(report.methodVersion()) ? "SUCCEEDED" : existing == null ? "NOT_STARTED" : existing.status();
        String reason = eligibility(report, run, observations);
        if (!"SUCCEEDED".equals(status) && reason == null && !available()) reason = "SERVICE_UNAVAILABLE";
        boolean capacity = capacityOnlyFailure(existing);
        OffsetDateTime retryAfter = capacity && existing.availableAt().isAfter(now()) ? existing.availableAt() : null;
        if (reason == null && retryAfter != null) reason = "SERVICE_UNAVAILABLE";
        if (!"SUCCEEDED".equals(status) && reason == null && attempts >= 2 && !active(existing) && !capacity) reason = "ATTEMPT_LIMIT_REACHED";
        boolean canRetry = reason == null && Set.of("NOT_STARTED", "FAILED").contains(status);
        return new GeoTaskDtos.ReportInterpretation(status, canRetry, attempts, 2,
                existing == null ? null : existing.requestedAt(),
                existing == null ? ("SUCCEEDED".equals(status) ? report.generatedAt() : null) : existing.finishedAt(),
                existing == null ? null : existing.lastErrorCode(), reason, retryAfter);
    }

    private String eligibility(GeoTaskRecords.Report report, GeoTaskRecords.Run run,
                               List<GeoTaskRecords.Observation> observations) {
        if (report.archivedAt() != null) return "ARCHIVED";
        if (!TERMINAL.contains(report.status()) || !TERMINAL.contains(run.status())) return "NOT_TERMINAL";
        if (!Set.of(MODEL, FALLBACK).contains(report.methodVersion())) return "UNSUPPORTED_REPORT";
        if (observations.stream().noneMatch(GeoReportInterpretationService::validAnswer)) return "NO_VALID_ANSWERS";
        return null;
    }

    private static boolean validAnswer(GeoTaskRecords.Observation value) {
        return "SUCCEEDED".equals(value.status()) && value.answerText() != null && !value.answerText().isBlank();
    }

    private static boolean active(GeoInterpretationRecords.Work value) {
        return value != null && Set.of("QUEUED", "RUNNING").contains(value.status());
    }

    private static boolean capacityOnlyFailure(GeoInterpretationRecords.Work value) {
        return value != null && "FAILED".equals(value.status())
                && "GEO_INTERPRETATION_CAPACITY_FULL".equals(value.lastErrorCode());
    }

    private static boolean ownsLease(GeoInterpretationRecords.Work current, GeoInterpretationRecords.Work expected,
                                     OffsetDateTime now) {
        return current != null && "RUNNING".equals(current.status()) && current.attempts() == expected.attempts()
                && current.claimToken() != null && current.claimToken().equals(expected.claimToken())
                && current.leaseExpiresAt() != null && current.leaseExpiresAt().isAfter(now);
    }

    private void event(GeoTaskRecords.Report report, long jobId, int attempt, String type, String source,
                       String error, OffsetDateTime now) {
        ObjectNode detail = json.createObjectNode();
        detail.put("reportId", Long.toString(report.id())); detail.put("jobId", Long.toString(jobId));
        detail.put("attempt", attempt); detail.put("maxProviderCalls", 2);
        if (tasks.insertTaskEvent(ids.nextId(), report.taskId(), report.userId(), null, UUID.randomUUID(), null,
                null, null, type, source, error, null, detail.toString(), now) != 1) throw conflict();
    }

    private GeoTaskRecords.Report requireReport(long id, long userId) {
        return reports.findReport(id, userId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    private GeoTaskRecords.Run requireRun(GeoTaskRecords.Report report, long userId) {
        return reports.findRun(report.runId(), userId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    private boolean available() { return properties.isGeoInterpretationEnabled() && clientAvailable && work != null; }
    private OffsetDateTime now() { return OffsetDateTime.now(clock); }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE); }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.TASK_STATE_CONFLICT); }
    private JsonNode read(String value) {
        try { return json.readTree(value == null ? "{}" : value); }
        catch (JsonProcessingException invalid) { throw conflict(); }
    }
    private String hash(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("Unable to fingerprint GEO evidence", failure); }
    }
    private static String cut(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        int end = maximum;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
    private record ValidResult(GeoTaskDtos.SynthesisInput synthesis, String synthesisJson, String usageJson) { }
}
