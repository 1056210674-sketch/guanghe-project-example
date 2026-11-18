package com.guanghe.backend.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.agentplatform.AgentPlatformService;
import com.guanghe.backend.article.ArticleService;
import com.guanghe.backend.knowledge.KnowledgeService;
import com.guanghe.backend.knowledge.KnowledgeDtos;
import com.guanghe.backend.media.MediaSubmissionService;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.web.TraceIdHolder;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationEventRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.action.CreativeContinuationEvidence;
import com.guanghe.backend.conversation.action.RunActionDtos.CreativeContinuationReview;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.TaskWorkspaceStateSource;
import com.guanghe.backend.geo.GeoTaskService;
import com.guanghe.backend.integration.internal.client.PythonChatRunEvent;
import com.guanghe.backend.workflow.WorkflowTaskLifecycleService;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class TaskControlService implements TaskWorkspaceStateSource {
    private static final String GEO_ARTICLE_DRAFT_SCOPE = "TASK_DRAFT_CREATE";
    private static final Set<String> DRAFT_REQUIRED_FIELDS = Set.of("proposalOnly", "taskType",
            "parameterSchemaVersion", "spaceId", "productId", "parameters", "warnings");
    private static final Set<String> DRAFT_FIELDS = Set.of("proposalOnly", "taskType",
            "parameterSchemaVersion", "spaceId", "productId", "parameters", "warnings",
            "sourceMode", "referenceAssetIds", "sourceMessageId", "productCreativeBrief",
            "draftOperation", "previousDraftId");
    private static final Set<String> IMAGE_PARAMETER_FIELDS = Set.of("requestText", "mode", "agentSummary",
            "sourceMode", "referenceAssetIds", "scene", "style", "lighting", "aspectRatio", "count",
            "negativePrompt", "maskAssetId", "geoReportId", "geoRecommendationId", "creativePlan", "visualBrief");
    private static final Set<String> VISUAL_BRIEF_FIELDS = Set.of("version", "platform", "placement",
            "visualFocus", "composition", "background", "lighting", "textPolicy", "copyLines");
    private static final Set<String> VIDEO_PARAMETER_FIELDS = Set.of("requestText", "mode", "agentSummary",
            "sourceMode", "referenceAssetIds", "scene", "style", "durationSeconds", "aspectRatio", "cameraMotion", "resolution",
            "audio", "seed", "promptExtend", "watermark", "geoReportId", "geoRecommendationId",
            "creativePlan");
    private static final Set<String> PROGRESS_EVENTS = Set.of("TASK_PROGRESS", "PROVIDER_ACCEPTED", "USAGE_REPORTED");
    private static final Set<String> TERMINAL_TASK_STATUSES = Set.of(
            "SUCCEEDED", "PARTIAL", "FAILED", "SKIPPED", "SYSTEM_TERMINATED");
    private static final Set<String> TASK_TYPES = Set.of("IMAGE", "VIDEO", "GEO", "ARTICLE", "MEDIA");
    private static final Set<String> TASK_STATUSES = Set.of(
            "QUEUED", "RUNNING", "RETRY_WAIT", "SUCCEEDED", "PARTIAL",
            "FAILED", "SKIPPED", "SYSTEM_TERMINATED");
    // Recharge and manual adjustment are written by the billing control plane but read here: the
    // ledger is one table with one reader, so a payer sees every movement in one list. These two
    // values must stay in step with the CHECK constraint on business.point_ledger.
    private static final Set<String> POINT_LEDGER_ENTRY_TYPES = Set.of(
            "TASK_FREEZE", "TASK_SETTLE", "TASK_RELEASE", "WORKFLOW_FREEZE",
            "WORKFLOW_ALLOCATE", "WORKFLOW_TASK_SETTLE", "WORKFLOW_TASK_RELEASE",
            "WORKFLOW_SETTLE", "WORKFLOW_RELEASE", "ADMIN_INITIAL_GRANT",
            "RECHARGE", "ADMIN_ADJUSTMENT");
    private static final Set<String> POINT_LEDGER_BUSINESS_TYPES = Set.of(
            "TASK", "WORKFLOW", "ACCOUNT_PROVISIONING", "RECHARGE", "ADMIN_ADJUSTMENT");
    private static final Set<String> BROWSER_TASK_EVENT_TYPES = Set.of(
            "task.queued", "task.progress", "task.completed", "task.failed");
    private static final String MOCK_PROVIDER = "LOCAL_TASK_PROVIDER";
    private static final String MOCK_MODEL = "deterministic-provider-mock-v1";
    private static final String QWEN_IMAGE_PROVIDER = "QWEN_IMAGE";
    private static final String QWEN_IMAGE_MODEL = "qwen-image-3.0";
    private static final String WAN_IMAGE_EDIT_PROVIDER = "WAN_IMAGE_EDIT";
    private static final String WAN_IMAGE_EDIT_MODEL = "wanx2.1-imageedit";
    private static final String WAN_VIDEO_PROVIDER = "WAN_VIDEO";
    private static final String WAN_VIDEO_MODEL = "wan3.0-video";
    private static final String PROVIDER_RESULT_UNKNOWN = "PROVIDER_RESULT_UNKNOWN";
    private static final String PROVIDER_RESULT_RECOVERY = "PROVIDER_RESULT_RECOVERY";
    private static final String PROVIDER_RESULT_CONFIRMATION_FAILED =
            "PROVIDER_RESULT_CONFIRMATION_FAILED";
    private static final String PROVIDER_RESULT_RECOVERY_EXHAUSTED =
            "PROVIDER_RESULT_RECOVERY_EXHAUSTED";
    private static final Set<String> TERMINAL_FAILURE_FIELDS = Set.of("code", "submissionState");
    private AgentPlatformService agentPlatformService;
    private KnowledgeService articleKnowledge;
    @Autowired(required=false)
    void setArticleKnowledge(KnowledgeService service) { articleKnowledge=service; }
    private com.guanghe.backend.agentplatform.MonitorExecutionService monitorExecution;
    private com.guanghe.backend.agentplatform.MonitorExecutionMapper monitorMapper;
    private com.guanghe.backend.agentplatform.AgentPlatformProperties platformProperties;

    @Autowired(required=false)
    void setPlatformProperties(com.guanghe.backend.agentplatform.AgentPlatformProperties value) { platformProperties=value; }

    @Autowired(required=false)
    void setMonitorExecution(com.guanghe.backend.agentplatform.MonitorExecutionService service,
            com.guanghe.backend.agentplatform.MonitorExecutionMapper executionMapper) {
        monitorExecution=service;monitorMapper=executionMapper;
    }

    @Autowired(required = false)
    void setAgentPlatformService(AgentPlatformService value) { agentPlatformService = value; }
    private static final Set<String> UNKNOWN_FAILURE_FIELDS = Set.of(
            "code", "submissionState", "provider", "model", "providerRequestId", "providerTaskId");
    private static final Set<String> ARTIFACT_FIELDS = Set.of(
            "artifactType", "unitKey", "schemaVersion", "providerUrl", "contentType",
            "sizeBytes", "sha256", "expiresAt", "inlineData", "metadata");
    private static final Set<String> USAGE_FIELDS = Set.of(
            "inputTokens", "outputTokens", "images", "videoSeconds", "providerCost", "currency");
    private static final Set<String> QWEN_METADATA_FIELDS = Set.of(
            "index", "mock", "width", "height", "outputImageType",
            "providerRequestId", "providerResultRequestId");
    private static final Set<String> WAN_METADATA_FIELDS = Set.of(
            "durationSeconds", "requestedDurationSeconds", "requestedAspectRatio",
            "requestedResolution", "mock", "providerRequestId", "providerResultRequestId",
            "fps", "resolution", "aspectRatio");

    private final TaskControlMapper mapper;
    private final ConversationMapper conversationMapper;
    private CreativeContinuationEvidence creativeContinuations;
    @Autowired(required = false)
    void setCreativeContinuations(CreativeContinuationEvidence value) { creativeContinuations = value; }
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final PaidTaskProperties properties;
    private final AssetIngestionProperties assetProperties;
    private final AuthorizedCatalogContextFactory catalogContextFactory;
    private final GeoTaskService geoTasks;
    private final ArticleService articleService;
    private final TaskConfirmationTokenCodec confirmationTokens;
    private final MediaSubmissionService mediaSubmissions;
    private final TransactionTemplate transactions;
    private final ObservabilitySupport observability;
    private WorkflowTaskLifecycleService workflowLifecycle;
    private CreativeTaskSourceService creativeSources;
    private TaskNotificationService taskNotifications;
    private Clock accountingClock = Clock.systemUTC();

    @Autowired
    void setAccountingClock(@org.springframework.beans.factory.annotation.Qualifier("billingClock") Clock clock) {
        this.accountingClock = java.util.Objects.requireNonNull(clock);
    }

    @Autowired
    void setTaskNotifications(TaskNotificationService taskNotifications) {
        this.taskNotifications = taskNotifications;
    }

    @Autowired(required = false)
    void setWorkflowLifecycle(ObjectProvider<WorkflowTaskLifecycleService> provider) {
        this.workflowLifecycle = provider.getIfAvailable();
    }

    @Autowired(required = false)
    void setCreativeSources(ObjectProvider<CreativeTaskSourceService> provider) {
        this.creativeSources = provider.getIfAvailable();
    }

    @Autowired
    public TaskControlService(TaskControlMapper mapper, ConversationMapper conversationMapper,
                              SnowflakeIdGenerator ids, ObjectMapper json, PaidTaskProperties properties,
                              AssetIngestionProperties assetProperties,
                              AuthorizedCatalogContextFactory catalogContextFactory,
                              GeoTaskService geoTasks, ArticleService articleService,
                              ObjectProvider<MediaSubmissionService> mediaSubmissions,
                              PlatformTransactionManager transactionManager,
                              ObservabilitySupport observability) {
        this(mapper, conversationMapper, ids, json, properties, assetProperties,
                catalogContextFactory, geoTasks, articleService, mediaSubmissions.getIfAvailable(),
                new TransactionTemplate(transactionManager), observability);
    }

    public TaskControlService(TaskControlMapper mapper, ConversationMapper conversationMapper,
                              SnowflakeIdGenerator ids, ObjectMapper json, PaidTaskProperties properties,
                              AssetIngestionProperties assetProperties,
                              AuthorizedCatalogContextFactory catalogContextFactory,
                              GeoTaskService geoTasks, ArticleService articleService,
                              MediaSubmissionService mediaSubmissions) {
        this(mapper, conversationMapper, ids, json, properties, assetProperties,
                catalogContextFactory, geoTasks, articleService, mediaSubmissions, null,
                ObservabilitySupport.noop());
    }

    private TaskControlService(TaskControlMapper mapper, ConversationMapper conversationMapper,
                               SnowflakeIdGenerator ids, ObjectMapper json, PaidTaskProperties properties,
                               AssetIngestionProperties assetProperties,
                               AuthorizedCatalogContextFactory catalogContextFactory,
                               GeoTaskService geoTasks, ArticleService articleService,
                               MediaSubmissionService mediaSubmissions, TransactionTemplate transactions,
                               ObservabilitySupport observability) {
        this.mapper = mapper;
        this.conversationMapper = conversationMapper;
        this.ids = ids;
        this.json = json;
        this.properties = properties;
        this.assetProperties = assetProperties;
        this.catalogContextFactory = catalogContextFactory;
        this.geoTasks = geoTasks;
        this.articleService = articleService;
        this.confirmationTokens = new TaskConfirmationTokenCodec(properties);
        this.mediaSubmissions = mediaSubmissions;
        this.transactions = transactions;
        this.observability = observability;
    }

    public TaskControlService(TaskControlMapper mapper, ConversationMapper conversationMapper,
                              SnowflakeIdGenerator ids, ObjectMapper json, PaidTaskProperties properties,
                              AssetIngestionProperties assetProperties,
                              AuthorizedCatalogContextFactory catalogContextFactory,
                              GeoTaskService geoTasks, ArticleService articleService) {
        this(mapper, conversationMapper, ids, json, properties, assetProperties,
                catalogContextFactory, geoTasks, articleService, (MediaSubmissionService) null);
    }

    public TaskControlService(TaskControlMapper mapper, ConversationMapper conversationMapper,
                              SnowflakeIdGenerator ids, ObjectMapper json, PaidTaskProperties properties,
                              AssetIngestionProperties assetProperties,
                              AuthorizedCatalogContextFactory catalogContextFactory) {
        this(mapper, conversationMapper, ids, json, properties, assetProperties,
                catalogContextFactory, null, null, (MediaSubmissionService) null);
    }

    public TaskControlService(TaskControlMapper mapper, ConversationMapper conversationMapper,
                              SnowflakeIdGenerator ids, ObjectMapper json, PaidTaskProperties properties,
                              AssetIngestionProperties assetProperties,
                              AuthorizedCatalogContextFactory catalogContextFactory,
                              GeoTaskService geoTasks) {
        this(mapper, conversationMapper, ids, json, properties, assetProperties,
                catalogContextFactory, geoTasks, null, (MediaSubmissionService) null);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskWorkspaceStateSource.State read(long userId, long conversationId) {
        return readWorkspace(userId, conversationId, false, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskWorkspaceStateSource.State read(long userId, long conversationId, Long spaceId, Long productId) {
        return readWorkspace(userId, conversationId, true, spaceId, productId);
    }

    private TaskWorkspaceStateSource.State readWorkspace(long userId, long conversationId,
                                                        boolean scoped, Long spaceId, Long productId) {
        OffsetDateTime now = now();
        TaskRecords.Draft draft = mapper.findActiveWorkspaceDraft(userId, conversationId, now).orElse(null);
        // Do not revive an older matching draft when the most recent candidate belongs to another scope.
        if (draft != null && scoped && (!java.util.Objects.equals(spaceId, draft.spaceId())
                || !java.util.Objects.equals(productId, draft.productId()))) draft = null;
        List<TaskRecords.Task> activeTasks = mapper.findActiveWorkspaceTasks(userId, conversationId);
        TaskRecords.Task activeTask = activeTasks.isEmpty() ? null : activeTasks.getFirst();
        JsonNode selectedConstraints = draft == null ? json.createObjectNode() : read(draft.parametersJson());
        ObjectNode constraints = selectedConstraints.isObject()
                ? (ObjectNode) selectedConstraints.deepCopy() : json.createObjectNode();
        TaskRecords.ActiveArticle activeArticle = mapper
                .findActiveConversationArticle(userId, conversationId, scoped, spaceId, productId).orElse(null);
        if (activeArticle != null) {
            ObjectNode article = json.createObjectNode();
            article.put("articleId", Long.toString(activeArticle.articleId()));
            article.put("articleVersion", activeArticle.articleVersion());
            article.put("workflowStage", activeArticle.workflowStage());
            article.put("title", activeArticle.title());
            if (activeArticle.productId() == null) article.putNull("productId");
            else article.put("productId", Long.toString(activeArticle.productId()));
            constraints.set("activeArticle", article);
        }
        String intent = draft != null ? draft.taskType()
                : activeArticle == null ? null : "ARTICLE";
        List<InternalRunContextDtos.RunningTask> running = activeTasks.stream()
                .map(this::workspaceRunningTask).toList();
        return new TaskWorkspaceStateSource.State(
                draft == null ? null : Long.toString(draft.id()),
                activeTask == null ? null : Long.toString(activeTask.id()),
                intent, constraints, running
        );
    }

    @Override
    @Transactional(readOnly = true)
    public String activeConfirmationDraftId(long userId, long conversationId) {
        List<Long> draftIds = mapper.findActiveWorkspaceDraftIds(userId, conversationId, now());
        return draftIds.size() == 1 ? Long.toString(draftIds.getFirst()) : null;
    }

    private InternalRunContextDtos.RunningTask workspaceRunningTask(TaskRecords.Task task) {
        String status = "QUEUED".equals(task.status()) ? "QUEUED" : "RUNNING";
        String stageCode;
        if ("QUEUED".equals(status)) {
            stageCode = "WAITING_TO_START";
        } else {
            stageCode = switch (task.taskType()) {
                case "GEO" -> "GEO_SYNTHESIZING".equals(task.stageCode())
                        ? "SUMMARIZING_RESULTS" : "COLLECTING_RESULTS";
                case "ARTICLE" -> "CREATING_ARTICLE";
                case "IMAGE" -> "CREATING_IMAGE";
                case "VIDEO" -> "CREATING_VIDEO";
                case "MEDIA" -> "SUBMITTING_MEDIA";
                default -> "PROCESSING";
            };
        }
        return new InternalRunContextDtos.RunningTask(
                Long.toString(task.id()), task.taskType(), status, stageCode
        );
    }

    @Transactional(propagation = Propagation.NESTED)
    public ConversationEventRecord acceptProposal(AgentRunRecord run, PythonChatRunEvent source,
                                                   String accumulatedText, OffsetDateTime now) {
        JsonNode proposal = source.payload();
        ValidatedProposal validatedProposal = validateProposal(run, proposal);
        TaskRecords.Draft existing = mapper.findDraftByRunId(run.id()).orElse(null);
        if (existing != null) return draftBrowserEvent(run, source, existing, accumulatedText, now);
        TaskRecords.Draft previous = lockChatRevisionCandidate(run, proposal, validatedProposal.taskType(), now);
        if ("IMAGE".equals(validatedProposal.taskType())) {
            validateChatVisualCopy(run, validatedProposal.parameters(), previous);
        }

        long draftId = ids.nextId();
        OffsetDateTime expires = now.plus(properties.getConfirmationTtl());
        ObjectNode parameters = validatedProposal.parameters();
        ObjectNode snapshot;
        int quote;
        if ("GEO".equals(validatedProposal.taskType())) {
            GeoTaskService.PreparedDraft prepared = requireGeoTasks().isQuestionDiscovery(parameters)
                    ? requireGeoTasks().prepareDiscoveryDraft(
                    run.userId(), run.selectedSpaceId(), run.selectedProductId(), parameters)
                    : requireGeoTasks().prepareDraft(
                    run.userId(), run.selectedSpaceId(), run.selectedProductId(), parameters);
            parameters = prepared.parameters();
            snapshot = prepared.snapshot();
            quote = requireGeoTasks().quote(prepared);
        } else if ("ARTICLE".equals(validatedProposal.taskType())) {
            validateChatArticleReportBinding(run, parameters, previous);
            ArticleService.PreparedTask prepared = previous == null
                    ? requireArticles().prepareChatTitleTask(run.userId(), run.selectedSpaceId(),
                    run.selectedProductId(), parameters)
                    : requireArticles().prepareChatTitleRevision(run.userId(), run.selectedSpaceId(),
                    run.selectedProductId(), parameters, read(previous.parametersJson()));
            parameters = prepared.parameters();
            snapshot = prepared.snapshot();
            quote = articleQuote(prepared.mode());
        } else {
            CreativeTaskSourceService.PreparedSource preparedSource = requireCreativeSources()
                    .prepareChat(run, validatedProposal.taskType(), parameters);
            validateProposedCreativeSource(proposal, preparedSource, run.requestMessageId());
            parameters = preparedSource.parameters();
            snapshot = json.createObjectNode();
            snapshot.put("taskType", validatedProposal.taskType());
            snapshot.put("parameterSchemaVersion", 1);
            snapshot.set("parameters", parameters);
            ObjectNode authorized = json.createObjectNode();
            authorized.setAll(preparedSource.authorizedContext());
            snapshot.set("authorizedContext", authorized);
            snapshot.set("productCreativeBrief", preparedSource.productCreativeBrief());
            quote = creativeQuote(validatedProposal.taskType(), parameters);
        }
        if (previous != null) validateChatRevisionSource(previous, parameters);
        String snapshotJson = canonical(snapshot);
        String inputHash = sha256(snapshotJson);
        TaskRecords.Draft draft = new TaskRecords.Draft(draftId, run.userId(), run.conversationId(), run.id(),
                previous == null ? null : previous.id(), "CHAT", null, null, null,
                validatedProposal.taskType(), expectedParameterSchemaVersion(validatedProposal.taskType()),
                run.selectedSpaceId(),
                "CURRENT_ATTACHMENTS".equals(parameters.path("sourceMode").asText()) ? null : run.selectedProductId(),
                canonical(parameters), snapshotJson, inputHash,
                properties.pricingVersionId(validatedProposal.taskType()), quote,
                canonical(proposal.path("warnings")),
                "WAITING_CONFIRMATION", expires, null, now, now);
        String token = confirmationToken(draft);
        TaskRecords.Confirmation confirmation = new TaskRecords.Confirmation(ids.nextId(), draftId,
                run.userId(), sha256(token), inputHash, draft.pricingVersionId(), "AVAILABLE",
                expires, null, now);
        if (mapper.insertDraft(draft) != 1 || mapper.insertConfirmation(confirmation) != 1) {
            throw new IllegalStateException("Unable to persist task draft atomically");
        }
        if(monitorExecution!=null) monitorExecution.registerProposal(run,draft,now);
        if (previous != null && (mapper.invalidateConfirmation(previous.id(), run.userId()) != 1
                || mapper.abandonDraft(previous.id(), run.userId(), now) != 1)) {
            // Any mutation failure rolls back the whole nested proposal, never only one version.
            throw new IllegalStateException("Unable to replace chat draft atomically");
        }
        return draftBrowserEvent(run, source, draft, accumulatedText, now);
    }

    private TaskRecords.Draft lockChatRevisionCandidate(AgentRunRecord run, JsonNode proposal,
                                                       String taskType, OffsetDateTime now) {
        JsonNode operation = proposal.get("draftOperation");
        if (operation != null && (!operation.isTextual()
                || !Set.of("NEW", "REVISE").contains(operation.textValue()))) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        if (operation == null || "NEW".equals(operation.textValue())) {
            if (proposal.hasNonNull("previousDraftId")) throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
            return null;
        }
        JsonNode previousId = proposal.path("previousDraftId");
        JsonNode frozenId = read(run.contextSnapshotJson()).path("taskWorkspace").path("activeDraftId");
        if (!previousId.isTextual() || !previousId.textValue().matches("^[1-9][0-9]{0,18}$")
                || !previousId.equals(frozenId)) throw new IllegalArgumentException("TASK_DRAFT_REVISION_STALE");
        long id;
        try { id = Long.parseLong(previousId.textValue()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID"); }
        // Same lock order as confirmation and form revision: draft, then its confirmation.
        TaskRecords.Draft previous = mapper.lockDraft(id, run.userId()).orElse(null);
        if (previous == null || previous.userId() != run.userId()
                || !java.util.Objects.equals(previous.conversationId(), run.conversationId())
                || !taskType.equals(previous.taskType()) || isWorkflowDraft(previous)
                || !Set.of("CHAT", "FORM").contains(previous.draftSource())
                || !java.util.Objects.equals(run.selectedSpaceId(), previous.spaceId())
                || !java.util.Objects.equals(run.selectedProductId(), previous.productId())
                || !"WAITING_CONFIRMATION".equals(previous.status()) || !now.isBefore(previous.expiresAt())
                || mapper.findTaskByDraft(previous.id()).isPresent()) {
            throw new IllegalArgumentException("TASK_DRAFT_REVISION_STALE");
        }
        TaskRecords.Confirmation confirmation = mapper.lockConfirmation(previous.id(), run.userId()).orElse(null);
        if (confirmation == null || !"AVAILABLE".equals(confirmation.status())
                || !now.isBefore(confirmation.expiresAt())) throw new IllegalArgumentException("TASK_DRAFT_REVISION_STALE");
        return previous;
    }

    private void validateChatRevisionSource(TaskRecords.Draft previous, JsonNode parameters) {
        JsonNode old = read(previous.parametersJson());
        // These identify the source/object, not editable presentation parameters such as count or scene.
        for (String field : List.of("sourceMode", "referenceAssetIds", "maskAssetId", "geoReportId",
                "geoRecommendationId", "articleId", "articleVersion", "topicId", "mode")) {
            if (!old.path(field).equals(parameters.path(field))) {
                throw new IllegalArgumentException("TASK_DRAFT_REVISION_SOURCE_MISMATCH");
            }
        }
    }

    private void validateChatVisualCopy(AgentRunRecord run, JsonNode parameters, TaskRecords.Draft previous) {
        JsonNode brief = parameters.path("visualBrief");
        if (!"USER_SUPPLIED".equals(brief.path("textPolicy").asText())) return;
        String userText = "";
        for (JsonNode message : read(run.contextSnapshotJson()).path("conversationMemory").path("messages")) {
            if (Long.toString(run.requestMessageId()).equals(message.path("messageId").asText())
                    && "USER".equals(message.path("role").asText())) {
                userText = message.path("content").path("text").asText("");
                break;
            }
        }
        JsonNode priorLines = previous == null ? json.createArrayNode()
                : read(previous.parametersJson()).path("visualBrief").path("copyLines");
        for (JsonNode line : brief.path("copyLines")) {
            boolean explicitlySupplied = userText.contains(line.textValue());
            if (!explicitlySupplied && priorLines.isArray()) {
                for (JsonNode prior : priorLines) {
                    if (line.equals(prior)) { explicitlySupplied = true; break; }
                }
            }
            if (!explicitlySupplied) throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
    }

    @Transactional(readOnly = true)
    public TaskDtos.DraftResponse getDraft(AuthenticatedAccount account, long draftId) {
        long userId = CatalogAccess.requireUser(account);
        TaskRecords.Draft draft = mapper.findDraft(draftId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return draftResponse(draft, tokenIfAvailable(draft));
    }

    /** Internal same-transaction projection used by server-owned workflow materialization. */
    public TaskDtos.DraftResponse getDraftForWorkflow(long draftId, long userId) {
        TaskRecords.Draft draft = mapper.findDraft(draftId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return draftResponse(draft, tokenIfAvailable(draft));
    }

    /** Locks pending/actually executing article work in the destination conversation only. */
    public Optional<TaskRecords.Draft> lockActiveArticleDraftInConversation(
            long userId, long conversationId, OffsetDateTime now) {
        return mapper.lockActiveArticleDraftInConversation(userId, conversationId, now);
    }

    public Optional<TaskRecords.Draft> findGeoArticleDraftByIdempotency(long userId, UUID key) {
        return mapper.findDraftByIdempotency(userId, GEO_ARTICLE_DRAFT_SCOPE, key);
    }

    public Optional<TaskRecords.Draft> lockGeoArticleDraftByIdentity(long userId, long reportId,
                                                                     String suggestionId) {
        return mapper.lockGeoArticleDraftByIdentity(userId, Long.toString(reportId),
                suggestionId == null ? "" : suggestionId);
    }

    public Optional<TaskRecords.Draft> lockLatestArticleDraftByArticle(long userId, long articleId) {
        return mapper.lockLatestArticleDraftByArticle(userId, Long.toString(articleId));
    }

    @Transactional
    public TaskDtos.DraftResponse createDraft(AuthenticatedAccount account, String idempotencyHeader,
                                               TaskDtos.CreateDraftRequest request) {
        long userId = CatalogAccess.requireUser(account);
        UUID key = uuid(idempotencyHeader);
        long conversationId = positiveLong(request.conversationId());
        long spaceId = positiveLong(request.spaceId());
        Long productId = optionalPositiveLong(request.productId());
        Long sourceMessageId = optionalPositiveLong(request.sourceMessageId());
        conversationMapper.findConversation(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireParameterSchemaVersion(request.taskType(), request.parameterSchemaVersion());
        ObjectNode parameters = browserParameters(request.taskType(), request.parameters());
        String requestHash = draftRequestHash("TASK_DRAFT_CREATE", null, conversationId,
                request.taskType(), spaceId, productId, sourceMessageId,
                request.parameterSchemaVersion(), parameters);
        TaskRecords.Draft replay = replayDraft(userId, "TASK_DRAFT_CREATE", key, requestHash);
        if (replay != null) return draftResponse(replay, tokenIfAvailable(replay));
        TaskRecords.Draft draft = newFormDraft(userId, conversationId, null, request.taskType(),
                spaceId, productId, sourceMessageId, parameters, "TASK_DRAFT_CREATE", key, requestHash);
        persistDraftAndConfirmation(draft);
        return draftResponse(draft, confirmationToken(draft));
    }

    /** Materializes the server-authorized GEO article TITLE draft without confirming or charging it. */
    @Transactional
    public TaskDtos.DraftResponse createGeoArticleDraft(long userId, long conversationId, long spaceId,
                                                        Long productId, long articleId, long reportId,
                                                        String brief, UUID idempotencyKey,
                                                        String suggestionId, String stableRequestHash) {
        conversationMapper.findConversation(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        ObjectNode raw = json.createObjectNode();
        raw.put("mode", "TITLE");
        raw.put("articleId", Long.toString(articleId));
        raw.put("articleVersion", 1);
        raw.put("brief", brief);
        raw.putNull("selectedTitle");
        raw.putArray("confirmedOutline");
        raw.putNull("confirmedPlan");
        raw.putNull("rewriteInstruction");
        raw.putArray("rewriteBlockIds");
        raw.put("targetLength", "MEDIUM");
        raw.put("tone", "PROFESSIONAL");
        raw.putNull("targetPlatform");
        raw.putArray("sourceAssetIds");
        raw.put("geoReportId", Long.toString(reportId));
        if (suggestionId == null) raw.putNull("geoRecommendationId");
        else raw.put("geoRecommendationId", suggestionId);
        ObjectNode parameters = browserParameters("ARTICLE", raw);
        String requestHash = stableRequestHash == null
                ? draftRequestHash(GEO_ARTICLE_DRAFT_SCOPE, null, conversationId,
                "ARTICLE", spaceId, productId, null, 2, parameters)
                : stableRequestHash;
        TaskRecords.Draft replay = replayDraft(userId, GEO_ARTICLE_DRAFT_SCOPE, idempotencyKey, requestHash);
        if (replay != null) return draftResponse(replay, tokenIfAvailable(replay));
        TaskRecords.Draft draft = newFormDraft(userId, conversationId, null, "ARTICLE", spaceId,
                productId, null, parameters, GEO_ARTICLE_DRAFT_SCOPE, idempotencyKey, requestHash);
        persistDraftAndConfirmation(draft);
        return draftResponse(draft, confirmationToken(draft));
    }

    /** Direction-only revision for the existing GEO TITLE entry; no source field comes from the client. */
    @Transactional
    public TaskDtos.DraftResponse reviseGeoArticleDraft(AuthenticatedAccount account, long draftId,
            UUID idempotencyKey, String brief, String stableRequestHash) {
        long userId = CatalogAccess.requireUser(account);
        if (idempotencyKey == null || brief == null || brief.isBlank() || brief.length() > 4000
                || stableRequestHash == null || !stableRequestHash.matches("^[0-9a-f]{64}$"))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        TaskRecords.Draft current = mapper.lockDraft(draftId,userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        JsonNode original = read(current.parametersJson());
        if (isWorkflowDraft(current) || !"ARTICLE".equals(current.taskType())
                || current.parameterSchemaVersion()!=2 || !"TITLE".equals(original.path("mode").asText())
                || !original.path("geoReportId").isTextual())
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        TaskRecords.Draft replay = replayDraft(userId,GEO_ARTICLE_DRAFT_SCOPE,idempotencyKey,stableRequestHash);
        if (replay != null) return draftResponse(replay,tokenIfAvailable(replay));
        OffsetDateTime now = now();
        if (!Set.of("WAITING_CONFIRMATION","EDITING").contains(current.status()))
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        if (!now.isBefore(current.expiresAt())) throw new BusinessException(ErrorCode.DRAFT_EXPIRED);
        ObjectNode parameters = (ObjectNode)original.deepCopy();
        parameters.put("brief",brief);
        parameters = browserParameters("ARTICLE",parameters);
        TaskRecords.Draft revision = newFormDraft(userId,current.conversationId(),current.id(),"ARTICLE",
                current.spaceId(),current.productId(),null,parameters,GEO_ARTICLE_DRAFT_SCOPE,idempotencyKey,stableRequestHash);
        if (mapper.invalidateConfirmation(current.id(),userId)>1 || mapper.abandonDraft(current.id(),userId,now)!=1)
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        persistDraftAndConfirmation(revision);
        return draftResponse(revision,confirmationToken(revision));
    }

    @Transactional
    public WorkflowPreparedDraft prepareWorkflowDraft(long userId, long conversationId,
                                                       String taskType, long spaceId, Long productId,
                                                       int parameterSchemaVersion, JsonNode rawParameters,
                                                       UUID idempotencyKey) {
        if (!Set.of("IMAGE", "VIDEO", "GEO", "ARTICLE").contains(taskType)
                || parameterSchemaVersion != expectedParameterSchemaVersion(taskType)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        conversationMapper.findConversation(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        ObjectNode parameters = browserParameters(taskType, rawParameters);
        if (Set.of("TEXT_ONLY", "SELECTED_ASSETS", "PRODUCT_MATERIALS", "GEO_REPORT", "REGION_EDIT")
                .contains(parameters.path("sourceMode").asText())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if ("ARTICLE".equals(taskType) && (!"AGENT_ASSISTED".equals(parameters.path("mode").asText())
                || parameters.has("geoReportId"))) {
            // A plan prepares a new title stage, not a model-selected article or report workflow.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String scope = "WORKFLOW_PLAN_DRAFT_CREATE";
        String requestHash = draftRequestHash(scope, null, conversationId, taskType,
                spaceId, productId, null, parameterSchemaVersion, parameters);
        TaskRecords.Draft replay = replayDraft(userId, scope, idempotencyKey, requestHash);
        if (replay != null) return workflowPreparedDraft(replay);

        // Replay must be checked before materializing a Java-owned article workspace. The outer
        // plan transaction also owns the article so a later rejected step rolls back all drafts.
        TaskRecords.Draft base = "ARTICLE".equals(taskType)
                ? newArticleDraft(userId, conversationId, null, spaceId, productId,
                        requireArticles().prepareChatTitleTask(userId, spaceId, productId, parameters),
                        scope, idempotencyKey, requestHash)
                : newFormDraft(userId, conversationId, null, taskType,
                        spaceId, productId, null, parameters, scope, idempotencyKey, requestHash);
        TaskRecords.Draft draft = new TaskRecords.Draft(base.id(), base.userId(), base.conversationId(), null,
                null, "PLAN", scope, idempotencyKey, requestHash, base.taskType(),
                base.parameterSchemaVersion(), base.spaceId(), base.productId(), base.parametersJson(),
                base.inputSnapshotJson(), base.inputHash(), base.pricingVersionId(), base.quotedPoints(),
                base.warningsJson(), base.status(), base.expiresAt(), null, base.createdAt(), base.updatedAt());
        persistDraftAndConfirmation(draft);
        return workflowPreparedDraft(draft);
    }

    /** Fixed parent is already funded; later stages materialize only this same Java-owned article. */
    @Transactional
    public WorkflowPreparedDraft prepareFullArticleDraft(long userId,long conversationId,long spaceId,
            Long productId,ArticleService.PreparedTask prepared,JsonNode frozenStage,JsonNode parent,
            UUID key,OffsetDateTime expiresAt) {
        if(!Set.of("TITLE","OUTLINE","BODY").contains(prepared.mode()) || !expiresAt.isAfter(now()))
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        String requestHash=sha256(canonical(prepared.parameters())+"|"+canonical(parent));
        TaskRecords.Draft replay=replayDraft(userId,"WORKFLOW_PLAN_DRAFT_CREATE",key,requestHash);
        if(replay!=null) return workflowPreparedDraft(replay);
        prepared.snapshot().set("articleFull",parent.deepCopy());
        prepared.snapshot().set("executionBudget",frozenStage.deepCopy());
        String snapshotJson=canonical(prepared.snapshot());
        if(snapshotJson.getBytes(StandardCharsets.UTF_8).length>262144) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        OffsetDateTime now=now();
        TaskRecords.Draft draft=new TaskRecords.Draft(ids.nextId(),userId,conversationId,null,null,
                "PLAN","WORKFLOW_PLAN_DRAFT_CREATE",key,requestHash,"ARTICLE",2,spaceId,productId,
                canonical(prepared.parameters()),snapshotJson,sha256(snapshotJson),
                parent.path("pricingVersionId").asLong(),parent.path("quotedPoints").asInt(),"[]",
                "WAITING_CONFIRMATION",expiresAt,null,now,now);
        persistDraftAndConfirmation(draft);
        return workflowPreparedDraft(draft);
    }

    @Transactional
    public void abandonWorkflowDrafts(long userId, List<Long> draftIds, OffsetDateTime now) {
        for (Long draftId : draftIds) {
            TaskRecords.Draft draft = mapper.lockDraft(draftId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
            if (!"PLAN".equals(draft.draftSource())
                    || !"WORKFLOW_PLAN_DRAFT_CREATE".equals(draft.operationScope())) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            if ("ABANDONED".equals(draft.status())) continue;
            if (!("WAITING_CONFIRMATION".equals(draft.status()) || "EDITING".equals(draft.status()))) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            mapper.invalidateConfirmation(draftId, userId);
            if (mapper.abandonDraft(draftId, userId, now) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
        }
    }

    public void verifyWorkflowDrafts(long userId, long conversationId,
                                     List<WorkflowDraftExpectation> expectations,
                                     OffsetDateTime now) {
        Set<Long> uniqueDrafts = new HashSet<>();
        for (WorkflowDraftExpectation expected : expectations) {
            if (!uniqueDrafts.add(expected.draftId())) {
                throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
            }
            TaskRecords.Draft draft = mapper.lockDraft(expected.draftId(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH));
            boolean matches = isWorkflowDraft(draft)
                    && draft.conversationId() != null && draft.conversationId() == conversationId
                    && "WAITING_CONFIRMATION".equals(draft.status())
                    && now.isBefore(draft.expiresAt())
                    && expected.taskType().equals(draft.taskType())
                    && expected.pricingVersionId() == draft.pricingVersionId()
                    && expected.quotedPoints() == draft.quotedPoints();
            if (!matches) {
                throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
            }
        }
    }

    @Transactional
    public TaskDtos.DraftResponse reviseDraft(AuthenticatedAccount account, long draftId,
                                               String idempotencyHeader,
                                               TaskDtos.ReviseDraftRequest request) {
        long userId = CatalogAccess.requireUser(account);
        UUID key = uuid(idempotencyHeader);
        long spaceId = positiveLong(request.spaceId());
        Long productId = optionalPositiveLong(request.productId());
        Long sourceMessageId = optionalPositiveLong(request.sourceMessageId());
        TaskRecords.Draft current = mapper.lockDraft(draftId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (isWorkflowDraft(current)) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        requireParameterSchemaVersion(current.taskType(), request.parameterSchemaVersion());
        ObjectNode parameters = browserParameters(current.taskType(), request.parameters());
        String requestHash = draftRequestHash("TASK_DRAFT_REVISION", draftId, current.conversationId(),
                current.taskType(), spaceId, productId, sourceMessageId,
                request.parameterSchemaVersion(), parameters);
        TaskRecords.Draft replay = replayDraft(userId, "TASK_DRAFT_REVISION", key, requestHash);
        if (replay != null) return draftResponse(replay, tokenIfAvailable(replay));
        OffsetDateTime now = now();
        if (!("WAITING_CONFIRMATION".equals(current.status()) || "EDITING".equals(current.status()))) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (!now.isBefore(current.expiresAt())) {
            throw new BusinessException(ErrorCode.DRAFT_EXPIRED);
        }
        TaskRecords.Draft revision = newFormDraft(userId, current.conversationId(), current.id(),
                current.taskType(), spaceId, productId, sourceMessageId, parameters,
                "TASK_DRAFT_REVISION", key, requestHash);
        if (mapper.invalidateConfirmation(current.id(), userId) > 1
                || mapper.abandonDraft(current.id(), userId, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        persistDraftAndConfirmation(revision);
        return draftResponse(revision, confirmationToken(revision));
    }

    @Transactional
    public TaskDtos.DraftResponse abandonDraft(AuthenticatedAccount account, long draftId) {
        long userId = CatalogAccess.requireUser(account);
        TaskRecords.Draft draft = mapper.lockDraft(draftId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (isWorkflowDraft(draft)) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if ("ABANDONED".equals(draft.status())) {
            return draftResponse(draft, null);
        }
        if (!("WAITING_CONFIRMATION".equals(draft.status()) || "EDITING".equals(draft.status()))) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        OffsetDateTime now = now();
        mapper.invalidateConfirmation(draftId, userId);
        if (mapper.abandonDraft(draftId, userId, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return draftResponse(new TaskRecords.Draft(
                draft.id(), draft.userId(), draft.conversationId(), draft.agentRunId(),
                draft.previousDraftId(), draft.draftSource(), draft.operationScope(), draft.idempotencyKey(),
                draft.requestHash(), draft.taskType(), draft.parameterSchemaVersion(), draft.spaceId(),
                draft.productId(), draft.parametersJson(), draft.inputSnapshotJson(), draft.inputHash(),
                draft.pricingVersionId(), draft.quotedPoints(), draft.warningsJson(), "ABANDONED",
                draft.expiresAt(), draft.confirmedAt(), draft.createdAt(), now), null);
    }

    public ConfirmedTask confirm(AuthenticatedAccount account, long draftId, String idempotencyHeader,
                                 TaskDtos.ConfirmRequest request) {
        return observability.observe("java.task.confirm", "CONFIRM", span -> {
            if (account != null) span.tag("userIdHash", observability.reference(account.id()));
        }, () -> confirmInternal(account, draftId, idempotencyHeader, request));
    }

    /**
     * Legacy unversioned shortcut, retained only to fail closed for old clients.
     * A conversational "confirm" is not evidence that the user saw the current quote.
     * Clients must use confirm with an explicit draft ID and the displayed confirmation proof.
     */
    public ConfirmedTask confirmActiveConversationDraft(
            AuthenticatedAccount account,
            long conversationId,
            String idempotencyHeader
    ) {
        CatalogAccess.requireUser(account);
        throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
    }

    /**
     * Materializes a plan-authorized PREPARE draft without charging the account a second time.
     * The caller owns the workflow execution fence and workflow reservation row lock; this method
     * deliberately reuses the existing Task, task reservation, event and Outbox control plane.
     */
    @Transactional
    public TaskRecords.Task activateWorkflowTask(WorkflowTaskActivation activation) {
        TaskRecords.Task replay = mapper.findTaskByOperationIdempotency(
                activation.userId(), "WORKFLOW_STEP_ACTIVATE", activation.eventId()).orElse(null);
        if (replay != null) {
            if (!"WORKFLOW_STEP_ACTIVATE".equals(replay.operationScope())
                    || !constantEquals(replay.requestHash(), activation.requestHash())
                    || replay.draftId() == null || replay.draftId() != activation.draftId()) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return replay;
        }
        TaskRecords.Draft draft = mapper.lockDraft(activation.draftId(), activation.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH));
        if ("IMAGE".equals(draft.taskType()) || "VIDEO".equals(draft.taskType())) {
            requireCreativeSources().revalidate(draft);
        }
        TaskRecords.Task existing = mapper.findTaskByDraft(draft.id()).orElse(null);
        if (existing != null) {
            if (!"WORKFLOW_STEP_ACTIVATE".equals(existing.operationScope())
                    || !constantEquals(existing.requestHash(), activation.requestHash())) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            return existing;
        }
        OffsetDateTime now = activation.now();
        int expectedQuote = expectedQuote(draft);
        boolean matches = isWorkflowDraft(draft)
                && draft.conversationId() != null
                && draft.conversationId() == activation.conversationId()
                && draft.spaceId() == activation.spaceId()
                && java.util.Objects.equals(draft.productId(), activation.productId())
                && draft.taskType().equals(activation.taskType())
                && draft.pricingVersionId() == activation.pricingVersionId()
                && draft.quotedPoints() == activation.quotedPoints()
                && draft.quotedPoints() == expectedQuote
                && constantEquals(draft.inputHash(), activation.authorizedInputHash())
                && draft.expiresAt().equals(activation.authorizedDraftExpiresAt())
                && "WAITING_CONFIRMATION".equals(draft.status())
                // Quote validity is fixed at the explicit, funded plan start, not at each later step.
                && activation.planStartedAt() != null && !activation.planStartedAt().isAfter(now)
                && activation.planStartedAt().isBefore(draft.expiresAt());
        if (!matches) throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);

        long taskId = ids.nextId();
        TaskRecords.Task task = new TaskRecords.Task(taskId, draft.userId(), draft.spaceId(), draft.productId(),
                draft.conversationId(), draft.id(), draft.taskType(), "QUEUED", "QUEUED",
                expectedParameterSchemaVersion(draft.taskType()),
                draft.inputSnapshotJson(), draft.inputHash(), draft.pricingVersionId(), draft.quotedPoints(),
                0, "{}", null, "WORKFLOW_STEP_ACTIVATE", activation.eventId(), activation.requestHash(),
                now, null, null, now, now, null, null);
        boolean automaticArticle = "ARTICLE".equals(draft.taskType())
                && "ARTICLE_FULL_V1".equals(read(draft.inputSnapshotJson()).path("articleFull").path("templateCode").asText());
        if (mapper.invalidateConfirmation(draft.id(), draft.userId()) != 1
                || (automaticArticle ? mapper.admitFullArticleDraft(draft.id(),draft.userId(),now)
                    : mapper.confirmDraft(draft.id(), draft.userId(), now)) != 1
                || mapper.insertTask(task) != 1
                || mapper.insertReservation(ids.nextId(), draft.userId(), taskId,
                draft.quotedPoints(), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if ("GEO".equals(task.taskType())) requireGeoTasks().createRun(task, now);
        if ("ARTICLE".equals(task.taskType())) requireArticles().markGenerationStarted(task, now);
        mapper.insertTaskEvent(ids.nextId(), taskId, draft.userId(), null, null, null,
                null, "QUEUED", "TASK_QUEUED", "JAVA", null, null,
                canonical(Map.of("workflowPlanId", Long.toString(activation.planId()),
                        "workflowStepId", activation.stepKey())), now);
        insertExecutionOutbox(task, 1, now);
        insertConversationEvent(task, "task.queued",
                json.valueToTree(Map.of("taskId", Long.toString(taskId), "status", "QUEUED")), now);
        return task;
    }

    private ConfirmedTask confirmInternal(AuthenticatedAccount account, long draftId, String idempotencyHeader,
                                          TaskDtos.ConfirmRequest request) {
        long userId = CatalogAccess.requireUser(account);
        UUID key = uuid(idempotencyHeader);
        long requestedPriceVersion = positiveLong(request.pricingVersionId());
        String requestHash = sha256(canonical(Map.of("draftId", Long.toString(draftId),
                "token", request.confirmationToken(), "inputHash", request.inputHash(),
                "pricingVersionId", request.pricingVersionId())));
        TaskRecords.Task byKey = mapper.findTaskByIdempotency(userId, key).orElse(null);
        if (byKey != null) {
            if (!MessageDigest.isEqual(byKey.requestHash().getBytes(StandardCharsets.US_ASCII),
                    requestHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return new ConfirmedTask(taskResponse(byKey), null);
        }
        if (transactions == null) {
            return confirmTransaction(userId, draftId, key, requestedPriceVersion, request, requestHash);
        }
        return transactions.execute(status ->
                confirmTransaction(userId, draftId, key, requestedPriceVersion, request, requestHash));
    }

    private ConfirmedTask confirmTransaction(long userId, long draftId, UUID key,
                                               long requestedPriceVersion,
                                               TaskDtos.ConfirmRequest request, String requestHash) {
        TaskRecords.Task byKey = mapper.findTaskByIdempotency(userId, key).orElse(null);
        if (byKey != null) {
            if (!MessageDigest.isEqual(byKey.requestHash().getBytes(StandardCharsets.US_ASCII),
                    requestHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return new ConfirmedTask(taskResponse(byKey), null);
        }
        TaskRecords.Task byDraft=mapper.findTaskByDraft(draftId).orElse(null);
        if(byDraft!=null&&byDraft.userId()==userId) return new ConfirmedTask(taskResponse(byDraft),null);
        // The already-created task replays above survive later disable. New acceptance
        // takes the foreground account gate before draft/installation locks.
        if(monitorMapper!=null&&monitorMapper.shareRunnableAccount(userId)==null) throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        TaskRecords.Draft draft = mapper.lockDraft(draftId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (isWorkflowDraft(draft)) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        TaskRecords.Task already = mapper.findTaskByDraft(draftId).orElse(null);
        if (already != null) return new ConfirmedTask(taskResponse(already), null);
        if (agentPlatformService != null && draft.conversationId() != null) {
            // Do not acquire the conversation row before point_account: result settlement
            // locks points then appends a conversation event. Confirmation admission reads
            // the immutable binding and locks only its installation/definition.
            var identity = agentPlatformService.assertTaskConfirmationAllowed(userId, draft.conversationId());
            if (!identity.capabilities().contains(taskCapability(draft.taskType()))) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
        }
        OffsetDateTime now = now();
        if (!"WAITING_CONFIRMATION".equals(draft.status()) || !now.isBefore(draft.expiresAt())) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_EXPIRED);
        }
        TaskRecords.Confirmation confirmation = mapper.lockConfirmation(draftId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_CONFIRMATION_EXPIRED));
        if ("IMAGE".equals(draft.taskType()) || "VIDEO".equals(draft.taskType())) {
            requireCreativeSources().revalidate(draft);
        }
        JsonNode draftParameters = read(draft.parametersJson());
        int expectedQuote = "GEO".equals(draft.taskType())
                ? requireGeoTasks().quoteDraft(
                userId, draft.spaceId(), draft.productId(), draftParameters)
                : "ARTICLE".equals(draft.taskType())
                ? articleQuote(draftParameters.path("mode").textValue())
                : "MEDIA".equals(draft.taskType()) ? draft.quotedPoints()
                : creativeQuote(draft.taskType(), draftParameters);
        if ("ARTICLE".equals(draft.taskType()) && !properties.isArticleExecutionEnabled()) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        boolean matches = "AVAILABLE".equals(confirmation.status()) && now.isBefore(confirmation.expiresAt())
                && constantEquals(confirmation.tokenHash(), sha256(request.confirmationToken()))
                && constantEquals(draft.inputHash(), request.inputHash())
                && requestedPriceVersion == draft.pricingVersionId()
                && ("MEDIA".equals(draft.taskType())
                    || requestedPriceVersion == properties.pricingVersionId(draft.taskType()))
                && draft.quotedPoints() == expectedQuote;
        if (!matches) throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        if ("MEDIA".equals(draft.taskType())) {
            // Local catalogue rows stay locked until confirmation commits. A resource callback
            // cannot change the quoted version between validation and freezing points.
            requireMediaSubmissions().revalidateForConfirmation(draft);
        }
        TaskRecords.PointAccount accountRow = mapper.lockPointAccount(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_POINTS));
        if (accountRow.availablePoints() < draft.quotedPoints()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS);
        }
        String taskSnapshotJson = draft.inputSnapshotJson();
        String taskInputHash = draft.inputHash();
        if ("GEO".equals(draft.taskType()) && requireGeoTasks().isQuestionDiscovery(draftParameters)) {
            GeoTaskService.PreparedDraft executable = requireGeoTasks().materializeDiscoveryDraft(
                    userId, draft.spaceId(), draft.productId(), draftParameters);
            taskSnapshotJson = canonical(executable.snapshot());
            taskInputHash = sha256(taskSnapshotJson);
        }
        long taskId = ids.nextId();
        TaskRecords.Task task = new TaskRecords.Task(taskId, userId, draft.spaceId(), draft.productId(),
                draft.conversationId(), draft.id(), draft.taskType(), "QUEUED", "QUEUED",
                expectedParameterSchemaVersion(draft.taskType()),
                taskSnapshotJson, taskInputHash, draft.pricingVersionId(), draft.quotedPoints(),
                0, "{}", null, "TASK_CONFIRM", key, requestHash, now, null, null, now, now, null, null);
        if (mapper.consumeConfirmation(draftId, userId, now) != 1
                || mapper.confirmDraft(draftId, userId, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return persistAcceptedTask(task,draft,accountRow,now);
    }

    /** Shared side-effect block. Caller owns draft and points; user-confirm and preauth keep distinct truth. */
    private ConfirmedTask persistAcceptedTask(TaskRecords.Task task,TaskRecords.Draft draft,
            TaskRecords.PointAccount accountRow,OffsetDateTime now) {
        return persistAcceptedTask(task,draft,accountRow,now,()->{});
    }

    private ConfirmedTask persistAcceptedTask(TaskRecords.Task task,TaskRecords.Draft draft,
            TaskRecords.PointAccount accountRow,OffsetDateTime now,Runnable beforeConversationEvent) {
        long userId=task.userId();long taskId=task.id();
        if(mapper.insertTask(task)!=1||freezePoints(userId,taskId,draft.taskType(),draft.quotedPoints(),now)!=1
                ||mapper.insertReservation(ids.nextId(),userId,taskId,draft.quotedPoints(),now)!=1) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        if ("GEO".equals(task.taskType())) requireGeoTasks().createRun(task, now);
        if ("ARTICLE".equals(task.taskType())) requireArticles().markGenerationStarted(task, now);
        int availableAfter = accountRow.availablePoints() - draft.quotedPoints();
        int frozenAfter = accountRow.frozenPoints() + draft.quotedPoints();
        mapper.insertLedger(ids.nextId(), userId, -draft.quotedPoints(), draft.quotedPoints(),
                availableAfter, frozenAfter, "TASK_FREEZE", taskId, "task-freeze:" + taskId,
                draft.taskType() + " task " + task.operationScope(), now);
        mapper.insertTaskEvent(ids.nextId(), taskId, userId, null, null, null,
                null, "QUEUED", "TASK_QUEUED", "JAVA", null, null, "{}", now);
        if ("MEDIA".equals(task.taskType())) requireMediaSubmissions().createConfirmedBatch(task, draft, now);
        else insertExecutionOutbox(task, 1, now);
        beforeConversationEvent.run();
        ConversationEventRecord browserEvent = task.conversationId() == null ? null
                : insertConversationEvent(task, "task.queued",
                json.valueToTree(Map.of("taskId", Long.toString(taskId), "status", "QUEUED")), now);
        return new ConfirmedTask(taskResponse(task), browserEvent);
    }

    /** Each tick is one durable admission transaction; no model or provider call in this method. */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Optional<MonitorAdmissionResult> admitNextMonitorAction() {
        if(monitorExecution==null||monitorMapper==null) return Optional.empty();
        var admission=monitorMapper.lockNextAdmission().orElse(null);
        if(admission==null) return Optional.empty();
        var priorTask=mapper.findTaskByDraft(admission.draftId()).orElse(null);
        if(priorTask!=null) return replayMonitorAdmission(admission,priorTask);
        // Queueing an installation writer makes subsequent SHARE requests wait. Acquire
        // the same account gate as foreground messages before ANY draft/identity locks.
        if(monitorMapper.shareRunnableAccount(admission.userId())==null) {
            if(monitorMapper.refuseAdmission(admission.id(),"ACCOUNT_UNAVAILABLE",monitorExecution.accountingNow())!=1) throw new IllegalStateException("Admission refusal changed");
            return Optional.of(new MonitorAdmissionResult(null));
        }
        var draft=mapper.lockDraft(admission.draftId(),admission.userId()).orElseThrow();
        var already=mapper.findTaskByDraft(draft.id()).orElse(null);
        if(already!=null) return replayMonitorAdmission(admission,already);
        var run=conversationMapper.findRun(admission.runId()).orElseThrow();
        com.guanghe.backend.agentplatform.MonitorExecutionService.Permit permit;
        try { permit=monitorExecution.validateAdmission(admission,draft,run); }
        catch(com.guanghe.backend.agentplatform.MonitorExecutionService.Refusal refused) {
            if(monitorMapper.refuseAdmission(admission.id(),refused.reason().name(),monitorExecution.accountingNow())!=1) throw new IllegalStateException("Admission refusal changed");
            return Optional.of(new MonitorAdmissionResult(null));
        }
        // Acquire the old confirmation row before the wallet. Never mark it CONSUMED.
        mapper.lockConfirmation(draft.id(),draft.userId()).orElseThrow();
        var points=mapper.lockPointAccount(admission.userId()).orElse(null);
        OffsetDateTime accountingAt=monitorExecution.accountingNow();
        try { monitorExecution.validateBudget(permit,draft,points,accountingAt); }
        catch(com.guanghe.backend.agentplatform.MonitorExecutionService.Refusal refused) {
            if(monitorMapper.refuseAdmission(admission.id(),refused.reason().name(),accountingAt)!=1) throw new IllegalStateException("Admission refusal changed");
            return Optional.of(new MonitorAdmissionResult(null));
        }
        long taskId=ids.nextId();
        UUID key=UUID.nameUUIDFromBytes(("monitor-admission:"+admission.id()).getBytes(StandardCharsets.UTF_8));
        var task=new TaskRecords.Task(taskId,draft.userId(),draft.spaceId(),draft.productId(),draft.conversationId(),draft.id(),
                "GEO","QUEUED","QUEUED",1,draft.inputSnapshotJson(),draft.inputHash(),draft.pricingVersionId(),draft.quotedPoints(),
                0,"{}",null,"EXECUTION_PREAUTH",key,admission.intentHash(),accountingAt,null,null,accountingAt,accountingAt,null,null);
        if(mapper.invalidateConfirmation(draft.id(),draft.userId())!=1||mapper.admitMonitorDraft(draft.id(),draft.userId(),accountingAt)!=1) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        ConfirmedTask accepted=persistAcceptedTask(task,draft,points,accountingAt,()->{
            if(monitorMapper.admit(admission.id(),taskId,"EXECUTION_PREAUTH",accountingAt)!=1) throw new IllegalStateException("Unable to commit monitor admission");
        });
        return Optional.of(new MonitorAdmissionResult(accepted.browserEvent()));
    }

    public record MonitorAdmissionResult(ConversationEventRecord browserEvent) { }

    public record AgentGeoAction(TaskDtos.TaskResponse task, String topicId, boolean reused) { }

    public record DiagnosticBudget(String blockedReason,Integer availablePoints,Integer maxPointsPerAction,Integer maxPointsPerDay) { }

    /** Read-only preview of the same personal policy used by actual admission; never raises limits. */
    public DiagnosticBudget diagnosticBudget(long user,int quote) {
        if(monitorMapper==null || platformProperties==null)return new DiagnosticBudget("BUDGET_UNAVAILABLE",null,null,null);
        var policy=monitorMapper.sharePolicy(user).orElse(null);
        int action=policy==null?platformProperties.getNativeActionMaxPointsPerAction():policy.maxPointsPerAction();
        int daily=policy==null?platformProperties.getNativeActionMaxPointsPerDay():policy.maxPointsPerDay();
        var points=mapper.findPointAccount(user).orElse(null);
        String reason=null;
        var at=OffsetDateTime.ofInstant(accountingClock.instant(),ZoneOffset.UTC);
        var zone=java.time.ZoneId.of("Asia/Shanghai");var day=at.atZoneSameInstant(zone).toLocalDate();
        if(policy!=null&&!policy.enabled())reason="BUDGET_DISABLED";
        else if(action<1||daily<action)reason="BUDGET_UNAVAILABLE";
        else if(policy!=null&&day.isBefore(policy.effectiveShanghaiDate()))reason="BUDGET_NOT_EFFECTIVE";
        else if(quote>action)reason="SINGLE_ACTION_LIMIT";
        else if(points==null||points.availablePoints()<quote)reason="INSUFFICIENT_POINTS";
        else {
            long spent=monitorMapper.consumedPoints(user,day.atStartOfDay(zone).toOffsetDateTime(),day.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
            if(spent+(long)points.frozenPoints()+quote>daily)reason="DAY_LIMIT";
        }
        return new DiagnosticBudget(reason,points==null?null:points.availablePoints(),action,daily);
    }

    /** A real user pressed the diagnostic quote card. FORM/ADMITTED records that authority,
     * not a fabricated chat Run, consumed confirmation token, or confirmed_at timestamp. */
    @Transactional
    public TaskDtos.TaskResponse admitGeoDiagnostic(AuthenticatedAccount account,long conversation,long project,
            GeoTaskService.PreparedDraft prepared,UUID requestId,String requestHash,int quote) {
        long user=CatalogAccess.requireUser(account);
        if(!properties.isGeoDiagnosticSubmissionEnabled()||!properties.isGeoExecutionEnabled())throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        var replay=mapper.findTaskByOperationIdempotency(user,"GEO_DIAGNOSTIC_SUBMIT",requestId).orElse(null);
        if(replay!=null){if(!constantEquals(replay.requestHash(),requestHash))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);return taskResponse(replay);}
        if(monitorMapper==null||monitorMapper.shareRunnableAccount(user)==null)throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        var identity=agentPlatformService.assertMonitorExecutionAllowed(user,conversation);
        if(identity.projectId()==null||Long.parseLong(identity.projectId())!=project||!"geo".equals(identity.agentKey())
            ||!"DIAGNOSTIC_RUN".equals(prepared.parameters().path("mode").asText()))throw new BusinessException(ErrorCode.FORBIDDEN);
        var points=mapper.lockPointAccount(user).orElseThrow(()->new BusinessException(ErrorCode.INSUFFICIENT_POINTS));
        replay=mapper.findTaskByOperationIdempotency(user,"GEO_DIAGNOSTIC_SUBMIT",requestId).orElse(null);
        if(replay!=null){if(!constantEquals(replay.requestHash(),requestHash))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);return taskResponse(replay);}
        var budget=diagnosticBudget(user,quote);
        if(budget.blockedReason()!=null)throw agentBudgetRefusal(budget.blockedReason(),"本次诊断未受理，请减少问题或平台，或检查账户执行额度。");
        if(requireGeoTasks().quote(prepared)!=quote)throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        var time=OffsetDateTime.ofInstant(accountingClock.instant(),ZoneOffset.UTC);String snapshot=canonical(prepared.snapshot());
        var draft=new TaskRecords.Draft(ids.nextId(),user,conversation,null,null,"FORM","GEO_DIAGNOSTIC_SUBMIT",requestId,requestHash,
            "GEO",1,project,null,canonical(prepared.parameters()),snapshot,sha256(snapshot),properties.pricingVersionId("GEO"),quote,"[]",
            "WAITING_CONFIRMATION",time.plus(properties.getConfirmationTtl()),null,time,time);
        if(mapper.insertDraft(draft)!=1||mapper.admitMonitorDraft(draft.id(),user,time)!=1)throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        var task=new TaskRecords.Task(ids.nextId(),user,project,null,conversation,draft.id(),"GEO","QUEUED","QUEUED",1,snapshot,draft.inputHash(),
            draft.pricingVersionId(),quote,0,"{}",null,"GEO_DIAGNOSTIC_SUBMIT",requestId,requestHash,time,null,null,time,time,null,null);
        return persistAcceptedTask(task,draft,points,time).response();
    }

    /** Native loop admission. Authenticated USER run and bounded account budget are the authority;
     * no browser confirmation, model-supplied prices, or legacy executionIntent are fabricated. */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AgentGeoAction admitAgentGeoAction(UUID runId,long projectId,ObjectNode parameters) {
        AgentRunRecord run=conversationMapper.findRun(runId)
                .orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        long user=run.userId();
        if (monitorMapper==null || platformProperties==null || agentPlatformService==null)
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        if (monitorMapper.shareRunnableAccount(user)==null) throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        var identity=agentPlatformService.assertMonitorExecutionAllowed(user,run.conversationId());
        if(identity.projectId()!=null && Long.parseLong(identity.projectId())!=projectId)
            throw new BusinessException(ErrorCode.FORBIDDEN);
        var selection=conversationMapper.findConversation(run.conversationId(),user).orElseThrow();
        if(!java.util.Objects.equals(selection.selectedSpaceId(),projectId)) throw new BusinessException(ErrorCode.FORBIDDEN);
        GeoTaskService.PreparedDraft prepared=requireGeoTasks().isQuestionDiscovery(parameters)
                ? requireGeoTasks().prepareDiscoveryDraft(user,projectId,null,parameters)
                : requireGeoTasks().prepareDraft(user,projectId,null,parameters);
        ObjectNode semantic=json.createObjectNode();
        semantic.put("region",prepared.parameters().path("region").asText());
        semantic.set("platformCodes",prepared.parameters().path("platformCodes"));
        semantic.put("sampleMode","STANDARD");
        var questionTexts=semantic.putArray("questions");
        if(requireGeoTasks().isQuestionDiscovery(parameters)) prepared.parameters().path("questions").forEach(q->questionTexts.add(q.asText()));
        else prepared.snapshot().path("authorizedContext").path("questions").forEach(q->questionTexts.add(q.path("text").asText()));
        semantic.put("spaceId",Long.toString(projectId));
        String requestHash=sha256(canonical(semantic));
        UUID key=UUID.nameUUIDFromBytes(("native-geo:"+runId).getBytes(StandardCharsets.UTF_8));
        TaskRecords.Task replay=mapper.findTaskByOperationIdempotency(user,"AGENT_GEO_ACTION",key).orElse(null);
        if(replay!=null) {
            if(!constantEquals(requestHash,replay.requestHash())) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return agentGeoAction(replay,true);
        }
        var active=mapper.activeAgentGeoAction(user,run.conversationId(),requestHash).orElse(null);
        if(active!=null) return agentGeoAction(active,true);
        if(!Set.of("QUEUED","STARTING","RUNNING").contains(run.status())
                || run.stopRequestedAt()!=null || (run.deadlineAt()!=null && !now().isBefore(run.deadlineAt())))
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        var policy=monitorMapper.sharePolicy(user).orElse(null);
        if(policy!=null && !policy.enabled()) throw agentBudgetRefusal("BUDGET_DISABLED","自动监测额度已关闭，请在设置中启用。");
        int perAction=policy==null?platformProperties.getNativeActionMaxPointsPerAction():policy.maxPointsPerAction();
        int perDay=policy==null?platformProperties.getNativeActionMaxPointsPerDay():policy.maxPointsPerDay();
        if(perAction<1 || perDay<perAction) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        int quote=requireGeoTasks().quote(prepared);
        if(quote>perAction) throw agentBudgetRefusal("SINGLE_ACTION_LIMIT","本次监测超出单次额度，请减少问题或平台，或调整额度。");
        // The wallet precedes any conversation update, matching task settlement lock order.
        var points=mapper.lockPointAccount(user).orElseThrow(()->new BusinessException(ErrorCode.INSUFFICIENT_POINTS));
        OffsetDateTime acceptedAt=OffsetDateTime.ofInstant(accountingClock.instant(),ZoneOffset.UTC);
        var zone=java.time.ZoneId.of("Asia/Shanghai");
        var day=acceptedAt.atZoneSameInstant(zone).toLocalDate();
        if(policy!=null && day.isBefore(policy.effectiveShanghaiDate()))
            throw agentBudgetRefusal("BUDGET_NOT_EFFECTIVE","监测额度尚未生效。");
        if(points.availablePoints()<quote) throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS);
        long spent=monitorMapper.consumedPoints(user,day.atStartOfDay(zone).toOffsetDateTime(),day.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        if(spent+(long)points.frozenPoints()+quote>perDay)
            throw agentBudgetRefusal("DAY_LIMIT","今日自动执行额度不足，请调整额度或明天继续。");
        GeoTaskService.PreparedDraft executable=requireGeoTasks().isQuestionDiscovery(parameters)
                ? requireGeoTasks().materializeDiscoveryDraft(user,projectId,null,parameters):prepared;
        String snapshot=canonical(executable.snapshot());
        // Existing CHAT provenance gives one draft per originating USER run.
        var draft=new TaskRecords.Draft(ids.nextId(),user,run.conversationId(),runId,null,"CHAT",null,null,null,
                "GEO",1,projectId,null,canonical(executable.parameters()),snapshot,sha256(snapshot),
                properties.pricingVersionId("GEO"),quote,"[]","WAITING_CONFIRMATION",
                acceptedAt.plus(properties.getConfirmationTtl()),null,acceptedAt,acceptedAt);
        if(mapper.insertDraft(draft)!=1 || mapper.admitMonitorDraft(draft.id(),user,acceptedAt)!=1)
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        var task=new TaskRecords.Task(ids.nextId(),user,projectId,null,run.conversationId(),draft.id(),"GEO","QUEUED","QUEUED",1,
                snapshot,draft.inputHash(),draft.pricingVersionId(),quote,0,"{}",null,"AGENT_GEO_ACTION",key,requestHash,
                acceptedAt,null,null,acceptedAt,acceptedAt,null,null);
        persistAcceptedTask(task,draft,points,acceptedAt);
        return agentGeoAction(task,false);
    }

    private AgentGeoAction agentGeoAction(TaskRecords.Task task,boolean reused) {
        return new AgentGeoAction(taskResponse(task),read(task.inputSnapshotJson()).path("parameters").path("topicId").asText(),reused);
    }

    public record AgentCreativeAction(TaskDtos.TaskResponse task,boolean reused) { }

    /** Explicit native creation. Reuses the same quote, wallet reservation, outbox and media worker. */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AgentCreativeAction admitAgentCreativeAction(AuthenticatedAccount account,UUID runId,Long projectId,
            String taskType,ObjectNode rawParameters) {
        return admitAgentCreativeAction(account,runId,projectId,taskType,rawParameters,null);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AgentCreativeAction admitAgentCreativeAction(AuthenticatedAccount account,UUID runId,Long projectId,
            String taskType,ObjectNode rawParameters,CreativeContinuationReview continuationReview) {
        if(!Set.of("IMAGE","VIDEO").contains(taskType)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var run=conversationMapper.findRun(runId).orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        long user=CatalogAccess.requireUser(account);
        if(user!=run.userId()) throw new BusinessException(ErrorCode.FORBIDDEN);
        if(monitorMapper==null||platformProperties==null||agentPlatformService==null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        if(monitorMapper.shareRunnableAccount(user)==null) throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        var identity=agentPlatformService.assertTaskConfirmationAllowed(user,run.conversationId());
        if(!identity.capabilities().contains(taskCapability(taskType))
                ||!java.util.Objects.equals(identity.projectId()==null?null:Long.valueOf(identity.projectId()),projectId))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        var conversation=conversationMapper.findConversation(run.conversationId(),user).orElseThrow();
        if(!java.util.Objects.equals(conversation.selectedSpaceId(),projectId)) throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        var sourceMessage=conversationMapper.findMessage(run.requestMessageId(),user,run.conversationId())
                .orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if(!"USER".equals(sourceMessage.role())) throw new BusinessException(ErrorCode.FORBIDDEN);
        String sourceText=read(sourceMessage.contentJson()).path("text").asText("");
        JsonNode frozenAttachments=read(run.attachmentAssetIdsJson());
        boolean hasCurrentAttachments=frozenAttachments.isArray()&&frozenAttachments.size()>0;
        boolean previousImageResult="PREVIOUS_IMAGE_RESULT".equals(rawParameters.path("sourceMode").asText());
        boolean previousUserAttachment="PREVIOUS_USER_ATTACHMENT".equals(rawParameters.path("sourceMode").asText());
        boolean previousImageRequested=CreativeGenerationIntentPolicy.explicitlyReferencesPreviousImage(taskType,sourceText);
        boolean previousUserAttachmentRequested=!hasCurrentAttachments
                && CreativeGenerationIntentPolicy.explicitlyReferencesPreviousUserAttachment(taskType,sourceText);
        boolean hasFrozenSource=hasCurrentAttachments
                ||run.selectedProductId()!=null
                ||previousImageResult||previousUserAttachment;
        boolean directIntent=CreativeGenerationIntentPolicy.allows(taskType,sourceText,hasFrozenSource);
        Long reviewedImageAssetId=null;
        if(previousUserAttachment&&!previousUserAttachmentRequested)
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "请在当前消息中明确指向上一轮上传的图片。",
                    Map.of("reasonCode","EXPLICIT_PREVIOUS_USER_ATTACHMENT_REFERENCE_REQUIRED"));
        if(previousUserAttachmentRequested&&!previousUserAttachment)
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "当前消息明确要求继续使用上一轮上传的图片。",
                    Map.of("reasonCode","PREVIOUS_USER_ATTACHMENT_SOURCE_MODE_REQUIRED"));
        if(previousImageRequested&&!previousImageResult)
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "当前消息明确要求继续使用上一张生成图片。",
                    Map.of("reasonCode","PREVIOUS_IMAGE_SOURCE_MODE_REQUIRED"));
        if(previousImageResult&&!previousImageRequested&&continuationReview==null)
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "请在当前消息中明确指向上一张生成图片。",
                    Map.of("reasonCode","EXPLICIT_PREVIOUS_IMAGE_REFERENCE_REQUIRED"));
        // Selecting an image is context, not permission to charge; current attachments always need the semantic verdict.
        if(hasCurrentAttachments || continuationReview!=null || !directIntent) {
            if(creativeContinuations==null)
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                        "请在当前消息中明确要求生成或修改图片/视频。",
                        Map.of("reasonCode","EXPLICIT_CREATIVE_INTENT_REQUIRED"));
            reviewedImageAssetId=creativeContinuations.requireReview(run,sourceMessage,projectId,taskType,continuationReview);
            if("IMAGE".equals(taskType) && reviewedImageAssetId==null
                    && !"CURRENT_ATTACHMENTS".equals(continuationReview.sourceMode()))
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                        "请明确要继续修改哪一张图片。",
                        Map.of("reasonCode","CREATIVE_CONTINUATION_SOURCE_REQUIRED"));
            if(!rawParameters.path("sourceMode").asText().equals(continuationReview.sourceMode()))
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                        "当前创作必须使用已核对的图片来源。",
                        Map.of("reasonCode","CREATIVE_CONTINUATION_SOURCE_MODE_REQUIRED"));
        }
        if(previousImageResult&&!previousImageRequested&&reviewedImageAssetId==null)
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "请在当前消息中明确指向上一张生成图片。",
                    Map.of("reasonCode","EXPLICIT_PREVIOUS_IMAGE_REFERENCE_REQUIRED"));
        if(reviewedImageAssetId!=null&&!previousImageResult)
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "当前消息明确要求继续使用上一张生成图片。",
                    Map.of("reasonCode","PREVIOUS_IMAGE_SOURCE_MODE_REQUIRED"));
        // The original USER message is the only request-text authority. A tool caller cannot
        // smuggle a different billable instruction through its generated arguments.
        ObjectNode trustedParameters=rawParameters.deepCopy();
        trustedParameters.put("requestText",sourceText);
        ObjectNode parameters;
        try { parameters=validateAndNormalizeTaskParameters(taskType,trustedParameters); }
        catch(IllegalArgumentException failure) { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
        UUID key=UUID.nameUUIDFromBytes(("native-creative:"+runId).getBytes(StandardCharsets.UTF_8));
        String requestHash=sha256(taskType+"|"+canonical(parameters));
        var replay=mapper.findTaskByOperationIdempotency(user,"AGENT_CREATIVE_ACTION",key).orElse(null);
        if(replay!=null) {
            if(!constantEquals(requestHash,replay.requestHash())) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return new AgentCreativeAction(taskResponse(replay),true);
        }
        if(!Set.of("QUEUED","STARTING","RUNNING").contains(run.status())||run.stopRequestedAt()!=null
                ||!now().isBefore(run.effectiveDeadline())) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        var policy=monitorMapper.sharePolicy(user).orElse(null);
        if(policy!=null&&!policy.enabled()) throw agentBudgetRefusal("BUDGET_DISABLED","自动执行额度已关闭。");
        int perAction=policy==null?platformProperties.getNativeActionMaxPointsPerAction():policy.maxPointsPerAction();
        int perDay=policy==null?platformProperties.getNativeActionMaxPointsPerDay():policy.maxPointsPerDay();
        if(perAction<1||perDay<perAction) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        int quote=creativeQuote(taskType,parameters);
        if(quote>perAction) throw agentBudgetRefusal("SINGLE_ACTION_LIMIT","本次创作超出单次额度，请减少数量或调整额度。");
        var points=mapper.lockPointAccount(user).orElseThrow(()->new BusinessException(ErrorCode.INSUFFICIENT_POINTS));
        OffsetDateTime acceptedAt=OffsetDateTime.ofInstant(accountingClock.instant(),ZoneOffset.UTC);
        var zone=java.time.ZoneId.of("Asia/Shanghai");var day=acceptedAt.atZoneSameInstant(zone).toLocalDate();
        if(policy!=null&&day.isBefore(policy.effectiveShanghaiDate())) throw agentBudgetRefusal("BUDGET_NOT_EFFECTIVE","执行额度尚未生效。");
        if(points.availablePoints()<quote) throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS);
        long spent=monitorMapper.consumedPoints(user,day.atStartOfDay(zone).toOffsetDateTime(),day.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        if(spent+(long)points.frozenPoints()+quote>perDay) throw agentBudgetRefusal("DAY_LIMIT","今日自动执行额度不足。");
        // Internal personal storage never becomes a customer project or changes the frozen conversation identity.
        long storageProject;
        if(projectId==null) {
            if(!Set.of("TEXT_ONLY","CURRENT_ATTACHMENTS","PREVIOUS_IMAGE_RESULT","PREVIOUS_USER_ATTACHMENT")
                    .contains(parameters.path("sourceMode").asText()))
                throw new BusinessException(ErrorCode.CREATIVE_SOURCE_REQUIRED);
            storageProject=agentPlatformService.ensurePersonalCreativeStorage(user);
        } else storageProject=projectId;
        var source=reviewedImageAssetId==null
                ?requireCreativeSources().prepareNative(run,storageProject,taskType,parameters)
                :requireCreativeSources().prepareNative(run,storageProject,taskType,parameters,reviewedImageAssetId);
        ObjectNode snapshot=json.createObjectNode();snapshot.put("taskType",taskType);snapshot.put("parameterSchemaVersion",1);
        snapshot.set("parameters",source.parameters());snapshot.set("authorizedContext",source.authorizedContext());
        snapshot.set("productCreativeBrief",source.productCreativeBrief());
        ObjectNode nativeCreative=snapshot.putObject("nativeCreative").put("runId",runId.toString())
                .put("sourceMessageId",Long.toString(run.requestMessageId()))
                .put("storageKind",projectId==null?"PERSONAL_CREATIVE":"PROJECT");
        if(continuationReview!=null) nativeCreative.set("continuationReview",json.valueToTree(continuationReview));
        String frozen=canonical(snapshot);
        var draft=new TaskRecords.Draft(ids.nextId(),user,run.conversationId(),runId,null,"CHAT",null,null,null,
                taskType,1,storageProject,source.productId(),canonical(source.parameters()),frozen,sha256(frozen),
                properties.pricingVersionId(taskType),quote,"[]","WAITING_CONFIRMATION",acceptedAt.plus(properties.getConfirmationTtl()),null,acceptedAt,acceptedAt);
        if(mapper.insertDraft(draft)!=1||mapper.admitCreativeDraft(draft.id(),user,acceptedAt)!=1) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        var task=new TaskRecords.Task(ids.nextId(),user,storageProject,source.productId(),run.conversationId(),draft.id(),taskType,"QUEUED","QUEUED",1,
                frozen,draft.inputHash(),draft.pricingVersionId(),quote,0,"{}",null,"AGENT_CREATIVE_ACTION",key,requestHash,
                acceptedAt,null,null,acceptedAt,acceptedAt,null,null);
        persistAcceptedTask(task,draft,points,acceptedAt);
        return new AgentCreativeAction(taskResponse(task),false);
    }

    private BusinessException agentBudgetRefusal(String reason,String message) {
        return new BusinessException(ErrorCode.TASK_STATE_CONFLICT,message,Map.of("reasonCode",reason));
    }

    private Optional<MonitorAdmissionResult> replayMonitorAdmission(
            com.guanghe.backend.agentplatform.MonitorExecutionRecords.Admission admission,TaskRecords.Task task) {
        if(task.userId()!=admission.userId()||!java.util.Objects.equals(task.conversationId(),admission.conversationId())
                ||!java.util.Objects.equals(task.draftId(),admission.draftId())) throw new IllegalStateException("Admission task ownership mismatch");
        String source="EXECUTION_PREAUTH".equals(task.operationScope())?"EXECUTION_PREAUTH":"USER_CONFIRM";
        if(monitorMapper.admit(admission.id(),task.id(),source,task.createdAt())!=1) throw new IllegalStateException("Admission replay changed");
        return Optional.of(new MonitorAdmissionResult(null));
    }

    @Transactional
    public ScheduledTaskResult createScheduledGeoTask(long planId, long userId, long spaceId,
                                                       Long productId, OffsetDateTime scheduledSlot,
                                                       JsonNode inputSnapshot, String inputHash,
                                                       long pricingVersionId, int maxPointsPerRun,
                                                       OffsetDateTime now) {
        int quote;
        try {
            quote = requireGeoTasks().quoteScheduled(inputSnapshot);
        } catch (BusinessException exception) {
            if (exception.errorCode() == ErrorCode.PROVIDER_NOT_CONFIGURED) {
                return ScheduledTaskResult.skipped("PROVIDER_NOT_CONFIGURED", null);
            }
            throw exception;
        }
        if (pricingVersionId != properties.pricingVersionId("GEO")) {
            return ScheduledTaskResult.skipped("PRICING_VERSION_CHANGED", quote);
        }
        if (quote > maxPointsPerRun) {
            return ScheduledTaskResult.skipped("PRICE_LIMIT_EXCEEDED", quote);
        }
        TaskRecords.PointAccount account = mapper.lockPointAccount(userId).orElse(null);
        if (account == null || account.availablePoints() < quote) {
            return ScheduledTaskResult.skipped("INSUFFICIENT_POINTS", quote);
        }
        UUID key = UUID.nameUUIDFromBytes(("geo-plan-slot|" + planId + "|"
                + scheduledSlot.toInstant()).getBytes(StandardCharsets.UTF_8));
        String requestHash = sha256(canonical(Map.of(
                "planId", Long.toString(planId), "scheduledSlot", scheduledSlot.toInstant().toString(),
                "inputHash", inputHash, "pricingVersionId", Long.toString(pricingVersionId))));
        long taskId = ids.nextId();
        TaskRecords.Task task = new TaskRecords.Task(taskId, userId, spaceId, productId,
                null, null, "GEO", "QUEUED", "QUEUED", 1, canonical(inputSnapshot),
                inputHash, pricingVersionId, quote, 0, "{}", null, "GEO_PLAN_SLOT",
                key, requestHash, now, null, null, now, now, planId, scheduledSlot);
        if (mapper.insertTask(task) != 1 || freezePoints(userId, taskId, "GEO", quote, now) != 1
                || mapper.insertReservation(ids.nextId(), userId, taskId, quote, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        requireGeoTasks().createRun(task, now);
        mapper.insertLedger(ids.nextId(), userId, -quote, quote,
                account.availablePoints() - quote, account.frozenPoints() + quote,
                "TASK_FREEZE", taskId, "task-freeze:" + taskId,
                "GEO scheduled test task", now);
        mapper.insertTaskEvent(ids.nextId(), taskId, userId, null, null, null,
                null, "QUEUED", "TASK_QUEUED", "SCHEDULER", null, null,
                canonical(Map.of("planId", Long.toString(planId),
                        "scheduledSlot", scheduledSlot.toInstant().toString())), now);
        insertExecutionOutbox(task, 1, now);
        return ScheduledTaskResult.dispatched(taskId, quote);
    }

    @Transactional(readOnly = true)
    public TaskDtos.TaskResponse getTask(AuthenticatedAccount account, long taskId) {
        long userId = CatalogAccess.requireUser(account);
        return taskResponse(mapper.findTask(taskId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskDtos.TaskResponse> listTasks(AuthenticatedAccount account, String taskType,
                                                         String status, String conversationIdValue,
                                                         String spaceIdValue,
                                                         String productIdValue, int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        String normalizedType = optionalEnum(taskType, TASK_TYPES);
        String normalizedStatus = optionalEnum(status, TASK_STATUSES);
        Long conversationId = optionalPositiveLong(conversationIdValue);
        Long spaceId = optionalPositiveLong(spaceIdValue);
        Long productId = optionalPositiveLong(productIdValue);
        long total = mapper.countTasks(userId, normalizedType, normalizedStatus,
                conversationId, spaceId, productId);
        List<TaskRecords.Task> tasks = mapper.findTaskPage(userId, normalizedType, normalizedStatus,
                conversationId, spaceId, productId, (long) (page - 1) * size, size);
        Map<Long, TaskRecords.TaskFunding> funding = new HashMap<>();
        if (!tasks.isEmpty()) mapper.findTaskFunding(userId, tasks.stream().map(TaskRecords.Task::id).toList())
                .forEach(value -> funding.put(value.taskId(), value));
        List<TaskDtos.TaskResponse> items = tasks.stream()
                .map(task -> taskResponse(task, funding.get(task.id()))).toList();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public List<TaskDtos.TaskEventResponse> taskEvents(AuthenticatedAccount account, long taskId) {
        long userId = CatalogAccess.requireUser(account);
        mapper.findTask(taskId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return mapper.findTaskBrowserEvents(taskId, userId).stream()
                .filter(event -> BROWSER_TASK_EVENT_TYPES.contains(event.eventType()))
                .map(this::safeTaskEvent)
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskDtos.PointAccountResponse getPointAccount(AuthenticatedAccount account) {
        long userId = CatalogAccess.requireUser(account);
        return mapper.findPointAccount(userId)
                .map(a -> new TaskDtos.PointAccountResponse(Long.toString(userId), a.availablePoints(),
                        a.frozenPoints(), a.version(), a.updatedAt()))
                .orElse(new TaskDtos.PointAccountResponse(Long.toString(userId), 0, 0, 1, null));
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskDtos.PointLedgerEntryResponse> listPointLedger(
            AuthenticatedAccount account, String entryType, String businessType, int page, int size) {
        return listPointLedger(account, null, entryType, businessType, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskDtos.PointLedgerEntryResponse> listPointLedger(
            AuthenticatedAccount account, Long ledgerId, String entryType, String businessType,
            int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        if (ledgerId != null && ledgerId <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String normalizedEntryType = optionalEnum(entryType, POINT_LEDGER_ENTRY_TYPES);
        String normalizedBusinessType = optionalEnum(businessType, POINT_LEDGER_BUSINESS_TYPES);
        long total = mapper.countPointLedger(userId, ledgerId, normalizedEntryType, normalizedBusinessType);
        List<TaskDtos.PointLedgerEntryResponse> items = mapper.findPointLedgerPage(
                        userId, ledgerId, normalizedEntryType, normalizedBusinessType,
                        (long) (page - 1) * size, size)
                .stream().map(this::pointLedgerResponse).toList();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional
    public ClaimedTask claim(long taskId, TaskDtos.ClaimRequest request) {
        long eventId = positiveLong(request.eventId());
        TaskRecords.Inbox seen = mapper.findInbox(request.consumerName(), eventId).orElse(null);
        if (seen != null) {
            if (seen.taskId() != taskId) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            TaskRecords.Execution execution = seen.executionId() == null ? null
                    : mapper.findExecution(seen.executionId()).orElse(null);
            TaskDtos.ClaimResponse response = execution == null
                    ? new TaskDtos.ClaimResponse(false, seen.disposition(), null, null, null)
                    : new TaskDtos.ClaimResponse(false, "DUPLICATE", execution.executionNo(),
                    executionToken(execution), execution.leaseExpiresAt());
            return new ClaimedTask(response, null);
        }
        TaskRecords.Outbox outbox = mapper.findOutbox(eventId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        TaskRecords.Task task = mapper.lockTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (outbox.userId() != task.userId()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (TERMINAL_TASK_STATUSES.contains(task.status())) {
            if (mapper.insertInbox(ids.nextId(), request.consumerName(), eventId, taskId, task.userId(),
                    null, "TERMINAL", now()) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            return new ClaimedTask(new TaskDtos.ClaimResponse(
                    false, "TERMINAL", null, null, null), null);
        }
        int executionNo = request.attempt();
        String expectedStatus = executionNo == 1 ? "QUEUED" : "RETRY_WAIT";
        TaskRecords.Execution latest = mapper.findLatestExecution(taskId).orElse(null);
        if (!expectedStatus.equals(task.status()) || outbox.executionAttempt() != executionNo
                || (latest != null && latest.executionNo() >= executionNo)) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_CLAIMED);
        }
        TaskDtos.ExecutionContextResponse.ProviderContinuation continuation =
                providerContinuation(latest);
        requireContinuationMatchesTask(task, continuation);
        boolean resumeOnly = continuation != null;
        // A retry dispatched by an older watchdog must not submit another paid generation without
        // queryable receipts. Synchronous ARTICLE calls cannot recover such receipts after a crash.
        if (executionNo > 1 && Set.of("IMAGE", "VIDEO", "ARTICLE").contains(task.taskType())
                && latest != null && "EXPIRED".equals(latest.status()) && !resumeOnly) {
            OffsetDateTime now = now();
            if (mapper.terminateUnconfirmedTask(taskId, now) != 1
                    || mapper.releaseReservation(taskId, task.quotedPoints(), now) != 1
                    || releasePoints(task.userId(), taskId, task.taskType(), task.quotedPoints(), now) != 1
                    || mapper.insertInbox(ids.nextId(), request.consumerName(), eventId, taskId, task.userId(),
                    null, "TERMINAL", now) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            TaskRecords.PointAccount after = mapper.findPointAccount(task.userId()).orElseThrow();
            mapper.insertLedger(ids.nextId(), task.userId(), task.quotedPoints(), -task.quotedPoints(),
                    after.availablePoints(), after.frozenPoints(), "TASK_RELEASE", taskId,
                    "task-release:" + taskId, task.taskType() + " provider receipt unavailable", now);
            mapper.insertTaskEvent(ids.nextId(), taskId, task.userId(), latest.id(), null, null,
                    "RETRY_WAIT", "SYSTEM_TERMINATED", "PROVIDER_RESULT_RECOVERY_EXHAUSTED", "JAVA",
                    PROVIDER_RESULT_RECOVERY_EXHAUSTED, null,
                    "{\"confirmationFailure\":\"NO_QUERYABLE_RECEIPT\"}", now);
            notifyWorkflowTerminal(task, "SYSTEM_TERMINATED", PROVIDER_RESULT_RECOVERY_EXHAUSTED, now);
            ConversationEventRecord browserEvent = insertConversationEvent(task, "task.failed",
                    json.valueToTree(Map.of("taskId", Long.toString(taskId), "status", "SYSTEM_TERMINATED",
                            "stageCode", PROVIDER_RESULT_CONFIRMATION_FAILED,
                            "code", PROVIDER_RESULT_RECOVERY_EXHAUSTED)), now);
            return new ClaimedTask(new TaskDtos.ClaimResponse(false, "TERMINAL", null, null, null), browserEvent);
        }
        if (executionNo > 1 && PROVIDER_RESULT_UNKNOWN.equals(task.stageCode()) && !resumeOnly) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        String stage = resumeOnly ? PROVIDER_RESULT_RECOVERY
                : "GEO".equals(task.taskType()) ? "GEO_SAMPLING"
                : "ARTICLE".equals(task.taskType()) ? "ARTICLE_GENERATING" : "CLAIMED";
        OffsetDateTime now = now();
        OffsetDateTime lease = now.plus(properties.getExecutionLease());
        long executionId = ids.nextId();
        TaskRecords.Execution unsigned = new TaskRecords.Execution(executionId, taskId, task.userId(), executionNo,
                "RUNNING", request.consumerName(), request.workerId(), request.supportedBundleVersion(), "",
                lease, stage, resumeOnly ? continuation.provider() : null,
                resumeOnly ? continuation.model() : null,
                resumeOnly ? UUID.fromString(continuation.providerRequestId()) : null,
                resumeOnly ? continuation.providerTaskId() : null,
                resumeOnly ? continuation.submissionState() : null, null,
                "{}", null, now, now, null, now, now);
        String token = executionToken(unsigned);
        TaskRecords.Execution execution = new TaskRecords.Execution(executionId, taskId, task.userId(), executionNo,
                "RUNNING", request.consumerName(), request.workerId(), request.supportedBundleVersion(), sha256(token),
                lease, stage, unsigned.providerCode(), unsigned.providerModel(), unsigned.providerRequestId(),
                unsigned.providerTaskId(), unsigned.submissionState(), null,
                "{}", null, now, now, null, now, now);
        if (mapper.claimTask(taskId, stage, now) != 1 || mapper.insertExecution(execution) != 1
                || mapper.insertInbox(ids.nextId(), request.consumerName(), eventId, taskId, task.userId(),
                executionId, "CLAIMED", now) != 1) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_CLAIMED);
        }
        if ("GEO".equals(task.taskType())) requireGeoTasks().markRunning(task, now);
        ObjectNode claimDetails = json.createObjectNode();
        claimDetails.put("resumeOnly", resumeOnly);
        mapper.insertTaskEvent(ids.nextId(), taskId, task.userId(), executionId, null, null,
                expectedStatus, "RUNNING", "TASK_CLAIMED", "PYTHON", null, null,
                canonical(claimDetails), now);
        ConversationEventRecord browserEvent = resumeOnly || "GEO".equals(task.taskType())
                ? insertConversationEvent(task, "task.progress", json.valueToTree(Map.of(
                "taskId", Long.toString(taskId), "status", "RUNNING",
                "stageCode", stage)), now)
                : null;
        return new ClaimedTask(new TaskDtos.ClaimResponse(
                true, "CLAIMED", executionNo, token, lease), browserEvent);
    }

    @Transactional(readOnly = true)
    public TaskDtos.ExecutionContextResponse executionContext(long taskId, int executionNo, String token) {
        TaskRecords.Task task = mapper.findTaskInternal(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        TaskRecords.Execution execution = requireExecution(taskId, executionNo, token, true);
        if ("GEO".equals(task.taskType())) {
            return requireGeoTasks().executionContext(task, execution);
        }
        JsonNode snapshot = read(task.inputSnapshotJson());
        if("ARTICLE".equals(task.taskType())
                && "ARTICLE_FULL_V1".equals(snapshot.path("articleFull").path("templateCode").asText())) {
            JsonNode frozenNode=snapshot.path("authorizedContext").path("knowledge");
            if(frozenNode.isObject()) {
                if(articleKnowledge==null) throw new BusinessException(ErrorCode.KNOWLEDGE_SOURCE_CHANGED);
                var frozen=json.convertValue(frozenNode,KnowledgeDtos.KnowledgeContext.class);
                var checked=articleKnowledge.revalidateFrozenContext(task.userId(),task.spaceId(),frozen);
                if(!frozen.documents().equals(checked.documents()) || !frozen.preferences().equals(checked.preferences())
                        || !java.util.Objects.equals(frozen.memory(),checked.memory()))
                    throw new BusinessException(ErrorCode.KNOWLEDGE_SOURCE_CHANGED);
            }
        }
        TaskDtos.ExecutionContextResponse.ProviderContinuation continuation =
                providerContinuation(execution);
        requireContinuationMatchesTask(task, continuation);
        boolean videoTask = "VIDEO".equals(task.taskType());
        boolean articleTask = "ARTICLE".equals(task.taskType());
        boolean fixedArticleBudget = articleTask && snapshot.has("executionBudget");
        PaidTaskProperties.ArticleStageBudget articleBudget = articleTask && !fixedArticleBudget
                ? properties.articleStageBudget() : null;
        TaskDtos.ExecutionContextResponse.Budget budget = articleTask
                ? fixedArticleBudget ? null : new TaskDtos.ExecutionContextResponse.Budget(0, null, null, null,
                null, null, null, null, now().plus(articleBudget.executionDeadline()),
                articleBudget.maxModelCalls(), articleBudget.maxRepairRetries(),
                articleBudget.maxInputTokens(), articleBudget.maxOutputTokens())
                : videoTask
                ? new TaskDtos.ExecutionContextResponse.Budget(
                properties.getVideoMaxProviderCalls(), null, 1, 30)
                : new TaskDtos.ExecutionContextResponse.Budget(
                isRegionEdit(task) ? properties.getVideoMaxProviderCalls() : 1,
                imageCount(snapshot.path("parameters")), null, null);
        if(fixedArticleBudget) {
            JsonNode fixed=snapshot.path("executionBudget");
            budget=new TaskDtos.ExecutionContextResponse.Budget(0,null,null,null,null,null,null,null,
                    OffsetDateTime.parse(fixed.path("deadlineAt").asText()),fixed.path("maxModelCalls").asInt(),
                    fixed.path("maxRepairRetries").asInt(),fixed.path("maxInputTokens").asInt(),fixed.path("maxOutputTokens").asInt());
        }
        JsonNode creativeBrief = snapshot.get("productCreativeBrief");
        List<TaskDtos.ExecutionContextResponse.ControlledReferenceAsset> references =
                (videoTask || "IMAGE".equals(task.taskType()))
                        ? requireCreativeSources().controlledReferences(task, snapshot) : List.of();
        return new TaskDtos.ExecutionContextResponse(Long.toString(taskId), task.taskType(),
                articleTask ? "DEEPSEEK" : videoTask ? WAN_VIDEO_PROVIDER
                        : "IMAGE".equals(task.taskType()) ? imageProvider(task) : null,
                videoTask ? WAN_VIDEO_MODEL : "IMAGE".equals(task.taskType()) ? imageModel(task) : null,
                execution.executionNo(),
                task.snapshotSchemaVersion(), snapshot.path("parameters"), snapshot.path("authorizedContext"),
                creativeBrief, references, "IMAGE".equals(task.taskType())
                        ? requireCreativeSources().controlledMask(task, snapshot) : null,
                continuation != null, continuation,
                new TaskDtos.ExecutionContextResponse.Pricing(Long.toString(task.pricingVersionId()),
                        task.quotedPoints()), budget);
    }

    @Transactional
    public TaskDtos.HeartbeatResponse heartbeat(long taskId, TaskDtos.HeartbeatRequest request) {
        TaskRecords.Execution execution = requireExecution(
                taskId, request.executionNo(), request.executionToken(), true);
        TaskRecords.Task task = mapper.lockTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"RUNNING".equals(task.status()) || task.userId() != execution.userId()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        OffsetDateTime now = now();
        OffsetDateTime lease = now.plus(properties.getExecutionLease());
        if (mapper.heartbeat(taskId, request.executionNo(), sha256(request.executionToken()),
                safeStage(request.stageCode()), lease, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (mapper.updateProgress(taskId, safeStage(request.stageCode()), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return new TaskDtos.HeartbeatResponse(true, lease);
    }

    @Transactional
    public ReportedTask reportEvent(long taskId, TaskDtos.ExecutionEventRequest request) {
        String requestHash = sha256(canonical(json.valueToTree(request)));
        TaskDtos.InternalAccepted replay = replayIfSeen(taskId, request.eventId(), request.executionNo(),
                request.executionToken(), requestHash);
        if (replay != null) return new ReportedTask(replay, null);
        TaskRecords.Execution execution = requireExecution(taskId, request.executionNo(), request.executionToken(), true);
        if ("TASK_FAILED".equals(request.eventType())) {
            return reportFailure(taskId, execution, request, requestHash);
        }
        if (!PROGRESS_EVENTS.contains(request.eventType())) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if ("ARTICLE_BODY_STREAMING".equals(request.stageCode())
                || (request.details()!=null && request.details().has("articlePreview"))) {
            if (!"TASK_PROGRESS".equals(request.eventType()) || !"ARTICLE_BODY_STREAMING".equals(request.stageCode())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            TaskRecords.Task task=mapper.lockTask(taskId).orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            // Check again after acquiring the task lock: an old worker must not publish after finalization/reclaim.
            execution=requireExecution(taskId,request.executionNo(),request.executionToken(),true);
            OffsetDateTime at=now();
            ObjectNode reference=articleService.acceptStreamPreview(task,execution,request.details(),at);
            if (mapper.insertTaskEvent(ids.nextId(),taskId,task.userId(),execution.id(),request.eventId(),requestHash,
                    null,null,request.eventType(),"PYTHON",null,null,
                    reference==null ? "{}" : canonical(json.createObjectNode().set("articlePreview",reference)),at)==0) {
                return new ReportedTask(requireReplay(taskId,request.eventId(),request.executionNo(),request.executionToken(),requestHash),null);
            }
            if (reference==null) return new ReportedTask(new TaskDtos.InternalAccepted(true,task.status()),null);
            mapper.updateProgress(taskId,request.stageCode(),at);
            ObjectNode payload=json.createObjectNode().put("taskId",Long.toString(taskId)).put("status","RUNNING")
                    .put("stageCode",request.stageCode());
            payload.set("articlePreview",reference);
            return new ReportedTask(new TaskDtos.InternalAccepted(true,"RUNNING"),insertConversationEvent(task,"task.progress",payload,at));
        }
        String details = request.details() == null ? "{}" : canonical(request.details());
        int inserted = mapper.insertTaskEvent(ids.nextId(), taskId, execution.userId(), execution.id(), request.eventId(),
                requestHash, null, null, request.eventType(), "PYTHON", null, null, details, now());
        if (inserted == 0) return new ReportedTask(requireReplay(taskId, request.eventId(), request.executionNo(),
                request.executionToken(), requestHash), null);
        String stage = safeStage(request.stageCode());
        OffsetDateTime now = now();
        mapper.updateProgress(taskId, stage, now);
        TaskRecords.Task task = mapper.findTaskInternal(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        ConversationEventRecord browserEvent = insertConversationEvent(task, "task.progress",
                json.valueToTree(Map.of("taskId", Long.toString(taskId), "status", "RUNNING",
                        "stageCode", stage)), now);
        return new ReportedTask(new TaskDtos.InternalAccepted(true, "RUNNING"), browserEvent);
    }

    private ReportedTask reportFailure(long taskId, TaskRecords.Execution execution,
                                       TaskDtos.ExecutionEventRequest request, String requestHash) {
        JsonNode details = request.details();
        FailureDetails failure = validateFailureDetails(details, execution);
        TaskRecords.Task task = mapper.lockTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (failure.provider() != null) {
            boolean matchesTask = ("IMAGE".equals(task.taskType())
                    && imageProvider(task).equals(failure.provider()) && imageModel(task).equals(failure.model()))
                    || ("VIDEO".equals(task.taskType()) && WAN_VIDEO_PROVIDER.equals(failure.provider()))
                    || ("ARTICLE".equals(task.taskType()) && "DEEPSEEK".equals(failure.provider()));
            if (!matchesTask) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String code = failure.code();
        String submission = failure.submissionState();
        if (("UNKNOWN".equals(submission) && !PROVIDER_RESULT_UNKNOWN.equals(request.stageCode()))
                || (!"UNKNOWN".equals(submission) && !"FAILED".equals(request.stageCode()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        OffsetDateTime now = now();
        boolean unknown = "UNKNOWN".equals(submission);
        boolean missingReceipt = unknown && (failure.providerRequestId() == null || failure.providerTaskId() == null);
        boolean recoveryExhausted = unknown
                && (missingReceipt || execution.executionNo() >= properties.getMaxExecutionAttempts());
        String finalStatus = !unknown ? "FAILED"
                : recoveryExhausted ? "SYSTEM_TERMINATED" : "RETRY_WAIT";
        String stage = !unknown ? "FAILED"
                : recoveryExhausted ? PROVIDER_RESULT_CONFIRMATION_FAILED : PROVIDER_RESULT_UNKNOWN;
        String terminalCode = recoveryExhausted ? PROVIDER_RESULT_RECOVERY_EXHAUSTED : code;
        if (mapper.insertTaskEvent(ids.nextId(), taskId, task.userId(), execution.id(), request.eventId(),
                requestHash, "RUNNING", finalStatus, "TASK_FAILED", "PYTHON", terminalCode, null,
                canonical(details), now) == 0) return new ReportedTask(
                requireReplay(taskId, request.eventId(), request.executionNo(),
                        request.executionToken(), requestHash), null);
        int executionUpdated = unknown
                ? mapper.recordUnknownExecution(taskId, request.executionNo(), sha256(request.executionToken()),
                failure.provider(), failure.model(), failure.providerRequestId(), failure.providerTaskId(), code, now)
                : mapper.failExecution(taskId, request.executionNo(), sha256(request.executionToken()), submission,
                code, now);
        int taskUpdated = recoveryExhausted
                ? mapper.terminateUnconfirmedTask(taskId, now)
                : mapper.failOrRetryTask(taskId, finalStatus, stage, terminalCode, now);
        if (executionUpdated != 1 || taskUpdated != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (unknown && !recoveryExhausted) {
            insertExecutionOutbox(task, execution.executionNo() + 1, now);
            ConversationEventRecord browserEvent = insertConversationEvent(task, "task.progress",
                    json.valueToTree(Map.of("taskId", Long.toString(taskId), "status", "RETRY_WAIT",
                            "stageCode", PROVIDER_RESULT_UNKNOWN,
                            "nextExecutionNo", execution.executionNo() + 1)), now);
            return new ReportedTask(new TaskDtos.InternalAccepted(true, finalStatus), browserEvent);
        }
        ConversationEventRecord browserEvent = null;
        if ("FAILED".equals(finalStatus) || "SYSTEM_TERMINATED".equals(finalStatus)) {
            if ("GEO".equals(task.taskType())) requireGeoTasks().markFailed(task, finalStatus, now);
            if (mapper.releaseReservation(taskId, task.quotedPoints(), now) != 1
                    || releasePoints(task.userId(), task.id(), task.taskType(), task.quotedPoints(), now) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            TaskRecords.PointAccount after = mapper.findPointAccount(task.userId()).orElseThrow();
            mapper.insertLedger(ids.nextId(), task.userId(), task.quotedPoints(), -task.quotedPoints(),
                    after.availablePoints(), after.frozenPoints(), "TASK_RELEASE", taskId,
                    "task-release:" + taskId,
                    recoveryExhausted ? task.taskType() + " provider result confirmation exhausted"
                            : "Validated " + task.taskType() + " provider failure", now);
            notifyWorkflowTerminal(task, finalStatus, terminalCode, now);
            browserEvent = insertConversationEvent(task, "task.failed",
                    json.valueToTree(Map.of("taskId", Long.toString(taskId), "status", finalStatus,
                            "stageCode", stage, "code", terminalCode)), now);
        }
        return new ReportedTask(new TaskDtos.InternalAccepted(true, finalStatus), browserEvent);
    }

    @Transactional
    public ReportedTask reportResult(long taskId, TaskDtos.ResultArtifactsRequest request) {
        String requestHash = sha256(canonical(json.valueToTree(request)));
        TaskDtos.InternalAccepted replay = replayIfSeen(taskId, request.eventId(), request.executionNo(),
                request.executionToken(), requestHash);
        if (replay != null) return new ReportedTask(replay, null);
        TaskRecords.Execution execution = requireExecution(taskId, request.executionNo(), request.executionToken(), true);
        TaskRecords.Task task = mapper.lockTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if ("ARTICLE".equals(task.taskType())) {
            return reportArticleResult(task, execution, request, requestHash);
        }
        ValidatedTaskResult validated = validateTaskResult(task, execution, request);
        ObjectNode evidence = json.createObjectNode();
        evidence.put("requestId", request.requestId().toString());
        evidence.put("providerTaskId", request.providerTaskId());
        evidence.put("submissionState", request.submissionState());
        evidence.put("latencyMs", request.latencyMs());
        OffsetDateTime now = now();
        int inserted = mapper.insertTaskEvent(ids.nextId(), taskId, task.userId(), execution.id(), request.eventId(),
                requestHash, "RUNNING", validated.mock() ? "SUCCEEDED" : "RUNNING",
                validated.mock() ? "RESULT_REPORTED" : "RESULT_READY", "PYTHON", null, null,
                canonical(evidence), now);
        if (inserted == 0) return new ReportedTask(requireReplay(taskId, request.eventId(), request.executionNo(),
                request.executionToken(), requestHash), null);
        if (!validated.mock()) {
            if (!assetProperties.isEnabled()) {
                throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
            }
            if (mapper.markExecutionResultReady(taskId, request.executionNo(), sha256(request.executionToken()),
                    request.provider(), request.model(), request.requestId(), request.providerTaskId(),
                    request.latencyMs(), canonical(validated.persistedUsage()), now) != 1
                    || mapper.markTaskAssetIngestionPending(taskId, now) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            for (int index = 0; index < request.artifacts().size(); index++) {
                JsonNode artifact = request.artifacts().get(index);
                long assetId = ids.nextId();
                Long expectedSize = artifact.path("sizeBytes").canConvertToLong()
                        && artifact.path("sizeBytes").longValue() > 0 ? artifact.path("sizeBytes").longValue() : null;
                String expectedSha256 = artifact.path("sha256").isTextual()
                        ? artifact.path("sha256").textValue() : null;
                boolean video = validated.video();
                JsonNode metadata = artifact.path("metadata");
                AssetRecords.IngestionWork work = new AssetRecords.IngestionWork(
                        ids.nextId(), assetId, task.id(), execution.id(), execution.executionNo(), task.userId(),
                        task.spaceId(), task.productId(), index + 1, request.provider(), request.model(), request.requestId(),
                        request.providerTaskId(), artifact.path("providerUrl").textValue(),
                        video ? null : OffsetDateTime.parse(artifact.path("expiresAt").textValue()),
                        artifact.path("contentType").textValue(), expectedSize, expectedSha256,
                        video || isRegionEdit(task) ? null : metadata.path("width").intValue(),
                        video || isRegionEdit(task) ? null : metadata.path("height").intValue(),
                        video ? BigDecimal.valueOf(
                                metadata.path("requestedDurationSeconds").intValue()) : null,
                        video ? metadata.path("requestedAspectRatio").textValue() : null,
                        video ? metadata.path("requestedResolution").textValue() : null,
                        video ? now.plus(assetProperties.getVideoIngestionDeadline()) : null,
                        assetProperties.objectKey(task.userId(), assetId, task.taskType()), "PENDING", 0,
                        assetProperties.getMaxAttempts(), now, null, null, null, null, now, now
                );
                if (mapper.insertAssetIngestionWork(work) != 1) {
                    throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
                }
            }
            ConversationEventRecord browserEvent = insertConversationEvent(task, "task.progress",
                    json.valueToTree(Map.of("taskId", Long.toString(taskId), "status", "RUNNING",
                            "stageCode", "ASSET_INGESTION_PENDING")), now);
            return new ReportedTask(new TaskDtos.InternalAccepted(true, "RUNNING"), browserEvent);
        }
        ObjectNode resultSummary = json.createObjectNode();
        resultSummary.put("provider", request.provider());
        resultSummary.put("model", request.model());
        resultSummary.put("mock", validated.mock());
        resultSummary.set("artifacts", mockBrowserArtifacts(request));
        String resultJson = canonical(resultSummary);
        if (mapper.completeExecution(taskId, request.executionNo(), sha256(request.executionToken()),
                request.provider(), request.model(), request.requestId(), request.providerTaskId(),
                request.latencyMs(), canonical(validated.persistedUsage()), now) != 1
                || mapper.completeTask(taskId, resultJson, now) != 1
                || mapper.settleReservation(taskId, task.quotedPoints(), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        OffsetDateTime accountingAt = settlePoints(task.userId(), task.id(), task.taskType(), task.quotedPoints());
        TaskRecords.PointAccount after = mapper.findPointAccount(task.userId()).orElseThrow();
        mapper.insertLedger(ids.nextId(), task.userId(), 0, -task.quotedPoints(), after.availablePoints(),
                after.frozenPoints(), "TASK_SETTLE", taskId, "task-settle:" + taskId,
                "Validated " + task.taskType() + " provider result", accountingAt);
        notifyWorkflowTerminal(task, "SUCCEEDED", null, now);
        ConversationEventRecord browserEvent = insertConversationEvent(task, "task.completed",
                json.valueToTree(Map.of("taskId", Long.toString(taskId), "status", "SUCCEEDED")), now);
        return new ReportedTask(new TaskDtos.InternalAccepted(true, "SUCCEEDED"), browserEvent);
    }

    private ReportedTask reportArticleResult(TaskRecords.Task task, TaskRecords.Execution execution,
                                              TaskDtos.ResultArtifactsRequest request,
                                              String requestHash) {
        if (!"CONFIRMED".equals(request.submissionState()) || request.artifacts() == null
                || request.artifacts().size() != 1 || request.providerTaskId() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ArticleService.ValidatedResult validated = requireArticles().validateResult(task, request.provider(),
                request.model(), request.artifacts().getFirst(), request.usage(), request.latencyMs());
        requireArticles().lockAndRequireCurrentVersion(task.userId(), validated.articleId(),
                validated.articleVersion());
        OffsetDateTime now = now();
        ObjectNode evidence = json.createObjectNode();
        evidence.put("requestId", request.requestId().toString());
        evidence.put("mode", validated.mode());
        evidence.put("articleId", Long.toString(validated.articleId()));
        evidence.put("articleVersion", validated.articleVersion());
        if (mapper.insertTaskEvent(ids.nextId(), task.id(), task.userId(), execution.id(), request.eventId(),
                requestHash, "RUNNING", "RUNNING", "ARTICLE_RESULT_REPORTED", "PYTHON", null, null,
                canonical(evidence), now) == 0) {
            return new ReportedTask(requireReplay(task.id(), request.eventId(), request.executionNo(),
                    request.executionToken(), requestHash), null);
        }
        if (!requireArticles().applyGeneratedResult(task, validated, now)) {
            String code = "ARTICLE_VERSION_CONFLICT";
            if (mapper.failExecution(task.id(), request.executionNo(), sha256(request.executionToken()),
                    "CONFIRMED", code, now) != 1
                    || mapper.failOrRetryTask(task.id(), "FAILED", "FAILED", code, now) != 1
                    || mapper.releaseReservation(task.id(), task.quotedPoints(), now) != 1
                    || releasePoints(task.userId(), task.id(), task.taskType(), task.quotedPoints(), now) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            TaskRecords.PointAccount after = mapper.findPointAccount(task.userId()).orElseThrow();
            mapper.insertLedger(ids.nextId(), task.userId(), task.quotedPoints(), -task.quotedPoints(),
                    after.availablePoints(), after.frozenPoints(), "TASK_RELEASE", task.id(),
                    "task-release:" + task.id(), "ARTICLE version conflict", now);
            notifyWorkflowTerminal(task, "FAILED", code, now);
            mapper.insertTaskEvent(ids.nextId(), task.id(), task.userId(), execution.id(), null, null,
                    "RUNNING", "FAILED", "ARTICLE_VERSION_CONFLICT", "JAVA", code, null, "{}", now);
            ConversationEventRecord browserEvent = insertConversationEvent(task, "task.failed",
                    json.valueToTree(Map.of("taskId", Long.toString(task.id()), "status", "FAILED",
                            "stageCode", "FAILED", "code", code)), now);
            return new ReportedTask(new TaskDtos.InternalAccepted(true, "FAILED"), browserEvent);
        }
        String resultJson = canonical(validated.publicResult());
        if (mapper.completeExecution(task.id(), request.executionNo(), sha256(request.executionToken()),
                request.provider(), request.model(), request.requestId(), null, request.latencyMs(),
                canonical(request.usage()), now) != 1
                || mapper.completeTask(task.id(), resultJson, now) != 1
                || mapper.settleReservation(task.id(), task.quotedPoints(), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        OffsetDateTime accountingAt = settlePoints(task.userId(), task.id(), task.taskType(), task.quotedPoints());
        TaskRecords.PointAccount after = mapper.findPointAccount(task.userId()).orElseThrow();
        mapper.insertLedger(ids.nextId(), task.userId(), 0, -task.quotedPoints(), after.availablePoints(),
                after.frozenPoints(), "TASK_SETTLE", task.id(), "task-settle:" + task.id(),
                "Validated ARTICLE generation result", accountingAt);
        notifyWorkflowTerminal(task, "SUCCEEDED", null, now);
        mapper.insertTaskEvent(ids.nextId(), task.id(), task.userId(), execution.id(), null, null,
                "RUNNING", "SUCCEEDED", "ARTICLE_COMPLETED", "JAVA", null, null,
                canonical(json.valueToTree(Map.of("mode", validated.mode(),
                        "articleId", Long.toString(validated.articleId())))), now);
        ConversationEventRecord browserEvent = insertConversationEvent(task, "task.completed",
                json.valueToTree(Map.of("taskId", Long.toString(task.id()), "status", "SUCCEEDED")), now);
        return new ReportedTask(new TaskDtos.InternalAccepted(true, "SUCCEEDED"), browserEvent);
    }

    @Transactional
    public Optional<ReportedTask> recoverExpiredExecution() {
        OffsetDateTime now = now();
        Long taskId = mapper.findExpiredTaskId(now).orElse(null);
        if (taskId == null) return Optional.empty();
        TaskRecords.Task task = mapper.lockTask(taskId).orElse(null);
        if (task == null || !"RUNNING".equals(task.status())) return Optional.empty();
        TaskRecords.Execution execution = mapper.lockExpiredExecution(taskId, now).orElse(null);
        if (execution == null || mapper.expireExecution(execution.id(), now) != 1) return Optional.empty();

        try (ObservabilitySupport.Operation span = observability.startConsumer(
                "java.task.recover", "EXECUTION_LEASE_EXPIRED", null, true)) {
            span.tag("taskIdRef", observability.reference(task.id()));
            span.tag("executionNo", execution.executionNo());
            span.tag("taskType", task.taskType());
        int nextExecutionNo = execution.executionNo() + 1;
        boolean requiresQueryableReceipt = Set.of("IMAGE", "VIDEO", "ARTICLE").contains(task.taskType());
        TaskDtos.ExecutionContextResponse.ProviderContinuation continuation = providerContinuation(execution);
        requireContinuationMatchesTask(task, continuation);
        boolean providerRecovery = continuation != null;
        boolean missingReceipt = requiresQueryableReceipt && !providerRecovery;
        boolean unconfirmed = providerRecovery || missingReceipt;
        if (missingReceipt || nextExecutionNo > properties.getMaxExecutionAttempts()) {
            int terminalUpdate = unconfirmed
                    ? mapper.terminateUnconfirmedTask(taskId, now)
                    : mapper.failExpiredTask(taskId, now);
            if (terminalUpdate != 1
                    || mapper.releaseReservation(taskId, task.quotedPoints(), now) != 1
                    || releasePoints(task.userId(), task.id(), task.taskType(), task.quotedPoints(), now) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            String eventType = unconfirmed
                    ? "PROVIDER_RESULT_RECOVERY_EXHAUSTED" : "EXECUTION_RETRIES_EXHAUSTED";
            String errorCode = unconfirmed
                    ? PROVIDER_RESULT_RECOVERY_EXHAUSTED : "WORKER_LEASE_EXPIRED";
            String finalStatus = unconfirmed ? "SYSTEM_TERMINATED" : "FAILED";
            String finalStage = unconfirmed ? PROVIDER_RESULT_CONFIRMATION_FAILED : "FAILED";
            if ("GEO".equals(task.taskType())) requireGeoTasks().markFailed(task, finalStatus, now);
            mapper.insertTaskEvent(ids.nextId(), taskId, task.userId(), execution.id(), null, null,
                    "RUNNING", finalStatus, eventType, "JAVA",
                    errorCode, null, missingReceipt ? "{\"confirmationFailure\":\"NO_QUERYABLE_RECEIPT\"}"
                            : "{}", now);
            TaskRecords.PointAccount after = mapper.findPointAccount(task.userId()).orElseThrow();
            mapper.insertLedger(ids.nextId(), task.userId(), task.quotedPoints(), -task.quotedPoints(),
                    after.availablePoints(), after.frozenPoints(), "TASK_RELEASE", taskId,
                    "task-release:" + taskId, unconfirmed
                            ? task.taskType() + " provider result confirmation exhausted"
                            : task.taskType() + " worker lease expired", now);
            notifyWorkflowTerminal(task, finalStatus, errorCode, now);
            ConversationEventRecord browserEvent = insertConversationEvent(task, "task.failed",
                    json.valueToTree(Map.of("taskId", Long.toString(taskId), "status", finalStatus,
                            "stageCode", finalStage, "code", errorCode)), now);
            return Optional.of(new ReportedTask(
                    new TaskDtos.InternalAccepted(true, finalStatus), browserEvent));
        }

        String retryStage = providerRecovery ? PROVIDER_RESULT_RECOVERY : "WORKER_LEASE_EXPIRED";
        String retryError = providerRecovery ? PROVIDER_RESULT_UNKNOWN : "WORKER_LEASE_EXPIRED";
        if (mapper.scheduleTaskRetry(taskId, retryStage, retryError, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        mapper.insertTaskEvent(ids.nextId(), taskId, task.userId(), execution.id(), null, null,
                "RUNNING", "RETRY_WAIT", "EXECUTION_LEASE_EXPIRED", "JAVA",
                retryError, null, "{}", now);
        insertExecutionOutbox(task, nextExecutionNo, now);
        ConversationEventRecord browserEvent = insertConversationEvent(task, "task.progress",
                json.valueToTree(Map.of("taskId", Long.toString(taskId), "status", "RETRY_WAIT",
                        "stageCode", retryStage, "executionNo", nextExecutionNo)), now);
        return Optional.of(new ReportedTask(
                new TaskDtos.InternalAccepted(true, "RETRY_WAIT"), browserEvent));
        }
    }

    @Transactional
    public Optional<ReportedTask> reconcileStaleQueuedTask() {
        OffsetDateTime now = now();
        OffsetDateTime cutoff = now.minus(properties.getMaxQueueAge());
        Long taskId = mapper.findStaleQueuedTaskId(cutoff).orElse(null);
        if (taskId == null) return Optional.empty();
        TaskRecords.Task task = mapper.lockTask(taskId).orElse(null);
        if (task == null || !"QUEUED".equals(task.status()) || task.queuedAt().isAfter(cutoff)
                || mapper.terminateStaleQueuedTask(taskId, cutoff, now) != 1) {
            return Optional.empty();
        }
        try (ObservabilitySupport.Operation span = observability.startConsumer(
                "java.task.recover", "DISPATCH_EXPIRED", null, true)) {
            span.tag("taskIdRef", observability.reference(task.id()));
            span.tag("taskType", task.taskType());
        if (mapper.releaseReservation(taskId, task.quotedPoints(), now) != 1
                || releasePoints(task.userId(), task.id(), task.taskType(), task.quotedPoints(), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if ("GEO".equals(task.taskType())) requireGeoTasks().markFailed(task, "SYSTEM_TERMINATED", now);
        mapper.insertTaskEvent(ids.nextId(), taskId, task.userId(), null, null, null,
                "QUEUED", "SYSTEM_TERMINATED", "DISPATCH_EXPIRED", "SCHEDULER",
                "DISPATCH_EXPIRED", null, "{}", now);
        TaskRecords.PointAccount after = mapper.findPointAccount(task.userId()).orElseThrow();
        mapper.insertLedger(ids.nextId(), task.userId(), task.quotedPoints(), -task.quotedPoints(),
                after.availablePoints(), after.frozenPoints(), "TASK_RELEASE", taskId,
                "task-release:" + taskId, task.taskType() + " dispatch expired before worker claim", now);
        notifyWorkflowTerminal(task, "SYSTEM_TERMINATED", "DISPATCH_EXPIRED", now);
        ConversationEventRecord browserEvent = insertConversationEvent(task, "task.failed",
                json.valueToTree(Map.of("taskId", Long.toString(taskId),
                        "status", "SYSTEM_TERMINATED", "stageCode", "DISPATCH_EXPIRED",
                        "code", "DISPATCH_EXPIRED")), now);
        return Optional.of(new ReportedTask(
                new TaskDtos.InternalAccepted(true, "SYSTEM_TERMINATED"), browserEvent));
        }
    }

    private TaskRecords.Execution requireExecution(long taskId, int executionNo, String token, boolean requireLease) {
        TaskRecords.Execution execution = requireExecutionIdentity(taskId, executionNo, token);
        if (!"RUNNING".equals(execution.status())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (requireLease && now().isAfter(execution.leaseExpiresAt())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return execution;
    }

    private TaskRecords.Execution requireExecutionIdentity(long taskId, int executionNo, String token) {
        TaskRecords.Execution execution = mapper.findLatestExecution(taskId)
                .filter(x -> x.executionNo() == executionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        if (!constantEquals(execution.executionTokenHash(), sha256(token))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return execution;
    }

    private TaskDtos.InternalAccepted replayIfSeen(long taskId, UUID eventId, int executionNo,
                                                    String token, String requestHash) {
        TaskRecords.Execution execution = requireExecutionIdentity(taskId, executionNo, token);
        String storedHash = mapper.findTaskEventRequestHash(taskId, eventId).orElse(null);
        if (storedHash == null) return null;
        if (!constantEquals(storedHash, requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        TaskRecords.Task task = mapper.findTaskInternal(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (execution.userId() != task.userId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return new TaskDtos.InternalAccepted(true, task.status());
    }

    private TaskDtos.InternalAccepted requireReplay(long taskId, UUID eventId, int executionNo,
                                                     String token, String requestHash) {
        TaskDtos.InternalAccepted replay = replayIfSeen(taskId, eventId, executionNo, token, requestHash);
        if (replay == null) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        return replay;
    }

    private FailureDetails validateFailureDetails(JsonNode details, TaskRecords.Execution execution) {
        if (details == null || !details.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String code = details.path("code").textValue();
        String submission = details.path("submissionState").textValue();
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if ("NOT_SUBMITTED".equals(submission) || "CONFIRMED".equals(submission)) {
            if (!names(details).equals(TERMINAL_FAILURE_FIELDS)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return new FailureDetails(code, submission, null, null, null, null);
        }
        String provider = details.path("provider").textValue();
        String model = details.path("model").textValue();
        // Synchronous article generation has no queryable continuation receipt. An in-flight
        // request must be recorded as UNKNOWN, never CONFIRMED/NOT_SUBMITTED or retried as media.
        boolean articleUnknown = "DEEPSEEK".equals(provider) && model != null
                && model.matches("^deepseek-[A-Za-z0-9._-]{1,112}$")
                && Set.of("ARTICLE_DEADLINE_EXCEEDED", "DEEPSEEK_SUBMISSION_UNKNOWN").contains(code)
                && details.path("providerRequestId").isNull() && details.path("providerTaskId").isNull();
        boolean supportedProvider = (QWEN_IMAGE_PROVIDER.equals(provider) && QWEN_IMAGE_MODEL.equals(model))
                || (WAN_IMAGE_EDIT_PROVIDER.equals(provider) && WAN_IMAGE_EDIT_MODEL.equals(model))
                || (WAN_VIDEO_PROVIDER.equals(provider) && WAN_VIDEO_MODEL.equals(model)) || articleUnknown;
        if (!"UNKNOWN".equals(submission) || !names(details).equals(UNKNOWN_FAILURE_FIELDS)
                || !supportedProvider
                || (!details.path("providerTaskId").isNull() && !isProviderTaskId(details.path("providerTaskId")))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        UUID providerRequestId = details.path("providerRequestId").isNull()
                ? null : strictUuid(details.path("providerRequestId"));
        FailureDetails failure = new FailureDetails(code, submission, provider,
                model, providerRequestId, details.path("providerTaskId").textValue());
        TaskDtos.ExecutionContextResponse.ProviderContinuation existing = providerContinuation(execution);
        if (existing != null && (!existing.provider().equals(failure.provider())
                || !existing.model().equals(failure.model())
                || failure.providerRequestId() == null
                || !existing.providerRequestId().equals(failure.providerRequestId().toString())
                || !existing.providerTaskId().equals(failure.providerTaskId()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return failure;
    }

    private UUID strictUuid(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            UUID parsed = UUID.fromString(value.textValue());
            if (!parsed.toString().equals(value.textValue())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private boolean isProviderTaskId(JsonNode value) {
        return value != null && value.isTextual()
                && value.textValue().matches("^[A-Za-z0-9_-]{1,256}$");
    }

    private TaskDtos.ExecutionContextResponse.ProviderContinuation providerContinuation(
            TaskRecords.Execution execution) {
        if (execution == null) return null;
        boolean allAbsent = execution.providerCode() == null && execution.providerModel() == null
                && execution.providerRequestId() == null && execution.providerTaskId() == null
                && execution.submissionState() == null;
        if (allAbsent) return null;
        boolean supported = (QWEN_IMAGE_PROVIDER.equals(execution.providerCode())
                && QWEN_IMAGE_MODEL.equals(execution.providerModel()))
                || (WAN_IMAGE_EDIT_PROVIDER.equals(execution.providerCode())
                && WAN_IMAGE_EDIT_MODEL.equals(execution.providerModel()))
                || (WAN_VIDEO_PROVIDER.equals(execution.providerCode())
                && WAN_VIDEO_MODEL.equals(execution.providerModel()));
        if (!supported
                || (execution.providerTaskId() != null
                && !execution.providerTaskId().matches("^[A-Za-z0-9_-]{1,256}$"))
                || !"UNKNOWN".equals(execution.submissionState())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (execution.providerRequestId() == null || execution.providerTaskId() == null) return null;
        return new TaskDtos.ExecutionContextResponse.ProviderContinuation(true,
                execution.providerCode(), execution.providerModel(),
                execution.providerRequestId().toString(), execution.providerTaskId(),
                execution.submissionState());
    }

    private void requireContinuationMatchesTask(TaskRecords.Task task,
                                                 TaskDtos.ExecutionContextResponse.ProviderContinuation continuation) {
        if (continuation == null) return;
        boolean matches = ("IMAGE".equals(task.taskType())
                && imageProvider(task).equals(continuation.provider()) && imageModel(task).equals(continuation.model()))
                || ("VIDEO".equals(task.taskType()) && WAN_VIDEO_PROVIDER.equals(continuation.provider()));
        if (!matches) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
    }

    private boolean isRegionEdit(TaskRecords.Task task) {
        return "IMAGE".equals(task.taskType())
                && "REGION_EDIT".equals(read(task.inputSnapshotJson()).path("parameters").path("sourceMode").asText());
    }

    private String imageProvider(TaskRecords.Task task) {
        return isRegionEdit(task) ? WAN_IMAGE_EDIT_PROVIDER : QWEN_IMAGE_PROVIDER;
    }

    private String imageModel(TaskRecords.Task task) {
        return isRegionEdit(task) ? WAN_IMAGE_EDIT_MODEL : QWEN_IMAGE_MODEL;
    }

    private void insertExecutionOutbox(TaskRecords.Task task, int executionAttempt,
                                       OffsetDateTime now) {
        long eventId = ids.nextId();
        String traceparent = TraceIdHolder.outboundTraceparent();
        ObjectNode payload = json.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("eventId", Long.toString(eventId));
        payload.put("eventType", "TASK_EXECUTION_REQUESTED");
        payload.put("taskId", Long.toString(task.id()));
        payload.put("taskType", task.taskType());
        payload.put("userId", Long.toString(task.userId()));
        payload.put("spaceId", Long.toString(task.spaceId()));
        payload.put("attempt", executionAttempt);
        payload.put("traceparent", traceparent);
        payload.put("createdAt", now.toString());
        if (mapper.insertOutbox(new TaskRecords.Outbox(eventId, task.userId(), task.id(), executionAttempt,
                        canonical(payload), traceparent, "PENDING", 0, now, now),
                properties.getExchange(), properties.routingKey(task.taskType())) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
    }

    private ValidatedProposal validateProposal(AgentRunRecord run, JsonNode proposal) {
        Set<String> fields = names(proposal);
        boolean attachmentCreative = Set.of("IMAGE", "VIDEO").contains(proposal.path("taskType").asText())
                && run.selectedProductId() == null && !attachmentIds(run).isEmpty();
        if (!proposal.isObject() || !fields.containsAll(DRAFT_REQUIRED_FIELDS) || !DRAFT_FIELDS.containsAll(fields)
                || !proposal.path("proposalOnly").isBoolean() || !proposal.path("proposalOnly").booleanValue()
                || !proposal.path("parameterSchemaVersion").isInt()
                || run.selectedSpaceId() == null
                || !(Long.toString(run.selectedSpaceId()).equals(proposal.path("spaceId").textValue())
                    || attachmentCreative && proposal.path("spaceId").isNull())
                || !proposalProductMatches(run.selectedProductId(), proposal.get("productId"))
                || !proposal.path("parameters").isObject()
                || !proposal.path("warnings").isArray() || proposal.path("warnings").size()>20) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        String taskType = proposal.path("taskType").textValue();
        if (!TASK_TYPES.contains(taskType) || "MEDIA".equals(taskType)) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        JsonNode frozenIdentity = read(run.contextSnapshotJson()).get("agentIdentity");
        if (frozenIdentity != null) {
            JsonNode capabilities = frozenIdentity.path("capabilities");
            boolean permitted = capabilities.isArray() && java.util.stream.StreamSupport
                    .stream(capabilities.spliterator(), false)
                    .anyMatch(capability -> taskCapability(taskType).equals(capability.asText()));
            if (!permitted) throw new IllegalArgumentException("AGENT_CAPABILITY_DENIED");
        }
        if (proposal.path("parameterSchemaVersion").intValue()
                != expectedParameterSchemaVersion(taskType)) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        if (!("GEO".equals(taskType) || "ARTICLE".equals(taskType))
                && run.selectedProductId() == null && attachmentIds(run).isEmpty()) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        ObjectNode normalized = validateAndNormalizeTaskParameters(taskType, proposal.path("parameters"));
        for (JsonNode warning : proposal.path("warnings")) if (!boundedText(warning,500)) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        return new ValidatedProposal(taskType, normalized);
    }

    private String taskCapability(String taskType) {
        return switch (taskType) {
            case "GEO" -> "geo.monitor";
            case "ARTICLE" -> "article.generate";
            case "IMAGE" -> "image.generate";
            case "VIDEO" -> "video.generate";
            case "MEDIA" -> "media.recommend"; // Publication still requires the existing explicit final token.
            default -> "unsupported";
        };
    }

    private ObjectNode validateAndNormalizeTaskParameters(String taskType, JsonNode p) {
        if (!p.isObject() || !TASK_TYPES.contains(taskType)) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        if ("GEO".equals(taskType) || "ARTICLE".equals(taskType)) return (ObjectNode) p.deepCopy();
        if ("IMAGE".equals(taskType)) {
            if (!IMAGE_PARAMETER_FIELDS.containsAll(names(p))
                    || !boundedText(p.path("requestText"), 16000)
                    || !"AGENT_ASSISTED".equals(p.path("mode").textValue())
                    || !boundedText(p.path("agentSummary"), 1000) || !p.path("count").isInt()
                    || p.path("count").intValue() < 1 || p.path("count").intValue() > 4
                    || !boundedOptionalText(p.get("scene"),1000)
                    || !boundedOptionalText(p.get("style"),256) || !boundedOptionalText(p.get("lighting"),256)
                    || !boundedOptionalText(p.get("negativePrompt"),1000)
                    || !p.path("aspectRatio").isTextual()
                    || !p.path("aspectRatio").textValue().matches("^(1:1|3:4|4:3|9:16|16:9)$")) {
                throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
            }
            validateVisualBrief(p.get("visualBrief"));
            return (ObjectNode) p.deepCopy();
        }
        return validateAndNormalizeVideoParameters(p);
    }

    private void validateVisualBrief(JsonNode brief) {
        if (brief == null) return;
        if (!brief.isObject() || !names(brief).equals(VISUAL_BRIEF_FIELDS)
                || !"commerce-image-v1".equals(brief.path("version").asText())
                || !Set.of("GENERAL", "PINDUODUO", "TAOBAO", "JD", "DOUYIN", "XIAOHONGSHU")
                    .contains(brief.path("platform").asText())
                || !Set.of("MAIN_IMAGE", "DETAIL_IMAGE", "SOCIAL_COVER", "POSTER")
                    .contains(brief.path("placement").asText())
                || !boundedText(brief.path("visualFocus"), 160)
                || !boundedText(brief.path("composition"), 240)
                || !boundedText(brief.path("background"), 180)
                || !boundedText(brief.path("lighting"), 160)
                || !Set.of("NONE", "USER_SUPPLIED").contains(brief.path("textPolicy").asText())
                || !brief.path("copyLines").isArray() || brief.path("copyLines").size() > 3
                || ("NONE".equals(brief.path("textPolicy").asText()) != brief.path("copyLines").isEmpty())) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        for (JsonNode line : brief.path("copyLines")) {
            if (!boundedText(line, 40)) throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
    }

    private List<Long> attachmentIds(AgentRunRecord run) {
        JsonNode value = read(run.attachmentAssetIdsJson());
        if (!value.isArray()) return List.of();
        java.util.ArrayList<Long> result = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) return List.of();
            try {
                result.add(Long.parseLong(item.textValue()));
            } catch (NumberFormatException exception) {
                return List.of();
            }
        }
        return List.copyOf(result);
    }

    private void validateProposedCreativeSource(JsonNode proposal,
                                                CreativeTaskSourceService.PreparedSource authoritative,
                                                long requestMessageId) {
        if (proposal.hasNonNull("sourceMode")
                && (!proposal.path("sourceMode").isTextual()
                || !authoritative.parameters().path("sourceMode").asText()
                .equals(proposal.path("sourceMode").textValue()))) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        if (proposal.hasNonNull("referenceAssetIds")
                && (!proposal.path("referenceAssetIds").isArray()
                || !canonical(authoritative.parameters().path("referenceAssetIds"))
                .equals(canonical(proposal.path("referenceAssetIds"))))) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        if (proposal.hasNonNull("sourceMessageId")
                && (!proposal.path("sourceMessageId").isTextual()
                || !Long.toString(requestMessageId).equals(proposal.path("sourceMessageId").textValue()))) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        // ProductCreativeBrief is deliberately rebuilt from Java-owned facts and never trusted from the model.
    }

    private ObjectNode validateAndNormalizeVideoParameters(JsonNode p) {
        if (!VIDEO_PARAMETER_FIELDS.containsAll(names(p))
                || !boundedText(p.path("requestText"), 16000)
                || !"AGENT_ASSISTED".equals(p.path("mode").textValue())
                || !boundedText(p.path("agentSummary"), 1000)
                || !boundedText(p.path("scene"), 1000)
                || !boundedOptionalText(p.get("style"), 256)
                || !boundedOptionalText(p.get("cameraMotion"), 256)) {
            throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        }
        int duration = integerOrDefault(p.get("durationSeconds"), 5, 2, 30);
        int seed = integerOrDefault(p.get("seed"), -1, -1, Integer.MAX_VALUE);
        String aspectRatio = textOrDefault(p.get("aspectRatio"), "adaptive",
                Set.of("adaptive", "16:9", "4:3", "1:1", "3:4", "9:16"));
        String resolution = textOrDefault(p.get("resolution"), "720P",
                Set.of("480P", "720P", "1080P"));
        boolean audio = booleanOrDefault(p.get("audio"), false);
        boolean promptExtend = booleanOrDefault(p.get("promptExtend"), true);
        boolean watermark = booleanOrDefault(p.get("watermark"), false);
        ObjectNode normalized = json.createObjectNode();
        normalized.put("requestText", p.path("requestText").textValue().trim());
        normalized.put("mode", "AGENT_ASSISTED");
        normalized.put("agentSummary", p.path("agentSummary").textValue().trim());
        if (p.has("sourceMode")) normalized.put("sourceMode", p.path("sourceMode").asText());
        if (p.has("referenceAssetIds")) {
            normalized.set("referenceAssetIds", p.path("referenceAssetIds").deepCopy());
        }
        if (p.has("geoReportId")) normalized.set("geoReportId", p.path("geoReportId").deepCopy());
        if (p.has("geoRecommendationId")) {
            normalized.set("geoRecommendationId", p.path("geoRecommendationId").deepCopy());
        }
        if (p.has("creativePlan")) normalized.set("creativePlan", p.path("creativePlan").deepCopy());
        normalized.put("scene", p.path("scene").textValue().trim());
        putTrimmedOptional(normalized, "style", p.get("style"));
        normalized.put("durationSeconds", duration);
        normalized.put("aspectRatio", aspectRatio);
        putTrimmedOptional(normalized, "cameraMotion", p.get("cameraMotion"));
        normalized.put("resolution", resolution);
        normalized.put("audio", audio);
        normalized.put("seed", seed);
        normalized.put("promptExtend", promptExtend);
        normalized.put("watermark", watermark);
        return normalized;
    }

    private ValidatedTaskResult validateTaskResult(TaskRecords.Task task, TaskRecords.Execution execution,
                                                    TaskDtos.ResultArtifactsRequest request) {
        if (!"CONFIRMED".equals(request.submissionState())
                || request.artifacts() == null || request.artifacts().isEmpty()
                || request.artifacts().size() > 4) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if ("VIDEO".equals(task.taskType())) {
            if (request.artifacts().size() != 1) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            return validateWanVideoResult(task, execution, request);
        }
        if (!"IMAGE".equals(task.taskType())) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        int requestedCount = imageCount(read(task.inputSnapshotJson()).path("parameters"));
        if (request.artifacts().size() > requestedCount) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Set<String> urls = new java.util.HashSet<>();
        for (JsonNode artifact : request.artifacts()) {
            if (artifact == null || !artifact.isObject()
                    || !urls.add(artifact.path("providerUrl").asText())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        boolean mock = !isRegionEdit(task) && MOCK_PROVIDER.equals(request.provider()) && MOCK_MODEL.equals(request.model());
        boolean providerMatches = imageProvider(task).equals(request.provider()) && imageModel(task).equals(request.model());
        if (!mock && !providerMatches) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (mock) {
            if (providerContinuation(execution) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (request.artifacts().size() != requestedCount) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            for (int index = 0; index < request.artifacts().size(); index++) {
                validateMockResult(request, request.artifacts().get(index), index + 1);
            }
            return new ValidatedTaskResult(true, false, request.usage());
        }
        for (int index = 0; index < request.artifacts().size(); index++) {
            validateImageResult(request, request.artifacts().get(index), index + 1, isRegionEdit(task));
        }
        TaskDtos.ExecutionContextResponse.ProviderContinuation continuation =
                providerContinuation(execution);
        requireContinuationMatchesTask(task, continuation);
        if (continuation != null && (!request.requestId().toString().equals(
                continuation.providerRequestId())
                || !request.providerTaskId().equals(continuation.providerTaskId()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ObjectNode persistedUsage = (ObjectNode) request.usage().deepCopy();
        persistedUsage.putNull("providerCost");
        persistedUsage.putNull("currency");
        persistedUsage.put("providerCostReported", false);
        return new ValidatedTaskResult(false, false, persistedUsage);
    }

    private void validateMockResult(TaskDtos.ResultArtifactsRequest request, JsonNode artifact, int index) {
        JsonNode usage = request.usage();
        String requestId = request.requestId().toString();
        if (!("mock-image-" + requestId).equals(request.providerTaskId())
                || artifact == null || usage == null || !artifact.isObject()
                || !ARTIFACT_FIELDS.containsAll(names(artifact))
                || !"IMAGE".equals(artifact.path("artifactType").textValue())
                || !artifact.path("schemaVersion").isInt() || artifact.path("schemaVersion").intValue()!=1
                || !artifact.path("providerUrl").isTextual()
                || !("https://mock-provider.invalid/image/" + requestId + (index == 1 ? "" : "-" + index) + ".png")
                .equals(artifact.path("providerUrl").textValue())
                || !"image/png".equals(artifact.path("contentType").textValue())
                || !nullOrText(artifact.get("unitKey")) || !nullOrNonNegativeInteger(artifact.get("sizeBytes"))
                || !nullOrSha256(artifact.get("sha256")) || !nullOrText(artifact.get("expiresAt"))
                || !nullOrObject(artifact.get("inlineData")) || (artifact.hasNonNull("inlineData"))
                || !artifact.path("metadata").path("mock").isBoolean()
                || !artifact.path("metadata").path("mock").booleanValue()
                || !names(artifact.path("metadata")).equals(Set.of("width", "height", "mock"))
                || !artifact.path("metadata").path("width").isInt()
                || artifact.path("metadata").path("width").intValue()!=1024
                || !artifact.path("metadata").path("height").isInt()
                || artifact.path("metadata").path("height").intValue()!=1024
                || !usage.isObject()
                || !names(usage).equals(USAGE_FIELDS)
                || !usage.path("inputTokens").isInt() || usage.path("inputTokens").intValue()!=0
                || !usage.path("outputTokens").isInt() || usage.path("outputTokens").intValue()!=0
                || !usage.path("images").isInt() || usage.path("images").intValue()!=request.artifacts().size()
                || !usage.path("videoSeconds").isInt() || usage.path("videoSeconds").intValue()!=0
                || !"0.0000".equals(usage.path("providerCost").textValue())
                || !"CNY".equals(usage.path("currency").textValue())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private ValidatedTaskResult validateWanVideoResult(TaskRecords.Task task,
                                                        TaskRecords.Execution execution,
                                                        TaskDtos.ResultArtifactsRequest request) {
        if (!WAN_VIDEO_PROVIDER.equals(request.provider()) || !WAN_VIDEO_MODEL.equals(request.model())
                || request.providerTaskId() == null
                || !request.providerTaskId().matches("^[A-Za-z0-9_-]{1,256}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        JsonNode artifact = request.artifacts().getFirst();
        JsonNode usage = request.usage();
        JsonNode parameters = read(task.inputSnapshotJson()).path("parameters");
        JsonNode metadata = artifact == null ? null : artifact.path("metadata");
        int requestedDuration = parameters.path("durationSeconds").intValue();
        if (artifact == null || !artifact.isObject() || usage == null || !usage.isObject()
                || !ARTIFACT_FIELDS.containsAll(names(artifact))
                || !"VIDEO".equals(artifact.path("artifactType").textValue())
                || !artifact.path("schemaVersion").isInt() || artifact.path("schemaVersion").intValue() != 1
                || !isAllowedProviderHttpsUrl(artifact.path("providerUrl"))
                || !"video/mp4".equals(artifact.path("contentType").textValue())
                || !nullOrVideoUnitKey(artifact.get("unitKey"))
                || !nullOrNonNegativeInteger(artifact.get("sizeBytes"))
                || !nullOrSha256(artifact.get("sha256"))
                || (artifact.has("expiresAt") && !artifact.path("expiresAt").isNull())
                || !nullOrObject(artifact.get("inlineData")) || artifact.hasNonNull("inlineData")
                || metadata == null || !metadata.isObject()
                || !WAN_METADATA_FIELDS.containsAll(names(metadata))
                || !names(metadata).containsAll(Set.of("durationSeconds", "requestedDurationSeconds",
                "requestedAspectRatio", "requestedResolution", "mock", "providerRequestId"))
                || !metadata.path("mock").isBoolean() || metadata.path("mock").booleanValue()
                || !metadata.path("requestedDurationSeconds").isInt()
                || metadata.path("requestedDurationSeconds").intValue() != requestedDuration
                || !parameters.path("aspectRatio").textValue()
                .equals(metadata.path("requestedAspectRatio").textValue())
                || !parameters.path("resolution").textValue()
                .equals(metadata.path("requestedResolution").textValue())
                || !positiveFiniteNumber(metadata.path("durationSeconds"))
                || Math.abs(metadata.path("durationSeconds").doubleValue() - requestedDuration) > 0.75d
                || !request.requestId().toString().equals(metadata.path("providerRequestId").textValue())
                || !optionalBoundedTextField(metadata, "providerResultRequestId", 256)
                || !optionalPositiveNumber(metadata, "fps")
                || !optionalBoundedTextField(metadata, "resolution", 32)
                || !optionalBoundedTextField(metadata, "aspectRatio", 32)
                || !names(usage).equals(USAGE_FIELDS)
                || !usage.path("inputTokens").isInt() || usage.path("inputTokens").intValue() != 0
                || !usage.path("outputTokens").isInt() || usage.path("outputTokens").intValue() != 0
                || !usage.path("images").isInt() || usage.path("images").intValue() != 0
                || !usage.path("videoSeconds").isInt()
                || usage.path("videoSeconds").intValue() != requestedDuration
                || !usage.path("providerCost").isNull() || !usage.path("currency").isNull()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        TaskDtos.ExecutionContextResponse.ProviderContinuation continuation = providerContinuation(execution);
        if (continuation != null && (!request.requestId().toString().equals(continuation.providerRequestId())
                || !request.providerTaskId().equals(continuation.providerTaskId()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ObjectNode persistedUsage = (ObjectNode) usage.deepCopy();
        persistedUsage.put("providerCostReported", false);
        return new ValidatedTaskResult(false, true, persistedUsage);
    }

    private void validateImageResult(TaskDtos.ResultArtifactsRequest request, JsonNode artifact, int index, boolean regionEdit) {
        JsonNode usage = request.usage();
        if (artifact == null || usage == null || !artifact.isObject() || !usage.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        JsonNode metadata = artifact.path("metadata");
        JsonNode providerCost = usage.get("providerCost");
        JsonNode currency = usage.get("currency");
        boolean unknownCost = providerCost != null && providerCost.isNull()
                && currency != null && currency.isNull();
        boolean legacyUnknownCost = providerCost != null && providerCost.isTextual()
                && "0.0000".equals(providerCost.textValue())
                && currency != null && currency.isTextual() && "CNY".equals(currency.textValue());
        if (request.providerTaskId() == null
                || !request.providerTaskId().matches("^[A-Za-z0-9_-]{1,256}$")
                || !ARTIFACT_FIELDS.containsAll(names(artifact))
                || !"IMAGE".equals(artifact.path("artifactType").textValue())
                || !artifact.path("schemaVersion").isInt() || artifact.path("schemaVersion").intValue() != 1
                || (regionEdit ? !isAllowedProviderHttpsUrl(artifact.path("providerUrl"))
                        : !isValidHttpsUrl(artifact.path("providerUrl")))
                || (regionEdit ? !artifact.path("contentType").isNull()
                        : !"image/png".equals(artifact.path("contentType").textValue()))
                || !nullOrQwenUnitKey(artifact.get("unitKey"), index)
                || !nullOrNonNegativeInteger(artifact.get("sizeBytes"))
                || !nullOrSha256(artifact.get("sha256"))
                || (regionEdit && (artifact.hasNonNull("sizeBytes") || artifact.hasNonNull("sha256")))
                || !isFutureOffsetDateTime(artifact.get("expiresAt"))
                || !nullOrObject(artifact.get("inlineData")) || artifact.hasNonNull("inlineData")
                || !metadata.isObject() || !QWEN_METADATA_FIELDS.containsAll(names(metadata))
                || (regionEdit && metadata.has("outputImageType"))
                || !metadata.path("mock").isBoolean() || metadata.path("mock").booleanValue()
                || !metadata.path("index").isInt() || metadata.path("index").intValue() != index
                || (regionEdit ? !(metadata.path("width").isNull() && metadata.path("height").isNull())
                        : !(reasonableImageDimension(metadata.path("width")) && reasonableImageDimension(metadata.path("height"))))
                || !request.requestId().toString().equals(metadata.path("providerRequestId").textValue())
                || !optionalBoundedTextField(metadata, "providerResultRequestId", 256)
                || !optionalBoundedTextField(metadata, "outputImageType", 128)
                || !names(usage).equals(USAGE_FIELDS)
                || !usage.path("inputTokens").isInt() || usage.path("inputTokens").intValue() != 0
                || !usage.path("outputTokens").isInt() || usage.path("outputTokens").intValue() != 0
                || !usage.path("images").isInt() || usage.path("images").intValue() != request.artifacts().size()
                || !usage.path("videoSeconds").isInt() || usage.path("videoSeconds").intValue() != 0
                || (!unknownCost && (regionEdit || !legacyUnknownCost))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private boolean isValidHttpsUrl(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().length() > 2048) return false;
        try {
            URI uri = new URI(value.textValue());
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && !uri.getHost().isBlank() && uri.getUserInfo() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isFutureOffsetDateTime(JsonNode value) {
        if (value == null || !value.isTextual()) return false;
        try {
            return OffsetDateTime.parse(value.textValue()).isAfter(now());
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private boolean reasonableImageDimension(JsonNode value) {
        return value != null && value.isInt() && value.intValue() >= 8 && value.intValue() <= 8192;
    }

    private boolean nullOrQwenUnitKey(JsonNode value, int index) {
        return value == null || value.isNull() || (value.isTextual() && ("image:" + index).equals(value.textValue()));
    }

    private boolean nullOrVideoUnitKey(JsonNode value) {
        return value == null || value.isNull() || (value.isTextual() && "video:1".equals(value.textValue()));
    }

    private boolean positiveFiniteNumber(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                && value.doubleValue() > 0;
    }

    private boolean optionalPositiveNumber(JsonNode object, String field) {
        return !object.has(field) || positiveFiniteNumber(object.get(field));
    }

    private boolean isAllowedProviderHttpsUrl(JsonNode value) {
        if (!isValidHttpsUrl(value)) return false;
        URI uri = URI.create(value.textValue());
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return assetProperties.getProviderAllowedHosts().stream()
                .map(candidate -> candidate.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(candidate -> candidate.startsWith("*.")
                        ? host.endsWith(candidate.substring(1)) && host.length() > candidate.length() - 1
                        : host.equals(candidate));
    }

    private boolean optionalBoundedTextField(JsonNode object, String field, int max) {
        return !object.has(field) || boundedText(object.get(field), max);
    }

    private ArrayNode mockBrowserArtifacts(TaskDtos.ResultArtifactsRequest request) {
        return (ArrayNode) json.valueToTree(request.artifacts());
    }

    private boolean nullOrText(JsonNode value) {
        return value == null || value.isNull() || value.isTextual();
    }

    private boolean nullOrNonNegativeInteger(JsonNode value) {
        return value == null || value.isNull() || (value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= 0);
    }

    private boolean nullOrSha256(JsonNode value) {
        return value == null || value.isNull() || (value.isTextual()
                && value.textValue().matches("^[0-9a-f]{64}$"));
    }

    private boolean nullOrObject(JsonNode value) {
        return value == null || value.isNull() || value.isObject();
    }

    private record ValidatedProposal(String taskType, ObjectNode parameters) { }
    private record ValidatedTaskResult(boolean mock, boolean video, JsonNode persistedUsage) { }

    private record FailureDetails(String code, String submissionState, String provider,
                                  String model, UUID providerRequestId, String providerTaskId) { }

    private ConversationEventRecord draftBrowserEvent(AgentRunRecord run, PythonChatRunEvent source,
                                                        TaskRecords.Draft draft, String text, OffsetDateTime now) {
        TaskDtos.DraftResponse response = draftResponse(draft, tokenIfAvailable(draft));
        ObjectNode content = json.createObjectNode(); content.put("text", text); content.set("taskDraft", json.valueToTree(response));
        if (conversationMapper.updateAssistantInteraction(run.responseMessageId(), run.userId(), run.id(),
                canonical(content), now) != 1) throw new IllegalStateException("Assistant message missing");
        ObjectNode payload = json.createObjectNode(); payload.put("messageId", Long.toString(run.responseMessageId()));
        payload.set("draft", json.valueToTree(response));
        long sequence = conversationMapper.nextEventSequence(run.conversationId(), run.userId());
        ConversationEventRecord event = new ConversationEventRecord(ids.nextId(), run.conversationId(), run.userId(),
                run.id(), run.responseMessageId(), null, source.eventId(), "draft.created", sequence,
                canonical(payload), source.occurredAt(), now);
        if (conversationMapper.insertEvent(event)!=1) throw new IllegalStateException("Duplicate task draft event");
        return event;
    }

    private ConversationEventRecord insertConversationEvent(TaskRecords.Task task, String type, JsonNode payload,
                                                              OffsetDateTime now) {
        if (task.conversationId() == null) return null;
        ObjectNode taskPayload = payload != null && payload.isObject()
                ? (ObjectNode) payload.deepCopy() : json.createObjectNode();
        taskPayload.put("deliveryKind", "BACKGROUND_TASK");
        long sequence = conversationMapper.nextEventSequence(task.conversationId(), task.userId());
        ConversationEventRecord event = new ConversationEventRecord(ids.nextId(), task.conversationId(), task.userId(),
                null, null, task.id(), null, type, sequence, canonical(taskPayload), now, now);
        if (conversationMapper.insertEvent(event)!=1) throw new IllegalStateException("Task browser event insert failed");
        return event;
    }

    private TaskDtos.DraftResponse draftResponse(TaskRecords.Draft d, String token) {
        JsonNode parameters = read(d.parametersJson());
        String sourceMode = parameters.path("sourceMode").isTextual()
                ? parameters.path("sourceMode").textValue() : null;
        List<String> referenceAssetIds = parameters.path("referenceAssetIds").isArray()
                ? java.util.stream.StreamSupport.stream(
                parameters.path("referenceAssetIds").spliterator(), false)
                .filter(JsonNode::isTextual).map(JsonNode::textValue).toList() : List.of();
        return new TaskDtos.DraftResponse(Long.toString(d.id()),
                d.previousDraftId() == null ? null : Long.toString(d.previousDraftId()), d.draftSource(),
                d.taskType(), d.parameterSchemaVersion(), d.status(), Long.toString(d.spaceId()),
                d.productId()==null?null:Long.toString(d.productId()), sourceMode, referenceAssetIds,
                parameters, read(d.warningsJson()),
                d.inputHash(), Long.toString(d.pricingVersionId()), d.quotedPoints(), token, d.expiresAt(), d.createdAt());
    }

    private WorkflowPreparedDraft workflowPreparedDraft(TaskRecords.Draft draft) {
        return new WorkflowPreparedDraft(draft.id(), draft.taskType(), draft.pricingVersionId(),
                draft.quotedPoints(), draft.inputHash(), draft.expiresAt(), draft.userId(),
                draft.conversationId(), draft.spaceId(), draft.productId());
    }

    private boolean isWorkflowDraft(TaskRecords.Draft draft) {
        return "PLAN".equals(draft.draftSource())
                && "WORKFLOW_PLAN_DRAFT_CREATE".equals(draft.operationScope());
    }

    private TaskDtos.TaskResponse taskResponse(TaskRecords.Task t) {
        TaskRecords.TaskFunding funding = mapper.findTaskFunding(t.userId(), List.of(t.id())).stream()
                .filter(value -> value.taskId() == t.id()).findFirst().orElse(null);
        return taskResponse(t, funding);
    }

    private TaskDtos.TaskResponse taskResponse(TaskRecords.Task t, TaskRecords.TaskFunding funding) {
        String label = switch (t.stageCode()) { case "QUEUED" -> "等待执行"; case "CLAIMED" -> "已领取";
            case "ARTICLE_GENERATING" -> "正在生成文章内容";
            case "ARTICLE_BODY_STREAMING" -> "正在撰写正文";
            case "MEDIA_SUBMITTING" -> "正在提交媒体";
            case "MEDIA_PROCESSING" -> "媒体处理中";
            case "MEDIA_RECONCILING" -> "投稿结果核对中";
            case "PROVIDER_GENERATING" -> "VIDEO".equals(t.taskType()) ? "正在生成视频" : "正在生成图片";
            case "COMPLETED" -> "已完成";
            case "GEO_SAMPLING" -> "正在采集平台回答";
            case "GEO_RETRYING" -> "正在补试部分平台";
            case "GEO_SYNTHESIZING" -> "正在生成诊断报告";
            case "PARTIAL_COMPLETED" -> "部分采样完成";
            case "IMAGE_PARTIAL_COMPLETED" -> "部分图片已完成，未完成部分积分已退回";
            case "FAILED" -> "执行失败"; case PROVIDER_RESULT_UNKNOWN -> "结果确认中";
            case PROVIDER_RESULT_RECOVERY -> "恢复轮询中";
            case PROVIDER_RESULT_CONFIRMATION_FAILED -> "结果确认失败";
            case "ASSET_INGESTION_PENDING" -> "正在保存永久" + ("VIDEO".equals(t.taskType()) ? "视频" : "图片");
            case "ASSET_INGESTION_RETRY" -> "永久" + ("VIDEO".equals(t.taskType()) ? "视频" : "图片") + "保存重试中";
            case "ASSET_INGESTION_FAILED" -> "永久" + ("VIDEO".equals(t.taskType()) ? "视频" : "图片") + "保存失败";
            case "DISPATCH_EXPIRED" -> "排队超时，已安全终止";
            default -> "处理中"; };
        TaskDtos.TaskProgressResponse progress = geoTasks == null ? null : geoTasks.taskProgress(t);
        return new TaskDtos.TaskResponse(Long.toString(t.id()),t.taskType(),t.status(),t.stageCode(),label,
                Long.toString(t.spaceId()),t.productId()==null?null:Long.toString(t.productId()),t.quotedPoints(),
                funding == null ? null : funding.settledPoints(),funding == null ? null : funding.frozenPoints(),
                funding == null ? null : funding.releasedPoints(),progress,
                read(t.resultSummaryJson()),t.errorCode(),t.createdAt(),t.updatedAt(),
                "ARTICLE".equals(t.taskType()) && "ARTICLE_FULL_V1".equals(read(t.inputSnapshotJson()).path("articleFull").path("templateCode").asText())
                    ? read(t.inputSnapshotJson()).path("articleFull").path("planId").asText(null):null);
    }

    private TaskDtos.PointLedgerEntryResponse pointLedgerResponse(TaskRecords.PointLedgerEntry entry) {
        String title = switch (entry.entryType()) {
            case "ADMIN_INITIAL_GRANT" -> "开户赠送积分";
            case "TASK_FREEZE" -> "任务已受理，积分已冻结";
            case "TASK_SETTLE" -> "任务完成，积分已结算";
            case "TASK_RELEASE" -> "任务未完成，积分已返还";
            case "WORKFLOW_FREEZE" -> "工作流确认，积分已冻结";
            case "WORKFLOW_ALLOCATE" -> "工作流额度已分配到子任务";
            case "WORKFLOW_TASK_SETTLE" -> "工作流子任务完成，积分已结算";
            case "WORKFLOW_TASK_RELEASE" -> "工作流子任务未完成，积分已返还";
            case "WORKFLOW_SETTLE" -> "工作流完成，积分已结算";
            case "WORKFLOW_RELEASE" -> "工作流结束，剩余积分已返还";
            case "RECHARGE" -> "充值到账";
            case "ADMIN_ADJUSTMENT" -> "管理员人工补赠";
            default -> "积分变动";
        };
        int amountPoints = Math.max(Math.abs(entry.availableDelta()), Math.abs(entry.frozenDelta()));
        return new TaskDtos.PointLedgerEntryResponse(Long.toString(entry.id()), entry.entryType(), title,
                entry.businessType(), Long.toString(entry.businessId()), amountPoints,
                entry.availableDelta(), entry.frozenDelta(), entry.availableAfter(), entry.frozenAfter(),
                entry.createdAt());
    }

    private String tokenIfAvailable(TaskRecords.Draft d) {
        return !isWorkflowDraft(d) && "WAITING_CONFIRMATION".equals(d.status()) && now().isBefore(d.expiresAt())
                ? confirmationToken(d)
                : null;
    }
    public String confirmationToken(TaskRecords.Draft d) {
        return confirmationTokens.token(d);
    }
    private String executionToken(TaskRecords.Execution x) {
        String value="execute|"+x.id()+"|"+x.taskId()+"|"+x.userId()+"|"+x.executionNo()+"|"+x.workerId()+"|"+x.leaseExpiresAt().toEpochSecond();
        return "v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(value));
    }
    private byte[] hmac(String value) { try { Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.getTokenSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); } catch(Exception e){throw new IllegalStateException(e);} }
    private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e){throw new IllegalStateException(e);} }
    private boolean constantEquals(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}
    private UUID uuid(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value)?UUID.fromString(value):bad();}catch(Exception e){throw new BusinessException(ErrorCode.VALIDATION_FAILED);}}
    private UUID bad(){throw new BusinessException(ErrorCode.VALIDATION_FAILED);}
    private long positiveLong(String value){try{long v=Long.parseLong(value);if(v<=0)throw new NumberFormatException();return v;}catch(Exception e){throw new BusinessException(ErrorCode.VALIDATION_FAILED);}}
    private Long optionalPositiveLong(String value){return value==null?null:positiveLong(value);}
    private String optionalEnum(String value,Set<String> allowed){if(value==null)return null;if(!allowed.contains(value))throw new BusinessException(ErrorCode.VALIDATION_FAILED);return value;}
    private int integerOrDefault(JsonNode value,int fallback,int min,int max){
        if(value==null||value.isNull())return fallback;
        if(!value.isInt()||value.intValue()<min||value.intValue()>max)throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        return value.intValue();
    }
    private boolean booleanOrDefault(JsonNode value,boolean fallback){
        if(value==null||value.isNull())return fallback;
        if(!value.isBoolean())throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        return value.booleanValue();
    }
    private String textOrDefault(JsonNode value,String fallback,Set<String> allowed){
        if(value==null||value.isNull())return fallback;
        if(!value.isTextual()||!allowed.contains(value.textValue()))throw new IllegalArgumentException("TASK_DRAFT_SCHEMA_INVALID");
        return value.textValue();
    }
    private void putTrimmedOptional(ObjectNode target,String field,JsonNode value){
        if(value!=null&&!value.isNull())target.put(field,value.textValue().trim());
    }
    private String safeStage(String value){if(value==null||!value.matches("[A-Z][A-Z0-9_]{0,63}"))throw new BusinessException(ErrorCode.VALIDATION_FAILED);return value;}
    private boolean boundedText(JsonNode n,int max){return n!=null&&n.isTextual()&&!n.textValue().isBlank()&&n.textValue().length()<=max;}
    private boolean boundedOptionalText(JsonNode n,int max){return n==null||n.isNull()||boundedText(n,max);}
    private Set<String> names(JsonNode n){Set<String>s=new HashSet<>();n.fieldNames().forEachRemaining(s::add);return s;}
    private JsonNode read(String value){try{return json.readTree(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private String canonical(Object value){JsonNode n=value instanceof JsonNode node?node:json.valueToTree(value);return write(sort(n));}
    private JsonNode sort(JsonNode n){if(n.isObject()){ObjectNode o=json.createObjectNode();List<String> names=new java.util.ArrayList<>();n.fieldNames().forEachRemaining(names::add);names.sort(Comparator.naturalOrder());for(String k:names)o.set(k,sort(n.get(k)));return o;}if(n.isArray()){ArrayNode a=json.createArrayNode();n.forEach(v->a.add(sort(v)));return a;}return n;}
    private String write(JsonNode n){try{return json.writeValueAsString(n);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}

    public record ScheduledTaskResult(boolean dispatched, Long taskId, Integer quotedPoints,
                                      String errorCode) {
        static ScheduledTaskResult dispatched(long taskId, int points) {
            return new ScheduledTaskResult(true, taskId, points, null);
        }
        static ScheduledTaskResult skipped(String code, Integer points) {
            return new ScheduledTaskResult(false, null, points, code);
        }
    }

    private void validateChatArticleReportBinding(AgentRunRecord run, ObjectNode parameters, TaskRecords.Draft previous) {
        JsonNode requested = parameters.get("geoReportId");
        if (previous != null) {
            JsonNode bound = read(previous.parametersJson()).path("geoReportId");
            boolean hasBound = !bound.isMissingNode() && !bound.isNull();
            if (requested != null && !requested.isNull() && (!hasBound || !requested.equals(bound))) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            // An authorized revision keeps its original source even if a newer report now exists.
            // ArticleService still validates that original report's current ownership and usability.
            if (hasBound) parameters.set("geoReportId", bound.deepCopy());
            return;
        }
        if (requested == null || requested.isNull()) return;
        JsonNode frozen = read(run.contextSnapshotJson()).path("geoInsightContext");
        JsonNode report = frozen.path("report");
        // The model may refer to the report selected for this exact Run, never choose another
        // report (even an owned one) or recover a report from historical assistant text.
        if (!requested.isTextual() || !"READY".equals(frozen.path("status").asText())
                || !"EXPLICIT_REPORT".equals(frozen.path("selectionSource").asText())
                || !requested.equals(report.path("reportId"))
                || run.selectedSpaceId() == null
                || !Long.toString(run.selectedSpaceId()).equals(report.path("spaceId").asText())
                || !java.util.Objects.equals(run.selectedProductId() == null ? null
                        : Long.toString(run.selectedProductId()), report.path("productId").textValue())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private ObjectNode browserParameters(String taskType, JsonNode parameters) {
        try {
            return validateAndNormalizeTaskParameters(taskType, parameters);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private int expectedParameterSchemaVersion(String taskType) {
        return "ARTICLE".equals(taskType) ? 2 : 1;
    }

    private void requireParameterSchemaVersion(String taskType, Integer version) {
        if (version == null || version != expectedParameterSchemaVersion(taskType)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String draftRequestHash(String scope, Long previousDraftId, long conversationId,
                                    String taskType, long spaceId, Long productId, Long sourceMessageId,
                                    int parameterSchemaVersion, JsonNode parameters) {
        ObjectNode fingerprint = json.createObjectNode();
        fingerprint.put("scope", scope);
        if (previousDraftId == null) fingerprint.putNull("previousDraftId");
        else fingerprint.put("previousDraftId", Long.toString(previousDraftId));
        fingerprint.put("conversationId", Long.toString(conversationId));
        fingerprint.put("taskType", taskType);
        fingerprint.put("spaceId", Long.toString(spaceId));
        if (productId == null) fingerprint.putNull("productId");
        else fingerprint.put("productId", Long.toString(productId));
        if (sourceMessageId == null) fingerprint.putNull("sourceMessageId");
        else fingerprint.put("sourceMessageId", Long.toString(sourceMessageId));
        fingerprint.put("parameterSchemaVersion", parameterSchemaVersion);
        fingerprint.set("parameters", parameters);
        return sha256(canonical(fingerprint));
    }

    private TaskRecords.Draft replayDraft(long userId, String scope, UUID key, String requestHash) {
        TaskRecords.Draft existing = mapper.findDraftByIdempotency(userId, scope, key).orElse(null);
        if (existing != null && !constantEquals(existing.requestHash(), requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return existing;
    }

    private TaskRecords.Draft newFormDraft(long userId, long conversationId, Long previousDraftId,
                                           String taskType, long spaceId, Long productId,
                                           Long sourceMessageId, ObjectNode parameters, String scope, UUID key,
                                           String requestHash) {
        if ("GEO".equals(taskType)) {
            GeoTaskService.PreparedDraft prepared = requireGeoTasks().isQuestionDiscovery(parameters)
                    ? requireGeoTasks().prepareDiscoveryDraft(userId, spaceId, productId, parameters)
                    : requireGeoTasks().prepareDraft(userId, spaceId, productId, parameters);
            String snapshotJson = canonical(prepared.snapshot());
            OffsetDateTime now = now();
            return new TaskRecords.Draft(ids.nextId(), userId, conversationId, null,
                    previousDraftId, "FORM", scope, key, requestHash, taskType,
                    expectedParameterSchemaVersion(taskType), spaceId, productId,
                    canonical(prepared.parameters()), snapshotJson, sha256(snapshotJson),
                    properties.pricingVersionId(taskType), requireGeoTasks().quote(prepared), "[]",
                    "WAITING_CONFIRMATION", now.plus(properties.getConfirmationTtl()), null, now, now);
        }
        if ("ARTICLE".equals(taskType)) {
            ArticleService.PreparedTask prepared = requireArticles().prepareTask(userId, spaceId, productId, parameters);
            return newArticleDraft(userId, conversationId, previousDraftId, spaceId, productId,
                    prepared, scope, key, requestHash);
        }
        CreativeTaskSourceService.PreparedSource source = requireCreativeSources().prepareForm(
                userId, conversationId, sourceMessageId, spaceId, productId, taskType, parameters);
        parameters = source.parameters();
        productId = source.productId();
        ObjectNode authorized = source.authorizedContext();
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("taskType", taskType);
        snapshot.put("parameterSchemaVersion", 1);
        snapshot.set("parameters", parameters.deepCopy());
        snapshot.set("authorizedContext", authorized);
        snapshot.set("productCreativeBrief", source.productCreativeBrief());
        String snapshotJson = canonical(snapshot);
        if (snapshotJson.getBytes(StandardCharsets.UTF_8).length > 262_144) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        OffsetDateTime now = now();
        return new TaskRecords.Draft(ids.nextId(), userId, conversationId, null,
                previousDraftId, "FORM", scope, key, requestHash, taskType, 1, spaceId, productId,
                canonical(parameters), snapshotJson, sha256(snapshotJson),
                properties.pricingVersionId(taskType), creativeQuote(taskType, parameters), "[]",
                "WAITING_CONFIRMATION", now.plus(properties.getConfirmationTtl()), null, now, now);
    }

    private TaskRecords.Draft newArticleDraft(long userId, long conversationId, Long previousDraftId,
                                              long spaceId, Long productId, ArticleService.PreparedTask prepared,
                                              String scope, UUID key, String requestHash) {
        String snapshotJson = canonical(prepared.snapshot());
        OffsetDateTime now = now();
        return new TaskRecords.Draft(ids.nextId(), userId, conversationId, null,
                previousDraftId, "FORM", scope, key, requestHash, "ARTICLE",
                expectedParameterSchemaVersion("ARTICLE"), spaceId, productId,
                canonical(prepared.parameters()), snapshotJson, sha256(snapshotJson),
                properties.pricingVersionId("ARTICLE"), articleQuote(prepared.mode()), "[]",
                "WAITING_CONFIRMATION", now.plus(properties.getConfirmationTtl()), null, now, now);
    }

    private void persistDraftAndConfirmation(TaskRecords.Draft draft) {
        String token = confirmationToken(draft);
        TaskRecords.Confirmation confirmation = new TaskRecords.Confirmation(ids.nextId(), draft.id(),
                draft.userId(), sha256(token), draft.inputHash(), draft.pricingVersionId(), "AVAILABLE",
                draft.expiresAt(), null, draft.createdAt());
        if (mapper.insertDraft(draft) != 1 || mapper.insertConfirmation(confirmation) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
    }

    private int freezePoints(long userId, long taskId, String taskType, int points, OffsetDateTime now) {
        return observability.observe("java.points.freeze", "FREEZE", span -> {
            span.tag("userIdHash", observability.reference(userId));
            span.tag("taskIdRef", observability.reference(taskId));
            span.tag("taskType", taskType);
        }, () -> mapper.freezePoints(userId, points, now));
    }

    private OffsetDateTime settlePoints(long userId, long taskId, String taskType, int points) {
        mapper.lockPointAccount(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        OffsetDateTime accountingAt = OffsetDateTime.ofInstant(accountingClock.instant(), ZoneOffset.UTC);
        if (pointsChange("SETTLE", userId, taskId, taskType,
                () -> mapper.settlePoints(userId, points, accountingAt)) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return accountingAt;
    }

    private int releasePoints(long userId, long taskId, String taskType, int points, OffsetDateTime now) {
        return pointsChange("RELEASE", userId, taskId, taskType,
                () -> mapper.releasePoints(userId, points, now));
    }

    private int pointsChange(String stage, long userId, long taskId, String taskType,
                             java.util.function.IntSupplier action) {
        return observability.observe("java.points.settle-or-release", stage, span -> {
            span.tag("userIdHash", observability.reference(userId));
            span.tag("taskIdRef", observability.reference(taskId));
            span.tag("taskType", taskType);
        }, action::getAsInt);
    }

    private GeoTaskService requireGeoTasks() {
        if (geoTasks == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        return geoTasks;
    }

    private ArticleService requireArticles() {
        if (articleService == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        return articleService;
    }

    private MediaSubmissionService requireMediaSubmissions() {
        if (mediaSubmissions == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        return mediaSubmissions;
    }

    private CreativeTaskSourceService requireCreativeSources() {
        if (creativeSources == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        return creativeSources;
    }

    private int articleQuote(String mode) {
        return properties.articleStageQuote(mode).quotedPoints();
    }

    private void notifyWorkflowTerminal(TaskRecords.Task task, String status,
                                        String errorCode, OffsetDateTime now) {
        if ("ARTICLE".equals(task.taskType())
                && ("FAILED".equals(status) || "SYSTEM_TERMINATED".equals(status))) {
            requireArticles().markGenerationFailed(task, now);
        }
        if (workflowLifecycle != null) workflowLifecycle.taskTerminal(task, status, errorCode, now);
        if (taskNotifications != null) taskNotifications.publishTerminal(task.id(), task.userId());
    }

    private int expectedQuote(TaskRecords.Draft draft) {
        if("ARTICLE".equals(draft.taskType()) && isWorkflowDraft(draft)
                && "ARTICLE_FULL_V1".equals(read(draft.inputSnapshotJson()).path("articleFull").path("templateCode").asText())) {
            if(!properties.isArticleExecutionEnabled()) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
            // The explicit parent already reserved these fixed prices. Do not re-price a later
            // stage using live configuration; verify its materialized draft against the stored pot.
            if(mapper.matchesFixedArticleDraftPrice(draft.id(),draft.userId(),draft.pricingVersionId(),draft.quotedPoints())!=1)
                throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
            return draft.quotedPoints();
        }
        if (draft.pricingVersionId() != properties.pricingVersionId(draft.taskType())) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        }
        if ("GEO".equals(draft.taskType())) {
            return requireGeoTasks().quoteDraft(
                    draft.userId(), draft.spaceId(), draft.productId(), read(draft.parametersJson()));
        }
        if ("ARTICLE".equals(draft.taskType())) {
            if (!properties.isArticleExecutionEnabled()) {
                throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
            }
            return articleQuote(read(draft.parametersJson()).path("mode").textValue());
        }
        return creativeQuote(draft.taskType(), read(draft.parametersJson()));
    }

    private int creativeQuote(String taskType, JsonNode parameters) {
        int unitPoints = properties.quotedPoints(taskType);
        try {
            return Math.multiplyExact(unitPoints, "IMAGE".equals(taskType) ? imageCount(parameters) : 1);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
    }

    private int imageCount(JsonNode parameters) {
        // Historical single-image tasks may predate the explicit parameter.
        JsonNode count = parameters.get("count");
        if (count == null) return 1;
        if (!count.isInt() || count.intValue() < 1 || count.intValue() > 4) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return count.intValue();
    }

    private boolean proposalProductMatches(Long selectedProductId, JsonNode proposalProductId) {
        return selectedProductId == null
                ? proposalProductId != null && proposalProductId.isNull()
                : proposalProductId != null && proposalProductId.isTextual()
                && Long.toString(selectedProductId).equals(proposalProductId.textValue());
    }

    private TaskDtos.TaskEventResponse safeTaskEvent(ConversationEventRecord event) {
        JsonNode payload = read(event.payloadJson());
        return new TaskDtos.TaskEventResponse(Long.toString(event.id()), event.sequenceNo(), event.eventType(),
                safePublicValue(payload.get("status"), TASK_STATUSES),
                safeStableCode(payload.get("stageCode")), safeStableCode(payload.get("code")),
                event.occurredAt());
    }

    private String safePublicValue(JsonNode value, Set<String> allowed) {
        return value != null && value.isTextual() && allowed.contains(value.textValue())
                ? value.textValue() : null;
    }

    private String safeStableCode(JsonNode value) {
        return value != null && value.isTextual() && value.textValue().matches("^[A-Z][A-Z0-9_]{0,63}$")
                ? value.textValue() : null;
    }

    public record ConfirmedTask(TaskDtos.TaskResponse response, ConversationEventRecord browserEvent) { }
    public record WorkflowPreparedDraft(long draftId, String taskType, long pricingVersionId,
                                         int quotedPoints, String inputHash,
                                         OffsetDateTime expiresAt, long userId,
                                         Long conversationId, long spaceId, Long productId) { }

    public record WorkflowDraftExpectation(long draftId, String taskType, long pricingVersionId,
                                            int quotedPoints) { }
    public record WorkflowTaskActivation(long planId, String stepKey, long userId,
                                         long conversationId, long spaceId, Long productId,
                                         long draftId, String taskType, long pricingVersionId,
                                         int quotedPoints, String authorizedInputHash,
                                         OffsetDateTime authorizedDraftExpiresAt, UUID eventId,
                                         String requestHash, OffsetDateTime planStartedAt,
                                         OffsetDateTime now) { }
    public record ClaimedTask(TaskDtos.ClaimResponse response, ConversationEventRecord browserEvent) { }
    public record ReportedTask(TaskDtos.InternalAccepted response, ConversationEventRecord browserEvent) { }
}
