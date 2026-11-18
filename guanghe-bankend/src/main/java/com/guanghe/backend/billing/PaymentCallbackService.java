package com.guanghe.backend.billing;

import com.guanghe.backend.billing.payment.PaymentProvider;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The payment callback boundary.
 *
 * <p>This is the only unauthenticated entry point into the commercial control plane, so it is
 * deliberately thin: authenticate the message, resolve the order it names, and hand both to the one
 * reconciliation path the active query also uses.
 *
 * <p>Deliberately not transactional. Signature verification and decryption are pure computation and
 * must not hold a database connection, and a rejected callback must be able to record its own audit
 * row without being rolled back by a later failure.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PaymentCallbackService {

    public static final String SOURCE_PROVIDER_CALLBACK = "PROVIDER_CALLBACK";

    private final PaymentProvider provider;
    private final RechargeOrderService orders;

    public PaymentCallbackService(PaymentProvider provider, RechargeOrderService orders) {
        this.provider = provider;
        this.orders = orders;
    }

    /**
     * Authenticates and reconciles one provider callback.
     *
     * @throws BusinessException {@code PAYMENT_CALLBACK_INVALID} when the message cannot be
     *         authenticated, {@code PAYMENT_PROVIDER_NOT_CONFIGURED} when verification is impossible
     */
    public RechargeOrderService.Settlement accept(Map<String, String> headers, String rawBody) {
        PaymentProvider.CallbackEnvelope envelope = provider.verifyCallback(headers, rawBody);
        // An event we cannot identify can never be deduplicated, so it is refused rather than
        // applied speculatively.
        if (envelope.providerEventId() == null || envelope.providerEventId().isBlank()) {
            throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID);
        }
        BillingRecords.RechargeOrder order =
                orders.findByProviderReference(provider.code(), envelope.providerOrderNo());
        if (order == null) {
            return orders.unmatched(envelope.providerOrderNo(), envelope.providerEventId());
        }
        return orders.applySnapshot(order, new PaymentProvider.ProviderOrderSnapshot(
                envelope.providerOrderNo(), envelope.tradeState(), envelope.amountCents(),
                envelope.providerEventId(), envelope.paidAt()), SOURCE_PROVIDER_CALLBACK,
                envelope.payloadSha256());
    }
}
