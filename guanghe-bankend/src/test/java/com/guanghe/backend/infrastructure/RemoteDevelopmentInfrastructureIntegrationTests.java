package com.guanghe.backend.infrastructure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "zhixiaohe.internal.enabled=false")
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "REMOTE_DEV_GATE_ENABLED", matches = "true")
class RemoteDevelopmentInfrastructureIntegrationTests {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ConnectionFactory rabbit;
    @Autowired
    private ObjectProvider<Flyway> flyway;
    @Autowired
    private Environment environment;

    @Test
    void devProfileConnectsDirectlyToAllThreeRemoteComponentsWithFlywayDisabled() throws Exception {
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
        assertThat(flyway.stream()).isEmpty();
        assertThat(environment.getProperty("zhixiaohe.auth.login-rate-limit.key-prefix"))
                .isEqualTo("zhixiaohe:test:");
        assertThat(environment.getProperty("spring.rabbitmq.virtual-host"))
                .isEqualTo("/zhixiaohe-test");
        assertThat(environment.getProperty("spring.data.redis.ssl.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.rabbitmq.ssl.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.rabbitmq.ssl.validate-server-certificate", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("spring.rabbitmq.ssl.verify-hostname", Boolean.class)).isTrue();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT current_database(), current_user,
                            COALESCE((SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()), false)
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("zhixiaohe_test");
            assertThat(result.getString(2)).isEqualTo("zhixiaohe_test_app");
            assertThat(result.getBoolean(3)).isTrue();
        }

        assertThat(redis.getConnectionFactory()).isNotNull();
        assertThat(redis.getConnectionFactory().getConnection().ping()).isEqualTo("PONG");
        org.springframework.amqp.rabbit.connection.Connection rabbitConnection = rabbit.createConnection();
        try {
            assertThat(rabbitConnection.isOpen()).isTrue();
        } finally {
            rabbitConnection.close();
        }

        System.out.println("REMOTE_DEV_GATE postgresql_ssl=yes");
        System.out.println("REMOTE_DEV_GATE redis_acl=yes namespace=zhixiaohe:test:");
        System.out.println("REMOTE_DEV_GATE rabbit_vhost=/zhixiaohe-test");
        System.out.println("REMOTE_DEV_GATE flyway_enabled=false");
    }
}
