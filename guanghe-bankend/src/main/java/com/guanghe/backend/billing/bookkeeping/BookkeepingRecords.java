package com.guanghe.backend.billing.bookkeeping;

/**
 * Row shapes for the administrator bookkeeping aggregates.
 *
 * <p>Every field here is a sum over rows that already exist; nothing in this package writes.
 */
public final class BookkeepingRecords {

    private BookkeepingRecords() {
    }

    /** Net movement across the ledger rows the current filter selected. */
    public record LedgerDelta(long availableDelta, long frozenDelta) {
    }

    /**
     * Platform-wide outstanding point balances.
     *
     * <p>Deliberately not scoped to recharge: {@code point_account} is one shared balance moved by
     * recharges, task execution and workflow runs alike. The operator summary labels it as
     * platform-wide rather than presenting a slice as if it were this module's total.
     */
    public record BalanceTotal(long availablePoints, long frozenPoints) {
    }
}
