package com.guanghe.backend.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "FORMAL_TEST_DB_INIT_ENABLED", matches = "true")
class FormalTestDatabaseInitializationIntegrationTests {

    private static final String EXPECTED_DATABASE = "zhixiaohe_test";
    private static final String EXPECTED_MIGRATION_ROLE = "zhixiaohe_test_flyway";
    private static final String EXPECTED_RUNTIME_ROLE = "zhixiaohe_test_app";
    private static final Set<String> EXPECTED_TABLES = Set.of(
            "account",
            "account_activation",
            "account_consent",
            "account_deactivation_request",
            "account_security_audit",
            "business_space",
            "competitor",
            "competitor_alias",
            "conversation",
            "conversation_event",
            "conversation_message",
            "agent_run",
            "product",
            "product_alias",
            "sms_delivery_log",
            "space_brand_alias",
            "user_preference",
            "task_draft", "task_confirmation", "point_account", "business_task",
            "point_reservation", "point_ledger", "task_execution", "business_task_event",
            "outbox_event", "inbox_event", "asset", "asset_product_relation",
            "asset_ingestion_work",
            "geo_topic", "geo_keyword", "geo_question", "geo_competitor_set",
            "geo_competitor_set_member", "geo_run", "geo_observation", "geo_citation", "geo_report",
            "geo_plan", "geo_plan_slot", "qrtz_job_details", "qrtz_triggers",
            "qrtz_simple_triggers", "qrtz_cron_triggers", "qrtz_simprop_triggers",
            "qrtz_blob_triggers", "qrtz_calendars", "qrtz_paused_trigger_grps",
            "qrtz_fired_triggers", "qrtz_scheduler_state", "qrtz_locks",
            "article", "article_asset_relation", "article_submission_snapshot",
            "media_resource", "media_price_snapshot", "media_submission_batch",
            "media_submission", "media_submission_event", "media_callback_event",
            "media_provider_sync_state", "workflow_plan", "workflow_plan_step",
            "workflow_confirmation", "workflow_event", "workflow_execution",
            "workflow_observation", "workflow_point_reservation"
    );

    @Test
    void initializesAndVerifiesTheFormalTestDatabase() throws Exception {
        String expectedDatabase = required("FORMAL_TEST_EXPECTED_DATABASE");
        String migrationUrl = required("FORMAL_TEST_MIGRATION_DB_URL");
        String migrationUser = required("FORMAL_TEST_MIGRATION_DB_USERNAME");
        String migrationPassword = required("FORMAL_TEST_MIGRATION_DB_PASSWORD");
        String runtimeUrl = required("FORMAL_TEST_RUNTIME_DB_URL");
        String runtimeUser = required("FORMAL_TEST_RUNTIME_DB_USERNAME");
        String runtimePassword = required("FORMAL_TEST_RUNTIME_DB_PASSWORD");

        requireExactFormalTarget(expectedDatabase, migrationUser, runtimeUser);
        verifyBothConnectionsBeforeMutation(
                expectedDatabase,
                migrationUrl,
                migrationUser,
                migrationPassword,
                runtimeUrl,
                runtimeUser,
                runtimePassword
        );

        Flyway flyway = Flyway.configure()
                .dataSource(migrationUrl, migrationUser, migrationPassword)
                .defaultSchema("business")
                .schemas("business", "agent")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load();

        MigrateResult firstRun = flyway.migrate();
        flyway.validate();
        MigrateResult secondRun = flyway.migrate();
        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(firstRun.migrationsExecuted).isEqualTo(1);
        assertThat(current.getVersion().toString()).isEqualTo("020");
        assertThat(secondRun.migrationsExecuted).isZero();

        ObjectSnapshot snapshot;
        try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            configureRuntimePermissions(migration, migrationUser, runtimeUser);
            snapshot = assertDatabaseObjects(migration, migrationUser, runtimeUser);
            execute(migration, "CREATE TABLE business.formal_permission_probe "
                    + "(id bigint PRIMARY KEY, value text NOT NULL)");
        }

