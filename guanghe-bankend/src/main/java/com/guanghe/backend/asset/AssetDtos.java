package com.guanghe.backend.asset;

import java.time.OffsetDateTime;
import java.util.List;

public final class AssetDtos {
    private AssetDtos() { }

    public record Summary(String id, String assetType, String sourceType, String status,
                          String safetyStatus, String name, String spaceId, String primaryProductId,
                          String contentType, long sizeBytes, String sha256, Integer width,
                          Integer height, String sourceTaskId, OffsetDateTime createdAt,
                          OffsetDateTime updatedAt) { }

    public record Response(String id, String assetType, String sourceType, String status,
                           String safetyStatus, String name, String spaceId, String primaryProductId,
                           List<String> productIds, String contentType, long sizeBytes, String sha256,
                           Integer width, Integer height, String durationSeconds, String sourceTaskId,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public Response {
            productIds = List.copyOf(productIds);
        }
    }

    public record DownloadUrlResponse(String url, OffsetDateTime expiresAt) { }

    public record UploadResponse(String id, String assetType, String sourceType, String status,
                                 String safetyStatus, String name, String spaceId,
                                 String contentType, long sizeBytes, String sha256,
                                 int width, int height, OffsetDateTime createdAt) { }
}
