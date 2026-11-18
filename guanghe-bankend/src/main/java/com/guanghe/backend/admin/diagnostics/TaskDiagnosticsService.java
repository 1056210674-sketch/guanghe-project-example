package com.guanghe.backend.admin.diagnostics;

import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskDiagnosticsService {
    private static final Pattern BUSINESS_ID = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern STABLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern SAFE_PROVIDER = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern SAFE_MODEL = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");
    private static final Set<String> TASK_TYPES = Set.of("IMAGE", "VIDEO", "GEO", "ARTICLE", "MEDIA");
    private static final Set<String> TASK_STATUSES = Set.of(
            "QUEUED", "RUNNING", "RETRY_WAIT", "SUCCEEDED", "PARTIAL",
            "FAILED", "SKIPPED", "SYSTEM_TERMINATED");

    private final TaskDiagnosticsMapper mapper;

    @Autowired
    public TaskDiagnosticsService(ObjectProvider<TaskDiagnosticsMapper> mapperProvider) {
        this.mapper = mapperProvider.getIfAvailable();
    }

    TaskDiagnosticsService(TaskDiagnosticsMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskDiagnosticsDtos.Summary> list(
            String taskId, String taskType, String taskStatus, String stableErrorCode,
            String traceId, OffsetDateTime from, OffsetDateTime to, int page, int size) {
        TaskDiagnosticsMapper db = mapper();
        Long parsedTaskId = taskId == null ? null : parseId(taskId);
        String type = optionalEnum(taskType, TASK_TYPES);
        String status = optionalEnum(taskStatus, TASK_STATUSES);
        String error = optionalCode(stableErrorCode);
        String trace = optionalTraceId(traceId);
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long offset = (long) (page - 1) * size;
        List<TaskDiagnosticsDtos.Summary> items = db.list(
                parsedTaskId, type, status, error, trace, from, to, offset, size)
                .stream().map(this::summary).toList();
        long total = db.count(parsedTaskId, type, status, error, trace, from, to);
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public TaskDiagnosticsDtos.Detail get(long taskId) {
        TaskDiagnosticsMapper db = mapper();
        TaskDiagnosticsRecords.Task task = db.find(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<TaskDiagnosticsRecords.Execution> executions = db.executions(taskId);
        List<TaskDiagnosticsRecords.Outbox> outbox = db.outbox(taskId);
        TaskDiagnosticsRecords.Reservation reservation = db.reservation(taskId).orElse(null);
        TaskDiagnosticsRecords.Geo geo = db.geo(taskId).orElse(null);
        TaskDiagnosticsRecords.Article article = db.article(taskId).orElse(null);
        TaskDiagnosticsRecords.MediaBatch media = db.mediaBatch(taskId).orElse(null);
        List<String> gaps = new ArrayList<>();
        if (task.traceId() == null) gaps.add("TRACE_ID_NOT_PERSISTED");
        if (executions.isEmpty() && !"MEDIA".equals(task.taskType())) gaps.add("NO_EXECUTION_RECORDED");

        return new TaskDiagnosticsDtos.Detail(
                Long.toString(task.id()), task.taskType(), task.status(), task.stageCode(),
                task.quotedPoints(), task.settledPoints(), task.queuedAt(), task.startedAt(),
                task.finishedAt(), task.createdAt(), task.updatedAt(), decision(task), task.traceId(),
                db.events(taskId).stream().map(e -> new TaskDiagnosticsDtos.TimelineEntry(
                        e.eventType(), e.fromStatus(), e.toStatus(), e.source(),
                        e.errorCode(), e.occurredAt())).toList(),
                executions.stream().map(e -> new TaskDiagnosticsDtos.ExecutionSummary(
                        e.executionNo(), e.status(), e.stageCode(), safeProvider(e.providerCode()),
                        safeModel(e.providerModel()), e.submissionState(), e.latencyMs(), e.errorCode(),
                        e.lastHeartbeatAt(), e.startedAt(), e.finishedAt())).toList(),
                outbox.stream().map(o -> new TaskDiagnosticsDtos.OutboxSummary(
                        o.executionAttempt(), o.status(), o.attempts(), o.lastErrorCode(),
                        o.availableAt(), o.publishedAt(), o.createdAt())).toList(),
                db.inbox(taskId).stream().map(i -> new TaskDiagnosticsDtos.InboxSummary(
                        i.disposition(), i.createdAt())).toList(),
                reservation == null ? null : new TaskDiagnosticsDtos.BillingSummary(
                        reservation.status(), reservation.quotedPoints(), reservation.frozenPoints(),
                        reservation.settledPoints(), reservation.releasedPoints(),
                        db.ledger(taskId).stream().map(l -> new TaskDiagnosticsDtos.LedgerEntry(
                                l.entryType(), l.availableDelta(), l.frozenDelta(), l.createdAt())).toList()),
                db.ingestions(taskId).stream().map(i -> new TaskDiagnosticsDtos.AssetIngestionSummary(
                        i.status(), i.attempt(), i.maxAttempts(), safeProvider(i.providerCode()),
                        safeModel(i.providerModel()), i.lastErrorCode(), i.availableAt(),
                        i.completedAt(), i.updatedAt())).toList(),
                geo == null ? null : new TaskDiagnosticsDtos.GeoSummary(
                        Long.toString(geo.id()), geo.status(), geo.expectedUnits(), geo.succeededUnits(),
                        geo.failedUnits(), geo.missingUnits(), geo.startedAt(), geo.finishedAt(), geo.updatedAt()),
                article == null ? null : new TaskDiagnosticsDtos.ArticleSummary(
                        Long.toString(article.id()), article.status(), article.version(), article.updatedAt()),
                media == null ? null : new TaskDiagnosticsDtos.MediaSummary(
                        media.status(), media.quotedPoints(), media.settledPoints(), media.releasedPoints(),
                        media.finishedAt(), media.updatedAt(),
                        db.mediaSubmissions(taskId).stream().map(s ->
                                new TaskDiagnosticsDtos.MediaSubmissionSummary(
                                        s.status(), s.submissionState(), safeProvider(s.providerCode()),
                                        safeModel(s.upstreamModelId()), s.stableErrorCode(), s.updatedAt())).toList()),
                List.copyOf(gaps));
    }

    private TaskDiagnosticsDtos.Summary summary(TaskDiagnosticsRecords.Task task) {
        return new TaskDiagnosticsDtos.Summary(Long.toString(task.id()), task.taskType(), task.status(),
                task.stageCode(), task.createdAt(), task.updatedAt(), task.finishedAt(),
                decision(task), task.traceId());
    }

    static TaskDiagnosticsDtos.Decision decision(TaskDiagnosticsRecords.Task task) {
        return decision(task.status(), task.errorCode(), task.stageCode());
    }

    static TaskDiagnosticsDtos.Decision decision(String status, String error, String stageCode) {
        String stage = stageCode == null ? "" : stageCode;
        String signal = ((error == null ? "" : error) + " " + stage).toUpperCase(Locale.ROOT);
        TaskDiagnosticsDtos.FailureCategory category = category(signal);
        boolean terminal = List.of("SUCCEEDED", "FAILED", "PARTIAL", "SYSTEM_TERMINATED",
                "COMPLETED", "STOPPED").contains(status);
        boolean waiting = !terminal && (signal.contains("UNKNOWN") || signal.contains("RECONCIL")
                || signal.contains("RECOVERY") || "RETRY_WAIT".equals(status));
        TaskDiagnosticsDtos.Retryability retryability;
        if (category == TaskDiagnosticsDtos.FailureCategory.BILLING) {
            retryability = TaskDiagnosticsDtos.Retryability.MANUAL_RECONCILIATION;
        } else if (terminal) {
            retryability = TaskDiagnosticsDtos.Retryability.NEVER;
        } else if (waiting && (category == TaskDiagnosticsDtos.FailureCategory.PROVIDER
                || category == TaskDiagnosticsDtos.FailureCategory.MEDIA_SUBMISSION)) {
            retryability = TaskDiagnosticsDtos.Retryability.RECOVERY_ONLY;
        } else if (category == TaskDiagnosticsDtos.FailureCategory.WORKER
                || category == TaskDiagnosticsDtos.FailureCategory.MESSAGING) {
            retryability = TaskDiagnosticsDtos.Retryability.IDEMPOTENT_RETRY;
        } else if (category == TaskDiagnosticsDtos.FailureCategory.PROVIDER
                || category == TaskDiagnosticsDtos.FailureCategory.ASSET_INGESTION) {
            retryability = TaskDiagnosticsDtos.Retryability.SAFE_RETRY;
        } else if (category == TaskDiagnosticsDtos.FailureCategory.UNKNOWN) {
            retryability = TaskDiagnosticsDtos.Retryability.UNKNOWN;
        } else {
            retryability = TaskDiagnosticsDtos.Retryability.NEVER;
        }
        boolean retryable = retryability == TaskDiagnosticsDtos.Retryability.SAFE_RETRY
                || retryability == TaskDiagnosticsDtos.Retryability.IDEMPOTENT_RETRY
                || retryability == TaskDiagnosticsDtos.Retryability.RECOVERY_ONLY;
        return new TaskDiagnosticsDtos.Decision(category, error, retryability, retryable, waiting);
    }

    private static TaskDiagnosticsDtos.FailureCategory category(String signal) {
        if (signal.matches(".*(MEDIA|SUBMIT|ORDER|RECONCIL).*")) return TaskDiagnosticsDtos.FailureCategory.MEDIA_SUBMISSION;
        if (signal.matches(".*(INGESTION|ASSET|OSS|DOWNLOAD).*")) return TaskDiagnosticsDtos.FailureCategory.ASSET_INGESTION;
        if (signal.matches(".*(POINT|LEDGER|RESERVATION|SETTLE|BALANCE|BILLING).*")) return TaskDiagnosticsDtos.FailureCategory.BILLING;
        if (signal.matches(".*(OUTBOX|RABBIT|DISPATCH|DLQ|MESSAGE|SSE|AGENT_STREAM).*")) return TaskDiagnosticsDtos.FailureCategory.MESSAGING;
        if (signal.matches(".*(WORKER|LEASE|CLAIM|HEARTBEAT|AGENT_START|AGENT_BRIDGE).*")) return TaskDiagnosticsDtos.FailureCategory.WORKER;
        if (signal.matches(".*(PROVIDER|QWEN|WAN).*")) return TaskDiagnosticsDtos.FailureCategory.PROVIDER;
        if (signal.matches(".*(MODEL|DEEPSEEK|SYNTHESIS|TOKEN_BUDGET|OUTPUT_SCHEMA).*")) return TaskDiagnosticsDtos.FailureCategory.MODEL;
        if (signal.matches(".*(CONTEXT|HMAC|NONCE|AUTH).*")) return TaskDiagnosticsDtos.FailureCategory.CONTEXT;
        if (signal.matches(".*(TOOL|DRAFT|POLICY).*")) return TaskDiagnosticsDtos.FailureCategory.TOOL_POLICY;
        return TaskDiagnosticsDtos.FailureCategory.UNKNOWN;
    }

    private TaskDiagnosticsMapper mapper() {
        if (mapper == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        return mapper;
    }

    private static long parseId(String value) {
        if (!BUSINESS_ID.matcher(value).matches()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static String optionalCode(String value) {
        if (value == null) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!STABLE_CODE.matcher(normalized).matches()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return normalized;
    }

    private static String optionalEnum(String value, Set<String> allowed) {
        String normalized = optionalCode(value);
        if (normalized != null && !allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private static String optionalTraceId(String value) {
        if (value == null) return null;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!TRACE_ID.matcher(normalized).matches() || normalized.chars().allMatch(c -> c == '0')) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private static String safeProvider(String value) {
        return value != null && SAFE_PROVIDER.matcher(value).matches() ? value : null;
    }

    private static String safeModel(String value) {
        return value != null && SAFE_MODEL.matcher(value).matches() ? value : null;
    }
}
