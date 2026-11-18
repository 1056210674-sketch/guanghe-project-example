package com.guanghe.backend.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.AssetIngestionService;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.asset.DownloadedArtifact;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.ConversationRunPersistenceService;
import com.guanghe.backend.article.ArticleService;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.integration.internal.client.PythonChatRunEvent;
import com.guanghe.backend.geo.GeoInterpretationMapper;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskControlService;
import com.guanghe.backend.task.TaskDtos;
import com.guanghe.backend.workflow.WorkflowExecutionMapper;
import com.guanghe.backend.workflow.WorkflowExecutionRecords;
import com.guanghe.backend.workflow.WorkflowPlanMapper;
import com.guanghe.backend.workflow.WorkflowPlanRecords;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.AdditionalAnswers.delegatesTo;

@EnabledIfEnvironmentVariable(named = "DATABASE_GATE_ENABLED", matches = "true")
class DatabaseGateIntegrationTests {

    @Test
    void geoCompetitiveDiscoveryUsesOwnedDurableEvidenceAndLeases() throws Exception {
        // Reuse the historical evidence fixture and its real JDBC mapping checks through V042.
        geoCollectionMethodMigrationPreservesHistoricalEvidence();
        String migrationUrl = requiredEnvironment("GATE_MIGRATION_DB_URL");
        String migrationUser = requiredEnvironment("GATE_MIGRATION_DB_USERNAME");
        String migrationPassword = requiredEnvironment("GATE_MIGRATION_DB_PASSWORD");
        String runtimeUrl = requiredEnvironment("GATE_RUNTIME_DB_URL");
        String runtimeUser = requiredEnvironment("GATE_RUNTIME_DB_USERNAME");
        String runtimePassword = requiredEnvironment("GATE_RUNTIME_DB_PASSWORD");
        String originalReport;
        try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            execute(migration, """
                    INSERT INTO business.geo_report(id,run_id,task_id,user_id,topic_id,status,synthesis,synthesis_usage,
                        summary,method_version,sample_size,generated_at,created_at)
                    VALUES(942081,942051,942041,942001,942020,'PARTIAL','{}','{}','historical report',
                        'geo-deterministic-v1',6,now(),now());
                    INSERT INTO business.task_draft SELECT (jsonb_populate_record(NULL::business.task_draft,
                        to_jsonb(d)||'{"id":942032,"idempotency_key":"04300000-0000-0000-0000-000000000002"}'::jsonb)).*
                        FROM business.task_draft d WHERE id=942031;
                    INSERT INTO business.business_task SELECT (jsonb_populate_record(NULL::business.business_task,
                        to_jsonb(t)||'{"id":942042,"draft_id":942032,"idempotency_key":"04310000-0000-0000-0000-000000000002"}'::jsonb)).*
                        FROM business.business_task t WHERE id=942041;
                    INSERT INTO business.geo_run SELECT (jsonb_populate_record(NULL::business.geo_run,
                        to_jsonb(r)||'{"id":942052,"task_id":942042}'::jsonb)).* FROM business.geo_run r WHERE id=942051;
                    INSERT INTO business.geo_report SELECT (jsonb_populate_record(NULL::business.geo_report,
                        to_jsonb(r)||'{"id":942082,"run_id":942052,"task_id":942042}'::jsonb)).*
                        FROM business.geo_report r WHERE id=942081;
                    INSERT INTO business.competitor(id,user_id,space_id,name,name_normalized,competitor_type,status,created_at,updated_at)
                    VALUES(943090,942001,942010,'Existing Brand','existing brand','DIRECT','ACTIVE',now(),now());
                    INSERT INTO business.competitor_alias(id,user_id,space_id,competitor_id,alias,alias_normalized,created_at)
                    VALUES(943091,942001,942010,943090,'Brand Alias','brand alias',now());
                    INSERT INTO business.geo_citation(id,observation_id,run_id,user_id,sequence_no,title,url,domain,snippet,observed_at,created_at)
                    SELECT 943100+n,942061,942051,942001,n,'source '||n,'https://example.com/'||n,'example.com',NULL,now(),now()
                    FROM generate_series(1,4) AS n;
                    """);
            try (Statement s = migration.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT md5(to_jsonb(r)::text) FROM business.geo_report r WHERE id=942081")) {
                assertThat(r.next()).isTrue(); originalReport = r.getString(1);
            }
        }
        Flyway upgrade = Flyway.configure().dataSource(migrationUrl,migrationUser,migrationPassword)
                .defaultSchema("business").schemas("business","agent").createSchemas(true)
                .locations("classpath:db/migration").target("043").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(upgrade.info().current().getVersion().toString()).isEqualTo("043");
        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setUrl(runtimeUrl); source.setUser(runtimeUser); source.setPassword(runtimePassword);
        Configuration config = new Configuration(new Environment("geo-competitive-gate",new JdbcTransactionFactory(),source));
        config.addMapper(com.guanghe.backend.geo.GeoCompetitiveMapper.class);
        config.addMapper(com.guanghe.backend.geo.GeoTaskMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        OffsetDateTime now = OffsetDateTime.now();
        UUID token = UUID.randomUUID();
        try (SqlSession session = factory.openSession(true)) {
            var mapper = session.getMapper(com.guanghe.backend.geo.GeoCompetitiveMapper.class);
            var candidate = new com.guanghe.backend.geo.GeoCompetitiveRecords.Candidate(943001,942001,942010,942020,
                    "Evidence Brand","evidence brand","BRAND","PENDING",1,null,942081,942081,
                    "[{\"reportId\":\"942081\",\"unitKey\":\"942071:DEEPSEEK:1\",\"quote\":\"Evidence Brand\",\"signal\":\"MENTION\"}]",now,now);
            assertThat(mapper.upsertCandidate(candidate)).isEqualTo(1);
            var read = mapper.findName(942001,942020,"evidence brand").orElseThrow();
            assertThat(read.name()).isEqualTo("Evidence Brand");
            assertThat(read.competitorId()).isNull();
            assertThat(read.firstSeenAt()).isNotNull();
            assertThat(mapper.candidate(943001,999999)).isEmpty();
            assertThat(mapper.candidates(999999,942020,"PENDING",0,20)).isEmpty();
            assertThat(mapper.countCandidates(942001,942020,"PENDING")).isEqualTo(1);
            assertThat(mapper.reportCandidates(942001,List.of(943001L))).hasSize(1);
            assertThat(mapper.reportCandidates(999999,List.of(943001L))).isEmpty();
            var matches=mapper.existingCompetitors(942001,942010,List.of("existing brand","brand alias"));
            assertThat(matches).hasSize(2);
            assertThat(matches).allSatisfy(m -> assertThat(m.competitorId()).isEqualTo(943090));
            assertThat(mapper.existingCompetitors(999999,942010,List.of("existing brand"))).isEmpty();
            var taskMapper=session.getMapper(com.guanghe.backend.geo.GeoTaskMapper.class);
            var citations=taskMapper.findInterpretationCitations(942051,942001);
            assertThat(citations).hasSize(3);
            assertThat(citations).allSatisfy(c -> {assertThat(c.totalForObservation()).isEqualTo(4);assertThat(c.snippet()).isNull();});
            assertThat(taskMapper.findInterpretationCitations(942051,999999)).isEmpty();
            assertThat(mapper.decide(943001,999999,1,"IGNORED",null)).isZero();
            assertThat(mapper.decide(943001,942001,1,"IGNORED",null)).isEqualTo(1);
            assertThat(mapper.decide(943001,942001,1,"IGNORED",null)).isZero();
            assertThat(mapper.upsertCandidate(candidate)).isEqualTo(1);
            assertThat(mapper.candidate(943001,942001).orElseThrow().status()).isEqualTo("IGNORED");
            assertThat(mapper.candidate(943001,942001).orElseThrow().version()).isEqualTo(2);
            // V2 must find old full-pair keys without rewriting rows or losing ignored decisions.
            String bilingualEvidence="[{\"reportId\":\"942081\",\"unitKey\":\"942071:DEEPSEEK:1\",\"quote\":\"虎牌（TIGER）有保温杯\",\"signal\":\"MENTION\"}]";
            var oldPair=new com.guanghe.backend.geo.GeoCompetitiveRecords.Candidate(943002,942001,942010,942020,
                    "虎牌（TIGER）","虎牌(tiger)","BRAND","PENDING",1,null,942081,942081,bilingualEvidence,now,now);
            assertThat(mapper.upsertCandidate(oldPair)).isEqualTo(1);
            assertThat(mapper.decide(943002,942001,1,"IGNORED",null)).isEqualTo(1);
            var compatible=mapper.compatibleBrandCandidates(942001,942020,"虎牌");
            assertThat(compatible).hasSize(1);assertThat(compatible.getFirst().id()).isEqualTo(943002);
            assertThat(compatible.getFirst().status()).isEqualTo("IGNORED");
            assertThat(mapper.compatibleBrandCandidates(999999,942020,"虎牌")).isEmpty();
            assertThat(mapper.compatibleBrandCandidates(942001,999999,"虎牌")).isEmpty();
            var reused=compatible.getFirst();
            assertThat(mapper.upsertCandidate(new com.guanghe.backend.geo.GeoCompetitiveRecords.Candidate(943003,942001,942010,942020,
                    reused.name(),reused.nameNormalized(),"BRAND","PENDING",1,null,942081,942082,reused.evidenceJson(),now,now.plusSeconds(1)))).isEqualTo(1);
            assertThat(mapper.candidate(943003,942001)).isEmpty();
            assertThat(mapper.compatibleBrandCandidates(942001,942020,"虎牌").getFirst().version()).isEqualTo(2);
            assertThat(mapper.compatibleBrandCandidates(942001,942020,"虎牌").getFirst().status()).isEqualTo("IGNORED");
            var canonicalPair=new com.guanghe.backend.geo.GeoCompetitiveRecords.Candidate(943004,942001,942010,942020,
                    "膳魔师（THERMOS）","膳魔师","BRAND","PENDING",1,null,942081,942081,bilingualEvidence,now,now);
            assertThat(mapper.upsertCandidate(canonicalPair)).isEqualTo(1);
            assertThat(mapper.compatibleBrandCandidates(942001,942020,"膳魔师")).extracting(com.guanghe.backend.geo.GeoCompetitiveRecords.Candidate::id).containsExactly(943004L);
            // If two reports both read "absent", the second different pair must not overwrite the first proof.
            var originalCanonical=mapper.candidate(943004,942001).orElseThrow();
            assertThat(mapper.upsertCandidate(new com.guanghe.backend.geo.GeoCompetitiveRecords.Candidate(943007,942001,942010,942020,
                    "膳魔师（OTHERBRAND）","膳魔师","BRAND","PENDING",1,null,942081,942082,"[]",now,now.plusSeconds(2)))).isZero();
            assertThat(mapper.candidate(943004,942001).orElseThrow()).isEqualTo(originalCanonical);
            assertThat(mapper.candidate(943007,942001)).isEmpty();
            assertThat(mapper.upsertCandidate(new com.guanghe.backend.geo.GeoCompetitiveRecords.Candidate(943005,942001,942010,942020,
                    "虎牌（500ML）","虎牌(500ml)","PRODUCT","PENDING",1,null,942081,942081,bilingualEvidence,now,now))).isEqualTo(1);
            assertThat(mapper.compatibleBrandCandidates(942001,942020,"虎牌")).hasSize(1);
            assertThat(mapper.upsertCandidate(new com.guanghe.backend.geo.GeoCompetitiveRecords.Candidate(943006,942001,942010,942020,
                    "虎牌","虎牌","BRAND","PENDING",1,null,942081,942081,bilingualEvidence,now,now))).isEqualTo(1);
            assertThat(mapper.compatibleBrandCandidates(942001,942020,"虎牌")).hasSize(2); // Caller rejects ambiguity instead of merging.
            assertThat(mapper.enqueue(943011,942081,942001,"a".repeat(64),null,now)).isEqualTo(1);
            try (Connection constraint=DriverManager.getConnection(runtimeUrl,runtimeUser,runtimePassword)) {
                assertSqlState(constraint,"23505","""
                        INSERT INTO business.geo_report_competitive_insight
                        SELECT (jsonb_populate_record(NULL::business.geo_report_competitive_insight,
                            to_jsonb(w)||'{"id":943099,"report_id":942082}'::jsonb)).*
                        FROM business.geo_report_competitive_insight w WHERE id=943011
                        """);
            }
            assertThat(mapper.countActive(942001)).isEqualTo(1);
            assertThat(mapper.find(942081,999999)).isEmpty();
            var queued = mapper.find(942081,942001).orElseThrow();
            assertThat(queued.claimToken()).isNull();
            assertThat(queued.resultJson()).isNull();
            assertThat(mapper.lockNext(now.plusSeconds(1)).orElseThrow().id()).isEqualTo(943011);
            assertThat(mapper.claim(943011,token,now)).isEqualTo(1);
            var running = mapper.find(942081,942001).orElseThrow();
            assertThat(running.claimToken()).isEqualTo(token);
            assertThat(running.deadlineAt()).isBefore(running.leaseExpiresAt());
            assertThat(mapper.finish(943011,1,UUID.randomUUID(),"FAILED","MODEL_FAILED",null,"{}",now)).isZero();
            assertThat(mapper.finish(943011,2,token,"FAILED","MODEL_FAILED",null,"{}",now)).isZero();
            assertThat(mapper.capacity(943011,1,token,now.plusSeconds(1))).isEqualTo(1);
            assertThat(mapper.find(942081,942001).orElseThrow().attempts()).isEqualTo(1);
            assertThat(mapper.find(942081,942001).orElseThrow().claimToken()).isNull();
            assertThat(mapper.lockNext(now.plusSeconds(2))).isEmpty();
            assertThat(mapper.claim(943011,token,now.plusSeconds(6))).isEqualTo(1);
            assertThat(mapper.finish(943011,1,token,"FAILED","MODEL_FAILED",null,"{}",now.plusSeconds(7))).isEqualTo(1);
            assertThat(mapper.requeue(943011,null,now.plusSeconds(8))).isEqualTo(1);
            assertThat(mapper.find(942081,942001).orElseThrow().attempts()).isEqualTo(2);
            assertThat(mapper.claim(943011,token,now.plusSeconds(8))).isEqualTo(1);
            assertThat(mapper.finish(943011,2,token,"SUCCEEDED",null,"{}","{}",now.plusSeconds(99))).isZero();
            assertThat(mapper.lockExpired(now.plusSeconds(99)).orElseThrow().id()).isEqualTo(943011);
            assertThat(mapper.reject(943011,"LEASE_EXPIRED",now.plusSeconds(99))).isEqualTo(1);
            assertThat(mapper.requeue(943011,null,now.plusSeconds(100))).isZero();
            assertThat(mapper.publishAutomatic(943011,"{}",now.plusSeconds(101))).isEqualTo(1);
            assertThat(mapper.find(942081,942001).orElseThrow().status()).isEqualTo("SUCCEEDED");
            assertThat(mapper.find(942081,942001).orElseThrow().resultJson()).isEqualTo("{}");
            assertThat(mapper.find(942081,942001).orElseThrow().finishedAt()).isNotNull();
            assertThat(mapper.insertAutomatic(943012,942082,942001,"b".repeat(64),"{}",now)).isEqualTo(1);
            assertThat(mapper.insertAutomatic(943013,942082,942001,"b".repeat(64),"{}",now)).isZero();
        }
        try (Connection runtime = DriverManager.getConnection(runtimeUrl,runtimeUser,runtimePassword)) {
            assertSqlState(runtime,"23503","UPDATE business.geo_competitor_candidate SET user_id=999999 WHERE id=943001");
            assertSqlState(runtime,"23503","UPDATE business.geo_report_competitive_insight SET user_id=999999 WHERE id=943011");
            assertSqlState(runtime,"23514","UPDATE business.geo_report_competitive_insight SET claim_token=gen_random_uuid() WHERE id=943011");
            assertSqlState(runtime,"23514","UPDATE business.geo_report_competitive_insight SET finished_at=NULL WHERE id=943011");
            assertSqlState(runtime,"23514","UPDATE business.geo_report_competitive_insight SET attempts=3 WHERE id=943011");
            assertSqlState(runtime,"23514","UPDATE business.geo_competitor_candidate SET status='CONFIRMED' WHERE id=943001");
            assertSqlState(runtime,"42501","CREATE TABLE business.forbidden_runtime_ddl(id bigint)");
            try (Statement s=runtime.createStatement();ResultSet r=s.executeQuery(
                    "SELECT md5(to_jsonb(r)::text) FROM business.geo_report r WHERE id=942081")) {
                assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo(originalReport);
            }
            assertThat(scalarInt(runtime,"SELECT count(*) FROM business.point_ledger WHERE user_id=942001")).isZero();
            assertThat(scalarInt(runtime,"SELECT count(*) FROM business.competitor WHERE user_id=942001")).isEqualTo(1);
            assertThat(scalarInt(runtime,"SELECT count(*) FROM business.business_task WHERE user_id=942001 AND settled_points=4 AND status='PARTIAL'")).isEqualTo(2);
        }
        System.out.println("DB_GATE geo_competitive=042_to_043_real_mapper_owner_isolation_ignore_sticky_bounded_retry_fenced_lease_no_history_or_charging_changes_passed");
    }

