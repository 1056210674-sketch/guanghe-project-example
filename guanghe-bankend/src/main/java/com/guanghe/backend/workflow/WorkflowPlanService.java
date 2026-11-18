package com.guanghe.backend.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.conversation.context.RunContextService;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskConfirmationTokenCodec;
import com.guanghe.backend.task.TaskControlService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class WorkflowPlanService {
    private static final String WORKFLOW_DRAFT_SCOPE = "WORKFLOW_PLAN_DRAFT_CREATE";
    private static final Set<String> STATUSES = Set.of(
            "DRAFT", "WAITING_CONFIRMATION", "CONFIRMED", "QUEUED", "RUNNING",
            "WAITING_INPUT", "SUCCEEDED", "FAILED", "RECONFIRMATION_REQUIRED", "ABANDONED");
    private static final Map<String, ToolPolicy> TOOLS = Map.of(
            "context.catalog.inspect", new ToolPolicy(Set.of("QUERY"), "QUERY"),
            "context.memory.search", new ToolPolicy(Set.of("QUERY"), "QUERY"),
            "context.task_workspace.inspect", new ToolPolicy(Set.of("QUERY"), "QUERY"),
            "analysis.competitor.compare", new ToolPolicy(Set.of("ANALYZE"), "ANALYZE"),
            "analysis.evidence.aggregate", new ToolPolicy(Set.of("ANALYZE", "SYNTHESIZE"), "ANALYZE"),
            "task_draft.propose", new ToolPolicy(Set.of("PREPARE"), "PREPARE")
    );

    private final WorkflowPlanMapper mapper;
    private final ConversationMapper conversations;
    private final RunContextService runContexts;
    private final WorkflowAuthorizedContextService authorizedContexts;
    private final TaskControlService tasks;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final PaidTaskProperties properties;
    private final TaskConfirmationTokenCodec confirmationTokens;
    private final ObservabilitySupport observability;
    private final WorkflowExecutionProperties workflowExecution;
    private com.guanghe.backend.agentplatform.AgentPlatformService agentPlatform;
    private com.guanghe.backend.agentplatform.AgentRuntimeCatalog runtimeCatalog = new com.guanghe.backend.agentplatform.AgentRuntimeCatalog();
    @Autowired(required=false)
    void setAgentPlatform(com.guanghe.backend.agentplatform.AgentPlatformService value) { agentPlatform=value; }
    @Autowired
    void setRuntimeCatalog(com.guanghe.backend.agentplatform.AgentRuntimeCatalog value) { runtimeCatalog=java.util.Objects.requireNonNull(value); }

    @Autowired
    public WorkflowPlanService(WorkflowPlanMapper mapper, ConversationMapper conversations,
                               RunContextService runContexts, WorkflowAuthorizedContextService authorizedContexts,
                               TaskControlService tasks,
                               SnowflakeIdGenerator ids, ObjectMapper json,
                               PaidTaskProperties properties, ObservabilitySupport observability,
                               WorkflowExecutionProperties workflowExecution) {
        this.mapper = mapper;
        this.conversations = conversations;
        this.runContexts = runContexts;
        this.authorizedContexts = authorizedContexts;
        this.tasks = tasks;
        this.ids = ids;
        this.json = json;
        this.properties = properties;
        this.confirmationTokens = new TaskConfirmationTokenCodec(properties);
        this.observability = observability;
        this.workflowExecution = workflowExecution;
    }

    WorkflowPlanService(WorkflowPlanMapper mapper, ConversationMapper conversations,
                        RunContextService runContexts, TaskControlService tasks,
                        SnowflakeIdGenerator ids, ObjectMapper json,
                        PaidTaskProperties properties) {
        this(mapper, conversations, runContexts, new WorkflowAuthorizedContextService(json), tasks, ids, json, properties,
                ObservabilitySupport.noop(), new WorkflowExecutionProperties());
    }

    @Transactional
    public WorkflowPlanDtos.Detail propose(UUID runId, String contextRef,
                                            WorkflowPlanDtos.ProposalRequest request) {
        InternalRunContextDtos.RunContext runContext = runContexts.get(runId, contextRef);
        AgentRunRecord run = conversations.findRun(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        long conversationId = positiveLong(request.conversationId());
        if (run.conversationId() != conversationId || run.selectedSpaceId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String goal = normalizedSummary(request.goalSummary(), 500);
        List<ValidatedStep> steps = validateSteps(request.steps());
        validateBudget(request.budget(), steps.size(),
                (int) steps.stream().filter(step -> "PREPARE".equals(step.type())).count());
        String requestHash = proposalHash(runId, request.proposalId(), conversationId, goal,
                request.budget(), steps);

        mapper.lockUser(run.userId());
        if(agentPlatform==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        var agent=runtimeCatalog.authorize(runContext.agentIdentity(),
                agentPlatform.assertTaskConfirmationAllowed(run.userId(),conversationId));
        if(!runtimeCatalog.permits(agent,"workflow_plan.propose")) throw new BusinessException(ErrorCode.FORBIDDEN);
        for(var step:steps) {
            if(!runtimeCatalog.permits(agent,step.toolName())) throw new BusinessException(ErrorCode.FORBIDDEN);
            if(!"PREPARE".equals(step.type())) continue;
            String capability=switch(step.taskDraft().taskType()) {
                case "GEO" -> "geo.monitor";
                case "ARTICLE" -> "article.generate";
                case "IMAGE" -> "image.generate";
                case "VIDEO" -> "video.generate";
                default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            };
            if(!agent.capabilities().contains(capability)) throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        WorkflowPlanRecords.Plan replay = mapper.findByProposal(run.userId(), request.proposalId()).orElse(null);
        if (replay != null) {
            if (!constantEquals(replay.requestHash(), requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return detail(replay);
        }
        WorkflowPlanRecords.Plan existingRun = mapper.findByRun(runId).orElse(null);
        if (existingRun != null) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);

        OffsetDateTime now = now();
        if (!request.budget().deadlineAt().isAfter(now.plusMinutes(1))
                || request.budget().deadlineAt().isAfter(now.plusDays(30))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        WorkflowAuthorizedContextService.FrozenContext frozenContext = authorizedContexts.freeze(
                runContext, runId, conversationId, run.selectedSpaceId(), run.selectedProductId());
        long planId = ids.nextId();
        List<PreparedStep> prepared = new ArrayList<>();
        long totalQuotedPoints = 0;
        OffsetDateTime confirmationExpiresAt = now.plus(properties.getConfirmationTtl());
        for (ValidatedStep step : steps) {
            TaskControlService.WorkflowPreparedDraft draft = null;
            if ("PREPARE".equals(step.type())) {
                UUID draftKey = UUID.nameUUIDFromBytes(("workflow-plan|" + request.proposalId()
                        + "|" + step.stepId()).getBytes(StandardCharsets.UTF_8));
                draft = tasks.prepareWorkflowDraft(run.userId(), conversationId,
                        step.taskDraft().taskType(), run.selectedSpaceId(), run.selectedProductId(),
                        step.taskDraft().parameterSchemaVersion(), step.taskDraft().parameters(), draftKey);
                requireDraftBinding(draft, run, conversationId);
                totalQuotedPoints = Math.addExact(totalQuotedPoints, draft.quotedPoints());
                if (draft.expiresAt().isBefore(confirmationExpiresAt)) {
                    confirmationExpiresAt = draft.expiresAt();
                }
            }
            prepared.add(new PreparedStep(step, draft));
        }
        if (totalQuotedPoints <= 0 || totalQuotedPoints > request.budget().maxPointCost()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        int quotedPoints = Math.toIntExact(totalQuotedPoints);
        if (confirmationExpiresAt.isAfter(request.budget().deadlineAt())) {
            confirmationExpiresAt = request.budget().deadlineAt();
        }
        WorkflowPlanRecords.Plan plan = new WorkflowPlanRecords.Plan(planId, run.userId(), conversationId,
                runId, run.selectedSpaceId(), run.selectedProductId(), request.proposalId(), requestHash,
                goal, "WAITING_CONFIRMATION", 1, steps.size(), quotedPoints,
                request.budget().maxSteps(), request.budget().maxReplans(),
                request.budget().maxModelCalls(), request.budget().maxToolCalls(),
                request.budget().maxProviderCalls(), request.budget().maxPointCost(),
                request.budget().deadlineAt(), confirmationExpiresAt, null, now, now, frozenContext.json(),
                frozenContext.hash(), frozenContext.schemaVersion(), false);
        requireOne(mapper.insertPlan(plan));
        for (PreparedStep item : prepared) {
            ValidatedStep step = item.step();
            TaskControlService.WorkflowPreparedDraft draft = item.draft();
            WorkflowPlanRecords.Step stored = new WorkflowPlanRecords.Step(ids.nextId(), planId,
                    run.userId(), conversationId, step.stepId(), step.ordinal(), step.type(),
                    step.toolName(), "1", step.risk(), canonical(step.dependsOn()),
                    canonical(step.successCriteria()), canonical(step.inputRefs()), step.outputRef(),
                    draft == null ? null : draft.draftId(), draft == null ? null : draft.taskType(),
                    draft == null ? null : draft.pricingVersionId(), draft == null ? 0 : draft.quotedPoints(),
                    "PENDING", now);
            requireOne(mapper.insertStep(stored));
        }
        String token = confirmationTokens.workflowToken(planId, run.userId(), 1,
                request.budget().maxPointCost(), confirmationExpiresAt);
        requireOne(mapper.insertConfirmation(new WorkflowPlanRecords.Confirmation(ids.nextId(), planId,
                run.userId(), conversationId, 1, confirmationTokens.sha256(token),
                request.budget().maxPointCost(), "AVAILABLE", confirmationExpiresAt,
                null, null, null, null, now)));
        requireOne(mapper.insertEvent(new WorkflowPlanRecords.Event(ids.nextId(), planId,
                run.userId(), conversationId, 1, "PLAN_PROPOSED", null, "DRAFT", null, now)));
        requireOne(mapper.insertEvent(new WorkflowPlanRecords.Event(ids.nextId(), planId,
                run.userId(), conversationId, 2, "PLAN_WAITING_CONFIRMATION", "DRAFT",
                "WAITING_CONFIRMATION", null, now)));
        return detail(plan);
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkflowPlanDtos.Summary> list(AuthenticatedAccount account, String status,
                                                       int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        String normalizedStatus = status == null ? null : status.strip().toUpperCase(java.util.Locale.ROOT);
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus)) invalid();
        long offset = (long) (page - 1) * size;
        List<WorkflowPlanDtos.Summary> items = mapper.list(userId, normalizedStatus, offset, size)
                .stream().map(this::summary).toList();
        return PageResponse.of(items, page, size, mapper.count(userId, normalizedStatus));
    }

    @Transactional(readOnly = true)
    public WorkflowPlanDtos.Detail get(AuthenticatedAccount account, long planId) {
        long userId = CatalogAccess.requireUser(account);
        return detail(mapper.find(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    @Transactional
    public WorkflowPlanDtos.Detail abandon(AuthenticatedAccount account, long planId, int version) {
        long userId = CatalogAccess.requireUser(account);
        WorkflowPlanRecords.Plan plan = mapper.lock(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if ("ABANDONED".equals(plan.status())) return detail(plan);
        if (plan.version() != version) throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        if (!("DRAFT".equals(plan.status()) || "WAITING_CONFIRMATION".equals(plan.status()))) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        List<Long> draftIds = mapper.steps(planId, userId).stream()
                .map(WorkflowPlanRecords.Step::taskDraftId).filter(java.util.Objects::nonNull).toList();
        OffsetDateTime now = now();
        tasks.abandonWorkflowDrafts(userId, draftIds, now);
        mapper.invalidateConfirmation(planId, userId, now);
        if (mapper.abandon(planId, userId, version, now) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        requireOne(mapper.insertEvent(new WorkflowPlanRecords.Event(ids.nextId(), planId, userId,
                plan.conversationId(), 3, "PLAN_ABANDONED", plan.status(), "ABANDONED", null, now)));
        return detail(mapper.find(planId, userId).orElseThrow());
    }

    @Transactional
    public WorkflowPlanDtos.ConfirmationAuthorization confirmationPreview(
            AuthenticatedAccount account, long planId, int planVersion) {
        return observability.observe("java.workflow.confirmation.preview", "PREVIEW", span -> {
            span.tag("planIdRef", observability.reference(planId));
            span.tag("planVersion", planVersion);
        }, () -> confirmationPreviewInternal(account, planId, planVersion));
    }

    private WorkflowPlanDtos.ConfirmationAuthorization confirmationPreviewInternal(
            AuthenticatedAccount account, long planId, int planVersion) {
        long userId = CatalogAccess.requireUser(account);
        WorkflowPlanRecords.Plan plan = mapper.lock(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (plan.version() != planVersion) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        if (!"WAITING_CONFIRMATION".equals(plan.status())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        WorkflowPlanRecords.Confirmation confirmation = mapper.lockConfirmation(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_CONFIRMATION_EXPIRED));
        OffsetDateTime now = now();
        if (!"AVAILABLE".equals(confirmation.status()) || !now.isBefore(confirmation.expiresAt())
                || confirmation.planVersion() != planVersion) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_EXPIRED);
        }
        tasks.verifyWorkflowDrafts(userId, plan.conversationId(), draftExpectations(plan, userId), now);
        String token = confirmationTokens.workflowToken(plan.id(), userId, planVersion,
                plan.maxPointCost(), confirmation.expiresAt());
        if (!constantEquals(confirmation.tokenHash(), confirmationTokens.sha256(token))) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        }
        return new WorkflowPlanDtos.ConfirmationAuthorization(Long.toString(plan.id()), planVersion,
                plan.totalQuotedPoints(), plan.maxPointCost(), confirmation.expiresAt(), false, token);
    }

    @Transactional
    public WorkflowPlanDtos.ConfirmResponse confirm(AuthenticatedAccount account, long planId,
                                                     String idempotencyHeader,
                                                     WorkflowPlanDtos.ConfirmRequest request) {
        return observability.observe("java.workflow.confirm", "CONFIRM", span -> {
            span.tag("planIdRef", observability.reference(planId));
            span.tag("planVersion", request.planVersion());
        }, () -> confirmInternal(account, planId, idempotencyHeader, request));
    }

    private WorkflowPlanDtos.ConfirmResponse confirmInternal(AuthenticatedAccount account, long planId,
                                                              String idempotencyHeader,
                                                              WorkflowPlanDtos.ConfirmRequest request) {
        long userId = CatalogAccess.requireUser(account);
        UUID key = uuid(idempotencyHeader);
        String tokenHash = confirmationTokens.sha256(request.confirmationToken());
        String requestHash = confirmationRequestHash(planId, request.planVersion(),
                request.authorizedMaxPoints(), tokenHash);
        mapper.lockUser(userId);
        WorkflowPlanRecords.Confirmation replay = mapper.findConfirmationByIdempotency(userId, key)
                .orElse(null);
        if (replay != null) {
            if (replay.planId() != planId || !constantEquals(replay.requestHash(), requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return confirmedResponse(replay);
        }
        WorkflowPlanRecords.Plan plan = mapper.lock(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (plan.version() != request.planVersion()) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        if (!"WAITING_CONFIRMATION".equals(plan.status())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        WorkflowPlanRecords.Confirmation confirmation = mapper.lockConfirmation(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_CONFIRMATION_EXPIRED));
        OffsetDateTime now = now();
        if (!"AVAILABLE".equals(confirmation.status()) || !now.isBefore(confirmation.expiresAt())) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_EXPIRED);
        }
        if (confirmation.planVersion() != request.planVersion()
                || !constantEquals(confirmation.tokenHash(), tokenHash)) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        }
        if (request.authorizedMaxPoints() < plan.totalQuotedPoints()
                || request.authorizedMaxPoints() > plan.maxPointCost()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        tasks.verifyWorkflowDrafts(userId, plan.conversationId(), draftExpectations(plan, userId), now);
        int prepareSteps = (int) mapper.steps(planId, userId).stream()
                .filter(step -> "PREPARE".equals(step.stepType())).count();
        if (mapper.consumeConfirmation(planId, userId, request.planVersion(),
                request.authorizedMaxPoints(), key, requestHash, now) != 1
                || mapper.authorizeWorkflowDrafts(planId, userId) != prepareSteps
                || mapper.confirm(planId, userId, request.planVersion(), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        requireOne(mapper.insertEvent(new WorkflowPlanRecords.Event(ids.nextId(), planId, userId,
                plan.conversationId(), 3, "PLAN_CONFIRMED", "WAITING_CONFIRMATION",
                "CONFIRMED", null, now)));
        return new WorkflowPlanDtos.ConfirmResponse(Long.toString(planId), request.planVersion(),
                "CONFIRMED", plan.totalQuotedPoints(), request.authorizedMaxPoints(), now,
                false, false);
    }

    private List<TaskControlService.WorkflowDraftExpectation> draftExpectations(
            WorkflowPlanRecords.Plan plan, long userId) {
        List<WorkflowPlanRecords.Step> planSteps = mapper.steps(plan.id(), userId);
        if (planSteps.size() != plan.stepCount()) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        }
        List<TaskControlService.WorkflowDraftExpectation> drafts = planSteps.stream()
                .filter(step -> step.taskDraftId() != null)
                .map(step -> new TaskControlService.WorkflowDraftExpectation(step.taskDraftId(),
                        step.taskType(), step.pricingVersionId(), step.quotedPoints()))
                .toList();
        if (drafts.size() < 2) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        }
        return drafts;
    }

    private void requireDraftBinding(TaskControlService.WorkflowPreparedDraft draft,
                                     AgentRunRecord run, long conversationId) {
        if (draft.userId() != run.userId()
                || draft.conversationId() == null || draft.conversationId() != conversationId
                || draft.spaceId() != run.selectedSpaceId()
                || !java.util.Objects.equals(draft.productId(), run.selectedProductId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private WorkflowPlanDtos.ConfirmResponse confirmedResponse(WorkflowPlanRecords.Confirmation value) {
        WorkflowPlanRecords.Plan plan = mapper.find(value.planId(), value.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        if (!"CONSUMED".equals(value.status()) || !"CONFIRMED".equals(plan.status())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return new WorkflowPlanDtos.ConfirmResponse(Long.toString(plan.id()), value.planVersion(),
                plan.status(), plan.totalQuotedPoints(), value.authorizedMaxPoints(), value.consumedAt(),
                false, false);
    }

    private String confirmationRequestHash(long planId, int planVersion, int authorizedMaxPoints,
                                           String tokenHash) {
        return sha256(canonical(Map.of("planId", Long.toString(planId), "planVersion", planVersion,
                "authorizedMaxPoints", authorizedMaxPoints, "confirmationTokenHash", tokenHash)));
    }

    private UUID uuid(String value) {
        try {
            UUID key = UUID.fromString(value);
            if (!key.toString().equals(value)) invalid();
            return key;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private List<ValidatedStep> validateSteps(List<WorkflowPlanDtos.StepProposal> proposals) {
        List<ValidatedStep> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int prepareCount = 0;
        for (int index = 0; index < proposals.size(); index++) {
            WorkflowPlanDtos.StepProposal source = proposals.get(index);
            int ordinal = index + 1;
            if (source.ordinal() != ordinal || !source.stepId().equals("step-" + ordinal)
                    || !ids.add(source.stepId())) invalid();
            ToolPolicy policy = TOOLS.get(source.toolName());
            if (policy == null || !policy.types().contains(source.type())
                    || !policy.risk().equals(source.risk()) || !"1".equals(source.toolVersion())) invalid();
            WorkflowPlanDtos.TaskDraftProposal draft = source.taskDraft();
            if ("PREPARE".equals(source.type())) {
                if (draft == null || !draft.parameters().isObject()) invalid();
                prepareCount++;
            } else if (draft != null) invalid();
            result.add(new ValidatedStep(source.stepId(), ordinal, source.type(), source.toolName(),
                    source.risk(), List.copyOf(source.dependsOn()),
                    normalizedList(source.successCriteria(), 200), List.copyOf(source.inputRefs()),
                    source.outputRef(), draft));
        }
        if (prepareCount < 2) invalid();
        validateDag(result);
        return List.copyOf(result);
    }

    private void validateDag(List<ValidatedStep> steps) {
        Map<String, ValidatedStep> byId = new HashMap<>();
        steps.forEach(step -> byId.put(step.stepId(), step));
        for (ValidatedStep step : steps) {
            Set<String> unique = new HashSet<>();
            for (String dependency : step.dependsOn()) {
                if (!byId.containsKey(dependency) || dependency.equals(step.stepId()) || !unique.add(dependency)) {
                    invalid();
                }
            }
        }
        Map<String, Integer> colors = new HashMap<>();
        for (ValidatedStep step : steps) visit(step.stepId(), byId, colors);
    }

    private void visit(String stepId, Map<String, ValidatedStep> byId, Map<String, Integer> colors) {
        int color = colors.getOrDefault(stepId, 0);
        if (color == 1) invalid();
        if (color == 2) return;
        colors.put(stepId, 1);
        for (String dependency : byId.get(stepId).dependsOn()) visit(dependency, byId, colors);
        colors.put(stepId, 2);
    }

    private void validateBudget(WorkflowPlanDtos.BudgetRequest budget, int steps, int prepares) {
        if (budget.maxSteps() < steps || budget.maxToolCalls() < steps
                || budget.maxProviderCalls() < prepares) invalid();
    }

    private WorkflowPlanDtos.Detail detail(WorkflowPlanRecords.Plan plan) {
        List<WorkflowPlanDtos.Step> steps = mapper.steps(plan.id(), plan.userId()).stream()
                .map(this::step).toList();
        List<WorkflowPlanDtos.Event> events = mapper.events(plan.id(), plan.userId()).stream()
                .map(event -> new WorkflowPlanDtos.Event(event.sequenceNo(), event.eventType(),
                        event.fromStatus(), event.toStatus(), event.stableErrorCode(), event.createdAt()))
                .toList();
        WorkflowPlanDtos.Budget budget = new WorkflowPlanDtos.Budget(plan.maxSteps(), plan.maxReplans(),
                plan.maxModelCalls(), plan.maxToolCalls(), plan.maxProviderCalls(), plan.maxPointCost(),
                plan.deadlineAt());
        WorkflowPlanDtos.ConfirmationPreview confirmation = new WorkflowPlanDtos.ConfirmationPreview(
                1, plan.totalQuotedPoints(), plan.maxPointCost(), plan.confirmationExpiresAt(), false);
        return new WorkflowPlanDtos.Detail(Long.toString(plan.id()), Long.toString(plan.conversationId()),
                Long.toString(plan.spaceId()), plan.productId() == null ? null : Long.toString(plan.productId()),
                plan.goalSummary(), plan.status(), plan.version(), budget, plan.totalQuotedPoints(), steps,
                confirmation, events, plan.abandonedAt(), plan.createdAt(), plan.updatedAt(),
                workflowExecution.isEnabled() && workflowExecution.isCompatibleWorkerReady(),
                Set.of("QUEUED", "RUNNING", "WAITING_INPUT", "SUCCEEDED", "FAILED", "RECONFIRMATION_REQUIRED")
                        .contains(plan.status()),
                events.stream().map(WorkflowPlanDtos.Event::stableErrorCode)
                        .filter(java.util.Objects::nonNull).reduce((first, last) -> last).orElse(null));
    }

    private WorkflowPlanDtos.Step step(WorkflowPlanRecords.Step step) {
        WorkflowPlanDtos.DraftReference draft = step.taskDraftId() == null ? null
                : new WorkflowPlanDtos.DraftReference(Long.toString(step.taskDraftId()), step.taskType(),
                Long.toString(step.pricingVersionId()), step.quotedPoints());
        return new WorkflowPlanDtos.Step(step.stepKey(), step.ordinal(), step.stepType(), step.toolName(),
                step.toolVersion(), step.riskLevel(), strings(step.dependsOnJson()),
                strings(step.successCriteriaJson()), strings(step.inputRefsJson()), step.outputRef(),
                draft, step.status(), step.taskId() == null ? null : Long.toString(step.taskId()),
                step.taskStatus(), step.taskResultRef());
    }

    private WorkflowPlanDtos.Summary summary(WorkflowPlanRecords.Plan plan) {
        return new WorkflowPlanDtos.Summary(Long.toString(plan.id()), Long.toString(plan.conversationId()),
                plan.goalSummary(), plan.status(), plan.version(), plan.stepCount(), plan.totalQuotedPoints(),
                plan.maxPointCost(), plan.confirmationExpiresAt(), plan.createdAt(), plan.updatedAt());
    }

    private String proposalHash(UUID runId, UUID proposalId, long conversationId, String goal,
                                WorkflowPlanDtos.BudgetRequest budget, List<ValidatedStep> steps) {
        ObjectNode fingerprint = json.createObjectNode();
        fingerprint.put("runId", runId.toString());
        fingerprint.put("proposalId", proposalId.toString());
        fingerprint.put("conversationId", Long.toString(conversationId));
        fingerprint.put("goalSummary", goal);
        fingerprint.set("budget", json.valueToTree(budget));
        fingerprint.set("steps", json.valueToTree(steps));
        return sha256(canonical(fingerprint));
    }

    private List<String> normalizedList(List<String> source, int maxLength) {
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String value : source) {
            String normalized = normalizedSummary(value, maxLength);
            if (!unique.add(normalized)) invalid();
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private String normalizedSummary(String value, int maxLength) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        if (normalized.isBlank() || normalized.length() > maxLength
                || normalized.indexOf('\u0000') >= 0) invalid();
        return normalized;
    }

    private List<String> strings(String value) {
        try {
            List<?> values = json.readValue(value, List.class);
            return values.stream().map(String.class::cast).toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid persisted workflow list", exception);
        }
    }

    private String canonical(Object value) {
        JsonNode node = value instanceof JsonNode jsonNode ? jsonNode : json.valueToTree(value);
        return write(sort(node));
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> result.set(name, sort(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.forEach(item -> result.add(sort(item)));
            return result;
        }
        return node;
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) invalid();
            return parsed;
        } catch (NumberFormatException exception) {
            invalid();
            return 0;
        }
    }

    private void requireOne(int count) {
        if (count != 1) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
    }

    private static void invalid() {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private record ToolPolicy(Set<String> types, String risk) { }
    private record ValidatedStep(String stepId, int ordinal, String type, String toolName,
                                 String risk, List<String> dependsOn,
                                 List<String> successCriteria, List<String> inputRefs,
                                 String outputRef, WorkflowPlanDtos.TaskDraftProposal taskDraft) { }
    private record PreparedStep(ValidatedStep step, TaskControlService.WorkflowPreparedDraft draft) { }
}
