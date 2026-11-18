package com.guanghe.backend.billing;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.notification.NotificationMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The capability probes in a deployment where the commercial module is switched off.
 *
 * <p>This is the configuration the mock profile ships and the one most likely to be mis-described to
 * a user. Commercial routes remain absent, but the notification inbox is independent of billing:
 * users must still receive task results when paid billing capabilities are unavailable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class BillingCapabilityWhileSwitchedOffTests {

    private static final long USER_ID = 5001L;

    @Autowired MockMvc mvc;
    @MockitoBean NotificationMapper notificationMapper;

    @Test
    void rechargeCapabilityStillAnswersAndReportsTheProviderUnconfigured() throws Exception {
        mvc.perform(get("/api/v1/billing/recharge-capability").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.payable").value(false))
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PROVIDER_NOT_CONFIGURED"));
    }

    @Test
    void invoiceCapabilitySaysTheModuleIsOffRatherThanDisappearing() throws Exception {
        mvc.perform(get("/api/v1/billing/invoice-capability").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationsOpen").value(false))
                .andExpect(jsonPath("$.data.applicationsStatus").value("INVOICE_MODULE_DISABLED"))
                .andExpect(jsonPath("$.data.deliverable").value(false))
                .andExpect(jsonPath("$.data.deliveryStatus")
                        .value("INVOICE_MANUAL_EMAIL"));
    }

    @Test
    void notificationCapabilityKeepsInAppDeliveryAvailableWhileMailIsNotConfigured() throws Exception {
        mvc.perform(get("/api/v1/billing/notification-capability").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inApp").value(true))
                .andExpect(jsonPath("$.data.inAppStatus").value("NOTIFICATION_IN_APP_AVAILABLE"))
                .andExpect(jsonPath("$.data.mailConfigured").value(false))
                .andExpect(jsonPath("$.data.mailStatus").value("MAIL_DELIVERY_NOT_CONFIGURED"));
    }

    @Test
    void notificationsAndUnreadBadgeRemainReadableWhileBillingIsDisabled() throws Exception {
        when(notificationMapper.findByUser(USER_ID, null, false, 0, 20)).thenReturn(List.of());
        when(notificationMapper.countUnread(USER_ID)).thenReturn(4L);
        mvc.perform(get("/api/v1/billing/notifications").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/v1/billing/notifications/unread-count").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(4));
    }

    @Test
    void theGatedSurfacesAreGenuinelyAbsentSoTheProbesAreTheOnlyHonestAnswer() throws Exception {
        // Not a 500 and not an empty page: the route does not exist, which is why the client must ask
        // the capability probe instead of treating a failure as "no data".
        mvc.perform(get("/api/v1/billing/pricing/active").with(user()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/billing/invoices").with(user()))
                .andExpect(status().isNotFound());
    }

    private static RequestPostProcessor user() {
        AuthenticatedAccount account = new AuthenticatedAccount(USER_ID, "user5001", AccountRole.USER,
                1, AccountStatus.ACTIVE, null, null, null);
        return authentication(new UsernamePasswordAuthenticationToken(account, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
