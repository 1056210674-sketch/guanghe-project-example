package com.guanghe.backend.geo;

import com.guanghe.backend.integration.internal.client.PythonCallResult;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One bounded background call; database lease fences restarts and late responses. */
@Component
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class GeoPeriodReportWorker {
    private final GeoPeriodReportService service;
    private final PythonInternalClient python;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "geo-period-report"); thread.setDaemon(true); return thread;
            });
    public GeoPeriodReportWorker(GeoPeriodReportService service, ObjectProvider<PythonInternalClient> python) {
        this.service = service; this.python = python.getIfAvailable();
    }
    @Scheduled(fixedDelayString = "${GEO_PERIOD_REPORT_POLL_DELAY_MS:1000}")
    public void processOne() {
        if (!busy.compareAndSet(false, true)) return;
        try { worker.execute(() -> { try { execute(); } finally { busy.set(false); } }); }
        catch (java.util.concurrent.RejectedExecutionException shutdown) { busy.set(false); }
    }
    private void execute() {
        var claim = service.claimNext().orElse(null);
        if (claim == null) return;
        if (python == null) { service.failed(claim, "SUMMARY_UNAVAILABLE"); return; }
        try {
            var result = python.summarizeGeoPeriodReport(claim.request());
            if (result.available() && Integer.valueOf(200).equals(result.httpStatus()) && result.payload() != null)
                service.completed(claim, result.payload());
            else service.failed(claim, result.reason() == PythonCallResult.Reason.TIMEOUT ? "SUMMARY_TIMEOUT" : "SUMMARY_UNAVAILABLE");
        } catch (RuntimeException unavailable) { service.failed(claim, "SUMMARY_UNAVAILABLE"); }
    }
    @PreDestroy public void close() { worker.shutdown(); }
}
