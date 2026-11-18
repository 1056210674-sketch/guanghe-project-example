package com.guanghe.backend.auth.security;

import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.config.AccountAccessProperties;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptivePasswordServiceTests {
    private final AdaptivePasswordService passwords = new AdaptivePasswordService(
            new AccountAccessProperties(0, Duration.ofMinutes(30), 16, 4));

    @ParameterizedTest
    @ValueSource(strings = {"p6word", "existing-long-password-123"})
    void sixCharacterAndExistingLongPasswordsKeepTheSameSaltedHashAlgorithm(String raw) {
        String hash = passwords.encode(raw);
        AccountRecord account = account(hash, AdaptivePasswordService.BCRYPT_V1);

        assertThat(hash).startsWith("$2a$04$").isNotEqualTo(raw);
        assertThat(passwords.encode(raw)).isNotEqualTo(hash);
        assertThat(passwords.matches(raw, account)).isTrue();
        assertThat(passwords.matches(raw + "x", account)).isFalse();
        assertThat(passwords.isLegacy(account)).isFalse();
        assertThat(account.passwordHash()).isEqualTo(hash);
    }

    @ParameterizedTest
    @ValueSource(strings = {"p6word", "existing-long-password-123"})
    void existingLegacyHashesRemainReadableWithoutChangingTheirStoredValue(String raw) {
        String hash = new FixedSaltMd5PasswordEncoder().encode(raw);
        AccountRecord account = account(hash, FixedSaltMd5PasswordEncoder.ALGORITHM_ID);

        assertThat(passwords.matches(raw, account)).isTrue();
        assertThat(passwords.matches(raw + "x", account)).isFalse();
        assertThat(passwords.isLegacy(account)).isTrue();
        assertThat(account.passwordHash()).isEqualTo(hash);
    }

    private AccountRecord account(String hash, String algorithm) {
        return new AccountRecord(41L, "existing-user", "existing-user", "+8613800138000",
                hash, algorithm, 1, AccountRole.USER, AccountStatus.ACTIVE, "用户", null, "ACTIVE");
    }
}
