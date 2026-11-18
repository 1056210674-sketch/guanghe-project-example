package com.guanghe.backend.billing.invoice;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.OperationalAuditService;
import com.guanghe.backend.billing.notification.NotificationService;
import com.guanghe.backend.billing.pricing.CommercialRateLimitService;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice application lifecycle.
 *
 * <p>The state machine exists to keep two things apart that are easy to conflate: an operator
 * approving an application, and the customer actually holding an invoice.
 *
 * <pre>
 *   SUBMITTED ──accept──▶ UNDER_REVIEW ──approve──▶ APPROVED ──deliver──▶ DELIVERED
 *       │                      │                       (needs a real receipt)
 *       ├──────reject──────────┴──▶ REJECTED
 *       └──────cancel──────────┴──▶ CANCELLED
 * </pre>
 *
 * <p>{@code APPROVED} records that internal review passed. It does not mean the invoice was issued,
 * {@code DELIVERED} is an operator's explicit record that an issued invoice was manually emailed.
 * The independent Feishu webhook only routes the application to the operator group.
 *
 * <p>Every transition is a compare-and-set on the previous status. A late operator action on an
 * application that has already moved updates zero rows and is reported as a conflict rather than
 * applied to whatever state the row happens to be in.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InvoiceApplicationService {

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_UNDER_REVIEW = "UNDER_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    static final String TYPE_VAT_NORMAL = "VAT_NORMAL";
    static final String TYPE_VAT_SPECIAL = "VAT_SPECIAL";
    static final String TITLE_PERSONAL = "PERSONAL";
    static final String TITLE_COMPANY = "COMPANY";

    /** Withdrawable while no decision has been taken. */
    private static final Set<String> CANCELLABLE = Set.of(STATUS_SUBMITTED, STATUS_UNDER_REVIEW);
    /** Refusable while no decision has been taken. */
    private static final Set<String> REJECTABLE = Set.of(STATUS_SUBMITTED, STATUS_UNDER_REVIEW);

    private static final String SOURCE_BROWSER = "BROWSER";
    private static final String SOURCE_OPERATOR = "OPERATOR";

    private static final String TARGET_INVOICE = "INVOICE_APPLICATION";

    private final InvoiceMapper mapper;
    private final SnowflakeIdGenerator ids;
    private final CommercialRateLimitService rateLimits;
    private final NotificationService notifications;
    private final OperationalAuditService audit;
    private final Clock clock;

    public InvoiceApplicationService(InvoiceMapper mapper, SnowflakeIdGenerator ids,
                                     CommercialRateLimitService rateLimits,
                                     NotificationService notifications, OperationalAuditService audit,
                                     @Qualifier("billingClock") Clock clock) {
        this.mapper = mapper;
        this.ids = ids;
        this.rateLimits = rateLimits;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- submission

    @Transactional
    public InvoiceDtos.InvoiceApplicationResponse submit(AuthenticatedAccount account,
                                                         String idempotencyKey,
                                                         InvoiceDtos.CreateInvoiceApplicationRequest request) {
        long userId = CatalogAccess.requireUser(account);
        validateTitle(request);

        // Namespaced by user so one account's key can never collide with, or replay, another's.
        String key = userId + ":" + uuid(idempotencyKey);
        String requestHash = sha256(String.join("\n", request.invoiceType(), request.titleType(),
                request.title(), nullToEmpty(request.taxNumber()),
                Long.toString(request.amountCents()), request.deliveryEmail(),
                nullToEmpty(request.remark())));

        rateLimits.enforce(CommercialRateLimitService.ACTION_INVOICE_APPLICATION_SUBMIT,
                since -> mapper.countSubmittedSince(userId, since));

        InvoiceRecords.InvoiceApplication existing = mapper.findByIdempotencyKey(key).orElse(null);
        if (existing != null) return replay(existing, requestHash);

        OffsetDateTime now = OffsetDateTime.now(clock);
        long applicationId = ids.nextId();
        InvoiceRecords.InvoiceApplication application = new InvoiceRecords.InvoiceApplication(
                applicationId, "I" + applicationId, userId, STATUS_SUBMITTED, request.invoiceType(),
                request.titleType(), request.title().trim(), blankToNull(request.taxNumber()),
                request.amountCents(), request.deliveryEmail().trim(), blankToNull(request.remark()),
                null, null, null, null, key, requestHash, now, null, null, null, null, null, 1, now,
                now, null, null, "PENDING", null);
        try {
            if (mapper.insertApplication(application) != 1) {
                throw new BusinessException(ErrorCode.INVOICE_STATE_CONFLICT);
            }
        } catch (DuplicateKeyException exception) {
            InvoiceRecords.InvoiceApplication raced =
                    mapper.findByIdempotencyKey(key).orElse(null);
            if (raced == null) throw exception;
            return replay(raced, requestHash);
        }
        if (mapper.queueWebhook(applicationId, now) != 1) {
            throw new BusinessException(ErrorCode.INVOICE_STATE_CONFLICT);
        }
        recordEvent(application, null, STATUS_SUBMITTED, "APPLICATION_SUBMITTED", SOURCE_BROWSER,
                null, null, summary(application), null);
        audit.actorUser(OperationalAuditService.ACTOR_USER, userId, TARGET_INVOICE, applicationId,
                "INVOICE_APPLICATION_SUBMITTED", OperationalAuditService.OUTCOME_SUCCESS,
                summary(application));
        notifications.publish(userId, NotificationService.CATEGORY_INVOICE, "发票申请已提交",
                "申请单号 " + application.applicationNo() + " 已提交，等待审核。",
                TARGET_INVOICE, applicationId, "INVOICE_SUBMITTED:" + applicationId);
        return response(application);
    }

    // ---------------------------------------------------------------- payer reads

    @Transactional(readOnly = true)
    public PageResponse<InvoiceDtos.InvoiceApplicationResponse> list(AuthenticatedAccount account,
                                                                    int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        long offset = (long) (page - 1) * size;
        List<InvoiceDtos.InvoiceApplicationResponse> items = mapper.findByUser(userId, offset, size)
                .stream().map(InvoiceApplicationService::response).toList();
        return PageResponse.of(items, page, size, mapper.countByUser(userId));
    }

    @Transactional(readOnly = true)
    public InvoiceDtos.InvoiceApplicationResponse get(AuthenticatedAccount account, long id) {
        long userId = CatalogAccess.requireUser(account);
        return response(ownedApplication(userId, id));
    }

    /** Withdraws an application that no operator has decided on yet. */
    @Transactional
    public InvoiceDtos.InvoiceApplicationResponse cancel(AuthenticatedAccount account, long id) {
        long userId = CatalogAccess.requireUser(account);
        InvoiceRecords.InvoiceApplication application = ownedApplication(userId, id);
        if (!CANCELLABLE.contains(application.status())) {
            throw new BusinessException(ErrorCode.INVOICE_STATE_CONFLICT);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        transition(application, new Transition(application.status(), STATUS_CANCELLED)
                .cancelled(now), now);
        mapper.skipWebhook(id, now);
        InvoiceRecords.InvoiceApplication cancelled = require(id);
        recordEvent(cancelled, application.status(), STATUS_CANCELLED, "APPLICATION_CANCELLED",
                SOURCE_BROWSER, userId, null, "{\"fromStatus\":\"" + application.status() + "\"}",
                browserEventId("cancel:" + id));
        audit.actorUser(OperationalAuditService.ACTOR_USER, userId, TARGET_INVOICE, id,
                "INVOICE_APPLICATION_CANCELLED", OperationalAuditService.OUTCOME_SUCCESS,
                "{\"fromStatus\":\"" + application.status() + "\"}");
        return response(cancelled);
    }

    // ---------------------------------------------------------------- operator transitions

    @Transactional
    public InvoiceDtos.InvoiceApplicationResponse accept(long operatorId, long id) {
        InvoiceRecords.InvoiceApplication application = require(id);
        if (!STATUS_SUBMITTED.equals(application.status())) {
            throw new BusinessException(ErrorCode.INVOICE_STATE_CONFLICT);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        transition(application, new Transition(STATUS_SUBMITTED, STATUS_UNDER_REVIEW)
                .reviewer(operatorId).reviewed(now), now);
        InvoiceRecords.InvoiceApplication accepted = require(id);
        recordEvent(accepted, STATUS_SUBMITTED, STATUS_UNDER_REVIEW, "APPLICATION_ACCEPTED",
                SOURCE_OPERATOR, operatorId, null, "{}", null);
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_INVOICE, id,
                "INVOICE_APPLICATION_ACCEPTED", OperationalAuditService.OUTCOME_SUCCESS, "{}");
        notifications.publish(accepted.userId(), NotificationService.CATEGORY_INVOICE, "发票申请已受理",
                "申请单号 " + accepted.applicationNo() + " 已受理，正在审核。", TARGET_INVOICE, id,
                "INVOICE_ACCEPTED:" + id);
        return response(accepted);
    }

    /**
     * Records that internal review passed.
     *
     * <p>Deliberately does not notify the customer that an invoice has been issued. The wording of
     * the notification is "审核通过，尚未开具", because that is what this transition means.
     */
    @Transactional
    public InvoiceDtos.InvoiceApplicationResponse approve(long operatorId, long id) {
        InvoiceRecords.InvoiceApplication application = require(id);
        if (!STATUS_UNDER_REVIEW.equals(application.status())) {
            throw new BusinessException(ErrorCode.INVOICE_STATE_CONFLICT);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        transition(application, new Transition(STATUS_UNDER_REVIEW, STATUS_APPROVED)
                .reviewer(operatorId).reviewed(now).approved(now), now);
        InvoiceRecords.InvoiceApplication approved = require(id);
        recordEvent(approved, STATUS_UNDER_REVIEW, STATUS_APPROVED, "APPLICATION_APPROVED",
                SOURCE_OPERATOR, operatorId, null, summary(approved), null);
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_INVOICE, id,
                "INVOICE_APPLICATION_APPROVED", OperationalAuditService.OUTCOME_SUCCESS,
                summary(approved));
        notifications.publish(approved.userId(), NotificationService.CATEGORY_INVOICE, "发票申请审核通过",
                "申请单号 " + approved.applicationNo() + " 已通过审核，发票开具后会发送至 "
                        + approved.deliveryEmail() + "。", TARGET_INVOICE, id,
                "INVOICE_APPROVED:" + id);
        return response(approved);
    }

    @Transactional
    public InvoiceDtos.InvoiceApplicationResponse reject(long operatorId, long id, String reason) {
        InvoiceRecords.InvoiceApplication application = require(id);
        if (!REJECTABLE.contains(application.status())) {
            throw new BusinessException(ErrorCode.INVOICE_STATE_CONFLICT);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        transition(application, new Transition(application.status(), STATUS_REJECTED)
                .reviewer(operatorId).reviewed(now).rejectReason(reason.trim()).rejected(now), now);
        mapper.skipWebhook(id, now);
        InvoiceRecords.InvoiceApplication rejected = require(id);
        recordEvent(rejected, application.status(), STATUS_REJECTED, "APPLICATION_REJECTED",
                SOURCE_OPERATOR, operatorId, null,
                "{\"fromStatus\":\"" + application.status() + "\"}", null);
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_INVOICE, id,
                "INVOICE_APPLICATION_REJECTED", OperationalAuditService.OUTCOME_SUCCESS,
                "{\"fromStatus\":\"" + application.status() + "\"}");
        notifications.publish(rejected.userId(), NotificationService.CATEGORY_INVOICE, "发票申请未通过",
                "申请单号 " + rejected.applicationNo() + " 未通过审核：" + rejected.rejectReason(),
                TARGET_INVOICE, id, "INVOICE_REJECTED:" + id);
        return response(rejected);
    }

    /** Records an administrator's attestation after they issued and emailed the invoice externally. */
    @Transactional
    public InvoiceDtos.InvoiceApplicationResponse deliver(long operatorId, long id,
                                                           String invoiceNumber) {
        InvoiceRecords.InvoiceApplication application = require(id);
        if (!STATUS_APPROVED.equals(application.status())) {
            throw new BusinessException(ErrorCode.INVOICE_STATE_CONFLICT);
        }
        String number = blankToNull(invoiceNumber);
        if (number == null || number.length() > 64) {
            throw new BusinessException(ErrorCode.INVOICE_REQUEST_INVALID);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        String confirmationReference = "manual:" + UUID.randomUUID();
        transition(application, new Transition(STATUS_APPROVED, STATUS_DELIVERED)
                .reviewed(application.reviewedAt()).approved(application.approvedAt())
                .manualSentBy(operatorId).invoiceNumber(number)
                .receipt("MANUAL_EMAIL", confirmationReference, now), now);
        mapper.skipWebhook(id, now);
        InvoiceRecords.InvoiceApplication delivered = require(id);
        recordEvent(delivered, STATUS_APPROVED, STATUS_DELIVERED, "APPLICATION_DELIVERED",
                SOURCE_OPERATOR, operatorId, null, "{\"method\":\"MANUAL_EMAIL\"}", null);
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_INVOICE, id,
                "INVOICE_APPLICATION_DELIVERED", OperationalAuditService.OUTCOME_SUCCESS,
                "{\"method\":\"MANUAL_EMAIL\",\"confirmationReference\":\""
                        + confirmationReference + "\"}");
        notifications.publish(delivered.userId(), NotificationService.CATEGORY_INVOICE,
                "电子发票已人工发送",
                "管理员已确认申请单号 " + delivered.applicationNo() + " 的电子发票发送至 "
                        + delivered.deliveryEmail() + "，请查收邮箱。", TARGET_INVOICE, id,
                "INVOICE_DELIVERED:" + id);
        return response(delivered);
    }

    // ---------------------------------------------------------------- shared helpers

    /** Operator reads. Role checking happens in the caller, which owns the operator surface. */
    @Transactional(readOnly = true)
    public PageResponse<InvoiceDtos.InvoiceApplicationResponse> listForOperator(Long userId,
                                                                              String status, int page,
                                                                              int size) {
        String normalized = blankToNull(status);
        if (normalized != null && !InvoiceAdminService.STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long offset = (long) (page - 1) * size;
        List<InvoiceDtos.InvoiceApplicationResponse> items = mapper
                .findForAdmin(userId, normalized, offset, size).stream()
                .map(InvoiceApplicationService::response).toList();
        return PageResponse.of(items, page, size, mapper.countForAdmin(userId, normalized));
    }

    @Transactional(readOnly = true)
    public InvoiceDtos.InvoiceApplicationResponse getForOperator(long id) {
        return response(require(id));
    }

    InvoiceRecords.InvoiceApplication require(long id) {
        return mapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVOICE_APPLICATION_NOT_FOUND));
    }

    private InvoiceRecords.InvoiceApplication ownedApplication(long userId, long id) {
        // Somebody else's application is reported as absent, not forbidden: the difference would
        // tell a caller which application ids exist.
        return mapper.findById(id).filter(row -> row.userId() == userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVOICE_APPLICATION_NOT_FOUND));
    }

    /**
     * One compare-and-set transition. Every nullable column is written explicitly, so a transition
     * can never leave a value behind from an earlier state.
     *
     * <p>Takes a {@link Transition} rather than a twelve-argument parameter list: the columns are
     * eight mostly-nullable timestamps and ids, and positional arguments at that width are a
     * misalignment waiting to happen.
     */
    private void transition(InvoiceRecords.InvoiceApplication application, Transition change,
                            OffsetDateTime now) {
        if (mapper.transition(application.id(), change.from, change.to, change.reviewerId,
                change.rejectReason, change.deliveryProvider, change.deliveryReference,
                change.reviewedAt, change.approvedAt, change.deliveredAt, change.rejectedAt,
                change.cancelledAt, change.invoiceNumber, change.manualSentBy, now) != 1) {
            throw new BusinessException(ErrorCode.INVOICE_STATE_CONFLICT);
        }
    }

    /** A transition being assembled by name, so no field can be set by position. */
    private static final class Transition {
        private final String from;
        private final String to;
        private Long reviewerId;
        private String rejectReason;
        private String deliveryProvider;
        private String deliveryReference;
        private OffsetDateTime reviewedAt;
        private OffsetDateTime approvedAt;
        private OffsetDateTime deliveredAt;
        private OffsetDateTime rejectedAt;
        private OffsetDateTime cancelledAt;
        private String invoiceNumber;
        private Long manualSentBy;

        private Transition(String from, String to) {
            this.from = from;
            this.to = to;
        }

        private Transition reviewer(long operatorId) {
            this.reviewerId = operatorId;
            return this;
        }

        private Transition reviewed(OffsetDateTime at) {
            this.reviewedAt = at;
            return this;
        }

        private Transition approved(OffsetDateTime at) {
            this.approvedAt = at;
            return this;
        }

        private Transition rejectReason(String value) {
            this.rejectReason = value;
            return this;
        }

        private Transition rejected(OffsetDateTime at) {
            this.rejectedAt = at;
            return this;
        }

        private Transition cancelled(OffsetDateTime at) {
            this.cancelledAt = at;
            return this;
        }

        private Transition receipt(String provider, String reference, OffsetDateTime at) {
            this.deliveryProvider = provider;
            this.deliveryReference = reference;
            this.deliveredAt = at;
            return this;
        }

        private Transition invoiceNumber(String value) {
            this.invoiceNumber = value;
            return this;
        }

        private Transition manualSentBy(long value) {
            this.manualSentBy = value;
            return this;
        }
    }

    /**
     * Appends one transition to the application's history.
     *
     * <p>When the caller supplies no event id, one is derived from the application and the
     * transition rather than from the clock. Two attempts at the same transition are therefore the
     * same fact, and the table's unique index absorbs the repeat instead of logging it twice.
     */
    private void recordEvent(InvoiceRecords.InvoiceApplication application, String fromStatus,
                             String toStatus, String eventType, String source, Long operatorId,
                             String errorCode, String details, UUID sourceEventId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID eventId = sourceEventId != null ? sourceEventId
                : UUID.nameUUIDFromBytes((application.id() + ":" + eventType + ":" + toStatus)
                        .getBytes(StandardCharsets.UTF_8));
        mapper.insertEvent(new InvoiceRecords.InvoiceEvent(ids.nextId(), application.id(),
                application.userId(), eventId, eventType, fromStatus, toStatus, source, operatorId,
                errorCode, details, now));
    }

    private InvoiceDtos.InvoiceApplicationResponse replay(
            InvoiceRecords.InvoiceApplication existing, String requestHash) {
        if (!MessageDigest.isEqual(existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return response(existing);
    }

    /**
     * Cross-field rules the bean validation annotations cannot express: a personal invoice cannot be
     * a special VAT invoice and carries no tax number, and a company invoice must carry one.
     */
    private static void validateTitle(InvoiceDtos.CreateInvoiceApplicationRequest request) {
        boolean personal = TITLE_PERSONAL.equals(request.titleType());
        String taxNumber = blankToNull(request.taxNumber());
        if (personal) {
            if (!TYPE_VAT_NORMAL.equals(request.invoiceType()) || taxNumber != null) {
                throw new BusinessException(ErrorCode.INVOICE_REQUEST_INVALID);
            }
            return;
        }
        if (!TITLE_COMPANY.equals(request.titleType()) || taxNumber == null) {
            throw new BusinessException(ErrorCode.INVOICE_REQUEST_INVALID);
        }
    }

    private static String summary(InvoiceRecords.InvoiceApplication application) {
        return "{\"applicationNo\":\"" + application.applicationNo() + "\",\"amountCents\":"
                + application.amountCents() + ",\"invoiceType\":\"" + application.invoiceType()
                + "\",\"titleType\":\"" + application.titleType() + "\"}";
    }

    private static UUID browserEventId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    static InvoiceDtos.InvoiceApplicationResponse response(
            InvoiceRecords.InvoiceApplication application) {
        return new InvoiceDtos.InvoiceApplicationResponse(Long.toString(application.id()),
                application.applicationNo(), application.status(), application.invoiceType(),
                application.titleType(), application.title(), application.taxNumber(),
                application.amountCents(), application.deliveryEmail(), application.remark(),
                application.rejectReason(), application.deliveryProvider(),
                application.invoiceNumber(), application.manualSentBy() == null ? null
                        : Long.toString(application.manualSentBy()),
                application.webhookStatus(), application.webhookErrorCode(),
                application.submittedAt(), application.reviewedAt(), application.approvedAt(),
                application.deliveredAt(), application.rejectedAt(), application.cancelledAt(),
                application.createdAt(), application.updatedAt());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
