package com.guanghe.backend.billing.pricing;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payer-facing rate card.
 *
 * <p>Requires no parameters, so there is exactly one answer to "what does a recharge cost" and no
 * way for a client to ask for a price that is not the one in force.
 */
@RestController
@RequestMapping("/api/v1/billing/pricing")
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PricingController {

    private final PricingService pricing;

    public PricingController(PricingService pricing) {
        this.pricing = pricing;
    }

    @GetMapping("/active")
    public ApiResponse<PricingDtos.RateCardResponse> active(
            @AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(pricing.currentRateCard(account));
    }
}
