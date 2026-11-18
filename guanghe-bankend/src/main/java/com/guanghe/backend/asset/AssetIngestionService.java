package com.guanghe.backend.asset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.conversation.ConversationEventRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskRecords;
import com.guanghe.backend.task.TaskNotificationService;
import com.guanghe.backend.workflow.WorkflowTaskLifecycleService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "enabled", havingValue = "true")
public class AssetIngestionService {
    private final AssetMapper assets;
    private final TaskControlMapper tasks;
    private final ConversationMapper conversations;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final AssetIngestionProperties properties;
    private final ObservabilitySupport observability;
    private WorkflowTaskLifecycleService workflowLifecycle;
    private TaskNotificationService taskNotifications;
    private Clock accountingClock = Clock.systemUTC();

    @Autowired
    void setAccountingClock(@Qualifier("billingClock") Clock clock) {
        this.accountingClock = Objects.requireNonNull(clock);
    }

    @Autowired
    void setTaskNotifications(TaskNotificationService taskNotifications) {
        this.taskNotifications = taskNotifications;
    }

    @Autowired(required = false)
    void setWorkflowLifecycle(ObjectProvider<WorkflowTaskLifecycleService> provider) {
        this.workflowLifecycle = provider.getIfAvailable();
    }

    @Autowired
    public AssetIngestionService(AssetMapper assets, TaskControlMapper tasks,
                                 ConversationMapper conversations, SnowflakeIdGenerator ids,
                                 ObjectMapper json, AssetIngestionProperties properties,
                                 ObservabilitySupport observability) {
        this.assets = assets;
        this.tasks = tasks;
        this.conversations = conversations;
        this.ids = ids;
        this.json = json;
        this.properties = properties;
        this.observability = observability;
    }

    public AssetIngestionService(AssetMapper assets, TaskControlMapper tasks,
                                 ConversationMapper conversations, SnowflakeIdGenerator ids,
                                 ObjectMapper json, AssetIngestionProperties properties) {
        this(assets, tasks, conversations, ids, json, properties, ObservabilitySupport.noop());
    }

    @Transactional
    public Optional<AssetRecords.IngestionWork> claimNext() {
        OffsetDateTime now = now();
        AssetRecords.IngestionWork work = assets.lockNextWork(now).orElse(null);
        if (work == null) return Optional.empty();
        UUID claimToken = UUID.randomUUID();
        if (assets.claimWork(work.id(), now.plus(properties.getLease()), claimToken, now) != 1) {
            return Optional.empty();
        }
        return assets.findWork(work.id()).filter(claimed -> "PROCESSING".equals(claimed.status())
                && Objects.equals(claimed.claimToken(), claimToken));
    }

    @Transactional
    public boolean renewLease(long workId, int expectedGeneration, UUID expectedClaimToken) {
        if (expectedClaimToken == null) return false;
        OffsetDateTime now = now();
        return assets.renewLease(workId, expectedGeneration, expectedClaimToken,
                now.plus(properties.getLease()), now) == 1;
    }

