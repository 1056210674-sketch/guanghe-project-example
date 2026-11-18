package com.guanghe.backend.geo;

import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.integration.internal.client.PythonCallResult;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The service methods commit before this worker makes any model call. No auto model retry. */
@Component
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = {"enabled", "geo-interpretation-enabled"}, havingValue = "true")
public class GeoReportInterpretationWorker {
    private final GeoReportInterpretationService service;
    private final PythonInternalClient python;
    private final ObservabilitySupport observability;
    private GeoCompetitiveService competitive;

    @Autowired(required = false)
    void setCompetitive(ObjectProvider<GeoCompetitiveService> provider) { competitive = provider.getIfAvailable(); }
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "geo-interpretation");
                thread.setDaemon(true);
                return thread;
            });

    public GeoReportInterpretationWorker(GeoReportInterpretationService service,
            ObjectProvider<PythonInternalClient> python, ObservabilitySupport observability) {
        this.service = service; this.python = python.getIfAvailable(); this.observability = observability;
    }

    @Scheduled(fixedDelayString = "${GEO_INTERPRETATION_POLL_DELAY_MS:1000}")
    public void processOne() {
        if (!busy.compareAndSet(false, true)) return;
        try { worker.execute(() -> { try { executeOne(); } finally { busy.set(false); } }); }
        catch (java.util.concurrent.RejectedExecutionException shutdown) { busy.set(false); }
    }

    @PreDestroy
    public void close() { worker.shutdown(); }

    void executeOne() {
        service.expireOne();
        if (competitive != null) competitive.expireOne();
        if (python == null) return;
        GeoInterpretationRecords.Claim claim = service.claimNext().orElse(null);
        if (claim == null) { executeCompetitive(); return; }
        try (ObservabilitySupport.Operation span = observability.startConsumer(
                "java.geo.interpretation", "SYNTHESIS_RECOVERY", claim.work().traceparent(), true)) {
            span.tag("taskIdRef", observability.reference(claim.taskId()));
            span.tag("runIdRef", observability.reference(claim.runId()));
            span.tag("retryNo", claim.work().attempts());
            try {
                var result = python.synthesizeGeo(claim.request());
                if (result.available() && result.httpStatus() == 200 && result.payload() != null) {
                    String error = service.completed(claim, result.payload());
                    if (error != null) span.fail(error, "SYNTHESIS_RECOVERY");
                } else if (Integer.valueOf(429).equals(result.httpStatus())) {
                    span.fail("GEO_INTERPRETATION_CAPACITY_FULL", "SYNTHESIS_RECOVERY");
                    service.capacityRejected(claim);
                } else {
                    String code = result.reason() == PythonCallResult.Reason.TIMEOUT || Integer.valueOf(504).equals(result.httpStatus())
                            ? "GEO_INTERPRETATION_TIMEOUT" : "GEO_INTERPRETATION_PROVIDER_UNAVAILABLE";
                    span.fail(code, "SYNTHESIS_RECOVERY");
                    service.failed(claim, code);
                }
            } catch (RuntimeException failure) {
                span.fail("GEO_INTERPRETATION_PROVIDER_UNAVAILABLE", "SYNTHESIS_RECOVERY");
                service.failed(claim, "GEO_INTERPRETATION_PROVIDER_UNAVAILABLE");
            }
        }
    }

    private void executeCompetitive() {
        if (competitive == null) return;
        GeoCompetitiveRecords.Claim claim = competitive.claimNext().orElse(null);
        if (claim == null) return;
        try (ObservabilitySupport.Operation span = observability.startConsumer(
                "java.geo.competitive", "COMPETITIVE_INSIGHT", claim.work().traceparent(), true)) {
            span.tag("taskIdRef", observability.reference(claim.taskId()));
            span.tag("runIdRef", observability.reference(claim.runId()));
            span.tag("retryNo", claim.work().attempts());
            try {
                var result = python.synthesizeGeo(claim.request());
                if (result.available() && result.httpStatus() == 200 && result.payload() != null) {
                    String error = competitive.completed(claim, result.payload());
                    if (error != null) span.fail(error, "COMPETITIVE_INSIGHT");
                } else if (Integer.valueOf(429).equals(result.httpStatus())) {
                    competitive.capacityRejected(claim);
                } else {
                    String code = result.reason() == PythonCallResult.Reason.TIMEOUT || Integer.valueOf(504).equals(result.httpStatus())
                            ? "TIMEOUT" : "PROVIDER_UNAVAILABLE";
                    span.fail(code, "COMPETITIVE_INSIGHT"); competitive.failed(claim, code);
                }
            } catch (RuntimeException failure) {
                span.fail("PROVIDER_UNAVAILABLE", "COMPETITIVE_INSIGHT"); competitive.failed(claim, "PROVIDER_UNAVAILABLE");
            }
        }
    }
}
