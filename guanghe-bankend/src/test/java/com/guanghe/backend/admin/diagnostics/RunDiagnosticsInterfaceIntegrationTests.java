package com.guanghe.backend.admin.diagnostics;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class RunDiagnosticsInterfaceIntegrationTests {
    private static final String RUN_ID = "0f1e2d3c-4b5a-4678-9abc-def012345678";
    private static final String CONVERSATION_ID = "1900012345678904001";
    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 9, 10, 8, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private MockMvc mvc;
    @MockitoBean private RunDiagnosticsService service;

    @BeforeEach
    void setUp() {
        TaskDiagnosticsDtos.Decision decision = new TaskDiagnosticsDtos.Decision(
                TaskDiagnosticsDtos.FailureCategory.UNKNOWN, null,
                TaskDiagnosticsDtos.Retryability.UNKNOWN, false, false);
        RunDiagnosticsDtos.Summary summary = new RunDiagnosticsDtos.Summary(
                RUN_ID, CONVERSATION_ID, "COMPLETED", null, decision,
                "0123456789abcdef0123456789abcdef", NOW, NOW, NOW);
        when(service.list(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(PageResponse.of(List.of(summary), 1, 20, 1));
        when(service.get(RUN_ID)).thenReturn(detail(decision));
    }

    @Test
    void adminReadsListAndDetailWithCanonicalStringIds() throws Exception {
        mvc.perform(get("/api/v1/admin/diagnostics/runs").with(actor(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].runId").value(RUN_ID))
                .andExpect(jsonPath("$.data.items[0].conversationId").value(CONVERSATION_ID));
        mvc.perform(get("/api/v1/admin/diagnostics/runs/{runId}", RUN_ID).with(actor(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(RUN_ID))
                .andExpect(jsonPath("$.data.timeline[0].eventType").value("message.completed"));
    }

    @Test
    void userAndAnonymousAreRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/diagnostics/runs").with(actor(AccountRole.USER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/diagnostics/runs"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void missingAndMalformedRunIdentifiersAreSafe() throws Exception {
        String missing = "11111111-1111-4111-8111-111111111111";
        when(service.get(missing)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(get("/api/v1/admin/diagnostics/runs/{runId}", missing).with(actor(AccountRole.ADMIN)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isString());
        String malicious = "not-a-run-secret";
        when(service.get(malicious)).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED));
        String body = mvc.perform(get("/api/v1/admin/diagnostics/runs/{runId}", malicious)
                        .with(actor(AccountRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(malicious);
        mvc.perform(get("/api/v1/admin/diagnostics/runs?page=abc").with(actor(AccountRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private RunDiagnosticsDtos.Detail detail(TaskDiagnosticsDtos.Decision decision) {
        return new RunDiagnosticsDtos.Detail(RUN_ID, CONVERSATION_ID, "COMPLETED", 3, null,
                decision, "0123456789abcdef0123456789abcdef", null, NOW, NOW, NOW, NOW,
                new RunDiagnosticsDtos.Usage(1, 1, 1, 0, 0, 100, 90, 10, 20, "DEEPSEEK", "deepseek-v4-flash"),
                new RunDiagnosticsDtos.ContextBudget(128000, 8000, 2000, 1000, 117000, 100, false, "REDIS", true),
                null, List.of(new RunDiagnosticsDtos.TimelineEntry("message.completed", 3, null, NOW)), List.of());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor actor(AccountRole role) {
        AuthenticatedAccount principal = new AuthenticatedAccount(7, "actor", role, 1,
                AccountStatus.ACTIVE, null, null, null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
