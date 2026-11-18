ALTER TABLE business.agent_run
    ADD COLUMN trace_id char(32);

ALTER TABLE business.agent_run
    ADD CONSTRAINT ck_agent_run_trace_id CHECK (
        trace_id IS NULL
        OR (trace_id ~ '^[0-9a-f]{32}$' AND trace_id <> '00000000000000000000000000000000')
    );

CREATE INDEX idx_agent_run_trace_id
    ON business.agent_run (trace_id, created_at DESC)
    WHERE trace_id IS NOT NULL;
