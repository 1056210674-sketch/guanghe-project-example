package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.agentplatform.*;
import com.guanghe.backend.asset.*;
import com.guanghe.backend.auth.account.*;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.notification.*;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.id.*;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.task.*;
import java.net.URI;
import java.sql.*;
import java.time.*;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exact isolated V061 -> V062, actual Java admission/sampling accounting and offline fixture delivery only. */
@EnabledIfEnvironmentVariable(named="DATABASE_GATE_ENABLED",matches="true")
class GeoDiagnosticDeliveryDatabaseGateIntegrationTests {
    static final long USER=96201,OTHER=96202,PROJECT=96210,CONVERSATION=96220;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @Test void oneFormalTaskSeparatesSamplingAnalysisPrivatePdfAndEveryRecoveryWithoutResampling() throws Exception {
        String database=required("GATE_EXPECTED_DATABASE"),url=required("GATE_MIGRATION_DB_URL"),migrationUser=required("GATE_MIGRATION_DB_USERNAME"),
            password=required("GATE_MIGRATION_DB_PASSWORD"),runtimeUrl=required("GATE_RUNTIME_DB_URL"),runtimeUser=required("GATE_RUNTIME_DB_USERNAME"),runtimePassword=required("GATE_RUNTIME_DB_PASSWORD");
        assertThat(database).matches("^zhixiaohe_gate_[a-z0-9_]+$");assertThat(runtimeUser).matches("^gate_runtime_[a-z0-9_]+$").isNotEqualTo(migrationUser);
        try(var c=DriverManager.getConnection(url,migrationUser,password)){assertThat(scalar(c,"SELECT current_database()")).isEqualTo(database);}
        flyway(url,migrationUser,password,"061").migrate();
        try(var c=DriverManager.getConnection(url,migrationUser,password)){
            execute(c,"GRANT USAGE ON SCHEMA business TO \""+runtimeUser+"\"; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA business TO \""+runtimeUser+"\"; "
                +"ALTER DEFAULT PRIVILEGES IN SCHEMA business GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO \""+runtimeUser+"\"");
            execute(c,"""
                INSERT INTO business.account(id,username,username_normalized,password_hash,role,status,credential_state,created_at,updated_at)
                VALUES(96201,'diag_delivery_gate','diag_delivery_gate',repeat('a',32),'USER','ACTIVE','ACTIVE',now(),now()),
                (96202,'diag_delivery_other','diag_delivery_other',repeat('a',32),'USER','ACTIVE','ACTIVE',now(),now());
                INSERT INTO business.point_account(user_id,available_points,frozen_points,version,created_at,updated_at)
                VALUES(96201,1000,0,1,now(),now()),(96202,1000,0,1,now(),now());
                INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,brand_profile,project_status,storage_kind,created_at,updated_at)
                VALUES(96210,96201,'诊断交付客户','诊断交付客户',NULL,'{}','ACTIVE','PROJECT',now(),now()),
                (96211,96202,'别人的客户','别人的客户',NULL,'{}','ACTIVE','PROJECT',now(),now());
                INSERT INTO business.conversation(id,user_id,title,created_at,updated_at)
                VALUES(96220,96201,'诊断交付验收',now(),now()),(96221,96202,'其他用户聊天',now(),now());
                """);
        }
        var upgrade=flyway(url,migrationUser,password,"062");assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);upgrade.validate();assertThat(upgrade.migrate().migrationsExecuted).isZero();
        var source=new PGSimpleDataSource();source.setUrl(runtimeUrl);source.setUser(runtimeUser);source.setPassword(runtimePassword);source.setConnectTimeout(5);source.setSocketTimeout(30);
        source.setOptions("-c statement_timeout=20000 -c lock_timeout=15000");
        try(var c=source.getConnection()){
            assertThat(scalar(c,"SELECT current_database()")).isEqualTo(database);
            for(String table:List.of("geo_diagnostic_delivery","geo_diagnostic_delivery_request"))assertThat(scalar(c,"SELECT has_table_privilege(current_user,'business."+table+"','SELECT,INSERT,UPDATE,DELETE')")).isEqualTo("t");
            reject(c,"CREATE TABLE business.diagnostic_delivery_ddl_forbidden(id bigint)","42501");
        }
        var config=new Configuration(new Environment("diagnostic-delivery-gate",new SpringManagedTransactionFactory(),source));config.setMapUnderscoreToCamelCase(true);
        for(Class<?> type:List.of(GeoDiagnosticMapper.class,GeoDiagnosticDeliveryMapper.class,GeoMapper.class,GeoTaskMapper.class,GeoPeriodReportMapper.class,
            TaskControlMapper.class,ConversationMapper.class,AgentPlatformMapper.class,MonitorExecutionMapper.class,NotificationMapper.class))config.addMapper(type);
        var sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));var ids=new SnowflakeIdGenerator(new SnowflakeProperties(1,2));var names=new CatalogNameNormalizer();
        var agents=transactional(new AgentPlatformService(sessions.getMapper(AgentPlatformMapper.class),new AgentPlatformProperties(),ids,names,json),source);
        agents.bindConversation(USER,CONVERSATION,null,PROJECT);agents.bindConversation(OTHER,96221,null,96211L);
        var pricing=new PaidTaskProperties();pricing.setEnabled(true);pricing.setGeoExecutionEnabled(true);pricing.setGeoDiagnosticEnabled(true);pricing.setGeoDiagnosticSubmissionEnabled(true);
        pricing.setTokenSecret("isolated-fixture-key-not-a-production-secret");pricing.setGeoTestPricingVersionId(2);pricing.setGeoTestUnitPoints(1);
        pricing.setGeoConfiguredPlatforms(Set.of("DOUBAO","DEEPSEEK","QWEN","WENXIN","KIMI","YUANBAO","ZHIPU"));
        var prepMapper=sessions.getMapper(GeoDiagnosticMapper.class);var deliveryMapper=sessions.getMapper(GeoDiagnosticDeliveryMapper.class);
        var tasks=sessions.getMapper(TaskControlMapper.class);var runs=sessions.getMapper(GeoTaskMapper.class);var conversations=sessions.getMapper(ConversationMapper.class);
        var geoRaw=new GeoTaskService(runs,sessions.getMapper(GeoMapper.class),tasks,conversations,mock(AuthorizedCatalogContextFactory.class),ids,json,pricing,names);
        var geo=transactional(geoRaw,source);
        var controlRaw=new TaskControlService(tasks,conversations,ids,json,pricing,new AssetIngestionProperties(),mock(AuthorizedCatalogContextFactory.class),geo);
        ReflectionTestUtils.setField(controlRaw,"agentPlatformService",agents);ReflectionTestUtils.setField(controlRaw,"monitorMapper",sessions.getMapper(MonitorExecutionMapper.class));
        ReflectionTestUtils.setField(controlRaw,"platformProperties",new AgentPlatformProperties());var control=transactional(controlRaw,source);
        var inbox=transactional(new NotificationService(sessions.getMapper(NotificationMapper.class),ids,Clock.systemUTC()),source);
        var notifications=transactional(new TaskNotificationService(provider(tasks),inbox,json),source);ReflectionTestUtils.setField(controlRaw,"taskNotifications",notifications);
        var storage=mock(ObjectStorage.class);when(storage.signedGetUrl(anyString(),any())).thenAnswer(i->URI.create("https://private-storage.example.invalid/report.pdf?fixture=private"));
        var delivery=transactional(new GeoDiagnosticDeliveryService(deliveryMapper,prepMapper,sessions.getMapper(GeoMapper.class),runs,tasks,control,conversations,agents,pricing,ids,json,names,
            provider(storage),new AssetIngestionProperties(),notifications,geo),source);geoRaw.setDiagnostics(provider(delivery));geoRaw.setTaskNotifications(notifications);
        var preparation=new GeoDiagnosticService(prepMapper,conversations,sessions.getMapper(GeoPeriodReportMapper.class),agents,json,ids,names,pricing);
        preparation.setDelivery(provider(delivery),provider(control));var prep=transactional(preparation,source);
        // A historical unsubmitted draft/quote could contain a user-chosen reserved ID. Both
        // renewed quote and stale quote admission must reject it before any wallet/outbox write.
        var legacy=fixture(prep,prepMapper,3);legacy=prep.quote(user(USER),Long.parseLong(legacy.id()),new GeoDiagnosticDtos.QuoteRequest(legacy.version(),List.of("DOUBAO")));
        long legacyId=Long.parseLong(legacy.id());var legacyRow=prepMapper.find(USER,legacyId).orElseThrow();var malformed=(ObjectNode)json.readTree(legacyRow.dataJson());
        ((ObjectNode)malformed.path("competitors").get(0)).put("id","OWN");
        assertThat(prepMapper.save(USER,legacyId,legacy.version(),6,malformed.toString(),legacyRow.quoteJson(),OffsetDateTime.now(ZoneOffset.UTC))).isEqualTo(1);
        var legacyDetail=prep.get(user(USER),legacyId);
        assertThatThrownBy(()->prep.quote(user(USER),legacyId,new GeoDiagnosticDtos.QuoteRequest(legacyDetail.version(),List.of("DOUBAO"))))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.details()).containsEntry("reason","DIAGNOSTIC_RESERVED_BRAND_ID"));
        assertThatThrownBy(()->delivery.submit(user(USER),legacyId,new GeoDiagnosticReportDtos.Submit(legacyDetail.version(),UUID.fromString(legacyDetail.quote().id()),legacyDetail.quote().inputHash(),UUID.randomUUID())))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.details()).containsEntry("reason","DIAGNOSTIC_RESERVED_BRAND_ID"));
        assertThat(tasks.findPointAccount(USER).orElseThrow().frozenPoints()).isZero();
        try(var c=source.getConnection()){for(String table:List.of("business_task","outbox_event","point_ledger"))assertThat(scalar(c,"SELECT count(*) FROM business."+table)).isEqualTo("0");}
        var eight=fixture(prep,prepMapper,8);var tooMuch=prep.quote(user(USER),Long.parseLong(eight.id()),new GeoDiagnosticDtos.QuoteRequest(eight.version(),GeoTaskService.PLATFORM_ORDER));
        assertThat(tooMuch.quote().expectedSamples()).isEqualTo(56);assertThat(tooMuch.quote().points()).isEqualTo(56);
        assertThat(tooMuch.quote().blockedReason()).isEqualTo("SINGLE_ACTION_LIMIT");assertThat(tooMuch.quote().maxPointsPerAction()).isEqualTo(50);
        assertThatThrownBy(()->delivery.submit(user(USER),Long.parseLong(eight.id()),submit(tooMuch))).isInstanceOf(BusinessException.class);
        assertThat(tasks.findPointAccount(USER).orElseThrow().frozenPoints()).isZero();
        var ready=fixture(prep,prepMapper,3);ready=prep.quote(user(USER),Long.parseLong(ready.id()),new GeoDiagnosticDtos.QuoteRequest(ready.version(),List.of("DOUBAO","DEEPSEEK")));
        var request=submit(ready);long id=Long.parseLong(ready.id());delivery.submit(user(USER),id,request);delivery.submit(user(USER),id,request);
        var accepted=prep.get(user(USER),id);assertThat(accepted.version()).isGreaterThan(ready.version());assertThat(accepted.delivery().status()).isEqualTo("SAMPLING");
        long task=Long.parseLong(accepted.delivery().taskId());var row=deliveryMapper.find(USER,id).orElseThrow();
        assertThat(runs.findRun(row.runId(),USER)).isEmpty();assertThat(runs.findRunPage(USER,null,null,0,20)).isEmpty();assertThat(runs.countRuns(USER,null,null)).isZero();
        assertThatThrownBy(()->geo.run(user(USER),row.runId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->geo.runProgress(user(USER),row.runId())).isInstanceOf(BusinessException.class);
        assertThat(tasks.findPointAccount(USER).orElseThrow().frozenPoints()).isEqualTo(6);
        assertThatThrownBy(()->prep.patch(user(USER),id,new GeoDiagnosticDtos.Patch(accepted.version(),2,accepted.profile(),null,null,null,null,false))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->delivery.get(user(OTHER),id)).isInstanceOf(BusinessException.class);
        try(var c=source.getConnection()){
            assertThat(scalar(c,"SELECT count(*) FROM business.business_task")).isEqualTo("1");assertThat(scalar(c,"SELECT count(*) FROM business.agent_run")).isEqualTo("0");
            assertThat(scalar(c,"SELECT draft_source||':'||status||':'||(agent_run_id IS NULL)::text||':'||(confirmed_at IS NULL)::text FROM business.task_draft")).isEqualTo("FORM:ADMITTED:true:true");
            reject(c,"UPDATE business.geo_diagnostic_preparation SET data='{}' WHERE id="+id,"23514");reject(c,"DELETE FROM business.geo_diagnostic_delivery WHERE diagnostic_id="+id,"23514");
        }
        var claim=claim(control,source,task);var context=control.executionContext(task,claim.executionNo(),claim.executionToken());
        assertThat(context.budget().maxProviderCalls()).isEqualTo(200);assertThat(context.budget().samplesPerCombination()).isEqualTo(1);
        var questions=context.authorizedContext().path("questions");List<String> q=new ArrayList<>();questions.forEach(v->q.add(v.path("id").asText()));
        observe(geo,task,claim,q.get(0),"DOUBAO","星河咖啡提供冷萃。",false);
        observe(geo,task,claim,q.get(1),"DOUBAO","辰光咖啡提供手冲。",false);
        observe(geo,task,claim,q.get(2),"DOUBAO","星河咖啡的体验应核对门店信息。",false);
        observe(geo,task,claim,q.get(0),"DEEPSEEK","星河咖啡提供冷萃。",false);
        observe(geo,task,claim,q.get(1),"DEEPSEEK",null,true);
        var complete=completion(claim);var sampled=geo.complete(task,complete).response();
        assertThat(sampled.taskStatus()).isEqualTo("RUNNING");assertThat(sampled.runStatus()).isEqualTo("PARTIAL");assertThat(sampled.reportId()).isNull();
        assertThat(sampled.settledPoints()).isEqualTo(4);assertThat(sampled.releasedPoints()).isEqualTo(2);geo.complete(task,complete);
        assertThat(tasks.findTaskInternal(task).orElseThrow().status()).isEqualTo("RUNNING");
        assertThat(tasks.findPointAccount(USER).orElseThrow().availablePoints()).isEqualTo(996);assertThat(tasks.findPointAccount(USER).orElseThrow().frozenPoints()).isZero();
        try(var c=source.getConnection()){
            assertThat(scalar(c,"SELECT count(*) FROM business.geo_report")).isEqualTo("0");assertThat(scalar(c,"SELECT count(*) FROM business.user_notification")).isEqualTo("0");
            reject(c,"UPDATE business.geo_observation SET answer_text='伪造' WHERE run_id="+row.runId()+" AND status='SUCCEEDED'","23514");
            reject(c,"DELETE FROM business.geo_observation WHERE run_id="+row.runId(),"23514");
            assertThat(scalar(c,"SELECT count(*) FROM business.point_ledger")).isEqualTo("3");
        }
        var first=delivery.claimNext().orElseThrow();assertThat(first.phase()).isEqualTo("ANALYSIS_RUNNING");assertThat(prep.get(user(USER),id).delivery().reportVersion()).isEqualTo(1);
        var privateSamples=first.request().path("evidence").path("samples");assertThat(privateSamples).hasSize(6);
        for(var sample:privateSamples){assertThat(sample.path("matchedBrandIds").isArray()).isTrue();
            if(!sample.path("status").asText().equals("SUCCEEDED"))assertThat(sample.path("matchedBrandIds")).isEmpty();}
        assertThat(privateSamples.get(0).path("matchedBrandIds").toString()).isEqualTo("[\"OWN\"]");
        assertThat(delivery.get(user(USER),id).samples().getFirst().matchedBrandIds()).containsExactly("OWN");
        delivery.failed(first,"ANALYSIS_UNAVAILABLE");var failed=delivery.get(user(USER),id);assertThat(failed.canRetry()).isTrue();
        UUID recovery=UUID.randomUUID();var retry=new GeoDiagnosticReportDtos.Retry(failed.version(),recovery);delivery.retry(user(USER),id,retry);delivery.retry(user(USER),id,retry);
        var second=delivery.claimNext().orElseThrow();assertThat(second.detail().analysisAttempts()).isEqualTo(2);delivery.analyzed(second,analysis(second));delivery.analyzed(first,analysis(first));
        assertThat(deliveryMapper.find(USER,id).orElseThrow().analysisUsageJson()).contains("GEO_DIAGNOSTIC_ANALYSIS_V10");
        var pdf=delivery.claimNext().orElseThrow();assertThat(pdf.phase()).isEqualTo("PDF_RUNNING");assertThat(pdf.fullObservations()).hasSize(5);assertThat(prep.get(user(USER),id).delivery().reportVersion()).isEqualTo(1);
        delivery.failed(pdf,"PDF_DELIVERY_UNAVAILABLE");var pdfFailed=delivery.get(user(USER),id);delivery.retry(user(USER),id,retry);
        assertThat(delivery.get(user(USER),id).status()).isEqualTo("PDF_FAILED"); // old analysis nonce cannot now retry PDF
        assertThatThrownBy(()->delivery.retry(user(USER),id,new GeoDiagnosticReportDtos.Retry(pdfFailed.version(),recovery))).isInstanceOf(BusinessException.class);
        delivery.retry(user(USER),id,new GeoDiagnosticReportDtos.Retry(pdfFailed.version(),UUID.randomUUID()));
        var pdfSecond=delivery.claimNext().orElseThrow();assertThat(delivery.ready(pdfSecond,new ObjectStorage.StoredObject(pdfSecond.objectKey(),"application/pdf",123,"a".repeat(64),true),"diagnostic-v1.pdf")).isTrue();
        assertThat(delivery.ready(pdfSecond,new ObjectStorage.StoredObject(pdfSecond.objectKey(),"application/pdf",123,"a".repeat(64),true),"diagnostic-v1.pdf")).isFalse();
        var report=delivery.get(user(USER),id);assertThat(report.status()).isEqualTo("READY");assertThat(report.reportVersion()).isEqualTo(1);assertThat(report.hasNoData()).isFalse();
        assertThat(tasks.findTaskInternal(task).orElseThrow().status()).isEqualTo("PARTIAL");assertThat(delivery.observations(user(USER),id,1,10).items()).hasSize(5);
        assertThat(geo.complete(task,complete).response().taskStatus()).isEqualTo("PARTIAL");
        assertThat(delivery.download(user(USER),id,1).downloadUrl()).startsWith("https://private-storage.example.invalid/");assertThatThrownBy(()->delivery.download(user(USER),id,2)).isInstanceOf(BusinessException.class);
        assertThat(delivery.latestAgentProjection(USER,PROJECT).toString()).contains("contentTopics","diagnosticId").doesNotContain("private-storage","objectKey","downloadUrl");
        assertThat(delivery.isAgentProjectionAvailable(USER,PROJECT,id,1)).isTrue();assertThat(delivery.isAgentProjectionAvailable(OTHER,PROJECT,id,1)).isFalse();
        try(var c=source.getConnection()){
            assertThat(scalar(c,"SELECT count(*) FROM business.point_ledger")).isEqualTo("3");assertThat(scalar(c,"SELECT count(*) FROM business.outbox_event")).isEqualTo("1");
            assertThat(scalar(c,"SELECT count(*) FROM business.user_notification WHERE dedup_key='DIAGNOSTIC_READY:"+task+"'")).isEqualTo("1");
            reject(c,"DELETE FROM business.geo_diagnostic_delivery_request WHERE diagnostic_id="+id,"23514");
            reject(c,"UPDATE business.geo_diagnostic_delivery SET pdf_sha256=NULL WHERE diagnostic_id="+id,"23514");
            reject(c,"UPDATE business.geo_diagnostic_delivery SET user_id=96202 WHERE diagnostic_id="+id,"23514");
        }
        // All failed signed handoff: no model analysis, zero settled points, an explicitly failed sampling result with private PDF ready.
        var empty=fixture(prep,prepMapper,3);empty=prep.quote(user(USER),Long.parseLong(empty.id()),new GeoDiagnosticDtos.QuoteRequest(empty.version(),List.of("DOUBAO")));
        long emptyId=Long.parseLong(empty.id());delivery.submit(user(USER),emptyId,submit(empty));long emptyTask=deliveryMapper.find(USER,emptyId).orElseThrow().taskId();var emptyClaim=claim(control,source,emptyTask);
        var emptyContext=control.executionContext(emptyTask,emptyClaim.executionNo(),emptyClaim.executionToken());for(var question:emptyContext.authorizedContext().path("questions"))observe(geo,emptyTask,emptyClaim,question.path("id").asText(),"DOUBAO",null,true);
        var emptyCompletion=completion(emptyClaim);var emptyResult=geo.complete(emptyTask,emptyCompletion).response();assertThat(emptyResult.runStatus()).isEqualTo("FAILED");assertThat(emptyResult.settledPoints()).isZero();assertThat(emptyResult.releasedPoints()).isEqualTo(3);
        var emptyPdf=delivery.claimNext().orElseThrow();assertThat(emptyPdf.phase()).isEqualTo("PDF_RUNNING");assertThat(emptyPdf.detail().analysisAttempts()).isZero();assertThat(emptyPdf.detail().hasNoData()).isTrue();
        delivery.ready(emptyPdf,new ObjectStorage.StoredObject(emptyPdf.objectKey(),"application/pdf",123,"b".repeat(64),true),"failed-v1.pdf");
        assertThat(delivery.get(user(USER),emptyId).status()).isEqualTo("READY");assertThat(tasks.findTaskInternal(emptyTask).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(geo.complete(emptyTask,emptyCompletion).response().taskStatus()).isEqualTo("FAILED");
        // Genuine terminal watchdog path: report recovery preserves the terminal task and its already released reservation.
        var crash=fixture(prep,prepMapper,3);crash=prep.quote(user(USER),Long.parseLong(crash.id()),new GeoDiagnosticDtos.QuoteRequest(crash.version(),List.of("DOUBAO")));
        long crashId=Long.parseLong(crash.id());delivery.submit(user(USER),crashId,submit(crash));var crashRow=deliveryMapper.find(USER,crashId).orElseThrow();var crashClaim=claim(control,source,crashRow.taskId());
        control.reportEvent(crashRow.taskId(),new TaskDtos.ExecutionEventRequest(UUID.randomUUID(),crashClaim.executionNo(),crashClaim.executionToken(),"TASK_FAILED","FAILED",json.createObjectNode().put("code","PROVIDER_TIMEOUT").put("submissionState","NOT_SUBMITTED")));
        assertThat(tasks.findTaskInternal(crashRow.taskId()).orElseThrow().status()).isEqualTo("FAILED");var crashPdf=delivery.claimNext().orElseThrow();assertThat(crashPdf.phase()).isEqualTo("PDF_RUNNING");
        delivery.ready(crashPdf,new ObjectStorage.StoredObject(crashPdf.objectKey(),"application/pdf",123,"c".repeat(64),true),"crash-v1.pdf");
        assertThat(tasks.findTaskInternal(crashRow.taskId()).orElseThrow().status()).isEqualTo("FAILED");assertThat(delivery.get(user(USER),crashId).status()).isEqualTo("READY");
        try(var c=source.getConnection()){
            assertThat(scalar(c,"SELECT count(*) FROM business.business_task")).isEqualTo("3");assertThat(scalar(c,"SELECT count(*) FROM business.outbox_event")).isEqualTo("3");
            assertThat(scalar(c,"SELECT count(*) FROM business.agent_run")).isEqualTo("0");assertThat(scalar(c,"SELECT count(*) FROM business.point_ledger WHERE entry_type='TASK_FREEZE'")).isEqualTo("3");
            assertThat(scalar(c,"SELECT count(*) FROM business.user_notification WHERE dedup_key LIKE 'DIAGNOSTIC_READY:%'")).isEqualTo("3");
            assertThat(scalar(c,"SELECT payload->>'status' FROM business.conversation_event WHERE task_id="+crashRow.taskId()+" AND event_type='task.failed' ORDER BY sequence_no DESC LIMIT 1")).isEqualTo("FAILED");
        }
        // Two failed analysis attempts terminate the business task, without settling or sampling again.
        var exhausted=fixture(prep,prepMapper,3);exhausted=prep.quote(user(USER),Long.parseLong(exhausted.id()),new GeoDiagnosticDtos.QuoteRequest(exhausted.version(),List.of("DOUBAO")));
        long exhaustedId=Long.parseLong(exhausted.id());delivery.submit(user(USER),exhaustedId,submit(exhausted));var exhaustedRow=deliveryMapper.find(USER,exhaustedId).orElseThrow();var exhaustedClaim=claim(control,source,exhaustedRow.taskId());
        sampleSuccessful(geo,control,exhaustedRow.taskId(),exhaustedClaim);var exhaustedCompletion=completion(exhaustedClaim);geo.complete(exhaustedRow.taskId(),exhaustedCompletion);
        String beforeFailureLedger;try(var c=source.getConnection()){beforeFailureLedger=scalar(c,"SELECT count(*) FROM business.point_ledger");}
        var analysisOne=delivery.claimNext().orElseThrow();delivery.failed(analysisOne,"ANALYSIS_UNAVAILABLE");var analysisFailure=delivery.get(user(USER),exhaustedId);
        assertThat(tasks.findTaskInternal(exhaustedRow.taskId()).orElseThrow().status()).isEqualTo("RUNNING");
        delivery.retry(user(USER),exhaustedId,new GeoDiagnosticReportDtos.Retry(analysisFailure.version(),UUID.randomUUID()));var analysisTwo=delivery.claimNext().orElseThrow();delivery.failed(analysisTwo,"ANALYSIS_UNAVAILABLE");
        assertThat(delivery.get(user(USER),exhaustedId).canRetry()).isFalse();assertThat(tasks.findTaskInternal(exhaustedRow.taskId()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(geo.complete(exhaustedRow.taskId(),exhaustedCompletion).response().taskStatus()).isEqualTo("FAILED");
        try(var c=source.getConnection()){assertThat(scalar(c,"SELECT count(*) FROM business.point_ledger")).isEqualTo(beforeFailureLedger);}
        // A third PDF lease expiring is the same exhausted terminal outcome, not a permanently RUNNING job.
        var pdfExhausted=fixture(prep,prepMapper,3);pdfExhausted=prep.quote(user(USER),Long.parseLong(pdfExhausted.id()),new GeoDiagnosticDtos.QuoteRequest(pdfExhausted.version(),List.of("DOUBAO")));
        long pdfExhaustedId=Long.parseLong(pdfExhausted.id());delivery.submit(user(USER),pdfExhaustedId,submit(pdfExhausted));var pdfRow=deliveryMapper.find(USER,pdfExhaustedId).orElseThrow();var pdfClaim=claim(control,source,pdfRow.taskId());
        sampleSuccessful(geo,control,pdfRow.taskId(),pdfClaim);geo.complete(pdfRow.taskId(),completion(pdfClaim));var pdfAnalysis=delivery.claimNext().orElseThrow();delivery.analyzed(pdfAnalysis,analysis(pdfAnalysis));
        for(int attempt=1;attempt<=2;attempt++){var p=delivery.claimNext().orElseThrow();delivery.failed(p,"PDF_DELIVERY_UNAVAILABLE");var f=delivery.get(user(USER),pdfExhaustedId);delivery.retry(user(USER),pdfExhaustedId,new GeoDiagnosticReportDtos.Retry(f.version(),UUID.randomUUID()));}
        var thirdPdf=delivery.claimNext().orElseThrow();assertThat(thirdPdf.detail().pdfAttempts()).isEqualTo(3);
        try(var c=source.getConnection();var statement=c.prepareStatement("UPDATE business.geo_diagnostic_delivery SET lease_expires_at=? WHERE diagnostic_id=?")){
            statement.setObject(1,OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));statement.setLong(2,pdfExhaustedId);assertThat(statement.executeUpdate()).isEqualTo(1);}
        assertThat(delivery.claimNext()).isEmpty();assertThat(delivery.get(user(USER),pdfExhaustedId).canRetry()).isFalse();
        assertThat(tasks.findTaskInternal(pdfRow.taskId()).orElseThrow().status()).isEqualTo("FAILED");
        try(var c=source.getConnection()){
            assertThat(scalar(c,"SELECT count(*) FROM business.outbox_event")).isEqualTo("5");assertThat(scalar(c,"SELECT count(*) FROM business.point_ledger WHERE entry_type='TASK_FREEZE'")).isEqualTo("5");
            assertThat(scalar(c,"SELECT count(*) FROM business.user_notification WHERE dedup_key LIKE 'DIAGNOSTIC_DELIVERY_FAILED:%'")).isEqualTo("2");
        }
        System.out.println("DB_GATE diagnostic_delivery=v061_v062_scope_default_acl_real_policy_one_geo_task_partial_settlement_fenced_recovery_full_answers_no_data_hard_failure_private_pdf_passed");
    }
    private GeoDiagnosticDtos.Detail fixture(GeoDiagnosticService service,GeoDiagnosticMapper mapper,int count){
        var first=service.create(user(USER),new GeoDiagnosticDtos.Create(UUID.randomUUID(),Long.toString(CONVERSATION)));long id=Long.parseLong(first.id());var data=json.createObjectNode();
        data.set("profile",json.valueToTree(new GeoDiagnosticDtos.Profile("星河咖啡","https://example.com","现磨冷萃门店，服务广州上班族",List.of("星河咖啡"),"广州")));
        data.set("competitors",json.valueToTree(List.of(new GeoDiagnosticDtos.Competitor("comp-1","辰光咖啡",List.of("辰光咖啡"),"同地区门店",List.of("web-1"),"USER",true))));
        var discovery=data.putArray("discoveryQuestions");for(int i=0;i<count-1;i++)discovery.addObject().put("id","d-"+i).put("text","广州上班族适合哪些现磨咖啡门店，场景"+i+"？").put("origin","USER").put("edited",true);
        data.putArray("reputationQuestions").addObject().put("id","r-1").put("text","星河咖啡体验如何？").put("origin","USER").put("edited",true);
        data.putArray("platformCodes");data.set("sources",json.valueToTree(List.of(new GeoDiagnosticDtos.Source("web-1","真实公开资料","https://example.com/about","真实公开品牌资料"))));data.putArray("warnings");
        mapper.save(USER,id,first.version(),6,data.toString(),null,OffsetDateTime.now(ZoneOffset.UTC));return service.get(user(USER),id);
    }
    private GeoDiagnosticReportDtos.Submit submit(GeoDiagnosticDtos.Detail detail){return new GeoDiagnosticReportDtos.Submit(detail.version(),UUID.fromString(detail.quote().id()),detail.quote().inputHash(),UUID.randomUUID());}
    private TaskDtos.ClaimResponse claim(TaskControlService control,DataSource source,long task)throws Exception{try(var c=source.getConnection()){
        String event=scalar(c,"SELECT id FROM business.outbox_event WHERE aggregate_id="+task);return control.claim(task,new TaskDtos.ClaimRequest("diagnostic-fixture",event,"offline-fixture",1,"agent-bundle-v1.54.0")).response();}}
    private void observe(GeoTaskService service,long task,TaskDtos.ClaimResponse claim,String question,String platform,String answer,boolean failed){var now=OffsetDateTime.now(ZoneOffset.UTC);
        service.reportObservation(task,new GeoTaskDtos.ObservationRequest(UUID.randomUUID(),claim.executionNo(),claim.executionToken(),new GeoTaskDtos.ObservationInput(platform,question,1,failed?"FAILED":"SUCCEEDED",answer,
            failed?List.of():List.of(new GeoTaskDtos.CitationInput("实际引用","https://example.com/answer","example.com","实际采样来源",now)),platform,"fixture-model",now,now,1,failed?"PROVIDER_TIMEOUT":null,
            new GeoTaskDtos.UsageInput(0,0,0,null,null),1,"fixture-v1")));
    }
    private void sampleSuccessful(GeoTaskService geo,TaskControlService control,long task,TaskDtos.ClaimResponse claim){
        var context=control.executionContext(task,claim.executionNo(),claim.executionToken());for(var q:context.authorizedContext().path("questions"))
            observe(geo,task,claim,q.path("id").asText(),"DOUBAO","星河咖啡提供冷萃，辰光咖啡提供手冲。",false);
    }
    private GeoTaskDtos.CompletionRequest completion(TaskDtos.ClaimResponse claim){return new GeoTaskDtos.CompletionRequest(UUID.randomUUID(),claim.executionNo(),claim.executionToken(),
        new GeoTaskDtos.SynthesisInput("采样完成，诊断报告单独生成。",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"geo-diagnostic-sampling-v1"),
        new GeoTaskDtos.SynthesisUsageInput("SYSTEM","deterministic-geo-diagnostic-sampling-v1",0,0,0,1));}
    private ObjectNode analysis(GeoDiagnosticDeliveryService.Claim claim){var evidence=claim.request().path("evidence");var response=json.createObjectNode().put("jobId",Long.toString(claim.id())).put("inputHash",claim.request().path("inputHash").asText()).put("promptVersion","GEO_DIAGNOSTIC_ANALYSIS_V10");
        var report=response.putObject("report").put("title","星河咖啡诊断").put("summary","发现与口碑分开，以下结论只指实际样本。");var findings=report.putArray("findings");
        for(String section:List.of("DISCOVERY","REPUTATION","COMPETITION")){var sample=java.util.stream.StreamSupport.stream(evidence.path("samples").spliterator(),false).filter(s->s.path("status").asText().equals("SUCCEEDED")
            &&s.path("category").asText().equals(section.equals("REPUTATION")?"BRAND_REPUTATION":"BRAND_DISCOVERY")).findFirst().orElseThrow();
            var f=findings.addObject().put("section",section).put("heading","本次观察 "+section).put("description","仅根据有效样本做定性解释，不证明排名或因果。");ref(f.putArray("evidence").addObject(),sample);}
        var sample=evidence.path("samples").get(0);var suggestion=report.putArray("recommendations").addObject().put("priority","P1").put("title","完善公开资料").put("action","补充可核查门店和产品信息。");ref(suggestion.putArray("basisEvidence").addObject(),sample);
        var topic=report.putArray("contentTopics").addObject().put("title","广州上班族怎么选冷萃").put("audienceQuestion","广州有哪些冷萃门店？").put("angle","从实际门店和产品资料展开。");topic.putArray("coveredBrandTerms").add("星河咖啡");ref(topic.putArray("basisEvidence").addObject(),sample);
        report.putArray("limitations").add("一次采集不是长期口碑调查。");response.set("synthesisUsage",json.valueToTree(Map.of("provider","DEEPSEEK","model","deepseek-flash","inputTokens",100,"outputTokens",100,"providerCallCount",1,"latencyMs",1)));return response;}
    private void ref(ObjectNode node,com.fasterxml.jackson.databind.JsonNode sample){node.put("unitKey",sample.path("unitKey").asText()).putNull("sourceId").put("quote",sample.path("answerExcerpt").asText());}
    @SuppressWarnings("unchecked") private static <T>ObjectProvider<T> provider(T object){ObjectProvider<T> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(object);when(provider.getObject()).thenReturn(object);return provider;}
    private static <T>T transactional(T object,DataSource source){var proxy=new ProxyFactory(object);proxy.setProxyTargetClass(true);var advice=new TransactionInterceptor();advice.setTransactionManager(new JdbcTransactionManager(source));advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(advice);
        @SuppressWarnings("unchecked")T result=(T)proxy.getProxy();return result;}
    private static AuthenticatedAccount user(long id){return new AuthenticatedAccount(id,"fixture",AccountRole.USER,1,AccountStatus.ACTIVE,null,null,null);}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing Gate variable: "+name);return value;}
    private static Flyway flyway(String url,String user,String password,String target){return Flyway.configure().dataSource(url,user,password).schemas("business").defaultSchema("business").createSchemas(true).target(target).load();}
    private static String scalar(Connection c,String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){if(!r.next())throw new IllegalStateException("Missing gate scalar");return r.getString(1);}}
    private static void execute(Connection c,String sql)throws SQLException {try(var statement=c.createStatement()){statement.execute(sql);}}
    private static void reject(Connection c,String sql,String state){assertThatThrownBy(()->execute(c,sql)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo(state));}
}
