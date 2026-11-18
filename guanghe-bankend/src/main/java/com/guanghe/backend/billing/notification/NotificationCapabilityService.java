package com.guanghe.backend.billing.notification;

import com.guanghe.backend.billing.mail.MailDeliveryCapability;
import com.guanghe.backend.billing.mail.MailDeliveryProvider;
import org.springframework.stereotype.Service;

/**
 * What notification delivery can currently do.
 *
 * <p>Depends on configuration only — no mapper, no transaction — so it answers in every deployment,
 * including one where the commercial module is switched off. That is the point: the Web client must
 * be able to distinguish the durable in-app inbox from optional mail delivery. Task notifications
 * remain available when payment and invoice features are disabled.
 */
@Service
public class NotificationCapabilityService {

    public static final String IN_APP_AVAILABLE = "NOTIFICATION_IN_APP_AVAILABLE";
    private final MailDeliveryProvider mail;

    public NotificationCapabilityService(MailDeliveryProvider mail) {
        this.mail = mail;
    }

    public NotificationDtos.NotificationCapabilityResponse current() {
        MailDeliveryCapability capability = mail.capability();
        return new NotificationDtos.NotificationCapabilityResponse(true,
                IN_APP_AVAILABLE, capability.provider(),
                capability.configured(), capability.status(), capability.missingRequirements());
    }
}
