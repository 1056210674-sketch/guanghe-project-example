package com.guanghe.backend.catalog.competitor;

import java.time.OffsetDateTime;

public record CompetitorRecord(
        long id,
        long userId,
        long spaceId,
        String name,
        String nameNormalized,
        CompetitorType type,
        String description,
        CompetitorStatus status,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int aliasCount
) {
}
