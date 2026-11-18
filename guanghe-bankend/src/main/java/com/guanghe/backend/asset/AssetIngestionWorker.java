package com.guanghe.backend.asset;

import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.conversation.ConversationEventStreamService;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "worker-enabled", havingValue = "true")
public class AssetIngestionWorker {
    private final AssetIngestionService service;
    private final ProviderArtifactDownloader downloader;
    private final ObjectStorage storage;
    private final ContentSafetyPolicy safety;
    private final ConversationEventStreamService events;
    private final ObservabilitySupport observability;
    private ImageRegionEditService regionEdits;

    @Autowired(required = false)
    void setRegionEdits(org.springframework.beans.factory.ObjectProvider<ImageRegionEditService> provider) {
        this.regionEdits = provider.getIfAvailable();
    }

    @Autowired
    public AssetIngestionWorker(AssetIngestionService service, ProviderArtifactDownloader downloader,
                                ObjectStorage storage, ContentSafetyPolicy safety,
                                ConversationEventStreamService events,
                                ObservabilitySupport observability) {
        this.service = service;
        this.downloader = downloader;
        this.storage = storage;
        this.safety = safety;
        this.events = events;
        this.observability = observability;
    }

    public AssetIngestionWorker(AssetIngestionService service, ProviderArtifactDownloader downloader,
                                ObjectStorage storage, ContentSafetyPolicy safety,
                                ConversationEventStreamService events) {
        this(service, downloader, storage, safety, events, ObservabilitySupport.noop());
    }

    @Scheduled(fixedDelayString = "${ASSET_INGESTION_POLL_DELAY_MS:1000}")
    public void processOne() {
        AssetRecords.IngestionWork work = service.claimNext().orElse(null);
        if (work == null) return;
        DownloadedArtifact artifact = null;
        try (ObservabilitySupport.Operation span = observability.startConsumer(
                "java.asset.ingest", "DOWNLOAD_VALIDATE_STORE", null, true)) {
            span.tag("taskIdRef", observability.reference(work.taskId()));
            span.tag("executionNo", work.executionNo());
            span.tag("provider", work.providerCode());
            try {
            if (!renew(work)) return;
            artifact = downloader.download(work);
            if ("WAN_IMAGE_EDIT".equals(work.providerCode())) {
                if (regionEdits == null) throw new AssetIngestionException("REGION_COMPOSITOR_UNAVAILABLE", false);
                artifact = regionEdits.composite(work, artifact);
            }
            if (!renew(work)) return;
            ContentSafetyPolicy.SafetyDecision decision = safety.inspect(work, artifact);
            if (!decision.accepted()) throw new AssetIngestionException("CONTENT_REJECTED", false);
            if (!renew(work)) return;
            DownloadedArtifact storedArtifact = artifact;
            observability.observe("java.oss.upload", "STORE_OR_CONFIRM", child ->
                    child.tag("taskIdRef", observability.reference(work.taskId())), () ->
                    storage.storeOrConfirm(work.objectKey(), storedArtifact.path(), storedArtifact.contentType(),
                            storedArtifact.sizeBytes(), storedArtifact.sha256()));
            if (!renew(work)) return;
            service.complete(work.id(), work.attempt(), work.claimToken(), artifact,
                    decision.auditStatus()).ifPresent(events::publish);
            } catch (AssetIngestionException exception) {
                span.fail(exception.errorCode(), "DOWNLOAD_VALIDATE_STORE");
            if (!exception.retryable() && exception.objectCreatedByCaller() && renew(work)) {
                try {
                    storage.delete(work.objectKey());
                } catch (RuntimeException ignored) {
                    // The database work remains auditable; never broaden cleanup beyond this owned generation.
                }
            }
            service.failOrRetry(work.id(), work.attempt(), work.claimToken(), exception.errorCode(),
                    exception.retryable()).ifPresent(events::publish);
            } catch (RuntimeException exception) {
                span.fail("ASSET_INGESTION_INTERNAL_ERROR", "DOWNLOAD_VALIDATE_STORE");
            service.failOrRetry(work.id(), work.attempt(), work.claimToken(),
                    "ASSET_INGESTION_INTERNAL_ERROR", true).ifPresent(events::publish);
            }
        } finally {
            downloader.cleanup(artifact);
        }
    }

    private boolean renew(AssetRecords.IngestionWork work) {
        return service.renewLease(work.id(), work.attempt(), work.claimToken());
    }
}