        PermissionResult permissions;
        try (Connection runtime = DriverManager.getConnection(runtimeUrl, runtimeUser, runtimePassword)) {
            permissions = verifyActualRuntimeDmlAndDdl(runtime);
        } finally {
            try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
                execute(migration, "DROP TABLE IF EXISTS business.formal_runtime_create_probe");
                execute(migration, "DROP TABLE IF EXISTS business.formal_permission_probe");
            }
        }

        assertThat(permissions.selectAllowed()).isTrue();
        assertThat(permissions.insertAllowed()).isTrue();
        assertThat(permissions.updateAllowed()).isTrue();
        assertThat(permissions.deleteAllowed()).isTrue();
        assertThat(permissions.createDenied()).isTrue();
        assertThat(permissions.alterDenied()).isTrue();
        assertThat(permissions.dropDenied()).isTrue();
        assertThat(permissions.flywayHistorySelectDenied()).isTrue();
        assertThat(permissions.flywayHistoryInsertDenied()).isTrue();
        assertThat(permissions.flywayHistoryUpdateDenied()).isTrue();
        assertThat(permissions.flywayHistoryDeleteDenied()).isTrue();

        List<String> applied = new ArrayList<>();
        for (MigrationInfo info : flyway.info().applied()) {
            if (info.getVersion() != null) {
                applied.add(info.getVersion() + ":" + info.getDescription() + ":" + info.getState());
            }
        }

        System.out.println("FORMAL_DB database=" + EXPECTED_DATABASE);
        System.out.println("FORMAL_DB flyway_first_run=" + firstRun.migrationsExecuted);
        System.out.println("FORMAL_DB flyway_second_run=" + secondRun.migrationsExecuted);
        System.out.println("FORMAL_DB flyway_applied=" + applied);
        System.out.println("FORMAL_DB schemas=" + snapshot.schemas());
        System.out.println("FORMAL_DB tables=" + snapshot.tables());
        System.out.println("FORMAL_DB indexes=" + snapshot.indexes());
        System.out.println("FORMAL_DB constraints=" + snapshot.constraints());
        System.out.println("FORMAL_DB runtime_dml=SELECT,INSERT,UPDATE,DELETE allowed");
        System.out.println("FORMAL_DB runtime_ddl=CREATE,ALTER,DROP denied");
        System.out.println("FORMAL_DB flyway_history=SELECT,INSERT,UPDATE,DELETE denied");
    }

    static void requireExactFormalTarget(String expectedDatabase, String migrationUser, String runtimeUser) {
        if (!EXPECTED_DATABASE.equals(expectedDatabase)) {
            throw new IllegalStateException("Formal initializer may only target " + EXPECTED_DATABASE);
        }
        if (!EXPECTED_MIGRATION_ROLE.equals(migrationUser)) {
            throw new IllegalStateException("Unexpected formal Flyway role");
        }
        if (!EXPECTED_RUNTIME_ROLE.equals(runtimeUser)) {
            throw new IllegalStateException("Unexpected formal runtime role");
        }
        if (migrationUser.equals(runtimeUser)) {
            throw new IllegalStateException("Flyway and runtime roles must be different");
        }
    }

    private void verifyBothConnectionsBeforeMutation(
            String expectedDatabase,
            String migrationUrl,
            String migrationUser,
            String migrationPassword,
            String runtimeUrl,
            String runtimeUser,
            String runtimePassword
    ) throws SQLException {
        String migrationDatabase;
        try (Connection connection = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            migrationDatabase = currentDatabase(connection);
            assertPostgresql18(connection);
        }
        String runtimeDatabase;
        try (Connection connection = DriverManager.getConnection(runtimeUrl, runtimeUser, runtimePassword)) {
            runtimeDatabase = currentDatabase(connection);
        }
        if (!expectedDatabase.equals(migrationDatabase)
                || !expectedDatabase.equals(runtimeDatabase)
                || !migrationDatabase.equals(runtimeDatabase)) {
            throw new IllegalStateException("Formal migration and runtime URLs must both resolve to the expected test database");
        }
    }

    private void configureRuntimePermissions(Connection connection, String migrationUser, String runtimeUser)
            throws SQLException {
        String runtimeRole = quoteIdentifier(runtimeUser);
        String migrationRole = quoteIdentifier(migrationUser);
        execute(connection, "REVOKE ALL ON SCHEMA public, business, agent FROM PUBLIC");
        execute(connection, "GRANT USAGE ON SCHEMA business, agent TO " + runtimeRole);
        for (String table : EXPECTED_TABLES) {
            execute(connection, "GRANT SELECT, INSERT, UPDATE, DELETE ON business."
                    + quoteIdentifier(table) + " TO " + runtimeRole);
        }
        execute(connection, "REVOKE ALL ON business.flyway_schema_history FROM " + runtimeRole);
        execute(connection, "ALTER DEFAULT PRIVILEGES FOR ROLE " + migrationRole
                + " IN SCHEMA business GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + runtimeRole);
        execute(connection, "ALTER DEFAULT PRIVILEGES FOR ROLE " + migrationRole
                + " IN SCHEMA agent GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + runtimeRole);
    }

    private ObjectSnapshot assertDatabaseObjects(Connection connection, String migrationUser, String runtimeUser)
            throws SQLException {
        Set<String> schemas = stringSet(connection,
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('business', 'agent')");
        assertThat(schemas).containsExactlyInAnyOrder("business", "agent");

        Set<String> tables = stringSet(connection, """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'business' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
        assertThat(tables).doesNotContain("shop", "store");

        Set<String> owners = stringSet(connection, """
                SELECT DISTINCT tableowner FROM pg_tables
                WHERE schemaname IN ('business', 'agent')
                """);
        assertThat(owners).containsExactly(migrationUser);

        Set<String> indexes = stringSet(connection, """
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'business'
                ORDER BY indexname
                """);
        assertThat(indexes).contains("uk_account_username_normalized", "uk_account_phone_e164",
                "uk_account_activation_token", "uk_account_activation_available",
                "uk_business_space_user_name", "uk_product_space_name", "uk_competitor_space_name",
                "uk_agent_run_active_conversation", "uk_conversation_event_terminal_run",
                "uk_conversation_event_agent_progress_identity",
                "uk_task_draft_run", "uk_business_task_idempotency", "uk_point_reservation_task",
                 "uk_task_execution_number", "uk_business_task_event_source", "uk_outbox_task_execution_attempt",
                "uk_inbox_consumer_event", "uk_asset_object_key", "uk_asset_source_task",
                 "uk_asset_ingestion_task_artifact", "uk_asset_ingestion_object_key",
                 "idx_asset_ingestion_claim", "uk_task_draft_idempotency",
                 "idx_business_task_stale_queue", "idx_agent_run_trace_id");
        assertThat(indexes).contains(
                "uk_geo_topic_space_name", "uk_geo_keyword_topic_sequence",
                "uk_geo_keyword_topic_text", "uk_geo_question_topic_sequence",
                "uk_geo_question_topic_text", "uk_geo_competitor_set_version",
                "uk_geo_competitor_member_sequence", "uk_geo_competitor_member_competitor",
                "idx_geo_topic_user_created", "idx_geo_competitor_set_current",
                "uk_geo_run_task", "uk_geo_observation_event", "uk_geo_observation_sequence",
                "uk_geo_observation_unit",
                "uk_geo_observation_unit_key", "uk_geo_report_run",
                "idx_geo_run_user_created", "idx_geo_report_user_generated",
                "uk_geo_plan_source_draft", "uk_geo_plan_idempotency", "uk_geo_plan_name", "uk_business_task_plan_slot",
                "uk_geo_plan_slot", "idx_geo_plan_active_next", "idx_qrtz_t_next_fire_time",
                "uk_article_asset", "uk_article_asset_position", "uk_article_asset_order",
                "uk_article_snapshot_version", "idx_article_user_created",
                "uk_media_resource_upstream", "uk_media_batch_task", "uk_media_batch_draft",
                "uk_media_submission_customer_order", "uk_media_submission_batch_resource",
                "uk_media_event_sequence", "uk_media_event_source",
                "uk_media_callback_order_identity", "uk_media_callback_resource_identity",
                 "idx_media_submission_poll", "uk_workflow_plan_proposal",
                 "uk_workflow_plan_source_run", "uk_workflow_step_key",
                 "uk_workflow_step_ordinal", "uk_workflow_confirmation_token",
                 "uk_workflow_confirmation_user_idempotency",
                 "uk_workflow_event_sequence", "idx_workflow_plan_user_created",
                 "uk_workflow_plan_start_idempotency", "uk_workflow_execution_number",
                 "uk_workflow_execution_claim", "uk_workflow_execution_active",
                 "uk_workflow_observation_event", "uk_workflow_observation_attempt",
                 "uk_workflow_observation_tool_call", "uk_workflow_reservation_plan",
                 "uk_workflow_step_activation_event"
        );

        Set<String> constraints = stringSet(connection, """
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE constraint_schema = 'business'
                ORDER BY constraint_name
                """);
        assertThat(constraints).contains("fk_product_space", "fk_product_alias_product",
                "fk_competitor_space", "ck_account_role", "ck_product_parameters",
                "fk_account_activation_user", "fk_account_security_audit_actor",
                "fk_account_security_audit_target", "ck_account_credential_state",
                "ck_account_activation_status", "ck_account_security_audit_event",
                "fk_conversation_message_reply", "fk_agent_run_request_message",
                "fk_agent_run_response_message", "fk_conversation_event_run",
                "fk_conversation_event_message", "ck_agent_run_context_snapshot",
                "uk_conversation_message_sequence", "uk_agent_run_request_message",
                "uk_conversation_event_sequence", "fk_task_draft_run", "fk_task_draft_product",
                "fk_business_task_draft", "fk_point_reservation_task", "fk_task_execution_task",
                "fk_business_task_event_execution", "fk_inbox_event_outbox",
                "fk_conversation_event_task", "ck_task_draft_snapshot",
                "ck_task_confirmation_token_hash", "ck_point_account_available",
                "ck_business_task_status", "ck_point_reservation_amounts",
                "ck_task_execution_submission_state", "ck_outbox_event_traceparent",
                "uk_task_confirmation_token_hash", "uk_business_task_draft",
                "uk_task_execution_number", "uk_business_task_event_source",
                 "uk_outbox_task_execution_attempt", "uk_inbox_consumer_event",
                 "ck_outbox_execution_attempt", "ck_outbox_payload_execution_attempt",
                "fk_asset_source_task", "fk_asset_primary_product",
                "fk_asset_product_relation_asset", "fk_asset_product_relation_product",
                "fk_asset_ingestion_task", "fk_asset_ingestion_execution",
                "uk_asset_source_task", "uk_asset_ingestion_task_artifact",
                 "ck_asset_ready_safety", "ck_asset_ingestion_provider_url",
                 "ck_asset_ingestion_lease", "ck_task_draft_source",
                 "ck_task_draft_source_binding", "ck_agent_run_trace_id");
        assertThat(constraints).contains(
                "fk_geo_topic_space", "fk_geo_topic_product", "fk_geo_keyword_topic",
                "fk_geo_question_topic", "fk_geo_competitor_set_topic",
                "fk_geo_competitor_member_set", "fk_geo_competitor_member_competitor",
                "ck_geo_keyword_type", "ck_geo_question_category", "ck_geo_question_source",
                "fk_geo_run_task", "fk_geo_run_topic", "fk_geo_observation_run",
                "fk_geo_citation_observation", "fk_geo_report_run", "fk_geo_report_topic",
                "ck_geo_run_counts", "ck_geo_observation_sequence", "ck_geo_observation_result",
                "ck_geo_citation_url",
                "ck_geo_report_synthesis", "fk_geo_plan_space", "fk_geo_plan_topic",
                "fk_geo_plan_draft", "fk_business_task_plan", "fk_geo_plan_slot_plan",
                "ck_geo_plan_schedule", "ck_geo_plan_lifecycle", "ck_geo_plan_slot_result",
                "fk_article_space", "fk_article_product", "fk_article_source_task",
                "fk_article_asset_article", "fk_article_asset_asset", "fk_article_snapshot_article",
                "fk_article_snapshot_product", "ck_article_body_html", "ck_article_outline",
                "ck_article_asset_position", "ck_article_snapshot_assets",
                "fk_media_price_resource", "fk_media_batch_task", "fk_media_batch_draft",
                "fk_media_batch_article", "fk_media_batch_snapshot", "fk_media_batch_product",
                "fk_media_submission_batch", "fk_media_submission_resource",
                "fk_media_submission_price", "fk_media_submission_snapshot",
                "fk_media_event_submission", "fk_media_callback_submission",
                "ck_media_resource_price", "ck_media_price_amounts", "ck_media_batch_points",
                "ck_media_submission_state_alignment", "ck_media_submission_terminal_fields",
                 "ck_media_callback_type", "fk_workflow_plan_conversation",
                 "fk_workflow_plan_run", "fk_workflow_plan_space", "fk_workflow_plan_product",
                 "fk_workflow_step_plan", "fk_workflow_step_draft",
                 "fk_workflow_confirmation_plan", "fk_workflow_event_plan",
                 "ck_workflow_plan_steps", "ck_workflow_plan_budget",
                 "ck_workflow_step_prepare", "ck_workflow_confirmation_request_hash",
                 "ck_workflow_confirmation_idempotency_pair", "ck_workflow_confirmation_lifecycle",
                 "ck_workflow_event_type", "fk_workflow_execution_plan",
                 "fk_workflow_observation_step", "fk_workflow_observation_execution",
                 "fk_workflow_reservation_plan", "ck_workflow_execution_token",
                 "ck_workflow_execution_context", "ck_workflow_observation_request_hash",
                 "ck_workflow_observation_facts", "ck_workflow_reservation_amounts",
                 "ck_workflow_plan_lease_recovery_count", "ck_workflow_step_authorized_draft",
                 "ck_workflow_step_activation", "ck_workflow_step_task_result_ref",
                 "uk_workflow_step_activation_event", "ck_workflow_plan_authorized_context"
        );

        Set<String> accountAccessChecks = stringSet(connection, """
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'CHECK'
                  AND table_name IN ('account', 'account_activation', 'account_security_audit')
                ORDER BY constraint_name
                """);
        assertThat(accountAccessChecks).contains(
                "ck_account_password_algorithm", "ck_account_credential_state",
                "ck_account_activation_hash", "ck_account_activation_version",
                "ck_account_activation_status", "ck_account_activation_lifecycle",
                "ck_account_security_audit_event", "ck_account_security_audit_details");

        assertContextSnapshotColumn(connection);
        assertAgentRunTraceColumn(connection);
        assertAssetIngestionLifecycleColumns(connection);
        assertWorkflowConfirmationAuthorizationColumns(connection);
        assertWorkflowTaskBridgeColumns(connection);
        assertWorkflowAuthorizedContextColumns(connection);
        assertCompositeOwnershipForeignKeys(connection);
        assertPartialUniqueIndex(connection, "uk_agent_run_active_conversation", "stop_requested");
        assertPartialUniqueIndex(connection, "uk_conversation_event_terminal_run", "message.completed");

        assertThat(hasSchemaPrivilege(connection, runtimeUser, "business", "USAGE")).isTrue();
        assertThat(hasSchemaPrivilege(connection, runtimeUser, "business", "CREATE")).isFalse();
        assertThat(hasDatabasePrivilege(connection, runtimeUser, EXPECTED_DATABASE, "CREATE")).isFalse();
        assertThat(hasDatabasePrivilege(connection, runtimeUser, EXPECTED_DATABASE, "TEMPORARY")).isFalse();
        assertThat(hasTablePrivilege(connection, runtimeUser, "business.flyway_schema_history",
                "SELECT,INSERT,UPDATE,DELETE")).isFalse();
        for (String table : EXPECTED_TABLES) {
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(hasTablePrivilege(connection, runtimeUser, "business." + table, privilege))
                        .as(table + ":" + privilege).isTrue();
            }
        }

        return new ObjectSnapshot(schemas, tables, indexes, constraints);
    }

    private void assertContextSnapshotColumn(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT data_type, is_nullable
                     FROM information_schema.columns
                     WHERE table_schema = 'business' AND table_name = 'agent_run'
                       AND column_name = 'context_snapshot'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("data_type")).isEqualTo("jsonb");
            assertThat(result.getString("is_nullable")).isEqualTo("NO");
        }
    }

    private void assertAgentRunTraceColumn(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT data_type, is_nullable, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema='business' AND table_name='agent_run' AND column_name='trace_id'
                """); ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("data_type")).isEqualTo("character");
            assertThat(result.getString("is_nullable")).isEqualTo("YES");
            assertThat(result.getInt("character_maximum_length")).isEqualTo(32);
        }
    }

    private void assertWorkflowConfirmationAuthorizationColumns(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, is_nullable, data_type
                FROM information_schema.columns
                WHERE table_schema='business' AND table_name='workflow_confirmation'
                  AND column_name IN ('confirmation_idempotency_key','request_hash')
                ORDER BY column_name
                """); ResultSet result = statement.executeQuery()) {
            Map<String, String> columns = new java.util.HashMap<>();
            while (result.next()) {
                columns.put(result.getString("column_name"),
                        result.getString("is_nullable") + ":" + result.getString("data_type"));
            }
            assertThat(columns).containsEntry("confirmation_idempotency_key", "YES:uuid")
                    .containsEntry("request_hash", "YES:character");
        }
    }

    private void assertWorkflowTaskBridgeColumns(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT table_name, column_name, is_nullable, data_type
                FROM information_schema.columns
                WHERE table_schema='business'
                  AND ((table_name='workflow_plan' AND column_name='lease_recovery_count')
                    OR (table_name='workflow_plan_step' AND column_name IN
                      ('authorized_input_hash','authorized_draft_expires_at','activation_event_id',
                       'activation_request_hash','task_result_ref')))
                ORDER BY table_name,column_name
                """); ResultSet result = statement.executeQuery()) {
            Map<String, String> columns = new java.util.HashMap<>();
            while (result.next()) {
                columns.put(result.getString("table_name") + "." + result.getString("column_name"),
                        result.getString("is_nullable") + ":" + result.getString("data_type"));
            }
            assertThat(columns).containsEntry("workflow_plan.lease_recovery_count", "NO:integer")
                    .containsEntry("workflow_plan_step.authorized_input_hash", "YES:character")
                    .containsEntry("workflow_plan_step.authorized_draft_expires_at", "YES:timestamp with time zone")
                    .containsEntry("workflow_plan_step.activation_event_id", "YES:uuid")
                    .containsEntry("workflow_plan_step.activation_request_hash", "YES:character")
                    .containsEntry("workflow_plan_step.task_result_ref", "YES:character varying");
        }
    }

    private void assertWorkflowAuthorizedContextColumns(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name,is_nullable,data_type,is_generated
                FROM information_schema.columns
                WHERE table_schema='business' AND table_name='workflow_plan'
                  AND column_name IN ('authorized_context_snapshot','authorized_context_hash',
                    'authorized_context_schema_version','authorized_context_data_gap',
                    'authorized_context_size_bytes')
                ORDER BY column_name
                """); ResultSet result = statement.executeQuery()) {
            Map<String, String> columns = new java.util.HashMap<>();
            while (result.next()) {
                columns.put(result.getString("column_name"), result.getString("is_nullable") + ":"
                        + result.getString("data_type") + ":" + result.getString("is_generated"));
            }
            assertThat(columns)
                    .containsEntry("authorized_context_snapshot", "YES:jsonb:NEVER")
                    .containsEntry("authorized_context_hash", "YES:character:NEVER")
                    .containsEntry("authorized_context_schema_version", "YES:integer:NEVER")
                    .containsEntry("authorized_context_data_gap", "NO:boolean:NEVER")
                    .containsEntry("authorized_context_size_bytes", "YES:integer:ALWAYS");
        }
    }

    private void assertAssetIngestionLifecycleColumns(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, is_nullable, data_type
                FROM information_schema.columns
                WHERE table_schema='business' AND table_name='asset_ingestion_work'
                  AND column_name IN ('provider_url','provider_url_expires_at','ingestion_deadline_at',
                    'expected_duration_seconds','claim_token')
                ORDER BY column_name
                """); ResultSet result = statement.executeQuery()) {
            Map<String, String> columns = new java.util.HashMap<>();
            while (result.next()) {
                columns.put(result.getString("column_name"),
                        result.getString("is_nullable") + ":" + result.getString("data_type"));
            }
            assertThat(columns).containsEntry("provider_url", "YES:character varying")
                    .containsEntry("provider_url_expires_at", "YES:timestamp with time zone")
                    .containsEntry("ingestion_deadline_at", "YES:timestamp with time zone")
                    .containsEntry("expected_duration_seconds", "YES:numeric")
                    .containsEntry("claim_token", "YES:uuid");
        }
    }

    private void assertCompositeOwnershipForeignKeys(Connection connection) throws SQLException {
        Map<String, String> expectedDefinitions = Map.ofEntries(
                Map.entry("fk_conversation_message_reply",
                        "FOREIGN KEY (reply_to_message_id, user_id, conversation_id)"),
                Map.entry("fk_agent_run_request_message",
                        "FOREIGN KEY (request_message_id, user_id, conversation_id)"),
                Map.entry("fk_agent_run_response_message",
                        "FOREIGN KEY (response_message_id, user_id, conversation_id)"),
                Map.entry("fk_conversation_event_run",
                        "FOREIGN KEY (agent_run_id, user_id, conversation_id)"),
                Map.entry("fk_conversation_event_message",
                        "FOREIGN KEY (message_id, user_id, conversation_id)"),
                Map.entry("fk_asset_space", "FOREIGN KEY (space_id, user_id)"),
                Map.entry("fk_asset_primary_product",
                        "FOREIGN KEY (primary_product_id, user_id, space_id)"),
                Map.entry("fk_asset_source_task",
                        "FOREIGN KEY (source_task_id, user_id, space_id)"),
                Map.entry("fk_asset_product_relation_asset",
                        "FOREIGN KEY (asset_id, user_id, space_id)"),
                Map.entry("fk_asset_product_relation_product",
                        "FOREIGN KEY (product_id, user_id, space_id)"),
                Map.entry("fk_asset_ingestion_task",
                        "FOREIGN KEY (task_id, user_id, space_id)"),
                Map.entry("fk_asset_ingestion_execution",
                        "FOREIGN KEY (execution_id, user_id, task_id)"),
                Map.entry("fk_asset_ingestion_product",
                        "FOREIGN KEY (product_id, user_id, space_id)"),
                Map.entry("fk_geo_topic_space", "FOREIGN KEY (space_id, user_id)"),
                Map.entry("fk_geo_topic_product",
                        "FOREIGN KEY (product_id, user_id, space_id)"),
                Map.entry("fk_geo_keyword_topic",
                        "FOREIGN KEY (topic_id, user_id, space_id)"),
                Map.entry("fk_geo_question_topic",
                        "FOREIGN KEY (topic_id, user_id, space_id)"),
                Map.entry("fk_geo_competitor_set_topic",
                        "FOREIGN KEY (topic_id, user_id, space_id)"),
                Map.entry("fk_geo_competitor_member_set",
                        "FOREIGN KEY (competitor_set_id, user_id, space_id, topic_id)"),
                Map.entry("fk_geo_competitor_member_competitor",
                        "FOREIGN KEY (competitor_id, user_id, space_id)")
        );
        for (Map.Entry<String, String> expected : expectedDefinitions.entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT pg_get_constraintdef(c.oid)
                    FROM pg_constraint c
                    JOIN pg_namespace n ON n.oid = c.connamespace
                    WHERE n.nspname = 'business' AND c.conname = ?
                    """)) {
                statement.setString(1, expected.getKey());
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).as(expected.getKey()).isTrue();
                    assertThat(result.getString(1)).as(expected.getKey()).contains(expected.getValue());
                }
            }
        }
    }

    private void assertPartialUniqueIndex(Connection connection, String indexName, String predicate)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'business' AND indexname = ?
                """)) {
            statement.setString(1, indexName);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as(indexName).isTrue();
                assertThat(result.getString(1)).as(indexName)
                        .containsIgnoringCase("CREATE UNIQUE INDEX")
                        .containsIgnoringCase(predicate);
            }
        }
    }

    private PermissionResult verifyActualRuntimeDmlAndDdl(Connection connection) throws SQLException {
        boolean insertAllowed;
        boolean selectAllowed;
        boolean updateAllowed;
        boolean deleteAllowed;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO business.formal_permission_probe (id, value) VALUES (?, ?)")) {
            statement.setLong(1, 1L);
            statement.setString(2, "gate");
            insertAllowed = statement.executeUpdate() == 1;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM business.formal_permission_probe WHERE id = ?")) {
            statement.setLong(1, 1L);
            try (ResultSet result = statement.executeQuery()) {
                selectAllowed = result.next() && "gate".equals(result.getString(1));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE business.formal_permission_probe SET value = ? WHERE id = ?")) {
            statement.setString(1, "updated");
            statement.setLong(2, 1L);
            updateAllowed = statement.executeUpdate() == 1;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM business.formal_permission_probe WHERE id = ?")) {
            statement.setLong(1, 1L);
            deleteAllowed = statement.executeUpdate() == 1;
        }

        boolean createDenied = denied(connection,
                "CREATE TABLE business.formal_runtime_create_probe (id bigint PRIMARY KEY)");
        boolean alterDenied = denied(connection,
                "ALTER TABLE business.formal_permission_probe ADD COLUMN forbidden_column text");
        boolean dropDenied = denied(connection, "DROP TABLE business.formal_permission_probe");
        boolean flywayHistorySelectDenied = denied(connection,
                "SELECT installed_rank FROM business.flyway_schema_history LIMIT 1");
        boolean flywayHistoryInsertDenied = denied(connection,
                "INSERT INTO business.flyway_schema_history DEFAULT VALUES");
        boolean flywayHistoryUpdateDenied = denied(connection,
                "UPDATE business.flyway_schema_history SET description = description WHERE false");
        boolean flywayHistoryDeleteDenied = denied(connection,
                "DELETE FROM business.flyway_schema_history WHERE false");
        return new PermissionResult(selectAllowed, insertAllowed, updateAllowed, deleteAllowed,
                createDenied, alterDenied, dropDenied, flywayHistorySelectDenied,
                flywayHistoryInsertDenied, flywayHistoryUpdateDenied, flywayHistoryDeleteDenied);
    }

    private boolean denied(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            return false;
        } catch (SQLException exception) {
            if ("42501".equals(exception.getSQLState())) {
                return true;
            }
            throw exception;
        }
    }

    private void assertPostgresql18(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW server_version_num")) {
            assertThat(result.next()).isTrue();
            assertThat(Integer.parseInt(result.getString(1))).isBetween(180000, 189999);
        }
    }

    private String currentDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT current_database()")) {
            if (!result.next()) {
                throw new IllegalStateException("Unable to resolve current_database()");
            }
            return result.getString(1);
        }
    }

    private boolean hasSchemaPrivilege(Connection connection, String role, String schema, String privilege)
            throws SQLException {
        return privilege(connection, "SELECT has_schema_privilege(?, ?, ?)", role, schema, privilege);
    }

    private boolean hasDatabasePrivilege(Connection connection, String role, String database, String privilege)
            throws SQLException {
        return privilege(connection, "SELECT has_database_privilege(?, ?, ?)", role, database, privilege);
    }

    private boolean hasTablePrivilege(Connection connection, String role, String table, String privilege)
            throws SQLException {
        return privilege(connection, "SELECT has_table_privilege(?, ?, ?)", role, table, privilege);
    }

    private boolean privilege(Connection connection, String sql, String role, String object, String privilege)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, role);
            statement.setString(2, object);
            statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private Set<String> stringSet(Connection connection, String sql) throws SQLException {
        Set<String> values = new TreeSet<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for formal test database initialization");
        }
        return value;
    }

    private record ObjectSnapshot(
            Set<String> schemas,
            Set<String> tables,
            Set<String> indexes,
            Set<String> constraints
    ) {
    }

    private record PermissionResult(
            boolean selectAllowed,
            boolean insertAllowed,
            boolean updateAllowed,
            boolean deleteAllowed,
            boolean createDenied,
            boolean alterDenied,
            boolean dropDenied,
            boolean flywayHistorySelectDenied,
            boolean flywayHistoryInsertDenied,
            boolean flywayHistoryUpdateDenied,
            boolean flywayHistoryDeleteDenied
    ) {
    }
}
