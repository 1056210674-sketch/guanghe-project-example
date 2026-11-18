package com.guanghe.backend.article;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.agentplatform.*;
import com.guanghe.backend.adminplatform.AdminPlatformMapper;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.*;
import com.guanghe.backend.catalog.competitor.CompetitorMapper;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.product.ProductService;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.geo.GeoTaskMapper;
import com.guanghe.backend.geo.GeoPlanMapper;
import com.guanghe.backend.geo.GeoPlanRecords;
import com.guanghe.backend.knowledge.*;
import com.guanghe.backend.asset.ObjectStorage;
import org.springframework.beans.factory.ObjectProvider;
import com.guanghe.backend.task.*;
import com.guanghe.backend.workflow.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Actual V048 -> V049, MyBatis, fixed parent wallet, outbox, claim, ARTICLE schema validation,
 * result settlement and durable TITLE -> OUTLINE -> BODY continuation. Results here are explicit
 * fixture responses, not evidence that a paid model or publishing provider was contacted.
 * Requires a separately provisioned throwaway Gate database; never a shared/test production DB.
 */
@EnabledIfEnvironmentVariable(named="DATABASE_GATE_ENABLED",matches="true")
class ArticleFullDatabaseGateIntegrationTests {
    @Test void completeSingleArticleUsesOneFrozenPotAndCommittedDeferredStages() throws Exception {
        String database=required("GATE_EXPECTED_DATABASE");
        assertThat(database).matches("^zhixiaohe_gate_video_[a-f0-9]{10}$");
        String suffix=database.substring("zhixiaohe_gate_video_".length());
        String migrationUser=required("GATE_MIGRATION_DB_USERNAME"),runtimeUser=required("GATE_RUNTIME_DB_USERNAME");
        assertThat(migrationUser).isEqualTo("gate_migration_"+suffix);
        assertThat(runtimeUser).isEqualTo("gate_runtime_"+suffix);
        String migrationUrl=required("GATE_MIGRATION_DB_URL"),runtimeUrl=required("GATE_RUNTIME_DB_URL");
        String migrationPassword=required("GATE_MIGRATION_DB_PASSWORD"),runtimePassword=required("GATE_RUNTIME_DB_PASSWORD");
        try(var migration=DriverManager.getConnection(migrationUrl,migrationUser,migrationPassword);
                var runtime=DriverManager.getConnection(runtimeUrl,runtimeUser,runtimePassword)) {
            assertThat(scalar(migration,"SELECT current_database()")).isEqualTo(database);
            assertThat(scalar(runtime,"SELECT current_database()")).isEqualTo(database);
        }
        flyway(migrationUrl,migrationUser,migrationPassword,"048").migrate();
        try(var connection=DriverManager.getConnection(migrationUrl,migrationUser,migrationPassword)) {
            execute(connection,"INSERT INTO business.account(id,username,username_normalized,password_hash,role,status,created_at,updated_at) VALUES(94901,'article_legacy','article_legacy',repeat('a',32),'USER','ACTIVE',now(),now()) ON CONFLICT(id) DO NOTHING");
        }
        var upgrade=flyway(migrationUrl,migrationUser,migrationPassword,"049");
        upgrade.migrate();upgrade.validate();assertThat(upgrade.migrate().migrationsExecuted).isZero();
        var knowledgeHourly=flyway(migrationUrl,migrationUser,migrationPassword,"051");
        knowledgeHourly.migrate();knowledgeHourly.validate();assertThat(knowledgeHourly.migrate().migrationsExecuted).isZero();
        try(var connection=DriverManager.getConnection(migrationUrl,migrationUser,migrationPassword)) {
            assertThat(scalar(connection,"SELECT username FROM business.account WHERE id=94901")).isEqualTo("article_legacy");
            assertThat(scalar(connection,"SELECT count(*) FROM business.flyway_schema_history WHERE success AND version IN ('049','050','051')")).isEqualTo("3");
            execute(connection,"GRANT USAGE ON SCHEMA business TO \""+runtimeUser+"\"; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA business TO \""+runtimeUser+"\"");
        }
        var source=new PGSimpleDataSource();source.setUrl(runtimeUrl);source.setUser(runtimeUser);source.setPassword(runtimePassword);
        source.setApplicationName("article-full-gate-"+suffix);source.setConnectTimeout(5);source.setSocketTimeout(30);
        source.setOptions("-c statement_timeout=20000 -c lock_timeout=15000");
        var h=new Harness(source);
        var scenario=h.scenario();
        String key=UUID.randomUUID().toString();
        var started=h.full.start(user(scenario.user),scenario.conversation,key,scenario.request);
        long plan=Long.parseLong(started.id()),article=Long.parseLong(started.articleId());
        assertThat(started.status()).isEqualTo("RUNNING");assertThat(started.quotedPoints()).isEqualTo(3);
        assertThat(started.publicationRequiresConfirmation()).isTrue();
        assertThat(started.knowledgeSourcesCurrent()).isTrue();
        assertThat(started.stages()).extracting(ArticleFullDtos.StageView::status).containsExactly("PENDING","PENDING","PENDING");
        h.assertScalar("SELECT frozen_points FROM business.point_account WHERE user_id="+scenario.user,"3");
        h.assertScalar("SELECT count(*) FROM business.business_task WHERE user_id="+scenario.user,"0");
        h.assertScalar("SELECT count(*) FROM business.article WHERE user_id="+scenario.user,"1");
        h.assertScalar("SELECT confirmed_title IS NULL FROM business.article WHERE id="+article,"t");
        assertThat(h.full.start(user(scenario.user),scenario.conversation,key,scenario.request).id()).isEqualTo(started.id());
        assertThatThrownBy(()->h.full.get(user(94901),plan)).isInstanceOf(BusinessException.class);
        try(var connection=source.getConnection()) {
            reject(connection,"UPDATE business.article_execution_grant SET max_points=max_points+1,version=version+1 WHERE id="+scenario.grant.id(),"P0001");
            reject(connection,"UPDATE business.article_full_preparation SET deadline_at=deadline_at+interval '1 hour' WHERE plan_id="+plan,"P0001");
            reject(connection,"UPDATE business.workflow_plan_step SET quoted_points=2 WHERE plan_id="+plan+" AND ordinal=2","23514");
            reject(connection,"UPDATE business.workflow_plan_step SET deferred_article_mode=NULL WHERE plan_id="+plan+" AND ordinal=2","23514");
            reject(connection,"CREATE TABLE business.article_runtime_ddl_forbidden(id bigint)","42501");
        }
        OffsetDateTime deadline=h.full.get(user(scenario.user),plan).deadlineAt();
        for(int ordinal=1;ordinal<=3;ordinal++) {
            // A fresh service instance proves that continuation is database state, not JVM memory.
            h.full=h.newFullService();h.full.advance(plan);
            var running=h.full.get(user(scenario.user),plan);var stage=running.stages().get(ordinal-1);
            assertThat(stage.status()).isEqualTo("WAITING");assertThat(stage.taskId()).isNotNull();
            assertThat(running.deadlineAt()).isEqualTo(deadline);
            long task=Long.parseLong(stage.taskId());
            h.assertScalar("SELECT input_snapshot->'parameters'->>'articleId' FROM business.business_task WHERE id="+task,Long.toString(article));
            h.assertScalar("SELECT confirmed_at IS NULL AND admitted_at IS NOT NULL FROM business.task_draft WHERE id=(SELECT draft_id FROM business.business_task WHERE id="+task+")","t");
            h.assertScalar("SELECT count(*) FROM business.outbox_event WHERE aggregate_id="+task,"1");
            var claimed=h.claim(task);
            var firstContext=h.tasks.executionContext(task,claimed.executionNo(),claimed.executionToken());
            assertThat(firstContext.authorizedContext().path("knowledge").path("documents").size()).isEqualTo(1);
            // Live pricing/budget configuration drift must not renew or alter accepted stage limits.
            h.paid.setArticleMaxModelCalls(99);h.paid.setArticleTitleTestPoints(90);
            var secondContext=h.tasks.executionContext(task,claimed.executionNo(),claimed.executionToken());
            assertThat(secondContext.budget()).isEqualTo(firstContext.budget());
            assertThat(secondContext.pricing().quotedPoints()).isEqualTo(1);
            h.full.advance(plan); // In-flight GET/tick cannot start a duplicate task or move to next stage.
            h.assertScalar("SELECT count(*) FROM business.business_task WHERE user_id="+scenario.user,Integer.toString(ordinal));
            h.complete(task,claimed,stage.mode());
            h.full.advance(plan); // Settlement + auto-adoption commits alone, without materializing next stage.
            var completed=h.full.get(user(scenario.user),plan);
            assertThat(completed.stages().get(ordinal-1).status()).isEqualTo("SUCCEEDED");
            h.assertScalar("SELECT count(*) FROM business.business_task WHERE user_id="+scenario.user,Integer.toString(ordinal));
            if(ordinal<3) assertThat(completed.stages().get(ordinal).status()).isEqualTo("PENDING");
        }
        h.full.advance(plan);
        var ready=h.full.get(user(scenario.user),plan);
        assertThat(ready.status()).isEqualTo("SUCCEEDED");assertThat(ready.settledPoints()).isEqualTo(3);assertThat(ready.releasedPoints()).isZero();
        assertThat(h.full.get(user(scenario.user),plan).deadlineAt()).isEqualTo(deadline);
        h.assertScalar("SELECT workflow_stage FROM business.article WHERE id="+article,"BODY_READY");
        h.assertScalar("SELECT status FROM business.article WHERE id="+article,"COMPLETED");
        h.assertScalar("SELECT length(body_text)>0 AND confirmed_title_source='AUTO_POLICY' AND confirmed_title_at IS NULL FROM business.article WHERE id="+article,"t");
        h.assertScalar("SELECT count(*) FROM business.article_automatic_adoption WHERE plan_id="+plan,"2");
        h.assertScalar("SELECT count(*) FROM business.article_creative_plan_revision WHERE article_id="+article+" AND confirmed_at IS NOT NULL","0");
        h.assertScalar("SELECT count(*) FROM business.article_submission_snapshot WHERE article_id="+article,"0");
        h.assertScalar("SELECT count(*) FROM business.point_ledger WHERE user_id="+scenario.user+" AND entry_type='WORKFLOW_FREEZE'","1");
        h.assertScalar("SELECT count(*) FROM business.point_ledger WHERE user_id="+scenario.user+" AND entry_type='TASK_FREEZE'","0");
        h.assertScalar("SELECT available_points||':'||frozen_points FROM business.point_account WHERE user_id="+scenario.user,"497:0");
        try(var connection=source.getConnection()) {
            reject(connection,"DELETE FROM business.article_automatic_adoption WHERE plan_id="+plan,"23514");
            reject(connection,"UPDATE business.article_full_preparation SET updated_at=updated_at+interval '1 second' WHERE plan_id="+plan,"P0001");
        }
        // Preparation of a later stage can fail without reverting an already paid/title artifact.
        h.paid.setArticleMaxModelCalls(2);h.paid.setArticleTitleTestPoints(1);
        var stopped=h.scenario();var second=h.full.start(user(stopped.user),stopped.conversation,UUID.randomUUID().toString(),stopped.request);
        long secondPlan=Long.parseLong(second.id());h.full.advance(secondPlan);
        long titleTask=Long.parseLong(h.full.get(user(stopped.user),secondPlan).stages().getFirst().taskId());
        var claim=h.claim(titleTask);h.complete(titleTask,claim,"TITLE");h.full.advance(secondPlan);
        h.full.revoke(user(stopped.user),stopped.conversation,Long.parseLong(stopped.grant.id()),2);
        assertThatThrownBy(()->h.full.advance(secondPlan)).isInstanceOf(BusinessException.class);
        h.full.preparationFailed(secondPlan,"TASK_STATE_CONFLICT");
        var failed=h.full.get(user(stopped.user),secondPlan);
        assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.settledPoints()).isEqualTo(1);assertThat(failed.releasedPoints()).isEqualTo(2);
        h.assertScalar("SELECT confirmed_title_source FROM business.article WHERE id="+second.articleId(),"AUTO_POLICY");
        h.assertScalar("SELECT count(*) FROM business.business_task WHERE user_id="+stopped.user,"1");
        h.assertScalar("SELECT available_points||':'||frozen_points FROM business.point_account WHERE user_id="+stopped.user,"499:0");
        h.knowledgeAndAdmin(scenario,plan);
        h.hourlyAndAdmin(scenario);
        // A corrected source after parent admission is never silently replaced by latest facts.
        h.paid.setArticleMaxModelCalls(2);h.paid.setArticleTitleTestPoints(1);
        var drift=h.scenario();var frozen=h.full.start(user(drift.user),drift.conversation,UUID.randomUUID().toString(),drift.request);
        var document=h.knowledge.documents(user(drift.user),drift.space,1,10).items().getFirst();
        h.knowledge.updateDocument(user(drift.user),drift.space,Long.parseLong(document.id()),
                new KnowledgeDtos.UpdateDocumentRequest(document.version(),document.name(),"小行星咖啡店的资料已更正，不静默使用新的资料。",null));
        h.full.advance(Long.parseLong(frozen.id()));
        assertThat(h.full.get(user(drift.user),Long.parseLong(frozen.id())).errorCode()).isEqualTo("KNOWLEDGE_SOURCE_CHANGED");
        h.assertScalar("SELECT count(*) FROM business.business_task WHERE user_id="+drift.user,"0");
        h.assertScalar("SELECT available_points||':'||frozen_points FROM business.point_account WHERE user_id="+drift.user,"500:0");
        System.out.println("DB_GATE article_full=v048_v049_v050_v051_owned_fixed_parent_same_article_schema_claim_result_adoption_restart_budget_knowledge_crud_metadata_hourly_no_publish_partial_failure_passed");
    }

    private record Scenario(long user,long space,long conversation,ArticleFullDtos.AuthorizationView grant,ArticleFullDtos.StartRequest request) { }
    private static final class Harness {
        final DataSource source;final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        final SnowflakeIdGenerator ids=mock(SnowflakeIdGenerator.class);final Clock clock=Clock.systemUTC();
        final PaidTaskProperties paid=new PaidTaskProperties();
        final ArticleFullMapper fullMapper;final ArticleMapper articles;final TaskControlMapper taskMapper;
        final ConversationMapper conversations;final WorkflowPlanMapper plans;final WorkflowExecutionMapper funding;
        final AgentPlatformService platform;final ArticleService articleService;final TaskControlService tasks;
        final KnowledgeService knowledge;final KnowledgeMapper knowledgeMapper;final AdminPlatformMapper admin;final GeoPlanMapper geoPlans;
        ArticleFullService full;
        Harness(DataSource source) {
            this.source=source;var sequence=new AtomicLong(949000000000000000L);when(ids.nextId()).thenAnswer(ignored->sequence.incrementAndGet());
            var configuration=new Configuration(new Environment("article-full-gate",new SpringManagedTransactionFactory(),source));
            configuration.setMapUnderscoreToCamelCase(true);
            for(var type:List.of(ArticleFullMapper.class,ArticleMapper.class,TaskControlMapper.class,ConversationMapper.class,
                    WorkflowPlanMapper.class,WorkflowExecutionMapper.class,AgentPlatformMapper.class,BusinessSpaceMapper.class,
                    ProductMapper.class,CompetitorMapper.class,AssetMapper.class,GeoTaskMapper.class,
                    KnowledgeMapper.class,AdminPlatformMapper.class,GeoPlanMapper.class)) configuration.addMapper(type);
            var sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
            fullMapper=sessions.getMapper(ArticleFullMapper.class);articles=sessions.getMapper(ArticleMapper.class);
            taskMapper=sessions.getMapper(TaskControlMapper.class);conversations=sessions.getMapper(ConversationMapper.class);
            plans=sessions.getMapper(WorkflowPlanMapper.class);funding=sessions.getMapper(WorkflowExecutionMapper.class);
            var spaces=sessions.getMapper(BusinessSpaceMapper.class);var products=sessions.getMapper(ProductMapper.class);
            var competitors=sessions.getMapper(CompetitorMapper.class);var codec=new CatalogJsonCodec(json);
            knowledgeMapper=sessions.getMapper(KnowledgeMapper.class);admin=sessions.getMapper(AdminPlatformMapper.class);
            geoPlans=sessions.getMapper(GeoPlanMapper.class);
            var knowledgeProperties=new KnowledgeProperties();
            @SuppressWarnings("unchecked") ObjectProvider<ObjectStorage> storage=mock(ObjectProvider.class);
            knowledge=proxy(new KnowledgeService(knowledgeMapper,sessions.getMapper(AgentPlatformMapper.class),ids,json,
                    new KnowledgeParser(knowledgeProperties),knowledgeProperties,storage,new AssetIngestionProperties(),new JdbcTransactionManager(source)),source);
            platform=proxy(new AgentPlatformService(sessions.getMapper(AgentPlatformMapper.class),new AgentPlatformProperties(),ids,new CatalogNameNormalizer(),json),source);
            var catalog=new AuthorizedCatalogContextFactory(spaces,products,competitors,codec);
            var spaceService=proxy(new BusinessSpaceService(spaces,ids,new CatalogNameNormalizer(),codec),source);
            articleService=proxy(new ArticleService(articles,sessions.getMapper(AssetMapper.class),sessions.getMapper(GeoTaskMapper.class),
                    spaceService,mock(ProductService.class),catalog,ids,json,new ArticleHtmlSanitizer()),source);
            paid.setEnabled(true);paid.setArticleExecutionEnabled(true);paid.setArticleTestPricingVersionId(7003);
            paid.setArticleTitleTestPoints(1);paid.setArticleOutlineTestPoints(1);paid.setArticleBodyTestPoints(1);
            paid.setTokenSecret("isolated-article-gate-token-secret-at-least-32");
            var target=new TaskControlService(taskMapper,conversations,ids,json,paid,new AssetIngestionProperties(),catalog,null,articleService);
            ReflectionTestUtils.setField(target,"transactions",new TransactionTemplate(new JdbcTransactionManager(source)));
            ReflectionTestUtils.invokeMethod(target,"setAgentPlatformService",platform);
            ReflectionTestUtils.invokeMethod(target,"setAccountingClock",clock);
            ReflectionTestUtils.invokeMethod(target,"setArticleKnowledge",knowledge);
            tasks=proxy(target,source);full=newFullService();
        }
        ArticleFullService newFullService() {
            var target=new ArticleFullService(fullMapper,articles,articleService,tasks,taskMapper,plans,funding,conversations,platform,paid,ids,json,clock);
            target.setKnowledgeService(knowledge);return proxy(target,source);
        }
        Scenario scenario() throws Exception {
            long user=ids.nextId(),space=ids.nextId(),conversation=ids.nextId(),message=ids.nextId();UUID run=UUID.randomUUID();
            try(var connection=source.getConnection()) {
                execute(connection,"INSERT INTO business.account(id,username,username_normalized,password_hash,role,status,created_at,updated_at) VALUES("+user+",'gate"+user+"','gate"+user+"',repeat('a',32),'USER','ACTIVE',now(),now());"
                        +"INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,brand_profile,created_at,updated_at) VALUES("+space+","+user+",'Synthetic project','synthetic project',NULL,'{}',now(),now());"
                        +"INSERT INTO business.conversation(id,user_id,title,created_at,updated_at) VALUES("+conversation+","+user+",'Synthetic article',now(),now());"
                        +"INSERT INTO business.point_account(user_id,available_points,frozen_points,created_at,updated_at) VALUES("+user+",500,0,now(),now());");
                try(var statement=connection.prepareStatement("INSERT INTO business.conversation_message(id,conversation_id,user_id,role,content_type,content,agent_run_id,client_message_id,idempotency_key,request_fingerprint,sequence_no,status,created_at,updated_at) VALUES(?,?,?,'USER','TEXT','{\"text\":\"介绍小行星咖啡店，事实只以资料为准\"}',?,?,?,repeat('a',64),1,'COMPLETED',now(),now())")) {
                    statement.setLong(1,message);statement.setLong(2,conversation);statement.setLong(3,user);statement.setObject(4,run);
                    statement.setObject(5,UUID.randomUUID());statement.setObject(6,UUID.randomUUID());statement.executeUpdate();
                }
                try(var statement=connection.prepareStatement("INSERT INTO business.agent_run(id,conversation_id,user_id,request_message_id,selected_space_id,status,context_snapshot,context_ref_hash,context_expires_at,completed_at,created_at,updated_at) VALUES(?,?,?,?,?,'COMPLETED','{}',repeat('b',64),now()+interval '2 days',now(),now(),now())")) {
                    statement.setObject(1,run);statement.setLong(2,conversation);statement.setLong(3,user);statement.setLong(4,message);statement.setLong(5,space);statement.executeUpdate();
                }
            }
            platform.bindConversation(user,conversation,null,space);
            var identity=platform.assertRunAllowed(user,conversation);
            try(var connection=source.getConnection();var statement=connection.prepareStatement("UPDATE business.agent_run SET context_snapshot=CAST(? AS jsonb) WHERE id=?")) {
                statement.setString(1,json.writeValueAsString(json.createObjectNode().set("agentIdentity",json.valueToTree(identity))));
                statement.setObject(2,run);statement.executeUpdate();
            }
            knowledge.createDocument(user(user),space,new KnowledgeDtos.CreateDocumentRequest("门店资料", "小行星咖啡店供应咖啡和点心。地址、价格和营业时间尚未提供。",null));
            knowledge.createPreference(user(user),new KnowledgeDtos.CreatePreferenceRequest(null,"文字简洁，未知资料不要猜测。"));
            var grant=full.authorize(user(user),conversation,new ArticleFullDtos.AuthorizationCreate(Long.toString(space),null,3,OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)));
            var request=new ArticleFullDtos.StartRequest(run,grant.id(),1,Long.toString(space),null,"介绍小行星咖啡店，事实只以资料为准",null,"NATURAL",1200);
            return new Scenario(user,space,conversation,grant,request);
        }
        void knowledgeAndAdmin(Scenario s,long completedPlan) throws Exception {
            var account=user(s.user);var page=knowledge.documents(account,s.space,1,10);
            assertThat(page.total()).isEqualTo(1);var first=page.items().getFirst();long document=Long.parseLong(first.id());
            assertThat(knowledge.document(account,s.space,document).paragraphs()).isNotEmpty();
            assertThatThrownBy(()->knowledge.document(user(94901),s.space,document)).isInstanceOf(BusinessException.class);
            var frozen=knowledge.context(s.user,s.space,"小行星咖啡店");
            assertThat(frozen.documents()).hasSize(1);assertThat(frozen.preferences()).hasSize(1);
            assertThat(frozen.businessHistory().writtenArticleCount()).isEqualTo(1);
            assertThat(frozen.businessHistory().publishedArticleCount()).isZero();
            assertThat(frozen.businessHistory().publishedPlacementCount()).isZero();
            var updated=knowledge.updateDocument(account,s.space,document,
                    new KnowledgeDtos.UpdateDocumentRequest(1,"资料名称不应出现在管理端", "私密门店更正资料：广州小行星，仅经营咖啡和点心。", "https://example.com/private-source"));
            assertThat(updated.version()).isEqualTo(2);assertThat(updated.sha256()).isNotEqualTo(first.sha256());
            assertThatThrownBy(()->knowledge.updateDocument(account,s.space,document,
                    new KnowledgeDtos.UpdateDocumentRequest(1,"过期资料", "不要用过期版本覆盖事实",null))).isInstanceOf(BusinessException.class);
            assertThat(knowledge.revalidateFrozenContext(s.user,s.space,frozen).documents()).isEmpty();
            assertThat(knowledge.revalidateFrozenContext(s.user,s.space,frozen).truncated()).isTrue();
            // Finished articles keep their result, with a read-only warning about changed sources.
            assertThat(full.get(account,completedPlan).status()).isEqualTo("SUCCEEDED");
            assertThat(full.get(account,completedPlan).knowledgeSourcesCurrent()).isFalse();
            var personal=knowledge.preferences(account,null).getFirst();
            assertThat(knowledge.createPreference(account,new KnowledgeDtos.CreatePreferenceRequest(null,personal.content())).id()).isEqualTo(personal.id());
            var project=knowledge.createPreference(account,new KnowledgeDtos.CreatePreferenceRequest(Long.toString(s.space),"项目私密表达偏好：只写已有事实。"));
            assertThat(project.scope()).isEqualTo("PROJECT");assertThat(project.sourceType()).isEqualTo("USER_INPUT");
            assertThat(knowledge.preferences(account,s.space)).hasSize(1);assertThat(knowledge.preferences(account,null)).hasSize(1);
            assertThatThrownBy(()->knowledge.updatePreference(user(94901),Long.parseLong(project.id()),
                    new KnowledgeDtos.UpdatePreferenceRequest(1,"越权替换"))).isInstanceOf(BusinessException.class);
            var preference=knowledge.updatePreference(account,Long.parseLong(personal.id()),new KnowledgeDtos.UpdatePreferenceRequest(1,"个人私密偏好：写短一点，不要营销口号。"));
            assertThat(preference.version()).isEqualTo(2);
            assertThatThrownBy(()->knowledge.deletePreference(account,Long.parseLong(personal.id()),1)).isInstanceOf(BusinessException.class);
            assertThat(knowledge.revalidateFrozenContext(s.user,s.space,frozen).preferences()).isEmpty();
            assertThat(knowledgeMapper.preferenceCount(s.user,null)).isEqualTo(1);
            assertThat(knowledgeMapper.preferenceCount(s.user,s.space)).isEqualTo(1);
            var docs=admin.documents(s.user,s.space,0,10);var prefs=admin.preferences(s.user,null,0,10);
            assertThat(docs).hasSize(1);assertThat(docs.getFirst().version()).isEqualTo(2);
            assertThat(prefs).hasSize(2);assertThat(admin.documentCount(s.user,s.space)).isEqualTo(1);
            assertThat(admin.preferenceCount(s.user,s.space)).isEqualTo(1);
            assertThat(admin.metadataSummary(s.user,s.space).readyKnowledgeDocumentCount()).isEqualTo(1);
            assertThat(admin.metadataSummary(s.user,null).activePersonalPreferenceCount()).isEqualTo(1);
            assertThat(json.writeValueAsString(docs)).doesNotContain("资料名称","private-source","私密门店更正资料","paragraphs","objectKey","sourceUrl");
            assertThat(json.writeValueAsString(prefs)).doesNotContain("个人私密偏好","项目私密表达偏好","sourceMessageId");
            knowledge.deletePreference(account,Long.parseLong(project.id()),1);
            knowledge.deleteDocument(account,s.space,document,2);
            assertThat(knowledge.documents(account,s.space,1,10).total()).isZero();
            assertThat(knowledge.context(s.user,s.space,"小行星").documents()).isEmpty();
            assertThat(knowledge.context(s.user,s.space,"小行星").preferences()).containsExactly(preference);
            assertThat(admin.metadataSummary(s.user,s.space).deletedKnowledgeDocumentCount()).isEqualTo(1);
            assertThat(admin.metadataSummary(s.user,s.space).deletedPreferenceCount()).isEqualTo(1);
            assertThat(admin.documents(s.user,s.space,0,10).getFirst().paragraphCount()).isZero();
            assertScalar("SELECT bool_and(paragraphs='[]'::jsonb AND object_key IS NULL AND source_url IS NULL) FROM business.knowledge_document_version WHERE document_id="+document,"t");
            assertThat(knowledgeMapper.documentCount(s.user,s.space)).isZero();
            assertThat(knowledgeMapper.readyDocumentCount(s.user,s.space)).isZero();
            assertThat(admin.documentCount(94901L,s.space)).isZero();assertThat(admin.preferenceCount(94901L,s.space)).isZero();
            var definition=admin.definitions().stream().filter(value->value.agentKey().equals("geo")).findFirst().orElseThrow();
            assertThat(definition.installationCount()).isGreaterThanOrEqualTo(2);
            new TransactionTemplate(new JdbcTransactionManager(source)).executeWithoutResult(ignored->{
                var locked=admin.lockDefinition(definition.agentKey(),definition.version()).orElseThrow();
                assertThat(admin.updateDefinition(locked.agentKey(),locked.version(),"0".repeat(64),locked.status(),locked.visibility(),"DISABLED",locked.visibility())).isZero();
                assertThat(admin.updateDefinition(locked.agentKey(),locked.version(),locked.definitionHash(),locked.status(),locked.visibility(),"DISABLED",locked.visibility())).isEqualTo(1);
                assertThat(admin.updateDefinition(locked.agentKey(),locked.version(),locked.definitionHash(),"DISABLED",locked.visibility(),locked.status(),locked.visibility())).isEqualTo(1);
            });
        }
        void hourlyAndAdmin(Scenario s) throws Exception {
            long topic=ids.nextId(),draft=ids.nextId(),plan=ids.nextId();OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
            try(var connection=source.getConnection()) {
                execute(connection,"INSERT INTO business.geo_topic(id,user_id,space_id,name,name_normalized,region,created_at,updated_at) VALUES("+topic+","+s.user+","+s.space+",'Hourly fixture','hourly fixture','Guangzhou',now(),now())");
            }
            // A non-executable GEO draft fixture for mapper/constraint checks only: no task,
            // money, outbox or provider call is created by this schedule metadata exercise.
            assertThat(taskMapper.insertDraft(new TaskRecords.Draft(draft,s.user,null,null,null,"PLAN","GEO_PLAN_DRAFT_CREATE",UUID.randomUUID(),"c".repeat(64),
                    "GEO",1,s.space,null,"{}","{}","a".repeat(64),7001,1,"[]","WAITING_CONFIRMATION",now.plusHours(1),null,now,now))).isEqualTo(1);
            var schedule="{\"type\":\"HOURLY\",\"timeZone\":\"Asia/Shanghai\",\"localTime\":\"00:00\"}";
            var record=new GeoPlanRecords.Plan(plan,s.user,s.space,null,topic,draft,UUID.randomUUID(),"d".repeat(64),"Hourly fixture","hourly fixture",
                    "ACTIVE","HOURLY",schedule,"{}","e".repeat(64),7001,1,2,now.plusHours(1),null,null,1,now,now);
            assertThat(geoPlans.insertPlan(record)).isEqualTo(1);
            assertThat(geoPlans.findPlan(plan,s.user).orElseThrow().scheduleType()).isEqualTo("HOURLY");
            assertThat(geoPlans.findPlan(plan,94901)).isEmpty();
            assertThat(admin.plan(plan).orElseThrow().scheduleType()).isEqualTo("HOURLY");
            assertThat(admin.plans(s.user,s.space,"ACTIVE",0,10)).hasSize(1);
            assertThat(admin.planCount(s.user,s.space,"ACTIVE")).isEqualTo(1);
            assertThat(admin.planCount(94901L,s.space,null)).isZero();
            assertThat(geoPlans.insertSlot(new GeoPlanRecords.Slot(ids.nextId(),plan,s.user,now,"SKIPPED",null,null,"TASK_ALREADY_RUNNING",now,now))).isEqualTo(1);
            assertThat(admin.slots(plan,0,10)).hasSize(1);assertThat(admin.slotCount(plan)).isEqualTo(1);
            try(var connection=source.getConnection()) {
                reject(connection,"UPDATE business.geo_plan SET schedule_type='ARTICLE_FULL_V1' WHERE id="+plan,"23514");
            }
        }
        TaskDtos.ClaimResponse claim(long task) throws Exception {
            String event;try(var connection=source.getConnection()){event=scalar(connection,"SELECT id FROM business.outbox_event WHERE aggregate_id="+task+" ORDER BY id LIMIT 1");}
            var claimed=tasks.claim(task,new TaskDtos.ClaimRequest("article-gate",event,"fixture-worker",1,"agent-bundle-v1.33.9")).response();
            assertThat(claimed.claimed()).isTrue();return claimed;
        }
        void complete(long taskId,TaskDtos.ClaimResponse claim,String mode) throws Exception {
            var task=taskMapper.findTaskInternal(taskId).orElseThrow();JsonNode parameters=json.readTree(task.inputSnapshotJson()).path("parameters");
            ObjectNode inline=json.createObjectNode().put("mode",mode).put("articleVersion",parameters.path("articleVersion").asInt());
            inline.putArray("titleCandidates");inline.putNull("title");inline.putArray("outline");inline.putNull("plan");inline.putNull("body");
            inline.put("provider","DEEPSEEK").put("model","deepseek-v4-flash").put("modelVersion","fixture-gate-v1").put("promptVersion","agent-bundle-v1.33.9");
            inline.putObject("usage").put("modelCallCount",1).put("inputTokens",20).put("outputTokens",10).put("latencyMs",123).putNull("monetaryCost").putNull("currency");
            inline.putArray("warnings");inline.putObject("metadata").put("proposalOnly",true).put("persisted",false).put("published",false).putArray("authorizedRewriteBlockIds");
            if(mode.equals("TITLE")) inline.withArray("titleCandidates").add("小行星咖啡店介绍").add("小行星门店指南").add("了解小行星咖啡").add("小行星店内体验").add("小行星咖啡与点心");
            else {
                inline.put("title",parameters.path("selectedTitle").asText());
                JsonNode outline=mode.equals("BODY")?parameters.path("confirmedOutline"):json.readTree("[{\"key\":\"intro\",\"sequence\":1,\"level\":2,\"heading\":\"门店介绍\",\"brief\":\"介绍已有事实\"},{\"key\":\"visit\",\"sequence\":2,\"level\":2,\"heading\":\"到店前了解\",\"brief\":\"说明资料边界\"}]");
                inline.set("outline",outline.deepCopy());
                if(mode.equals("OUTLINE")) {
                    ObjectNode plan=inline.putObject("plan").put("summary","介绍小行星咖啡店").put("coreMessage","基于已提供事实介绍门店")
                            .put("targetAudience","关注咖啡店的读者").put("tone",parameters.path("tone").asText()).put("targetLength",parameters.path("targetLength").asText());
                    plan.set("targetPlatform",parameters.path("targetPlatform").deepCopy());plan.set("sections",outline.deepCopy());plan.putArray("riskNotes");
                } else {
                    ObjectNode body=inline.putObject("body").put("markdown","小行星咖啡店介绍");var blocks=body.putArray("blocks");
                    outline.forEach(section->blocks.addObject().put("key",section.path("key").asText()).put("heading",section.path("heading").asText()).put("markdown","小行星咖啡店的信息应以门店资料为准，尚未提供的地址、价格、奖项均不作推断。"));
                }
            }
            var artifact=json.createObjectNode().put("artifactType","ARTICLE_CONTENT").put("unitKey","article:"+mode.toLowerCase(Locale.ROOT)).put("schemaVersion",2);
            artifact.set("inlineData",inline);var usage=json.createObjectNode().put("inputTokens",20).put("outputTokens",10).put("images",0).put("videoSeconds",0).putNull("providerCost").putNull("currency");
            var request=new TaskDtos.ResultArtifactsRequest(UUID.randomUUID(),claim.executionNo(),claim.executionToken(),"DEEPSEEK","deepseek-v4-flash",UUID.randomUUID(),null,"CONFIRMED",List.of(artifact),usage,123);
            assertThat(tasks.reportResult(taskId,request).response().taskStatus()).isEqualTo("SUCCEEDED");
            assertThat(tasks.reportResult(taskId,request).response().taskStatus()).isEqualTo("SUCCEEDED"); // Same result ID replays, never charges twice.
        }
        void assertScalar(String sql,String expected) throws SQLException {try(var connection=source.getConnection()){assertThat(scalar(connection,sql)).as(sql).isEqualTo(expected);}}
    }
    private static AuthenticatedAccount user(long id){return new AuthenticatedAccount(id,"gate",AccountRole.USER,1,AccountStatus.ACTIVE,null,"gate",null);}
    @SuppressWarnings("unchecked") private static <T>T proxy(T target,DataSource source){var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(new JdbcTransactionManager(source),new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();}
    private static Flyway flyway(String url,String user,String password,String target){return Flyway.configure().dataSource(url,user,password).schemas("business","agent").defaultSchema("business").cleanDisabled(true).target(target).load();}
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing isolated Gate variable: "+key);return value;}
    private static String scalar(Connection connection,String sql) throws SQLException {try(var statement=connection.createStatement();var result=statement.executeQuery(sql)){if(!result.next())throw new IllegalStateException("Missing Gate scalar");return result.getString(1);}}
    private static void execute(Connection connection,String sql) throws SQLException {try(var statement=connection.createStatement()){statement.execute(sql);}}
    private static void reject(Connection connection,String sql,String state){assertThatThrownBy(()->execute(connection,sql)).isInstanceOfSatisfying(SQLException.class,failure->assertThat(failure.getSQLState()).isEqualTo(state));}
}
