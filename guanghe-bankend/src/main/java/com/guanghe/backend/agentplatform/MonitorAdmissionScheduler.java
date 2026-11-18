package com.guanghe.backend.agentplatform;

import com.guanghe.backend.conversation.ConversationEventStreamService;
import com.guanghe.backend.task.TaskControlService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${zhixiaohe.paid-task.enabled:false} and ${zhixiaohe.agent-platform.enabled:true}")
public class MonitorAdmissionScheduler {
    private final TaskControlService tasks;
    private final ConversationEventStreamService events;
    public MonitorAdmissionScheduler(TaskControlService tasks,ConversationEventStreamService events) {
        this.tasks=tasks;this.events=events;
    }
    @Scheduled(fixedDelayString="${MONITOR_ADMISSION_POLL_DELAY_MS:1000}")
    public void admitCompletedRuns() {
        // Even when execution is switched off, finish pending terminal Runs as WAIT_INPUT.
        // TaskControl checks the feature before any wallet mutation; do not strand PENDING.
        for(int index=0;index<20;index++) {
            var result=tasks.admitNextMonitorAction().orElse(null);
            if(result==null) break;
            if(result.browserEvent()!=null) events.publish(result.browserEvent());
        }
    }
}
