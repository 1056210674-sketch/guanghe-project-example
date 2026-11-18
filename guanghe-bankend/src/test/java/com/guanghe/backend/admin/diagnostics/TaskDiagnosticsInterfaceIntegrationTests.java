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
class TaskDiagnosticsInterfaceIntegrationTests {
    private static final String TASK_ID = "1900012345678904001";
    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 9, 9, 8, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private MockMvc mvc;
    @MockitoBean private TaskDiagnosticsService service;

    @BeforeEach
    void setUp() {
        TaskDiagnosticsDtos.Decision decision = new TaskDiagnosticsDtos.Decision(
                TaskDiagnosticsDtos.FailureCategory.UNKNOWN, null,
                TaskDiagnosticsDtos.Retryability.UNKNOWN, false, false);
        TaskDiagnosticsDtos.Summary summary = new TaskDiagnosticsDtos.Summary(
                TASK_ID, "IMAGE", "SUCCEEDED", "COMPLETED", NOW, NOW, NOW, decision,
                "0123456789abcdef0123456789abcdef");
        when(service.list(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(PageResponse.of(List.of(summary), 1, 20, 1));
        when(service.get(Long.parseLong(TASK_ID))).thenReturn(detail(decision));
    }

    @Test
    void adminCanReadListAndDetailWithStringIds() throws Exception {
        mvc.perform(get("/api/v1/admin/diagnostics/tasks").with(actor(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.items[0].taskId").isString());
        mvc.perform(get("/api/v1/admin/diagnostics/tasks/{taskId}", TASK_ID)
                        .with(actor(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.decision.failureCategory").value("UNKNOWN"));
    }

    @Test
    void userAndAnonymousAreRejectedBeforeDiagnosticsAreRead() throws Exception {
        mvc.perform(get("/api/v1/admin/diagnostics/tasks").with(actor(AccountRole.USER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/diagnostics/tasks"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void missingAndMalformedIdentifiersUseStableSafeErrors() throws Exception {
        when(service.get(9L)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(get("/api/v1/admin/diagnostics/tasks/{taskId}", "9").with(actor(AccountRole.ADMIN)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isString());
        String malicious = "not-a-task";
        String body = mvc.perform(get("/api/v1/admin/diagnostics/tasks/{taskId}", malicious)
                        .with(actor(AccountRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isString())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain(malicious);
    }

    private TaskDiagnosticsDtos.Detail detail(TaskDiagnosticsDtos.Decision decision) {
        return new TaskDiagnosticsDtos.Detail(TASK_ID, "IMAGE", "SUCCEEDED", "COMPLETED",
                20, 20, NOW, NOW, NOW, NOW, NOW, decision,
                "0123456789abcdef0123456789abcdef", List.of(), List.of(), List.of(), List.of(),
                null, List.of(), null, null, null, List.of());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor actor(AccountRole role) {
        AuthenticatedAccount principal = new AuthenticatedAccount(7, "actor", role, 1,
                AccountStatus.ACTIVE, null, null, null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
