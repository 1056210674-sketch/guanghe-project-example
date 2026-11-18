package com.guanghe.backend.workflow;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.conversation.ConversationEventStreamService;
import com.guanghe.backend.article.ArticleFullService;
import com.guanghe.backend.geo.GeoArticleWorkflowService;
import com.guanghe.backend.geo.GeoTaskService;
import com.guanghe.backend.task.TaskControlService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class WorkflowPlanInterfaceIntegrationTests {
    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 9, 10, 8, 0, 0, 0, ZoneOffset.UTC);
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.paid-task.enabled", () -> "true");
        registry.add("zhixiaohe.paid-task.token-secret",
                () -> "test-only-workflow-confirmation-secret-material");
        registry.add("zhixiaohe.paid-task.image-mock-pricing-version-id", () -> "6001");
        registry.add("zhixiaohe.paid-task.image-mock-points", () -> "7");
        registry.add("zhixiaohe.paid-task.video-test-pricing-version-id", () -> "7001");
        registry.add("zhixiaohe.paid-task.video-test-points", () -> "11");
    }

    @Autowired MockMvc mvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @MockitoBean WorkflowPlanService service;
    @MockitoBean TaskControlService taskControlService;
    @MockitoBean GeoArticleWorkflowService geoArticleWorkflowService;
    @MockitoBean GeoTaskService geoTaskService;
    @MockitoBean ConversationEventStreamService events;
    @MockitoBean ArticleFullService articleFullService;
    @MockitoBean com.guanghe.backend.geo.GeoPeriodReportService periodReports;

    @BeforeEach
    void setUp() {
        WorkflowPlanDtos.Detail detail = detail("WAITING_CONFIRMATION", 1);
        WorkflowPlanDtos.Summary summary = new WorkflowPlanDtos.Summary(
                detail.id(), detail.conversationId(), detail.goalSummary(), detail.status(), detail.version(),
                detail.steps().size(), detail.totalQuotedPoints(), detail.budget().maxPointCost(),
                detail.confirmation().expiresAt(), NOW, NOW);
        when(service.list(any(), eq(null), eq(1), eq(20)))
                .thenReturn(PageResponse.of(List.of(summary), 1, 20, 1));
        when(service.get(any(), eq(9001L))).thenReturn(detail);
        when(service.abandon(any(), eq(9001L), eq(1))).thenReturn(detail("ABANDONED", 2));
        when(service.confirmationPreview(any(), eq(9001L), eq(1))).thenReturn(
                new WorkflowPlanDtos.ConfirmationAuthorization("9001", 1, 30, 50,
                        NOW.plusMinutes(10), false, "preview-only-secret-token"));
        when(service.confirm(any(), eq(9001L), eq("12345678-1234-4234-8234-123456789abc"), any()))
                .thenReturn(new WorkflowPlanDtos.ConfirmResponse("9001", 1, "CONFIRMED",
                        30, 40, NOW, false, false));
    }

    @Test
    void userReadsSafePlanAndCanAbandonWithVersionWhileIdsStayStrings() throws Exception {
        mvc.perform(get("/api/v1/workflow-plans").with(actor(AccountRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("9001"))
                .andExpect(jsonPath("$.data.items[0].conversationId").isString());
        String detail = mvc.perform(get("/api/v1/workflow-plans/9001").with(actor(AccountRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.steps[0].taskDraft.draftId").isString())
                .andExpect(jsonPath("$.data.steps[0].taskId").value("9201"))
                .andExpect(jsonPath("$.data.steps[0].taskStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.steps[0].taskResultRef").value("asset:9301"))
                .andExpect(jsonPath("$.data.confirmation.consumptionEnabled").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("tokenHash", "contextRef", "parameters", "provider", "prompt");
        mvc.perform(post("/api/v1/workflow-plans/9001/abandon").with(actor(AccountRole.USER))
                        .contentType("application/json").content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ABANDONED"));
    }

    @Test
    void securityValidationAndInternalProposalMappingRemainExplicit() throws Exception {
        mvc.perform(get("/api/v1/workflow-plans").with(actor(AccountRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/workflow-plans"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/workflow-plans?page=abc").with(actor(AccountRole.USER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/workflow-plans/9001/abandon").with(actor(AccountRole.USER))
                        .contentType("application/json").content("{\"version\":1,\"userId\":41}"))
                .andExpect(status().isUnprocessableEntity());

        Set<String> paths = mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()).collect(Collectors.toSet());
        assertThat(paths).contains("/internal/v1/runs/{runId}/workflow-plans",
                "/api/v1/workflow-plans", "/api/v1/workflow-plans/{planId}",
                "/api/v1/workflow-plans/{planId}/abandon",
                "/api/v1/workflow-plans/{planId}/confirmation-preview",
                "/api/v1/workflow-plans/{planId}/confirm");
    }

    @Test
    void previewIsTheOnlyTokenResponseAndConfirmExplicitlyKeepsExecutionDisabled() throws Exception {
        String preview = mvc.perform(post("/api/v1/workflow-plans/9001/confirmation-preview")
                        .with(actor(AccountRole.USER)).contentType("application/json")
                        .content("{\"planVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planId").value("9001"))
                .andExpect(jsonPath("$.data.confirmationToken").value("preview-only-secret-token"))
                .andExpect(jsonPath("$.data.executionEnabled").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(preview).doesNotContain("tokenHash");

        String confirmed = mvc.perform(post("/api/v1/workflow-plans/9001/confirm")
                        .with(actor(AccountRole.USER))
                        .header("Idempotency-Key", "12345678-1234-4234-8234-123456789abc")
                        .contentType("application/json")
                        .content("{\"planVersion\":1,\"authorizedMaxPoints\":40,"
                                + "\"confirmationToken\":\"preview-only-secret-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.executionEnabled").value(false))
                .andExpect(jsonPath("$.data.executionStarted").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(confirmed).doesNotContain("confirmationToken", "tokenHash", "executionToken");

        mvc.perform(post("/api/v1/workflow-plans/9001/confirm")
                        .with(actor(AccountRole.ADMIN))
                        .header("Idempotency-Key", "12345678-1234-4234-8234-123456789abc")
                        .contentType("application/json")
                        .content("{\"planVersion\":1,\"authorizedMaxPoints\":40,"
                                + "\"confirmationToken\":\"do-not-echo\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        String invalid = mvc.perform(post("/api/v1/workflow-plans/not-a-number/confirm")
                        .with(actor(AccountRole.USER))
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType("application/json")
                        .content("{\"planVersion\":1,\"authorizedMaxPoints\":40,"
                                + "\"confirmationToken\":\"malicious-token\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(invalid).doesNotContain("not-a-number", "not-a-uuid", "malicious-token");
    }

    @Test
    void disabledWorkflowExecutionStartReturnsStable503BeforeAnyControlPlaneWrite() throws Exception {
        mvc.perform(post("/api/v1/workflow-plans/9001/start")
                        .with(actor(AccountRole.USER))
                        .header("Idempotency-Key", "12345678-1234-4234-8234-123456789abc")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"planVersion\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WORKFLOW_EXECUTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private WorkflowPlanDtos.Detail detail(String status, int version) {
        WorkflowPlanDtos.Budget budget = new WorkflowPlanDtos.Budget(3, 1, 6, 3, 2, 50,
                NOW.plusDays(1));
        WorkflowPlanDtos.Step step = new WorkflowPlanDtos.Step("step-1", 1, "PREPARE",
                "task_draft.propose", "1", "PREPARE", List.of(), List.of("draft is valid"),
                List.of("context:product"), "draft:step-1",
                new WorkflowPlanDtos.DraftReference("9101", "IMAGE", "8101", 10), "SUCCEEDED",
                "9201", "SUCCEEDED", "asset:9301");
        return new WorkflowPlanDtos.Detail("9001", "5001", "6001", "7001", "组合计划", status,
                version, budget, 30, List.of(step),
                new WorkflowPlanDtos.ConfirmationPreview(1, 30, 50, NOW.plusMinutes(10), false),
                List.of(new WorkflowPlanDtos.Event(1, "PLAN_PROPOSED", null, "DRAFT", null, NOW)),
                "ABANDONED".equals(status) ? NOW : null, NOW, NOW);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor actor(AccountRole role) {
        AuthenticatedAccount principal = new AuthenticatedAccount(41, "actor", role, 1,
                AccountStatus.ACTIVE, null, null, null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
