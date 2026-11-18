package com.guanghe.backend.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "POSTGRES_CONNECTION_GATE_ENABLED", matches = "true")
class DocumentedPostgresqlConnectionGateIntegrationTests {

    @Test
    void documentedIdentityConnectsToPostgresql18WithSsl() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                required("POSTGRES_GATE_DB_URL"),
                required("POSTGRES_GATE_DB_USERNAME"),
                required("POSTGRES_GATE_DB_PASSWORD"));
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT current_setting('server_version_num'),
                            ssl
                     FROM pg_stat_ssl
                     WHERE pid = pg_backend_pid()
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(Integer.parseInt(result.getString(1))).isBetween(180000, 189999);
            assertThat(result.getBoolean(2)).isTrue();
            assertThat(connection.getCatalog()).isEqualTo(required("POSTGRES_GATE_EXPECTED_DATABASE"));
            assertThat(connection.getMetaData().getUserName()).isEqualTo(required("POSTGRES_GATE_DB_USERNAME"));
            System.out.println("POSTGRES_GATE documented_identity_authenticated=yes");
            System.out.println("POSTGRES_GATE ssl=yes,server=PostgreSQL-18");
        }
    }

    @Test
    void runtimeIdentityCannotUseAPlaintextPostgresqlConnection() {
        String sslUrl = required("POSTGRES_GATE_DB_URL");
        if (!sslUrl.contains("sslmode=require")) {
            throw new IllegalStateException("PostgreSQL Gate URL must explicitly require SSL");
        }
        String plaintextUrl = sslUrl.replace("sslmode=require", "sslmode=disable");
        assertThatThrownBy(() -> DriverManager.getConnection(
                plaintextUrl,
                required("POSTGRES_GATE_DB_USERNAME"),
                required("POSTGRES_GATE_DB_PASSWORD")))
                .isInstanceOf(SQLException.class);
        System.out.println("POSTGRES_GATE plaintext_runtime_connection=rejected");
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the PostgreSQL connection Gate");
        }
        return value;
    }
}
