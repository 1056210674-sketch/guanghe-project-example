ALTER TABLE business.conversation_event DROP CONSTRAINT ck_conversation_event_type;
ALTER TABLE business.conversation_event ADD CONSTRAINT ck_conversation_event_type CHECK (
    event_type IN ('message.start','message.delta','message.completed','interaction.required',
        'draft.created','draft.expired','agent.progress','task.queued','task.progress',
        'task.completed','task.failed','heartbeat','context.resource')
);

CREATE INDEX idx_conversation_resource_latest
    ON business.conversation_event(user_id,conversation_id,sequence_no DESC)
    WHERE event_type='context.resource';
