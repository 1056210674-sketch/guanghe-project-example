package com.guanghe.backend.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M8 migration Gate.
 *
 * <p>Runs against two throwaway databases created for this run only, both named with the
 * {@code zhixiaohe_gate_} prefix and dropped by the runner afterwards. Neither is the shared test
 * database and neither is the production database.
 *
 * <p>What it establishes, in the order the Gate requires:
 *
 * <ol>
 *   <li>an empty database migrates V001 through V040 in one run;</li>
 *   <li>a database holding data at V027 upgrades through V028, V032 and V033 to V040,
 *       and the data that existed before those migrations still satisfies the constraints they
 *       install;</li>
 *   <li>migrating a second time executes nothing, in both databases;</li>
 *   <li>Flyway history, constraints, the V029 backfill and the resulting NOT NULL columns are
 *       read back from the catalogue rather than assumed;</li>
 *   <li>the runner deletes both databases; this class never does.</li>
 * </ol>
 *
 * <p>The assertions are about the schema, not about the application: this Gate answers "does the
 * migration set produce the database the code expects", which is the question a deployment asks
 * before it ever starts.
 */
@EnabledIfEnvironmentVariable(named = "DATABASE_GATE_ENABLED", matches = "true")
class CommercialMigrationGateIntegrationTests {

    private static final String LATEST_VERSION = "040";
    private static final int TOTAL_MIGRATIONS = 40;

    /** Tables V028, V029 and V030 add. Named individually so a silently dropped one fails here. */
    private static final Set<String> M8_TABLES = Set.of(
            "recharge_order",
            "recharge_order_event",
            "manual_point_adjustment",
            "operational_audit_event",
            "invoice_application",
            "invoice_application_event",
            "user_notification",
            "pricing_version",
            "pricing_version_tier",
            "commercial_rate_limit",
            "user_feedback"
    );

    @Test
    void migratesAnEmptyDatabaseToTheLatestVersionAndReRunningChangesNothing() throws Exception {
        String database = requiredEnvironment("GATE_EMPTY_EXPECTED_DATABASE");
        DatabaseGateIntegrationTests.requireSafeExpectedDatabase(database);
        String url = requiredEnvironment("GATE_EMPTY_DB_URL");
        String user = requiredEnvironment("GATE_EMPTY_DB_USERNAME");
        String password = requiredEnvironment("GATE_EMPTY_DB_PASSWORD");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertThat(currentDatabase(connection)).isEqualTo(database);
        }

        Flyway flyway = flyway(url, user, password);
        MigrateResult firstRun = flyway.migrate();
        flyway.validate();
        MigrateResult secondRun = flyway.migrate();

