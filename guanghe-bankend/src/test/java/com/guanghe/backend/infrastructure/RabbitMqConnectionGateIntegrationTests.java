package com.guanghe.backend.infrastructure;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RABBIT_GATE_ENABLED", matches = "true")
class RabbitMqConnectionGateIntegrationTests {

    @Test
    void remoteIdentityCanPublishConsumeAckAndRequeueOnlyInTheTestVhost() throws Exception {
        String vhost = required("RABBIT_GATE_VHOST");
        if (!"/zhixiaohe-test".equals(vhost)) {
            throw new IllegalStateException("RabbitMQ Gate may only target /zhixiaohe-test");
        }
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(required("RABBIT_GATE_HOST"));
        factory.setPort(Integer.parseInt(required("RABBIT_GATE_PORT")));
        factory.setVirtualHost(vhost);
        factory.setUsername(required("RABBIT_GATE_USERNAME"));
        factory.setPassword(required("RABBIT_GATE_PASSWORD"));
        factory.setConnectionTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setHandshakeTimeout((int) Duration.ofSeconds(10).toMillis());

        assertThat(required("RABBIT_GATE_SSL_ENABLED")).isEqualToIgnoringCase("true");
        assertThat(factory.getPort()).isNotEqualTo(5672);
        factory.useSslProtocol(TestTlsSupport.sslContext());
        factory.enableHostnameVerification();
        factory.setAutomaticRecoveryEnabled(false);

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String exchange = "zhixiaohe.test.gate.exchange." + suffix;
        String queue = "zhixiaohe.test.gate.queue." + suffix;
        String routingKey = "gate";
        try (Connection connection = factory.newConnection("zhixiaohe-remote-gate-probe");
             Channel channel = connection.createChannel()) {
            assertThat(connection.isOpen()).isTrue();
            Map<String, Object> properties = connection.getServerProperties();
            assertMinimumCompatibleVersion(String.valueOf(properties.get("version")));

            channel.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, false, true, Map.of());
            channel.queueDeclare(queue, false, true, true, Map.of());
            channel.queueBind(queue, exchange, routingKey);
            try {
                publish(channel, exchange, routingKey, "ack-message");
                GetResponse acknowledged = requireMessage(channel, queue);
                assertThat(new String(acknowledged.getBody(), StandardCharsets.UTF_8))
                        .isEqualTo("ack-message");
                channel.basicAck(acknowledged.getEnvelope().getDeliveryTag(), false);

                publish(channel, exchange, routingKey, "retry-message");
                GetResponse firstDelivery = requireMessage(channel, queue);
                channel.basicNack(firstDelivery.getEnvelope().getDeliveryTag(), false, true);
                GetResponse redelivery = requireMessage(channel, queue);
                assertThat(redelivery.getEnvelope().isRedeliver()).isTrue();
                assertThat(new String(redelivery.getBody(), StandardCharsets.UTF_8))
                        .isEqualTo("retry-message");
                channel.basicAck(redelivery.getEnvelope().getDeliveryTag(), false);
            } finally {
                channel.queueDelete(queue);
                channel.exchangeDelete(exchange);
            }

            System.out.println("RABBIT_GATE vhost=/zhixiaohe-test authenticated=yes");
            System.out.println("RABBIT_GATE tls_certificate_and_hostname=verified");
            System.out.println("RABBIT_GATE publish_consume_ack=yes");
            System.out.println("RABBIT_GATE failed_delivery_requeued=yes");
        }
    }

    private void assertMinimumCompatibleVersion(String version) {
        var matcher = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.|$)").matcher(version);
        assertThat(matcher.find()).as("RabbitMQ server version should be semantic").isTrue();
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        assertThat(major > 3 || major == 3 && minor >= 13)
                .as("RabbitMQ must be at least 3.13 or expose equivalent tested core capabilities")
                .isTrue();
    }

    private void publish(Channel channel, String exchange, String routingKey, String body) throws Exception {
        channel.basicPublish(
                exchange,
                routingKey,
                new AMQP.BasicProperties.Builder().contentType("text/plain").deliveryMode(1).build(),
                body.getBytes(StandardCharsets.UTF_8)
        );
    }

    private GetResponse requireMessage(Channel channel, String queue) throws Exception {
        GetResponse response = channel.basicGet(queue, false);
        assertThat(response).isNotNull();
        return response;
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the RabbitMQ Gate");
        }
        return value;
    }
}
