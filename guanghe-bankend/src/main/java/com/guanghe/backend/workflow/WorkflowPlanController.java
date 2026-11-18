package com.guanghe.backend.workflow;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/workflow-plans")
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class WorkflowPlanController {
    private final WorkflowPlanService service;

    public WorkflowPlanController(WorkflowPlanService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkflowPlanDtos.Summary>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(account, status, page, size));
    }

    @GetMapping("/{planId}")
    public ApiResponse<WorkflowPlanDtos.Detail> get(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long planId) {
        return ApiResponse.success(service.get(account, planId));
    }

    @PostMapping("/{planId}/abandon")
    public ApiResponse<WorkflowPlanDtos.Detail> abandon(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long planId,
            @Valid @RequestBody WorkflowPlanDtos.AbandonRequest request) {
        return ApiResponse.success(service.abandon(account, planId, request.version()));
    }

    @PostMapping("/{planId}/confirmation-preview")
    public ApiResponse<WorkflowPlanDtos.ConfirmationAuthorization> confirmationPreview(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long planId,
            @Valid @RequestBody WorkflowPlanDtos.ConfirmationPreviewRequest request) {
        return ApiResponse.success(service.confirmationPreview(account, planId, request.planVersion()));
    }

    @PostMapping("/{planId}/confirm")
    public ApiResponse<WorkflowPlanDtos.ConfirmResponse> confirm(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long planId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WorkflowPlanDtos.ConfirmRequest request) {
        return ApiResponse.success(service.confirm(account, planId, idempotencyKey, request));
    }
}
