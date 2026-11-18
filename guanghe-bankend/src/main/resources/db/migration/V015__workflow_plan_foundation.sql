ALTER TABLE business.task_draft
    DROP CONSTRAINT ck_task_draft_source_binding,
    DROP CONSTRAINT ck_task_draft_media_conversation;

ALTER TABLE business.task_draft
    ADD CONSTRAINT uk_task_draft_owner_conversation UNIQUE (id, user_id, conversation_id),
    ADD CONSTRAINT ck_task_draft_source_binding CHECK (
        (draft_source = 'CHAT' AND conversation_id IS NOT NULL AND agent_run_id IS NOT NULL
            AND operation_scope IS NULL AND idempotency_key IS NULL AND request_hash IS NULL)
        OR
        (draft_source = 'FORM' AND agent_run_id IS NULL
            AND ((task_type = 'MEDIA' AND conversation_id IS NULL
                    AND operation_scope = 'MEDIA_QUOTE_CREATE')
                OR (task_type <> 'MEDIA' AND conversation_id IS NOT NULL
                    AND operation_scope IN ('TASK_DRAFT_CREATE', 'TASK_DRAFT_REVISION')))
            AND idempotency_key IS NOT NULL AND request_hash ~ '^[0-9a-f]{64}$')
        OR
        (draft_source = 'PLAN' AND agent_run_id IS NULL
            AND ((conversation_id IS NULL AND task_type = 'GEO'
                    AND operation_scope = 'GEO_PLAN_DRAFT_CREATE')
                OR (conversation_id IS NOT NULL AND task_type IN ('IMAGE', 'VIDEO', 'GEO', 'ARTICLE')
                    AND operation_scope = 'WORKFLOW_PLAN_DRAFT_CREATE'))
            AND idempotency_key IS NOT NULL AND request_hash ~ '^[0-9a-f]{64}$')
    ),
    ADD CONSTRAINT ck_task_draft_media_conversation CHECK (
        (task_type = 'MEDIA' AND draft_source = 'FORM' AND conversation_id IS NULL)
        OR (task_type <> 'MEDIA' AND (
            (draft_source = 'PLAN')
            OR (draft_source IN ('CHAT', 'FORM') AND conversation_id IS NOT NULL)
        ))
    );

