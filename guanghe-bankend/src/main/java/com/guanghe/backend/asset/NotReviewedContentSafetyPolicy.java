package com.guanghe.backend.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records the explicit product decision to make assets usable without content review. */
final class NotReviewedContentSafetyPolicy implements ContentSafetyPolicy {
    private static final Logger log = LoggerFactory.getLogger(NotReviewedContentSafetyPolicy.class);

    @Override public String mode() { return "NOT_REVIEWED"; }

    @Override
    public SafetyDecision inspect(AssetRecords.IngestionWork work, DownloadedArtifact artifact) {
        log.info("asset_content_review mode=NOT_REVIEWED taskId={} assetId={}", work.taskId(), work.assetId());
        return new SafetyDecision(true, "NOT_REVIEWED");
    }

    @Override
    public SafetyDecision inspectUpload(long userId, long assetId, DownloadedArtifact artifact) {
        log.info("asset_upload_content_review mode=NOT_REVIEWED assetId={}", assetId);
        return new SafetyDecision(true, "NOT_REVIEWED");
    }
}
