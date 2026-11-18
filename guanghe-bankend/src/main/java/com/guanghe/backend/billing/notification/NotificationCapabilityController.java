package com.guanghe.backend.billing.notification;

import com.guanghe.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The notification capability probe.
 *
 * <p>Like the recharge probe, deliberately not gated by {@code zhixiaohe.billing.enabled}. It needs
 * no database. The in-app inbox remains enabled independently of billing; optional mail capability
 * is reported separately, and a database error must never be presented as an empty inbox.
 */
@RestController
public class NotificationCapabilityController {

    private final NotificationCapabilityService capability;

    public NotificationCapabilityController(NotificationCapabilityService capability) {
        this.capability = capability;
    }

    @GetMapping("/api/v1/billing/notification-capability")
    public ApiResponse<NotificationDtos.NotificationCapabilityResponse> capability() {
        return ApiResponse.success(capability.current());
    }
}
