ALTER TABLE business.workflow_plan
    ADD COLUMN start_idempotency_key uuid,
    ADD COLUMN start_request_hash char(64),
    ADD COLUMN execution_started_at timestamptz,
    ADD COLUMN finished_at timestamptz,
    ADD COLUMN last_error_code varchar(64),
    DROP CONSTRAINT ck_workflow_plan_status,
    DROP CONSTRAINT ck_workflow_plan_lifecycle;

ALTER TABLE business.workflow_plan
    ADD CONSTRAINT uk_workflow_plan_start_idempotency UNIQUE (user_id, start_idempotency_key),
    ADD CONSTRAINT ck_workflow_plan_start_hash CHECK (
        start_request_hash IS NULL OR start_request_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_workflow_plan_start_pair CHECK (
        (start_idempotency_key IS NULL) = (start_request_hash IS NULL)
    ),
    ADD CONSTRAINT ck_workflow_plan_last_error CHECK (
        last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    ADD CONSTRAINT ck_workflow_plan_status CHECK (
        status IN ('DRAFT', 'WAITING_CONFIRMATION', 'CONFIRMED', 'QUEUED', 'RUNNING',
            'WAITING_INPUT', 'SUCCEEDED', 'FAILED', 'RECONFIRMATION_REQUIRED', 'ABANDONED')
    ),
    ADD CONSTRAINT ck_workflow_plan_lifecycle CHECK (
        (status IN ('DRAFT', 'WAITING_CONFIRMATION', 'CONFIRMED')
            AND abandoned_at IS NULL AND execution_started_at IS NULL AND finished_at IS NULL)
        OR (status = 'ABANDONED' AND abandoned_at IS NOT NULL
            AND execution_started_at IS NULL AND finished_at IS NULL)
        OR (status IN ('QUEUED', 'RUNNING', 'WAITING_INPUT', 'RECONFIRMATION_REQUIRED')
            AND abandoned_at IS NULL AND execution_started_at IS NOT NULL AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND abandoned_at IS NULL
            AND execution_started_at IS NOT NULL AND finished_at IS NOT NULL)
    );

ALTER TABLE business.workflow_plan_step
    ADD COLUMN task_id bigint,
    ADD COLUMN attempt integer NOT NULL DEFAULT 0,
    ADD COLUMN last_error_code varchar(64),
    ADD COLUMN started_at timestamptz,
    ADD COLUMN finished_at timestamptz,
    DROP CONSTRAINT ck_workflow_step_status;

ALTER TABLE business.workflow_plan_step
    ADD CONSTRAINT uk_workflow_step_owner UNIQUE (id, plan_id, user_id, conversation_id),
    ADD CONSTRAINT fk_workflow_step_task FOREIGN KEY (task_id, user_id)
        REFERENCES business.business_task (id, user_id),
    ADD CONSTRAINT ck_workflow_step_status CHECK (
        status IN ('PENDING', 'READY', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'SKIPPED')
    ),
    ADD CONSTRAINT ck_workflow_step_attempt CHECK (attempt >= 0),
    ADD CONSTRAINT ck_workflow_step_error CHECK (
        last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    ADD CONSTRAINT ck_workflow_step_lifecycle CHECK (
        (status IN ('PENDING', 'READY') AND started_at IS NULL AND finished_at IS NULL)
        OR (status IN ('RUNNING', 'WAITING') AND started_at IS NOT NULL AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED') AND finished_at IS NOT NULL)
    );

CREATE TABLE business.workflow_execution (
    id bigint PRIMARY KEY,
    plan_id bigint NOT NULL,
    user_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    plan_version integer NOT NULL,
    execution_no integer NOT NULL,
    claim_event_id bigint NOT NULL,
    status varchar(24) NOT NULL,
    consumer_name varchar(128) NOT NULL,
    worker_id varchar(128) NOT NULL,
    bundle_version varchar(128) NOT NULL,
    execution_token_hash char(64) NOT NULL,
    context_snapshot jsonb NOT NULL,
    lease_expires_at timestamptz NOT NULL,
    last_heartbeat_at timestamptz,
    replan_count integer NOT NULL DEFAULT 0,
    stable_error_code varchar(64),
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_workflow_execution_plan FOREIGN KEY (plan_id, user_id, conversation_id)
        REFERENCES business.workflow_plan (id, user_id, conversation_id),
    CONSTRAINT uk_workflow_execution_number UNIQUE (plan_id, execution_no),
    CONSTRAINT uk_workflow_execution_claim UNIQUE (plan_id, claim_event_id),
    CONSTRAINT uk_workflow_execution_owner UNIQUE (id, plan_id, user_id, conversation_id),
    CONSTRAINT ck_workflow_execution_numbers CHECK (
        plan_version > 0 AND execution_no > 0 AND claim_event_id > 0 AND replan_count BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_workflow_execution_status CHECK (
        status IN ('RUNNING', 'WAITING_INPUT', 'SUCCEEDED', 'FAILED', 'EXPIRED')
    ),
    CONSTRAINT ck_workflow_execution_token CHECK (execution_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workflow_execution_context CHECK (
        jsonb_typeof(context_snapshot) = 'object' AND octet_length(context_snapshot::text) <= 262144
    ),
    CONSTRAINT ck_workflow_execution_error CHECK (
        stable_error_code IS NULL OR stable_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    CONSTRAINT ck_workflow_execution_lifecycle CHECK (
        (status IN ('RUNNING', 'WAITING_INPUT') AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED', 'EXPIRED') AND finished_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_workflow_execution_active
    ON business.workflow_execution (plan_id)
    WHERE status IN ('RUNNING', 'WAITING_INPUT');
CREATE INDEX idx_workflow_execution_lease
    ON business.workflow_execution (lease_expires_at, id)
    WHERE status = 'RUNNING';

CREATE TABLE business.workflow_observation (
    id bigint PRIMARY KEY,
    plan_id bigint NOT NULL,
    step_id bigint NOT NULL,
    execution_id bigint NOT NULL,
    user_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    event_id uuid NOT NULL,
    tool_call_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    step_attempt integer NOT NULL,
    status varchar(16) NOT NULL,
    result_type varchar(64) NOT NULL,
    result_ref varchar(256),
    summary varchar(1000) NOT NULL,
    facts jsonb NOT NULL,
    stable_error_code varchar(64),
    retryable boolean NOT NULL,
    completed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_workflow_observation_step
        FOREIGN KEY (step_id, plan_id, user_id, conversation_id)
        REFERENCES business.workflow_plan_step (id, plan_id, user_id, conversation_id),
    CONSTRAINT fk_workflow_observation_execution
        FOREIGN KEY (execution_id, plan_id, user_id, conversation_id)
        REFERENCES business.workflow_execution (id, plan_id, user_id, conversation_id),
    CONSTRAINT uk_workflow_observation_event UNIQUE (plan_id, event_id),
    CONSTRAINT uk_workflow_observation_attempt UNIQUE (plan_id, step_id, step_attempt),
    CONSTRAINT uk_workflow_observation_tool_call UNIQUE (plan_id, tool_call_id),
    CONSTRAINT ck_workflow_observation_attempt CHECK (step_attempt > 0),
    CONSTRAINT ck_workflow_observation_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workflow_observation_status CHECK (
        status IN ('SUCCEEDED', 'FAILED', 'WAITING')
    ),
    CONSTRAINT ck_workflow_observation_result_type CHECK (
        result_type ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    CONSTRAINT ck_workflow_observation_summary CHECK (length(btrim(summary)) BETWEEN 1 AND 1000),
    CONSTRAINT ck_workflow_observation_facts CHECK (
        jsonb_typeof(facts) = 'object' AND octet_length(facts::text) <= 32768
    ),
    CONSTRAINT ck_workflow_observation_error CHECK (
        stable_error_code IS NULL OR stable_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    CONSTRAINT ck_workflow_observation_lifecycle CHECK (
        (status = 'SUCCEEDED' AND stable_error_code IS NULL)
        OR (status IN ('FAILED', 'WAITING') AND stable_error_code IS NOT NULL)
    )
);

CREATE INDEX idx_workflow_observation_timeline
    ON business.workflow_observation (plan_id, step_id, step_attempt, id);

CREATE TABLE business.workflow_point_reservation (
    id bigint PRIMARY KEY,
    plan_id bigint NOT NULL,
    user_id bigint NOT NULL,
    authorized_points integer NOT NULL,
    frozen_points integer NOT NULL,
    allocated_points integer NOT NULL DEFAULT 0,
    settled_points integer NOT NULL DEFAULT 0,
    released_points integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_workflow_reservation_plan FOREIGN KEY (plan_id, user_id)
        REFERENCES business.workflow_plan (id, user_id),
    CONSTRAINT uk_workflow_reservation_plan UNIQUE (plan_id),
    CONSTRAINT ck_workflow_reservation_amounts CHECK (
        authorized_points > 0 AND frozen_points >= 0 AND allocated_points >= 0
        AND settled_points >= 0 AND released_points >= 0
        AND frozen_points + allocated_points + settled_points + released_points = authorized_points
    ),
    CONSTRAINT ck_workflow_reservation_status CHECK (
        status IN ('FROZEN', 'PARTIAL', 'SETTLED', 'RELEASED')
    )
);

ALTER TABLE business.point_ledger
    DROP CONSTRAINT ck_point_ledger_entry_type,
    DROP CONSTRAINT ck_point_ledger_business_type;

ALTER TABLE business.point_ledger
    ADD CONSTRAINT ck_point_ledger_entry_type CHECK (
        entry_type IN ('TASK_FREEZE', 'TASK_SETTLE', 'TASK_RELEASE',
            'WORKFLOW_FREEZE', 'WORKFLOW_SETTLE', 'WORKFLOW_RELEASE')
    ),
    ADD CONSTRAINT ck_point_ledger_business_type CHECK (
        business_type IN ('TASK', 'WORKFLOW')
    );

ALTER TABLE business.workflow_event
    ADD COLUMN source_event_id uuid,
    ADD COLUMN request_hash char(64),
    DROP CONSTRAINT ck_workflow_event_type,
    DROP CONSTRAINT ck_workflow_event_status;

ALTER TABLE business.workflow_event
    ADD CONSTRAINT uk_workflow_event_source UNIQUE (plan_id, source_event_id),
    ADD CONSTRAINT ck_workflow_event_request_hash CHECK (
        request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_workflow_event_type CHECK (
        event_type IN ('PLAN_PROPOSED', 'PLAN_WAITING_CONFIRMATION', 'PLAN_CONFIRMED',
            'PLAN_QUEUED', 'PLAN_STARTED', 'STEP_STATE_CHANGED', 'OBSERVATION_RECORDED',
            'PLAN_REPLANNED', 'PLAN_WAITING_INPUT', 'PLAN_RETRY_QUEUED',
            'PLAN_SUCCEEDED', 'PLAN_FAILED', 'PLAN_ABANDONED')
    ),
    ADD CONSTRAINT ck_workflow_event_status CHECK (
        (from_status IS NULL OR from_status IN ('DRAFT', 'WAITING_CONFIRMATION', 'CONFIRMED',
            'QUEUED', 'RUNNING', 'WAITING_INPUT', 'SUCCEEDED', 'FAILED',
            'RECONFIRMATION_REQUIRED', 'ABANDONED'))
        AND to_status IN ('DRAFT', 'WAITING_CONFIRMATION', 'CONFIRMED', 'QUEUED', 'RUNNING',
            'WAITING_INPUT', 'SUCCEEDED', 'FAILED', 'RECONFIRMATION_REQUIRED', 'ABANDONED')
    );
