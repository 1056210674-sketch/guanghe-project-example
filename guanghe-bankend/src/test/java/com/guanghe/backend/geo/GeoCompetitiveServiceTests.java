package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.competitor.*;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.task.PaidTaskProperties;
import jakarta.validation.Validation;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GeoCompetitiveServiceTests {
    private static final OffsetDateTime NOW=OffsetDateTime.parse("2026-09-28T08:00:00Z");
    private final GeoCompetitiveMapper work=mock(GeoCompetitiveMapper.class);
    private final BusinessSpaceService spaces=mock(BusinessSpaceService.class);
    private final GeoTaskMapper reports=mock(GeoTaskMapper.class);
    private final GeoMapper topics=mock(GeoMapper.class);
    private final GeoInterpretationMapper interpretation=mock(GeoInterpretationMapper.class);
    private final GeoReportInterpretationService builder=mock(GeoReportInterpretationService.class);
    private final CompetitorService catalog=mock(CompetitorService.class);
    private final GeoService geo=mock(GeoService.class);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final SnowflakeIdGenerator ids=mock(SnowflakeIdGenerator.class);
    private final PaidTaskProperties properties=new PaidTaskProperties();
    private final AuthenticatedAccount account=new AuthenticatedAccount(41,"user",AccountRole.USER,1,AccountStatus.ACTIVE,null,null,null);
    private final AtomicReference<GeoCompetitiveRecords.Insight> state=new AtomicReference<>();
    private final Map<String,GeoCompetitiveRecords.Candidate> candidates=new HashMap<>();
    private GeoCompetitiveService service;
    private GeoTaskRecords.Report report;
    private GeoTaskRecords.Run run;
    private ObjectNode snapshot;

    @BeforeEach void setup()throws Exception {
        properties.setGeoInterpretationEnabled(true);
        service=new GeoCompetitiveService(work,spaces,reports,topics,interpretation,builder,catalog,geo,json,ids,properties,true,
                Validation.buildDefaultValidatorFactory().getValidator(),ObservabilitySupport.noop(),Clock.fixed(NOW.toInstant(),ZoneOffset.UTC));
        snapshot=(ObjectNode)json.readTree("""
                {"authorizedContext":{"topic":{"id":"201"},"questions":[
                  {"id":"301","category":"BRAND_DISCOVERY","text":"有什么保温杯？"},
                  {"id":"302","category":"BRAND_DISCOVERY","text":"禾序轻行杯怎么样？"},
                  {"id":"303","category":"BRAND_DISCOVERY","text":"便携保温杯推荐？"}],"competitors":[]},
                 "parameters":{"platformCodes":["DOUBAO"],"sampleMode":"STANDARD"},
                 "catalogSnapshot":{"space":{"id":"101","brand":{"name":"禾序","aliases":["Hexu"]}},
                   "product":{"id":"102","spaceId":"101","name":"禾序轻行杯","aliases":[],"category":"饮具"}}}
                """);
        run=new GeoTaskRecords.Run(501,401,41,101,102L,201,"PARTIAL","STANDARD",3,1,1,1,snapshot.toString(),"geo-observation-v1","geo-synthesis-v1",NOW,NOW,NOW,NOW,6,2,4);
        report=new GeoTaskRecords.Report(701,501,401,41,201,"PARTIAL","{}","{}","原报告","geo-synthesis-v1",3,NOW,NOW,2,4);
        when(reports.findReport(701,41)).thenReturn(Optional.of(report));when(reports.findRun(501,41)).thenReturn(Optional.of(run));
        when(reports.lockOwner(41)).thenReturn(Optional.of(41L));when(reports.lockReport(701,41)).thenReturn(Optional.of(701L));
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of(answer(301,"有什么保温杯？","松野杯轻便易清洗。")));
        when(work.find(701,41)).thenAnswer(i->Optional.ofNullable(state.get()));when(work.lock(701,41)).thenAnswer(i->Optional.ofNullable(state.get()));
        when(work.lockNext(NOW)).thenAnswer(i->Optional.ofNullable(state.get()));
        when(interpretation.executionEligible(41,401)).thenReturn(true);
        when(builder.evidence(eq(run),anyList())).thenAnswer(i->json.createObjectNode().put("frozen","evidence"));
        AtomicLong sequence=new AtomicLong(801);when(ids.nextId()).thenAnswer(i->sequence.getAndIncrement());
        when(work.upsertCandidate(any())).thenAnswer(i->{var c=i.<GeoCompetitiveRecords.Candidate>getArgument(0);candidates.putIfAbsent(c.nameNormalized(),c);return 1;});
        when(work.findName(eq(41L),eq(201L),anyString())).thenAnswer(i->Optional.ofNullable(candidates.get(i.getArgument(2))));
        when(work.compatibleBrandCandidates(eq(41L),eq(201L),anyString())).thenAnswer(i->candidates.values().stream()
                .filter(c->"BRAND".equals(c.entityKind())&&(c.nameNormalized().equals(i.<String>getArgument(2))
                        ||c.nameNormalized().matches(java.util.regex.Pattern.quote(i.getArgument(2))+"\\([a-z]{2,40}\\)")))
                .sorted(Comparator.comparingLong(GeoCompetitiveRecords.Candidate::id)).limit(2).toList());
        when(work.candidate(anyLong(),eq(41L))).thenAnswer(i->candidates.values().stream().filter(c->c.id()==i.<Long>getArgument(0)).findFirst());
        when(work.reportCandidates(eq(41L),anyList())).thenAnswer(i->candidates.values().stream().filter(c->i.<List<Long>>getArgument(1).contains(c.id())).toList());
        when(work.lockCandidate(anyLong(),eq(41L))).thenAnswer(i->candidates.values().stream().filter(c->c.id()==i.<Long>getArgument(0)).findFirst());
        when(work.insertAutomatic(anyLong(),eq(701L),eq(41L),anyString(),anyString(),eq(NOW))).thenAnswer(i->{state.set(new GeoCompetitiveRecords.Insight(i.getArgument(0),701,41,i.getArgument(3),"SUCCEEDED",0,null,null,null,i.getArgument(4),null,null,NOW,NOW,NOW));return 1;});
        when(work.enqueue(anyLong(),eq(701L),eq(41L),anyString(),any(),eq(NOW))).thenAnswer(i->{state.set(new GeoCompetitiveRecords.Insight(i.getArgument(0),701,41,i.getArgument(3),"QUEUED",1,null,null,null,null,null,null,NOW,NOW.plusSeconds(120),null));return 1;});
        when(work.claim(anyLong(),any(),eq(NOW))).thenAnswer(i->{var old=state.get();state.set(new GeoCompetitiveRecords.Insight(old.id(),701,41,old.inputHash(),"RUNNING",old.attempts(),i.getArgument(1),NOW.plusSeconds(90),NOW.plusSeconds(60),null,null,null,NOW,NOW.plusSeconds(120),null));return 1;});
        when(work.finish(anyLong(),anyInt(),any(),anyString(),any(),any(),any(),eq(NOW))).thenReturn(1);
    }

    @Test void oldReportGetIsReadOnlyAndExplicitlyEligible() {
        var result=service.get(account,701,"DISCOVERY");assertThat(result.status()).isEqualTo("NOT_ANALYZED");assertThat(result.retryAllowed()).isTrue();
        verify(work,never()).enqueue(anyLong(),anyLong(),anyLong(),anyString(),any(),any());verifyNoInteractions(builder,catalog,geo);
    }
    @Test void newSynthesisAutomaticallyFreezesCandidatesWithoutAnotherJobOrProviderUsage()throws Exception {
        ingest(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","POSITIVE_ATTRIBUTE"));
        assertThat(state.get().status()).isEqualTo("SUCCEEDED");assertThat(state.get().attempts()).isZero();
        var result=service.get(account,701,"DISCOVERY");assertThat(result.candidates()).hasSize(1);assertThat(result.candidates().getFirst().learningSuggestions().getFirst()).contains("松野杯轻便易清洗","不表示这些做法导致");
        var entity=result.platforms().getFirst().entities().stream().filter(e->e.role().equals("DISCOVERED")).findFirst().orElseThrow();
        assertThat(entity.mentionRate()).isEqualTo(100.0);assertThat(entity.evidence().getFirst().unitKey()).isEqualTo("301:DOUBAO:1");
        verify(work,never()).enqueue(anyLong(),anyLong(),anyLong(),anyString(),any(),any());verifyNoInteractions(builder,catalog,geo);
    }
    @Test void optionalDiscoveryMissingDoesNotInventCandidatesOrJob() {
        service.ingest(report,run,List.of(),new GeoTaskDtos.SynthesisInput("原解读",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"geo-synthesis-v1"));
        assertThat(state.get()).isNull();verifyNoInteractions(work);
    }
    @Test void selfGenericMarketplaceFakeUnitAndHallucinatedQuotesAreDiscarded()throws Exception {
        ingest(candidate("禾序","301:DOUBAO:1","禾序","MENTION"),candidate("保温杯","301:DOUBAO:1","保温杯","MENTION"),
                candidate("京东","301:DOUBAO:1","京东","MENTION"),candidate("松野杯","foreign:DOUBAO:1","松野杯轻便易清洗。","MENTION"),
                candidate("不存在品牌","301:DOUBAO:1","不存在品牌更好","POSITIVE_ATTRIBUTE"));
        assertThat(candidates).isEmpty();assertThat(state.get().status()).isEqualTo("SUCCEEDED");
        assertThat(service.get(account,701,"DISCOVERY").reasonCode()).isEqualTo("CANDIDATES_REJECTED");
    }
    @Test void pureSourceAndUrlMentionsDoNotBecomeCandidates()throws Exception {
        var row=answer(301,"有什么保温杯？","正文未提及品牌。\n参考资料：\n松野杯官网 https://example.com");
        service.ingest(report,run,List.of(row),synthesis(List.of(candidate("松野杯","301:DOUBAO:1","松野杯官网","MENTION"))));
        assertThat(candidates).isEmpty();assertThat(GeoCompetitiveService.narrative("[松野杯](https://example.com)" )).isBlank();
        assertThat(GeoCompetitiveService.acceptableName("Hexu",List.of("Hexu"))).isFalse();
    }
    @Test void invalidFirstProposalCannotShadowLaterVerifiedSameName() {
        ingest(candidate("松野杯","bad-unit","松野杯不存在的描述","MENTION"),candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","MENTION"));
        assertThat(candidates).hasSize(1);assertThat(service.get(account,701,"DISCOVERY").candidates()).hasSize(1);
    }
    @Test void fullSavedAnswersNotCandidateExcerptsDetermineCountsAndFailuresAreNotZero()throws Exception {
        var first=answer(301,"有什么保温杯？","松野杯轻便易清洗。");var second=answer(303,"便携保温杯推荐？","前言".repeat(7000)+"松野杯也有出现。");
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of(first,second));
        service.ingest(report,run,List.of(first,second),synthesis(List.of(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","MENTION"))));
        var p=service.get(account,701,"DISCOVERY").platforms().getFirst();assertThat(p.validUnits()).isEqualTo(2);
        assertThat(p.entities().getLast().mentionedUnits()).isEqualTo(2);assertThat(p.entities().getLast().mentionRate()).isEqualTo(100.0);
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of());
        p=service.get(account,701,"DISCOVERY").platforms().getFirst();assertThat(p.validUnits()).isZero();assertThat(p.entities().getLast().mentionRate()).isNull();
    }
    @Test void discoveryAndBrandValidationNeverShareDenominators() {
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of(answer(301,"有什么保温杯？","自家没出现"),answer(302,"禾序轻行杯怎么样？","禾序轻行杯出现")));
        assertThat(service.get(account,701,"DISCOVERY").platforms().getFirst().entities().getFirst().mentionRate()).isZero();
        assertThat(service.get(account,701,"BRAND_VALIDATION").platforms().getFirst().entities().getFirst().mentionRate()).isEqualTo(100.0);
    }
    @Test void successfulCacheNeverReingestsLaterDifferentIdentities()throws Exception {
        ingest(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","MENTION"));String before=state.get().resultJson();
        ingest(candidate("别的品牌","301:DOUBAO:1","别的品牌","MENTION"));assertThat(state.get().resultJson()).isEqualTo(before);
        assertThat(service.analyze(account,701).status()).isEqualTo("SUCCEEDED");verify(work,never()).enqueue(anyLong(),anyLong(),anyLong(),anyString(),any(),any());
    }
    @Test void explicitAnalyzeIsIdempotentWhileActiveAndUsesSixtySecondTwoCallBudget() {
        assertThat(service.analyze(account,701).status()).isEqualTo("QUEUED");assertThat(service.analyze(account,701).status()).isEqualTo("QUEUED");
        var claim=service.claimNext().orElseThrow();assertThat(claim.request().path("maxProviderCalls").asInt()).isEqualTo(2);
        assertThat(claim.work().deadlineAt()).isEqualTo(NOW.plusSeconds(60));verify(work,times(1)).enqueue(anyLong(),anyLong(),anyLong(),anyString(),any(),any());
    }
    @Test void interpretationAdmissionPreventsOverlappingHeavyWork() {
        when(interpretation.countActive(41)).thenReturn(1);assertThat(service.get(account,701,"DISCOVERY").retryAllowed()).isFalse();
        assertThatThrownBy(()->service.analyze(account,701)).isInstanceOf(BusinessException.class);verify(work,never()).enqueue(anyLong(),anyLong(),anyLong(),anyString(),any(),any());
    }
    @Test void completedHistoryAnalysisDoesNotPublishOriginalReportOrAccounting()throws Exception {
        service.analyze(account,701);var claim=service.claimNext().orElseThrow();assertThat(service.completed(claim,payload(claim))).isNull();
        verify(work).finish(anyLong(),eq(1),any(),eq("SUCCEEDED"),isNull(),contains("松野杯"),contains("DEEPSEEK"),eq(NOW));
        verify(interpretation,never()).publish(anyLong(),anyLong(),anyString(),anyString(),anyString());verifyNoInteractions(catalog,geo);
        assertThat(mockingDetails(reports).getInvocations()).allMatch(v->v.getMethod().getName().startsWith("find")||v.getMethod().getName().startsWith("lock"));
    }
    @Test void invalidDiscoveryOrStaleLeaseCannotPublishHistoryResult()throws Exception {
        service.analyze(account,701);var claim=service.claimNext().orElseThrow();var payload=payload(claim);
        ((ObjectNode)payload.path("synthesis")).remove("competitorDiscoveryVersion");assertThat(service.completed(claim,payload)).isEqualTo("INVALID_RESULT");
        state.set(new GeoCompetitiveRecords.Insight(claim.work().id(),701,41,claim.work().inputHash(),"RUNNING",1,UUID.randomUUID(),NOW.plusSeconds(90),NOW.plusSeconds(60),null,null,null,NOW,NOW,null));
        assertThat(service.completed(claim,payload(claim))).isEqualTo("LEASE_EXPIRED");verify(work,never()).upsertCandidate(any());
    }
    @Test void attemptsExhaustedCannotRetryButCapacityOnlyFailureCan() {
        state.set(failed("TIMEOUT"));assertThat(service.get(account,701,"DISCOVERY").retryAllowed()).isFalse();
        assertThatThrownBy(()->service.analyze(account,701)).isInstanceOf(BusinessException.class);
        state.set(failed("CAPACITY_FULL"));assertThat(service.get(account,701,"DISCOVERY").retryAllowed()).isTrue();
    }
    @Test void ignoredTombstoneRemainsIgnoredAndCannotBeConfirmedByOldVersion()throws Exception {
        ingest(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","MENTION"));var c=candidates.values().iterator().next();
        var ignored=new GeoCompetitiveRecords.Candidate(c.id(),41,101,201,c.name(),c.nameNormalized(),c.entityKind(),"IGNORED",2,null,701,701,c.evidenceJson(),NOW,NOW);
        candidates.put(c.nameNormalized(),ignored);state.set(null);ingest(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","MENTION"));
        assertThat(candidates.get(c.nameNormalized()).status()).isEqualTo("IGNORED");
        assertThatThrownBy(()->service.confirm(account,c.id(),new GeoCompetitiveDtos.ConfirmRequest(1,1,null,CompetitorType.DIRECT,true))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(catalog,geo);
    }
    @Test void ownerIsCheckedBeforeAnyEvidenceAndForeignCandidateIsNotFound() {
        assertThatThrownBy(()->service.get(account,999,"DISCOVERY")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.ignore(account,999,new GeoCompetitiveDtos.IgnoreRequest(1))).isInstanceOf(BusinessException.class);
        verify(reports,never()).findRun(eq(999L),anyLong());verify(work,never()).decide(anyLong(),anyLong(),anyInt(),anyString(),any());
    }
    @Test void personalCreativeCandidateCannotBeIgnoredThroughTheProjectApi() throws Exception {
        ingest(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","MENTION"));
        var value=candidates.values().iterator().next();
        when(spaces.requireProjectSpace(41,101)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        assertThatThrownBy(()->service.ignore(account,value.id(),new GeoCompetitiveDtos.IgnoreRequest(value.version())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error->assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        verify(work,never()).decide(anyLong(),anyLong(),anyInt(),anyString(),any());
    }
    @Test void confirmationRequiresCurrentTopicAndCreatesNoCatalogEntryOnConflict()throws Exception {
        ingest(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","MENTION"));var c=candidates.values().iterator().next();
        when(geo.get(account,201)).thenReturn(topic(3,List.of()));
        assertThatThrownBy(()->service.confirm(account,c.id(),new GeoCompetitiveDtos.ConfirmRequest(1,2,null,CompetitorType.DIRECT,true))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(catalog);
    }
    @Test void nameConflictRequiresExplicitLinkAndInactiveOrOtherSpaceIsRejected()throws Exception {
        ingest(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","MENTION"));var c=candidates.values().iterator().next();when(geo.get(account,201)).thenReturn(topic(1,List.of()));
        when(work.existingCompetitor(41,101,"松野杯")).thenReturn(Optional.of(999L));
        assertThatThrownBy(()->service.confirm(account,c.id(),new GeoCompetitiveDtos.ConfirmRequest(1,1,null,CompetitorType.DIRECT,true))).isInstanceOf(BusinessException.class);
        when(catalog.requireCompetitor(41,999)).thenReturn(new CompetitorRecord(999,41,101,"松野杯","松野杯",CompetitorType.DIRECT,null,CompetitorStatus.INACTIVE,1,NOW,NOW,0));
        assertThatThrownBy(()->service.confirm(account,c.id(),new GeoCompetitiveDtos.ConfirmRequest(1,1,"999",CompetitorType.DIRECT,true))).isInstanceOf(BusinessException.class);
        verify(catalog,never()).create(any(),anyLong(),any());verify(geo,never()).replaceCompetitors(any(),anyLong(),any());
    }
    @Test void confirmLinksExistingAppendsCurrentSetAndDoesNotChangePlans()throws Exception {
        ingest(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","MENTION"));var c=candidates.values().iterator().next();when(geo.get(account,201)).thenReturn(topic(2,List.of("888")));
        when(catalog.requireCompetitor(41,999)).thenReturn(new CompetitorRecord(999,41,101,"松野杯","松野杯",CompetitorType.DIRECT,null,CompetitorStatus.ACTIVE,1,NOW,NOW,0));
        when(geo.replaceCompetitors(eq(account),eq(201L),any())).thenReturn(topic(3,List.of("888","999")));
        when(work.decide(c.id(),41,1,"CONFIRMED",999L)).thenReturn(1);
        var result=service.confirm(account,c.id(),new GeoCompetitiveDtos.ConfirmRequest(1,2,"999",CompetitorType.DIRECT,true));
        assertThat(result.existingPlansChanged()).isFalse();verify(geo).replaceCompetitors(account,201,new GeoDtos.ReplaceCompetitorsRequest(2,List.of("888","999")));
        verify(catalog,never()).create(any(),anyLong(),any());
    }
    @Test void fullWidthNameDedupAndLatinBoundaryAreDeterministic() {
        assertThat(GeoVisibilityProjection.normalize(" Ａｃｍｅ ")).isEqualTo("acme");assertThat(GeoVisibilityProjection.containsName("AcmePlus",List.of("Acme"))).isFalse();
        assertThat(GeoVisibilityProjection.containsName("Acme is mentioned",List.of("Acme"))).isTrue();
        assertThat(GeoCompetitiveService.acceptableName("禾序保温杯",List.of("禾序"))).isFalse();
        assertThat(GeoCompetitiveService.acceptableName("Acme Plus",List.of("Acme"))).isFalse();
        assertThat(GeoCompetitiveService.acceptableName("AcmePlus",List.of("Acme"))).isTrue();
        assertThat(GeoCompetitiveService.acceptableName("Pineapple",List.of("Apple"))).isTrue();
    }
    @Test void failedAndMissingAnswersAreSeparateAndUnknownHistoricalMethodStillWorks() {
        var failed=new GeoTaskRecords.Observation(902,501,401,41,UUID.randomUUID(),"a".repeat(64),"303:DOUBAO:1",2,303,"便携保温杯推荐？","DOUBAO",1,"FAILED",null,"TIMEOUT","DOUBAO","doubao",NOW,NOW,10,"{}",1,NOW,null);
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of(answer(301,"有什么保温杯？","禾序轻行杯出现"),failed));
        var p=service.get(account,701,"DISCOVERY").platforms().getFirst();assertThat(p.expectedUnits()).isEqualTo(2);assertThat(p.validUnits()).isEqualTo(1);assertThat(p.failedUnits()).isEqualTo(1);assertThat(p.missingUnits()).isZero();
        assertThat(p.entities().getFirst().mentionRate()).isEqualTo(100.0);
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of(failed));p=service.get(account,701,"DISCOVERY").platforms().getFirst();
        assertThat(p.entities().getFirst().mentionedUnits()).isNull();assertThat(p.entities().getFirst().mentionRate()).isNull();assertThat(p.missingUnits()).isEqualTo(1);
    }
    @Test void boundedPagedCandidateListResolvesCatalogInOneBatch()throws Exception {
        ingest(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","MENTION"));var c=candidates.values().iterator().next();
        when(topics.findTopic(201,41)).thenReturn(Optional.of(new GeoRecords.Topic(201,41,101,102L,"主题","主题",null,1,NOW,NOW)));
        when(work.candidates(41,201,"PENDING",20,20)).thenReturn(List.of(c));when(work.countCandidates(41,201,"PENDING")).thenReturn(21L);
        when(work.existingCompetitors(41,101,List.of("松野杯"))).thenReturn(List.of(new GeoCompetitiveRecords.CatalogMatch("松野杯",999L)));
        var result=service.candidates(account,201,"PENDING",2,20);assertThat(result.total()).isEqualTo(21);assertThat(result.hasNext()).isFalse();assertThat(result.items().getFirst().existingCompetitorId()).isEqualTo("999");
        verify(work,never()).existingCompetitor(anyLong(),anyLong(),anyString());
        assertThatThrownBy(()->service.candidates(account,201,"PENDING",0,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.candidates(account,201,"PENDING",1,101)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.candidates(account,201,"ALL",1,20)).isInstanceOf(BusinessException.class);
    }
    @Test void malformedOptionalCandidatesCannotBreakOriginalReportAndMismatchedRunCannotIngest() {
        var garbage=json.createArrayNode().add("ignored");service.ingest(report,run,List.of(),synthesis(List.of(garbage,json.nullNode())));
        assertThat(state.get().status()).isEqualTo("SUCCEEDED");assertThat(candidates).isEmpty();
        state.set(null);var foreign=new GeoTaskRecords.Report(702,999,401,41,201,"PARTIAL","{}","{}","报告","geo-synthesis-v1",1,NOW,NOW,2,4);
        service.ingest(foreign,run,List.of(),synthesis(List.of()));assertThat(state.get()).isNull();
    }
    @Test void legacySynthesisSerializationDoesNotChangeCanonicalNullFieldSet()throws Exception {
        var old=new GeoTaskDtos.SynthesisInput("解读",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"geo-synthesis-v1");
        var tree=json.valueToTree(old);assertThat(tree.size()).isEqualTo(9);assertThat(tree.has("competitorCandidates")).isFalse();assertThat(tree.has("competitorDiscoveryVersion")).isFalse();
        assertThat(json.treeToValue(tree,GeoTaskDtos.SynthesisInput.class).competitorCandidates()).isNull();
    }
    @Test void discoveredNameLedQuestionIsValidationAndIgnoreDoesNotShiftOtherDenominators() {
        ((ObjectNode)snapshot.path("authorizedContext").path("questions").get(0)).put("text","松野杯怎么样？");useSnapshot();
        var row=answer(301,"松野杯怎么样？","松野杯轻便易清洗。");when(reports.findObservationPage(501,0,210)).thenReturn(List.of(row));
        service.ingest(report,run,List.of(row),synthesis(List.of(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","POSITIVE_ATTRIBUTE"))));
        assertThat(service.get(account,701,"DISCOVERY").platforms().getFirst().expectedUnits()).isEqualTo(1);
        assertThat(service.get(account,701,"BRAND_VALIDATION").platforms().getFirst().validUnits()).isEqualTo(1);
        var c=candidates.values().iterator().next();candidates.put(c.nameNormalized(),new GeoCompetitiveRecords.Candidate(c.id(),41,101,201,c.name(),c.nameNormalized(),c.entityKind(),"IGNORED",2,null,701,701,c.evidenceJson(),NOW,NOW));
        var ignored=service.get(account,701,"DISCOVERY");assertThat(ignored.candidates()).isEmpty();assertThat(ignored.platforms().getFirst().entities()).hasSize(1);
        assertThat(ignored.platforms().getFirst().expectedUnits()).isEqualTo(1);
    }
    @Test void frozenCompetitorAliasClassifiesQuestionAndAvoidsDuplicateComparisonRow() {
        var configured=((ObjectNode)snapshot.path("authorizedContext")).withArray("competitors").addObject().put("id","999").put("name","松野旗舰杯");configured.putArray("aliases").add("松野杯");
        ((ObjectNode)snapshot.path("authorizedContext").path("questions").get(0)).put("text","松野杯怎么样？");useSnapshot();
        var row=answer(301,"松野杯怎么样？","松野杯轻便易清洗。");when(reports.findObservationPage(501,0,210)).thenReturn(List.of(row));
        service.ingest(report,run,List.of(row),synthesis(List.of(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","MENTION"))));
        var result=service.get(account,701,"BRAND_VALIDATION");assertThat(result.platforms().getFirst().entities()).hasSize(2);
        assertThat(result.platforms().getFirst().entities().getLast().role()).isEqualTo("CONFIGURED");assertThat(result.candidates()).hasSize(1);
    }
    @Test void explicitShopMediaDomainNamesAreRejectedButConcreteProductsArePreserved() {
        for(String name:List.of("松野官方旗舰店","A咖啡旗舰店","人民日报","某某新闻网站","松野商城","松野官网","WWW.ACME.COM","Acme.com"))
            assertThat(GeoCompetitiveService.acceptableName(name,List.of())).as(name).isFalse();
        for(String name:List.of("京东京造","商城牌保温杯","媒体杯","Acme 2.0","猫头鹰咖啡店"))
            assertThat(GeoCompetitiveService.acceptableName(name,List.of())).as(name).isTrue();
    }
    @Test void genericCategoryAliasCannotTurnNaturalQuestionsIntoBrandValidation() {
        ((ObjectNode)snapshot.path("catalogSnapshot").path("product")).put("category","便携保温杯").withArray("aliases").add("便携保温杯");useSnapshot();
        assertThat(service.get(account,701,"DISCOVERY").platforms().getFirst().expectedUnits()).isEqualTo(2);
    }
    @Test void savedThermosAndTigerBrandPairsCountThreeOfFourWithoutReanalysisOrSnapshotWrites()throws Exception {
        ((ObjectNode)snapshot.path("authorizedContext").path("questions").get(1)).put("text","适合通勤的杯子？");
        ((ObjectNode)snapshot.path("authorizedContext")).withArray("questions").addObject().put("id","304").put("text","出行用什么杯子？");useSnapshot();
        String quote="膳魔师（THERMOS）与虎牌（TIGER）都有保温杯。";
        seedLegacyBrand(811,"膳魔师（THERMOS）","PENDING",quote);
        seedLegacyBrand(812,"虎牌（TIGER）","PENDING",quote);
        String frozen=state.get().resultJson();
        for(String third:List.of("膳魔师和虎牌也有便携款。","THERMOS and TIGER have travel cups.")) {
            when(reports.findObservationPage(501,0,210)).thenReturn(List.of(answer(301,"有什么保温杯？",quote),
                    answer(302,"适合通勤的杯子？","膳魔师与虎牌都有提及。"),answer(303,"便携保温杯推荐？",third),
                    answer(304,"出行用什么杯子？","THERMOSTAT and TIGERLILY are not these brands.")));
            var result=service.get(account,701,"DISCOVERY");assertThat(result.methodVersion()).isEqualTo("geo-competitive-insight-v2");
            assertThat(result.candidates()).extracting(GeoCompetitiveDtos.Candidate::name).containsExactly("膳魔师","虎牌");
            assertThat(result.candidates()).extracting(GeoCompetitiveDtos.Candidate::id).containsExactly("811","812");
            var platform=result.platforms().getFirst();assertThat(platform.validUnits()).isEqualTo(4);
            assertThat(platform.entities().stream().filter(e->"DISCOVERED".equals(e.role())))
                    .allSatisfy(e->{assertThat(e.mentionedUnits()).isEqualTo(3);assertThat(e.mentionRate()).isEqualTo(75.0);});
        }
        assertThat(state.get().resultJson()).isEqualTo(frozen);
        verify(work,never()).upsertCandidate(any());verify(work,never()).enqueue(anyLong(),anyLong(),anyLong(),anyString(),any(),any());
        verifyNoInteractions(catalog,geo,builder);
    }
    @Test void bilingualExpansionRequiresBrandExactExplicitPairAndRejectsUnsafeComponents() {
        for(String name:List.of("膳魔师（THERMOS）","虎牌(TIGER)")) {
            var value=GeoCompetitiveService.names(name,"BRAND",proof(name),List.of());
            assertThat(value.terms()).hasSize(2);assertThat(value.name()).doesNotContain("(","（");
            assertThat(GeoCompetitiveService.names(name,"PRODUCT",proof(name),List.of()).terms()).containsExactly(name);
            assertThat(GeoCompetitiveService.names(name,"UNKNOWN",proof(name),List.of()).terms()).containsExactly(name);
            assertThat(GeoCompetitiveService.names(name,"BRAND",proof("只有中文名，未证明英文对应"),List.of()).terms()).containsExactly(name);
        }
        for(String name:List.of("虎牌（500ML）","虎牌（SAHARA 500）","虎牌（轻量系列）","虎牌（PRO）","松野(PREMIUM)",
                "膳魔师(STAINLESS)","松野(USA)","松野(Travel Cup)","松野（ALPHA）附加说明"))
            assertThat(GeoCompetitiveService.names(name,"BRAND",proof(name),List.of()).terms()).as(name).containsExactly(name);
        for(String name:List.of("保温杯（THERMOS）","京东（JD）","某牌（Amazon）","禾序（HEXU）"))
            assertThat(GeoCompetitiveService.names(name,"BRAND",proof(name),List.of("禾序","Hexu"))).as(name).isNull();
        assertThat(GeoCompetitiveService.names("虎牌(TIGER)","BRAND",proof("虎牌(TIGER)"),List.of("Tiger"))).isNull();
        assertThat(GeoCompetitiveService.names("膳魔师","BRAND",proof("推荐膳魔师（THERMOS）。"),List.of()).terms()).containsExactly("膳魔师");
        assertThat(GeoCompetitiveService.names("虎牌(TIGER)","BRAND",proof("虎牌(TIGER)和虎牌(TIGRE)不能确认为同一英文名。"),List.of()).terms()).containsExactly("虎牌(TIGER)");
    }
    @Test void newBilingualIdentityUsesCanonicalKeyButKeepsOriginalProofAndEnglishBoundary()throws Exception {
        String quote="膳魔师（THERMOS）有保温杯。";var row=answer(301,"有什么保温杯？",quote);
        service.ingest(report,run,List.of(row),synthesis(List.of(candidate("膳魔师（THERMOS）",row.unitKey(),quote,"MENTION").put("entityKind","BRAND"))));
        assertThat(candidates).containsOnlyKeys("膳魔师");var stored=candidates.get("膳魔师");
        assertThat(stored.name()).isEqualTo("膳魔师（THERMOS）");assertThat(stored.evidenceJson()).contains("THERMOS");
        assertThat(json.readTree(state.get().resultJson()).path("entities").get(0).path("name").asText()).isEqualTo("膳魔师（THERMOS）");
        var projected=GeoCompetitiveService.names(stored.name(),"BRAND",proof(quote),List.of());
        assertThat(projected.terms()).containsExactly("膳魔师","THERMOS");
        assertThat(GeoVisibilityProjection.containsName("thermos is present",projected.terms())).isTrue();
        assertThat(GeoVisibilityProjection.containsName("THERMOSTAT XTHERMOS THERMOS_2",projected.terms())).isFalse();
    }
    @Test void laterChineseOnlyDiscoveryReusesOldBilingualIdAndIgnoredTombstone()throws Exception {
        seedLegacyBrand(811,"虎牌（TIGER）","IGNORED","虎牌（TIGER）有保温杯。");state.set(null);
        var row=answer(301,"有什么保温杯？","虎牌有便携杯。");
        service.ingest(report,run,List.of(row),synthesis(List.of(candidate("虎牌",row.unitKey(),row.answerText(),"MENTION").put("entityKind","BRAND"))));
        assertThat(candidates).hasSize(1);var value=candidates.values().iterator().next();
        assertThat(value.id()).isEqualTo(811);assertThat(value.status()).isEqualTo("IGNORED");assertThat(value.evidenceJson()).contains("TIGER");
        assertThat(json.readTree(state.get().resultJson()).path("entities").get(0).path("candidateId").asText()).isEqualTo("811");
        assertThat(service.get(account,701,"DISCOVERY").candidates()).isEmpty();
    }
    @Test void ambiguousOldBilingualRowsAreNotMergedOrRevived()throws Exception {
        seedLegacyBrand(811,"虎牌（TIGER）","IGNORED","虎牌（TIGER）有保温杯。");
        seedLegacyBrand(812,"虎牌（TIGRE）","CONFIRMED","虎牌（TIGRE）有保温杯。");state.set(null);
        var row=answer(301,"有什么保温杯？","虎牌有便携杯。");
        service.ingest(report,run,List.of(row),synthesis(List.of(candidate("虎牌",row.unitKey(),row.answerText(),"MENTION").put("entityKind","BRAND"))));
        assertThat(candidates).hasSize(2);verify(work,never()).upsertCandidate(any());
        assertThat(service.get(account,701,"DISCOVERY").candidates()).isEmpty();
    }
    @Test void differingEnglishPairsDoNotMergeEvenWithSameChineseName()throws Exception {
        seedLegacyBrand(811,"虎牌（TIGER）","IGNORED","虎牌（TIGER）有保温杯。");state.set(null);
        var row=answer(301,"有什么保温杯？","虎牌（TIGRE）有便携杯。");
        service.ingest(report,run,List.of(row),synthesis(List.of(candidate("虎牌（TIGRE）",row.unitKey(),row.answerText(),"MENTION").put("entityKind","BRAND"))));
        assertThat(candidates).hasSize(1);verify(work,never()).upsertCandidate(any());
    }
    @Test void concurrentlyConflictingCanonicalInsertIsDiscardedWithoutAttachingOrOverwritingOtherIdentity() {
        doReturn(0).when(work).upsertCandidate(any());
        var row=answer(301,"有什么保温杯？","虎牌（TIGER）有便携杯。");
        service.ingest(report,run,List.of(row),synthesis(List.of(candidate("虎牌（TIGER）",row.unitKey(),row.answerText(),"MENTION").put("entityKind","BRAND"))));
        assertThat(candidates).isEmpty();assertThat(state.get().status()).isEqualTo("SUCCEEDED");
        assertThat(service.get(account,701,"DISCOVERY").candidates()).isEmpty();
        verify(work,never()).findName(anyLong(),anyLong(),anyString());
    }
    @Test void canonicalConfirmationCreatesChineseNameWithoutAliasesOrTopicChanges()throws Exception {
        seedLegacyBrand(811,"膳魔师（THERMOS）","PENDING","膳魔师（THERMOS）有保温杯。");
        when(geo.get(account,201)).thenReturn(topic(2,List.of()));
        when(catalog.create(eq(account),eq(101L),any())).thenReturn(new CompetitorDtos.Response("999","101","膳魔师",List.of(),CompetitorType.DIRECT,null,CompetitorStatus.ACTIVE,1,NOW,NOW));
        when(work.decide(811,41,1,"CONFIRMED",999L)).thenReturn(1);
        service.confirm(account,811,new GeoCompetitiveDtos.ConfirmRequest(1,2,null,CompetitorType.DIRECT,false));
        verify(catalog).create(account,101,new CompetitorDtos.CreateRequest("膳魔师",CompetitorType.DIRECT,null,CompetitorStatus.ACTIVE));
        verify(catalog,never()).replaceAliases(any(),anyLong(),any());verify(geo,never()).replaceCompetitors(any(),anyLong(),any());
    }
    @Test void canonicalOrEnglishExistingCatalogRequiresExplicitLinkNotDuplicateCreation()throws Exception {
        seedLegacyBrand(811,"膳魔师（THERMOS）","PENDING","膳魔师（THERMOS）有保温杯。");
        when(geo.get(account,201)).thenReturn(topic(2,List.of()));
        when(work.existingCompetitor(41,101,"thermos")).thenReturn(Optional.of(999L));
        assertThatThrownBy(()->service.confirm(account,811,new GeoCompetitiveDtos.ConfirmRequest(1,2,null,CompetitorType.DIRECT,false))).isInstanceOf(BusinessException.class);
        verify(catalog,never()).create(any(),anyLong(),any());
        when(work.existingCompetitors(eq(41L),eq(101L),anyList())).thenReturn(List.of(new GeoCompetitiveRecords.CatalogMatch("thermos",999L)));
        assertThat(service.get(account,701,"DISCOVERY").candidates().getFirst().existingCompetitorId()).isEqualTo("999");
    }
    @Test void bilingualChineseAndEnglishNameLedQuestionsStayOutsideDiscovery()throws Exception {
        ((ObjectNode)snapshot.path("authorizedContext").path("questions").get(0)).put("text","虎牌怎么样？");
        ((ObjectNode)snapshot.path("authorizedContext").path("questions").get(2)).put("text","TIGER怎么样？");useSnapshot();
        seedLegacyBrand(811,"虎牌（TIGER）","PENDING","虎牌（TIGER）有保温杯。");
        assertThat(service.get(account,701,"DISCOVERY").platforms().getFirst().expectedUnits()).isZero();
        assertThat(service.get(account,701,"BRAND_VALIDATION").platforms().getFirst().expectedUnits()).isEqualTo(3);
    }
    @Test void oneMatchingFrozenConfiguredIdentityUsesTheSameEvidencedPairWithoutDuplicateRow()throws Exception {
        ((ObjectNode)snapshot.path("authorizedContext")).withArray("competitors").addObject().put("id","999").put("name","THERMOS").putArray("aliases");useSnapshot();
        seedLegacyBrand(811,"膳魔师（THERMOS）","PENDING","膳魔师（THERMOS）有保温杯。");
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of(answer(301,"有什么保温杯？","膳魔师（THERMOS）有保温杯。"),
                answer(303,"便携保温杯推荐？","膳魔师有便携款。")));
        var result=service.get(account,701,"DISCOVERY");var entities=result.platforms().getFirst().entities();
        assertThat(entities).hasSize(2);var configured=entities.getLast();assertThat(configured.role()).isEqualTo("CONFIGURED");
        assertThat(configured.entityKind()).isEqualTo("UNKNOWN");assertThat(configured.mentionedUnits()).isEqualTo(2);assertThat(configured.mentionRate()).isEqualTo(100.0);
        assertThat(result.candidates()).hasSize(1);assertThat(snapshot.path("authorizedContext").path("competitors").get(0).path("aliases")).isEmpty();
        verifyNoInteractions(catalog);
    }
    @Test void conflictingEnglishPairsInOldSnapshotRemainFullNamesAndNeverExpandConfiguredIdentity()throws Exception {
        ((ObjectNode)snapshot.path("authorizedContext")).withArray("competitors").addObject().put("id","999").put("name","虎牌").putArray("aliases");useSnapshot();
        seedLegacyBrand(811,"虎牌(TIGER)","PENDING","虎牌(TIGER)有保温杯。");
        seedLegacyBrand(812,"虎牌(TIGRE)","PENDING","虎牌(TIGRE)有保温杯。");
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of(answer(301,"有什么保温杯？","Only TIGER and TIGRE are mentioned.")));
        var result=service.get(account,701,"DISCOVERY");
        assertThat(result.candidates()).extracting(GeoCompetitiveDtos.Candidate::name).containsExactly("虎牌(TIGER)","虎牌(TIGRE)");
        assertThat(result.platforms().getFirst().entities()).allSatisfy(e->assertThat(e.mentionedUnits()).isZero());
    }
    private List<GeoCompetitiveDtos.Evidence> proof(String quote){return List.of(new GeoCompetitiveDtos.Evidence("701","301:DOUBAO:1",quote,"MENTION"));}
    private void seedLegacyBrand(long id,String name,String status,String quote)throws Exception {
        var proof=proof(quote);String normalized=GeoVisibilityProjection.normalize(name);
        candidates.put(normalized,new GeoCompetitiveRecords.Candidate(id,41,101,201,name,normalized,"BRAND",status,1,null,701,701,json.writeValueAsString(proof),NOW,NOW));
        ObjectNode result=state.get()==null?json.createObjectNode():(ObjectNode)json.readTree(state.get().resultJson());
        result.withArray("entities").add(json.valueToTree(new GeoCompetitiveService.Identity(Long.toString(id),null,name,"BRAND","DISCOVERED",List.of(name),proof)));
        state.set(new GeoCompetitiveRecords.Insight(899,701,41,"a".repeat(64),"SUCCEEDED",1,null,null,null,result.toString(),null,null,NOW,NOW,NOW));
    }
    private void useSnapshot(){run=new GeoTaskRecords.Run(501,401,41,101,102L,201,"PARTIAL","STANDARD",3,1,1,1,snapshot.toString(),"geo-observation-v1","geo-synthesis-v1",NOW,NOW,NOW,NOW,6,2,4);when(reports.findRun(501,41)).thenReturn(Optional.of(run));}
    private GeoDtos.TopicResponse topic(int version,List<String> ids){return new GeoDtos.TopicResponse("201","101","102","主题",null,List.of(),List.of(),ids,version,version,NOW,NOW);}
    private GeoCompetitiveRecords.Insight failed(String reason){return new GeoCompetitiveRecords.Insight(801,701,41,"a".repeat(64),"FAILED",2,null,null,null,null,reason,null,NOW,NOW,NOW);}
    private void ingest(JsonNode... nodes){service.ingest(report,run,List.of(answer(301,"有什么保温杯？","松野杯轻便易清洗。")),synthesis(List.of(nodes)));}
    private GeoTaskDtos.SynthesisInput synthesis(List<JsonNode> nodes){return new GeoTaskDtos.SynthesisInput("解读",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"geo-synthesis-v1",nodes,GeoCompetitiveService.DISCOVERY);}
    private ObjectNode candidate(String name,String unit,String quote,String signal){var node=json.createObjectNode().put("name",name).put("entityKind","PRODUCT");node.putArray("evidence").addObject().put("unitKey",unit).put("quote",quote).put("signal",signal);return node;}
    private ObjectNode payload(GeoCompetitiveRecords.Claim c){var out=json.createObjectNode().put("jobId",Long.toString(c.work().id())).put("attempt",c.work().attempts()).put("inputHash",c.work().inputHash());out.set("synthesis",json.valueToTree(synthesis(List.of(candidate("松野杯","301:DOUBAO:1","松野杯轻便易清洗。","POSITIVE_ATTRIBUTE")))));out.set("synthesisUsage",json.valueToTree(new GeoTaskDtos.SynthesisUsageInput("DEEPSEEK","deepseek-flash",20,20,1,50)));return out;}
    private GeoTaskRecords.Observation answer(long question,String text,String answer){return new GeoTaskRecords.Observation(question+600,501,401,41,UUID.randomUUID(),"a".repeat(64),question+":DOUBAO:1",1,question,text,"DOUBAO",1,"SUCCEEDED",answer,null,"DOUBAO","doubao",NOW,NOW,10,"{}",1,NOW,null);}
}
