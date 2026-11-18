CREATE TABLE business.task_draft (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    agent_run_id uuid NOT NULL,
    previous_draft_id bigint,
    task_type varchar(32) NOT NULL,
    parameter_schema_version integer NOT NULL,
    space_id bigint NOT NULL,
    product_id bigint,
    parameters jsonb NOT NULL,
    input_snapshot jsonb NOT NULL,
    input_hash char(64) NOT NULL,
    pricing_version_id bigint NOT NULL,
    quoted_points integer NOT NULL,
    warnings jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(32) NOT NULL,
    expires_at timestamptz NOT NULL,
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_task_draft_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT fk_task_draft_conversation FOREIGN KEY (conversation_id, user_id)
        REFERENCES business.conversation (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_draft_run FOREIGN KEY (agent_run_id, user_id, conversation_id)
        REFERENCES business.agent_run (id, user_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_draft_space FOREIGN KEY (space_id, user_id)
        REFERENCES business.business_space (id, user_id),
    CONSTRAINT fk_task_draft_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id),
    CONSTRAINT uk_task_draft_owner UNIQUE (id, user_id),
    CONSTRAINT uk_task_draft_run UNIQUE (agent_run_id),
    CONSTRAINT ck_task_draft_type CHECK (task_type IN ('GEO', 'IMAGE', 'VIDEO', 'ARTICLE', 'MEDIA')),
    CONSTRAINT ck_task_draft_schema_version CHECK (parameter_schema_version > 0),
    CONSTRAINT ck_task_draft_parameters CHECK (
        jsonb_typeof(parameters) = 'object' AND octet_length(parameters::text) <= 32768
    ),
    CONSTRAINT ck_task_draft_snapshot CHECK (
        jsonb_typeof(input_snapshot) = 'object' AND octet_length(input_snapshot::text) <= 262144
    ),
    CONSTRAINT ck_task_draft_input_hash CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_task_draft_quote CHECK (quoted_points > 0),
    CONSTRAINT ck_task_draft_warnings CHECK (
        jsonb_typeof(warnings) = 'array' AND octet_length(warnings::text) <= 16384
    ),
    CONSTRAINT ck_task_draft_status CHECK (
        status IN ('EDITING', 'WAITING_CONFIRMATION', 'CONFIRMED', 'EXPIRED', 'ABANDONED')
    )
);

ALTER TABLE business.task_draft
    ADD CONSTRAINT fk_task_draft_previous FOREIGN KEY (previous_draft_id, user_id)
        REFERENCES business.task_draft (id, user_id);

CREATE INDEX idx_task_draft_user_status_created
    ON business.task_draft (user_id, status, created_at DESC);

CREATE TABLE business.task_confirmation (
    id bigint PRIMARY KEY,
    draft_id bigint NOT NULL,
    user_id bigint NOT NULL,
    token_hash char(64) NOT NULL,
    input_hash char(64) NOT NULL,
    pricing_version_id bigint NOT NULL,
    status varchar(16) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_task_confirmation_draft FOREIGN KEY (draft_id, user_id)
        REFERENCES business.task_draft (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uk_task_confirmation_draft UNIQUE (draft_id),
    CONSTRAINT uk_task_confirmation_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_task_confirmation_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_task_confirmation_input_hash CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_task_confirmation_status CHECK (
        status IN ('AVAILABLE', 'CONSUMED', 'EXPIRED', 'INVALIDATED')
    )
);

CREATE TABLE business.point_account (
    user_id bigint PRIMARY KEY,
    available_points integer NOT NULL DEFAULT 0,
    frozen_points integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_point_account_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT ck_point_account_available CHECK (available_points >= 0),
    CONSTRAINT ck_point_account_frozen CHECK (frozen_points >= 0),
    CONSTRAINT ck_point_account_version CHECK (version > 0)
);

CREATE TABLE business.business_task (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    product_id bigint,
    conversation_id bigint NOT NULL,
    draft_id bigint NOT NULL,
    task_type varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    stage_code varchar(64) NOT NULL,
    snapshot_schema_version integer NOT NULL,
    input_snapshot jsonb NOT NULL,
    input_hash char(64) NOT NULL,
    pricing_version_id bigint NOT NULL,
    quoted_points integer NOT NULL,
    settled_points integer NOT NULL DEFAULT 0,
    result_summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code varchar(64),
    operation_scope varchar(32) NOT NULL,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    queued_at timestamptz NOT NULL,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_business_task_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT fk_business_task_space FOREIGN KEY (space_id, user_id)
        REFERENCES business.business_space (id, user_id),
    CONSTRAINT fk_business_task_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id),
    CONSTRAINT fk_business_task_conversation FOREIGN KEY (conversation_id, user_id)
        REFERENCES business.conversation (id, user_id),
    CONSTRAINT fk_business_task_draft FOREIGN KEY (draft_id, user_id)
        REFERENCES business.task_draft (id, user_id),
    CONSTRAINT uk_business_task_user UNIQUE (id, user_id),
    CONSTRAINT uk_business_task_owner UNIQUE (id, user_id, conversation_id),
    CONSTRAINT uk_business_task_draft UNIQUE (draft_id),
    CONSTRAINT uk_business_task_idempotency UNIQUE (user_id, operation_scope, idempotency_key),
    CONSTRAINT ck_business_task_type CHECK (task_type IN ('GEO', 'IMAGE', 'VIDEO', 'ARTICLE', 'MEDIA')),
    CONSTRAINT ck_business_task_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'PARTIAL',
                   'FAILED', 'SKIPPED', 'SYSTEM_TERMINATED')
    ),
    CONSTRAINT ck_business_task_snapshot_version CHECK (snapshot_schema_version > 0),
    CONSTRAINT ck_business_task_snapshot CHECK (
        jsonb_typeof(input_snapshot) = 'object' AND octet_length(input_snapshot::text) <= 262144
    ),
    CONSTRAINT ck_business_task_input_hash CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_business_task_points CHECK (
        quoted_points > 0 AND settled_points >= 0 AND settled_points <= quoted_points
    ),
    CONSTRAINT ck_business_task_result CHECK (
        jsonb_typeof(result_summary) = 'object' AND octet_length(result_summary::text) <= 65536
    ),
    CONSTRAINT ck_business_task_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_business_task_error_code CHECK (
        error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    )
);

