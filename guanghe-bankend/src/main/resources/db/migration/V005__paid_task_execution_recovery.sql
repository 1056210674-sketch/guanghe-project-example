ALTER TABLE business.outbox_event
    DROP CONSTRAINT uk_outbox_task_event;

ALTER TABLE business.outbox_event
    ADD COLUMN execution_attempt integer NOT NULL DEFAULT 1;

ALTER TABLE business.outbox_event
    ADD CONSTRAINT ck_outbox_execution_attempt CHECK (execution_attempt > 0),
    ADD CONSTRAINT ck_outbox_payload_execution_attempt CHECK (
        payload ? 'attempt'
        AND jsonb_typeof(payload -> 'attempt') = 'number'
        AND execution_attempt = (payload ->> 'attempt')::integer
    ),
    ADD CONSTRAINT uk_outbox_task_execution_attempt UNIQUE (
        aggregate_type, aggregate_id, event_type, execution_attempt
    );
