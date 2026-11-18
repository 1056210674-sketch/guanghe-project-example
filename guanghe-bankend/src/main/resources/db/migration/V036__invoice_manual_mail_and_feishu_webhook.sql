-- Invoice requests are queued durably for the Feishu operator group. This table records only
-- delivery metadata; the submitted invoice details remain in invoice_application.
CREATE TABLE business.invoice_webhook_delivery (
    application_id bigint PRIMARY KEY REFERENCES business.invoice_application(id),
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL,
    sent_at timestamptz,
    last_error_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_invoice_webhook_status CHECK
        (status IN ('PENDING', 'WAITING_CONFIG', 'WAITING_MANUAL', 'SENT', 'SKIPPED')),
    CONSTRAINT ck_invoice_webhook_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_invoice_webhook_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT ck_invoice_webhook_error CHECK
        (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
);

CREATE INDEX idx_invoice_webhook_due
    ON business.invoice_webhook_delivery (available_at, application_id)
    WHERE status IN ('PENDING', 'WAITING_CONFIG');

INSERT INTO business.invoice_webhook_delivery
    (application_id,status,attempts,available_at,last_error_code,created_at,updated_at)
SELECT id,
       CASE WHEN status IN ('REJECTED','CANCELLED','DELIVERED') THEN 'SKIPPED'
            ELSE 'WAITING_MANUAL' END,
       0, now(),
       CASE WHEN status IN ('REJECTED','CANCELLED','DELIVERED') THEN NULL
            ELSE 'WEBHOOK_LEGACY_NOT_SENT' END,
       now(), now()
FROM business.invoice_application;

-- DELIVERED now means an administrator attested that an already issued invoice was sent from
-- the external mailbox. It is not inferred from webhook acceptance or internal review.
ALTER TABLE business.invoice_application ADD COLUMN invoice_number varchar(64);
ALTER TABLE business.invoice_application ADD COLUMN manual_sent_by bigint
    REFERENCES business.account(id);
ALTER TABLE business.invoice_application ADD CONSTRAINT ck_invoice_manual_delivery
    CHECK (status <> 'DELIVERED' OR delivery_provider <> 'MANUAL_EMAIL' OR (
        invoice_number IS NOT NULL AND length(btrim(invoice_number)) > 0
        AND manual_sent_by IS NOT NULL
        AND delivery_reference IS NOT NULL AND length(btrim(delivery_reference)) > 0
    ));

COMMENT ON TABLE business.invoice_webhook_delivery IS
    'Durable at-least-once Feishu submission dispatch, independent of invoice issue and manual email confirmation.';
COMMENT ON COLUMN business.invoice_application.invoice_number IS
    'Issued invoice number entered by an administrator after the invoice is manually emailed.';
