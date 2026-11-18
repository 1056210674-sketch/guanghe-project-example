package com.guanghe.backend.auth.access;

import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.account.ChinaPhoneNormalizer;
import com.guanghe.backend.auth.account.UsernameNormalizer;
import com.guanghe.backend.auth.config.AccountAccessProperties;
import com.guanghe.backend.auth.config.JwtProperties;
import com.guanghe.backend.auth.ratelimit.LoginAttemptGuard;
import com.guanghe.backend.auth.registration.RegistrationProperties;
import com.guanghe.backend.auth.security.ActivationTokenService;
import com.guanghe.backend.auth.security.AdaptivePasswordService;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.auth.security.FixedSaltMd5PasswordEncoder;
import com.guanghe.backend.auth.security.JwtTokenService;
import com.guanghe.backend.auth.service.AuthService;
import com.guanghe.backend.auth.web.CompleteFirstLoginRequest;
import com.guanghe.backend.auth.web.LoginRequest;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.validation.Validation;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.spec.SecretKeySpec;
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
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Runs only against an explicitly guarded disposable database; never the shared test database. */
@EnabledIfEnvironmentVariable(named = "DATABASE_GATE_ENABLED", matches = "true")
class AccountProvisioningDatabaseGateIntegrationTests {
    private static final long ADMIN_ID = 944001;
    private static final long OLD_USER_ID = 944002;

