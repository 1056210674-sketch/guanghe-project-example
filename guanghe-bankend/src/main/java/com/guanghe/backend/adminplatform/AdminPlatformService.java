package com.guanghe.backend.adminplatform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.OperationalAuditService;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.geo.GeoPlanService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.agent-platform", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdminPlatformService {
    private static final Set<String> DEFINITION_STATUSES = Set.of("PUBLISHED", "DISABLED", "WITHDRAWN");
    private static final Set<String> VISIBILITIES = Set.of("PUBLIC", "INTERNAL");
    private final AdminPlatformMapper mapper;
    private final ObjectProvider<GeoPlanService> plans;
    private final ObjectProvider<OperationalAuditService> audits;
    private final ObjectMapper json;

    public AdminPlatformService(AdminPlatformMapper mapper, ObjectProvider<GeoPlanService> plans,
            ObjectProvider<OperationalAuditService> audits, ObjectMapper json) {
        this.mapper = mapper; this.plans = plans; this.audits = audits; this.json = json;
    }

    @Transactional(readOnly = true)
    public List<AdminPlatformDtos.AgentDefinitionView> definitions(AuthenticatedAccount account) {
        requireAdmin(account);
        return mapper.definitions();
    }

    @Transactional
    public AdminPlatformDtos.AgentDefinitionView updateDefinition(AuthenticatedAccount account, String key, String version,
            AdminPlatformDtos.UpdateDefinitionRequest request) {
        long adminId = requireAdmin(account);
        if (key == null || !key.matches("^[a-z][a-z0-9-]{0,63}$") || version == null
                || version.length() > 32 || !version.matches("^[0-9]+\\.[0-9]+\\.[0-9]+$") || request == null
                || request.expectedStatus() == null || !DEFINITION_STATUSES.contains(request.expectedStatus())
                || request.expectedVisibility() == null || !VISIBILITIES.contains(request.expectedVisibility())
                || request.definitionHash() == null || !request.definitionHash().matches("^[0-9a-f]{64}$")
                || (request.status() == null && request.visibility() == null)
                || (request.status() != null && !DEFINITION_STATUSES.contains(request.status()))
                || (request.visibility() != null && !VISIBILITIES.contains(request.visibility()))) throw invalid();
        var audit = audit();
        var current = mapper.lockDefinition(key, version).orElseThrow(this::notFound);
        if (!current.status().equals(request.expectedStatus()) || !current.visibility().equals(request.expectedVisibility())
                || !current.definitionHash().equals(request.definitionHash())) throw conflict();
        String nextStatus = request.status() == null ? current.status() : request.status();
        String nextVisibility = request.visibility() == null ? current.visibility() : request.visibility();
        // Installation/execution supports these immutable, built-in adapters only. Governance cannot upload code.
        if ("PUBLISHED".equals(nextStatus) && (!Set.of("geo", "summary").contains(key)
                || !"1.0.0".equals(version) || !key.equals(current.adapterKey()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (nextStatus.equals(current.status()) && nextVisibility.equals(current.visibility())) return current;
        if (mapper.updateDefinition(key, version, current.definitionHash(), current.status(), current.visibility(),
                nextStatus, nextVisibility) != 1) throw conflict();
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, adminId, "AGENT_DEFINITION", null,
                "AGENT_DEFINITION_GOVERNANCE_CHANGED", OperationalAuditService.OUTCOME_SUCCESS,
                details(Map.of("agentKey", key, "version", version, "definitionHash", current.definitionHash(),
                        "fromStatus", current.status(), "toStatus", nextStatus,
                        "fromVisibility", current.visibility(), "toVisibility", nextVisibility)));
        return new AdminPlatformDtos.AgentDefinitionView(current.agentKey(), current.version(), current.name(), current.adapterKey(),
                nextVisibility, nextStatus, current.definitionHash(), current.publishedAt(), current.installationCount());
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminPlatformDtos.PlanView> plans(AuthenticatedAccount account, Long userId, Long projectId,
            String status, int page, int size) {
        requireAdmin(account); filters(userId, projectId, page, size);
        if (status != null && !Set.of("ACTIVE", "PAUSED").contains(status)) throw invalid();
        return PageResponse.of(mapper.plans(userId, projectId, status, offset(page, size), size), page, size,
                mapper.planCount(userId, projectId, status));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminPlatformDtos.PlanSlotView> slots(AuthenticatedAccount account, long id, int page, int size) {
        requireAdmin(account); filters(null, null, page, size);
        requirePlan(id);
        return PageResponse.of(mapper.slots(id, offset(page, size), size), page, size, mapper.slotCount(id));
    }

    @Transactional
    public AdminPlatformDtos.PlanView pause(AuthenticatedAccount account, long id, AdminPlatformDtos.PausePlanRequest request) {
        long adminId = requireAdmin(account);
        if (request == null || request.version() == null || request.version() < 1 || request.reason() == null
                || request.reason().isBlank() || request.reason().length() > 200
                || request.reason().chars().anyMatch(Character::isISOControl)) throw invalid();
        var audit = audit();
        var planService = planService();
        var before = requirePlan(id);
        String reason = request.reason().strip();
        planService.adminPause(adminId, id, request.version(), reason);
        var result = requirePlan(id);
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, adminId, "GEO_PLAN", id,
                "GEO_PLAN_ADMIN_PAUSED", OperationalAuditService.OUTCOME_SUCCESS,
                details(Map.of("ownerUserId", before.userId(), "expectedVersion", request.version(), "resultVersion", result.version(),
                        "fromStatus", before.status(), "toStatus", result.status(), "reason", reason,
                        "inFlightTasksCancelled", false)));
        return result;
    }

    @Transactional
    public AdminPlatformDtos.PlanView resume(AuthenticatedAccount account, long id, AdminPlatformDtos.ResumePlanRequest request) {
        long adminId = requireAdmin(account);
        if (request == null || request.version() == null || request.version() < 1) throw invalid();
        var audit = audit();
        var planService = planService();
        var before = requirePlan(id);
        // The dedicated seam revalidates original owner/account/project/installation, price and authorization.
        planService.adminResume(adminId, id, request.version());
        var result = requirePlan(id);
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, adminId, "GEO_PLAN", id,
                "GEO_PLAN_ADMIN_RESUMED", OperationalAuditService.OUTCOME_SUCCESS,
                details(Map.of("ownerUserId", before.userId(), "expectedVersion", request.version(), "resultVersion", result.version(),
                        "fromStatus", before.status(), "toStatus", result.status(), "pausedSlotsBackfilled", false)));
        return result;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminPlatformDtos.KnowledgeDocumentMetadata> documents(AuthenticatedAccount account,
            Long userId, Long projectId, int page, int size) {
        requireAdmin(account); filters(userId, projectId, page, size);
        return PageResponse.of(mapper.documents(userId, projectId, offset(page, size), size), page, size,
                mapper.documentCount(userId, projectId));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminPlatformDtos.MemoryPreferenceMetadata> preferences(AuthenticatedAccount account,
            Long userId, Long projectId, int page, int size) {
        requireAdmin(account); filters(userId, projectId, page, size);
        return PageResponse.of(mapper.preferences(userId, projectId, offset(page, size), size), page, size,
                mapper.preferenceCount(userId, projectId));
    }

    @Transactional(readOnly = true)
    public AdminPlatformDtos.MetadataSummary summary(AuthenticatedAccount account, Long userId, Long projectId) {
        requireAdmin(account); filters(userId, projectId, 1, 1);
        return mapper.metadataSummary(userId, projectId);
    }

    private long requireAdmin(AuthenticatedAccount account) {
        if (account == null || account.role() != AccountRole.ADMIN || account.status() != AccountStatus.ACTIVE
                || !"ACTIVE".equals(account.credentialState())) throw new BusinessException(ErrorCode.FORBIDDEN);
        return account.id();
    }
    private void filters(Long userId, Long projectId, int page, int size) {
        if ((userId != null && userId < 1) || (projectId != null && projectId < 1)
                || page < 1 || size < 1 || size > 100) throw invalid();
    }
    private long offset(int page, int size) { return (long) (page - 1) * size; }
    private AdminPlatformDtos.PlanView requirePlan(long id) {
        if (id < 1) throw invalid();
        return mapper.plan(id).orElseThrow(this::notFound);
    }
    private GeoPlanService planService() {
        var value = plans.getIfAvailable();
        if (value == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        return value;
    }
    private OperationalAuditService audit() {
        var value = audits.getIfAvailable();
        if (value == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        return value;
    }
    private String details(Map<String, Object> values) {
        try { return json.writeValueAsString(values); }
        catch (JsonProcessingException exception) { throw invalid(); }
    }
    private BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
    private BusinessException conflict() { return new BusinessException(ErrorCode.TASK_STATE_CONFLICT); }
    private BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
