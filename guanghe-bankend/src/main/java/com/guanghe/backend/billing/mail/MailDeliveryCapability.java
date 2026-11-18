package com.guanghe.backend.billing.mail;

import java.util.List;

/**
 * What a mail transport can currently do.
 *
 * <p>Top-level rather than nested in {@link MailDeliveryProvider} because it is a vocabulary three
 * packages share — the transport reports it, the invoice and notification capability services
 * translate it, and it reaches the browser. It is the answer to "can this deployment send mail",
 * not a provider-internal detail.
 *
 * @param configured whether every prerequisite is present
 * @param sendable   whether the transport may be asked to deliver; false in this build, always
 * @param status     one of {@link #READY}, {@link #NOT_CONFIGURED}, {@link #NOT_IMPLEMENTED}
 */
public record MailDeliveryCapability(String provider, boolean configured, boolean sendable,
                                     String status, List<String> missingRequirements) {

    public static final String READY = "MAIL_DELIVERY_READY";
    public static final String NOT_CONFIGURED = "MAIL_DELIVERY_NOT_CONFIGURED";
    public static final String NOT_IMPLEMENTED = "MAIL_DELIVERY_NOT_IMPLEMENTED";

    public MailDeliveryCapability {
        missingRequirements = List.copyOf(missingRequirements);
    }
}
