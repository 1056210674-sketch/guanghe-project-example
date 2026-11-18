package com.guanghe.backend.media;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfiguration {
    public MediaConfiguration(MediaProperties properties) { properties.validate(); }
}
