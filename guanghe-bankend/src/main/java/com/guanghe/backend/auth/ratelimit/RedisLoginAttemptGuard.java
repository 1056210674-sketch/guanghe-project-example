package com.guanghe.backend.auth.ratelimit;

import com.guanghe.backend.auth.config.LoginRateLimitProperties;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisLoginAttemptGuard implements LoginAttemptGuard {

    private static final DefaultRedisScript<Long> CHECK_SCRIPT = new DefaultRedisScript<>("""
            local maximum = tonumber(ARGV[1])
            local by_username = tonumber(redis.call('GET', KEYS[1])) or 0
            local by_ip = tonumber(redis.call('GET', KEYS[2])) or 0
            if by_username >= maximum or by_ip >= maximum then return 1 end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> FAILURE_SCRIPT = new DefaultRedisScript<>("""
            local window = tonumber(ARGV[1])
            local maximum = 0
            for _, key in ipairs(KEYS) do
                local count = redis.call('INCR', key)
                if count == 1 then redis.call('EXPIRE', key, window) end
                if count > maximum then maximum = count end
            end
            return maximum
            """, Long.class);

    private final StringRedisTemplate redis;
    private final LoginRateLimitProperties properties;

    public RedisLoginAttemptGuard(StringRedisTemplate redis, LoginRateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void checkAllowed(String usernameNormalized, String sourceIp) {
        try {
            Long blocked = redis.execute(
                    CHECK_SCRIPT,
                    keys(usernameNormalized, sourceIp),
                    Integer.toString(properties.maxFailures())
            );
            if (blocked == null) {
                throw dependencyUnavailable();
            }
            if (blocked == 1L) {
                throw new BusinessException(ErrorCode.RATE_LIMITED);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw dependencyUnavailable();
        }
    }

    @Override
    public boolean recordFailure(String usernameNormalized, String sourceIp) {
        try {
            Long failures = redis.execute(
                    FAILURE_SCRIPT,
                    keys(usernameNormalized, sourceIp),
                    Long.toString(properties.windowSeconds())
            );
            if (failures == null) {
                throw dependencyUnavailable();
            }
            return failures >= properties.maxFailures();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw dependencyUnavailable();
        }
    }

    @Override
    public void clear(String usernameNormalized, String sourceIp) {
        try {
            redis.delete(usernameKey(usernameNormalized));
        } catch (RuntimeException exception) {
            throw dependencyUnavailable();
        }
    }

    private List<String> keys(String usernameNormalized, String sourceIp) {
        return List.of(
                usernameKey(usernameNormalized),
                properties.keyPrefix() + "auth:login-failure:ip:"
                        + sha256(sourceIp == null ? "unknown" : sourceIp)
        );
    }

    private String usernameKey(String usernameNormalized) {
        return properties.keyPrefix() + "auth:login-failure:username:" + sha256(usernameNormalized);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private BusinessException dependencyUnavailable() {
        return new BusinessException(ErrorCode.AUTHENTICATION_DEPENDENCY_UNAVAILABLE);
    }
}
