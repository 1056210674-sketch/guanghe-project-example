package com.guanghe.backend.common.config;

import com.guanghe.backend.auth.registration.RegistrationProperties;
import com.guanghe.backend.common.id.SnowflakeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SnowflakeProperties.class, RegistrationProperties.class})
public class ApplicationConfiguration {
}
