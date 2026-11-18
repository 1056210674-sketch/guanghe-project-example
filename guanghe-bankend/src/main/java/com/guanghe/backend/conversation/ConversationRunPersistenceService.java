package com.guanghe.backend.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.conversation.context.ConversationWorkspaceService;
import com.guanghe.backend.conversation.resource.RunResourceService;
import com.guanghe.backend.conversation.resource.RunResourceDtos;
import com.guanghe.backend.integration.internal.client.PythonChatRunEvent;
import com.guanghe.backend.task.TaskControlService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationRunPersistenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationRunPersistenceService.class);

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "STOPPED");
    private static final Set<String> TASK_TYPES = Set.of("GEO", "IMAGE", "VIDEO", "ARTICLE", "MEDIA");
    private static final Set<String> INTENTS = Set.of(
            "GENERAL", "GEO", "IMAGE", "VIDEO", "ARTICLE", "AMBIGUOUS_CONFIRMATION"
    );
    private static final Set<String> BUSINESS_STATUSES = Set.of(
            "ANSWERED", "NEEDS_INPUT", "DRAFT_READY", "PARTIAL", "FAILED");
    private static final Set<String> BUSINESS_STAGES = Set.of("LOOKUP", "PLAN", "TOOL", "SYNTHESIS", "PROTOCOL");
    private static final Set<String> BUSINESS_REASONS = Set.of("INPUT_REQUIRED", "TASK_DRAFT_PROPOSED",
            "READONLY_PLANNER_UNAVAILABLE", "READONLY_PLAN_INVALID", "READONLY_EVIDENCE_UNAVAILABLE",
            "READONLY_PARTIAL_EVIDENCE", "READONLY_SYNTHESIS_UNAVAILABLE", "GEO_CONTEXT_UNAVAILABLE", "TASK_PARAMETERS_INVALID",
            "TASK_PREPARATION_REJECTED", "RESPONSE_PROTOCOL_INVALID", "AGENT_RUN_FAILED");
    private static final Set<String> INTERACTION_FIELDS = Set.of(
            "interactionType", "intent", "question", "missingFields", "choices"
    );
    private static final Set<String> DRAFT_REQUIRED_FIELDS = Set.of(
            "proposalOnly", "taskType", "parameterSchemaVersion", "spaceId", "productId",
            "parameters", "warnings"
    );
    private static final Set<String> DRAFT_FIELDS = Set.of(
            "proposalOnly", "taskType", "parameterSchemaVersion", "spaceId", "productId",
            "parameters", "warnings", "sourceMode", "referenceAssetIds", "sourceMessageId",
            "productCreativeBrief", "draftOperation", "previousDraftId"
    );
    private static final Set<String> DELTA_FIELDS = Set.of("messageId", "delta", "replace");
    private static final Set<String> PROGRESS_REQUIRED_FIELDS = Set.of(
            "stageCode", "status", "ordinal", "elapsedMs"
    );
    private static final Set<String> PROGRESS_FIELDS = Set.of(
            "stageCode", "status", "ordinal", "elapsedMs", "current", "total"
    );
    private static final Set<String> PROGRESS_STAGES = Set.of(
            "UNDERSTANDING_REQUEST", "LOADING_BUSINESS_CONTEXT", "LOADING_PRODUCT_CONTEXT",
            "LOADING_REFERENCE_ASSETS", "ROUTING_INTENT", "PLANNING_WORKFLOW",
            "RUNNING_SAFE_TOOL", "SEARCHING_WEB", "PREPARING_TASK_DRAFT", "COMPOSING_RESPONSE"
    );
    private static final Set<String> PROGRESS_STATUSES = Set.of("RUNNING", "SUCCEEDED", "FAILED");
    private static final int MAX_PROGRESS_EVENTS = 24;
    private static final int MAX_PROGRESS_BYTES = 2 * 1024;
    private static final Set<String> USAGE_REQUIRED_FIELDS = Set.of(
            "workflowStepCount", "modelCallCount", "contextLoadCount", "toolCallCount",
            "providerCallCount", "modelCalls", "toolCalls", "steps", "estimatedInputTokens"
    );
    private static final Set<String> USAGE_OPTIONAL_FIELDS = Set.of(
            "providerInputTokens", "providerOutputTokens", "providerLatencyMs", "provider", "model"
    );
    private static final Set<String> USAGE_FIELDS = Set.of(
            "workflowStepCount", "modelCallCount", "contextLoadCount", "toolCallCount",
            "providerCallCount", "modelCalls", "toolCalls", "steps", "estimatedInputTokens",
            "providerInputTokens", "providerOutputTokens", "providerLatencyMs", "provider", "model"
    );
    private static final Set<String> USAGE_NUMERIC_FIELDS = Set.of(
            "workflowStepCount", "modelCallCount", "contextLoadCount", "toolCallCount",
            "providerCallCount", "modelCalls", "toolCalls", "steps", "estimatedInputTokens",
            "providerInputTokens", "providerOutputTokens", "providerLatencyMs"
    );
    private static final int MAX_ASSISTANT_TEXT_LENGTH = 64_000;
    private static final String RUN_FAILURE_MESSAGE =
            "抱歉，本次处理未能完成。请稍后重试，或调整需求后重新发送。";
    private static final String RESPONSE_TRUNCATED_MESSAGE =
            "本次回复达到长度上限，未能完整生成。已有内容和任务记录仍保留。";
    private static final String TASK_PARAMETERS_FAILURE_MESSAGE =
            "刚才整理创作方案时出了点问题，尚未创建草稿，也未开始生成。你的要求已保留，可以稍后重试。";
    private static final String TASK_DRAFT_FAILURE_MESSAGE =
            "抱歉，本次任务没有创建成功。请检查当前经营空间、商品和需求后重试。";

    private final ConversationMapper mapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final ObservabilitySupport observability;
    private TaskControlService taskControlService;
    private ConversationWorkspaceService workspaceService;
    private RunResourceService resourceService;

    @Autowired
    public ConversationRunPersistenceService(
            ConversationMapper mapper,
            SnowflakeIdGenerator idGenerator,
            ObjectMapper objectMapper,
            ObservabilitySupport observability
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.observability = observability;
    }

    public ConversationRunPersistenceService(
            ConversationMapper mapper,
            SnowflakeIdGenerator idGenerator,
            ObjectMapper objectMapper
    ) {
        this(mapper, idGenerator, objectMapper, ObservabilitySupport.noop());
    }

    @Autowired(required = false)
    void setTaskControlService(TaskControlService taskControlService) {
        this.taskControlService = taskControlService;
    }

    @Autowired(required = false)
    void setWorkspaceService(ConversationWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Autowired(required = false)
    void setResourceService(RunResourceService resourceService) { this.resourceService = resourceService; }

    @Transactional
    public AgentRunRecord markStarting(UUID runId) {
        OffsetDateTime now = now();
        if (mapper.markRunStarting(runId, now) != 1) {
            return mapper.findRun(runId).orElse(null);
        }
        return mapper.findRun(runId).orElseThrow();
    }

    @Transactional
    public void markRunning(UUID runId, String eventsUrl) {
        mapper.markRunRunning(runId, eventsUrl, now());
    }

    @Transactional
    public ProcessedEvent processEvent(UUID runId, PythonChatRunEvent event, String accumulatedText) {
        AgentRunRecord run = mapper.lockRun(runId).orElse(null);
        if (run == null) {
            return ProcessedEvent.terminalWithoutEvent();
        }
        if (TERMINAL.contains(run.status())) {
            // Python cancellation can report known usage after Java has acknowledged the stop.
            // Only accounting may advance here: never resurrect the Run or emit another result.
            if (!"usage.reported".equals(event.eventType()) || event.sequence() <= run.lastPythonSequence()) {
                return ProcessedEvent.terminalWithoutEvent();
            }
            return processUsage(run, event, now());
        }
        OffsetDateTime now = now();
        if (mapper.advancePythonSequence(runId, event.sequence(), now) != 1) {
            return ProcessedEvent.invalidSequence();
        }
        return switch (event.eventType()) {
            case "run.started" -> persist(run, event, "message.start", objectNode(Map.of(
                    "messageId", Long.toString(run.responseMessageId()),
                    "runId", run.id().toString(),
                    "status", "RUNNING"
            )), false);
            case "message.delta" -> processDelta(run, event, accumulatedText, now);
            case "run.progress" -> processProgress(run, event, now);
            case "interaction.required" -> processInteraction(run, event, accumulatedText, now);
            case "task-draft.proposed" -> processTaskDraft(run, event, accumulatedText, now);
            case "run.completed" -> processCompleted(run, event, accumulatedText, now);
            case "run.failed" -> processFailed(run, event, accumulatedText, now);
            case "usage.reported" -> processUsage(run, event, now);
            case "heartbeat" -> persist(run, event, "heartbeat", objectNode(Map.of(
                    "runId", run.id().toString()
            )), false);
            default -> ProcessedEvent.invalidEvent();
        };
    }

    private ProcessedEvent processProgress(
            AgentRunRecord run,
            PythonChatRunEvent event,
            OffsetDateTime now
    ) {
        JsonNode payload = event.payload();
        if (event.schemaVersion() != 1 || event.eventId() == null || event.occurredAt() == null
                || !run.id().equals(event.runId()) || payload == null || !payload.isObject()) {
            return dropProgress(run, "INVALID_ENVELOPE");
        }
        Set<String> fields = fieldNames(payload);
        if (!fields.containsAll(PROGRESS_REQUIRED_FIELDS) || !PROGRESS_FIELDS.containsAll(fields)) {
            return dropProgress(run, "INVALID_FIELDS");
        }
        JsonNode stage = payload.get("stageCode");
        JsonNode status = payload.get("status");
        JsonNode ordinalNode = payload.get("ordinal");
        JsonNode elapsedNode = payload.get("elapsedMs");
        if (stage == null || !stage.isTextual() || !PROGRESS_STAGES.contains(stage.textValue())
                || status == null || !status.isTextual() || !PROGRESS_STATUSES.contains(status.textValue())
                || ordinalNode == null || !ordinalNode.isIntegralNumber() || !ordinalNode.canConvertToInt()
                || ordinalNode.intValue() < 1 || ordinalNode.intValue() > 32
                || !nonNegativeLong(elapsedNode)) {
            return dropProgress(run, "INVALID_VALUE");
        }
        JsonNode currentNode = payload.get("current");
        JsonNode totalNode = payload.get("total");
        if (!nullableNonNegativeLong(currentNode) || !nullableNonNegativeLong(totalNode)) {
            return dropProgress(run, "INVALID_COUNTERS");
        }
        Long current = nullableLongValue(currentNode);
        Long total = nullableLongValue(totalNode);
        if ((current == null) != (total == null)
                || (current != null && current > total)) {
            return dropProgress(run, "INVALID_COUNTERS");
        }
        int ordinal = ordinalNode.intValue();
        long elapsedMs = elapsedNode.longValue();
        if (mapper.findAgentProgressByIdentity(run.id(), stage.textValue(), status.textValue(), ordinal)
                .isPresent()) {
            return ProcessedEvent.withoutBrowserEvent();
        }
        if (mapper.countAgentProgress(run.id()) >= MAX_PROGRESS_EVENTS) {
            return dropProgress(run, "EVENT_LIMIT_REACHED");
        }
        Optional<ConversationEventRecord> latest = mapper.findLatestAgentProgress(run.id());
        if (latest.isPresent() && progressRegressed(latest.orElseThrow(), ordinal, elapsedMs)) {
            return dropProgress(run, "NON_MONOTONIC");
        }
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("runId", run.id().toString());
        normalized.put("messageId", Long.toString(run.responseMessageId()));
        normalized.put("stageCode", stage.textValue());
        normalized.put("status", status.textValue());
        normalized.put("ordinal", ordinal);
        normalized.put("elapsedMs", elapsedMs);
        if (current == null) {
            normalized.putNull("current");
            normalized.putNull("total");
        } else {
            normalized.put("current", current);
            normalized.put("total", total);
        }
        if (writeJson(normalized).getBytes(StandardCharsets.UTF_8).length > MAX_PROGRESS_BYTES) {
            return dropProgress(run, "EVENT_TOO_LARGE");
        }
        ConversationEventRecord stored = insertBrowserEvent(
                run, event.eventId(), "agent.progress", normalized, now, now
        );
        return new ProcessedEvent(Optional.of(stored), false, false, false, null);
    }

    private boolean progressRegressed(ConversationEventRecord latest, int ordinal, long elapsedMs) {
        try {
            JsonNode payload = objectMapper.readTree(latest.payloadJson());
            return ordinal < payload.path("ordinal").intValue()
                    || elapsedMs < payload.path("elapsedMs").longValue();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode persisted progress", exception);
        }
    }

    private ProcessedEvent dropProgress(AgentRunRecord run, String reason) {
        LOGGER.warn("conversation_run_progress_dropped runIdRef={} reason={}",
                observability.reference(run.id()), reason);
        return ProcessedEvent.withoutBrowserEvent();
    }

    @Transactional
    public Optional<ConversationEventRecord> failRun(UUID runId, String errorCode, String accumulatedText) {
        AgentRunRecord run = mapper.lockRun(runId).orElse(null);
        if (run == null || TERMINAL.contains(run.status())) {
            return Optional.empty();
        }
        OffsetDateTime now = now();
        if (mapper.completeRun(runId, "FAILED", safeCode(errorCode), now) != 1) {
            return Optional.empty();
        }
        replaceAssistantWithFailure(run, failureMessage(errorCode), now);
        ObjectNode payload = terminalPayload(run, "FAILED");
        attachFailureOutcome(run, payload, "AGENT_RUN_FAILED", "PROTOCOL", now);
        refreshWorkspace(run);
        return Optional.of(insertBrowserEvent(
                run, null, "message.completed", payload, now, now
        ));
    }

    /** Ends orphaned free Runs without restarting a model or touching paid tasks. */
    @Transactional
    public Optional<ConversationEventRecord> expireOneRun(OffsetDateTime cutoff) {
        AgentRunRecord run = mapper.lockExpiredRun(cutoff).orElse(null);
        if (run == null) return Optional.empty();
        if ("STOP_REQUESTED".equals(run.status())) return stopRun(run.id()).browserEvent();
        return failRun(run.id(), "AGENT_RUN_DEADLINE_EXCEEDED", "");
    }

    @Transactional
    public StopResult stopRun(UUID runId) {
        AgentRunRecord run = mapper.lockRun(runId).orElse(null);
        if (run == null) {
            return new StopResult("FAILED", Optional.empty());
        }
        if (TERMINAL.contains(run.status())) {
            return new StopResult(run.status(), Optional.empty());
        }
        OffsetDateTime now = now();
        if (mapper.completeRun(runId, "STOPPED", null, now) != 1) {
            String finalStatus = mapper.findRun(runId).map(AgentRunRecord::status).orElse("FAILED");
            return new StopResult(finalStatus, Optional.empty());
        }
        updateAssistantStatus(run, "STOPPED", now);
        refreshWorkspace(run);
        return new StopResult("STOPPED", Optional.of(insertBrowserEvent(
                run, null, "message.completed", objectNode(Map.of(
                        "messageId", Long.toString(run.responseMessageId()),
                        "runId", run.id().toString(),
                        "status", "STOPPED"
                )), now, now
        )));
    }

    private ProcessedEvent processDelta(
            AgentRunRecord run,
            PythonChatRunEvent event,
            String accumulatedText,
            OffsetDateTime now
    ) {
        JsonNode messageId = event.payload().get("messageId");
        JsonNode delta = event.payload().get("delta");
        JsonNode replaceNode = event.payload().get("replace");
        boolean replace = replaceNode != null && replaceNode.isBoolean() && replaceNode.booleanValue();
        if (!event.payload().isObject()
                || !DELTA_FIELDS.containsAll(fieldNames(event.payload()))
                || messageId == null || !messageId.isTextual()
                || !Long.toString(run.requestMessageId()).equals(messageId.textValue())
                || (replaceNode != null && !replaceNode.isBoolean())
                || delta == null || !delta.isTextual() || (!replace && delta.textValue().isEmpty())
                || delta.textValue().length() > 16000
                || accumulatedText == null
                || (!replace && accumulatedText.length() > MAX_ASSISTANT_TEXT_LENGTH - delta.textValue().length())) {
            return ProcessedEvent.invalidEvent();
        }
        String updatedText = replace ? delta.textValue() : accumulatedText + delta.textValue();
        if (mapper.updateAssistantMessage(
                run.responseMessageId(), run.userId(), run.id(), updatedText, "STREAMING", now
        ) != 1) {
            throw new IllegalStateException("Assistant message disappeared while persisting a delta");
        }
        ObjectNode payload = objectNode(Map.of(
                "messageId", Long.toString(run.responseMessageId()),
                "delta", delta.textValue()
        ));
        if (replaceNode != null) payload.put("replace", replace);
        ProcessedEvent persisted = persist(run, event, "message.delta", payload, false);
        return persisted.withAcceptedDelta(delta.textValue(), replace);
    }

    private ProcessedEvent processInteraction(
            AgentRunRecord run,
            PythonChatRunEvent event,
            String accumulatedText,
            OffsetDateTime now
    ) {
        JsonNode question = event.payload().get("question");
        JsonNode interactionType = event.payload().get("interactionType");
        JsonNode intent = event.payload().get("intent");
        JsonNode missingFields = event.payload().get("missingFields");
        JsonNode choices = event.payload().get("choices");
        if ("RESOURCE_SELECTION".equals(event.payload().path("interactionType").asText())) {
            if (resourceService == null || !resourceLookupEnabled(run)
                    || !fieldNames(event.payload()).equals(Set.of("interactionType", "candidateSetId", "question"))
                    || question == null || !question.isTextual() || question.textValue().isBlank()
                    || question.textValue().length() > 4000
                    || !positiveId(event.payload().path("candidateSetId"))) return ProcessedEvent.invalidEvent();
            JsonNode serverInteraction;
            try {
                serverInteraction = resourceService.selectionInteraction(run,
                        event.payload().path("candidateSetId").textValue(), question.textValue());
            } catch (BusinessException exception) {
                return ProcessedEvent.invalidEvent();
            }
            ObjectNode content = objectNode(Map.of("text", accumulatedText));
            content.set("interaction", serverInteraction.deepCopy());
            if (mapper.updateAssistantInteraction(run.responseMessageId(), run.userId(), run.id(),
                    writeJson(content), now) != 1) {
                throw new IllegalStateException("Assistant message disappeared while persisting a resource selection");
            }
            ObjectNode browserPayload = (ObjectNode) serverInteraction.deepCopy();
            browserPayload.put("messageId", Long.toString(run.responseMessageId()));
            browserPayload.put("available", true);
            refreshWorkspace(run);
            return persist(run, event, "interaction.required", browserPayload, false);
        }
        if (!event.payload().isObject() || !fieldNames(event.payload()).equals(INTERACTION_FIELDS)
                || question == null || !question.isTextual() || question.textValue().isBlank()
                || question.textValue().length() > 4000
                || interactionType == null || !"CLARIFICATION".equals(interactionType.textValue())
                || intent == null || !intent.isTextual() || !INTENTS.contains(intent.textValue())
                || !boundedStringArray(missingFields, 50, 128, true)
                || !boundedStringArray(choices, 50, 500, false)) {
            return ProcessedEvent.invalidEvent();
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("interactionType", "CLARIFICATION");
        payload.put("intent", intent.textValue());
        payload.put("question", question.textValue());
        payload.set("missingFields", missingFields.deepCopy());
        payload.set("choices", choices.deepCopy());
        ObjectNode content = objectNode(Map.of("text", accumulatedText));
        content.set("interaction", payload.deepCopy());
        if (mapper.updateAssistantInteraction(
                run.responseMessageId(), run.userId(), run.id(), writeJson(content), now
        ) != 1) {
            throw new IllegalStateException("Assistant message disappeared while persisting an interaction");
        }
        refreshWorkspace(run);
        payload.put("messageId", Long.toString(run.responseMessageId()));
        return persist(run, event, "interaction.required", payload, false);
    }

    private ProcessedEvent rejectTaskDraft(
            AgentRunRecord run,
            PythonChatRunEvent event,
            String accumulatedText,
            OffsetDateTime now
    ) {
        JsonNode payload = event.payload();
        boolean valid = payload.isObject()
                && fieldNames(payload).containsAll(DRAFT_REQUIRED_FIELDS)
                && DRAFT_FIELDS.containsAll(fieldNames(payload))
                && payload.path("proposalOnly").isBoolean()
                && payload.path("proposalOnly").booleanValue()
                && payload.path("parameterSchemaVersion").isIntegralNumber()
                && payload.path("parameterSchemaVersion").canConvertToInt()
                && payload.path("parameterSchemaVersion").intValue() == 1
                && TASK_TYPES.contains(payload.path("taskType").asText())
                && nullableId(payload.get("spaceId"))
                && nullableId(payload.get("productId"))
                && payload.path("parameters").isObject()
                && boundedStringArray(payload.path("warnings"), 20, 500, false);
        String code = valid ? "TASK_DRAFT_NOT_SUPPORTED" : "TASK_DRAFT_SCHEMA_INVALID";
        if (mapper.completeRun(run.id(), "FAILED", code, now) != 1) {
            return ProcessedEvent.terminalWithoutEvent();
        }
        replaceAssistantWithFailure(run, TASK_DRAFT_FAILURE_MESSAGE, now);
        ObjectNode terminalPayload = terminalPayload(run, "FAILED");
        attachFailureOutcome(run, terminalPayload, "TASK_PREPARATION_REJECTED", "TOOL", now);
        refreshWorkspace(run);
        ConversationEventRecord stored = insertBrowserEvent(
                run, event.eventId(), "message.completed", terminalPayload, event.occurredAt(), now
        );
        return new ProcessedEvent(Optional.of(stored), true, false, true, null);
    }

    private ProcessedEvent processTaskDraft(
            AgentRunRecord run, PythonChatRunEvent event, String accumulatedText, OffsetDateTime now
    ) {
        if (taskControlService == null) {
            return rejectTaskDraft(run, event, accumulatedText, now);
        }
        try {
            ConversationEventRecord stored = taskControlService.acceptProposal(
                    run, event, accumulatedText == null ? "" : accumulatedText, now
            );
            refreshWorkspace(run);
            return new ProcessedEvent(Optional.of(stored), false, false, false, null);
        } catch (BusinessException exception) {
            String code = "TASK_DRAFT_" + exception.errorCode().name();
            LOGGER.warn("task_draft_proposal_rejected runId={} code={}", run.id(), code);
            return rejectTaskDraft(run, event, now, code);
        } catch (IllegalArgumentException exception) {
            String code = Set.of("TASK_DRAFT_REVISION_STALE", "TASK_DRAFT_REVISION_SOURCE_MISMATCH")
                    .contains(exception.getMessage() == null ? "" : exception.getMessage())
                    ? exception.getMessage() : "TASK_DRAFT_SCHEMA_INVALID";
            LOGGER.warn("task_draft_proposal_rejected runId={} code={}", run.id(), code);
            return rejectTaskDraft(run, event, now, code);
        }
    }

    private ProcessedEvent rejectTaskDraft(
            AgentRunRecord run,
            PythonChatRunEvent event,
            OffsetDateTime now,
            String code
    ) {
        if (mapper.completeRun(run.id(), "FAILED", code, now) != 1) {
            return ProcessedEvent.terminalWithoutEvent();
        }
        String message = switch (code) {
            case "TASK_DRAFT_REVISION_STALE" -> "原方案已变更、过期或确认，未替换旧方案，也未开始生成。请查看当前方案后重新整理。";
            case "TASK_DRAFT_REVISION_SOURCE_MISMATCH" -> "所选商品或创作来源已变化，未替换旧方案，也未开始生成。请重新整理新的方案。";
            default -> TASK_DRAFT_FAILURE_MESSAGE;
        };
        replaceAssistantWithFailure(run, message, now);
        ObjectNode terminalPayload = terminalPayload(run, "FAILED");
        attachFailureOutcome(run, terminalPayload, "TASK_PREPARATION_REJECTED", "TOOL", now);
        refreshWorkspace(run);
        ConversationEventRecord stored = insertBrowserEvent(
                run, event.eventId(), "message.completed", terminalPayload, event.occurredAt(), now
        );
        return new ProcessedEvent(Optional.of(stored), true, false, true, null);
    }

    private ProcessedEvent processCompleted(
            AgentRunRecord run,
            PythonChatRunEvent event,
            String accumulatedText,
            OffsetDateTime now
    ) {
        JsonNode statusNode = event.payload().get("status");
        if (!event.payload().isObject() || statusNode == null || !statusNode.isTextual()
                || !Set.of("status", "intent", "domainGraph", "reason", "geoContextUsed", "businessOutcome")
                    .containsAll(fieldNames(event.payload()))
                || !optionalBoundedText(event.payload(), "intent", 64)
                || !optionalBoundedText(event.payload(), "domainGraph", 128)
                || !optionalBoundedText(event.payload(), "reason", 128)
                || (event.payload().has("geoContextUsed") && !event.payload().path("geoContextUsed").isBoolean())
                || (event.payload().has("businessOutcome") && !validBusinessOutcome(event.payload().get("businessOutcome")))
                || !("COMPLETED".equals(statusNode.textValue()) || "STOPPED".equals(statusNode.textValue()))) {
            return ProcessedEvent.invalidEvent();
        }
        String status = mapper.completeRunFromPython(run.id(), statusNode.textValue(), now);
        if (status == null) {
            return ProcessedEvent.terminalWithoutEvent();
        }
        updateAssistantStatus(run, status, now);
        if ("COMPLETED".equals(status) && event.payload().path("geoContextUsed").asBoolean(false)) {
            ObjectNode geoContext = frozenGeoBrowserContext(run);
            if (geoContext != null && mapper.setAssistantGeoContext(
                    run.responseMessageId(), run.userId(), run.id(), writeJson(geoContext), now) != 1) {
                throw new IllegalStateException("Assistant message disappeared while saving frozen GEO context");
            }
        }
        ObjectNode terminalPayload = terminalPayload(run, status);
        if ("COMPLETED".equals(status) && businessOutcomeEnabled(run)) {
            ObjectNode outcome = completedBusinessOutcome(run, event.payload().get("businessOutcome"), now);
            saveBusinessOutcome(run, outcome, now);
            terminalPayload.set("businessOutcome", outcome);
        }
        refreshWorkspace(run);
        return persist(run, event, "message.completed", terminalPayload, true);
    }

    private ProcessedEvent processFailed(
            AgentRunRecord run,
            PythonChatRunEvent event,
            String accumulatedText,
            OffsetDateTime now
    ) {
        JsonNode status = event.payload().get("status");
        JsonNode codeNode = event.payload().get("code");
        if (!event.payload().isObject()
                || status == null || !status.isTextual() || !"FAILED".equals(status.textValue())
                || codeNode == null || !codeNode.isTextual() || !validCode(codeNode.textValue())) {
            return ProcessedEvent.invalidEvent();
        }
        String code = codeNode.textValue();
        if (mapper.completeRun(run.id(), "FAILED", code, now) != 1) {
            return ProcessedEvent.terminalWithoutEvent();
        }
        replaceAssistantWithFailure(run, failureMessage(code), now);
        ObjectNode terminalPayload = terminalPayload(run, "FAILED");
        attachFailureOutcome(run, terminalPayload, "DEEPSEEK_TASK_RESPONSE_INVALID".equals(code)
                ? "TASK_PARAMETERS_INVALID" : "AGENT_RUN_FAILED", "PROTOCOL", now);
        refreshWorkspace(run);
        return persist(run, event, "message.completed", terminalPayload, true);
    }

    private ObjectNode terminalPayload(AgentRunRecord run, String status) {
        ObjectNode payload=objectNode(Map.of("messageId", Long.toString(run.responseMessageId()),
                "runId", run.id().toString(), "status", status));
        mapper.findMessage(run.responseMessageId(),run.userId(),run.conversationId()).ifPresent(message->{
            try {
                JsonNode reference=objectMapper.readTree(message.contentJson()).get("articlePreparation");
                if(reference!=null && reference.isObject()) payload.set("articlePreparation",reference);
            } catch(JsonProcessingException exception) { throw new IllegalStateException("Invalid stored article reference",exception); }
        });
        return payload;
    }

    private boolean validBusinessOutcome(JsonNode value) {
        return value != null && value.isObject()
                && Set.of("schemaVersion", "status", "reasonCode", "failureStage").containsAll(fieldNames(value))
                && value.path("schemaVersion").isIntegralNumber() && value.path("schemaVersion").canConvertToInt()
                && value.path("schemaVersion").asInt() == 1
                && value.path("status").isTextual() && BUSINESS_STATUSES.contains(value.path("status").textValue())
                && (!value.has("reasonCode") || value.path("reasonCode").isTextual()
                    && BUSINESS_REASONS.contains(value.path("reasonCode").textValue()))
                && (!value.has("failureStage") || value.path("failureStage").isTextual()
                    && BUSINESS_STAGES.contains(value.path("failureStage").textValue()));
    }

    private boolean businessOutcomeEnabled(AgentRunRecord run) {
        try {
            return objectMapper.readTree(run.contextSnapshotJson()).path("protocolFeatures")
                    .path("businessOutcome").asBoolean(false);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read frozen Run protocol features", exception);
        }
    }

    private boolean resourceLookupEnabled(AgentRunRecord run) {
        try {
            return objectMapper.readTree(run.contextSnapshotJson()).path("protocolFeatures")
                    .path("resourceLookup").asBoolean(false);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read frozen Run resource feature", exception);
        }
    }

    private ObjectNode completedBusinessOutcome(AgentRunRecord run, JsonNode declared, OffsetDateTime now) {
        if (mapper.hasReadyRunDraft(run.id(), run.userId(), run.conversationId(), now)) {
            return businessOutcome("DRAFT_READY", null, null);
        }
        if (declared != null && "DRAFT_READY".equals(declared.path("status").asText())) {
            return businessOutcome("FAILED", "TASK_PREPARATION_REJECTED", "TOOL");
        }
        if (mapper.findMessage(run.responseMessageId(), run.userId(), run.conversationId())
                .map(message -> "INTERACTION".equals(message.contentType())).orElse(false)) {
            if (declared != null && "NEEDS_INPUT".equals(declared.path("status").asText())) {
                return (ObjectNode) declared.deepCopy();
            }
            return businessOutcome("NEEDS_INPUT", "INPUT_REQUIRED", null);
        }
        if (resourceLookupEnabled(run)) {
            try {
                JsonNode selection = objectMapper.readTree(run.contextSnapshotJson()).path("resourceSelection");
                if (selection.isObject() && (resourceService == null || !resourceService.hasSuccessfulRead(
                        run.userId(), run.conversationId(), run.id(), selection.path("candidateSetId").asText(),
                        selection.path("choiceId").asText()))) {
                    return businessOutcome("FAILED", "READONLY_EVIDENCE_UNAVAILABLE", "TOOL");
                }
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Unable to read frozen Run selection", exception);
            }
        }
        return declared == null ? businessOutcome("ANSWERED", null, null) : (ObjectNode) declared.deepCopy();
    }

    private ObjectNode businessOutcome(String status, String reason, String stage) {
        ObjectNode result = objectMapper.createObjectNode().put("schemaVersion", 1).put("status", status);
        if (reason != null) result.put("reasonCode", reason);
        if (stage != null) result.put("failureStage", stage);
        return result;
    }

    private void attachFailureOutcome(AgentRunRecord run, ObjectNode payload, String reason,
                                      String stage, OffsetDateTime now) {
        if (!businessOutcomeEnabled(run)) return;
        ObjectNode outcome = businessOutcome("FAILED", reason, stage);
        saveBusinessOutcome(run, outcome, now);
        payload.set("businessOutcome", outcome);
    }

    private void saveBusinessOutcome(AgentRunRecord run, ObjectNode outcome, OffsetDateTime now) {
        if (mapper.setAssistantBusinessOutcome(run.responseMessageId(), run.userId(), run.id(),
                writeJson(outcome), now) != 1) {
            throw new IllegalStateException("Assistant message disappeared while saving its business outcome");
        }
        LOGGER.info("conversation_business_outcome runIdRef={} status={} reason={} stage={}",
                observability.reference(run.id()), outcome.path("status").asText(),
                outcome.path("reasonCode").asText("NONE"), outcome.path("failureStage").asText("NONE"));
    }

    private ProcessedEvent processUsage(AgentRunRecord run, PythonChatRunEvent event, OffsetDateTime now) {
        JsonNode payload = event.payload();
        if (event.schemaVersion() != 1 || event.eventId() == null || event.occurredAt() == null
                || !run.id().equals(event.runId()) || payload == null) {
            return ProcessedEvent.invalidEvent();
        }
        Set<String> fields = fieldNames(payload);
        if (!payload.isObject()
                || !fields.containsAll(USAGE_REQUIRED_FIELDS)
                || !USAGE_FIELDS.containsAll(fields)
                || USAGE_REQUIRED_FIELDS.stream().anyMatch(field -> !nonNegativeInteger(payload.get(field)))
                || USAGE_OPTIONAL_FIELDS.stream()
                .filter(USAGE_NUMERIC_FIELDS::contains)
                .anyMatch(field -> payload.has(field) && !nonNegativeInteger(payload.get(field)))
                || !sameInteger(payload, "workflowStepCount", "steps")
                || !sameInteger(payload, "modelCallCount", "modelCalls")
                || !sameInteger(payload, "toolCallCount", "toolCalls")
                || !nullableString(payload, "provider")
                || !nullableString(payload, "model")) {
            return ProcessedEvent.invalidEvent();
        }
        boolean terminal = TERMINAL.contains(run.status());
        if (terminal && mapper.advanceTerminalUsageSequence(run.id(), event.sequence(), now) != 1) {
            return ProcessedEvent.terminalWithoutEvent();
        }
        ObjectNode normalized = objectMapper.createObjectNode();
        USAGE_REQUIRED_FIELDS.forEach(field -> normalized.set(field, payload.get(field)));
        normalized.set("providerInputTokens", payload.has("providerInputTokens")
                ? payload.get("providerInputTokens") : objectMapper.getNodeFactory().numberNode(0));
        normalized.set("providerOutputTokens", payload.has("providerOutputTokens")
                ? payload.get("providerOutputTokens") : objectMapper.getNodeFactory().numberNode(0));
        normalized.set("providerLatencyMs", payload.has("providerLatencyMs")
                ? payload.get("providerLatencyMs") : objectMapper.getNodeFactory().numberNode(0));
        copyNullableText(normalized, payload, "provider");
        copyNullableText(normalized, payload, "model");
        if (mapper.updateRunUsage(run.id(), writeJson(normalized), now) != 1) {
            throw new IllegalStateException("Run disappeared while persisting usage");
        }
        return terminal ? ProcessedEvent.terminalWithoutEvent() : ProcessedEvent.withoutBrowserEvent();
    }

    private ProcessedEvent persist(
            AgentRunRecord run,
            PythonChatRunEvent source,
            String eventType,
            ObjectNode payload,
            boolean terminal
    ) {
        ConversationEventRecord stored = insertBrowserEvent(
                run, source.eventId(), eventType, payload, source.occurredAt(), now()
        );
        return new ProcessedEvent(Optional.of(stored), terminal, false, false, null);
    }

    private ConversationEventRecord insertBrowserEvent(
            AgentRunRecord run,
            UUID sourceEventId,
            String eventType,
            JsonNode payload,
            OffsetDateTime occurredAt,
            OffsetDateTime createdAt
    ) {
        Long sequence = mapper.nextEventSequence(run.conversationId(), run.userId());
        if (sequence == null) {
            throw new IllegalStateException("Conversation disappeared while persisting an event");
        }
        ConversationEventRecord event = new ConversationEventRecord(
                idGenerator.nextId(), run.conversationId(), run.userId(), run.id(), run.responseMessageId(),
                null, sourceEventId, eventType, sequence, writeJson(payload), occurredAt, createdAt
        );
        if (mapper.insertEvent(event) != 1) {
            throw new IllegalStateException("Duplicate Python event");
        }
        return event;
    }

    private ObjectNode objectNode(Map<String, String> values) {
        ObjectNode node = objectMapper.createObjectNode();
        values.forEach(node::put);
        return node;
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode conversation event", exception);
        }
    }

    private String safeCode(String value) {
        if (validCode(value)) {
            return value;
        }
        return "AGENT_RUN_FAILED";
    }

    private ObjectNode frozenGeoBrowserContext(AgentRunRecord run) {
        try {
            JsonNode snapshot = objectMapper.readTree(run.contextSnapshotJson());
            if (resourceService != null && snapshot.at("/protocolFeatures/resourceLookup").asBoolean(false)) {
                try {
                    RunResourceService.ActionReportReference reference = resourceService.currentRunLatestReportReference(run);
                    if (reference != null) return resourceGeoBrowserContext(run, reference);
                } catch (BusinessException invalidReference) {
                    // A revoked/reset resource anchor is not permission to revive a different legacy report.
                    LOGGER.info("geo_browser_context_unavailable reason=RESOURCE_REFERENCE_UNAVAILABLE");
                    return null;
                }
            }
            JsonNode source = snapshot.path("geoInsightContext");
            String status = source.path("status").asText();
            if (!source.isObject() || !Set.of("READY", "NEEDS_SCOPE", "NEEDS_TOPIC", "NO_REPORT", "UNAVAILABLE")
                    .contains(status)) return null;
            ObjectNode result = objectMapper.createObjectNode().put("status", status);
            String selection = source.path("selectionSource").asText();
            if (Set.of("EXPLICIT_REPORT", "AUTO_SINGLE_TOPIC").contains(selection)) result.put("selectionSource", selection);
            else result.putNull("selectionSource");
            result.put("topicsTruncated", source.path("topicsTruncated").asBoolean(false));
            result.putNull("report");
            var choices = result.putArray("topicChoices");
            for (JsonNode topic : source.path("topicChoices")) {
                if (choices.size() >= 6) break;
                if (!geoScopeMatches(run, topic) || !positiveId(topic.path("topicId"))) continue;
                ObjectNode choice = choices.addObject();
                copyFields(topic, choice, "topicId", "spaceId", "productId", "name");
                choice.put("href", "/app/geo?topicId=" + topic.path("topicId").textValue());
            }
            if ("READY".equals(status)) {
                JsonNode report = source.path("report");
                // Old snapshots without frozen scope/eligibility do not receive actionable metadata.
                if (!geoScopeMatches(run, report) || !positiveId(report.path("reportId"))
                        || !positiveId(report.path("topicId")) || !report.path("canCreateArticle").isBoolean()
                        || !report.has("articleUnavailableReason")) return null;
                ObjectNode reference = result.putObject("report");
                copyFields(report, reference, "reportId", "topicId", "title", "spaceId", "productId",
                        "generatedAt", "canCreateArticle", "articleUnavailableReason");
            }
            return result;
        } catch (JsonProcessingException exception) {
            LOGGER.warn("geo_browser_context_unavailable reason=INVALID_STORED_SNAPSHOT");
            return null;
        }
    }

    private ObjectNode resourceGeoBrowserContext(AgentRunRecord run, RunResourceService.ActionReportReference reference) {
        RunResourceDtos.ReadResponse read = reference.observation();
        if (read == null || !"GEO_TOPIC".equals(read.kind())
                || !Set.of("LATEST_GEO_REPORT", "GEO_ANSWER_EVIDENCE").contains(read.purpose())
                || !positiveId(objectMapper.valueToTree(read.observationId()))
                || !positiveId(objectMapper.valueToTree(read.resourceId()))
                || !positiveId(objectMapper.valueToTree(read.spaceId()))
                || (read.productId() != null && !positiveId(objectMapper.valueToTree(read.productId())))) return null;
        ObjectNode result = objectMapper.createObjectNode().put("status", read.reportId() == null ? "NO_REPORT" : "READY")
                .put("selectionSource", "EXPLICIT_REPORT").put("topicsTruncated", false);
        result.putArray("topicChoices");
        result.putNull("report");
        if (read.reportId() == null) return result; // Successful lookup of absence is a frozen barrier, not an article source.
        JsonNode report = reference.reportMetadata();
        if (report == null || !report.isObject() || !positiveId(report.path("reportId"))
                || !read.reportId().equals(report.path("reportId").asText())
                || !read.resourceId().equals(report.path("topicId").asText())
                || !read.spaceId().equals(report.path("spaceId").asText())
                || !report.has("productId")
                || !(read.productId() == null ? report.path("productId").isNull()
                    : read.productId().equals(report.path("productId").asText()))
                || !report.path("canCreateArticle").isBoolean() || !report.has("articleUnavailableReason")
                || !report.path("title").isTextual() || report.path("title").asText().length() > 100) return null;
        ObjectNode browserReport = result.putObject("report");
        copyFields(report, browserReport, "reportId", "topicId", "title", "spaceId", "productId",
                "generatedAt", "canCreateArticle", "articleUnavailableReason");
        browserReport.put("observationId", read.observationId());
        browserReport.put("available", report.path("canCreateArticle").asBoolean()
                && resourceService.isOwnedObservationAvailableForAction(run.userId(), run.conversationId(),
                    read.observationId(), Long.parseLong(read.reportId())));
        return result;
    }

    private boolean geoScopeMatches(AgentRunRecord run, JsonNode value) {
        return run.selectedSpaceId() != null && value.isObject()
                && Long.toString(run.selectedSpaceId()).equals(value.path("spaceId").asText())
                && value.has("productId")
                && (value.path("productId").isNull() || positiveId(value.path("productId")))
                && (run.selectedProductId() == null
                    || Long.toString(run.selectedProductId()).equals(value.path("productId").asText()));
    }

    private boolean positiveId(JsonNode value) {
        return value.isTextual() && value.textValue().matches("^[1-9][0-9]{0,18}$");
    }

    private void copyFields(JsonNode source, ObjectNode target, String... names) {
        for (String name : names) target.set(name, source.path(name).isMissingNode()
                ? objectMapper.nullNode() : source.path(name).deepCopy());
    }

    private void updateAssistantStatus(AgentRunRecord run, String status, OffsetDateTime now) {
        if (mapper.updateAssistantStatus(
                run.responseMessageId(), run.userId(), run.id(), status, now
        ) != 1) {
            throw new IllegalStateException("Assistant message disappeared while finalizing a Run");
        }
    }

    private String failureMessage(String code) {
        if ("DEEPSEEK_RESPONSE_TRUNCATED".equals(code)) {
            return RESPONSE_TRUNCATED_MESSAGE;
        }
        if ("DEEPSEEK_TASK_RESPONSE_INVALID".equals(code)) {
            return TASK_PARAMETERS_FAILURE_MESSAGE;
        }
        return RUN_FAILURE_MESSAGE;
    }

    private void replaceAssistantWithFailure(AgentRunRecord run, String message, OffsetDateTime now) {
        // Only committed text is reliable here: the in-memory accumulator can be empty after a
        // bridge exception/restart or contain an event that failed persistence. Never erase a
        // partial answer, and never recover it by silently starting another model generation.
        String text = mapper.findMessage(run.responseMessageId(), run.userId(), run.conversationId())
                .map(record -> {
                    try {
                        return objectMapper.readTree(record.contentJson()).path("text").asText("");
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("Unable to decode persisted answer", exception);
                    }
                }).filter(value -> !value.isBlank()).orElse(message);
        if (mapper.updateAssistantMessage(
                run.responseMessageId(), run.userId(), run.id(), text, "FAILED", now
        ) != 1) {
            throw new IllegalStateException("Assistant message disappeared while finalizing a failed Run");
        }
    }

    private void refreshWorkspace(AgentRunRecord run) {
        if (workspaceService != null) {
            workspaceService.refreshAfterCommit(run.userId(), run.conversationId());
        }
    }

    private boolean validCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}");
    }

    private Set<String> fieldNames(JsonNode value) {
        Set<String> result = new java.util.HashSet<>();
        value.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private boolean nonNegativeInteger(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.bigIntegerValue().signum() >= 0;
    }

    private boolean nonNegativeLong(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= 0;
    }

    private boolean nullableNonNegativeLong(JsonNode value) {
        return value == null || value.isNull() || nonNegativeLong(value);
    }

    private Long nullableLongValue(JsonNode value) {
        return value == null || value.isNull() ? null : value.longValue();
    }

    private boolean sameInteger(JsonNode payload, String authoritative, String mirror) {
        return payload.get(authoritative).bigIntegerValue().equals(payload.get(mirror).bigIntegerValue());
    }

    private boolean nullableString(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() || value.isTextual();
    }

    private boolean optionalBoundedText(JsonNode payload, String field, int maxLength) {
        JsonNode value = payload.get(field);
        return value == null || (value.isTextual() && !value.textValue().isBlank()
                && value.textValue().length() <= maxLength);
    }

    private void copyNullableText(ObjectNode target, JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) {
            target.putNull(field);
        } else {
            target.put(field, value.textValue());
        }
    }

    private boolean nullableId(JsonNode value) {
        return value != null && (value.isNull()
                || (value.isTextual() && value.textValue().matches("^[1-9][0-9]{0,18}$")));
    }

    private boolean boundedStringArray(JsonNode value, int maxItems, int maxLength, boolean nonEmpty) {
        if (value == null || !value.isArray() || value.size() > maxItems || (nonEmpty && value.isEmpty())) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > maxLength) {
                return false;
            }
        }
        return true;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public record ProcessedEvent(
            Optional<ConversationEventRecord> browserEvent,
            boolean terminal,
            boolean invalid,
            boolean taskDraftRejected,
            String acceptedDelta,
            boolean replacesText
    ) {
        public ProcessedEvent(Optional<ConversationEventRecord> browserEvent, boolean terminal,
                              boolean invalid, boolean taskDraftRejected, String acceptedDelta) {
            this(browserEvent, terminal, invalid, taskDraftRejected, acceptedDelta, false);
        }

        static ProcessedEvent terminalWithoutEvent() {
            return new ProcessedEvent(Optional.empty(), true, false, false, null);
        }

        static ProcessedEvent invalidSequence() {
            return new ProcessedEvent(Optional.empty(), false, true, false, null);
        }

        static ProcessedEvent invalidEvent() {
            return new ProcessedEvent(Optional.empty(), false, true, false, null);
        }

        static ProcessedEvent withoutBrowserEvent() {
            return new ProcessedEvent(Optional.empty(), false, false, false, null);
        }

        ProcessedEvent withAcceptedDelta(String delta, boolean replace) {
            return new ProcessedEvent(browserEvent, terminal, invalid, taskDraftRejected, delta, replace);
        }
    }

    public record StopResult(String status, Optional<ConversationEventRecord> browserEvent) {
    }
}
