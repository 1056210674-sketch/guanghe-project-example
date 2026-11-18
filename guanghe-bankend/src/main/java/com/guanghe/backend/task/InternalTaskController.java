package com.guanghe.backend.task;

import com.guanghe.backend.conversation.ConversationEventStreamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/v1/tasks")
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class InternalTaskController {
    public static final String EXECUTION_NO = "X-Execution-No";
    public static final String EXECUTION_TOKEN = "X-Execution-Token";
    private final TaskControlService service;
    private final ConversationEventStreamService events;
    public InternalTaskController(TaskControlService service, ConversationEventStreamService events) {
        this.service = service;
        this.events = events;
    }

    @PostMapping("/{taskId}/claim")
    public TaskDtos.ClaimResponse claim(@PathVariable @Positive long taskId,
                                        @Valid @RequestBody TaskDtos.ClaimRequest request) {
        TaskControlService.ClaimedTask result = service.claim(taskId, request);
        if (result.browserEvent() != null) events.publish(result.browserEvent());
        return result.response();
    }

    @GetMapping("/{taskId}/execution-context")
    public TaskDtos.ExecutionContextResponse context(@PathVariable @Positive long taskId,
            @RequestHeader(EXECUTION_NO) @Positive int executionNo,
            @RequestHeader(EXECUTION_TOKEN) String token) {
        return service.executionContext(taskId, executionNo, token);
    }

    @PostMapping("/{taskId}/heartbeat")
    public TaskDtos.HeartbeatResponse heartbeat(@PathVariable @Positive long taskId,
            @Valid @RequestBody TaskDtos.HeartbeatRequest request) {
        return service.heartbeat(taskId, request);
    }

    @PostMapping("/{taskId}/execution-events")
    public TaskDtos.InternalAccepted event(@PathVariable @Positive long taskId,
            @Valid @RequestBody TaskDtos.ExecutionEventRequest request) {
        TaskControlService.ReportedTask result = service.reportEvent(taskId, request);
        if (result.browserEvent() != null) events.publish(result.browserEvent());
        return result.response();
    }

    @PostMapping("/{taskId}/result-artifacts")
    public TaskDtos.InternalAccepted result(@PathVariable @Positive long taskId,
            @Valid @RequestBody TaskDtos.ResultArtifactsRequest request) {
        TaskControlService.ReportedTask result = service.reportResult(taskId, request);
        if (result.browserEvent() != null) events.publish(result.browserEvent());
        return result.response();
    }
}
