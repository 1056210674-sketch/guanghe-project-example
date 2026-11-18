package com.guanghe.backend.geo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.article.ArticleRecords;
import com.guanghe.backend.article.ArticleService;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.ConversationRecord;
import com.guanghe.backend.conversation.resource.RunResourceService;
import com.guanghe.backend.task.TaskControlService;
import com.guanghe.backend.task.TaskDtos;
import com.guanghe.backend.task.TaskRecords;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class GeoArticleWorkflowService {
    private static final int MAX_BRIEF_LENGTH = 4_000;

    private final GeoTaskMapper geoTasks;
    private final GeoMapper topics;
    private final ConversationMapper conversations;
    private final ArticleService articles;
    private final TaskControlService taskControl;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private RunResourceService resources;

    public GeoArticleWorkflowService(GeoTaskMapper geoTasks, GeoMapper topics,
                                     ConversationMapper conversations, ArticleService articles,
                                     TaskControlService taskControl, SnowflakeIdGenerator ids,
                                     ObjectMapper json) {
        this.geoTasks = geoTasks;
        this.topics = topics;
        this.conversations = conversations;
        this.articles = articles;
        this.taskControl = taskControl;
        this.ids = ids;
        this.json = json;
    }

    @Autowired(required = false)
    void setResourceLookup(RunResourceService resources) { this.resources = resources; }

    @Transactional
    public GeoArticleWorkflowDtos.Response create(AuthenticatedAccount account, long reportId,
                                                   GeoArticleWorkflowDtos.CreateRequest request,
                                                   String idempotencyHeader) {
        long userId = CatalogAccess.requireUser(account);
        UUID idempotencyKey = parseKey(idempotencyHeader);
        geoTasks.lockOwner(userId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        geoTasks.lockReport(reportId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        GeoTaskRecords.Report report = geoTasks.findReport(reportId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!isTerminalReport(report.status())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "监测报告尚未完成，不能创建文章工作流。",
                    Map.of("reason", "GEO_REPORT_NOT_TERMINAL", "reportId", Long.toString(reportId)));
        }
        if (!"geo-synthesis-v1".equals(report.methodVersion()) || report.archivedAt() != null
                || report.sampleSize() == 0) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "报告分析尚未完成或已归档，请先完成有效监测分析再创建文章。",
                    Map.of("reason", "GEO_REPORT_ANALYSIS_UNAVAILABLE"));
        }
        GeoRecords.Topic topic = topics.findTopic(report.topicId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        GeoTaskRecords.Run reportRun = geoTasks.findRun(report.runId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (reportRun.userId() != userId || reportRun.topicId() != report.topicId()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        // Topic ownership/labels may change after monitoring. The report's monitored object is immutable.
        long spaceId = reportRun.spaceId();
        Long productId = reportRun.productId();
        String suggestionId = validateSuggestion(request == null ? null : request.suggestionId(), report);
        Long requestedConversationId = optionalId(request == null ? null : request.conversationId());
        String observationId = request == null ? null : request.observationId();
        if (observationId != null) optionalId(observationId);
        String direction = normalizeDirection(request == null ? null : request.direction());
        String sourceHash = null;
        if (requestedConversationId != null) {
            conversations.findConversation(requestedConversationId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        }
        if (observationId != null) {
            if (requestedConversationId == null) throw validation();
            conversations.lockConversation(requestedConversationId,userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (resources == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            sourceHash = resources.requireOwnedObservationForAction(userId,requestedConversationId,observationId,reportId).sourceHash();
        }
        String requestHash = hash(reportId, suggestionId, spaceId, productId,
                requestedConversationId, observationId, sourceHash, direction);

        Optional<TaskRecords.Draft> replay = taskControl.findGeoArticleDraftByIdempotency(
                userId, idempotencyKey);
        if (replay.isPresent()) {
            // Older releases hashed the derived brief. Their stored source and brief can
            // validate an old request without letting today's labels/AI summary change its key.
            boolean legacyReplay = observationId == null && direction == null
                    && requestHashLegacy(reportId,suggestionId,spaceId,productId,requestedConversationId,
                    read(replay.get().parametersJson()).path("brief").asText("")).equals(replay.get().requestHash());
            if (!requestHash.equals(replay.get().requestHash()) && !legacyReplay) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return response(replay.get(), true);
        }

        String brief = buildBrief(report, reportRun, suggestionId, direction);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Optional<TaskRecords.Draft> sameWorkflow = taskControl.lockGeoArticleDraftByIdentity(
                userId, reportId, suggestionId);
        if (sameWorkflow.isPresent()) {
            TaskRecords.Draft original = sameWorkflow.orElseThrow();
            long articleId = requiredStoredId(read(original.parametersJson()), "articleId");
            // Later ARTICLE stages retain the report but do not necessarily retain the
            // recommendation id. Resolve the article's current stage, not its old TITLE.
            TaskRecords.Draft latest = taskControl.lockLatestArticleDraftByArticle(userId,articleId)
                    .orElse(original);
            JsonNode latestParameters = read(latest.parametersJson());
            if (latest.userId()!=userId || !"ARTICLE".equals(latest.taskType())
                    || requiredStoredId(latestParameters,"articleId")!=articleId
                    || requiredStoredId(latestParameters,"geoReportId")!=reportId
                    || latest.spaceId()!=spaceId || !Objects.equals(latest.productId(),productId)
                    || !Objects.equals(latest.conversationId(),original.conversationId())) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            sameWorkflow = Optional.of(latest);
        }
        Optional<TaskRecords.Draft> recoverableWorkflow = sameWorkflow
                .filter(draft -> isRecoverable(draft, now)
                        || !"TITLE".equals(read(draft.parametersJson()).path("mode").asText("TITLE")));
        if (recoverableWorkflow.isPresent()) {
            TaskRecords.Draft draft = recoverableWorkflow.orElseThrow();
            if (requestedConversationId != null && !Objects.equals(requestedConversationId, draft.conversationId())) {
                throw activeWorkflowConflict(draft);
            }
            JsonNode parameters = read(draft.parametersJson());
            // This entry starts TITLE only. Later stages retain their confirmed plan and
            // paid tasks; the UI continues that article instead of secretly rewriting it.
            if (!"CONFIRMED".equals(draft.status()) && direction != null
                    && "TITLE".equals(parameters.path("mode").asText("TITLE"))
                    && !brief.equals(parameters.path("brief").asText())) {
                TaskDtos.DraftResponse revised = taskControl.reviseGeoArticleDraft(
                        account,draft.id(),idempotencyKey,brief,requestHash);
                return response(revised,reportId,suggestionId,draft.conversationId(),
                        requiredStoredId(parameters,"articleId"),false);
            }
            return response(draft, true);
        }

        // A historical confirmed draft is not a running task and must not reserve the whole
        // account forever. Only work in the destination conversation can conflict here.
        Long destinationConversationId = requestedConversationId != null ? requestedConversationId
                : sameWorkflow.map(TaskRecords.Draft::conversationId).orElse(null);
        if (destinationConversationId != null) {
            Optional<TaskRecords.Draft> active = taskControl.lockActiveArticleDraftInConversation(
                    userId, destinationConversationId, now);
            if (active.isPresent()) throw activeWorkflowConflict(active.get());
        }

        if (sameWorkflow.isPresent()) {
            TaskRecords.Draft expired = sameWorkflow.orElseThrow();
            JsonNode parameters = read(expired.parametersJson());
            long articleId = requiredStoredId(parameters, "articleId");
            long renewalConversationId = requestedConversationId == null
                    ? expired.conversationId() : requestedConversationId;
            TaskDtos.DraftResponse renewed = taskControl.createGeoArticleDraft(
                    userId, renewalConversationId, spaceId, productId, articleId,
                    reportId, brief, idempotencyKey, suggestionId, requestHash);
            return response(renewed, reportId, suggestionId, renewalConversationId, articleId, false);
        }

        long conversationId = requestedConversationId == null ? ids.nextId() : requestedConversationId;
        if (requestedConversationId == null) {
            ConversationRecord conversation = new ConversationRecord(
                    conversationId, userId, "GEO文章创作 · " + frozenTopicName(reportRun),
                    null, null, 1, null, now, now);
            if (conversations.insertConversation(conversation) != 1) {
                throw new IllegalStateException("Unable to create GEO article workflow conversation");
            }
        }
        if (requestedConversationId == null && conversations.updateConversationSelection(conversationId, userId,
                spaceId, productId, now) != 1) {
            throw new IllegalStateException("Unable to create GEO article workflow conversation");
        }
        ArticleRecords.Article article = articles.createForWorkflow(
                userId, spaceId, productId, "");
        TaskDtos.DraftResponse draft = taskControl.createGeoArticleDraft(
                userId, conversationId, spaceId, productId, article.id(), reportId,
                brief, idempotencyKey, suggestionId, requestHash);
        return response(draft, reportId, suggestionId, conversationId, article.id(), false);
    }

    private BusinessException activeWorkflowConflict(TaskRecords.Draft draft) {
        JsonNode parameters = read(draft.parametersJson());
        return new BusinessException(ErrorCode.RESOURCE_IN_USE,
                "已有进行中的文章工作流，请继续当前文章或完成正文确认后再创建。",
                Map.of(
                        "reason", "ARTICLE_WORKFLOW_ACTIVE",
                        "workflowId", Long.toString(draft.id()),
                        "draftId", Long.toString(draft.id()),
                        "reportId", parameters.path("geoReportId").asText(""),
                        "conversationId", Long.toString(draft.conversationId()),
                        "articleId", parameters.path("articleId").asText("")));
    }

    private GeoArticleWorkflowDtos.Response response(TaskRecords.Draft stored, boolean reused) {
        TaskDtos.DraftResponse draft = taskControl.getDraftForWorkflow(stored.id(), stored.userId());
        JsonNode parameters = read(stored.parametersJson());
        return new GeoArticleWorkflowDtos.Response(
                Long.toString(stored.id()),
                parameters.path("geoReportId").asText(null),
                parameters.path("geoRecommendationId").asText(null),
                Long.toString(stored.conversationId()),
                parameters.path("articleId").asText(null),
                Long.toString(stored.id()),
                reused,
                draft);
    }

    private GeoArticleWorkflowDtos.Response response(TaskDtos.DraftResponse draft, long reportId,
                                                      String suggestionId, long conversationId,
                                                      long articleId, boolean reused) {
        return new GeoArticleWorkflowDtos.Response(
                draft.id(), Long.toString(reportId), suggestionId, Long.toString(conversationId),
                Long.toString(articleId), draft.id(), reused, draft);
    }

    private boolean isRecoverable(TaskRecords.Draft draft, OffsetDateTime now) {
        if ("CONFIRMED".equals(draft.status())) return true;
        return ("WAITING_CONFIRMATION".equals(draft.status()) || "EDITING".equals(draft.status()))
                && now.isBefore(draft.expiresAt());
    }

    private long requiredStoredId(JsonNode parameters, String field) {
        try {
            long value = Long.parseLong(parameters.path(field).asText());
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Stored GEO article workflow is invalid", exception);
        }
    }

    private String validateSuggestion(String suggestionId, GeoTaskRecords.Report report) {
        if (suggestionId == null || suggestionId.isBlank()) return null;
        String prefix = report.id() + ":recommendation:";
        if (!suggestionId.startsWith(prefix)) throw validation();
        int ordinal;
        try {
            ordinal = Integer.parseInt(suggestionId.substring(prefix.length()));
        } catch (NumberFormatException exception) {
            throw validation();
        }
        JsonNode recommendations = read(report.synthesisJson()).path("recommendations");
        if (!recommendations.isArray() || ordinal < 1 || ordinal > recommendations.size()
                || !recommendations.path(ordinal - 1).isObject()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return suggestionId;
    }

    private String buildBrief(GeoTaskRecords.Report report, GeoTaskRecords.Run reportRun,
                              String suggestionId, String direction) {
        JsonNode synthesis = read(report.synthesisJson());
        String summary = report.summary() == null || report.summary().isBlank()
                ? "本次监测未提供独立摘要，请以结构化发现为准。" : report.summary().strip();
        StringBuilder brief = new StringBuilder("根据已冻结的 GEO 监测报告，策划一篇品牌或商品内容文章。\n")
                .append("报告来源：").append(report.id()).append("，监测专题（当时名称）：")
                .append(frozenTopicName(reportRun)).append("。\n监测对象（当时名称）：")
                .append(frozenTargetName(reportRun)).append("。\n");
        if (direction != null && !direction.isBlank()) brief.append("用户本次创作方向（意图，不是已核实事实）：")
                .append(direction).append('\n');
        brief.append("报告观察：")
                .append(summary)
                .append('\n');
        appendArray(brief, "内容蓝图", synthesis.path("contentBlueprint"), 5);
        appendArray(brief, "关键发现", synthesis.path("findings"), 5);
        appendArray(brief, "覆盖缺口", synthesis.path("coverageGaps"), 5);
        if (suggestionId == null) {
            appendRecommendations(brief, synthesis.path("recommendations"), 3);
        } else {
            JsonNode recommendation = selectedRecommendation(report, synthesis, suggestionId);
            brief.append("本次优先执行建议：")
                    .append(recommendation.path("title").asText())
                    .append("：")
                    .append(recommendation.path("rationale").asText())
                    .append('\n');
        }
        boolean partial = synthesis.path("missingSamples").isArray()
                && !synthesis.path("missingSamples").isEmpty();
        String scopeNotice = (partial
                ? "范围限定：本次报告存在缺失样本，以下判断仅代表本次监测的平台、问题与采样时间范围，不得把结论写成平台官方排名或全网事实。"
                : "范围限定：以下判断仅代表本次监测的平台、问题与采样时间范围，不得写成平台官方排名或全网事实。")
                + "当前资料与用户补充仅用于本次创作，不冒充当时监测事实。零提及可以写介绍，但不证明内容缺口的因果关系，"
                + "不保证发文后可见度上涨。缺少的参数、销量、价格、认证和竞品结论不得编造。仅准备标题阶段，不自动生成正文或投稿。";
        String body = brief.toString().strip();
        int bodyLimit = MAX_BRIEF_LENGTH - scopeNotice.length() - 1;
        if (body.length() > bodyLimit) body = body.substring(0, Math.max(0, bodyLimit)).stripTrailing();
        return body + "\n" + scopeNotice;
    }

    private String frozenTopicName(GeoTaskRecords.Run run) {
        JsonNode topic = read(run.contextSnapshotJson()).path("authorizedContext").path("topic");
        return Long.toString(run.topicId()).equals(topic.path("id").asText())
                && topic.path("name").isTextual() && !topic.path("name").asText().isBlank()
                ? cut(topic.path("name").asText(),100) : "历史专题名称未保存";
    }

    private String frozenTargetName(GeoTaskRecords.Run run) {
        JsonNode catalog = read(run.contextSnapshotJson()).path("catalogSnapshot");
        JsonNode identity = run.productId() == null ? catalog.path("space").path("brand") : catalog.path("product");
        boolean scope = Long.toString(run.spaceId()).equals(catalog.path("space").path("id").asText())
                && (run.productId() == null || (Long.toString(run.productId()).equals(identity.path("id").asText())
                && Long.toString(run.spaceId()).equals(identity.path("spaceId").asText())));
        return scope && identity.path("name").isTextual() && !identity.path("name").asText().isBlank()
                ? cut(identity.path("name").asText(),200) : "历史对象名称未保存（以原报告的冻结对象为准）";
    }

    private String cut(String value,int max) {
        return value.codePointCount(0,value.length()) <= max ? value : value.substring(0,value.offsetByCodePoints(0,max));
    }

    private String normalizeDirection(String value) {
        if (value == null) return null;
        if (value.length() > 800) throw validation();
        return value.strip();
    }

    private JsonNode selectedRecommendation(GeoTaskRecords.Report report, JsonNode synthesis,
                                            String suggestionId) {
        String prefix = report.id() + ":recommendation:";
        int index = Integer.parseInt(suggestionId.substring(prefix.length())) - 1;
        return synthesis.path("recommendations").path(index);
    }

    private void appendRecommendations(StringBuilder brief, JsonNode recommendations, int limit) {
        if (!recommendations.isArray()) return;
        for (int index = 0; index < Math.min(limit, recommendations.size()); index++) {
            JsonNode recommendation = recommendations.path(index);
            if (!recommendation.isObject()) continue;
            brief.append("优先建议：")
                    .append(recommendation.path("title").asText())
                    .append("：")
                    .append(recommendation.path("rationale").asText())
                    .append('\n');
        }
    }

    private void appendArray(StringBuilder brief, String label, JsonNode values, int limit) {
        if (!values.isArray() || values.isEmpty()) return;
        int start = brief.length();
        brief.append(label).append('：');
        boolean appended = false;
        for (int index = 0; index < Math.min(limit, values.size()); index++) {
            JsonNode value = values.path(index);
            if (!value.isTextual() || value.textValue().isBlank()) continue;
            if (appended) brief.append('；');
            brief.append(value.textValue().strip());
            appended = true;
        }
        if (appended) brief.append('\n');
        else brief.setLength(start);
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored GEO report payload is invalid", exception);
        }
    }

    private String hash(long reportId, String suggestionId, long spaceId, Long productId,
                        Long conversationId, String observationId, String sourceHash, String direction) {
        ObjectNode input=json.createObjectNode().put("version","geo-article-workflow-v2")
                .put("reportId",Long.toString(reportId)).put("suggestionId",suggestionId)
                .put("spaceId",Long.toString(spaceId)).put("productId",productId==null?null:Long.toString(productId))
                .put("conversationId",conversationId==null?null:Long.toString(conversationId))
                .put("observationId",observationId).put("sourceHash",sourceHash).put("direction",direction);
        return sha256(input.toString());
    }

    private String requestHashLegacy(long reportId, String suggestionId, long spaceId, Long productId,
                        Long conversationId, String brief) {
        String canonical = reportId + "|" + Objects.toString(suggestionId, "") + "|" + spaceId + "|"
                + Objects.toString(productId, "") + "|" + Objects.toString(conversationId, "") + "|" + brief;
        return sha256(canonical);
    }

    private String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private UUID parseKey(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw validation();
        }
    }

    private Long optionalId(String value) {
        if (value == null) return null;
        try {
            if (!value.matches("^[1-9][0-9]{0,18}$")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw validation();
        }
    }

    private boolean isTerminalReport(String status) {
        return "SUCCEEDED".equals(status) || "PARTIAL".equals(status);
    }

    private BusinessException validation() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
