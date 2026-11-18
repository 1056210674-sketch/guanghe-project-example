package com.guanghe.backend.task;

import com.guanghe.backend.conversation.ConversationEventStreamService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class TaskExecutionWatchdog {
    private static final int MAX_RECOVERIES_PER_TICK = 20;

    private final TaskControlService service;
    private final ConversationEventStreamService events;
    private final PaidTaskProperties properties;

    public TaskExecutionWatchdog(TaskControlService service, ConversationEventStreamService events,
                                 PaidTaskProperties properties) {
        this.service = service;
        this.events = events;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${TASK_EXECUTION_WATCHDOG_POLL_DELAY_MS:1000}")
    public void recoverExpiredExecutions() {
        if (!properties.isExecutionWatchdogEnabled()) return;
        for (int index = 0; index < MAX_RECOVERIES_PER_TICK; index++) {
            TaskControlService.ReportedTask reconciled = service.reconcileStaleQueuedTask().orElse(null);
            if (reconciled == null) break;
            if (reconciled.browserEvent() != null) events.publish(reconciled.browserEvent());
        }
        for (int index = 0; index < MAX_RECOVERIES_PER_TICK; index++) {
            TaskControlService.ReportedTask recovered = service.recoverExpiredExecution().orElse(null);
            if (recovered == null) return;
            if (recovered.browserEvent() != null) events.publish(recovered.browserEvent());
        }
    }
}
