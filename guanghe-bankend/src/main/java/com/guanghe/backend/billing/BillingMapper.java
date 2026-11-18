package com.guanghe.backend.billing;

import com.guanghe.backend.billing.bookkeeping.BookkeepingRecords;
import com.guanghe.backend.billing.bookkeeping.StatusCount;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Data access for the M8 commercial control plane.
 *
 * <p>Point balances live in the shared {@code business.point_account} / {@code business.point_ledger}
 * tables — the same ones the task and workflow control planes use. Only the credit direction is
 * new here; freeze/settle/release stay owned by the task and workflow mappers and are untouched.
 */
@Mapper
public interface BillingMapper {

    String ORDER_COLUMNS = """
            id, order_no AS orderNo, user_id AS userId, status, provider,
            amount_cents AS amountCents, points, gift_points AS giftPoints,
            provider_order_no AS providerOrderNo,
            provider_prepay_id AS providerPrepayId, provider_code_url AS providerCodeUrl,
            idempotency_key AS idempotencyKey, request_hash AS requestHash,
            return_context AS returnContext, pricing_version_id AS pricingVersionId,
            expires_at AS expiresAt,
            paid_at AS paidAt, closed_at AS closedAt, failed_at AS failedAt,
            failure_code AS failureCode, version, created_at AS createdAt, updated_at AS updatedAt
            """;

    String ADJUSTMENT_COLUMNS = """
            id, user_id AS userId, points, direction, reason, operator_id AS operatorId,
            idempotency_key AS idempotencyKey, request_hash AS requestHash,
            confirmation_token_hash AS confirmationTokenHash,
            confirmation_expires_at AS confirmationExpiresAt, status, ledger_id AS ledgerId,
            applied_at AS appliedAt, created_at AS createdAt, updated_at AS updatedAt
            """;

    // ---------------------------------------------------------------- recharge order

    @Insert("""
            INSERT INTO business.recharge_order(id,order_no,user_id,status,provider,amount_cents,
              points,gift_points,provider_order_no,provider_prepay_id,provider_code_url,
              idempotency_key,request_hash,return_context,pricing_version_id,expires_at,paid_at,
              closed_at,failed_at,failure_code,version,created_at,updated_at)
            VALUES(#{order.id},#{order.orderNo},#{order.userId},#{order.status},#{order.provider},
              #{order.amountCents},#{order.points},#{order.giftPoints},#{order.providerOrderNo},
              #{order.providerPrepayId},#{order.providerCodeUrl},#{order.idempotencyKey},
              #{order.requestHash},#{order.returnContext},#{order.pricingVersionId},
              #{order.expiresAt},#{order.paidAt},#{order.closedAt},#{order.failedAt},
              #{order.failureCode},#{order.version},#{order.createdAt},#{order.updatedAt})
            """)
    int insertOrder(@Param("order") BillingRecords.RechargeOrder order);

    @Select("SELECT " + ORDER_COLUMNS + " FROM business.recharge_order WHERE id=#{id}")
    Optional<BillingRecords.RechargeOrder> findOrderById(@Param("id") long id);

    @Select("SELECT " + ORDER_COLUMNS + " FROM business.recharge_order WHERE id=#{id} FOR UPDATE")
    Optional<BillingRecords.RechargeOrder> lockOrderById(@Param("id") long id);

    @Select("SELECT " + ORDER_COLUMNS + " FROM business.recharge_order WHERE order_no=#{orderNo}")
    Optional<BillingRecords.RechargeOrder> findOrderByOrderNo(@Param("orderNo") String orderNo);

    @Select("SELECT " + ORDER_COLUMNS + " FROM business.recharge_order WHERE provider=#{provider}"
            + " AND provider_order_no=#{providerOrderNo}")
    Optional<BillingRecords.RechargeOrder> findOrderByProviderOrderNo(
            @Param("provider") String provider, @Param("providerOrderNo") String providerOrderNo);

    @Select("SELECT " + ORDER_COLUMNS + " FROM business.recharge_order WHERE idempotency_key=#{key}")
    Optional<BillingRecords.RechargeOrder> findOrderByIdempotencyKey(@Param("key") String key);

