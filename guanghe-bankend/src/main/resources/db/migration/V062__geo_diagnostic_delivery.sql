-- A diagnostic uses one existing paid GEO task. Delivery retries never admit another task.
ALTER TABLE business.geo_question DROP CONSTRAINT ck_geo_question_category;
ALTER TABLE business.geo_question ADD CONSTRAINT ck_geo_question_category
 CHECK(category IN ('BRAND_DISCOVERY','BRAND_REPUTATION'));
ALTER TABLE business.geo_topic ADD COLUMN topic_kind varchar(16) NOT NULL DEFAULT 'MONITORING';
ALTER TABLE business.geo_topic ADD CONSTRAINT ck_geo_topic_kind CHECK(topic_kind IN ('MONITORING','DIAGNOSTIC'));
ALTER TABLE business.task_draft DROP CONSTRAINT ck_task_draft_source_binding;
ALTER TABLE business.task_draft ADD CONSTRAINT ck_task_draft_source_binding CHECK (
 (draft_source='CHAT' AND conversation_id IS NOT NULL AND agent_run_id IS NOT NULL
  AND operation_scope IS NULL AND idempotency_key IS NULL AND request_hash IS NULL)
 OR (draft_source='FORM' AND agent_run_id IS NULL
  AND ((task_type='MEDIA' AND conversation_id IS NULL AND operation_scope='MEDIA_QUOTE_CREATE')
   OR (task_type<>'MEDIA' AND conversation_id IS NOT NULL
    AND operation_scope IN ('TASK_DRAFT_CREATE','TASK_DRAFT_REVISION'))
   OR (task_type='GEO' AND conversation_id IS NOT NULL AND operation_scope='GEO_DIAGNOSTIC_SUBMIT'
    AND COALESCE(input_snapshot->'parameters'->>'mode'='DIAGNOSTIC_RUN',false)))
  AND idempotency_key IS NOT NULL AND request_hash ~ '^[0-9a-f]{64}$')
 OR (draft_source='PLAN' AND agent_run_id IS NULL
  AND ((conversation_id IS NULL AND task_type='GEO' AND operation_scope='GEO_PLAN_DRAFT_CREATE')
   OR (conversation_id IS NOT NULL AND task_type IN ('IMAGE','VIDEO','GEO','ARTICLE')
    AND operation_scope='WORKFLOW_PLAN_DRAFT_CREATE'))
  AND idempotency_key IS NOT NULL AND request_hash ~ '^[0-9a-f]{64}$')
);
ALTER TABLE business.geo_diagnostic_preparation ADD CONSTRAINT uk_diagnostic_project_owner UNIQUE(id,user_id,conversation_id,space_id);
ALTER TABLE business.geo_diagnostic_preparation ADD CONSTRAINT uk_diagnostic_delivery_owner UNIQUE(id,user_id);
CREATE TABLE business.geo_diagnostic_delivery (
 diagnostic_id bigint PRIMARY KEY,user_id bigint NOT NULL,space_id bigint NOT NULL,conversation_id bigint NOT NULL,
 task_id bigint NOT NULL UNIQUE,run_id bigint NOT NULL UNIQUE,topic_id bigint NOT NULL UNIQUE,
 request_id uuid NOT NULL,request_hash char(64) NOT NULL,submitted_version integer NOT NULL,
 quote_id uuid NOT NULL,quote_hash char(64) NOT NULL,pricing_version_id bigint NOT NULL,quoted_points integer NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'SAMPLING',version integer NOT NULL DEFAULT 1,
 sampling_status varchar(24) NOT NULL DEFAULT 'QUEUED',settled_points integer NOT NULL DEFAULT 0,released_points integer NOT NULL DEFAULT 0,
 evidence jsonb,evidence_hash char(64),analysis jsonb,analysis_usage jsonb,snapshot_at timestamptz,
 analysis_attempts integer NOT NULL DEFAULT 0,pdf_attempts integer NOT NULL DEFAULT 0,
 claim_token uuid,lease_expires_at timestamptz,error_code varchar(64),
 object_key varchar(1024),pdf_size bigint,pdf_sha256 char(64),pdf_filename varchar(255),
 created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,completed_at timestamptz,
 UNIQUE(user_id,request_id),
 FOREIGN KEY(diagnostic_id,user_id,conversation_id,space_id) REFERENCES business.geo_diagnostic_preparation(id,user_id,conversation_id,space_id),
 FOREIGN KEY(task_id,user_id,conversation_id) REFERENCES business.business_task(id,user_id,conversation_id),
 FOREIGN KEY(task_id,user_id,space_id) REFERENCES business.business_task(id,user_id,space_id),
 FOREIGN KEY(run_id,user_id,task_id) REFERENCES business.geo_run(id,user_id,task_id),
 FOREIGN KEY(topic_id,user_id,space_id) REFERENCES business.geo_topic(id,user_id,space_id),
 CHECK(request_hash ~ '^[0-9a-f]{64}$' AND quote_hash ~ '^[0-9a-f]{64}$' AND quoted_points>0 AND pricing_version_id>0 AND submitted_version>0 AND version>0),
 CHECK(status IN ('SAMPLING','ANALYSIS_QUEUED','ANALYSIS_RUNNING','ANALYSIS_FAILED','PDF_QUEUED','PDF_RUNNING','PDF_FAILED','READY')),
 CHECK(sampling_status IN ('QUEUED','RUNNING','SUCCEEDED','PARTIAL','FAILED','SYSTEM_TERMINATED')),
 CHECK(settled_points>=0 AND released_points>=0 AND settled_points+released_points<=quoted_points),
 CHECK(analysis_attempts BETWEEN 0 AND 2 AND pdf_attempts BETWEEN 0 AND 3),
 CHECK((status IN ('ANALYSIS_RUNNING','PDF_RUNNING') AND claim_token IS NOT NULL AND lease_expires_at IS NOT NULL)
  OR (status NOT IN ('ANALYSIS_RUNNING','PDF_RUNNING') AND claim_token IS NULL AND lease_expires_at IS NULL)),
 CHECK(evidence IS NULL OR (jsonb_typeof(evidence)='object' AND octet_length(evidence::text)<=262144 AND evidence_hash IS NOT NULL AND evidence_hash ~ '^[0-9a-f]{64}$' AND snapshot_at IS NOT NULL)),
 CHECK(analysis IS NULL OR (jsonb_typeof(analysis)='object' AND octet_length(analysis::text)<=131072)),
 CHECK(analysis_usage IS NULL OR (jsonb_typeof(analysis_usage)='object' AND octet_length(analysis_usage::text)<=4096)),
 CHECK(status<>'READY' OR (analysis IS NOT NULL AND evidence IS NOT NULL AND object_key IS NOT NULL AND pdf_size IS NOT NULL AND pdf_size BETWEEN 1 AND 10485760
  AND pdf_sha256 IS NOT NULL AND pdf_sha256 ~ '^[0-9a-f]{64}$' AND pdf_filename IS NOT NULL AND length(btrim(pdf_filename))>0 AND completed_at IS NOT NULL))
);
CREATE TABLE business.geo_diagnostic_delivery_request (
 user_id bigint NOT NULL,request_id uuid NOT NULL,diagnostic_id bigint NOT NULL,
 request_version integer NOT NULL CHECK(request_version>0),request_hash char(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL,PRIMARY KEY(user_id,request_id),
 FOREIGN KEY(diagnostic_id,user_id) REFERENCES business.geo_diagnostic_preparation(id,user_id)
);
CREATE TRIGGER protect_diagnostic_delivery_request BEFORE UPDATE ON business.geo_diagnostic_delivery_request
 FOR EACH ROW EXECUTE FUNCTION business.protect_geo_diagnostic_card();
CREATE FUNCTION business.protect_geo_diagnostic_saved_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM business.geo_run WHERE id=OLD.run_id AND metric_version='geo-diagnostic-observation-v1') THEN
  RAISE EXCEPTION 'diagnostic saved answers and citations are immutable' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_diagnostic_saved_answers BEFORE UPDATE OR DELETE ON business.geo_observation
 FOR EACH ROW EXECUTE FUNCTION business.protect_geo_diagnostic_saved_evidence();
