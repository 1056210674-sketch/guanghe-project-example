package com.guanghe.backend.integration.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.integration.internal.security.InternalHmacSigner;
import com.guanghe.backend.integration.internal.security.InternalHmacVerificationFilter;
import com.guanghe.backend.integration.internal.security.InternalNonceStore;
import com.guanghe.backend.integration.internal.security.RedisInternalNonceStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "REMOTE_JAVA_M1_GATE_ENABLED", matches = "true")
class RemoteJavaM1SpringContextIntegrationTests {

    private static final String KEY_ID = "agent-service-v1";
    private static final byte[] HMAC_SECRET_BYTES = new byte[32];
    private static final byte[] JWT_SECRET = new byte[32];
    private static final String HMAC_SECRET;
    private static final String NONCE_PREFIX = "zhixiaohe:test:gate:java-m1:"
            + UUID.randomUUID().toString().replace("-", "") + ":";
    private static String claimedNonceKey;

    static {
        SecureRandom random = new SecureRandom();
        random.nextBytes(HMAC_SECRET_BYTES);
        random.nextBytes(JWT_SECRET);
        HMAC_SECRET = Base64.getEncoder().encodeToString(HMAC_SECRET_BYTES);
    }

    @DynamicPropertySource
    static void gateProperties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.internal.enabled", () -> "true");
        registry.add("zhixiaohe.internal.java-m1-ready", () -> "true");
        registry.add("zhixiaohe.internal.java-m1-server-ready", () -> "false");
        registry.add("zhixiaohe.internal.m0-gate-status", () -> "NOT_RUN");
        registry.add("zhixiaohe.internal.key-id", () -> KEY_ID);
        registry.add("zhixiaohe.internal.secret", () -> HMAC_SECRET);
        registry.add("zhixiaohe.internal.nonce-key-prefix", () -> NONCE_PREFIX);
        registry.add("zhixiaohe.internal.reverse-probe-enabled", () -> "true");
        registry.add("zhixiaohe.internal.python.base-url", () -> "http://127.0.0.1:1");
        registry.add("zhixiaohe.internal.python.connect-timeout", () -> "100ms");
        registry.add("zhixiaohe.internal.python.read-timeout", () -> "100ms");
        registry.add("zhixiaohe.auth.jwt.secret-base64",
                () -> Base64.getEncoder().encodeToString(JWT_SECRET));
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private InternalNonceStore nonceStore;
    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanNonce() {
        if (claimedNonceKey != null) {
            redis.delete(claimedNonceKey);
            claimedNonceKey = null;
        }
    }

    @Test
    void completeSpringContextUsesRealRedisForHmacAndDegradesWhenPythonIsDown() throws Exception {
        assertThat(nonceStore).isInstanceOf(RedisInternalNonceStore.class);

        ResponseEntity<JsonNode> internalReady = http.getForEntity(
                url(InternalHmacVerificationFilter.READINESS_PATH), JsonNode.class
        );
        assertThat(internalReady.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(internalReady.getBody().path("status").asText()).isEqualTo("UP");

        String rawPath = "/internal/v1/test/reverse-call?mode=python-m1";
        byte[] body = objectMapper.writeValueAsBytes(java.util.Map.of("probe", "remote-redis"));
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        InternalHmacSigner.SignedContent signed = InternalHmacSigner.sign(
                timestamp, nonce, "POST", rawPath, body,
                HMAC_SECRET.getBytes(StandardCharsets.UTF_8)
        );
        claimedNonceKey = NONCE_PREFIX + sha256Hex(KEY_ID + ":" + nonce);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(InternalHmacVerificationFilter.KEY_ID_HEADER, KEY_ID);
        headers.set(InternalHmacVerificationFilter.TIMESTAMP_HEADER, timestamp);
        headers.set(InternalHmacVerificationFilter.NONCE_HEADER, nonce);
        headers.set(InternalHmacVerificationFilter.CONTENT_SHA256_HEADER, signed.bodySha256());
        headers.set(InternalHmacVerificationFilter.SIGNATURE_HEADER, signed.signature());
        headers.set("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        HttpEntity<byte[]> request = new HttpEntity<>(body, headers);

        ResponseEntity<JsonNode> accepted = http.exchange(
                URI.create(url(rawPath)), HttpMethod.POST, request, JsonNode.class
        );
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody().path("accepted").asBoolean()).isTrue();
        assertThat(accepted.getBody().path("traceId").asText())
                .isEqualTo("0123456789abcdef0123456789abcdef");

        ResponseEntity<JsonNode> replay = http.exchange(
                URI.create(url(rawPath)), HttpMethod.POST, request, JsonNode.class
        );
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody().path("code").asText()).isEqualTo("INTERNAL_REPLAY_DETECTED");

        JsonNode info = http.getForObject(url("/actuator/info"), JsonNode.class);
        assertThat(info.path("javaM1Ready").asBoolean()).isTrue();
        assertThat(info.path("codeCapabilitiesReady").asBoolean()).isTrue();
        assertThat(info.path("testServerDeployed").asBoolean()).isFalse();
        assertThat(info.path("m0GateStatus").asText()).isEqualTo("NOT_RUN");
        assertThat(info.has("python")).isFalse();

        JsonNode readiness = http.getForObject(url("/actuator/health/readiness"), JsonNode.class);
        assertThat(readiness.path("status").asText()).isEqualTo("UP");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
