package com.guanghe.backend.auth.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zhixiaohe.auth.login-rate-limit")
public record LoginRateLimitProperties(
        @Min(1) int maxFailures,
        @Min(1) long windowSeconds,
        @NotBlank String keyPrefix
) {
    public LoginRateLimitProperties {
        if (keyPrefix != null && !keyPrefix.endsWith(":")) {
            throw new IllegalArgumentException("Redis key prefix must end with ':'");
        }
    }
}
