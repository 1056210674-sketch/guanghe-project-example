package com.guanghe.backend.workflow;

import com.guanghe.backend.conversation.context.RunContextController;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/v1/runs")
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class InternalWorkflowPlanController {
    private final WorkflowPlanService service;

    public InternalWorkflowPlanController(WorkflowPlanService service) {
        this.service = service;
    }

    @PostMapping("/{runId}/workflow-plans")
    public ResponseEntity<WorkflowPlanDtos.Detail> propose(
            @PathVariable UUID runId,
            @RequestHeader(RunContextController.CONTEXT_REF_HEADER) String contextRef,
            @Valid @RequestBody WorkflowPlanDtos.ProposalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.propose(runId, contextRef, request));
    }
}
