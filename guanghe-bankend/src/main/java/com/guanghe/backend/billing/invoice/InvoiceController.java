package com.guanghe.backend.billing.invoice;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Payer-facing invoice applications. */
@Validated
@RestController
@RequestMapping("/api/v1/billing/invoices")
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InvoiceController {

    private final InvoiceApplicationService applications;

    public InvoiceController(InvoiceApplicationService applications) {
        this.applications = applications;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<InvoiceDtos.InvoiceApplicationResponse>> submit(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InvoiceDtos.CreateInvoiceApplicationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                applications.submit(account, idempotencyKey, request)));
    }

    @GetMapping
    public ApiResponse<PageResponse<InvoiceDtos.InvoiceApplicationResponse>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(applications.list(account, page, size));
    }

    @GetMapping("/{applicationId}")
    public ApiResponse<InvoiceDtos.InvoiceApplicationResponse> get(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long applicationId) {
        return ApiResponse.success(applications.get(account, applicationId));
    }

    /** Withdraws an application no operator has decided on. */
    @PostMapping("/{applicationId}/cancel")
    public ApiResponse<InvoiceDtos.InvoiceApplicationResponse> cancel(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long applicationId) {
        return ApiResponse.success(applications.cancel(account, applicationId));
    }
}
