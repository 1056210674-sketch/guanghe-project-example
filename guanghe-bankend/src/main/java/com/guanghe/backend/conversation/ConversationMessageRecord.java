package com.guanghe.backend.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConversationMessageRecord(
        long id,
        long conversationId,
        long userId,
        String role,
        String contentType,
        String contentJson,
        Long replyToMessageId,
        UUID agentRunId,
        UUID clientMessageId,
        UUID idempotencyKey,
        String requestFingerprint,
        long sequenceNo,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
