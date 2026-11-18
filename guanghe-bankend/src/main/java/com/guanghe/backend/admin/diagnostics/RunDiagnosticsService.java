package com.guanghe.backend.admin.diagnostics;

import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunDiagnosticsService {
    private static final Pattern BUSINESS_ID = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern PROVIDER = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");
    private static final Pattern EVENT_TYPE = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");
    private static final Set<String> STATUSES = Set.of(
            "QUEUED", "STARTING", "RUNNING", "COMPLETED", "FAILED", "STOP_REQUESTED", "STOPPED");
    private final RunDiagnosticsMapper mapper;

    @Autowired
    public RunDiagnosticsService(ObjectProvider<RunDiagnosticsMapper> provider) {
        this.mapper = provider.getIfAvailable();
    }

    RunDiagnosticsService(RunDiagnosticsMapper mapper) { this.mapper = mapper; }

    @Transactional(readOnly = true)
    public PageResponse<RunDiagnosticsDtos.Summary> list(String runId, String conversationId,
            String status, String errorCode, String traceId, OffsetDateTime from,
            OffsetDateTime to, int page, int size) {
        UUID parsedRun = runId == null ? null : parseUuid(runId);
        Long parsedConversation = conversationId == null ? null : parseBusinessId(conversationId);
        String normalizedStatus = optionalCode(status);
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus)) invalid();
        String normalizedError = optionalCode(errorCode);
        String normalizedTrace = optionalTrace(traceId);
        if (from != null && to != null && from.isAfter(to)) invalid();
        long offset = (long) (page - 1) * size;
        List<RunDiagnosticsDtos.Summary> items = mapper().list(parsedRun, parsedConversation,
                normalizedStatus, normalizedError, normalizedTrace, from, to, offset, size)
                .stream().map(this::summary).toList();
        long total = mapper().count(parsedRun, parsedConversation, normalizedStatus,
                normalizedError, normalizedTrace, from, to);
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public RunDiagnosticsDtos.Detail get(String rawRunId) {
        UUID runId = parseUuid(rawRunId);
        RunDiagnosticsRecords.Run run = mapper().find(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<String> gaps = new ArrayList<>();
        if (run.traceId() == null) gaps.add("TRACE_ID_NOT_PERSISTED");
        if (run.workflowStepCount() == null) gaps.add("USAGE_NOT_REPORTED");
        String source = Set.of("REDIS", "POSTGRESQL").contains(String.valueOf(run.contextSource()))
                ? run.contextSource() : null;
        Boolean cacheHit = source == null ? null : "REDIS".equals(source);
        String stableErrorCode = safe(run.errorCode(), CODE);
        return new RunDiagnosticsDtos.Detail(run.id().toString(), Long.toString(run.conversationId()),
                run.status(), run.lastPythonSequence(), stableErrorCode,
                TaskDiagnosticsService.decision(run.status(), stableErrorCode, null), run.traceId(),
                run.stopRequestedAt(), run.startedAt(), run.completedAt(), run.createdAt(), run.updatedAt(),
                usage(run), new RunDiagnosticsDtos.ContextBudget(run.targetInputTokens(),
                        run.reservedOutputTokens(), run.reservedToolTokens(), run.reservedSafetyTokens(),
                        run.availableHistoryTokens(), run.estimatedHistoryTokens(), run.contextTruncated(),
                        source, cacheHit), run.linkedTaskId() == null ? null : Long.toString(run.linkedTaskId()),
                mapper().events(runId).stream()
                        .filter(e -> safe(e.eventType(), EVENT_TYPE) != null)
                        .map(e -> new RunDiagnosticsDtos.TimelineEntry(e.eventType(), e.sequence(),
                                safe(e.stableErrorCode(), CODE), e.occurredAt())).toList(),
                List.copyOf(gaps));
    }

    private RunDiagnosticsDtos.Summary summary(RunDiagnosticsRecords.Run run) {
        String stableErrorCode = safe(run.errorCode(), CODE);
        return new RunDiagnosticsDtos.Summary(run.id().toString(), Long.toString(run.conversationId()),
                run.status(), stableErrorCode,
                TaskDiagnosticsService.decision(run.status(), stableErrorCode, null), run.traceId(), run.createdAt(),
                run.updatedAt(), run.completedAt());
    }

    private RunDiagnosticsDtos.Usage usage(RunDiagnosticsRecords.Run run) {
        return new RunDiagnosticsDtos.Usage(run.workflowStepCount(), run.modelCallCount(),
                run.contextLoadCount(), run.toolCallCount(), run.providerCallCount(),
                run.estimatedInputTokens(), run.providerInputTokens(), run.providerOutputTokens(),
                run.providerLatencyMs(), safe(run.provider(), PROVIDER), safe(run.model(), MODEL));
    }

    private RunDiagnosticsMapper mapper() {
        if (mapper == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        return mapper;
    }

    static UUID parseUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) invalid();
            return parsed;
        } catch (IllegalArgumentException exception) {
            invalid();
            return null;
        }
    }

    private static long parseBusinessId(String value) {
        if (!BUSINESS_ID.matcher(value).matches()) invalid();
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) invalid();
            return parsed;
        } catch (NumberFormatException exception) {
            invalid();
            return 0;
        }
    }

    private static String optionalCode(String value) {
        if (value == null) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) invalid();
        return normalized;
    }

    private static String optionalTrace(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (!TRACE_ID.matcher(normalized).matches() || normalized.chars().allMatch(c -> c == '0')) invalid();
        return normalized;
    }

    private static String safe(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches() ? value : null;
    }

    private static void invalid() { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
}
