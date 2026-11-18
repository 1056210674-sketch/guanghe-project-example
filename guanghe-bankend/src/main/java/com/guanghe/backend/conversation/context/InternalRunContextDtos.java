package com.guanghe.backend.conversation.context;

import com.guanghe.backend.catalog.competitor.CompetitorStatus;
import com.guanghe.backend.catalog.competitor.CompetitorType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.guanghe.backend.conversation.resource.RunResourceDtos;
import com.guanghe.backend.agentplatform.AgentPlatformDtos;
import com.guanghe.backend.knowledge.KnowledgeDtos;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class InternalRunContextDtos {

    private InternalRunContextDtos() {
    }

    public record RunContext(
            String runId,
            String conversationId,
            int contextVersion,
            Space space,
            Product product,
            List<Competitor> competitors,
            List<String> attachmentAssetIds,
            ConversationMemory conversationMemory,
            TaskWorkspace taskWorkspace,
            List<String> loadedToolGroups,
            ContextBudget contextBudget,
            TaskPreparationConstraints taskPreparationConstraints,
            JsonNode geoInsightContext,
            @JsonInclude(JsonInclude.Include.NON_NULL) ProtocolFeatures protocolFeatures,
            @JsonInclude(JsonInclude.Include.NON_NULL) RunResourceDtos.Selection resourceSelection,
            @JsonInclude(JsonInclude.Include.NON_NULL) RunResourceDtos.ReadResponse lastReadContext,
            @JsonInclude(JsonInclude.Include.NON_NULL) RunResourceDtos.SearchResponse pendingResourceContext,
            @JsonInclude(JsonInclude.Include.NON_NULL) RunResourceDtos.ReadResponse lastReportReadContext,
            @JsonInclude(JsonInclude.Include.NON_NULL) AgentPlatformDtos.AgentIdentity agentIdentity,
            @JsonInclude(JsonInclude.Include.NON_NULL) com.guanghe.backend.agentplatform.MonitorExecutionDtos.MonitorActionContext monitorActionContext,
            @JsonInclude(JsonInclude.Include.NON_NULL) KnowledgeDtos.KnowledgeContext knowledgeContext,
            @JsonInclude(JsonInclude.Include.NON_NULL) String articlePreparationMode
    ) {
        public RunContext(String runId,String conversationId,int contextVersion,Space space,Product product,
                List<Competitor> competitors,List<String> attachmentAssetIds,ConversationMemory conversationMemory,
                TaskWorkspace taskWorkspace,List<String> loadedToolGroups,ContextBudget contextBudget,
                TaskPreparationConstraints taskPreparationConstraints,JsonNode geoInsightContext,ProtocolFeatures protocolFeatures,
                RunResourceDtos.Selection resourceSelection,RunResourceDtos.ReadResponse lastReadContext,
                RunResourceDtos.SearchResponse pendingResourceContext,RunResourceDtos.ReadResponse lastReportReadContext,
                AgentPlatformDtos.AgentIdentity agentIdentity,
                com.guanghe.backend.agentplatform.MonitorExecutionDtos.MonitorActionContext monitorActionContext,
                KnowledgeDtos.KnowledgeContext knowledgeContext) {
            this(runId,conversationId,contextVersion,space,product,competitors,attachmentAssetIds,conversationMemory,
                    taskWorkspace,loadedToolGroups,contextBudget,taskPreparationConstraints,geoInsightContext,protocolFeatures,
                    resourceSelection,lastReadContext,pendingResourceContext,lastReportReadContext,agentIdentity,
                    monitorActionContext,knowledgeContext,null);
        }
        public RunContext(String runId,String conversationId,int contextVersion,Space space,Product product,
                List<Competitor> competitors,List<String> attachmentAssetIds,ConversationMemory conversationMemory,
                TaskWorkspace taskWorkspace,List<String> loadedToolGroups,ContextBudget contextBudget,
                TaskPreparationConstraints taskPreparationConstraints,JsonNode geoInsightContext,ProtocolFeatures protocolFeatures,
                RunResourceDtos.Selection resourceSelection,RunResourceDtos.ReadResponse lastReadContext,
                RunResourceDtos.SearchResponse pendingResourceContext,RunResourceDtos.ReadResponse lastReportReadContext,
                AgentPlatformDtos.AgentIdentity agentIdentity,
                com.guanghe.backend.agentplatform.MonitorExecutionDtos.MonitorActionContext monitorActionContext) {
            this(runId,conversationId,contextVersion,space,product,competitors,attachmentAssetIds,conversationMemory,
                    taskWorkspace,loadedToolGroups,contextBudget,taskPreparationConstraints,geoInsightContext,protocolFeatures,
                    resourceSelection,lastReadContext,pendingResourceContext,lastReportReadContext,agentIdentity,monitorActionContext,null);
        }
        public RunContext withKnowledge(KnowledgeDtos.KnowledgeContext value) {
            return new RunContext(runId,conversationId,contextVersion,space,product,competitors,attachmentAssetIds,
                    conversationMemory,taskWorkspace,loadedToolGroups,contextBudget,taskPreparationConstraints,
                    geoInsightContext,protocolFeatures,resourceSelection,lastReadContext,pendingResourceContext,
                    lastReportReadContext,agentIdentity,monitorActionContext,value,articlePreparationMode);
        }
        /** Model-only projection: UI message storage is untouched; task/report identifiers remain usable. */
        public RunContext enforceMemoryBoundary(String currentUserMessageId) {
            var fence=knowledgeContext==null||knowledgeContext.memory()==null?null:knowledgeContext.memory().historyCutoffAt();
            if(fence==null||conversationMemory==null) return this;
            var messages=conversationMemory.messages().stream().filter(m->currentUserMessageId.equals(m.messageId())
                    ||m.createdAt()!=null&&!m.createdAt().isBefore(fence)).toList();
            var cleanedMemory=new ConversationMemory(messages,null,conversationMemory.lastMessageSequence(),0,conversationMemory.contextSource());
            var workspace=taskWorkspace==null?null:new TaskWorkspace("","GENERAL",taskWorkspace.activeScope(),
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),List.of(),null,
                    taskWorkspace.activeDraftId(),taskWorkspace.activeTaskId(),taskWorkspace.referenceAssetIds(),
                    taskWorkspace.runningTasks(),taskWorkspace.contextVersion());
            return new RunContext(runId,conversationId,contextVersion,space,product,competitors,attachmentAssetIds,
                    cleanedMemory,workspace,loadedToolGroups,contextBudget,taskPreparationConstraints,geoInsightContext,
                    protocolFeatures,resourceSelection,lastReadContext,pendingResourceContext,lastReportReadContext,
                    agentIdentity,monitorActionContext,knowledgeContext,articlePreparationMode);
        }
        public RunContext(String runId,String conversationId,int contextVersion,Space space,Product product,
                List<Competitor> competitors,List<String> attachmentAssetIds,ConversationMemory conversationMemory,
                TaskWorkspace taskWorkspace,List<String> loadedToolGroups,ContextBudget contextBudget,
                TaskPreparationConstraints taskPreparationConstraints,JsonNode geoInsightContext,ProtocolFeatures protocolFeatures,
                RunResourceDtos.Selection resourceSelection,RunResourceDtos.ReadResponse lastReadContext,
                RunResourceDtos.SearchResponse pendingResourceContext,RunResourceDtos.ReadResponse lastReportReadContext,
                AgentPlatformDtos.AgentIdentity agentIdentity) {
            this(runId,conversationId,contextVersion,space,product,competitors,attachmentAssetIds,conversationMemory,
                    taskWorkspace,loadedToolGroups,contextBudget,taskPreparationConstraints,geoInsightContext,protocolFeatures,
                    resourceSelection,lastReadContext,pendingResourceContext,lastReportReadContext,agentIdentity,null);
        }
        public RunContext(String runId, String conversationId, int contextVersion, Space space,
                Product product, List<Competitor> competitors, List<String> attachmentAssetIds,
                ConversationMemory conversationMemory, TaskWorkspace taskWorkspace, List<String> loadedToolGroups,
                ContextBudget contextBudget, TaskPreparationConstraints taskPreparationConstraints,
                JsonNode geoInsightContext, ProtocolFeatures protocolFeatures,
                RunResourceDtos.Selection resourceSelection, RunResourceDtos.ReadResponse lastReadContext,
                RunResourceDtos.SearchResponse pendingResourceContext, RunResourceDtos.ReadResponse lastReportReadContext) {
            this(runId, conversationId, contextVersion, space, product, competitors, attachmentAssetIds,
                    conversationMemory, taskWorkspace, loadedToolGroups, contextBudget,
                    taskPreparationConstraints, geoInsightContext, protocolFeatures, resourceSelection,
                    lastReadContext, pendingResourceContext, lastReportReadContext, null);
        }
        public RunContext(String runId, String conversationId, int contextVersion, Space space,
                Product product, List<Competitor> competitors, List<String> attachmentAssetIds,
                ConversationMemory conversationMemory, TaskWorkspace taskWorkspace, List<String> loadedToolGroups,
                ContextBudget contextBudget, TaskPreparationConstraints taskPreparationConstraints,
                JsonNode geoInsightContext, ProtocolFeatures protocolFeatures) {
            this(runId, conversationId, contextVersion, space, product, competitors, attachmentAssetIds,
                    conversationMemory, taskWorkspace, loadedToolGroups, contextBudget,
                    taskPreparationConstraints, geoInsightContext, protocolFeatures, null, null, null, null);
        }
        public RunContext(String runId, String conversationId, int contextVersion, Space space,
                Product product, List<Competitor> competitors, List<String> attachmentAssetIds,
                ConversationMemory conversationMemory, TaskWorkspace taskWorkspace, List<String> loadedToolGroups,
                ContextBudget contextBudget, TaskPreparationConstraints taskPreparationConstraints,
                JsonNode geoInsightContext) {
            this(runId, conversationId, contextVersion, space, product, competitors, attachmentAssetIds,
                    conversationMemory, taskWorkspace, loadedToolGroups, contextBudget,
                    taskPreparationConstraints, geoInsightContext, null);
        }
        public RunContext(String runId, String conversationId, int contextVersion, Space space,
                Product product, List<Competitor> competitors, List<String> attachmentAssetIds,
                ConversationMemory conversationMemory, TaskWorkspace taskWorkspace, List<String> loadedToolGroups,
                ContextBudget contextBudget, TaskPreparationConstraints taskPreparationConstraints) {
            this(runId, conversationId, contextVersion, space, product, competitors, attachmentAssetIds,
                    conversationMemory, taskWorkspace, loadedToolGroups, contextBudget, taskPreparationConstraints, null);
        }
        public RunContext(
                String runId,
                String conversationId,
                int contextVersion,
                Space space,
                Product product,
                List<Competitor> competitors,
                List<String> attachmentAssetIds,
                ConversationMemory conversationMemory,
                TaskWorkspace taskWorkspace,
                List<String> loadedToolGroups,
                ContextBudget contextBudget
        ) {
            this(runId, conversationId, contextVersion, space, product, competitors, attachmentAssetIds,
                    conversationMemory, taskWorkspace, loadedToolGroups, contextBudget,
                    TaskPreparationConstraints.unavailable());
        }

        public RunContext(
                String runId,
                String conversationId,
                int contextVersion,
                Space space,
                Product product,
                List<Competitor> competitors,
                List<String> attachmentAssetIds
        ) {
            this(runId, conversationId, contextVersion, space, product, competitors, attachmentAssetIds,
                    new ConversationMemory(List.of(), null, 1, 0, "POSTGRESQL"),
                    new TaskWorkspace("", "GENERAL", new ActiveScope(null, null, List.of()),
                            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                            List.of(), null, null, null, List.of(), List.of(), 1),
                    List.of("TASK_PREPARATION"),
                    new ContextBudget(128_000, 16_000, 8_000, 4_000, 100_000, 0,
                            false, "TOKEN_BUDGET_REPLY_CHAIN_THEN_RECENCY"),
                    TaskPreparationConstraints.unavailable());
        }

        public RunContext {
            competitors = List.copyOf(competitors);
            attachmentAssetIds = List.copyOf(attachmentAssetIds);
            loadedToolGroups = List.copyOf(loadedToolGroups);
            if (taskPreparationConstraints == null) {
                taskPreparationConstraints = TaskPreparationConstraints.unavailable();
            }
        }
    }

    public record ProtocolFeatures(boolean businessOutcome, boolean resourceLookup) { }

    public record TaskPreparationConstraints(GeoPreparationConstraints geo) {
        public TaskPreparationConstraints {
            if (geo == null) geo = GeoPreparationConstraints.unavailable();
        }

        public static TaskPreparationConstraints unavailable() {
            return new TaskPreparationConstraints(GeoPreparationConstraints.unavailable());
        }
    }

    public record GeoPreparationConstraints(
            boolean enabled,
            int minQuestions,
            int maxQuestions,
            List<String> platformCodes,
            int maxProviderCalls
    ) {
        public GeoPreparationConstraints {
            platformCodes = List.copyOf(platformCodes);
        }

        public static GeoPreparationConstraints unavailable() {
            return new GeoPreparationConstraints(false, 3, 0, List.of(), 0);
        }
    }

    public record ConversationMemory(
            List<MemoryMessage> messages,
            ConversationSummary summary,
            long lastMessageSequence,
            long summaryThroughSequence,
            String contextSource
    ) {
        public ConversationMemory {
            messages = List.copyOf(messages);
        }
    }

    public record MemoryMessage(
            String messageId,
            long sequence,
            String role,
            JsonNode content,
            OffsetDateTime createdAt,
            String replyToMessageId
    ) {
    }

    public record ConversationSummary(String text, int version) {
    }

    public record TaskWorkspace(
            String currentGoal,
            String currentIntent,
            ActiveScope activeScope,
            JsonNode confirmedConstraints,
            List<String> unresolvedQuestions,
            PendingInteraction pendingInteraction,
            String activeDraftId,
            String activeTaskId,
            List<String> referenceAssetIds,
            List<RunningTask> runningTasks,
            int contextVersion
    ) {
        public TaskWorkspace {
            unresolvedQuestions = List.copyOf(unresolvedQuestions);
            referenceAssetIds = List.copyOf(referenceAssetIds);
            runningTasks = List.copyOf(runningTasks);
        }
    }

    public record ActiveScope(
            String spaceId,
            String productId,
            List<String> competitorIds
    ) {
        public ActiveScope {
            competitorIds = List.copyOf(competitorIds);
        }
    }

    public record PendingInteraction(
            String messageId,
            String interactionType,
            String intent,
            String question,
            List<String> missingFields,
            List<String> choices
    ) {
        public PendingInteraction {
            missingFields = List.copyOf(missingFields);
            choices = List.copyOf(choices);
        }
    }

    public record RunningTask(
            String taskId,
            String taskType,
            String status,
            String stageCode
    ) {
    }

    public record ContextBudget(
            int targetInputTokens,
            int reservedOutputTokens,
            int reservedToolTokens,
            int reservedSafetyTokens,
            int availableHistoryTokens,
            int estimatedHistoryTokens,
            boolean truncated,
            String selectionStrategy
    ) {
    }

    public record Space(
            String id,
            String name,
            Brand brand,
            String logoAssetId
    ) {
    }

    public record Brand(
            String name,
            List<String> aliases,
            String description,
            List<String> tone,
            List<String> audience,
            List<String> sellingPoints,
            List<String> prohibitedClaims
    ) {
        public Brand {
            aliases = List.copyOf(aliases);
            tone = List.copyOf(tone);
            audience = List.copyOf(audience);
            sellingPoints = List.copyOf(sellingPoints);
            prohibitedClaims = List.copyOf(prohibitedClaims);
        }
    }

    public record Product(
            String id,
            String spaceId,
            String name,
            List<String> aliases,
            String category,
            String modelSpec,
            List<String> sellingPoints,
            Map<String, String> parameters,
            List<String> audience,
            List<String> usageScenarios,
            List<String> prohibitedClaims,
            String primaryAssetId,
            List<String> identityAssetIds
    ) {
        public Product(String id, String spaceId, String name, List<String> aliases,
                       String category, String modelSpec, List<String> sellingPoints,
                       Map<String, String> parameters, List<String> audience,
                       List<String> usageScenarios, List<String> prohibitedClaims,
                       String primaryAssetId) {
            this(id, spaceId, name, aliases, category, modelSpec, sellingPoints, parameters,
                    audience, usageScenarios, prohibitedClaims, primaryAssetId, List.of());
        }

        public Product {
            aliases = List.copyOf(aliases);
            sellingPoints = List.copyOf(sellingPoints);
            parameters = Map.copyOf(parameters);
            audience = List.copyOf(audience);
            usageScenarios = List.copyOf(usageScenarios);
            prohibitedClaims = List.copyOf(prohibitedClaims);
            identityAssetIds = List.copyOf(identityAssetIds);
        }
    }

    public record Competitor(
            String id,
            String spaceId,
            String name,
            List<String> aliases,
            CompetitorType type,
            String description,
            CompetitorStatus status
    ) {
        public Competitor {
            aliases = List.copyOf(aliases);
        }
    }
}
