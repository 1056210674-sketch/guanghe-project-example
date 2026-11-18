-- Current authoritative memory only. Deletion retains a versioned, value-free tombstone.
ALTER TABLE business.knowledge_document ADD COLUMN conversation_memory_excluded boolean NOT NULL DEFAULT false;
-- Only proven legacy brand.save documents are excluded. Manual/uploaded sources are untouched.
UPDATE business.knowledge_document d SET conversation_memory_excluded=true
 WHERE EXISTS(SELECT 1 FROM business.agent_tool_action a WHERE a.user_id=d.user_id AND a.action='brand.save'
   AND a.response->>'status'='COMPLETED' AND a.response->'data'->>'knowledgeDocumentId'=d.id::text);
CREATE TABLE business.agent_memory_entry (
 id bigint PRIMARY KEY,
 user_id bigint NOT NULL REFERENCES business.account(id),
 project_id bigint,
 scope varchar(16) NOT NULL CHECK (scope IN ('PROJECT','ACCOUNT')),
 kind varchar(16) NOT NULL CHECK (kind IN ('FACT','PREFERENCE')),
 memory_key varchar(64) NOT NULL CHECK (memory_key ~ '^[a-z][a-z0-9_.-]{0,63}$'),
 label varchar(100) NOT NULL CHECK (length(btrim(label)) > 0),
 value varchar(2000),
 version integer NOT NULL CHECK (version > 0),
 source_type varchar(16) NOT NULL CHECK (source_type IN ('USER_MESSAGE','USER_INPUT')),
 source_message_id bigint,
 source_conversation_id bigint,
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL,
 deleted_at timestamptz,
 history_cutoff_at timestamptz,
 CHECK ((scope='PROJECT' AND project_id IS NOT NULL) OR (scope='ACCOUNT' AND project_id IS NULL AND kind='PREFERENCE')),
 CHECK ((deleted_at IS NULL AND value IS NOT NULL AND length(btrim(value))>0) OR (deleted_at IS NOT NULL AND value IS NULL)),
 CHECK ((source_type='USER_MESSAGE' AND ((source_message_id IS NOT NULL AND source_conversation_id IS NOT NULL)
         OR (source_message_id IS NULL AND source_conversation_id IS NULL)))
     OR (source_type='USER_INPUT' AND source_message_id IS NULL AND source_conversation_id IS NULL)),
 FOREIGN KEY(project_id,user_id) REFERENCES business.business_space(id,user_id) ON DELETE CASCADE,
 FOREIGN KEY(source_message_id,user_id,source_conversation_id) REFERENCES business.conversation_message(id,user_id,conversation_id)
     ON DELETE SET NULL (source_message_id,source_conversation_id),
 UNIQUE NULLS NOT DISTINCT(user_id,scope,project_id,memory_key)
);
CREATE INDEX idx_agent_memory_scope ON business.agent_memory_entry(user_id,project_id,updated_at);
ALTER TABLE business.agent_tool_action DROP CONSTRAINT agent_tool_action_action_check;
ALTER TABLE business.agent_tool_action ADD CONSTRAINT agent_tool_action_action_check
 CHECK(action IN ('context.read','brand.save','geo.monitor','memory.read','memory.upsert','memory.forget'));
