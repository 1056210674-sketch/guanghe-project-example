package com.guanghe.backend.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.agentplatform.AgentPlatformDtos;
import com.guanghe.backend.agentplatform.AgentPlatformService;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.catalog.product.ProductService;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.conversation.*;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.geo.GeoTaskMapper;
import com.guanghe.backend.knowledge.KnowledgeDtos;
import com.guanghe.backend.knowledge.KnowledgeService;
import com.guanghe.backend.task.*;
import com.guanghe.backend.workflow.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Control-plane boundary tests. Real migration/transaction and paid provider runs are separate gates. */
class ArticleFullServiceTests {
    private static final OffsetDateTime NOW=OffsetDateTime.parse("2026-10-07T08:00:00Z");
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private ArticleFullMapper mapper;
    private ArticleMapper articles;
    private ArticleService articleService;
    private TaskControlService tasks;
    private TaskControlMapper taskMapper;
    private WorkflowPlanMapper plans;
    private WorkflowExecutionMapper funding;
    private ConversationMapper conversations;
    private AgentPlatformService platform;
    private ArticleFullService service;
    private PaidTaskProperties properties;
    private ArticleFullPreparation.Snapshot snapshot;
    private UUID run=UUID.randomUUID();

    @BeforeEach void setup() {
        mapper=writes(ArticleFullMapper.class);articles=writes(ArticleMapper.class);
        articleService=mock(ArticleService.class);tasks=mock(TaskControlService.class);
        taskMapper=mock(TaskControlMapper.class);plans=writes(WorkflowPlanMapper.class);
        funding=writes(WorkflowExecutionMapper.class);conversations=mock(ConversationMapper.class);
        platform=mock(AgentPlatformService.class);properties=new PaidTaskProperties();
        properties.setArticleExecutionEnabled(true);
        properties.setArticleTestPricingVersionId(11);properties.setArticleTitleTestPoints(1);
        properties.setArticleOutlineTestPoints(1);properties.setArticleBodyTestPoints(1);
        var ids=mock(SnowflakeIdGenerator.class);var sequence=new AtomicLong(1000);
        when(ids.nextId()).thenAnswer(ignored->sequence.incrementAndGet());
        service=new ArticleFullService(mapper,articles,articleService,tasks,taskMapper,plans,funding,
                conversations,platform,properties,ids,json,Clock.fixed(NOW.toInstant(),ZoneOffset.UTC));
        ObjectNode brief=json.createObjectNode();brief.putObject("editorial").put("requestText","小行星咖啡店介绍")
                .put("tone","PROFESSIONAL").put("targetLength","MEDIUM");brief.putObject("authorizedContext");
        var frozen=ArticleFullPreparation.freezeBrief(json,brief);
        snapshot=ArticleFullPreparation.prepare(json,properties,frozen,NOW);
        when(articleService.prepareFullArticleBrief(anyLong(),anyLong(),any(),any())).thenReturn(frozen);
        when(mapper.runnable(7)).thenReturn(7L);
        when(platform.assertTaskConfirmationAllowed(7,31)).thenReturn(new AgentPlatformDtos.AgentIdentity(
                "81","geo","1.0.0","GEO","21",List.of("article.generate")));
        when(conversations.findConversation(31,7)).thenReturn(Optional.of(new ConversationRecord(31,7,"测试",21L,null,1,NOW,NOW,NOW)));
        when(articles.find(101,7)).thenReturn(Optional.of(article(1)));
        when(funding.lockPointAccount(7)).thenReturn(Optional.of(new WorkflowExecutionRecords.PointAccount(7,500,3,1,NOW,NOW)));
    }
    @Test void readOnlyGetDoesNotLockWalletOrRenewDeadline() {
        var p=preparation(1);when(mapper.get(201,7)).thenReturn(Optional.of(p));
        when(mapper.reservation(201,7)).thenReturn(Optional.of(reservation(3,0,0,0)));
        when(funding.steps(201,7)).thenReturn(List.of(step(1,"PENDING",null),step(2,"PENDING",null),step(3,"PENDING",null)));
        assertThat(service.get(user(),201).deadlineAt()).isEqualTo(p.deadlineAt());
        assertThat(service.get(user(),201).deadlineAt()).isEqualTo(p.deadlineAt());
        verify(funding,never()).lockReservation(anyLong());verify(funding,never()).lockPointAccount(anyLong());
        verify(mapper,never()).version(anyLong(),anyInt(),any());
    }
    @Test void unknownInFlightTaskNeverTriggersNextStageOrParentReleaseEvenPastDeadline() {
        var p=preparation(1,NOW.minusSeconds(1));allowAdvance(p,List.of(step(1,"WAITING",501L),step(2,"PENDING",null),step(3,"PENDING",null)));
        when(taskMapper.lockTask(501)).thenReturn(Optional.of(task("UNKNOWN",0)));
        service.advance(201);
        verifyNoInteractions(tasks);verify(articleService,never()).adoptFullStage(anyLong(),anyLong(),anyInt(),any(),anyLong(),any());
        verify(funding,never()).releasePoints(anyLong(),anyInt(),any());verify(mapper,never()).finish(anyLong(),any(),any(),any());
    }
    @Test void committedTitleIsAdoptedBeforeAnyOutlineMaterializationAndUsesAutomaticAudit() {
        allowAdvance(preparation(1),List.of(step(1,"WAITING",501L),step(2,"PENDING",null),step(3,"PENDING",null)));
        when(taskMapper.lockTask(501)).thenReturn(Optional.of(task("SUCCEEDED",1)));
        when(funding.lockReservation(201)).thenReturn(Optional.of(reservation(2,1,0,0)));
        when(articles.lock(101,7)).thenReturn(Optional.of(article(2)));
        when(articleService.adoptFullStage(7,101,2,"TITLE",501,NOW)).thenReturn(1);
        service.advance(201);
        verify(funding).finishAllocation(201,7,1,1,0,NOW);
        verify(mapper).adoption(anyLong(),eq(201L),eq(101L),eq(7L),eq("TITLE"),eq(501L),eq(1),eq(NOW));
        verify(mapper).version(201,3,NOW);verifyNoInteractions(tasks);
    }
    @Test void resultVersionConflictCommitsFundingMirrorAndStopsWithoutLosingEarlierResult() {
        allowAdvance(preparation(1),List.of(step(1,"WAITING",501L),step(2,"PENDING",null),step(3,"PENDING",null)));
        when(taskMapper.lockTask(501)).thenReturn(Optional.of(task("SUCCEEDED",1)));
        when(funding.lockReservation(201)).thenReturn(Optional.of(reservation(2,1,0,0)),Optional.of(reservation(2,0,1,0)));
        when(articles.lock(101,7)).thenReturn(Optional.of(article(9)));
        service.advance(201);
        verify(funding).finishAllocation(201,7,1,1,0,NOW);
        verify(funding).releasePoints(7,2,NOW);
        verify(mapper).finish(201,"FAILED","RESOURCE_VERSION_CONFLICT",NOW);
        verify(articleService,never()).adoptFullStage(anyLong(),anyLong(),anyInt(),any(),anyLong(),any());
        verifyNoInteractions(tasks);
    }
    @Test void rejectedAdoptionCommitsPreviousSettlementInsteadOfRetryingWholeArticle() {
        allowAdvance(preparation(1),List.of(step(1,"WAITING",501L),step(2,"PENDING",null),step(3,"PENDING",null)));
        when(taskMapper.lockTask(501)).thenReturn(Optional.of(task("SUCCEEDED",1)));
        when(funding.lockReservation(201)).thenReturn(Optional.of(reservation(2,1,0,0)),Optional.of(reservation(2,0,1,0)));
        when(articles.lock(101,7)).thenReturn(Optional.of(article(2)));
        when(articleService.adoptFullStage(7,101,2,"TITLE",501,NOW)).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED));
        service.advance(201);
        verify(mapper).finish(201,"FAILED","VALIDATION_FAILED",NOW);
        verify(funding).finishAllocation(201,7,1,1,0,NOW);verifyNoInteractions(tasks);
    }
    @Test void nextStageMaterializesSameArticleWithPersistedParentBudgetAndNoRefreeze() throws Exception {
        allowAdvance(preparation(3),List.of(step(1,"SUCCEEDED",501L),step(2,"PENDING",null),step(3,"PENDING",null)));
        when(mapper.lockGrant(301,7,31)).thenReturn(Optional.of(grant("USED",2)));
        when(articles.lock(101,7)).thenReturn(Optional.of(article(3)));
        var checked=new ArticleService.PreparedTask(json.createObjectNode().put("mode","OUTLINE"),json.createObjectNode(),"OUTLINE");
        when(articleService.prepareFullStage(eq(7L),eq(101L),eq(3),eq("OUTLINE"),any())).thenReturn(checked);
        when(tasks.prepareFullArticleDraft(eq(7L),eq(31L),eq(21L),isNull(),eq(checked),any(),any(),any(),any()))
                .thenReturn(new TaskControlService.WorkflowPreparedDraft(402,"ARTICLE",properties.getArticleTestPricingVersionId(),1,"a".repeat(64),snapshot.deadlineAt(),7,31L,21,null));
        when(funding.lockReservation(201)).thenReturn(Optional.of(reservation(2,0,1,0)));
        when(tasks.activateWorkflowTask(any())).thenReturn(task("QUEUED",0));
        service.advance(201);
        var limits=ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(tasks).prepareFullArticleDraft(eq(7L),eq(31L),eq(21L),isNull(),eq(checked),limits.capture(),any(),any(),eq(snapshot.deadlineAt()));
        assertThat(limits.getValue().path("deadlineAt").asText()).isEqualTo(NOW.plusMinutes(10).toString());
        assertThat(limits.getValue().path("maxModelCalls").asInt()).isEqualTo(2);
        verify(funding,never()).freezePoints(anyLong(),anyInt(),any());
        verify(articleService,never()).createForWorkflow(anyLong(),anyLong(),any(),any());
    }
    @Test void revokeUsedGrantStopsFutureStagesWithoutCancellingCommittedInFlightTasks() {
        when(mapper.lockGrant(301,7,31)).thenReturn(Optional.of(grant("USED",2)));
        service.revoke(user(),31,301,2);
        verify(mapper).closeGrant(301,7,2,"REVOKED",NOW);verifyNoInteractions(tasks);
    }
    @Test void revokedGrantRejectsNewStageAndCannotCreateAnotherTask() {
        allowAdvance(preparation(3),List.of(step(1,"SUCCEEDED",501L),step(2,"PENDING",null),step(3,"PENDING",null)));
        when(mapper.lockGrant(301,7,31)).thenReturn(Optional.of(grant("REVOKED",3)));
        assertThatThrownBy(()->service.advance(201)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(tasks);verify(funding,never()).allocateReservation(anyLong(),anyLong(),anyInt(),any());
    }
    @Test void expiredParentOnlyReleasesUnusedPortionAfterNoTaskIsInFlight() {
        allowAdvance(preparation(3,NOW.minusSeconds(1)),List.of(step(1,"SUCCEEDED",501L),step(2,"PENDING",null),step(3,"PENDING",null)));
        when(funding.lockReservation(201)).thenReturn(Optional.of(reservation(2,0,1,0)));
        service.advance(201);
        verify(funding).releasePoints(7,2,NOW);verify(mapper).finish(201,"FAILED","ARTICLE_PARENT_DEADLINE_EXCEEDED",NOW);
        verifyNoInteractions(tasks);
    }
    @Test void assistantMessageCannotAuthorizePaidFullArticle() {
        allowStart("ASSISTANT");
        assertThatThrownBy(()->service.start(user(),31,UUID.randomUUID().toString(),request())).isInstanceOf(BusinessException.class);
        verify(funding,never()).freezePoints(anyLong(),anyInt(),any());
        verify(articleService,never()).createForWorkflow(anyLong(),anyLong(),any(),any());
    }
    @Test void completedGreetingCannotBeRewrittenIntoDifferentPaidArticleRequest() {
        allowStart("USER");when(mapper.lockGrant(301,7,31)).thenReturn(Optional.of(grant("ACTIVE",1)));
        when(conversations.findMessage(401,7,31)).thenReturn(Optional.of(new ConversationMessageRecord(401,31,7,"USER","TEXT",
                "{\"text\":\"你好\"}",null,run,null,null,"a".repeat(64),1,"COMPLETED",NOW,NOW)));
        assertThatThrownBy(()->service.start(user(),31,UUID.randomUUID().toString(),request())).isInstanceOf(BusinessException.class);
        verify(articleService,never()).createForWorkflow(anyLong(),anyLong(),any(),any());verifyNoInteractions(tasks);
    }
    @Test void sourceIdentityCannotBeChangedToAnotherInstallationInSameConversation() {
        allowStart("USER");when(mapper.lockGrant(301,7,31)).thenReturn(Optional.of(grant("ACTIVE",1)));
        when(platform.assertTaskConfirmationAllowed(7,31)).thenReturn(new AgentPlatformDtos.AgentIdentity(
                "82","geo","1.0.0","GEO","21",List.of("article.generate")));
        when(mapper.lockGrant(301,7,31)).thenReturn(Optional.of(new ArticleFullRecords.Grant(301,7,31,82,"geo","1.0.0",21L,21,null,1,"ACTIVE",100,NOW.plusDays(1),NOW,NOW)));
        assertThatThrownBy(()->service.start(user(),31,UUID.randomUUID().toString(),request())).isInstanceOf(BusinessException.class);
        verify(funding,never()).freezePoints(anyLong(),anyInt(),any());
    }
    @Test void explicitOriginalReportIsPassedUnchangedIntoTheFrozenArticleBrief() {
        allowReportStart(reportContext("EXPLICIT_REPORT","51","21",null),originalSource("51"));
        allowAcceptedStart();
        service.start(user(),31,UUID.randomUUID().toString(),request("51"));
        var hints=ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(articleService).prepareFullArticleBrief(eq(7L),eq(21L),isNull(),hints.capture());
        assertThat(hints.getValue().path("geoReportId").asText()).isEqualTo("51");
        verify(funding).freezePoints(7,3,NOW);verifyNoInteractions(tasks);
    }
    @Test void legacyOriginalUserWithoutRawReportFieldCanUseItsExplicitFrozenReport() {
        allowReportStart(reportContext("EXPLICIT_REPORT","51","21",null),originalSource(null));
        allowAcceptedStart();
        service.start(user(),31,UUID.randomUUID().toString(),request("51"));
        var hints=ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(articleService).prepareFullArticleBrief(eq(7L),eq(21L),isNull(),hints.capture());
        assertThat(hints.getValue().path("geoReportId").asText()).isEqualTo("51");
        verify(funding).freezePoints(7,3,NOW);
    }
    @Test void fullStartCannotOmitTheReportSelectedByTheOriginalUser() {
        allowReportStart(reportContext("EXPLICIT_REPORT","51","21",null),originalSource("51"));
        assertReportConflict(null);
    }
    @Test void fullStartCannotReplaceTheReportSelectedByTheOriginalUser() {
        allowReportStart(reportContext("EXPLICIT_REPORT","51","21",null),originalSource("51"));
        assertReportConflict("52");
    }
    @Test void rawOriginalReportCannotDisagreeWithTheServerFrozenSelection() {
        allowReportStart(reportContext("EXPLICIT_REPORT","51","21",null),originalSource("52"));
        assertReportConflict("51");
    }
    @Test void explicitNullOriginalReportCannotBorrowAnExplicitFrozenReport() {
        allowReportStart(reportContext("EXPLICIT_REPORT","51","21",null),originalSource(null).putNull("geoReportId"));
        assertReportConflict("51");
    }
    @Test void reportFromAnotherProjectCannotBeInjectedEvenWithAMatchingId() {
        allowReportStart(reportContext("EXPLICIT_REPORT","51","22",null),originalSource("51"));
        assertReportConflict("51");
    }
    @Test void productReportCannotBeInjectedIntoAProjectOnlyOriginalRequest() {
        allowReportStart(reportContext("EXPLICIT_REPORT","51","21","39"),originalSource("51"));
        assertReportConflict("51");
    }
    @Test void unavailableExplicitReportCannotAuthorizePaidArticlePreparation() {
        ObjectNode context=reportContext("EXPLICIT_REPORT","51","21",null);
        ((ObjectNode)context.path("geoInsightContext")).put("status","UNAVAILABLE");
        allowReportStart(context,originalSource("51"));assertReportConflict("51");
    }
    @Test void originalReportIdsMustRemainStringsWithoutNumericCoercion() {
        allowReportStart(reportContext("EXPLICIT_REPORT","51","21",null),originalSource(null).put("geoReportId",51));
        assertReportConflict("51");
    }
    @Test void frozenReportIdsMustRemainStringsWithoutNumericCoercion() {
        ObjectNode context=reportContext("EXPLICIT_REPORT","51","21",null);
        ((ObjectNode)context.at("/geoInsightContext/report")).put("reportId",51);
        allowReportStart(context,originalSource("51"));assertReportConflict("51");
    }
    @Test void automaticLatestReportDoesNotAuthorizeAddingAReportToFullStart() {
        allowReportStart(reportContext("AUTO_SINGLE_TOPIC","51","21",null),originalSource(null));
        assertReportConflict("51");
    }
    @Test void explicitRawReportRequiresAnExplicitFrozenSelectionNotAnAutomaticReport() {
        allowReportStart(reportContext("AUTO_SINGLE_TOPIC","51","21",null),originalSource("51"));
        assertReportConflict("51");
    }
    @Test void fullStartCannotInjectAReportIntoANonReportOriginalRequest() {
        allowStart("USER");when(mapper.lockGrant(301,7,31)).thenReturn(Optional.of(grant("ACTIVE",1)));
        assertReportConflict("51");
    }
    @Test void automaticOrHistoricalReportReadContextIsNotInheritedIntoThePaidArticle() {
        ObjectNode context=reportContext("AUTO_SINGLE_TOPIC","51","21",null);
        context.putObject("taskWorkspace").putObject("pendingInteraction").put("geoReportId","52");
        context.putObject("lastReportReadContext").put("reportId","53");
        allowReportStart(context,originalSource(null));allowAcceptedStart();
        service.start(user(),31,UUID.randomUUID().toString(),request());
        var hints=ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(articleService).prepareFullArticleBrief(eq(7L),eq(21L),isNull(),hints.capture());
        assertThat(hints.getValue().has("geoReportId")).isFalse();
        verify(funding).freezePoints(7,3,NOW);verifyNoInteractions(tasks);
    }
    @Test void admissionFreezesKnowledgeAndOneParentPotBeforeAnyTaskIsActivated() throws Exception {
        allowStart("USER");when(mapper.lockGrant(301,7,31)).thenReturn(Optional.of(grant("ACTIVE",1)));
        allowAcceptedStart();
        var knowledge=mock(KnowledgeService.class);
        when(knowledge.revalidateFrozenContext(anyLong(),any(),any())).thenAnswer(invocation->invocation.getArgument(2));
        when(knowledge.context(7,21L,"小行星咖啡店介绍")).thenReturn(new KnowledgeDtos.KnowledgeContext(List.of(
                new KnowledgeDtos.ContextDocument("601",2,"门店资料","d".repeat(64),"PASTED_TEXT",null,
                        List.of(new KnowledgeDtos.Paragraph(1,null,"小行星位于广州，不添加无依据的奖项。")))),List.of(),
                        new KnowledgeDtos.BusinessHistory("21",3,1,1,NOW),false,1,26));
        service.setKnowledgeService(knowledge);
        service.start(user(),31,UUID.randomUUID().toString(),request());
        var frozen=ArgumentCaptor.forClass(ArticleFullRecords.Preparation.class);verify(mapper).insert(frozen.capture());
        verify(conversations).setAssistantArticlePreparation(402,7,run,
                Long.toString(frozen.getValue().planId()),"101",NOW);
        var kb=json.readTree(frozen.getValue().snapshotJson()).path("brief").path("authorizedContext").path("knowledge");
        assertThat(kb.path("documents").get(0).path("version").asInt()).isEqualTo(2);
        assertThat(kb.path("businessHistory").path("writtenArticleCount").asInt()).isEqualTo(3);
        verify(funding).freezePoints(7,3,NOW);verify(mapper,times(3)).insertDeferred(any(),any());
        verifyNoInteractions(tasks);
    }
    @Test void changedFrozenKnowledgeStopsNextStageAndReadOnlyViewReportsDrift() throws Exception {
        var knowledge=mock(KnowledgeService.class);
        var frozen=new KnowledgeDtos.KnowledgeContext(List.of(new KnowledgeDtos.ContextDocument("601",2,"门店资料",
                "d".repeat(64),"PASTED_TEXT",null,List.of(new KnowledgeDtos.Paragraph(1,null,"小行星位于广州。")))),
                List.of(),new KnowledgeDtos.BusinessHistory("21",3,1,1,NOW),false,1,10);
        when(knowledge.revalidateFrozenContext(eq(7L),eq(21L),any())).thenReturn(
                new KnowledgeDtos.KnowledgeContext(List.of(),List.of(),frozen.businessHistory(),true,0,0));
        service.setKnowledgeService(knowledge);
        ObjectNode root=(ObjectNode)json.readTree(snapshot.canonicalJson());
        ((ObjectNode)root.path("brief").path("authorizedContext")).set("knowledge",json.valueToTree(frozen));
        var base=preparation(3);
        var p=new ArticleFullRecords.Preparation(base.planId(),base.userId(),base.conversationId(),base.grantId(),base.grantVersion(),
                base.articleId(),base.expectedArticleVersion(),base.sourceRunId(),base.requestMessageId(),base.idempotencyKey(),base.requestHash(),
                json.writeValueAsString(root),base.briefHash(),base.maxCalls(),base.maxInputTokens(),base.maxOutputTokens(),base.deadlineAt(),base.status(),null,NOW,NOW);
        allowAdvance(p,List.of(step(1,"SUCCEEDED",501L),step(2,"PENDING",null),step(3,"PENDING",null)));
        when(mapper.lockGrant(301,7,31)).thenReturn(Optional.of(grant("USED",2)));
        when(articles.lock(101,7)).thenReturn(Optional.of(article(3)));
        when(funding.lockReservation(201)).thenReturn(Optional.of(reservation(2,0,1,0)));
        service.advance(201);
        verify(mapper).finish(201,"FAILED","KNOWLEDGE_SOURCE_CHANGED",NOW);verifyNoInteractions(tasks);
        when(mapper.get(201,7)).thenReturn(Optional.of(p));
        when(mapper.reservation(201,7)).thenReturn(Optional.of(reservation(2,0,1,0)));
        clearInvocations(mapper,funding);
        assertThat(service.get(user(),201).knowledgeSourcesCurrent()).isFalse();
        verify(mapper,never()).finish(anyLong(),any(),any(),any());verify(funding,never()).lockPointAccount(anyLong());
    }
    @Test void duplicateAcceptanceReturnsPersistedPlanWithoutFreezingAgain() {
        String key=UUID.randomUUID().toString();var p=preparation(1);
        // Request hash is deterministic and independent of current grant/configuration state.
        String hash;
        try { hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((31+"|"+json.writeValueAsString(request())).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(Exception e){throw new IllegalStateException(e);}
        p=new ArticleFullRecords.Preparation(p.planId(),7,31,301,1,101,1,run,401,UUID.fromString(key),hash,p.snapshotJson(),p.briefHash(),6,p.maxInputTokens(),p.maxOutputTokens(),p.deadlineAt(),"RUNNING",null,NOW,NOW);
        when(mapper.replay(7,UUID.fromString(key))).thenReturn(Optional.of(p));
        when(mapper.reservation(201,7)).thenReturn(Optional.of(reservation(3,0,0,0)));
        assertThat(service.start(user(),31,key,request()).id()).isEqualTo("201");
        verify(funding,never()).freezePoints(anyLong(),anyInt(),any());verify(mapper,never()).closeGrant(anyLong(),anyLong(),anyInt(),any(),any());
    }
    @Test void shortConfirmationProjectsTrustedEditorialOfferWithoutWritingAnything() {
        allowNativeContinuation("好",null,null);
        var evidence=service.articleContinuation(run,21).orElseThrow();
        assertThat(evidence.path("authorized").asBoolean()).isFalse();
        assertThat(evidence.path("reviewRequired").asBoolean()).isTrue();
        assertThat(evidence.path("sourceMessageId").asText()).isEqualTo("501");
        assertThat(evidence.path("offerMessageId").asText()).isEqualTo("502");
        assertThat(evidence.path("currentMessageId").asText()).isEqualTo("401");
        assertThat(evidence.path("requestText").asText()).contains("广州咖啡怎么选","原始需求","标题、结构","不是品牌事实");
        verify(mapper,never()).insertGrant(any());verify(funding,never()).freezePoints(anyLong(),anyInt(),any());
    }
    @Test void nativeConfirmationStartsOneArticleWithFrozenOfferProvenanceAndSourceReport() throws Exception {
        allowNativeContinuation("认可","51",null);allowNativeAcceptance();
        service.startFromAgent(run,21,null,null,null,approvedReview());
        var hints=ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(articleService).prepareFullArticleBrief(eq(7L),eq(21L),isNull(),hints.capture());
        assertThat(hints.getValue().path("requestText").asText()).isEqualTo("认可");
        assertThat(hints.getValue().path("geoReportId").asText()).isEqualTo("51");
        var saved=ArgumentCaptor.forClass(ArticleFullRecords.Preparation.class);verify(mapper).insert(saved.capture());
        var scope=json.readTree(saved.getValue().snapshotJson()).at("/brief/authorizedContext/articleContinuation");
        var parentBrief=(ObjectNode)json.readTree(saved.getValue().snapshotJson()).path("brief");
        // Mirrors the immutable DB authority equality, not merely a mocked successful insert.
        assertThat(parentBrief.at("/editorial/requestText").asText()).isEqualTo(
                json.readTree(conversations.findMessage(401,7,31).orElseThrow().contentJson()).path("text").asText().trim());
        assertThat(saved.getValue().briefHash()).isEqualTo(ArticleFullPreparation.freezeBrief(json,parentBrief).sha256());
        assertThat(scope.path("requestText").asText()).contains("广州咖啡怎么选","小行星咖啡店介绍").isNotEqualTo("认可");
        assertThat(scope.path("sourceMessageId").asText()).isEqualTo("501");
        assertThat(scope.path("offerMessageId").asText()).isEqualTo("502");
        assertThat(scope.path("geoReportId").asText()).isEqualTo("51");
        assertThat(scope.path("authorized").asBoolean()).isTrue();
        assertThat(scope.path("reviewRequired").asBoolean()).isFalse();
        assertThat(scope.path("continuationReview")).isEqualTo(json.valueToTree(approvedReview()));
        verify(funding).freezePoints(7,3,NOW);verifyNoInteractions(tasks);
    }
    @ParameterizedTest
    @ValueSource(strings={
            "请先给面向广州企业的GEO选题和1200–1500字、四部分结构。先不要生成，不要监测、投稿或联网。",
            "标题必须是《广州企业如何了解AI搜索》。请写一篇中立科普文章，不要提及品牌。1200–1500字，四部分结构。先只讨论方案，不要监测、投稿或联网。"})
    void realContinuationMaterializesUserRequirementsSeparatelyFromAssistantEditorialProposal(String originalUser) throws Exception {
        var previous=allowNativeContinuation("继续做",null,null);allowNativeAcceptance();
        String offer="建议标题《客户已经在AI里问了》。四部分：客户问题、判断方式、公司档案、下一步。"
                +"内部编辑提示：每个结论都说明尚未独立核实。"
                +"单一提议：我按上面这个选题和四部分结构，写一篇1200–1500字的完整稿，不动监测、不投稿。";
        when(conversations.findMessage(501,7,31)).thenReturn(Optional.of(nativeMessage(
                501,previous,"USER",originalUser,1,NOW.minusMinutes(2))));
        when(conversations.findMessage(502,7,31)).thenReturn(Optional.of(nativeMessage(
                502,previous,"ASSISTANT",offer,2,NOW.minusMinutes(1))));
        when(mapper.writingMessages(7,31,21,3,401,NOW)).thenReturn(List.of(
                new ArticleFullMapper.WritingMessage(490,"USER","此前讨论的是广州企业的真实需求。",18)));

        // Exercise the real brief freezer and real stage materializer; do not handcraft the
        // combined continuation string which previously concealed the production regression.
        var catalog=mock(AuthorizedCatalogContextFactory.class);
        when(catalog.create(7,21L,null)).thenReturn(new AuthorizedCatalogContextFactory.Context(
                new InternalRunContextDtos.Space("21","内部项目",new InternalRunContextDtos.Brand(
                        "小行星",List.of(),null,List.of(),List.of(),List.of(),List.of()),null),null,List.of()));
        var materializer=new ArticleService(articles,mock(AssetMapper.class),mock(GeoTaskMapper.class),
                mock(BusinessSpaceService.class),mock(ProductService.class),catalog,
                mock(SnowflakeIdGenerator.class),json,new ArticleHtmlSanitizer());
        when(articleService.prepareFullArticleBrief(anyLong(),anyLong(),any(),any())).thenAnswer(invocation->
                materializer.prepareFullArticleBrief(invocation.getArgument(0),invocation.getArgument(1),
                        invocation.getArgument(2),invocation.getArgument(3)));
        service.startFromAgent(run,21,null,null,null,approvedReview());
        var saved=ArgumentCaptor.forClass(ArticleFullRecords.Preparation.class);verify(mapper).insert(saved.capture());
        var frozen=(ObjectNode)json.readTree(saved.getValue().snapshotJson()).path("brief");
        assertThat(frozen.at("/editorial/requestText").asText()).isEqualTo("继续做");
        assertThat(saved.getValue().briefHash()).isEqualTo(ArticleFullPreparation.freezeBrief(json,frozen).sha256());
        assertThat(frozen.at("/authorizedContext/articleContinuation/sourceMessageId").asText()).isEqualTo("501");
        assertThat(frozen.at("/authorizedContext/articleContinuation/offerMessageId").asText()).isEqualTo("502");
        when(articles.lock(101,7)).thenReturn(Optional.of(article(1)));
        when(articles.findWorkflow(101,7)).thenReturn(Optional.of(new ArticleRecords.Workflow(
                101,7,"TITLE_INPUT",null,null,null,null,null,null,null,null)));
        String workerBrief=materializer.prepareFullStage(7,101,1,"TITLE",frozen).parameters().path("brief").asText();
        String marker="\n\n【写作参考／非授权】";
        assertThat(workerBrief).hasSizeLessThanOrEqualTo(4000);
        assertThat(workerBrief.indexOf(marker)).isPositive().isEqualTo(workerBrief.lastIndexOf(marker));
        String actualRequest=workerBrief.substring(0,workerBrief.indexOf(marker));
        String reference=workerBrief.substring(workerBrief.indexOf(marker)+marker.length());
        assertThat(actualRequest).startsWith("本次 USER 回复（当前轮）：\n继续做")
                .contains(originalUser,"1200–1500字","四部分结构")
                .doesNotContain("客户已经在AI里问了","公司档案","内部编辑提示","尚未独立核实");
        assertThat(reference).contains(offer,"不是当前 USER 原话","不是品牌事实","不要求逐字复述提议",
                "【补充历史／非授权】","此前讨论的是广州企业的真实需求。");
        if(originalUser.contains("不要提及品牌")) {
            assertThat(actualRequest).contains("请写一篇中立科普文章，不要提及品牌。","标题必须是《广州企业如何了解AI搜索》。");
        }
        verify(funding,times(1)).freezePoints(7,3,NOW);
        verifyNoInteractions(tasks);
    }
    @ParameterizedTest
    @ValueSource(strings={
            "**单一写作提议**：是否按上述选题和四段结构，写一篇 1200–1500 字的完整稿？你说可以，我就开始生成，成稿可以在结果里直接查看和编辑。",
            "请再明确一句就行：是否就按上轮那个选题和四段结构，出 1200–1500 字的完整稿？你说确认，我就提交生成。",
            """
            **选题（单一提议）**

            《AI 搜索成了新入口，广州企业该怎么选 GEO 优化与智能体定制服务？》

            定位：写给广州的品牌商、代运营公司和中小电商商家——他们可能是被同行或客户提醒"你在 AI 里搜不到"才来找答案的。文章帮他们理解这两类服务在解决什么问题、自己是否该做、选服务商时看什么，而不是教人怎么优化。

            **四部分结构（合计约 1200–1500 字）**

            一、开头与背景：为什么广州企业开始关心"AI 里有没有我"（约 300 字）
            用客户的真实处境开场——有人直接在 AI 助手里问"广州哪家能做 GEO 优化""广州有没有靠谱的智能体开发公司"。然后做一般性解释：行业里普遍把"公开内容能否被检索、被引用"看作企业出现在 AI 回答里的基础条件之一，这也解释了为什么 GEO 从营销话题变成采购话题。此处只讲行业常识，不诊断任何具体企业的情况，也不承诺"做了就会被推荐"。

            二、GEO 搜索优化：解决什么问题、什么样的企业该先做（约 400 字）
            讲清它与传统 SEO 在回答形式、可见方式上的差别；给出几个自查信号，比如客户会主动问这类服务、公开页面上的公司名或业务描述与实际不一致、主要靠口碑没留下可查的公开信息。说明适用范围和边界：GEO 影响的是被提及和被引用的概率，不等于排名或成交保证。这一部分不写操作步骤。

            三、智能体定制开发：哪些业务场景值得投入（约 400 字）
            结合公司的业务脉络——从 AIGC 营销与 GEO 优化切入，延展到企业级 AI 电商智能体 SaaS——说明定制开发的典型切入面：面向消费者的咨询与导购、内容与素材生产、内部流程与数据问答等通用类别。再给读者一条判断线：什么情况下用通用工具就够、什么情况下需要定制、自建与采购各自要考虑的长期成本。场景描述按行业通用情况展开，不套用到任何具体企业。

            四、怎么判断一家服务商是否合适，光禾 AI 是谁（约 300–350 字）
            先给选择依据：能不能把做法和边界讲清楚、愿不愿意先做小范围验证再扩大、交付后如何维护和交接数据。然后自然带入公司：广州光禾未来人工智能科技有限公司（光禾 AI），2023 年成立，位于广州琶洲，主要做 GEO 搜索优化和智能体定制开发，服务过电商品牌、医疗健康、制造业、本地生活等行业，客户以品牌商、代运营公司和中小电商商家为主；服务范围覆盖全国、以远程交付为主，广州是重点曝光城市。结尾给出下一步：官网 www.guangheai.com，可先就自己的业务做一次需求沟通。

            **写作时明确不写的内容**：客户案例、成效数字、资质奖项、价格、团队规模、具体客户名称——这些本项目里没有已确认记录，一律不编。全文其他事实只取自已确认资料（品牌名与别名、主营、业务背景、成立年份、涉及行业、所在地、服务范围、客户类型、官网、目标地域与需求方向）。

            **提议**：按上面这个选题和四部分结构，写一篇约 1200–1500 字的完整稿。你答应后我就开始写。
            """,
            """
            **选题（单一建议）**

            《客户不再只搜百度了：广州企业该怎么理解 GEO 搜索优化和智能体定制开发》

            面向对象：广州本地及在广州设点的品牌商、代运营公司、中小电商商家的决策人——他们已经在问"AI 里能不能搜到我们""要不要做个智能体"，但还分不清这两件事各解决什么问题。

            **四部分结构（约 1200–1500 字）**

            **第一部分：变化发生在客户那边（约 300 字）**
            从读者的日常经验切入——采购、找服务、比方案时越来越多人先问 AI，得到的是几句概括而非一列链接。说明这意味着什么：企业被"提及"和"没被提及"的差别，往往在客户第一次接触前就形成了。这部分只解释一般现象，不下结论说谁一定被推荐、谁一定被漏掉，也不假设读者已经了解 GEO。

            **第二部分：GEO 搜索优化解决什么、不解决什么（约 400 字）**
            解释 GEO 的目标是让品牌在 AI 生成的回答里被准确提及和描述，与投放广告、传统排名是不同的事。写清楚它依赖什么（公开可核验的品牌信息、内容是否讲清业务和适用条件）、以及它的边界——它不是一次性交付、也不该被承诺成"必然被推荐"。给出企业自查的三个方向：对外名称是否统一、主营和地域是否说得明白、客户会怎么问。这部分是帮助读者判断，不写成操作清单。

            **第三部分：智能体定制开发是另一件事（约 400 字）**
            说明智能体更多落在企业自己的业务流程里，与 GEO 面向公开搜索的可见度是两个层面。结合已确认的业务背景——从 AIGC 营销与 GEO 优化切入、延展到企业级 AI 电商智能体 SaaS——讲清楚两者如何衔接。然后给出选择服务商的判断依据：需求描述是否具体、交付方式是否清楚、能不能说明哪些是行业常识哪些是定制内容、验收标准是什么。这是通用选择方法，不针对任何具体同行。

            **第四部分：光禾AI在这条线上的位置（约 300 字）**
            自然带出公司信息：2023 年成立的广州光禾未来人工智能科技有限公司，办公地在广州琶洲，主营 GEO 搜索优化与智能体定制开发，服务过电商品牌、医疗健康、制造业、本地生活等领域，客户包括品牌商、代运营公司和中小电商商家，面向全国远程交付。收尾落在"怎么开始"——建议读者先把自己希望被回答的问题列出来，再判断需要哪一类服务。官网 www.guangheai.com 可作为信息核对入口。

            **写作边界（会严格遵守）**
            - 只用上述已确认的品牌事实；公司注册资本、团队规模、客户名单、具体效果数据、资质荣誉一律不写。
            - 通用行业原理（AI 回答如何生成、信息一致性的作用等）可以展开解释，但不包装成光禾AI的实绩或行业统计。
            - 不出现虚构客户案例、前后对比数字、"提升 X%"这类表述。

            **我的提议**

            要不要我就按上面这个选题和四部分结构，写一篇 1200–1500 字的完整稿？你答应后我再生成，正文会保存在项目里，可以编辑、也可以之后再选媒体，投稿前还会再跟你确认一次。
            """,
            """
            **选题（暂定标题）**

            《客户开始问 AI 了：广州企业该怎么理解 GEO，又该怎么挑服务商》

            定位：写给广州有真实需求的品牌商、代运营公司和中小电商商家——他们可能已经听过 GEO，或正被人推荐做智能体，但还不清楚这两件事分别解决什么问题、投入前该看什么。全文解释原理与判断依据，不给操作步骤。

            **四部分结构（合计约 1200–1500 字）**

            **一、客户了解企业的方式变了（约 300 字）**
            从读者熟悉的场景切入：潜在客户过去搜关键词、比页面，现在越来越多直接把问题抛给 AI 助手，比如“广州有没有做 GEO 的公司”“我们做电商要不要上智能体”。这一部分解释 GEO 究竟在做什么——让企业的信息在 AI 的回答里能被正确提到和理解，以及它与传统 SEO 的关注点差异。强调一个边界：AI 回答的提及情况是一种观察口径，不等于市场份额、销量或消费者整体评价，避免读者把“被提到”直接当成成交。

            **二、什么阶段的企业值得投入（约 350 字）**
            按需求信号分类，而不是按行业贴标签：有稳定业务、但客户搜索行为已经转向对话式提问的品牌，更需要 GEO；客服重复问答多、咨询量大、内容与产品参数需要统一口径的企业，智能体定制开发的收益更直接。可结合光禾已涉及的电商品牌、医疗健康、制造业、本地生活四类行业说明差异，但只作背景说明，不写成“这些行业必须这样做”。

            **三、选择服务商时看什么（约 400 字）**
            这是文章的核心判断部分，给可核实的依据：是否愿意先厘清业务、服务范围和希望覆盖的地域，而不是一上来报关键词数量；是否区分“实际能服务的范围”和“希望被曝光的地域”；是否说得清怎么衡量进展、多久用同一口径复测一次；交付方式是本地驻场还是远程协作，与企业的沟通节奏能否对上。同时说明：任何不解释衡量方式、只承诺结果的服务承诺，都值得先打问号。

            **四、广州企业怎么起步，以及一家本地公司怎么做（约 350 字）**
            收束成起步路径：先明确自己的业务和曝光地域，选一到两个真实客户会问的问题做小范围试点，按同口径复测再决定是否扩大，不追求一次铺开。随后自然介绍光禾 AI——广州光禾未来人工智能科技有限公司，2023 年成立，公司在广州琶洲，业务从 AIGC 营销与 GEO 优化切入，延展到企业级 AI 电商智能体 SaaS，服务全国、以远程方式交付，官网 www.guangheai.com。只作事实交代，不写效果承诺。

            **写作边界**：全文只使用上述已确认信息；通用行业原理可以展开，不编造客户案例、效果数据、资质或“行业第一”类表述。

            **提议**：按这个选题和四部分结构写一篇 1200–1500 字的完整稿，你看要不要现在开始？
            """})
    void realQuestionAndFullDeclarativeOffersProjectScopedContinuationAndStartOnePreparation(String offer) throws Exception {
        offer=offer.strip();
        var previous=allowNativeContinuation("继续做",null,null);allowNativeAcceptance();
        String original="请使用本项目品牌知识库里我已经确认的资料，先给一个面向广州企业潜在客户的 GEO 文章选题，以及约 1200–1500 字的四部分写作结构。"
                +"文章应帮助有 GEO 搜索优化或智能体定制开发需求的客户理解服务和选择依据，自然介绍光禾AI及公司，不要写成给运营人员看的优化操作方案或只有几句的公司简介。"
                +"本轮只讨论选题和结构，先不要生成正文，不要启动监测，不要投稿，不要联网。全文之后也只使用已确认的品牌事实；通用行业解释可以展开，但不要编造客户案例、效果数据或资质。"
                +"请在最后提出按这个选题和结构写一篇完整稿的单一提议，等我答应后再写。";
        when(conversations.findMessage(501,7,31)).thenReturn(Optional.of(nativeMessage(
                501,previous,"USER",original,1,NOW.minusMinutes(2))));
        when(conversations.findMessage(502,7,31)).thenReturn(Optional.of(nativeMessage(
                502,previous,"ASSISTANT",offer,2,NOW.minusMinutes(1))));
        var evidence=service.articleContinuation(run,21).orElseThrow();
        assertThat(evidence.path("authorized").asBoolean()).isFalse();
        assertThat(evidence.path("reviewRequired").asBoolean()).isTrue();
        assertThat(evidence.path("action").asText()).isEqualTo("article.generate");
        assertThat(evidence.path("currentMessageId").asText()).isEqualTo("401");
        assertThat(evidence.path("sourceMessageId").asText()).isEqualTo("501");
        assertThat(evidence.path("offerMessageId").asText()).isEqualTo("502");
        verify(mapper,never()).insertGrant(any());verify(funding,never()).freezePoints(anyLong(),anyInt(),any());

        service.startFromAgent(run,21,null,null,null,approvedReview());
        var saved=ArgumentCaptor.forClass(ArticleFullRecords.Preparation.class);verify(mapper).insert(saved.capture());
        var frozen=json.readTree(saved.getValue().snapshotJson()).path("brief");
        assertThat(frozen.at("/editorial/requestText").asText()).isEqualTo("继续做");
        var admitted=evidence.deepCopy().put("authorized",true).put("reviewRequired",false);
        admitted.set("continuationReview",json.valueToTree(approvedReview()));
        assertThat(frozen.at("/authorizedContext/articleContinuation")).isEqualTo(admitted);
        String request=evidence.path("requestText").asText();
        int boundary=request.indexOf("\n\n【写作参考／非授权】");
        assertThat(boundary).isPositive();
        assertThat(request.substring(0,boundary)).contains(original).doesNotContain(offer);
        assertThat(request.substring(boundary)).contains(offer);
        when(mapper.bySourceRun(run,7)).thenReturn(Optional.of(saved.getValue()));
        when(mapper.byWritingOffer(7,31,"502")).thenReturn(Optional.of(saved.getValue()));
        service.startFromAgent(run,21,null,null,null,approvedReview());
        verify(mapper,times(1)).insert(any());verify(mapper,times(1)).insertGrant(any());
        verify(funding,times(1)).freezePoints(7,3,NOW);verifyNoInteractions(tasks);
    }
    @ParameterizedTest
    @ValueSource(strings={
            "要不要我按你刚才的要求，重出一整版新稿？",
            "我把这些内容串成一份能直接给读者看的稿子，好吗？",
            "生成一篇文章的方法如下：先写标题。",
            "这只是一个例子：“我可以写一篇文章。”",
            "我先监测，再写一篇稿并投稿，可以吗？",
            "只讨论结构，先不写正文。"})
    void assistantProseIsUntrustedSemanticEvidenceNeverAHardcodedAuthorization(String offer) {
        var previous=allowNativeContinuation("好",null,null);
        when(conversations.findMessage(502,7,31)).thenReturn(Optional.of(nativeMessage(
                502,previous,"ASSISTANT",offer,2,NOW.minusMinutes(1))));
        var evidence=service.articleContinuation(run,21).orElseThrow();
        assertThat(evidence.path("authorized").asBoolean()).isFalse();
        assertThat(evidence.path("reviewRequired").asBoolean()).isTrue();
        assertThat(evidence.path("sourceText").asText()).isEqualTo("小行星咖啡店介绍");
        assertThat(evidence.path("offerText").asText()).isEqualTo(offer);
        assertThatThrownBy(()->service.startFromAgent(run,21,null,null))
                .isInstanceOfSatisfying(BusinessException.class,failure->
                        assertThat(failure.details()).containsEntry("reasonCode","ARTICLE_CONTINUATION_REVIEW_REQUIRED"));
        verify(mapper,never()).insertGrant(any());verify(funding,never()).freezePoints(anyLong(),anyInt(),any());
    }
    @Test void trustedReviewMustBindAllThreeCurrentCandidateIdsAndTheNewDraftDecision() {
        allowNativeContinuation("好",null,null);
        for(var review:List.of(
                new com.guanghe.backend.conversation.action.RunActionDtos.ArticleContinuationReview("999","501","502","NEW_DRAFT"),
                new com.guanghe.backend.conversation.action.RunActionDtos.ArticleContinuationReview("401","999","502","NEW_DRAFT"),
                new com.guanghe.backend.conversation.action.RunActionDtos.ArticleContinuationReview("401","501","999","NEW_DRAFT"),
                new com.guanghe.backend.conversation.action.RunActionDtos.ArticleContinuationReview("401","501","502","PLAN_ONLY"))) {
            assertThatThrownBy(()->service.startFromAgent(run,21,null,null,null,review))
                    .isInstanceOf(BusinessException.class);
        }
        when(mapper.writingOffer(7,31,21,3,NOW)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.startFromAgent(run,21,null,null,null,approvedReview()))
                .isInstanceOf(BusinessException.class);
        verify(mapper,never()).insertGrant(any());verify(funding,never()).freezePoints(anyLong(),anyInt(),any());
    }
    @Test void realWholeDraftRephraseIsAdmittedOnceAfterSemanticReviewWithoutAssistantKeywordMatching() {
        var previous=allowNativeContinuation("好",null,null);allowNativeAcceptance();
        when(conversations.findMessage(502,7,31)).thenReturn(Optional.of(nativeMessage(
                502,previous,"ASSISTANT","要不要我按这三处重出一整版新稿（四块结构和 1000–1500 字不变，另起标题）？"
                        +"那需要重新提交生成，预计 8 点、尚未结算，最终以结算记录为准。",
                2,NOW.minusMinutes(1))));
        service.startFromAgent(run,21,null,null,null,approvedReview());
        var saved=ArgumentCaptor.forClass(ArticleFullRecords.Preparation.class);verify(mapper).insert(saved.capture());
        when(mapper.bySourceRun(run,7)).thenReturn(Optional.of(saved.getValue()));
        when(mapper.byWritingOffer(7,31,"502")).thenReturn(Optional.of(saved.getValue()));
        service.startFromAgent(run,21,null,null,null,approvedReview());
        verify(mapper,times(1)).insertGrant(any());verify(funding,times(1)).freezePoints(7,3,NOW);
    }
    @Test void currentDifferentExplicitReportCannotReplaceApprovedOfferReport() {
        allowNativeContinuation("好","51","52");
        assertThat(service.articleContinuation(run,21)).isEmpty();
        assertThatThrownBy(()->service.startFromAgent(run,21,null,null)).isInstanceOf(BusinessException.class);
        verify(mapper,never()).insertGrant(any());verify(funding,never()).freezePoints(anyLong(),anyInt(),any());
    }
    @Test void noAdjacentEligibleOfferCannotBeGuessedFromEarlierConversation() {
        allowNativeContinuation("好",null,null);
        when(mapper.writingOffer(7,31,21,3,NOW)).thenReturn(Optional.empty());
        assertThat(service.articleContinuation(run,21)).isEmpty();
        assertThatThrownBy(()->service.startFromAgent(run,21,null,null)).isInstanceOf(BusinessException.class);
        verify(mapper,never()).insertGrant(any());
    }
    @Test void explicitReplyToAnotherMessageCannotConsumeAdjacentOffer() {
        allowNativeContinuation("好",null,null);
        var message=conversations.findMessage(401,7,31).orElseThrow();
        when(conversations.findMessage(401,7,31)).thenReturn(Optional.of(new ConversationMessageRecord(401,31,7,"USER","TEXT",
                message.contentJson(),499L,run,null,null,"a".repeat(64),3,"COMPLETED",NOW,NOW)));
        assertThat(service.articleContinuation(run,21)).isEmpty();
    }
    @Test void negativeAcknowledgementPlanApprovalAndPunctuationCannotStartAnArticle() {
        for(String text:List.of("先别写","只认可方案","好但不要生成","...")) {
            allowNativeContinuation(text,null,null);
            assertThat(service.articleContinuation(run,21)).as(text).isEmpty();
            assertThatThrownBy(()->service.startFromAgent(run,21,null,null)).as(text).isInstanceOf(BusinessException.class);
        }
        verify(mapper,never()).insertGrant(any());
    }
    @Test void consumedOfferReturnsExistingPreparationEvenWhenItFailedAndNeverRefreezes() {
        allowNativeContinuation("好",null,null);
        var old=preparation(1);
        var failed=new ArticleFullRecords.Preparation(old.planId(),old.userId(),old.conversationId(),old.grantId(),old.grantVersion(),
                old.articleId(),old.expectedArticleVersion(),UUID.randomUUID(),old.requestMessageId(),old.idempotencyKey(),old.requestHash(),
                old.snapshotJson(),old.briefHash(),old.maxCalls(),old.maxInputTokens(),old.maxOutputTokens(),old.deadlineAt(),"FAILED","ARTICLE_STAGE_FAILED",NOW,NOW);
        when(mapper.byWritingOffer(7,31,"502")).thenReturn(Optional.of(failed));
        when(mapper.reservation(201,7)).thenReturn(Optional.of(reservation(0,0,3,0)));
        var evidence=service.articleContinuation(run,21).orElseThrow();
        assertThat(evidence.path("authorized").asBoolean()).isFalse();
        assertThat(evidence.path("submissionState").asText()).isEqualTo("ALREADY_STARTED");
        assertThat(evidence.at("/preparation/status").asText()).isEqualTo("FAILED");
        assertThat(service.startFromAgent(run,21,null,null,null,approvedReview()).id()).isEqualTo("201");
        verify(mapper,never()).insertGrant(any());verify(funding,never()).freezePoints(anyLong(),anyInt(),any());
    }
    @Test void sameRunReplayRetainsInputFingerprintAndReturnsItsOnePreparation() {
        allowNativeContinuation("好",null,null);allowNativeAcceptance();
        service.startFromAgent(run,21,null,null,null,approvedReview());
        var saved=ArgumentCaptor.forClass(ArticleFullRecords.Preparation.class);verify(mapper).insert(saved.capture());
        when(mapper.bySourceRun(run,7)).thenReturn(Optional.of(saved.getValue()));
        when(mapper.byWritingOffer(7,31,"502")).thenReturn(Optional.of(saved.getValue()));
        service.startFromAgent(run,21,null,null,null,approvedReview());
        verify(mapper,times(1)).insertGrant(any());verify(funding,times(1)).freezePoints(7,3,NOW);
        assertThatThrownBy(()->service.startFromAgent(run,21,"FRIENDLY",null,null,approvedReview())).isInstanceOf(BusinessException.class);
    }
    @ParameterizedTest
    @ValueSource(strings={"帮我写篇文章","照刚才那版出成稿","不要写报价，按刚才主题生成完整新稿"})
    void directWritingRequestStillUsesItsOriginalBriefAndCannotCarryAStaleReview(String request) {
        allowNativeContinuation(request,null,null);allowNativeAcceptance();
        assertThatThrownBy(()->service.startFromAgent(run,21,null,null,null,approvedReview()))
                .isInstanceOf(BusinessException.class);
        service.startFromAgent(run,21,null,null);
        var hints=ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(articleService).prepareFullArticleBrief(eq(7L),eq(21L),isNull(),hints.capture());
        assertThat(hints.getValue().path("requestText").asText()).isEqualTo(request);
        verify(mapper,never()).writingOffer(anyLong(),anyLong(),anyLong(),anyLong(),any());
    }
    @Test void latestDraftReadsPersistedParagraphsAndVersionWithoutWriting() {
        when(mapper.latestArticle(7,31,21,3,401,NOW)).thenReturn(Optional.of(new ArticleFullMapper.LatestArticle(
                201,101,"品牌稿","<h2>公司介绍</h2><p>小行星咖啡店。</p><p>广州。</p>","公司介绍 小行星咖啡店。 广州。",
                6,"COMPLETED","SUCCEEDED","COMPLETED",NOW,false)));
        var result=service.latestArticle(7,31,21,3,401,NOW).orElseThrow();
        assertThat(result.path("bodyText").asText()).contains("公司介绍\n\n小行星咖啡店。\n\n广州。");
        assertThat(result.path("version").asInt()).isEqualTo(6);
        assertThat(result.path("bodyComplete").asBoolean()).isTrue();
        assertThat(result.path("truncated").asBoolean()).isFalse();
        assertThat(result.path("referenceOnly").asBoolean()).isTrue();
        verifyNoInteractions(funding,tasks);verify(articles,never()).preview(anyLong());
    }
    @Test void latestDraftIsBoundedAndNeverClaimsTruncatedOrFailedBodyIsComplete() {
        String body="甲".repeat(11_999)+"😀"+"乙".repeat(10);
        when(mapper.latestArticle(7,31,21,3,401,NOW)).thenReturn(Optional.of(new ArticleFullMapper.LatestArticle(
                201,101,"旧稿","",body,6,"COMPLETED","SUCCEEDED","COMPLETED",NOW,false)));
        var result=service.latestArticle(7,31,21,3,401,NOW).orElseThrow();
        assertThat(result.path("bodyText").asText()).hasSize(11_999);
        assertThat(result.path("totalCharacters").asInt()).isEqualTo(body.length());
        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(result.path("bodyComplete").asBoolean()).isFalse();
        when(mapper.latestArticle(7,31,21,3,401,NOW)).thenReturn(Optional.of(new ArticleFullMapper.LatestArticle(
                202,102,"待生成","","",2,"DRAFT","FAILED","BODY_FAILED",NOW,false)));
        result=service.latestArticle(7,31,21,3,401,NOW).orElseThrow();
        assertThat(result.path("articleId").asText()).isEqualTo("102");
        assertThat(result.path("preparationStatus").asText()).isEqualTo("FAILED");
        assertThat(result.path("bodyAvailable").asBoolean()).isFalse();
        assertThat(result.path("bodyComplete").asBoolean()).isFalse();
    }
    @Test void missingScopedArticleDoesNotFallbackToAnotherProjectOrGlobalArticleSearch() {
        assertThat(service.latestArticle(7,31,22,3,401,NOW)).isEmpty();
        verify(mapper).latestArticle(7,31,22,3,401,NOW);
        verifyNoInteractions(articles,tasks,funding);
    }
    @Test void oldDraftStillReadableForReviewButCannotRepopulateWritingReferenceAfterMemoryCutoff() throws Exception {
        allowNativeContinuation("重新生成完整稿",null,null);allowNativeAcceptance();
        when(mapper.latestArticle(7,31,21,3,401,NOW)).thenReturn(Optional.of(new ArticleFullMapper.LatestArticle(
                201,101,"旧稿","<p>已忘记的旧信息</p>","已忘记的旧信息",6,"COMPLETED","SUCCEEDED","COMPLETED",NOW,true)));
        var review=service.latestArticle(7,31,21,3,401,NOW).orElseThrow();
        assertThat(review.path("bodyText").asText()).contains("已忘记的旧信息");
        assertThat(review.path("bodyPredatesMemoryBoundary").asBoolean()).isTrue();
        service.startFromAgent(run,21,null,null);
        var saved=ArgumentCaptor.forClass(ArticleFullRecords.Preparation.class);verify(mapper).insert(saved.capture());
        assertThat(json.readTree(saved.getValue().snapshotJson()).at("/brief/authorizedContext/articleWritingReference").isMissingNode()).isTrue();
    }
    @Test void directWritingFreezesReferenceButNeverReplacesCurrentAnonymousRequest() throws Exception {
        String current="把上面内容扩成完整稿，但必须匿名中立，不提任何公司名";
        allowNativeContinuation(current,null,null);allowNativeAcceptance();
        when(mapper.writingMessages(7,31,21,3,401,NOW)).thenReturn(List.of(
                new ArticleFullMapper.WritingMessage(502,"ASSISTANT","旧建议：夸赞小行星咖啡店并投稿。",24),
                new ArticleFullMapper.WritingMessage(501,"USER","讨论广州咖啡价格、口味和便利性。",18)));
        when(mapper.latestArticle(7,31,21,3,401,NOW)).thenReturn(Optional.of(new ArticleFullMapper.LatestArticle(
                201,101,"广州咖啡", "<p>旧稿正文</p>","旧稿正文",6,"COMPLETED","SUCCEEDED","COMPLETED",NOW,false)));
        service.startFromAgent(run,21,null,null);
        var saved=ArgumentCaptor.forClass(ArticleFullRecords.Preparation.class);verify(mapper).insert(saved.capture());
        var brief=json.readTree(saved.getValue().snapshotJson()).path("brief");
        var reference=brief.at("/authorizedContext/articleWritingReference");
        assertThat(brief.at("/editorial/requestText").asText()).isEqualTo(current);
        assertThat(reference.path("requestText").asText()).startsWith(current).hasSizeLessThanOrEqualTo(4000)
                .contains("非授权","当前 USER 的全部要求优先","旧稿正文","ASSISTANT 消息 502");
        assertThat(reference.at("/sourceMessages/0/role").asText()).isEqualTo("ASSISTANT");
        assertThat(reference.at("/article/version").asInt()).isEqualTo(6);
        verify(mapper,never()).writingOffer(anyLong(),anyLong(),anyLong(),anyLong(),any());
    }
    @Test void fullCurrentRequestUsesAllBudgetAndDoesNotReadOrAppendHistory() throws Exception {
        String current="写一篇文案，"+"甲".repeat(3993);
        allowNativeContinuation(current,null,null);allowNativeAcceptance();
        service.startFromAgent(run,21,null,null);
        var saved=ArgumentCaptor.forClass(ArticleFullRecords.Preparation.class);verify(mapper).insert(saved.capture());
        var brief=json.readTree(saved.getValue().snapshotJson()).path("brief");
        assertThat(brief.at("/editorial/requestText").asText()).isEqualTo(current);
        assertThat(brief.at("/authorizedContext/articleWritingReference").isMissingNode()).isTrue();
        verify(mapper,never()).writingMessages(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),any());
    }
    @Test void longHistoricalMessagesAreMarkedTruncatedAndCannotOverflowWorkerBrief() throws Exception {
        allowNativeContinuation("重新生成完整稿",null,null);allowNativeAcceptance();
        when(mapper.writingMessages(7,31,21,3,401,NOW)).thenReturn(List.of(
                new ArticleFullMapper.WritingMessage(502,"ASSISTANT","甲".repeat(800),5000),
                new ArticleFullMapper.WritingMessage(501,"USER","乙".repeat(800),2000)));
        service.startFromAgent(run,21,null,null);
        var saved=ArgumentCaptor.forClass(ArticleFullRecords.Preparation.class);verify(mapper).insert(saved.capture());
        var reference=json.readTree(saved.getValue().snapshotJson()).at("/brief/authorizedContext/articleWritingReference");
        assertThat(reference.path("requestText").asText()).hasSizeLessThanOrEqualTo(4000).contains("[历史消息已截断]");
        assertThat(reference.at("/sourceMessages/0/truncated").asBoolean()).isTrue();
    }
    @Test void candidateFromAnotherFrozenProjectIsRejectedEvenIfMapperReturnedIt() {
        var previous=allowNativeContinuation("好",null,null);
        ObjectNode wrong=reportContext("AUTO_SINGLE_TOPIC","51","22",null);
        ((ObjectNode)wrong.path("agentIdentity")).put("projectId","22");
        when(conversations.findRun(previous)).thenReturn(Optional.of(nativeRun(previous,501,502,"COMPLETED",wrong,NOW.minusMinutes(1))));
        assertThat(service.articleContinuation(run,21)).isEmpty();
    }
    @Test void staleFailedOrOversizedSourceCannotBecomeAContinuationCandidate() {
        var previous=allowNativeContinuation("好",null,null);
        var context=reportContext("AUTO_SINGLE_TOPIC","51","21",null);
        when(conversations.findRun(previous)).thenReturn(Optional.of(nativeRun(previous,501,502,"COMPLETED",context,NOW.minusHours(25))));
        assertThat(service.articleContinuation(run,21)).isEmpty();
        when(conversations.findRun(previous)).thenReturn(Optional.of(nativeRun(previous,501,502,"FAILED",context,NOW.minusMinutes(1))));
        assertThat(service.articleContinuation(run,21)).isEmpty();
        when(conversations.findRun(previous)).thenReturn(Optional.of(nativeRun(previous,501,502,"COMPLETED",context,NOW.minusMinutes(1))));
        for(String text:List.of("我可以写一篇完整文章。"+"内容".repeat(2100),"")) {
            when(conversations.findMessage(502,7,31)).thenReturn(Optional.of(nativeMessage(502,previous,"ASSISTANT",text,2,NOW.minusMinutes(1))));
            assertThat(service.articleContinuation(run,21)).isEmpty();
        }
    }
    private com.guanghe.backend.conversation.action.RunActionDtos.ArticleContinuationReview approvedReview() {
        return new com.guanghe.backend.conversation.action.RunActionDtos.ArticleContinuationReview("401","501","502","NEW_DRAFT");
    }
    private UUID allowNativeContinuation(String confirmation,String sourceReport,String currentReport) {
        var previous=UUID.randomUUID();
        var currentContext=reportContext(currentReport==null?"AUTO_SINGLE_TOPIC":"EXPLICIT_REPORT",currentReport==null?"99":currentReport,"21",null);
        var priorContext=reportContext(sourceReport==null?"AUTO_SINGLE_TOPIC":"EXPLICIT_REPORT",sourceReport==null?"98":sourceReport,"21",null);
        when(conversations.findRun(run)).thenReturn(Optional.of(nativeRun(run,401,402,"RUNNING",currentContext,NOW)));
        when(conversations.findRun(previous)).thenReturn(Optional.of(nativeRun(previous,501,502,"COMPLETED",priorContext,NOW.minusMinutes(1))));
        when(conversations.findMessage(401,7,31)).thenReturn(Optional.of(nativeMessage(401,run,"USER",confirmation,3,NOW)));
        when(conversations.findMessage(501,7,31)).thenReturn(Optional.of(nativeMessage(501,previous,"USER","小行星咖啡店介绍",1,NOW.minusMinutes(2))));
        when(conversations.findMessage(502,7,31)).thenReturn(Optional.of(nativeMessage(502,previous,"ASSISTANT",
                "我可以写一篇完整文章，标题《广州咖啡怎么选》，结构是价格、口味、门店。不会自动监测，不投稿。你认可吗？",2,NOW.minusMinutes(1))));
        when(mapper.writingOffer(7,31,21,3,NOW)).thenReturn(Optional.of(new ArticleFullMapper.WritingOffer(502,501,previous)));
        when(mapper.lockNativeAccount(7)).thenReturn(7L);
        return previous;
    }
    private AgentRunRecord nativeRun(UUID id,long request,long response,String status,ObjectNode context,OffsetDateTime at) {
        return new AgentRunRecord(id,31,7,request,response,null,null,"[]",status,1,context.toString(),"a".repeat(64),NOW.plusHours(1),
                null,0,"{\"toolCallCount\":1}",null,"trace",null,at,"COMPLETED".equals(status)?at:null,at,at);
    }
    private ConversationMessageRecord nativeMessage(long id,UUID sourceRun,String role,String text,long sequence,OffsetDateTime at) {
        return new ConversationMessageRecord(id,31,7,role,"TEXT",json.createObjectNode().put("text",text).toString(),
                null,sourceRun,null,null,"a".repeat(64),sequence,"COMPLETED",at,at);
    }
    private void allowNativeAcceptance() {
        allowAcceptedStart();
        service.setNativeBudgets(mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class),new com.guanghe.backend.agentplatform.AgentPlatformProperties());
        when(articleService.prepareFullArticleBrief(anyLong(),anyLong(),any(),any())).thenAnswer(invocation->{
            ObjectNode facts=json.createObjectNode();facts.set("editorial",invocation.getArgument(3));facts.putObject("authorizedContext");
            return ArticleFullPreparation.freezeBrief(json,facts);
        });
    }
    private void allowStart(String role) {
        String context="{\"agentIdentity\":{\"installationId\":\"81\",\"agentKey\":\"geo\",\"agentVersion\":\"1.0.0\",\"projectId\":\"21\",\"capabilities\":[\"article.generate\"]}}";
        when(conversations.findRun(run)).thenReturn(Optional.of(new AgentRunRecord(run,31,7,401,402L,21L,null,"[]","COMPLETED",1,context,"a".repeat(64),NOW.plusHours(1),null,0,"{}",null,"trace",null,NOW,NOW,NOW,NOW)));
        when(conversations.findMessage(401,7,31)).thenReturn(Optional.of(new ConversationMessageRecord(401,31,7,role,"TEXT","{\"text\":\"小行星咖啡店介绍\"}",null,run,null,null,"a".repeat(64),1,"COMPLETED",NOW,NOW)));
    }
    private ObjectNode originalSource(String reportId) {
        ObjectNode source=json.createObjectNode().put("text","小行星咖啡店介绍").put("articlePreparationMode","ARTICLE_FULL_V1");
        if(reportId!=null)source.put("geoReportId",reportId);return source;
    }
    private ObjectNode reportContext(String selection,String reportId,String spaceId,String productId) {
        ObjectNode context=json.createObjectNode();
        context.putObject("agentIdentity").put("installationId","81").put("agentKey","geo").put("agentVersion","1.0.0")
                .put("projectId","21").putArray("capabilities").add("article.generate");
        ObjectNode report=context.putObject("geoInsightContext").put("status","READY").put("selectionSource",selection).putObject("report");
        report.put("reportId",reportId).put("spaceId",spaceId);
        if(productId==null)report.putNull("productId");else report.put("productId",productId);return context;
    }
    private void allowReportStart(ObjectNode context,ObjectNode source) {
        when(conversations.findRun(run)).thenReturn(Optional.of(new AgentRunRecord(run,31,7,401,402L,21L,null,"[]","COMPLETED",1,context.toString(),"a".repeat(64),NOW.plusHours(1),null,0,"{}",null,"trace",null,NOW,NOW,NOW,NOW)));
        when(conversations.findMessage(401,7,31)).thenReturn(Optional.of(new ConversationMessageRecord(401,31,7,"USER","TEXT",source.toString(),null,run,null,null,"a".repeat(64),1,"COMPLETED",NOW,NOW)));
        when(mapper.lockGrant(301,7,31)).thenReturn(Optional.of(grant("ACTIVE",1)));
    }
    private void allowAcceptedStart() {
        when(articleService.createForWorkflow(7,21,null,"文章准备中")).thenReturn(article(1));
        when(mapper.reservation(anyLong(),eq(7L))).thenReturn(Optional.of(reservation(3,0,0,0)));
        when(conversations.setAssistantArticlePreparation(eq(402L),eq(7L),eq(run),anyString(),eq("101"),eq(NOW))).thenReturn(1);
    }
    private void assertReportConflict(String reportId) {
        assertThatThrownBy(()->service.start(user(),31,UUID.randomUUID().toString(),request(reportId)))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        verify(articleService,never()).prepareFullArticleBrief(anyLong(),anyLong(),any(),any());
        verify(articleService,never()).createForWorkflow(anyLong(),anyLong(),any(),any());
        verify(funding,never()).lockPointAccount(anyLong());verify(funding,never()).freezePoints(anyLong(),anyInt(),any());
        verifyNoInteractions(tasks);
    }
    private void allowAdvance(ArticleFullRecords.Preparation p,List<WorkflowExecutionRecords.StepState> steps) {
        when(mapper.lock(201)).thenReturn(Optional.of(p));when(funding.lockPlanInternal(201)).thenReturn(Optional.of(mock(WorkflowExecutionRecords.PlanState.class)));
        when(funding.steps(201,7)).thenReturn(steps);
    }
    private ArticleFullDtos.StartRequest request(){return request(null);}
    private ArticleFullDtos.StartRequest request(String reportId){return new ArticleFullDtos.StartRequest(run,"301",1,"21",null,"小行星咖啡店介绍",reportId,null,null);}
    private ArticleFullRecords.Grant grant(String status,int version){return new ArticleFullRecords.Grant(301,7,31,81,"geo","1.0.0",21L,21,null,version,status,100,NOW.plusDays(1),NOW,NOW);}
    private ArticleFullRecords.Preparation preparation(int version){return preparation(version,snapshot.deadlineAt());}
    private ArticleFullRecords.Preparation preparation(int version,OffsetDateTime deadline){return new ArticleFullRecords.Preparation(201,7,31,301,1,101,version,run,401,UUID.randomUUID(),"a".repeat(64),snapshot.canonicalJson(),snapshot.briefHash(),6,768000,60000,deadline,"RUNNING",null,NOW,NOW);}
    private WorkflowExecutionRecords.Reservation reservation(int frozen,int allocated,int settled,int released){return new WorkflowExecutionRecords.Reservation(801,201,7,3,frozen,allocated,settled,released,"PARTIAL",NOW,NOW);}
    private ArticleRecords.Article article(int version){return new ArticleRecords.Article(101,7,21,null,"标题","","","[]","DRAFT",null,version,NOW,NOW);}
    private WorkflowExecutionRecords.StepState step(int n,String status,Long task){return new WorkflowExecutionRecords.StepState(900+n,201,7,31,"step-"+n,n,"PREPARE","task_draft.propose","1","PREPARE",n==1?"[]":"[\"step-"+(n-1)+"\"]","[]","[]",null,task==null?null:400L+n,"ARTICLE",properties.getArticleTestPricingVersionId(),1,status,task==null?0:1,task,null,task==null?null:NOW,status.equals("SUCCEEDED")?NOW:null);}
    private TaskRecords.Task task(String status,int settled){return new TaskRecords.Task(501,7,21,null,31,401,"ARTICLE",status,"ARTICLE_GENERATING",2,"{}","a".repeat(64),properties.getArticleTestPricingVersionId(),1,settled,"{}",null,"WORKFLOW_STEP_ACTIVATE",UUID.randomUUID(),"a".repeat(64),NOW,NOW,NOW,NOW,NOW);}
    private AuthenticatedAccount user(){return new AuthenticatedAccount(7,"tester",AccountRole.USER,1,AccountStatus.ACTIVE,null,"tester",null);}
    private <T>T writes(Class<T> type){return mock(type,invocation->{if(invocation.getMethod().getReturnType()==int.class)return 1;if(invocation.getMethod().getReturnType()==long.class)return 1L;return Answers.RETURNS_DEFAULTS.answer(invocation);});}
}
