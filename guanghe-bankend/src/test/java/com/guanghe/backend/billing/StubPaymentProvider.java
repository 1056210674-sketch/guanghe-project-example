package com.guanghe.backend.billing;

import com.guanghe.backend.billing.payment.PaymentCapability;
import com.guanghe.backend.billing.payment.PaymentProvider;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Test double for the payment SPI.
 *
 * <p>Exists so the recharge control plane's own behaviour — idempotency, state transitions,
 * exactly-once crediting — can be exercised without merchant credentials. It is not a stub success
 * path in the product: it lives in test sources only, and every assertion made through it is about
 * this service's bookkeeping, never about a real payment having happened.
 *
 * <p>The default configuration is the honest one: unconfigured and not payable.
 */
final class StubPaymentProvider implements PaymentProvider {

    static final String CODE = "STUB_NATIVE";

    private boolean configured;
    private boolean payable;
    private String status = PaymentCapability.NOT_CONFIGURED;
    private List<String> missing = List.of("enabled");
    private String providerOrderNo = "STUB-ORDER-1";
    private String prepayId = "STUB-PREPAY-1";
    private String codeUrl = "weixin://wxpay/bizpayurl?pr=stub";
    private ProviderOrderSnapshot snapshot;
    private CallbackEnvelope envelope;
    private BusinessException prepayFailure;
    private BusinessException queryFailure;
    private BusinessException callbackFailure;

    /** Configured and payable: the only state in which order creation may proceed. */
    static StubPaymentProvider payable() {
        StubPaymentProvider provider = new StubPaymentProvider();
        provider.configured = true;
        provider.payable = true;
        provider.status = PaymentCapability.READY;
        provider.missing = List.of();
        return provider;
    }

    /** Credentials present but live order creation disabled — the realistic production state. */
    static StubPaymentProvider configuredButNotPayable() {
        StubPaymentProvider provider = new StubPaymentProvider();
        provider.configured = true;
        provider.payable = false;
        provider.status = PaymentCapability.NOT_IMPLEMENTED;
        provider.missing = List.of("liveOrderCreation");
        return provider;
    }

    StubPaymentProvider withPrepayFailure(BusinessException failure) {
        this.prepayFailure = failure;
        return this;
    }

    StubPaymentProvider withQueryFailure(BusinessException failure) {
        this.queryFailure = failure;
        return this;
    }

    StubPaymentProvider withCallbackFailure(BusinessException failure) {
        this.callbackFailure = failure;
        return this;
    }

    StubPaymentProvider withSnapshot(ProviderOrderSnapshot value) {
        this.snapshot = value;
        return this;
    }

    StubPaymentProvider withEnvelope(CallbackEnvelope value) {
        this.envelope = value;
        return this;
    }

    StubPaymentProvider withProviderOrderNo(String value) {
        this.providerOrderNo = value;
        return this;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public PaymentCapability capability() {
        return new PaymentCapability(CODE, configured, payable, true, status, missing);
    }

    @Override
    public PrepayTicket createPrepay(PrepayCommand command) {
        if (prepayFailure != null) throw prepayFailure;
        return new PrepayTicket(providerOrderNo, prepayId, codeUrl);
    }

    @Override
    public ProviderOrderSnapshot queryOrder(String providerOrderNo) {
        if (queryFailure != null) throw queryFailure;
        if (snapshot == null) throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE);
        return snapshot;
    }

    @Override
    public CallbackEnvelope verifyCallback(Map<String, String> headers, String rawBody) {
        if (callbackFailure != null) throw callbackFailure;
        if (envelope == null) throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID);
        return envelope;
    }

    static CallbackEnvelope envelope(String eventId, String providerOrderNo, long amountCents,
                                     TradeState state, OffsetDateTime paidAt) {
        return new CallbackEnvelope(eventId, providerOrderNo, amountCents, state, paidAt,
                "0".repeat(64), "{\"tradeState\":\"" + state.name() + "\"}");
    }
}
