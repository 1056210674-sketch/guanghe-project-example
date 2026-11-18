package com.guanghe.backend.billing.notification;

import java.time.OffsetDateTime;
import java.util.List;

/** Browser-facing payloads for the notification inbox. */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    /**
     * One inbox entry.
     *
     * <p>{@code targetType}/{@code targetId} are a resource kind and an id, never a URL. The Web
     * client decides the route, so a notification cannot direct a browser anywhere a notification
     * author chose.
     */
    public record NotificationResponse(String id, String category, String title, String body,
                                       String targetType, String targetId, boolean read,
                                       OffsetDateTime readAt, OffsetDateTime createdAt) {
    }

    /** The unread badge. Kept separate so polling it does not pull the whole inbox. */
    public record UnreadCountResponse(long unread) {
    }

    /**
     * How notifications can reach a user in this deployment.
     *
     * <p>In-app delivery is part of this service and independent of billing's switch. Mail is
     * a separate provider that this build does not implement, so the client is told so explicitly
     * instead of being left to assume an email was sent.
     */
    public record NotificationCapabilityResponse(boolean inApp, String inAppStatus,
                                                 String mailProvider, boolean mailConfigured,
                                                 String mailStatus, List<String> mailMissing) {
    }
}
