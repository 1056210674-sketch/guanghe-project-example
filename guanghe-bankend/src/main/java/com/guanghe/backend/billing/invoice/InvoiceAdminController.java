package com.guanghe.backend.billing.invoice;

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

/**
 * Administrator invoice surface.
 *
 * <p>The four actions are separate endpoints rather than one "set status" call, so the API itself
 * documents which transitions exist and an operator cannot ask for one that does not.
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/billing/invoices")
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InvoiceAdminController {

    private final InvoiceAdminService admin;
    private final InvoiceWebhookDispatchService webhook;

    public InvoiceAdminController(InvoiceAdminService admin,
                                  InvoiceWebhookDispatchService webhook) {
        this.admin = admin;
        this.webhook = webhook;
    }

    @GetMapping
    public ApiResponse<PageResponse<InvoiceDtos.InvoiceApplicationResponse>> list(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @RequestParam(required = false) @Positive Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(admin.list(actor, userId, status, page, size));
    }

    @GetMapping("/{applicationId}")
    public ApiResponse<InvoiceDtos.InvoiceApplicationResponse> get(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long applicationId) {
        return ApiResponse.success(admin.get(actor, applicationId));
    }

    @PostMapping("/{applicationId}/accept")
    public ApiResponse<InvoiceDtos.InvoiceApplicationResponse> accept(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long applicationId) {
        return ApiResponse.success(admin.accept(actor, applicationId));
    }

    /** Records that internal review passed. Does not mean the invoice was issued. */
    @PostMapping("/{applicationId}/approve")
    public ApiResponse<InvoiceDtos.InvoiceApplicationResponse> approve(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long applicationId) {
        return ApiResponse.success(admin.approve(actor, applicationId));
    }

    @PostMapping("/{applicationId}/reject")
    public ApiResponse<InvoiceDtos.InvoiceApplicationResponse> reject(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long applicationId,
            @Valid @RequestBody InvoiceDtos.RejectInvoiceApplicationRequest request) {
        return ApiResponse.success(admin.reject(actor, applicationId, request.reason()));
    }

    /** Operator attestation after manually emailing the issued invoice. */
    @PostMapping("/{applicationId}/deliver")
    public ApiResponse<InvoiceDtos.InvoiceApplicationResponse> deliver(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long applicationId,
            @Valid @RequestBody InvoiceDtos.ConfirmManualInvoiceDeliveryRequest request) {
        return ApiResponse.success(admin.deliver(actor, applicationId, request.invoiceNumber()));
    }

    @PostMapping("/{applicationId}/webhook/retry")
    public ApiResponse<InvoiceDtos.InvoiceApplicationResponse> retryWebhook(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @Positive long applicationId) {
        return ApiResponse.success(webhook.retry(actor, applicationId));
    }
}
