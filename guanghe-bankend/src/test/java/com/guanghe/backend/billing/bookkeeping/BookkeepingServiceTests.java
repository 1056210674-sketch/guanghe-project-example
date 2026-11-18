package com.guanghe.backend.billing.bookkeeping;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.BillingMapper;
import com.guanghe.backend.billing.BillingProperties;
import com.guanghe.backend.billing.BillingRecords;
import com.guanghe.backend.billing.feedback.FeedbackMapper;
import com.guanghe.backend.billing.invoice.InvoiceMapper;
import com.guanghe.backend.billing.pricing.PricingMapper;
import com.guanghe.backend.billing.pricing.PricingRecords;
import com.guanghe.backend.billing.pricing.RateCardResolver;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The administrator bookkeeping view.
 *
 * <p>Two properties matter more than the arithmetic: the surface is closed to non-operators, and the
 * figures are reported together with whether a published rate card produced them. A deployment
 * running on the placeholder must say so on this page rather than showing the development default as
 * settled commercial data.
 */
class BookkeepingServiceTests {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
    private static final long OPERATOR_ID = 1L;
    private static final long USER_ID = 5001L;

    private BillingMapper billing;
    private InvoiceMapper invoices;
    private FeedbackMapper feedback;
    private PricingMapper pricing;
    private BookkeepingService service;

    @BeforeEach
    void setUp() {
        billing = mock(BillingMapper.class);
        invoices = mock(InvoiceMapper.class);
        feedback = mock(FeedbackMapper.class);
        pricing = mock(PricingMapper.class);
        BillingProperties properties = new BillingProperties();
        service = new BookkeepingService(billing, invoices, feedback,
                new RateCardResolver(pricing, properties), CLOCK);
    }

    // ---------------------------------------------------------------- access