    @Transactional
    public Optional<ConversationEventRecord> complete(long workId, int expectedGeneration,
                                                       UUID expectedClaimToken,
                                                       DownloadedArtifact artifact, String safetyStatus) {
        OffsetDateTime now = now();
        AssetRecords.IngestionWork work = assets.lockWork(workId).orElse(null);
        if (!ownsActiveLease(work, expectedGeneration, expectedClaimToken, now)) return Optional.empty();
        TaskRecords.Task task = tasks.lockTask(work.taskId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"RUNNING".equals(task.status())) return Optional.empty();
        TaskRecords.Execution execution = tasks.findExecution(work.executionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        if (!"RESULT_READY".equals(execution.status())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }

        boolean video = "WAN_VIDEO".equals(work.providerCode());
        String assetType = video ? "VIDEO" : "IMAGE";
        String extension = video ? ".mp4" : ".png";
        AssetRecords.Asset asset = new AssetRecords.Asset(work.assetId(), work.userId(), work.spaceId(),
                work.productId(), assetType, "GENERATED", work.taskId(), work.artifactIndex(),
                "AI生成" + (video ? "视频-" : "图片-") + work.assetId() + extension,
                work.objectKey(), artifact.contentType(),
                artifact.sizeBytes(), artifact.sha256(), artifact.width(), artifact.height(),
                video ? artifact.durationSeconds() : null,
                "READY", safetyStatus, now, now);
        int inserted = assets.insertAsset(asset);
        if (inserted == 0) {
            AssetRecords.Asset existing = assets.findAssetBySourceArtifact(work.taskId(), work.artifactIndex())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
            if (existing.id() != work.assetId() || !existing.objectKey().equals(work.objectKey())
                    || !existing.sha256().equals(artifact.sha256())) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
        }
        if (work.productId() != null) {
            assets.insertProductRelation(work.assetId(), work.productId(), work.userId(), work.spaceId(), now);
        }

        int requestedCount = requestedCount(task);
        if (requestedCount > 1) {
            if (assets.completeWork(work.id(), expectedGeneration, expectedClaimToken, now) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            recordCandidateEvent(task, work, "ASSET_INGESTION_CANDIDATE_COMPLETED", null, now);
            return Optional.of(finishCandidatesOrProgress(task, work, requestedCount, now));
        }

        ObjectNode assetResult = json.createObjectNode();
        assetResult.put("assetId", Long.toString(work.assetId()));
        assetResult.put("assetType", assetType);
        assetResult.put("status", "READY");
        assetResult.put("contentType", artifact.contentType());
        assetResult.put("sizeBytes", artifact.sizeBytes());
        assetResult.put("sha256", artifact.sha256());
        assetResult.put("width", artifact.width());
        assetResult.put("height", artifact.height());
        if (video) assetResult.put("durationSeconds", artifact.durationSeconds());
        assetResult.put("safetyStatus", safetyStatus);
        ObjectNode result = json.createObjectNode();
        result.put("provider", work.providerCode());
        result.put("model", work.providerModel());
        result.put("providerCostReported", false);
        result.set("asset", assetResult);
        if (!video) {
            assetResult.put("index", work.artifactIndex());
            result.putArray("assets").add(assetResult);
            result.put("requestedCount", 1);
            result.put("succeededCount", 1);
            result.put("failedCount", 0);
        }

        UUID sourceEventId = stableEventId("asset-complete", work.id());
        if (tasks.insertTaskEvent(ids.nextId(), task.id(), task.userId(), execution.id(), sourceEventId,
                null, "RUNNING", "SUCCEEDED", "ASSET_INGESTION_COMPLETED", "JAVA", null, null,
                canonical(Map.of("assetId", Long.toString(work.assetId()), "safetyStatus", safetyStatus)), now) != 1
                || tasks.completeResultReadyExecution(execution.id(), now) != 1
                || tasks.completeTask(task.id(), canonical(result), now) != 1
                || tasks.settleReservation(task.id(), task.quotedPoints(), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        OffsetDateTime accountingAt = settlePoints(task, task.quotedPoints());
        if (assets.completeWork(work.id(), expectedGeneration, expectedClaimToken, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        TaskRecords.PointAccount after = tasks.findPointAccount(task.userId()).orElseThrow();
        tasks.insertLedger(ids.nextId(), task.userId(), 0, -task.quotedPoints(), after.availablePoints(),
                after.frozenPoints(), "TASK_SETTLE", task.id(), "task-settle:" + task.id(),
                "Generated " + assetType + " persisted as permanent asset", accountingAt);
        notifyWorkflowTerminal(task, "SUCCEEDED", null, now);
        return Optional.of(insertBrowserEvent(task, "task.completed", Map.of(
                "taskId", Long.toString(task.id()), "status", "SUCCEEDED",
                "assetId", Long.toString(work.assetId())), now));
    }

    @Transactional
    public Optional<ConversationEventRecord> failOrRetry(long workId, int expectedGeneration,
                                                          UUID expectedClaimToken, String errorCode,
                                                          boolean retryable) {
        OffsetDateTime now = now();
        AssetRecords.IngestionWork work = assets.lockWork(workId).orElse(null);
        if (!ownsActiveLease(work, expectedGeneration, expectedClaimToken, now)) return Optional.empty();
        TaskRecords.Task task = tasks.lockTask(work.taskId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"RUNNING".equals(task.status())) return Optional.empty();
        OffsetDateTime deadline = work.providerUrlExpiresAt() != null
                ? work.providerUrlExpiresAt() : work.ingestionDeadlineAt();
        boolean urlUsable = deadline != null && now.isBefore(deadline);
        if (retryable && urlUsable && work.attempt() < work.maxAttempts()) {
            if (assets.retryWork(work.id(), expectedGeneration, expectedClaimToken,
                    now.plus(properties.getRetryDelay()), errorCode, now) != 1
                    || tasks.updateAssetIngestionStage(task.id(), "ASSET_INGESTION_RETRY", errorCode, now) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            tasks.insertTaskEvent(ids.nextId(), task.id(), task.userId(), work.executionId(),
                    stableEventId("asset-retry-" + work.attempt(), work.id()), null,
                    "RUNNING", "RUNNING", "ASSET_INGESTION_RETRY_SCHEDULED", "SCHEDULER", errorCode,
                    null, canonical(Map.of("attempt", work.attempt(), "maxAttempts", work.maxAttempts())), now);
            return Optional.of(insertBrowserEvent(task, "task.progress", Map.of(
                    "taskId", Long.toString(task.id()), "status", "RUNNING",
                    "stageCode", "ASSET_INGESTION_RETRY", "attempt", work.attempt()), now));
        }

        String terminalCode = urlUsable ? errorCode
                : "WAN_VIDEO".equals(work.providerCode())
                ? "VIDEO_INGESTION_DEADLINE_EXPIRED" : "PROVIDER_URL_EXPIRED";
        int requestedCount = requestedCount(task);
        if (requestedCount > 1) {
            if (assets.failWork(work.id(), expectedGeneration, expectedClaimToken, terminalCode, now) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            recordCandidateEvent(task, work, "ASSET_INGESTION_CANDIDATE_FAILED", terminalCode, now);
            return Optional.of(finishCandidatesOrProgress(task, work, requestedCount, now));
        }
        if (assets.failWork(work.id(), expectedGeneration, expectedClaimToken, terminalCode, now) != 1
                || tasks.failResultReadyExecution(work.executionId(), terminalCode, now) != 1
                || tasks.failAssetIngestionTask(task.id(), terminalCode, now) != 1
                || tasks.releaseReservation(task.id(), task.quotedPoints(), now) != 1
                || settleOrReleasePoints(task, "RELEASE", now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        tasks.insertTaskEvent(ids.nextId(), task.id(), task.userId(), work.executionId(),
                stableEventId("asset-failed", work.id()), null, "RUNNING", "FAILED",
                "ASSET_INGESTION_FAILED", "JAVA", terminalCode, null,
                canonical(Map.of("attempt", work.attempt(), "maxAttempts", work.maxAttempts())), now);
        TaskRecords.PointAccount after = tasks.findPointAccount(task.userId()).orElseThrow();
        tasks.insertLedger(ids.nextId(), task.userId(), task.quotedPoints(), -task.quotedPoints(),
                after.availablePoints(), after.frozenPoints(), "TASK_RELEASE", task.id(),
                "task-release:" + task.id(), "Permanent " + task.taskType() + " asset ingestion failure", now);
        notifyWorkflowTerminal(task, "FAILED", terminalCode, now);
        return Optional.of(insertBrowserEvent(task, "task.failed", Map.of(
                "taskId", Long.toString(task.id()), "status", "FAILED",
                "stageCode", "ASSET_INGESTION_FAILED", "code", terminalCode), now));
    }

    private int requestedCount(TaskRecords.Task task) {
        if (!"IMAGE".equals(task.taskType())) return 1;
        try {
            JsonNode count = json.readTree(task.inputSnapshotJson()).path("parameters").path("count");
            if (count.isMissingNode()) return 1;
            if (!count.isIntegralNumber() || !count.canConvertToInt()
                    || count.intValue() < 1 || count.intValue() > 4
                    || task.quotedPoints() % count.intValue() != 0) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            return count.intValue();
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
    }

    private void recordCandidateEvent(TaskRecords.Task task, AssetRecords.IngestionWork work,
                                       String eventType, String errorCode, OffsetDateTime now) {
        if (tasks.insertTaskEvent(ids.nextId(), task.id(), task.userId(), work.executionId(),
                stableEventId(eventType, work.id()), null, "RUNNING", "RUNNING", eventType,
                "JAVA", errorCode, null, canonical(Map.of("index", work.artifactIndex(),
                        "assetId", Long.toString(work.assetId()), "attempt", work.attempt())), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
    }

    private ConversationEventRecord finishCandidatesOrProgress(TaskRecords.Task task,
                                                                AssetRecords.IngestionWork completed,
                                                                int requestedCount, OffsetDateTime now) {
        // The task row serializes finalization. Never lock sibling work rows here: another
        // completion may already own one and be waiting for this same task row.
        List<AssetRecords.IngestionWork> works = assets.findWorksByTask(task.id());
        if (works.isEmpty() || works.size() > requestedCount) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        for (int index = 0; index < works.size(); index++) {
            AssetRecords.IngestionWork work = works.get(index);
            if (work.artifactIndex() != index + 1 || work.executionId() != completed.executionId()) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
        }
        long succeeded = works.stream().filter(work -> "SUCCEEDED".equals(work.status())).count();
        if (works.stream().anyMatch(work -> !"SUCCEEDED".equals(work.status()) && !"FAILED".equals(work.status()))) {
            return insertBrowserEvent(task, "task.progress", Map.of("taskId", Long.toString(task.id()),
                    "status", "RUNNING", "stageCode", "ASSET_INGESTION_PENDING",
                    "requestedCount", requestedCount, "succeededCount", succeeded), now);
        }

        List<AssetRecords.Asset> readyAssets = assets.findReadyAssetsByTask(task.id());
        if (readyAssets.size() != succeeded) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        int succeededCount = readyAssets.size();
        int failedCount = requestedCount - succeededCount;
        int settled = succeededCount * (task.quotedPoints() / requestedCount);
        int released = task.quotedPoints() - settled;
        String status = succeededCount == 0 ? "FAILED" : failedCount == 0 ? "SUCCEEDED" : "PARTIAL";
        String stage = succeededCount == 0 ? "ASSET_INGESTION_FAILED"
                : failedCount == 0 ? "COMPLETED" : "IMAGE_PARTIAL_COMPLETED";
        String errorCode = succeededCount == 0 ? works.stream()
                .map(AssetRecords.IngestionWork::lastErrorCode).filter(Objects::nonNull)
                .findFirst().orElse("ASSET_INGESTION_FAILED") : null;
        ObjectNode result = json.createObjectNode();
        result.put("provider", completed.providerCode());
        result.put("model", completed.providerModel());
        result.put("providerCostReported", false);
        result.put("requestedCount", requestedCount);
        result.put("succeededCount", succeededCount);
        result.put("failedCount", failedCount);
        ArrayNode results = result.putArray("assets");
        for (AssetRecords.Asset asset : readyAssets) results.add(assetResult(asset));
        if (!results.isEmpty()) result.set("asset", results.get(0));

        int executionUpdated = succeededCount == 0
                ? tasks.failResultReadyExecution(completed.executionId(), errorCode, now)
                : tasks.completeResultReadyExecution(completed.executionId(), now);
        if (executionUpdated != 1
                || assets.finishImageCandidatesTask(task.id(), status, stage, settled, canonical(result), errorCode, now) != 1
                || assets.finalizeImageCandidatesReservation(task.id(), settled, released,
                settled == 0 ? "RELEASED" : released == 0 ? "SETTLED" : "PARTIAL", now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        settleCandidatePoints(task, settled, released, now);
        if (tasks.insertTaskEvent(ids.nextId(), task.id(), task.userId(), completed.executionId(),
                stableEventId("asset-candidates-terminal", task.id()), null, "RUNNING", status,
                succeededCount == 0 ? "ASSET_INGESTION_FAILED" : "ASSET_INGESTION_COMPLETED", "JAVA",
                errorCode, null, canonical(Map.of("requestedCount", requestedCount,
                        "succeededCount", succeededCount, "failedCount", failedCount,
                        "settledPoints", settled, "releasedPoints", released)), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        notifyWorkflowTerminal(task, status, errorCode, now);
        ObjectNode payload = json.createObjectNode();
        payload.put("taskId", Long.toString(task.id()));
        payload.put("status", status);
        payload.put("stageCode", stage);
        payload.put("requestedCount", requestedCount);
        payload.put("succeededCount", succeededCount);
        payload.put("failedCount", failedCount);
        if (succeededCount > 0) payload.put("assetId", Long.toString(readyAssets.getFirst().id()));
        if (errorCode != null) payload.put("code", errorCode);
        return insertBrowserEvent(task, succeededCount == 0 ? "task.failed" : "task.completed", payload, now);
    }

    private ObjectNode assetResult(AssetRecords.Asset asset) {
        ObjectNode result = json.createObjectNode();
        result.put("index", asset.sourceArtifactIndex());
        result.put("assetId", Long.toString(asset.id()));
        result.put("assetType", asset.assetType());
        result.put("status", asset.status());
        result.put("contentType", asset.contentType());
        result.put("sizeBytes", asset.sizeBytes());
        result.put("sha256", asset.sha256());
        result.put("width", asset.width());
        result.put("height", asset.height());
        result.put("safetyStatus", asset.safetyStatus());
        return result;
    }

    private void settleCandidatePoints(TaskRecords.Task task, int settled, int released, OffsetDateTime now) {
        OffsetDateTime accountingAt = now;
        if (settled > 0) {
            accountingAt = settlePoints(task, settled);
            TaskRecords.PointAccount after = tasks.findPointAccount(task.userId()).orElseThrow();
            if (tasks.insertLedger(ids.nextId(), task.userId(), 0, -settled, after.availablePoints(),
                    after.frozenPoints(), "TASK_SETTLE", task.id(), "task-settle:" + task.id(),
                    "Generated IMAGE candidates persisted as permanent assets", accountingAt) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
        }
        if (released > 0) {
            if (settleOrReleasePoints(task, "RELEASE", released, accountingAt) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
            TaskRecords.PointAccount after = tasks.findPointAccount(task.userId()).orElseThrow();
            if (tasks.insertLedger(ids.nextId(), task.userId(), released, -released, after.availablePoints(),
                    after.frozenPoints(), "TASK_RELEASE", task.id(), "task-release:" + task.id(),
                    "Unsuccessful IMAGE candidates released", accountingAt) != 1) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
            }
        }
    }

    private boolean ownsActiveLease(AssetRecords.IngestionWork work, int expectedGeneration,
                                    UUID expectedClaimToken, OffsetDateTime now) {
        return work != null && "PROCESSING".equals(work.status())
                && work.attempt() == expectedGeneration
                && expectedClaimToken != null
                && Objects.equals(work.claimToken(), expectedClaimToken)
                && work.leaseExpiresAt() != null
                && !work.leaseExpiresAt().isBefore(now);
    }

    private void notifyWorkflowTerminal(TaskRecords.Task task, String status,
                                        String errorCode, OffsetDateTime now) {
        if (workflowLifecycle != null) workflowLifecycle.taskTerminal(task, status, errorCode, now);
        if (taskNotifications != null) taskNotifications.publishTerminal(task.id(), task.userId());
    }

    private int settleOrReleasePoints(TaskRecords.Task task, String stage, OffsetDateTime now) {
        return settleOrReleasePoints(task, stage, task.quotedPoints(), now);
    }

    private OffsetDateTime settlePoints(TaskRecords.Task task, int points) {
        tasks.lockPointAccount(task.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT));
        OffsetDateTime accountingAt = OffsetDateTime.ofInstant(accountingClock.instant(), ZoneOffset.UTC);
        if (settleOrReleasePoints(task, "SETTLE", points, accountingAt) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return accountingAt;
    }

    private int settleOrReleasePoints(TaskRecords.Task task, String stage, int points, OffsetDateTime now) {
        return observability.observe("java.points.settle-or-release", stage, span -> {
            span.tag("taskIdRef", observability.reference(task.id()));
            span.tag("userIdHash", observability.reference(task.userId()));
            span.tag("taskType", task.taskType());
        }, () -> "SETTLE".equals(stage)
                ? tasks.settlePoints(task.userId(), points, now)
                : tasks.releasePoints(task.userId(), points, now));
    }

    private ConversationEventRecord insertBrowserEvent(TaskRecords.Task task, String type,
                                                        Object payload, OffsetDateTime now) {
        ObjectNode taskPayload = json.valueToTree(payload);
        taskPayload.put("deliveryKind", "BACKGROUND_TASK");
        long sequence = conversations.nextEventSequence(task.conversationId(), task.userId());
        ConversationEventRecord event = new ConversationEventRecord(ids.nextId(), task.conversationId(),
                task.userId(), null, null, task.id(), null, type, sequence,
                canonical(taskPayload), now, now);
        if (conversations.insertEvent(event) != 1) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        return event;
    }

    private UUID stableEventId(String scope, long workId) {
        return UUID.nameUUIDFromBytes((scope + ":" + workId).getBytes(StandardCharsets.UTF_8));
    }

    private String canonical(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
