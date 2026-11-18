package com.guanghe.backend.conversation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.guanghe.backend.agentplatform.AgentPlatformDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ConversationDtos {

    private ConversationDtos() {
    }

    public record CreateRequest(
            @Size(max = 200) String title,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String installationId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String projectId
    ) {
        public CreateRequest(String title) { this(title, null, null); }
        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown conversation request field");
        }
    }

    public record UpdateRequest(
            @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer version,
            @NotBlank @Size(max = 200) String title
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown conversation request field");
        }
    }

    public record AttachmentRequest(
            @NotBlank @Pattern(regexp = "^[0-9]{1,19}$") String assetId
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown attachment request field");
        }
    }

    public enum ContextSelectionMode {
        /** Keep the conversation's currently persisted business space and product. */
        INHERIT,
        /** Replace the persisted context with the supplied business space and optional product. */
        REPLACE,
        /** Clear the persisted business space and product. */
        CLEAR
    }

    public record ResourceSelection(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String candidateSetId,
            @NotBlank @Size(max = 128) String choiceId
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown resource selection field");
        }
    }

    public record SendMessageRequest(
            @NotNull UUID clientMessageId,
            @NotBlank @Size(max = 16000) String content,
            @Pattern(regexp = "^[0-9]{1,19}$") String replyToMessageId,
            String draftId,
            String taskId,
            @Pattern(regexp = "^[0-9]{1,19}$") String selectedSpaceId,
            @Pattern(regexp = "^[0-9]{1,19}$") String selectedProductId,
            @Size(max = 8) List<@Valid AttachmentRequest> attachments,
            ContextSelectionMode contextSelectionMode,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String geoReportId,
            @Valid ResourceSelection resourceSelection,
            @Valid com.guanghe.backend.agentplatform.MonitorExecutionDtos.MonitorExecutionIntent executionIntent,
            @Pattern(regexp = "^ARTICLE_FULL_V1$") String articlePreparationMode
    ) {
        public SendMessageRequest(UUID clientMessageId,String content,String replyToMessageId,
                String draftId,String taskId,String selectedSpaceId,String selectedProductId,
                List<AttachmentRequest> attachments,ContextSelectionMode contextSelectionMode,
                String geoReportId,ResourceSelection resourceSelection,
                com.guanghe.backend.agentplatform.MonitorExecutionDtos.MonitorExecutionIntent executionIntent) {
            this(clientMessageId,content,replyToMessageId,draftId,taskId,selectedSpaceId,selectedProductId,
                    attachments,contextSelectionMode,geoReportId,resourceSelection,executionIntent,null);
        }
        public SendMessageRequest(UUID clientMessageId,String content,String replyToMessageId,
                String draftId,String taskId,String selectedSpaceId,String selectedProductId,
                List<AttachmentRequest> attachments,ContextSelectionMode contextSelectionMode,
                String geoReportId,ResourceSelection resourceSelection) {
            this(clientMessageId,content,replyToMessageId,draftId,taskId,selectedSpaceId,selectedProductId,
                    attachments,contextSelectionMode,geoReportId,resourceSelection,null);
        }
        public SendMessageRequest(UUID clientMessageId, String content, String replyToMessageId,
                String draftId, String taskId, String selectedSpaceId, String selectedProductId,
                List<AttachmentRequest> attachments, ContextSelectionMode contextSelectionMode,
                String geoReportId) {
            this(clientMessageId, content, replyToMessageId, draftId, taskId, selectedSpaceId,
                    selectedProductId, attachments, contextSelectionMode, geoReportId, null);
        }
        public SendMessageRequest(UUID clientMessageId, String content, String replyToMessageId,
                String draftId, String taskId, String selectedSpaceId, String selectedProductId,
                List<AttachmentRequest> attachments, ContextSelectionMode contextSelectionMode) {
            this(clientMessageId, content, replyToMessageId, draftId, taskId, selectedSpaceId,
                    selectedProductId, attachments, contextSelectionMode, null);
        }
        public SendMessageRequest(
                UUID clientMessageId,
                String content,
                String replyToMessageId,
                String draftId,
                String taskId,
                String selectedSpaceId,
                String selectedProductId,
                List<AttachmentRequest> attachments
        ) {
            this(clientMessageId, content, replyToMessageId, draftId, taskId,
                    selectedSpaceId, selectedProductId, attachments, null, null);
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown message request field");
        }
    }

    public record Response(
            String id,
            String title,
            String selectedSpaceId,
            String selectedProductId,
            String activeConfirmationDraftId,
            int version,
            OffsetDateTime lastMessageAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) AgentPlatformDtos.AgentIdentity agentIdentity
    ) {
        public Response(String id, String title, String selectedSpaceId, String selectedProductId,
                        String activeConfirmationDraftId, int version, OffsetDateTime lastMessageAt,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this(id, title, selectedSpaceId, selectedProductId, activeConfirmationDraftId, version,
                    lastMessageAt, createdAt, updatedAt, null);
        }
        public Response(String id, String title, String selectedSpaceId, String selectedProductId,
                        int version, OffsetDateTime lastMessageAt, OffsetDateTime createdAt,
                        OffsetDateTime updatedAt) {
            this(id, title, selectedSpaceId, selectedProductId, null, version,
                    lastMessageAt, createdAt, updatedAt);
        }
    }

    public record MessageResponse(
            String id,
            String conversationId,
            String role,
            String contentType,
            JsonNode content,
            String replyToMessageId,
            String runId,
            List<String> attachmentAssetIds,
            long sequence,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public MessageResponse(String id, String conversationId, String role, String contentType,
                               JsonNode content, String replyToMessageId, String runId, long sequence,
                               String status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this(id, conversationId, role, contentType, content, replyToMessageId, runId,
                    List.of(), sequence, status, createdAt, updatedAt);
        }

        public MessageResponse {
            attachmentAssetIds = attachmentAssetIds == null ? List.of() : List.copyOf(attachmentAssetIds);
        }
    }

    public record MessageAccepted(String messageId, String runId, String status) {
    }

    public record StopAccepted(String runId, boolean accepted, String status) {
    }

    public record StopRequest(@NotNull UUID expectedRunId) {
        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown stop request field");
        }
    }

    public record Snapshot(
            List<MessageResponse> messages,
            String nextBeforeSequence,
            long highWatermark,
            MessageAccepted activeRun
    ) {
        public Snapshot {
            messages = List.copyOf(messages);
        }
    }

    public record CursorPage<T>(List<T> items, String nextCursor) {
        public CursorPage {
            items = List.copyOf(items);
        }
    }

    public record EventResponse(
            String eventId,
            long sequence,
            OffsetDateTime occurredAt,
            String conversationId,
            String messageId,
            String taskId,
            JsonNode payload
    ) {
    }
}
