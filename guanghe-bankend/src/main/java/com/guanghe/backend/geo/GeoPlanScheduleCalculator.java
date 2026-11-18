package com.guanghe.backend.geo;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.agentplatform.AgentPlatformProperties;
import com.guanghe.backend.task.PaidTaskProperties;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRulesProvider;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "geo-plan-enabled", havingValue = "true")
public class GeoPlanScheduleCalculator {
    private final PaidTaskProperties properties;
    private AgentPlatformProperties agentProperties;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAgentPlatformProperties(AgentPlatformProperties value) { agentProperties = value; }

    public GeoPlanScheduleCalculator(PaidTaskProperties properties) {
        this.properties = properties;
    }

    public GeoPlanDtos.Schedule normalize(GeoPlanDtos.ScheduleRequest request) {
        try {
            if (!ZoneRulesProvider.getAvailableZoneIds().contains(request.timeZone())) throw invalid();
            if (request.localTime().getSecond() != 0 || request.localTime().getNano() != 0) throw invalid();
            List<Integer> days = request.daysOfWeek() == null ? List.of() : List.copyOf(
                    new LinkedHashSet<>(request.daysOfWeek()));
            if (days.size() != (request.daysOfWeek() == null ? 0 : request.daysOfWeek().size())) throw invalid();
            Integer interval = request.intervalDays();
            switch (request.type()) {
                case "HOURLY" -> {
                    requireHourlyTrustedFloor();
                    if (!days.isEmpty() || interval != null) throw invalid();
                }
                case "DAILY" -> {
                    if (!days.isEmpty() || interval != null) throw invalid();
                }
                case "WEEKLY" -> {
                    if (days.isEmpty() || interval != null) throw invalid();
                    days = days.stream().sorted().toList();
                }
                case "INTERVAL_DAYS" -> {
                    if (!days.isEmpty() || interval == null
                            || interval < properties.getGeoPlanMinIntervalDays()
                            || interval > properties.getGeoPlanMaxIntervalDays()) throw invalid();
                }
                default -> throw invalid();
            }
            return new GeoPlanDtos.Schedule(request.type(), request.timeZone(),
                    ("HOURLY".equals(request.type())
                            ? request.localTime().withHour(0) : request.localTime()).toString(), days, interval);
        } catch (java.time.DateTimeException exception) {
            throw invalid();
        }
    }

    public OffsetDateTime next(GeoPlanDtos.Schedule schedule, OffsetDateTime after) {
        ZoneId zone = ZoneId.of(schedule.timeZone());
        ZonedDateTime localAfter = after.atZoneSameInstant(zone);
        LocalDate date = localAfter.toLocalDate();
        LocalTime time = LocalTime.parse(schedule.localTime());
        if ("HOURLY".equals(schedule.type())) {
            OffsetDateTime floor = requireHourlyTrustedFloor();
            if (after.isBefore(floor)) {
                // The floor is a Shanghai date, independent of the display zone.
                // Include an anchor exactly at the floor while staying strictly
                // later than the real caller time. This is not a catch-up run.
                localAfter = floor.minusNanos(1).atZoneSameInstant(zone);
            }
            // A one-hour elapsed interval, including DST repeats/gaps. The minute is the anchor.
            ZonedDateTime candidate = localAfter.withMinute(time.getMinute()).withSecond(0).withNano(0);
            if (!candidate.isAfter(localAfter)) candidate = candidate.plusHours(1);
            return candidate.toOffsetDateTime();
        }
        if ("INTERVAL_DAYS".equals(schedule.type())) {
            ZonedDateTime candidate = date.atTime(time).atZone(zone);
            if (!candidate.isAfter(localAfter)) candidate = candidate.plusDays(schedule.intervalDays());
            return candidate.toOffsetDateTime();
        }
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate candidateDate = date.plusDays(offset);
            if ("WEEKLY".equals(schedule.type())
                    && !schedule.daysOfWeek().contains(candidateDate.getDayOfWeek().getValue())) continue;
            ZonedDateTime candidate = candidateDate.atTime(time).atZone(zone);
            if (candidate.isAfter(localAfter)) return candidate.toOffsetDateTime();
        }
        throw new IllegalStateException("Unable to calculate next GEO plan slot");
    }

    OffsetDateTime hourlyTrustedFloor() {
        LocalDate floor = agentProperties == null ? null : agentProperties.getMonitorBudgetEarliestTrustedDate();
        return floor == null ? null : floor.atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
    }

    private OffsetDateTime requireHourlyTrustedFloor() {
        OffsetDateTime floor = hourlyTrustedFloor();
        if (floor == null) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "自动监测日期保护尚未配置，请稍后再试",
                    java.util.Map.of("reason", "MONITOR_FLOOR_UNAVAILABLE"));
        }
        return floor;
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
