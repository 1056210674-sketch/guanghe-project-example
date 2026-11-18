package com.guanghe.backend.geo;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.agentplatform.AgentPlatformProperties;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeoPlanScheduleCalculatorTests {
    private final PaidTaskProperties properties = properties();
    private final GeoPlanScheduleCalculator calculator = new GeoPlanScheduleCalculator(properties);

    @BeforeEach
    void trustedFloor() {
        AgentPlatformProperties platform = new AgentPlatformProperties();
        platform.setMonitorBudgetEarliestTrustedDate(LocalDate.of(2026, 10, 8));
        calculator.setAgentPlatformProperties(platform);
    }

    @Test
    void normalizesWeeklyDaysAndCalculatesOnlyAFutureSlot() {
        GeoPlanDtos.Schedule schedule = calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "WEEKLY", "Asia/Shanghai", LocalTime.of(9, 0), List.of(5, 1), null));
        OffsetDateTime after = OffsetDateTime.parse("2026-09-08T10:00:00+08:00");
        assertThat(schedule.daysOfWeek()).containsExactly(1, 5);
        assertThat(calculator.next(schedule, after))
                .isEqualTo(OffsetDateTime.parse("2026-09-11T09:00:00+08:00"));
    }

    @Test
    void rejectsUnknownZoneDuplicateWeekdayAndOutOfRangeInterval() {
        assertThatThrownBy(() -> calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "DAILY", "Mars/Olympus", LocalTime.NOON, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "WEEKLY", "Asia/Shanghai", LocalTime.NOON, List.of(1, 1), null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "INTERVAL_DAYS", "Asia/Shanghai", LocalTime.NOON, null, 31)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void daylightSavingGapUsesZoneRulesAndStillProducesOneFutureInstant() {
        GeoPlanDtos.Schedule schedule = calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "DAILY", "America/New_York", LocalTime.of(2, 30), null, null));
        OffsetDateTime next = calculator.next(schedule, OffsetDateTime.parse("2026-03-08T06:00:00Z"));
        assertThat(next.toInstant()).isAfter(OffsetDateTime.parse("2026-03-08T06:00:00Z").toInstant());
        assertThat(next.atZoneSameInstant(ZoneId.of("America/New_York")).toLocalTime())
                .isEqualTo(LocalTime.of(3, 30));
    }

    private PaidTaskProperties properties() {
        PaidTaskProperties result = new PaidTaskProperties();
        result.setGeoPlanMinIntervalDays(1);
        result.setGeoPlanMaxIntervalDays(30);
        return result;
    }

    @Test
    void hourlyIsStrictlyFutureAndDoesNotCatchUpAfterAnOutage() {
        GeoPlanDtos.Schedule schedule = calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "HOURLY", "Asia/Shanghai", LocalTime.of(9, 15), null, null));
        assertThat(schedule.localTime()).isEqualTo("00:15");
        assertThat(calculator.next(schedule, OffsetDateTime.parse("2026-10-07T23:15:00+08:00")))
                .isEqualTo(OffsetDateTime.parse("2026-10-08T00:15:00+08:00"));
        assertThat(calculator.next(schedule, OffsetDateTime.parse("2026-10-09T10:48:00+08:00")))
                .isEqualTo(OffsetDateTime.parse("2026-10-09T11:15:00+08:00"));
        assertThatThrownBy(() -> calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "HOURLY", "Asia/Shanghai", LocalTime.NOON, List.of(1), null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void hourlyDuringDstFallbackIsAnElapsedHourNotDuplicateLocalTime() {
        GeoPlanDtos.Schedule schedule = calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "HOURLY", "America/New_York", LocalTime.of(0, 30), null, null));
        OffsetDateTime after = OffsetDateTime.parse("2026-11-01T01:30:00-04:00");
        assertThat(calculator.next(schedule, after).toInstant()).isEqualTo(after.plusHours(1).toInstant());
    }

    @Test
    void hourlyCreationAndResumeBeforeFloorUseOnlyTheFirstFutureTrustedAnchor() {
        GeoPlanDtos.Schedule quarter = calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "HOURLY", "Asia/Shanghai", LocalTime.of(9, 15), null, null));
        assertThat(calculator.next(quarter, OffsetDateTime.parse("2026-10-07T09:20:00+08:00")))
                .isEqualTo(OffsetDateTime.parse("2026-10-08T00:15:00+08:00"));
        assertThat(calculator.next(quarter, OffsetDateTime.parse("2026-10-07T23:59:59+08:00")))
                .isEqualTo(OffsetDateTime.parse("2026-10-08T00:15:00+08:00"));
        GeoPlanDtos.Schedule zero = calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "HOURLY", "Asia/Shanghai", LocalTime.MIDNIGHT, null, null));
        assertThat(calculator.next(zero, OffsetDateTime.parse("2026-10-07T09:20:00+08:00")))
                .isEqualTo(OffsetDateTime.parse("2026-10-08T00:00:00+08:00"));
        assertThat(calculator.next(zero, OffsetDateTime.parse("2026-10-08T00:00:00+08:00")))
                .isEqualTo(OffsetDateTime.parse("2026-10-08T01:00:00+08:00"));
    }

    @Test
    void hourlyFloorIsShanghaiEvenForAnotherDisplayZone() {
        GeoPlanDtos.Schedule schedule = calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "HOURLY", "America/New_York", LocalTime.of(0, 30), null, null));
        assertThat(calculator.next(schedule, OffsetDateTime.parse("2026-10-07T00:00:00Z")).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-07T16:30:00Z").toInstant());
    }

    @Test
    void missingFloorRejectsHourlyButPreservesLegacyDailyAndWeeklySchedules() {
        calculator.setAgentPlatformProperties(new AgentPlatformProperties());
        assertThatThrownBy(() -> calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "HOURLY", "Asia/Shanghai", LocalTime.MIDNIGHT, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        assertThatThrownBy(() -> calculator.next(new GeoPlanDtos.Schedule(
                "HOURLY", "Asia/Shanghai", "00:15", List.of(), null), OffsetDateTime.now()))
                .isInstanceOf(BusinessException.class);
        GeoPlanDtos.Schedule daily = calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "DAILY", "Asia/Shanghai", LocalTime.of(9, 0), null, null));
        assertThat(calculator.next(daily, OffsetDateTime.parse("2026-10-07T08:00:00+08:00")))
                .isEqualTo(OffsetDateTime.parse("2026-10-07T09:00:00+08:00"));
        GeoPlanDtos.Schedule weekly = calculator.normalize(new GeoPlanDtos.ScheduleRequest(
                "WEEKLY", "Asia/Shanghai", LocalTime.of(9, 0), List.of(3), null));
        assertThat(calculator.next(weekly, OffsetDateTime.parse("2026-10-07T08:00:00+08:00")))
                .isEqualTo(OffsetDateTime.parse("2026-10-07T09:00:00+08:00"));
    }
}
