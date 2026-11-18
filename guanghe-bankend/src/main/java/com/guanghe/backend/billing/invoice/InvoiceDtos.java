package com.guanghe.backend.billing.invoice;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** Browser-facing payloads for the invoice control plane. */
public final class InvoiceDtos {

    private InvoiceDtos() {
    }

    /**
     * An invoice application.
     *
     * <p>{@code approvedAt} and {@code deliveredAt} are separate fields on purpose. {@code APPROVED}
     * means internal review passed; {@code DELIVERED} means an administrator recorded manual
     * emailing, not recipient receipt. A client that
     * shows "已开票" off {@code approvedAt} would be telling the customer something untrue, so both
     * timestamps travel and the UI is expected to label them differently.
     */
    public record InvoiceApplicationResponse(String id, String applicationNo, String status,
                                             String invoiceType, String titleType, String title,
                                             String taxNumber, long amountCents,
                                             String deliveryEmail, String remark,
                                             String rejectReason, String deliveryProvider,
                                             String invoiceNumber, String manualSentBy,
                                             String webhookStatus, String webhookErrorCode,
                                             OffsetDateTime submittedAt, OffsetDateTime reviewedAt,
                                             OffsetDateTime approvedAt,
                                             OffsetDateTime deliveredAt,
                                             OffsetDateTime rejectedAt,
                                             OffsetDateTime cancelledAt,
                                             OffsetDateTime createdAt,
                                             OffsetDateTime updatedAt) {
    }

    /** A new application. The amount is what the customer states; no order is attached to it. */
    public record CreateInvoiceApplicationRequest(
            @NotBlank @Pattern(regexp = "^(VAT_NORMAL|VAT_SPECIAL)$", message = "发票类型非法")
            String invoiceType,
            @NotBlank @Pattern(regexp = "^(PERSONAL|COMPANY)$", message = "抬头类型非法")
            String titleType,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 64)
            @Pattern(regexp = "^[0-9A-Z]{15,20}$", message = "纳税人识别号格式非法")
            String taxNumber,
            @NotNull @Min(1) Long amountCents,
            @NotBlank @Size(max = 255)
            @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "收件邮箱格式非法")
            String deliveryEmail,
            @Size(max = 500) String remark) {
    }

    /** An operator refusal. A reason is mandatory: a rejection with no explanation is not a service. */
    public record RejectInvoiceApplicationRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** Operator attestation after the invoice was issued and mailed outside this system. */
    public record ConfirmManualInvoiceDeliveryRequest(
            @NotBlank @Size(max = 64) String invoiceNumber) {
    }

    /**
     * Whether invoices can be applied for, manually emailed and notified to Feishu.
     * {@code deliverable} means the admin confirmation route exists; it says nothing about
     * whether the manual action has actually happened for an individual request.
     */
    public record InvoiceCapabilityResponse(boolean applicationsOpen, String applicationsStatus,
                                            String deliveryProvider, boolean deliveryConfigured,
                                            boolean deliverable, String deliveryStatus,
                                            List<String> deliveryMissing,
                                            boolean webhookConfigured, String webhookStatus,
                                            String currency) {
    }
}
