package com.guanghe.backend.workflow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhixiaohe.workflow-execution")
public class WorkflowExecutionProperties {
    private boolean enabled;
    private boolean compatibleWorkerReady;
    private Duration lease = Duration.ofMinutes(2);
    private int maxExecutionAttempts = 2;
    private int maxReplans = 1;
    private String exchange = "zhixiaohe.task";
    private String routingKey = "agent.workflow";
    private String queue = "zhixiaohe.agent.workflow";
    private String deadLetterExchange = "zhixiaohe.task.dlx";
    private String deadLetterQueue = "zhixiaohe.agent.workflow.dlq";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isCompatibleWorkerReady() { return compatibleWorkerReady; }
    public void setCompatibleWorkerReady(boolean value) { compatibleWorkerReady = value; }
    public Duration getLease() { return lease; }
    public void setLease(Duration value) { lease = value; }
    public int getMaxExecutionAttempts() { return maxExecutionAttempts; }
    public void setMaxExecutionAttempts(int value) { maxExecutionAttempts = value; }
    public int getMaxReplans() { return maxReplans; }
    public void setMaxReplans(int value) { maxReplans = value; }
    public String getExchange() { return exchange; }
    public void setExchange(String value) { exchange = value; }
    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String value) { routingKey = value; }
    public String getQueue() { return queue; }
    public void setQueue(String value) { queue = value; }
    public String getDeadLetterExchange() { return deadLetterExchange; }
    public void setDeadLetterExchange(String value) { deadLetterExchange = value; }
    public String getDeadLetterQueue() { return deadLetterQueue; }
    public void setDeadLetterQueue(String value) { deadLetterQueue = value; }

    public void validate() {
        if (lease == null || lease.isZero() || lease.isNegative()
                || maxExecutionAttempts < 2 || maxExecutionAttempts > 10
                || maxReplans != 1 || blank(exchange) || blank(routingKey) || blank(queue)
                || blank(deadLetterExchange) || blank(deadLetterQueue)) {
            throw new IllegalStateException("Workflow execution control-plane configuration is invalid");
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
