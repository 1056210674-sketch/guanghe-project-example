package com.guanghe.backend.billing.invoice;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.BillingMapper;
import com.guanghe.backend.billing.OperationalAuditService;
import com.guanghe.backend.billing.BillingRecords;
import com.guanghe.backend.billing.notification.NotificationMapper;
import com.guanghe.backend.billing.notification.NotificationRecords;
import com.guanghe.backend.billing.notification.NotificationService;
import com.guanghe.backend.billing.pricing.CommercialRateLimitService;
import com.guanghe.backend.billing.pricing.PricingMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The invoice state machine.
 *
 * <p>The mapper is replaced by an in-memory store that honours the same compare-and-set predicate the
 * SQL uses, so a transition that the database would refuse is refused here too. The distinction the
 * whole feature turns on — {@code APPROVED} is not {@code DELIVERED} — is asserted directly: an
 * approval must never produce a delivered timestamp, and completion requires a distinct
 * administrator attestation after external manual email sending.
 */
class InvoiceApplicationServiceTests {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
    private static final long USER_ID = 5001L;
    private static final long OTHER_USER_ID = 5002L;
    private static final long OPERATOR_ID = 1L;

    private InvoiceMapper mapper;
    private NotificationMapper notificationMapper;
    private InvoiceApplicationService service;

    private final Map<Long, InvoiceRecords.InvoiceApplication> applications = new LinkedHashMap<>();
    private final List<InvoiceRecords.InvoiceEvent> events = new ArrayList<>();
    private final List<NotificationRecords.Notification> notifications = new ArrayList<>();
    private final List<BillingRecords.OperationalAuditEvent> audits = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong(9000L);

    @BeforeEach
    void setUp() {
        mapper = mock(InvoiceMapper.class);
        notificationMapper = mock(NotificationMapper.class);
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        when(ids.nextId()).thenAnswer(inv -> sequence.incrementAndGet());

        stubStore();

        PricingMapper pricingMapper = mock(PricingMapper.class);
        when(pricingMapper.findRule(anyString())).thenReturn(Optional.empty());
        NotificationService notifications = new NotificationService(notificationMapper, ids, CLOCK);
        OperationalAuditService audit = new OperationalAuditService(stubAuditStore(), ids, CLOCK);
        service = new InvoiceApplicationService(mapper, ids,
                new CommercialRateLimitService(pricingMapper, ids, CLOCK), notifications, audit,
                CLOCK);
    }

    // ---------------------------------------------------------------- submission

    @Test
    void reSubmittingTheSameApplicationReturnsTheFirstOneRatherThanCreatingASecond() {
        String key = UUID.randomUUID().toString();
        InvoiceDtos.InvoiceApplicationResponse first = service.submit(user(USER_ID), key, request());
        InvoiceDtos.InvoiceApplicationResponse second = service.submit(user(USER_ID), key, request());

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(applications).hasSize(1);
    }

