CREATE TABLE business.conversation (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    title varchar(200) NOT NULL,
    selected_space_id bigint,
    selected_product_id bigint,
    summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    summary_version integer NOT NULL DEFAULT 1,
    version integer NOT NULL DEFAULT 1,
    next_message_sequence bigint NOT NULL DEFAULT 1,
    next_event_sequence bigint NOT NULL DEFAULT 1,
    last_message_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT uk_conversation_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_conversation_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_conversation_summary CHECK (jsonb_typeof(summary) = 'object'),
    CONSTRAINT ck_conversation_summary_version CHECK (summary_version > 0),
    CONSTRAINT ck_conversation_version CHECK (version > 0),
    CONSTRAINT ck_conversation_message_sequence CHECK (next_message_sequence > 0),
    CONSTRAINT ck_conversation_event_sequence CHECK (next_event_sequence > 0),
    CONSTRAINT ck_conversation_product_requires_space
        CHECK (selected_product_id IS NULL OR selected_space_id IS NOT NULL)
);

CREATE INDEX idx_conversation_user_last_message
    ON business.conversation (user_id, last_message_at DESC NULLS LAST, created_at DESC, id DESC);

CREATE TABLE business.conversation_message (
    id bigint PRIMARY KEY,
    conversation_id bigint NOT NULL,
    user_id bigint NOT NULL,
    role varchar(16) NOT NULL,
    content_type varchar(32) NOT NULL,
    content jsonb NOT NULL,
    reply_to_message_id bigint,
    agent_run_id uuid,
    client_message_id uuid,
    idempotency_key uuid,
    request_fingerprint char(64),
    sequence_no bigint NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'COMPLETED',
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_conversation_message_conversation FOREIGN KEY (conversation_id, user_id)
        REFERENCES business.conversation (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_message_reply FOREIGN KEY (
        reply_to_message_id, user_id, conversation_id
    ) REFERENCES business.conversation_message (id, user_id, conversation_id)
        ON DELETE SET NULL (reply_to_message_id),
    CONSTRAINT uk_conversation_message_owner UNIQUE (id, user_id, conversation_id),
    CONSTRAINT uk_conversation_message_sequence UNIQUE (conversation_id, sequence_no),
    CONSTRAINT uk_conversation_message_client UNIQUE (user_id, conversation_id, client_message_id),
    CONSTRAINT uk_conversation_message_idempotency UNIQUE (user_id, conversation_id, idempotency_key),
    CONSTRAINT ck_conversation_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM_EVENT')),
    CONSTRAINT ck_conversation_message_content_type CHECK (
        content_type IN ('TEXT', 'INTERACTION', 'SYSTEM_EVENT')
    ),
    CONSTRAINT ck_conversation_message_content CHECK (
        jsonb_typeof(content) = 'object' AND octet_length(content::text) <= 65536
    ),
    CONSTRAINT ck_conversation_message_sequence_no CHECK (sequence_no > 0),
    CONSTRAINT ck_conversation_message_status CHECK (
        status IN ('STREAMING', 'COMPLETED', 'FAILED', 'STOPPED')
    ),
    CONSTRAINT ck_conversation_message_idempotency_fields CHECK (
        (role = 'USER' AND client_message_id IS NOT NULL AND idempotency_key IS NOT NULL
            AND request_fingerprint ~ '^[0-9a-f]{64}$')
        OR
        (role <> 'USER' AND client_message_id IS NULL AND idempotency_key IS NULL
            AND request_fingerprint IS NULL)
    )
);

CREATE INDEX idx_conversation_message_history
    ON business.conversation_message (user_id, conversation_id, sequence_no ASC);

CREATE TABLE business.agent_run (
    id uuid PRIMARY KEY,
    conversation_id bigint NOT NULL,
    user_id bigint NOT NULL,
    request_message_id bigint NOT NULL,
    response_message_id bigint,
    selected_space_id bigint,
    selected_product_id bigint,
    attachment_asset_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(24) NOT NULL,
    context_version integer NOT NULL DEFAULT 1,
    context_snapshot jsonb NOT NULL,
    context_ref_hash char(64) NOT NULL,
    context_expires_at timestamptz NOT NULL,
    python_events_url varchar(500),
    last_python_sequence bigint NOT NULL DEFAULT 0,
    usage jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code varchar(64),
    stop_requested_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_agent_run_conversation FOREIGN KEY (conversation_id, user_id)
        REFERENCES business.conversation (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_run_request_message FOREIGN KEY (request_message_id, user_id, conversation_id)
        REFERENCES business.conversation_message (id, user_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_run_response_message FOREIGN KEY (
        response_message_id, user_id, conversation_id
    ) REFERENCES business.conversation_message (id, user_id, conversation_id)
        ON DELETE SET NULL (response_message_id),
    CONSTRAINT uk_agent_run_owner UNIQUE (id, user_id, conversation_id),
    CONSTRAINT uk_agent_run_request_message UNIQUE (request_message_id),
    CONSTRAINT ck_agent_run_product_requires_space
        CHECK (selected_product_id IS NULL OR selected_space_id IS NOT NULL),
    CONSTRAINT ck_agent_run_attachments CHECK (jsonb_typeof(attachment_asset_ids) = 'array'),
    CONSTRAINT ck_agent_run_usage CHECK (jsonb_typeof(usage) = 'object'),
    CONSTRAINT ck_agent_run_status CHECK (
        status IN ('QUEUED', 'STARTING', 'RUNNING', 'COMPLETED', 'FAILED', 'STOP_REQUESTED', 'STOPPED')
    ),
    CONSTRAINT ck_agent_run_context_version CHECK (context_version = 1),
    CONSTRAINT ck_agent_run_context_snapshot CHECK (
        jsonb_typeof(context_snapshot) = 'object'
        AND octet_length(context_snapshot::text) <= 262144
    ),
    CONSTRAINT ck_agent_run_context_ref_hash CHECK (context_ref_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_run_python_sequence CHECK (last_python_sequence >= 0)
);

CREATE INDEX idx_agent_run_conversation_created
    ON business.agent_run (user_id, conversation_id, created_at DESC);

CREATE UNIQUE INDEX uk_agent_run_active_conversation
    ON business.agent_run (conversation_id)
    WHERE status IN ('QUEUED', 'STARTING', 'RUNNING', 'STOP_REQUESTED');

CREATE TABLE business.conversation_event (
    id bigint PRIMARY KEY,
    conversation_id bigint NOT NULL,
    user_id bigint NOT NULL,
    agent_run_id uuid,
    message_id bigint,
    source_event_id uuid,
    event_type varchar(64) NOT NULL,
    sequence_no bigint NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_conversation_event_conversation FOREIGN KEY (conversation_id, user_id)
        REFERENCES business.conversation (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_event_run FOREIGN KEY (
        agent_run_id, user_id, conversation_id
    ) REFERENCES business.agent_run (id, user_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_event_message FOREIGN KEY (
        message_id, user_id, conversation_id
    ) REFERENCES business.conversation_message (id, user_id, conversation_id)
        ON DELETE SET NULL (message_id),
    CONSTRAINT uk_conversation_event_sequence UNIQUE (conversation_id, sequence_no),
    CONSTRAINT uk_conversation_event_source UNIQUE (agent_run_id, source_event_id),
    CONSTRAINT ck_conversation_event_type CHECK (
        event_type IN ('message.start', 'message.delta', 'message.completed',
            'interaction.required', 'heartbeat')
    ),
    CONSTRAINT ck_conversation_event_sequence_no CHECK (sequence_no > 0),
    CONSTRAINT ck_conversation_event_payload CHECK (
        jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 65536
    )
);

CREATE INDEX idx_conversation_event_replay
    ON business.conversation_event (user_id, conversation_id, sequence_no ASC);

CREATE UNIQUE INDEX uk_conversation_event_terminal_run
    ON business.conversation_event (agent_run_id)
    WHERE event_type = 'message.completed';
