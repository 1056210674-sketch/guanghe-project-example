package com.guanghe.backend.billing.notification;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** User-facing notification inbox, independent of paid billing availability. */
@Validated
@RestController
@RequestMapping("/api/v1/billing/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public ApiResponse<PageResponse<NotificationDtos.NotificationResponse>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false)
            @Pattern(regexp = "RECHARGE|POINTS|INVOICE|FEEDBACK|SYSTEM") String category,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(notifications.list(account, category, unreadOnly, page, size));
    }

    /** The unread badge. Cheap enough for the client to poll. */
    @GetMapping("/unread-count")
    public ApiResponse<NotificationDtos.UnreadCountResponse> unreadCount(
            @AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(notifications.unreadCount(account));
    }

    /** Idempotent: marking an already-read notification read again returns its original state. */
    @PostMapping("/{notificationId}/read")
    public ApiResponse<NotificationDtos.NotificationResponse> markRead(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long notificationId) {
        return ApiResponse.success(notifications.markRead(account, notificationId));
    }

    @PostMapping("/read-all")
    public ApiResponse<NotificationDtos.UnreadCountResponse> markAllRead(
            @AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(notifications.markAllRead(account));
    }
}
