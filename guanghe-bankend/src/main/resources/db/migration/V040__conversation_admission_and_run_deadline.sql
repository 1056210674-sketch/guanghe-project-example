-- Keep accepted-turn counters independent of deletable conversations/messages. No prompts,
-- responses, tokens or image URLs are retained here. The account row serializes admission.
CREATE TABLE business.conversation_admission (
    run_id uuid PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES business.account(id),
    accepted_at timestamptz NOT NULL,
    vision boolean NOT NULL
);

CREATE INDEX idx_conversation_admission_user_time
    ON business.conversation_admission (user_id, accepted_at DESC);

-- Preserve the current rolling/day allowance through the upgrade. Earlier deleted conversations
-- cannot be reconstructed; subsequent deletions do not affect this ledger.
INSERT INTO business.conversation_admission(run_id, user_id, accepted_at, vision)
SELECT id, user_id, created_at, jsonb_array_length(attachment_asset_ids) > 0
FROM business.agent_run
WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '2 days';

-- Nullable for older writers during the compatibility window. Old Runs retain their immutable
-- context expiry as a conservative recovery deadline; new writers freeze the real model deadline.
ALTER TABLE business.agent_run ADD COLUMN deadline_at timestamptz;
ALTER TABLE business.agent_run ADD CONSTRAINT ck_agent_run_deadline
    CHECK (deadline_at IS NULL OR (deadline_at > created_at AND deadline_at <= context_expires_at));

CREATE INDEX idx_agent_run_active_deadline
    ON business.agent_run (COALESCE(deadline_at, context_expires_at), id)
    WHERE status IN ('QUEUED', 'STARTING', 'RUNNING', 'STOP_REQUESTED');
