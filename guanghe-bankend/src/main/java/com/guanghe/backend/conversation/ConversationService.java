package com.guanghe.backend.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.agentplatform.AgentPlatformService;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.web.TraceIdHolder;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.ConversationWorkspaceService;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.conversation.context.RunContextSnapshotFactory;
import com.guanghe.backend.conversation.context.TaskWorkspaceStateSource;
import com.guanghe.backend.conversation.resource.RunResourceDtos;
import com.guanghe.backend.conversation.resource.RunResourceService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DEFAULT_TITLE = "新会话";
    private static final int AUTOMATIC_TITLE_MAX_CODE_POINTS = 30;

    private final ConversationMapper mapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final ConversationProperties properties;
    private final RunContextSnapshotFactory contextSnapshotFactory;
    private com.guanghe.backend.agentplatform.AgentRuntimeCatalog runtimeCatalog =
            new com.guanghe.backend.agentplatform.AgentRuntimeCatalog();

    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeCatalog(com.guanghe.backend.agentplatform.AgentRuntimeCatalog value) { runtimeCatalog=value; }
    private final AuthorizedCatalogContextFactory catalogContextFactory;
    private ConversationWorkspaceService workspaceService;
    private TaskWorkspaceStateSource taskWorkspaceStateSource;
    private AssetMapper assetMapper;
    private AssetIngestionProperties assetProperties;
    private RunResourceService resourceService;
    private AgentPlatformService agentPlatformService;
    private com.guanghe.backend.knowledge.KnowledgeService knowledgeService;

    @Autowired(required = false)
    void setKnowledgeService(com.guanghe.backend.knowledge.KnowledgeService value) { knowledgeService = value; }
    private com.guanghe.backend.agentplatform.MonitorExecutionService monitorExecution;

    @Autowired(required=false)
    void setMonitorExecution(com.guanghe.backend.agentplatform.MonitorExecutionService value) { monitorExecution=value; }
    private Clock clock = Clock.systemUTC();

    public ConversationService(
            ConversationMapper mapper,
            SnowflakeIdGenerator idGenerator,
            ObjectMapper objectMapper,
            ConversationProperties properties,
            RunContextSnapshotFactory contextSnapshotFactory,
            AuthorizedCatalogContextFactory catalogContextFactory
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.contextSnapshotFactory = contextSnapshotFactory;
        this.catalogContextFactory = catalogContextFactory;
    }

    @Autowired(required = false)
    void setWorkspaceService(ConversationWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Autowired(required = false)
    void setTaskWorkspaceStateSource(TaskWorkspaceStateSource taskWorkspaceStateSource) {
        this.taskWorkspaceStateSource = taskWorkspaceStateSource;
    }

    @Autowired(required = false)
    void setAssetMapper(AssetMapper assetMapper) {
        this.assetMapper = assetMapper;
    }

    @Autowired(required = false)
    void setAssetProperties(AssetIngestionProperties assetProperties) {
        this.assetProperties = assetProperties;
    }

    @Autowired(required = false)
    void setResourceService(RunResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @Autowired(required = false)
    void setAgentPlatformService(AgentPlatformService value) { this.agentPlatformService = value; }

    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public PageResponse<ConversationDtos.Response> list(AuthenticatedAccount account, int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        long total = mapper.countByUserId(userId);
        List<ConversationDtos.Response> items = mapper.findPage(userId, offset(page, size), size)
                .stream().map(this::toResponse).toList();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public ConversationDtos.Response get(AuthenticatedAccount account, long conversationId) {
        long userId = CatalogAccess.requireUser(account);
        ConversationRecord conversation = requireConversation(userId, conversationId);
        String activeConfirmationDraftId = taskWorkspaceStateSource == null ? null
                : taskWorkspaceStateSource.activeConfirmationDraftId(userId, conversationId);
        return toResponse(conversation, activeConfirmationDraftId);
    }

    @Transactional
    public ConversationDtos.Response create(
            AuthenticatedAccount account,
            ConversationDtos.CreateRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        String title = request.title() == null ? DEFAULT_TITLE : requiredTitle(request.title());
        OffsetDateTime now = now();
        ConversationRecord conversation = new ConversationRecord(
                idGenerator.nextId(), userId, title, null, null, 1, null, now, now
        );
        mapper.insertConversation(conversation);
        if (agentPlatformService != null) {
            agentPlatformService.bindConversation(userId, conversation.id(),
                    parseOptionalId(request.installationId()), parseOptionalId(request.projectId()));
        } else if (request.installationId() != null || request.projectId() != null) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        return toResponse(conversation);
    }

    @Transactional
    public ConversationDtos.Response update(
            AuthenticatedAccount account,
            long conversationId,
            ConversationDtos.UpdateRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        ConversationRecord current = requireConversation(userId, conversationId);
        if (current.version() != request.version()) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        String title = requiredTitle(request.title());
        OffsetDateTime now = now();
        if (mapper.updateConversation(conversationId, userId, current.version(), title, now) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        return toResponse(new ConversationRecord(
                current.id(), current.userId(), title, current.selectedSpaceId(), current.selectedProductId(),
                current.version() + 1, current.lastMessageAt(), current.createdAt(), now
        ));
    }

    @Transactional
    public void delete(AuthenticatedAccount account, long conversationId) {
        long userId = CatalogAccess.requireUser(account);
        lockConversation(userId, conversationId);
        if (mapper.findActiveRun(userId, conversationId).isPresent()
                || mapper.countPaidTasks(conversationId, userId) > 0
                || mapper.hasMonitorExecutionRecords(conversationId, userId)
                || mapper.hasArticleExecutionRecords(conversationId, userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE);
        }
        if (mapper.deleteConversation(conversationId, userId) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (workspaceService != null) {
            workspaceService.invalidateAfterCommit(userId, conversationId);
        }
    }

    @Transactional(readOnly = true)
    public ConversationDtos.CursorPage<ConversationDtos.MessageResponse> messages(
            AuthenticatedAccount account,
            long conversationId,
            long cursor,
            int limit
    ) {
        long userId = CatalogAccess.requireUser(account);
        requireConversation(userId, conversationId);
        List<ConversationMessageRecord> records = mapper.findMessagesAfter(userId, conversationId, cursor, limit + 1);
        boolean hasNext = records.size() > limit;
        List<ConversationMessageRecord> page = hasNext ? records.subList(0, limit) : records;
        List<ConversationDtos.MessageResponse> items = page.stream().map(this::toMessageResponse).toList();
        String nextCursor = hasNext ? Long.toString(page.getLast().sequenceNo()) : null;
        return new ConversationDtos.CursorPage<>(items, nextCursor);
    }

    @Transactional
    public MessageSubmission submitMessage(
            AuthenticatedAccount account,
            long conversationId,
            String idempotencyKeyValue,
            ConversationDtos.SendMessageRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        // One account lock covers all conversations and rechecks the authentication generation.
        // Replays below return before any quota counter is changed.
        if (mapper.lockAdmissionAccount(userId, account.tokenVersion()) == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        // Serialize admission before either idempotency check or the active-Run check. A replay
        // waiting for the first transaction must see its committed Run, not return a false conflict.
        ConversationRecord conversation = lockConversation(userId, conversationId);
        UUID idempotencyKey = parseUuid(idempotencyKeyValue);
        String content = request.content().trim();
        if (content.isEmpty()) {
            throw validation();
        }
        rejectUnavailableInputs(request);
        Long replyToMessageId = parseOptionalId(request.replyToMessageId());
        Long requestedSpaceId = parseOptionalId(request.selectedSpaceId());
        Long requestedProductId = parseOptionalId(request.selectedProductId());
        ConversationDtos.ContextSelectionMode selectionMode = normalizedSelectionMode(
                request.contextSelectionMode(), requestedSpaceId, requestedProductId
        );
        List<String> attachmentAssetIds = canonicalAttachmentAssetIds(request.attachments());
        Long geoReportId = parseOptionalId(request.geoReportId());
        if (geoReportId != null && !attachmentAssetIds.isEmpty()) throw validation();
        ConversationDtos.ResourceSelection resourceSelection = request.resourceSelection();
        var executionIntent=request.executionIntent();
        String articlePreparationMode = request.articlePreparationMode();
        if (articlePreparationMode != null && (!"ARTICLE_FULL_V1".equals(articlePreparationMode)
                || executionIntent != null || resourceSelection != null || !attachmentAssetIds.isEmpty()
                || selectionMode == ConversationDtos.ContextSelectionMode.CLEAR)) throw validation();
        if(executionIntent!=null&&(geoReportId!=null||resourceSelection!=null||!attachmentAssetIds.isEmpty())) throw validation();
        if (resourceSelection != null && (geoReportId != null || !attachmentAssetIds.isEmpty()
                || selectionMode == ConversationDtos.ContextSelectionMode.CLEAR)) throw validation();

        CanonicalMessage canonical = new CanonicalMessage(
                request.clientMessageId(), content, replyToMessageId, selectionMode,
                requestedSpaceId, requestedProductId, attachmentAssetIds
        );
        Object canonicalRequest = geoReportId == null ? canonical : new GeoCanonicalMessage(canonical, geoReportId);
        Object fingerprintInput=resourceSelection == null ? canonicalRequest
                : new ResourceCanonicalMessage(canonicalRequest, resourceSelection);
        Object executionFingerprint = executionIntent==null?fingerprintInput:new ExecutionCanonicalMessage(fingerprintInput,executionIntent);
        String fingerprint = fingerprint(articlePreparationMode == null ? executionFingerprint
                : new ArticlePreparationCanonicalMessage(executionFingerprint, articlePreparationMode));
        String legacyFingerprint = request.contextSelectionMode() == null && geoReportId == null && resourceSelection == null && executionIntent==null && articlePreparationMode==null
                ? fingerprint(new LegacyCanonicalMessage(
                request.clientMessageId(), content, replyToMessageId,
                requestedSpaceId, requestedProductId, attachmentAssetIds
        )) : null;
        Optional<MessageSubmissionRecord> replay = mapper.findSubmissionByIdempotencyKey(
                userId, conversationId, idempotencyKey
        );
        if (replay.isPresent()) {
            return replay(replay.get(), request.clientMessageId(), idempotencyKey,
                    fingerprint, legacyFingerprint);
        }
        Optional<MessageSubmissionRecord> clientReplay = mapper.findSubmissionByClientMessageId(
                userId, conversationId, request.clientMessageId()
        );
        if (clientReplay.isPresent()) {
            return replay(clientReplay.get(), request.clientMessageId(), idempotencyKey,
                    fingerprint, legacyFingerprint);
        }

        // Identity is frozen separately from one-turn resource selection. Replays of already
        // accepted work must survive removal; only new admissions recheck the installation.
        Long articleProjectId = null;
        Long boundProjectId = null;
        boolean creativeAgent = false;
        if (agentPlatformService != null) {
            var identity = agentPlatformService.assertRunAllowed(userId, conversationId);
            creativeAgent = identity.capabilities().contains("image.generate") || identity.capabilities().contains("video.generate");
            boundProjectId = identity.projectId() == null ? null : positiveLong(identity.projectId());
            if (articlePreparationMode != null) {
                if (!runtimeCatalog.require(identity).explicitActions().contains("ARTICLE_FULL_V1") || identity.installationId() == null || identity.projectId() == null
                        || !identity.capabilities().contains("article.generate")) throw new BusinessException(ErrorCode.FORBIDDEN);
                articleProjectId = positiveLong(identity.projectId());
                if (!agentPlatformService.canUseInstalledAgent(userId, identity.agentKey(), articleProjectId)) {
                    throw new BusinessException(ErrorCode.FORBIDDEN);
                }
                if (requestedSpaceId != null && !requestedSpaceId.equals(articleProjectId)) throw validation();
            }
            if ("summary".equals(runtimeCatalog.require(identity).runtimeKind()) && (!attachmentAssetIds.isEmpty()
                    || geoReportId != null || resourceSelection != null
                    || requestedSpaceId != null || requestedProductId != null)) {
                throw validation();
            }
        }
        if (articlePreparationMode != null && articleProjectId == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);

        PersistedSelection persistedSelection = resolveSelection(
                conversation, selectionMode, requestedSpaceId, requestedProductId
        );
        // A newly created project conversation has a trusted identity but no sidebar selection yet.
        // INHERIT must use that project in both the Run snapshot and persisted conversation so
        // native task admission need not ask the browser to re-select its already bound project.
        if (selectionMode == ConversationDtos.ContextSelectionMode.INHERIT
                && persistedSelection.spaceId() == null && boundProjectId != null) {
            persistedSelection = new PersistedSelection(boundProjectId, null);
        }
        List<AssetRecords.Asset> attachmentAssets = validateAttachments(userId, attachmentAssetIds);
        Long selectedSpaceId = attachmentAssets.isEmpty()
                ? persistedSelection.spaceId() : Long.valueOf(attachmentAssets.getFirst().spaceId());
        Long selectedProductId = attachmentAssets.isEmpty() ? persistedSelection.productId() : null;
        if(creativeAgent) {
            // Creative attachments may not switch clients. Personal storage is not a sidebar/project selection.
            if(!Objects.equals(boundProjectId,persistedSelection.spaceId())) throw validation();
            for(var asset:attachmentAssets) {
                boolean allowed=boundProjectId==null
                        ? agentPlatformService.isPersonalCreativeStorage(userId,asset.spaceId())
                        : boundProjectId.longValue()==asset.spaceId();
                if(!allowed) throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED);
            }
            selectedSpaceId=persistedSelection.spaceId();
            selectedProductId=persistedSelection.productId();
        }
        if (articlePreparationMode != null) {
            if (persistedSelection.spaceId() != null && !persistedSelection.spaceId().equals(articleProjectId)) throw validation();
            selectedSpaceId = articleProjectId;
        }
        com.guanghe.backend.agentplatform.MonitorExecutionService.Selection monitorSelection=null;
        if(executionIntent!=null) {
            if(monitorExecution==null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
            // A one-turn topic scope is not a sidebar update or a new project binding.
            catalogContextFactory.create(userId,persistedSelection.spaceId(),persistedSelection.productId());
            monitorSelection=monitorExecution.prepareMessage(userId,conversationId,executionIntent);
            selectedSpaceId=monitorSelection.spaceId();selectedProductId=monitorSelection.productId();
        }
        RunResourceDtos.Selection selectedResource = null;
        if (resourceSelection != null) {
            if (!properties.isResourceLookupEnabled() || resourceService == null) {
                throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE);
            }
            if (replyToMessageId != null) {
                ConversationMessageRecord replied = mapper.findMessage(replyToMessageId, userId, conversationId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                JsonNode interaction = readTree(replied.contentJson()).path("interaction");
                if (!"ASSISTANT".equals(replied.role())
                        || !"RESOURCE_SELECTION".equals(interaction.path("interactionType").asText())
                        || !resourceSelection.candidateSetId().equals(interaction.path("candidateSetId").asText())) {
                    throw validation();
                }
            }
            selectedResource = resourceService.validateSelection(userId, conversationId,
                    resourceSelection.candidateSetId(), resourceSelection.choiceId(), selectionMode.name(),
                    persistedSelection.spaceId(), persistedSelection.productId());
            // This is a one-turn read. The independently persisted sidebar is neither overwritten
            // nor treated as evidence for the temporarily selected resource.
            catalogContextFactory.create(userId, persistedSelection.spaceId(), persistedSelection.productId());
            selectedSpaceId = selectedResource.spaceId();
            selectedProductId = selectedResource.productId();
        }
        if (!attachmentAssets.isEmpty()
                && (persistedSelection.spaceId() != null || persistedSelection.productId() != null)) {
            // CURRENT_ATTACHMENTS is the source for this Run, but the independently persisted sidebar
            // context must still be authorized before it is retained for later turns.
            catalogContextFactory.create(userId, persistedSelection.spaceId(), persistedSelection.productId());
        }
        if (boundProjectId != null && ((persistedSelection.spaceId() != null
                && !boundProjectId.equals(persistedSelection.spaceId()))
                || (selectedSpaceId != null && !boundProjectId.equals(selectedSpaceId)))) {
            // A project conversation is a client boundary, not an owner-wide resource selector.
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (replyToMessageId != null
                && mapper.findMessage(replyToMessageId, userId, conversationId).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (mapper.findActiveRun(userId, conversationId).isPresent()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }

        UUID runId = UUID.randomUUID();
        String contextRef = newContextRef();
        String contextRefHash = sha256(contextRef.getBytes(StandardCharsets.UTF_8));
        OffsetDateTime now = now();
        requireAvailableQuota(userId, !attachmentAssets.isEmpty(), now);
        long userMessageId = idGenerator.nextId();
        Long userSequence = mapper.nextMessageSequence(conversationId, userId);
        if (userSequence == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        ObjectNode userContent = objectMapper.createObjectNode().put("text", content);
        if (articlePreparationMode != null) userContent.put("articlePreparationMode", articlePreparationMode);
        // Preserve only the original USER's explicit report choice for later bounded preparation.
        if (geoReportId != null) userContent.put("geoReportId", Long.toString(geoReportId));
        if(executionIntent!=null) {
            userContent.set("executionIntent",objectMapper.valueToTree(executionIntent));
            if(monitorSelection.context()!=null) userContent.set("monitorActionContext",objectMapper.valueToTree(monitorSelection.context()));
            if(monitorSelection.reason()!=null) userContent.put("monitorPreparationReason",monitorSelection.reason().name());
        }
        if (resourceSelection != null) userContent.set("resourceSelection", objectMapper.valueToTree(resourceSelection));
        if (selectionMode == ConversationDtos.ContextSelectionMode.CLEAR
                || selectionMode == ConversationDtos.ContextSelectionMode.REPLACE
                && (!Objects.equals(conversation.selectedSpaceId(), persistedSelection.spaceId())
                    || !Objects.equals(conversation.selectedProductId(), persistedSelection.productId()))) {
            userContent.put("resourceContextReset", true);
        }
        ConversationMessageRecord userMessage = new ConversationMessageRecord(
                userMessageId, conversationId, userId, "USER", "TEXT",
                writeJson(userContent), replyToMessageId, runId,
                request.clientMessageId(), idempotencyKey, fingerprint, userSequence,
                "COMPLETED", now, now
        );
        if (mapper.insertUserMessage(userMessage) != 1) {
            MessageSubmissionRecord existing = mapper.findSubmissionByIdempotencyKey(
                            userId, conversationId, idempotencyKey
                    ).or(() -> mapper.findSubmissionByClientMessageId(
                            userId, conversationId, request.clientMessageId()
                    ))
                    .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED));
            return replay(existing, request.clientMessageId(), idempotencyKey,
                    fingerprint, legacyFingerprint);
        }
        // Native memory tools persist exact current USER facts under server-resolved scope.
        // Prefixes such as '以后' are not an authorization to write account-wide preferences.
        for (int index = 0; index < attachmentAssets.size(); index++) {
            AssetRecords.Asset asset = attachmentAssets.get(index);
            if (mapper.insertMessageAsset(userMessageId, asset.id(), userId, conversationId,
                    asset.spaceId(), index + 1, now) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
        }
        String automaticTitle = automaticTitle(content);
        if (!DEFAULT_TITLE.equals(automaticTitle)) {
            mapper.replaceDefaultTitleFromFirstMessage(
                    conversationId, userId, DEFAULT_TITLE, automaticTitle, now
            );
        }

        InternalRunContextDtos.RunContext contextSnapshot = selectedResource != null
                ? contextSnapshotFactory.createWithResourceSelection(runId, conversationId, userId,
                selectedSpaceId, selectedProductId, userMessage, selectedResource,
                persistedSelection.spaceId(), persistedSelection.productId())
                : geoReportId != null
                ? contextSnapshotFactory.create(runId, conversationId, userId,
                selectedSpaceId, selectedProductId, userMessage, geoReportId)
                : attachmentAssets.isEmpty()
                ? contextSnapshotFactory.create(runId, conversationId, userId,
                selectedSpaceId, selectedProductId, userMessage)
                : contextSnapshotFactory.create(runId, conversationId, userId,
                selectedSpaceId, null, attachmentAssetIds, userMessage);

        AgentRunRecord run = new AgentRunRecord(
                runId, conversationId, userId, userMessageId, null, selectedSpaceId, selectedProductId,
                writeJson(attachmentAssetIds), "QUEUED", 1, writeJson(contextSnapshot), contextRefHash,
                now.plus(properties.getContextRefTtl()),
                null, 0, "{}", null, persistableTraceId(), null, null, null, now, now,
                now.plus(properties.getRunDeadline())
        );
        try {
            mapper.insertRun(run);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (mapper.insertAdmission(runId, userId, now, !attachmentAssets.isEmpty()) != 1) {
            throw new IllegalStateException("Unable to record accepted conversation turn");
        }
        long assistantMessageId = idGenerator.nextId();
        Long assistantSequence = mapper.nextMessageSequence(conversationId, userId);
        if (assistantSequence == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        mapper.insertAssistantMessage(new ConversationMessageRecord(
                assistantMessageId, conversationId, userId, "ASSISTANT", "TEXT", "{\"text\":\"\"}",
                null, runId, null, null, null, assistantSequence, "STREAMING", now, now
        ));
        mapper.bindRunResponseMessage(runId, userId, assistantMessageId, now);
        mapper.updateConversationSelection(
                conversationId, userId, persistedSelection.spaceId(), persistedSelection.productId(), now
        );
        return new MessageSubmission(
                new ConversationDtos.MessageAccepted(userMessageId + "", runId.toString(), "QUEUED"),
                true,
                contextRef,
                content,
                replyToMessageId,
                contextSnapshot.space() == null ? null : Long.valueOf(contextSnapshot.space().id()),
                contextSnapshot.product() == null ? null : Long.valueOf(contextSnapshot.product().id()),
                pendingInteractionId(contextSnapshot),
                contextSnapshot.taskWorkspace().activeDraftId(),
                contextSnapshot.taskWorkspace().activeTaskId(),
                contextSnapshot.attachmentAssetIds()
        );
    }

    @Transactional(readOnly = true)
    public AgentRunRecord requireOwnedActiveRun(
            AuthenticatedAccount account,
            long conversationId
    ) {
        long userId = CatalogAccess.requireUser(account);
        requireConversation(userId, conversationId);
        return mapper.findActiveRun(userId, conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Transactional
    public AgentRunRecord requestStop(AuthenticatedAccount account, long conversationId, UUID expectedRunId) {
        long userId = CatalogAccess.requireUser(account);
        requireConversation(userId, conversationId);
        if (expectedRunId == null) throw validation();
        AgentRunRecord run = mapper.lockOwnedRun(expectedRunId, userId, conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (java.util.Set.of("COMPLETED", "FAILED", "STOPPED").contains(run.status())) return run;
        if (mapper.requestStop(run.id(), run.userId(), conversationId, now()) != 1
                && !"STOP_REQUESTED".equals(run.status())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return new AgentRunRecord(
                run.id(), run.conversationId(), run.userId(), run.requestMessageId(), run.responseMessageId(),
                run.selectedSpaceId(), run.selectedProductId(), run.attachmentAssetIdsJson(), "STOP_REQUESTED",
                run.contextVersion(), run.contextSnapshotJson(), run.contextRefHash(), run.contextExpiresAt(),
                run.pythonEventsUrl(),
                run.lastPythonSequence(), run.usageJson(), run.errorCode(), run.traceId(), now(), run.startedAt(),
                run.completedAt(), run.createdAt(), now(), run.deadlineAt()
        );
    }

    private String persistableTraceId() {
        String traceId = TraceIdHolder.currentTraceId();
        return traceId != null && traceId.matches("[0-9a-f]{32}")
                && !traceId.chars().allMatch(character -> character == '0') ? traceId : null;
    }

    ConversationRecord requireConversation(long userId, long conversationId) {
        return mapper.findConversation(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private ConversationRecord lockConversation(long userId, long conversationId) {
        return mapper.lockConversation(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireAvailableQuota(long userId, boolean vision, OffsetDateTime now) {
        if (mapper.countActiveRuns(userId) >= properties.getMaxConcurrentRuns()) {
            throw new BusinessException(ErrorCode.CHAT_CONCURRENCY_LIMIT);
        }
        OffsetDateTime dayStart = now.atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                .toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        ConversationQuotaUsage usage = mapper.admissionUsage(userId, now.minusSeconds(60), dayStart);
        if (usage == null) throw new IllegalStateException("Conversation quota projection unavailable");
        if (usage.dailyAccepted() >= properties.getMaxTurnsPerDay()) {
            throw new BusinessException(ErrorCode.CHAT_DAILY_LIMIT);
        }
        if (vision && usage.visionAccepted() >= properties.getMaxVisionTurnsPerDay()) {
            throw new BusinessException(ErrorCode.CHAT_VISION_DAILY_LIMIT);
        }
        if (usage.minuteAccepted() >= properties.getMaxTurnsPerMinute()) {
            throw new BusinessException(ErrorCode.CHAT_MINUTE_LIMIT);
        }
    }

    @Transactional(readOnly = true)
    public ConversationDtos.MessageAccepted messageOperation(
            AuthenticatedAccount account, long conversationId, UUID clientMessageId) {
        long userId = CatalogAccess.requireUser(account);
        requireConversation(userId, conversationId);
        MessageSubmissionRecord operation = mapper.findSubmissionByClientMessageId(
                userId, conversationId, clientMessageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return new ConversationDtos.MessageAccepted(Long.toString(operation.messageId()),
                operation.runId().toString(), operation.runStatus());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConversationDtos.Snapshot snapshot(AuthenticatedAccount account, long conversationId, int limit) {
        long userId = CatalogAccess.requireUser(account);
        requireConversation(userId, conversationId);
        ConversationDtos.CursorPage<ConversationDtos.MessageResponse> page = earlierMessages(
                userId, conversationId, Long.MAX_VALUE, limit);
        long highWatermark = mapper.eventHighWatermark(conversationId, userId);
        ConversationDtos.MessageAccepted activeRun = mapper.findActiveRun(userId, conversationId)
                .map(run -> new ConversationDtos.MessageAccepted(Long.toString(run.requestMessageId()),
                        run.id().toString(), run.status())).orElse(null);
        return new ConversationDtos.Snapshot(page.items(), page.nextCursor(), highWatermark, activeRun);
    }

    @Transactional(readOnly = true)
    public ConversationDtos.CursorPage<ConversationDtos.MessageResponse> messagesBefore(
            AuthenticatedAccount account, long conversationId, long beforeSequence, int limit) {
        long userId = CatalogAccess.requireUser(account);
        requireConversation(userId, conversationId);
        return earlierMessages(userId, conversationId, beforeSequence, limit);
    }

    private ConversationDtos.CursorPage<ConversationDtos.MessageResponse> earlierMessages(
            long userId, long conversationId, long beforeSequence, int limit) {
        List<ConversationMessageRecord> records = mapper.findMessagesBefore(
                userId, conversationId, beforeSequence, limit + 1);
        boolean hasEarlier = records.size() > limit;
        List<ConversationMessageRecord> page = hasEarlier ? records.subList(0, limit) : records;
        return new ConversationDtos.CursorPage<>(page.reversed().stream().map(this::toMessageResponse).toList(),
                hasEarlier ? Long.toString(page.getLast().sequenceNo()) : null);
    }

    private MessageSubmission replay(
            MessageSubmissionRecord existing,
            UUID clientMessageId,
            UUID idempotencyKey,
            String fingerprint,
            String legacyFingerprint
    ) {
        if (!existing.clientMessageId().equals(clientMessageId)
                || !existing.idempotencyKey().equals(idempotencyKey)
                || !(sameFingerprint(existing.requestFingerprint(), fingerprint)
                || sameFingerprint(existing.requestFingerprint(), legacyFingerprint))) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return new MessageSubmission(
                new ConversationDtos.MessageAccepted(
                        Long.toString(existing.messageId()), existing.runId().toString(), existing.runStatus()
                ),
                false, null, null, null, null, null, null, null, null
        );
    }

    private boolean sameFingerprint(String persisted, String candidate) {
        return persisted != null && candidate != null && MessageDigest.isEqual(
                persisted.getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private String pendingInteractionId(InternalRunContextDtos.RunContext context) {
        return context.taskWorkspace().pendingInteraction() == null
                ? null : context.taskWorkspace().pendingInteraction().messageId();
    }

    private void rejectUnavailableInputs(ConversationDtos.SendMessageRequest request) {
        if (request.draftId() != null || request.taskId() != null) {
            throw validation();
        }
    }

    private List<String> canonicalAttachmentAssetIds(
            List<ConversationDtos.AttachmentRequest> requested
    ) {
        if (requested == null || requested.isEmpty()) return List.of();
        HashSet<Long> unique = new HashSet<>();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (ConversationDtos.AttachmentRequest item : requested) {
            if (item == null) throw validation();
            long assetId = positiveLong(item.assetId());
            if (!unique.add(assetId)) throw validation();
            result.add(Long.toString(assetId));
        }
        return List.copyOf(result);
    }

    private List<AssetRecords.Asset> validateAttachments(long userId, List<String> requestedAssetIds) {
        if (requestedAssetIds.isEmpty()) return List.of();
        if (assetMapper == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        Long commonSpaceId = null;
        java.util.ArrayList<AssetRecords.Asset> result = new java.util.ArrayList<>();
        for (String requestedAssetId : requestedAssetIds) {
            long assetId = positiveLong(requestedAssetId);
            AssetRecords.Asset asset = assetMapper.findAsset(assetId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED));
            if (!"IMAGE".equals(asset.assetType())) {
                throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED);
            }
            if (!"READY".equals(asset.status()) || !downloadAllowed(asset.safetyStatus())) {
                throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_READY);
            }
            if (commonSpaceId != null && commonSpaceId.longValue() != asset.spaceId()) {
                throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED);
            }
            commonSpaceId = asset.spaceId();
            result.add(asset);
        }
        return List.copyOf(result);
    }

    private ConversationDtos.ContextSelectionMode normalizedSelectionMode(
            ConversationDtos.ContextSelectionMode requestedMode,
            Long requestedSpaceId,
            Long requestedProductId
    ) {
        if (requestedMode != null) return requestedMode;
        return requestedSpaceId != null || requestedProductId != null
                ? ConversationDtos.ContextSelectionMode.REPLACE
                : ConversationDtos.ContextSelectionMode.INHERIT;
    }

    private PersistedSelection resolveSelection(
            ConversationRecord conversation,
            ConversationDtos.ContextSelectionMode mode,
            Long requestedSpaceId,
            Long requestedProductId
    ) {
        return switch (mode) {
            case INHERIT -> {
                if (requestedSpaceId != null || requestedProductId != null) throw validation();
                yield new PersistedSelection(
                        conversation.selectedSpaceId(), conversation.selectedProductId()
                );
            }
            case REPLACE -> {
                if (requestedSpaceId == null) throw validation();
                yield new PersistedSelection(requestedSpaceId, requestedProductId);
            }
            case CLEAR -> {
                if (requestedSpaceId != null || requestedProductId != null) throw validation();
                yield new PersistedSelection(null, null);
            }
        };
    }

    private boolean downloadAllowed(String safetyStatus) {
        return assetProperties != null && assetProperties.allowsAssetUse(safetyStatus);
    }

    private ConversationDtos.Response toResponse(ConversationRecord value) {
        return toResponse(value, null);
    }

    private ConversationDtos.Response toResponse(
            ConversationRecord value,
            String activeConfirmationDraftId
    ) {
        return new ConversationDtos.Response(
                Long.toString(value.id()), value.title(), id(value.selectedSpaceId()), id(value.selectedProductId()),
                activeConfirmationDraftId, value.version(), value.lastMessageAt(), value.createdAt(), value.updatedAt(),
                agentPlatformService == null ? null : agentPlatformService.identityOf(value.userId(), value.id())
        );
    }

    private ConversationDtos.MessageResponse toMessageResponse(ConversationMessageRecord value) {
        JsonNode content = readTree(value.contentJson());
        JsonNode interaction = content.path("interaction");
        if (content.isObject() && "RESOURCE_SELECTION".equals(interaction.path("interactionType").asText())) {
            ObjectNode projected = (ObjectNode) content.deepCopy();
            boolean available = false;
            if (properties.isResourceLookupEnabled() && resourceService != null) {
                ConversationRecord conversation = requireConversation(value.userId(), value.conversationId());
                RunResourceDtos.SearchResponse pending = resourceService.pendingSearch(value.userId(),
                        value.conversationId(), conversation.selectedSpaceId(), conversation.selectedProductId());
                available = pending != null && pending.candidateSetId().equals(interaction.path("candidateSetId").asText());
            }
            ((ObjectNode) projected.path("interaction")).put("available", available);
            content = projected;
        }
        JsonNode report = content.at("/geoContext/report");
        if (content.isObject() && "ASSISTANT".equals(value.role()) && report.isObject() && report.has("observationId")) {
            // Frozen historical identity is retained; action availability is checked against today's owner/scope/reset.
            ObjectNode projected = (ObjectNode) content.deepCopy();
            boolean available = false;
            if (properties.isResourceLookupEnabled() && resourceService != null
                    && report.path("observationId").isTextual()
                    && report.path("observationId").asText().matches("^[1-9][0-9]{0,18}$")
                    && report.path("reportId").isTextual()
                    && report.path("reportId").asText().matches("^[1-9][0-9]{0,18}$")
                    && report.path("canCreateArticle").asBoolean(false)) {
                available = resourceService.isOwnedObservationAvailableForAction(value.userId(), value.conversationId(),
                        report.path("observationId").asText(), Long.parseLong(report.path("reportId").asText()));
            }
            ((ObjectNode) projected.at("/geoContext/report")).put("available", available);
            content = projected;
        }
        return new ConversationDtos.MessageResponse(
                Long.toString(value.id()), Long.toString(value.conversationId()), value.role(), value.contentType(),
                content, id(value.replyToMessageId()),
                value.agentRunId() == null ? null : value.agentRunId().toString(),
                mapper.findMessageAssetIds(value.id(), value.userId(), value.conversationId())
                        .stream().map(String::valueOf).toList(),
                value.sequenceNo(), value.status(), value.createdAt(), value.updatedAt()
        );
    }

    private String requiredTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw validation();
        }
        return normalized;
    }

    private String automaticTitle(String content) {
        String singleLine = content.replaceAll("\\s+", " ").trim();
        int codePoints = singleLine.codePointCount(0, singleLine.length());
        if (codePoints <= AUTOMATIC_TITLE_MAX_CODE_POINTS) {
            return singleLine;
        }
        int end = singleLine.offsetByCodePoints(0, AUTOMATIC_TITLE_MAX_CODE_POINTS - 1);
        return singleLine.substring(0, end) + "…";
    }

    private Long parseOptionalId(String value) {
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw validation();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw validation();
        }
    }

    private long positiveLong(String value) {
        Long parsed = parseOptionalId(value);
        if (parsed == null) throw validation();
        return parsed;
    }

    private UUID parseUuid(String value) {
        if (value == null) {
            throw validation();
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw validation();
        }
    }

    private String newContextRef() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String fingerprint(Object value) {
        try {
            return sha256(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to fingerprint conversation request", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode conversation JSON", exception);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode conversation JSON", exception);
        }
    }

    private long offset(int page, int size) {
        return Math.multiplyExact((long) page - 1, size);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private BusinessException validation() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private record CanonicalMessage(
            UUID clientMessageId,
            String content,
            Long replyToMessageId,
            ConversationDtos.ContextSelectionMode contextSelectionMode,
            Long selectedSpaceId,
            Long selectedProductId,
            List<String> attachmentAssetIds
    ) {
    }

    private record GeoCanonicalMessage(CanonicalMessage message, long geoReportId) { }

    private record ResourceCanonicalMessage(Object message, ConversationDtos.ResourceSelection resourceSelection) { }
    private record ArticlePreparationCanonicalMessage(Object message, String articlePreparationMode) { }
    private record ExecutionCanonicalMessage(Object message,
            com.guanghe.backend.agentplatform.MonitorExecutionDtos.MonitorExecutionIntent executionIntent) { }

    /** Serialization shape used before contextSelectionMode became an explicit API field. */
    private record LegacyCanonicalMessage(
            UUID clientMessageId,
            String content,
            Long replyToMessageId,
            Long selectedSpaceId,
            Long selectedProductId,
            List<String> attachmentAssetIds
    ) {
    }

    private record PersistedSelection(Long spaceId, Long productId) {
    }

    public record MessageSubmission(
            ConversationDtos.MessageAccepted response,
            boolean newSubmission,
            String contextRef,
            String content,
            Long replyToMessageId,
            Long selectedSpaceId,
            Long selectedProductId,
            String pendingInteractionId,
            String draftId,
            String taskId,
            List<String> attachmentAssetIds
    ) {
        public MessageSubmission(ConversationDtos.MessageAccepted response, boolean newSubmission,
                String contextRef, String content, Long replyToMessageId, Long selectedSpaceId,
                Long selectedProductId, String pendingInteractionId, String draftId, String taskId) {
            this(response, newSubmission, contextRef, content, replyToMessageId, selectedSpaceId,
                    selectedProductId, pendingInteractionId, draftId, taskId, List.of());
        }

        public MessageSubmission(
                ConversationDtos.MessageAccepted response,
                boolean newSubmission,
                String contextRef,
                String content,
                Long replyToMessageId,
                Long selectedSpaceId,
                Long selectedProductId
        ) {
            this(response, newSubmission, contextRef, content, replyToMessageId, selectedSpaceId,
                    selectedProductId, null, null, null);
        }

        public MessageSubmission {
            Objects.requireNonNull(response);
            attachmentAssetIds = List.copyOf(attachmentAssetIds);
        }
    }
}
