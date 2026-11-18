package com.guanghe.backend.billing.bookkeeping;

import java.time.OffsetDateTime;
import java.util.List;

/** Response shapes for the administrator bookkeeping view. */
public final class BookkeepingDtos {

    private BookkeepingDtos() {
    }

    /**
     * One ledger row, rendered for a browser.
     *
     * <p>Snowflake ids stay strings: a JSON number would lose precision in JavaScript, and the
     * client cannot send back what it cannot represent.
     */
    public record PointLedgerEntryResponse(String id, String userId, int availableDelta,
                                           int frozenDelta, int availableAfter, int frozenAfter,
                                           String entryType, String businessType, String businessId,
                                           String operatorId, String reason,
                                           OffsetDateTime createdAt) {
    }

    /**
     * Totals for the ledger query that produced the page of rows above it.
     *
     * <p>The sums are computed over the same filter as the rows, so an operator narrowing to one
     * user is not shown a platform-wide number that silently disagrees with what they see.
     */
    public record LedgerSummaryResponse(long entries, long availableDelta, long frozenDelta) {
    }

    /** What the recharge control plane actually settled. */
    public record RechargeSummaryResponse(List<StatusCount> ordersByStatus, long succeededOrders,
                                          long succeededAmountCents, long basePoints,
                                          long giftPoints) {
    }

    public record InvoiceSummaryResponse(List<StatusCount> applicationsByStatus,
                                         long deliveredAmountCents) {
    }

    public record FeedbackSummaryResponse(List<StatusCount> itemsByStatus) {
    }

    /**
     * Whether the figures were produced under a published rate card or the placeholder.
     *
     * <p>{@code productionRate=false} means no pricing version has been published and orders are
     * being priced by {@code zhixiaohe.billing.points-per-yuan}, which is a development default and
     * not an agreed exchange rate. The admin view states this rather than presenting the numbers as
     * commercially final.
     */
    public record RatePlanResponse(boolean productionRate, String versionId, Integer versionNo,
                                   int pointsPerYuan) {
    }

    /**
     * @param platformAvailablePoints outstanding balance across every account on the platform, not
     *                                only balances this billing module moved — {@code point_account}
     *                                is shared with task execution and workflow runs
     */
    public record BookkeepingSummaryResponse(OffsetDateTime generatedAt,
                                            RechargeSummaryResponse recharge,
                                            InvoiceSummaryResponse invoice,
                                            FeedbackSummaryResponse feedback,
                                            LedgerSummaryResponse ledger,
                                            RatePlanResponse ratePlan,
                                            long platformAvailablePoints,
                                            long platformFrozenPoints,
                                            List<String> ledgerEntryTypes,
                                            List<String> ledgerBusinessTypes) {
    }
}
