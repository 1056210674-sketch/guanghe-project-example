package com.guanghe.backend.billing;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.feedback.FeedbackMapper;
import com.guanghe.backend.billing.invoice.InvoiceMapper;
import com.guanghe.backend.billing.notification.NotificationMapper;
import com.guanghe.backend.billing.pricing.PricingMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP surface of the commercial control plane: who may call what, and what a refusal looks like.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class BillingInterfaceTests {

    private static final long USER_ID = 5001L;
    private static final long ADMIN_ID = 1L;
    private static final String ORDER_ID = "9001";
    private static final String IDEMPOTENCY_KEY = "3f0c4d5e-6a7b-4c8d-9e0f-1a2b3c4d5e6f";

    /**
     * The mock profile switches every mapper-backed module off, so the commercial module is
     * re-enabled here the same way the other interface tests re-enable theirs. The mapper itself is
     * replaced because the mock profile has no DataSource.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.billing.enabled", () -> "true");
    }

    @Autowired MockMvc mvc;

    @MockitoBean BillingMapper mapper;
    /**
     * The mappers of the other commercial surfaces. They are declared because enabling the module
     * makes every mapper-backed service in it a real bean, and the mock profile has no DataSource to
     * build one from; the tests below only exercise the routes they stub.
     */
    @MockitoBean PricingMapper pricingMapper;
    @MockitoBean NotificationMapper notificationMapper;
    @MockitoBean InvoiceMapper invoiceMapper;
    @MockitoBean FeedbackMapper feedbackMapper;
    @MockitoBean RechargeCapabilityService capability;
    @MockitoBean RechargeOrderService orders;
    @MockitoBean PaymentCallbackService callbacks;
    @MockitoBean BillingAdminService admin;
    @MockitoBean ManualPointAdjustmentService adjustments;

    // ---------------------------------------------------------------- capability

    @Test
    void rechargeCapabilityIsReportedVerbatimSoTheClientCanShowAnHonestState() throws Exception {
        when(capability.current()).thenReturn(new BillingDtos.RechargeCapabilityResponse(
                "WECHAT_NATIVE", false, false, false, "PAYMENT_PROVIDER_NOT_CONFIGURED",
                List.of("enabled", "merchantId"), 100L, 500_000L, 100, 1800L, "CNY"));

        mvc.perform(get("/api/v1/billing/recharge-capability").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.payable").value(false))
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PROVIDER_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.data.missingRequirements[0]").value("enabled"))
                .andExpect(jsonPath("$.data.pointsPerYuan").value(100))
                .andExpect(jsonPath("$.data.currency").value("CNY"));
    }

    // ---------------------------------------------------------------- order creation

    @Test
    void creatingARechargeOrderWithoutAPayableProviderIsRefusedWith503() throws Exception {
        when(orders.create(any(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED));

        mvc.perform(post("/api/v1/billing/recharge-orders")
                        .with(user())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":10000}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_NOT_CONFIGURED"));
    }

    @Test
    void creatingARechargeOrderWithoutAnIdempotencyKeyIsRefused() throws Exception {
        mvc.perform(post("/api/v1/billing/recharge-orders")
                        .with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":10000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void creatingARechargeOrderReturnsTheOrderWithStringTypedIds() throws Exception {
        when(orders.create(any(), anyString(), any())).thenReturn(order("PAYING"));

        mvc.perform(post("/api/v1/billing/recharge-orders")
                        .with(user())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(ORDER_ID))
                .andExpect(jsonPath("$.data.status").value("PAYING"))
                .andExpect(jsonPath("$.data.points").value(10000));
    }

    // ---------------------------------------------------------------- ownership

    @Test
    void anotherAccountsOrderIsReportedAsAbsentRatherThanForbidden() throws Exception {
        when(orders.get(any(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mvc.perform(get("/api/v1/billing/recharge-orders/{orderId}", ORDER_ID).with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void anAnonymousCallerCannotReachTheRechargeSurface() throws Exception {
        mvc.perform(get("/api/v1/billing/recharge-orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdministratorTokenDoesNotOpenThePayerSurface() throws Exception {
        mvc.perform(get("/api/v1/billing/recharge-orders").with(admin()))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- no fake success

    @Test
    void aProviderAmountMismatchIsReportedAsAConflictAndNeverAsSuccess() throws Exception {
        when(orders.query(any(), anyLong())).thenReturn(new RechargeOrderService.QueryResult(
                order("PAYING"), RechargeOrderService.Settlement.AMOUNT_MISMATCH));

        mvc.perform(post("/api/v1/billing/recharge-orders/{orderId}/query", ORDER_ID).with(user()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
    }

    @Test
    void anOrderTheProviderHasNotSettledIsReturnedUnchanged() throws Exception {
        when(orders.query(any(), anyLong())).thenReturn(new RechargeOrderService.QueryResult(
                order("PAYING"), RechargeOrderService.Settlement.NO_CHANGE));

        mvc.perform(post("/api/v1/billing/recharge-orders/{orderId}/query", ORDER_ID).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYING"))
                .andExpect(jsonPath("$.data.paidAt").doesNotExist());
    }

    // ---------------------------------------------------------------- administrator surface

    @Test
    void theAdministratorBillingSurfaceIsClosedToPayers() throws Exception {
        mvc.perform(get("/api/v1/admin/billing/audit-events").with(user()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/billing/recharge-orders").with(user()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/billing/point-adjustments").with(user())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":5001,\"points\":100,\"reason\":\"补偿\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reconciliationOfAnyAccountsOrderIsAvailableToAdministrators() throws Exception {
        when(admin.reconcileOrder(any(), anyLong())).thenReturn(
                new RechargeOrderService.QueryResult(order("SUCCEEDED"),
                        RechargeOrderService.Settlement.APPLIED));

        mvc.perform(post("/api/v1/admin/billing/recharge-orders/{orderId}/reconcile", ORDER_ID)
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    void requestingAnAdjustmentWithoutAnIdempotencyKeyIsRefused() throws Exception {
        mvc.perform(post("/api/v1/admin/billing/point-adjustments").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":5001,\"points\":100,\"reason\":\"补偿\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void confirmingAnAdjustmentWithAMalformedTokenIsRefusedBeforeBusinessLogic() throws Exception {
        mvc.perform(post("/api/v1/admin/billing/point-adjustments/{id}/confirm", "7001").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationToken\":\"too-short\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------------------------------------------------------------- provider callback

    @Test
    void thePaymentCallbackIsReachableWithoutABearerToken() throws Exception {
        when(callbacks.accept(any(), anyString()))
                .thenReturn(RechargeOrderService.Settlement.APPLIED);

        // No authentication post-processor: the provider signature is the authentication.
        mvc.perform(post("/api/v1/callbacks/payment/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("成功"));
    }

    @Test
    void aStaleCallbackIsAcknowledgedSoTheProviderStopsRetrying() throws Exception {
        when(callbacks.accept(any(), anyString()))
                .thenReturn(RechargeOrderService.Settlement.STALE);

        mvc.perform(post("/api/v1/callbacks/payment/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt-late\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void aCallbackWeCannotAccountForIsRefusedSoItKeepsRetrying() throws Exception {
        when(callbacks.accept(any(), anyString()))
                .thenReturn(RechargeOrderService.Settlement.AMOUNT_MISMATCH);
        mvc.perform(post("/api/v1/callbacks/payment/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt-mismatch\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("PAYMENT_AMOUNT_MISMATCH"));

        when(callbacks.accept(any(), anyString()))
                .thenReturn(RechargeOrderService.Settlement.UNMATCHED);
        mvc.perform(post("/api/v1/callbacks/payment/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt-unknown\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("RECHARGE_ORDER_NOT_FOUND"));
    }

    @Test
    void anUnauthenticatedCallbackNeverRevealsWhyItFailed() throws Exception {
        when(callbacks.accept(any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID));

        mvc.perform(post("/api/v1/callbacks/payment/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt-forged\",\"resource\":{\"ciphertext\":\"secret\"}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("PAYMENT_CALLBACK_INVALID"));
    }

    @Test
    void callbackVerificationBeingUnconfiguredIsReportedAsSuch() throws Exception {
        when(callbacks.accept(any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED));

        mvc.perform(post("/api/v1/callbacks/payment/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("PAYMENT_PROVIDER_NOT_CONFIGURED"));
    }

    // ---------------------------------------------------------------- helpers

    private static BillingDtos.RechargeOrderResponse order(String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new BillingDtos.RechargeOrderResponse(ORDER_ID, "R9001", status, "WECHAT_NATIVE",
                10_000L, 10_000, 0, null, null, null, null, now.plusMinutes(30), null, null, null,
                null, now, now);
    }

    private static RequestPostProcessor user() {
        return principal(USER_ID, AccountRole.USER);
    }

    private static RequestPostProcessor admin() {
        return principal(ADMIN_ID, AccountRole.ADMIN);
    }

    private static RequestPostProcessor principal(long id, AccountRole role) {
        AuthenticatedAccount account = new AuthenticatedAccount(id, role.name().toLowerCase(), role,
                1, AccountStatus.ACTIVE, null, null, null);
        return authentication(new UsernamePasswordAuthenticationToken(account, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
