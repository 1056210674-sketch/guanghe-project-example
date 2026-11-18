package com.guanghe.backend.conversation.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.ConversationMessageRecord;
import com.guanghe.backend.knowledge.AgentMemoryService;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** Bounded, read-only conversation evidence. A candidate is never paid-task authorization. */
@Component
@ConditionalOnExpression("${zhixiaohe.paid-task.enabled:false} and ${zhixiaohe.agent-platform.enabled:true}")
public class CreativeContinuationEvidence {
    private static final int MAX_MESSAGES = 6;
    private static final int MAX_CHARACTERS = 16_000;
    private final ConversationMapper conversations;
    private final ObjectMapper json;
    private AgentMemoryService memory;
    private AssetMapper assets;

    public CreativeContinuationEvidence(ConversationMapper conversations, ObjectMapper json) {
        this.conversations = conversations;
        this.json = json;
    }

    @Autowired(required = false)
    void setMemory(AgentMemoryService value) { memory = value; }

    @Autowired
    void setAssets(AssetMapper value) { assets = value; }

    public Optional<ObjectNode> candidate(AgentRunRecord run, ConversationMessageRecord current, Long project) {
        if (run == null || current == null || current.id() != run.requestMessageId()
                || current.userId() != run.userId() || current.conversationId() != run.conversationId()
                || !"USER".equals(current.role()) || !"COMPLETED".equals(current.status())
                || current.createdAt() == null || !run.id().equals(current.agentRunId())
                || !Objects.equals(project, run.selectedSpaceId())) {
            return Optional.empty();
        }
        var conversation = conversations.findConversation(run.conversationId(), run.userId()).orElse(null);
        if (conversation == null || !Objects.equals(project, conversation.selectedSpaceId())) return Optional.empty();
        String currentText = text(current);
        if (currentText == null || currentText.isBlank() || currentText.length() > MAX_CHARACTERS) return Optional.empty();
        List<String> currentAttachments = currentAttachments(run, current);
        if (currentAttachments == null) return Optional.empty();
        boolean hasCurrentAttachments = !currentAttachments.isEmpty();
        OffsetDateTime fence = memoryFence(run, project);
        var rows = conversations.findMessagesBefore(run.userId(), run.conversationId(), current.sequenceNo(), MAX_MESSAGES + 1);
        if (rows == null) rows = List.of();
        if (rows.isEmpty() && !hasCurrentAttachments) return Optional.empty();
        var selected = new java.util.ArrayList<ConversationMessageRecord>();
        var seenIds = new HashSet<Long>();
        var seenSequences = new HashSet<Long>();
        boolean incomplete = false;
        int remaining = MAX_CHARACTERS;
        var history = json.createArrayNode();
        for (var previous : rows.stream().sorted(Comparator.comparingLong(ConversationMessageRecord::sequenceNo).reversed()).toList()) {
            if (selected.size() >= MAX_MESSAGES) break;
            if (previous.userId() != run.userId() || previous.conversationId() != run.conversationId()
                    || previous.sequenceNo() >= current.sequenceNo() || previous.id() == current.id()
                    || previous.createdAt() == null || previous.createdAt().isAfter(current.createdAt())
                    || !Set.of("USER", "ASSISTANT").contains(previous.role())
                    || !"COMPLETED".equals(previous.status())
                    || fence != null && previous.createdAt().isBefore(fence)
                    || !seenIds.add(previous.id()) || !seenSequences.add(previous.sequenceNo())) {
                incomplete = true;
                continue;
            }
            String value = text(previous);
            if (value == null || value.isBlank()) { incomplete = true; continue; }
            // Never drop a trailing negation or condition by clipping a raw message.
            if (value.length() > remaining) { incomplete = true; break; }
            ObjectNode item = json.createObjectNode().put("messageId", Long.toString(previous.id()))
                    .put("role", previous.role()).put("text", value);
            history.add(item);
            selected.add(previous);
            remaining -= value.length();
        }
        if (!hasCurrentAttachments && (history.isEmpty()
                || selected.stream().noneMatch(message -> "USER".equals(message.role())))) return Optional.empty();
        // The query is newest first; the reviewer receives the actual dialogue in chronological order.
        var chronological = json.createArrayNode();
        for (int i = history.size() - 1; i >= 0; i--) chronological.add(history.get(i));
        ObjectNode candidate = json.createObjectNode().put("authorized", false).put("reviewRequired", true)
                .put("currentMessageId", Long.toString(current.id())).put("conversationId", Long.toString(run.conversationId()));
        if (project == null) candidate.putNull("projectId"); else candidate.put("projectId", Long.toString(project));
        candidate.set("history", chronological);
        candidate.put("historyIncomplete", incomplete);
        candidate.set("currentAttachmentAssetIds", json.valueToTree(currentAttachments));
        candidate.putNull("sourceImageAssetId");
        // A short edit can inherit only one exact, current-scope result. Never choose a model ID,
        // skip an ambiguous latest result, or reach outside the complete bounded dialogue.
        if (!incomplete && assets != null && selected.stream().anyMatch(message -> "USER".equals(message.role()))) {
            var images = assets.findContinuationImageResults(run.userId(), run.conversationId(), project,
                    current.id(), run.createdAt());
            if (images != null && images.size() == 1 && selected.stream().anyMatch(message ->
                    "USER".equals(message.role()) && message.id() == images.getFirst().sourceMessageId())) {
                candidate.put("sourceImageAssetId", Long.toString(images.getFirst().assetId()));
            }
        }
        ObjectNode binding = candidate.deepCopy();
        binding.put("runId", run.id().toString()).put("userId", Long.toString(run.userId())).put("currentText", currentText);
        if (fence == null) binding.putNull("historyCutoffAt"); else binding.put("historyCutoffAt", fence.toString());
        candidate.put("historyFingerprint", fingerprint(binding));
        return Optional.of(candidate);
    }

