package com.guanghe.backend.auth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("zhixiaohe.auth.account-access")
public record AccountAccessProperties(
        @Min(0) @Max(1000000) int initialPointGrantMax,
        Duration activationTokenTtl,
        @Min(12) @Max(32) int temporaryPasswordLength,
        @Min(4) @Max(15) int bcryptStrength
) {
    public AccountAccessProperties {
        if (activationTokenTtl == null || activationTokenTtl.isZero() || activationTokenTtl.isNegative()) {
            throw new IllegalArgumentException("activationTokenTtl must be positive");
        }
    }
}
