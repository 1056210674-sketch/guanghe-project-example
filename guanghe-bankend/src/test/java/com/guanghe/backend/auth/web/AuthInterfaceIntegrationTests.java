package com.guanghe.backend.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountIdentity;
import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.access.AccessDtos;
import com.guanghe.backend.auth.access.AccessService;
import com.guanghe.backend.auth.ratelimit.LoginAttemptGuard;
import com.guanghe.backend.auth.security.FixedSaltMd5PasswordEncoder;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
@Import(AuthInterfaceIntegrationTests.BoundaryProbeController.class)
class AuthInterfaceIntegrationTests {

    private static final byte[] TEST_SECRET = new byte[32];

    static {
        new SecureRandom().nextBytes(TEST_SECRET);
    }

    @DynamicPropertySource
    static void authenticationProperties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.auth.enabled", () -> "true");
        registry.add("zhixiaohe.auth.jwt.issuer", () -> "auth-interface-test");
        registry.add("zhixiaohe.auth.jwt.access-token-ttl-seconds", () -> "3600");
        registry.add("zhixiaohe.auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(TEST_SECRET));
        registry.add("zhixiaohe.auth.login-rate-limit.max-failures", () -> "5");
        registry.add("zhixiaohe.auth.login-rate-limit.window-seconds", () -> "900");
        registry.add("zhixiaohe.auth.account-access.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AccountMapper accountMapper;
    @MockitoBean
    private LoginAttemptGuard loginAttemptGuard;
    @MockitoBean
    private AccessService accessService;

    private final FixedSaltMd5PasswordEncoder encoder = new FixedSaltMd5PasswordEncoder();
    private AtomicInteger adminTokenVersion;
    private AtomicInteger userTokenVersion;
    private AtomicReference<AccountStatus> adminStatus;
    private AtomicReference<AccountStatus> userStatus;

    @BeforeEach
    void setUpAccounts() {
        adminTokenVersion = new AtomicInteger(1);
        userTokenVersion = new AtomicInteger(1);
        adminStatus = new AtomicReference<>(AccountStatus.ACTIVE);
        userStatus = new AtomicReference<>(AccountStatus.ACTIVE);
        when(accountMapper.findByUsernameNormalized(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> switch (invocation.<String>getArgument(0)) {
                    case "guanghe888" -> Optional.of(account(101L, "Guanghe888", "Guanghe888", AccountRole.ADMIN,
                            adminTokenVersion.get()));
                    case "test001" -> Optional.of(account(102L, "test001", "test001", AccountRole.USER,
                            userTokenVersion.get()));
                    default -> Optional.empty();
                });
        when(accountMapper.findIdentityById(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            if (id == 101L) {
                return Optional.of(identity(101L, "Guanghe888", AccountRole.ADMIN,
                        adminTokenVersion.get(), adminStatus.get()));
            }
            if (id == 102L) {
                return Optional.of(identity(102L, "test001", AccountRole.USER,
                        userTokenVersion.get(), userStatus.get()));
            }
            return Optional.empty();
        });
        when(accountMapper.incrementTokenVersion(anyLong(), anyInt())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            int expected = invocation.getArgument(1);
            AtomicInteger version = id == 101L ? adminTokenVersion : id == 102L ? userTokenVersion : null;
            return version != null && version.compareAndSet(expected, expected + 1) ? 1 : 0;
        });
    }

    @Test
    void loginIsCaseInsensitiveAndJwtContainsRequiredClaims() throws Exception {
        String token = login("gUaNgHe888", "Guanghe888");
        var jwt = jwtDecoder.decode(token);

        assertThat(jwt.getClaimAsString("userId")).isEqualTo("101");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.<Number>getClaim("tokenVersion").intValue()).isEqualTo(1);
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void meLogoutAndStaleJwtFollowTheAccountTokenVersion() throws Exception {
        String token = login("TEST001", "test001");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("102"))
                .andExpect(jsonPath("$.data.username").value("test001"))
                .andExpect(jsonPath("$.data.role").value("USER"));

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void nonexistentAccountAndWrongPasswordReturnTheSameResponse() throws Exception {
        MvcResult missing = loginFailure("missing", "wrong");
        MvcResult wrongPassword = loginFailure("test001", "wrong");
        JsonNode missingBody = objectMapper.readTree(missing.getResponse().getContentAsByteArray());
        JsonNode wrongBody = objectMapper.readTree(wrongPassword.getResponse().getContentAsByteArray());

        assertThat(missingBody.path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(missingBody.path("message").asText()).isEqualTo("账号或密码错误");
        assertThat(wrongBody.path("code")).isEqualTo(missingBody.path("code"));
        assertThat(wrongBody.path("message")).isEqualTo(missingBody.path("message"));
    }

    @Test
    void adminAndUserRoutesAreMutuallySeparated() throws Exception {
        String admin = login("Guanghe888", "Guanghe888");
        String user = login("test001", "test001");

        mockMvc.perform(get("/api/v1/admin/probe").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/probe").header("Authorization", "Bearer " + user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/user/probe").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/user/probe").header("Authorization", "Bearer " + admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void meAndLogoutRequireBearerAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void accountStatusIsRecheckedForEveryAuthenticatedRequest() throws Exception {
        String token = login("test001", "test001");
        userStatus.set(AccountStatus.DISABLED);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void rateLimitedLoginUsesTheStandardErrorEnvelope() throws Exception {
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED))
                .when(loginAttemptGuard).checkAllowed("test001", "127.0.0.1");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("test001", "test001"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void redisDependencyFailureReturns503WithoutIssuingAJwt() throws Exception {
        doThrow(new BusinessException(ErrorCode.AUTHENTICATION_DEPENDENCY_UNAVAILABLE))
                .when(loginAttemptGuard).checkAllowed("test001", "127.0.0.1");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("test001", "test001"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void localNginxForwardingProducesDifferentRateLimitInputsForDifferentClients() throws Exception {
        String requestBody = objectMapper.writeValueAsString(new LoginRequest("test001", "wrong"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .header("Forwarded", "for=198.51.100.10;proto=https")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("::1");
                            return request;
                        })
                        .header("X-Forwarded-For", "203.0.113.20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized());

        verify(loginAttemptGuard).checkAllowed("test001", "198.51.100.10");
        verify(loginAttemptGuard).recordFailure("test001", "198.51.100.10");
        verify(loginAttemptGuard).checkAllowed("test001", "203.0.113.20");
        verify(loginAttemptGuard).recordFailure("test001", "203.0.113.20");
    }

    @Test
    void directConnectionCannotForgeTheRateLimitSourceIp() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.42");
                            return request;
                        })
                        .header("Forwarded", "for=203.0.113.9")
                        .header("X-Forwarded-For", "203.0.113.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("test001", "wrong"))))
                .andExpect(status().isUnauthorized());

        verify(loginAttemptGuard).checkAllowed("test001", "198.51.100.42");
        verify(loginAttemptGuard).recordFailure("test001", "198.51.100.42");
    }

    @Test
    void adminProvisioningRouteReturnsTemporaryPasswordOnceAndRejectsUserRole() throws Exception {
        var user = new AccessDtos.UserResponse("301", "+8613800138000", "+8613800138000", "新用户", "ACTIVE",
                "MUST_CHANGE_PASSWORD", 1, null);
        when(accessService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AccessDtos.ProvisionedUserResponse(user, "Temporary-Password"));
        String admin = login("Guanghe888", "Guanghe888");
        String ordinaryUser = login("test001", "test001");
        String body = "{\"phone\":\"13800138000\",\"displayName\":\"新用户\",\"initialPointGrant\":20}";

        mockMvc.perform(post("/api/v1/admin/users").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.id").value("301"))
                .andExpect(jsonPath("$.data.temporaryPassword").value("Temporary-Password"));
        mockMvc.perform(post("/api/v1/admin/users").header("Authorization", "Bearer " + ordinaryUser)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Abc", "YHG123", "valid_name", "Abcdefghijklmnopqrstuvwxyz123456"})
    void adminCanProvisionUsernameOnlyUserAndUserRoleStillCannot(String username) throws Exception {
        var user = new AccessDtos.UserResponse("301", username, null, "用户名用户", "ACTIVE",
                "MUST_CHANGE_PASSWORD", 1, null);
        when(accessService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AccessDtos.ProvisionedUserResponse(user, "Temporary-Password"));
        String admin = login("Guanghe888", "Guanghe888");
        String ordinaryUser = login("test001", "test001");
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "username", username, "displayName", "用户名用户", "initialPointGrant", 500));

        mockMvc.perform(post("/api/v1/admin/users").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.username").value(username))
                .andExpect(jsonPath("$.data.user.phone").isEmpty())
                .andExpect(jsonPath("$.data.temporaryPassword").value("Temporary-Password"));
        mockMvc.perform(post("/api/v1/admin/users").header("Authorization", "Bearer " + ordinaryUser)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"phone\":\"13800138000\",\"username\":\"valid123\"}",
            "{\"username\":\"\"}", "{\"username\":\"   \"}", "{\"phone\":\"   \"}",
            "{\"username\":\"ab\"}", "{\"username\":\"123abc\"}", "{\"username\":\"用户123\"}",
            "{\"username\":\"bad-name\"}", "{\"username\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}",
            "{\"username\":\"valid123\",\"role\":\"ADMIN\"}",
            "{\"username\":\"valid123\",\"password\":\"chosen-by-client\"}",
            "{\"username\":\"valid123\",\"tokenVersion\":50}"
    })
    void invalidOrPrivilegedProvisioningFieldsNeverReachTheService(String fields) throws Exception {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(fields);
        body.put("displayName", "用户").put("initialPointGrant", 500);
        String admin = login("Guanghe888", "Guanghe888");

        mockMvc.perform(post("/api/v1/admin/users").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(accessService, org.mockito.Mockito.never()).create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void firstLoginCompletionIsPublicButReturnsOnlyTheFreshAccessTokenShape() throws Exception {
        AccountRecord pending = new AccountRecord(301L, "+8613800138000", "+8613800138000",
                "+8613800138000", encoder.encode("temporary"), FixedSaltMd5PasswordEncoder.ALGORITHM_ID, 1,
                AccountRole.USER, AccountStatus.ACTIVE, "新用户", null, "MUST_CHANGE_PASSWORD");
        when(accountMapper.findByUsernameNormalized("+8613800138000")).thenReturn(Optional.of(pending));
        when(accessService.issueFirstLogin(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LoginResponse(null, "Activation", Instant.parse("2026-09-11T00:30:00Z"),
                        AuthUserResponse.from(pending), "COMPLETE_FIRST_LOGIN", "activation", null, null));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"138-0013-8000\",\"password\":\"temporary\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.activationToken").value("activation"))
                .andExpect(jsonPath("$.data.nextAction").value("COMPLETE_FIRST_LOGIN"))
                .andExpect(jsonPath("$.data.user.phoneMasked").value("+86****8000"));

        AccountRecord active = account(102L, "test001", "test001", AccountRole.USER, 2);
        when(accessService.complete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new LoginResponse("fresh-access", "Bearer", Instant.parse("2026-09-14T00:00:00Z"),
                        AuthUserResponse.from(active)));

        mockMvc.perform(post("/api/v1/auth/complete-first-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activationToken\":\"activation\",\"newPassword\":\"new-password-123\","
                                + "\"termsAccepted\":true,\"privacyAccepted\":true,"
                                + "\"termsVersion\":\"terms-test-v1\",\"privacyVersion\":\"privacy-test-v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("fresh-access"))
                .andExpect(jsonPath("$.data.nextAction").doesNotExist())
                .andExpect(jsonPath("$.data.activationToken").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 10, 128})
    void firstLoginAcceptsSixOrMoreCharactersWithoutTruncatingThePassword(int length) throws Exception {
        String newPassword = "p".repeat(length);
        var request = new CompleteFirstLoginRequest("activation", newPassword,
                true, true, "terms-test-v1", "privacy-test-v1");
        AccountRecord active = account(102L, "test001", "test001", AccountRole.USER, 2);
        when(accessService.complete(org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new LoginResponse("fresh-access", "Bearer", Instant.parse("2026-09-14T00:00:00Z"),
                        AuthUserResponse.from(active)));

        mockMvc.perform(post("/api/v1/auth/complete-first-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("fresh-access"));
        verify(accessService).complete(org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 129})
    void firstLoginRejectsOutOfBoundsPasswordsBeforeAnyAccountChange(int length) throws Exception {
        var request = new CompleteFirstLoginRequest("activation", "p".repeat(length),
                true, true, "terms-test-v1", "privacy-test-v1");

        mockMvc.perform(post("/api/v1/auth/complete-first-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(accessService);
    }

    private String login(String username, String password) throws Exception {
        String request = objectMapper.writeValueAsString(new LoginRequest(username, password));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data").path("accessToken").asText();
    }

    private MvcResult loginFailure(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isUnauthorized())
                .andReturn();
    }

    private AccountRecord account(
            long id,
            String username,
            String rawPassword,
            AccountRole role,
            int tokenVersion
    ) {
        return new AccountRecord(
                id,
                username,
                username.toLowerCase(),
                "+8610000000002",
                encoder.encode(rawPassword),
                FixedSaltMd5PasswordEncoder.ALGORITHM_ID,
                tokenVersion,
                role,
                AccountStatus.ACTIVE,
                null,
                null
        );
    }

    private AccountIdentity identity(
            long id,
            String username,
            AccountRole role,
            int tokenVersion,
            AccountStatus status
    ) {
        return new AccountIdentity(
                id, username, "+8610000000002", tokenVersion, role, status, null, null
        );
    }

    @RestController
    static class BoundaryProbeController {

        @GetMapping("/api/v1/admin/probe")
        String adminProbe() {
            return "admin";
        }

        @GetMapping("/api/v1/user/probe")
        String userProbe() {
            return "user";
        }
    }
}
