package com.guanghe.backend.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.AutomapConstructor;

public record ConversationEventRecord(
        long id,
        long conversationId,
        long userId,
        UUID agentRunId,
        Long messageId,
        Long taskId,
        UUID sourceEventId,
        String eventType,
        long sequenceNo,
        String payloadJson,
        OffsetDateTime occurredAt,
        OffsetDateTime createdAt
) {
    @AutomapConstructor
    public ConversationEventRecord {
    }

    public ConversationEventRecord(long id, long conversationId, long userId, UUID agentRunId,
                                   Long messageId, UUID sourceEventId, String eventType, long sequenceNo,
                                   String payloadJson, OffsetDateTime occurredAt, OffsetDateTime createdAt) {
        this(id, conversationId, userId, agentRunId, messageId, null, sourceEventId, eventType,
                sequenceNo, payloadJson, occurredAt, createdAt);
    }
}
