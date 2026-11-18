package com.guanghe.backend.common.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration(proxyBeanMethods = false)
@Profile("!mock")
public class AclSafeRedisHealthConfiguration {

    /**
     * The test Redis identity intentionally cannot execute INFO. Spring Boot's
     * default Redis indicator uses INFO and would therefore report DOWN even
     * when every application command is available. PING is sufficient for the
     * transport/authentication health check and preserves the least-privilege
     * ACL.
     */
    @Bean(name = "redisHealthContributor")
    HealthContributor redisHealthContributor(ObjectProvider<RedisConnectionFactory> connectionFactories) {
        HealthIndicator indicator = () -> {
            RedisConnectionFactory connectionFactory = connectionFactories.getIfAvailable();
            if (connectionFactory == null) {
                return Health.unknown()
                        .withDetail("reason", "Redis connection factory is not configured")
                        .build();
            }
            return ping(connectionFactory);
        };
        return indicator;
    }

    private Health ping(RedisConnectionFactory connectionFactory) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String response = connection.ping();
            if (response != null && "PONG".equalsIgnoreCase(response)) {
                return Health.up().build();
            }
            return Health.down().withDetail("reason", "Unexpected Redis PING response").build();
        } catch (RuntimeException exception) {
            return Health.down(exception).build();
        }
    }
}
