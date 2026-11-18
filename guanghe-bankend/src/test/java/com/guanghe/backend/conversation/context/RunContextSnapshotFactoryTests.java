package com.guanghe.backend.conversation.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogJsonCodec;
import com.guanghe.backend.catalog.competitor.CompetitorMapper;
import com.guanghe.backend.catalog.competitor.CompetitorRecord;
import com.guanghe.backend.catalog.competitor.CompetitorStatus;
import com.guanghe.backend.catalog.competitor.CompetitorType;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.product.ProductRecord;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceRecord;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.resource.RunResourceDtos;
import com.guanghe.backend.conversation.resource.RunResourceService;
import com.guanghe.backend.conversation.ConversationProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RunContextSnapshotFactoryTests {

    @Test
    void nonGeoIdentityIsFrozenAndCannotLoadCatalogOrReportTools() {
        var platform = mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        var identity = new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                "5002", "summary", "1.0.0", "摘要助手", "2001", List.of("chat", "text.summarize"));
        factory.setAgentPlatformService(platform);
        when(platform.identityOf(USER_ID, 9001)).thenReturn(identity);
        var resources = mock(RunResourceService.class);
        factory.setResourceService(resources);
        var properties = new ConversationProperties(); properties.setResourceLookupEnabled(true);
        factory.setConversationProperties(properties);
        var snapshot = factory.create(UUID.randomUUID(), 9001, USER_ID, SPACE_ID, PRODUCT_ID, message());
        assertThat(snapshot.agentIdentity()).usingRecursiveComparison()
                .ignoringFields("executionRelease", "catalogHash").isEqualTo(identity);
        var runtimeCatalog = new com.guanghe.backend.agentplatform.AgentRuntimeCatalog();
        assertThat(snapshot.agentIdentity().executionRelease()).isEqualTo(runtimeCatalog.require(identity).executionRelease());
        assertThat(snapshot.agentIdentity().catalogHash()).isEqualTo(runtimeCatalog.catalogHash());
        assertThat(snapshot.space()).isNull();
        assertThat(snapshot.product()).isNull();
        assertThat(snapshot.competitors()).isEmpty();
        assertThat(snapshot.loadedToolGroups()).isEmpty();
        assertThat(snapshot.taskPreparationConstraints().geo().enabled()).isFalse();
        assertThat(snapshot.geoInsightContext()).isNull();
        verifyNoInteractions(resources, spaceMapper, productMapper, competitorMapper);
    }

    private static final long USER_ID = 41;
    private static final long SPACE_ID = 2001;
    private static final long PRODUCT_ID = 3001;

    @Test
    void chatOnlyNativeIdentityDoesNotReceiveProjectKnowledgeCatalogOrAttachments() {
        var platform=mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        factory.setAgentPlatformService(platform);
        when(platform.identityOf(USER_ID,9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "5001","geo","1.0.0","GEO","2001",List.of("chat")));
        var knowledge=mock(com.guanghe.backend.knowledge.KnowledgeService.class);
        factory.setKnowledgeService(knowledge);
        var resources=mock(RunResourceService.class);
        factory.setResourceService(resources);
        var snapshot=factory.create(UUID.randomUUID(),9001,USER_ID,SPACE_ID,PRODUCT_ID,message());
        assertThat(snapshot.agentIdentity().capabilities()).containsExactly("chat");
        assertThat(snapshot.knowledgeContext()).isNull();
        assertThat(snapshot.space()).isNull();
        assertThat(snapshot.product()).isNull();
        assertThat(snapshot.geoInsightContext()).isNull();
        verifyNoInteractions(knowledge,resources,spaceMapper,productMapper,competitorMapper);
        assertThatThrownBy(()->factory.create(UUID.randomUUID(),9001,USER_ID,SPACE_ID,null,List.of("8001"),message()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure->assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void boundProjectCannotAssembleAnotherClientsCatalogEvenWhenBothBelongToUser() {
        var platform=mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        factory.setAgentPlatformService(platform);
        when(platform.identityOf(USER_ID,9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "5001","geo","1.0.0","GEO","2001",List.of("chat","resources.read")));
        assertThatThrownBy(()->factory.create(UUID.randomUUID(),9001,USER_ID,2002L,null,message()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure->assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verifyNoInteractions(spaceMapper,productMapper,competitorMapper,workspaceService);
    }

    private BusinessSpaceMapper spaceMapper;
    private ProductMapper productMapper;
    private CompetitorMapper competitorMapper;
    private ConversationWorkspaceService workspaceService;
    private ObjectMapper objectMapper;
    private RunContextSnapshotFactory factory;

    @BeforeEach
    void setUp() {
        spaceMapper = mock(BusinessSpaceMapper.class);
        productMapper = mock(ProductMapper.class);
        competitorMapper = mock(CompetitorMapper.class);
        workspaceService = mock(ConversationWorkspaceService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        AuthorizedCatalogContextFactory catalogFactory = new AuthorizedCatalogContextFactory(
                spaceMapper, productMapper, competitorMapper, new CatalogJsonCodec(objectMapper)
        );
        factory = new RunContextSnapshotFactory(catalogFactory, objectMapper, workspaceService);
        when(workspaceService.assemble(anyLong(), anyLong(), any(), any(), any(), any(), anyInt()))
                .thenReturn(emptyWorkspace());
    }

    @Test
    void resourceSnapshotsAreOptionalAndKeepASeparateReportAnchorAcrossAssetReads() throws Exception {
        var resources = mock(RunResourceService.class);
        factory.setResourceService(resources);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var report = new RunResourceDtos.ReadResponse("7001", "GEO_TOPIC", "LATEST_GEO_REPORT", "6001",
                "品牌专题", "2001", null, now, "a".repeat(64), "8001", null,
                objectMapper.createObjectNode().put("reportId", "8001"), false);
        var assetRead = new RunResourceDtos.ReadResponse("7002", "SPACE", "ASSET_INVENTORY", "2001",
                "品牌空间", "2001", null, now, "b".repeat(64), null, null,
                objectMapper.createObjectNode().putArray("assets"), false);
        factory.create(UUID.randomUUID(), 9001, USER_ID, null, null, message());
        verifyNoInteractions(resources);
        var properties = new ConversationProperties(); properties.setResourceLookupEnabled(true);
        factory.setConversationProperties(properties);
        when(resources.lastReadContext(USER_ID, 9001, null, null)).thenReturn(assetRead);
        when(resources.lastReportReadContext(USER_ID, 9001, null, null)).thenReturn(report);
        var snapshot = factory.create(UUID.randomUUID(), 9001, USER_ID, null, null, message());
        assertThat(snapshot.lastReadContext().purpose()).isEqualTo("ASSET_INVENTORY");
        assertThat(snapshot.lastReportReadContext().reportId()).isEqualTo("8001");
        var stored = objectMapper.readTree(objectMapper.writeValueAsString(snapshot));
        assertThat(stored.at("/lastReportReadContext/observationId").asText()).isEqualTo("7001");
        when(resources.lastReadContext(USER_ID, 9001, null, null)).thenReturn(report);
        var same = factory.create(UUID.randomUUID(), 9001, USER_ID, null, null, message());
        assertThat(same.lastReadContext()).isEqualTo(report);
        assertThat(same.lastReportReadContext()).isNull();
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(same)).has("lastReportReadContext")).isFalse();
    }

    @Test
    void resourceHistoryIsNeverLoadedForCurrentAttachments() {
        var resources = mock(RunResourceService.class);
        factory.setResourceService(resources);
        var properties = new ConversationProperties(); properties.setResourceLookupEnabled(true);
        factory.setConversationProperties(properties);
        var snapshot = factory.create(UUID.randomUUID(), 9001, USER_ID, SPACE_ID, null, List.of("8001"), message());
        assertThat(snapshot.lastReadContext()).isNull();
        assertThat(snapshot.lastReportReadContext()).isNull();
        assertThat(snapshot.pendingResourceContext()).isNull();
        verifyNoInteractions(resources);
    }

    @Test
    void capturesAuthorizedSpaceProductAndActiveCompetitorsOnce() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(spaceMapper.findProjectByIdAndUserId(SPACE_ID, USER_ID)).thenReturn(Optional.of(new BusinessSpaceRecord(
                SPACE_ID, USER_ID, "经营空间", "经营空间", "品牌", "{\"description\":\"说明\","
                        + "\"tone\":[\"专业\"],\"audience\":[\"家庭\"],"
                        + "\"sellingPoints\":[\"省电\"],\"prohibitedClaims\":[\"绝对化\"]}",
                999L, 1, now, now, 1, 1
        )));
        when(spaceMapper.findAliases(SPACE_ID, USER_ID))
                .thenReturn(List.of(new AliasValue("品牌别名", "品牌别名")));
        when(productMapper.findByIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.of(new ProductRecord(
                PRODUCT_ID, USER_ID, SPACE_ID, "商品", "商品", "家电", "A1",
                "[\"卖点\"]", "{\"功率\":\"1000W\"}", "[\"家庭\"]", "[\"厨房\"]",
                "[\"禁语\"]", 998L, 1, now, now, 1
        )));
        when(productMapper.findAliases(PRODUCT_ID, USER_ID, SPACE_ID))
                .thenReturn(List.of(new AliasValue("商品别名", "商品别名")));
        CompetitorRecord active = new CompetitorRecord(
                4001, USER_ID, SPACE_ID, "竞品", "竞品", CompetitorType.DIRECT,
                "说明", CompetitorStatus.ACTIVE, 1, now, now, 1
        );
        when(competitorMapper.findActiveForRunContext(USER_ID, SPACE_ID)).thenReturn(List.of(active));
        when(competitorMapper.findAliases(4001, USER_ID, SPACE_ID))
                .thenReturn(List.of(new AliasValue("对手别名", "对手别名")));

        UUID runId = UUID.randomUUID();
        InternalRunContextDtos.RunContext snapshot = factory.create(
                runId, 9001, USER_ID, SPACE_ID, PRODUCT_ID, message()
        );

        assertThat(snapshot.runId()).isEqualTo(runId.toString());
        assertThat(snapshot.space().name()).isEqualTo("经营空间");
        assertThat(snapshot.space().logoAssetId()).isNull();
        assertThat(snapshot.product().name()).isEqualTo("商品");
        assertThat(snapshot.product().primaryAssetId()).isEqualTo("998");
        assertThat(snapshot.competitors()).extracting(InternalRunContextDtos.Competitor::status)
                .containsExactly(CompetitorStatus.ACTIVE);
        assertThat(snapshot.attachmentAssetIds()).isEmpty();
    }

    @Test
    void rolloutFlagsAreOmittedByDefaultAndFrozenForEachNewRun() throws Exception {
        var original = factory.create(UUID.randomUUID(), 9001, USER_ID, null, null, message());
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(original)).has("protocolFeatures")).isFalse();
        var properties = new com.guanghe.backend.conversation.ConversationProperties();
        properties.setBusinessOutcomeEnabled(true);
        factory.setConversationProperties(properties);
        var enabled = factory.create(UUID.randomUUID(), 9001, USER_ID, null, null, message());
        properties.setBusinessOutcomeEnabled(false);

        assertThat(enabled.protocolFeatures().businessOutcome()).isTrue();
        assertThat(enabled.protocolFeatures().resourceLookup()).isFalse();
        var next = factory.create(UUID.randomUUID(), 9001, USER_ID, null, null, message());
        assertThat(next.protocolFeatures()).isNull();
    }

    @Test
    void attachmentSourceUsesItsAuthorizedStorageScopeForDraftsWithoutLoadingCatalogFacts() throws Exception {
        var current = message();
        var snapshot = factory.create(UUID.randomUUID(), 9001, USER_ID, SPACE_ID, null,
                List.of("998"), current);

        assertThat(snapshot.space()).isNull();
        assertThat(snapshot.product()).isNull();
        assertThat(snapshot.competitors()).isEmpty();
        assertThat(snapshot.attachmentAssetIds()).containsExactly("998");
        assertThat(snapshot.taskWorkspace().activeScope().spaceId()).isNull();
        assertThat(snapshot.taskWorkspace().activeScope().productId()).isNull();
        assertThat(snapshot.taskWorkspace().referenceAssetIds()).containsExactly("998");
        org.mockito.Mockito.verify(workspaceService).assemble(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(9001L), org.mockito.ArgumentMatchers.eq(current),
                org.mockito.ArgumentMatchers.eq(SPACE_ID), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(List.of()), anyInt());
        org.mockito.Mockito.verifyNoInteractions(spaceMapper, productMapper, competitorMapper);
        // Non-sensitive, generated cross-language fixture for Python's strict response validator.
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "attachment-run-context-contract-fixture.json"),
                objectMapper.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .writeValueAsString(snapshot));
    }

    @Test
    void freezesGeoProjectionInRunBudgetAndAttachmentSourceCannotLoadSelectedCatalogScope() throws Exception {
        var geo = mock(com.guanghe.backend.geo.GeoChatContextFactory.class);
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(geo);
        var catalog = mock(AuthorizedCatalogContextFactory.class);
        when(catalog.create(anyLong(),any(),any())).thenReturn(new AuthorizedCatalogContextFactory.Context(null,null,List.of()));
        var evidence = objectMapper.createObjectNode().put("schemaVersion",1).put("status","READY");
        evidence.putObject("report").put("reportId","7001");
        when(geo.create(USER_ID,SPACE_ID,PRODUCT_ID,7001L)).thenReturn(evidence);
        factory = new RunContextSnapshotFactory(catalog,objectMapper,workspaceService,
                com.guanghe.backend.common.observability.ObservabilitySupport.noop(),
                new com.guanghe.backend.task.PaidTaskProperties(),provider);
        var snapshot = factory.create(UUID.randomUUID(),9001,USER_ID,SPACE_ID,PRODUCT_ID,message(),7001L);
        assertThat(snapshot.geoInsightContext()).isEqualTo(evidence);
        var saved = objectMapper.readValue(objectMapper.writeValueAsString(snapshot),InternalRunContextDtos.RunContext.class);
        assertThat(saved.geoInsightContext()).isEqualTo(evidence);
        org.mockito.Mockito.verify(geo).create(USER_ID,SPACE_ID,PRODUCT_ID,7001L);
        factory.create(UUID.randomUUID(),9001,USER_ID,SPACE_ID,null,List.of("998"),message());
        org.mockito.Mockito.verify(geo).create(USER_ID,null,null,null);
    }

    @Test
    void rejectsCatalogResourcesOutsideTheAuthorizedSelection() {
        when(spaceMapper.findProjectByIdAndUserId(SPACE_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> factory.create(
                UUID.randomUUID(), 9001, USER_ID, SPACE_ID, null, message()
        )).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void rejectsAProductThatDoesNotBelongToTheSelectedSpace() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(spaceMapper.findProjectByIdAndUserId(SPACE_ID, USER_ID)).thenReturn(Optional.of(new BusinessSpaceRecord(
                SPACE_ID, USER_ID, "经营空间", "经营空间", "品牌", "{}",
                null, 1, now, now, 0, 0
        )));
        when(spaceMapper.findAliases(SPACE_ID, USER_ID)).thenReturn(List.of());
        when(competitorMapper.findActiveForRunContext(USER_ID, SPACE_ID)).thenReturn(List.of());
        when(productMapper.findByIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.of(new ProductRecord(
                PRODUCT_ID, USER_ID, SPACE_ID + 1, "商品", "商品", null, null,
                "[]", "{}", "[]", "[]", "[]", null, 1, now, now, 0
        )));

        assertThatThrownBy(() -> factory.create(
                UUID.randomUUID(), 9001, USER_ID, SPACE_ID, PRODUCT_ID, message()
        )).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void trimsLongHistoryAgainstTheCompleteSnapshotWithMaximumLegalCatalogFields() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ObjectNodeBuilder brand = new ObjectNodeBuilder(objectMapper);
        brand.text("description", fixed("description", 2000));
        brand.array("tone", strings("tone", 10, 50));
        brand.array("audience", strings("audience", 20, 200));
        brand.array("sellingPoints", strings("selling", 20, 500));
        brand.array("prohibitedClaims", strings("prohibited", 20, 500));
        when(spaceMapper.findProjectByIdAndUserId(SPACE_ID, USER_ID)).thenReturn(Optional.of(new BusinessSpaceRecord(
                SPACE_ID, USER_ID, fixed("space", 100), fixed("space", 100), fixed("brand", 100),
                brand.json(), null, 1, now, now, 50, 1
        )));
        when(spaceMapper.findAliases(SPACE_ID, USER_ID)).thenReturn(aliases("brand-alias", 50, 100));

        Map<String, String> parameters = new LinkedHashMap<>();
        for (int index = 0; index < 100; index++) {
            parameters.put(fixed("parameter-" + index, 100), fixed("value-" + index, 1000));
        }
        when(productMapper.findByIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.of(new ProductRecord(
                PRODUCT_ID, USER_ID, SPACE_ID, fixed("product", 150), fixed("product", 150),
                fixed("category", 100), fixed("model", 150),
                objectMapper.writeValueAsString(strings("selling", 50, 500)),
                objectMapper.writeValueAsString(parameters),
                objectMapper.writeValueAsString(strings("audience", 50, 200)),
                objectMapper.writeValueAsString(strings("scenario", 50, 200)),
                objectMapper.writeValueAsString(strings("claim", 50, 500)),
                null, 1, now, now, 50
        )));
        when(productMapper.findAliases(PRODUCT_ID, USER_ID, SPACE_ID))
                .thenReturn(aliases("product-alias", 50, 150));
        CompetitorRecord competitor = new CompetitorRecord(
                4001, USER_ID, SPACE_ID, fixed("competitor", 150), fixed("competitor", 150),
                CompetitorType.DIRECT, fixed("description", 1000), CompetitorStatus.ACTIVE, 1, now, now, 50
        );
        when(competitorMapper.findActiveForRunContext(USER_ID, SPACE_ID)).thenReturn(List.of(competitor));
        when(competitorMapper.findAliases(4001, USER_ID, SPACE_ID))
                .thenReturn(aliases("competitor-alias", 50, 150));

        var oldMessage = new InternalRunContextDtos.MemoryMessage(
                "7001", 1, "USER", objectMapper.createObjectNode().put("text", "h".repeat(120_000)),
                now.minusMinutes(1), null
        );
        var currentMessage = new InternalRunContextDtos.MemoryMessage(
                "8001", 2, "USER", objectMapper.createObjectNode().put("text", "当前请求"), now, null
        );
        when(workspaceService.assemble(anyLong(), anyLong(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new ConversationWorkspaceService.WorkspaceContext(
                        new InternalRunContextDtos.ConversationMemory(
                                List.of(oldMessage, currentMessage), null, 2, 0, "POSTGRESQL"
                        ),
                        emptyWorkspace().taskWorkspace(), List.of("TASK_PREPARATION"),
                        new InternalRunContextDtos.ContextBudget(
                                128000, 16000, 8000, 4000, 100000, 120010, false,
                                "TOKEN_BUDGET_REPLY_CHAIN_THEN_RECENCY"
                        )
                ));

        InternalRunContextDtos.RunContext snapshot = factory.create(
                UUID.randomUUID(), 9001, USER_ID, SPACE_ID, PRODUCT_ID, message()
        );

        assertThat(snapshot.conversationMemory().messages())
                .extracting(InternalRunContextDtos.MemoryMessage::messageId)
                .containsExactly("8001");
        assertThat(snapshot.contextBudget().truncated()).isTrue();
        assertThat(factory.projectedJsonbTextBytes(snapshot))
                .isLessThanOrEqualTo(RunContextSnapshotFactory.DATABASE_CONTEXT_SNAPSHOT_MAX_BYTES);
    }

    @Test
    void fullArticleModeComesOnlyFromOriginalCurrentUserAndSurvivesKnowledgeRevalidation() {
        var platform = mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        factory.setAgentPlatformService(platform);
        when(platform.identityOf(USER_ID, 9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "5001", "geo", "1.0.0", "GEO", "2001", List.of("chat", "article.generate")));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        when(spaceMapper.findProjectByIdAndUserId(SPACE_ID, USER_ID)).thenReturn(Optional.of(new BusinessSpaceRecord(
                SPACE_ID, USER_ID, "小行星", "小行星", "小行星", "{}", null, 1, now, now, 0, 0)));
        when(spaceMapper.findAliases(SPACE_ID, USER_ID)).thenReturn(List.of());
        when(competitorMapper.findActiveForRunContext(USER_ID, SPACE_ID)).thenReturn(List.of());
        var original = message();
        var current = new com.guanghe.backend.conversation.ConversationMessageRecord(
                original.id(), original.conversationId(), original.userId(), "USER", "TEXT",
                "{\"text\":\"目标\",\"articlePreparationMode\":\"ARTICLE_FULL_V1\"}",
                original.replyToMessageId(), original.agentRunId(), original.clientMessageId(),
                original.idempotencyKey(), original.requestFingerprint(), original.sequenceNo(),
                original.status(), original.createdAt(), original.updatedAt());
        var snapshot = factory.create(UUID.randomUUID(), 9001, USER_ID, SPACE_ID, null, current);
        assertThat(snapshot.articlePreparationMode()).isEqualTo("ARTICLE_FULL_V1");
        assertThat(snapshot.withKnowledge(null).articlePreparationMode()).isEqualTo("ARTICLE_FULL_V1");
        assertThat(factory.create(UUID.randomUUID(), 9001, USER_ID, SPACE_ID, null, original)
                .articlePreparationMode()).isNull();
        var assistant = new com.guanghe.backend.conversation.ConversationMessageRecord(
                current.id(), current.conversationId(), current.userId(), "ASSISTANT", "TEXT", current.contentJson(),
                current.replyToMessageId(), current.agentRunId(), current.clientMessageId(), current.idempotencyKey(),
                current.requestFingerprint(), current.sequenceNo(), current.status(), current.createdAt(), current.updatedAt());
        assertThat(factory.create(UUID.randomUUID(), 9001, USER_ID, SPACE_ID, null, assistant)
                .articlePreparationMode()).isNull();
        assertThatThrownBy(() -> factory.create(UUID.randomUUID(), 9001, USER_ID, SPACE_ID + 1, null, current))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> factory.create(UUID.randomUUID(), 9001, USER_ID, SPACE_ID, null,
                List.of("8001"), current)).isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private com.guanghe.backend.conversation.ConversationMessageRecord message() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new com.guanghe.backend.conversation.ConversationMessageRecord(
                8001, 9001, USER_ID, "USER", "TEXT", "{\"text\":\"目标\"}", null,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "0".repeat(64),
                1, "COMPLETED", now, now
        );
    }

    private ConversationWorkspaceService.WorkspaceContext emptyWorkspace() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ConversationWorkspaceService.WorkspaceContext(
                new InternalRunContextDtos.ConversationMemory(List.of(
                        new InternalRunContextDtos.MemoryMessage(
                                "8001", 1, "USER", objectMapper.createObjectNode().put("text", "目标"),
                                now, null
                        )
                ), null, 1, 0, "POSTGRESQL"),
                new InternalRunContextDtos.TaskWorkspace(
                        "目标", "GENERAL", new InternalRunContextDtos.ActiveScope(null, null, List.of()),
                        new ObjectMapper().createObjectNode(), List.of(), null, null, null,
                        List.of(), List.of(), 1
                ),
                List.of("TASK_PREPARATION"),
                new InternalRunContextDtos.ContextBudget(
                        128000, 16000, 8000, 4000, 100000, 1, false,
                        "TOKEN_BUDGET_REPLY_CHAIN_THEN_RECENCY"
                )
        );
    }

    private List<String> strings(String prefix, int count, int length) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> fixed(prefix + "-" + index, length))
                .toList();
    }

    private List<AliasValue> aliases(String prefix, int count, int length) {
        return strings(prefix, count, length).stream().map(value -> new AliasValue(value, value)).toList();
    }

    private String fixed(String prefix, int length) {
        return prefix + "x".repeat(length - prefix.length());
    }

    private static final class ObjectNodeBuilder {
        private final ObjectMapper mapper;
        private final com.fasterxml.jackson.databind.node.ObjectNode value;

        private ObjectNodeBuilder(ObjectMapper mapper) {
            this.mapper = mapper;
            this.value = mapper.createObjectNode();
        }

        void text(String name, String text) {
            value.put(name, text);
        }

        void array(String name, List<String> items) {
            var array = value.putArray(name);
            items.forEach(array::add);
        }

        String json() throws Exception {
            return mapper.writeValueAsString(value);
        }
    }
}
