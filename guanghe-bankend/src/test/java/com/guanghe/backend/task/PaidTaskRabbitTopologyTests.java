package com.guanghe.backend.task;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PaidTaskRabbitTopologyTests {
    private static final String[] REQUIRED_PAID_TASK_PROPERTIES = {
            "zhixiaohe.paid-task.enabled=true",
            "zhixiaohe.paid-task.token-secret=test-only-secret-material-at-least-32-bytes",
            "zhixiaohe.paid-task.image-mock-pricing-version-id=6001",
            "zhixiaohe.paid-task.image-mock-points=7",
            "zhixiaohe.paid-task.video-test-pricing-version-id=7001",
            "zhixiaohe.paid-task.video-test-points=11"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PaidTaskConfiguration.class)
            .withPropertyValues(REQUIRED_PAID_TASK_PROPERTIES);

    @Test
    void topologyIsAbsentUntilOutboxPublishingIsExplicitlyEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(Declarables.class);
        });
    }

    @Test
    void declaresDurableImageVideoGeoAndArticleTopology() {
        contextRunner.withPropertyValues("zhixiaohe.paid-task.outbox-publisher-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Declarables topology = context.getBean(Declarables.class);

                    Map<String, DirectExchange> exchanges = topology.getDeclarablesByType(DirectExchange.class)
                            .stream().collect(java.util.stream.Collectors.toMap(DirectExchange::getName, value -> value));
                    assertThat(exchanges).containsOnlyKeys("zhixiaohe.task", "zhixiaohe.task.dlx");
                    assertThat(exchanges.values()).allMatch(DirectExchange::isDurable);

                    Map<String, Queue> queues = topology.getDeclarablesByType(Queue.class).stream()
                            .collect(java.util.stream.Collectors.toMap(Queue::getName, value -> value));
                    assertThat(queues).containsOnlyKeys("zhixiaohe.agent.image", "zhixiaohe.agent.image.dlq",
                            "zhixiaohe.agent.video", "zhixiaohe.agent.video.dlq",
                            "zhixiaohe.agent.geo", "zhixiaohe.agent.geo.dlq",
                            "zhixiaohe.agent.article", "zhixiaohe.agent.article.dlq");
                    assertThat(queues.values()).allMatch(Queue::isDurable);
                    assertThat(queues.get("zhixiaohe.agent.image").getArguments())
                            .containsEntry("x-dead-letter-exchange", "zhixiaohe.task.dlx")
                            .containsEntry("x-dead-letter-routing-key", "agent.image");
                    assertThat(queues.get("zhixiaohe.agent.geo").getArguments())
                            .containsEntry("x-dead-letter-exchange", "zhixiaohe.task.dlx")
                            .containsEntry("x-dead-letter-routing-key", "agent.geo");

                    assertThat(topology.getDeclarablesByType(Binding.class))
                            .anySatisfy(binding -> assertBinding(binding, "zhixiaohe.task",
                                    "zhixiaohe.agent.image", "agent.image"))
                            .anySatisfy(binding -> assertBinding(binding, "zhixiaohe.task.dlx",
                                    "zhixiaohe.agent.image.dlq", "agent.image"))
                            .anySatisfy(binding -> assertBinding(binding, "zhixiaohe.task",
                                    "zhixiaohe.agent.video", "agent.video"))
                            .anySatisfy(binding -> assertBinding(binding, "zhixiaohe.task.dlx",
                                    "zhixiaohe.agent.video.dlq", "agent.video"))
                            .anySatisfy(binding -> assertBinding(binding, "zhixiaohe.task",
                                    "zhixiaohe.agent.geo", "agent.geo"))
                            .anySatisfy(binding -> assertBinding(binding, "zhixiaohe.task.dlx",
                                    "zhixiaohe.agent.geo.dlq", "agent.geo"))
                            .anySatisfy(binding -> assertBinding(binding, "zhixiaohe.task",
                                    "zhixiaohe.agent.article", "agent.article"))
                            .anySatisfy(binding -> assertBinding(binding, "zhixiaohe.task.dlx",
                                    "zhixiaohe.agent.article.dlq", "agent.article"));
                });
    }

    private void assertBinding(Binding binding, String exchange, String queue, String routingKey) {
        assertThat(binding.getExchange()).isEqualTo(exchange);
        assertThat(binding.getDestination()).isEqualTo(queue);
        assertThat(binding.getRoutingKey()).isEqualTo(routingKey);
        assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
    }
}
