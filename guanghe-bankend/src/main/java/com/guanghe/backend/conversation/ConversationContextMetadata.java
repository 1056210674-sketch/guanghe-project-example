package com.guanghe.backend.conversation;

public record ConversationContextMetadata(
        long id,
        long userId,
        String summaryJson,
        int summaryVersion,
        long lastMessageSequence
) {
}
