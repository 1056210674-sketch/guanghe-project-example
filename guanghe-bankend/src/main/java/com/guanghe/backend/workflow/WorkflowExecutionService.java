package com.guanghe.backend.workflow;

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
import com.guanghe.backend.common.web.TraceIdHolder;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskConfirmationTokenCodec;
import com.guanghe.backend.task.TaskControlService;
import com.guanghe.backend.task.TaskRecords;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.workflow-execution", name = "enabled", havingValue = "true")
public class WorkflowExecutionService {
    private static final Set<String> TERMINAL_PLAN = Set.of("SUCCEEDED", "FAILED", "ABANDONED");
    private final WorkflowExecutionMapper mapper;
    private final WorkflowPlanMapper plans;
    private final WorkflowExecutionProperties properties;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final TaskConfirmationTokenCodec tokens;
    private final ObservabilitySupport observability;
    private final TaskControlService tasks;
    private final WorkflowAuthorizedContextService authorizedContexts;

    @Autowired
    public WorkflowExecutionService(WorkflowExecutionMapper mapper, WorkflowPlanMapper plans,
                                    WorkflowExecutionProperties properties, SnowflakeIdGenerator ids,
                                    ObjectMapper json, PaidTaskProperties paidTaskProperties,
                                    ObservabilitySupport observability, TaskControlService tasks,
                                    WorkflowAuthorizedContextService authorizedContexts) {
        this.mapper = mapper;
        this.plans = plans;
        this.properties = properties;
        this.ids = ids;
        this.json = json;
        this.tokens = new TaskConfirmationTokenCodec(paidTaskProperties);
        this.observability = observability;
        this.tasks = tasks;
        this.authorizedContexts = authorizedContexts;
    }

    public WorkflowExecutionService(WorkflowExecutionMapper mapper, WorkflowPlanMapper plans,
                                    WorkflowExecutionProperties properties, SnowflakeIdGenerator ids,
                                    ObjectMapper json, PaidTaskProperties paidTaskProperties,
                                    ObservabilitySupport observability) {
        this(mapper, plans, properties, ids, json, paidTaskProperties, observability, null,
                new WorkflowAuthorizedContextService(json));
    }

    public WorkflowExecutionService(WorkflowExecutionMapper mapper, WorkflowPlanMapper plans,
                                    WorkflowExecutionProperties properties, SnowflakeIdGenerator ids,
                                    ObjectMapper json, PaidTaskProperties paidTaskProperties,
                                    ObservabilitySupport observability, TaskControlService tasks) {
        this(mapper, plans, properties, ids, json, paidTaskProperties, observability, tasks,
                new WorkflowAuthorizedContextService(json));
    }

    public boolean executionEnabled() {
        return properties.isEnabled() && properties.isCompatibleWorkerReady();
    }

