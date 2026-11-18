ALTER TABLE business.conversation_event
    DROP CONSTRAINT ck_conversation_event_type;

ALTER TABLE business.conversation_event
    ADD CONSTRAINT ck_conversation_event_type CHECK (
        event_type IN ('message.start', 'message.delta', 'message.completed',
            'interaction.required', 'draft.created', 'draft.expired',
            'agent.progress', 'task.queued', 'task.progress', 'task.completed',
            'task.failed', 'heartbeat')
    );

CREATE UNIQUE INDEX uk_conversation_event_agent_progress_identity
    ON business.conversation_event (
        agent_run_id,
        (payload ->> 'stageCode'),
        (payload ->> 'status'),
        ((payload ->> 'ordinal')::integer)
    )
    WHERE event_type = 'agent.progress';