CREATE TABLE business.workflow_plan (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    source_run_id uuid NOT NULL,
    space_id bigint NOT NULL,
    product_id bigint,
    proposal_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    goal_summary varchar(500) NOT NULL,
    status varchar(32) NOT NULL,
    version integer NOT NULL,
    step_count integer NOT NULL,
    total_quoted_points integer NOT NULL,
    max_steps integer NOT NULL,
    max_replans integer NOT NULL,
    max_model_calls integer NOT NULL,
    max_tool_calls integer NOT NULL,
    max_provider_calls integer NOT NULL,
    max_point_cost integer NOT NULL,
    deadline_at timestamptz NOT NULL,
    confirmation_expires_at timestamptz NOT NULL,
    abandoned_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_workflow_plan_user FOREIGN KEY (user_id)
        REFERENCES business.account (id),
    CONSTRAINT fk_workflow_plan_conversation FOREIGN KEY (conversation_id, user_id)
        REFERENCES business.conversation (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_plan_run FOREIGN KEY (source_run_id, user_id, conversation_id)
        REFERENCES business.agent_run (id, user_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_plan_space FOREIGN KEY (space_id, user_id)
        REFERENCES business.business_space (id, user_id),
    CONSTRAINT fk_workflow_plan_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id),
    CONSTRAINT uk_workflow_plan_owner UNIQUE (id, user_id),
    CONSTRAINT uk_workflow_plan_conversation_owner UNIQUE (id, user_id, conversation_id),
    CONSTRAINT uk_workflow_plan_proposal UNIQUE (user_id, proposal_id),
    CONSTRAINT uk_workflow_plan_source_run UNIQUE (source_run_id),
    CONSTRAINT ck_workflow_plan_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workflow_plan_goal CHECK (
        length(btrim(goal_summary)) BETWEEN 1 AND 500
    ),
    CONSTRAINT ck_workflow_plan_status CHECK (
        status IN ('DRAFT', 'WAITING_CONFIRMATION', 'ABANDONED')
    ),
    CONSTRAINT ck_workflow_plan_version CHECK (version > 0),
    CONSTRAINT ck_workflow_plan_steps CHECK (
        step_count BETWEEN 2 AND 12 AND max_steps BETWEEN step_count AND 12
    ),
    CONSTRAINT ck_workflow_plan_budget CHECK (
        max_replans BETWEEN 0 AND 2
        AND max_model_calls BETWEEN 1 AND 32
        AND max_tool_calls BETWEEN step_count AND 32
        AND max_provider_calls BETWEEN 0 AND 12
        AND max_point_cost > 0
        AND total_quoted_points > 0
        AND total_quoted_points <= max_point_cost
    ),
    CONSTRAINT ck_workflow_plan_deadline CHECK (deadline_at > created_at),
    CONSTRAINT ck_workflow_plan_confirmation_expiry CHECK (
        confirmation_expires_at > created_at
        AND confirmation_expires_at <= deadline_at
    ),
    CONSTRAINT ck_workflow_plan_lifecycle CHECK (
        (status IN ('DRAFT', 'WAITING_CONFIRMATION') AND abandoned_at IS NULL)
        OR (status = 'ABANDONED' AND abandoned_at IS NOT NULL)
    )
);

CREATE INDEX idx_workflow_plan_user_created
    ON business.workflow_plan (user_id, created_at DESC, id DESC);
CREATE INDEX idx_workflow_plan_user_status_created
    ON business.workflow_plan (user_id, status, created_at DESC, id DESC);

CREATE TABLE business.workflow_plan_step (
    id bigint PRIMARY KEY,
    plan_id bigint NOT NULL,
    user_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    step_key varchar(64) NOT NULL,
    ordinal integer NOT NULL,
    step_type varchar(16) NOT NULL,
    tool_name varchar(128) NOT NULL,
    tool_version varchar(16) NOT NULL,
    risk_level varchar(16) NOT NULL,
    depends_on jsonb NOT NULL,
    success_criteria jsonb NOT NULL,
    input_refs jsonb NOT NULL,
    output_ref varchar(128),
    task_draft_id bigint,
    task_type varchar(32),
    pricing_version_id bigint,
    quoted_points integer NOT NULL,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_workflow_step_plan FOREIGN KEY (plan_id, user_id, conversation_id)
        REFERENCES business.workflow_plan (id, user_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_step_draft FOREIGN KEY (task_draft_id, user_id, conversation_id)
        REFERENCES business.task_draft (id, user_id, conversation_id),
    CONSTRAINT uk_workflow_step_key UNIQUE (plan_id, step_key),
    CONSTRAINT uk_workflow_step_ordinal UNIQUE (plan_id, ordinal),
    CONSTRAINT uk_workflow_step_draft UNIQUE (task_draft_id),
    CONSTRAINT ck_workflow_step_key CHECK (step_key ~ '^step-[1-9][0-9]?$'),
    CONSTRAINT ck_workflow_step_ordinal CHECK (ordinal BETWEEN 1 AND 12),
    CONSTRAINT ck_workflow_step_type CHECK (
        step_type IN ('QUERY', 'ANALYZE', 'PREPARE', 'SYNTHESIZE')
    ),
    CONSTRAINT ck_workflow_step_tool CHECK (
        tool_name ~ '^[a-z][a-z0-9_.-]{0,127}$' AND tool_version = '1'
    ),
    CONSTRAINT ck_workflow_step_risk CHECK (
        risk_level IN ('QUERY', 'ANALYZE', 'PREPARE')
    ),
    CONSTRAINT ck_workflow_step_depends CHECK (
        jsonb_typeof(depends_on) = 'array' AND octet_length(depends_on::text) <= 2048
    ),
    CONSTRAINT ck_workflow_step_success CHECK (
        jsonb_typeof(success_criteria) = 'array' AND octet_length(success_criteria::text) <= 4096
    ),
    CONSTRAINT ck_workflow_step_inputs CHECK (
        jsonb_typeof(input_refs) = 'array' AND octet_length(input_refs::text) <= 4096
    ),
    CONSTRAINT ck_workflow_step_output CHECK (
        output_ref IS NULL OR output_ref ~ '^(observation|draft):[A-Za-z0-9._-]{1,100}$'
    ),
    CONSTRAINT ck_workflow_step_status CHECK (status = 'PENDING'),
    CONSTRAINT ck_workflow_step_prepare CHECK (
        (step_type = 'PREPARE' AND risk_level = 'PREPARE'
            AND tool_name = 'task_draft.propose' AND task_draft_id IS NOT NULL
            AND task_type IN ('IMAGE', 'VIDEO', 'GEO', 'ARTICLE')
            AND pricing_version_id IS NOT NULL AND quoted_points > 0)
        OR (step_type <> 'PREPARE' AND task_draft_id IS NULL AND task_type IS NULL
            AND pricing_version_id IS NULL AND quoted_points = 0)
    )
);

CREATE TABLE business.workflow_confirmation (
    id bigint PRIMARY KEY,
    plan_id bigint NOT NULL,
    user_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    plan_version integer NOT NULL,
    token_hash char(64) NOT NULL,
    authorized_max_points integer NOT NULL,
    status varchar(16) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    invalidated_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_workflow_confirmation_plan FOREIGN KEY (plan_id, user_id, conversation_id)
        REFERENCES business.workflow_plan (id, user_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT uk_workflow_confirmation_plan_version UNIQUE (plan_id, plan_version),
    CONSTRAINT uk_workflow_confirmation_token UNIQUE (token_hash),
    CONSTRAINT ck_workflow_confirmation_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workflow_confirmation_points CHECK (authorized_max_points > 0),
    CONSTRAINT ck_workflow_confirmation_status CHECK (
        status IN ('AVAILABLE', 'CONSUMED', 'EXPIRED', 'INVALIDATED')
    ),
    CONSTRAINT ck_workflow_confirmation_lifecycle CHECK (
        (status = 'AVAILABLE' AND consumed_at IS NULL AND invalidated_at IS NULL)
        OR (status = 'CONSUMED' AND consumed_at IS NOT NULL AND invalidated_at IS NULL)
        OR (status IN ('EXPIRED', 'INVALIDATED') AND consumed_at IS NULL AND invalidated_at IS NOT NULL)
    )
);

CREATE TABLE business.workflow_event (
    id bigint PRIMARY KEY,
    plan_id bigint NOT NULL,
    user_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    sequence_no bigint NOT NULL,
    event_type varchar(64) NOT NULL,
    from_status varchar(32),
    to_status varchar(32) NOT NULL,
    stable_error_code varchar(64),
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_workflow_event_plan FOREIGN KEY (plan_id, user_id, conversation_id)
        REFERENCES business.workflow_plan (id, user_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT uk_workflow_event_sequence UNIQUE (plan_id, sequence_no),
    CONSTRAINT ck_workflow_event_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_workflow_event_type CHECK (
        event_type IN ('PLAN_PROPOSED', 'PLAN_WAITING_CONFIRMATION', 'PLAN_ABANDONED')
    ),
    CONSTRAINT ck_workflow_event_status CHECK (
        (from_status IS NULL OR from_status IN ('DRAFT', 'WAITING_CONFIRMATION', 'ABANDONED'))
        AND to_status IN ('DRAFT', 'WAITING_CONFIRMATION', 'ABANDONED')
    ),
    CONSTRAINT ck_workflow_event_error CHECK (
        stable_error_code IS NULL OR stable_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    )
);

CREATE INDEX idx_workflow_event_timeline
    ON business.workflow_event (plan_id, sequence_no);
