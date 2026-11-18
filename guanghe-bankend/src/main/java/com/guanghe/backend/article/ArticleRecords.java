package com.guanghe.backend.article;

import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.AutomapConstructor;

public final class ArticleRecords {
    private ArticleRecords() { }

    public record Article(long id, long userId, long spaceId, Long productId, String title,
                          String bodyHtml, String bodyText, String outlineJson, String status,
                          Long sourceTaskId, int version, OffsetDateTime createdAt,
                          OffsetDateTime updatedAt, OffsetDateTime savedToAssetsAt,
                          Integer savedArticleVersion) {
        @AutomapConstructor public Article { }
        public Article(long id, long userId, long spaceId, Long productId, String title,
                       String bodyHtml, String bodyText, String outlineJson, String status,
                       Long sourceTaskId, int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this(id,userId,spaceId,productId,title,bodyHtml,bodyText,outlineJson,status,sourceTaskId,
                    version,createdAt,updatedAt,null,null);
        }
    }

    public record StreamPreview(long taskId, int executionNo, int attempt, int sequence,
                                String markdown, OffsetDateTime updatedAt) { }

    public record AssetRelation(long id, long articleId, long assetId, long userId, long spaceId,
                                String positionKey, int sortOrder, String altText,
                                OffsetDateTime createdAt) { }

    public record SubmissionSnapshot(long id, long articleId, long userId, long spaceId,
                                     Long productId, int articleVersion, String title,
                                     String bodyHtml, String bodyText, String bodyHash,
                                     String outlineJson, String assetManifestJson,
                                     OffsetDateTime createdAt) { }

    public record Workflow(long articleId, long userId, String workflowStage,
                           Integer currentTitleBatchVersion, String confirmedTitle,
                           String confirmedTitleSource, Integer confirmedTitleBatchVersion,
                           Integer confirmedTitleCandidateIndex, OffsetDateTime confirmedTitleAt,
                           Integer currentPlanVersion, Integer confirmedPlanVersion) { }

    public record TitleCandidateBatch(long id, long articleId, long userId, long spaceId,
                                      int batchVersion, int articleVersion, String candidatesJson,
                                      long sourceTaskId, OffsetDateTime createdAt) { }

    public record CreativePlanRevision(long id, long articleId, long userId, long spaceId,
                                       int planVersion, int articleVersion, String planJson,
                                       String source, Long sourceTaskId, OffsetDateTime confirmedAt,
                                       OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record SubmissionPreference(long articleId, long userId, long spaceId, int articleVersion,
                                       String goal, String preferredRegion, Integer maxTotalPoints,
                                       boolean requiresLink, OffsetDateTime confirmedAt,
                                       OffsetDateTime updatedAt) { }
}
