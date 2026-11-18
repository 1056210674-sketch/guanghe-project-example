package com.guanghe.backend.billing;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** Browser-facing payloads for the M8 commercial control plane. */
public final class BillingDtos {

    private BillingDtos() {
    }

    /**
     * Whether recharge is usable right now. The Web client renders this verbatim: a {@code false}
     * {@code payable} must be shown as an unavailable capability, never as a disabled button that
     * pretends the feature is finished.
     */
    public record RechargeCapabilityResponse(String provider, boolean configured, boolean payable,
                                             boolean callbackVerifiable, String status,
                                             List<String> missingRequirements,
                                             long minRechargeAmountCents,
                                             long maxRechargeAmountCents, int pointsPerYuan,
                                             long rechargeOrderTtlSeconds, String currency) {
    }

    /**
     * A recharge order.
     *
     * <p>{@code codeUrl} is only ever set from a real provider response. It stays {@code null} for
     * every order this build can create, because live order creation is not enabled.
     *
     * <p>{@code returnContext} is where the payer was when they ran out of points. It is an opaque
     * {@code KIND:id}, never a URL, so the client decides the route.
     *
     * <p>{@code giftPoints} is included in {@code points}; {@code points - giftPoints} is what was
     * bought. {@code pricingVersionId} is null for an order priced on the placeholder rate, which is
     * every order this deployment can currently produce.
     */
    public record RechargeOrderResponse(String id, String orderNo, String status, String provider,
                                        long amountCents, int points, int giftPoints,
                                        String providerOrderNo, String codeUrl,
                                        String returnContext, String pricingVersionId,
                                        OffsetDateTime expiresAt, OffsetDateTime paidAt,
                                        OffsetDateTime closedAt, OffsetDateTime failedAt,
                                        String failureCode, OffsetDateTime createdAt,
                                        OffsetDateTime updatedAt) {
    }

    /**
     * Idempotent recharge request.
     *
     * <p>Deliberately carries no user id, no point count and no price: the amount is the only
     * input, and the points and rate are resolved server-side.
     */
    public record CreateRechargeOrderRequest(
            @NotNull @Min(1) Long amountCents,
            @Size(max = 200)
            @Pattern(regexp = "^(TASK_DRAFT|WORKFLOW_PLAN|ARTICLE|GEO_PLAN):[1-9][0-9]{0,18}$",
                    message = "返回上下文格式非法")
            String returnContext) {
    }

    /**
     * Operator point credit request. Only ever creates a pending record; the credit happens when
     * the operator confirms with the returned token.
     */
    public record CreatePointAdjustmentRequest(
            @NotNull @Positive Long userId,
            @NotNull @Min(1) Integer points,
            @NotBlank @Size(max = 500) String reason) {
    }

    /**
     * A manual adjustment.
     *
     * <p>{@code confirmationToken} is returned exactly once, on creation. It is never derivable
     * again, because only its hash is stored.
     */
    public record PointAdjustmentResponse(String id, String userId, int points, String direction,
                                          String reason, String status, String operatorId,
                                          String confirmationToken,
                                          OffsetDateTime confirmationExpiresAt, String ledgerId,
                                          OffsetDateTime appliedAt, OffsetDateTime createdAt) {
    }

    /** Confirms a previously requested adjustment. */
    public record ConfirmPointAdjustmentRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$", message = "二次确认凭证格式非法")
            String confirmationToken) {
    }

    /** One append-only operational audit row. Rows can be read but never modified. */
    public record OperationalAuditEventResponse(String id, String actorType, String actorUserId,
                                                String actorRef, String targetType, String targetId,
                                                String eventType, String outcome, String traceId,
                                                String details, OffsetDateTime createdAt) {
    }
}
