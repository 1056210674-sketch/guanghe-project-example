package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.catalog.CatalogJsonCodec;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.competitor.CompetitorMapper;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.geo.GeoMapper;
import com.guanghe.backend.geo.GeoTaskMapper;
import com.guanghe.backend.geo.GeoTaskService;
import com.guanghe.backend.integration.internal.client.PythonChatRunEvent;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskControlService;
import com.guanghe.backend.task.TaskDtos;
import com.guanghe.backend.task.TaskRecords;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static com.guanghe.backend.agentplatform.MonitorExecutionDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual V047 -> V048, MyBatis, wallet and outbox transactions; no model, worker or shared database. */
@EnabledIfEnvironmentVariable(named="DATABASE_GATE_ENABLED", matches="true")
class MonitorExecutionDatabaseGateIntegrationTests {
    @Test void singleMonitorUsesOwnedDurableAdmissionFreshAccountingAndOneExistingTask() throws Exception {
        String database=required("GATE_EXPECTED_DATABASE");
        assertThat(database).matches("^zhixiaohe_gate_video_[a-f0-9]{10}$");
        String suffix=database.substring("zhixiaohe_gate_video_".length());
        String migrationUser=required("GATE_MIGRATION_DB_USERNAME"), runtimeUser=required("GATE_RUNTIME_DB_USERNAME");
        assertThat(migrationUser).isEqualTo("gate_migration_"+suffix);
        assertThat(runtimeUser).isEqualTo("gate_runtime_"+suffix);
        String migrationUrl=required("GATE_MIGRATION_DB_URL"), runtimeUrl=required("GATE_RUNTIME_DB_URL");
        String migrationPassword=required("GATE_MIGRATION_DB_PASSWORD"), runtimePassword=required("GATE_RUNTIME_DB_PASSWORD");
        // Check actual names BEFORE any migration or fixture write, including runtime credentials.
        try(var migration=DriverManager.getConnection(migrationUrl,migrationUser,migrationPassword);
                var runtime=DriverManager.getConnection(runtimeUrl,runtimeUser,runtimePassword)) {
            assertThat(scalar(migration,"SELECT current_database()")).isEqualTo(database);
            assertThat(scalar(runtime,"SELECT current_database()")).isEqualTo(database);
        }
        flyway(migrationUrl,migrationUser,migrationPassword,"047").migrate();
        String legacy;
        try(var connection=DriverManager.getConnection(migrationUrl,migrationUser,migrationPassword)) {
            execute(connection,"INSERT INTO business.account(id,username,username_normalized,password_hash,role,status,created_at,updated_at) VALUES (94801,'monitor_legacy','monitor_legacy',repeat('a',32),'USER','ACTIVE',now(),now())");
            legacy=scalar(connection,"SELECT to_jsonb(a)::text FROM business.account a WHERE id=94801");
        }
        var upgrade=flyway(migrationUrl,migrationUser,migrationPassword,"048");
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate(); assertThat(upgrade.migrate().migrationsExecuted).isZero();
        try(var connection=DriverManager.getConnection(migrationUrl,migrationUser,migrationPassword)) {
            assertThat(scalar(connection,"SELECT to_jsonb(a)::text FROM business.account a WHERE id=94801")).isEqualTo(legacy);
            execute(connection,"GRANT USAGE ON SCHEMA business TO \""+runtimeUser+"\"; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA business TO \""+runtimeUser+"\"");
        }
        var source=new PGSimpleDataSource(); source.setUrl(runtimeUrl);source.setUser(runtimeUser);source.setPassword(runtimePassword);
        source.setApplicationName("monitor-execution-gate-"+suffix);source.setConnectTimeout(5);source.setSocketTimeout(30);
        source.setOptions("-c statement_timeout=20000 -c lock_timeout=15000");
        var h=new Harness(source);
        var normal=h.scenario(500,8,12);
        assertThat(h.monitors.budget(AgentPlatformServiceTests.user(normal.user)).configured()).isTrue();
        assertThat(h.monitors.grants(AgentPlatformServiceTests.user(normal.user),normal.conversation)).hasSize(1);
        assertThat(h.conversations.hasMonitorExecutionRecords(normal.conversation,normal.user)).isTrue();
        assertThat(h.conversations.hasMonitorExecutionRecords(normal.conversation,94801)).isFalse();
        assertThat(h.conversations.hasMonitorExecutionRecords(normal.conversation+1,normal.user)).isFalse();
        assertThat(h.mapper.grant(Long.parseLong(normal.grant.id()),94801,normal.conversation)).isEmpty();
        assertThatThrownBy(()->h.monitors.preview(AgentPlatformServiceTests.user(94801),normal.conversation,normal.range.input())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->h.monitors.putBudget(AgentPlatformServiceTests.user(normal.user),new BudgetPolicyPut(0,true,8,12))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->h.monitors.createGrant(AgentPlatformServiceTests.user(normal.user),normal.conversation,new MonitorAuthorizationCreate(0,normal.range,h.now().plusDays(2)))).isInstanceOf(BusinessException.class);
        try(var connection=source.getConnection()) {
            reject(connection,"CREATE TABLE business.monitor_runtime_ddl_forbidden(id bigint)","42501");
            reject(connection,"UPDATE business.agent_execution_budget_policy SET effective_shanghai_date=effective_shanghai_date+1 WHERE user_id="+normal.user,"P0001");
            reject(connection,"UPDATE business.geo_monitor_execution_grant SET installation_id=installation_id+1 WHERE id="+normal.grant.id(),"P0001");
            reject(connection,"INSERT INTO business.geo_monitor_execution_grant SELECT (jsonb_populate_record(NULL::business.geo_monitor_execution_grant,to_jsonb(g)||'{\"id\":94899,\"user_id\":94801}'::jsonb)).* FROM business.geo_monitor_execution_grant g WHERE id="+normal.grant.id(),"23503");
        }
        var first=h.propose(normal,"RUNNING");
        assertThat(h.tasks.admitNextMonitorAction()).isEmpty();
        h.assertNoTask(normal);
        h.finish(first,"COMPLETED");
        assertThat(h.tasks.admitNextMonitorAction()).isPresent();
        var admitted=h.monitors.admission(AgentPlatformServiceTests.user(normal.user),normal.conversation,first.run.id());
        assertThat(admitted.status()).isEqualTo("ADMITTED");assertThat(admitted.executionSource()).isEqualTo("EXECUTION_PREAUTH");
        h.assertSingleAcceptance(first,admitted,6);
        assertThat(h.tasks.admitNextMonitorAction()).isEmpty();
        h.assertSingleAcceptance(first,admitted,6);
        try(var connection=source.getConnection()) {
            reject(connection,"UPDATE business.geo_monitor_execution_admission SET intent_hash=repeat('d',64) WHERE run_id='"+first.run.id()+"'","P0001");
            reject(connection,"UPDATE business.geo_monitor_execution_admission SET updated_at=updated_at+interval '1 second' WHERE run_id='"+first.run.id()+"'","P0001");
            reject(connection,"UPDATE business.task_draft SET confirmed_at=now() WHERE id="+first.draft.id(),"23514");
        }
        var second=h.propose(normal,"COMPLETED");h.tasks.admitNextMonitorAction().orElseThrow();
        assertThat(h.monitors.admission(AgentPlatformServiceTests.user(normal.user),normal.conversation,second.run.id()).status()).isEqualTo("ADMITTED");
        var third=h.propose(normal,"COMPLETED");h.tasks.admitNextMonitorAction().orElseThrow();h.assertRefused(third,"DAY_LIMIT");
        try(var connection=source.getConnection()) {
            assertThat(scalar(connection,"SELECT frozen_points FROM business.point_account WHERE user_id="+normal.user)).isEqualTo("12");
            assertThat(scalar(connection,"SELECT count(*) FROM business.business_task WHERE user_id="+normal.user)).isEqualTo("2");
            reject(connection,"INSERT INTO business.geo_monitor_execution_admission SELECT (jsonb_populate_record(NULL::business.geo_monitor_execution_admission,to_jsonb(a)||'{\"id\":94898,\"status\":\"WAIT_INPUT\",\"reason_code\":null}'::jsonb)).* FROM business.geo_monitor_execution_admission a WHERE run_id='"+third.run.id()+"'","23514");
        }
        var stopped=h.scenario(500,8,100);var stoppedProposal=h.propose(stopped,"STOPPED");
        h.tasks.admitNextMonitorAction().orElseThrow();h.assertRefused(stoppedProposal,"RUN_STOPPED");h.assertNoTask(stopped);
        var failed=h.scenario(500,8,100);var failedProposal=h.propose(failed,"FAILED");
        h.tasks.admitNextMonitorAction().orElseThrow();h.assertRefused(failedProposal,"RUN_FAILED");h.assertNoTask(failed);
        var noBalance=h.scenario(0,8,100);var noBalanceProposal=h.propose(noBalance,"COMPLETED");
        h.tasks.admitNextMonitorAction().orElseThrow();h.assertRefused(noBalanceProposal,"INSUFFICIENT_POINTS");h.assertNoTask(noBalance);
        var revoked=h.scenario(500,8,100);var revokedProposal=h.propose(revoked,"COMPLETED");
        var revokedGrant=h.monitors.revokeGrant(AgentPlatformServiceTests.user(revoked.user),revoked.conversation,Long.parseLong(revoked.grant.id()),new VersionRequest(1));
        assertThat(revokedGrant.status()).isEqualTo("REVOKED");h.tasks.admitNextMonitorAction().orElseThrow();h.assertRefused(revokedProposal,"GRANT_REVOKED");h.assertNoTask(revoked);
        assertThat(h.conversations.hasMonitorExecutionRecords(revoked.conversation,revoked.user)).isTrue();
        try(var connection=source.getConnection()) { reject(connection,"UPDATE business.geo_monitor_execution_grant SET status='ACTIVE',revoked_at=NULL WHERE id="+revoked.grant.id(),"P0001"); }
        // New grants cannot replace the stable account budget or make a used day fresh.
        var replacement=h.monitors.createGrant(AgentPlatformServiceTests.user(revoked.user),revoked.conversation,new MonitorAuthorizationCreate(0,revoked.range,h.now().plusDays(2)));
        assertThat(replacement.id()).isNotEqualTo(revoked.grant.id());
        assertThat(h.monitors.budget(AgentPlatformServiceTests.user(revoked.user)).version()).isEqualTo(1);
        var changed=h.scenario(500,8,100);var changedProposal=h.propose(changed,"COMPLETED");
        try(var connection=source.getConnection()) {execute(connection,"UPDATE business.business_space SET brand_name='New synthetic brand',version=version+1 WHERE id="+changed.space);}
        h.tasks.admitNextMonitorAction().orElseThrow();h.assertRefused(changedProposal,"INPUT_CHANGED");h.assertNoTask(changed);
        // An expected identity refusal must commit WAIT_INPUT, not poison the caller's transaction.
        var disabled=h.scenario(500,8,100);var disabledProposal=h.propose(disabled,"COMPLETED");
        long disabledInstallation=Long.parseLong(h.platform.identityOf(disabled.user,disabled.conversation).installationId());
        try(var connection=source.getConnection()) {execute(connection,"UPDATE business.agent_installation SET enabled=false,version=version+1 WHERE id="+disabledInstallation);}
        h.tasks.admitNextMonitorAction().orElseThrow();h.assertRefused(disabledProposal,"AGENT_UNAVAILABLE");h.assertNoTask(disabled);
        var switchedOff=h.scenario(500,8,100);var switchedOffProposal=h.propose(switchedOff,"COMPLETED");
        h.properties.setAutomaticMonitorEnabled(false);
        h.tasks.admitNextMonitorAction().orElseThrow();h.assertRefused(switchedOffProposal,"AUTOMATIC_MONITOR_DISABLED");h.assertNoTask(switchedOff);
        h.properties.setAutomaticMonitorEnabled(true);
        var noDay=h.scenario(500,8,100);var noDayProposal=h.propose(noDay,"COMPLETED");
        h.properties.setMonitorBudgetEarliestTrustedDate(LocalDate.parse("2026-10-10"));
        h.tasks.admitNextMonitorAction().orElseThrow();h.assertRefused(noDayProposal,"BUDGET_NOT_EFFECTIVE");h.assertNoTask(noDay);
        h.properties.setMonitorBudgetEarliestTrustedDate(LocalDate.parse("2026-10-08"));
        verifyTwoConversationsShareOneBudget(h);
        verifyManualWinnerReplaysAfterAccountDisable(h);
        verifyFreshMidnightAndSeparateLedgerSnapshot(h);
        verifyQueuedInstallationWriterDoesNotInvertForegroundLocks(h);
        System.out.println("DB_GATE monitor_execution=v047_v048_owned_immutable_cas_zero_before_completed_single_task_wallet_outbox_replay_budget_revocation_stop_failure_midnight_queued_writer_passed");
    }

