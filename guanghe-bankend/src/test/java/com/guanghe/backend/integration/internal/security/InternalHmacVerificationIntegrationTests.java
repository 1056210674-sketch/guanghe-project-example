package com.guanghe.backend.integration.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
@ExtendWith(OutputCaptureExtension.class)
class InternalHmacVerificationIntegrationTests {

    private static final String KEY_ID = "agent-service-v1";
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String PROBE_PATH = "/internal/v1/test/reverse-call";
    private static final String PROBE_TARGET = PROBE_PATH + "?mode=python-m1";

    @DynamicPropertySource
    static void internalProperties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.internal.enabled", () -> "true");
        registry.add("zhixiaohe.internal.java-m1-ready", () -> "true");
        registry.add("zhixiaohe.internal.java-m1-server-ready", () -> "false");
        registry.add("zhixiaohe.internal.m0-gate-status", () -> "NOT_RUN");
        registry.add("zhixiaohe.internal.key-id", () -> KEY_ID);
        registry.add("zhixiaohe.internal.secret", () -> new String(SECRET, StandardCharsets.UTF_8));
        registry.add("zhixiaohe.internal.allowed-clock-skew", () -> "60s");
        registry.add("zhixiaohe.internal.nonce-ttl", () -> "120s");
        registry.add("zhixiaohe.internal.reverse-probe-enabled", () -> "true");
        registry.add("zhixiaohe.internal.python.base-url", () -> "http://127.0.0.1:1");
        registry.add("zhixiaohe.internal.python.connect-timeout", () -> "100ms");
        registry.add("zhixiaohe.internal.python.read-timeout", () -> "100ms");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private GitProperties gitProperties;

    @MockitoBean
    private InternalNonceStore nonceStore;

