package com.guanghe.backend.asset;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "enabled", havingValue = "true")
public class AssetService {
    private static final Set<String> TYPES = Set.of("IMAGE", "VIDEO", "ARTICLE", "GEO_REPORT");
    private static final Set<String> SOURCES = Set.of("GENERATED", "UPLOADED", "IMPORTED");
    private final AssetMapper mapper;
    private final ObjectStorage storage;
    private final AssetIngestionProperties properties;

    public AssetService(AssetMapper mapper, ObjectStorage storage, AssetIngestionProperties properties) {
        this.mapper = mapper;
        this.storage = storage;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PageResponse<AssetDtos.Summary> list(AuthenticatedAccount account, int page, int size,
                                                String type, Long spaceId, Long productId,
                                                String sourceType) {
        long userId = CatalogAccess.requireUser(account);
        String normalizedType = optionalEnum(type, TYPES);
        String normalizedSource = optionalEnum(sourceType, SOURCES);
        long total = mapper.countAssets(userId, normalizedType, spaceId, productId, normalizedSource);
        List<AssetDtos.Summary> items = mapper.findAssetPage(userId, normalizedType, spaceId, productId,
                normalizedSource, (long) (page - 1) * size, size).stream().map(this::summary).toList();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public AssetDtos.Response get(AuthenticatedAccount account, long assetId) {
        long userId = CatalogAccess.requireUser(account);
        return response(requireAsset(assetId, userId), userId);
    }

    @Transactional(readOnly = true)
    public AssetDtos.DownloadUrlResponse downloadUrl(AuthenticatedAccount account, long assetId) {
        long userId = CatalogAccess.requireUser(account);
        AssetRecords.Asset asset = requireAsset(assetId, userId);
        if (!"READY".equals(asset.status()) || !properties.allowsAssetUse(asset.safetyStatus())) {
            throw new BusinessException(ErrorCode.ASSET_NOT_READY);
        }
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(properties.getSignedUrlTtl());
        return new AssetDtos.DownloadUrlResponse(storage.signedGetUrl(asset.objectKey(), expiresAt).toString(),
                expiresAt);
    }

    private AssetRecords.Asset requireAsset(long assetId, long userId) {
        return mapper.findAsset(assetId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private String optionalEnum(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) return null;
        if (!allowed.contains(value)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return value;
    }

    private AssetDtos.Summary summary(AssetRecords.Asset asset) {
        return new AssetDtos.Summary(Long.toString(asset.id()), asset.assetType(), asset.sourceType(),
                asset.status(), asset.safetyStatus(), asset.name(), Long.toString(asset.spaceId()),
                asset.primaryProductId() == null ? null : Long.toString(asset.primaryProductId()),
                asset.contentType(), asset.sizeBytes(), asset.sha256(), asset.width(), asset.height(),
                asset.sourceTaskId() == null ? null : Long.toString(asset.sourceTaskId()),
                asset.createdAt(), asset.updatedAt());
    }

    private AssetDtos.Response response(AssetRecords.Asset asset, long userId) {
        return new AssetDtos.Response(Long.toString(asset.id()), asset.assetType(), asset.sourceType(),
                asset.status(), asset.safetyStatus(), asset.name(), Long.toString(asset.spaceId()),
                asset.primaryProductId() == null ? null : Long.toString(asset.primaryProductId()),
                mapper.findProductIds(asset.id(), userId).stream().map(String::valueOf).toList(),
                asset.contentType(), asset.sizeBytes(), asset.sha256(), asset.width(), asset.height(),
                asset.durationSeconds(), asset.sourceTaskId() == null ? null : Long.toString(asset.sourceTaskId()),
                asset.createdAt(), asset.updatedAt());
    }
}
