package com.guanghe.backend.integration.internal.security;

import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisInternalNonceStoreTests {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisInternalNonceStore nonceStore;

    @BeforeEach
    void setUp() {
        InternalCommunicationProperties properties = new InternalCommunicationProperties();
        properties.setNonceKeyPrefix("zhixiaohe:test:internal:hmac:nonce:");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        nonceStore = new RedisInternalNonceStore(redisTemplate, properties);
    }

    @Test
    void usesAtomicSetIfAbsentWithTtlAndHashesTheNonce() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(120))))
                .thenReturn(true);

        assertThat(nonceStore.claim(
                "agent-service-v1",
                "00000000-0000-4000-8000-000000000001",
                Duration.ofSeconds(120)
        )).isTrue();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(key.capture(), eq("1"), eq(Duration.ofSeconds(120)));
        assertThat(key.getValue()).startsWith("zhixiaohe:test:internal:hmac:nonce:");
        assertThat(key.getValue()).doesNotContain("00000000-0000-4000-8000-000000000001");
    }

    @Test
    void duplicateReturnsFalseAndConnectionFailureIsClosed() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(120))))
                .thenReturn(false)
                .thenThrow(new RedisConnectionFailureException("unavailable"));

        assertThat(nonceStore.claim("key", "nonce", Duration.ofSeconds(120))).isFalse();
        assertThatThrownBy(() -> nonceStore.claim("key", "nonce-2", Duration.ofSeconds(120)))
                .isInstanceOf(InternalNonceStoreUnavailableException.class);
    }
}
