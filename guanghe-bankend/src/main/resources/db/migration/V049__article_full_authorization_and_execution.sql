-- Fixed one-article preparation, on the existing workflow wallet and task lifecycle.
CREATE TABLE business.article_execution_grant (
 id bigint PRIMARY KEY,user_id bigint NOT NULL,conversation_id bigint NOT NULL,
 installation_id bigint NOT NULL,agent_key varchar(64) NOT NULL,agent_version varchar(32) NOT NULL,
 project_id bigint,space_id bigint NOT NULL,product_id bigint,version integer NOT NULL CHECK(version>0),
 status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','USED','REVOKED')),
 max_points integer NOT NULL CHECK(max_points BETWEEN 1 AND 100000),expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
 UNIQUE(id,user_id,conversation_id),
 FOREIGN KEY(user_id) REFERENCES business.account(id),
 FOREIGN KEY(conversation_id,user_id) REFERENCES business.conversation(id,user_id),
 FOREIGN KEY(space_id,user_id) REFERENCES business.business_space(id,user_id),
 FOREIGN KEY(product_id,user_id,space_id) REFERENCES business.product(id,user_id,space_id),
 FOREIGN KEY(installation_id,user_id,agent_key,agent_version) REFERENCES business.agent_installation(id,user_id,agent_key,agent_version),
 CHECK(expires_at>created_at AND expires_at<=created_at+interval '7 days')
);
ALTER TABLE business.workflow_plan_step ADD COLUMN deferred_article_mode varchar(16);
ALTER TABLE business.workflow_plan_step DROP CONSTRAINT ck_workflow_step_prepare;
ALTER TABLE business.workflow_plan_step ADD CONSTRAINT ck_workflow_step_prepare CHECK (COALESCE(
 (step_type='PREPARE' AND risk_level='PREPARE' AND tool_name='task_draft.propose'
  AND task_type IN ('IMAGE','VIDEO','GEO','ARTICLE') AND pricing_version_id IS NOT NULL AND quoted_points>0
  AND (task_draft_id IS NOT NULL OR (task_type='ARTICLE' AND deferred_article_mode IN ('TITLE','OUTLINE','BODY')
       AND status IN ('PENDING','READY','FAILED','SKIPPED'))))
 OR (step_type<>'PREPARE' AND task_draft_id IS NULL AND task_type IS NULL AND pricing_version_id IS NULL AND quoted_points=0
     AND deferred_article_mode IS NULL)
,false));
CREATE TABLE business.article_full_preparation (
 plan_id bigint PRIMARY KEY,user_id bigint NOT NULL,conversation_id bigint NOT NULL,
 grant_id bigint NOT NULL UNIQUE,grant_version integer NOT NULL CHECK(grant_version>0),
 article_id bigint NOT NULL UNIQUE,expected_article_version integer NOT NULL CHECK(expected_article_version>0),
 source_run_id uuid NOT NULL UNIQUE,request_message_id bigint NOT NULL,
 idempotency_key uuid NOT NULL,request_hash char(64) NOT NULL CHECK(request_hash~'^[0-9a-f]{64}$'),
 snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object' AND octet_length(snapshot::text)<=262144),
 brief_hash char(64) NOT NULL CHECK(brief_hash~'^[0-9a-f]{64}$'),
 max_calls integer NOT NULL CHECK(max_calls BETWEEN 3 AND 30),
 max_input_tokens bigint NOT NULL CHECK(max_input_tokens>0),max_output_tokens bigint NOT NULL CHECK(max_output_tokens>0),
 deadline_at timestamptz NOT NULL,status varchar(16) NOT NULL CHECK(status IN ('RUNNING','SUCCEEDED','FAILED')),
 error_code varchar(64),created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
 UNIQUE(user_id,idempotency_key),
 FOREIGN KEY(plan_id,user_id,conversation_id) REFERENCES business.workflow_plan(id,user_id,conversation_id),
 FOREIGN KEY(grant_id,user_id,conversation_id) REFERENCES business.article_execution_grant(id,user_id,conversation_id),
 FOREIGN KEY(article_id,user_id) REFERENCES business.article(id,user_id),
 FOREIGN KEY(source_run_id,user_id,conversation_id) REFERENCES business.agent_run(id,user_id,conversation_id),
 FOREIGN KEY(request_message_id,user_id,conversation_id) REFERENCES business.conversation_message(id,user_id,conversation_id),
 CHECK(deadline_at>created_at),CHECK((status='FAILED')=(error_code IS NOT NULL))
);
CREATE INDEX idx_article_full_running ON business.article_full_preparation(updated_at,plan_id) WHERE status='RUNNING';
CREATE TABLE business.article_automatic_adoption (
 id bigint PRIMARY KEY,plan_id bigint NOT NULL REFERENCES business.article_full_preparation(plan_id),
 article_id bigint NOT NULL,user_id bigint NOT NULL,mode varchar(16) NOT NULL CHECK(mode IN ('TITLE','OUTLINE')),
 source_task_id bigint NOT NULL,artifact_version integer NOT NULL CHECK(artifact_version>0),
 source varchar(16) NOT NULL CHECK(source='AUTO_POLICY'),adopted_at timestamptz NOT NULL,
 UNIQUE(plan_id,mode),UNIQUE(source_task_id),
 FOREIGN KEY(article_id,user_id) REFERENCES business.article(id,user_id),
 FOREIGN KEY(source_task_id,user_id) REFERENCES business.business_task(id,user_id)
);
ALTER TABLE business.article DROP CONSTRAINT ck_article_title_workflow;
ALTER TABLE business.article ADD CONSTRAINT ck_article_title_workflow CHECK (COALESCE(
 (confirmed_title IS NULL AND confirmed_title_source IS NULL AND confirmed_title_batch_version IS NULL
  AND confirmed_title_candidate_index IS NULL AND confirmed_title_at IS NULL)
 OR (confirmed_title IS NOT NULL AND length(btrim(confirmed_title)) BETWEEN 1 AND 300 AND confirmed_title_batch_version>0
  AND ((confirmed_title_source IN ('SELECTED','EDITED','CUSTOM') AND confirmed_title_at IS NOT NULL
       AND ((confirmed_title_source='CUSTOM' AND confirmed_title_candidate_index IS NULL)
            OR (confirmed_title_source IN ('SELECTED','EDITED') AND confirmed_title_candidate_index BETWEEN 1 AND 5)))
       OR (confirmed_title_source='AUTO_POLICY' AND confirmed_title_at IS NULL AND confirmed_title_candidate_index BETWEEN 1 AND 5)))
,false));
ALTER TABLE business.task_draft DROP CONSTRAINT ck_task_draft_preapproval_truth;
ALTER TABLE business.task_draft ADD CONSTRAINT ck_task_draft_preapproval_truth CHECK (COALESCE(
 (status='ADMITTED' AND confirmed_at IS NULL AND admitted_at IS NOT NULL
  AND (task_type='GEO' OR (task_type='ARTICLE' AND draft_source='PLAN'
       AND operation_scope='WORKFLOW_PLAN_DRAFT_CREATE' AND input_snapshot->'articleFull'->>'templateCode'='ARTICLE_FULL_V1')))
 OR (status<>'ADMITTED' AND admitted_at IS NULL)
,false));
CREATE FUNCTION business.guard_article_full_facts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='article_execution_grant' THEN
  IF ROW(NEW.id,NEW.user_id,NEW.conversation_id,NEW.installation_id,NEW.agent_key,NEW.agent_version,NEW.project_id,
         NEW.space_id,NEW.product_id,NEW.max_points,NEW.expires_at,NEW.created_at)
     IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.conversation_id,OLD.installation_id,OLD.agent_key,OLD.agent_version,OLD.project_id,
         OLD.space_id,OLD.product_id,OLD.max_points,OLD.expires_at,OLD.created_at)
     OR (OLD.status='REVOKED' AND NEW.status<>'REVOKED')
     OR (OLD.status='USED' AND NEW.status NOT IN ('USED','REVOKED'))
     OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Article grant facts are immutable'; END IF;
 ELSE
  IF ROW(NEW.plan_id,NEW.user_id,NEW.conversation_id,NEW.grant_id,NEW.grant_version,NEW.article_id,NEW.source_run_id,
         NEW.request_message_id,NEW.idempotency_key,NEW.request_hash,NEW.snapshot,NEW.brief_hash,NEW.max_calls,
         NEW.max_input_tokens,NEW.max_output_tokens,NEW.deadline_at,NEW.created_at)
     IS DISTINCT FROM ROW(OLD.plan_id,OLD.user_id,OLD.conversation_id,OLD.grant_id,OLD.grant_version,OLD.article_id,OLD.source_run_id,
         OLD.request_message_id,OLD.idempotency_key,OLD.request_hash,OLD.snapshot,OLD.brief_hash,OLD.max_calls,
         OLD.max_input_tokens,OLD.max_output_tokens,OLD.deadline_at,OLD.created_at)
     OR (OLD.status<>'RUNNING' AND NEW IS DISTINCT FROM OLD) THEN RAISE EXCEPTION 'Article preparation facts are immutable'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER guard_article_grant BEFORE UPDATE ON business.article_execution_grant FOR EACH ROW EXECUTE FUNCTION business.guard_article_full_facts();
