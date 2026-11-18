package com.guanghe.backend.integration.internal.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import com.guanghe.backend.integration.internal.security.InternalHmacVerificationFilter;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(name = "zhixiaohe.internal.enabled", havingValue = "true", matchIfMissing = true)
public class PythonInternalClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonInternalClient.class);
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final Set<String> CHAT_EVENT_TYPES = Set.of(
            "run.started", "run.progress", "message.delta", "interaction.required", "task-draft.proposed",
            "run.completed", "run.failed", "usage.reported", "heartbeat"
    );

    private final RestClient restClient;
    private final RestClient streamRestClient;
    private final RestClient planningRestClient;
    private final RestClient synthesisRestClient;
    private final RestClient diagnosticAnalysisRestClient;
    static final Duration SYNTHESIS_READ_TIMEOUT = Duration.ofSeconds(70);
    static final Duration DIAGNOSTIC_ANALYSIS_READ_TIMEOUT = Duration.ofSeconds(130);
    private final ObjectMapper objectMapper;
    private final ObjectReader strictReverseTraceProbeReader;
    private final ObjectReader strictChatRunEventReader;
    private final InternalHmacRequestSigner requestSigner;
    private final int maxRequestBytes;
    private final int maxResponseBytes;

    @Autowired
    public PythonInternalClient(
            InternalCommunicationProperties properties,
            ObjectMapper objectMapper,
            InternalHmacRequestSigner requestSigner
    ) {
        this(
                createRestClient(properties, properties.getPython().getReadTimeout()),
                createRestClient(properties, properties.getPython().getStreamReadTimeout()),
                createRestClient(properties, Duration.ofSeconds(25)),
                createRestClient(properties, SYNTHESIS_READ_TIMEOUT),
                createRestClient(properties, DIAGNOSTIC_ANALYSIS_READ_TIMEOUT),
                objectMapper,
                requestSigner,
                properties.getMaxBodyBytes(),
                properties.getPython().getMaxResponseBytes()
        );
    }

    PythonInternalClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            InternalHmacRequestSigner requestSigner,
            int maxResponseBytes
    ) {
        this(restClient, restClient, restClient, objectMapper, requestSigner, 1_048_576, maxResponseBytes);
    }

    PythonInternalClient(
            RestClient restClient,
            RestClient streamRestClient,
            RestClient planningRestClient,
            ObjectMapper objectMapper,
            InternalHmacRequestSigner requestSigner,
            int maxRequestBytes,
            int maxResponseBytes
    ) {
        this(restClient, streamRestClient, planningRestClient, planningRestClient, objectMapper,
                requestSigner, maxRequestBytes, maxResponseBytes);
    }

    PythonInternalClient(RestClient restClient, RestClient streamRestClient, RestClient planningRestClient,
            RestClient synthesisRestClient, ObjectMapper objectMapper,
            InternalHmacRequestSigner requestSigner, int maxRequestBytes, int maxResponseBytes) {
        this(restClient, streamRestClient, planningRestClient, synthesisRestClient, synthesisRestClient,
                objectMapper, requestSigner, maxRequestBytes, maxResponseBytes);
    }

    PythonInternalClient(RestClient restClient, RestClient streamRestClient, RestClient planningRestClient,
            RestClient synthesisRestClient, RestClient diagnosticAnalysisRestClient, ObjectMapper objectMapper,
            InternalHmacRequestSigner requestSigner, int maxRequestBytes, int maxResponseBytes) {
        this.restClient = restClient;
        this.streamRestClient = streamRestClient;
        this.planningRestClient = planningRestClient;
        this.synthesisRestClient = synthesisRestClient;
        this.diagnosticAnalysisRestClient = diagnosticAnalysisRestClient;
        this.objectMapper = objectMapper;
        ObjectMapper strictRequestMapper = objectMapper.copy();
        strictRequestMapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        strictRequestMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        this.strictReverseTraceProbeReader = strictRequestMapper
                .readerFor(PythonReverseTraceProbeRequest.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.strictChatRunEventReader = strictRequestMapper
                .readerFor(PythonChatRunEvent.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.requestSigner = requestSigner;
        this.maxRequestBytes = maxRequestBytes;
        this.maxResponseBytes = maxResponseBytes;
    }

    PythonInternalClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            InternalHmacRequestSigner requestSigner,
            int maxRequestBytes,
            int maxResponseBytes
    ) {
        this(restClient, restClient, restClient, objectMapper, requestSigner, maxRequestBytes, maxResponseBytes);
    }

    /** Explicit user action only: a short, signed text-model planning call, never image/video execution. */
    public PythonCallResult<JsonNode> planGeoVisual(ObjectNode request) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            if (body.length > maxRequestBytes) {
                return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
            }
            return exchange(planningRestClient, HttpMethod.POST,
                    "/internal/v1/creative/geo-plans", body, true, JsonNode.class);
        } catch (IOException exception) {
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
        }
    }

    /** Called only by the durable report-recovery worker; no HTTP or model retry is hidden here. */
    public PythonCallResult<JsonNode> synthesizeGeo(ObjectNode request) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            if (body.length > Math.min(maxRequestBytes, 900 * 1024))
                return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
            return exchange(synthesisRestClient, HttpMethod.POST,
                    "/internal/v1/geo/syntheses", body, true, JsonNode.class);
        } catch (IOException exception) {
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
        }
    }

    /** Explicit project-report job only; signed narrative generation, with no hidden HTTP retry. */
    public PythonCallResult<JsonNode> summarizeGeoPeriodReport(ObjectNode request) {
        if (request == null) {
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            if (body.length > Math.min(maxRequestBytes, 64 * 1024)) {
                return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
            }
            return exchange(synthesisRestClient, HttpMethod.POST,
                    "/internal/v1/geo/period-report-summaries", body, true, JsonNode.class);
        } catch (IOException exception) {
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
        }
    }

    /** Durable diagnostic preparation; signed, bounded, and never a monitoring or billing call. */
    public PythonCallResult<JsonNode> prepareGeoDiagnostic(ObjectNode request) {
        if (request == null) return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            if (body.length > Math.min(maxRequestBytes, 256 * 1024))
                return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
            return exchange(synthesisRestClient, HttpMethod.POST,
                    "/internal/v1/geo/diagnostic-preparations", body, true, JsonNode.class);
        } catch (IOException invalid) {
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
        }
    }

    /** Analysis recovery never recreates a GEO execution or retries a paid sample. */
    public PythonCallResult<JsonNode> analyzeGeoDiagnostic(ObjectNode request) {
        if(request==null)return PythonCallResult.unavailable(null,PythonCallResult.Reason.INVALID_REQUEST);
        try {
            byte[] body=objectMapper.writeValueAsBytes(request);
            if(body.length>Math.min(maxRequestBytes,256*1024))return PythonCallResult.unavailable(null,PythonCallResult.Reason.INVALID_REQUEST);
            return exchange(diagnosticAnalysisRestClient,HttpMethod.POST,"/internal/v1/geo/diagnostic-analyses",body,true,JsonNode.class);
        } catch(IOException invalid){return PythonCallResult.unavailable(null,PythonCallResult.Reason.INVALID_REQUEST);}
    }

    public PythonCallResult<PythonReadinessResponse> readiness() {
        return exchange(
                HttpMethod.GET,
                "/internal/v1/health/ready",
                EMPTY_BODY,
                false,
                PythonReadinessResponse.class
        );
    }

    public PythonCallResult<PythonCapabilitiesResponse> capabilities() {
        return exchange(
                HttpMethod.GET,
                "/internal/v1/capabilities",
                EMPTY_BODY,
                true,
                PythonCapabilitiesResponse.class
        );
    }

    public PythonCallResult<PythonReverseTraceProbeResponse> reverseTraceProbe(
            PythonReverseTraceProbeRequest request
    ) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            return exchange(
                    HttpMethod.POST,
                    "/internal/v1/test/java-reverse-call",
                    body,
                    true,
                    PythonReverseTraceProbeResponse.class
            );
        } catch (IOException exception) {
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_RESPONSE);
        }
    }

    public PythonCallResult<PythonChatRunAccepted> startChatRun(PythonChatRunRequest request) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            if (body.length > maxRequestBytes) {
                return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
            }
            PythonCallResult<PythonChatRunAccepted> result = exchange(
                    HttpMethod.POST,
                    "/internal/v1/chat-runs",
                    body,
                    true,
                    PythonChatRunAccepted.class
            );
            if (!result.available() || result.httpStatus() != 202 || result.payload() == null
                    || !request.runId().equals(result.payload().runId())
                    || result.payload().eventsUrl() == null
                    || !result.payload().eventsUrl().equals(
                            "/internal/v1/chat-runs/" + request.runId() + "/events"
                    )
                    || !("QUEUED".equals(result.payload().status()) || "RUNNING".equals(result.payload().status()))) {
                return result.available()
                        ? PythonCallResult.unavailable(result.httpStatus(), PythonCallResult.Reason.INVALID_RESPONSE)
                        : result;
            }
            return result;
        } catch (IOException exception) {
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
        }
    }

    public PythonCallResult<Void> streamChatRunEvents(
            UUID runId,
            Consumer<PythonChatRunEvent> eventConsumer
    ) {
        String path = "/internal/v1/chat-runs/" + runId + "/events";
        InternalHmacRequestHeaders signed = requestSigner.sign(HttpMethod.GET.name(), path, EMPTY_BODY);
        try {
            return streamRestClient.get()
                    .uri(path)
                    .headers(headers -> {
                        headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));
                        applySignedHeaders(headers, signed);
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return PythonCallResult.unavailable(status, PythonCallResult.Reason.HTTP_ERROR);
                        }
                        MediaType contentType = response.getHeaders().getContentType();
                        if (contentType == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
                            return PythonCallResult.unavailable(status, PythonCallResult.Reason.INVALID_RESPONSE);
                        }
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                response.getBody(), StandardCharsets.UTF_8
                        ))) {
                            parseEventStream(reader, runId, eventConsumer);
                            return PythonCallResult.available(status, null);
                        } catch (IOException | RuntimeException exception) {
                            return PythonCallResult.unavailable(status, PythonCallResult.Reason.INVALID_RESPONSE);
                        }
                    });
        } catch (ResourceAccessException exception) {
            if (hasTimeoutCause(exception)) {
                return PythonCallResult.unavailable(null, PythonCallResult.Reason.TIMEOUT);
            }
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.CONNECTION_FAILED);
        } catch (RuntimeException exception) {
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.CONNECTION_FAILED);
        }
    }

    public PythonCallResult<PythonStopRunResponse> stopChatRun(UUID runId) {
        PythonCallResult<PythonStopRunResponse> result = exchange(
                HttpMethod.POST,
                "/internal/v1/chat-runs/" + runId + "/stop",
                EMPTY_BODY,
                true,
                PythonStopRunResponse.class
        );
        if (!result.available() || result.httpStatus() != 202 || result.payload() == null
                || !runId.equals(result.payload().runId())) {
            return result.available()
                    ? PythonCallResult.unavailable(result.httpStatus(), PythonCallResult.Reason.INVALID_RESPONSE)
                    : result;
        }
        return result;
    }

    /**
     * Send caller-supplied JSON bytes without parsing and serializing them again.
     * The bytes are still constrained to the frozen reverse-probe DTO before
     * they are signed and sent.
     */
    public PythonCallResult<PythonReverseTraceProbeResponse> reverseTraceProbeRawJson(byte[] rawJson) {
        if (rawJson == null || rawJson.length == 0 || rawJson.length > maxRequestBytes) {
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
        }
        byte[] exactBody = Arrays.copyOf(rawJson, rawJson.length);
        try {
            if (strictReverseTraceProbeReader.readValue(exactBody) == null) {
                return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
            }
        } catch (IOException | RuntimeException exception) {
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.INVALID_REQUEST);
        }
        return exchange(
                HttpMethod.POST,
                "/internal/v1/test/java-reverse-call",
                exactBody,
                true,
                PythonReverseTraceProbeResponse.class
        );
    }

    private <T> PythonCallResult<T> exchange(
            HttpMethod method,
            String rawPathWithQuery,
            byte[] body,
            boolean signed,
            Class<T> responseType
    ) {
        return exchange(restClient, method, rawPathWithQuery, body, signed, responseType);
    }

    private <T> PythonCallResult<T> exchange(
            RestClient client, HttpMethod method, String rawPathWithQuery, byte[] body,
            boolean signed, Class<T> responseType
    ) {
        Integer observedStatus = null;
        try {
            RestClient.RequestBodySpec request = client.method(method)
                    .uri(rawPathWithQuery)
                    .headers(headers -> {
                        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
                        if (signed) {
                            applySignedHeaders(headers, requestSigner.sign(method.name(), rawPathWithQuery, body));
                        }
                    });
            if (body.length > 0) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            ResponseEntity<byte[]> response = request.retrieve()
                    .toEntity(byte[].class);
            int httpStatus = response.getStatusCode().value();
            observedStatus = httpStatus;
            byte[] safeResponse = response.getBody() == null ? EMPTY_BODY : response.getBody();
            if (safeResponse.length > maxResponseBytes) {
                logUnavailable(rawPathWithQuery, FailureCategory.INVALID_RESPONSE,
                        PythonCallResult.Reason.INVALID_RESPONSE, httpStatus);
                return PythonCallResult.unavailable(httpStatus, PythonCallResult.Reason.INVALID_RESPONSE);
            }
            if (safeResponse.length == 0) {
                logUnavailable(rawPathWithQuery, FailureCategory.INVALID_RESPONSE,
                        PythonCallResult.Reason.INVALID_RESPONSE, httpStatus);
                return PythonCallResult.unavailable(httpStatus, PythonCallResult.Reason.INVALID_RESPONSE);
            }
            T payload = objectMapper.readValue(safeResponse, responseType);
            return PythonCallResult.available(httpStatus, payload);
        } catch (RestClientResponseException exception) {
            logUnavailable(rawPathWithQuery, FailureCategory.HTTP_RESPONSE,
                    PythonCallResult.Reason.HTTP_ERROR, exception.getStatusCode().value());
            return PythonCallResult.unavailable(exception.getStatusCode().value(), PythonCallResult.Reason.HTTP_ERROR);
        } catch (ResourceAccessException exception) {
            if (hasTimeoutCause(exception)) {
                logUnavailable(rawPathWithQuery, FailureCategory.TRANSPORT_TIMEOUT,
                        PythonCallResult.Reason.TIMEOUT, observedStatus);
                return PythonCallResult.unavailable(null, PythonCallResult.Reason.TIMEOUT);
            }
            logUnavailable(rawPathWithQuery, FailureCategory.RESOURCE_ACCESS,
                    PythonCallResult.Reason.CONNECTION_FAILED, observedStatus);
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.CONNECTION_FAILED);
        } catch (IOException exception) {
            logUnavailable(rawPathWithQuery, FailureCategory.RESPONSE_IO,
                    PythonCallResult.Reason.INVALID_RESPONSE, observedStatus);
            return PythonCallResult.unavailable(200, PythonCallResult.Reason.INVALID_RESPONSE);
        } catch (RuntimeException exception) {
            logUnavailable(rawPathWithQuery, FailureCategory.CLIENT_RUNTIME,
                    PythonCallResult.Reason.CONNECTION_FAILED, observedStatus);
            return PythonCallResult.unavailable(null, PythonCallResult.Reason.CONNECTION_FAILED);
        }
    }

    private void logUnavailable(String path, FailureCategory category,
                                PythonCallResult.Reason reason, Integer httpStatus) {
        // The caller's trace is already carried by MDC. Never pass the exception, its message,
        // request/response data, or the dynamic URL to the logger; classification is diagnostic only.
        LOGGER.warn("python_internal_call_unavailable endpoint={} failureCategory={} reason={} httpStatus={}",
                EndpointCategory.from(path), category, reason, httpStatus == null ? "NONE" : httpStatus);
    }

    private enum FailureCategory {
        HTTP_RESPONSE, TRANSPORT_TIMEOUT, RESOURCE_ACCESS, RESPONSE_IO, CLIENT_RUNTIME, INVALID_RESPONSE
    }

    private enum EndpointCategory {
        CHAT_RUN_START, CHAT_RUN_STOP, READINESS, CAPABILITIES, REVERSE_TRACE_PROBE,
        GEO_VISUAL_PLAN, GEO_SYNTHESIS, GEO_PERIOD_REPORT_SUMMARY, GEO_DIAGNOSTIC_PREPARATION, OTHER;

        private static EndpointCategory from(String path) {
            return switch (path) {
                case "/internal/v1/chat-runs" -> CHAT_RUN_START;
                case "/internal/v1/health/ready" -> READINESS;
                case "/internal/v1/capabilities" -> CAPABILITIES;
                case "/internal/v1/test/java-reverse-call" -> REVERSE_TRACE_PROBE;
                case "/internal/v1/creative/geo-plans" -> GEO_VISUAL_PLAN;
                case "/internal/v1/geo/syntheses" -> GEO_SYNTHESIS;
                case "/internal/v1/geo/period-report-summaries" -> GEO_PERIOD_REPORT_SUMMARY;
                case "/internal/v1/geo/diagnostic-preparations" -> GEO_DIAGNOSTIC_PREPARATION;
                default -> path.startsWith("/internal/v1/chat-runs/") && path.endsWith("/stop")
                        ? CHAT_RUN_STOP : OTHER;
            };
        }
    }

    private void applySignedHeaders(HttpHeaders headers, InternalHmacRequestHeaders signed) {
        headers.set(InternalHmacVerificationFilter.KEY_ID_HEADER, signed.keyId());
        headers.set(InternalHmacVerificationFilter.TIMESTAMP_HEADER, signed.timestamp());
        headers.set(InternalHmacVerificationFilter.NONCE_HEADER, signed.nonce());
        headers.set(InternalHmacVerificationFilter.CONTENT_SHA256_HEADER, signed.contentSha256());
        headers.set(InternalHmacVerificationFilter.SIGNATURE_HEADER, signed.signature());
        headers.set("traceparent", signed.traceparent());
    }

    private void parseEventStream(
            BufferedReader reader,
            UUID expectedRunId,
            Consumer<PythonChatRunEvent> eventConsumer
    ) throws IOException {
        String eventName = null;
        String eventId = null;
        StringBuilder data = new StringBuilder();
        int consumedBytes = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            consumedBytes = Math.addExact(consumedBytes, line.getBytes(StandardCharsets.UTF_8).length + 1);
            if (consumedBytes > maxResponseBytes) {
                throw new IOException("Python event stream exceeds the configured limit");
            }
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    emitParsedEvent(expectedRunId, eventConsumer, eventName, eventId, data.toString());
                }
                eventName = null;
                eventId = null;
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                eventName = line.substring(6).trim();
            } else if (line.startsWith("id:")) {
                eventId = line.substring(3).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            }
        }
        if (data.length() > 0) {
            emitParsedEvent(expectedRunId, eventConsumer, eventName, eventId, data.toString());
        }
    }

    private void emitParsedEvent(
            UUID expectedRunId,
            Consumer<PythonChatRunEvent> eventConsumer,
            String eventName,
            String eventId,
            String data
    ) throws IOException {
        PythonChatRunEvent event = strictChatRunEventReader.readValue(data);
        if (event.schemaVersion() != 1
                || event.eventId() == null
                || event.eventType() == null
                || !CHAT_EVENT_TYPES.contains(event.eventType())
                || !event.eventType().equals(eventName)
                || event.runId() == null
                || !expectedRunId.equals(event.runId())
                || event.occurredAt() == null
                || event.sequence() < 1
                || !Long.toString(event.sequence()).equals(eventId)
                || event.payload() == null
                || !event.payload().isObject()) {
            throw new IOException("Invalid Python Chat Run event");
        }
        eventConsumer.accept(event);
    }

    private boolean hasTimeoutCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.net.http.HttpTimeoutException || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static RestClient createRestClient(
            InternalCommunicationProperties properties,
            Duration readTimeout
    ) {
        Duration connectTimeout = properties.getPython().getConnectTimeout();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // Uvicorn is HTTP/1.1-only in the test topology. JDK's default
                // cleartext HTTP/2 upgrade can make it parse signed body bytes
                // as a second protocol frame, so internal HMAC transport must
                // remain plain HTTP/1.1.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(properties.getPython().getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT_CHARSET, StandardCharsets.UTF_8.name())
                .build();
    }
}
