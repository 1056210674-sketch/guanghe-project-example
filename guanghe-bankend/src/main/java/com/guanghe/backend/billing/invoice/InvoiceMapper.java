package com.guanghe.backend.billing.invoice;

import com.guanghe.backend.billing.bookkeeping.StatusCount;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Data access for the invoice control plane. */
@Mapper
public interface InvoiceMapper {

    String COLUMNS = """
            id, application_no AS applicationNo, user_id AS userId, status,
            invoice_type AS invoiceType, title_type AS titleType, title, tax_number AS taxNumber,
            amount_cents AS amountCents, delivery_email AS deliveryEmail, remark,
            reject_reason AS rejectReason, reviewer_id AS reviewerId,
            delivery_provider AS deliveryProvider, delivery_reference AS deliveryReference,
            idempotency_key AS idempotencyKey, request_hash AS requestHash,
            submitted_at AS submittedAt, reviewed_at AS reviewedAt, approved_at AS approvedAt,
            delivered_at AS deliveredAt, rejected_at AS rejectedAt, cancelled_at AS cancelledAt,
            version, created_at AS createdAt, updated_at AS updatedAt,
            invoice_number AS invoiceNumber, manual_sent_by AS manualSentBy,
            (SELECT w.status FROM business.invoice_webhook_delivery w
             WHERE w.application_id=business.invoice_application.id) AS webhookStatus,
            (SELECT w.last_error_code FROM business.invoice_webhook_delivery w
             WHERE w.application_id=business.invoice_application.id) AS webhookErrorCode
            """;

    @Insert("""
            INSERT INTO business.invoice_application(id,application_no,user_id,status,invoice_type,
              title_type,title,tax_number,amount_cents,delivery_email,remark,reject_reason,
              reviewer_id,delivery_provider,delivery_reference,idempotency_key,request_hash,
              submitted_at,reviewed_at,approved_at,delivered_at,rejected_at,cancelled_at,version,
              created_at,updated_at)
            VALUES(#{a.id},#{a.applicationNo},#{a.userId},#{a.status},#{a.invoiceType},
              #{a.titleType},#{a.title},#{a.taxNumber},#{a.amountCents},#{a.deliveryEmail},
              #{a.remark},#{a.rejectReason},#{a.reviewerId},#{a.deliveryProvider},
              #{a.deliveryReference},#{a.idempotencyKey},#{a.requestHash},#{a.submittedAt},
              #{a.reviewedAt},#{a.approvedAt},#{a.deliveredAt},#{a.rejectedAt},#{a.cancelledAt},
              #{a.version},#{a.createdAt},#{a.updatedAt})
            """)
    int insertApplication(@Param("a") InvoiceRecords.InvoiceApplication application);

