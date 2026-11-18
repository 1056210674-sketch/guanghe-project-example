package com.guanghe.backend.admin.agentversion;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.integration.internal.client.PythonCallResult;
import com.guanghe.backend.integration.internal.client.PythonCapabilitiesResponse;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class AgentVersionInterfaceIntegrationTests {
    private static final String BUNDLE = "agent-bundle-v1.15.0";

    @Autowired private MockMvc mvc;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @MockitoBean private AgentVersionMapper mapper;
    @MockitoBean private PythonInternalClient python;

    @BeforeEach
    void setUp() {
        AgentVersionRecords.VersionAggregate version = new AgentVersionRecords.VersionAggregate(
                BUNDLE, time(10), time(12), 2);
        when(python.capabilities()).thenReturn(PythonCallResult.available(200, capabilities()));
        when(mapper.listVersions(nullable(String.class), anyLong(), anyInt()))
                .thenReturn(List.of(version));
        when(mapper.countVersions(nullable(String.class))).thenReturn(1L);
        when(mapper.findVersion(BUNDLE)).thenReturn(Optional.of(version));
        when(mapper.listRuns(eq(BUNDLE), anyLong(), anyInt())).thenReturn(List.of(
                new AgentVersionRecords.AssociatedRun(101, "TASK", 201, "ARTICLE",
                        "SUCCEEDED", "COMPLETED", "DEEPSEEK", "deepseek-chat", null,
                        time(10), time(10).plusSeconds(2)),
                new AgentVersionRecords.AssociatedRun(102, "WORKFLOW", 202, "WORKFLOW",
                        "FAILED", null, null, null, "TOOL_FAILED", time(11), time(11).plusSeconds(3))));
        when(mapper.countRuns(BUNDLE)).thenReturn(2L);
    }

    @Test
    void operatorSeesLiveEvidenceObservedHistoryRunsAndExplicitMissingEvaluations() throws Exception {
        mvc.perform(get("/api/v1/admin/agent-versions").with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentRuntime.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.currentRuntime.bundleVersion").value(BUNDLE))
                .andExpect(jsonPath("$.data.currentRuntime.promptHash").value("b".repeat(64)))
                .andExpect(jsonPath("$.data.currentRuntime.profiles[0].profileCode").value("GENERAL"))
                .andExpect(jsonPath("$.data.versions.items[0].status").value("CURRENT"))
                .andExpect(jsonPath("$.data.versions.items[0].runCount").value(2));

        mvc.perform(get("/api/v1/admin/agent-versions/{bundleVersion}", BUNDLE)
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.bundleVersion").value(BUNDLE))
                .andExpect(jsonPath("$.data.version.status").value("CURRENT"))
                .andExpect(jsonPath("$.data.evaluationAvailability").value("NOT_REGISTERED"))
                .andExpect(jsonPath("$.data.dataGaps[0]").value("EVALUATIONS_NOT_REGISTERED"));

        mvc.perform(get("/api/v1/admin/agent-versions/{bundleVersion}/runs", BUNDLE)
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].executionId").value("101"))
                .andExpect(jsonPath("$.data.items[0].source").value("TASK"))
                .andExpect(jsonPath("$.data.items[0].durationMs").value(2000))
                .andExpect(jsonPath("$.data.items[1].source").value("WORKFLOW"))
                .andExpect(jsonPath("$.data.items[1].provider").doesNotExist());

        mvc.perform(get("/api/v1/admin/agent-versions/{bundleVersion}/evaluations", BUNDLE)
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability").value("NOT_REGISTERED"))
                .andExpect(jsonPath("$.data.results.items").isEmpty())
                .andExpect(jsonPath("$.data.results.total").value(0));
    }

    @Test
    void unavailableRuntimeNeverPromotesLatestObservedVersionToCurrent() throws Exception {
        when(python.capabilities()).thenReturn(PythonCallResult.unavailable(
                null, PythonCallResult.Reason.TIMEOUT));

        mvc.perform(get("/api/v1/admin/agent-versions").with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentRuntime.availability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.currentRuntime.reasonCode").value("TIMEOUT"))
                .andExpect(jsonPath("$.data.currentRuntime.bundleVersion").doesNotExist())
                .andExpect(jsonPath("$.data.versions.items[0].status").value("OBSERVED"));
    }

    @Test
    void capabilityEvidenceOutsideTheBrowserContractDegradesAsAWhole() throws Exception {
        List<String> tooManyTaskTypes = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> "TYPE_" + index).toList();
        PythonCapabilitiesResponse malformed = new PythonCapabilitiesResponse(
                "1.0.0", BUNDLE, "a".repeat(64), "b".repeat(64),
                "0123456789abcdef0123456789abcdef01234567",
                new PythonCapabilitiesResponse.ModelGateway(
                        PythonCapabilitiesResponse.GatewayMode.REAL, "DEEPSEEK", "deepseek-chat",
                        PythonCapabilitiesResponse.ProviderStatus.AVAILABLE, null),
                List.of(new PythonCapabilitiesResponse.Profile(
                        "GENERAL", "profile-v1", tooManyTaskTypes)),
                List.of());
        when(python.capabilities()).thenReturn(PythonCallResult.available(200, malformed));

        mvc.perform(get("/api/v1/admin/agent-versions").with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentRuntime.availability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.currentRuntime.reasonCode").value("INVALID_RESPONSE"))
                .andExpect(jsonPath("$.data.currentRuntime.bundleVersion").doesNotExist())
                .andExpect(jsonPath("$.data.currentRuntime.profiles").isEmpty())
                .andExpect(jsonPath("$.data.versions.items[0].status").value("OBSERVED"));
    }

    @Test
    void validLargePageUsesLongOffsetAndUnknownOrMalformedVersionsFailClosed() throws Exception {
        mvc.perform(get("/api/v1/admin/agent-versions")
                        .queryParam("page", "21474838").queryParam("size", "100")
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk());
        verify(mapper).listVersions(BUNDLE, 2_147_483_700L, 100);

        mvc.perform(get("/api/v1/admin/agent-versions/not-observed")
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/agent-versions/{bundleVersion}", "bad bundle")
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void everyVersionRouteIsReadOnlyAndRequiresAnOperator() throws Exception {
        for (String path : List.of("/api/v1/admin/agent-versions",
                "/api/v1/admin/agent-versions/" + BUNDLE,
                "/api/v1/admin/agent-versions/" + BUNDLE + "/runs",
                "/api/v1/admin/agent-versions/" + BUNDLE + "/evaluations")) {
            mvc.perform(get(path).with(user(AccountRole.USER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        Set<String> implemented = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            if (method.getBeanType() == AgentVersionController.class) {
                mapping.getMethodsCondition().getMethods().forEach(httpMethod ->
                        mapping.getPatternValues().forEach(path ->
                                implemented.add(httpMethod.name() + " " + path)));
            }
        });
        assertThat(implemented).containsExactlyInAnyOrder(
                "GET /api/v1/admin/agent-versions",
                "GET /api/v1/admin/agent-versions/{bundleVersion}",
                "GET /api/v1/admin/agent-versions/{bundleVersion}/runs",
                "GET /api/v1/admin/agent-versions/{bundleVersion}/evaluations");
    }

    private PythonCapabilitiesResponse capabilities() {
        return new PythonCapabilitiesResponse("1.0.0", BUNDLE, "a".repeat(64), "b".repeat(64),
                "0123456789abcdef0123456789abcdef01234567",
                new PythonCapabilitiesResponse.ModelGateway(
                        PythonCapabilitiesResponse.GatewayMode.REAL, "DEEPSEEK", "deepseek-chat",
                        PythonCapabilitiesResponse.ProviderStatus.AVAILABLE, null),
                List.of(new PythonCapabilitiesResponse.Profile(
                        "GENERAL", "profile-v1", List.of("ARTICLE", "GEO"))),
                List.of(new PythonCapabilitiesResponse.Provider("DEEPSEEK",
                        PythonCapabilitiesResponse.ProviderStatus.AVAILABLE, "deepseek-chat")));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor user(AccountRole role) {
        AuthenticatedAccount principal = new AuthenticatedAccount(7, "actor", role, 1,
                AccountStatus.ACTIVE, null, null, null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    private OffsetDateTime time(int hour) {
        return OffsetDateTime.of(2026, 9, 22, hour, 0, 0, 0, ZoneOffset.UTC);
    }
}
