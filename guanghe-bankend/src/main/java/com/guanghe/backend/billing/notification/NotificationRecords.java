package com.guanghe.backend.billing.notification;

import java.time.OffsetDateTime;

/** Row shapes for the in-app notification inbox. */
public final class NotificationRecords {

    private NotificationRecords() {
    }

    public record Notification(long id, long userId, String category, String title, String body,
                               String targetType, Long targetId, String dedupKey,
                               OffsetDateTime readAt, OffsetDateTime createdAt) {
    }
}
