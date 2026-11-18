package com.guanghe.backend.asset;

import com.guanghe.backend.conversation.ConversationEventStreamService;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetIngestionWorkerTests {
    private AssetIngestionService service;
    private ProviderArtifactDownloader downloader;
    private ObjectStorage storage;
    private ContentSafetyPolicy safety;
    private AssetIngestionWorker worker;

    @BeforeEach
    void setUp() {
        service = mock(AssetIngestionService.class);
        downloader = mock(ProviderArtifactDownloader.class);
        storage = mock(ObjectStorage.class);
        safety = mock(ContentSafetyPolicy.class);
        worker = new AssetIngestionWorker(service, downloader, storage, safety,
                mock(ConversationEventStreamService.class));
    }

    @Test
    void networkIoRunsOnlyAfterPersistentClaimAndThenFinalizes() {
        AssetRecords.IngestionWork work = work();
        DownloadedArtifact artifact = new DownloadedArtifact(Path.of("artifact.png"), "image/png", 88,
                "a".repeat(64), 16, 12);
        when(service.claimNext()).thenReturn(Optional.of(work));
        when(service.renewLease(work.id(), work.attempt(), work.claimToken())).thenReturn(true);
        when(downloader.download(work)).thenReturn(artifact);
        when(safety.inspect(work, artifact)).thenReturn(
                new ContentSafetyPolicy.SafetyDecision(true, "TEST_BYPASS"));
        when(storage.storeOrConfirm(anyString(), any(), anyString(), anyLong(), anyString()))
                .thenReturn(new ObjectStorage.StoredObject(work.objectKey(), "image/png", 88,
                        "a".repeat(64), true));
        when(service.complete(work.id(), work.attempt(), work.claimToken(), artifact, "TEST_BYPASS"))
                .thenReturn(Optional.empty());

        worker.processOne();

        var order = inOrder(service, downloader, safety, storage);
        order.verify(service).claimNext();
        order.verify(downloader).download(work);
        order.verify(safety).inspect(work, artifact);
        order.verify(storage).storeOrConfirm(work.objectKey(), artifact.path(), artifact.contentType(),
                artifact.sizeBytes(), artifact.sha256());
        order.verify(service).complete(work.id(), work.attempt(), work.claimToken(), artifact, "TEST_BYPASS");
        verify(service, times(4)).renewLease(work.id(), work.attempt(), work.claimToken());
        verify(downloader).cleanup(artifact);
    }

    @Test
    void explicitlyUnreviewedGeneratedAssetKeepsItsOwnAuditStatus() {
        AssetRecords.IngestionWork work = work();
        DownloadedArtifact artifact = new DownloadedArtifact(Path.of("artifact.png"), "image/png", 88,
                "a".repeat(64), 16, 12);
        worker = new AssetIngestionWorker(service, downloader, storage,
                new NotReviewedContentSafetyPolicy(), mock(ConversationEventStreamService.class));
        when(service.claimNext()).thenReturn(Optional.of(work));
        when(service.renewLease(work.id(), work.attempt(), work.claimToken())).thenReturn(true);
        when(downloader.download(work)).thenReturn(artifact);
        when(storage.storeOrConfirm(anyString(), any(), anyString(), anyLong(), anyString()))
                .thenReturn(new ObjectStorage.StoredObject(work.objectKey(), "image/png", 88,
                        "a".repeat(64), true));
        when(service.complete(work.id(), work.attempt(), work.claimToken(), artifact, "NOT_REVIEWED"))
                .thenReturn(Optional.empty());

        worker.processOne();

        verify(service).complete(work.id(), work.attempt(), work.claimToken(), artifact, "NOT_REVIEWED");
        verify(downloader).cleanup(artifact);
    }

    @Test
    void databaseFailureAfterOssUploadBecomesRecoverableWithoutCreatingAnotherObjectKey() {
        AssetRecords.IngestionWork work = work();
        DownloadedArtifact artifact = new DownloadedArtifact(Path.of("artifact.png"), "image/png", 88,
                "a".repeat(64), 16, 12);
        when(service.claimNext()).thenReturn(Optional.of(work));
        when(service.renewLease(work.id(), work.attempt(), work.claimToken())).thenReturn(true);
        when(downloader.download(work)).thenReturn(artifact);
        when(safety.inspect(work, artifact)).thenReturn(
                new ContentSafetyPolicy.SafetyDecision(true, "TEST_BYPASS"));
        when(service.complete(work.id(), work.attempt(), work.claimToken(), artifact, "TEST_BYPASS"))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(service.failOrRetry(anyLong(), anyInt(), any(), anyString(), anyBoolean()))
                .thenReturn(Optional.empty());

        worker.processOne();

        verify(service).failOrRetry(work.id(), work.attempt(), work.claimToken(),
                "ASSET_INGESTION_INTERNAL_ERROR", true);
        verify(storage, never()).delete(work.objectKey());
        verify(downloader).cleanup(artifact);
    }

    @Test
    void rejectedContentNeverReachesOssOrAReadyAssetTransaction() {
        AssetRecords.IngestionWork work = work();
        DownloadedArtifact artifact = new DownloadedArtifact(Path.of("artifact.png"), "image/png", 88,
                "a".repeat(64), 16, 12);
        when(service.claimNext()).thenReturn(Optional.of(work));
        when(service.renewLease(work.id(), work.attempt(), work.claimToken())).thenReturn(true);
        when(downloader.download(work)).thenReturn(artifact);
        when(safety.inspect(work, artifact)).thenReturn(
                new ContentSafetyPolicy.SafetyDecision(false, "REJECTED"));
        when(service.failOrRetry(work.id(), work.attempt(), work.claimToken(),
                "CONTENT_REJECTED", false)).thenReturn(Optional.empty());

        worker.processOne();

        verify(storage, never()).storeOrConfirm(anyString(), any(), anyString(), anyLong(), anyString());
        verify(service, never()).complete(anyLong(), anyInt(), any(), any(), anyString());
        verify(service).failOrRetry(work.id(), work.attempt(), work.claimToken(),
                "CONTENT_REJECTED", false);
        verify(downloader).cleanup(artifact);
    }

    @Test
    void staleWorkerNeverDeletesAnObjectAfterItsLeaseWasReclaimed() {
        AssetRecords.IngestionWork work = work();
        DownloadedArtifact artifact = new DownloadedArtifact(Path.of("artifact.png"), "image/png", 88,
                "a".repeat(64), 16, 12);
        when(service.claimNext()).thenReturn(Optional.of(work));
        when(service.renewLease(work.id(), work.attempt(), work.claimToken()))
                .thenReturn(true, true, true, false);
        when(downloader.download(work)).thenReturn(artifact);
        when(safety.inspect(work, artifact)).thenReturn(
                new ContentSafetyPolicy.SafetyDecision(true, "TEST_BYPASS"));
        when(storage.storeOrConfirm(anyString(), any(), anyString(), anyLong(), anyString()))
                .thenThrow(new AssetIngestionException("OSS_OBJECT_VERIFICATION_FAILED", false, true));
        when(service.failOrRetry(anyLong(), anyInt(), any(), anyString(), anyBoolean()))
                .thenReturn(Optional.empty());

        worker.processOne();

        verify(storage, never()).delete(work.objectKey());
        verify(service).failOrRetry(work.id(), work.attempt(), work.claimToken(),
                "OSS_OBJECT_VERIFICATION_FAILED", false);
    }

    @Test
    void currentGenerationMayDeleteOnlyAnObjectItCreatedAndStillOwns() {
        AssetRecords.IngestionWork work = work();
        DownloadedArtifact artifact = new DownloadedArtifact(Path.of("artifact.png"), "image/png", 88,
                "a".repeat(64), 16, 12);
        when(service.claimNext()).thenReturn(Optional.of(work));
        when(service.renewLease(work.id(), work.attempt(), work.claimToken())).thenReturn(true);
        when(downloader.download(work)).thenReturn(artifact);
        when(safety.inspect(work, artifact)).thenReturn(
                new ContentSafetyPolicy.SafetyDecision(true, "TEST_BYPASS"));
        when(storage.storeOrConfirm(anyString(), any(), anyString(), anyLong(), anyString()))
                .thenThrow(new AssetIngestionException("OSS_OBJECT_VERIFICATION_FAILED", false, true));
        when(service.failOrRetry(anyLong(), anyInt(), any(), anyString(), anyBoolean()))
                .thenReturn(Optional.empty());

        worker.processOne();

        verify(storage).delete(work.objectKey());
    }

    @Test
    void currentGenerationNeverDeletesAPreexistingObject() {
        AssetRecords.IngestionWork work = work();
        DownloadedArtifact artifact = new DownloadedArtifact(Path.of("artifact.png"), "image/png", 88,
                "a".repeat(64), 16, 12);
        when(service.claimNext()).thenReturn(Optional.of(work));
        when(service.renewLease(work.id(), work.attempt(), work.claimToken())).thenReturn(true);
        when(downloader.download(work)).thenReturn(artifact);
        when(safety.inspect(work, artifact)).thenReturn(
                new ContentSafetyPolicy.SafetyDecision(true, "TEST_BYPASS"));
        when(storage.storeOrConfirm(anyString(), any(), anyString(), anyLong(), anyString()))
                .thenThrow(new AssetIngestionException("OSS_OBJECT_VERIFICATION_FAILED", false, false));
        when(service.failOrRetry(anyLong(), anyInt(), any(), anyString(), anyBoolean()))
                .thenReturn(Optional.empty());

        worker.processOne();

        verify(storage, never()).delete(work.objectKey());
    }

    private AssetRecords.IngestionWork work() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AssetRecords.IngestionWork(1, 2, 3, 4, 1, 5, 6, 7L, 1, "QWEN_IMAGE",
                "qwen-image-3.0", UUID.randomUUID(), "provider-task",
                "https://cdn.example.com/a.png", now.plusHours(1), "image/png", null, null,
                16, 12, "zhixiaohe/test/users/5/assets/2/original.png", "PROCESSING", 1, 3,
                now, now.plusMinutes(1), UUID.randomUUID(), null, null, now, now);
    }
}
