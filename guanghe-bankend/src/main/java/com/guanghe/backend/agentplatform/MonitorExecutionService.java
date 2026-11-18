package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.competitor.CompetitorMapper;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.geo.GeoMapper;
import com.guanghe.backend.geo.GeoTaskService;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskRecords;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.guanghe.backend.agentplatform.MonitorExecutionDtos.*;
import static com.guanghe.backend.agentplatform.MonitorWaitReason.*;

/** Narrow pre-authorization for one existing GEO topic. The wallet and task writer remain TaskControl. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${zhixiaohe.paid-task.enabled:false} and ${zhixiaohe.agent-platform.enabled:true}")
public class MonitorExecutionService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final MonitorExecutionMapper mapper;
    private final AgentPlatformService platform;
    private final ConversationMapper conversations;
    private final GeoMapper geo;
    private final BusinessSpaceMapper spaces;
    private final ProductMapper products;
    private final CompetitorMapper competitors;
    private final GeoTaskService tasks;
    private final PaidTaskProperties pricing;
    private final AgentPlatformProperties properties;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final Clock clock;

    public MonitorExecutionService(MonitorExecutionMapper mapper,AgentPlatformService platform,
            ConversationMapper conversations,GeoMapper geo,BusinessSpaceMapper spaces,ProductMapper products,
            CompetitorMapper competitors,GeoTaskService tasks,PaidTaskProperties pricing,
            AgentPlatformProperties properties,SnowflakeIdGenerator ids,ObjectMapper json,
            @Qualifier("billingClock") Clock clock) {
        this.mapper=mapper;this.platform=platform;this.conversations=conversations;this.geo=geo;
        this.spaces=spaces;this.products=products;this.competitors=competitors;this.tasks=tasks;
        this.pricing=pricing;this.properties=properties;this.ids=ids;this.json=json;this.clock=clock;
    }

    @Transactional(readOnly=true)
    public BudgetPolicyView budget(AuthenticatedAccount account) { return budgetView(mapper.policy(CatalogAccess.requireUser(account)).orElse(null)); }

    @Transactional
    public BudgetPolicyView putBudget(AuthenticatedAccount account,BudgetPolicyPut request) {
        long user=CatalogAccess.requireUser(account);
        if (request.maxPointsPerDay()<request.maxPointsPerAction()) throw invalid();
        LocalDate floor=properties.getMonitorBudgetEarliestTrustedDate();
        if (floor==null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        OffsetDateTime now=accountingNow();
        if (request.version()==0) {
            LocalDate effective=now.atZoneSameInstant(SHANGHAI).toLocalDate();
            if (effective.isBefore(floor)) effective=floor;
            if (mapper.insertPolicy(new MonitorExecutionRecords.Policy(user,1,request.enabled(),
                    request.maxPointsPerAction(),request.maxPointsPerDay(),effective,now,now))!=1) throw conflict();
        } else if (mapper.updatePolicy(user,request.version(),request.enabled(),request.maxPointsPerAction(),request.maxPointsPerDay(),now)!=1) throw conflict();
        return budgetView(mapper.policy(user).orElseThrow());
    }

    @Transactional
    public MonitorActionPreview preview(AuthenticatedAccount account,long conversation,MonitorRangeInput input) {
        long user=CatalogAccess.requireUser(account);ownedConversation(user,conversation);
        try {
            if(!properties.isAutomaticMonitorEnabled()) throw refuse(AUTOMATIC_MONITOR_DISABLED);
            Prepared prepared=prepare(user,identity(user,conversation),input);
            return new MonitorActionPreview("MONITOR_RUN","READY",null,prepared.range(),
                    Long.toString(pricing.pricingVersionId("GEO")),pricing.getGeoTestUnitPoints(),
                    prepared.draft().quotedUnits(),tasks.quote(prepared.draft()));
        } catch (Refusal refused) { return new MonitorActionPreview("MONITOR_RUN","WAIT_INPUT",refused.reason.name(),null,null,null,null,null); }
    }

    @Transactional(readOnly=true)
    public List<MonitorAuthorizationView> grants(AuthenticatedAccount account,long conversation) {
        long user=CatalogAccess.requireUser(account);ownedConversation(user,conversation);
        return mapper.activeGrants(user,conversation).stream().map(this::grantView).toList();
    }

    @Transactional
    public MonitorAuthorizationView createGrant(AuthenticatedAccount account,long conversation,MonitorAuthorizationCreate request) {
        long user=CatalogAccess.requireUser(account);ownedConversation(user,conversation);
        var identity=identity(user,conversation);
        requireRange(request.range(),prepare(user,identity,request.range().input()).range());
        OffsetDateTime now=accountingNow();requireFuture(request.expiresAt(),now);
        var grant=new MonitorExecutionRecords.Grant(ids.nextId(),user,conversation,id(identity.installationId()),
                identity.agentKey(),identity.agentVersion(),definitionHash(identity),nullableId(identity.projectId()),
                id(request.range().topicId()),canonical(request.range()),1,"ACTIVE",request.expiresAt(),null,now,now);
        if(mapper.insertGrant(grant)!=1) throw conflict();
        return grantView(grant);
    }

    @Transactional
    public MonitorAuthorizationView updateGrant(AuthenticatedAccount account,long conversation,long grantId,MonitorAuthorizationPut request) {
        long user=CatalogAccess.requireUser(account);ownedConversation(user,conversation);
        // Installation SHARE first, then grant; settings never acquire draft/admission/points.
        var identity=identity(user,conversation);
        var grant=mapper.lockGrant(grantId,user,conversation).orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if(grant.version()!=request.version()||!"ACTIVE".equals(grant.status())) throw conflict();
        requireGrantIdentity(grant,identity);
        requireRange(request.range(),prepare(user,identity,request.range().input()).range());
        OffsetDateTime now=accountingNow();requireFuture(request.expiresAt(),now);
        if(mapper.updateGrant(grantId,user,request.version(),id(request.range().topicId()),canonical(request.range()),request.expiresAt(),now)!=1) throw conflict();
        return grantView(mapper.grant(grantId,user,conversation).orElseThrow());
    }

    @Transactional
    public MonitorAuthorizationView revokeGrant(AuthenticatedAccount account,long conversation,long grantId,VersionRequest request) {
        long user=CatalogAccess.requireUser(account);ownedConversation(user,conversation);
        var grant=mapper.lockGrant(grantId,user,conversation).orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if(grant.version()!=request.version()) throw conflict();
        if("ACTIVE".equals(grant.status())&&mapper.revokeGrant(grantId,user,request.version(),accountingNow())!=1) throw conflict();
        return grantView(mapper.grant(grantId,user,conversation).orElseThrow());
    }

    public record Selection(Long spaceId,Long productId,MonitorActionContext context,MonitorWaitReason reason) { }
    /** Already inside the account/conversation admission transaction; no point lock or charge. */
    public Selection prepareMessage(long user,long conversation,MonitorExecutionIntent intent) {
        try {
            if(!properties.isAutomaticMonitorEnabled()) throw refuse(AUTOMATIC_MONITOR_DISABLED);
            var identity=identity(user,conversation);
            var grant=mapper.shareGrant(id(intent.grantId()),user,conversation).orElseThrow(()->refuse(GRANT_NOT_FOUND));
            requireGrant(grant,intent,identity,accountingNow());
            Prepared prepared=prepare(user,identity,intent.range().input());
            requireRange(intent.range(),prepared.range());requirePrice(intent,prepared);
            var params=prepared.draft().parameters();
            return new Selection(prepared.spaceId(),prepared.productId(),new MonitorActionContext("MONITOR_RUN",
                    Long.toString(prepared.spaceId()),textId(prepared.productId()),params.deepCopy(),
                    prepared.draft().snapshot().path("authorizedContext").path("questions").deepCopy()),null);
        } catch(Refusal refused) { return new Selection(null,null,null,refused.reason); }
    }

    /** Called with existing Run/conversation locks. It stores intent, never authorizes or locks points. */
    public void registerProposal(AgentRunRecord run,TaskRecords.Draft draft,OffsetDateTime now) {
        JsonNode message=originalUser(run);
        if(!message.hasNonNull("executionIntent")) return;
        MonitorExecutionIntent intent=convert(message.path("executionIntent"),MonitorExecutionIntent.class);
        JsonNode frozen=read(run.contextSnapshotJson()).path("agentIdentity");
        if(!frozen.isObject()||!frozen.hasNonNull("installationId")) throw new IllegalStateException("Missing frozen monitor identity");
        ObjectNode identity=frozen.deepCopy();
        identity.put("definitionHash",mapper.definitionHash(identity.path("agentKey").asText(),identity.path("agentVersion").asText()));
        var ownedGrant=mapper.grant(id(intent.grantId()),run.userId(),run.conversationId()).orElse(null);
        MonitorWaitReason reason=null;
        if(message.hasNonNull("monitorPreparationReason")) reason=MonitorWaitReason.valueOf(message.path("monitorPreparationReason").asText());
        if(reason==null&&(!"GEO".equals(draft.taskType())||!"MONITOR_RUN".equals(read(draft.parametersJson()).path("mode").asText())
                ||!intent.range().inputHash().equals(draft.inputHash())||!parameters(intent.range()).equals(read(draft.parametersJson())))) reason=PROPOSAL_RANGE_MISMATCH;
        if(reason==null&&(id(intent.pricingVersionId())!=draft.pricingVersionId()||intent.quotedPoints()!=draft.quotedPoints())) reason=PRICE_CHANGED;
        if(reason==null&&ownedGrant==null) reason=GRANT_NOT_FOUND;
        mapper.insertAdmission(new MonitorExecutionRecords.Admission(ids.nextId(),run.userId(),run.conversationId(),run.id(),
                run.requestMessageId(),draft.id(),ownedGrant==null?null:ownedGrant.id(),intent.grantVersion(),canonical(identity),
                canonical(intent),tasks.snapshotHash(json.valueToTree(intent)),draft.inputHash(),draft.pricingVersionId(),draft.quotedPoints(),
                reason==null?"PENDING":"WAIT_INPUT",reason==null?null:reason.name(),null,null,null,now,now));
    }

    public record Permit(MonitorExecutionRecords.Grant grant,MonitorExecutionRecords.Policy policy) { }
    /** Called by TaskControl with admission then draft locked, before point_account. */
    public Permit validateAdmission(MonitorExecutionRecords.Admission admission,TaskRecords.Draft draft,AgentRunRecord run) {
        if(!properties.isAutomaticMonitorEnabled()) throw refuse(AUTOMATIC_MONITOR_DISABLED);
        if("STOPPED".equals(run.status())) throw refuse(RUN_STOPPED);
        if("FAILED".equals(run.status())) throw refuse(RUN_FAILED);
        if(!"COMPLETED".equals(run.status())) throw new IllegalStateException("Run is not terminal completed");
        JsonNode user=originalUser(run);
        var intent=convert(user.path("executionIntent"),MonitorExecutionIntent.class);
        if(!canonical(intent).equals(admission.intentSnapshotJson())
                &&!json.valueToTree(intent).equals(read(admission.intentSnapshotJson()))) throw refuse(PROPOSAL_RANGE_MISMATCH);
        if(!tasks.snapshotHash(json.valueToTree(intent)).equals(admission.intentHash())) throw refuse(PROPOSAL_RANGE_MISMATCH);
        var identity=identity(admission.userId(),admission.conversationId());
        ObjectNode frozen=json.valueToTree(identity);frozen.put("definitionHash",definitionHash(identity));
        if(!frozen.equals(read(admission.frozenIdentityJson()))||!json.valueToTree(identity).equals(read(run.contextSnapshotJson()).path("agentIdentity"))) throw refuse(AGENT_UNAVAILABLE);
        var grant=mapper.shareGrant(id(intent.grantId()),admission.userId(),admission.conversationId()).orElseThrow(()->refuse(GRANT_NOT_FOUND));
        requireGrant(grant,intent,identity,accountingNow());
        var policy=mapper.sharePolicy(admission.userId()).orElseThrow(()->refuse(BUDGET_NOT_CONFIGURED));
        if(!policy.enabled()) throw refuse(BUDGET_DISABLED);
        Prepared prepared=prepare(admission.userId(),identity,intent.range().input());
        requireRange(intent.range(),prepared.range());requirePrice(intent,prepared);
        if(!"WAITING_CONFIRMATION".equals(draft.status())||!"GEO".equals(draft.taskType())) throw refuse(DRAFT_EXPIRED);
        if(!draft.inputHash().equals(intent.range().inputHash())||!draft.inputHash().equals(tasks.snapshotHash(read(draft.inputSnapshotJson())))
                ||!parameters(intent.range()).equals(read(draft.parametersJson()))) throw refuse(PROPOSAL_RANGE_MISMATCH);
        if(draft.pricingVersionId()!=id(intent.pricingVersionId())||draft.quotedPoints()!=intent.quotedPoints()) throw refuse(PRICE_CHANGED);
        return new Permit(grant,policy);
    }

    /** Fresh time is supplied AFTER point_account lock. SUM is a separate READ_COMMITTED SQL. */
    public void validateBudget(Permit permit,TaskRecords.Draft draft,TaskRecords.PointAccount points,OffsetDateTime accountingAt) {
        if(!accountingAt.isBefore(permit.grant().expiresAt())) throw refuse(GRANT_EXPIRED);
        if(!accountingAt.isBefore(draft.expiresAt())) throw refuse(DRAFT_EXPIRED);
        LocalDate floor=properties.getMonitorBudgetEarliestTrustedDate();
        LocalDate day=accountingAt.atZoneSameInstant(SHANGHAI).toLocalDate();
        if(floor==null||day.isBefore(floor)||day.isBefore(permit.policy().effectiveShanghaiDate())) throw refuse(BUDGET_NOT_EFFECTIVE);
        int quote=draft.quotedPoints();
        if(quote>permit.policy().maxPointsPerAction()) throw refuse(SINGLE_ACTION_LIMIT);
        if(points==null||points.availablePoints()<quote) throw refuse(INSUFFICIENT_POINTS);
        OffsetDateTime start=day.atStartOfDay(SHANGHAI).toOffsetDateTime();
        long spent=mapper.consumedPoints(draft.userId(),start,day.plusDays(1).atStartOfDay(SHANGHAI).toOffsetDateTime());
        if(spent+(long)points.frozenPoints()+quote>permit.policy().maxPointsPerDay()) throw refuse(DAY_LIMIT);
    }

    @Transactional(readOnly=true)
    public MonitorAdmissionView admission(AuthenticatedAccount account,long conversation,UUID runId) {
        long user=CatalogAccess.requireUser(account);ownedConversation(user,conversation);
        AgentRunRecord run=conversations.findRun(runId).filter(r->r.userId()==user&&r.conversationId()==conversation)
                .orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        JsonNode message=originalUser(run);
        if(!message.hasNonNull("executionIntent")) return new MonitorAdmissionView(runId,"NOT_REQUESTED",null,null,null,null,null,null);
        var admitted=mapper.admission(runId,user,conversation).orElse(null);
        if(admitted!=null) return new MonitorAdmissionView(runId,admitted.status(),admitted.reasonCode(),Long.toString(admitted.draftId()),
                textId(admitted.taskId()),admitted.executionSource(),admitted.quotedPoints(),admitted.admittedAt());
        String reason=message.hasNonNull("monitorPreparationReason")?message.path("monitorPreparationReason").asText():
                "STOPPED".equals(run.status())?RUN_STOPPED.name():"FAILED".equals(run.status())?RUN_FAILED.name():
                "COMPLETED".equals(run.status())?NO_MATCHING_PROPOSAL.name():null;
        return new MonitorAdmissionView(runId,reason==null?"WAITING_PROPOSAL":"WAIT_INPUT",reason,null,null,null,null,null);
    }

    private record Prepared(long spaceId,Long productId,FrozenMonitorRange range,GeoTaskService.PreparedDraft draft) { }
    private Prepared prepare(long user,AgentPlatformDtos.AgentIdentity identity,MonitorRangeInput input) {
        if(input.questionIds()==null||input.platformCodes()==null||input.questionIds().size()<3||input.questionIds().size()>10
                ||input.platformCodes().isEmpty()||input.platformCodes().size()>7
                ||new HashSet<>(input.questionIds()).size()!=input.questionIds().size()
                ||new HashSet<>(input.platformCodes()).size()!=input.platformCodes().size()) throw invalid();
        var topic=geo.shareTopic(id(input.topicId()),user).orElseThrow(()->refuse(TOPIC_UNAVAILABLE));
        if(identity.projectId()!=null&&id(identity.projectId())!=topic.spaceId()) throw refuse(PROJECT_UNAVAILABLE);
        if(topic.region()==null||topic.region().isBlank()) throw refuse(MONITOR_REGION_MISSING);
        var space=spaces.shareProjectByIdAndUserId(topic.spaceId(),user).orElseThrow(()->refuse(MONITOR_TARGET_MISSING));
        if(space.brandName()==null||space.brandName().isBlank()) throw refuse(MONITOR_TARGET_MISSING);
        Integer productVersion=null;
        if(topic.productId()!=null) {
            var product=products.shareByIdAndUserId(topic.productId(),user).orElseThrow(()->refuse(MONITOR_TARGET_MISSING));
            if(product.spaceId()!=topic.spaceId()||product.name()==null||product.name().isBlank()) throw refuse(MONITOR_TARGET_MISSING);
            productVersion=product.version();
        } else if(space.brandName()==null||space.brandName().isBlank()) throw refuse(MONITOR_TARGET_MISSING);
        var set=geo.findLatestCompetitorSet(topic.id(),user).orElse(null);
        var versions=set==null?List.<CompetitorVersion>of():geo.findCompetitorSetMembers(set.id()).stream()
                .sorted(Comparator.comparingLong(m->m.competitorId())).map(member->{
                    var competitor=competitors.shareByIdAndUserId(member.competitorId(),user).orElseThrow(()->refuse(INPUT_CHANGED));
                    if(competitor.spaceId()!=topic.spaceId()) throw refuse(INPUT_CHANGED);
                    return new CompetitorVersion(Long.toString(competitor.id()),competitor.version());
                }).toList();
        if(versions.size()>20) throw refuse(INPUT_CHANGED);
        FrozenMonitorRange range=new FrozenMonitorRange(input.topicId(),topic.version(),List.copyOf(input.questionIds()),
                List.copyOf(input.platformCodes()),input.sampleMode(),topic.region(),
                new InputVersions(space.version(),productVersion,set==null?null:set.version(),versions),"");
        GeoTaskService.PreparedDraft prepared;
        try { prepared=tasks.prepareDraft(user,topic.spaceId(),topic.productId(),parameters(range)); }
        catch(BusinessException ex) { throw refuse(ex.errorCode()==ErrorCode.PROVIDER_NOT_CONFIGURED?AGENT_UNAVAILABLE:RANGE_CHANGED); }
        range=new FrozenMonitorRange(range.topicId(),range.topicVersion(),range.questionIds(),range.platformCodes(),range.sampleMode(),
                range.region(),range.inputVersions(),tasks.snapshotHash(prepared.snapshot()));
        return new Prepared(topic.spaceId(),topic.productId(),range,prepared);
    }

    private ObjectNode parameters(FrozenMonitorRange range) {
        ObjectNode result=json.createObjectNode().put("mode","MONITOR_RUN").put("topicId",range.topicId())
                .put("sampleMode",range.sampleMode()).put("region",range.region());
        result.set("questionIds",json.valueToTree(range.questionIds()));result.set("platformCodes",json.valueToTree(range.platformCodes()));return result;
    }
    private void requireRange(FrozenMonitorRange expected,FrozenMonitorRange actual) {
        if(!expected.topicId().equals(actual.topicId())||!expected.questionIds().equals(actual.questionIds())
                ||!expected.platformCodes().equals(actual.platformCodes())||!expected.sampleMode().equals(actual.sampleMode())
                ||!expected.region().equals(actual.region())||!Objects.equals(expected.topicVersion(),actual.topicVersion())) throw refuse(RANGE_CHANGED);
        if(!expected.inputVersions().equals(actual.inputVersions())||!expected.inputHash().equals(actual.inputHash())) throw refuse(INPUT_CHANGED);
    }
    private void requirePrice(MonitorExecutionIntent intent,Prepared prepared) {
        if(id(intent.pricingVersionId())!=pricing.pricingVersionId("GEO")||intent.quotedPoints()!=tasks.quote(prepared.draft())) throw refuse(PRICE_CHANGED);
    }
    private void requireGrant(MonitorExecutionRecords.Grant grant,MonitorExecutionIntent intent,AgentPlatformDtos.AgentIdentity identity,OffsetDateTime now) {
        if(!"ACTIVE".equals(grant.status())) throw refuse(GRANT_REVOKED);
        if(grant.version()!=intent.grantVersion()) throw refuse(GRANT_CHANGED);
        if(!now.isBefore(grant.expiresAt())) throw refuse(GRANT_EXPIRED);
        requireGrantIdentity(grant,identity);
        if(!read(grant.scopeSnapshotJson()).equals(json.valueToTree(intent.range()))) throw refuse(GRANT_CHANGED);
    }
    private void requireGrantIdentity(MonitorExecutionRecords.Grant grant,AgentPlatformDtos.AgentIdentity identity) {
        if(grant.installationId()!=id(identity.installationId())||!grant.agentKey().equals(identity.agentKey())
                ||!grant.agentVersion().equals(identity.agentVersion())||!Objects.equals(grant.projectId(),nullableId(identity.projectId()))
                ||!grant.definitionHash().equals(definitionHash(identity))) throw refuse(AGENT_UNAVAILABLE);
    }
    private AgentPlatformDtos.AgentIdentity identity(long user,long conversation) {
        try { return platform.assertMonitorExecutionAllowed(user,conversation); }
        catch(BusinessException ex) { throw refuse(ex.errorCode()==ErrorCode.RESOURCE_IN_USE?PROJECT_UNAVAILABLE:AGENT_UNAVAILABLE); }
    }
    private String definitionHash(AgentPlatformDtos.AgentIdentity identity) {
        String hash=mapper.definitionHash(identity.agentKey(),identity.agentVersion());
        if(hash==null||!hash.matches("[0-9a-f]{64}")) throw refuse(AGENT_UNAVAILABLE);return hash;
    }
    private JsonNode originalUser(AgentRunRecord run) {
        var message=conversations.findMessage(run.requestMessageId(),run.userId(),run.conversationId()).orElseThrow();
        if(!"USER".equals(message.role())||!run.id().equals(message.agentRunId())) throw new IllegalStateException("Monitor intent is not the original USER message");
        return read(message.contentJson());
    }
    private void ownedConversation(long user,long conversation) {
        if(conversations.findConversation(conversation,user).isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    private BudgetPolicyView budgetView(MonitorExecutionRecords.Policy policy) {
        return new BudgetPolicyView(policy!=null,policy==null?0:policy.version(),policy!=null&&policy.enabled(),
                policy==null?null:policy.maxPointsPerAction(),policy==null?null:policy.maxPointsPerDay(),"Asia/Shanghai",
                properties.getMonitorBudgetEarliestTrustedDate(),policy==null?null:policy.effectiveShanghaiDate());
    }
    private MonitorAuthorizationView grantView(MonitorExecutionRecords.Grant grant) { return new MonitorAuthorizationView(Long.toString(grant.id()),
            "MONITOR_RUN",grant.version(),grant.status(),convert(read(grant.scopeSnapshotJson()),FrozenMonitorRange.class),grant.expiresAt(),grant.revokedAt(),grant.createdAt()); }
    public OffsetDateTime accountingNow() { return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
    private void requireFuture(OffsetDateTime expires,OffsetDateTime now) { if(expires==null||!expires.isAfter(now)) throw invalid(); }
    private String canonical(Object value) { return tasks.canonicalSnapshot(json.valueToTree(value)); }
    private JsonNode read(String raw) { try {return json.readTree(raw);} catch(Exception ex){throw new IllegalStateException("Invalid persisted monitor facts",ex);} }
    private <T> T convert(JsonNode node,Class<T> type) { try{return json.treeToValue(node,type);}catch(Exception ex){throw new IllegalStateException("Invalid persisted monitor shape",ex);} }
    private static long id(String value) { try{long result=Long.parseLong(value);if(result<1)throw invalid();return result;}catch(NumberFormatException ex){throw invalid();} }
    private static Long nullableId(String value) { return value==null?null:id(value); }
    private static String textId(Long value) { return value==null?null:Long.toString(value); }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT); }
    public static Refusal refuse(MonitorWaitReason reason) { return new Refusal(reason); }
    public static final class Refusal extends BusinessException {
        private final MonitorWaitReason reason;
        private Refusal(MonitorWaitReason reason){super(ErrorCode.RESOURCE_VERSION_CONFLICT,
                "监测条件已变化，请检查设置后重试",java.util.Map.of("reasonCode",reason.name()));this.reason=reason;}
        public MonitorWaitReason reason(){return reason;}
    }
}
