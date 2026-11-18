package com.guanghe.backend.auth.service;

import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.account.UsernameNormalizer;
import com.guanghe.backend.auth.account.ChinaPhoneNormalizer;
import com.guanghe.backend.auth.access.AccessService;
import com.guanghe.backend.auth.ratelimit.LoginAttemptGuard;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.auth.security.FixedSaltMd5PasswordEncoder;
import com.guanghe.backend.auth.security.AdaptivePasswordService;
import com.guanghe.backend.auth.security.IssuedJwt;
import com.guanghe.backend.auth.security.JwtTokenService;
import com.guanghe.backend.auth.web.AuthUserResponse;
import com.guanghe.backend.auth.web.LoginRequest;
import com.guanghe.backend.auth.web.LoginResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthService {

    private final AccountMapper accountMapper;
    private final UsernameNormalizer usernameNormalizer;
    private final FixedSaltMd5PasswordEncoder passwordEncoder;
    private final AdaptivePasswordService adaptivePasswordService;
    private final ChinaPhoneNormalizer phoneNormalizer;
    private final AccessService accessService;
    private final JwtTokenService jwtTokenService;
    private final LoginAttemptGuard loginAttemptGuard;
    private final String dummyPasswordHash;

    @Autowired
    public AuthService(
            AccountMapper accountMapper,
            UsernameNormalizer usernameNormalizer,
            FixedSaltMd5PasswordEncoder passwordEncoder,
            AdaptivePasswordService adaptivePasswordService,
            ChinaPhoneNormalizer phoneNormalizer,
            ObjectProvider<AccessService> accessService,
            JwtTokenService jwtTokenService,
            LoginAttemptGuard loginAttemptGuard
    ) {
        this.accountMapper = accountMapper;
        this.usernameNormalizer = usernameNormalizer;
        this.passwordEncoder = passwordEncoder;
        this.adaptivePasswordService = adaptivePasswordService;
        this.phoneNormalizer = phoneNormalizer;
        this.accessService = accessService.getIfAvailable();
        this.jwtTokenService = jwtTokenService;
        this.loginAttemptGuard = loginAttemptGuard;
        this.dummyPasswordHash = passwordEncoder.encode("fixed-login-timing-placeholder");
    }

    public AuthService(AccountMapper accountMapper,UsernameNormalizer usernameNormalizer,
                       FixedSaltMd5PasswordEncoder passwordEncoder,JwtTokenService jwtTokenService,
                       LoginAttemptGuard loginAttemptGuard) {
        this.accountMapper=accountMapper;this.usernameNormalizer=usernameNormalizer;this.passwordEncoder=passwordEncoder;
        this.adaptivePasswordService=null;this.phoneNormalizer=null;this.accessService=null;
        this.jwtTokenService=jwtTokenService;this.loginAttemptGuard=loginAttemptGuard;
        this.dummyPasswordHash=passwordEncoder.encode("fixed-login-timing-placeholder");
    }

    public LoginResponse login(LoginRequest request, String sourceIp) {
        String usernameNormalized = normalizeLogin(request.username());
        loginAttemptGuard.checkAllowed(usernameNormalized, sourceIp);

        AccountRecord account = accountMapper.findByUsernameNormalized(usernameNormalized).orElse(null);
        String storedHash = account == null ? dummyPasswordHash : account.passwordHash();
        boolean passwordMatches = account != null && adaptivePasswordService != null
                ? adaptivePasswordService.matches(request.password(), account)
                : passwordEncoder.matches(request.password(), storedHash);
        boolean credentialsValid = account != null
                && (FixedSaltMd5PasswordEncoder.ALGORITHM_ID.equals(account.passwordAlgorithm())
                    || AdaptivePasswordService.BCRYPT_V1.equals(account.passwordAlgorithm()))
                && passwordMatches
                && account.status() == AccountStatus.ACTIVE;

        if (!credentialsValid) {
            boolean limited = loginAttemptGuard.recordFailure(usernameNormalized, sourceIp);
            throw new BusinessException(limited ? ErrorCode.RATE_LIMITED : ErrorCode.INVALID_CREDENTIALS);
        }

        loginAttemptGuard.clear(usernameNormalized, sourceIp);
        if (adaptivePasswordService != null && adaptivePasswordService.isLegacy(account)) {
            accountMapper.upgradeLegacyPassword(account.id(), adaptivePasswordService.encode(request.password()));
            if (accessService != null) {
                accessService.auditLegacyUpgrade(account);
            }
            account = new AccountRecord(account.id(),account.username(),account.usernameNormalized(),account.phoneE164(),
                    account.passwordHash(),AdaptivePasswordService.BCRYPT_V1,account.tokenVersion(),account.role(),
                    account.status(),account.displayName(),account.email(),account.credentialState(),account.createdAt());
        }
        if ("MUST_CHANGE_PASSWORD".equals(account.credentialState()) && accessService != null) {
            return accessService.issueFirstLogin(account);
        }
        IssuedJwt jwt = jwtTokenService.issue(account);
        return new LoginResponse(jwt.value(), "Bearer", jwt.expiresAt(), AuthUserResponse.from(account));
    }

    private String normalizeLogin(String value) {
        if (phoneNormalizer != null && phoneNormalizer.looksLikePhone(value)) return phoneNormalizer.normalize(value);
        return usernameNormalizer.normalize(value);
    }

    public AuthUserResponse currentUser(AuthenticatedAccount principal) {
        return AuthUserResponse.from(principal);
    }

    @Transactional
    public void logout(AuthenticatedAccount principal) {
        int updated = accountMapper.incrementTokenVersion(principal.id(), principal.tokenVersion());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
