package com.guanghe.backend.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.agentplatform.*;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.context.RunContextService;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskConfirmationTokenCodec;
import com.guanghe.backend.task.TaskControlService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowPlanServiceTests {
    private static final UUID RUN_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID PROPOSAL_ID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 10, 8, 0, 0, 0, ZoneOffset.UTC);
    @Mock WorkflowPlanMapper mapper;
    @Mock ConversationMapper conversations;
    @Mock RunContextService runContexts;
    @Mock WorkflowAuthorizedContextService authorizedContexts;
    @Mock TaskControlService tasks;
    @Mock SnowflakeIdGenerator ids;
    @Mock AgentPlatformService agentPlatform;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private WorkflowPlanService service;
    private TaskConfirmationTokenCodec tokenCodec;
    private final List<WorkflowPlanRecords.Step> storedSteps = new ArrayList<>();
    private final List<WorkflowPlanRecords.Event> storedEvents = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        PaidTaskProperties properties = new PaidTaskProperties();
        properties.setTokenSecret("test-only-workflow-confirmation-secret-material");
        properties.setConfirmationTtl(java.time.Duration.ofMinutes(10));
        tokenCodec = new TaskConfirmationTokenCodec(properties);
        service = new WorkflowPlanService(mapper, conversations, runContexts, authorizedContexts,
                tasks, ids, json, properties, com.guanghe.backend.common.observability.ObservabilitySupport.noop(),
                new WorkflowExecutionProperties()) {
            @Override OffsetDateTime now() { return NOW; }
        };
        ObjectNode catalogue=(ObjectNode)json.readTree(AgentRuntimeCatalog.class.getResourceAsStream("/contracts/agents/runtime-catalog.json"));
        ObjectNode registration=((ObjectNode)catalogue.path("agents").get(0)).deepCopy();
        registration.put("agentKey","workflow-fixture").put("agentVersion","2.0.0");
        registration.putObject("legacyCapabilityGrants");
        ((com.fasterxml.jackson.databind.node.ArrayNode)registration.path("capabilities")).add("image.generate").add("video.generate");
        ((com.fasterxml.jackson.databind.node.ArrayNode)catalogue.path("agents")).add(registration);
        var runtime=new AgentRuntimeCatalog(json.writeValueAsBytes(catalogue));
        service.setRuntimeCatalog(runtime);
        service.setAgentPlatform(agentPlatform);
        var identity=runtime.freeze(new AgentPlatformDtos.AgentIdentity("91","workflow-fixture","2.0.0","Workflow","601",
                List.of("chat","resources.read","geo.read","geo.monitor","article.generate","image.generate","video.generate","task.prepare")));
        when(agentPlatform.assertTaskConfirmationAllowed(41,501)).thenReturn(identity);
        AtomicLong sequence = new AtomicLong(9000);
        when(ids.nextId()).thenAnswer(invocation -> sequence.incrementAndGet());
        when(conversations.findRun(RUN_ID)).thenReturn(Optional.of(run()));
        ObjectNode frozenContext=json.valueToTree(runContext());
        frozenContext.set("agentIdentity",json.valueToTree(identity));
        when(runContexts.get(RUN_ID, "opaque-context-ref")).thenReturn(json.treeToValue(frozenContext,InternalRunContextDtos.RunContext.class));
        when(authorizedContexts.freeze(any(), eq(RUN_ID), eq(501L), eq(601L), eq(701L)))
                .thenReturn(new WorkflowAuthorizedContextService.FrozenContext(
                        "{\"contextVersion\":1}", "c".repeat(64), 1, 64));
        when(mapper.findByProposal(41, PROPOSAL_ID)).thenReturn(Optional.empty());
        when(mapper.findByRun(RUN_ID)).thenReturn(Optional.empty());
        when(mapper.insertPlan(any())).thenReturn(1);
        when(mapper.insertStep(any())).thenAnswer(invocation -> {
            storedSteps.add(invocation.getArgument(0)); return 1;
        });
        when(mapper.insertEvent(any())).thenAnswer(invocation -> {
            storedEvents.add(invocation.getArgument(0)); return 1;
        });
        when(mapper.insertConfirmation(any())).thenReturn(1);
        when(mapper.authorizeWorkflowDrafts(anyLong(), eq(41L))).thenReturn(2);
        when(mapper.steps(anyLong(), eq(41L))).thenAnswer(invocation -> List.copyOf(storedSteps));
        when(mapper.events(anyLong(), eq(41L))).thenAnswer(invocation -> List.copyOf(storedEvents));
        when(tasks.prepareWorkflowDraft(eq(41L), eq(501L), any(), eq(601L), eq(701L),
                anyInt(), any(), any())).thenAnswer(invocation -> {
            String type = invocation.getArgument(2);
            return new TaskControlService.WorkflowPreparedDraft(
                    "IMAGE".equals(type) ? 9101 : 9102, type,
                    "IMAGE".equals(type) ? 8101 : 8102,
                    "IMAGE".equals(type) ? 10 : 20,
                    "a".repeat(64), NOW.plusMinutes(10), 41, 501L, 601, 701L);
        });
    }

    @Test
    void signedGeoRunCannotCreateMixedWorkflowDraftsOrReadReplay() throws Exception {
        var catalog = new AgentRuntimeCatalog();
        service.setRuntimeCatalog(catalog);
        var geo = catalog.freeze(new AgentPlatformDtos.AgentIdentity("91", "geo", "1.0.0", "GEO", "601",
                List.of("chat", "resources.read", "geo.monitor", "article.generate")));
        ObjectNode context = json.valueToTree(runContext());
        context.set("agentIdentity", json.valueToTree(geo));
        when(runContexts.get(RUN_ID, "opaque-context-ref")).thenReturn(
                json.treeToValue(context, InternalRunContextDtos.RunContext.class));
        when(agentPlatform.assertTaskConfirmationAllowed(41, 501)).thenReturn(geo);
        assertThatThrownBy(() -> service.propose(RUN_ID, "opaque-context-ref", validRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(tasks, never()).prepareWorkflowDraft(anyLong(), anyLong(), any(), anyLong(), any(), anyInt(), any(), any());
        verify(mapper, never()).findByProposal(anyLong(), any());
        verify(mapper, never()).insertPlan(any());
    }

    @Test
    void persistsTwoExistingDraftsQuoteDagAndIdempotentReplayWithoutCommitSideEffects() {
        WorkflowPlanDtos.ProposalRequest request = validRequest();
        WorkflowPlanDtos.Detail created = service.propose(RUN_ID, "opaque-context-ref", request);

        assertThat(created.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(created.totalQuotedPoints()).isEqualTo(30);
        verify(tasks).prepareWorkflowDraft(eq(41L), eq(501L), eq("ARTICLE"), eq(601L), eq(701L),
                eq(2), eq(article()), any());
        assertThat(created.confirmation().consumptionEnabled()).isFalse();
        assertThat(created.steps()).extracting(WorkflowPlanDtos.Step::type)
                .containsExactly("PREPARE", "PREPARE", "SYNTHESIZE");
        assertThat(created.steps()).extracting(step -> step.taskDraft() == null ? null : step.taskDraft().draftId())
                .containsExactly("9101", "9102", null);
        assertThat(storedEvents).extracting(WorkflowPlanRecords.Event::eventType)
                .containsExactly("PLAN_PROPOSED", "PLAN_WAITING_CONFIRMATION");

        ArgumentCaptor<WorkflowPlanRecords.Plan> plan = ArgumentCaptor.forClass(WorkflowPlanRecords.Plan.class);
        verify(mapper).insertPlan(plan.capture());
        assertThat(plan.getValue().authorizedContextSnapshotJson()).contains("contextVersion");
        assertThat(plan.getValue().authorizedContextHash()).isEqualTo("c".repeat(64));
        assertThat(plan.getValue().authorizedContextSchemaVersion()).isEqualTo(1);
        assertThat(plan.getValue().authorizedContextDataGap()).isFalse();
        when(mapper.findByProposal(41, PROPOSAL_ID)).thenReturn(Optional.of(plan.getValue()));
        WorkflowPlanDtos.Detail replay = service.propose(RUN_ID, "opaque-context-ref", request);
        assertThat(replay.id()).isEqualTo(created.id());
        verify(tasks, org.mockito.Mockito.times(2)).prepareWorkflowDraft(
                eq(41L), eq(501L), any(), eq(601L), eq(701L), anyInt(), any(), any());

        WorkflowPlanDtos.ProposalRequest changed = new WorkflowPlanDtos.ProposalRequest(
                PROPOSAL_ID, "501", "different goal", request.budget(), request.steps());
        assertThatThrownBy(() -> service.propose(RUN_ID, "opaque-context-ref", changed))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void rejectsWorkflowDraftWhoseOwnerOrAuthorizedScopeDiffersFromRunSnapshot() {
        when(tasks.prepareWorkflowDraft(eq(41L), eq(501L), any(), eq(601L), eq(701L),
                eq(1), any(), any())).thenReturn(new TaskControlService.WorkflowPreparedDraft(
                9101, "IMAGE", 8101, 10, "a".repeat(64), NOW.plusMinutes(10),
                42, 501L, 999, 701L));

        assertValidation(validRequest());
        verify(mapper, never()).insertPlan(any());
    }

    @Test
    void rejectsCyclesCommitRiskAndInsufficientPointBudgetBeforePlanPersistence() {
        WorkflowPlanDtos.ProposalRequest valid = validRequest();
        List<WorkflowPlanDtos.StepProposal> cycle = List.of(
                prepare("step-1", 1, List.of("step-2"), "IMAGE", image()),
                prepare("step-2", 2, List.of("step-1"), "ARTICLE", article()),
                synthesize());
        assertValidation(new WorkflowPlanDtos.ProposalRequest(PROPOSAL_ID, "501", "goal",
                valid.budget(), cycle));

        WorkflowPlanDtos.StepProposal commit = new WorkflowPlanDtos.StepProposal("step-1", 1,
                "PREPARE", "task.commit", "1", "PREPARE", List.of(), List.of("done"),
                List.of("context:product"), "draft:step-1",
                new WorkflowPlanDtos.TaskDraftProposal("IMAGE", 1, image()));
        assertValidation(new WorkflowPlanDtos.ProposalRequest(PROPOSAL_ID, "501", "goal",
                valid.budget(), List.of(commit, prepare("step-2", 2, List.of("step-1"),
                "ARTICLE", article()))));

        WorkflowPlanDtos.BudgetRequest tooSmall = new WorkflowPlanDtos.BudgetRequest(
                3, 1, 4, 3, 2, 15, NOW.plusDays(1));
        assertValidation(new WorkflowPlanDtos.ProposalRequest(PROPOSAL_ID, "501", "goal",
                tooSmall, valid.steps()));
        verify(mapper, never()).insertPlan(any());
    }

    @Test
    void enforcesOwnerVersionAndAbandonsPlanDraftsOnce() {
        WorkflowPlanRecords.Plan waiting = plan("WAITING_CONFIRMATION", 1, null);
        WorkflowPlanRecords.Plan abandoned = plan("ABANDONED", 2, NOW);
        when(mapper.lock(9001, 41)).thenReturn(Optional.of(waiting));
        when(mapper.steps(9001, 41)).thenReturn(List.of(
                storedStep(9001, 9101, "IMAGE", 10), storedStep(9001, 9102, "ARTICLE", 20),
                storedNonPrepareStep(9001)));
        when(mapper.abandon(9001, 41, 1, NOW)).thenReturn(1);
        when(mapper.find(9001, 41)).thenReturn(Optional.of(abandoned));
        WorkflowPlanDtos.Detail result = service.abandon(user(41), 9001, 1);
        assertThat(result.status()).isEqualTo("ABANDONED");
        verify(tasks).abandonWorkflowDrafts(41, List.of(9101L, 9102L), NOW);

        when(mapper.find(9001, 42)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(user(42), 9001))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void issuesCleartextTokenOnlyFromPreviewAndConfirmsWithoutExecutionSideEffects() {
        WorkflowPlanRecords.Plan waiting = plan("WAITING_CONFIRMATION", 1, null);
        String token = tokenCodec.workflowToken(9001, 41, 1, 50, NOW.plusMinutes(10));
        WorkflowPlanRecords.Confirmation available = confirmation("AVAILABLE", token,
                null, null, null);
        when(mapper.lock(9001, 41)).thenReturn(Optional.of(waiting));
        when(mapper.lockConfirmation(9001, 41)).thenReturn(Optional.of(available));
        when(mapper.steps(9001, 41)).thenReturn(List.of(
                storedStep(9001, 9101, "IMAGE", 10), storedStep(9001, 9102, "ARTICLE", 20),
                storedNonPrepareStep(9001)));
        when(mapper.consumeConfirmation(eq(9001L), eq(41L), eq(1), eq(35), any(), any(), eq(NOW)))
                .thenReturn(1);
        when(mapper.confirm(9001, 41, 1, NOW)).thenReturn(1);

        WorkflowPlanDtos.ConfirmationAuthorization preview =
                service.confirmationPreview(user(41), 9001, 1);
        assertThat(preview.confirmationToken()).isEqualTo(token);
        assertThat(preview.executionEnabled()).isFalse();

        String key = "12345678-1234-4234-8234-123456789abc";
        WorkflowPlanDtos.ConfirmResponse confirmed = service.confirm(user(41), 9001, key,
                new WorkflowPlanDtos.ConfirmRequest(1, 35, token));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.executionEnabled()).isFalse();
        assertThat(confirmed.executionStarted()).isFalse();
        verify(tasks, times(2)).verifyWorkflowDrafts(eq(41L), eq(501L), any(), eq(NOW));
        assertThat(storedEvents).extracting(WorkflowPlanRecords.Event::eventType)
                .containsExactly("PLAN_CONFIRMED");
    }

    @Test
    void confirmationReplayIsStableAndChangedInputCollidesWithoutAnotherWrite() {
        String token = tokenCodec.workflowToken(9001, 41, 1, 50, NOW.plusMinutes(10));
        String keyText = "12345678-1234-4234-8234-123456789abc";
        UUID key = UUID.fromString(keyText);
        WorkflowPlanDtos.ConfirmRequest request = new WorkflowPlanDtos.ConfirmRequest(1, 35, token);

        when(mapper.findConfirmationByIdempotency(41, key)).thenReturn(Optional.empty());
        when(mapper.lock(9001, 41)).thenReturn(Optional.of(plan("WAITING_CONFIRMATION", 1, null)));
        when(mapper.lockConfirmation(9001, 41)).thenReturn(Optional.of(
                confirmation("AVAILABLE", token, null, null, null)));
        when(mapper.steps(9001, 41)).thenReturn(List.of(
                storedStep(9001, 9101, "IMAGE", 10), storedStep(9001, 9102, "ARTICLE", 20),
                storedNonPrepareStep(9001)));
        when(mapper.consumeConfirmation(eq(9001L), eq(41L), eq(1), eq(35), eq(key), any(), eq(NOW)))
                .thenReturn(1);
        when(mapper.confirm(9001, 41, 1, NOW)).thenReturn(1);
        service.confirm(user(41), 9001, keyText, request);

        ArgumentCaptor<String> requestHash = ArgumentCaptor.forClass(String.class);
        verify(mapper).consumeConfirmation(eq(9001L), eq(41L), eq(1), eq(35), eq(key),
                requestHash.capture(), eq(NOW));
        WorkflowPlanRecords.Confirmation consumed = new WorkflowPlanRecords.Confirmation(
                8001, 9001, 41, 501, 1, tokenCodec.sha256(token), 35, "CONSUMED",
                NOW.plusMinutes(10), key, requestHash.getValue(), NOW, null, NOW);
        when(mapper.findConfirmationByIdempotency(41, key)).thenReturn(Optional.of(consumed));
        when(mapper.find(9001, 41)).thenReturn(Optional.of(plan("CONFIRMED", 1, null)));

        assertThat(service.confirm(user(41), 9001, keyText, request)).isEqualTo(
                new WorkflowPlanDtos.ConfirmResponse("9001", 1, "CONFIRMED", 30, 35,
                        NOW, false, false));
        verify(mapper, times(1)).confirm(9001, 41, 1, NOW);
        verify(mapper, times(1)).insertEvent(any());

        assertThatThrownBy(() -> service.confirm(user(41), 9001, keyText,
                new WorkflowPlanDtos.ConfirmRequest(1, 36, token)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void rejectsExpiredStaleLowAuthorizationAndCrossUserConfirmation() {
        String token = tokenCodec.workflowToken(9001, 41, 1, 50, NOW.plusMinutes(10));
        when(mapper.lock(9001, 42)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.confirm(user(42), 9001,
                "12345678-1234-4234-8234-123456789abc",
                new WorkflowPlanDtos.ConfirmRequest(1, 35, token)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        when(mapper.lock(9001, 41)).thenReturn(Optional.of(plan("WAITING_CONFIRMATION", 1, null)));
        assertThatThrownBy(() -> service.confirm(user(41), 9001,
                "12345678-1234-4234-8234-123456789abc",
                new WorkflowPlanDtos.ConfirmRequest(2, 35, token)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.RESOURCE_VERSION_CONFLICT));

        when(mapper.lockConfirmation(9001, 41)).thenReturn(Optional.of(
                confirmation("AVAILABLE", token, null, null, null)));
        assertThatThrownBy(() -> service.confirm(user(41), 9001,
                "12345678-1234-4234-8234-123456789abc",
                new WorkflowPlanDtos.ConfirmRequest(1, 29, token)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.confirm(user(41), 9001,
                "12345678-1234-4234-8234-123456789abc",
                new WorkflowPlanDtos.ConfirmRequest(1, 35, "different-confirmation-token")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.TASK_CONFIRMATION_MISMATCH));

        WorkflowPlanRecords.Confirmation expired = new WorkflowPlanRecords.Confirmation(
                8001, 9001, 41, 501, 1, tokenCodec.sha256(token), 50, "AVAILABLE",
                NOW.minusSeconds(1), null, null, null, null, NOW.minusMinutes(10));
        when(mapper.lockConfirmation(9001, 41)).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.confirmationPreview(user(41), 9001, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.TASK_CONFIRMATION_EXPIRED));
    }

    private void assertValidation(WorkflowPlanDtos.ProposalRequest request) {
        assertThatThrownBy(() -> service.propose(RUN_ID, "opaque-context-ref", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private WorkflowPlanDtos.ProposalRequest validRequest() {
        List<WorkflowPlanDtos.StepProposal> steps = List.of(
                prepare("step-1", 1, List.of(), "IMAGE", image()),
                prepare("step-2", 2, List.of("step-1"), "ARTICLE", article()),
                synthesize());
        return new WorkflowPlanDtos.ProposalRequest(PROPOSAL_ID, "501", " 组合营销素材计划 ",
                new WorkflowPlanDtos.BudgetRequest(3, 1, 6, 3, 2, 50, NOW.plusDays(1)), steps);
    }

    private WorkflowPlanDtos.StepProposal prepare(String id, int ordinal, List<String> dependencies,
                                                    String type, ObjectNode parameters) {
        return new WorkflowPlanDtos.StepProposal(id, ordinal, "PREPARE", "task_draft.propose", "1",
                "PREPARE", dependencies, List.of("草稿通过Java校验"), List.of("context:product"),
                "draft:" + id, new WorkflowPlanDtos.TaskDraftProposal(
                type, "ARTICLE".equals(type) ? 2 : 1, parameters));
    }

    private WorkflowPlanDtos.StepProposal synthesize() {
        return new WorkflowPlanDtos.StepProposal("step-3", 3, "SYNTHESIZE",
                "analysis.evidence.aggregate", "1", "ANALYZE", List.of("step-1", "step-2"),
                List.of("形成组合交付摘要"), List.of("observation:step-1", "observation:step-2"),
                "observation:summary", null);
    }

    private ObjectNode image() {
        return json.createObjectNode().put("requestText", "蓝色商品主图").put("mode", "AGENT_ASSISTED")
                .put("agentSummary", "生成一张蓝色商品主图").put("aspectRatio", "1:1").put("count", 1);
    }

    private ObjectNode article() {
        return json.createObjectNode().put("mode", "AGENT_ASSISTED").put("requestText", "生成文章标题")
                .put("agentSummary", "准备文章标题候选").put("topic", "商品使用场景");
    }

    private AgentRunRecord run() {
        return new AgentRunRecord(RUN_ID, 501, 41, 301, null, 601L, 701L, "[]", "RUNNING", 1,
                "{}", "a".repeat(64), NOW.plusMinutes(5), null, 0, "{}", null,
                "1".repeat(32), null, NOW, null, NOW, NOW);
    }

    private InternalRunContextDtos.RunContext runContext() {
        return new InternalRunContextDtos.RunContext(RUN_ID.toString(), "501", 1,
                null, null, List.of(), List.of());
    }

    private WorkflowPlanRecords.Plan plan(String status, int version, OffsetDateTime abandonedAt) {
        return new WorkflowPlanRecords.Plan(9001, 41, 501, RUN_ID, 601, 701L, PROPOSAL_ID,
                "a".repeat(64), "goal", status, version, 3, 30, 3, 1, 6, 3, 2, 50,
                NOW.plusDays(1), NOW.plusMinutes(10), abandonedAt, NOW, NOW);
    }

    private WorkflowPlanRecords.Step storedStep(long planId, long draftId, String type, int points) {
        return new WorkflowPlanRecords.Step(draftId, planId, 41, 501, "step-" + (draftId - 9100),
                (int) (draftId - 9100), "PREPARE", "task_draft.propose", "1", "PREPARE", "[]",
                "[\"done\"]", "[]", "draft:step", draftId, type, 8001L, points, "PENDING", NOW);
    }

    private WorkflowPlanRecords.Step storedNonPrepareStep(long planId) {
        return new WorkflowPlanRecords.Step(9203, planId, 41, 501, "step-3", 3,
                "SYNTHESIZE", "analysis.evidence.aggregate", "1", "ANALYZE", "[]",
                "[\"done\"]", "[]", "observation:summary", null, null, null,
                0, "PENDING", NOW);
    }

    private WorkflowPlanRecords.Confirmation confirmation(String status, String token, UUID key,
                                                            String requestHash,
                                                            OffsetDateTime consumedAt) {
        return new WorkflowPlanRecords.Confirmation(8001, 9001, 41, 501, 1,
                tokenCodec.sha256(token), 50, status, NOW.plusMinutes(10), key, requestHash,
                consumedAt, null, NOW);
    }

    private AuthenticatedAccount user(long id) {
        return new AuthenticatedAccount(id, "user", AccountRole.USER, 1, AccountStatus.ACTIVE,
                null, null, null);
    }
}
