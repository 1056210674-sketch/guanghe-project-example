package com.guanghe.backend.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.billing.payment.PaymentCallbackSignatureVerifier;
import com.guanghe.backend.billing.payment.PaymentProvider;
import com.guanghe.backend.billing.payment.WeChatNativePaymentProvider;
import com.guanghe.backend.billing.payment.WeChatPayProperties;
import com.guanghe.backend.billing.payment.WeChatPaySignatureVerifier;
import com.guanghe.backend.billing.mail.MailDeliveryProperties;
import com.guanghe.backend.billing.mail.MailDeliveryProvider;
import com.guanghe.backend.billing.mail.SmtpMailDeliveryProvider;
import com.guanghe.backend.billing.invoice.InvoiceWebhookProperties;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the commercial control plane.
 *
 * <p>Deliberately has no {@code @ConditionalOnProperty} switch. The payment provider and the policy
 * properties must exist in every deployment so the Browser API can always report an honest
 * capability state: a provider that reports itself unconfigured is what lets the Web client
 * distinguish "payment is not available" from "payment is broken". Switching recharge off is done
 * on the endpoints that write orders, not here.
 *
 * <p>Scheduling is the exception and lives in {@link BillingSchedulingConfiguration}: switching
 * scheduling on for a deployment that has the commercial module off would start every <em>other</em>
 * module's {@code @Scheduled} beans in that context too, which is not this module's decision to
 * make. Each module enables scheduling for itself, as {@code PaidTaskConfiguration} does.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({BillingProperties.class, WeChatPayProperties.class,
        MailDeliveryProperties.class, InvoiceWebhookProperties.class})
public class BillingConfiguration {

    @Bean
    Clock billingClock() {
        return Clock.systemUTC();
    }

    /**
     * The mail transport boundary.
     *
     * <p>Present in every deployment, like the payment provider, so the browser can always be told
     * that invoice delivery and mail notification are unavailable — and why — instead of finding a
     * missing route or, worse, being shown a success that never happened.
     */
    @Bean
    MailDeliveryProvider mailDeliveryProvider(MailDeliveryProperties properties) {
        return new SmtpMailDeliveryProvider(properties);
    }

    @Bean
    PaymentCallbackSignatureVerifier paymentCallbackSignatureVerifier(WeChatPayProperties properties) {
        return new WeChatPaySignatureVerifier(properties);
    }

    @Bean
    PaymentProvider paymentProvider(WeChatPayProperties properties,
                                    PaymentCallbackSignatureVerifier verifier, ObjectMapper json,
                                    @Qualifier("billingClock") Clock clock) {
        return new WeChatNativePaymentProvider(properties, verifier, json, clock);
    }
}
