package com.guanghe.backend.auth.security;

import com.guanghe.backend.auth.account.AccountRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class AdaptivePasswordService {
    public static final String BCRYPT_V1 = "BCRYPT_V1";
    private final BCryptPasswordEncoder bcrypt;
    private final FixedSaltMd5PasswordEncoder legacy = new FixedSaltMd5PasswordEncoder();

    public AdaptivePasswordService(com.guanghe.backend.auth.config.AccountAccessProperties properties) {
        bcrypt = new BCryptPasswordEncoder(properties.bcryptStrength());
    }
    public String encode(String raw) { return bcrypt.encode(raw); }
    public boolean matches(String raw, AccountRecord account) {
        return switch (account.passwordAlgorithm()) {
            case BCRYPT_V1 -> bcrypt.matches(raw, account.passwordHash());
            case FixedSaltMd5PasswordEncoder.ALGORITHM_ID -> legacy.matches(raw, account.passwordHash());
            default -> false;
        };
    }
    public boolean isLegacy(AccountRecord account) {
        return FixedSaltMd5PasswordEncoder.ALGORITHM_ID.equals(account.passwordAlgorithm());
    }
}
