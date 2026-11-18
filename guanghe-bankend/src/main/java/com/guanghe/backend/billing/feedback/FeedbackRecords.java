package com.guanghe.backend.billing.feedback;

import java.time.OffsetDateTime;

/** Row shapes for user feedback. */
public final class FeedbackRecords {

    private FeedbackRecords() {
    }

    public record UserFeedback(long id, long userId, String category, String content, String contact,
                               String status, String reply, Long handledBy,
                               OffsetDateTime handledAt, String idempotencyKey, String requestHash,
                               int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }
}
