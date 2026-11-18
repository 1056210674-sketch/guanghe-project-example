package com.guanghe.backend.conversation.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.agentplatform.AgentPlatformService;
import com.guanghe.backend.agentplatform.AgentRuntimeCatalog;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.*;
import com.guanghe.backend.knowledge.AgentMemoryService;
import com.guanghe.backend.knowledge.MemoryDtos;
import com.guanghe.backend.knowledge.KnowledgeMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable model-only history checkpoints. Never changes chat messages or grants business authority. */
@Service
@ConditionalOnProperty(prefix="zhixiaohe.agent-platform", name="enabled", havingValue="true", matchIfMissing=true)
public class ConversationCompactionService {
    private final ConversationMapper conversations;
    private final ConversationCompactionMapper checkpoints;
    private final RunContextService contexts;
    private final AgentPlatformService agents;
    private final AgentMemoryService memories;
    private final ConversationWorkspaceService workspaces;
    private final ObjectMapper json;
    private final KnowledgeMapper knowledge;
    private AgentRuntimeCatalog runtimeCatalog = new AgentRuntimeCatalog();
    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeCatalog(AgentRuntimeCatalog value) { runtimeCatalog=Objects.requireNonNull(value); }
    public ConversationCompactionService(ConversationMapper conversations, ConversationCompactionMapper checkpoints,
            RunContextService contexts, AgentPlatformService agents, AgentMemoryService memories,
            ConversationWorkspaceService workspaces, ObjectMapper json, KnowledgeMapper knowledge) {
        this.conversations=conversations; this.checkpoints=checkpoints; this.contexts=contexts;
        this.agents=agents; this.memories=memories; this.workspaces=workspaces; this.json=json; this.knowledge=knowledge;
    }
    @Transactional
    public ConversationCompactionDtos.Source source(UUID runId, String ref, ConversationCompactionDtos.SourceRequest request) {
        return build(authorize(runId,ref,false),request);
    }
    @Transactional
    public ConversationCompactionDtos.Source commit(UUID runId, String ref, ConversationCompactionDtos.CommitRequest request) {
        var run=authorize(runId,ref,true);
        var options=new ConversationCompactionDtos.SourceRequest(request.maxSourceChars(),request.force());
        var current=build(run,options);
        // Exact replay after a lost response is harmless; a changed summary with the same source is not.
        var persisted=tree(conversations.findContextMetadata(run.conversationId(),run.userId()).orElseThrow(this::missing).summaryJson());
        if(current.version()==request.expectedVersion()+1
                && request.sourceHash().equals(persisted.path("sourceHash").asText())
                && request.summary().strip().equals(persisted.path("text").asText())
                && current.throughSequence()==request.throughSequence()) return current;
        if(current.version()!=request.expectedVersion() || !current.needsCompaction()
                || current.sourceThroughSequence()!=request.throughSequence()
                || !current.sourceHash().equals(request.sourceHash())) throw conflict();
        var value=json.createObjectNode().put("schemaVersion",1).put("text",request.summary().strip())
                .put("throughSequence",request.throughSequence()).put("sourceHash",request.sourceHash())
                .put("memoryFence",fence(current.memory())).put("memoryRevision",current.memory().revision())
                .put("runId",run.id().toString());
        if(checkpoints.checkpoint(run.conversationId(),run.userId(),request.expectedVersion(),value.toString())!=1) throw conflict();
        workspaces.invalidateAfterCommit(run.userId(),run.conversationId());
        return build(run,options);
    }
    private AgentRunRecord authorize(UUID runId, String ref, boolean lock) {
        var context=contexts.get(runId,ref);
        var run=conversations.findRun(runId).orElseThrow(this::missing);
        knowledge.lockAccountForPreferenceWrite(run.userId()).orElseThrow(this::missing);
        if(lock) {
            // Same account -> Run -> conversation lock order as memory/actions; prevents a
            // correction racing between the source-revision check and the checkpoint write.
            run=conversations.lockRun(runId).orElseThrow(this::missing);
        }
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        if(!Set.of("QUEUED","STARTING","RUNNING").contains(run.status()) || run.stopRequestedAt()!=null
                || !now.isBefore(run.contextExpiresAt()) || run.deadlineAt()!=null&&!now.isBefore(run.deadlineAt())) throw missing();
        var agent=runtimeCatalog.authorize(context.agentIdentity(),
                agents.assertTaskConfirmationAllowed(run.userId(),run.conversationId()));
        if(!agent.capabilities().containsAll(List.of("chat","resources.read","memory.read"))) throw missing();
        return run;
    }
    private ConversationCompactionDtos.Source build(AgentRunRecord run, ConversationCompactionDtos.SourceRequest options) {
        var current=conversations.findMessage(run.requestMessageId(),run.userId(),run.conversationId()).orElseThrow(this::missing);
        var identity=agents.assertTaskConfirmationAllowed(run.userId(),run.conversationId());
        var conversation=conversations.findConversation(run.conversationId(),run.userId()).orElseThrow(this::missing);
        Long project=identity.projectId()==null?conversation.selectedSpaceId():Long.valueOf(identity.projectId());
        if(run.selectedSpaceId()!=null && !Objects.equals(run.selectedSpaceId(),conversation.selectedSpaceId())) throw conflict();
        var memory=memories.context(run.userId(),project);
        var metadata=conversations.findContextMetadata(run.conversationId(),run.userId()).orElseThrow(this::missing);
        var checkpoint=tree(metadata.summaryJson());
        boolean usable=fence(memory).equals(checkpoint.path("memoryFence").asText());
        long through=usable?checkpoint.path("throughSequence").asLong(0):0;
        String summary=usable?checkpoint.path("text").asText(""):"";
        // Only terminal persisted messages strictly before this USER are eligible. A checkpoint
        // never consumes a live assistant placeholder or current user request.
        var all=checkpoints.sourceMessages(run.userId(),run.conversationId(),through,
                current.sequenceNo(),memory.historyCutoffAt()).stream().map(this::message).toList();
        int total=all.stream().mapToInt(m->json.valueToTree(m).toString().length()).sum();
        int boundary=Math.max(0,all.size()-6);
        while(boundary>0 && !"USER".equals(all.get(boundary).role())) boundary--;
        // A few unusually large turns also need summarizing. All historical messages are
        // already terminal; the current USER and live task/authorization state stay outside.
        if(boundary==0 && !all.isEmpty() && (options.force() || total>options.maxSourceChars())) boundary=all.size();
        boolean needed=boundary>0 && (options.force() || total>options.maxSourceChars() || all.size()==256);
        List<InternalRunContextDtos.MemoryMessage> batch=new ArrayList<>();
        int characters=0;
        if(needed) {
            for(int i=0;i<boundary;i++) {
                var item=all.get(i);
                int size=json.valueToTree(item).toString().length();
                // Persisted history is role/sequence-labelled DATA, not a live native tool
                // protocol. Whole messages can cross checkpoints; forcing a USER/ASSISTANT
                // pair into one batch would permanently block two otherwise valid 16k texts.
                // Always admit one whole message; Python's actual model-token budget decides
                // whether that individual message fits. Never clip its content silently.
                if(!batch.isEmpty() && characters+size>options.maxSourceChars()) break;
                batch.add(item); characters+=size;
            }
        }
        long target=batch.isEmpty()?through:batch.getLast().sequence();
        // The hash binds exact messages, revision and prior checkpoint. A concurrent correction,
        // deletion, or another successful writer cannot publish an obsolete summary.
        String hash=hash(metadata.summaryVersion()+":"+through+":"+memory.revision()+":"+summary+":"+json.valueToTree(batch));
        return new ConversationCompactionDtos.Source(metadata.summaryVersion(),through,summary,hash,target,!batch.isEmpty(),
                !batch.isEmpty()&&target<all.get(boundary-1).sequence(),List.copyOf(batch),
                needed?List.of():all,memory);
    }
    private InternalRunContextDtos.MemoryMessage message(ConversationMessageRecord m) {
        return new InternalRunContextDtos.MemoryMessage(Long.toString(m.id()),m.sequenceNo(),m.role(),tree(m.contentJson()),
                m.createdAt(),m.replyToMessageId()==null?null:m.replyToMessageId().toString());
    }
    private String fence(MemoryDtos.Context memory) { return memory.projectId()+":"+memory.historyCutoffAt(); }
    private JsonNode tree(String value) { try { return json.readTree(value==null?"{}":value); } catch(Exception e) { throw new IllegalStateException("Invalid compaction state",e); } }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private BusinessException missing() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    private BusinessException conflict() { return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT); }
}
