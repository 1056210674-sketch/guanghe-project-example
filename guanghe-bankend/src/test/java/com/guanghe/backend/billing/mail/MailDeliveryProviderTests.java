package com.guanghe.backend.billing.mail;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mail boundary.
 *
 * <p>This class exists to prove the one property the whole invoice feature depends on: there is no
 * configuration of this build in which a message leaves the service or a receipt is fabricated. Both
 * the unconfigured and the fully-configured deployment must refuse, and the two refusals must be
 * distinguishable so an operator knows whether to fill in credentials or finish the integration.
 */
class MailDeliveryProviderTests {

    @Test
    void aDefaultDeploymentCannotSendAndNamesWhatIsMissing() {
        MailDeliveryProperties properties = new MailDeliveryProperties();
        SmtpMailDeliveryProvider provider = new SmtpMailDeliveryProvider(properties);

        MailDeliveryCapability capability = provider.capability();

        assertThat(capability.configured()).isFalse();
        assertThat(capability.sendable()).isFalse();
        assertThat(capability.status()).isEqualTo(MailDeliveryCapability.NOT_CONFIGURED);
        assertThat(capability.missingRequirements())
                .containsExactly("enabled", "host", "from", "username", "password");
    }

    @Test
    void everyPrerequisitePresentStillCannotSendBecauseTheTransportIsNotBuilt() {
        SmtpMailDeliveryProvider provider = new SmtpMailDeliveryProvider(fullyConfigured());

        MailDeliveryCapability capability = provider.capability();

        assertThat(capability.configured()).isTrue();
        // The single most dangerous thing this class could do is claim readiness here: invoice
        // delivery would proceed and something upstream would have to invent a receipt.
        assertThat(capability.sendable()).isFalse();
        assertThat(capability.status()).isEqualTo(MailDeliveryCapability.NOT_IMPLEMENTED);
        assertThat(capability.missingRequirements()).containsExactly("liveSmtpDelivery");
    }

    @Test
    void anUnconfiguredTransportRefusesWithItsOwnErrorCode() {
        SmtpMailDeliveryProvider provider =
                new SmtpMailDeliveryProvider(new MailDeliveryProperties());

        assertThatThrownBy(() -> provider.send(message()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.MAIL_DELIVERY_NOT_CONFIGURED);
    }

    @Test
    void aConfiguredTransportStillRefusesRatherThanInventingADelivery() {
        SmtpMailDeliveryProvider provider = new SmtpMailDeliveryProvider(fullyConfigured());

        assertThatThrownBy(() -> provider.send(message()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.MAIL_DELIVERY_NOT_IMPLEMENTED);
    }

    @Test
    void theCapabilityNamesRequirementsWithoutEverRevealingAValue() {
        MailDeliveryProperties properties = new MailDeliveryProperties();
        properties.setEnabled(true);
        properties.setHost("smtp.example.com");
        properties.setUsername("noreply@example.com");
        properties.setPassword("super-secret-value");

        MailDeliveryCapability capability = new SmtpMailDeliveryProvider(properties).capability();

        assertThat(capability.missingRequirements()).containsExactly("from");
        assertThat(capability.toString()).doesNotContain("super-secret-value");
        assertThat(String.join(",", capability.missingRequirements()))
                .doesNotContain("super-secret-value", "smtp.example.com");
    }

    private static MailDeliveryProperties fullyConfigured() {
        MailDeliveryProperties properties = new MailDeliveryProperties();
        properties.setEnabled(true);
        properties.setHost("smtp.example.com");
        properties.setPort(465);
        properties.setFrom("noreply@example.com");
        properties.setUsername("noreply@example.com");
        properties.setPassword("configured-but-unused");
        return properties;
    }

    private static MailDeliveryProvider.MailMessage message() {
        return new MailDeliveryProvider.MailMessage("payer@example.com", "光禾AI智能体框架电子发票 I9001",
                "正文", "I9001");
    }

}
