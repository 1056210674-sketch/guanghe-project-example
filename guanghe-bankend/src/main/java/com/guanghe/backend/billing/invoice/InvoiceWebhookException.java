package com.guanghe.backend.billing.invoice;

/** Only a stable error code crosses the worker boundary; URLs and response bodies never do. */
final class InvoiceWebhookException extends RuntimeException {
    private final String code;

    InvoiceWebhookException(String code) {
        super(code);
        this.code = code;
    }

    String code() { return code; }
}