    @BeforeEach
    void resetNonceStore() {
        reset(nonceStore);
        when(nonceStore.claim(anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    void validSignedRequestInjectsCallerContinuesTraceAndWritesStructuredAuditLog(
            CapturedOutput output
    ) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(java.util.Map.of("probe", "ok"));
        String traceparent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        SignedRequest signed = sign("POST", PROBE_TARGET, body, Instant.now().getEpochSecond());

        mockMvc.perform(post(URI.create(PROBE_TARGET))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("traceparent", traceparent)
                        .headers(signed.headers()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.callerKeyId").value(KEY_ID))
                .andExpect(jsonPath("$.traceId").value("0123456789abcdef0123456789abcdef"))
                .andExpect(header().string("X-Trace-Id", "0123456789abcdef0123456789abcdef"));

        assertThat(output.getOut())
                .contains("internal_hmac_verified")
                .contains("traceId=0123456789abcdef0123456789abcdef")
                .contains("path=" + PROBE_PATH)
                .contains("keyId=" + KEY_ID)
                .contains("queryMode=python-m1");
    }

    @Test
    void changedBodyAndExpiredTimestampAreRejectedBeforeNonceClaim() throws Exception {
        byte[] signedBody = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
        SignedRequest changedBody = sign("POST", PROBE_TARGET, signedBody, Instant.now().getEpochSecond());

        mockMvc.perform(post(URI.create(PROBE_TARGET))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":2}")
                        .headers(changedBody.headers()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_SIGNATURE_INVALID"));

        SignedRequest expired = sign("POST", PROBE_TARGET, signedBody, Instant.now().minusSeconds(61).getEpochSecond());
        mockMvc.perform(post(URI.create(PROBE_TARGET))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody)
                        .headers(expired.headers()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_SIGNATURE_INVALID"));

        verify(nonceStore, never()).claim(anyString(), anyString(), any());
    }

    @Test
    void changedRawQueryIsRejectedBeforeNonceClaim() throws Exception {
        byte[] body = "{\"probe\":\"query-integrity\"}".getBytes(StandardCharsets.UTF_8);
        SignedRequest signed = sign(
                "POST",
                PROBE_PATH + "?mode=python-m1&attempt=1",
                body,
                Instant.now().getEpochSecond()
        );

        mockMvc.perform(post(URI.create(PROBE_PATH + "?mode=python-m1-tampered&attempt=1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .headers(signed.headers()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_SIGNATURE_INVALID"));

        verify(nonceStore, never()).claim(anyString(), anyString(), any());
    }

    @Test
    void uppercaseNonceWithValidSignatureIsRejectedBeforeNonceClaim() throws Exception {
        byte[] body = "{\"probe\":\"uppercase-nonce\"}".getBytes(StandardCharsets.UTF_8);
        String uppercaseNonce = "9CA4EA78-5D80-4E87-8BCB-C0093470C54D";
        SignedRequest signed = sign(
                "POST",
                PROBE_PATH,
                body,
                Instant.now().getEpochSecond(),
                uppercaseNonce
        );

        mockMvc.perform(post(PROBE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .headers(signed.headers()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_SIGNATURE_INVALID"));

        verify(nonceStore, never()).claim(anyString(), anyString(), any());
    }

    @Test
    void correctlySignedRequestWithDuplicateNonceHeaderIsRejectedBeforeNonceClaim() throws Exception {
        assertHeaderCardinalityRejected(InternalHmacVerificationFilter.NONCE_HEADER, false);
    }

    @Test
    void duplicateValuesForEveryOtherSecurityHeaderAreRejectedBeforeNonceClaim() throws Exception {
        for (String headerName : List.of(
                InternalHmacVerificationFilter.KEY_ID_HEADER,
                InternalHmacVerificationFilter.TIMESTAMP_HEADER,
                InternalHmacVerificationFilter.CONTENT_SHA256_HEADER,
                InternalHmacVerificationFilter.SIGNATURE_HEADER
        )) {
            clearInvocations(nonceStore);
            assertHeaderCardinalityRejected(headerName, false);
        }
    }

    @Test
    void commaCombinedSecurityHeadersAreRejectedBeforeNonceClaim() throws Exception {
        for (String headerName : List.of(
                InternalHmacVerificationFilter.KEY_ID_HEADER,
                InternalHmacVerificationFilter.TIMESTAMP_HEADER,
                InternalHmacVerificationFilter.NONCE_HEADER,
                InternalHmacVerificationFilter.CONTENT_SHA256_HEADER,
                InternalHmacVerificationFilter.SIGNATURE_HEADER
        )) {
            clearInvocations(nonceStore);
            assertHeaderCardinalityRejected(headerName, true);
        }
    }

    @Test
    void duplicateNonceIsRejected() throws Exception {
        when(nonceStore.claim(anyString(), anyString(), any())).thenReturn(true, false);
        byte[] body = new byte[0];
        SignedRequest signed = sign("POST", PROBE_TARGET, body, Instant.now().getEpochSecond());

        mockMvc.perform(post(URI.create(PROBE_TARGET)).headers(signed.headers()))
                .andExpect(status().isOk());
        mockMvc.perform(post(URI.create(PROBE_TARGET)).headers(signed.headers()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_REPLAY_DETECTED"));
    }

    @Test
    void redisFailureFailsClosed() throws Exception {
        when(nonceStore.claim(anyString(), anyString(), any()))
                .thenThrow(new InternalNonceStoreUnavailableException());
        SignedRequest signed = sign("POST", PROBE_PATH, new byte[0], Instant.now().getEpochSecond());

        mockMvc.perform(post(PROBE_PATH).headers(signed.headers()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTERNAL_AUTHENTICATION_UNAVAILABLE"));
    }

    @Test
    void readinessIsUnsignedOnlyForLoopback() throws Exception {
        mockMvc.perform(get(InternalHmacVerificationFilter.READINESS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.javaM1Ready").value(true));

        mockMvc.perform(get(InternalHmacVerificationFilter.READINESS_PATH)
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void javaM1InfoIsReadOnlyAndReportsTheFullBuildCommit() throws Exception {
        String fullCommit = gitProperties.get("commit.id.full");
        assertThat(fullCommit).matches("[0-9a-f]{40,64}");

        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gitCommit").value(fullCommit))
                .andExpect(jsonPath("$.javaM1Ready").value(true))
                .andExpect(jsonPath("$.codeCapabilitiesReady").value(true))
                .andExpect(jsonPath("$.testServerDeployed").value(false))
                .andExpect(jsonPath("$.m0GateStatus").value("NOT_RUN"))
                .andExpect(jsonPath("$.python").doesNotExist());

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void missingSignatureCannotReachInternalProbe() throws Exception {
        mockMvc.perform(post(PROBE_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_SIGNATURE_INVALID"));

        verify(nonceStore, never()).claim(anyString(), anyString(), any());
    }

    private void assertHeaderCardinalityRejected(String headerName, boolean commaCombined) throws Exception {
        SignedRequest signed = sign("POST", PROBE_TARGET, new byte[0], Instant.now().getEpochSecond());
        String validValue = signed.headers().getFirst(headerName);
        assertThat(validValue).isNotNull();
        if (commaCombined) {
            signed.headers().set(headerName, validValue + "," + validValue);
        } else {
            signed.headers().add(headerName, validValue);
        }

        mockMvc.perform(post(URI.create(PROBE_TARGET)).headers(signed.headers()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_SIGNATURE_INVALID"));

        verify(nonceStore, never()).claim(anyString(), anyString(), any());
    }

    private SignedRequest sign(String method, String path, byte[] body, long epochSecond) {
        String nonce = UUID.randomUUID().toString();
        return sign(method, path, body, epochSecond, nonce);
    }

    private SignedRequest sign(String method, String path, byte[] body, long epochSecond, String nonce) {
        String timestamp = Long.toString(epochSecond);
        InternalHmacSigner.SignedContent signed = InternalHmacSigner.sign(
                timestamp, nonce, method, path, body, SECRET
        );
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set(InternalHmacVerificationFilter.KEY_ID_HEADER, KEY_ID);
        headers.set(InternalHmacVerificationFilter.TIMESTAMP_HEADER, timestamp);
        headers.set(InternalHmacVerificationFilter.NONCE_HEADER, nonce);
        headers.set(InternalHmacVerificationFilter.CONTENT_SHA256_HEADER, signed.bodySha256());
        headers.set(InternalHmacVerificationFilter.SIGNATURE_HEADER, signed.signature());
        return new SignedRequest(headers);
    }

    private record SignedRequest(org.springframework.http.HttpHeaders headers) {
    }
}
