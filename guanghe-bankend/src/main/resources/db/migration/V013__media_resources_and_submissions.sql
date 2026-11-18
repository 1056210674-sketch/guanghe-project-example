ALTER TABLE business.task_draft
    ALTER COLUMN conversation_id DROP NOT NULL,
    DROP CONSTRAINT ck_task_draft_source_binding;

ALTER TABLE business.business_task
    ALTER COLUMN conversation_id DROP NOT NULL,
    DROP CONSTRAINT ck_business_task_origin;

ALTER TABLE business.task_draft
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
        (draft_source = 'PLAN' AND conversation_id IS NULL AND agent_run_id IS NULL
            AND operation_scope = 'GEO_PLAN_DRAFT_CREATE'
            AND idempotency_key IS NOT NULL AND request_hash ~ '^[0-9a-f]{64}$')
    );

ALTER TABLE business.business_task
    ADD CONSTRAINT ck_business_task_origin CHECK (
        (plan_id IS NULL AND scheduled_slot IS NULL AND draft_id IS NOT NULL
            AND ((task_type = 'MEDIA' AND conversation_id IS NULL)
                OR (task_type <> 'MEDIA' AND conversation_id IS NOT NULL)))
        OR
        (plan_id IS NOT NULL AND scheduled_slot IS NOT NULL AND conversation_id IS NULL AND draft_id IS NULL
            AND task_type = 'GEO')
    );

ALTER TABLE business.task_draft
    ADD CONSTRAINT ck_task_draft_media_conversation CHECK (
        (task_type = 'MEDIA' AND draft_source = 'FORM' AND conversation_id IS NULL)
        OR (task_type <> 'MEDIA' AND (
            (draft_source = 'PLAN' AND conversation_id IS NULL)
            OR (draft_source IN ('CHAT', 'FORM') AND conversation_id IS NOT NULL)
        ))
    );

ALTER TABLE business.business_task
    ADD CONSTRAINT ck_business_task_media_conversation CHECK (
        (task_type = 'MEDIA' AND plan_id IS NULL AND conversation_id IS NULL)
        OR task_type <> 'MEDIA'
    );

