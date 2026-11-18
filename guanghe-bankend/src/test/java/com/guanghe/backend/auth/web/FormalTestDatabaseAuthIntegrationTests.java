package com.guanghe.backend.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.ratelimit.LoginAttemptGuard;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "FORMAL_AUTH_GATE_ENABLED", matches = "true")
class FormalTestDatabaseAuthIntegrationTests {

    private static final String EXPECTED_DATABASE = "zhixiaohe_test";
    private static final String EXPECTED_RUNTIME_ROLE = "zhixiaohe_test_app";
    private static final byte[] TEST_SECRET = new byte[32];

    static {
        new SecureRandom().nextBytes(TEST_SECRET);
    }

    @DynamicPropertySource
    static void authenticationProperties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.internal.enabled", () -> "false");
        registry.add("zhixiaohe.auth.enabled", () -> "true");
        registry.add("zhixiaohe.auth.jwt.issuer", () -> "formal-test-auth-gate");
        registry.add("zhixiaohe.auth.jwt.access-token-ttl-seconds", () -> "3600");
        registry.add("zhixiaohe.auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(TEST_SECRET));
    }

    @Autowired
    private DataSource dataSource;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LoginAttemptGuard loginAttemptGuard;

    @BeforeEach
    void verifyExactDatabaseBeforeTokenVersionMutation() throws Exception {
        assertThat(required("FORMAL_AUTH_EXPECTED_DATABASE")).isEqualTo(EXPECTED_DATABASE);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                    SELECT current_database(), current_user,
                           current_setting('server_version_num')::integer,
                           COALESCE((SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()), false)
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(EXPECTED_DATABASE);
                assertThat(result.getString(2)).isEqualTo(EXPECTED_RUNTIME_ROLE);
                assertThat(result.getInt(3)).isBetween(180000, 189999);
                assertThat(result.getBoolean(4)).isTrue();
            }
        }
    }

    @Test
    void fixedAccountsLoginMeLogoutAndRejectTheirStaleJwt() throws Exception {
        verifyAccountLifecycle("gUaNgHe888", "Guanghe888", "ADMIN");
        verifyAccountLifecycle("TEST001", "test001", "USER");
    }

    private void verifyAccountLifecycle(String username, String password, String expectedRole) throws Exception {
        String token = login(username, password, expectedRole);
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value(expectedRole))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private String login(String username, String password, String expectedRole) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value(expectedRole))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return body.path("data").path("accessToken").asText();
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the formal authentication gate");
        }
        return value;
    }
}
