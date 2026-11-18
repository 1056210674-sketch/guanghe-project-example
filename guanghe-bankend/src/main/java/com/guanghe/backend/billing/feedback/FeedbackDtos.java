package com.guanghe.backend.billing.feedback;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Browser-facing payloads for user feedback. */
public final class FeedbackDtos {

    private FeedbackDtos() {
    }

    /**
     * One piece of feedback.
     *
     * <p>{@code reply} is null until an operator has answered. The client should show the feedback
     * as unanswered rather than as "处理中" until that field exists, because there is no state in
     * which an operator has seen it but left no answer.
     */
    public record FeedbackResponse(String id, String category, String content, String contact,
                                   String status, String reply, OffsetDateTime handledAt,
                                   OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** A new submission. */
    public record CreateFeedbackRequest(
            @NotBlank @Pattern(regexp = "^(PRODUCT|BILLING|BUG|CONTENT|OTHER)$", message = "反馈类型非法")
            String category,
            @NotBlank @Size(max = 2000) String content,
            @Size(max = 128) String contact) {
    }

    /**
     * An operator response.
     *
     * <p>A reply is mandatory whenever the status moves off SUBMITTED. A status change with no
     * explanation reaches the user as a notification that says nothing, which is worse than the
     * feedback simply staying open. Because the reply is always user-facing text, the operator's
     * answer is always shown to the author — there is no private-note channel that could leak into
     * the visible field by mistake.
     */
    public record HandleFeedbackRequest(
            @NotBlank @Pattern(regexp = "^(ACKNOWLEDGED|RESOLVED|CLOSED)$", message = "反馈状态非法")
            String status,
            @NotBlank @Size(max = 2000) String reply) {
    }
}
