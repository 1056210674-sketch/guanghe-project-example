package com.guanghe.backend.billing.bookkeeping;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.BillingAccess;
import com.guanghe.backend.billing.BillingMapper;
import com.guanghe.backend.billing.BillingRecords;
import com.guanghe.backend.billing.feedback.FeedbackMapper;
import com.guanghe.backend.billing.invoice.InvoiceMapper;
import com.guanghe.backend.billing.pricing.RateCardResolver;
import com.guanghe.backend.catalog.PageResponse;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only bookkeeping for operators: what the commercial control plane actually recorded.
 *
 * <p>Every figure is an aggregate over rows that already exist. Nothing here is estimated, and
 * nothing here writes — the ledger in particular has no update or delete path in the mapper.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BookkeepingService {

    private final BillingMapper billing;
    private final InvoiceMapper invoices;
    private final FeedbackMapper feedback;
    private final RateCardResolver rateCards;
    private final Clock clock;

    public BookkeepingService(BillingMapper billing, InvoiceMapper invoices, FeedbackMapper feedback,
                              RateCardResolver rateCards, @Qualifier("billingClock") Clock clock) {
        this.billing = billing;
        this.invoices = invoices;
        this.feedback = feedback;
        this.rateCards = rateCards;
        this.clock = clock;
    }

    /**
     * Searches the point ledger.
     *
     * <p>The summary returned alongside the page is computed over the same filter, so the net
     * movement shown above a page of rows always describes that selection.
     */
    @Transactional(readOnly = true)
    public PageResponse<BookkeepingDtos.PointLedgerEntryResponse> ledger(
            AuthenticatedAccount actor, Long userId, String entryType, String businessType,
            OffsetDateTime from, OffsetDateTime to, int page, int size) {
        BillingAccess.requireAdmin(actor);
        List<BillingRecords.PointLedgerEntry> rows = billing.findLedgerForAdmin(userId,
                blankToNull(entryType), blankToNull(businessType), from, to,
                (long) (page - 1) * size, size);
        long total = billing.countLedgerForAdmin(userId, blankToNull(entryType),
                blankToNull(businessType), from, to);
        return PageResponse.of(rows.stream().map(BookkeepingService::toResponse).toList(), page,
                size, total);
    }

    /** Net movement for a ledger filter, independent of paging. */
    @Transactional(readOnly = true)
    public BookkeepingDtos.LedgerSummaryResponse ledgerSummary(AuthenticatedAccount actor,
                                                               Long userId, String entryType,
                                                               String businessType,
                                                               OffsetDateTime from,
                                                               OffsetDateTime to) {
        BillingAccess.requireAdmin(actor);
        String type = blankToNull(entryType);
        String business = blankToNull(businessType);
        BookkeepingRecords.LedgerDelta delta =
                billing.sumLedgerForAdmin(userId, type, business, from, to);
        return new BookkeepingDtos.LedgerSummaryResponse(
                billing.countLedgerForAdmin(userId, type, business, from, to),
                delta.availableDelta(), delta.frozenDelta());
    }

    @Transactional(readOnly = true)
    public BookkeepingDtos.BookkeepingSummaryResponse summary(AuthenticatedAccount actor) {
        BillingAccess.requireAdmin(actor);
        RateCardResolver.RateCard card = rateCards.current();
        BookkeepingRecords.BalanceTotal balances = billing.sumPointBalances();

        BookkeepingDtos.RechargeSummaryResponse recharge = new BookkeepingDtos.RechargeSummaryResponse(
                billing.countOrdersByStatus(), billing.countSucceededOrders(),
                billing.sumSucceededAmountCents(), billing.sumSucceededPoints(),
                billing.sumSucceededGiftPoints());
        BookkeepingDtos.InvoiceSummaryResponse invoice = new BookkeepingDtos.InvoiceSummaryResponse(
                invoices.countByStatus(), invoices.sumDeliveredAmountCents());
        BookkeepingDtos.FeedbackSummaryResponse feedbackSummary =
                new BookkeepingDtos.FeedbackSummaryResponse(feedback.countByStatus());
        BookkeepingDtos.RatePlanResponse ratePlan = new BookkeepingDtos.RatePlanResponse(
                card.production(), card.versionId() == null ? null : Long.toString(card.versionId()),
                card.versionNo(), card.pointsPerYuan());
        BookkeepingRecords.LedgerDelta ledger = billing.sumLedgerForAdmin(null, null, null, null,
                null);
        BookkeepingDtos.LedgerSummaryResponse ledgerSummary = new BookkeepingDtos.LedgerSummaryResponse(
                billing.countLedgerForAdmin(null, null, null, null, null),
                ledger.availableDelta(), ledger.frozenDelta());

        return new BookkeepingDtos.BookkeepingSummaryResponse(OffsetDateTime.now(clock), recharge,
                invoice, feedbackSummary, ledgerSummary, ratePlan, balances.availablePoints(),
                balances.frozenPoints(), billing.distinctLedgerEntryTypes(),
                billing.distinctLedgerBusinessTypes());
    }

    private static BookkeepingDtos.PointLedgerEntryResponse toResponse(
            BillingRecords.PointLedgerEntry entry) {
        return new BookkeepingDtos.PointLedgerEntryResponse(Long.toString(entry.id()),
                Long.toString(entry.userId()), entry.availableDelta(), entry.frozenDelta(),
                entry.availableAfter(), entry.frozenAfter(), entry.entryType(),
                entry.businessType(), Long.toString(entry.businessId()),
                entry.operatorId() == null ? null : Long.toString(entry.operatorId()),
                entry.reason(), entry.createdAt());
    }

    /** Treats an empty query parameter as "no filter" rather than as "match the empty string". */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
