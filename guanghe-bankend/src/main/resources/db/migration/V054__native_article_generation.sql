-- Native article admission reuses the fixed TITLE/OUTLINE/BODY parent and its wallet.
-- Its authority is a bounded account execution policy, not an invented UI confirmation.
ALTER TABLE business.agent_tool_action DROP CONSTRAINT agent_tool_action_action_check;
ALTER TABLE business.agent_tool_action ADD CONSTRAINT agent_tool_action_action_check
 CHECK(action IN ('context.read','brand.save','geo.monitor','memory.read','memory.upsert','memory.forget','article.generate'));

ALTER TABLE business.article_execution_grant
 ADD COLUMN execution_source varchar(32) NOT NULL DEFAULT 'USER_AUTHORIZATION',
 ADD COLUMN source_run_id uuid,
 ADD CONSTRAINT ck_article_grant_source CHECK (
   (execution_source='USER_AUTHORIZATION' AND source_run_id IS NULL)
   OR (execution_source='AGENT_ARTICLE_ACTION' AND source_run_id IS NOT NULL)),
 ADD CONSTRAINT fk_article_grant_source_run FOREIGN KEY(source_run_id,user_id,conversation_id)
   REFERENCES business.agent_run(id,user_id,conversation_id);
CREATE UNIQUE INDEX uq_article_grant_native_run ON business.article_execution_grant(source_run_id)
 WHERE source_run_id IS NOT NULL;

CREATE FUNCTION business.guard_article_grant_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.execution_source,NEW.source_run_id) IS DISTINCT FROM ROW(OLD.execution_source,OLD.source_run_id) THEN
  RAISE EXCEPTION 'Article grant source is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_article_grant_source BEFORE UPDATE ON business.article_execution_grant
 FOR EACH ROW EXECUTE FUNCTION business.guard_article_grant_source();

CREATE OR REPLACE FUNCTION business.guard_article_full_relationships() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='article_full_preparation' THEN
  IF NOT EXISTS (SELECT 1 FROM business.workflow_plan w
       JOIN business.article_execution_grant g ON g.id=NEW.grant_id AND g.user_id=NEW.user_id AND g.conversation_id=NEW.conversation_id
       JOIN business.article a ON a.id=NEW.article_id AND a.user_id=NEW.user_id
       JOIN business.agent_run r ON r.id=NEW.source_run_id AND r.user_id=NEW.user_id AND r.conversation_id=NEW.conversation_id
       JOIN business.conversation c ON c.id=r.conversation_id AND c.user_id=r.user_id
       JOIN business.conversation_message m ON m.id=NEW.request_message_id AND m.user_id=NEW.user_id AND m.conversation_id=NEW.conversation_id
       WHERE w.id=NEW.plan_id AND w.goal_summary='ARTICLE_FULL_V1' AND w.step_count=3
       AND w.source_run_id=NEW.source_run_id AND r.request_message_id=NEW.request_message_id
       AND (
         (g.execution_source='USER_AUTHORIZATION' AND r.status='COMPLETED' AND r.selected_space_id=g.space_id)
         OR (g.execution_source='AGENT_ARTICLE_ACTION' AND g.source_run_id=r.id
           AND r.status IN ('QUEUED','STARTING','RUNNING') AND r.stop_requested_at IS NULL
           AND r.context_expires_at>NEW.created_at AND (r.deadline_at IS NULL OR r.deadline_at>NEW.created_at)
           AND (r.selected_space_id IS NULL OR r.selected_space_id=g.space_id)
           AND (c.selected_space_id=g.space_id OR (c.selected_space_id IS NULL AND g.project_id=g.space_id))
           AND (g.project_id IS NULL OR g.project_id=g.space_id)))
       AND m.role='USER' AND m.status='COMPLETED' AND m.agent_run_id=r.id
       AND jsonb_typeof(m.content->'text')='string'
       AND btrim(m.content->>'text')=NEW.snapshot->'brief'->'editorial'->>'requestText'
       AND r.selected_product_id IS NOT DISTINCT FROM g.product_id
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