    @Select("SELECT " + COLUMNS + " FROM business.invoice_application WHERE id=#{id}")
    Optional<InvoiceRecords.InvoiceApplication> findById(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM business.invoice_application WHERE id=#{id} FOR UPDATE")
    Optional<InvoiceRecords.InvoiceApplication> lockById(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM business.invoice_application WHERE idempotency_key=#{key}")
    Optional<InvoiceRecords.InvoiceApplication> findByIdempotencyKey(@Param("key") String key);

    @Select("SELECT " + COLUMNS + " FROM business.invoice_application WHERE user_id=#{userId}"
            + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}")
    List<InvoiceRecords.InvoiceApplication> findByUser(@Param("userId") long userId,
                                                       @Param("offset") long offset,
                                                       @Param("size") int size);

    @Select("SELECT count(*) FROM business.invoice_application WHERE user_id=#{userId}")
    long countByUser(@Param("userId") long userId);

    /** Counting real rows, so a rolled-back attempt does not consume the caller's budget. */
    @Select("SELECT count(*) FROM business.invoice_application"
            + " WHERE user_id=#{userId} AND submitted_at >= #{since}")
    long countSubmittedSince(@Param("userId") long userId, @Param("since") OffsetDateTime since);

    @Select("<script>SELECT " + COLUMNS + " FROM business.invoice_application"
            + " <where><if test='status != null'>status=#{status}</if>"
            + " <if test='userId != null'>AND user_id=#{userId}</if></where>"
            + " ORDER BY submitted_at ASC, id ASC LIMIT #{size} OFFSET #{offset}</script>")
    List<InvoiceRecords.InvoiceApplication> findForAdmin(@Param("userId") Long userId,
                                                         @Param("status") String status,
                                                         @Param("offset") long offset,
                                                         @Param("size") int size);

    @Select("""
            <script>
            SELECT count(*) FROM business.invoice_application
            <where>
              <if test="status != null">status=#{status}</if>
              <if test="userId != null">AND user_id=#{userId}</if>
            </where>
            </script>
            """)
    long countForAdmin(@Param("userId") Long userId, @Param("status") String status);

    /**
     * Compare-and-set transition. The {@code expectedFrom} predicate is what makes an illegal jump
     * impossible rather than merely unlikely: a losing racer, or a caller that mis-read the state,
     * updates zero rows.
     *
     * <p>Nullable timestamps are written explicitly rather than coalesced, except {@code reviewedAt},
     * which survives a later withdrawal, because "an operator looked at this" stays true.
     */
    @Update("""
            UPDATE business.invoice_application SET status=#{to},
              reviewer_id=COALESCE(#{reviewerId},reviewer_id),
              reject_reason=#{rejectReason},
              delivery_provider=#{deliveryProvider},
              delivery_reference=#{deliveryReference},
              reviewed_at=COALESCE(#{reviewedAt},reviewed_at),
              approved_at=#{approvedAt}, delivered_at=#{deliveredAt},
              rejected_at=#{rejectedAt}, cancelled_at=#{cancelledAt},
              invoice_number=#{invoiceNumber}, manual_sent_by=#{manualSentBy},
              version=version+1, updated_at=#{now}
            WHERE id=#{id} AND status=#{expectedFrom}
            """)
    int transition(@Param("id") long id, @Param("expectedFrom") String expectedFrom,
                   @Param("to") String to, @Param("reviewerId") Long reviewerId,
                   @Param("rejectReason") String rejectReason,
                   @Param("deliveryProvider") String deliveryProvider,
                   @Param("deliveryReference") String deliveryReference,
                   @Param("reviewedAt") OffsetDateTime reviewedAt,
                   @Param("approvedAt") OffsetDateTime approvedAt,
                   @Param("deliveredAt") OffsetDateTime deliveredAt,
                   @Param("rejectedAt") OffsetDateTime rejectedAt,
                   @Param("cancelledAt") OffsetDateTime cancelledAt,
                   @Param("invoiceNumber") String invoiceNumber,
                   @Param("manualSentBy") Long manualSentBy,
                   @Param("now") OffsetDateTime now);

    @Insert("""
            INSERT INTO business.invoice_webhook_delivery
              (application_id,status,attempts,available_at,created_at,updated_at)
            VALUES(#{applicationId},'PENDING',0,#{now},#{now},#{now})
            ON CONFLICT (application_id) DO NOTHING
            """)
    int queueWebhook(@Param("applicationId") long applicationId,
                     @Param("now") OffsetDateTime now);

    @Select("""
            SELECT application_id AS applicationId,status,attempts,
                   available_at AS availableAt,sent_at AS sentAt,
                   last_error_code AS lastErrorCode
            FROM business.invoice_webhook_delivery
            WHERE status IN ('PENDING','WAITING_CONFIG') AND available_at<=#{now}
            ORDER BY available_at,application_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    Optional<InvoiceRecords.WebhookDelivery> lockNextWebhook(@Param("now") OffsetDateTime now);

    @Select("""
            SELECT application_id AS applicationId,status,attempts,
                   available_at AS availableAt,sent_at AS sentAt,
                   last_error_code AS lastErrorCode
            FROM business.invoice_webhook_delivery WHERE application_id=#{applicationId}
            """)
    Optional<InvoiceRecords.WebhookDelivery> findWebhook(@Param("applicationId") long applicationId);

    @Update("""
            UPDATE business.invoice_webhook_delivery SET status='SENT',attempts=attempts+1,
                sent_at=#{now},last_error_code=NULL,updated_at=#{now}
            WHERE application_id=#{applicationId} AND status IN ('PENDING','WAITING_CONFIG')
            """)
    int webhookSent(@Param("applicationId") long applicationId,@Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.invoice_webhook_delivery SET status=#{status},attempts=attempts+1,
                available_at=#{availableAt},last_error_code=#{errorCode},updated_at=#{now}
            WHERE application_id=#{applicationId} AND status IN ('PENDING','WAITING_CONFIG')
            """)
    int webhookFailed(@Param("applicationId") long applicationId,
                      @Param("status") String status,
                      @Param("availableAt") OffsetDateTime availableAt,
                      @Param("errorCode") String errorCode,
                      @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.invoice_webhook_delivery SET status='PENDING',
                available_at=#{now},last_error_code=NULL,updated_at=#{now}
            WHERE application_id=#{applicationId} AND status IN ('PENDING','WAITING_CONFIG','WAITING_MANUAL')
            """)
    int retryWebhook(@Param("applicationId") long applicationId,
                     @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.invoice_webhook_delivery SET status='SKIPPED',updated_at=#{now}
            WHERE application_id=#{applicationId} AND status IN ('PENDING','WAITING_CONFIG','WAITING_MANUAL')
            """)
    int skipWebhook(@Param("applicationId") long applicationId,
                    @Param("now") OffsetDateTime now);

    @Insert("""
            INSERT INTO business.invoice_application_event(id,application_id,user_id,
              source_event_id,event_type,from_status,to_status,source,operator_id,error_code,
              details,created_at)
            VALUES(#{e.id},#{e.applicationId},#{e.userId},#{e.sourceEventId},#{e.eventType},
              #{e.fromStatus},#{e.toStatus},#{e.source},#{e.operatorId},#{e.errorCode},
              CAST(#{e.details} AS jsonb),#{e.createdAt})
            ON CONFLICT (application_id,source_event_id) DO NOTHING
            """)
    int insertEvent(@Param("e") InvoiceRecords.InvoiceEvent event);

    @Select("SELECT id,application_id AS applicationId,user_id AS userId,"
            + "source_event_id AS sourceEventId,event_type AS eventType,"
            + "from_status AS fromStatus,to_status AS toStatus,source,operator_id AS operatorId,"
            + "error_code AS errorCode,details::text AS details,created_at AS createdAt"
            + " FROM business.invoice_application_event WHERE application_id=#{applicationId}"
            + " ORDER BY created_at ASC, id ASC")
    List<InvoiceRecords.InvoiceEvent> listEvents(@Param("applicationId") long applicationId);

    /** Per-status totals for the administrator bookkeeping view. Aggregated in the database. */
    @Select("SELECT status, count(*) AS total FROM business.invoice_application GROUP BY status")
    List<StatusCount> countByStatus();

    /** Total value of every invoice that reached DELIVERED, in fen. */
    @Select("SELECT COALESCE(sum(amount_cents),0) FROM business.invoice_application"
            + " WHERE status = 'DELIVERED'")
    long sumDeliveredAmountCents();
}
