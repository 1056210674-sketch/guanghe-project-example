package com.guanghe.backend.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.article.ArticleMapper;
import com.guanghe.backend.article.ArticleRecords;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.catalog.product.ProductRecord;
import com.guanghe.backend.catalog.product.ProductService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.common.web.TraceIdHolder;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskConfirmationTokenCodec;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskDtos;
import com.guanghe.backend.task.TaskRecords;
import com.guanghe.backend.task.TaskNotificationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.jsoup.Jsoup;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.media", name = "enabled", havingValue = "true")
public class MediaSubmissionService {
    static final BigDecimal MARKUP_RATE = new BigDecimal("0.3500");
    private static final BigDecimal MULTIPLIER = new BigDecimal("1.35");
    /**
     * The one vocabulary of local submission statuses.
     *
     * <p>Package-private because the operator console filters submissions by the same set: a second
     * copy of this list is a second thing that can drift out of step with the state machine, and the
     * two surfaces would then disagree about whether a status exists.
     */
    static final Set<String> STATUSES = Set.of("QUEUED", "SUBMITTING", "SUBMIT_UNKNOWN",
            "ACCEPTED", "PUBLISHED", "REJECTED", "RECONCILING", "FAILED");
    /**
     * The operator console's grouping of {@link #STATUSES}: still moving, versus needs a human.
     *
     * <p>Lists rather than sets because their only two uses are a SQL {@code IN} and a membership
     * check, and the console passes them straight to the mapper.
     */
    static final List<String> PENDING_STATUSES = List.of("QUEUED", "SUBMITTING", "ACCEPTED", "RECONCILING");
    static final List<String> EXCEPTION_STATUSES = List.of("SUBMIT_UNKNOWN", "FAILED");
    private static final Set<String> SUBMISSION_READY_STAGES = Set.of(
            "READY_TO_SUBMIT");
    private static final int MAX_RECOMMENDATION_CANDIDATES = 20_000;
    private static final Map<String, List<String>> CATEGORY_KEYWORDS = Map.ofEntries(
            Map.entry("食品餐饮", List.of("咖啡", "餐饮", "食品", "饮料", "茶饮", "零食", "酒水", "烘焙")),
            Map.entry("IT科技", List.of("科技", "软件", "硬件", "数码", "人工智能", "ai", "互联网", "芯片")),
            Map.entry("汽车行业", List.of("汽车", "新能源车", "电动车", "车载", "驾驶")),
            Map.entry("财经金融", List.of("财经", "金融", "银行", "保险", "证券", "投资", "理财")),
            Map.entry("健康医疗", List.of("健康", "医疗", "医药", "医院", "保健", "养生")),
            Map.entry("女性时尚", List.of("时尚", "美妆", "护肤", "彩妆", "女装", "珠宝")),
            Map.entry("家装家居", List.of("家装", "家居", "家具", "装修", "厨卫")),
            Map.entry("酒店旅游", List.of("旅游", "酒店", "旅行", "景区", "度假")),
            Map.entry("留学教育", List.of("教育", "学校", "课程", "培训", "留学")),
            Map.entry("母婴亲子", List.of("母婴", "亲子", "婴儿", "儿童", "宝宝")),
            Map.entry("房产", List.of("房产", "楼盘", "住宅", "置业")),
            Map.entry("娱乐行业", List.of("娱乐", "影视", "明星", "综艺", "游戏")),
            Map.entry("生活消费", List.of("生活", "消费", "日用", "零售", "电商"))
    );
    private final MediaMapper media;
    private final TaskControlMapper tasks;
    private final ArticleMapper articles;
    private final ProductService products;
    private final MediaProviderAdapter provider;
    private final MediaProperties properties;
    private final PaidTaskProperties taskProperties;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final TaskConfirmationTokenCodec confirmationTokens;
    private final ObservabilitySupport observability;
    private TaskNotificationService taskNotifications;
    private Clock accountingClock = Clock.systemUTC();
    private MediaArticleImages articleImages;

    @Autowired(required = false)
    void setArticleImages(MediaArticleImages value) { this.articleImages = value; }

    @Autowired
    void setAccountingClock(@Qualifier("billingClock") Clock clock) {
        this.accountingClock = java.util.Objects.requireNonNull(clock);
    }

    @Autowired
    void setTaskNotifications(TaskNotificationService taskNotifications) {
        this.taskNotifications = taskNotifications;
    }

    @Autowired
    public MediaSubmissionService(MediaMapper media, TaskControlMapper tasks, ArticleMapper articles,
                                  ProductService products,
                                  MediaProviderAdapter provider, MediaProperties properties,
                                  PaidTaskProperties taskProperties, SnowflakeIdGenerator ids,
                                  ObjectMapper json,
                                  org.springframework.transaction.PlatformTransactionManager transactionManager,
                                  ObservabilitySupport observability) {
        this.media = media; this.tasks = tasks; this.articles = articles; this.products = products;
        this.provider = provider;
        this.properties = properties; this.taskProperties = taskProperties; this.ids = ids; this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.confirmationTokens = new TaskConfirmationTokenCodec(taskProperties);
        this.observability = observability;
    }

    public MediaSubmissionService(MediaMapper media, TaskControlMapper tasks, ArticleMapper articles,
                                  ProductService products,
                                  MediaProviderAdapter provider, MediaProperties properties,
                                  PaidTaskProperties taskProperties, SnowflakeIdGenerator ids,
                                  ObjectMapper json,
                                  org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this(media, tasks, articles, products, provider, properties, taskProperties, ids, json,
                transactionManager, ObservabilitySupport.noop());
    }

    @Transactional(readOnly = true)
    public PageResponse<MediaDtos.ResourceSummary> resources(AuthenticatedAccount account, String search,
            String modelId, String type, String status, Integer minPrice, Integer maxPrice, int page, int size) {
        CatalogAccess.requireUser(account);
        long offset = (long) (page - 1) * size;
        String normalizedStatus = status == null ? "ACTIVE" : enumValue(status, Set.of("ACTIVE", "INACTIVE"));
        String normalizedModel = optionalToken(modelId, 32);
        String normalizedType = optionalToken(type, 64);
        String normalizedSearch = search == null || search.isBlank() ? null : "%" + search.strip() + "%";
        OffsetDateTime freshAfter = now().minus(properties.getResourceMaxAge());
        String supportedModel = properties.getSupportedModelIds().iterator().next();
        if (minPrice != null && (minPrice < 0 || maxPrice != null && minPrice > maxPrice)) throw validation();
        if (maxPrice != null && maxPrice < 0) throw validation();
        List<MediaRecords.Resource> rows = media.listResources(normalizedSearch, normalizedModel,
                normalizedType, normalizedStatus, minPrice, maxPrice, freshAfter,
                properties.getProviderCode(), supportedModel, size, offset);
        long total = media.countResources(normalizedSearch, normalizedModel, normalizedType,
                normalizedStatus, minPrice, maxPrice, freshAfter,
                properties.getProviderCode(), supportedModel);
        return PageResponse.of(rows.stream().map(this::resourceResponse).toList(), page, size, total);
    }

    @Transactional(readOnly = true)
    public MediaDtos.ResourceSummary resource(AuthenticatedAccount account, long resourceId) {
        CatalogAccess.requireUser(account);
        return resourceResponse(media.findResource(resourceId).orElseThrow(this::notFound));
    }

    @Transactional(readOnly = true)
    public MediaDtos.RecommendationSet recommendations(AuthenticatedAccount account, long articleId, int limit) {
        long userId = CatalogAccess.requireUser(account);
        if (limit < 1 || limit > 5) throw validation();
        ArticleRecords.Article article = articles.find(articleId, userId).orElseThrow(this::notFound);
        ArticleRecords.Workflow workflow = requireSubmissionReady(article);
        ArticleRecords.SubmissionPreference preference = articles.findSubmissionPreference(article.id(), userId)
                .filter(value -> value.articleVersion() == article.version())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        return rankRecommendations(article, workflow, preference, limit, submissionEnabled());
    }

