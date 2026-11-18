package com.guanghe.backend.geo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.notification.NotificationService;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskControlService;
import com.guanghe.backend.task.TaskRecords;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "geo-plan-enabled", havingValue = "true")
public class GeoPlanService {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "PAUSED");
    private final GeoPlanMapper mapper;
    private final GeoMapper geoMapper;
    private final GeoTaskService geoTasks;
    private final TaskControlMapper taskMapper;
    private final TaskControlService taskControl;
    private final GeoPlanScheduleCalculator schedules;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper json;
    private final PaidTaskProperties properties;
    private final ApplicationEventPublisher events;
    private final NotificationService notifications;
    private com.guanghe.backend.agentplatform.AgentPlatformService agentPlatform;
    private Clock accountingClock = Clock.systemUTC();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAccountingClock(@org.springframework.beans.factory.annotation.Qualifier("billingClock") Clock clock) {
        accountingClock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAgentPlatform(com.guanghe.backend.agentplatform.AgentPlatformService value) { agentPlatform = value; }

    public GeoPlanService(GeoPlanMapper mapper, GeoMapper geoMapper, GeoTaskService geoTasks,
                          TaskControlMapper taskMapper, TaskControlService taskControl,
                          GeoPlanScheduleCalculator schedules, SnowflakeIdGenerator ids,
                          ObjectMapper json, PaidTaskProperties properties,
                          ApplicationEventPublisher events, NotificationService notifications) {
        this.mapper = mapper;
        this.geoMapper = geoMapper;
        this.geoTasks = geoTasks;
        this.taskMapper = taskMapper;
        this.taskControl = taskControl;
        this.schedules = schedules;
        this.ids = ids;
        this.json = json;
        this.properties = properties;
        this.events = events;
        this.notifications = notifications;
    }

    @Transactional
    public GeoPlanDtos.DraftResponse createDraft(AuthenticatedAccount account, String idempotencyHeader,
                                                  GeoPlanDtos.CreateDraftRequest request) {
        long userId = CatalogAccess.requireUser(account);
        UUID key = uuid(idempotencyHeader);
        long topicId = positiveId(request.topicId());
        GeoRecords.Topic topic = geoMapper.findTopic(topicId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        String name = requiredName(request.name());
        GeoPlanDtos.Schedule schedule = schedules.normalize(request.schedule());
        requireHourlyInstallation(userId, topic.spaceId(), schedule.type());
        ObjectNode raw = rawTaskParameters(topic, request);
        GeoTaskService.PreparedDraft prepared = geoTasks.prepareDraft(
                userId, topic.spaceId(), topic.productId(), raw);
        int quote = geoTasks.quote(prepared);
        if (quote > request.maxPointsPerRun()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);

        ObjectNode authorization = json.createObjectNode();
        authorization.put("name", name);
        authorization.set("schedule", json.valueToTree(schedule));
        authorization.put("maxPointsPerRun", request.maxPointsPerRun());
        ObjectNode snapshot = prepared.snapshot().deepCopy();
        snapshot.set("planAuthorization", authorization.deepCopy());
        String snapshotJson = canonical(snapshot);
        if (snapshotJson.getBytes(StandardCharsets.UTF_8).length > 262_144) throw invalid();
        String requestHash = sha256(canonical(authorizationWithTask(authorization, prepared.parameters())));

        TaskRecords.Draft existing = taskMapper.findDraftByIdempotency(
                userId, "GEO_PLAN_DRAFT_CREATE", key).orElse(null);
        if (existing != null) {
            if (!constantEquals(existing.requestHash(), requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return draftResponse(existing);
        }
        OffsetDateTime now = now();
        TaskRecords.Draft draft = new TaskRecords.Draft(ids.nextId(), userId, null, null,
                null, "PLAN", "GEO_PLAN_DRAFT_CREATE", key, requestHash,
                "GEO", 1, topic.spaceId(), topic.productId(),
                canonical(authorizationWithTask(authorization, prepared.parameters())), snapshotJson,
                sha256(snapshotJson), properties.pricingVersionId("GEO"), quote, "[]",
                "WAITING_CONFIRMATION", now.plus(properties.getConfirmationTtl()), null, now, now);
        String token = taskControl.confirmationToken(draft);
        TaskRecords.Confirmation confirmation = new TaskRecords.Confirmation(ids.nextId(), draft.id(),
                userId, sha256(token), draft.inputHash(), draft.pricingVersionId(), "AVAILABLE",
                draft.expiresAt(), null, now);
        if (taskMapper.insertDraft(draft) != 1 || taskMapper.insertConfirmation(confirmation) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return draftResponse(draft);
    }

    @Transactional
    public GeoPlanDtos.PlanResponse createPlan(AuthenticatedAccount account, String idempotencyHeader,
                                                GeoPlanDtos.ConfirmPlanRequest request) {
        long userId = CatalogAccess.requireUser(account);
        UUID key = uuid(idempotencyHeader);
        long draftId = positiveId(request.draftId());
        long pricingVersion = positiveId(request.pricingVersionId());
        String requestHash = confirmationRequestHash(draftId, request);
        GeoPlanRecords.Plan replay = mapper.findByIdempotency(userId, key).orElse(null);
        if (replay != null) {
            if (!constantEquals(replay.requestHash(), requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return response(replay);
        }
        TaskRecords.Draft draft = requirePlanDraft(taskMapper.lockDraft(draftId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
        validateConfirmation(draft, request.inputHash(), pricingVersion, request.confirmationToken());
        JsonNode snapshot = read(draft.inputSnapshotJson());
        long topicId = requireProjectDraftTarget(userId, draft, snapshot);
        JsonNode planAuth = snapshot.path("planAuthorization");
        GeoPlanDtos.Schedule schedule = readSchedule(planAuth.path("schedule"));
        requireHourlyInstallation(userId, draft.spaceId(), schedule.type());
        String name = requiredName(planAuth.path("name").textValue());
        if (mapper.planNameExists(userId, normalizedName(name), 0)) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        OffsetDateTime now = now();
        GeoPlanRecords.Plan plan = new GeoPlanRecords.Plan(ids.nextId(), userId, draft.spaceId(),
                draft.productId(), topicId,
                draft.id(), key, requestHash, name, normalizedName(name), "ACTIVE", schedule.type(),
                canonical(schedule), draft.inputSnapshotJson(), draft.inputHash(), draft.pricingVersionId(),
                draft.quotedPoints(), positiveInteger(planAuth.path("maxPointsPerRun")),
                schedules.next(schedule, now), null, null, 1, now, now);
        if (taskMapper.consumeConfirmation(draft.id(), userId, now) != 1
                || taskMapper.confirmDraft(draft.id(), userId, now) != 1
                || mapper.insertPlan(plan) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        events.publishEvent(GeoPlanScheduleChanged.schedule(plan));
        return response(plan);
    }

    @Transactional(readOnly = true)
    public PageResponse<GeoPlanDtos.PlanResponse> list(AuthenticatedAccount account, String status,
                                                       String topicIdValue, int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        String normalizedStatus = optionalEnum(status, STATUSES);
        Long topicId = topicIdValue == null ? null : positiveId(topicIdValue);
        long total = mapper.count(userId, normalizedStatus, topicId);
        List<GeoPlanDtos.PlanResponse> items = mapper.findPage(userId, normalizedStatus, topicId,
                (long) (page - 1) * size, size).stream().map(this::response).toList();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional
    public GeoPlanDtos.PlanResponse update(AuthenticatedAccount account, long planId,
                                            GeoPlanDtos.UpdatePlanRequest request) {
        long userId = CatalogAccess.requireUser(account);
        GeoPlanRecords.Plan current = mapper.lockPlan(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (request.isNamePresent() && !request.hasAnyConfirmation()) {
            String name = requiredName(request.getName());
            if (mapper.planNameExists(userId, normalizedName(name), planId)) {
                throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
            }
            if (mapper.updateName(planId, userId, request.getVersion(), name,
                    normalizedName(name), now()) != 1) throw versionConflict();
            return response(mapper.findPlan(planId, userId).orElseThrow());
        }
        if (request.isNamePresent() || !request.hasCompleteConfirmation()) throw invalid();
        long draftId = positiveId(request.getDraftId());
        if (current.sourceDraftId() == draftId) return response(current);
        if (current.version() != request.getVersion()) throw versionConflict();
        TaskRecords.Draft draft = requirePlanDraft(taskMapper.lockDraft(draftId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
        validateConfirmation(draft, request.getInputHash(), positiveId(request.getPricingVersionId()),
                request.getConfirmationToken());
        JsonNode snapshot = read(draft.inputSnapshotJson());
        long topicId = requireProjectDraftTarget(userId, draft, snapshot);
        JsonNode planAuth = snapshot.path("planAuthorization");
        GeoPlanDtos.Schedule schedule = readSchedule(planAuth.path("schedule"));
        String name = requiredName(planAuth.path("name").textValue());
        if (mapper.planNameExists(userId, normalizedName(name), planId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        OffsetDateTime now = now();
        boolean active = "ACTIVE".equals(current.status());
        GeoPlanRecords.Plan replacement = new GeoPlanRecords.Plan(planId, userId, draft.spaceId(),
                draft.productId(), topicId,
                draft.id(), current.createIdempotencyKey(), current.requestHash(), name,
                normalizedName(name), current.status(), schedule.type(), canonical(schedule),
                draft.inputSnapshotJson(), draft.inputHash(), draft.pricingVersionId(), draft.quotedPoints(),
                positiveInteger(planAuth.path("maxPointsPerRun")),
                active ? schedules.next(schedule, now) : null, current.lastScheduledSlot(),
                active ? null : current.pauseReason(), current.version() + 1, current.createdAt(), now);
        if (taskMapper.consumeConfirmation(draft.id(), userId, now) != 1
                || taskMapper.confirmDraft(draft.id(), userId, now) != 1
                || mapper.replacePlan(replacement, current.version()) != 1) throw versionConflict();
        if (active) events.publishEvent(GeoPlanScheduleChanged.schedule(replacement));
        return response(replacement);
    }

    @Transactional
    public GeoPlanDtos.PlanResponse pause(AuthenticatedAccount account, long planId,
                                           GeoPlanDtos.VersionRequest request) {
        long userId = CatalogAccess.requireUser(account);
        GeoPlanRecords.Plan plan = mapper.lockPlan(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if ("PAUSED".equals(plan.status())) return response(plan);
        OffsetDateTime now = now();
        if (mapper.pause(planId, userId, request.version(), "USER_PAUSED", now) != 1) {
            throw versionConflict();
        }
        events.publishEvent(GeoPlanScheduleChanged.unschedule(planId));
        return response(mapper.findPlan(planId, userId).orElseThrow());
    }

    @Transactional
    public GeoPlanDtos.PlanResponse resume(AuthenticatedAccount account, long planId,
                                            GeoPlanDtos.VersionRequest request) {
        long userId = CatalogAccess.requireUser(account);
        GeoPlanRecords.Plan plan = mapper.lockPlan(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if ("ACTIVE".equals(plan.status())) return response(plan);
        requireHourlyInstallation(userId, plan.spaceId(), plan.scheduleType());
        geoTasks.quoteScheduled(read(plan.inputSnapshotJson()));
        OffsetDateTime now = now();
        OffsetDateTime next = schedules.next(readSchedule(read(plan.scheduleJson())), now);
        if (mapper.resume(planId, userId, request.version(), next, now) != 1) throw versionConflict();
        GeoPlanRecords.Plan resumed = mapper.findPlan(planId, userId).orElseThrow();
        events.publishEvent(GeoPlanScheduleChanged.schedule(resumed));
        return response(resumed);
    }

    /** Dedicated operational controls: the admin caller authenticates and audits in its transaction. */
    @Transactional
    public GeoPlanDtos.PlanResponse adminPause(long adminUserId, long planId, int expectedVersion, String reason) {
        if (adminUserId <= 0 || reason == null || reason.isBlank() || reason.length() > 500) throw invalid();
        GeoPlanRecords.Plan plan = mapper.lockPlanInternal(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (plan.version() != expectedVersion) throw versionConflict();
        if ("PAUSED".equals(plan.status())) return response(plan);
        if (mapper.pause(planId, plan.userId(), expectedVersion, "ADMIN_PAUSED", now()) != 1) throw versionConflict();
        events.publishEvent(GeoPlanScheduleChanged.unschedule(planId));
        notifications.publish(plan.userId(), NotificationService.CATEGORY_SYSTEM, "自动监测计划已暂停",
                "管理员已暂停计划「" + plan.name() + "」。原因：" + reason.strip()
                        + "。不会新增监测任务；已经开始的任务继续按原规则处理。",
                "GEO_PLAN", planId, "GEO_PLAN_ADMIN_PAUSE:" + planId + ":" + expectedVersion);
        return response(mapper.findPlan(planId, plan.userId()).orElseThrow());
    }

    @Transactional
    public GeoPlanDtos.PlanResponse adminResume(long adminUserId, long planId, int expectedVersion) {
        if (adminUserId <= 0) throw invalid();
        GeoPlanRecords.Plan plan = mapper.lockPlanInternal(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (plan.version() != expectedVersion) throw versionConflict();
        if ("ACTIVE".equals(plan.status())) return response(plan);
        if (!"ACTIVE".equals(mapper.findAccountStatus(plan.userId()).orElse(null))
                || !hourlyInstallationValid(plan.userId(), plan.spaceId())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "账号、项目或智能体授权已失效，不能恢复计划", Map.of("reason", "AGENT_AUTHORIZATION_UNAVAILABLE"));
        }
        int quote = geoTasks.quoteScheduled(read(plan.inputSnapshotJson()));
        if (quote != plan.estimatedPointsPerRun() || quote > plan.maxPointsPerRun()
                || plan.pricingVersionId() != properties.pricingVersionId("GEO")) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        }
        OffsetDateTime time = now();
        if (mapper.resume(planId, plan.userId(), expectedVersion,
                schedules.next(readSchedule(read(plan.scheduleJson())), time), time) != 1) throw versionConflict();
        GeoPlanRecords.Plan resumed = mapper.findPlan(planId, plan.userId()).orElseThrow();
        events.publishEvent(GeoPlanScheduleChanged.schedule(resumed));
        notifications.publish(plan.userId(), NotificationService.CATEGORY_SYSTEM, "自动监测计划已恢复",
                "管理员已恢复计划「" + plan.name() + "」。从下一时段继续按你原先授权的范围和预算监测，不补跑暂停时段。",
                "GEO_PLAN", planId, "GEO_PLAN_ADMIN_RESUME:" + planId + ":" + expectedVersion);
        return response(resumed);
    }

    @Transactional
    public GeoPlanRecords.DispatchResult dispatchScheduledSlot(long planId, OffsetDateTime scheduledSlot) {
        GeoPlanRecords.Plan plan = mapper.lockPlanInternal(planId).orElse(null);
        if (plan == null || !"ACTIVE".equals(plan.status())) {
            return new GeoPlanRecords.DispatchResult("IGNORED", null, null, "PLAN_NOT_ACTIVE");
        }
        OffsetDateTime slot = scheduledSlot.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime now = now();
        GeoPlanRecords.Slot claim = new GeoPlanRecords.Slot(ids.nextId(), planId, plan.userId(), slot,
                "PROCESSING", null, null, null, now, now);
        if (mapper.insertSlot(claim) != 1) {
            GeoPlanRecords.Slot existing = mapper.findSlot(planId, slot).orElseThrow();
            return new GeoPlanRecords.DispatchResult("DUPLICATE", existing.taskId(),
                    existing.quotedPoints(), existing.errorCode());
        }
        if (!"ACTIVE".equals(mapper.findAccountStatus(plan.userId()).orElse(null))) {
            return skip(plan, slot, "ACCOUNT_DISABLED", null, now);
        }
        if ("HOURLY".equals(plan.scheduleType()) && !hourlyInstallationValid(plan.userId(), plan.spaceId())) {
            return skip(plan, slot, "AGENT_AUTHORIZATION_UNAVAILABLE", null, now);
        }
        if ("HOURLY".equals(plan.scheduleType())) {
            OffsetDateTime floor = schedules.hourlyTrustedFloor();
            if (floor == null) {
                // Fail closed through the existing persisted pause flow, not
                // next(HOURLY,null-floor), and never admit a paid task.
                return skip(plan, slot, "MONITOR_FLOOR_UNAVAILABLE", null, now);
            }
            if (slot.isBefore(floor) || now.isBefore(floor)) {
                return skipAndAdvance(plan, slot, "BUDGET_NOT_EFFECTIVE", now);
            }
        }
        if ("HOURLY".equals(plan.scheduleType())
                && (slot.isAfter(now.plusSeconds(60)) || slot.isBefore(now.minusMinutes(15)))) {
            return skipAndAdvance(plan, slot, "SLOT_MISSED", now);
        }
        if (mapper.hasInFlightTask(planId)) {
            return skipAndAdvance(plan, slot, "PREVIOUS_RUN_ACTIVE", now);
        }
        TaskControlService.ScheduledTaskResult result = taskControl.createScheduledGeoTask(
                plan.id(), plan.userId(), plan.spaceId(), plan.productId(), slot,
                read(plan.inputSnapshotJson()), plan.inputHash(), plan.pricingVersionId(),
                plan.maxPointsPerRun(), now);
        if (!result.dispatched()) {
            return skip(plan, slot, result.errorCode(), result.quotedPoints(), now);
        }
        if (mapper.dispatchSlot(planId, slot, result.taskId(), result.quotedPoints(), now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        OffsetDateTime next = schedules.next(readSchedule(read(plan.scheduleJson())),
                slot.isAfter(now) ? slot.plusNanos(1) : now);
        if (mapper.advance(planId, slot, next, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return new GeoPlanRecords.DispatchResult("DISPATCHED", result.taskId(),
                result.quotedPoints(), null);
    }

    private GeoPlanRecords.DispatchResult skipAndAdvance(GeoPlanRecords.Plan plan, OffsetDateTime slot,
                                                          String code, OffsetDateTime now) {
        OffsetDateTime next = schedules.next(readSchedule(read(plan.scheduleJson())), now);
        if (mapper.skipSlot(plan.id(), slot, null, code, now) != 1
                || mapper.advance(plan.id(), slot, next, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        return new GeoPlanRecords.DispatchResult("SKIPPED", null, null, code);
    }

    private GeoPlanRecords.DispatchResult skip(GeoPlanRecords.Plan plan, OffsetDateTime slot,
                                                String code, Integer points, OffsetDateTime now) {
        if (mapper.skipSlot(plan.id(), slot, points, code, now) != 1
                || mapper.pauseAfterSkip(plan.id(), slot, code, now) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        String reason = switch (code) {
            case "INSUFFICIENT_POINTS" -> "可用积分不足";
            case "PRICE_LIMIT_EXCEEDED" -> "本次预计积分超过计划授权的单次上限";
            case "PROVIDER_NOT_CONFIGURED" -> "所选平台暂不可用，请调整平台后重新配置计划";
            case "TASK_PAID_DISABLED" -> "监测服务暂不可用";
            case "AGENT_AUTHORIZATION_UNAVAILABLE" -> "智能体已停用、移除或项目已归档";
            case "MONITOR_FLOOR_UNAVAILABLE" -> "自动监测日期保护尚未配置";
            default -> null;
        };
        if (reason != null) {
            notifications.publish(plan.userId(), NotificationService.CATEGORY_SYSTEM, "自动监测计划已暂停",
                    "自动监测计划「" + plan.name() + "」因" + reason
                            + "已暂停。本次监测未执行，也未扣除积分。请查看计划并处理后再恢复。",
                    "GEO_PLAN", plan.id(), "GEO_PLAN_SKIPPED:" + plan.id() + ":" + slot.toInstant());
        }
        events.publishEvent(GeoPlanScheduleChanged.unschedule(plan.id()));
        return new GeoPlanRecords.DispatchResult("SKIPPED", null, points, code);
    }

    private void validateConfirmation(TaskRecords.Draft draft, String inputHash,
                                      long pricingVersion, String token) {
        OffsetDateTime now = now();
        TaskRecords.Confirmation confirmation = taskMapper.lockConfirmation(draft.id(), draft.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_CONFIRMATION_EXPIRED));
        JsonNode snapshot = read(draft.inputSnapshotJson());
        int expectedQuote = geoTasks.quoteScheduled(snapshot);
        int max = positiveInteger(snapshot.path("planAuthorization").path("maxPointsPerRun"));
        boolean matches = "WAITING_CONFIRMATION".equals(draft.status())
                && now.isBefore(draft.expiresAt()) && "AVAILABLE".equals(confirmation.status())
                && now.isBefore(confirmation.expiresAt())
                && constantEquals(confirmation.tokenHash(), sha256(token))
                && constantEquals(draft.inputHash(), inputHash)
                && pricingVersion == draft.pricingVersionId()
                && pricingVersion == properties.pricingVersionId("GEO")
                && draft.quotedPoints() == expectedQuote && expectedQuote <= max;
        if (!matches) throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
    }

    private boolean hourlyInstallationValid(long userId, long projectId) {
        return agentPlatform != null && agentPlatform.canUseInstalledAgent(userId, "geo", projectId);
    }

    private void requireHourlyInstallation(long userId, long projectId, String type) {
        if ("HOURLY".equals(type) && !hourlyInstallationValid(userId, projectId)) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "请先启用 GEO 智能体，并恢复当前项目后再设置自动监测", Map.of("reason", "AGENT_AUTHORIZATION_UNAVAILABLE"));
        }
    }

    private TaskRecords.Draft requirePlanDraft(TaskRecords.Draft draft) {
        if (!"PLAN".equals(draft.draftSource()) || !"GEO".equals(draft.taskType())
                || draft.conversationId() != null || draft.agentRunId() != null) throw invalid();
        return draft;
    }

    private long requireProjectDraftTarget(long userId, TaskRecords.Draft draft, JsonNode snapshot) {
        long topicId = positiveId(snapshot.path("parameters").path("topicId").textValue());
        GeoRecords.Topic topic = geoMapper.findTopic(topicId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (topic.spaceId() != draft.spaceId() || !Objects.equals(topic.productId(), draft.productId())) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        }
        return topicId;
    }

    private ObjectNode rawTaskParameters(GeoRecords.Topic topic, GeoPlanDtos.CreateDraftRequest request) {
        ObjectNode node = json.createObjectNode();
        node.put("mode", "MONITOR_RUN");
        node.put("topicId", Long.toString(topic.id()));
        ArrayNode questions = node.putArray("questionIds");
        request.questionIds().forEach(questions::add);
        ArrayNode platforms = node.putArray("platformCodes");
        request.platformCodes().forEach(platforms::add);
        node.put("sampleMode", request.sampleMode());
        node.put("region", topic.region());
        return node;
    }

    private ObjectNode authorizationWithTask(ObjectNode authorization, ObjectNode taskParameters) {
        ObjectNode result = authorization.deepCopy();
        result.set("taskParameters", taskParameters.deepCopy());
        return result;
    }

    private GeoPlanDtos.DraftResponse draftResponse(TaskRecords.Draft draft) {
        String token = "WAITING_CONFIRMATION".equals(draft.status()) && now().isBefore(draft.expiresAt())
                ? taskControl.confirmationToken(draft) : null;
        return new GeoPlanDtos.DraftResponse(Long.toString(draft.id()), draft.inputHash(),
                Long.toString(draft.pricingVersionId()), draft.quotedPoints(), token, draft.expiresAt());
    }

    private GeoPlanDtos.PlanResponse response(GeoPlanRecords.Plan plan) {
        JsonNode snapshot = read(plan.inputSnapshotJson());
        JsonNode parameters = snapshot.path("parameters");
        return new GeoPlanDtos.PlanResponse(Long.toString(plan.id()), Long.toString(plan.topicId()),
                Long.toString(plan.spaceId()), plan.productId() == null ? null : Long.toString(plan.productId()),
                plan.name(), plan.status(), readSchedule(read(plan.scheduleJson())),
                strings(parameters.path("questionIds")), strings(parameters.path("platformCodes")),
                parameters.path("sampleMode").textValue(), plan.estimatedPointsPerRun(),
                plan.maxPointsPerRun(), Long.toString(plan.pricingVersionId()), plan.nextRunAt(),
                plan.lastScheduledSlot(), plan.pauseReason(), plan.version(), plan.createdAt(), plan.updatedAt());
    }

    private GeoPlanDtos.Schedule readSchedule(JsonNode node) {
        try {
            return json.treeToValue(node, GeoPlanDtos.Schedule.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String confirmationRequestHash(long draftId, GeoPlanDtos.ConfirmPlanRequest request) {
        return sha256(canonical(Map.of("draftId", Long.toString(draftId),
                "inputHash", request.inputHash(), "pricingVersionId", request.pricingVersionId(),
                "confirmationToken", request.confirmationToken())));
    }

    private String requiredName(String value) {
        if (value == null) throw invalid();
        String result = value.trim();
        if (result.isEmpty() || result.length() > 100) throw invalid();
        return result;
    }

    private String normalizedName(String value) {
        return Normalizer.normalize(requiredName(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private long positiveId(String value) {
        try {
            long result = Long.parseLong(value);
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private int positiveInteger(JsonNode value) {
        if (!value.isInt() || value.intValue() <= 0) throw invalid();
        return value.intValue();
    }

    private String optionalEnum(String value, Set<String> allowed) {
        if (value == null) return null;
        if (!allowed.contains(value)) throw invalid();
        return value;
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.textValue()));
        return List.copyOf(values);
    }

    private UUID uuid(String value) {
        try {
            UUID result = UUID.fromString(value);
            if (!result.toString().equals(value)) throw invalid();
            return result;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String canonical(Object value) {
        JsonNode node = value instanceof JsonNode jsonNode ? jsonNode : json.valueToTree(value);
        return write(sort(node));
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = json.createObjectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(Comparator.naturalOrder());
            fields.forEach(field -> object.set(field, sort(node.get(field))));
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = json.createArrayNode();
            node.forEach(item -> array.add(sort(item)));
            return array;
        }
        return node;
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
    private BusinessException versionConflict() {
        return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(accountingClock.instant(), ZoneOffset.UTC); }
}
