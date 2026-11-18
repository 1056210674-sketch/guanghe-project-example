package com.guanghe.backend.catalog.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.CatalogJsonCodec;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.catalog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProductService {

    private final ProductMapper mapper;
    private final BusinessSpaceService spaceService;
    private final SnowflakeIdGenerator idGenerator;
    private final CatalogNameNormalizer normalizer;
    private final CatalogJsonCodec jsonCodec;
    private final ProductIdentityAssetService identityAssets;

    @Autowired
    public ProductService(
            ProductMapper mapper,
            BusinessSpaceService spaceService,
            SnowflakeIdGenerator idGenerator,
            CatalogNameNormalizer normalizer,
            CatalogJsonCodec jsonCodec,
            org.springframework.beans.factory.ObjectProvider<ProductIdentityAssetService> identityAssets
    ) {
        this.mapper = mapper;
        this.spaceService = spaceService;
        this.idGenerator = idGenerator;
        this.normalizer = normalizer;
        this.jsonCodec = jsonCodec;
        this.identityAssets = identityAssets.getIfAvailable();
    }

    public ProductService(ProductMapper mapper, BusinessSpaceService spaceService,
                          SnowflakeIdGenerator idGenerator, CatalogNameNormalizer normalizer,
                          CatalogJsonCodec jsonCodec) {
        this.mapper = mapper;
        this.spaceService = spaceService;
        this.idGenerator = idGenerator;
        this.normalizer = normalizer;
        this.jsonCodec = jsonCodec;
        this.identityAssets = null;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductDtos.Summary> list(
            AuthenticatedAccount account, long spaceId, int page, int size
    ) {
        long userId = CatalogAccess.requireUser(account);
        spaceService.requireProjectSpace(userId, spaceId);
        long total = mapper.countBySpace(userId, spaceId);
        List<ProductDtos.Summary> items = mapper.findPage(userId, spaceId, offset(page, size), size)
                .stream().map(this::toSummary).toList();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public ProductDtos.Response get(AuthenticatedAccount account, long productId) {
        long userId = CatalogAccess.requireUser(account);
        ProductRecord product = requireProduct(userId, productId);
        return toResponse(product, mapper.findAliases(productId, userId, product.spaceId()));
    }

    @Transactional
    public ProductDtos.Response create(
            AuthenticatedAccount account, long spaceId, ProductDtos.CreateRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        spaceService.requireProjectSpace(userId, spaceId);
        Long primaryAssetId = parseAssetId(request.primaryAssetId());
        if (primaryAssetId != null) requireIdentityAssets().validatePrimaryAsset(userId, spaceId, primaryAssetId);
        AliasValue name = normalizer.requiredName(request.name(), 150, "name");
        ensureNameAvailable(userId, spaceId, name.normalized(), 0L);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProductRecord product = new ProductRecord(
                idGenerator.nextId(), userId, spaceId, name.value(), name.normalized(),
                normalizer.nullableText(request.category(), 100, "category"),
                normalizer.nullableText(request.modelSpec(), 150, "modelSpec"),
                jsonCodec.write(normalizer.textList(request.sellingPoints(), 50, 500, "sellingPoints")),
                jsonCodec.write(normalizer.parameters(request.parameters(), "parameters")),
                jsonCodec.write(normalizer.textList(request.audience(), 50, 200, "audience")),
                jsonCodec.write(normalizer.textList(request.usageScenarios(), 50, 200, "usageScenarios")),
                jsonCodec.write(normalizer.textList(request.prohibitedClaims(), 50, 500, "prohibitedClaims")),
                primaryAssetId, 1, now, now, 0
        );
        try {
            mapper.insert(product);
            if (primaryAssetId != null) {
                requireIdentityAssets().replacePrimaryRelation(userId, product.id(), spaceId, primaryAssetId, now);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        return toResponse(product, List.of());
    }

    @Transactional
    public ProductDtos.Response update(
            AuthenticatedAccount account, long productId, ProductDtos.UpdateRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        if (!request.hasChanges()) {
            throw normalizer.invalid("request");
        }
        ProductRecord current = requireProduct(userId, productId);
        if (current.version() != request.getVersion()) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        AliasValue name = request.isNamePresent()
                ? normalizer.requiredName(request.getName(), 150, "name")
                : new AliasValue(current.name(), current.nameNormalized());
        if (request.isNamePresent()) {
            ensureNameAvailable(userId, current.spaceId(), name.normalized(), productId);
        }
        Long primaryAssetId = request.isPrimaryAssetIdPresent()
                ? parseAssetId(request.getPrimaryAssetId()) : current.primaryAssetId();
        if (request.isPrimaryAssetIdPresent() && primaryAssetId != null) {
            requireIdentityAssets().validatePrimaryAsset(userId, current.spaceId(), primaryAssetId);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProductRecord updated = new ProductRecord(
                current.id(), userId, current.spaceId(), name.value(), name.normalized(),
                request.isCategoryPresent()
                        ? normalizer.nullableText(request.getCategory(), 100, "category") : current.category(),
                request.isModelSpecPresent()
                        ? normalizer.nullableText(request.getModelSpec(), 150, "modelSpec") : current.modelSpec(),
                request.isSellingPointsPresent()
                        ? jsonCodec.write(requiredList(request.getSellingPoints(), 50, 500, "sellingPoints"))
                        : current.sellingPointsJson(),
                request.isParametersPresent()
                        ? jsonCodec.write(requiredParameters(request.getParameters())) : current.parametersJson(),
                request.isAudiencePresent()
                        ? jsonCodec.write(requiredList(request.getAudience(), 50, 200, "audience"))
                        : current.audienceJson(),
                request.isUsageScenariosPresent()
                        ? jsonCodec.write(requiredList(request.getUsageScenarios(), 50, 200, "usageScenarios"))
                        : current.usageScenariosJson(),
                request.isProhibitedClaimsPresent()
                        ? jsonCodec.write(requiredList(request.getProhibitedClaims(), 50, 500, "prohibitedClaims"))
                        : current.prohibitedClaimsJson(),
                primaryAssetId, current.version() + 1,
                current.createdAt(), now, current.aliasCount()
        );
        try {
            if (mapper.update(updated, current.version()) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
            }
            if (request.isPrimaryAssetIdPresent()) {
                requireIdentityAssets().replacePrimaryRelation(userId, productId, current.spaceId(),
                        primaryAssetId, now);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        return toResponse(updated, mapper.findAliases(productId, userId, current.spaceId()));
    }

    @Transactional
    public ProductDtos.Response replaceAliases(
            AuthenticatedAccount account, long productId, ProductDtos.ReplaceAliasesRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        ProductRecord current = requireProduct(userId, productId);
        if (current.version() != request.version()) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        List<AliasValue> aliases = normalizer.aliases(request.aliases(), 150, "aliases");
        for (AliasValue alias : aliases) {
            if (mapper.nameExists(userId, current.spaceId(), alias.normalized(), 0L)
                    || mapper.aliasExists(userId, current.spaceId(), alias.normalized(), productId)) {
                throw new BusinessException(ErrorCode.RESOURCE_ALIAS_CONFLICT);
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            if (mapper.incrementVersion(productId, userId, current.spaceId(), current.version(), now) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
            }
            mapper.deleteAliases(productId, userId, current.spaceId());
            aliases.forEach(alias -> mapper.insertAlias(
                    idGenerator.nextId(), userId, current.spaceId(), productId, alias, now
            ));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_ALIAS_CONFLICT);
        }
        ProductRecord updated = new ProductRecord(
                current.id(), current.userId(), current.spaceId(), current.name(), current.nameNormalized(),
                current.category(), current.modelSpec(), current.sellingPointsJson(), current.parametersJson(),
                current.audienceJson(), current.usageScenariosJson(), current.prohibitedClaimsJson(),
                current.primaryAssetId(), current.version() + 1, current.createdAt(), now, aliases.size()
        );
        return toResponse(updated, aliases);
    }

    @Transactional
    public void delete(AuthenticatedAccount account, long productId) {
        long userId = CatalogAccess.requireUser(account);
        requireProduct(userId, productId);
        try {
            if (mapper.delete(productId, userId) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_IN_USE,
                    "商品已被任务、永久资产或审计记录引用，解除关联前不能删除",
                    Map.of("reason", "PRODUCT_HAS_PROTECTED_REFERENCES")
            );
        }
    }

    public ProductRecord requireProduct(long userId, long productId) {
        ProductRecord product=mapper.findByIdAndUserId(productId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        spaceService.requireProjectSpace(userId,product.spaceId());
        return product;
    }

    private void ensureNameAvailable(long userId, long spaceId, String normalized, long excludedId) {
        if (mapper.nameExists(userId, spaceId, normalized, excludedId)
                || mapper.aliasExists(userId, spaceId, normalized, 0L)) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
    }

    private List<String> requiredList(List<String> value, int maxItems, int maxLength, String field) {
        if (value == null) {
            throw normalizer.invalid(field);
        }
        return normalizer.textList(value, maxItems, maxLength, field);
    }

    private Map<String, String> requiredParameters(Map<String, String> value) {
        if (value == null) {
            throw normalizer.invalid("parameters");
        }
        return normalizer.parameters(value, "parameters");
    }

    private Long parseAssetId(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || !value.textValue().matches("^[1-9][0-9]{0,18}$")) {
            throw normalizer.invalid("primaryAssetId");
        }
        try {
            return Long.parseLong(value.textValue());
        } catch (NumberFormatException exception) {
            throw normalizer.invalid("primaryAssetId");
        }
    }

    private ProductIdentityAssetService requireIdentityAssets() {
        if (identityAssets == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        return identityAssets;
    }

    private ProductDtos.Response toResponse(ProductRecord product, List<AliasValue> aliases) {
        return new ProductDtos.Response(
                Long.toString(product.id()), Long.toString(product.spaceId()), product.name(),
                aliases.stream().map(AliasValue::value).toList(), product.category(), product.modelSpec(),
                jsonCodec.readStringList(product.sellingPointsJson()),
                jsonCodec.readStringMap(product.parametersJson()),
                jsonCodec.readStringList(product.audienceJson()),
                jsonCodec.readStringList(product.usageScenariosJson()),
                jsonCodec.readStringList(product.prohibitedClaimsJson()),
                product.primaryAssetId() == null ? null : Long.toString(product.primaryAssetId()),
                product.version(), product.createdAt(), product.updatedAt()
        );
    }

    private ProductDtos.Summary toSummary(ProductRecord product) {
        return new ProductDtos.Summary(
                Long.toString(product.id()), Long.toString(product.spaceId()), product.name(),
                product.category(), product.modelSpec(), product.aliasCount(), product.version(),
                product.createdAt(), product.updatedAt()
        );
    }

    private long offset(int page, int size) {
        return (long) (page - 1) * size;
    }
}
