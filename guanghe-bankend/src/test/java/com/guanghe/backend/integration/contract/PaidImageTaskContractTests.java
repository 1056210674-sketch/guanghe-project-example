package com.guanghe.backend.integration.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaidImageTaskContractTests {
    private static final Path V004 = Path.of("src", "main", "resources", "db", "migration",
            "V004__paid_image_task_control_plane.sql");
    private static final Path V005 = Path.of("src", "main", "resources", "db", "migration",
            "V005__paid_task_execution_recovery.sql");
    private static final Path V006 = Path.of("src", "main", "resources", "db", "migration",
            "V006__generated_assets_and_oss_ingestion.sql");
    private static final Path V007 = Path.of("src", "main", "resources", "db", "migration",
            "V007__video_tasks_and_asset_ingestion.sql");
    private static final Path V008 = Path.of("src", "main", "resources", "db", "migration",
            "V008__form_task_drafts_and_queued_task_watchdog.sql");
    private static final Path V027 = Path.of("src", "main", "resources", "db", "migration",
            "V027__conversation_task_delivery_indexes.sql");
    private static final Path RABBIT = Path.of("src", "main", "resources", "contracts", "rabbit",
            "task-execution-requested-v1.schema.json");

    @Test
    void v004AddsOnlyThePaidTaskControlPlaneAndLeavesEarlierMigrationsImmutable() throws Exception {
        String sql = Files.readString(V004).toLowerCase(Locale.ROOT);
        assertThat(sql).contains(
                "create table business.task_draft", "create table business.task_confirmation",
                "create table business.point_account", "create table business.point_reservation",
                "create table business.point_ledger", "create table business.business_task",
                "create table business.task_execution", "create table business.business_task_event",
                "create table business.outbox_event", "create table business.inbox_event",
                "uk_business_task_idempotency", "uk_inbox_consumer_event",
                "ck_business_task_status", "fk_conversation_event_task",
                "provider_request_id uuid", "provider_task_id varchar(256)",
                "submission_state varchar(16)", "latency_ms integer");
        assertThat(sql).doesNotContain("create table business.asset", "create table business.prompt",
                "create table business.pricing_version", "cancelled");
    }

    @Test
    void v005AddsOnlyTheOutboxExecutionGenerationNeededForLeaseRecovery() throws Exception {
        String sql = Files.readString(V005).toLowerCase(Locale.ROOT);
        assertThat(sql).contains("alter table business.outbox_event",
                "drop constraint uk_outbox_task_event", "add column execution_attempt",
                "ck_outbox_execution_attempt", "ck_outbox_payload_execution_attempt",
                "uk_outbox_task_execution_attempt");
        assertThat(sql).doesNotContain("create table", "drop table", "business.point_account",
                "business.business_task", "business.task_execution");
    }

    @Test
    void v006AddsOwnedAssetsRelationsAndOneRecoverableIngestionWork() throws Exception {
        String sql = Files.readString(V006).toLowerCase(Locale.ROOT);
        assertThat(sql).contains("create table business.asset (",
                "create table business.asset_product_relation",
                "create table business.asset_ingestion_work",
                "uk_asset_source_task", "uk_asset_ingestion_task_artifact",
                "fk_asset_source_task", "fk_asset_product_relation_asset",
                "fk_asset_product_relation_product", "status = 'processing'",
                "provider_url_expires_at", "lease_expires_at", "claim_token uuid",
                "last_error_code", "provider_url is null", "provider_url_expires_at is null");
        assertThat(sql).doesNotContain("provider_url varchar(4096) not null",
                "provider_url_expires_at timestamptz not null");
        assertThat(sql).doesNotContain("alter table business.point_account",
                "alter table business.task_execution add column", "create table business.provider");
    }

    @Test
    void sharedRabbitMessageSchemaIsByteForByteBusinessCompatible() throws Exception {
        String normalized = Files.readString(RABBIT).replace("\r\n", "\n").replace('\r', '\n');
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        assertThat(hash).isEqualTo("e13ea99e873c34a9ccad011148b1c53d6d606109b206b27b875aa20f150e2928");
        JsonNode schema = new ObjectMapper().readTree(Files.readString(RABBIT));
        assertThat(schema.path("required")).extracting(JsonNode::asText).contains(
                "eventId", "taskId", "userId", "spaceId", "taskType", "traceparent", "attempt");
    }

    @Test
    void v007ExtendsExistingAssetIngestionForWanVideoWithoutCreatingParallelTables() throws Exception {
        String sql=Files.readString(V007).toLowerCase(Locale.ROOT);
        assertThat(sql).contains("expected_duration_seconds", "ingestion_deadline_at",
                "provider_code = 'wan_video'", "provider_model = 'wan3.0-video'",
                "expected_content_type = 'video/mp4'", "duration_seconds > 0",
                "object_key like '%.mp4'", "provider_url is null");
        assertThat(sql).doesNotContain("create table", "create schema", "alter table business.business_task",
                "alter table business.point_account", "alter table business.task_execution");
    }

    @Test
    void v008AllowsAuditableFormDraftsAndIndexesOnlyStaleQueuedTasks() throws Exception {
        String sql=Files.readString(V008).toLowerCase(Locale.ROOT);
        assertThat(sql).contains("alter table business.task_draft", "draft_source",
                "alter column agent_run_id drop not null", "draft_source = 'chat'",
                "agent_run_id is not null", "draft_source = 'form'", "agent_run_id is null",
                "task_draft_create", "task_draft_revision", "uk_task_draft_idempotency",
                "idx_business_task_stale_queue", "where status = 'queued'");
        assertThat(sql).doesNotContain("create table", "drop table", "alter table business.point_account",
                "alter table business.task_execution");
    }

    @Test
    void v027IndexesConversationTaskHistoryAndActiveDeliveryChecksWithoutChangingTables() throws Exception {
        String sql = Files.readString(V027).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        assertThat(sql).contains(
                "create index idx_business_task_conversation_delivery on business.business_task "
                        + "( user_id, conversation_id, "
                        + "(status in ('queued', 'running', 'retry_wait')) desc, "
                        + "created_at desc, id desc ) "
                        + "where conversation_id is not null",
                "create index idx_business_task_conversation_active on business.business_task "
                        + "(user_id, conversation_id) where conversation_id is not null "
                        + "and status in ('queued', 'running', 'retry_wait')");
        assertThat(sql).doesNotContain("create table", "alter table", "drop table", "drop index");
    }
}
