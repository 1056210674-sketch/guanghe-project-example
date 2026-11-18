package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskControlMapper;
import jakarta.validation.Validation;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeoReportInterpretationServiceTests {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-28T08:00:00Z");
    private final GeoTaskMapper reports = mock(GeoTaskMapper.class);
    private final GeoInterpretationMapper work = mock(GeoInterpretationMapper.class);
    private final TaskControlMapper tasks = mock(TaskControlMapper.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final PaidTaskProperties properties = new PaidTaskProperties();
    private final AtomicReference<GeoInterpretationRecords.Work> saved = new AtomicReference<>();
    private final AtomicReference<GeoTaskRecords.Report> report = new AtomicReference<>();
    private final AtomicReference<GeoTaskRecords.Run> run = new AtomicReference<>();
    private final AuthenticatedAccount account = new AuthenticatedAccount(41,"user",AccountRole.USER,1,
            AccountStatus.ACTIVE,null,null,null);
    private GeoReportInterpretationService service;

    @BeforeEach
    void setup() throws Exception {
        properties.setGeoInterpretationEnabled(true);
        service = new GeoReportInterpretationService(reports,work,tasks,ids,json,properties,true,
                Validation.buildDefaultValidatorFactory().getValidator(),ObservabilitySupport.noop(),
                Clock.fixed(NOW.toInstant(),ZoneOffset.UTC));
        report.set(report("geo-deterministic-v1",null));
        run.set(run(snapshot()));
        when(reports.lockOwner(41)).thenReturn(Optional.of(41L));
        when(reports.lockReport(701,41)).thenReturn(Optional.of(701L));
        when(reports.findReport(701,41)).thenAnswer(i -> Optional.of(report.get()));
        when(reports.findRun(501,41)).thenAnswer(i -> Optional.of(run.get()));
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of(answer("禾序轻行杯清晰出现。")));
        when(reports.findCitations(anyLong())).thenReturn(List.of());
        when(work.executionEligible(41,401)).thenReturn(true);
        when(work.find(701,41)).thenAnswer(i -> Optional.ofNullable(saved.get()));
        when(work.lock(701,41)).thenAnswer(i -> Optional.ofNullable(saved.get()));
        when(work.lockNext(any())).thenAnswer(i -> Optional.ofNullable(saved.get()));
        AtomicLong sequence = new AtomicLong(801);
        when(ids.nextId()).thenAnswer(i -> sequence.getAndIncrement());
        when(work.enqueue(anyLong(),eq(701L),eq(41L),anyString(),any(),eq(NOW))).thenAnswer(i -> {
            saved.set(new GeoInterpretationRecords.Work(i.getArgument(0),701,41,i.getArgument(3),"QUEUED",1,
                    null,null,null,NOW,null,null,null,null,NOW,NOW.plusSeconds(120))); return 1;
        });
        when(work.claim(anyLong(),any(),any(),any(),eq(NOW))).thenAnswer(i -> {
            var old=saved.get(); saved.set(new GeoInterpretationRecords.Work(old.id(),701,41,old.inputHash(),
                    "RUNNING",old.attempts(),i.getArgument(1),i.getArgument(2),i.getArgument(3),NOW,NOW,null,null,null,NOW,NOW.plusSeconds(120)));
            return 1;
        });
        when(work.finish(anyLong(),anyInt(),any(),anyString(),any(),any(),any())).thenReturn(1);
        when(work.publish(eq(701L),eq(41L),anyString(),anyString(),anyString())).thenReturn(1);
        when(tasks.insertTaskEvent(anyLong(),eq(401L),eq(41L),isNull(),any(),isNull(),isNull(),isNull(),
                anyString(),anyString(),any(),isNull(),anyString(),any())).thenReturn(1);
    }

    @Test void getOnlyProjectsAndNeverEnqueuesOrTouchesAccounting() {
        var state=service.status(report.get(),run.get(),List.of(answer("answer")));
        assertThat(state.status()).isEqualTo("NOT_STARTED"); assertThat(state.canRetry()).isTrue();
        verify(work,never()).enqueue(anyLong(),anyLong(),anyLong(),anyString(),any(),any());
        verify(work,never()).lockNext(any()); verifyNoInteractions(tasks);
    }

    @Test void explicitRequestReservesAttemptAndRepeatedRequestReturnsSameActiveWork() {
        assertThat(service.request(account,701).status()).isEqualTo("QUEUED");
        assertThat(service.request(account,701).attempts()).isEqualTo(1);
        verify(work,times(1)).enqueue(anyLong(),anyLong(),anyLong(),anyString(),any(),any());
        verify(tasks,times(1)).insertTaskEvent(anyLong(),eq(401L),eq(41L),isNull(),any(),isNull(),isNull(),isNull(),
                eq("GEO_INTERPRETATION_REQUESTED"),eq("USER"),isNull(),isNull(),anyString(),eq(NOW));
    }

    @Test void alreadySuccessfulReportNeverSchedulesAnotherModelEvenWhenFlagOff() {
        report.set(report("geo-synthesis-v1",null)); properties.setGeoInterpretationEnabled(false);
        assertThat(service.request(account,701).status()).isEqualTo("SUCCEEDED");
        verify(work,never()).enqueue(anyLong(),anyLong(),anyLong(),anyString(),any(),any());
        verifyNoInteractions(tasks);
    }

    @Test void noAnswerArchiveAndCrossOwnerFailBeforeQueueMutation() {
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of());
        assertThatThrownBy(() -> service.request(account,701)).isInstanceOf(BusinessException.class);
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of(answer("answer")));
        report.set(report("geo-deterministic-v1",NOW));
        assertThatThrownBy(() -> service.request(account,701)).isInstanceOf(BusinessException.class);
        when(reports.lockReport(701,41)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.request(account,701)).isInstanceOf(BusinessException.class);
        verify(work,never()).enqueue(anyLong(),anyLong(),anyLong(),anyString(),any(),any());
    }

    @Test void attemptCapAndAnotherActiveReportCannotBeBypassed() {
        saved.set(state("FAILED",2,null));
        assertThat(service.status(report.get(),run.get(),List.of(answer("answer"))).unavailableReason())
                .isEqualTo("ATTEMPT_LIMIT_REACHED");
        assertThatThrownBy(() -> service.request(account,701)).isInstanceOf(BusinessException.class);
        saved.set(null); when(work.countActive(41)).thenReturn(1);
        assertThatThrownBy(() -> service.request(account,701)).isInstanceOf(BusinessException.class);
        verify(work,never()).enqueue(anyLong(),anyLong(),anyLong(),anyString(),any(),any());
    }

    @Test void providerFlagOffCannotEnqueueOrClaim() {
        properties.setGeoInterpretationEnabled(false);
        assertThat(service.status(report.get(),run.get(),List.of(answer("answer"))).unavailableReason())
                .isEqualTo("SERVICE_UNAVAILABLE");
        assertThatThrownBy(() -> service.request(account,701)).isInstanceOf(BusinessException.class);
        assertThat(service.claimNext()).isEmpty(); verify(work,never()).lockNext(any());
    }

    @Test void claimUsesOnlyFrozenEvidenceAndFullAnswerMentionBeforeBoundedProjection() {
        String original = "解释".repeat(4000)+"禾序轻行杯";
        when(reports.findObservationPage(501,0,210)).thenReturn(List.of(answer(original)));
        var citation=new GeoTaskRecords.InterpretationCitation(901,"title","https://example.com/path?q=exact",
                "example.com","摘要".repeat(200),NOW,4);
        when(reports.findInterpretationCitations(501,41)).thenReturn(List.of(citation,citation,citation));
        service.request(account,701);
        var claim=service.claimNext().orElseThrow();
        assertThat(claim.request().path("observations").get(0).path("answerText").asText()).hasSize(6000).doesNotContain("禾序轻行杯");
        assertThat(claim.request().path("visibilityEvidence").path("units").get(0).path("targetMentioned").asBoolean()).isTrue();
        assertThat(claim.request().path("authorizedContext").path("monitoringTarget").path("name").asText()).isEqualTo("禾序轻行杯");
        assertThat(claim.request().path("observations").get(0).path("citations")).hasSize(3);
        assertThat(claim.request().path("evidenceProjection").path("truncatedAnswerCount").asInt()).isEqualTo(1);
        assertThat(claim.request().path("evidenceProjection").path("omittedCitationCount").asInt()).isEqualTo(1);
        assertThat(claim.work().deadlineAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(claim.work().leaseExpiresAt()).isEqualTo(NOW.plusSeconds(90));
        assertThat(claim.request().path("maxProviderCalls").asInt()).isEqualTo(2);
        assertThat(original).hasSize(8005);
        verify(reports,never()).findObservationByEvent(anyLong(),any());
    }

    @Test void stoppedAccountOrChangedTaskPreventsQueuedCall() {
        service.request(account,701); when(work.executionEligible(41,401)).thenReturn(false);
        assertThat(service.claimNext()).isEmpty();
        verify(work).rejectOrExpire(anyLong(),eq("GEO_INTERPRETATION_REPORT_UNAVAILABLE"),eq(NOW));
        verify(work,never()).claim(anyLong(),any(),any(),any(),any());
    }

    @Test void changedFrozenEvidenceFailsInsteadOfSilentlyCallingDifferentInput() throws Exception {
        service.request(account,701);
        var changed=snapshot(); changed.path("authorizedContext").path("topic");
        ((ObjectNode) changed.path("authorizedContext").path("topic")).put("version",2);
        run.set(run(changed));
        assertThat(service.claimNext()).isEmpty();
        verify(work).rejectOrExpire(anyLong(),eq("GEO_INTERPRETATION_INPUT_CHANGED"),eq(NOW));
        verify(work,never()).claim(anyLong(),any(),any(),any(),any());
    }

    @Test void expiredRunningWorkFailsWithoutAutomaticModelRetry() {
        var expired=new GeoInterpretationRecords.Work(801,701,41,"a".repeat(64),"RUNNING",1,
                UUID.randomUUID(),NOW.minusSeconds(1),NOW.minusSeconds(31),NOW.minusMinutes(2),NOW.minusMinutes(2),null,null,null,NOW.minusMinutes(2),NOW);
        when(work.lockExpired(NOW)).thenReturn(Optional.of(expired));
        when(work.rejectOrExpire(801,"GEO_INTERPRETATION_LEASE_EXPIRED",NOW)).thenReturn(1);
        service.expireOne();
        verify(work,never()).requeue(anyLong(),any(),any());
        verify(work,never()).publish(anyLong(),anyLong(),anyString(),anyString(),anyString());
    }

    @Test void successfulResponseOnlyPublishesInterpretationAndSafeTaskEvent() {
        var competitive=mock(GeoCompetitiveService.class);
        service.setCompetitive(new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("competitive",competitive)).getBeanProvider(GeoCompetitiveService.class));
        service.request(account,701); var claim=service.claimNext().orElseThrow();
        assertThat(service.completed(claim,response(claim))).isNull();
        verify(work).publish(eq(701L),eq(41L),contains("geo-synthesis-v1"),contains("DEEPSEEK"),eq("基于现有回答的解读"));
        verify(work).finish(eq(claim.work().id()),eq(1),eq(claim.work().claimToken()),eq("SUCCEEDED"),isNull(),anyString(),eq(NOW));
        verify(competitive).ingest(eq(report.get()),eq(run.get()),anyList(),any());
        assertThat(mockingDetails(tasks).getInvocations()).allMatch(i -> i.getMethod().getName().equals("insertTaskEvent"));
        assertThat(mockingDetails(reports).getInvocations()).allMatch(i -> i.getMethod().getName().startsWith("find") || i.getMethod().getName().startsWith("lock"));
    }

    @Test void malformedOrDeterministicResponsesFailAndNeverOverwriteOriginal() {
        service.request(account,701); var claim=service.claimNext().orElseThrow();
        var payload=response(claim); payload.set("synthesis",json.nullNode());
        assertThat(service.completed(claim,payload)).isEqualTo("GEO_INTERPRETATION_INVALID_RESULT");
        payload=response(claim); ((ObjectNode)payload.path("synthesis")).put("methodVersion","geo-deterministic-v1");
        assertThat(service.completed(claim,payload)).isEqualTo("GEO_INTERPRETATION_INVALID_RESULT");
        payload=response(claim); ((ObjectNode)payload.path("synthesisUsage")).put("providerCallCount",3);
        assertThat(service.completed(claim,payload)).isEqualTo("GEO_INTERPRETATION_INVALID_RESULT");
        payload=response(claim); payload.put("inputHash","b".repeat(64));
        assertThat(service.completed(claim,payload)).isEqualTo("GEO_INTERPRETATION_INVALID_RESULT");
        verify(work,never()).publish(anyLong(),anyLong(),anyString(),anyString(),anyString());
    }

    @Test void staleClaimCannotPublishEvenIfProviderEventuallyReturnsSuccess() {
        service.request(account,701); var claim=service.claimNext().orElseThrow();
        saved.set(new GeoInterpretationRecords.Work(801,701,41,claim.work().inputHash(),"RUNNING",2,
                UUID.randomUUID(),NOW.plusSeconds(90),NOW.plusSeconds(60),NOW,NOW,null,null,null,NOW,NOW.plusSeconds(120)));
        assertThat(service.completed(claim,response(claim))).isEqualTo("GEO_INTERPRETATION_LEASE_EXPIRED");
        verify(work,never()).publish(anyLong(),anyLong(),anyString(),anyString(),anyString());
        verify(work,never()).finish(anyLong(),anyInt(),any(),anyString(),any(),any(),any());
    }

    @Test void failureKeepsOriginalAndDoesNotPropagatePrivateProviderMessage() {
        service.request(account,701); var claim=service.claimNext().orElseThrow();
        service.failed(claim,"upstream secret body");
        verify(work).finish(anyLong(),eq(1),any(),eq("FAILED"),eq("GEO_INTERPRETATION_PROVIDER_UNAVAILABLE"),isNull(),eq(NOW));
        verify(work,never()).publish(anyLong(),anyLong(),anyString(),anyString(),anyString());
    }

    private ObjectNode snapshot() throws Exception {
        return (ObjectNode)json.readTree("""
                {"authorizedContext":{"topic":{"id":"201","spaceId":"101","productId":"102","name":"监测专题","region":"CN","version":1},
                 "questions":[{"id":"301","category":"BRAND_DISCOVERY","text":"问题一？"},{"id":"302","category":"BRAND_DISCOVERY","text":"问题二？"},{"id":"303","category":"BRAND_DISCOVERY","text":"问题三？"}],
                 "competitorSetVersion":null,"competitors":[]},"parameters":{"platformCodes":["DOUBAO"],"sampleMode":"STANDARD"},
                 "catalogSnapshot":{"space":{"id":"101","brand":{"name":"禾序","aliases":[]}},"product":{"id":"102","spaceId":"101","name":"禾序轻行杯","aliases":[],"category":"饮具"}}}
                """);
    }
    private GeoTaskRecords.Run run(ObjectNode snapshot) { return new GeoTaskRecords.Run(501,401,41,101,102L,201,
            "PARTIAL","STANDARD",3,1,1,1,snapshot.toString(),"geo-observation-v1","geo-deterministic-v1",NOW,NOW,NOW,NOW,6,2,4); }
    private GeoTaskRecords.Report report(String method,OffsetDateTime archived) { return new GeoTaskRecords.Report(701,501,401,41,201,
            "PARTIAL","{}","{}","原报告",method,3,NOW,NOW,2,4,archived); }
    private GeoTaskRecords.Observation answer(String answer) { return new GeoTaskRecords.Observation(901,501,401,41,UUID.randomUUID(),"a".repeat(64),
            "301:DOUBAO:1",1,301,"问题一？","DOUBAO",1,"SUCCEEDED",answer,null,"DOUBAO","doubao",NOW,NOW,10,
            "{\"inputTokens\":1,\"outputTokens\":1,\"searchRequests\":1,\"monetaryCost\":null,\"currency\":null}",1,NOW); }
    private GeoInterpretationRecords.Work state(String status,int attempts,String error) { return new GeoInterpretationRecords.Work(801,701,41,"a".repeat(64),status,attempts,
            null,null,null,NOW,NOW,NOW,error,null,NOW,NOW.plusSeconds(120)); }

    @Test void capacityOnlyFailureIsNotAnExhaustedModelAttempt() {
        saved.set(state("FAILED",2,"GEO_INTERPRETATION_CAPACITY_FULL"));
        var state=service.status(report.get(),run.get(),List.of(answer("answer")));
        assertThat(state.canRetry()).isTrue(); assertThat(state.unavailableReason()).isNull();
        saved.set(new GeoInterpretationRecords.Work(801,701,41,"a".repeat(64),"FAILED",2,null,null,null,
                NOW,NOW,NOW,"GEO_INTERPRETATION_CAPACITY_FULL",null,NOW.plusSeconds(5),NOW.plusSeconds(120)));
        state=service.status(report.get(),run.get(),List.of(answer("answer")));
        assertThat(state.canRetry()).isFalse(); assertThat(state.retryAfter()).isEqualTo(NOW.plusSeconds(5));
        assertThatThrownBy(() -> service.request(account,701)).isInstanceOf(BusinessException.class);
        verify(work,never()).requeue(anyLong(),any(),any());
    }
    private ObjectNode response(GeoInterpretationRecords.Claim claim) {
        ObjectNode payload=json.createObjectNode().put("jobId",Long.toString(claim.work().id())).put("attempt",claim.work().attempts()).put("inputHash",claim.work().inputHash());
        payload.set("synthesis",json.valueToTree(new GeoTaskDtos.SynthesisInput("基于现有回答的解读",List.of(),List.of(),List.of(),List.of("事实"),
                List.of(new GeoTaskDtos.RecommendationInput("P1","建议","依据")),List.of(),List.of(),"geo-synthesis-v1")));
        payload.set("synthesisUsage",json.valueToTree(new GeoTaskDtos.SynthesisUsageInput("DEEPSEEK","deepseek-flash",100,80,1,500)));
        return payload;
    }
}