    /** Rebuild, rather than trusting model-selected history IDs or a previously read candidate. */
    public Long requireReview(AgentRunRecord run, ConversationMessageRecord current, Long project,
            String taskType, RunActionDtos.CreativeContinuationReview review) {
        var fresh = candidate(run, current, project).orElse(null);
        boolean currentAttachments = review != null && "CURRENT_ATTACHMENTS".equals(review.sourceMode());
        if (review == null || fresh == null
                || currentAttachments && fresh.path("currentAttachmentAssetIds").isEmpty()
                || !currentAttachments && (fresh.path("historyIncomplete").asBoolean()
                    || !fresh.path("history").findValuesAsText("role").contains("USER"))
                || !"CREATE_NEW".equals(review.decision())
                || !(currentAttachments || "TEXT_ONLY".equals(review.sourceMode()) || "PREVIOUS_IMAGE_RESULT".equals(review.sourceMode()))
                || !taskType.equals(review.taskType()) || !Set.of("IMAGE", "VIDEO").contains(taskType)
                || !fresh.path("currentMessageId").asText().equals(review.currentMessageId())
                || !fresh.path("historyFingerprint").asText().equals(review.historyFingerprint())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "请明确当前图片或视频的创作要求。", Map.of("reasonCode", "CREATIVE_CONTINUATION_REVIEW_REQUIRED"));
        }
        boolean previousImage="PREVIOUS_IMAGE_RESULT".equals(review.sourceMode());
        if ((previousImage && review.sourceImageAssetId() == null)
                || (!previousImage && review.sourceImageAssetId() != null)
                || (review.sourceImageAssetId() != null
                    && (!review.sourceImageAssetId().equals(fresh.path("sourceImageAssetId").asText())
                        || !review.sourceImageAssetId().matches("^[1-9][0-9]{0,18}$")))) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "请明确要继续修改哪一张图片。", Map.of("reasonCode", "CREATIVE_CONTINUATION_SOURCE_REQUIRED"));
        }
        return review.sourceImageAssetId() == null ? null : Long.valueOf(review.sourceImageAssetId());
    }

    /** Current attachments remain Java-owned provenance, never model-selected IDs. */
    private List<String> currentAttachments(AgentRunRecord run, ConversationMessageRecord current) {
        try {
            JsonNode frozen = json.readTree(run.attachmentAssetIdsJson());
            if (frozen == null || !frozen.isArray() || frozen.size() > 8) return null;
            var ids = new java.util.ArrayList<Long>();
            var seen = new HashSet<Long>();
            for (JsonNode value : frozen) {
                if (!value.isTextual() || !value.textValue().matches("^[1-9][0-9]{0,18}$")) return null;
                long id = Long.parseLong(value.textValue());
                if (!seen.add(id)) return null;
                ids.add(id);
            }
            if (!ids.equals(conversations.findMessageAssetIds(current.id(), run.userId(), run.conversationId()))) return null;
            return ids.stream().map(String::valueOf).toList();
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) { return null; }
    }

    private OffsetDateTime memoryFence(AgentRunRecord run, Long project) {
        OffsetDateTime frozen = null;
        try {
            JsonNode value = json.readTree(run.contextSnapshotJson()).at("/knowledgeContext/memory/historyCutoffAt");
            if (value.isTextual()) frozen = OffsetDateTime.parse(value.asText());
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid frozen creative context", failure);
        }
        var current = memory == null ? null : memory.context(run.userId(), project);
        OffsetDateTime live = current == null ? null : current.historyCutoffAt();
        return frozen == null ? live : live == null || frozen.isAfter(live) ? frozen : live;
    }

    private String text(ConversationMessageRecord message) {
        try {
            JsonNode value = json.readTree(message.contentJson()).path("text");
            return value.isTextual() ? value.textValue() : null;
        } catch (Exception invalid) { return null; }
    }

    private String fingerprint(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))); }
        catch (Exception failure) { throw new IllegalStateException("Unable to bind creative continuation", failure); }
    }
}
