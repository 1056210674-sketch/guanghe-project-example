package com.guanghe.backend.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on scheduling for the commercial module.
 *
 * <p>Separate from {@link BillingConfiguration} because the two have opposite lifetimes.
 * {@code BillingConfiguration} must be present in every deployment so the browser can always be told
 * why recharge is unavailable; scheduling must not, because {@code @EnableScheduling} is a
 * context-wide switch. Left unconditional it would start every other module's {@code @Scheduled}
 * beans in a deployment that has the commercial module switched off — including, in a test context,
 * background pollers calling mocks while the test thread is still stubbing them.
 *
 * <p>This mirrors the baseline behaviour of the commercial module, which was gated as a whole, and
 * matches {@code PaidTaskConfiguration}, which enables scheduling for itself when paid tasks are on.
 * The only scheduled bean here is the opt-in expiry sweep, so nothing runs unless an operator asked
 * for it.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
class BillingSchedulingConfiguration {
}
