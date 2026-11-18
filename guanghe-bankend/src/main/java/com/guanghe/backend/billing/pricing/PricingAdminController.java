package com.guanghe.backend.billing.pricing;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator surface for pricing versions and commercial rules.
 *
 * <p>Kept away from the recharge administrator controller so the two can be reasoned about
 * separately: this one changes what things cost, the other moves balances.
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/billing")
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PricingAdminController {

    private final PricingAdminService admin;

    public PricingAdminController(PricingAdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/pricing-versions")
    public ApiResponse<PageResponse<PricingDtos.PricingVersionResponse>> listVersions(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(admin.listVersions(actor, status, page, size));
    }

    @GetMapping("/pricing-versions/{versionId}")
    public ApiResponse<PricingDtos.PricingVersionResponse> getVersion(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long versionId) {
        return ApiResponse.success(admin.getVersion(actor, versionId));
    }

    @PostMapping("/pricing-versions")
    public ResponseEntity<ApiResponse<PricingDtos.PricingVersionResponse>> createDraft(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @Valid @RequestBody PricingDtos.CreatePricingVersionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(admin.createDraft(actor, request)));
    }

    @PutMapping("/pricing-versions/{versionId}")
    public ApiResponse<PricingDtos.PricingVersionResponse> updateDraft(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long versionId,
            @Valid @RequestBody PricingDtos.UpdatePricingVersionRequest request) {
        return ApiResponse.success(admin.updateDraft(actor, versionId, request));
    }

    /** Publishes a draft and retires the version it replaces, in one transaction. */
    @PostMapping("/pricing-versions/{versionId}/publish")
    public ApiResponse<PricingDtos.PricingVersionResponse> publish(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long versionId) {
        return ApiResponse.success(admin.publish(actor, versionId));
    }

    /**
     * Retires the active version. The deployment falls back to the placeholder rate, which the
     * audit trail records — a deliberate return to non-production pricing, not an accident.
     */
    @PostMapping("/pricing-versions/{versionId}/retire")
    public ApiResponse<PricingDtos.PricingVersionResponse> retire(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long versionId) {
        return ApiResponse.success(admin.retire(actor, versionId));
    }

    @GetMapping("/rate-limits")
    public ApiResponse<List<PricingDtos.RateLimitRuleResponse>> listRateLimits(
            @AuthenticationPrincipal AuthenticatedAccount actor) {
        return ApiResponse.success(admin.listRateLimits(actor));
    }

    @PutMapping("/rate-limits/{action}")
    public ApiResponse<PricingDtos.RateLimitRuleResponse> updateRateLimit(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,47}$", message = "规则标识非法")
            String action,
            @Valid @RequestBody PricingDtos.UpdateRateLimitRequest request) {
        return ApiResponse.success(admin.updateRateLimit(actor, action, request));
    }
}
