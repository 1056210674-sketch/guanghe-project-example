package com.guanghe.backend.article;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.product.ProductService;
import com.guanghe.backend.catalog.product.ProductRecord;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.geo.GeoTaskMapper;
import com.guanghe.backend.geo.GeoTaskRecords;
import com.guanghe.backend.knowledge.KnowledgeDtos;
import com.guanghe.backend.task.TaskRecords;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleServiceTests {
    @Mock private ArticleMapper mapper;
    @Mock private AssetMapper assetMapper;
    @Mock private GeoTaskMapper geoTaskMapper;
    @Mock private BusinessSpaceService spaceService;
    @Mock private ProductService productService;
    @Mock private AuthorizedCatalogContextFactory catalogContextFactory;
    @Mock private SnowflakeIdGenerator ids;
    private ObjectMapper json;
    private ArticleService service;

    @BeforeEach
    void setUp() {
        json = new ObjectMapper().findAndRegisterModules();
        service = new ArticleService(mapper, assetMapper, geoTaskMapper, spaceService, productService,
                catalogContextFactory, ids, json, new ArticleHtmlSanitizer());
    }

    @Test
    void crudSanitizesHtmlDerivesTextAndRequiresLinkedReadyAssets() {
        when(ids.nextId()).thenReturn(101L);
        when(mapper.insert(any())).thenReturn(1);
        when(mapper.findAssets(101, 7)).thenReturn(List.of());
        when(mapper.findWorkflow(101, 7)).thenReturn(Optional.of(workflow("TITLE_INPUT", null, null)));
        ArticleDtos.Response created = service.create(user(), new ArticleDtos.CreateRequest("21", null, "  标题  "));
        assertThat(created.id()).isEqualTo("101");
        assertThat(created.title()).isEqualTo("标题");

        ArticleRecords.Article stored = article(101, 1, "标题", "", "", "[]");
        ArticleRecords.AssetRelation relation = new ArticleRecords.AssetRelation(
                401, 101, 501, 7, 21, "hero", 1, "主图", now());
        when(mapper.lock(101, 7)).thenReturn(Optional.of(stored));
        when(mapper.findAssets(101, 7)).thenReturn(List.of(relation));
        when(mapper.update(any(), any(Integer.class))).thenReturn(1);
        ArticleDtos.UpdateRequest update = new ArticleDtos.UpdateRequest();
        update.setVersion(1);
        update.setBodyHtml("<section data-article-block-id=\"intro\"><p onclick=\"steal()\">安全正文"
                + "<script>alert(1)</script><img src=\"https://evil.invalid/x.png\">"
                + "<span data-asset-id=\"501\">配图</span></p></section>");
        ArticleDtos.Response response = service.update(user(), 101, null, update);
        assertThat(response.bodyHtml()).contains("data-article-block-id=\"intro\"", "data-asset-id=\"501\"")
                .doesNotContain("onclick", "script", "evil.invalid", "<img");
        assertThat(response.bodyText()).contains("安全正文", "配图").doesNotContain("alert(1)");
        assertThat(response.version()).isEqualTo(2);

        ArticleDtos.UpdateRequest unlinked = new ArticleDtos.UpdateRequest();
        unlinked.setVersion(1);
        unlinked.setBodyHtml("<span data-asset-id=\"999\">未授权</span>");
        assertCode(() -> service.update(user(), 101, null, unlinked), ErrorCode.VALIDATION_FAILED);
        org.mockito.Mockito.verifyNoInteractions(catalogContextFactory);
    }

    @Test
    void outlineAssetsAndSnapshotsAreVersionedOwnedAndImmutable() {
        ArticleRecords.Article stored = article(101, 1, "标题", "<p>正文</p>", "正文", "[]");
        when(mapper.lock(101, 7)).thenReturn(Optional.of(stored));
        when(mapper.findWorkflow(101, 7)).thenReturn(
                Optional.of(workflow("TITLE_INPUT", null, null)),
                Optional.of(workflow("TITLE_INPUT", null, null)),
                Optional.of(workflow("READY_TO_SUBMIT", null, null)));
        when(mapper.updateOutline(any(Long.class), any(Long.class), any(Integer.class), any(String.class), any()))
                .thenReturn(1);
        when(mapper.findAssets(101, 7)).thenReturn(List.of());
        ArticleDtos.Response outlined = service.replaceOutline(user(), 101,
                new ArticleDtos.ReplaceOutlineRequest(1, List.of(
                        new ArticleDtos.OutlineItem("intro", 2, " 开场 ", " 摘要 "),
                        new ArticleDtos.OutlineItem("detail", 2, "详情", null))));
        assertThat(outlined.outline()).extracting(ArticleDtos.OutlineResponse::sequence)
                .containsExactly(1, 2);
        assertThat(outlined.version()).isEqualTo(2);

        assertCode(() -> service.replaceOutline(user(), 101,
                new ArticleDtos.ReplaceOutlineRequest(9, List.of())), ErrorCode.RESOURCE_VERSION_CONFLICT);
        assertCode(() -> service.createSnapshot(user(), 101,
                new ArticleDtos.CreateSnapshotRequest(1)), ErrorCode.TASK_STATE_CONFLICT);

        ArticleRecords.Article versionTwo = completedArticle(101, 2, "标题", "<p>正文</p>", "正文",
                "[{\"key\":\"intro\",\"sequence\":1,\"level\":2,\"heading\":\"开场\",\"brief\":null}]");
        ArticleRecords.SubmissionSnapshot existing = new ArticleRecords.SubmissionSnapshot(
                701, 101, 7, 21, null, 2, "标题", "<p>正文</p>", "正文",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                versionTwo.outlineJson(), "[]", now());
        when(mapper.lock(101, 7)).thenReturn(Optional.of(versionTwo));
        when(mapper.findSnapshotByVersion(101, 2, 7)).thenReturn(Optional.of(existing));
        ArticleDtos.SubmissionSnapshotResponse snapshot = service.createSnapshot(user(), 101,
                new ArticleDtos.CreateSnapshotRequest(2));
        assertThat(snapshot.id()).isEqualTo("701");
        assertThat(snapshot.bodyHtml()).isEqualTo("<p>正文</p>");
        verify(mapper, never()).insertSnapshot(any());
    }

    @Test
    void completedBodyRequiresExplicitVersionBoundSubmissionPreferences() {
        ArticleRecords.Article stored = completedArticle(101, 4, "标题", "<p>正文</p>", "正文", "[]");
        when(mapper.lock(101, 7)).thenReturn(Optional.of(stored));
        when(mapper.findWorkflow(101, 7)).thenReturn(
                Optional.of(workflow("BODY_READY", null, null)),
                Optional.of(workflow("READY_TO_SUBMIT", null, null)));
        when(mapper.upsertSubmissionPreference(any())).thenReturn(1);
        when(mapper.confirmBody(anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.findAssets(101, 7)).thenReturn(List.of());

        ArticleDtos.Response confirmed = service.confirmBody(user(), 101,
                new ArticleDtos.ConfirmBodyRequest(4, ArticleDtos.SubmissionGoal.INDUSTRY_MATCH,
                        " 全国 ", 5_000, true));

        assertThat(confirmed.workflowStage()).isEqualTo(ArticleDtos.WorkflowStage.READY_TO_SUBMIT);
        var preference = org.mockito.ArgumentCaptor.forClass(ArticleRecords.SubmissionPreference.class);
        verify(mapper).upsertSubmissionPreference(preference.capture());
        assertThat(preference.getValue().articleVersion()).isEqualTo(4);
        assertThat(preference.getValue().preferredRegion()).isEqualTo("全国");
        assertThat(preference.getValue().requiresLink()).isTrue();
    }

    @Test
    void articleTaskSnapshotAndTitleResultAreStrictAndDoNotPersistProviderPayload() throws Exception {
        ArticleRecords.Article stored = article(101, 3, "标题", "", "", "[]");
        when(mapper.find(101, 7)).thenReturn(Optional.of(stored));
        when(mapper.findWorkflow(101, 7)).thenReturn(Optional.of(workflow("TITLE_INPUT", null, null)));
        when(catalogContextFactory.create(7, 21L, null)).thenReturn(new AuthorizedCatalogContextFactory.Context(
                new InternalRunContextDtos.Space("21", "空间",
                        new InternalRunContextDtos.Brand("小行星咖啡店", List.of(), null, List.of(), List.of(),
                                List.of(), List.of()), null), null, List.of()));
        ObjectNode parameters = json.createObjectNode();
        parameters.put("mode", "TITLE");
        parameters.put("articleId", "101");
        parameters.put("articleVersion", 3);
        parameters.put("brief", "写三个标题");
        parameters.putNull("selectedTitle");
        parameters.putArray("confirmedOutline");
        parameters.putNull("confirmedPlan");
        parameters.putNull("rewriteInstruction");
        parameters.putArray("rewriteBlockIds");
        parameters.put("targetLength", "SHORT");
        parameters.put("tone", "PROFESSIONAL");
        parameters.putNull("targetPlatform");
        parameters.putArray("sourceAssetIds");
        parameters.putNull("geoReportId");
        ArticleService.PreparedTask prepared = service.prepareTask(7, 21, null, parameters);
        assertThat(prepared.mode()).isEqualTo("TITLE");
        assertThat(prepared.parameters().path("geoRecommendationId").isNull()).isTrue();
        assertThat(prepared.snapshot().toString()).doesNotContain("userId", "objectKey", "providerUrl");

        TaskRecords.Task task = task(prepared.snapshot().toString());
        ObjectNode inline = articleResult("TITLE");
        inline.putArray("titleCandidates").add("标题一").add("标题二").add("标题三").add("标题四").add("标题五");
        ObjectNode artifact = json.createObjectNode();
        artifact.put("artifactType", "ARTICLE_CONTENT");
        artifact.put("unitKey", "article:title");
        artifact.put("schemaVersion", 2);
        artifact.set("inlineData", inline);
        ObjectNode topUsage = json.createObjectNode();
        topUsage.put("inputTokens", 20).put("outputTokens", 10).put("images", 0).put("videoSeconds", 0);
        topUsage.putNull("providerCost").putNull("currency");
        ArticleService.ValidatedResult valid = service.validateResult(
                task, "DEEPSEEK", "deepseek-v4-flash", artifact, topUsage, 123);
        assertThat(valid.publicResult().path("titleCandidates").size()).isEqualTo(5);
        assertThat(valid.publicResult().toString()).doesNotContain("brief", "warnings", "promptVersion");

        for (String bundle : java.util.List.of("agent-bundle-v1.15.0", "agent-bundle-v1.16.0", "agent-bundle-v1.17.0",
                "agent-bundle-v1.18.0", "agent-bundle-v1.19.0", "agent-bundle-v1.20.0", "agent-bundle-v1.21.0", "agent-bundle-v1.22.0", "agent-bundle-v1.23.0", "agent-bundle-v1.24.0", "agent-bundle-v1.25.0")) {
            inline.put("promptVersion", bundle);
            assertThat(service.validateResult(task, "DEEPSEEK", "deepseek-v4-flash", artifact, topUsage, 123)
                    .publicResult().path("titleCandidates").size()).isEqualTo(5);
        }
        inline.put("promptVersion", "agent-bundle-v999.0.0");
        assertCode(() -> service.validateResult(task, "DEEPSEEK", "deepseek-v4-flash",
                artifact, topUsage, 123), ErrorCode.VALIDATION_FAILED);
        inline.put("promptVersion", "agent-bundle-v1.16.0");

        inline.path("metadata").deepCopy();
        ((ObjectNode) inline.path("metadata")).put("persisted", true);
        assertCode(() -> service.validateResult(task, "DEEPSEEK", "deepseek-v4-flash",
                artifact, topUsage, 123), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void currentAndNewPromptBundlesCompleteEveryArticleGenerationStageButUnknownVersionIsRejected() throws Exception {
        JsonNode outline = json.readTree("""
                [{"key":"intro","sequence":1,"level":2,"heading":"开场","brief":"介绍商品"},
                 {"key":"detail","sequence":2,"level":2,"heading":"细节","brief":"说明使用场景"}]
                """);
        for (String mode : List.of("TITLE", "OUTLINE", "BODY")) {
            ObjectNode parameters = json.createObjectNode().put("mode", mode).put("articleId", "101")
                    .put("articleVersion", 3).put("selectedTitle", "商品介绍")
                    .put("tone", "PROFESSIONAL").put("targetLength", "MEDIUM").putNull("targetPlatform");
            parameters.set("confirmedOutline", outline);
            parameters.putArray("rewriteBlockIds");
            ObjectNode snapshot = json.createObjectNode();
            snapshot.set("parameters", parameters);
            TaskRecords.Task current = task(snapshot.toString());
            ObjectNode inline = articleResult(mode);
            if ("TITLE".equals(mode)) {
                inline.withArray("titleCandidates").add("标题一").add("标题二").add("标题三").add("标题四").add("标题五");
            } else {
                inline.put("title", "商品介绍");
                inline.set("outline", outline);
                if ("OUTLINE".equals(mode)) {
                    ObjectNode plan = inline.putObject("plan").put("summary", "介绍商品")
                            .put("coreMessage", "客观说明使用场景").put("targetAudience", "商品使用者")
                            .put("tone", "PROFESSIONAL").put("targetLength", "MEDIUM").putNull("targetPlatform");
                    plan.set("sections", outline);
                    plan.putArray("riskNotes");
                } else {
                    ObjectNode body = inline.putObject("body").put("markdown", "商品介绍与使用细节");
                    var blocks = body.putArray("blocks");
                    blocks.addObject().put("key", "intro").put("heading", "开场").put("markdown", "介绍商品。");
                    blocks.addObject().put("key", "detail").put("heading", "细节").put("markdown", """
                            **先确定使用对象**。再说明使用场景。

                            | 需求 | 判断维度 |
                            | --- | --- |
                            | 日常使用 | 操作是否方便 |
                            | 携带使用 | 收纳是否合适 |
                            """);
                }
            }
            ObjectNode artifact = json.createObjectNode().put("artifactType", "ARTICLE_CONTENT")
                    .put("unitKey", "article:" + mode.toLowerCase(java.util.Locale.ROOT)).put("schemaVersion", 2);
            artifact.set("inlineData", inline);
            ObjectNode usage = json.createObjectNode().put("inputTokens", 20).put("outputTokens", 10)
                    .put("images", 0).put("videoSeconds", 0).putNull("providerCost").putNull("currency");
            for (String bundle : List.of("agent-bundle-v1.26.0", "agent-bundle-v1.27.0",
                    "agent-bundle-v1.27.1", "agent-bundle-v1.28.0", "agent-bundle-v1.29.0",
                    "agent-bundle-v1.30.0", "agent-bundle-v1.31.0", "agent-bundle-v1.32.0",
                    "agent-bundle-v1.33.0", "agent-bundle-v1.33.1", "agent-bundle-v1.33.2", "agent-bundle-v1.33.3", "agent-bundle-v1.33.4", "agent-bundle-v1.33.5", "agent-bundle-v1.33.6", "agent-bundle-v1.33.7", "agent-bundle-v1.33.8", "agent-bundle-v1.33.9", "agent-bundle-v1.33.10", "agent-bundle-v1.33.11", "agent-bundle-v1.33.12", "agent-bundle-v1.33.13", "agent-bundle-v1.46.3", "agent-bundle-v1.46.4", "agent-bundle-v1.47.0", "agent-bundle-v1.48.0", "agent-bundle-v1.48.1", "agent-bundle-v1.48.2", "agent-bundle-v1.48.3", "agent-bundle-v1.49.0", "agent-bundle-v1.50.0", "agent-bundle-v1.51.0", "agent-bundle-v1.52.0", "agent-bundle-v1.53.0", "agent-bundle-v1.54.0")) {
                inline.put("promptVersion", bundle);
                ArticleService.ValidatedResult valid = service.validateResult(
                        current, "DEEPSEEK", "deepseek-v4-flash", artifact, usage, 123);
                assertThat(valid.mode()).isEqualTo(mode);
                if ("BODY".equals(mode)) {
                    assertThat(valid.bodyHtml()).contains("<table>", "<thead>", "<tbody>",
                            "<th>需求</th>", "<td>日常使用</td>", "<td>收纳是否合适</td>",
                            "<strong>先确定使用对象</strong>。再说明使用场景。")
                            .doesNotContain("| --- |", "| 需求 |", "**先确定使用对象**");
                }
            }
            for (String unknownBundle : List.of("agent-bundle-v1.46.5", "agent-bundle-v1.48.4", "agent-bundle-v1.50.1", "agent-bundle-v999.0.0")) {
                inline.put("promptVersion", unknownBundle);
                assertCode(() -> service.validateResult(current, "DEEPSEEK", "deepseek-v4-flash", artifact, usage, 123),
                        ErrorCode.VALIDATION_FAILED);
            }
        }
        verify(mapper, never()).insert(any());
    }

    @Test
    void chatArticleProposalCreatesWorkspaceAndNormalizesExistingTitleTaskContract() {
        when(ids.nextId()).thenReturn(901L);
        when(mapper.insert(any())).thenReturn(1);
        ArticleRecords.Article created = new ArticleRecords.Article(901, 7, 21, 31L,
                "咖啡好喝又健康", "", "", "[]", "DRAFT", null, 1, now(), now());
        when(mapper.find(901, 7)).thenReturn(Optional.of(created));
        when(mapper.findWorkflow(901, 7)).thenReturn(Optional.of(workflow("TITLE_INPUT", null, null)));
        when(productService.requireProduct(7, 31)).thenReturn(new ProductRecord(
                31, 7, 21, "美式咖啡", "美式咖啡", null, null,
                "[]", "{}", "[]", "[]", "[]", null, 1, now(), now(), 0));
        when(catalogContextFactory.create(7, 21L, 31L)).thenReturn(new AuthorizedCatalogContextFactory.Context(
                new InternalRunContextDtos.Space("21", "抖店",
                        new InternalRunContextDtos.Brand("小行星咖啡店", List.of(), null, List.of(), List.of(),
                                List.of(), List.of()), null),
                new InternalRunContextDtos.Product("31", "21", "美式咖啡", List.of("小行星招牌美式"), null, null,
                        List.of(), Map.of(), List.of(), List.of(), List.of(), null), List.of()));
        ObjectNode proposal = json.createObjectNode();
        proposal.put("requestText", "基于当前商品创建一篇文章，主题是咖啡好喝又健康");
        proposal.put("mode", "AGENT_ASSISTED");
        proposal.put("agentSummary", "围绕健康饮用场景生成文章标题");
        proposal.put("topic", "咖啡好喝又健康");
        proposal.put("tone", "亲切自然");
        proposal.put("language", "zh-CN");
        proposal.put("targetLength", 1200);

        ArticleService.PreparedTask prepared = service.prepareChatTitleTask(7, 21, 31L, proposal);

        ArgumentCaptor<ArticleRecords.Article> article = ArgumentCaptor.forClass(ArticleRecords.Article.class);
        verify(mapper).insert(article.capture());
        assertThat(article.getValue().title()).isEqualTo("咖啡好喝又健康");
        assertThat(article.getValue().status()).isEqualTo("DRAFT");
        assertThat(prepared.mode()).isEqualTo("TITLE");
        assertThat(prepared.parameters().path("articleId").textValue()).isEqualTo("901");
        assertThat(prepared.parameters().path("targetLength").textValue()).isEqualTo("MEDIUM");
        assertThat(prepared.parameters().path("tone").textValue()).isEqualTo("NATURAL");
        assertThat(prepared.snapshot().path("authorizedContext").path("article").path("id").textValue())
                .isEqualTo("901");

        org.mockito.Mockito.clearInvocations(mapper);
        proposal.put("topic", "换一个文章角度");
        ArticleService.PreparedTask revision = service.prepareChatTitleRevision(7, 21, 31L,
                proposal, prepared.parameters());
        assertThat(revision.parameters().path("articleId").asText()).isEqualTo("901");
        assertThat(revision.parameters().path("articleVersion").asInt()).isEqualTo(1);
        assertThat(revision.parameters().path("brief").asText()).isEqualTo("换一个文章角度");
        verify(mapper, never()).insert(any());
        ObjectNode stale = prepared.parameters().deepCopy();
        stale.put("articleVersion", 999);
        assertCode(() -> service.prepareChatTitleRevision(7, 21, 31L, proposal, stale),
                ErrorCode.RESOURCE_VERSION_CONFLICT);
    }

    @Test
    void assistedTitleCandidatesCannotSupplyArticleIdentityExecutionStateOrPrice() {
        ObjectNode candidate = json.createObjectNode().put("mode", "AGENT_ASSISTED")
                .put("requestText", "准备商品图片和文章标题")
                .put("agentSummary", "只准备文章标题阶段")
                .put("topic", "办公室饮水");
        for (String field : List.of("articleId", "articleVersion", "userId", "spaceId", "brief",
                "selectedTitle", "confirmedPlan", "bodyHtml", "quotedPoints", "confirmationToken")) {
            ObjectNode forged = candidate.deepCopy().put(field, "999");
            assertCode(() -> service.prepareChatTitleTask(7, 21, 31L, forged), ErrorCode.VALIDATION_FAILED);
        }
        verify(mapper, never()).insert(any());
        verify(ids, never()).nextId();
    }

    @Test
    void invalidEditorialOptionsAreRejectedBeforeCreatingManualTitleWorkspace() {
        ObjectNode candidate = json.createObjectNode().put("mode", "AGENT_ASSISTED")
                .put("requestText", "给咖啡店写一篇文章").put("agentSummary", "准备标题")
                .put("topic", "小行星咖啡店");
        for (JsonNode length : List.<JsonNode>of(json.valueToTree(99), json.valueToTree(20_001),
                json.valueToTree("1200"), json.valueToTree(true), json.createObjectNode())) {
            ObjectNode invalid = candidate.deepCopy();
            invalid.set("targetLength", length);
            assertCode(() -> service.prepareChatTitleTask(7, 21, null, invalid), ErrorCode.VALIDATION_FAILED);
        }
        candidate.set("tone", json.createArrayNode().add("NATURAL"));
        assertCode(() -> service.prepareChatTitleTask(7, 21, null, candidate), ErrorCode.VALIDATION_FAILED);
        org.mockito.Mockito.verifyNoInteractions(ids, mapper, geoTaskMapper, spaceService, productService,
                catalogContextFactory, assetMapper);
    }

    @Test
    void chatArticlePreparationRejectionsCannotPoisonOuterRunFailureTransaction() throws Exception {
        var fresh = ArticleService.class.getMethod("prepareChatTitleTask", long.class, long.class,
                Long.class, com.fasterxml.jackson.databind.JsonNode.class);
        var revision = ArticleService.class.getMethod("prepareChatTitleRevision", long.class, long.class,
                Long.class, com.fasterxml.jackson.databind.JsonNode.class, com.fasterxml.jackson.databind.JsonNode.class);
        for (var method : List.of(fresh, revision)) {
            assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation())
                    .isEqualTo(org.springframework.transaction.annotation.Propagation.NESTED);
        }
    }

    @Test
    void titleConfirmationUsesExactBatchVersionAndAdvancesToPlanReady() {
        ArticleRecords.Article before = article(101, 3, "候选标题", "", "", "[]");
        ArticleRecords.Article after = article(101, 4, "标题二", "", "", "[]");
        ArticleRecords.TitleCandidateBatch batch = new ArticleRecords.TitleCandidateBatch(
                601, 101, 7, 21, 1, 3,
                "[\"标题一\",\"标题二\",\"标题三\",\"标题四\",\"标题五\"]", 801, now());
        ArticleRecords.Workflow awaiting = new ArticleRecords.Workflow(101, 7,
                "TITLE_AWAITING_CONFIRMATION", 1, null, null, null, null, null, null, null);
        ArticleRecords.Workflow ready = new ArticleRecords.Workflow(101, 7, "PLAN_READY", 1,
                "标题二", "SELECTED", 1, 2, now(), null, null);
        when(mapper.lock(101, 7)).thenReturn(Optional.of(before));
        when(mapper.find(101, 7)).thenReturn(Optional.of(after));
        when(mapper.findWorkflow(101, 7)).thenReturn(Optional.of(awaiting), Optional.of(ready));
        when(mapper.findTitleBatch(101, 7, 1)).thenReturn(Optional.of(batch));
        when(mapper.findAssets(101, 7)).thenReturn(List.of());
        when(mapper.confirmTitle(anyLong(), anyLong(), anyInt(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(1);

        ArticleDtos.Response response = service.confirmTitle(user(), 101,
                new ArticleDtos.ConfirmTitleRequest(3, 1, "标题二", ArticleDtos.TitleSource.SELECTED, 2));

        assertThat(response.workflowStage()).isEqualTo(ArticleDtos.WorkflowStage.PLAN_READY);
        assertThat(response.confirmedTitle().value()).isEqualTo("标题二");
        assertThat(response.confirmedTitle().candidateIndex()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"TITLE,false", "OUTLINE,false", "BODY,false", "TITLE,true", "OUTLINE,true", "BODY,true"})
    void singleStageAiGenerationRequiresPublicIdentityBeforeProducingAnyPaidDraft(
            String mode, boolean configured) throws Exception {
        String plan = """
                {"summary":"介绍门店","coreMessage":"只用菜单事实","targetAudience":"咖啡爱好者",
                 "sections":[{"key":"intro","sequence":1,"level":2,"heading":"门店介绍","brief":"公开资料"},
                 {"key":"menu","sequence":2,"level":2,"heading":"菜单","brief":"仅咖啡"}],
                 "tone":"PROFESSIONAL","targetLength":"MEDIUM","targetPlatform":null,"riskNotes":[]}
                """;
        boolean body = "BODY".equals(mode);
        ArticleRecords.Article stored = article(101, 3, "小行星咖啡店", "", "",
                body ? json.readTree(plan).path("sections").toString() : "[]");
        when(mapper.find(101, 7)).thenReturn(Optional.of(stored));
        String stage = "TITLE".equals(mode) ? "TITLE_INPUT"
                : "OUTLINE".equals(mode) ? "PLAN_READY" : "BODY_READY_TO_GENERATE";
        when(mapper.findWorkflow(101, 7)).thenReturn(Optional.of(new ArticleRecords.Workflow(
                101, 7, stage, null, "TITLE".equals(mode) ? null : stored.title(),
                "TITLE".equals(mode) ? null : "SELECTED", null, null, null,
                body ? 1 : null, body ? 1 : null)));
        if (body) when(mapper.findPlanRevision(101, 7, 1)).thenReturn(Optional.of(
                new ArticleRecords.CreativePlanRevision(701, 101, 7, 21, 1, 2, plan,
                        "GENERATED", 802L, now(), now(), now())));
        when(catalogContextFactory.create(7, 21L, null)).thenReturn(configured
                ? fullArticleCatalog("小行星咖啡店")
                : new AuthorizedCatalogContextFactory.Context(null, null, List.of()));
        ObjectNode parameters = json.createObjectNode().put("mode", mode).put("articleId", "101")
                .put("articleVersion", 3).put("brief", "帮我介绍咖啡店")
                .put("tone", "PROFESSIONAL").put("targetLength", "MEDIUM");
        if ("TITLE".equals(mode)) parameters.putNull("selectedTitle");
        else parameters.put("selectedTitle", stored.title());
        parameters.set("confirmedOutline", body ? json.readTree(plan).path("sections") : json.createArrayNode());
        if (body) parameters.set("confirmedPlan", json.readTree(plan));
        else parameters.putNull("confirmedPlan");
        parameters.putNull("rewriteInstruction").putNull("targetPlatform").putNull("geoReportId");
        parameters.putArray("rewriteBlockIds"); parameters.putArray("sourceAssetIds");

        if (configured) {
            ArticleService.PreparedTask result = service.prepareTask(7, 21, null, parameters);
            assertThat(result.mode()).isEqualTo(mode);
            assertThat(result.snapshot().at("/authorizedContext/space/brand/name").asText())
                    .isEqualTo("小行星咖啡店");
            assertThat(result.snapshot().at("/authorizedContext/sourceAssets")).isEqualTo(json.createArrayNode());
        } else {
            assertThatThrownBy(() -> service.prepareTask(7, 21, null, parameters))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(error.errorCode().status().value()).isEqualTo(422);
                        assertThat(error.details()).containsEntry("field", "geoTarget");
                    });
        }
        // All calls in this path are read-only; no article, draft, task or funds can be created.
        org.mockito.Mockito.verifyNoInteractions(assetMapper, ids, geoTaskMapper);
        verify(mapper, never()).insert(any());
        verify(mapper, never()).update(any(), anyInt());
        verify(mapper, never()).updateWorkflowStage(anyLong(), anyLong(), anyInt(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"unauthorized", "wrong-action", "consumed", "other-user", "other-project", "not-confirmation", "empty-brief", "oversized-brief"})
    void invalidFrozenContinuationCannotMaterializeAWorkerRequest(String invalid) {
        when(mapper.lock(101,7)).thenReturn(Optional.of(article(101,3,"待生成","","","[]")));
        when(mapper.findWorkflow(101,7)).thenReturn(Optional.of(workflow("TITLE_INPUT",null,null)));
        ObjectNode frozen=json.createObjectNode().put("userId","7").put("spaceId","21");
        frozen.putObject("editorial").put("requestText","认可");
        ObjectNode continuation=frozen.putObject("authorizedContext").putObject("articleContinuation")
                .put("authorized",true).put("action","article.generate").put("submissionState","NOT_ATTEMPTED")
                .put("projectId","21").put("requestText","广州咖啡怎么选：按已认可的价格、口味、门店结构写一篇文章。");
        switch(invalid) {
            case "unauthorized" -> continuation.put("authorized",false);
            case "wrong-action" -> continuation.put("action","geo.monitor");
            case "consumed" -> continuation.put("submissionState","ALREADY_STARTED");
            case "other-user" -> frozen.put("userId","8");
            case "other-project" -> continuation.put("projectId","22");
            case "not-confirmation" -> ((ObjectNode)frozen.path("editorial")).put("requestText","先别写");
            case "empty-brief" -> continuation.put("requestText","  ");
            case "oversized-brief" -> continuation.put("requestText","文".repeat(4001));
            default -> throw new AssertionError(invalid);
        }
        assertThatThrownBy(()->service.prepareFullStage(7,101,3,"TITLE",frozen)).isInstanceOf(BusinessException.class);
        verify(mapper,never()).find(anyLong(),anyLong());
        org.mockito.Mockito.verifyNoInteractions(catalogContextFactory,assetMapper,geoTaskMapper,ids);
    }

    @ParameterizedTest
    @CsvSource({"authority", "other-user", "other-project", "current-overwritten", "base-overwritten", "prefix-overwritten", "too-long"})
    void invalidFrozenWritingReferenceCannotOverrideCurrentUserOrScope(String invalid) {
        when(mapper.lock(101,7)).thenReturn(Optional.of(article(101,3,"待生成","","","[]")));
        when(mapper.findWorkflow(101,7)).thenReturn(Optional.of(workflow("TITLE_INPUT",null,null)));
        ObjectNode frozen=json.createObjectNode().put("userId","7").put("spaceId","21");
        frozen.putObject("editorial").put("requestText","写一篇匿名科普，不提品牌");
        ObjectNode reference=frozen.putObject("authorizedContext").putObject("articleWritingReference")
                .put("referenceOnly",true).put("projectId","21")
                .put("originalRequestText","写一篇匿名科普，不提品牌").put("baseRequestText","写一篇匿名科普，不提品牌")
                .put("requestText","写一篇匿名科普，不提品牌\n\n【写作参考／非授权】旧方向");
        switch(invalid) {
            case "authority" -> reference.put("referenceOnly",false);
            case "other-user" -> frozen.put("userId","8");
            case "other-project" -> reference.put("projectId","22");
            case "current-overwritten" -> reference.put("originalRequestText","推广品牌");
            case "base-overwritten" -> reference.put("baseRequestText","推广品牌");
            case "prefix-overwritten" -> reference.put("requestText","推广品牌");
            case "too-long" -> reference.put("requestText","甲".repeat(4001));
            default -> throw new AssertionError(invalid);
        }
        assertThatThrownBy(()->service.prepareFullStage(7,101,3,"TITLE",frozen)).isInstanceOf(BusinessException.class);
        verify(mapper,never()).find(anyLong(),anyLong());
    }

    @ParameterizedTest
    @CsvSource({"TITLE,false,false,false", "OUTLINE,false,false,false", "BODY,false,false,false", "TITLE,true,false,false", "OUTLINE,true,false,false", "BODY,true,false,false",
            "TITLE,false,true,false", "OUTLINE,false,true,false", "BODY,false,true,false", "TITLE,true,true,false", "OUTLINE,true,true,false", "BODY,true,true,false",
            "TITLE,false,false,true", "OUTLINE,false,false,true", "BODY,false,false,true", "TITLE,true,false,true", "OUTLINE,true,false,true", "BODY,true,false,true",
            "TITLE,false,true,true", "OUTLINE,false,true,true", "BODY,false,true,true", "TITLE,true,true,true", "OUTLINE,true,true,true", "BODY,true,true,true"})
    void everyFullStageKeepsCompleteFrozenWorkerContextWithOrWithoutExplicitReport(
            String mode, boolean withReport, boolean withContinuation, boolean withReference) throws Exception {
        var originalCatalog = fullArticleCatalog("小行星咖啡店");
        when(catalogContextFactory.create(7, 21L, null)).thenReturn(originalCatalog);
        ObjectNode hints = json.createObjectNode().put("mode", "AGENT_ASSISTED")
                .put("requestText", withContinuation ? "认可" : "帮小行星咖啡店写一篇客观介绍")
                .put("agentSummary", "只使用提供的资料").put("topic", "咖啡店介绍")
                .put("tone", "PROFESSIONAL").put("targetLength", "MEDIUM");
        GeoTaskRecords.Report originalReport = null;
        if (withReport) {
            hints.put("geoReportId", "41");
            originalReport = new GeoTaskRecords.Report(41, 51, 61, 7, 71, "SUCCEEDED",
                    "{\"findings\":[\"已保存的观察\"],\"coverageGaps\":[\"样本范围有限\"],"
                            + "\"recommendations\":[{\"priority\":\"P1\",\"title\":\"补充真实门店资料\","
                            + "\"rationale\":\"仅使用可核验事实\"}],\"contentBlueprint\":[\"介绍菜单\"],"
                            + "\"missingSamples\":[]}", "{}", "原报告摘要", "geo-synthesis-v1", 3,
                    now(), now(), 3, 0);
            when(geoTaskMapper.findReport(41, 7)).thenReturn(Optional.of(originalReport));
            when(geoTaskMapper.findRun(51, 7)).thenReturn(Optional.of(new GeoTaskRecords.Run(
                    51, 61, 7, 21, null, 71, "SUCCEEDED", "STANDARD", 3, 3, 0, 0,
                    "{}", "geo-metric-v1", "geo-synthesis-v1", now(), now(), now(), now(), 3, 3, 0)));
            when(mapper.findGeoReportId(101, 7)).thenReturn(Optional.of(41L));
        }
        ObjectNode frozen = (ObjectNode) json.readTree(
                service.prepareFullArticleBrief(7, 21, null, hints).canonicalJson());
        assertThat(frozen.at("/authorizedContext/sourceAssets")).isEqualTo(json.createArrayNode());
        var knowledge = new KnowledgeDtos.KnowledgeContext(List.of(new KnowledgeDtos.ContextDocument(
                "501", 1, "门店菜单", "a".repeat(64), "PASTED_TEXT", null,
                List.of(new KnowledgeDtos.Paragraph(1, null, "只售咖啡")))), List.of(),
                new KnowledgeDtos.BusinessHistory("21", 0, 0, 0, now()), false, 1, 4);
        ((ObjectNode) frozen.path("authorizedContext")).set("knowledge", json.valueToTree(knowledge));
        String approvedRequest="原始需求：帮小行星咖啡店写一篇客观介绍。已认可标题：广州咖啡怎么选；结构：价格、口味、门店。";
        if(withContinuation) ((ObjectNode) frozen.path("authorizedContext")).putObject("articleContinuation")
                .put("sourceMessageId","91").put("offerMessageId","92").put("currentMessageId","93")
                .put("authorized",true).put("action","article.generate").put("submissionState","NOT_ATTEMPTED")
                .put("projectId","21").put("requestText",approvedRequest);
        String baseRequest=withContinuation?approvedRequest:hints.path("requestText").asText();
        String expandedRequest=baseRequest+"\n\n【写作参考／非授权】当前USER优先。\nASSISTANT 消息 82：旧稿可参考，不能当品牌事实。";
        if(withReference) ((ObjectNode) frozen.path("authorizedContext")).putObject("articleWritingReference")
                .put("referenceOnly",true).put("projectId","21").put("currentMessageId","93").put("conversationId","31")
                .put("originalRequestText",hints.path("requestText").asText()).put("baseRequestText",baseRequest)
                .put("requestText",expandedRequest).putArray("sourceMessages").addObject()
                .put("messageId","82").put("role","ASSISTANT").put("text","旧稿可参考，不能当品牌事实。").put("truncated",false);
        String before = frozen.toString();
        // Later catalog/report edits must not replace the original article's frozen facts.
        when(catalogContextFactory.create(7, 21L, null)).thenReturn(fullArticleCatalog("另一个品牌"));
        if (withReport) {
            when(geoTaskMapper.findReport(41, 7)).thenReturn(Optional.of(new GeoTaskRecords.Report(
                    41, 51, 61, 7, 71, "SUCCEEDED", "{}", "{}", "后来改写的摘要",
                    "geo-synthesis-v1", 3, now(), now(), 3, 0)));
        }
        String plan = """
                {"summary":"介绍门店","coreMessage":"只用菜单事实","targetAudience":"咖啡爱好者",
                 "sections":[{"key":"intro","sequence":1,"level":2,"heading":"门店介绍","brief":"公开资料"},
                 {"key":"menu","sequence":2,"level":2,"heading":"菜单","brief":"仅咖啡"}],
                 "tone":"PROFESSIONAL","targetLength":"MEDIUM","targetPlatform":null,"riskNotes":[]}
                """;
        String outline = "BODY".equals(mode) ? json.readTree(plan).path("sections").toString() : "[]";
        ArticleRecords.Article stored = article(101, 3, "小行星咖啡店", "", "", outline);
        when(mapper.lock(101, 7)).thenReturn(Optional.of(stored));
        when(mapper.find(101, 7)).thenReturn(Optional.of(stored));
        String stage = "TITLE".equals(mode) ? "TITLE_INPUT"
                : "OUTLINE".equals(mode) ? "PLAN_READY" : "BODY_READY_TO_GENERATE";
        when(mapper.findWorkflow(101, 7)).thenReturn(Optional.of(new ArticleRecords.Workflow(
                101, 7, stage, null, "TITLE".equals(mode) ? null : stored.title(),
                "TITLE".equals(mode) ? null : "AUTO_POLICY", null, null, null,
                "BODY".equals(mode) ? 1 : null, "BODY".equals(mode) ? 1 : null)));
        if ("BODY".equals(mode)) {
            when(mapper.findPlanRevision(101, 7, 1)).thenReturn(Optional.of(
                    new ArticleRecords.CreativePlanRevision(701, 101, 7, 21, 1, 2, plan,
                            "GENERATED", 802L, null, now(), now())));
        }

        ArticleService.PreparedTask prepared = service.prepareFullStage(7, 101, 3, mode, frozen);

        JsonNode authorized = prepared.snapshot().path("authorizedContext");
        assertThat(authorized.has("articleContinuation")).isFalse();
        assertThat(authorized.has("articleWritingReference")).isFalse();
        if(withContinuation) assertThat(frozen.at("/authorizedContext/articleContinuation/offerMessageId").asText()).isEqualTo("92");
        assertThat(prepared.parameters().path("brief").asText()).isEqualTo(withReference?expandedRequest:baseRequest);
        assertThat(frozen.at("/editorial/requestText").asText()).isEqualTo(withContinuation?"认可":"帮小行星咖啡店写一篇客观介绍");
        var keys = new java.util.HashSet<String>();
        authorized.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("space", "product", "article", "sourceAssets", "geoReport", "knowledge");
        assertThat(authorized.path("sourceAssets")).isEqualTo(json.createArrayNode());
        assertThat(prepared.parameters().path("sourceAssetIds")).isEqualTo(json.createArrayNode());
        assertThat(authorized.path("space")).isEqualTo(frozen.at("/authorizedContext/space"));
        assertThat(authorized.path("space").path("brand").path("name").asText()).isEqualTo("小行星咖啡店");
        assertThat(authorized.path("product").isNull()).isTrue();
        assertThat(authorized.path("knowledge")).isEqualTo(frozen.at("/authorizedContext/knowledge"));
        assertThat(authorized.path("geoReport")).isEqualTo(frozen.at("/authorizedContext/geoReport"));
        assertThat(authorized.path("article").path("id").asText()).isEqualTo("101");
        assertThat(authorized.path("article").path("version").asInt()).isEqualTo(3);
        assertThat(prepared.parameters().path("mode").asText()).isEqualTo(mode);
        if (withReport) {
            assertThat(prepared.parameters().path("geoReportId").asText()).isEqualTo("41");
            assertThat(authorized.path("geoReport").path("summary").asText()).isEqualTo(originalReport.summary());
            assertThat(authorized.path("geoReport").path("recommendations").get(0).path("id").asText())
                    .isEqualTo("41:recommendation:1");
        } else assertThat(prepared.parameters().path("geoReportId").isNull()).isTrue();
        assertThat(frozen.toString()).isEqualTo(before);
        org.mockito.Mockito.verifyNoInteractions(assetMapper, ids);
        verify(mapper, never()).insert(any());
    }

    private AuthorizedCatalogContextFactory.Context fullArticleCatalog(String publicName) {
        return new AuthorizedCatalogContextFactory.Context(new InternalRunContextDtos.Space(
                "21", "私密项目整理名", new InternalRunContextDtos.Brand(publicName, List.of("小行星"),
                null, List.of(), List.of(), List.of(), List.of()), null), null, List.of());
    }

    @Test
    void fixedFullArticleAdoptsTitleWithAutomaticSourceAndNeverForgesUserConfirmation() {
        OffsetDateTime adoptedAt=now();
        when(mapper.lock(101,7)).thenReturn(Optional.of(article(101,2,"候选标题","","","[]")));
        when(mapper.findWorkflow(101,7)).thenReturn(Optional.of(new ArticleRecords.Workflow(101,7,
                "TITLE_AWAITING_CONFIRMATION",1,null,null,null,null,null,null,null)));
        when(mapper.findTitleBatch(101,7,1)).thenReturn(Optional.of(new ArticleRecords.TitleCandidateBatch(
                601,101,7,21,1,1,"[\"标题一\",\"标题二\",\"标题三\",\"标题四\",\"标题五\"]",801,now())));
        when(mapper.adoptTitle(anyLong(),anyLong(),anyInt(),any(),anyInt(),any())).thenReturn(1);
        assertThat(service.adoptFullStage(7,101,2,"TITLE",801,adoptedAt)).isEqualTo(1);
        verify(mapper).adoptTitle(101,7,2,"标题一",1,adoptedAt);
        verify(mapper,never()).confirmTitle(anyLong(),anyLong(),anyInt(),any(),any(),anyInt(),anyInt(),any());
        assertCode(()->service.confirmTitle(user(),101,new ArticleDtos.ConfirmTitleRequest(2,1,"标题一",
                ArticleDtos.TitleSource.AUTO_POLICY,1)),ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void fixedFullArticleAdoptsGeneratedPlanWithoutMarkingItUserConfirmed() {
        OffsetDateTime adoptedAt=now();
        String plan="""
                {"summary":"摘要","coreMessage":"门店事实","targetAudience":"咖啡爱好者",
                "sections":[{"key":"intro","sequence":1,"level":2,"heading":"门店介绍","brief":"只用已核验事实"},
                {"key":"details","sequence":2,"level":2,"heading":"产品说明","brief":"已提供的菜单事实"}],
                "tone":"PROFESSIONAL","targetLength":"MEDIUM","targetPlatform":null,"riskNotes":[]}
                """;
        when(mapper.lock(101,7)).thenReturn(Optional.of(article(101,4,"标题一","","","[]")));
        when(mapper.findWorkflow(101,7)).thenReturn(Optional.of(new ArticleRecords.Workflow(101,7,
                "PLAN_AWAITING_CONFIRMATION",1,"标题一","AUTO_POLICY",1,1,null,1,null)));
        when(mapper.findPlanRevision(101,7,1)).thenReturn(Optional.of(new ArticleRecords.CreativePlanRevision(
                701,101,7,21,1,3,plan,"GENERATED",802L,null,now(),now())));
        when(mapper.confirmPlan(anyLong(),anyLong(),anyInt(),anyInt(),any(),any())).thenReturn(1);
        assertThat(service.adoptFullStage(7,101,4,"OUTLINE",802,adoptedAt)).isEqualTo(1);
        verify(mapper,never()).markPlanConfirmed(anyLong(),anyLong(),anyInt(),any());
        verify(mapper).confirmPlan(eq(101L),eq(7L),eq(4),eq(1),any(),eq(adoptedAt));
    }

    @Test
    void planConfirmationPersistsExactOutlineAndUnlocksBodyDrafting() {
        String planJson = """
                {"summary":"摘要","coreMessage":"核心观点","targetAudience":"目标读者",
                 "sections":[
                   {"key":"intro","sequence":1,"level":2,"heading":"开场","brief":"引出主题"},
                   {"key":"body","sequence":2,"level":2,"heading":"正文","brief":"展开论证"}],
                 "tone":"PROFESSIONAL","targetLength":"MEDIUM","targetPlatform":null,"riskNotes":[]}
                """;
        ArticleRecords.Article before = article(101, 4, "已确认标题", "", "", "[]");
        ArticleRecords.Article after = article(101, 5, "已确认标题", "", "",
                "[{\"key\":\"intro\",\"sequence\":1,\"level\":2,\"heading\":\"开场\",\"brief\":\"引出主题\"},"
                        + "{\"key\":\"body\",\"sequence\":2,\"level\":2,\"heading\":\"正文\",\"brief\":\"展开论证\"}]");
        ArticleRecords.CreativePlanRevision plan = new ArticleRecords.CreativePlanRevision(
                701, 101, 7, 21, 1, 4, planJson, "GENERATED", 801L, null, now(), now());
        ArticleRecords.Workflow awaiting = new ArticleRecords.Workflow(101, 7,
                "PLAN_AWAITING_CONFIRMATION", 1, "已确认标题", "SELECTED", 1, 1,
                now(), 1, null);
        ArticleRecords.Workflow ready = new ArticleRecords.Workflow(101, 7,
                "BODY_READY_TO_GENERATE", 1, "已确认标题", "SELECTED", 1, 1,
                now(), 1, 1);
        when(mapper.lock(101, 7)).thenReturn(Optional.of(before));
        when(mapper.find(101, 7)).thenReturn(Optional.of(after));
        when(mapper.findWorkflow(101, 7)).thenReturn(Optional.of(awaiting), Optional.of(ready));
        when(mapper.findPlanRevision(101, 7, 1)).thenReturn(Optional.of(plan));
        when(mapper.findTitleBatch(101, 7, 1)).thenReturn(Optional.of(new ArticleRecords.TitleCandidateBatch(
                601, 101, 7, 21, 1, 3,
                "[\"标题一\",\"已确认标题\",\"标题三\",\"标题四\",\"标题五\"]", 800, now())));
        when(mapper.findAssets(101, 7)).thenReturn(List.of());
        when(mapper.markPlanConfirmed(anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.confirmPlan(anyLong(), anyLong(), anyInt(), anyInt(), any(), any())).thenReturn(1);

        ArticleDtos.Response response = service.confirmCreativePlan(user(), 101,
                new ArticleDtos.ConfirmCreativePlanRequest(4, 1));

        assertThat(response.workflowStage()).isEqualTo(ArticleDtos.WorkflowStage.BODY_READY_TO_GENERATE);
        assertThat(response.creativePlan().confirmed()).isTrue();
        assertThat(response.outline()).extracting(ArticleDtos.OutlineResponse::brief)
                .containsExactly("引出主题", "展开论证");
    }

    @Test
    void sanitizerRejectsForgedAssetIdentifiersAndAdminCannotReadArticles() {
        assertCode(() -> new ArticleHtmlSanitizer().sanitize("<span data-asset-id=\"-1\">x</span>"),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.get(new AuthenticatedAccount(1, "admin", AccountRole.ADMIN, 1,
                AccountStatus.ACTIVE, null, null, null), 101), ErrorCode.FORBIDDEN);
        verify(mapper, never()).find(any(Long.class), any(Long.class));
    }

    private ObjectNode articleResult(String mode) {
        ObjectNode data = json.createObjectNode();
        data.put("mode", mode).put("articleVersion", 3);
        data.putArray("titleCandidates");
        data.putNull("title");
        data.putArray("outline");
        data.putNull("plan");
        data.putNull("body");
        data.put("provider", "DEEPSEEK").put("model", "deepseek-v4-flash")
                .put("modelVersion", "v1").put("promptVersion", "agent-bundle-v1.12.0");
        ObjectNode usage = data.putObject("usage");
        usage.put("modelCallCount", 1).put("inputTokens", 20).put("outputTokens", 10).put("latencyMs", 123);
        usage.putNull("monetaryCost").putNull("currency");
        data.putArray("warnings");
        ObjectNode metadata = data.putObject("metadata");
        metadata.put("proposalOnly", true).put("persisted", false).put("published", false);
        metadata.putArray("authorizedRewriteBlockIds");
        return data;
    }

    private ArticleRecords.Article article(long id, int version, String title, String html,
                                            String text, String outline) {
        return new ArticleRecords.Article(id, 7, 21, null, title, html, text, outline, "DRAFT", null,
                version, now(), now());
    }

    private ArticleRecords.Article completedArticle(long id, int version, String title, String html,
                                                     String text, String outline) {
        return new ArticleRecords.Article(id, 7, 21, null, title, html, text, outline, "COMPLETED", null,
                version, now(), now());
    }

    private TaskRecords.Task task(String snapshot) {
        return new TaskRecords.Task(801, 7, 21, null, 31L, 41L, "ARTICLE", "RUNNING",
                "ARTICLE_GENERATING", 2, snapshot, "hash", 901, 5, 0, null, null,
                "task-confirm:41", UUID.randomUUID(), "request-hash", now(), now(), null, now(), now(), null, null);
    }

    private AuthenticatedAccount user() {
        return new AuthenticatedAccount(7, "user", AccountRole.USER, 1, AccountStatus.ACTIVE,
                null, null, null);
    }

    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }

    private ArticleRecords.Workflow workflow(String stage, Integer titleBatch, Integer planVersion) {
        return new ArticleRecords.Workflow(101, 7, stage, titleBatch, null, null, null, null,
                null, planVersion, null);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class)
                .extracting(value -> ((BusinessException) value).errorCode()).isEqualTo(code);
    }
}