CREATE TRIGGER guard_article_preparation BEFORE UPDATE ON business.article_full_preparation FOR EACH ROW EXECUTE FUNCTION business.guard_article_full_facts();

-- Deferred preparation is permitted only inside this fixed, owned, one-article parent.
CREATE FUNCTION business.guard_fixed_article_stage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.deferred_article_mode IS NOT NULL THEN
  IF NEW.task_type<>'ARTICLE' OR NEW.ordinal NOT BETWEEN 1 AND 3
     OR NEW.deferred_article_mode<>(CASE NEW.ordinal WHEN 1 THEN 'TITLE' WHEN 2 THEN 'OUTLINE' ELSE 'BODY' END)
     OR NOT EXISTS (SELECT 1 FROM business.article_full_preparation p
         WHERE p.plan_id=NEW.plan_id AND p.user_id=NEW.user_id AND p.conversation_id=NEW.conversation_id)
     OR (TG_OP='UPDATE' AND ROW(NEW.deferred_article_mode,NEW.plan_id,NEW.user_id,NEW.conversation_id,NEW.ordinal,NEW.pricing_version_id,NEW.quoted_points)
         IS DISTINCT FROM ROW(OLD.deferred_article_mode,OLD.plan_id,OLD.user_id,OLD.conversation_id,OLD.ordinal,OLD.pricing_version_id,OLD.quoted_points)) THEN
   RAISE EXCEPTION 'Invalid fixed ARTICLE stage' USING ERRCODE='23514';
  END IF;
 ELSIF TG_OP='UPDATE' AND OLD.deferred_article_mode IS NOT NULL THEN
  RAISE EXCEPTION 'Fixed ARTICLE stage identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_fixed_article_stage BEFORE INSERT OR UPDATE ON business.workflow_plan_step
 FOR EACH ROW EXECUTE FUNCTION business.guard_fixed_article_stage();

