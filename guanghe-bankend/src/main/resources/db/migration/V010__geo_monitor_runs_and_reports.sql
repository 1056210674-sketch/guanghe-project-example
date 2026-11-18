ALTER TABLE business.geo_topic
    ADD CONSTRAINT uk_geo_topic_id_user UNIQUE (id, user_id);

CREATE TABLE business.geo_run (
    id bigint PRIMARY KEY,
    task_id bigint NOT NULL,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    product_id bigint,
    topic_id bigint NOT NULL,
    status varchar(24) NOT NULL,
    sample_mode varchar(16) NOT NULL,
    expected_units integer NOT NULL,
    succeeded_units integer NOT NULL DEFAULT 0,
    failed_units integer NOT NULL DEFAULT 0,
    missing_units integer NOT NULL,
    context_snapshot jsonb NOT NULL,
    metric_version varchar(64) NOT NULL,
    method_version varchar(64) NOT NULL,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_run_task FOREIGN KEY (task_id, user_id, space_id)
        REFERENCES business.business_task (id, user_id, space_id),
    CONSTRAINT fk_geo_run_topic FOREIGN KEY (topic_id, user_id, space_id)
        REFERENCES business.geo_topic (id, user_id, space_id),
    CONSTRAINT fk_geo_run_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id),
    CONSTRAINT uk_geo_run_task UNIQUE (task_id),
    CONSTRAINT uk_geo_run_user UNIQUE (id, user_id),
    CONSTRAINT uk_geo_run_owner UNIQUE (id, user_id, task_id),
    CONSTRAINT ck_geo_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'SYSTEM_TERMINATED')
    ),
    CONSTRAINT ck_geo_run_sample_mode CHECK (sample_mode IN ('STANDARD', 'DEEP')),
    CONSTRAINT ck_geo_run_counts CHECK (
        expected_units BETWEEN 1 AND 210
        AND succeeded_units >= 0 AND failed_units >= 0 AND missing_units >= 0
        AND succeeded_units + failed_units + missing_units = expected_units
    ),
    CONSTRAINT ck_geo_run_snapshot CHECK (
        jsonb_typeof(context_snapshot) = 'object'
        AND octet_length(context_snapshot::text) <= 262144
    ),
    CONSTRAINT ck_geo_run_versions CHECK (
        metric_version ~ '^[A-Za-z0-9._-]{1,64}$'
        AND method_version ~ '^[A-Za-z0-9._-]{1,64}$'
    ),
    CONSTRAINT ck_geo_run_lifecycle CHECK (
        (status = 'QUEUED' AND started_at IS NULL AND finished_at IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'SYSTEM_TERMINATED')
            AND finished_at IS NOT NULL)
    )
);

CREATE INDEX idx_geo_run_user_created
    ON business.geo_run (user_id, created_at DESC, id DESC);
CREATE INDEX idx_geo_run_user_topic_created
    ON business.geo_run (user_id, topic_id, created_at DESC, id DESC);

