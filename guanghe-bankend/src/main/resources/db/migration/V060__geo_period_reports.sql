-- Customer project reports freeze existing facts. They never create paid business tasks.
CREATE TABLE business.geo_period_report (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    request_id uuid NOT NULL,
    input_hash char(64) NOT NULL,
    snapshot jsonb NOT NULL,
    evidence jsonb NOT NULL,
    evidence_hash char(64) NOT NULL,
    status varchar(24) NOT NULL,
    summary_markdown text,
    summary_error varchar(64),
    summary_attempts integer NOT NULL DEFAULT 0,
    claim_token uuid,
    lease_expires_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_period_report_project FOREIGN KEY (space_id,user_id)
        REFERENCES business.business_space(id,user_id),
    CONSTRAINT uk_period_report_request UNIQUE(user_id,request_id),
    CONSTRAINT ck_period_report_status CHECK(status IN ('QUEUED','RUNNING','READY','SUMMARY_UNAVAILABLE')),
    CONSTRAINT ck_period_report_hash CHECK(input_hash ~ '^[0-9a-f]{64}$' AND evidence_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_period_report_snapshot CHECK(jsonb_typeof(snapshot)='object' AND octet_length(snapshot::text)<=2097152),
    CONSTRAINT ck_period_report_evidence CHECK(jsonb_typeof(evidence)='object' AND octet_length(evidence::text)<=49152),
    CONSTRAINT ck_period_report_summary CHECK(summary_markdown IS NULL OR length(summary_markdown)<=6000),
    CONSTRAINT ck_period_report_attempts CHECK(summary_attempts BETWEEN 0 AND 2 AND version>0),
    CONSTRAINT ck_period_report_claim CHECK((status='RUNNING' AND claim_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status<>'RUNNING' AND claim_token IS NULL AND lease_expires_at IS NULL))
);
CREATE INDEX idx_period_report_owner ON business.geo_period_report(user_id,space_id,created_at DESC,id DESC);
CREATE UNIQUE INDEX uk_period_report_active_user ON business.geo_period_report(user_id)
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX idx_period_report_queue ON business.geo_period_report(created_at,id) WHERE status='QUEUED';
CREATE FUNCTION business.protect_geo_period_report_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.user_id,NEW.space_id,NEW.request_id,NEW.input_hash,NEW.snapshot,NEW.evidence,NEW.evidence_hash,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.space_id,OLD.request_id,OLD.input_hash,OLD.snapshot,OLD.evidence,OLD.evidence_hash,OLD.created_at)
        OR OLD.status='READY' THEN
        RAISE EXCEPTION 'saved period report facts are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_geo_period_report_snapshot BEFORE UPDATE ON business.geo_period_report
    FOR EACH ROW EXECUTE FUNCTION business.protect_geo_period_report_snapshot();
