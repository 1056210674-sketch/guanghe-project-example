package com.guanghe.backend.task;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.ApiResponse;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.conversation.ConversationEventStreamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class TaskController {
    private final TaskControlService service;
    private final ConversationEventStreamService events;

    public TaskController(TaskControlService service, ConversationEventStreamService events) {
        this.service = service; this.events = events;
    }

    @PostMapping("/api/v1/task-drafts")
    public ResponseEntity<ApiResponse<TaskDtos.DraftResponse>> createDraft(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody TaskDtos.CreateDraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.createDraft(account, key, request)));
    }

    @GetMapping("/api/v1/task-drafts/{draftId}")
    public ApiResponse<TaskDtos.DraftResponse> draft(@AuthenticationPrincipal AuthenticatedAccount account,
                                                     @PathVariable @Positive long draftId) {
        return ApiResponse.success(service.getDraft(account, draftId));
    }

    @PostMapping("/api/v1/task-drafts/{draftId}/revisions")
    public ResponseEntity<ApiResponse<TaskDtos.DraftResponse>> reviseDraft(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long draftId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody TaskDtos.ReviseDraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.reviseDraft(account, draftId, key, request)));
    }

    @PostMapping("/api/v1/task-drafts/{draftId}/abandon")
    public ApiResponse<TaskDtos.DraftResponse> abandonDraft(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long draftId) {
        return ApiResponse.success(service.abandonDraft(account, draftId));
    }

    @PostMapping("/api/v1/task-drafts/{draftId}/confirm")
    public ResponseEntity<ApiResponse<TaskDtos.ConfirmResponse>> confirm(
            @AuthenticationPrincipal AuthenticatedAccount account, @PathVariable @Positive long draftId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody TaskDtos.ConfirmRequest request) {
        TaskControlService.ConfirmedTask result = service.confirm(account, draftId, key, request);
        if (result.browserEvent() != null) events.publish(result.browserEvent());
        TaskDtos.TaskResponse task = result.response();
        TaskDtos.ConfirmResponse response = new TaskDtos.ConfirmResponse(task.id(), task.status(),
                task.quotedPoints(), task.frozenPoints(), task.createdAt());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }

    @GetMapping("/api/v1/tasks/{taskId}")
    public ApiResponse<TaskDtos.TaskResponse> task(@AuthenticationPrincipal AuthenticatedAccount account,
                                                   @PathVariable @Positive long taskId) {
        return ApiResponse.success(service.getTask(account, taskId));
    }

    @GetMapping("/api/v1/tasks")
    public ApiResponse<PageResponse<TaskDtos.TaskResponse>> tasks(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String productId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.listTasks(
                account, taskType, status, conversationId, spaceId, productId, page, size));
    }

    @GetMapping("/api/v1/tasks/{taskId}/events")
    public ApiResponse<List<TaskDtos.TaskEventResponse>> taskEvents(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long taskId) {
        return ApiResponse.success(service.taskEvents(account, taskId));
    }

    @GetMapping("/api/v1/billing/point-account")
    public ApiResponse<TaskDtos.PointAccountResponse> points(@AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(service.getPointAccount(account));
    }

    @GetMapping("/api/v1/billing/point-ledger")
    public ApiResponse<PageResponse<TaskDtos.PointLedgerEntryResponse>> pointLedger(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) @Positive Long ledgerId,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String businessType,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.listPointLedger(account, ledgerId, entryType, businessType, page, size));
    }
}
