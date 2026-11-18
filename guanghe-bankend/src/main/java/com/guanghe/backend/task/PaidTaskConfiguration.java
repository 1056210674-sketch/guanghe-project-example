package com.guanghe.backend.task;

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
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaidTaskProperties.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class PaidTaskConfiguration {
    public PaidTaskConfiguration(PaidTaskProperties properties) {
        properties.validate();
    }

    @Bean
    @ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "outbox-publisher-enabled", havingValue = "true")
    Declarables paidTaskRabbitTopology(PaidTaskProperties properties) {
        DirectExchange taskExchange = new DirectExchange(properties.getExchange(), true, false);
        DirectExchange deadLetterExchange = new DirectExchange(properties.getDeadLetterExchange(), true, false);
        Queue imageQueue = QueueBuilder.durable(properties.getImageQueue())
                .deadLetterExchange(properties.getDeadLetterExchange())
                .deadLetterRoutingKey(properties.getImageRoutingKey())
                .build();
        Queue imageDeadLetterQueue = QueueBuilder.durable(properties.getImageDeadLetterQueue()).build();
        Queue videoQueue = QueueBuilder.durable(properties.getVideoQueue())
                .deadLetterExchange(properties.getDeadLetterExchange())
                .deadLetterRoutingKey(properties.getVideoRoutingKey())
                .build();
        Queue videoDeadLetterQueue = QueueBuilder.durable(properties.getVideoDeadLetterQueue()).build();
        Queue geoQueue = QueueBuilder.durable(properties.getGeoQueue())
                .deadLetterExchange(properties.getDeadLetterExchange())
                .deadLetterRoutingKey(properties.getGeoRoutingKey())
                .build();
        Queue geoDeadLetterQueue = QueueBuilder.durable(properties.getGeoDeadLetterQueue()).build();
        Queue articleQueue = QueueBuilder.durable(properties.getArticleQueue())
                .deadLetterExchange(properties.getDeadLetterExchange())
                .deadLetterRoutingKey(properties.getArticleRoutingKey()).build();
        Queue articleDeadLetterQueue = QueueBuilder.durable(properties.getArticleDeadLetterQueue()).build();
        Binding imageBinding = BindingBuilder.bind(imageQueue)
                .to(taskExchange)
                .with(properties.getImageRoutingKey());
        Binding deadLetterBinding = BindingBuilder.bind(imageDeadLetterQueue)
                .to(deadLetterExchange)
                .with(properties.getImageRoutingKey());
        Binding videoBinding = BindingBuilder.bind(videoQueue).to(taskExchange)
                .with(properties.getVideoRoutingKey());
        Binding videoDeadLetterBinding = BindingBuilder.bind(videoDeadLetterQueue)
                .to(deadLetterExchange).with(properties.getVideoRoutingKey());
        Binding geoBinding = BindingBuilder.bind(geoQueue).to(taskExchange)
                .with(properties.getGeoRoutingKey());
        Binding geoDeadLetterBinding = BindingBuilder.bind(geoDeadLetterQueue)
                .to(deadLetterExchange).with(properties.getGeoRoutingKey());
        Binding articleBinding = BindingBuilder.bind(articleQueue).to(taskExchange)
                .with(properties.getArticleRoutingKey());
        Binding articleDeadLetterBinding = BindingBuilder.bind(articleDeadLetterQueue)
                .to(deadLetterExchange).with(properties.getArticleRoutingKey());
        return new Declarables(taskExchange, deadLetterExchange, imageQueue, imageDeadLetterQueue,
                videoQueue, videoDeadLetterQueue, geoQueue, geoDeadLetterQueue,
                articleQueue, articleDeadLetterQueue,
                imageBinding, deadLetterBinding, videoBinding, videoDeadLetterBinding,
                geoBinding, geoDeadLetterBinding, articleBinding, articleDeadLetterBinding);
    }
}
