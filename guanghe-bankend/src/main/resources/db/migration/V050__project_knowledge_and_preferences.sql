-- Source data is separate from personal preferences and derived business history.
CREATE TABLE business.knowledge_document (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    project_id bigint NOT NULL,
    name varchar(255) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 255),
    source_type varchar(24) NOT NULL CHECK (source_type IN ('PASTED_TEXT','LINK_PASTED_TEXT','TXT','MARKDOWN','PDF')),
    status varchar(16) NOT NULL CHECK (status IN ('UPLOADING','PARSING','READY','FAILED','DELETED')),
    current_version integer NOT NULL CHECK (current_version > 0),
    failure_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    UNIQUE(id,user_id,project_id),
    FOREIGN KEY(project_id,user_id) REFERENCES business.business_space(id,user_id),
    CHECK ((status='DELETED') = (deleted_at IS NOT NULL)),
    CHECK ((status='FAILED') = (failure_code IS NOT NULL)),
    CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
);
CREATE INDEX idx_knowledge_document_project
    ON business.knowledge_document(user_id,project_id,updated_at DESC,id DESC) WHERE deleted_at IS NULL;

CREATE TABLE business.knowledge_document_version (
    document_id bigint NOT NULL,
    user_id bigint NOT NULL,
    project_id bigint NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    content_type varchar(80) NOT NULL CHECK (content_type IN ('text/plain','text/markdown','application/pdf')),
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 20971520),
    source_url varchar(2048),
    object_key varchar(1024),
    paragraphs jsonb NOT NULL CHECK (jsonb_typeof(paragraphs)='array'
        AND jsonb_array_length(paragraphs)<=200 AND octet_length(paragraphs::text)<=700000),
    page_count integer CHECK (page_count BETWEEN 1 AND 100),
    created_at timestamptz NOT NULL,
    PRIMARY KEY(document_id,version),
    FOREIGN KEY(document_id,user_id,project_id) REFERENCES business.knowledge_document(id,user_id,project_id),
    CHECK (source_url IS NULL OR (length(source_url)<=2048 AND source_url ~ '^https?://[^[:space:]]+$'))
);

ALTER TABLE business.conversation_message ADD CONSTRAINT uk_conversation_message_preference_owner UNIQUE(id,user_id);
CREATE TABLE business.agent_memory_preference (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES business.account(id),
    project_id bigint,
    content varchar(1000) NOT NULL CHECK (length(btrim(content)) BETWEEN 1 AND 1000),
    content_hash char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    source_type varchar(16) NOT NULL CHECK (source_type IN ('USER_INPUT','USER_MESSAGE')),
    source_message_id bigint,
    version integer NOT NULL CHECK (version>0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    FOREIGN KEY(project_id,user_id) REFERENCES business.business_space(id,user_id),
    FOREIGN KEY(source_message_id,user_id) REFERENCES business.conversation_message(id,user_id)
        ON DELETE SET NULL (source_message_id),
    -- Conversation deletion may remove the source pointer, not the user's explicit preference.
    CHECK (source_type='USER_MESSAGE' OR source_message_id IS NULL)
);
-- NULL project means a personal expression preference, not global business facts.
CREATE UNIQUE INDEX uk_memory_preference_active_content
    ON business.agent_memory_preference(user_id,COALESCE(project_id,0),content_hash) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_memory_preference_message_fragment
    ON business.agent_memory_preference(user_id,source_message_id,content_hash)
    WHERE source_message_id IS NOT NULL;
CREATE INDEX idx_memory_preference_scope
    ON business.agent_memory_preference(user_id,project_id,updated_at DESC,id DESC) WHERE deleted_at IS NULL;
