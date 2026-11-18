package com.guanghe.backend.billing;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Row shapes for the M8 commercial control plane. */
public final class BillingRecords {

    private BillingRecords() {
    }

    /**
     * @param giftPoints       promotional points included in {@code points}; {@code points} minus
     *                         this is what the payer bought
     * @param pricingVersionId the rate card that priced this order, or null when the deployment was
     *                         on the placeholder rate
     */
    public record RechargeOrder(long id, String orderNo, long userId, String status, String provider,
                                long amountCents, int points, int giftPoints, String providerOrderNo,
                                String providerPrepayId, String providerCodeUrl,
                                String idempotencyKey, String requestHash, String returnContext,
                                Long pricingVersionId, OffsetDateTime expiresAt, OffsetDateTime paidAt,
                                OffsetDateTime closedAt, OffsetDateTime failedAt,
                                String failureCode, int version, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt) {
    }

    public record RechargeOrderEvent(long id, long orderId, long userId, UUID sourceEventId,
                                     String eventType, String fromStatus, String toStatus,
                                     String source, String providerEventId, Long amountCents,
                                     String requestHash, String errorCode, String details,
                                     OffsetDateTime createdAt) {
    }

    public record ManualAdjustment(long id, long userId, int points, String direction, String reason,
                                   long operatorId, String idempotencyKey, String requestHash,
                                   String confirmationTokenHash, OffsetDateTime confirmationExpiresAt,
                                   String status, Long ledgerId, OffsetDateTime appliedAt,
                                   OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record PointAccount(long userId, int availablePoints, int frozenPoints, int version,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record PointLedgerEntry(long id, long userId, int availableDelta, int frozenDelta,
                                   int availableAfter, int frozenAfter, String entryType,
                                   String businessType, long businessId, Long operatorId,
                                   String reason, OffsetDateTime createdAt) {
    }

    public record OperationalAuditEvent(long id, String actorType, Long actorUserId, String actorRef,
                                        String targetType, Long targetId, String eventType,
                                        String outcome, String traceId, String sourceEventId,
                                        String idempotencyKey, String details,
                                        OffsetDateTime createdAt) {
    }
}
