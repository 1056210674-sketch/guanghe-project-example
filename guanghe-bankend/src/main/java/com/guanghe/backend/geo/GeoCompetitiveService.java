package com.guanghe.backend.geo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.catalog.competitor.*;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import com.guanghe.backend.task.PaidTaskProperties;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saved evidence discovery; never a collection, billing or plan mutation. */
@Service
@ConditionalOnProperty(prefix="zhixiaohe.paid-task",name="enabled",havingValue="true")
public class GeoCompetitiveService {
    static final String DISCOVERY = "geo-competitor-discovery-v1";
    static final String METHOD = "geo-competitive-insight-v2";
    private static final Pattern BILINGUAL_BRAND = Pattern.compile("([一-鿿]{2,30})\\(([A-Za-z]{2,40})\\)");
    private static final Set<String> BRAND_QUALIFIERS = Set.of("pro","plus","max","mini","new","large","small","xl","xxl",
            "premium","official","limited","edition","steel","stainless","plastic","glass","ceramic","silicone",
            "black","white","red","blue","green","silver","gold","usa","uk","china","japan","germany","original");
    private static final Set<String> ACTIVE = Set.of("QUEUED","RUNNING");
    private static final Set<String> SIGNALS = Set.of("MENTION","POSITIVE_ATTRIBUTE","NEGATIVE_ATTRIBUTE");
    private static final Set<String> NON_ENTITIES = Set.of("淘宝","天猫","京东","拼多多","亚马逊","抖音","小红书","知乎","百度","维基百科","wikipedia","amazon","官网","官方网站","商城","旗舰店","品牌官网","电商平台","消费者","用户","竞品","其他品牌","知名品牌",
            "人民日报","新华社","央视新闻","人民网","新华网","中新网","中国新闻网","澎湃新闻","腾讯新闻","新浪新闻","网易新闻","搜狐新闻","今日头条","百度百科");
    private static final List<String> NON_ENTITY_SUFFIXES = List.of("旗舰店","专卖店","专营店","直营店","官方店","官方网店","自营店",
            "官方商城","商城","购物中心","购物广场","百货商场","新闻网","新闻网站","门户网站","资讯网","媒体网","官方网站","官网","店铺","购物平台");
    private final GeoCompetitiveMapper mapper;
    private final BusinessSpaceService spaces;
    private final GeoTaskMapper reports;
    private final GeoMapper topics;
    private final GeoInterpretationMapper interpretations;
    private final GeoReportInterpretationService evidenceBuilder;
    private final CompetitorService catalog;
    private final GeoService geo;
    private final ObjectMapper json;
    private final SnowflakeIdGenerator ids;
    private final PaidTaskProperties properties;
    private final boolean clientAvailable;
    private final Validator validator;
    private final ObservabilitySupport observability;
    private final Clock clock;

    @Autowired
    public GeoCompetitiveService(ObjectProvider<GeoCompetitiveMapper> mapper,ObjectProvider<BusinessSpaceService> spaces,
            ObjectProvider<GeoTaskMapper> reports,
            ObjectProvider<GeoMapper> topics,ObjectProvider<GeoInterpretationMapper> interpretations,
            GeoReportInterpretationService evidenceBuilder,ObjectProvider<CompetitorService> catalog,
            ObjectProvider<GeoService> geo,ObjectMapper json,SnowflakeIdGenerator ids,PaidTaskProperties properties,
            ObjectProvider<PythonInternalClient> python,Validator validator,ObservabilitySupport observability) {
        this(mapper.getIfAvailable(),spaces.getIfAvailable(),reports.getIfAvailable(),topics.getIfAvailable(),interpretations.getIfAvailable(),
                evidenceBuilder,catalog.getIfAvailable(),geo.getIfAvailable(),json,ids,properties,python.getIfAvailable()!=null,
                validator,observability,Clock.systemUTC());
    }
    GeoCompetitiveService(GeoCompetitiveMapper mapper,BusinessSpaceService spaces,GeoTaskMapper reports,GeoMapper topics,
            GeoInterpretationMapper interpretations,GeoReportInterpretationService evidenceBuilder,CompetitorService catalog,
            GeoService geo,ObjectMapper json,SnowflakeIdGenerator ids,PaidTaskProperties properties,boolean clientAvailable,
            Validator validator,ObservabilitySupport observability,Clock clock) {
        this.mapper=mapper;this.spaces=spaces;this.reports=reports;this.topics=topics;this.interpretations=interpretations;
        this.evidenceBuilder=evidenceBuilder;this.catalog=catalog;this.geo=geo;this.json=json;this.ids=ids;
        this.properties=properties;this.clientAvailable=clientAvailable;this.validator=validator;
        this.observability=observability;this.clock=clock;
    }

    @Transactional(readOnly=true)
    public GeoCompetitiveDtos.Insights get(AuthenticatedAccount account,long reportId,String scope) {
        long user=CatalogAccess.requireUser(account); requireStorage(); scope(scope);
        var report=report(reportId,user); var run=run(report);
        var observations=reports.findObservationPage(run.id(),0,210);
        var state=mapper.find(reportId,user).orElse(null);
        return response(report,run,observations,state,scope);
    }