    @Transactional
    public WorkflowExecutionDtos.StartResponse start(AuthenticatedAccount account, long planId,
                                                      String idempotencyKey,
                                                      WorkflowExecutionDtos.StartRequest request) {
        long userId = CatalogAccess.requireUser(account);
        requireAvailable();
        UUID key = uuid(idempotencyKey);
        String requestHash = sha256(planId + "|" + request.planVersion());
        WorkflowExecutionRecords.PlanState replay = mapper.findByStartKey(userId, key).orElse(null);
        if (replay != null) {
            if (!constantEquals(requestHash, replay.startRequestHash())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return startResponse(replay);
        }
        WorkflowExecutionRecords.PlanState plan = mapper.lockPlan(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (plan.startIdempotencyKey() != null) {
            if (key.equals(plan.startIdempotencyKey())
                    && constantEquals(requestHash, plan.startRequestHash())) return startResponse(plan);
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (!"CONFIRMED".equals(plan.status()) || plan.version() != request.planVersion()
                || !now().isBefore(plan.deadlineAt())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        authorizedContexts.requireExecutable(plan.authorizedContextSnapshotJson(),
                plan.authorizedContextHash(), plan.authorizedContextSchemaVersion(),
                plan.authorizedContextDataGap());
        WorkflowPlanRecords.Confirmation confirmation = plans.lockConfirmation(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH));
        if (!"CONSUMED".equals(confirmation.status()) || confirmation.planVersion() != plan.version()
                || !now().isBefore(confirmation.expiresAt())
                || confirmation.authorizedMaxPoints() < plan.totalQuotedPoints()
                || plan.totalQuotedPoints() > plan.maxPointCost()) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        }
        OffsetDateTime now = now();
        List<WorkflowExecutionRecords.StepState> prepared = mapper.steps(planId, userId).stream()
                .filter(step -> step.taskDraftId() != null).toList();
        if (prepared.stream().anyMatch(step -> step.authorizedInputHash() == null
                || step.authorizedDraftExpiresAt() == null
                || !now.isBefore(step.authorizedDraftExpiresAt()))) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        }
        // The start action accepts the frozen quote once, before any point reservation.
        tasks.verifyWorkflowDrafts(userId, plan.conversationId(), prepared.stream()
                .map(step -> new TaskControlService.WorkflowDraftExpectation(step.taskDraftId(),
                        step.taskType(), step.pricingVersionId(), step.quotedPoints())).toList(), now);
        WorkflowExecutionRecords.PointAccount accountRow = mapper.lockPointAccount(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_POINTS));
        int points = plan.totalQuotedPoints();
        if (accountRow.availablePoints() < points) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS);
        }
        requireOne(mapper.freezePoints(userId, points, now));
        requireOne(mapper.insertReservation(ids.nextId(), planId, userId, points, now));
        requireOne(mapper.insertLedger(ids.nextId(), userId, -points, points,
                accountRow.availablePoints() - points, accountRow.frozenPoints() + points,
                "WORKFLOW_FREEZE", planId, "workflow-freeze:" + planId,
                "Workflow execution authorization", now));
        requireOne(mapper.queuePlan(planId, userId, plan.version(), key, requestHash, now));
        long eventId = ids.nextId();
        ObjectNode payload = json.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("eventId", Long.toString(eventId));
        payload.put("eventType", "WORKFLOW_EXECUTION_REQUESTED");
        payload.put("planId", Long.toString(planId));
        payload.put("planVersion", plan.version());
        payload.put("attempt", 1);
        payload.put("traceparent", TraceIdHolder.outboundTraceparent());
        requireOne(mapper.insertOutbox(eventId, userId, planId, 1, properties.getExchange(),
                properties.getRoutingKey(), write(payload), payload.path("traceparent").asText(), now));
        workflowEvent(plan, "PLAN_QUEUED", "CONFIRMED", "QUEUED", null, null, null, now);
        observability.observe("workflow.start", "QUEUED", operation -> {
            operation.tag("planIdRef", observability.reference(planId));
            operation.tag("planVersion", plan.version());
            operation.tag("status", "QUEUED");
        }, () -> { });
        return new WorkflowExecutionDtos.StartResponse(Long.toString(planId), plan.version(),
                "QUEUED", true, true, 1);
    }

    @Transactional
    public WorkflowExecutionDtos.ClaimResponse claim(long planId,
                                                      WorkflowExecutionDtos.ClaimRequest request) {
        requireAvailable();
        long eventId = positiveLong(request.eventId());
        WorkflowExecutionRecords.PlanState plan = mapper.lockPlanInternal(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        WorkflowExecutionRecords.Execution replay = mapper.findExecutionByClaim(planId, eventId).orElse(null);
        if (replay != null) {
            return new WorkflowExecutionDtos.ClaimResponse(false, "DUPLICATE", replay.executionNo(), null,
                    replay.leaseExpiresAt());
        }
        if (TERMINAL_PLAN.contains(plan.status())) {
            return new WorkflowExecutionDtos.ClaimResponse(false, "TERMINAL", null, null, null);
        }
        WorkflowExecutionRecords.Execution latest = mapper.latestExecution(planId).orElse(null);
        int expectedExecutionNo = latest == null ? 1 : latest.executionNo() + 1;
        boolean initial = "QUEUED".equals(plan.status());
        boolean taskResume = "RUNNING".equals(plan.status()) && latest != null
                && "WAITING_TASK".equals(latest.status());
        if (!(initial || taskResume) || request.planVersion() != plan.version()
                || request.executionNo() != expectedExecutionNo
                || mapper.matchingOutbox(eventId, planId, request.executionNo()) != 1) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_CLAIMED);
        }
        OffsetDateTime now = now();
        OffsetDateTime lease = now.plus(properties.getLease());
        long executionId = ids.nextId();
        String token = tokens.workflowExecutionToken(executionId, planId, plan.userId(),
                request.executionNo(), request.workerId());
        mapper.readyRootSteps(planId, now);
        String snapshot = contextSnapshot(plan);
        WorkflowExecutionRecords.Execution execution = new WorkflowExecutionRecords.Execution(executionId,
                planId, plan.userId(), plan.conversationId(), plan.version(), request.executionNo(), eventId,
                "RUNNING", request.consumerName(), request.workerId(), request.supportedBundleVersion(),
                sha256(token), snapshot, lease, now, 0, null, now, null, now, now);
        requireOne(mapper.insertExecution(execution));
        if (initial) {
            requireOne(mapper.startPlan(planId, plan.version(), now));
            workflowEvent(plan, "PLAN_STARTED", "QUEUED", "RUNNING", null, null, null, now);
        } else {
            requireOne(mapper.resumePlan(planId, plan.version(), now));
        }
        return new WorkflowExecutionDtos.ClaimResponse(true, "CLAIMED", request.executionNo(), token, lease);
    }

    @Transactional(readOnly = true)
    public WorkflowExecutionDtos.ExecutionContext context(long planId, int executionNo, String token) {
        WorkflowExecutionRecords.Execution execution = current(planId, executionNo, token, false);
        WorkflowExecutionRecords.PlanState plan = mapper.findPlanInternal(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_CONTEXT_UNAVAILABLE));
        var authorized = authorizedContexts.load(plan.authorizedContextSnapshotJson(),
                plan.authorizedContextHash(), plan.authorizedContextSchemaVersion(),
                plan.authorizedContextDataGap());
        JsonNode snapshot = read(execution.contextSnapshotJson());
        if (!plan.authorizedContextHash().equals(snapshot.path("authorizedContextHash").asText())
                || plan.authorizedContextSchemaVersion()
                != snapshot.path("authorizedContextSchemaVersion").intValue()) {
            throw new BusinessException(ErrorCode.WORKFLOW_CONTEXT_UNAVAILABLE);
        }
        List<WorkflowExecutionDtos.Observation> observations = mapper.observations(planId).stream()
                .map(this::observation).toList();
        return new WorkflowExecutionDtos.ExecutionContext(Long.toString(planId), execution.planVersion(),
                execution.executionNo(), snapshot.path("plan"), nodes(snapshot.path("steps")), observations,
                json.convertValue(snapshot.path("budget"), WorkflowExecutionDtos.ExecutionContext.Budget.class),
                execution.executionNo() > 1,
                authorized, plan.authorizedContextSchemaVersion(), plan.authorizedContextHash(),
                "PLAN_SNAPSHOT");
    }

    @Transactional
    public WorkflowExecutionDtos.HeartbeatResponse heartbeat(long planId,
                                                              WorkflowExecutionDtos.HeartbeatRequest request) {
        WorkflowExecutionRecords.Execution execution = current(planId, request.executionNo(),
                request.executionToken(), true);
        OffsetDateTime now = now();
        OffsetDateTime lease = now.plus(properties.getLease());
        requireOne(mapper.heartbeat(execution.id(), sha256(request.executionToken()), lease, now));
        return new WorkflowExecutionDtos.HeartbeatResponse(true, lease);
    }

    @Transactional
    public WorkflowExecutionDtos.ObservationAccepted observe(long planId,
                                                               WorkflowExecutionDtos.ObservationRequest request) {
        WorkflowExecutionRecords.Execution execution = current(planId, request.executionNo(),
                request.executionToken(), true);
        validateObservation(request);
        String hash = sha256(write(json.valueToTree(request)));
        WorkflowExecutionRecords.Observation replay = mapper.findObservationByEvent(planId, request.eventId())
                .orElse(null);
        if (replay != null) {
            if (!constantEquals(hash, replay.requestHash())) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return new WorkflowExecutionDtos.ObservationAccepted(false, "DUPLICATE", "RUNNING", 0);
        }
        WorkflowExecutionRecords.StepState step = mapper.lockStep(planId, request.stepId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (mapper.findObservationByToolCall(planId, request.toolCallId()).isPresent()
                || mapper.findObservationByAttempt(planId, step.id(), request.stepAttempt()).isPresent()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (step.attempt() != request.stepAttempt()
                || !("RUNNING".equals(step.status()) || "WAITING".equals(step.status()))) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        OffsetDateTime now = now();
        WorkflowExecutionRecords.Observation value = new WorkflowExecutionRecords.Observation(ids.nextId(),
                planId, step.id(), execution.id(), execution.userId(), execution.conversationId(),
                request.eventId(), request.toolCallId(), hash, request.stepAttempt(), request.status(),
                request.resultType(), request.resultRef(), request.summary(), write(request.facts()),
                request.stableErrorCode(), request.retryable(), request.completedAt(), now);
        requireOne(mapper.insertObservation(value));
        String target = switch (request.status()) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "FAILED" -> "FAILED";
            case "WAITING" -> "WAITING";
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
        requireOne(mapper.changeStep(step.id(), planId, step.status(), target, step.attempt(),
                request.stableErrorCode(), now));
        readyDependents(planId, execution.userId());
        WorkflowExecutionRecords.PlanState plan = mapper.lockPlanInternal(planId).orElseThrow();
        long sequence = workflowEvent(plan, "OBSERVATION_RECORDED", "RUNNING", "RUNNING",
                request.stableErrorCode(), request.eventId(), hash, now);
        return new WorkflowExecutionDtos.ObservationAccepted(true, "RECORDED", plan.status(), sequence);
    }

    @Transactional
    public WorkflowExecutionDtos.Accepted stepState(long planId, String stepKey,
                                                     WorkflowExecutionDtos.StepStateRequest request) {
        WorkflowExecutionRecords.Execution execution = current(planId, request.executionNo(),
                request.executionToken(), true);
        String hash = sha256(write(json.valueToTree(request)));
        WorkflowExecutionDtos.Accepted replay = eventReplay(planId, request.eventId(), hash);
        if (replay != null) return replay;
        WorkflowExecutionRecords.StepState step = mapper.lockStep(planId, stepKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        String target = request.status();
        int attempt = step.attempt();
        boolean valid = "READY".equals(step.status()) && "RUNNING".equals(target);
        if (valid) attempt++;
        valid |= ("RUNNING".equals(step.status()) || "WAITING".equals(step.status()))
                && Set.of("RUNNING", "WAITING", "SUCCEEDED", "FAILED", "SKIPPED").contains(target);
        if (!valid || (Set.of("FAILED", "WAITING").contains(target)) != (request.stableErrorCode() != null)) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        OffsetDateTime now = now();
        requireOne(mapper.changeStep(step.id(), planId, step.status(), target, attempt,
                request.stableErrorCode(), now));
        readyDependents(planId, execution.userId());
        WorkflowExecutionRecords.PlanState plan = mapper.lockPlanInternal(planId).orElseThrow();
        workflowEvent(plan, "STEP_STATE_CHANGED", "RUNNING", "RUNNING", request.stableErrorCode(),
                request.eventId(), hash, now);
        return new WorkflowExecutionDtos.Accepted(true, "RECORDED", plan.status(), plan.version());
    }

    @Transactional
    public WorkflowExecutionDtos.TaskActivationResponse activateTask(
            long planId, String stepKey, WorkflowExecutionDtos.TaskActivationRequest request) {
        long draftId = positiveLong(request.taskDraftId());
        String requestHash = sha256(planId + "|" + stepKey + "|" + request.eventId() + "|"
                + request.executionNo() + "|" + request.planVersion() + "|" + draftId);
        WorkflowExecutionRecords.StepState step = mapper.lockStep(planId, stepKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (request.eventId().equals(step.activationEventId())) {
            if (!constantEquals(requestHash, step.activationRequestHash()) || step.taskId() == null) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            requireExecutionIdentity(planId, request.executionNo(), request.executionToken());
            return activationResponse(false, "DUPLICATE", planId, step, request.executionNo());
        }
        WorkflowExecutionRecords.Execution execution = current(planId, request.executionNo(),
                request.executionToken(), true);
        WorkflowExecutionRecords.PlanState plan = mapper.lockPlanInternal(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        boolean valid = plan.version() == request.planVersion()
                && execution.planVersion() == plan.version()
                && step.userId() == plan.userId() && step.conversationId() == plan.conversationId()
                && "RUNNING".equals(plan.status()) && "RUNNING".equals(step.status())
                && step.attempt() > 0 && "PREPARE".equals(step.stepType())
                && "task_draft.propose".equals(step.toolName()) && "1".equals(step.toolVersion())
                && "PREPARE".equals(step.riskLevel()) && step.taskId() == null
                && step.taskDraftId() != null && step.taskDraftId() == draftId
                && step.authorizedInputHash() != null && step.authorizedDraftExpiresAt() != null
                && plan.executionStartedAt() != null && !plan.executionStartedAt().isAfter(now())
                && plan.executionStartedAt().isBefore(step.authorizedDraftExpiresAt())
                && mapper.incompleteDependencies(planId, step.id()) == 0;
        if (!valid) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        OffsetDateTime now = now();
        WorkflowExecutionRecords.Reservation reservation = mapper.lockReservation(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        if (reservation.userId() != plan.userId() || reservation.frozenPoints() < step.quotedPoints()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        TaskRecords.Task task = tasks.activateWorkflowTask(new TaskControlService.WorkflowTaskActivation(
                planId, step.stepKey(), plan.userId(), plan.conversationId(), plan.spaceId(), plan.productId(),
                draftId, step.taskType(), step.pricingVersionId(), step.quotedPoints(),
                step.authorizedInputHash(), step.authorizedDraftExpiresAt(), request.eventId(), requestHash,
                plan.executionStartedAt(), now));
        if (mapper.allocateReservation(planId, plan.userId(), step.quotedPoints(), now) != 1
                || mapper.bindTask(step.id(), planId, task.id(), request.eventId(), requestHash,
                step.authorizedInputHash(), now) != 1
                || mapper.waitForTask(execution.id(), sha256(request.executionToken()), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        WorkflowExecutionRecords.PointAccount account = mapper.lockPointAccount(plan.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        mapper.insertLedger(ids.nextId(), plan.userId(), 0, 0, account.availablePoints(),
                account.frozenPoints(), "WORKFLOW_ALLOCATE", planId,
                "workflow-allocate:" + planId + ":" + step.id(),
                "Workflow authorization allocated to task", now);
        workflowEvent(plan, "STEP_TASK_ALLOCATED", "RUNNING", "RUNNING", null,
                request.eventId(), requestHash, now);
        workflowEvent(plan, "PLAN_WAITING_TASK", "RUNNING", "RUNNING", null,
                null, null, now);
        observability.observe("workflow.task.allocate", "WAITING", operation -> {
            operation.tag("planIdRef", observability.reference(planId));
            operation.tag("stepId", step.stepKey());
            operation.tag("taskType", step.taskType());
            operation.tag("points", step.quotedPoints());
        }, () -> { });
        observability.observe("workflow.task.wait", "WAITING", operation -> {
            operation.tag("planIdRef", observability.reference(planId));
            operation.tag("stepId", step.stepKey());
        }, () -> { });
        WorkflowExecutionRecords.StepState stored = mapper.lockStep(planId, stepKey).orElseThrow();
        return activationResponse(true, "ACTIVATED", planId, stored, execution.executionNo());
    }

    @Transactional
    public WorkflowExecutionDtos.Accepted replan(long planId, WorkflowExecutionDtos.ReplanRequest request) {
        WorkflowExecutionRecords.Execution execution = current(planId, request.executionNo(),
                request.executionToken(), true);
        String hash = sha256(write(json.valueToTree(request)));
        WorkflowExecutionDtos.Accepted replay = eventReplay(planId, request.eventId(), hash);
        if (replay != null) return replay;
        WorkflowExecutionRecords.PlanState plan = mapper.lockPlanInternal(planId).orElseThrow();
        if (execution.replanCount() >= properties.getMaxReplans()
                || request.expectedPlanVersion() != plan.version()
                || request.newPlanVersion() != plan.version() + 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        List<WorkflowExecutionRecords.StepState> currentSteps = mapper.steps(planId, plan.userId());
        Map<String, WorkflowExecutionRecords.StepState> byKey = new HashMap<>();
        currentSteps.forEach(step -> byKey.put(step.stepKey(), step));
        if (request.steps().size() != currentSteps.stream()
                .filter(step -> Set.of("PENDING", "READY").contains(step.status())).count()) invalid();
        for (WorkflowExecutionDtos.StepRevision revision : request.steps()) {
            WorkflowExecutionRecords.StepState step = byKey.get(revision.stepId());
            if (step == null || !Set.of("PENDING", "READY").contains(step.status())) invalid();
        }
        validateDag(currentSteps, request.steps());
        for (WorkflowExecutionDtos.StepRevision revision : request.steps()) {
            WorkflowExecutionRecords.StepState step = byKey.get(revision.stepId());
            requireOne(mapper.reviseStep(step.id(), planId, write(json.valueToTree(revision.dependsOn())),
                    write(json.valueToTree(revision.successCriteria())),
                    write(json.valueToTree(revision.inputRefs())), revision.outputRef()));
        }
        OffsetDateTime now = now();
        requireOne(mapper.updatePlanVersion(planId, plan.version(), request.newPlanVersion(), now));
        requireOne(mapper.incrementReplan(execution.id(), request.newPlanVersion(), now));
        workflowEvent(plan, "PLAN_REPLANNED", "RUNNING", "RUNNING", null,
                request.eventId(), hash, now);
        return new WorkflowExecutionDtos.Accepted(true, "RECORDED", "RUNNING", request.newPlanVersion());
    }

    @Transactional
    public WorkflowExecutionDtos.Accepted complete(long planId,
                                                    WorkflowExecutionDtos.CompletionRequest request) {
        String hash = sha256(write(json.valueToTree(request)));
        String stored = mapper.findEventHash(planId, request.eventId()).orElse(null);
        if (stored != null) {
            if (!constantEquals(stored, hash)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            WorkflowExecutionRecords.Execution existing = mapper.lockLatestExecution(planId).orElseThrow();
            if (existing.executionNo() != request.executionNo()
                    || !constantEquals(existing.executionTokenHash(), sha256(request.executionToken()))) {
                throw new BusinessException(ErrorCode.TASK_ALREADY_CLAIMED);
            }
            WorkflowExecutionRecords.PlanState existingPlan = mapper.lockPlanInternal(planId).orElseThrow();
            return new WorkflowExecutionDtos.Accepted(false, "DUPLICATE", existingPlan.status(),
                    existingPlan.version());
        }
        WorkflowExecutionRecords.Execution execution = current(planId, request.executionNo(),
                request.executionToken(), true);
        String planStatus;
        String executionStatus;
        String eventType;
        switch (request.status()) {
            case "SUCCEEDED" -> { planStatus = "SUCCEEDED"; executionStatus = "SUCCEEDED"; eventType = "PLAN_SUCCEEDED"; }
            case "FAILED" -> { planStatus = "FAILED"; executionStatus = "FAILED"; eventType = "PLAN_FAILED"; }
            case "WAITING_INPUT" -> { planStatus = "WAITING_INPUT"; executionStatus = "WAITING_INPUT"; eventType = "PLAN_WAITING_INPUT"; }
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (("FAILED".equals(planStatus)) != (request.stableErrorCode() != null)) invalid();
        if ("SUCCEEDED".equals(planStatus) && mapper.steps(planId, execution.userId()).stream()
                .anyMatch(step -> !Set.of("SUCCEEDED", "SKIPPED").contains(step.status()))) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        OffsetDateTime now = now();
        requireOne(mapper.finishExecution(execution.id(), sha256(request.executionToken()), executionStatus,
                request.stableErrorCode(), now));
        requireOne(mapper.finishPlan(planId, planStatus, request.stableErrorCode(), now));
        if (!"WAITING_INPUT".equals(planStatus)) releaseUnusedFunding(planId, execution.userId(), now);
        WorkflowExecutionRecords.PlanState plan = mapper.lockPlanInternal(planId).orElseThrow();
        workflowEvent(plan, eventType, "RUNNING", planStatus, request.stableErrorCode(),
                request.eventId(), hash, now);
        return new WorkflowExecutionDtos.Accepted(true, "RECORDED", planStatus, plan.version());
    }

    @Transactional
    public boolean recoverOneExpired() {
        if (!executionEnabled()) return false;
        OffsetDateTime now = now();
        Long planId = mapper.findExpiredPlanId(now).orElse(null);
        if (planId == null) return false;
        WorkflowExecutionRecords.PlanState plan = mapper.lockPlanInternal(planId).orElse(null);
        WorkflowExecutionRecords.Execution execution = mapper.lockLatestExecution(planId).orElse(null);
        if (plan == null || execution == null || !"RUNNING".equals(plan.status())
                || !"RUNNING".equals(execution.status()) || !execution.leaseExpiresAt().isBefore(now)) return false;
        requireOne(mapper.expireExecution(execution.id(), now));
        int nextAttempt = execution.executionNo() + 1;
        if (nextAttempt <= properties.getMaxExecutionAttempts()) {
            requireOne(mapper.incrementLeaseRecovery(planId, plan.leaseRecoveryCount(), now));
            requireOne(mapper.retryPlan(planId, now));
            long eventId = ids.nextId();
            ObjectNode payload = json.createObjectNode();
            payload.put("schemaVersion", 1);
            payload.put("eventId", Long.toString(eventId));
            payload.put("eventType", "WORKFLOW_EXECUTION_REQUESTED");
            payload.put("planId", Long.toString(planId));
            payload.put("planVersion", plan.version());
            payload.put("attempt", nextAttempt);
            payload.put("resumeOnly", true);
            payload.put("traceparent", TraceIdHolder.outboundTraceparent());
            requireOne(mapper.insertOutbox(eventId, plan.userId(), planId, nextAttempt,
                    properties.getExchange(), properties.getRoutingKey(), write(payload),
                    payload.path("traceparent").asText(), now));
            workflowEvent(plan, "PLAN_RETRY_QUEUED", "RUNNING", "QUEUED",
                    "WORKER_LEASE_EXPIRED", null, null, now);
        } else {
            requireOne(mapper.failExpiredPlan(planId, now));
            releaseUnusedFunding(planId, plan.userId(), now);
            workflowEvent(plan, "PLAN_FAILED", "RUNNING", "FAILED",
                    "WORKER_LEASE_EXPIRED", null, null, now);
        }
        return true;
    }

    private void releaseUnusedFunding(long planId, long userId, OffsetDateTime now) {
        WorkflowExecutionRecords.Reservation reservation = mapper.lockReservation(planId).orElse(null);
        if (reservation == null || reservation.frozenPoints() == 0) return;
        if (reservation.allocatedPoints() != 0) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        WorkflowExecutionRecords.PointAccount account = mapper.lockPointAccount(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        requireOne(mapper.releasePoints(userId, reservation.frozenPoints(), now));
        requireOne(mapper.releaseReservation(planId, reservation.frozenPoints(), now));
        requireOne(mapper.insertLedger(ids.nextId(), userId, reservation.frozenPoints(),
                -reservation.frozenPoints(), account.availablePoints() + reservation.frozenPoints(),
                account.frozenPoints() - reservation.frozenPoints(), "WORKFLOW_RELEASE", planId,
                "workflow-release:" + planId, "Unused workflow authorization released", now));
    }

    private WorkflowExecutionRecords.Execution current(long planId, int executionNo, String token,
                                                         boolean lockRow) {
        WorkflowExecutionRecords.Execution execution = (lockRow
                ? mapper.lockLatestExecution(planId) : mapper.latestExecution(planId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        OffsetDateTime now = now();
        if (execution.executionNo() != executionNo || !"RUNNING".equals(execution.status())
                || !constantEquals(execution.executionTokenHash(), sha256(token))
                || !execution.leaseExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_CLAIMED);
        }
        return execution;
    }

    private String contextSnapshot(WorkflowExecutionRecords.PlanState plan) {
        var authorized = authorizedContexts.load(plan.authorizedContextSnapshotJson(),
                plan.authorizedContextHash(), plan.authorizedContextSchemaVersion(),
                plan.authorizedContextDataGap());
        ObjectNode root = json.createObjectNode();
        ObjectNode planNode = root.putObject("plan");
        planNode.put("id", Long.toString(plan.id()));
        planNode.put("conversationId", Long.toString(plan.conversationId()));
        planNode.put("version", plan.version());
        planNode.put("status", "RUNNING");
        planNode.put("goalSummary", plan.goalSummary());
        ArrayNode steps = root.putArray("steps");
        mapper.steps(plan.id(), plan.userId()).forEach(step -> {
            ObjectNode value = steps.addObject();
            value.put("id", step.stepKey());
            value.put("ordinal", step.ordinal());
            value.put("type", step.stepType());
            value.put("toolName", step.toolName());
            value.put("toolVersion", step.toolVersion());
            value.put("risk", step.riskLevel());
            value.put("status", step.status());
            value.set("dependsOn", read(step.dependsOnJson()));
            value.set("successCriteria", read(step.successCriteriaJson()));
            value.set("inputRefs", read(step.inputRefsJson()));
            if (step.outputRef() != null) value.put("outputRef", step.outputRef());
            if (step.taskDraftId() != null) value.put("taskDraftId", Long.toString(step.taskDraftId()));
            if (step.taskId() != null) value.put("taskId", Long.toString(step.taskId()));
            if (step.taskStatus() != null) value.put("taskStatus", step.taskStatus());
            if (step.taskResultRef() != null) value.put("taskResultRef", step.taskResultRef());
        });
        ObjectNode budget = root.putObject("budget");
        budget.put("maxSteps", plan.maxSteps());
        budget.put("maxReplans", Math.min(plan.maxReplans(), properties.getMaxReplans()));
        budget.put("maxModelCalls", plan.maxModelCalls());
        budget.put("maxToolCalls", plan.maxToolCalls());
        budget.put("maxProviderCalls", plan.maxProviderCalls());
        budget.put("authorizedMaxPoints", plan.totalQuotedPoints());
        budget.put("deadlineAt", plan.deadlineAt().toString());
        root.set("authorizedContext", json.valueToTree(authorized));
        root.put("authorizedContextSchemaVersion", WorkflowAuthorizedContextService.SCHEMA_VERSION);
        root.put("authorizedContextHash", plan.authorizedContextHash());
        root.put("authorizedContextSource", "PLAN_SNAPSHOT");
        authorizedContexts.requireExecutionSize(root);
        String result = write(root);
        return result;
    }

    private void validateObservation(WorkflowExecutionDtos.ObservationRequest request) {
        if (!Set.of("SUCCEEDED", "FAILED", "WAITING").contains(request.status())
                || !request.facts().isObject()
                || write(request.facts()).getBytes(StandardCharsets.UTF_8).length > 32_768
                || ("SUCCEEDED".equals(request.status())) != (request.stableErrorCode() == null)) invalid();
    }

    private void readyDependents(long planId, long userId) {
        List<WorkflowExecutionRecords.StepState> steps = mapper.steps(planId, userId);
        Set<String> succeeded = new HashSet<>();
        steps.stream().filter(step -> "SUCCEEDED".equals(step.status()) || "SKIPPED".equals(step.status()))
                .forEach(step -> succeeded.add(step.stepKey()));
        for (WorkflowExecutionRecords.StepState step : steps) {
            if ("PENDING".equals(step.status()) && succeeded.containsAll(strings(step.dependsOnJson()))) {
                mapper.readyStep(step.id(), planId);
            }
        }
    }

    private void validateDag(List<WorkflowExecutionRecords.StepState> current,
                             List<WorkflowExecutionDtos.StepRevision> revisions) {
        Map<String, List<String>> graph = new HashMap<>();
        current.forEach(step -> graph.put(step.stepKey(), strings(step.dependsOnJson())));
        revisions.forEach(step -> graph.put(step.stepId(), step.dependsOn()));
        for (Map.Entry<String, List<String>> entry : graph.entrySet()) {
            if (new HashSet<>(entry.getValue()).size() != entry.getValue().size()
                    || entry.getValue().stream().anyMatch(dep -> dep.equals(entry.getKey()) || !graph.containsKey(dep))) invalid();
        }
        Map<String, Integer> colors = new HashMap<>();
        for (String key : graph.keySet()) visit(key, graph, colors);
    }

    private void visit(String key, Map<String, List<String>> graph, Map<String, Integer> colors) {
        int color = colors.getOrDefault(key, 0);
        if (color == 1) invalid();
        if (color == 2) return;
        colors.put(key, 1);
        graph.get(key).forEach(dependency -> visit(dependency, graph, colors));
        colors.put(key, 2);
    }

    private WorkflowExecutionDtos.Accepted eventReplay(long planId, UUID eventId, String hash) {
        String stored = mapper.findEventHash(planId, eventId).orElse(null);
        if (stored == null) return null;
        if (!constantEquals(stored, hash)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        WorkflowExecutionRecords.PlanState plan = mapper.lockPlanInternal(planId).orElseThrow();
        return new WorkflowExecutionDtos.Accepted(false, "DUPLICATE", plan.status(), plan.version());
    }

    private WorkflowExecutionRecords.Execution requireExecutionIdentity(
            long planId, int executionNo, String token) {
        WorkflowExecutionRecords.Execution execution = mapper.lockExecutionNumber(planId, executionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!constantEquals(execution.executionTokenHash(), sha256(token))) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_CLAIMED);
        }
        return execution;
    }

    private WorkflowExecutionDtos.TaskActivationResponse activationResponse(
            boolean accepted, String disposition, long planId,
            WorkflowExecutionRecords.StepState step, int executionNo) {
        return new WorkflowExecutionDtos.TaskActivationResponse(accepted, disposition,
                Long.toString(planId), step.stepKey(), Long.toString(step.taskId()),
                step.taskStatus() == null ? "QUEUED" : step.taskStatus(), step.status(), true, executionNo);
    }

    private long workflowEvent(WorkflowExecutionRecords.PlanState plan, String type, String from,
                               String to, String error, UUID sourceEventId, String requestHash,
                               OffsetDateTime now) {
        long sequence = mapper.nextEventSequence(plan.id());
        requireOne(mapper.insertEvent(ids.nextId(), plan.id(), plan.userId(), plan.conversationId(),
                sequence, type, from, to, error, sourceEventId, requestHash, now));
        return sequence;
    }

    private WorkflowExecutionDtos.StartResponse startResponse(WorkflowExecutionRecords.PlanState plan) {
        Integer executionNo = mapper.latestExecution(plan.id()).map(WorkflowExecutionRecords.Execution::executionNo)
                .orElse("QUEUED".equals(plan.status()) ? 1 : null);
        return new WorkflowExecutionDtos.StartResponse(Long.toString(plan.id()), plan.version(), plan.status(),
                executionEnabled(), plan.executionStartedAt() != null, executionNo);
    }

    private WorkflowExecutionDtos.Observation observation(WorkflowExecutionRecords.Observation value) {
        return new WorkflowExecutionDtos.Observation(Long.toString(value.id()),
                mapper.steps(value.planId(), value.userId()).stream()
                        .filter(step -> step.id() == value.stepId()).findFirst()
                        .map(WorkflowExecutionRecords.StepState::stepKey).orElse("unavailable"),
                value.eventId().toString(), value.toolCallId().toString(), value.stepAttempt(), value.status(),
                value.resultType(), value.resultRef(), value.summary(), read(value.factsJson()),
                value.stableErrorCode(), value.retryable(), value.completedAt());
    }

    private List<JsonNode> nodes(JsonNode source) {
        List<JsonNode> result = new ArrayList<>();
        source.forEach(result::add);
        return List.copyOf(result);
    }

    private List<String> strings(String source) {
        List<String> result = new ArrayList<>();
        read(source).forEach(value -> result.add(value.textValue()));
        return result;
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("Invalid persisted workflow JSON", exception); }
    }

    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private UUID uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) invalid();
            return parsed;
        } catch (Exception exception) { invalid(); return null; }
    }

    private long positiveLong(String value) {
        try { long parsed = Long.parseLong(value); if (parsed <= 0) invalid(); return parsed; }
        catch (NumberFormatException exception) { invalid(); return 0; }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private boolean constantEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private void requireAvailable() {
        if (!executionEnabled()) throw new BusinessException(ErrorCode.WORKFLOW_EXECUTION_UNAVAILABLE);
    }

    private void requireOne(int count) {
        if (count != 1) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
    }

    private static void invalid() { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
    OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
