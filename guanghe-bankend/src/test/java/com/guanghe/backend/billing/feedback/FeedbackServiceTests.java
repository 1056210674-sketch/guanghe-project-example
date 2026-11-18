package com.guanghe.backend.billing.feedback;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.BillingMapper;
import com.guanghe.backend.billing.BillingRecords;
import com.guanghe.backend.billing.OperationalAuditService;
import com.guanghe.backend.billing.notification.NotificationMapper;
import com.guanghe.backend.billing.notification.NotificationRecords;
import com.guanghe.backend.billing.notification.NotificationService;
import com.guanghe.backend.billing.pricing.CommercialRateLimitService;
import com.guanghe.backend.billing.pricing.PricingMapper;
import com.guanghe.backend.billing.pricing.PricingRecords;
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
 * User feedback: submission, and the forward-only handling an operator can apply to it.
 */
class FeedbackServiceTests {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
    private static final long USER_ID = 5001L;
    private static final long OTHER_USER_ID = 5002L;
    private static final long OPERATOR_ID = 1L;

    private FeedbackMapper mapper;
    private FeedbackService service;
    private FeedbackAdminService admin;

    private final Map<Long, FeedbackRecords.UserFeedback> rows = new LinkedHashMap<>();
    private final List<NotificationRecords.Notification> notifications = new ArrayList<>();
    private final List<BillingRecords.OperationalAuditEvent> audits = new ArrayList<>();
    private final Map<String, PricingRecords.RateLimitRule> rules = new LinkedHashMap<>();
    /** When set, the next read returns this snapshot: the screen an operator is still looking at. */
    private FeedbackRecords.UserFeedback staleRead;
    private final AtomicLong sequence = new AtomicLong(400L);

