package com.guanghe.backend.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires recharge orders that were never paid.
 *
 * <p>Disabled by default. Read paths sweep expired orders themselves, so an order can never be
 * reported as payable past its deadline even with this off; the background sweep only keeps the
 * table tidy on a schedule. It is opt-in because a developer machine must not run a timer that
 * writes to a shared database.
 */
@Component
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "expiry-sweep-enabled",
        havingValue = "true")
public class RechargeOrderExpiryScheduler {

    private final RechargeOrderService orders;

    RechargeOrderExpiryScheduler(RechargeOrderService orders) {
        this.orders = orders;
    }

    @Scheduled(fixedDelayString = "${zhixiaohe.billing.expiry-sweep-delay:60000}")
    void sweep() {
        orders.sweepExpiredOrders();
    }
}
