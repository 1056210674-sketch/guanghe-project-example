package com.guanghe.backend.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "zhixiaohe.auth.enabled=false",
        "zhixiaohe.internal.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
})
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "PUBLIC_TEST_DB_GATE_ENABLED", matches = "true")
class PublicTestDatabaseDevelopmentModeIntegrationTests {

    private static final String EXPECTED_DATABASE = "zhixiaohe_test";
    private static final String EXPECTED_RUNTIME_ROLE = "zhixiaohe_test_app";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectProvider<Flyway> flyway;

    @Autowired
    private Environment environment;

    @Test
    void startsWithoutFlywayCredentialsAndVerifiesPublicSslRuntimeAccess() throws Exception {
        assertThat(System.getenv("FLYWAY_DB_URL")).isNull();
        assertThat(System.getenv("FLYWAY_DB_USERNAME")).isNull();
        assertThat(System.getenv("FLYWAY_DB_PASSWORD")).isNull();
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("zhixiaohe.auth.enabled", Boolean.class)).isFalse();
        assertThat(flyway.stream()).isEmpty();

        try (Connection connection = dataSource.getConnection()) {
            verifyExactTargetBeforeDml(connection);
            verifyActualDml(connection);
        }

        assertCreateDenied();

        System.out.println("PUBLIC_TEST_DB database=" + EXPECTED_DATABASE);
        System.out.println("PUBLIC_TEST_DB transport=SSL required and active");
        System.out.println("PUBLIC_TEST_DB runtime_role=" + EXPECTED_RUNTIME_ROLE);
        System.out.println("PUBLIC_TEST_DB dml=SELECT,INSERT,UPDATE,DELETE allowed");
        System.out.println("PUBLIC_TEST_DB ddl=CREATE denied; runtime role owns no business tables");
        System.out.println("PUBLIC_TEST_DB ordinary_context_started=true flyway_enabled=false flyway_credentials=absent");
    }

    private void verifyExactTargetBeforeDml(Connection connection) throws SQLException {
        String runtimeUrl = required("APP_DB_URL");
        if (!runtimeUrl.startsWith("jdbc:postgresql://")
                || !runtimeUrl.contains("/" + EXPECTED_DATABASE)
                || !runtimeUrl.contains("sslmode=require")
                || runtimeUrl.contains("localhost")
                || runtimeUrl.contains("127.0.0.1")) {
            throw new IllegalStateException("APP_DB_URL must be the public SSL URL for the formal test database");
        }
        assertThat(required("APP_DB_USERNAME")).isEqualTo(EXPECTED_RUNTIME_ROLE);
        assertThat(scalar(connection, "SELECT current_database()")).isEqualTo(EXPECTED_DATABASE);
        assertThat(scalar(connection, "SELECT current_user")).isEqualTo(EXPECTED_RUNTIME_ROLE);
        assertThat(booleanScalar(connection,
                "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")).isTrue();
        assertThat(booleanScalar(connection,
                "SELECT NOT has_schema_privilege(current_user, 'business', 'CREATE')")).isTrue();
        assertThat(booleanScalar(connection, """
                SELECT NOT EXISTS (
                    SELECT 1 FROM pg_tables
                    WHERE schemaname IN ('business', 'agent') AND tableowner = current_user
                )
                """)).isTrue();
    }

    private void verifyActualDml(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        long probeId = -Math.abs(Instant.now().toEpochMilli());
        String marker = "public-db-gate-" + UUID.randomUUID();
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO business.sms_delivery_log
                        (id, user_id, phone_masked, phone_hash, purpose, provider_request_id, result_code, created_at)
                    VALUES (?, NULL, ?, ?, 'REGISTER', ?, 'GATE_INSERT', ?)
                    """)) {
                statement.setLong(1, probeId);
                statement.setString(2, "***gate");
                statement.setString(3, "0".repeat(64));
                statement.setString(4, marker);
                statement.setObject(5, OffsetDateTime.now(ZoneOffset.UTC));
                assertThat(statement.executeUpdate()).isOne();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT result_code FROM business.sms_delivery_log WHERE id = ?")) {
                statement.setLong(1, probeId);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("GATE_INSERT");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE business.sms_delivery_log SET result_code = 'GATE_UPDATE' WHERE id = ?")) {
                statement.setLong(1, probeId);
                assertThat(statement.executeUpdate()).isOne();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM business.sms_delivery_log WHERE id = ?")) {
                statement.setLong(1, probeId);
                assertThat(statement.executeUpdate()).isOne();
            }
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private void assertCreateDenied() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("CREATE TABLE business.public_development_gate_forbidden (id bigint PRIMARY KEY)");
                connection.rollback();
                throw new AssertionError("Runtime role unexpectedly created a table");
            } catch (SQLException exception) {
                connection.rollback();
                assertThat(exception.getSQLState()).isEqualTo("42501");
            }
        }
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private boolean booleanScalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the public test database Gate");
        }
        return value;
    }
}