CREATE INDEX idx_business_task_user_status_created
    ON business.business_task (user_id, status, created_at DESC);
CREATE INDEX idx_business_task_watchdog
    ON business.business_task (status, updated_at)
    WHERE status IN ('RUNNING', 'RETRY_WAIT');

CREATE TABLE business.point_reservation (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    task_id bigint NOT NULL,
    quoted_points integer NOT NULL,
    frozen_points integer NOT NULL,
    settled_points integer NOT NULL DEFAULT 0,
    released_points integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_point_reservation_task FOREIGN KEY (task_id, user_id)
        REFERENCES business.business_task (id, user_id),
    CONSTRAINT uk_point_reservation_task UNIQUE (task_id),
    CONSTRAINT ck_point_reservation_amounts CHECK (
        quoted_points > 0 AND frozen_points >= 0 AND settled_points >= 0 AND released_points >= 0
        AND settled_points + released_points <= quoted_points
    ),
    CONSTRAINT ck_point_reservation_status CHECK (status IN ('FROZEN', 'SETTLED', 'RELEASED', 'PARTIAL'))
);

CREATE TABLE business.point_ledger (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    available_delta integer NOT NULL,
    frozen_delta integer NOT NULL,
    available_after integer NOT NULL,
    frozen_after integer NOT NULL,
    entry_type varchar(32) NOT NULL,
    business_type varchar(32) NOT NULL,
    business_id bigint NOT NULL,
    idempotency_key varchar(100) NOT NULL,
    operator_id bigint,
    reason varchar(500),
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_point_ledger_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT uk_point_ledger_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_point_ledger_balances CHECK (available_after >= 0 AND frozen_after >= 0),
    CONSTRAINT ck_point_ledger_entry_type CHECK (
        entry_type IN ('TASK_FREEZE', 'TASK_SETTLE', 'TASK_RELEASE')
    ),
    CONSTRAINT ck_point_ledger_business_type CHECK (business_type = 'TASK')
);

CREATE INDEX idx_point_ledger_user_created
    ON business.point_ledger (user_id, created_at DESC);

