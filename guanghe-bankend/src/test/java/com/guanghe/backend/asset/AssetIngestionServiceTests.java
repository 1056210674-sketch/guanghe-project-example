package com.guanghe.backend.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskRecords;
import com.guanghe.backend.workflow.WorkflowTaskLifecycleService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssetIngestionServiceTests {
    private static final UUID CLAIM_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CLAIM_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private AssetMapper assets;
    private TaskControlMapper tasks;
    private ConversationMapper conversations;
    private AssetIngestionService service;
    private Map<Integer, AssetRecords.IngestionWork> candidateWorks;
    private Map<Integer, AssetRecords.Asset> candidateAssets;
    private WorkflowTaskLifecycleService workflow;
    private final AtomicLong nextId = new AtomicLong(9000);

    @BeforeEach
    void setUp() {
        assets = mock(AssetMapper.class);
        tasks = mock(TaskControlMapper.class);
        conversations = mock(ConversationMapper.class);
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        when(ids.nextId()).thenAnswer(ignored -> nextId.incrementAndGet());
        AssetIngestionProperties properties = new AssetIngestionProperties();
        properties.setLease(java.time.Duration.ofMinutes(2));
        properties.setRetryDelay(java.time.Duration.ofSeconds(5));
        service = new AssetIngestionService(assets, tasks, conversations, ids,
                new ObjectMapper().findAndRegisterModules(), properties);
    }

    @Test
    void expiredGenerationOneIsReclaimedAsGenerationTwoWithANewClaimToken() {
        AssetRecords.IngestionWork expired = work("PROCESSING", 1, now().minusMinutes(1), CLAIM_A);
        AtomicReference<UUID> claimedToken = new AtomicReference<>();
        when(assets.lockNextWork(any())).thenReturn(Optional.of(expired));
        when(assets.claimWork(eq(expired.id()), any(), any(), any())).thenAnswer(invocation -> {
            claimedToken.set(invocation.getArgument(2));
            return 1;
        });
        when(assets.findWork(expired.id())).thenAnswer(ignored -> Optional.of(
                work("PROCESSING", 2, now().plusMinutes(2), claimedToken.get())));

        AssetRecords.IngestionWork reclaimed = service.claimNext().orElseThrow();
        assertThat(reclaimed.attempt()).isEqualTo(2);
        assertThat(reclaimed.claimToken()).isNotNull().isNotEqualTo(CLAIM_A);
        verify(assets).claimWork(eq(expired.id()), any(), eq(reclaimed.claimToken()), any());
    }

    @Test
    void successfulCompletionCreatesOneAssetAndSettlesExactlyOnce() {
        OffsetDateTime beforeMidnight = OffsetDateTime.parse("2026-09-24T15:59:59Z");
        OffsetDateTime afterMidnight = beforeMidnight.plusSeconds(2);
        AtomicReference<OffsetDateTime> clockTime = new AtomicReference<>(beforeMidnight);
        Clock accountingClock = mock(Clock.class);
        when(accountingClock.instant()).thenAnswer(ignored -> clockTime.get().toInstant());
        service.setAccountingClock(accountingClock);
        var notifications = mock(com.guanghe.backend.task.TaskNotificationService.class);
        service.setTaskNotifications(notifications);
        AssetRecords.IngestionWork processing = work("PROCESSING", 2, now().plusMinutes(2), CLAIM_B);
        AssetRecords.IngestionWork done = work("SUCCEEDED", 2, null, null);
        when(assets.lockWork(processing.id())).thenReturn(Optional.of(processing), Optional.of(done));
        when(tasks.lockTask(processing.taskId())).thenReturn(Optional.of(task("RUNNING")));
        when(tasks.findExecution(processing.executionId())).thenReturn(Optional.of(execution("RESULT_READY")));
        when(assets.insertAsset(any())).thenReturn(1);
        when(assets.insertProductRelation(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(1);
        when(tasks.insertTaskEvent(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(tasks.completeResultReadyExecution(eq(processing.executionId()), any())).thenReturn(1);
        when(tasks.completeTask(anyLong(), anyString(), any())).thenReturn(1);
        when(tasks.settleReservation(anyLong(), anyInt(), any())).thenReturn(1);
        when(tasks.settlePoints(anyLong(), anyInt(), any())).thenReturn(1);
        when(tasks.lockPointAccount(processing.userId())).thenAnswer(ignored -> {
            clockTime.set(afterMidnight);
            return Optional.of(new TaskRecords.PointAccount(processing.userId(), 20, 7, 1,
                    beforeMidnight, beforeMidnight));
        });
        when(assets.completeWork(eq(processing.id()), eq(2), eq(CLAIM_B), any())).thenReturn(1);
        when(tasks.findPointAccount(processing.userId())).thenReturn(Optional.of(
                new TaskRecords.PointAccount(processing.userId(), 20, 0, 2, now(), now())));
        when(tasks.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(),
                anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(conversations.nextEventSequence(anyLong(), anyLong())).thenReturn(8L);
        when(conversations.insertEvent(any())).thenReturn(1);
        DownloadedArtifact artifact = new DownloadedArtifact(Path.of("test.png"), "image/png", 88,
                "a".repeat(64), 32, 24);

        assertThat(service.complete(processing.id(), 2, CLAIM_B, artifact, "TEST_BYPASS")).isPresent();
        assertThat(service.complete(processing.id(), 2, CLAIM_B, artifact, "TEST_BYPASS")).isEmpty();

        verify(assets, times(1)).insertAsset(any());
        verify(tasks, times(1)).settlePoints(eq(processing.userId()), eq(7), any());
        verify(tasks).lockPointAccount(processing.userId());
        verify(accountingClock).instant();
        verify(tasks).settlePoints(processing.userId(), 7, afterMidnight);
        verify(tasks).insertLedger(anyLong(), eq(processing.userId()), eq(0), eq(-7), eq(20), eq(0),
                eq("TASK_SETTLE"), eq(processing.taskId()), eq("task-settle:" + processing.taskId()),
                anyString(), eq(afterMidnight));
        verify(tasks, times(1)).insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyString(), anyLong(), anyString(), anyString(), any());
        verify(conversations, times(1)).insertEvent(any());
        verify(notifications).publishTerminal(processing.taskId(), processing.userId());
    }

    @Test
    void successfulVideoCompletionCreatesPermanentMp4AndSettlesExactlyOnce() {
        OffsetDateTime now = now();
        AssetRecords.IngestionWork processing = new AssetRecords.IngestionWork(
                6101, 6102, 9102, 8201, 1, 41, 101, 102L, 1,
                "WAN_VIDEO", "wan3.0-video", UUID.randomUUID(), "wan-task-1",
                "https://example.com/result.mp4?signature=not-logged", null, "video/mp4",
                null, null, null, null, java.math.BigDecimal.valueOf(5), "16:9", "720P",
                now.plusMinutes(30),
                "zhixiaohe/test/users/41/assets/6102/original.mp4", "PROCESSING", 1,
                3, now, now.plusMinutes(2), CLAIM_A, null, null, now, now);
        TaskRecords.Task task = new TaskRecords.Task(9102, 41, 101, 102L, 9001, 7001,
                "VIDEO", "RUNNING", "ASSET_INGESTION_PENDING", 1, "{}", "b".repeat(64),
                7001, 11, 0, "{}", null, "TASK_CONFIRM", UUID.randomUUID(), "a".repeat(64),
                now, now, null, now, now);
        TaskRecords.Execution execution = new TaskRecords.Execution(8201, 9102, 41, 1,
                "RESULT_READY", "worker", "worker-1", "bundle", "c".repeat(64), now,
                "ASSET_INGESTION_PENDING", "WAN_VIDEO", "wan3.0-video", UUID.randomUUID(),
                "wan-task-1", "CONFIRMED", 100, "{}", null, now, now, null, now, now);
        when(assets.lockWork(processing.id())).thenReturn(Optional.of(processing));
        when(tasks.lockTask(processing.taskId())).thenReturn(Optional.of(task));
        when(tasks.findExecution(processing.executionId())).thenReturn(Optional.of(execution));
        when(assets.insertAsset(any())).thenReturn(1);
        when(assets.insertProductRelation(anyLong(),anyLong(),anyLong(),anyLong(),any())).thenReturn(1);
        when(tasks.insertTaskEvent(anyLong(),anyLong(),anyLong(),anyLong(),any(),any(),anyString(),
                anyString(),anyString(),anyString(),any(),any(),anyString(),any())).thenReturn(1);
        when(tasks.completeResultReadyExecution(eq(processing.executionId()),any())).thenReturn(1);
        when(tasks.completeTask(anyLong(),anyString(),any())).thenReturn(1);
        when(tasks.settleReservation(anyLong(),anyInt(),any())).thenReturn(1);
        when(tasks.settlePoints(anyLong(),anyInt(),any())).thenReturn(1);
        when(tasks.lockPointAccount(processing.userId())).thenReturn(Optional.of(
                new TaskRecords.PointAccount(processing.userId(), 20, 11, 1, now, now)));
        when(assets.completeWork(eq(processing.id()),eq(1),eq(CLAIM_A),any())).thenReturn(1);
        when(tasks.findPointAccount(processing.userId())).thenReturn(Optional.of(
                new TaskRecords.PointAccount(processing.userId(),20,0,2,now,now)));
        when(tasks.insertLedger(anyLong(),anyLong(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),
                anyLong(),anyString(),anyString(),any())).thenReturn(1);
        when(conversations.nextEventSequence(anyLong(),anyLong())).thenReturn(10L);
        when(conversations.insertEvent(any())).thenReturn(1);
        DownloadedArtifact artifact = new DownloadedArtifact(Path.of("test.mp4"), "video/mp4", 188,
                "d".repeat(64), 1280, 720, "5.000", "h264");

        assertThat(service.complete(processing.id(),1,CLAIM_A,artifact,"TEST_BYPASS")).isPresent();

        org.mockito.ArgumentCaptor<AssetRecords.Asset> captured =
                org.mockito.ArgumentCaptor.forClass(AssetRecords.Asset.class);
        verify(assets).insertAsset(captured.capture());
        assertThat(captured.getValue().assetType()).isEqualTo("VIDEO");
        assertThat(captured.getValue().contentType()).isEqualTo("video/mp4");
        assertThat(captured.getValue().durationSeconds()).isEqualTo("5.000");
        verify(tasks,times(1)).settlePoints(eq(41L),eq(11),any());
    }

    @Test
    void transientFailureKeepsPointsFrozenAndSchedulesBoundedRetry() {
        var notifications = mock(com.guanghe.backend.task.TaskNotificationService.class);
        service.setTaskNotifications(notifications);
        AssetRecords.IngestionWork processing = work("PROCESSING", 1, now().plusMinutes(2), CLAIM_A);
        when(assets.lockWork(processing.id())).thenReturn(Optional.of(processing));
        when(tasks.lockTask(processing.taskId())).thenReturn(Optional.of(task("RUNNING")));
        when(assets.retryWork(anyLong(), eq(1), eq(CLAIM_A), any(), anyString(), any())).thenReturn(1);
        when(tasks.updateAssetIngestionStage(anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(tasks.insertTaskEvent(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(conversations.nextEventSequence(anyLong(), anyLong())).thenReturn(8L);
        when(conversations.insertEvent(any())).thenReturn(1);

        assertThat(service.failOrRetry(processing.id(), 1, CLAIM_A,
                "OSS_UPLOAD_FAILED", true)).isPresent();
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verifyNoInteractions(notifications);
    }

    @Test
    void permanentOrExhaustedFailureReleasesFrozenPointsOnlyOnce() {
        var notifications = mock(com.guanghe.backend.task.TaskNotificationService.class);
        service.setTaskNotifications(notifications);
        AssetRecords.IngestionWork processing = work("PROCESSING", 3, now().plusMinutes(2), CLAIM_A);
        AssetRecords.IngestionWork failed = work("FAILED", 3, null, null);
        when(assets.lockWork(processing.id())).thenReturn(Optional.of(processing), Optional.of(failed));
        when(tasks.lockTask(processing.taskId())).thenReturn(Optional.of(task("RUNNING")));
        when(assets.failWork(anyLong(), eq(3), eq(CLAIM_A), anyString(), any())).thenReturn(1);
        when(tasks.failResultReadyExecution(anyLong(), anyString(), any())).thenReturn(1);
        when(tasks.failAssetIngestionTask(anyLong(), anyString(), any())).thenReturn(1);
        when(tasks.releaseReservation(anyLong(), anyInt(), any())).thenReturn(1);
        when(tasks.releasePoints(anyLong(), anyInt(), any())).thenReturn(1);
        when(tasks.insertTaskEvent(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(tasks.findPointAccount(processing.userId())).thenReturn(Optional.of(
                new TaskRecords.PointAccount(processing.userId(), 27, 0, 2, now(), now())));
        when(tasks.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(),
                anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(conversations.nextEventSequence(anyLong(), anyLong())).thenReturn(9L);
        when(conversations.insertEvent(any())).thenReturn(1);

        assertThat(service.failOrRetry(processing.id(), 3, CLAIM_A,
                "OSS_UPLOAD_FAILED", true)).isPresent();
        assertThat(service.failOrRetry(processing.id(), 3, CLAIM_A,
                "OSS_UPLOAD_FAILED", true)).isEmpty();
        verify(tasks, times(1)).releasePoints(eq(processing.userId()), eq(7), any());
        verify(notifications).publishTerminal(processing.taskId(), processing.userId());
        verify(conversations, times(1)).insertEvent(any());
    }

    @Test
    void staleGenerationCannotCompleteRetryFailOrRenewTheCurrentGeneration() {
        AssetRecords.IngestionWork current = work("PROCESSING", 2, now().plusMinutes(2), CLAIM_B);
        when(assets.lockWork(current.id())).thenReturn(Optional.of(current));
        DownloadedArtifact artifact = new DownloadedArtifact(Path.of("test.png"), "image/png", 88,
                "a".repeat(64), 32, 24);

        assertThat(service.complete(current.id(), 1, CLAIM_A, artifact, "TEST_BYPASS")).isEmpty();
        assertThat(service.failOrRetry(current.id(), 1, CLAIM_A,
                "OSS_UPLOAD_FAILED", true)).isEmpty();
        assertThat(service.failOrRetry(current.id(), 1, CLAIM_A,
                "CONTENT_REJECTED", false)).isEmpty();
        assertThat(service.renewLease(current.id(), 1, CLAIM_A)).isFalse();

        verify(assets, never()).insertAsset(any());
        verify(assets, never()).completeWork(anyLong(), anyInt(), any(), any());
        verify(assets, never()).retryWork(anyLong(), anyInt(), any(), any(), anyString(), any());
        verify(assets, never()).failWork(anyLong(), anyInt(), any(), anyString(), any());
        verifyNoInteractions(tasks, conversations);
    }

    @Test
    void fourCandidatesSettleOnlyAfterTheLastPermanentAssetAndReplayIsIgnored() throws Exception {
        var notifications = mock(com.guanghe.backend.task.TaskNotificationService.class);
        service.setTaskNotifications(notifications);
        prepareCandidates(4, 4);
        for (int index = 1; index <= 3; index++) {
            assertThat(completeCandidate(index).orElseThrow().eventType()).isEqualTo("task.progress");
        }
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verify(assets, never()).finishImageCandidatesTask(anyLong(), anyString(), anyString(), anyInt(),
                anyString(), any(), any());
        verifyNoInteractions(workflow);
        verifyNoInteractions(notifications);

        assertThat(completeCandidate(4).orElseThrow().eventType()).isEqualTo("task.completed");
        assertThat(completeCandidate(4)).isEmpty();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(assets).finishImageCandidatesTask(eq(9002L), eq("SUCCEEDED"), eq("COMPLETED"), eq(28),
                summary.capture(), eq(null), any());
        var result = new ObjectMapper().readTree(summary.getValue());
        assertThat(result.path("requestedCount").asInt()).isEqualTo(4);
        assertThat(result.path("succeededCount").asInt()).isEqualTo(4);
        assertThat(result.path("failedCount").asInt()).isZero();
        assertThat(result.path("assets")).hasSize(4);
        assertThat(result.path("assets").get(3).path("assetId").asText()).isEqualTo("7004");
        assertThat(result.path("assets").get(3).path("width").asInt()).isEqualTo(104);
        assertThat(result.path("asset")).isEqualTo(result.path("assets").get(0));
        verify(tasks).settlePoints(eq(41L), eq(28), any());
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
        verify(assets).finalizeImageCandidatesReservation(eq(9002L), eq(28), eq(0), eq("SETTLED"), any());
        verify(workflow).taskTerminal(any(), eq("SUCCEEDED"), eq(null), any());
        verify(notifications).publishTerminal(9002L, 41L);
    }

    @Test
    void failedCandidateDoesNotStopSiblingsAndOutOfOrderCompletionSettlesOnlyReadyAssets() throws Exception {
        prepareCandidates(4, 4);
        OffsetDateTime beforeMidnight = OffsetDateTime.parse("2026-09-24T15:59:59Z");
        OffsetDateTime afterMidnight = beforeMidnight.plusSeconds(2);
        AtomicReference<OffsetDateTime> clockTime = new AtomicReference<>(beforeMidnight);
        Clock accountingClock = mock(Clock.class);
        when(accountingClock.instant()).thenAnswer(ignored -> clockTime.get().toInstant());
        service.setAccountingClock(accountingClock);
        when(tasks.lockPointAccount(41L)).thenAnswer(ignored -> {
            clockTime.set(afterMidnight);
            return Optional.of(new TaskRecords.PointAccount(41, 20, 28, 1,
                    beforeMidnight, beforeMidnight));
        });
        assertThat(failCandidate(1).orElseThrow().eventType()).isEqualTo("task.progress");
        verify(tasks, never()).failResultReadyExecution(anyLong(), anyString(), any());
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
        assertThat(completeCandidate(4)).isPresent();
        assertThat(failCandidate(3)).isPresent();
        assertThat(completeCandidate(2).orElseThrow().eventType()).isEqualTo("task.completed");

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(assets).finishImageCandidatesTask(eq(9002L), eq("PARTIAL"), eq("IMAGE_PARTIAL_COMPLETED"), eq(14),
                summary.capture(), eq(null), any());
        var result = new ObjectMapper().readTree(summary.getValue());
        assertThat(result.path("succeededCount").asInt()).isEqualTo(2);
        assertThat(result.path("failedCount").asInt()).isEqualTo(2);
        assertThat(result.path("assets")).hasSize(2);
        assertThat(result.path("assets").get(0).path("index").asInt()).isEqualTo(2);
        assertThat(result.path("assets").get(0).path("assetId").asText()).isEqualTo("7002");
        assertThat(result.path("assets").get(1).path("index").asInt()).isEqualTo(4);
        assertThat(result.path("assets").get(1).path("width").asInt()).isEqualTo(104);
        verify(tasks).lockPointAccount(41L);
        verify(accountingClock).instant();
        verify(tasks).settlePoints(41L, 14, afterMidnight);
        verify(tasks).releasePoints(41L, 14, afterMidnight);
        verify(assets).finalizeImageCandidatesReservation(eq(9002L), eq(14), eq(14), eq("PARTIAL"), any());
        verify(tasks).insertLedger(anyLong(), eq(41L), eq(0), eq(-14), eq(20), eq(14),
                eq("TASK_SETTLE"), eq(9002L), eq("task-settle:9002"), anyString(), eq(afterMidnight));
        verify(tasks).insertLedger(anyLong(), eq(41L), eq(14), eq(-14), eq(34), eq(0),
                eq("TASK_RELEASE"), eq(9002L), eq("task-release:9002"), anyString(), eq(afterMidnight));
        verify(workflow).taskTerminal(any(), eq("PARTIAL"), eq(null), any());
    }

    @Test
    void unreportedProviderCandidatesAreReleasedWhenAllReportedWorksFinish() throws Exception {
        prepareCandidates(4, 1);

        assertThat(completeCandidate(1).orElseThrow().eventType()).isEqualTo("task.completed");

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(assets).finishImageCandidatesTask(eq(9002L), eq("PARTIAL"), eq("IMAGE_PARTIAL_COMPLETED"), eq(7),
                summary.capture(), eq(null), any());
        var result = new ObjectMapper().readTree(summary.getValue());
        assertThat(result.path("requestedCount").asInt()).isEqualTo(4);
        assertThat(result.path("succeededCount").asInt()).isEqualTo(1);
        assertThat(result.path("failedCount").asInt()).isEqualTo(3);
        verify(tasks).settlePoints(eq(41L), eq(7), any());
        verify(tasks).releasePoints(eq(41L), eq(21), any());
    }

    @Test
    void allCandidatesFailWithEmptyResultsAndOneFullRelease() throws Exception {
        prepareCandidates(3, 2);
        assertThat(failCandidate(2).orElseThrow().eventType()).isEqualTo("task.progress");
        assertThat(failCandidate(1).orElseThrow().eventType()).isEqualTo("task.failed");
        assertThat(failCandidate(1)).isEmpty();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(assets).finishImageCandidatesTask(eq(9002L), eq("FAILED"), eq("ASSET_INGESTION_FAILED"), eq(0),
                summary.capture(), eq("CONTENT_REJECTED"), any());
        var result = new ObjectMapper().readTree(summary.getValue());
        assertThat(result.path("assets")).isEmpty();
        assertThat(result.has("asset")).isFalse();
        assertThat(result.path("failedCount").asInt()).isEqualTo(3);
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verify(tasks).releasePoints(eq(41L), eq(21), any());
        verify(tasks, never()).lockPointAccount(anyLong());
        verify(assets).finalizeImageCandidatesReservation(eq(9002L), eq(0), eq(21), eq("RELEASED"), any());
        verify(tasks).failResultReadyExecution(eq(8101L), eq("CONTENT_REJECTED"), any());
        verify(workflow).taskTerminal(any(), eq("FAILED"), eq("CONTENT_REJECTED"), any());
    }

    @Test
    void missingReadyAssetPreventsSettlementEvenIfWorkSaysSucceeded() {
        prepareCandidates(2, 2);
        completeCandidate(1);
        candidateAssets.clear();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> completeCandidate(2))
                .isInstanceOf(com.guanghe.backend.common.api.BusinessException.class);

        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
        verifyNoInteractions(workflow);
    }

    @Test
    void candidateAggregationReadsDoNotAcquireSiblingWorkLocks() throws Exception {
        for (String methodName : new String[] {"findWorksByTask", "findReadyAssetsByTask"}) {
            var method = AssetMapper.class.getMethod(methodName, long.class);
            assertThat(String.join(" ", method.getAnnotation(org.apache.ibatis.annotations.Select.class).value())
                    .toLowerCase(java.util.Locale.ROOT)).doesNotContain("for update", "for share");
        }
    }

    @SuppressWarnings("unchecked")
    private void prepareCandidates(int requested, int reported) {
        candidateWorks = new TreeMap<>();
        candidateAssets = new TreeMap<>();
        AtomicReference<String> taskStatus = new AtomicReference<>("RUNNING");
        AtomicReference<TaskRecords.PointAccount> account = new AtomicReference<>(
                new TaskRecords.PointAccount(41, 20, requested * 7, 1, now(), now()));
        workflow = mock(WorkflowTaskLifecycleService.class);
        ObjectProvider<WorkflowTaskLifecycleService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(workflow);
        service.setWorkflowLifecycle(provider);
        for (int index = 1; index <= reported; index++) candidateWorks.put(index, candidate(index, "PROCESSING", null));
        when(assets.lockWork(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(candidateWorks.get((int) ((long) invocation.getArgument(0) - 6000))));
        when(tasks.lockTask(9002L)).thenAnswer(ignored -> Optional.of(candidateTask(requested, taskStatus.get())));
        when(tasks.findExecution(8101L)).thenReturn(Optional.of(execution("RESULT_READY")));
        when(assets.insertAsset(any())).thenAnswer(invocation -> {
            AssetRecords.Asset asset = invocation.getArgument(0);
            candidateAssets.put(asset.sourceArtifactIndex(), asset);
            return 1;
        });
        when(assets.completeWork(anyLong(), eq(1), eq(CLAIM_A), any())).thenAnswer(invocation -> {
            int index = (int) ((long) invocation.getArgument(0) - 6000);
            candidateWorks.put(index, candidate(index, "SUCCEEDED", null));
            return 1;
        });
        when(assets.failWork(anyLong(), eq(1), eq(CLAIM_A), anyString(), any())).thenAnswer(invocation -> {
            int index = (int) ((long) invocation.getArgument(0) - 6000);
            candidateWorks.put(index, candidate(index, "FAILED", invocation.getArgument(3)));
            return 1;
        });
        when(assets.findWorksByTask(9002L)).thenAnswer(ignored -> java.util.List.copyOf(candidateWorks.values()));
        when(assets.findReadyAssetsByTask(9002L)).thenAnswer(ignored -> java.util.List.copyOf(candidateAssets.values()));
        when(assets.finishImageCandidatesTask(eq(9002L), anyString(), anyString(), anyInt(), anyString(), any(), any()))
                .thenAnswer(invocation -> { taskStatus.set(invocation.getArgument(1)); return 1; });
        when(assets.finalizeImageCandidatesReservation(eq(9002L), anyInt(), anyInt(), anyString(), any())).thenReturn(1);
        when(tasks.completeResultReadyExecution(eq(8101L), any())).thenReturn(1);
        when(tasks.failResultReadyExecution(eq(8101L), anyString(), any())).thenReturn(1);
        when(tasks.insertTaskEvent(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(tasks.settlePoints(eq(41L), anyInt(), any())).thenAnswer(invocation -> {
            TaskRecords.PointAccount previous = account.get();
            account.set(new TaskRecords.PointAccount(41, previous.availablePoints(),
                    previous.frozenPoints() - (int) invocation.getArgument(1), previous.version() + 1, now(), now()));
            return 1;
        });
        when(tasks.releasePoints(eq(41L), anyInt(), any())).thenAnswer(invocation -> {
            TaskRecords.PointAccount previous = account.get();
            int points = invocation.getArgument(1);
            account.set(new TaskRecords.PointAccount(41, previous.availablePoints() + points,
                    previous.frozenPoints() - points, previous.version() + 1, now(), now()));
            return 1;
        });
        when(tasks.findPointAccount(41L)).thenAnswer(ignored -> Optional.of(account.get()));
        when(tasks.lockPointAccount(41L)).thenAnswer(ignored -> Optional.of(account.get()));
        when(tasks.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(),
                anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(conversations.nextEventSequence(anyLong(), anyLong())).thenReturn(12L);
        when(conversations.insertEvent(any())).thenReturn(1);
    }

    private Optional<com.guanghe.backend.conversation.ConversationEventRecord> completeCandidate(int index) {
        return service.complete(6000L + index, 1, CLAIM_A,
                new DownloadedArtifact(Path.of("test.png"), "image/png", 80L + index,
                        "a".repeat(64), 100 + index, 24), "TEST_BYPASS");
    }

    private Optional<com.guanghe.backend.conversation.ConversationEventRecord> failCandidate(int index) {
        return service.failOrRetry(6000L + index, 1, CLAIM_A, "CONTENT_REJECTED", false);
    }

    private AssetRecords.IngestionWork candidate(int index, String status, String errorCode) {
        OffsetDateTime now = now();
        boolean terminal = !"PROCESSING".equals(status);
        return new AssetRecords.IngestionWork(6000L + index, 7000L + index, 9002, 8101, 1, 41, 101, 102L, index,
                "QWEN_IMAGE", "qwen-image-3.0", UUID.randomUUID(), "qwen-task-1",
                terminal ? null : "https://example.com/result.png?signature=not-logged",
                terminal ? null : now.plusHours(1), "image/png", null, null, 32, 24,
                "zhixiaohe/test/users/41/assets/" + (7000 + index) + "/original.png", status, 1, 3, now,
                terminal ? null : now.plusMinutes(2), terminal ? null : CLAIM_A, errorCode,
                terminal ? now : null, now, now);
    }

    private TaskRecords.Task candidateTask(int count, String status) {
        OffsetDateTime now = now();
        return new TaskRecords.Task(9002, 41, 101, 102L, 9001, 7001, "IMAGE", status,
                "ASSET_INGESTION_PENDING", 1, "{\"parameters\":{\"count\":" + count + "}}", "b".repeat(64),
                6001, count * 7, 0, "{}", null, "TASK_CONFIRM", UUID.randomUUID(), "a".repeat(64),
                now, now, null, now, now);
    }

    private AssetRecords.IngestionWork work(String status, int attempt, OffsetDateTime lease) {
        return work(status, attempt, lease, "PROCESSING".equals(status) ? CLAIM_A : null);
    }

    private AssetRecords.IngestionWork work(String status, int attempt, OffsetDateTime lease, UUID claimToken) {
        OffsetDateTime now = now();
        boolean terminal = "SUCCEEDED".equals(status) || "FAILED".equals(status);
        return new AssetRecords.IngestionWork(6001, 6002, 9002, 8101, 1, 41, 101, 102L, 1,
                "QWEN_IMAGE", "qwen-image-3.0", UUID.randomUUID(), "qwen-task-1",
                terminal ? null : "https://example.com/result.png?signature=not-logged",
                terminal ? null : now.plusHours(1), "image/png",
                null, null, 32, 24, "zhixiaohe/test/users/41/assets/6002/original.png", status,
                attempt, 3, now, lease, claimToken, null, terminal ? now : null, now, now);
    }

    private TaskRecords.Task task(String status) {
        OffsetDateTime now = now();
        return new TaskRecords.Task(9002, 41, 101, 102L, 9001, 7001, "IMAGE", status,
                "ASSET_INGESTION_PENDING", 1, "{}", "b".repeat(64), 6001, 7, 0, "{}", null,
                "TASK_CONFIRM", UUID.randomUUID(), "a".repeat(64), now, now, null, now, now);
    }

    private TaskRecords.Execution execution(String status) {
        OffsetDateTime now = now();
        return new TaskRecords.Execution(8101, 9002, 41, 1, status, "worker", "worker-1", "bundle",
                "c".repeat(64), now, "ASSET_INGESTION_PENDING", "QWEN_IMAGE", "qwen-image-3.0",
                UUID.randomUUID(), "qwen-task-1", "CONFIRMED", 100, "{}", null, now, now,
                null, now, now);
    }

    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
