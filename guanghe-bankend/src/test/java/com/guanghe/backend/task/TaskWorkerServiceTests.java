package com.guanghe.backend.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.article.ArticleService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.ConversationMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskWorkerServiceTests {
    private TaskControlMapper mapper;
    private ConversationMapper conversations;
    private ArticleService articles;
    private TaskControlService service;
    private ObjectMapper json;
    private final AtomicLong ids = new AtomicLong(8000);

    @BeforeEach
    void setUp() {
        mapper=mock(TaskControlMapper.class); conversations=mock(ConversationMapper.class);
        articles=mock(ArticleService.class);
        SnowflakeIdGenerator idGenerator=mock(SnowflakeIdGenerator.class);
        when(idGenerator.nextId()).thenAnswer(ignored -> ids.incrementAndGet());
        json=new ObjectMapper().findAndRegisterModules();
        PaidTaskProperties properties=new PaidTaskProperties(); properties.setEnabled(true);
        properties.setTokenSecret("worker-test-secret-material-at-least-32-bytes");
        properties.setImageMockPricingVersionId(6001);properties.setImageMockPoints(7);
        properties.setVideoTestPricingVersionId(7001);properties.setVideoTestPoints(11);
        properties.setExecutionLease(Duration.ofMinutes(2));
        service=withCreativeSources(new TaskControlService(mapper,conversations,idGenerator,json,properties,
                assetProperties(),
                mock(com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory.class),
                null, articles));
    }

    @Test
    void videoContextAndWanResultCreateMp4IngestionWithoutEarlySettlement() {
        TaskRecords.Task running=videoTask("RUNNING","CLAIMED");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executionCaptor.getValue()));
        when(mapper.findTaskInternal(running.id())).thenReturn(Optional.of(running));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        allowSuccessfulResult(running);

        TaskDtos.ExecutionContextResponse context=service.executionContext(running.id(),1,claim.executionToken());
        assertThat(context.taskType()).isEqualTo("VIDEO");
        assertThat(context.budget().maxProviderCalls()).isEqualTo(61);
        assertThat(context.budget().maxImages()).isNull();
        assertThat(context.budget().maxVideos()).isEqualTo(1);
        assertThat(context.budget().maxVideoSeconds()).isEqualTo(30);

        TaskDtos.ResultArtifactsRequest result=wanVideoResult(claim.executionToken());
        assertThat(service.reportResult(running.id(),result).response().taskStatus()).isEqualTo("RUNNING");
        ArgumentCaptor<com.guanghe.backend.asset.AssetRecords.IngestionWork> work=
                ArgumentCaptor.forClass(com.guanghe.backend.asset.AssetRecords.IngestionWork.class);
        verify(mapper).insertAssetIngestionWork(work.capture());
        assertThat(work.getValue().providerCode()).isEqualTo("WAN_VIDEO");
        assertThat(work.getValue().providerModel()).isEqualTo("wan3.0-video");
        assertThat(work.getValue().providerUrlExpiresAt()).isNull();
        assertThat(work.getValue().ingestionDeadlineAt()).isAfter(now());
        assertThat(work.getValue().expectedDurationSeconds()).isEqualByComparingTo(BigDecimal.valueOf(5));
        assertThat(work.getValue().objectKey()).endsWith(".mp4");
        verify(mapper,never()).settlePoints(anyLong(),anyInt(),any());
    }

    @Test
    void regionEditUsesFrozenProviderAndAcceptsOnlyMeasuredLaterArtifactMetadata() throws Exception {
        TaskRecords.Task original = task("RUNNING", "CLAIMED");
        ObjectNode snapshot = (ObjectNode) json.readTree(original.inputSnapshotJson());
        ((ObjectNode) snapshot.path("parameters")).put("sourceMode", "REGION_EDIT").put("maskAssetId", "9911");
        TaskRecords.Task running = new TaskRecords.Task(original.id(), original.userId(), original.spaceId(), null,
                original.conversationId(), original.draftId(), original.taskType(), original.status(), original.stageCode(),
                1, snapshot.toString(), original.inputHash(), original.pricingVersionId(), original.quotedPoints(), 0,
                "{}", null, original.operationScope(), original.idempotencyKey(), original.requestHash(),
                original.queuedAt(), original.startedAt(), null, original.createdAt(), original.updatedAt());
        TaskDtos.ClaimResponse claim = claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executions = ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executions.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executions.getValue()));
        when(mapper.findTaskInternal(running.id())).thenReturn(Optional.of(running));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        allowSuccessfulResult(running);
        var context = service.executionContext(running.id(), 1, claim.executionToken());
        assertThat(context.provider()).isEqualTo("WAN_IMAGE_EDIT");
        assertThat(context.model()).isEqualTo("wanx2.1-imageedit");
        assertValidationFailure(running.id(), qwenResult(claim.executionToken()));
        TaskDtos.ResultArtifactsRequest qwen = qwenResult(claim.executionToken());
        ObjectNode artifact = (ObjectNode) qwen.artifacts().getFirst();
        artifact.putNull("contentType");
        ((ObjectNode) artifact.path("metadata")).putNull("width").putNull("height");
        ((ObjectNode) artifact.path("metadata")).remove("outputImageType");
        ((ObjectNode) qwen.usage()).putNull("providerCost").putNull("currency");
        TaskDtos.ResultArtifactsRequest edit = new TaskDtos.ResultArtifactsRequest(qwen.eventId(), 1,
                claim.executionToken(), "WAN_IMAGE_EDIT", "wanx2.1-imageedit", qwen.requestId(), "edit-task-1",
                "CONFIRMED", qwen.artifacts(), qwen.usage(), qwen.latencyMs());
        assertThat(service.reportResult(running.id(), edit).response().taskStatus()).isEqualTo("RUNNING");
        ArgumentCaptor<com.guanghe.backend.asset.AssetRecords.IngestionWork> work =
                ArgumentCaptor.forClass(com.guanghe.backend.asset.AssetRecords.IngestionWork.class);
        verify(mapper).insertAssetIngestionWork(work.capture());
        assertThat(work.getValue().expectedContentType()).isNull();
        assertThat(work.getValue().expectedWidth()).isNull();
        assertThat(work.getValue().expectedHeight()).isNull();
        assertThat(work.getValue().providerCode()).isEqualTo("WAN_IMAGE_EDIT");
        verify(mapper, never()).settlePoints(anyLong(), anyInt(), any());
    }

    @Test
    void previousImageResultUsesQwenReferenceGenerationAndHasNoRegionMask() throws Exception {
        TaskRecords.Task original = task("RUNNING", "CLAIMED");
        ObjectNode snapshot = (ObjectNode) json.readTree(original.inputSnapshotJson());
        ((ObjectNode) snapshot.path("parameters")).put("sourceMode", "PREVIOUS_IMAGE_RESULT")
                .putArray("referenceAssetIds").add("9910");
        TaskRecords.Task running = new TaskRecords.Task(original.id(), original.userId(), original.spaceId(), null,
                original.conversationId(), original.draftId(), original.taskType(), original.status(), original.stageCode(),
                1, snapshot.toString(), original.inputHash(), original.pricingVersionId(), original.quotedPoints(), 0,
                "{}", null, original.operationScope(), original.idempotencyKey(), original.requestHash(),
                original.queuedAt(), original.startedAt(), null, original.createdAt(), original.updatedAt());
        TaskDtos.ClaimResponse claim = claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executions = ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executions.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executions.getValue()));
        when(mapper.findTaskInternal(running.id())).thenReturn(Optional.of(running));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        allowSuccessfulResult(running);

        var context = service.executionContext(running.id(), 1, claim.executionToken());

        assertThat(context.provider()).isEqualTo("QWEN_IMAGE");
        assertThat(context.model()).isEqualTo("qwen-image-3.0");
        assertThat(context.controlledMaskAsset()).isNull();
        assertThat(context.budget().maxProviderCalls()).isEqualTo(1);
        TaskDtos.ResultArtifactsRequest qwen = qwenResult(claim.executionToken());
        assertThat(service.reportResult(running.id(), qwen).response().taskStatus()).isEqualTo("RUNNING");
        ArgumentCaptor<com.guanghe.backend.asset.AssetRecords.IngestionWork> work =
                ArgumentCaptor.forClass(com.guanghe.backend.asset.AssetRecords.IngestionWork.class);
        verify(mapper).insertAssetIngestionWork(work.capture());
        assertThat(work.getValue().expectedWidth()).isEqualTo(2048);
        assertThat(work.getValue().expectedHeight()).isEqualTo(1152);
        assertThat(work.getValue().providerCode()).isEqualTo("QWEN_IMAGE");
    }

    @Test
    void ordinaryImageCannotAcceptRegionEditProviderResultOrUnknownContinuation() {
        TaskRecords.Task running = task("RUNNING", "CLAIMED");
        TaskDtos.ClaimResponse claim = claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executions = ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executions.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executions.getValue()));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        TaskDtos.ResultArtifactsRequest qwen = qwenResult(claim.executionToken());
        assertValidationFailure(running.id(), new TaskDtos.ResultArtifactsRequest(qwen.eventId(), 1,
                claim.executionToken(), "WAN_IMAGE_EDIT", "wanx2.1-imageedit", qwen.requestId(), "edit-task-1",
                "CONFIRMED", qwen.artifacts(), qwen.usage(), qwen.latencyMs()));
        ObjectNode failure = json.createObjectNode().put("code", "WAN_IMAGE_EDIT_TIMEOUT")
                .put("submissionState", "UNKNOWN").put("provider", "WAN_IMAGE_EDIT")
                .put("model", "wanx2.1-imageedit").put("providerRequestId", providerRequestId().toString())
                .put("providerTaskId", "edit-task-1");
        assertEventValidationFailure(running.id(), new TaskDtos.ExecutionEventRequest(UUID.randomUUID(), 1,
                claim.executionToken(), "TASK_FAILED", "PROVIDER_RESULT_UNKNOWN", failure));
        verify(mapper, never()).insertAssetIngestionWork(any());
    }

    @Test
    void videoUnknownRecoveryKeepsOriginalProviderIdentityAndRoutesToVideoQueue() {
        TaskRecords.Task running=videoTask("RUNNING","CLAIMED");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executionCaptor.getValue()));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        when(mapper.recordUnknownExecution(anyLong(),anyInt(),anyString(),anyString(),anyString(),any(),
                anyString(),anyString(),any())).thenReturn(1);
        when(mapper.failOrRetryTask(anyLong(),anyString(),anyString(),anyString(),any())).thenReturn(1);
        when(mapper.insertOutbox(any(),anyString(),anyString())).thenReturn(1);
        when(conversations.nextEventSequence(anyLong(),anyLong())).thenReturn(8L);
        when(conversations.insertEvent(any())).thenReturn(1);
        ObjectNode details=json.createObjectNode();
        details.put("code","WAN_VIDEO_POLL_TIMEOUT");details.put("provider","WAN_VIDEO");
        details.put("model","wan3.0-video");details.put("providerRequestId",providerRequestId().toString());
        details.put("providerTaskId","wan-task-1");details.put("submissionState","UNKNOWN");

        service.reportEvent(running.id(),new TaskDtos.ExecutionEventRequest(UUID.randomUUID(),1,
                claim.executionToken(),"TASK_FAILED","PROVIDER_RESULT_UNKNOWN",details));

        verify(mapper).recordUnknownExecution(eq(running.id()),eq(1),anyString(),eq("WAN_VIDEO"),
                eq("wan3.0-video"),eq(providerRequestId()),eq("wan-task-1"),
                eq("WAN_VIDEO_POLL_TIMEOUT"),any());
        verify(mapper).insertOutbox(any(),eq("zhixiaohe.task"),eq("agent.video"));
    }

    @Test
    void executionBeyondInitialLeaseStaysRunningWhileHeartbeatsContinue() {
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-09-07T12:00:00Z");
        AtomicReference<OffsetDateTime> clock = new AtomicReference<>(startedAt);
        AtomicReference<OffsetDateTime> lease = new AtomicReference<>(startedAt.plusMinutes(2));
        AtomicReference<OffsetDateTime> lastHeartbeat = new AtomicReference<>();
        String token = "v1.lease-heartbeat-token";
        TaskRecords.Task running = task("RUNNING", "PROVIDER_GENERATING");
        service = serviceAt(clock);
        stubRenewingExecution(running, token, lease, lastHeartbeat);
        when(mapper.findExpiredTaskId(any())).thenAnswer(invocation ->
                lease.get().isBefore(invocation.getArgument(0, OffsetDateTime.class))
                        ? Optional.of(running.id()) : Optional.empty());

        clock.set(startedAt.plusSeconds(90));
        TaskDtos.HeartbeatResponse first = service.heartbeat(running.id(),
                new TaskDtos.HeartbeatRequest(1, token, "PROVIDER_GENERATING"));
        clock.set(startedAt.plusMinutes(3));
        TaskDtos.HeartbeatResponse second = service.heartbeat(running.id(),
                new TaskDtos.HeartbeatRequest(1, token, "PROVIDER_GENERATING"));

        clock.set(startedAt.plusMinutes(4));
        assertThat(service.recoverExpiredExecution()).isEmpty();
        assertThat(first.leaseExpiresAt()).isEqualTo(startedAt.plusSeconds(210));
        assertThat(second.leaseExpiresAt()).isEqualTo(startedAt.plusMinutes(5));
        assertThat(lastHeartbeat.get()).isEqualTo(startedAt.plusMinutes(3));
        var order = inOrder(mapper);
        order.verify(mapper).findLatestExecution(running.id());
        order.verify(mapper).lockTask(running.id());
        order.verify(mapper).heartbeat(eq(running.id()), eq(1), anyString(),
                eq("PROVIDER_GENERATING"), eq(first.leaseExpiresAt()), eq(startedAt.plusSeconds(90)));
        order.verify(mapper).updateProgress(running.id(), "PROVIDER_GENERATING",
                startedAt.plusSeconds(90));
        order.verify(mapper).findLatestExecution(running.id());
        order.verify(mapper).lockTask(running.id());
        order.verify(mapper).heartbeat(eq(running.id()), eq(1), anyString(),
                eq("PROVIDER_GENERATING"), eq(second.leaseExpiresAt()), eq(startedAt.plusMinutes(3)));
        verify(mapper, never()).expireExecution(anyLong(), any());
        verify(mapper, never()).scheduleTaskRetry(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void articleWatchdogTerminatesWithoutRetryOnlyAfterHeartbeatsStopAndRenewedLeasePasses() {
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-09-07T12:00:00Z");
        AtomicReference<OffsetDateTime> clock = new AtomicReference<>(startedAt);
        AtomicReference<OffsetDateTime> lease = new AtomicReference<>(startedAt.plusMinutes(2));
        AtomicReference<OffsetDateTime> lastHeartbeat = new AtomicReference<>();
        String token = "v1.stopped-heartbeat-token";
        TaskRecords.Task running = asArticle(task("RUNNING", "PROVIDER_GENERATING"));
        service = serviceAt(clock);
        stubRenewingExecution(running, token, lease, lastHeartbeat);
        when(mapper.findExpiredTaskId(any())).thenAnswer(invocation ->
                lease.get().isBefore(invocation.getArgument(0, OffsetDateTime.class))
                        ? Optional.of(running.id()) : Optional.empty());
        when(mapper.lockExpiredExecution(eq(running.id()), any())).thenAnswer(invocation ->
                lease.get().isBefore(invocation.getArgument(1, OffsetDateTime.class))
                        ? Optional.of(leasedExecution(token, lease.get(), lastHeartbeat.get()))
                        : Optional.empty());
        when(mapper.expireExecution(anyLong(), any())).thenReturn(1);
        allowUnconfirmedTermination(running);

        clock.set(startedAt.plusSeconds(90));
        TaskDtos.HeartbeatResponse heartbeat = service.heartbeat(running.id(),
                new TaskDtos.HeartbeatRequest(1, token, "PROVIDER_GENERATING"));
        clock.set(heartbeat.leaseExpiresAt().minusSeconds(1));
        assertThat(service.recoverExpiredExecution()).isEmpty();
        verify(mapper, never()).expireExecution(anyLong(), any());
        verify(mapper, never()).releasePoints(anyLong(), anyInt(), any());

        clock.set(heartbeat.leaseExpiresAt().plusSeconds(1));
        TaskControlService.ReportedTask recovered = service.recoverExpiredExecution().orElseThrow();

        assertThat(recovered.response().taskStatus()).isEqualTo("SYSTEM_TERMINATED");
        assertThat(recovered.browserEvent().eventType()).isEqualTo("task.failed");
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(terminatedWithoutReceipt(running)));
        assertThat(service.recoverExpiredExecution()).isEmpty();
        verify(mapper, times(1)).expireExecution(anyLong(), eq(clock.get()));
        verify(mapper, times(1)).terminateUnconfirmedTask(running.id(), clock.get());
        verify(mapper, times(1)).releaseReservation(running.id(), running.quotedPoints(), clock.get());
        verify(mapper, times(1)).releasePoints(running.userId(), running.quotedPoints(), clock.get());
        verify(articles, times(1)).markGenerationFailed(running, clock.get());
        org.mockito.Mockito.verifyNoMoreInteractions(articles);
        verify(mapper, never()).scheduleTaskRetry(anyLong(), anyString(), anyString(), any());
        verify(mapper, never()).insertOutbox(any(), anyString(), anyString());
        verify(mapper, never()).insertExecution(any());
        verify(mapper, never()).settlePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
    }

    @Test
    void wanVideoResultRejectsProviderMixingUnsafeUrlAndDurationMismatch() {
        TaskRecords.Task running=videoTask("RUNNING","CLAIMED");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executionCaptor.getValue()));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));

        TaskDtos.ResultArtifactsRequest mixed=wanVideoResult(claim.executionToken());
        mixed=new TaskDtos.ResultArtifactsRequest(mixed.eventId(),mixed.executionNo(),mixed.executionToken(),
                "QWEN_IMAGE",mixed.model(),mixed.requestId(),mixed.providerTaskId(),mixed.submissionState(),
                mixed.artifacts(),mixed.usage(),mixed.latencyMs());
        assertValidationFailure(running.id(),mixed);

        TaskDtos.ResultArtifactsRequest unsafe=wanVideoResult(claim.executionToken());
        ((ObjectNode)unsafe.artifacts().getFirst()).put("providerUrl","http://result.example/video.mp4");
        assertValidationFailure(running.id(),unsafe);

        TaskDtos.ResultArtifactsRequest wrongDuration=wanVideoResult(claim.executionToken());
        ((ObjectNode)wrongDuration.artifacts().getFirst().path("metadata")).put("durationSeconds",9);
        assertValidationFailure(running.id(),wrongDuration);
        verify(mapper,never()).insertAssetIngestionWork(any());
        verify(mapper,never()).settlePoints(anyLong(),anyInt(),any());
    }

    @Test
    void claimIsBoundToOutboxAndDuplicateReturnsSameExecutionWithoutCreatingAnother() {
        TaskRecords.Task queued=task("QUEUED");
        when(mapper.findOutbox(7001,queued.id())).thenReturn(Optional.of(
                new TaskRecords.Outbox(7001,41,queued.id(),1,"{}","00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01","PENDING",0,now(),now())));
        when(mapper.lockTask(queued.id())).thenReturn(Optional.of(queued));
        when(mapper.claimTask(anyLong(),anyString(),any())).thenReturn(1);
        when(mapper.insertExecution(any())).thenReturn(1);
        when(mapper.insertInbox(anyLong(),anyString(),anyLong(),anyLong(),anyLong(),anyLong(),anyString(),any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(),anyLong(),anyLong(),anyLong(),any(),any(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyString(),any())).thenReturn(1);
        TaskDtos.ClaimRequest request=new TaskDtos.ClaimRequest("agent-image-dev", "7001","worker-1",1,"bundle-v1");

        TaskDtos.ClaimResponse claimed=service.claim(queued.id(),request).response();

        assertThat(claimed.claimed()).isTrue();assertThat(claimed.executionToken()).startsWith("v1.");
        ArgumentCaptor<TaskRecords.Execution> captured=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(captured.capture());
        assertThat(captured.getValue().executionTokenHash()).matches("[0-9a-f]{64}");
        when(mapper.findInbox("agent-image-dev",7001)).thenReturn(Optional.of(
                new TaskRecords.Inbox(1,"agent-image-dev",7001,queued.id(),41,captured.getValue().id(),"CLAIMED",now())));
        when(mapper.findExecution(captured.getValue().id())).thenReturn(Optional.of(captured.getValue()));
        TaskDtos.ClaimResponse replay=service.claim(queued.id(),request).response();
        assertThat(replay.claimed()).isFalse();assertThat(replay.disposition()).isEqualTo("DUPLICATE");
        assertThat(replay.executionToken()).isEqualTo(claimed.executionToken());
    }

    @Test
    void terminalTaskIsAcknowledgedWithoutCreatingAnExecution() {
        TaskRecords.Task terminal=task("SUCCEEDED");
        when(mapper.findOutbox(7001,terminal.id())).thenReturn(Optional.of(
                new TaskRecords.Outbox(7001,41,terminal.id(),1,"{}","00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01","PUBLISHED",1,now(),now())));
        when(mapper.lockTask(terminal.id())).thenReturn(Optional.of(terminal));
        when(mapper.insertInbox(anyLong(),anyString(),anyLong(),anyLong(),anyLong(),any(),anyString(),any()))
                .thenReturn(1);

        TaskDtos.ClaimResponse response=service.claim(terminal.id(),
                new TaskDtos.ClaimRequest("agent-image-dev","7001","worker-1",1,"bundle-v1")).response();

        assertThat(response.claimed()).isFalse();
        assertThat(response.disposition()).isEqualTo("TERMINAL");
        assertThat(response.executionNo()).isNull();
        verify(mapper,never()).insertExecution(any());
        verify(mapper,never()).claimTask(anyLong(),anyString(),any());
    }

    @Test
    void duplicateInboxCannotBeReplayedAgainstAnotherTaskId() {
        when(mapper.findInbox("agent-image-dev",7001)).thenReturn(Optional.of(
                new TaskRecords.Inbox(1,"agent-image-dev",7001,9002,41,null,"TERMINAL",now())));

        assertThatThrownBy(() -> service.claim(9999,
                new TaskDtos.ClaimRequest("agent-image-dev","7001","worker-1",1,"bundle-v1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(com.guanghe.backend.common.api.ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void immutableContextIsReturnedAndValidatedMockResultStillSettlesPointsExactlyOnce() {
        OffsetDateTime beforeMidnight = OffsetDateTime.parse("2026-09-24T15:59:59Z");
        OffsetDateTime afterMidnight = beforeMidnight.plusSeconds(2);
        AtomicReference<OffsetDateTime> clockTime = new AtomicReference<>(beforeMidnight);
        Clock accountingClock = mock(Clock.class);
        when(accountingClock.instant()).thenAnswer(ignored -> clockTime.get().toInstant());
        service.setAccountingClock(accountingClock);
        TaskRecords.Task running=task("RUNNING");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        TaskRecords.Execution execution=executionCaptor.getValue();
        when(mapper.findTaskInternal(running.id())).thenReturn(Optional.of(running));
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(execution));
        TaskDtos.ExecutionContextResponse context=service.executionContext(running.id(),1,claim.executionToken());
        assertThat(context.inputSnapshot().path("count").asInt()).isEqualTo(1);
        assertThat(context.authorizedContext().path("space").path("id").asText()).isEqualTo("101");
        assertThat(context.resumeOnly()).isFalse();
        assertThat(context.providerContinuation()).isNull();

        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        when(mapper.insertTaskEvent(anyLong(),anyLong(),anyLong(),anyLong(),any(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyString(),any())).thenReturn(1);
        when(mapper.completeExecution(anyLong(),anyInt(),anyString(),anyString(),anyString(),any(),anyString(),anyInt(),anyString(),any())).thenReturn(1);
        when(mapper.completeTask(anyLong(),anyString(),any())).thenReturn(1);
        when(mapper.settleReservation(anyLong(),anyInt(),any())).thenReturn(1);
        when(mapper.settlePoints(anyLong(),anyInt(),any())).thenReturn(1);
        when(mapper.lockPointAccount(41)).thenAnswer(ignored -> {
            clockTime.set(afterMidnight);
            return Optional.of(new TaskRecords.PointAccount(41, 13, 7, 1, beforeMidnight, beforeMidnight));
        });
        when(mapper.findPointAccount(41)).thenReturn(Optional.of(new TaskRecords.PointAccount(41,13,0,2,now(),now())));
        when(mapper.insertLedger(anyLong(),anyLong(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),anyLong(),anyString(),anyString(),any())).thenReturn(1);
        when(conversations.nextEventSequence(anyLong(),anyLong())).thenReturn(2L);
        when(conversations.insertEvent(any())).thenReturn(1);
        TaskDtos.ResultArtifactsRequest result=result(claim.executionToken(),"LOCAL_TASK_PROVIDER");
        TaskDtos.InternalAccepted accepted=service.reportResult(running.id(),result).response();
        assertThat(accepted.taskStatus()).isEqualTo("SUCCEEDED");
        verify(mapper).settlePoints(anyLong(),anyInt(),any());
        verify(mapper).insertLedger(anyLong(),anyLong(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),anyLong(),anyString(),anyString(),any());
        verify(mapper,never()).insertAssetIngestionWork(any());

        ArgumentCaptor<String> requestHash=ArgumentCaptor.forClass(String.class);
        verify(mapper).insertTaskEvent(anyLong(),eq(running.id()),anyLong(),anyLong(),eq(result.eventId()),
                requestHash.capture(),eq("RUNNING"),eq("SUCCEEDED"),eq("RESULT_REPORTED"),eq("PYTHON"),
                any(),any(),anyString(),any());
        when(mapper.findTaskEventRequestHash(running.id(),result.eventId()))
                .thenReturn(Optional.of(requestHash.getValue()));
        when(mapper.findTaskInternal(running.id())).thenReturn(Optional.of(task("SUCCEEDED")));
        TaskDtos.InternalAccepted replay=service.reportResult(running.id(),result).response();
        assertThat(replay.taskStatus()).isEqualTo("SUCCEEDED");
        verify(mapper,times(1)).completeTask(anyLong(),anyString(),any());
        verify(mapper,times(1)).settlePoints(anyLong(),anyInt(),any());
        verify(mapper).lockPointAccount(41);
        verify(accountingClock).instant();
        verify(mapper).settlePoints(41, 7, afterMidnight);
        verify(mapper).insertLedger(anyLong(), eq(41L), eq(0), eq(-7), eq(13), eq(0),
                eq("TASK_SETTLE"), eq(running.id()), eq("task-settle:" + running.id()),
                anyString(), eq(afterMidnight));
    }

    @Test
    void articleResultUsesAccountingTimeAfterAccountLockAndReplayDoesNotSettleAgain() {
        OffsetDateTime beforeMidnight = OffsetDateTime.parse("2026-09-24T15:59:59Z");
        OffsetDateTime afterMidnight = beforeMidnight.plusSeconds(2);
        AtomicReference<OffsetDateTime> clockTime = new AtomicReference<>(beforeMidnight);
        Clock accountingClock = mock(Clock.class);
        when(accountingClock.instant()).thenAnswer(ignored -> clockTime.get().toInstant());
        service.setAccountingClock(accountingClock);
        TaskRecords.Task running = asArticle(task("RUNNING"));
        String token = "article-token";
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(
                leasedExecution(token, now().plusMinutes(5), now())));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        ArticleService.ValidatedResult validated = new ArticleService.ValidatedResult(
                "BODY", 5001, 2, List.of(), "标题", null, "<p>正文</p>", "正文",
                json.createObjectNode().put("articleId", "5001"));
        when(articles.validateResult(eq(running), eq("DEEPSEEK"), eq("deepseek-v4-flash"),
                any(), any(), eq(100))).thenReturn(validated);
        when(articles.applyGeneratedResult(eq(running), eq(validated), any())).thenReturn(true);
        allowSuccessfulResult(running);
        when(mapper.insertTaskEvent(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(),
                anyString(), anyString(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(1);
        when(mapper.completeExecution(eq(running.id()), eq(1), anyString(), eq("DEEPSEEK"),
                eq("deepseek-v4-flash"), any(), eq(null), eq(100), anyString(), any())).thenReturn(1);
        when(mapper.lockPointAccount(41)).thenAnswer(ignored -> {
            clockTime.set(afterMidnight);
            return Optional.of(new TaskRecords.PointAccount(41, 13, 7, 1, beforeMidnight, beforeMidnight));
        });
        TaskDtos.ResultArtifactsRequest request = new TaskDtos.ResultArtifactsRequest(UUID.randomUUID(),
                1, token, "DEEPSEEK", "deepseek-v4-flash", UUID.randomUUID(), null, "CONFIRMED",
                List.of(json.createObjectNode().put("artifactType", "ARTICLE_CONTENT")),
                json.createObjectNode(), 100);

        assertThat(service.reportResult(running.id(), request).response().taskStatus()).isEqualTo("SUCCEEDED");

        ArgumentCaptor<String> requestHash = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertTaskEvent(anyLong(), eq(running.id()), anyLong(), anyLong(), eq(request.eventId()),
                requestHash.capture(), eq("RUNNING"), eq("RUNNING"), eq("ARTICLE_RESULT_REPORTED"),
                eq("PYTHON"), any(), any(), anyString(), any());
        when(mapper.findTaskEventRequestHash(running.id(), request.eventId()))
                .thenReturn(Optional.of(requestHash.getValue()));
        when(mapper.findTaskInternal(running.id())).thenReturn(Optional.of(asArticle(task("SUCCEEDED"))));
        assertThat(service.reportResult(running.id(), request).response().taskStatus()).isEqualTo("SUCCEEDED");

        var ordered = inOrder(articles, mapper, conversations);
        ordered.verify(articles).lockAndRequireCurrentVersion(41, 5001, 2);
        ordered.verify(articles).applyGeneratedResult(eq(running), eq(validated), any());
        ordered.verify(mapper).settleReservation(eq(running.id()), eq(7), any());
        ordered.verify(mapper).lockPointAccount(41);
        ordered.verify(mapper).settlePoints(41, 7, afterMidnight);
        ordered.verify(mapper).insertLedger(anyLong(), eq(41L), eq(0), eq(-7), eq(13), eq(0),
                eq("TASK_SETTLE"), eq(running.id()), eq("task-settle:" + running.id()),
                anyString(), eq(afterMidnight));
        ordered.verify(conversations).nextEventSequence(running.conversationId(), running.userId());
        verify(mapper).lockPointAccount(41);
        verify(accountingClock).instant();
        verify(mapper).settlePoints(41, 7, afterMidnight);
    }

    @Test
    void qwenImageResultCreatesRecoverableIngestionWithoutSettlingOrExposingProviderUrl() throws Exception {
        TaskRecords.Task running=task("RUNNING");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executionCaptor.getValue()));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        allowSuccessfulResult(running);
        TaskDtos.ResultArtifactsRequest result=qwenResult(claim.executionToken());

        TaskDtos.InternalAccepted accepted=service.reportResult(running.id(),result).response();

        assertThat(accepted.taskStatus()).isEqualTo("RUNNING");
        ArgumentCaptor<String> usageJson=ArgumentCaptor.forClass(String.class);
        verify(mapper).markExecutionResultReady(eq(running.id()),eq(1),anyString(),eq("QWEN_IMAGE"),
                eq("qwen-image-3.0"),eq(result.requestId()),eq("qwen-task-1"),eq(2400),
                usageJson.capture(),any());
        JsonNode persistedUsage=json.readTree(usageJson.getValue());
        assertThat(persistedUsage.path("providerCost").isNull()).isTrue();
        assertThat(persistedUsage.path("currency").isNull()).isTrue();
        assertThat(persistedUsage.path("providerCostReported").asBoolean()).isFalse();
        ArgumentCaptor<com.guanghe.backend.asset.AssetRecords.IngestionWork> work=
                ArgumentCaptor.forClass(com.guanghe.backend.asset.AssetRecords.IngestionWork.class);
        verify(mapper).insertAssetIngestionWork(work.capture());
        assertThat(work.getValue().providerUrl()).startsWith("https://result.example/");
        assertThat(work.getValue().objectKey()).startsWith("zhixiaohe/test/users/41/assets/");
        verify(mapper,never()).completeTask(anyLong(),anyString(),any());
        verify(mapper,never()).settlePoints(anyLong(),anyInt(),any());

        ArgumentCaptor<String> requestHash=ArgumentCaptor.forClass(String.class);
        verify(mapper).insertTaskEvent(anyLong(),eq(running.id()),anyLong(),anyLong(),eq(result.eventId()),
                requestHash.capture(),eq("RUNNING"),eq("RUNNING"),eq("RESULT_READY"),eq("PYTHON"),
                any(),any(),anyString(),any());
        when(mapper.findTaskEventRequestHash(running.id(),result.eventId()))
                .thenReturn(Optional.of(requestHash.getValue()));
        when(mapper.findTaskInternal(running.id()))
                .thenReturn(Optional.of(task("RUNNING","ASSET_INGESTION_PENDING")));

        assertThat(service.reportResult(running.id(),result).response().taskStatus()).isEqualTo("RUNNING");
        verify(mapper,times(1)).insertAssetIngestionWork(any());
    }

    @Test
    void frozenFourImageBudgetAcceptsPartialResultsWithoutEarlySettlement() {
        TaskRecords.Task running = imageTask(4);
        TaskDtos.ClaimResponse claim = claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executions = ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executions.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executions.getValue()));
        when(mapper.findTaskInternal(running.id())).thenReturn(Optional.of(running));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        allowSuccessfulResult(running);

        assertThat(service.executionContext(running.id(), 1, claim.executionToken()).budget().maxImages())
                .isEqualTo(4);
        TaskDtos.ResultArtifactsRequest result = qwenResults(claim.executionToken(), 2);
        assertThat(service.reportResult(running.id(), result).response().taskStatus()).isEqualTo("RUNNING");
        ArgumentCaptor<com.guanghe.backend.asset.AssetRecords.IngestionWork> works =
                ArgumentCaptor.forClass(com.guanghe.backend.asset.AssetRecords.IngestionWork.class);
        verify(mapper, times(2)).insertAssetIngestionWork(works.capture());
        assertThat(works.getAllValues()).extracting(work -> work.artifactIndex()).containsExactly(1, 2);
        assertThat(works.getAllValues()).extracting(work -> work.assetId()).doesNotHaveDuplicates();
        verify(mapper, never()).settlePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).completeTask(anyLong(), anyString(), any());
    }

    @Test
    void multiImageResultsRejectDuplicateUrlsIndexesAndIncorrectUsage() {
        TaskRecords.Task running = imageTask(4);
        TaskDtos.ClaimResponse claim = claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executions = ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executions.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executions.getValue()));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));

        TaskDtos.ResultArtifactsRequest duplicateUrl = qwenResults(claim.executionToken(), 2);
        ((ObjectNode) duplicateUrl.artifacts().get(1)).put("providerUrl",
                duplicateUrl.artifacts().getFirst().path("providerUrl").asText());
        assertValidationFailure(running.id(), duplicateUrl);
        TaskDtos.ResultArtifactsRequest duplicateIndex = qwenResults(claim.executionToken(), 2);
        ((ObjectNode) duplicateIndex.artifacts().get(1).path("metadata")).put("index", 1);
        assertValidationFailure(running.id(), duplicateIndex);
        TaskDtos.ResultArtifactsRequest incorrectUsage = qwenResults(claim.executionToken(), 2);
        ((ObjectNode) incorrectUsage.usage()).put("images", 4);
        assertValidationFailure(running.id(), incorrectUsage);
        assertValidationFailure(running.id(), qwenResults(claim.executionToken(), 5));
        verify(mapper, never()).insertAssetIngestionWork(any());
        verify(mapper, never()).settlePoints(anyLong(), anyInt(), any());
    }

    @Test
    void providerAndModelMixingCannotBecomeAuthoritative() {
        TaskRecords.Task running=task("RUNNING");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executionCaptor.getValue()));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        TaskDtos.ResultArtifactsRequest mock=result(claim.executionToken(),"QWEN_IMAGE");

        assertThatThrownBy(() -> service.reportResult(running.id(),mock))
                .isInstanceOf(BusinessException.class);
        verify(mapper,never()).completeTask(anyLong(),anyString(),any());
        verify(mapper,never()).settlePoints(anyLong(),anyInt(),any());
    }

    @Test
    void malformedQwenArtifactsAreRejectedBeforeSettlement() {
        TaskRecords.Task running=task("RUNNING");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executionCaptor.getValue()));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));

        TaskDtos.ResultArtifactsRequest http=qwenResult(claim.executionToken());
        ((ObjectNode)http.artifacts().getFirst()).put("providerUrl","http://result.example/image.png");
        assertValidationFailure(running.id(),http);

        TaskDtos.ResultArtifactsRequest missingExpiry=qwenResult(claim.executionToken());
        ((ObjectNode)missingExpiry.artifacts().getFirst()).remove("expiresAt");
        assertValidationFailure(running.id(),missingExpiry);

        TaskDtos.ResultArtifactsRequest expired=qwenResult(claim.executionToken());
        ((ObjectNode)expired.artifacts().getFirst()).put("expiresAt",now().minusMinutes(1).toString());
        assertValidationFailure(running.id(),expired);

        TaskDtos.ResultArtifactsRequest mockMismatch=qwenResult(claim.executionToken());
        ((ObjectNode)mockMismatch.artifacts().getFirst().path("metadata")).put("mock",true);
        assertValidationFailure(running.id(),mockMismatch);

        TaskDtos.ResultArtifactsRequest video=qwenResult(claim.executionToken());
        ((ObjectNode)video.artifacts().getFirst()).put("artifactType","VIDEO");
        assertValidationFailure(running.id(),video);

        TaskDtos.ResultArtifactsRequest invalidDimension=qwenResult(claim.executionToken());
        ((ObjectNode)invalidDimension.artifacts().getFirst().path("metadata")).put("width",0);
        assertValidationFailure(running.id(),invalidDimension);

        TaskDtos.ResultArtifactsRequest tooMany=qwenResult(claim.executionToken());
        tooMany=new TaskDtos.ResultArtifactsRequest(tooMany.eventId(),tooMany.executionNo(),
                tooMany.executionToken(),tooMany.provider(),tooMany.model(),tooMany.requestId(),
                tooMany.providerTaskId(),tooMany.submissionState(),
                List.of(tooMany.artifacts().getFirst(),tooMany.artifacts().getFirst().deepCopy()),
                tooMany.usage(),tooMany.latencyMs());
        assertValidationFailure(running.id(),tooMany);

        verify(mapper,never()).completeTask(anyLong(),anyString(),any());
        verify(mapper,never()).settlePoints(anyLong(),anyInt(),any());
    }

    @Test
    void terminalProviderFailureCannotLaterBecomeSuccess() {
        TaskRecords.Task running=task("RUNNING");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        TaskRecords.Execution execution=executionCaptor.getValue();
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(execution));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        when(mapper.failExecution(anyLong(),anyInt(),anyString(),anyString(),anyString(),any())).thenReturn(1);
        when(mapper.failOrRetryTask(anyLong(),anyString(),anyString(),anyString(),any())).thenReturn(1);
        when(mapper.releaseReservation(anyLong(),anyInt(),any())).thenReturn(1);
        when(mapper.releasePoints(anyLong(),anyInt(),any())).thenReturn(1);
        when(mapper.findPointAccount(running.userId())).thenReturn(Optional.of(
                new TaskRecords.PointAccount(running.userId(),20,0,3,now(),now())));
        when(mapper.insertLedger(anyLong(),anyLong(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),
                anyLong(),anyString(),anyString(),any())).thenReturn(1);
        when(conversations.nextEventSequence(running.conversationId(),running.userId())).thenReturn(7L);
        when(conversations.insertEvent(any())).thenReturn(1);
        ObjectNode details=json.createObjectNode();
        details.put("code","QWEN_IMAGE_PROVIDER_UNAVAILABLE");
        details.put("submissionState","CONFIRMED");

        TaskDtos.InternalAccepted failed=service.reportEvent(running.id(),new TaskDtos.ExecutionEventRequest(
                UUID.randomUUID(),1,claim.executionToken(),"TASK_FAILED","FAILED",details)).response();
        assertThat(failed.taskStatus()).isEqualTo("FAILED");

        TaskRecords.Execution terminal=new TaskRecords.Execution(execution.id(),execution.taskId(),
                execution.userId(),execution.executionNo(),"FAILED",execution.consumerName(),
                execution.workerId(),execution.bundleVersion(),execution.executionTokenHash(),
                execution.leaseExpiresAt(),"FAILED",execution.providerCode(),execution.providerModel(),
                execution.providerRequestId(),execution.providerTaskId(),"CONFIRMED",
                execution.latencyMs(),execution.usageJson(),"QWEN_IMAGE_PROVIDER_UNAVAILABLE",
                execution.lastHeartbeatAt(),execution.startedAt(),now(),execution.createdAt(),now());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(terminal));

        assertThatThrownBy(() -> service.reportResult(running.id(),qwenResult(claim.executionToken())))
                .isInstanceOf(BusinessException.class);
        verify(mapper,never()).completeTask(anyLong(),anyString(),any());
        verify(mapper,never()).settleReservation(anyLong(),anyInt(),any());
    }

    @Test
    void reusedResultEventIdWithDifferentEvidenceIsRejected() {
        TaskRecords.Task running=task("RUNNING");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executionCaptor.getValue()));
        TaskDtos.ResultArtifactsRequest first=result(claim.executionToken(),"LOCAL_TASK_PROVIDER");
        when(mapper.findTaskEventRequestHash(running.id(),first.eventId()))
                .thenReturn(Optional.of("a".repeat(64)));

        assertThatThrownBy(() -> service.reportResult(running.id(),first))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(com.guanghe.backend.common.api.ErrorCode.IDEMPOTENCY_KEY_REUSED);
        verify(mapper,never()).completeTask(anyLong(),anyString(),any());
        verify(mapper,never()).settlePoints(anyLong(),anyInt(),any());
    }

    @Test
    void progressIsPersistedAsABrowserEventTogetherWithTheTaskProgress() throws Exception {
        TaskRecords.Task running=task("RUNNING");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executionCaptor.getValue()));
        when(mapper.findTaskInternal(running.id())).thenReturn(Optional.of(running));
        when(mapper.insertTaskEvent(anyLong(),anyLong(),anyLong(),anyLong(),any(),any(),any(),any(),
                anyString(),anyString(),any(),any(),anyString(),any())).thenReturn(1);
        when(conversations.nextEventSequence(running.conversationId(),running.userId())).thenReturn(4L);
        when(conversations.insertEvent(any())).thenReturn(1);

        TaskControlService.ReportedTask reported=service.reportEvent(running.id(),
                new TaskDtos.ExecutionEventRequest(UUID.randomUUID(),1,claim.executionToken(),
                        "TASK_PROGRESS","PROVIDER_GENERATING",json.createObjectNode()));

        assertThat(reported.response().taskStatus()).isEqualTo("RUNNING");
        assertThat(reported.browserEvent().eventType()).isEqualTo("task.progress");
        assertThat(json.readTree(reported.browserEvent().payloadJson()).path("stageCode").asText())
                .isEqualTo("PROVIDER_GENERATING");
        verify(mapper).updateProgress(eq(running.id()),eq("PROVIDER_GENERATING"),any());
    }

    @Test
    void unknownResultPersistsContinuationAndCreatesOnlyOneRecoveryOutbox() throws Exception {
        TaskRecords.Task running=task("RUNNING");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        TaskRecords.Execution execution=executionCaptor.getValue();
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(execution));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        when(mapper.recordUnknownExecution(anyLong(),anyInt(),anyString(),anyString(),anyString(),any(),
                anyString(),anyString(),any())).thenReturn(1);
        when(mapper.failOrRetryTask(anyLong(),anyString(),anyString(),anyString(),any())).thenReturn(1);
        when(mapper.insertOutbox(any(),anyString(),anyString())).thenReturn(1);
        when(conversations.nextEventSequence(running.conversationId(),running.userId())).thenReturn(5L);
        when(conversations.insertEvent(any())).thenReturn(1);
        UUID sourceEventId=UUID.randomUUID();
        TaskDtos.ExecutionEventRequest unknown=unknownFailure(
                sourceEventId,1,claim.executionToken());

        TaskControlService.ReportedTask response=service.reportEvent(running.id(),unknown);

        assertThat(response.response().taskStatus()).isEqualTo("RETRY_WAIT");
        assertThat(response.browserEvent().eventType()).isEqualTo("task.progress");
        JsonNode browserPayload=json.readTree(response.browserEvent().payloadJson());
        assertThat(browserPayload.path("stageCode").asText()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        assertThat(browserPayload.has("providerRequestId")).isFalse();
        assertThat(browserPayload.has("providerTaskId")).isFalse();
        verify(mapper).recordUnknownExecution(eq(running.id()),eq(1),anyString(),eq("QWEN_IMAGE"),
                eq("qwen-image-3.0"),eq(providerRequestId()),eq("qwen-task-1"),
                eq("QWEN_IMAGE_RESULT_TIMEOUT"),any());
        ArgumentCaptor<TaskRecords.Outbox> outbox=ArgumentCaptor.forClass(TaskRecords.Outbox.class);
        verify(mapper).insertOutbox(outbox.capture(),eq("zhixiaohe.task"),eq("agent.image"));
        assertThat(outbox.getValue().executionAttempt()).isEqualTo(2);
        assertThat(json.readTree(outbox.getValue().payloadJson()).path("attempt").asInt()).isEqualTo(2);
        verify(mapper,never()).releasePoints(anyLong(),anyInt(),any());
        verify(mapper,never()).settlePoints(anyLong(),anyInt(),any());

        ArgumentCaptor<String> requestHash=ArgumentCaptor.forClass(String.class);
        verify(mapper).insertTaskEvent(anyLong(),eq(running.id()),eq(running.userId()),eq(execution.id()),
                eq(sourceEventId),requestHash.capture(),eq("RUNNING"),eq("RETRY_WAIT"),
                eq("TASK_FAILED"),eq("PYTHON"),eq("QWEN_IMAGE_RESULT_TIMEOUT"),any(),anyString(),any());
        TaskRecords.Execution stored=withContinuation(execution,"FAILED");
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(stored));
        when(mapper.findTaskEventRequestHash(running.id(),sourceEventId))
                .thenReturn(Optional.of(requestHash.getValue()));
        when(mapper.findTaskInternal(running.id())).thenReturn(Optional.of(
                task("RETRY_WAIT","PROVIDER_RESULT_UNKNOWN")));

        TaskDtos.InternalAccepted replay=service.reportEvent(running.id(),unknown).response();

        assertThat(replay.taskStatus()).isEqualTo("RETRY_WAIT");
        verify(mapper,times(1)).insertOutbox(any(),anyString(),anyString());
        verify(mapper,times(1)).recordUnknownExecution(anyLong(),anyInt(),anyString(),anyString(),
                anyString(),any(),anyString(),anyString(),any());
    }

    @Test
    void unknownWithoutCompleteReceiptTerminatesImageAndVideoWithoutAnotherSubmission() throws Exception {
        for (boolean video : new boolean[] {false, true}) {
            setUp();
            TaskRecords.Task running = video ? videoTask("RUNNING", "CLAIMED") : task("RUNNING");
            TaskDtos.ClaimResponse claim = claimFor(running);
            ArgumentCaptor<TaskRecords.Execution> saved = ArgumentCaptor.forClass(TaskRecords.Execution.class);
            verify(mapper).insertExecution(saved.capture());
            when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(saved.getValue()));
            when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
            when(mapper.recordUnknownExecution(anyLong(), anyInt(), anyString(), anyString(), anyString(), any(),
                    any(), anyString(), any())).thenReturn(1);
            allowUnconfirmedTermination(running);
            var request = unknownFailure(UUID.randomUUID(), 1, claim.executionToken());
            ObjectNode details = (ObjectNode) request.details();
            if (video) details.put("provider", "WAN_VIDEO").put("model", "wan3.0-video");
            details.putNull("providerTaskId");
            if (!video) details.putNull("providerRequestId");
            var result = service.reportEvent(running.id(), request);
            assertThat(result.response().taskStatus()).isEqualTo("SYSTEM_TERMINATED");
            assertThat(json.readTree(result.browserEvent().payloadJson()).path("code").asText())
                    .isEqualTo("PROVIDER_RESULT_RECOVERY_EXHAUSTED");
            verify(mapper).recordUnknownExecution(eq(running.id()), eq(1), anyString(),
                    eq(video ? "WAN_VIDEO" : "QWEN_IMAGE"), eq(video ? "wan3.0-video" : "qwen-image-3.0"),
                    eq(video ? providerRequestId() : null), org.mockito.ArgumentMatchers.isNull(),
                    eq("QWEN_IMAGE_RESULT_TIMEOUT"), any());
            verify(mapper, never()).insertOutbox(any(), anyString(), anyString());
            verify(mapper).releasePoints(eq(running.userId()), eq(running.quotedPoints()), any());
            verify(mapper, never()).settlePoints(anyLong(), anyInt(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "VIDEO", "ARTICLE"})
    void expiredPaidGenerationLeaseWithoutReceiptTerminatesAtFirstAttemptWithoutRetry(String taskType)
            throws Exception {
            TaskRecords.Task running = receiptRequiredTask(taskType, "RUNNING", "CLAIMED");
            TaskRecords.Execution expired = execution(1, "RUNNING", now().minusSeconds(5));
            TaskRecords.Task terminal = terminatedWithoutReceipt(running);
            var notifications = mock(TaskNotificationService.class);
            service.setTaskNotifications(notifications);
            when(mapper.findExpiredTaskId(any())).thenReturn(Optional.of(running.id()));
            when(mapper.lockTask(running.id())).thenReturn(Optional.of(running), Optional.of(terminal));
            when(mapper.lockExpiredExecution(eq(running.id()), any())).thenReturn(Optional.of(expired));
            when(mapper.expireExecution(eq(expired.id()), any())).thenReturn(1);
            allowUnconfirmedTermination(running);
            var result = service.recoverExpiredExecution().orElseThrow();
            assertThat(result.response().taskStatus()).isEqualTo("SYSTEM_TERMINATED");
            JsonNode payload = json.readTree(result.browserEvent().payloadJson());
            assertThat(payload.path("stageCode").asText()).isEqualTo("PROVIDER_RESULT_CONFIRMATION_FAILED");
            assertThat(payload.path("code").asText()).isEqualTo("PROVIDER_RESULT_RECOVERY_EXHAUSTED");
            assertThat(service.recoverExpiredExecution()).isEmpty();
            verify(mapper, times(1)).expireExecution(eq(expired.id()), any());
            verify(mapper, times(1)).terminateUnconfirmedTask(eq(running.id()), any());
            verify(mapper, times(1)).releaseReservation(eq(running.id()), eq(running.quotedPoints()), any());
            verify(mapper, times(1)).releasePoints(eq(running.userId()), eq(running.quotedPoints()), any());
            verify(mapper, times(1)).insertLedger(anyLong(), eq(running.userId()), eq(running.quotedPoints()),
                    eq(-running.quotedPoints()), eq(20), eq(0), eq("TASK_RELEASE"), eq(running.id()),
                    eq("task-release:" + running.id()), anyString(), any());
            verify(notifications, times(1)).publishTerminal(running.id(), running.userId());
            if ("ARTICLE".equals(taskType)) {
                verify(articles, times(1)).markGenerationFailed(running, result.browserEvent().createdAt());
                org.mockito.Mockito.verifyNoMoreInteractions(articles);
            } else org.mockito.Mockito.verifyNoInteractions(articles);
            verify(mapper, never()).scheduleTaskRetry(anyLong(), anyString(), anyString(), any());
            verify(mapper, never()).insertOutbox(any(), anyString(), anyString());
            verify(mapper, never()).insertExecution(any());
            verify(mapper, never()).settlePoints(anyLong(), anyInt(), any());
            verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
            verify(mapper, never()).completeTask(anyLong(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "VIDEO", "ARTICLE"})
    void olderWatchdogRetryWithoutReceiptIsTerminalAndCannotCreateNewExecution(String taskType) {
            TaskRecords.Task retrying = receiptRequiredTask(taskType, "RETRY_WAIT", "WORKER_LEASE_EXPIRED");
            TaskRecords.Execution expired = execution(1, "EXPIRED", now().minusSeconds(5));
            when(mapper.findOutbox(7002, retrying.id())).thenReturn(Optional.of(new TaskRecords.Outbox(
                    7002, retrying.userId(), retrying.id(), 2, "{\"attempt\":2}", null,
                    "PUBLISHED", 1, now(), now())));
            when(mapper.lockTask(retrying.id())).thenReturn(Optional.of(retrying));
            when(mapper.findLatestExecution(retrying.id())).thenReturn(Optional.of(expired));
            when(mapper.insertInbox(anyLong(), anyString(), anyLong(), anyLong(), anyLong(), any(),
                    eq("TERMINAL"), any())).thenReturn(1);
            allowUnconfirmedTermination(retrying);
            TaskDtos.ClaimRequest request = new TaskDtos.ClaimRequest("agent-image-dev", "7002",
                    "new-worker", 2, "bundle-v1");
            var result = service.claim(retrying.id(), request);
            assertThat(result.response().claimed()).isFalse();
            assertThat(result.response().disposition()).isEqualTo("TERMINAL");
            assertThat(result.browserEvent().eventType()).isEqualTo("task.failed");
            when(mapper.findInbox("agent-image-dev", 7002)).thenReturn(Optional.of(new TaskRecords.Inbox(
                    101, "agent-image-dev", 7002, retrying.id(), retrying.userId(), null, "TERMINAL", now())));
            assertThat(service.claim(retrying.id(), request).response().disposition()).isEqualTo("TERMINAL");
            verify(mapper, times(1)).terminateUnconfirmedTask(eq(retrying.id()), any());
            verify(mapper, times(1)).releaseReservation(eq(retrying.id()), eq(retrying.quotedPoints()), any());
            verify(mapper, times(1)).releasePoints(eq(retrying.userId()), eq(retrying.quotedPoints()), any());
            verify(mapper, times(1)).insertLedger(anyLong(), eq(retrying.userId()), eq(retrying.quotedPoints()),
                    eq(-retrying.quotedPoints()), eq(20), eq(0), eq("TASK_RELEASE"), eq(retrying.id()),
                    eq("task-release:" + retrying.id()), anyString(), any());
            verify(mapper, never()).settlePoints(anyLong(), anyInt(), any());
            verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
            verify(mapper, never()).claimTask(anyLong(), anyString(), any());
            verify(mapper, never()).insertExecution(any());
            verify(mapper, never()).insertOutbox(any(), anyString(), anyString());
            if ("ARTICLE".equals(taskType)) {
                verify(articles, times(1)).markGenerationFailed(retrying, result.browserEvent().createdAt());
                org.mockito.Mockito.verifyNoMoreInteractions(articles);
            } else org.mockito.Mockito.verifyNoInteractions(articles);
    }

    private void allowUnconfirmedTermination(TaskRecords.Task task) {
        when(mapper.terminateUnconfirmedTask(eq(task.id()), any())).thenReturn(1);
        when(mapper.releaseReservation(eq(task.id()), eq(task.quotedPoints()), any())).thenReturn(1);
        when(mapper.releasePoints(eq(task.userId()), eq(task.quotedPoints()), any())).thenReturn(1);
        when(mapper.findPointAccount(task.userId())).thenReturn(Optional.of(
                new TaskRecords.PointAccount(task.userId(), 20, 0, 3, now(), now())));
        when(mapper.insertTaskEvent(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(conversations.nextEventSequence(task.conversationId(), task.userId())).thenReturn(6L);
        when(conversations.insertEvent(any())).thenReturn(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARTICLE_DEADLINE_EXCEEDED", "DEEPSEEK_SUBMISSION_UNKNOWN"})
    void articleUnknownIsRecordedWithoutReceiptsAndReleasesOnceWithoutRetry(String code) {
        TaskRecords.Task running = asArticle(task("RUNNING"));
        TaskDtos.ClaimResponse claim = claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executions = ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executions.capture());
        TaskRecords.Execution initial = executions.getValue();
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(initial));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        allowUnconfirmedTermination(running);
        when(mapper.recordUnknownExecution(eq(running.id()), eq(1), anyString(), eq("DEEPSEEK"),
                eq("deepseek-chat"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), eq(code), any())).thenReturn(1);
        TaskDtos.ExecutionEventRequest failure = new TaskDtos.ExecutionEventRequest(UUID.randomUUID(), 1,
                claim.executionToken(), "TASK_FAILED", "PROVIDER_RESULT_UNKNOWN", articleUnknownDetails(code));

        TaskControlService.ReportedTask result = service.reportEvent(running.id(), failure);

        assertThat(result.response().taskStatus()).isEqualTo("SYSTEM_TERMINATED");
        verify(mapper).recordUnknownExecution(eq(running.id()), eq(1), anyString(), eq("DEEPSEEK"),
                eq("deepseek-chat"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), eq(code), any());
        verify(mapper, never()).failExecution(anyLong(), anyInt(), anyString(), anyString(), anyString(), any());
        verify(mapper, never()).insertOutbox(any(), anyString(), anyString());
        verify(mapper, never()).settlePoints(anyLong(), anyInt(), any());

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertTaskEvent(anyLong(), eq(running.id()), eq(running.userId()), eq(initial.id()),
                eq(failure.eventId()), hash.capture(), eq("RUNNING"), eq("SYSTEM_TERMINATED"),
                eq("TASK_FAILED"), eq("PYTHON"), eq("PROVIDER_RESULT_RECOVERY_EXHAUSTED"), any(),
                evidence.capture(), any());
        assertThat(evidence.getValue()).contains("\"submissionState\":\"UNKNOWN\"", code)
                .doesNotContain("\"submissionState\":\"CONFIRMED\"", "\"submissionState\":\"NOT_SUBMITTED\"");

        TaskRecords.Execution terminal = new TaskRecords.Execution(initial.id(), initial.taskId(),
                initial.userId(), initial.executionNo(), "FAILED", initial.consumerName(), initial.workerId(),
                initial.bundleVersion(), initial.executionTokenHash(), initial.leaseExpiresAt(),
                "PROVIDER_RESULT_UNKNOWN", "DEEPSEEK", "deepseek-chat", null, null, "UNKNOWN", null,
                "{}", code, initial.lastHeartbeatAt(), initial.startedAt(), now(), initial.createdAt(), now());
        TaskRecords.Task finished = asArticle(task("SYSTEM_TERMINATED", "PROVIDER_RESULT_CONFIRMATION_FAILED"));
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(terminal));
        when(mapper.findTaskEventRequestHash(running.id(), failure.eventId())).thenReturn(Optional.of(hash.getValue()));
        when(mapper.findTaskInternal(running.id())).thenReturn(Optional.of(finished));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(finished));
        when(mapper.insertInbox(anyLong(), eq("article-late"), eq(7001L), eq(running.id()),
                eq(running.userId()), org.mockito.ArgumentMatchers.isNull(), eq("TERMINAL"), any()))
                .thenReturn(1);
        assertThat(service.reportEvent(running.id(), failure).response().taskStatus()).isEqualTo("SYSTEM_TERMINATED");
        TaskDtos.ExecutionEventRequest late = new TaskDtos.ExecutionEventRequest(UUID.randomUUID(), 1,
                claim.executionToken(), "TASK_FAILED", "PROVIDER_RESULT_UNKNOWN", articleUnknownDetails(code));
        assertThatThrownBy(() -> service.reportEvent(running.id(), late)).isInstanceOf(BusinessException.class);
        assertThat(service.claim(running.id(), new TaskDtos.ClaimRequest(
                "article-late", "7001", "late-worker", 1, "bundle-v1")).response().disposition())
                .isEqualTo("TERMINAL");
        verify(mapper, times(1)).insertExecution(any());
        verify(mapper, times(1)).releaseReservation(eq(running.id()), eq(running.quotedPoints()), any());
        verify(mapper, times(1)).releasePoints(eq(running.userId()), eq(running.quotedPoints()), any());
        verify(mapper, times(1)).insertLedger(anyLong(), eq(running.userId()), eq(7), eq(-7),
                anyInt(), eq(0), eq("TASK_RELEASE"), eq(running.id()), eq("task-release:" + running.id()),
                anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestReceipt", "taskReceipt", "bothReceipts", "wrongModel", "wrongCode",
            "missingReceiptField", "extraUsage", "wrongStage", "mediaTask"})
    void articleUnknownCannotBecomeAQueryableOrForeignProviderContinuation(String mutation) {
        TaskRecords.Task running = "mediaTask".equals(mutation)
                ? task("RUNNING") : asArticle(task("RUNNING"));
        TaskDtos.ClaimResponse claim = claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executions = ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executions.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executions.getValue()));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        ObjectNode details = articleUnknownDetails("ARTICLE_DEADLINE_EXCEEDED");
        switch (mutation) {
            case "requestReceipt" -> details.put("providerRequestId", providerRequestId().toString());
            case "taskReceipt" -> details.put("providerTaskId", "article-request-1");
            case "bothReceipts" -> details.put("providerRequestId", providerRequestId().toString())
                    .put("providerTaskId", "article-request-1");
            case "wrongModel" -> details.put("model", "qwen-image-3.0");
            case "wrongCode" -> details.put("code", "ARTICLE_RESULT_INVALID");
            case "missingReceiptField" -> details.remove("providerTaskId");
            case "extraUsage" -> details.put("inputTokens", 0);
            default -> { }
        }
        String stage = "wrongStage".equals(mutation) ? "FAILED" : "PROVIDER_RESULT_UNKNOWN";
        assertEventValidationFailure(running.id(), new TaskDtos.ExecutionEventRequest(UUID.randomUUID(), 1,
                claim.executionToken(), "TASK_FAILED", stage, details));
        verify(mapper, never()).recordUnknownExecution(anyLong(), anyInt(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), any());
        verify(mapper, never()).terminateUnconfirmedTask(anyLong(), any());
        verify(mapper, never()).releasePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).insertOutbox(any(), anyString(), anyString());
    }

    private ObjectNode articleUnknownDetails(String code) {
        return json.createObjectNode().put("code", code).put("provider", "DEEPSEEK")
                .put("model", "deepseek-chat").putNull("providerRequestId").putNull("providerTaskId")
                .put("submissionState", "UNKNOWN");
    }

    @Test
    void unknownResultRequiresExactQwenContinuationIdentity() {
        TaskRecords.Task running=task("RUNNING");
        TaskDtos.ClaimResponse claim=claimFor(running);
        ArgumentCaptor<TaskRecords.Execution> executionCaptor=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(executionCaptor.capture());
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(executionCaptor.getValue()));

        TaskDtos.ExecutionEventRequest missingId=unknownFailure(
                UUID.randomUUID(),1,claim.executionToken());
        ((ObjectNode)missingId.details()).remove("providerTaskId");
        assertEventValidationFailure(running.id(),missingId);

        TaskDtos.ExecutionEventRequest mixedProvider=unknownFailure(
                UUID.randomUUID(),1,claim.executionToken());
        ((ObjectNode)mixedProvider.details()).put("provider","LOCAL_TASK_PROVIDER");
        assertEventValidationFailure(running.id(),mixedProvider);

        TaskDtos.ExecutionEventRequest invalidRequestId=unknownFailure(
                UUID.randomUUID(),1,claim.executionToken());
        ((ObjectNode)invalidRequestId.details()).put("providerRequestId","not-a-uuid");
        assertEventValidationFailure(running.id(),invalidRequestId);

        verify(mapper,never()).recordUnknownExecution(anyLong(),anyInt(),anyString(),anyString(),
                anyString(),any(),anyString(),anyString(),any());
        verify(mapper,never()).insertOutbox(any(),anyString(),anyString());
    }

    @Test
    void recoveryClaimSurvivesServiceRestartAndReturnsResumeOnlyContinuation() throws Exception {
        TaskRecords.Task retrying=task("RETRY_WAIT","PROVIDER_RESULT_UNKNOWN");
        TaskRecords.Execution previous=continuationExecution(1,"FAILED",now().minusSeconds(1));
        ClaimedRecovery recovery=claimRecovery(retrying,previous);

        assertThat(recovery.claim().response().executionNo()).isEqualTo(2);
        assertThat(recovery.claim().browserEvent().eventType()).isEqualTo("task.progress");
        JsonNode browserPayload=json.readTree(recovery.claim().browserEvent().payloadJson());
        assertThat(browserPayload.path("stageCode").asText()).isEqualTo("PROVIDER_RESULT_RECOVERY");
        assertThat(browserPayload.has("providerRequestId")).isFalse();
        assertThat(recovery.execution().providerCode()).isEqualTo("QWEN_IMAGE");
        assertThat(recovery.execution().providerRequestId()).isEqualTo(providerRequestId());
        assertThat(recovery.execution().providerTaskId()).isEqualTo("qwen-task-1");
        assertThat(recovery.execution().stageCode()).isEqualTo("PROVIDER_RESULT_RECOVERY");
        verify(mapper).insertInbox(anyLong(),eq("agent-image-dev"),eq(7002L),eq(retrying.id()),
                eq(retrying.userId()),eq(recovery.execution().id()),eq("CLAIMED"),any());

        when(mapper.findTaskInternal(retrying.id())).thenReturn(Optional.of(
                task("RUNNING","PROVIDER_RESULT_RECOVERY")));
        when(mapper.findLatestExecution(retrying.id())).thenReturn(Optional.of(recovery.execution()));
        TaskControlService restarted=withCreativeSources(new TaskControlService(mapper,conversations,
                mock(SnowflakeIdGenerator.class),json,properties(),assetProperties(),
                mock(com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory.class)));

        TaskDtos.ExecutionContextResponse context=restarted.executionContext(retrying.id(),2,
                recovery.claim().response().executionToken());

        assertThat(context.resumeOnly()).isTrue();
        assertThat(context.providerContinuation()).isNotNull();
        assertThat(context.providerContinuation().resumeOnly()).isTrue();
        assertThat(context.providerContinuation().provider()).isEqualTo("QWEN_IMAGE");
        assertThat(context.providerContinuation().model()).isEqualTo("qwen-image-3.0");
        assertThat(context.providerContinuation().providerRequestId())
                .isEqualTo(providerRequestId().toString());
        assertThat(context.providerContinuation().providerTaskId()).isEqualTo("qwen-task-1");
        assertThat(context.providerContinuation().submissionState()).isEqualTo("UNKNOWN");
        assertThat(context.budget().maxProviderCalls()).isEqualTo(1);
    }

    @Test
    void recoveredQwenResultMatchesContinuationAndCreatesOnlyOneIngestionWork() {
        TaskRecords.Task retrying=task("RETRY_WAIT","PROVIDER_RESULT_UNKNOWN");
        ClaimedRecovery recovery=claimRecovery(retrying,
                continuationExecution(1,"FAILED",now().minusSeconds(1)));
        TaskRecords.Task running=task("RUNNING","PROVIDER_RESULT_RECOVERY");
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(recovery.execution()));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        allowSuccessfulResult(running);
        TaskDtos.ResultArtifactsRequest result=withExecutionNo(
                qwenResult(recovery.claim().response().executionToken()),2);
        TaskDtos.ResultArtifactsRequest mismatched=new TaskDtos.ResultArtifactsRequest(
                UUID.randomUUID(),result.executionNo(),result.executionToken(),result.provider(),
                result.model(),result.requestId(),"different-qwen-task",result.submissionState(),
                result.artifacts(),result.usage(),result.latencyMs());
        assertValidationFailure(running.id(),mismatched);

        TaskDtos.InternalAccepted accepted=service.reportResult(running.id(),result).response();

        assertThat(accepted.taskStatus()).isEqualTo("RUNNING");
        verify(mapper).markExecutionResultReady(eq(running.id()),eq(2),anyString(),eq("QWEN_IMAGE"),
                eq("qwen-image-3.0"),eq(providerRequestId()),eq("qwen-task-1"),eq(2400),
                anyString(),any());
        verify(mapper,times(1)).insertAssetIngestionWork(any());
        verify(mapper,never()).settlePoints(eq(running.userId()),eq(running.quotedPoints()),any());

        ArgumentCaptor<String> requestHash=ArgumentCaptor.forClass(String.class);
        verify(mapper).insertTaskEvent(anyLong(),eq(running.id()),anyLong(),anyLong(),eq(result.eventId()),
                requestHash.capture(),eq("RUNNING"),eq("RUNNING"),eq("RESULT_READY"),eq("PYTHON"),
                any(),any(),anyString(),any());
        when(mapper.findTaskEventRequestHash(running.id(),result.eventId()))
                .thenReturn(Optional.of(requestHash.getValue()));
        when(mapper.findTaskInternal(running.id())).thenReturn(Optional.of(task("RUNNING","ASSET_INGESTION_PENDING")));

        assertThat(service.reportResult(running.id(),result).response().taskStatus())
                .isEqualTo("RUNNING");
        verify(mapper,times(1)).insertAssetIngestionWork(any());
        verify(mapper,never()).settlePoints(anyLong(),anyInt(),any());
    }

    @Test
    void repeatedUnknownAtExecutionLimitTerminatesAndReleasesFrozenPointsOnce() throws Exception {
        TaskRecords.Task retrying=task("RETRY_WAIT","PROVIDER_RESULT_UNKNOWN");
        ClaimedRecovery recovery=claimRecovery(retrying,
                continuationExecution(1,"FAILED",now().minusSeconds(1)));
        TaskRecords.Task running=task("RUNNING","PROVIDER_RESULT_RECOVERY");
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(recovery.execution()));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        when(mapper.recordUnknownExecution(anyLong(),anyInt(),anyString(),anyString(),anyString(),any(),
                anyString(),anyString(),any())).thenReturn(1);
        when(mapper.terminateUnconfirmedTask(eq(running.id()),any())).thenReturn(1);
        when(mapper.releaseReservation(eq(running.id()),eq(7),any())).thenReturn(1);
        when(mapper.releasePoints(eq(running.userId()),eq(7),any())).thenReturn(1);
        when(mapper.findPointAccount(running.userId())).thenReturn(Optional.of(
                new TaskRecords.PointAccount(running.userId(),20,0,3,now(),now())));
        when(mapper.insertLedger(anyLong(),anyLong(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),
                anyLong(),anyString(),anyString(),any())).thenReturn(1);
        when(conversations.nextEventSequence(running.conversationId(),running.userId())).thenReturn(8L);
        when(conversations.insertEvent(any())).thenReturn(1);
        TaskDtos.ExecutionEventRequest unknown=unknownFailure(UUID.randomUUID(),2,
                recovery.claim().response().executionToken());

        TaskControlService.ReportedTask terminal=service.reportEvent(running.id(),unknown);

        assertThat(terminal.response().taskStatus()).isEqualTo("SYSTEM_TERMINATED");
        assertThat(terminal.browserEvent().eventType()).isEqualTo("task.failed");
        JsonNode payload=json.readTree(terminal.browserEvent().payloadJson());
        assertThat(payload.path("stageCode").asText())
                .isEqualTo("PROVIDER_RESULT_CONFIRMATION_FAILED");
        assertThat(payload.path("code").asText()).isEqualTo("PROVIDER_RESULT_RECOVERY_EXHAUSTED");
        verify(mapper).releasePoints(running.userId(),7,terminal.browserEvent().createdAt());
        verify(mapper,never()).insertOutbox(any(),anyString(),anyString());
        verify(mapper,never()).settlePoints(anyLong(),anyInt(),any());

        ArgumentCaptor<String> requestHash=ArgumentCaptor.forClass(String.class);
        verify(mapper).insertTaskEvent(anyLong(),eq(running.id()),anyLong(),anyLong(),eq(unknown.eventId()),
                requestHash.capture(),eq("RUNNING"),eq("SYSTEM_TERMINATED"),eq("TASK_FAILED"),
                eq("PYTHON"),eq("PROVIDER_RESULT_RECOVERY_EXHAUSTED"),any(),anyString(),any());
        when(mapper.findLatestExecution(running.id()))
                .thenReturn(Optional.of(withContinuation(recovery.execution(),"FAILED")));
        when(mapper.findTaskEventRequestHash(running.id(),unknown.eventId()))
                .thenReturn(Optional.of(requestHash.getValue()));
        when(mapper.findTaskInternal(running.id()))
                .thenReturn(Optional.of(task("SYSTEM_TERMINATED","PROVIDER_RESULT_CONFIRMATION_FAILED")));

        assertThat(service.reportEvent(running.id(),unknown).response().taskStatus())
                .isEqualTo("SYSTEM_TERMINATED");
        verify(mapper,times(1)).releasePoints(anyLong(),anyInt(),any());
        verify(mapper,times(1)).insertLedger(anyLong(),anyLong(),anyInt(),anyInt(),anyInt(),anyInt(),
                anyString(),anyLong(),anyString(),anyString(),any());
    }

    @Test
    void expiredGeoLeaseStillCreatesSecondGenerationOutboxAndRetryClaimUsesExecutionNoTwo() throws Exception {
        var geo = mock(com.guanghe.backend.geo.GeoTaskService.class);
        SnowflakeIdGenerator generator = mock(SnowflakeIdGenerator.class);
        when(generator.nextId()).thenAnswer(ignored -> ids.incrementAndGet());
        service = new TaskControlService(mapper, conversations, generator, json, properties(),
                assetProperties(), mock(com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory.class),
                geo, articles);
        TaskRecords.Task source = task("RUNNING");
        TaskRecords.Task running = new TaskRecords.Task(source.id(), source.userId(), source.spaceId(),
                source.productId(), source.conversationId(), source.draftId(), "GEO", "RUNNING", "GEO_SAMPLING",
                1, "{\"parameters\":{\"mode\":\"MONITOR_RUN\",\"topicId\":\"501\"}}", source.inputHash(),
                8001, source.quotedPoints(), 0, "{}", null, source.operationScope(), source.idempotencyKey(),
                source.requestHash(), source.queuedAt(), source.startedAt(), null, source.createdAt(), source.updatedAt());
        TaskRecords.Execution expired=execution(1,"RUNNING",now().minusSeconds(5));
        when(mapper.findExpiredTaskId(any())).thenReturn(Optional.of(running.id()));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running));
        when(mapper.lockExpiredExecution(eq(running.id()),any())).thenReturn(Optional.of(expired));
        when(mapper.expireExecution(eq(expired.id()),any())).thenReturn(1);
        when(mapper.scheduleTaskRetry(eq(running.id()),anyString(),anyString(),any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(),anyLong(),anyLong(),anyLong(),any(),any(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyString(),any())).thenReturn(1);
        when(mapper.insertOutbox(any(),anyString(),anyString())).thenReturn(1);
        when(conversations.nextEventSequence(running.conversationId(),running.userId())).thenReturn(5L);
        when(conversations.insertEvent(any())).thenReturn(1);

        TaskControlService.ReportedTask recovered=service.recoverExpiredExecution().orElseThrow();

        assertThat(recovered.response().taskStatus()).isEqualTo("RETRY_WAIT");
        assertThat(recovered.browserEvent().eventType()).isEqualTo("task.progress");
        ArgumentCaptor<TaskRecords.Outbox> outbox=ArgumentCaptor.forClass(TaskRecords.Outbox.class);
        verify(mapper).insertOutbox(outbox.capture(),eq("zhixiaohe.task"),eq("agent.geo"));
        assertThat(outbox.getValue().executionAttempt()).isEqualTo(2);
        assertThat(json.readTree(outbox.getValue().payloadJson()).path("attempt").asInt()).isEqualTo(2);

        TaskRecords.Task retrying=new TaskRecords.Task(running.id(),running.userId(),running.spaceId(),
                running.productId(),running.conversationId(),running.draftId(),running.taskType(),
                "RETRY_WAIT","WORKER_LEASE_EXPIRED",running.snapshotSchemaVersion(),
                running.inputSnapshotJson(),running.inputHash(),running.pricingVersionId(),
                running.quotedPoints(),running.settledPoints(),running.resultSummaryJson(),
                "WORKER_LEASE_EXPIRED",running.operationScope(),running.idempotencyKey(),running.requestHash(),
                running.queuedAt(),running.startedAt(),null,running.createdAt(),now());
        when(mapper.findOutbox(7002,running.id())).thenReturn(Optional.of(new TaskRecords.Outbox(
                7002,41,running.id(),2,"{\"attempt\":2}",
                "00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01","PUBLISHED",1,now(),now())));
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(retrying));
        when(mapper.findLatestExecution(running.id()))
                .thenReturn(Optional.of(execution(1,"EXPIRED",expired.leaseExpiresAt())));
        when(mapper.claimTask(eq(running.id()),anyString(),any())).thenReturn(1);
        when(mapper.insertExecution(any())).thenReturn(1);
        when(mapper.insertInbox(anyLong(),anyString(),anyLong(),anyLong(),anyLong(),anyLong(),anyString(),any())).thenReturn(1);

        TaskDtos.ClaimResponse second=service.claim(running.id(),new TaskDtos.ClaimRequest(
                "agent-geo-dev","7002","worker-2",2,"bundle-v1")).response();
        assertThat(second.claimed()).isTrue();
        assertThat(second.executionNo()).isEqualTo(2);
        verify(geo).markRunning(eq(retrying), any());
        verify(mapper, times(1)).scheduleTaskRetry(eq(running.id()), eq("WORKER_LEASE_EXPIRED"),
                eq("WORKER_LEASE_EXPIRED"), any());
        verify(mapper, times(1)).insertExecution(any());
        verify(mapper, never()).terminateUnconfirmedTask(anyLong(), any());
        verify(mapper, never()).releasePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).settlePoints(anyLong(), anyInt(), any());
        org.mockito.Mockito.verifyNoInteractions(articles);
    }

    @Test
    void watchdogGenerationRejectsResultFromPriorExecution() {
        TaskRecords.Task running=task("RUNNING");
        TaskRecords.Execution latest=execution(2,"RUNNING",now().plusMinutes(1));
        when(mapper.findLatestExecution(running.id())).thenReturn(Optional.of(latest));
        TaskDtos.ResultArtifactsRequest source=qwenResult("v1.old-execution-token");
        TaskDtos.ResultArtifactsRequest old=new TaskDtos.ResultArtifactsRequest(source.eventId(),1,
                source.executionToken(),source.provider(),source.model(),source.requestId(),
                source.providerTaskId(),source.submissionState(),source.artifacts(),source.usage(),
                source.latencyMs());

        assertThatThrownBy(() -> service.reportResult(running.id(),old))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(com.guanghe.backend.common.api.ErrorCode.TASK_STATE_CONFLICT);
        verify(mapper,never()).lockTask(anyLong());
        verify(mapper,never()).completeTask(anyLong(),anyString(),any());
        verify(mapper,never()).settlePoints(anyLong(),anyInt(),any());
    }

    @Test
    void expiredFinalArticleAttemptTerminatesOnceAndLateClaimCannotRestartOrReleaseAgain() throws Exception {
        TaskRecords.Task running=asArticle(task("RUNNING"));
        TaskRecords.Execution second=execution(2,"RUNNING",now().minusSeconds(5));
        when(mapper.findExpiredTaskId(any())).thenReturn(Optional.of(running.id()));
        TaskRecords.Task terminal = terminatedWithoutReceipt(running);
        when(mapper.lockTask(running.id())).thenReturn(Optional.of(running), Optional.of(terminal));
        when(mapper.lockExpiredExecution(eq(running.id()),any())).thenReturn(Optional.of(second));
        when(mapper.expireExecution(eq(second.id()),any())).thenReturn(1);
        when(mapper.terminateUnconfirmedTask(eq(running.id()),any())).thenReturn(1);
        when(mapper.releaseReservation(eq(running.id()),eq(7),any())).thenReturn(1);
        when(mapper.releasePoints(eq(running.userId()),eq(7),any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(),anyLong(),anyLong(),anyLong(),any(),any(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyString(),any())).thenReturn(1);
        when(mapper.findPointAccount(running.userId())).thenReturn(Optional.of(
                new TaskRecords.PointAccount(running.userId(),20,0,3,now(),now())));
        when(mapper.insertLedger(anyLong(),anyLong(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),anyLong(),anyString(),anyString(),any())).thenReturn(1);
        when(conversations.nextEventSequence(running.conversationId(),running.userId())).thenReturn(6L);
        when(conversations.insertEvent(any())).thenReturn(1);

        TaskControlService.ReportedTask failed=service.recoverExpiredExecution().orElseThrow();

        assertThat(failed.response().taskStatus()).isEqualTo("SYSTEM_TERMINATED");
        assertThat(failed.browserEvent().eventType()).isEqualTo("task.failed");
        JsonNode payload = json.readTree(failed.browserEvent().payloadJson());
        assertThat(payload.path("stageCode").asText()).isEqualTo("PROVIDER_RESULT_CONFIRMATION_FAILED");
        assertThat(payload.path("code").asText()).isEqualTo("PROVIDER_RESULT_RECOVERY_EXHAUSTED");
        assertThat(service.recoverExpiredExecution()).isEmpty();
        when(mapper.findOutbox(7003, running.id())).thenReturn(Optional.of(new TaskRecords.Outbox(
                7003, running.userId(), running.id(), 3, "{\"attempt\":3}", null,
                "PUBLISHED", 1, now(), now())));
        when(mapper.insertInbox(anyLong(), eq("agent-article-dev"), eq(7003L), eq(running.id()),
                eq(running.userId()), org.mockito.ArgumentMatchers.isNull(), eq("TERMINAL"), any()))
                .thenReturn(1);
        var late = service.claim(running.id(), new TaskDtos.ClaimRequest(
                "agent-article-dev", "7003", "late-worker", 3, "bundle-v1"));
        assertThat(late.response().claimed()).isFalse();
        assertThat(late.response().disposition()).isEqualTo("TERMINAL");
        assertThat(late.response().executionToken()).isNull();
        verify(mapper, times(1)).expireExecution(eq(second.id()), any());
        verify(mapper, times(1)).terminateUnconfirmedTask(eq(running.id()), any());
        verify(mapper, times(1)).releaseReservation(eq(running.id()), eq(7), any());
        verify(mapper, times(1)).releasePoints(running.userId(),7,failed.browserEvent().createdAt());
        verify(mapper, times(1)).insertLedger(anyLong(), eq(running.userId()), eq(7), eq(-7),
                eq(20), eq(0), eq("TASK_RELEASE"), eq(running.id()),
                eq("task-release:" + running.id()), anyString(), any());
        verify(articles, times(1)).markGenerationFailed(running,failed.browserEvent().createdAt());
        org.mockito.Mockito.verifyNoMoreInteractions(articles);
        verify(mapper, never()).failExpiredTask(anyLong(), any());
        verify(mapper, never()).scheduleTaskRetry(anyLong(), anyString(), anyString(), any());
        verify(mapper,never()).insertOutbox(any(),anyString(),anyString());
        verify(mapper, never()).claimTask(anyLong(), anyString(), any());
        verify(mapper, never()).insertExecution(any());
        verify(mapper, never()).settlePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).completeTask(anyLong(), anyString(), any());
    }

    @Test
    void staleQueuedTaskReleasesOnceAndLateClaimIsTerminal() throws Exception {
        OffsetDateTime queuedAt = now().minusHours(2);
        TaskRecords.Task base = task("QUEUED", "QUEUED");
        TaskRecords.Task stale = new TaskRecords.Task(base.id(), base.userId(), base.spaceId(), base.productId(),
                base.conversationId(), base.draftId(), base.taskType(), "QUEUED", "QUEUED",
                base.snapshotSchemaVersion(), base.inputSnapshotJson(), base.inputHash(), base.pricingVersionId(),
                base.quotedPoints(), 0, "{}", null, base.operationScope(), base.idempotencyKey(),
                base.requestHash(), queuedAt, null, null, queuedAt, queuedAt);
        TaskRecords.Task terminal = new TaskRecords.Task(stale.id(), stale.userId(), stale.spaceId(),
                stale.productId(), stale.conversationId(), stale.draftId(), stale.taskType(),
                "SYSTEM_TERMINATED", "DISPATCH_EXPIRED", stale.snapshotSchemaVersion(),
                stale.inputSnapshotJson(), stale.inputHash(), stale.pricingVersionId(), stale.quotedPoints(),
                0, "{}", "DISPATCH_EXPIRED", stale.operationScope(), stale.idempotencyKey(),
                stale.requestHash(), stale.queuedAt(), null, now(), stale.createdAt(), now());
        when(mapper.findStaleQueuedTaskId(any())).thenReturn(Optional.of(stale.id()), Optional.empty());
        when(mapper.lockTask(stale.id())).thenReturn(Optional.of(stale), Optional.of(terminal));
        when(mapper.terminateStaleQueuedTask(eq(stale.id()), any(), any())).thenReturn(1);
        when(mapper.releaseReservation(eq(stale.id()), eq(7), any())).thenReturn(1);
        when(mapper.releasePoints(eq(stale.userId()), eq(7), any())).thenReturn(1);
        when(mapper.findPointAccount(stale.userId())).thenReturn(Optional.of(
                new TaskRecords.PointAccount(stale.userId(), 100, 0, 2, now(), now())));
        when(mapper.insertTaskEvent(anyLong(), eq(stale.id()), eq(stale.userId()), any(), any(), any(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(1);
        when(mapper.insertLedger(anyLong(), eq(stale.userId()), anyInt(), anyInt(), anyInt(), anyInt(),
                anyString(), eq(stale.id()), anyString(), anyString(), any())).thenReturn(1);
        when(conversations.nextEventSequence(stale.conversationId(), stale.userId())).thenReturn(9L);
        when(conversations.insertEvent(any())).thenReturn(1);

        TaskControlService.ReportedTask reconciled = service.reconcileStaleQueuedTask().orElseThrow();
        assertThat(reconciled.response().taskStatus()).isEqualTo("SYSTEM_TERMINATED");
        assertThat(json.readTree(reconciled.browserEvent().payloadJson()).path("code").asText())
                .isEqualTo("DISPATCH_EXPIRED");
        assertThat(service.reconcileStaleQueuedTask()).isEmpty();
        verify(mapper, times(1)).releasePoints(stale.userId(), 7, reconciled.browserEvent().createdAt());
        verify(mapper, times(1)).insertLedger(anyLong(), eq(stale.userId()), eq(7), eq(-7),
                anyInt(), eq(0), eq("TASK_RELEASE"), eq(stale.id()),
                eq("task-release:" + stale.id()), anyString(), any());

        when(mapper.findOutbox(7001, stale.id())).thenReturn(Optional.of(new TaskRecords.Outbox(
                7001, stale.userId(), stale.id(), 1, "{}",
                "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01",
                "PUBLISHED", 1, now(), now())));
        when(mapper.insertInbox(anyLong(), anyString(), anyLong(), anyLong(), anyLong(), any(),
                eq("TERMINAL"), any())).thenReturn(1);
        TaskDtos.ClaimResponse late = service.claim(stale.id(), new TaskDtos.ClaimRequest(
                "agent-image-dev", "7001", "late-worker", 1, "bundle-v1")).response();
        assertThat(late.disposition()).isEqualTo("TERMINAL");
        verify(mapper, never()).insertExecution(any());
        verify(mapper, never()).claimTask(anyLong(), anyString(), any());
    }

    private TaskDtos.ClaimResponse claimFor(TaskRecords.Task task) {
        TaskRecords.Task queued=new TaskRecords.Task(task.id(),task.userId(),task.spaceId(),task.productId(),task.conversationId(),task.draftId(),task.taskType(),"QUEUED","QUEUED",1,task.inputSnapshotJson(),task.inputHash(),task.pricingVersionId(),task.quotedPoints(),0,"{}",null,"TASK_CONFIRM",UUID.randomUUID(),"a".repeat(64),now(),null,null,now(),now());
        when(mapper.findOutbox(7001,task.id())).thenReturn(Optional.of(new TaskRecords.Outbox(7001,41,task.id(),1,"{}","00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01","PENDING",0,now(),now())));
        when(mapper.lockTask(task.id())).thenReturn(Optional.of(queued));when(mapper.claimTask(anyLong(),anyString(),any())).thenReturn(1);
        when(mapper.insertExecution(any())).thenReturn(1);when(mapper.insertInbox(anyLong(),anyString(),anyLong(),anyLong(),anyLong(),anyLong(),anyString(),any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(),anyLong(),anyLong(),anyLong(),any(),any(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyString(),any())).thenReturn(1);
        return service.claim(task.id(),new TaskDtos.ClaimRequest(
                "agent-image-dev","7001","worker-1",1,"bundle-v1")).response();
    }

    private ClaimedRecovery claimRecovery(TaskRecords.Task retrying,
                                           TaskRecords.Execution previous) {
        when(mapper.findOutbox(7002,retrying.id())).thenReturn(Optional.of(new TaskRecords.Outbox(
                7002,retrying.userId(),retrying.id(),2,"{\"attempt\":2}",
                "00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01","PUBLISHED",1,now(),now())));
        when(mapper.lockTask(retrying.id())).thenReturn(Optional.of(retrying));
        when(mapper.findLatestExecution(retrying.id())).thenReturn(Optional.of(previous));
        when(mapper.claimTask(eq(retrying.id()),eq("PROVIDER_RESULT_RECOVERY"),any())).thenReturn(1);
        when(mapper.insertExecution(any())).thenReturn(1);
        when(mapper.insertInbox(anyLong(),anyString(),anyLong(),anyLong(),anyLong(),anyLong(),
                anyString(),any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(),anyLong(),anyLong(),anyLong(),any(),any(),anyString(),
                anyString(),anyString(),anyString(),any(),any(),anyString(),any())).thenReturn(1);
        when(conversations.nextEventSequence(retrying.conversationId(),retrying.userId())).thenReturn(6L);
        when(conversations.insertEvent(any())).thenReturn(1);

        TaskControlService.ClaimedTask claim=service.claim(retrying.id(),new TaskDtos.ClaimRequest(
                "agent-image-dev","7002","worker-2",2,"bundle-v1"));
        ArgumentCaptor<TaskRecords.Execution> execution=ArgumentCaptor.forClass(TaskRecords.Execution.class);
        verify(mapper).insertExecution(execution.capture());
        return new ClaimedRecovery(claim,execution.getValue());
    }

    private TaskDtos.ExecutionEventRequest unknownFailure(UUID eventId, int executionNo,
                                                           String executionToken) {
        ObjectNode details=json.createObjectNode();
        details.put("code","QWEN_IMAGE_RESULT_TIMEOUT");
        details.put("provider","QWEN_IMAGE");
        details.put("model","qwen-image-3.0");
        details.put("providerRequestId",providerRequestId().toString());
        details.put("providerTaskId","qwen-task-1");
        details.put("submissionState","UNKNOWN");
        return new TaskDtos.ExecutionEventRequest(eventId,executionNo,executionToken,
                "TASK_FAILED","PROVIDER_RESULT_UNKNOWN",details);
    }

    private TaskDtos.ResultArtifactsRequest withExecutionNo(TaskDtos.ResultArtifactsRequest source,
                                                             int executionNo) {
        return new TaskDtos.ResultArtifactsRequest(source.eventId(),executionNo,source.executionToken(),
                source.provider(),source.model(),source.requestId(),source.providerTaskId(),
                source.submissionState(),source.artifacts(),source.usage(),source.latencyMs());
    }

    private UUID providerRequestId() {
        return UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
    }

    private PaidTaskProperties properties() {
        PaidTaskProperties value=new PaidTaskProperties();
        value.setEnabled(true);
        value.setTokenSecret("worker-test-secret-material-at-least-32-bytes");
        value.setImageMockPricingVersionId(6001);
        value.setImageMockPoints(7);
        value.setVideoTestPricingVersionId(7001);
        value.setVideoTestPoints(11);
        value.setExecutionLease(Duration.ofMinutes(2));
        return value;
    }

    private TaskControlService serviceAt(AtomicReference<OffsetDateTime> clock) {
        return withCreativeSources(new TaskControlService(mapper, conversations,
                mock(SnowflakeIdGenerator.class), json,
                properties(), assetProperties(),
                mock(com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory.class),
                null, articles) {
            @Override
            OffsetDateTime now() {
                return clock.get();
            }
        });
    }

    private TaskControlService withCreativeSources(TaskControlService target) {
        CreativeTaskSourceService creativeSources = mock(CreativeTaskSourceService.class);
        when(creativeSources.controlledReferences(any(), any())).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        ObjectProvider<CreativeTaskSourceService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(creativeSources);
        target.setCreativeSources(provider);
        return target;
    }

    private void stubRenewingExecution(TaskRecords.Task task, String token,
                                       AtomicReference<OffsetDateTime> lease,
                                       AtomicReference<OffsetDateTime> lastHeartbeat) {
        when(mapper.findLatestExecution(task.id())).thenAnswer(ignored -> Optional.of(
                leasedExecution(token, lease.get(), lastHeartbeat.get())));
        when(mapper.lockTask(task.id())).thenReturn(Optional.of(task));
        when(mapper.heartbeat(eq(task.id()), eq(1), eq(sha256(token)),
                eq("PROVIDER_GENERATING"), any(), any())).thenAnswer(invocation -> {
                    OffsetDateTime now = invocation.getArgument(5, OffsetDateTime.class);
                    if (lease.get().isBefore(now)) {
                        return 0;
                    }
                    lease.set(invocation.getArgument(4, OffsetDateTime.class));
                    lastHeartbeat.set(now);
                    return 1;
                });
        when(mapper.updateProgress(eq(task.id()), eq("PROVIDER_GENERATING"), any())).thenReturn(1);
    }

    private TaskRecords.Execution leasedExecution(String token, OffsetDateTime lease,
                                                   OffsetDateTime lastHeartbeat) {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-07T12:00:00Z");
        return new TaskRecords.Execution(8101, 9002, 41, 1, "RUNNING", "agent-video-dev",
                "worker-1", "bundle-v1", sha256(token), lease, "PROVIDER_GENERATING",
                null, null, null, null, null, null, "{}", null, lastHeartbeat, createdAt,
                null, createdAt, lastHeartbeat == null ? createdAt : lastHeartbeat);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void allowSuccessfulResult(TaskRecords.Task task) {
        when(mapper.completeExecution(anyLong(),anyInt(),anyString(),anyString(),anyString(),any(),
                anyString(),anyInt(),anyString(),any())).thenReturn(1);
        when(mapper.completeTask(anyLong(),anyString(),any())).thenReturn(1);
        when(mapper.markExecutionResultReady(anyLong(),anyInt(),anyString(),anyString(),anyString(),any(),
                anyString(),anyInt(),anyString(),any())).thenReturn(1);
        when(mapper.markTaskAssetIngestionPending(anyLong(),any())).thenReturn(1);
        when(mapper.insertAssetIngestionWork(any())).thenReturn(1);
        when(mapper.settleReservation(anyLong(),anyInt(),any())).thenReturn(1);
        when(mapper.settlePoints(anyLong(),anyInt(),any())).thenReturn(1);
        when(mapper.lockPointAccount(task.userId())).thenReturn(Optional.of(
                new TaskRecords.PointAccount(task.userId(), 13, task.quotedPoints(), 1, now(), now())));
        when(mapper.findPointAccount(task.userId())).thenReturn(Optional.of(
                new TaskRecords.PointAccount(task.userId(),13,0,2,now(),now())));
        when(mapper.insertLedger(anyLong(),anyLong(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),
                anyLong(),anyString(),anyString(),any())).thenReturn(1);
        when(conversations.nextEventSequence(task.conversationId(),task.userId())).thenReturn(2L);
        when(conversations.insertEvent(any())).thenReturn(1);
    }

    private void assertValidationFailure(long taskId, TaskDtos.ResultArtifactsRequest request) {
        assertThatThrownBy(() -> service.reportResult(taskId,request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(com.guanghe.backend.common.api.ErrorCode.VALIDATION_FAILED);
    }

    private void assertEventValidationFailure(long taskId, TaskDtos.ExecutionEventRequest request) {
        assertThatThrownBy(() -> service.reportEvent(taskId,request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(com.guanghe.backend.common.api.ErrorCode.VALIDATION_FAILED);
    }

    private TaskRecords.Task imageTask(int count) {
        TaskRecords.Task task = task("RUNNING");
        return new TaskRecords.Task(task.id(), task.userId(), task.spaceId(), task.productId(),
                task.conversationId(), task.draftId(), task.taskType(), task.status(), task.stageCode(),
                task.snapshotSchemaVersion(), task.inputSnapshotJson().replace("\"count\":1", "\"count\":" + count),
                task.inputHash(), task.pricingVersionId(), 7 * count, task.settledPoints(), task.resultSummaryJson(),
                task.errorCode(), task.operationScope(), task.idempotencyKey(), task.requestHash(),
                task.queuedAt(), task.startedAt(), task.finishedAt(), task.createdAt(), task.updatedAt());
    }

    private TaskDtos.ResultArtifactsRequest qwenResults(String token, int count) {
        TaskDtos.ResultArtifactsRequest first = qwenResult(token);
        List<JsonNode> artifacts = new java.util.ArrayList<>();
        for (int index = 1; index <= count; index++) {
            ObjectNode artifact = first.artifacts().getFirst().deepCopy();
            artifact.put("unitKey", "image:" + index);
            artifact.put("providerUrl", "https://result.example/image-" + index + ".png");
            ((ObjectNode) artifact.path("metadata")).put("index", index);
            artifacts.add(artifact);
        }
        ObjectNode usage = first.usage().deepCopy();
        usage.put("images", count);
        return new TaskDtos.ResultArtifactsRequest(first.eventId(), first.executionNo(), token,
                first.provider(), first.model(), first.requestId(), first.providerTaskId(),
                first.submissionState(), artifacts, usage, first.latencyMs());
    }

    private TaskDtos.ResultArtifactsRequest qwenResult(String token) {
        UUID requestId=UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
        ObjectNode artifact=json.createObjectNode();
        artifact.put("artifactType","IMAGE");
        artifact.put("unitKey","image:1");
        artifact.put("schemaVersion",1);
        artifact.put("providerUrl","https://result.example/image.png?Expires=1&Signature=redacted");
        artifact.put("contentType","image/png");
        artifact.put("expiresAt",now().plusHours(1).toString());
        artifact.putNull("inlineData");
        ObjectNode metadata=artifact.putObject("metadata");
        metadata.put("index",1);
        metadata.put("width",2048);
        metadata.put("height",1152);
        metadata.put("mock",false);
        metadata.put("outputImageType","qima_output_2k");
        metadata.put("providerRequestId",requestId.toString());
        metadata.put("providerResultRequestId","provider-result-request-1");
        ObjectNode usage=json.createObjectNode();
        usage.put("inputTokens",0);
        usage.put("outputTokens",0);
        usage.put("images",1);
        usage.put("videoSeconds",0);
        usage.put("providerCost","0.0000");
        usage.put("currency","CNY");
        return new TaskDtos.ResultArtifactsRequest(UUID.randomUUID(),1,token,"QWEN_IMAGE",
                "qwen-image-3.0",requestId,"qwen-task-1","CONFIRMED",List.of(artifact),usage,2400);
    }

    private TaskDtos.ResultArtifactsRequest result(String token,String provider){UUID requestId=UUID.fromString("123e4567-e89b-12d3-a456-426614174000");ObjectNode artifact=json.createObjectNode();artifact.put("artifactType","IMAGE");artifact.put("schemaVersion",1);artifact.put("providerUrl","https://mock-provider.invalid/image/"+requestId+".png");artifact.put("contentType","image/png");ObjectNode metadata=artifact.putObject("metadata");metadata.put("width",1024);metadata.put("height",1024);metadata.put("mock",true);
        ObjectNode usage=json.createObjectNode();usage.put("inputTokens",0);usage.put("outputTokens",0);usage.put("images",1);usage.put("videoSeconds",0);usage.put("providerCost","0.0000");usage.put("currency","CNY");return new TaskDtos.ResultArtifactsRequest(UUID.randomUUID(),1,token,provider,"deterministic-provider-mock-v1",requestId,"mock-image-"+requestId,"CONFIRMED",List.of(artifact),usage,0);}
    private TaskDtos.ResultArtifactsRequest wanVideoResult(String token){UUID requestId=providerRequestId();ObjectNode artifact=json.createObjectNode();artifact.put("artifactType","VIDEO");artifact.put("unitKey","video:1");artifact.put("schemaVersion",1);artifact.put("providerUrl","https://result.example/video.mp4?signature=redacted");artifact.put("contentType","video/mp4");artifact.putNull("expiresAt");artifact.putNull("inlineData");ObjectNode metadata=artifact.putObject("metadata");metadata.put("durationSeconds",5.0);metadata.put("requestedDurationSeconds",5);metadata.put("requestedAspectRatio","16:9");metadata.put("requestedResolution","720P");metadata.put("mock",false);metadata.put("providerRequestId",requestId.toString());ObjectNode usage=json.createObjectNode();usage.put("inputTokens",0);usage.put("outputTokens",0);usage.put("images",0);usage.put("videoSeconds",5);usage.putNull("providerCost");usage.putNull("currency");return new TaskDtos.ResultArtifactsRequest(UUID.randomUUID(),1,token,"WAN_VIDEO","wan3.0-video",requestId,"wan-task-1","CONFIRMED",List.of(artifact),usage,9000);}
    private TaskRecords.Task task(String status){return task(status,"CLAIMED");}
    private TaskRecords.Task receiptRequiredTask(String type, String status, String stage) {
        return switch (type) {
            case "IMAGE" -> task(status, stage);
            case "VIDEO" -> videoTask(status, stage);
            case "ARTICLE" -> asArticle(task(status, stage));
            default -> throw new IllegalArgumentException("Unsupported receipt fixture type");
        };
    }
    private TaskRecords.Task terminatedWithoutReceipt(TaskRecords.Task source) {
        return new TaskRecords.Task(source.id(), source.userId(), source.spaceId(), source.productId(),
                source.conversationId(), source.draftId(), source.taskType(), "SYSTEM_TERMINATED",
                "PROVIDER_RESULT_CONFIRMATION_FAILED", source.snapshotSchemaVersion(), source.inputSnapshotJson(),
                source.inputHash(), source.pricingVersionId(), source.quotedPoints(), source.settledPoints(),
                source.resultSummaryJson(), "PROVIDER_RESULT_RECOVERY_EXHAUSTED", source.operationScope(),
                source.idempotencyKey(), source.requestHash(), source.queuedAt(), source.startedAt(), now(),
                source.createdAt(), now(), source.planId(), source.scheduledSlot());
    }
    private TaskRecords.Task asArticle(TaskRecords.Task source) {
        return new TaskRecords.Task(source.id(), source.userId(), source.spaceId(), source.productId(),
                source.conversationId(), source.draftId(), "ARTICLE", source.status(), source.stageCode(),
                source.snapshotSchemaVersion(), source.inputSnapshotJson(), source.inputHash(),
                source.pricingVersionId(), source.quotedPoints(), source.settledPoints(), source.resultSummaryJson(),
                source.errorCode(), source.operationScope(), source.idempotencyKey(), source.requestHash(),
                source.queuedAt(), source.startedAt(), source.finishedAt(), source.createdAt(), source.updatedAt(),
                source.planId(), source.scheduledSlot());
    }
    private TaskRecords.Task task(String status,String stage){OffsetDateTime n=now();String snapshot="{\"parameters\":{\"count\":1},\"authorizedContext\":{\"space\":{\"id\":\"101\"},\"product\":{\"id\":\"102\"},\"competitors\":[]}}";return new TaskRecords.Task(9002,41,101,102L,9001,7001,"IMAGE",status,stage,1,snapshot,"b".repeat(64),6001,7,0,"{}",null,"TASK_CONFIRM",UUID.randomUUID(),"a".repeat(64),n,n,null,n,n);}
    private TaskRecords.Task videoTask(String status,String stage){OffsetDateTime n=now();String snapshot="{\"parameters\":{\"requestText\":\"video\",\"mode\":\"AGENT_ASSISTED\",\"agentSummary\":\"summary\",\"scene\":\"spin\",\"durationSeconds\":5,\"aspectRatio\":\"16:9\",\"resolution\":\"720P\",\"audio\":false,\"seed\":-1,\"promptExtend\":true,\"watermark\":false},\"authorizedContext\":{\"space\":{\"id\":\"101\"},\"product\":{\"id\":\"102\"},\"competitors\":[]}}";return new TaskRecords.Task(9002,41,101,102L,9001,7001,"VIDEO",status,stage,1,snapshot,"b".repeat(64),7001,11,0,"{}",null,"TASK_CONFIRM",UUID.randomUUID(),"a".repeat(64),n,n,null,n,n);}
    private TaskRecords.Execution execution(int no,String status,OffsetDateTime lease){OffsetDateTime n=now();return new TaskRecords.Execution(8100+no,9002,41,no,status,"agent-image-dev","worker-"+no,"bundle-v1","c".repeat(64),lease,"PROVIDER_GENERATING",null,null,null,null,null,null,"{}",null,n,n,null,n,n);}
    private TaskRecords.Execution continuationExecution(int no,String status,OffsetDateTime lease){OffsetDateTime n=now();return new TaskRecords.Execution(8100+no,9002,41,no,status,"agent-image-dev","worker-"+no,"bundle-v1","c".repeat(64),lease,"PROVIDER_RESULT_UNKNOWN","QWEN_IMAGE","qwen-image-3.0",providerRequestId(),"qwen-task-1","UNKNOWN",null,"{}","QWEN_IMAGE_RESULT_TIMEOUT",n,n,n,n,n);}
    private TaskRecords.Execution withContinuation(TaskRecords.Execution source,String status){OffsetDateTime n=now();return new TaskRecords.Execution(source.id(),source.taskId(),source.userId(),source.executionNo(),status,source.consumerName(),source.workerId(),source.bundleVersion(),source.executionTokenHash(),source.leaseExpiresAt(),"PROVIDER_RESULT_UNKNOWN","QWEN_IMAGE","qwen-image-3.0",providerRequestId(),"qwen-task-1","UNKNOWN",null,"{}","QWEN_IMAGE_RESULT_TIMEOUT",source.lastHeartbeatAt(),source.startedAt(),n,source.createdAt(),n);}
    private AssetIngestionProperties assetProperties(){AssetIngestionProperties p=new AssetIngestionProperties();
        p.setEnabled(true);p.setEnvironment("test");p.setMaxAttempts(3);p.setProviderAllowedHosts(List.of("result.example"));return p;}
    private OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    private record ClaimedRecovery(TaskControlService.ClaimedTask claim,
                                   TaskRecords.Execution execution) { }
}
