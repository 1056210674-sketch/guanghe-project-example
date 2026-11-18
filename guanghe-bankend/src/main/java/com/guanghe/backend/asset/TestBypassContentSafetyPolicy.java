package com.guanghe.backend.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TestBypassContentSafetyPolicy implements ContentSafetyPolicy {
    private static final Logger log = LoggerFactory.getLogger(TestBypassContentSafetyPolicy.class);

    @Override public String mode() { return "TEST_BYPASS"; }

    @Override
    public SafetyDecision inspect(AssetRecords.IngestionWork work, DownloadedArtifact artifact) {
        log.info("asset_content_safety mode=TEST_BYPASS taskId={} assetId={}", work.taskId(), work.assetId());
        return new SafetyDecision(true, "TEST_BYPASS");
    }

    @Override
    public SafetyDecision inspectUpload(long userId, long assetId, DownloadedArtifact artifact) {
        log.info("asset_upload_content_safety mode=TEST_BYPASS assetId={}", assetId);
        return new SafetyDecision(true, "TEST_BYPASS");
    }
}