    @Select("<script>SELECT " + ORDER_COLUMNS + " FROM business.recharge_order"
            + " WHERE user_id=#{userId}"
            + " <if test='status != null'>AND status=#{status}</if>"
            + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<BillingRecords.RechargeOrder> findOrdersByUser(@Param("userId") long userId,
                                                        @Param("status") String status,
                                                        @Param("offset") long offset,
                                                        @Param("size") int size);

    @Select("""
            <script>
            SELECT count(*) FROM business.recharge_order WHERE user_id=#{userId}
            <if test="status != null">AND status=#{status}</if>
            </script>
            """)
    long countOrdersByUser(@Param("userId") long userId, @Param("status") String status);

    @Select("<script>SELECT " + ORDER_COLUMNS + " FROM business.recharge_order"
            + " <where><if test='status != null'>status=#{status}</if>"
            + " <if test='userId != null'>AND user_id=#{userId}</if></where>"
            + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<BillingRecords.RechargeOrder> findOrdersForAdmin(@Param("userId") Long userId,
                                                          @Param("status") String status,
                                                          @Param("offset") long offset,
                                                          @Param("size") int size);

    @Select("""
            <script>
            SELECT count(*) FROM business.recharge_order
            <where>
              <if test="status != null">status=#{status}</if>
              <if test="userId != null">AND user_id=#{userId}</if>
            </where>
            </script>
            """)
    long countOrdersForAdmin(@Param("userId") Long userId, @Param("status") String status);

    /**
     * Compare-and-set transition. The {@code expectedFrom} predicate is what makes an illegal state
     * jump impossible rather than merely unlikely: a losing racer updates zero rows.
     */
    @Update("""
            UPDATE business.recharge_order SET status=#{to},
              provider_order_no=COALESCE(#{providerOrderNo},provider_order_no),
              provider_prepay_id=COALESCE(#{providerPrepayId},provider_prepay_id),
              provider_code_url=COALESCE(#{providerCodeUrl},provider_code_url),
              paid_at=#{paidAt}, closed_at=#{closedAt}, failed_at=#{failedAt},
              failure_code=#{failureCode}, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND status=#{expectedFrom}
            """)
    int transitionOrder(@Param("id") long id, @Param("expectedFrom") String expectedFrom,
                        @Param("to") String to, @Param("providerOrderNo") String providerOrderNo,
                        @Param("providerPrepayId") String providerPrepayId,
                        @Param("providerCodeUrl") String providerCodeUrl,
                        @Param("paidAt") OffsetDateTime paidAt,
                        @Param("closedAt") OffsetDateTime closedAt,
                        @Param("failedAt") OffsetDateTime failedAt,
                        @Param("failureCode") String failureCode,
                        @Param("now") OffsetDateTime now);

    /** Expires every payable order whose deadline has passed. Returns how many were closed. */
    @Update("""
            UPDATE business.recharge_order SET status='EXPIRED', closed_at=#{now},
              version=version+1, updated_at=#{now}
            WHERE status IN ('CREATED','PAYING') AND expires_at <= #{now}
            """)
    int expireDueOrders(@Param("now") OffsetDateTime now);

    /**
     * How many orders this account has created since a moment. The throttle counts real rows rather
     * than a parallel counter, so a rolled-back attempt does not consume the caller's budget and a
     * counter can never drift away from what actually happened.
     */
    @Select("SELECT count(*) FROM business.recharge_order"
            + " WHERE user_id=#{userId} AND created_at >= #{since}")
    long countOrdersCreatedSince(@Param("userId") long userId,
                                 @Param("since") OffsetDateTime since);

    // ---------------------------------------------------------------- bookkeeping

    @Select("SELECT count(*) FROM business.recharge_order WHERE status='SUCCEEDED'")
    long countSucceededOrders();

    @Select("SELECT COALESCE(sum(amount_cents),0) FROM business.recharge_order"
            + " WHERE status='SUCCEEDED'")
    long sumSucceededAmountCents();

    @Select("SELECT COALESCE(sum(points),0) FROM business.recharge_order WHERE status='SUCCEEDED'")
    long sumSucceededPoints();

    @Select("SELECT COALESCE(sum(gift_points),0) FROM business.recharge_order"
            + " WHERE status='SUCCEEDED'")
    long sumSucceededGiftPoints();

    /**
     * Ledger search for operators. Read-only by construction: no update or delete path exists
     * against the ledger anywhere in this mapper.
     */
    @Select("""
            <script>
            SELECT id,user_id AS userId,available_delta AS availableDelta,
              frozen_delta AS frozenDelta,available_after AS availableAfter,
              frozen_after AS frozenAfter,entry_type AS entryType,business_type AS businessType,
              business_id AS businessId,operator_id AS operatorId,reason,created_at AS createdAt
            FROM business.point_ledger
            <where>
              <if test="userId != null">user_id=#{userId}</if>
              <if test="entryType != null">AND entry_type=#{entryType}</if>
              <if test="businessType != null">AND business_type=#{businessType}</if>
              <if test="from != null">AND created_at &gt;= #{from}</if>
              <if test="to != null">AND created_at &lt; #{to}</if>
            </where>
            ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<BillingRecords.PointLedgerEntry> findLedgerForAdmin(@Param("userId") Long userId,
                                                             @Param("entryType") String entryType,
                                                             @Param("businessType") String businessType,
                                                             @Param("from") OffsetDateTime from,
                                                             @Param("to") OffsetDateTime to,
                                                             @Param("offset") long offset,
                                                             @Param("size") int size);

    @Select("""
            <script>
            SELECT count(*) FROM business.point_ledger
            <where>
              <if test="userId != null">user_id=#{userId}</if>
              <if test="entryType != null">AND entry_type=#{entryType}</if>
              <if test="businessType != null">AND business_type=#{businessType}</if>
              <if test="from != null">AND created_at &gt;= #{from}</if>
              <if test="to != null">AND created_at &lt; #{to}</if>
            </where>
            </script>
            """)
    long countLedgerForAdmin(@Param("userId") Long userId,
                             @Param("entryType") String entryType,
                             @Param("businessType") String businessType,
                             @Param("from") OffsetDateTime from,
                             @Param("to") OffsetDateTime to);

    /**
     * Net movement across exactly the ledger rows {@link #findLedgerForAdmin} would return for the
     * same filter, so the figure above a page of results always describes that page's selection.
     */
    @Select("""
            <script>
            SELECT COALESCE(sum(available_delta),0) AS availableDelta,
                   COALESCE(sum(frozen_delta),0) AS frozenDelta
            FROM business.point_ledger
            <where>
              <if test="userId != null">user_id=#{userId}</if>
              <if test="entryType != null">AND entry_type=#{entryType}</if>
              <if test="businessType != null">AND business_type=#{businessType}</if>
              <if test="from != null">AND created_at &gt;= #{from}</if>
              <if test="to != null">AND created_at &lt; #{to}</if>
            </where>
            </script>
            """)
    BookkeepingRecords.LedgerDelta sumLedgerForAdmin(@Param("userId") Long userId,
                                                     @Param("entryType") String entryType,
                                                     @Param("businessType") String businessType,
                                                     @Param("from") OffsetDateTime from,
                                                     @Param("to") OffsetDateTime to);

    /**
     * Outstanding balances across every account on the platform, whichever module moved them.
     *
     * <p>{@code point_account} is one shared balance table, so this deliberately is not filtered to
     * recharge rows — the caller must present it as a platform-wide liability.
     */
    @Select("SELECT COALESCE(sum(available_points),0) AS availablePoints,"
            + " COALESCE(sum(frozen_points),0) AS frozenPoints FROM business.point_account")
    BookkeepingRecords.BalanceTotal sumPointBalances();

    /** Per-status order counts. Matches the shape {@code countByStatus()} returns elsewhere. */
    @Select("SELECT status, count(*) AS total FROM business.recharge_order GROUP BY status")
    List<StatusCount> countOrdersByStatus();

    /**
     * The ledger types that actually occur, so the operator filter is built from data rather than
     * from a second hand-maintained copy of the SQL {@code CHECK} constraint's vocabulary.
     */
    @Select("SELECT DISTINCT entry_type FROM business.point_ledger ORDER BY entry_type")
    List<String> distinctLedgerEntryTypes();

    @Select("SELECT DISTINCT business_type FROM business.point_ledger ORDER BY business_type")
    List<String> distinctLedgerBusinessTypes();

    // ---------------------------------------------------------------- order events

    @Insert("""
            INSERT INTO business.recharge_order_event(id,order_id,user_id,source_event_id,event_type,
              from_status,to_status,source,provider_event_id,amount_cents,request_hash,error_code,
              details,created_at)
            VALUES(#{event.id},#{event.orderId},#{event.userId},#{event.sourceEventId},
              #{event.eventType},#{event.fromStatus},#{event.toStatus},#{event.source},
              #{event.providerEventId},#{event.amountCents},#{event.requestHash},#{event.errorCode},
              CAST(#{event.details} AS jsonb),#{event.createdAt})
            ON CONFLICT (order_id,source_event_id) DO NOTHING
            """)
    int insertEvent(@Param("event") BillingRecords.RechargeOrderEvent event);

    @Select("SELECT id,order_id AS orderId,user_id AS userId,source_event_id AS sourceEventId,"
            + "event_type AS eventType,from_status AS fromStatus,to_status AS toStatus,source,"
            + "provider_event_id AS providerEventId,amount_cents AS amountCents,"
            + "request_hash AS requestHash,error_code AS errorCode,details::text AS details,"
            + "created_at AS createdAt FROM business.recharge_order_event"
            + " WHERE order_id=#{orderId} AND source_event_id=#{sourceEventId}")
    Optional<BillingRecords.RechargeOrderEvent> findEvent(@Param("orderId") long orderId,
                                                          @Param("sourceEventId") UUID sourceEventId);

    @Select("SELECT id,order_id AS orderId,user_id AS userId,source_event_id AS sourceEventId,"
            + "event_type AS eventType,from_status AS fromStatus,to_status AS toStatus,source,"
            + "provider_event_id AS providerEventId,amount_cents AS amountCents,"
            + "request_hash AS requestHash,error_code AS errorCode,details::text AS details,"
            + "created_at AS createdAt FROM business.recharge_order_event"
            + " WHERE order_id=#{orderId} ORDER BY created_at ASC, id ASC")
    List<BillingRecords.RechargeOrderEvent> listEvents(@Param("orderId") long orderId);

    // ---------------------------------------------------------------- manual adjustment

    @Insert("""
            INSERT INTO business.manual_point_adjustment(id,user_id,points,direction,reason,
              operator_id,idempotency_key,request_hash,confirmation_token_hash,
              confirmation_expires_at,status,ledger_id,applied_at,created_at,updated_at)
            VALUES(#{adjustment.id},#{adjustment.userId},#{adjustment.points},
              #{adjustment.direction},#{adjustment.reason},#{adjustment.operatorId},
              #{adjustment.idempotencyKey},#{adjustment.requestHash},
              #{adjustment.confirmationTokenHash},#{adjustment.confirmationExpiresAt},
              #{adjustment.status},#{adjustment.ledgerId},#{adjustment.appliedAt},
              #{adjustment.createdAt},#{adjustment.updatedAt})
            """)
    int insertAdjustment(@Param("adjustment") BillingRecords.ManualAdjustment adjustment);

    @Select("SELECT " + ADJUSTMENT_COLUMNS
            + " FROM business.manual_point_adjustment WHERE idempotency_key=#{key}")
    Optional<BillingRecords.ManualAdjustment> findAdjustmentByIdempotencyKey(@Param("key") String key);

    @Select("SELECT " + ADJUSTMENT_COLUMNS
            + " FROM business.manual_point_adjustment WHERE id=#{id}")
    Optional<BillingRecords.ManualAdjustment> findAdjustmentById(@Param("id") long id);

    @Select("SELECT " + ADJUSTMENT_COLUMNS
            + " FROM business.manual_point_adjustment WHERE id=#{id} FOR UPDATE")
    Optional<BillingRecords.ManualAdjustment> lockAdjustmentById(@Param("id") long id);

    @Select("SELECT " + ADJUSTMENT_COLUMNS
            + " FROM business.manual_point_adjustment WHERE confirmation_token_hash=#{hash}")
    Optional<BillingRecords.ManualAdjustment> findAdjustmentByTokenHash(@Param("hash") String hash);

    /** Applies a pending adjustment exactly once. */
    @Update("""
            UPDATE business.manual_point_adjustment SET status='APPLIED', ledger_id=#{ledgerId},
              applied_at=#{now}, updated_at=#{now}
            WHERE id=#{id} AND status='PENDING_CONFIRMATION'
            """)
    int markAdjustmentApplied(@Param("id") long id, @Param("ledgerId") long ledgerId,
                              @Param("now") OffsetDateTime now);

    @Select("<script>SELECT " + ADJUSTMENT_COLUMNS + " FROM business.manual_point_adjustment"
            + " <where><if test='userId != null'>user_id=#{userId}</if>"
            + " <if test='status != null'>AND status=#{status}</if></where>"
            + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<BillingRecords.ManualAdjustment> findAdjustmentsForAdmin(@Param("userId") Long userId,
                                                                  @Param("status") String status,
                                                                  @Param("offset") long offset,
                                                                  @Param("size") int size);

    @Select("""
            <script>
            SELECT count(*) FROM business.manual_point_adjustment
            <where>
              <if test="userId != null">user_id=#{userId}</if>
              <if test="status != null">AND status=#{status}</if>
            </where>
            </script>
            """)
    long countAdjustmentsForAdmin(@Param("userId") Long userId, @Param("status") String status);

    // ---------------------------------------------------------------- point account

    @Select("SELECT user_id AS userId,available_points AS availablePoints,"
            + "frozen_points AS frozenPoints,version,created_at AS createdAt,updated_at AS updatedAt"
            + " FROM business.point_account WHERE user_id=#{userId} FOR UPDATE")
    Optional<BillingRecords.PointAccount> lockPointAccount(@Param("userId") long userId);

    @Select("SELECT user_id AS userId,available_points AS availablePoints,"
            + "frozen_points AS frozenPoints,version,created_at AS createdAt,updated_at AS updatedAt"
            + " FROM business.point_account WHERE user_id=#{userId}")
    Optional<BillingRecords.PointAccount> findPointAccount(@Param("userId") long userId);

    /**
     * Credits available points. The overflow guard keeps the value inside the column's integer
     * range; the CHECK constraint on the table independently forbids a negative balance.
     */
    @Update("UPDATE business.point_account SET available_points=available_points+#{points},"
            + "version=version+1,updated_at=#{now} WHERE user_id=#{userId}"
            + " AND available_points<=2147483647-#{points}")
    int creditAvailable(@Param("userId") long userId, @Param("points") int points,
                        @Param("now") OffsetDateTime now);

    @Insert("""
            INSERT INTO business.point_ledger(id,user_id,available_delta,frozen_delta,
              available_after,frozen_after,entry_type,business_type,business_id,idempotency_key,
              operator_id,reason,created_at)
            VALUES(#{id},#{userId},#{availableDelta},0,#{availableAfter},#{frozenAfter},
              #{entryType},#{businessType},#{businessId},#{key},#{operatorId},#{reason},#{now})
            """)
    int insertCreditLedger(@Param("id") long id, @Param("userId") long userId,
                           @Param("availableDelta") int availableDelta,
                           @Param("availableAfter") int availableAfter,
                           @Param("frozenAfter") int frozenAfter,
                           @Param("entryType") String entryType,
                           @Param("businessType") String businessType,
                           @Param("businessId") long businessId, @Param("key") String key,
                           @Param("operatorId") Long operatorId, @Param("reason") String reason,
                           @Param("now") OffsetDateTime now);

    // ---------------------------------------------------------------- operational audit

    @Insert("""
            INSERT INTO business.operational_audit_event(id,actor_type,actor_user_id,actor_ref,
              target_type,target_id,event_type,outcome,trace_id,source_event_id,idempotency_key,
              details,created_at)
            VALUES(#{event.id},#{event.actorType},#{event.actorUserId},#{event.actorRef},
              #{event.targetType},#{event.targetId},#{event.eventType},#{event.outcome},
              #{event.traceId},#{event.sourceEventId},#{event.idempotencyKey},
              CAST(#{event.details} AS jsonb),#{event.createdAt})
            ON CONFLICT DO NOTHING
            """)
    int insertOperationalAudit(@Param("event") BillingRecords.OperationalAuditEvent event);

    @Select("""
            <script>
            SELECT id,actor_type AS actorType,actor_user_id AS actorUserId,actor_ref AS actorRef,
              target_type AS targetType,target_id AS targetId,event_type AS eventType,outcome,
              trace_id AS traceId,source_event_id AS sourceEventId,
              idempotency_key AS idempotencyKey,details::text AS details,
              created_at AS createdAt
            FROM business.operational_audit_event
            <where>
              <if test="actorType != null">actor_type=#{actorType}</if>
              <if test="targetType != null">AND target_type=#{targetType}</if>
              <if test="targetId != null">AND target_id=#{targetId}</if>
              <if test="eventType != null">AND event_type=#{eventType}</if>
            </where>
            ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<BillingRecords.OperationalAuditEvent> findOperationalAudit(
            @Param("actorType") String actorType, @Param("targetType") String targetType,
            @Param("targetId") Long targetId, @Param("eventType") String eventType,
            @Param("offset") long offset, @Param("size") int size);

    @Select("""
            <script>
            SELECT count(*) FROM business.operational_audit_event
            <where>
              <if test="actorType != null">actor_type=#{actorType}</if>
              <if test="targetType != null">AND target_type=#{targetType}</if>
              <if test="targetId != null">AND target_id=#{targetId}</if>
              <if test="eventType != null">AND event_type=#{eventType}</if>
            </where>
            </script>
            """)
    long countOperationalAudit(@Param("actorType") String actorType,
                               @Param("targetType") String targetType,
                               @Param("targetId") Long targetId,
                               @Param("eventType") String eventType);
}
