package com.guanghe.backend.integration.internal.client;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PythonChatRunRequest(
        UUID runId,
        String conversationId,
        String userContextRef,
        Message message,
        TrustedState trustedState,
        Budget budget
) {
    public record Message(
            String messageId,
            String content,
            String replyToMessageId,
            List<String> attachmentAssetIds
    ) {
        public Message {
            attachmentAssetIds = List.copyOf(attachmentAssetIds);
        }
    }

    public record TrustedState(
            String selectedSpaceId,
            String selectedProductId,
            String pendingInteractionId,
            String draftId,
            String taskId
    ) {
    }

    public record Budget(
            int maxModelCalls,
            int maxToolCalls,
            int maxSteps,
            int maxContextLoads,
            int maxProviderCalls,
            int maxInputTokens,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            OffsetDateTime deadlineAt
    ) {
    }
}
