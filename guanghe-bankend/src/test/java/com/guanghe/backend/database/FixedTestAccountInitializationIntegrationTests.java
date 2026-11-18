package com.guanghe.backend.database;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TEST_ACCOUNT_INIT_ENABLED", matches = "true")
class FixedTestAccountInitializationIntegrationTests {

    private static final String EXPECTED_DATABASE = "zhixiaohe_test";
    private static final String EXPECTED_MIGRATION_ROLE = "zhixiaohe_test_flyway";

    @Test
    void initializesFixedAccountsOnlyInTheExactFormalTestDatabase() throws Exception {
        String expectedDatabase = required("TEST_ACCOUNT_EXPECTED_DATABASE");
        String url = required("TEST_ACCOUNT_DB_URL");
        String username = required("TEST_ACCOUNT_DB_USERNAME");
        String password = required("TEST_ACCOUNT_DB_PASSWORD");
        requireExactTestTarget(expectedDatabase, username);

        String script = new ClassPathResource("db/testdata/fixed_test_accounts.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        List<AccountAudit> first;
        List<AccountAudit> second;
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try {
                verifyConnectionBeforeMutation(connection, expectedDatabase, username);
                execute(connection, script);
                first = auditedAccounts(connection);
                execute(connection, script);
                second = auditedAccounts(connection);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }

        assertThat(first).containsExactly(
                new AccountAudit("guanghe888", "ADMIN", "ACTIVE"),
                new AccountAudit("test001", "USER", "ACTIVE")
        );
        assertThat(second).containsExactlyElementsOf(first);
        System.out.println("TEST_ACCOUNT_INIT database=" + EXPECTED_DATABASE);
        System.out.println("TEST_ACCOUNT_INIT executions=2 idempotent=yes");
        System.out.println("TEST_ACCOUNT_INIT accounts=" + second);
    }

    static void requireExactTestTarget(String expectedDatabase, String username) {
        if (!EXPECTED_DATABASE.equals(expectedDatabase)) {
            throw new IllegalStateException("Fixed accounts may only target " + EXPECTED_DATABASE);
        }
        if (!EXPECTED_MIGRATION_ROLE.equals(username)) {
            throw new IllegalStateException("Fixed accounts require the dedicated test migration role");
        }
    }

    private void verifyConnectionBeforeMutation(Connection connection, String expectedDatabase, String expectedUser)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT current_database(), current_user,
                            current_setting('server_version_num')::integer,
                            COALESCE((SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()), false)
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo(expectedDatabase);
            assertThat(result.getString(2)).isEqualTo(expectedUser);
            assertThat(result.getInt(3)).isBetween(180000, 189999);
            assertThat(result.getBoolean(4)).isTrue();
        }
    }

    private List<AccountAudit> auditedAccounts(Connection connection) throws Exception {
        List<AccountAudit> accounts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT username_normalized, role, status
                  FROM business.account
                 WHERE username_normalized IN ('guanghe888', 'test001')
                 ORDER BY username_normalized
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                accounts.add(new AccountAudit(result.getString(1), result.getString(2), result.getString(3)));
            }
        }
        return accounts;
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for fixed test-account initialization");
        }
        return value;
    }

    private record AccountAudit(String usernameNormalized, String role, String status) {
    }
}
