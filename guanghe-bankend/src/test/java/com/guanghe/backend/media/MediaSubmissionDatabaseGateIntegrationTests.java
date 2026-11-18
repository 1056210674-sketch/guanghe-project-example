package com.guanghe.backend.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.article.ArticleMapper;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.product.ProductService;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskControlService;
import com.guanghe.backend.task.TaskDtos;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real migrated PostgreSQL/MyBatis and transaction boundaries; no provider network calls. */
@EnabledIfEnvironmentVariable(named = "DATABASE_GATE_ENABLED", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MediaSubmissionDatabaseGateIntegrationTests {
    private static DataSource source;
    private static final AtomicLong SEQUENCE = new AtomicLong(963000000000000000L);

    @BeforeAll
    static void migrateIsolatedDatabaseAndUseRestrictedRuntimeRole() throws Exception {
        String database = required("GATE_EXPECTED_DATABASE");
        assertThat(database).matches("^zhixiaohe_gate_video_[a-f0-9]{10}$");
        String suffix = database.substring("zhixiaohe_gate_video_".length());
        String migrationUser = required("GATE_MIGRATION_DB_USERNAME");
        String runtimeUser = required("GATE_RUNTIME_DB_USERNAME");
        assertThat(migrationUser).isEqualTo("gate_migration_" + suffix);
        assertThat(runtimeUser).isEqualTo("gate_runtime_" + suffix);
        String migrationUrl = required("GATE_MIGRATION_DB_URL");
        String migrationPassword = required("GATE_MIGRATION_DB_PASSWORD");
        String runtimeUrl = required("GATE_RUNTIME_DB_URL");
        String runtimePassword = required("GATE_RUNTIME_DB_PASSWORD");
        try (var migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword);
             var runtime = DriverManager.getConnection(runtimeUrl, runtimeUser, runtimePassword)) {
            assertThat(scalar(migration, "SELECT current_database()")).isEqualTo(database);
            assertThat(scalar(runtime, "SELECT current_database()")).isEqualTo(database);
        }
        var flyway = Flyway.configure().dataSource(migrationUrl, migrationUser, migrationPassword)
                .schemas("business", "agent").defaultSchema("business").cleanDisabled(true).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            execute(migration, "GRANT USAGE ON SCHEMA business TO \"" + runtimeUser + "\"; "
                    + "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA business TO \"" + runtimeUser + "\"");
        }
        var runtime = new PGSimpleDataSource();
        runtime.setUrl(runtimeUrl); runtime.setUser(runtimeUser); runtime.setPassword(runtimePassword);
        runtime.setApplicationName("media-submission-gate-" + suffix);
        runtime.setConnectTimeout(5); runtime.setSocketTimeout(30);
        runtime.setOptions("-c statement_timeout=20000 -c lock_timeout=15000");
        source = runtime;
        try (var connection = source.getConnection()) {
            assertThat(scalar(connection, "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user")).isEqualTo("f");
            assertThatThrownBy(() -> execute(connection, "CREATE TABLE business.media_gate_runtime_ddl_forbidden(id bigint)"))
                    .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    @Order(1) // The local queue is global: claim before the other tests add pending fixture rows.
    void confirmedMediaQuoteCommitsOneLocalOutboxWithNumericExecutionAttempt() throws Exception {
        var h = new Harness();
        var s = h.scenario();
        var quote = h.quote(s, UUID.randomUUID().toString());
        assertThat(quote.quotedPoints()).isEqualTo(200);
        h.assertWallet(s, 1000, 0);
        var accepted = h.confirm(s, quote, UUID.randomUUID().toString());
        assertThat(accepted.response().status()).isEqualTo("QUEUED");
        assertThat(accepted.response().taskType()).isEqualTo("MEDIA");
        assertThat(accepted.browserEvent()).isNull();
        long taskId = Long.parseLong(accepted.response().id());
        h.assertCommittedOnce(s, quote, taskId);
        var payload = h.json.readTree(h.scalar("SELECT payload::text FROM business.outbox_event WHERE aggregate_id=" + taskId));
        assertThat(payload.path("attempt").isIntegralNumber()).isTrue();
        assertThat(payload.path("attempt").intValue()).isEqualTo(1);
        assertThat(payload.path("taskId").isTextual()).isTrue();
        assertThat(payload.path("taskId").asText()).isEqualTo(accepted.response().id());
        assertThat(payload.path("batchId").isTextual()).isTrue();
        assertThat(payload.path("batchId").asText()).isEqualTo(h.scalar("SELECT id FROM business.media_submission_batch WHERE task_id=" + taskId));
        assertThat(h.scalar("SELECT execution_attempt || ':' || event_type || ':' || exchange_name || ':' || routing_key || ':' || status FROM business.outbox_event WHERE aggregate_id=" + taskId))
                .isEqualTo("1:MEDIA_SUBMISSION_REQUESTED:LOCAL:media.submit:PENDING");
        assertThat(h.scalar("SELECT attempts FROM business.outbox_event WHERE aggregate_id=" + taskId)).isEqualTo("0");
        var transactions = new TransactionTemplate(new JdbcTransactionManager(source));
        OffsetDateTime claimAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);
        var firstClaim = transactions.execute(status -> h.mediaMapper.claimLocalOutbox(claimAt, claimAt.plusMinutes(1)).orElseThrow());
        assertThat(firstClaim.taskId()).isEqualTo(taskId);
        assertThat(firstClaim.attempts()).isEqualTo(1);
        assertThat(h.json.readTree(firstClaim.payloadJson()).path("attempt").intValue()).isEqualTo(1);
        assertThat(h.scalar("SELECT execution_attempt || ':' || (payload->>'attempt') || ':' || attempts FROM business.outbox_event WHERE aggregate_id=" + taskId))
                .isEqualTo("1:1:1");
        OffsetDateTime retryAt = claimAt.plusSeconds(5);
        assertThat(transactions.<Integer>execute(status -> h.mediaMapper.finishLocalOutbox(firstClaim.id(), firstClaim.attempts(),
                "PENDING", retryAt, "MEDIA_GATE_SYNTHETIC_RETRY", claimAt))).isEqualTo(1);
        var secondClaim = transactions.execute(status -> h.mediaMapper.claimLocalOutbox(retryAt, retryAt.plusMinutes(1)).orElseThrow());
        assertThat(secondClaim.id()).isEqualTo(firstClaim.id());
        assertThat(secondClaim.attempts()).isEqualTo(2);
        assertThat(h.json.readTree(secondClaim.payloadJson()).path("attempt").intValue()).isEqualTo(1);
        assertThat(h.scalar("SELECT execution_attempt || ':' || (payload->>'attempt') || ':' || attempts FROM business.outbox_event WHERE aggregate_id=" + taskId))
                .isEqualTo("1:1:2");
        // A stale lease holder cannot acknowledge the second delivery; these are local dispatch attempts,
        // not new business executions. No scheduler or provider method is invoked by this Gate.
        assertThat(transactions.<Integer>execute(status -> h.mediaMapper.finishLocalOutbox(firstClaim.id(), firstClaim.attempts(),
                "PUBLISHED", retryAt, null, retryAt))).isZero();
        assertThat(transactions.<Integer>execute(status -> h.mediaMapper.finishLocalOutbox(secondClaim.id(), secondClaim.attempts(),
                "PUBLISHED", retryAt, null, retryAt))).isEqualTo(1);
        h.assertCommittedOnce(s, quote, taskId);
        verifyNoInteractions(h.provider);
    }

    @Test
    void quoteAndConfirmationReplayCannotDuplicateOrdersFrozenPointsOrOutbox() throws Exception {
        var h = new Harness();
        var s = h.scenario();
        String quoteKey = UUID.randomUUID().toString();
        var quote = h.quote(s, quoteKey);
        assertThat(h.quote(s, quoteKey).id()).isEqualTo(quote.id());
        String confirmKey = UUID.randomUUID().toString();
        var first = h.confirm(s, quote, confirmKey);
        assertThat(h.confirm(s, quote, confirmKey).response().id()).isEqualTo(first.response().id());
        // A second browser attempt with a fresh key still resolves the already-confirmed draft.
        assertThat(h.confirm(s, quote, UUID.randomUUID().toString()).response().id()).isEqualTo(first.response().id());
        h.assertCommittedOnce(s, quote, Long.parseLong(first.response().id()));
        assertThat(h.scalar("SELECT count(*) FROM business.task_draft WHERE user_id=" + s.userId)).isEqualTo("1");
        verifyNoInteractions(h.provider);
    }

    @Test
    void missingAttemptFailsRealCheckAndRollsBackConfirmationWalletTaskAndMediaRows() throws Exception {
        var h = new Harness();
        var s = h.scenario();
        var quote = h.quote(s, UUID.randomUUID().toString());
        // Test-only fault injection delegates to the real MyBatis INSERT and real PostgreSQL CHECK.
        var faultyMapper = mock(MediaMapper.class, delegatesTo(h.mediaMapper));
        doAnswer(invocation -> {
            ObjectNode payload = (ObjectNode) h.json.readTree((String) invocation.getArgument(3));
            payload.remove("attempt");
            return h.mediaMapper.insertLocalOutbox(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), h.json.writeValueAsString(payload),
                    invocation.getArgument(4), invocation.getArgument(5));
        }).when(faultyMapper).insertLocalOutbox(anyLong(), anyLong(), anyLong(), anyString(), any(), any());
        var faultyTasks = h.newTaskService(h.newMediaService(faultyMapper));
        String confirmKey = UUID.randomUUID().toString();
        assertThatThrownBy(() -> faultyTasks.confirm(user(s.userId), Long.parseLong(quote.id()), confirmKey, proof(quote)))
                .satisfies(failure -> {
                    Throwable root = failure;
                    while (root.getCause() != null) root = root.getCause();
                    assertThat(root).isInstanceOf(PSQLException.class);
                    var sql = (PSQLException) root;
                    assertThat(sql.getSQLState()).isEqualTo("23514");
                    assertThat(sql.getServerErrorMessage().getConstraint()).isEqualTo("ck_outbox_payload_execution_attempt");
                });
        verify(faultyMapper).insertBatch(any());
        verify(faultyMapper).insertSubmission(any());
        verify(faultyMapper).insertLocalOutbox(anyLong(), anyLong(), anyLong(), anyString(), any(), any());
        h.assertWallet(s, 1000, 0);
        for (String table : List.of("business_task", "point_reservation", "point_ledger", "business_task_event",
                "media_submission_batch", "media_submission", "media_submission_event", "outbox_event")) {
            assertThat(h.scalar("SELECT count(*) FROM business." + table + " WHERE user_id=" + s.userId)).as(table).isEqualTo("0");
        }
        assertThat(h.scalar("SELECT status || ':' || (confirmed_at IS NULL) FROM business.task_draft WHERE id=" + quote.id()))
                .isEqualTo("WAITING_CONFIRMATION:true");
        assertThat(h.scalar("SELECT status || ':' || (used_at IS NULL) FROM business.task_confirmation WHERE draft_id=" + quote.id()))
                .isEqualTo("AVAILABLE:true");
        // Recovery of the very same proof/key is safe after rollback, not a second charge.
        var recovered = h.confirm(s, quote, confirmKey);
        h.assertCommittedOnce(s, quote, Long.parseLong(recovered.response().id()));
        verifyNoInteractions(h.provider);
    }

    private record Scenario(long userId, long articleId, long snapshotId, long resourceId) { }

    private static final class Harness {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        final MediaProviderAdapter provider = mock(MediaProviderAdapter.class);
        final PaidTaskProperties paid = new PaidTaskProperties();
        final MediaProperties properties = new MediaProperties();
        final MediaMapper mediaMapper;
        final TaskControlMapper taskMapper;
        final ArticleMapper articles;
        final ConversationMapper conversations;
        final MediaSubmissionService media;
        final TaskControlService tasks;

        Harness() {
            when(ids.nextId()).thenAnswer(ignored -> SEQUENCE.incrementAndGet());
            var configuration = new Configuration(new Environment("media-submission-gate", new SpringManagedTransactionFactory(), source));
            configuration.setMapUnderscoreToCamelCase(true);
            for (var mapper : List.of(MediaMapper.class, TaskControlMapper.class, ArticleMapper.class, ConversationMapper.class)) configuration.addMapper(mapper);
            var sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
            mediaMapper = sessions.getMapper(MediaMapper.class); taskMapper = sessions.getMapper(TaskControlMapper.class);
            articles = sessions.getMapper(ArticleMapper.class); conversations = sessions.getMapper(ConversationMapper.class);
            paid.setEnabled(true); paid.setTokenSecret("isolated-media-gate-only-at-least-32-characters");
            properties.setEnabled(true); properties.setExecutionEnabled(true);
            properties.setBaseUrl("https://media-gate.invalid"); properties.setAppKey("0123456789abcdef0123456789abcdef");
            media = newMediaService(mediaMapper);
            tasks = newTaskService(media);
        }

        MediaSubmissionService newMediaService(MediaMapper mapper) {
            return transactional(new MediaSubmissionService(mapper, taskMapper, articles, mock(ProductService.class),
                    provider, properties, paid, ids, json, new JdbcTransactionManager(source)));
        }

        TaskControlService newTaskService(MediaSubmissionService mediaService) {
            @SuppressWarnings("unchecked") ObjectProvider<MediaSubmissionService> mediaProvider = mock(ObjectProvider.class);
            when(mediaProvider.getIfAvailable()).thenReturn(mediaService);
            return transactional(new TaskControlService(taskMapper, conversations, ids, json, paid,
                    new AssetIngestionProperties(), null, null, null, mediaProvider,
                    new JdbcTransactionManager(source), ObservabilitySupport.noop()));
        }

        Scenario scenario() throws SQLException {
            long userId = ids.nextId(), spaceId = ids.nextId(), articleId = ids.nextId();
            long snapshotId = ids.nextId(), resourceId = ids.nextId();
            try (var connection = source.getConnection()) {
                execute(connection, "INSERT INTO business.account(id,username,username_normalized,password_hash,role,status,created_at,updated_at) VALUES(" + userId + ",'gate" + userId + "','gate" + userId + "',repeat('a',32),'USER','ACTIVE',now(),now());"
                        + "INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,brand_profile,created_at,updated_at) VALUES(" + spaceId + "," + userId + ",'Synthetic media','synthetic media',NULL,'{}',now(),now());"
                        + "INSERT INTO business.point_account(user_id,available_points,frozen_points,created_at,updated_at) VALUES(" + userId + ",1000,0,now(),now());"
                        + "INSERT INTO business.article(id,user_id,space_id,title,body_html,body_text,status,workflow_stage,version,created_at,updated_at) VALUES(" + articleId + "," + userId + "," + spaceId + ",'Synthetic publication','<p>Synthetic article.</p>','Synthetic article.','COMPLETED','READY_TO_SUBMIT',1,now(),now());"
                        + "INSERT INTO business.article_submission_snapshot(id,article_id,user_id,space_id,article_version,title,body_html,body_text,body_hash,outline,asset_manifest,created_at) VALUES(" + snapshotId + "," + articleId + "," + userId + "," + spaceId + ",1,'Synthetic publication','<p>Synthetic article.</p>','Synthetic article.',repeat('a',64),'[]','[]',now());"
                        + "INSERT INTO business.media_resource(id,provider_code,upstream_model_id,upstream_resource_id,name,status,upstream_price,last_seen_at,synced_at) VALUES(" + resourceId + ",'MEDIA_PROCUREMENT','1','gate-" + resourceId + "','Synthetic publisher','ACTIVE',1.0000,now(),now());");
            }
            return new Scenario(userId, articleId, snapshotId, resourceId);
        }

        TaskDtos.DraftResponse quote(Scenario s, String key) {
            return media.quote(user(s.userId), key, new MediaDtos.QuoteRequest(Long.toString(s.articleId),
                    Long.toString(s.snapshotId), List.of(new MediaDtos.QuoteItem(Long.toString(s.resourceId), null))));
        }

        TaskControlService.ConfirmedTask confirm(Scenario s, TaskDtos.DraftResponse quote, String key) {
            return tasks.confirm(user(s.userId), Long.parseLong(quote.id()), key, proof(quote));
        }

        void assertCommittedOnce(Scenario s, TaskDtos.DraftResponse quote, long taskId) throws SQLException {
            assertWallet(s, 800, 200);
            for (String table : List.of("business_task", "point_reservation", "point_ledger", "business_task_event",
                    "media_submission_batch", "media_submission", "media_submission_event", "outbox_event")) {
                assertThat(scalar("SELECT count(*) FROM business." + table + " WHERE user_id=" + s.userId)).as(table).isEqualTo("1");
            }
            assertThat(scalar("SELECT status FROM business.task_draft WHERE id=" + quote.id())).isEqualTo("CONFIRMED");
            assertThat(scalar("SELECT status FROM business.task_confirmation WHERE draft_id=" + quote.id())).isEqualTo("CONSUMED");
            assertThat(scalar("SELECT frozen_points || ':' || settled_points || ':' || released_points FROM business.point_reservation WHERE task_id=" + taskId)).isEqualTo("200:0:0");
            assertThat(scalar("SELECT entry_type FROM business.point_ledger WHERE user_id=" + s.userId)).isEqualTo("TASK_FREEZE");
            assertThat(scalar("SELECT status || ':' || submission_state FROM business.media_submission WHERE task_id=" + taskId)).isEqualTo("QUEUED:NOT_SUBMITTED");
        }

        void assertWallet(Scenario s, int available, int frozen) throws SQLException {
            assertThat(scalar("SELECT available_points || ':' || frozen_points FROM business.point_account WHERE user_id=" + s.userId)).isEqualTo(available + ":" + frozen);
        }

        String scalar(String sql) throws SQLException {
            try (var connection = source.getConnection()) { return MediaSubmissionDatabaseGateIntegrationTests.scalar(connection, sql); }
        }
    }

    private static TaskDtos.ConfirmRequest proof(TaskDtos.DraftResponse quote) {
        return new TaskDtos.ConfirmRequest(quote.confirmationToken(), quote.inputHash(), quote.pricingVersionId());
    }
    private static AuthenticatedAccount user(long id) { return new AuthenticatedAccount(id, "gate", AccountRole.USER, 1, AccountStatus.ACTIVE, null, "gate", null); }
    @SuppressWarnings("unchecked") private static <T> T transactional(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new JdbcTransactionManager(source), new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing isolated Gate variable: " + key);
        return value;
    }
    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new IllegalStateException("Missing Gate scalar");
            return result.getString(1);
        }
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
