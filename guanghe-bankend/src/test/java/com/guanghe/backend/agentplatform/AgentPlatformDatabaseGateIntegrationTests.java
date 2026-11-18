package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.id.SnowflakeProperties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real V046 -> V047 upgrade and production-mode MyBatis mapping, in a disposable database only. */
@EnabledIfEnvironmentVariable(named="DATABASE_GATE_ENABLED",matches="true")
class AgentPlatformDatabaseGateIntegrationTests {
    @Test void upgradePreservesLegacyAndEnforcesOwnedImmutablePlatformIdentity() throws Exception {
        String database=required("GATE_EXPECTED_DATABASE"),url=required("GATE_MIGRATION_DB_URL"),
                migrationUser=required("GATE_MIGRATION_DB_USERNAME"),password=required("GATE_MIGRATION_DB_PASSWORD"),
                runtimeUrl=required("GATE_RUNTIME_DB_URL"),runtimeUser=required("GATE_RUNTIME_DB_USERNAME"),
                runtimePassword=required("GATE_RUNTIME_DB_PASSWORD");
        if (!database.matches("^zhixiaohe_gate_[a-z0-9_]+$") || migrationUser.equals(runtimeUser)
                || !runtimeUser.matches("^gate_runtime_[a-z0-9_]+$")) throw new IllegalStateException("Isolated Gate roles required");
        // Verify both actual database names before running any Flyway or fixture write.
        try(var migration=DriverManager.getConnection(url,migrationUser,password);
                var runtime=DriverManager.getConnection(runtimeUrl,runtimeUser,runtimePassword)) {
            assertThat(scalar(migration,"SELECT current_database()")).isEqualTo(database);
            assertThat(scalar(runtime,"SELECT current_database()")).isEqualTo(database);
        }
        flyway(url,migrationUser,password,"046").migrate();
        String original;
        try(var migration=DriverManager.getConnection(url,migrationUser,password)) {
            execute(migration,"""
                    INSERT INTO business.account(id,username,username_normalized,password_hash,role,status,created_at,updated_at)
                    VALUES (98401,'platform_gate','platform_gate',repeat('a',32),'USER','ACTIVE',now(),now()),
                           (98402,'platform_other','platform_other',repeat('a',32),'USER','ACTIVE',now(),now());
                    INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,brand_profile,created_at,updated_at)
                    VALUES (98410,98401,'Existing client','existing client','Existing brand','{"description":"fixture"}',now(),now()),
                           (98411,98402,'Other client','other client','Other brand','{}',now(),now());
                    INSERT INTO business.conversation(id,user_id,title,selected_space_id,created_at,updated_at)
                    VALUES (98420,98401,'Legacy conversation',98410,now(),now()),
                           (98421,98401,'Second conversation',NULL,now(),now()),
                           (98422,98402,'Other conversation',98411,now(),now());
                    """);
            original=scalar(migration,"SELECT (to_jsonb(s))::text FROM business.business_space s WHERE id=98410");
        }
        var upgrade=flyway(url,migrationUser,password,"047");
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate(); assertThat(upgrade.migrate().migrationsExecuted).isZero();
        try(var migration=DriverManager.getConnection(url,migrationUser,password)) {
            assertThat(scalar(migration,"SELECT (to_jsonb(s)-'project_status')::text FROM business.business_space s WHERE id=98410")).isEqualTo(original);
            assertThat(scalar(migration,"SELECT selected_space_id::text FROM business.conversation WHERE id=98420")).isEqualTo("98410");
            execute(migration,"GRANT USAGE ON SCHEMA business TO \""+runtimeUser+"\"; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA business TO \""+runtimeUser+"\"");
        }
        var source=new PGSimpleDataSource(); source.setUrl(runtimeUrl); source.setUser(runtimeUser); source.setPassword(runtimePassword);
        var configuration=new Configuration(new Environment("agent-platform-gate",new SpringManagedTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true); // Match production's ordinal record mapping, including nullable columns.
        configuration.addMapper(AgentPlatformMapper.class); configuration.addMapper(BusinessSpaceMapper.class);
        var sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        var mapper=sessions.getMapper(AgentPlatformMapper.class);
        var service=transactional(new AgentPlatformService(mapper,new AgentPlatformProperties(),
                new SnowflakeIdGenerator(new SnowflakeProperties(1,1)),new CatalogNameNormalizer(),new ObjectMapper()),source);
        var legacy=service.identityOf(98401,98420);
        assertThat(legacy.installationId()).isNull(); assertThat(legacy.projectId()).isNull();
        assertThat(mapper.installations(98401)).isEmpty(); assertThat(mapper.binding(98401,98420)).isEmpty();
        var now=OffsetDateTime.now();
        assertThat(mapper.insertProject(new AgentPlatformRecords.Project(98412,98401,"Brandless","brandless","ACTIVE",1,now,now))).isEqualTo(1);
        assertThat(mapper.lockProject(98401,98412).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(mapper.lockProject(98402,98412)).isEmpty();
        var spaces=sessions.getMapper(BusinessSpaceMapper.class);
        assertThat(spaces.findOwnedStorageByIdAndUserId(98412,98401).orElseThrow().brandName()).isNull();
        assertThat(spaces.countByUserId(98401)).isEqualTo(1);
        var identity=service.bindConversation(98401,98420,null,98412L);
        long installation=Long.parseLong(identity.installationId());
        assertThat(service.assertRunAllowed(98401,98420)).isEqualTo(identity);
        assertThat(service.assertTaskConfirmationAllowed(98401,98420)).isEqualTo(identity);
        verifyAdmissionAndConfirmationLocksCoexist(service,mapper,source);
        assertThat(mapper.binding(98401,98420).orElseThrow().projectId()).isEqualTo(98412);
        // A separate conversation explicitly has no project; null cannot become primitive zero in MyBatis.
        assertThat(service.bindConversation(98401,98421,installation,null).projectId()).isNull();
        assertThat(mapper.binding(98401,98421).orElseThrow().projectId()).isNull();
        assertThat(mapper.lockInstallation(98402,installation)).isEmpty();
        try(var runtime=source.getConnection()) {
            reject(runtime,"UPDATE business.conversation_agent_binding SET project_id=98410 WHERE conversation_id=98420","23514");
            reject(runtime,"UPDATE business.agent_definition_version SET capabilities='[]' WHERE agent_key='geo'","23514");
            reject(runtime,"UPDATE business.agent_installation SET agent_key='summary' WHERE id="+installation,"23514");
            reject(runtime,"INSERT INTO business.conversation_agent_binding SELECT 98422,98402,installation_id,agent_key,agent_version,agent_name,NULL,capabilities,now() FROM business.conversation_agent_binding WHERE conversation_id=98420","23503");
            execute(runtime,"INSERT INTO business.conversation(id,user_id,title,created_at,updated_at) VALUES (98423,98401,'FK fixture',now(),now())");
            reject(runtime,"INSERT INTO business.conversation_agent_binding SELECT 98423,98401,installation_id,agent_key,agent_version,agent_name,98411,capabilities,now() FROM business.conversation_agent_binding WHERE conversation_id=98420","23503");
            reject(runtime,"INSERT INTO business.agent_installation SELECT 98430,user_id,agent_key,agent_version,true,1,now(),now(),NULL FROM business.agent_installation WHERE id="+installation,"23505");
            reject(runtime,"CREATE TABLE business.agent_platform_forbidden(id bigint)","42501");
        }
        service.removeInstallation(AgentPlatformServiceTests.user(98401),installation);
        service.removeInstallation(AgentPlatformServiceTests.user(98401),installation);
        assertThat(service.identityOf(98401,98420)).isEqualTo(identity);
        assertThatThrownBy(() -> service.assertRunAllowed(98401,98420)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        var readded=service.addInstallation(AgentPlatformServiceTests.user(98401),new AgentPlatformDtos.AddInstallationRequest("geo"));
        assertThat(readded.id()).isNotEqualTo(identity.installationId());
        assertThat(service.identityOf(98401,98420)).isEqualTo(identity);
        try(var runtime=source.getConnection()) {
            reject(runtime,"UPDATE business.agent_installation SET deleted_at=NULL,enabled=true WHERE id="+installation,"23514");
            reject(runtime,"DELETE FROM business.agent_installation WHERE id="+installation,"23503");
            // Existing explicit deletion of an empty/free conversation still works.
            execute(runtime,"DELETE FROM business.conversation WHERE id=98421 AND user_id=98401");
            assertThat(scalar(runtime,"SELECT count(*) FROM business.conversation_agent_binding WHERE conversation_id=98421")).isEqualTo("0");
            assertThat(scalar(runtime,"SELECT count(*) FROM business.conversation_agent_binding WHERE conversation_id=98420")).isEqualTo("1");
            assertThat(scalar(runtime,"SELECT count(*) FROM business.business_task")).isEqualTo("0");
            assertThat(scalar(runtime,"SELECT count(*) FROM business.point_ledger")).isEqualTo("0");
        }
        System.out.println("DB_GATE agent_platform=v046_v047_legacy_preserved_nullable_project_owned_immutable_tombstone_history_zero_paid_passed");
    }
    private static AgentPlatformService transactional(AgentPlatformService service,DataSource source) {
        var proxy=new ProxyFactory(service); proxy.setProxyTargetClass(true);
        var transaction=new TransactionInterceptor(); transaction.setTransactionManager(new JdbcTransactionManager(source));
        transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); proxy.addAdvice(transaction);
        return (AgentPlatformService)proxy.getProxy();
    }
    private static void verifyAdmissionAndConfirmationLocksCoexist(AgentPlatformService service,
            AgentPlatformMapper mapper,DataSource source) throws Exception {
        var transactions=new TransactionTemplate(new JdbcTransactionManager(source));
        transactions.setTimeout(10);
        var confirmationHasInstallation=new CountDownLatch(1);
        try(var workers=Executors.newSingleThreadExecutor()) {
            Future<?>[] completion=new Future<?>[1];
            transactions.executeWithoutResult(status -> {
                mapper.lockConversation(98401,98420).orElseThrow();
                completion[0]=workers.submit(() -> transactions.executeWithoutResult(other -> {
                    service.assertTaskConfirmationAllowed(98401,98420);
                    confirmationHasInstallation.countDown();
                    // Existing confirmation emits its conversation event after its points work.
                    mapper.lockConversation(98401,98420).orElseThrow();
                }));
                try { assertThat(confirmationHasInstallation.await(5,TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                // FOR SHARE permissions coexist despite opposite conversation acquisition order.
                service.assertRunAllowed(98401,98420);
            });
            completion[0].get(10,TimeUnit.SECONDS);
        }
    }
    private static Flyway flyway(String url,String user,String password,String target) {
        return Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration")
                .schemas("business","agent").defaultSchema("business").cleanDisabled(true).target(target).load();
    }
    private static String required(String name) { var value=System.getenv(name);
        if(value==null || value.isBlank()) throw new IllegalStateException("Missing Gate variable: "+name); return value; }
    private static String scalar(Connection connection,String statement) throws SQLException {
        try(var sql=connection.createStatement();var result=sql.executeQuery(statement)) {
            if(!result.next()) throw new IllegalStateException("No Gate scalar result"); return result.getString(1); }
    }
    private static void execute(Connection connection,String statement) throws SQLException {
        try(var sql=connection.createStatement()) { sql.execute(statement); }
    }
    private static void reject(Connection connection,String statement,String state) {
        assertThatThrownBy(() -> execute(connection,statement)).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).isEqualTo(state));
    }
}
