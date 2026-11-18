package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.agentplatform.AgentPlatformService;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.ObjectStorage;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.ConversationEventRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.task.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One paid sampling task followed by a separately fenced, free-to-recover report delivery. */
@Service
@ConditionalOnProperty(prefix="zhixiaohe.paid-task",name={"enabled","geo-diagnostic-enabled"},havingValue="true")
public class GeoDiagnosticDeliveryService {
    static final int ANALYSIS_TIMEOUT_SECONDS=120;
    private final GeoDiagnosticDeliveryMapper mapper;
    private final GeoDiagnosticMapper preparations;
    private final GeoMapper topics;
    private final GeoTaskMapper runs;
    private final TaskControlMapper tasks;
    private final TaskControlService control;
    private final ConversationMapper conversations;
    private final AgentPlatformService agents;
    private final PaidTaskProperties pricing;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final CatalogNameNormalizer names;
    private final ObjectProvider<ObjectStorage> storage;
    private final AssetIngestionProperties assetProperties;
    private final TaskNotificationService notifications;
    private final GeoTaskService geoTasks;
    public GeoDiagnosticDeliveryService(GeoDiagnosticDeliveryMapper mapper,GeoDiagnosticMapper preparations,GeoMapper topics,
        GeoTaskMapper runs,TaskControlMapper tasks,TaskControlService control,ConversationMapper conversations,AgentPlatformService agents,
        PaidTaskProperties pricing,SnowflakeIdGenerator ids,ObjectMapper json,CatalogNameNormalizer names,
        ObjectProvider<ObjectStorage> storage,AssetIngestionProperties assetProperties,TaskNotificationService notifications,GeoTaskService geoTasks) {
        this.mapper=mapper;this.preparations=preparations;this.topics=topics;this.runs=runs;this.tasks=tasks;this.control=control;
        this.conversations=conversations;this.agents=agents;this.pricing=pricing;this.ids=ids;this.json=json;this.names=names;
        this.storage=storage;this.assetProperties=assetProperties;this.notifications=notifications;this.geoTasks=geoTasks;
    }
    @Transactional
    public void submit(AuthenticatedAccount account,long id,GeoDiagnosticReportDtos.Submit request) {
        long user=CatalogAccess.requireUser(account);
        if(conversations.lockAdmissionAccount(account.id(),account.tokenVersion())==null)throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        var original=preparations.find(user,id).orElseThrow(this::missing);scope(original);
        String requestHash=hash(write(json.valueToTree(List.of(id,request.version(),request.quoteId().toString(),request.inputHash()))));
        var byRequest=mapper.byRequest(user,request.requestId()).orElse(null);
        if(byRequest!=null){if(byRequest.diagnosticId()!=id||!byRequest.requestHash().equals(requestHash))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);return;}
        var row=preparations.lock(user,id).orElseThrow(this::missing);
        byRequest=mapper.byRequest(user,request.requestId()).orElse(null);
        if(byRequest!=null){if(byRequest.diagnosticId()!=id||!byRequest.requestHash().equals(requestHash))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);return;}
        if(mapper.find(user,id).isPresent())throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,"诊断已提交，请查看后台进度",Map.of());
        if(!pricing.isGeoDiagnosticSubmissionEnabled()||!pricing.isGeoExecutionEnabled()||storage.getIfAvailable()==null)
            throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        if(row.version()!=request.version()||row.step()!=6||row.quoteJson()==null)throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        var quote=convert(read(row.quoteJson()),GeoDiagnosticDtos.Quote.class);var data=read(row.dataJson());
        GeoDiagnosticProjection.preflight(data,json);
        var frozen=data.deepCopy();frozen.put("pricingVersionId",Long.toString(pricing.getGeoTestPricingVersionId()));
        int units=(data.path("discoveryQuestions").size()+data.path("reputationQuestions").size())*data.path("platformCodes").size();
        if(!quote.id().equals(request.quoteId().toString())||!quote.inputHash().equals(request.inputHash())||quote.version()!=row.version()
            ||!now().isBefore(quote.expiresAt())||!hash(write(frozen)).equals(quote.inputHash())||units!=quote.expectedSamples()
            ||quote.points()!=Math.multiplyExact(units,pricing.getGeoTestUnitPoints()))throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        if(preparations.latest(id).filter(j->Set.of("QUEUED","RUNNING").contains(j.status())).isPresent())throw conflict();
        int questionCount=data.path("discoveryQuestions").size()+data.path("reputationQuestions").size();
        var codes=convertStrings(data.path("platformCodes"));
        if(questionCount<3||questionCount>10||data.path("discoveryQuestions").isEmpty()||data.path("reputationQuestions").isEmpty()
            ||codes.isEmpty()||codes.size()>7||!pricing.getGeoConfiguredPlatforms().containsAll(codes)
            ||pricing.geoRequiredProviderCalls(questionCount,codes,1)>pricing.getGeoDiagnosticMaxProviderCalls())throw conflict();
        var time=now();long topicId=ids.nextId();String topicName="诊断 · "+id;
        var profile=convert(data.path("profile"),GeoDiagnosticDtos.Profile.class);
        if(topics.insertTopic(new GeoRecords.Topic(topicId,user,row.spaceId(),null,topicName,names.requiredName(topicName,100,"topic").normalized(),
            profile.region(),1,time,time))!=1||mapper.diagnosticTopic(user,topicId)!=1)throw conflict();
        var parameters=json.createObjectNode().put("mode","DIAGNOSTIC_RUN").put("topicId",Long.toString(topicId)).put("sampleMode","STANDARD").put("region",profile.region());
        parameters.set("platformCodes",data.path("platformCodes").deepCopy());var qIds=parameters.putArray("questionIds");
        var snapshot=json.createObjectNode().put("taskType","GEO").put("parameterSchemaVersion",1);snapshot.set("parameters",parameters);
        var authorized=snapshot.putObject("authorizedContext");
        authorized.set("topic",json.valueToTree(new GeoTaskDtos.TopicSnapshot(Long.toString(topicId),Long.toString(row.spaceId()),null,topicName,profile.region(),1)));
        var questions=authorized.putArray("questions");int sequence=0;
        for(String field:List.of("discoveryQuestions","reputationQuestions"))for(var q:data.path(field)) {
            long questionId=ids.nextId();String category=field.equals("discoveryQuestions")?"BRAND_DISCOVERY":"BRAND_REPUTATION";
            String text=q.path("text").asText();var normalized=names.requiredName(text,1000,"question");
            if(topics.insertQuestion(new GeoRecords.Question(questionId,user,row.spaceId(),topicId,++sequence,GeoDtos.QuestionCategory.valueOf(category),
                text,normalized.normalized(),GeoDtos.QuestionSource.MANUAL,true,time,time))!=1)throw conflict();
            qIds.add(Long.toString(questionId));questions.addObject().put("id",Long.toString(questionId)).put("category",category).put("text",text);
        }
        authorized.putNull("competitorSetVersion");var comps=authorized.putArray("competitors");
        for(var comp:data.path("competitors")){var c=comps.addObject().put("id",comp.path("id").asText()).put("name",comp.path("name").asText()).put("competitorType","DIRECT");c.set("aliases",comp.path("brandTerms").deepCopy());}
        var target=authorized.putObject("monitoringTarget").put("kind","BRAND").put("name",profile.brandName());target.set("aliases",json.valueToTree(profile.brandTerms()));
        var diagnostic=authorized.putObject("diagnostic").put("diagnosticId",Long.toString(id));
        for(String field:List.of("profile","competitors","sources","warnings"))diagnostic.set(field,data.path(field).deepCopy());
        snapshot.putObject("catalogSnapshot");
        snapshot.putObject("diagnosticExecutionBudget").put("maxProviderCalls",pricing.getGeoDiagnosticMaxProviderCalls())
            .put("maxConcurrency",pricing.getGeoMaxConcurrency()).put("deadlineSeconds",pricing.getGeoExecutionDeadline().toSeconds());
        if(write(snapshot).getBytes(StandardCharsets.UTF_8).length>250*1024)throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var task=control.admitGeoDiagnostic(account,row.conversationId(),row.spaceId(),new GeoTaskService.PreparedDraft(parameters,snapshot,units),request.requestId(),requestHash,quote.points());
        long taskId=Long.parseLong(task.id());var run=runs.findRunByTask(taskId).orElseThrow();
        var delivery=new GeoDiagnosticDeliveryMapper.Row(id,user,row.spaceId(),row.conversationId(),taskId,run.id(),topicId,request.requestId(),requestHash,row.version(),
            request.quoteId(),request.inputHash(),pricing.getGeoTestPricingVersionId(),quote.points(),"SAMPLING",1,"QUEUED",0,0,null,null,null,null,null,0,0,null,null,null,null,null,null,null,time,time,null);
        if(mapper.insert(delivery)!=1||mapper.revision(user,id,time)!=1)throw conflict();
    }
    @Transactional(readOnly=true)
    public GeoDiagnosticReportDtos.Delivery summary(long user,long id) {return mapper.find(user,id).map(this::summary).orElse(null);}
    public boolean submitted(long user,long id){return mapper.find(user,id).isPresent();}
    @Transactional(readOnly=true)
    public GeoDiagnosticReportDtos.Detail get(AuthenticatedAccount account,long id) {
        long user=CatalogAccess.requireUser(account);var prep=preparations.find(user,id).orElseThrow(this::missing);scope(prep);
        return detail(mapper.find(user,id).orElseThrow(this::missing));
    }
    @Transactional
    public GeoDiagnosticReportDtos.Detail retry(AuthenticatedAccount account,long id,GeoDiagnosticReportDtos.Retry request) {
        long user=CatalogAccess.requireUser(account);var prep=preparations.find(user,id).orElseThrow(this::missing);scope(prep);
        String requestHash=hash(id+"\n"+request.version());
        var prior=mapper.recovery(user,request.requestId()).orElse(null);
        if(prior!=null){if(prior.diagnosticId()!=id||!prior.requestHash().equals(requestHash))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return detail(mapper.find(user,id).orElseThrow(this::missing));}
        var row=mapper.lock(user,id).orElseThrow(this::missing);
        prior=mapper.recovery(user,request.requestId()).orElse(null);
        if(prior!=null){if(prior.diagnosticId()!=id||!prior.requestHash().equals(requestHash))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);return detail(row);}
        if(row.version()!=request.version())throw conflict();
        var time=now();if(mapper.insertRecovery(user,request.requestId(),id,request.version(),requestHash,time)!=1)throw conflict();
        if(!Set.of("ANALYSIS_FAILED","PDF_FAILED").contains(row.status()))return detail(row);
        if(!retryable(row))throw new BusinessException(ErrorCode.RATE_LIMITED);
        String next=row.status().equals("ANALYSIS_FAILED")?"ANALYSIS_QUEUED":"PDF_QUEUED";
        if(mapper.retry(id,next,time)!=1)throw conflict();changed(row,time,next,null);return detail(mapper.find(user,id).orElseThrow());
    }
    @Transactional(readOnly=true)
    public GeoDiagnosticReportDtos.Download download(AuthenticatedAccount account,long id,int version) {
        long user=CatalogAccess.requireUser(account);var prep=preparations.find(user,id).orElseThrow(this::missing);scope(prep);
        var row=mapper.find(user,id).orElseThrow(this::missing);if(!"READY".equals(row.status())||version!=1)throw conflict();
        var store=storage.getIfAvailable();if(store==null)throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        var expiry=now().plus(assetProperties.getSignedUrlTtl());return new GeoDiagnosticReportDtos.Download(store.signedGetUrl(row.objectKey(),expiry).toString(),expiry,row.pdfFilename());
    }
    @Transactional(readOnly=true)
    public PageResponse<GeoTaskDtos.ObservationResponse> observations(AuthenticatedAccount account,long id,int page,int size) {
        long user=CatalogAccess.requireUser(account);var prep=preparations.find(user,id).orElseThrow(this::missing);scope(prep);
        var row=mapper.find(user,id).orElseThrow(this::missing);
        return PageResponse.of(runs.findObservationPage(row.runId(),(long)(page-1)*size,size).stream().map(geoTasks::observation).toList(),page,size,runs.countObservations(row.runId()));
    }
    @Transactional(readOnly=true)
    public ObjectNode latestAgentProjection(long user,long space) {
        if(!runs.activeProject(user,space))throw missing();
        var newest=mapper.latest(user,space).orElse(null);var ready=mapper.latestReady(user,space).orElse(null);
        ObjectNode result=json.createObjectNode().put("readOnly",true).put("status",ready==null?"NO_REPORT":"READY");
        if(newest!=null)result.putObject("latestAttempt").put("diagnosticId",Long.toString(newest.diagnosticId())).put("status",newest.status())
            .put("samplingStatus",newest.samplingStatus()).put("errorCode",newest.errorCode()).put("isSelectedReport",ready!=null&&newest.diagnosticId()==ready.diagnosticId());
        if(ready==null)return result;
        var evidence=read(ready.evidenceJson());var report=read(ready.analysisJson());
        result.put("diagnosticId",Long.toString(ready.diagnosticId())).put("reportVersion",1).put("snapshotAt",ready.snapshotAt().toString())
            .put("hasNoData",evidence.path("metrics").path("validSamples").asInt()==0).put("samplingStatus",ready.samplingStatus()).put("truncated",true);
        result.set("metrics",evidence.path("metrics").deepCopy());result.set("limitations",evidence.path("limitations").deepCopy());
        var analysis=result.putObject("analysis");analysis.put("title",report.path("title").asText()).put("summary",GeoDiagnosticProjection.excerpt(report.path("summary").asText(),1200));
        for(String field:List.of("findings","recommendations","contentTopics")){var selected=analysis.putArray(field);int count=0;
            for(var item:report.path(field)){if(count++>=3)break;selected.add(item.deepCopy());}}
        var sources=result.putArray("sources");int count=0;for(var source:evidence.path("sources")){if(count++>=3)break;sources.add(source.deepCopy());}
        return result;
    }
    @Transactional(readOnly=true)
    public boolean isAgentProjectionAvailable(long user,long space,long id,int reportVersion) {
        return reportVersion==1&&runs.activeProject(user,space)&&mapper.find(user,id).filter(row->row.spaceId()==space&&row.status().equals("READY")).isPresent();
    }
    /** Called within the sampling/settlement transaction, before a completion notice can escape. */
    public void samplingCompleted(TaskRecords.Task task,GeoTaskRecords.Run run,String samplingStatus,int settled,int released,
                                  List<GeoTaskRecords.Observation> observations,OffsetDateTime time) {
        var row=mapper.byTask(task.id()).orElseThrow(this::missing);ObjectNode evidence=GeoDiagnosticProjection.evidence(run,observations,runs,json);
        String frozen=write(evidence);if(frozen.getBytes(StandardCharsets.UTF_8).length>250*1024)throw conflict();
        boolean noData=evidence.path("metrics").path("validSamples").asInt()==0;
        var analysis=noData?new GeoDiagnosticReportDtos.Analysis(reportTitle(evidence.path("profile").path("brandName").asText(),true),
            "本次监测没有获得有效回答，不能判断品牌自然可见度、竞品差异或口碑。失败和缺失样本不当成未提及，全部采样积分已释放。此文件仅交付实际采集失败记录。",
            List.of(),List.of(),List.of(),List.of("无有效样本；报告不构成优化成果或口碑评价。")):null;
        if(mapper.sampling(task.id(),noData?"PDF_QUEUED":"ANALYSIS_QUEUED",samplingStatus,settled,released,frozen,hash(frozen),analysis==null?null:write(json.valueToTree(analysis)),time)!=1)throw conflict();
        changed(row,time,noData?"PDF_QUEUED":"ANALYSIS_QUEUED",null);
    }
    record Claim(long id,long userId,UUID token,String phase,ObjectNode request,GeoDiagnosticReportDtos.Detail detail,String objectKey,
                 List<GeoTaskDtos.ObservationResponse> fullObservations,List<GeoDiagnosticDtos.Source> fullPreparationSources) { }
    @Transactional
    public Optional<Claim> claimNext() {
        var time=now();var expired=mapper.expired(time).orElse(null);
        if(expired!=null){String failed=expired.status().equals("PDF_RUNNING")?"PDF_FAILED":"ANALYSIS_FAILED";
            if(mapper.expire(expired.diagnosticId(),failed,time)==1){changed(expired,time,failed,"DELIVERY_LEASE_EXPIRED");
                finishExhausted(mapper.find(expired.userId(),expired.diagnosticId()).orElseThrow(),time);}}
        var stranded=mapper.strandedSampling().orElse(null);
        if(stranded!=null){var task=tasks.findTaskInternal(stranded.taskId()).orElseThrow();var run=runs.findRunByTask(task.id()).orElseThrow();
            samplingCompleted(task,run,run.status(),task.settledPoints(),task.quotedPoints()-task.settledPoints(),runs.findObservationPage(run.id(),0,210),time);}
        var row=mapper.next().orElse(null);if(row==null)return Optional.empty();
        String phase=row.status().equals("ANALYSIS_QUEUED")?"ANALYSIS_RUNNING":"PDF_RUNNING";UUID token=UUID.randomUUID();
        if(mapper.claim(row.diagnosticId(),row.status(),phase,token,deliveryLeaseExpiresAt(phase,time),time)!=1)return Optional.empty();changed(row,time,phase,null);
        var request=json.createObjectNode().put("jobId",Long.toString(row.diagnosticId())).put("inputHash",row.evidenceHash()).put("deadlineAt",time.plusSeconds(ANALYSIS_TIMEOUT_SECONDS).toString())
            .put("title",reportTitle(read(row.evidenceJson()).path("profile").path("brandName").asText(),false));request.set("evidence",read(row.evidenceJson()));
        var claimed=mapper.find(row.userId(),row.diagnosticId()).orElseThrow();
        return Optional.of(new Claim(row.diagnosticId(),row.userId(),token,phase,request,detail(claimed),
            "zhixiaohe/diagnostic-reports/"+row.userId()+"/"+row.diagnosticId()+"/"+UUID.randomUUID()+".pdf",
            phase.equals("PDF_RUNNING")?runs.findObservationPage(row.runId(),0,210).stream().map(geoTasks::observation).toList():List.of(),
            phase.equals("PDF_RUNNING")?convertList(read(preparations.find(row.userId(),row.diagnosticId()).orElseThrow().dataJson()).path("sources"),GeoDiagnosticDtos.Source.class):List.of()));
    }
    @Transactional
    public void analyzed(Claim claim,JsonNode response) {
        var row=active(claim);if(row==null)return;
        try {
            exact(response,Set.of("jobId","inputHash","promptVersion","report","synthesisUsage"));
            if(!Long.toString(claim.id()).equals(response.path("jobId").asText())||!row.evidenceHash().equals(response.path("inputHash").asText())
                ||!Set.of("GEO_DIAGNOSTIC_ANALYSIS_V10","GEO_DIAGNOSTIC_ANALYSIS_V11").contains(response.path("promptVersion").asText()))throw conflict();
            var report=GeoDiagnosticProjection.validateAnalysis(response.path("report"),read(row.evidenceJson()),json,
                "GEO_DIAGNOSTIC_ANALYSIS_V11".equals(response.path("promptVersion").asText()));
            var usage=response.path("synthesisUsage");exact(usage,Set.of("provider","model","inputTokens","outputTokens","providerCallCount","latencyMs"));
            if(!"DEEPSEEK".equals(usage.path("provider").asText())||!Set.of("deepseek-flash","deepseek-v4-flash").contains(usage.path("model").asText()))throw conflict();
            for(String key:List.of("inputTokens","outputTokens","providerCallCount","latencyMs"))if(!usage.path(key).isIntegralNumber()||!usage.path(key).canConvertToInt()||usage.path(key).asInt()<0)throw conflict();
            if(usage.path("providerCallCount").asInt()<1||usage.path("providerCallCount").asInt()>2||!validAnalysisLatency(usage.path("latencyMs").asInt()))throw conflict();
            var savedUsage=(ObjectNode)usage.deepCopy();savedUsage.put("promptVersion",response.path("promptVersion").asText());
            var time=now();if(mapper.analyzed(claim.id(),claim.token(),write(json.valueToTree(report)),write(savedUsage),time)==1)changed(row,time,"PDF_QUEUED",null);
        } catch(BusinessException|IllegalArgumentException invalid){failed(claim,"ANALYSIS_INVALID_RESULT");}
    }
    @Transactional
    public void failed(Claim claim,String error) {
        var row=active(claim);if(row==null)return;var time=now();String phase=claim.phase().equals("PDF_RUNNING")?"PDF_FAILED":"ANALYSIS_FAILED";
        if(mapper.failed(claim.id(),claim.token(),phase,error,time)==1){changed(row,time,phase,error);
            finishExhausted(mapper.find(row.userId(),row.diagnosticId()).orElseThrow(),time);}
    }
    @Transactional
    public boolean ready(Claim claim,ObjectStorage.StoredObject object,String filename) {
        var row=active(claim);if(row==null)return false;
        if(!"PDF_RUNNING".equals(claim.phase())||!claim.objectKey().equals(object.objectKey())||!"application/pdf".equals(object.contentType())
            ||object.sizeBytes()<1||object.sizeBytes()>10*1024*1024)throw conflict();
        var time=now();if(mapper.ready(claim.id(),claim.token(),object.objectKey(),object.sizeBytes(),object.sha256(),filename,time)!=1)return false;
        boolean noData=read(row.evidenceJson()).path("metrics").path("validSamples").asInt()==0;
        String status=noData?"FAILED":row.samplingStatus().equals("SUCCEEDED")?"SUCCEEDED":"PARTIAL";
        var result=json.createObjectNode().put("diagnosticId",Long.toString(row.diagnosticId())).put("diagnosticReportReady",true)
            .put("samplingStatus",row.samplingStatus()).put("hasNoData",noData).put("settledPoints",row.settledPoints()).put("releasedPoints",row.releasedPoints());
        var originalTask=tasks.findTaskInternal(row.taskId()).orElseThrow();
        result.put("status","RUNNING".equals(originalTask.status())?status:originalTask.status())
            .put("stageCode","RUNNING".equals(originalTask.status())?(noData?"DIAGNOSTIC_NO_DATA":"COMPLETED"):originalTask.stageCode());
        if("RUNNING".equals(originalTask.status())){
            if(mapper.completedTask(row.taskId(),status,noData?"DIAGNOSTIC_NO_DATA":"COMPLETED",noData?"DIAGNOSTIC_NO_VALID_SAMPLES":null,write(result),time)!=1)throw conflict();
        } else if(!Set.of("FAILED","SYSTEM_TERMINATED").contains(originalTask.status()))throw conflict();
        mapper.revision(row.userId(),row.diagnosticId(),time);
        var task=tasks.findTaskInternal(row.taskId()).orElseThrow();
        result.put("status",task.status()).put("stageCode",task.stageCode());
        if(tasks.insertTaskEvent(ids.nextId(),task.id(),task.userId(),null,null,null,originalTask.status(),task.status(),"DIAGNOSTIC_REPORT_READY","JAVA",null,null,write(result),time)!=1)throw conflict();
        browser(row,noData||!"RUNNING".equals(originalTask.status())?"task.failed":"task.completed",result,time);
        if("RUNNING".equals(originalTask.status()))notifications.publishTerminal(task.id(),task.userId());
        else notifications.publishDiagnosticDelivery(task.id(),task.userId(),row.diagnosticId(),true);
        return true;
    }
    @Transactional(readOnly=true)
    public boolean mayDiscard(Claim claim,String objectKey) {
        if(!claim.objectKey().equals(objectKey))return false;
        var row=mapper.find(claim.userId(),claim.id()).orElse(null);
        return row!=null&&!objectKey.equals(row.objectKey());
    }
    private GeoDiagnosticDeliveryMapper.Row active(Claim claim) {
        var row=mapper.lock(claim.userId(),claim.id()).orElse(null);
        return row!=null&&row.status().equals(claim.phase())&&claim.token().equals(row.claimToken())&&row.leaseExpiresAt().isAfter(now())?row:null;
    }
    static OffsetDateTime deliveryLeaseExpiresAt(String phase,OffsetDateTime startedAt) {
        return startedAt.plusSeconds("ANALYSIS_RUNNING".equals(phase)?150:90);
    }
    static boolean validAnalysisLatency(int latencyMs) {return latencyMs>=0&&latencyMs<=ANALYSIS_TIMEOUT_SECONDS*1000;}
    private void changed(GeoDiagnosticDeliveryMapper.Row row,OffsetDateTime time,String stage,String error) {
        if(mapper.revision(row.userId(),row.diagnosticId(),time)!=1)throw conflict();mapper.stage(row.taskId(),"DIAGNOSTIC_"+stage,error,time);
        var task=tasks.findTaskInternal(row.taskId()).orElseThrow();
        browser(row,"task.progress",json.createObjectNode().put("status",task.status()).put("stageCode","DIAGNOSTIC_"+stage).put("diagnosticId",Long.toString(row.diagnosticId())),time);
    }
    /** Delivery exhaustion closes only the business result; the already settled sampling ledger is untouched. */
    private void finishExhausted(GeoDiagnosticDeliveryMapper.Row row,OffsetDateTime time) {
        if(!Set.of("ANALYSIS_FAILED","PDF_FAILED").contains(row.status())||retryable(row))return;
        var original=tasks.findTaskInternal(row.taskId()).orElseThrow();
        if(!"RUNNING".equals(original.status()))return; // watchdog truth is never reopened or rewritten
        String stage="DIAGNOSTIC_"+row.status();
        var result=json.createObjectNode().put("diagnosticId",Long.toString(row.diagnosticId())).put("diagnosticReportReady",false)
            .put("deliveryStatus",row.status()).put("samplingStatus",row.samplingStatus()).put("settledPoints",row.settledPoints())
            .put("releasedPoints",row.releasedPoints()).put("status","FAILED").put("stageCode",stage).put("code",row.errorCode());
        if(mapper.completedTask(row.taskId(),"FAILED",stage,row.errorCode(),write(result),time)!=1)throw conflict();
        if(tasks.insertTaskEvent(ids.nextId(),row.taskId(),row.userId(),null,null,null,"RUNNING","FAILED",stage,"JAVA",null,null,write(result),time)!=1)throw conflict();
        browser(row,"task.failed",result,time);notifications.publishDiagnosticFailure(row.taskId(),row.userId(),row.diagnosticId());
    }
    private void browser(GeoDiagnosticDeliveryMapper.Row row,String type,ObjectNode payload,OffsetDateTime time) {
        payload.put("taskId",Long.toString(row.taskId())).put("deliveryKind","BACKGROUND_TASK");
        var sequence=conversations.nextEventSequence(row.conversationId(),row.userId());
        if(sequence==null||conversations.insertEvent(new ConversationEventRecord(ids.nextId(),row.conversationId(),row.userId(),null,null,row.taskId(),null,type,sequence,write(payload),time,time))!=1)throw conflict();
    }
    private GeoDiagnosticReportDtos.Delivery summary(GeoDiagnosticDeliveryMapper.Row row) {
        var run=runs.findRunByTask(row.taskId()).orElseThrow();int percent=row.status().equals("READY")?100:row.status().equals("SAMPLING")?
            Math.min(70,(run.succeededUnits()+run.failedUnits())*70/run.expectedUnits()):row.status().startsWith("PDF")?95:80;
        return new GeoDiagnosticReportDtos.Delivery(Long.toString(row.taskId()),Long.toString(row.runId()),row.status(),run.status(),
            row.evidenceJson()!=null&&read(row.evidenceJson()).path("metrics").path("validSamples").asInt()==0,row.errorCode(),retryable(row),percent,1);
    }
    private GeoDiagnosticReportDtos.Detail detail(GeoDiagnosticDeliveryMapper.Row row) {
        var data=row.evidenceJson()==null?read(preparations.find(row.userId(),row.diagnosticId()).orElseThrow().dataJson()):read(row.evidenceJson());
        var summary=summary(row);var current=runs.findRunByTask(row.taskId()).orElseThrow();
        var metrics=row.evidenceJson()==null?new GeoDiagnosticReportDtos.Metrics(current.expectedUnits(),current.succeededUnits(),current.failedUnits(),current.missingUnits(),List.of(),List.of()):convert(data.path("metrics"),GeoDiagnosticReportDtos.Metrics.class);
        return new GeoDiagnosticReportDtos.Detail(Long.toString(row.diagnosticId()),row.version(),1,Long.toString(row.taskId()),Long.toString(row.runId()),row.status(),summary.samplingStatus(),summary.hasNoData(),
            row.analysisAttempts(),row.pdfAttempts(),retryable(row),row.errorCode(),row.quotedPoints(),row.settledPoints(),row.releasedPoints(),convert(data.path("profile"),GeoDiagnosticDtos.Profile.class),
            convertStrings(data.path("platformCodes")),metrics,row.analysisJson()==null?null:convert(read(row.analysisJson()),GeoDiagnosticReportDtos.Analysis.class),
            row.evidenceJson()==null?List.of():reportSamples(data,json),convertList(data.path("sources"),GeoDiagnosticDtos.Source.class),
            row.evidenceJson()==null?List.of():convertStrings(data.path("limitations")),row.snapshotAt(),row.pdfSize()==null?null:new GeoDiagnosticReportDtos.Pdf(row.pdfFilename(),row.pdfSize(),row.pdfSha256()),
            convertList(data.path("competitors"),GeoDiagnosticDtos.Competitor.class));
    }
    static List<GeoDiagnosticReportDtos.Sample> reportSamples(ObjectNode data,ObjectMapper json) {
        Map<String,String> questions=new HashMap<>();data.path("questions").forEach(q->questions.put(q.path("id").asText(),q.path("text").asText()));
        List<GeoDiagnosticReportDtos.Sample> samples=new ArrayList<>();for(var value:data.path("samples")){
            var sample=(ObjectNode)value.deepCopy();sample.put("questionText",Objects.requireNonNull(questions.get(sample.path("questionId").asText())));
            if(!"SUCCEEDED".equals(sample.path("status").asText()))sample.putNull("matchedBrandIds");
            samples.add(json.convertValue(sample,GeoDiagnosticReportDtos.Sample.class));}return List.copyOf(samples);
    }
    private boolean retryable(GeoDiagnosticDeliveryMapper.Row row){return row.status().equals("ANALYSIS_FAILED")&&row.analysisAttempts()<2||row.status().equals("PDF_FAILED")&&row.pdfAttempts()<3;}
    // A derived caption has a smaller budget than immutable brand facts. Never truncate the brand profile or matching target.
    static String reportTitle(String brandName,boolean failed){return GeoDiagnosticProjection.excerpt(brandName+(failed?" · GEO 诊断失败说明":" · GEO 诊断报告"),200).strip();}
    private void scope(GeoDiagnosticMapper.Row row){var identity=agents.assertContextReadAllowed(row.userId(),row.conversationId(),false);
        if(!"geo".equals(identity.agentKey())||identity.projectId()==null||Long.parseLong(identity.projectId())!=row.spaceId())throw missing();}
    private ObjectNode read(String value){try{return (ObjectNode)json.readTree(value);}catch(Exception invalid){throw new IllegalStateException("Invalid saved diagnostic JSON");}}
    private String write(JsonNode value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException("Unable to serialize diagnostic JSON");}}
    private <T>T convert(JsonNode value,Class<T> type){return json.convertValue(value,type);}
    private <T>List<T> convertList(JsonNode value,Class<T> type){List<T> list=new ArrayList<>();value.forEach(v->list.add(convert(v,type)));return List.copyOf(list);}
    private List<String> convertStrings(JsonNode value){return convertList(value,String.class);}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
    static void exact(JsonNode value,Set<String> fields){if(!value.isObject())throw new IllegalArgumentException("Expected diagnostic object");Set<String> actual=new HashSet<>();value.fieldNames().forEachRemaining(actual::add);if(!actual.equals(fields))throw new IllegalArgumentException("Invalid diagnostic object fields");}
    private OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    private BusinessException missing(){return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);}
    private BusinessException conflict(){return new BusinessException(ErrorCode.TASK_STATE_CONFLICT);}
}
