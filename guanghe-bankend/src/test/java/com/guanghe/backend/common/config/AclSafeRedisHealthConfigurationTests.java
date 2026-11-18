package com.guanghe.backend.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AclSafeRedisHealthConfigurationTests {

    private final RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
    private final RedisConnection connection = mock(RedisConnection.class);
    private final AclSafeRedisHealthConfiguration configuration = new AclSafeRedisHealthConfiguration();

    @Test
    void reportsUpUsingAclSafePing() {
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        HealthContributor contributor = configuration.redisHealthContributor(providerWith(connectionFactory));

        assertThat(((HealthIndicator) contributor).health().getStatus()).isEqualTo(Status.UP);
        verify(connection).ping();
        verify(connection).close();
    }

    @Test
    void reportsDownWhenRedisIsUnavailable() {
        when(connectionFactory.getConnection())
                .thenThrow(new RedisConnectionFailureException("gate-unavailable"));

        HealthContributor contributor = configuration.redisHealthContributor(providerWith(connectionFactory));

        assertThat(((HealthIndicator) contributor).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenClosingTheRedisConnectionFails() {
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        doThrow(new RedisConnectionFailureException("close-unavailable"))
                .when(connection).close();

        HealthContributor contributor = configuration.redisHealthContributor(providerWith(connectionFactory));

        assertThat(((HealthIndicator) contributor).health().getStatus()).isEqualTo(Status.DOWN);
        verify(connection).close();
    }

    @Test
    void devProfileRegistersTheAclSafeContributorWhenRedisIsAvailable() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("dev");
            context.registerBean(RedisConnectionFactory.class, () -> connectionFactory);
            context.register(AclSafeRedisHealthConfiguration.class);
            context.refresh();

            assertThat(context.getBean("redisHealthContributor"))
                    .isInstanceOf(HealthContributor.class);
        }
    }

    @Test
    void devProfileWithoutRedisFactoryStartsAndReportsUnknown() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("dev");
            context.register(AclSafeRedisHealthConfiguration.class);
            context.refresh();

            HealthIndicator indicator = (HealthIndicator) context.getBean("redisHealthContributor");
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
        }
    }

    @Test
    void mockProfileDoesNotRequireARedisConnectionFactory() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("mock");
            context.register(AclSafeRedisHealthConfiguration.class);
            context.refresh();

            assertThat(context.containsBean("redisHealthContributor")).isFalse();
        }
    }

    private ObjectProvider<RedisConnectionFactory> providerWith(RedisConnectionFactory factory) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("redisConnectionFactory", factory);
        return beanFactory.getBeanProvider(RedisConnectionFactory.class);
    }
}
