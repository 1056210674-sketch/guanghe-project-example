package com.guanghe.backend.billing.pricing;

import com.guanghe.backend.billing.BillingProperties;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single answer to "what does a recharge cost right now".
 *
 * <p>Both the payer-facing rate card and order creation read through this class, so the price a
 * customer is shown and the price an order is written at cannot disagree.
 *
 * <p>When no pricing version is active the deployment has no production rate. Rather than refuse to
 * recharge — which would make the control plane untestable — it falls back to the configured
 * placeholder and marks the card {@code production=false}. Callers are expected to surface that
 * flag; the administrator surface must never present the fallback as settled pricing.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RateCardResolver {

    private final PricingMapper mapper;
    private final BillingProperties properties;

    public RateCardResolver(PricingMapper mapper, BillingProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** The rate card in force. */
    @Transactional(readOnly = true)
    public RateCard current() {
        PricingRecords.PricingVersion active = mapper.findActiveVersion().orElse(null);
        if (active == null) {
            return fallback();
        }
        List<RateCard.Tier> tiers = mapper.findTiers(active.id()).stream()
                .map(tier -> new RateCard.Tier(tier.amountCents(), tier.giftPoints(), tier.label(),
                        tier.sortOrder()))
                .toList();
        return new RateCard(active.id(), active.versionNo(), active.pointsPerYuan(),
                active.minRechargeAmountCents(), active.maxRechargeAmountCents(), true, active.note(),
                active.publishedAt(), tiers);
    }

    /** The placeholder card, used while nothing has been published. */
    public RateCard fallback() {
        return new RateCard(null, null, properties.getPointsPerYuan(),
                properties.getMinRechargeAmountCents(), properties.getMaxRechargeAmountCents(),
                false, null, null, List.of());
    }

    /**
     * The rate card in force, as a plain data value.
     *
     * @param versionId  the pricing version that priced this card, or null when the
     *                   placeholder is in force
     * @param production whether a published version produced these numbers
     */
    public record RateCard(Long versionId, Integer versionNo, int pointsPerYuan, long minCents,
                           long maxCents, boolean production, String note,
                           OffsetDateTime publishedAt, List<Tier> tiers) {

        public RateCard {
            tiers = List.copyOf(tiers);
        }

        /** Points the amount buys, floored to whole yuan, ignoring any promotional bonus. */
        public int basePointsFor(long amountCents) {
            long wholeYuan = amountCents / 100;
            long points = wholeYuan * pointsPerYuan;
            return (int) Math.min(points, Integer.MAX_VALUE);
        }

        /** Promotional points attached to this exact amount, or zero when it matches no tier. */
        public int giftPointsFor(long amountCents) {
            return tiers.stream()
                    .filter(tier -> tier.amountCents() == amountCents)
                    .mapToInt(Tier::giftPoints)
                    .findFirst()
                    .orElse(0);
        }

        /** Total credited by one ledger entry. */
        public int totalPointsFor(long amountCents) {
            long total = (long) basePointsFor(amountCents) + giftPointsFor(amountCents);
            return (int) Math.min(total, Integer.MAX_VALUE);
        }

        public boolean withinBounds(long amountCents) {
            return amountCents >= minCents && amountCents <= maxCents;
        }

        /** Tier for an exact amount, or null. */
        public Tier tierFor(long amountCents) {
            return tiers.stream().filter(tier -> tier.amountCents() == amountCents).findFirst()
                    .orElse(null);
        }

        public record Tier(long amountCents, int giftPoints, String label, int sortOrder) {
        }
    }
}
