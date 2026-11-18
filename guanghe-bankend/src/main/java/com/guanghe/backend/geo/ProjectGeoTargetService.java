package com.guanghe.backend.geo;

import com.guanghe.backend.agentplatform.AgentPlatformMapper;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit public identity settings; project display names and pasted knowledge never grant an identity. */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.agent-platform", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProjectGeoTargetService {
    private final AgentPlatformMapper projects;
    private final BusinessSpaceMapper spaces;
    private final CatalogNameNormalizer names;
    private final SnowflakeIdGenerator ids;

    public ProjectGeoTargetService(AgentPlatformMapper projects, BusinessSpaceMapper spaces,
                                   CatalogNameNormalizer names, SnowflakeIdGenerator ids) {
        this.projects = projects;
        this.spaces = spaces;
        this.names = names;
        this.ids = ids;
    }

    // A shared row lock keeps name, optimistic version and separately stored aliases coherent.
    // PostgreSQL disallows FOR SHARE in a read-only transaction; this path still performs no DML.
    @Transactional
    public ProjectGeoTargetDtos.View get(AuthenticatedAccount account, long projectId) {
        long userId = CatalogAccess.requireUser(account);
        var project = projects.lockProject(userId, projectId).orElseThrow(this::notFound);
        var space = spaces.findProjectByIdAndUserId(projectId, userId).orElseThrow(this::notFound);
        return view(projectId, project.version(), space.brandName(), spaces.findAliases(projectId, userId),
                project.updatedAt());
    }

    @Transactional
    public ProjectGeoTargetDtos.View update(AuthenticatedAccount account, long projectId,
                                           ProjectGeoTargetDtos.UpdateRequest request) {
        long userId = CatalogAccess.requireUser(account);
        if (request == null || request.version() == null || request.version() < 1 || request.aliases() == null) {
            throw names.invalid("geoTarget");
        }
        AliasValue name = identity(request.name(), "name");
        List<AliasValue> aliases = names.aliases(request.aliases(), 100, "aliases");
        if (aliases.stream().anyMatch(alias -> !GeoVisibilityProjection.identityName(alias.value())
                || alias.normalized().equals(name.normalized()))) throw names.invalid("aliases");
        var project = projects.lockProjectForUpdate(userId, projectId).orElseThrow(this::notFound);
        if (!"ACTIVE".equals(project.status())) throw new BusinessException(ErrorCode.RESOURCE_IN_USE,
                "项目已归档，请先恢复项目。", Map.of("reasonCode", "PROJECT_ARCHIVED"));
        if (project.version() != request.version()) throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        var current = spaces.findProjectByIdAndUserId(projectId, userId).orElseThrow(this::notFound);
        List<AliasValue> oldAliases = spaces.findAliases(projectId, userId);
        if (name.value().equals(current.brandName()) && aliases.equals(oldAliases)) {
            return view(projectId, project.version(), current.brandName(), oldAliases, project.updatedAt());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // Update only the public name: keep brand profile, logo, project display name and history byte-for-byte.
        if (spaces.updateGeoTarget(projectId, userId, request.version(), name.value(), now) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        spaces.deleteAliases(projectId, userId);
        for (AliasValue alias : aliases) {
            if (spaces.insertAlias(ids.nextId(), userId, projectId, alias, now) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
            }
        }
        return view(projectId, project.version() + 1, name.value(), aliases, now);
    }

    private AliasValue identity(String raw, String field) {
        AliasValue value = names.requiredName(raw, 100, field);
        if (!GeoVisibilityProjection.identityName(value.value())) throw names.invalid(field);
        return value;
    }

    private ProjectGeoTargetDtos.View view(long projectId, int version, String name, List<AliasValue> aliases,
                                           OffsetDateTime updatedAt) {
        return new ProjectGeoTargetDtos.View(Long.toString(projectId), version, name,
                aliases.stream().map(AliasValue::value).toList(), name != null && GeoVisibilityProjection.identityName(name),
                updatedAt);
    }

    private BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
