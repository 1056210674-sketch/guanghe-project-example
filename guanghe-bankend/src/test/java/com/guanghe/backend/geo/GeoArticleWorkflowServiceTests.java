package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.guanghe.backend.article.ArticleRecords;
import com.guanghe.backend.article.ArticleService;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.ConversationRecord;
import com.guanghe.backend.conversation.resource.RunResourceDtos;
import com.guanghe.backend.conversation.resource.RunResourceService;
import com.guanghe.backend.task.TaskControlService;
import com.guanghe.backend.task.TaskDtos;
import com.guanghe.backend.task.TaskRecords;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class GeoArticleWorkflowServiceTests {
    private static final long USER_ID = 41;
    private static final long REPORT_ID = 501;
    private static final long TOPIC_ID = 601;
    private static final long SPACE_ID = 701;
    private static final long PRODUCT_ID = 801;
    private static final long CONVERSATION_ID = 901;
    private static final long REQUESTED_CONVERSATION_ID = 902;
    private static final long ARTICLE_ID = 1001;

    private final GeoTaskMapper reports = mock(GeoTaskMapper.class);
    private final GeoMapper topics = mock(GeoMapper.class);
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final ArticleService articles = mock(ArticleService.class);
    private final TaskControlService taskControl = mock(TaskControlService.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private GeoArticleWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new GeoArticleWorkflowService(reports, topics, conversations, articles,
                taskControl, ids, new ObjectMapper());
        when(reports.lockOwner(USER_ID)).thenReturn(Optional.of(USER_ID));
        when(reports.lockReport(REPORT_ID, USER_ID)).thenReturn(Optional.of(REPORT_ID));
        when(reports.findReport(REPORT_ID, USER_ID)).thenReturn(Optional.of(report()));
        when(reports.findRun(502, USER_ID)).thenReturn(Optional.of(new GeoTaskRecords.Run(
                502,503,USER_ID,SPACE_ID,PRODUCT_ID,TOPIC_ID,"SUCCEEDED","STANDARD",10,10,0,0,
                "{}","geo-metric-v1","geo-synthesis-v1",OffsetDateTime.now(),OffsetDateTime.now(),
                OffsetDateTime.now(),OffsetDateTime.now(),30,30,0)));
        when(topics.findTopic(TOPIC_ID, USER_ID)).thenReturn(Optional.of(topic()));
        when(taskControl.findGeoArticleDraftByIdempotency(eq(USER_ID), any())).thenReturn(Optional.empty());
        when(taskControl.lockGeoArticleDraftByIdentity(USER_ID, REPORT_ID, null)).thenReturn(Optional.empty());
        when(taskControl.lockActiveArticleDraftInConversation(eq(USER_ID), anyLong(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void deterministicOrArchivedReportCannotCreateAnyArticle() {
        var source = report();
        for (boolean archived : new boolean[] {false, true}) {
            var incomplete = new GeoTaskRecords.Report(source.id(), source.runId(), source.taskId(),
                    source.userId(), source.topicId(), "SUCCEEDED", source.synthesisJson(),
                    source.synthesisUsageJson(), source.summary(),
                    archived ? "geo-synthesis-v1" : "geo-deterministic-v1", source.sampleSize(),
                    source.generatedAt(), source.createdAt(), source.settledPoints(), source.releasedPoints(),
                    archived ? source.generatedAt() : null);
            when(reports.findReport(REPORT_ID, USER_ID)).thenReturn(Optional.of(incomplete));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user(), REPORT_ID,
                    new GeoArticleWorkflowDtos.CreateRequest(null, null), UUID.randomUUID().toString()))
                    .isInstanceOf(com.guanghe.backend.common.api.BusinessException.class)
                    .hasMessageContaining("分析尚未完成或已归档");
        }
        verify(articles, never()).createForWorkflow(anyLong(), anyLong(), any(), anyString());
        verify(conversations, never()).insertConversation(any());
    }

    @Test
    void createsConversationArticleAndTitleDraftInTheServerOwnedWorkflow() {
        when(ids.nextId()).thenReturn(CONVERSATION_ID);
        when(conversations.insertConversation(any())).thenReturn(1);
        when(conversations.updateConversationSelection(eq(CONVERSATION_ID), eq(USER_ID),
                eq(SPACE_ID), eq(PRODUCT_ID), any())).thenReturn(1);
        when(articles.createForWorkflow(USER_ID, SPACE_ID, PRODUCT_ID, ""))
                .thenReturn(article());
        when(taskControl.createGeoArticleDraft(eq(USER_ID), eq(CONVERSATION_ID), eq(SPACE_ID),
                eq(PRODUCT_ID), eq(ARTICLE_ID), eq(REPORT_ID), anyString(), any(), eq(null), anyString()))
                .thenReturn(draftResponse(1101));

        GeoArticleWorkflowDtos.Response response = service.create(
                user(), REPORT_ID, new GeoArticleWorkflowDtos.CreateRequest(null, null),
                UUID.randomUUID().toString());

        assertThat(response.articleId()).isEqualTo(Long.toString(ARTICLE_ID));
        assertThat(response.conversationId()).isEqualTo(Long.toString(CONVERSATION_ID));
        assertThat(response.reused()).isFalse();
        verify(taskControl, never()).lockActiveArticleDraftInConversation(anyLong(), anyLong(), any());
        ArgumentCaptor<String> brief = ArgumentCaptor.forClass(String.class);
        verify(taskControl).createGeoArticleDraft(eq(USER_ID), eq(CONVERSATION_ID), eq(SPACE_ID),
                eq(PRODUCT_ID), eq(ARTICLE_ID), eq(REPORT_ID), brief.capture(), any(), eq(null), anyString());
        assertThat(brief.getValue()).contains("可见度不足", "不得写成平台官方排名或全网事实");
    }

    @Test
    void analyzedReportWithoutRecommendationsCanStillPrepareAnExplicitArticleDraft() {
        var source = report();
        var withoutRecommendations = new GeoTaskRecords.Report(source.id(), source.runId(), source.taskId(),
                source.userId(), source.topicId(), source.status(),
                "{\"findings\":[\"未观察到名称提及\"],\"recommendations\":[],\"contentBlueprint\":[],\"coverageGaps\":[],\"missingSamples\":[]}",
                source.synthesisUsageJson(), source.summary(), source.methodVersion(), source.sampleSize(),
                source.generatedAt(), source.createdAt(), source.settledPoints(), source.releasedPoints());
        when(reports.findReport(REPORT_ID, USER_ID)).thenReturn(Optional.of(withoutRecommendations));
        when(ids.nextId()).thenReturn(CONVERSATION_ID);
        when(conversations.insertConversation(any())).thenReturn(1);
        when(conversations.updateConversationSelection(eq(CONVERSATION_ID), eq(USER_ID),
                eq(SPACE_ID), eq(PRODUCT_ID), any())).thenReturn(1);
        when(articles.createForWorkflow(USER_ID, SPACE_ID, PRODUCT_ID, "")).thenReturn(article());
        when(taskControl.createGeoArticleDraft(eq(USER_ID), eq(CONVERSATION_ID), eq(SPACE_ID),
                eq(PRODUCT_ID), eq(ARTICLE_ID), eq(REPORT_ID), anyString(), any(), eq(null), anyString()))
                .thenReturn(draftResponse(1101));

        var response = service.create(user(), REPORT_ID, new GeoArticleWorkflowDtos.CreateRequest(null, null),
                UUID.randomUUID().toString());

        assertThat(response.draftId()).isEqualTo("1101");
        assertThat(response.reused()).isFalse();
        verify(taskControl).createGeoArticleDraft(eq(USER_ID), eq(CONVERSATION_ID), eq(SPACE_ID),
                eq(PRODUCT_ID), eq(ARTICLE_ID), eq(REPORT_ID), anyString(), any(), eq(null), anyString());
    }

    @Test
    void reassigningTopicDoesNotRebindHistoricalReportArticleToAnotherProduct() {
        var original = topic();
        when(topics.findTopic(TOPIC_ID,USER_ID)).thenReturn(Optional.of(new GeoRecords.Topic(
                original.id(),original.userId(),original.spaceId(),999L,original.name(),original.nameNormalized(),
                original.region(),original.version()+1,original.createdAt(),original.updatedAt())));
        when(ids.nextId()).thenReturn(CONVERSATION_ID);
        when(conversations.insertConversation(any())).thenReturn(1);
        when(conversations.updateConversationSelection(eq(CONVERSATION_ID),eq(USER_ID),
                eq(SPACE_ID),eq(PRODUCT_ID),any())).thenReturn(1);
        when(articles.createForWorkflow(USER_ID,SPACE_ID,PRODUCT_ID,"")).thenReturn(article());
        when(taskControl.createGeoArticleDraft(eq(USER_ID),eq(CONVERSATION_ID),eq(SPACE_ID),eq(PRODUCT_ID),
                eq(ARTICLE_ID),eq(REPORT_ID),anyString(),any(),eq(null),anyString())).thenReturn(draftResponse(1101));

        var response = service.create(user(),REPORT_ID,new GeoArticleWorkflowDtos.CreateRequest(null,null),
                UUID.randomUUID().toString());

        assertThat(response.draft().productId()).isEqualTo(Long.toString(PRODUCT_ID));
        verify(articles).createForWorkflow(USER_ID,SPACE_ID,PRODUCT_ID,"");
        verify(articles,never()).createForWorkflow(USER_ID,SPACE_ID,999L,"");
    }

    @Test
    void missingAuthorizedFrozenRunDoesNotFallBackToMutableTopicScope() {
        when(reports.findRun(502,USER_ID)).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user(),REPORT_ID,
                new GeoArticleWorkflowDtos.CreateRequest(null,null),UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(com.guanghe.backend.common.api.BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.guanghe.backend.common.api.ErrorCode.RESOURCE_NOT_FOUND));
        verify(articles,never()).createForWorkflow(anyLong(),anyLong(),any(),anyString());
        verify(conversations,never()).insertConversation(any());
    }

    @Test
    void expiredDraftRenewsAgainstTheSameArticleInsteadOfCreatingAnOrphan() {
        TaskRecords.Draft expired = storedDraft(1100, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(taskControl.lockGeoArticleDraftByIdentity(USER_ID, REPORT_ID, null))
                .thenReturn(Optional.of(expired));
        when(taskControl.createGeoArticleDraft(eq(USER_ID), eq(CONVERSATION_ID), eq(SPACE_ID),
                eq(PRODUCT_ID), eq(ARTICLE_ID), eq(REPORT_ID), anyString(), any(), eq(null), anyString()))
                .thenReturn(draftResponse(1102));

        GeoArticleWorkflowDtos.Response response = service.create(
                user(), REPORT_ID, new GeoArticleWorkflowDtos.CreateRequest(null, null),
                UUID.randomUUID().toString());

        assertThat(response.draftId()).isEqualTo("1102");
        assertThat(response.articleId()).isEqualTo(Long.toString(ARTICLE_ID));
        verify(taskControl).lockActiveArticleDraftInConversation(eq(USER_ID), eq(CONVERSATION_ID), any());
        verify(articles, never()).createForWorkflow(anyLong(), anyLong(), any(), anyString());
        verify(conversations, never()).insertConversation(any());
    }

    @Test
    void expiredDraftCanResumeInTheConversationThatRequestedTheWorkflow() {
        TaskRecords.Draft expired = storedDraft(1100, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(conversations.findConversation(REQUESTED_CONVERSATION_ID, USER_ID))
                .thenReturn(Optional.of(mock(com.guanghe.backend.conversation.ConversationRecord.class)));
        when(taskControl.lockGeoArticleDraftByIdentity(USER_ID, REPORT_ID, null))
                .thenReturn(Optional.of(expired));
        when(taskControl.createGeoArticleDraft(eq(USER_ID), eq(REQUESTED_CONVERSATION_ID), eq(SPACE_ID),
                eq(PRODUCT_ID), eq(ARTICLE_ID), eq(REPORT_ID), anyString(), any(), eq(null), anyString()))
                .thenReturn(draftResponse(1103));

        GeoArticleWorkflowDtos.Response response = service.create(
                user(), REPORT_ID,
                new GeoArticleWorkflowDtos.CreateRequest(null, Long.toString(REQUESTED_CONVERSATION_ID)),
                UUID.randomUUID().toString());

        assertThat(response.conversationId()).isEqualTo(Long.toString(REQUESTED_CONVERSATION_ID));
        verify(taskControl).lockActiveArticleDraftInConversation(eq(USER_ID), eq(REQUESTED_CONVERSATION_ID), any());
        verify(taskControl, never()).lockActiveArticleDraftInConversation(eq(USER_ID), eq(CONVERSATION_ID), any());
        verify(taskControl).createGeoArticleDraft(eq(USER_ID), eq(REQUESTED_CONVERSATION_ID), eq(SPACE_ID),
                eq(PRODUCT_ID), eq(ARTICLE_ID), eq(REPORT_ID), anyString(), any(), eq(null), anyString());
    }

    @Test
    void aDifferentConversationCanStartWhileTheOwnerHasOlderConfirmedArticleWork() {
        when(conversations.findConversation(REQUESTED_CONVERSATION_ID, USER_ID))
                .thenReturn(Optional.of(mock(com.guanghe.backend.conversation.ConversationRecord.class)));
        // The historical conversation can have a confirmed/succeeded or failed generation.
        // It must never be queried to decide whether this independent destination is available.
        when(taskControl.lockActiveArticleDraftInConversation(eq(USER_ID), eq(CONVERSATION_ID), any()))
                .thenReturn(Optional.of(storedDraft(1100, OffsetDateTime.now().minusDays(20), "CONFIRMED")));
        when(conversations.updateConversationSelection(eq(REQUESTED_CONVERSATION_ID), eq(USER_ID),
                eq(SPACE_ID), eq(PRODUCT_ID), any())).thenReturn(1);
        when(articles.createForWorkflow(USER_ID, SPACE_ID, PRODUCT_ID, "")).thenReturn(article());
        when(taskControl.createGeoArticleDraft(eq(USER_ID), eq(REQUESTED_CONVERSATION_ID), eq(SPACE_ID),
                eq(PRODUCT_ID), eq(ARTICLE_ID), eq(REPORT_ID), anyString(), any(), eq(null), anyString()))
                .thenReturn(draftResponse(1101));

        var result = service.create(user(), REPORT_ID,
                new GeoArticleWorkflowDtos.CreateRequest(null, Long.toString(REQUESTED_CONVERSATION_ID)),
                UUID.randomUUID().toString());

        assertThat(result.reused()).isFalse();
        assertThat(result.conversationId()).isEqualTo(Long.toString(REQUESTED_CONVERSATION_ID));
        verify(taskControl).lockActiveArticleDraftInConversation(eq(USER_ID), eq(REQUESTED_CONVERSATION_ID), any());
        verify(taskControl, never()).lockActiveArticleDraftInConversation(eq(USER_ID), eq(CONVERSATION_ID), any());
    }

    @Test
    void pendingOrActuallyExecutingArticleInTheDestinationStillConflicts() {
        when(conversations.findConversation(CONVERSATION_ID, USER_ID))
                .thenReturn(Optional.of(mock(com.guanghe.backend.conversation.ConversationRecord.class)));
        for (String status : java.util.List.of("WAITING_CONFIRMATION", "CONFIRMED")) {
            // The mapper returns CONFIRMED only when its actual business task is still executing.
            when(taskControl.lockActiveArticleDraftInConversation(eq(USER_ID), eq(CONVERSATION_ID), any()))
                    .thenReturn(Optional.of(storedDraft(1100, OffsetDateTime.now().plusMinutes(10), status)));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user(), REPORT_ID,
                    new GeoArticleWorkflowDtos.CreateRequest(null, Long.toString(CONVERSATION_ID)),
                    UUID.randomUUID().toString()))
                    .isInstanceOfSatisfying(com.guanghe.backend.common.api.BusinessException.class,
                            exception -> assertThat(exception.errorCode())
                                    .isEqualTo(com.guanghe.backend.common.api.ErrorCode.RESOURCE_IN_USE));
        }
        verify(articles, never()).createForWorkflow(anyLong(), anyLong(), any(), anyString());
        verify(taskControl, never()).createGeoArticleDraft(anyLong(), anyLong(), anyLong(), any(),
                anyLong(), anyLong(), anyString(), any(), any(), anyString());
    }

    @Test
    void sameReportIdentityStillRedirectsAnotherConversationInsteadOfDuplicatingTheArticle() {
        when(conversations.findConversation(REQUESTED_CONVERSATION_ID, USER_ID))
                .thenReturn(Optional.of(mock(com.guanghe.backend.conversation.ConversationRecord.class)));
        when(taskControl.lockGeoArticleDraftByIdentity(USER_ID, REPORT_ID, null))
                .thenReturn(Optional.of(storedDraft(1100, OffsetDateTime.now().plusMinutes(10))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user(), REPORT_ID,
                new GeoArticleWorkflowDtos.CreateRequest(null, Long.toString(REQUESTED_CONVERSATION_ID)),
                UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(com.guanghe.backend.common.api.BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.guanghe.backend.common.api.ErrorCode.RESOURCE_IN_USE));
        verify(taskControl, never()).lockActiveArticleDraftInConversation(anyLong(), anyLong(), any());
        verify(articles, never()).createForWorkflow(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void sameReportAndConversationStillReuseTheOriginalWorkflow() {
        when(conversations.findConversation(CONVERSATION_ID, USER_ID))
                .thenReturn(Optional.of(mock(com.guanghe.backend.conversation.ConversationRecord.class)));
        when(taskControl.lockGeoArticleDraftByIdentity(USER_ID, REPORT_ID, null))
                .thenReturn(Optional.of(storedDraft(1100, OffsetDateTime.now().plusMinutes(10))));
        when(taskControl.getDraftForWorkflow(1100, USER_ID)).thenReturn(draftResponse(1100));

        var result = service.create(user(), REPORT_ID,
                new GeoArticleWorkflowDtos.CreateRequest(null, Long.toString(CONVERSATION_ID)),
                UUID.randomUUID().toString());

        assertThat(result.reused()).isTrue();
        assertThat(result.draftId()).isEqualTo("1100");
        assertThat(result.articleId()).isEqualTo(Long.toString(ARTICLE_ID));
        verify(taskControl, never()).lockActiveArticleDraftInConversation(anyLong(), anyLong(), any());
        verify(articles, never()).createForWorkflow(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void activeArticleQueryBindsDestinationAndUsesTaskLivenessNotPermanentConfirmation() {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(com.guanghe.backend.task.TaskControlMapper.class);
        var statement = configuration.getMappedStatement(
                "com.guanghe.backend.task.TaskControlMapper.lockActiveArticleDraftInConversation");
        var bound = statement.getBoundSql(java.util.Map.of("userId", USER_ID,
                "conversationId", REQUESTED_CONVERSATION_ID, "now", OffsetDateTime.now()));

        assertThat(bound.getParameterMappings()).extracting(org.apache.ibatis.mapping.ParameterMapping::getProperty)
                .containsExactly("userId", "conversationId", "now");
        assertThat(bound.getSql()).contains("d.user_id=? AND d.conversation_id=?",
                "d.status IN ('EDITING','WAITING_CONFIRMATION') AND d.expires_at>?",
                "t.user_id=d.user_id", "t.draft_id=d.id", "t.task_type='ARTICLE'",
                "t.status IN ('QUEUED','RUNNING','RETRY_WAIT')", "FOR UPDATE");
        // Succeeded, failed and expired history has no unconditional branch reserving the account.
        assertThat(bound.getSql()).doesNotContain("d.status='CONFIRMED'", "workflow_stage<>",
                "'SUCCEEDED'", "'PARTIAL'", "'EXPIRED'");
    }

    @Test
    void observedReportUsesFrozenLabelsAndDirectionWithoutChangingExistingSidebar() {
        var resources=mock(RunResourceService.class); service.setResourceLookup(resources);
        var conversation=new ConversationRecord(CONVERSATION_ID,USER_ID,"chat",99L,98L,1,null,OffsetDateTime.now(),OffsetDateTime.now());
        when(conversations.findConversation(CONVERSATION_ID,USER_ID)).thenReturn(Optional.of(conversation));
        when(conversations.lockConversation(CONVERSATION_ID,USER_ID)).thenReturn(Optional.of(conversation));
        when(resources.requireOwnedObservationForAction(USER_ID,CONVERSATION_ID,"1501",REPORT_ID))
                .thenReturn(new RunResourceDtos.ReadResponse("1501","GEO_TOPIC","LATEST_GEO_REPORT",Long.toString(TOPIC_ID),"旧专题",
                        Long.toString(SPACE_ID),Long.toString(PRODUCT_ID),OffsetDateTime.now(),"c".repeat(64),
                        Long.toString(REPORT_ID),null,JsonNodeFactory.instance.objectNode(),false));
        frozenRun();
        when(articles.createForWorkflow(USER_ID,SPACE_ID,PRODUCT_ID,"")).thenReturn(article());
        when(taskControl.createGeoArticleDraft(eq(USER_ID),eq(CONVERSATION_ID),eq(SPACE_ID),eq(PRODUCT_ID),eq(ARTICLE_ID),eq(REPORT_ID),
                anyString(),any(),eq(null),anyString())).thenReturn(draftResponse(1101));

        var response=service.create(user(),REPORT_ID,new GeoArticleWorkflowDtos.CreateRequest(null,Long.toString(CONVERSATION_ID),"1501","写给刚入职的年轻人"),UUID.randomUUID().toString());

        assertThat(response.articleId()).isEqualTo("1001");
        ArgumentCaptor<String> brief=ArgumentCaptor.forClass(String.class);
        verify(taskControl).createGeoArticleDraft(eq(USER_ID),eq(CONVERSATION_ID),eq(SPACE_ID),eq(PRODUCT_ID),eq(ARTICLE_ID),eq(REPORT_ID),
                brief.capture(),any(),eq(null),anyString());
        assertThat(brief.getValue()).contains("原始咖啡专题","原始咖啡商品","写给刚入职的年轻人","不保证发文后可见度上涨","当前资料与用户补充");
        verify(conversations,never()).updateConversationSelection(anyLong(),anyLong(),any(),any(),any());
    }

    @Test
    void directionChangeRevisesOnlyUnconfirmedTitleAndKeepsOriginalArticle() {
        var pending=withParameters(storedDraft(1100,OffsetDateTime.now().plusMinutes(10)),
                "{\"mode\":\"TITLE\",\"articleId\":\"1001\",\"geoReportId\":\"501\",\"brief\":\"old\"}","a".repeat(64));
        when(conversations.findConversation(CONVERSATION_ID,USER_ID)).thenReturn(Optional.of(mock(ConversationRecord.class)));
        when(taskControl.lockGeoArticleDraftByIdentity(USER_ID,REPORT_ID,null)).thenReturn(Optional.of(pending));
        when(taskControl.reviseGeoArticleDraft(eq(user()),eq(1100L),any(),anyString(),anyString())).thenReturn(draftResponse(1102));
        var response=service.create(user(),REPORT_ID,new GeoArticleWorkflowDtos.CreateRequest(null,"901",null,"突出通勤场景"),UUID.randomUUID().toString());
        assertThat(response.draftId()).isEqualTo("1102");
        assertThat(response.reused()).isFalse();
        verify(taskControl).reviseGeoArticleDraft(eq(user()),eq(1100L),any(),anyString(),anyString());
        verify(articles,never()).createForWorkflow(anyLong(),anyLong(),any(),anyString());
    }

    @Test
    void confirmedAndLaterStageArticlesIgnoreEntryDirectionAndReuseExistingFlow() {
        when(conversations.findConversation(CONVERSATION_ID,USER_ID)).thenReturn(Optional.of(mock(ConversationRecord.class)));
        when(taskControl.getDraftForWorkflow(1100,USER_ID)).thenReturn(draftResponse(1100));
        for (String stage : java.util.List.of("CONFIRMED","BODY","REWRITE")) {
            var original=storedDraft(1100,OffsetDateTime.now().minusMinutes(10),stage.equals("CONFIRMED")?stage:"WAITING_CONFIRMATION");
            var stored=withParameters(original,"{\"mode\":\""+(stage.equals("CONFIRMED")?"TITLE":stage)+"\",\"articleId\":\"1001\",\"geoReportId\":\"501\"}",original.requestHash());
            when(taskControl.lockGeoArticleDraftByIdentity(USER_ID,REPORT_ID,null)).thenReturn(Optional.of(stored));
            var response=service.create(user(),REPORT_ID,new GeoArticleWorkflowDtos.CreateRequest(null,"901",null,"重写方向"),UUID.randomUUID().toString());
            assertThat(response.reused()).isTrue();
            assertThat(response.draftId()).isEqualTo("1100");
        }
        verify(taskControl,never()).reviseGeoArticleDraft(any(),anyLong(),any(),anyString(),anyString());
        verify(taskControl,never()).createGeoArticleDraft(anyLong(),anyLong(),anyLong(),any(),anyLong(),anyLong(),anyString(),any(),any(),anyString());
    }

    @Test
    void recommendationTitleResumesLatestExpiredBodyInsteadOfRenewingTitleVersionOne() {
        String suggestion=REPORT_ID+":recommendation:1";
        var title=withParameters(storedDraft(1100,OffsetDateTime.now().minusDays(1),"CONFIRMED"),
                "{\"mode\":\"TITLE\",\"articleId\":\"1001\",\"articleVersion\":1,\"geoReportId\":\"501\",\"geoRecommendationId\":\"501:recommendation:1\"}","a".repeat(64));
        var body=withParameters(storedDraft(1102,OffsetDateTime.now().minusMinutes(10),"EXPIRED"),
                "{\"mode\":\"BODY\",\"articleId\":\"1001\",\"articleVersion\":3,\"geoReportId\":\"501\",\"geoRecommendationId\":null}","b".repeat(64));
        when(conversations.findConversation(CONVERSATION_ID,USER_ID)).thenReturn(Optional.of(mock(ConversationRecord.class)));
        when(taskControl.lockGeoArticleDraftByIdentity(USER_ID,REPORT_ID,suggestion)).thenReturn(Optional.of(title));
        when(taskControl.lockLatestArticleDraftByArticle(USER_ID,ARTICLE_ID)).thenReturn(Optional.of(body));
        when(taskControl.getDraftForWorkflow(1102,USER_ID)).thenReturn(draftResponse(1102));

        var response=service.create(user(),REPORT_ID,new GeoArticleWorkflowDtos.CreateRequest(suggestion,"901",null,"换个方向"),UUID.randomUUID().toString());

        assertThat(response.reused()).isTrue();
        assertThat(response.articleId()).isEqualTo("1001");
        assertThat(response.draftId()).isEqualTo("1102");
        assertThat(response.suggestionId()).isNull();
        verify(taskControl,never()).reviseGeoArticleDraft(any(),anyLong(),any(),anyString(),anyString());
        verify(taskControl,never()).createGeoArticleDraft(anyLong(),anyLong(),anyLong(),any(),anyLong(),anyLong(),anyString(),any(),any(),anyString());
        verify(articles,never()).createForWorkflow(anyLong(),anyLong(),any(),anyString());
    }

    @Test
    void articleLatestStageWithContradictoryReportCannotHideBehindOldTitle() {
        var original=storedDraft(1100,OffsetDateTime.now().minusDays(1),"CONFIRMED");
        var inconsistent=withParameters(storedDraft(1102,OffsetDateTime.now().minusMinutes(10),"EXPIRED"),
                "{\"mode\":\"BODY\",\"articleId\":\"1001\",\"geoReportId\":\"502\"}","b".repeat(64));
        when(taskControl.lockGeoArticleDraftByIdentity(USER_ID,REPORT_ID,null)).thenReturn(Optional.of(original));
        when(taskControl.lockLatestArticleDraftByArticle(USER_ID,ARTICLE_ID)).thenReturn(Optional.of(inconsistent));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user(),REPORT_ID,
                new GeoArticleWorkflowDtos.CreateRequest(null,null),UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(com.guanghe.backend.common.api.BusinessException.class,e -> assertThat(e.errorCode())
                        .isEqualTo(com.guanghe.backend.common.api.ErrorCode.TASK_STATE_CONFLICT));
        verify(taskControl,never()).getDraftForWorkflow(anyLong(),anyLong());
        verify(taskControl,never()).createGeoArticleDraft(anyLong(),anyLong(),anyLong(),any(),anyLong(),anyLong(),anyString(),any(),any(),anyString());
    }

    @Test
    void latestArticleStageQueryUsesOwnerAndArticleNotRecommendationOrExpiry() {
        var configuration=new org.apache.ibatis.session.Configuration();
        configuration.addMapper(com.guanghe.backend.task.TaskControlMapper.class);
        var statement=configuration.getMappedStatement("com.guanghe.backend.task.TaskControlMapper.lockLatestArticleDraftByArticle");
        var bound=statement.getBoundSql(java.util.Map.of("userId",USER_ID,"articleId",Long.toString(ARTICLE_ID)));
        assertThat(bound.getParameterMappings()).extracting(org.apache.ibatis.mapping.ParameterMapping::getProperty)
                .containsExactly("userId","articleId");
        assertThat(bound.getSql()).contains("a.user_id=d.user_id","d.user_id=?","d.parameters->>'articleId'=?",
                "d.status<>'ABANDONED'","a.status NOT IN ('FAILED','ARCHIVED')","ORDER BY d.created_at DESC,d.id DESC","FOR UPDATE");
        assertThat(bound.getSql()).doesNotContain("geoRecommendationId","expires_at>","geoReportId'=");
    }

    @Test
    void workflowReplayHashDependsOnRequestNotRenamedTopicOrChangedAiBrief() {
        UUID key=UUID.randomUUID();
        when(conversations.findConversation(CONVERSATION_ID,USER_ID)).thenReturn(Optional.of(mock(ConversationRecord.class)));
        when(articles.createForWorkflow(USER_ID,SPACE_ID,PRODUCT_ID,"")).thenReturn(article());
        when(taskControl.createGeoArticleDraft(eq(USER_ID),eq(CONVERSATION_ID),eq(SPACE_ID),eq(PRODUCT_ID),eq(ARTICLE_ID),eq(REPORT_ID),
                anyString(),any(),eq(null),anyString())).thenReturn(draftResponse(1101));
        var request=new GeoArticleWorkflowDtos.CreateRequest(null,"901",null,"介绍通勤用途");
        service.create(user(),REPORT_ID,request,key.toString());
        ArgumentCaptor<String> hash=ArgumentCaptor.forClass(String.class);
        verify(taskControl).createGeoArticleDraft(eq(USER_ID),eq(CONVERSATION_ID),eq(SPACE_ID),eq(PRODUCT_ID),eq(ARTICLE_ID),eq(REPORT_ID),
                anyString(),eq(key),eq(null),hash.capture());
        var stored=withParameters(storedDraft(1101,OffsetDateTime.now().plusMinutes(10)),"{\"mode\":\"TITLE\",\"articleId\":\"1001\",\"geoReportId\":\"501\"}",hash.getValue());
        when(taskControl.findGeoArticleDraftByIdempotency(USER_ID,key)).thenReturn(Optional.of(stored));
        when(taskControl.getDraftForWorkflow(1101,USER_ID)).thenReturn(draftResponse(1101));
        var topic=topic();
        when(topics.findTopic(TOPIC_ID,USER_ID)).thenReturn(Optional.of(new GeoRecords.Topic(topic.id(),topic.userId(),topic.spaceId(),topic.productId(),
                "后来修改的标签","later",topic.region(),2,topic.createdAt(),topic.updatedAt())));
        var report=report();
        when(reports.findReport(REPORT_ID,USER_ID)).thenReturn(Optional.of(new GeoTaskRecords.Report(report.id(),report.runId(),report.taskId(),report.userId(),report.topicId(),report.status(),
                report.synthesisJson(),report.synthesisUsageJson(),"新的总结文本",report.methodVersion(),report.sampleSize(),report.generatedAt(),report.createdAt(),report.settledPoints(),report.releasedPoints())));
        assertThat(service.create(user(),REPORT_ID,request,key.toString()).reused()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user(),REPORT_ID,
                new GeoArticleWorkflowDtos.CreateRequest(null,"901",null,"换个方向"),key.toString()))
                .isInstanceOfSatisfying(com.guanghe.backend.common.api.BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(com.guanghe.backend.common.api.ErrorCode.IDEMPOTENCY_KEY_REUSED));
        verify(articles,times(1)).createForWorkflow(anyLong(),anyLong(),any(),anyString());
    }

    @Test
    void invalidObservationOrOversizedDirectionNeverPreparesAnyArticle() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user(),REPORT_ID,
                new GeoArticleWorkflowDtos.CreateRequest(null,null,"1501",null),UUID.randomUUID().toString()))
                .isInstanceOf(com.guanghe.backend.common.api.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(user(),REPORT_ID,
                new GeoArticleWorkflowDtos.CreateRequest(null,null,null,"长".repeat(801)),UUID.randomUUID().toString()))
                .isInstanceOf(com.guanghe.backend.common.api.BusinessException.class);
        verify(articles,never()).createForWorkflow(anyLong(),anyLong(),any(),anyString());
    }

    private void frozenRun() {
        OffsetDateTime now=OffsetDateTime.now();
        when(reports.findRun(502,USER_ID)).thenReturn(Optional.of(new GeoTaskRecords.Run(502,503,USER_ID,SPACE_ID,PRODUCT_ID,TOPIC_ID,"SUCCEEDED","STANDARD",10,10,0,0,
                "{\"authorizedContext\":{\"topic\":{\"id\":\"601\",\"name\":\"原始咖啡专题\"}},\"catalogSnapshot\":{\"space\":{\"id\":\"701\"},\"product\":{\"id\":\"801\",\"spaceId\":\"701\",\"name\":\"原始咖啡商品\"}}}",
                "geo-metric-v1","geo-synthesis-v1",now,now,now,now,30,30,0)));
    }

    private TaskRecords.Draft withParameters(TaskRecords.Draft d,String parameters,String requestHash) {
        return new TaskRecords.Draft(d.id(),d.userId(),d.conversationId(),d.agentRunId(),d.previousDraftId(),d.draftSource(),d.operationScope(),d.idempotencyKey(),requestHash,
                d.taskType(),d.parameterSchemaVersion(),d.spaceId(),d.productId(),parameters,d.inputSnapshotJson(),d.inputHash(),d.pricingVersionId(),d.quotedPoints(),d.warningsJson(),
                d.status(),d.expiresAt(),d.confirmedAt(),d.createdAt(),d.updatedAt());
    }

    private GeoTaskRecords.Report report() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String synthesis = """
                {"contentBlueprint":["通勤场景"],"findings":["可见度不足"],
                 "coverageGaps":[],"recommendations":[{"title":"补齐内容","rationale":"覆盖核心问法"}],
                 "missingSamples":[]}
                """;
        return new GeoTaskRecords.Report(REPORT_ID, 502, 503, USER_ID, TOPIC_ID, "SUCCEEDED",
                synthesis, "{}", "可见度不足", "geo-synthesis-v1", 10, now, now, 30, 0);
    }

    private GeoRecords.Topic topic() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new GeoRecords.Topic(TOPIC_ID, USER_ID, SPACE_ID, PRODUCT_ID, "美式咖啡",
                "美式咖啡", "中国大陆", 1, now, now);
    }

    private ArticleRecords.Article article() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ArticleRecords.Article(ARTICLE_ID, USER_ID, SPACE_ID, PRODUCT_ID, "", "", "",
                "[]", "DRAFT", null, 1, now, now);
    }

    private TaskRecords.Draft storedDraft(long draftId, OffsetDateTime expiresAt) {
        return storedDraft(draftId, expiresAt, "WAITING_CONFIRMATION");
    }

    private TaskRecords.Draft storedDraft(long draftId, OffsetDateTime expiresAt, String status) {
        OffsetDateTime created = expiresAt.minusMinutes(10);
        return new TaskRecords.Draft(draftId, USER_ID, CONVERSATION_ID, null, null, "FORM",
                "TASK_DRAFT_CREATE", UUID.randomUUID(), "a".repeat(64), "ARTICLE", 2,
                SPACE_ID, PRODUCT_ID,
                "{\"articleId\":\"" + ARTICLE_ID + "\",\"geoReportId\":\"" + REPORT_ID + "\"}",
                "{}", "b".repeat(64), 1201, 1, "[]", status, expiresAt,
                null, created, created);
    }

    private TaskDtos.DraftResponse draftResponse(long draftId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new TaskDtos.DraftResponse(Long.toString(draftId), null, "FORM", "ARTICLE", 2,
                "WAITING_CONFIRMATION", Long.toString(SPACE_ID), Long.toString(PRODUCT_ID),
                JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.arrayNode(),
                "b".repeat(64), "1201", 1, "token", now.plusMinutes(10), now);
    }

    private AuthenticatedAccount user() {
        return new AuthenticatedAccount(USER_ID, "user", AccountRole.USER, 1,
                AccountStatus.ACTIVE, null, null, null);
    }
}
