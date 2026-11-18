package com.guanghe.backend.geo;

import com.guanghe.backend.asset.ObjectStorage;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import com.guanghe.backend.integration.internal.client.PythonCallResult;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Model/PDF/OSS IO is outside database transactions; a separate lease fences every delivery phase. */
@Component
@ConditionalOnProperty(prefix="zhixiaohe.paid-task",name={"enabled","geo-diagnostic-enabled"},havingValue="true")
public class GeoDiagnosticDeliveryWorker {
    private final GeoDiagnosticDeliveryService service;
    private final ObjectProvider<PythonInternalClient> python;
    private final ObjectProvider<ObjectStorage> storage;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{
        Thread t=new Thread(r,"geo-diagnostic-delivery");t.setDaemon(true);return t;});
    public GeoDiagnosticDeliveryWorker(GeoDiagnosticDeliveryService service,ObjectProvider<PythonInternalClient> python,ObjectProvider<ObjectStorage> storage){
        this.service=service;this.python=python;this.storage=storage;
    }
    @Scheduled(fixedDelayString="${GEO_DIAGNOSTIC_DELIVERY_POLL_DELAY_MS:1000}")
    public void processOne(){if(!busy.compareAndSet(false,true))return;try{worker.execute(()->{try{execute();}finally{busy.set(false);}});}
        catch(RejectedExecutionException closed){busy.set(false);}}
    void execute(){
        var claim=service.claimNext().orElse(null);if(claim==null)return;
        if(claim.phase().equals("ANALYSIS_RUNNING")){
            var client=python.getIfAvailable();if(client==null){service.failed(claim,"ANALYSIS_UNAVAILABLE");return;}
            try{var result=client.analyzeGeoDiagnostic(claim.request());
                if(result.available()&&Integer.valueOf(200).equals(result.httpStatus())&&result.payload()!=null)service.analyzed(claim,result.payload());
                else service.failed(claim,result.reason()==PythonCallResult.Reason.TIMEOUT?"ANALYSIS_TIMEOUT":"ANALYSIS_UNAVAILABLE");
            }catch(RuntimeException unavailable){service.failed(claim,"ANALYSIS_UNAVAILABLE");}return;
        }
        var store=storage.getIfAvailable();if(store==null){service.failed(claim,"PDF_STORAGE_UNAVAILABLE");return;}
        Path folder=null;Path file=null;
        try{
            byte[] bytes=GeoDiagnosticPdfRenderer.pdf(claim.detail(),claim.fullObservations(),claim.fullPreparationSources());
            if(bytes.length>10*1024*1024){service.failed(claim,"PDF_EVIDENCE_TOO_LARGE");return;}
            folder=Files.createTempDirectory("zhixiaohe-diagnostic-");file=folder.resolve("report.pdf");Files.write(file,bytes);
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var object=store.storeOrConfirm(claim.objectKey(),file,"application/pdf",bytes.length,digest);
            // A random, private key isolates uncertain IO outcomes. Late results never overwrite a chosen report.
            if(!service.ready(claim,object,"geo-diagnostic-"+claim.id()+"-v1.pdf")&&object.createdByCaller()&&service.mayDiscard(claim,object.objectKey()))store.delete(object.objectKey());
        }catch(GeoDiagnosticPdfRenderer.TooLarge tooLarge){service.failed(claim,"PDF_EVIDENCE_TOO_LARGE");}
        catch(Exception unavailable){service.failed(claim,"PDF_DELIVERY_UNAVAILABLE");}
        finally{try{if(file!=null)Files.deleteIfExists(file);if(folder!=null)Files.deleteIfExists(folder);}catch(Exception ignored){}}
    }
    @PreDestroy public void close(){worker.shutdown();}
}