    @BeforeEach
    void setUp() {
        mapper = mock(FeedbackMapper.class);
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        when(ids.nextId()).thenAnswer(inv -> sequence.incrementAndGet());
        stubStore();

        PricingMapper pricingMapper = mock(PricingMapper.class);
        when(pricingMapper.findRule(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(rules.get(inv.getArgument(0))));
        OperationalAuditService audit = new OperationalAuditService(stubAuditStore(), ids, CLOCK);
        CommercialRateLimitService rateLimits =
                new CommercialRateLimitService(pricingMapper, ids, CLOCK);
        service = new FeedbackService(mapper, ids, rateLimits, audit, CLOCK);
        admin = new FeedbackAdminService(mapper,
                new NotificationService(mockNotificationStore(), ids, CLOCK), audit, CLOCK);
    }

    // ---------------------------------------------------------------- submission

    @Test
    void reSubmittingTheSameFeedbackReturnsTheFirstOneRatherThanCreatingASecond() {
        String key = UUID.randomUUID().toString();
        FeedbackDtos.FeedbackResponse first = service.submit(user(USER_ID), key, request("打不开页面"));
        FeedbackDtos.FeedbackResponse second = service.submit(user(USER_ID), key, request("打不开页面"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(rows).hasSize(1);
    }

    @Test
    void reusingAKeyForDifferentContentIsRefusedRatherThanSilentlyReplayed() {
        String key = UUID.randomUUID().toString();
        service.submit(user(USER_ID), key, request("打不开页面"));

        assertThatThrownBy(() -> service.submit(user(USER_ID), key, request("另外一件事")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void oneAccountsKeyCannotReplayAnothersSubmission() {
        String key = UUID.randomUUID().toString();
        service.submit(user(USER_ID), key, request("打不开页面"));

        // The same key from a different account is a different submission, not a replay.
        FeedbackDtos.FeedbackResponse other = service.submit(user(OTHER_USER_ID), key,
                request("打不开页面"));
        assertThat(rows).hasSize(2);
        assertThat(other.id()).isNotNull();
    }

    @Test
    void aSubmissionNoLongerAcceptedIsRefusedOnceTheThrottleIsFull() {
        rules.put(CommercialRateLimitService.ACTION_FEEDBACK_SUBMIT,
                new PricingRecords.RateLimitRule(1L,
                        CommercialRateLimitService.ACTION_FEEDBACK_SUBMIT, 3600, 1, true, OPERATOR_ID,
                        1, OffsetDateTime.now(CLOCK), OffsetDateTime.now(CLOCK)));

        service.submit(user(USER_ID), UUID.randomUUID().toString(), request("第一次"));

        assertThatThrownBy(() -> service.submit(user(USER_ID), UUID.randomUUID().toString(),
                request("第二次")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void theAuditTrailRecordsTheCategoryAndLengthButNotTheCustomerFreeText() {
        service.submit(user(USER_ID), UUID.randomUUID().toString(), request("这是一段很长的用户反馈内容"));

        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).eventType()).isEqualTo("USER_FEEDBACK_SUBMITTED");
        assertThat(audits.get(0).details())
                .contains("\"category\":\"BUG\"")
                .contains("\"contentLength\":13")
                .doesNotContain("这是一段很长的用户反馈内容");
    }

    // ---------------------------------------------------------------- ownership

    @Test
    void anotherAccountsFeedbackIsReportedAsAbsentRatherThanForbidden() {
        long id = submitOne(USER_ID);

        assertThatThrownBy(() -> service.get(user(OTHER_USER_ID), id))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.FEEDBACK_NOT_FOUND);
    }

    // ---------------------------------------------------------------- handling

    @Test
    void handlingRequiresAnOperatorAndRepliesToTheAuthor() {
        long id = submitOne(USER_ID);

        FeedbackDtos.FeedbackResponse handled = admin.handle(admin(), id,
                new FeedbackDtos.HandleFeedbackRequest("ACKNOWLEDGED", "已经转给相关同事"));

        assertThat(handled.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(handled.reply()).isEqualTo("已经转给相关同事");
        assertThat(handled.handledAt()).isNotNull();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).body()).contains("已经转给相关同事");
    }

    @Test
    void handlingMovesForwardOnlySoAResolvedItemCannotGoBackToAcknowledged() {
        long id = submitOne(USER_ID);
        admin.handle(admin(), id, new FeedbackDtos.HandleFeedbackRequest("RESOLVED", "已修复"));

        assertThatThrownBy(() -> admin.handle(admin(), id,
                new FeedbackDtos.HandleFeedbackRequest("ACKNOWLEDGED", "再看一眼")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.FEEDBACK_STATE_CONFLICT);
        assertThat(rows.get(id).status()).isEqualTo("RESOLVED");
    }

    @Test
    void anItemCanBeClosedFromAnyOpenState() {
        long id = submitOne(USER_ID);

        assertThat(admin.handle(admin(), id,
                new FeedbackDtos.HandleFeedbackRequest("CLOSED", "无更多可做")).status())
                .isEqualTo("CLOSED");
    }

    @Test
    void aClosedItemCannotBeHandledAgain() {
        long id = submitOne(USER_ID);
        admin.handle(admin(), id, new FeedbackDtos.HandleFeedbackRequest("CLOSED", "结束"));

        assertThatThrownBy(() -> admin.handle(admin(), id,
                new FeedbackDtos.HandleFeedbackRequest("CLOSED", "再关一次")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.FEEDBACK_STATE_CONFLICT);
    }

    @Test
    void aStaleHandlingScreenIsRefusedRatherThanOverwritingAColleaguesReply() {
        long id = submitOne(USER_ID);
        FeedbackRecords.UserFeedback asFirstOperatorSawIt = rows.get(id);
        admin.handle(admin(), id, new FeedbackDtos.HandleFeedbackRequest("RESOLVED", "已修复"));

        // The second operator's screen still shows the item as submitted. The read succeeds, so the
        // forward-only check passes; it is the compare-and-set that refuses, which is what stops the
        // stale screen from quietly rewriting a reply the author has already been sent.
        staleRead = asFirstOperatorSawIt;
        assertThatThrownBy(() -> admin.handle(admin(), id,
                new FeedbackDtos.HandleFeedbackRequest("ACKNOWLEDGED", "我也看看")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.FEEDBACK_STATE_CONFLICT);
        assertThat(rows.get(id).status()).isEqualTo("RESOLVED");
    }

    @Test
    void theOperatorQueueIsClosedToNonAdministrators() {
        assertThatThrownBy(() -> admin.list(user(USER_ID), null, null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ---------------------------------------------------------------- infrastructure

    private void stubStore() {
        when(mapper.insert(any())).thenAnswer(inv -> {
            FeedbackRecords.UserFeedback feedback = inv.getArgument(0);
            if (rows.values().stream()
                    .anyMatch(row -> row.idempotencyKey().equals(feedback.idempotencyKey()))) {
                throw new DuplicateKeyException("uk_user_feedback_idempotency_key");
            }
            rows.put(feedback.id(), feedback);
            return 1;
        });
        when(mapper.findById(anyLong())).thenAnswer(inv -> {
            FeedbackRecords.UserFeedback stale = staleRead;
            if (stale != null) {
                staleRead = null;
                return Optional.of(stale);
            }
            return Optional.ofNullable(rows.get(inv.<Long>getArgument(0)));
        });
        when(mapper.findByIdempotencyKey(any())).thenAnswer(inv -> rows.values().stream()
                .filter(row -> row.idempotencyKey().equals(inv.getArgument(0))).findFirst());
        when(mapper.findByUser(anyLong(), anyLong(), anyInt())).thenAnswer(inv -> {
            long userId = inv.getArgument(0);
            return rows.values().stream().filter(row -> row.userId() == userId).toList();
        });
        when(mapper.countByUser(anyLong())).thenAnswer(inv -> (long) rows.values().stream()
                .filter(row -> row.userId() == inv.<Long>getArgument(0)).count());
        when(mapper.countSubmittedSince(anyLong(), any())).thenAnswer(inv -> rows.values().stream()
                .filter(row -> row.userId() == inv.<Long>getArgument(0)).count());
        when(mapper.findForAdmin(any(), any(), anyLong(), anyInt())).thenAnswer(inv ->
                rows.values().stream()
                        .filter(row -> inv.getArgument(0) == null
                                || row.userId() == inv.<Long>getArgument(0))
                        .filter(row -> inv.getArgument(1) == null
                                || row.status().equals(inv.getArgument(1)))
                        .toList());
        when(mapper.countForAdmin(any(), any())).thenAnswer(inv -> (long) rows.values().stream()
                .filter(row -> inv.getArgument(0) == null
                        || row.userId() == inv.<Long>getArgument(0))
                .filter(row -> inv.getArgument(1) == null
                        || row.status().equals(inv.getArgument(1)))
                .count());
        when(mapper.applyHandling(anyLong(), any(), any(), any(), anyLong(), any()))
                .thenAnswer(inv -> {
                    FeedbackRecords.UserFeedback current = rows.get(inv.<Long>getArgument(0));
                    if (current == null || !current.status().equals(inv.getArgument(1))) return 0;
                    rows.put(current.id(), new FeedbackRecords.UserFeedback(current.id(),
                            current.userId(), current.category(), current.content(),
                            current.contact(), inv.getArgument(2), inv.getArgument(3),
                            inv.getArgument(4), inv.getArgument(5), current.idempotencyKey(),
                            current.requestHash(), current.version() + 1, current.createdAt(),
                            inv.getArgument(5)));
                    return 1;
                });
    }

    private NotificationMapper mockNotificationStore() {
        NotificationMapper notificationMapper = mock(NotificationMapper.class);
        when(notificationMapper.insert(any())).thenAnswer(inv -> {
            NotificationRecords.Notification notification = inv.getArgument(0);
            notifications.add(notification);
            return 1;
        });
        return notificationMapper;
    }

    private BillingMapper stubAuditStore() {
        BillingMapper auditMapper = mock(BillingMapper.class);
        when(auditMapper.insertOperationalAudit(any())).thenAnswer(inv -> {
            audits.add(inv.getArgument(0));
            return 1;
        });
        return auditMapper;
    }

    private long submitOne(long userId) {
        return Long.parseLong(service.submit(user(userId), UUID.randomUUID().toString(),
                request("打不开页面")).id());
    }

    private static FeedbackDtos.CreateFeedbackRequest request(String content) {
        return new FeedbackDtos.CreateFeedbackRequest("BUG", content, null);
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
