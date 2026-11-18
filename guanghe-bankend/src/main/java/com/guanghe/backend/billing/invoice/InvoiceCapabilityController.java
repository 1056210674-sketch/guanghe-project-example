package com.guanghe.backend.billing.invoice;

import com.guanghe.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The invoice capability probe.
 *
 * <p>Ungated, like the recharge and notification probes, because it needs no database. An invoice
 * page that cannot say "交付能力尚未配置" will either hide the feature or imply a service level
 * nothing here can meet; both are worse than an honest refusal.
 */
@RestController
public class InvoiceCapabilityController {

    private final InvoiceCapabilityService capability;

    public InvoiceCapabilityController(InvoiceCapabilityService capability) {
        this.capability = capability;
    }

    @GetMapping("/api/v1/billing/invoice-capability")
    public ApiResponse<InvoiceDtos.InvoiceCapabilityResponse> capability() {
        return ApiResponse.success(capability.current());
    }
}
