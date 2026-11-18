package com.guanghe.backend.billing.notification;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The in-app notification inbox.
 *
 * <p>Notifications are announced from inside the transaction of the event they describe, which is
 * what makes "a notification cannot exist for something that did not happen" true rather than
 * aspirational: if the business write rolls back, so does the notification.
 *
 * <p>Read state is a compare-and-set. Marking an already-read notification read again updates zero
 * rows and returns the timestamp that was recorded the first time, so a client that retries a tap
 * neither fails nor rewrites history.
 */
@Service
public class NotificationService {

    public static final String CATEGORY_RECHARGE = "RECHARGE";
    public static final String CATEGORY_POINTS = "POINTS";
    public static final String CATEGORY_INVOICE = "INVOICE";
    public static final String CATEGORY_FEEDBACK = "FEEDBACK";
    public static final String CATEGORY_SYSTEM = "SYSTEM";
    private static final Set<String> CATEGORIES = Set.of(CATEGORY_RECHARGE, CATEGORY_POINTS,
            CATEGORY_INVOICE, CATEGORY_FEEDBACK, CATEGORY_SYSTEM);

    /** Mirrors the column width; rejected here so a long title never reaches the database. */
    private static final int MAX_TITLE = 120;
    private static final int MAX_BODY = 1000;
    private static final int MAX_DEDUP_KEY = 160;

    private final NotificationMapper mapper;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;

    @Autowired
    public NotificationService(ObjectProvider<NotificationMapper> provider, SnowflakeIdGenerator ids,
                               @Qualifier("billingClock") Clock clock) {
        this(provider.getIfAvailable(), ids, clock);
    }

    public NotificationService(NotificationMapper mapper, SnowflakeIdGenerator ids,
                               @Qualifier("billingClock") Clock clock) {
        this.mapper = mapper;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Announces a fact to one user.
     *
     * <p>Runs with {@link Propagation#MANDATORY}: a notification is only ever created as part of the
     * transaction that made it true.
     *
     * @param dedupKey deterministic description of the fact, e.g. {@code RECHARGE_SUCCEEDED:9001};
     *                 a repeat of the same fact inserts nothing
     * @param targetType resource kind for the client to route to, or {@code null} for none
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(long userId, String category, String title, String body,
                        String targetType, Long targetId, String dedupKey) {
        if (category == null || !CATEGORIES.contains(category)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (title == null || title.isBlank() || title.length() > MAX_TITLE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (body == null || body.isBlank() || body.length() > MAX_BODY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (dedupKey == null || dedupKey.isBlank() || dedupKey.length() > MAX_DEDUP_KEY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if ((targetType == null) != (targetId == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (targetType != null && (!targetType.matches("^[A-Z][A-Z0-9_]{0,31}$")
                || targetId <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        NotificationRecords.Notification notification = new NotificationRecords.Notification(
                ids.nextId(), userId, category, title, body, targetType, targetId, dedupKey, null,
                now);
        // A duplicate is the expected outcome of a retried settlement, not an error.
        mapper().insert(notification);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDtos.NotificationResponse> list(AuthenticatedAccount account,
                                                                   int page, int size) {
        return list(account, null, false, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDtos.NotificationResponse> list(AuthenticatedAccount account,
            String category, boolean unreadOnly, int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        if ((category != null && !CATEGORIES.contains(category)) || page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long offset = (long) (page - 1) * size;
        NotificationMapper store = mapper();
        List<NotificationDtos.NotificationResponse> items = store
                .findByUser(userId, category, unreadOnly, offset, size).stream()
                .map(NotificationService::response).toList();
        return PageResponse.of(items, page, size, store.countByUser(userId, category, unreadOnly));
    }

    @Transactional(readOnly = true)
    public NotificationDtos.UnreadCountResponse unreadCount(AuthenticatedAccount account) {
        long userId = CatalogAccess.requireUser(account);
        return new NotificationDtos.UnreadCountResponse(mapper().countUnread(userId));
    }

    /**
     * Marks one notification read.
     *
     * <p>Idempotent by construction: the update only touches a row that is still unread, and the
     * response is read back afterwards, so a repeat returns the original {@code readAt} instead of
     * moving it. A notification belonging to somebody else is reported as absent, never as
     * forbidden, so the endpoint cannot be used to probe which ids exist.
     */
    @Transactional
    public NotificationDtos.NotificationResponse markRead(AuthenticatedAccount account, long id) {
        long userId = CatalogAccess.requireUser(account);
        NotificationMapper store = mapper();
        store.markRead(id, userId, OffsetDateTime.now(clock));
        NotificationRecords.Notification notification = store.findByIdForUser(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        return response(notification);
    }

    /**
     * Marks everything unread as read and reports the badge that results.
     *
     * <p>Returns the count read back from the database rather than asserting zero: the caller
     * learns what is true, and a notification that arrived while the update was running is still
     * counted as unread.
     */
    @Transactional
    public NotificationDtos.UnreadCountResponse markAllRead(AuthenticatedAccount account) {
        long userId = CatalogAccess.requireUser(account);
        NotificationMapper store = mapper();
        store.markAllRead(userId, OffsetDateTime.now(clock));
        return new NotificationDtos.UnreadCountResponse(store.countUnread(userId));
    }

    private NotificationMapper mapper() {
        // The mock profile deliberately has no database; a real request must fail visibly instead
        // of reporting an empty inbox or dropping a business notification.
        if (mapper == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        return mapper;
    }

    private static NotificationDtos.NotificationResponse response(
            NotificationRecords.Notification notification) {
        return new NotificationDtos.NotificationResponse(Long.toString(notification.id()),
                notification.category(), notification.title(), notification.body(),
                notification.targetType(),
                notification.targetId() == null ? null : Long.toString(notification.targetId()),
                notification.readAt() != null, notification.readAt(), notification.createdAt());
    }
}
