package com.guanghe.backend.geo;

import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import com.guanghe.backend.integration.internal.client.PythonCallResult;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Database queued jobs survive restart; one request per explicit action, never hidden model retries. */
@Component
@ConditionalOnProperty(prefix="zhixiaohe.paid-task",name={"enabled","geo-diagnostic-enabled"},havingValue="true")
public class GeoDiagnosticPreparationWorker {
    private final GeoDiagnosticService service;private final PythonInternalClient python;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),task->{
        Thread thread=new Thread(task,"geo-diagnostic-preparation");thread.setDaemon(true);return thread;});
    public GeoDiagnosticPreparationWorker(GeoDiagnosticService service,ObjectProvider<PythonInternalClient> python){this.service=service;this.python=python.getIfAvailable();}
    @Scheduled(fixedDelayString="${GEO_DIAGNOSTIC_PREPARATION_POLL_DELAY_MS:1000}")
    public void processOne(){if(!busy.compareAndSet(false,true))return;
        try{worker.execute(()->{try{execute();}finally{busy.set(false);}});}catch(RejectedExecutionException closed){busy.set(false);}}
    private void execute(){var claim=service.claimNext().orElse(null);if(claim==null)return;
        if(python==null){service.failed(claim,"PREPARATION_UNAVAILABLE");return;}
        try{var result=python.prepareGeoDiagnostic(claim.request());
            if(result.available() && Integer.valueOf(200).equals(result.httpStatus()) && result.payload()!=null)service.completed(claim,result.payload());
            else service.failed(claim,result.reason()==PythonCallResult.Reason.TIMEOUT?"PREPARATION_TIMEOUT":"PREPARATION_UNAVAILABLE");
        }catch(RuntimeException unavailable){service.failed(claim,"PREPARATION_UNAVAILABLE");}}
    @PreDestroy public void close(){worker.shutdown();}
}
