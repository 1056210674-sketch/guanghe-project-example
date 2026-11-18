package com.guanghe.backend.catalog.space;

import java.time.OffsetDateTime;

public record BusinessSpaceRecord(
        long id,
        long userId,
        String name,
        String nameNormalized,
        String brandName,
        String brandProfileJson,
        Long logoAssetId,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long productCount,
        long competitorCount
) {
}
