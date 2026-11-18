package com.guanghe.backend.billing.mail;

import java.time.OffsetDateTime;

/**
 * Mail transport boundary (SPI).
 *
 * <p>Implementations must fail closed. When credentials, a host or a sender are absent, every
 * method that would deliver must raise {@code MAIL_DELIVERY_NOT_CONFIGURED} rather than reporting a
 * delivery that did not happen. No implementation may reach a live transport unless
 * {@link #capability()} reports {@code sendable}.
 *
 * <p>This SPI exists so the boundary is designed rather than discovered later. There is deliberately
 * no implementation in this build that sends: the only one present reports itself unconfigured, or
 * configured but not implemented, and refuses both ways. A delivery receipt therefore cannot be
 * fabricated anywhere in this service.
 */
public interface MailDeliveryProvider {

    /** Stable transport code recorded on a delivery receipt, e.g. {@code SMTP}. */
    String code();

    /** Current readiness. Safe to expose to the browser: names only, never values. */
    MailDeliveryCapability capability();

    /**
     * Delivers one message and returns the transport's receipt.
     *
     * <p>Only callable when {@link #capability()} reports {@code sendable}. The receipt is the only
     * evidence this service accepts that a message left it.
     *
     * @throws com.guanghe.backend.common.api.BusinessException
     *         {@code MAIL_DELIVERY_NOT_CONFIGURED} when credentials are absent,
     *         {@code MAIL_DELIVERY_NOT_IMPLEMENTED} when the live transport is not built
     */
    MailReceipt send(MailMessage message);

    /**
     * One message to deliver.
     *
     * @param subject     plain text, never HTML: the body is composed by this service, not a template
     *                    engine the caller can inject markup into
     * @param reference   the business object the message is about, recorded on the receipt
     */
    record MailMessage(String recipient, String subject, String body, String reference) {
    }

    /** The transport's own evidence that a message was accepted for delivery. */
    record MailReceipt(String provider, String reference, OffsetDateTime acceptedAt) {
    }
}
