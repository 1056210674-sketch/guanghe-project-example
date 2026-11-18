package com.guanghe.backend.agentplatform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "zhixiaohe.agent-platform", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentPlatformProperties.class)
public class AgentPlatformConfiguration { }
