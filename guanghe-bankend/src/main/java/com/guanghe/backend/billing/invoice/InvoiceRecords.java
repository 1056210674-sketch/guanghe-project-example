package com.guanghe.backend.billing.invoice;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Row shapes for the invoice control plane. */
public final class InvoiceRecords {

    private InvoiceRecords() {
    }

    public record InvoiceApplication(long id, String applicationNo, long userId, String status,
                                     String invoiceType, String titleType, String title,
                                     String taxNumber, long amountCents, String deliveryEmail,
                                     String remark, String rejectReason, Long reviewerId,
                                     String deliveryProvider, String deliveryReference,
                                     String idempotencyKey, String requestHash,
                                     OffsetDateTime submittedAt, OffsetDateTime reviewedAt,
                                     OffsetDateTime approvedAt, OffsetDateTime deliveredAt,
                                     OffsetDateTime rejectedAt, OffsetDateTime cancelledAt,
                                     int version, OffsetDateTime createdAt,
                                     OffsetDateTime updatedAt, String invoiceNumber,
                                     Long manualSentBy, String webhookStatus,
                                     String webhookErrorCode) {
    }

    public record WebhookDelivery(long applicationId, String status, int attempts,
                                  OffsetDateTime availableAt, OffsetDateTime sentAt,
                                  String lastErrorCode) {
    }

    public record InvoiceEvent(long id, long applicationId, long userId, UUID sourceEventId,
                               String eventType, String fromStatus, String toStatus, String source,
                               Long operatorId, String errorCode, String details,
                               OffsetDateTime createdAt) {
    }
}
