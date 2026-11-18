package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.agent-platform", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentPlatformService {
    public static final String LEGACY_AGENT_KEY = "geo";
    public static final String LEGACY_AGENT_VERSION = "1.0.0";
    private AgentRuntimeCatalog runtimeCatalog = new AgentRuntimeCatalog();
    private final AgentPlatformMapper mapper;
    private final AgentPlatformProperties properties;
    private final SnowflakeIdGenerator ids;
    private final CatalogNameNormalizer names;
    private final ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeCatalog(AgentRuntimeCatalog value) { runtimeCatalog = Objects.requireNonNull(value); }

    public AgentRuntimeCatalog.Entry runtime(AgentPlatformDtos.AgentIdentity identity) { return runtimeCatalog.require(identity); }

    public boolean supportsAction(AgentPlatformDtos.AgentIdentity identity, String action) {
        return runtime(identity).explicitActions().contains(action);
    }

    public boolean supportsPersonalCreation(AgentPlatformDtos.AgentIdentity identity) {
        return identity.capabilities().contains("image.generate") || identity.capabilities().contains("video.generate");
    }

    public AgentPlatformService(AgentPlatformMapper mapper, AgentPlatformProperties properties,
                                SnowflakeIdGenerator ids, CatalogNameNormalizer names, ObjectMapper json) {
        this.mapper = mapper;
        this.properties = properties;
        this.ids = ids;
        this.names = names;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<AgentPlatformDtos.AgentDefinitionView> catalogue(AuthenticatedAccount account) {
        long userId = CatalogAccess.requireUser(account);
        List<AgentPlatformRecords.Installation> installed = mapper.installations(userId);
        Set<String> seen = new HashSet<>();
        List<AgentPlatformDtos.AgentDefinitionView> result = new ArrayList<>();
        for (var definition : mapper.definitions()) {
            if (!visible(definition, userId)) continue;
            boolean latest = seen.add(definition.agentKey());
            var installation = installed.stream().filter(value -> value.agentKey().equals(definition.agentKey())
                            && value.agentVersion().equals(definition.version()))
                    .findFirst().orElse(null);
            // Keep the latest card first, plus the exact installed version used by chat/asset
            // permission checks. A new release must not lend its status or capabilities to an old install.
            if (!latest && installation == null) continue;
            if ("WITHDRAWN".equals(definition.status()) && installation == null) continue;
            result.add(new AgentPlatformDtos.AgentDefinitionView(definition.agentKey(), definition.version(),
                    definition.name(), definition.description(), effectiveCapabilities(definition.agentKey(),definition.version(),definition.capabilitiesJson()),
                    installation == null ? null : Long.toString(installation.id()), definition.status()));
        }
        return List.copyOf(result);
    }

    /** Storage only: never bind this internal scope to a project or include it in brand memory. */
    @Transactional
    public long ensurePersonalCreativeStorage(long userId) {
        return mapper.personalCreativeStorage(userId).orElseGet(()->{
            long id=ids.nextId();
            mapper.insertPersonalCreativeStorage(id,userId,"__personal_creative_"+id,now());
            return mapper.personalCreativeStorage(userId).orElseThrow(this::conflict);
        });
    }

    @Transactional
    public AgentPlatformDtos.CreativeStorageView creativeStorage(AuthenticatedAccount account,long installationId) {
        long userId=CatalogAccess.requireUser(account);
        var installed=ownedInstallation(userId,installationId);
        if(!installed.enabled()||installed.deletedAt()!=null)
            throw new BusinessException(ErrorCode.FORBIDDEN);
        var definition=definition(installed,true);
        requireRunnable(definition,userId);
        var capabilities=effectiveCapabilities(definition.agentKey(),definition.version(),definition.capabilitiesJson());
        if(!capabilities.contains("image.generate")&&!capabilities.contains("video.generate"))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        return new AgentPlatformDtos.CreativeStorageView(Long.toString(ensurePersonalCreativeStorage(userId)));
    }

    public boolean isPersonalCreativeStorage(long userId,long spaceId) {
        return mapper.personalCreativeStorage(userId).map(value->value==spaceId).orElse(false);
    }

    @Transactional(readOnly = true)
    public List<AgentPlatformDtos.AgentInstallationView> installations(AuthenticatedAccount account) {
        long userId = CatalogAccess.requireUser(account);
        return mapper.installations(userId).stream().filter(value -> visible(definition(value, false), userId))
                .map(this::installationView).toList();
    }

    @Transactional
    public AgentPlatformDtos.AgentInstallationView addInstallation(AuthenticatedAccount account,
                                                                  AgentPlatformDtos.AddInstallationRequest request) {
        long userId = CatalogAccess.requireUser(account);
        if (request == null || request.agentKey() == null || !request.agentKey().matches("^[a-z][a-z0-9-]{0,63}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return installationView(getOrAddInstallation(userId, request.agentKey()));
    }

    @Transactional
    public AgentPlatformDtos.AgentInstallationView updateInstallation(AuthenticatedAccount account, long id,
                                                                     AgentPlatformDtos.UpdateInstallationRequest request) {
        long userId = CatalogAccess.requireUser(account);
        var current = ownedInstallation(userId, id);
        if (current.deletedAt() != null) throw notFound();
        if (request == null || request.version() == null || request.version() < 1 || request.enabled() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (current.version() != request.version()) throw conflict();
        if (request.enabled()) requireRunnable(definition(current, true), userId);
        if (current.enabled() == request.enabled()) return installationView(current);
        OffsetDateTime now = now();
        if (mapper.updateInstallation(id, userId, request.version(), request.enabled(), now) != 1) throw conflict();
        return installationView(new AgentPlatformRecords.Installation(current.id(), userId, current.agentKey(),
                current.agentVersion(), request.enabled(), current.version() + 1, current.createdAt(), now, null));
    }

    @Transactional
    public void removeInstallation(AuthenticatedAccount account, long id) {
        long userId = CatalogAccess.requireUser(account);
        var current = ownedInstallation(userId, id);
        if (current.deletedAt() == null && mapper.tombstoneInstallation(id, userId, now()) != 1) throw conflict();
    }

    @Transactional(readOnly = true)
    public List<AgentPlatformDtos.ProjectView> projects(AuthenticatedAccount account) {
        return mapper.projects(CatalogAccess.requireUser(account)).stream().map(this::projectView).toList();
    }

    @Transactional
    public AgentPlatformDtos.ProjectView createProject(AuthenticatedAccount account,
                                                     AgentPlatformDtos.CreateProjectRequest request) {
        long userId = CatalogAccess.requireUser(account);
        if (request == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        AliasValue name = names.requiredName(request.name(), 100, "name");
        OffsetDateTime now = now();
        var project = new AgentPlatformRecords.Project(ids.nextId(), userId, name.value(), name.normalized(),
                "ACTIVE", 1, now, now);
        try {
            if (mapper.insertProject(project) != 1) throw conflict();
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        return projectView(project);
    }

    @Transactional
    public AgentPlatformDtos.ProjectView updateProject(AuthenticatedAccount account, long id,
            AgentPlatformDtos.UpdateProjectRequest request) {
        long userId = CatalogAccess.requireUser(account);
        if (request == null || request.version() == null || request.version() < 1
                || (request.name() == null && request.status() == null)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var current = mapper.lockProjectForUpdate(userId, id).orElseThrow(this::notFound);
        if (current.version() != request.version()) throw conflict();
        AliasValue name = request.name() == null ? new AliasValue(current.name(), current.nameNormalized())
                : names.requiredName(request.name(), 100, "name");
        String status = request.status() == null ? current.status() : request.status();
        if (!Set.of("ACTIVE", "ARCHIVED").contains(status)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if (current.name().equals(name.value()) && current.status().equals(status)) return projectView(current);
        OffsetDateTime now = now();
        try {
            if (mapper.updateProject(userId, id, request.version(), name.value(), name.normalized(), status, now) != 1) throw conflict();
        } catch (DuplicateKeyException exception) { throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT); }
        return projectView(new AgentPlatformRecords.Project(id, userId, name.value(), name.normalized(), status,
                current.version() + 1, current.createdAt(), now));
    }

    /** No implicit installation or identity rewrite. Used to revalidate explicitly configured new hourly plans. */
    @Transactional(readOnly = true)
    public boolean canUseInstalledAgent(long userId, String agentKey, Long projectId) {
        var installation = mapper.activeInstallation(userId, agentKey).orElse(null);
        if (installation == null || !installation.enabled() || installation.deletedAt() != null) return false;
        var definition = mapper.definition(installation.agentKey(), installation.agentVersion()).orElse(null);
        if (definition == null || !"PUBLISHED".equals(definition.status()) || !visible(definition, userId)
                || !runtimeCatalog.supports(definition)) return false;
        if (projectId == null) return true;
        return mapper.project(userId, projectId).filter(project -> "ACTIVE".equals(project.status())).isPresent();
    }

    /** Called in the owning conversation transaction. Explicit selections can never rewrite a binding. */
    @Transactional
    public AgentPlatformDtos.AgentIdentity bindConversation(long userId, long conversationId,
                                                            Long installationId, Long projectId) {
        requireOwnedConversationLocked(userId, conversationId);
        var existing = mapper.binding(userId, conversationId).orElse(null);
        if (existing != null) {
            if ((installationId != null && installationId != existing.installationId())
                    || (projectId != null && !Objects.equals(projectId, existing.projectId()))) throw conflict();
            return identity(existing);
        }
        var installation = installationId == null
                ? getOrAddInstallation(userId, LEGACY_AGENT_KEY) : admittedInstallation(userId, installationId);
        // Lock an auto-created/reused installation too, so disable/remove cannot race admission.
        installation = admittedInstallation(userId, installation.id());
        requireEnabled(installation);
        var definition = definition(installation, true);
        requireRunnable(definition, userId);
        requireActiveProject(userId, projectId);
        var binding = new AgentPlatformRecords.Binding(conversationId, userId, installation.id(),
                definition.agentKey(), definition.version(), definition.name(), projectId,
                definition.capabilitiesJson(), now());
        if (mapper.insertBinding(binding) != 1) throw conflict();
        return identity(binding);
    }

    /** Pure read: no installation or binding is created while rendering legacy history. */
    @Transactional(readOnly = true)
    public AgentPlatformDtos.AgentIdentity identityOf(long userId, long conversationId) {
        if (!mapper.ownsConversation(userId, conversationId)) throw notFound();
        var binding = mapper.binding(userId, conversationId).orElse(null);
        if (binding != null) return identity(binding);
        var legacy = mapper.definition(LEGACY_AGENT_KEY, LEGACY_AGENT_VERSION).orElseThrow(this::unavailable);
        return new AgentPlatformDtos.AgentIdentity(null, legacy.agentKey(), legacy.version(), legacy.name(),
                null, effectiveCapabilities(legacy.agentKey(),legacy.version(),legacy.capabilitiesJson()));
    }

    /** Read-time revocation check only: no installation, binding, project or account row locks. */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public AgentPlatformDtos.AgentIdentity assertContextReadAllowed(long userId,long conversationId,boolean legacySnapshot) {
        if (!mapper.ownsConversation(userId,conversationId)) throw notFound();
        var binding=mapper.binding(userId,conversationId).orElse(null);
        if (legacySnapshot && binding!=null) throw unavailable(); // New bound Runs cannot omit their identity.
        if (binding==null) {
            mapper.activeInstallation(userId,LEGACY_AGENT_KEY).ifPresent(this::requireEnabled);
            var definition=mapper.definition(LEGACY_AGENT_KEY,LEGACY_AGENT_VERSION).orElseThrow(this::unavailable);
            requireRunnable(definition,userId);
            return new AgentPlatformDtos.AgentIdentity(null,definition.agentKey(),definition.version(),definition.name(),
                    null,effectiveCapabilities(definition.agentKey(),definition.version(),definition.capabilitiesJson()));
        }
        var installation=mapper.installation(userId,binding.installationId()).orElseThrow(this::unavailable);
        requireEnabled(installation);
        var definition=definition(installation,false);
        requireRunnable(definition,userId);
        var identity=identity(binding);
        requireFrozenIdentity(definition,identity);
        if(binding.projectId()!=null && !"ACTIVE".equals(mapper.project(userId,binding.projectId()).orElseThrow(this::notFound).status()))
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE);
        return identity;
    }

    /** Admission only. Existing runs/history retain their frozen identity after removal or disable. */
    @Transactional
    public AgentPlatformDtos.AgentIdentity assertRunAllowed(long userId, long conversationId) {
        var identity = bindConversation(userId, conversationId, null, null);
        var installation = admittedInstallation(userId, Long.parseLong(identity.installationId()));
        requireEnabled(installation);
        var definition = definition(installation, true);
        requireRunnable(definition, userId);
        requireFrozenIdentity(definition, identity);
        requireActiveProject(userId, identity.projectId() == null ? null : Long.valueOf(identity.projectId()));
        return identity;
    }

    /**
     * Confirmation must not acquire a conversation lock before the existing point-account lock.
     * In-flight completion locks points then the conversation event sequence. Never bind/migrate
     * legacy history here; only new Run admission owns that write.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AgentPlatformDtos.AgentIdentity assertTaskConfirmationAllowed(long userId, long conversationId) {
        if (!mapper.ownsConversation(userId, conversationId)) throw notFound();
        var binding = mapper.binding(userId, conversationId).orElse(null);
        if (binding == null) {
            var existing = mapper.activeInstallation(userId, LEGACY_AGENT_KEY).orElse(null);
            if (existing != null) requireEnabled(admittedInstallation(userId, existing.id()));
            var legacy = mapper.lockDefinition(LEGACY_AGENT_KEY, LEGACY_AGENT_VERSION).orElseThrow(this::unavailable);
            requireRunnable(legacy, userId);
            return new AgentPlatformDtos.AgentIdentity(null, legacy.agentKey(), legacy.version(), legacy.name(),
                    null, effectiveCapabilities(legacy.agentKey(),legacy.version(),legacy.capabilitiesJson()));
        }
        var installation = admittedInstallation(userId, binding.installationId());
        requireEnabled(installation);
        var definition = definition(installation, true);
        requireRunnable(definition, userId);
        var identity = identity(binding);
        requireFrozenIdentity(definition, identity);
        if (binding.projectId() != null) {
            var project = mapper.project(userId, binding.projectId()).orElseThrow(this::notFound);
            if (!"ACTIVE".equals(project.status())) throw new BusinessException(ErrorCode.RESOURCE_IN_USE);
        }
        return identity;
    }

    /**
     * Called inside monitor preview, message admission or task admission transactions. Keep their
     * SHARE locks until that caller commits, but do not start a participating proxy transaction:
     * an expected refusal is caught by the caller and must be able to commit WAIT_INPUT instead
     * of marking that transaction rollback-only. No legacy binding creation or conversation lock.
     */
    public AgentPlatformDtos.AgentIdentity assertMonitorExecutionAllowed(long userId,long conversationId) {
        var binding = mapper.binding(userId,conversationId).orElseThrow(this::unavailable);
        var identity = assertTaskConfirmationAllowed(userId,conversationId);
        if (identity.installationId()==null || !identity.capabilities().contains("geo.monitor")
                || !supportsAction(identity,"MONITOR_RUN")) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        requireActiveProject(userId,binding.projectId());
        return identity;
    }

    private void requireFrozenIdentity(AgentPlatformRecords.Definition definition, AgentPlatformDtos.AgentIdentity identity) {
        if (!definition.agentKey().equals(identity.agentKey()) || !definition.version().equals(identity.agentVersion())
                || !definition.name().equals(identity.name())
                || !effectiveCapabilities(definition.agentKey(),definition.version(),definition.capabilitiesJson()).equals(identity.capabilities())) throw unavailable();
    }

    private AgentPlatformRecords.Installation getOrAddInstallation(long userId, String key) {
        var existing = mapper.activeInstallation(userId, key).orElse(null);
        if (existing != null) {
            if (!visible(definition(existing, false), userId)) throw notFound();
            return existing; // Adding twice never upgrades, enables, or revives a removed record.
        }
        var selected = mapper.definitions().stream().filter(value -> value.agentKey().equals(key)
                && visible(value, userId) && "PUBLISHED".equals(value.status())).findFirst().orElseThrow(this::notFound);
        var published = mapper.lockDefinition(selected.agentKey(), selected.version()).orElseThrow(this::notFound);
        if (!"PUBLISHED".equals(published.status()) || !visible(published, userId)) throw notFound();
        requireRunnable(published, userId);
        OffsetDateTime now = now();
        var created = new AgentPlatformRecords.Installation(ids.nextId(), userId, published.agentKey(),
                published.version(), true, 1, now, now, null);
        if (mapper.insertInstallation(created) == 1) return created;
        return mapper.activeInstallation(userId, key).orElseThrow(this::conflict);
    }

    private AgentPlatformRecords.Installation ownedInstallation(long userId, long id) {
        return mapper.lockInstallation(userId, id).orElseThrow(this::notFound);
    }

    private AgentPlatformRecords.Installation admittedInstallation(long userId, long id) {
        // Admission locks must coexist: task confirmation emits a conversation event only after
        // points are locked, while message admission already owns its conversation row.
        return mapper.shareInstallation(userId, id).orElseThrow(this::notFound);
    }

    private AgentPlatformRecords.Definition definition(AgentPlatformRecords.Installation value, boolean lock) {
        return (lock ? mapper.lockDefinition(value.agentKey(), value.agentVersion())
                : mapper.definition(value.agentKey(), value.agentVersion())).orElseThrow(this::unavailable);
    }

    private boolean visible(AgentPlatformRecords.Definition value, long userId) {
        return "PUBLIC".equals(value.visibility())
                || ("INTERNAL".equals(value.visibility()) && properties.isInternalAgentsEnabled()
                    && properties.getInternalAgentUserIds().contains(userId));
    }

    private void requireRunnable(AgentPlatformRecords.Definition value, long userId) {
        if (!visible(value, userId) || "DISABLED".equals(value.status())) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (!Set.of("PUBLISHED", "WITHDRAWN").contains(value.status()) || !runtimeCatalog.supports(value)) {
            throw unavailable();
        }
        if (!effectiveCapabilities(value.agentKey(),value.version(),value.capabilitiesJson())
                .containsAll(runtimeCatalog.require(value.agentKey(),value.version()).requiredCapabilities())) throw unavailable();
    }

    private void requireEnabled(AgentPlatformRecords.Installation value) {
        if (!value.enabled() || value.deletedAt() != null) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private void requireActiveProject(long userId, Long projectId) {
        if (projectId == null) return;
        var project = mapper.lockProject(userId, projectId).orElseThrow(this::notFound);
        if (!"ACTIVE".equals(project.status())) throw new BusinessException(ErrorCode.RESOURCE_IN_USE);
    }

    private void requireOwnedConversationLocked(long userId, long conversationId) {
        if (mapper.lockConversation(userId, conversationId).isEmpty()) throw notFound();
    }

    private AgentPlatformDtos.AgentIdentity identity(AgentPlatformRecords.Binding value) {
        return new AgentPlatformDtos.AgentIdentity(Long.toString(value.installationId()), value.agentKey(),
                value.agentVersion(), value.name(), value.projectId() == null ? null : Long.toString(value.projectId()),
                effectiveCapabilities(value.agentKey(),value.agentVersion(),value.capabilitiesJson()));
    }

    private AgentPlatformDtos.AgentInstallationView installationView(AgentPlatformRecords.Installation value) {
        return new AgentPlatformDtos.AgentInstallationView(Long.toString(value.id()), value.agentKey(),
                value.agentVersion(), definition(value, false).name(), value.enabled(), value.version(), value.createdAt());
    }

    private AgentPlatformDtos.ProjectView projectView(AgentPlatformRecords.Project value) {
        return new AgentPlatformDtos.ProjectView(Long.toString(value.id()), value.name(), value.status(),
                value.version(), value.createdAt(), value.updatedAt());
    }

    private List<String> effectiveCapabilities(String key,String version,String value) {
        // History remains readable after a runtime version is retired. Admission separately fails closed.
        var granted=capabilities(value);
        return runtimeCatalog.find(key,version).isEmpty() ? granted
                : runtimeCatalog.effectiveCapabilities(key,version,granted);
    }

    private List<String> capabilities(String value) {
        try {
            List<String> parsed = json.readValue(value, new TypeReference<List<String>>() { });
            if (parsed == null || parsed.size() > 32 || parsed.stream().anyMatch(item -> item == null
                    || !item.matches("^[a-z][a-z0-9_.-]{0,63}$")) || new HashSet<>(parsed).size() != parsed.size()) {
                throw unavailable();
            }
            return List.copyOf(parsed);
        } catch (java.io.IOException exception) {
            throw unavailable();
        }
    }

    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    private BusinessException conflict() { return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED); }
}
