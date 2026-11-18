package com.guanghe.backend.integration.internal.security;

import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.web.TraceIdHolder;
import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(name = "zhixiaohe.internal.enabled", havingValue = "true", matchIfMissing = true)
public class InternalHmacVerificationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalHmacVerificationFilter.class);

    public static final String KEY_ID_HEADER = "X-Internal-Key-Id";
    public static final String TIMESTAMP_HEADER = "X-Internal-Timestamp";
    public static final String NONCE_HEADER = "X-Internal-Nonce";
    public static final String CONTENT_SHA256_HEADER = "X-Content-SHA256";
    public static final String SIGNATURE_HEADER = "X-Internal-Signature";
    public static final String READINESS_PATH = "/internal/v1/health/ready";

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final InternalCommunicationProperties properties;
    private final InternalNonceStore nonceStore;
    private final InternalHmacFailureWriter failureWriter;
    private final Clock clock;

    public InternalHmacVerificationFilter(
            InternalCommunicationProperties properties,
            InternalNonceStore nonceStore,
            InternalHmacFailureWriter failureWriter,
            @Qualifier("internalCommunicationClock") Clock clock
    ) {
        this.properties = properties;
        this.nonceStore = nonceStore;
        this.failureWriter = failureWriter;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (READINESS_PATH.equals(request.getRequestURI())) {
            if (!isLoopback(request.getRemoteAddr())) {
                failureWriter.write(response, ErrorCode.FORBIDDEN);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        String keyId = exactlyOneHeaderValue(request, KEY_ID_HEADER);
        String timestamp = exactlyOneHeaderValue(request, TIMESTAMP_HEADER);
        String nonce = exactlyOneHeaderValue(request, NONCE_HEADER);
        String suppliedBodyHash = exactlyOneHeaderValue(request, CONTENT_SHA256_HEADER);
        String suppliedSignature = exactlyOneHeaderValue(request, SIGNATURE_HEADER);

        if (!validKeyId(keyId)
                || !validTimestamp(timestamp)
                || !validNonce(nonce)
                || suppliedBodyHash == null
                || !LOWERCASE_SHA256.matcher(suppliedBodyHash).matches()
                || suppliedSignature == null
                || suppliedSignature.length() > 128) {
            failureWriter.write(response, ErrorCode.INTERNAL_SIGNATURE_INVALID);
            return;
        }

        CachedBodyHttpServletRequest cachedRequest;
        try {
            cachedRequest = new CachedBodyHttpServletRequest(request, properties.getMaxBodyBytes());
        } catch (CachedBodyHttpServletRequest.RequestBodyTooLargeException exception) {
            failureWriter.write(response, ErrorCode.INTERNAL_SIGNATURE_INVALID);
            return;
        }

        String rawPathWithQuery = request.getRequestURI();
        if (request.getQueryString() != null) {
            rawPathWithQuery += "?" + request.getQueryString();
        }
        InternalHmacSigner.SignedContent expected = InternalHmacSigner.sign(
                timestamp,
                nonce,
                request.getMethod().toUpperCase(Locale.ROOT),
                rawPathWithQuery,
                cachedRequest.body(),
                properties.secretBytes()
        );
        if (!constantTimeEquals(expected.bodySha256(), suppliedBodyHash)
                || !InternalHmacSigner.signatureMatches(expected.signature(), suppliedSignature)) {
            failureWriter.write(response, ErrorCode.INTERNAL_SIGNATURE_INVALID);
            return;
        }

        try {
            if (!nonceStore.claim(keyId, nonce, properties.getNonceTtl())) {
                failureWriter.write(response, ErrorCode.INTERNAL_REPLAY_DETECTED);
                return;
            }
        } catch (InternalNonceStoreUnavailableException exception) {
            failureWriter.write(response, ErrorCode.INTERNAL_AUTHENTICATION_UNAVAILABLE);
            return;
        }

        cachedRequest.setAttribute(InternalCallerIdentity.REQUEST_ATTRIBUTE, new InternalCallerIdentity(keyId));
        LOGGER.info(
                "internal_hmac_verified traceId={} path={} keyId={}",
                TraceIdHolder.currentTraceId(),
                request.getRequestURI(),
                keyId
        );
        filterChain.doFilter(cachedRequest, response);
    }

    private String exactlyOneHeaderValue(HttpServletRequest request, String headerName) {
        Enumeration<String> values = request.getHeaders(headerName);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        if (value == null || value.indexOf(',') >= 0 || values.hasMoreElements()) {
            return null;
        }
        return value;
    }

    private boolean validKeyId(String keyId) {
        return keyId != null && constantTimeEquals(properties.getKeyId(), keyId);
    }

    private boolean validTimestamp(String timestamp) {
        if (timestamp == null || timestamp.length() > 20) {
            return false;
        }
        try {
            Instant supplied = Instant.ofEpochSecond(Long.parseLong(timestamp));
            Instant now = clock.instant();
            return !supplied.isBefore(now.minus(properties.getAllowedClockSkew()))
                    && !supplied.isAfter(now.plus(properties.getAllowedClockSkew()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean validNonce(String nonce) {
        if (nonce == null || nonce.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(nonce).toString().equals(nonce);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isLoopback(String remoteAddress) {
        try {
            return remoteAddress != null && InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
        );
    }
}
