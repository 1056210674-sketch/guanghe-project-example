package com.guanghe.backend.billing.feedback;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator feedback queue. */
@Validated
@RestController
@RequestMapping("/api/v1/admin/billing/feedback")
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class FeedbackAdminController {

    private final FeedbackAdminService admin;

    public FeedbackAdminController(FeedbackAdminService admin) {
        this.admin = admin;
    }

    @GetMapping
    public ApiResponse<PageResponse<FeedbackDtos.FeedbackResponse>> list(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @RequestParam(required = false) @Positive Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(admin.list(actor, userId, status, page, size));
    }

    /** Acknowledges, resolves or closes an item. Status only ever moves forward. */
    @PostMapping("/{feedbackId}/handle")
    public ApiResponse<FeedbackDtos.FeedbackResponse> handle(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long feedbackId,
            @Valid @RequestBody FeedbackDtos.HandleFeedbackRequest request) {
        return ApiResponse.success(admin.handle(actor, feedbackId, request));
    }
}
