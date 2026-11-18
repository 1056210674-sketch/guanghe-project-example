package com.guanghe.backend.billing.invoice;

import com.guanghe.backend.billing.BillingProperties;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Readiness of the manual invoice workflow and the separate Feishu intake webhook.
 */
@Service
public class InvoiceCapabilityService {

    public static final String APPLICATIONS_OPEN = "INVOICE_APPLICATIONS_OPEN";
    public static final String MODULE_DISABLED = "INVOICE_MODULE_DISABLED";

    public static final String DELIVERY_MANUAL = "INVOICE_MANUAL_EMAIL";
    public static final String WEBHOOK_READY = "INVOICE_WEBHOOK_READY";
    public static final String WEBHOOK_NOT_CONFIGURED = "INVOICE_WEBHOOK_NOT_CONFIGURED";

    private final BillingProperties properties;
    private final InvoiceWebhookProperties webhook;

    public InvoiceCapabilityService(BillingProperties properties, InvoiceWebhookProperties webhook) {
        this.properties = properties;
        this.webhook = webhook;
    }

    public InvoiceDtos.InvoiceCapabilityResponse current() {
        boolean open = properties.isEnabled();
        boolean configured = webhook.configured();
        return new InvoiceDtos.InvoiceCapabilityResponse(properties.isEnabled(),
                open ? APPLICATIONS_OPEN : MODULE_DISABLED, "MANUAL_EMAIL",
                true, open, DELIVERY_MANUAL, List.of(), configured,
                configured ? WEBHOOK_READY : WEBHOOK_NOT_CONFIGURED, "CNY");
    }
}
