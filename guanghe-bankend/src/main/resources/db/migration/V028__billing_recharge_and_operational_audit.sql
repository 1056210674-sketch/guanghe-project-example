-- M8 Stage 1: recharge control plane, manual point adjustment and operational audit.
--
-- Scope notes:
--   * No payment provider is configured in this environment. These tables model the control
--     plane only; nothing here implies a real WeChat Pay order exists.
--   * Point balances stay in business.point_account / business.point_ledger. This migration only
--     widens the closed CHECK constraints on point_ledger so recharge and manual adjustment can
--     be recorded through the same immutable ledger. No second balance system is introduced.
--   * account_security_audit is deliberately left untouched. It stays scoped to account security
--     events; commercial and provider-originated events go to business.operational_audit_event
--     because that table cannot express a non-user actor (its actor/target columns are NOT NULL
--     foreign keys into business.account).

CREATE TABLE business.recharge_order (
    id bigint PRIMARY KEY,
    order_no varchar(40) NOT NULL,
    user_id bigint NOT NULL,
    status varchar(16) NOT NULL,
    provider varchar(32) NOT NULL,
    amount_cents integer NOT NULL,
    points integer NOT NULL,
    provider_order_no varchar(64),
    provider_prepay_id varchar(64),
    provider_code_url varchar(512),
    idempotency_key varchar(100) NOT NULL,
    request_hash char(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    paid_at timestamptz,
    closed_at timestamptz,
    failed_at timestamptz,
    failure_code varchar(64),
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_recharge_order_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT uk_recharge_order_no UNIQUE (order_no),
    CONSTRAINT uk_recharge_order_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_recharge_order_provider_order UNIQUE (provider, provider_order_no),
    CONSTRAINT ck_recharge_order_status CHECK (
        status IN ('CREATED', 'PAYING', 'SUCCEEDED', 'CLOSED', 'EXPIRED', 'FAILED')
    ),
    CONSTRAINT ck_recharge_order_amount CHECK (amount_cents > 0),
    CONSTRAINT ck_recharge_order_points CHECK (points > 0),
    CONSTRAINT ck_recharge_order_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_recharge_order_version CHECK (version > 0),
    CONSTRAINT ck_recharge_order_failure_code CHECK (
        failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    -- Terminal timestamps are derivable from status, so they are pinned to it. This makes an
    -- illegal order row unrepresentable rather than merely rejected by application code.
    CONSTRAINT ck_recharge_order_lifecycle CHECK (
        (paid_at IS NOT NULL) = (status = 'SUCCEEDED')
        AND (closed_at IS NOT NULL) = (status IN ('CLOSED', 'EXPIRED'))
        AND (failed_at IS NOT NULL) = (status = 'FAILED')
    ),
    -- Once the order is with the provider it must carry a provider reference. Orders that expired
    -- or were closed before the provider accepted them legitimately have none.
    CONSTRAINT ck_recharge_order_provider_refs CHECK (
        status NOT IN ('PAYING', 'SUCCEEDED')
        OR (provider_order_no IS NOT NULL)
    )
);

CREATE INDEX idx_recharge_order_user_created
    ON business.recharge_order (user_id, created_at DESC, id DESC);
CREATE INDEX idx_recharge_order_status_expiry
    ON business.recharge_order (expires_at)
    WHERE status IN ('CREATED', 'PAYING');

COMMENT ON TABLE business.recharge_order IS
    'Recharge order control plane. amount_cents is an integer count of fen. Rows are only created '
    'when a payment provider is configured; no provider credentials are stored here.';

CREATE TABLE business.recharge_order_event (
    id bigint PRIMARY KEY,
    order_id bigint NOT NULL,
    user_id bigint NOT NULL,
    source_event_id uuid NOT NULL,
    event_type varchar(32) NOT NULL,
    from_status varchar(16),
    to_status varchar(16) NOT NULL,
    source varchar(32) NOT NULL,
    provider_event_id varchar(128),
    amount_cents integer,
    request_hash char(64),
    error_code varchar(64),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_recharge_order_event_order FOREIGN KEY (order_id) REFERENCES business.recharge_order (id),
    CONSTRAINT fk_recharge_order_event_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    -- Idempotent ingestion: replaying the same source event can never append a second row.
    CONSTRAINT uk_recharge_order_event_source UNIQUE (order_id, source_event_id),
    CONSTRAINT ck_recharge_order_event_type CHECK (event_type ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    CONSTRAINT ck_recharge_order_event_source CHECK (
        source IN ('BROWSER', 'PROVIDER_CALLBACK', 'PROVIDER_QUERY', 'SYSTEM')
    ),
    CONSTRAINT ck_recharge_order_event_from_status CHECK (
        from_status IS NULL OR from_status IN ('CREATED', 'PAYING', 'SUCCEEDED', 'CLOSED', 'EXPIRED', 'FAILED')
    ),
    CONSTRAINT ck_recharge_order_event_to_status CHECK (
        to_status IN ('CREATED', 'PAYING', 'SUCCEEDED', 'CLOSED', 'EXPIRED', 'FAILED')
    ),
    CONSTRAINT ck_recharge_order_event_error CHECK (
        error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    CONSTRAINT ck_recharge_order_event_request_hash CHECK (
        request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$'
    ),
    -- Bounded payload: callbacks are reduced to a summary plus hash, never stored verbatim.
    CONSTRAINT ck_recharge_order_event_details CHECK (
        jsonb_typeof(details) = 'object' AND octet_length(details::text) <= 16384
    )
);

CREATE INDEX idx_recharge_order_event_order
    ON business.recharge_order_event (order_id, created_at DESC, id DESC);
CREATE INDEX idx_recharge_order_event_provider
    ON business.recharge_order_event (provider_event_id)
    WHERE provider_event_id IS NOT NULL;

COMMENT ON TABLE business.recharge_order_event IS
    'Append-only recharge order transition log. Late or out-of-order provider callbacks are '
    'recorded here for reconciliation instead of mutating a terminal order.';

CREATE TABLE business.manual_point_adjustment (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    points integer NOT NULL,
    direction varchar(8) NOT NULL,
    reason varchar(500) NOT NULL,
    operator_id bigint NOT NULL,
    idempotency_key varchar(100) NOT NULL,
    request_hash char(64) NOT NULL,
    confirmation_token_hash char(64) NOT NULL,
    status varchar(24) NOT NULL,
    ledger_id bigint,
    applied_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_manual_point_adjustment_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT fk_manual_point_adjustment_operator FOREIGN KEY (operator_id) REFERENCES business.account (id),
    CONSTRAINT uk_manual_point_adjustment_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_manual_point_adjustment_confirmation UNIQUE (confirmation_token_hash),
    CONSTRAINT ck_manual_point_adjustment_points CHECK (points > 0),
    -- Only credits are representable. Debits would need an explicit overdraft product decision,
    -- which is not taken here; point_account already forbids negative balances.
    CONSTRAINT ck_manual_point_adjustment_direction CHECK (direction IN ('CREDIT')),
    CONSTRAINT ck_manual_point_adjustment_status CHECK (
        status IN ('PENDING_CONFIRMATION', 'APPLIED', 'CANCELLED')
    ),
    CONSTRAINT ck_manual_point_adjustment_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_manual_point_adjustment_token_hash CHECK (confirmation_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_manual_point_adjustment_reason CHECK (length(btrim(reason)) > 0),
    -- An applied adjustment must carry its ledger row; an unapplied one must not.
    CONSTRAINT ck_manual_point_adjustment_ledger CHECK (
        (status = 'APPLIED' AND ledger_id IS NOT NULL AND applied_at IS NOT NULL)
        OR (status <> 'APPLIED' AND ledger_id IS NULL AND applied_at IS NULL)
    )
);

CREATE INDEX idx_manual_point_adjustment_user
    ON business.manual_point_adjustment (user_id, created_at DESC, id DESC);

COMMENT ON TABLE business.manual_point_adjustment IS
    'Operator-initiated point credit. Requires a two-step confirmation: the request stores only the '
    'confirmation token hash, and the ledger row is written in the same transaction that flips '
    'status to APPLIED.';

CREATE TABLE business.operational_audit_event (
    id bigint PRIMARY KEY,
    actor_type varchar(24) NOT NULL,
    actor_user_id bigint,
    actor_ref varchar(64),
    target_type varchar(32) NOT NULL,
    target_id bigint,
    event_type varchar(48) NOT NULL,
    outcome varchar(16) NOT NULL,
    trace_id varchar(64),
    source_event_id varchar(128),
    idempotency_key varchar(100),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_operational_audit_actor FOREIGN KEY (actor_user_id) REFERENCES business.account (id),
    CONSTRAINT ck_operational_audit_actor_type CHECK (
        actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'PAYMENT_PROVIDER', 'MAIL_PROVIDER')
    ),
    -- Non-user actors must not fabricate an account reference. This is the invariant that
    -- account_security_audit could not express.
    CONSTRAINT ck_operational_audit_actor_binding CHECK (
        (actor_type IN ('USER', 'ADMIN') AND actor_user_id IS NOT NULL)
        OR (actor_type IN ('SYSTEM', 'PAYMENT_PROVIDER', 'MAIL_PROVIDER')
            AND actor_user_id IS NULL AND actor_ref IS NOT NULL)
    ),
    CONSTRAINT ck_operational_audit_target_type CHECK (target_type ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    CONSTRAINT ck_operational_audit_event_type CHECK (event_type ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    CONSTRAINT ck_operational_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'PENDING')),
    -- 16 KiB ceiling, matching the M8 requirement: summaries and hashes only, never secrets,
    -- tokens, full callback bodies or full prompts.
    CONSTRAINT ck_operational_audit_details CHECK (
        jsonb_typeof(details) = 'object' AND octet_length(details::text) <= 16384
    )
);

CREATE INDEX idx_operational_audit_target
    ON business.operational_audit_event (target_type, target_id, created_at DESC, id DESC);
CREATE INDEX idx_operational_audit_actor
    ON business.operational_audit_event (actor_type, actor_user_id, created_at DESC, id DESC);
CREATE INDEX idx_operational_audit_created
    ON business.operational_audit_event (created_at DESC, id DESC);
-- Deduplicates provider-originated events without forcing every caller to supply a key.
CREATE UNIQUE INDEX uk_operational_audit_idempotency
    ON business.operational_audit_event (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON TABLE business.operational_audit_event IS
    'Append-only commercial and system audit trail. Distinct from account_security_audit, which '
    'stays scoped to account security. Rows are never updated or deleted through the Browser API.';

-- Widen the closed point_ledger type constraints. The pre-existing values are preserved verbatim;
-- all rows that satisfied the old constraint still satisfy the new one, so no data migration is
-- needed and the task/workflow freeze/settle/release semantics are untouched.
ALTER TABLE business.point_ledger DROP CONSTRAINT ck_point_ledger_entry_type;
ALTER TABLE business.point_ledger DROP CONSTRAINT ck_point_ledger_business_type;

ALTER TABLE business.point_ledger
    ADD CONSTRAINT ck_point_ledger_entry_type CHECK (
        entry_type IN ('TASK_FREEZE', 'TASK_SETTLE', 'TASK_RELEASE',
            'WORKFLOW_FREEZE', 'WORKFLOW_ALLOCATE', 'WORKFLOW_TASK_SETTLE',
            'WORKFLOW_TASK_RELEASE', 'WORKFLOW_SETTLE', 'WORKFLOW_RELEASE',
            'ADMIN_INITIAL_GRANT',
            'RECHARGE', 'ADMIN_ADJUSTMENT')
    ),
    ADD CONSTRAINT ck_point_ledger_business_type CHECK (
        business_type IN ('TASK', 'WORKFLOW', 'ACCOUNT_PROVISIONING',
            'RECHARGE', 'ADMIN_ADJUSTMENT')
    );

-- Recharge and manual adjustment always move available points and never touch frozen points.
-- Keeping frozen_delta at zero for these types preserves the freeze/settle/release invariants.
ALTER TABLE business.point_ledger
    ADD CONSTRAINT ck_point_ledger_credit_types CHECK (
        entry_type NOT IN ('RECHARGE', 'ADMIN_ADJUSTMENT')
        OR (available_delta > 0 AND frozen_delta = 0)
    );
