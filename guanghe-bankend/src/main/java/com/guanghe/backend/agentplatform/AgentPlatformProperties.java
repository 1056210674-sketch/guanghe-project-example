package com.guanghe.backend.agentplatform;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhixiaohe.agent-platform")
public class AgentPlatformProperties {
    private boolean enabled = true;
    private boolean internalAgentsEnabled;
    private Set<Long> internalAgentUserIds = Set.of();
    private boolean automaticMonitorEnabled;
    private java.time.LocalDate monitorBudgetEarliestTrustedDate;
    private int nativeActionMaxPointsPerAction = 50;
    private int nativeActionMaxPointsPerDay = 200;

    public int getNativeActionMaxPointsPerAction() { return nativeActionMaxPointsPerAction; }
    public void setNativeActionMaxPointsPerAction(int value) { nativeActionMaxPointsPerAction = value; }
    public int getNativeActionMaxPointsPerDay() { return nativeActionMaxPointsPerDay; }
    public void setNativeActionMaxPointsPerDay(int value) { nativeActionMaxPointsPerDay = value; }

    public boolean isAutomaticMonitorEnabled() { return automaticMonitorEnabled; }
    public void setAutomaticMonitorEnabled(boolean value) { automaticMonitorEnabled = value; }
    public java.time.LocalDate getMonitorBudgetEarliestTrustedDate() { return monitorBudgetEarliestTrustedDate; }
    public void setMonitorBudgetEarliestTrustedDate(java.time.LocalDate value) { monitorBudgetEarliestTrustedDate = value; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isInternalAgentsEnabled() { return internalAgentsEnabled; }
    public void setInternalAgentsEnabled(boolean value) { internalAgentsEnabled = value; }
    public Set<Long> getInternalAgentUserIds() { return internalAgentUserIds; }
    public void setInternalAgentUserIds(Set<Long> value) {
        internalAgentUserIds = value == null ? Set.of() : Set.copyOf(value);
    }
}
