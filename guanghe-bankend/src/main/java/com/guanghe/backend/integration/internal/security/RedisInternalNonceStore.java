package com.guanghe.backend.integration.internal.security;

import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisInternalNonceStore implements InternalNonceStore {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public RedisInternalNonceStore(
            StringRedisTemplate redisTemplate,
            InternalCommunicationProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = properties.getNonceKeyPrefix();
    }

    @Override
    public boolean claim(String keyId, String nonce, Duration ttl) {
        String key = keyPrefix + sha256Hex(keyId + ":" + nonce);
        try {
            Boolean claimed = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
            if (claimed == null) {
                throw new InternalNonceStoreUnavailableException();
            }
            return claimed;
        } catch (RedisConnectionFailureException exception) {
            throw new InternalNonceStoreUnavailableException(exception);
        } catch (RuntimeException exception) {
            if (exception instanceof InternalNonceStoreUnavailableException unavailable) {
                throw unavailable;
            }
            throw new InternalNonceStoreUnavailableException(exception);
        }
    }

    private String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
