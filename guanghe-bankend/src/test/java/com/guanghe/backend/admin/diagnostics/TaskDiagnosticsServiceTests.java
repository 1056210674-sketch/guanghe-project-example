package com.guanghe.backend.admin.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskDiagnosticsServiceTests {
    private static final long TASK_ID = 1_900_012_345_678_904_001L;
    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 9, 9, 8, 0, 0, 0, ZoneOffset.UTC);

    @Mock private TaskDiagnosticsMapper mapper;
    private TaskDiagnosticsService service;

    @BeforeEach
    void setUp() {
        service = new TaskDiagnosticsService(mapper);
    }

    @Test
    void detailAggregatesOnlySanitizedLifecycleEvidenceAndClassifiesStableCodes() throws Exception {
        var task = task("FAILED", "PROVIDER_RESULT_CONFIRMATION_FAILED",
                "PROVIDER_RESULT_UNKNOWN", "0123456789abcdef0123456789abcdef");
        when(mapper.find(TASK_ID)).thenReturn(Optional.of(task));
        when(mapper.events(TASK_ID)).thenReturn(List.of(new TaskDiagnosticsRecords.Event(
                "TASK_FAILED", "RUNNING", "FAILED", "JAVA",
                "PROVIDER_RESULT_CONFIRMATION_FAILED", NOW)));
        when(mapper.executions(TASK_ID)).thenReturn(List.of(new TaskDiagnosticsRecords.Execution(
                2, "FAILED", "PROVIDER_RESULT_RECOVERY", "QWEN_IMAGE", "qwen-image-3.0",
                "UNKNOWN", 1200, "PROVIDER_RESULT_CONFIRMATION_FAILED", NOW, NOW, NOW, NOW, NOW)));
        when(mapper.outbox(TASK_ID)).thenReturn(List.of(new TaskDiagnosticsRecords.Outbox(
                2, "PUBLISHED", 1, null, NOW, NOW, NOW)));
        when(mapper.inbox(TASK_ID)).thenReturn(List.of(new TaskDiagnosticsRecords.Inbox("TERMINAL", NOW)));
        when(mapper.reservation(TASK_ID)).thenReturn(Optional.of(new TaskDiagnosticsRecords.Reservation(
                "RELEASED", 20, 20, 0, 20, NOW, NOW)));
        when(mapper.ledger(TASK_ID)).thenReturn(List.of(new TaskDiagnosticsRecords.Ledger(
                "TASK_RELEASE", 20, -20, NOW)));
        when(mapper.ingestions(TASK_ID)).thenReturn(List.of());
        when(mapper.geo(TASK_ID)).thenReturn(Optional.empty());
        when(mapper.article(TASK_ID)).thenReturn(Optional.empty());
        when(mapper.mediaBatch(TASK_ID)).thenReturn(Optional.empty());

        TaskDiagnosticsDtos.Detail detail = service.get(TASK_ID);

        assertThat(detail.taskId()).isEqualTo(Long.toString(TASK_ID));
        assertThat(detail.decision().failureCategory()).isEqualTo(TaskDiagnosticsDtos.FailureCategory.PROVIDER);
        assertThat(detail.decision().retryability()).isEqualTo(TaskDiagnosticsDtos.Retryability.NEVER);
        assertThat(detail.executions().getFirst().provider()).isEqualTo("QWEN_IMAGE");
        assertThat(detail.billing().releasedPoints()).isEqualTo(20);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(detail);
        assertThat(json).doesNotContain("userId", "inputSnapshot", "prompt", "context",
                "jwt", "hmac", "providerRequestId", "providerTaskId", "providerUrl",
                "objectKey", "executionToken", "sql", "exception");
    }

    @Test
    void unsafeProviderLabelsAreSuppressedAndUnknownStateIsMarkedAsDataGap() {
        when(mapper.find(TASK_ID)).thenReturn(Optional.of(task("RUNNING", "UNCLASSIFIED_FAILURE", null, null)));
        when(mapper.events(TASK_ID)).thenReturn(List.of());
        when(mapper.executions(TASK_ID)).thenReturn(List.of(new TaskDiagnosticsRecords.Execution(
                1, "RUNNING", "EXECUTING", "provider?secret", "model with spaces", null,
                null, null, NOW, NOW, null, NOW, NOW)));
        when(mapper.outbox(TASK_ID)).thenReturn(List.of());
        when(mapper.inbox(TASK_ID)).thenReturn(List.of());
        when(mapper.reservation(TASK_ID)).thenReturn(Optional.empty());
        when(mapper.ingestions(TASK_ID)).thenReturn(List.of());
        when(mapper.geo(TASK_ID)).thenReturn(Optional.empty());
        when(mapper.article(TASK_ID)).thenReturn(Optional.empty());
        when(mapper.mediaBatch(TASK_ID)).thenReturn(Optional.empty());

        TaskDiagnosticsDtos.Detail detail = service.get(TASK_ID);

        assertThat(detail.executions().getFirst().provider()).isNull();
        assertThat(detail.executions().getFirst().model()).isNull();
        assertThat(detail.decision().failureCategory()).isEqualTo(TaskDiagnosticsDtos.FailureCategory.UNKNOWN);
        assertThat(detail.dataGaps()).containsExactly("TRACE_ID_NOT_PERSISTED");
    }

    @Test
    void filtersAndMissingTaskFailWithStableBusinessErrors() {
        assertThatThrownBy(() -> service.list(null, "PAYMENT", null, null, null,
                null, null, 1, 20)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.list(null, null, "BROKEN", null, null,
                null, null, 1, 20)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.list(null, null, null, null, "0".repeat(32),
                null, null, 1, 20)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        when(mapper.find(TASK_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(TASK_ID)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private TaskDiagnosticsRecords.Task task(String status, String stage, String error, String traceId) {
        return new TaskDiagnosticsRecords.Task(TASK_ID, "IMAGE", status, stage, 20, 0,
                error, traceId, NOW, NOW, NOW, NOW, NOW);
    }
}
