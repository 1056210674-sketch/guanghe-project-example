package com.guanghe.backend.billing.notification;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.session.Configuration;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The notification inbox.
 *
 * <p>The mapper is replaced by an in-memory store that honours the same partial-update predicate the
 * SQL uses — {@code WHERE id=? AND user_id=? AND read_at IS NULL} — so read-once behaviour is
 * exercised rather than asserted against a call count.
 */
class NotificationServiceTests {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
    private static final long USER_ID = 5001L;
    private static final long OTHER_USER_ID = 5002L;

    private NotificationService service;
    private final Map<Long, NotificationRecords.Notification> rows = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong(700L);

    @BeforeEach
    void setUp() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        when(ids.nextId()).thenAnswer(inv -> sequence.incrementAndGet());
        stubStore(mapper);
        service = new NotificationService(mapper, ids, CLOCK);
    }

    // ---------------------------------------------------------------- publication

    @Test
    void announcingTheSameFactTwiceProducesOneNotification() {
        service.publish(USER_ID, NotificationService.CATEGORY_RECHARGE, "充值到账", "充值 100 元，已到账。",
                "RECHARGE_ORDER", 9001L, "RECHARGE_SUCCEEDED:9001");
        service.publish(USER_ID, NotificationService.CATEGORY_RECHARGE, "充值到账", "充值 100 元，已到账。",
                "RECHARGE_ORDER", 9001L, "RECHARGE_SUCCEEDED:9001");

        assertThat(rows).hasSize(1);
    }

    @Test
    void twoUsersCanBeToldTheSameFactWithoutCollidingOnTheDedupKey() {
        service.publish(USER_ID, NotificationService.CATEGORY_POINTS, "积分到账", "积分 +100。", null,
                null, "ADJUSTMENT_APPLIED:1");
        service.publish(OTHER_USER_ID, NotificationService.CATEGORY_POINTS, "积分到账", "积分 +100。",
                null, null, "ADJUSTMENT_APPLIED:1");

        assertThat(rows).hasSize(2);
    }

    @Test
    void aTargetTypeWithoutATargetIdIsRefusedRatherThanStoredAsARouteToNowhere() {
        assertThatThrownBy(() -> service.publish(USER_ID, NotificationService.CATEGORY_SYSTEM, "标题",
                "内容", "INVOICE_APPLICATION", null, "SOME_FACT:1"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void aTitleLongerThanTheColumnIsRefusedHereRatherThanByTheDatabase() {
        assertThatThrownBy(() -> service.publish(USER_ID, NotificationService.CATEGORY_SYSTEM,
                "x".repeat(121), "内容", null, null, "SOME_FACT:2"))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------- read state

    @Test
    void markingANotificationReadTwiceKeepsTheFirstReadTimestamp() {
        long id = publishOne();

        NotificationDtos.NotificationResponse first = service.markRead(user(USER_ID), id);
        NotificationDtos.NotificationResponse second = service.markRead(user(USER_ID), id);

        assertThat(first.read()).isTrue();
        assertThat(second.read()).isTrue();
        assertThat(second.readAt()).isEqualTo(first.readAt());
    }

    @Test
    void anotherAccountsNotificationIsReportedAsAbsentAndIsNotMarkedRead() {
        long id = publishOne();

        assertThatThrownBy(() -> service.markRead(user(OTHER_USER_ID), id))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
        assertThat(rows.get(id).readAt()).isNull();
    }

    @Test
    void theBadgeCountsOnlyThisAccountsUnreadNotifications() {
        publishOne();
        long mine = publishOne("RECHARGE_SUCCEEDED:9002");
        service.publish(OTHER_USER_ID, NotificationService.CATEGORY_SYSTEM, "标题", "内容", null, null,
                "SOME_FACT:3");

        assertThat(service.unreadCount(user(USER_ID)).unread()).isEqualTo(2);

        service.markRead(user(USER_ID), mine);

        assertThat(service.unreadCount(user(USER_ID)).unread()).isEqualTo(1);
        assertThat(service.unreadCount(user(OTHER_USER_ID)).unread()).isEqualTo(1);
    }

    @Test
    void markingEverythingReadReportsTheBadgeReadBackRatherThanAnAssumedZero() {
        publishOne();
        publishOne("RECHARGE_SUCCEEDED:9002");
        service.publish(OTHER_USER_ID, NotificationService.CATEGORY_SYSTEM, "标题", "内容", null, null,
                "SOME_FACT:4");

        assertThat(service.markAllRead(user(USER_ID)).unread()).isZero();
        // Somebody else's inbox is untouched.
        assertThat(service.unreadCount(user(OTHER_USER_ID)).unread()).isEqualTo(1);
    }

    @Test
    void categoryAndUnreadFiltersApplyBeforePaginationAndTheTotalMatches() {
        long read = publishOne();
        service.markRead(user(USER_ID), read);
        service.publish(USER_ID, "SYSTEM", "图片完成", "可以查看结果。", "TASK", 8001L, "TASK:8001");
        service.publish(OTHER_USER_ID, "SYSTEM", "他人任务", "可以查看结果。", "TASK", 8002L, "TASK:8002");
        service.publish(USER_ID, "RECHARGE", "充值到账", "积分已到账。", "RECHARGE_ORDER", 9002L, "RECHARGE:9002");
        service.publish(USER_ID, "SYSTEM", "视频完成", "可以查看结果。", "TASK", 8003L, "TASK:8003");

        var first = service.list(user(USER_ID), "SYSTEM", true, 1, 1);
        var second = service.list(user(USER_ID), "SYSTEM", true, 2, 1);
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.items()).extracting(NotificationDtos.NotificationResponse::targetId)
                .containsExactly("8003");
        assertThat(second.total()).isEqualTo(2);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.items()).extracting(NotificationDtos.NotificationResponse::targetId)
                .containsExactly("8001");
        assertThat(service.list(user(USER_ID), null, true, 1, 20).total()).isEqualTo(3);
        assertThat(service.list(user(USER_ID), 1, 20).total()).isEqualTo(4);
    }

    @Test
    void invalidCategoryAndPaginationAreRefusedInsteadOfLookingLikeAnEmptyInbox() {
        assertThatThrownBy(() -> service.list(user(USER_ID), "TASK", false, 1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.list(user(USER_ID), null, false, 0, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(user(USER_ID), null, false, 1, 101))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void absentDatabaseCannotSilentlyDropNotificationsOrReturnAnEmptyInbox() {
        var provider = new StaticListableBeanFactory().getBeanProvider(NotificationMapper.class);
        NotificationService unavailable = new NotificationService(provider,
                mock(SnowflakeIdGenerator.class), CLOCK);
        assertThatThrownBy(() -> unavailable.publish(USER_ID, "SYSTEM", "标题", "内容", null,
                null, "SOME_FACT:9")).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThatThrownBy(() -> unavailable.list(user(USER_ID), 1, 20))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void realMapperSqlScopesListCountAndReadBackToTheAccountWithTheSameFilters() {
        Configuration configuration = new Configuration();
        configuration.addMapper(NotificationMapper.class);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("userId", USER_ID);
        parameters.put("category", "SYSTEM");
        parameters.put("unreadOnly", true);
        parameters.put("offset", 20L);
        parameters.put("size", 20);
        for (String method : List.of("findByUser", "countByUser")) {
            String sql = configuration.getMappedStatement(NotificationMapper.class.getName() + "." + method)
                    .getBoundSql(parameters).getSql().replaceAll("\\s+", " ");
            assertThat(sql).contains("WHERE user_id=?", "AND category=?", "AND read_at IS NULL");
        }
        parameters.put("category", null);
        parameters.put("unreadOnly", false);
        String all = configuration.getMappedStatement(NotificationMapper.class.getName() + ".findByUser")
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ");
        assertThat(all).contains("WHERE user_id=?", "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .doesNotContain("AND category", "AND read_at");
        String readBack = configuration.getMappedStatement(NotificationMapper.class.getName() + ".findByIdForUser")
                .getBoundSql(Map.of("id", 700L, "userId", USER_ID)).getSql();
        assertThat(readBack).contains("WHERE id=? AND user_id=?");
    }

    // ---------------------------------------------------------------- infrastructure

    private void stubStore(NotificationMapper mapper) {
        when(mapper.insert(any())).thenAnswer(inv -> {
            NotificationRecords.Notification notification = inv.getArgument(0);
            boolean present = rows.values().stream().anyMatch(existing ->
                    existing.userId() == notification.userId()
                            && existing.dedupKey().equals(notification.dedupKey()));
            if (present) return 0;
            rows.put(notification.id(), notification);
            return 1;
        });
        when(mapper.findByIdForUser(anyLong(), anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<Long>getArgument(0)))
                        .filter(row -> row.userId() == inv.<Long>getArgument(1)));
        when(mapper.findByUser(anyLong(), any(), anyBoolean(), anyLong(), anyInt())).thenAnswer(inv -> {
            long userId = inv.getArgument(0);
            String category = inv.getArgument(1);
            boolean unreadOnly = inv.getArgument(2);
            return rows.values().stream().filter(row -> row.userId() == userId)
                    .filter(row -> category == null || category.equals(row.category()))
                    .filter(row -> !unreadOnly || row.readAt() == null)
                    .sorted(Comparator.comparing(NotificationRecords.Notification::createdAt)
                            .thenComparingLong(NotificationRecords.Notification::id).reversed())
                    .skip(inv.<Long>getArgument(3)).limit(inv.<Integer>getArgument(4)).toList();
        });
        when(mapper.countByUser(anyLong(), any(), anyBoolean())).thenAnswer(inv -> rows.values().stream()
                .filter(row -> row.userId() == inv.<Long>getArgument(0))
                .filter(row -> inv.getArgument(1) == null || inv.<String>getArgument(1).equals(row.category()))
                .filter(row -> !inv.<Boolean>getArgument(2) || row.readAt() == null).count());
        when(mapper.countUnread(anyLong())).thenAnswer(inv -> (long) rows.values().stream()
                .filter(row -> row.userId() == inv.<Long>getArgument(0))
                .filter(row -> row.readAt() == null).count());
        when(mapper.markRead(anyLong(), anyLong(), any())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            long userId = inv.getArgument(1);
            NotificationRecords.Notification current = rows.get(id);
            if (current == null || current.userId() != userId || current.readAt() != null) return 0;
            rows.put(id, withReadAt(current, inv.getArgument(2)));
            return 1;
        });
        when(mapper.markAllRead(anyLong(), any())).thenAnswer(inv -> {
            long userId = inv.getArgument(0);
            int marked = 0;
            for (Map.Entry<Long, NotificationRecords.Notification> entry
                    : List.copyOf(rows.entrySet())) {
                NotificationRecords.Notification row = entry.getValue();
                if (row.userId() == userId && row.readAt() == null) {
                    rows.put(entry.getKey(), withReadAt(row, inv.getArgument(1)));
                    marked++;
                }
            }
            return marked;
        });
    }

    private long publishOne() {
        return publishOne("RECHARGE_SUCCEEDED:9001");
    }

    private long publishOne(String dedupKey) {
        service.publish(USER_ID, NotificationService.CATEGORY_RECHARGE, "充值到账", "充值 100 元，已到账。",
                "RECHARGE_ORDER", 9001L, dedupKey);
        return rows.keySet().stream().max(Long::compareTo).orElseThrow();
    }

    private static NotificationRecords.Notification withReadAt(
            NotificationRecords.Notification row, OffsetDateTime readAt) {
        return new NotificationRecords.Notification(row.id(), row.userId(), row.category(),
                row.title(), row.body(), row.targetType(), row.targetId(), row.dedupKey(), readAt,
                row.createdAt());
    }

    private static AuthenticatedAccount user(long id) {
        return new AuthenticatedAccount(id, "user" + id, AccountRole.USER, 1, AccountStatus.ACTIVE,
                null, null, null);
    }
}
