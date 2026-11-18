package com.guanghe.backend.conversation.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.agentplatform.*;
import com.guanghe.backend.auth.account.*;
import com.guanghe.backend.conversation.*;
import com.guanghe.backend.conversation.context.RunContextService;
import com.guanghe.backend.geo.*;
import com.guanghe.backend.knowledge.KnowledgeService;
import com.guanghe.backend.task.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the public read action; policy availability is not a paid-task admission. */
class RunActionBudgetContextTests {
    private static final long USER=41, CONVERSATION=51, SOURCE=61;
    private static final UUID RUN=UUID.fromString("130292ae-46d8-4fbe-af5b-82bb43a043be");
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final MonitorExecutionMapper budgets=mock(MonitorExecutionMapper.class);
    private final AgentPlatformProperties properties=new AgentPlatformProperties();
    private final TaskControlService tasks=mock(TaskControlService.class);
    private ValidatorFactory validators;
    private RunActionService service;
    private AgentPlatformService platform;
    private ProjectGeoTargetService targets;
    private RunActionMapper actions;
    private KnowledgeService knowledge;
    private com.guanghe.backend.conversation.context.InternalRunContextDtos.RunContext frozenContext;

    @BeforeEach void setup() {
        validators=Validation.buildDefaultValidatorFactory();
        var conversations=mock(ConversationMapper.class);
        var accounts=mock(AccountMapper.class);
        actions=mock(RunActionMapper.class);
        platform=mock(AgentPlatformService.class);
        targets=mock(ProjectGeoTargetService.class);
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        var run=new AgentRunRecord(RUN,CONVERSATION,USER,SOURCE,null,null,null,"[]","RUNNING",1,
                "{}","context",now.plusHours(1),null,0,"{}",null,null,null,now,null,now,now);
        var source=new ConversationMessageRecord(SOURCE,CONVERSATION,USER,"USER","TEXT",
                "{\"text\":\"查看当前额度\"}",null,RUN,null,null,null,1,"COMPLETED",now,now);
        when(conversations.findRun(RUN)).thenReturn(Optional.of(run));
        when(conversations.lockOwnedRun(RUN,USER,CONVERSATION)).thenReturn(Optional.of(run));
        when(conversations.lockAdmissionAccount(USER,1)).thenReturn(USER);
        when(accounts.findIdentityById(USER)).thenReturn(Optional.of(new AccountIdentity(USER,"test",null,1,
                AccountRole.USER,AccountStatus.ACTIVE,"test",null)));
        when(conversations.findConversation(CONVERSATION,USER)).thenReturn(Optional.of(
                new ConversationRecord(CONVERSATION,USER,"test",null,null,1,now,now,now)));
        when(conversations.findMessage(SOURCE,USER,CONVERSATION)).thenReturn(Optional.of(source));
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(
                new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO",null,List.of("chat","resources.read")));
        when(actions.insert(eq(RUN),anyString(),eq(USER),eq(CONVERSATION),eq(SOURCE),eq("context.read"),
                anyString(),anyString(),any())).thenReturn(1);
        var contexts=mock(RunContextService.class);
        frozenContext=mock(com.guanghe.backend.conversation.context.InternalRunContextDtos.RunContext.class);
        when(contexts.get(RUN,"context")).thenReturn(frozenContext);
        when(frozenContext.agentIdentity()).thenAnswer(invocation->platform.assertTaskConfirmationAllowed(USER,CONVERSATION));
        knowledge=mock(KnowledgeService.class);
        var authorization=new RunToolAuthorizationService(conversations,accounts,Optional.of(platform),new AgentRuntimeCatalog());
        service=new RunActionService(contexts,conversations,authorization,actions,platform,
                properties,budgets,targets,knowledge,
                mock(GeoService.class),tasks,new PaidTaskProperties(),json,validators.getValidator());
        setClock("2026-10-10T00:00:00Z");
    }

    @AfterEach void cleanup() {
        validators.close();
        verifyNoInteractions(tasks);
        verify(budgets,never()).insertPolicy(any());
    }

    @Test void absentAccountPolicyMeansActiveDefaultNotMissingPermission() {
        var value=readBudget();
        assertThat(value.path("configured").asBoolean()).isFalse();
        assertThat(value.path("enabled").asBoolean()).isTrue();
        assertThat(value.path("policySource").asText()).isEqualTo("DEFAULT");
        assertThat(value.path("status").asText()).isEqualTo("ENABLED");
        assertThat(value.path("maxPointsPerAction").asInt()).isEqualTo(50);
        assertThat(value.path("maxPointsPerDay").asInt()).isEqualTo(200);
        assertThat(value.path("effectiveShanghaiDate").isNull()).isTrue();
        assertThat(value.path("message").asText()).contains("无需先配置","不是账户余额或今日剩余额度");
    }

    @Test void creativeEvidenceIsReturnedBeforeProjectlessContextEarlyReturnWithoutCreatingWork() {
        var evidence=mock(CreativeContinuationEvidence.class);service.setCreativeContinuations(evidence);
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(
                new AgentPlatformDtos.AgentIdentity("71","creative","1.0.0","创作智能体",null,
                        List.of("chat","resources.read","image.generate","video.generate")));
        var candidate=json.createObjectNode().put("authorized",false).put("reviewRequired",true)
                .put("currentMessageId",Long.toString(SOURCE)).put("historyFingerprint","b".repeat(64));
        when(evidence.candidate(any(),any(),isNull())).thenReturn(Optional.of(candidate));
        var response=service.execute(RUN,"context",new RunActionDtos.Request("read-evidence","context.read",json.createObjectNode()));
        assertThat(response.data().path("project").isNull()).isTrue();
        assertThat(response.data().path("creativeContinuation")).isEqualTo(candidate);
        verify(evidence).candidate(argThat(r->r.id().equals(RUN)),argThat(m->m.id()==SOURCE),isNull());
        verifyNoInteractions(tasks);
    }

    @Test void legacyCreativeReceiptHashDoesNotAcquireAnAbsentContinuationReviewField() throws Exception {
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(
                new AgentPlatformDtos.AgentIdentity("71","creative","1.0.0","创作智能体",null,
                        List.of("chat","resources.read","video.generate")));
        var input=json.createObjectNode().put("sourceMode","TEXT_ONLY").put("scene","猫跳舞").put("durationSeconds",10);
        var oldArguments=input.deepCopy();
        for(String field:List.of("aspectRatio","resolution","audio","style","cameraMotion")) oldArguments.putNull(field);
        var oldFingerprint=json.createObjectNode().put("action","video.generate");oldFingerprint.set("arguments",oldArguments);
        String oldHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsBytes(oldFingerprint)));
        when(actions.receipt(RUN,"legacy")).thenReturn(Optional.of(new RunActionMapper.Receipt(RUN,"legacy","video.generate",oldHash,
                "{\"action\":\"video.generate\",\"status\":\"COMPLETED\",\"data\":{\"taskId\":\"91\"}}")));
        var response=service.execute(RUN,"context",new RunActionDtos.Request("legacy","video.generate",input));
        assertThat(response.data().path("taskId").asText()).isEqualTo("91");
        verifyNoInteractions(tasks);
    }

    @Test void draftReviewComplaintUsesExactCurrentScopeAndReadOnlyArticleProjection() {
        var articleFull=mock(com.guanghe.backend.article.ArticleFullService.class);
        service.setArticleFull(articleFull);
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(
                new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO","21",List.of("chat","resources.read")));
        when(platform.projects(any())).thenReturn(List.of(new AgentPlatformDtos.ProjectView("21","客户","ACTIVE",1,now,now)));
        when(targets.get(any(),eq(21L))).thenReturn(new ProjectGeoTargetDtos.View("21",1,"小行星",List.of(),true,now));
        var article=json.createObjectNode().put("articleId","91").put("bodyText","小行星咖啡店。")
                .put("version",6).put("bodyComplete",true);
        when(articleFull.latestArticle(eq(USER),eq(CONVERSATION),eq(21L),eq(1L),eq(SOURCE),any())).thenReturn(Optional.of(article));
        var result=service.execute(RUN,"context",new RunActionDtos.Request("review","context.read",json.createObjectNode()));
        assertThat(result.data().path("latestArticle")).isEqualTo(article);
        verify(articleFull).latestArticle(eq(USER),eq(CONVERSATION),eq(21L),eq(1L),eq(SOURCE),any());
        verify(articleFull,never()).startFromAgent(any(),anyLong(),any(),any(),any(),any());
    }
    @Test void articleActionForwardsHarnessReviewWithoutReplacingCurrentScope() {
        var articleFull=mock(com.guanghe.backend.article.ArticleFullService.class);
        service.setArticleFull(articleFull);
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(
                new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO","21",
                        List.of("chat","resources.read","article.generate")));
        when(platform.projects(any())).thenReturn(List.of(new AgentPlatformDtos.ProjectView("21","客户","ACTIVE",1,now,now)));
        when(targets.get(any(),eq(21L))).thenReturn(new ProjectGeoTargetDtos.View("21",1,"小行星",List.of(),true,now));
        when(actions.insert(eq(RUN),anyString(),eq(USER),eq(CONVERSATION),eq(SOURCE),eq("article.generate"),
                anyString(),anyString(),any())).thenReturn(1);
        var review=new RunActionDtos.ArticleContinuationReview("61","59","60","NEW_DRAFT");
        var input=json.createObjectNode().put("tone","PROFESSIONAL").put("targetLength",1200);
        input.set("continuationReview",json.valueToTree(review));
        var response=service.execute(RUN,"context",new RunActionDtos.Request("generate","article.generate",input));
        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(articleFull).startFromAgent(RUN,21L,"PROFESSIONAL",1200,null,review);
    }
    @Test void continuationReviewDtoRejectsUnboundVerdictsAndExtraAuthority() throws Exception {
        var valid=json.createObjectNode().put("currentMessageId","61").put("sourceMessageId","59")
                .put("offerMessageId","60").put("decision","NEW_DRAFT");
        for(String field:List.of("currentMessageId","sourceMessageId","offerMessageId","decision")) {
            var missing=valid.deepCopy();missing.remove(field);
            var dto=json.treeToValue(missing,RunActionDtos.ArticleContinuationReview.class);
            assertThat(validators.getValidator().validate(dto)).as(field).isNotEmpty();
        }
        for(String field:List.of("currentMessageId","sourceMessageId","offerMessageId")) {
            var changed=valid.deepCopy().put(field,"-1");
            assertThat(validators.getValidator().validate(json.treeToValue(
                    changed,RunActionDtos.ArticleContinuationReview.class))).as(field).isNotEmpty();
        }
        for(String decision:List.of("REVIEW_ONLY","EDIT_EXISTING","PLAN_ONLY","PUBLISH")) {
            assertThat(validators.getValidator().validate(json.treeToValue(
                    valid.deepCopy().put("decision",decision),RunActionDtos.ArticleContinuationReview.class))).isNotEmpty();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(()->json.treeToValue(
                valid.deepCopy().put("authorized",true),RunActionDtos.ArticleContinuationReview.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @Test void contextReadNeverAcceptsModelSelectedArticleOrProjectIds() {
        var articleFull=mock(com.guanghe.backend.article.ArticleFullService.class);service.setArticleFull(articleFull);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.execute(RUN,"context",
                new RunActionDtos.Request("review","context.read",json.createObjectNode().put("articleId","91").put("projectId","99"))))
                .isInstanceOf(com.guanghe.backend.common.api.BusinessException.class);
        verifyNoInteractions(articleFull);
    }
    @Test void readOnlyFrozenRunCannotWriteMemoryEvenWhenCurrentInstallationHistoricallyGrantedIt() {
        var catalog=new AgentRuntimeCatalog();
        var full=catalog.freeze(new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO",null,
                List.of("chat","resources.read")));
        when(frozenContext.agentIdentity()).thenReturn(new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO",null,
                List.of("chat","resources.read"),full.executionRelease(),full.catalogHash()));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.execute(RUN,"context",
                new RunActionDtos.Request("write","memory.upsert",json.createObjectNode())))
                .isInstanceOfSatisfying(com.guanghe.backend.common.api.BusinessException.class,
                        failure->assertThat(failure.errorCode()).isEqualTo(com.guanghe.backend.common.api.ErrorCode.FORBIDDEN));
    }
    @Test void incompatibleFrozenReleaseCannotReachReadAction() {
        var catalog=new AgentRuntimeCatalog();
        when(frozenContext.agentIdentity()).thenReturn(new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO",null,
                List.of("chat","resources.read"),"unknown-release",catalog.catalogHash()));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->readBudget())
                .isInstanceOfSatisfying(com.guanghe.backend.common.api.BusinessException.class,
                        failure->assertThat(failure.errorCode()).isEqualTo(com.guanghe.backend.common.api.ErrorCode.PROVIDER_NOT_CONFIGURED));
    }
    @Test void currentCapabilityRevocationOverridesPreviouslyFrozenWriteGrant() {
        var catalog=new AgentRuntimeCatalog();
        when(frozenContext.agentIdentity()).thenReturn(catalog.freeze(new AgentPlatformDtos.AgentIdentity(
                "71","geo","1.0.0","GEO",null,List.of("chat","resources.read"))));
        // Current platform authorization intentionally has no memory.write despite retaining read.
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.execute(RUN,"context",
                new RunActionDtos.Request("write","memory.upsert",json.createObjectNode())))
                .isInstanceOfSatisfying(com.guanghe.backend.common.api.BusinessException.class,
                        failure->assertThat(failure.errorCode()).isEqualTo(com.guanghe.backend.common.api.ErrorCode.FORBIDDEN));
    }
    @Test void resourceReadDoesNotExposeMemoryOrPreferencesThroughKnowledgeProjection() {
        var catalog=new AgentRuntimeCatalog();
        var full=catalog.freeze(new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO","21",
                List.of("chat","resources.read")));
        var reader=new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO","21",List.of("chat","resources.read"),
                full.executionRelease(),full.catalogHash());
        when(frozenContext.agentIdentity()).thenReturn(reader);
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(reader);
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        when(platform.projects(any())).thenReturn(List.of(new AgentPlatformDtos.ProjectView("21","客户","ACTIVE",1,now,now)));
        when(targets.get(any(),eq(21L))).thenReturn(new ProjectGeoTargetDtos.View("21",1,"小行星",List.of(),true,now));
        var document=new com.guanghe.backend.knowledge.KnowledgeDtos.ContextDocument("91",1,"品牌事实","hash","TEXT",null,
                List.of(new com.guanghe.backend.knowledge.KnowledgeDtos.Paragraph(0,null,"小行星咖啡店")));
        var context=new com.guanghe.backend.knowledge.KnowledgeDtos.KnowledgeContext(List.of(document),
                List.of(new com.guanghe.backend.knowledge.KnowledgeDtos.PreferenceView("92",null,"ACCOUNT","个人偏好","USER",null,1,now,now)),
                null,false,1,100,new com.guanghe.backend.knowledge.MemoryDtos.Context(1,"21",List.of(),"revision",null));
        when(knowledge.context(eq(USER),eq(21L),anyString())).thenReturn(context);
        var result=service.execute(RUN,"context",new RunActionDtos.Request("read","context.read",json.createObjectNode())).data().path("knowledge");
        assertThat(result.path("documents").get(0).path("name").asText()).isEqualTo("品牌事实");
        assertThat(result.path("preferences").isEmpty()).isTrue();
        assertThat(result.path("memory").isNull()).isTrue();
    }
    @Test void defaultsComeFromExistingConfigurationRatherThanHardcodedProjection() {
        properties.setNativeActionMaxPointsPerAction(30);
        properties.setNativeActionMaxPointsPerDay(90);
        var value=readBudget();
        assertThat(value.path("maxPointsPerAction").asInt()).isEqualTo(30);
        assertThat(value.path("maxPointsPerDay").asInt()).isEqualTo(90);
        assertThat(value.path("status").asText()).isEqualTo("ENABLED");
    }

    @Test void disabledAccountPolicyNeverFallsBackToEnabledDefaults() {
        policy(false,8,20,"2026-10-09");
        var value=readBudget();
        assertThat(value.path("configured").asBoolean()).isTrue();
        assertThat(value.path("enabled").asBoolean()).isFalse();
        assertThat(value.path("policySource").asText()).isEqualTo("ACCOUNT");
        assertThat(value.path("status").asText()).isEqualTo("DISABLED");
        assertThat(value.path("maxPointsPerAction").asInt()).isEqualTo(8);
        assertThat(value.path("maxPointsPerDay").asInt()).isEqualTo(20);
    }

    @Test void effectiveAccountPolicyRemainsSubjectToFinalQuoteWalletAndDayChecks() {
        policy(true,8,20,"2026-10-09");
        var value=readBudget();
        assertThat(value.path("status").asText()).isEqualTo("ENABLED");
        assertThat(value.path("message").asText()).contains("已生效","报价、余额及今日额度校验");
        assertThat(value.has("availablePoints")).isFalse();
        assertThat(value.has("remainingDailyPoints")).isFalse();
    }

    @Test void futurePolicyBecomesEffectiveAtShanghaiMidnightUsingBillingClock() {
        policy(true,8,20,"2026-10-10");
        setClock("2026-10-09T15:59:59Z");
        var before=readBudget();
        assertThat(before.path("enabled").asBoolean()).isTrue();
        assertThat(before.path("status").asText()).isEqualTo("NOT_EFFECTIVE");
        assertThat(before.path("effectiveShanghaiDate").asText()).isEqualTo("2026-10-10");
        setClock("2026-10-09T16:00:00Z");
        assertThat(readBudget().path("status").asText()).isEqualTo("ENABLED");
    }

    @ParameterizedTest @CsvSource({"0,200","50,49"})
    void invalidDefaultLimitsDoNotClaimPolicyAvailability(int perAction,int perDay) {
        properties.setNativeActionMaxPointsPerAction(perAction);
        properties.setNativeActionMaxPointsPerDay(perDay);
        assertThat(readBudget().path("status").asText()).isEqualTo("INVALID");
    }

    private JsonNode readBudget() {
        var response=service.execute(RUN,"context",new RunActionDtos.Request("budget","context.read",json.createObjectNode()));
        assertThat(response.status()).isEqualTo("COMPLETED");
        var value=response.data().path("budget");
        assertThat(value.path("finalAdmissionRequired").asBoolean()).isTrue();
        assertThat(value.path("timezone").asText()).isEqualTo("Asia/Shanghai");
        return value;
    }

    private void policy(boolean enabled,int perAction,int perDay,String date) {
        var now=OffsetDateTime.parse("2026-10-09T00:00:00Z");
        when(budgets.policy(USER)).thenReturn(Optional.of(new MonitorExecutionRecords.Policy(USER,1,enabled,
                perAction,perDay,LocalDate.parse(date),now,now)));
    }

    private void setClock(String instant) {
        service.setAccountingClock(Clock.fixed(Instant.parse(instant),ZoneOffset.UTC));
    }
}
