-- One explicitly selected monitor only; no new wallet, counters or workflow.
CREATE TABLE business.agent_execution_budget_policy (
    user_id bigint PRIMARY KEY REFERENCES business.account(id),
    version integer NOT NULL CHECK (version > 0),
    enabled boolean NOT NULL,
    max_points_per_action integer NOT NULL CHECK (max_points_per_action > 0),
    max_points_per_day integer NOT NULL CHECK (max_points_per_day >= max_points_per_action),
    effective_shanghai_date date NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE business.geo_monitor_execution_grant (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    installation_id bigint NOT NULL,
    agent_key varchar(64) NOT NULL CHECK (agent_key = 'geo'),
    agent_version varchar(64) NOT NULL,
    definition_hash char(64) NOT NULL CHECK (definition_hash ~ '^[0-9a-f]{64}$'),
    project_id bigint,
    topic_id bigint NOT NULL,
    scope_snapshot jsonb NOT NULL CHECK (jsonb_typeof(scope_snapshot) = 'object' AND octet_length(scope_snapshot::text) <= 32768),
    version integer NOT NULL CHECK (version > 0),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE(id,user_id,conversation_id),
    FOREIGN KEY(conversation_id,user_id) REFERENCES business.conversation(id,user_id),
    FOREIGN KEY(installation_id,user_id,agent_key,agent_version)
        REFERENCES business.agent_installation(id,user_id,agent_key,agent_version),
    FOREIGN KEY(project_id,user_id) REFERENCES business.business_space(id,user_id),
    FOREIGN KEY(topic_id,user_id) REFERENCES business.geo_topic(id,user_id),
    CHECK ((status='ACTIVE' AND revoked_at IS NULL) OR (status='REVOKED' AND revoked_at IS NOT NULL))
);
CREATE UNIQUE INDEX uk_geo_monitor_active_grant
    ON business.geo_monitor_execution_grant(user_id,conversation_id) WHERE status='ACTIVE';

-- Freeze quote provenance against the existing actual draft. Task price IDs are not
-- V030 recharge conversion-rate IDs; do not create an unrelated commercial FK.
ALTER TABLE business.task_draft ADD CONSTRAINT uk_task_draft_monitor_quote
    UNIQUE(id,user_id,pricing_version_id,input_hash,quoted_points);

CREATE TABLE business.geo_monitor_execution_admission (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    run_id uuid NOT NULL UNIQUE,
    request_message_id bigint NOT NULL,
    draft_id bigint NOT NULL UNIQUE,
    -- Missing/unowned grants are normal preparation refusals. The original claimed ID
    -- remains in intent_snapshot; this FK is populated only for an owned real record.
    grant_id bigint,
    grant_version integer NOT NULL CHECK (grant_version > 0),
    frozen_identity jsonb NOT NULL CHECK (jsonb_typeof(frozen_identity)='object' AND octet_length(frozen_identity::text)<=8192),
    intent_snapshot jsonb NOT NULL CHECK (jsonb_typeof(intent_snapshot)='object' AND octet_length(intent_snapshot::text)<=32768),
    intent_hash char(64) NOT NULL CHECK (intent_hash ~ '^[0-9a-f]{64}$'),
    input_hash char(64) NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    pricing_version_id bigint NOT NULL,
    quoted_points integer NOT NULL CHECK (quoted_points > 0),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','WAIT_INPUT','ADMITTED')),
    reason_code varchar(64),
    task_id bigint,
    execution_source varchar(32) CHECK (execution_source IN ('EXECUTION_PREAUTH','USER_CONFIRM')),
    admitted_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY(run_id,user_id,conversation_id) REFERENCES business.agent_run(id,user_id,conversation_id),
    FOREIGN KEY(request_message_id,user_id,conversation_id) REFERENCES business.conversation_message(id,user_id,conversation_id),
    FOREIGN KEY(draft_id,user_id) REFERENCES business.task_draft(id,user_id),
    FOREIGN KEY(draft_id,user_id,pricing_version_id,input_hash,quoted_points)
        REFERENCES business.task_draft(id,user_id,pricing_version_id,input_hash,quoted_points),
    FOREIGN KEY(grant_id,user_id,conversation_id) REFERENCES business.geo_monitor_execution_grant(id,user_id,conversation_id),
    FOREIGN KEY(task_id,user_id,conversation_id) REFERENCES business.business_task(id,user_id,conversation_id),
    CHECK ((status='ADMITTED' AND task_id IS NOT NULL AND execution_source IS NOT NULL AND admitted_at IS NOT NULL AND reason_code IS NULL)
        OR (status IN ('PENDING','WAIT_INPUT') AND task_id IS NULL AND execution_source IS NULL AND admitted_at IS NULL)),
    CHECK ((status='WAIT_INPUT' AND reason_code IS NOT NULL AND reason_code IN (
        'AUTOMATIC_MONITOR_DISABLED','BUDGET_NOT_CONFIGURED','BUDGET_DISABLED','BUDGET_NOT_EFFECTIVE',
        'ACCOUNT_UNAVAILABLE','AGENT_UNAVAILABLE','PROJECT_UNAVAILABLE',
        'GRANT_NOT_FOUND','GRANT_CHANGED','GRANT_REVOKED','GRANT_EXPIRED',
        'TOPIC_UNAVAILABLE','MONITOR_TARGET_MISSING','MONITOR_REGION_MISSING',
        'RANGE_CHANGED','INPUT_CHANGED','PRICE_CHANGED','DRAFT_EXPIRED',
        'RUN_FAILED','RUN_STOPPED','NO_MATCHING_PROPOSAL','PROPOSAL_RANGE_MISMATCH',
        'SINGLE_ACTION_LIMIT','DAY_LIMIT','INSUFFICIENT_POINTS')) OR (status<>'WAIT_INPUT' AND reason_code IS NULL))
);
CREATE INDEX idx_geo_monitor_pending_admission
    ON business.geo_monitor_execution_admission(created_at,id) WHERE status='PENDING';

ALTER TABLE business.task_draft ADD COLUMN admitted_at timestamptz;
ALTER TABLE business.task_draft DROP CONSTRAINT ck_task_draft_status;
ALTER TABLE business.task_draft ADD CONSTRAINT ck_task_draft_status CHECK (
    status IN ('EDITING','WAITING_CONFIRMATION','CONFIRMED','ADMITTED','EXPIRED','ABANDONED'));
ALTER TABLE business.task_draft ADD CONSTRAINT ck_task_draft_preapproval_truth CHECK (
    (status='ADMITTED' AND task_type='GEO' AND confirmed_at IS NULL AND admitted_at IS NOT NULL)
    OR (status<>'ADMITTED' AND admitted_at IS NULL));

CREATE FUNCTION business.guard_monitor_execution_facts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='agent_execution_budget_policy' THEN
        IF ROW(NEW.user_id,NEW.effective_shanghai_date,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.user_id,OLD.effective_shanghai_date,OLD.created_at) THEN
            RAISE EXCEPTION 'Budget policy identity/effective date is immutable';
        END IF;
    ELSIF TG_TABLE_NAME='geo_monitor_execution_grant' THEN
        IF ROW(NEW.id,NEW.user_id,NEW.conversation_id,NEW.installation_id,NEW.agent_key,NEW.agent_version,NEW.definition_hash,NEW.project_id,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.conversation_id,OLD.installation_id,OLD.agent_key,OLD.agent_version,OLD.definition_hash,OLD.project_id,OLD.created_at)
            OR (OLD.status='REVOKED' AND NEW.status<>'REVOKED') THEN
            RAISE EXCEPTION 'Grant identity/revocation is immutable';
        END IF;
    ELSE
        IF ROW(NEW.id,NEW.user_id,NEW.conversation_id,NEW.run_id,NEW.request_message_id,NEW.draft_id,NEW.grant_id,NEW.grant_version,NEW.frozen_identity,NEW.intent_snapshot,NEW.intent_hash,NEW.input_hash,NEW.pricing_version_id,NEW.quoted_points,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.conversation_id,OLD.run_id,OLD.request_message_id,OLD.draft_id,OLD.grant_id,OLD.grant_version,OLD.frozen_identity,OLD.intent_snapshot,OLD.intent_hash,OLD.input_hash,OLD.pricing_version_id,OLD.quoted_points,OLD.created_at)
            OR (OLD.status='ADMITTED' AND NEW IS DISTINCT FROM OLD) THEN
            RAISE EXCEPTION 'Admission frozen facts/results are immutable';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_budget_policy_facts BEFORE UPDATE ON business.agent_execution_budget_policy
    FOR EACH ROW EXECUTE FUNCTION business.guard_monitor_execution_facts();
CREATE TRIGGER guard_geo_monitor_grant_facts BEFORE UPDATE ON business.geo_monitor_execution_grant
    FOR EACH ROW EXECUTE FUNCTION business.guard_monitor_execution_facts();
CREATE TRIGGER guard_geo_monitor_admission_facts BEFORE UPDATE ON business.geo_monitor_execution_admission
    FOR EACH ROW EXECUTE FUNCTION business.guard_monitor_execution_facts();
