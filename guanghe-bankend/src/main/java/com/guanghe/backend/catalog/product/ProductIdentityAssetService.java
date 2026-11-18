package com.guanghe.backend.catalog.product;

import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "enabled", havingValue = "true")
public class ProductIdentityAssetService {
    private static final Set<String> ROLES = Set.of("PRIMARY", "WHITE_BACKGROUND", "DETAIL", "PACKAGE");

    private final ProductMapper products;
    private final AssetMapper assets;
    private final AssetIngestionProperties assetProperties;

    public ProductIdentityAssetService(ProductMapper products, AssetMapper assets,
                                       AssetIngestionProperties assetProperties) {
        this.products = products;
        this.assets = assets;
        this.assetProperties = assetProperties;
    }

    @Transactional(readOnly = true)
    public ProductDtos.IdentityAssetsResponse get(AuthenticatedAccount account, long productId) {
        long userId = CatalogAccess.requireUser(account);
        ProductRecord product = requireProduct(userId, productId);
        return response(product, products.findIdentityAssets(productId, userId, product.spaceId()));
    }

    @Transactional
    public ProductDtos.IdentityAssetsResponse replace(AuthenticatedAccount account, long productId,
                                                       ProductDtos.ReplaceIdentityAssetsRequest request) {
        long userId = CatalogAccess.requireUser(account);
        ProductRecord product = requireProduct(userId, productId);
        if (product.version() != request.version()) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        List<ValidatedIdentityAsset> validated = validateInputs(userId, product.spaceId(), request.items());
        Long primaryAssetId = validated.stream().filter(item -> "PRIMARY".equals(item.role()))
                .map(ValidatedIdentityAsset::assetId).findFirst().orElse(null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (products.updateIdentityVersion(productId, userId, product.spaceId(), product.version(),
                primaryAssetId, now) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        products.deleteIdentityAssets(productId, userId, product.spaceId());
        validated.forEach(item -> products.insertIdentityAsset(productId, item.assetId(), userId,
                product.spaceId(), item.role(), item.sequence(), now));
        return response(new ProductRecord(product.id(), product.userId(), product.spaceId(), product.name(),
                        product.nameNormalized(), product.category(), product.modelSpec(), product.sellingPointsJson(),
                        product.parametersJson(), product.audienceJson(), product.usageScenariosJson(),
                        product.prohibitedClaimsJson(), primaryAssetId, product.version() + 1,
                        product.createdAt(), now, product.aliasCount()),
                products.findIdentityAssets(productId, userId, product.spaceId()));
    }

    public long validatePrimaryAsset(long userId, long spaceId, long assetId) {
        return validateAsset(userId, spaceId, assetId).id();
    }

    public void replacePrimaryRelation(long userId, long productId, long spaceId, Long assetId,
                                       OffsetDateTime now) {
        List<ProductIdentityAssetRecord> existing = products.findIdentityAssets(productId, userId, spaceId);
        List<ValidatedIdentityAsset> desired = new java.util.ArrayList<>();
        if (assetId != null) desired.add(new ValidatedIdentityAsset(assetId, "PRIMARY", 1));
        int sequence = assetId == null ? 1 : 2;
        for (ProductIdentityAssetRecord item : existing) {
            if (!"PRIMARY".equals(item.role()) && item.assetId() != (assetId == null ? -1L : assetId)) {
                desired.add(new ValidatedIdentityAsset(item.assetId(), item.role(), sequence++));
            }
        }
        products.deleteIdentityAssets(productId, userId, spaceId);
        desired.forEach(item -> products.insertIdentityAsset(productId, item.assetId(), userId, spaceId,
                item.role(), item.sequence(), now));
    }

    // Pure validation: let callers persist a rejected proposal without poisoning their transaction.
    // Database and unexpected runtime failures still mark the enclosing transaction for rollback.
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public List<ProductIdentityAssetRecord> requireReadyIdentityAssets(long userId, long productId, long spaceId) {
        ProductRecord product = requireProduct(userId, productId);
        if (product.spaceId() != spaceId) throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED);
        List<ProductIdentityAssetRecord> found = products.findIdentityAssets(productId, userId, spaceId);
        if (found.stream().noneMatch(item -> "PRIMARY".equals(item.role())
                || "WHITE_BACKGROUND".equals(item.role()))) {
            throw new BusinessException(ErrorCode.PRODUCT_IDENTITY_ASSET_REQUIRED);
        }
        if (found.stream().anyMatch(item -> !"READY".equals(item.status())
                || !downloadAllowed(item.safetyStatus()))) {
            throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_READY);
        }
        return List.copyOf(found);
    }

    private List<ValidatedIdentityAsset> validateInputs(long userId, long spaceId,
                                                         List<ProductDtos.IdentityAssetInput> inputs) {
        Set<Long> assetIds = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        int primaryCount = 0;
        java.util.ArrayList<ValidatedIdentityAsset> result = new java.util.ArrayList<>();
        for (ProductDtos.IdentityAssetInput input : inputs) {
            long assetId = positiveLong(input.assetId());
            if (!ROLES.contains(input.role()) || !assetIds.add(assetId) || !sequences.add(input.sequence())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if ("PRIMARY".equals(input.role()) && ++primaryCount > 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            validateAsset(userId, spaceId, assetId);
            result.add(new ValidatedIdentityAsset(assetId, input.role(), input.sequence()));
        }
        result.sort(java.util.Comparator.comparingInt(ValidatedIdentityAsset::sequence));
        for (int index = 0; index < result.size(); index++) {
            if (result.get(index).sequence() != index + 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        return List.copyOf(result);
    }

    private AssetRecords.Asset validateAsset(long userId, long spaceId, long assetId) {
        AssetRecords.Asset asset = assets.findAsset(assetId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED));
        if (asset.spaceId() != spaceId || !"IMAGE".equals(asset.assetType())) {
            throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED);
        }
        if (!"READY".equals(asset.status()) || !downloadAllowed(asset.safetyStatus())) {
            throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_READY);
        }
        return asset;
    }

    private ProductRecord requireProduct(long userId, long productId) {
        return products.findByIdAndUserId(productId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private boolean downloadAllowed(String status) {
        return assetProperties.allowsAssetUse(status);
    }

    private ProductDtos.IdentityAssetsResponse response(ProductRecord product,
                                                         List<ProductIdentityAssetRecord> items) {
        List<ProductDtos.IdentityAssetResponse> responses = items.stream().map(item ->
                new ProductDtos.IdentityAssetResponse(Long.toString(item.assetId()), item.role(), item.sequence(),
                        item.status(), item.contentType(), item.width(), item.height())).toList();
        boolean ready = items.stream().anyMatch(item -> "READY".equals(item.status())
                && downloadAllowed(item.safetyStatus())
                && ("PRIMARY".equals(item.role()) || "WHITE_BACKGROUND".equals(item.role())));
        return new ProductDtos.IdentityAssetsResponse(Long.toString(product.id()), product.version(), ready, responses);
    }

    private long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private record ValidatedIdentityAsset(long assetId, String role, int sequence) { }
}
