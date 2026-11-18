package com.guanghe.backend.task;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.ApiResponse;
import com.guanghe.backend.conversation.ConversationEventStreamService;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legacy route retained to reject unsafe, unversioned confirmations from old browser bundles.
 * Explicit quote-card confirmation uses TaskDraftController and its full confirmation proof.
 */
@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class ConversationTaskActionController {

    private final TaskControlService service;
    private final ConversationEventStreamService events;

    public ConversationTaskActionController(
            TaskControlService service,
            ConversationEventStreamService events
    ) {
        this.service = service;
        this.events = events;
    }

    @PostMapping("/api/v1/conversations/{conversationId}/actions/confirm-active-task-draft")
    public ResponseEntity<ApiResponse<TaskDtos.ConfirmResponse>> confirmActiveTaskDraft(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        TaskControlService.ConfirmedTask result = service.confirmActiveConversationDraft(
                account, conversationId, idempotencyKey
        );
        if (result.browserEvent() != null) {
            events.publish(result.browserEvent());
        }
        TaskDtos.TaskResponse task = result.response();
        TaskDtos.ConfirmResponse response = new TaskDtos.ConfirmResponse(
                task.id(), task.status(), task.quotedPoints(), task.frozenPoints(), task.createdAt()
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }
}
