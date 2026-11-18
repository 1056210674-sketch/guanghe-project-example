package com.guanghe.backend.conversation;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConversationProperties.class)
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationConfiguration {

    @Bean(name = "conversationRunExecutor", destroyMethod = "close")
    ExecutorService conversationRunExecutor(ConversationProperties properties) {
        requirePositive(properties.getContextRefTtl(), "CONVERSATION_CONTEXT_REF_TTL");
        requirePositive(properties.getRunDeadline(), "CONVERSATION_RUN_DEADLINE");
        requirePositive(properties.getBrowserSseTimeout(), "CONVERSATION_BROWSER_SSE_TIMEOUT");
        requirePositive(properties.getWorkspaceTtl(), "CONVERSATION_WORKSPACE_TTL");
        requirePositive(properties.getRecoveryGrace(), "CONVERSATION_RECOVERY_GRACE");
        if (properties.getMaxConcurrentRuns() < 1 || properties.getMaxTurnsPerMinute() < 1
                || properties.getMaxTurnsPerDay() < 1 || properties.getMaxVisionTurnsPerDay() < 0
                || properties.getMaxVisionTurnsPerDay() > properties.getMaxTurnsPerDay()) {
            throw new IllegalStateException("Conversation quota configuration is invalid");
        }
        if (properties.getContextRefTtl().compareTo(properties.getRunDeadline()) <= 0) {
            throw new IllegalStateException("CONVERSATION_CONTEXT_REF_TTL must exceed CONVERSATION_RUN_DEADLINE");
        }
        if (properties.getWorkspaceKeyPrefix() == null || properties.getWorkspaceKeyPrefix().isBlank()
                || properties.getWorkspaceMaxBytes() < 16_384) {
            throw new IllegalStateException("Conversation workspace cache configuration is invalid");
        }
        int reserved = Math.addExact(properties.getReservedOutputTokens(),
                Math.addExact(properties.getReservedToolTokens(), properties.getReservedSafetyTokens()));
        if (properties.getTargetInputTokens() < 128 || properties.getTargetInputTokens() > 128_000
                || reserved < 0 || reserved >= properties.getTargetInputTokens()) {
            throw new IllegalStateException("Conversation context token budget is invalid");
        }
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    private void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be positive");
        }
    }
}
