package com.guanghe.backend.admin.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunDiagnosticsServiceTests {
    private static final UUID RUN_ID = UUID.fromString("0f1e2d3c-4b5a-4678-9abc-def012345678");
    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 9, 10, 8, 0, 0, 0, ZoneOffset.UTC);

    @Mock private RunDiagnosticsMapper mapper;
    private RunDiagnosticsService service;

    @BeforeEach
    void setUp() { service = new RunDiagnosticsService(mapper); }

    @Test
    void detailReturnsOnlySafeProjectionAndReusesFailureDecision() throws Exception {
        when(mapper.find(RUN_ID)).thenReturn(Optional.of(run("FAILED", "MODEL_OUTPUT_SCHEMA_INVALID",
                "0123456789abcdef0123456789abcdef", "REDIS", "DEEPSEEK", "deepseek-v4-flash")));
        when(mapper.events(RUN_ID)).thenReturn(List.of(
                new RunDiagnosticsRecords.Event("message.start", 1, null, NOW),
                new RunDiagnosticsRecords.Event("message.failed", 2, "MODEL_OUTPUT_SCHEMA_INVALID", NOW)));

        RunDiagnosticsDtos.Detail detail = service.get(RUN_ID.toString());

        assertThat(detail.runId()).isEqualTo(RUN_ID.toString());
        assertThat(detail.conversationId()).isEqualTo("1900012345678904001");
        assertThat(detail.decision().failureCategory()).isEqualTo(TaskDiagnosticsDtos.FailureCategory.MODEL);
        assertThat(detail.contextBudget().cacheHit()).isTrue();
        assertThat(detail.timeline()).extracting(RunDiagnosticsDtos.TimelineEntry::eventType)
                .containsExactly("message.start", "message.failed");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(detail);
        assertThat(json).doesNotContain("contextSnapshot", "contextRef", "messageContent", "prompt",
                "jwt", "hmac", "providerRequestId", "providerTaskId", "providerUrl",
                "objectKey", "executionToken", "payload", "exception");
    }

    @Test
    void historicalNullTraceAndUsageAreExplicitGapsAndUnsafeLabelsAreSuppressed() {
        when(mapper.find(RUN_ID)).thenReturn(Optional.of(run("RUNNING", "bad error text", null,
                "UNKNOWN_SOURCE", "provider?secret", "model with spaces")));
        when(mapper.events(RUN_ID)).thenReturn(List.of(
                new RunDiagnosticsRecords.Event("raw payload secret", 1, "bad code", NOW)));

        RunDiagnosticsDtos.Detail detail = service.get(RUN_ID.toString());

        assertThat(detail.dataGaps()).containsExactly("TRACE_ID_NOT_PERSISTED", "USAGE_NOT_REPORTED");
        assertThat(detail.stableErrorCode()).isNull();
        assertThat(detail.usage().provider()).isNull();
        assertThat(detail.usage().model()).isNull();
        assertThat(detail.contextBudget().contextSource()).isNull();
        assertThat(detail.timeline()).isEmpty();
    }

    @Test
    void malformedFiltersAndMissingRunUseStableErrors() {
        assertValidation(() -> service.list(RUN_ID.toString().toUpperCase(), null, null,
                null, null, null, null, 1, 20));
        assertValidation(() -> service.list(null, "999999999999999999999", null,
                null, null, null, null, 1, 20));
        assertValidation(() -> service.list(null, null, "BROKEN", null,
                null, null, null, 1, 20));
        assertValidation(() -> service.list(null, null, null, null,
                "0".repeat(32), null, null, 1, 20));
        assertValidation(() -> service.list(null, null, null, null, null,
                NOW.plusDays(1), NOW, 1, 20));
        when(mapper.find(RUN_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(RUN_ID.toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void assertValidation(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private RunDiagnosticsRecords.Run run(String status, String error, String traceId,
            String contextSource, String provider, String model) {
        Integer usage = traceId == null ? null : 1;
        return new RunDiagnosticsRecords.Run(RUN_ID, 1_900_012_345_678_904_001L, status, 2,
                error, traceId, 1_900_012_345_678_904_002L,
                usage, usage, usage, usage, 0, 100, 90, 10, 123,
                provider, model, 128000, 8000, 2000, 1000, 117000, 100,
                false, contextSource, null, NOW, "FAILED".equals(status) ? NOW : null, NOW, NOW);
    }
}