    @Transactional(readOnly=true)
    public PageResponse<GeoCompetitiveDtos.Candidate> candidates(AuthenticatedAccount account,long topicId,String status,int page,int size) {
        long user=CatalogAccess.requireUser(account);requireStorage();
        if(!Set.of("PENDING","CONFIRMED","IGNORED").contains(status)||page<1||size<1||size>100)throw invalid();
        topics.findTopic(topicId,user).orElseThrow(GeoCompetitiveService::notFound);
        return PageResponse.of(candidateDtos(mapper.candidates(user,topicId,status,(long)(page-1)*size,size)),
                page,size,mapper.countCandidates(user,topicId,status));
    }

    @Transactional
    public GeoCompetitiveDtos.Candidate ignore(AuthenticatedAccount account,long candidateId,GeoCompetitiveDtos.IgnoreRequest request) {
        long user=CatalogAccess.requireUser(account);lockOwner(user);
        var value=mapper.lockCandidate(candidateId,user).orElseThrow(GeoCompetitiveService::notFound);
        requireProjectCandidate(user,value);
        if(value.version()!=request.version()||!"PENDING".equals(value.status()))throw versionConflict();
        if(mapper.decide(candidateId,user,value.version(),"IGNORED",null)!=1)throw versionConflict();
        return candidate(mapper.candidate(candidateId,user).orElseThrow());
    }

