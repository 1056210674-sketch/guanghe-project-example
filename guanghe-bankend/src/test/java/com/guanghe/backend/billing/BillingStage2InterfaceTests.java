package com.guanghe.backend.billing;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.bookkeeping.BookkeepingDtos;
import com.guanghe.backend.billing.bookkeeping.BookkeepingRecords;
import com.guanghe.backend.billing.bookkeeping.BookkeepingService;
import com.guanghe.backend.billing.bookkeeping.StatusCount;
import com.guanghe.backend.billing.feedback.FeedbackAdminService;
import com.guanghe.backend.billing.feedback.FeedbackDtos;
import com.guanghe.backend.billing.feedback.FeedbackMapper;
import com.guanghe.backend.billing.feedback.FeedbackService;
import com.guanghe.backend.billing.invoice.InvoiceAdminService;
import com.guanghe.backend.billing.invoice.InvoiceApplicationService;
import com.guanghe.backend.billing.invoice.InvoiceDtos;
import com.guanghe.backend.billing.invoice.InvoiceMapper;
import com.guanghe.backend.billing.notification.NotificationDtos;
import com.guanghe.backend.billing.notification.NotificationMapper;
import com.guanghe.backend.billing.notification.NotificationService;
import com.guanghe.backend.billing.pricing.PricingAdminService;
import com.guanghe.backend.billing.pricing.PricingDtos;
import com.guanghe.backend.billing.pricing.PricingMapper;
import com.guanghe.backend.catalog.PageResponse;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP surface of the Stage 2 commercial features: invoices, notifications, feedback, pricing and
 * bookkeeping.
 *
 * <p>Two things are asserted here that no unit test can: the capability probes answer from real
 * services (not mocks) and are therefore honest about this build, and every administrator route is
 * closed to a payer token by the security configuration itself rather than by a check inside a
 * service that a future refactor could drop.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class BillingStage2InterfaceTests {

    private static final long USER_ID = 5001L;
    private static final long ADMIN_ID = 1L;
    private static final String APPLICATION_ID = "9101";
    private static final String IDEMPOTENCY_KEY = "3f0c4d5e-6a7b-4c8d-9e0f-1a2b3c4d5e6f";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.billing.enabled", () -> "true");
    }

    @Autowired MockMvc mvc;

    /**
     * The mappers are replaced because the mock profile has no DataSource; replacing them is also
     * what lets the real capability and pricing services be built, so the probes below exercise the
     * production wiring rather than a stub.
     */
    @MockitoBean BillingMapper billingMapper;
    @MockitoBean PricingMapper pricingMapper;
    @MockitoBean NotificationMapper notificationMapper;
    @MockitoBean InvoiceMapper invoiceMapper;
    @MockitoBean FeedbackMapper feedbackMapper;

    @MockitoBean InvoiceApplicationService invoiceApplications;
    @MockitoBean InvoiceAdminService invoiceAdmin;
    @MockitoBean NotificationService notifications;
    @MockitoBean FeedbackService feedback;
    @MockitoBean FeedbackAdminService feedbackAdmin;
    @MockitoBean PricingAdminService pricingAdmin;
    @MockitoBean BookkeepingService bookkeeping;

    // ---------------------------------------------------------------- capability

    @Test
    void theInvoiceCapabilityDescribesManualEmailAndUnconfiguredWebhook() throws Exception {
        // Real service: this is what the deployment actually reports, not what a stub was told to.
        mvc.perform(get("/api/v1/billing/invoice-capability").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationsOpen").value(true))
                .andExpect(jsonPath("$.data.applicationsStatus").value("INVOICE_APPLICATIONS_OPEN"))
                .andExpect(jsonPath("$.data.deliverable").value(true))
                .andExpect(jsonPath("$.data.deliveryStatus")
                        .value("INVOICE_MANUAL_EMAIL"))
                .andExpect(jsonPath("$.data.deliveryConfigured").value(true))
                .andExpect(jsonPath("$.data.webhookConfigured").value(false))
                .andExpect(jsonPath("$.data.webhookStatus").value("INVOICE_WEBHOOK_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.data.currency").value("CNY"));
    }

    @Test
    void theNotificationCapabilitySeparatesInAppDeliveryFromMail() throws Exception {
        mvc.perform(get("/api/v1/billing/notification-capability").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inApp").value(true))
                .andExpect(jsonPath("$.data.inAppStatus").value("NOTIFICATION_IN_APP_AVAILABLE"))
                .andExpect(jsonPath("$.data.mailConfigured").value(false))
                .andExpect(jsonPath("$.data.mailStatus").value("MAIL_DELIVERY_NOT_CONFIGURED"));
    }

    @Test
    void theActiveRateCardIsServedWithProductionRateFalseWhileNothingIsPublished() throws Exception {
        // Real PricingService over a mapper that finds no active version: the placeholder, labelled.
        mvc.perform(get("/api/v1/billing/pricing/active").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productionRate").value(false))
                .andExpect(jsonPath("$.data.versionId").doesNotExist())
                .andExpect(jsonPath("$.data.versionNo").doesNotExist())
                .andExpect(jsonPath("$.data.pointsPerYuan").value(100))
                .andExpect(jsonPath("$.data.tiers").isEmpty())
                .andExpect(jsonPath("$.data.currency").value("CNY"));
    }

    @Test
    void anAnonymousCallerCannotReachAnyStageTwoPayerSurface() throws Exception {
        mvc.perform(get("/api/v1/billing/invoices")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/billing/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/billing/feedback")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/billing/pricing/active")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- invoices

    @Test
    void anInvoiceApplicationIsReturnedWithStringTypedIds() throws Exception {
        when(invoiceApplications.submit(any(), anyString(), any())).thenReturn(application("SUBMITTED"));

        mvc.perform(post("/api/v1/billing/invoices")
                        .with(user())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceType":"VAT_NORMAL","titleType":"PERSONAL","title":"张三",
                                 "amountCents":10000,"deliveryEmail":"payer@example.com"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(APPLICATION_ID))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.amountCents").value(10_000))
                .andExpect(jsonPath("$.data.deliveredAt").doesNotExist());
    }

    @Test
    void anInvoiceApplicationWithoutAnIdempotencyKeyIsRefused() throws Exception {
        mvc.perform(post("/api/v1/billing/invoices")
                        .with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceType":"VAT_NORMAL","titleType":"PERSONAL","title":"张三",
                                 "amountCents":10000,"deliveryEmail":"payer@example.com"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anImpossibleTaxNumberIsRefusedBeforeAnyBusinessLogicRuns() throws Exception {
        mvc.perform(post("/api/v1/billing/invoices")
                        .with(user())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceType":"VAT_SPECIAL","titleType":"COMPANY","title":"某某公司",
                                 "taxNumber":"not-a-tax-number","amountCents":10000,
                                 "deliveryEmail":"payer@example.com"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anotherAccountsInvoiceApplicationIsReportedAsAbsentRatherThanForbidden() throws Exception {
        when(invoiceApplications.get(any(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.INVOICE_APPLICATION_NOT_FOUND));

        mvc.perform(get("/api/v1/billing/invoices/{id}", APPLICATION_ID).with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVOICE_APPLICATION_NOT_FOUND"));
    }

    @Test
    void anApplicationAnOperatorHasDecidedOnCanNoLongerBeWithdrawn() throws Exception {
        when(invoiceApplications.cancel(any(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.INVOICE_STATE_CONFLICT));

        mvc.perform(post("/api/v1/billing/invoices/{id}/cancel", APPLICATION_ID).with(user()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVOICE_STATE_CONFLICT"));
    }

    @Test
    void manualDeliveryRequiresAnInvoiceNumber() throws Exception {
        mvc.perform(post("/api/v1/admin/billing/invoices/{id}/deliver", APPLICATION_ID)
                        .with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceNumber\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectingAnApplicationRequiresAReason() throws Exception {
        mvc.perform(post("/api/v1/admin/billing/invoices/{id}/reject", APPLICATION_ID).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------------------------------------------------------------- notifications

    @Test
    void theInboxIsPagedAndCarriesTheUnreadBadgeSeparately() throws Exception {
        when(notifications.list(any(), isNull(), eq(false), anyInt(), anyInt())).thenReturn(PageResponse.of(
                List.of(new NotificationDtos.NotificationResponse("7001", "RECHARGE", "充值到账",
                        "充值 100 元，已到账。", "RECHARGE_ORDER", "9001", false, null,
                        OffsetDateTime.now(ZoneOffset.UTC))), 1, 20, 1));
        when(notifications.unreadCount(any()))
                .thenReturn(new NotificationDtos.UnreadCountResponse(3));

        mvc.perform(get("/api/v1/billing/notifications").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("7001"))
                .andExpect(jsonPath("$.data.items[0].read").value(false))
                .andExpect(jsonPath("$.data.items[0].targetId").value("9001"))
                .andExpect(jsonPath("$.data.total").value(1));

        mvc.perform(get("/api/v1/billing/notifications/unread-count").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(3))
                // The badge route deliberately carries no inbox, so polling it stays cheap.
                .andExpect(jsonPath("$.data.items").doesNotExist());
    }

    @Test
    void inboxCategoryAndUnreadFiltersArePassedToTheService() throws Exception {
        when(notifications.list(any(), eq("SYSTEM"), eq(true), eq(2), eq(10)))
                .thenReturn(PageResponse.of(List.of(), 2, 10, 0));
        mvc.perform(get("/api/v1/billing/notifications").with(user())
                        .param("category", "SYSTEM").param("unreadOnly", "true")
                        .param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10));
    }

    @Test
    void inboxRejectsUnknownCategoriesAndInvalidUnreadFilters() throws Exception {
        mvc.perform(get("/api/v1/billing/notifications").with(user()).param("category", "TASK"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/billing/notifications").with(user()).param("unreadOnly", "unknown"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anotherAccountsNotificationIsReportedAsAbsent() throws Exception {
        when(notifications.markRead(any(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));

        mvc.perform(post("/api/v1/billing/notifications/{id}/read", "7001").with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void markingEverythingReadReportsWhatItActuallyRead() throws Exception {
        when(notifications.markAllRead(any()))
                .thenReturn(new NotificationDtos.UnreadCountResponse(0));

        mvc.perform(post("/api/v1/billing/notifications/read-all").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(0));
    }

    // ---------------------------------------------------------------- feedback

    @Test
    void feedbackIsAcceptedAndReturnedAsSubmitted() throws Exception {
        when(feedback.submit(any(), anyString(), any())).thenReturn(new FeedbackDtos.FeedbackResponse(
                "8001", "BUG", "打不开页面", null, "SUBMITTED", null, null,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC)));

        mvc.perform(post("/api/v1/billing/feedback")
                        .with(user())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"BUG\",\"content\":\"打不开页面\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("8001"))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.reply").doesNotExist());
    }

    @Test
    void feedbackOfAnUnknownCategoryIsRefused() throws Exception {
        mvc.perform(post("/api/v1/billing/feedback")
                        .with(user())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"COMPLAINT\",\"content\":\"内容\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anotherAccountsFeedbackIsReportedAsAbsent() throws Exception {
        when(feedback.get(any(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.FEEDBACK_NOT_FOUND));

        mvc.perform(get("/api/v1/billing/feedback/{id}", "8001").with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEEDBACK_NOT_FOUND"));
    }

    @Test
    void anOperatorReplyIsRequiredSoAStatusChangeCanNeverArriveWithoutAReason() throws Exception {
        mvc.perform(post("/api/v1/admin/billing/feedback/{id}/handle", "8001").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"reply\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------------------------------------------------------------- administrator boundaries

    @Test
    void everyStageTwoAdministratorRouteIsClosedToPayers() throws Exception {
        mvc.perform(get("/api/v1/admin/billing/invoices").with(user()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/billing/feedback").with(user()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/billing/pricing-versions").with(user()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/billing/rate-limits").with(user()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/billing/point-ledger").with(user()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/billing/bookkeeping/summary").with(user()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorTokenDoesNotOpenThePayerFacingStageTwoSurfaces() throws Exception {
        mvc.perform(get("/api/v1/billing/invoices").with(admin()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/billing/notifications").with(admin()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/billing/pricing/active").with(admin()))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- pricing administration

    @Test
    void publishingIsASeparateStepFromAuthoringAndReturnsThePublishedCard() throws Exception {
        when(pricingAdmin.publish(any(), anyLong())).thenReturn(pricingVersion("ACTIVE"));

        mvc.perform(post("/api/v1/admin/billing/pricing-versions/{id}/publish", "42").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("42"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.versionNo").value(7))
                .andExpect(jsonPath("$.data.publishedAt").isNotEmpty());
    }

    @Test
    void aRateLimitRuleNeedsItsExpectedVersionSoTwoOperatorsCannotOverwriteEachOther() throws Exception {
        mvc.perform(put("/api/v1/admin/billing/rate-limits/{action}", "RECHARGE_ORDER_CREATE")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windowSeconds\":3600,\"maxAttempts\":20,\"enabled\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------------------------------------------------------------- bookkeeping

    @Test
    void theLedgerSearchIsPagedAndKeepsIdsAsStrings() throws Exception {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
        when(bookkeeping.ledger(any(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(1),
                eq(20))).thenReturn(PageResponse.of(List.of(
                        new BookkeepingDtos.PointLedgerEntryResponse("9007199254740993", "5001",
                                10_000, 0, 10_100, 0, "RECHARGE", "RECHARGE", "9007199254740994",
                                null, "充值 100 元", at)), 1, 20, 1));

        mvc.perform(get("/api/v1/admin/billing/point-ledger").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].businessId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.items[0].operatorId").doesNotExist())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void theBookkeepingSummaryCarriesWhetherTheNumbersCameFromAPublishedRate() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(bookkeeping.summary(any())).thenReturn(new BookkeepingDtos.BookkeepingSummaryResponse(
                now,
                new BookkeepingDtos.RechargeSummaryResponse(
                        List.of(new StatusCount("SUCCEEDED", 2)), 2, 30_000, 30_000, 1_000),
                new BookkeepingDtos.InvoiceSummaryResponse(List.of(), 0),
                new BookkeepingDtos.FeedbackSummaryResponse(List.of()),
                new BookkeepingDtos.LedgerSummaryResponse(4, 30_000, 0),
                new BookkeepingDtos.RatePlanResponse(false, null, null, 100),
                120_000, 3_000, List.of("RECHARGE"), List.of("RECHARGE")));

        mvc.perform(get("/api/v1/admin/billing/bookkeeping/summary").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ratePlan.productionRate").value(false))
                .andExpect(jsonPath("$.data.ratePlan.versionId").doesNotExist())
                .andExpect(jsonPath("$.data.recharge.succeededAmountCents").value(30_000))
                .andExpect(jsonPath("$.data.platformAvailablePoints").value(120_000))
                .andExpect(jsonPath("$.data.platformFrozenPoints").value(3_000))
                .andExpect(jsonPath("$.data.ledgerEntryTypes[0]").value("RECHARGE"))
                .andExpect(jsonPath("$.data.generatedAt").isNotEmpty());
    }

    @Test
    void theLedgerSummaryIsASeparateRouteSoTheTotalNeverHasToBeInferredFromAPage() throws Exception {
        when(bookkeeping.ledgerSummary(any(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new BookkeepingDtos.LedgerSummaryResponse(4, 30_000, -500));

        mvc.perform(get("/api/v1/admin/billing/point-ledger/summary").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries").value(4))
                .andExpect(jsonPath("$.data.availableDelta").value(30_000))
                .andExpect(jsonPath("$.data.frozenDelta").value(-500));
    }

    // ---------------------------------------------------------------- helpers

    private static InvoiceDtos.InvoiceApplicationResponse application(String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new InvoiceDtos.InvoiceApplicationResponse(APPLICATION_ID, "I9101", status,
                "VAT_NORMAL", "PERSONAL", "张三", null, 10_000L, "payer@example.com", null, null,
                null, null, null, "PENDING", null,
                now, null, null, null, null, null, now, now);
    }

    private static PricingDtos.PricingVersionResponse pricingVersion(String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new PricingDtos.PricingVersionResponse("42", 7, status, 250, 1_000L, 500_000L,
                "正式比例", "1", "1", now, null, List.of(), now, now);
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
