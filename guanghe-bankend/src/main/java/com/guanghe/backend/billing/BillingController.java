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
 * Payer-facing recharge API.
 *
 * <p>The capability probe lives in {@link RechargeCapabilityController} instead, because it must
 * answer even when this controller is switched off.
 *
 * <p>Point balance and point ledger are deliberately absent: they are already served from
 * {@code /api/v1/billing/point-account} and {@code /api/v1/billing/point-ledger} by the task control
 * plane, which owns the freeze/settle/release side of the same two tables. Re-exposing them here
 * would create a second, drifting view of one balance.
 */
@Validated
@RestController
@RequestMapping("/api/v1/billing")
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BillingController {

    private final RechargeOrderService orders;

    public BillingController(RechargeOrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/recharge-orders")
    public ResponseEntity<ApiResponse<BillingDtos.RechargeOrderResponse>> create(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BillingDtos.CreateRechargeOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(orders.create(account, idempotencyKey, request)));
    }

    @GetMapping("/recharge-orders")
    public ApiResponse<PageResponse<BillingDtos.RechargeOrderResponse>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(orders.list(account, status, page, size));
    }

    @GetMapping("/recharge-orders/{orderId}")
    public ApiResponse<BillingDtos.RechargeOrderResponse> get(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long orderId) {
        return ApiResponse.success(orders.get(account, orderId));
    }

    /**
     * Actively polls the provider and reconciles. The transaction that does the reconciling has
     * already committed by the time a disagreement is turned into an error, so the mismatch is
     * recorded for operators even though the caller sees a failure.
     */
    @PostMapping("/recharge-orders/{orderId}/query")
    public ApiResponse<BillingDtos.RechargeOrderResponse> query(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long orderId) {
        RechargeOrderService.QueryResult result = orders.query(account, orderId);
        if (result.settlement() == RechargeOrderService.Settlement.AMOUNT_MISMATCH) {
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        return ApiResponse.success(result.order());
    }

    @PostMapping("/recharge-orders/{orderId}/close")
    public ApiResponse<BillingDtos.RechargeOrderResponse> close(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long orderId) {
        return ApiResponse.success(orders.close(account, orderId));
    }
}