CREATE TRIGGER protect_diagnostic_saved_citations BEFORE UPDATE OR DELETE ON business.geo_citation
 FOR EACH ROW EXECUTE FUNCTION business.protect_geo_diagnostic_saved_evidence();
CREATE INDEX idx_diagnostic_delivery_queue ON business.geo_diagnostic_delivery(status,updated_at,diagnostic_id)
 WHERE status IN ('SAMPLING','ANALYSIS_QUEUED','ANALYSIS_RUNNING','PDF_QUEUED','PDF_RUNNING');
CREATE FUNCTION business.verify_geo_diagnostic_admission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM business.business_task t
  JOIN business.task_draft d ON d.id=t.draft_id AND d.user_id=t.user_id
  JOIN business.geo_run r ON r.id=NEW.run_id AND r.task_id=t.id AND r.user_id=t.user_id
  JOIN business.geo_topic q ON q.id=r.topic_id AND q.user_id=t.user_id
  JOIN business.geo_diagnostic_preparation p ON p.id=NEW.diagnostic_id AND p.user_id=t.user_id
  WHERE t.id=NEW.task_id AND t.task_type='GEO' AND t.operation_scope='GEO_DIAGNOSTIC_SUBMIT'
   AND t.conversation_id=NEW.conversation_id AND t.space_id=NEW.space_id
   AND t.idempotency_key=NEW.request_id AND t.request_hash=NEW.request_hash AND t.quoted_points=NEW.quoted_points
   AND t.pricing_version_id=NEW.pricing_version_id AND t.input_snapshot#>>'{parameters,mode}'='DIAGNOSTIC_RUN'
   AND t.input_snapshot#>>'{authorizedContext,diagnostic,diagnosticId}'=NEW.diagnostic_id::text
   AND d.draft_source='FORM' AND d.agent_run_id IS NULL AND d.status='ADMITTED'
   AND d.confirmed_at IS NULL AND d.admitted_at IS NOT NULL AND d.operation_scope='GEO_DIAGNOSTIC_SUBMIT'
   AND r.metric_version='geo-diagnostic-observation-v1' AND r.sample_mode='STANDARD'
   AND r.topic_id=NEW.topic_id AND q.topic_kind='DIAGNOSTIC'
   AND p.step=6 AND p.version=NEW.submitted_version AND p.conversation_id=NEW.conversation_id AND p.space_id=NEW.space_id
   AND p.quote->>'id'=NEW.quote_id::text AND p.quote->>'inputHash'=NEW.quote_hash
   AND (p.quote->>'points')::integer=NEW.quoted_points AND (p.quote->>'version')::integer=NEW.submitted_version)
 THEN RAISE EXCEPTION 'diagnostic admission provenance mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER verify_geo_diagnostic_admission BEFORE INSERT ON business.geo_diagnostic_delivery
 FOR EACH ROW EXECUTE FUNCTION business.verify_geo_diagnostic_admission();
