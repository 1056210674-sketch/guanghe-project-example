package com.guanghe.backend.auth.service;

import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountIdentity;
import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.account.ChinaPhoneNormalizer;
import com.guanghe.backend.auth.account.UsernameNormalizer;
import com.guanghe.backend.auth.access.AccessService;
import com.guanghe.backend.auth.ratelimit.LoginAttemptGuard;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.auth.security.AdaptivePasswordService;
import com.guanghe.backend.auth.security.FixedSaltMd5PasswordEncoder;
import com.guanghe.backend.auth.security.IssuedJwt;
import com.guanghe.backend.auth.security.JwtTokenService;
import com.guanghe.backend.auth.web.LoginRequest;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTests {

    @Mock
    private AccountMapper accountMapper;
    @Mock
    private JwtTokenService jwtTokenService;
    @Mock
    private LoginAttemptGuard loginAttemptGuard;
    @Mock
    private AdaptivePasswordService adaptivePasswordService;
    @Mock
    private AccessService accessService;
    @Mock
    private ObjectProvider<AccessService> accessServiceProvider;

    private FixedSaltMd5PasswordEncoder passwordEncoder;
    private AuthService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new FixedSaltMd5PasswordEncoder();
        service = new AuthService(
                accountMapper,
                new UsernameNormalizer(),
                passwordEncoder,
                jwtTokenService,
                loginAttemptGuard
        );
    }

    @Test
    void authenticatesWithNormalizedUsernameAndReturnsRedactedTokenResponse() {
        AccountRecord account = account("Guanghe888", "correct", AccountRole.ADMIN, 3);
        when(accountMapper.findByUsernameNormalized("guanghe888")).thenReturn(Optional.of(account));
        when(jwtTokenService.issue(account)).thenReturn(new IssuedJwt("signed-token", Instant.parse("2026-08-28T00:00:00Z")));

        var response = service.login(new LoginRequest("  GUANGHE888 ", "correct"), "127.0.0.1");

        assertThat(response.accessToken()).isEqualTo("signed-token");
        assertThat(response.user().id()).isEqualTo(Long.toString(account.id()));
        assertThat(response.user().role()).isEqualTo("ADMIN");
        assertThat(response.toString()).doesNotContain("signed-token");
        verify(loginAttemptGuard).clear("guanghe888", "127.0.0.1");
    }

    @Test
    void missingAccountAndWrongPasswordHaveTheSamePublicFailure() {
        AccountRecord account = account("test001", "correct", AccountRole.USER, 1);
        when(accountMapper.findByUsernameNormalized("missing")).thenReturn(Optional.empty());
        when(accountMapper.findByUsernameNormalized("test001")).thenReturn(Optional.of(account));

        assertInvalidCredentials(() -> service.login(new LoginRequest("missing", "wrong"), "127.0.0.1"));
        assertInvalidCredentials(() -> service.login(new LoginRequest("test001", "wrong"), "127.0.0.1"));
        verify(jwtTokenService, never()).issue(account);
    }

    @Test
    void redisFailurePreventsTokenIssuanceEvenForValidCredentials() {
        AccountRecord account = account("test001", "correct", AccountRole.USER, 1);
        when(accountMapper.findByUsernameNormalized("test001")).thenReturn(Optional.of(account));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.AUTHENTICATION_DEPENDENCY_UNAVAILABLE))
                .when(loginAttemptGuard).clear("test001", "127.0.0.1");

        assertThatThrownBy(() -> service.login(new LoginRequest("test001", "correct"), "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_DEPENDENCY_UNAVAILABLE));
        verify(jwtTokenService, never()).issue(account);
    }

    @Test
    void mobileLoginUsesE164AndTemporaryCredentialOnlyStartsFirstLoginFlow() {
        AccountRecord pending = new AccountRecord(88L, "+8613800138000", "+8613800138000",
                "+8613800138000", "$2a$12$" + "x".repeat(53), AdaptivePasswordService.BCRYPT_V1, 2,
                AccountRole.USER, AccountStatus.ACTIVE, "待激活用户", null, "MUST_CHANGE_PASSWORD");
        when(accessServiceProvider.getIfAvailable()).thenReturn(accessService);
        AuthService adaptiveService = new AuthService(accountMapper, new UsernameNormalizer(), passwordEncoder,
                adaptivePasswordService, new ChinaPhoneNormalizer(), accessServiceProvider,
                jwtTokenService, loginAttemptGuard);
        when(accountMapper.findByUsernameNormalized("+8613800138000")).thenReturn(Optional.of(pending));
        when(adaptivePasswordService.matches("temporary", pending)).thenReturn(true);
        when(adaptivePasswordService.isLegacy(pending)).thenReturn(false);
        var activationResponse = new com.guanghe.backend.auth.web.LoginResponse(
                null, "Activation", Instant.parse("2026-09-11T00:30:00Z"),
                com.guanghe.backend.auth.web.AuthUserResponse.from(pending), "COMPLETE_FIRST_LOGIN",
                "activation-token", null, null);
        when(accessService.issueFirstLogin(pending)).thenReturn(activationResponse);

        var response = adaptiveService.login(new LoginRequest("138-0013-8000", "temporary"), "127.0.0.1");

        assertThat(response.accessToken()).isNull();
        assertThat(response.nextAction()).isEqualTo("COMPLETE_FIRST_LOGIN");
        verify(accountMapper).findByUsernameNormalized("+8613800138000");
        verify(jwtTokenService, never()).issue(pending);
    }

    @Test
    void logoutUsesTheTokenVersionAsAnAtomicCompareAndIncrementGuard() {
        AccountRecord account = account("test001", "correct", AccountRole.USER, 7);
        when(accountMapper.incrementTokenVersion(account.id(), 7)).thenReturn(1);

        service.logout(AuthenticatedAccount.from(identity(account)));

        verify(accountMapper).incrementTokenVersion(account.id(), 7);
    }

    @Test
    void staleLogoutIsRejected() {
        AccountRecord account = account("test001", "correct", AccountRole.USER, 7);
        when(accountMapper.incrementTokenVersion(account.id(), 7)).thenReturn(0);

        assertThatThrownBy(() -> service.logout(AuthenticatedAccount.from(identity(account))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    private void assertInvalidCredentials(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(exception.getMessage()).isEqualTo("账号或密码错误");
                });
    }

    private AccountRecord account(String username, String rawPassword, AccountRole role, int tokenVersion) {
        return new AccountRecord(
                362387869691905L,
                username,
                username.toLowerCase(),
                "+8610000000002",
                passwordEncoder.encode(rawPassword),
                FixedSaltMd5PasswordEncoder.ALGORITHM_ID,
                tokenVersion,
                role,
                AccountStatus.ACTIVE,
                null,
                null
        );
    }

    private AccountIdentity identity(AccountRecord account) {
        return new AccountIdentity(
                account.id(), account.username(), account.phoneE164(), account.tokenVersion(), account.role(),
                account.status(), account.displayName(), account.email()
        );
    }
}
