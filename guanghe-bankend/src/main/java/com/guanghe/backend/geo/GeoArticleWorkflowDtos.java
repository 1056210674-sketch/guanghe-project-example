package com.guanghe.backend.geo;

import com.guanghe.backend.task.TaskDtos;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class GeoArticleWorkflowDtos {
    private GeoArticleWorkflowDtos() { }

    public record CreateRequest(
            @Pattern(regexp = "^([1-9][0-9]{0,18}:recommendation:[1-9][0-9]{0,2})$")
            String suggestionId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$")
            String conversationId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$")
            String observationId,
            @Size(max = 800) String direction) {
        public CreateRequest(String suggestionId, String conversationId) {
            this(suggestionId, conversationId, null, null);
        }
    }

    public record Response(String workflowId, String reportId, String suggestionId,
                           String conversationId, String articleId, String draftId,
                           boolean reused, TaskDtos.DraftResponse draft) { }
}
