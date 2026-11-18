package com.guanghe.backend.billing;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The capability probe must answer even when recharge itself is switched off.
 *
 * <p>This runs with the module switch at its default in the {@code mock} profile, i.e. with the
 * order endpoints deliberately disabled — the same shape as a deployment that has not enabled the
 * commercial module. The product requirement is that the payer page can still say "暂不可充值" and
 * name what is missing; a route that vanishes cannot do that, and a disabled-looking button would
 * imply the feature exists but is merely unavailable to this user.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class RechargeCapabilityAvailabilityTests {

    @Autowired MockMvc mvc;

    /** Only the mapper is replaced; the capability path is exercised for real. */
    @MockitoBean BillingMapper mapper;

    @Test
    void capabilityIsReadableWhileRechargeItselfIsOff() throws Exception {
        mvc.perform(get("/api/v1/billing/recharge-capability").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                // The honest state: recharge exists as a capability and is not available.
                .andExpect(jsonPath("$.data.payable").value(false))
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.provider").value("WECHAT_NATIVE"))
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PROVIDER_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.data.missingRequirements").isArray())
                .andExpect(jsonPath("$.data.currency").value("CNY"));
    }

    @Test
    void theOrderSurfaceIsAbsentWhileRechargeIsOff() throws Exception {
        // No order may be created, and no order route may pretend to exist.
        mvc.perform(get("/api/v1/billing/recharge-orders").with(user()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theCapabilityProbeIsStillBehindAuthentication() throws Exception {
        mvc.perform(get("/api/v1/billing/recharge-capability"))
                .andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor user() {
        AuthenticatedAccount account = new AuthenticatedAccount(5001L, "payer", AccountRole.USER, 1,
                AccountStatus.ACTIVE, null, null, null);
        return authentication(new UsernamePasswordAuthenticationToken(account, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
