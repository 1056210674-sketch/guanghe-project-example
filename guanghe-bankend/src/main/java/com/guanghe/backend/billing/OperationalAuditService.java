package com.guanghe.backend.billing;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.web.TraceIdHolder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only operational audit for commercial and system events.
 *
 * <p>Deliberately separate from {@code account_security_audit}, which remains scoped to account
 * security and cannot express a non-user actor.
 *
 * <p>Runs with {@link Propagation#MANDATORY}: an audit row is only ever written as part of a
 * business transaction, so the trail can never disagree with the state it describes.
 *
 * <p>Details are summaries, hashes, provider event ids, error codes and state changes only. Secrets,
 * tokens, full callback bodies and full prompts must never be passed in.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OperationalAuditService {

    public static final String ACTOR_USER = "USER";
    public static final String ACTOR_ADMIN = "ADMIN";
    public static final String ACTOR_SYSTEM = "SYSTEM";
    public static final String ACTOR_PAYMENT_PROVIDER = "PAYMENT_PROVIDER";
    public static final String ACTOR_MAIL_PROVIDER = "MAIL_PROVIDER";

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";
    public static final String OUTCOME_PENDING = "PENDING";

    public static final String RECHARGE_ORDER_CREATED = "RECHARGE_ORDER_CREATED";
    public static final String RECHARGE_ORDER_SUCCEEDED = "RECHARGE_ORDER_SUCCEEDED";
    public static final String RECHARGE_ORDER_CLOSED = "RECHARGE_ORDER_CLOSED";
    public static final String RECHARGE_CALLBACK_REJECTED = "RECHARGE_CALLBACK_REJECTED";
    public static final String RECHARGE_CALLBACK_STALE = "RECHARGE_CALLBACK_STALE";
    public static final String POINT_ADJUSTMENT_REQUESTED = "POINT_ADJUSTMENT_REQUESTED";
    public static final String POINT_ADJUSTMENT_APPLIED = "POINT_ADJUSTMENT_APPLIED";
    public static final String POINT_ADJUSTMENT_REJECTED = "POINT_ADJUSTMENT_REJECTED";

    /** Mirrors the CHECK constraint on the table. Rejected in Java for a clearer error. */
    private static final int MAX_DETAILS_BYTES = 16384;

    private final BillingMapper mapper;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;

    public OperationalAuditService(BillingMapper mapper, SnowflakeIdGenerator ids,
                                   @Qualifier("billingClock") Clock clock) {
        this.mapper = mapper;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Records one audit event inside the caller's transaction.
     *
     * @param actorUserId required for USER/ADMIN actors, must be {@code null} otherwise
     * @param actorRef    required for non-user actors; must be non-sensitive
     * @param detailsJson bounded JSON object; never secrets, tokens or raw payloads
     * @param idempotencyKey optional; when present, a repeat is silently ignored
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actorType, Long actorUserId, String actorRef, String targetType,
                       Long targetId, String eventType, String outcome, String detailsJson,
                       String idempotencyKey) {
        String details = detailsJson == null || detailsJson.isBlank() ? "{}" : detailsJson;
        if (details.getBytes(StandardCharsets.UTF_8).length > MAX_DETAILS_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        BillingRecords.OperationalAuditEvent event = new BillingRecords.OperationalAuditEvent(
                ids.nextId(), actorType, actorUserId, actorRef, targetType, targetId, eventType,
                outcome, TraceIdHolder.currentTraceId(), null, idempotencyKey, details, now());
        mapper.insertOperationalAudit(event);
    }

    /** Audit attributed to a signed-in user or administrator. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void actorUser(String actorType, long actorUserId, String targetType, Long targetId,
                          String eventType, String outcome, String detailsJson) {
        record(actorType, actorUserId, null, targetType, targetId, eventType, outcome, detailsJson,
                null);
    }

    /** Audit attributed to a system or provider actor, which never carries a user reference. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void actorSystem(String actorType, String actorRef, String targetType, Long targetId,
                            String eventType, String outcome, String detailsJson,
                            String idempotencyKey) {
        record(actorType, null, actorRef, targetType, targetId, eventType, outcome, detailsJson,
                idempotencyKey);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
