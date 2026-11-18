ALTER TABLE business.task_draft
    DROP CONSTRAINT ck_task_draft_source,
    DROP CONSTRAINT ck_task_draft_source_binding,
    ALTER COLUMN conversation_id DROP NOT NULL;

ALTER TABLE business.task_draft
    ADD CONSTRAINT ck_task_draft_source CHECK (draft_source IN ('CHAT', 'FORM', 'PLAN')),
    ADD CONSTRAINT ck_task_draft_source_binding CHECK (
        (draft_source = 'CHAT' AND conversation_id IS NOT NULL AND agent_run_id IS NOT NULL
            AND operation_scope IS NULL AND idempotency_key IS NULL AND request_hash IS NULL)
        OR
        (draft_source = 'FORM' AND conversation_id IS NOT NULL AND agent_run_id IS NULL
            AND operation_scope IN ('TASK_DRAFT_CREATE', 'TASK_DRAFT_REVISION')
            AND idempotency_key IS NOT NULL AND request_hash ~ '^[0-9a-f]{64}$')
        OR
        (draft_source = 'PLAN' AND conversation_id IS NULL AND agent_run_id IS NULL
            AND operation_scope = 'GEO_PLAN_DRAFT_CREATE'
            AND idempotency_key IS NOT NULL AND request_hash ~ '^[0-9a-f]{64}$')
    );

CREATE TABLE business.geo_plan (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    product_id bigint,
    topic_id bigint NOT NULL,
    source_draft_id bigint NOT NULL,
    create_idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    name varchar(100) NOT NULL,
    name_normalized varchar(100) NOT NULL,
    status varchar(16) NOT NULL,
    schedule_type varchar(24) NOT NULL,
    schedule jsonb NOT NULL,
    input_snapshot jsonb NOT NULL,
    input_hash char(64) NOT NULL,
    pricing_version_id bigint NOT NULL,
    estimated_points_per_run integer NOT NULL,
    max_points_per_run integer NOT NULL,
    next_run_at timestamptz,
    last_scheduled_slot timestamptz,
    pause_reason varchar(64),
    version integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_plan_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT fk_geo_plan_space FOREIGN KEY (space_id, user_id)
        REFERENCES business.business_space (id, user_id),
    CONSTRAINT fk_geo_plan_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id),
    CONSTRAINT fk_geo_plan_topic FOREIGN KEY (topic_id, user_id, space_id)
        REFERENCES business.geo_topic (id, user_id, space_id),
    CONSTRAINT fk_geo_plan_draft FOREIGN KEY (source_draft_id, user_id)
        REFERENCES business.task_draft (id, user_id),
    CONSTRAINT uk_geo_plan_owner UNIQUE (id, user_id),
    CONSTRAINT uk_geo_plan_source_draft UNIQUE (source_draft_id),
    CONSTRAINT uk_geo_plan_idempotency UNIQUE (user_id, create_idempotency_key),
    CONSTRAINT uk_geo_plan_name UNIQUE (user_id, name_normalized),
    CONSTRAINT ck_geo_plan_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_geo_plan_status CHECK (status IN ('ACTIVE', 'PAUSED')),
    CONSTRAINT ck_geo_plan_schedule_type CHECK (
        schedule_type IN ('DAILY', 'WEEKLY', 'INTERVAL_DAYS')
    ),
    CONSTRAINT ck_geo_plan_schedule CHECK (
        jsonb_typeof(schedule) = 'object'
        AND octet_length(schedule::text) <= 4096
        AND schedule->>'type' = schedule_type
        AND jsonb_typeof(schedule->'timeZone') = 'string'
        AND jsonb_typeof(schedule->'localTime') = 'string'
    ),
    CONSTRAINT ck_geo_plan_snapshot CHECK (
        jsonb_typeof(input_snapshot) = 'object'
        AND octet_length(input_snapshot::text) <= 262144
    ),
    CONSTRAINT ck_geo_plan_input_hash CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_geo_plan_points CHECK (
        estimated_points_per_run > 0 AND max_points_per_run > 0
        AND estimated_points_per_run <= max_points_per_run
    ),
    CONSTRAINT ck_geo_plan_version CHECK (version > 0),
    CONSTRAINT ck_geo_plan_lifecycle CHECK (
        (status = 'ACTIVE' AND next_run_at IS NOT NULL AND pause_reason IS NULL)
        OR (status = 'PAUSED' AND next_run_at IS NULL AND pause_reason IS NOT NULL)
    )
);

CREATE INDEX idx_geo_plan_user_created
    ON business.geo_plan (user_id, created_at DESC, id DESC);
CREATE INDEX idx_geo_plan_active_next
    ON business.geo_plan (next_run_at, id) WHERE status = 'ACTIVE';

