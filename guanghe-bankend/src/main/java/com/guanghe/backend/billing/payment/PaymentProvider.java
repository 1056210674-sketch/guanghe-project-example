package com.guanghe.backend.billing.payment;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Payment provider boundary (SPI).
 *
 * <p>Implementations must fail closed. When merchant credentials, certificates, keys or a callback
 * address are absent, every method that would talk to the provider must raise
 * {@code PAYMENT_PROVIDER_NOT_CONFIGURED} rather than returning a fabricated QR code, a fabricated
 * provider order number or a fabricated success. No implementation may reach the live provider
 * unless {@link #capability()} reports {@code payable}.
 *
 * <p>Nothing returned by this SPI may contain credentials, tokens or a verbatim provider payload.
 */
public interface PaymentProvider {

    /** Stable provider code persisted on the order, e.g. {@code WECHAT_NATIVE}. */
    String code();

    /** Current readiness. Safe to expose to the browser: names only, never values. */
    PaymentCapability capability();

    /**
     * Ask the provider to accept an order and return what the client needs to pay.
     *
     * @throws com.guanghe.backend.common.api.BusinessException
     *         {@code PAYMENT_PROVIDER_NOT_CONFIGURED} when credentials are absent,
     *         {@code PAYMENT_PROVIDER_UNAVAILABLE} when the live integration is not enabled.
     */
    PrepayTicket createPrepay(PrepayCommand command);

    /**
     * Actively poll the provider for an order's authoritative state.
     *
     * @throws com.guanghe.backend.common.api.BusinessException same conditions as
     *         {@link #createPrepay(PrepayCommand)}
     */
    ProviderOrderSnapshot queryOrder(String providerOrderNo);

    /**
     * Authenticate and reduce an inbound provider callback.
     *
     * <p>Must verify the signature, the timestamp and the nonce before returning anything. The
     * returned envelope carries a summary and a hash only — never the raw body, which may contain
     * buyer identifiers and is deliberately not persisted.
     *
     * @throws com.guanghe.backend.common.api.BusinessException
     *         {@code PAYMENT_CALLBACK_INVALID} when the callback cannot be authenticated,
     *         {@code PAYMENT_PROVIDER_NOT_CONFIGURED} when verification is impossible
     */
    CallbackEnvelope verifyCallback(Map<String, String> headers, String rawBody);

    record PrepayCommand(long orderId, String orderNo, long amountCents, String description,
                         OffsetDateTime expiresAt) {
    }

    /** What the browser needs to render a payment. Never synthesised locally. */
    record PrepayTicket(String providerOrderNo, String prepayId, String codeUrl) {
    }

    /** Authoritative provider view of an order. */
    record ProviderOrderSnapshot(String providerOrderNo, TradeState tradeState, Long amountCents,
                                 String providerEventId, OffsetDateTime paidAt) {
    }

    /**
     * An authenticated callback reduced to non-sensitive fields.
     *
     * @param payloadSha256 hash of the raw body, kept for reconciliation without storing it
     * @param summaryJson   bounded, non-sensitive summary of the event
     */
    record CallbackEnvelope(String providerEventId, String providerOrderNo, long amountCents,
                            TradeState tradeState, OffsetDateTime paidAt, String payloadSha256,
                            String summaryJson) {
    }

    /** Provider trade states mapped onto our own vocabulary. */
    enum TradeState {
        SUCCESS, NOTPAY, USERPAYING, CLOSED, REVOKED, PAYERROR, REFUND, UNKNOWN;

        public boolean isSettled() {
            return this == SUCCESS;
        }

        /** States after which the provider will not accept payment for this order. */
        public boolean isTerminal() {
            return this == SUCCESS || this == CLOSED || this == REVOKED || this == PAYERROR
                    || this == REFUND;
        }
    }
}
