ALTER TABLE business.task_draft
    ADD COLUMN draft_source varchar(8) NOT NULL DEFAULT 'CHAT',
    ADD COLUMN operation_scope varchar(32),
    ADD COLUMN idempotency_key uuid,
    ADD COLUMN request_hash char(64),
    ALTER COLUMN agent_run_id DROP NOT NULL;

ALTER TABLE business.task_draft
    ADD CONSTRAINT ck_task_draft_source CHECK (draft_source IN ('CHAT', 'FORM')),
    ADD CONSTRAINT ck_task_draft_source_binding CHECK (
        (draft_source = 'CHAT' AND agent_run_id IS NOT NULL
            AND operation_scope IS NULL AND idempotency_key IS NULL AND request_hash IS NULL)
        OR
        (draft_source = 'FORM' AND agent_run_id IS NULL
            AND operation_scope IN ('TASK_DRAFT_CREATE', 'TASK_DRAFT_REVISION')
            AND idempotency_key IS NOT NULL
            AND request_hash ~ '^[0-9a-f]{64}$')
    );

CREATE UNIQUE INDEX uk_task_draft_idempotency
    ON business.task_draft (user_id, operation_scope, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_business_task_stale_queue
    ON business.business_task (queued_at, id)
    WHERE status = 'QUEUED';