        assertThat(firstRun.migrationsExecuted).as("migrations executed on an empty database")
                .isEqualTo(TOTAL_MIGRATIONS);
        assertThat(firstRun.targetSchemaVersion).isEqualTo(LATEST_VERSION);
        assertThat(secondRun.migrationsExecuted).as("second migrate() must execute nothing").isZero();
        // A run that migrated nothing reports no target version, so the version the database is
        // actually on is read back from the schema history instead.
        assertThat(secondRun.targetSchemaVersion).isNull();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo(LATEST_VERSION);
        assertThat(flyway.info().pending()).isEmpty();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertHistoryIsCompleteAndSuccessful(connection);
            assertM8TablesExist(connection);
            assertM8ConstraintsExist(connection);
            assertM8NotNullColumns(connection);
            assertM8UniqueIndexesExist(connection);
            assertOperatorConsoleIndexesExist(connection);
            assertAgentVersionConstraintsExist(connection);
            assertAgentVersionIndexesExist(connection);
            assertImageCandidateConstraintsExist(connection);
            assertPublicationWithoutArticleUrlIsAllowed(connection);
            assertConversationAdmissionSurvivesDeletion(connection);
            assertM8ConstraintBehaviour(connection, url, user, password);
        }

        System.out.println("M8_GATE scenario=empty_database");
        System.out.println("M8_GATE flyway_first_run=" + firstRun.migrationsExecuted
                + " target=" + firstRun.targetSchemaVersion);
        System.out.println("M8_GATE flyway_second_run=" + secondRun.migrationsExecuted + " (no-op)");
        System.out.println("M8_GATE m8_tables=" + new TreeSet<>(M8_TABLES));
        System.out.println("M8_GATE constraint_behaviour=verified");
    }

    /**
     * The path a deployment that is already live has to take.
     *
     * <p>Data is written at each stop so the upgrade is exercised over rows that exist rather than
     * over an empty table: V028 replaces two closed CHECK constraints on {@code point_ledger}, and
     * V029 backfills a column and then makes it NOT NULL. Both are the kind of statement that works
     * on a developer's empty database and fails on a populated one.
     */
    @Test
    void upgradesAVersion027DatabaseCarryingExistingRowsToTheLatestVersion() throws Exception {
        String database = requiredEnvironment("GATE_UPGRADE_EXPECTED_DATABASE");
        DatabaseGateIntegrationTests.requireSafeExpectedDatabase(database);
        String url = requiredEnvironment("GATE_UPGRADE_DB_URL");
        String user = requiredEnvironment("GATE_UPGRADE_DB_USERNAME");
        String password = requiredEnvironment("GATE_UPGRADE_DB_PASSWORD");

        long userId = 981000000000000001L;
        long legacyLedgerId = 981000000000000002L;
        long adjustmentId = 981000000000000003L;

        MigrateResult baselineRun = targetedFlyway(url, user, password, "027").migrate();
        assertThat(baselineRun.migrationsExecuted).as("V001..V027 on an empty database")
                .isEqualTo(27);
        assertThat(baselineRun.targetSchemaVersion).isEqualTo("027");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertThat(currentDatabase(connection)).isEqualTo(database);
            execute(connection, """
                    INSERT INTO business.account(
                        id,username,username_normalized,phone_e164,password_hash,role,status,
                        created_at,updated_at
                    ) VALUES (
                        981000000000000001,'m8-upgrade-gate','m8-upgrade-gate','+8600000000981',
                        repeat('a',32),'USER','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            // A ledger row of a type V028 keeps. If the replacement constraint were narrower than
            // the one it drops, this row would become unrepresentable and the upgrade would strand it.
            execute(connection, """
                    INSERT INTO business.point_ledger(
                        id,user_id,available_delta,frozen_delta,available_after,frozen_after,
                        entry_type,business_type,business_id,idempotency_key,created_at
                    ) VALUES (
                        981000000000000002,981000000000000001,0,-11,89,0,
                        'TASK_FREEZE','TASK',981000000000000009,
                        'm8-gate-legacy-ledger',CURRENT_TIMESTAMP
                    )
                    """);
        }

        MigrateResult v028 = targetedFlyway(url, user, password, "028").migrate();
        assertThat(v028.migrationsExecuted).isEqualTo(1);
        assertThat(v028.targetSchemaVersion).isEqualTo("028");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            // Written at V028, i.e. before V029 adds confirmation_expires_at. This is the row V029
            // has to backfill, and it is the reason the backfill exists at all.
            execute(connection, """
                    INSERT INTO business.manual_point_adjustment(
                        id,user_id,points,direction,reason,operator_id,idempotency_key,request_hash,
                        confirmation_token_hash,status,created_at,updated_at
                    ) VALUES (
                        981000000000000003,981000000000000001,100,'CREDIT','m8 gate legacy row',
                        981000000000000001,'m8-gate-legacy-adjustment',repeat('b',64),repeat('c',64),
                        'PENDING_CONFIRMATION',CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes'
                    )
                    """);
        }

        MigrateResult v032 = targetedFlyway(url, user, password, "032").migrate();
        assertThat(v032.migrationsExecuted).as("V029..V032 on a populated V028 database").isEqualTo(4);
        assertThat(v032.targetSchemaVersion).isEqualTo("032");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            insertVersion032Assets(connection);
        }

        MigrateResult v033 = targetedFlyway(url, user, password, "033").migrate();
        assertThat(v033.migrationsExecuted).isEqualTo(1);
        assertThat(v033.targetSchemaVersion).isEqualTo("033");

        Flyway latest = flyway(url, user, password);
        MigrateResult upgradeRun = latest.migrate();
        latest.validate();
        MigrateResult noOpRun = latest.migrate();

        assertThat(upgradeRun.migrationsExecuted)
                .as("V034..V040 on a populated V033 database")
                .isEqualTo(7);
        assertThat(upgradeRun.targetSchemaVersion).isEqualTo(LATEST_VERSION);
        assertThat(noOpRun.migrationsExecuted).as("second migrate() after the upgrade").isZero();
        assertThat(latest.info().current().getVersion().toString()).isEqualTo(LATEST_VERSION);

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertHistoryIsCompleteAndSuccessful(connection);
            assertM8TablesExist(connection);
            assertM8ConstraintsExist(connection);
            assertM8NotNullColumns(connection);
            assertM8UniqueIndexesExist(connection);
            assertOperatorConsoleIndexesExist(connection);
            assertAgentVersionConstraintsExist(connection);
            assertAgentVersionIndexesExist(connection);
            assertImageCandidateConstraintsExist(connection);
            assertPublicationWithoutArticleUrlIsAllowed(connection);
            assertConversationAdmissionSurvivesDeletion(connection);
            assertThat(scalarInt(connection, """
                    SELECT count(*) FROM business.asset WHERE id=983000000000000105
                      AND source_task_id=983000000000000104 AND source_artifact_index=1
                      AND source_type='GENERATED' AND sha256=repeat('4',64) AND status='READY'
                    """)).as("V033 preserves the historical generated image and backfills slot one").isEqualTo(1);
            assertThat(scalarInt(connection, """
                    SELECT count(*) FROM business.asset WHERE id=983000000000000106
                      AND source_task_id IS NULL AND source_artifact_index IS NULL
                      AND source_type='UPLOADED' AND sha256=repeat('5',64) AND status='READY'
                    """)).as("V033 preserves a historical upload without a source task or slot").isEqualTo(1);

            assertThat(scalarString(connection, "SELECT entry_type FROM business.point_ledger"
                    + " WHERE id=" + legacyLedgerId)).as("row predating V028")
                    .isEqualTo("TASK_FREEZE");
            assertThat(scalarInt(connection, "SELECT count(*) FROM business.point_ledger"
                    + " WHERE id=" + legacyLedgerId + " AND available_delta=0 AND frozen_delta=-11"))
                    .as("row predating V028 still satisfies the constraint V028 installs").isEqualTo(1);

            String backfilled = scalarString(connection, """
                    SELECT to_char(confirmation_expires_at - created_at, 'HH24:MI:SS')
                    FROM business.manual_point_adjustment WHERE id=981000000000000003
                    """);
            assertThat(backfilled).as("V029 backfills ten minutes from created_at")
                    .isEqualTo("00:10:00");
            assertThat(scalarInt(connection, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='business' AND table_name='manual_point_adjustment'
                      AND column_name='confirmation_expires_at' AND is_nullable='NO'
                    """)).as("V029 then makes the column NOT NULL").isEqualTo(1);

            // The widened constraints accept the M8 entry types and still refuse an unknown one.
            execute(connection, """
                    INSERT INTO business.point_ledger(
                        id,user_id,available_delta,frozen_delta,available_after,frozen_after,
                        entry_type,business_type,business_id,idempotency_key,created_at
                    ) VALUES (
                        981000000000000004,981000000000000001,1000,0,1089,0,
                        'RECHARGE','RECHARGE',981000000000000010,
                        'm8-gate-recharge-ledger',CURRENT_TIMESTAMP
                    )
                    """);
            // A recharge that touches frozen points is refused: the freeze/settle/release
            // invariants of the paid-task ledger are what the widened constraint protects.
            assertSqlState(connection, "23514", """
                    INSERT INTO business.point_ledger(
                        id,user_id,available_delta,frozen_delta,available_after,frozen_after,
                        entry_type,business_type,business_id,idempotency_key,created_at
                    ) VALUES (
                        981000000000000005,981000000000000001,1000,-1000,2089,1000,
                        'RECHARGE','RECHARGE',981000000000000011,
                        'm8-gate-recharge-frozen',CURRENT_TIMESTAMP
                    )
                    """);
            // And an entry type nobody widened the constraint for is still refused, so "widened"
            // did not become "accepts anything".
            assertSqlState(connection, "23514", """
                    INSERT INTO business.point_ledger(
                        id,user_id,available_delta,frozen_delta,available_after,frozen_after,
                        entry_type,business_type,business_id,idempotency_key,created_at
                    ) VALUES (
                        981000000000000006,981000000000000001,1000,0,3089,0,
                        'M8_GATE_UNKNOWN','TASK',981000000000000012,
                        'm8-gate-unknown-ledger',CURRENT_TIMESTAMP
                    )
                    """);
        }

        System.out.println("M8_GATE scenario=upgrade_from_027");
        System.out.println("M8_GATE flyway_baseline=" + baselineRun.migrationsExecuted
                + " target=" + baselineRun.targetSchemaVersion);
        System.out.println("M8_GATE flyway_v028=" + v028.migrationsExecuted);
        System.out.println("M8_GATE flyway_v032=" + v032.migrationsExecuted);
        System.out.println("M8_GATE flyway_upgrade=" + upgradeRun.migrationsExecuted
                + " target=" + upgradeRun.targetSchemaVersion);
        System.out.println("M8_GATE flyway_second_run=" + noOpRun.migrationsExecuted + " (no-op)");
        System.out.println("M8_GATE backfill_confirmation_expires_at=created_at+00:10:00");
        System.out.println("M8_GATE preexisting_row_survives_constraint_replacement=verified");
        System.out.println("M8_GATE v033_generated_asset_slot_backfill_and_null_source=verified");
    }

    private void insertVersion032Assets(Connection connection) throws SQLException {
        // No source_artifact_index exists yet; these are persisted using the V032 row shape.
        execute(connection, """
                INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,created_at,updated_at)
                VALUES (983000000000000101,981000000000000001,'v033-space','v033-space','gate',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO business.conversation(id,user_id,title,created_at,updated_at)
                VALUES (983000000000000102,981000000000000001,'v033-upgrade',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO business.task_draft(
                    id,user_id,conversation_id,draft_source,operation_scope,idempotency_key,request_hash,
                    task_type,parameter_schema_version,space_id,parameters,input_snapshot,input_hash,
                    pricing_version_id,quoted_points,status,expires_at,created_at,updated_at
                ) VALUES (
                    983000000000000103,981000000000000001,983000000000000102,'FORM','TASK_DRAFT_CREATE',
                    '33000000-0000-0000-0000-000000000001',repeat('1',64),'IMAGE',1,983000000000000101,
                    '{}','{}',repeat('2',64),7001,7,'CONFIRMED',CURRENT_TIMESTAMP+INTERVAL '5 minutes',
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                INSERT INTO business.business_task(
                    id,user_id,space_id,conversation_id,draft_id,task_type,status,stage_code,
                    snapshot_schema_version,input_snapshot,input_hash,pricing_version_id,quoted_points,
                    settled_points,operation_scope,idempotency_key,request_hash,queued_at,created_at,updated_at
                ) VALUES (
                    983000000000000104,981000000000000001,983000000000000101,983000000000000102,
                    983000000000000103,'IMAGE','SUCCEEDED','COMPLETED',1,'{}',repeat('3',64),7001,7,7,
                    'TASK_CONFIRM','33000000-0000-0000-0000-000000000002',repeat('3',64),
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                INSERT INTO business.asset(
                    id,user_id,space_id,asset_type,source_type,source_task_id,name,object_key,
                    content_type,size_bytes,sha256,width,height,status,safety_status,created_at,updated_at
                ) VALUES
                    (983000000000000105,981000000000000001,983000000000000101,'IMAGE','GENERATED',
                     983000000000000104,'legacy-generated.png','zhixiaohe/gate/v033/legacy-generated.png',
                     'image/png',88,repeat('4',64),24,18,'READY','TEST_BYPASS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                    (983000000000000106,981000000000000001,983000000000000101,'IMAGE','UPLOADED',
                     NULL,'legacy-upload.png','zhixiaohe/gate/v033/legacy-upload.png',
                     'image/png',89,repeat('5',64),24,18,'READY','TEST_BYPASS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
    }

    private void assertImageCandidateConstraintsExist(Connection connection) throws SQLException {
        assertThat(scalarString(connection, """
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE connamespace='business'::regnamespace AND conname='uk_asset_source_task_artifact'
                """)).contains("UNIQUE (source_task_id, source_artifact_index)");
        assertThat(scalarInt(connection, """
                SELECT count(*) FROM pg_constraint
                WHERE connamespace='business'::regnamespace AND conname='uk_asset_source_task'
                """)).as("the single-asset-per-task restriction was replaced").isZero();
        assertThat(scalarString(connection, """
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE connamespace='business'::regnamespace AND conname='ck_asset_source_artifact_index'
                """)).contains("source_task_id IS NULL", "source_artifact_index IS NULL",
                        "source_artifact_index IS NOT NULL", "IMAGE", "source_artifact_index <= 4");
        assertThat(scalarString(connection, """
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE connamespace='business'::regnamespace AND conname='ck_asset_ingestion_artifact_index'
                """)).contains("QWEN_IMAGE", "WAN_IMAGE_EDIT", "WAN_VIDEO", "artifact_index <= 4", "artifact_index = 1");
        assertThat(scalarString(connection, """
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE connamespace='business'::regnamespace AND conname='ck_asset_ingestion_content_type'
                """)).contains("WAN_IMAGE_EDIT", "expected_content_type IS NULL", "expected_content_type IS NOT NULL");
    }

    private void assertPublicationWithoutArticleUrlIsAllowed(Connection connection) throws SQLException {
        assertThat(scalarString(connection, """
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE connamespace='business'::regnamespace
                  AND conrelid='business.media_submission'::regclass
                  AND conname='ck_media_submission_terminal_fields'
                """))
                .as("a signed PUBLISHED receipt can settle without a public article URL")
                .contains("PUBLISHED", "published_at IS NOT NULL", "stable_error_code IS NULL")
                .doesNotContain("published_url IS NOT NULL");
        assertThat(scalarString(connection, """
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE connamespace='business'::regnamespace
                  AND conrelid='business.media_submission'::regclass
                  AND conname='ck_media_submission_url'
                """))
                .as("the URL-list constraint still permits an empty, no-link receipt")
                .contains("published_url IS NULL", "published_urls", "[]");
    }

    // ------------------------------------------------------------------ catalogue assertions

    private void assertHistoryIsCompleteAndSuccessful(Connection connection) throws SQLException {
        Map<String, Boolean> history = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT version, success FROM business.flyway_schema_history
                WHERE version IS NOT NULL ORDER BY installed_rank
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                history.put(result.getString(1), result.getBoolean(2));
            }
        }
        List<String> versions = new ArrayList<>(history.keySet());
        assertThat(versions).as("Flyway history versions").containsExactlyElementsOf(
                expectedVersions());
        assertThat(history.values()).as("every history row succeeded").doesNotContain(false);
    }

    private void assertM8TablesExist(Connection connection) throws SQLException {
        Set<String> tables = stringSet(connection, """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='business' AND table_type='BASE TABLE'
                """);
        assertThat(tables).as("M8 tables").containsAll(M8_TABLES);
    }

    /**
     * Each constraint is checked by its own definition text, not merely by its presence: a
     * constraint that exists with the wrong predicate is the failure mode this Gate is for.
     */
    private void assertM8ConstraintsExist(Connection connection) throws SQLException {
        Map<String, String> definitions = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
                WHERE connamespace='business'::regnamespace AND conname LIKE ANY (ARRAY[
                    'ck_recharge_order%','ck_manual_point_adjustment%','ck_operational_audit%',
                    'ck_invoice_application%','ck_user_notification%','ck_pricing_version%',
                    'ck_commercial_rate_limit%','ck_user_feedback%','ck_point_ledger%'])
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                definitions.put(result.getString(1), result.getString(2));
            }
        }

        assertThat(definitions.keySet()).contains(
                "ck_recharge_order_status", "ck_recharge_order_lifecycle",
                "ck_recharge_order_provider_refs", "ck_recharge_order_return_context",
                "ck_recharge_order_gift",
                "ck_recharge_order_event_details", "ck_recharge_order_event_source",
                "ck_manual_point_adjustment_ledger", "ck_manual_point_adjustment_direction",
                "ck_manual_point_adjustment_confirmation_expiry",
                "ck_operational_audit_actor_binding", "ck_operational_audit_details",
                "ck_operational_audit_outcome",
                "ck_invoice_application_lifecycle", "ck_invoice_application_delivery_receipt",
                "ck_invoice_application_delivery_binding", "ck_invoice_application_review_binding",
                "ck_invoice_application_title", "ck_invoice_application_reject_reason_binding",
                "ck_user_notification_target", "ck_user_notification_category",
                "ck_pricing_version_publish_binding", "ck_pricing_version_retire_binding",
                "ck_pricing_version_bounds", "ck_pricing_version_tier_gift",
                "ck_commercial_rate_limit_window", "ck_commercial_rate_limit_attempts",
                "ck_user_feedback_handling", "ck_user_feedback_status",
                "ck_point_ledger_entry_type", "ck_point_ledger_business_type",
                "ck_point_ledger_credit_types"
        );

        assertThat(definitions.get("ck_recharge_order_lifecycle"))
                .contains("paid_at", "closed_at", "failed_at", "SUCCEEDED", "EXPIRED");
        assertThat(definitions.get("ck_recharge_order_provider_refs"))
                .contains("PAYING", "SUCCEEDED", "provider_order_no");
        assertThat(definitions.get("ck_recharge_order_return_context"))
                .as("return_context is a kind and an id, never a URL")
                .contains("[A-Z][A-Z0-9_]", "[1-9][0-9]{0,18}");
        assertThat(definitions.get("ck_recharge_order_event_details"))
                .as("callback summaries are bounded").contains("16384");
        assertThat(definitions.get("ck_manual_point_adjustment_ledger"))
                .contains("APPLIED", "ledger_id", "applied_at");
        assertThat(definitions.get("ck_manual_point_adjustment_direction"))
                .as("only credits are representable").contains("CREDIT");
        assertThat(definitions.get("ck_operational_audit_actor_binding"))
                .contains("PAYMENT_PROVIDER", "MAIL_PROVIDER", "actor_ref");
        assertThat(definitions.get("ck_operational_audit_details"))
                .as("audit details are bounded").contains("16384");
        assertThat(definitions.get("ck_invoice_application_delivery_receipt"))
                .as("DELIVERED requires a delivery receipt").contains("DELIVERED",
                        "delivery_provider", "delivery_reference");
        assertThat(definitions.get("ck_invoice_application_review_binding"))
                .contains("reviewer_id", "reviewed_at");
        assertThat(definitions.get("ck_invoice_application_delivery_binding"))
                .contains("delivery_provider", "delivery_reference");
        assertThat(definitions.get("ck_user_notification_target"))
                .as("a notification target is a kind and an id, never a URL")
                .contains("target_type", "target_id");
        assertThat(definitions.get("ck_pricing_version_publish_binding"))
                .contains("published_by", "published_at");
        assertThat(definitions.get("ck_commercial_rate_limit_window"))
                .as("the bounds the service mirrors").contains("86400");
        assertThat(definitions.get("ck_commercial_rate_limit_attempts")).contains("100000");
        assertThat(definitions.get("ck_point_ledger_entry_type"))
                .as("widened, not replaced").contains("RECHARGE", "ADMIN_ADJUSTMENT",
                        "TASK_FREEZE", "WORKFLOW_SETTLE", "ADMIN_INITIAL_GRANT");
        assertThat(definitions.get("ck_point_ledger_credit_types"))
                .contains("RECHARGE", "ADMIN_ADJUSTMENT", "frozen_delta");
        assertThat(definitions.get("ck_pricing_version_tier_gift")).contains("gift_points");
    }

    private void assertM8NotNullColumns(Connection connection) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT table_name, column_name, is_nullable, coalesce(column_default,'')
                FROM information_schema.columns
                WHERE table_schema='business' AND (
                    (table_name='recharge_order' AND column_name IN
                        ('gift_points','pricing_version_id','return_context','order_no','status'))
                 OR (table_name='manual_point_adjustment' AND column_name IN
                        ('confirmation_expires_at','confirmation_token_hash','ledger_id'))
                 OR (table_name='invoice_application' AND column_name IN
                        ('delivered_at','approved_at','version','delivery_email'))
                 OR (table_name='user_notification' AND column_name IN ('read_at','dedup_key'))
                 OR (table_name='pricing_version' AND column_name IN
                        ('status','published_at','retired_at'))
                 OR (table_name='commercial_rate_limit' AND column_name IN
                        ('window_seconds','max_attempts','enabled'))
                 OR (table_name='user_feedback' AND column_name IN ('reply','handled_by','status'))
                )
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                columns.put(result.getString(1) + "." + result.getString(2),
                        result.getString(3) + "|" + result.getString(4));
            }
        }

        assertThat(columns).containsEntry("recharge_order.gift_points", "NO|0")
                .containsEntry("manual_point_adjustment.confirmation_expires_at", "NO|")
                .containsEntry("manual_point_adjustment.confirmation_token_hash", "NO|")
                .containsEntry("invoice_application.delivery_email", "NO|")
                .containsEntry("user_notification.dedup_key", "NO|")
                .containsEntry("commercial_rate_limit.enabled", "NO|true");
        // Deliberately nullable: these are the fields that mean "this has not happened yet".
        assertThat(columns).containsEntry("recharge_order.pricing_version_id", "YES|")
                .containsEntry("recharge_order.return_context", "YES|")
                .containsEntry("manual_point_adjustment.ledger_id", "YES|")
                .containsEntry("invoice_application.delivered_at", "YES|")
                .containsEntry("invoice_application.approved_at", "YES|")
                .containsEntry("user_notification.read_at", "YES|")
                .containsEntry("pricing_version.published_at", "YES|")
                .containsEntry("pricing_version.retired_at", "YES|")
                .containsEntry("user_feedback.reply", "YES|")
                .containsEntry("user_feedback.handled_by", "YES|");
    }

    /**
     * V031 adds no table and no column. Its entire content is the three indexes the operator console
     * reads through when it crosses accounts, so an index that was written but not created would be
     * invisible to every other assertion in this Gate.
     */
    private void assertOperatorConsoleIndexesExist(Connection connection) throws SQLException {
        Map<String, String> indexes = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT indexname, indexdef FROM pg_indexes
                WHERE schemaname='business' AND indexname = ANY (ARRAY[
                    'idx_media_submission_admin_created',
                    'idx_media_submission_admin_status_created',
                    'idx_media_callback_admin_received'])
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                indexes.put(result.getString(1), result.getString(2));
            }
        }
        assertThat(indexes.keySet()).hasSize(3);
        assertThat(indexes.get("idx_media_submission_admin_created"))
                .as("the console's default listing crosses accounts and orders by time")
                .contains("created_at DESC").contains("id DESC").doesNotContain("user_id");
        assertThat(indexes.get("idx_media_submission_admin_status_created"))
                .as("one exact status or one status bucket, still ordered by time")
                .contains("status").contains("created_at DESC");
        assertThat(indexes.get("idx_media_callback_admin_received"))
                .as("only the receipts that still want a human, ordered by arrival")
                .contains("received_at DESC").contains("RECEIVED").contains("FAILED");
    }

    private void assertAgentVersionConstraintsExist(Connection connection) throws SQLException {
        Map<String, String> constraints = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conname, pg_get_constraintdef(oid), convalidated
                FROM pg_constraint
                WHERE connamespace='business'::regnamespace AND conname = ANY (ARRAY[
                    'ck_task_execution_bundle_version',
                    'ck_workflow_execution_bundle_version'])
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                constraints.put(result.getString(1),
                        result.getBoolean(3) + "|" + result.getString(2));
            }
        }
        assertThat(constraints.keySet()).containsExactlyInAnyOrder(
                "ck_task_execution_bundle_version",
                "ck_workflow_execution_bundle_version");
        assertThat(constraints.values()).allSatisfy(definition ->
                assertThat(definition).startsWith("true|")
                        .contains("[A-Za-z0-9]", "[A-Za-z0-9._:-]"));
    }

    /**
     * V032 adds the two exact access paths used by the read-only Agent-version console. Both begin
     * with the equality-filtered bundle version and then preserve the stable newest-first order.
     */
    private void assertAgentVersionIndexesExist(Connection connection) throws SQLException {
        Map<String, String> indexes = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT indexname, indexdef FROM pg_indexes
                WHERE schemaname='business' AND indexname = ANY (ARRAY[
                    'idx_task_execution_bundle_started',
                    'idx_workflow_execution_bundle_started'])
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                indexes.put(result.getString(1), result.getString(2));
            }
        }
        assertThat(indexes.keySet()).containsExactlyInAnyOrder(
                "idx_task_execution_bundle_started",
                "idx_workflow_execution_bundle_started");
        assertThat(indexes.get("idx_task_execution_bundle_started"))
                .contains("bundle_version", "started_at DESC", "id DESC");
        assertThat(indexes.get("idx_workflow_execution_bundle_started"))
                .contains("bundle_version", "started_at DESC", "id DESC");
    }

    private void assertM8UniqueIndexesExist(Connection connection) throws SQLException {
        Map<String, String> indexes = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT indexname, indexdef FROM pg_indexes
                WHERE schemaname='business' AND indexname = ANY (ARRAY[
                    'uk_recharge_order_idempotency','uk_recharge_order_no',
                    'uk_recharge_order_provider_order','uk_recharge_order_event_source',
                    'uk_manual_point_adjustment_idempotency','uk_manual_point_adjustment_confirmation',
                    'uk_operational_audit_idempotency','uk_invoice_application_no',
                    'uk_invoice_application_idempotency','uk_invoice_application_event_source',
                    'uk_user_notification_dedup','uk_pricing_version_no',
                    'uk_pricing_version_single_active','uk_pricing_version_tier_amount',
                    'uk_commercial_rate_limit_action','uk_user_feedback_idempotency'])
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                indexes.put(result.getString(1), result.getString(2));
            }
        }
        assertThat(indexes.keySet()).hasSize(16);
        assertThat(indexes.get("uk_pricing_version_single_active"))
                .as("at most one published rate card").containsIgnoringCase("UNIQUE")
                .contains("ACTIVE");
        assertThat(indexes.get("uk_operational_audit_idempotency"))
                .containsIgnoringCase("UNIQUE").contains("idempotency_key IS NOT NULL");
        assertThat(indexes.get("uk_recharge_order_provider_order")).contains("provider_order_no");
        assertThat(indexes.get("uk_user_notification_dedup")).contains("dedup_key");
    }

    /**
     * The constraints are read back, then asked to refuse something.
     *
     * <p>A predicate can be present in the catalogue and still be wrong; these probes are the only
     * way to know it is the predicate that was intended. Each runs in its own transaction that is
     * rolled back, so a probe never leaves a row behind and never poisons the next one.
     */
    private void assertM8ConstraintBehaviour(Connection connection, String url, String user,
                                             String password) throws Exception {
        execute(connection, """
                INSERT INTO business.account(
                    id,username,username_normalized,phone_e164,password_hash,role,status,
                    created_at,updated_at
                ) VALUES (
                    982000000000000001,'m8-gate','m8-gate','+8600000000982',
                    repeat('a',32),'USER','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        assertSqlState(url, user, password, "23514", """
                INSERT INTO business.recharge_order(
                    id,order_no,user_id,status,provider,amount_cents,points,idempotency_key,
                    request_hash,expires_at,created_at,updated_at
                ) VALUES (
                    982000000000000002,'M8GATE-ILLEGAL',982000000000000001,'SUCCEEDED','WECHAT_NATIVE',
                    100,100,'m8-gate-illegal-order',repeat('d',64),
                    CURRENT_TIMESTAMP + INTERVAL '30 minutes',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        assertSqlState(url, user, password, "23514", """
                INSERT INTO business.recharge_order(
                    id,order_no,user_id,status,provider,amount_cents,points,idempotency_key,
                    request_hash,expires_at,return_context,created_at,updated_at
                ) VALUES (
                    982000000000000003,'M8GATE-BADCTX',982000000000000001,'CREATED','WECHAT_NATIVE',
                    100,100,'m8-gate-bad-context',repeat('e',64),
                    CURRENT_TIMESTAMP + INTERVAL '30 minutes',
                    'https://example.invalid/pay',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        // Reviewed and approved in every respect, carrying a delivered_at and no delivery receipt at
        // all: the row is exactly the shape that would let "internal review passed" be shown as
        // "invoice issued", and it must be unrepresentable. It is written without a delivery
        // provider as well, so the receipt requirement is the only thing that can refuse it.
        assertSqlState(url, user, password, "23514", """
                INSERT INTO business.invoice_application(
                    id,application_no,user_id,status,invoice_type,title_type,title,tax_number,
                    amount_cents,delivery_email,reviewer_id,idempotency_key,request_hash,
                    submitted_at,reviewed_at,approved_at,delivered_at,
                    created_at,updated_at
                ) VALUES (
                    982000000000000004,'M8GATE-INVOICE',982000000000000001,'DELIVERED','VAT_NORMAL',
                    'COMPANY','Gate Co','91310000MA1FL0XXXX',100,'billing@example.invalid',
                    982000000000000001,'m8-gate-invoice',repeat('f',64),CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        assertSqlState(url, user, password, "23514", """
                INSERT INTO business.user_notification(
                    id,user_id,category,title,body,target_type,dedup_key,created_at
                ) VALUES (
                    982000000000000005,982000000000000001,'RECHARGE','gate','gate','RECHARGE_ORDER',
                    'm8-gate-notification',CURRENT_TIMESTAMP
                )
                """);
        // A published version whose range is inverted. The publish binding is satisfied, so the
        // bounds are the only thing left that can refuse this row.
        assertSqlState(url, user, password, "23514", """
                INSERT INTO business.pricing_version(
                    id,version_no,status,points_per_yuan,min_recharge_amount_cents,
                    max_recharge_amount_cents,created_by,published_by,published_at,
                    created_at,updated_at
                ) VALUES (
                    982000000000000006,9001,'ACTIVE',100,1000,500,982000000000000001,
                    982000000000000001,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        // A version that claims to be in force without recording who published it. Every rate a user
        // is charged comes from an ACTIVE version, so "in force" and "someone published this" have
        // to be the same fact; this row would break that.
        assertSqlState(url, user, password, "23514", """
                INSERT INTO business.pricing_version(
                    id,version_no,status,points_per_yuan,min_recharge_amount_cents,
                    max_recharge_amount_cents,created_by,created_at,updated_at
                ) VALUES (
                    982000000000000012,9004,'ACTIVE',100,1000,50000,982000000000000001,
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);

        // And the same shapes are accepted when they are legal, so the probes above are not simply
        // proving that the table rejects everything.
        execute(connection, """
                INSERT INTO business.recharge_order(
                    id,order_no,user_id,status,provider,amount_cents,points,gift_points,
                    idempotency_key,request_hash,expires_at,return_context,created_at,updated_at
                ) VALUES (
                    982000000000000007,'M8GATE-LEGAL',982000000000000001,'CREATED','WECHAT_NATIVE',
                    1000,1100,100,'m8-gate-legal-order',repeat('1',64),
                    CURRENT_TIMESTAMP + INTERVAL '30 minutes','TASK_DRAFT:981000000000000001',
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                INSERT INTO business.pricing_version(
                    id,version_no,status,points_per_yuan,min_recharge_amount_cents,
                    max_recharge_amount_cents,created_by,published_by,published_at,
                    created_at,updated_at
                ) VALUES (
                    982000000000000008,9002,'ACTIVE',100,1000,50000,982000000000000001,
                    982000000000000001,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        // The same invoice, with a delivery receipt recorded. This is the only shape that may claim
        // the applicant has the invoice, and it is representable.
        execute(connection, """
                INSERT INTO business.invoice_application(
                    id,application_no,user_id,status,invoice_type,title_type,title,tax_number,
                    amount_cents,delivery_email,reviewer_id,idempotency_key,request_hash,
                    submitted_at,reviewed_at,approved_at,delivered_at,delivery_provider,
                    delivery_reference,created_at,updated_at
                ) VALUES (
                    982000000000000011,'M8GATE-DELIVERED',982000000000000001,'DELIVERED','VAT_NORMAL',
                    'COMPANY','Gate Co','91310000MA1FL0XXXX',100,'billing@example.invalid',
                    982000000000000001,'m8-gate-delivered-invoice',repeat('3',64),CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'MANUAL_OFFLINE',
                    'm8-gate-delivery-receipt',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        // A second version claiming to be in force. This one is legal in every column, so the only
        // thing that can refuse it is the partial unique index: two live rate cards would price the
        // same recharge differently depending on which row a query happened to read.
        assertSqlState(url, user, password, "23505", """
                INSERT INTO business.pricing_version(
                    id,version_no,status,points_per_yuan,min_recharge_amount_cents,
                    max_recharge_amount_cents,created_by,published_by,published_at,
                    created_at,updated_at
                ) VALUES (
                    982000000000000009,9003,'ACTIVE',100,1000,50000,982000000000000001,
                    982000000000000001,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        assertSqlState(url, user, password, "23514", """
                INSERT INTO business.recharge_order(
                    id,order_no,user_id,status,provider,amount_cents,points,gift_points,
                    idempotency_key,request_hash,expires_at,created_at,updated_at
                ) VALUES (
                    982000000000000010,'M8GATE-GIFT',982000000000000001,'CREATED','WECHAT_NATIVE',
                    1000,1000,1000,'m8-gate-gift-order',repeat('2',64),
                    CURRENT_TIMESTAMP + INTERVAL '30 minutes',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
    }

    // ------------------------------------------------------------------ helpers

    private static Flyway flyway(String url, String user, String password) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .defaultSchema("business")
                .schemas("business", "agent")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load();
    }

    private static Flyway targetedFlyway(String url, String user, String password, String target) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .defaultSchema("business")
                .schemas("business", "agent")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static List<String> expectedVersions() {
        List<String> versions = new ArrayList<>();
        for (int index = 1; index <= TOTAL_MIGRATIONS; index++) {
            versions.add("%03d".formatted(index));
        }
        return versions;
    }

    private static String currentDatabase(Connection connection) throws SQLException {
        return scalarString(connection, "SELECT current_database()");
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the migration Gate");
        }
        return value;
    }

    /**
     * Runs {@code sql} in a throwaway transaction and asserts the SQLSTATE it fails with.
     *
     * <p>The rollback is what makes successive probes independent: without it the first refused
     * statement would leave the transaction aborted and every later probe would report 25P02
     * instead of the constraint it was aimed at.
     */
    private static void assertSqlState(String url, String user, String password, String expected,
                                       String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                throw new AssertionError("Expected SQLSTATE " + expected + " but the statement succeeded");
            } catch (SQLException exception) {
                assertThat(exception.getSQLState()).as(exception.getMessage()).isEqualTo(expected);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertSqlState(Connection connection, String expected, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            throw new AssertionError("Expected SQLSTATE " + expected + " but the statement succeeded");
        } catch (SQLException exception) {
            assertThat(exception.getSQLState()).as(exception.getMessage()).isEqualTo(expected);
        }
    }

    private void assertConversationAdmissionSurvivesDeletion(Connection connection) throws SQLException {
        assertThat(scalarInt(connection, "SELECT count(*) FROM pg_indexes WHERE schemaname='business'"
                + " AND indexname IN ('idx_conversation_admission_user_time','idx_agent_run_active_deadline')"))
                .isEqualTo(2);
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                    INSERT INTO business.account(id,username,username_normalized,phone_e164,password_hash,role,status,created_at,updated_at)
                    VALUES(984000000000000001,'chat-quota-gate','chat-quota-gate','+8600000000984',repeat('a',32),'USER','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO business.conversation(id,user_id,title,created_at,updated_at)
                    VALUES(984000000000000002,984000000000000001,'quota gate',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO business.conversation_message(id,conversation_id,user_id,role,content_type,content,
                        client_message_id,idempotency_key,request_fingerprint,sequence_no,created_at,updated_at)
                    VALUES(984000000000000003,984000000000000002,984000000000000001,'USER','TEXT','{}',
                        '00000000-0000-0000-0000-000000000040','00000000-0000-0000-0000-000000000041',repeat('a',64),1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO business.agent_run(id,conversation_id,user_id,request_message_id,status,
                        context_snapshot,context_ref_hash,context_expires_at,deadline_at,created_at,updated_at)
                    VALUES('00000000-0000-0000-0000-000000000042',984000000000000002,984000000000000001,984000000000000003,
                        'FAILED','{}',repeat('a',64),CURRENT_TIMESTAMP+INTERVAL '5 minutes',CURRENT_TIMESTAMP+INTERVAL '2 minutes',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO business.conversation_admission(run_id,user_id,accepted_at,vision)
                    VALUES('00000000-0000-0000-0000-000000000042',984000000000000001,CURRENT_TIMESTAMP,false);
                    """);
            java.sql.Savepoint validDeadline = connection.setSavepoint();
            try {
                execute(connection, "UPDATE business.agent_run SET deadline_at=created_at"
                        + " WHERE id='00000000-0000-0000-0000-000000000042'");
                throw new AssertionError("An already-expired admission deadline must be rejected");
            } catch (SQLException exception) {
                assertThat(exception.getSQLState()).isEqualTo("23514");
                connection.rollback(validDeadline);
            }
            execute(connection, "DELETE FROM business.conversation WHERE id=984000000000000002");
            assertThat(scalarInt(connection, "SELECT count(*) FROM business.agent_run"
                    + " WHERE id='00000000-0000-0000-0000-000000000042'")).isZero();
            assertThat(scalarInt(connection, "SELECT count(*) FROM business.conversation_admission"
                    + " WHERE user_id=984000000000000001")).isEqualTo(1);
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).as(sql).isTrue();
            return result.getString(1);
        }
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).as(sql).isTrue();
            return result.getInt(1);
        }
    }

    private static Set<String> stringSet(Connection connection, String sql) throws SQLException {
        Set<String> values = new TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
