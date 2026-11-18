package com.guanghe.backend.billing.pricing;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payer-facing rate card.
 *
 * <p>Read-only and requires no input: the price of a recharge is a property of the deployment, not
 * of the caller. The response carries {@code productionRate} so the page can say out loud that the
 * numbers are a placeholder while no version has been published.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PricingService {

    private final RateCardResolver resolver;

    public PricingService(RateCardResolver resolver) {
        this.resolver = resolver;
    }

    @Transactional(readOnly = true)
    public PricingDtos.RateCardResponse currentRateCard(AuthenticatedAccount account) {
        CatalogAccess.requireUser(account);
        RateCardResolver.RateCard card = resolver.current();
        return new PricingDtos.RateCardResponse(card.production(),
                card.versionId() == null ? null : Long.toString(card.versionId()),
                card.versionNo(), card.pointsPerYuan(), card.minCents(), card.maxCents(),
                card.note(), card.publishedAt(), card.tiers().stream()
                        .map(tier -> tier(card, tier)).toList(), "CNY");
    }

    static PricingDtos.RechargeTier tier(RateCardResolver.RateCard card,
                                         RateCardResolver.RateCard.Tier tier) {
        int base = card.basePointsFor(tier.amountCents());
        return new PricingDtos.RechargeTier(tierId(tier), tier.amountCents(), base, tier.giftPoints(),
                base + tier.giftPoints(), tier.label(), tier.sortOrder());
    }

    /**
     * A tier has no identifier of its own on the payer surface: the amount identifies it, and
     * exposing the row id would only invite a client to treat it as a handle it can use elsewhere.
     */
    static String tierId(RateCardResolver.RateCard.Tier tier) {
        return "TIER-" + tier.amountCents();
    }
}