    @Test
    void reusingAKeyForADifferentAmountIsRefusedRatherThanSilentlyReplayed() {
        String key = UUID.randomUUID().toString();
        service.submit(user(USER_ID), key, request());

        assertThatThrownBy(() -> service.submit(user(USER_ID), key,
                new InvoiceDtos.CreateInvoiceApplicationRequest("VAT_NORMAL", "PERSONAL", "张三",
                        null, 20_000L, "a@example.com", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void aPersonalInvoiceCannotBeASpecialVatInvoiceOrCarryATaxNumber() {
        assertThatThrownBy(() -> service.submit(user(USER_ID), UUID.randomUUID().toString(),
                new InvoiceDtos.CreateInvoiceApplicationRequest("VAT_SPECIAL", "PERSONAL", "张三",
                        null, 10_000L, "a@example.com", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVOICE_REQUEST_INVALID);

        assertThatThrownBy(() -> service.submit(user(USER_ID), UUID.randomUUID().toString(),
                new InvoiceDtos.CreateInvoiceApplicationRequest("VAT_NORMAL", "PERSONAL", "张三",
                        "91310000MA1K35Q123", 10_000L, "a@example.com", null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aCompanyInvoiceWithoutATaxNumberIsRefused() {
        assertThatThrownBy(() -> service.submit(user(USER_ID), UUID.randomUUID().toString(),
                new InvoiceDtos.CreateInvoiceApplicationRequest("VAT_NORMAL", "COMPANY",
                        "某某科技有限公司", null, 10_000L, "a@example.com", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVOICE_REQUEST_INVALID);
    }

    // ---------------------------------------------------------------- state machine

    @Test
    void theLegalPathIsSubmittedAcceptedApprovedAndEachStepRecordsItsOperator() {
        long id = submitOne();

        InvoiceDtos.InvoiceApplicationResponse accepted = service.accept(OPERATOR_ID, id);
        assertThat(accepted.status()).isEqualTo("UNDER_REVIEW");
        assertThat(accepted.reviewedAt()).isNotNull();

        InvoiceDtos.InvoiceApplicationResponse approved = service.approve(OPERATOR_ID, id);
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvedAt()).isNotNull();

        // The invariant the migration's CHECK constraint enforces, asserted here too: approval is
        // not delivery, and nothing but a receipt may set delivered_at.
        assertThat(approved.deliveredAt()).isNull();
        assertThat(approved.deliveryProvider()).isNull();
    }

    @Test
    void approvingAnApplicationNoOperatorAcceptedIsRefused() {
        long id = submitOne();

        assertThatThrownBy(() -> service.approve(OPERATOR_ID, id))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
        assertThat(applications.get(id).status()).isEqualTo("SUBMITTED");
    }

    @Test
    void acceptingTwiceIsRefusedByTheCompareAndSetRatherThanAppliedTwice() {
        long id = submitOne();
        service.accept(OPERATOR_ID, id);
        InvoiceRecords.InvoiceApplication before = applications.get(id);

        assertThatThrownBy(() -> service.accept(OPERATOR_ID, id))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
        assertThat(applications.get(id).version()).isEqualTo(before.version());
    }

    @Test
    void anApprovedApplicationCanNoLongerBeWithdrawnOrRejected() {
        long id = submitOne();
        service.accept(OPERATOR_ID, id);
        service.approve(OPERATOR_ID, id);

        assertThatThrownBy(() -> service.cancel(user(USER_ID), id))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
        assertThatThrownBy(() -> service.reject(OPERATOR_ID, id, "不通过"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
        assertThat(applications.get(id).status()).isEqualTo("APPROVED");
    }

    @Test
    void aRejectionCarriesItsReasonAndItsOperatorAndNotifiesTheApplicant() {
        long id = submitOne();
        InvoiceDtos.InvoiceApplicationResponse rejected = service.reject(OPERATOR_ID, id, "抬头有误");

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectReason()).isEqualTo("抬头有误");
        assertThat(rejected.reviewedAt()).isNotNull();
        assertThat(rejected.rejectedAt()).isNotNull();
        assertThat(notifications).anySatisfy(notification -> {
            assertThat(notification.title()).isEqualTo("发票申请未通过");
            assertThat(notification.body()).contains("抬头有误");
        });
    }

    @Test
    void anOperatorCanRejectStraightFromSubmittedWithoutAcceptingFirst() {
        long id = submitOne();

        assertThat(service.reject(OPERATOR_ID, id, "信息不全").status()).isEqualTo("REJECTED");
    }

    // ---------------------------------------------------------------- ownership

    @Test
    void anotherAccountsApplicationIsReportedAsAbsentRatherThanForbidden() {
        long id = submitOne();

        assertThatThrownBy(() -> service.get(user(OTHER_USER_ID), id))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVOICE_APPLICATION_NOT_FOUND);
        assertThatThrownBy(() -> service.cancel(user(OTHER_USER_ID), id))
                .isInstanceOf(BusinessException.class);
        assertThat(applications.get(id).status()).isEqualTo("SUBMITTED");
    }

    // ---------------------------------------------------------------- delivery

    @Test
    void anEmptyInvoiceNumberCannotBeRecordedAsManuallySent() {
        long id = approvedOne();
        assertThatThrownBy(() -> service.deliver(OPERATOR_ID, id, "  "))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVOICE_REQUEST_INVALID);
        assertThat(applications.get(id).status()).isEqualTo("APPROVED");
    }

    @Test
    void anApplicationNoOperatorApprovedCannotBeDelivered() {
        long id = submitOne();

        assertThatThrownBy(() -> service.deliver(OPERATOR_ID, id, "12345678"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
    }

    @Test
    void operatorConfirmationAfterManualEmailIsTheOnlyDeliveryTransition() {
        long id = approvedOne();
        InvoiceDtos.InvoiceApplicationResponse delivered = service.deliver(OPERATOR_ID, id, "12345678");

        assertThat(delivered.status()).isEqualTo("DELIVERED");
        assertThat(delivered.deliveredAt()).isNotNull();
        assertThat(delivered.invoiceNumber()).isEqualTo("12345678");
        assertThat(delivered.manualSentBy()).isEqualTo(Long.toString(OPERATOR_ID));
        assertThat(delivered.deliveryProvider()).isEqualTo("MANUAL_EMAIL");
        assertThat(events).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("APPLICATION_DELIVERED");
            assertThat(event.source()).isEqualTo("OPERATOR");
        });
        assertThat(notifications).anySatisfy(
                notification -> assertThat(notification.title()).isEqualTo("电子发票已人工发送"));
    }

    @Test
    void theApprovalNotificationSaysReviewPassedNotThatTheInvoiceWasIssued() {
        long id = submitOne();
        service.accept(OPERATOR_ID, id);
        notifications.clear();

        service.approve(OPERATOR_ID, id);

        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).title()).isEqualTo("发票申请审核通过");
        assertThat(notifications.get(0).body()).contains("已通过审核").doesNotContain("已开具");
    }

    // ---------------------------------------------------------------- infrastructure

    /** Mirrors the SQL: the compare-and-set predicate is what refuses an illegal transition. */
    private void stubStore() {
        when(mapper.insertApplication(any())).thenAnswer(inv -> {
            InvoiceRecords.InvoiceApplication application = inv.getArgument(0);
            if (applications.values().stream()
                    .anyMatch(existing -> existing.idempotencyKey()
                            .equals(application.idempotencyKey()))) {
                throw new DuplicateKeyException("uk_invoice_application_idempotency_key");
            }
            applications.put(application.id(), application);
            return 1;
        });
        when(mapper.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(applications.get(inv.<Long>getArgument(0))));
        when(mapper.findByIdempotencyKey(any())).thenAnswer(inv -> applications.values().stream()
                .filter(row -> row.idempotencyKey().equals(inv.getArgument(0))).findFirst());
        when(mapper.findByUser(anyLong(), anyLong(), anyInt())).thenAnswer(inv -> {
            long userId = inv.getArgument(0);
            return applications.values().stream()
                    .filter(row -> row.userId() == userId)
                    .sorted((left, right) -> Long.compare(right.id(), left.id())).toList();
        });
        when(mapper.countByUser(anyLong())).thenAnswer(inv -> (long) applications.values().stream()
                .filter(row -> row.userId() == inv.<Long>getArgument(0)).count());
        when(mapper.countSubmittedSince(anyLong(), any())).thenReturn(0L);
        when(mapper.queueWebhook(anyLong(), any())).thenReturn(1);
        when(mapper.skipWebhook(anyLong(), any())).thenReturn(1);
        when(mapper.transition(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                    long id = inv.getArgument(0);
                    String expectedFrom = inv.getArgument(1);
                    InvoiceRecords.InvoiceApplication current = applications.get(id);
                    if (current == null || !current.status().equals(expectedFrom)) return 0;
                    applications.put(id, new InvoiceRecords.InvoiceApplication(id,
                            current.applicationNo(), current.userId(), inv.getArgument(2),
                            current.invoiceType(), current.titleType(), current.title(),
                            current.taxNumber(), current.amountCents(), current.deliveryEmail(),
                            current.remark(), inv.getArgument(4),
                            inv.getArgument(3) == null ? current.reviewerId() : inv.getArgument(3),
                            inv.getArgument(5), inv.getArgument(6),
                            current.idempotencyKey(), current.requestHash(), current.submittedAt(),
                            inv.getArgument(7) == null ? current.reviewedAt() : inv.getArgument(7),
                            inv.getArgument(8), inv.getArgument(9), inv.getArgument(10),
                            inv.getArgument(11), current.version() + 1, current.createdAt(),
                            inv.getArgument(14), inv.getArgument(12), inv.getArgument(13),
                            current.webhookStatus(), current.webhookErrorCode()));
                    return 1;
                });
        // Mirrors ON CONFLICT (application_id, source_event_id) DO NOTHING.
        when(mapper.insertEvent(any())).thenAnswer(inv -> {
            InvoiceRecords.InvoiceEvent event = inv.getArgument(0);
            boolean present = events.stream().anyMatch(existing ->
                    existing.applicationId() == event.applicationId()
                            && existing.sourceEventId().equals(event.sourceEventId()));
            if (present) return 0;
            events.add(event);
            return 1;
        });
        when(notificationMapper.insert(any())).thenAnswer(inv -> {
            NotificationRecords.Notification notification = inv.getArgument(0);
            boolean present = notifications.stream().anyMatch(existing ->
                    existing.userId() == notification.userId()
                            && existing.dedupKey().equals(notification.dedupKey()));
            if (present) return 0;
            notifications.add(notification);
            return 1;
        });
    }

    /** Captures what the audit trail was told, honouring the same optional-key dedup the SQL does. */
    private BillingMapper stubAuditStore() {
        BillingMapper auditMapper = mock(BillingMapper.class);
        when(auditMapper.insertOperationalAudit(any())).thenAnswer(inv -> {
            BillingRecords.OperationalAuditEvent event = inv.getArgument(0);
            boolean present = event.idempotencyKey() != null && audits.stream().anyMatch(existing ->
                    event.idempotencyKey().equals(existing.idempotencyKey()));
            if (present) return 0;
            audits.add(event);
            return 1;
        });
        return auditMapper;
    }

    private long submitOne() {
        return Long.parseLong(service.submit(user(USER_ID), UUID.randomUUID().toString(), request())
                .id());
    }

    private long approvedOne() {
        long id = submitOne();
        service.accept(OPERATOR_ID, id);
        service.approve(OPERATOR_ID, id);
        return id;
    }

    private static InvoiceDtos.CreateInvoiceApplicationRequest request() {
        return new InvoiceDtos.CreateInvoiceApplicationRequest("VAT_NORMAL", "PERSONAL", "张三",
                null, 10_000L, "payer@example.com", "备注");
    }

    private static AuthenticatedAccount user(long id) {
        return new AuthenticatedAccount(id, "user" + id, AccountRole.USER, 1, AccountStatus.ACTIVE,
                null, null, null);
    }

}
