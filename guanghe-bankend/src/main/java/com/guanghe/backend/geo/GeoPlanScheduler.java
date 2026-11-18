package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import org.quartz.CalendarIntervalScheduleBuilder;
import org.quartz.CronScheduleBuilder;
import org.quartz.DateBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "geo-plan-enabled", havingValue = "true")
public class GeoPlanScheduler {
    private static final Logger log = LoggerFactory.getLogger(GeoPlanScheduler.class);
    private static final String GROUP = "zhixiaohe-geo-plan";
    private static final Map<Integer, String> QUARTZ_DAYS = Map.of(
            1, "MON", 2, "TUE", 3, "WED", 4, "THU", 5, "FRI", 6, "SAT", 7, "SUN");
    private final Scheduler scheduler;
    private final GeoPlanMapper mapper;
    private final ObjectMapper json;

    public GeoPlanScheduler(Scheduler scheduler, GeoPlanMapper mapper, ObjectMapper json) {
        this.scheduler = scheduler;
        this.mapper = mapper;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreActivePlans() {
        for (GeoPlanRecords.Plan plan : mapper.findActivePlans()) {
            try {
                schedule(plan);
            } catch (RuntimeException exception) {
                log.error("geo_plan_schedule_restore_failed planId={}", plan.id());
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPlanChanged(GeoPlanScheduleChanged event) {
        if (event.action() == GeoPlanScheduleChanged.Action.SCHEDULE) schedule(event.plan());
        else unschedule(event.planId());
    }

    void schedule(GeoPlanRecords.Plan plan) {
        try {
            GeoPlanDtos.Schedule schedule = json.readValue(plan.scheduleJson(), GeoPlanDtos.Schedule.class);
            JobKey jobKey = jobKey(plan.id());
            TriggerKey triggerKey = triggerKey(plan.id());
            JobDetail job = JobBuilder.newJob(GeoPlanQuartzJob.class)
                    .withIdentity(jobKey).usingJobData("planId", Long.toString(plan.id()))
                    .storeDurably().requestRecovery().build();
            scheduler.addJob(job, true, true);
            Trigger trigger = trigger(plan, schedule, jobKey, triggerKey);
            if (scheduler.checkExists(triggerKey)) scheduler.rescheduleJob(triggerKey, trigger);
            else scheduler.scheduleJob(trigger);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to persist GEO plan schedule", exception);
        }
    }

    void unschedule(long planId) {
        try {
            scheduler.deleteJob(jobKey(planId));
        } catch (SchedulerException exception) {
            throw new IllegalStateException("Unable to remove GEO plan schedule", exception);
        }
    }

    private Trigger trigger(GeoPlanRecords.Plan plan, GeoPlanDtos.Schedule schedule,
                            JobKey jobKey, TriggerKey triggerKey) {
        TriggerBuilder<Trigger> builder = TriggerBuilder.newTrigger().withIdentity(triggerKey)
                .forJob(jobKey).startAt(Date.from(plan.nextRunAt().toInstant()));
        if ("HOURLY".equals(schedule.type())) {
            return builder.withSchedule(SimpleScheduleBuilder.simpleSchedule()
                    .withIntervalInHours(1).repeatForever()
                    .withMisfireHandlingInstructionNextWithExistingCount()).build();
        }
        if ("INTERVAL_DAYS".equals(schedule.type())) {
            CalendarIntervalScheduleBuilder interval = CalendarIntervalScheduleBuilder.calendarIntervalSchedule()
                    .withInterval(schedule.intervalDays(), DateBuilder.IntervalUnit.DAY)
                    .inTimeZone(java.util.TimeZone.getTimeZone(schedule.timeZone()))
                    .preserveHourOfDayAcrossDaylightSavings(true)
                    .skipDayIfHourDoesNotExist(false)
                    .withMisfireHandlingInstructionDoNothing();
            return builder.withSchedule(interval).build();
        }
        LocalTime time = LocalTime.parse(schedule.localTime());
        String day = "DAILY".equals(schedule.type()) ? "*"
                : String.join(",", schedule.daysOfWeek().stream().map(QUARTZ_DAYS::get).toList());
        String expression = "%d %d %d ? * %s".formatted(
                time.getSecond(), time.getMinute(), time.getHour(), day);
        CronScheduleBuilder cron = CronScheduleBuilder.cronSchedule(expression)
                .inTimeZone(java.util.TimeZone.getTimeZone(ZoneId.of(schedule.timeZone())))
                .withMisfireHandlingInstructionDoNothing();
        return builder.withSchedule(cron).build();
    }

    private JobKey jobKey(long planId) { return new JobKey("geo-plan-" + planId, GROUP); }
    private TriggerKey triggerKey(long planId) { return new TriggerKey("geo-plan-" + planId, GROUP); }
}
