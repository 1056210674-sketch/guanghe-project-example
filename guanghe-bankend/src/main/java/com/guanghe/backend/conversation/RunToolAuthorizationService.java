package com.guanghe.backend.conversation;

import com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity;
import com.guanghe.backend.agentplatform.AgentPlatformService;
import com.guanghe.backend.agentplatform.AgentRuntimeCatalog;
import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.context.InternalRunContextDtos.RunContext;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Live authorization shared by native actions and resource tools, before any receipt replay.
 * Callers must load the signed Context through RunContextService and invoke this within their
 * existing transaction. This service deliberately adds no transaction/rollback policy: resource
 * tools commit controlled failure receipts, whereas mutating actions roll back business failures.
 * Domain ownership, original USER intent, budgets and idempotency remain with those callers.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RunToolAuthorizationService {
    private static final Set<String> ACTIVE = Set.of("QUEUED", "STARTING", "RUNNING");
    private final ConversationMapper conversations;
    private final AccountMapper accounts;
    private final Optional<AgentPlatformService> platform;
    private final AgentRuntimeCatalog catalogue;

    public RunToolAuthorizationService(ConversationMapper conversations, AccountMapper accounts,
            Optional<AgentPlatformService> platform, AgentRuntimeCatalog catalogue) {
        this.conversations = conversations;
        this.accounts = accounts;
        this.platform = platform;
        this.catalogue = catalogue;
    }

    public Authorized authorize(UUID runId, RunContext context, String tool, Clock clock) {
        var initial = conversations.findRun(runId).orElseThrow(this::unavailable);
        var account = accounts.findIdentityById(initial.userId()).orElseThrow(this::unavailable);
        if (account.role() != AccountRole.USER || account.status() != AccountStatus.ACTIVE
                || !"ACTIVE".equals(account.credentialState())
                || conversations.lockAdmissionAccount(account.id(), account.tokenVersion()) == null) throw unavailable();
        // The account row lock rechecks its current state/token. Keep account -> Run ->
        // installation order, before callers take wallet/conversation locks or receipt FKs.
        var run = conversations.lockOwnedRun(runId, initial.userId(), initial.conversationId())
                .orElseThrow(this::unavailable);
        requireLive(run, clock);
        var current = platform.orElseThrow(this::unavailable)
                .assertTaskConfirmationAllowed(run.userId(), run.conversationId());
        if (context == null || context.agentIdentity() == null) throw unavailable();
        // Never re-expand current grants here: a revoked capability must stay revoked.
        var agent = catalogue.authorize(context.agentIdentity(), current);
        if (!catalogue.permits(agent, tool)) throw new BusinessException(ErrorCode.FORBIDDEN);
        // Installation/definition SHARE locks can wait after the first lease check.
        requireLive(run, clock);
        return new Authorized(run, AuthenticatedAccount.from(account), agent);
    }

    /** Recheck after a caller acquires another lock; lock waiting must not extend a deadline. */
    public void requireLive(AgentRunRecord lockedRun, Clock clock) {
        var now = OffsetDateTime.now(clock);
        if (!ACTIVE.contains(lockedRun.status()) || lockedRun.stopRequestedAt() != null
                || !now.isBefore(lockedRun.contextExpiresAt())
                || lockedRun.deadlineAt() != null && !now.isBefore(lockedRun.deadlineAt())) throw unavailable();
    }

    public record Authorized(AgentRunRecord run, AuthenticatedAccount account, AgentIdentity agent) { }

    private BusinessException unavailable() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
