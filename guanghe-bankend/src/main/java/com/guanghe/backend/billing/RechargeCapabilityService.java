package com.guanghe.backend.billing;

import com.guanghe.backend.billing.payment.PaymentCapability;
import com.guanghe.backend.billing.payment.PaymentProvider;
import org.springframework.stereotype.Service;

/**
 * Reports whether recharge is usable, and when it is not, exactly which prerequisite is missing.
 *
 * <p>Deliberately depends on nothing but the provider and the policy properties: no mapper, no
 * transaction, no module switch. Whatever else is switched off, the browser must always be able to
 * ask this question and be told the truth, because the alternative — a route that disappears —
 * leaves the page unable to say anything more useful than "something went wrong".
 *
 * <p>It reports capability only. It never claims recharge works, and {@code payable} is false until
 * a provider genuinely is payable.
 */
@Service
public class RechargeCapabilityService {

    private final BillingProperties properties;
    private final PaymentProvider provider;

    public RechargeCapabilityService(BillingProperties properties, PaymentProvider provider) {
        this.properties = properties;
        this.provider = provider;
    }

    public BillingDtos.RechargeCapabilityResponse current() {
        PaymentCapability capability = provider.capability();
        return new BillingDtos.RechargeCapabilityResponse(capability.provider(),
                capability.configured(), capability.payable(), capability.callbackVerifiable(),
                capability.status(), capability.missingRequirements(),
                properties.getMinRechargeAmountCents(), properties.getMaxRechargeAmountCents(),
                properties.getPointsPerYuan(), properties.getRechargeOrderTtl().toSeconds(), "CNY");
    }
}
