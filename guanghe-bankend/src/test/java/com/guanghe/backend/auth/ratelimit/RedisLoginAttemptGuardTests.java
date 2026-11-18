package com.guanghe.backend.auth.ratelimit;

import com.guanghe.backend.auth.config.LoginRateLimitProperties;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisLoginAttemptGuardTests {

    @Mock
    private StringRedisTemplate redis;

    @Test
    void redisOutageFailsClosed() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        var guard = new RedisLoginAttemptGuard(redis, new LoginRateLimitProperties(5, 900, "zhixiaohe:test:"));

        assertThatThrownBy(() -> guard.checkAllowed("test001", "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void thresholdResultIsRateLimited() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        var guard = new RedisLoginAttemptGuard(redis, new LoginRateLimitProperties(5, 900, "zhixiaohe:test:"));

        assertThatThrownBy(() -> guard.checkAllowed("test001", "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void redisKeysContainOnlyFingerprints() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(1);
            assertThat(keys).allMatch(key -> key.matches(
                    "zhixiaohe:test:auth:login-failure:(username|ip):[0-9a-f]{64}"));
            assertThat(keys).noneMatch(key -> key.contains("test001") || key.contains("127.0.0.1"));
            return 0L;
        });
        var guard = new RedisLoginAttemptGuard(redis, new LoginRateLimitProperties(5, 900, "zhixiaohe:test:"));

        guard.checkAllowed("test001", "127.0.0.1");
    }

    @Test
    void failureCounterReportsTheLimitingThreshold() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(5L);
        var guard = new RedisLoginAttemptGuard(redis, new LoginRateLimitProperties(5, 900, "zhixiaohe:test:"));

        assertThat(guard.recordFailure("test001", "127.0.0.1")).isTrue();
    }

    @Test
    void fiveFailuresFromOneClientDoNotLockEveryPublicClient() {
        Map<String, Long> counters = new HashMap<>();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            RedisScript<Long> script = invocation.getArgument(0);
            List<String> keys = invocation.getArgument(1);
            if (script.getScriptAsString().contains("INCR")) {
                long maximum = 0;
                for (String key : keys) {
                    long count = counters.merge(key, 1L, Long::sum);
                    maximum = Math.max(maximum, count);
                }
                return maximum;
            }
            return keys.stream().anyMatch(key -> counters.getOrDefault(key, 0L) >= 5L) ? 1L : 0L;
        });
        var guard = new RedisLoginAttemptGuard(redis, new LoginRateLimitProperties(5, 900, "zhixiaohe:test:"));

        for (int attempt = 0; attempt < 5; attempt++) {
            guard.recordFailure("arbitrary-user-" + attempt, "198.51.100.10");
        }

        assertThatThrownBy(() -> guard.checkAllowed("victim", "198.51.100.10"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
        guard.checkAllowed("victim", "203.0.113.20");
    }
}
