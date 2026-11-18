package com.guanghe.backend.billing.bookkeeping;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator bookkeeping: the point ledger and the commercial totals.
 *
 * <p>Read-only. There is no endpoint here that moves a balance; corrections go through the
 * two-step manual adjustment flow, which carries its own confirmation and audit trail.
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/billing")
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BookkeepingController {

    private final BookkeepingService bookkeeping;

    public BookkeepingController(BookkeepingService bookkeeping) {
        this.bookkeeping = bookkeeping;
    }

    @GetMapping("/point-ledger")
    public ApiResponse<PageResponse<BookkeepingDtos.PointLedgerEntryResponse>> ledger(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @RequestParam(required = false) @Positive Long userId,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime to,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(bookkeeping.ledger(actor, userId, entryType, businessType, from,
                to, page, size));
    }

    /**
     * Net movement for a ledger filter.
     *
     * <p>Separate from the page because a page of results cannot carry the total for the selection
     * without the client having to trust that the two agree.
     */
    @GetMapping("/point-ledger/summary")
    public ApiResponse<BookkeepingDtos.LedgerSummaryResponse> ledgerSummary(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @RequestParam(required = false) @Positive Long userId,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime to) {
        return ApiResponse.success(
                bookkeeping.ledgerSummary(actor, userId, entryType, businessType, from, to));
    }

    /**
     * Totals across the whole ledger, plus the vocabularies the filter is built from.
     *
     * <p>The type lists come from the rows that exist rather than a second copy of the SQL
     * constraint, so the admin filter can never offer a value the database would reject.
     */
    @GetMapping("/bookkeeping/summary")
    public ApiResponse<BookkeepingDtos.BookkeepingSummaryResponse> summary(
            @AuthenticationPrincipal AuthenticatedAccount actor) {
        return ApiResponse.success(bookkeeping.summary(actor));
    }
}
