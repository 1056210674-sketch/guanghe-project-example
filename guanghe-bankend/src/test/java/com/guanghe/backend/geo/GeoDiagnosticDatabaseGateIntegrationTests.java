package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.agentplatform.*;
import com.guanghe.backend.auth.account.*;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.*;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.task.PaidTaskProperties;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
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
import static org.assertj.core.api.Assertions.*;

/** Real MyBatis, ownership, card history and lease fencing; explicit fixture model outputs only. */
@EnabledIfEnvironmentVariable(named="DATABASE_GATE_ENABLED",matches="true")
class GeoDiagnosticDatabaseGateIntegrationTests {
    private static final long USER=96101,OTHER=96102,PROJECT=96110,CONVERSATION=96120;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @Test void v060ToV061AndSixCardsUseOwnedDurablePreparationWithoutTasksOrWalletWrites() throws Exception {
        String database=required("GATE_EXPECTED_DATABASE"),url=required("GATE_MIGRATION_DB_URL"),
            migrationUser=required("GATE_MIGRATION_DB_USERNAME"),password=required("GATE_MIGRATION_DB_PASSWORD"),
            runtimeUrl=required("GATE_RUNTIME_DB_URL"),runtimeUser=required("GATE_RUNTIME_DB_USERNAME"),runtimePassword=required("GATE_RUNTIME_DB_PASSWORD");
        assertThat(database).matches("^zhixiaohe_gate_[a-z0-9_]+$");
        assertThat(runtimeUser).matches("^gate_runtime_[a-z0-9_]+$");assertThat(runtimeUser).isNotEqualTo(migrationUser);
        try(var migration=DriverManager.getConnection(url,migrationUser,password);var runtime=DriverManager.getConnection(runtimeUrl,runtimeUser,runtimePassword)) {
            assertThat(scalar(migration,"SELECT current_database()")).isEqualTo(database);
            assertThat(scalar(runtime,"SELECT current_database()")).isEqualTo(database);
        }
        flyway(url,migrationUser,password,"060").migrate();
        try(var c=DriverManager.getConnection(url,migrationUser,password)) {
            execute(c,"GRANT USAGE ON SCHEMA business TO \""+runtimeUser+"\"; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA business TO \""+runtimeUser+"\"; "
                +"ALTER DEFAULT PRIVILEGES IN SCHEMA business GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO \""+runtimeUser+"\"");
            execute(c,"""
                INSERT INTO business.account(id,username,username_normalized,password_hash,role,status,credential_state,created_at,updated_at)
                VALUES(96101,'diagnostic_gate','diagnostic_gate',repeat('a',32),'USER','ACTIVE','ACTIVE',now(),now()),
                      (96102,'diagnostic_other','diagnostic_other',repeat('a',32),'USER','ACTIVE','ACTIVE',now(),now());
                INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,brand_profile,project_status,storage_kind,created_at,updated_at)
                VALUES(96110,96101,'诊断客户','诊断客户',NULL,'{}','ACTIVE','PROJECT',now(),now()),
                      (96111,96102,'其他客户','其他客户',NULL,'{}','ACTIVE','PROJECT',now(),now());
                INSERT INTO business.conversation(id,user_id,title,created_at,updated_at)
                VALUES(96120,96101,'诊断验收',now(),now()),(96121,96102,'其他诊断',now(),now());
                INSERT INTO business.agent_memory_entry(id,user_id,project_id,scope,kind,memory_key,label,value,version,source_type,created_at,updated_at)
                VALUES(96130,96101,96110,'PROJECT','FACT','geo.target_regions','目标地域','广州',1,'USER_INPUT',now(),now());
                """);
        }
        var upgrade=flyway(url,migrationUser,password,"061");assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();assertThat(upgrade.migrate().migrationsExecuted).isZero();
        var source=new PGSimpleDataSource();source.setUrl(runtimeUrl);source.setUser(runtimeUser);source.setPassword(runtimePassword);
        source.setConnectTimeout(5);source.setSocketTimeout(30);source.setOptions("-c statement_timeout=20000 -c lock_timeout=15000");
        try(var c=source.getConnection()) {
            for(String table:List.of("geo_diagnostic_preparation","geo_diagnostic_card","geo_diagnostic_generation"))
                assertThat(scalar(c,"SELECT has_table_privilege(current_user,'business."+table+"','SELECT,INSERT,UPDATE,DELETE')")).isEqualTo("t");
            reject(c,"CREATE TABLE business.diagnostic_ddl_forbidden(id bigint)","42501");
        }
        var config=new Configuration(new Environment("diagnostic-gate",new SpringManagedTransactionFactory(),source));
        config.setMapUnderscoreToCamelCase(true);
        for(Class<?> type:List.of(GeoDiagnosticMapper.class,ConversationMapper.class,GeoPeriodReportMapper.class,AgentPlatformMapper.class))config.addMapper(type);
        var sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        var ids=new SnowflakeIdGenerator(new SnowflakeProperties(1,1));var names=new CatalogNameNormalizer();
        var agents=transactional(new AgentPlatformService(sessions.getMapper(AgentPlatformMapper.class),new AgentPlatformProperties(),ids,names,json),source);
        agents.bindConversation(USER,CONVERSATION,null,PROJECT);agents.bindConversation(OTHER,96121,null,96111L);
        var pricing=new PaidTaskProperties();pricing.setGeoConfiguredPlatforms(Set.of("DEEPSEEK","DOUBAO"));
        pricing.setGeoTestPricingVersionId(2);pricing.setGeoTestUnitPoints(2);pricing.setGeoMaxProviderCalls(1000);
        var mapper=sessions.getMapper(GeoDiagnosticMapper.class);
        var service=transactional(new GeoDiagnosticService(mapper,sessions.getMapper(ConversationMapper.class),sessions.getMapper(GeoPeriodReportMapper.class),
            agents,json,ids,names,pricing),source);
        var request=new GeoDiagnosticDtos.Create(UUID.randomUUID(),Long.toString(CONVERSATION));
        var first=service.create(user(USER),request);long id=Long.parseLong(first.id());
        assertThat(first.profile().region()).isEqualTo("广州");assertThat(first.submissionEnabled()).isFalse();
        assertThat(first.cards()).hasSize(1);assertThat(service.create(user(USER),request).id()).isEqualTo(first.id());
        assertThat(mapper.cards(id)).hasSize(1);
        assertThatThrownBy(()->service.get(user(OTHER),id)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.create(user(USER),new GeoDiagnosticDtos.Create(UUID.randomUUID(),"96121"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(999,1,null,null,null,null,null,false)))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_VERSION_CONFLICT));
        var profile=new GeoDiagnosticDtos.Profile("星河咖啡",null,"现磨咖啡与冷萃，服务广州上班族",List.of("星河"),"广州");
        var saved=service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(first.version(),1,profile,null,null,null,null,false));
        assertThat(saved.profile().brandTerms()).contains("星河咖啡","星河");
        var genKey=UUID.randomUUID();var queued=service.generate(user(USER),id,new GeoDiagnosticDtos.Generate(saved.version(),GeoDiagnosticDtos.Phase.BRAND_RESEARCH,genKey));
        assertThat(queued.step()).isEqualTo(2);assertThat(queued.generation().status()).isEqualTo("QUEUED");
        assertThat(service.generate(user(USER),id,new GeoDiagnosticDtos.Generate(saved.version(),GeoDiagnosticDtos.Phase.BRAND_RESEARCH,genKey)).version()).isEqualTo(queued.version());
        assertThatThrownBy(()->service.generate(user(USER),id,new GeoDiagnosticDtos.Generate(queued.version(),GeoDiagnosticDtos.Phase.BRAND_RESEARCH,genKey)))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        var originalSources=new ArrayList<GeoDiagnosticDtos.Source>();originalSources.add(sourceOne());
        for(int i=2;i<=32;i++)originalSources.add(new GeoDiagnosticDtos.Source("old-"+i,"旧来源 "+i,"https://example.com/old-"+i,"旧原文 "+i));
        complete(service,GeoDiagnosticDtos.Phase.BRAND_RESEARCH,profile,List.of(),List.of(),originalSources);
        try(var c=source.getConnection()) {
            assertThat(scalar(c,"SELECT usage->>'promptVersion' FROM business.geo_diagnostic_generation WHERE diagnostic_id="+id
                +" AND status='SUCCEEDED' ORDER BY id DESC LIMIT 1")).isEqualTo("GEO_DIAGNOSTIC_PREPARATION_V4");
        }
        var researched=service.get(user(USER),id);assertThat(researched.generation()).isNull();assertThat(researched.sources()).hasSize(32);
        var originalCard=researched.cards().getLast();assertThat(originalCard.snapshot().sources()).containsExactlyElementsOf(originalSources);
        assertThatThrownBy(()->service.generate(user(USER),id,new GeoDiagnosticDtos.Generate(researched.version(),GeoDiagnosticDtos.Phase.BRAND_RESEARCH,UUID.randomUUID())))
            .isInstanceOf(BusinessException.class);
        assertThat(researched.warnings()).containsExactly("官网信息请确认");
        var advanced=service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(researched.version(),2,researched.profile(),null,null,null,null,true));
        assertThat(advanced.step()).isEqualTo(3);
        complete(service,GeoDiagnosticDtos.Phase.COMPETITORS,null,List.of(Map.of("name","蓝星咖啡","brandTerms",List.of("蓝星"),"reason","同类现磨咖啡","sourceIds",List.of("source-one"))),List.of());
        var competitors=service.get(user(USER),id);var old=competitors.competitors().getFirst();
        var changed=new GeoDiagnosticDtos.Competitor(old.id(),old.name(),old.brandTerms(),"用户补充：服务同类通勤人群",old.sourceIds(),"USER",false);
        var edited=service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(competitors.version(),3,null,List.of(changed),null,null,null,false));
        assertThat(edited.competitors().getFirst().edited()).isTrue();
        var regen=service.generate(user(USER),id,new GeoDiagnosticDtos.Generate(edited.version(),GeoDiagnosticDtos.Phase.COMPETITORS,UUID.randomUUID()));
        var stale=service.claimNext().orElseThrow();
        var rewound=service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(regen.version(),2,regen.profile(),null,null,null,null,false));
        service.completed(stale,response(stale,null,List.of(Map.of("name","迟到品牌","brandTerms",List.of(),"reason","迟到","sourceIds",List.of())),List.of()));
        assertThat(service.get(user(USER),id).version()).isEqualTo(rewound.version());
        assertThat(service.get(user(USER),id).competitors()).extracting(GeoDiagnosticDtos.Competitor::name).doesNotContain("迟到品牌");
        var again=service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(rewound.version(),2,rewound.profile(),null,null,null,null,true));
        var freshSources=new ArrayList<GeoDiagnosticDtos.Source>();
        for(int i=1;i<=5;i++)freshSources.add(new GeoDiagnosticDtos.Source("fresh-"+i,"新竞品来源 "+i,"https://example.com/fresh-"+i,"新竞品原文 "+i));
        complete(service,GeoDiagnosticDtos.Phase.COMPETITORS,null,List.of(Map.of("name","青星咖啡","brandTerms",List.of("青星"),"reason","同类咖啡","sourceIds",List.of("fresh-3"))),List.of(),freshSources);
        var refreshed=service.get(user(USER),id);assertThat(refreshed.competitors()).extracting(GeoDiagnosticDtos.Competitor::name).contains("蓝星咖啡","青星咖啡");
        assertThat(refreshed.competitors().getFirst().reason()).contains("用户补充");
        assertThat(refreshed.sources()).hasSize(32).contains(sourceOne()).containsAll(freshSources);
        assertThat(refreshed.competitors()).anySatisfy(c->{assertThat(c.name()).isEqualTo("青星咖啡");assertThat(c.sourceIds()).containsExactly("fresh-3");});
        assertThat(refreshed.cards()).filteredOn(card->card.messageId().equals(originalCard.messageId())).singleElement()
            .satisfies(card->assertThat(card.snapshot().sources()).containsExactlyElementsOf(originalSources));
        assertThat(refreshed.sources()).extracting(GeoDiagnosticDtos.Source::id).doesNotContain("old-32");
        var discovery=service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(refreshed.version(),3,null,refreshed.competitors(),null,null,null,true));
        assertThat(discovery.step()).isEqualTo(4);
        complete(service,GeoDiagnosticDtos.Phase.DISCOVERY_QUESTIONS,null,List.of(),List.of(
            Map.of("text","广州有哪些现磨咖啡店？"),Map.of("text","广州上班族适合选择哪些咖啡店？"),Map.of("text","广州有哪些冷萃值得尝试？"),
            Map.of("text","广州通勤时有哪些方便取餐的咖啡店？"),Map.of("text","广州有哪些咖啡门店适合日常喝？")));
        var questions=service.get(user(USER),id);
        assertThatThrownBy(()->service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(questions.version(),4,null,null,
            List.of(new GeoDiagnosticDtos.Question("bad","广州蓝星咖啡怎么样？","USER",true)),null,null,false))).isInstanceOf(BusinessException.class);
        service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(questions.version(),4,null,null,questions.discoveryQuestions(),null,null,true));
        complete(service,GeoDiagnosticDtos.Phase.REPUTATION_QUESTIONS,null,List.of(),List.of(
            Map.of("text","星河咖啡好不好？"),Map.of("text","星河咖啡是否值得考虑？"),Map.of("text","星河咖啡的体验如何？")));
        var reputation=service.get(user(USER),id);assertThat(reputation.reputationQuestions()).hasSize(3);
        var finalCard=service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(reputation.version(),5,null,null,null,reputation.reputationQuestions(),null,true));
        assertThat(finalCard.step()).isEqualTo(6);assertThat(finalCard.platformCodes()).containsExactlyInAnyOrder("DEEPSEEK","DOUBAO");
        // Freeze the actual JSONB-owned input, not the DTO's different property order.
        ObjectNode quotedInput=(ObjectNode)json.readTree(mapper.find(USER,id).orElseThrow().dataJson());
        quotedInput.set("platformCodes",json.valueToTree(finalCard.platformCodes()));quotedInput.put("pricingVersionId","2");
        var quoted=service.quote(user(USER),id,new GeoDiagnosticDtos.QuoteRequest(finalCard.version(),finalCard.platformCodes()));
        assertThat(quoted.quote().expectedSamples()).isEqualTo(16);assertThat(quoted.quote().points()).isEqualTo(32);
        assertThat(quoted.quote().version()).isEqualTo(quoted.version());assertThat(quoted.submissionEnabled()).isFalse();
        assertThat(quoted.quote().inputHash()).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(json.writeValueAsBytes(quotedInput))));
        assertThat(quoted.cards().getLast().snapshot().sources()).containsAll(freshSources).hasSize(32);
        pricing.setGeoMaxProviderCalls(60);
        pricing.setGeoConfiguredPlatforms(Set.of("DEEPSEEK","DOUBAO","QWEN","WENXIN","KIMI","YUANBAO","ZHIPU"));
        quoted=service.quote(user(USER),id,new GeoDiagnosticDtos.QuoteRequest(quoted.version(),pricing.getGeoConfiguredPlatforms().stream().sorted().toList()));
        assertThat(quoted.quote().expectedSamples()).isEqualTo(56);assertThat(quoted.quote().points()).isEqualTo(112);
        assertThat(pricing.getGeoMaxProviderCalls()).isEqualTo(60);assertThat(pricing.getGeoDiagnosticMaxProviderCalls()).isEqualTo(200);
        assertThat(service.list(user(USER),CONVERSATION,PROJECT,1,20).items()).hasSize(1);
        var revisit=service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(quoted.version(),3,null,quoted.competitors(),null,null,null,false));
        assertThat(revisit.quote()).isNull();
        var pending=service.generate(user(USER),id,new GeoDiagnosticDtos.Generate(revisit.version(),GeoDiagnosticDtos.Phase.COMPETITORS,UUID.randomUUID()));
        var abandoned=service.claimNext().orElseThrow();
        try(var c=source.getConnection();var statement=c.prepareStatement("UPDATE business.geo_diagnostic_generation SET lease_expires_at=? WHERE id=?")){
            statement.setObject(1,java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(5));statement.setLong(2,abandoned.id());statement.executeUpdate();}
        assertThat(service.claimNext()).isEmpty();
        assertThat(service.get(user(USER),id).generation().status()).isEqualTo("FAILED");
        service.completed(abandoned,response(abandoned,null,List.of(),List.of()));
        assertThat(service.get(user(USER),id).version()).isEqualTo(pending.version());
        service.generate(user(USER),id,new GeoDiagnosticDtos.Generate(pending.version(),GeoDiagnosticDtos.Phase.COMPETITORS,UUID.randomUUID()));
        var explicitRetry=service.claimNext().orElseThrow();
        try(var c=source.getConnection()) {
            assertThat(scalar(c,"SELECT count(*) FROM business.business_task")).isEqualTo("0");
            assertThat(scalar(c,"SELECT count(*) FROM business.point_ledger")).isEqualTo("0");
            assertThat(scalar(c,"SELECT count(*) FROM business.outbox_event")).isEqualTo("0");
            assertThat(scalar(c,"SELECT count(*) FROM business.agent_run")).isEqualTo("0");
            assertThat(scalar(c,"SELECT count(*) FROM business.conversation_message WHERE agent_run_id IS NOT NULL OR status<>'COMPLETED'")).isEqualTo("0");
            reject(c,"UPDATE business.geo_diagnostic_preparation SET user_id=96102 WHERE id="+id,"23514");
            reject(c,"UPDATE business.geo_diagnostic_card SET user_id=96102 WHERE diagnostic_id="+id,"23514");
            reject(c,"UPDATE business.geo_diagnostic_card SET snapshot='{}' WHERE diagnostic_id="+id,"23514");
            reject(c,"UPDATE business.geo_diagnostic_generation SET input='{}' WHERE diagnostic_id="+id,"23514");
            assertThat(scalar(c,"SELECT title FROM business.conversation WHERE id=96120")).isEqualTo("诊断验收");
            reject(c,"UPDATE business.geo_diagnostic_generation SET claim_token=NULL WHERE status='RUNNING'","23514");
        }
        service.failed(explicitRetry,"PREPARATION_UNAVAILABLE");
        var beforeRename=service.get(user(USER),id);
        var renamedProfile=new GeoDiagnosticDtos.Profile("河川咖啡","https://example.com/new-brand","用户明确填写的新品牌介绍",List.of("河川"),"福建");
        var renamed=service.patch(user(USER),id,new GeoDiagnosticDtos.Patch(beforeRename.version(),2,renamedProfile,null,null,null,null,false));
        assertThat(renamed.sources()).isEmpty();assertThat(renamed.warnings()).isEmpty();
        assertThat(renamed.profile().introduction()).isEqualTo(renamedProfile.introduction());
        assertThat(renamed.profile().website()).isEqualTo(renamedProfile.website());assertThat(renamed.profile().region()).isEqualTo("福建");
        assertThat(renamed.profile().brandTerms()).contains("河川咖啡","河川");
        assertThat(renamed.cards()).anySatisfy(card->assertThat(card.snapshot().sources()).isNotEmpty());
        try(var c=source.getConnection()){execute(c,"INSERT INTO business.conversation(id,user_id,title,created_at,updated_at) VALUES(96122,96101,'新会话',now(),now())");}
        agents.bindConversation(USER,96122,null,PROJECT);
        var defaultChat=service.create(user(USER),new GeoDiagnosticDtos.Create(UUID.randomUUID(),"96122"));
        service.patch(user(USER),Long.parseLong(defaultChat.id()),new GeoDiagnosticDtos.Patch(defaultChat.version(),1,profile,null,null,null,null,false));
        try(var c=source.getConnection()){assertThat(scalar(c,"SELECT title FROM business.conversation WHERE id=96122")).isEqualTo("品牌诊断 · 星河咖啡");}
        System.out.println("DB_GATE geo_diagnostic=v060_v061_default_acl_six_cards_scope_replay_versions_manual_retention_stale_fencing_quote_zero_paid_passed");
    }
    private void complete(GeoDiagnosticService service,GeoDiagnosticDtos.Phase phase,GeoDiagnosticDtos.Profile profile,List<?> competitors,List<?> questions) {
        complete(service,phase,profile,competitors,questions,List.of(sourceOne()));
    }
    private void complete(GeoDiagnosticService service,GeoDiagnosticDtos.Phase phase,GeoDiagnosticDtos.Profile profile,List<?> competitors,List<?> questions,List<GeoDiagnosticDtos.Source> sources) {
        var claim=service.claimNext().orElseThrow();assertThat(claim.request().path("phase").asText()).isEqualTo(phase.name());
        service.completed(claim,response(claim,profile,competitors,questions,sources));
    }
    private ObjectNode response(GeoDiagnosticService.Claim claim,GeoDiagnosticDtos.Profile profile,List<?> competitors,List<?> questions) {
        return response(claim,profile,competitors,questions,List.of(sourceOne()));
    }
    private static GeoDiagnosticDtos.Source sourceOne() { return new GeoDiagnosticDtos.Source("source-one","真实来源标题","http://example.com/brand",""); }
    private ObjectNode response(GeoDiagnosticService.Claim claim,GeoDiagnosticDtos.Profile profile,List<?> competitors,List<?> questions,List<GeoDiagnosticDtos.Source> sources) {
        ObjectNode result=json.createObjectNode();result.put("jobId",Long.toString(claim.id())).put("inputHash",claim.request().path("inputHash").asText())
            .put("promptVersion","GEO_DIAGNOSTIC_PREPARATION_V4").put("searchRequestCount",1);
        ObjectNode data=result.putObject("result");data.set("profile",json.valueToTree(profile));data.set("competitors",json.valueToTree(competitors));data.set("questions",json.valueToTree(questions));
        data.set("sources",json.valueToTree(sources));
        data.set("warnings",json.valueToTree(List.of("官网信息请确认")));
        result.set("synthesisUsage",json.valueToTree(Map.of("provider","DEEPSEEK","model","deepseek-flash","inputTokens",100,"outputTokens",100,"providerCallCount",1,"latencyMs",1)));
        return result;
    }
    private static <T>T transactional(T service,DataSource source) {
        var proxy=new ProxyFactory(service);proxy.setProxyTargetClass(true);var transaction=new TransactionInterceptor();
        transaction.setTransactionManager(new JdbcTransactionManager(source));transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(transaction);
        @SuppressWarnings("unchecked") T result=(T)proxy.getProxy();return result;
    }
    private static AuthenticatedAccount user(long id){return new AuthenticatedAccount(id,"fixture",AccountRole.USER,1,AccountStatus.ACTIVE,null,null,null);}
    private static String required(String name){String value=System.getenv(name);if(value==null ||value.isBlank())throw new IllegalStateException("Missing Gate variable: "+name);return value;}
    private static Flyway flyway(String url,String user,String password,String version){return Flyway.configure().dataSource(url,user,password).schemas("business").defaultSchema("business").createSchemas(true).target(version).load();}
    private static String scalar(Connection c,String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){if(!r.next())throw new IllegalStateException("Missing scalar");return r.getString(1);}}
    private static void execute(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.execute(sql);}}
    private static void reject(Connection c,String sql,String state){assertThatThrownBy(()->execute(c,sql)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo(state));}
}
