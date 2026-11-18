package com.guanghe.backend.geo;

import com.guanghe.backend.catalog.competitor.CompetitorType;
import java.time.OffsetDateTime;

public final class GeoRecords {
    private GeoRecords() { }

    public record Topic(
            long id,
            long userId,
            long spaceId,
            Long productId,
            String name,
            String nameNormalized,
            String region,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) { }

    public record Keyword(
            long id,
            long userId,
            long spaceId,
            long topicId,
            int sequence,
            GeoDtos.KeywordType type,
            String text,
            String textNormalized,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) { }

    public record Question(
            long id,
            long userId,
            long spaceId,
            long topicId,
            int sequence,
            GeoDtos.QuestionCategory category,
            String text,
            String textNormalized,
            GeoDtos.QuestionSource source,
            boolean enabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) { }

    public record CompetitorSet(
            long id,
            long userId,
            long spaceId,
            long topicId,
            int version,
            OffsetDateTime createdAt
    ) { }

    public record CompetitorSetMember(
            long id,
            long userId,
            long spaceId,
            long topicId,
            long competitorSetId,
            long competitorId,
            int sequence,
            String competitorNameSnapshot,
            CompetitorType competitorTypeSnapshot,
            OffsetDateTime createdAt
    ) { }
}
