package com.guanghe.backend.integration.internal.config;

import com.guanghe.backend.integration.internal.security.InternalNonceStore;
import com.guanghe.backend.integration.internal.security.RedisInternalNonceStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InternalCommunicationProperties.class)
@ConditionalOnProperty(name = "zhixiaohe.internal.enabled", havingValue = "true", matchIfMissing = true)
public class InternalCommunicationConfiguration {

    @Bean
    Clock internalCommunicationClock(InternalCommunicationProperties properties) {
        validate(properties);
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(InternalNonceStore.class)
    InternalNonceStore internalNonceStore(
            StringRedisTemplate redisTemplate,
            InternalCommunicationProperties properties
    ) {
        return new RedisInternalNonceStore(redisTemplate, properties);
    }

    private void validate(InternalCommunicationProperties properties) {
        requireText(properties.getKeyId(), "INTERNAL_HMAC_KEY_ID");
        requireText(properties.getSecret(), "INTERNAL_HMAC_SECRET");
        if (properties.secretBytes().length < 32) {
            throw new IllegalStateException("INTERNAL_HMAC_SECRET must contain at least 32 UTF-8 bytes");
        }
        requirePositive(properties.getAllowedClockSkew(), "INTERNAL_HMAC_ALLOWED_CLOCK_SKEW");
        if (properties.getNonceTtl() == null || properties.getNonceTtl().compareTo(Duration.ofSeconds(120)) < 0) {
            throw new IllegalStateException("INTERNAL_HMAC_NONCE_TTL must be at least 120 seconds");
        }
        requireText(properties.getNonceKeyPrefix(), "INTERNAL_HMAC_NONCE_KEY_PREFIX");
        if (properties.getMaxBodyBytes() <= 0) {
            throw new IllegalStateException("INTERNAL_HMAC_MAX_BODY_BYTES must be positive");
        }
        URI pythonUri;
        try {
            pythonUri = URI.create(properties.getPython().getBaseUrl());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AGENT_BASE_URL must be a valid URI", exception);
        }
        if (!"http".equalsIgnoreCase(pythonUri.getScheme()) || pythonUri.getHost() == null) {
            throw new IllegalStateException("AGENT_BASE_URL must be an HTTP service URI");
        }
        if (!isLoopbackHost(pythonUri.getHost())) {
            throw new IllegalStateException("AGENT_BASE_URL must use a loopback host");
        }
        requirePositive(properties.getPython().getConnectTimeout(), "AGENT_CONNECT_TIMEOUT");
        requirePositive(properties.getPython().getReadTimeout(), "AGENT_READ_TIMEOUT");
        requirePositive(properties.getPython().getStreamReadTimeout(), "AGENT_STREAM_READ_TIMEOUT");
        if (properties.getPython().getMaxResponseBytes() <= 0) {
            throw new IllegalStateException("AGENT_MAX_RESPONSE_BYTES must be positive");
        }
    }

    private void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException(name + " must be positive");
        }
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
    }

    private boolean isLoopbackHost(String host) {
        return "127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }
}
