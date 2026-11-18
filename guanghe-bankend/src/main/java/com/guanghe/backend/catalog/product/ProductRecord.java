package com.guanghe.backend.catalog.product;

import java.time.OffsetDateTime;

public record ProductRecord(
        long id,
        long userId,
        long spaceId,
        String name,
        String nameNormalized,
        String category,
        String modelSpec,
        String sellingPointsJson,
        String parametersJson,
        String audienceJson,
        String usageScenariosJson,
        String prohibitedClaimsJson,
        Long primaryAssetId,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int aliasCount
) {
}
