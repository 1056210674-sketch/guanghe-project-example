package com.guanghe.backend.auth.registration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zhixiaohe.registration")
public record RegistrationProperties(
        boolean enabled,
        @NotBlank String termsVersion,
        @NotBlank String termsUrl,
        @NotBlank String privacyVersion,
        @NotBlank String privacyUrl,
        @Min(1) int smsCooldownSeconds
) {
}
