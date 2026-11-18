package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeoPlanSchedulerTests {
    @Test
    void restoresDurableJdbcJobWithDoNothingMisfirePolicy() throws Exception {
        Scheduler quartz = mock(Scheduler.class);
        GeoPlanMapper mapper = mock(GeoPlanMapper.class);
        GeoPlanRecords.Plan plan = plan();
        when(mapper.findActivePlans()).thenReturn(List.of(plan));
        when(quartz.checkExists(any(org.quartz.TriggerKey.class))).thenReturn(false);
        GeoPlanScheduler scheduler = new GeoPlanScheduler(quartz, mapper, new ObjectMapper());

        scheduler.restoreActivePlans();

        ArgumentCaptor<JobDetail> job = ArgumentCaptor.forClass(JobDetail.class);
        verify(quartz).addJob(job.capture(), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(true));
        assertThat(job.getValue().isDurable()).isTrue();
        assertThat(job.getValue().requestsRecovery()).isTrue();
        ArgumentCaptor<Trigger> trigger = ArgumentCaptor.forClass(Trigger.class);
        verify(quartz).scheduleJob(trigger.capture());
        assertThat(trigger.getValue()).isInstanceOf(CronTrigger.class);
        assertThat(trigger.getValue().getMisfireInstruction())
                .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
    }

    @Test
    void hourlyTriggerKeepsOneHourIntervalAndSkipsMissedSlots() throws Exception {
        Scheduler quartz = mock(Scheduler.class);
        GeoPlanMapper mapper = mock(GeoPlanMapper.class);
        var p = plan();
        var hourly = new GeoPlanRecords.Plan(p.id(), p.userId(), p.spaceId(), p.productId(), p.topicId(),
                p.sourceDraftId(), p.createIdempotencyKey(), p.requestHash(), p.name(), p.nameNormalized(), p.status(),
                "HOURLY", new ObjectMapper().writeValueAsString(new GeoPlanDtos.Schedule(
                        "HOURLY", "Asia/Shanghai", "00:05", List.of(), null)),
                p.inputSnapshotJson(), p.inputHash(), p.pricingVersionId(), p.estimatedPointsPerRun(),
                p.maxPointsPerRun(), p.nextRunAt(), p.lastScheduledSlot(), p.pauseReason(), p.version(), p.createdAt(), p.updatedAt());
        new GeoPlanScheduler(quartz, mapper, new ObjectMapper()).schedule(hourly);
        var trigger = ArgumentCaptor.forClass(Trigger.class);
        verify(quartz).scheduleJob(trigger.capture());
        assertThat(trigger.getValue()).isInstanceOf(org.quartz.SimpleTrigger.class);
        var simple = (org.quartz.SimpleTrigger) trigger.getValue();
        assertThat(simple.getRepeatInterval()).isEqualTo(3_600_000L);
        assertThat(simple.getRepeatCount()).isEqualTo(org.quartz.SimpleTrigger.REPEAT_INDEFINITELY);
        assertThat(simple.getMisfireInstruction()).isEqualTo(
                org.quartz.SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_EXISTING_COUNT);
    }

    private GeoPlanRecords.Plan plan() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        String schedule = new ObjectMapper().writeValueAsString(new GeoPlanDtos.Schedule(
                "DAILY", "Asia/Shanghai", "09:00", List.of(), null));
        return new GeoPlanRecords.Plan(9001, 41, 101, null, 201, 5001,
                UUID.randomUUID(), "a".repeat(64), "每日监测", "每日监测", "ACTIVE",
                "DAILY", schedule, "{}", "b".repeat(64), 8001, 3, 10,
                now.plusMinutes(1), null, null, 1, now, now);
    }
}
