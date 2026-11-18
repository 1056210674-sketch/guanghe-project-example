package com.guanghe.backend.catalog.space;

import com.fasterxml.jackson.databind.JsonNode;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.CatalogJsonCodec;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.PageResponse;
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
public class BusinessSpaceService {

    private final BusinessSpaceMapper mapper;
    private final SnowflakeIdGenerator idGenerator;
    private final CatalogNameNormalizer normalizer;
    private final CatalogJsonCodec jsonCodec;

    public BusinessSpaceService(
            BusinessSpaceMapper mapper,
            SnowflakeIdGenerator idGenerator,
            CatalogNameNormalizer normalizer,
            CatalogJsonCodec jsonCodec
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.normalizer = normalizer;
        this.jsonCodec = jsonCodec;
    }

    @Transactional(readOnly = true)
    public PageResponse<SpaceDtos.Summary> list(AuthenticatedAccount account, int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        long total = mapper.countByUserId(userId);
        List<SpaceDtos.Summary> items = mapper.findPage(userId, offset(page, size), size).stream()
                .map(this::toSummary)
                .toList();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public SpaceDtos.Response get(AuthenticatedAccount account, long spaceId) {
        long userId = CatalogAccess.requireUser(account);
        BusinessSpaceRecord record = requireProjectSpace(userId, spaceId);
        requireLegacyBrand(record);
        return toResponse(record, mapper.findAliases(spaceId, userId));
    }

    @Transactional
    public SpaceDtos.Response create(AuthenticatedAccount account, SpaceDtos.CreateRequest request) {
        long userId = CatalogAccess.requireUser(account);
        requireNullAsset(request.logoAssetId(), "logoAssetId");
        AliasValue name = normalizer.requiredName(request.name(), 100, "name");
        PreparedBrand brand = prepareBrand(request.brand());
        if (mapper.nameExists(userId, name.normalized(), 0L)) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        BusinessSpaceRecord record = new BusinessSpaceRecord(
                idGenerator.nextId(), userId, name.value(), name.normalized(), brand.name().value(),
                jsonCodec.write(brand.profile()), null, 1, now, now, 0, 0
        );
        try {
            mapper.insert(record);
            insertAliases(record.id(), userId, brand.aliases(), now);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        return toResponse(record, brand.aliases());
    }

    @Transactional
    public SpaceDtos.Response update(
            AuthenticatedAccount account,
            long spaceId,
            SpaceDtos.UpdateRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        if (!request.hasChanges()) {
            throw normalizer.invalid("request");
        }
        BusinessSpaceRecord current = requireProjectSpace(userId, spaceId);
        requireLegacyBrand(current);
        if (current.version() != request.getVersion()) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }

        AliasValue name = request.isNamePresent()
                ? normalizer.requiredName(request.getName(), 100, "name")
                : new AliasValue(current.name(), current.nameNormalized());
        if (mapper.nameExists(userId, name.normalized(), spaceId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        PreparedBrand brand = request.isBrandPresent()
                ? prepareBrand(request.getBrand())
                : currentBrand(current, mapper.findAliases(spaceId, userId));
        if (request.isLogoAssetIdPresent()) {
            requireNullAsset(request.getLogoAssetId(), "logoAssetId");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        BusinessSpaceRecord updated = new BusinessSpaceRecord(
                current.id(), userId, name.value(), name.normalized(), brand.name().value(),
                jsonCodec.write(brand.profile()), null, current.version() + 1,
                current.createdAt(), now, current.productCount(), current.competitorCount()
        );
        try {
            if (mapper.update(updated, current.version()) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
            }
            if (request.isBrandPresent()) {
                mapper.deleteAliases(spaceId, userId);
                insertAliases(spaceId, userId, brand.aliases(), now);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        return toResponse(updated, brand.aliases());
    }

    @Transactional
    public void delete(AuthenticatedAccount account, long spaceId) {
        long userId = CatalogAccess.requireUser(account);
        requireLegacyBrand(requireProjectSpace(userId, spaceId));
        SpaceUsageCounts counts = mapper.usageCounts(spaceId, userId);
        if (counts.inUse()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_IN_USE,
                    ErrorCode.RESOURCE_IN_USE.defaultMessage(),
                    Map.of("productCount", counts.productCount(), "competitorCount", counts.competitorCount())
            );
        }
        try {
            if (mapper.delete(spaceId, userId) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE);
        }
    }

    /** Ownership check for storage-only flows such as personal creative asset upload. */
    public BusinessSpaceRecord requireOwnedStorageSpace(long userId, long spaceId) {
        return mapper.findOwnedStorageByIdAndUserId(spaceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** Server-owned personal creation storage; never a customer project. */
    public BusinessSpaceRecord requirePersonalCreativeSpace(long userId, long spaceId) {
        return mapper.findPersonalCreativeByIdAndUserId(spaceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** Customer-project boundary. PERSONAL_CREATIVE storage must never pass this method. */
    public BusinessSpaceRecord requireProjectSpace(long userId, long spaceId) {
        return mapper.findProjectByIdAndUserId(spaceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireLegacyBrand(BusinessSpaceRecord record) {
        if (record.brandName() == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private PreparedBrand prepareBrand(SpaceDtos.BrandRequest request) {
        if (request == null) {
            throw normalizer.invalid("brand");
        }
        AliasValue name = normalizer.requiredName(request.name(), 100, "brand.name");
        List<AliasValue> aliases = normalizer.aliases(request.aliases(), 100, "brand.aliases");
        if (aliases.stream().anyMatch(alias -> alias.normalized().equals(name.normalized()))) {
            throw normalizer.invalid("brand.aliases");
        }
        SpaceDtos.StoredBrandProfile profile = new SpaceDtos.StoredBrandProfile(
                normalizer.nullableText(request.description(), 2000, "brand.description"),
                normalizer.textList(request.tone(), 10, 50, "brand.tone"),
                normalizer.textList(request.audience(), 20, 200, "brand.audience"),
                normalizer.textList(request.sellingPoints(), 20, 500, "brand.sellingPoints"),
                normalizer.textList(request.prohibitedClaims(), 20, 500, "brand.prohibitedClaims")
        );
        return new PreparedBrand(name, aliases, profile);
    }

    private PreparedBrand currentBrand(BusinessSpaceRecord current, List<AliasValue> aliases) {
        SpaceDtos.StoredBrandProfile profile = jsonCodec.read(
                current.brandProfileJson(), SpaceDtos.StoredBrandProfile.class
        );
        return new PreparedBrand(
                normalizer.requiredName(current.brandName(), 100, "brand.name"), aliases,
                new SpaceDtos.StoredBrandProfile(
                        profile.description(), safe(profile.tone()), safe(profile.audience()),
                        safe(profile.sellingPoints()), safe(profile.prohibitedClaims())
                )
        );
    }

    private void insertAliases(long spaceId, long userId, List<AliasValue> aliases, OffsetDateTime now) {
        aliases.forEach(alias -> mapper.insertAlias(idGenerator.nextId(), userId, spaceId, alias, now));
    }

    private SpaceDtos.Response toResponse(BusinessSpaceRecord record, List<AliasValue> aliases) {
        SpaceDtos.StoredBrandProfile profile = jsonCodec.read(
                record.brandProfileJson(), SpaceDtos.StoredBrandProfile.class
        );
        SpaceDtos.Brand brand = new SpaceDtos.Brand(
                record.brandName(), aliases.stream().map(AliasValue::value).toList(), profile.description(),
                safe(profile.tone()), safe(profile.audience()), safe(profile.sellingPoints()),
                safe(profile.prohibitedClaims())
        );
        return new SpaceDtos.Response(
                Long.toString(record.id()), record.name(), brand,
                record.logoAssetId() == null ? null : Long.toString(record.logoAssetId()),
                record.productCount(), record.competitorCount(), record.version(),
                record.createdAt(), record.updatedAt()
        );
    }

    private SpaceDtos.Summary toSummary(BusinessSpaceRecord record) {
        return new SpaceDtos.Summary(
                Long.toString(record.id()), record.name(), record.brandName(), record.productCount(),
                record.competitorCount(), record.version(), record.createdAt(), record.updatedAt()
        );
    }

    private void requireNullAsset(JsonNode value, String field) {
        if (value != null && !value.isNull()) {
            throw normalizer.invalid(field);
        }
    }

    private long offset(int page, int size) {
        return (long) (page - 1) * size;
    }

    private List<String> safe(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record PreparedBrand(
            AliasValue name,
            List<AliasValue> aliases,
            SpaceDtos.StoredBrandProfile profile
    ) {
    }
}