CREATE TABLE business.media_resource (
    id bigint PRIMARY KEY,
    provider_code varchar(64) NOT NULL,
    upstream_model_id varchar(32) NOT NULL,
    upstream_resource_id varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    status varchar(16) NOT NULL,
    resource_type varchar(64),
    channel_category varchar(120),
    region varchar(120),
    estimated_days integer,
    capabilities jsonb NOT NULL DEFAULT '{}'::jsonb,
    upstream_price numeric(12,4) NOT NULL,
    raw_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    last_seen_at timestamptz NOT NULL,
    synced_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1,
    CONSTRAINT uk_media_resource_upstream UNIQUE
        (provider_code, upstream_model_id, upstream_resource_id),
    CONSTRAINT uk_media_resource_owner UNIQUE (id, provider_code, upstream_model_id, upstream_resource_id),
    CONSTRAINT ck_media_resource_provider CHECK (provider_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_media_resource_upstream_ids CHECK (
        upstream_model_id ~ '^[A-Za-z0-9._:-]{1,32}$'
        AND upstream_resource_id ~ '^[A-Za-z0-9._:-]{1,64}$'
    ),
    CONSTRAINT ck_media_resource_name CHECK (length(btrim(name)) BETWEEN 1 AND 255),
    CONSTRAINT ck_media_resource_status CHECK (status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT ck_media_resource_price CHECK (upstream_price > 0),
    CONSTRAINT ck_media_resource_capabilities CHECK (
        jsonb_typeof(capabilities) = 'object' AND octet_length(capabilities::text) <= 16384
    ),
    CONSTRAINT ck_media_resource_raw CHECK (
        jsonb_typeof(raw_json) = 'object' AND octet_length(raw_json::text) <= 65536
    ),
    CONSTRAINT ck_media_resource_days CHECK (estimated_days IS NULL OR estimated_days BETWEEN 0 AND 365),
    CONSTRAINT ck_media_resource_version CHECK (version > 0)
);

CREATE INDEX idx_media_resource_search
    ON business.media_resource (status, upstream_model_id, upstream_price, id);
CREATE INDEX idx_media_resource_name
    ON business.media_resource (lower(name), id);

CREATE TABLE business.media_price_snapshot (
    id bigint PRIMARY KEY,
    media_resource_id bigint NOT NULL,
    provider_code varchar(64) NOT NULL,
    upstream_model_id varchar(32) NOT NULL,
    upstream_resource_id varchar(64) NOT NULL,
    upstream_price numeric(12,4) NOT NULL,
    markup_rate numeric(5,4) NOT NULL,
    sale_amount_cents integer NOT NULL,
    charged_points integer NOT NULL,
    quoted_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT fk_media_price_resource FOREIGN KEY
        (media_resource_id, provider_code, upstream_model_id, upstream_resource_id)
        REFERENCES business.media_resource
        (id, provider_code, upstream_model_id, upstream_resource_id),
    CONSTRAINT uk_media_price_owner UNIQUE (id, media_resource_id),
    CONSTRAINT ck_media_price_amounts CHECK (
        upstream_price > 0 AND markup_rate = 0.3500
        AND sale_amount_cents > 0 AND charged_points = sale_amount_cents
    ),
    CONSTRAINT ck_media_price_expiry CHECK (expires_at > quoted_at)
);

CREATE INDEX idx_media_price_latest
    ON business.media_price_snapshot (media_resource_id, quoted_at DESC, id DESC);

CREATE TABLE business.media_submission_batch (
    id bigint PRIMARY KEY,
    task_id bigint NOT NULL,
    draft_id bigint NOT NULL,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    product_id bigint,
    article_id bigint NOT NULL,
    article_snapshot_id bigint NOT NULL,
    status varchar(24) NOT NULL,
    quoted_points integer NOT NULL,
    settled_points integer NOT NULL DEFAULT 0,
    released_points integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    finished_at timestamptz,
    CONSTRAINT fk_media_batch_task FOREIGN KEY (task_id, user_id, space_id)
        REFERENCES business.business_task (id, user_id, space_id),
    CONSTRAINT fk_media_batch_draft FOREIGN KEY (draft_id, user_id)
        REFERENCES business.task_draft (id, user_id),
    CONSTRAINT fk_media_batch_article FOREIGN KEY (article_id, user_id, space_id)
        REFERENCES business.article (id, user_id, space_id),
    CONSTRAINT fk_media_batch_snapshot FOREIGN KEY (article_snapshot_id, user_id, article_id)
        REFERENCES business.article_submission_snapshot (id, user_id, article_id),
    CONSTRAINT fk_media_batch_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id),
    CONSTRAINT uk_media_batch_task UNIQUE (task_id),
    CONSTRAINT uk_media_batch_draft UNIQUE (draft_id),
    CONSTRAINT uk_media_batch_owner UNIQUE (id, user_id, task_id),
    CONSTRAINT ck_media_batch_status CHECK (
        status IN ('QUEUED','SUBMITTING','SUBMIT_UNKNOWN','ACCEPTED','PARTIAL',
                   'PUBLISHED','REJECTED','RECONCILING','FAILED')
    ),
    CONSTRAINT ck_media_batch_points CHECK (
        quoted_points > 0 AND settled_points >= 0 AND released_points >= 0
        AND settled_points + released_points <= quoted_points
        AND (
            (status IN ('PUBLISHED','PARTIAL','REJECTED','FAILED')
                AND settled_points + released_points = quoted_points AND finished_at IS NOT NULL)
            OR
            (status NOT IN ('PUBLISHED','PARTIAL','REJECTED','FAILED') AND finished_at IS NULL)
        )
    )
);

CREATE INDEX idx_media_batch_user_created
    ON business.media_submission_batch (user_id, created_at DESC, id DESC);
CREATE INDEX idx_media_batch_status
    ON business.media_submission_batch (status, updated_at, id)
    WHERE status IN ('QUEUED','SUBMITTING','SUBMIT_UNKNOWN','ACCEPTED','RECONCILING');

CREATE TABLE business.media_submission (
    id bigint PRIMARY KEY,
    batch_id bigint NOT NULL,
    task_id bigint NOT NULL,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    article_id bigint NOT NULL,
    article_snapshot_id bigint NOT NULL,
    media_resource_id bigint NOT NULL,
    price_snapshot_id bigint NOT NULL,
    provider_code varchar(64) NOT NULL,
    upstream_model_id varchar(32) NOT NULL,
    upstream_resource_id varchar(64) NOT NULL,
    media_name varchar(255) NOT NULL,
    upstream_price numeric(12,4) NOT NULL,
    markup_rate numeric(5,4) NOT NULL,
    sale_amount_cents integer NOT NULL,
    charged_points integer NOT NULL,
    remark varchar(1500),
    customer_order_id varchar(30) NOT NULL,
    upstream_order_id varchar(128),
    upstream_order_no varchar(128),
    upstream_status varchar(64),
    status varchar(24) NOT NULL,
    submission_state varchar(16) NOT NULL,
    published_url varchar(2048),
    stable_error_code varchar(64),
    submit_attempted_at timestamptz,
    next_poll_at timestamptz,
    published_at timestamptz,
    rejected_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_media_submission_batch FOREIGN KEY (batch_id, user_id, task_id)
        REFERENCES business.media_submission_batch (id, user_id, task_id),
    CONSTRAINT fk_media_submission_resource FOREIGN KEY
        (media_resource_id, provider_code, upstream_model_id, upstream_resource_id)
        REFERENCES business.media_resource
        (id, provider_code, upstream_model_id, upstream_resource_id),
    CONSTRAINT fk_media_submission_price FOREIGN KEY (price_snapshot_id, media_resource_id)
        REFERENCES business.media_price_snapshot (id, media_resource_id),
    CONSTRAINT fk_media_submission_snapshot FOREIGN KEY (article_snapshot_id, user_id, article_id)
        REFERENCES business.article_submission_snapshot (id, user_id, article_id),
    CONSTRAINT uk_media_submission_customer_order UNIQUE (customer_order_id),
    CONSTRAINT uk_media_submission_batch_resource UNIQUE (batch_id, media_resource_id),
    CONSTRAINT uk_media_submission_owner UNIQUE (id, user_id, batch_id),
    CONSTRAINT uk_media_submission_upstream_order UNIQUE
        (provider_code, upstream_model_id, upstream_order_id),
    CONSTRAINT ck_media_submission_customer_order CHECK (customer_order_id = 'ZXH' || id::text),
    CONSTRAINT ck_media_submission_price CHECK (
        upstream_price > 0 AND markup_rate = 0.3500
        AND sale_amount_cents > 0 AND charged_points = sale_amount_cents
    ),
    CONSTRAINT ck_media_submission_status CHECK (
        status IN ('QUEUED','SUBMITTING','SUBMIT_UNKNOWN','ACCEPTED','PUBLISHED',
                   'REJECTED','RECONCILING','FAILED')
    ),
    CONSTRAINT ck_media_submission_state CHECK (
        submission_state IN ('NOT_SUBMITTED','CONFIRMED','UNKNOWN')
    ),
    CONSTRAINT ck_media_submission_state_alignment CHECK (
        (status IN ('QUEUED','SUBMITTING','FAILED') AND submission_state = 'NOT_SUBMITTED')
        OR (status = 'SUBMIT_UNKNOWN' AND submission_state = 'UNKNOWN')
        OR (status IN ('ACCEPTED','PUBLISHED','REJECTED') AND submission_state = 'CONFIRMED')
        OR (status = 'RECONCILING' AND submission_state IN ('CONFIRMED','UNKNOWN'))
    ),
    CONSTRAINT ck_media_submission_error CHECK (
        stable_error_code IS NULL OR stable_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    CONSTRAINT ck_media_submission_url CHECK (
        published_url IS NULL OR (
            length(published_url) <= 2048
            AND published_url ~ '^https://[^[:space:]@?#]+$'
            AND published_url !~ '@'
        )
    ),
    CONSTRAINT ck_media_submission_terminal_fields CHECK (
        (status = 'PUBLISHED' AND published_url IS NOT NULL AND published_at IS NOT NULL
            AND stable_error_code IS NULL)
        OR (status IN ('REJECTED','FAILED') AND published_url IS NULL AND rejected_at IS NOT NULL
            AND stable_error_code IS NOT NULL)
        OR (status NOT IN ('PUBLISHED','REJECTED','FAILED') AND published_at IS NULL AND rejected_at IS NULL)
    )
);

CREATE INDEX idx_media_submission_user_created
    ON business.media_submission (user_id, created_at DESC, id DESC);
CREATE INDEX idx_media_submission_poll
    ON business.media_submission (next_poll_at, id)
    WHERE status IN ('SUBMIT_UNKNOWN','ACCEPTED','RECONCILING');

CREATE TABLE business.media_submission_event (
    id bigint PRIMARY KEY,
    submission_id bigint NOT NULL,
    batch_id bigint NOT NULL,
    user_id bigint NOT NULL,
    sequence_no bigint NOT NULL,
    source_event_id uuid,
    event_type varchar(64) NOT NULL,
    from_status varchar(24),
    to_status varchar(24) NOT NULL,
    source varchar(16) NOT NULL,
    stable_error_code varchar(64),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_media_event_submission FOREIGN KEY (submission_id, user_id, batch_id)
        REFERENCES business.media_submission (id, user_id, batch_id),
    CONSTRAINT uk_media_event_sequence UNIQUE (submission_id, sequence_no),
    CONSTRAINT uk_media_event_source UNIQUE (submission_id, source_event_id),
    CONSTRAINT ck_media_event_source CHECK (source IN ('JAVA','PROVIDER','SCHEDULER')),
    CONSTRAINT ck_media_event_details CHECK (
        jsonb_typeof(details) = 'object' AND octet_length(details::text) <= 16384
    ),
    CONSTRAINT ck_media_event_error CHECK (
        stable_error_code IS NULL OR stable_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    )
);

CREATE TABLE business.media_callback_event (
    id bigint PRIMARY KEY,
    provider_code varchar(64) NOT NULL,
    customer_order_id varchar(30),
    upstream_model_id varchar(32),
    upstream_resource_id varchar(64),
    event_type varchar(32) NOT NULL,
    payload_hash char(64) NOT NULL,
    status varchar(16) NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    stable_error_code varchar(64),
    received_at timestamptz NOT NULL,
    processed_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_media_callback_submission FOREIGN KEY (customer_order_id)
        REFERENCES business.media_submission (customer_order_id),
    CONSTRAINT ck_media_callback_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_media_callback_status CHECK (status IN ('RECEIVED','PROCESSED','FAILED')),
    CONSTRAINT ck_media_callback_type CHECK (
        (event_type IN ('PROGRESS','RECEIPT','REFUND') AND customer_order_id IS NOT NULL
            AND upstream_model_id IS NULL AND upstream_resource_id IS NULL)
        OR
        (event_type = 'RESOURCE_UPDATE' AND customer_order_id IS NULL
            AND upstream_model_id IS NOT NULL AND upstream_resource_id IS NOT NULL)
    ),
    CONSTRAINT ck_media_callback_attempts CHECK (attempts >= 0)
);

CREATE UNIQUE INDEX uk_media_callback_order_identity
    ON business.media_callback_event
        (provider_code, customer_order_id, event_type, payload_hash)
    WHERE event_type IN ('PROGRESS','RECEIPT','REFUND');
CREATE UNIQUE INDEX uk_media_callback_resource_identity
    ON business.media_callback_event
        (provider_code, upstream_model_id, upstream_resource_id, event_type, payload_hash)
    WHERE event_type = 'RESOURCE_UPDATE';

CREATE INDEX idx_media_callback_recovery
    ON business.media_callback_event (status, updated_at, id)
    WHERE status IN ('RECEIVED','FAILED');

CREATE TABLE business.media_provider_sync_state (
    provider_code varchar(64) NOT NULL,
    upstream_model_id varchar(32) NOT NULL,
    status varchar(16) NOT NULL,
    last_attempted_at timestamptz,
    last_succeeded_at timestamptz,
    last_error_code varchar(64),
    last_resource_count integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (provider_code, upstream_model_id),
    CONSTRAINT ck_media_sync_status CHECK (status IN ('IDLE','RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_media_sync_count CHECK (last_resource_count >= 0),
    CONSTRAINT ck_media_sync_version CHECK (version > 0),
    CONSTRAINT ck_media_sync_error CHECK (
        last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    )
);

CREATE INDEX idx_business_task_media_created
    ON business.business_task (user_id, created_at DESC, id DESC)
    WHERE task_type = 'MEDIA';
CREATE INDEX idx_task_draft_media_created
    ON business.task_draft (user_id, created_at DESC, id DESC)
    WHERE task_type = 'MEDIA';
