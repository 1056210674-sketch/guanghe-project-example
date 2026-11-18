package com.guanghe.backend.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/v1/workflow-plans")
@ConditionalOnProperty(prefix = "zhixiaohe.workflow-execution", name = "enabled", havingValue = "true")
public class InternalWorkflowExecutionController {
    public static final String EXECUTION_NO = "X-Execution-No";
    public static final String EXECUTION_TOKEN = "X-Execution-Token";
    private final WorkflowExecutionService service;

    public InternalWorkflowExecutionController(WorkflowExecutionService service) {
        this.service = service;
    }

    @PostMapping("/{planId}/claim")
    public WorkflowExecutionDtos.ClaimResponse claim(@PathVariable @Positive long planId,
            @Valid @RequestBody WorkflowExecutionDtos.ClaimRequest request) {
        return service.claim(planId, request);
    }

    @GetMapping("/{planId}/execution-context")
    public WorkflowExecutionDtos.ExecutionContext context(@PathVariable @Positive long planId,
            @RequestHeader(EXECUTION_NO) @Positive int executionNo,
            @RequestHeader(EXECUTION_TOKEN) String token) {
        return service.context(planId, executionNo, token);
    }

    @PostMapping("/{planId}/heartbeat")
    public WorkflowExecutionDtos.HeartbeatResponse heartbeat(@PathVariable @Positive long planId,
            @Valid @RequestBody WorkflowExecutionDtos.HeartbeatRequest request) {
        return service.heartbeat(planId, request);
    }

    @PostMapping("/{planId}/observations")
    public WorkflowExecutionDtos.ObservationAccepted observation(@PathVariable @Positive long planId,
            @Valid @RequestBody WorkflowExecutionDtos.ObservationRequest request) {
        return service.observe(planId, request);
    }

    @PutMapping("/{planId}/steps/{stepId}/state")
    public WorkflowExecutionDtos.Accepted stepState(@PathVariable @Positive long planId,
            @PathVariable String stepId, @Valid @RequestBody WorkflowExecutionDtos.StepStateRequest request) {
        return service.stepState(planId, stepId, request);
    }

    @PostMapping("/{planId}/steps/{stepId}/task")
    public WorkflowExecutionDtos.TaskActivationResponse activateTask(
            @PathVariable @Positive long planId, @PathVariable String stepId,
            @Valid @RequestBody WorkflowExecutionDtos.TaskActivationRequest request) {
        return service.activateTask(planId, stepId, request);
    }

    @PostMapping("/{planId}/replans")
    public WorkflowExecutionDtos.Accepted replan(@PathVariable @Positive long planId,
            @Valid @RequestBody WorkflowExecutionDtos.ReplanRequest request) {
        return service.replan(planId, request);
    }

    @PostMapping("/{planId}/completion")
    public WorkflowExecutionDtos.Accepted completion(@PathVariable @Positive long planId,
            @Valid @RequestBody WorkflowExecutionDtos.CompletionRequest request) {
        return service.complete(planId, request);
    }
}
