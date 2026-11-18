package com.guanghe.backend.conversation;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping("/api/v1/conversations")
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationController {

    private final ConversationService service;
    private final ConversationRunCoordinator coordinator;
    private final ConversationEventStreamService eventStreamService;
    private final ObservabilitySupport observability;

    public ConversationController(
            ConversationService service,
            ConversationRunCoordinator coordinator,
            ConversationEventStreamService eventStreamService,
            ObservabilitySupport observability
    ) {
        this.service = service;
        this.coordinator = coordinator;
        this.eventStreamService = eventStreamService;
        this.observability = observability;
    }

    @GetMapping
    public ApiResponse<PageResponse<ConversationDtos.Response>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(service.list(account, page, size));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ConversationDtos.Response>> create(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody ConversationDtos.CreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(account, request)));
    }

    @GetMapping("/{conversationId}")
    public ApiResponse<ConversationDtos.Response> get(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId
    ) {
        return ApiResponse.success(service.get(account, conversationId));
    }

    @PatchMapping("/{conversationId}")
    public ApiResponse<ConversationDtos.Response> update(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,
            @Valid @RequestBody ConversationDtos.UpdateRequest request
    ) {
        return ApiResponse.success(service.update(account, conversationId, request));
    }

    @DeleteMapping("/{conversationId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId
    ) {
        service.delete(account, conversationId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{conversationId}/messages")
    public ApiResponse<ConversationDtos.CursorPage<ConversationDtos.MessageResponse>> messages(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,
            @RequestParam(defaultValue = "0") @Min(0) long cursor,
            @RequestParam(required = false) @Positive Long beforeSequence,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        if (beforeSequence != null) {
            if (cursor != 0) throw new com.guanghe.backend.common.api.BusinessException(
                    com.guanghe.backend.common.api.ErrorCode.VALIDATION_FAILED);
            return ApiResponse.success(service.messagesBefore(account, conversationId, beforeSequence, limit));
        }
        return ApiResponse.success(service.messages(account, conversationId, cursor, limit));
    }

    @GetMapping("/{conversationId}/snapshot")
    public ApiResponse<ConversationDtos.Snapshot> snapshot(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.snapshot(account, conversationId, limit));
    }

    @GetMapping("/{conversationId}/message-operations/{clientMessageId}")
    public ApiResponse<ConversationDtos.MessageAccepted> messageOperation(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,
            @PathVariable UUID clientMessageId) {
        return ApiResponse.success(service.messageOperation(account, conversationId, clientMessageId));
    }

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<ApiResponse<ConversationDtos.MessageAccepted>> sendMessage(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConversationDtos.SendMessageRequest request
    ) {
        return observability.observe("java.conversation.message", "PERSIST_AND_DISPATCH", span -> {
            span.tag("conversationIdRef", observability.reference(conversationId));
            if (account != null) span.tag("userIdHash", observability.reference(account.id()));
        }, () -> {
            ConversationService.MessageSubmission submission = service.submitMessage(
                    account, conversationId, idempotencyKey, request
            );
            coordinator.dispatch(submission);
            return ResponseEntity.accepted().body(ApiResponse.success(submission.response()));
        });
    }

    @GetMapping(value = "/{conversationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        return eventStreamService.open(account, conversationId, lastEventId);
    }

    @PostMapping("/{conversationId}/stream-actions/stop")
    public ResponseEntity<ApiResponse<ConversationDtos.StopAccepted>> stop(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,
            @Valid @RequestBody ConversationDtos.StopRequest request
    ) {
        AgentRunRecord run = service.requestStop(account, conversationId, request.expectedRunId());
        return ResponseEntity.accepted().body(ApiResponse.success(coordinator.stop(run)));
    }
}
