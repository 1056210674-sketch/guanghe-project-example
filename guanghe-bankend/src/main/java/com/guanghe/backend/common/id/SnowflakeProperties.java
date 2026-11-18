package com.guanghe.backend.common.id;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("zhixiaohe.id")
public record SnowflakeProperties(
        @Min(0) @Max(31) long workerId,
        @Min(0) @Max(31) long datacenterId
) {
}