    @Test
    void theBookkeepingSurfaceIsClosedToNonAdministrators() {
        assertThatThrownBy(() -> service.summary(user(USER_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.ledger(user(USER_ID), null, null, null, null, null, 1, 20))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------- ledger search

    @Test
    void theLedgerIsPagedWithStringTypedIdsSoTheBrowserCannotLosePrecision() {
        OffsetDateTime at = OffsetDateTime.now(CLOCK);
        when(billing.findLedgerForAdmin(any(), any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of(new BillingRecords.PointLedgerEntry(9_007_199_254_740_993L,
                        USER_ID, 10_000, 0, 10_100, 0, "RECHARGE", "RECHARGE",
                        9_007_199_254_740_994L, null, "充值 100 元", at)));
        when(billing.countLedgerForAdmin(any(), any(), any(), any(), any())).thenReturn(1L);

        PageResponse<BookkeepingDtos.PointLedgerEntryResponse> page =
                service.ledger(admin(), USER_ID, "RECHARGE", "RECHARGE", null, null, 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.items()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("9007199254740993");
            assertThat(entry.businessId()).isEqualTo("9007199254740994");
            assertThat(entry.operatorId()).isNull();
            assertThat(entry.availableAfter()).isEqualTo(10_100);
        });
    }

    @Test
    void anEmptyFilterValueMeansNoFilterRatherThanMatchingTheEmptyString() {
        when(billing.findLedgerForAdmin(any(), any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(billing.countLedgerForAdmin(any(), any(), any(), any(), any())).thenReturn(0L);

        service.ledger(admin(), null, "  ", "", null, null, 1, 20);

        verify(billing).findLedgerForAdmin(isNull(), isNull(), isNull(), isNull(), isNull(), eq(0L),
                eq(20));
    }

    @Test
    void theLedgerSummaryIsComputedOverTheSameFilterAsTheRows() {
        OffsetDateTime from = OffsetDateTime.parse("2026-09-01T00:00:00Z");
        when(billing.sumLedgerForAdmin(eq(USER_ID), eq("RECHARGE"), isNull(), eq(from), isNull()))
                .thenReturn(new BookkeepingRecords.LedgerDelta(10_000, 0));
        when(billing.countLedgerForAdmin(eq(USER_ID), eq("RECHARGE"), isNull(), eq(from), isNull()))
                .thenReturn(3L);

        BookkeepingDtos.LedgerSummaryResponse summary =
                service.ledgerSummary(admin(), USER_ID, "RECHARGE", null, from, null);

        assertThat(summary.entries()).isEqualTo(3);
        assertThat(summary.availableDelta()).isEqualTo(10_000);
        assertThat(summary.frozenDelta()).isZero();
    }

    // ---------------------------------------------------------------- totals

    @Test
    void theSummaryReportsWhatWasSettledNotWhatWasAttempted() {
        stubEmptyEverything();
        when(billing.countOrdersByStatus()).thenReturn(List.of(
                new StatusCount("SUCCEEDED", 2), new StatusCount("EXPIRED", 5)));
        when(billing.countSucceededOrders()).thenReturn(2L);
        when(billing.sumSucceededAmountCents()).thenReturn(30_000L);
        when(billing.sumSucceededPoints()).thenReturn(30_000L);
        when(billing.sumSucceededGiftPoints()).thenReturn(1_000L);

        BookkeepingDtos.BookkeepingSummaryResponse summary = service.summary(admin());

        assertThat(summary.recharge().succeededOrders()).isEqualTo(2L);
        assertThat(summary.recharge().succeededAmountCents()).isEqualTo(30_000L);
        assertThat(summary.recharge().basePoints()).isEqualTo(30_000L);
        assertThat(summary.recharge().giftPoints()).isEqualTo(1_000L);
        assertThat(summary.recharge().ordersByStatus()).hasSize(2);
    }

    @Test
    void theSummarySaysWhenTheNumbersCameFromThePlaceholderRateRatherThanAPublishedOne() {
        stubEmptyEverything();
        when(pricing.findActiveVersion()).thenReturn(Optional.empty());

        BookkeepingDtos.BookkeepingSummaryResponse placeholder = service.summary(admin());
        assertThat(placeholder.ratePlan().productionRate()).isFalse();
        assertThat(placeholder.ratePlan().versionId()).isNull();
        assertThat(placeholder.ratePlan().pointsPerYuan()).isEqualTo(100);

        stubEmptyEverything();
        when(pricing.findActiveVersion()).thenReturn(Optional.of(new PricingRecords.PricingVersion(
                42L, 7, "ACTIVE", 250, 1_000L, 500_000L, "正式比例", OPERATOR_ID, OPERATOR_ID,
                OffsetDateTime.now(CLOCK), null, 2, OffsetDateTime.now(CLOCK),
                OffsetDateTime.now(CLOCK))));

        BookkeepingDtos.BookkeepingSummaryResponse published = service.summary(admin());
        assertThat(published.ratePlan().productionRate()).isTrue();
        assertThat(published.ratePlan().versionId()).isEqualTo("42");
        assertThat(published.ratePlan().versionNo()).isEqualTo(7);
        assertThat(published.ratePlan().pointsPerYuan()).isEqualTo(250);
    }

    @Test
    void thePlatformBalanceIsReportedAsPlatformWideBecauseTheBalanceTableIsShared() {
        stubEmptyEverything();
        when(billing.sumPointBalances()).thenReturn(new BookkeepingRecords.BalanceTotal(120_000, 3_000));

        BookkeepingDtos.BookkeepingSummaryResponse summary = service.summary(admin());

        // point_account is moved by recharges, task execution and workflow runs alike, so the figure
        // is presented as the platform's outstanding liability, not as this module's total.
        assertThat(summary.platformAvailablePoints()).isEqualTo(120_000);
        assertThat(summary.platformFrozenPoints()).isEqualTo(3_000);
    }

    @Test
    void theLedgerFilterIsBuiltFromTheTypesThatActuallyOccur() {
        stubEmptyEverything();
        when(billing.distinctLedgerEntryTypes())
                .thenReturn(List.of("ADMIN_ADJUSTMENT", "RECHARGE", "TASK_SETTLE"));
        when(billing.distinctLedgerBusinessTypes())
                .thenReturn(List.of("RECHARGE", "TASK"));

        BookkeepingDtos.BookkeepingSummaryResponse summary = service.summary(admin());

        assertThat(summary.ledgerEntryTypes()).contains("RECHARGE", "ADMIN_ADJUSTMENT");
        assertThat(summary.ledgerBusinessTypes()).contains("RECHARGE");
    }

    @Test
    void theInvoiceAndFeedbackTotalsComeFromAggregatesRatherThanWholeTableScans() {
        stubEmptyEverything();
        when(invoices.countByStatus()).thenReturn(List.of(new StatusCount("DELIVERED", 1),
                new StatusCount("APPROVED", 4)));
        when(invoices.sumDeliveredAmountCents()).thenReturn(12_345L);
        when(feedback.countByStatus()).thenReturn(List.of(new StatusCount("SUBMITTED", 3)));

        BookkeepingDtos.BookkeepingSummaryResponse summary = service.summary(admin());

        assertThat(summary.invoice().deliveredAmountCents()).isEqualTo(12_345L);
        assertThat(summary.invoice().applicationsByStatus())
                .extracting(StatusCount::status).containsExactly("DELIVERED", "APPROVED");
        assertThat(summary.feedback().itemsByStatus()).singleElement()
                .satisfies(count -> assertThat(count.total()).isEqualTo(3));
    }

    @Test
    void theSummaryCarriesTheMomentItWasGeneratedSoAStaleScreenIsDetectable() {
        stubEmptyEverything();

        assertThat(service.summary(admin()).generatedAt()).isEqualTo(OffsetDateTime.now(CLOCK));
    }

    // ---------------------------------------------------------------- infrastructure

    private void stubEmptyEverything() {
        when(billing.countOrdersByStatus()).thenReturn(new ArrayList<>());
        when(billing.countSucceededOrders()).thenReturn(0L);
        when(billing.sumSucceededAmountCents()).thenReturn(0L);
        when(billing.sumSucceededPoints()).thenReturn(0L);
        when(billing.sumSucceededGiftPoints()).thenReturn(0L);
        when(billing.sumPointBalances()).thenReturn(new BookkeepingRecords.BalanceTotal(0, 0));
        when(billing.distinctLedgerEntryTypes()).thenReturn(List.of());
        when(billing.distinctLedgerBusinessTypes()).thenReturn(List.of());
        when(billing.sumLedgerForAdmin(any(), any(), any(), any(), any()))
                .thenReturn(new BookkeepingRecords.LedgerDelta(0, 0));
        when(billing.countLedgerForAdmin(any(), any(), any(), any(), any())).thenReturn(0L);
        when(invoices.countByStatus()).thenReturn(List.of());
        when(invoices.sumDeliveredAmountCents()).thenReturn(0L);
        when(feedback.countByStatus()).thenReturn(List.of());
        when(pricing.findActiveVersion()).thenReturn(Optional.empty());
    }

    private static AuthenticatedAccount admin() {
        return account(OPERATOR_ID, AccountRole.ADMIN);
    }

    private static AuthenticatedAccount user(long id) {
        return account(id, AccountRole.USER);
    }

    private static AuthenticatedAccount account(long id, AccountRole role) {
        return new AuthenticatedAccount(id, "user" + id, role, 1, AccountStatus.ACTIVE, null, null,
                null);
    }
}
