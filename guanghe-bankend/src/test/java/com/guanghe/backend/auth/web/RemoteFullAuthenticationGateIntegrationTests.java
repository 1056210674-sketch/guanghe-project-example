package com.guanghe.backend.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "REMOTE_FULL_AUTH_GATE_ENABLED", matches = "true")
class RemoteFullAuthenticationGateIntegrationTests {

    private static final String EXPECTED_DATABASE = "zhixiaohe_test";
    private static final String EXPECTED_RUNTIME_ROLE = "zhixiaohe_test_app";
    private static final int MAX_FAILURES = 3;
    private static final int WINDOW_SECONDS = 60;
    private static final String KEY_PREFIX = "zhixiaohe:test:gate:full-auth:"
            + UUID.randomUUID().toString().replace("-", "") + ":";
    private static final byte[] JWT_SECRET = new byte[32];

    static {
        new SecureRandom().nextBytes(JWT_SECRET);
    }

    @DynamicPropertySource
    static void gateProperties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.internal.enabled", () -> "false");
        registry.add("zhixiaohe.auth.enabled", () -> "true");
        registry.add("zhixiaohe.auth.jwt.issuer", () -> "remote-full-auth-gate");
        registry.add("zhixiaohe.auth.jwt.access-token-ttl-seconds", () -> "3600");
        registry.add("zhixiaohe.auth.jwt.secret-base64",
                () -> Base64.getEncoder().encodeToString(JWT_SECRET));
        registry.add("zhixiaohe.auth.login-rate-limit.max-failures",
                () -> Integer.toString(MAX_FAILURES));
        registry.add("zhixiaohe.auth.login-rate-limit.window-seconds",
                () -> Integer.toString(WINDOW_SECONDS));
        registry.add("zhixiaohe.auth.login-rate-limit.key-prefix", () -> KEY_PREFIX);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    void realHttpUsesFormalPostgresqlAndRedisForRateLimitLoginMeAndLogout() throws Exception {
        assertExactTestDatabaseAndRuntimeRole();
        assertThat(required("REMOTE_FULL_AUTH_EXPECTED_DATABASE")).isEqualTo(EXPECTED_DATABASE);
        assertThat(required("REDIS_SSL_ENABLED")).isEqualToIgnoringCase("true");

        String failedUsername = "remote-gate-missing-" + UUID.randomUUID();
        String failedUsernameKey = usernameKey(failedUsername.toLowerCase());
        String sourceIpKey = ipKey("127.0.0.1");
        String realUsername = required("REMOTE_FULL_AUTH_GATE_USERNAME");
        String realUsernameKey = usernameKey(realUsername.toLowerCase());
        List<String> cleanupKeys = List.of(failedUsernameKey, sourceIpKey, realUsernameKey);

        redis.delete(cleanupKeys);
        try {
            for (int attempt = 1; attempt < MAX_FAILURES; attempt++) {
                assertLoginError(failedUsername, "intentionally-wrong-gate-password",
                        HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
            }
            assertLoginError(failedUsername, "intentionally-wrong-gate-password",
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
            assertLoginError(failedUsername, "intentionally-wrong-gate-password",
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");

            assertThat(redis.opsForValue().get(failedUsernameKey)).isEqualTo(Integer.toString(MAX_FAILURES));
            assertThat(redis.opsForValue().get(sourceIpKey)).isEqualTo(Integer.toString(MAX_FAILURES));
            assertThat(redis.getExpire(failedUsernameKey, TimeUnit.SECONDS))
                    .isBetween(1L, (long) WINDOW_SECONDS);
            assertThat(redis.getExpire(sourceIpKey, TimeUnit.SECONDS))
                    .isBetween(1L, (long) WINDOW_SECONDS);

            redis.delete(cleanupKeys);
            String accessToken = loginSuccessfully(
                    realUsername,
                    required("REMOTE_FULL_AUTH_GATE_PASSWORD"),
                    required("REMOTE_FULL_AUTH_GATE_EXPECTED_ROLE")
            );
            HttpHeaders bearer = new HttpHeaders();
            bearer.setBearerAuth(accessToken);

            var me = http.exchange(url("/api/v1/auth/me"), HttpMethod.GET,
                    new HttpEntity<>(bearer), String.class);
            assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(objectMapper.readTree(me.getBody()).path("data").path("username").asText())
                    .isEqualToIgnoringCase(realUsername);

            var logout = http.exchange(url("/api/v1/auth/logout"), HttpMethod.POST,
                    new HttpEntity<>(bearer), String.class);
            assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);

            var staleMe = http.exchange(url("/api/v1/auth/me"), HttpMethod.GET,
                    new HttpEntity<>(bearer), String.class);
            assertThat(staleMe.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(objectMapper.readTree(staleMe.getBody()).path("code").asText())
                    .isEqualTo("UNAUTHENTICATED");

            System.out.println("REMOTE_FULL_AUTH_GATE http_server=real-random-port");
            System.out.println("REMOTE_FULL_AUTH_GATE postgresql=zhixiaohe_test runtime_role=dml-only ssl=yes");
            System.out.println("REMOTE_FULL_AUTH_GATE redis=remote-tls isolated-prefix=yes");
            System.out.println("REMOTE_FULL_AUTH_GATE failure_counter_ttl_and_rate_limit=yes");
            System.out.println("REMOTE_FULL_AUTH_GATE login_me_logout_stale_jwt=yes");
        } finally {
            redis.delete(cleanupKeys);
        }
    }

    private void assertExactTestDatabaseAndRuntimeRole() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT current_database(), current_user,
                            COALESCE((SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()), false)
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo(EXPECTED_DATABASE);
            assertThat(result.getString(2)).isEqualTo(EXPECTED_RUNTIME_ROLE);
            assertThat(result.getBoolean(3)).isTrue();
        }
    }

    private void assertLoginError(String username, String password, HttpStatus status, String code)
            throws Exception {
        var response = postLogin(username, password);
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(objectMapper.readTree(response.getBody()).path("code").asText()).isEqualTo(code);
    }

    private String loginSuccessfully(String username, String password, String expectedRole) throws Exception {
        var response = postLogin(username, password);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("data").path("user").path("role").asText()).isEqualTo(expectedRole);
        String token = body.path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private org.springframework.http.ResponseEntity<String> postLogin(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(url("/api/v1/auth/login"),
                new HttpEntity<>(Map.of("username", username, "password", password), headers), String.class);
    }

    private String usernameKey(String usernameNormalized) throws Exception {
        return KEY_PREFIX + "auth:login-failure:username:" + sha256(usernameNormalized);
    }

    private String ipKey(String sourceIp) throws Exception {
        return KEY_PREFIX + "auth:login-failure:ip:" + sha256(sourceIp);
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the remote full authentication Gate");
        }
        return value;
    }
}
