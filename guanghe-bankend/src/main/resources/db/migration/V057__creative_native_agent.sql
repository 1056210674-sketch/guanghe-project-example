-- Personal creation reuses the existing media storage/worker scope, never a customer project.
ALTER TABLE business.business_space ADD COLUMN storage_kind varchar(24) NOT NULL DEFAULT 'PROJECT';
ALTER TABLE business.business_space ADD CONSTRAINT ck_business_space_storage_kind
 CHECK(storage_kind IN ('PROJECT','PERSONAL_CREATIVE'));
ALTER TABLE business.business_space ADD CONSTRAINT ck_personal_creative_no_brand
 CHECK(storage_kind='PROJECT' OR (brand_name IS NULL AND brand_profile='{}'::jsonb AND logo_asset_id IS NULL));
CREATE UNIQUE INDEX uk_personal_creative_space ON business.business_space(user_id)
 WHERE storage_kind='PERSONAL_CREATIVE';

-- New installable identity; historic GEO definitions and conversation bindings remain immutable.
INSERT INTO business.agent_definition_version(agent_key,version,name,description,capabilities,adapter_key,
    visibility,status,definition_hash,published_at)
SELECT 'creative','1.0.0',name,description,capabilities::jsonb,'creative','PUBLIC','PUBLISHED',
    encode(sha256(convert_to('creative|1.0.0|'||name||'|'||description||'|'||capabilities,'UTF8')),'hex'),CURRENT_TIMESTAMP
FROM (VALUES ('创作智能体','通过对话创作图片和视频，支持文字描述与已授权参考图片，作品保存到资产中心。',
    '["chat","resources.read","image.generate","video.generate"]')) AS v(name,description,capabilities);

ALTER TABLE business.agent_tool_action DROP CONSTRAINT agent_tool_action_action_check;
ALTER TABLE business.agent_tool_action ADD CONSTRAINT agent_tool_action_action_check
 CHECK(action IN ('context.read','brand.save','geo.monitor','memory.read','memory.upsert','memory.forget',
                 'article.generate','image.generate','video.generate'));

ALTER TABLE business.task_draft DROP CONSTRAINT ck_task_draft_preapproval_truth;
ALTER TABLE business.task_draft ADD CONSTRAINT ck_task_draft_preapproval_truth CHECK (COALESCE(
 (status='ADMITTED' AND confirmed_at IS NULL AND admitted_at IS NOT NULL
  AND (task_type='GEO' OR (task_type='ARTICLE' AND draft_source='PLAN'
       AND operation_scope='WORKFLOW_PLAN_DRAFT_CREATE' AND input_snapshot->'articleFull'->>'templateCode'='ARTICLE_FULL_V1')
       OR (task_type IN ('IMAGE','VIDEO') AND draft_source='CHAT' AND agent_run_id IS NOT NULL
           AND input_snapshot->'nativeCreative'->>'runId'=agent_run_id::text)))
 OR (status<>'ADMITTED' AND admitted_at IS NULL),false));