ALTER TABLE business.business_task
    ALTER COLUMN conversation_id DROP NOT NULL,
    ALTER COLUMN draft_id DROP NOT NULL,
    ADD COLUMN plan_id bigint,
    ADD COLUMN scheduled_slot timestamptz,
    ADD CONSTRAINT fk_business_task_plan FOREIGN KEY (plan_id, user_id)
        REFERENCES business.geo_plan (id, user_id),
    ADD CONSTRAINT ck_business_task_origin CHECK (
        (plan_id IS NULL AND scheduled_slot IS NULL AND conversation_id IS NOT NULL AND draft_id IS NOT NULL)
        OR
        (plan_id IS NOT NULL AND scheduled_slot IS NOT NULL AND conversation_id IS NULL AND draft_id IS NULL
            AND task_type = 'GEO' AND operation_scope = 'GEO_PLAN_SLOT')
    );

CREATE UNIQUE INDEX uk_business_task_plan_slot
    ON business.business_task (plan_id, scheduled_slot) WHERE plan_id IS NOT NULL;

CREATE TABLE business.geo_plan_slot (
    id bigint PRIMARY KEY,
    plan_id bigint NOT NULL,
    user_id bigint NOT NULL,
    scheduled_slot timestamptz NOT NULL,
    status varchar(16) NOT NULL,
    task_id bigint,
    quoted_points integer,
    error_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_plan_slot_plan FOREIGN KEY (plan_id, user_id)
        REFERENCES business.geo_plan (id, user_id),
    CONSTRAINT fk_geo_plan_slot_task FOREIGN KEY (task_id, user_id)
        REFERENCES business.business_task (id, user_id),
    CONSTRAINT uk_geo_plan_slot UNIQUE (plan_id, scheduled_slot),
    CONSTRAINT uk_geo_plan_slot_task UNIQUE (task_id),
    CONSTRAINT ck_geo_plan_slot_status CHECK (status IN ('PROCESSING', 'DISPATCHED', 'SKIPPED')),
    CONSTRAINT ck_geo_plan_slot_points CHECK (quoted_points IS NULL OR quoted_points > 0),
    CONSTRAINT ck_geo_plan_slot_error CHECK (
        error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    CONSTRAINT ck_geo_plan_slot_result CHECK (
        (status = 'PROCESSING' AND task_id IS NULL AND quoted_points IS NULL AND error_code IS NULL)
        OR (status = 'DISPATCHED' AND task_id IS NOT NULL AND quoted_points IS NOT NULL AND error_code IS NULL)
        OR (status = 'SKIPPED' AND task_id IS NULL AND error_code IS NOT NULL)
    )
);

CREATE INDEX idx_geo_plan_slot_plan_time
    ON business.geo_plan_slot (plan_id, scheduled_slot DESC);

CREATE TABLE business.qrtz_job_details (
    sched_name varchar(120) NOT NULL, job_name varchar(200) NOT NULL,
    job_group varchar(200) NOT NULL, description varchar(250), job_class_name varchar(250) NOT NULL,
    is_durable boolean NOT NULL, is_nonconcurrent boolean NOT NULL,
    is_update_data boolean NOT NULL, requests_recovery boolean NOT NULL, job_data bytea,
    PRIMARY KEY (sched_name, job_name, job_group)
);
CREATE TABLE business.qrtz_triggers (
    sched_name varchar(120) NOT NULL, trigger_name varchar(200) NOT NULL,
    trigger_group varchar(200) NOT NULL, job_name varchar(200) NOT NULL,
    job_group varchar(200) NOT NULL, description varchar(250), next_fire_time bigint,
    prev_fire_time bigint, priority integer, trigger_state varchar(16) NOT NULL,
    trigger_type varchar(8) NOT NULL, start_time bigint NOT NULL, end_time bigint,
    calendar_name varchar(200), misfire_instr smallint, job_data bytea,
    PRIMARY KEY (sched_name, trigger_name, trigger_group),
    FOREIGN KEY (sched_name, job_name, job_group)
        REFERENCES business.qrtz_job_details (sched_name, job_name, job_group)
);
CREATE TABLE business.qrtz_simple_triggers (
    sched_name varchar(120) NOT NULL, trigger_name varchar(200) NOT NULL,
    trigger_group varchar(200) NOT NULL, repeat_count bigint NOT NULL,
    repeat_interval bigint NOT NULL, times_triggered bigint NOT NULL,
    PRIMARY KEY (sched_name, trigger_name, trigger_group),
    FOREIGN KEY (sched_name, trigger_name, trigger_group)
        REFERENCES business.qrtz_triggers (sched_name, trigger_name, trigger_group) ON DELETE CASCADE
);
CREATE TABLE business.qrtz_cron_triggers (
    sched_name varchar(120) NOT NULL, trigger_name varchar(200) NOT NULL,
    trigger_group varchar(200) NOT NULL, cron_expression varchar(120) NOT NULL,
    time_zone_id varchar(80), PRIMARY KEY (sched_name, trigger_name, trigger_group),
    FOREIGN KEY (sched_name, trigger_name, trigger_group)
        REFERENCES business.qrtz_triggers (sched_name, trigger_name, trigger_group) ON DELETE CASCADE
);
CREATE TABLE business.qrtz_simprop_triggers (
    sched_name varchar(120) NOT NULL, trigger_name varchar(200) NOT NULL,
    trigger_group varchar(200) NOT NULL,
    str_prop_1 varchar(512), str_prop_2 varchar(512), str_prop_3 varchar(512),
    int_prop_1 integer, int_prop_2 integer, long_prop_1 bigint, long_prop_2 bigint,
    dec_prop_1 numeric(13,4), dec_prop_2 numeric(13,4), bool_prop_1 boolean, bool_prop_2 boolean,
    time_zone_id varchar(80), PRIMARY KEY (sched_name, trigger_name, trigger_group),
    FOREIGN KEY (sched_name, trigger_name, trigger_group)
        REFERENCES business.qrtz_triggers (sched_name, trigger_name, trigger_group) ON DELETE CASCADE
);
CREATE TABLE business.qrtz_blob_triggers (
    sched_name varchar(120) NOT NULL, trigger_name varchar(200) NOT NULL,
    trigger_group varchar(200) NOT NULL, blob_data bytea,
    PRIMARY KEY (sched_name, trigger_name, trigger_group),
    FOREIGN KEY (sched_name, trigger_name, trigger_group)
        REFERENCES business.qrtz_triggers (sched_name, trigger_name, trigger_group) ON DELETE CASCADE
);
CREATE TABLE business.qrtz_calendars (
    sched_name varchar(120) NOT NULL, calendar_name varchar(200) NOT NULL,
    calendar bytea NOT NULL, PRIMARY KEY (sched_name, calendar_name)
);
CREATE TABLE business.qrtz_paused_trigger_grps (
    sched_name varchar(120) NOT NULL, trigger_group varchar(200) NOT NULL,
    PRIMARY KEY (sched_name, trigger_group)
);
CREATE TABLE business.qrtz_fired_triggers (
    sched_name varchar(120) NOT NULL, entry_id varchar(95) NOT NULL,
    trigger_name varchar(200) NOT NULL, trigger_group varchar(200) NOT NULL,
    instance_name varchar(200) NOT NULL, fired_time bigint NOT NULL,
    sched_time bigint NOT NULL, priority integer NOT NULL, state varchar(16) NOT NULL,
    job_name varchar(200), job_group varchar(200), is_nonconcurrent boolean,
    requests_recovery boolean, PRIMARY KEY (sched_name, entry_id)
);
CREATE TABLE business.qrtz_scheduler_state (
    sched_name varchar(120) NOT NULL, instance_name varchar(200) NOT NULL,
    last_checkin_time bigint NOT NULL, checkin_interval bigint NOT NULL,
    PRIMARY KEY (sched_name, instance_name)
);
CREATE TABLE business.qrtz_locks (
    sched_name varchar(120) NOT NULL, lock_name varchar(40) NOT NULL,
    PRIMARY KEY (sched_name, lock_name)
);

CREATE INDEX idx_qrtz_j_req_recovery ON business.qrtz_job_details (sched_name, requests_recovery);
CREATE INDEX idx_qrtz_j_grp ON business.qrtz_job_details (sched_name, job_group);
CREATE INDEX idx_qrtz_t_j ON business.qrtz_triggers (sched_name, job_name, job_group);
CREATE INDEX idx_qrtz_t_jg ON business.qrtz_triggers (sched_name, job_group);
CREATE INDEX idx_qrtz_t_c ON business.qrtz_triggers (sched_name, calendar_name);
CREATE INDEX idx_qrtz_t_g ON business.qrtz_triggers (sched_name, trigger_group);
CREATE INDEX idx_qrtz_t_state ON business.qrtz_triggers (sched_name, trigger_state);
CREATE INDEX idx_qrtz_t_n_state ON business.qrtz_triggers (sched_name, trigger_name, trigger_group, trigger_state);
CREATE INDEX idx_qrtz_t_next_fire_time ON business.qrtz_triggers (sched_name, next_fire_time);
CREATE INDEX idx_qrtz_t_nft_st ON business.qrtz_triggers (sched_name, trigger_state, next_fire_time);
CREATE INDEX idx_qrtz_ft_trig_inst_name ON business.qrtz_fired_triggers (sched_name, instance_name);
CREATE INDEX idx_qrtz_ft_inst_job_req_rcvry ON business.qrtz_fired_triggers (sched_name, instance_name, requests_recovery);
CREATE INDEX idx_qrtz_ft_j_g ON business.qrtz_fired_triggers (sched_name, job_name, job_group);
CREATE INDEX idx_qrtz_ft_jg ON business.qrtz_fired_triggers (sched_name, job_group);
CREATE INDEX idx_qrtz_ft_t_g ON business.qrtz_fired_triggers (sched_name, trigger_name, trigger_group);
CREATE INDEX idx_qrtz_ft_tg ON business.qrtz_fired_triggers (sched_name, trigger_group);