    private static void verifyTwoConversationsShareOneBudget(Harness h) throws Exception {
        var first=h.scenario(500,6,6);var second=h.sibling(first);
        var firstProposal=h.propose(first,"COMPLETED");var secondProposal=h.propose(second,"COMPLETED");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(()->h.tasks.admitNextMonitorAction().orElseThrow());
            var two=executor.submit(()->h.tasks.admitNextMonitorAction().orElseThrow());
            one.get(20,TimeUnit.SECONDS);two.get(20,TimeUnit.SECONDS);
        }
        var account=AgentPlatformServiceTests.user(first.user);
        var firstView=h.monitors.admission(account,first.conversation,firstProposal.run.id());
        var secondView=h.monitors.admission(account,second.conversation,secondProposal.run.id());
        assertThat(List.of(firstView.status(),secondView.status())).containsExactlyInAnyOrder("ADMITTED","WAIT_INPUT");
        assertThat(firstView.status().equals("WAIT_INPUT")?firstView.reasonCode():secondView.reasonCode()).isEqualTo("DAY_LIMIT");
        try(var connection=h.source.getConnection()) {
            assertThat(scalar(connection,"SELECT count(*) FROM business.business_task WHERE user_id="+first.user)).isEqualTo("1");
            assertThat(scalar(connection,"SELECT frozen_points FROM business.point_account WHERE user_id="+first.user)).isEqualTo("6");
        }
    }

    private static void verifyManualWinnerReplaysAfterAccountDisable(Harness h) throws Exception {
        var scenario=h.scenario(500,8,100);var proposed=h.propose(scenario,"COMPLETED");
        var draft=h.tasks.getDraft(AgentPlatformServiceTests.user(scenario.user),proposed.draft.id());
        UUID key=UUID.randomUUID();
        var confirmed=h.tasks.confirm(AgentPlatformServiceTests.user(scenario.user),proposed.draft.id(),key.toString(),
                new TaskDtos.ConfirmRequest(draft.confirmationToken(),draft.inputHash(),draft.pricingVersionId()));
        try(var connection=h.source.getConnection()) {execute(connection,"UPDATE business.account SET status='DISABLED' WHERE id="+scenario.user);}
        h.tasks.admitNextMonitorAction().orElseThrow();
        var view=h.monitors.admission(AgentPlatformServiceTests.user(scenario.user),scenario.conversation,proposed.run.id());
        assertThat(view.taskId()).isEqualTo(confirmed.response().id());assertThat(view.executionSource()).isEqualTo("USER_CONFIRM");
        h.assertSingleAcceptance(proposed,view,6);
        assertThat(h.tasks.confirm(AgentPlatformServiceTests.user(scenario.user),proposed.draft.id(),key.toString(),
                new TaskDtos.ConfirmRequest(draft.confirmationToken(),draft.inputHash(),draft.pricingVersionId())).response().id()).isEqualTo(view.taskId());
    }

    private static void verifyFreshMidnightAndSeparateLedgerSnapshot(Harness h) throws Exception {
        var scenario=h.scenario(500,8,12);var proposal=h.propose(scenario,"COMPLETED");
        try(var holder=h.source.getConnection();var observer=h.source.getConnection();var executor=Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);int holderPid=pid(holder);
            try {
            scalar(holder,"SELECT user_id FROM business.point_account WHERE user_id="+scenario.user+" FOR UPDATE");
            Future<?> admission=executor.submit(()->h.tasks.admitNextMonitorAction().orElseThrow());
            awaitBlocked(observer,holderPid,"point_account",admission);
            h.time.set(Instant.parse("2026-10-08T16:00:01Z"));
            // This transaction becomes visible only after the waiting point-account SELECT finishes.
            execute(holder,"INSERT INTO business.point_ledger(id,user_id,available_delta,frozen_delta,available_after,frozen_after,entry_type,business_type,business_id,idempotency_key,reason,created_at) VALUES ("+h.ids.nextId()+","+scenario.user+",0,-7,500,0,'TASK_SETTLE','TASK',"+h.ids.nextId()+",'midnight-gate','synthetic prior consumption','2026-10-08T16:00:01Z')");
            holder.commit();admission.get(20,TimeUnit.SECONDS);
            h.assertRefused(proposal,"DAY_LIMIT");h.assertNoTask(scenario);
            assertThat(h.monitors.admission(AgentPlatformServiceTests.user(scenario.user),scenario.conversation,proposal.run.id()).admittedAt()).isNull();
            } finally {holder.rollback();}
        } finally {h.time.set(Instant.parse("2026-10-08T15:59:59Z"));}
    }

    private static void verifyQueuedInstallationWriterDoesNotInvertForegroundLocks(Harness h) throws Exception {
        var scenario=h.scenario(500,8,100);var proposal=h.propose(scenario,"COMPLETED");
        long installation=Long.parseLong(h.platform.identityOf(scenario.user,scenario.conversation).installationId());
        try(var holder=h.source.getConnection();var observer=h.source.getConnection();var executor=Executors.newFixedThreadPool(3)) {
            holder.setAutoCommit(false);int holderPid=pid(holder);
            try {
            scalar(holder,"SELECT user_id FROM business.point_account WHERE user_id="+scenario.user+" FOR UPDATE");
            Future<?> admission=executor.submit(()->h.tasks.admitNextMonitorAction().orElseThrow());
            int admissionPid=awaitBlocked(observer,holderPid,"point_account",admission);
            Future<?> writer=executor.submit(()-> {try(var connection=h.source.getConnection()) {execute(connection,"UPDATE business.agent_installation SET enabled=false,version=version+1 WHERE id="+installation);}catch(SQLException failure){throw new IllegalStateException(failure);} });
            awaitBlocked(observer,admissionPid,"agent_installation",writer);
            Future<?> foreground=executor.submit(()-> {try(var connection=h.source.getConnection()) {
                connection.setAutoCommit(false);
                // Exact foreground ordering; it must block BEFORE taking the conversation row.
                scalar(connection,"SELECT id FROM business.account WHERE id="+scenario.user+" FOR UPDATE");
                scalar(connection,"SELECT id FROM business.conversation WHERE id="+scenario.conversation+" FOR UPDATE");
                scalar(connection,"SELECT id FROM business.agent_installation WHERE id="+installation+" FOR SHARE");
                connection.commit();
            }catch(SQLException failure){throw new IllegalStateException(failure);} });
            awaitBlocked(observer,admissionPid,"business.account",foreground);
            assertThat(foreground.isDone()).isFalse();holder.commit();
            admission.get(20,TimeUnit.SECONDS);writer.get(20,TimeUnit.SECONDS);foreground.get(20,TimeUnit.SECONDS);
            assertThat(h.monitors.admission(AgentPlatformServiceTests.user(scenario.user),scenario.conversation,proposal.run.id()).status()).isEqualTo("ADMITTED");
            } finally {holder.rollback();}
        }
    }

    private static int awaitBlocked(Connection observer,int blocker,String queryFragment,Future<?> operation) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            if(operation.isDone()) {operation.get();throw new AssertionError("Operation finished without the required PostgreSQL lock wait: "+queryFragment);}
            try(var statement=observer.prepareStatement("SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid)) AND query LIKE ?")) {
                statement.setInt(1,blocker);statement.setString(2,"%"+queryFragment+"%");
                try(var result=statement.executeQuery()) {if(result.next())return result.getInt(1);}
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("Required PostgreSQL lock wait not observed: "+queryFragment);
    }

    private record Scenario(long user,long conversation,long space,long topic,FrozenMonitorRange range,MonitorAuthorizationView grant,MonitorExecutionIntent intent) { }
    private record Proposed(Scenario scenario,AgentRunRecord run,TaskRecords.Draft draft) { }
    private static final class Harness {
        final DataSource source;final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        final SnowflakeIdGenerator ids=mock(SnowflakeIdGenerator.class);
        final AtomicReference<Instant> time=new AtomicReference<>(Instant.parse("2026-10-08T15:59:59Z"));
        final AgentPlatformProperties properties=new AgentPlatformProperties();
        final MonitorExecutionMapper mapper;final ConversationMapper conversations;final TaskControlMapper taskMapper;
        final AgentPlatformService platform;final MonitorExecutionService monitors;final TaskControlService tasks;
        Harness(DataSource source) {
            this.source=source;var sequence=new AtomicLong(948000000000000000L);when(ids.nextId()).thenAnswer(ignored->sequence.incrementAndGet());
            var configuration=new Configuration(new Environment("monitor-execution-gate",new SpringManagedTransactionFactory(),source));
            configuration.setMapUnderscoreToCamelCase(true);
            for(var mapperType:List.of(MonitorExecutionMapper.class,AgentPlatformMapper.class,ConversationMapper.class,
                    TaskControlMapper.class,GeoMapper.class,GeoTaskMapper.class,BusinessSpaceMapper.class,ProductMapper.class,CompetitorMapper.class)) configuration.addMapper(mapperType);
            var sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
            mapper=sessions.getMapper(MonitorExecutionMapper.class);conversations=sessions.getMapper(ConversationMapper.class);taskMapper=sessions.getMapper(TaskControlMapper.class);
            var spaces=sessions.getMapper(BusinessSpaceMapper.class);var products=sessions.getMapper(ProductMapper.class);var competitors=sessions.getMapper(CompetitorMapper.class);
            platform=proxy(new AgentPlatformService(sessions.getMapper(AgentPlatformMapper.class),properties,ids,new CatalogNameNormalizer(),json),source);
            var catalog=new AuthorizedCatalogContextFactory(spaces,products,competitors,new CatalogJsonCodec(json));
            var paid=new PaidTaskProperties();paid.setEnabled(true);paid.setGeoExecutionEnabled(true);paid.setGeoTestUnitPoints(2);paid.setGeoTestPricingVersionId(7002);paid.setGeoConfiguredPlatforms(Set.of("DEEPSEEK"));paid.setTokenSecret("isolated-monitor-gate-token-secret-at-least-32");
            var geo=new GeoTaskService(sessions.getMapper(GeoTaskMapper.class),sessions.getMapper(GeoMapper.class),taskMapper,conversations,catalog,ids,json,paid);
            Clock clock=mock(Clock.class);when(clock.instant()).thenAnswer(ignored->time.get());
            properties.setAutomaticMonitorEnabled(true);properties.setMonitorBudgetEarliestTrustedDate(LocalDate.parse("2026-10-08"));
            monitors=proxy(new MonitorExecutionService(mapper,platform,conversations,sessions.getMapper(GeoMapper.class),spaces,products,competitors,geo,paid,properties,ids,json,clock),source);
            var target=new TaskControlService(taskMapper,conversations,ids,json,paid,new AssetIngestionProperties(),catalog,geo);
            // confirm() is deliberately not @Transactional; production supplies this template.
            // Wire it here too, rather than accidentally validating auto-committed mapper calls.
            ReflectionTestUtils.setField(target,"transactions",new TransactionTemplate(new JdbcTransactionManager(source)));
            ReflectionTestUtils.invokeMethod(target,"setMonitorExecution",monitors,mapper);
            ReflectionTestUtils.invokeMethod(target,"setAgentPlatformService",platform);
            ReflectionTestUtils.invokeMethod(target,"setAccountingClock",clock);
            tasks=proxy(target,source);
        }
        OffsetDateTime now(){return OffsetDateTime.ofInstant(time.get(),ZoneOffset.UTC);}
        Scenario scenario(int balance,int perAction,int perDay) throws Exception {
            long user=ids.nextId(),space=ids.nextId(),conversation=ids.nextId(),topic=ids.nextId();
            long q1=ids.nextId(),q2=ids.nextId(),q3=ids.nextId();
            try(var connection=source.getConnection()) {
                execute(connection,"INSERT INTO business.account(id,username,username_normalized,password_hash,role,status,created_at,updated_at) VALUES ("+user+",'gate"+user+"','gate"+user+"',repeat('a',32),'USER','ACTIVE',now(),now());"
                    +"INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,brand_profile,created_at,updated_at) VALUES ("+space+","+user+",'Synthetic cafe','synthetic cafe','Synthetic cafe','{}',now(),now());"
                    +"INSERT INTO business.conversation(id,user_id,title,created_at,updated_at) VALUES ("+conversation+","+user+",'Synthetic monitor',now(),now());"
                    +"INSERT INTO business.geo_topic(id,user_id,space_id,name,name_normalized,region,created_at,updated_at) VALUES ("+topic+","+user+","+space+",'Cafe discovery','cafe discovery','Guangzhou',now(),now());"
                    +"INSERT INTO business.point_account(user_id,available_points,frozen_points,created_at,updated_at) VALUES ("+user+","+balance+",0,now(),now());");
                int index=0;for(long question:List.of(q1,q2,q3)) execute(connection,"INSERT INTO business.geo_question(id,user_id,space_id,topic_id,sequence_no,category,question_text,text_normalized,source,enabled,created_at,updated_at) VALUES ("+question+","+user+","+space+","+topic+","+(++index)+",'BRAND_DISCOVERY','Which cafe question "+index+"','which cafe question "+index+"','MANUAL',true,now(),now())");
            }
            platform.bindConversation(user,conversation,null,space);
            var input=new MonitorRangeInput(Long.toString(topic),List.of(Long.toString(q1),Long.toString(q2),Long.toString(q3)),List.of("DEEPSEEK"),"STANDARD");
            var account=AgentPlatformServiceTests.user(user);
            var preview=monitors.preview(account,conversation,input);assertThat(preview.status()).isEqualTo("READY");assertThat(preview.quotedPoints()).isEqualTo(6);
            monitors.putBudget(account,new BudgetPolicyPut(0,true,perAction,perDay));
            var grant=monitors.createGrant(account,conversation,new MonitorAuthorizationCreate(0,preview.range(),now().plusDays(2)));
            var intent=new MonitorExecutionIntent("MONITOR_RUN",grant.id(),grant.version(),preview.range(),preview.pricingVersionId(),preview.quotedPoints());
            return new Scenario(user,conversation,space,topic,preview.range(),grant,intent);
        }
        Scenario sibling(Scenario first) throws Exception {
            long conversation=ids.nextId();
            try(var connection=source.getConnection()) {execute(connection,"INSERT INTO business.conversation(id,user_id,title,created_at,updated_at) VALUES ("+conversation+","+first.user+",'Second budget conversation',now(),now())");}
            platform.bindConversation(first.user,conversation,Long.valueOf(platform.identityOf(first.user,first.conversation).installationId()),first.space);
            var grant=monitors.createGrant(AgentPlatformServiceTests.user(first.user),conversation,new MonitorAuthorizationCreate(0,first.range,now().plusDays(2)));
            var intent=new MonitorExecutionIntent("MONITOR_RUN",grant.id(),grant.version(),first.range,first.intent.pricingVersionId(),first.intent.quotedPoints());
            return new Scenario(first.user,conversation,first.space,first.topic,first.range,grant,intent);
        }
        Proposed propose(Scenario scenario,String finalStatus) throws Exception {
            UUID runId=UUID.randomUUID();long message=ids.nextId(),assistant=ids.nextId();
            var selection=monitors.prepareMessage(scenario.user,scenario.conversation,scenario.intent);
            assertThat(selection.reason()).isNull();
            var content=json.createObjectNode().put("text","Run this selected GEO monitor");
            content.set("executionIntent",json.valueToTree(scenario.intent));content.set("monitorActionContext",json.valueToTree(selection.context()));
            var context=json.createObjectNode();context.set("agentIdentity",json.valueToTree(platform.identityOf(scenario.user,scenario.conversation)));
            try(var connection=source.getConnection()) {
                try(var statement=connection.prepareStatement("INSERT INTO business.conversation_message(id,conversation_id,user_id,role,content_type,content,agent_run_id,client_message_id,idempotency_key,request_fingerprint,sequence_no,status,created_at,updated_at) VALUES (?,?,?,'USER','TEXT',?::jsonb,?,?,?,repeat('a',64),(SELECT COALESCE(MAX(sequence_no),0)+1 FROM business.conversation_message WHERE conversation_id=?),'COMPLETED',now(),now())")) {
                    statement.setLong(1,message);statement.setLong(2,scenario.conversation);statement.setLong(3,scenario.user);statement.setString(4,json.writeValueAsString(content));statement.setObject(5,runId);statement.setObject(6,UUID.randomUUID());statement.setObject(7,UUID.randomUUID());statement.setLong(8,scenario.conversation);statement.executeUpdate();
                }
                try(var statement=connection.prepareStatement("INSERT INTO business.agent_run(id,conversation_id,user_id,request_message_id,selected_space_id,status,context_snapshot,context_ref_hash,context_expires_at,created_at,updated_at) VALUES (?,?,?,?,?,'RUNNING',?::jsonb,repeat('b',64),now()+interval '2 days',now(),now())")) {
                    statement.setObject(1,runId);statement.setLong(2,scenario.conversation);statement.setLong(3,scenario.user);statement.setLong(4,message);statement.setLong(5,scenario.space);statement.setString(6,json.writeValueAsString(context));statement.executeUpdate();
                }
                try(var statement=connection.prepareStatement("INSERT INTO business.conversation_message(id,conversation_id,user_id,role,content_type,content,agent_run_id,reply_to_message_id,sequence_no,status,created_at,updated_at) VALUES (?,?,?,'ASSISTANT','TEXT','{}',?,?,(SELECT MAX(sequence_no)+1 FROM business.conversation_message WHERE conversation_id=?),'STREAMING',now(),now())")) {
                    statement.setLong(1,assistant);statement.setLong(2,scenario.conversation);statement.setLong(3,scenario.user);statement.setObject(4,runId);statement.setLong(5,message);statement.setLong(6,scenario.conversation);statement.executeUpdate();
                }
                execute(connection,"UPDATE business.agent_run SET response_message_id="+assistant+" WHERE id='"+runId+"'");
            }
            var run=conversations.findRun(runId).orElseThrow();
            var proposal=json.createObjectNode().put("taskType","GEO").put("parameterSchemaVersion",1).put("spaceId",Long.toString(scenario.space)).putNull("productId").put("proposalOnly",true).put("draftOperation","NEW");
            proposal.set("parameters",selection.context().parameters());proposal.putArray("warnings");
            tasks.acceptProposal(run,new PythonChatRunEvent(1,UUID.randomUUID(),"task-draft.proposed",runId,now(),1,proposal),"Prepared, not executed",now());
            var draft=taskMapper.findDraftByRunId(runId).orElseThrow();
            assertThat(mapper.admission(runId,scenario.user,scenario.conversation).orElseThrow().status()).isEqualTo("PENDING");
            var result=new Proposed(scenario,run,draft);if(!"RUNNING".equals(finalStatus)) finish(result,finalStatus);return result;
        }
        void finish(Proposed proposed,String status) throws SQLException {
            assertThat(status).isIn("COMPLETED","FAILED","STOPPED");
            try(var connection=source.getConnection();var statement=connection.prepareStatement("UPDATE business.agent_run SET status=?,completed_at=? WHERE id=?")) {statement.setString(1,status);statement.setObject(2,now());statement.setObject(3,proposed.run.id());statement.executeUpdate();}
        }
        void assertRefused(Proposed proposed,String reason) {
            var view=monitors.admission(AgentPlatformServiceTests.user(proposed.scenario.user),proposed.scenario.conversation,proposed.run.id());
            assertThat(view.status()).isEqualTo("WAIT_INPUT");assertThat(view.reasonCode()).isEqualTo(reason);assertThat(view.taskId()).isNull();
        }
        void assertNoTask(Scenario scenario) throws SQLException {
            try(var connection=source.getConnection()) {assertThat(scalar(connection,"SELECT count(*) FROM business.business_task WHERE user_id="+scenario.user)).isEqualTo("0");assertThat(scalar(connection,"SELECT count(*) FROM business.point_ledger WHERE user_id="+scenario.user+" AND entry_type='TASK_FREEZE'")).isEqualTo("0");assertThat(scalar(connection,"SELECT frozen_points FROM business.point_account WHERE user_id="+scenario.user)).isEqualTo("0");}
        }
        void assertSingleAcceptance(Proposed proposed,MonitorAdmissionView view,int points) throws SQLException {
            try(var connection=source.getConnection()) {
                for(String table:List.of("business_task","point_reservation","geo_run","outbox_event")) {
                    String field=table.equals("business_task")?"id":table.equals("outbox_event")?"aggregate_id":"task_id";
                    assertThat(scalar(connection,"SELECT count(*) FROM business."+table+" WHERE "+field+"="+view.taskId())).as(table).isEqualTo("1");
                }
                assertThat(scalar(connection,"SELECT count(*) FROM business.point_ledger WHERE business_id="+view.taskId()+" AND entry_type='TASK_FREEZE'")).isEqualTo("1");
                assertThat(scalar(connection,"SELECT frozen_points FROM business.point_reservation WHERE task_id="+view.taskId())).isEqualTo(Integer.toString(points));
                if("EXECUTION_PREAUTH".equals(view.executionSource())) {
                    assertThat(scalar(connection,"SELECT status FROM business.task_draft WHERE id="+proposed.draft.id())).isEqualTo("ADMITTED");
                    assertThat(scalar(connection,"SELECT confirmed_at IS NULL AND admitted_at IS NOT NULL FROM business.task_draft WHERE id="+proposed.draft.id())).isEqualTo("t");
                    assertThat(scalar(connection,"SELECT status FROM business.task_confirmation WHERE draft_id="+proposed.draft.id())).isEqualTo("INVALIDATED");
                } else assertThat(scalar(connection,"SELECT status FROM business.task_draft WHERE id="+proposed.draft.id())).isEqualTo("CONFIRMED");
            }
        }
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(T target,DataSource source) {
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new JdbcTransactionManager(source),new AnnotationTransactionAttributeSource()));return (T)proxy.getProxy();
    }
    private static int pid(Connection connection) throws SQLException {return Integer.parseInt(scalar(connection,"SELECT pg_backend_pid()"));}
    private static Flyway flyway(String url,String user,String password,String target) {return Flyway.configure().dataSource(url,user,password).schemas("business","agent").defaultSchema("business").cleanDisabled(true).target(target).load();}
    private static String required(String name) {var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing isolated Gate variable: "+name);return value;}
    private static String scalar(Connection connection,String sql) throws SQLException {try(var statement=connection.createStatement();var result=statement.executeQuery(sql)){if(!result.next())throw new IllegalStateException("Missing gate scalar");return result.getString(1);}}
    private static void execute(Connection connection,String sql) throws SQLException {try(var statement=connection.createStatement()){statement.execute(sql);}}
    private static void reject(Connection connection,String sql,String state) {assertThatThrownBy(()->execute(connection,sql)).isInstanceOfSatisfying(SQLException.class,exception->assertThat(exception.getSQLState()).isEqualTo(state));}
}
