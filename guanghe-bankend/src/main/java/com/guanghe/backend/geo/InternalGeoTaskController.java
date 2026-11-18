package com.guanghe.backend.geo;

import com.guanghe.backend.conversation.ConversationEventStreamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/v1/tasks")
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class InternalGeoTaskController {
    private static final String EXECUTION_NO = "X-Execution-No";
    private static final String EXECUTION_TOKEN = "X-Execution-Token";
    private final GeoTaskService service;
    private final ConversationEventStreamService events;

    public InternalGeoTaskController(GeoTaskService service, ConversationEventStreamService events) {
        this.service = service;
        this.events = events;
    }

    @PostMapping("/{taskId}/geo-observations")
    public GeoTaskDtos.ObservationAccepted observation(@PathVariable @Positive long taskId,
            @Valid @RequestBody GeoTaskDtos.ObservationRequest request) {
        GeoTaskService.ObservationResult result = service.reportObservation(taskId, request);
        if (result.browserEvent() != null) events.publish(result.browserEvent());
        return result.response();
    }

    @GetMapping("/{taskId}/geo-observations")
    public GeoTaskDtos.InternalObservationPage observations(@PathVariable @Positive long taskId,
            @RequestHeader(EXECUTION_NO) @Positive int executionNo,
            @RequestHeader(EXECUTION_TOKEN) String executionToken,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return service.observations(taskId, executionNo, executionToken, page, size);
    }

    @PostMapping("/{taskId}/geo-completion")
    public GeoTaskDtos.CompletionAccepted completion(@PathVariable @Positive long taskId,
            @Valid @RequestBody GeoTaskDtos.CompletionRequest request) {
        GeoTaskService.CompletionResult result = service.complete(taskId, request);
        if (result.browserEvent() != null) events.publish(result.browserEvent());
        return result.response();
    }
}
