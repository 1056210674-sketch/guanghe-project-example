package com.guanghe.backend.geo;

public record GeoPlanScheduleChanged(Action action, GeoPlanRecords.Plan plan, long planId) {
    public enum Action { SCHEDULE, UNSCHEDULE }

    public static GeoPlanScheduleChanged schedule(GeoPlanRecords.Plan plan) {
        return new GeoPlanScheduleChanged(Action.SCHEDULE, plan, plan.id());
    }

    public static GeoPlanScheduleChanged unschedule(long planId) {
        return new GeoPlanScheduleChanged(Action.UNSCHEDULE, null, planId);
    }
}
