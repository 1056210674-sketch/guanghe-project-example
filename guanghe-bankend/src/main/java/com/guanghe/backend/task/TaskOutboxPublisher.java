package com.guanghe.backend.task;

import com.guanghe.backend.common.observability.ObservabilitySupport;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "outbox-publisher-enabled", havingValue = "true")
public class TaskOutboxPublisher {
    private final TaskControlMapper mapper;
    private final RabbitTemplate rabbit;
    private final ObservabilitySupport observability;

    @Autowired
    public TaskOutboxPublisher(TaskControlMapper mapper, RabbitTemplate rabbit,
                               ObservabilitySupport observability) {
        this.mapper=mapper; this.rabbit=rabbit;
        this.observability = observability;
        this.rabbit.setMandatory(true);
    }

    public TaskOutboxPublisher(TaskControlMapper mapper, RabbitTemplate rabbit) {
        this(mapper, rabbit, ObservabilitySupport.noop());
    }

    @Scheduled(fixedDelayString = "${TASK_OUTBOX_POLL_DELAY_MS:1000}")
    @Transactional
    public void publishOne() {
        TaskRecords.Outbox outbox=mapper.lockNextOutbox(OffsetDateTime.now(ZoneOffset.UTC)).orElse(null);
        if(outbox==null)return;
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
        try (ObservabilitySupport.Operation span = observability.startProducer(
                "java.outbox.publish", "PUBLISH", outbox.traceparent(), false)) {
            span.tag("taskIdRef", observability.reference(outbox.aggregateId()));
            span.tag("executionNo", outbox.executionAttempt());
            span.tag("messaging.system", "rabbitmq");
            span.tag("messaging.destination.name", outbox.routingKey());
            try {
            Message message=MessageBuilder.withBody(outbox.payloadJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .setContentType("application/json").setMessageId(Long.toString(outbox.id()))
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT).setHeader("traceparent",
                            observability.currentTraceparent() == null
                                    ? outbox.traceparent() : observability.currentTraceparent()).build();
            CorrelationData correlation=new CorrelationData(Long.toString(outbox.id()));
            rabbit.send(outbox.exchangeName(), outbox.routingKey(), message, correlation);
            CorrelationData.Confirm confirm=correlation.getFuture().get(5, TimeUnit.SECONDS);
            if(!confirm.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("Rabbit publish not accepted");
            mapper.finishOutbox(outbox.id(),"PUBLISHED",null,now);
            } catch(Exception exception) {
                span.fail("RABBIT_PUBLISH_FAILED", "PUBLISH");
                mapper.failOutbox(outbox.id(),now.plusSeconds(5),"RABBIT_PUBLISH_FAILED",now);
            }
        }
    }
}