    @Test
    void geoCollectionMethodMigrationPreservesHistoricalEvidence() throws Exception {
        String database = requiredEnvironment("GATE_EXPECTED_DATABASE");
        String migrationUrl = requiredEnvironment("GATE_MIGRATION_DB_URL");
        String migrationUser = requiredEnvironment("GATE_MIGRATION_DB_USERNAME");
        String migrationPassword = requiredEnvironment("GATE_MIGRATION_DB_PASSWORD");
        String runtimeUrl = requiredEnvironment("GATE_RUNTIME_DB_URL");
        String runtimeUser = requiredEnvironment("GATE_RUNTIME_DB_USERNAME");
        String runtimePassword = requiredEnvironment("GATE_RUNTIME_DB_PASSWORD");
        verifySafeIsolatedTarget(database, migrationUrl, migrationUser, migrationPassword,
                runtimeUrl, runtimeUser, runtimePassword);
        Flyway baseline = Flyway.configure().dataSource(migrationUrl, migrationUser, migrationPassword)
                .defaultSchema("business").schemas("business", "agent").createSchemas(true)
                .locations("classpath:db/migration").target("041").load();
        assertThat(baseline.migrate().migrationsExecuted).isEqualTo(41);
        String before;
        try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            configureRuntimePrivileges(migration, runtimeUser);
            execute(migration, """
                    INSERT INTO business.account(id,username,username_normalized,phone_e164,password_hash,role,status,created_at,updated_at)
                    VALUES(942001,'geo-trend','geo-trend','+860000000943',repeat('a',32),'USER','ACTIVE',now(),now());
                    INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,created_at,updated_at)
                    VALUES(942010,942001,'trend','trend','trend',now(),now());
                    INSERT INTO business.geo_topic(id,user_id,space_id,name,name_normalized,region,version,created_at,updated_at)
                    VALUES(942020,942001,942010,'trend','trend','CN',1,now(),now());
                    INSERT INTO business.conversation(id,user_id,title,created_at,updated_at)
                    VALUES(942090,942001,'trend',now(),now());
                    INSERT INTO business.task_draft(id,user_id,conversation_id,task_type,parameter_schema_version,space_id,parameters,input_snapshot,
                        input_hash,pricing_version_id,quoted_points,status,expires_at,created_at,updated_at,draft_source,
                        operation_scope,idempotency_key,request_hash)
                    VALUES(942031,942001,942090,'GEO',1,942010,'{}','{}',repeat('b',64),7001,6,'CONFIRMED',now(),now(),now(),
                        'FORM','TASK_DRAFT_CREATE','04200000-0000-0000-0000-000000000001',repeat('c',64));
                    INSERT INTO business.business_task(id,user_id,space_id,conversation_id,draft_id,task_type,status,stage_code,
                        snapshot_schema_version,input_snapshot,input_hash,pricing_version_id,quoted_points,settled_points,
                        operation_scope,idempotency_key,request_hash,queued_at,finished_at,created_at,updated_at)
                    VALUES(942041,942001,942010,942090,942031,'GEO','PARTIAL','COMPLETED',1,'{}',repeat('b',64),7001,6,4,
                        'TASK_CONFIRM','04210000-0000-0000-0000-000000000001',repeat('c',64),now(),now(),now(),now());
                    INSERT INTO business.geo_run(id,task_id,user_id,space_id,topic_id,status,sample_mode,expected_units,
                        succeeded_units,failed_units,missing_units,context_snapshot,metric_version,method_version,
                        finished_at,created_at,updated_at)
                    VALUES(942051,942041,942001,942010,942020,'PARTIAL','STANDARD',6,4,2,0,'{}',
                        'geo-v1','geo-deterministic-v1',now(),now(),now());
                    INSERT INTO business.geo_observation(id,run_id,task_id,user_id,source_event_id,request_hash,unit_key,unit_sequence,
                        question_id,question_text_snapshot,platform_code,sample_no,status,answer_text,provider,model,
                        started_at,finished_at,latency_ms,usage,provider_call_count,created_at)
                    VALUES(942061,942051,942041,942001,'04220000-0000-0000-0000-000000000001',repeat('d',64),'942071:DEEPSEEK:1',1,
                        942071,'historical question','DEEPSEEK',1,'SUCCEEDED','historical answer','DEEPSEEK','historical-model',
                        now(),now(),0,'{"inputTokens":12}',1,now());
                    """);
            try (Statement statement = migration.createStatement(); ResultSet result = statement.executeQuery(
                    "SELECT md5(to_jsonb(o)::text) FROM business.geo_observation o WHERE id=942061")) {
                assertThat(result.next()).isTrue(); before = result.getString(1);
            }
        }
        Flyway upgrade = Flyway.configure().dataSource(migrationUrl, migrationUser, migrationPassword)
                .defaultSchema("business").schemas("business", "agent").createSchemas(true)
                .locations("classpath:db/migration").target("042").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(upgrade.info().current().getVersion().toString()).isEqualTo("042");
        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setUrl(runtimeUrl); source.setUser(runtimeUser); source.setPassword(runtimePassword);
        Configuration observationConfig = new Configuration(new Environment("geo-observation-gate", new JdbcTransactionFactory(), source));
        observationConfig.addMapper(com.guanghe.backend.geo.GeoTaskMapper.class);
        SqlSessionFactory observationFactory = new SqlSessionFactoryBuilder().build(observationConfig);
        try (SqlSession session = observationFactory.openSession(true)) {
            var record = session.getMapper(com.guanghe.backend.geo.GeoTaskMapper.class)
                    .findObservationByEvent(942051, UUID.fromString("04220000-0000-0000-0000-000000000001")).orElseThrow();
            assertThat(record.collectionMethod()).isNull();
            assertThat(record.answerText()).isEqualTo("historical answer");
            assertThat(record.startedAt()).isNotNull();
        }
        try (Connection runtime = DriverManager.getConnection(runtimeUrl, runtimeUser, runtimePassword)) {
            try (Statement statement = runtime.createStatement(); ResultSet result = statement.executeQuery(
                    "SELECT md5((to_jsonb(o)-'collection_method')::text),collection_method FROM business.geo_observation o WHERE id=942061")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(before);
                assertThat(result.getString(2)).isNull();
            }
            execute(runtime,"UPDATE business.geo_observation SET collection_method='bocha-context-v1' WHERE id=942061");
            try (SqlSession session = observationFactory.openSession(true)) {
                var record = session.getMapper(com.guanghe.backend.geo.GeoTaskMapper.class)
                        .findObservationByEvent(942051, UUID.fromString("04220000-0000-0000-0000-000000000001")).orElseThrow();
                assertThat(record.collectionMethod()).isEqualTo("bocha-context-v1");
                assertThat(record.answerText()).isEqualTo("historical answer");
            }
            assertThat(scalarInt(runtime,"SELECT count(*) FROM business.geo_observation WHERE id=942061 AND collection_method='bocha-context-v1'")).isEqualTo(1);
            assertSqlState(runtime,"23514","UPDATE business.geo_observation SET collection_method='arbitrary secret / unsafe method' WHERE id=942061");
            assertSqlState(runtime,"23514","UPDATE business.geo_observation SET collection_method='' WHERE id=942061");
            execute(runtime,"UPDATE business.geo_observation SET collection_method=NULL WHERE id=942061");
            assertThat(scalarInt(runtime,"SELECT count(*) FROM business.point_ledger WHERE user_id=942001")).isZero();
            assertThat(scalarInt(runtime,"SELECT count(*) FROM business.business_task WHERE id=942041 AND settled_points=4 AND quoted_points=6 AND status='PARTIAL' AND finished_at=updated_at")).isEqualTo(1);
            assertThat(scalarInt(runtime,"SELECT count(*) FROM business.geo_run WHERE id=942051 AND succeeded_units=4 AND failed_units=2 AND status='PARTIAL' AND finished_at=updated_at")).isEqualTo(1);
        }
        System.out.println("DB_GATE geo_collection_method=041_to_042_second_migrate_0_legacy_evidence_unchanged_nullable_runtime_constraints_no_charging_passed");
    }

    @Test
    void geoInterpretationRecoveryUsesBoundedOwnedLeasesWithoutCharging() throws Exception {
        String database = requiredEnvironment("GATE_EXPECTED_DATABASE");
        String migrationUrl = requiredEnvironment("GATE_MIGRATION_DB_URL");
        String migrationUser = requiredEnvironment("GATE_MIGRATION_DB_USERNAME");
        String migrationPassword = requiredEnvironment("GATE_MIGRATION_DB_PASSWORD");
        String runtimeUrl = requiredEnvironment("GATE_RUNTIME_DB_URL");
        String runtimeUser = requiredEnvironment("GATE_RUNTIME_DB_USERNAME");
        String runtimePassword = requiredEnvironment("GATE_RUNTIME_DB_PASSWORD");
        verifySafeIsolatedTarget(database, migrationUrl, migrationUser, migrationPassword,
                runtimeUrl, runtimeUser, runtimePassword);
        boolean empty = "true".equals(System.getenv("GATE_GEO_INTERPRETATION_FROM_EMPTY"));
        if (!empty) {
            var baseline = Flyway.configure().dataSource(migrationUrl, migrationUser, migrationPassword)
                    .defaultSchema("business").schemas("business", "agent").createSchemas(true)
                    .locations("classpath:db/migration").target("040").load().migrate();
            assertThat(baseline.migrationsExecuted).isEqualTo(40);
        }
        Flyway flyway = Flyway.configure().dataSource(migrationUrl, migrationUser, migrationPassword)
                .defaultSchema("business").schemas("business", "agent").createSchemas(true)
                .locations("classpath:db/migration").target("041").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(empty ? 41 : 1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("041");
        try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            configureRuntimePrivileges(migration, runtimeUser);
            execute(migration, """
                    INSERT INTO business.account(id,username,username_normalized,phone_e164,password_hash,role,status,created_at,updated_at)
                    VALUES(941001,'geo-recovery','geo-recovery','+860000000941',repeat('a',32),'USER','ACTIVE',now(),now()),
                          (941002,'geo-other','geo-other','+860000000942',repeat('a',32),'USER','ACTIVE',now(),now());
                    INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,created_at,updated_at)
                    VALUES(941010,941001,'recovery','recovery','recovery',now(),now());
                    INSERT INTO business.geo_topic(id,user_id,space_id,name,name_normalized,region,version,created_at,updated_at)
                    VALUES(941020,941001,941010,'recovery','recovery','CN',1,now(),now());
                    INSERT INTO business.conversation(id,user_id,title,created_at,updated_at)
                    VALUES(941090,941001,'recovery',now(),now());
                    INSERT INTO business.task_draft(id,user_id,conversation_id,task_type,parameter_schema_version,space_id,parameters,input_snapshot,
                        input_hash,pricing_version_id,quoted_points,status,expires_at,created_at,updated_at,draft_source,
                        operation_scope,idempotency_key,request_hash)
                    SELECT 941030+n,941001,941090,'GEO',1,941010,'{}','{}',repeat('b',64),7001,6,'CONFIRMED',now(),now(),now(),
                        'FORM','TASK_DRAFT_CREATE',('04100000-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,repeat('c',64)
                    FROM generate_series(1,2) AS n;
                    INSERT INTO business.business_task(id,user_id,space_id,conversation_id,draft_id,task_type,status,stage_code,
                        snapshot_schema_version,input_snapshot,input_hash,pricing_version_id,quoted_points,settled_points,
                        operation_scope,idempotency_key,request_hash,queued_at,finished_at,created_at,updated_at)
                    SELECT 941040+n,941001,941010,941090,941030+n,'GEO','PARTIAL','COMPLETED',1,'{}',repeat('b',64),7001,6,4,
                        'TASK_CONFIRM',('04110000-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,repeat('c',64),
                        now(),now(),now(),now() FROM generate_series(1,2) AS n;
                    INSERT INTO business.geo_run(id,task_id,user_id,space_id,topic_id,status,sample_mode,expected_units,
                        succeeded_units,failed_units,missing_units,context_snapshot,metric_version,method_version,
                        finished_at,created_at,updated_at)
                    SELECT 941050+n,941040+n,941001,941010,941020,'PARTIAL','STANDARD',6,4,2,0,'{}',
                        'geo-v1','geo-deterministic-v1',now(),now(),now() FROM generate_series(1,2) AS n;
                    INSERT INTO business.geo_report(id,run_id,task_id,user_id,topic_id,status,synthesis,synthesis_usage,
                        summary,method_version,sample_size,generated_at,created_at)
                    SELECT 941060+n,941050+n,941040+n,941001,941020,'PARTIAL','{}','{}','fallback',
                        'geo-deterministic-v1',6,now(),now() FROM generate_series(1,2) AS n;
                    """);
        }
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setUrl(runtimeUrl); dataSource.setUser(runtimeUser); dataSource.setPassword(runtimePassword);
        Configuration configuration = new Configuration(new Environment("geo-recovery-gate", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(GeoInterpretationMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        OffsetDateTime now = OffsetDateTime.now();
        try (SqlSession session = factory.openSession(true)) {
            GeoInterpretationMapper mapper = session.getMapper(GeoInterpretationMapper.class);
            assertThat(mapper.executionEligible(941001, 941041)).isTrue();
            assertThat(mapper.executionEligible(941002, 941041)).isFalse();
            assertThat(mapper.enqueue(941071,941061,941001,"a".repeat(64),null,now)).isEqualTo(1);
            assertThat(mapper.find(941061,941002)).isEmpty();
            assertThat(mapper.countActive(941001)).isEqualTo(1);
        }
        try (Connection runtime = DriverManager.getConnection(runtimeUrl,runtimeUser,runtimePassword)) {
            assertSqlState(runtime,"23503", "INSERT INTO business.geo_report_interpretation_work(id,report_id,user_id,input_hash,status,attempts,requested_at,available_at,capacity_wait_until,created_at,updated_at) VALUES(941073,941062,941002,repeat('a',64),'QUEUED',1,now(),now(),now()+interval '120 seconds',now(),now())");
            assertSqlState(runtime,"23505", "INSERT INTO business.geo_report_interpretation_work(id,report_id,user_id,input_hash,status,attempts,requested_at,available_at,capacity_wait_until,created_at,updated_at) VALUES(941072,941062,941001,repeat('a',64),'QUEUED',1,now(),now(),now()+interval '120 seconds',now(),now())");
        }
        try (SqlSession first = factory.openSession(false); SqlSession second = factory.openSession(false)) {
            assertThat(first.getMapper(GeoInterpretationMapper.class).lockNext(now)).isPresent();
            assertThat(second.getMapper(GeoInterpretationMapper.class).lockNext(now)).isEmpty();
            first.rollback(); second.rollback();
        }
        try (SqlSession session = factory.openSession(true)) {
            GeoInterpretationMapper mapper = session.getMapper(GeoInterpretationMapper.class);
            UUID token = UUID.randomUUID();
            assertThat(mapper.claim(941071,token,now.plusSeconds(90),now.plusSeconds(60),now)).isEqualTo(1);
            assertThat(mapper.claim(941071,UUID.randomUUID(),now.plusSeconds(90),now.plusSeconds(60),now)).isZero();
            assertThat(mapper.finish(941071,1,UUID.randomUUID(),"FAILED","GEO_INTERPRETATION_TIMEOUT",null,now)).isZero();
            assertThat(mapper.finish(941071,1,token,"FAILED","GEO_INTERPRETATION_TIMEOUT",null,now.plusSeconds(91))).isZero();
            assertThat(mapper.rejectOrExpire(941071,"GEO_INTERPRETATION_LEASE_EXPIRED",now.plusSeconds(91))).isEqualTo(1);
            assertThat(mapper.requeue(941071,null,now.plusSeconds(92))).isEqualTo(1);
            assertThat(mapper.find(941061,941001).orElseThrow().attempts()).isEqualTo(2);
            UUID secondToken = UUID.randomUUID();
            assertThat(mapper.claim(941071,secondToken,now.plusSeconds(182),now.plusSeconds(152),now.plusSeconds(92))).isEqualTo(1);
            assertThat(mapper.finish(941071,1,token,"SUCCEEDED",null,"{}",now.plusSeconds(93))).isZero();
            assertThat(mapper.finish(941071,2,secondToken,"FAILED","GEO_INTERPRETATION_TIMEOUT",null,now.plusSeconds(93))).isEqualTo(1);
            assertThat(mapper.requeue(941071,null,now.plusSeconds(94))).isZero();
            // A guaranteed zero-provider-call capacity refusal returns the SAME reserved attempt.
            assertThat(mapper.enqueue(941072,941062,941001,"b".repeat(64),null,now)).isEqualTo(1);
            UUID capacityToken=UUID.randomUUID();
            assertThat(mapper.claim(941072,capacityToken,now.plusSeconds(90),now.plusSeconds(60),now)).isEqualTo(1);
            assertThat(mapper.capacityRejected(941072,1,capacityToken,now.plusSeconds(1))).isEqualTo(1);
            assertThat(mapper.find(941062,941001).orElseThrow().attempts()).isEqualTo(1);
            assertThat(mapper.lockNext(now.plusSeconds(2))).isEmpty();
            assertThat(mapper.lockNext(now.plusSeconds(6))).isPresent();
            assertThat(mapper.claim(941072,capacityToken,now.plusSeconds(215),now.plusSeconds(185),now.plusSeconds(125))).isEqualTo(1);
            assertThat(mapper.capacityRejected(941072,1,capacityToken,now.plusSeconds(126))).isEqualTo(1);
            assertThat(mapper.find(941062,941001).orElseThrow().status()).isEqualTo("FAILED");
            assertThat(mapper.requeue(941072,null,now.plusSeconds(130))).isZero();
            assertThat(mapper.requeue(941072,null,now.plusSeconds(131))).isEqualTo(1);
            assertThat(mapper.find(941062,941001).orElseThrow().attempts()).isEqualTo(1);
            assertThat(mapper.publish(941061,941002,"{}","{}","cross-owner")).isZero();
            assertThat(mapper.publish(941061,941001,"{}","{}","recovered")).isEqualTo(1);
            assertThat(mapper.publish(941061,941001,"{}","{}","overwrite")).isZero();
        }
        try (Connection runtime = DriverManager.getConnection(runtimeUrl,runtimeUser,runtimePassword)) {
            assertThat(scalarInt(runtime,"SELECT count(*) FROM business.point_ledger WHERE user_id=941001")).isZero();
            assertThat(scalarInt(runtime,"SELECT count(*) FROM business.business_task WHERE user_id=941001 AND status='PARTIAL' AND settled_points=4 AND quoted_points=6 AND finished_at=updated_at")).isEqualTo(2);
            assertThat(scalarInt(runtime,"SELECT count(*) FROM business.geo_run WHERE user_id=941001 AND status='PARTIAL' AND succeeded_units=4 AND failed_units=2 AND finished_at=updated_at")).isEqualTo(2);
            assertThat(scalarInt(runtime,"SELECT count(*) FROM business.geo_report WHERE id=941061 AND status='PARTIAL' AND generated_at=created_at AND summary='recovered'")).isEqualTo(1);
            assertSqlState(runtime,"23514","UPDATE business.geo_report_interpretation_work SET attempts=3 WHERE id=941071");
        }
        System.out.println("DB_GATE geo_interpretation=" + (empty ? "empty_to_041" : "040_to_041")
                + "_second_migrate_0_owner_active_unique_skip_locked_fence_attempts_no_charging_passed");
    }

    @Test
    void chatDraftRevisionSavepointRollsBackWhileRunFailureCommits() throws Exception {
        String database = requiredEnvironment("GATE_EXPECTED_DATABASE");
        String migrationUrl = requiredEnvironment("GATE_MIGRATION_DB_URL");
        String migrationUser = requiredEnvironment("GATE_MIGRATION_DB_USERNAME");
        String migrationPassword = requiredEnvironment("GATE_MIGRATION_DB_PASSWORD");
        String runtimeUrl = requiredEnvironment("GATE_RUNTIME_DB_URL");
        String runtimeUser = requiredEnvironment("GATE_RUNTIME_DB_USERNAME");
        String runtimePassword = requiredEnvironment("GATE_RUNTIME_DB_PASSWORD");
        verifySafeIsolatedTarget(database, migrationUrl, migrationUser, migrationPassword,
                runtimeUrl, runtimeUser, runtimePassword);
        assertThat(runtimeUser).isNotEqualTo(migrationUser);
        Flyway.configure().dataSource(migrationUrl, migrationUser, migrationPassword)
                .defaultSchema("business").schemas("business", "agent").createSchemas(true)
                .locations("classpath:db/migration").load().migrate();

        UUID runId = UUID.fromString("39100000-0000-0000-0000-000000000001");
        long userId = 919100000000000001L;
        long draftId = 919600000000000001L;
        try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            configureRuntimePrivileges(migration, runtimeUser);
            execute(migration, """
                    INSERT INTO business.account(id,username,username_normalized,phone_e164,password_hash,
                        role,status,created_at,updated_at)
                    VALUES (919100000000000001,'revision-gate','revision-gate','+860000000091',
                        repeat('a',32),'USER','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,created_at,updated_at)
                    VALUES (919200000000000001,919100000000000001,'revision','revision','revision',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO business.product(id,user_id,space_id,name,name_normalized,created_at,updated_at)
                    VALUES (919300000000000001,919100000000000001,919200000000000001,'revision','revision',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO business.conversation(id,user_id,title,next_message_sequence,created_at,updated_at)
                    VALUES (919400000000000001,919100000000000001,'revision',3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO business.conversation_message(id,conversation_id,user_id,role,content_type,content,
                        client_message_id,idempotency_key,request_fingerprint,sequence_no,status,created_at,updated_at)
                    VALUES (919500000000000001,919400000000000001,919100000000000001,'USER','TEXT','{"text":"revise"}',
                        '39100000-0000-0000-0000-000000000002','39100000-0000-0000-0000-000000000003',
                        repeat('b',64),1,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO business.agent_run(id,conversation_id,user_id,request_message_id,
                        selected_space_id,selected_product_id,status,context_snapshot,context_ref_hash,
                        context_expires_at,created_at,updated_at)
                    VALUES ('39100000-0000-0000-0000-000000000001',919400000000000001,919100000000000001,
                        919500000000000001,919200000000000001,919300000000000001,'RUNNING',
                        '{"taskWorkspace":{"activeDraftId":"919600000000000001"}}',repeat('c',64),
                        CURRENT_TIMESTAMP + INTERVAL '5 minutes',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO business.conversation_message(id,conversation_id,user_id,role,content_type,content,
                        agent_run_id,sequence_no,status,created_at,updated_at)
                    VALUES (919500000000000002,919400000000000001,919100000000000001,'ASSISTANT','TEXT','{"text":""}',
                        '39100000-0000-0000-0000-000000000001',2,'STREAMING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    UPDATE business.agent_run SET response_message_id=919500000000000002
                        WHERE id='39100000-0000-0000-0000-000000000001';
                    INSERT INTO business.task_draft(id,user_id,conversation_id,task_type,parameter_schema_version,
                        space_id,product_id,parameters,input_snapshot,input_hash,pricing_version_id,quoted_points,
                        status,expires_at,created_at,updated_at,draft_source,operation_scope,idempotency_key,request_hash)
                    VALUES (919600000000000001,919100000000000001,919400000000000001,'ARTICLE',2,
                        919200000000000001,919300000000000001,'{"mode":"TITLE","articleId":"901","articleVersion":1}',
                        '{}',repeat('d',64),7001,3,'WAITING_CONFIRMATION',CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'FORM','TASK_DRAFT_CREATE',
                        '39100000-0000-0000-0000-000000000004',repeat('e',64));
                    INSERT INTO business.task_confirmation(id,draft_id,user_id,token_hash,input_hash,pricing_version_id,
                        status,expires_at,created_at)
                    VALUES (919700000000000001,919600000000000001,919100000000000001,repeat('f',64),repeat('d',64),
                        7001,'AVAILABLE',CURRENT_TIMESTAMP + INTERVAL '5 minutes',CURRENT_TIMESTAMP);
                    """);
        }

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setUrl(runtimeUrl);
        dataSource.setUser(runtimeUser);
        dataSource.setPassword(runtimePassword);
        Configuration configuration = new Configuration(new Environment(
                "chat-revision-savepoint-gate", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(TaskControlMapper.class);
        configuration.addMapper(ConversationMapper.class);
        // SqlSessionTemplate delegates resource lifetime to Spring's transaction synchronization;
        // manually closing this template is deliberately unsupported by MyBatis-Spring.
        SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        {
            TaskControlMapper realTasks = sessions.getMapper(TaskControlMapper.class);
            ConversationMapper conversations = sessions.getMapper(ConversationMapper.class);
            TaskControlMapper faultyTasks = mock(TaskControlMapper.class, delegatesTo(realTasks));
            doAnswer(invocation -> {
                assertThat(realTasks.abandonDraft(draftId, userId, invocation.getArgument(2))).isEqualTo(1);
                // Deliberately fail after BOTH old-state changes and both new inserts. The outer
                // Run transaction must still commit FAILED while the nested savepoint restores all four.
                assertThat(realTasks.findDraft(draftId, userId).orElseThrow().status()).isEqualTo("ABANDONED");
                assertThat(realTasks.lockConfirmation(draftId, userId).orElseThrow().status()).isEqualTo("INVALIDATED");
                assertThat(realTasks.findDraftByRunId(runId)).isPresent();
                throw new IllegalArgumentException("TASK_DRAFT_REVISION_STALE");
            }).when(faultyTasks).abandonDraft(org.mockito.ArgumentMatchers.eq(draftId),
                    org.mockito.ArgumentMatchers.eq(userId), any());
            SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
            AtomicLong sequence = new AtomicLong(919800000000000000L);
            when(ids.nextId()).thenAnswer(ignored -> sequence.incrementAndGet());
            ObjectMapper json = new ObjectMapper().findAndRegisterModules();
            ObjectNode parameters = json.createObjectNode().put("mode", "TITLE")
                    .put("articleId", "901").put("articleVersion", 1).put("brief", "revised");
            ObjectNode snapshot = json.createObjectNode();
            snapshot.set("parameters", parameters);
            ArticleService articles = mock(ArticleService.class);
            when(articles.prepareChatTitleRevision(anyLong(), anyLong(), any(), any(), any()))
                    .thenReturn(new ArticleService.PreparedTask(parameters, snapshot, "TITLE"));
            PaidTaskProperties properties = new PaidTaskProperties();
            properties.setEnabled(true);
            properties.setTokenSecret("database-gate-revision-at-least-32-bytes");
            properties.setArticleTestPricingVersionId(7001);
            properties.setArticleTitleTestPoints(3);
            TaskControlService target = new TaskControlService(faultyTasks, conversations, ids, json, properties,
                    new AssetIngestionProperties(), mock(AuthorizedCatalogContextFactory.class), null, articles);
            JdbcTransactionManager transactions = new JdbcTransactionManager(dataSource);
            ProxyFactory taskProxy = new ProxyFactory(target);
            taskProxy.setProxyTargetClass(true);
            taskProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            ConversationRunPersistenceService persistence = new ConversationRunPersistenceService(conversations, ids, json);
            ReflectionTestUtils.invokeMethod(persistence, "setTaskControlService", taskProxy.getProxy());
            ProxyFactory persistenceProxy = new ProxyFactory(persistence);
            persistenceProxy.setProxyTargetClass(true);
            persistenceProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            ObjectNode proposal = json.createObjectNode().put("proposalOnly", true).put("taskType", "ARTICLE")
                    .put("parameterSchemaVersion", 2).put("spaceId", "919200000000000001")
                    .put("productId", "919300000000000001").put("draftOperation", "REVISE")
                    .put("previousDraftId", Long.toString(draftId));
            proposal.putObject("parameters").put("requestText", "revise").put("mode", "AGENT_ASSISTED")
                    .put("agentSummary", "revise").put("topic", "revised").put("language", "zh-CN");
            proposal.putArray("warnings");
            PythonChatRunEvent event = new PythonChatRunEvent(1, UUID.randomUUID(), "task-draft.proposed",
                    runId, OffsetDateTime.now(), 1, proposal);
            ((ConversationRunPersistenceService) persistenceProxy.getProxy()).processEvent(runId, event, "");
        }
        try (Connection runtime = DriverManager.getConnection(runtimeUrl, runtimeUser, runtimePassword)) {
            assertThat(scalarInt(runtime, "SELECT count(*) FROM business.task_draft WHERE user_id=" + userId)).isEqualTo(1);
            assertThat(scalarInt(runtime, "SELECT count(*) FROM business.task_draft WHERE id=" + draftId
                    + " AND status='WAITING_CONFIRMATION'")).isEqualTo(1);
            assertThat(scalarInt(runtime, "SELECT count(*) FROM business.task_confirmation WHERE user_id=" + userId)).isEqualTo(1);
            assertThat(scalarInt(runtime, "SELECT count(*) FROM business.task_confirmation WHERE draft_id=" + draftId
                    + " AND status='AVAILABLE'")).isEqualTo(1);
            assertThat(scalarInt(runtime, "SELECT count(*) FROM business.agent_run WHERE id='" + runId
                    + "' AND status='FAILED' AND error_code='TASK_DRAFT_REVISION_STALE' AND last_python_sequence=1")).isEqualTo(1);
            assertThat(scalarInt(runtime, "SELECT count(*) FROM business.conversation_event WHERE user_id=" + userId
                    + " AND event_type='message.completed'")).isEqualTo(1);
            assertThat(scalarInt(runtime, "SELECT count(*) FROM business.business_task WHERE user_id=" + userId)).isZero();
        }
        System.out.println("DB_GATE chat_revision_savepoint=old_draft_and_proof_restored_new_rows_absent_run_failed_committed");
    }

    @Test
    void settlementAccountingUsesLockedAccountDayAndIdempotentReplay() throws Exception {
        String database = requiredEnvironment("GATE_EXPECTED_DATABASE");
        String migrationUrl = requiredEnvironment("GATE_MIGRATION_DB_URL");
        String migrationUser = requiredEnvironment("GATE_MIGRATION_DB_USERNAME");
        String migrationPassword = requiredEnvironment("GATE_MIGRATION_DB_PASSWORD");
        String runtimeUrl = requiredEnvironment("GATE_RUNTIME_DB_URL");
        String runtimeUser = requiredEnvironment("GATE_RUNTIME_DB_USERNAME");
        String runtimePassword = requiredEnvironment("GATE_RUNTIME_DB_PASSWORD");
        verifySafeIsolatedTarget(database, migrationUrl, migrationUser, migrationPassword,
                runtimeUrl, runtimeUser, runtimePassword);
        // This gate is deliberately restricted to resources created by the isolated runner.
        assertThat(database).matches("^zhixiaohe_gate_video_[a-f0-9]{10}$");
        String suffix = database.substring("zhixiaohe_gate_video_".length());
        assertThat(migrationUser).isEqualTo("gate_migration_" + suffix);
        assertThat(runtimeUser).isEqualTo("gate_runtime_" + suffix);
        Flyway flyway = Flyway.configure().dataSource(migrationUrl, migrationUser, migrationPassword)
                .defaultSchema("business").schemas("business", "agent").createSchemas(true)
                .locations("classpath:db/migration").target("047").load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("047");
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        long userId = 947900000000000001L;
        long taskId = 947900000000000006L;
        String token = "v1.database-gate-settlement-execution-token";
        try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            assertPostgresql18(migration);
            configureRuntimePrivileges(migration, runtimeUser);
            assertRoleSeparation(migration, migrationUser, runtimeUser);
            try (Statement statement = migration.createStatement();
                 ResultSet result = statement.executeQuery("SELECT current_user")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(migrationUser);
            }
            // Reuse the existing paid-result fixture shape; only synthetic local mock evidence.
            execute(migration, """
                    INSERT INTO business.account(id,username,username_normalized,phone_e164,password_hash,
                        role,status,created_at,updated_at)
                    VALUES(947900000000000001,'settlement-gate','settlement-gate','+860000000047',
                        repeat('a',32),'USER','ACTIVE',now(),now());
                    INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,created_at,updated_at)
                    VALUES(947900000000000002,947900000000000001,'settlement','settlement','settlement',now(),now());
                    INSERT INTO business.product(id,user_id,space_id,name,name_normalized,created_at,updated_at)
                    VALUES(947900000000000003,947900000000000001,947900000000000002,'settlement','settlement',now(),now());
                    INSERT INTO business.conversation(id,user_id,title,created_at,updated_at)
                    VALUES(947900000000000004,947900000000000001,'settlement-gate',now(),now());
                    INSERT INTO business.task_draft(id,user_id,conversation_id,task_type,parameter_schema_version,
                        space_id,product_id,parameters,input_snapshot,input_hash,pricing_version_id,quoted_points,
                        status,expires_at,created_at,updated_at,draft_source,operation_scope,idempotency_key,request_hash)
                    VALUES(947900000000000005,947900000000000001,947900000000000004,'IMAGE',1,
                        947900000000000002,947900000000000003,'{"count":1}','{"parameters":{"count":1}}',
                        repeat('b',64),7001,7,'CONFIRMED',now()+interval '15 minutes',now(),now(),'FORM',
                        'TASK_DRAFT_CREATE','04790000-0000-0000-0000-000000000001',repeat('c',64));
                    INSERT INTO business.business_task(id,user_id,space_id,product_id,conversation_id,draft_id,
                        task_type,status,stage_code,snapshot_schema_version,input_snapshot,input_hash,
                        pricing_version_id,quoted_points,operation_scope,idempotency_key,request_hash,
                        queued_at,started_at,created_at,updated_at)
                    VALUES(947900000000000006,947900000000000001,947900000000000002,947900000000000003,
                        947900000000000004,947900000000000005,'IMAGE','RUNNING','PROVIDER_GENERATING',1,
                        '{"parameters":{"count":1},"authorizedContext":{}}',repeat('d',64),7001,7,'TASK_CONFIRM',
                        '04790000-0000-0000-0000-000000000002',repeat('e',64),now(),now(),now(),now());
                    INSERT INTO business.task_execution(id,task_id,user_id,execution_no,status,consumer_name,worker_id,
                        bundle_version,execution_token_hash,lease_expires_at,stage_code,started_at,created_at,updated_at)
                    VALUES(947900000000000007,947900000000000006,947900000000000001,1,'RUNNING',
                        'settlement-gate','local-fixture','settlement-gate','%s',now()+interval '15 minutes',
                        'PROVIDER_GENERATING',now(),now(),now());
                    INSERT INTO business.point_account(user_id,available_points,frozen_points,version,created_at,updated_at)
                    VALUES(947900000000000001,93,7,1,now(),now());
                    INSERT INTO business.point_reservation(id,user_id,task_id,quoted_points,frozen_points,
                        settled_points,released_points,status,created_at,updated_at)
                    VALUES(947900000000000008,947900000000000001,947900000000000006,7,7,0,0,'FROZEN',now(),now());
                    """.formatted(sha256(token)));
        }

        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setUrl(runtimeUrl);
        source.setUser(runtimeUser);
        source.setPassword(runtimePassword);
        String applicationName = "settlement-accounting-gate-" + UUID.randomUUID();
        source.setApplicationName(applicationName);
        source.setConnectTimeout(5);
        source.setSocketTimeout(30);
        source.setOptions("-c statement_timeout=20000 -c lock_timeout=15000");
        Configuration configuration = new Configuration(new Environment(
                "settlement-accounting-gate", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(TaskControlMapper.class);
        configuration.addMapper(ConversationMapper.class);
        SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        AtomicLong sequence = new AtomicLong(947900000000000100L);
        when(ids.nextId()).thenAnswer(ignored -> sequence.incrementAndGet());
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        PaidTaskProperties properties = new PaidTaskProperties();
        properties.setEnabled(true);
        properties.setTokenSecret("database-gate-settlement-at-least-32-bytes");
        TaskControlService target = new TaskControlService(sessions.getMapper(TaskControlMapper.class),
                sessions.getMapper(ConversationMapper.class), ids, json, properties,
                new AssetIngestionProperties(), mock(AuthorizedCatalogContextFactory.class));
        Instant beforeMidnight = Instant.parse("2026-10-06T15:59:59Z");
        Instant afterMidnight = Instant.parse("2026-10-06T16:00:01Z");
        AtomicReference<Instant> accountingTime = new AtomicReference<>(beforeMidnight);
        AtomicLong clockReads = new AtomicLong();
        Clock accountingClock = mock(Clock.class);
        when(accountingClock.instant()).thenAnswer(ignored -> {
            clockReads.incrementAndGet();
            return accountingTime.get();
        });
        ReflectionTestUtils.invokeMethod(target, "setAccountingClock", accountingClock);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new JdbcTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        TaskControlService service = (TaskControlService) proxy.getProxy();

        UUID requestId = UUID.fromString("04790000-0000-0000-0000-000000000003");
        ObjectNode artifact = json.createObjectNode().put("artifactType", "IMAGE").put("schemaVersion", 1)
                .put("providerUrl", "https://mock-provider.invalid/image/" + requestId + ".png")
                .put("contentType", "image/png");
        artifact.putObject("metadata").put("width", 1024).put("height", 1024).put("mock", true);
        ObjectNode usage = json.createObjectNode().put("inputTokens", 0).put("outputTokens", 0)
                .put("images", 1).put("videoSeconds", 0).put("providerCost", "0.0000").put("currency", "CNY");
        TaskDtos.ResultArtifactsRequest request = new TaskDtos.ResultArtifactsRequest(
                UUID.fromString("04790000-0000-0000-0000-000000000004"), 1, token,
                "LOCAL_TASK_PROVIDER", "deterministic-provider-mock-v1", requestId,
                "mock-image-" + requestId, "CONFIRMED", List.of(artifact), usage, 0);

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "settlement-accounting-gate");
            thread.setDaemon(true);
            return thread;
        });
        Future<TaskControlService.ReportedTask> settlement = null;
        try (Connection holder = source.getConnection(); Connection observer = source.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int holderPid;
                try (Statement statement = holder.createStatement();
                     ResultSet result = statement.executeQuery("SELECT pg_backend_pid(),current_user,current_database()")) {
                    assertThat(result.next()).isTrue();
                    holderPid = result.getInt(1);
                    assertThat(result.getString(2)).isEqualTo(runtimeUser);
                    assertThat(result.getString(3)).isEqualTo(database);
                }
                try (PreparedStatement lock = holder.prepareStatement(
                        "SELECT user_id FROM business.point_account WHERE user_id=? FOR UPDATE")) {
                    lock.setLong(1, userId);
                    try (ResultSet result = lock.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getLong(1)).isEqualTo(userId);
                    }
                }
                settlement = executor.submit(() -> service.reportResult(taskId, request));
                int waiterPid = awaitSettlementAccountLock(observer, database, runtimeUser,
                        applicationName, holderPid, settlement);
                assertThat(waiterPid).isNotEqualTo(holderPid);
                assertThat(settlement.isDone()).isFalse();
                assertThat(accountingTime.get()).isEqualTo(beforeMidnight);
                assertThat(clockReads.get()).as("accounting clock must not be read before account lock").isZero();
                assertThat(scalarInt(observer, "SELECT count(*) FROM business.point_ledger WHERE user_id=" + userId)).isZero();
                assertThat(scalarInt(observer, "SELECT count(*) FROM business.point_account WHERE user_id=" + userId
                        + " AND available_points=93 AND frozen_points=7 AND version=1")).isEqualTo(1);
                // PostgreSQL, not thread timing, proves the real service is blocked before the clock moves.
                accountingTime.set(afterMidnight);
                holder.commit();
                assertThat(settlement.get(20, TimeUnit.SECONDS).response().taskStatus()).isEqualTo("SUCCEEDED");
                assertThat(clockReads.get()).isEqualTo(1);
                assertSettlementAccountingRows(observer, userId, taskId, afterMidnight);

                assertThat(service.reportResult(taskId, request).response().taskStatus()).isEqualTo("SUCCEEDED");
                assertThat(clockReads.get()).as("same result replay must not settle again").isEqualTo(1);
                assertSettlementAccountingRows(observer, userId, taskId, afterMidnight);
                assertThat(scalarInt(observer, "SELECT count(*) FROM business.business_task_event WHERE task_id=" + taskId)).isEqualTo(1);
                assertThat(scalarInt(observer, "SELECT count(*) FROM business.conversation_event WHERE task_id=" + taskId
                        + " AND event_type='task.completed'")).isEqualTo(1);
                assertThat(scalarInt(observer, "SELECT count(*) FROM business.asset_ingestion_work WHERE task_id=" + taskId)).isZero();
            } finally {
                holder.rollback();
            }
        } finally {
            if (settlement != null && !settlement.isDone()) settlement.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("bounded gate executor cleanup").isTrue();
        }
        System.out.println("DB_GATE settlement_accounting=real_service_real_owned_pg_lock_after_midnight_single_ledger_replay_safe");
    }

    private int awaitSettlementAccountLock(Connection observer, String database, String runtimeUser,
                                           String applicationName, int holderPid, Future<?> settlement)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (PreparedStatement query = observer.prepareStatement("""
                SELECT waiter.pid FROM pg_stat_activity waiter
                JOIN pg_stat_activity holder ON holder.pid=?
                WHERE waiter.datname=? AND holder.datname=waiter.datname
                  AND waiter.usename=? AND holder.usename=waiter.usename
                  AND waiter.application_name=? AND waiter.pid<>holder.pid
                  AND waiter.state='active' AND waiter.wait_event_type='Lock'
                  AND holder.xact_start IS NOT NULL
                  AND holder.pid=ANY(pg_blocking_pids(waiter.pid))
                  AND waiter.query LIKE '%business.point_account%'
                  AND upper(waiter.query) LIKE '%FOR UPDATE%'
                """)) {
            query.setQueryTimeout(2);
            query.setInt(1, holderPid);
            query.setString(2, database);
            query.setString(3, runtimeUser);
            query.setString(4, applicationName);
            while (System.nanoTime() < deadline) {
                if (settlement.isDone()) {
                    settlement.get(1, TimeUnit.SECONDS);
                    throw new AssertionError("Settlement completed without the required PostgreSQL account-lock wait");
                }
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        int waiterPid = result.getInt(1);
                        assertThat(result.next()).as("exactly one owned service waiter").isFalse();
                        return waiterPid;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError("Owned settlement service did not reach the expected PostgreSQL account-lock wait");
    }

    private void assertSettlementAccountingRows(Connection connection, long userId, long taskId,
                                                 Instant accountingAt) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT created_at,available_delta,frozen_delta,available_after,frozen_after
                FROM business.point_ledger WHERE user_id=? AND business_id=? AND entry_type='TASK_SETTLE'
                """)) {
            query.setLong(1, userId);
            query.setLong(2, taskId);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject(1, OffsetDateTime.class).toInstant()).isEqualTo(accountingAt);
                assertThat(result.getInt(2)).isZero();
                assertThat(result.getInt(3)).isEqualTo(-7);
                assertThat(result.getInt(4)).isEqualTo(93);
                assertThat(result.getInt(5)).isZero();
                assertThat(result.next()).as("one settlement ledger after replay").isFalse();
            }
        }
        assertThat(scalarInt(connection, "SELECT count(*) FROM business.point_ledger WHERE user_id=" + userId)).isEqualTo(1);
        assertThat(scalarInt(connection, "SELECT count(*) FROM business.point_account a JOIN business.point_ledger l"
                + " ON l.user_id=a.user_id WHERE a.user_id=" + userId
                + " AND a.available_points=93 AND a.frozen_points=0 AND a.version=2 AND a.updated_at=l.created_at")).isEqualTo(1);
        assertThat(scalarInt(connection, "SELECT count(*) FROM business.point_reservation WHERE task_id=" + taskId
                + " AND status='SETTLED' AND quoted_points=7 AND frozen_points=0 AND settled_points=7 AND released_points=0")).isEqualTo(1);
        assertThat(scalarInt(connection, "SELECT count(*) FROM business.business_task WHERE id=" + taskId
                + " AND status='SUCCEEDED' AND settled_points=7")).isEqualTo(1);
        assertThat(scalarInt(connection, "SELECT count(*) FROM business.task_execution WHERE task_id=" + taskId
                + " AND status='SUCCEEDED'")).isEqualTo(1);
        for (String day : List.of("2026-10-06", "2026-10-07")) {
            int expected = day.equals("2026-10-06") ? 0 : 7;
            assertThat(scalarInt(connection, "SELECT COALESCE(sum(-frozen_delta),0) FROM business.point_ledger"
                    + " WHERE user_id=" + userId + " AND entry_type='TASK_SETTLE'"
                    + " AND (created_at AT TIME ZONE 'Asia/Shanghai')::date=DATE '" + day + "'")).isEqualTo(expected);
        }
    }


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
            "geo_competitor_set_member", "geo_run", "geo_observation", "geo_citation", "geo_report", "geo_report_interpretation_work",
            "geo_plan", "geo_plan_slot", "qrtz_job_details", "qrtz_triggers",
            "qrtz_simple_triggers", "qrtz_cron_triggers", "qrtz_simprop_triggers",
            "qrtz_blob_triggers", "qrtz_calendars", "qrtz_paused_trigger_grps",
            "qrtz_fired_triggers", "qrtz_scheduler_state", "qrtz_locks",
            "article", "article_asset_relation", "article_submission_snapshot",
            "article_title_candidate_batch", "article_creative_plan_revision",
            "article_submission_preference",
            "media_resource", "media_price_snapshot", "media_submission_batch",
            "media_submission", "media_submission_event", "media_callback_event",
            "media_provider_sync_state", "workflow_plan", "workflow_plan_step",
            "workflow_confirmation", "workflow_event", "workflow_execution",
            "workflow_observation", "workflow_point_reservation",
            "product_identity_asset", "conversation_message_asset", "asset_upload_request",
            "conversation_admission",
            // M8 (V028..V030): recharge, manual adjustment, commercial audit, invoice,
            // notifications, pricing/rate-limit versions and user feedback. V031 and V032 add no
            // tables: they provide cross-account read indexes for the media and Agent consoles;
            // V032 also freezes the persisted Agent bundle-version format.
            "recharge_order", "recharge_order_event", "manual_point_adjustment",
            "operational_audit_event", "invoice_application", "invoice_application_event",
            "invoice_webhook_delivery",
            "user_notification", "pricing_version", "pricing_version_tier",
            "commercial_rate_limit", "user_feedback"
    );

    @Test
    void validatesFlywayAndRuntimeLeastPrivilegeAgainstPostgresql18() throws Exception {
        String expectedDatabase = requiredEnvironment("GATE_EXPECTED_DATABASE");
        String migrationUrl = requiredEnvironment("GATE_MIGRATION_DB_URL");
        String migrationUser = requiredEnvironment("GATE_MIGRATION_DB_USERNAME");
        String migrationPassword = requiredEnvironment("GATE_MIGRATION_DB_PASSWORD");
        String runtimeUrl = requiredEnvironment("GATE_RUNTIME_DB_URL");
        String runtimeUser = requiredEnvironment("GATE_RUNTIME_DB_USERNAME");
        String runtimePassword = requiredEnvironment("GATE_RUNTIME_DB_PASSWORD");

        verifySafeIsolatedTarget(
                expectedDatabase,
                migrationUrl,
                migrationUser,
                migrationPassword,
                runtimeUrl,
                runtimeUser,
                runtimePassword
        );
        assertThat(runtimeUser).isNotEqualTo(migrationUser);

        String upgradeFromVersion = System.getenv("GATE_UPGRADE_FROM_VERSION");
        if (upgradeFromVersion != null && !"020".equals(upgradeFromVersion)) {
            throw new IllegalStateException("GATE_UPGRADE_FROM_VERSION must be 020 when supplied");
        }
        if ("020".equals(upgradeFromVersion)) {
            MigrateResult baselineRun = Flyway.configure()
                    .dataSource(migrationUrl, migrationUser, migrationPassword)
                    .defaultSchema("business")
                    .schemas("business", "agent")
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .target("020")
                    .load()
                    .migrate();
            assertThat(baselineRun.migrationsExecuted).isEqualTo(20);
            assertThat(baselineRun.targetSchemaVersion).isEqualTo("020");
        }

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

        assertThat(firstRun.migrationsExecuted).isEqualTo("020".equals(upgradeFromVersion) ? 21 : 41);
        assertThat(secondRun.migrationsExecuted).isZero();
        assertThat(firstRun.targetSchemaVersion).isEqualTo("041");
        assertThat(current).isNotNull();
        assertThat(current.getVersion().toString()).isEqualTo("041");

        try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            assertPostgresql18(migration);
            assertSchemasAndObjects(migration);
            configureRuntimePrivileges(migration, runtimeUser);
            assertRoleSeparation(migration, migrationUser, runtimeUser);
            verifyWorkflowV019MapperReads(
                    migration, runtimeUrl, runtimeUser, runtimePassword);
            verifyVideoResultArtifactsWritePath(
                    migration, runtimeUrl, runtimeUser, runtimePassword);
            verifyConversationOwnershipAndCascade(migration);
            verifyGeneratedAssetOwnershipUniquenessAndCascade(migration);
            verifyGeoOwnershipVersioningAndCascade(migration);
            createPermissionProbe(migration);
        }

        PermissionResult permissionResult;
        try (Connection runtime = DriverManager.getConnection(runtimeUrl, runtimeUser, runtimePassword)) {
            permissionResult = verifyRuntimePermissions(runtime);
        } finally {
            try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
                execute(migration, "DROP TABLE IF EXISTS business.gate_runtime_create_probe");
                execute(migration, "DROP TABLE IF EXISTS business.gate_permission_target");
            }
        }

        assertThat(permissionResult.selectAllowed()).isTrue();
        assertThat(permissionResult.insertAllowed()).isTrue();
        assertThat(permissionResult.updateAllowed()).isTrue();
        assertThat(permissionResult.deleteAllowed()).isTrue();
        assertThat(permissionResult.paidTaskDmlAllowed()).isTrue();
        assertThat(permissionResult.createDenied()).isTrue();
        assertThat(permissionResult.alterDenied()).isTrue();
        assertThat(permissionResult.dropDenied()).isTrue();
        assertThat(permissionResult.flywayHistorySelectDenied()).isTrue();
        assertThat(permissionResult.flywayHistoryInsertDenied()).isTrue();
        assertThat(permissionResult.flywayHistoryUpdateDenied()).isTrue();
        assertThat(permissionResult.flywayHistoryDeleteDenied()).isTrue();

        MigrationInfoService info = flyway.info();
        List<String> applied = new ArrayList<>();
        for (MigrationInfo migrationInfo : info.applied()) {
            MigrationVersion version = migrationInfo.getVersion();
            if (version != null) {
                applied.add(version + ":" + migrationInfo.getDescription() + ":" + migrationInfo.getState());
            }
        }

        System.out.println("DB_GATE server=PostgreSQL-18");
        System.out.println("DB_GATE flyway_first_run=" + firstRun.migrationsExecuted);
        System.out.println("DB_GATE flyway_second_run=" + secondRun.migrationsExecuted);
        System.out.println("DB_GATE flyway_applied=" + applied);
        System.out.println("DB_GATE runtime_dml=SELECT,INSERT,UPDATE,DELETE allowed");
        System.out.println("DB_GATE paid_task_runtime_dml=point_account CRUD verified");
        System.out.println("DB_GATE runtime_ddl=CREATE,ALTER,DROP denied");
        System.out.println("DB_GATE flyway_history=SELECT,INSERT,UPDATE,DELETE denied");
        System.out.println("DB_GATE ownership_and_cascade=verified");
        System.out.println("DB_GATE generated_asset_ownership_uniqueness_and_cascade=verified");
    }

    private void verifyWorkflowV019MapperReads(Connection migration, String runtimeUrl,
                                                 String runtimeUser, String runtimePassword)
            throws SQLException {
        long userId = 918100000000000001L;
        long spaceId = 918200000000000001L;
        long productId = 918300000000000001L;
        long conversationId = 918400000000000001L;
        long messageId = 918500000000000001L;
        long planId = 918600000000000001L;
        UUID runId = UUID.fromString("38100000-0000-0000-0000-000000000001");
        UUID proposalId = UUID.fromString("38100000-0000-0000-0000-000000000002");
        String contextHash = "a".repeat(64);
        String context = "{\"contextVersion\":1,\"space\":{\"id\":\"" + spaceId
                + "\"},\"product\":{\"id\":\"" + productId + "\"}}";

        try {
            execute(migration, """
                    INSERT INTO business.account(
                        id,username,username_normalized,phone_e164,password_hash,role,status,created_at,updated_at
                    ) VALUES (
                        918100000000000001,'workflow-gate','workflow-gate','+860000000081',
                        repeat('a',32),'USER','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.business_space(
                        id,user_id,name,name_normalized,brand_name,brand_profile,created_at,updated_at
                    ) VALUES (
                        918200000000000001,918100000000000001,'Workflow Gate','workflow gate',
                        'Workflow Brand','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.product(
                        id,user_id,space_id,name,name_normalized,created_at,updated_at
                    ) VALUES (
                        918300000000000001,918100000000000001,918200000000000001,
                        'Workflow Product','workflow product',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.conversation(
                        id,user_id,title,selected_space_id,selected_product_id,created_at,updated_at
                    ) VALUES (
                        918400000000000001,918100000000000001,'Workflow Mapper Gate',
                        918200000000000001,918300000000000001,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.conversation_message(
                        id,conversation_id,user_id,role,content_type,content,client_message_id,
                        idempotency_key,request_fingerprint,sequence_no,status,created_at,updated_at
                    ) VALUES (
                        918500000000000001,918400000000000001,918100000000000001,'USER','TEXT',
                        '{"text":"workflow mapper gate"}',
                        '38100000-0000-0000-0000-000000000003',
                        '38100000-0000-0000-0000-000000000004',repeat('b',64),1,'COMPLETED',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.agent_run(
                        id,conversation_id,user_id,request_message_id,selected_space_id,selected_product_id,
                        status,context_snapshot,context_ref_hash,context_expires_at,completed_at,
                        created_at,updated_at
                    ) VALUES (
                        '38100000-0000-0000-0000-000000000001',918400000000000001,
                        918100000000000001,918500000000000001,918200000000000001,
                        918300000000000001,'COMPLETED','{}',repeat('c',64),
                        CURRENT_TIMESTAMP + INTERVAL '1 hour',CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            try (PreparedStatement statement = migration.prepareStatement("""
                    INSERT INTO business.workflow_plan(
                        id,user_id,conversation_id,source_run_id,space_id,product_id,proposal_id,
                        request_hash,goal_summary,status,version,step_count,total_quoted_points,
                        max_steps,max_replans,max_model_calls,max_tool_calls,max_provider_calls,
                        max_point_cost,deadline_at,confirmation_expires_at,created_at,updated_at,
                        authorized_context_snapshot,authorized_context_hash,
                        authorized_context_schema_version,authorized_context_data_gap
                    ) VALUES (?,?,?,?,?,?,?,repeat('d',64),'Workflow mapper gate',
                        'WAITING_CONFIRMATION',1,2,20,4,1,4,4,2,20,
                        CURRENT_TIMESTAMP + INTERVAL '1 hour',CURRENT_TIMESTAMP + INTERVAL '30 minutes',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CAST(? AS jsonb),?,1,false)
                    """)) {
                statement.setLong(1, planId);
                statement.setLong(2, userId);
                statement.setLong(3, conversationId);
                statement.setObject(4, runId);
                statement.setLong(5, spaceId);
                statement.setLong(6, productId);
                statement.setObject(7, proposalId);
                statement.setString(8, context);
                statement.setString(9, contextHash);
                statement.executeUpdate();
            }
            execute(migration, """
                    INSERT INTO business.workflow_plan_step(
                        id,plan_id,user_id,conversation_id,step_key,ordinal,step_type,tool_name,
                        tool_version,risk_level,depends_on,success_criteria,input_refs,output_ref,
                        quoted_points,status,created_at
                    ) VALUES
                        (918700000000000001,918600000000000001,918100000000000001,
                         918400000000000001,'step-1',1,'QUERY','catalog.compare','1','QUERY',
                         '[]','["comparison ready"]','[]','observation:comparison',0,'PENDING',CURRENT_TIMESTAMP),
                        (918700000000000002,918600000000000001,918100000000000001,
                         918400000000000001,'step-2',2,'ANALYZE','catalog.synthesize','1','ANALYZE',
                         '["step-1"]','["summary ready"]','["observation:comparison"]',
                         'observation:summary',0,'PENDING',CURRENT_TIMESTAMP)
                    """);

            org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
            dataSource.setUrl(runtimeUrl);
            dataSource.setUser(runtimeUser);
            dataSource.setPassword(runtimePassword);
            Configuration configuration = new Configuration(new Environment(
                    "workflow-v019-mapper-gate", new JdbcTransactionFactory(), dataSource));
            configuration.addMapper(WorkflowPlanMapper.class);
            configuration.addMapper(WorkflowExecutionMapper.class);
            SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);

            try (SqlSession session = sessions.openSession(false)) {
                WorkflowPlanMapper planMapper = session.getMapper(WorkflowPlanMapper.class);
                WorkflowExecutionMapper executionMapper = session.getMapper(WorkflowExecutionMapper.class);

                WorkflowPlanRecords.Plan listed = planMapper.list(userId, null, 0, 10).getFirst();
                WorkflowPlanRecords.Plan found = planMapper.find(planId, userId).orElseThrow();
                WorkflowPlanRecords.Plan locked = planMapper.lock(planId, userId).orElseThrow();
                WorkflowExecutionRecords.PlanState startRead = executionMapper.lockPlan(planId, userId)
                        .orElseThrow();
                WorkflowExecutionRecords.PlanState internalRead = executionMapper.findPlanInternal(planId)
                        .orElseThrow();

                for (WorkflowPlanRecords.Plan plan : List.of(listed, found, locked)) {
                    assertThat(plan.id()).isEqualTo(planId);
                    assertThat(plan.authorizedContextSnapshotJson()).contains("contextVersion");
                    assertThat(plan.authorizedContextHash()).isEqualTo(contextHash);
                    assertThat(plan.authorizedContextSchemaVersion()).isEqualTo(1);
                    assertThat(plan.authorizedContextDataGap()).isFalse();
                    assertThat(plan.createdAt()).isNotNull();
                    assertThat(plan.updatedAt()).isNotNull();
                }
                for (WorkflowExecutionRecords.PlanState plan : List.of(startRead, internalRead)) {
                    assertThat(plan.id()).isEqualTo(planId);
                    assertThat(plan.authorizedContextSnapshotJson()).contains("contextVersion");
                    assertThat(plan.authorizedContextHash()).isEqualTo(contextHash);
                    assertThat(plan.authorizedContextSchemaVersion()).isEqualTo(1);
                    assertThat(plan.authorizedContextDataGap()).isFalse();
                    assertThat(plan.goalSummary()).isEqualTo("Workflow mapper gate");
                }
                assertThat(planMapper.steps(planId, userId)).hasSize(2)
                        .allSatisfy(step -> {
                            assertThat(step.taskId()).isNull();
                            assertThat(step.taskStatus()).isNull();
                            assertThat(step.taskResultRef()).isNull();
                        });
                assertThat(executionMapper.steps(planId, userId)).hasSize(2)
                        .allSatisfy(step -> {
                            assertThat(step.authorizedInputHash()).isNull();
                            assertThat(step.taskResultRef()).isNull();
                            assertThat(step.taskStatus()).isNull();
                        });
                session.rollback();
            }
            System.out.println("DB_GATE workflow_v019_mapper_list_find_lock_start=verified");
        } finally {
            execute(migration, "DELETE FROM business.conversation WHERE id=" + conversationId);
            execute(migration, "DELETE FROM business.product WHERE id=" + productId);
            execute(migration, "DELETE FROM business.business_space WHERE id=" + spaceId);
            execute(migration, "DELETE FROM business.account WHERE id=" + userId);
        }
    }

    private void verifySafeIsolatedTarget(
            String expectedDatabase,
            String migrationUrl,
            String migrationUser,
            String migrationPassword,
            String runtimeUrl,
            String runtimeUser,
            String runtimePassword
    ) throws SQLException {
        requireSafeExpectedDatabase(expectedDatabase);

        String migrationDatabase;
        try (Connection connection = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            migrationDatabase = currentDatabase(connection);
        }
        String runtimeDatabase;
        try (Connection connection = DriverManager.getConnection(runtimeUrl, runtimeUser, runtimePassword)) {
            runtimeDatabase = currentDatabase(connection);
        }

        if (!expectedDatabase.equals(migrationDatabase)) {
            throw new IllegalStateException("Migration URL does not point to GATE_EXPECTED_DATABASE");
        }
        if (!expectedDatabase.equals(runtimeDatabase)) {
            throw new IllegalStateException("Runtime URL does not point to GATE_EXPECTED_DATABASE");
        }
        if (!migrationDatabase.equals(runtimeDatabase)) {
            throw new IllegalStateException("Migration URL and runtime URL must point to the same database");
        }
    }

    static void requireSafeExpectedDatabase(String expectedDatabase) {
        if (expectedDatabase == null || !expectedDatabase.matches("^zhixiaohe_gate_[a-z0-9_]+$")) {
            throw new IllegalStateException(
                    "Database Gate target must start with zhixiaohe_gate_ and use only lowercase letters, digits, or underscores"
            );
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

    private void assertPostgresql18(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW server_version_num")) {
            assertThat(result.next()).isTrue();
            assertThat(Integer.parseInt(result.getString(1))).isBetween(180000, 189999);
        }
    }

    private void assertSchemasAndObjects(Connection connection) throws SQLException {
        Set<String> schemas = stringSet(connection,
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('business', 'agent')");
        assertThat(schemas).containsExactlyInAnyOrder("business", "agent");

        Set<String> tables = stringSet(connection, """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'business' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
        assertThat(tables).doesNotContain("shop", "store");

        Set<String> foreignKeys = stringSet(connection, """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'FOREIGN KEY'
                ORDER BY constraint_name
                """);
        assertThat(foreignKeys).contains(
                "fk_business_space_user",
                "fk_account_activation_user",
                "fk_account_security_audit_actor",
                "fk_account_security_audit_target",
                "fk_product_space",
                "fk_product_alias_product",
                "fk_competitor_space",
                "fk_competitor_alias_competitor",
                "fk_conversation_message_reply",
                "fk_agent_run_request_message",
                "fk_agent_run_response_message",
                "fk_conversation_event_run",
                "fk_conversation_event_message",
                "fk_task_draft_run",
                "fk_task_draft_space",
                "fk_task_draft_product",
                "fk_business_task_draft",
                "fk_point_reservation_task",
                "fk_task_execution_task",
                "fk_business_task_event_execution",
                "fk_inbox_event_outbox",
                "fk_conversation_event_task",
                "fk_asset_source_task", "fk_asset_primary_product",
                "fk_asset_product_relation_asset", "fk_asset_product_relation_product",
                "fk_asset_ingestion_task", "fk_asset_ingestion_execution",
                "fk_asset_ingestion_product"
                , "fk_geo_topic_space", "fk_geo_topic_product", "fk_geo_keyword_topic",
                "fk_geo_question_topic", "fk_geo_competitor_set_topic",
                "fk_geo_competitor_member_set", "fk_geo_competitor_member_competitor",
                "fk_geo_run_task", "fk_geo_run_topic", "fk_geo_run_product",
                "fk_geo_observation_run", "fk_geo_citation_observation",
                "fk_geo_citation_run", "fk_geo_report_run", "fk_geo_report_topic",
                "fk_geo_plan_space", "fk_geo_plan_product", "fk_geo_plan_topic",
                "fk_geo_plan_draft", "fk_business_task_plan", "fk_geo_plan_slot_plan",
                "fk_geo_plan_slot_task", "fk_article_space", "fk_article_product",
                "fk_article_source_task", "fk_article_asset_article", "fk_article_asset_asset",
                "fk_article_snapshot_article", "fk_article_snapshot_product",
                "fk_article_title_batch_article", "fk_article_title_batch_task",
                "fk_article_plan_article", "fk_article_plan_task",
                "fk_article_submission_preference_article",
                "fk_media_price_resource", "fk_media_batch_task", "fk_media_batch_draft",
                "fk_media_batch_article", "fk_media_batch_snapshot", "fk_media_batch_product",
                "fk_media_submission_batch", "fk_media_submission_resource",
                "fk_media_submission_price", "fk_media_submission_snapshot",
                "fk_media_event_submission", "fk_media_callback_submission",
                "fk_workflow_plan_conversation", "fk_workflow_plan_run",
                "fk_workflow_plan_space", "fk_workflow_plan_product",
                "fk_workflow_step_plan", "fk_workflow_step_draft",
                "fk_workflow_confirmation_plan", "fk_workflow_event_plan",
                "fk_workflow_execution_plan", "fk_workflow_observation_step",
                "fk_workflow_observation_execution", "fk_workflow_reservation_plan"
        );

        Set<String> uniqueIndexes = stringSet(connection, """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'business' AND indexdef ILIKE '%UNIQUE%'
                ORDER BY indexname
                """);
        assertThat(uniqueIndexes).contains(
                "uk_account_username_normalized",
                "uk_account_phone_e164",
                "uk_account_activation_token",
                "uk_account_activation_available",
                "uk_deactivation_request_open",
                "uk_business_space_user_name",
                "uk_product_space_name",
                "uk_competitor_space_name",
                "uk_agent_run_active_conversation",
                "uk_conversation_event_terminal_run",
                "uk_conversation_event_agent_progress_identity",
                "uk_task_draft_run",
                "uk_business_task_idempotency",
                "uk_point_reservation_task",
                "uk_task_execution_number",
                "uk_business_task_event_source",
                "uk_outbox_task_execution_attempt",
                "uk_inbox_consumer_event", "uk_asset_object_key", "uk_asset_source_task_artifact",
                 "uk_asset_ingestion_task_artifact", "uk_asset_ingestion_execution_artifact",
                 "uk_asset_ingestion_asset", "uk_asset_ingestion_object_key",
                 "uk_task_draft_idempotency"
                 , "uk_geo_topic_space_name", "uk_geo_keyword_topic_sequence",
                 "uk_geo_keyword_topic_text", "uk_geo_question_topic_sequence",
                 "uk_geo_question_topic_text", "uk_geo_competitor_set_version",
                 "uk_geo_competitor_member_sequence", "uk_geo_competitor_member_competitor",
                 "uk_geo_run_task", "uk_geo_observation_event", "uk_geo_observation_sequence",
                 "uk_geo_observation_unit",
                 "uk_geo_observation_unit_key", "uk_geo_report_run",
                 "uk_geo_plan_source_draft", "uk_geo_plan_idempotency", "uk_geo_plan_name", "uk_business_task_plan_slot",
                  "uk_geo_plan_slot", "uk_article_asset", "uk_article_asset_position",
                  "uk_article_asset_order", "uk_article_snapshot_version",
                  "uk_article_title_batch_version", "uk_article_title_batch_task",
                  "uk_article_plan_version", "uk_article_plan_task",
                  "uk_media_resource_upstream", "uk_media_batch_task", "uk_media_batch_draft",
                  "uk_media_submission_customer_order", "uk_media_submission_batch_resource",
                  "uk_media_event_sequence", "uk_media_event_source",
                  "uk_media_callback_order_identity", "uk_media_callback_resource_identity",
                  "uk_workflow_plan_proposal", "uk_workflow_plan_source_run",
                  "uk_workflow_step_key", "uk_workflow_step_ordinal",
                  "uk_workflow_step_draft", "uk_workflow_confirmation_plan_version",
                  "uk_workflow_confirmation_token", "uk_workflow_confirmation_user_idempotency",
                  "uk_workflow_event_sequence", "uk_workflow_plan_start_idempotency",
                  "uk_workflow_execution_number", "uk_workflow_execution_claim",
                  "uk_workflow_execution_active", "uk_workflow_observation_event",
                  "uk_workflow_observation_attempt", "uk_workflow_observation_tool_call",
                  "uk_workflow_reservation_plan", "uk_workflow_step_activation_event"
        );

        Set<String> uniqueConstraints = stringSet(connection, """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'UNIQUE'
                ORDER BY constraint_name
                """);
        assertThat(uniqueConstraints).contains(
                "uk_conversation_message_sequence",
                "uk_account_activation_token",
                "uk_agent_run_request_message",
                "uk_conversation_event_sequence",
                "uk_task_confirmation_token_hash",
                "uk_business_task_draft",
                "uk_task_execution_number",
                "uk_business_task_event_source",
                "uk_outbox_task_execution_attempt",
                "uk_inbox_consumer_event", "uk_asset_owner", "uk_asset_source_task_artifact",
                "uk_asset_ingestion_task_artifact", "uk_asset_ingestion_execution_artifact",
                "uk_asset_ingestion_asset", "uk_asset_ingestion_object_key"
                , "uk_geo_topic_owner", "uk_geo_keyword_topic_sequence",
                "uk_geo_keyword_topic_text", "uk_geo_question_topic_sequence",
                "uk_geo_question_topic_text", "uk_geo_competitor_set_owner",
                "uk_geo_competitor_set_version", "uk_geo_competitor_member_sequence",
                "uk_geo_competitor_member_competitor", "uk_geo_run_owner",
                "uk_geo_observation_owner", "uk_geo_report_owner",
                "uk_geo_plan_owner", "uk_geo_plan_source_draft", "uk_geo_plan_slot",
                "uk_article_title_batch_version", "uk_article_title_batch_task",
                "uk_article_plan_version",
                "uk_media_resource_upstream", "uk_media_resource_owner",
                "uk_media_price_owner", "uk_media_batch_task", "uk_media_batch_draft",
                "uk_media_batch_owner", "uk_media_submission_customer_order",
                "uk_media_submission_batch_resource", "uk_media_submission_upstream_order",
                "uk_media_event_sequence", "uk_media_event_source",
                "uk_workflow_plan_owner", "uk_workflow_plan_conversation_owner",
                "uk_workflow_plan_proposal", "uk_workflow_plan_source_run",
                "uk_workflow_step_key", "uk_workflow_step_ordinal",
                "uk_workflow_step_draft", "uk_workflow_confirmation_plan_version",
                "uk_workflow_confirmation_token", "uk_workflow_confirmation_user_idempotency",
                "uk_workflow_event_sequence", "uk_workflow_plan_start_idempotency",
                "uk_workflow_execution_number", "uk_workflow_execution_claim",
                "uk_workflow_observation_event", "uk_workflow_observation_attempt",
                "uk_workflow_observation_tool_call", "uk_workflow_reservation_plan",
                "uk_workflow_step_activation_event"
        );

        assertContextSnapshotColumn(connection);
        assertAgentRunTraceColumn(connection);
        assertCompositeOwnershipForeignKeys(connection);
        assertPartialUniqueIndex(connection, "uk_agent_run_active_conversation", "stop_requested");
        assertPartialUniqueIndex(connection, "uk_conversation_event_terminal_run", "message.completed");
        assertAgentVersionConstraints(connection);
        assertAgentVersionIndexes(connection);

        Set<String> accountAccessChecks = stringSet(connection, """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'CHECK'
                  AND table_name IN ('account', 'account_activation', 'account_security_audit')
                ORDER BY constraint_name
                """);
        assertThat(accountAccessChecks).contains(
                "ck_account_password_algorithm", "ck_account_credential_state",
                "ck_account_activation_hash", "ck_account_activation_version",
                "ck_account_activation_status", "ck_account_activation_lifecycle",
                "ck_account_security_audit_event", "ck_account_security_audit_details");

        Set<String> v003Checks = stringSet(connection, """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'CHECK'
                  AND table_name IN ('conversation_message', 'agent_run', 'conversation_event')
                ORDER BY constraint_name
                """);
        assertThat(v003Checks).contains(
                "ck_conversation_message_sequence_no",
                "ck_agent_run_context_snapshot",
                "ck_agent_run_python_sequence",
                "ck_conversation_event_sequence_no"
        );

        Set<String> paidTaskChecks = stringSet(connection, """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'CHECK'
                  AND table_name IN ('task_draft', 'task_confirmation', 'point_account',
                    'business_task', 'point_reservation', 'point_ledger', 'task_execution',
                    'business_task_event', 'outbox_event', 'inbox_event')
                ORDER BY constraint_name
                """);
        assertThat(paidTaskChecks).contains(
                "ck_task_draft_snapshot", "ck_task_confirmation_token_hash",
                "ck_point_account_available", "ck_business_task_status",
                "ck_business_task_points", "ck_point_reservation_amounts",
                "ck_point_ledger_balances", "ck_task_execution_token_hash",
                "ck_task_execution_submission_state", "ck_business_task_event_request_hash",
                 "ck_outbox_event_traceparent", "ck_outbox_execution_attempt",
                 "ck_outbox_payload_execution_attempt", "ck_inbox_event_disposition",
                 "ck_task_draft_source", "ck_task_draft_source_binding"
        );

        assertTaskDraftSourceColumns(connection);

        Set<String> workflowChecks = stringSet(connection, """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'CHECK'
                  AND table_name IN ('workflow_plan', 'workflow_plan_step',
                    'workflow_confirmation', 'workflow_event', 'workflow_execution',
                    'workflow_observation', 'workflow_point_reservation')
                ORDER BY constraint_name
                """);
        assertThat(workflowChecks).contains(
                "ck_workflow_plan_request_hash", "ck_workflow_plan_status",
                "ck_workflow_plan_steps", "ck_workflow_plan_budget",
                "ck_workflow_plan_lifecycle", "ck_workflow_step_type",
                "ck_workflow_step_tool", "ck_workflow_step_prepare",
                "ck_workflow_confirmation_hash", "ck_workflow_confirmation_request_hash",
                "ck_workflow_confirmation_idempotency_pair", "ck_workflow_confirmation_lifecycle",
                "ck_workflow_event_sequence", "ck_workflow_event_type",
                "ck_workflow_execution_token", "ck_workflow_execution_context",
                "ck_workflow_execution_lifecycle", "ck_workflow_observation_request_hash",
                "ck_workflow_observation_facts", "ck_workflow_observation_lifecycle",
                "ck_workflow_reservation_amounts", "ck_workflow_plan_lease_recovery_count",
                "ck_workflow_step_authorized_draft", "ck_workflow_step_activation",
                "ck_workflow_step_task_result_ref", "ck_workflow_plan_authorized_context"
        );
        assertWorkflowConfirmationAuthorizationSchema(connection);
        assertWorkflowTaskBridgeSchema(connection);
        assertWorkflowAuthorizedContextSchema(connection);

        Set<String> articleWorkflowChecks = stringSet(connection, """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'CHECK'
                  AND table_name IN ('article', 'article_title_candidate_batch',
                    'article_creative_plan_revision', 'article_submission_preference')
                ORDER BY constraint_name
                """);
        assertThat(articleWorkflowChecks).contains(
                "ck_article_workflow_stage", "ck_article_title_workflow",
                "ck_article_workflow_versions", "ck_article_title_batch_versions",
                "ck_article_title_candidates", "ck_article_plan_versions",
                "ck_article_plan_source", "ck_article_plan_payload",
                "ck_article_submission_preference_version",
                "ck_article_submission_preference_goal",
                "ck_article_submission_preference_region",
                "ck_article_submission_preference_budget");

        Set<String> assetChecks = stringSet(connection, """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'CHECK'
                  AND table_name IN ('asset', 'asset_ingestion_work')
                ORDER BY constraint_name
                """);
        assertThat(assetChecks).contains(
                "ck_asset_type", "ck_asset_source_type", "ck_asset_object_key",
                "ck_asset_sha256", "ck_asset_dimensions", "ck_asset_status",
                "ck_asset_safety_status", "ck_asset_ready_safety", "ck_asset_generated_source",
                "ck_asset_source_artifact_index", "ck_asset_ingestion_artifact_index",
                "ck_asset_ingestion_provider", "ck_asset_ingestion_provider_url",
                "ck_asset_ingestion_content_type", "ck_asset_ingestion_dimensions",
                "ck_asset_ingestion_object_key", "ck_asset_ingestion_status",
                "ck_asset_ingestion_attempts", "ck_asset_ingestion_lease"
        );
        assertAssetIngestionLifecycleColumns(connection);
        assertVideoAssetConstraints(connection);

        Set<String> geoChecks = stringSet(connection, """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'CHECK'
                  AND table_name IN ('geo_topic', 'geo_keyword', 'geo_question',
                    'geo_competitor_set', 'geo_competitor_set_member', 'geo_run',
                    'geo_observation', 'geo_citation', 'geo_report', 'geo_plan', 'geo_plan_slot')
                ORDER BY constraint_name
                """);
        assertThat(geoChecks).contains(
                "ck_geo_topic_version", "ck_geo_keyword_sequence", "ck_geo_keyword_type",
                "ck_geo_question_sequence", "ck_geo_question_category", "ck_geo_question_source",
                "ck_geo_competitor_set_version", "ck_geo_competitor_member_sequence",
                "ck_geo_competitor_member_type", "ck_geo_run_status", "ck_geo_run_counts",
                "ck_geo_run_snapshot", "ck_geo_observation_sequence", "ck_geo_observation_result",
                "ck_geo_observation_usage",
                "ck_geo_citation_url", "ck_geo_report_status", "ck_geo_report_synthesis",
                "ck_geo_plan_schedule", "ck_geo_plan_lifecycle", "ck_geo_plan_slot_result"
        );

        Set<String> mediaChecks = stringSet(connection, """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'CHECK'
                  AND table_name LIKE 'media_%'
                ORDER BY constraint_name
                """);
        assertThat(mediaChecks).contains(
                "ck_media_resource_provider", "ck_media_resource_price",
                "ck_media_price_amounts", "ck_media_price_expiry",
                "ck_media_batch_status", "ck_media_batch_points",
                "ck_media_submission_customer_order", "ck_media_submission_price",
                "ck_media_submission_state_alignment", "ck_media_submission_terminal_fields",
                "ck_media_callback_type", "ck_media_callback_status",
                "ck_media_event_details", "ck_media_sync_status"
        );

        int checkCount = scalarInt(connection, """
                SELECT count(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'business' AND constraint_type = 'CHECK'
                """);
        assertThat(checkCount).isGreaterThanOrEqualTo(30);

        System.out.println("DB_GATE schemas=" + schemas);
        System.out.println("DB_GATE business_tables=" + tables);
        System.out.println("DB_GATE foreign_keys=" + foreignKeys);
        System.out.println("DB_GATE unique_indexes=" + uniqueIndexes);
        System.out.println("DB_GATE v003_unique_constraints=" + uniqueConstraints);
        System.out.println("DB_GATE v003_checks=" + v003Checks);
        System.out.println("DB_GATE paid_task_checks=" + paidTaskChecks);
        System.out.println("DB_GATE asset_checks=" + assetChecks);
        System.out.println("DB_GATE geo_checks=" + geoChecks);
        System.out.println("DB_GATE media_checks=" + mediaChecks);
        System.out.println("DB_GATE check_constraints=" + checkCount);
    }

    private void assertAgentVersionIndexes(Connection connection) throws SQLException {
        Map<String, String> indexes = new java.util.LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT indexname, indexdef
                FROM pg_indexes
                WHERE schemaname='business' AND indexname = ANY (ARRAY[
                    'idx_task_execution_bundle_started',
                    'idx_workflow_execution_bundle_started'])
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                indexes.put(result.getString("indexname"), result.getString("indexdef"));
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

    private void assertAgentVersionConstraints(Connection connection) throws SQLException {
        Map<String, String> constraints = new java.util.LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conname, pg_get_constraintdef(oid), convalidated
                FROM pg_constraint
                WHERE connamespace='business'::regnamespace AND conname = ANY (ARRAY[
                    'ck_task_execution_bundle_version',
                    'ck_workflow_execution_bundle_version'])
                """); ResultSet result = statement.executeQuery()) {
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

    private void assertTaskDraftSourceColumns(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, is_nullable, data_type
                FROM information_schema.columns
                WHERE table_schema='business' AND table_name='task_draft'
                  AND column_name IN ('agent_run_id','draft_source','operation_scope',
                    'idempotency_key','request_hash')
                ORDER BY column_name
                """); ResultSet result = statement.executeQuery()) {
            Map<String, String> columns = new java.util.HashMap<>();
            while (result.next()) {
                columns.put(result.getString("column_name"),
                        result.getString("is_nullable") + ":" + result.getString("data_type"));
            }
            assertThat(columns).containsEntry("agent_run_id", "YES:uuid")
                    .containsEntry("draft_source", "NO:character varying")
                    .containsEntry("operation_scope", "YES:character varying")
                    .containsEntry("idempotency_key", "YES:uuid")
                    .containsEntry("request_hash", "YES:character");
        }
    }

    private void assertWorkflowConfirmationAuthorizationSchema(Connection connection) throws SQLException {
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
        Map<String, String> definitions = new java.util.HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conname, pg_get_constraintdef(oid) AS definition
                FROM pg_constraint
                WHERE connamespace='business'::regnamespace
                  AND conname IN ('ck_workflow_plan_status','ck_workflow_confirmation_lifecycle',
                    'ck_workflow_confirmation_idempotency_pair','ck_workflow_event_type',
                    'uk_workflow_confirmation_user_idempotency')
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) definitions.put(result.getString(1), result.getString(2));
        }
        assertThat(definitions).hasSize(5);
        String all = String.join(" ", definitions.values()).toLowerCase(java.util.Locale.ROOT);
        assertThat(all).contains("confirmed", "plan_confirmed", "confirmation_idempotency_key",
                "request_hash", "user_id");
    }

    private void assertWorkflowTaskBridgeSchema(Connection connection) throws SQLException {
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

    private void assertWorkflowAuthorizedContextSchema(Connection connection) throws SQLException {
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
        String definition;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE connamespace='business'::regnamespace
                  AND conname='ck_workflow_plan_authorized_context'
                """); ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            definition = result.getString(1);
        }
        assertThat(definition.toLowerCase(java.util.Locale.ROOT))
                .contains("authorized_context_data_gap", "jsonb_typeof", "196608", "64");
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

    private void assertVideoAssetConstraints(Connection connection) throws SQLException {
        Map<String,String> definitions=new java.util.HashMap<>();
        try (PreparedStatement statement=connection.prepareStatement("""
                SELECT conname, pg_get_constraintdef(oid) AS definition
                FROM pg_constraint
                WHERE connamespace='business'::regnamespace
                  AND conname IN ('ck_asset_dimensions','ck_asset_media_content_type',
                    'ck_asset_ingestion_provider','ck_asset_ingestion_provider_url',
                    'ck_asset_ingestion_content_type','ck_asset_ingestion_dimensions',
                    'ck_asset_ingestion_object_key')
                """); ResultSet result=statement.executeQuery()) {
            while(result.next()) definitions.put(result.getString(1),result.getString(2));
        }
        assertThat(definitions).hasSize(7);
        String all=String.join(" ",definitions.values()).toLowerCase(java.util.Locale.ROOT);
        assertThat(all).contains("wan_video","wan3.0-video","video/mp4","duration_seconds >",
                "expected_duration_seconds >", ".mp4", "ingestion_deadline_at is not null");
    }

    private void configureRuntimePrivileges(Connection connection, String runtimeUser) throws SQLException {
        String role = quoteIdentifier(runtimeUser);
        execute(connection, "REVOKE ALL ON SCHEMA business, agent FROM PUBLIC");
        execute(connection, "GRANT USAGE ON SCHEMA business, agent TO " + role);

        Set<String> tables = stringSet(connection, """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'business' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """);
        for (String table : tables) {
            execute(connection, "GRANT SELECT, INSERT, UPDATE, DELETE ON business."
                    + quoteIdentifier(table) + " TO " + role);
        }
        execute(connection, "REVOKE ALL ON business.flyway_schema_history FROM " + role);
        execute(connection, "ALTER DEFAULT PRIVILEGES IN SCHEMA business "
                + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + role);
        execute(connection, "ALTER DEFAULT PRIVILEGES IN SCHEMA agent "
                + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + role);
    }

    private void assertRoleSeparation(Connection connection, String migrationUser, String runtimeUser)
            throws SQLException {
        assertThat(hasRoleFlag(connection, migrationUser, "rolcanlogin")).isTrue();
        assertThat(hasRoleFlag(connection, runtimeUser, "rolcanlogin")).isTrue();
        assertThat(hasRoleFlag(connection, runtimeUser, "rolsuper")).isFalse();
        assertThat(hasRoleFlag(connection, runtimeUser, "rolcreatedb")).isFalse();
        assertThat(hasRoleFlag(connection, runtimeUser, "rolcreaterole")).isFalse();

        assertThat(hasPrivilege(connection, runtimeUser, "business", "USAGE")).isTrue();
        assertThat(hasPrivilege(connection, runtimeUser, "business", "CREATE")).isFalse();
        assertThat(hasTablePrivilege(connection, runtimeUser, "business.flyway_schema_history", "SELECT"))
                .isFalse();
        for (String table : EXPECTED_TABLES) {
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(hasTablePrivilege(connection, runtimeUser, "business." + table, privilege))
                        .as(table + ":" + privilege).isTrue();
            }
        }
    }

    private void assertContextSnapshotColumn(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'business' AND table_name = 'agent_run'
                  AND column_name = 'context_snapshot'
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("data_type")).isEqualTo("jsonb");
                assertThat(result.getString("is_nullable")).isEqualTo("NO");
            }
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
        assertThat(stringSet(connection, """
                SELECT indexname FROM pg_indexes
                WHERE schemaname='business' AND indexname='idx_agent_run_trace_id'
                """)).containsExactly("idx_agent_run_trace_id");
        assertThat(stringSet(connection, """
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE constraint_schema='business' AND table_name='agent_run'
                  AND constraint_name='ck_agent_run_trace_id'
                """)).containsExactly("ck_agent_run_trace_id");
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
                        "FOREIGN KEY (competitor_id, user_id, space_id)"),
                Map.entry("fk_workflow_plan_conversation",
                        "FOREIGN KEY (conversation_id, user_id)"),
                Map.entry("fk_workflow_plan_run",
                        "FOREIGN KEY (source_run_id, user_id, conversation_id)"),
                Map.entry("fk_workflow_plan_space",
                        "FOREIGN KEY (space_id, user_id)"),
                Map.entry("fk_workflow_plan_product",
                        "FOREIGN KEY (product_id, user_id, space_id)"),
                Map.entry("fk_workflow_step_plan",
                        "FOREIGN KEY (plan_id, user_id, conversation_id)"),
                Map.entry("fk_workflow_step_draft",
                        "FOREIGN KEY (task_draft_id, user_id, conversation_id)"),
                Map.entry("fk_workflow_confirmation_plan",
                        "FOREIGN KEY (plan_id, user_id, conversation_id)"),
                Map.entry("fk_workflow_event_plan",
                        "FOREIGN KEY (plan_id, user_id, conversation_id)")
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

    private void verifyVideoResultArtifactsWritePath(Connection migration, String runtimeUrl,
                                                     String runtimeUser, String runtimePassword)
            throws Exception {
        long userId = 912000000000000001L;
        long spaceId = 922000000000000001L;
        long productId = 932000000000000001L;
        long conversationId = 942000000000000001L;
        long messageId = 952000000000000001L;
        long draftId = 962000000000000001L;
        long taskId = 972000000000000001L;
        long executionId = 982000000000000001L;
        long reservationId = 902000000000000001L;
        String token = "v1.database-gate-video-execution-token";
        UUID runId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID providerRequestId = UUID.fromString("30000000-0000-0000-0000-000000000002");
        try {
            execute(migration, """
                    INSERT INTO business.account(
                        id,username,username_normalized,phone_e164,password_hash,role,created_at,updated_at
                    ) VALUES (
                        912000000000000001,'video_mapper_gate','video_mapper_gate','+8611200000001',
                        repeat('a',32),'USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.business_space(
                        id,user_id,name,name_normalized,brand_name,created_at,updated_at
                    ) VALUES (
                        922000000000000001,912000000000000001,'video-space','video-space',
                        'video-brand',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.product(
                        id,user_id,space_id,name,name_normalized,created_at,updated_at
                    ) VALUES (
                        932000000000000001,912000000000000001,922000000000000001,
                        'video-product','video-product',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.conversation(id,user_id,title,created_at,updated_at)
                    VALUES (942000000000000001,912000000000000001,'video-mapper-gate',
                            CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            execute(migration, """
                    INSERT INTO business.conversation_message(
                        id,conversation_id,user_id,role,content_type,content,client_message_id,
                        idempotency_key,request_fingerprint,sequence_no,status,created_at,updated_at
                    ) VALUES (
                        952000000000000001,942000000000000001,912000000000000001,'USER','TEXT',
                        '{"text":"video gate"}','30000000-0000-0000-0000-000000000003',
                        '30000000-0000-0000-0000-000000000004',repeat('b',64),1,'COMPLETED',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.agent_run(
                        id,conversation_id,user_id,request_message_id,status,context_snapshot,
                        context_ref_hash,context_expires_at,created_at,updated_at
                    ) VALUES (
                        '30000000-0000-0000-0000-000000000001',942000000000000001,
                        912000000000000001,952000000000000001,'COMPLETED','{}',repeat('c',64),
                        CURRENT_TIMESTAMP + INTERVAL '5 minutes',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.task_draft(
                        id,user_id,conversation_id,agent_run_id,task_type,parameter_schema_version,
                        space_id,product_id,parameters,input_snapshot,input_hash,pricing_version_id,
                        quoted_points,status,expires_at,created_at,updated_at
                    ) VALUES (
                        962000000000000001,912000000000000001,942000000000000001,
                        '30000000-0000-0000-0000-000000000001','VIDEO',1,922000000000000001,
                        932000000000000001,'{}','{}',repeat('d',64),7001,11,'CONFIRMED',
                        CURRENT_TIMESTAMP + INTERVAL '5 minutes',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.business_task(
                        id,user_id,space_id,product_id,conversation_id,draft_id,task_type,status,
                        stage_code,snapshot_schema_version,input_snapshot,input_hash,
                        pricing_version_id,quoted_points,operation_scope,idempotency_key,request_hash,
                        queued_at,started_at,created_at,updated_at
                    ) VALUES (
                        972000000000000001,912000000000000001,922000000000000001,
                        932000000000000001,942000000000000001,962000000000000001,'VIDEO',
                        'RUNNING','PROVIDER_GENERATING',1,
                        '{"parameters":{"durationSeconds":5,"aspectRatio":"16:9",'
                        '"resolution":"480P"},"authorizedContext":{}}',repeat('e',64),7001,11,
                        'TASK_CONFIRM','30000000-0000-0000-0000-000000000005',repeat('f',64),
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.task_execution(
                        id,task_id,user_id,execution_no,status,consumer_name,worker_id,bundle_version,
                        execution_token_hash,lease_expires_at,stage_code,started_at,created_at,updated_at
                    ) VALUES (
                        982000000000000001,972000000000000001,912000000000000001,1,'RUNNING',
                        'agent-video-gate','worker-gate','bundle-gate','%s',
                        CURRENT_TIMESTAMP + INTERVAL '5 minutes','PROVIDER_GENERATING',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """.formatted(sha256(token)));
            execute(migration, """
                    INSERT INTO business.point_account(
                        user_id,available_points,frozen_points,version,created_at,updated_at
                    ) VALUES (
                        912000000000000001,89,11,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);
            execute(migration, """
                    INSERT INTO business.point_reservation(
                        id,user_id,task_id,quoted_points,frozen_points,settled_points,released_points,
                        status,created_at,updated_at
                    ) VALUES (
                        902000000000000001,912000000000000001,972000000000000001,
                        11,11,0,0,'FROZEN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    )
                    """);

            org.postgresql.ds.PGSimpleDataSource dataSource =
                    new org.postgresql.ds.PGSimpleDataSource();
            dataSource.setUrl(runtimeUrl);
            dataSource.setUser(runtimeUser);
            dataSource.setPassword(runtimePassword);
            Configuration configuration = new Configuration(new Environment(
                    "video-result-gate", new JdbcTransactionFactory(), dataSource));
            configuration.addMapper(TaskControlMapper.class);
            configuration.addMapper(AssetMapper.class);
            SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);

            try (SqlSession session = sessions.openSession(false)) {
                TaskControlMapper mapper = session.getMapper(TaskControlMapper.class);
                AssetMapper assetMapper = session.getMapper(AssetMapper.class);
                ConversationMapper conversations = mock(ConversationMapper.class);
                when(conversations.nextEventSequence(conversationId, userId)).thenReturn(2L);
                when(conversations.insertEvent(any())).thenReturn(1);
                SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
                AtomicLong sequence = new AtomicLong(992000000000000000L);
                when(ids.nextId()).thenAnswer(ignored -> sequence.incrementAndGet());
                PaidTaskProperties taskProperties = new PaidTaskProperties();
                taskProperties.setEnabled(true);
                taskProperties.setTokenSecret("database-gate-task-secret-at-least-32-bytes");
                taskProperties.setExecutionLease(Duration.ofMinutes(2));
                AssetIngestionProperties assetProperties = new AssetIngestionProperties();
                assetProperties.setEnabled(true);
                assetProperties.setEnvironment("test");
                assetProperties.setMaxAttempts(3);
                assetProperties.setProviderAllowedHosts(List.of("result.example"));
                ObjectMapper json = new ObjectMapper().findAndRegisterModules();
                TaskControlService service = new TaskControlService(
                        mapper, conversations, ids, json, taskProperties, assetProperties,
                        mock(com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory.class));

                ObjectNode artifact = json.createObjectNode();
                artifact.put("artifactType", "VIDEO");
                artifact.put("unitKey", "video:1");
                artifact.put("schemaVersion", 1);
                artifact.put("providerUrl", "https://result.example/video.mp4?signature=redacted");
                artifact.put("contentType", "video/mp4");
                artifact.putNull("expiresAt");
                artifact.putNull("inlineData");
                ObjectNode metadata = artifact.putObject("metadata");
                metadata.put("durationSeconds", new BigDecimal("5.000"));
                metadata.put("requestedDurationSeconds", 5);
                metadata.put("requestedAspectRatio", "16:9");
                metadata.put("requestedResolution", "480P");
                metadata.put("mock", false);
                metadata.put("providerRequestId", providerRequestId.toString());
                ObjectNode usage = json.createObjectNode();
                usage.put("inputTokens", 0);
                usage.put("outputTokens", 0);
                usage.put("images", 0);
                usage.put("videoSeconds", 5);
                usage.putNull("providerCost");
                usage.putNull("currency");
                TaskDtos.ResultArtifactsRequest request = new TaskDtos.ResultArtifactsRequest(
                        UUID.fromString("30000000-0000-0000-0000-000000000006"), 1, token,
                        "WAN_VIDEO", "wan3.0-video", providerRequestId, "wan-task-gate",
                        "CONFIRMED", List.of(artifact), usage, 1_000);

                assertThat(service.reportResult(taskId, request).response().taskStatus())
                        .isEqualTo("RUNNING");
                session.commit();

                AssetIngestionService ingestion = new AssetIngestionService(
                        assetMapper, mapper, conversations, ids, json, assetProperties);
                AssetRecords.IngestionWork claimed = ingestion.claimNext().orElseThrow();
                assertThat(claimed.taskId()).isEqualTo(taskId);
                assertThat(claimed.executionId()).isEqualTo(executionId);
                assertThat(claimed.status()).isEqualTo("PROCESSING");
                assertThat(claimed.attempt()).isEqualTo(1);
                assertThat(claimed.claimToken()).isNotNull();
                assertThat(claimed.expectedDurationSeconds())
                        .isEqualByComparingTo(new BigDecimal("5.000"));
                assertThat(claimed.expectedAspectRatio()).isEqualTo("16:9");
                assertThat(claimed.expectedResolution()).isEqualTo("480P");
                assertThat(claimed.providerUrlExpiresAt()).isNull();
                assertThat(claimed.ingestionDeadlineAt()).isNotNull();

                DownloadedArtifact downloaded = new DownloadedArtifact(
                        Path.of("database-gate-video.mp4"), "video/mp4", 4096,
                        "a".repeat(64), 832, 480, "5.000", "h264");
                assertThat(ingestion.complete(claimed.id(), claimed.attempt(),
                        claimed.claimToken(), downloaded, "TEST_BYPASS")).isPresent();
                session.commit();
            }

            assertThat(scalarInt(migration, "SELECT count(*) FROM business.asset_ingestion_work"
                    + " WHERE task_id=" + taskId + " AND provider_code='WAN_VIDEO'"
                    + " AND expected_duration_seconds=5.000 AND status='SUCCEEDED'"
                    + " AND provider_url IS NULL AND claim_token IS NULL")).isEqualTo(1);
            assertThat(scalarInt(migration, "SELECT count(*) FROM business.task_execution"
                    + " WHERE id=" + executionId + " AND status='SUCCEEDED'"
                    + " AND stage_code='COMPLETED'")).isEqualTo(1);
            assertThat(scalarInt(migration, "SELECT count(*) FROM business.business_task"
                    + " WHERE id=" + taskId + " AND status='SUCCEEDED'"
                    + " AND stage_code='COMPLETED' AND settled_points=11")).isEqualTo(1);
            assertThat(scalarInt(migration, "SELECT count(*) FROM business.asset"
                    + " WHERE source_task_id=" + taskId + " AND asset_type='VIDEO'"
                    + " AND source_artifact_index=1"
                    + " AND content_type='video/mp4' AND width=832 AND height=480"
                    + " AND duration_seconds=5.000 AND size_bytes=4096"
                    + " AND sha256=repeat('a',64)")).isEqualTo(1);
            assertThat(scalarInt(migration, "SELECT count(*) FROM business.asset_product_relation r"
                    + " JOIN business.asset a ON a.id=r.asset_id"
                    + " WHERE a.source_task_id=" + taskId + " AND r.product_id=" + productId)).isEqualTo(1);
            assertThat(scalarInt(migration, "SELECT count(*) FROM business.point_reservation"
                    + " WHERE id=" + reservationId + " AND status='SETTLED'"
                    + " AND frozen_points=0 AND settled_points=11")).isEqualTo(1);
            assertThat(scalarInt(migration, "SELECT count(*) FROM business.point_account"
                    + " WHERE user_id=" + userId + " AND available_points=89 AND frozen_points=0")).isEqualTo(1);
            assertThat(scalarInt(migration, "SELECT count(*) FROM business.point_ledger"
                    + " WHERE business_id=" + taskId + " AND entry_type='TASK_SETTLE'"
                    + " AND available_delta=0 AND frozen_delta=-11")).isEqualTo(1);
            assertThat(scalarInt(migration, "SELECT count(*) FROM business.business_task_event"
                    + " WHERE task_id=" + taskId + " AND event_type='ASSET_INGESTION_COMPLETED'"
                    + " AND to_status='SUCCEEDED'")).isEqualTo(1);
            assertSqlState(migration, "23514", "UPDATE business.asset SET source_artifact_index=2"
                    + " WHERE source_task_id=" + taskId);
            assertSqlState(migration, "23514", "UPDATE business.asset_ingestion_work SET artifact_index=2"
                    + " WHERE task_id=" + taskId);
            System.out.println("DB_GATE video_result_artifacts_numeric_binding=verified");
            System.out.println("DB_GATE video_ingestion_work_claim_mapping=verified");
            System.out.println("DB_GATE video_asset_completion_atomic_path=verified");
            System.out.println("DB_GATE v033_video_asset_and_work_slot_two_rejected=verified");
        } finally {
            execute(migration, "DELETE FROM business.asset_ingestion_work WHERE task_id=" + taskId);
            execute(migration, "DELETE FROM business.asset_product_relation WHERE asset_id IN"
                    + " (SELECT id FROM business.asset WHERE source_task_id=" + taskId + ")");
            execute(migration, "DELETE FROM business.asset WHERE source_task_id=" + taskId);
            execute(migration, "DELETE FROM business.business_task_event WHERE task_id=" + taskId);
            execute(migration, "DELETE FROM business.task_execution WHERE task_id=" + taskId);
            execute(migration, "DELETE FROM business.point_ledger WHERE business_id=" + taskId);
            execute(migration, "DELETE FROM business.point_reservation WHERE task_id=" + taskId);
            execute(migration, "DELETE FROM business.business_task WHERE id=" + taskId);
            execute(migration, "DELETE FROM business.task_draft WHERE id=" + draftId);
            execute(migration, "DELETE FROM business.agent_run WHERE id='" + runId + "'");
            execute(migration, "DELETE FROM business.conversation_message WHERE id=" + messageId);
            execute(migration, "DELETE FROM business.conversation WHERE id=" + conversationId);
            execute(migration, "DELETE FROM business.product WHERE id=" + productId);
            execute(migration, "DELETE FROM business.business_space WHERE id=" + spaceId);
            execute(migration, "DELETE FROM business.point_account WHERE user_id=" + userId);
            execute(migration, "DELETE FROM business.account WHERE id=" + userId);
        }
    }

    private void verifyConversationOwnershipAndCascade(Connection connection) throws SQLException {
        long userA = 910000000000000001L;
        long userB = 910000000000000002L;
        long conversationA = 920000000000000001L;
        long conversationB = 920000000000000002L;
        long messageA = 930000000000000001L;
        long messageB = 930000000000000002L;
        UUID runA = UUID.fromString("00000000-0000-0000-0000-000000000031");

        execute(connection, """
                INSERT INTO business.account(
                    id, username, username_normalized, phone_e164, password_hash, role, created_at, updated_at
                ) VALUES
                    (910000000000000001, 'gate_a', 'gate_a', '+8610000000001',
                     '00000000000000000000000000000000', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (910000000000000002, 'gate_b', 'gate_b', '+8610000000002',
                     '00000000000000000000000000000000', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO business.conversation(id, user_id, title, created_at, updated_at)
                VALUES
                    (920000000000000001, 910000000000000001, 'gate-a', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (920000000000000002, 910000000000000002, 'gate-b', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO business.conversation_message(
                    id, conversation_id, user_id, role, content_type, content, client_message_id,
                    idempotency_key, request_fingerprint, sequence_no, status, created_at, updated_at
                ) VALUES
                    (930000000000000001, 920000000000000001, 910000000000000001, 'USER', 'TEXT',
                     '{"text":"a"}', '00000000-0000-0000-0000-000000000011',
                     '00000000-0000-0000-0000-000000000012', repeat('a', 64), 1, 'COMPLETED',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (930000000000000002, 920000000000000002, 910000000000000002, 'USER', 'TEXT',
                     '{"text":"b"}', '00000000-0000-0000-0000-000000000021',
                     '00000000-0000-0000-0000-000000000022', repeat('b', 64), 1, 'COMPLETED',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);

        assertSqlState(connection, "23503", """
                INSERT INTO business.conversation_message(
                    id, conversation_id, user_id, role, content_type, content, reply_to_message_id,
                    client_message_id, idempotency_key, request_fingerprint, sequence_no, status,
                    created_at, updated_at
                ) VALUES (
                    930000000000000003, 920000000000000001, 910000000000000001, 'USER', 'TEXT',
                    '{"text":"cross-owner"}', 930000000000000002,
                    '00000000-0000-0000-0000-000000000013',
                    '00000000-0000-0000-0000-000000000014', repeat('c', 64), 2, 'COMPLETED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """);

        execute(connection, """
                INSERT INTO business.agent_run(
                    id, conversation_id, user_id, request_message_id, status, context_snapshot,
                    context_ref_hash, context_expires_at, created_at, updated_at
                ) VALUES (
                    '00000000-0000-0000-0000-000000000031', 920000000000000001,
                    910000000000000001, 930000000000000001, 'RUNNING', '{}', repeat('d', 64),
                    CURRENT_TIMESTAMP + INTERVAL '5 minutes', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """);
        assertSqlState(connection, "23503", """
                INSERT INTO business.conversation_event(
                    id, conversation_id, user_id, agent_run_id, message_id, source_event_id,
                    event_type, sequence_no, payload, occurred_at, created_at
                ) VALUES (
                    940000000000000001, 920000000000000002, 910000000000000002,
                    '00000000-0000-0000-0000-000000000031', 930000000000000002,
                    '00000000-0000-0000-0000-000000000041', 'message.start', 1, '{}',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                INSERT INTO business.conversation_event(
                    id, conversation_id, user_id, agent_run_id, message_id, source_event_id,
                    event_type, sequence_no, payload, occurred_at, created_at
                ) VALUES (
                    940000000000000002, 920000000000000001, 910000000000000001,
                    '00000000-0000-0000-0000-000000000031', 930000000000000001,
                    '00000000-0000-0000-0000-000000000042', 'message.start', 1, '{}',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """);

        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM business.conversation WHERE id = ? AND user_id = ?")) {
            statement.setLong(1, conversationA);
            statement.setLong(2, userA);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        assertThat(scalarInt(connection, "SELECT count(*) FROM business.conversation_message"
                + " WHERE conversation_id = " + conversationA)).isZero();
        assertThat(scalarInt(connection, "SELECT count(*) FROM business.agent_run"
                + " WHERE id = '" + runA + "'")).isZero();
        assertThat(scalarInt(connection, "SELECT count(*) FROM business.conversation_event"
                + " WHERE conversation_id = " + conversationA)).isZero();
        execute(connection, "DELETE FROM business.conversation WHERE id = " + conversationB);
        execute(connection, "DELETE FROM business.account WHERE id IN (" + userA + ", " + userB + ")");
    }

    private void verifyGeneratedAssetOwnershipUniquenessAndCascade(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO business.account(
                    id, username, username_normalized, phone_e164, password_hash, role, created_at, updated_at
                ) VALUES
                    (911000000000000001, 'asset_gate_a', 'asset_gate_a', '+8611000000001',
                     repeat('a', 32), 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (911000000000000002, 'asset_gate_b', 'asset_gate_b', '+8611000000002',
                     repeat('b', 32), 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO business.business_space(
                    id,user_id,name,name_normalized,brand_name,created_at,updated_at
                ) VALUES
                    (921000000000000001,911000000000000001,'asset-space-a','asset-space-a','brand-a',
                     CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                    (921000000000000002,911000000000000002,'asset-space-b','asset-space-b','brand-b',
                     CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO business.product(
                    id,user_id,space_id,name,name_normalized,created_at,updated_at
                ) VALUES
                    (931000000000000001,911000000000000001,921000000000000001,
                     'asset-product-a','asset-product-a',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                    (931000000000000002,911000000000000002,921000000000000002,
                     'asset-product-b','asset-product-b',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO business.conversation(id,user_id,title,created_at,updated_at)
                VALUES (941000000000000001,911000000000000001,'asset-conversation',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO business.conversation_message(
                    id,conversation_id,user_id,role,content_type,content,client_message_id,
                    idempotency_key,request_fingerprint,sequence_no,status,created_at,updated_at
                ) VALUES (
                    951000000000000001,941000000000000001,911000000000000001,'USER','TEXT',
                    '{"text":"asset gate"}','10000000-0000-0000-0000-000000000001',
                    '10000000-0000-0000-0000-000000000002',repeat('c',64),1,'COMPLETED',
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                INSERT INTO business.agent_run(
                    id,conversation_id,user_id,request_message_id,status,context_snapshot,
                    context_ref_hash,context_expires_at,created_at,updated_at
                ) VALUES (
                    '10000000-0000-0000-0000-000000000003',941000000000000001,
                    911000000000000001,951000000000000001,'COMPLETED','{}',repeat('d',64),
                    CURRENT_TIMESTAMP + INTERVAL '5 minutes',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                INSERT INTO business.task_draft(
                    id,user_id,conversation_id,agent_run_id,task_type,parameter_schema_version,
                    space_id,product_id,parameters,input_snapshot,input_hash,pricing_version_id,
                    quoted_points,status,expires_at,created_at,updated_at
                ) VALUES (
                    961000000000000001,911000000000000001,941000000000000001,
                    '10000000-0000-0000-0000-000000000003','IMAGE',1,921000000000000001,
                    931000000000000001,'{}','{}',repeat('e',64),971000000000000001,7,
                    'CONFIRMED',CURRENT_TIMESTAMP + INTERVAL '5 minutes',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                INSERT INTO business.business_task(
                    id,user_id,space_id,product_id,conversation_id,draft_id,task_type,status,stage_code,
                    snapshot_schema_version,input_snapshot,input_hash,pricing_version_id,quoted_points,
                    operation_scope,idempotency_key,request_hash,queued_at,created_at,updated_at
                ) VALUES (
                    971000000000000001,911000000000000001,921000000000000001,
                    931000000000000001,941000000000000001,961000000000000001,'IMAGE','RUNNING',
                    'ASSET_INGESTION_PENDING',1,'{}',repeat('f',64),971000000000000001,7,
                    'TASK_CONFIRM','10000000-0000-0000-0000-000000000004',repeat('1',64),
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                INSERT INTO business.task_execution(
                    id,task_id,user_id,execution_no,status,consumer_name,worker_id,bundle_version,
                    execution_token_hash,lease_expires_at,stage_code,provider_code,provider_model,
                    provider_request_id,provider_task_id,submission_state,started_at,created_at,updated_at
                ) VALUES (
                    981000000000000001,971000000000000001,911000000000000001,1,'RESULT_READY',
                    'asset-gate','asset-gate-worker','asset-gate-bundle',repeat('2',64),
                    CURRENT_TIMESTAMP + INTERVAL '5 minutes','ASSET_INGESTION_PENDING','QWEN_IMAGE',
                    'qwen-image-3.0','10000000-0000-0000-0000-000000000005','provider-task-gate',
                    'CONFIRMED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                INSERT INTO business.asset_ingestion_work(
                    id,asset_id,task_id,execution_id,execution_no,user_id,space_id,product_id,
                    artifact_index,provider_code,provider_model,provider_request_id,provider_task_id,
                    provider_url,provider_url_expires_at,expected_content_type,expected_width,
                    expected_height,object_key,status,max_attempts,available_at,created_at,updated_at
                ) VALUES (
                    991000000000000001,991000000000000011,971000000000000001,981000000000000001,
                    1,911000000000000001,921000000000000001,931000000000000001,1,'QWEN_IMAGE',
                    'qwen-image-3.0','10000000-0000-0000-0000-000000000005','provider-task-gate',
                    'https://example.invalid/generated.png?signature=not-logged',
                    CURRENT_TIMESTAMP + INTERVAL '5 minutes','image/png',24,18,
                    'zhixiaohe/test/users/911000000000000001/assets/991000000000000011/original.png',
                    'PENDING',3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        assertSqlState(connection, "23514", """
                UPDATE business.asset_ingestion_work
                   SET provider_url=NULL, updated_at=CURRENT_TIMESTAMP
                 WHERE id=991000000000000001
                """);
        assertSqlState(connection, "23514", """
                UPDATE business.asset_ingestion_work
                   SET status='SUCCEEDED', provider_url=NULL,
                       completed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                 WHERE id=991000000000000001
                """);
        assertSqlState(connection, "23514", """
                UPDATE business.asset_ingestion_work
                   SET status='SUCCEEDED', completed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                 WHERE id=991000000000000001
                """);

        execute(connection, """
                UPDATE business.asset_ingestion_work
                   SET status='PROCESSING', attempt=1,
                       claim_token='20000000-0000-0000-0000-000000000001',
                       lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second',
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=991000000000000001
                """);
        execute(connection, """
                UPDATE business.asset_ingestion_work
                   SET attempt=2, claim_token='20000000-0000-0000-0000-000000000002',
                       lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=991000000000000001 AND status='PROCESSING'
                   AND lease_expires_at<CURRENT_TIMESTAMP
                """);

        String staleFence = """
                 WHERE id=991000000000000001 AND status='PROCESSING' AND attempt=1
                   AND claim_token='20000000-0000-0000-0000-000000000001'
                """;
        assertThat(updateCount(connection, """
                UPDATE business.asset_ingestion_work
                   SET status='SUCCEEDED', provider_url=NULL, provider_url_expires_at=NULL,
                       lease_expires_at=NULL, claim_token=NULL, completed_at=CURRENT_TIMESTAMP
                """ + staleFence)).isZero();
        assertThat(updateCount(connection, """
                UPDATE business.asset_ingestion_work
                   SET status='RETRY_WAIT', lease_expires_at=NULL, claim_token=NULL
                """ + staleFence)).isZero();
        assertThat(updateCount(connection, """
                UPDATE business.asset_ingestion_work
                   SET status='FAILED', provider_url=NULL, provider_url_expires_at=NULL,
                       lease_expires_at=NULL, claim_token=NULL, completed_at=CURRENT_TIMESTAMP
                """ + staleFence)).isZero();
        assertThat(scalarInt(connection, """
                SELECT count(*) FROM business.asset_ingestion_work
                 WHERE id=991000000000000001 AND status='PROCESSING' AND attempt=2
                   AND claim_token='20000000-0000-0000-0000-000000000002'
                   AND provider_url IS NOT NULL AND provider_url_expires_at IS NOT NULL
                """)).isEqualTo(1);

        assertThat(updateCount(connection, """
                UPDATE business.asset_ingestion_work
                   SET status='RETRY_WAIT', available_at=CURRENT_TIMESTAMP,
                       lease_expires_at=NULL, claim_token=NULL, last_error_code='OSS_UPLOAD_FAILED',
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=991000000000000001 AND status='PROCESSING' AND attempt=2
                   AND claim_token='20000000-0000-0000-0000-000000000002'
                   AND lease_expires_at>=CURRENT_TIMESTAMP
                """)).isEqualTo(1);
        assertThat(scalarInt(connection, """
                SELECT count(*) FROM business.asset_ingestion_work
                 WHERE id=991000000000000001 AND status='RETRY_WAIT'
                   AND provider_url IS NOT NULL AND provider_url_expires_at IS NOT NULL
                   AND claim_token IS NULL AND lease_expires_at IS NULL
                """)).isEqualTo(1);

        execute(connection, """
                UPDATE business.asset_ingestion_work
                   SET status='PROCESSING', attempt=3,
                       claim_token='20000000-0000-0000-0000-000000000003',
                       lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=991000000000000001
                """);
        assertThat(updateCount(connection, """
                UPDATE business.asset_ingestion_work
                   SET status='SUCCEEDED', provider_url=NULL, provider_url_expires_at=NULL,
                       lease_expires_at=NULL, claim_token=NULL, completed_at=CURRENT_TIMESTAMP,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=991000000000000001 AND status='PROCESSING' AND attempt=3
                   AND claim_token='20000000-0000-0000-0000-000000000003'
                   AND lease_expires_at>=CURRENT_TIMESTAMP
                """)).isEqualTo(1);
        assertThat(scalarInt(connection, """
                SELECT count(*) FROM business.asset_ingestion_work
                 WHERE id=991000000000000001 AND status='SUCCEEDED'
                   AND provider_url IS NULL AND provider_url_expires_at IS NULL
                """)).isEqualTo(1);

        execute(connection, """
                UPDATE business.asset_ingestion_work
                   SET status='PENDING', attempt=0, completed_at=NULL, last_error_code=NULL,
                       provider_url='https://example.invalid/generated.png',
                       provider_url_expires_at=CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                       available_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                 WHERE id=991000000000000001
                """);
        execute(connection, """
                UPDATE business.asset_ingestion_work
                   SET status='PROCESSING', attempt=1,
                       claim_token='20000000-0000-0000-0000-000000000004',
                       lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=991000000000000001
                """);
        assertThat(updateCount(connection, """
                UPDATE business.asset_ingestion_work
                   SET status='FAILED', provider_url=NULL, provider_url_expires_at=NULL,
                       lease_expires_at=NULL, claim_token=NULL, last_error_code='CONTENT_REJECTED',
                       completed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                 WHERE id=991000000000000001 AND status='PROCESSING' AND attempt=1
                   AND claim_token='20000000-0000-0000-0000-000000000004'
                   AND lease_expires_at>=CURRENT_TIMESTAMP
                """)).isEqualTo(1);
        assertThat(scalarInt(connection, """
                SELECT count(*) FROM business.asset_ingestion_work
                 WHERE id=991000000000000001 AND status='FAILED'
                   AND provider_url IS NULL AND provider_url_expires_at IS NULL
                """)).isEqualTo(1);
        System.out.println("DB_GATE asset_ingestion_fencing_and_url_lifecycle=verified");

        execute(connection, """
                INSERT INTO business.asset(
                    id,user_id,space_id,primary_product_id,asset_type,source_type,source_task_id,source_artifact_index,name,
                    object_key,content_type,size_bytes,sha256,width,height,status,safety_status,
                    created_at,updated_at
                ) VALUES (
                    991000000000000011,911000000000000001,921000000000000001,931000000000000001,
                    'IMAGE','GENERATED',971000000000000001,1,'gate-image.png',
                    'zhixiaohe/test/users/911000000000000001/assets/991000000000000011/original.png',
                    'image/png',88,repeat('3',64),24,18,'READY','TEST_BYPASS',
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                INSERT INTO business.asset_product_relation(asset_id,product_id,user_id,space_id,created_at)
                VALUES (991000000000000011,931000000000000001,911000000000000001,
                        921000000000000001,CURRENT_TIMESTAMP)
                """);

        assertSqlState(connection, "23503", """
                INSERT INTO business.asset_product_relation(asset_id,product_id,user_id,space_id,created_at)
                VALUES (991000000000000011,931000000000000002,911000000000000001,
                        921000000000000001,CURRENT_TIMESTAMP)
                """);
        assertSqlState(connection, "23505", """
                INSERT INTO business.asset(
                    id,user_id,space_id,asset_type,source_type,source_task_id,source_artifact_index,name,object_key,
                    content_type,size_bytes,sha256,width,height,status,safety_status,created_at,updated_at
                ) VALUES (
                    991000000000000012,911000000000000001,921000000000000001,'IMAGE','GENERATED',
                    971000000000000001,1,'duplicate.png',
                    'zhixiaohe/test/users/911000000000000001/assets/991000000000000012/original.png',
                    'image/png',88,repeat('4',64),24,18,'READY','TEST_BYPASS',
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                )
                """);

        // A different permanent candidate of the same IMAGE task is legal; the first slot
        // remains unique. Uploads have no task or slot and may coexist with each other.
        execute(connection, """
                INSERT INTO business.asset(
                    id,user_id,space_id,asset_type,source_type,source_task_id,source_artifact_index,name,
                    object_key,content_type,size_bytes,sha256,width,height,status,safety_status,created_at,updated_at
                ) VALUES
                    (991000000000000012,911000000000000001,921000000000000001,'IMAGE','GENERATED',
                     971000000000000001,2,'candidate-two.png',
                     'zhixiaohe/test/users/911000000000000001/assets/991000000000000012/original.png',
                     'image/png',89,repeat('4',64),24,18,'READY','TEST_BYPASS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                    (991000000000000013,911000000000000001,921000000000000001,'IMAGE','UPLOADED',
                     NULL,NULL,'upload-one.png',
                     'zhixiaohe/test/users/911000000000000001/assets/991000000000000013/original.png',
                     'image/png',89,repeat('5',64),24,18,'READY','TEST_BYPASS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                    (991000000000000014,911000000000000001,921000000000000001,'IMAGE','UPLOADED',
                     NULL,NULL,'upload-two.png',
                     'zhixiaohe/test/users/911000000000000001/assets/991000000000000014/original.png',
                     'image/png',90,repeat('6',64),24,18,'READY','TEST_BYPASS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        assertThat(scalarInt(connection, """
                SELECT count(*) FROM business.asset
                WHERE source_task_id=971000000000000001 AND source_artifact_index IN (1,2)
                """)).isEqualTo(2);
        assertThat(scalarInt(connection, """
                SELECT count(*) FROM business.asset WHERE id IN (991000000000000013,991000000000000014)
                  AND source_task_id IS NULL AND source_artifact_index IS NULL
                """)).isEqualTo(2);
        assertSqlState(connection, "23505", "UPDATE business.asset SET source_artifact_index=1"
                + " WHERE id=991000000000000012");
        assertSqlState(connection, "23514", "UPDATE business.asset SET source_artifact_index=5"
                + " WHERE id=991000000000000012");
        assertSqlState(connection, "23514", "UPDATE business.asset SET source_artifact_index=0"
                + " WHERE id=991000000000000012");
        assertThat(updateCount(connection, "UPDATE business.asset SET source_artifact_index=4"
                + " WHERE id=991000000000000012")).isEqualTo(1);
        execute(connection, "UPDATE business.asset SET source_artifact_index=2 WHERE id=991000000000000012");
        assertSqlState(connection, "23514", "UPDATE business.asset SET source_artifact_index=NULL"
                + " WHERE id=991000000000000012");
        assertSqlState(connection, "23514", "UPDATE business.asset SET source_artifact_index=1"
                + " WHERE id=991000000000000013");

        execute(connection, """
                INSERT INTO business.asset_ingestion_work(
                    id,asset_id,task_id,execution_id,execution_no,user_id,space_id,product_id,
                    artifact_index,provider_code,provider_model,provider_request_id,provider_task_id,
                    expected_content_type,expected_width,expected_height,object_key,status,
                    max_attempts,available_at,completed_at,created_at,updated_at
                ) SELECT
                    991000000000000002,991000000000000012,task_id,execution_id,execution_no,user_id,space_id,product_id,
                    2,provider_code,provider_model,provider_request_id,provider_task_id,
                    expected_content_type,expected_width,expected_height,
                    'zhixiaohe/test/users/911000000000000001/assets/991000000000000012/original.png',
                    'FAILED',max_attempts,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                  FROM business.asset_ingestion_work WHERE id=991000000000000001
                """);
        assertThat(scalarInt(connection, """
                SELECT count(*) FROM business.asset_ingestion_work
                WHERE task_id=971000000000000001 AND artifact_index IN (1,2)
                """)).isEqualTo(2);
        assertSqlState(connection, "23505", "UPDATE business.asset_ingestion_work SET artifact_index=1"
                + " WHERE id=991000000000000002");
        assertSqlState(connection, "23514", "UPDATE business.asset_ingestion_work SET artifact_index=5"
                + " WHERE id=991000000000000002");
        assertSqlState(connection, "23514", "UPDATE business.asset_ingestion_work SET artifact_index=0"
                + " WHERE id=991000000000000002");
        assertThat(updateCount(connection, "UPDATE business.asset_ingestion_work SET artifact_index=4"
                + " WHERE id=991000000000000002")).isEqualTo(1);
        execute(connection, "UPDATE business.asset_ingestion_work SET artifact_index=2 WHERE id=991000000000000002");
        System.out.println("DB_GATE v033_image_candidates_unique_slots_and_null_source=verified");
        // The mask-edit provider has honestly unknown output geometry/type until downloaded.
        execute(connection, """
                UPDATE business.asset_ingestion_work
                SET provider_code='WAN_IMAGE_EDIT',provider_model='wanx2.1-imageedit',
                    expected_content_type=NULL,expected_width=NULL,expected_height=NULL
                WHERE id=991000000000000002
                """);
        assertSqlState(connection, "23514", "UPDATE business.asset_ingestion_work SET expected_content_type='image/png'"
                + " WHERE id=991000000000000002");
        assertSqlState(connection, "23514", "UPDATE business.asset_ingestion_work SET expected_width=512"
                + " WHERE id=991000000000000002");
        assertSqlState(connection, "23514", "UPDATE business.asset_ingestion_work SET provider_model='qwen-image-3.0'"
                + " WHERE id=991000000000000002");
        assertSqlState(connection, "23514", "UPDATE business.asset_ingestion_work SET artifact_index=5"
                + " WHERE id=991000000000000002");
        assertSqlState(connection, "23514", "UPDATE business.asset_ingestion_work SET expected_content_type=NULL"
                + " WHERE id=991000000000000001");
        execute(connection, """
                UPDATE business.asset_ingestion_work
                SET provider_code='QWEN_IMAGE',provider_model='qwen-image-3.0',
                    expected_content_type='image/png',expected_width=24,expected_height=18
                WHERE id=991000000000000002
                """);
        System.out.println("DB_GATE v034_region_edit_unknown_metadata_and_legacy_strictness=verified");

        execute(connection, "DELETE FROM business.asset WHERE id=991000000000000011");
        assertThat(scalarInt(connection, "SELECT count(*) FROM business.asset_product_relation"
                + " WHERE asset_id=991000000000000011")).isZero();
        execute(connection, "DELETE FROM business.asset WHERE id IN"
                + " (991000000000000012,991000000000000013,991000000000000014)");
        execute(connection, "DELETE FROM business.asset_ingestion_work WHERE id IN"
                + " (991000000000000001,991000000000000002)");
        execute(connection, "DELETE FROM business.task_execution WHERE id=981000000000000001");
        execute(connection, "DELETE FROM business.business_task WHERE id=971000000000000001");
        execute(connection, "DELETE FROM business.task_draft WHERE id=961000000000000001");
        execute(connection, "DELETE FROM business.conversation WHERE id=941000000000000001");
        execute(connection, "DELETE FROM business.product WHERE id IN (931000000000000001,931000000000000002)");
        execute(connection, "DELETE FROM business.business_space WHERE id IN (921000000000000001,921000000000000002)");
        execute(connection, "DELETE FROM business.account WHERE id IN (911000000000000001,911000000000000002)");
    }

    private void verifyGeoOwnershipVersioningAndCascade(Connection connection) throws SQLException {
        long userA = 913000000000000001L;
        long userB = 913000000000000002L;
        long topicId = 943000000000000001L;
        try {
            execute(connection, """
                    INSERT INTO business.account(
                        id, username, username_normalized, phone_e164, password_hash, role,
                        created_at, updated_at
                    ) VALUES
                        (913000000000000001, 'geo_gate_a', 'geo_gate_a', '+8611300000001',
                         repeat('a', 32), 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        (913000000000000002, 'geo_gate_b', 'geo_gate_b', '+8611300000002',
                         repeat('b', 32), 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            execute(connection, """
                    INSERT INTO business.business_space(
                        id, user_id, name, name_normalized, brand_name, created_at, updated_at
                    ) VALUES
                        (923000000000000001, 913000000000000001, 'geo-space-a', 'geo-space-a',
                         'geo-brand-a', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        (923000000000000002, 913000000000000002, 'geo-space-b', 'geo-space-b',
                         'geo-brand-b', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            execute(connection, """
                    INSERT INTO business.product(
                        id, user_id, space_id, name, name_normalized, created_at, updated_at
                    ) VALUES
                        (933000000000000001, 913000000000000001, 923000000000000001,
                         'geo-product-a', 'geo-product-a', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        (933000000000000002, 913000000000000002, 923000000000000002,
                         'geo-product-b', 'geo-product-b', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            execute(connection, """
                    INSERT INTO business.competitor(
                        id, user_id, space_id, name, name_normalized, competitor_type, status,
                        created_at, updated_at
                    ) VALUES
                        (953000000000000001, 913000000000000001, 923000000000000001,
                         'geo-competitor-a1', 'geo-competitor-a1', 'DIRECT', 'ACTIVE',
                         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        (953000000000000002, 913000000000000001, 923000000000000001,
                         'geo-competitor-a2', 'geo-competitor-a2', 'SUBSTITUTE', 'ACTIVE',
                         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        (953000000000000003, 913000000000000002, 923000000000000002,
                         'geo-competitor-b', 'geo-competitor-b', 'INDIRECT', 'ACTIVE',
                         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            execute(connection, """
                    INSERT INTO business.geo_topic(
                        id, user_id, space_id, product_id, name, name_normalized, region,
                        version, created_at, updated_at
                    ) VALUES (
                        943000000000000001, 913000000000000001, 923000000000000001,
                        933000000000000001, 'geo-topic-a', 'geo-topic-a', 'CN', 1,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """);

            assertSqlState(connection, "23503", """
                    INSERT INTO business.geo_topic(
                        id, user_id, space_id, product_id, name, name_normalized,
                        version, created_at, updated_at
                    ) VALUES (
                        943000000000000002, 913000000000000001, 923000000000000001,
                        933000000000000002, 'cross-product', 'cross-product', 1,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """);
            execute(connection, """
                    INSERT INTO business.geo_keyword(
                        id, user_id, space_id, topic_id, sequence_no, keyword_type,
                        keyword_text, text_normalized, created_at, updated_at
                    ) VALUES
                        (963000000000000001, 913000000000000001, 923000000000000001,
                         943000000000000001, 1, 'CORE', '核心词', '核心词',
                         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        (963000000000000002, 913000000000000001, 923000000000000001,
                         943000000000000001, 2, 'BRAND', '品牌词', '品牌词',
                         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            assertSqlState(connection, "23505", """
                    INSERT INTO business.geo_keyword(
                        id, user_id, space_id, topic_id, sequence_no, keyword_type,
                        keyword_text, text_normalized, created_at, updated_at
                    ) VALUES (
                        963000000000000003, 913000000000000001, 923000000000000001,
                        943000000000000001, 3, 'SCENE', ' 核心词 ', '核心词',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """);
            execute(connection, """
                    INSERT INTO business.geo_question(
                        id, user_id, space_id, topic_id, sequence_no, category,
                        question_text, text_normalized, source, enabled, created_at, updated_at
                    ) VALUES
                        (973000000000000001, 913000000000000001, 923000000000000001,
                         943000000000000001, 1, 'BRAND_DISCOVERY', '推荐哪个品牌？',
                         '推荐哪个品牌？', 'MANUAL', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        (973000000000000002, 913000000000000001, 923000000000000001,
                         943000000000000001, 2, 'BRAND_DISCOVERY', '哪个商品更适合？',
                         '哪个商品更适合？', 'MANUAL', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            execute(connection, """
                    INSERT INTO business.geo_competitor_set(
                        id, user_id, space_id, topic_id, version_no, created_at
                    ) VALUES
                        (983000000000000001, 913000000000000001, 923000000000000001,
                         943000000000000001, 1, CURRENT_TIMESTAMP),
                        (983000000000000002, 913000000000000001, 923000000000000001,
                         943000000000000001, 2, CURRENT_TIMESTAMP)
                    """);
            execute(connection, """
                    INSERT INTO business.geo_competitor_set_member(
                        id, user_id, space_id, topic_id, competitor_set_id, competitor_id,
                        sequence_no, competitor_name_snapshot, competitor_type_snapshot, created_at
                    ) VALUES
                        (993000000000000001, 913000000000000001, 923000000000000001,
                         943000000000000001, 983000000000000001, 953000000000000001, 1,
                         'geo-competitor-a1', 'DIRECT', CURRENT_TIMESTAMP),
                        (993000000000000002, 913000000000000001, 923000000000000001,
                         943000000000000001, 983000000000000002, 953000000000000002, 1,
                         'geo-competitor-a2', 'SUBSTITUTE', CURRENT_TIMESTAMP)
                    """);
            assertSqlState(connection, "23503", """
                    INSERT INTO business.geo_competitor_set_member(
                        id, user_id, space_id, topic_id, competitor_set_id, competitor_id,
                        sequence_no, competitor_name_snapshot, competitor_type_snapshot, created_at
                    ) VALUES (
                        993000000000000003, 913000000000000001, 923000000000000001,
                        943000000000000001, 983000000000000002, 953000000000000003, 2,
                        'geo-competitor-b', 'INDIRECT', CURRENT_TIMESTAMP
                    )
                    """);

            assertThat(scalarInt(connection, "SELECT count(*) FROM business.geo_competitor_set"
                    + " WHERE topic_id=" + topicId)).isEqualTo(2);
            assertThat(scalarInt(connection, "SELECT count(*) FROM business.geo_competitor_set_member"
                    + " WHERE competitor_set_id=983000000000000001"
                    + " AND competitor_id=953000000000000001"
                    + " AND competitor_name_snapshot='geo-competitor-a1'"
                    + " AND competitor_type_snapshot='DIRECT'")).isEqualTo(1);

            execute(connection, "DELETE FROM business.geo_topic WHERE id=" + topicId
                    + " AND user_id=" + userA);
            for (String childTable : List.of(
                    "geo_keyword", "geo_question", "geo_competitor_set",
                    "geo_competitor_set_member")) {
                assertThat(scalarInt(connection, "SELECT count(*) FROM business." + childTable
                        + " WHERE topic_id=" + topicId)).as(childTable).isZero();
            }
            System.out.println("DB_GATE geo_ownership_versioning_and_cascade=verified");
        } finally {
            execute(connection, "DELETE FROM business.geo_topic WHERE id=" + topicId);
            execute(connection, "DELETE FROM business.competitor WHERE id IN "
                    + "(953000000000000001,953000000000000002,953000000000000003)");
            execute(connection, "DELETE FROM business.product WHERE id IN "
                    + "(933000000000000001,933000000000000002)");
            execute(connection, "DELETE FROM business.business_space WHERE id IN "
                    + "(923000000000000001,923000000000000002)");
            execute(connection, "DELETE FROM business.account WHERE id IN (" + userA + "," + userB + ")");
        }
    }

    private void assertSqlState(Connection connection, String expectedSqlState, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            throw new AssertionError("Expected SQLSTATE " + expectedSqlState);
        } catch (SQLException exception) {
            assertThat(exception.getSQLState()).isEqualTo(expectedSqlState);
        }
    }

    private void createPermissionProbe(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE business.gate_permission_target (id bigint PRIMARY KEY, value text NOT NULL)");
    }

    private PermissionResult verifyRuntimePermissions(Connection connection) throws SQLException {
        boolean insertAllowed;
        boolean selectAllowed;
        boolean updateAllowed;
        boolean deleteAllowed;
        boolean paidTaskDmlAllowed = verifyPaidTaskRuntimeDml(connection);

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO business.gate_permission_target (id, value) VALUES (?, ?)")) {
            statement.setLong(1, 1L);
            statement.setString(2, "gate");
            insertAllowed = statement.executeUpdate() == 1;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM business.gate_permission_target WHERE id = ?")) {
            statement.setLong(1, 1L);
            try (ResultSet result = statement.executeQuery()) {
                selectAllowed = result.next() && "gate".equals(result.getString(1));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE business.gate_permission_target SET value = ? WHERE id = ?")) {
            statement.setString(1, "updated");
            statement.setLong(2, 1L);
            updateAllowed = statement.executeUpdate() == 1;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM business.gate_permission_target WHERE id = ?")) {
            statement.setLong(1, 1L);
            deleteAllowed = statement.executeUpdate() == 1;
        }

        boolean createDenied = isInsufficientPrivilege(connection,
                "CREATE TABLE business.gate_runtime_create_probe (id bigint PRIMARY KEY)");
        boolean alterDenied = isInsufficientPrivilege(connection,
                "ALTER TABLE business.gate_permission_target ADD COLUMN forbidden_column text");
        boolean dropDenied = isInsufficientPrivilege(connection,
                "DROP TABLE business.gate_permission_target");
        boolean flywayHistorySelectDenied = isInsufficientPrivilege(connection,
                "SELECT installed_rank FROM business.flyway_schema_history LIMIT 1");
        boolean flywayHistoryInsertDenied = isInsufficientPrivilege(connection,
                "INSERT INTO business.flyway_schema_history DEFAULT VALUES");
        boolean flywayHistoryUpdateDenied = isInsufficientPrivilege(connection,
                "UPDATE business.flyway_schema_history SET description = description WHERE false");
        boolean flywayHistoryDeleteDenied = isInsufficientPrivilege(connection,
                "DELETE FROM business.flyway_schema_history WHERE false");

        return new PermissionResult(
                selectAllowed,
                insertAllowed,
                updateAllowed,
                deleteAllowed,
                paidTaskDmlAllowed,
                createDenied,
                alterDenied,
                dropDenied,
                flywayHistorySelectDenied,
                flywayHistoryInsertDenied,
                flywayHistoryUpdateDenied,
                flywayHistoryDeleteDenied
        );
    }

    private boolean verifyPaidTaskRuntimeDml(Connection connection) throws SQLException {
        long userId = 910000000000000099L;
        execute(connection, """
                INSERT INTO business.account(
                    id, username, username_normalized, phone_e164, password_hash, role, created_at, updated_at
                ) VALUES (
                    910000000000000099, 'gate_runtime_points', 'gate_runtime_points', '+8610000000099',
                    '00000000000000000000000000000000', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                INSERT INTO business.point_account(
                    user_id, available_points, frozen_points, version, created_at, updated_at
                ) VALUES (910000000000000099, 20, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        boolean selectAllowed = scalarInt(connection,
                "SELECT available_points FROM business.point_account WHERE user_id = " + userId) == 20;
        boolean updateAllowed;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE business.point_account
                   SET available_points = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE user_id = ?
                """)) {
            statement.setInt(1, 19);
            statement.setLong(2, userId);
            updateAllowed = statement.executeUpdate() == 1;
        }
        boolean deleteAllowed;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM business.point_account WHERE user_id = ?")) {
            statement.setLong(1, userId);
            deleteAllowed = statement.executeUpdate() == 1;
        }
        execute(connection, "DELETE FROM business.account WHERE id = " + userId);
        return selectAllowed && updateAllowed && deleteAllowed;
    }

    private boolean isInsufficientPrivilege(Connection connection, String sql) throws SQLException {
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

    private boolean hasRoleFlag(Connection connection, String role, String column) throws SQLException {
        if (!Set.of("rolcanlogin", "rolsuper", "rolcreatedb", "rolcreaterole").contains(column)) {
            throw new IllegalArgumentException("Unsupported role flag");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private boolean hasPrivilege(Connection connection, String role, String schema, String privilege)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT has_schema_privilege(?, ?, ?)")) {
            statement.setString(1, role);
            statement.setString(2, schema);
            statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private boolean hasTablePrivilege(Connection connection, String role, String table, String privilege)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT has_table_privilege(?, ?, ?)")) {
            statement.setString(1, role);
            statement.setString(2, table);
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

    private int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private int updateCount(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the database Gate");
        }
        return value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record PermissionResult(
            boolean selectAllowed,
            boolean insertAllowed,
            boolean updateAllowed,
            boolean deleteAllowed,
            boolean paidTaskDmlAllowed,
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
