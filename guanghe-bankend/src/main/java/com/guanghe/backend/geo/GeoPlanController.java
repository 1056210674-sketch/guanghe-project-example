package com.guanghe.backend.geo;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/geo")
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "geo-plan-enabled", havingValue = "true")
public class GeoPlanController {
    private final GeoPlanService service;

    public GeoPlanController(GeoPlanService service) { this.service = service; }

    @PostMapping("/plan-drafts")
    public ResponseEntity<ApiResponse<GeoPlanDtos.DraftResponse>> createDraft(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GeoPlanDtos.CreateDraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.createDraft(account, key, request)));
    }

    @GetMapping("/plans")
    public ApiResponse<PageResponse<GeoPlanDtos.PlanResponse>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String topicId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(account, status, topicId, page, size));
    }

    @PostMapping("/plans")
    public ResponseEntity<ApiResponse<GeoPlanDtos.PlanResponse>> create(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GeoPlanDtos.ConfirmPlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.createPlan(account, key, request)));
    }

    @PatchMapping("/plans/{planId}")
    public ApiResponse<GeoPlanDtos.PlanResponse> update(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long planId,
            @Valid @RequestBody GeoPlanDtos.UpdatePlanRequest request) {
        return ApiResponse.success(service.update(account, planId, request));
    }

    @PostMapping("/plans/{planId}/pause")
    public ApiResponse<GeoPlanDtos.PlanResponse> pause(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long planId,
            @Valid @RequestBody GeoPlanDtos.VersionRequest request) {
        return ApiResponse.success(service.pause(account, planId, request));
    }

    @PostMapping("/plans/{planId}/resume")
    public ApiResponse<GeoPlanDtos.PlanResponse> resume(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long planId,
            @Valid @RequestBody GeoPlanDtos.VersionRequest request) {
        return ApiResponse.success(service.resume(account, planId, request));
    }
}
