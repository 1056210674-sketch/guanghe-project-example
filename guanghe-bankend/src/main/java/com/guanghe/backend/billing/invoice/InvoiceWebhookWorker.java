package com.guanghe.backend.billing.invoice;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polling is disabled in mock contexts and can be disabled independently in an environment. */
@Component
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "zhixiaohe.billing.invoice-webhook", name = "worker-enabled",
        havingValue = "true")
final class InvoiceWebhookWorker {
    private final InvoiceWebhookDispatchService dispatch;

    InvoiceWebhookWorker(InvoiceWebhookDispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @Scheduled(fixedDelayString = "${zhixiaohe.billing.invoice-webhook.poll-delay-ms:30000}")
    void poll() {
        dispatch.dispatchOne();
    }
}
