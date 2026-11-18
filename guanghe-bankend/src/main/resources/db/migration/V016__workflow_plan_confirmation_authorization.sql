ALTER TABLE business.workflow_plan
    DROP CONSTRAINT ck_workflow_plan_status,
    DROP CONSTRAINT ck_workflow_plan_lifecycle;

ALTER TABLE business.workflow_plan
    ADD CONSTRAINT ck_workflow_plan_status CHECK (
        status IN ('DRAFT', 'WAITING_CONFIRMATION', 'CONFIRMED', 'ABANDONED')
    ),
    ADD CONSTRAINT ck_workflow_plan_lifecycle CHECK (
        (status IN ('DRAFT', 'WAITING_CONFIRMATION', 'CONFIRMED') AND abandoned_at IS NULL)
        OR (status = 'ABANDONED' AND abandoned_at IS NOT NULL)
    );

ALTER TABLE business.workflow_confirmation
    ADD COLUMN confirmation_idempotency_key uuid,
    ADD COLUMN request_hash char(64),
    DROP CONSTRAINT ck_workflow_confirmation_lifecycle;

ALTER TABLE business.workflow_confirmation
    ADD CONSTRAINT uk_workflow_confirmation_user_idempotency
        UNIQUE (user_id, confirmation_idempotency_key),
    ADD CONSTRAINT ck_workflow_confirmation_request_hash CHECK (
        request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_workflow_confirmation_idempotency_pair CHECK (
        (confirmation_idempotency_key IS NULL) = (request_hash IS NULL)
    ),
    ADD CONSTRAINT ck_workflow_confirmation_lifecycle CHECK (
        (status = 'AVAILABLE' AND consumed_at IS NULL AND invalidated_at IS NULL
            AND confirmation_idempotency_key IS NULL AND request_hash IS NULL)
        OR (status = 'CONSUMED' AND consumed_at IS NOT NULL AND invalidated_at IS NULL
            AND confirmation_idempotency_key IS NOT NULL AND request_hash IS NOT NULL)
        OR (status IN ('EXPIRED', 'INVALIDATED') AND consumed_at IS NULL
            AND invalidated_at IS NOT NULL AND confirmation_idempotency_key IS NULL
            AND request_hash IS NULL)
    );

ALTER TABLE business.workflow_event
    DROP CONSTRAINT ck_workflow_event_type,
    DROP CONSTRAINT ck_workflow_event_status;

ALTER TABLE business.workflow_event
    ADD CONSTRAINT ck_workflow_event_type CHECK (
        event_type IN ('PLAN_PROPOSED', 'PLAN_WAITING_CONFIRMATION',
            'PLAN_CONFIRMED', 'PLAN_ABANDONED')
    ),
    ADD CONSTRAINT ck_workflow_event_status CHECK (
        (from_status IS NULL OR from_status IN (
            'DRAFT', 'WAITING_CONFIRMATION', 'CONFIRMED', 'ABANDONED'))
        AND to_status IN ('DRAFT', 'WAITING_CONFIRMATION', 'CONFIRMED', 'ABANDONED')
    );
