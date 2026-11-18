package com.guanghe.backend.conversation.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface TaskWorkspaceStateSource {

    State read(long userId, long conversationId);

    /** Current-turn scope, not the scope of the most recent historical draft. */
    State read(long userId, long conversationId, Long spaceId, Long productId);

    /**
     * Returns the draft id only when exactly one unexpired draft is waiting for confirmation.
     * Unlike the latest scoped candidate in {@link State#activeDraftId()}, this requires
     * one unambiguous candidate across the conversation for browser confirmation recovery.
     */
    String activeConfirmationDraftId(long userId, long conversationId);

    record State(
            String activeDraftId,
            String activeTaskId,
            String intent,
            JsonNode confirmedConstraints,
            List<InternalRunContextDtos.RunningTask> runningTasks
    ) {
        public State {
            runningTasks = List.copyOf(runningTasks);
        }
    }
}