    /** A real-media preview before any user publication confirmation. This writes no preference or snapshot. */
    @Transactional(readOnly = true)
    public MediaDtos.RecommendationSet preparationRecommendations(AuthenticatedAccount account, long articleId, int limit) {
        long userId = CatalogAccess.requireUser(account);
        if (limit < 1 || limit > 5) throw validation();
        ArticleRecords.Article article = articles.find(articleId, userId).orElseThrow(this::notFound);
        ArticleRecords.Workflow workflow = articles.findWorkflow(articleId, userId).orElseThrow(this::notFound);
        if (!Set.of("BODY_READY", "EDITING", "READY_TO_SUBMIT").contains(workflow.workflowStage())
                || !"COMPLETED".equals(article.status()) || article.bodyText() == null || article.bodyText().isBlank()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        ArticleRecords.SubmissionPreference preference = articles.findSubmissionPreference(articleId, userId)
                .filter(value -> value.articleVersion() == article.version())
                .orElseGet(() -> new ArticleRecords.SubmissionPreference(articleId, userId, article.spaceId(),
                        article.version(), "BALANCED", null, null, false, null, now()));
        // This flag is the global submission gate, not proof of user publication confirmation.
        // BODY_READY still cannot be quoted until the final explicit action confirms its body.
        return rankRecommendations(article, workflow, preference, limit, submissionEnabled());
    }

    private boolean submissionEnabled() {
        return properties.isEnabled() && properties.isExecutionEnabled() && taskProperties.isEnabled();
    }

    private MediaDtos.RecommendationSet rankRecommendations(ArticleRecords.Article article,
            ArticleRecords.Workflow workflow, ArticleRecords.SubmissionPreference preference,
            int limit, boolean submissionEnabled) {
        long userId = article.userId();
        // The snapshot is authoritative once created. Before the browser creates it, the current
        // article version is the only available body; quote and confirm still validate the snapshot.
        ArticleRecords.SubmissionSnapshot currentSnapshot = articles.findSnapshotByVersion(
                article.id(), article.version(), userId).orElse(null);
        boolean articleHasExternalLink = containsExternalLink(currentSnapshot == null
                ? article.bodyHtml() : currentSnapshot.bodyHtml());
        int imageCount = currentSnapshot == null ? articles.findAssets(article.id(), userId).size()
                : read(currentSnapshot.assetManifestJson()).size();
        boolean hasImages = imageCount > 0 || containsImages(currentSnapshot == null
                ? article.bodyHtml() : currentSnapshot.bodyHtml());
        RecommendationContext context = recommendationContext(article, workflow, preference);
        List<MediaRecords.Resource> candidates = media.listRecommendationCandidates(
                properties.getProviderCode(), properties.getSupportedModelIds().iterator().next(),
                now().minus(properties.getResourceMaxAge()),
                MAX_RECOMMENDATION_CANDIDATES);
        List<RankedResource> ranked = candidates.stream()
                .filter(resource -> snapshotUsable(resource, now()))
                .filter(resource -> matchesHardConstraints(resource, preference))
                .filter(resource -> !articleHasExternalLink || supportsLink(resource))
                .filter(resource -> !hasImages || imageCount > 0 && imageCount <= properties.getImageMaxCount()
                        && articleImages != null && articleImages.supports(resource))
                .filter(resource -> canPrice(resource, imageCount))
                .map(resource -> rank(resource, context, imageCount))
                .sorted(Comparator.comparingInt(RankedResource::score).reversed()
                        .thenComparing(rankedResource -> rankedResource.resource().estimatedDays(),
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparingInt(rankedResource -> articlePoints(rankedResource.resource(), imageCount))
                        .thenComparing(rankedResource -> rankedResource.resource().name())
                        .thenComparingLong(rankedResource -> rankedResource.resource().id()))
                .toList();
        List<RankedResource> selected = selectPortfolio(ranked, preference.maxTotalPoints(), limit, imageCount);
        List<MediaDtos.Recommendation> items = new ArrayList<>(selected.size());
        for (int index = 0; index < selected.size(); index++) {
            RankedResource candidate = selected.get(index);
            items.add(new MediaDtos.Recommendation(index + 1,
                    Long.toString(candidate.resource().id()), candidate.reasonCode(),
                    candidate.reason(), candidate.score(), candidate.evidence(),
                    resourceResponse(candidate.resource(), imageCount)));
        }
        List<String> limitations = new ArrayList<>();
        limitations.add("媒体库暂未提供历史过稿率、真实发布时间和权威度，本次未对这些指标评分。");
        if (articleHasExternalLink) {
            limitations.add("文章正文包含外链，已排除不支持外链的媒体。");
        }
        if (hasImages) {
            limitations.add(items.isEmpty()
                    ? "当前没有已核验图片抓取规则和费用的图文渠道。请保留含图稿件；渠道核验完成前不会投稿或扣分。"
                    : "已按本稿图片数量计算媒体基础价及图片附加费；图片金额或计费单位不明确的媒体已排除。最终以确认报价为准。");
        }
        if (candidates.size() == MAX_RECOMMENDATION_CANDIDATES) {
            limitations.add("可用媒体超过单次安全候选上限，本次仅评估前 20000 条同步资源。");
        }
        MediaDtos.RecommendationStrategy strategy = new MediaDtos.RecommendationStrategy(
                preference.goal(), preference.preferredRegion(), preference.maxTotalPoints(),
                preference.requiresLink() || articleHasExternalLink,
                context.categories(), candidates.size(), List.copyOf(limitations));
        return new MediaDtos.RecommendationSet(Long.toString(article.id()), article.version(), now(),
                submissionEnabled && (!hasImages || !items.isEmpty()), strategy,
                List.copyOf(items));
    }

    public TaskDtos.DraftResponse quote(AuthenticatedAccount account, String idempotencyHeader,
                                        MediaDtos.QuoteRequest request) {
        return observability.observe("java.media.quote", "VALIDATE_AND_QUOTE", span -> {
            if (account != null) span.tag("userIdHash", observability.reference(account.id()));
            span.tag("taskType", "MEDIA");
        }, () -> quoteInternal(account, idempotencyHeader, request));
    }

    private TaskDtos.DraftResponse quoteInternal(AuthenticatedAccount account, String idempotencyHeader,
                                                  MediaDtos.QuoteRequest request) {
        long userId = CatalogAccess.requireUser(account);
        requireEnabled();
        UUID key = uuid(idempotencyHeader);
        long articleId = positiveId(request.articleId());
        long snapshotId = positiveId(request.articleSnapshotId());
        String requestHash = sha256(canonical(request));
        TaskRecords.Draft replay = tasks.findDraftByIdempotency(userId, "MEDIA_QUOTE_CREATE", key).orElse(null);
        if (replay != null) return replayDraft(replay, requestHash);

        ArticleRecords.Article article = articles.find(articleId, userId).orElseThrow(this::notFound);
        requireSubmissionReady(article);
        ArticleRecords.SubmissionSnapshot snapshot = articles.findSnapshot(snapshotId, articleId, userId)
                .orElseThrow(this::notFound);
        if (snapshot.articleVersion() != article.version() || snapshot.spaceId() != article.spaceId()
                || !java.util.Objects.equals(snapshot.productId(), article.productId())) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        ArrayNode frozenImages = freezeImages(snapshot);
        Set<Long> unique = new HashSet<>();
        List<MediaDtos.PreparedItem> preparedItems = new ArrayList<>();
        OffsetDateTime now = now();
        OffsetDateTime expiresAt = now.plus(properties.getQuoteTtl());
        int total = 0;
        for (MediaDtos.QuoteItem item : request.items()) {
            long resourceId = positiveId(item.mediaResourceId());
            if (!unique.add(resourceId)) throw validation();
            MediaRecords.Resource local = media.findResource(resourceId).orElseThrow(this::notFound);
            MediaRecords.Resource current = local;
            if (!snapshotUsable(current, now)) {
                throw new BusinessException(ErrorCode.MEDIA_QUOTE_CHANGED);
            }
            requireLinkCompatible(snapshot, current);
            if (!frozenImages.isEmpty()) articleImages.requireCompatible(current, frozenImages.size());
            OffsetDateTime resourceExpiresAt = current.syncedAt().plus(properties.getResourceMaxAge());
            if (resourceExpiresAt.isBefore(expiresAt)) expiresAt = resourceExpiresAt;
            MediaImagePricing.Rule imageRule = imageRule(current, frozenImages.size());
            BigDecimal supplierTotal = supplierTotal(current, imageRule, frozenImages.size());
            int points = saleAmountCents(supplierTotal);
            try { total = Math.addExact(total, points); }
            catch (ArithmeticException exception) { throw validation(); }
            MediaRecords.Price price = new MediaRecords.Price(ids.nextId(), current.id(), current.providerCode(),
                    current.upstreamModelId(), current.upstreamResourceId(), supplierTotal, MARKUP_RATE,
                    points, points, now, expiresAt);
            preparedItems.add(new MediaDtos.PreparedItem(current, price, normalizeRemark(item.remark()),
                    imageRule, frozenImages.size()));
        }
        long pricingVersionId = ids.nextId();
        ObjectNode parameters = quoteParameters(articleId, snapshotId, preparedItems);
        ObjectNode inputSnapshot = quoteSnapshot(snapshot, preparedItems, pricingVersionId);
        if (!frozenImages.isEmpty()) {
            inputSnapshot.set("articleImages", frozenImages);
            inputSnapshot.set("imageContract", articleImages.contract());
        }
        ensureSnapshotSize(inputSnapshot);
        MediaDtos.PreparedQuote prepared = new MediaDtos.PreparedQuote(articleId, snapshotId, snapshot.spaceId(),
                snapshot.productId(), parameters, inputSnapshot, List.copyOf(preparedItems), total,
                pricingVersionId, expiresAt);
        return transactions.execute(status -> persistQuote(userId, key, requestHash, prepared));
    }

    public void revalidateForConfirmation(TaskRecords.Draft draft) {
        requireEnabled();
        requireExecutionEnabled();
        JsonNode snapshot = read(draft.inputSnapshotJson());
        long articleId = positiveId(snapshot.path("articleId").textValue());
        long snapshotId = positiveId(snapshot.path("articleSnapshotId").textValue());
        ArticleRecords.Article article = articles.find(articleId, draft.userId()).orElseThrow(this::notFound);
        requireSubmissionReady(article);
        ArticleRecords.SubmissionSnapshot articleSnapshot = articles.findSnapshot(snapshotId, articleId, draft.userId())
                .orElseThrow(this::notFound);
        if (article.version() != articleSnapshot.articleVersion()) {
            throw new BusinessException(ErrorCode.MEDIA_QUOTE_CHANGED);
        }
        ArrayNode frozenImages = freezeImages(articleSnapshot);
        if (!frozenImages.isEmpty()) articleImages.revalidate(articleSnapshot, snapshot);
        if (!draft.expiresAt().isAfter(now())) throw new BusinessException(ErrorCode.TASK_CONFIRMATION_EXPIRED);
        List<JsonNode> items = new ArrayList<>();
        snapshot.path("items").forEach(items::add);
        items.sort(Comparator.comparing((JsonNode item) -> item.path("providerCode").asText())
                .thenComparing(item -> item.path("upstreamModelId").asText())
                .thenComparing(item -> item.path("upstreamResourceId").asText()));
        int quotedTotal = 0;
        for (JsonNode item : items) {
            MediaRecords.Resource current = media.lockResourceForConfirmation(
                            positiveId(item.path("mediaResourceId").textValue()))
                    .orElseThrow(this::notFound);
            requireLinkCompatible(articleSnapshot, current);
            if (!frozenImages.isEmpty()) articleImages.requireCompatible(current, frozenImages.size());
            MediaImagePricing.Rule imageRule = imageRule(current, frozenImages.size());
            BigDecimal supplierTotal = supplierTotal(current, imageRule, frozenImages.size());
            int points = saleAmountCents(supplierTotal);
            if (!snapshotUsable(current, now())
                    || current.version() != item.path("resourceVersion").asInt(-1)
                    || !current.providerCode().equals(item.path("providerCode").asText())
                    || !current.upstreamModelId().equals(item.path("upstreamModelId").asText())
                    || !current.upstreamResourceId().equals(item.path("upstreamResourceId").asText())
                    || supplierTotal.compareTo(new BigDecimal(item.path("upstreamPrice").textValue())) != 0
                    || points != item.path("saleAmountCents").intValue()
                    || points != item.path("chargedPoints").intValue()
                    || !frozenImages.isEmpty() && !imagePricingSnapshot(current, imageRule, frozenImages.size())
                            .equals(item.path("imagePricing"))) {
                throw new BusinessException(ErrorCode.MEDIA_QUOTE_CHANGED);
            }
            try { quotedTotal = Math.addExact(quotedTotal, points); }
            catch (ArithmeticException exception) { throw new BusinessException(ErrorCode.MEDIA_QUOTE_CHANGED); }
        }
        if (quotedTotal != draft.quotedPoints()) throw new BusinessException(ErrorCode.MEDIA_QUOTE_CHANGED);
    }

    public void createConfirmedBatch(TaskRecords.Task task, TaskRecords.Draft draft, OffsetDateTime now) {
        JsonNode snapshot = read(draft.inputSnapshotJson());
        long articleId = positiveId(snapshot.path("articleId").textValue());
        long articleSnapshotId = positiveId(snapshot.path("articleSnapshotId").textValue());
        ArticleRecords.Article article = articles.find(articleId, task.userId()).orElseThrow(this::notFound);
        requireSubmissionReady(article);
        ArticleRecords.SubmissionSnapshot currentSnapshot = articles.findSnapshot(
                articleSnapshotId, articleId, task.userId()).orElseThrow(this::notFound);
        if (currentSnapshot.articleVersion() != article.version()) {
            throw new BusinessException(ErrorCode.MEDIA_QUOTE_CHANGED);
        }
        if (!read(currentSnapshot.assetManifestJson()).isEmpty() || containsImages(currentSnapshot.bodyHtml())) {
            if (articleImages == null) throw new BusinessException(ErrorCode.MEDIA_IMAGE_UNSUPPORTED);
            articleImages.revalidate(currentSnapshot, snapshot);
        }
        long batchId = ids.nextId();
        MediaRecords.Batch batch = new MediaRecords.Batch(batchId, task.id(), draft.id(), task.userId(),
                task.spaceId(), task.productId(), articleId, articleSnapshotId, "QUEUED", task.quotedPoints(),
                0, 0, now, now, null);
        if (media.insertBatch(batch) != 1) throw stateConflict();
        for (JsonNode item : snapshot.path("items")) {
            long submissionId = ids.nextId();
            MediaRecords.Submission submission = new MediaRecords.Submission(submissionId, batchId, task.id(),
                    task.userId(), task.spaceId(), articleId, articleSnapshotId,
                    positiveId(item.path("mediaResourceId").textValue()),
                    positiveId(item.path("priceSnapshotId").textValue()), item.path("providerCode").textValue(),
                    item.path("upstreamModelId").textValue(), item.path("upstreamResourceId").textValue(),
                    item.path("mediaName").textValue(), new BigDecimal(item.path("upstreamPrice").textValue()),
                    MARKUP_RATE, item.path("saleAmountCents").intValue(), item.path("chargedPoints").intValue(),
                    item.path("remark").isNull() ? null : item.path("remark").textValue(),
                    "ZXH" + submissionId, null, null, null, "QUEUED", "NOT_SUBMITTED", null, "[]", null,
                    null, null, null, null, now, now);
            if (media.insertSubmission(submission) != 1) throw stateConflict();
            for (JsonNode image : snapshot.path("articleImages")) {
                if (media.pinSubmissionImage(submissionId, task.userId(), task.spaceId(),
                        positiveId(image.path("assetId").asText()), image.path("objectKey").asText(),
                        image.path("sha256").asText(), image.path("sizeBytes").asLong(),
                        image.path("contentType").asText(),
                        articleImages.publicationKey(image.path("contentType").asText()), now) != 1) {
                    throw new BusinessException(ErrorCode.MEDIA_QUOTE_CHANGED);
                }
            }
            writeEvent(submission, null, "SUBMISSION_QUEUED", null, "QUEUED", "JAVA", null, now);
        }
        ObjectNode payload = json.createObjectNode().put("taskId", Long.toString(task.id()))
                .put("batchId", Long.toString(batchId)).put("attempt", 1);
        if (media.insertLocalOutbox(ids.nextId(), task.userId(), task.id(), canonical(payload),
                TraceIdHolder.outboundTraceparent(), now) != 1) throw stateConflict();
    }

    @Transactional(readOnly = true)
    public PageResponse<MediaDtos.SubmissionSummary> submissions(AuthenticatedAccount account, String status,
                                                                  Long taskId, int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        long offset = (long) (page - 1) * size;
        String normalized = status == null ? null : enumValue(status, STATUSES);
        List<MediaDtos.SubmissionSummary> items = media.listSubmissions(userId, normalized, taskId, size,
                offset).stream().map(this::summary).toList();
        return PageResponse.of(items, page, size, media.countSubmissions(userId, normalized, taskId));
    }

    @Transactional(readOnly = true)
    public MediaDtos.SubmissionDetail submission(AuthenticatedAccount account, long submissionId) {
        long userId = CatalogAccess.requireUser(account);
        MediaRecords.Submission row = media.findSubmission(submissionId, userId).orElseThrow(this::notFound);
        List<MediaDtos.SubmissionEvent> events = media.events(submissionId, userId).stream()
                .map(event -> new MediaDtos.SubmissionEvent(Long.toString(event.id()), event.sequenceNo(),
                        event.eventType(), event.fromStatus(), event.toStatus(), event.stableErrorCode(),
                        event.createdAt())).toList();
        return new MediaDtos.SubmissionDetail(Long.toString(row.id()), Long.toString(row.batchId()),
                Long.toString(row.taskId()), Long.toString(row.articleId()),
                Long.toString(row.articleSnapshotId()), Long.toString(row.mediaResourceId()), row.mediaName(),
                row.status(), row.saleAmountCents(), row.chargedPoints(), row.remark(), row.publishedUrl(),
                MediaPublishedLinks.fromStored(json, row.publishedUrlsJson(), row.publishedUrl()),
                row.stableErrorCode(), events, row.createdAt(), row.updatedAt());
    }

    int synchronizeResources() {
        return observability.observe("java.media.sync", "FETCH_AND_PERSIST", span ->
                span.tag("provider", properties.getProviderCode()), this::synchronizeResourcesInternal);
    }

    private int synchronizeResourcesInternal() {
        requireEnabled();
        int count = 0;
        for (String modelId : properties.getSupportedModelIds()) {
            OffsetDateTime syncStartedAt = now();
            int modelCount = 0;
            Set<String> seenResourceIds = new HashSet<>();
            try {
                OffsetDateTime firstFetchedAt = now();
                MediaProviderAdapter.ResourcePage first = provider.resources(modelId, "", 1,
                        properties.getSyncPageSize());
                if (first.total() < 0 || first.items() == null) {
                    throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", false);
                }
                long requiredPages = Math.max(1L, Math.ceilDiv(first.total(), properties.getSyncPageSize()));
                if (requiredPages > properties.getMaxSyncPages()) {
                    throw new MediaProviderException("MEDIA_RESOURCE_SYNC_INCOMPLETE", false);
                }
                int pages = (int) requiredPages;
                List<ResourceObservation> firstPage = mapPage(modelId, first.items(), firstFetchedAt);
                validatePageCoverage(firstPage, first.total(), seenResourceIds);
                modelCount += persistPage(firstPage);
                for (int page = 2; page <= pages; page++) {
                    OffsetDateTime fetchedAt = now();
                    MediaProviderAdapter.ResourcePage fetched = provider.resources(modelId, "", page,
                            properties.getSyncPageSize());
                    if (fetched.total() != first.total() || fetched.items() == null) {
                        throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", false);
                    }
                    List<ResourceObservation> currentPage = mapPage(modelId, fetched.items(), fetchedAt);
                    validatePageCoverage(currentPage, first.total(), seenResourceIds);
                    modelCount += persistPage(currentPage);
                }
                if (seenResourceIds.size() != first.total()) {
                    throw new MediaProviderException("MEDIA_RESOURCE_SYNC_INCOMPLETE", false);
                }
                int persisted = modelCount;
                transactions.executeWithoutResult(status -> {
                    OffsetDateTime completedAt = now();
                    media.inactivateMissingResources(properties.getProviderCode(), modelId,
                            syncStartedAt, completedAt);
                    media.updateSyncState(properties.getProviderCode(), modelId,
                            "SUCCEEDED", null, persisted, completedAt);
                });
                count += modelCount;
            } catch (MediaProviderException exception) {
                int persisted = modelCount;
                transactions.executeWithoutResult(status -> media.updateSyncState(properties.getProviderCode(),
                        modelId, "FAILED", exception.code(), persisted, now()));
                throw exception;
            }
        }
        return count;
    }

    void processOneOutbox() {
        if (!properties.isExecutionEnabled()) return;
        observability.observe("java.media.submit-or-reconcile", "SUBMIT", span ->
                span.tag("provider", properties.getProviderCode()), this::processOneOutboxInternal);
    }

    private void processOneOutboxInternal() {
        OffsetDateTime claimedAt = now();
        MediaRecords.LocalOutbox outbox = transactions.execute(status -> media.claimLocalOutbox(
                claimedAt, claimedAt.plus(taskProperties.getExecutionLease())).orElse(null));
        if (outbox == null) return;
        try {
            transactions.executeWithoutResult(status -> media.startTask(outbox.taskId(), now()));
            for (MediaRecords.Submission row : media.findByTask(outbox.taskId())) {
                if ("SUBMITTING".equals(row.status()) && "NOT_SUBMITTED".equals(row.submissionState())) {
                    recoverInterruptedSubmission(row);
                    continue;
                }
                if (!"QUEUED".equals(row.status())) continue;
                boolean claimed = Boolean.TRUE.equals(transactions.execute(status ->
                        media.claimSubmission(row.id(), now()) == 1));
                if (!claimed) continue;
                MediaRecords.Submission claimedRow = media.lockSubmission(row.id()).orElseThrow();
                ArticleRecords.SubmissionSnapshot article = articles.findSnapshot(claimedRow.articleSnapshotId(),
                        claimedRow.articleId(), claimedRow.userId()).orElseThrow(this::notFound);
                try {
                    String bodyHtml = article.bodyHtml();
                    if (!read(article.assetManifestJson()).isEmpty() || containsImages(bodyHtml)) {
                        if (articleImages == null) throw new MediaProviderException("MEDIA_IMAGE_UNSUPPORTED", false);
                        TaskRecords.Task task = tasks.findTask(claimedRow.taskId(), claimedRow.userId()).orElseThrow();
                        JsonNode frozen = read(task.inputSnapshotJson());
                        MediaRecords.Resource resource = media.findResource(claimedRow.mediaResourceId()).orElseThrow();
                        // A disabled/changed image contract must stop before supplier admission, never silently strip images.
                        try {
                            int imageCount = frozen.path("articleImages").size();
                            articleImages.requireCompatible(resource, imageCount);
                            JsonNode frozenItem = null;
                            for (JsonNode item : frozen.path("items")) {
                                if (Long.toString(resource.id()).equals(item.path("mediaResourceId").asText())) {
                                    frozenItem = item;
                                    break;
                                }
                            }
                            if (frozenItem == null || !imagePricingSnapshot(resource, imageRule(resource, imageCount),
                                    imageCount).equals(frozenItem.path("imagePricing"))) {
                                throw new BusinessException(ErrorCode.MEDIA_QUOTE_CHANGED);
                            }
                        } catch (BusinessException exception) {
                            throw new MediaProviderException(exception.errorCode().name(), false);
                        }
                        bodyHtml = articleImages.render(article, frozen, claimedRow.id());
                        int pinned = media.markPublicationImagesReady(claimedRow.id(), now());
                        if (pinned != frozen.path("articleImages").size()) {
                            throw new MediaProviderException("MEDIA_IMAGE_PREPARATION_FAILED", false);
                        }
                    }
                    MediaProviderAdapter.SubmitResult result = provider.submit(new MediaProviderAdapter.SubmitCommand(
                            claimedRow.upstreamModelId(), claimedRow.upstreamResourceId(), claimedRow.customerOrderId(),
                            claimedRow.upstreamPrice().setScale(4).toPlainString(), article.title(), bodyHtml,
                            claimedRow.remark() == null ? "请按稿件正文正常排版。" : claimedRow.remark()));
                    transactions.executeWithoutResult(status -> acceptSubmitted(claimedRow, result));
                } catch (MediaProviderException exception) {
                    transactions.executeWithoutResult(status -> recordSubmitFailure(claimedRow, exception));
                }
            }
            transactions.executeWithoutResult(status -> media.finishLocalOutbox(outbox.id(), outbox.attempts(), "PUBLISHED",
                    now(), null, now()));
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> media.finishLocalOutbox(outbox.id(), outbox.attempts(), "PENDING",
                    now().plusSeconds(10), "MEDIA_LOCAL_DISPATCH_FAILED", now()));
        }
    }

    void pollOneSubmission() {
        if (!properties.isExecutionEnabled() || !properties.isOrderPollEnabled()) return;
        observability.observe("java.media.submit-or-reconcile", "RECONCILE", span ->
                span.tag("provider", properties.getProviderCode()), this::pollOneSubmissionInternal);
    }

    private void pollOneSubmissionInternal() {
        MediaRecords.Submission row = media.findPollCandidate(now()).orElse(null);
        if (row == null) return;
        try {
            if (row.upstreamOrderId() == null || row.upstreamOrderId().isBlank()) return;
            MediaProviderAdapter.OrderResult result = provider.order(row.upstreamModelId(), row.upstreamOrderId());
            transactions.executeWithoutResult(status -> {
                MediaRecords.Submission locked = media.lockSubmission(row.id()).orElseThrow();
                if (locked.status().equals(row.status())) applyProviderStatus(locked, result.raw(), null, now());
            });
        } catch (MediaProviderException exception) {
            transactions.executeWithoutResult(status -> {
                MediaRecords.Submission locked = media.lockSubmission(row.id()).orElseThrow();
                if (!Set.of("PUBLISHED", "REJECTED", "FAILED").contains(locked.status())) {
                    media.transition(locked.id(), locked.status(), locked.upstreamStatus(), "RECONCILING",
                            locked.submissionState(), locked.upstreamOrderId(), null, null, exception.code(),
                            now().plus(properties.getUnknownPollDelay()), now());
                }
            });
        }
    }

    JsonNode decodeCallback(String response, String sign, String appKey) {
        return provider.decodeCallback(response, sign, appKey);
    }

    public boolean processCallback(String eventType, JsonNode payload) {
        if (payload == null || !payload.isObject()) throw validation();
        String customerOrderId = boundedText(payload.path("customer_order_id"), 30);
        // The old GEO and Zhixiaohe share the upstream callback account, not orders or
        // articles. Only orders minted by this database may enter its callback ledger.
        if (customerOrderId == null || !customerOrderId.matches("ZXH[1-9][0-9]{0,26}")) {
            throw validation();
        }
        String normalizedEvent = enumValue(eventType.toUpperCase(Locale.ROOT),
                Set.of("PROGRESS", "RECEIPT", "REFUND"));
        ObjectNode normalizedPayload = normalizeOrderCallback(normalizedEvent, payload);
        String hash = sha256(canonical(payload));
        OffsetDateTime now = now();
        int created = transactions.execute(status -> media.insertCallback(ids.nextId(), properties.getProviderCode(),
                customerOrderId, null, null, normalizedEvent, hash, now));
        if (created == 0 && "PROCESSED".equals(media.callbackStatus(properties.getProviderCode(),
                customerOrderId, null, null, normalizedEvent, hash).orElse(null))) return false;
        try {
            transactions.executeWithoutResult(status -> {
                MediaRecords.Submission submission = media.lockByCustomerOrder(customerOrderId)
                        .orElseThrow(this::notFound);
                String callbackModel = boundedText(payload.path("customer_model_id"), 32);
                if (callbackModel != null && !callbackModel.equals(submission.upstreamModelId())) throw validation();
                applyProviderStatus(submission, normalizedPayload,
                        UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.US_ASCII)), now);
                media.finishCallback(properties.getProviderCode(), customerOrderId, null, null, normalizedEvent, hash,
                        "PROCESSED", null, now);
            });
            return true;
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> media.finishCallback(properties.getProviderCode(),
                    customerOrderId, null, null, normalizedEvent, hash,
                    "FAILED", "MEDIA_CALLBACK_PROCESSING_FAILED", now));
            throw exception;
        }
    }

    public boolean processResourceCallback(JsonNode payload) {
        if (payload == null) throw validation();
        JsonNode resources = payload;
        if (payload.isObject() && !payload.has("id") && !payload.has("resource_id")) {
            resources = payload.has("data") ? payload.path("data") : payload;
        }
        if (resources.isObject() && (resources.has("id") || resources.has("resource_id"))) {
            return processResourceCallbackItem(resources);
        }
        if ((!resources.isArray() && !resources.isObject()) || resources.isEmpty() || resources.size() > 500) {
            throw validation();
        }
        boolean processed = false;
        for (JsonNode item : resources) {
            if (!item.isObject()) throw validation();
            processed |= processResourceCallbackItem(item);
        }
        return processed;
    }

    private boolean processResourceCallbackItem(JsonNode payload) {
        // PHP callback payloads encode these identifiers as either strings or integers. Keep
        // the callback identity aligned with mapResource, which prefers id over resource_id.
        String model = resourceCallbackIdentifier(payload.path("model_id"), 32);
        String resourceId = resourceCallbackIdentifier(
                payload.has("id") ? payload.path("id") : payload.path("resource_id"), 64);
        if (model == null || !model.matches("[1-9][0-9]{0,31}")
                || resourceId == null || !resourceId.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw validation();
        }
        // The provider sends callbacks for every procurement model, while this release only
        // catalogs the explicitly supported model. Acknowledge valid foreign-model events
        // without importing or retrying them; malformed identities still fail validation.
        if (!properties.getSupportedModelIds().contains(model)) return false;
        boolean unpriced = resourcePriceUnavailable(payload);
        String hash = sha256(canonical(payload));
        OffsetDateTime now = now();
        int created = transactions.execute(status -> media.insertCallback(ids.nextId(), properties.getProviderCode(),
                null, model, resourceId, "RESOURCE_UPDATE", hash, now));
        if (created == 0 && "PROCESSED".equals(media.callbackStatus(properties.getProviderCode(),
                null, model, resourceId, "RESOURCE_UPDATE", hash).orElse(null))) return false;
        try {
            transactions.executeWithoutResult(status -> {
                MediaRecords.Resource current = media.findResourceByUpstream(
                        properties.getProviderCode(), model, resourceId).orElse(null);
                if (unpriced) {
                    String name = firstText(payload, "name");
                    String upstreamStatus = firstText(payload, "status");
                    if (name == null || name.length() > 255 || upstreamStatus == null
                            || !Set.of("-1", "0", "1").contains(upstreamStatus)) {
                        throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", false);
                    }
                    // An unpriced resource must not retain an old orderable quote. Preserve the
                    // last real price for audit, but make the existing row inactive. A new
                    // unpriced resource has no truthful price to persist and remains absent.
                    if (current != null) media.upsertResource(inactiveUnpricedResource(current, payload, now));
                } else {
                    long id = current == null ? ids.nextId() : current.id();
                    media.upsertResource(mapResource(payload, id, now));
                }
                media.finishCallback(properties.getProviderCode(), null, model, resourceId,
                        "RESOURCE_UPDATE", hash, "PROCESSED",
                        unpriced ? "MEDIA_RESOURCE_PRICE_UNAVAILABLE" : null,
                        now);
            });
            return true;
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> media.finishCallback(properties.getProviderCode(),
                    null, model, resourceId, "RESOURCE_UPDATE", hash,
                    "FAILED", "MEDIA_CALLBACK_PROCESSING_FAILED", now));
            throw exception;
        }
    }

    private ObjectNode normalizeOrderCallback(String eventType, JsonNode payload) {
        ObjectNode result = payload.deepCopy();
        switch (eventType) {
            case "PROGRESS" -> {
                String progress = callbackScalar(payload.path("order_progress"), 32);
                if (progress == null) throw validation();
                result.put("status", "1".equals(progress) || "1001".equals(progress) ? "1001" : "1000");
            }
            case "RECEIPT" -> {
                // The signature authenticates the payload, not the callback URL. A signed progress
                // or refund envelope replayed to the receipt route must not settle frozen points.
                if (payload.has("order_progress") || payload.has("type")
                        || !hasReceiptEvidence(payload)) throw validation();
                result.put("status", "1002");
            }
            case "REFUND" -> {
                String type = callbackScalar(payload.path("type"), 8);
                if (!"1".equals(type)) throw validation();
                result.put("status", "1004");
            }
            default -> throw validation();
        }
        return result;
    }

    private boolean hasReceiptEvidence(JsonNode payload) {
        for (String field : List.of("return_info", "return_content", "return_file")) {
            JsonNode value = payload.get(field);
            if (value != null && value.isTextual() && !value.textValue().isBlank()) return true;
        }
        return false;
    }

    static int saleAmountCents(BigDecimal upstreamPrice) {
        if (upstreamPrice == null || upstreamPrice.signum() <= 0 || upstreamPrice.scale() > 4) throw validationStatic();
        try {
            return upstreamPrice.multiply(MULTIPLIER).setScale(0, RoundingMode.CEILING)
                    .multiply(BigDecimal.valueOf(100)).intValueExact();
        } catch (ArithmeticException exception) { throw validationStatic(); }
    }

    private TaskDtos.DraftResponse persistQuote(long userId, UUID key, String requestHash,
                                                 MediaDtos.PreparedQuote prepared) {
        TaskRecords.Draft replay = tasks.findDraftByIdempotency(userId, "MEDIA_QUOTE_CREATE", key).orElse(null);
        if (replay != null) return replayDraft(replay, requestHash);
        prepared.items().forEach(item -> {
            if (media.insertPrice(item.price()) != 1) throw stateConflict();
        });
        OffsetDateTime now = now();
        String snapshotJson = canonical(prepared.inputSnapshot());
        TaskRecords.Draft draft = new TaskRecords.Draft(ids.nextId(), userId, null, null, null,
                "FORM", "MEDIA_QUOTE_CREATE", key, requestHash, "MEDIA", 1, prepared.spaceId(),
                prepared.productId(), canonical(prepared.parameters()), snapshotJson, sha256(snapshotJson),
                prepared.pricingVersionId(), prepared.quotedPoints(), "[]", "WAITING_CONFIRMATION",
                prepared.expiresAt(), null, now, now);
        String token = confirmationTokens.token(draft);
        TaskRecords.Confirmation confirmation = new TaskRecords.Confirmation(ids.nextId(), draft.id(), userId,
                confirmationTokens.sha256(token), draft.inputHash(), draft.pricingVersionId(), "AVAILABLE",
                draft.expiresAt(), null, now);
        if (tasks.insertDraft(draft) != 1 || tasks.insertConfirmation(confirmation) != 1) throw stateConflict();
        return response(draft, token);
    }

    private TaskDtos.DraftResponse replayDraft(TaskRecords.Draft draft, String requestHash) {
        if (!MessageDigest.isEqual(draft.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        String token = "WAITING_CONFIRMATION".equals(draft.status()) && draft.expiresAt().isAfter(now())
                ? confirmationTokens.token(draft) : null;
        return response(draft, token);
    }

    private TaskDtos.DraftResponse response(TaskRecords.Draft draft, String token) {
        return new TaskDtos.DraftResponse(Long.toString(draft.id()), null, draft.draftSource(), draft.taskType(),
                draft.parameterSchemaVersion(), draft.status(), Long.toString(draft.spaceId()),
                draft.productId() == null ? null : Long.toString(draft.productId()), read(draft.parametersJson()),
                read(draft.warningsJson()), draft.inputHash(), Long.toString(draft.pricingVersionId()),
                draft.quotedPoints(), token, draft.expiresAt(), draft.createdAt());
    }

    private record ResourceObservation(String modelId, String resourceId, MediaRecords.Resource priced,
                                       JsonNode unpricedPayload, OffsetDateTime seenAt) { }

    private List<ResourceObservation> mapPage(String modelId, List<JsonNode> items, OffsetDateTime now) {
        List<ResourceObservation> resources = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            if (item == null || !item.isObject()) {
                throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", false);
            }
            String sourceModel = resourceCallbackIdentifier(item.path("model_id"), 32);
            String resourceId = resourceCallbackIdentifier(
                    item.has("id") ? item.path("id") : item.path("resource_id"), 64);
            String name = firstText(item, "name");
            String status = firstText(item, "status");
            if (!modelId.equals(sourceModel) || resourceId == null
                    || !resourceId.matches("[A-Za-z0-9._:-]{1,64}")
                    || name == null || name.length() > 255 || status == null
                    || !Set.of("-1", "0", "1").contains(status)) {
                throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", false);
            }
            MediaRecords.Resource priced = resourcePriceUnavailable(item)
                    ? null : mapResource(item, ids.nextId(), now);
            resources.add(new ResourceObservation(modelId, resourceId, priced,
                    priced == null ? item : null, now));
        }
        return List.copyOf(resources);
    }

    private void validatePageCoverage(List<ResourceObservation> page, long expectedTotal,
                                      Set<String> seenResourceIds) {
        if (page.size() > properties.getSyncPageSize() || page.size() > expectedTotal) {
            throw new MediaProviderException("MEDIA_RESOURCE_SYNC_INCOMPLETE", false);
        }
        for (ResourceObservation resource : page) {
            if (!seenResourceIds.add(resource.resourceId()) || seenResourceIds.size() > expectedTotal) {
                throw new MediaProviderException("MEDIA_RESOURCE_SYNC_INCOMPLETE", false);
            }
        }
    }

    private int persistPage(List<ResourceObservation> resources) {
        return transactions.execute(status -> {
            resources.stream().sorted(Comparator.comparing(ResourceObservation::modelId)
                            .thenComparing(ResourceObservation::resourceId))
                    .forEach(observation -> {
                MediaRecords.Resource resource = observation.priced();
                if (resource == null) {
                    MediaRecords.Resource current = media.findResourceByUpstream(properties.getProviderCode(),
                            observation.modelId(), observation.resourceId()).orElse(null);
                    if (current == null) return;
                    resource = inactiveUnpricedResource(current, observation.unpricedPayload(), observation.seenAt());
                }
                int changed = media.upsertResource(resource);
                if (changed < 0 || changed > 1) {
                    throw new MediaProviderException("MEDIA_RESOURCE_PERSIST_FAILED", false);
                }
            });
            // Count every validated upstream item, including a new unpriced one that cannot
            // truthfully be stored in the positive-price catalogue.
            return resources.size();
        });
    }

    private MediaRecords.Resource mapResource(JsonNode node, long id, OffsetDateTime now) {
        String resourceId = firstText(node, "id", "resource_id");
        String modelId = firstText(node, "model_id");
        String name = firstText(node, "name");
        String statusValue = firstText(node, "status");
        if (resourceId == null || !resourceId.matches("[A-Za-z0-9._:-]{1,64}")
                || modelId == null || !properties.getSupportedModelIds().contains(modelId)
                || name == null || name.length() > 255 || statusValue == null
                || !Set.of("-1", "0", "1").contains(statusValue)) {
            throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", false);
        }
        BigDecimal price = decimal(node.get("price_assign"));
        String raw = canonical(sanitizedRaw(node));
        if (raw.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", false);
        }
        ObjectNode capabilities = json.createObjectNode();
        putOptional(capabilities, "linkPolicy", firstText(node, "lianjieleixing"), 120);
        putOptional(capabilities, "imagePolicy", firstText(node, "ketudai", "tupian"), 120);
        putOptional(capabilities, "imageFeeDescription", firstText(node, "tupianfeiyong"), 120);
        return new MediaRecords.Resource(id, properties.getProviderCode(), modelId, resourceId, name.strip(),
                "1".equals(statusValue) ? "ACTIVE" : "INACTIVE",
                bounded(firstText(node, "type", "resource_type"), 64), bounded(firstText(node, "pindaoleixing"), 120),
                bounded(firstText(node, "quyu"), 120), integer(node.get("shixiao"), 0, 365),
                canonical(capabilities), price, raw, now, now, 1);
    }

    private JsonNode sanitizedRaw(JsonNode node) {
        ObjectNode raw = json.createObjectNode();
        for (String field : List.of("id", "resource_id", "model_id", "name", "price_assign", "status",
                "lianjieleixing", "ketudai", "tupian", "tupianfeiyong", "pindaoleixing", "quyu", "shixiao")) {
            if (node.has(field)) raw.set(field, node.get(field));
        }
        return raw;
    }

    private ObjectNode quoteParameters(long articleId, long snapshotId, List<MediaDtos.PreparedItem> items) {
        ObjectNode result = json.createObjectNode();
        result.put("articleId", Long.toString(articleId)); result.put("articleSnapshotId", Long.toString(snapshotId));
        ArrayNode array = result.putArray("items");
        items.forEach(item -> {
            ObjectNode node = array.addObject().put("mediaResourceId", Long.toString(item.resource().id()));
            if (item.remark() == null) node.putNull("remark"); else node.put("remark", item.remark());
            node.set("pricing", articlePricing(item.resource(), item.imageRule(), item.imageCount()));
        });
        return result;
    }

    private ObjectNode quoteSnapshot(ArticleRecords.SubmissionSnapshot article,
                                     List<MediaDtos.PreparedItem> items, long pricingVersionId) {
        ObjectNode result = json.createObjectNode();
        result.put("taskType", "MEDIA"); result.put("parameterSchemaVersion", 1);
        result.put("articleId", Long.toString(article.articleId()));
        result.put("articleSnapshotId", Long.toString(article.id()));
        result.put("articleVersion", article.articleVersion()); result.put("articleBodyHash", article.bodyHash());
        result.put("pricingVersionId", Long.toString(pricingVersionId));
        ArrayNode array = result.putArray("items");
        items.forEach(item -> {
            MediaRecords.Resource r = item.resource(); MediaRecords.Price p = item.price();
            ObjectNode node = array.addObject();
            node.put("mediaResourceId", Long.toString(r.id())); node.put("priceSnapshotId", Long.toString(p.id()));
            node.put("providerCode", r.providerCode()); node.put("upstreamModelId", r.upstreamModelId());
            node.put("upstreamResourceId", r.upstreamResourceId()); node.put("mediaName", r.name());
            node.put("resourceVersion", r.version());
            node.put("upstreamPrice", p.upstreamPrice().setScale(4).toPlainString());
            node.put("markupRate", MARKUP_RATE.toPlainString()); node.put("saleAmountCents", p.saleAmountCents());
            node.put("chargedPoints", p.chargedPoints());
            if (item.imageCount() > 0) node.set("imagePricing",
                    imagePricingSnapshot(r, item.imageRule(), item.imageCount()));
            if (item.remark() == null) node.putNull("remark"); else node.put("remark", item.remark());
        });
        return result;
    }

    private MediaDtos.ResourceSummary resourceResponse(MediaRecords.Resource row) {
        return resourceResponse(row, null);
    }

    private MediaDtos.ResourceSummary resourceResponse(MediaRecords.Resource row, Integer imageCount) {
        int price = imageCount == null ? saleAmountCents(row.upstreamPrice()) : articlePoints(row, imageCount);
        OffsetDateTime now = now();
        OffsetDateTime quoteExpiresAt = now.plus(properties.getQuoteTtl());
        OffsetDateTime snapshotExpiresAt = row.syncedAt().plus(properties.getResourceMaxAge());
        if (snapshotExpiresAt.isBefore(quoteExpiresAt)) quoteExpiresAt = snapshotExpiresAt;
        ObjectNode capabilities = (ObjectNode) read(row.capabilitiesJson()).deepCopy();
        boolean supportsImages = articleImages != null && articleImages.supports(row);
        capabilities.put("supportsImages", supportsImages);
        // Project current server policy; never trust a copied provider capability as a calculated quote.
        capabilities.remove(List.of("imageFeePoints", "imageFeeDescription", "articlePricing",
                "imageFeeMode", "imagePricingDescription"));
        if (supportsImages) {
            MediaImagePricing.Rule rule = imageRule(row, 1);
            capabilities.put("imageFeeMode", rule.mode()).put("imagePricingDescription", rule.description())
                    .put("maxImageCount", properties.getImageMaxCount()).put("maxImageBytes", properties.getImageMaxBytes());
        }
        if (imageCount != null) capabilities.set("articlePricing", articlePricing(row, imageRule(row, imageCount), imageCount));
        return new MediaDtos.ResourceSummary(Long.toString(row.id()), row.providerCode(), row.upstreamModelId(),
                row.upstreamResourceId(), row.name(), snapshotUsable(row, now) ? "ACTIVE" : "INACTIVE",
                row.resourceType(), row.channelCategory(),
                row.region(), row.estimatedDays(), capabilities, price, price,
                quoteExpiresAt, row.syncedAt());
    }

    private MediaImagePricing.Rule imageRule(MediaRecords.Resource resource, int imageCount) {
        if (imageCount == 0) return null;
        return MediaImagePricing.resolve(properties, resource, json)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_IMAGE_UNSUPPORTED));
    }

    private BigDecimal supplierTotal(MediaRecords.Resource resource, MediaImagePricing.Rule rule, int imageCount) {
        return resource.upstreamPrice().add(imageCount == 0 ? BigDecimal.ZERO : rule.fee(imageCount));
    }

    private int articlePoints(MediaRecords.Resource resource, int imageCount) {
        return saleAmountCents(supplierTotal(resource, imageRule(resource, imageCount), imageCount));
    }

    private boolean canPrice(MediaRecords.Resource resource, int imageCount) {
        try { articlePoints(resource, imageCount); return true; }
        catch (BusinessException | ArithmeticException exception) { return false; }
    }

    /** User-facing totals use exactly the existing whole-yuan markup/rounding rule, once per media. */
    private ObjectNode articlePricing(MediaRecords.Resource resource, MediaImagePricing.Rule rule, int imageCount) {
        int base = saleAmountCents(resource.upstreamPrice());
        int total = saleAmountCents(supplierTotal(resource, rule, imageCount));
        int imageFee = total - base;
        String mode = imageCount == 0 ? "NONE" : rule.mode();
        String description = imageCount == 0 ? "纯文稿件，无图片附加费"
                : "本稿 " + imageCount + " 张图片；" + rule.description()
                    + "；图片导致的报价增加 " + imageFee + " 积分，已计入总价（统一取整）。";
        return json.createObjectNode().put("imageCount", imageCount)
                .put("baseSaleAmountCents", base).put("imageFeeAmountCents", imageFee)
                .put("totalSaleAmountCents", total).put("basePoints", base)
                .put("imageFeePoints", imageFee).put("totalPoints", total)
                .put("imageFeeMode", mode).put("description", description);
    }

    /** Private supplier-cost snapshot: browser inputs never supply these amounts or rate definitions. */
    private ObjectNode imagePricingSnapshot(MediaRecords.Resource resource, MediaImagePricing.Rule rule,
                                            int imageCount) {
        return json.createObjectNode().put("version", 1).put("imageCount", imageCount)
                .put("baseUpstreamPrice", resource.upstreamPrice().setScale(4).toPlainString())
                .put("mode", rule.mode()).put("unitAmount", rule.unitAmount().toPlainString())
                .put("source", rule.source()).put("definition", rule.definition())
                .put("imageUpstreamPrice", rule.fee(imageCount).toPlainString())
                .put("totalUpstreamPrice", supplierTotal(resource, rule, imageCount).setScale(4).toPlainString());
    }

    private MediaRecords.Resource inactiveUnpricedResource(MediaRecords.Resource current,
                                                             JsonNode payload, OffsetDateTime now) {
        String raw = canonical(sanitizedRaw(payload));
        if (raw.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", false);
        }
        return new MediaRecords.Resource(current.id(), current.providerCode(), current.upstreamModelId(),
                current.upstreamResourceId(), current.name(), "INACTIVE", current.resourceType(),
                current.channelCategory(), current.region(), current.estimatedDays(), current.capabilitiesJson(),
                current.upstreamPrice(), raw, now, now, current.version());
    }

    private boolean snapshotUsable(MediaRecords.Resource resource, OffsetDateTime at) {
        return "ACTIVE".equals(resource.status())
                && properties.getProviderCode().equals(resource.providerCode())
                && properties.getSupportedModelIds().contains(resource.upstreamModelId())
                && !resource.syncedAt().isBefore(at.minus(properties.getResourceMaxAge()));
    }

    private void requireTextOnly(ArticleRecords.SubmissionSnapshot snapshot) {
        JsonNode manifest = read(snapshot.assetManifestJson());
        if (!manifest.isArray() || !manifest.isEmpty()
                || !Jsoup.parseBodyFragment(snapshot.bodyHtml() == null ? "" : snapshot.bodyHtml())
                        .select("img, [data-asset-id]").isEmpty()) {
            throw new BusinessException(ErrorCode.MEDIA_IMAGE_UNSUPPORTED);
        }
    }

    private ArrayNode freezeImages(ArticleRecords.SubmissionSnapshot snapshot) {
        if (articleImages != null) return articleImages.freeze(snapshot);
        requireTextOnly(snapshot);
        return json.createArrayNode();
    }

    private boolean containsImages(String html) {
        return !Jsoup.parseBodyFragment(html == null ? "" : html).select("img, [data-asset-id]").isEmpty();
    }

    private void requireLinkCompatible(ArticleRecords.SubmissionSnapshot snapshot,
                                       MediaRecords.Resource resource) {
        if (containsExternalLink(snapshot.bodyHtml()) && !supportsLink(resource)) {
            throw new BusinessException(ErrorCode.MEDIA_LINK_UNSUPPORTED);
        }
    }

    private boolean containsExternalLink(String bodyHtml) {
        String body = bodyHtml == null ? "" : bodyHtml;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("http://") || lower.contains("https://")
                || !Jsoup.parseBodyFragment(body).select("a[href]").isEmpty();
    }

    private ArticleRecords.Workflow requireSubmissionReady(ArticleRecords.Article article) {
        ArticleRecords.Workflow workflow = articles.findWorkflow(article.id(), article.userId())
                .orElseThrow(this::notFound);
        if (!SUBMISSION_READY_STAGES.contains(workflow.workflowStage())
                || !"COMPLETED".equals(article.status()) || article.bodyText() == null
                || article.bodyText().isBlank()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return workflow;
    }

    private RecommendationContext recommendationContext(ArticleRecords.Article article,
                                                        ArticleRecords.Workflow workflow,
                                                        ArticleRecords.SubmissionPreference preference) {
        JsonNode plan = json.createObjectNode();
        if (workflow.confirmedPlanVersion() != null) {
            ArticleRecords.CreativePlanRevision revision = articles.findPlanRevision(article.id(), article.userId(),
                    workflow.confirmedPlanVersion()).orElse(null);
            if (revision != null) plan = read(revision.planJson());
        }
        ProductRecord product = article.productId() == null ? null
                : products.requireProduct(article.userId(), article.productId());
        String productCategory = product == null ? null : product.category();
        String targetPlatform = textValue(plan.get("targetPlatform"));
        String productSignals = String.join(" ", nonNullText(
                product == null ? null : product.name(), productCategory)).toLowerCase(Locale.ROOT);
        String articleSignals = String.join(" ", nonNullText(article.title(), article.bodyText(),
                textValue(plan.get("summary")), textValue(plan.get("coreMessage")),
                textValue(plan.get("targetAudience")))).toLowerCase(Locale.ROOT);
        Map<String, Integer> categoryScores = new java.util.HashMap<>();
        CATEGORY_KEYWORDS.forEach((category, keywords) -> {
            int score = 0;
            if (productCategory != null
                    && (productCategory.contains(category) || category.contains(productCategory))) score += 120;
            for (String keyword : keywords) {
                String normalized = keyword.toLowerCase(Locale.ROOT);
                if (productSignals.contains(normalized)) score += 80;
                if (articleSignals.contains(normalized)) score += 10;
            }
            if (score > 0) categoryScores.put(category, score);
        });
        List<String> categories = categoryScores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
        return new RecommendationContext(preference.goal(), categories,
                targetPlatform == null ? null : targetPlatform.toLowerCase(Locale.ROOT));
    }

    private List<String> nonNullText(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) if (value != null && !value.isBlank()) result.add(value);
        return result;
    }

    private String textValue(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue().strip() : null;
    }

    private boolean matchesHardConstraints(MediaRecords.Resource resource,
                                           ArticleRecords.SubmissionPreference preference) {
        if (preference.preferredRegion() != null
                && !(preference.preferredRegion().equalsIgnoreCase(resource.region())
                || "全国".equals(resource.region()))) return false;
        return !preference.requiresLink() || supportsLink(resource);
    }

    private boolean supportsLink(MediaRecords.Resource resource) {
        JsonNode capabilities = read(resource.capabilitiesJson());
        JsonNode explicit = capabilities.get("supportsLink");
        if (explicit != null && explicit.isBoolean()) return explicit.booleanValue();
        String policy = textValue(capabilities.get("linkPolicy"));
        if (policy == null || policy.contains("不可") || policy.contains("禁止")) return false;
        return policy.contains("可带") || policy.equalsIgnoreCase("SUPPORTED");
    }

    private RankedResource rank(MediaRecords.Resource resource, RecommendationContext context, int imageCount) {
        int score = 10;
        List<String> evidence = new ArrayList<>();
        int categoryIndex = categoryMatchIndex(resource.channelCategory(), context.categories());
        if (categoryIndex == 0) {
            score += "INDUSTRY_MATCH".equals(context.goal()) ? 90 : 70;
            evidence.add("频道与文章/商品首要品类匹配：" + resource.channelCategory());
        } else if (categoryIndex > 0) {
            score += "INDUSTRY_MATCH".equals(context.goal()) ? 65 : 45;
            evidence.add("频道与文章相关品类匹配：" + resource.channelCategory());
        }
        if (context.targetPlatform() != null && (matches(resource.channelCategory(), context.targetPlatform())
                || matches(resource.resourceType(), context.targetPlatform())
                || matches(resource.capabilitiesJson(), context.targetPlatform()))) {
            score += 15;
            evidence.add("媒体元数据与已确认目标平台匹配");
        }
        if ("全国".equals(resource.region())) {
            score += "BROAD_REACH".equals(context.goal()) ? 35 : 12;
            evidence.add("覆盖全国");
        }
        if (matches(resource.channelCategory(), "新闻")) {
            score += "BROAD_REACH".equals(context.goal()) ? 45 : 12;
            evidence.add("综合新闻资讯频道");
        }
        int points = articlePoints(resource, imageCount);
        int costScore = Math.max(0, 30 - Math.min(30, points / 1_000));
        score += "COST_EFFECTIVE".equals(context.goal()) ? costScore * 2 : costScore / 2;
        if (costScore >= 20) evidence.add("当前报价处于较低积分区间");
        if (supportsLink(resource)) {
            score += 5;
            evidence.add("媒体资料标记为可带链接");
        }
        String reasonCode;
        String reason;
        if (categoryIndex >= 0) {
            reasonCode = "INDUSTRY_CATEGORY_MATCH";
            reason = "与文章和商品品类相符，并结合地域、带链能力与当前积分排序。";
        } else if ("BROAD_REACH".equals(context.goal())
                && ("全国".equals(resource.region()) || matches(resource.channelCategory(), "新闻"))) {
            reasonCode = "BROAD_REACH";
            reason = "符合广泛触达策略，并结合当前可验证的覆盖地域和频道分类排序。";
        } else if ("COST_EFFECTIVE".equals(context.goal())) {
            reasonCode = "COST_EFFECTIVE";
            reason = "在满足硬约束的媒体中优先按当前积分控制成本。";
        } else {
            reasonCode = "ACTIVE_RESOURCE_FALLBACK";
            reason = "缺少更强的可验证匹配信号，按当前积分和稳定字段保守排序。";
        }
        if (evidence.isEmpty()) evidence.add("媒体当前状态为可用");
        return new RankedResource(resource, score, reasonCode, reason, List.copyOf(evidence));
    }

    private int categoryMatchIndex(String resourceCategory, List<String> detectedCategories) {
        if (resourceCategory == null || resourceCategory.isBlank()) return -1;
        String normalized = resourceCategory.strip().toLowerCase(Locale.ROOT);
        for (int index = 0; index < detectedCategories.size(); index++) {
            String detected = detectedCategories.get(index);
            String normalizedDetected = detected.toLowerCase(Locale.ROOT);
            if (normalized.equals(normalizedDetected) || normalized.contains(normalizedDetected)
                    || normalizedDetected.contains(normalized)) return index;
            if (CATEGORY_KEYWORDS.getOrDefault(detected, List.of()).stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(normalized::contains)) return index;
        }
        return -1;
    }

    private List<RankedResource> selectPortfolio(List<RankedResource> ranked, Integer maxTotalPoints, int limit,
                                                int imageCount) {
        List<RankedResource> selected = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, Integer> categories = new java.util.HashMap<>();
        int total = 0;
        for (RankedResource candidate : ranked) {
            String normalizedName = candidate.resource().name().strip().toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", "");
            if (names.contains(normalizedName)) continue;
            String category = candidate.resource().channelCategory() == null
                    ? "" : candidate.resource().channelCategory();
            int categoryLimit = "INDUSTRY_CATEGORY_MATCH".equals(candidate.reasonCode()) ? 4 : 3;
            if (categories.getOrDefault(category, 0) >= categoryLimit) continue;
            int points = articlePoints(candidate.resource(), imageCount);
            if ((long) total + points > Integer.MAX_VALUE
                    || maxTotalPoints != null && (long) total + points > maxTotalPoints) continue;
            selected.add(candidate);
            names.add(normalizedName);
            total += points;
            categories.merge(category, 1, Integer::sum);
            if (selected.size() == limit) break;
        }
        return List.copyOf(selected);
    }

    private boolean matches(String value, String signal) {
        return signal != null && value != null && value.toLowerCase(Locale.ROOT).contains(signal);
    }

    private record RecommendationContext(String goal, List<String> categories, String targetPlatform) { }
    private record RankedResource(MediaRecords.Resource resource, int score,
                                  String reasonCode, String reason, List<String> evidence) { }

    private MediaDtos.SubmissionSummary summary(MediaRecords.Submission row) {
        return new MediaDtos.SubmissionSummary(Long.toString(row.id()), Long.toString(row.batchId()),
                Long.toString(row.taskId()), Long.toString(row.articleId()), Long.toString(row.articleSnapshotId()),
                Long.toString(row.mediaResourceId()), row.mediaName(), row.status(), row.saleAmountCents(),
                row.chargedPoints(), row.publishedUrl(),
                MediaPublishedLinks.fromStored(json, row.publishedUrlsJson(), row.publishedUrl()),
                row.stableErrorCode(), row.createdAt(), row.updatedAt());
    }

    private void ensureSnapshotSize(JsonNode node) {
        if (canonical(node).getBytes(StandardCharsets.UTF_8).length > 262_144) throw validation();
    }

    private void writeEvent(MediaRecords.Submission submission, UUID sourceEventId, String type,
                            String from, String to, String source, String error, OffsetDateTime now) {
        media.insertEvent(ids.nextId(), submission.id(), submission.batchId(), submission.userId(),
                media.nextEventSequence(submission.id()), sourceEventId, type, from, to, source, error, now);
    }

    private void requireEnabled() {
        if (!properties.isEnabled() || !properties.providerConfigured()) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
    }

    private void requireExecutionEnabled() {
        if (!properties.isExecutionEnabled()) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
    }

    private String normalizeRemark(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > 1500) throw validation();
        return normalized;
    }

    private String enumValue(String value, Set<String> allowed) {
        String normalized = value == null ? null : value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw validation();
        return normalized;
    }

    private String optionalToken(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max || !normalized.matches("[A-Za-z0-9._:-]+")) throw validation();
        return normalized;
    }

    private long positiveId(String value) {
        try { long result = Long.parseLong(value); if (result <= 0) throw new NumberFormatException(); return result; }
        catch (RuntimeException exception) { throw validation(); }
    }

    private UUID uuid(String value) {
        try { UUID result = UUID.fromString(value); if (!result.toString().equals(value)) throw new IllegalArgumentException(); return result; }
        catch (RuntimeException exception) { throw validation(); }
    }

    private BigDecimal decimal(JsonNode value) {
        try {
            BigDecimal result = new BigDecimal(value == null ? "" : value.asText().strip()).setScale(4, RoundingMode.UNNECESSARY);
            if (result.signum() <= 0 || result.precision() - result.scale() > 8) throw new ArithmeticException();
            return result;
        } catch (RuntimeException exception) { throw new MediaProviderException("MEDIA_PROVIDER_RESPONSE_INVALID", false); }
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText().strip();
        }
        return null;
    }

    private Integer integer(JsonNode value, int min, int max) {
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        try { int result = Integer.parseInt(value.asText()); return result >= min && result <= max ? result : null; }
        catch (RuntimeException exception) { return null; }
    }

    private String bounded(String value, int max) { return value == null ? null : value.substring(0, Math.min(max, value.length())); }
    private void putOptional(ObjectNode node, String field, String value, int max) {
        if (value != null) node.put(field, bounded(value, max));
    }
    private String boundedText(JsonNode value, int max) {
        return value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max
                ? null : value.textValue().strip();
    }

    private String callbackScalar(JsonNode value, int max) {
        if (value == null || (!value.isTextual() && !value.isIntegralNumber())) return null;
        String text = value.asText().strip();
        return text.isEmpty() || text.length() > max ? null : text;
    }

    private String resourceCallbackIdentifier(JsonNode value, int max) {
        if (value == null || value.isNull()) return null;
        String result;
        if (value.isIntegralNumber()) {
            if (!value.canConvertToLong() || value.longValue() <= 0) return null;
            result = value.asText();
        } else if (value.isTextual()) {
            result = value.textValue().strip();
        } else {
            return null;
        }
        return result.isEmpty() || result.length() > max ? null : result;
    }

    private boolean resourcePriceUnavailable(JsonNode resource) {
        JsonNode price = resource.get("price_assign");
        return price == null || price.isNull() || (price.isTextual() && price.textValue().isBlank());
    }
    private JsonNode read(String value) { try { return json.readTree(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private String canonical(Object value) { try { return json.writeValueAsString(sort(json.valueToTree(value))); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private JsonNode sort(JsonNode node) {
        if (node.isObject()) { ObjectNode result=json.createObjectNode(); List<String> names=new ArrayList<>(); node.fieldNames().forEachRemaining(names::add); names.sort(Comparator.naturalOrder()); names.forEach(name -> result.set(name,sort(node.get(name)))); return result; }
        if (node.isArray()) { ArrayNode result=json.createArrayNode(); node.forEach(value -> result.add(sort(value))); return result; }
        return node;
    }
    private String sha256(String value) { return confirmationTokens.sha256(value); }
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    private BusinessException stateConflict() { return new BusinessException(ErrorCode.TASK_STATE_CONFLICT); }
    private BusinessException validation() { return validationStatic(); }
    private static BusinessException validationStatic() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }

    private BusinessException providerFailure(MediaProviderException exception) {
        return switch (exception.code()) {
            case "MEDIA_PROVIDER_RATE_LIMITED" -> new BusinessException(ErrorCode.PROVIDER_RATE_LIMITED);
            case "MEDIA_PROVIDER_NOT_CONFIGURED", "MEDIA_PROVIDER_AUTHENTICATION_FAILED" ->
                    new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
            default -> new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE);
        };
    }

    // Implemented below with row locking; Java, not Provider payload, decides points and terminal state.
    private void applyProviderStatus(MediaRecords.Submission submission, JsonNode payload,
                                     UUID sourceEventId, OffsetDateTime now) {
        String upstreamStatus = firstText(payload, "status");
        String upstreamOrderId = firstText(payload, "resource_order_id");
        if (upstreamStatus == null) throw validation();
        if ("MEDIA_SUBMISSION_ORDER_CONFLICT".equals(submission.stableErrorCode())) throw stateConflict();
        if (upstreamOrderId != null && submission.upstreamOrderId() != null
                && !submission.upstreamOrderId().equals(upstreamOrderId)) {
            // The signed customer reference locates the row, but an unrelated upstream order
            // must not settle this user's reserved points or publish someone else's receipt.
            throw validation();
        }
        if (Set.of("1002", "1004").contains(upstreamStatus)
                && "NOT_SUBMITTED".equals(submission.submissionState())) {
            // A terminal callback may outrun the synchronous submit response. The GEO outbox
            // retries this 409 after that response has supplied the order id for comparison.
            throw stateConflict();
        }
        if ("1002".equals(upstreamStatus)) {
            List<String> publishedUrls = MediaPublishedLinks.publicLinksFromReceipt(firstText(payload, "return_info"));
            transitionTerminal(submission, "PUBLISHED", "CONFIRMED", upstreamStatus, upstreamOrderId, publishedUrls,
                    null, sourceEventId, now);
        } else if ("1004".equals(upstreamStatus)) {
            transitionTerminal(submission, "REJECTED", "CONFIRMED", upstreamStatus, upstreamOrderId, null,
                    "MEDIA_REJECTED", sourceEventId, now);
        } else if (Set.of("PUBLISHED", "REJECTED", "FAILED").contains(submission.status())) {
            // Progress can arrive after a receipt. A late callback is acknowledged, never a regression.
            return;
        } else if ("1001".equals(upstreamStatus)) {
            if (media.transition(submission.id(), submission.status(), upstreamStatus, "ACCEPTED", "CONFIRMED",
                    upstreamOrderId, null, null, null,
                    upstreamOrderId == null && submission.upstreamOrderId() == null ? null
                            : now.plus(properties.getAcceptedPollDelay()), now) == 1) {
                writeEvent(submission, sourceEventId, "SUBMISSION_ACCEPTED", submission.status(),
                        "ACCEPTED", "PROVIDER", null, now);
                media.updateTaskStage(submission.taskId(), "MEDIA_PROCESSING", now);
            }
        } else if (!Set.of("PUBLISHED", "REJECTED", "FAILED").contains(submission.status())) {
            if (media.transition(submission.id(), submission.status(), upstreamStatus, "RECONCILING",
                    "SUBMITTING".equals(submission.status()) ? "CONFIRMED" : submission.submissionState(),
                    upstreamOrderId, null, null,
                    "MEDIA_UPSTREAM_STATUS_UNKNOWN",
                    upstreamOrderId == null && submission.upstreamOrderId() == null ? null
                            : now.plus(properties.getUnknownPollDelay()), now) == 1) {
                media.updateTaskStage(submission.taskId(), "MEDIA_RECONCILING", now);
            }
        }
    }

    private void transitionTerminal(MediaRecords.Submission submission, String target, String submissionState,
                                    String upstreamStatus, String upstreamOrderId,
                                    List<String> publishedUrls, String error,
                                    UUID sourceEventId, OffsetDateTime now) {
        if (Set.of("PUBLISHED", "REJECTED", "FAILED").contains(submission.status())) return;
        String publishedUrl = publishedUrls == null || publishedUrls.isEmpty() ? null : publishedUrls.getFirst();
        String publishedUrlsJson = publishedUrls == null ? null : MediaPublishedLinks.toJson(json, publishedUrls);
        if (media.transition(submission.id(), submission.status(), upstreamStatus, target, submissionState,
                upstreamOrderId, publishedUrl, publishedUrlsJson, error, null, now) != 1) return;
        settleItem(submission, "PUBLISHED".equals(target), now);
        writeEvent(submission, sourceEventId, "PUBLISHED".equals(target) ? "SUBMISSION_PUBLISHED" : "SUBMISSION_REJECTED",
                submission.status(), target, "PROVIDER", error, now);
        finishBatchIfTerminal(submission.batchId(), now);
    }

    private void settleItem(MediaRecords.Submission submission, boolean success, OffsetDateTime now) {
        if (success) {
            // Keep submission -> account -> reservation/task aggregate -> batch lock order.
            tasks.lockPointAccount(submission.userId()).orElseThrow(this::stateConflict);
            OffsetDateTime accountingAt = OffsetDateTime.ofInstant(accountingClock.instant(), ZoneOffset.UTC);
            if (settleOrReleasePoints(submission, "SETTLE", accountingAt) != 1
                    || media.settleReservationItem(submission.taskId(), submission.chargedPoints(), now) != 1
                    || media.addTaskSettled(submission.taskId(), submission.chargedPoints(), now) != 1) throw stateConflict();
            TaskRecords.PointAccount after = tasks.findPointAccount(submission.userId()).orElseThrow();
            if (tasks.insertLedger(ids.nextId(), submission.userId(), 0, -submission.chargedPoints(),
                    after.availablePoints(), after.frozenPoints(), "TASK_SETTLE", submission.taskId(),
                    "media-settle:" + submission.id(), "MEDIA submission published", accountingAt) != 1
                    || media.updateBatchPoints(submission.batchId(), submission.chargedPoints(), 0, now) != 1) {
                throw stateConflict();
            }
        } else {
            if (settleOrReleasePoints(submission, "RELEASE", now) != 1
                    || media.releaseReservationItem(submission.taskId(), submission.chargedPoints(), now) != 1) throw stateConflict();
            TaskRecords.PointAccount after = tasks.findPointAccount(submission.userId()).orElseThrow();
            if (tasks.insertLedger(ids.nextId(), submission.userId(), submission.chargedPoints(), -submission.chargedPoints(),
                    after.availablePoints(), after.frozenPoints(), "TASK_RELEASE", submission.taskId(),
                    "media-release:" + submission.id(), "MEDIA submission rejected", now) != 1
                    || media.updateBatchPoints(submission.batchId(), 0, submission.chargedPoints(), now) != 1) {
                throw stateConflict();
            }
        }
    }

    private void finishBatchIfTerminal(long batchId, OffsetDateTime now) {
        MediaRecords.Batch batch = media.lockBatch(batchId).orElseThrow();
        if (Set.of("PUBLISHED", "PARTIAL", "REJECTED", "FAILED").contains(batch.status())) return;
        List<MediaRecords.Submission> rows = media.findByTask(batch.taskId());
        if (rows.stream().anyMatch(row -> !Set.of("PUBLISHED", "REJECTED", "FAILED").contains(row.status()))) return;
        long published = rows.stream().filter(row -> "PUBLISHED".equals(row.status())).count();
        String batchStatus = published == rows.size() ? "PUBLISHED" : published == 0 ? "REJECTED" : "PARTIAL";
        String taskStatus = published == rows.size() ? "SUCCEEDED" : published == 0 ? "FAILED" : "PARTIAL";
        String stage = published == rows.size() ? "COMPLETED" : published == 0 ? "FAILED" : "PARTIAL_COMPLETED";
        ObjectNode result = json.createObjectNode().put("submissionBatchId", Long.toString(batch.id()))
                .put("total", rows.size()).put("published", published).put("rejected", rows.size() - published);
        if (media.finishBatch(batch.id(), batchStatus, now) != 1
                || media.finishTask(batch.taskId(), taskStatus, stage,
                published == 0 ? "MEDIA_REJECTED" : null, canonical(result), now) != 1
                || tasks.insertTaskEvent(ids.nextId(), batch.taskId(), batch.userId(), null, null, null, "RUNNING",
                taskStatus, "SUCCEEDED".equals(taskStatus) || "PARTIAL".equals(taskStatus)
                        ? "TASK_COMPLETED" : "TASK_FAILED", "JAVA", published == 0 ? "MEDIA_REJECTED" : null,
                null, "{}", now) != 1) {
            throw stateConflict();
        }
        if (taskNotifications != null) taskNotifications.publishTerminal(batch.taskId(), batch.userId());
    }

    private int settleOrReleasePoints(MediaRecords.Submission submission, String stage, OffsetDateTime now) {
        return observability.observe("java.points.settle-or-release", stage, span -> {
            span.tag("taskIdRef", observability.reference(submission.taskId()));
            span.tag("userIdHash", observability.reference(submission.userId()));
            span.tag("taskType", "MEDIA");
        }, () -> "SETTLE".equals(stage)
                ? tasks.settlePoints(submission.userId(), submission.chargedPoints(), now)
                : tasks.releasePoints(submission.userId(), submission.chargedPoints(), now));
    }

    private void acceptSubmitted(MediaRecords.Submission row, MediaProviderAdapter.SubmitResult result) {
        OffsetDateTime now = now();
        OffsetDateTime nextPoll = now.plus(properties.getAcceptedPollDelay());
        if (media.acceptSubmission(row.id(), result.upstreamOrderId(), result.upstreamOrderNo(),
                nextPoll, now) == 1) {
            writeEvent(row, null, "SUBMISSION_ACCEPTED", "SUBMITTING", "ACCEPTED", "PROVIDER", null, now);
            media.updateTaskStage(row.taskId(), "MEDIA_PROCESSING", now);
            return;
        }
        MediaRecords.Submission current = media.lockSubmission(row.id()).orElseThrow(this::notFound);
        if (Set.of("PUBLISHED", "REJECTED", "FAILED").contains(current.status())) return;
        if (!Set.of("ACCEPTED", "RECONCILING").contains(current.status())
                || !"CONFIRMED".equals(current.submissionState())) {
            throw stateConflict();
        }
        if (current.upstreamOrderId() != null
                && !current.upstreamOrderId().equals(result.upstreamOrderId())) {
            // Two authenticated provider messages disagree about the upstream order. Leave
            // points frozen and stop automatic reconciliation until an operator resolves it.
            if (media.transition(current.id(), current.status(), current.upstreamStatus(), "RECONCILING",
                    "CONFIRMED", current.upstreamOrderId(), null, null,
                    "MEDIA_SUBMISSION_ORDER_CONFLICT", null, now) != 1) throw stateConflict();
            writeEvent(current, null, "SUBMISSION_RESULT_UNKNOWN", current.status(), "RECONCILING",
                    "JAVA", "MEDIA_SUBMISSION_ORDER_CONFLICT", now);
            media.updateTaskStage(current.taskId(), "MEDIA_RECONCILING", now);
            return;
        }
        if (media.hydrateSubmittedOrder(row.id(), result.upstreamOrderId(), result.upstreamOrderNo(),
                nextPoll, now) != 1) throw stateConflict();
    }

    private void recordSubmitFailure(MediaRecords.Submission row, MediaProviderException exception) {
        OffsetDateTime now = now();
        if (exception.uncertain()) {
            if (media.markUnknown(row.id(), "MEDIA_SUBMISSION_UNKNOWN", null, now) == 1) {
                writeEvent(row, null, "SUBMISSION_RESULT_UNKNOWN", "SUBMITTING", "SUBMIT_UNKNOWN",
                        "JAVA", "MEDIA_SUBMISSION_UNKNOWN", now);
                media.updateTaskStage(row.taskId(), "MEDIA_RECONCILING", now);
                return;
            }
            recordCallbackFirstSubmitFailure(row, now);
            return;
        }
        if (media.markSubmitFailed(row.id(), exception.code(), now) != 1) {
            recordCallbackFirstSubmitFailure(row, now);
            return;
        }
        settleItem(row, false, now);
        writeEvent(row, null, "SUBMISSION_FAILED", "SUBMITTING", "FAILED", "JAVA", exception.code(), now);
        finishBatchIfTerminal(row.batchId(), now);
    }

    private void recordCallbackFirstSubmitFailure(MediaRecords.Submission row, OffsetDateTime now) {
        MediaRecords.Submission current = media.lockSubmission(row.id()).orElseThrow(this::notFound);
        if (!Set.of("ACCEPTED", "RECONCILING").contains(current.status())
                || !"CONFIRMED".equals(current.submissionState())
                || current.upstreamOrderId() != null) return;
        // A signed progress callback proves the order may exist, but neither that callback nor
        // the failed submit response supplied an upstream id. Keep frozen points for review.
        if (media.transition(current.id(), current.status(), current.upstreamStatus(), "RECONCILING",
                "CONFIRMED", null, null, null, "MEDIA_SUBMISSION_UNKNOWN", null, now) != 1) {
            throw stateConflict();
        }
        writeEvent(current, null, "SUBMISSION_RESULT_UNKNOWN", current.status(), "RECONCILING",
                "JAVA", "MEDIA_SUBMISSION_UNKNOWN", now);
        media.updateTaskStage(current.taskId(), "MEDIA_RECONCILING", now);
    }

    private void recoverInterruptedSubmission(MediaRecords.Submission row) {
        OffsetDateTime now = now();
        if (media.recoverInterruptedSubmission(row.id(), "MEDIA_SUBMISSION_UNKNOWN", null, now) == 1) {
            writeEvent(row, null, "SUBMISSION_RESULT_UNKNOWN", "SUBMITTING", "SUBMIT_UNKNOWN",
                    "SCHEDULER", "MEDIA_SUBMISSION_UNKNOWN", now);
            media.updateTaskStage(row.taskId(), "MEDIA_RECONCILING", now);
        }
    }
}