CREATE FUNCTION business.protect_geo_diagnostic_paid_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'paid diagnostic admission and recovery proof cannot be deleted' USING ERRCODE='23514';
END $$;
CREATE TRIGGER protect_diagnostic_delivery_delete BEFORE DELETE ON business.geo_diagnostic_delivery
 FOR EACH ROW EXECUTE FUNCTION business.protect_geo_diagnostic_paid_delete();
CREATE TRIGGER protect_diagnostic_recovery_delete BEFORE DELETE ON business.geo_diagnostic_delivery_request
 FOR EACH ROW EXECUTE FUNCTION business.protect_geo_diagnostic_paid_delete();
CREATE FUNCTION business.protect_geo_diagnostic_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.diagnostic_id,NEW.user_id,NEW.space_id,NEW.conversation_id,NEW.task_id,NEW.run_id,NEW.topic_id,
  NEW.request_id,NEW.request_hash,NEW.submitted_version,NEW.quote_id,NEW.quote_hash,NEW.pricing_version_id,NEW.quoted_points,NEW.created_at)
  IS DISTINCT FROM ROW(OLD.diagnostic_id,OLD.user_id,OLD.space_id,OLD.conversation_id,OLD.task_id,OLD.run_id,OLD.topic_id,
  OLD.request_id,OLD.request_hash,OLD.submitted_version,OLD.quote_id,OLD.quote_hash,OLD.pricing_version_id,OLD.quoted_points,OLD.created_at)
  OR (OLD.evidence IS NOT NULL AND ROW(NEW.evidence,NEW.evidence_hash,NEW.snapshot_at,NEW.sampling_status,NEW.settled_points,NEW.released_points)
   IS DISTINCT FROM ROW(OLD.evidence,OLD.evidence_hash,OLD.snapshot_at,OLD.sampling_status,OLD.settled_points,OLD.released_points))
  OR (OLD.analysis IS NOT NULL AND ROW(NEW.analysis,NEW.analysis_usage) IS DISTINCT FROM ROW(OLD.analysis,OLD.analysis_usage))
  OR (OLD.status='READY' AND NEW IS DISTINCT FROM OLD) THEN
  RAISE EXCEPTION 'diagnostic submission/evidence/delivery is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_geo_diagnostic_delivery BEFORE UPDATE ON business.geo_diagnostic_delivery
 FOR EACH ROW EXECUTE FUNCTION business.protect_geo_diagnostic_delivery();
CREATE FUNCTION business.protect_submitted_diagnostic_input() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM business.geo_diagnostic_delivery WHERE diagnostic_id=OLD.id)
  AND ROW(NEW.data,NEW.quote,NEW.step) IS DISTINCT FROM ROW(OLD.data,OLD.quote,OLD.step) THEN
  RAISE EXCEPTION 'submitted diagnostic input is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_submitted_diagnostic_input BEFORE UPDATE ON business.geo_diagnostic_preparation
 FOR EACH ROW EXECUTE FUNCTION business.protect_submitted_diagnostic_input();
