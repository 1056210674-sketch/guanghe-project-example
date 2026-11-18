package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.integration.internal.client.PythonCallResult;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeoReportInterpretationWorkerTests {
    @Test void modelWaitDoesNotBlockSchedulerOrQueueMoreCallsAndHasNoTransaction() throws Exception {
        var service=mock(GeoReportInterpretationService.class);
        var python=mock(PythonInternalClient.class);
        var claim=claim();
        when(service.claimNext()).thenReturn(Optional.of(claim));
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), completed=new CountDownLatch(1);
        AtomicBoolean inTransaction=new AtomicBoolean(true);
        when(python.synthesizeGeo(any())).thenAnswer(i -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();
            if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return PythonCallResult.available(200,new ObjectMapper().createObjectNode());
        });
        when(service.completed(any(),any())).thenAnswer(i -> { completed.countDown(); return null; });
        var worker=new GeoReportInterpretationWorker(service,provider(python),ObservabilitySupport.noop());
        try {
            assertTimeout(Duration.ofSeconds(1),worker::processOne);
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<10;i++) worker.processOne();
            verify(service,times(1)).claimNext(); verify(python,times(1)).synthesizeGeo(any());
            assertThat(inTransaction.get()).isFalse();
            release.countDown(); assertThat(completed.await(3,TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); worker.close(); }
    }

    @Test void invalidCompletionIsMarkedFailedInTraceNotReportedAsSuccess() {
        var service=mock(GeoReportInterpretationService.class); var python=mock(PythonInternalClient.class);
        var observation=mock(ObservabilitySupport.class); var span=mock(ObservabilitySupport.Operation.class);
        when(observation.startConsumer(anyString(),anyString(),any(),eq(true))).thenReturn(span);
        when(service.claimNext()).thenReturn(Optional.of(claim()));
        when(python.synthesizeGeo(any())).thenReturn(PythonCallResult.available(200,new ObjectMapper().createObjectNode()));
        when(service.completed(any(),any())).thenReturn("GEO_INTERPRETATION_INVALID_RESULT");
        var worker=new GeoReportInterpretationWorker(service,provider(python),observation);
        try {
            worker.executeOne();
            verify(span).fail("GEO_INTERPRETATION_INVALID_RESULT","SYNTHESIS_RECOVERY");
            verify(python,times(1)).synthesizeGeo(any());
            verify(service,never()).failed(any(),anyString());
        } finally { worker.close(); }
    }

    @Test void timeoutIsPersistedOnceWithoutAutomaticProviderRetry() {
        var service=mock(GeoReportInterpretationService.class); var python=mock(PythonInternalClient.class);
        when(service.claimNext()).thenReturn(Optional.of(claim()));
        when(python.synthesizeGeo(any())).thenReturn(PythonCallResult.unavailable(504,PythonCallResult.Reason.HTTP_ERROR));
        var worker=new GeoReportInterpretationWorker(service,provider(python),ObservabilitySupport.noop());
        try {
            worker.executeOne();
            verify(service).failed(any(),eq("GEO_INTERPRETATION_TIMEOUT"));
            verify(python,times(1)).synthesizeGeo(any());
            verify(service,never()).completed(any(),any());
        } finally { worker.close(); }
    }

    @Test void confirmedCapacityRejectionDoesNotUseGenericAttemptFailure() {
        var service=mock(GeoReportInterpretationService.class); var python=mock(PythonInternalClient.class);
        when(service.claimNext()).thenReturn(Optional.of(claim()));
        when(python.synthesizeGeo(any())).thenReturn(PythonCallResult.unavailable(429,PythonCallResult.Reason.HTTP_ERROR));
        var worker=new GeoReportInterpretationWorker(service,provider(python),ObservabilitySupport.noop());
        try {
            worker.executeOne();
            verify(service).capacityRejected(any()); verify(service,never()).failed(any(),anyString());
            verify(python,times(1)).synthesizeGeo(any());
        } finally { worker.close(); }
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<PythonInternalClient> provider(PythonInternalClient client) {
        ObjectProvider<PythonInternalClient> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client); return provider;
    }
    @Test void competitiveAnalysisUsesTheSameWorkerAndOnlyItsOwnCompletion() {
        var service=mock(GeoReportInterpretationService.class);var competitive=mock(GeoCompetitiveService.class);var python=mock(PythonInternalClient.class);
        var base=claim();var source=base.work();
        var work=new GeoCompetitiveRecords.Insight(source.id(),source.reportId(),source.userId(),source.inputHash(),"RUNNING",1,
                source.claimToken(),source.leaseExpiresAt(),source.deadlineAt(),null,null,null,source.availableAt(),source.capacityWaitUntil(),null);
        var claim=new GeoCompetitiveRecords.Claim(work,base.request(),401,501);
        when(service.claimNext()).thenReturn(Optional.empty());when(competitive.claimNext()).thenReturn(Optional.of(claim));
        when(python.synthesizeGeo(any())).thenReturn(PythonCallResult.available(200,new ObjectMapper().createObjectNode()));
        var worker=new GeoReportInterpretationWorker(service,provider(python),ObservabilitySupport.noop());
        worker.setCompetitive(new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("competitive",competitive)).getBeanProvider(GeoCompetitiveService.class));
        try {worker.executeOne();verify(competitive).completed(eq(claim),any());verify(service,never()).completed(any(),any());verify(python,times(1)).synthesizeGeo(any());}
        finally {worker.close();}
    }
    @Test void competitiveCapacityRejectionDoesNotConsumeGenericFailureAttempt() {
        var service=mock(GeoReportInterpretationService.class);var competitive=mock(GeoCompetitiveService.class);var python=mock(PythonInternalClient.class);
        var source=claim().work();var work=new GeoCompetitiveRecords.Insight(source.id(),source.reportId(),source.userId(),source.inputHash(),"RUNNING",1,
                source.claimToken(),source.leaseExpiresAt(),source.deadlineAt(),null,null,null,source.availableAt(),source.capacityWaitUntil(),null);
        when(competitive.claimNext()).thenReturn(Optional.of(new GeoCompetitiveRecords.Claim(work,new ObjectMapper().createObjectNode(),401,501)));
        when(python.synthesizeGeo(any())).thenReturn(PythonCallResult.unavailable(429,PythonCallResult.Reason.HTTP_ERROR));
        var worker=new GeoReportInterpretationWorker(service,provider(python),ObservabilitySupport.noop());
        worker.setCompetitive(new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("competitive",competitive)).getBeanProvider(GeoCompetitiveService.class));
        try {worker.executeOne();verify(competitive).capacityRejected(any());verify(competitive,never()).failed(any(),any());}
        finally {worker.close();}
    }
    private GeoInterpretationRecords.Claim claim() {
        OffsetDateTime now=OffsetDateTime.now();
        var work=new GeoInterpretationRecords.Work(801,701,41,"a".repeat(64),"RUNNING",1,UUID.randomUUID(),
                now.plusSeconds(90),now.plusSeconds(60),now,now,null,null,null,now,now.plusSeconds(120));
        return new GeoInterpretationRecords.Claim(work,new ObjectMapper().createObjectNode(),401,501);
    }
}
