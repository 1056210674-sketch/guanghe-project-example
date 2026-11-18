package com.guanghe.backend.conversation;

import java.time.OffsetDateTime;

public record ConversationRecord(
        long id,
        long userId,
        String title,
        Long selectedSpaceId,
        Long selectedProductId,
        int version,
        OffsetDateTime lastMessageAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
