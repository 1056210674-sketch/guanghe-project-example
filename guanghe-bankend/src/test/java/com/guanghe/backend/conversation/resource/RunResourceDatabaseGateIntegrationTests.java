package com.guanghe.backend.conversation.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.agentplatform.AgentPlatformDtos;
import com.guanghe.backend.agentplatform.AgentPlatformService;
import com.guanghe.backend.agentplatform.AgentRuntimeCatalog;
import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.RunToolAuthorizationService;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.conversation.context.RunContextService;
import com.guanghe.backend.geo.GeoChatContextFactory;
import com.guanghe.backend.geo.GeoMapper;
import com.guanghe.backend.geo.GeoTaskMapper;
import com.guanghe.backend.task.TaskControlMapper;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static com.guanghe.backend.conversation.resource.RunResourceDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual PostgreSQL constraints, owner filters and transaction locks; never the shared database. */
@EnabledIfEnvironmentVariable(named="DATABASE_GATE_ENABLED",matches="true")
class RunResourceDatabaseGateIntegrationTests {
    static final long USER=98701,OTHER=98702,CONVERSATION=98710,SPACE=98730,PRODUCT=98740,TOPIC=98750;
    static final UUID RUN=UUID.fromString("c19e66f5-c6f0-4b3e-a96b-2e75c4c33980");

