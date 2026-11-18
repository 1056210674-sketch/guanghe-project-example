package com.guanghe.backend.billing;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.notification.NotificationService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The two-step operator credit. The point of these tests is that step one moves nothing.
 */
class ManualPointAdjustmentServiceTests {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
    private static final long OPERATOR_ID = 1L;
    private static final long TARGET_USER_ID = 5001L;
    private static final long ADJUSTMENT_ID = 7001L;

    private BillingMapper mapper;
    private BillingProperties properties;
    private ManualPointAdjustmentService service;
    private NotificationService notifications;

    private final Map<Long, BillingRecords.ManualAdjustment> adjustments = new LinkedHashMap<>();
    private final List<Credit> credits = new ArrayList<>();
    private final List<BillingRecords.OperationalAuditEvent> audits = new ArrayList<>();

    private record Credit(long userId, int delta, String entryType, String businessType,
                          long businessId, String key, Long operatorId) {
    }

    @BeforeEach
    void setUp() {
        mapper = mock(BillingMapper.class);
        properties = new BillingProperties();
        notifications = mock(NotificationService.class);
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        when(ids.nextId()).thenReturn(ADJUSTMENT_ID);

        when(mapper.insertAdjustment(any())).thenAnswer(inv -> {
            BillingRecords.ManualAdjustment adjustment = inv.getArgument(0);
            adjustments.put(adjustment.id(), adjustment);
            return 1;
        });
        when(mapper.findAdjustmentByIdempotencyKey(any())).thenAnswer(inv ->
                adjustments.values().stream()
                        .filter(a -> a.idempotencyKey().equals(inv.getArgument(0))).findFirst());
        when(mapper.lockAdjustmentById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(adjustments.get(inv.<Long>getArgument(0))));
        when(mapper.findAdjustmentById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(adjustments.get(inv.<Long>getArgument(0))));
        when(mapper.markAdjustmentApplied(anyLong(), anyLong(), any())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            BillingRecords.ManualAdjustment current = adjustments.get(id);
            if (current == null || !ManualPointAdjustmentService.STATUS_PENDING.equals(
                    current.status())) {
                return 0;
            }
            adjustments.put(id, new BillingRecords.ManualAdjustment(current.id(), current.userId(),
                    current.points(), current.direction(), current.reason(), current.operatorId(),
                    current.idempotencyKey(), current.requestHash(), current.confirmationTokenHash(),
                    current.confirmationExpiresAt(), ManualPointAdjustmentService.STATUS_APPLIED,
                    inv.getArgument(1), inv.getArgument(2), current.createdAt(), inv.getArgument(2)));
            return 1;
        });
        when(mapper.lockPointAccount(anyLong()))
                .thenAnswer(inv -> Optional.of(new BillingRecords.PointAccount(inv.getArgument(0),
                        100, 0, 1, now(), now())));
        when(mapper.creditAvailable(anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertCreditLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), any(),
                any(), anyLong(), any(), any(), any(), any())).thenAnswer(inv -> {
            credits.add(new Credit(inv.getArgument(1), inv.getArgument(2), inv.getArgument(5),
                    inv.getArgument(6), inv.getArgument(7), inv.getArgument(8), inv.getArgument(9)));
            return 1;
        });
        when(mapper.insertOperationalAudit(any())).thenAnswer(inv -> {
            audits.add(inv.getArgument(0));
            return 1;
        });
        service = new ManualPointAdjustmentService(mapper, ids, properties,
                new PointCreditService(mapper, ids, CLOCK),
                new OperationalAuditService(mapper, ids, CLOCK), notifications, CLOCK);
    }

    // ---------------------------------------------------------------- step one moves nothing

    @Test
    void requestingAnAdjustmentCreditsNothingAndLeavesTheRecordPending() {
        BillingDtos.PointAdjustmentResponse response =
                service.request(admin(), UUID.randomUUID().toString(), request(5_000));

        assertThat(credits).isEmpty();
        assertThat(response.status())
                .isEqualTo(ManualPointAdjustmentService.STATUS_PENDING);
        assertThat(response.ledgerId()).isNull();
        assertThat(response.appliedAt()).isNull();
        verifyNoInteractions(notifications);
        assertThat(adjustments.get(ADJUSTMENT_ID).status())
                .isEqualTo(ManualPointAdjustmentService.STATUS_PENDING);
        assertThat(audits).anyMatch(audit -> OperationalAuditService.POINT_ADJUSTMENT_REQUESTED
                .equals(audit.eventType())
                && OperationalAuditService.OUTCOME_PENDING.equals(audit.outcome())
                && OperationalAuditService.ACTOR_ADMIN.equals(audit.actorType()));
    }

    @Test
    void theConfirmationTokenIsReturnedOnceAndIsNeverStoredInPlaintext() {
        BillingDtos.PointAdjustmentResponse response =
                service.request(admin(), UUID.randomUUID().toString(), request(5_000));

        assertThat(response.confirmationToken()).hasSize(43).matches("^[A-Za-z0-9_-]{43}$");
        BillingRecords.ManualAdjustment stored = adjustments.get(ADJUSTMENT_ID);
        assertThat(stored.confirmationTokenHash()).isEqualTo(sha256(response.confirmationToken()));
        assertThat(stored.confirmationTokenHash()).isNotEqualTo(response.confirmationToken());
        // Neither the token nor anything derived from it may appear in the audit trail.
        assertThat(audits).allSatisfy(audit ->
                assertThat(audit.details()).doesNotContain(response.confirmationToken()));
    }

    // ---------------------------------------------------------------- step two applies exactly once

    @Test
    void confirmingWithTheTokenCreditsExactlyOnceThroughTheSharedLedgerPath() {
        BillingDtos.PointAdjustmentResponse requested =
                service.request(admin(), UUID.randomUUID().toString(), request(5_000));

        BillingDtos.PointAdjustmentResponse applied =
                service.confirm(admin(), ADJUSTMENT_ID, requested.confirmationToken());

        assertThat(credits).hasSize(1);
        Credit credit = credits.get(0);
        assertThat(credit.userId()).isEqualTo(TARGET_USER_ID);
        assertThat(credit.delta()).isEqualTo(5_000);
        assertThat(credit.entryType()).isEqualTo(PointCreditService.ENTRY_ADMIN_ADJUSTMENT);
        assertThat(credit.businessType()).isEqualTo(PointCreditService.BUSINESS_ADMIN_ADJUSTMENT);
        assertThat(credit.businessId()).isEqualTo(ADJUSTMENT_ID);
        assertThat(credit.key()).isEqualTo("adjustment:" + ADJUSTMENT_ID);
        // The operator is recorded as who acted, not as a business-type discriminator.
        assertThat(credit.operatorId()).isEqualTo(OPERATOR_ID);
        assertThat(applied.status()).isEqualTo(ManualPointAdjustmentService.STATUS_APPLIED);
        assertThat(applied.ledgerId()).isNotNull();
        verify(notifications).publish(TARGET_USER_ID, NotificationService.CATEGORY_POINTS, "积分已到账",
                "管理员已为您补充 5000 积分，可在积分明细中查看本次入账。",
                "POINT_LEDGER", Long.parseLong(applied.ledgerId()), "POINT_ADJUSTMENT_APPLIED:" + ADJUSTMENT_ID);
        verifyNoMoreInteractions(notifications);
        assertThat(audits).anyMatch(audit -> OperationalAuditService.POINT_ADJUSTMENT_APPLIED
                .equals(audit.eventType()));
    }

    @Test
    void confirmingTheSameAdjustmentTwiceIsRefusedAndStillCreditsOnce() {
        BillingDtos.PointAdjustmentResponse requested =
                service.request(admin(), UUID.randomUUID().toString(), request(5_000));
        service.confirm(admin(), ADJUSTMENT_ID, requested.confirmationToken());

        assertThatThrownBy(() -> service.confirm(admin(), ADJUSTMENT_ID,
                requested.confirmationToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.ADJUSTMENT_ALREADY_APPLIED);
        assertThat(credits).hasSize(1);
        verify(notifications).publish(TARGET_USER_ID, NotificationService.CATEGORY_POINTS, "积分已到账",
                "管理员已为您补充 5000 积分，可在积分明细中查看本次入账。",
                "POINT_LEDGER", ADJUSTMENT_ID, "POINT_ADJUSTMENT_APPLIED:" + ADJUSTMENT_ID);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void aResubmittedRequestIsIdempotentAndDoesNotHandOutASecondToken() {
        String key = UUID.randomUUID().toString();
        BillingDtos.PointAdjustmentResponse first = service.request(admin(), key, request(5_000));
        BillingDtos.PointAdjustmentResponse second = service.request(admin(), key, request(5_000));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(adjustments).hasSize(1);
        // Unrecoverable by design: the operator must use the token they were already given.
        assertThat(second.confirmationToken()).isNull();
        assertThat(credits).isEmpty();
        verifyNoInteractions(notifications);
    }

    @Test
    void reusingAKeyForADifferentTargetIsRefusedRatherThanSilentlyReplayed() {
        String key = UUID.randomUUID().toString();
        service.request(admin(), key, request(5_000));

        assertThatThrownBy(() -> service.request(admin(), key, request(9_000)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(adjustments).hasSize(1);
    }

    // ---------------------------------------------------------------- what refuses

    @Test
    void aWrongTokenIsRejectedRecordedAndLeavesTheRecordPending() {
        service.request(admin(), UUID.randomUUID().toString(), request(5_000));

        assertThatThrownBy(() -> service.confirm(admin(), ADJUSTMENT_ID, "A".repeat(43)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.CONFIRMATION_TOKEN_INVALID);

        assertThat(credits).isEmpty();
        assertThat(adjustments.get(ADJUSTMENT_ID).status())
                .isEqualTo(ManualPointAdjustmentService.STATUS_PENDING);
        assertThat(audits).anyMatch(audit -> OperationalAuditService.POINT_ADJUSTMENT_REJECTED
                .equals(audit.eventType()));
        verifyNoInteractions(notifications);
    }

    @Test
    void anExpiredTokenCannotBeConfirmed() {
        BillingDtos.PointAdjustmentResponse requested =
                service.request(admin(), UUID.randomUUID().toString(), request(5_000));
        BillingRecords.ManualAdjustment pending = adjustments.get(ADJUSTMENT_ID);
        adjustments.put(ADJUSTMENT_ID, new BillingRecords.ManualAdjustment(pending.id(),
                pending.userId(), pending.points(), pending.direction(), pending.reason(),
                pending.operatorId(), pending.idempotencyKey(), pending.requestHash(),
                pending.confirmationTokenHash(), now().minusSeconds(1), pending.status(), null, null,
                pending.createdAt(), pending.updatedAt()));

        assertThatThrownBy(() -> service.confirm(admin(), ADJUSTMENT_ID,
                requested.confirmationToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.CONFIRMATION_TOKEN_INVALID);
        assertThat(credits).isEmpty();
        verifyNoInteractions(notifications);
    }

    @Test
    void failedApplicationDoesNotAnnounceAnUnpersistedCredit() {
        BillingDtos.PointAdjustmentResponse requested =
                service.request(admin(), UUID.randomUUID().toString(), request(5_000));
        when(mapper.markAdjustmentApplied(anyLong(), anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(admin(), ADJUSTMENT_ID, requested.confirmationToken()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_ALREADY_APPLIED));
        verifyNoInteractions(notifications);
    }

    @Test
    void aPayerCannotRequestOrConfirmAnAdjustment() {
        assertThatThrownBy(() -> service.request(user(), UUID.randomUUID().toString(),
                request(5_000)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.confirm(user(), ADJUSTMENT_ID, "A".repeat(43)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(adjustments).isEmpty();
        assertThat(credits).isEmpty();
    }

    @Test
    void anAmountAboveTheOperatorBoundIsRefused() {
        properties.setManualAdjustmentMaxPoints(1_000);

        assertThatThrownBy(() -> service.request(admin(), UUID.randomUUID().toString(),
                request(1_001)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(adjustments).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static BillingDtos.CreatePointAdjustmentRequest request(int points) {
        return new BillingDtos.CreatePointAdjustmentRequest(TARGET_USER_ID, points, "补偿测试");
    }

    private static AuthenticatedAccount admin() {
        return new AuthenticatedAccount(OPERATOR_ID, "admin", AccountRole.ADMIN, 1,
                AccountStatus.ACTIVE, null, null, null);
    }

    private static AuthenticatedAccount user() {
        return new AuthenticatedAccount(TARGET_USER_ID, "user", AccountRole.USER, 1,
                AccountStatus.ACTIVE, null, null, null);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(CLOCK);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
