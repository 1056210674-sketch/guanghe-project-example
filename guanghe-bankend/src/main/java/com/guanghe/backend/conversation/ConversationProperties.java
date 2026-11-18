package com.guanghe.backend.conversation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhixiaohe.conversation")
public class ConversationProperties {

    private boolean enabled = true;
    private boolean businessOutcomeEnabled;
    private boolean resourceLookupEnabled;
    private Duration contextRefTtl = Duration.ofMinutes(5);
    private Duration runDeadline = Duration.ofMinutes(3);
    private Duration browserSseTimeout = Duration.ofMinutes(2);
    private boolean recoveryEnabled = true;
    private Duration recoveryGrace = Duration.ofSeconds(30);
    private int maxConcurrentRuns = 2;
    private int maxTurnsPerMinute = 10;
    private int maxTurnsPerDay = 200;
    private int maxVisionTurnsPerDay = 40;
    private int webSearchMaxProviderCalls = 6;
    private String workspaceKeyPrefix = "zhixiaohe:conversation:workspace:v1:";
    private Duration workspaceTtl = Duration.ofMinutes(30);
    private int workspaceMaxBytes = 196_608;
    private int targetInputTokens = 128_000;
    private int reservedOutputTokens = 16_000;
    private int reservedToolTokens = 8_000;
    private int reservedSafetyTokens = 4_000;

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isBusinessOutcomeEnabled() { return businessOutcomeEnabled; }
    public void setBusinessOutcomeEnabled(boolean value) { businessOutcomeEnabled = value; }
    public boolean isResourceLookupEnabled() { return resourceLookupEnabled; }
    public void setResourceLookupEnabled(boolean value) { resourceLookupEnabled = value; }

    public boolean isRecoveryEnabled() { return recoveryEnabled; }
    public void setRecoveryEnabled(boolean value) { recoveryEnabled = value; }
    public Duration getRecoveryGrace() { return recoveryGrace; }
    public void setRecoveryGrace(Duration value) { recoveryGrace = value; }
    public int getMaxConcurrentRuns() { return maxConcurrentRuns; }
    public void setMaxConcurrentRuns(int value) { maxConcurrentRuns = value; }
    public int getMaxTurnsPerMinute() { return maxTurnsPerMinute; }
    public void setMaxTurnsPerMinute(int value) { maxTurnsPerMinute = value; }
    public int getMaxTurnsPerDay() { return maxTurnsPerDay; }
    public void setMaxTurnsPerDay(int value) { maxTurnsPerDay = value; }
    public int getMaxVisionTurnsPerDay() { return maxVisionTurnsPerDay; }
    public void setMaxVisionTurnsPerDay(int value) { maxVisionTurnsPerDay = value; }
    public int getWebSearchMaxProviderCalls() { return webSearchMaxProviderCalls; }
    public void setWebSearchMaxProviderCalls(int value) {
        if (value < 0 || value > 6) {
            throw new IllegalArgumentException("Conversation web search provider budget must be between 0 and 6");
        }
        webSearchMaxProviderCalls = value;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getContextRefTtl() {
        return contextRefTtl;
    }

    public void setContextRefTtl(Duration contextRefTtl) {
        this.contextRefTtl = contextRefTtl;
    }

    public Duration getRunDeadline() {
        return runDeadline;
    }

    public void setRunDeadline(Duration runDeadline) {
        this.runDeadline = runDeadline;
    }

    public Duration getBrowserSseTimeout() {
        return browserSseTimeout;
    }

    public void setBrowserSseTimeout(Duration browserSseTimeout) {
        this.browserSseTimeout = browserSseTimeout;
    }

    public String getWorkspaceKeyPrefix() {
        return workspaceKeyPrefix;
    }

    public void setWorkspaceKeyPrefix(String workspaceKeyPrefix) {
        this.workspaceKeyPrefix = workspaceKeyPrefix;
    }

    public Duration getWorkspaceTtl() {
        return workspaceTtl;
    }

    public void setWorkspaceTtl(Duration workspaceTtl) {
        this.workspaceTtl = workspaceTtl;
    }

    public int getWorkspaceMaxBytes() {
        return workspaceMaxBytes;
    }

    public void setWorkspaceMaxBytes(int workspaceMaxBytes) {
        this.workspaceMaxBytes = workspaceMaxBytes;
    }

    public int getTargetInputTokens() {
        return targetInputTokens;
    }

    public void setTargetInputTokens(int targetInputTokens) {
        this.targetInputTokens = targetInputTokens;
    }

    public int getReservedOutputTokens() {
        return reservedOutputTokens;
    }

    public void setReservedOutputTokens(int reservedOutputTokens) {
        this.reservedOutputTokens = reservedOutputTokens;
    }

    public int getReservedToolTokens() {
        return reservedToolTokens;
    }

    public void setReservedToolTokens(int reservedToolTokens) {
        this.reservedToolTokens = reservedToolTokens;
    }

    public int getReservedSafetyTokens() {
        return reservedSafetyTokens;
    }

    public void setReservedSafetyTokens(int reservedSafetyTokens) {
        this.reservedSafetyTokens = reservedSafetyTokens;
    }
}
