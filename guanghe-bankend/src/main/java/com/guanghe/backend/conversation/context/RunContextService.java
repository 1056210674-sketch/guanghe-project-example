package com.guanghe.backend.conversation.context;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RunContextService {

    private static final Set<String> AVAILABLE_STATUSES = Set.of("QUEUED", "STARTING", "RUNNING");

    private final ConversationMapper conversationMapper;
    private final ObjectReader snapshotReader;
    private com.guanghe.backend.knowledge.KnowledgeService knowledgeService;
    private com.guanghe.backend.agentplatform.AgentRuntimeCatalog runtimeCatalog = new com.guanghe.backend.agentplatform.AgentRuntimeCatalog();
    private com.guanghe.backend.agentplatform.AgentPlatformService agentPlatform;
    private com.guanghe.backend.auth.account.AccountMapper accounts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAgentPlatform(com.guanghe.backend.agentplatform.AgentPlatformService value) { agentPlatform=value; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAccounts(com.guanghe.backend.auth.account.AccountMapper value) { accounts=value; }

    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeCatalog(com.guanghe.backend.agentplatform.AgentRuntimeCatalog value) { runtimeCatalog = java.util.Objects.requireNonNull(value); }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setKnowledgeService(com.guanghe.backend.knowledge.KnowledgeService value) { knowledgeService = value; }

    public RunContextService(
            ConversationMapper conversationMapper,
            ObjectMapper objectMapper
    ) {
        this.conversationMapper = conversationMapper;
        ObjectMapper strictMapper = objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.snapshotReader = strictMapper.readerFor(InternalRunContextDtos.RunContext.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    // Read-only admission refusals must not poison a caller that persists a controlled error receipt.
    // Write callers retain their own rollback policy; infrastructure exceptions still roll back.
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public InternalRunContextDtos.RunContext get(UUID runId, String contextRef) {
        AgentRunRecord run = conversationMapper.findRun(runId).orElseThrow(this::unavailable);
        if (!AVAILABLE_STATUSES.contains(run.status())
                || run.stopRequestedAt()!=null
                || run.deadlineAt()!=null && !OffsetDateTime.now(ZoneOffset.UTC).isBefore(run.deadlineAt())
                || contextRef == null
                || contextRef.isBlank()
                || contextRef.length() > 512
                || !OffsetDateTime.now(ZoneOffset.UTC).isBefore(run.contextExpiresAt())
                || !matches(run.contextRefHash(), contextRef)) {
            throw unavailable();
        }
        if(accounts==null) throw unavailable();
        var account=accounts.findIdentityById(run.userId()).orElseThrow(this::unavailable);
        if(account.role()!=com.guanghe.backend.auth.account.AccountRole.USER
                || account.status()!=com.guanghe.backend.auth.account.AccountStatus.ACTIVE
                || !"ACTIVE".equals(account.credentialState())) throw unavailable();

        try {
            InternalRunContextDtos.RunContext snapshot = snapshotReader.readValue(run.contextSnapshotJson());
            if (!run.id().toString().equals(snapshot.runId())
                    || !Long.toString(run.conversationId()).equals(snapshot.conversationId())
                    || run.contextVersion() != snapshot.contextVersion()) {
                throw unavailable();
            }
            if(agentPlatform==null && snapshot.agentIdentity()!=null) throw unavailable();
            // Identity-less snapshots predate agent bindings; when the platform is enabled its
            // read check explicitly rejects omission for any already-bound conversation.
            var current=agentPlatform==null?null:agentPlatform.assertContextReadAllowed(run.userId(),run.conversationId(),snapshot.agentIdentity()==null);
            var identity = snapshot.agentIdentity() == null ? current : runtimeCatalog.authorize(snapshot.agentIdentity(),current);
            if(identity!=null && identity.projectId()!=null
                    && ((run.selectedSpaceId()!=null && !identity.projectId().equals(run.selectedSpaceId().toString()))
                        || snapshot.space()!=null && !identity.projectId().equals(snapshot.space().id()))) throw unavailable();
            // Returning an older projection after any frozen grant is revoked risks leaking
            // already-materialized data. Refuse the whole snapshot instead of guessing fields.
            if(snapshot.agentIdentity()!=null && !identity.capabilities().containsAll(
                    runtimeCatalog.validateFrozen(snapshot.agentIdentity()).capabilities())) throw unavailable();
            boolean memoryReadable = identity == null || identity.capabilities().contains("memory.read");
            if(identity!=null && !identity.capabilities().contains("resources.read")) {
                if(snapshot.knowledgeContext()!=null || snapshot.space()!=null || snapshot.product()!=null
                        || !snapshot.competitors().isEmpty() || !snapshot.attachmentAssetIds().isEmpty()
                        || snapshot.geoInsightContext()!=null || snapshot.lastReadContext()!=null
                        || snapshot.lastReportReadContext()!=null || snapshot.pendingResourceContext()!=null
                        || snapshot.resourceSelection()!=null) throw unavailable();
                return snapshot;
            }
            if (knowledgeService != null && snapshot.knowledgeContext() != null) {
                Long projectId = snapshot.agentIdentity() == null || snapshot.agentIdentity().projectId() == null
                        ? null : Long.valueOf(snapshot.agentIdentity().projectId());
                if(projectId==null && snapshot.space()!=null && snapshot.attachmentAssetIds().isEmpty())
                    projectId=Long.valueOf(snapshot.space().id());
                var knowledge = knowledgeService.revalidateFrozenContext(run.userId(), projectId, snapshot.knowledgeContext());
                if (knowledge != null && !memoryReadable) knowledge = knowledge.withoutMemory();
                return snapshot.withKnowledge(knowledge).enforceMemoryBoundary(Long.toString(run.requestMessageId()));
            }
            if (!memoryReadable && snapshot.knowledgeContext() != null)
                return snapshot.withKnowledge(snapshot.knowledgeContext().withoutMemory());
            return snapshot;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private boolean matches(String expectedHash, String contextRef) {
        byte[] actual = sha256(contextRef.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII), actual);
    }

    private byte[] sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
                    .getBytes(StandardCharsets.US_ASCII);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
