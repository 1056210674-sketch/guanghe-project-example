package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.notification.NotificationService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskControlService;
import com.guanghe.backend.task.TaskRecords;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GeoPlanServiceTests {
    private final GeoPlanMapper mapper = mock(GeoPlanMapper.class);
    private final GeoMapper geoMapper = mock(GeoMapper.class);
    private final GeoTaskService geoTasks = mock(GeoTaskService.class);
    private final TaskControlMapper taskMapper = mock(TaskControlMapper.class);
    private final TaskControlService taskControl = mock(TaskControlService.class);
    private final GeoPlanScheduleCalculator schedules = mock(GeoPlanScheduleCalculator.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final ObjectMapper json = new ObjectMapper();
    private final PaidTaskProperties properties = properties();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private GeoPlanService service;

    @BeforeEach
    void setUp() {
        AtomicLong sequence = new AtomicLong(10_000);
        when(ids.nextId()).thenAnswer(invocation -> sequence.incrementAndGet());
        when(geoMapper.findTopic(201, 41)).thenReturn(Optional.of(topic()));
        service = new GeoPlanService(mapper, geoMapper, geoTasks, taskMapper, taskControl,
                schedules, ids, json, properties, events, notifications);
    }

    @Test
    void planDraftPersistsAuthorizationWithoutPointOrTaskSideEffects() {
        GeoRecords.Topic topic = topic();
        GeoPlanDtos.Schedule normalized = schedule();
        ObjectNode parameters = parameters();
        ObjectNode snapshot = snapshot();
        when(geoMapper.findTopic(201, 41)).thenReturn(Optional.of(topic));
        when(schedules.normalize(any())).thenReturn(normalized);
        when(geoTasks.prepareDraft(anyLong(), anyLong(), any(), any()))
                .thenReturn(new GeoTaskService.PreparedDraft(parameters, snapshot, 3));
        when(geoTasks.quote(any())).thenReturn(3);
        when(taskMapper.findDraftByIdempotency(anyLong(), anyString(), any())).thenReturn(Optional.empty());
        when(taskControl.confirmationToken(any())).thenReturn("v1.plan-token");
        when(taskMapper.insertDraft(any())).thenReturn(1);
        when(taskMapper.insertConfirmation(any())).thenReturn(1);

        GeoPlanDtos.DraftResponse response = service.createDraft(user(), UUID.randomUUID().toString(),
                draftRequest());

        assertThat(response.estimatedPointsPerRun()).isEqualTo(3);
        ArgumentCaptor<TaskRecords.Draft> draft = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(taskMapper).insertDraft(draft.capture());
        assertThat(draft.getValue().draftSource()).isEqualTo("PLAN");
        assertThat(draft.getValue().conversationId()).isNull();
        verify(taskMapper, never()).lockPointAccount(anyLong());
        verify(taskMapper, never()).insertTask(any());
        verify(taskMapper, never()).freezePoints(anyLong(), any(Integer.class), any());
    }

    @Test
    void administratorCannotCreateOrReadUserGeoPlans() {
        AuthenticatedAccount admin = new AuthenticatedAccount(1, "admin", AccountRole.ADMIN, 1,
                AccountStatus.ACTIVE, null, null, null);

        assertThatThrownBy(() -> service.createDraft(admin, UUID.randomUUID().toString(), draftRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.list(admin, null, null, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(mapper, never()).insertPlan(any());
        verify(taskMapper, never()).insertDraft(any());
    }

    @Test
    void confirmingPlanConsumesTokenButDoesNotFreezeOrCreateTask() {
        String token = "v1.confirm";
        TaskRecords.Draft draft = planDraft(token);
        when(mapper.findByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(taskMapper.lockDraft(draft.id(), 41)).thenReturn(Optional.of(draft));
        when(taskMapper.lockConfirmation(draft.id(), 41)).thenReturn(Optional.of(confirmation(draft, token)));
        when(geoTasks.quoteScheduled(any())).thenReturn(3);
        when(mapper.planNameExists(anyLong(), anyString(), anyLong())).thenReturn(false);
        when(schedules.next(any(), any())).thenReturn(OffsetDateTime.now().plusMinutes(1));
        when(taskMapper.consumeConfirmation(anyLong(), anyLong(), any())).thenReturn(1);
        when(taskMapper.confirmDraft(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.insertPlan(any())).thenReturn(1);

        GeoPlanDtos.PlanResponse response = service.createPlan(user(), UUID.randomUUID().toString(),
                new GeoPlanDtos.ConfirmPlanRequest(Long.toString(draft.id()), draft.inputHash(),
                        Long.toString(draft.pricingVersionId()), token));

        assertThat(response.status()).isEqualTo("ACTIVE");
        verify(taskMapper, never()).lockPointAccount(anyLong());
        verify(taskMapper, never()).insertTask(any());
        verify(taskMapper, never()).freezePoints(anyLong(), any(Integer.class), any());
        verify(events).publishEvent(any(GeoPlanScheduleChanged.class));
    }

    @Test
    void stalePersonalCreativeGeoDraftCannotBeConfirmedOrConsumed() {
        String token = "v1.confirm";
        TaskRecords.Draft draft = planDraft(token);
        when(mapper.findByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(taskMapper.lockDraft(draft.id(), 41)).thenReturn(Optional.of(draft));
        when(taskMapper.lockConfirmation(draft.id(), 41)).thenReturn(Optional.of(confirmation(draft, token)));
        when(geoTasks.quoteScheduled(any())).thenReturn(3);
        when(geoMapper.findTopic(201, 41)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPlan(user(), UUID.randomUUID().toString(),
                new GeoPlanDtos.ConfirmPlanRequest(Long.toString(draft.id()), draft.inputHash(),
                        Long.toString(draft.pricingVersionId()), token)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        verify(taskMapper, never()).consumeConfirmation(anyLong(), anyLong(), any());
        verify(taskMapper, never()).confirmDraft(anyLong(), anyLong(), any());
        verify(mapper, never()).insertPlan(any());
    }

    @Test
    void repeatedSlotReturnsExistingTaskWithoutCreatingAnotherOne() {
        GeoPlanRecords.Plan plan = plan("ACTIVE");
        OffsetDateTime slot = OffsetDateTime.parse("2026-09-09T01:00:00Z");
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(0);
        when(mapper.findSlot(plan.id(), slot)).thenReturn(Optional.of(new GeoPlanRecords.Slot(
                91, plan.id(), 41, slot, "DISPATCHED", 7001L, 3, null, slot, slot)));

        assertThat(service.dispatchScheduledSlot(plan.id(), slot).disposition()).isEqualTo("DUPLICATE");
        verify(taskControl, never()).createScheduledGeoTask(anyLong(), anyLong(), anyLong(), any(),
                any(), any(), anyString(), anyLong(), any(Integer.class), any());
        verifyNoInteractions(notifications);
    }

    @Test
    void insufficientPointsSkipsSlotAndPausesPlan() {
        GeoPlanRecords.Plan plan = plan("ACTIVE");
        OffsetDateTime slot = OffsetDateTime.parse("2026-09-09T01:00:00Z");
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(taskControl.createScheduledGeoTask(anyLong(), anyLong(), anyLong(), any(), any(), any(),
                anyString(), anyLong(), any(Integer.class), any()))
                .thenReturn(new TaskControlService.ScheduledTaskResult(
                        false, null, 3, "INSUFFICIENT_POINTS"));
        when(mapper.skipSlot(anyLong(), any(), any(), anyString(), any())).thenReturn(1);
        when(mapper.pauseAfterSkip(anyLong(), any(), anyString(), any())).thenReturn(1);

        GeoPlanRecords.DispatchResult result = service.dispatchScheduledSlot(plan.id(), slot);

        assertThat(result.disposition()).isEqualTo("SKIPPED");
        assertThat(result.errorCode()).isEqualTo("INSUFFICIENT_POINTS");
        verify(mapper).pauseAfterSkip(eq(plan.id()), eq(slot), eq("INSUFFICIENT_POINTS"), any());
        verify(notifications).publish(41, NotificationService.CATEGORY_SYSTEM, "自动监测计划已暂停",
                "自动监测计划「每日监测」因可用积分不足已暂停。本次监测未执行，也未扣除积分。请查看计划并处理后再恢复。",
                "GEO_PLAN", plan.id(), "GEO_PLAN_SKIPPED:" + plan.id() + ":" + slot.toInstant());
    }

    @Test
    void disabledPlatformPausesScheduledPlanAndNotifiesWithoutTaskDispatch() {
        GeoPlanRecords.Plan plan = plan("ACTIVE");
        OffsetDateTime slot = OffsetDateTime.parse("2026-09-09T01:00:00Z");
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(taskControl.createScheduledGeoTask(anyLong(), anyLong(), anyLong(), any(), any(), any(),
                anyString(), anyLong(), any(Integer.class), any()))
                .thenReturn(new TaskControlService.ScheduledTaskResult(false, null, null, "PROVIDER_NOT_CONFIGURED"));
        when(mapper.skipSlot(anyLong(), any(), any(), anyString(), any())).thenReturn(1);
        when(mapper.pauseAfterSkip(anyLong(), any(), anyString(), any())).thenReturn(1);
        assertThat(service.dispatchScheduledSlot(plan.id(), slot).disposition()).isEqualTo("SKIPPED");
        verify(notifications).publish(eq(41L), eq(NotificationService.CATEGORY_SYSTEM),
                eq("自动监测计划已暂停"), org.mockito.ArgumentMatchers.contains("所选平台暂不可用"),
                eq("GEO_PLAN"), eq(plan.id()), anyString());
        verify(mapper, never()).dispatchSlot(anyLong(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void disabledPlatformCannotResumePausedPlan() {
        GeoPlanRecords.Plan plan = plan("PAUSED");
        when(mapper.lockPlan(plan.id(), 41)).thenReturn(Optional.of(plan));
        when(geoTasks.quoteScheduled(any())).thenThrow(new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED));
        assertThatThrownBy(() -> service.resume(user(), plan.id(), new GeoPlanDtos.VersionRequest(plan.version())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.PROVIDER_NOT_CONFIGURED));
        verify(mapper, never()).resume(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void priceLimitPauseNotifiesThePlanOwnerWithAnActionableTarget() {
        GeoPlanRecords.Plan plan = plan("ACTIVE");
        OffsetDateTime slot = OffsetDateTime.parse("2026-09-09T09:00:00+08:00");
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(taskControl.createScheduledGeoTask(anyLong(), anyLong(), anyLong(), any(), any(), any(),
                anyString(), anyLong(), any(Integer.class), any()))
                .thenReturn(new TaskControlService.ScheduledTaskResult(false, null, 15, "PRICE_LIMIT_EXCEEDED"));
        when(mapper.skipSlot(anyLong(), any(), any(), anyString(), any())).thenReturn(1);
        when(mapper.pauseAfterSkip(anyLong(), any(), anyString(), any())).thenReturn(1);

        assertThat(service.dispatchScheduledSlot(plan.id(), slot).disposition()).isEqualTo("SKIPPED");

        verify(notifications).publish(41, NotificationService.CATEGORY_SYSTEM, "自动监测计划已暂停",
                "自动监测计划「每日监测」因本次预计积分超过计划授权的单次上限已暂停。本次监测未执行，也未扣除积分。请查看计划并处理后再恢复。",
                "GEO_PLAN", plan.id(), "GEO_PLAN_SKIPPED:" + plan.id() + ":2026-09-09T01:00:00Z");
    }

    @Test
    void failedPauseDoesNotNotifyOrUnscheduleAnUnpersistedChange() {
        GeoPlanRecords.Plan plan = plan("ACTIVE");
        OffsetDateTime slot = OffsetDateTime.parse("2026-09-09T01:00:00Z");
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(taskControl.createScheduledGeoTask(anyLong(), anyLong(), anyLong(), any(), any(), any(),
                anyString(), anyLong(), any(Integer.class), any()))
                .thenReturn(new TaskControlService.ScheduledTaskResult(false, null, 3, "INSUFFICIENT_POINTS"));
        when(mapper.skipSlot(anyLong(), any(), any(), anyString(), any())).thenReturn(1);
        when(mapper.pauseAfterSkip(anyLong(), any(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.dispatchScheduledSlot(plan.id(), slot))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        verifyNoInteractions(notifications, events);
    }

    @Test
    void replayedSkippedSlotDoesNotSendThePauseNotificationAgain() {
        GeoPlanRecords.Plan plan = plan("ACTIVE");
        OffsetDateTime slot = OffsetDateTime.parse("2026-09-09T01:00:00Z");
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(0);
        when(mapper.findSlot(plan.id(), slot)).thenReturn(Optional.of(new GeoPlanRecords.Slot(
                91, plan.id(), 41, slot, "SKIPPED", null, 3, "INSUFFICIENT_POINTS", slot, slot)));

        assertThat(service.dispatchScheduledSlot(plan.id(), slot).disposition()).isEqualTo("DUPLICATE");
        verifyNoInteractions(notifications);
    }

    @Test
    void dispatchedSlotCreatesAndAdvancesExactlyOnce() {
        GeoPlanRecords.Plan plan = plan("ACTIVE");
        OffsetDateTime slot = OffsetDateTime.parse("2026-09-09T01:00:00Z");
        OffsetDateTime next = slot.plusDays(1);
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(taskControl.createScheduledGeoTask(anyLong(), anyLong(), anyLong(), any(), any(), any(),
                anyString(), anyLong(), any(Integer.class), any()))
                .thenReturn(new TaskControlService.ScheduledTaskResult(true, 7001L, 3, null));
        when(mapper.dispatchSlot(eq(plan.id()), eq(slot), eq(7001L), eq(3), any())).thenReturn(1);
        when(schedules.next(any(), any())).thenReturn(next);
        when(mapper.advance(eq(plan.id()), eq(slot), eq(next), any())).thenReturn(1);

        assertThat(service.dispatchScheduledSlot(plan.id(), slot).taskId()).isEqualTo(7001L);
        verify(mapper).dispatchSlot(eq(plan.id()), eq(slot), eq(7001L), eq(3), any());
        verify(mapper).advance(eq(plan.id()), eq(slot), eq(next), any());
        verifyNoInteractions(notifications);
    }

    @Test
    void staleVersionCannotPauseAndResumeCalculatesOnlyFutureSlot() {
        GeoPlanRecords.Plan active = plan("ACTIVE");
        when(mapper.lockPlan(active.id(), 41)).thenReturn(Optional.of(active));
        when(mapper.pause(eq(active.id()), eq(41L), eq(99), eq("USER_PAUSED"), any())).thenReturn(0);
        assertThatThrownBy(() -> service.pause(user(), active.id(), new GeoPlanDtos.VersionRequest(99)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_VERSION_CONFLICT));

        GeoPlanRecords.Plan paused = plan("PAUSED");
        when(mapper.lockPlan(paused.id(), 41)).thenReturn(Optional.of(paused));
        OffsetDateTime next = OffsetDateTime.now().plusDays(1);
        when(schedules.next(any(), any())).thenReturn(next);
        when(mapper.resume(eq(paused.id()), eq(41L), eq(paused.version()), eq(next), any())).thenReturn(1);
        GeoPlanRecords.Plan resumed = plan("ACTIVE");
        when(mapper.findPlan(paused.id(), 41)).thenReturn(Optional.of(resumed));
        assertThat(service.resume(user(), paused.id(), new GeoPlanDtos.VersionRequest(paused.version())).status())
                .isEqualTo("ACTIVE");
        verify(schedules).next(any(), any());
    }

    @Test
    void overlappingTaskSkipsAndAdvancesWithoutPausingOrCharging() {
        var plan = plan("ACTIVE");
        var slot = OffsetDateTime.now().minusSeconds(5).withOffsetSameInstant(java.time.ZoneOffset.UTC);
        var next = OffsetDateTime.now().plusDays(1);
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(mapper.hasInFlightTask(plan.id())).thenReturn(true);
        when(schedules.next(any(), any())).thenReturn(next);
        when(mapper.skipSlot(eq(plan.id()), eq(slot), any(), eq("PREVIOUS_RUN_ACTIVE"), any())).thenReturn(1);
        when(mapper.advance(eq(plan.id()), eq(slot), eq(next), any())).thenReturn(1);
        assertThat(service.dispatchScheduledSlot(plan.id(), slot).errorCode()).isEqualTo("PREVIOUS_RUN_ACTIVE");
        verifyNoInteractions(taskControl, notifications);
        verify(mapper, never()).pauseAfterSkip(anyLong(), any(), anyString(), any());
    }

    @Test
    void hourlyMissedSlotDoesNotCatchUpOrCharge() {
        var plan = hourlyPlan("ACTIVE");
        var platform = mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setAgentPlatform(platform);
        when(platform.canUseInstalledAgent(41, "geo", 101L)).thenReturn(true);
        var slot = OffsetDateTime.now().minusHours(2).withOffsetSameInstant(java.time.ZoneOffset.UTC);
        var next = OffsetDateTime.now().plusHours(1);
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(schedules.hourlyTrustedFloor()).thenReturn(OffsetDateTime.parse("2026-10-01T00:00:00+08:00"));
        when(schedules.next(any(), any())).thenReturn(next);
        when(mapper.skipSlot(eq(plan.id()), eq(slot), any(), eq("SLOT_MISSED"), any())).thenReturn(1);
        when(mapper.advance(eq(plan.id()), eq(slot), eq(next), any())).thenReturn(1);
        assertThat(service.dispatchScheduledSlot(plan.id(), slot).errorCode()).isEqualTo("SLOT_MISSED");
        verifyNoInteractions(taskControl, notifications);
    }

    @Test
    void hourlyRemovedInstallationStopsNewPaidAdmission() {
        var plan = hourlyPlan("ACTIVE");
        var slot = OffsetDateTime.now().minusSeconds(5).withOffsetSameInstant(java.time.ZoneOffset.UTC);
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(mapper.skipSlot(eq(plan.id()), eq(slot), any(), eq("AGENT_AUTHORIZATION_UNAVAILABLE"), any())).thenReturn(1);
        when(mapper.pauseAfterSkip(eq(plan.id()), eq(slot), eq("AGENT_AUTHORIZATION_UNAVAILABLE"), any())).thenReturn(1);
        assertThat(service.dispatchScheduledSlot(plan.id(), slot).errorCode()).isEqualTo("AGENT_AUTHORIZATION_UNAVAILABLE");
        verifyNoInteractions(taskControl);
        verify(events).publishEvent(any(GeoPlanScheduleChanged.class));
    }

    @Test
    void adminStaleVersionIsRejectedEvenWhenAlreadyPaused() {
        var plan = plan("PAUSED");
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        assertThatThrownBy(() -> service.adminPause(99, plan.id(), 99, "维护暂停"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_VERSION_CONFLICT));
        verifyNoInteractions(taskControl, notifications);
    }

    @Test
    void adminResumeCannotSilentlyAdoptNewPrice() {
        var plan = plan("PAUSED");
        var platform = mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setAgentPlatform(platform);
        when(platform.canUseInstalledAgent(41, "geo", 101L)).thenReturn(true);
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(geoTasks.quoteScheduled(any())).thenReturn(4);
        assertThatThrownBy(() -> service.adminResume(99, plan.id(), plan.version()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.TASK_CONFIRMATION_MISMATCH));
        verify(mapper, never()).resume(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void hourlyConfirmationBeforeFloorRegistersOnlyAFuturePlanWithoutCharging() {
        var calculator = realFloorCalculator();
        service = new GeoPlanService(mapper, geoMapper, geoTasks, taskMapper, taskControl,
                calculator, ids, json, properties, events, notifications);
        service.setAccountingClock(Clock.fixed(OffsetDateTime.parse("2026-10-07T09:20:00+08:00").toInstant(), ZoneOffset.UTC));
        allowHourlyInstallation();
        String token = "v1.hourly-confirm";
        var draft = planDraft(token, new GeoPlanDtos.Schedule("HOURLY", "Asia/Shanghai", "00:15", List.of(), null));
        when(mapper.findByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(taskMapper.lockDraft(draft.id(), 41)).thenReturn(Optional.of(draft));
        when(taskMapper.lockConfirmation(draft.id(), 41)).thenReturn(Optional.of(confirmation(draft, token)));
        when(geoTasks.quoteScheduled(any())).thenReturn(3);
        when(taskMapper.consumeConfirmation(anyLong(), anyLong(), any())).thenReturn(1);
        when(taskMapper.confirmDraft(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.insertPlan(any())).thenReturn(1);

        var result = service.createPlan(user(), UUID.randomUUID().toString(),
                new GeoPlanDtos.ConfirmPlanRequest(Long.toString(draft.id()), draft.inputHash(),
                        Long.toString(draft.pricingVersionId()), token));

        assertThat(result.schedule().type()).isEqualTo("HOURLY");
        assertThat(result.nextRunAt()).isEqualTo(OffsetDateTime.parse("2026-10-08T00:15:00+08:00"));
        verify(taskMapper, never()).lockPointAccount(anyLong());
        verify(taskMapper, never()).freezePoints(anyLong(), any(Integer.class), any());
        verify(taskMapper, never()).insertTask(any());
        verifyNoInteractions(taskControl);
        verify(events).publishEvent(any(GeoPlanScheduleChanged.class));
    }

    @Test
    void userAndAdminResumeBeforeFloorBothUseTheSameTrustedFutureCalculator() {
        var calculator = realFloorCalculator();
        service = new GeoPlanService(mapper, geoMapper, geoTasks, taskMapper, taskControl,
                calculator, ids, json, properties, events, notifications);
        service.setAccountingClock(Clock.fixed(OffsetDateTime.parse("2026-10-07T09:20:00+08:00").toInstant(), ZoneOffset.UTC));
        allowHourlyInstallation();
        var paused = hourlyPlan("PAUSED");
        when(mapper.lockPlan(paused.id(), 41)).thenReturn(Optional.of(paused));
        when(mapper.lockPlanInternal(paused.id())).thenReturn(Optional.of(paused));
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(geoTasks.quoteScheduled(any())).thenReturn(3);
        var next = OffsetDateTime.parse("2026-10-08T00:05:00+08:00");
        when(mapper.resume(eq(paused.id()), eq(41L), eq(paused.version()), eq(next), any())).thenReturn(1);
        when(mapper.findPlan(paused.id(), 41)).thenReturn(Optional.of(hourlyPlan("ACTIVE")));

        assertThat(service.resume(user(), paused.id(), new GeoPlanDtos.VersionRequest(paused.version())).status()).isEqualTo("ACTIVE");
        assertThat(service.adminResume(99, paused.id(), paused.version()).status()).isEqualTo("ACTIVE");

        verify(mapper, org.mockito.Mockito.times(2)).resume(eq(paused.id()), eq(41L), eq(paused.version()), eq(next), any());
        verifyNoInteractions(taskControl, taskMapper);
    }

    @Test
    void hourlyBeforeFloorSkipsBothTodayAndPrematureFutureSlotsWithoutPaidAdmission() {
        var plan = hourlyPlan("ACTIVE");
        allowHourlyInstallation();
        service.setAccountingClock(Clock.fixed(OffsetDateTime.parse("2026-10-07T09:20:00+08:00").toInstant(), ZoneOffset.UTC));
        var floor = OffsetDateTime.parse("2026-10-08T00:00:00+08:00");
        var next = floor.plusMinutes(5);
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(schedules.hourlyTrustedFloor()).thenReturn(floor);
        when(schedules.next(any(), any())).thenReturn(next);
        when(mapper.skipSlot(eq(plan.id()), any(), any(), eq("BUDGET_NOT_EFFECTIVE"), any())).thenReturn(1);
        when(mapper.advance(eq(plan.id()), any(), eq(next), any())).thenReturn(1);

        assertThat(service.dispatchScheduledSlot(plan.id(), floor.minusHours(12)).errorCode()).isEqualTo("BUDGET_NOT_EFFECTIVE");
        assertThat(service.dispatchScheduledSlot(plan.id(), floor.plusMinutes(5)).errorCode()).isEqualTo("BUDGET_NOT_EFFECTIVE");

        verifyNoInteractions(taskControl, taskMapper, notifications);
        verify(mapper, never()).pauseAfterSkip(anyLong(), any(), anyString(), any());
        verify(mapper, never()).hasInFlightTask(anyLong());
    }

    @Test
    void hourlyHistoricSlotBelowFloorNeverChargesEvenAfterTheFloorIsEffective() {
        var plan = hourlyPlan("ACTIVE");
        allowHourlyInstallation();
        service.setAccountingClock(Clock.fixed(OffsetDateTime.parse("2026-10-09T09:20:00+08:00").toInstant(), ZoneOffset.UTC));
        var floor = OffsetDateTime.parse("2026-10-08T00:00:00+08:00");
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(schedules.hourlyTrustedFloor()).thenReturn(floor);
        when(schedules.next(any(), any())).thenReturn(OffsetDateTime.parse("2026-10-09T10:05:00+08:00"));
        when(mapper.skipSlot(eq(plan.id()), any(), any(), eq("BUDGET_NOT_EFFECTIVE"), any())).thenReturn(1);
        when(mapper.advance(eq(plan.id()), any(), any(), any())).thenReturn(1);

        assertThat(service.dispatchScheduledSlot(plan.id(), floor.minusMinutes(5)).errorCode()).isEqualTo("BUDGET_NOT_EFFECTIVE");
        verifyNoInteractions(taskControl, taskMapper, notifications);
    }

    @Test
    void missingHourlyFloorPausesWithoutCallingNextOrCreatingPaidTask() {
        var plan = hourlyPlan("ACTIVE");
        allowHourlyInstallation();
        var slot = OffsetDateTime.now();
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(mapper.skipSlot(eq(plan.id()), any(), any(), eq("MONITOR_FLOOR_UNAVAILABLE"), any())).thenReturn(1);
        when(mapper.pauseAfterSkip(eq(plan.id()), any(), eq("MONITOR_FLOOR_UNAVAILABLE"), any())).thenReturn(1);

        assertThat(service.dispatchScheduledSlot(plan.id(), slot).errorCode()).isEqualTo("MONITOR_FLOOR_UNAVAILABLE");
        verify(schedules, never()).next(any(), any());
        verifyNoInteractions(taskControl, taskMapper);
        verify(events).publishEvent(any(GeoPlanScheduleChanged.class));
        verify(notifications).publish(eq(41L), eq(NotificationService.CATEGORY_SYSTEM),
                eq("自动监测计划已暂停"), org.mockito.ArgumentMatchers.contains("日期保护尚未配置"),
                eq("GEO_PLAN"), eq(plan.id()), anyString());
    }

    @Test
    void authorizedHourlySlotAfterFloorStillDispatchesAndAdvancesExactlyOnce() {
        var plan = hourlyPlan("ACTIVE");
        allowHourlyInstallation();
        var slot = OffsetDateTime.parse("2026-10-08T09:05:00+08:00");
        service.setAccountingClock(Clock.fixed(slot.toInstant(), ZoneOffset.UTC));
        var next = slot.plusHours(1);
        when(mapper.lockPlanInternal(plan.id())).thenReturn(Optional.of(plan));
        when(mapper.insertSlot(any())).thenReturn(1);
        when(mapper.findAccountStatus(41)).thenReturn(Optional.of("ACTIVE"));
        when(schedules.hourlyTrustedFloor()).thenReturn(OffsetDateTime.parse("2026-10-08T00:00:00+08:00"));
        when(taskControl.createScheduledGeoTask(anyLong(), anyLong(), anyLong(), any(), any(), any(),
                anyString(), anyLong(), any(Integer.class), any()))
                .thenReturn(new TaskControlService.ScheduledTaskResult(true, 7001L, 3, null));
        when(mapper.dispatchSlot(eq(plan.id()), any(), eq(7001L), eq(3), any())).thenReturn(1);
        when(schedules.next(any(), any())).thenReturn(next);
        when(mapper.advance(eq(plan.id()), any(), eq(next), any())).thenReturn(1);

        assertThat(service.dispatchScheduledSlot(plan.id(), slot).taskId()).isEqualTo(7001L);
        verify(mapper).dispatchSlot(eq(plan.id()), any(), eq(7001L), eq(3), any());
        verify(mapper).advance(eq(plan.id()), any(), eq(next), any());
        verifyNoInteractions(notifications);
    }

    private void allowHourlyInstallation() {
        var platform = mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setAgentPlatform(platform);
        when(platform.canUseInstalledAgent(41, "geo", 101L)).thenReturn(true);
    }

    private GeoPlanScheduleCalculator realFloorCalculator() {
        var platform = new com.guanghe.backend.agentplatform.AgentPlatformProperties();
        platform.setMonitorBudgetEarliestTrustedDate(LocalDate.of(2026, 10, 8));
        var calculator = new GeoPlanScheduleCalculator(properties);
        calculator.setAgentPlatformProperties(platform);
        return calculator;
    }

    private GeoPlanRecords.Plan hourlyPlan(String status) {
        var p = plan(status);
        return new GeoPlanRecords.Plan(p.id(), p.userId(), p.spaceId(), p.productId(), p.topicId(),
                p.sourceDraftId(), p.createIdempotencyKey(), p.requestHash(), p.name(), p.nameNormalized(),
                status, "HOURLY", canonical(new GeoPlanDtos.Schedule("HOURLY", "Asia/Shanghai", "00:05", List.of(), null)),
                p.inputSnapshotJson(), p.inputHash(), p.pricingVersionId(), p.estimatedPointsPerRun(),
                p.maxPointsPerRun(), p.nextRunAt(), p.lastScheduledSlot(), p.pauseReason(), p.version(), p.createdAt(), p.updatedAt());
    }

    private GeoPlanDtos.CreateDraftRequest draftRequest() {
        return new GeoPlanDtos.CreateDraftRequest("201", " 每日监测 ",
                new GeoPlanDtos.ScheduleRequest("DAILY", "Asia/Shanghai",
                        java.time.LocalTime.of(9, 0), null, null),
                List.of("301", "302", "303"), List.of("DOUBAO"), "STANDARD", 10);
    }

    private GeoRecords.Topic topic() {
        OffsetDateTime now = OffsetDateTime.now();
        return new GeoRecords.Topic(201, 41, 101, null, "专题", "专题", "中国大陆", 1, now, now);
    }

    private ObjectNode parameters() {
        ObjectNode node = json.createObjectNode();
        node.put("mode", "MONITOR_RUN"); node.put("topicId", "201");
        node.putArray("questionIds").add("301").add("302").add("303");
        node.putArray("platformCodes").add("DOUBAO");
        node.put("sampleMode", "STANDARD"); node.put("region", "中国大陆");
        return node;
    }

    private ObjectNode snapshot() {
        ObjectNode node = json.createObjectNode();
        node.put("taskType", "GEO"); node.set("parameters", parameters());
        node.set("authorizedContext", json.createObjectNode());
        return node;
    }

    private GeoPlanDtos.Schedule schedule() {
        return new GeoPlanDtos.Schedule("DAILY", "Asia/Shanghai", "09:00", List.of(), null);
    }

    private TaskRecords.Draft planDraft(String token) {
        return planDraft(token, schedule());
    }

    private TaskRecords.Draft planDraft(String token, GeoPlanDtos.Schedule schedule) {
        ObjectNode snapshot = snapshot();
        ObjectNode auth = snapshot.putObject("planAuthorization");
        auth.put("name", "每日监测"); auth.set("schedule", json.valueToTree(schedule));
        auth.put("maxPointsPerRun", 10);
        String value = canonical(snapshot);
        OffsetDateTime now = OffsetDateTime.now();
        return new TaskRecords.Draft(5001, 41, (Long) null, null, null, "PLAN",
                "GEO_PLAN_DRAFT_CREATE", UUID.randomUUID(), "a".repeat(64), "GEO", 1,
                101, null, canonical(parameters()), value, sha(value), 8001, 3, "[]",
                "WAITING_CONFIRMATION", now.plusMinutes(10), null, now, now);
    }

    private TaskRecords.Confirmation confirmation(TaskRecords.Draft draft, String token) {
        return new TaskRecords.Confirmation(6001, draft.id(), 41, sha(token), draft.inputHash(),
                draft.pricingVersionId(), "AVAILABLE", draft.expiresAt(), null, draft.createdAt());
    }

    private GeoPlanRecords.Plan plan(String status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new GeoPlanRecords.Plan(9001, 41, 101, null, 201, 5001, UUID.randomUUID(),
                "a".repeat(64), "每日监测", "每日监测", status, "DAILY", canonical(schedule()),
                canonical(snapshot()), "b".repeat(64), 8001, 3, 10,
                "ACTIVE".equals(status) ? now.plusMinutes(1) : null, null,
                "PAUSED".equals(status) ? "USER_PAUSED" : null, 1, now, now);
    }

    private AuthenticatedAccount user() {
        return new AuthenticatedAccount(41, "user", AccountRole.USER, 1,
                AccountStatus.ACTIVE, null, null, null);
    }

    private PaidTaskProperties properties() {
        PaidTaskProperties result = new PaidTaskProperties();
        result.setGeoTestPricingVersionId(8001);
        result.setGeoTestUnitPoints(1);
        result.setConfirmationTtl(java.time.Duration.ofMinutes(10));
        result.setGeoPlanMinIntervalDays(1);
        result.setGeoPlanMaxIntervalDays(365);
        return result;
    }

    private String canonical(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
