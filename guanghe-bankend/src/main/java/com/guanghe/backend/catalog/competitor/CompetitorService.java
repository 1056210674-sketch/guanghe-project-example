package com.guanghe.backend.catalog.competitor;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.catalog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CompetitorService {

    private final CompetitorMapper mapper;
    private final BusinessSpaceService spaceService;
    private final SnowflakeIdGenerator idGenerator;
    private final CatalogNameNormalizer normalizer;

    public CompetitorService(
            CompetitorMapper mapper,
            BusinessSpaceService spaceService,
            SnowflakeIdGenerator idGenerator,
            CatalogNameNormalizer normalizer
    ) {
        this.mapper = mapper;
        this.spaceService = spaceService;
        this.idGenerator = idGenerator;
        this.normalizer = normalizer;
    }

    @Transactional(readOnly = true)
    public PageResponse<CompetitorDtos.Summary> list(
            AuthenticatedAccount account, long spaceId, int page, int size
    ) {
        long userId = CatalogAccess.requireUser(account);
        spaceService.requireProjectSpace(userId, spaceId);
        long total = mapper.countBySpace(userId, spaceId);
        List<CompetitorDtos.Summary> items = mapper.findPage(userId, spaceId, offset(page, size), size)
                .stream().map(this::toSummary).toList();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public CompetitorDtos.Response get(AuthenticatedAccount account, long competitorId) {
        long userId = CatalogAccess.requireUser(account);
        CompetitorRecord competitor = requireCompetitor(userId, competitorId);
        return toResponse(
                competitor, mapper.findAliases(competitorId, userId, competitor.spaceId())
        );
    }

    @Transactional
    public CompetitorDtos.Response create(
            AuthenticatedAccount account, long spaceId, CompetitorDtos.CreateRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        spaceService.requireProjectSpace(userId, spaceId);
        AliasValue name = normalizer.requiredName(request.name(), 150, "name");
        ensureNameAvailable(userId, spaceId, name.normalized(), 0L);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CompetitorRecord competitor = new CompetitorRecord(
                idGenerator.nextId(), userId, spaceId, name.value(), name.normalized(), request.type(),
                normalizer.nullableText(request.description(), 1000, "description"),
                request.status() == null ? CompetitorStatus.ACTIVE : request.status(),
                1, now, now, 0
        );
        try {
            mapper.insert(competitor);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        return toResponse(competitor, List.of());
    }

    @Transactional
    public CompetitorDtos.Response update(
            AuthenticatedAccount account, long competitorId, CompetitorDtos.UpdateRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        if (!request.hasChanges()) {
            throw normalizer.invalid("request");
        }
        CompetitorRecord current = requireCompetitor(userId, competitorId);
        if (current.version() != request.getVersion()) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        AliasValue name = request.isNamePresent()
                ? normalizer.requiredName(request.getName(), 150, "name")
                : new AliasValue(current.name(), current.nameNormalized());
        if (request.isNamePresent()) {
            ensureNameAvailable(userId, current.spaceId(), name.normalized(), competitorId);
        }
        if (request.isTypePresent() && request.getType() == null) {
            throw normalizer.invalid("type");
        }
        if (request.isStatusPresent() && request.getStatus() == null) {
            throw normalizer.invalid("status");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CompetitorRecord updated = new CompetitorRecord(
                current.id(), userId, current.spaceId(), name.value(), name.normalized(),
                request.isTypePresent() ? request.getType() : current.type(),
                request.isDescriptionPresent()
                        ? normalizer.nullableText(request.getDescription(), 1000, "description")
                        : current.description(),
                request.isStatusPresent() ? request.getStatus() : current.status(),
                current.version() + 1, current.createdAt(), now, current.aliasCount()
        );
        try {
            if (mapper.update(updated, current.version()) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        return toResponse(updated, mapper.findAliases(competitorId, userId, current.spaceId()));
    }

    @Transactional
    public CompetitorDtos.Response replaceAliases(
            AuthenticatedAccount account,
            long competitorId,
            CompetitorDtos.ReplaceAliasesRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        CompetitorRecord current = requireCompetitor(userId, competitorId);
        if (current.version() != request.version()) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        List<AliasValue> aliases = normalizer.aliases(request.aliases(), 150, "aliases");
        for (AliasValue alias : aliases) {
            if (mapper.nameExists(userId, current.spaceId(), alias.normalized(), 0L)
                    || mapper.aliasExists(userId, current.spaceId(), alias.normalized(), competitorId)) {
                throw new BusinessException(ErrorCode.RESOURCE_ALIAS_CONFLICT);
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            if (mapper.incrementVersion(
                    competitorId, userId, current.spaceId(), current.version(), now
            ) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
            }
            mapper.deleteAliases(competitorId, userId, current.spaceId());
            aliases.forEach(alias -> mapper.insertAlias(
                    idGenerator.nextId(), userId, current.spaceId(), competitorId, alias, now
            ));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_ALIAS_CONFLICT);
        }
        CompetitorRecord updated = new CompetitorRecord(
                current.id(), current.userId(), current.spaceId(), current.name(), current.nameNormalized(),
                current.type(), current.description(), current.status(), current.version() + 1,
                current.createdAt(), now, aliases.size()
        );
        return toResponse(updated, aliases);
    }

    @Transactional
    public void delete(AuthenticatedAccount account, long competitorId) {
        long userId = CatalogAccess.requireUser(account);
        requireCompetitor(userId, competitorId);
        try {
            if (mapper.delete(competitorId, userId) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_IN_USE,
                    "竞品已被 GEO 正式集合或审计记录引用，解除关联前不能删除",
                    Map.of("reason", "COMPETITOR_HAS_PROTECTED_REFERENCES")
            );
        }
    }

    public CompetitorRecord requireCompetitor(long userId, long competitorId) {
        CompetitorRecord competitor=mapper.findByIdAndUserId(competitorId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        spaceService.requireProjectSpace(userId,competitor.spaceId());
        return competitor;
    }

    private void ensureNameAvailable(long userId, long spaceId, String normalized, long excludedId) {
        if (mapper.nameExists(userId, spaceId, normalized, excludedId)
                || mapper.aliasExists(userId, spaceId, normalized, 0L)) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
    }

    private CompetitorDtos.Response toResponse(
            CompetitorRecord competitor, List<AliasValue> aliases
    ) {
        return new CompetitorDtos.Response(
                Long.toString(competitor.id()), Long.toString(competitor.spaceId()), competitor.name(),
                aliases.stream().map(AliasValue::value).toList(), competitor.type(), competitor.description(),
                competitor.status(), competitor.version(), competitor.createdAt(), competitor.updatedAt()
        );
    }

    private CompetitorDtos.Summary toSummary(CompetitorRecord competitor) {
        return new CompetitorDtos.Summary(
                Long.toString(competitor.id()), Long.toString(competitor.spaceId()), competitor.name(),
                competitor.type(), competitor.status(), competitor.aliasCount(), competitor.version(),
                competitor.createdAt(), competitor.updatedAt()
        );
    }

    private long offset(int page, int size) {
        return (long) (page - 1) * size;
    }
}
