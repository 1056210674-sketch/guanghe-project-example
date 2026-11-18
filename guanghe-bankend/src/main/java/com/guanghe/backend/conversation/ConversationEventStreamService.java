package com.guanghe.backend.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.DisconnectedClientHelper;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationEventStreamService {

    private static final int REPLAY_BATCH_SIZE = 500;
    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationEventStreamService.class);

    private final ConversationMapper mapper;
    private final ConversationService conversationService;
    private final ConversationProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<Long, java.util.Set<Subscriber>> subscribers = new ConcurrentHashMap<>();

    public ConversationEventStreamService(
            ConversationMapper mapper,
            ConversationService conversationService,
            ConversationProperties properties,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.conversationService = conversationService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SseEmitter open(AuthenticatedAccount account, long conversationId, String lastEventId) {
        long userId = CatalogAccess.requireUser(account);
        conversationService.requireConversation(userId, conversationId);
        long afterSequence = parseLastEventId(lastEventId);
        SseEmitter emitter = new SseEmitter(properties.getBrowserSseTimeout().toMillis());
        Subscriber subscriber = new Subscriber(userId, conversationId, afterSequence, emitter);
        subscribers.computeIfAbsent(conversationId, ignored -> ConcurrentHashMap.newKeySet()).add(subscriber);
        Runnable remove = () -> remove(subscriber);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(ignored -> remove.run());

        try {
            synchronized (subscriber) {
                replay(subscriber);
            }
            if (!hasActiveWork(userId, conversationId)) {
                remove(subscriber);
                emitter.complete();
            }
        } catch (IOException exception) {
            remove(subscriber);
            recordSendFailure(exception);
            // The servlet container completes failed writes. Completing a second time would
            // dispatch an ordinary JSON error into an already committed SSE response.
        } catch (IllegalStateException exception) {
            remove(subscriber);
            if (exception.getCause() instanceof JsonProcessingException) {
                emitter.completeWithError(exception);
            } else {
                recordSendFailure(exception);
            }
        } catch (RuntimeException exception) {
            remove(subscriber);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void publish(ConversationEventRecord event) {
        java.util.Set<Subscriber> conversationSubscribers = subscribers.get(event.conversationId());
        if (conversationSubscribers == null) {
            return;
        }
        for (Subscriber subscriber : List.copyOf(conversationSubscribers)) {
            if (subscriber.userId != event.userId()) {
                continue;
            }
            try {
                synchronized (subscriber) {
                    // Transaction commits are ordered by the conversation sequence lock, but
                    // after-commit publishers can run in the opposite order. Never skip the
                    // missing committed events just because a newer notification arrived first.
                    if (event.sequenceNo() > subscriber.lastSequence + 1) {
                        replay(subscriber);
                    } else {
                        subscriber.send(event);
                    }
                }
                if (isTerminalDeliveryEvent(event.eventType())
                        && !hasActiveWork(subscriber.userId, subscriber.conversationId)) {
                    remove(subscriber);
                    subscriber.emitter.complete();
                }
            } catch (IOException | IllegalStateException exception) {
                remove(subscriber);
                if (exception.getCause() instanceof JsonProcessingException) {
                    subscriber.emitter.completeWithError(exception);
                } else {
                    recordSendFailure(exception);
                }
            }
        }
    }

    private void replay(Subscriber subscriber) throws IOException {
        while (true) {
            List<ConversationEventRecord> events = mapper.findEventsAfter(
                    subscriber.userId, subscriber.conversationId, subscriber.lastSequence, REPLAY_BATCH_SIZE
            );
            for (ConversationEventRecord event : events) {
                subscriber.send(event);
            }
            if (events.size() < REPLAY_BATCH_SIZE) {
                return;
            }
        }
    }

    private void recordSendFailure(Exception exception) {
        if (DisconnectedClientHelper.isClientDisconnectedException(exception)
                || alreadyCompletedEmitter(exception)) {
            LOGGER.debug("conversation_sse_subscriber_disconnected");
        } else {
            LOGGER.warn("conversation_sse_write_failed type={}", exception.getClass().getSimpleName());
        }
    }

    static boolean alreadyCompletedEmitter(Exception exception) {
        // Only the framework's terminal-emitter signal is routine. Other IllegalStateException
        // instances can be persistence/programming failures and must remain visible as warnings.
        return exception instanceof IllegalStateException && exception.getCause() == null
                && exception.getMessage() != null
                && exception.getMessage().startsWith("ResponseBodyEmitter has already completed");
    }

    private void remove(Subscriber subscriber) {
        subscribers.computeIfPresent(subscriber.conversationId, (ignored, values) -> {
            values.remove(subscriber);
            return values.isEmpty() ? null : values;
        });
    }

    private boolean hasActiveWork(long userId, long conversationId) {
        return mapper.findActiveRun(userId, conversationId).isPresent()
                || mapper.hasActivePaidTask(conversationId, userId);
    }

    private boolean isTerminalDeliveryEvent(String eventType) {
        return "message.completed".equals(eventType)
                || "task.completed".equals(eventType)
                || "task.failed".equals(eventType);
    }

    private long parseLastEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private ConversationDtos.EventResponse response(ConversationEventRecord event) {
        try {
            JsonNode payload = objectMapper.readTree(event.payloadJson());
            if ("context.resource".equals(event.eventType())) payload = resourceBrowserPayload(event, payload);
            return new ConversationDtos.EventResponse(
                    Long.toString(event.id()), event.sequenceNo(), event.occurredAt(),
                    Long.toString(event.conversationId()),
                    event.messageId() == null ? null : Long.toString(event.messageId()),
                    event.taskId() == null ? null : Long.toString(event.taskId()), payload
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode persisted conversation event", exception);
        }
    }

    private ObjectNode resourceBrowserPayload(ConversationEventRecord event, JsonNode stored) {
        ObjectNode safe = objectMapper.createObjectNode();
        // The durable record contains model evidence and normalized inputs. Browser replay keeps
        // the same event sequence but only emits the small, controlled status/reference projection.
        for (String field : List.of("kind", "success", "errorCode", "purpose", "candidateSetId", "choiceId")) {
            JsonNode value = stored.get(field);
            if (value != null && (value.isTextual() || value.isBoolean())) safe.set(field, value.deepCopy());
        }
        JsonNode result = stored.path("result");
        for (String field : List.of("status", "purpose", "candidateSetId", "observationId", "resourceId",
                "name", "reportId", "spaceId", "productId")) {
            JsonNode value = result.get(field);
            if (value != null && value.isTextual()) safe.set(field, value.deepCopy());
        }
        if (event.agentRunId() != null) safe.put("runId", event.agentRunId().toString());
        return safe;
    }

    private final class Subscriber {
        private final long userId;
        private final long conversationId;
        private final SseEmitter emitter;
        private long lastSequence;

        private Subscriber(long userId, long conversationId, long lastSequence, SseEmitter emitter) {
            this.userId = userId;
            this.conversationId = conversationId;
            this.lastSequence = lastSequence;
            this.emitter = emitter;
        }

        private synchronized void send(ConversationEventRecord event) throws IOException {
            if (event.sequenceNo() <= lastSequence) {
                return;
            }
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequenceNo()))
                    .name(event.eventType())
                    .data(response(event), MediaType.APPLICATION_JSON));
            lastSequence = event.sequenceNo();
        }
    }
}
