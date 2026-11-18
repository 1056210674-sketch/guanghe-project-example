package com.guanghe.backend.integration.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.web.TraceIdHolder;
import com.guanghe.backend.integration.internal.client.InternalHmacRequestHeaders;
import com.guanghe.backend.integration.internal.client.InternalHmacRequestSigner;
import com.guanghe.backend.integration.internal.client.PythonCallResult;
import com.guanghe.backend.integration.internal.client.PythonCapabilitiesResponse;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import com.guanghe.backend.integration.internal.client.PythonReadinessResponse;
import com.guanghe.backend.integration.internal.client.PythonReverseTraceProbeResponse;
import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import com.guanghe.backend.integration.internal.security.InternalHmacSigner;
import com.guanghe.backend.integration.internal.security.InternalHmacVerificationFilter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit live M1 Contract Gate A. This class has no conditional annotation:
 * once the m1-contract-remote-gate Maven profile is selected, missing
 * configuration, missing evidence, or unavailable services fail the build
 * instead of producing a skipped test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonContractRemoteGateIT {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final URI JAVA_BASE_URI = URI.create("http://127.0.0.1:8080");
    private static final String JAVA_SYSTEMD_UNIT = "zhixiaohe-java-test.service";
    private static final String PYTHON_SYSTEMD_UNIT = "zhixiaohe-agent-test.service";
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-f]{16}$");
    private final Map<String, String> privateEvidence = new ConcurrentHashMap<>();

    @AfterAll
    void persistPrivateEvidenceForTheGateRunner() throws IOException {
        writeTraceEvidence();
    }

    @AfterEach
    void clearTrace() {
        MDC.clear();
    }

    @Test
    void checkedOutPythonContractsAreLfNormalizedMatchesForTheFrozenJavaCopies() throws Exception {
        String expectedCommit = expectedAgentCommit();
        Path pythonContracts = Path.of(required("PYTHON_CONTRACT_ROOT")).toAbsolutePath().normalize();
        requireGate(Files.isDirectory(pythonContracts), "Python contracts checkout is missing");
        pythonContracts = pythonContracts.toRealPath();

        Path repositoryRoot = Path.of(runCommand(
                List.of("git", "-C", pythonContracts.toString(), "rev-parse", "--show-toplevel")
        )).toRealPath();
        assertThat(pythonContracts).isEqualTo(repositoryRoot.resolve("contracts").toRealPath());
        assertThat(runCommand(List.of("git", "-C", repositoryRoot.toString(), "rev-parse", "HEAD")))
                .isEqualTo(expectedCommit);
        assertThat(runCommand(List.of(
                "git", "-C", repositoryRoot.toString(), "status", "--porcelain=v1", "--untracked-files=all"
        ))).isEmpty();

        assertTrackedAtHead(repositoryRoot, "contracts/agent-openapi.yaml", pythonContracts.resolve("agent-openapi.yaml"));
        assertLfNormalizedEqual(
                "/contracts/openapi/agent-openapi.yaml",
                pythonContracts.resolve("agent-openapi.yaml")
        );
        assertTrackedAtHead(
                repositoryRoot,
                "contracts/hmac/hmac-v1-test-vector.json",
                pythonContracts.resolve("hmac/hmac-v1-test-vector.json")
        );
        assertLfNormalizedEqual(
                "/contracts/hmac/hmac-v1-test-vector.json",
                pythonContracts.resolve("hmac/hmac-v1-test-vector.json")
        );
        assertTrackedAtHead(
                repositoryRoot,
                "contracts/rabbit/task-execution-requested-v1.schema.json",
                pythonContracts.resolve("rabbit/task-execution-requested-v1.schema.json")
        );
        assertLfNormalizedEqual(
                "/contracts/rabbit/task-execution-requested-v1.schema.json",
                pythonContracts.resolve("rabbit/task-execution-requested-v1.schema.json")
        );

        AgentOpenApiSource source = readResource(
                "/contracts/openapi/agent-openapi-source.json",
                AgentOpenApiSource.class
        );
        assertThat(source.gitCommit()).isEqualTo(expectedCommit);
        assertThat(source.sourcePath()).isEqualTo("contracts/agent-openapi.yaml");
        assertThat(source.sharedContracts()).containsOnlyKeys(
                "contracts/hmac/hmac-v1-test-vector.json",
                "contracts/rabbit/task-execution-requested-v1.schema.json"
        );

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult parsed = new OpenAPIV3Parser().readLocation(
                pythonContracts.resolve("agent-openapi.yaml").toUri().toString(),
                null,
                options
        );
        assertThat(parsed.getOpenAPI()).isNotNull();
        assertThat(parsed.getMessages()).isEmpty();
        evidence("python_repository", "commit=" + expectedCommit + " clean=true mirrors=exact");
    }

    @Test
    void signedPostBodyContinuesOneTraceFromJavaThroughPythonBackToJava() {
        InternalCommunicationProperties properties = properties(required("AGENT_BASE_URL"));
        String traceId = nonZeroHex(16);
        MDC.put(TraceIdHolder.MDC_KEY, traceId);
        CapturingRequestSigner signer = new CapturingRequestSigner(properties, Clock.systemUTC());
        PythonInternalClient client = new PythonInternalClient(properties, OBJECT_MAPPER, signer);
        String bodyMarker = "raw-post-" + nonZeroHex(6);
        byte[] rawBody = ("{\n  \"probe\" : \"" + bodyMarker + "\"\n}\n")
                .getBytes(StandardCharsets.UTF_8);

        PythonCallResult<PythonReverseTraceProbeResponse> result = client.reverseTraceProbeRawJson(rawBody);

        assertAvailable(result);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.payload()).isNotNull();
        assertThat(result.payload().accepted()).isTrue();
        assertThat(result.payload().callerKeyId()).isNotBlank();
        assertThat(result.payload().traceId()).isEqualTo(traceId);
        assertThat(signer.lastBody()).containsExactly(rawBody);
        assertTraceparent(signer.last().traceparent(), traceId);
        privateEvidence("signedRawJsonBodyMarker", bodyMarker);
        privateEvidence("signedRawJsonSignature", signer.last().signature());
        evidence("signed_raw_json_post", "httpStatus=200 traceId=" + traceId);
    }

    @Test
    void javaAndPythonReadinessAreReady() throws Exception {
        PythonInternalClient python = pythonClient(required("AGENT_BASE_URL"));
        PythonCallResult<PythonReadinessResponse> pythonResult = python.readiness();
        assertAvailable(pythonResult);
        assertThat(pythonResult.httpStatus()).isEqualTo(200);
        assertThat(pythonResult.payload().status()).isEqualTo(PythonReadinessResponse.Status.READY);

        HttpResponse<byte[]> javaResult = HTTP_CLIENT.send(
                HttpRequest.newBuilder(JAVA_BASE_URI.resolve("/internal/v1/health/ready"))
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        assertThat(javaResult.statusCode()).isEqualTo(200);
        JavaReadinessResponse javaReadiness = OBJECT_MAPPER.readValue(
                javaResult.body(),
                JavaReadinessResponse.class
        );
        assertThat(javaReadiness.status()).isEqualTo("UP");
        assertThat(javaReadiness.javaM1Ready()).isTrue();
        assertThat(javaReadiness.codeCapabilitiesReady()).isTrue();
        assertThat(javaReadiness.testServerDeployed()).isTrue();
        assertThat(javaReadiness.m0GateStatus()).isEqualTo("PASS");
        evidence("readiness", "java=200/UP python=200/READY");
    }

    @Test
    void signedCapabilitiesReportExpectedPythonCommitAndOpenApiStatuses() {
        String expectedCommit = expectedAgentCommit();
        InternalCommunicationProperties properties = properties(required("AGENT_BASE_URL"));
        CapturingRequestSigner signer = new CapturingRequestSigner(properties, Clock.systemUTC());
        PythonCallResult<PythonCapabilitiesResponse> result = new PythonInternalClient(
                properties,
                OBJECT_MAPPER,
                signer
        ).capabilities();

        assertAvailable(result);
        privateEvidence("capabilitiesSignature", signer.last().signature());
        assertThat(result.httpStatus()).isEqualTo(200);
        PythonCapabilitiesResponse capabilities = result.payload();
        assertThat(capabilities.gitCommit()).isEqualTo(expectedCommit);
        assertThat(capabilities.modelGateway().mode()).isNotNull();
        assertThat(capabilities.modelGateway().status()).isNotNull();
        assertThat(capabilities.providers())
                .allSatisfy(provider -> {
                    assertThat(provider.code()).isNotBlank();
                    assertThat(provider.status()).isNotNull();
                });
        assertThat(capabilities.supportedProfiles())
                .allSatisfy(profile -> {
                    assertThat(profile.profileCode()).isNotBlank();
                    assertThat(profile.profileVersion()).isNotBlank();
                    assertThat(profile.taskTypes()).isNotNull();
                });
        evidence("capabilities", "httpStatus=200 gitCommit=" + expectedCommit + " schema=valid");
    }

    @Test
    void oneByteBodyTamperingIsRejected() throws Exception {
        String target = "/internal/v1/test/java-reverse-call";
        String bodyMarker = "body-tamper-" + nonZeroHex(6);
        byte[] signedBody = ("{\"probe\":\"" + bodyMarker + "-a\"}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] sentBody = signedBody.clone();
        sentBody[sentBody.length - 3] ^= 1;
        assertThat(differentByteCount(signedBody, sentBody)).isEqualTo(1);
        SignedHeaders headers = signedHeaders("POST", target, signedBody, nowTimestamp(), newNonce());
        privateEvidence("bodyTamperBodyMarker", bodyMarker);
        privateEvidence("bodyTamperSignature", headers.signature());

        RawResponse response = send(requiredBaseUri("AGENT_BASE_URL"), "POST", target, sentBody, headers);

        assertError(response, 401, "INTERNAL_SIGNATURE_INVALID");
        RawResponse validAfterTamper = send(
                requiredBaseUri("AGENT_BASE_URL"), "POST", target, signedBody, headers
        );
        assertThat(validAfterTamper.status()).isEqualTo(200);
        evidence("body_tamper", "httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true");
    }

    @Test
    void queryTamperingIsRejected() throws Exception {
        String signedTarget = "/internal/v1/capabilities?mode=a%20b";
        String sentTarget = "/internal/v1/capabilities?mode=a+b";
        SignedHeaders headers = signedHeaders("GET", signedTarget, new byte[0], nowTimestamp(), newNonce());
        privateEvidence("queryTamperSignature", headers.signature());

        RawResponse response = send(
                requiredBaseUri("AGENT_BASE_URL"),
                "GET",
                sentTarget,
                new byte[0],
                headers
        );

        assertError(response, 401, "INTERNAL_SIGNATURE_INVALID");
        RawResponse validAfterTamper = send(
                requiredBaseUri("AGENT_BASE_URL"),
                "GET",
                signedTarget,
                new byte[0],
                headers
        );
        assertThat(validAfterTamper.status()).isEqualTo(200);
        evidence("query_tamper", "httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true");
    }

    @Test
    void sameNonceReplayIsRejected() throws Exception {
        String target = "/internal/v1/capabilities";
        SignedHeaders headers = signedHeaders("GET", target, new byte[0], nowTimestamp(), newNonce());
        URI baseUri = requiredBaseUri("AGENT_BASE_URL");
        privateEvidence("replaySignature", headers.signature());

        RawResponse first = send(baseUri, "GET", target, new byte[0], headers);
        RawResponse replay = send(baseUri, "GET", target, new byte[0], headers);

        assertThat(first.status()).isEqualTo(200);
        assertError(replay, 401, "INTERNAL_REPLAY_DETECTED");
        evidence("nonce_replay", "first=200 second=401 code=INTERNAL_REPLAY_DETECTED");
    }

    @Test
    void expiredTimestampIsRejected() throws Exception {
        String target = "/internal/v1/capabilities";
        String timestamp = Long.toString(Instant.now().minusSeconds(600).getEpochSecond());
        String nonce = newNonce();
        SignedHeaders headers = signedHeaders("GET", target, new byte[0], timestamp, nonce);
        privateEvidence("expiredTimestampSignature", headers.signature());

        RawResponse response = send(
                requiredBaseUri("AGENT_BASE_URL"),
                "GET",
                target,
                new byte[0],
                headers
        );

        assertError(response, 401, "INTERNAL_SIGNATURE_INVALID");
        SignedHeaders freshHeaders = signedHeaders("GET", target, new byte[0], nowTimestamp(), nonce);
        privateEvidence("expiredFreshSignature", freshHeaders.signature());
        RawResponse validAfterExpiry = send(
                requiredBaseUri("AGENT_BASE_URL"),
                "GET",
                target,
                new byte[0],
                freshHeaders
        );
        assertThat(validAfterExpiry.status()).isEqualTo(200);
        evidence("expired_timestamp", "httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true");
    }

    @Test
    void uppercaseUuidEvenWhenCorrectlySignedIsRejectedByPythonBeforeNonceClaim() throws Exception {
        String target = "/internal/v1/capabilities";
        String uppercaseNonce = "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF";
        SignedHeaders headers = signedHeaders("GET", target, new byte[0], nowTimestamp(), uppercaseNonce);
        privateEvidence("uppercaseNonceSignature", headers.signature());
        URI healthyBaseUri = requiredBaseUri("AGENT_BASE_URL");
        URI failureBaseUri = requiredBaseUri("AGENT_REDIS_FAILURE_BASE_URL");
        requireGate(!sameAuthority(healthyBaseUri, failureBaseUri),
                "The isolated Redis failure instance must use a distinct loopback listener");

        RawResponse isolatedResponse = send(failureBaseUri, "GET", target, new byte[0], headers);
        RawResponse healthyResponse = send(healthyBaseUri, "GET", target, new byte[0], headers);

        assertError(isolatedResponse, 401, "INTERNAL_SIGNATURE_INVALID");
        assertError(healthyResponse, 401, "INTERNAL_SIGNATURE_INVALID");
        evidence("uppercase_nonce",
                "httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceStoreBypassed=true");
    }

    @Test
    void isolatedPythonRedisFailureFailsClosedWithoutStoppingTheSharedAgent() throws Exception {
        URI healthyBaseUri = requiredBaseUri("AGENT_BASE_URL");
        URI failureBaseUri = requiredBaseUri("AGENT_REDIS_FAILURE_BASE_URL");
        requireGate(!sameAuthority(healthyBaseUri, failureBaseUri),
                "The isolated Redis failure instance must use a distinct loopback listener");
        PythonReadinessHttpResponse healthyBefore = pythonReadiness(healthyBaseUri);
        assertPythonReady(healthyBefore);

        PythonReadinessHttpResponse isolated = pythonReadiness(failureBaseUri);
        assertThat(isolated.httpStatus()).isEqualTo(503);
        assertThat(isolated.payload().status()).isEqualTo(PythonReadinessResponse.Status.NOT_READY);
        assertThat(isolated.payload().service()).isEqualTo(healthyBefore.payload().service());
        assertThat(isolated.payload().serviceVersion()).isEqualTo(healthyBefore.payload().serviceVersion());
        assertThat(healthyBefore.payload().checks())
                .allSatisfy(check -> {
                    assertThat(check.status()).isEqualTo(PythonReadinessResponse.Status.READY);
                    assertThat(check.reasonCode()).isNull();
                });
        assertThat(isolated.payload().checks())
                .extracting(PythonReadinessResponse.Check::name)
                .containsExactlyInAnyOrderElementsOf(healthyBefore.payload().checks().stream()
                        .map(PythonReadinessResponse.Check::name)
                        .toList());
        assertThat(isolated.payload().checks())
                .filteredOn(check -> check.name().equals("internalAuthentication"))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(PythonReadinessResponse.Status.NOT_READY);
                    assertThat(check.reasonCode()).isEqualTo("INTERNAL_NONCE_STORE_UNAVAILABLE");
                });
        assertThat(isolated.payload().checks())
                .filteredOn(check -> !check.name().equals("internalAuthentication"))
                .allSatisfy(check -> {
                    PythonReadinessResponse.Check healthyCheck = healthyBefore.payload().checks().stream()
                            .filter(candidate -> candidate.name().equals(check.name()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(check.status())
                            .isEqualTo(PythonReadinessResponse.Status.READY)
                            .isEqualTo(healthyCheck.status());
                    assertThat(check.reasonCode()).isNull();
                    assertThat(check.reasonCode()).isEqualTo(healthyCheck.reasonCode());
                });

        String target = "/internal/v1/capabilities";
        SignedHeaders headers = signedHeaders("GET", target, new byte[0], nowTimestamp(), newNonce());
        privateEvidence("isolatedRedisFailureSignature", headers.signature());
        RawResponse response = send(failureBaseUri, "GET", target, new byte[0], headers);

        assertError(response, 503, "INTERNAL_AUTHENTICATION_UNAVAILABLE");
        PythonReadinessHttpResponse healthyAfter = pythonReadiness(healthyBaseUri);
        assertPythonReady(healthyAfter);
        assertThat(healthyAfter.payload().service()).isEqualTo(healthyBefore.payload().service());
        assertThat(healthyAfter.payload().serviceVersion()).isEqualTo(healthyBefore.payload().serviceVersion());
        evidence("isolated_redis_failure",
                "isolatedAgent=NOT_READY(INTERNAL_NONCE_STORE_UNAVAILABLE)"
                        + " hmac=503/INTERNAL_AUTHENTICATION_UNAVAILABLE sharedAgent=READY");
    }

    @Test
    void crossServiceTraceUsesChildSpansIsLoggedByBothServicesAndDoesNotLeakSensitiveInputs()
            throws Exception {
        InternalCommunicationProperties properties = properties(required("AGENT_BASE_URL"));
        String traceId = nonZeroHex(16);
        String bodyMarker = "trace-log-" + nonZeroHex(6);
        String rawBody = "{\"probe\":\"" + bodyMarker + "\"}";
        long journalSince = Instant.now().minusSeconds(2).getEpochSecond();
        String javaInboundBodyMarker = "java-inbound-" + nonZeroHex(6);
        String javaInboundTarget = "/internal/v1/test/reverse-call?mode=python-m1";
        byte[] javaInboundBody = ("{\"probe\":\"" + javaInboundBodyMarker + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        SignedHeaders javaInboundHeaders = signedHeaders(
                "POST",
                javaInboundTarget,
                javaInboundBody,
                nowTimestamp(),
                newNonce()
        );
        RawResponse javaInboundResponse = send(
                JAVA_BASE_URI,
                "POST",
                javaInboundTarget,
                javaInboundBody,
                javaInboundHeaders
        );
        assertThat(javaInboundResponse.status()).isEqualTo(200);
        privateEvidence("javaInboundBodyMarker", javaInboundBodyMarker);
        privateEvidence("javaInboundSignature", javaInboundHeaders.signature());
        MDC.put(TraceIdHolder.MDC_KEY, traceId);
        CapturingRequestSigner signer = new CapturingRequestSigner(properties, Clock.systemUTC());
        PythonInternalClient client = new PythonInternalClient(properties, OBJECT_MAPPER, signer);

        PythonCallResult<PythonReverseTraceProbeResponse> result = client.reverseTraceProbeRawJson(
                rawBody.getBytes(StandardCharsets.UTF_8)
        );

        assertAvailable(result);
        assertThat(result.payload().traceId()).isEqualTo(traceId);
        InternalHmacRequestHeaders outbound = signer.last();
        String javaToPythonSpan = spanId(outbound.traceparent());
        JournalEvidence journals = awaitJournalEvidence(traceId, journalSince);
        Matcher javaSpanLog = Pattern.compile(
                "internal_reverse_probe_completed traceId=" + Pattern.quote(traceId)
                        + " parentSpanId=([0-9a-f]{16}) queryMode=python-m1"
        ).matcher(journals.javaLog());
        requireGate(javaSpanLog.find(), "Java journal is missing the reverse-call child span evidence");
        String pythonToJavaSpan = javaSpanLog.group(1);
        assertThat(pythonToJavaSpan).matches(SPAN_ID).isNotEqualTo(javaToPythonSpan);

        String combined = journals.javaLog() + journals.pythonLog();
        requireGate(!combined.contains(properties.getSecret()), "Service journals exposed the HMAC secret");
        requireGate(!combined.contains(outbound.signature()), "Service journals exposed an HMAC signature");
        requireGate(!combined.contains(rawBody), "Service journals exposed the complete request body");
        requireGate(!combined.contains(bodyMarker), "Service journals exposed request body content");
        privateEvidence("traceId", traceId);
        privateEvidence("javaOutboundSpanId", javaToPythonSpan);
        privateEvidence("javaInboundSpanId", pythonToJavaSpan);
        privateEvidence("probeBodyMarker", bodyMarker);
        privateEvidence("crossTraceSignature", outbound.signature());
        evidence("cross_service_trace",
                "traceId=" + traceId
                        + " javaOutboundSpanId=" + javaToPythonSpan
                        + " javaInboundSpanId=" + pythonToJavaSpan
                        + " sameTrace=true childSpan=true query=verified logs=java,python redaction=PASS");
    }

    private void assertLfNormalizedEqual(String javaResource, Path pythonFile) throws Exception {
        requireGate(Files.isRegularFile(pythonFile), "A required Python contract file is missing");
        byte[] javaBytes = resourceBytes(javaResource);
        byte[] pythonBytes = Files.readAllBytes(pythonFile);
        requireGate(Arrays.equals(pythonBytes, javaBytes),
                "A Python contract is not a byte-exact Java mirror");
        requireGate(normalize(pythonBytes).equals(normalize(javaBytes)),
                "A Python contract differs after LF normalization");
    }

    private void assertTrackedAtHead(Path repositoryRoot, String repositoryPath, Path worktreeFile)
            throws Exception {
        requireGate(Files.isRegularFile(worktreeFile), "A required Python contract file is missing");
        String headBlob = runCommand(List.of(
                "git", "-C", repositoryRoot.toString(), "rev-parse", "HEAD:" + repositoryPath
        ));
        String worktreeBlob = runCommand(List.of(
                "git", "-C", repositoryRoot.toString(), "hash-object", worktreeFile.toString()
        ));
        requireGate(COMMIT.matcher(headBlob).matches() && headBlob.equals(worktreeBlob),
                "A Python contract file is not the tracked blob from the expected HEAD");
    }

    private byte[] resourceBytes(String resource) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            requireGate(input != null, "A required Java contract resource is missing");
            return input.readAllBytes();
        }
    }

    private <T> T readResource(String resource, Class<T> type) throws IOException {
        return OBJECT_MAPPER.readValue(resourceBytes(resource), type);
    }

    private String normalize(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private PythonInternalClient pythonClient(String baseUrl) {
        InternalCommunicationProperties properties = properties(baseUrl);
        return new PythonInternalClient(
                properties,
                OBJECT_MAPPER,
                new InternalHmacRequestSigner(properties, Clock.systemUTC())
        );
    }

    private InternalCommunicationProperties properties(String baseUrl) {
        InternalCommunicationProperties properties = new InternalCommunicationProperties();
        properties.setKeyId(required("INTERNAL_HMAC_KEY_ID"));
        properties.setSecret(required("INTERNAL_HMAC_SECRET"));
        properties.getPython().setBaseUrl(baseUrl);
        properties.getPython().setConnectTimeout(Duration.ofSeconds(3));
        properties.getPython().setReadTimeout(Duration.ofSeconds(8));
        return properties;
    }

    private void assertAvailable(PythonCallResult<?> result) {
        assertThat(result.state())
                .as("Python call reason=%s httpStatus=%s", result.reason(), result.httpStatus())
                .isEqualTo(PythonCallResult.State.AVAILABLE);
        assertThat(result.payload()).isNotNull();
    }

    private SignedHeaders signedHeaders(
            String method,
            String rawPathWithQuery,
            byte[] body,
            String timestamp,
            String nonce
    ) {
        String traceId = nonZeroHex(16);
        String traceparent = "00-" + traceId + "-" + nonZeroHex(8) + "-01";
        InternalHmacSigner.SignedContent signed = InternalHmacSigner.sign(
                timestamp,
                nonce,
                method,
                rawPathWithQuery,
                body,
                required("INTERNAL_HMAC_SECRET").getBytes(StandardCharsets.UTF_8)
        );
        return new SignedHeaders(
                required("INTERNAL_HMAC_KEY_ID"),
                timestamp,
                nonce,
                signed.bodySha256(),
                signed.signature(),
                traceparent
        );
    }

    private RawResponse send(
            URI baseUri,
            String method,
            String rawTarget,
            byte[] body,
            SignedHeaders headers
    ) throws IOException, InterruptedException {
        URI target = URI.create(withoutTrailingSlash(baseUri.toString()) + rawTarget);
        HttpRequest.BodyPublisher publisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(8))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Accept", "application/json")
                .header(InternalHmacVerificationFilter.KEY_ID_HEADER, headers.keyId())
                .header(InternalHmacVerificationFilter.TIMESTAMP_HEADER, headers.timestamp())
                .header(InternalHmacVerificationFilter.NONCE_HEADER, headers.nonce())
                .header(InternalHmacVerificationFilter.CONTENT_SHA256_HEADER, headers.contentSha256())
                .header(InternalHmacVerificationFilter.SIGNATURE_HEADER, headers.signature())
                .header("traceparent", headers.traceparent())
                .method(method, publisher);
        if (body.length > 0) {
            request.header("Content-Type", "application/json");
        }
        HttpResponse<byte[]> response = HTTP_CLIENT.send(
                request.build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        return new RawResponse(response.statusCode(), response.body(), response.headers());
    }

    private void assertError(RawResponse response, int status, String code) throws IOException {
        assertThat(response.status()).isEqualTo(status);
        InternalErrorResponse error = OBJECT_MAPPER.readValue(response.body(), InternalErrorResponse.class);
        assertThat(error.code()).isEqualTo(code);
        assertThat(error.message()).isNotBlank();
        assertThat(error.details()).isNotNull();
        assertThat(error.traceId()).matches(TRACE_ID);
        assertThat(response.headers().firstValue("X-Trace-Id")).contains(error.traceId());
    }

    private PythonReadinessHttpResponse pythonReadiness(URI baseUri) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = HTTP_CLIENT.send(
                HttpRequest.newBuilder(URI.create(
                                withoutTrailingSlash(baseUri.toString()) + "/internal/v1/health/ready"
                        ))
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        PythonReadinessResponse readiness = OBJECT_MAPPER.readValue(response.body(), PythonReadinessResponse.class);
        return new PythonReadinessHttpResponse(response.statusCode(), readiness);
    }

    private void assertPythonReady(PythonReadinessHttpResponse response) {
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.payload().status()).isEqualTo(PythonReadinessResponse.Status.READY);
    }

    private JournalEvidence awaitJournalEvidence(String traceId, long sinceEpochSeconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        String javaLog = "";
        String pythonLog = "";
        while (System.nanoTime() < deadline) {
            javaLog = journal(JAVA_SYSTEMD_UNIT, sinceEpochSeconds);
            pythonLog = journal(PYTHON_SYSTEMD_UNIT, sinceEpochSeconds);
            if (javaLog.contains(traceId)
                    && javaLog.contains("internal_reverse_probe_completed")
                    && pythonLog.contains(traceId)
                    && pythonLog.contains("internal_request_authenticated")
                    && pythonLog.contains("java_reverse_call_completed")) {
                return new JournalEvidence(javaLog, pythonLog);
            }
            Thread.sleep(200);
        }
        requireGate(false, "Cross-service trace was not correlated in both service journals");
        return new JournalEvidence(javaLog, pythonLog);
    }

    private void writeTraceEvidence() throws IOException {
        List<String> keys = List.of(
                "traceId",
                "javaOutboundSpanId",
                "javaInboundSpanId",
                "probeBodyMarker",
                "signedRawJsonBodyMarker",
                "signedRawJsonSignature",
                "capabilitiesSignature",
                "bodyTamperBodyMarker",
                "bodyTamperSignature",
                "queryTamperSignature",
                "replaySignature",
                "expiredTimestampSignature",
                "expiredFreshSignature",
                "uppercaseNonceSignature",
                "isolatedRedisFailureSignature",
                "javaInboundBodyMarker",
                "javaInboundSignature",
                "crossTraceSignature"
        );
        requireGate(privateEvidence.keySet().equals(Set.copyOf(keys)),
                "The private Gate evidence inventory is incomplete");
        Path target = Path.of(required("M1_TRACE_EVIDENCE_FILE")).toAbsolutePath().normalize();
        Path parent = target.getParent();
        requireGate(parent != null && Files.isDirectory(parent),
                "M1 trace evidence parent directory is missing");
        requireGate(!Files.isSymbolicLink(target), "M1 trace evidence target must not be a symbolic link");
        StringBuilder content = new StringBuilder();
        keys.forEach(key -> content.append(key).append('=').append(privateEvidence.get(key)).append('\n'));
        Path temporary = Files.createTempFile(parent, ".m1-trace-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.US_ASCII);
            Files.setPosixFilePermissions(temporary, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
            Files.setPosixFilePermissions(target, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void privateEvidence(String key, String value) {
        requireGate(value != null && !value.isBlank() && !value.contains("\n") && !value.contains("\r"),
                "A private Gate evidence value is invalid");
        requireGate(privateEvidence.putIfAbsent(key, value) == null,
                "A private Gate evidence key was written more than once");
    }

    private String journal(String unit, long sinceEpochSeconds) throws Exception {
        return runCommand(List.of(
                "journalctl",
                "--unit", unit,
                "--since", "@" + sinceEpochSeconds,
                "--lines", "300",
                "--no-pager",
                "--output", "cat"
        ));
    }

    private String runCommand(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
        AtomicReference<IOException> drainFailure = new AtomicReference<>();
        Thread outputDrainer = Thread.ofVirtual()
                .name("m1-gate-command-output")
                .start(() -> {
                    try (InputStream output = process.getInputStream()) {
                        output.transferTo(capturedOutput);
                    } catch (IOException exception) {
                        drainFailure.set(exception);
                    }
                });
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            outputDrainer.join(TimeUnit.SECONDS.toMillis(2));
            throw new IllegalStateException("A bounded Gate evidence command timed out");
        }
        outputDrainer.join(TimeUnit.SECONDS.toMillis(2));
        if (outputDrainer.isAlive()) {
            process.destroyForcibly();
            throw new IllegalStateException("A Gate evidence command output could not be drained");
        }
        if (drainFailure.get() != null) {
            throw new IllegalStateException("A Gate evidence command output could not be read");
        }
        String output = capturedOutput.toString(StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("A Gate evidence command failed without usable evidence");
        }
        return output;
    }

    private URI requiredBaseUri(String name) throws Exception {
        URI uri;
        try {
            uri = URI.create(required(name));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " must be an absolute loopback HTTP URL", exception);
        }
        requireGate("http".equalsIgnoreCase(uri.getScheme()), name + " must use HTTP on loopback");
        requireGate(uri.getHost() != null && InetAddress.getByName(uri.getHost()).isLoopbackAddress(),
                name + " must target loopback");
        requireGate(uri.getRawQuery() == null && uri.getRawFragment() == null,
                name + " must not contain query or fragment data");
        requireGate(uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()),
                name + " must not contain a base path");
        requireGate(uri.getPort() > 0, name + " must use an explicit loopback port");
        return uri;
    }

    private String expectedAgentCommit() {
        String expected = required("EXPECTED_AGENT_COMMIT");
        requireGate(COMMIT.matcher(expected).matches(),
                "EXPECTED_AGENT_COMMIT must be an explicit lowercase full Git commit");
        return expected;
    }

    private String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required by the live M1 Contract Gate A");
        }
        return value;
    }

    private String nowTimestamp() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    private String newNonce() {
        return UUID.randomUUID().toString();
    }

    private String nonZeroHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        do {
            RANDOM.nextBytes(bytes);
        } while (allZero(bytes));
        return HexFormat.of().formatHex(bytes);
    }

    private boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private void assertTraceparent(String traceparent, String traceId) {
        assertThat(traceparent).matches("^00-" + traceId + "-[0-9a-f]{16}-01$");
        assertThat(spanId(traceparent)).matches(SPAN_ID).isNotEqualTo("0000000000000000");
    }

    private String spanId(String traceparent) {
        String[] parts = traceparent.split("-", -1);
        requireGate(parts.length == 4, "Outbound traceparent is malformed");
        return parts[2];
    }

    private int differentByteCount(byte[] left, byte[] right) {
        requireGate(left.length == right.length, "Tampered body must retain its original byte length");
        int differences = 0;
        for (int index = 0; index < left.length; index++) {
            if (left[index] != right[index]) {
                differences++;
            }
        }
        return differences;
    }

    private boolean sameAuthority(URI left, URI right) {
        return Objects.equals(left.getHost(), right.getHost()) && left.getPort() == right.getPort();
    }

    private String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void requireGate(boolean condition, String safeMessage) {
        if (!condition) {
            throw new IllegalStateException(safeMessage);
        }
    }

    private void evidence(String check, String value) {
        System.out.println("M1_GATE_A " + check + " " + value);
    }

    private record SignedHeaders(
            String keyId,
            String timestamp,
            String nonce,
            String contentSha256,
            String signature,
            String traceparent
    ) {
    }

    private record RawResponse(int status, byte[] body, HttpHeaders headers) {
    }

    private record PythonReadinessHttpResponse(int httpStatus, PythonReadinessResponse payload) {
    }

    private record InternalErrorResponse(
            String code,
            String message,
            Map<String, Object> details,
            String traceId
    ) {
    }

    private record JavaReadinessResponse(
            String status,
            boolean javaM1Ready,
            boolean codeCapabilitiesReady,
            boolean testServerDeployed,
            String m0GateStatus
    ) {
    }

    private record AgentOpenApiSource(
            String repository,
            String gitCommit,
            String sourcePath,
            String sha256LfNormalized,
            Map<String, String> sharedContracts
    ) {
    }

    private record JournalEvidence(String javaLog, String pythonLog) {
    }

    private static final class CapturingRequestSigner extends InternalHmacRequestSigner {

        private final AtomicReference<InternalHmacRequestHeaders> last = new AtomicReference<>();
        private final AtomicReference<byte[]> lastBody = new AtomicReference<>();

        private CapturingRequestSigner(InternalCommunicationProperties properties, Clock clock) {
            super(properties, clock);
        }

        @Override
        public InternalHmacRequestHeaders sign(String method, String rawPathWithQuery, byte[] body) {
            InternalHmacRequestHeaders signed = super.sign(method, rawPathWithQuery, body);
            lastBody.set(java.util.Arrays.copyOf(body, body.length));
            last.set(signed);
            return signed;
        }

        private InternalHmacRequestHeaders last() {
            return Objects.requireNonNull(last.get(), "No signed request was captured");
        }

        private byte[] lastBody() {
            byte[] body = Objects.requireNonNull(lastBody.get(), "No signed request body was captured");
            return java.util.Arrays.copyOf(body, body.length);
        }
    }
}
