package com.guanghe.backend.asset;

public interface ContentSafetyPolicy {
    String mode();
    SafetyDecision inspect(AssetRecords.IngestionWork work, DownloadedArtifact artifact);

    default SafetyDecision inspectUpload(long userId, long assetId, DownloadedArtifact artifact) {
        throw new AssetIngestionException("UPLOAD_CONTENT_SAFETY_UNAVAILABLE", false);
    }

    record SafetyDecision(boolean accepted, String auditStatus) { }
}
