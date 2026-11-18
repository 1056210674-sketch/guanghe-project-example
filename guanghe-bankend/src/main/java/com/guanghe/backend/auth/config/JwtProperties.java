package com.guanghe.backend.auth.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zhixiaohe.auth.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @Min(60) long accessTokenTtlSeconds,
        @NotBlank String secretBase64
) {
}
