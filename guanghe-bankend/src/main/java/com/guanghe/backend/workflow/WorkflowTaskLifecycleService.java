package com.guanghe.backend.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.common.web.TraceIdHolder;
import com.guanghe.backend.task.TaskRecords;
import com.guanghe.backend.task.TaskControlMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.workflow-execution", name = "enabled", havingValue = "true")
public class WorkflowTaskLifecycleService {
    private static final Set<String> SUCCESS = Set.of("SUCCEEDED", "PARTIAL");
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "PARTIAL", "FAILED", "SKIPPED", "SYSTEM_TERMINATED");
    private final WorkflowExecutionMapper mapper;
    private final WorkflowExecutionProperties properties;
    private final TaskControlMapper taskMapper;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final ObservabilitySupport observability;

    public WorkflowTaskLifecycleService(WorkflowExecutionMapper mapper, TaskControlMapper taskMapper,
                                        WorkflowExecutionProperties properties,
                                        SnowflakeIdGenerator ids, ObjectMapper json,
                                        ObservabilitySupport observability) {
        this.mapper = mapper;
        this.taskMapper = taskMapper;
        this.properties = properties;
        this.ids = ids;
        this.json = json;
        this.observability = observability;
    }

    @Transactional
    public boolean taskTerminal(TaskRecords.Task task, String terminalStatus,
                                String stableErrorCode, OffsetDateTime now) {
        if (!TERMINAL.contains(terminalStatus)) return false;
        // Fixed ARTICLE_FULL has a Java-only continuation. Read committed task state on the next
        // scheduler tick; do not prepare a new stage inside the result/settlement transaction.
        if ("ARTICLE".equals(task.taskType()) && taskMapper.isFullArticleTask(task.id())==1) return false;
        TaskRecords.Task storedTask = taskMapper.lockTask(task.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!terminalStatus.equals(storedTask.status()) || storedTask.userId() != task.userId()
                || storedTask.quotedPoints() != task.quotedPoints()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        WorkflowExecutionRecords.StepState step = mapper.lockStepByTask(task.id()).orElse(null);
        if (step == null || Set.of("SUCCEEDED", "FAILED", "SKIPPED").contains(step.status())) return false;
        if (!"WAITING".equals(step.status()) || step.userId() != task.userId()
                || step.quotedPoints() != task.quotedPoints()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        WorkflowExecutionRecords.PlanState plan = mapper.lockPlanInternal(step.planId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        WorkflowExecutionRecords.Execution waiting = mapper.lockLatestExecution(plan.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        if (!"RUNNING".equals(plan.status()) || !"WAITING_TASK".equals(waiting.status())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        WorkflowExecutionRecords.Reservation reservation = mapper.lockReservation(plan.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        if (reservation.allocatedPoints() < task.quotedPoints()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        boolean succeeded = SUCCESS.contains(terminalStatus);
        int settled = succeeded ? storedTask.settledPoints() : 0;
        int released = storedTask.quotedPoints() - settled;
        if (settled < 0 || released < 0 || settled + released != storedTask.quotedPoints()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        String stepStatus = succeeded ? "SUCCEEDED" : "FAILED";
        String error = succeeded ? null : stableCode(stableErrorCode);
        String resultRef = succeeded ? "task:" + task.id() : null;
        if (mapper.finishAllocation(plan.id(), plan.userId(), storedTask.quotedPoints(), settled, released, now) != 1
                || mapper.finishTaskStep(step.id(), plan.id(), task.id(), stepStatus,
                resultRef, error, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        mapper.readySatisfiedSteps(plan.id());
        WorkflowExecutionRecords.PointAccount account = mapper.lockPointAccount(plan.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        String transition = succeeded ? "WORKFLOW_TASK_SETTLE" : "WORKFLOW_TASK_RELEASE";
        mapper.insertLedger(ids.nextId(), plan.userId(), 0, 0, account.availablePoints(),
                account.frozenPoints(), transition, plan.id(),
                "workflow-task-terminal:" + plan.id() + ":" + step.id(),
                "Workflow task funding mirrored", now);

        int nextExecutionNo = waiting.executionNo() + 1;
        long outboxId = ids.nextId();
        ObjectNode payload = json.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("eventId", Long.toString(outboxId));
        payload.put("eventType", "WORKFLOW_EXECUTION_REQUESTED");
        payload.put("planId", Long.toString(plan.id()));
        payload.put("planVersion", plan.version());
        payload.put("attempt", nextExecutionNo);
        payload.put("resumeOnly", true);
        payload.put("traceparent", TraceIdHolder.outboundTraceparent());
        if (mapper.insertOutbox(outboxId, plan.userId(), plan.id(), nextExecutionNo,
                properties.getExchange(), properties.getRoutingKey(), write(payload),
                payload.path("traceparent").asText(), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        UUID sourceEvent = UUID.nameUUIDFromBytes(("workflow-task-terminal|" + task.id() + "|"
                + terminalStatus).getBytes(StandardCharsets.UTF_8));
        String requestHash = sha256(plan.id() + "|" + step.id() + "|" + task.id() + "|" + terminalStatus);
        workflowEvent(plan, succeeded ? "STEP_TASK_SUCCEEDED" : "STEP_TASK_FAILED",
                error, sourceEvent, requestHash, now);
        workflowEvent(plan, "PLAN_TASK_RESUME_QUEUED", null, null, null, now);
        observability.observe("workflow.funding.transition", stepStatus, operation -> {
            operation.tag("planIdRef", observability.reference(plan.id()));
            operation.tag("stepId", step.stepKey());
            operation.tag("taskType", storedTask.taskType());
            operation.tag("points", storedTask.quotedPoints());
        }, () -> { });
        observability.observe("workflow.task.resume", "QUEUED", operation -> {
            operation.tag("planIdRef", observability.reference(plan.id()));
            operation.tag("stepId", step.stepKey());
        }, () -> { });
        return true;
    }

    private void workflowEvent(WorkflowExecutionRecords.PlanState plan, String type,
                               String error, UUID sourceEvent, String requestHash,
                               OffsetDateTime now) {
        long sequence = mapper.nextEventSequence(plan.id());
        if (mapper.insertEvent(ids.nextId(), plan.id(), plan.userId(), plan.conversationId(),
                sequence, type, "RUNNING", "RUNNING", error, sourceEvent, requestHash, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
    }

    private String stableCode(String value) {
        if (value == null || !value.matches("^[A-Z][A-Z0-9_]{0,63}$")) return "TASK_EXECUTION_FAILED";
        return value;
    }

    private String write(ObjectNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
