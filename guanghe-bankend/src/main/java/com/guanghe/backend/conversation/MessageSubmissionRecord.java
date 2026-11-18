package com.guanghe.backend.conversation;

import java.util.UUID;

public record MessageSubmissionRecord(
        long messageId,
        UUID runId,
        UUID clientMessageId,
        UUID idempotencyKey,
        String requestFingerprint,
        String runStatus
) {
}