CREATE TABLE business.task_execution (
    id bigint PRIMARY KEY,
    task_id bigint NOT NULL,
    user_id bigint NOT NULL,
    execution_no integer NOT NULL,
    status varchar(24) NOT NULL,
    consumer_name varchar(128) NOT NULL,
    worker_id varchar(128) NOT NULL,
    bundle_version varchar(128) NOT NULL,
    execution_token_hash char(64) NOT NULL,
    lease_expires_at timestamptz NOT NULL,
    stage_code varchar(64) NOT NULL,
    provider_code varchar(64),
    provider_model varchar(128),
    provider_request_id uuid,
    provider_task_id varchar(256),
    submission_state varchar(16),
    latency_ms integer,
    usage jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code varchar(64),
    last_heartbeat_at timestamptz,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_task_execution_task FOREIGN KEY (task_id, user_id)
        REFERENCES business.business_task (id, user_id),
    CONSTRAINT uk_task_execution_number UNIQUE (task_id, execution_no),
    CONSTRAINT uk_task_execution_owner UNIQUE (id, user_id, task_id),
    CONSTRAINT ck_task_execution_no CHECK (execution_no > 0),
    CONSTRAINT ck_task_execution_status CHECK (
        status IN ('RUNNING', 'RESULT_READY', 'SUCCEEDED', 'FAILED', 'EXPIRED')
    ),
    CONSTRAINT ck_task_execution_token_hash CHECK (execution_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_task_execution_usage CHECK (
        jsonb_typeof(usage) = 'object' AND octet_length(usage::text) <= 32768
    ),
    CONSTRAINT ck_task_execution_submission_state CHECK (
        submission_state IS NULL OR submission_state IN ('NOT_SUBMITTED', 'CONFIRMED', 'UNKNOWN')
    ),
    CONSTRAINT ck_task_execution_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX idx_task_execution_lease
    ON business.task_execution (status, lease_expires_at)
    WHERE status IN ('RUNNING', 'RESULT_READY');

CREATE TABLE business.business_task_event (
    id bigint PRIMARY KEY,
    task_id bigint NOT NULL,
    user_id bigint NOT NULL,
    execution_id bigint,
    source_event_id uuid,
    request_hash char(64),
    from_status varchar(32),
    to_status varchar(32),
    event_type varchar(64) NOT NULL,
    source varchar(16) NOT NULL,
    error_code varchar(64),
    message varchar(500),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_business_task_event_task FOREIGN KEY (task_id, user_id)
        REFERENCES business.business_task (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_business_task_event_execution FOREIGN KEY (execution_id, user_id, task_id)
        REFERENCES business.task_execution (id, user_id, task_id),
    CONSTRAINT uk_business_task_event_source UNIQUE (task_id, source_event_id),
    CONSTRAINT ck_business_task_event_source CHECK (source IN ('JAVA', 'PYTHON', 'SCHEDULER', 'USER')),
    CONSTRAINT ck_business_task_event_request_hash CHECK (
        request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_business_task_event_details CHECK (
        jsonb_typeof(details) = 'object' AND octet_length(details::text) <= 32768
    )
);

CREATE INDEX idx_business_task_event_timeline
    ON business.business_task_event (user_id, task_id, created_at ASC, id ASC);

CREATE TABLE business.outbox_event (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    aggregate_type varchar(32) NOT NULL,
    aggregate_id bigint NOT NULL,
    schema_version integer NOT NULL,
    event_type varchar(64) NOT NULL,
    exchange_name varchar(128) NOT NULL,
    routing_key varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    traceparent varchar(55) NOT NULL,
    status varchar(16) NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL,
    locked_until timestamptz,
    published_at timestamptz,
    last_error_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_outbox_event_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT uk_outbox_task_event UNIQUE (aggregate_type, aggregate_id, event_type),
    CONSTRAINT ck_outbox_event_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_outbox_event_payload CHECK (
        jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 65536
    ),
    CONSTRAINT ck_outbox_event_traceparent CHECK (
        traceparent ~ '^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
    ),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_event_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_outbox_event_publish
    ON business.outbox_event (status, available_at, id)
    WHERE status IN ('PENDING', 'PUBLISHING');

CREATE TABLE business.inbox_event (
    id bigint PRIMARY KEY,
    consumer_name varchar(128) NOT NULL,
    event_id bigint NOT NULL,
    task_id bigint NOT NULL,
    user_id bigint NOT NULL,
    execution_id bigint,
    disposition varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_inbox_event_outbox FOREIGN KEY (event_id) REFERENCES business.outbox_event (id),
    CONSTRAINT fk_inbox_event_task FOREIGN KEY (task_id, user_id)
        REFERENCES business.business_task (id, user_id),
    CONSTRAINT fk_inbox_event_execution FOREIGN KEY (execution_id, user_id, task_id)
        REFERENCES business.task_execution (id, user_id, task_id),
    CONSTRAINT uk_inbox_consumer_event UNIQUE (consumer_name, event_id),
    CONSTRAINT ck_inbox_event_disposition CHECK (disposition IN ('CLAIMED', 'DUPLICATE', 'TERMINAL'))
);

ALTER TABLE business.conversation_event
    ADD COLUMN task_id bigint;

ALTER TABLE business.conversation_event
    ADD CONSTRAINT fk_conversation_event_task FOREIGN KEY (task_id, user_id, conversation_id)
        REFERENCES business.business_task (id, user_id, conversation_id) ON DELETE CASCADE;

ALTER TABLE business.conversation_event
    DROP CONSTRAINT ck_conversation_event_type;

ALTER TABLE business.conversation_event
    ADD CONSTRAINT ck_conversation_event_type CHECK (
        event_type IN ('message.start', 'message.delta', 'message.completed',
            'interaction.required', 'draft.created', 'draft.expired',
            'task.queued', 'task.progress', 'task.completed', 'task.failed', 'heartbeat')
    );
