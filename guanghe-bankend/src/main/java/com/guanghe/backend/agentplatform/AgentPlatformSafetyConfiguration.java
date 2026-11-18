package com.guanghe.backend.agentplatform;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Platform identity enforcement is mandatory outside the infrastructure-free mock profile. */
@Configuration(proxyBeanMethods = false)
@Profile("!mock")
public class AgentPlatformSafetyConfiguration {
    static final String CONFIGURATION_ERROR = "AGENT_PLATFORM_ENFORCEMENT_REQUIRED";

    @Bean
    static BeanFactoryPostProcessor requireAgentPlatformEnforcement(Environment environment) {
        return beanFactory -> {
            String configured = environment.getProperty("zhixiaohe.agent-platform.enabled");
            // Match ConditionalOnProperty's exact true semantics, not Boolean conversion aliases
            // such as 'yes' that would leave the platform beans absent and permit legacy fallback.
            if (configured != null && !"true".equalsIgnoreCase(configured)) {
                throw new IllegalStateException(CONFIGURATION_ERROR
                        + ": zhixiaohe.agent-platform.enabled must be true outside mock");
            }
        };
    }
}
