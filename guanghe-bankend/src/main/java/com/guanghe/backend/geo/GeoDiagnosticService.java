package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.agentplatform.AgentPlatformService;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.ConversationMessageRecord;
import com.guanghe.backend.task.PaidTaskProperties;
import java.net.URI;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Six-card preparation. The only model calls happen outside transactions in the bounded worker. */
@Service
@ConditionalOnProperty(prefix="zhixiaohe.paid-task",name={"enabled","geo-diagnostic-enabled"},havingValue="true")
public class GeoDiagnosticService {
    private static final Set<String> ACTIVE=Set.of("QUEUED","RUNNING");
    private static final Set<String> PREPARATION_PROMPT_VERSIONS=Set.of("GEO_DIAGNOSTIC_PREPARATION_V1","GEO_DIAGNOSTIC_PREPARATION_V2","GEO_DIAGNOSTIC_PREPARATION_V3","GEO_DIAGNOSTIC_PREPARATION_V4","GEO_DIAGNOSTIC_PREPARATION_V5");
    private final GeoDiagnosticMapper mapper;
    private final ConversationMapper conversations;
    private final GeoPeriodReportMapper projects;
    private final AgentPlatformService agents;
    private final ObjectMapper json;
    private final SnowflakeIdGenerator ids;
    private final CatalogNameNormalizer names;
    private final PaidTaskProperties pricing;
    private org.springframework.beans.factory.ObjectProvider<GeoDiagnosticDeliveryService> delivery;
    private org.springframework.beans.factory.ObjectProvider<com.guanghe.backend.task.TaskControlService> taskControl;
    @org.springframework.beans.factory.annotation.Autowired
    void setDelivery(org.springframework.beans.factory.ObjectProvider<GeoDiagnosticDeliveryService> value,
        org.springframework.beans.factory.ObjectProvider<com.guanghe.backend.task.TaskControlService> control) {this.delivery=value;this.taskControl=control;}
    public GeoDiagnosticService(GeoDiagnosticMapper mapper,ConversationMapper conversations,GeoPeriodReportMapper projects,
        AgentPlatformService agents,ObjectMapper json,SnowflakeIdGenerator ids,CatalogNameNormalizer names,PaidTaskProperties pricing) {
        this.mapper=mapper;this.conversations=conversations;this.projects=projects;this.agents=agents;
        this.json=json;this.ids=ids;this.names=names;this.pricing=pricing;
    }
    @Transactional
    public GeoDiagnosticDtos.Detail create(AuthenticatedAccount account,GeoDiagnosticDtos.Create request) {
        long user=owner(account),conversation=positive(request.conversationId());
        admission(account); conversations.lockConversation(conversation,user).orElseThrow(this::missing);
        long project=scope(user,conversation);
        var replay=mapper.request(user,request.requestId()).orElse(null);
        if(replay!=null) {
            if(replay.conversationId()!=conversation)throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return detail(replay);
        }
        if(mapper.count(user,conversation,null)>=20)throw rate("DIAGNOSTIC_SESSION_LIMIT");
        ObjectNode data=json.createObjectNode();
        String region=mapper.projectRegion(user,project).orElseGet(()->mapper.topicRegion(user,project).orElse("全国"));
        data.set("profile",json.valueToTree(new GeoDiagnosticDtos.Profile("",null,"",List.of(),region.length()<=100?region:"")));
        for(String field:List.of("competitors","discoveryQuestions","reputationQuestions","platformCodes","sources"))data.putArray(field);
        data.putArray("warnings");
        if(region.length()>100)data.withArray("warnings").add("项目已有目标地域较长，请填写本次诊断的目标地域。");
        var now=now();var row=new GeoDiagnosticMapper.Row(ids.nextId(),user,project,conversation,request.requestId(),1,1,write(data),null,now,now);
        if(mapper.insert(row)!=1)throw new IllegalStateException("Diagnostic insertion failed");
        card(row,data);return detail(row);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public GeoDiagnosticDtos.Detail get(AuthenticatedAccount account,long id) {
        var row=mapper.find(owner(account),id).orElseThrow(this::missing);
        if(scope(row.userId(),row.conversationId())!=row.spaceId())throw missing();return detail(row);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<GeoDiagnosticDtos.Detail> list(AuthenticatedAccount account,Long conversation,Long project,int page,int size) {
        long user=owner(account);
        if(conversation==null && project==null)throw invalid("scope");
        if(conversation!=null){long bound=scope(user,conversation);if(project!=null && project!=bound)throw missing();}
        if(project!=null)projects.project(user,project).orElseThrow(this::missing);
        return PageResponse.of(mapper.list(user,conversation,project,size,(long)(page-1)*size).stream().map(row->{
            if(scope(row.userId(),row.conversationId())!=row.spaceId())throw missing();return detail(row);}).toList(),
            page,size,mapper.count(user,conversation,project));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<GeoDiagnosticDtos.Detail> listSubmitted(AuthenticatedAccount account,Long conversation,Long project,int page,int size) {
        long user=owner(account);if(conversation==null&&project==null)throw invalid("scope");
        if(conversation!=null){long bound=scope(user,conversation);if(project!=null&&project!=bound)throw missing();}
        if(project!=null)projects.project(user,project).orElseThrow(this::missing);
        return PageResponse.of(mapper.listSubmitted(user,conversation,project,size,(long)(page-1)*size).stream().map(row->{
            if(scope(user,row.conversationId())!=row.spaceId())throw missing();return detail(row);}).toList(),page,size,mapper.countSubmitted(user,conversation,project));
    }
    @Transactional
    public GeoDiagnosticDtos.Detail patch(AuthenticatedAccount account,long id,GeoDiagnosticDtos.Patch request) {
        var row=mutable(account,id,request.version());
        if(request.step()>row.step())throw invalid("step");
        int step=request.step();ObjectNode data=read(row.dataJson());boolean changed=false;
        if(request.profile()!=null) {
            if(step>2)throw invalid("profile");
            var profile=profile(request.profile(),step==2 && request.advance());
            boolean identityChanged=!GeoVisibilityProjection.normalize(profile.brandName()).equals(
                GeoVisibilityProjection.normalize(data.path("profile").path("brandName").asText()));
            if(identityChanged){data.putArray("sources");data.putArray("warnings");}
            JsonNode next=json.valueToTree(profile);
            changed=!next.equals(data.path("profile"));data.set("profile",next);
            if(changed)clearAfter(data,2);
        }
        if(request.competitors()!=null){if(step!=3)throw invalid("competitors");
            var next=json.valueToTree(competitors(request.competitors(),data));changed=!next.equals(data.path("competitors"));
            data.set("competitors",next);if(changed)clearAfter(data,3);}
        if(request.discoveryQuestions()!=null){if(step!=4)throw invalid("discoveryQuestions");
            var next=json.valueToTree(questions(request.discoveryQuestions(),data.path("discoveryQuestions")));
            changed=!next.equals(data.path("discoveryQuestions"));data.set("discoveryQuestions",next);if(changed)clearAfter(data,4);}
        if(request.reputationQuestions()!=null){if(step!=5)throw invalid("reputationQuestions");
            var next=json.valueToTree(questions(request.reputationQuestions(),data.path("reputationQuestions")));
            changed=!next.equals(data.path("reputationQuestions"));data.set("reputationQuestions",next);}
        if(request.platformCodes()!=null){if(step!=6)throw invalid("platformCodes");data.set("platformCodes",json.valueToTree(platforms(request.platformCodes(),false)));changed=true;}
        validateQuestionCounts(data,false);
        if(changed || step<row.step())mapper.supersede(id,now());
        if(request.advance()) {
            if(step==1 || step==6)throw invalid("advance");
            validateStep(data,step);step++;
            if(step==6)data.set("platformCodes",json.valueToTree(pricing.getGeoConfiguredPlatforms().stream().sorted().toList()));
        }
        var saved=save(row,step,data,null);card(saved,data);
        if(request.advance() && step<6)queue(saved,data,phaseForStep(step),UUID.randomUUID());
        return detail(mapper.find(row.userId(),id).orElseThrow());
    }
    @Transactional
    public GeoDiagnosticDtos.Detail generate(AuthenticatedAccount account,long id,GeoDiagnosticDtos.Generate request) {
        var original=mapper.find(owner(account),id).orElseThrow(this::missing);
        admission(account);conversations.lockConversation(original.conversationId(),original.userId()).orElseThrow(this::missing);
        scope(original.userId(),original.conversationId());
        var row=mapper.lock(original.userId(),id).orElseThrow(this::missing);
        requireUnsubmitted(row);
        var replay=mapper.jobRequest(row.userId(),request.requestId()).orElse(null);
        if(replay!=null){if(replay.diagnosticId()!=id || !replay.phase().equals(request.phase().name()) || replay.requestVersion()!=request.version())
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);return detail(row);}
        requireVersion(row,request.version());
        int target=targetStep(request.phase());
        if(request.phase()==GeoDiagnosticDtos.Phase.BRAND_RESEARCH ? row.step()!=1 : row.step()!=target)throw invalid("phase");
        ObjectNode data=read(row.dataJson());validateGeneration(data,request.phase());
        mapper.supersede(id,now());
        var saved=save(row,target,data,null);card(saved,data);queue(saved,data,request.phase(),request.requestId());return detail(saved);
    }
    @Transactional
    public GeoDiagnosticDtos.Detail quote(AuthenticatedAccount account,long id,GeoDiagnosticDtos.QuoteRequest request) {
        var row=mutable(account,id,request.version());if(row.step()!=6)throw invalid("step");
        ObjectNode data=read(row.dataJson());validateQuestionCounts(data,true);validateStep(data,2);
        var codes=platforms(request.platformCodes(),true);data.set("platformCodes",json.valueToTree(codes));
        GeoDiagnosticProjection.preflight(data,json);
        int questionCount=data.path("discoveryQuestions").size()+data.path("reputationQuestions").size();
        if(pricing.getGeoTestUnitPoints()<1 || pricing.getGeoTestPricingVersionId()<1)throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        if(pricing.geoRequiredProviderCalls(questionCount,codes,1)>pricing.getGeoDiagnosticMaxProviderCalls())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,"当前采集容量不足，请减少问题或平台",Map.of("reason","GEO_CALL_BUDGET"));
        int samples=Math.multiplyExact(questionCount,codes.size());
        ObjectNode frozen=data.deepCopy();frozen.put("pricingVersionId",Long.toString(pricing.getGeoTestPricingVersionId()));
        int points=Math.multiplyExact(samples,pricing.getGeoTestUnitPoints());
        var budget=taskControl==null?null:taskControl.getObject().diagnosticBudget(row.userId(),points);
        var quote=new GeoDiagnosticDtos.Quote(UUID.randomUUID().toString(),hash(write(frozen)),row.version()+1,samples,
            points,now().plus(pricing.getConfirmationTtl()),budget==null?"BUDGET_UNAVAILABLE":budget.blockedReason(),budget==null?null:budget.availablePoints(),
            budget==null?null:budget.maxPointsPerAction(),budget==null?null:budget.maxPointsPerDay());
        var saved=save(row,6,data,write(quote));card(saved,data);return detail(saved);
    }
    record Claim(long id,long diagnosticId,long userId,long conversationId,UUID token,ObjectNode request) { }
    @Transactional
    public Optional<Claim> claimNext() {
        var time=now();mapper.expire(time);var job=mapper.next().orElse(null);if(job==null)return Optional.empty();
        UUID token=UUID.randomUUID();if(mapper.claim(job.id(),token,time.plusSeconds(90),time)!=1)return Optional.empty();
        ObjectNode request=json.createObjectNode().put("jobId",Long.toString(job.id())).put("inputHash",job.inputHash())
            .put("phase",job.phase()).put("deadlineAt",time.plusSeconds(60).toString());request.set("input",read(job.inputJson()));
        return Optional.of(new Claim(job.id(),job.diagnosticId(),job.userId(),job.conversationId(),token,request));
    }
    @Transactional
    public void completed(Claim claim,JsonNode response) {
        var row=completionRow(claim);if(row==null)return;
        ObjectNode data=read(row.dataJson());ObjectNode tracked;
        try {
            fields(response,Set.of("jobId","inputHash","promptVersion","result","synthesisUsage","searchRequestCount"));
            if(!Long.toString(claim.id()).equals(response.path("jobId").asText())
                ||!claim.request().path("inputHash").asText().equals(response.path("inputHash").asText())
                ||!supportedPromptVersion(response.path("promptVersion").asText()))throw invalid("identity");
            var result=response.path("result");fields(result,Set.of("profile","competitors","questions","sources","warnings"));
            if(!result.path("warnings").isArray() || result.path("warnings").size()>20)throw invalid("warnings");
            List<String> warnings=stringList(result.path("warnings"),20,1000);
            var usage=response.path("synthesisUsage");fields(usage,Set.of("provider","model","inputTokens","outputTokens","providerCallCount","latencyMs"));
            if(!"DEEPSEEK".equals(usage.path("provider").asText()) || !Set.of("deepseek-flash","deepseek-v4-flash").contains(usage.path("model").asText()))throw invalid("usage");
            for(String key:List.of("inputTokens","outputTokens","providerCallCount","latencyMs"))
                if(!usage.path(key).isIntegralNumber() || usage.path(key).asLong()<0 || !usage.path(key).canConvertToInt())throw invalid("usage");
            if(usage.path("latencyMs").asInt()>3_600_000)throw invalid("usage");
            if(usage.path("providerCallCount").asInt()>2 || !response.path("searchRequestCount").isIntegralNumber()
                ||response.path("searchRequestCount").asInt()<0 ||response.path("searchRequestCount").asInt()>6)throw invalid("usage");
            var phase=GeoDiagnosticDtos.Phase.valueOf(claim.request().path("phase").asText());
            if(row.step()!=targetStep(phase))throw invalid("phase");
            var availableSources=sourceCandidates(data,result.path("sources"));
            // Validate candidate citations against the complete, bounded response union before selecting evidence.
            // This is transaction-local only; the persisted preparation and new card always contain at most 32 sources.
            data.set("sources",json.valueToTree(availableSources.values()));
            if(phase==GeoDiagnosticDtos.Phase.BRAND_RESEARCH) {
                if(!result.path("profile").isObject())throw invalid("profile");
                var generated=profile(json.convertValue(result.path("profile"),GeoDiagnosticDtos.Profile.class),false);
                var existing=json.convertValue(data.path("profile"),GeoDiagnosticDtos.Profile.class);
                if(!names.requiredName(generated.brandName(),200,"brandName").normalized().equals(names.requiredName(existing.brandName(),200,"brandName").normalized()))throw invalid("brandName");
                generated=new GeoDiagnosticDtos.Profile(existing.brandName(),generated.website(),generated.introduction(),
                    generated.brandTerms(),existing.region().isBlank()?generated.region():existing.region());
                data.set("profile",json.valueToTree(generated));clearAfter(data,2);
            } else if(phase==GeoDiagnosticDtos.Phase.COMPETITORS)mergeCompetitors(data,result.path("competitors"));
            else mergeQuestions(data,result.path("questions"),phase==GeoDiagnosticDtos.Phase.DISCOVERY_QUESTIONS?"discoveryQuestions":"reputationQuestions");
            retainSources(data,availableSources,result.path("sources"));
            validateQuestionCounts(data,false);
            data.set("warnings",json.valueToTree(warnings));
            if(write(data).getBytes(StandardCharsets.UTF_8).length>192*1024)throw invalid("data");
            tracked=usage.deepCopy();tracked.set("searchRequestCount",response.path("searchRequestCount"));
            tracked.put("promptVersion",response.path("promptVersion").asText());
        } catch (BusinessException | IllegalArgumentException rejected) {
            mapper.finish(claim.id(),claim.token(),"FAILED","PREPARATION_INVALID_RESULT",null,now());
            return;
        }
        if(mapper.finish(claim.id(),claim.token(),"SUCCEEDED",null,write(tracked),now())!=1)return;
        var saved=save(row,row.step(),data,null);card(saved,data);
    }
    @Transactional
    public void failed(Claim claim,String code) {
        var row=completionRow(claim);if(row!=null)mapper.finish(claim.id(),claim.token(),"FAILED",code,null,now());
    }
    private GeoDiagnosticMapper.Row completionRow(Claim claim) {
        projects.lockOwner(claim.userId()).orElseThrow(this::missing);
        if(conversations.lockConversation(claim.conversationId(),claim.userId()).isEmpty())return null;
        var row=mapper.lock(claim.userId(),claim.diagnosticId()).orElse(null);if(row==null)return null;
        var job=mapper.lockJob(claim.id()).orElse(null);
        if(job==null || !"RUNNING".equals(job.status()) || !claim.token().equals(job.claimToken())
            ||job.leaseExpiresAt()==null ||!job.leaseExpiresAt().isAfter(now()))return null;
        try { if(scope(claim.userId(),claim.conversationId())!=row.spaceId())throw missing(); }
        catch(BusinessException revoked){mapper.finish(claim.id(),claim.token(),"FAILED","PREPARATION_SCOPE_UNAVAILABLE",null,now());return null;}
        return row;
    }
    private void queue(GeoDiagnosticMapper.Row row,ObjectNode data,GeoDiagnosticDtos.Phase phase,UUID request) {
        validateGeneration(data,phase);
        if(mapper.active(row.userId())>0)throw rate("DIAGNOSTIC_GENERATION_ACTIVE");
        if(mapper.generationCount(row.id())>=20)throw rate("DIAGNOSTIC_GENERATION_LIMIT");
        ObjectNode input=data.deepCopy();input.put("brandName",data.path("profile").path("brandName").asText());
        input.remove(List.of("platformCodes","warnings"));String frozen=write(input);
        if(frozen.getBytes(StandardCharsets.UTF_8).length>192*1024)throw invalid("input");
        var time=now();mapper.job(new GeoDiagnosticMapper.Job(ids.nextId(),row.id(),row.userId(),row.conversationId(),request,
            phase.name(),hash(phase.name()+"\n"+frozen),frozen,"QUEUED",null,null,null,time,time,row.version()-1));
    }
    private GeoDiagnosticMapper.Row mutable(AuthenticatedAccount account,long id,int version) {
        long user=owner(account);admission(account);var found=mapper.find(user,id).orElseThrow(this::missing);
        conversations.lockConversation(found.conversationId(),user).orElseThrow(this::missing);
        if(scope(user,found.conversationId())!=found.spaceId())throw missing();
        var locked=mapper.lock(user,id).orElseThrow(this::missing);requireUnsubmitted(locked);requireVersion(locked,version);return locked;
    }
    private void requireUnsubmitted(GeoDiagnosticMapper.Row row) {if(delivery!=null&&delivery.getObject().submitted(row.userId(),row.id()))throw conflict();}
    private void admission(AuthenticatedAccount account) {
        if(conversations.lockAdmissionAccount(account.id(),account.tokenVersion())==null)throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
    }
    private long scope(long user,long conversation) {
        var identity=agents.assertContextReadAllowed(user,conversation,false);
        if(!"geo".equals(identity.agentKey()) || identity.projectId()==null)throw invalid("GEO_PROJECT_REQUIRED");
        long project=positive(identity.projectId());projects.project(user,project).orElseThrow(this::missing);return project;
    }
    private GeoDiagnosticMapper.Row save(GeoDiagnosticMapper.Row row,int step,ObjectNode data,String quote) {
        if(write(data).getBytes(StandardCharsets.UTF_8).length>192*1024)throw invalid("data");
        var time=now();if(mapper.save(row.userId(),row.id(),row.version(),step,write(data),quote,time)!=1)throw conflict();
        return new GeoDiagnosticMapper.Row(row.id(),row.userId(),row.spaceId(),row.conversationId(),row.requestId(),
            row.version()+1,step,write(data),quote,row.createdAt(),time);
    }
    private void card(GeoDiagnosticMapper.Row row,ObjectNode data) {
        if(mapper.cards(row.id()).size()>=128)throw rate("DIAGNOSTIC_CARD_LIMIT");
        long message=ids.nextId();var time=now();ObjectNode content=json.createObjectNode().put("text","");
        String brand=data.path("profile").path("brandName").asText();
        if(!brand.isBlank())mapper.defaultTitle(row.userId(),row.conversationId(),"品牌诊断 · "+brand.substring(0,Math.min(100,brand.length())),time);
        content.putObject("geoDiagnostic").put("diagnosticId",Long.toString(row.id())).put("step",row.step());
        Long sequence=conversations.nextMessageSequence(row.conversationId(),row.userId());
        if(sequence==null || conversations.insertAssistantMessage(new ConversationMessageRecord(message,row.conversationId(),row.userId(),
            "ASSISTANT","TEXT",write(content),null,null,null,null,null,sequence,"COMPLETED",time,time))!=1)
            throw new IllegalStateException("Diagnostic card message failed");
        mapper.card(new GeoDiagnosticMapper.CardRow(ids.nextId(),row.id(),row.userId(),row.conversationId(),row.step(),message,
            write(snapshot(row,data)),time));
    }
    private GeoDiagnosticDtos.Detail detail(GeoDiagnosticMapper.Row row) {
        ObjectNode data=read(row.dataJson());var value=snapshot(row,data);var latest=mapper.latest(row.id()).orElse(null);
        var generation=latest!=null && !Set.of("SUCCEEDED","SUPERSEDED").contains(latest.status())
            ?new GeoDiagnosticDtos.Generation(GeoDiagnosticDtos.Phase.valueOf(latest.phase()),latest.status(),latest.errorCode()):null;
        List<GeoDiagnosticDtos.Card> cards=mapper.cards(row.id()).stream().map(c->new GeoDiagnosticDtos.Card(c.step(),Long.toString(c.messageId()),
            convert(read(c.snapshotJson()),GeoDiagnosticDtos.Snapshot.class))).toList();
        return new GeoDiagnosticDtos.Detail(Long.toString(row.id()),row.version(),Long.toString(row.spaceId()),Long.toString(row.conversationId()),row.step(),
            value.profile().brandName(),value.profile(),value.competitors(),value.discoveryQuestions(),value.reputationQuestions(),value.platformCodes(),
            value.sources(),value.warnings(),generation,cards,row.quoteJson()==null?null:convert(read(row.quoteJson()),GeoDiagnosticDtos.Quote.class),pricing.isGeoDiagnosticSubmissionEnabled(),
            delivery==null?null:delivery.getObject().summary(row.userId(),row.id()));
    }
    private GeoDiagnosticDtos.Snapshot snapshot(GeoDiagnosticMapper.Row row,ObjectNode data) {
        ObjectNode value=data.deepCopy();value.put("version",row.version());return convert(value,GeoDiagnosticDtos.Snapshot.class);
    }
    private GeoDiagnosticDtos.Profile profile(GeoDiagnosticDtos.Profile raw,boolean complete) {
        String brand=raw.brandName().isBlank()?"":names.requiredName(raw.brandName(),200,"brandName").value();
        String intro=raw.introduction().strip(),region=raw.region().strip();
        if(complete && (brand.isBlank() || intro.isBlank() || region.isBlank()))throw invalid("profile");
        if(intro.length()>6000 || region.length()>100)throw invalid("profile");
        List<String> terms=new ArrayList<>(names.aliases(raw.brandTerms(),200,"brandTerms").stream().map(v->v.value()).toList());
        if(!brand.isBlank() && terms.stream().noneMatch(t->GeoVisibilityProjection.normalize(t).equals(GeoVisibilityProjection.normalize(brand))))terms.add(0,brand);
        if(terms.size()>50)throw invalid("brandTerms");
        return new GeoDiagnosticDtos.Profile(brand,website(raw.website()),intro,terms,region);
    }
    private List<GeoDiagnosticDtos.Competitor> competitors(List<GeoDiagnosticDtos.Competitor> values,ObjectNode data) {
        Set<String> idsSeen=new HashSet<>(),namesSeen=new HashSet<>();List<GeoDiagnosticDtos.Competitor> result=new ArrayList<>();
        if(values.size()>5)throw invalid("competitors");Set<String> own=identityTerms(data);Set<String> sourceIds=new HashSet<>();
        data.path("sources").forEach(s->sourceIds.add(s.path("id").asText()));
        for(var value:values){var name=names.requiredName(value.name(),200,"competitors");
            if("OWN".equals(value.id().strip()) ||!idsSeen.add(value.id()) || !namesSeen.add(name.normalized()) || matchesNames(name.value(),List.copyOf(own)))throw invalid("competitors");
            List<String> terms=names.aliases(value.brandTerms(),200,"brandTerms").stream().map(v->v.value()).toList();
            if(terms.stream().anyMatch(t->matchesNames(t,List.copyOf(own))))throw invalid("competitors");
            if(!sourceIds.containsAll(value.sourceIds()))throw invalid("sourceIds");
            JsonNode prior=find(data.path("competitors"),value.id());boolean manual=prior==null;
            boolean edited=manual || prior.path("edited").asBoolean() || !name.value().equals(prior.path("name").asText())
                ||!json.valueToTree(terms).equals(prior.path("brandTerms")) ||!value.reason().equals(prior.path("reason").asText());
            result.add(new GeoDiagnosticDtos.Competitor(value.id(),name.value(),terms,value.reason().strip(),value.sourceIds(),
                manual?"USER":prior.path("origin").asText(),edited));}
        return List.copyOf(result);
    }
    private List<GeoDiagnosticDtos.Question> questions(List<GeoDiagnosticDtos.Question> values,JsonNode previous) {
        if(values.size()>10)throw invalid("questions");Set<String> idsSeen=new HashSet<>(),seen=new HashSet<>();List<GeoDiagnosticDtos.Question> result=new ArrayList<>();
        for(var value:values){var text=names.requiredName(value.text(),1000,"questions");if(!idsSeen.add(value.id()) ||!seen.add(text.normalized()))throw invalid("questions");
            var prior=find(previous,value.id());boolean manual=prior==null;result.add(new GeoDiagnosticDtos.Question(value.id(),text.value(),manual?"USER":prior.path("origin").asText(),
                manual ||prior.path("edited").asBoolean() ||!text.value().equals(prior.path("text").asText())));}
        return List.copyOf(result);
    }
    Map<String,JsonNode> sourceCandidates(ObjectNode data,JsonNode sources) {
        if(!sources.isArray() ||sources.size()>32)throw invalid("sources");Map<String,JsonNode> merged=new LinkedHashMap<>();
        data.path("sources").forEach(s->merged.put(s.path("id").asText(),s));
        for(var value:sources){fields(value,Set.of("id","title","url","snippet"));
            String id=text(value.path("id"),128),title=text(value.path("title"),500),url=sourceUrl(text(value.path("url"),2048));
            String snippet=value.path("snippet").isTextual()?value.path("snippet").asText():null;
            if(snippet==null ||snippet.length()>2000)throw invalid("sources");
            if(merged.containsKey(id) && !merged.get(id).equals(value))throw invalid("sourceId");
            merged.put(id,json.valueToTree(new GeoDiagnosticDtos.Source(id,title,url,snippet)));}
        return merged;
    }
    void retainSources(ObjectNode data,Map<String,JsonNode> available,JsonNode incoming) {
        // COMPETITORS has already replaced unlocked AI candidates, preserving USER/edited entries. In later
        // phases every selected competitor survives. Protect exactly those final citations, including new candidates.
        Set<String> required=new LinkedHashSet<>();
        data.path("competitors").forEach(c->c.path("sourceIds").forEach(id->required.add(id.asText())));
        if(required.size()>32 ||!available.keySet().containsAll(required))throw invalid("sourceIds");
        Map<String,JsonNode> retained=new LinkedHashMap<>();
        required.forEach(id->retained.put(id,available.get(id)));
        // Python puts the current phase's evidence first; old, unreferenced sources only fill remaining capacity.
        incoming.forEach(source->{String id=source.path("id").asText().strip();
            if(retained.size()<32)retained.putIfAbsent(id,available.get(id));});
        available.forEach((id,source)->{if(retained.size()<32)retained.putIfAbsent(id,source);});
        data.set("sources",json.valueToTree(retained.values()));
    }
    private void mergeCompetitors(ObjectNode data,JsonNode generated) {
        if(!generated.isArray() ||generated.size()>5)throw invalid("competitors");var keep=new ArrayList<JsonNode>();
        data.path("competitors").forEach(c->{if(c.path("edited").asBoolean() ||"USER".equals(c.path("origin").asText()))keep.add(c);});
        Set<String> seen=new HashSet<>();keep.forEach(c->seen.add(GeoVisibilityProjection.normalize(c.path("name").asText())));
        Set<String> own=identityTerms(data),sourceIds=new HashSet<>();data.path("sources").forEach(s->sourceIds.add(s.path("id").asText()));
        for(var item:generated){fields(item,Set.of("name","brandTerms","reason","sourceIds"));
            String name=text(item.path("name"),200);String key=GeoVisibilityProjection.normalize(name);
            if(matchesNames(name,List.copyOf(own)) ||!seen.add(key))continue;
            List<String> terms=stringList(item.path("brandTerms"),50,200),source=stringList(item.path("sourceIds"),8,128);
            if(!sourceIds.containsAll(source) ||terms.stream().anyMatch(t->matchesNames(t,List.copyOf(own))))throw invalid("competitors");
            if(keep.size()<5)keep.add(json.valueToTree(new GeoDiagnosticDtos.Competitor(UUID.randomUUID().toString(),name,
                names.aliases(terms,200,"brandTerms").stream().map(v->v.value()).toList(),text(item.path("reason"),1000),source,"AI",false)));}
        data.set("competitors",json.valueToTree(keep));clearAfter(data,3);
    }
    private void mergeQuestions(ObjectNode data,JsonNode generated,String field) {
        if(!generated.isArray() ||generated.size()>10)throw invalid("questions");var keep=new ArrayList<JsonNode>();
        data.path(field).forEach(q->{if(q.path("edited").asBoolean() ||"USER".equals(q.path("origin").asText()))keep.add(q);});
        int target="discoveryQuestions".equals(field)?5:3;String other="discoveryQuestions".equals(field)?"reputationQuestions":"discoveryQuestions";
        int capacity=10-data.path(other).size();target=Math.min(capacity,Math.max(target,keep.size()));Set<String> seen=new HashSet<>();
        keep.forEach(q->seen.add(GeoVisibilityProjection.normalize(q.path("text").asText())));
        for(var q:generated){fields(q,Set.of("text"));String value=text(q.path("text"),1000);
            if(keep.size()<target && seen.add(GeoVisibilityProjection.normalize(value)))keep.add(json.valueToTree(new GeoDiagnosticDtos.Question(UUID.randomUUID().toString(),value,"AI",false)));}
        if(keep.isEmpty())throw invalid("questions");data.set(field,json.valueToTree(keep));
        if("discoveryQuestions".equals(field))clearAfter(data,4);
    }
    private void validateStep(ObjectNode data,int step) {
        if(step==2)profile(convert(data.path("profile"),GeoDiagnosticDtos.Profile.class),true);
        if(step==4 && (data.path("discoveryQuestions").isEmpty() ||data.path("discoveryQuestions").size()>9))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,"请保留 1–9 个可见度问题，至少为品牌口碑留出一个名额",Map.of("field","discoveryQuestions"));
        if(step==5)validateQuestionCounts(data,true);
    }
    private void validateGeneration(ObjectNode data,GeoDiagnosticDtos.Phase phase) {
        if(data.path("profile").path("brandName").asText().isBlank())throw invalid("brandName");
        if(phase!=GeoDiagnosticDtos.Phase.BRAND_RESEARCH)validateStep(data,2);
        if(phase==GeoDiagnosticDtos.Phase.REPUTATION_QUESTIONS)validateStep(data,4);
    }
    private void validateQuestionCounts(ObjectNode data,boolean complete) {
        int discovery=data.path("discoveryQuestions").size(),reputation=data.path("reputationQuestions").size();
        if(discovery+reputation>10 ||complete && (discovery<1 ||reputation<1 ||discovery+reputation<3))throw invalid("questions");
        Set<String> seen=new HashSet<>();for(String field:List.of("discoveryQuestions","reputationQuestions"))for(var q:data.path(field))
            if(!seen.add(GeoVisibilityProjection.normalize(q.path("text").asText())))throw invalid("questions");
        List<String> own=new ArrayList<>(identityTerms(data));List<String> all=new ArrayList<>(own);
        for(var c:data.path("competitors")){all.add(c.path("name").asText());c.path("brandTerms").forEach(a->all.add(a.asText()));}
        for(var q:data.path("discoveryQuestions"))if(matchesNames(q.path("text").asText(),all))throw invalid("discoveryQuestions");
        for(var q:data.path("reputationQuestions"))if(!matchesNames(q.path("text").asText(),own))throw invalid("reputationQuestions");
    }
    private Set<String> identityTerms(ObjectNode data) {
        Set<String> result=new LinkedHashSet<>();result.add(GeoVisibilityProjection.normalize(data.path("profile").path("brandName").asText()));
        data.path("profile").path("brandTerms").forEach(t->result.add(GeoVisibilityProjection.normalize(t.asText())));result.remove("");return result;
    }
    private List<String> platforms(List<String> values,boolean required) {
        if(required && values.isEmpty() ||values.size()>7 ||new HashSet<>(values).size()!=values.size())throw invalid("platformCodes");
        if(!pricing.getGeoConfiguredPlatforms().containsAll(values))throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        return List.copyOf(values);
    }
    static boolean supportedPromptVersion(String version) {return PREPARATION_PROMPT_VERSIONS.contains(version==null?"":version);}
    /** Diagnostic input validation includes every explicit user term, including one-letter and category-like names. */
    static boolean matchesNames(String text,List<String> terms) {
        String normalized=Normalizer.normalize(text,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        for(String term:terms) {
            String name=Normalizer.normalize(term,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
            if(name.isEmpty())continue;
            String left=wordEdge(name.codePointAt(0))?"(?<![\\p{IsLatin}\\p{N}_])":"";
            String right=wordEdge(name.codePointBefore(name.length()))?"(?![\\p{IsLatin}\\p{N}_])":"";
            if(Pattern.compile(left+Pattern.quote(name)+right).matcher(normalized).find())return true;
        }
        return false;
    }
    private static boolean wordEdge(int point) {
        return Character.UnicodeScript.of(point)==Character.UnicodeScript.LATIN ||Character.isDigit(point);
    }
    private void clearAfter(ObjectNode data,int step) {
        if(step<3)data.putArray("competitors");if(step<4)data.putArray("discoveryQuestions");if(step<5)data.putArray("reputationQuestions");data.putArray("platformCodes");
    }
    private static GeoDiagnosticDtos.Phase phaseForStep(int step) {
        return switch(step){case 3->GeoDiagnosticDtos.Phase.COMPETITORS;case 4->GeoDiagnosticDtos.Phase.DISCOVERY_QUESTIONS;case 5->GeoDiagnosticDtos.Phase.REPUTATION_QUESTIONS;default->throw new IllegalArgumentException("Invalid step");};
    }
    private static int targetStep(GeoDiagnosticDtos.Phase phase) { return switch(phase){case BRAND_RESEARCH->2;case COMPETITORS->3;case DISCOVERY_QUESTIONS->4;case REPUTATION_QUESTIONS->5;}; }
    private JsonNode find(JsonNode rows,String id) { for(var row:rows)if(row.path("id").asText().equals(id))return row;return null; }
    private void fields(JsonNode value,Set<String> fields) {
        if(!value.isObject())throw invalid("result");Set<String> actual=new HashSet<>();value.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(fields))throw invalid("result");
    }
    private List<String> stringList(JsonNode value,int max,int length) {if(!value.isArray() ||value.size()>max)throw invalid("list");
        List<String> result=new ArrayList<>();value.forEach(v->result.add(text(v,length)));return result;}
    private String text(JsonNode value,int max){if(!value.isTextual() ||value.asText().isBlank() ||value.asText().length()>max)throw invalid("text");return value.asText().strip();}
    static String website(String raw) {
        if(raw==null ||raw.isBlank())return null;
        try {String value=raw.strip();if(!value.contains("://"))value="https://"+value;URI uri=URI.create(value);
            String host=uri.getHost();if(!"https".equalsIgnoreCase(uri.getScheme()) ||host==null ||uri.getRawUserInfo()!=null
                ||uri.getPort()!=-1 && uri.getPort()!=443 ||host.indexOf('.')<0 ||host.matches("[0-9.]+")
                ||host.contains(":") ||host.toLowerCase(Locale.ROOT).endsWith(".local") ||host.toLowerCase(Locale.ROOT).endsWith(".localhost") ||host.toLowerCase(Locale.ROOT).endsWith(".internal")
                ||value.length()>2048)throw new IllegalArgumentException();return uri.toASCIIString();
        }catch(IllegalArgumentException invalid){throw new BusinessException(ErrorCode.VALIDATION_FAILED,"请填写公开可访问的 HTTPS 网站地址",Map.of("field","website"));}
    }
    static String sourceUrl(String raw) {
        try {URI uri=URI.create(raw);String host=uri.getHost();String scheme=uri.getScheme();
            if(host==null || !Set.of("http","https").contains(scheme==null?"":scheme.toLowerCase(Locale.ROOT))
                ||uri.getRawUserInfo()!=null ||host.indexOf('.')<0 ||host.matches("[0-9.]+") ||host.contains(":")
                ||host.toLowerCase(Locale.ROOT).endsWith(".local") ||host.toLowerCase(Locale.ROOT).endsWith(".localhost") ||host.toLowerCase(Locale.ROOT).endsWith(".internal")
                ||uri.getPort()!=-1 && uri.getPort()!=80 && uri.getPort()!=443 ||raw.length()>2048)throw new IllegalArgumentException();
            return uri.toASCIIString();
        }catch(IllegalArgumentException invalid){throw new BusinessException(ErrorCode.VALIDATION_FAILED,"调研来源地址无效",Map.of("field","sources"));}
    }
    private <T>T convert(JsonNode value,Class<T> type){return json.convertValue(value,type);}
    private ObjectNode read(String value){try{return(ObjectNode)json.readTree(value);}catch(Exception invalid){throw new IllegalStateException("Invalid saved diagnostic JSON");}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException("Diagnostic JSON serialization failed");}}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private long owner(AuthenticatedAccount account){return CatalogAccess.requireUser(account);}
    private long positive(String value){try{long result=Long.parseLong(value);if(result<1)throw new NumberFormatException();return result;}catch(NumberFormatException invalid){throw invalid("id");}}
    private void requireVersion(GeoDiagnosticMapper.Row row,int version){if(row.version()!=version)throw conflict();}
    private BusinessException conflict(){return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);}
    private BusinessException missing(){return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);}
    private BusinessException invalid(String field){return names.invalid(field);}
    private BusinessException rate(String reason){return new BusinessException(ErrorCode.RATE_LIMITED,"请稍后重试，或继续当前诊断",Map.of("reason",reason));}
    private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
}