    @Test void controlledResourcesUseRealOwnedSqlDurableReplayAndConcurrentBudget() throws Exception {
        String db=required("GATE_EXPECTED_DATABASE"),migrationUrl=required("GATE_MIGRATION_DB_URL"),
                migrationUser=required("GATE_MIGRATION_DB_USERNAME"),migrationPassword=required("GATE_MIGRATION_DB_PASSWORD"),
                runtimeUrl=required("GATE_RUNTIME_DB_URL"),runtimeUser=required("GATE_RUNTIME_DB_USERNAME"),
                runtimePassword=required("GATE_RUNTIME_DB_PASSWORD");
        if (!db.matches("^zhixiaohe_gate_[a-z0-9_]+$") || migrationUser.equals(runtimeUser)
                || !runtimeUser.matches("^gate_runtime_[a-z0-9_]+$")) throw new IllegalStateException("Isolated roles required");
        try(var migration=DriverManager.getConnection(migrationUrl,migrationUser,migrationPassword);
                var runtime=DriverManager.getConnection(runtimeUrl,runtimeUser,runtimePassword)) {
            assertThat(scalar(migration,"SELECT current_database()")).isEqualTo(db);
            assertThat(scalar(runtime,"SELECT current_database()")).isEqualTo(db);
        }
        var flyway=Flyway.configure().dataSource(migrationUrl,migrationUser,migrationPassword)
                .defaultSchema("business").schemas("business","agent").createSchemas(true)
                .locations("classpath:db/migration").load();
        flyway.migrate(); flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var migration=DriverManager.getConnection(migrationUrl,migrationUser,migrationPassword)) {
            seed(migration);
            execute(migration,"GRANT USAGE ON SCHEMA business TO \""+runtimeUser+"\"; GRANT SELECT,INSERT,UPDATE,DELETE"
                    +" ON ALL TABLES IN SCHEMA business TO \""+runtimeUser+"\"");
        }
        var dataSource=new PGSimpleDataSource(); dataSource.setUrl(runtimeUrl); dataSource.setUser(runtimeUser); dataSource.setPassword(runtimePassword);
        var configuration=new Configuration(new Environment("resource-gate",new SpringManagedTransactionFactory(),dataSource));
        // Production application.yaml enables camel-case mapping and keeps MyBatis's
        // ordinal constructor default. Do not turn on a different mapping mode for this Gate.
        configuration.setMapUnderscoreToCamelCase(true);
        for(Class<?> mapper:List.of(RunResourceMapper.class,ConversationMapper.class,AccountMapper.class,
                BusinessSpaceMapper.class,ProductMapper.class,GeoMapper.class,GeoTaskMapper.class,AssetMapper.class,TaskControlMapper.class))
            configuration.addMapper(mapper);
        var sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        var mapper=sessions.getMapper(RunResourceMapper.class);
        var taskMapper=sessions.getMapper(TaskControlMapper.class);
        // A real recommendation TITLE is followed by an expired BODY without that
        // recommendation id. The newest abandoned draft must not hide the BODY either.
        assertThat(taskMapper.lockGeoArticleDraftByIdentity(USER,"501","501:recommendation:1").orElseThrow().id()).isEqualTo(98902);
        var later=taskMapper.lockLatestArticleDraftByArticle(USER,"98901").orElseThrow();
        assertThat(later.id()).isEqualTo(98903);
        assertThat(later.status()).isEqualTo("EXPIRED");
        assertThat(later.parametersJson()).contains("BODY","\"articleVersion\": 3","\"geoRecommendationId\": null");
        assertThat(taskMapper.lockLatestArticleDraftByArticle(OTHER,"98901")).isEmpty();
        // Literal '%' / '_' are not SQL wildcard patterns; brands and aliases find real spaces.
        assertThat(mapper.search(USER,"SPACE","%",null,null)).extracting(RunResourceMapper.MatchedResource::resourceId).containsExactly(SPACE);
        assertThat(mapper.search(USER,"SPACE","光禾",null,null)).hasSize(2).allMatch(RunResourceMapper.MatchedResource::exact);
        assertThat(mapper.search(USER,"SPACE","品牌别名",null,null)).extracting(RunResourceMapper.MatchedResource::resourceId).containsExactly(SPACE);
        assertThat(mapper.search(USER,"PRODUCT","咖啡别名",null,null)).extracting(RunResourceMapper.MatchedResource::resourceId).containsExactly(PRODUCT);
        assertThat(mapper.search(USER,"GEO_TOPIC","咖啡关键词",SPACE,PRODUCT)).extracting(RunResourceMapper.MatchedResource::resourceId).containsExactly(TOPIC);
        assertThat(mapper.search(USER,"GEO_TOPIC","美式咖啡",SPACE,PRODUCT)).hasSize(1)
                .allMatch(item -> item.resourceId()==TOPIC && !item.exact());
        assertThat(mapper.search(USER,"GEO_TOPIC","咖啡别名",SPACE,PRODUCT)).hasSize(1).allMatch(item -> !item.exact());
        assertThat(mapper.search(USER,"GEO_TOPIC","光禾",null,null)).hasSize(1).allMatch(item -> !item.exact());
        assertThat(mapper.search(USER,"GEO_TOPIC","品牌别名",null,null)).hasSize(1).allMatch(item -> !item.exact());
        assertThat(mapper.search(USER,"GEO_TOPIC","别人的秘密别名",null,null)).isEmpty();
        assertThat(mapper.search(OTHER,"SPACE","光禾",null,null)).isEmpty();
        var all=mapper.search(USER,"SPACE","店",null,null);
        assertThat(all).hasSize(6); assertThat(all.getFirst().totalMatches()).isEqualTo(8);
        ObjectMapper json=JsonMapper.builder().addModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        var contexts=mock(RunContextService.class); var context=mock(InternalRunContextDtos.RunContext.class);
        when(context.protocolFeatures()).thenReturn(new InternalRunContextDtos.ProtocolFeatures(true,true));
        when(contexts.get(RUN,"ref")).thenReturn(context);
        var catalog=new AgentRuntimeCatalog();
        var agent=catalog.freeze(new AgentPlatformDtos.AgentIdentity("98770","geo","1.0.0","GEO",null,
                List.of("chat","resources.read","geo.read")));
        when(context.agentIdentity()).thenReturn(agent);
        var platform=mock(AgentPlatformService.class);
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(agent);
        var authorization=new RunToolAuthorizationService(sessions.getMapper(ConversationMapper.class),
                sessions.getMapper(AccountMapper.class),Optional.of(platform),catalog);
        var ids=mock(SnowflakeIdGenerator.class); var sequence=new AtomicLong(98800);
        when(ids.nextId()).thenAnswer(i -> sequence.incrementAndGet());
        var service=transactional(new RunResourceService(mapper,sessions.getMapper(ConversationMapper.class),contexts,authorization,
                sessions.getMapper(AccountMapper.class),sessions.getMapper(BusinessSpaceMapper.class),sessions.getMapper(ProductMapper.class),
                sessions.getMapper(GeoMapper.class),sessions.getMapper(GeoTaskMapper.class),sessions.getMapper(AssetMapper.class),
                mock(GeoChatContextFactory.class),new CatalogNameNormalizer(),ids,json),dataSource);
        var search=service.search(RUN,"ref",new SearchRequest("topic","GEO_TOPIC","咖啡关键词",null));
        assertThat(search.status()).isEqualTo("UNIQUE");
        var conversationMapper=sessions.getMapper(ConversationMapper.class);
        try(var runtime=dataSource.getConnection()) {
            execute(runtime,"INSERT INTO business.conversation_message(id,conversation_id,user_id,role,content_type,content,sequence_no,created_at,updated_at)"
                    +" VALUES (98713,98710,98701,'ASSISTANT','INTERACTION','{\"interaction\":{\"interactionType\":\"RESOURCE_SELECTION\",\"candidateSetId\":\""
                    +search.candidateSetId()+"\"}}',3,now(),now());"
                    +"INSERT INTO business.conversation_message(id,conversation_id,user_id,role,content_type,content,reply_to_message_id,client_message_id,idempotency_key,request_fingerprint,sequence_no,created_at,updated_at)"
                    +" VALUES (98714,98710,98701,'USER','TEXT','{}',98713,gen_random_uuid(),gen_random_uuid(),repeat('a',64),4,now(),now());"
                    +"INSERT INTO business.agent_run(id,conversation_id,user_id,request_message_id,status,context_snapshot,context_ref_hash,context_expires_at,deadline_at,created_at,updated_at)"
                    +" VALUES ('c19e66f5-c6f0-4b3e-a96b-2e75c4c33981',98710,98701,98714,'COMPLETED','{}',repeat('a',64),now()+interval '1 day',now()+interval '1 day',now(),now())");
        }
        assertThat(conversationMapper.hasInteractionConsumptionEvidence(USER,CONVERSATION,"98713")).isFalse();
        var failedSelection=new ReadRequest("failed-selection","ASSET_INVENTORY",search.candidateSetId(),"choice-98750",null,null,null);
        assertCode(() -> service.read(RUN,"ref",failedSelection),ErrorCode.VALIDATION_FAILED);
        assertThat(conversationMapper.hasInteractionConsumptionEvidence(USER,CONVERSATION,"98713")).isFalse();
        var read=new ReadRequest("no-report","LATEST_GEO_REPORT",search.candidateSetId(),"choice-98750",null,null,null);
        var result=service.read(RUN,"ref",read);
        assertThat(result.reportId()).isNull(); assertThat(result.reportAttempt()).isNull();
        assertThat(result.projection().path("status").asText()).isEqualTo("NO_REPORT");
        assertThat(service.read(RUN,"ref",read)).isEqualTo(result);
        assertThat(mapper.callCount(RUN)).isEqualTo(3);
        assertThat(conversationMapper.hasInteractionConsumptionEvidence(USER,CONVERSATION,"98713")).isTrue();
        assertThat(mapper.consumed(USER,CONVERSATION,RUN,search.candidateSetId(),"choice-98750")).isTrue();
        assertThat(mapper.findReference(OTHER,CONVERSATION,Long.parseLong(result.observationId()),0)).isEmpty();
        assertThat(service.lastReportReadContext(USER,CONVERSATION,SPACE,null)).isEqualTo(result);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(4)) {
            var work=new ArrayList<Future<SearchResponse>>();
            for(int n=0;n<4;n++) work.add(executor.submit(() -> { start.await();
                return service.search(RUN,"ref",new SearchRequest("concurrent","SPACE","品牌别名",null)); }));
            start.countDown();
            String same=null;
            for(var future:work) { var saved=future.get(20,TimeUnit.SECONDS);
                if(same==null) same=saved.candidateSetId(); else assertThat(saved.candidateSetId()).isEqualTo(same); }
        }
        assertThat(mapper.callCount(RUN)).isEqualTo(4);
        var pending=service.pendingSearch(USER,CONVERSATION,SPACE,null);
        // Authorized failed source-purpose reads commit controlled failure receipts and do not consume.
        for(int n=0;n<2;n++) {
            var invalid=new ReadRequest("failure-"+n,"GEO_ANSWER_EVIDENCE",pending.candidateSetId(),"choice-98730",null,null,List.of("ref"));
            assertCode(() -> service.read(RUN,"ref",invalid),ErrorCode.VALIDATION_FAILED);
            assertCode(() -> service.read(RUN,"ref",invalid),ErrorCode.VALIDATION_FAILED);
        }
        assertThat(mapper.callCount(RUN)).isEqualTo(6);
        assertCode(() -> service.search(RUN,"ref",new SearchRequest("seventh","SPACE","品牌别名",null)),ErrorCode.TASK_STATE_CONFLICT);
        assertThat(service.pendingSearch(USER,CONVERSATION,SPACE,null)).isNotNull();
        try(var runtime=dataSource.getConnection()) {
            assertThat(number(runtime,"SELECT count(*) FROM business.business_task")).isZero();
            assertThat(number(runtime,"SELECT count(*) FROM business.point_ledger")).isZero();
            assertThat(number(runtime,"SELECT count(*) FROM business.point_reservation")).isZero();
            assertThatThrownBy(() -> execute(runtime,"CREATE TABLE business.forbidden_resource_gate_ddl(id bigint)"))
                    .isInstanceOfSatisfying(SQLException.class,e -> assertThat(e.getSQLState()).isEqualTo("42501"));
            execute(runtime,"INSERT INTO business.conversation_message(id,conversation_id,user_id,role,content_type,content,client_message_id,"
                    +"idempotency_key,request_fingerprint,sequence_no,created_at,updated_at) VALUES"
                    +"(98799,98710,98701,'USER','TEXT','{\"resourceContextReset\":true}',gen_random_uuid(),gen_random_uuid(),repeat('a',64),5,now(),now())");
        }
        assertThat(mapper.resetSequence(USER,CONVERSATION)).isEqualTo(5);
        assertThat(service.pendingSearch(USER,CONVERSATION,SPACE,null)).isNull();
        assertThat(service.lastReadContext(USER,CONVERSATION,SPACE,null)).isNull();
        System.out.println("DB_GATE conversation_resources=real_mappers_owner_alias_literal_total_count_concurrent_single_receipt_failure_budget_replay_reset_zero_task_points_passed");
    }
    private void seed(Connection connection) throws SQLException {
        execute(connection,"""
                INSERT INTO business.account(id,username,username_normalized,password_hash,role,status,display_name,created_at,updated_at)
                VALUES (98701,'resource_gate','resource_gate',repeat('a',32),'USER','ACTIVE','Resource Gate',now(),now()),
                       (98702,'resource_other','resource_other',repeat('a',32),'USER','ACTIVE','Other Gate',now(),now());
                INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,created_at,updated_at)
                SELECT 98729+n,98701,CASE WHEN n=1 THEN '抖店_%' ELSE '店'||n END,
                       CASE WHEN n=1 THEN '抖店_%' ELSE '店'||n END,CASE WHEN n<3 THEN '光禾' ELSE '品牌'||n END,now(),now()
                  FROM generate_series(1,8) n;
                INSERT INTO business.space_brand_alias(id,user_id,space_id,alias,alias_normalized,created_at)
                  VALUES (98739,98701,98730,'品牌别名','品牌别名',now());
                INSERT INTO business.product(id,user_id,space_id,name,name_normalized,created_at,updated_at)
                  VALUES (98740,98701,98730,'美式咖啡','美式咖啡',now(),now());
                INSERT INTO business.product_alias(id,user_id,space_id,product_id,alias,alias_normalized,created_at)
                  VALUES (98741,98701,98730,98740,'咖啡别名','咖啡别名',now());
                INSERT INTO business.geo_topic(id,user_id,space_id,product_id,name,name_normalized,created_at,updated_at)
                  VALUES (98750,98701,98730,98740,'咖啡可见度','咖啡可见度',now(),now());
                INSERT INTO business.geo_keyword(id,user_id,space_id,topic_id,sequence_no,keyword_type,keyword_text,text_normalized,created_at,updated_at)
                  VALUES (98751,98701,98730,98750,1,'CORE','咖啡关键词','咖啡关键词',now(),now());
                INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,created_at,updated_at)
                  VALUES (98780,98702,'other','other','Other',now(),now());
                INSERT INTO business.product(id,user_id,space_id,name,name_normalized,created_at,updated_at)
                  VALUES (98781,98702,98780,'Other coffee','other coffee',now(),now());
                INSERT INTO business.product_alias(id,user_id,space_id,product_id,alias,alias_normalized,created_at)
                  VALUES (98782,98702,98780,98781,'别人的秘密别名','别人的秘密别名',now());
                INSERT INTO business.geo_topic(id,user_id,space_id,product_id,name,name_normalized,created_at,updated_at)
                  VALUES (98783,98702,98780,98781,'other topic','other topic',now(),now());
                INSERT INTO business.conversation(id,user_id,title,selected_space_id,created_at,updated_at)
                  VALUES (98710,98701,'Resource gate',98730,now(),now());
                INSERT INTO business.conversation_message(id,conversation_id,user_id,role,content_type,content,client_message_id,idempotency_key,request_fingerprint,sequence_no,created_at,updated_at)
                  VALUES (98711,98710,98701,'USER','TEXT','{}',gen_random_uuid(),gen_random_uuid(),repeat('a',64),1,now(),now());
                INSERT INTO business.conversation_message(id,conversation_id,user_id,role,content_type,content,sequence_no,created_at,updated_at)
                  VALUES (98712,98710,98701,'ASSISTANT','TEXT','{}',2,now(),now());
                INSERT INTO business.agent_run(id,conversation_id,user_id,request_message_id,response_message_id,selected_space_id,status,context_snapshot,context_ref_hash,context_expires_at,deadline_at,created_at,updated_at)
                  VALUES ('c19e66f5-c6f0-4b3e-a96b-2e75c4c33980',98710,98701,98711,98712,98730,'RUNNING','{}',repeat('a',64),now()+interval '1 day',now()+interval '1 day',now(),now());
                INSERT INTO business.article(id,user_id,space_id,product_id,title,version,created_at,updated_at)
                  VALUES (98901,98701,98730,98740,'Gate frozen report article',3,now()-interval '1 day',now());
                INSERT INTO business.task_draft(id,user_id,conversation_id,draft_source,operation_scope,idempotency_key,request_hash,
                  task_type,parameter_schema_version,space_id,product_id,parameters,input_snapshot,input_hash,pricing_version_id,quoted_points,status,expires_at,created_at,updated_at)
                  VALUES (98902,98701,98710,'FORM','TASK_DRAFT_CREATE',gen_random_uuid(),repeat('a',64),
                    'ARTICLE',2,98730,98740,'{"mode":"TITLE","articleId":"98901","articleVersion":1,"geoReportId":"501","geoRecommendationId":"501:recommendation:1"}',
                    '{}',repeat('b',64),1,1,'CONFIRMED',now()-interval '1 day',now()-interval '1 day',now()-interval '1 day'),
                    (98903,98701,98710,'FORM','TASK_DRAFT_CREATE',gen_random_uuid(),repeat('a',64),
                    'ARTICLE',2,98730,98740,'{"mode":"BODY","articleId":"98901","articleVersion":3,"geoReportId":"501","geoRecommendationId":null}',
                    '{}',repeat('b',64),1,1,'EXPIRED',now()-interval '10 minutes',now()-interval '1 hour',now()-interval '10 minutes'),
                    (98904,98701,98710,'FORM','TASK_DRAFT_CREATE',gen_random_uuid(),repeat('a',64),
                    'ARTICLE',2,98730,98740,'{"mode":"BODY","articleId":"98901","articleVersion":3,"geoReportId":"501","geoRecommendationId":null}',
                    '{}',repeat('b',64),1,1,'ABANDONED',now()+interval '10 minutes',now(),now());
                """);
    }
    private RunResourceService transactional(RunResourceService service,DataSource source) {
        var proxy=new ProxyFactory(service); proxy.setProxyTargetClass(true);
        var transaction=new TransactionInterceptor(); transaction.setTransactionManager(new JdbcTransactionManager(source));
        transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); proxy.addAdvice(transaction);
        return (RunResourceService)proxy.getProxy();
    }
    private void assertCode(Runnable work,ErrorCode code) { assertThatThrownBy(work::run)
            .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(code)); }
    private static String scalar(Connection c,String sql) throws SQLException {
        try(var statement=c.createStatement();var result=statement.executeQuery(sql)) {
            if(!result.next()) throw new IllegalStateException("No Gate scalar result"); return result.getString(1);
        }
    }
    private static long number(Connection c,String sql) throws SQLException { return Long.parseLong(scalar(c,sql)); }
    private static void execute(Connection c,String sql) throws SQLException { try(var statement=c.createStatement()) { statement.execute(sql); } }
    private static String required(String name) { String value=System.getenv(name);
        if(value==null || value.isBlank()) throw new IllegalStateException("Missing Gate environment "+name); return value; }
}
