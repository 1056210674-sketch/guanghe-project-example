package com.guanghe.backend.conversation.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.agentplatform.AgentPlatformDtos;
import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationEventRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.ConversationRecord;
import com.guanghe.backend.conversation.RunToolAuthorizationService;
import com.guanghe.backend.conversation.context.ConversationWorkspaceService;
import com.guanghe.backend.conversation.context.RunContextService;
import com.guanghe.backend.geo.GeoChatContextFactory;
import com.guanghe.backend.geo.GeoDiagnosticDeliveryService;
import com.guanghe.backend.geo.GeoMapper;
import com.guanghe.backend.geo.GeoTaskMapper;
import com.guanghe.backend.geo.GeoTaskRecords;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.guanghe.backend.conversation.resource.RunResourceDtos.*;

/** Six bounded, owner-checked reads per Run. Nothing here creates tasks or spends points. */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RunResourceService {
    static final int PROJECTION_BYTES = 16_384;
    private static final Set<String> KINDS = Set.of("SPACE", "PRODUCT", "GEO_TOPIC");
    private static final Set<String> PURPOSES = Set.of("CATALOG_SUMMARY", "LATEST_GEO_REPORT",
            "GEO_ANSWER_EVIDENCE", "GEO_DIAGNOSTIC_REPORT", "ASSET_INVENTORY");
    private static final Set<String> FAILED_ATTEMPTS = Set.of("FAILED", "SYSTEM_TERMINATED");
    private final RunResourceMapper resources;
    private final ConversationMapper conversations;
    private final RunContextService contexts;
    private final RunToolAuthorizationService authorization;
    private final AccountMapper accounts;
    private final BusinessSpaceMapper spaces;
    private final ProductMapper products;
    private final GeoMapper topics;
    private final GeoTaskMapper reports;
    private final AssetMapper assets;
    private final GeoChatContextFactory geoContexts;
    private final CatalogNameNormalizer normalizer;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private Clock clock = Clock.systemUTC();
    private ConversationWorkspaceService workspace;
    private GeoDiagnosticDeliveryService diagnostics;

    public RunResourceService(RunResourceMapper resources, ConversationMapper conversations,
            RunContextService contexts, RunToolAuthorizationService authorization, AccountMapper accounts, BusinessSpaceMapper spaces,
            ProductMapper products, GeoMapper topics, GeoTaskMapper reports, AssetMapper assets,
            GeoChatContextFactory geoContexts, CatalogNameNormalizer normalizer,
            SnowflakeIdGenerator ids, ObjectMapper json) {
        this.resources=resources; this.conversations=conversations; this.contexts=contexts; this.authorization=authorization;
        this.accounts=accounts; this.spaces=spaces; this.products=products; this.topics=topics;
        this.reports=reports; this.assets=assets; this.geoContexts=geoContexts;
        this.normalizer=normalizer; this.ids=ids; this.json=json;
    }

    @Autowired(required = false)
    void setWorkspace(ConversationWorkspaceService value) { workspace=value; }
    @Autowired(required = false)
    void setDiagnostics(GeoDiagnosticDeliveryService value) { diagnostics=value; }
    void setClock(Clock value) { clock=Objects.requireNonNull(value); }

    // Only an authorized failed read commits its error receipt. Infrastructure/DB exceptions roll back.
    @Transactional(noRollbackFor = BusinessException.class)
    public SearchResponse search(UUID runId, String contextRef, SearchRequest request) {
        requireToolCall(request.toolCallId());
        if (!KINDS.contains(request.kind())) throw invalid();
        String keyword=normalizer.requiredName(request.keyword(),200,"keyword").normalized();
        var input=new SearchRequest(request.toolCallId(),request.kind(),keyword,request.scopeRef());
        Session session=authorize(runId,contextRef,"context.resources.search");
        if ("GEO_TOPIC".equals(request.kind())) requireGeoRead(session);
        UUID source=sourceId(runId,input.toolCallId());
        String hash=hash(input);
        var replay=replay(session,source,hash,"SEARCH");
        if (replay!=null) return decode(replay.path("result"),SearchResponse.class);
        requireBudget(runId);
        long id=ids.nextId();
        try {
            Long scopeSpace=optionalId(session.agent.projectId()),scopeProduct=null;
            if (input.scopeRef()!=null) {
                var scope=ownedObservation(session,input.scopeRef());
                if(scopeSpace!=null && !scopeSpace.toString().equals(scope.spaceId())) throw unavailable();
                scopeSpace=positive(scope.spaceId());
                scopeProduct=optionalId(scope.productId());
            }
            var matches=resources.search(session.run.userId(),input.kind(),keyword,scopeSpace,scopeProduct);
            List<Candidate> candidates=matches.stream().limit(6).map(item -> new Candidate(
                    "choice-"+item.resourceId(),input.kind(),Long.toString(item.resourceId()),item.name(),
                    Long.toString(item.spaceId()),item.spaceName(),item.hasReport(),item.exact())).toList();
            long total=matches.isEmpty()?0:matches.getFirst().totalMatches();
            boolean complete=total>=0 && candidates.size()==Math.min(total,6);
            String status=!complete?"INCOMPLETE":total==0?"NOT_FOUND"
                    :total==1 && candidates.size()==1 && candidates.getFirst().exact()?"UNIQUE":"MULTIPLE";
            SearchResponse result=new SearchResponse(Long.toString(id),input.kind(),status,total,complete,
                    candidates,now().plusHours(24));
            save(session,id,source,"SEARCH",hash,input,result,null);
            return result;
        } catch (BusinessException failure) {
            save(session,id,source,"SEARCH",hash,input,null,failure.errorCode()); throw failure;
        }
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ReadResponse read(UUID runId, String contextRef, ReadRequest request) {
        ReadRequest input=canonicalRead(request);
        Session session=authorize(runId,contextRef,"context.resources.read");
        if (reportPurpose(input.purpose()) || diagnosticPurpose(input.purpose())) requireGeoRead(session);
        UUID source=sourceId(runId,input.toolCallId());
        String hash=hash(input);
        var replay=replay(session,source,hash,"READ");
        if (replay!=null) return decode(replay.path("result"),ReadResponse.class);
        requireBudget(runId);
        long id=ids.nextId();
        try {
            Selection selection;
            ReadResponse previous=null;
            if (input.observationId()!=null) {
                previous=ownedObservation(session,input.observationId()); selection=selection(previous);
            } else selection=ownedCandidate(session,input.candidateSetId(),input.choiceId(),false);
            authorizeReadScope(session,selection,input);
            if (input.candidateSetId()!=null)
                selection=ownedCandidate(session,input.candidateSetId(),input.choiceId(),true);
            Projected projected=project(session,selection,previous,input);
            Bounded bounded=bound(projected.projection);
            ReadResponse result=new ReadResponse(Long.toString(id),selection.kind(),input.purpose(),
                    Long.toString(selection.resourceId()),selection.name(),Long.toString(selection.spaceId()),
                    id(selection.productId()),now(),hash(bounded.node),id(projected.reportId),projected.attempt,
                    bounded.node,bounded.truncated || projected.projection.path("truncated").asBoolean(false));
            save(session,id,source,"READ",hash,input,result,null);
            return result;
        } catch (BusinessException failure) {
            save(session,id,source,"READ",hash,input,null,failure.errorCode()); throw failure;
        }
    }

    @Transactional(readOnly = true)
    public Selection validateSelection(long userId,long conversationId,String candidateSetId,String choiceId,
            String selectionMode,Long requestedSpaceId,Long requestedProductId) {
        requireAccount(userId);
        var conversation=conversations.findConversation(conversationId,userId).orElseThrow(this::unavailable);
        if ("CLEAR".equals(selectionMode) || ("REPLACE".equals(selectionMode)
                && (!Objects.equals(requestedSpaceId,conversation.selectedSpaceId())
                || !Objects.equals(requestedProductId,conversation.selectedProductId())))) throw unavailable();
        var search=currentSearch(userId,conversationId,conversation.selectedSpaceId(),conversation.selectedProductId());
        if (search==null || !candidateSetId.equals(search.candidateSetId())) throw unavailable();
        Candidate choice=findCandidate(search,choiceId);
        return ownedSelection(userId,choice,candidateSetId);
    }

    @Transactional(readOnly = true)
    public SearchResponse pendingSearch(long userId,long conversationId,Long spaceId,Long productId) {
        try { requireAccount(userId); return currentSearch(userId,conversationId,spaceId,productId); }
        catch (BusinessException denied) { return null; }
    }

    @Transactional(readOnly = true)
    public ReadResponse lastReadContext(long userId,long conversationId,Long spaceId,Long productId) {
        return lastRead(userId,conversationId,spaceId,productId,false);
    }

    @Transactional(readOnly = true)
    public ReadResponse lastReportReadContext(long userId,long conversationId,Long spaceId,Long productId) {
        // A successful latest-attempt read with no report is a barrier, NOT a reason to load an older report.
        return lastRead(userId,conversationId,spaceId,productId,true);
    }

    @Transactional(readOnly = true)
    public JsonNode selectionInteraction(AgentRunRecord run,String candidateSetId,String question) {
        requireAccount(run.userId());
        var conversation=conversations.findConversation(run.conversationId(),run.userId()).orElseThrow(this::unavailable);
        var search=currentSearch(run.userId(),run.conversationId(),conversation.selectedSpaceId(),conversation.selectedProductId());
        if (search==null || !search.candidateSetId().equals(candidateSetId)
                || search.candidates().isEmpty() || question==null || question.isBlank() || question.length()>4000)
            throw unavailable();
        ObjectNode node=json.createObjectNode().put("interactionType","RESOURCE_SELECTION")
                .put("intent","GENERAL").put("question",question).put("candidateSetId",candidateSetId);
        node.putArray("missingFields");
        node.set("choices",json.valueToTree(search.candidates().stream().map(Candidate::name).toList()));
        node.set("candidates",json.valueToTree(search.candidates()));
        return node;
    }

    @Transactional(readOnly = true)
    public boolean hasSelectionConsumption(long userId,long conversationId,String candidateSetId,String choiceId) {
        return resources.consumed(userId,conversationId,null,candidateSetId,choiceId);
    }

    @Transactional(readOnly = true)
    public boolean hasSuccessfulRead(long userId,long conversationId,UUID runId,String candidateSetId,String choiceId) {
        return resources.consumed(userId,conversationId,runId,candidateSetId,choiceId);
    }

    /** Server-only projection: the actual read identity is kept separate from its original report header. */
    public record ActionReportReference(ReadResponse observation,JsonNode reportMetadata) { }

    @Transactional(readOnly = true)
    public ActionReportReference currentRunLatestReportReference(AgentRunRecord run) {
        requireAccount(run.userId());
        var conversation=conversations.findConversation(run.conversationId(),run.userId()).orElseThrow(this::unavailable);
        conversations.findRun(run.id()).filter(owned -> owned.userId()==run.userId()
                && owned.conversationId()==run.conversationId()).orElseThrow(this::unavailable);
        var event=resources.latestReportReadForRun(run.userId(),run.conversationId(),run.id()).orElse(null);
        JsonNode referenced;
        if (event!=null) referenced=readTree(event.payloadJson()).path("result");
        else {
            JsonNode snapshot=readTree(run.contextSnapshotJson());
            if ("EXPLICIT_REPORT".equals(snapshot.path("geoInsightContext").path("selectionSource").asText()))
                return null; // Completion must use this Run's frozen explicit attachment, not previous reads.
            referenced=snapshot.path("lastReportReadContext");
            if (referenced.isMissingNode() || referenced.isNull()) {
                referenced=snapshot.path("lastReadContext");
                if (!reportPurpose(referenced.path("purpose").asText())) return null;
            }
        }
        // Only true absence returns null. A revoked/reset/invalid reference must not revive a
        // legacy report. Completion callers may omit its metadata without failing the message.
        if (!reportPurpose(referenced.path("purpose").asText())) throw unavailable();
        var session=new Session(run,conversation,scopeFingerprint(conversation.selectedSpaceId(),conversation.selectedProductId()));
        ReadResponse observation=ownedObservation(session,referenced.path("observationId").asText());
        Long explicitReport=explicitReportId(session);
        if (explicitReport!=null && !id(explicitReport).equals(observation.reportId())) throw unavailable();
        if (!Objects.equals(observation.sourceHash(),referenced.path("sourceHash").asText())) throw unavailable();
        return new ActionReportReference(observation,reportMetadata(session,observation));
    }

    @Transactional(readOnly = true)
    public ReadResponse requireOwnedObservationForAction(long userId,long conversationId,String observationId,long reportId) {
        requireAccount(userId);
        var conversation=conversations.findConversation(conversationId,userId).orElseThrow(this::unavailable);
        var anchor=lastRead(userId,conversationId,conversation.selectedSpaceId(),conversation.selectedProductId(),true);
        if (anchor==null || !Objects.equals(anchor.observationId(),observationId)
                || !Objects.equals(anchor.reportId(),id(reportId))) throw unavailable();
        requireArticleReport(userId,anchor,reportId);
        return anchor;
    }

    @Transactional(readOnly = true)
    public ReadResponse requireOwnedObservationForAction(AgentRunRecord run,String observationId,long reportId) {
        var reference=currentRunLatestReportReference(run);
        if (reference==null || !Objects.equals(reference.observation().observationId(),observationId)
                || !Objects.equals(reference.observation().reportId(),id(reportId))) throw unavailable();
        requireArticleReport(run.userId(),reference.observation(),reportId);
        return reference.observation();
    }

    @Transactional(readOnly = true)
    public boolean isOwnedObservationAvailableForAction(long userId,long conversationId,String observationId,long reportId) {
        try { requireOwnedObservationForAction(userId,conversationId,observationId,reportId); return true; }
        catch (BusinessException denied) { return false; }
    }

    private boolean reportPurpose(String purpose) {
        return Set.of("LATEST_GEO_REPORT","GEO_ANSWER_EVIDENCE").contains(purpose);
    }

    private boolean diagnosticPurpose(String purpose) {
        return "GEO_DIAGNOSTIC_REPORT".equals(purpose);
    }

    private void requireArticleReport(long userId,ReadResponse observation,long reportId) {
        if (!reportPurpose(observation.purpose()) || !"GEO_TOPIC".equals(observation.kind())) throw unavailable();
        var report=reports.findReport(reportId,userId).orElseThrow(this::unavailable);
        if (report.archivedAt()!=null || !Set.of("SUCCEEDED","PARTIAL").contains(report.status())
                || !"geo-synthesis-v1".equals(report.methodVersion()) || report.sampleSize()==0)
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        // The original monitoring scope, not a subsequently renamed/reassigned topic, owns the action.
        var monitored=reports.findRun(report.runId(),userId).orElseThrow(this::unavailable);
        if (monitored.spaceId()!=positive(observation.spaceId())
                || !Objects.equals(monitored.productId(),optionalId(observation.productId()))) throw unavailable();
        spaces.findProjectByIdAndUserId(monitored.spaceId(),userId).orElseThrow(this::unavailable);
        if (monitored.productId()!=null) {
            var product=products.findByIdAndUserId(monitored.productId(),userId).orElseThrow(this::unavailable);
            if (product.spaceId()!=monitored.spaceId()) throw unavailable();
        }
    }

    private JsonNode reportMetadata(Session session,ReadResponse observation) {
        if (observation.reportId()==null) return null;
        Set<String> visited=new HashSet<>();
        ReadResponse cursor=observation;
        for (int count=0;count<6;count++) {
            if (!visited.add(cursor.observationId()) || !Objects.equals(cursor.reportId(),observation.reportId())) throw unavailable();
            JsonNode header=cursor.projection().path("report");
            if (header.isObject() && observation.reportId().equals(header.path("reportId").asText())) {
                ObjectNode metadata=(ObjectNode)header.deepCopy();
                var report=reports.findReport(positive(observation.reportId()),session.run.userId()).orElseThrow(this::unavailable);
                var monitored=reports.findRun(report.runId(),session.run.userId()).orElseThrow(this::unavailable);
                JsonNode original=readTree(monitored.contextSnapshotJson()).path("authorizedContext").path("topic").path("name");
                metadata.put("title",original.isTextual() && !original.asText().isBlank()?original.asText():"监测报告（原专题名称未保存）");
                return metadata;
            }
            if (!"GEO_ANSWER_EVIDENCE".equals(cursor.purpose())) throw unavailable();
            var event=resources.findReference(session.run.userId(),session.run.conversationId(),positive(cursor.observationId()),
                    resources.resetSequence(session.run.userId(),session.run.conversationId())).orElseThrow(this::unavailable);
            String parent=readTree(event.payloadJson()).path("input").path("observationId").asText();
            cursor=ownedObservation(session,parent);
        }
        throw unavailable();
    }

    private record Session(AgentRunRecord run,ConversationRecord conversation,String fingerprint,
            AgentPlatformDtos.AgentIdentity agent) {
        Session(AgentRunRecord run,ConversationRecord conversation,String fingerprint) { this(run,conversation,fingerprint,null); }
    }
    private Session authorize(UUID runId,String contextRef,String tool) {
        var context=contexts.get(runId,contextRef);
        if (context.protocolFeatures()==null || !context.protocolFeatures().resourceLookup()) throw unavailable();
        var authorized=authorization.authorize(runId,context,tool,clock);
        var run=authorized.run();
        // Resource tools additionally lock their selected scope after the common account/Run/installation boundary.
        ConversationRecord conversation=conversations.lockConversation(run.conversationId(),run.userId())
                .orElseThrow(this::unavailable);
        if (conversations.findActiveRun(run.userId(),run.conversationId()).filter(active -> active.id().equals(runId)).isEmpty())
            throw unavailable();
        authorization.requireLive(run,clock);
        if (!readTree(run.attachmentAssetIdsJson()).isEmpty()) throw unavailable();
        return new Session(run,conversation,scopeFingerprint(conversation.selectedSpaceId(),conversation.selectedProductId()),authorized.agent());
    }

    private void requireGeoRead(Session session) {
        if (session.agent!=null && !session.agent.capabilities().contains("geo.read"))
            throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private com.guanghe.backend.auth.account.AccountIdentity requireAccount(long userId) {
        var account=accounts.findIdentityById(userId).orElseThrow(this::unavailable);
        if (account.role()!=AccountRole.USER || account.status()!=AccountStatus.ACTIVE
                || !"ACTIVE".equals(account.credentialState())) throw unavailable();
        return account;
    }

    private JsonNode replay(Session session,UUID source,String hash,String kind) {
        var event=resources.findCall(session.run.id(),source).orElse(null);
        if (event==null) return null;
        JsonNode payload=readTree(event.payloadJson());
        if (!"context.resource".equals(event.eventType()) || !kind.equals(payload.path("kind").asText())
                || !hash.equals(payload.path("inputHash").asText()))
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        if (!session.fingerprint.equals(payload.path("scopeFingerprint").asText())) throw unavailable();
        // Failed calls contain no resource result; the current Run/account authorization is
        // sufficient to replay their controlled error. A revoked source must never expose data.
        if (!payload.path("success").asBoolean(false)) {
            ErrorCode code;
            try { code=ErrorCode.valueOf(payload.path("errorCode").asText()); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid resource failure receipt"); }
            throw new BusinessException(code);
        }
        // Revalidate the saved source before successful replay, but never reapply TTL/consumption.
        JsonNode savedInput=payload.path("input");
        if ("SEARCH".equals(kind)) {
            if (savedInput.hasNonNull("scopeRef")) {
                var scope=ownedObservation(session,savedInput.path("scopeRef").asText());
                if(session.agent.projectId()!=null && !session.agent.projectId().equals(scope.spaceId())) throw unavailable();
            }
        } else {
            var request=decode(savedInput,ReadRequest.class);
            Selection selected=request.observationId()!=null?selection(ownedObservation(session,request.observationId()))
                    :ownedCandidate(session,request.candidateSetId(),request.choiceId(),false);
            authorizeReadScope(session,selected,request);
        }
        if ("SEARCH".equals(kind)) {
            var saved=decode(payload.path("result"),SearchResponse.class);
            for (Candidate candidate:saved.candidates()) {
                if(session.agent.projectId()!=null && !session.agent.projectId().equals(candidate.spaceId())) throw unavailable();
                ownedSelection(session.run.userId(),candidate,saved.candidateSetId());
            }
        } else requireOwnedRead(session.run.userId(),decode(payload.path("result"),ReadResponse.class));
        return payload;
    }

    private void requireBudget(UUID runId) {
        if (resources.callCount(runId)>=6) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
    }

    private void save(Session session,long eventId,UUID source,String kind,String inputHash,Object input,
            Object result,ErrorCode failure) {
        ObjectNode payload=json.createObjectNode().put("kind",kind).put("inputHash",inputHash)
                .put("scopeFingerprint",session.fingerprint).put("success",failure==null);
        payload.set("input",json.valueToTree(input)); payload.set("result",json.valueToTree(result));
        JsonNode inputNode=payload.path("input");
        for (String field:List.of("candidateSetId","choiceId","purpose"))
            if (inputNode.hasNonNull(field)) payload.set(field,inputNode.get(field));
        if (result instanceof SearchResponse search) payload.put("candidateSetId",search.candidateSetId());
        if (result instanceof ReadResponse read) payload.put("observationId",read.observationId());
        if (failure!=null) payload.put("errorCode",failure.name());
        Long sequence=conversations.nextEventSequence(session.run.conversationId(),session.run.userId());
        if (sequence==null) throw new IllegalStateException("Resource event sequence unavailable");
        OffsetDateTime now=now();
        if (conversations.insertEvent(new ConversationEventRecord(eventId,session.run.conversationId(),
                session.run.userId(),session.run.id(),session.run.responseMessageId(),source,"context.resource",
                sequence,write(payload),now,now))!=1) throw new IllegalStateException("Resource event insert conflict");
        if (workspace!=null) workspace.refreshAfterCommit(session.run.userId(),session.run.conversationId());
    }

    private SearchResponse currentSearch(long userId,long conversationId,Long spaceId,Long productId) {
        var event=resources.latestSearch(userId,conversationId,resources.resetSequence(userId,conversationId)).orElse(null);
        if (event==null) return null;
        JsonNode payload=readTree(event.payloadJson());
        if (!scopeFingerprint(spaceId,productId).equals(payload.path("scopeFingerprint").asText())) return null;
        var result=decode(payload.path("result"),SearchResponse.class);
        if (!now().isBefore(result.expiresAt()) || resources.consumed(userId,conversationId,null,result.candidateSetId(),null))
            return null;
        for (var candidate:result.candidates()) ownedSelection(userId,candidate,result.candidateSetId());
        return result;
    }

    private ReadResponse lastRead(long userId,long conversationId,Long spaceId,Long productId,boolean reportOnly) {
        try {
            requireAccount(userId);
            var event=resources.latestRead(userId,conversationId,resources.resetSequence(userId,conversationId),reportOnly).orElse(null);
            if (event==null) return null;
            JsonNode payload=readTree(event.payloadJson());
            if (!scopeFingerprint(spaceId,productId).equals(payload.path("scopeFingerprint").asText())) return null;
            var result=decode(payload.path("result"),ReadResponse.class); requireOwnedRead(userId,result); return result;
        } catch (BusinessException denied) { return null; }
    }

    private Selection ownedCandidate(Session session,String candidateSetId,String choiceId,boolean lifecycle) {
        var event=resources.findReference(session.run.userId(),session.run.conversationId(),positive(candidateSetId),
                resources.resetSequence(session.run.userId(),session.run.conversationId())).orElseThrow(this::unavailable);
        JsonNode payload=readTree(event.payloadJson());
        if (!"SEARCH".equals(payload.path("kind").asText()) || !payload.path("success").asBoolean()
                || !session.fingerprint.equals(payload.path("scopeFingerprint").asText())) throw unavailable();
        var search=decode(payload.path("result"),SearchResponse.class);
        Candidate candidate=findCandidate(search,choiceId);
        Selection selection=ownedSelection(session.run.userId(),candidate,candidateSetId);
        if (lifecycle) {
            if (session.run.id().equals(event.agentRunId())) {
                // Independent searches in one Agent Loop must not invalidate each other.
                // Only this Run's issued sets get that exception; owner/scope/reset checks above
                // and the existing set-wide TTL/consumption rules still apply.
                if (!candidateSetId.equals(search.candidateSetId()) || !now().isBefore(search.expiresAt())
                        || resources.consumed(session.run.userId(),session.run.conversationId(),null,candidateSetId,null))
                    throw unavailable();
                for (var item:search.candidates()) ownedSelection(session.run.userId(),item,candidateSetId);
            } else {
                var current=currentSearch(session.run.userId(),session.run.conversationId(),
                        session.conversation.selectedSpaceId(),session.conversation.selectedProductId());
                if (current==null || !current.candidateSetId().equals(candidateSetId)) throw unavailable();
            }
        }
        return selection;
    }

    private ReadResponse ownedObservation(Session session,String observationId) {
        var event=resources.findReference(session.run.userId(),session.run.conversationId(),positive(observationId),
                resources.resetSequence(session.run.userId(),session.run.conversationId())).orElseThrow(this::unavailable);
        JsonNode payload=readTree(event.payloadJson());
        if (!"READ".equals(payload.path("kind").asText()) || !payload.path("success").asBoolean()
                || !session.fingerprint.equals(payload.path("scopeFingerprint").asText())) throw unavailable();
        var result=decode(payload.path("result"),ReadResponse.class);
        requireOwnedRead(session.run.userId(),result); return result;
    }

    private Candidate findCandidate(SearchResponse search,String choiceId) {
        return search.candidates().stream().filter(item -> item.choiceId().equals(choiceId)).findFirst()
                .orElseThrow(this::unavailable);
    }

    private Selection ownedSelection(long userId,Candidate candidate,String candidateSetId) {
        long resourceId=positive(candidate.resourceId()), spaceId=positive(candidate.spaceId());
        Long productId=null; String name;
        switch (candidate.kind()) {
            case "SPACE" -> { var value=spaces.findProjectByIdAndUserId(resourceId,userId).orElseThrow(this::unavailable);
                if (value.id()!=spaceId) throw unavailable(); name=value.name(); }
            case "PRODUCT" -> { var value=products.findByIdAndUserId(resourceId,userId).orElseThrow(this::unavailable);
                if (value.spaceId()!=spaceId) throw unavailable(); productId=value.id(); name=value.name(); }
            case "GEO_TOPIC" -> { var value=topics.findTopic(resourceId,userId).orElseThrow(this::unavailable);
                if (value.spaceId()!=spaceId) throw unavailable(); productId=value.productId(); name=value.name(); }
            default -> throw unavailable();
        }
        spaces.findProjectByIdAndUserId(spaceId,userId).orElseThrow(this::unavailable);
        return new Selection(candidate.kind(),resourceId,name,spaceId,productId,candidateSetId,candidate.choiceId());
    }

    private void requireOwnedRead(long userId,ReadResponse value) {
        Selection owned=ownedSelection(userId,new Candidate("observation",value.kind(),value.resourceId(),value.name(),
                value.spaceId(),null,value.reportId()!=null,true),null);
        if (!Objects.equals(owned.productId(),optionalId(value.productId()))) throw unavailable();
        if (diagnosticPurpose(value.purpose())) {
            if (!"SPACE".equals(value.kind()) || value.reportId()!=null || value.productId()!=null) throw unavailable();
            JsonNode projection=value.projection();
            if ("READY".equals(projection.path("status").asText())) {
                JsonNode version=projection.path("reportVersion");
                if (diagnostics==null || !version.isIntegralNumber() || !version.canConvertToInt()
                        || version.asInt()<1 || !diagnostics.isAgentProjectionAvailable(userId,owned.spaceId(),
                            positive(projection.path("diagnosticId").asText()),version.asInt())) throw unavailable();
            } else if (!"NO_REPORT".equals(projection.path("status").asText())) throw unavailable();
        }
        if (value.reportId()!=null) {
            var report=reports.findReport(positive(value.reportId()),userId).orElseThrow(this::unavailable);
            if (report.archivedAt()!=null) throw unavailable();
            var run=reports.findRun(report.runId(),userId).orElseThrow(this::unavailable);
            if (run.spaceId()!=owned.spaceId() || !Objects.equals(run.productId(),owned.productId())
                    || !"GEO_TOPIC".equals(owned.kind()) || report.topicId()!=owned.resourceId()) throw unavailable();
        }
        if ("ASSET_INVENTORY".equals(value.purpose())) {
            for (var item:value.projection().path("items")) {
                var asset=assets.findAsset(positive(item.path("assetId").asText()),userId).orElseThrow(this::unavailable);
                if (asset.spaceId()!=owned.spaceId()) throw unavailable();
            }
        }
    }

    private Selection selection(ReadResponse value) {
        return new Selection(value.kind(),positive(value.resourceId()),value.name(),positive(value.spaceId()),
                optionalId(value.productId()),null,null);
    }

    private void authorizeReadScope(Session session,Selection selection,ReadRequest input) {
        if ("GEO_TOPIC".equals(selection.kind()) || reportPurpose(input.purpose()) || diagnosticPurpose(input.purpose())) requireGeoRead(session);
        if (diagnosticPurpose(input.purpose()) && (!"SPACE".equals(selection.kind())
                || session.conversation.selectedSpaceId()==null
                || session.conversation.selectedSpaceId()!=selection.spaceId())) throw unavailable();
        if(session.agent!=null && session.agent.projectId()!=null
                && !session.agent.projectId().equals(Long.toString(selection.spaceId()))) throw unavailable();
        if (input.observationId()!=null || session.conversation.selectedSpaceId()==null) return;
        boolean sameSpace=session.conversation.selectedSpaceId()==selection.spaceId();
        boolean sameProduct=session.conversation.selectedProductId()==null || selection.productId()==null
                || Objects.equals(session.conversation.selectedProductId(),selection.productId());
        if (sameSpace && sameProduct) return;
        JsonNode frozen=readTree(session.run.contextSnapshotJson()).path("resourceSelection");
        if (!input.candidateSetId().equals(frozen.path("candidateSetId").asText())
                || !input.choiceId().equals(frozen.path("choiceId").asText())) throw unavailable();
    }

    private record Projected(ObjectNode projection,Long reportId,ReportAttempt attempt) { }
    private Projected project(Session session,Selection selection,ReadResponse previous,ReadRequest input) {
        return switch (input.purpose()) {
            case "CATALOG_SUMMARY" -> new Projected(catalog(session.run.userId(),selection),null,null);
            case "ASSET_INVENTORY" -> new Projected(inventory(session.run.userId(),selection),null,null);
            case "LATEST_GEO_REPORT" -> latestReport(session,selection,previous,input);
            case "GEO_ANSWER_EVIDENCE" -> evidence(session,selection,previous,input);
            case "GEO_DIAGNOSTIC_REPORT" -> diagnosticReport(session,selection,previous);
            default -> throw invalid();
        };
    }

    private Projected diagnosticReport(Session session,Selection selection,ReadResponse previous) {
        if (!"SPACE".equals(selection.kind())) throw invalid();
        // An issued observation is a frozen read, including a NO_REPORT barrier.
        // Resolving a newer diagnosis requires a new, authorized project selection.
        if (previous!=null && diagnosticPurpose(previous.purpose()))
            return new Projected((ObjectNode)previous.projection().deepCopy(),null,null);
        ObjectNode result=diagnostics==null
                ?json.createObjectNode().put("readOnly",true).put("status","NO_REPORT")
                    .put("diagnosticsEnabled",false)
                :diagnostics.latestAgentProjection(session.run.userId(),selection.spaceId());
        return new Projected(result,null,null);
    }

    private ObjectNode catalog(long userId,Selection selection) {
        ObjectNode result=json.createObjectNode().put("readOnly",true).put("status","READY");
        var space=spaces.findProjectByIdAndUserId(selection.spaceId(),userId).orElseThrow(this::unavailable);
        ObjectNode spaceNode=result.putObject("space").put("id",id(space.id())).put("name",space.name())
                .put("brandName",space.brandName()).put("version",space.version());
        spaceNode.set("brandProfile",readTree(space.brandProfileJson()));
        spaceNode.set("aliases",json.valueToTree(spaces.findAliases(space.id(),userId).stream().map(a -> a.value()).toList()));
        if (selection.productId()!=null) {
            var product=products.findByIdAndUserId(selection.productId(),userId).orElseThrow(this::unavailable);
            ObjectNode item=result.putObject("product").put("id",id(product.id())).put("name",product.name())
                    .put("version",product.version()).put("category",product.category()).put("modelSpec",product.modelSpec());
            item.set("sellingPoints",readTree(product.sellingPointsJson())); item.set("parameters",readTree(product.parametersJson()));
            item.set("audience",readTree(product.audienceJson())); item.set("usageScenarios",readTree(product.usageScenariosJson()));
            item.set("prohibitedClaims",readTree(product.prohibitedClaimsJson()));
            item.set("aliases",json.valueToTree(products.findAliases(product.id(),userId,selection.spaceId())
                    .stream().map(a -> a.value()).toList()));
        }
        if ("GEO_TOPIC".equals(selection.kind())) result.putObject("topic").put("id",id(selection.resourceId())).put("name",selection.name());
        return result;
    }

    private ObjectNode inventory(long userId,Selection selection) {
        if ("GEO_TOPIC".equals(selection.kind())) throw invalid();
        var page=assets.findAssetPage(userId,null,selection.spaceId(),selection.productId(),null,0,21);
        ObjectNode result=json.createObjectNode().put("readOnly",true).put("status","READY")
                .put("hasMore",page.size()>20).put("visualContentRead",false).put("truncated",page.size()>20);
        ArrayNode items=result.putArray("items");
        for (var asset:page.stream().limit(20).toList()) {
            if (asset.userId()!=userId || asset.spaceId()!=selection.spaceId()) throw unavailable();
            items.addObject().put("assetId",id(asset.id())).put("name",asset.name()).put("type",asset.assetType())
                    .put("status",asset.status()).put("createdAt",asset.createdAt().toString())
                    .put("href","/app/assets?asset="+asset.id());
        }
        return result;
    }

    private Long explicitReportId(Session session) {
        JsonNode frozen=readTree(session.run.contextSnapshotJson()).path("geoInsightContext");
        if (!"EXPLICIT_REPORT".equals(frozen.path("selectionSource").asText())) return null;
        if (!"READY".equals(frozen.path("status").asText())) throw unavailable();
        long reportId=positive(frozen.path("report").path("reportId").asText());
        var saved=reports.findReport(reportId,session.run.userId()).orElseThrow(this::unavailable);
        var monitored=reports.findRun(saved.runId(),session.run.userId()).orElseThrow(this::unavailable);
        if (saved.archivedAt()!=null || !Set.of("SUCCEEDED","PARTIAL").contains(saved.status())
                || !Set.of("SUCCEEDED","PARTIAL").contains(monitored.status())
                || !reports.activeProject(session.run.userId(),monitored.spaceId())
                || saved.topicId()!=monitored.topicId()
                || !id(monitored.spaceId()).equals(frozen.path("report").path("spaceId").asText())
                || !id(saved.topicId()).equals(frozen.path("report").path("topicId").asText())
                || !Objects.equals(session.conversation.selectedSpaceId(),monitored.spaceId())
                || (session.conversation.selectedProductId()!=null
                    && !Objects.equals(session.conversation.selectedProductId(),monitored.productId()))) throw unavailable();
        return reportId;
    }

    private Projected latestReport(Session session,Selection selection,ReadResponse previous,ReadRequest input) {
        long userId=session.run.userId();
        if (!"GEO_TOPIC".equals(selection.kind())) throw invalid();
        Long explicitReport=explicitReportId(session);
        if (explicitReport!=null) {
            if (previous!=null && previous.reportId()!=null
                    && !id(explicitReport).equals(previous.reportId())) throw unavailable();
            var saved=reports.findReport(explicitReport,userId).orElseThrow(this::unavailable);
            var monitored=reports.findRun(saved.runId(),userId).orElseThrow(this::unavailable);
            // Even LATEST_USABLE is not authority to replace a browser-selected historical report.
            return reportProjection(userId,selection,explicitReport,
                    new ReportAttempt(id(monitored.id()),monitored.status(),monitored.createdAt(),false));
        }
        // Continuing an already frozen report never silently resolves 'latest' again.
        if (previous!=null && previous.reportId()!=null) {
            if (input.reportPolicy()!=null) throw invalid();
            return reportProjection(userId,selection,positive(previous.reportId()),previous.reportAttempt());
        }
        if (previous!=null && "NO_REPORT".equals(previous.projection().path("status").asText())) {
            // A no-report observation freezes the absence observed at that moment too. Only
            // an explicit request for the latest usable report may move past this barrier.
            if (input.reportPolicy()==null)
                return new Projected((ObjectNode)previous.projection().deepCopy(),null,previous.reportAttempt());
            if (!"LATEST_USABLE".equals(input.reportPolicy())) throw invalid();
        }
        var latest=resources.latestAttempt(userId,selection.resourceId()).orElse(null);
        var selected="LATEST_USABLE".equals(input.reportPolicy())
                ?resources.latestUsable(userId,selection.resourceId()).orElse(null):latest;
        boolean newerFailed=selected!=null && latest!=null && selected.runId()!=latest.runId()
                && FAILED_ATTEMPTS.contains(latest.status());
        var described=selected==null?latest:selected;
        ReportAttempt attempt=described==null?null:new ReportAttempt(id(described.runId()),described.status(),described.createdAt(),newerFailed);
        if (selected==null || selected.reportId()==null) {
            ObjectNode value=json.createObjectNode().put("status","NO_REPORT").put("readOnly",true)
                    .put("topicId",id(selection.resourceId())).put("topicName",selection.name()).put("reportRead",false);
            if (attempt!=null) value.set("latestAttempt",json.valueToTree(attempt));
            return new Projected(value,null,attempt);
        }
        if (!Set.of("SUCCEEDED","PARTIAL").contains(selected.status())) throw unavailable();
        return reportProjection(userId,selection,selected.reportId(),attempt);
    }

    private Projected reportProjection(long userId,Selection selection,long reportId,ReportAttempt attempt) {
        var saved=reports.findReport(reportId,userId).orElseThrow(this::unavailable);
        if (saved.topicId()!=selection.resourceId() || saved.archivedAt()!=null) throw unavailable();
        JsonNode context=geoContexts.create(userId,selection.spaceId(),selection.productId(),reportId);
        if (!"READY".equals(context.path("status").asText()) || !Long.toString(reportId).equals(context.at("/report/reportId").asText()))
            throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE);
        ObjectNode result=(ObjectNode)context.deepCopy();
        ArrayNode refs=result.putArray("sampleRefs");
        var samples=reports.findObservationPage(saved.runId(),0,7);
        for (var sample:samples.stream().limit(6).toList()) {
            if (sample.userId()!=userId || sample.runId()!=saved.runId()) throw unavailable();
            if (sample.unitKey()==null || sample.unitKey().isBlank() || sample.unitKey().length()>200) {
                result.put("truncated",true); continue;
            }
            refs.addObject().put("sampleRef",sample.unitKey()).put("question",sample.questionTextSnapshot())
                    .put("platform",sample.platformCode()).put("status",sample.status());
        }
        if (samples.size()>6) result.put("truncated",true);
        return new Projected(result,reportId,attempt);
    }

    private Projected evidence(Session session,Selection selection,ReadResponse previous,ReadRequest input) {
        long userId=session.run.userId();
        if (!"GEO_TOPIC".equals(selection.kind()) || previous==null || previous.reportId()==null
                || input.sampleRefs()==null || input.sampleRefs().isEmpty()) throw invalid();
        Long explicitReport=explicitReportId(session);
        if (explicitReport!=null && !id(explicitReport).equals(previous.reportId())) throw unavailable();
        var saved=reports.findReport(positive(previous.reportId()),userId).orElseThrow(this::unavailable);
        if (saved.topicId()!=selection.resourceId() || saved.archivedAt()!=null) throw unavailable();
        Set<String> issuedRefs=new HashSet<>();
        for(String field:List.of("sampleRefs","samples")) for(var sample:previous.projection().path(field))
            if(sample.path("sampleRef").isTextual()) issuedRefs.add(sample.path("sampleRef").asText());
        if(!issuedRefs.containsAll(input.sampleRefs())) throw unavailable();
        ObjectNode result=json.createObjectNode().put("readOnly",true).put("status","READY")
                .put("reportId",previous.reportId()); ArrayNode samples=result.putArray("samples");
        for (String ref:input.sampleRefs()) {
            GeoTaskRecords.Observation sample=reports.findObservationByUnit(saved.runId(),ref).orElseThrow(this::unavailable);
            if (sample.userId()!=userId || sample.runId()!=saved.runId()) throw unavailable();
            ObjectNode item=samples.addObject().put("sampleRef",ref).put("question",sample.questionTextSnapshot())
                    .put("platform",sample.platformCode()).put("status",sample.status()).put("answer",sample.answerText());
            ArrayNode citations=item.putArray("citations");
            var sources=reports.findCitations(sample.id());
            for (var source:sources.stream().limit(6).toList()) {
                if (source.userId()!=userId || source.runId()!=saved.runId()) throw unavailable();
                citations.addObject().put("title",source.title()).put("url",source.url()).put("domain",source.domain())
                        .put("snippet",source.snippet());
            }
            if (sources.size()>6) result.put("truncated",true);
        }
        return new Projected(result,positive(previous.reportId()),previous.reportAttempt());
    }

    private record Bounded(ObjectNode node,boolean truncated) { }
    private Bounded bound(ObjectNode original) {
        ObjectNode value=original.deepCopy(); boolean changed=false;
        for (int maxText:new int[] {1200,600,300,150,60}) {
            if (bytes(value)<=PROJECTION_BYTES) break;
            changed|=clip(value,"",maxText,maxText<=150?1:3);
            value.put("truncated",true);
        }
        if (bytes(value)>PROJECTION_BYTES) throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE);
        return new Bounded(value,changed);
    }
    private boolean clip(JsonNode node,String field,int maxText,int citationLimit) {
        boolean changed=false;
        if (node.isObject()) {
            // User-entered parameter maps have no required schema keys. Keep a bounded subset,
            // unlike the typed report object whose required fields and references must survive.
            if ("parameters".equals(field)) {
                var fields=new ArrayList<>(node.properties());
                for (int index=20;index<fields.size();index++) {
                    ((ObjectNode)node).remove(fields.get(index).getKey()); changed=true;
                }
            }
            for (var property:new ArrayList<>(node.properties())) {
                JsonNode child=property.getValue(); String name=property.getKey();
                boolean reference=name.endsWith("Id") || Set.of("id","sampleRef","unitKey","url","href").contains(name)
                        || name.endsWith("Path");
                if (child.isTextual() && !reference && child.textValue().codePointCount(0,child.textValue().length())>maxText) {
                    int end=child.textValue().offsetByCodePoints(0,maxText);
                    ((ObjectNode)node).put(name,child.textValue().substring(0,end)); changed=true;
                } else changed|=clip(child,name,maxText,citationLimit);
            }
        } else if (node.isArray()) {
            int limit="citations".equals(field)?citationLimit:
                    Set.of("sampleRefs","samples","items").contains(field)?("items".equals(field)?20:6):6;
            while (node.size()>limit) { ((ArrayNode)node).remove(node.size()-1); changed=true; }
            for (int index=0;index<node.size();index++) {
                JsonNode item=node.get(index);
                if (item.isTextual() && item.textValue().codePointCount(0,item.textValue().length())>maxText) {
                    int end=item.textValue().offsetByCodePoints(0,maxText);
                    ((ArrayNode)node).set(index,json.getNodeFactory().textNode(item.textValue().substring(0,end))); changed=true;
                } else changed|=clip(item,field,maxText,citationLimit);
            }
        }
        return changed;
    }

    private ReadRequest canonicalRead(ReadRequest value) {
        requireToolCall(value.toolCallId());
        if (!PURPOSES.contains(value.purpose())) throw invalid();
        boolean candidate=value.candidateSetId()!=null || value.choiceId()!=null;
        if (candidate==(value.observationId()!=null) || (candidate && (value.candidateSetId()==null
                || value.choiceId()==null || value.choiceId().isBlank() || value.choiceId().length()>128))) throw invalid();
        if (value.candidateSetId()!=null) positive(value.candidateSetId());
        if (value.observationId()!=null) positive(value.observationId());
        if (value.reportPolicy()!=null && (!"LATEST_GEO_REPORT".equals(value.purpose())
                || !Set.of("LATEST_ATTEMPT","LATEST_USABLE").contains(value.reportPolicy()))) throw invalid();
        if (value.sampleRefs()!=null && value.sampleRefs().stream().anyMatch(Objects::isNull)) throw invalid();
        List<String> refs=value.sampleRefs()==null?List.of():List.copyOf(value.sampleRefs());
        if (refs.size()>3 || new HashSet<>(refs).size()!=refs.size()
                || refs.stream().anyMatch(ref -> ref==null || ref.isBlank() || ref.length()>200)
                || (!refs.isEmpty() && !"GEO_ANSWER_EVIDENCE".equals(value.purpose()))) throw invalid();
        return new ReadRequest(value.toolCallId(),value.purpose(),value.candidateSetId(),value.choiceId(),
                value.observationId(),value.reportPolicy(),refs);
    }
    private void requireToolCall(String value) { if (value==null || value.isBlank() || value.length()>128) throw invalid(); }
    private UUID sourceId(UUID runId,String toolCallId) {
        return UUID.nameUUIDFromBytes(("zhixiaohe:context.resource:v1:"+runId+":"+toolCallId).getBytes(StandardCharsets.UTF_8));
    }
    private String scopeFingerprint(Long spaceId,Long productId) { return hash(json.createObjectNode()
            .put("spaceId",id(spaceId)).put("productId",id(productId))); }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))); }
        catch (Exception failure) { throw new IllegalStateException("Unable to hash resource data",failure); }
    }
    private int bytes(JsonNode value) { try { return json.writeValueAsBytes(value).length; }
        catch (Exception failure) { throw new IllegalStateException("Unable to size resource projection",failure); } }
    private String write(Object value) { try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Unable to encode resource receipt",failure); } }
    private JsonNode readTree(String value) { try { return json.readTree(value); }
        catch (Exception failure) { throw new IllegalStateException("Invalid resource receipt",failure); } }
    private <T> T decode(JsonNode value,Class<T> type) { try { return json.treeToValue(value,type); }
        catch (Exception failure) { throw new IllegalStateException("Invalid resource result",failure); } }
    private long positive(String value) { try { if (value==null || !value.matches("^[1-9][0-9]{0,18}$")) throw invalid();
            long parsed=Long.parseLong(value); if (parsed<=0) throw invalid(); return parsed;
        } catch (NumberFormatException invalid) { throw invalid(); } }
    private Long optionalId(String value) { return value==null?null:positive(value); }
    private String id(Long value) { return value==null?null:Long.toString(value); }
    private OffsetDateTime now() { return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    private BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
}
