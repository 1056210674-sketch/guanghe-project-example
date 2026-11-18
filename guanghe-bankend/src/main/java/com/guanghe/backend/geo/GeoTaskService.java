package com.guanghe.backend.geo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.ConversationEventRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskDtos;
import com.guanghe.backend.task.TaskRecords;
import com.guanghe.backend.task.TaskNotificationService;
import com.guanghe.backend.workflow.WorkflowTaskLifecycleService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class GeoTaskService {
    public static final List<String> PLATFORM_ORDER = List.of(
            "DOUBAO", "DEEPSEEK", "QWEN", "WENXIN", "KIMI", "YUANBAO", "ZHIPU");
    public static final Set<String> PLATFORM_CODES = Set.copyOf(PLATFORM_ORDER);
    public static final Set<String> RUN_STATUSES = Set.of(
            "QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "SYSTEM_TERMINATED");
    private static final Set<String> TERMINAL_RUN_STATUSES =
            Set.of("SUCCEEDED", "PARTIAL", "FAILED", "SYSTEM_TERMINATED");
    private static final Set<String> PARAMETER_FIELDS = Set.of(
            "mode", "topicId", "questionIds", "platformCodes", "sampleMode", "region");
    private static final Set<String> DISCOVERY_REQUIRED_FIELDS = Set.of(
            "requestText", "mode", "agentSummary", "topic", "region", "questions");
    private static final Set<String> DISCOVERY_ALLOWED_FIELDS = Set.of(
            "requestText", "mode", "agentSummary", "topic", "audience", "region", "questions",
            "platformCodes", "sampleMode");
    private static final String METRIC_VERSION = "geo-observation-v1";
    private static final String METHOD_VERSION = "geo-synthesis-v1";
    private static final String DETERMINISTIC_METHOD_VERSION = "geo-deterministic-v1";
    private static final Set<String> DEEPSEEK_MODELS = Set.of("deepseek-v4-flash", "deepseek-flash");

    private final GeoTaskMapper mapper;
    private final GeoMapper geoMapper;
    private final TaskControlMapper tasks;
    private final ConversationMapper conversations;
    private final AuthorizedCatalogContextFactory catalogContextFactory;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final PaidTaskProperties properties;
    private final CatalogNameNormalizer normalizer;
    private WorkflowTaskLifecycleService workflowLifecycle;
    private TaskNotificationService taskNotifications;
    private GeoReportInterpretationService interpretations;
    private ObjectProvider<GeoCompetitiveService> competitive;
    private ObjectProvider<GeoDiagnosticDeliveryService> diagnostics;
    private Clock accountingClock = Clock.systemUTC();

    @Autowired
    void setAccountingClock(@Qualifier("billingClock") Clock clock) {
        this.accountingClock = java.util.Objects.requireNonNull(clock);
    }

    @Autowired(required = false)
    void setCompetitive(ObjectProvider<GeoCompetitiveService> provider) { this.competitive = provider; }
    @Autowired(required=false)
    void setDiagnostics(ObjectProvider<GeoDiagnosticDeliveryService> provider) { this.diagnostics=provider; }

    @Autowired(required = false)
    void setInterpretations(ObjectProvider<GeoReportInterpretationService> provider) {
        this.interpretations = provider.getIfAvailable();
    }

    @Autowired
    void setTaskNotifications(TaskNotificationService taskNotifications) {
        this.taskNotifications = taskNotifications;
    }

    @Autowired(required = false)
    void setWorkflowLifecycle(ObjectProvider<WorkflowTaskLifecycleService> provider) {
        this.workflowLifecycle = provider.getIfAvailable();
    }

    @Autowired
    public GeoTaskService(GeoTaskMapper mapper, GeoMapper geoMapper, TaskControlMapper tasks,
                          ConversationMapper conversations,
                          AuthorizedCatalogContextFactory catalogContextFactory,
                          SnowflakeIdGenerator ids, ObjectMapper json, PaidTaskProperties properties,
                          CatalogNameNormalizer normalizer) {
        this.mapper = mapper;
        this.geoMapper = geoMapper;
        this.tasks = tasks;
        this.conversations = conversations;
        this.catalogContextFactory = catalogContextFactory;
        this.ids = ids;
        this.json = json;
        this.properties = properties;
        this.normalizer = normalizer;
    }

    public GeoTaskService(GeoTaskMapper mapper, GeoMapper geoMapper, TaskControlMapper tasks,
                          ConversationMapper conversations,
                          AuthorizedCatalogContextFactory catalogContextFactory,
                          SnowflakeIdGenerator ids, ObjectMapper json, PaidTaskProperties properties) {
        this(mapper, geoMapper, tasks, conversations, catalogContextFactory,
                ids, json, properties, new CatalogNameNormalizer());
    }

    public boolean isQuestionDiscovery(JsonNode parameters) {
        return parameters != null && parameters.isObject()
                && "QUESTION_DISCOVERY".equals(parameters.path("mode").textValue());
    }

    public PreparedDraft prepareDiscoveryDraft(
            long userId, long spaceId, Long productId, JsonNode rawParameters
    ) {
        requireGeoExecutionEnabled();
        if (!isQuestionDiscovery(rawParameters)
                || !DISCOVERY_ALLOWED_FIELDS.containsAll(names(rawParameters))
                || !names(rawParameters).containsAll(DISCOVERY_REQUIRED_FIELDS)) {
            throw invalid();
        }
        String requestText = requiredText(rawParameters.path("requestText"), 16_000);
        String agentSummary = requiredText(rawParameters.path("agentSummary"), 1_000);
        AliasValue topic = normalizer.requiredName(rawParameters.path("topic").textValue(), 100, "topic");
        String audience = rawParameters.has("audience")
                ? normalizer.nullableText(rawParameters.path("audience").textValue(), 500, "audience")
                : null;
        String region = requiredText(rawParameters.path("region"), 100);
        List<AliasValue> questions = discoveryQuestions(rawParameters.path("questions"));
        if (rawParameters.has("sampleMode")
                && !"STANDARD".equals(rawParameters.path("sampleMode").textValue())) {
            throw invalid();
        }
        List<String> platforms = rawParameters.has("platformCodes")
                ? orderedStrings(rawParameters.path("platformCodes"), 1, 7, PLATFORM_CODES)
                : PLATFORM_ORDER.stream()
                .filter(properties.getGeoConfiguredPlatforms()::contains)
                .toList();
        if (platforms.isEmpty()) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        if (!properties.getGeoConfiguredPlatforms().containsAll(platforms)) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        int units;
        try {
            units = Math.multiplyExact(questions.size(), platforms.size());
        } catch (ArithmeticException exception) {
            throw invalid();
        }
        validateCallBudget(questions.size(), platforms, 1);

        ObjectNode parameters = json.createObjectNode();
        parameters.put("requestText", requestText);
        parameters.put("mode", "QUESTION_DISCOVERY");
        parameters.put("agentSummary", agentSummary);
        parameters.put("topic", topic.value());
        if (audience != null) parameters.put("audience", audience);
        parameters.put("region", region);
        ArrayNode normalizedQuestions = parameters.putArray("questions");
        questions.forEach(question -> normalizedQuestions.add(question.value()));
        ArrayNode normalizedPlatforms = parameters.putArray("platformCodes");
        platforms.forEach(normalizedPlatforms::add);
        parameters.put("sampleMode", "STANDARD");

        AuthorizedCatalogContextFactory.Context catalog = catalogContextFactory.create(userId, spaceId, productId);
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("taskType", "GEO");
        snapshot.put("parameterSchemaVersion", 1);
        snapshot.set("parameters", parameters.deepCopy());
        ObjectNode catalogSnapshot = snapshot.putObject("catalogSnapshot");
        catalogSnapshot.set("space", json.valueToTree(catalog.space()));
        if (catalog.product() == null) catalogSnapshot.putNull("product");
        else catalogSnapshot.set("product", json.valueToTree(catalog.product()));
        requireMonitoringTarget(spaceId, productId, snapshot);
        ensureSnapshotSize(snapshot);
        return new PreparedDraft(parameters, snapshot, units);
    }

    public int quoteDraft(long userId, long spaceId, Long productId, JsonNode parameters) {
        return isQuestionDiscovery(parameters)
                ? quote(prepareDiscoveryDraft(userId, spaceId, productId, parameters))
                : quote(prepareDraft(userId, spaceId, productId, parameters));
    }

    @Transactional
    public PreparedDraft materializeDiscoveryDraft(
            long userId, long spaceId, Long productId, JsonNode rawParameters
    ) {
        PreparedDraft discovery = prepareDiscoveryDraft(userId, spaceId, productId, rawParameters);
        AliasValue topicName = normalizer.requiredName(
                discovery.parameters().path("topic").textValue(), 100, "topic");
        if (geoMapper.topicNameExists(userId, spaceId, topicName.normalized(), 0L)) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        List<AliasValue> questions = discoveryQuestions(discovery.parameters().path("questions"));
        OffsetDateTime now = now();
        GeoRecords.Topic topic = new GeoRecords.Topic(
                ids.nextId(), userId, spaceId, productId, topicName.value(), topicName.normalized(),
                discovery.parameters().path("region").textValue(), 1, now, now);
        List<GeoRecords.Question> storedQuestions = new ArrayList<>();
        try {
            if (geoMapper.insertTopic(topic) != 1) {
                throw new IllegalStateException("Unable to persist generated GEO topic");
            }
            for (int index = 0; index < questions.size(); index++) {
                AliasValue value = questions.get(index);
                GeoRecords.Question question = new GeoRecords.Question(
                        ids.nextId(), userId, spaceId, topic.id(), index + 1,
                        GeoDtos.QuestionCategory.BRAND_DISCOVERY, value.value(), value.normalized(),
                        GeoDtos.QuestionSource.MANUAL, true, now, now);
                if (geoMapper.insertQuestion(question) != 1) {
                    throw new IllegalStateException("Unable to persist generated GEO question");
                }
                storedQuestions.add(question);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }

        ObjectNode executable = json.createObjectNode();
        executable.put("mode", "MONITOR_RUN");
        executable.put("topicId", Long.toString(topic.id()));
        ArrayNode questionIds = executable.putArray("questionIds");
        storedQuestions.forEach(question -> questionIds.add(Long.toString(question.id())));
        executable.set("platformCodes", discovery.parameters().path("platformCodes").deepCopy());
        executable.put("sampleMode", discovery.parameters().path("sampleMode").textValue());
        executable.put("region", topic.region());
        return prepareDraft(userId, spaceId, productId, executable);
    }

    public PreparedDraft prepareDraft(long userId, long spaceId, Long productId, JsonNode rawParameters) {
        requireGeoExecutionEnabled();
        if (rawParameters == null || !rawParameters.isObject()
                || !names(rawParameters).equals(PARAMETER_FIELDS)) {
            throw invalid();
        }
        if (!"MONITOR_RUN".equals(rawParameters.path("mode").textValue())) throw invalid();
        long topicId = positiveId(rawParameters.path("topicId"));
        GeoRecords.Topic topic = geoMapper.findTopic(topicId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (topic.spaceId() != spaceId || !java.util.Objects.equals(topic.productId(), productId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String region = requiredText(rawParameters.path("region"), 100);
        if (topic.region() == null || !topic.region().equals(region)) throw invalid();

        List<Long> questionIds = orderedIds(rawParameters.path("questionIds"), 3, 10);
        Map<Long, GeoRecords.Question> available = new HashMap<>();
        for (GeoRecords.Question question : geoMapper.findQuestions(topicId, userId)) {
            available.put(question.id(), question);
        }
        List<GeoRecords.Question> selectedQuestions = new ArrayList<>();
        for (Long questionId : questionIds) {
            GeoRecords.Question question = available.get(questionId);
            if (question == null || !question.enabled()) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            selectedQuestions.add(question);
        }

        List<String> platforms = orderedStrings(rawParameters.path("platformCodes"), 1, 7, PLATFORM_CODES);
        if (!properties.getGeoConfiguredPlatforms().containsAll(platforms)) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        String sampleMode = requiredEnum(rawParameters.path("sampleMode"), Set.of("STANDARD", "DEEP"));
        int rounds = "DEEP".equals(sampleMode) ? 3 : 1;
        int units = Math.multiplyExact(Math.multiplyExact(questionIds.size(), platforms.size()), rounds);
        validateCallBudget(questionIds.size(), platforms, rounds);

        ObjectNode parameters = json.createObjectNode();
        parameters.put("mode", "MONITOR_RUN");
        parameters.put("topicId", Long.toString(topicId));
        ArrayNode normalizedQuestions = parameters.putArray("questionIds");
        questionIds.forEach(id -> normalizedQuestions.add(Long.toString(id)));
        ArrayNode normalizedPlatforms = parameters.putArray("platformCodes");
        platforms.forEach(normalizedPlatforms::add);
        parameters.put("sampleMode", sampleMode);
        parameters.put("region", topic.region());

        AuthorizedCatalogContextFactory.Context catalog = catalogContextFactory.create(userId, spaceId, productId);
        ObjectNode authorized = json.createObjectNode();
        ObjectNode topicNode = authorized.putObject("topic");
        topicNode.put("id", Long.toString(topic.id()));
        topicNode.put("spaceId", Long.toString(topic.spaceId()));
        if (topic.productId() == null) topicNode.putNull("productId");
        else topicNode.put("productId", Long.toString(topic.productId()));
        topicNode.put("name", topic.name());
        topicNode.put("region", topic.region());
        topicNode.put("version", topic.version());
        ArrayNode questions = authorized.putArray("questions");
        for (GeoRecords.Question question : selectedQuestions) {
            ObjectNode node = questions.addObject();
            node.put("id", Long.toString(question.id()));
            node.put("category", question.category().name());
            node.put("text", question.text());
        }
        GeoRecords.CompetitorSet set = geoMapper.findLatestCompetitorSet(topicId, userId).orElse(null);
        if (set == null) authorized.putNull("competitorSetVersion");
        else authorized.put("competitorSetVersion", set.version());
        ArrayNode competitors = authorized.putArray("competitors");
        if (set != null) {
            for (GeoTaskRecords.CompetitorSnapshot competitor : mapper.findCompetitorSnapshots(set.id())) {
                ObjectNode node = competitors.addObject();
                node.put("id", Long.toString(competitor.id()));
                node.put("name", competitor.name());
                node.set("aliases", read(competitor.aliasesJson()));
                node.put("competitorType", competitor.type());
            }
        }

        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("taskType", "GEO");
        snapshot.put("parameterSchemaVersion", 1);
        snapshot.set("parameters", parameters.deepCopy());
        ObjectNode catalogSnapshot = snapshot.putObject("catalogSnapshot");
        catalogSnapshot.set("space", json.valueToTree(catalog.space()));
        if (catalog.product() == null) catalogSnapshot.putNull("product");
        else catalogSnapshot.set("product", json.valueToTree(catalog.product()));
        snapshot.set("authorizedContext", authorized);
        requireMonitoringTarget(spaceId, productId, snapshot);
        ensureSnapshotSize(snapshot);
        return new PreparedDraft(parameters, snapshot, units);
    }

    public int quote(PreparedDraft prepared) {
        try {
            return Math.multiplyExact(prepared.quotedUnits(), properties.getGeoTestUnitPoints());
        } catch (ArithmeticException exception) {
            throw invalid();
        }
    }

    /** Same canonical encoding as existing paid draft inputs, reused by the single-action fence. */
    public String canonicalSnapshot(JsonNode value) { return canonical(value); }
    public String snapshotHash(JsonNode value) { return sha256(canonical(value)); }

    public int quoteNormalized(JsonNode parameters) {
        requireGeoExecutionEnabled();
        List<String> configured = strings(parameters.path("platformCodes"));
        if (configured.isEmpty() || !properties.getGeoConfiguredPlatforms().containsAll(configured)) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        validateCallBudget(parameters.path("questionIds").size(),
                strings(parameters.path("platformCodes")),
                "DEEP".equals(parameters.path("sampleMode").asText()) ? 3 : 1);
        try {
            return Math.multiplyExact(expectedUnits(parameters), properties.getGeoTestUnitPoints());
        } catch (ArithmeticException exception) {
            throw invalid();
        }
    }

    public int quoteScheduled(JsonNode snapshot) {
        requireGeoExecutionEnabled();
        if (snapshot == null || !snapshot.isObject()) throw invalid();
        JsonNode parameters = snapshot.path("parameters");
        List<String> platforms = strings(parameters.path("platformCodes"));
        if (platforms.isEmpty() || !properties.getGeoConfiguredPlatforms().containsAll(platforms)) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        JsonNode topic = snapshot.path("authorizedContext").path("topic");
        long spaceId = positiveId(topic.path("spaceId"));
        Long productId = topic.path("productId").isNull() || topic.path("productId").isMissingNode()
                ? null : positiveId(topic.path("productId"));
        requireMonitoringTarget(spaceId, productId, snapshot);
        return quoteNormalized(parameters);
    }

    /** Shared pre-charge identity guard for GEO and its fixed full-article preparation. */
    public static void requireMonitoringTarget(long spaceId, Long productId, JsonNode snapshot) {
        if (GeoVisibilityProjection.target(spaceId, productId, snapshot).unavailableReason() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "请先设置项目的公开监测名称，再继续。", Map.of("field", "geoTarget"));
        }
    }

    public void createRun(TaskRecords.Task task, OffsetDateTime now) {
        if (!"GEO".equals(task.taskType())) return;
        JsonNode snapshot = read(task.inputSnapshotJson());
        JsonNode parameters = snapshot.path("parameters");
        int expected = expectedUnits(parameters);
        GeoTaskRecords.Run run = new GeoTaskRecords.Run(
                ids.nextId(), task.id(), task.userId(), task.spaceId(), task.productId(),
                positiveId(parameters.path("topicId")), "QUEUED", parameters.path("sampleMode").textValue(),
                expected, 0, 0, expected, canonical(snapshot), isDiagnostic(snapshot)?"geo-diagnostic-observation-v1":METRIC_VERSION,
                isDiagnostic(snapshot)?"geo-diagnostic-sampling-v1":METHOD_VERSION,
                null, null, now, now, task.quotedPoints(), 0, 0);
        if (mapper.insertRun(run) != 1) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
    }

    public void markRunning(TaskRecords.Task task, OffsetDateTime now) {
        if ("GEO".equals(task.taskType()) && mapper.markRunRunning(task.id(), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
    }

    public void markFailed(TaskRecords.Task task, String taskStatus, OffsetDateTime now) {
        if (!"GEO".equals(task.taskType())) return;
        String runStatus = "SYSTEM_TERMINATED".equals(taskStatus) ? "SYSTEM_TERMINATED" : "FAILED";
        if (mapper.finishRun(task.id(), runStatus, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
    }

    @Transactional(readOnly = true)
    public TaskDtos.TaskProgressResponse taskProgress(TaskRecords.Task task) {
        if (!"GEO".equals(task.taskType())) return null;
        GeoTaskRecords.Run run = mapper.findRunByTask(task.id()).orElse(null);
        return progressForRun(run, task);
    }

    private TaskDtos.TaskProgressResponse progressForRun(GeoTaskRecords.Run run,
                                                          TaskRecords.Task task) {
        if (run == null || run.expectedUnits() <= 0) return null;
        int completed = completedUnits(run);
        boolean terminal = terminalForRun(run);
        int percent;
        if(task!=null&&"GEO_DIAGNOSTIC_SUBMIT".equals(task.operationScope())&&"RUNNING".equals(task.status()))
            percent=terminal?(task.stageCode().contains("PDF")?95:80):Math.min(70,completed*70/run.expectedUnits());
        else if (terminal) percent = 100;
        else if (task != null && "GEO_SYNTHESIZING".equals(task.stageCode())) percent = 95;
        else percent = Math.min(90, (int) Math.floor((completed * 90.0) / run.expectedUnits()));
        return new TaskDtos.TaskProgressResponse(percent, completed, run.expectedUnits());
    }

    private int completedUnits(GeoTaskRecords.Run run) {
        return Math.min(run.expectedUnits(), run.succeededUnits() + run.failedUnits());
    }

    private boolean isTerminalRun(String status) {
        return TERMINAL_RUN_STATUSES.contains(status);
    }

    private boolean terminalForRun(GeoTaskRecords.Run run) {
        // geo_run is the GEO aggregate's terminal-state authority.  The generic
        // business_task can briefly lag (or be observed first) during a worker
        // hand-off; using it here would expose contradictory RUNNING/terminal
        // projections and create a second state machine in the browser.
        return isTerminalRun(run.status());
    }

    private String fallbackStage(String status) {
        if ("QUEUED".equals(status)) return "QUEUED";
        if (isTerminalRun(status)) return "COMPLETED";
        return "GEO_SAMPLING";
    }

    @Transactional(readOnly = true)
    public TaskDtos.ExecutionContextResponse executionContext(TaskRecords.Task task,
                                                              TaskRecords.Execution execution) {
        GeoTaskRecords.Run run = mapper.findRunByTask(task.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        JsonNode snapshot = read(run.contextSnapshotJson());
        ObjectNode authorized = snapshot.path("authorizedContext").deepCopy();
        GeoVisibilityProjection.Target monitoringTarget = GeoVisibilityProjection.target(run, snapshot);
        if (!isDiagnostic(snapshot) && monitoringTarget.unavailableReason() == null) {
            ObjectNode target = authorized.putObject("monitoringTarget");
            target.put("kind", monitoringTarget.value().kind());
            target.put("name", monitoringTarget.terms().getFirst());
            target.set("aliases", json.valueToTree(monitoringTarget.terms().subList(1, monitoringTarget.terms().size())));
        }
        ArrayNode completed = authorized.putArray("completedUnitKeys");
        mapper.findObservationPage(run.id(), 0, 210).forEach(value -> completed.add(value.unitKey()));
        JsonNode parameters = snapshot.path("parameters");
        int rounds = "DEEP".equals(parameters.path("sampleMode").textValue()) ? 3 : 1;
        TaskDtos.ExecutionContextResponse.Budget budget = new TaskDtos.ExecutionContextResponse.Budget(
                isDiagnostic(snapshot)?snapshot.path("diagnosticExecutionBudget").path("maxProviderCalls").asInt():properties.getGeoMaxProviderCalls(), null, null, null, 10, 7, rounds,
                isDiagnostic(snapshot)?snapshot.path("diagnosticExecutionBudget").path("maxConcurrency").asInt():properties.getGeoMaxConcurrency(),
                (run.startedAt() == null ? execution.startedAt() : run.startedAt())
                        .plus(isDiagnostic(snapshot)?java.time.Duration.ofSeconds(snapshot.path("diagnosticExecutionBudget").path("deadlineSeconds").asLong()):properties.getGeoExecutionDeadline()));
        ObjectNode inputSnapshot = json.createObjectNode();
        inputSnapshot.set("parameters", parameters.deepCopy());
        return new TaskDtos.ExecutionContextResponse(Long.toString(task.id()), "GEO", null, null,
                execution.executionNo(), task.snapshotSchemaVersion(), inputSnapshot, authorized,
                false, null, new TaskDtos.ExecutionContextResponse.Pricing(
                Long.toString(task.pricingVersionId()), task.quotedPoints()), budget);
    }

    @Transactional
    public ObservationResult reportObservation(long taskId, GeoTaskDtos.ObservationRequest request) {
        String requestHash = sha256(canonical(json.valueToTree(request.observation())));
        TaskRecords.Execution execution = requireExecutionIdentity(taskId, request.executionNo(),
                request.executionToken());
        TaskRecords.Task task = tasks.findTaskInternal(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireGeoTask(task, execution);
        GeoTaskRecords.Run run = mapper.lockRunByTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        GeoTaskRecords.Observation replay = mapper.findObservationByEvent(run.id(), request.eventId()).orElse(null);
        if (replay != null) return duplicateObservation(replay, requestHash, task.status());

        ValidatedObservation valid = validateObservation(run, request.observation());
        GeoTaskRecords.Observation byUnit = mapper.findObservationByUnit(run.id(), valid.unitKey()).orElse(null);
        if (byUnit != null) return duplicateObservation(byUnit, requestHash, task.status());
        requireActiveExecution(execution);
        OffsetDateTime now = now();
        GeoTaskDtos.ObservationInput input = request.observation();
        String usage = canonical(json.valueToTree(input.usage()));
        GeoTaskRecords.Observation observation = new GeoTaskRecords.Observation(
                ids.nextId(), run.id(), task.id(), task.userId(), request.eventId(), requestHash,
                valid.unitKey(), valid.unitSequence(), valid.questionId(), valid.questionText(), input.platformCode(),
                input.sampleNo(), input.status(), normalized(input.answerText()), input.stableErrorCode(),
                input.provider(), input.model().trim(), input.startedAt(), input.finishedAt(),
                input.latencyMs(), usage, input.providerCallCount(), now, input.collectionMethod());
        if (mapper.insertObservation(observation) != 1) {
            GeoTaskRecords.Observation concurrent = mapper.findObservationByUnit(run.id(), valid.unitKey())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
            return duplicateObservation(concurrent, requestHash, task.status());
        }
        for (int index = 0; index < input.citations().size(); index++) {
            GeoTaskDtos.CitationInput citation = input.citations().get(index);
            if (mapper.insertCitation(new GeoTaskRecords.Citation(
                    ids.nextId(), observation.id(), run.id(), task.userId(), index + 1,
                    normalized(citation.title()), citation.url(), normalizedDomain(citation.domain()),
                    normalized(citation.snippet()), citation.observedAt(), now)) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
        }
        if (mapper.refreshRunCounts(run.id(), now) != 1
                || tasks.updateGeoSamplingProgress(taskId, now) != 1
                || tasks.insertTaskEvent(ids.nextId(), task.id(), task.userId(), execution.id(),
                request.eventId(), requestHash, null, null, "GEO_OBSERVATION_REPORTED", "PYTHON",
                input.stableErrorCode(), null, canonical(Map.of(
                "unitKey", valid.unitKey(), "status", input.status())), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        GeoTaskRecords.Run refreshed = mapper.findRunByTask(taskId).orElseThrow();
        String samplingStage = tasks.findTaskInternal(taskId).map(TaskRecords.Task::stageCode).orElse("GEO_SAMPLING");
        ConversationEventRecord browserEvent = browserEvent(task, "task.progress", Map.of(
                "taskId", Long.toString(task.id()), "status", "RUNNING", "stageCode", samplingStage,
                "completedUnits", refreshed.succeededUnits() + refreshed.failedUnits(),
                "expectedUnits", refreshed.expectedUnits()), now);
        return new ObservationResult(new GeoTaskDtos.ObservationAccepted(true, "CREATED", "RUNNING"),
                browserEvent);
    }

    @Transactional(readOnly = true)
    public GeoTaskDtos.InternalObservationPage observations(long taskId, int executionNo,
                                                             String token, int page, int size) {
        TaskRecords.Execution execution = requireExecutionIdentity(taskId, executionNo, token);
        requireActiveExecution(execution);
        TaskRecords.Task task = tasks.findTaskInternal(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireGeoTask(task, execution);
        GeoTaskRecords.Run run = mapper.findRunByTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        long total = mapper.countObservations(run.id());
        if (total > 210) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        List<GeoTaskRecords.Observation> saved = mapper.findObservationPage(run.id(), 0, 210);
        List<GeoTaskDtos.ObservationResponse> items = mapper.findObservationPage(
                run.id(), (long) (page - 1) * size, size).stream().map(this::observation).toList();
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new GeoTaskDtos.InternalObservationPage(items, page, size, total, totalPages,
                GeoVisibilityProjection.evidence(run, read(run.contextSnapshotJson()), saved));
    }

    @Transactional
    public CompletionResult complete(long taskId, GeoTaskDtos.CompletionRequest request) {
        String requestHash = sha256(canonical(json.valueToTree(request)));
        TaskRecords.Execution execution = requireExecutionIdentity(taskId, request.executionNo(),
                request.executionToken());
        String replayHash = tasks.findTaskEventRequestHash(taskId, request.eventId()).orElse(null);
        if (replayHash != null) {
            if (!constantEquals(replayHash, requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            GeoTaskRecords.Run run = mapper.findRunByTask(taskId).orElseThrow();
            if(isDiagnostic(read(run.contextSnapshotJson())))return new CompletionResult(new GeoTaskDtos.CompletionAccepted(true,
                tasks.findTaskInternal(taskId).orElseThrow().status(),run.status(),null,run.settledPoints(),run.releasedPoints()),null);
            GeoTaskRecords.Report report = mapper.findReportByRun(run.id()).orElseThrow();
            return new CompletionResult(new GeoTaskDtos.CompletionAccepted(
                    true, run.status(), run.status(), Long.toString(report.id()),
                    report.settledPoints(), report.releasedPoints()), null);
        }
        requireActiveExecution(execution);
        TaskRecords.Task task = tasks.lockTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireGeoTask(task, execution);
        GeoTaskRecords.Run run = mapper.lockRunByTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"RUNNING".equals(task.status()) || !"RUNNING".equals(run.status())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if(isDiagnostic(read(run.contextSnapshotJson())))return completeDiagnostic(task,execution,run,request,requestHash);
        validateSynthesis(request);
        List<GeoTaskRecords.Observation> observations = mapper.findObservationPage(run.id(), 0, 210);
        int succeeded = (int) observations.stream().filter(value -> "SUCCEEDED".equals(value.status())).count();
        if (succeeded == 0) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        int failed = (int) observations.stream().filter(value -> "FAILED".equals(value.status())).count();
        int missing = run.expectedUnits() - succeeded - failed;
        if (missing < 0) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        String status = succeeded == run.expectedUnits()
                && METHOD_VERSION.equals(request.synthesis().methodVersion()) ? "SUCCEEDED" : "PARTIAL";
        String stage = "SUCCEEDED".equals(status) ? "COMPLETED" : "PARTIAL_COMPLETED";
        // GEO quotes are a uniform integer price per frozen sampling unit. The
        // confirmed total and immutable unit count are the pricing snapshot;
        // current test-pricing configuration must never reprice an active task.
        if (run.expectedUnits() <= 0 || task.quotedPoints() <= 0
                || task.quotedPoints() != run.quotedPoints()
                || task.quotedPoints() % run.expectedUnits() != 0) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        int settled = Math.multiplyExact(succeeded, task.quotedPoints() / run.expectedUnits());
        int released = task.quotedPoints() - settled;
        if (settled <= 0 || released < 0 || settled + released != task.quotedPoints()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        OffsetDateTime now = now();
        long reportId = ids.nextId();
        String synthesisJson = canonical(json.valueToTree(request.synthesis()));
        String synthesisUsageJson = canonical(json.valueToTree(request.synthesisUsage()));
        if (synthesisJson.getBytes(StandardCharsets.UTF_8).length > 131_072
                || synthesisUsageJson.getBytes(StandardCharsets.UTF_8).length > 4_096) {
            throw invalid();
        }
        GeoTaskRecords.Report report = new GeoTaskRecords.Report(
                reportId, run.id(), task.id(), task.userId(), run.topicId(), status,
                synthesisJson, synthesisUsageJson, request.synthesis().summary().trim(),
                request.synthesis().methodVersion(),
                succeeded + failed, now, now, settled, released);
        ObjectNode result = json.createObjectNode();
        result.put("runId", Long.toString(run.id()));
        result.put("reportId", Long.toString(reportId));
        result.put("status", status);
        result.put("expectedUnits", run.expectedUnits());
        result.put("succeededUnits", succeeded);
        result.put("failedUnits", failed);
        result.put("missingUnits", missing);
        result.put("settledPoints", settled);
        result.put("releasedPoints", released);
        if (mapper.insertReport(report) != 1
                || tasks.insertTaskEvent(ids.nextId(), task.id(), task.userId(), execution.id(),
                request.eventId(), requestHash, "RUNNING", status, "GEO_COMPLETED", "PYTHON",
                null, null, canonical(result), now) != 1
                || tasks.completeGeoExecution(task.id(), execution.executionNo(),
                sha256(request.executionToken()), stage, request.synthesisUsage().provider(),
                request.synthesisUsage().model(),
                request.synthesisUsage().latencyMs(), synthesisUsageJson, now) != 1
                || tasks.completeGeoTask(task.id(), status, stage, settled, canonical(result), now) != 1
                || mapper.finishRun(task.id(), status, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (competitive != null) competitive.ifAvailable(value -> value.ingest(report, run, observations, request.synthesis()));
        settle(task, settled, released, now);
        if (workflowLifecycle != null) workflowLifecycle.taskTerminal(task, status, null, now);
        if (taskNotifications != null) taskNotifications.publishTerminal(task.id(), task.userId());
        ConversationEventRecord browserEvent = browserEvent(task, "task.completed", Map.of(
                "taskId", Long.toString(task.id()), "status", status, "stageCode", stage,
                "runId", Long.toString(run.id()), "reportId", Long.toString(reportId),
                "resultRef", "geo-report:" + reportId), now);
        return new CompletionResult(new GeoTaskDtos.CompletionAccepted(
                true, status, status, Long.toString(reportId), settled, released), browserEvent);
    }

    @Transactional(readOnly = true)
    public List<GeoTaskDtos.PlatformResponse> platforms(AuthenticatedAccount account) {
        CatalogAccess.requireUser(account);
        Map<String, String> names = Map.of(
                "DOUBAO", "豆包", "DEEPSEEK", "DeepSeek", "QWEN", "通义千问",
                "WENXIN", "文心一言", "KIMI", "Kimi", "YUANBAO", "腾讯元宝", "ZHIPU", "智谱");
        return PLATFORM_ORDER.stream().map(code -> {
                    boolean configured = properties.getGeoConfiguredPlatforms().contains(code);
                    return new GeoTaskDtos.PlatformResponse(code, names.get(code), configured,
                            configured && properties.isGeoExecutionEnabled(), null);
                }).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<GeoTaskDtos.RunSummary> runs(AuthenticatedAccount account, String topicIdValue,
                                                     String status, int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        Long topicId = optionalId(topicIdValue);
        String normalizedStatus = optionalEnum(status, RUN_STATUSES);
        long total = mapper.countRuns(userId, topicId, normalizedStatus);
        return PageResponse.of(mapper.findRunPage(userId, topicId, normalizedStatus,
                (long) (page - 1) * size, size).stream().map(this::runSummary).toList(),
                page, size, total);
    }

    @Transactional(readOnly = true)
    public GeoTaskDtos.RunDetail run(AuthenticatedAccount account, long runId) {
        long userId = CatalogAccess.requireUser(account);
        return runDetail(mapper.findRun(runId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    /**
     * Read-only progress projection for a long-running GEO run.  Counters and the
     * terminal state remain owned by geo_run; the stage is read from the existing
     * business_task record.  No second task state machine is introduced here.
     */
    @Transactional(readOnly = true)
    public GeoTaskDtos.RunProgressResponse runProgress(AuthenticatedAccount account, long runId) {
        long userId = CatalogAccess.requireUser(account);
        GeoTaskRecords.Run run = mapper.findRun(runId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        TaskRecords.Task task = tasks.findTask(run.taskId(), userId).orElse(null);
        Optional<GeoTaskRecords.Report> report = mapper.findReportByRun(run.id());
        TaskDtos.TaskProgressResponse progress = progressForRun(run, task);
        String stage = task == null || task.stageCode() == null || task.stageCode().isBlank()
                ? fallbackStage(run.status()) : task.stageCode();
        boolean terminal = terminalForRun(run);
        // A terminal GEO run has no user-facing retry action.  Expose true only
        // while the existing task controller is in its automatic recovery window.
        boolean retryable = !terminal && task != null && "RETRY_WAIT".equals(task.status());
        return new GeoTaskDtos.RunProgressResponse(
                Long.toString(run.id()), Long.toString(run.taskId()), run.status(), stage,
                progress == null ? 0 : progress.percent(),
                progress == null ? completedUnits(run) : progress.completedUnits(),
                progress == null ? run.expectedUnits() : progress.totalUnits(),
                run.succeededUnits(), run.failedUnits(), run.missingUnits(),
                report.map(value -> Long.toString(value.id())).orElse(null), terminal, retryable,
                run.updatedAt(), run.finishedAt());
    }

    @Transactional(readOnly = true)
    public PageResponse<GeoTaskDtos.ReportSummary> reports(AuthenticatedAccount account,
                                                           String topicIdValue, int page, int size) {
        return reports(account, topicIdValue, false, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<GeoTaskDtos.ReportSummary> reports(AuthenticatedAccount account,
                                                           String topicIdValue, Boolean archived, int page, int size) {
        return reports(account, null, topicIdValue, archived, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<GeoTaskDtos.ReportSummary> reports(AuthenticatedAccount account,
            String spaceIdValue, String topicIdValue, Boolean archived, int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        Long spaceId = optionalId(spaceIdValue);
        Long topicId = optionalId(topicIdValue);
        if (spaceId != null && !mapper.activeProject(userId, spaceId))
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (spaceId != null && topicId != null) {
            var topic = geoMapper.findTopic(topicId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (topic.spaceId() != spaceId) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        boolean archiveFilter = Boolean.TRUE.equals(archived);
        long total = mapper.countReports(userId, spaceId, topicId, archiveFilter);
        var pageRows = mapper.findReportPage(userId, spaceId, topicId, archiveFilter, (long) (page - 1) * size, size);
        Map<Long, GeoTaskMapper.ReportMetadata> metadata = new HashMap<>();
        if (!pageRows.isEmpty()) mapper.reportMetadata(userId, pageRows.stream().map(GeoTaskRecords.Report::runId).toList())
                .forEach(value -> metadata.put(value.runId(), value));
        return PageResponse.of(pageRows.stream().map(value -> reportSummary(value, metadata.get(value.runId())))
                .toList(), page, size, total);
    }

    @Transactional
    public GeoTaskDtos.ReportSummary setReportArchived(AuthenticatedAccount account, long reportId,
                                                       boolean archived) {
        long userId = CatalogAccess.requireUser(account);
        int updated = archived
                ? mapper.archiveReport(reportId, userId, OffsetDateTime.now(ZoneOffset.UTC))
                : mapper.restoreReport(reportId, userId);
        if (updated != 1) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return reportSummary(mapper.findReport(reportId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public GeoTaskDtos.ReportDetail report(AuthenticatedAccount account, long reportId) {
        long userId = CatalogAccess.requireUser(account);
        GeoTaskRecords.Report report = mapper.findReport(reportId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        GeoTaskRecords.Run run = mapper.findRun(report.runId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<GeoTaskRecords.Observation> savedObservations = mapper.findObservationPage(run.id(), 0, 210);
        List<GeoTaskDtos.ObservationResponse> observations = savedObservations.stream().map(this::observation).toList();
        JsonNode snapshot = read(run.contextSnapshotJson());
        JsonNode authorized = snapshot.path("authorizedContext");
        return new GeoTaskDtos.ReportDetail(
                Long.toString(report.id()), Long.toString(run.id()), Long.toString(run.taskId()),
                Long.toString(run.topicId()),
                DETERMINISTIC_METHOD_VERSION.equals(report.methodVersion()) ? "PARTIAL" : report.status(),
                topicSnapshot(authorized.path("topic")),
                questionSnapshots(authorized.path("questions")), strings(snapshot.path("parameters").path("platformCodes")),
                run.expectedUnits(), run.succeededUnits(), run.failedUnits(), run.missingUnits(), observations,
                synthesis(report.id(), read(report.synthesisJson())), report.settledPoints(), report.releasedPoints(),
                report.generatedAt(), report.methodVersion(), report.archivedAt(),
                GeoVisibilityProjection.calculate(run, snapshot, observations),
                interpretations == null
                        ? new GeoTaskDtos.ReportInterpretation(METHOD_VERSION.equals(report.methodVersion())
                            ? "SUCCEEDED" : "NOT_STARTED", false, 0, 2, null, null, null, "SERVICE_UNAVAILABLE", null)
                        : interpretations.status(report, run, savedObservations));
    }

    private void settle(TaskRecords.Task task, int settled, int released, OffsetDateTime now) {
        OffsetDateTime accountingAt = now;
        if (settled > 0) {
            // UPDATE would acquire this same row lock implicitly. Take it before reading the
            // accounting time so a lock wait across midnight cannot backdate consumption.
            tasks.lockPointAccount(task.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
            accountingAt = OffsetDateTime.ofInstant(accountingClock.instant(), ZoneOffset.UTC);
        }
        if (settled > 0 && tasks.settlePoints(task.userId(), settled, accountingAt) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (settled > 0) {
            TaskRecords.PointAccount after = tasks.findPointAccount(task.userId()).orElseThrow();
            if (tasks.insertLedger(ids.nextId(), task.userId(), 0, -settled,
                    after.availablePoints(), after.frozenPoints(), "TASK_SETTLE", task.id(),
                    "task-settle:" + task.id(), "GEO successful sample units settled", accountingAt) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
        }
        if (released > 0 && tasks.releasePoints(task.userId(), released, accountingAt) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (released > 0) {
            TaskRecords.PointAccount after = tasks.findPointAccount(task.userId()).orElseThrow();
            if (tasks.insertLedger(ids.nextId(), task.userId(), released, -released,
                    after.availablePoints(), after.frozenPoints(), "TASK_RELEASE", task.id(),
                    "task-release:" + task.id(), "GEO unsuccessful sample units released", accountingAt) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
        }
        String reservationStatus = settled==0 ? "RELEASED" : released == 0 ? "SETTLED" : "PARTIAL";
        if (tasks.finalizeGeoReservation(task.id(), settled, released, reservationStatus, accountingAt) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
    }

    private boolean isDiagnostic(JsonNode snapshot){return "DIAGNOSTIC_RUN".equals(snapshot.path("parameters").path("mode").asText());}
    private CompletionResult completeDiagnostic(TaskRecords.Task task,TaskRecords.Execution execution,GeoTaskRecords.Run run,
            GeoTaskDtos.CompletionRequest request,String requestHash) {
        var usage=request.synthesisUsage();var synthesis=request.synthesis();
        if(!"GEO_DIAGNOSTIC_SUBMIT".equals(task.operationScope())||!"geo-diagnostic-sampling-v1".equals(synthesis.methodVersion())
            ||!"SYSTEM".equals(usage.provider())||!"deterministic-geo-diagnostic-sampling-v1".equals(usage.model())
            ||usage.inputTokens()!=0||usage.outputTokens()!=0||usage.providerCallCount()!=0
            ||!synthesis.coverageGaps().isEmpty()||!synthesis.citationStructure().isEmpty()||!synthesis.competitorSignals().isEmpty()
            ||!synthesis.findings().isEmpty()||!synthesis.recommendations().isEmpty()||!synthesis.contentBlueprint().isEmpty()
            ||synthesis.competitorCandidates()!=null||synthesis.competitorDiscoveryVersion()!=null)throw invalid();
        var observations=mapper.findObservationPage(run.id(),0,210);
        int succeeded=(int)observations.stream().filter(o->"SUCCEEDED".equals(o.status())).count();
        int failed=(int)observations.stream().filter(o->"FAILED".equals(o.status())).count();
        if(succeeded+failed>run.expectedUnits()||task.quotedPoints()%run.expectedUnits()!=0)throw invalid();
        int settled=Math.multiplyExact(succeeded,task.quotedPoints()/run.expectedUnits()),released=task.quotedPoints()-settled;
        String status=succeeded==0?"FAILED":succeeded==run.expectedUnits()?"SUCCEEDED":"PARTIAL";var time=now();
        var result=json.createObjectNode().put("diagnosticId",read(run.contextSnapshotJson()).path("authorizedContext").path("diagnostic").path("diagnosticId").asText())
            .put("runId",Long.toString(run.id())).put("samplingStatus",status).put("settledPoints",settled).put("releasedPoints",released);
        if(tasks.insertTaskEvent(ids.nextId(),task.id(),task.userId(),execution.id(),request.eventId(),requestHash,"RUNNING","RUNNING","DIAGNOSTIC_SAMPLING_COMPLETED",
                "PYTHON",null,null,canonical(result),time)!=1
            ||tasks.completeGeoExecution(task.id(),execution.executionNo(),sha256(request.executionToken()),"DIAGNOSTIC_SAMPLING_COMPLETED",usage.provider(),usage.model(),
                usage.latencyMs(),canonical(json.valueToTree(usage)),time)!=1
            ||tasks.handoffDiagnosticTask(task.id(),settled,canonical(result),time)!=1||mapper.finishRun(task.id(),status,time)!=1)throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        settle(task,settled,released,time);
        var delivery=diagnostics==null?null:diagnostics.getIfAvailable();if(delivery==null)throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        delivery.samplingCompleted(task,run,status,settled,released,observations,time);
        return new CompletionResult(new GeoTaskDtos.CompletionAccepted(true,"RUNNING",status,null,settled,released),null);
    }

    private ValidatedObservation validateObservation(GeoTaskRecords.Run run,
                                                       GeoTaskDtos.ObservationInput input) {
        boolean success = "SUCCEEDED".equals(input.status());
        if ((success && (normalized(input.answerText()) == null || input.stableErrorCode() != null))
                || (!success && (input.answerText() != null || !input.citations().isEmpty()
                || input.stableErrorCode() == null))) throw invalid();
        if (!input.provider().equals(input.platformCode()) || input.model().isBlank()
                || input.finishedAt().isBefore(input.startedAt())) throw invalid();
        boolean costMissing = input.usage().monetaryCost() == null;
        if (costMissing != (input.usage().currency() == null)) throw invalid();
        JsonNode snapshot = read(run.contextSnapshotJson());
        JsonNode parameters = snapshot.path("parameters");
        List<String> platforms = strings(parameters.path("platformCodes"));
        int platformIndex = platforms.indexOf(input.platformCode());
        if (platformIndex < 0) throw invalid();
        int rounds = "DEEP".equals(parameters.path("sampleMode").textValue()) ? 3 : 1;
        if (input.sampleNo() > rounds) throw invalid();
        long questionId = positiveId(json.getNodeFactory().textNode(input.questionId()));
        String questionText = null;
        int questionIndex = -1;
        int currentQuestionIndex = 0;
        for (JsonNode question : snapshot.path("authorizedContext").path("questions")) {
            if (input.questionId().equals(question.path("id").textValue())) {
                questionText = question.path("text").textValue();
                questionIndex = currentQuestionIndex;
                break;
            }
            currentQuestionIndex++;
        }
        if (questionText == null) throw invalid();
        for (GeoTaskDtos.CitationInput citation : input.citations()) validateCitation(citation);
        int unitSequence = ((questionIndex * platforms.size() + platformIndex) * rounds)
                + input.sampleNo();
        return new ValidatedObservation(questionId + ":" + input.platformCode() + ":" + input.sampleNo(),
                unitSequence, questionId, questionText);
    }

    private void validateCitation(GeoTaskDtos.CitationInput citation) {
        try {
            URI uri = URI.create(citation.url());
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || citation.url().chars().anyMatch(Character::isWhitespace)) throw invalid();
            if (citation.domain() != null && !citation.domain().equalsIgnoreCase(uri.getHost())) throw invalid();
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private void validateSynthesis(GeoTaskDtos.CompletionRequest request) {
        boolean modelSynthesis = METHOD_VERSION.equals(request.synthesis().methodVersion())
                && "DEEPSEEK".equals(request.synthesisUsage().provider())
                && DEEPSEEK_MODELS.contains(request.synthesisUsage().model())
                && request.synthesisUsage().providerCallCount() > 0;
        boolean deterministicSynthesis = DETERMINISTIC_METHOD_VERSION.equals(
                request.synthesis().methodVersion())
                && "SYSTEM".equals(request.synthesisUsage().provider())
                && "deterministic-geo-fallback-v1".equals(request.synthesisUsage().model())
                && request.synthesisUsage().inputTokens() == 0
                && request.synthesisUsage().outputTokens() == 0
                && request.synthesisUsage().providerCallCount() == 0;
        if (!modelSynthesis && !deterministicSynthesis) throw invalid();
    }

    private TaskRecords.Execution requireExecutionIdentity(long taskId, int executionNo, String token) {
        TaskRecords.Execution execution = tasks.findLatestExecution(taskId)
                .filter(value -> value.executionNo() == executionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        if (!constantEquals(execution.executionTokenHash(), sha256(token))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return execution;
    }

    private void requireActiveExecution(TaskRecords.Execution execution) {
        if (!"RUNNING".equals(execution.status())) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        if (now().isAfter(execution.leaseExpiresAt())) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private void requireGeoTask(TaskRecords.Task task, TaskRecords.Execution execution) {
        if (!"GEO".equals(task.taskType()) || task.userId() != execution.userId()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private ObservationResult duplicateObservation(GeoTaskRecords.Observation existing,
                                                    String requestHash, String taskStatus) {
        if (!constantEquals(existing.requestHash(), requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return new ObservationResult(new GeoTaskDtos.ObservationAccepted(
                true, "DUPLICATE", taskStatus), null);
    }

    private ConversationEventRecord browserEvent(TaskRecords.Task task, String type,
                                                   Object payload, OffsetDateTime now) {
        if (task.conversationId() == null) return null;
        ObjectNode taskPayload = json.valueToTree(payload);
        taskPayload.put("deliveryKind", "BACKGROUND_TASK");
        long sequence = conversations.nextEventSequence(task.conversationId(), task.userId());
        ConversationEventRecord event = new ConversationEventRecord(ids.nextId(), task.conversationId(),
                task.userId(), null, null, task.id(), null, type, sequence,
                canonical(taskPayload), now, now);
        if (conversations.insertEvent(event) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return event;
    }

    GeoTaskDtos.ObservationResponse observation(GeoTaskRecords.Observation value) {
        JsonNode usage = read(value.usageJson());
        GeoTaskDtos.UsageResponse usageResponse = new GeoTaskDtos.UsageResponse(
                usage.path("inputTokens").intValue(), usage.path("outputTokens").intValue(),
                usage.path("searchRequests").intValue(), textOrNull(usage.get("monetaryCost")),
                textOrNull(usage.get("currency")));
        List<GeoTaskDtos.CitationResponse> citations = mapper.findCitations(value.id()).stream()
                .map(c -> new GeoTaskDtos.CitationResponse(c.sequence(), c.title(), c.url(), c.domain(),
                        c.snippet(), c.observedAt())).toList();
        return new GeoTaskDtos.ObservationResponse(value.unitKey(), Long.toString(value.questionId()),
                value.questionTextSnapshot(), value.platformCode(), value.sampleNo(), value.status(),
                value.answerText(), value.stableErrorCode(), value.provider(), value.model(),
                value.startedAt(), value.finishedAt(), value.latencyMs(), usageResponse,
                value.providerCallCount(), citations, value.collectionMethod());
    }

    private GeoTaskDtos.RunSummary runSummary(GeoTaskRecords.Run run) {
        return new GeoTaskDtos.RunSummary(Long.toString(run.id()), Long.toString(run.taskId()),
                Long.toString(run.topicId()), run.status(), run.sampleMode(), run.expectedUnits(),
                run.succeededUnits(), run.failedUnits(), run.missingUnits(), run.createdAt(), run.finishedAt());
    }

    private GeoTaskDtos.RunDetail runDetail(GeoTaskRecords.Run run) {
        JsonNode snapshot = read(run.contextSnapshotJson());
        JsonNode authorized = snapshot.path("authorizedContext");
        return new GeoTaskDtos.RunDetail(Long.toString(run.id()), Long.toString(run.taskId()),
                Long.toString(run.topicId()), Long.toString(run.spaceId()),
                run.productId() == null ? null : Long.toString(run.productId()), run.status(), run.sampleMode(),
                run.expectedUnits(), run.succeededUnits(), run.failedUnits(), run.missingUnits(),
                topicSnapshot(authorized.path("topic")), questionSnapshots(authorized.path("questions")),
                strings(snapshot.path("parameters").path("platformCodes")),
                authorized.path("competitorSetVersion").isInt()
                        ? authorized.path("competitorSetVersion").intValue() : null,
                competitorSnapshots(authorized.path("competitors")), run.metricVersion(), run.methodVersion(),
                run.startedAt(), run.finishedAt(), run.createdAt());
    }

    private GeoTaskDtos.ReportSummary reportSummary(GeoTaskRecords.Report report) {
        var metadata = mapper.reportMetadata(report.userId(), List.of(report.runId()));
        return reportSummary(report, metadata.isEmpty() ? null : metadata.getFirst());
    }

    private GeoTaskDtos.ReportSummary reportSummary(GeoTaskRecords.Report report, GeoTaskMapper.ReportMetadata metadata) {
        return new GeoTaskDtos.ReportSummary(Long.toString(report.id()), Long.toString(report.runId()),
                Long.toString(report.taskId()), Long.toString(report.topicId()),
                DETERMINISTIC_METHOD_VERSION.equals(report.methodVersion()) ? "PARTIAL" : report.status(), report.summary(),
                report.sampleSize(), report.settledPoints(), report.releasedPoints(), report.generatedAt(),
                report.archivedAt(), metadata == null ? null : metadata.topicName(),
                metadata == null ? List.of() : strings(read(metadata.platformCodesJson())));
    }

    private GeoTaskDtos.TopicSnapshot topicSnapshot(JsonNode topic) {
        return new GeoTaskDtos.TopicSnapshot(topic.path("id").textValue(), topic.path("spaceId").textValue(),
                textOrNull(topic.get("productId")), topic.path("name").textValue(),
                textOrNull(topic.get("region")), topic.path("version").intValue());
    }

    private List<GeoTaskDtos.QuestionSnapshot> questionSnapshots(JsonNode values) {
        List<GeoTaskDtos.QuestionSnapshot> result = new ArrayList<>();
        values.forEach(value -> result.add(new GeoTaskDtos.QuestionSnapshot(
                value.path("id").textValue(), value.path("category").textValue(),
                value.path("text").textValue())));
        return List.copyOf(result);
    }

    private List<GeoTaskDtos.CompetitorSnapshot> competitorSnapshots(JsonNode values) {
        List<GeoTaskDtos.CompetitorSnapshot> result = new ArrayList<>();
        values.forEach(value -> result.add(new GeoTaskDtos.CompetitorSnapshot(
                value.path("id").textValue(), value.path("name").textValue(),
                strings(value.path("aliases")), value.path("competitorType").textValue())));
        return List.copyOf(result);
    }

    private GeoTaskDtos.SynthesisResponse synthesis(long reportId, JsonNode value) {
        List<GeoTaskDtos.RecommendationResponse> recommendations = new ArrayList<>();
        int recommendationIndex = 0;
        for (JsonNode item : value.path("recommendations")) {
            if (!item.isObject()) continue;
            recommendationIndex++;
            // The report id and ordinal are server-owned.  Do not expose an id
            // copied from model JSON, because article workflow creation uses this
            // value as an authorization boundary and as an idempotent key.
            recommendations.add(new GeoTaskDtos.RecommendationResponse(
                    reportId + ":recommendation:" + recommendationIndex,
                    item.path("priority").textValue(), item.path("title").textValue(),
                    item.path("rationale").textValue()));
        }
        return new GeoTaskDtos.SynthesisResponse(value.path("summary").textValue(),
                strings(value.path("coverageGaps")), strings(value.path("citationStructure")),
                strings(value.path("competitorSignals")), strings(value.path("findings")),
                List.copyOf(recommendations), strings(value.path("contentBlueprint")),
                strings(value.path("missingSamples")), value.path("methodVersion").textValue());
    }

    private void validateCallBudget(int questions, List<String> platforms, int rounds) {
        if (properties.geoRequiredProviderCalls(questions, platforms, rounds) > properties.getGeoMaxProviderCalls()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "当前监测范围超过可执行额度（含搜索和失败重试），请减少问题、平台或采样轮次。",
                    Map.of("reason", "GEO_CALL_BUDGET_EXCEEDED"));
        }
    }

    private int expectedUnits(JsonNode parameters) {
        int rounds = "DEEP".equals(parameters.path("sampleMode").textValue()) ? 3 : 1;
        return parameters.path("questionIds").size() * parameters.path("platformCodes").size() * rounds;
    }

    private List<AliasValue> discoveryQuestions(JsonNode value) {
        if (value == null || !value.isArray() || value.size() < 3 || value.size() > 10) {
            throw invalid();
        }
        List<AliasValue> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw invalid();
            AliasValue question = normalizer.requiredName(item.textValue(), 1_000, "questions");
            if (!unique.add(question.normalized())) throw invalid();
            result.add(question);
        }
        return List.copyOf(result);
    }

    private List<Long> orderedIds(JsonNode value, int min, int max) {
        if (!value.isArray() || value.size() < min || value.size() > max) throw invalid();
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        value.forEach(item -> {
            long id = positiveId(item);
            if (!unique.add(id)) throw invalid();
        });
        return List.copyOf(unique);
    }

    private List<String> orderedStrings(JsonNode value, int min, int max, Set<String> allowed) {
        if (!value.isArray() || value.size() < min || value.size() > max) throw invalid();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        value.forEach(item -> {
            String text = requiredEnum(item, allowed);
            if (!unique.add(text)) throw invalid();
        });
        return List.copyOf(unique);
    }

    private long positiveId(JsonNode value) {
        if (value == null || !value.isTextual() || !value.textValue().matches("^[1-9][0-9]{0,18}$")) {
            throw invalid();
        }
        try {
            long id = Long.parseLong(value.textValue());
            if (id <= 0) throw invalid();
            return id;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private Long optionalId(String value) {
        return value == null ? null : positiveId(json.getNodeFactory().textNode(value));
    }

    private String optionalEnum(String value, Set<String> allowed) {
        if (value == null) return null;
        if (!allowed.contains(value)) throw invalid();
        return value;
    }

    private String requiredEnum(JsonNode value, Set<String> allowed) {
        if (!value.isTextual() || !allowed.contains(value.textValue())) throw invalid();
        return value.textValue();
    }

    private String requiredText(JsonNode value, int max) {
        if (!value.isTextual()) throw invalid();
        String text = value.textValue().trim();
        if (text.isEmpty() || text.length() > max) throw invalid();
        return text;
    }

    private void requireGeoExecutionEnabled() {
        if (!properties.isGeoExecutionEnabled()) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
    }

    private void ensureSnapshotSize(JsonNode snapshot) {
        if (canonical(snapshot).getBytes(StandardCharsets.UTF_8).length > 262_144) throw invalid();
    }

    private String normalized(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private String normalizedDomain(String value) {
        String result = normalized(value);
        return result == null ? null : result.toLowerCase(Locale.ROOT);
    }

    private String textOrNull(JsonNode value) {
        return value == null || value.isNull() ? null : value.textValue();
    }

    private List<String> strings(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        value.forEach(item -> result.add(item.textValue()));
        return List.copyOf(result);
    }

    private Set<String> names(JsonNode node) {
        Set<String> result = new HashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String canonical(Object value) {
        JsonNode node = value instanceof JsonNode jsonNode ? jsonNode : json.valueToTree(value);
        return write(sort(node));
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = json.createObjectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(Comparator.naturalOrder());
            fields.forEach(field -> object.set(field, sort(node.get(field))));
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = json.createArrayNode();
            node.forEach(item -> array.add(sort(item)));
            return array;
        }
        return node;
    }

    private String write(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public record PreparedDraft(ObjectNode parameters, ObjectNode snapshot, int quotedUnits) { }
    private record ValidatedObservation(String unitKey, int unitSequence,
                                        long questionId, String questionText) { }
    public record ObservationResult(GeoTaskDtos.ObservationAccepted response,
                                    ConversationEventRecord browserEvent) { }
    public record CompletionResult(GeoTaskDtos.CompletionAccepted response,
                                   ConversationEventRecord browserEvent) { }
}
