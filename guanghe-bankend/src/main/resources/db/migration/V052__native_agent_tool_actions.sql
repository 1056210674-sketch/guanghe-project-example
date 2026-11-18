-- Receipts preserve USER provenance and make native tool retries replayable.
CREATE TABLE business.agent_tool_action (
    run_id uuid NOT NULL,
    tool_call_id varchar(128) NOT NULL CHECK (tool_call_id ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    user_id bigint NOT NULL REFERENCES business.account(id),
    conversation_id bigint NOT NULL,
    source_message_id bigint NOT NULL,
    action varchar(32) NOT NULL CHECK (action IN ('context.read','brand.save','geo.monitor')),
    input_hash char(64) NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    response jsonb NOT NULL CHECK (jsonb_typeof(response)='object' AND octet_length(response::text)<=262144),
    created_at timestamptz NOT NULL,
    PRIMARY KEY(run_id,tool_call_id),
    FOREIGN KEY(run_id,user_id,conversation_id) REFERENCES business.agent_run(id,user_id,conversation_id) ON DELETE CASCADE,
    FOREIGN KEY(conversation_id,user_id) REFERENCES business.conversation(id,user_id) ON DELETE CASCADE,
    FOREIGN KEY(source_message_id,user_id,conversation_id) REFERENCES business.conversation_message(id,user_id,conversation_id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_tool_action_conversation ON business.agent_tool_action(user_id,conversation_id,created_at);
