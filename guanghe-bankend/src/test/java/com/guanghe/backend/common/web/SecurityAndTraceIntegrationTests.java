package com.guanghe.backend.common.web;

import com.guanghe.backend.common.api.ApiResponse;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
@Import(SecurityAndTraceIntegrationTests.BudgetPolicyBoundaryProbeController.class)
class SecurityAndTraceIntegrationTests {

    private static final String BUDGET_POLICY = "/api/v1/execution-budget-policy";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointIsPublicAndReturnsTraceId() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER));
    }

    @Test
    void unknownBusinessEndpointIsNotPublic() throws Exception {
        mockMvc.perform(get("/api/v1/private-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER));
    }

    @Test
    void internalRoutesFailClosedWhenTheHmacHarnessIsDisabled() throws Exception {
        mockMvc.perform(get("/internal/v1/unsafe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void registrationConfigUsesTheStandardEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/public/registration-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.registrationEnabled").value(false))
                .andExpect(jsonPath("$.data.terms.version").value("terms-test-v1"))
                .andExpect(jsonPath("$.data.privacy.version").value("privacy-test-v1"))
                .andExpect(jsonPath("$.data.smsCooldownSeconds").value(60))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT"})
    void executionBudgetPolicyRequiresAuthentication(String method) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), BUDGET_POLICY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT"})
    void userCanReachTheExactExecutionBudgetPolicyHandlers(String method) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), BUDGET_POLICY)
                        .with(user("budget-owner").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"enabled\":true,\"maxPointsPerAction\":6,\"maxPointsPerDay\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").value(method + ":budget-owner"))
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT"})
    void adminCannotReadOrChangeAUserExecutionBudgetPolicy(String method) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), BUDGET_POLICY)
                        .with(user("budget-admin").roles("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH", "DELETE", "HEAD", "OPTIONS"})
    void executionBudgetPolicyDoesNotAuthorizeOtherMethods(String method) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), BUDGET_POLICY)
                        .with(user("budget-owner").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/execution-budget-policy/", "/api/v1/execution-budget-policy/other",
            "/api/v1/execution-budget-policies", "/api/v1/private-test"})
    void executionBudgetPolicyDoesNotAuthorizeSubpathsOrUnknownEndpoints(String path) throws Exception {
        for (String role : new String[]{"USER", "ADMIN"}) {
            for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.PUT}) {
                mockMvc.perform(request(method, path).with(user("budget-boundary").roles(role)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
        }
    }

    // Explicitly imported only here; not component-scanned into paid-task tests.
    // This is a handler probe, not an alternate budget service or authorization path.
    @TestComponent
    @RestController
    static class BudgetPolicyBoundaryProbeController {
        @GetMapping(BUDGET_POLICY)
        ApiResponse<String> budget(Principal principal) {
            return ApiResponse.success("GET:" + principal.getName());
        }

        @PutMapping(BUDGET_POLICY)
        ApiResponse<String> putBudget(Principal principal) {
            return ApiResponse.success("PUT:" + principal.getName());
        }
    }
}
