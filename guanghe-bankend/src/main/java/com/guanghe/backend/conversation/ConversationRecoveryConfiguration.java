package com.guanghe.backend.conversation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = {"enabled", "recovery-enabled"},
        havingValue = "true", matchIfMissing = true)
class ConversationRecoveryConfiguration { }
