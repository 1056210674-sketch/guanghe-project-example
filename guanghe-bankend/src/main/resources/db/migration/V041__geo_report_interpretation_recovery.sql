-- Recovery reuses saved observations. It never reopens a paid task or touches its ledger.
CREATE TABLE business.geo_report_interpretation_work (
    id bigint PRIMARY KEY,
    report_id bigint NOT NULL,
    user_id bigint NOT NULL,
    input_hash char(64) NOT NULL,
    status varchar(16) NOT NULL,
    attempts integer NOT NULL,
    claim_token uuid,
    lease_expires_at timestamptz,
    deadline_at timestamptz,
    requested_at timestamptz NOT NULL,
    available_at timestamptz NOT NULL,
    capacity_wait_until timestamptz NOT NULL,
    started_at timestamptz,
    finished_at timestamptz,
    last_error_code varchar(64),
    traceparent varchar(55),
    usage jsonb,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_interpretation_report_owner FOREIGN KEY (report_id, user_id)
        REFERENCES business.geo_report (id, user_id),
    CONSTRAINT uk_geo_interpretation_report UNIQUE (report_id),
    CONSTRAINT ck_geo_interpretation_hash CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_geo_interpretation_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_geo_interpretation_attempts CHECK (attempts BETWEEN 1 AND 2),
    CONSTRAINT ck_geo_interpretation_capacity_wait CHECK (capacity_wait_until > requested_at),
    CONSTRAINT ck_geo_interpretation_lease CHECK (
        (status='RUNNING' AND claim_token IS NOT NULL AND lease_expires_at IS NOT NULL
         AND deadline_at IS NOT NULL AND deadline_at < lease_expires_at AND started_at IS NOT NULL)
        OR (status<>'RUNNING' AND claim_token IS NULL AND lease_expires_at IS NULL AND deadline_at IS NULL)
    ),
    CONSTRAINT ck_geo_interpretation_finished CHECK (
        (status IN ('QUEUED','RUNNING') AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED','FAILED') AND finished_at IS NOT NULL)
    ),
    CONSTRAINT ck_geo_interpretation_usage CHECK (
        usage IS NULL OR (jsonb_typeof(usage)='object' AND octet_length(usage::text)<=4096)
    ),
    CONSTRAINT ck_geo_interpretation_trace CHECK (
        traceparent IS NULL OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$'
    )
);

-- Admission serializes on the existing account row; this also enforces the invariant across JVMs.
CREATE UNIQUE INDEX uk_geo_interpretation_active_user
    ON business.geo_report_interpretation_work(user_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX idx_geo_interpretation_queue
    ON business.geo_report_interpretation_work(available_at,id) WHERE status='QUEUED';
CREATE INDEX idx_geo_interpretation_expired
    ON business.geo_report_interpretation_work(lease_expires_at,id) WHERE status='RUNNING';
