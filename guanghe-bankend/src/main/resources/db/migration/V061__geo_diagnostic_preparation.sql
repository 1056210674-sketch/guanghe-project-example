-- Preparation is persisted separately from Agent Runs and never freezes task funds.
CREATE TABLE business.geo_diagnostic_preparation (
 id bigint PRIMARY KEY, user_id bigint NOT NULL, space_id bigint NOT NULL,
 conversation_id bigint NOT NULL, request_id uuid NOT NULL,
 version integer NOT NULL DEFAULT 1, step integer NOT NULL DEFAULT 1,
 data jsonb NOT NULL, quote jsonb,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 CONSTRAINT fk_diagnostic_project FOREIGN KEY(space_id,user_id) REFERENCES business.business_space(id,user_id),
 CONSTRAINT fk_diagnostic_conversation FOREIGN KEY(conversation_id,user_id) REFERENCES business.conversation(id,user_id) ON DELETE CASCADE,
 CONSTRAINT uk_diagnostic_request UNIQUE(user_id,request_id),
 CONSTRAINT uk_diagnostic_owner UNIQUE(id,user_id,conversation_id),
 CONSTRAINT ck_diagnostic_version CHECK(version>0 AND step BETWEEN 1 AND 6),
 CONSTRAINT ck_diagnostic_data CHECK(jsonb_typeof(data)='object' AND octet_length(data::text)<=262144),
 CONSTRAINT ck_diagnostic_quote CHECK(quote IS NULL OR jsonb_typeof(quote)='object')
);
CREATE INDEX idx_diagnostic_conversation ON business.geo_diagnostic_preparation(user_id,conversation_id,created_at DESC,id DESC);
CREATE INDEX idx_diagnostic_project ON business.geo_diagnostic_preparation(user_id,space_id,created_at DESC,id DESC);
CREATE TABLE business.geo_diagnostic_card (
 id bigint PRIMARY KEY, diagnostic_id bigint NOT NULL, user_id bigint NOT NULL, conversation_id bigint NOT NULL,
 step integer NOT NULL, message_id bigint NOT NULL, snapshot jsonb NOT NULL, created_at timestamptz NOT NULL,
 CONSTRAINT fk_diagnostic_card FOREIGN KEY(diagnostic_id,user_id,conversation_id)
  REFERENCES business.geo_diagnostic_preparation(id,user_id,conversation_id) ON DELETE CASCADE,
 CONSTRAINT fk_diagnostic_card_message FOREIGN KEY(message_id,user_id,conversation_id)
  REFERENCES business.conversation_message(id,user_id,conversation_id) ON DELETE CASCADE,
 CONSTRAINT uk_diagnostic_card_message UNIQUE(message_id),
 CONSTRAINT ck_diagnostic_card_step CHECK(step BETWEEN 1 AND 6),
 CONSTRAINT ck_diagnostic_card_snapshot CHECK(jsonb_typeof(snapshot)='object' AND octet_length(snapshot::text)<=262144)
);
CREATE INDEX idx_diagnostic_cards ON business.geo_diagnostic_card(diagnostic_id,id);
CREATE TABLE business.geo_diagnostic_generation (
 id bigint PRIMARY KEY, diagnostic_id bigint NOT NULL, user_id bigint NOT NULL, conversation_id bigint NOT NULL,
 request_id uuid NOT NULL, request_version integer NOT NULL CHECK(request_version>0), phase varchar(32) NOT NULL, input_hash char(64) NOT NULL, input jsonb NOT NULL,
 status varchar(12) NOT NULL, error_code varchar(64), claim_token uuid, lease_expires_at timestamptz,
 usage jsonb, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 CONSTRAINT fk_diagnostic_generation FOREIGN KEY(diagnostic_id,user_id,conversation_id)
  REFERENCES business.geo_diagnostic_preparation(id,user_id,conversation_id) ON DELETE CASCADE,
 CONSTRAINT uk_diagnostic_generation_request UNIQUE(user_id,request_id),
 CONSTRAINT ck_diagnostic_generation_phase CHECK(phase IN ('BRAND_RESEARCH','COMPETITORS','DISCOVERY_QUESTIONS','REPUTATION_QUESTIONS')),
 CONSTRAINT ck_diagnostic_generation_status CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','SUPERSEDED')),
 CONSTRAINT ck_diagnostic_generation_hash CHECK(input_hash ~ '^[0-9a-f]{64}$'),
 CONSTRAINT ck_diagnostic_generation_input CHECK(jsonb_typeof(input)='object' AND octet_length(input::text)<=262144),
 CONSTRAINT ck_diagnostic_generation_claim CHECK((status='RUNNING' AND claim_token IS NOT NULL AND lease_expires_at IS NOT NULL)
  OR (status<>'RUNNING' AND claim_token IS NULL AND lease_expires_at IS NULL))
);
CREATE UNIQUE INDEX uk_diagnostic_active_user ON business.geo_diagnostic_generation(user_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX idx_diagnostic_generation_queue ON business.geo_diagnostic_generation(created_at,id) WHERE status='QUEUED';
CREATE FUNCTION business.protect_geo_diagnostic_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.user_id,NEW.space_id,NEW.conversation_id,NEW.request_id,NEW.created_at)
  IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.space_id,OLD.conversation_id,OLD.request_id,OLD.created_at) THEN
  RAISE EXCEPTION 'diagnostic identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER protect_geo_diagnostic_identity BEFORE UPDATE ON business.geo_diagnostic_preparation
 FOR EACH ROW EXECUTE FUNCTION business.protect_geo_diagnostic_identity();
CREATE FUNCTION business.protect_geo_diagnostic_card() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW IS DISTINCT FROM OLD THEN
  RAISE EXCEPTION 'diagnostic card history is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER protect_geo_diagnostic_card BEFORE UPDATE ON business.geo_diagnostic_card
 FOR EACH ROW EXECUTE FUNCTION business.protect_geo_diagnostic_card();
CREATE FUNCTION business.protect_geo_diagnostic_generation_input() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.diagnostic_id,NEW.user_id,NEW.conversation_id,NEW.request_id,NEW.request_version,NEW.phase,NEW.input_hash,NEW.input,NEW.created_at)
  IS DISTINCT FROM ROW(OLD.id,OLD.diagnostic_id,OLD.user_id,OLD.conversation_id,OLD.request_id,OLD.request_version,OLD.phase,OLD.input_hash,OLD.input,OLD.created_at) THEN
  RAISE EXCEPTION 'diagnostic generation input is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER protect_geo_diagnostic_generation_input BEFORE UPDATE ON business.geo_diagnostic_generation
 FOR EACH ROW EXECUTE FUNCTION business.protect_geo_diagnostic_generation_input();
