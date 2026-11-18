package com.guanghe.backend.billing.invoice;

/** A single-purpose delivery boundary for invoice details sent to the Feishu operator group. */
interface InvoiceWebhookClient {
    void send(InvoiceRecords.InvoiceApplication application);
}
