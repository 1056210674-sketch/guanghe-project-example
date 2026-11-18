package com.guanghe.backend.article;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.catalog.product.ProductRecord;
import com.guanghe.backend.catalog.product.ProductService;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.geo.GeoTaskMapper;
import com.guanghe.backend.geo.GeoTaskRecords;
import com.guanghe.backend.geo.GeoTaskService;
import com.guanghe.backend.task.TaskRecords;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.nodes.Entities;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.commonmark.parser.Parser;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.article", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArticleService {
    private static final Set<String> STATUSES = Set.of(
            "DRAFT", "OUTLINING", "BODY_QUEUED", "BODY_GENERATING", "COMPLETED", "FAILED", "ARCHIVED");
    private static final Set<String> USER_WRITABLE_STATUSES = Set.of("DRAFT", "COMPLETED", "ARCHIVED");
    private static final Set<String> MODES = Set.of("TITLE", "OUTLINE", "BODY", "REWRITE");
    private static final Set<String> PARAMETER_FIELDS = Set.of("mode", "articleId", "articleVersion", "brief",
            "selectedTitle", "confirmedOutline", "confirmedPlan", "rewriteInstruction", "targetLength", "tone",
            "targetPlatform", "sourceAssetIds", "geoReportId", "geoRecommendationId", "rewriteBlockIds");
    private static final Set<String> REQUIRED_PARAMETER_FIELDS = Set.of("mode", "articleId", "articleVersion",
            "brief", "selectedTitle", "confirmedOutline", "confirmedPlan", "rewriteInstruction",
            "targetLength", "tone", "targetPlatform", "sourceAssetIds", "geoReportId", "rewriteBlockIds");
    private static final Set<String> CHAT_TITLE_PARAMETER_FIELDS = Set.of("requestText", "mode", "agentSummary",
            "topic", "tone", "language", "outline", "targetLength", "geoReportId");
    private static final Set<String> TONES = Set.of("PROFESSIONAL", "NATURAL", "FRIENDLY", "CONCISE");
    private com.guanghe.backend.agentplatform.AgentRuntimeCatalog runtimeCatalog =
            new com.guanghe.backend.agentplatform.AgentRuntimeCatalog();

    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeCatalog(com.guanghe.backend.agentplatform.AgentRuntimeCatalog value) { runtimeCatalog=value; }
    private static final Set<String> RESULT_FIELDS = Set.of("mode", "articleVersion", "titleCandidates", "title",
            "outline", "plan", "body", "provider", "model", "modelVersion", "promptVersion", "usage", "warnings",
            "metadata");
    private static final Set<String> PLAN_FIELDS = Set.of("summary", "coreMessage", "targetAudience", "sections",
            "tone", "targetLength", "targetPlatform", "riskNotes");
    private static final Set<String> RESULT_USAGE_FIELDS = Set.of("modelCallCount", "inputTokens", "outputTokens",
            "latencyMs", "monetaryCost", "currency");
    private static final Set<String> RESULT_METADATA_FIELDS = Set.of("proposalOnly", "persisted", "published",
            "authorizedRewriteBlockIds");
    private static final int MAX_TASK_SNAPSHOT_BYTES = 262_144;
    private static final int MAX_RESULT_BYTES = 524_288;
    private static final List<org.commonmark.Extension> MARKDOWN_EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser MARKDOWN = Parser.builder().extensions(MARKDOWN_EXTENSIONS).build();
    private static final HtmlRenderer MARKDOWN_HTML = HtmlRenderer.builder()
            .extensions(MARKDOWN_EXTENSIONS).escapeHtml(true).sanitizeUrls(true).build();

    private final ArticleMapper mapper;
    private final AssetMapper assetMapper;
    private final GeoTaskMapper geoTaskMapper;
    private final BusinessSpaceService spaceService;
    private final ProductService productService;
    private final AuthorizedCatalogContextFactory catalogContextFactory;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final ArticleHtmlSanitizer htmlSanitizer;

    public ArticleService(ArticleMapper mapper, AssetMapper assetMapper, GeoTaskMapper geoTaskMapper,
                          BusinessSpaceService spaceService, ProductService productService,
                          AuthorizedCatalogContextFactory catalogContextFactory, SnowflakeIdGenerator ids,
                          ObjectMapper json, ArticleHtmlSanitizer htmlSanitizer) {
        this.mapper = mapper;
        this.assetMapper = assetMapper;
        this.geoTaskMapper = geoTaskMapper;
        this.spaceService = spaceService;
        this.productService = productService;
        this.catalogContextFactory = catalogContextFactory;
        this.ids = ids;
        this.json = json;
        this.htmlSanitizer = htmlSanitizer;
    }

    @Transactional(readOnly = true)
    public PageResponse<ArticleDtos.Summary> list(AuthenticatedAccount account, Long spaceId, Long productId,
                                                   ArticleDtos.Status status, String keyword, int page, int size) {
        return list(account,spaceId,productId,status,keyword,page,size,null);
    }

    @Transactional(readOnly = true)
    public PageResponse<ArticleDtos.Summary> list(AuthenticatedAccount account, Long spaceId, Long productId,
            ArticleDtos.Status status, String keyword, int page, int size, Boolean savedToAssets) {
        long userId = CatalogAccess.requireUser(account);
        if (spaceId != null) spaceService.requireProjectSpace(userId, spaceId);
        if (productId != null) {
            ProductRecord product = productService.requireProduct(userId, productId);
            if (spaceId != null && product.spaceId() != spaceId) throw notFound();
        }
        String normalizedKeyword = keyword == null || keyword.isBlank()
                ? null : keyword.strip().toLowerCase(Locale.ROOT);
        String statusValue = status == null ? null : status.name();
        long total = mapper.count(userId, spaceId, productId, statusValue, normalizedKeyword, savedToAssets);
        List<ArticleDtos.Summary> items = mapper.findPage(userId, spaceId, productId, statusValue,
                normalizedKeyword, savedToAssets, (long) (page - 1) * size, size).stream().map(this::summary).toList();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public ArticleDtos.Response get(AuthenticatedAccount account, long articleId) {
        long userId = CatalogAccess.requireUser(account);
        return response(requireArticle(userId, articleId));
    }

    @Transactional
    public ArticleDtos.Response saveToAssets(AuthenticatedAccount account, long articleId,
                                             ArticleDtos.SaveToAssetsRequest request) {
        long userId = CatalogAccess.requireUser(account);
        var article = mapper.lock(articleId,userId).orElseThrow(this::notFound);
        requireVersion(article,request.version());
        spaceService.requireProjectSpace(userId,article.spaceId());
        var workflow = requireWorkflow(userId,articleId);
        if (!"COMPLETED".equals(article.status()) || article.bodyText().isBlank()
                || !Set.of("BODY_READY","EDITING","READY_TO_SUBMIT").contains(workflow.workflowStage())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (article.savedToAssetsAt()!=null && java.util.Objects.equals(article.savedArticleVersion(),article.version())) {
            return response(article);
        }
        if (mapper.saveToAssets(articleId,userId,article.version(),now())!=1) throw versionConflict();
        return response(requireArticle(userId,articleId));
    }

    /** Caller holds the paid-task row lock and revalidates its current execution lease/token. */
    public ObjectNode acceptStreamPreview(TaskRecords.Task task, TaskRecords.Execution execution,
                                           JsonNode details, OffsetDateTime at) {
        if (!"ARTICLE".equals(task.taskType()) || !"RUNNING".equals(task.status())
                || details==null || !details.isObject() || !names(details).equals(Set.of("articlePreview"))) throw validation();
        JsonNode snapshot=read(task.inputSnapshotJson()),parameters=snapshot.path("parameters"),preview=details.path("articlePreview");
        if (!"BODY".equals(parameters.path("mode").asText()) || !preview.isObject()
                || !names(preview).equals(Set.of("attempt","sequence","markdown"))
                || !preview.path("attempt").isIntegralNumber() || !preview.path("attempt").canConvertToInt()
                || preview.path("attempt").intValue()<1 || preview.path("attempt").intValue()>10
                || !preview.path("sequence").isIntegralNumber() || !preview.path("sequence").canConvertToInt()
                || preview.path("sequence").intValue()<1 || preview.path("sequence").intValue()>10000
                || !preview.path("markdown").isTextual() || preview.path("markdown").textValue().length()>120000) throw validation();
        // Legacy independent BODY tasks share the same worker. A preview is optional for them;
        // accepting and ignoring it must not abort a valid paid generation just for lacking a canvas.
        if (!"ARTICLE_FULL_V1".equals(snapshot.path("articleFull").path("templateCode").asText())) return null;
        long articleId=positiveId(parameters.path("articleId").textValue());
        long preparationId=mapper.activePreviewPreparation(task.id(),task.userId(),task.conversationId(),articleId,task.spaceId())
                .orElseThrow(()->new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        int attempt=preview.path("attempt").intValue(), sequence=preview.path("sequence").intValue();
        String markdown=preview.path("markdown").textValue();
        var previous=mapper.preview(task.id()).orElse(null);
        if (previous!=null && previous.executionNo()==execution.executionNo()) {
            if (sequence<=previous.sequence()) return null; // Dropped/delayed HTTP delivery cannot rewind the canvas.
            if (attempt<previous.attempt() || (attempt==previous.attempt() && !markdown.startsWith(previous.markdown()))) throw validation();
            if (attempt>previous.attempt() && !markdown.isEmpty()) throw validation(); // Repair is an explicit empty reset.
        }
        if (mapper.savePreview(new ArticleRecords.StreamPreview(task.id(),execution.executionNo(),attempt,sequence,markdown,at))!=1) return null;
        return json.createObjectNode().put("preparationId",Long.toString(preparationId))
                .put("articleId",Long.toString(articleId)).put("executionNo",execution.executionNo())
                .put("attempt",attempt).put("sequence",sequence);
    }

    @Transactional
    public ArticleDtos.Response confirmTitle(AuthenticatedAccount account, long articleId,
                                             ArticleDtos.ConfirmTitleRequest request) {
        long userId = CatalogAccess.requireUser(account);
        if (request.source() == ArticleDtos.TitleSource.AUTO_POLICY) throw validation();
        ArticleRecords.Article article = mapper.lock(articleId, userId).orElseThrow(this::notFound);
        requireVersion(article, request.version());
        ArticleRecords.Workflow workflow = requireWorkflow(userId, articleId);
        if (!Set.of("TITLE_AWAITING_CONFIRMATION", "PLAN_READY", "PLAN_AWAITING_CONFIRMATION",
                "BODY_READY_TO_GENERATE", "BODY_READY", "EDITING", "READY_TO_SUBMIT")
                .contains(workflow.workflowStage())
                || workflow.currentTitleBatchVersion() == null
                || workflow.currentTitleBatchVersion() != request.titleBatchVersion()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        ArticleRecords.TitleCandidateBatch batch = mapper.findTitleBatch(articleId, userId,
                request.titleBatchVersion()).orElseThrow(this::notFound);
        List<String> candidates = stringList(read(batch.candidatesJson()), 5, 300);
        if (candidates.size() != 5) throw new IllegalStateException("Stored title batch is invalid");
        String title = cleanRequiredTitle(request.title());
        Integer index = request.candidateIndex();
        if (request.source() == ArticleDtos.TitleSource.CUSTOM) {
            if (index != null) throw validation();
        } else {
            if (index == null || index < 1 || index > candidates.size()) throw validation();
            boolean unchanged = candidates.get(index - 1).equals(title);
            if ((request.source() == ArticleDtos.TitleSource.SELECTED) != unchanged) throw validation();
        }
        OffsetDateTime now = now();
        if (mapper.confirmTitle(articleId, userId, article.version(), title, request.source().name(),
                request.titleBatchVersion(), index, now) != 1) throw versionConflict();
        return response(requireArticle(userId, articleId));
    }

    @Transactional
    public ArticleDtos.Response saveCreativePlan(AuthenticatedAccount account, long articleId,
                                                  ArticleDtos.SaveCreativePlanRequest request) {
        long userId = CatalogAccess.requireUser(account);
        ArticleRecords.Article article = mapper.lock(articleId, userId).orElseThrow(this::notFound);
        requireVersion(article, request.version());
        ArticleRecords.Workflow workflow = requireWorkflow(userId, articleId);
        if (!Set.of("PLAN_AWAITING_CONFIRMATION", "BODY_READY_TO_GENERATE", "BODY_READY",
                "EDITING", "READY_TO_SUBMIT")
                .contains(workflow.workflowStage()) || workflow.currentPlanVersion() == null
                || workflow.currentPlanVersion() != request.basePlanVersion()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        ObjectNode plan = normalizePlan(json.valueToTree(request.plan()));
        int planVersion = mapper.nextPlanVersion(articleId);
        OffsetDateTime now = now();
        ArticleRecords.CreativePlanRevision revision = new ArticleRecords.CreativePlanRevision(
                ids.nextId(), articleId, userId, article.spaceId(), planVersion, article.version(),
                write(plan), "EDITED", null, null, now, now);
        if (mapper.insertPlanRevision(revision) != 1
                || mapper.saveEditedPlan(articleId, userId, article.version(), planVersion, now) != 1) {
            throw versionConflict();
        }
        return response(requireArticle(userId, articleId));
    }

    @Transactional
    public ArticleDtos.Response confirmCreativePlan(AuthenticatedAccount account, long articleId,
                                                     ArticleDtos.ConfirmCreativePlanRequest request) {
        long userId = CatalogAccess.requireUser(account);
        ArticleRecords.Article article = mapper.lock(articleId, userId).orElseThrow(this::notFound);
        requireVersion(article, request.version());
        ArticleRecords.Workflow workflow = requireWorkflow(userId, articleId);
        if (!"PLAN_AWAITING_CONFIRMATION".equals(workflow.workflowStage())
                || workflow.currentPlanVersion() == null
                || workflow.currentPlanVersion() != request.planVersion()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        ArticleRecords.CreativePlanRevision revision = mapper.findPlanRevision(articleId, userId,
                request.planVersion()).orElseThrow(this::notFound);
        ObjectNode plan = normalizePlan(read(revision.planJson()));
        List<ArticleDtos.OutlineResponse> outline = normalizeOutlineNode(plan.path("sections"));
        OffsetDateTime now = now();
        if (mapper.markPlanConfirmed(articleId, userId, request.planVersion(), now) != 1
                || mapper.confirmPlan(articleId, userId, article.version(), request.planVersion(),
                write(outline), now) != 1) throw versionConflict();
        return response(requireArticle(userId, articleId));
    }

    @Transactional
    public ArticleDtos.Response create(AuthenticatedAccount account, ArticleDtos.CreateRequest request) {
        long userId = CatalogAccess.requireUser(account);
        long spaceId = positiveId(request.spaceId());
        Long productId = optionalId(request.productId());
        spaceService.requireProjectSpace(userId, spaceId);
        requireProductInSpace(userId, spaceId, productId);
        OffsetDateTime now = now();
        ArticleRecords.Article article = new ArticleRecords.Article(ids.nextId(), userId, spaceId, productId,
                cleanTitle(request.title()), "", "", "[]", "DRAFT", null, 1, now, now);
        if (mapper.insert(article) != 1) throw new IllegalStateException("Unable to create article");
        return response(article);
    }

    /** Creates the empty article owned by a server-side workflow transaction. */
    public ArticleRecords.Article createForWorkflow(long userId, long spaceId, Long productId, String title) {
        spaceService.requireProjectSpace(userId, spaceId);
        requireProductInSpace(userId, spaceId, productId);
        OffsetDateTime now = now();
        ArticleRecords.Article article = new ArticleRecords.Article(ids.nextId(), userId, spaceId, productId,
                cleanTitle(title), "", "", "[]", "DRAFT", null, 1, now, now);
        if (mapper.insert(article) != 1) throw new IllegalStateException("Unable to create workflow article");
        return article;
    }

    /**
     * Materialize an AI-chat ARTICLE proposal into the existing article workspace and TITLE task contract.
     * Python supplies only bounded editorial hints; Java creates the authoritative article identifier and
     * owns all catalog checks, task parameters, snapshot and pricing inputs.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NESTED)
    public PreparedTask prepareChatTitleTask(long userId, long spaceId, Long productId, JsonNode rawParameters) {
        return prepareChatTitle(userId, spaceId, productId, rawParameters, null);
    }

    /** Revise editorial hints while keeping the Java-owned article identity and optimistic version. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NESTED)
    public PreparedTask prepareChatTitleRevision(long userId, long spaceId, Long productId,
                                                JsonNode rawParameters, JsonNode previousParameters) {
        if (previousParameters == null || !"TITLE".equals(previousParameters.path("mode").asText())
                || !previousParameters.path("articleId").isTextual()
                || !previousParameters.path("articleVersion").isIntegralNumber()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return prepareChatTitle(userId, spaceId, productId, rawParameters, previousParameters);
    }

    private PreparedTask prepareChatTitle(long userId, long spaceId, Long productId,
                                          JsonNode rawParameters, JsonNode previousParameters) {
        EditorialHints hints = normalizeChatEditorialHints(rawParameters);
        String topic = hints.topic();

        spaceService.requireProjectSpace(userId, spaceId);
        requireProductInSpace(userId, spaceId, productId);
        GeoTaskRecords.Report report = validateGeoReport(
                userId, spaceId, productId, rawParameters.get("geoReportId"));
        long articleId;
        if (previousParameters == null) {
            OffsetDateTime createdAt = now();
            articleId = ids.nextId();
            ArticleRecords.Article article = new ArticleRecords.Article(articleId, userId, spaceId, productId,
                    chatArticleTitle(topic), "", "", "[]", "DRAFT", null, 1, createdAt, createdAt);
            if (mapper.insert(article) != 1) throw new IllegalStateException("Unable to create chat article");
        } else {
            articleId = positiveId(previousParameters.path("articleId").textValue());
        }

        ObjectNode parameters = json.createObjectNode();
        parameters.put("mode", "TITLE");
        parameters.put("articleId", Long.toString(articleId));
        parameters.put("articleVersion", previousParameters == null ? 1
                : previousParameters.path("articleVersion").intValue());
        parameters.put("brief", topic);
        parameters.putNull("selectedTitle");
        parameters.putArray("confirmedOutline");
        parameters.putNull("confirmedPlan");
        parameters.putArray("rewriteBlockIds");
        parameters.putNull("rewriteInstruction");
        parameters.put("targetLength", hints.targetLength());
        parameters.put("tone", hints.tone());
        parameters.putNull("targetPlatform");
        parameters.putArray("sourceAssetIds");
        if (report == null) parameters.putNull("geoReportId");
        else parameters.put("geoReportId", Long.toString(report.id()));
        return prepareTask(userId, spaceId, productId, parameters);
    }

    /**
     * Internal, read-only preparation for the fixed full-article recipe. This is neither an
     * authorization nor a persisted/resumable plan: it creates no article, draft, task or confirmation.
     * Scope and saved-report checks are the same as the manual ARTICLE path.
     */
    @Transactional(readOnly = true)
    public ArticleFullPreparation.Brief prepareFullArticleBrief(long userId, long spaceId, Long productId,
                                                               JsonNode rawParameters) {
        if (userId <= 0 || spaceId <= 0 || (productId != null && productId <= 0)) throw validation();
        EditorialHints hints = normalizeChatEditorialHints(rawParameters);
        spaceService.requireProjectSpace(userId, spaceId);
        requireProductInSpace(userId, spaceId, productId);
        GeoTaskRecords.Report report = validateGeoReport(
                userId, spaceId, productId, rawParameters.get("geoReportId"));
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.put("userId", Long.toString(userId));
        snapshot.put("spaceId", Long.toString(spaceId));
        if (productId == null) snapshot.putNull("productId");
        else snapshot.put("productId", Long.toString(productId));
        snapshot.set("editorial", json.valueToTree(hints));
        ObjectNode authorized = catalogAuthorizedContext(userId, spaceId, productId);
        // This fixed recipe currently needs an explicit public identity; never invent one from
        // a private project label or charge for a context the ARTICLE consumer cannot accept.
        GeoTaskService.requireMonitoringTarget(spaceId, productId,
                json.createObjectNode().set("catalogSnapshot", authorized));
        // Full preparation does not accept attachments. Freeze the explicit empty source set
        // so replacing the live ARTICLE context cannot omit a required worker field.
        authorized.putArray("sourceAssets");
        putGeoReportSnapshot(authorized, report);
        snapshot.set("authorizedContext", authorized);
        ensureSize(snapshot, MAX_TASK_SNAPSHOT_BYTES);
        return ArticleFullPreparation.freezeBrief(json, snapshot);
    }

    /** Same article, fixed editorial brief. Frozen facts are never refreshed into later stages. */
    public PreparedTask prepareFullStage(long userId, long articleId, int expectedVersion,
            String mode, JsonNode frozenBrief) {
        ArticleRecords.Article article = mapper.lock(articleId,userId).orElseThrow(this::notFound);
        requireVersion(article,expectedVersion);
        if (!Set.of("TITLE","OUTLINE","BODY").contains(mode)) throw validation();
        ArticleRecords.Workflow workflow = requireWorkflow(userId,articleId);
        JsonNode editorial=frozenBrief.path("editorial");
        String writingRequest=editorial.path("requestText").asText();
        JsonNode continuation=frozenBrief.path("authorizedContext").path("articleContinuation");
        if(!continuation.isMissingNode()) {
            // Only a server-admitted, immutable offer may expand a short confirmation.
            // Never re-read live chat here or replace the parent's original USER evidence.
            if(!continuation.isObject() || !ArticleContinuationPolicy.confirms(writingRequest)
                    || !continuation.path("authorized").isBoolean() || !continuation.path("authorized").booleanValue()
                    || !"article.generate".equals(continuation.path("action").asText())
                    || !"NOT_ATTEMPTED".equals(continuation.path("submissionState").asText())
                    || !Long.toString(userId).equals(frozenBrief.path("userId").asText())
                    || !Long.toString(article.spaceId()).equals(frozenBrief.path("spaceId").asText())
                    || !Long.toString(article.spaceId()).equals(continuation.path("projectId").asText())) throw validation();
            writingRequest=boundedText(continuation.get("requestText"),4_000,true);
        }
        JsonNode reference=frozenBrief.path("authorizedContext").path("articleWritingReference");
        if(!reference.isMissingNode()) {
            // Frozen by Java after admission, never a replacement USER request or provider authority.
            if(!reference.isObject() || !reference.path("referenceOnly").isBoolean()
                    || !reference.path("referenceOnly").booleanValue()
                    || !Long.toString(userId).equals(frozenBrief.path("userId").asText())
                    || !Long.toString(article.spaceId()).equals(reference.path("projectId").asText())
                    || !editorial.path("requestText").asText().equals(reference.path("originalRequestText").asText())
                    || !writingRequest.equals(reference.path("baseRequestText").asText())) throw validation();
            String expanded=boundedText(reference.get("requestText"),4_000,true);
            boolean existingBoundary=expanded.startsWith(writingRequest+"\n\n【写作参考／非授权】");
            boolean continuedReference=writingRequest.contains("\n\n【写作参考／非授权】")
                    && expanded.startsWith(writingRequest+"\n\n【补充历史／非授权】");
            // Older frozen parents keep their original delimiter, without rewriting saved inputs.
            if(!existingBoundary && !continuedReference) throw validation();
            writingRequest=expanded;
        }
        ObjectNode parameters=json.createObjectNode();
        parameters.put("mode",mode).put("articleId",Long.toString(articleId)).put("articleVersion",expectedVersion)
                .put("brief",writingRequest).put("tone",editorial.path("tone").asText())
                .put("targetLength",editorial.path("targetLength").asText());
        if ("TITLE".equals(mode)) parameters.putNull("selectedTitle");
        else parameters.put("selectedTitle",workflow.confirmedTitle());
        parameters.putNull("rewriteInstruction"); parameters.putArray("rewriteBlockIds");
        parameters.putArray("sourceAssetIds"); parameters.putNull("targetPlatform");
        JsonNode report=frozenBrief.path("authorizedContext").path("geoReport");
        if (report.hasNonNull("id")) parameters.put("geoReportId",report.path("id").asText()); else parameters.putNull("geoReportId");
        if ("BODY".equals(mode)) {
            if(workflow.confirmedPlanVersion()==null) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            ObjectNode plan=normalizePlan(read(mapper.findPlanRevision(articleId,userId,workflow.confirmedPlanVersion())
                    .orElseThrow(this::notFound).planJson()));
            parameters.set("confirmedPlan",plan); parameters.set("confirmedOutline",read(article.outlineJson()));
            parameters.put("targetLength",plan.path("targetLength").asText()).put("tone",plan.path("tone").asText());
            parameters.set("targetPlatform",plan.path("targetPlatform").deepCopy());
        } else { parameters.putNull("confirmedPlan"); parameters.putArray("confirmedOutline"); }
        PreparedTask checked=prepareTask(userId,article.spaceId(),article.productId(),parameters);
        ObjectNode authorized=(ObjectNode) frozenBrief.path("authorizedContext").deepCopy();
        // Continuation IDs are Java admission audit only, not a new provider input schema
        // or authority for the model. The approved editorial brief is already in parameters.
        authorized.remove("articleContinuation");
        authorized.remove("articleWritingReference");
        authorized.set("article",checked.snapshot().path("authorizedContext").path("article").deepCopy());
        // The fixed request always has sourceAssetIds=[]; preserve that explicit contract for
        // older frozen parents too without fetching assets or importing today's attachments.
        authorized.putArray("sourceAssets");
        checked.snapshot().set("authorizedContext",authorized);
        ensureSize(checked.snapshot(),MAX_TASK_SNAPSHOT_BYTES);
        return checked;
    }

    /** Deterministic adoption, separately audited by the fixed parent. Never a user confirmation. */
    public int adoptFullStage(long userId,long articleId,int expectedVersion,String mode,
            long sourceTaskId,OffsetDateTime now) {
        ArticleRecords.Article article=mapper.lock(articleId,userId).orElseThrow(this::notFound);
        requireVersion(article,expectedVersion);
        ArticleRecords.Workflow workflow=requireWorkflow(userId,articleId);
        if("TITLE".equals(mode)) {
            if(!"TITLE_AWAITING_CONFIRMATION".equals(workflow.workflowStage()) || workflow.currentTitleBatchVersion()==null)
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            var batch=mapper.findTitleBatch(articleId,userId,workflow.currentTitleBatchVersion()).orElseThrow(this::notFound);
            if(batch.sourceTaskId()!=sourceTaskId || batch.articleVersion()+1!=expectedVersion) throw versionConflict();
            List<String> titles=stringList(read(batch.candidatesJson()),5,300);
            if(titles.size()!=5 || mapper.adoptTitle(articleId,userId,expectedVersion,
                    cleanRequiredTitle(titles.getFirst()),batch.batchVersion(),now)!=1) throw versionConflict();
            return batch.batchVersion();
        }
        if(!"OUTLINE".equals(mode) || !"PLAN_AWAITING_CONFIRMATION".equals(workflow.workflowStage())
                || workflow.currentPlanVersion()==null) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        var revision=mapper.findPlanRevision(articleId,userId,workflow.currentPlanVersion()).orElseThrow(this::notFound);
        if(revision.sourceTaskId()==null || revision.sourceTaskId()!=sourceTaskId
                || revision.articleVersion()+1!=expectedVersion || revision.confirmedAt()!=null) throw versionConflict();
        List<ArticleDtos.OutlineResponse> outline=normalizeOutlineNode(normalizePlan(read(revision.planJson())).path("sections"));
        if(mapper.confirmPlan(articleId,userId,expectedVersion,revision.planVersion(),write(outline),now)!=1) throw versionConflict();
        return revision.planVersion();
    }

    private EditorialHints normalizeChatEditorialHints(JsonNode raw) {
        if (raw == null || !raw.isObject() || !CHAT_TITLE_PARAMETER_FIELDS.containsAll(names(raw))
                || !"AGENT_ASSISTED".equals(raw.path("mode").textValue())) throw validation();
        return new EditorialHints(boundedText(raw.get("requestText"), 16_000, true),
                boundedText(raw.get("agentSummary"), 1_000, true),
                boundedText(raw.get("topic"), 500, true), chatTone(raw.get("tone")),
                chatTargetLength(raw.get("targetLength")),
                raw.has("language") ? boundedText(raw.get("language"), 32, true) : null,
                raw.has("outline") ? stringList(raw.get("outline"), 50, 500) : List.of());
    }

    private record EditorialHints(String requestText, String agentSummary, String topic, String tone,
                                  String targetLength, String language, List<String> outline) { }

    @Transactional
    public ArticleDtos.Response update(AuthenticatedAccount account, long articleId, String ifMatch,
                                        ArticleDtos.UpdateRequest request) {
        long userId = CatalogAccess.requireUser(account);
        ArticleRecords.Article current = mapper.lock(articleId,userId).orElseThrow(this::notFound);
        int expected = expectedVersion(ifMatch, request.getVersion());
        requireVersion(current, expected);
        if (!request.hasChanges()) throw validation();
        ArticleRecords.Workflow workflow = requireWorkflow(userId, articleId);
        boolean editableFinished = "COMPLETED".equals(current.status())
                && Set.of("BODY_READY", "EDITING", "READY_TO_SUBMIT").contains(workflow.workflowStage());
        if (request.isTitlePresent() && workflow.currentTitleBatchVersion() != null && !editableFinished) {
            // Once AI title candidates exist, the versioned title-confirmation endpoint is the only
            // legal writer. Otherwise a generic PATCH could retain a stale confirmed plan/body.
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (request.isBodyHtmlPresent() && workflow.currentTitleBatchVersion() != null
                && !Set.of("BODY_READY", "EDITING", "READY_TO_SUBMIT")
                .contains(workflow.workflowStage())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        String title = request.isTitlePresent() ? cleanTitle(request.getTitle()) : current.title();
        String bodyHtml = current.bodyHtml();
        String bodyText = current.bodyText();
        if (request.isBodyHtmlPresent()) {
            ArticleHtmlSanitizer.Sanitized sanitized = htmlSanitizer.sanitize(request.getBodyHtml());
            requireLinkedAssets(userId, articleId, sanitized.assetIds());
            bodyHtml = sanitized.html();
            bodyText = sanitized.text();
        }
        String status = current.status();
        if (request.isStatusPresent()) {
            if (request.getStatus() == null || !USER_WRITABLE_STATUSES.contains(request.getStatus().name())) {
                throw validation();
            }
            status = request.getStatus().name();
        }
        OffsetDateTime now = now();
        ArticleRecords.Article updated = new ArticleRecords.Article(current.id(), userId, current.spaceId(),
                current.productId(), title, bodyHtml, bodyText, current.outlineJson(), status,
                current.sourceTaskId(), current.version() + 1, current.createdAt(), now,
                current.savedToAssetsAt(),current.savedArticleVersion());
        if (mapper.update(updated, expected) != 1) throw versionConflict();
        if ((request.isBodyHtmlPresent() || (request.isTitlePresent() && editableFinished))
                && workflow.currentTitleBatchVersion() != null
                && mapper.updateWorkflowStage(articleId, userId, updated.version(), "EDITING", now) != 1) {
            throw versionConflict();
        }
        return response(updated);
    }

    @Transactional
    public ArticleDtos.Response confirmBody(AuthenticatedAccount account, long articleId,
                                            ArticleDtos.ConfirmBodyRequest request) {
        long userId = CatalogAccess.requireUser(account);
        ArticleRecords.Article article = mapper.lock(articleId, userId).orElseThrow(this::notFound);
        requireVersion(article, request.version());
        ArticleRecords.Workflow workflow = requireWorkflow(userId, articleId);
        if (!Set.of("BODY_READY", "EDITING", "READY_TO_SUBMIT").contains(workflow.workflowStage())
                || !"COMPLETED".equals(article.status()) || article.bodyText() == null
                || article.bodyText().isBlank()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        String region = request.preferredRegion() == null || request.preferredRegion().isBlank()
                ? null : request.preferredRegion().strip();
        OffsetDateTime now = now();
        ArticleRecords.SubmissionPreference preference = new ArticleRecords.SubmissionPreference(
                article.id(), userId, article.spaceId(), article.version(), request.goal().name(), region,
                request.maxTotalPoints(), request.requiresLink(), now, now);
        if (mapper.upsertSubmissionPreference(preference) != 1
                || mapper.confirmBody(article.id(), userId, article.version(), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return response(article);
    }

    @Transactional
    public ArticleDtos.Response replaceOutline(AuthenticatedAccount account, long articleId,
                                                ArticleDtos.ReplaceOutlineRequest request) {
        long userId = CatalogAccess.requireUser(account);
        ArticleRecords.Article current = mapper.lock(articleId, userId).orElseThrow(this::notFound);
        requireVersion(current, request.version());
        if (requireWorkflow(userId, articleId).currentPlanVersion() != null) {
            // A confirmed/generated plan owns the outline. Editing it through the legacy endpoint
            // would bypass plan revisioning and leave BODY input falsely confirmed.
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        List<ArticleDtos.OutlineResponse> outline = normalizeOutline(request.items());
        OffsetDateTime now = now();
        if (mapper.updateOutline(articleId, userId, current.version(), write(outline), now) != 1) {
            throw versionConflict();
        }
        return response(with(current, current.title(), current.bodyHtml(), current.bodyText(), write(outline),
                current.status(), current.sourceTaskId(), now), outline, mapper.findAssets(articleId, userId));
    }

    @Transactional
    public ArticleDtos.Response replaceAssets(AuthenticatedAccount account, long articleId,
                                               ArticleDtos.ReplaceAssetsRequest request) {
        long userId = CatalogAccess.requireUser(account);
        ArticleRecords.Article current = mapper.lock(articleId, userId).orElseThrow(this::notFound);
        requireVersion(current, request.version());
        ArticleRecords.Workflow workflow = requireWorkflow(userId, articleId);
        List<ArticleRecords.AssetRelation> relations = normalizeAssets(current, request.items());
        OffsetDateTime now = now();
        if (mapper.incrementVersion(articleId, userId, current.version(), now) != 1) throw versionConflict();
        int updatedVersion = current.version() + 1;
        if (workflow.currentTitleBatchVersion() != null
                && Set.of("BODY_READY", "EDITING", "READY_TO_SUBMIT").contains(workflow.workflowStage())
                && mapper.updateWorkflowStage(articleId, userId, updatedVersion, "EDITING", now) != 1) {
            throw versionConflict();
        }
        mapper.deleteAssets(articleId, userId);
        try {
            for (ArticleRecords.AssetRelation relation : relations) {
                if (mapper.insertAsset(relation) != 1) throw new IllegalStateException("Unable to link article asset");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE);
        }
        return response(with(current, current.title(), current.bodyHtml(), current.bodyText(), current.outlineJson(),
                current.status(), current.sourceTaskId(), now), parseOutline(current.outlineJson()), relations);
    }

    @Transactional
    public ArticleDtos.SubmissionSnapshotResponse createSnapshot(AuthenticatedAccount account, long articleId,
            ArticleDtos.CreateSnapshotRequest request) {
        long userId = CatalogAccess.requireUser(account);
        ArticleRecords.Article article = mapper.lock(articleId, userId).orElseThrow(this::notFound);
        requireVersion(article, request.articleVersion());
        ArticleRecords.Workflow workflow = requireWorkflow(userId, articleId);
        if (!"READY_TO_SUBMIT".equals(workflow.workflowStage())
                || !"COMPLETED".equals(article.status()) || article.bodyText() == null
                || article.bodyText().isBlank()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        ArticleRecords.SubmissionSnapshot existing = mapper.findSnapshotByVersion(articleId,
                article.version(), userId).orElse(null);
        if (existing != null) return snapshotResponse(existing);
        List<ArticleRecords.AssetRelation> assets = mapper.findAssets(articleId, userId);
        for (ArticleRecords.AssetRelation relation : assets) {
            requireReadyImage(userId, article.spaceId(), relation.assetId());
        }
        OffsetDateTime now = now();
        ArticleRecords.SubmissionSnapshot snapshot = new ArticleRecords.SubmissionSnapshot(ids.nextId(), articleId,
                userId, article.spaceId(), article.productId(), article.version(), article.title(), article.bodyHtml(),
                article.bodyText(), sha256(article.bodyHtml()), article.outlineJson(), write(assetResponses(assets)), now);
        if (mapper.insertSnapshot(snapshot) == 0) {
            snapshot = mapper.findSnapshotByVersion(articleId, article.version(), userId).orElseThrow();
        }
        return snapshotResponse(snapshot);
    }

    @Transactional(readOnly = true)
    public ArticleDtos.SubmissionSnapshotResponse getSnapshot(AuthenticatedAccount account, long articleId,
                                                               long snapshotId) {
        long userId = CatalogAccess.requireUser(account);
        requireArticle(userId, articleId);
        return snapshotResponse(mapper.findSnapshot(snapshotId, articleId, userId).orElseThrow(this::notFound));
    }

    @Transactional
    public void delete(AuthenticatedAccount account, long articleId) {
        long userId = CatalogAccess.requireUser(account);
        ArticleRecords.Article article = requireArticle(userId, articleId);
        if (!"DRAFT".equals(article.status())
                || mapper.hasProtectedReference(articleId, userId, Long.toString(articleId))) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE, "文章已被任务、插图或投稿快照引用，不能删除",
                    Map.of("reason", "ARTICLE_HAS_PROTECTED_REFERENCES"));
        }
        try {
            if (mapper.delete(articleId, userId) != 1) throw notFound();
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE, "文章已被受保护记录引用，不能删除",
                    Map.of("reason", "ARTICLE_HAS_PROTECTED_REFERENCES"));
        }
    }

    @Transactional(readOnly = true)
    public PreparedTask prepareTask(long userId, long spaceId, Long productId, JsonNode rawParameters) {
        Set<String> parameterNames = rawParameters == null || !rawParameters.isObject()
                ? Set.of() : names(rawParameters);
        if (rawParameters == null || !rawParameters.isObject()
                || !PARAMETER_FIELDS.containsAll(parameterNames)
                || !parameterNames.containsAll(REQUIRED_PARAMETER_FIELDS)) throw validation();
        ArticleRecords.Article article = requireArticle(userId, positiveId(text(rawParameters, "articleId")));
        if (article.spaceId() != spaceId || !java.util.Objects.equals(article.productId(), productId)) {
            throw notFound();
        }
        ArticleRecords.Workflow workflow = requireWorkflow(userId, article.id());
        String mode = text(rawParameters, "mode");
        if (!MODES.contains(mode)) throw validation();
        int requestedVersion = rawParameters.path("articleVersion").isInt()
                ? rawParameters.path("articleVersion").intValue() : article.version();
        if (requestedVersion != article.version()) throw versionConflict();
        String brief = boundedText(rawParameters.get("brief"), 4000, true);
        String selectedTitle = boundedText(rawParameters.get("selectedTitle"), 300, false);
        String rewrite = boundedText(rawParameters.get("rewriteInstruction"), 4000, false);
        List<String> rewriteBlockIds = stringList(rawParameters.path("rewriteBlockIds"), 100, 64);
        if (("TITLE".equals(mode) && selectedTitle != null)
                || (!"TITLE".equals(mode) && selectedTitle == null)
                || ("REWRITE".equals(mode) != (rewrite != null))
                || ("REWRITE".equals(mode) != !rewriteBlockIds.isEmpty())) throw validation();
        if (!"TITLE".equals(mode) && !java.util.Objects.equals(selectedTitle, workflow.confirmedTitle())) {
            throw validation();
        }
        String targetLength = enumText(rawParameters.get("targetLength"), Set.of("SHORT", "MEDIUM", "LONG"));
        String tone = stableEnum(rawParameters.get("tone"), 64);
        String targetPlatform = boundedText(rawParameters.get("targetPlatform"), 100, false);
        List<ArticleDtos.OutlineResponse> confirmed = normalizeOutlineNode(rawParameters.get("confirmedOutline"));
        ObjectNode confirmedPlan = null;
        if ("BODY".equals(mode) || "REWRITE".equals(mode)) {
            if (workflow.confirmedPlanVersion() == null) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            ArticleRecords.CreativePlanRevision revision = mapper.findPlanRevision(article.id(), userId,
                    workflow.confirmedPlanVersion()).orElseThrow(this::notFound);
            confirmedPlan = normalizePlan(rawParameters.get("confirmedPlan"));
            if (!write(confirmedPlan).equals(write(normalizePlan(read(revision.planJson()))))
                    || !targetLength.equals(confirmedPlan.path("targetLength").textValue())
                    || !tone.equals(confirmedPlan.path("tone").textValue())
                    || !java.util.Objects.equals(targetPlatform,
                    confirmedPlan.path("targetPlatform").isNull()
                            ? null : confirmedPlan.path("targetPlatform").textValue())) throw validation();
        } else if (rawParameters.get("confirmedPlan") == null || !rawParameters.get("confirmedPlan").isNull()) {
            throw validation();
        }
        if (("BODY".equals(mode) || "REWRITE".equals(mode))) {
            if (confirmed.isEmpty() || !write(confirmed).equals(write(parseOutline(article.outlineJson())))) {
                throw validation();
            }
            if (!write(confirmed).equals(write(normalizeOutlineNode(confirmedPlan.path("sections"))))) {
                throw validation();
            }
            Set<String> outlineKeys = confirmed.stream().map(ArticleDtos.OutlineResponse::key)
                    .collect(java.util.stream.Collectors.toSet());
            if (!outlineKeys.containsAll(rewriteBlockIds)) throw validation();
            if ("REWRITE".equals(mode)) requireCanonicalBody(article, confirmed);
        } else if (!confirmed.isEmpty()) throw validation();
        boolean stageAllowed = switch (mode) {
            case "TITLE" -> Set.of("TITLE_INPUT", "TITLE_AWAITING_CONFIRMATION", "PLAN_READY",
                    "PLAN_AWAITING_CONFIRMATION", "BODY_READY_TO_GENERATE", "BODY_READY",
                    "EDITING", "READY_TO_SUBMIT").contains(workflow.workflowStage());
            case "OUTLINE" -> Set.of("PLAN_READY", "PLAN_AWAITING_CONFIRMATION",
                    "BODY_READY_TO_GENERATE", "BODY_READY", "EDITING", "READY_TO_SUBMIT")
                    .contains(workflow.workflowStage()) && workflow.confirmedTitle() != null;
            case "BODY" -> "BODY_READY_TO_GENERATE".equals(workflow.workflowStage());
            case "REWRITE" -> Set.of("BODY_READY", "EDITING", "READY_TO_SUBMIT")
                    .contains(workflow.workflowStage());
            default -> false;
        };
        if (!stageAllowed) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        List<AssetRecords.Asset> sources = validateSourceAssets(userId, spaceId, rawParameters.get("sourceAssetIds"));
        GeoTaskRecords.Report report = resolveGeoReportBinding(
                userId, article, rawParameters.get("geoReportId"));
        String geoRecommendationId = validateGeoRecommendationId(
                report, rawParameters.get("geoRecommendationId"));

        ObjectNode normalized = json.createObjectNode();
        normalized.put("mode", mode);
        normalized.put("articleId", Long.toString(article.id()));
        normalized.put("articleVersion", article.version());
        normalized.put("brief", brief);
        if (selectedTitle == null) normalized.putNull("selectedTitle"); else normalized.put("selectedTitle", selectedTitle);
        normalized.set("confirmedOutline", json.valueToTree(confirmed));
        if (confirmedPlan == null) normalized.putNull("confirmedPlan");
        else normalized.set("confirmedPlan", confirmedPlan.deepCopy());
        normalized.set("rewriteBlockIds", json.valueToTree(rewriteBlockIds));
        if (rewrite == null) normalized.putNull("rewriteInstruction"); else normalized.put("rewriteInstruction", rewrite);
        normalized.put("targetLength", targetLength);
        normalized.put("tone", tone);
        if (targetPlatform == null) normalized.putNull("targetPlatform");
        else normalized.put("targetPlatform", targetPlatform);
        ArrayNode sourceIds = normalized.putArray("sourceAssetIds");
        sources.forEach(asset -> sourceIds.add(Long.toString(asset.id())));
        if (report == null) normalized.putNull("geoReportId"); else normalized.put("geoReportId", Long.toString(report.id()));
        if (geoRecommendationId == null) normalized.putNull("geoRecommendationId");
        else normalized.put("geoRecommendationId", geoRecommendationId);

        ObjectNode authorized = catalogAuthorizedContext(userId, spaceId, productId);
        // AI generation requires a real public identity in its strict worker context. The
        // read-only draft check must reject missing/generic targets before paid admission;
        // manually creating or editing an article does not pass through this path.
        GeoTaskService.requireMonitoringTarget(spaceId, productId,
                json.createObjectNode().set("catalogSnapshot", authorized));
        ObjectNode articleNode = authorized.putObject("article");
        articleNode.put("id", Long.toString(article.id()));
        articleNode.put("version", article.version());
        articleNode.put("title", article.title());
        articleNode.set("outline", read(article.outlineJson()));
        if (confirmedPlan == null) articleNode.putNull("confirmedPlan");
        else articleNode.set("confirmedPlan", confirmedPlan.deepCopy());
        if ("REWRITE".equals(mode)) {
            ObjectNode currentBody = articleNode.putObject("currentBody");
            currentBody.put("html", article.bodyHtml());
            currentBody.put("text", article.bodyText());
            articleNode.put("currentVersionRef", article.version());
            articleNode.set("rewriteBlockIds", json.valueToTree(rewriteBlockIds));
        }
        ArrayNode sourceNodes = authorized.putArray("sourceAssets");
        for (AssetRecords.Asset asset : sources) {
            ObjectNode node = sourceNodes.addObject();
            node.put("id", Long.toString(asset.id()));
            node.put("assetType", asset.assetType());
            node.put("name", asset.name());
            node.put("contentType", asset.contentType());
            node.put("sha256", asset.sha256());
        }
        putGeoReportSnapshot(authorized, report);
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("taskType", "ARTICLE");
        snapshot.put("parameterSchemaVersion", 2);
        snapshot.set("parameters", normalized.deepCopy());
        snapshot.set("authorizedContext", authorized);
        ensureSize(snapshot, MAX_TASK_SNAPSHOT_BYTES);
        return new PreparedTask(normalized, snapshot, mode);
    }

    private ObjectNode catalogAuthorizedContext(long userId, long spaceId, Long productId) {
        AuthorizedCatalogContextFactory.Context catalog = catalogContextFactory.create(userId, spaceId, productId);
        ObjectNode authorized = json.createObjectNode();
        authorized.set("space", json.valueToTree(catalog.space()));
        if (catalog.product() == null) authorized.putNull("product");
        else authorized.set("product", json.valueToTree(catalog.product()));
        return authorized;
    }

    private void putGeoReportSnapshot(ObjectNode authorized, GeoTaskRecords.Report report) {
        if (report == null) authorized.putNull("geoReport");
        else {
            ObjectNode reportNode = authorized.putObject("geoReport");
            reportNode.put("id", Long.toString(report.id()));
            reportNode.put("summary", report.summary());
            reportNode.put("methodVersion", report.methodVersion());
            JsonNode synthesis = read(report.synthesisJson());
            reportNode.set("coverageGaps", boundedStringArray(synthesis.path("coverageGaps"), 20, 1_000));
            reportNode.set("findings", boundedStringArray(synthesis.path("findings"), 50, 1_000));
            reportNode.set("contentBlueprint", boundedStringArray(
                    synthesis.path("contentBlueprint"), 50, 1_000));
            reportNode.set("missingSamples", boundedStringArray(synthesis.path("missingSamples"), 50, 1_000));
            ArrayNode recommendations = reportNode.putArray("recommendations");
            int recommendationCount = 0;
            for (JsonNode recommendation : synthesis.path("recommendations")) {
                if (!recommendation.isObject()) continue;
                if (recommendationCount >= 30) break;
                recommendationCount++;
                ObjectNode item = recommendations.addObject();
                item.put("id", report.id() + ":recommendation:" + recommendationCount);
                item.put("priority", boundedText(recommendation.get("priority"), 8, true));
                item.put("title", boundedText(recommendation.get("title"), 300, true));
                item.put("rationale", boundedText(recommendation.get("rationale"), 1_000, true));
            }
        }
    }

    public ValidatedResult validateResult(TaskRecords.Task task, String requestProvider, String requestModel,
                                          JsonNode artifact, JsonNode requestUsage, int requestLatencyMs) {
        JsonNode parameters = read(task.inputSnapshotJson()).path("parameters");
        String mode = parameters.path("mode").textValue();
        if (!"DEEPSEEK".equals(requestProvider) || requestModel == null
                || !requestModel.matches("^deepseek-[A-Za-z0-9._-]{1,112}$")
                || artifact == null || !artifact.isObject()
                || !names(artifact).equals(Set.of("artifactType", "unitKey", "schemaVersion", "inlineData"))
                || !"ARTICLE_CONTENT".equals(artifact.path("artifactType").textValue())
                || !artifact.path("schemaVersion").isInt() || artifact.path("schemaVersion").intValue() != 2
                || !artifact.path("unitKey").isTextual()
                || !("article:" + mode.toLowerCase(Locale.ROOT)).equals(artifact.path("unitKey").textValue())
                || !artifact.path("inlineData").isObject()) throw validation();
        JsonNode data = artifact.path("inlineData");
        ensureSize(data, MAX_RESULT_BYTES);
        if (!names(data).equals(RESULT_FIELDS) || !mode.equals(data.path("mode").textValue())
                || !data.path("articleVersion").isInt()
                || data.path("articleVersion").intValue() != parameters.path("articleVersion").intValue()
                || !"DEEPSEEK".equals(data.path("provider").textValue())
                || !requestModel.equals(data.path("model").textValue())
                || boundedText(data.get("modelVersion"), 128, true) == null
                || !runtimeCatalog.acceptedArticlePromptVersions().contains(data.path("promptVersion").textValue())
                || !validWarnings(data.path("warnings")) || !validMetadata(data.path("metadata"), mode)
                || !validUsage(data.path("usage"), requestUsage, requestLatencyMs)) throw validation();

        List<String> candidates = stringList(data.path("titleCandidates"), 5, 300);
        List<String> reportedRewriteIds = stringList(
                data.path("metadata").path("authorizedRewriteBlockIds"), 100, 64);
        List<String> authorizedRewriteIds = stringList(parameters.path("rewriteBlockIds"), 100, 64);
        if (("REWRITE".equals(mode) && !reportedRewriteIds.equals(authorizedRewriteIds))
                || (!"REWRITE".equals(mode) && !reportedRewriteIds.isEmpty())) throw validation();
        String title = boundedText(data.get("title"), 300, false);
        List<ArticleDtos.OutlineResponse> outline = normalizeOutlineNode(data.path("outline"));
        ObjectNode plan = null;
        String html = null;
        String text = null;
        if ("TITLE".equals(mode)) {
            if (candidates.size() != 5 || title != null || !outline.isEmpty()
                    || !data.path("plan").isNull() || !data.path("body").isNull()) {
                throw validation();
            }
        } else if ("OUTLINE".equals(mode)) {
            plan = normalizePlan(data.path("plan"));
            if (!candidates.isEmpty() || title == null || outline.isEmpty()
                    || !title.equals(parameters.path("selectedTitle").textValue())
                    || !plan.path("tone").textValue().equals(parameters.path("tone").textValue())
                    || !plan.path("targetLength").textValue().equals(parameters.path("targetLength").textValue())
                    || !java.util.Objects.equals(
                    plan.path("targetPlatform").isNull()
                            ? null : plan.path("targetPlatform").textValue(),
                    parameters.path("targetPlatform").isNull()
                            ? null : parameters.path("targetPlatform").textValue())
                    || !write(outline).equals(write(normalizeOutlineNode(plan.path("sections"))))
                    || !data.path("body").isNull()) {
                throw validation();
            }
        } else {
            if (!candidates.isEmpty() || title == null || outline.isEmpty()
                    || !title.equals(parameters.path("selectedTitle").textValue())
                    || !data.path("plan").isNull()
                    || !write(outline).equals(write(normalizeOutlineNode(parameters.path("confirmedOutline"))))) {
                throw validation();
            }
            ArticleHtmlSanitizer.Sanitized sanitized = "REWRITE".equals(mode)
                    ? rewriteBody(task.userId(), parameters, data.path("body"), outline)
                    : body(data.path("body"), outline);
            html = sanitized.html();
            text = sanitized.text();
        }
        ObjectNode publicResult = json.createObjectNode();
        publicResult.put("mode", mode);
        publicResult.put("articleId", parameters.path("articleId").textValue());
        publicResult.put("articleVersion", parameters.path("articleVersion").intValue());
        publicResult.set("titleCandidates", json.valueToTree(candidates));
        if (title == null) publicResult.putNull("title"); else publicResult.put("title", title);
        publicResult.set("outline", json.valueToTree(outline));
        if (plan == null) publicResult.putNull("plan"); else publicResult.set("plan", plan.deepCopy());
        publicResult.put("provider", "DEEPSEEK");
        publicResult.put("model", requestModel);
        publicResult.set("usage", data.path("usage").deepCopy());
        return new ValidatedResult(mode, positiveId(parameters.path("articleId").textValue()),
                parameters.path("articleVersion").intValue(), candidates, title, plan, html, text, publicResult);
    }

    public boolean applyGeneratedResult(TaskRecords.Task task, ValidatedResult result, OffsetDateTime now) {
        if ("TITLE".equals(result.mode())) {
            int batchVersion = mapper.nextTitleBatchVersion(result.articleId());
            ArticleRecords.Article article = mapper.find(result.articleId(), task.userId()).orElseThrow(this::notFound);
            ArticleRecords.TitleCandidateBatch batch = new ArticleRecords.TitleCandidateBatch(ids.nextId(),
                    result.articleId(), task.userId(), article.spaceId(), batchVersion, result.articleVersion(),
                    write(result.titleCandidates()), task.id(), now);
            if (mapper.applyGeneratedTitleBatch(result.articleId(), task.userId(), result.articleVersion(),
                    batchVersion, now) != 1) return false;
            if (mapper.insertTitleBatch(batch) != 1) throw new IllegalStateException("Unable to persist title batch");
            return true;
        }
        if ("OUTLINE".equals(result.mode())) {
            int planVersion = mapper.nextPlanVersion(result.articleId());
            ArticleRecords.Article article = mapper.find(result.articleId(), task.userId()).orElseThrow(this::notFound);
            ArticleRecords.CreativePlanRevision plan = new ArticleRecords.CreativePlanRevision(ids.nextId(),
                    result.articleId(), task.userId(), article.spaceId(), planVersion, result.articleVersion(),
                    write(result.plan()), "GENERATED", task.id(), null, now, now);
            if (mapper.applyGeneratedPlan(result.articleId(), task.userId(), result.articleVersion(),
                    planVersion, now) != 1) return false;
            if (mapper.insertPlanRevision(plan) != 1) throw new IllegalStateException("Unable to persist plan");
            return true;
        }
        return mapper.applyGeneratedWorkflowBody(result.articleId(), task.userId(), result.articleVersion(),
                result.title(), result.bodyHtml(), result.bodyText(), task.id(), now) == 1;
    }

    public void markGenerationStarted(TaskRecords.Task task, OffsetDateTime now) {
        if (!"ARTICLE".equals(task.taskType())) return;
        JsonNode parameters = read(task.inputSnapshotJson()).path("parameters");
        long articleId = positiveId(parameters.path("articleId").textValue());
        int version = parameters.path("articleVersion").intValue();
        ArticleRecords.Article article = mapper.lock(articleId, task.userId()).orElseThrow(this::notFound);
        requireVersion(article, version);
        ArticleRecords.Workflow workflow = requireWorkflow(task.userId(), articleId);
        String mode = parameters.path("mode").textValue();
        boolean allowed = switch (mode) {
            case "TITLE" -> Set.of("TITLE_INPUT", "TITLE_AWAITING_CONFIRMATION", "PLAN_READY",
                    "PLAN_AWAITING_CONFIRMATION", "BODY_READY_TO_GENERATE", "BODY_READY",
                    "EDITING", "READY_TO_SUBMIT").contains(workflow.workflowStage());
            case "OUTLINE" -> Set.of("PLAN_READY", "PLAN_AWAITING_CONFIRMATION",
                    "BODY_READY_TO_GENERATE", "BODY_READY", "EDITING", "READY_TO_SUBMIT")
                    .contains(workflow.workflowStage());
            case "BODY" -> "BODY_READY_TO_GENERATE".equals(workflow.workflowStage());
            case "REWRITE" -> Set.of("BODY_READY", "EDITING", "READY_TO_SUBMIT")
                    .contains(workflow.workflowStage());
            default -> false;
        };
        if (!allowed) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        String stage = switch (mode) {
            case "TITLE" -> "TITLE_GENERATING";
            case "OUTLINE" -> "PLAN_GENERATING";
            default -> "BODY_GENERATING";
        };
        if (mapper.updateWorkflowStage(articleId, task.userId(), version, stage, now) != 1) {
            throw versionConflict();
        }
    }

    public void markGenerationFailed(TaskRecords.Task task, OffsetDateTime now) {
        if (!"ARTICLE".equals(task.taskType())) return;
        JsonNode parameters = read(task.inputSnapshotJson()).path("parameters");
        long articleId = positiveId(parameters.path("articleId").textValue());
        ArticleRecords.Article article = mapper.lock(articleId, task.userId()).orElse(null);
        if (article == null) return;
        ArticleRecords.Workflow workflow = requireWorkflow(task.userId(), articleId);
        String mode = parameters.path("mode").textValue();
        String fromStage;
        String toStage;
        if ("TITLE".equals(mode)) {
            fromStage = "TITLE_GENERATING";
            if (workflow.confirmedPlanVersion() != null) {
                toStage = article.bodyText().isBlank() ? "BODY_READY_TO_GENERATE" : "BODY_READY";
            } else if (workflow.currentPlanVersion() != null) {
                toStage = "PLAN_AWAITING_CONFIRMATION";
            } else if (workflow.confirmedTitle() != null) {
                toStage = "PLAN_READY";
            } else if (workflow.currentTitleBatchVersion() != null) {
                toStage = "TITLE_AWAITING_CONFIRMATION";
            } else {
                toStage = "TITLE_INPUT";
            }
        } else if ("OUTLINE".equals(mode)) {
            fromStage = "PLAN_GENERATING";
            toStage = workflow.currentPlanVersion() == null ? "PLAN_READY" : "PLAN_AWAITING_CONFIRMATION";
        } else {
            fromStage = "BODY_GENERATING";
            toStage = "REWRITE".equals(mode) && !article.bodyText().isBlank()
                    ? "BODY_READY" : "BODY_READY_TO_GENERATE";
        }
        mapper.restoreWorkflowStage(articleId, task.userId(), fromStage, toStage, now);
    }

    public void lockAndRequireCurrentVersion(long userId, long articleId, int expectedVersion) {
        ArticleRecords.Article article = mapper.lock(articleId, userId).orElseThrow(this::notFound);
        requireVersion(article, expectedVersion);
    }

    private ArticleHtmlSanitizer.Sanitized body(JsonNode body, List<ArticleDtos.OutlineResponse> outline) {
        if (!body.isObject() || !names(body).equals(Set.of("markdown", "blocks"))
                || boundedText(body.get("markdown"), MAX_RESULT_BYTES, true) == null
                || !body.path("blocks").isArray() || body.path("blocks").size() != outline.size()) {
            throw validation();
        }
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < outline.size(); i++) {
            JsonNode block = body.path("blocks").get(i);
            ArticleDtos.OutlineResponse expected = outline.get(i);
            if (!block.isObject() || !names(block).equals(Set.of("key", "heading", "markdown"))
                    || !expected.key().equals(block.path("key").textValue())
                    || !expected.heading().equals(block.path("heading").textValue())) throw validation();
            String markdown = boundedText(block.get("markdown"), 131072, true);
            html.append("<section data-article-block-id=\"").append(expected.key()).append("\"><h")
                    .append(Math.min(6, expected.level())).append('>')
                    .append(Entities.escape(expected.heading())).append("</h")
                    .append(Math.min(6, expected.level())).append('>')
                    .append(MARKDOWN_HTML.render(MARKDOWN.parse(markdown)))
                    .append("</section>");
        }
        return htmlSanitizer.sanitize(html.toString());
    }

    private ArticleHtmlSanitizer.Sanitized rewriteBody(long userId, JsonNode parameters, JsonNode body,
            List<ArticleDtos.OutlineResponse> outline) {
        if (!body.isObject() || !names(body).equals(Set.of("markdown", "blocks"))
                || boundedText(body.get("markdown"), MAX_RESULT_BYTES, true) == null
                || !body.path("blocks").isArray()) throw validation();
        List<String> allowed = stringList(parameters.path("rewriteBlockIds"), 100, 64);
        if (body.path("blocks").size() != allowed.size()) throw validation();
        Map<String, ArticleDtos.OutlineResponse> outlineByKey = outline.stream().collect(
                java.util.stream.Collectors.toMap(ArticleDtos.OutlineResponse::key, value -> value));
        Document document = Jsoup.parseBodyFragment(requireArticle(userId,
                positiveId(parameters.path("articleId").textValue())).bodyHtml());
        document.outputSettings().prettyPrint(false);
        Set<String> seen = new HashSet<>();
        for (JsonNode block : body.path("blocks")) {
            if (!block.isObject() || !names(block).equals(Set.of("key", "heading", "markdown"))) throw validation();
            String key = boundedText(block.get("key"), 64, true);
            ArticleDtos.OutlineResponse expected = outlineByKey.get(key);
            if (expected == null || !allowed.contains(key) || !seen.add(key)
                    || !expected.heading().equals(block.path("heading").textValue())) throw validation();
            String markdown = boundedText(block.get("markdown"), 131072, true);
            Element section = document.selectFirst("section[data-article-block-id=\"" + key + "\"]");
            if (section == null) throw validation();
            section.html("<h" + Math.min(6, expected.level()) + ">" + Entities.escape(expected.heading())
                    + "</h" + Math.min(6, expected.level()) + ">"
                    + MARKDOWN_HTML.render(MARKDOWN.parse(markdown)));
        }
        if (seen.size() != allowed.size()) throw validation();
        return htmlSanitizer.sanitize(document.body().html());
    }

    private void requireCanonicalBody(ArticleRecords.Article article,
                                      List<ArticleDtos.OutlineResponse> outline) {
        Document document = Jsoup.parseBodyFragment(article.bodyHtml());
        List<Element> sections = document.select("section[data-article-block-id]");
        if (sections.size() != outline.size()) throw validation();
        for (int i = 0; i < outline.size(); i++) {
            if (!outline.get(i).key().equals(sections.get(i).attr("data-article-block-id"))) throw validation();
        }
    }

    private void requireLinkedAssets(long userId, long articleId, Set<Long> referenced) {
        Set<Long> linked = mapper.findAssets(articleId, userId).stream()
                .map(ArticleRecords.AssetRelation::assetId).collect(java.util.stream.Collectors.toSet());
        if (!linked.containsAll(referenced)) throw validation();
    }

    private List<ArticleDtos.OutlineResponse> normalizeOutline(List<ArticleDtos.OutlineItem> items) {
        if (items == null || items.size() > 100) throw validation();
        Set<String> keys = new HashSet<>();
        List<ArticleDtos.OutlineResponse> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ArticleDtos.OutlineItem item = items.get(i);
            if (item == null || item.key() == null || !keys.add(item.key())) throw validation();
            result.add(new ArticleDtos.OutlineResponse(item.key(), i + 1, item.level(),
                    item.heading().strip(), optionalTrim(item.brief(), 1000)));
        }
        ensureSize(json.valueToTree(result), 131072);
        return List.copyOf(result);
    }

    private List<ArticleDtos.OutlineResponse> normalizeOutlineNode(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 100) throw validation();
        Set<String> keys = new HashSet<>();
        List<ArticleDtos.OutlineResponse> result = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            Set<String> itemFields = names(item);
            if (!item.isObject() || !(itemFields.equals(Set.of("key", "level", "heading", "brief"))
                    || itemFields.equals(Set.of("key", "sequence", "level", "heading", "brief")))) {
                throw validation();
            }
            String key = boundedText(item.get("key"), 64, true);
            if (!key.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$") || !keys.add(key)
                    || !item.path("level").isInt() || item.path("level").intValue() < 1
                    || item.path("level").intValue() > 6
                    || (item.has("sequence") && (!item.path("sequence").isInt()
                    || item.path("sequence").intValue() != i + 1))) throw validation();
            result.add(new ArticleDtos.OutlineResponse(key, i + 1, item.path("level").intValue(),
                    boundedText(item.get("heading"), 300, true), boundedText(item.get("brief"), 1000, false)));
        }
        return List.copyOf(result);
    }

    private ObjectNode normalizePlan(JsonNode node) {
        if (node == null || !node.isObject() || !names(node).equals(PLAN_FIELDS)) throw validation();
        List<ArticleDtos.OutlineResponse> sections = normalizeOutlineNode(node.path("sections"));
        if (sections.size() < 2 || sections.size() > 12
                || sections.stream().anyMatch(section -> section.brief() == null)) {
            throw validation();
        }
        List<String> risks = stringList(node.path("riskNotes"), 20, 500);
        ObjectNode result = json.createObjectNode();
        result.put("summary", boundedText(node.get("summary"), 1000, true));
        result.put("coreMessage", boundedText(node.get("coreMessage"), 2000, true));
        result.put("targetAudience", boundedText(node.get("targetAudience"), 1000, true));
        result.set("sections", json.valueToTree(sections));
        result.put("tone", stableEnum(node.get("tone"), 64));
        result.put("targetLength", enumText(node.get("targetLength"), Set.of("SHORT", "MEDIUM", "LONG")));
        String platform = boundedText(node.get("targetPlatform"), 100, false);
        if (platform == null) result.putNull("targetPlatform"); else result.put("targetPlatform", platform);
        result.set("riskNotes", json.valueToTree(risks));
        ensureSize(result, 131072);
        return result;
    }

    private List<ArticleRecords.AssetRelation> normalizeAssets(ArticleRecords.Article article,
                                                                List<ArticleDtos.AssetInput> inputs) {
        if (inputs == null || inputs.size() > 10) throw validation();
        Set<Long> assetIds = new HashSet<>();
        Set<String> positions = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        OffsetDateTime now = now();
        List<ArticleRecords.AssetRelation> result = new ArrayList<>();
        for (ArticleDtos.AssetInput input : inputs) {
            if (input == null) throw validation();
            long assetId = positiveId(input.assetId());
            if (!assetIds.add(assetId) || !positions.add(input.positionKey()) || !orders.add(input.sortOrder())) {
                throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
            }
            requireReadyImage(article.userId(), article.spaceId(), assetId);
            result.add(new ArticleRecords.AssetRelation(ids.nextId(), article.id(), assetId, article.userId(),
                    article.spaceId(), input.positionKey(), input.sortOrder(), optionalTrim(input.altText(), 500), now));
        }
        result.sort(java.util.Comparator.comparingInt(ArticleRecords.AssetRelation::sortOrder));
        return List.copyOf(result);
    }

    private List<AssetRecords.Asset> validateSourceAssets(long userId, long spaceId, JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 10) throw validation();
        Set<Long> seen = new HashSet<>();
        List<AssetRecords.Asset> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) throw validation();
            long id = positiveId(value.textValue());
            if (!seen.add(id)) throw validation();
            result.add(requireReadyImage(userId, spaceId, id));
        }
        return List.copyOf(result);
    }

    private GeoTaskRecords.Report validateGeoReport(long userId, long spaceId, Long productId, JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw validation();
        return validateGeoReport(userId, spaceId, productId, positiveId(value.textValue()));
    }

    private GeoTaskRecords.Report validateGeoReport(long userId, long spaceId, Long productId, long reportId) {
        GeoTaskRecords.Report report = geoTaskMapper.findReport(reportId, userId)
                .orElseThrow(this::notFound);
        if (!Set.of("SUCCEEDED", "PARTIAL").contains(report.status())
                || !"geo-synthesis-v1".equals(report.methodVersion()) || report.archivedAt() != null
                || report.sampleSize() == 0) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "报告分析尚未完成或已归档，不能用于文章创作。",
                    Map.of("reason", "GEO_REPORT_ANALYSIS_UNAVAILABLE"));
        }
        GeoTaskRecords.Run run = geoTaskMapper.findRun(report.runId(), userId).orElseThrow(this::notFound);
        if (run.spaceId() != spaceId || !java.util.Objects.equals(run.productId(), productId)) {
            throw notFound();
        }
        return report;
    }

    private GeoTaskRecords.Report resolveGeoReportBinding(long userId, ArticleRecords.Article article,
                                                           JsonNode requestedValue) {
        Long boundReportId = mapper.findGeoReportId(article.id(), userId).orElse(null);
        GeoTaskRecords.Report requested = validateGeoReport(
                userId, article.spaceId(), article.productId(), requestedValue);
        if (requested != null && boundReportId != null && requested.id() != boundReportId) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        long reportId = requested == null
                ? boundReportId == null ? 0L : boundReportId
                : requested.id();
        if (reportId == 0L) return null;
        GeoTaskRecords.Report report = requested == null
                ? validateGeoReport(userId, article.spaceId(), article.productId(), reportId)
                : requested;
        if (boundReportId == null && mapper.bindGeoReport(article.id(), userId, reportId) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return report;
    }

    private String validateGeoRecommendationId(GeoTaskRecords.Report report, JsonNode value) {
        String recommendationId = boundedText(value, 128, false);
        if (recommendationId == null) return null;
        if (report == null) throw validation();
        String prefix = report.id() + ":recommendation:";
        if (!recommendationId.startsWith(prefix)) throw validation();
        int ordinal;
        try {
            ordinal = Integer.parseInt(recommendationId.substring(prefix.length()));
        } catch (NumberFormatException exception) {
            throw validation();
        }
        JsonNode recommendations = read(report.synthesisJson()).path("recommendations");
        if (!recommendations.isArray() || ordinal < 1 || ordinal > recommendations.size()
                || !recommendations.path(ordinal - 1).isObject()) {
            throw validation();
        }
        return recommendationId;
    }

    private ArrayNode boundedStringArray(JsonNode value, int maxItems, int maxLength) {
        ArrayNode result = json.createArrayNode();
        if (!value.isArray()) return result;
        int count = 0;
        for (JsonNode item : value) {
            if (count++ >= maxItems) break;
            result.add(boundedText(item, maxLength, true));
        }
        return result;
    }

    private AssetRecords.Asset requireReadyImage(long userId, long spaceId, long assetId) {
        AssetRecords.Asset asset = assetMapper.findAsset(assetId, userId).orElseThrow(this::notFound);
        if (asset.spaceId() != spaceId) throw notFound();
        if (!"IMAGE".equals(asset.assetType()) || !"READY".equals(asset.status())) {
            throw new BusinessException(ErrorCode.ASSET_NOT_READY);
        }
        return asset;
    }

    private boolean validWarnings(JsonNode warnings) {
        if (!warnings.isArray() || warnings.size() > 20) return false;
        for (JsonNode warning : warnings) if (boundedText(warning, 500, true) == null) return false;
        return true;
    }

    private boolean validMetadata(JsonNode metadata, String mode) {
        if (!metadata.isObject() || !names(metadata).equals(RESULT_METADATA_FIELDS)
                || !metadata.path("proposalOnly").isBoolean() || !metadata.path("proposalOnly").booleanValue()
                || !metadata.path("persisted").isBoolean() || metadata.path("persisted").booleanValue()
                || !metadata.path("published").isBoolean() || metadata.path("published").booleanValue()
                || !metadata.path("authorizedRewriteBlockIds").isArray()) return false;
        return "REWRITE".equals(mode) || metadata.path("authorizedRewriteBlockIds").isEmpty();
    }

    private boolean validUsage(JsonNode usage, JsonNode top, int latencyMs) {
        if (!usage.isObject() || !names(usage).equals(RESULT_USAGE_FIELDS)
                || !nonNegativeInt(usage, "modelCallCount") || usage.path("modelCallCount").intValue() < 1
                || !nonNegativeInt(usage, "inputTokens") || !nonNegativeInt(usage, "outputTokens")
                || !nonNegativeInt(usage, "latencyMs") || usage.path("latencyMs").intValue() != latencyMs
                || !costPair(usage.path("monetaryCost"), usage.path("currency"))) return false;
        return top != null && top.isObject()
                && names(top).equals(Set.of("inputTokens", "outputTokens", "images", "videoSeconds",
                "providerCost", "currency"))
                && top.path("inputTokens").isInt()
                && top.path("inputTokens").intValue() == usage.path("inputTokens").intValue()
                && top.path("outputTokens").isInt()
                && top.path("outputTokens").intValue() == usage.path("outputTokens").intValue()
                && top.path("images").isInt() && top.path("images").intValue() == 0
                && top.path("videoSeconds").isInt() && top.path("videoSeconds").intValue() == 0
                && costPair(top.path("providerCost"), top.path("currency"))
                && top.path("providerCost").equals(usage.path("monetaryCost"))
                && top.path("currency").equals(usage.path("currency"));
    }

    private boolean costPair(JsonNode cost, JsonNode currency) {
        if (cost.isNull() && currency.isNull()) return true;
        return cost.isTextual() && cost.textValue().matches("^[0-9]+(?:\\.[0-9]{1,4})?$")
                && currency.isTextual() && currency.textValue().matches("^[A-Z]{3}$");
    }

    private boolean nonNegativeInt(JsonNode object, String field) {
        return object.path(field).isInt() && object.path(field).intValue() >= 0;
    }

    private List<String> stringList(JsonNode node, int max, int maxLength) {
        if (!node.isArray() || node.size() > max) throw validation();
        Set<String> unique = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String item = boundedText(value, maxLength, true);
            if (!unique.add(item)) throw validation();
            result.add(item);
        }
        return List.copyOf(result);
    }

    private ArticleDtos.Response response(ArticleRecords.Article article) {
        return response(article, parseOutline(article.outlineJson()), mapper.findAssets(article.id(), article.userId()));
    }

    private ArticleDtos.Response response(ArticleRecords.Article article,
                                          List<ArticleDtos.OutlineResponse> outline,
                                          List<ArticleRecords.AssetRelation> assets) {
        ArticleRecords.Workflow workflow = requireWorkflow(article.userId(), article.id());
        ArticleDtos.TitleCandidateBatchResponse batchResponse = null;
        if (workflow.currentTitleBatchVersion() != null) {
            ArticleRecords.TitleCandidateBatch batch = mapper.findTitleBatch(article.id(), article.userId(),
                    workflow.currentTitleBatchVersion()).orElseThrow(this::notFound);
            batchResponse = new ArticleDtos.TitleCandidateBatchResponse(batch.batchVersion(),
                    stringList(read(batch.candidatesJson()), 5, 300), batch.createdAt());
        }
        ArticleDtos.ConfirmedTitleResponse titleResponse = workflow.confirmedTitle() == null ? null
                : new ArticleDtos.ConfirmedTitleResponse(workflow.confirmedTitle(),
                ArticleDtos.TitleSource.valueOf(workflow.confirmedTitleSource()),
                workflow.confirmedTitleBatchVersion(), workflow.confirmedTitleCandidateIndex(),
                workflow.confirmedTitleAt());
        ArticleDtos.CreativePlanResponse planResponse = null;
        if (workflow.currentPlanVersion() != null) {
            ArticleRecords.CreativePlanRevision revision = mapper.findPlanRevision(article.id(), article.userId(),
                    workflow.currentPlanVersion()).orElseThrow(this::notFound);
            ObjectNode plan = normalizePlan(read(revision.planJson()));
            planResponse = new ArticleDtos.CreativePlanResponse(revision.planVersion(),
                    plan.path("summary").textValue(), plan.path("coreMessage").textValue(),
                    plan.path("targetAudience").textValue(), normalizeOutlineNode(plan.path("sections")),
                    plan.path("tone").textValue(), plan.path("targetLength").textValue(),
                    plan.path("targetPlatform").isNull() ? null : plan.path("targetPlatform").textValue(),
                    stringList(plan.path("riskNotes"), 20, 500),
                    java.util.Objects.equals(revision.planVersion(), workflow.confirmedPlanVersion()),
                    revision.updatedAt());
        }
        return new ArticleDtos.Response(Long.toString(article.id()), Long.toString(article.spaceId()),
                article.productId() == null ? null : Long.toString(article.productId()), article.title(),
                article.bodyHtml(), article.bodyText(), outline, assetResponses(assets),
                ArticleDtos.Status.valueOf(article.status()),
                article.sourceTaskId() == null ? null : Long.toString(article.sourceTaskId()),
                article.version(), article.createdAt(), article.updatedAt(),
                ArticleDtos.WorkflowStage.valueOf(workflow.workflowStage()), batchResponse, titleResponse, planResponse,
                article.savedToAssetsAt()!=null,article.savedToAssetsAt(),article.savedArticleVersion());
    }

    private ArticleDtos.Summary summary(ArticleRecords.Article article) {
        return new ArticleDtos.Summary(Long.toString(article.id()), Long.toString(article.spaceId()),
                article.productId() == null ? null : Long.toString(article.productId()), article.title(),
                ArticleDtos.Status.valueOf(article.status()), article.version(), article.createdAt(), article.updatedAt(),
                article.savedToAssetsAt()!=null,article.savedToAssetsAt(),article.savedArticleVersion());
    }

    private ArticleDtos.SubmissionSnapshotResponse snapshotResponse(ArticleRecords.SubmissionSnapshot snapshot) {
        return new ArticleDtos.SubmissionSnapshotResponse(Long.toString(snapshot.id()),
                Long.toString(snapshot.articleId()), Long.toString(snapshot.spaceId()),
                snapshot.productId() == null ? null : Long.toString(snapshot.productId()), snapshot.articleVersion(),
                snapshot.title(), snapshot.bodyHtml(), snapshot.bodyText(), snapshot.bodyHash(),
                parseOutline(snapshot.outlineJson()), parseAssets(snapshot.assetManifestJson()), snapshot.createdAt());
    }

    private List<ArticleDtos.AssetResponse> assetResponses(List<ArticleRecords.AssetRelation> assets) {
        return assets.stream().map(asset -> new ArticleDtos.AssetResponse(Long.toString(asset.assetId()),
                asset.positionKey(), asset.sortOrder(), asset.altText())).toList();
    }

    private List<ArticleDtos.OutlineResponse> parseOutline(String value) {
        try {
            return json.readerForListOf(ArticleDtos.OutlineResponse.class).readValue(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<ArticleDtos.AssetResponse> parseAssets(String value) {
        try {
            return json.readerForListOf(ArticleDtos.AssetResponse.class).readValue(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ArticleRecords.Article with(ArticleRecords.Article current, String title, String bodyHtml,
                                        String bodyText, String outlineJson, String status, Long sourceTaskId,
                                        OffsetDateTime now) {
        return new ArticleRecords.Article(current.id(), current.userId(), current.spaceId(), current.productId(),
                title, bodyHtml, bodyText, outlineJson, status, sourceTaskId, current.version() + 1,
                current.createdAt(), now,current.savedToAssetsAt(),current.savedArticleVersion());
    }

    private ArticleRecords.Article requireArticle(long userId, long articleId) {
        ArticleRecords.Article article=mapper.find(articleId, userId).orElseThrow(this::notFound);
        spaceService.requireProjectSpace(userId,article.spaceId());
        return article;
    }

    private ArticleRecords.Workflow requireWorkflow(long userId, long articleId) {
        return mapper.findWorkflow(articleId, userId).orElseThrow(this::notFound);
    }

    private void requireProductInSpace(long userId, long spaceId, Long productId) {
        if (productId == null) return;
        if (productService.requireProduct(userId, productId).spaceId() != spaceId) throw notFound();
    }

    private void requireVersion(ArticleRecords.Article article, Integer version) {
        if (version == null || article.version() != version) throw versionConflict();
    }

    private int expectedVersion(String ifMatch, Integer bodyVersion) {
        if (ifMatch == null || ifMatch.isBlank()) return bodyVersion == null ? -1 : bodyVersion;
        String value = ifMatch.strip();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || bodyVersion == null || parsed != bodyVersion) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw validation();
        }
    }

    private String cleanTitle(String value) {
        if (value == null) return "";
        String result = value.strip();
        if (result.length() > 300) throw validation();
        return result;
    }

    private String cleanRequiredTitle(String value) {
        String result = cleanTitle(value);
        if (result.isEmpty()) throw validation();
        return result;
    }

    private String chatArticleTitle(String topic) {
        if (topic.length() <= 300) return topic;
        return topic.substring(0, 300);
    }

    private String chatTargetLength(JsonNode value) {
        if (value == null || value.isNull()) return "MEDIUM";
        if (value.isTextual() && Set.of("SHORT", "MEDIUM", "LONG").contains(value.textValue())) {
            return value.textValue();
        }
        if (!value.isInt() || value.intValue() < 100 || value.intValue() > 20_000) throw validation();
        if (value.intValue() <= 800) return "SHORT";
        if (value.intValue() >= 1_600) return "LONG";
        return "MEDIUM";
    }

    private String chatTone(JsonNode value) {
        if (value == null || value.isNull()) return "PROFESSIONAL";
        String raw = boundedText(value, 128, true);
        String normalized = raw.toUpperCase(Locale.ROOT);
        if (TONES.contains(normalized)) return normalized;
        if (raw.contains("自然") || raw.contains("轻松")) return "NATURAL";
        if (raw.contains("亲切") || raw.contains("活泼")) return "FRIENDLY";
        if (raw.contains("简洁") || raw.contains("有力")) return "CONCISE";
        if (raw.contains("专业") || raw.contains("可信")) return "PROFESSIONAL";
        return "PROFESSIONAL";
    }

    private String optionalTrim(String value, int max) {
        if (value == null) return null;
        String result = value.strip();
        if (result.isEmpty() || result.length() > max) throw validation();
        return result;
    }

    private String boundedText(JsonNode value, int max, boolean required) {
        if (value == null || value.isNull()) {
            if (required) throw validation();
            return null;
        }
        if (!value.isTextual()) throw validation();
        String result = value.textValue().strip();
        if (result.isEmpty() || result.length() > max) throw validation();
        return result;
    }

    private String text(JsonNode object, String field) { return boundedText(object.get(field), 128, true); }
    private String enumText(JsonNode value, Set<String> allowed) {
        String result = boundedText(value, 64, true);
        if (!allowed.contains(result)) throw validation();
        return result;
    }
    private String stableEnum(JsonNode value, int max) {
        String result = boundedText(value, max, true);
        if (!result.matches("^[A-Z][A-Z0-9_]{0,63}$")) throw validation();
        return result;
    }
    private long positiveId(String value) {
        try { long id = Long.parseLong(value); if (id <= 0) throw new NumberFormatException(); return id; }
        catch (RuntimeException exception) { throw validation(); }
    }
    private Long optionalId(String value) { return value == null ? null : positiveId(value); }
    private Set<String> names(JsonNode node) {
        if (node == null || !node.isObject()) return Set.of();
        Set<String> result = new HashSet<>(); node.fieldNames().forEachRemaining(result::add); return result;
    }
    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
    private void ensureSize(JsonNode value, int max) {
        if (write(value).getBytes(StandardCharsets.UTF_8).length > max) throw validation();
    }
    private String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private BusinessException validation() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
    private BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    private BusinessException versionConflict() { return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT); }

    public record PreparedTask(ObjectNode parameters, ObjectNode snapshot, String mode) { }
    public record ValidatedResult(String mode, long articleId, int articleVersion,
                                  List<String> titleCandidates, String title, ObjectNode plan,
                                  String bodyHtml, String bodyText, ObjectNode publicResult) { }
}
