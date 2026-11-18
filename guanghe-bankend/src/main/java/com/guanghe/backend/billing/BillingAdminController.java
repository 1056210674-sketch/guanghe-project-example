package com.guanghe.backend.billing;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
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

/**
 * Administrator commercial surface: recharge orders, manual point credits and the operational audit
 * trail.
 *
 * <p>Authorization is enforced in the services; the role gate is duplicated here only so an
 * unauthenticated or non-admin caller never reaches business code at all.
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/billing")
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BillingAdminController {

    private final BillingAdminService admin;
    private final ManualPointAdjustmentService adjustments;

    public BillingAdminController(BillingAdminService admin,
                                  ManualPointAdjustmentService adjustments) {
        this.admin = admin;
        this.adjustments = adjustments;
    }

    @GetMapping("/recharge-orders")
    public ApiResponse<PageResponse<BillingDtos.RechargeOrderResponse>> listOrders(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @RequestParam(required = false) @Positive Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(admin.listOrders(actor, userId, status, page, size));
    }

    @GetMapping("/recharge-orders/{orderId}")
    public ApiResponse<BillingDtos.RechargeOrderResponse> getOrder(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long orderId) {
        return ApiResponse.success(admin.getOrder(actor, orderId));
    }

    /** Asks the provider for the authoritative state of an order the operator is investigating. */
    @PostMapping("/recharge-orders/{orderId}/reconcile")
    public ApiResponse<BillingDtos.RechargeOrderResponse> reconcileOrder(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long orderId) {
        RechargeOrderService.QueryResult result = admin.reconcileOrder(actor, orderId);
        if (result.settlement() == RechargeOrderService.Settlement.AMOUNT_MISMATCH) {
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        return ApiResponse.success(result.order());
    }

    @GetMapping("/point-adjustments")
    public ApiResponse<PageResponse<BillingDtos.PointAdjustmentResponse>> listAdjustments(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @RequestParam(required = false) @Positive Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(adjustments.list(actor, userId, status, page, size));
    }

    /**
     * Requests a credit. Nothing is credited yet: the response carries the single-use confirmation
     * token, which the operator must submit to {@code /confirm}.
     */
    @PostMapping("/point-adjustments")
    public ResponseEntity<ApiResponse<BillingDtos.PointAdjustmentResponse>> requestAdjustment(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BillingDtos.CreatePointAdjustmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(adjustments.request(actor, idempotencyKey, request)));
    }

    @PostMapping("/point-adjustments/{adjustmentId}/confirm")
    public ApiResponse<BillingDtos.PointAdjustmentResponse> confirmAdjustment(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long adjustmentId,
            @Valid @RequestBody BillingDtos.ConfirmPointAdjustmentRequest request) {
        return ApiResponse.success(
                adjustments.confirm(actor, adjustmentId, request.confirmationToken()));
    }

    /** Read-only view of the append-only operational audit trail. */
    @GetMapping("/audit-events")
    public ApiResponse<PageResponse<BillingDtos.OperationalAuditEventResponse>> listAuditEvents(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) @Positive Long targetId,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(admin.listAuditEvents(actor, actorType, targetType, targetId,
                eventType, page, size));
    }
}
