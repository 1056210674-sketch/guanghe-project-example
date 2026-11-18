package com.guanghe.backend.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executes real PostgreSQL constraints in an isolated, disposable Gate database. */
@EnabledIfEnvironmentVariable(named = "DATABASE_GATE_ENABLED", matches = "true")
class AssetMediaFormatMigrationGateIntegrationTests {
    @Test
    void upgradesExistingPngAssetsAndAcceptsJpegWithoutWeakeningOtherMediaChecks() throws Exception {
        String database = required("GATE_EXPECTED_DATABASE");
        DatabaseGateIntegrationTests.requireSafeExpectedDatabase(database);
        String url = required("GATE_MIGRATION_DB_URL");
        String user = required("GATE_MIGRATION_DB_USERNAME");
        String password = required("GATE_MIGRATION_DB_PASSWORD");
        Flyway baseline = flyway(url, user, password, "044");
        baseline.migrate();
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT current_database()")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(database);
            }
            sql.executeUpdate("""
                    INSERT INTO business.account(id,username,username_normalized,phone_e164,
                        password_hash,role,status,created_at,updated_at)
                    VALUES(951,'asset-format-gate','asset-format-gate',NULL,
                        repeat('a',32),'USER','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            sql.executeUpdate("""
                    INSERT INTO business.business_space(id,user_id,name,name_normalized,
                        brand_name,brand_profile,created_at,updated_at)
                    VALUES(952,951,'Gate','gate','Gate','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            sql.executeUpdate(asset(953, "IMAGE", "image/png"));
            assertRejected(sql, asset(954, "IMAGE", "image/jpeg"));
        }
        Flyway upgrade = flyway(url, user, password, "045");
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT content_type,source_type FROM business.asset WHERE id=953")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("image/png");
                assertThat(rows.getString(2)).isEqualTo("UPLOADED");
            }
            assertThat(sql.executeUpdate(asset(954, "IMAGE", "image/jpeg"))).isEqualTo(1);
            assertRejected(sql, asset(955, "IMAGE", "image/gif"));
            assertRejected(sql, asset(955, "IMAGE", "image/webp"));
            assertRejected(sql, asset(955, "IMAGE", "video/mp4"));
            assertRejected(sql, asset(955, "VIDEO", "image/jpeg"));
            assertThat(sql.executeUpdate(asset(955, "VIDEO", "video/mp4"))).isEqualTo(1);
        }
        System.out.println("ASSET_FORMAT_GATE baseline=044 target=045 png_preserved=true jpeg_accepted=true unsupported_rejected=true second_migrate=0");
    }

    private static void assertRejected(Statement sql, String statement) {
        assertThatThrownBy(() -> sql.executeUpdate(statement)).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
    }

    private static String asset(long id, String type, String mime) {
        String duration = "VIDEO".equals(type) ? "1" : "NULL";
        return "INSERT INTO business.asset(id,user_id,space_id,asset_type,source_type,name,object_key,"
                + "content_type,size_bytes,sha256,width,height,duration_seconds,status,safety_status,created_at,updated_at) "
                + "VALUES(" + id + ",951,952,'" + type + "','UPLOADED','gate','zhixiaohe/test/gate/"
                + id + "','" + mime + "',128,repeat('a',64),16,16," + duration
                + ",'READY','NOT_REVIEWED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
    }

    private static Flyway flyway(String url, String user, String password, String version) {
        return Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
                .schemas("business", "agent").defaultSchema("business").cleanDisabled(true)
                .target(version).load();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing Gate variable: " + name);
        return value;
    }
}
