package com.guanghe.backend.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.workflow-execution", name = "enabled", havingValue = "true")
public class WorkflowExecutionWatchdog {
    private final WorkflowExecutionService service;

    public WorkflowExecutionWatchdog(WorkflowExecutionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${WORKFLOW_EXECUTION_WATCHDOG_DELAY_MS:5000}")
    public void recoverOne() {
        service.recoverOneExpired();
    }
}
