package com.guanghe.backend.billing.feedback;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
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

/** Payer-facing feedback submission and history. */
@Validated
@RestController
@RequestMapping("/api/v1/billing/feedback")
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class FeedbackController {

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FeedbackDtos.FeedbackResponse>> submit(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody FeedbackDtos.CreateFeedbackRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(feedback.submit(account, idempotencyKey, request)));
    }

    @GetMapping
    public ApiResponse<PageResponse<FeedbackDtos.FeedbackResponse>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(feedback.list(account, page, size));
    }

    @GetMapping("/{feedbackId}")
    public ApiResponse<FeedbackDtos.FeedbackResponse> get(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long feedbackId) {
        return ApiResponse.success(feedback.get(account, feedbackId));
    }
}