    @Test
    void usernameProvisioningPreservesPhoneAccountsAndAtomicallyCreatesOneInitialGrant() throws Exception {
        String database = required("GATE_EXPECTED_DATABASE");
        String migrationUrl = required("GATE_MIGRATION_DB_URL");
        String migrationUser = required("GATE_MIGRATION_DB_USERNAME");
        String migrationPassword = required("GATE_MIGRATION_DB_PASSWORD");
        String runtimeUrl = required("GATE_RUNTIME_DB_URL");
        String runtimeUser = required("GATE_RUNTIME_DB_USERNAME");
        String runtimePassword = required("GATE_RUNTIME_DB_PASSWORD");
        if (!database.matches("^zhixiaohe_gate_[a-z0-9_]+$") || migrationUser.equals(runtimeUser)
                || !runtimeUser.matches("^gate_runtime_[a-z0-9_]+$")) {
            throw new IllegalStateException("Username Gate requires distinct isolated migration/runtime roles");
        }
        // Validate both live destinations before the first migration or write.
        try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword);
             Connection runtime = DriverManager.getConnection(runtimeUrl, runtimeUser, runtimePassword)) {
            assertThat(scalar(migration, "SELECT current_database()")).isEqualTo(database);
            assertThat(scalar(runtime, "SELECT current_database()")).isEqualTo(database);
        }
        Flyway.configure().dataSource(migrationUrl, migrationUser, migrationPassword)
                .defaultSchema("business").schemas("business", "agent").createSchemas(true)
                .locations("classpath:db/migration").target("043").load().migrate();
        String oldAccounts;
        try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            execute(migration, """
                    INSERT INTO business.account(id,username,username_normalized,phone_e164,password_hash,
                        role,status,display_name,created_at,updated_at)
                    VALUES (944001,'gateadmin','gateadmin','+8613800138000',repeat('a',32),
                        'ADMIN','ACTIVE','isolated gate admin',now(),now()),
                           (944002,'legacy_user','legacy_user','+8613800138001',repeat('b',32),
                        'USER','ACTIVE','isolated old user',now(),now());
                    """);
            oldAccounts = oldAccountFingerprint(migration);
            assertThat(scalar(migration, "SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_schema='business' AND table_name='account' AND column_name='phone_e164'"))
                    .isEqualTo("NO");
        }
        Flyway upgrade = Flyway.configure().dataSource(migrationUrl, migrationUser, migrationPassword)
                .defaultSchema("business").schemas("business", "agent").createSchemas(true)
                .locations("classpath:db/migration").target("044").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(upgrade.info().current().getVersion().toString()).isEqualTo("044");
        try (Connection migration = DriverManager.getConnection(migrationUrl, migrationUser, migrationPassword)) {
            assertThat(oldAccountFingerprint(migration)).isEqualTo(oldAccounts);
            assertThat(scalar(migration, "SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_schema='business' AND table_name='account' AND column_name='phone_e164'"))
                    .isEqualTo("YES");
            assertThat(number(migration, "SELECT count(*) FROM pg_constraint WHERE conrelid='business.account'::regclass "
                    + "AND conname IN ('uk_account_username_normalized','uk_account_phone_e164') AND contype='u'"))
                    .isEqualTo(2);
            execute(migration, "GRANT USAGE ON SCHEMA business TO \"" + runtimeUser + "\"; "
                    + "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA business TO \"" + runtimeUser + "\"");
        }
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(runtimeUrl); dataSource.setUser(runtimeUser); dataSource.setPassword(runtimePassword);
        Configuration configuration = new Configuration(new Environment(
                "username-provisioning-gate", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(AccessMapper.class);
        configuration.addMapper(AccountMapper.class);
        SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        AccessMapper mapper = sessions.getMapper(AccessMapper.class);
        AccountMapper accounts = sessions.getMapper(AccountMapper.class);
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        AtomicLong sequence = new AtomicLong(944100);
        when(ids.nextId()).thenAnswer(ignored -> sequence.incrementAndGet());
        AccountAccessProperties config = new AccountAccessProperties(1_000, Duration.ofMinutes(30), 16, 4);
        AdaptivePasswordService passwords = new AdaptivePasswordService(config);
        Clock clock = Clock.systemUTC();
        byte[] keyBytes = new byte[32]; new SecureRandom().nextBytes(keyBytes);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        JwtProperties jwtConfig = new JwtProperties("isolated-username-gate", 3600, Base64.getEncoder().encodeToString(keyBytes));
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtConfig.issuer()));
        ActivationTokenService activation = new ActivationTokenService(encoder, decoder, jwtConfig, config, clock);
        JwtTokenService jwt = new JwtTokenService(encoder, jwtConfig, clock);
        RegistrationProperties agreements = new RegistrationProperties(false, "gate-terms", "/legal/terms", "gate-privacy", "/legal/privacy", 60);
        AccessService service = transactional(new AccessService(mapper, ids, new ChinaPhoneNormalizer(),
                new UsernameNormalizer(), passwords, activation, jwt, agreements, config, clock), dataSource);
        AuthenticatedAccount admin = new AuthenticatedAccount(ADMIN_ID, "gateadmin", AccountRole.ADMIN, 1,
                AccountStatus.ACTIVE, "+8613800138000", "Gate admin", null);
        var created = service.create(admin, new AccessDtos.CreateUserRequest(null, "Gate_User", "Gate merchant", 500));
        long createdId = Long.parseLong(created.user().id());
        assertThat(created.user().username()).isEqualTo("Gate_User");
        assertThat(created.user().phone()).isNull();
        assertThat(created.user().credentialState()).isEqualTo("MUST_CHANGE_PASSWORD");
        AccountRecord pending = accounts.findByUsernameNormalized("gate_user").orElseThrow();
        assertThat(pending.phoneE164()).isNull();
        assertThat(pending.role()).isEqualTo(AccountRole.USER);
        assertThat(passwords.matches(created.temporaryPassword(), pending)).isTrue();
        assertThat(service.get(admin, createdId).phone()).isNull();
        assertThat(service.list(admin, "GATE_USER", 1, 20).items()).hasSize(1);
        try (Connection runtime = dataSource.getConnection()) { assertGrant(runtime, createdId); }
        assertThatThrownBy(() -> service.create(admin, new AccessDtos.CreateUserRequest(null, "GATE_USER", "duplicate", 500)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS));
        assertThatThrownBy(() -> service.create(admin, new AccessDtos.CreateUserRequest("13800138001", "duplicate phone", 500)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PHONE_ALREADY_EXISTS));

        // The real unique constraint arbitrates concurrent normalized-name creates; the loser adds no points.
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = List.of(executor.submit(() -> concurrentCreate(service, admin, start)),
                    executor.submit(() -> concurrentCreate(service, admin, start)));
            start.countDown();
            var outcomes = List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "USERNAME_ALREADY_EXISTS");
        }
        long concurrentId = accounts.findByUsernameNormalized("gate_concurrent").orElseThrow().id();
        try (Connection runtime = dataSource.getConnection()) { assertGrant(runtime, concurrentId); }

        // Fail after account, point-account AND grant inserts: all three must roll back with the audit failure.
        AccessMapper failing = mock(AccessMapper.class, delegatesTo(mapper));
        doThrow(new IllegalStateException("isolated forced audit failure")).when(failing)
                .insertAudit(anyLong(), anyLong(), anyLong(), any(), any(), any());
        AccessService faultyService = transactional(new AccessService(failing, ids, new ChinaPhoneNormalizer(),
                new UsernameNormalizer(), passwords, activation, jwt, agreements, config, clock), dataSource);
        long beforeFailed;
        try (Connection runtime = dataSource.getConnection()) { beforeFailed = number(runtime, "SELECT count(*) FROM business.point_ledger"); }
        assertThatThrownBy(() -> faultyService.create(admin, new AccessDtos.CreateUserRequest(null, "gate_rollback", "rollback", 500)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(accounts.findByUsernameNormalized("gate_rollback")).isEmpty();
        try (Connection runtime = dataSource.getConnection()) {
            assertThat(number(runtime, "SELECT count(*) FROM business.point_ledger")).isEqualTo(beforeFailed);
            assertThat(number(runtime, "SELECT count(*) FROM business.point_account")).isEqualTo(2);
        }

        // Exercise real username auth / Bcrypt / purpose-scoped activation, without any phone substitution.
        StaticListableBeanFactory beans = new StaticListableBeanFactory(); beans.addBean("accessService", service);
        AuthService auth = new AuthService(accounts, new UsernameNormalizer(), new FixedSaltMd5PasswordEncoder(),
                passwords, new ChinaPhoneNormalizer(), beans.getBeanProvider(AccessService.class), jwt, mock(LoginAttemptGuard.class));
        var first = auth.login(new LoginRequest(" GATE_USER ", created.temporaryPassword()), "127.0.0.1");
        assertThat(first.accessToken()).isNull();
        assertThat(first.tokenType()).isEqualTo("Activation");
        assertThat(first.user().phoneMasked()).isNull();
        String sixCharacterPassword = "g6test";
        var request = new CompleteFirstLoginRequest(first.activationToken(), sixCharacterPassword, true, true, "gate-terms", "gate-privacy");
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertThat(validatorFactory.getValidator().validate(request)).isEmpty();
            assertThat(validatorFactory.getValidator().validate(new CompleteFirstLoginRequest(first.activationToken(),
                    "five5", true, true, "gate-terms", "gate-privacy"))).isNotEmpty();
        }
        var activated = service.complete(request, "127.0.0.1");
        assertThat(activated.tokenType()).isEqualTo("Bearer");
        assertThat(activated.user().phoneMasked()).isNull();
        assertThat(auth.login(new LoginRequest("gate_user", sixCharacterPassword), "127.0.0.1").tokenType()).isEqualTo("Bearer");
        assertThatThrownBy(() -> service.complete(request, "127.0.0.1")).isInstanceOf(BusinessException.class);
        var reset = service.reset(admin, createdId);
        assertThat(reset.credentialState()).isEqualTo("MUST_CHANGE_PASSWORD");
        assertThatThrownBy(() -> auth.login(new LoginRequest("gate_user", sixCharacterPassword), "127.0.0.1"))
                .isInstanceOf(BusinessException.class);
        assertThat(auth.login(new LoginRequest("gate_user", reset.temporaryPassword()), "127.0.0.1").tokenType())
                .isEqualTo("Activation");
        try (Connection runtime = dataSource.getConnection()) {
            assertGrant(runtime, createdId);
            assertThat(oldAccountFingerprint(runtime)).isEqualTo(oldAccounts);
            assertThat(number(runtime, "SELECT count(*) FROM business.account WHERE phone_e164 IS NULL")).isEqualTo(2);
            assertThat(number(runtime, "SELECT count(*) FROM business.account_security_audit WHERE event_type='USER_PROVISIONED'"))
                    .isEqualTo(2);
            assertThatThrownBy(() -> execute(runtime, "CREATE TABLE business.forbidden_username_gate_ddl(id bigint)"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
        System.out.println("DB_GATE username_provisioning=043_to_044_real_runtime_mapper_null_phone_atomic_500_grant_concurrent_unique_six_character_activation_reset_old_accounts_unchanged_passed");
    }

    private static String concurrentCreate(AccessService service, AuthenticatedAccount admin, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            service.create(admin, new AccessDtos.CreateUserRequest(null, "gate_concurrent", "Concurrent gate", 500));
            return "CREATED";
        } catch (BusinessException failure) { return failure.errorCode().name(); }
    }

    private static AccessService transactional(AccessService service, DataSource source) {
        ProxyFactory proxy = new ProxyFactory(service); proxy.setProxyTargetClass(true);
        TransactionInterceptor transaction = new TransactionInterceptor();
        transaction.setTransactionManager(new JdbcTransactionManager(source));
        transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(transaction);
        return (AccessService) proxy.getProxy();
    }

    private static void assertGrant(Connection connection, long userId) throws SQLException {
        assertThat(number(connection, "SELECT count(*) FROM business.point_account WHERE user_id=" + userId
                + " AND available_points=500 AND frozen_points=0")).isEqualTo(1);
        assertThat(number(connection, "SELECT count(*) FROM business.point_ledger WHERE user_id=" + userId)).isEqualTo(1);
        assertThat(number(connection, "SELECT count(*) FROM business.point_ledger WHERE user_id=" + userId
                + " AND available_delta=500 AND available_after=500 AND frozen_delta=0 AND frozen_after=0"
                + " AND entry_type='ADMIN_INITIAL_GRANT' AND business_type='ACCOUNT_PROVISIONING' AND operator_id=" + ADMIN_ID))
                .isEqualTo(1);
    }

    private static String oldAccountFingerprint(Connection connection) throws SQLException {
        return scalar(connection, "SELECT md5(string_agg(to_jsonb(a)::text,',' ORDER BY id)) FROM business.account a WHERE id IN ("
                + ADMIN_ID + "," + OLD_USER_ID + ")");
    }

    private static long number(Connection connection, String sql) throws SQLException { return Long.parseLong(scalar(connection, sql)); }
    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new IllegalStateException("Missing isolated Gate query result");
            return result.getString(1);
        }
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing Gate environment variable " + name);
        return value;
    }
}