CREATE TABLE business.geo_observation (
    id bigint PRIMARY KEY,
    run_id bigint NOT NULL,
    task_id bigint NOT NULL,
    user_id bigint NOT NULL,
    source_event_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    unit_key varchar(256) NOT NULL,
    unit_sequence integer NOT NULL,
    question_id bigint NOT NULL,
    question_text_snapshot varchar(1000) NOT NULL,
    platform_code varchar(16) NOT NULL,
    sample_no integer NOT NULL,
    status varchar(16) NOT NULL,
    answer_text text,
    stable_error_code varchar(128),
    provider varchar(64) NOT NULL,
    model varchar(128) NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz NOT NULL,
    latency_ms integer NOT NULL,
    usage jsonb NOT NULL,
    provider_call_count integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_observation_run FOREIGN KEY (run_id, user_id, task_id)
        REFERENCES business.geo_run (id, user_id, task_id),
    CONSTRAINT uk_geo_observation_event UNIQUE (run_id, source_event_id),
    CONSTRAINT uk_geo_observation_owner UNIQUE (id, run_id, user_id),
    CONSTRAINT uk_geo_observation_sequence UNIQUE (run_id, unit_sequence),
    CONSTRAINT uk_geo_observation_unit UNIQUE (run_id, question_id, platform_code, sample_no),
    CONSTRAINT uk_geo_observation_unit_key UNIQUE (run_id, unit_key),
    CONSTRAINT ck_geo_observation_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_geo_observation_unit_key CHECK (
        unit_key ~ '^[1-9][0-9]{0,18}:(DOUBAO|DEEPSEEK|QWEN|WENXIN|KIMI|YUANBAO|ZHIPU):[1-3]$'
    ),
    CONSTRAINT ck_geo_observation_sequence CHECK (unit_sequence BETWEEN 1 AND 210),
    CONSTRAINT ck_geo_observation_platform CHECK (
        platform_code IN ('DOUBAO', 'DEEPSEEK', 'QWEN', 'WENXIN', 'KIMI', 'YUANBAO', 'ZHIPU')
    ),
    CONSTRAINT ck_geo_observation_sample_no CHECK (sample_no BETWEEN 1 AND 3),
    CONSTRAINT ck_geo_observation_status CHECK (status IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_geo_observation_result CHECK (
        (status = 'SUCCEEDED' AND answer_text IS NOT NULL AND length(btrim(answer_text)) > 0
            AND octet_length(answer_text) <= 400000 AND stable_error_code IS NULL)
        OR (status = 'FAILED' AND answer_text IS NULL
            AND stable_error_code ~ '^[A-Z][A-Z0-9_]{0,127}$')
    ),
    CONSTRAINT ck_geo_observation_provider CHECK (provider ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_geo_observation_model CHECK (length(btrim(model)) BETWEEN 1 AND 128),
    CONSTRAINT ck_geo_observation_time CHECK (finished_at >= started_at),
    CONSTRAINT ck_geo_observation_latency CHECK (latency_ms BETWEEN 0 AND 3600000),
    CONSTRAINT ck_geo_observation_usage CHECK (
        jsonb_typeof(usage) = 'object' AND octet_length(usage::text) <= 4096
    ),
    CONSTRAINT ck_geo_observation_provider_calls CHECK (provider_call_count BETWEEN 0 AND 100)
);

CREATE INDEX idx_geo_observation_run_order
    ON business.geo_observation (run_id, unit_sequence);

CREATE TABLE business.geo_citation (
    id bigint PRIMARY KEY,
    observation_id bigint NOT NULL,
    run_id bigint NOT NULL,
    user_id bigint NOT NULL,
    sequence_no integer NOT NULL,
    title varchar(500),
    url varchar(2048) NOT NULL,
    domain varchar(253),
    snippet varchar(2000),
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_citation_observation FOREIGN KEY (observation_id, run_id, user_id)
        REFERENCES business.geo_observation (id, run_id, user_id),
    CONSTRAINT fk_geo_citation_run FOREIGN KEY (run_id, user_id)
        REFERENCES business.geo_run (id, user_id),
    CONSTRAINT uk_geo_citation_observation_sequence UNIQUE (observation_id, sequence_no),
    CONSTRAINT ck_geo_citation_sequence CHECK (sequence_no BETWEEN 1 AND 50),
    CONSTRAINT ck_geo_citation_url CHECK (
        url ~ '^https?://[^[:space:]]+$' AND url !~ '^https?://[^/]*@'
    ),
    CONSTRAINT ck_geo_citation_domain CHECK (
        domain IS NULL OR domain ~ '^[A-Za-z0-9.-]{1,253}$'
    )
);

CREATE INDEX idx_geo_citation_run_observation
    ON business.geo_citation (run_id, observation_id, sequence_no);

CREATE TABLE business.geo_report (
    id bigint PRIMARY KEY,
    run_id bigint NOT NULL,
    task_id bigint NOT NULL,
    user_id bigint NOT NULL,
    topic_id bigint NOT NULL,
    status varchar(16) NOT NULL,
    synthesis jsonb NOT NULL,
    synthesis_usage jsonb NOT NULL,
    summary varchar(4000) NOT NULL,
    method_version varchar(64) NOT NULL,
    sample_size integer NOT NULL,
    generated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_report_run FOREIGN KEY (run_id, user_id, task_id)
        REFERENCES business.geo_run (id, user_id, task_id),
    CONSTRAINT fk_geo_report_topic FOREIGN KEY (topic_id, user_id)
        REFERENCES business.geo_topic (id, user_id),
    CONSTRAINT uk_geo_report_run UNIQUE (run_id),
    CONSTRAINT uk_geo_report_owner UNIQUE (id, user_id),
    CONSTRAINT ck_geo_report_status CHECK (status IN ('SUCCEEDED', 'PARTIAL')),
    CONSTRAINT ck_geo_report_synthesis CHECK (
        jsonb_typeof(synthesis) = 'object' AND octet_length(synthesis::text) <= 131072
    ),
    CONSTRAINT ck_geo_report_usage CHECK (
        jsonb_typeof(synthesis_usage) = 'object' AND octet_length(synthesis_usage::text) <= 4096
    ),
    CONSTRAINT ck_geo_report_summary CHECK (length(btrim(summary)) BETWEEN 1 AND 4000),
    CONSTRAINT ck_geo_report_method_version CHECK (
        method_version ~ '^[A-Za-z0-9._-]{1,64}$'
    ),
    CONSTRAINT ck_geo_report_sample_size CHECK (sample_size BETWEEN 1 AND 210)
);

CREATE INDEX idx_geo_report_user_generated
    ON business.geo_report (user_id, generated_at DESC, id DESC);
