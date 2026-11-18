package com.guanghe.backend.task;

import com.guanghe.backend.common.observability.ObservabilitySupport;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskOutboxPublisherTests {
    private TaskControlMapper mapper;
    private RabbitTemplate rabbit;
    private TaskOutboxPublisher publisher;
    private TaskRecords.Outbox outbox;

    @BeforeEach
    void setUp() {
        mapper = mock(TaskControlMapper.class);
        rabbit = mock(RabbitTemplate.class);
        publisher = new TaskOutboxPublisher(mapper, rabbit);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        outbox = new TaskRecords.Outbox(701L, 41L, 901L, 1,
                "{\"eventId\":\"701\",\"taskId\":\"901\"}",
                "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01",
                "zhixiaohe.task", "agent.image",
                "PENDING", 0, now.minusSeconds(1), now);
    }

    @Test
    void publisherUsesThePersistedVideoRoutingKeyWithoutImageFallback() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TaskRecords.Outbox video = new TaskRecords.Outbox(702L, 41L, 902L, 1,
                "{\"eventId\":\"702\",\"taskId\":\"902\"}",
                "00-" + "c".repeat(32) + "-" + "d".repeat(16) + "-01",
                "zhixiaohe.task", "agent.video",
                "PENDING", 0, now.minusSeconds(1), now);
        when(mapper.lockNextOutbox(any())).thenReturn(Optional.of(video));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher.publishOne();

        verify(rabbit).send(org.mockito.ArgumentMatchers.eq("zhixiaohe.task"),
                org.mockito.ArgumentMatchers.eq("agent.video"), any(Message.class), any(CorrelationData.class));
        verify(mapper).finishOutbox(org.mockito.ArgumentMatchers.eq(702L),
                org.mockito.ArgumentMatchers.eq("PUBLISHED"),
                org.mockito.ArgumentMatchers.isNull(), any());
        verify(mapper, never()).failOutbox(anyLong(), any(), anyString(), any());
    }

    @Test
    void publisherMarksOutboxPublishedOnlyAfterRabbitPublisherAck() {
        when(mapper.lockNextOutbox(any())).thenReturn(Optional.of(outbox));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher.publishOne();

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(org.mockito.ArgumentMatchers.eq("zhixiaohe.task"),
                org.mockito.ArgumentMatchers.eq("agent.image"), message.capture(), any(CorrelationData.class));
        assertThat(new String(message.getValue().getBody(), StandardCharsets.UTF_8))
                .isEqualTo(outbox.payloadJson());
        assertThat(message.getValue().getMessageProperties().getMessageId()).isEqualTo("701");
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat((String) message.getValue().getMessageProperties().getHeader("traceparent"))
                .isEqualTo(outbox.traceparent());
        verify(mapper).finishOutbox(org.mockito.ArgumentMatchers.eq(701L),
                org.mockito.ArgumentMatchers.eq("PUBLISHED"),
                org.mockito.ArgumentMatchers.isNull(), any());
        verify(mapper, never()).failOutbox(anyLong(), any(), anyString(), any());
    }

    @Test
    void publisherContinuesPersistedTraceAndInjectsTheProducerSpanIntoRabbitMessage() {
        SimpleTracer tracer = new SimpleTracer();
        publisher = new TaskOutboxPublisher(mapper, rabbit, new ObservabilitySupport(tracer));
        when(mapper.lockNextOutbox(any())).thenReturn(Optional.of(outbox));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher.publishOne();

        ArgumentCaptor<Message> captured = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(anyString(), anyString(), captured.capture(), any(CorrelationData.class));
        String propagated = captured.getValue().getMessageProperties().getHeader("traceparent");
        var producer = tracer.onlySpan();
        assertThat(producer.getName()).isEqualTo("java.outbox.publish");
        assertThat(producer.getTraceId()).isEqualTo("a".repeat(32));
        assertThat(producer.getParentId()).isEqualTo("b".repeat(16));
        assertThat(propagated).isEqualTo("00-" + producer.getTraceId() + "-"
                + producer.getSpanId() + "-01");
    }

    @Test
    void publisherKeepsOutboxRetryableWhenRabbitSendFails() {
        when(mapper.lockNextOutbox(any())).thenReturn(Optional.of(outbox));
        doThrow(new AmqpException("synthetic unavailable"))
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher.publishOne();

        verify(mapper).failOutbox(org.mockito.ArgumentMatchers.eq(701L), any(),
                org.mockito.ArgumentMatchers.eq("RABBIT_PUBLISH_FAILED"), any());
        verify(mapper, never()).finishOutbox(anyLong(), anyString(), any(), any());
    }
}
