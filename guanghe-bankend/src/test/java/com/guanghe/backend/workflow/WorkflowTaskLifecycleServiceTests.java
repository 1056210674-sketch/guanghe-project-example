package com.guanghe.backend.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskRecords;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowTaskLifecycleServiceTests {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 10, 9, 0, 0, 0, ZoneOffset.UTC);
    @Mock WorkflowExecutionMapper mapper;
    @Mock TaskControlMapper taskMapper;
    @Mock SnowflakeIdGenerator ids;
    private WorkflowTaskLifecycleService service;

    @BeforeEach
    void setUp() {
        WorkflowExecutionProperties properties = new WorkflowExecutionProperties();
        properties.setEnabled(true);
        properties.setCompatibleWorkerReady(true);
        AtomicLong sequence = new AtomicLong(30000);
        when(ids.nextId()).thenAnswer(invocation -> sequence.incrementAndGet());
        when(mapper.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertOutbox(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertEvent(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyString(),
                any(), anyString(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.nextEventSequence(anyLong())).thenReturn(10L, 11L);
        service = new WorkflowTaskLifecycleService(mapper, taskMapper, properties, ids,
                new ObjectMapper().findAndRegisterModules(), ObservabilitySupport.noop());
    }

    @Test
    void successfulTaskSettlesAllocationAndQueuesExactlyOneResumeGeneration() {
        TaskRecords.Task task = task("SUCCEEDED", 10);
        arrange(task, step("WAITING"), reservation(10));
        when(mapper.finishAllocation(9001, 41, 10, 10, 0, NOW)).thenReturn(1);
        when(mapper.finishTaskStep(9101, 9001, 9201, "SUCCEEDED", "task:9201", null, NOW))
                .thenReturn(1);
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(account()));

        assertThat(service.taskTerminal(task, "SUCCEEDED", null, NOW)).isTrue();

        verify(mapper).finishAllocation(9001, 41, 10, 10, 0, NOW);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertOutbox(anyLong(), org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(9001L), org.mockito.ArgumentMatchers.eq(2),
                anyString(), anyString(), payload.capture(), anyString(),
                org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(payload.getValue()).contains("\"resumeOnly\":true", "\"attempt\":2")
                .doesNotContain("executionToken", "provider", "model");
    }

    @Test
    void failedTaskReleasesAllocatedFundingAndDuplicateTerminalIsNoOp() {
        TaskRecords.Task task = task("FAILED", 0);
        arrange(task, step("WAITING"), reservation(10));
        when(mapper.finishAllocation(9001, 41, 10, 0, 10, NOW)).thenReturn(1);
        when(mapper.finishTaskStep(9101, 9001, 9201, "FAILED", null,
                "PROVIDER_UNAVAILABLE", NOW)).thenReturn(1);
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(account()));

        assertThat(service.taskTerminal(task, "FAILED", "PROVIDER_UNAVAILABLE", NOW)).isTrue();
        verify(mapper).finishAllocation(9001, 41, 10, 0, 10, NOW);

        when(mapper.lockStepByTask(9201)).thenReturn(Optional.of(step("FAILED")));
        assertThat(service.taskTerminal(task, "FAILED", "PROVIDER_UNAVAILABLE", NOW)).isFalse();
        verify(mapper, org.mockito.Mockito.times(1)).insertOutbox(anyLong(), anyLong(), anyLong(),
                anyInt(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void unrelatedTaskCannotChangeWorkflowFundingOrResumeState() {
        TaskRecords.Task task = task("SUCCEEDED", 10);
        when(taskMapper.lockTask(task.id())).thenReturn(Optional.of(task));
        when(mapper.lockStepByTask(task.id())).thenReturn(Optional.empty());

        assertThat(service.taskTerminal(task, "SUCCEEDED", null, NOW)).isFalse();
        verify(mapper, never()).finishAllocation(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), any());
        verify(mapper, never()).insertOutbox(anyLong(), anyLong(), anyLong(), anyInt(), anyString(),
                anyString(), anyString(), anyString(), any());
    }

    private void arrange(TaskRecords.Task task, WorkflowExecutionRecords.StepState step,
                         WorkflowExecutionRecords.Reservation reservation) {
        when(taskMapper.lockTask(task.id())).thenReturn(Optional.of(task));
        when(mapper.lockStepByTask(task.id())).thenReturn(Optional.of(step));
        when(mapper.lockPlanInternal(9001)).thenReturn(Optional.of(plan()));
        when(mapper.lockLatestExecution(9001)).thenReturn(Optional.of(execution()));
        when(mapper.lockReservation(9001)).thenReturn(Optional.of(reservation));
        when(mapper.readySatisfiedSteps(9001)).thenReturn(1);
    }

    private WorkflowExecutionRecords.PlanState plan() {
        return new WorkflowExecutionRecords.PlanState(9001, 41, 501, 101, 102L, 1,
                "RUNNING", 30, 50, 4, 1, 4, 4, 2, NOW.plusHours(1),
                null, null, NOW.minusMinutes(1), null, null, 0);
    }

    private WorkflowExecutionRecords.StepState step(String status) {
        return new WorkflowExecutionRecords.StepState(9101, 9001, 41, 501, "step-1", 1,
                "PREPARE", "task_draft.propose", "1", "PREPARE", "[]", "[\"done\"]",
                "[]", "draft:7101", 7101L, "IMAGE", 6001L, 10, status, 1, 9201L,
                null, NOW.minusMinutes(1), null, "a".repeat(64), NOW.plusMinutes(20),
                java.util.UUID.randomUUID(), "b".repeat(64), null, "WAITING".equals(status) ? "RUNNING" : "FAILED");
    }

    private WorkflowExecutionRecords.Execution execution() {
        return new WorkflowExecutionRecords.Execution(9301, 9001, 41, 501, 1, 1, 8001,
                "WAITING_TASK", "consumer", "worker", "bundle", "c".repeat(64), "{}",
                NOW, NOW.minusMinutes(1), 0, null, NOW.minusMinutes(2), NOW.minusMinutes(1),
                NOW.minusMinutes(2), NOW.minusMinutes(1));
    }

    private WorkflowExecutionRecords.Reservation reservation(int allocated) {
        return new WorkflowExecutionRecords.Reservation(9401, 9001, 41, 30,
                20, allocated, 0, 0, "PARTIAL", NOW, NOW);
    }

    private WorkflowExecutionRecords.PointAccount account() {
        return new WorkflowExecutionRecords.PointAccount(41, 70, 20, 2, NOW, NOW);
    }

    private TaskRecords.Task task(String status, int settledPoints) {
        return new TaskRecords.Task(9201, 41, 101, 102L, 501, 7101, "IMAGE", status,
                status, 1, "{}", "a".repeat(64), 6001, 10, settledPoints, "{}", null,
                "WORKFLOW_STEP_ACTIVATE", java.util.UUID.randomUUID(), "b".repeat(64), NOW,
                NOW, NOW, NOW, NOW);
    }
}
