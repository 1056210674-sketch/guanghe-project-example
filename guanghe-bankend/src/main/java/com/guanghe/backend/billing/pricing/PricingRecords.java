package com.guanghe.backend.billing.pricing;

import java.time.OffsetDateTime;

/** Row shapes for the commercial rate card and its rules. */
public final class PricingRecords {

    private PricingRecords() {
    }

    public record PricingVersion(long id, int versionNo, String status, int pointsPerYuan,
                                 long minRechargeAmountCents, long maxRechargeAmountCents,
                                 String note, long createdBy, Long publishedBy,
                                 OffsetDateTime publishedAt, OffsetDateTime retiredAt, int version,
                                 OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record PricingTier(long id, long pricingVersionId, long amountCents, int giftPoints,
                              String label, int sortOrder, OffsetDateTime createdAt) {
    }

    public record RateLimitRule(long id, String action, int windowSeconds, int maxAttempts,
                                boolean enabled, long updatedBy, int version,
                                OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }
}
