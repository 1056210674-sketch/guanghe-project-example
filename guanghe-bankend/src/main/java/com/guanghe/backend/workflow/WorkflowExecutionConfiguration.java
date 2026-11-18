package com.guanghe.backend.workflow;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowExecutionProperties.class)
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class WorkflowExecutionConfiguration {
    public WorkflowExecutionConfiguration(WorkflowExecutionProperties properties) {
        properties.validate();
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhixiaohe.workflow-execution", name = "enabled",
            havingValue = "true")
    Declarables workflowRabbitTopology(WorkflowExecutionProperties properties) {
        DirectExchange exchange = new DirectExchange(properties.getExchange(), true, false);
        DirectExchange dlx = new DirectExchange(properties.getDeadLetterExchange(), true, false);
        Queue queue = QueueBuilder.durable(properties.getQueue())
                .deadLetterExchange(properties.getDeadLetterExchange())
                .deadLetterRoutingKey(properties.getRoutingKey()).build();
        Queue dlq = QueueBuilder.durable(properties.getDeadLetterQueue()).build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(properties.getRoutingKey());
        Binding deadLetterBinding = BindingBuilder.bind(dlq).to(dlx).with(properties.getRoutingKey());
        return new Declarables(exchange, dlx, queue, dlq, binding, deadLetterBinding);
    }
}
