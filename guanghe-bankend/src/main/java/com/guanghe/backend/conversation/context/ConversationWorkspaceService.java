package com.guanghe.backend.conversation.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.ConversationContextMetadata;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.ConversationMessageRecord;
import com.guanghe.backend.conversation.ConversationProperties;
import com.guanghe.backend.conversation.ConversationRecord;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationWorkspaceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationWorkspaceService.class);
    private static final int CACHE_SCHEMA_VERSION = 1;
    private static final String SELECTION_STRATEGY = "TOKEN_BUDGET_REPLY_CHAIN_THEN_RECENCY";

    private final ConversationMapper mapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final TaskWorkspaceStateSource taskStateSource;
    private final ConversationProperties properties;

    public ConversationWorkspaceService(
            ConversationMapper mapper,
            ObjectMapper objectMapper,
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<TaskWorkspaceStateSource> taskStateProvider,
            ConversationProperties properties
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.redis = redisProvider.getIfAvailable();
        this.taskStateSource = taskStateProvider.getIfAvailable();
        this.properties = properties;
    }

    public WorkspaceContext assemble(
            long userId,
            long conversationId,
            ConversationMessageRecord currentMessage,
            Long selectedSpaceId,
            Long selectedProductId,
            List<String> competitorIds,
            int fixedContextTokens
    ) {
        ConversationContextMetadata metadata = mapper.findContextMetadata(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (metadata.lastMessageSequence() != currentMessage.sequenceNo()) {
            throw new IllegalStateException("Conversation sequence changed while creating Run context");
        }

        InternalRunContextDtos.ConversationSummary summary = summary(metadata);
        long summaryThrough = summaryThrough(metadata);
        CacheRead cache = readCache(userId, conversationId, currentMessage, metadata);
        List<InternalRunContextDtos.MemoryMessage> allMessages;
        String source;
        if (cache != null) {
            allMessages = new ArrayList<>(cache.value().messages());
            allMessages.add(toMemoryMessage(currentMessage));
            source = "REDIS";
        } else {
            allMessages = mapper.findContextMessages(userId, conversationId, currentMessage.sequenceNo())
                    .stream().map(this::toMemoryMessage).toList();
            source = "POSTGRESQL";
        }

        InternalRunContextDtos.PendingInteraction pending = pendingInteraction(
                userId, conversationId, allMessages
        );
        TaskWorkspaceStateSource.State taskState = taskState(userId, conversationId, selectedSpaceId, selectedProductId);
        InternalRunContextDtos.TaskWorkspace taskWorkspace = taskWorkspace(
                currentMessage, selectedSpaceId, selectedProductId, competitorIds, pending, taskState
        );
        List<String> loadedToolGroups = List.of("TASK_PREPARATION");
        int workspaceTokens = estimateTokens(taskWorkspace) + estimateTokens(summary) + fixedContextTokens;
        int availableHistoryTokens = Math.max(1, properties.getTargetInputTokens()
                - properties.getReservedOutputTokens()
                - properties.getReservedToolTokens()
                - properties.getReservedSafetyTokens()
                - workspaceTokens);
        Selection selection = select(
                allMessages, currentMessage.id(), availableHistoryTokens, properties.getWorkspaceMaxBytes()
        );
        InternalRunContextDtos.ConversationMemory memory = new InternalRunContextDtos.ConversationMemory(
                selection.messages(), summary, currentMessage.sequenceNo(), summaryThrough, source
        );
        InternalRunContextDtos.ContextBudget budget = new InternalRunContextDtos.ContextBudget(
                properties.getTargetInputTokens(), properties.getReservedOutputTokens(),
                properties.getReservedToolTokens(), properties.getReservedSafetyTokens(),
                availableHistoryTokens, selection.estimatedTokens(), selection.truncated(), SELECTION_STRATEGY
        );

        writeCacheAfterCommit(new CachedWorkspace(
                CACHE_SCHEMA_VERSION, userId, conversationId, metadata.summaryVersion(),
                currentMessage.sequenceNo(), cacheMessages(selection.messages(), currentMessage.id()),
                summary, summaryThrough, taskWorkspace
        ));
        return new WorkspaceContext(memory, taskWorkspace, loadedToolGroups, budget);
    }

    public void invalidateAfterCommit(long userId, long conversationId) {
        afterCommit(() -> deleteCache(userId, conversationId));
    }

    public void refreshAfterCommit(long userId, long conversationId) {
        afterCommit(() -> {
            try {
                refresh(userId, conversationId);
            } catch (RuntimeException exception) {
                LOGGER.warn("conversation_workspace_refresh_failed conversationId={}", conversationId);
            }
        });
    }

    private void refresh(long userId, long conversationId) {
        ConversationContextMetadata metadata = mapper.findContextMetadata(conversationId, userId).orElse(null);
        ConversationRecord conversation = mapper.findConversation(conversationId, userId).orElse(null);
        if (metadata == null || conversation == null || metadata.lastMessageSequence() < 1) {
            deleteCache(userId, conversationId);
            return;
        }
        List<InternalRunContextDtos.MemoryMessage> messages = mapper.findContextMessages(
                userId, conversationId, metadata.lastMessageSequence()
        ).stream().map(this::toMemoryMessage).toList();
        InternalRunContextDtos.MemoryMessage lastUser = null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("USER".equals(messages.get(index).role())) {
                lastUser = messages.get(index);
                break;
            }
        }
        if (lastUser == null) {
            deleteCache(userId, conversationId);
            return;
        }
        ConversationMessageRecord current = new ConversationMessageRecord(
                Long.parseLong(lastUser.messageId()), conversationId, userId, lastUser.role(), "TEXT",
                write(lastUser.content()), lastUser.replyToMessageId() == null ? null
                : Long.parseLong(lastUser.replyToMessageId()), null, null, null, null,
                lastUser.sequence(), "COMPLETED", lastUser.createdAt(), lastUser.createdAt()
        );
        TaskWorkspaceStateSource.State state = taskState(userId, conversationId,
                conversation.selectedSpaceId(), conversation.selectedProductId());
        InternalRunContextDtos.TaskWorkspace workspace = taskWorkspace(
                current, conversation.selectedSpaceId(), conversation.selectedProductId(), List.of(),
                pendingInteraction(userId, conversationId, messages), state
        );
        writeCache(new CachedWorkspace(
                CACHE_SCHEMA_VERSION, userId, conversationId, metadata.summaryVersion(),
                metadata.lastMessageSequence(), cacheMessages(messages, current.id()), summary(metadata),
                summaryThrough(metadata), workspace
        ));
    }

    private CacheRead readCache(
            long userId,
            long conversationId,
            ConversationMessageRecord currentMessage,
            ConversationContextMetadata metadata
    ) {
        if (redis == null) {
            return null;
        }
        try {
            String value = redis.opsForValue().get(key(userId, conversationId));
            if (value == null) {
                return null;
            }
            CachedWorkspace cached = objectMapper.readValue(value, CachedWorkspace.class);
            boolean replyAvailable = currentMessage.replyToMessageId() == null
                    || cached.messages().stream().anyMatch(message ->
                    Long.toString(currentMessage.replyToMessageId()).equals(message.messageId()));
            if (cached.schemaVersion() != CACHE_SCHEMA_VERSION
                    || cached.userId() != userId
                    || cached.conversationId() != conversationId
                    || cached.summaryVersion() != metadata.summaryVersion()
                    || cached.lastMessageSequence() != currentMessage.sequenceNo() - 1
                    || !validMessageOrder(cached)
                    || !replyAvailable) {
                return null;
            }
            return new CacheRead(cached);
        } catch (RuntimeException | JsonProcessingException exception) {
            LOGGER.warn("conversation_workspace_cache_read_failed conversationId={}", conversationId);
            return null;
        }
    }

    private boolean validMessageOrder(CachedWorkspace cached) {
        long previous = 0;
        for (InternalRunContextDtos.MemoryMessage message : cached.messages()) {
            if (message.sequence() <= previous || message.sequence() > cached.lastMessageSequence()) {
                return false;
            }
            previous = message.sequence();
        }
        return cached.messages().isEmpty()
                || cached.messages().getLast().sequence() == cached.lastMessageSequence();
    }

    private void writeCacheAfterCommit(CachedWorkspace value) {
        if (redis == null) {
            return;
        }
        afterCommit(() -> writeCache(value));
    }

    private void writeCache(CachedWorkspace value) {
        if (redis == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length <= properties.getWorkspaceMaxBytes()) {
                redis.opsForValue().set(
                        key(value.userId(), value.conversationId()), json, properties.getWorkspaceTtl()
                );
            }
        } catch (RuntimeException | JsonProcessingException exception) {
            LOGGER.warn("conversation_workspace_cache_write_failed conversationId={}", value.conversationId());
        }
    }

    private void deleteCache(long userId, long conversationId) {
        if (redis == null) {
            return;
        }
        try {
            redis.delete(key(userId, conversationId));
        } catch (RuntimeException exception) {
            LOGGER.warn("conversation_workspace_cache_invalidation_failed conversationId={}", conversationId);
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private List<InternalRunContextDtos.MemoryMessage> cacheMessages(
            List<InternalRunContextDtos.MemoryMessage> messages,
            long currentMessageId
    ) {
        List<InternalRunContextDtos.MemoryMessage> result = new ArrayList<>(messages);
        while (result.size() > 1 && estimateBytes(result) > properties.getWorkspaceMaxBytes()) {
            String protectedCurrent = Long.toString(currentMessageId);
            String protectedLatest = result.getLast().messageId();
            int removable = -1;
            for (int index = 0; index < result.size(); index++) {
                String messageId = result.get(index).messageId();
                if (!messageId.equals(protectedCurrent) && !messageId.equals(protectedLatest)) {
                    removable = index;
                    break;
                }
            }
            if (removable < 0) {
                break;
            }
            result.remove(removable);
        }
        return List.copyOf(result);
    }

    private Selection select(
            List<InternalRunContextDtos.MemoryMessage> messages,
            long currentMessageId,
            int budget,
            int maxBytes
    ) {
        Map<String, InternalRunContextDtos.MemoryMessage> byId = new HashMap<>();
        messages.forEach(message -> byId.put(message.messageId(), message));
        String currentId = Long.toString(currentMessageId);
        LinkedHashSet<String> priorityIds = new LinkedHashSet<>();
        String cursor = currentId;
        while (cursor != null && priorityIds.add(cursor)) {
            InternalRunContextDtos.MemoryMessage message = byId.get(cursor);
            cursor = message == null ? null : message.replyToMessageId();
        }

        List<InternalRunContextDtos.MemoryMessage> selected = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();
        int used = 0;
        int usedBytes = 0;
        for (String id : priorityIds) {
            InternalRunContextDtos.MemoryMessage message = byId.get(id);
            if (message == null) {
                continue;
            }
            int cost = estimateTokens(message);
            int bytes = estimateBytes(message);
            if (id.equals(currentId) || (used + cost <= budget && usedBytes + bytes <= maxBytes)) {
                selected.add(message);
                selectedIds.add(id);
                used += cost;
                usedBytes += bytes;
            }
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            InternalRunContextDtos.MemoryMessage message = messages.get(index);
            if (selectedIds.contains(message.messageId())) {
                continue;
            }
            int cost = estimateTokens(message);
            int bytes = estimateBytes(message);
            if (used + cost <= budget && usedBytes + bytes <= maxBytes) {
                selected.add(message);
                selectedIds.add(message.messageId());
                used += cost;
                usedBytes += bytes;
            }
        }
        selected.sort(Comparator.comparingLong(InternalRunContextDtos.MemoryMessage::sequence));
        return new Selection(List.copyOf(selected), used, selected.size() != messages.size());
    }

    private InternalRunContextDtos.TaskWorkspace taskWorkspace(
            ConversationMessageRecord currentMessage,
            Long selectedSpaceId,
            Long selectedProductId,
            List<String> competitorIds,
            InternalRunContextDtos.PendingInteraction pending,
            TaskWorkspaceStateSource.State state
    ) {
        JsonNode content = readTree(currentMessage.contentJson());
        String goal = content.path("text").isTextual() ? content.path("text").textValue() : "";
        String intent = state.intent() != null ? state.intent()
                : pending == null ? "GENERAL" : pending.intent();
        List<String> unresolved = pending == null ? List.of() : List.of(pending.question());
        return new InternalRunContextDtos.TaskWorkspace(
                goal, intent,
                new InternalRunContextDtos.ActiveScope(id(selectedSpaceId), id(selectedProductId), competitorIds),
                state.confirmedConstraints(), unresolved, pending,
                state.activeDraftId(), state.activeTaskId(), List.of(), state.runningTasks(), 1
        );
    }

    private TaskWorkspaceStateSource.State taskState(long userId, long conversationId, Long spaceId, Long productId) {
        if (taskStateSource == null) {
            return new TaskWorkspaceStateSource.State(
                    null, null, null, objectMapper.createObjectNode(), List.of()
            );
        }
        return taskStateSource.read(userId, conversationId, spaceId, productId);
    }

    private InternalRunContextDtos.PendingInteraction pendingInteraction(
            long userId,
            long conversationId,
            List<InternalRunContextDtos.MemoryMessage> messages
    ) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            InternalRunContextDtos.MemoryMessage message = messages.get(index);
            if (!"ASSISTANT".equals(message.role())) {
                continue;
            }
            JsonNode interaction = message.content().path("interaction");
            if (!interaction.isObject()) {
                // Ordinary assistant replies (including casual chat) do not resolve an earlier
                // clarification. Resolution needs an explicit reply or a durable task draft.
                continue;
            }
            if (mapper.hasInteractionConsumptionEvidence(
                    userId, conversationId, message.messageId()
            )) {
                // The newest interaction is a barrier. Once it has been consumed, do not revive
                // any older question that happened to remain in conversation history.
                return null;
            }
            return new InternalRunContextDtos.PendingInteraction(
                    message.messageId(), text(interaction, "interactionType"), text(interaction, "intent"),
                    text(interaction, "question"), strings(interaction.path("missingFields")),
                    strings(interaction.path("choices"))
            );
        }
        return null;
    }

    private InternalRunContextDtos.MemoryMessage toMemoryMessage(ConversationMessageRecord message) {
        return new InternalRunContextDtos.MemoryMessage(
                Long.toString(message.id()), message.sequenceNo(), message.role(), readTree(message.contentJson()),
                message.createdAt(), id(message.replyToMessageId())
        );
    }

    private InternalRunContextDtos.ConversationSummary summary(ConversationContextMetadata metadata) {
        JsonNode value = readTree(metadata.summaryJson());
        JsonNode text = value.get("text");
        return text != null && text.isTextual() && !text.textValue().isBlank()
                ? new InternalRunContextDtos.ConversationSummary(text.textValue(), metadata.summaryVersion())
                : null;
    }

    private long summaryThrough(ConversationContextMetadata metadata) {
        JsonNode value = readTree(metadata.summaryJson());
        JsonNode through = value.get("throughSequence");
        return through != null && through.canConvertToLong()
                ? Math.max(0, Math.min(metadata.lastMessageSequence(), through.longValue())) : 0;
    }

    private JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode persisted conversation workspace", exception);
        }
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode persisted conversation workspace", exception);
        }
    }

    private List<String> strings(JsonNode value) {
        if (!value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (item.isTextual()) {
                result.add(item.textValue());
            }
        });
        return List.copyOf(result);
    }

    private String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).textValue() : "";
    }

    private int estimateTokens(Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            String text = objectMapper.writeValueAsString(value);
            int codePoints = text.codePointCount(0, text.length());
            return Math.max(codePoints, (bytes.length + 3) / 4);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to estimate conversation context", exception);
        }
    }

    private int estimateBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to size conversation workspace", exception);
        }
    }

    private String key(long userId, long conversationId) {
        return properties.getWorkspaceKeyPrefix() + userId + ":" + conversationId;
    }

    private String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    public record WorkspaceContext(
            InternalRunContextDtos.ConversationMemory conversationMemory,
            InternalRunContextDtos.TaskWorkspace taskWorkspace,
            List<String> loadedToolGroups,
            InternalRunContextDtos.ContextBudget contextBudget
    ) {
    }

    private record CacheRead(CachedWorkspace value) {
    }

    private record CachedWorkspace(
            int schemaVersion,
            long userId,
            long conversationId,
            int summaryVersion,
            long lastMessageSequence,
            List<InternalRunContextDtos.MemoryMessage> messages,
            InternalRunContextDtos.ConversationSummary summary,
            long summaryThroughSequence,
            InternalRunContextDtos.TaskWorkspace taskWorkspace
    ) {
        private CachedWorkspace {
            messages = List.copyOf(messages);
        }
    }

    private record Selection(
            List<InternalRunContextDtos.MemoryMessage> messages,
            int estimatedTokens,
            boolean truncated
    ) {
    }
}
