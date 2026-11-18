package com.guanghe.backend.infrastructure;

import com.guanghe.backend.integration.internal.config.InternalCommunicationProperties;
import com.guanghe.backend.integration.internal.security.InternalNonceStoreUnavailableException;
import com.guanghe.backend.integration.internal.security.RedisInternalNonceStore;
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
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "REMOTE_INTERNAL_HMAC_GATE_ENABLED", matches = "true")
class RemoteInternalHmacNonceIntegrationTests {

    @Test
    void realTlsRedisAtomicallyRejectsReplayAndAppliesAtLeast120SecondTtl() throws Exception {
        String rootPrefix = required("REDIS_GATE_KEY_PREFIX");
        if (!rootPrefix.startsWith("zhixiaohe:test:") || !rootPrefix.endsWith(":")) {
            throw new IllegalStateException("Redis Gate must use the zhixiaohe:test: namespace");
        }
        String prefix = rootPrefix + "gate:m1:" + UUID.randomUUID() + ":";
        String keyId = "agent-service-v1";
        String nonce = UUID.randomUUID().toString();
        Duration ttl = Duration.ofSeconds(120);

        try (RedisFixture fixture = connect(
                required("REDIS_GATE_HOST"),
                Integer.parseInt(required("REDIS_GATE_PORT")),
                required("REDIS_GATE_USERNAME"),
                required("REDIS_GATE_PASSWORD"),
                true
        )) {
            InternalCommunicationProperties properties = new InternalCommunicationProperties();
            properties.setNonceKeyPrefix(prefix);
            RedisInternalNonceStore store = new RedisInternalNonceStore(fixture.template(), properties);
            String actualKey = prefix + sha256Hex(keyId + ":" + nonce);
            try {
                assertThat(store.claim(keyId, nonce, ttl)).isTrue();
                assertThat(store.claim(keyId, nonce, ttl)).isFalse();
                assertThat(fixture.template().getExpire(actualKey, TimeUnit.SECONDS)).isBetween(1L, 120L);
            } finally {
                fixture.template().delete(actualKey);
            }
        }
    }

    @Test
    void unreachableRedisFailsClosed() {
        try (RedisFixture fixture = connect("127.0.0.1", 1, "unavailable", "unavailable", false)) {
            InternalCommunicationProperties properties = new InternalCommunicationProperties();
            properties.setNonceKeyPrefix("zhixiaohe:test:gate:m1-unavailable:");
            RedisInternalNonceStore store = new RedisInternalNonceStore(fixture.template(), properties);
            assertThatThrownBy(() -> store.claim("key", UUID.randomUUID().toString(), Duration.ofSeconds(120)))
                    .isInstanceOf(InternalNonceStoreUnavailableException.class);
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

    private String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the remote HMAC Gate");
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
