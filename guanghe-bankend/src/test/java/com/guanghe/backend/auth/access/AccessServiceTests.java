package com.guanghe.backend.auth.access;

import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.account.ChinaPhoneNormalizer;
import com.guanghe.backend.auth.account.UsernameNormalizer;
import com.guanghe.backend.auth.config.AccountAccessProperties;
import com.guanghe.backend.auth.registration.RegistrationProperties;
import com.guanghe.backend.auth.security.ActivationTokenService;
import com.guanghe.backend.auth.security.AdaptivePasswordService;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.auth.security.IssuedJwt;
import com.guanghe.backend.auth.security.JwtTokenService;
import com.guanghe.backend.auth.web.CompleteFirstLoginRequest;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AccessServiceTests {
    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    @Mock private AccessMapper mapper;
    @Mock private SnowflakeIdGenerator ids;
    @Mock private AdaptivePasswordService passwords;
    @Mock private ActivationTokenService activations;
    @Mock private JwtTokenService jwt;
    private AccessService service;

    @BeforeEach
    void setUp() {
        service = new AccessService(mapper, ids, new ChinaPhoneNormalizer(), new UsernameNormalizer(), passwords, activations, jwt,
                new RegistrationProperties(false, "terms-v2", "/legal/terms", "privacy-v3", "/legal/privacy", 60),
                new AccountAccessProperties(1_000, Duration.ofMinutes(30), 16, 12),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void provisioningNormalizesPhoneUsesAdaptiveHashAndGrantsPointsOnce() {
        when(ids.nextId()).thenReturn(201L, 202L, 203L);
        when(passwords.encode(any())).thenReturn("$2a$12$" + "x".repeat(53));

        var result = service.create(admin(), new AccessDtos.CreateUserRequest(" 138-0013-8000 ", "  新用户  ", 80));

        ArgumentCaptor<AccountRecord> account = ArgumentCaptor.forClass(AccountRecord.class);
        verify(mapper).insertAccount(account.capture(), eq(atNow()));
        assertThat(account.getValue().usernameNormalized()).isEqualTo("+8613800138000");
        assertThat(account.getValue().phoneE164()).isEqualTo("+8613800138000");
        assertThat(account.getValue().displayName()).isEqualTo("新用户");
        assertThat(account.getValue().passwordAlgorithm()).isEqualTo(AdaptivePasswordService.BCRYPT_V1);
        assertThat(account.getValue().credentialState()).isEqualTo("MUST_CHANGE_PASSWORD");
        verify(mapper).insertPointAccount(201L, 80, atNow());
        verify(mapper).insertGrantLedger(202L, 201L, 80, "admin-initial-grant:201", 1L, atNow());
        verify(mapper).insertAudit(203L, 1L, 201L, "USER_PROVISIONED", "{\"initialPointGrant\":80}", atNow());
        assertThat(result.temporaryPassword()).hasSize(16);
        assertThat(result.toString()).doesNotContain(result.temporaryPassword());
        assertThat(result.user().createdAt()).isEqualTo(atNow());
    }

    @Test
    void usernameProvisioningUsesNullPhoneAndTheSameTemporaryCredentialAndGrantTransaction() {
        when(ids.nextId()).thenReturn(201L, 202L, 203L);
        when(passwords.encode(any())).thenReturn("$2a$12$" + "x".repeat(53));

        var result = service.create(admin(), new AccessDtos.CreateUserRequest(null, "YHG123", "用户名用户", 500));

        ArgumentCaptor<AccountRecord> account = ArgumentCaptor.forClass(AccountRecord.class);
        verify(mapper).insertAccount(account.capture(), eq(atNow()));
        assertThat(account.getValue().username()).isEqualTo("YHG123");
        assertThat(account.getValue().usernameNormalized()).isEqualTo("yhg123");
        assertThat(account.getValue().phoneE164()).isNull();
        assertThat(account.getValue().role()).isEqualTo(AccountRole.USER);
        assertThat(account.getValue().credentialState()).isEqualTo("MUST_CHANGE_PASSWORD");
        assertThat(result.user().username()).isEqualTo("YHG123");
        assertThat(result.user().phone()).isNull();
        assertThat(result.temporaryPassword()).hasSize(16).matches("[A-Za-z2-9]+");
        verify(mapper).insertPointAccount(201L, 500, atNow());
        verify(mapper).insertGrantLedger(202L, 201L, 500, "admin-initial-grant:201", 1L, atNow());
        verify(mapper).insertAudit(203L, 1L, 201L, "USER_PROVISIONED", "{\"initialPointGrant\":500}", atNow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "123abc", "bad-name", "white space", "用户123", " x123", "a123 ", "_abc", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidUsernameIsRejectedBeforeCredentialsOrWrites(String username) {
        assertThatThrownBy(() -> service.create(admin(), new AccessDtos.CreateUserRequest(null, username, "用户", 500)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(mapper, passwords, ids);
    }

    @Test
    void ambiguousOrMissingLoginIdentifiersAreRejectedBeforeWrites() {
        for (var request : java.util.List.of(
                new AccessDtos.CreateUserRequest("13800138000", "valid123", "用户", 500),
                new AccessDtos.CreateUserRequest(null, null, "用户", 500),
                new AccessDtos.CreateUserRequest(" ", null, "用户", 500),
                new AccessDtos.CreateUserRequest(null, " ", "用户", 500))) {
            assertThatThrownBy(() -> service.create(admin(), request))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        verifyNoInteractions(mapper, passwords, ids);
    }

    @Test
    void duplicateUsernameDoesNotCreatePointsOrAnAudit() {
        when(ids.nextId()).thenReturn(201L);
        when(passwords.encode(any())).thenReturn("$2a$12$" + "x".repeat(53));
        doThrow(new DuplicateKeyException("duplicate constraint uk_account_username_normalized"))
                .when(mapper).insertAccount(any(), any());
        assertThatThrownBy(() -> service.create(admin(), new AccessDtos.CreateUserRequest(null, "YHG123", "用户", 500)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS));
        verify(mapper, never()).insertPointAccount(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(mapper, never()).insertAudit(anyLong(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void unrelatedInsertConflictIsNotMisreportedAsAnExistingUsername() {
        when(ids.nextId()).thenReturn(201L);
        when(passwords.encode(any())).thenReturn("$2a$12$" + "x".repeat(53));
        doThrow(new DuplicateKeyException("duplicate constraint account_pkey"))
                .when(mapper).insertAccount(any(), any());
        assertThatThrownBy(() -> service.create(admin(), new AccessDtos.CreateUserRequest(null, "valid123", "用户", 500)))
                .isInstanceOf(DuplicateKeyException.class);
        verify(mapper, never()).insertPointAccount(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void userListReturnsPersistedCreationTimeAfterRefresh() {
        AccountRecord persisted = new AccountRecord(
                41L, "+8613800138000", "+8613800138000", "+8613800138000",
                "$2a$12$" + "p".repeat(53), AdaptivePasswordService.BCRYPT_V1, 4,
                AccountRole.USER, AccountStatus.ACTIVE, "用户", null, "ACTIVE", atNow());
        when(mapper.findUsers(null, 0, 20)).thenReturn(java.util.List.of(persisted));
        when(mapper.countUsers(null)).thenReturn(1L);

        var page = service.list(admin(), null, 1, 20);

        assertThat(page.items()).singleElement()
                .extracting(AccessDtos.UserResponse::createdAt)
                .isEqualTo(atNow());
    }

    @Test
    void zeroGrantCreatesNoSyntheticLedgerAndLimitIsEnforcedBeforeWrites() {
        when(ids.nextId()).thenReturn(301L, 302L);
        when(passwords.encode(any())).thenReturn("$2a$12$" + "y".repeat(53));

        service.create(admin(), new AccessDtos.CreateUserRequest("+8613900138000", "零积分", null));
        verify(mapper).insertPointAccount(301L, 0, atNow());
        verify(mapper, never()).insertGrantLedger(anyLong(), anyLong(), any(Integer.class), any(), anyLong(), any());

        assertThatThrownBy(() -> service.create(admin(),
                new AccessDtos.CreateUserRequest("+8613900138001", "超限", 1_001)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void resetInvalidatesSessionsAndPreviousActivationWithoutChangingRole() {
        AccountRecord user = user("ACTIVE", AccountStatus.ACTIVE, 4);
        when(mapper.findAccount(41L)).thenReturn(Optional.of(user));
        when(passwords.encode(any())).thenReturn("$2a$12$" + "z".repeat(53));
        when(mapper.resetPassword(eq(41L), any(), eq(atNow()))).thenReturn(1);
        when(ids.nextId()).thenReturn(401L);

        var result = service.reset(admin(), 41L);

        verify(mapper).invalidateActivations(41L);
        verify(mapper).insertAudit(401L, 1L, 41L, "PASSWORD_RESET", "{}", atNow());
        assertThat(result.credentialState()).isEqualTo("MUST_CHANGE_PASSWORD");
        assertThat(result.toString()).doesNotContain(result.temporaryPassword());
    }

    @ParameterizedTest
    @ValueSource(strings = {"p6word", "new-password-123"})
    void activationConsumesOnePurposeCredentialAcceptsAgreementsAndIssuesFreshAccessToken(String newPassword) {
        AccountRecord pending = user("MUST_CHANGE_PASSWORD", AccountStatus.ACTIVE, 7);
        AccountRecord active = user("ACTIVE", AccountStatus.ACTIVE, 8);
        AccessRecords.Activation activation = new AccessRecords.Activation(
                501L, 41L, "a".repeat(64), 7, "AVAILABLE", atNow().plusMinutes(20), null, atNow());
        when(activations.decode("activation-jwt"))
                .thenReturn(new ActivationTokenService.ActivationClaims(41L, 7, "a".repeat(64)));
        when(mapper.lockActivation("a".repeat(64))).thenReturn(Optional.of(activation));
        when(mapper.lockAccount(41L)).thenReturn(Optional.of(pending));
        when(passwords.matches(newPassword, pending)).thenReturn(false);
        when(passwords.encode(newPassword)).thenReturn("$2a$12$" + "n".repeat(53));
        when(mapper.completeActivation(eq(41L), eq(7), any(), eq(atNow()))).thenReturn(1);
        when(mapper.consumeActivation(501L, atNow())).thenReturn(1);
        when(ids.nextId()).thenReturn(601L, 602L, 603L);
        when(mapper.findAccount(41L)).thenReturn(Optional.of(active));
        when(jwt.issue(active)).thenReturn(new IssuedJwt("access-jwt", NOW.plus(Duration.ofHours(72))));

        var response = service.complete(new CompleteFirstLoginRequest("activation-jwt", newPassword,
                true, true, "terms-v2", "privacy-v3"), "198.51.100.20");

        verify(mapper).completeActivation(41L, 7, "$2a$12$" + "n".repeat(53), atNow());
        verify(mapper).consumeActivation(501L, atNow());
        verify(mapper).insertConsent(eq(601L), eq(41L), eq("TERMS"), eq("terms-v2"), any(), eq(atNow()));
        verify(mapper).insertConsent(eq(602L), eq(41L), eq("PRIVACY"), eq("privacy-v3"), any(), eq(atNow()));
        verify(mapper).insertAudit(603L, 41L, 41L, "FIRST_LOGIN_COMPLETED", "{}", atNow());
        assertThat(response.accessToken()).isEqualTo("access-jwt");
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void staleAgreementOrNonAdminActorFailsClosed() {
        assertThatThrownBy(() -> service.create(userActor(),
                new AccessDtos.CreateUserRequest("13800138000", "user", 0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.complete(new CompleteFirstLoginRequest("token", "new-password-123",
                true, true, "terms-old", "privacy-v3"), "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.AGREEMENT_VERSION_MISMATCH));
    }

    private AccountRecord user(String credentialState, AccountStatus status, int version) {
        return new AccountRecord(41L, "+8613800138000", "+8613800138000", "+8613800138000",
                "$2a$12$" + "p".repeat(53), AdaptivePasswordService.BCRYPT_V1, version,
                AccountRole.USER, status, "用户", null, credentialState);
    }

    private AuthenticatedAccount admin() {
        return new AuthenticatedAccount(1L, "Guanghe888", AccountRole.ADMIN, 1,
                AccountStatus.ACTIVE, "+8613800000000", "管理员", null);
    }

    private AuthenticatedAccount userActor() {
        return new AuthenticatedAccount(2L, "test001", AccountRole.USER, 1,
                AccountStatus.ACTIVE, "+8613800000001", "用户", null);
    }

    private OffsetDateTime atNow() {
        return OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }
}
