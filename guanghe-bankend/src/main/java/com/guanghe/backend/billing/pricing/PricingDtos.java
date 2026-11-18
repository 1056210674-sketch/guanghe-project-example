package com.guanghe.backend.billing.pricing;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** Browser-facing payloads for pricing versions and commercial rules. */
public final class PricingDtos {

    private PricingDtos() {
    }

    /**
     * One recharge tier as the payer sees it.
     *
     * <p>{@code basePoints} is what the amount buys; {@code giftPoints} is promotional. Both are
     * credited by a single ledger entry, so {@code points} is the sum — showing the split is a
     * display choice, not a second balance.
     */
    public record RechargeTier(String tierId, long amountCents, int basePoints, int giftPoints,
                               int points, String label, int sortOrder) {
    }

    /**
     * The rate card currently in force.
     *
     * <p>{@code productionRate} is false while no version has been published, and then
     * {@code versionId} is null and {@code tiers} is empty. It is the only signal separating a real
     * rate card from the placeholder, so the client must render it: showing the placeholder numbers
     * without that label would present an unapproved price as settled pricing.
     */
    public record RateCardResponse(boolean productionRate, String versionId, Integer versionNo,
                                   int pointsPerYuan, long minRechargeAmountCents,
                                   long maxRechargeAmountCents, String note,
                                   OffsetDateTime publishedAt, List<RechargeTier> tiers,
                                   String currency) {
    }

    /** A pricing version as an operator manages it. */
    public record PricingVersionResponse(String id, int versionNo, String status, int pointsPerYuan,
                                         long minRechargeAmountCents,
                                         long maxRechargeAmountCents, String note, String createdBy,
                                         String publishedBy, OffsetDateTime publishedAt,
                                         OffsetDateTime retiredAt, List<RechargeTier> tiers,
                                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** Tier definition when creating or replacing a draft. */
    public record TierRequest(@NotNull @Min(1) Long amountCents, @Min(0) Integer giftPoints,
                              @NotBlank @Size(max = 64) String label, Integer sortOrder) {
    }

    /** A new draft. Publishing is a separate, explicitly confirmed step. */
    public record CreatePricingVersionRequest(@NotNull @Min(1) Integer pointsPerYuan,
                                              @NotNull @Min(1) Long minRechargeAmountCents,
                                              @NotNull @Min(1) Long maxRechargeAmountCents,
                                              @Size(max = 500) String note,
                                              @NotNull @Size(max = 50) List<TierRequest> tiers) {
    }

    /** Replaces a draft's rate, bounds and tiers. Refused once the version is published. */
    public record UpdatePricingVersionRequest(@NotNull @Min(1) Integer pointsPerYuan,
                                              @NotNull @Min(1) Long minRechargeAmountCents,
                                              @NotNull @Min(1) Long maxRechargeAmountCents,
                                              @Size(max = 500) String note,
                                              @NotNull @Size(max = 50) List<TierRequest> tiers,
                                              @NotNull @Min(1) Integer expectedVersion) {
    }

    /** A throttle rule. {@code configured} is false when the built-in default is in force. */
    public record RateLimitRuleResponse(String id, String action, int windowSeconds, int maxAttempts,
                                        boolean enabled, boolean configured, int version,
                                        String updatedBy, OffsetDateTime updatedAt) {
    }

    /** Sets a rule. {@code expectedVersion} is 0 to create it for the first time. */
    public record UpdateRateLimitRequest(@NotNull @Min(1) Integer windowSeconds,
                                         @NotNull @Min(1) Integer maxAttempts,
                                         @NotNull Boolean enabled,
                                         @NotNull @Min(0) Integer expectedVersion) {
    }
}
