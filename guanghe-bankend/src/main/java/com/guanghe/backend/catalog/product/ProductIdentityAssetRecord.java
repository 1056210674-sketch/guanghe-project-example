package com.guanghe.backend.catalog.product;

public record ProductIdentityAssetRecord(
        long assetId,
        String role,
        int sequence,
        String status,
        String safetyStatus,
        String contentType,
        long sizeBytes,
        String sha256,
        Integer width,
        Integer height,
        String objectKey
) { }
