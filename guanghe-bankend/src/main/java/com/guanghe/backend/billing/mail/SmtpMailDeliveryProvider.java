package com.guanghe.backend.billing.mail;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.util.List;
import java.util.Map;

/**
 * The SMTP transport the product will use once mail delivery is built.
 *
 * <p>It does not send. There is no socket, no session and no client library here on purpose: a
 * half-built transport that happens to be reachable is how a test message escapes into a customer's
 * inbox, and a fabricated {@link MailReceipt} would let an invoice be marked delivered without one.
 *
 * <p>So it reports one of three honest states:
 * <ul>
 *   <li>prerequisites missing → {@code MAIL_DELIVERY_NOT_CONFIGURED}, naming what is absent;
 *   <li>prerequisites present but the live transport unbuilt → {@code MAIL_DELIVERY_NOT_IMPLEMENTED};
 *   <li>never {@code READY}, until a real implementation replaces this class.
 * </ul>
 */
public class SmtpMailDeliveryProvider implements MailDeliveryProvider {

    public static final String CODE = "SMTP";

    /** Named on the capability so an operator knows what is outstanding. */
    private static final String LIVE_TRANSPORT = "liveSmtpDelivery";

    private final MailDeliveryProperties properties;

    public SmtpMailDeliveryProvider(MailDeliveryProperties properties) {
        this.properties = properties;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public MailDeliveryCapability capability() {
        List<String> missing = properties.missingRequirements();
        if (!missing.isEmpty()) {
            return new MailDeliveryCapability(CODE, false, false,
                    MailDeliveryCapability.NOT_CONFIGURED, missing);
        }
        // Every credential is in place, but this build still has no transport to use them with.
        // Claiming READY here would be the single most dangerous lie in the service: invoice
        // delivery would proceed and there would be nothing to stop it recording a fake receipt.
        return new MailDeliveryCapability(CODE, true, false, MailDeliveryCapability.NOT_IMPLEMENTED,
                List.of(LIVE_TRANSPORT));
    }

    @Override
    public MailReceipt send(MailMessage message) {
        MailDeliveryCapability capability = capability();
        if (!capability.configured()) {
            throw new BusinessException(ErrorCode.MAIL_DELIVERY_NOT_CONFIGURED);
        }
        throw new BusinessException(ErrorCode.MAIL_DELIVERY_NOT_IMPLEMENTED,
                "邮件投递能力尚未启用，请联系管理员", Map.of("provider", CODE));
    }
}
