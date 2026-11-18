package com.guanghe.backend.billing;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place in the commercial domain where a point balance can go up.
 *
 * <p>Both recharge settlement and operator adjustment go through here, so there is exactly one code
 * path capable of increasing a balance and exactly one place that writes the matching ledger row.
 * Balances are never updated without a ledger entry — the two writes happen together or not at all.
 *
 * <p>Freeze / settle / release remain owned by the task and workflow control planes. This service
 * only ever moves {@code available_points}, never {@code frozen_points}, which is what keeps the
 * existing reservation invariants intact.
 *
 * <p>Runs with {@link Propagation#MANDATORY} so a caller cannot accidentally credit points outside
 * the business transaction that justifies them.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PointCreditService {

    public static final String ENTRY_RECHARGE = "RECHARGE";
    public static final String ENTRY_ADMIN_ADJUSTMENT = "ADMIN_ADJUSTMENT";
    public static final String BUSINESS_RECHARGE = "RECHARGE";
    public static final String BUSINESS_ADMIN_ADJUSTMENT = "ADMIN_ADJUSTMENT";

    private final BillingMapper mapper;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;

    public PointCreditService(BillingMapper mapper, SnowflakeIdGenerator ids,
                              @Qualifier("billingClock") Clock clock) {
        this.mapper = mapper;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Credits available points and appends the matching ledger row.
     *
     * <p>The account is locked first, so concurrent credits serialise on the row and the
     * {@code available_after} recorded on the ledger is the value that actually took effect.
     *
     * @param idempotencyKey globally unique ledger key; a repeat is rejected by the table's unique
     *                       constraint rather than silently double-crediting
     * @return the id of the ledger row that was written
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long credit(long userId, int points, String entryType, String businessType,
                       long businessId, String idempotencyKey, Long operatorId, String reason) {
        if (points <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        BillingRecords.PointAccount account = mapper.lockPointAccount(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        long after = (long) account.availablePoints() + points;
        if (after > Integer.MAX_VALUE) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (mapper.creditAvailable(userId, points, now) != 1) {
            // Either the overflow guard tripped or the account vanished; both are conflicts.
            throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
        }
        long ledgerId = ids.nextId();
        if (mapper.insertCreditLedger(ledgerId, userId, points, (int) after, account.frozenPoints(),
                entryType, businessType, businessId, idempotencyKey, operatorId, reason, now) != 1) {
            throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
        }
        return ledgerId;
    }
}