    @Transactional
    public GeoCompetitiveDtos.Confirmation confirm(AuthenticatedAccount account,long candidateId,GeoCompetitiveDtos.ConfirmRequest request) {
        long user=CatalogAccess.requireUser(account);lockOwner(user);
        var value=mapper.lockCandidate(candidateId,user).orElseThrow(GeoCompetitiveService::notFound);
        requireProjectCandidate(user,value);
        if(value.version()!=request.version()||!"PENDING".equals(value.status()))throw versionConflict();
        if(catalog==null||geo==null)throw unavailable();
        var topic=geo.get(account,value.topicId());
        if(topic.version()!=request.topicVersion())throw versionConflict();
        var projected=names(value.name(),value.entityKind(),evidence(value.evidenceJson()),List.of());
        if(projected==null)throw invalid();
        long competitorId;
        if(request.existingCompetitorId()!=null) {
            competitorId=positiveId(request.existingCompetitorId());
            var existing=catalog.requireCompetitor(user,competitorId);
            if(existing.spaceId()!=value.spaceId())throw notFound();
            if(existing.status()!=CompetitorStatus.ACTIVE)throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "停用的竞品不能关联，请先在业务资料中启用",Map.of("reason","INACTIVE_COMPETITOR"));
        } else {
            for(String key:lookupNames(value,projected)) {
                var existing=mapper.existingCompetitor(user,value.spaceId(),key);
                if(existing.isPresent())throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT,
                        "竞品名称或别名已存在，请选择关联已有竞品",Map.of("existingCompetitorId",Long.toString(existing.get())));
            }
            competitorId=Long.parseLong(catalog.create(account,value.spaceId(),new CompetitorDtos.CreateRequest(
                    projected.name(),request.type(),null,CompetitorStatus.ACTIVE)).id());
        }
        if(Boolean.TRUE.equals(request.addToTopic())&&!topic.competitorIds().contains(Long.toString(competitorId))) {
            List<String> selected=new ArrayList<>(topic.competitorIds());selected.add(Long.toString(competitorId));
            topic=geo.replaceCompetitors(account,value.topicId(),new GeoDtos.ReplaceCompetitorsRequest(topic.version(),selected));
        }
        if(mapper.decide(candidateId,user,value.version(),"CONFIRMED",competitorId)!=1)throw versionConflict();
        return new GeoCompetitiveDtos.Confirmation(candidate(mapper.candidate(candidateId,user).orElseThrow()),
                topic.version(),topic.competitorSetVersion(),false);
    }

    /** Called inside the existing report completion transaction. Optional discovery cannot invalidate a valid report. */
    public void ingest(GeoTaskRecords.Report report,GeoTaskRecords.Run run,List<GeoTaskRecords.Observation> observations,
            GeoTaskDtos.SynthesisInput synthesis) {
        if(mapper==null||!supported(synthesis)||report.runId()!=run.id()||report.userId()!=run.userId()||report.topicId()!=run.topicId())return;
        var existing=mapper.lock(report.id(),report.userId()).orElse(null);
        if(existing!=null&&(ACTIVE.contains(existing.status())||"SUCCEEDED".equals(existing.status())))return;
        ObjectNode result=extract(report,run,observations,synthesis);
        if(existing==null)mapper.insertAutomatic(ids.nextId(),report.id(),report.userId(),hash(read(run.contextSnapshotJson())),result.toString(),now());
        else mapper.publishAutomatic(existing.id(),result.toString(),now());
    }

    @Transactional
    public GeoCompetitiveDtos.Insights analyze(AuthenticatedAccount account,long reportId) {
        long user=CatalogAccess.requireUser(account);lockOwner(user);
        reports.lockReport(reportId,user).orElseThrow(GeoCompetitiveService::notFound);
        var report=report(reportId,user);var run=run(report);var rows=reports.findObservationPage(run.id(),0,210);
        var state=mapper.lock(reportId,user).orElse(null);
        if(state!=null&&("SUCCEEDED".equals(state.status())||ACTIVE.contains(state.status())))return response(report,run,rows,state,"DISCOVERY");
        String reason=eligibility(report,run,rows);
        if(reason!=null)throw new BusinessException(ErrorCode.VALIDATION_FAILED,"当前报告不能分析竞品",Map.of("reason",reason));
        if(!available())throw unavailable();
        if(!interpretations.executionEligible(user,report.taskId()))throw conflict();
        if(state!=null&&state.attempts()>=2&&!capacityFailure(state))throw limited("ATTEMPT_LIMIT_REACHED");
        if(capacityFailure(state)&&state.availableAt().isAfter(now()))throw limited("CAPACITY_COOLDOWN");
        if(hasActive(user)||interpretations.countActive(user)>0)throw limited("USER_ANALYSIS_ACTIVE");
        ObjectNode packet=evidenceBuilder.evidence(run,rows);String hash=hash(packet);
        if(state!=null&&!state.inputHash().equals(hash))throw conflict();
        int changed=state==null?mapper.enqueue(ids.nextId(),report.id(),user,hash,observability.currentTraceparent(),now())
                :mapper.requeue(state.id(),observability.currentTraceparent(),now());
        if(changed!=1)throw conflict();
        return response(report,run,rows,mapper.find(reportId,user).orElseThrow(),"DISCOVERY");
    }

    boolean hasActive(long user) {return mapper!=null&&mapper.countActive(user)>0;}

    @Transactional
    public Optional<GeoCompetitiveRecords.Claim> claimNext() {
        if(!available())return Optional.empty();
        var next=mapper.lockNext(now()).orElse(null);if(next==null)return Optional.empty();
        var report=reports.findReport(next.reportId(),next.userId()).orElse(null);
        var run=report==null?null:reports.findRun(report.runId(),next.userId()).orElse(null);
        var rows=run==null?List.<GeoTaskRecords.Observation>of():reports.findObservationPage(run.id(),0,210);
        if(report==null||run==null||eligibility(report,run,rows)!=null||!interpretations.executionEligible(next.userId(),report.taskId())) {
            mapper.reject(next.id(),"REPORT_UNAVAILABLE",now());return Optional.empty();
        }
        ObjectNode packet;
        try {packet=evidenceBuilder.evidence(run,rows);}catch(BusinessException invalid){mapper.reject(next.id(),"INPUT_CHANGED",now());return Optional.empty();}
        if(!next.inputHash().equals(hash(packet))){mapper.reject(next.id(),"INPUT_CHANGED",now());return Optional.empty();}
        if(mapper.claim(next.id(),UUID.randomUUID(),now())!=1)return Optional.empty();
        var claimed=mapper.find(next.reportId(),next.userId()).orElseThrow();
        packet.put("jobId",Long.toString(claimed.id()));packet.put("attempt",claimed.attempts());packet.put("inputHash",claimed.inputHash());
        packet.put("deadlineAt",claimed.deadlineAt().toString());packet.put("maxProviderCalls",2);
        return Optional.of(new GeoCompetitiveRecords.Claim(claimed,packet,report.taskId(),run.id()));
    }

    @Transactional
    public void expireOne(){if(mapper!=null)mapper.lockExpired(now()).ifPresent(v->mapper.reject(v.id(),"LEASE_EXPIRED",now()));}
    @Transactional
    public void capacityRejected(GeoCompetitiveRecords.Claim claim){var w=claim.work();mapper.capacity(w.id(),w.attempts(),w.claimToken(),now());}
    @Transactional
    public void failed(GeoCompetitiveRecords.Claim claim,String reason){
        String safe=Set.of("TIMEOUT","INVALID_RESULT","INPUT_CHANGED","REPORT_UNAVAILABLE").contains(reason)?reason:"PROVIDER_UNAVAILABLE";
        var w=claim.work();mapper.finish(w.id(),w.attempts(),w.claimToken(),"FAILED",safe,null,null,now());
    }

    @Transactional
    public String completed(GeoCompetitiveRecords.Claim claim,JsonNode response) {
        var expected=claim.work();
        if(reports.lockReport(expected.reportId(),expected.userId()).isEmpty())return "REPORT_UNAVAILABLE";
        var current=mapper.lock(expected.reportId(),expected.userId()).orElse(null);
        if(current==null||!"RUNNING".equals(current.status())||!Objects.equals(current.claimToken(),expected.claimToken())
                ||current.attempts()!=expected.attempts()||!current.leaseExpiresAt().isAfter(now()))return "LEASE_EXPIRED";
        var report=report(current.reportId(),current.userId());var run=run(report);var rows=reports.findObservationPage(run.id(),0,210);
        String error=eligibility(report,run,rows)!=null||!interpretations.executionEligible(report.userId(),report.taskId())?"REPORT_UNAVAILABLE":null;
        if(error==null&&!current.inputHash().equals(hash(evidenceBuilder.evidence(run,rows))))error="INPUT_CHANGED";
        GeoTaskDtos.SynthesisInput synthesis=null;GeoTaskDtos.SynthesisUsageInput usage=null;
        if(error==null)try {
            if(response==null||!response.isObject()||response.size()!=5||!Long.toString(current.id()).equals(response.path("jobId").asText())
                    ||!response.path("attempt").isIntegralNumber()||current.attempts()!=response.path("attempt").asInt()
                    ||!current.inputHash().equals(response.path("inputHash").asText()))throw new IllegalArgumentException();
            synthesis=json.treeToValue(response.path("synthesis"),GeoTaskDtos.SynthesisInput.class);
            usage=json.treeToValue(response.path("synthesisUsage"),GeoTaskDtos.SynthesisUsageInput.class);
            if(!supported(synthesis)||!validator.validate(synthesis).isEmpty()||usage==null||!validator.validate(usage).isEmpty()
                    ||!"DEEPSEEK".equals(usage.provider())||!Set.of("deepseek-flash","deepseek-v4-flash").contains(usage.model())
                    ||usage.providerCallCount()<1||usage.providerCallCount()>2)throw new IllegalArgumentException();
        }catch(JsonProcessingException|IllegalArgumentException invalid){error="INVALID_RESULT";}
        if(error!=null){failed(claim,error);return error;}
        ObjectNode result=extract(report,run,rows,synthesis);
        if(mapper.finish(current.id(),current.attempts(),current.claimToken(),"SUCCEEDED",null,result.toString(),write(usage),now())!=1)throw conflict();
        return null;
    }

    private GeoCompetitiveDtos.Insights response(GeoTaskRecords.Report report,GeoTaskRecords.Run run,
            List<GeoTaskRecords.Observation> rows,GeoCompetitiveRecords.Insight state,String scope) {
        JsonNode frozen=state!=null&&"SUCCEEDED".equals(state.status())?read(state.resultJson()):json.createObjectNode();
        List<Identity> identities=baseIdentities(run);
        List<GeoCompetitiveDtos.Candidate> candidates=new ArrayList<>();
        List<Identity> discovered=new ArrayList<>();
        List<String> own=ownTerms(read(run.contextSnapshotJson()));
        List<Identity> saved=new ArrayList<>();Map<String,Set<String>> englishByChinese=new HashMap<>();
        for(JsonNode node:frozen.path("entities")) {
            Identity identity=identity(node);
            if(identity!=null) {
                saved.add(identity);var projected=names(identity.name(),identity.kind(),identity.evidence(),own);
                if(projected!=null&&projected.terms().size()==2)englishByChinese.computeIfAbsent(projected.name(),key->new HashSet<>())
                        .add(GeoVisibilityProjection.normalize(projected.terms().get(1)));
            }
        }
        for(Identity identity:saved) {
            var projected=names(identity.name(),identity.kind(),identity.evidence(),own);
            if(projected==null)continue;
            if(projected.terms().size()==2&&englishByChinese.get(projected.name()).size()>1)
                projected=new Names(identity.name(),List.of(identity.name()));
            discovered.add(new Identity(identity.candidateId(),identity.competitorId(),projected.name(),identity.kind(),identity.role(),projected.terms(),identity.evidence()));
        }
        Map<String,GeoCompetitiveDtos.Candidate> current=new HashMap<>();
        if(!discovered.isEmpty())for(var value:candidateDtos(mapper.reportCandidates(report.userId(),discovered.stream().map(i->Long.parseLong(i.candidateId())).toList())))current.put(value.id(),value);
        // Scope belongs to the frozen report, not today's decisions. Ignoring a candidate must not
        // move its name-led question into discovery and inflate other entities' natural visibility.
        List<String> scopeTerms=new ArrayList<>(own);
        identities.forEach(i->scopeTerms.addAll(i.terms()));discovered.forEach(i->scopeTerms.addAll(i.terms()));
        for(Identity identity:discovered) {
            var c=current.get(identity.candidateId());
            if(c==null||"IGNORED".equals(c.status()))continue;
            List<Identity> configured=identities.stream().filter(i->"CONFIGURED".equals(i.role())).filter(i->i.terms().stream()
                    .anyMatch(t->identity.terms().stream().anyMatch(term->GeoVisibilityProjection.normalize(t).equals(GeoVisibilityProjection.normalize(term))))).toList();
            if(configured.isEmpty())identities.add(identity);
            else if(configured.size()==1&&"BRAND".equals(identity.kind())&&identity.terms().size()==2) {
                // Reuse this report's explicit pair on a single matching frozen configured identity;
                // otherwise deduplication would silently restore the old English-only undercount.
                var prior=configured.getFirst();var terms=new LinkedHashSet<>(prior.terms());terms.addAll(identity.terms());
                identities.set(identities.indexOf(prior),new Identity(prior.candidateId(),prior.competitorId(),prior.name(),prior.kind(),prior.role(),
                        List.copyOf(terms),identity.evidence()));
            }
            candidates.add(new GeoCompetitiveDtos.Candidate(c.id(),c.topicId(),identity.name(),identity.kind(),
                    c.status(),c.version(),c.competitorId(),c.existingCompetitorId(),c.firstReportId(),c.lastReportId(),c.firstSeenAt(),c.lastSeenAt(),identity.evidence(),suggestions(identity.evidence())));}
        List<GeoCompetitiveDtos.Platform> platforms=platforms(report,run,rows,scope,scopeTerms,identities,candidates);
        String status=state==null?"NOT_ANALYZED":state.status();int attempts=state==null?0:state.attempts();
        String reason=eligibility(report,run,rows);
        if(reason==null&&!available()&&!"SUCCEEDED".equals(status))reason="SERVICE_UNAVAILABLE";
        if(reason==null&&capacityFailure(state)&&state.availableAt().isAfter(now()))reason="CAPACITY_COOLDOWN";
        if(reason==null&&attempts>=2&&!ACTIVE.contains(status)&&!"SUCCEEDED".equals(status)&&!capacityFailure(state))reason="ATTEMPT_LIMIT_REACHED";
        if(reason==null&&Set.of("NOT_ANALYZED","FAILED").contains(status)&&(hasActive(report.userId())||interpretations.countActive(report.userId())>0))reason="USER_ANALYSIS_ACTIVE";
        boolean retry=reason==null&&Set.of("NOT_ANALYZED","FAILED").contains(status);
        if(reason==null&&state!=null)reason=state.lastErrorCode();
        if(reason==null&&frozen.path("rejectedCandidates").asInt()>0)reason="CANDIDATES_REJECTED";
        return new GeoCompetitiveDtos.Insights(Long.toString(report.id()),Long.toString(report.topicId()),status,retry,attempts,2,reason,scope,METHOD,
                frozen.path("evidenceTruncated").asBoolean(false),platforms,List.copyOf(candidates));
    }

    private ObjectNode extract(GeoTaskRecords.Report report,GeoTaskRecords.Run run,List<GeoTaskRecords.Observation> rows,GeoTaskDtos.SynthesisInput synthesis) {
        ObjectNode result=json.createObjectNode();var entities=result.putArray("entities");
        Set<String> seen=new HashSet<>();int rejected=0;
        Map<String,GeoTaskRecords.Observation> units=validUnits(run,rows);List<String> own=ownTerms(read(run.contextSnapshotJson()));
        List<JsonNode> inputs=synthesis.competitorCandidates()==null?List.of():synthesis.competitorCandidates();
        for(JsonNode node:inputs.stream().limit(6).toList()) {
            String name=node==null?"":node.path("name").asText("").strip();String kind=node==null?"":node.path("entityKind").asText("");
            String norm=GeoVisibilityProjection.normalize(name);
            if(!acceptableName(name,own)||!Set.of("BRAND","PRODUCT").contains(kind)||seen.contains(norm)){rejected++;continue;}
            List<GeoCompetitiveDtos.Evidence> evidence=new ArrayList<>();
            JsonNode proposed=node.path("evidence");
            if(proposed.isArray())for(JsonNode item:proposed){
                if(evidence.size()>=2)break;String unit=item.path("unitKey").asText("");String quote=item.path("quote").asText("").strip();String signal=item.path("signal").asText("");
                var row=units.get(unit);
                if(row==null||quote.isBlank()||quote.length()>240||!SIGNALS.contains(signal))continue;
                String narrative=narrative(row.answerText());String visibleQuote=GeoVisibilityProjection.visibleAnswerText(quote);
                if(!visibleQuote.isBlank()&&GeoVisibilityProjection.normalize(narrative).contains(GeoVisibilityProjection.normalize(visibleQuote))
                        &&GeoVisibilityProjection.containsName(visibleQuote,List.of(name)))
                    evidence.add(new GeoCompetitiveDtos.Evidence(Long.toString(report.id()),unit,quote,signal));
            }
            if(evidence.isEmpty()){rejected++;continue;}
            var projected=names(name,kind,evidence,own);
            if(projected==null||!seen.add(GeoVisibilityProjection.normalize(projected.name()))){rejected++;continue;}
            GeoCompetitiveRecords.Candidate prior=null;
            if("BRAND".equals(kind)&&projected.name().matches("[一-鿿]{2,30}")) {
                var compatible=mapper.compatibleBrandCandidates(report.userId(),run.topicId(),GeoVisibilityProjection.normalize(projected.name()));
                // Multiple historical spellings are not authority to merge candidates or revive a tombstone.
                if(compatible.size()>1){rejected++;continue;}
                if(!compatible.isEmpty()) {
                    prior=compatible.getFirst();var previous=names(prior.name(),prior.entityKind(),evidence(prior.evidenceJson()),own);
                    if(previous==null||!GeoVisibilityProjection.normalize(previous.name()).equals(GeoVisibilityProjection.normalize(projected.name()))
                            ||(previous.terms().size()==2&&projected.terms().size()==2
                            &&!GeoVisibilityProjection.normalize(previous.terms().get(1)).equals(GeoVisibilityProjection.normalize(projected.terms().get(1))))) {rejected++;continue;}
                }
            }
            String savedName=prior==null?name:prior.name();String savedNorm=prior==null?GeoVisibilityProjection.normalize(projected.name()):prior.nameNormalized();
            // Preserve the original bilingual proof on an old full-name row when a later report uses only Chinese.
            String savedEvidence=prior!=null&&!prior.name().equals(projected.name())?prior.evidenceJson():write(evidence);
            var input=new GeoCompetitiveRecords.Candidate(ids.nextId(),report.userId(),run.spaceId(),run.topicId(),savedName,savedNorm,kind,"PENDING",1,null,
                    report.id(),report.id(),savedEvidence,report.generatedAt(),report.generatedAt());
            // The SQL conflict guard rejects a concurrent different spelling/kind before it can overwrite proof.
            if(mapper.upsertCandidate(input)!=1){rejected++;continue;}
            var candidate=mapper.findName(report.userId(),run.topicId(),savedNorm).orElseThrow();
            entities.add(json.valueToTree(new Identity(Long.toString(candidate.id()),null,name,kind,"DISCOVERED",projected.terms(),List.copyOf(evidence))));
        }
        // Python projects every synthesis answer to a 900-character head plus 300-character tail.
        // Counts below still use the full saved answer, never that discovery projection.
        result.put("rejectedCandidates",rejected);result.put("evidenceTruncated",inputs.size()>6||rejected>0
                ||rows.stream().filter(GeoCompetitiveService::validAnswer).anyMatch(r->r.answerText().codePointCount(0,r.answerText().length())>1200));
        return result;
    }

    /** Only an explicit, evidenced Han(ASCII-word) BRAND pair; never inferred translations or product variants. */
    static Names names(String name,String kind,List<GeoCompetitiveDtos.Evidence> evidence,List<String> own) {
        Names unchanged=new Names(name,List.of(name));
        if(!"BRAND".equals(kind))return unchanged;
        String normalized=Normalizer.normalize(name,Normalizer.Form.NFKC).strip();
        var direct=BILINGUAL_BRAND.matcher(normalized);
        if(!direct.matches())return unchanged;
        String chinese=direct.group(1),english=direct.group(2);
        if(!acceptableName(chinese,own)||!acceptableName(english,own))return null;
        if(BRAND_QUALIFIERS.contains(english.toLowerCase(Locale.ROOT)))return unchanged;
        Set<String> explicit=new HashSet<>();var explicitPair=Pattern.compile(Pattern.quote(chinese)+"\\(([A-Za-z]{2,40})\\)");
        for(var item:evidence){var pairs=explicitPair.matcher(Normalizer.normalize(GeoVisibilityProjection.visibleAnswerText(item.quote()),Normalizer.Form.NFKC));
            while(pairs.find())explicit.add(pairs.group(1).toLowerCase(Locale.ROOT));}
        if(explicit.size()!=1)return unchanged;
        String pair=GeoVisibilityProjection.normalize(chinese+"("+english+")");
        if(evidence.stream().noneMatch(e->GeoVisibilityProjection.normalize(GeoVisibilityProjection.visibleAnswerText(e.quote())).contains(pair)))return unchanged;
        return new Names(chinese,List.of(chinese,english));
    }

    private static List<String> lookupNames(GeoCompetitiveRecords.Candidate value,Names projected) {
        var result=new LinkedHashSet<String>();projected.terms().forEach(t->result.add(GeoVisibilityProjection.normalize(t)));
        result.add(GeoVisibilityProjection.normalize(value.name()));return List.copyOf(result);
    }

    static boolean acceptableName(String name,List<String> own) {
        String norm=GeoVisibilityProjection.normalize(name);
        return GeoVisibilityProjection.identityName(name)&&!NON_ENTITIES.contains(norm)&&!norm.contains("://")&&!norm.contains("www.")
                &&NON_ENTITY_SUFFIXES.stream().noneMatch(norm::endsWith)&&!norm.matches("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}")
                &&!name.matches(".*[\\r\\n<>\\[\\]].*")&&!GeoVisibilityProjection.containsName(name,own);
    }
    static String narrative(String answer) {
        String body=answer.replaceFirst("(?im)^\\s*(?:#{1,6}\\s*)?(?:\\*{1,2})?(?:参考资料|参考文献|引用来源|来源|资料来源|sources|references)(?:\\*{1,2})?\\s*[:：]?[^\\r\\n]*(?:\\r?\\n|$)[\\s\\S]*$","");
        body=body.replaceAll("(?m)^\\s*(?:[-*]|[0-9]+[.)、])?\\s*\\[[^\\]]+\\]\\(https?://[^\\r\\n]*\\)\\s*$","");
        return GeoVisibilityProjection.visibleAnswerText(body);
    }
    private List<GeoCompetitiveDtos.Platform> platforms(GeoTaskRecords.Report report,GeoTaskRecords.Run run,List<GeoTaskRecords.Observation> rows,
            String scope,List<String> scopeTerms,List<Identity> identities,List<GeoCompetitiveDtos.Candidate> candidates) {
        JsonNode snapshot=read(run.contextSnapshotJson());Map<String,GeoTaskRecords.Observation> units=validUnits(run,rows);
        Map<String,String> questions=new LinkedHashMap<>();
        for(JsonNode q:snapshot.path("authorizedContext").path("questions")) {
            if(scopeTerms.isEmpty())continue;
            boolean validation=GeoVisibilityProjection.containsName(q.path("text").asText(""),scopeTerms);
            if("BRAND_VALIDATION".equals(scope)==validation)questions.put(q.path("id").asText(),q.path("text").asText());
        }
        int rounds="DEEP".equals(snapshot.path("parameters").path("sampleMode").asText())?3:1;
        Map<String,String> status=new HashMap<>();for(var c:candidates)status.put(c.id(),c.status());
        List<GeoCompetitiveDtos.Platform> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(JsonNode platform:snapshot.path("parameters").path("platformCodes")) {
            String code=platform.asText();if(!seen.add(code))continue;
            List<GeoTaskRecords.Observation> valid=new ArrayList<>();int failed=0;Set<String> expected=new LinkedHashSet<>();
            for(String q:questions.keySet())for(int n=1;n<=rounds;n++)expected.add(q+":"+code+":"+n);
            for(var row:rows)if(expected.contains(row.unitKey())&&Objects.equals(questions.get(Long.toString(row.questionId())),row.questionTextSnapshot())&&"FAILED".equals(row.status()))failed++;
            for(var row:units.values())if(expected.contains(row.unitKey()))valid.add(row);
            List<GeoCompetitiveDtos.Entity> entities=new ArrayList<>();
            for(Identity identity:identities) {
                int count=0;List<GeoCompetitiveDtos.Evidence> evidence=new ArrayList<>();
                for(var row:valid)if(GeoVisibilityProjection.containsName(row.answerText(),identity.terms())) {
                    count++;if(evidence.size()<2){var saved=identity.evidence().stream().filter(e->e.unitKey().equals(row.unitKey())).findFirst().orElse(null);
                        evidence.add(saved==null?new GeoCompetitiveDtos.Evidence(Long.toString(report.id()),row.unitKey(),excerpt(GeoVisibilityProjection.visibleAnswerText(row.answerText()),identity.terms()),"MENTION"):saved);}
                }
                boolean assessable=!valid.isEmpty()&&!identity.terms().isEmpty();
                entities.add(new GeoCompetitiveDtos.Entity(identity.candidateId(),identity.competitorId(),identity.name(),identity.kind(),identity.role(),
                        status.get(identity.candidateId()),assessable?count:null,assessable?Math.round(10000.0*count/valid.size())/100.0:null,List.copyOf(evidence)));
            }
            result.add(new GeoCompetitiveDtos.Platform(code,expected.size(),valid.size(),failed,Math.max(0,expected.size()-valid.size()-failed),List.copyOf(entities)));
        }
        return List.copyOf(result);
    }
    private Map<String,GeoTaskRecords.Observation> validUnits(GeoTaskRecords.Run run,List<GeoTaskRecords.Observation> rows) {
        JsonNode snapshot=read(run.contextSnapshotJson());Map<String,String> questions=new HashMap<>();Set<String> platforms=new HashSet<>();
        for(JsonNode q:snapshot.path("authorizedContext").path("questions"))questions.put(q.path("id").asText(),q.path("text").asText());
        for(JsonNode p:snapshot.path("parameters").path("platformCodes"))platforms.add(p.asText());
        int rounds="DEEP".equals(snapshot.path("parameters").path("sampleMode").asText())?3:1;Map<String,GeoTaskRecords.Observation> result=new LinkedHashMap<>();
        for(var row:rows)if(validAnswer(row)&&row.runId()==run.id()&&row.userId()==run.userId()&&platforms.contains(row.platformCode())
                &&row.sampleNo()>=1&&row.sampleNo()<=rounds&&Objects.equals(questions.get(Long.toString(row.questionId())),row.questionTextSnapshot())
                &&row.unitKey().equals(row.questionId()+":"+row.platformCode()+":"+row.sampleNo()))result.putIfAbsent(row.unitKey(),row);
        return result;
    }
    private List<Identity> baseIdentities(GeoTaskRecords.Run run) {
        JsonNode snapshot=read(run.contextSnapshotJson());var target=GeoVisibilityProjection.target(run,snapshot);List<Identity> result=new ArrayList<>();
        if(target.value()!=null)result.add(new Identity(null,null,target.value().name(),target.value().kind(),"OWN",target.terms(),List.of()));
        for(JsonNode c:snapshot.path("authorizedContext").path("competitors")) {
            if(result.size()>=21)break;String name=c.path("name").asText("");if(!GeoVisibilityProjection.identityName(name))continue;
            List<String> terms=new ArrayList<>(List.of(name));for(JsonNode a:c.path("aliases"))if(terms.size()<51&&a.isTextual()&&GeoVisibilityProjection.identityName(a.asText()))terms.add(a.asText());
            result.add(new Identity(null,c.path("id").asText(),name,"UNKNOWN","CONFIGURED",List.copyOf(terms),List.of()));
        }
        return result;
    }
    private List<String> ownTerms(JsonNode snapshot) {
        List<String> result=new ArrayList<>();JsonNode catalog=snapshot.path("catalogSnapshot");
        for(JsonNode identity:List.of(catalog.path("space").path("brand"),catalog.path("product"))) {
            String category=GeoVisibilityProjection.normalize(identity.path("category").asText(""));
            String name=identity.path("name").asText("");if(GeoVisibilityProjection.identityName(name)&&!GeoVisibilityProjection.normalize(name).equals(category))result.add(name);
            for(JsonNode alias:identity.path("aliases"))if(result.size()<102&&alias.isTextual()&&GeoVisibilityProjection.identityName(alias.asText())
                    &&!GeoVisibilityProjection.normalize(alias.asText()).equals(category))result.add(alias.asText());
        }
        return List.copyOf(result);
    }
    private GeoCompetitiveDtos.Candidate candidate(GeoCompetitiveRecords.Candidate value) {
        return candidateDtos(List.of(value)).getFirst();
    }
    private List<GeoCompetitiveDtos.Candidate> candidateDtos(List<GeoCompetitiveRecords.Candidate> values) {
        if(values.isEmpty())return List.of();
        Map<String,Long> matches=new HashMap<>();var first=values.getFirst();
        Map<Long,Names> projected=new HashMap<>();var keys=new LinkedHashSet<String>();
        for(var value:values){var n=names(value.name(),value.entityKind(),evidence(value.evidenceJson()),List.of());
            if(n==null)n=new Names(value.name(),List.of(value.name()));projected.put(value.id(),n);keys.addAll(lookupNames(value,n));}
        List<String> query=List.copyOf(keys);
        // A page has at most 100 candidates / 300 names; retain bounded batches and the mapper's 200-row cap.
        for(int start=0;start<query.size();start+=100)for(var match:mapper.existingCompetitors(first.userId(),first.spaceId(),query.subList(start,Math.min(start+100,query.size()))))matches.putIfAbsent(match.nameNormalized(),match.competitorId());
        return values.stream().map(value->{var n=projected.get(value.id());Long existing=lookupNames(value,n).stream().map(matches::get).filter(Objects::nonNull).findFirst().orElse(null);
            return candidate(value,existing,n);}).toList();
    }
    private GeoCompetitiveDtos.Candidate candidate(GeoCompetitiveRecords.Candidate value,Long existing,Names projected) {
        List<GeoCompetitiveDtos.Evidence> evidence=evidence(value.evidenceJson());
        return new GeoCompetitiveDtos.Candidate(Long.toString(value.id()),Long.toString(value.topicId()),projected.name(),value.entityKind(),value.status(),value.version(),
                string(value.competitorId()),string(existing),Long.toString(value.firstReportId()),Long.toString(value.lastReportId()),value.firstSeenAt(),value.lastSeenAt(),evidence,suggestions(evidence));
    }
    private static List<String> suggestions(List<GeoCompetitiveDtos.Evidence> evidence) {
        List<String> result=new ArrayList<>();for(var e:evidence)if("POSITIVE_ATTRIBUTE".equals(e.signal()))result.add("待核查线索：“"+e.quote()+"”。请阅读原回答及其引用，核实自家是否具备对应卖点或能力，仅在可举证时完善产品说明；不表示这些做法导致竞品GEO表现更好。");
        else if("NEGATIVE_ATTRIBUTE".equals(e.signal()))result.add("回答提到的顾虑（待核验）：“"+e.quote()+"”。检查自家说明能否用可靠证据回应同类问题；先核实原回答及其引用，不直接沿用对竞品的评价或推断因果。");
        return result.stream().distinct().limit(2).toList();
    }
    private static String excerpt(String answer,List<String> terms) {
        int index=0;String normalized=GeoVisibilityProjection.normalize(answer);
        for(String term:terms){int found=normalized.indexOf(GeoVisibilityProjection.normalize(term));if(found>=0){index=found;break;}}
        int start=Math.max(0,Math.min(answer.length(),index)-60);
        if(start>0&&Character.isLowSurrogate(answer.charAt(start)))start--;
        int end=Math.min(answer.length(),start+240);
        if(end<answer.length()&&end>0&&Character.isHighSurrogate(answer.charAt(end-1)))end--;
        return answer.substring(start,end);
    }
    private List<GeoCompetitiveDtos.Evidence> evidence(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(JsonProcessingException invalid){throw conflict();}}
    private Identity identity(JsonNode node){try{return json.treeToValue(node,Identity.class);}catch(JsonProcessingException invalid){return null;}}
    private static boolean supported(GeoTaskDtos.SynthesisInput s){return s!=null&&"geo-synthesis-v1".equals(s.methodVersion())&&DISCOVERY.equals(s.competitorDiscoveryVersion())&&s.competitorCandidates()!=null;}
    private static boolean validAnswer(GeoTaskRecords.Observation r){return "SUCCEEDED".equals(r.status())&&r.answerText()!=null&&!r.answerText().isBlank();}
    private static boolean capacityFailure(GeoCompetitiveRecords.Insight w){return w!=null&&"FAILED".equals(w.status())&&"CAPACITY_FULL".equals(w.lastErrorCode());}
    private static String eligibility(GeoTaskRecords.Report r,GeoTaskRecords.Run run,List<GeoTaskRecords.Observation> rows){
        if(r.archivedAt()!=null)return "ARCHIVED";if(!Set.of("SUCCEEDED","PARTIAL").contains(r.status())||!Set.of("SUCCEEDED","PARTIAL").contains(run.status()))return "NOT_TERMINAL";
        return rows.stream().anyMatch(GeoCompetitiveService::validAnswer)?null:"NO_VALID_ANSWERS";
    }
    private boolean available(){return mapper!=null&&reports!=null&&interpretations!=null&&properties.isGeoInterpretationEnabled()&&clientAvailable;}
    private void requireStorage(){if(mapper==null||reports==null||topics==null)throw unavailable();}
    private void requireProjectCandidate(long user,GeoCompetitiveRecords.Candidate value){
        if(spaces!=null)spaces.requireProjectSpace(user,value.spaceId());
        else topics.findTopic(value.topicId(),user).orElseThrow(GeoCompetitiveService::notFound);
    }
    private void lockOwner(long user){requireStorage();reports.lockOwner(user).orElseThrow(GeoCompetitiveService::notFound);}
    private GeoTaskRecords.Report report(long id,long user){return reports.findReport(id,user).orElseThrow(GeoCompetitiveService::notFound);}
    private GeoTaskRecords.Run run(GeoTaskRecords.Report r){return reports.findRun(r.runId(),r.userId()).orElseThrow(GeoCompetitiveService::notFound);}
    private static void scope(String scope){if(!Set.of("DISCOVERY","BRAND_VALIDATION").contains(scope))throw invalid();}
    private static long positiveId(String value){try{long id=Long.parseLong(value);if(id>0)return id;}catch(NumberFormatException ignored){}throw invalid();}
    private static String string(Long id){return id==null?null:Long.toString(id);}
    private OffsetDateTime now(){return OffsetDateTime.now(clock);}
    private JsonNode read(String value){try{return json.readTree(value==null?"{}":value);}catch(JsonProcessingException invalid){throw conflict();}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException invalid){throw conflict();}}
    private static String hash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
    private static BusinessException notFound(){return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);}
    private static BusinessException conflict(){return new BusinessException(ErrorCode.TASK_STATE_CONFLICT);}
    private static BusinessException versionConflict(){return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);}
    private static BusinessException invalid(){return new BusinessException(ErrorCode.VALIDATION_FAILED);}
    private static BusinessException unavailable(){return new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE);}
    private static BusinessException limited(String reason){return new BusinessException(ErrorCode.RATE_LIMITED,"报告分析暂不可用，请查看原因后重试",Map.of("reason",reason));}
    record Identity(String candidateId,String competitorId,String name,String kind,String role,List<String> terms,List<GeoCompetitiveDtos.Evidence> evidence) { }
    record Names(String name,List<String> terms) { }
}
