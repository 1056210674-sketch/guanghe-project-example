package com.guanghe.backend.conversation.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.conversation.ConversationMessageRecord;
import com.guanghe.backend.conversation.ConversationProperties;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.geo.GeoChatContextFactory;
import com.guanghe.backend.conversation.resource.RunResourceDtos;
import com.guanghe.backend.conversation.resource.RunResourceService;
import com.guanghe.backend.agentplatform.AgentPlatformService;
import org.springframework.beans.factory.ObjectProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RunContextSnapshotFactory {

    static final int DATABASE_CONTEXT_SNAPSHOT_MAX_BYTES = 262_144;
    static final int JSONB_TEXT_SAFETY_MARGIN_BYTES = 4_096;

    private final AuthorizedCatalogContextFactory catalogContextFactory;
    private final ObjectMapper objectMapper;
    private final ConversationWorkspaceService workspaceService;
    private final ObservabilitySupport observability;
    private final PaidTaskProperties paidTaskProperties;
    private final GeoChatContextFactory geoContexts;
    private ConversationProperties conversationProperties = new ConversationProperties();
    private RunResourceService resourceService;
    private AgentPlatformService agentPlatformService;
    private com.guanghe.backend.knowledge.KnowledgeService knowledgeService;
    private com.guanghe.backend.agentplatform.AgentRuntimeCatalog runtimeCatalog =
            new com.guanghe.backend.agentplatform.AgentRuntimeCatalog();

    @Autowired
    void setRuntimeCatalog(com.guanghe.backend.agentplatform.AgentRuntimeCatalog value) { runtimeCatalog=value; }

    @Autowired(required = false)
    void setKnowledgeService(com.guanghe.backend.knowledge.KnowledgeService value) { knowledgeService = value; }

    @Autowired
    void setConversationProperties(ConversationProperties value) { conversationProperties = value; }

    @Autowired(required = false)
    void setResourceService(RunResourceService value) { resourceService = value; }

    @Autowired(required = false)
    void setAgentPlatformService(AgentPlatformService value) { agentPlatformService = value; }

    @Autowired
    public RunContextSnapshotFactory(
            AuthorizedCatalogContextFactory catalogContextFactory,
            ObjectMapper objectMapper,
            ConversationWorkspaceService workspaceService,
            ObservabilitySupport observability,
            PaidTaskProperties paidTaskProperties,
            ObjectProvider<GeoChatContextFactory> geoContexts
    ) {
        this.catalogContextFactory = catalogContextFactory;
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.observability = observability;
        this.paidTaskProperties = paidTaskProperties;
        this.geoContexts = geoContexts.getIfAvailable();
    }

    public RunContextSnapshotFactory(AuthorizedCatalogContextFactory catalogContextFactory,
            ObjectMapper objectMapper, ConversationWorkspaceService workspaceService,
            ObservabilitySupport observability, PaidTaskProperties paidTaskProperties) {
        this.catalogContextFactory = catalogContextFactory;
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.observability = observability;
        this.paidTaskProperties = paidTaskProperties;
        this.geoContexts = null;
    }

    public RunContextSnapshotFactory(
            AuthorizedCatalogContextFactory catalogContextFactory,
            ObjectMapper objectMapper,
            ConversationWorkspaceService workspaceService
    ) {
        this(catalogContextFactory, objectMapper, workspaceService, ObservabilitySupport.noop(),
                new PaidTaskProperties());
    }

    public InternalRunContextDtos.RunContext create(
            UUID runId,
            long conversationId,
            long userId,
            Long selectedSpaceId,
            Long selectedProductId,
            ConversationMessageRecord currentMessage
    ) {
        return create(runId, conversationId, userId, selectedSpaceId, selectedProductId,
                List.of(), currentMessage);
    }

    public InternalRunContextDtos.RunContext create(
            UUID runId,
            long conversationId,
            long userId,
            Long selectedSpaceId,
            Long selectedProductId,
            List<String> attachmentAssetIds,
            ConversationMessageRecord currentMessage
    ) {
        return observability.observe("java.context.snapshot", "ASSEMBLE", span -> {
            span.tag("runIdRef", observability.reference(runId));
            span.tag("conversationIdRef", observability.reference(conversationId));
            span.tag("userIdHash", observability.reference(userId));
        }, () -> createSnapshot(runId, conversationId, userId, selectedSpaceId, selectedProductId,
                attachmentAssetIds, currentMessage, null, null, selectedSpaceId, selectedProductId));
    }

    public InternalRunContextDtos.RunContext create(UUID runId, long conversationId, long userId,
            Long selectedSpaceId, Long selectedProductId, ConversationMessageRecord currentMessage, long geoReportId) {
        return observability.observe("java.context.snapshot", "ASSEMBLE", span -> {
            span.tag("runIdRef",observability.reference(runId));
            span.tag("conversationIdRef",observability.reference(conversationId));
            span.tag("userIdHash",observability.reference(userId));
            span.tag("geoReportIdRef",observability.reference(geoReportId));
        }, () -> createSnapshot(runId, conversationId, userId, selectedSpaceId, selectedProductId,
                List.of(), currentMessage, geoReportId, null, selectedSpaceId, selectedProductId));
    }

    public InternalRunContextDtos.RunContext createWithResourceSelection(UUID runId, long conversationId,
            long userId, Long selectedSpaceId, Long selectedProductId, ConversationMessageRecord currentMessage,
            RunResourceDtos.Selection selection, Long persistedSpaceId, Long persistedProductId) {
        return observability.observe("java.context.snapshot", "ASSEMBLE", span -> {
            span.tag("runIdRef", observability.reference(runId));
            span.tag("conversationIdRef", observability.reference(conversationId));
        }, () -> createSnapshot(runId, conversationId, userId, selectedSpaceId, selectedProductId,
                List.of(), currentMessage, null, selection, persistedSpaceId, persistedProductId));
    }

    private InternalRunContextDtos.RunContext createSnapshot(
            UUID runId,
            long conversationId,
            long userId,
            Long selectedSpaceId,
            Long selectedProductId,
            List<String> attachmentAssetIds,
            ConversationMessageRecord currentMessage,
            Long geoReportId,
            RunResourceDtos.Selection selection,
            Long persistedSpaceId,
            Long persistedProductId
    ) {
        if (selectedProductId != null && selectedSpaceId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        var identity = agentPlatformService == null ? null : agentPlatformService.identityOf(userId, conversationId);
        if (identity != null) identity = runtimeCatalog.freeze(identity);
        if (identity != null && identity.projectId() != null
                && ((selectedSpaceId != null && !identity.projectId().equals(selectedSpaceId.toString()))
                    || (persistedSpaceId != null && !identity.projectId().equals(persistedSpaceId.toString())))) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String articleMode = articlePreparationMode(currentMessage, identity);
        if (articleMode != null && (!attachmentAssetIds.isEmpty() || selection != null
                || !identity.projectId().equals(selectedSpaceId == null ? null : selectedSpaceId.toString()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        boolean summaryOnly = identity != null && "summary".equals(runtimeCatalog.require(identity).runtimeKind());
        boolean resourcesReadable = identity == null || identity.capabilities().contains("resources.read");
        boolean brandContext = resourcesReadable && (identity == null || "geo-brand".equals(runtimeCatalog.require(identity).contextPolicy()));
        boolean geoReadable = identity == null || identity.capabilities().contains("geo.read");
        if(!resourcesReadable && (!attachmentAssetIds.isEmpty() || geoReportId!=null || selection!=null))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        Long projectId = identity == null || identity.projectId() == null ? null : Long.valueOf(identity.projectId());
        if(projectId==null && brandContext && attachmentAssetIds.isEmpty()) projectId=selectedSpaceId;
        String query;
        try { query = objectMapper.readTree(currentMessage.contentJson()).path("text").asText(""); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid user message", ex); }
        var knowledge = knowledgeService == null || !resourcesReadable ? null : knowledgeService.context(userId, projectId, query);
        if (knowledge != null && identity != null && !identity.capabilities().contains("memory.read"))
            knowledge = knowledge.withoutMemory();
        boolean attachmentSource = !attachmentAssetIds.isEmpty();
        // The validated attachment storage space selects the same-scope pending draft only.
        // It must never smuggle sidebar brand/product facts into an attachment-only model prompt.
        AuthorizedCatalogContextFactory.Context catalog = catalogContextFactory.create(
                userId, attachmentSource || !brandContext ? null : selectedSpaceId,
                attachmentSource || !brandContext ? null : selectedProductId
        );
        InternalRunContextDtos.Space space = catalog.space();
        InternalRunContextDtos.Product product = catalog.product();
        List<InternalRunContextDtos.Competitor> competitors = catalog.competitors();
        if (geoReportId != null && geoContexts == null) {
            throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE);
        }
        var geoContext = geoContexts == null || !brandContext || !geoReadable ? null : geoContexts.create(userId,
                attachmentSource ? null : selectedSpaceId, attachmentSource ? null : selectedProductId, geoReportId);
        if (geoReportId != null && (geoContext == null || !"READY".equals(geoContext.path("status").asText())
                || !geoReportId.toString().equals(geoContext.path("report").path("reportId").asText()))) {
            // A failed explicit read is not authority to fall back to another/latest report.
            throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE);
        }
        boolean resourcesEnabled = !summaryOnly && !attachmentSource && conversationProperties.isResourceLookupEnabled()
                && resourceService != null && (identity == null || runtimeCatalog.permits(identity,"context.resources.read"));
        // An explicit attachment is the reference for this turn, not a suggestion to revive an
        // older resource observation or a pending choice from the preceding conversation.
        RunResourceDtos.ReadResponse lastRead = resourcesEnabled && geoReportId == null
                ? resourceService.lastReadContext(userId, conversationId, persistedSpaceId, persistedProductId) : null;
        if (lastRead != null && !geoReadable && ("GEO_TOPIC".equals(lastRead.kind())
                || "LATEST_GEO_REPORT".equals(lastRead.purpose()) || "GEO_ANSWER_EVIDENCE".equals(lastRead.purpose()))) lastRead=null;
        RunResourceDtos.ReadResponse lastReport = resourcesEnabled && geoReadable && geoReportId == null
                ? resourceService.lastReportReadContext(userId, conversationId, persistedSpaceId, persistedProductId) : null;
        if (lastRead != null && lastReport != null && lastRead.observationId().equals(lastReport.observationId())) {
            lastReport = null;
        }
        RunResourceDtos.SearchResponse pendingResource = resourcesEnabled && geoReportId == null
                ? resourceService.pendingSearch(userId, conversationId, persistedSpaceId, persistedProductId) : null;
        if (pendingResource != null && !geoReadable && "GEO_TOPIC".equals(pendingResource.kind())) pendingResource=null;
        ConversationWorkspaceService.WorkspaceContext workspace = workspaceService.assemble(
                userId, conversationId, currentMessage, selectedSpaceId, attachmentSource ? null : selectedProductId,
                competitors.stream().map(InternalRunContextDtos.Competitor::id).toList(),
                estimateTokens(java.util.Arrays.asList(space, product, competitors, geoContext,
                        lastRead, lastReport, pendingResource, selection, knowledge))
        );
        InternalRunContextDtos.TaskWorkspace state = workspace.taskWorkspace();
        InternalRunContextDtos.PendingInteraction pendingInteraction = state.pendingInteraction();
        boolean resourceInteraction = pendingInteraction != null
                && "RESOURCE_SELECTION".equals(pendingInteraction.interactionType());
        if (resourceInteraction) {
            // Resource cards travel as typed pendingResourceContext plus saved memory, not through
            // the legacy clarification shape (which lacks stable candidate references).
            pendingInteraction = null;
        }
        InternalRunContextDtos.TaskWorkspace modelWorkspace = new InternalRunContextDtos.TaskWorkspace(
                state.currentGoal(), state.currentIntent(),
                new InternalRunContextDtos.ActiveScope(space == null ? null : space.id(),
                        product == null ? null : product.id(), competitors.stream()
                        .map(InternalRunContextDtos.Competitor::id).toList()),
                state.confirmedConstraints(), resourceInteraction ? List.of() : state.unresolvedQuestions(), pendingInteraction,
                state.activeDraftId(), state.activeTaskId(), attachmentAssetIds, state.runningTasks(),
                state.contextVersion());
        InternalRunContextDtos.RunContext snapshot = new InternalRunContextDtos.RunContext(
                runId.toString(), Long.toString(conversationId), 1,
                space, product, competitors, attachmentAssetIds, workspace.conversationMemory(),
                modelWorkspace, summaryOnly ? List.of() : workspace.loadedToolGroups(), workspace.contextBudget(),
                summaryOnly ? InternalRunContextDtos.TaskPreparationConstraints.unavailable() : taskPreparationConstraints(),
                geoContext, summaryOnly ? null : protocolFeatures(),
                resourcesEnabled ? selection : null, lastRead, pendingResource, lastReport, identity,
                monitorActionContext(currentMessage), knowledge, articleMode
        );
        return fitPersistedSnapshot(snapshot.enforceMemoryBoundary(Long.toString(currentMessage.id())), currentMessage.id());
    }

    private InternalRunContextDtos.RunContext fitPersistedSnapshot(
            InternalRunContextDtos.RunContext snapshot,
            long currentMessageId
    ) {
        List<InternalRunContextDtos.MemoryMessage> messages = new ArrayList<>(
                snapshot.conversationMemory().messages()
        );
        String currentId = Long.toString(currentMessageId);
        if (messages.stream().noneMatch(message -> currentId.equals(message.messageId()))) {
            throw new IllegalStateException("Current message is missing from the Run context snapshot");
        }
        Set<String> replyChain = replyChain(messages, currentId);
        InternalRunContextDtos.RunContext candidate = snapshot;
        while (projectedJsonbTextBytes(candidate) > DATABASE_CONTEXT_SNAPSHOT_MAX_BYTES) {
            int removable = removableMessage(messages, replyChain, currentId);
            if (removable < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            messages.remove(removable);
            candidate = withMessages(snapshot, messages);
        }
        return candidate;
    }

    private Set<String> replyChain(List<InternalRunContextDtos.MemoryMessage> messages, String currentMessageId) {
        Map<String, InternalRunContextDtos.MemoryMessage> byId = new HashMap<>();
        messages.forEach(message -> byId.put(message.messageId(), message));
        Set<String> result = new HashSet<>();
        String cursor = currentMessageId;
        while (cursor != null && result.add(cursor)) {
            InternalRunContextDtos.MemoryMessage message = byId.get(cursor);
            cursor = message == null ? null : message.replyToMessageId();
        }
        return result;
    }

    private int removableMessage(List<InternalRunContextDtos.MemoryMessage> messages,
                                 Set<String> replyChain, String currentMessageId) {
        for (int index = 0; index < messages.size(); index++) {
            if (!replyChain.contains(messages.get(index).messageId())) {
                return index;
            }
        }
        for (int index = 0; index < messages.size(); index++) {
            if (!currentMessageId.equals(messages.get(index).messageId())) {
                return index;
            }
        }
        return -1;
    }

    private InternalRunContextDtos.RunContext withMessages(
            InternalRunContextDtos.RunContext snapshot,
            List<InternalRunContextDtos.MemoryMessage> messages
    ) {
        InternalRunContextDtos.ConversationMemory memory = snapshot.conversationMemory();
        InternalRunContextDtos.ContextBudget budget = snapshot.contextBudget();
        return new InternalRunContextDtos.RunContext(
                snapshot.runId(), snapshot.conversationId(), snapshot.contextVersion(), snapshot.space(),
                snapshot.product(), snapshot.competitors(), snapshot.attachmentAssetIds(),
                new InternalRunContextDtos.ConversationMemory(
                        List.copyOf(messages), memory.summary(), memory.lastMessageSequence(),
                        memory.summaryThroughSequence(), memory.contextSource()
                ),
                snapshot.taskWorkspace(), snapshot.loadedToolGroups(),
                new InternalRunContextDtos.ContextBudget(
                        budget.targetInputTokens(), budget.reservedOutputTokens(), budget.reservedToolTokens(),
                        budget.reservedSafetyTokens(), budget.availableHistoryTokens(), estimateTokens(messages),
                        true, budget.selectionStrategy()
                ), snapshot.taskPreparationConstraints(), snapshot.geoInsightContext(), snapshot.protocolFeatures(),
                snapshot.resourceSelection(), snapshot.lastReadContext(), snapshot.pendingResourceContext(),
                snapshot.lastReportReadContext(), snapshot.agentIdentity(), snapshot.monitorActionContext(), snapshot.knowledgeContext(),
                snapshot.articlePreparationMode()
        );
    }

    private com.guanghe.backend.agentplatform.MonitorExecutionDtos.MonitorActionContext monitorActionContext(
            ConversationMessageRecord currentMessage) {
        try {
            var content=objectMapper.readTree(currentMessage.contentJson());
            if(!"USER".equals(currentMessage.role())||!content.hasNonNull("executionIntent")
                    ||!content.hasNonNull("monitorActionContext")) return null;
            return objectMapper.treeToValue(content.path("monitorActionContext"),
                    com.guanghe.backend.agentplatform.MonitorExecutionDtos.MonitorActionContext.class);
        } catch(JsonProcessingException ex) { throw new IllegalStateException("Invalid server monitor context",ex); }
    }

    private String articlePreparationMode(ConversationMessageRecord currentMessage,
            com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity identity) {
        try {
            var content = objectMapper.readTree(currentMessage.contentJson());
            if (!"USER".equals(currentMessage.role()) || !content.hasNonNull("articlePreparationMode")) return null;
            if (!"ARTICLE_FULL_V1".equals(content.path("articlePreparationMode").asText())
                    || identity == null || !runtimeCatalog.require(identity).explicitActions().contains("ARTICLE_FULL_V1") || identity.installationId() == null
                    || identity.projectId() == null || !identity.capabilities().contains("article.generate")
                    || content.hasNonNull("executionIntent") || content.hasNonNull("resourceSelection")) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return "ARTICLE_FULL_V1";
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid server article mode", exception); }
    }

    private InternalRunContextDtos.ProtocolFeatures protocolFeatures() {
        if (!conversationProperties.isBusinessOutcomeEnabled() && !conversationProperties.isResourceLookupEnabled()) {
            return null;
        }
        return new InternalRunContextDtos.ProtocolFeatures(conversationProperties.isBusinessOutcomeEnabled(),
                conversationProperties.isResourceLookupEnabled());
    }

    private InternalRunContextDtos.TaskPreparationConstraints taskPreparationConstraints() {
        int maxQuestions = paidTaskProperties.getGeoMaxDiscoveryQuestions();
        boolean enabled = paidTaskProperties.isGeoExecutionEnabled() && maxQuestions >= 3;
        List<String> platforms = enabled
                ? paidTaskProperties.getGeoConfiguredPlatforms().stream().sorted().toList()
                : List.of();
        return new InternalRunContextDtos.TaskPreparationConstraints(
                new InternalRunContextDtos.GeoPreparationConstraints(
                        enabled,
                        3,
                        enabled ? maxQuestions : 0,
                        platforms,
                        enabled ? paidTaskProperties.getGeoMaxProviderCalls() : 0
                )
        );
    }

    int projectedJsonbTextBytes(InternalRunContextDtos.RunContext snapshot) {
        try {
            byte[] compact = objectMapper.writeValueAsBytes(snapshot);
            int jsonbSpacing = jsonbTextSpacing(objectMapper.valueToTree(snapshot));
            return Math.addExact(Math.addExact(compact.length, jsonbSpacing), JSONB_TEXT_SAFETY_MARGIN_BYTES);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to size Run context snapshot", exception);
        }
    }

    private int jsonbTextSpacing(com.fasterxml.jackson.databind.JsonNode value) {
        int spacing = 0;
        if (value.isObject()) {
            spacing = value.size() + Math.max(0, value.size() - 1);
            for (var field : value.properties()) {
                spacing = Math.addExact(spacing, jsonbTextSpacing(field.getValue()));
            }
        } else if (value.isArray()) {
            spacing = Math.max(0, value.size() - 1);
            for (var item : value) {
                spacing = Math.addExact(spacing, jsonbTextSpacing(item));
            }
        }
        return spacing;
    }

    private int estimateTokens(Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            String text = objectMapper.writeValueAsString(value);
            return Math.max(text.codePointCount(0, text.length()), (bytes.length + 3) / 4);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to estimate authorized catalog context", exception);
        }
    }

}
