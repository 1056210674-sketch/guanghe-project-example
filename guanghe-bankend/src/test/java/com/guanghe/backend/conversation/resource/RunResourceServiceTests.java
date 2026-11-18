package com.guanghe.backend.conversation.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.agentplatform.*;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.auth.account.*;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.product.*;
import com.guanghe.backend.catalog.space.*;
import com.guanghe.backend.common.api.*;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.*;
import com.guanghe.backend.conversation.context.*;
import com.guanghe.backend.geo.*;
import com.guanghe.backend.knowledge.*;
import com.guanghe.backend.conversation.action.*;
import com.guanghe.backend.task.*;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.ObjectStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.guanghe.backend.conversation.resource.RunResourceDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RunResourceServiceTests {
    static final long USER=10,CONVERSATION=20,SPACE=30,PRODUCT=40,TOPIC=50,REPORT=60,GEO_RUN=70;
    static final UUID RUN=UUID.fromString("5c5ea6a3-491b-4035-a7b0-ae34f9f0f2bd");
    static final OffsetDateTime NOW=OffsetDateTime.parse("2026-10-05T04:00:00Z");
    final ObjectMapper json=JsonMapper.builder().addModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    final RunResourceMapper mapper=mock(RunResourceMapper.class);
    final ConversationMapper conversations=mock(ConversationMapper.class);
    final RunContextService contexts=mock(RunContextService.class);
    final AccountMapper accounts=mock(AccountMapper.class);
    final BusinessSpaceMapper spaces=mock(BusinessSpaceMapper.class);
    final ProductMapper products=mock(ProductMapper.class);
    final GeoMapper topics=mock(GeoMapper.class);
    final GeoTaskMapper reports=mock(GeoTaskMapper.class);
    final AssetMapper assets=mock(AssetMapper.class);
    final GeoChatContextFactory geo=mock(GeoChatContextFactory.class);
    final GeoDiagnosticDeliveryService diagnostics=mock(GeoDiagnosticDeliveryService.class);
    final SnowflakeIdGenerator ids=mock(SnowflakeIdGenerator.class);
    final Map<Long,ConversationEventRecord> events=new LinkedHashMap<>();
    ConversationRecord conversation;
    AgentRunRecord run;
    RunResourceService service;
    final AgentPlatformService platform=mock(AgentPlatformService.class);
    InternalRunContextDtos.RunContext frozenContext;
    long reset;

    @BeforeEach void setUp() {
        conversation=new ConversationRecord(CONVERSATION,USER,"test",SPACE,null,1,NOW,NOW,NOW);
        run=run("{}","RUNNING","[]");
        var context=mock(InternalRunContextDtos.RunContext.class);
        frozenContext=context;
        var catalog=new AgentRuntimeCatalog();
        var agent=catalog.freeze(new AgentPlatformDtos.AgentIdentity("81","geo","1.0.0","GEO",null,
                List.of("chat","resources.read","geo.read")));
        when(context.agentIdentity()).thenReturn(agent);
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(agent);
        when(context.protocolFeatures()).thenReturn(new InternalRunContextDtos.ProtocolFeatures(true,true));
        when(contexts.get(RUN,"ref")).thenReturn(context);
        when(accounts.findIdentityById(USER)).thenReturn(Optional.of(new AccountIdentity(USER,"test",null,1,
                AccountRole.USER,AccountStatus.ACTIVE,"test",null)));
        when(conversations.lockAdmissionAccount(USER,1)).thenReturn(USER);
        when(conversations.findRun(RUN)).thenAnswer(i -> Optional.of(run));
        when(conversations.lockOwnedRun(RUN,USER,CONVERSATION)).thenAnswer(i -> Optional.of(run));
        when(conversations.findActiveRun(USER,CONVERSATION)).thenAnswer(i -> Optional.of(run));
        when(conversations.lockConversation(CONVERSATION,USER)).thenAnswer(i -> Optional.of(conversation));
        when(conversations.findConversation(CONVERSATION,USER)).thenAnswer(i -> Optional.of(conversation));
        when(conversations.nextEventSequence(CONVERSATION,USER)).thenAnswer(i -> (long)events.size()+1);
        when(conversations.insertEvent(any())).thenAnswer(i -> {
            var event=i.getArgument(0,ConversationEventRecord.class); events.put(event.id(),event); return 1;
        });
        AtomicLong sequence=new AtomicLong(100);
        when(ids.nextId()).thenAnswer(i -> sequence.incrementAndGet());
        when(mapper.findCall(eq(RUN),any())).thenAnswer(i -> events.values().stream()
                .filter(e -> e.sourceEventId().equals(i.getArgument(1))).findFirst());
        when(mapper.findReference(eq(USER),eq(CONVERSATION),anyLong(),anyLong())).thenAnswer(i ->
                reset==0?Optional.ofNullable(events.get(i.getArgument(2,Long.class))):Optional.empty());
        when(mapper.resetSequence(USER,CONVERSATION)).thenAnswer(i -> reset);
        when(mapper.latestSearch(eq(USER),eq(CONVERSATION),anyLong())).thenAnswer(i -> latest("SEARCH",false));
        when(mapper.latestRead(eq(USER),eq(CONVERSATION),anyLong(),anyBoolean())).thenAnswer(i -> latest("READ",i.getArgument(3)));
        when(mapper.latestReportReadForRun(USER,CONVERSATION,RUN)).thenAnswer(i -> latest("READ",true));
        when(mapper.consumed(eq(USER),eq(CONVERSATION),nullable(UUID.class),anyString(),nullable(String.class)))
                .thenAnswer(i -> events.values().stream().map(e -> tree(e.payloadJson())).anyMatch(p ->
                        p.path("kind").asText().equals("READ") && p.path("success").asBoolean()
                        && p.path("candidateSetId").asText().equals(i.getArgument(3))
                        && (i.getArgument(4)==null || p.path("choiceId").asText().equals(i.getArgument(4)))));
        when(mapper.callCount(RUN)).thenAnswer(i -> (long)events.size());
        when(spaces.findProjectByIdAndUserId(SPACE,USER)).thenReturn(Optional.of(space(SPACE,"抖店")));
        when(products.findByIdAndUserId(PRODUCT,USER)).thenReturn(Optional.of(product("{}")));
        when(topics.findTopic(TOPIC,USER)).thenReturn(Optional.of(new GeoRecords.Topic(TOPIC,USER,SPACE,PRODUCT,
                "咖啡", "咖啡",null,1,NOW,NOW)));
        var authorization=new RunToolAuthorizationService(conversations,accounts,Optional.of(platform),catalog);
        service=new RunResourceService(mapper,conversations,contexts,authorization,accounts,spaces,products,topics,reports,
                assets,geo,new CatalogNameNormalizer(),ids,json);
        service.setClock(Clock.fixed(NOW.toInstant(),ZoneOffset.UTC));
    }
    @Test void creativeResourceReadCannotSearchOrReadGeoReports() {
        var catalog=new AgentRuntimeCatalog();
        var creative=catalog.freeze(new AgentPlatformDtos.AgentIdentity("82","creative","1.0.0","创作",null,
                List.of("chat","resources.read","image.generate")));
        when(frozenContext.agentIdentity()).thenReturn(creative);
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(creative);
        assertCode(()->service.search(RUN,"ref",new SearchRequest("geo-search","GEO_TOPIC","品牌",null)),ErrorCode.FORBIDDEN);
        assertCode(()->service.read(RUN,"ref",new ReadRequest("geo-read","LATEST_GEO_REPORT","101","choice-50",null,null,null)),ErrorCode.FORBIDDEN);
        assertCode(()->service.read(RUN,"ref",new ReadRequest("diagnostic-read","GEO_DIAGNOSTIC_REPORT","101","choice-30",null,null,null)),ErrorCode.FORBIDDEN);
        assertThat(events).isEmpty();
        verifyNoInteractions(geo,reports);
    }
    @Test void disabledDiagnosticReadIsHonestAndDoesNotInvokeAnyReportOrTask() {
        var search=search("SPACE",SPACE);
        var result=service.read(RUN,"ref",new ReadRequest("diagnostic","GEO_DIAGNOSTIC_REPORT",
                search.candidateSetId(),"choice-30",null,null,null));
        assertThat(result.reportId()).isNull();
        assertThat(result.reportAttempt()).isNull();
        assertThat(result.projection().path("status").asText()).isEqualTo("NO_REPORT");
        assertThat(result.projection().path("diagnosticsEnabled").asBoolean()).isFalse();
        verifyNoInteractions(diagnostics,geo,reports,assets);
    }
    @Test void diagnosticReadUsesOwnedCurrentProjectAndFreezesItsSeparateReportVersion() {
        service.setDiagnostics(diagnostics);
        ObjectNode projection=json.createObjectNode().put("status","READY").put("readOnly",true)
                .put("diagnosticId","90").put("reportVersion",1);
        projection.putObject("metrics").put("validSamples",6);
        when(diagnostics.latestAgentProjection(USER,SPACE)).thenReturn(projection);
        when(diagnostics.isAgentProjectionAvailable(USER,SPACE,90,1)).thenReturn(true);
        var search=search("SPACE",SPACE);
        var request=new ReadRequest("diagnostic","GEO_DIAGNOSTIC_REPORT",search.candidateSetId(),"choice-30",null,null,null);
        var first=service.read(RUN,"ref",request);
        assertThat(first.reportId()).isNull();
        assertThat(first.reportAttempt()).isNull();
        assertThat(first.projection().path("diagnosticId").asText()).isEqualTo("90");
        assertThat(first.projection().path("reportVersion").asInt()).isEqualTo(1);
        when(diagnostics.latestAgentProjection(USER,SPACE)).thenReturn(json.createObjectNode()
                .put("status","READY").put("diagnosticId","91").put("reportVersion",1));
        assertThat(service.read(RUN,"ref",request)).isEqualTo(first);
        var continued=service.read(RUN,"ref",new ReadRequest("continue-diagnostic","GEO_DIAGNOSTIC_REPORT",
                null,null,first.observationId(),null,null));
        assertThat(continued.projection()).isEqualTo(first.projection());
        assertThat(service.lastReadContext(USER,CONVERSATION,SPACE,null).purpose()).isEqualTo("GEO_DIAGNOSTIC_REPORT");
        assertThat(service.lastReportReadContext(USER,CONVERSATION,SPACE,null)).isNull();
        verify(diagnostics,times(1)).latestAgentProjection(USER,SPACE);
        verifyNoInteractions(geo,reports,assets);
    }
    @Test void diagnosticReplayRechecksAvailabilityWithoutResolvingNewerReport() {
        service.setDiagnostics(diagnostics);
        when(diagnostics.latestAgentProjection(USER,SPACE)).thenReturn(json.createObjectNode()
                .put("status","READY").put("diagnosticId","90").put("reportVersion",1));
        when(diagnostics.isAgentProjectionAvailable(USER,SPACE,90,1)).thenReturn(true);
        var search=search("SPACE",SPACE);
        var request=new ReadRequest("diagnostic","GEO_DIAGNOSTIC_REPORT",search.candidateSetId(),"choice-30",null,null,null);
        service.read(RUN,"ref",request);
        when(diagnostics.isAgentProjectionAvailable(USER,SPACE,90,1)).thenReturn(false);
        assertCode(()->service.read(RUN,"ref",request),ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(service.lastReadContext(USER,CONVERSATION,SPACE,null)).isNull();
        verify(diagnostics,times(1)).latestAgentProjection(USER,SPACE);
    }
    @Test void diagnosticNoReportObservationRemainsAnAbsenceBarrier() {
        service.setDiagnostics(diagnostics);
        when(diagnostics.latestAgentProjection(USER,SPACE)).thenReturn(json.createObjectNode()
                .put("readOnly",true).put("status","NO_REPORT"));
        var search=search("SPACE",SPACE);
        var first=service.read(RUN,"ref",new ReadRequest("diagnostic","GEO_DIAGNOSTIC_REPORT",
                search.candidateSetId(),"choice-30",null,null,null));
        when(diagnostics.latestAgentProjection(USER,SPACE)).thenReturn(json.createObjectNode()
                .put("status","READY").put("diagnosticId","90").put("reportVersion",1));
        var continued=service.read(RUN,"ref",new ReadRequest("continue-diagnostic","GEO_DIAGNOSTIC_REPORT",
                null,null,first.observationId(),null,null));
        assertThat(continued.projection().path("status").asText()).isEqualTo("NO_REPORT");
        verify(diagnostics,times(1)).latestAgentProjection(USER,SPACE);
    }
    @Test void diagnosticCannotReadAnotherProjectEvenWithOrdinaryCrossProjectSelection() {
        service.setDiagnostics(diagnostics);
        when(spaces.findProjectByIdAndUserId(31,USER)).thenReturn(Optional.of(space(31,"京东")));
        var search=search("SPACE",31);
        run=run("{\"resourceSelection\":{\"candidateSetId\":\""+search.candidateSetId()+"\",\"choiceId\":\"choice-31\"}}","RUNNING","[]");
        assertCode(()->service.read(RUN,"ref",new ReadRequest("other-project","GEO_DIAGNOSTIC_REPORT",
                search.candidateSetId(),"choice-31",null,null,null)),ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(diagnostics);
    }
    @Test void diagnosticRejectsOrdinaryReportPolicyAndSampleIdentifiers() {
        assertCode(()->service.read(RUN,"ref",new ReadRequest("policy","GEO_DIAGNOSTIC_REPORT",
                "101","choice-30",null,"LATEST_USABLE",null)),ErrorCode.VALIDATION_FAILED);
        assertCode(()->service.read(RUN,"ref",new ReadRequest("samples","GEO_DIAGNOSTIC_REPORT",
                "101","choice-30",null,null,List.of("1:DEEPSEEK:1"))),ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(diagnostics);
        assertThat(events).isEmpty();
    }
    @Test void currentResourceReadRevocationAndReleaseMismatchRejectBeforeSearch() {
        var catalog=new AgentRuntimeCatalog();
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(
                new AgentPlatformDtos.AgentIdentity("81","geo","1.0.0","GEO",null,List.of("chat")));
        assertCode(()->service.search(RUN,"ref",new SearchRequest("a","SPACE","品牌",null)),ErrorCode.FORBIDDEN);
        when(frozenContext.agentIdentity()).thenReturn(new AgentPlatformDtos.AgentIdentity("81","geo","1.0.0","GEO",null,
                List.of("chat","resources.read"),"unknown",catalog.catalogHash()));
        assertCode(()->service.search(RUN,"ref",new SearchRequest("b","SPACE","品牌",null)),ErrorCode.PROVIDER_NOT_CONFIGURED);
        assertThat(events).isEmpty();
        verify(mapper,never()).search(anyLong(),anyString(),anyString(),any(),any());
    }
    @Test void exactSearchNormalizesNfkcAndPersistsControlledMetadata() {
        when(mapper.search(USER,"SPACE","abc",null,null)).thenReturn(List.of(match(SPACE,true,1)));
        var result=service.search(RUN,"ref",new SearchRequest("call","SPACE"," ＡＢＣ ",null));
        assertThat(result.status()).isEqualTo("UNIQUE");
        assertThat(result.complete()).isTrue();
        assertThat(result.candidates()).hasSize(1);
        assertThat(tree(events.get(101L).payloadJson()).path("candidateSetId").asText()).isEqualTo("101");
        assertThat(tree(events.get(101L).payloadJson()).path("input").path("keyword").asText()).isEqualTo("abc");
    }

    @Test void boundProjectScopesSearchWithoutRequiringCallerScopeReference() {
        var catalog=new AgentRuntimeCatalog();
        var agent=catalog.freeze(new AgentPlatformDtos.AgentIdentity("81","geo","1.0.0","GEO",Long.toString(SPACE),
                List.of("chat","resources.read","geo.read")));
        when(frozenContext.agentIdentity()).thenReturn(agent);
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(agent);
        when(mapper.search(USER,"SPACE","品牌",SPACE,null)).thenReturn(List.of(match(SPACE,true,1)));
        var result=service.search(RUN,"ref",new SearchRequest("bound-search","SPACE","品牌",null));
        assertThat(result.candidates()).hasSize(1).allMatch(item->item.spaceId().equals(Long.toString(SPACE)));
        verify(mapper).search(USER,"SPACE","品牌",SPACE,null);
        verify(mapper,never()).search(eq(USER),anyString(),anyString(),isNull(),any());
    }

    @Test void boundProjectRejectsLegacyCrossProjectSearchReceiptOnReplay() {
        when(spaces.findProjectByIdAndUserId(31,USER)).thenReturn(Optional.of(space(31,"另一个客户")));
        when(mapper.search(USER,"SPACE","旧搜索",null,null)).thenReturn(List.of(match(31,true,1)));
        var request=new SearchRequest("historic-search","SPACE","旧搜索",null);
        var saved=service.search(RUN,"ref",request);
        assertThat(saved.candidates().getFirst().spaceId()).isEqualTo("31");
        var catalog=new AgentRuntimeCatalog();
        var bound=catalog.freeze(new AgentPlatformDtos.AgentIdentity("81","geo","1.0.0","GEO","30",
                List.of("chat","resources.read","geo.read")));
        when(frozenContext.agentIdentity()).thenReturn(bound);
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(bound);
        assertCode(()->service.search(RUN,"ref",request),ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(events).hasSize(1);
        verify(mapper,times(1)).search(anyLong(),anyString(),anyString(),any(),any());
    }
    @Test void oneFuzzyIsStillMultipleAndExactTotalIsNotTheFirstPageCount() {
        when(mapper.search(USER,"SPACE","x",null,null)).thenReturn(List.of(match(SPACE,false,1)));
        assertThat(service.search(RUN,"ref",new SearchRequest("a","SPACE","x",null)).status()).isEqualTo("MULTIPLE");
        when(mapper.search(USER,"SPACE","x",null,null)).thenReturn(List.of(match(SPACE,true,8)));
        var incomplete=service.search(RUN,"ref",new SearchRequest("b","SPACE","x",null));
        assertThat(incomplete.status()).isEqualTo("INCOMPLETE");
        assertThat(incomplete.totalMatches()).isEqualTo(8);
    }
    @Test void readReceiptReplaysAfterCandidateTtlAndConsumption() {
        var search=search("SPACE",SPACE);
        var request=new ReadRequest("read","CATALOG_SUMMARY",search.candidateSetId(),"choice-30",null,null,null);
        var first=service.read(RUN,"ref",request);
        assertThat(service.pendingSearch(USER,CONVERSATION,SPACE,null)).isNull();
        service.setClock(Clock.fixed(NOW.plusDays(2).toInstant(),ZoneOffset.UTC));
        run=run("{}","RUNNING","[]"); // context lease independently extended for replay.
        var again=service.read(RUN,"ref",request);
        assertThat(again).isEqualTo(first);
        assertThat(events).hasSize(2);
    }
    @Test void sameCallDifferentRequestConflictsBeforeNewSourceLookup() {
        var search=search("SPACE",SPACE);
        service.read(RUN,"ref",new ReadRequest("read","CATALOG_SUMMARY",search.candidateSetId(),"choice-30",null,null,null));
        assertCode(() -> service.read(RUN,"ref",new ReadRequest("read","CATALOG_SUMMARY","999","fake",null,null,null)),
                ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
    @Test void replayRevalidatesPermissionBeforeReturningSensitiveResult() {
        var search=search("SPACE",SPACE);
        var request=new ReadRequest("read","CATALOG_SUMMARY",search.candidateSetId(),"choice-30",null,null,null);
        service.read(RUN,"ref",request);
        when(spaces.findProjectByIdAndUserId(SPACE,USER)).thenReturn(Optional.empty());
        assertCode(() -> service.read(RUN,"ref",request),ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(events).hasSize(2);
    }
    @Test void stoppedRunCannotReplayItsPreviouslySuccessfulSearch() {
        var request=new SearchRequest("cached","SPACE","品牌",null);
        when(mapper.search(USER,"SPACE","品牌",null,null)).thenReturn(List.of(match(SPACE,true,1)));
        service.search(RUN,"ref",request);
        run=run("{}","STOP_REQUESTED","[]");
        clearInvocations(mapper);
        assertCode(()->service.search(RUN,"ref",request),ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper,never()).findCall(any(),any());
        assertThat(events).hasSize(1);
    }
    @Test void revokedInstallationCannotReplayItsPreviouslySuccessfulRead() {
        var search=search("SPACE",SPACE);
        var request=new ReadRequest("cached","CATALOG_SUMMARY",search.candidateSetId(),"choice-30",null,null,null);
        service.read(RUN,"ref",request);
        when(platform.assertTaskConfirmationAllowed(USER,CONVERSATION)).thenReturn(
                new AgentPlatformDtos.AgentIdentity("81","geo","1.0.0","GEO",null,List.of("chat")));
        clearInvocations(mapper);
        assertCode(()->service.read(RUN,"ref",request),ErrorCode.FORBIDDEN);
        verify(mapper,never()).findCall(any(),any());
        assertThat(events).hasSize(2);
    }
    @Test void waitingForConversationLockDoesNotExtendTheAuthorizedRunLease() {
        Clock clock=mock(Clock.class);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(NOW.toInstant());
        service.setClock(clock);
        when(conversations.lockConversation(CONVERSATION,USER)).thenAnswer(invocation->{
            when(clock.instant()).thenReturn(run.contextExpiresAt().toInstant());
            return Optional.of(conversation);
        });
        assertCode(()->service.search(RUN,"ref",new SearchRequest("late","SPACE","品牌",null)),ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper,never()).findCall(any(),any());
        assertThat(events).isEmpty();
    }
    @Test void authorizedFailuresCountAndReplayWithoutConsumption() {
        var search=search("SPACE",SPACE);
        var request=new ReadRequest("bad-purpose","LATEST_GEO_REPORT",search.candidateSetId(),"choice-30",null,null,null);
        assertCode(() -> service.read(RUN,"ref",request),ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.read(RUN,"ref",request),ErrorCode.VALIDATION_FAILED);
        assertThat(events).hasSize(2);
        assertThat(service.pendingSearch(USER,CONVERSATION,SPACE,null)).isNotNull();
        for (int n=0;n<4;n++) {
            int index=n;
            assertCode(() -> service.read(RUN,"ref",new ReadRequest("bad-"+index,"LATEST_GEO_REPORT",
                    search.candidateSetId(),"choice-30",null,null,null)),ErrorCode.VALIDATION_FAILED);
        }
        assertCode(() -> service.read(RUN,"ref",new ReadRequest("seventh","CATALOG_SUMMARY",
                search.candidateSetId(),"choice-30",null,null,null)),ErrorCode.TASK_STATE_CONFLICT);
        assertThat(events).hasSize(6);
    }
    @Test void independentSameRunSearchesRemainReadableButClearInvalidatesAllReferences() {
        var old=search("SPACE",SPACE);
        var newer=search("SPACE",SPACE);
        assertThat(service.read(RUN,"ref",new ReadRequest("old","CATALOG_SUMMARY",old.candidateSetId(),
                "choice-30",null,null,null)).spaceId()).isEqualTo("30");
        assertThat(service.pendingSearch(USER,CONVERSATION,SPACE,null).candidateSetId()).isEqualTo(newer.candidateSetId());
        reset=99;
        assertThat(service.pendingSearch(USER,CONVERSATION,SPACE,null)).isNull();
        assertCode(() -> service.validateSelection(USER,CONVERSATION,newer.candidateSetId(),"choice-30","CLEAR",null,null),
                ErrorCode.RESOURCE_NOT_FOUND);
    }
    @Test void explicitCrossSpaceIsTemporaryAndAnchorUsesPersistentSidebar() {
        when(spaces.findProjectByIdAndUserId(31,USER)).thenReturn(Optional.of(space(31,"京东")));
        var search=search("SPACE",31);
        var selection=service.validateSelection(USER,CONVERSATION,search.candidateSetId(),"choice-31","INHERIT",SPACE,null);
        assertThat(selection.spaceId()).isEqualTo(31);
        var request=new ReadRequest("cross","CATALOG_SUMMARY",search.candidateSetId(),"choice-31",null,null,null);
        assertCode(() -> service.read(RUN,"ref",request),ErrorCode.RESOURCE_NOT_FOUND);
        run=run("{\"resourceSelection\":{\"candidateSetId\":\""+search.candidateSetId()+"\",\"choiceId\":\"choice-31\"}}","RUNNING","[]");
        var read=service.read(RUN,"ref",new ReadRequest("cross-confirmed","CATALOG_SUMMARY",search.candidateSetId(),"choice-31",null,null,null));
        assertThat(read.spaceId()).isEqualTo("31");
        assertThat(conversation.selectedSpaceId()).isEqualTo(SPACE);
        assertThat(service.lastReadContext(USER,CONVERSATION,SPACE,null)).isEqualTo(read);
        assertThat(service.lastReadContext(USER,CONVERSATION,31L,null)).isNull();
    }
    @Test void latestFailedAttemptIsSuccessfulNoReportObservationNotInventedReport() {
        var search=search("GEO_TOPIC",TOPIC);
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(71,"FAILED",NOW,null)));
        var result=service.read(RUN,"ref",new ReadRequest("read","LATEST_GEO_REPORT",search.candidateSetId(),"choice-50",null,null,null));
        assertThat(result.reportId()).isNull();
        assertThat(result.reportAttempt().runId()).isEqualTo("71");
        assertThat(result.projection().path("reportRead").asBoolean()).isFalse();
        assertThat(service.lastReportReadContext(USER,CONVERSATION,SPACE,null)).isEqualTo(result);
        assertThat(service.hasSuccessfulRead(USER,CONVERSATION,RUN,search.candidateSetId(),"choice-50")).isTrue();
    }
    @Test void explicitLatestUsableLabelsNewerFailureAndFreezesReport() {
        var search=search("GEO_TOPIC",TOPIC); stubReport();
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(71,"FAILED",NOW,null)));
        when(mapper.latestUsable(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(GEO_RUN,"SUCCEEDED",NOW.minusDays(1),REPORT)));
        var first=service.read(RUN,"ref",new ReadRequest("read","LATEST_GEO_REPORT",search.candidateSetId(),"choice-50",null,"LATEST_USABLE",null));
        assertThat(first.reportAttempt().newerFailedAttempt()).isTrue();
        var again=service.read(RUN,"ref",new ReadRequest("continue","LATEST_GEO_REPORT",null,null,first.observationId(),null,null));
        assertThat(again.reportId()).isEqualTo("60");
        verify(mapper,times(1)).latestAttempt(USER,TOPIC);
    }
    @Test void noReportContinuationFreezesAbsenceEvenIfNewReportAppears() {
        var search=search("GEO_TOPIC",TOPIC); stubReport();
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(71,"FAILED",NOW,null)));
        var first=service.read(RUN,"ref",new ReadRequest("read","LATEST_GEO_REPORT",search.candidateSetId(),"choice-50",null,null,null));
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(GEO_RUN,"SUCCEEDED",NOW.plusHours(1),REPORT)));
        var again=service.read(RUN,"ref",new ReadRequest("continue","LATEST_GEO_REPORT",null,null,first.observationId(),null,null));
        assertThat(again.reportId()).isNull();
        assertThat(again.projection()).isEqualTo(first.projection());
        assertThat(again.reportAttempt()).isEqualTo(first.reportAttempt());
        assertThat(again.sourceHash()).isEqualTo(first.sourceHash());
        verify(mapper,times(1)).latestAttempt(USER,TOPIC);
        verify(mapper,never()).latestUsable(anyLong(),anyLong());
        assertCode(() -> service.read(RUN,"ref",new ReadRequest("attempt","LATEST_GEO_REPORT",null,null,
                first.observationId(),"LATEST_ATTEMPT",null)),ErrorCode.VALIDATION_FAILED);
    }
    @Test void explicitLatestUsableMayMovePastNoReportButCannotChangeAnExistingReport() {
        var search=search("GEO_TOPIC",TOPIC); stubReport();
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(71,"FAILED",NOW,null)));
        var first=service.read(RUN,"ref",new ReadRequest("read","LATEST_GEO_REPORT",search.candidateSetId(),"choice-50",null,null,null));
        when(mapper.latestUsable(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(GEO_RUN,"SUCCEEDED",NOW.minusDays(1),REPORT)));
        var usable=service.read(RUN,"ref",new ReadRequest("usable","LATEST_GEO_REPORT",null,null,
                first.observationId(),"LATEST_USABLE",null));
        assertThat(usable.reportId()).isEqualTo("60");
        assertThat(usable.reportAttempt().newerFailedAttempt()).isTrue();
        assertCode(() -> service.read(RUN,"ref",new ReadRequest("replace","LATEST_GEO_REPORT",null,null,
                usable.observationId(),"LATEST_USABLE",null)),ErrorCode.VALIDATION_FAILED);
        verify(mapper,times(1)).latestUsable(USER,TOPIC);
    }
    @Test void inventoryReadDoesNotEraseReportAnchorAndNoReportReadIsBarrier() {
        var search=search("GEO_TOPIC",TOPIC); stubReport();
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(GEO_RUN,"SUCCEEDED",NOW,REPORT)));
        var report=service.read(RUN,"ref",new ReadRequest("report","LATEST_GEO_REPORT",search.candidateSetId(),"choice-50",null,null,null));
        var space=search("SPACE",SPACE);
        service.read(RUN,"ref",new ReadRequest("asset","ASSET_INVENTORY",space.candidateSetId(),"choice-30",null,null,null));
        assertThat(service.lastReadContext(USER,CONVERSATION,SPACE,null).purpose()).isEqualTo("ASSET_INVENTORY");
        assertThat(service.lastReportReadContext(USER,CONVERSATION,SPACE,null)).isEqualTo(report);
        // A new run has its own budget. This fixture resets the count without changing durable references.
        when(mapper.callCount(RUN)).thenReturn(0L);
        var next=search("GEO_TOPIC",TOPIC);
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(71,"FAILED",NOW,null)));
        service.read(RUN,"ref",new ReadRequest("no-report","LATEST_GEO_REPORT",next.candidateSetId(),"choice-50",null,null,null));
        assertThat(service.lastReportReadContext(USER,CONVERSATION,SPACE,null).reportId()).isNull();
        assertThat(service.lastReportReadContext(USER,CONVERSATION,SPACE,null).projection().path("status").asText()).isEqualTo("NO_REPORT");
    }
    @Test void projectionsAreValidUtf8BoundedJsonAndPreserveRequiredReferences() {
        ObjectNode parameters=json.createObjectNode();
        for(int n=0;n<100;n++) parameters.put("字段"+n,"吸引眼球😀".repeat(300));
        when(products.findByIdAndUserId(PRODUCT,USER)).thenReturn(Optional.of(product(parameters.toString())));
        var search=search("PRODUCT",PRODUCT);
        var result=service.read(RUN,"ref",new ReadRequest("read","CATALOG_SUMMARY",search.candidateSetId(),"choice-40",null,null,null));
        assertThat(result.truncated()).isTrue();
        assertThat(bytes(result.projection())).isLessThanOrEqualTo(16_384);
        assertThat(result.projection().at("/product/id").asText()).isEqualTo("40");
        assertThat(tree(result.projection().toString())).isEqualTo(result.projection());
        assertThat(result.sourceHash()).matches("[a-f0-9]{64}");
    }
    @Test void evidenceCannotGuessSamplesAndRequiresFrozenReport() {
        var search=search("GEO_TOPIC",TOPIC); stubReport();
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(GEO_RUN,"SUCCEEDED",NOW,REPORT)));
        var report=service.read(RUN,"ref",new ReadRequest("report","LATEST_GEO_REPORT",search.candidateSetId(),"choice-50",null,null,null));
        assertCode(() -> service.read(RUN,"ref",new ReadRequest("evidence","GEO_ANSWER_EVIDENCE",null,null,
                report.observationId(),null,List.of("not-in-report"))),ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.read(RUN,"ref",new ReadRequest("too-many","GEO_ANSWER_EVIDENCE",null,null,
                report.observationId(),null,List.of("1","2","3","4"))),ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.read(RUN,"ref",new ReadRequest("null-ref","GEO_ANSWER_EVIDENCE",null,null,
                report.observationId(),null,Arrays.asList((String)null))),ErrorCode.VALIDATION_FAILED);
    }
    @Test void terminalRunOrAttachmentCannotReadAccountCatalog() {
        run=run("{}","SUCCEEDED","[]");
        assertCode(() -> search("SPACE",SPACE),ErrorCode.RESOURCE_NOT_FOUND);
        run=run("{}","RUNNING","[\"99\"]");
        assertCode(() -> search("SPACE",SPACE),ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(events).isEmpty();
    }
    @Test void selectionWireIdsRemainStrings() {
        var node=json.valueToTree(new Selection("PRODUCT",PRODUCT,"coffee",SPACE,PRODUCT,"101","choice-40"));
        for(String key:List.of("resourceId","spaceId","productId")) assertThat(node.path(key).isTextual()).isTrue();
    }
    @Test void evidenceUsesIssuedSampleRefsAndTrimsAnswersWithoutBreakingReferences() {
        var search=search("GEO_TOPIC",TOPIC); stubReport();
        var sample=new GeoTaskRecords.Observation(90,GEO_RUN,80,USER,UUID.randomUUID(),"a".repeat(64),"unit-90",1,
                91,"谁的咖啡好","DOUBAO",1,"SUCCEEDED","咖啡😀".repeat(20000),null,"p","m",NOW,NOW,10,"{}",1,NOW);
        when(reports.findObservationPage(GEO_RUN,0,7)).thenReturn(List.of(sample));
        when(reports.findObservationByUnit(GEO_RUN,"unit-90")).thenReturn(Optional.of(sample));
        when(reports.findCitations(90)).thenReturn(List.of(new GeoTaskRecords.Citation(92,90,GEO_RUN,USER,1,
                "咖啡来源","https://example.com/evidence","example.com","真实摘录",NOW,NOW)));
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(GEO_RUN,"SUCCEEDED",NOW,REPORT)));
        var report=service.read(RUN,"ref",new ReadRequest("report","LATEST_GEO_REPORT",search.candidateSetId(),"choice-50",null,null,null));
        var evidence=service.read(RUN,"ref",new ReadRequest("evidence","GEO_ANSWER_EVIDENCE",null,null,
                report.observationId(),null,List.of("unit-90")));
        assertThat(evidence.reportId()).isEqualTo("60");
        assertThat(evidence.truncated()).isTrue();
        assertThat(bytes(evidence.projection())).isLessThanOrEqualTo(16_384);
        assertThat(evidence.projection().at("/samples/0/sampleRef").asText()).isEqualTo("unit-90");
        assertThat(evidence.projection().at("/samples/0/citations/0/url").asText()).isEqualTo("https://example.com/evidence");
        assertThat(service.lastReportReadContext(USER,CONVERSATION,SPACE,null)).isEqualTo(evidence);
    }
    @Test void inventoryExposesOnlyBoundedMetadataAndRevokedAssetDoesNotReplay() {
        var asset=new AssetRecords.Asset(90,USER,SPACE,null,"IMAGE","UPLOADED",null,null,"图", "private/object-key",
                "image/png",10,"a".repeat(64),100,100,null,"READY","NOT_REVIEWED",NOW,NOW);
        when(assets.findAssetPage(USER,null,SPACE,null,null,0,21)).thenReturn(Collections.nCopies(21,asset));
        when(assets.findAsset(90,USER)).thenReturn(Optional.of(asset));
        var search=search("SPACE",SPACE);
        var request=new ReadRequest("inventory","ASSET_INVENTORY",search.candidateSetId(),"choice-30",null,null,null);
        var inventory=service.read(RUN,"ref",request);
        assertThat(inventory.projection().path("items")).hasSize(20);
        assertThat(inventory.projection().path("hasMore").asBoolean()).isTrue();
        assertThat(inventory.projection().toString()).doesNotContain("private/object-key");
        assertThat(inventory.projection().at("/items/0/href").asText()).isEqualTo("/app/assets?asset=90");
        when(assets.findAsset(90,USER)).thenReturn(Optional.empty());
        assertCode(() -> service.read(RUN,"ref",request),ErrorCode.RESOURCE_NOT_FOUND);
    }
    @Test void sameSpaceDifferentProductRequiresExplicitTemporarySelection() {
        conversation=new ConversationRecord(CONVERSATION,USER,"test",SPACE,41L,1,NOW,NOW,NOW);
        var search=search("PRODUCT",PRODUCT);
        assertCode(() -> service.read(RUN,"ref",new ReadRequest("silent-switch","CATALOG_SUMMARY",
                search.candidateSetId(),"choice-40",null,null,null)),ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(service.validateSelection(USER,CONVERSATION,search.candidateSetId(),"choice-40","INHERIT",SPACE,41L).productId()).isEqualTo(PRODUCT);
    }
    @Test void actionReferenceKeepsActualReadAndOriginalReportMetadataWithoutCreatingWork() {
        assertThat(service.currentRunLatestReportReference(run)).isNull();
        var search=search("GEO_TOPIC",TOPIC); stubReport();
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(GEO_RUN,"SUCCEEDED",NOW,REPORT)));
        var report=service.read(RUN,"ref",new ReadRequest("report","LATEST_GEO_REPORT",search.candidateSetId(),"choice-50",null,null,null));
        var reference=service.currentRunLatestReportReference(run);
        assertThat(reference.observation()).isEqualTo(report);
        assertThat(reference.reportMetadata().path("reportId").asText()).isEqualTo("60");
        assertThat(reference.reportMetadata().path("title").asText()).isEqualTo("原咖啡专题");
        assertThat(service.requireOwnedObservationForAction(USER,CONVERSATION,report.observationId(),REPORT)).isEqualTo(report);
        assertThat(service.requireOwnedObservationForAction(run,report.observationId(),REPORT)).isEqualTo(report);
        assertThat(service.isOwnedObservationAvailableForAction(USER,CONVERSATION,report.observationId(),REPORT)).isTrue();
        assertThat(events).hasSize(2);
    }
    @Test void noReportIsAnActionBarrierAndInvalidAnchorsCannotFallBack() {
        var search=search("GEO_TOPIC",TOPIC);
        var absent=service.read(RUN,"ref",new ReadRequest("report","LATEST_GEO_REPORT",search.candidateSetId(),"choice-50",null,null,null));
        var reference=service.currentRunLatestReportReference(run);
        assertThat(reference.observation()).isEqualTo(absent);
        assertThat(reference.reportMetadata()).isNull();
        assertCode(() -> service.requireOwnedObservationForAction(USER,CONVERSATION,absent.observationId(),REPORT),ErrorCode.RESOURCE_NOT_FOUND);
        reset=9;
        // latest-for-Run still has a resource anchor, while ownership/reset validation rejects it.
        when(mapper.latestReportReadForRun(USER,CONVERSATION,RUN)).thenReturn(Optional.of(events.get(Long.parseLong(absent.observationId()))));
        assertCode(() -> service.currentRunLatestReportReference(run),ErrorCode.RESOURCE_NOT_FOUND);
    }
    @Test void frozenPriorReportReferenceSurvivesAssetReadButNeverSelectsDynamicLatest() {
        var search=search("GEO_TOPIC",TOPIC); stubReport();
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(GEO_RUN,"SUCCEEDED",NOW,REPORT)));
        var report=service.read(RUN,"ref",new ReadRequest("report","LATEST_GEO_REPORT",search.candidateSetId(),"choice-50",null,null,null));
        ObjectNode snapshot=json.createObjectNode(); snapshot.set("lastReportReadContext",json.valueToTree(report));
        var space=search("SPACE",SPACE);
        var inventory=service.read(RUN,"ref",new ReadRequest("inventory","ASSET_INVENTORY",space.candidateSetId(),"choice-30",null,null,null));
        snapshot.set("lastReadContext",json.valueToTree(inventory));
        run=run(snapshot.toString(),"RUNNING","[]");
        when(mapper.latestReportReadForRun(USER,CONVERSATION,RUN)).thenReturn(Optional.empty());
        var reference=service.currentRunLatestReportReference(run);
        assertThat(reference.observation()).isEqualTo(report);
        verify(mapper,times(1)).latestAttempt(USER,TOPIC);
        when(reports.findReport(REPORT,USER)).thenReturn(Optional.empty());
        assertCode(() -> service.currentRunLatestReportReference(run),ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(service.isOwnedObservationAvailableForAction(USER,CONVERSATION,report.observationId(),REPORT)).isFalse();
    }
    @Test void evidenceActionMetadataFollowsFrozenParentButKeepsNewObservationIdentity() {
        var search=search("GEO_TOPIC",TOPIC); stubReport();
        var sample=new GeoTaskRecords.Observation(90,GEO_RUN,80,USER,UUID.randomUUID(),"a".repeat(64),"unit-90",1,
                91,"谁的咖啡好","DOUBAO",1,"SUCCEEDED","真实回答",null,"p","m",NOW,NOW,10,"{}",1,NOW);
        when(reports.findObservationPage(GEO_RUN,0,7)).thenReturn(List.of(sample));
        when(reports.findObservationByUnit(GEO_RUN,"unit-90")).thenReturn(Optional.of(sample));
        when(mapper.latestAttempt(USER,TOPIC)).thenReturn(Optional.of(new RunResourceMapper.GeoAttempt(GEO_RUN,"SUCCEEDED",NOW,REPORT)));
        var report=service.read(RUN,"ref",new ReadRequest("report","LATEST_GEO_REPORT",search.candidateSetId(),"choice-50",null,null,null));
        var evidence=service.read(RUN,"ref",new ReadRequest("evidence","GEO_ANSWER_EVIDENCE",null,null,report.observationId(),null,List.of("unit-90")));
        var reference=service.currentRunLatestReportReference(run);
        assertThat(reference.observation()).isEqualTo(evidence);
        assertThat(reference.observation().projection().has("report")).isFalse();
        assertThat(reference.reportMetadata().path("reportId").asText()).isEqualTo("60");
        assertThat(service.requireOwnedObservationForAction(USER,CONVERSATION,evidence.observationId(),REPORT)).isEqualTo(evidence);
        assertCode(() -> service.requireOwnedObservationForAction(USER,CONVERSATION,report.observationId(),REPORT),ErrorCode.RESOURCE_NOT_FOUND);
        var original=events.get(Long.parseLong(evidence.observationId()));
        ObjectNode payload=(ObjectNode)tree(original.payloadJson());
        ((ObjectNode)payload.path("input")).put("observationId",evidence.observationId());
        events.put(original.id(),new ConversationEventRecord(original.id(),original.conversationId(),original.userId(),original.agentRunId(),
                original.messageId(),original.taskId(),original.sourceEventId(),original.eventType(),original.sequenceNo(),payload.toString(),original.occurredAt(),original.createdAt()));
        assertCode(() -> service.currentRunLatestReportReference(run),ErrorCode.RESOURCE_NOT_FOUND);
    }
    @Test void actionAvailabilityDoesNotHideDatabaseFailures() {
        when(mapper.latestRead(eq(USER),eq(CONVERSATION),anyLong(),eq(true))).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.isOwnedObservationAvailableForAction(USER,CONVERSATION,"101",REPORT))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({"context,search", "context,read", "context-installation,search", "context-installation,read",
            "locked-installation,search", "locked-installation,read", "knowledge,search", "knowledge,read",
            "memory,search", "memory,read"})
    void transactionalResourceDenialsKeepBusinessErrorWithoutRollbackOnly(String rejection, String endpoint) throws Exception {
        var transaction = transactionalResourceFixture(rejection);
        assertCode(() -> resourceRequest(transaction.resources(), endpoint), ErrorCode.RESOURCE_NOT_FOUND);
        verify(transaction.connection()).commit();
        verify(transaction.connection(), never()).rollback();
        verify(conversations, never()).insertEvent(any());
        verify(mapper, never()).search(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test void transactionalResourceTechnicalFailureStillRollsBack() throws Exception {
        var transaction = transactionalResourceFixture("technical");
        assertThatThrownBy(() -> resourceRequest(transaction.resources(), "search"))
                .isInstanceOf(IllegalStateException.class).hasMessage("installation storage unavailable");
        verify(transaction.connection()).rollback();
        verify(transaction.connection(), never()).commit();
        verify(conversations, never()).insertEvent(any());
    }

    @Test void transactionalWriteCallerStillRollsBackOnReadAuthorizationDenial() throws Exception {
        var transaction = transactionalResourceFixture("context");
        var actions = new RunActionService(transaction.contexts(), conversations, mock(RunToolAuthorizationService.class),
                mock(RunActionMapper.class), platform, new AgentPlatformProperties(), mock(MonitorExecutionMapper.class),
                mock(ProjectGeoTargetService.class), mock(KnowledgeService.class), mock(GeoService.class),
                mock(TaskControlService.class), new PaidTaskProperties(), json, mock(jakarta.validation.Validator.class));
        var transactionalActions = transactionalProxy(actions, transaction.manager());
        assertCode(() -> transactionalActions.execute(RUN, "ref",
                new RunActionDtos.Request("write-denial", "brand.save", json.createObjectNode())), ErrorCode.RESOURCE_NOT_FOUND);
        verify(transaction.connection()).rollback();
        verify(transaction.connection(), never()).commit();
    }

    private void resourceRequest(RunResourceService resources, String endpoint) {
        if ("search".equals(endpoint)) resources.search(RUN, "ref", new SearchRequest("transaction-search", "SPACE", "coffee", null));
        else resources.read(RUN, "ref", new ReadRequest("transaction-read", "ASSET_INVENTORY", "101", "choice-30", null, null, null));
    }

    /** Real service annotations and nested Spring proxies, with only the JDBC transport mocked. */
    private TransactionalResourceFixture transactionalResourceFixture(String rejection) throws Exception {
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        var manager = new JdbcTransactionManager(source);
        var registrations = mock(AgentPlatformMapper.class);
        when(registrations.ownsConversation(USER, CONVERSATION)).thenReturn(true);
        var definition = new AgentPlatformRecords.Definition("geo", "1.0.0", "GEO", "test",
                "[\"chat\",\"resources.read\",\"geo.read\"]", "geo", "PUBLIC", "PUBLISHED");
        when(registrations.definition("geo", "1.0.0")).thenReturn(Optional.of(definition));
        when(registrations.lockDefinition("geo", "1.0.0")).thenReturn(Optional.of(definition));
        var actualPlatform = transactionalProxy(new AgentPlatformService(registrations, new AgentPlatformProperties(),
                ids, new CatalogNameNormalizer(), json), manager);
        var catalog = new AgentRuntimeCatalog();
        var identity = catalog.freeze(new AgentPlatformDtos.AgentIdentity(null, "geo", "1.0.0", "GEO", null,
                List.of("chat", "resources.read", "geo.read")));
        var context = new InternalRunContextDtos.RunContext(RUN.toString(), Long.toString(CONVERSATION), 1,
                new InternalRunContextDtos.Space(Long.toString(SPACE), "space", null, null), null, List.of(), List.of());
        var snapshot = (ObjectNode) json.valueToTree(context);
        snapshot.set("agentIdentity", json.valueToTree(identity));
        snapshot.set("protocolFeatures", json.valueToTree(new InternalRunContextDtos.ProtocolFeatures(true, true)));
        var actualContexts = new RunContextService(conversations, json);
        ReflectionTestUtils.setField(actualContexts, "accounts", accounts);
        ReflectionTestUtils.setField(actualContexts, "agentPlatform", actualPlatform);
        if (Set.of("knowledge", "memory").contains(rejection)) {
            var knowledgeMapper = mock(KnowledgeMapper.class);
            var projects = mock(AgentPlatformMapper.class);
            var active = new AgentPlatformRecords.Project(SPACE, USER, "space", "space", "ACTIVE", 1, NOW, NOW);
            when(projects.project(USER, SPACE)).thenReturn(Optional.of(active), Optional.empty());
            if ("knowledge".equals(rejection)) when(projects.project(USER, SPACE))
                    .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            var memory = transactionalProxy(new AgentMemoryService(knowledgeMapper, projects, ids), manager);
            @SuppressWarnings("unchecked") ObjectProvider<ObjectStorage> storage = mock(ObjectProvider.class);
            var knowledge = new KnowledgeService(knowledgeMapper, projects, ids, json, mock(KnowledgeParser.class),
                    new KnowledgeProperties(), storage, new AssetIngestionProperties(), manager);
            ReflectionTestUtils.setField(knowledge, "memoryService", memory);
            ReflectionTestUtils.setField(actualContexts, "knowledgeService", transactionalProxy(knowledge, manager));
            snapshot.set("knowledgeContext", json.valueToTree(new KnowledgeDtos.KnowledgeContext(List.of(), List.of(), null, false, 0, 0)));
        }
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String refHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("ref".getBytes(StandardCharsets.UTF_8)));
        run = new AgentRunRecord(RUN, CONVERSATION, USER, 21, 22L, SPACE, null, "[]", "RUNNING", 1,
                snapshot.toString(), refHash, now.plusMinutes(5), null, 0, "{}", null, "trace", null,
                now, null, now, now, now.plusMinutes(5));
        switch (rejection) {
            case "context" -> when(accounts.findIdentityById(USER)).thenReturn(Optional.empty());
            case "context-installation" -> when(registrations.ownsConversation(USER, CONVERSATION)).thenReturn(false);
            case "locked-installation" -> when(registrations.ownsConversation(USER, CONVERSATION)).thenReturn(true, false);
            case "technical" -> when(registrations.ownsConversation(USER, CONVERSATION)).thenReturn(true)
                    .thenThrow(new IllegalStateException("installation storage unavailable"));
            default -> { }
        }
        var proxiedContexts = transactionalProxy(actualContexts, manager);
        var authorization = new RunToolAuthorizationService(conversations, accounts, Optional.of(actualPlatform), catalog);
        var resources = new RunResourceService(mapper, conversations, proxiedContexts, authorization, accounts,
                spaces, products, topics, reports, assets, geo, new CatalogNameNormalizer(), ids, json);
        return new TransactionalResourceFixture(transactionalProxy(resources, manager), proxiedContexts, manager, connection);
    }

    private <T> T transactionalProxy(T target, JdbcTransactionManager manager) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        @SuppressWarnings("unchecked") T result = (T) proxy.getProxy();
        return result;
    }

    private record TransactionalResourceFixture(RunResourceService resources, RunContextService contexts,
            JdbcTransactionManager manager, Connection connection) { }
    private SearchResponse search(String kind,long resource) {
        when(mapper.search(USER,kind,"coffee",null,null)).thenReturn(List.of(match(resource,true,1)));
        return service.search(RUN,"ref",new SearchRequest("search-"+events.size(),kind,"coffee",null));
    }
    private RunResourceMapper.MatchedResource match(long id,boolean exact,long total) {
        return new RunResourceMapper.MatchedResource(id,"coffee",id==31?31:SPACE,"store",id==PRODUCT?PRODUCT:null,exact,false,total);
    }
    private Optional<ConversationEventRecord> latest(String kind,boolean reportOnly) {
        if(reset>0) return Optional.empty();
        return events.values().stream().filter(e -> {
            var p=tree(e.payloadJson());
            return p.path("kind").asText().equals(kind) && p.path("success").asBoolean()
                    && (!reportOnly || Set.of("LATEST_GEO_REPORT","GEO_ANSWER_EVIDENCE").contains(p.path("purpose").asText()));
        }).reduce((a,b) -> b);
    }
    private AgentRunRecord run(String snapshot,String status,String attachments) {
        return new AgentRunRecord(RUN,CONVERSATION,USER,21,22L,SPACE,null,attachments,status,1,snapshot,"hash",
                NOW.plusDays(3),null,0,"{}",null,"trace",null,NOW,null,NOW,NOW,NOW.plusDays(3));
    }
    private BusinessSpaceRecord space(long id,String name) {
        return new BusinessSpaceRecord(id,USER,name,name,"光禾","{}",null,1,NOW,NOW,1,0);
    }
    private ProductRecord product(String parameters) {
        return new ProductRecord(PRODUCT,USER,SPACE,"coffee","coffee","food",null,"[]",parameters,"[]","[]","[]",null,1,NOW,NOW,0);
    }
    private void stubReport() {
        when(reports.findReport(REPORT,USER)).thenReturn(Optional.of(new GeoTaskRecords.Report(REPORT,GEO_RUN,80,USER,TOPIC,
                "SUCCEEDED","{}","{}","summary","geo-synthesis-v1",1,NOW,NOW,1,0)));
        when(reports.findRun(GEO_RUN,USER)).thenReturn(Optional.of(new GeoTaskRecords.Run(GEO_RUN,80,USER,SPACE,PRODUCT,TOPIC,
                "SUCCEEDED","QUICK",1,1,0,0,"{\"authorizedContext\":{\"topic\":{\"id\":\"50\",\"name\":\"原咖啡专题\"}}}","v1","v1",NOW,NOW,NOW,NOW,1,1,0)));
        ObjectNode projection=json.createObjectNode().put("status","READY");
        projection.putObject("report").put("reportId","60");
        when(geo.create(USER,SPACE,PRODUCT,REPORT)).thenReturn(projection);
    }
    private JsonNode tree(String value) { try { return json.readTree(value); } catch(Exception e) { throw new AssertionError(e); } }
    private int bytes(JsonNode value) { try { return json.writeValueAsBytes(value).length; } catch(Exception e) { throw new AssertionError(e); } }
    private void assertCode(Runnable work,ErrorCode code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
