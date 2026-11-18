package com.guanghe.backend.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskControlService;
import com.guanghe.backend.task.TaskRecords;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowExecutionServiceTests {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 10, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final long PLAN_ID = 9001;
    @Mock WorkflowExecutionMapper mapper;
    @Mock WorkflowPlanMapper plans;
    @Mock SnowflakeIdGenerator ids;
    @Mock TaskControlService tasks;
    @Mock WorkflowAuthorizedContextService authorizedContexts;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private WorkflowExecutionProperties executionProperties;
    private WorkflowExecutionService service;

    @BeforeEach
    void setUp() {
        executionProperties = new WorkflowExecutionProperties();
        executionProperties.setEnabled(true);
        executionProperties.setCompatibleWorkerReady(true);
        PaidTaskProperties paid = new PaidTaskProperties();
        paid.setTokenSecret("test-only-workflow-execution-secret-material");
        AtomicLong sequence = new AtomicLong(10000);
        when(ids.nextId()).thenAnswer(invocation -> sequence.incrementAndGet());
        service = new WorkflowExecutionService(mapper, plans, executionProperties, ids, json, paid,
                ObservabilitySupport.noop(), tasks, authorizedContexts) {
            @Override OffsetDateTime now() { return NOW; }
        };
        when(authorizedContexts.load(any(), any(), any(), any(Boolean.class)))
                .thenReturn(runContext());
        when(mapper.insertReservation(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertOutbox(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertEvent(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyString(),
                any(), anyString(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.nextEventSequence(anyLong())).thenReturn(4L);
    }

    @Test
    void unavailableStartFailsBeforeReadingOrMutatingDatabase() {
        executionProperties.setCompatibleWorkerReady(false);

        assertThatThrownBy(() -> service.start(user(), PLAN_ID, uuid().toString(),
                new WorkflowExecutionDtos.StartRequest(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.WORKFLOW_EXECUTION_UNAVAILABLE));

        verify(mapper, never()).lockPlan(anyLong(), anyLong());
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).insertOutbox(anyLong(), anyLong(), anyLong(), anyInt(), anyString(),
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void startFreezesAuthoritativeQuoteAndCreatesOneSafeOutboxAtomically() {
        WorkflowExecutionRecords.PlanState plan = plan("CONFIRMED");
        when(mapper.findByStartKey(41, uuid())).thenReturn(Optional.empty());
        when(mapper.lockPlan(PLAN_ID, 41)).thenReturn(Optional.of(plan));
        when(plans.lockConfirmation(PLAN_ID, 41)).thenReturn(Optional.of(confirmation()));
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(account(100, 0)));
        when(mapper.freezePoints(41, 30, NOW)).thenReturn(1);
        when(mapper.queuePlan(anyLong(), anyLong(), anyInt(), any(), anyString(), any())).thenReturn(1);

        WorkflowExecutionDtos.StartResponse result = service.start(user(), PLAN_ID, uuid().toString(),
                new WorkflowExecutionDtos.StartRequest(1));

        assertThat(result.status()).isEqualTo("QUEUED");
        verify(authorizedContexts).requireExecutable(plan.authorizedContextSnapshotJson(),
                plan.authorizedContextHash(), plan.authorizedContextSchemaVersion(),
                plan.authorizedContextDataGap());
        verify(mapper).freezePoints(41, 30, NOW);
        verify(mapper).insertReservation(anyLong(), org.mockito.ArgumentMatchers.eq(PLAN_ID),
                org.mockito.ArgumentMatchers.eq(41L), org.mockito.ArgumentMatchers.eq(30),
                org.mockito.ArgumentMatchers.eq(NOW));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertOutbox(anyLong(), org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(PLAN_ID), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq("zhixiaohe.task"),
                org.mockito.ArgumentMatchers.eq("agent.workflow"), payload.capture(), anyString(),
                org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(payload.getValue()).contains("WORKFLOW_EXECUTION_REQUESTED", "\"attempt\":1")
                .doesNotContain("goalSummary", "confirmationToken", "userId");
    }

    @Test
    void historicalPlanWithoutAuthorizedSnapshotFailsClosedBeforeFunding() {
        WorkflowExecutionRecords.PlanState historical = plan("CONFIRMED");
        when(mapper.lockPlan(PLAN_ID, 41)).thenReturn(Optional.of(historical));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.WORKFLOW_CONTEXT_UNAVAILABLE))
                .when(authorizedContexts).requireExecutable(any(), any(), any(), any(Boolean.class));

        assertThatThrownBy(() -> service.start(user(), PLAN_ID, uuid().toString(),
                new WorkflowExecutionDtos.StartRequest(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.WORKFLOW_CONTEXT_UNAVAILABLE));
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).insertOutbox(anyLong(), anyLong(), anyLong(), anyInt(),
                anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void claimCreatesFencedGenerationAndDuplicateClaimNeverReturnsTokenAgain() {
        WorkflowExecutionRecords.PlanState queued = plan("QUEUED");
        when(mapper.lockPlanInternal(PLAN_ID)).thenReturn(Optional.of(queued));
        when(mapper.findExecutionByClaim(PLAN_ID, 7001)).thenReturn(Optional.empty());
        when(mapper.latestExecution(PLAN_ID)).thenReturn(Optional.empty());
        when(mapper.matchingOutbox(7001, PLAN_ID, 1)).thenReturn(1);
        when(mapper.steps(PLAN_ID, 41)).thenReturn(List.of(step(9101, "step-1", "[]"),
                step(9102, "step-2", "[\"step-1\"]")));
        when(mapper.insertExecution(any())).thenReturn(1);
        when(mapper.startPlan(PLAN_ID, 1, NOW)).thenReturn(1);
        WorkflowExecutionDtos.ClaimRequest request = new WorkflowExecutionDtos.ClaimRequest(
                "7001", "workflow-consumer", "worker-a", "p3-worker-v1", 1, 1);

        WorkflowExecutionDtos.ClaimResponse claimed = service.claim(PLAN_ID, request);
        assertThat(claimed.claimed()).isTrue();
        assertThat(claimed.executionToken()).startsWith("v1.");
        ArgumentCaptor<WorkflowExecutionRecords.Execution> inserted =
                ArgumentCaptor.forClass(WorkflowExecutionRecords.Execution.class);
        verify(mapper).insertExecution(inserted.capture());
        assertThat(inserted.getValue().executionNo()).isEqualTo(1);
        assertThat(inserted.getValue().contextSnapshotJson()).doesNotContain("userId", "executionToken");

        when(mapper.findExecutionByClaim(PLAN_ID, 7001)).thenReturn(Optional.of(inserted.getValue()));
        WorkflowExecutionDtos.ClaimResponse replay = service.claim(PLAN_ID, request);
        assertThat(replay.disposition()).isEqualTo("DUPLICATE");
        assertThat(replay.executionToken()).isNull();
    }

    @Test
    void executionContextReloadsImmutablePlanSnapshotInsteadOfTrustingExecutionCopy() throws Exception {
        String token = "workflow-context-token";
        String hash = "c".repeat(64);
        WorkflowExecutionRecords.PlanState plan = planWithContext("RUNNING", hash);
        ObjectNode stored = json.createObjectNode();
        stored.putObject("plan").put("id", Long.toString(PLAN_ID));
        stored.putArray("steps");
        stored.putArray("observations");
        stored.putObject("budget")
                .put("maxSteps", 4)
                .put("maxReplans", 1)
                .put("maxModelCalls", 4)
                .put("maxToolCalls", 4)
                .put("maxProviderCalls", 2)
                .put("authorizedMaxPoints", 30)
                .put("deadlineAt", NOW.plusHours(1).toString());
        stored.putObject("authorizedContext").put("untrusted", true);
        stored.put("authorizedContextSchemaVersion", 1);
        stored.put("authorizedContextHash", hash);
        stored.put("authorizedContextSource", "PLAN_SNAPSHOT");
        WorkflowExecutionRecords.Execution execution = new WorkflowExecutionRecords.Execution(
                10001, PLAN_ID, 41, 501, 1, 1, 7001, "RUNNING", "consumer", "worker",
                "bundle", sha256(token), json.writeValueAsString(stored), NOW.plusMinutes(2),
                NOW, 0, null, NOW, null, NOW, NOW);
        when(mapper.latestExecution(PLAN_ID)).thenReturn(Optional.of(execution));
        when(mapper.findPlanInternal(PLAN_ID)).thenReturn(Optional.of(plan));
        when(mapper.observations(PLAN_ID)).thenReturn(List.of());

        WorkflowExecutionDtos.ExecutionContext result = service.context(PLAN_ID, 1, token);

        assertThat(result.authorizedContext()).isEqualTo(runContext());
        assertThat(result.authorizedContextHash()).isEqualTo(hash);
        assertThat(result.authorizedContextSource()).isEqualTo("PLAN_SNAPSHOT");
        verify(authorizedContexts).load(plan.authorizedContextSnapshotJson(), hash, 1, false);
    }

    @Test
    void staleExecutionTokenCannotHeartbeatOrWriteObservation() {
        WorkflowExecutionRecords.Execution current = execution("current-hash");
        when(mapper.lockLatestExecution(PLAN_ID)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.heartbeat(PLAN_ID,
                new WorkflowExecutionDtos.HeartbeatRequest(1, "stale-token", "QUERYING")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_ALREADY_CLAIMED));
        verify(mapper, never()).heartbeat(anyLong(), anyString(), any(), any());
        verify(mapper, never()).insertObservation(any());
    }

    @Test
    void staleGenerationCannotHeartbeatObserveChangeStepReplanOrComplete() {
        String currentToken = "current-generation-token";
        when(mapper.lockLatestExecution(PLAN_ID)).thenReturn(Optional.of(execution(sha256(currentToken))));
        when(mapper.findEventHash(anyLong(), any())).thenReturn(Optional.empty());
        ObjectNode facts = json.createObjectNode().put("result", "safe");

        assertStale(() -> service.heartbeat(PLAN_ID,
                new WorkflowExecutionDtos.HeartbeatRequest(1, "stale-generation-token", "QUERYING")));
        assertStale(() -> service.observe(PLAN_ID, new WorkflowExecutionDtos.ObservationRequest(
                UUID.randomUUID(), UUID.randomUUID(), 1, "stale-generation-token", "step-1", 1,
                "SUCCEEDED", "CATALOG_CONTEXT", "context:1", "done", facts,
                null, false, NOW)));
        assertStale(() -> service.stepState(PLAN_ID, "step-1",
                new WorkflowExecutionDtos.StepStateRequest(UUID.randomUUID(), 1,
                        "stale-generation-token", "RUNNING", null)));
        assertStale(() -> service.replan(PLAN_ID, new WorkflowExecutionDtos.ReplanRequest(
                UUID.randomUUID(), 1, "stale-generation-token", 1, 2,
                List.of(new WorkflowExecutionDtos.StepRevision("step-1", List.of(),
                        List.of("done"), List.of(), "observation:step-1")))));
        assertStale(() -> service.complete(PLAN_ID, new WorkflowExecutionDtos.CompletionRequest(
                UUID.randomUUID(), 1, "stale-generation-token", "FAILED", "RECOVERY_TEST")));

        verify(mapper, never()).heartbeat(anyLong(), anyString(), any(), any());
        verify(mapper, never()).insertObservation(any());
        verify(mapper, never()).changeStep(anyLong(), anyLong(), anyString(), anyString(),
                anyInt(), any(), any());
        verify(mapper, never()).updatePlanVersion(anyLong(), anyInt(), anyInt(), any());
        verify(mapper, never()).finishExecution(anyLong(), anyString(), anyString(), any(), any());
        verify(mapper, never()).finishPlan(anyLong(), anyString(), any(), any());
    }

    @Test
    void expiredLeaseCreatesOnlyConfiguredTotalExecutionAttemptsThenFailsClosed() throws Exception {
        executionProperties.setMaxExecutionAttempts(2);
        WorkflowExecutionRecords.PlanState first = planWithRecovery(0);
        WorkflowExecutionRecords.PlanState queuedForSecondGeneration = planWithStatusAndRecovery("QUEUED", 1);
        WorkflowExecutionRecords.PlanState second = planWithRecovery(1);
        WorkflowExecutionRecords.Execution firstExecution = execution(
                1, "RUNNING", NOW.minusSeconds(1), sha256("generation-one"));
        WorkflowExecutionRecords.Execution expiredFirstExecution = execution(
                1, "EXPIRED", NOW.minusSeconds(1), sha256("generation-one"));
        WorkflowExecutionRecords.Execution secondExecution = execution(
                2, "RUNNING", NOW.minusSeconds(1), sha256("generation-two"));
        when(mapper.findExpiredPlanId(NOW)).thenReturn(Optional.of(PLAN_ID));
        when(mapper.lockPlanInternal(PLAN_ID)).thenReturn(Optional.of(first),
                Optional.of(queuedForSecondGeneration), Optional.of(second));
        when(mapper.lockLatestExecution(PLAN_ID))
                .thenReturn(Optional.of(firstExecution), Optional.of(secondExecution));
        when(mapper.latestExecution(PLAN_ID)).thenReturn(Optional.of(expiredFirstExecution));
        when(mapper.findExecutionByClaim(PLAN_ID, 10001)).thenReturn(Optional.empty());
        when(mapper.matchingOutbox(10001, PLAN_ID, 2)).thenReturn(1);
        when(mapper.steps(PLAN_ID, 41)).thenReturn(List.of(step(9101, "step-1", "[]")));
        when(mapper.insertExecution(any())).thenReturn(1);
        when(mapper.startPlan(PLAN_ID, 1, NOW)).thenReturn(1);
        when(mapper.expireExecution(anyLong(), org.mockito.ArgumentMatchers.eq(NOW))).thenReturn(1);
        when(mapper.incrementLeaseRecovery(PLAN_ID, 0, NOW)).thenReturn(1);
        when(mapper.retryPlan(PLAN_ID, NOW)).thenReturn(1);
        when(mapper.failExpiredPlan(PLAN_ID, NOW)).thenReturn(1);
        when(mapper.lockReservation(PLAN_ID)).thenReturn(Optional.of(new WorkflowExecutionRecords.Reservation(
                8101, PLAN_ID, 41, 30, 0, 0, 30, 0, "SETTLED", NOW, NOW)));

        assertThat(service.recoverOneExpired()).isTrue();
        WorkflowExecutionDtos.ClaimResponse replacement = service.claim(PLAN_ID,
                new WorkflowExecutionDtos.ClaimRequest("10001", "workflow-consumer", "worker-b",
                        "p3-worker-v1", 2, 1));
        assertThat(replacement.claimed()).isTrue();
        assertThat(replacement.executionNo()).isEqualTo(2);
        assertThat(replacement.executionToken()).startsWith("v1.");
        assertThat(service.recoverOneExpired()).isTrue();

        verify(mapper, times(2)).expireExecution(anyLong(), org.mockito.ArgumentMatchers.eq(NOW));
        ArgumentCaptor<String> recoveryPayload = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertOutbox(anyLong(), org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(PLAN_ID), org.mockito.ArgumentMatchers.eq(2),
                anyString(), anyString(), recoveryPayload.capture(), anyString(), org.mockito.ArgumentMatchers.eq(NOW));
        JsonNode recovery = new ObjectMapper().readTree(recoveryPayload.getValue());
        assertThat(recovery.path("resumeOnly").asBoolean()).isTrue();
        assertThat(recovery.path("attempt").asInt()).isEqualTo(replacement.executionNo());
        verify(mapper, times(1)).insertOutbox(anyLong(), anyLong(), anyLong(), anyInt(),
                anyString(), anyString(), anyString(), anyString(), any());
        verify(mapper).failExpiredPlan(PLAN_ID, NOW);
        verify(mapper, never()).incrementLeaseRecovery(PLAN_ID, 1, NOW);
    }

    @Test
    void prepareActivationAllocatesExistingWorkflowFundingAndSuspendsExecution() {
        assertPrepareActivation(false);
    }

    @Test
    void fundedPlanCanActivateLaterStepAfterItsOriginalQuoteExpires() {
        assertPrepareActivation(true);
    }

    private void assertPrepareActivation(boolean quoteExpiredAfterStart) {
        UUID eventId = UUID.fromString("92345678-1234-4234-8234-123456789abc");
        String token = "workflow-execution-token";
        WorkflowExecutionRecords.PlanState plan = new WorkflowExecutionRecords.PlanState(
                PLAN_ID, 41, 501, 101, 102L, 1, "RUNNING", 30, 50,
                4, 1, 4, 4, 2, NOW.plusHours(1), null, null,
                quoteExpiredAfterStart ? NOW.minusMinutes(2) : NOW, null, null, 0);
        WorkflowExecutionRecords.StepState step = prepareStep(null, null, null, null, "RUNNING",
                quoteExpiredAfterStart ? NOW.minusMinutes(1) : NOW.plusMinutes(20));
        WorkflowExecutionRecords.StepState waiting = prepareStep(9201L, eventId,
                "request-hash", null, "WAITING");
        WorkflowExecutionRecords.Execution execution = new WorkflowExecutionRecords.Execution(
                10001, PLAN_ID, 41, 501, 1, 1, 7001, "RUNNING", "consumer", "worker",
                "bundle", sha256(token), "{}", NOW.plusMinutes(2), NOW, 0, null,
                NOW, null, NOW, NOW);
        TaskRecords.Task task = new TaskRecords.Task(9201, 41, 101, 102L, 501, 7101,
                "IMAGE", "QUEUED", "QUEUED", 1, "{}", "a".repeat(64), 6001,
                10, 0, "{}", null, "WORKFLOW_STEP_ACTIVATE", eventId,
                "request-hash", NOW, null, null, NOW, NOW);
        when(mapper.lockStep(PLAN_ID, "step-1"))
                .thenReturn(Optional.of(step), Optional.of(waiting));
        when(mapper.lockLatestExecution(PLAN_ID)).thenReturn(Optional.of(execution));
        when(mapper.lockPlanInternal(PLAN_ID)).thenReturn(Optional.of(plan));
        when(mapper.incompleteDependencies(PLAN_ID, step.id())).thenReturn(0);
        when(mapper.lockReservation(PLAN_ID)).thenReturn(Optional.of(
                new WorkflowExecutionRecords.Reservation(8101, PLAN_ID, 41,
                        30, 30, 0, 0, 0, "FROZEN", NOW, NOW)));
        when(tasks.activateWorkflowTask(any())).thenReturn(task);
        when(mapper.allocateReservation(PLAN_ID, 41, 10, NOW)).thenReturn(1);
        when(mapper.bindTask(org.mockito.ArgumentMatchers.eq(step.id()),
                org.mockito.ArgumentMatchers.eq(PLAN_ID), org.mockito.ArgumentMatchers.eq(task.id()),
                org.mockito.ArgumentMatchers.eq(eventId), anyString(),
                org.mockito.ArgumentMatchers.eq("a".repeat(64)),
                org.mockito.ArgumentMatchers.eq(NOW))).thenReturn(1);
        when(mapper.waitForTask(execution.id(), sha256(token), NOW)).thenReturn(1);
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(account(70, 30)));

        WorkflowExecutionDtos.TaskActivationResponse response = service.activateTask(
                PLAN_ID, "step-1", new WorkflowExecutionDtos.TaskActivationRequest(
                        eventId, 1, token, 1, "7101"));

        assertThat(response.accepted()).isTrue();
        assertThat(response.taskId()).isEqualTo("9201");
        assertThat(response.waitingForTask()).isTrue();
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper).allocateReservation(PLAN_ID, 41, 10, NOW);
        verify(mapper).waitForTask(execution.id(), sha256(token), NOW);
        ArgumentCaptor<TaskControlService.WorkflowTaskActivation> activation =
                ArgumentCaptor.forClass(TaskControlService.WorkflowTaskActivation.class);
        verify(tasks).activateWorkflowTask(activation.capture());
        assertThat(activation.getValue().planStartedAt()).isEqualTo(plan.executionStartedAt());
        assertThat(activation.getValue().now()).isEqualTo(NOW);
    }

    @Test
    void expiredQuoteCannotStartOrFreezeEvenWithPreviouslyConfirmedPlan() {
        when(mapper.lockPlan(PLAN_ID, 41)).thenReturn(Optional.of(plan("CONFIRMED")));
        when(plans.lockConfirmation(PLAN_ID, 41)).thenReturn(Optional.of(confirmation()));
        when(mapper.steps(PLAN_ID, 41)).thenReturn(List.of(
                prepareStep(null, null, null, null, "PENDING", NOW)));

        assertThatThrownBy(() -> service.start(user(), PLAN_ID, uuid().toString(),
                new WorkflowExecutionDtos.StartRequest(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.TASK_CONFIRMATION_MISMATCH));

        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).queuePlan(anyLong(), anyLong(), anyInt(), any(), anyString(), any());
    }

    @Test
    void startRevalidatesExactDraftBeforeFreezingAuthorizedPoints() {
        when(mapper.lockPlan(PLAN_ID, 41)).thenReturn(Optional.of(plan("CONFIRMED")));
        when(plans.lockConfirmation(PLAN_ID, 41)).thenReturn(Optional.of(confirmation()));
        when(mapper.steps(PLAN_ID, 41)).thenReturn(List.of(prepareStep(null, null, null, null, "PENDING")));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH))
                .when(tasks).verifyWorkflowDrafts(org.mockito.ArgumentMatchers.eq(41L),
                        org.mockito.ArgumentMatchers.eq(501L), any(), org.mockito.ArgumentMatchers.eq(NOW));

        assertThatThrownBy(() -> service.start(user(), PLAN_ID, uuid().toString(),
                new WorkflowExecutionDtos.StartRequest(1))).isInstanceOf(BusinessException.class);
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
    }

    private WorkflowExecutionRecords.PlanState plan(String status) {
        return new WorkflowExecutionRecords.PlanState(PLAN_ID, 41, 501, 1, status, 30, 50,
                4, 1, 4, 4, 2, NOW.plusHours(1), null, null,
                "CONFIRMED".equals(status) ? null : NOW.minusMinutes(1), null, null);
    }

    private WorkflowExecutionRecords.PlanState planWithContext(String status, String hash) {
        return new WorkflowExecutionRecords.PlanState(PLAN_ID, 41, 501, 101, 102L, 1,
                status, 30, 50, 4, 1, 4, 4, 2, NOW.plusHours(1), null, null,
                NOW.minusMinutes(1), null, null, 0, "workflow goal", "{}", hash, 1, false);
    }

    private WorkflowPlanRecords.Confirmation confirmation() {
        return new WorkflowPlanRecords.Confirmation(8001, PLAN_ID, 41, 501, 1, "a".repeat(64),
                50, "CONSUMED", NOW.plusMinutes(20), uuid(), "b".repeat(64), NOW.minusMinutes(1),
                null, NOW.minusMinutes(2));
    }

    private WorkflowExecutionRecords.PointAccount account(int available, int frozen) {
        return new WorkflowExecutionRecords.PointAccount(41, available, frozen, 1, NOW, NOW);
    }

    private WorkflowExecutionRecords.StepState step(long id, String key, String depends) {
        return new WorkflowExecutionRecords.StepState(id, PLAN_ID, 41, 501, key,
                key.endsWith("1") ? 1 : 2, "QUERY", "context.catalog.inspect", "1", "QUERY",
                depends, "[\"done\"]", "[]", "observation:" + key, null, null, null,
                0, "PENDING", 0, null, null, null, null);
    }

    private WorkflowExecutionRecords.Execution execution(String tokenHash) {
        return execution(1, "RUNNING", NOW.plusMinutes(2), tokenHash);
    }

    private WorkflowExecutionRecords.Execution execution(int executionNo, String status,
                                                          OffsetDateTime lease, String tokenHash) {
        return new WorkflowExecutionRecords.Execution(10000 + executionNo, PLAN_ID, 41, 501, 1,
                executionNo, 7000 + executionNo, status, "consumer", "worker", "bundle", tokenHash,
                "{}", lease, NOW, 0, null, NOW, null, NOW, NOW);
    }

    private WorkflowExecutionRecords.PlanState planWithRecovery(int recoveryCount) {
        return planWithStatusAndRecovery("RUNNING", recoveryCount);
    }

    private WorkflowExecutionRecords.PlanState planWithStatusAndRecovery(String status, int recoveryCount) {
        return new WorkflowExecutionRecords.PlanState(PLAN_ID, 41, 501, 101, 102L, 1,
                status, 30, 50, 4, 1, 4, 4, 2, NOW.plusHours(1), null, null,
                NOW.minusMinutes(1), null, null, recoveryCount);
    }

    private void assertStale(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_ALREADY_CLAIMED));
    }

    private WorkflowExecutionRecords.StepState prepareStep(
            Long taskId, UUID activationEventId, String activationRequestHash,
            String resultRef, String status) {
        return prepareStep(taskId, activationEventId, activationRequestHash, resultRef, status, NOW.plusMinutes(20));
    }

    private WorkflowExecutionRecords.StepState prepareStep(
            Long taskId, UUID activationEventId, String activationRequestHash,
            String resultRef, String status, OffsetDateTime quoteExpiresAt) {
        return new WorkflowExecutionRecords.StepState(9101, PLAN_ID, 41, 501, "step-1", 1,
                "PREPARE", "task_draft.propose", "1", "PREPARE", "[]", "[\"done\"]",
                "[]", "draft:7101", 7101L, "IMAGE", 6001L, 10, status, 1, taskId,
                null, NOW, null, "a".repeat(64), quoteExpiresAt, activationEventId,
                activationRequestHash, resultRef, taskId == null ? null : "QUEUED");
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AuthenticatedAccount user() {
        return new AuthenticatedAccount(41, "test001", AccountRole.USER, 1, AccountStatus.ACTIVE,
                null, null, null);
    }

    private InternalRunContextDtos.RunContext runContext() {
        return new InternalRunContextDtos.RunContext(
                "11111111-2222-4333-8444-555555555555", "501", 1,
                null, null, List.of(), List.of());
    }

    private UUID uuid() { return UUID.fromString("12345678-1234-4234-8234-123456789abc"); }
}
