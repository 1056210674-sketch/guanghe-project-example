-- Only expand the existing deterministic MONITOR plan; no content/publishing schedules.
ALTER TABLE business.geo_plan DROP CONSTRAINT ck_geo_plan_schedule_type;
ALTER TABLE business.geo_plan ADD CONSTRAINT ck_geo_plan_schedule_type
    CHECK (schedule_type IN ('HOURLY','DAILY','WEEKLY','INTERVAL_DAYS'));
