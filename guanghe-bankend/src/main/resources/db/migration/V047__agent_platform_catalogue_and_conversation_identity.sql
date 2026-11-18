-- Preserve existing business-space IDs and every historical foreign key.
ALTER TABLE business.business_space ALTER COLUMN brand_name DROP NOT NULL;
ALTER TABLE business.business_space ADD COLUMN project_status varchar(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE business.business_space ADD CONSTRAINT ck_business_space_project_status
    CHECK (project_status IN ('ACTIVE','ARCHIVED'));

CREATE TABLE business.agent_definition_version (
    agent_key varchar(64) NOT NULL,
    version varchar(32) NOT NULL,
    name varchar(100) NOT NULL,
    description varchar(1000) NOT NULL,
    capabilities jsonb NOT NULL,
    adapter_key varchar(64) NOT NULL,
    visibility varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    definition_hash char(64) NOT NULL,
    published_at timestamptz NOT NULL,
    PRIMARY KEY (agent_key,version),
    CONSTRAINT ck_agent_definition_key CHECK (agent_key ~ '^[a-z][a-z0-9-]{0,63}$'),
    CONSTRAINT ck_agent_definition_version CHECK (version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    CONSTRAINT ck_agent_definition_name CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT ck_agent_definition_capabilities CHECK (
        jsonb_typeof(capabilities)='array' AND jsonb_array_length(capabilities)<=32
        AND octet_length(capabilities::text)<=4096),
    CONSTRAINT ck_agent_definition_visibility CHECK (visibility IN ('PUBLIC','INTERNAL')),
    CONSTRAINT ck_agent_definition_status CHECK (status IN ('PUBLISHED','DISABLED','WITHDRAWN')),
    CONSTRAINT ck_agent_definition_hash CHECK (definition_hash ~ '^[0-9a-f]{64}$')
);

INSERT INTO business.agent_definition_version(agent_key,version,name,description,capabilities,adapter_key,
    visibility,status,definition_hash,published_at)
SELECT key,'1.0.0',name,description,capabilities::jsonb,key,visibility,'PUBLISHED',
    encode(sha256(convert_to(key||'|1.0.0|'||name||'|'||description||'|'||capabilities,'UTF8')),'hex'),
    CURRENT_TIMESTAMP
FROM (VALUES
    ('geo','GEO 智能体','查看 AI 可见度证据，分析报告并准备内容；付费执行和发布仍遵循现有确认。',
     '["chat","resources.read","geo.read","geo.monitor","article.generate","image.generate","video.generate","media.recommend"]','PUBLIC'),
    ('summary','资料摘要助手','仅根据本次获授权的资料提供摘要，不具有 GEO、媒体生成或发布能力。',
     '["chat","resources.read","text.summarize"]','INTERNAL')
) AS builtins(key,name,description,capabilities,visibility);

CREATE TABLE business.agent_installation (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES business.account(id),
    agent_key varchar(64) NOT NULL,
    agent_version varchar(32) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    CONSTRAINT fk_agent_installation_definition FOREIGN KEY (agent_key,agent_version)
        REFERENCES business.agent_definition_version(agent_key,version),
    CONSTRAINT uk_agent_installation_owner_identity UNIQUE (id,user_id,agent_key,agent_version),
    CONSTRAINT ck_agent_installation_version CHECK (version>0),
    CONSTRAINT ck_agent_installation_tombstone CHECK (deleted_at IS NULL OR NOT enabled)
);
CREATE UNIQUE INDEX uk_agent_installation_active ON business.agent_installation(user_id,agent_key)
    WHERE deleted_at IS NULL;

CREATE TABLE business.conversation_agent_binding (
    conversation_id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    installation_id bigint NOT NULL,
    agent_key varchar(64) NOT NULL,
    agent_version varchar(32) NOT NULL,
    agent_name varchar(100) NOT NULL,
    project_id bigint,
    capabilities jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_conversation_agent_owner FOREIGN KEY (conversation_id,user_id)
        REFERENCES business.conversation(id,user_id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_agent_installation FOREIGN KEY (installation_id,user_id,agent_key,agent_version)
        REFERENCES business.agent_installation(id,user_id,agent_key,agent_version),
    CONSTRAINT fk_conversation_agent_project FOREIGN KEY (project_id,user_id)
        REFERENCES business.business_space(id,user_id),
    CONSTRAINT ck_conversation_agent_capabilities CHECK (
        jsonb_typeof(capabilities)='array' AND jsonb_array_length(capabilities)<=32
        AND octet_length(capabilities::text)<=4096)
);
CREATE INDEX idx_conversation_agent_project ON business.conversation_agent_binding(user_id,project_id,conversation_id);

-- Definition content and conversation identity cannot be rewritten by an update path.
-- Visibility/status remain operational controls, distinct from an immutable definition revision.
CREATE FUNCTION business.guard_agent_platform_immutable_fields() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='agent_definition_version' THEN
        IF (to_jsonb(NEW)-'status'-'visibility') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'visibility') THEN
            RAISE EXCEPTION 'Agent definition revisions are immutable' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='agent_installation' THEN
        IF ROW(NEW.id,NEW.user_id,NEW.agent_key,NEW.agent_version,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.agent_key,OLD.agent_version,OLD.created_at)
            OR (OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at) THEN
            RAISE EXCEPTION 'Agent installation identity is immutable' USING ERRCODE='23514';
        END IF;
    ELSIF NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Conversation agent identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_definition_immutable BEFORE UPDATE ON business.agent_definition_version
    FOR EACH ROW EXECUTE FUNCTION business.guard_agent_platform_immutable_fields();
CREATE TRIGGER agent_installation_identity_immutable BEFORE UPDATE ON business.agent_installation
    FOR EACH ROW EXECUTE FUNCTION business.guard_agent_platform_immutable_fields();
CREATE TRIGGER conversation_agent_identity_immutable BEFORE UPDATE ON business.conversation_agent_binding
    FOR EACH ROW EXECUTE FUNCTION business.guard_agent_platform_immutable_fields();
