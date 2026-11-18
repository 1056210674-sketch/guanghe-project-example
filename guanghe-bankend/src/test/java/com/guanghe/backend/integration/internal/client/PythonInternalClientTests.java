package com.guanghe.backend.integration.internal.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.web.TraceIdHolder;
import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import com.guanghe.backend.integration.internal.security.InternalHmacSigner;
import com.guanghe.backend.integration.internal.security.InternalHmacVerificationFilter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class PythonInternalClientTests {

    private static final String SECRET_TEXT = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.ofEpochSecond(1_787_625_000L);

    private HttpServer server;
    private InternalCommunicationProperties properties;
    private Logger clientLogger;
    private ListAppender<ILoggingEvent> diagnostics;

    @BeforeEach
    void setUp() throws IOException {
        clientLogger = (Logger) LoggerFactory.getLogger(PythonInternalClient.class);
        diagnostics = new ListAppender<>();
        diagnostics.start();
        clientLogger.addAppender(diagnostics);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        properties = new InternalCommunicationProperties();
        properties.setSecret(SECRET_TEXT);
        properties.setKeyId("agent-service-v1");
        properties.getPython().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getPython().setConnectTimeout(Duration.ofSeconds(2));
        properties.getPython().setReadTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(diagnostics);
        diagnostics.stop();
        MDC.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void geoInterpretationUsesSignedBoundedPostAndDoesNotBlindRetry() {
        MDC.put(TraceIdHolder.MDC_KEY, "0123456789abcdef0123456789abcdef");
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/internal/v1/geo/syntheses", exchange -> {
            calls.incrementAndGet();
            byte[] body=exchange.getRequestBody().readAllBytes();
            assertSigned(exchange.getRequestHeaders(),"POST","/internal/v1/geo/syntheses",body);
            json(exchange,503,"{\"detail\":{\"code\":\"GEO_SYNTHESIS_UNAVAILABLE\"}}");
        });
        server.start();
        var request=new ObjectMapper().createObjectNode().put("jobId","801").put("attempt",1);
        var result=client().synthesizeGeo(request);
        assertThat(result.available()).isFalse(); assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(calls.get()).isEqualTo(1);
        request.put("evidence","x".repeat(900*1024));
        assertThat(client().synthesizeGeo(request).reason()).isEqualTo(PythonCallResult.Reason.INVALID_REQUEST);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void typedGetResponsesAndSignedPostBodyPreserveTheTrace() {
        AtomicReference<com.sun.net.httpserver.Headers> capabilityHeaders = new AtomicReference<>();
        AtomicReference<com.sun.net.httpserver.Headers> postHeaders = new AtomicReference<>();
        AtomicReference<byte[]> postBody = new AtomicReference<>();
        server.createContext("/internal/v1/health/ready", exchange -> json(
                exchange,
                200,
                "{\"status\":\"READY\",\"service\":\"guanghe-agent\","
                        + "\"serviceVersion\":\"1.0.0\",\"checks\":[]}"
        ));
        server.createContext("/internal/v1/capabilities", exchange -> {
            capabilityHeaders.set(exchange.getRequestHeaders());
            json(exchange, 200, "{\"serviceVersion\":\"1.0.0\",\"bundleVersion\":\"v1\","
                    + "\"bundleHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                    + "\"promptHash\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\","
                    + "\"gitCommit\":null,\"modelGateway\":{\"mode\":\"MOCK\",\"provider\":null,"
                    + "\"model\":null,\"status\":\"AVAILABLE\",\"reasonCode\":null},"
                    + "\"supportedProfiles\":[],\"providers\":[]}");
        });
        server.createContext("/internal/v1/test/java-reverse-call", exchange -> {
            postHeaders.set(exchange.getRequestHeaders());
            postBody.set(exchange.getRequestBody().readAllBytes());
            json(exchange, 200, "{\"accepted\":true,\"callerKeyId\":\"agent-service-v1\","
                    + "\"traceId\":\"0123456789abcdef0123456789abcdef\"}");
        });
        server.start();
        MDC.put(TraceIdHolder.MDC_KEY, "0123456789abcdef0123456789abcdef");
        PythonInternalClient client = client();

        PythonCallResult<PythonReadinessResponse> readiness = client.readiness();
        PythonCallResult<PythonCapabilitiesResponse> capabilities = client.capabilities();
        PythonCallResult<PythonReverseTraceProbeResponse> reverse = client.reverseTraceProbe(
                new PythonReverseTraceProbeRequest("java-m1-contract-gate")
        );

        assertThat(readiness.payload().status()).isEqualTo(PythonReadinessResponse.Status.READY);
        assertThat(capabilities.payload().modelGateway().status())
                .isEqualTo(PythonCapabilitiesResponse.ProviderStatus.AVAILABLE);
        assertThat(reverse.payload().accepted()).isTrue();
        assertThat(reverse.payload().traceId()).isEqualTo("0123456789abcdef0123456789abcdef");

        var headers = capabilityHeaders.get();
        String timestamp = headers.getFirst(InternalHmacVerificationFilter.TIMESTAMP_HEADER);
        String nonce = headers.getFirst(InternalHmacVerificationFilter.NONCE_HEADER);
        InternalHmacSigner.SignedContent expected = InternalHmacSigner.sign(
                timestamp,
                nonce,
                "GET",
                "/internal/v1/capabilities",
                new byte[0],
                SECRET_TEXT.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(headers.getFirst(InternalHmacVerificationFilter.KEY_ID_HEADER)).isEqualTo("agent-service-v1");
        assertThat(headers.getFirst(InternalHmacVerificationFilter.CONTENT_SHA256_HEADER))
                .isEqualTo(expected.bodySha256());
        assertThat(headers.getFirst(InternalHmacVerificationFilter.SIGNATURE_HEADER))
                .isEqualTo(expected.signature());
        assertThat(headers.getFirst("traceparent"))
                .matches("00-0123456789abcdef0123456789abcdef-[0-9a-f]{16}-01");

        byte[] exactPostBody = postBody.get();
        assertThat(new String(exactPostBody, StandardCharsets.UTF_8))
                .isEqualTo("{\"probe\":\"java-m1-contract-gate\"}");
        assertThat(postHeaders.get().getFirst("Content-Type")).startsWith("application/json");
        assertThat(postHeaders.get().getFirst("Upgrade")).isNull();
        assertThat(postHeaders.get().getFirst("HTTP2-Settings")).isNull();
        assertSigned(
                postHeaders.get(),
                "POST",
                "/internal/v1/test/java-reverse-call",
                exactPostBody
        );
    }

    @Test
    void rawJsonProbeSignsAndSendsTheExactCallerSuppliedBytesWithoutReserializing() {
        AtomicReference<com.sun.net.httpserver.Headers> postHeaders = new AtomicReference<>();
        AtomicReference<byte[]> postBody = new AtomicReference<>();
        server.createContext("/internal/v1/test/java-reverse-call", exchange -> {
            postHeaders.set(exchange.getRequestHeaders());
            postBody.set(exchange.getRequestBody().readAllBytes());
            json(exchange, 200, "{\"accepted\":true,\"callerKeyId\":\"agent-service-v1\","
                    + "\"traceId\":\"0123456789abcdef0123456789abcdef\"}");
        });
        server.start();
        MDC.put(TraceIdHolder.MDC_KEY, "0123456789abcdef0123456789abcdef");
        byte[] exactBody = "{\n  \"probe\" : \"raw-json-contract\"\n}\n"
                .getBytes(StandardCharsets.UTF_8);

        PythonCallResult<PythonReverseTraceProbeResponse> result = client()
                .reverseTraceProbeRawJson(exactBody);

        assertThat(result.state()).isEqualTo(PythonCallResult.State.AVAILABLE);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(postBody.get()).containsExactly(exactBody);
        assertSigned(
                postHeaders.get(),
                "POST",
                "/internal/v1/test/java-reverse-call",
                exactBody
        );
    }

    @Test
    void rawJsonProbeRejectsNonContractAndOversizedBodiesBeforeOpeningTheConnection() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/internal/v1/test/java-reverse-call", exchange -> {
            requests.incrementAndGet();
            json(exchange, 500, "{}");
        });
        properties.setMaxBodyBytes(32);
        server.start();
        PythonInternalClient client = client();

        List<PythonCallResult<PythonReverseTraceProbeResponse>> rejected = List.of(
                client.reverseTraceProbeRawJson("[]".getBytes(StandardCharsets.UTF_8)),
                client.reverseTraceProbeRawJson("null".getBytes(StandardCharsets.UTF_8)),
                client.reverseTraceProbeRawJson(
                        "{\"probe\":123}".getBytes(StandardCharsets.UTF_8)
                ),
                client.reverseTraceProbeRawJson(
                        "{\"probe\":\"a\",\"probe\":\"b\"}".getBytes(StandardCharsets.UTF_8)
                ),
                client.reverseTraceProbeRawJson(
                        "{\"probe\":\"ok\",\"unexpected\":true}".getBytes(StandardCharsets.UTF_8)
                ),
                client.reverseTraceProbeRawJson(
                        "{\"probe\":\"0123456789012345678901234567890123456789\"}"
                                .getBytes(StandardCharsets.UTF_8)
                )
        );

        assertThat(rejected)
                .allSatisfy(result -> {
                    assertThat(result.state()).isEqualTo(PythonCallResult.State.UNAVAILABLE);
                    assertThat(result.reason()).isEqualTo(PythonCallResult.Reason.INVALID_REQUEST);
                });
        assertThat(requests).hasValue(0);
    }

    @Test
    void unavailableAndTimeoutAreReturnedAsStateInsteadOfThrown() {
        server.createContext("/internal/v1/health/ready", exchange -> {
            try {
                Thread.sleep(500);
                json(exchange, 200, "{\"status\":\"UP\"}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        properties.getPython().setReadTimeout(Duration.ofMillis(50));
        server.start();

        PythonCallResult<PythonReadinessResponse> timeout = client().readiness();
        assertThat(timeout.state()).isEqualTo(PythonCallResult.State.UNAVAILABLE);
        assertThat(timeout.reason()).isEqualTo(PythonCallResult.Reason.TIMEOUT);

        server.stop(0);
        server = null;
        PythonCallResult<PythonReadinessResponse> unavailable = client().readiness();
        assertThat(unavailable.state()).isEqualTo(PythonCallResult.State.UNAVAILABLE);
        assertThat(unavailable.reason()).isEqualTo(PythonCallResult.Reason.CONNECTION_FAILED);
    }

    @Test
    void startResourceAccessFailureIsClassifiedWithoutLeakingExceptionOrRetrying() {
        assertSafeStartFailure(new ResourceAccessException(sensitiveDiagnosticFixture(),
                        new IOException(sensitiveDiagnosticFixture())),
                PythonCallResult.Reason.CONNECTION_FAILED, "RESOURCE_ACCESS");
    }

    @Test
    void startTimeoutFailureRetainsTheExistingTimeoutReasonWithoutRetrying() {
        assertSafeStartFailure(new ResourceAccessException(sensitiveDiagnosticFixture(),
                        new HttpTimeoutException(sensitiveDiagnosticFixture())),
                PythonCallResult.Reason.TIMEOUT, "TRANSPORT_TIMEOUT");
    }

    @Test
    void startRuntimeFailureIsDistinctInDiagnosticsButKeepsThePublicReason() {
        assertSafeStartFailure(new IllegalStateException(sensitiveDiagnosticFixture()),
                PythonCallResult.Reason.CONNECTION_FAILED, "CLIENT_RUNTIME");
    }

    @Test
    void startHttpValidationFailureRetainsStatusWithoutLoggingBodyHeadersOrRetrying() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://offline.invalid");
        MockRestServiceServer transport = MockRestServiceServer.bindTo(builder).build();
        transport.expect(requestTo("http://offline.invalid/internal/v1/chat-runs"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Private-Fixture", sensitiveDiagnosticFixture())
                        .body(sensitiveDiagnosticFixture()));

        PythonCallResult<PythonChatRunAccepted> result = diagnosticClient(builder.build())
                .startChatRun(diagnosticRequest());

        assertThat(result.reason()).isEqualTo(PythonCallResult.Reason.HTTP_ERROR);
        assertThat(result.httpStatus()).isEqualTo(422);
        assertSafeDiagnostic("HTTP_RESPONSE", "HTTP_ERROR", "422");
        transport.verify();
    }

    @Test
    void malformedAcceptedResponseLogsObservedStatusWithoutChangingPublicMapping() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://offline.invalid");
        MockRestServiceServer transport = MockRestServiceServer.bindTo(builder).build();
        transport.expect(requestTo("http://offline.invalid/internal/v1/chat-runs"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON).body(sensitiveDiagnosticFixture()));

        PythonCallResult<PythonChatRunAccepted> result = diagnosticClient(builder.build())
                .startChatRun(diagnosticRequest());

        assertThat(result.reason()).isEqualTo(PythonCallResult.Reason.INVALID_RESPONSE);
        // Retain the pre-existing outward mapping; the diagnostic alone has the observed 202.
        assertThat(result.httpStatus()).isEqualTo(200);
        assertSafeDiagnostic("RESPONSE_IO", "INVALID_RESPONSE", "202");
        transport.verify();
    }

    private void assertSafeStartFailure(RuntimeException failure, PythonCallResult.Reason reason,
                                        String category) {
        AtomicInteger attempts = new AtomicInteger();
        RestClient transport = RestClient.builder().baseUrl("http://offline.invalid")
                .requestFactory((uri, method) -> {
                    attempts.incrementAndGet();
                    throw failure;
                }).build();

        PythonCallResult<PythonChatRunAccepted> result = diagnosticClient(transport)
                .startChatRun(diagnosticRequest());

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.httpStatus()).isNull();
        assertThat(attempts).hasValue(1);
        assertSafeDiagnostic(category, reason.name(), "NONE");
    }

    private void assertSafeDiagnostic(String category, String reason, String status) {
        assertThat(diagnostics.list).hasSize(1);
        ILoggingEvent event = diagnostics.list.getFirst();
        assertThat(event.getFormattedMessage()).isEqualTo(
                "python_internal_call_unavailable endpoint=CHAT_RUN_START failureCategory="
                        + category + " reason=" + reason + " httpStatus=" + status);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getArgumentArray()).allSatisfy(value -> {
            assertThat(value).isNotInstanceOf(Throwable.class);
            assertThat(String.valueOf(value)).doesNotContain("private-fixture", "offline.invalid",
                    "Authorization", "Bearer", "X-Private-Fixture", "context-reference");
        });
    }

    private PythonInternalClient diagnosticClient(RestClient transport) {
        return new PythonInternalClient(transport, new ObjectMapper().findAndRegisterModules(),
                new InternalHmacRequestSigner(properties, Clock.fixed(NOW, ZoneOffset.UTC)), 1_048_576);
    }

    private PythonChatRunRequest diagnosticRequest() {
        return new PythonChatRunRequest(UUID.fromString("4d8c03bf-5f5f-44bf-954b-313bc97cc7e1"),
                "1900012345678901000", "context-reference-private-fixture",
                new PythonChatRunRequest.Message("1900012345678901001", sensitiveDiagnosticFixture(),
                        null, List.of()),
                new PythonChatRunRequest.TrustedState(null, null, null, null, null),
                new PythonChatRunRequest.Budget(4, 6, 12, 1, 0, 32000,
                        OffsetDateTime.parse("2026-09-03T05:02:00Z")));
    }

    private String sensitiveDiagnosticFixture() {
        return "private-fixture https://offline.invalid/private?token=private-fixture "
                + "Authorization: Bearer private-fixture body=private-fixture";
    }

    @Test
    void structurallyIncompleteStableResponseIsRejected() {
        server.createContext("/internal/v1/health/ready", exchange -> json(
                exchange,
                200,
                "{\"status\":\"READY\"}"
        ));
        server.start();

        PythonCallResult<PythonReadinessResponse> result = client().readiness();

        assertThat(result.state()).isEqualTo(PythonCallResult.State.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(PythonCallResult.Reason.INVALID_RESPONSE);
        assertThat(result.payload()).isNull();
    }

    @Test
    void capabilityBundleVersionOutsideThePersistedContractIsRejected() {
        server.createContext("/internal/v1/capabilities", exchange -> json(
                exchange,
                200,
                "{\"serviceVersion\":\"1.0.0\",\"bundleVersion\":\"bad bundle\","
                        + "\"bundleHash\":\"" + "a".repeat(64) + "\","
                        + "\"promptHash\":\"" + "b".repeat(64) + "\",\"gitCommit\":null,"
                        + "\"modelGateway\":{\"mode\":\"MOCK\",\"provider\":null,"
                        + "\"model\":null,\"status\":\"AVAILABLE\",\"reasonCode\":null},"
                        + "\"supportedProfiles\":[],\"providers\":[]}"
        ));
        server.start();

        PythonCallResult<PythonCapabilitiesResponse> result = client().capabilities();

        assertThat(result.state()).isEqualTo(PythonCallResult.State.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(PythonCallResult.Reason.INVALID_RESPONSE);
        assertThat(result.payload()).isNull();
    }

    @Test
    void chatRunStartStreamAndStopReuseHmacAndValidateSseEnvelope() throws Exception {
        UUID runId = UUID.fromString("4d8c03bf-5f5f-44bf-954b-313bc97cc7e1");
        AtomicReference<com.sun.net.httpserver.Headers> startHeaders = new AtomicReference<>();
        AtomicReference<byte[]> startBody = new AtomicReference<>();
        AtomicReference<com.sun.net.httpserver.Headers> streamHeaders = new AtomicReference<>();
        AtomicReference<com.sun.net.httpserver.Headers> stopHeaders = new AtomicReference<>();
        String runPath = "/internal/v1/chat-runs/" + runId;
        server.createContext("/internal/v1/chat-runs", exchange -> {
            startHeaders.set(exchange.getRequestHeaders());
            startBody.set(exchange.getRequestBody().readAllBytes());
            json(exchange, 202, "{\"runId\":\"" + runId + "\",\"status\":\"QUEUED\","
                    + "\"eventsUrl\":\"" + runPath + "/events\"}");
        });
        server.createContext(runPath + "/events", exchange -> {
            streamHeaders.set(exchange.getRequestHeaders());
            String body = "event: run.started\n"
                    + "id: 1\n"
                    + "data: {\"schemaVersion\":1,\"eventId\":\""
                    + "11111111-1111-4111-8111-111111111111\",\"eventType\":\"run.started\","
                    + "\"runId\":\"" + runId + "\",\"occurredAt\":\"2026-09-03T05:00:00Z\","
                    + "\"sequence\":1,\"payload\":{\"status\":\"RUNNING\"}}\n\n"
                    + "event: run.progress\n"
                    + "id: 2\n"
                    + "data: {\"schemaVersion\":1,\"eventId\":\""
                    + "33333333-3333-4333-8333-333333333333\",\"eventType\":\"run.progress\","
                    + "\"runId\":\"" + runId + "\",\"occurredAt\":\"2026-09-03T05:00:00Z\","
                    + "\"sequence\":2,\"payload\":{\"stageCode\":\"UNDERSTANDING_REQUEST\","
                    + "\"status\":\"RUNNING\",\"ordinal\":1,\"elapsedMs\":5}}\n\n"
                    + "event: message.delta\n"
                    + "id: 3\n"
                    + "data: {\"schemaVersion\":1,\"eventId\":\""
                    + "22222222-2222-4222-8222-222222222222\",\"eventType\":\"message.delta\","
                    + "\"runId\":\"" + runId + "\",\"occurredAt\":\"2026-09-03T05:00:01Z\","
                    + "\"sequence\":3,\"payload\":{\"delta\":\"你好\"}}\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext(runPath + "/stop", exchange -> {
            stopHeaders.set(exchange.getRequestHeaders());
            json(exchange, 202, "{\"runId\":\"" + runId
                    + "\",\"accepted\":true,\"status\":\"STOPPED\"}");
        });
        server.start();
        MDC.put(TraceIdHolder.MDC_KEY, "0123456789abcdef0123456789abcdef");
        PythonInternalClient client = client();
        PythonChatRunRequest request = new PythonChatRunRequest(
                runId, "1900012345678901000", "opaque-context-reference",
                new PythonChatRunRequest.Message("1900012345678901001", "你好", null, List.of()),
                new PythonChatRunRequest.TrustedState(null, null, null, null, null),
                new PythonChatRunRequest.Budget(
                        4, 6, 12, 1, 0, 32000, OffsetDateTime.parse("2026-09-03T05:02:00Z")
                )
        );
        List<PythonChatRunEvent> events = new ArrayList<>();

        PythonCallResult<PythonChatRunAccepted> started = client.startChatRun(request);
        PythonCallResult<Void> streamed = client.streamChatRunEvents(runId, events::add);
        PythonCallResult<PythonStopRunResponse> stopped = client.stopChatRun(runId);

        assertThat(started.available()).isTrue();
        assertThat(streamed.available()).isTrue();
        assertThat(stopped.available()).isTrue();
        assertThat(events).extracting(PythonChatRunEvent::eventType)
                .containsExactly("run.started", "run.progress", "message.delta");
        assertThat(events).extracting(PythonChatRunEvent::sequence).containsExactly(1L, 2L, 3L);
        assertThat(startBody.get()).isNotEmpty();
        JsonNode serializedBudget = new ObjectMapper().readTree(startBody.get()).path("budget");
        assertThat(serializedBudget.size()).isEqualTo(7);
        assertThat(serializedBudget.path("maxModelCalls").intValue()).isEqualTo(4);
        assertThat(serializedBudget.path("maxToolCalls").intValue()).isEqualTo(6);
        assertThat(serializedBudget.path("maxSteps").intValue()).isEqualTo(12);
        assertThat(serializedBudget.path("maxContextLoads").intValue()).isEqualTo(1);
        assertThat(serializedBudget.path("maxProviderCalls").intValue()).isZero();
        assertThat(serializedBudget.path("maxInputTokens").intValue()).isEqualTo(32000);
        assertThat(serializedBudget.path("deadlineAt").asText()).isEqualTo("2026-09-03T05:02:00Z");
        assertSigned(startHeaders.get(), "POST", "/internal/v1/chat-runs", startBody.get());
        assertSigned(streamHeaders.get(), "GET", runPath + "/events", new byte[0]);
        assertSigned(stopHeaders.get(), "POST", runPath + "/stop", new byte[0]);
    }

    private PythonInternalClient client() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        InternalHmacRequestSigner signer = new InternalHmacRequestSigner(
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new PythonInternalClient(properties, objectMapper, signer);
    }

    private void assertSigned(
            com.sun.net.httpserver.Headers headers,
            String method,
            String rawPathWithQuery,
            byte[] body
    ) {
        for (String header : List.of(
                InternalHmacVerificationFilter.KEY_ID_HEADER,
                InternalHmacVerificationFilter.TIMESTAMP_HEADER,
                InternalHmacVerificationFilter.NONCE_HEADER,
                InternalHmacVerificationFilter.CONTENT_SHA256_HEADER,
                InternalHmacVerificationFilter.SIGNATURE_HEADER,
                "traceparent"
        )) {
            assertThat(headers.get(header)).as(header).hasSize(1);
        }
        String timestamp = headers.getFirst(InternalHmacVerificationFilter.TIMESTAMP_HEADER);
        String nonce = headers.getFirst(InternalHmacVerificationFilter.NONCE_HEADER);
        InternalHmacSigner.SignedContent expected = InternalHmacSigner.sign(
                timestamp,
                nonce,
                method,
                rawPathWithQuery,
                body,
                SECRET_TEXT.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(headers.getFirst(InternalHmacVerificationFilter.CONTENT_SHA256_HEADER))
                .isEqualTo(expected.bodySha256());
        assertThat(headers.getFirst(InternalHmacVerificationFilter.SIGNATURE_HEADER))
                .isEqualTo(expected.signature());
        assertThat(headers.getFirst("traceparent"))
                .matches("00-0123456789abcdef0123456789abcdef-[0-9a-f]{16}-01");
    }

    private void json(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
