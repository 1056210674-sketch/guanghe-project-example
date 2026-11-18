package com.guanghe.backend.article;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class ArticleFullRecords {
    private ArticleFullRecords() { }
    public record Grant(long id,long userId,long conversationId,long installationId,
            String agentKey,String agentVersion,Long projectId,long spaceId,Long productId,
            int version,String status,int maxPoints,OffsetDateTime expiresAt,
            OffsetDateTime createdAt,OffsetDateTime updatedAt,String executionSource,UUID sourceRunId) {
        @org.apache.ibatis.annotations.AutomapConstructor
        public Grant { }
        public Grant(long id,long userId,long conversationId,long installationId,String agentKey,String agentVersion,
                Long projectId,long spaceId,Long productId,int version,String status,int maxPoints,
                OffsetDateTime expiresAt,OffsetDateTime createdAt,OffsetDateTime updatedAt) {
            this(id,userId,conversationId,installationId,agentKey,agentVersion,projectId,spaceId,productId,
                    version,status,maxPoints,expiresAt,createdAt,updatedAt,"USER_AUTHORIZATION",null);
        }
    }
    public record Preparation(long planId,long userId,long conversationId,long grantId,
            int grantVersion,long articleId,int expectedArticleVersion,UUID sourceRunId,
            long requestMessageId,UUID idempotencyKey,String requestHash,String snapshotJson,
            String briefHash,int maxCalls,long maxInputTokens,long maxOutputTokens,
            OffsetDateTime deadlineAt,String status,String errorCode,
            OffsetDateTime createdAt,OffsetDateTime updatedAt) { }
}
