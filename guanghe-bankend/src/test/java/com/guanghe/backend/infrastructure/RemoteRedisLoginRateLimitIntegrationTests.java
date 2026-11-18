package com.guanghe.backend.infrastructure;

import com.guanghe.backend.auth.config.LoginRateLimitProperties;
import com.guanghe.backend.auth.ratelimit.RedisLoginAttemptGuard;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "REMOTE_REDIS_GATE_ENABLED", matches = "true")
class RemoteRedisLoginRateLimitIntegrationTests {

    @Test
    void realRedisExecutesFailureLimitTtlAndSuccessfulLoginCleanup() throws Exception {
        assertThat(required("REDIS_GATE_SSL_ENABLED")).isEqualToIgnoringCase("true");
        String rootPrefix = required("REDIS_GATE_KEY_PREFIX");
        if (!rootPrefix.startsWith("zhixiaohe:test:") || !rootPrefix.endsWith(":")) {
            throw new IllegalStateException("Redis Gate must use the zhixiaohe:test: namespace");
        }
        String prefix = rootPrefix + "gate:" + UUID.randomUUID() + ":";
        String username = "rate-limit-gate";
        String sourceIp = "192.0.2.10";
        int windowSeconds = 30;

        try (RedisFixture fixture = connect(
                required("REDIS_GATE_HOST"),
                Integer.parseInt(required("REDIS_GATE_PORT")),
                required("REDIS_GATE_USERNAME"),
                required("REDIS_GATE_PASSWORD"),
                true
        )) {
            assertThat(aclWhoAmI(fixture.template())).isEqualTo(required("REDIS_GATE_USERNAME"));
            String aclAllowedKey = prefix + "acl-allowed";
            fixture.template().opsForValue().set(aclAllowedKey, "allowed");
            assertThat(fixture.template().opsForValue().get(aclAllowedKey)).isEqualTo("allowed");
            assertThatThrownBy(() -> fixture.template().opsForValue()
                    .set("zhixiaohe:forbidden:remote-gate", "blocked"))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("NOPERM");
            fixture.template().delete(aclAllowedKey);

            var guard = new RedisLoginAttemptGuard(
                    fixture.template(),
                    new LoginRateLimitProperties(3, windowSeconds, prefix)
            );
            String usernameKey = prefix + "auth:login-failure:username:" + sha256(username);
            String ipKey = prefix + "auth:login-failure:ip:" + sha256(sourceIp);
            try {
                guard.checkAllowed(username, sourceIp);
                assertThat(guard.recordFailure(username, sourceIp)).isFalse();
                assertThat(guard.recordFailure(username, sourceIp)).isFalse();
                assertThat(guard.recordFailure(username, sourceIp)).isTrue();
                assertThatThrownBy(() -> guard.checkAllowed(username, sourceIp))
                        .isInstanceOfSatisfying(BusinessException.class, exception ->
                                assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));

                Long usernameTtl = fixture.template().getExpire(usernameKey, TimeUnit.SECONDS);
                Long ipTtl = fixture.template().getExpire(ipKey, TimeUnit.SECONDS);
                assertThat(usernameTtl).isBetween(1L, (long) windowSeconds);
                assertThat(ipTtl).isBetween(1L, (long) windowSeconds);

                guard.clear(username, sourceIp);
                assertThat(fixture.template().hasKey(usernameKey)).isFalse();
                assertThat(fixture.template().hasKey(ipKey)).isTrue();
            } finally {
                fixture.template().delete(usernameKey);
                fixture.template().delete(ipKey);
            }
        }
    }

    @Test
    void unreachableRedisMapsTo503FailClosedError() {
        try (RedisFixture fixture = connect("127.0.0.1", 1, "unavailable", "unavailable", false)) {
            var guard = new RedisLoginAttemptGuard(
                    fixture.template(),
                    new LoginRateLimitProperties(3, 30, "zhixiaohe:test:unavailable:")
            );
            assertThatThrownBy(() -> guard.checkAllowed("test001", "127.0.0.1"))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.AUTHENTICATION_DEPENDENCY_UNAVAILABLE);
                        assertThat(exception.errorCode().status().value()).isEqualTo(503);
                    });
        }
    }

    @Test
    void plaintextConnectionToTlsOnlyPortIsRejected() {
        try (RedisFixture fixture = connect(
                required("REDIS_GATE_HOST"),
                Integer.parseInt(required("REDIS_GATE_PORT")),
                required("REDIS_GATE_USERNAME"),
                required("REDIS_GATE_PASSWORD"),
                false
        )) {
            assertThatThrownBy(() -> fixture.template().getConnectionFactory().getConnection().ping())
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private RedisFixture connect(String host, int port, String username, String password, boolean ssl) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        standalone.setUsername(username);
        standalone.setPassword(password);
        ClientOptions.Builder options = ClientOptions.builder()
                .protocolVersion(ProtocolVersion.RESP2)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(3)).build());
        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientBuilder =
                LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3))
                .shutdownTimeout(Duration.ZERO);
        if (ssl) {
            options.sslOptions(SslOptions.builder()
                    .jdkSslProvider()
                    .trustManager(TestTlsSupport.caCertificateFile())
                    .build());
            clientBuilder.useSsl().verifyPeer(SslVerifyMode.FULL).and();
        }
        LettuceClientConfiguration client = clientBuilder.clientOptions(options.build()).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return new RedisFixture(factory, template);
    }

    private String aclWhoAmI(StringRedisTemplate template) {
        Object value = template.execute((RedisCallback<Object>) connection ->
                connection.execute("ACL", "WHOAMI".getBytes(StandardCharsets.UTF_8)));
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the remote Redis Gate");
        }
        return value;
    }

    private record RedisFixture(LettuceConnectionFactory factory, StringRedisTemplate template)
            implements AutoCloseable {

        @Override
        public void close() {
            factory.destroy();
        }
    }
}
