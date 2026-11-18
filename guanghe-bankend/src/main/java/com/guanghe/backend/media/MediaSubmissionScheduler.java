package com.guanghe.backend.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.media", name = "enabled", havingValue = "true")
final class MediaSubmissionScheduler {
    private final MediaSubmissionService service;
    private final MediaProperties properties;
    MediaSubmissionScheduler(MediaSubmissionService service, MediaProperties properties) {
        this.service = service; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${MEDIA_SUBMISSION_DISPATCH_POLL_DELAY_MS:1000}")
    void dispatch() { service.processOneOutbox(); }

    @Scheduled(fixedDelayString = "${MEDIA_SUBMISSION_STATUS_POLL_DELAY_MS:30000}")
    void poll() { service.pollOneSubmission(); }

    // Keep catch-up attempts outside the provider's 09–12 and 13–19 closed windows.
    @Scheduled(cron = "${MEDIA_RESOURCE_SYNC_CRON:0 5 2,12,19 * * *}", zone = "Asia/Shanghai")
    void synchronize() {
        if (properties.isResourceSyncEnabled()) service.synchronizeResources();
    }
}