CREATE FUNCTION business.guard_article_full_relationships() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='article_full_preparation' THEN
  IF NOT EXISTS (SELECT 1 FROM business.workflow_plan w
       JOIN business.article_execution_grant g ON g.id=NEW.grant_id AND g.user_id=NEW.user_id AND g.conversation_id=NEW.conversation_id
       JOIN business.article a ON a.id=NEW.article_id AND a.user_id=NEW.user_id
       JOIN business.agent_run r ON r.id=NEW.source_run_id AND r.user_id=NEW.user_id AND r.conversation_id=NEW.conversation_id
       JOIN business.conversation_message m ON m.id=NEW.request_message_id AND m.user_id=NEW.user_id AND m.conversation_id=NEW.conversation_id
       WHERE w.id=NEW.plan_id AND w.goal_summary='ARTICLE_FULL_V1' AND w.step_count=3
       AND w.source_run_id=NEW.source_run_id AND r.request_message_id=NEW.request_message_id AND r.status='COMPLETED'
       AND m.role='USER' AND m.status='COMPLETED' AND m.agent_run_id=r.id
       AND jsonb_typeof(m.content->'text')='string'
       AND btrim(m.content->>'text')=NEW.snapshot->'brief'->'editorial'->>'requestText'
       AND r.selected_space_id=g.space_id AND r.selected_product_id IS NOT DISTINCT FROM g.product_id
       AND r.context_snapshot->'agentIdentity'->>'installationId'=g.installation_id::text
       AND r.context_snapshot->'agentIdentity'->>'agentKey'=g.agent_key
       AND r.context_snapshot->'agentIdentity'->>'agentVersion'=g.agent_version
       AND (r.context_snapshot->'agentIdentity'->>'projectId') IS NOT DISTINCT FROM g.project_id::text
       AND r.context_snapshot->'agentIdentity'->'capabilities' ? 'article.generate'
       AND w.space_id=g.space_id AND a.space_id=g.space_id AND w.product_id IS NOT DISTINCT FROM g.product_id
       AND a.product_id IS NOT DISTINCT FROM g.product_id AND w.total_quoted_points<=g.max_points
       AND NEW.grant_version=g.version AND g.status='ACTIVE' AND g.expires_at>NEW.created_at
       AND NEW.snapshot->>'templateCode'='ARTICLE_FULL_V1' AND NEW.snapshot->>'briefHash'=NEW.brief_hash) THEN
   RAISE EXCEPTION 'Invalid single ARTICLE authority relationship' USING ERRCODE='23514';
  END IF;
 ELSE
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Automatic adoption history is append-only' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS (SELECT 1 FROM business.article_full_preparation p
       JOIN business.business_task t ON t.id=NEW.source_task_id AND t.user_id=NEW.user_id
       WHERE p.plan_id=NEW.plan_id AND p.article_id=NEW.article_id AND p.user_id=NEW.user_id
       AND t.task_type='ARTICLE' AND t.status='SUCCEEDED'
       AND t.input_snapshot->'parameters'->>'articleId'=NEW.article_id::text
       AND t.input_snapshot->'parameters'->>'mode'=NEW.mode
       AND t.input_snapshot->'articleFull'->>'planId'=NEW.plan_id::text) THEN
   RAISE EXCEPTION 'Invalid automatic ARTICLE adoption' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_article_full_relationships BEFORE INSERT ON business.article_full_preparation
 FOR EACH ROW EXECUTE FUNCTION business.guard_article_full_relationships();
CREATE TRIGGER guard_article_automatic_adoption BEFORE INSERT OR UPDATE OR DELETE ON business.article_automatic_adoption
 FOR EACH ROW EXECUTE FUNCTION business.guard_article_full_relationships();
