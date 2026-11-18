-- Additive, owner-scoped discovery. No historical report/context/catalog mutation.
CREATE TABLE business.geo_competitor_candidate (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    name_normalized VARCHAR(150) NOT NULL,
    entity_kind VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    version INTEGER NOT NULL DEFAULT 1,
    competitor_id BIGINT,
    first_report_id BIGINT NOT NULL,
    last_report_id BIGINT NOT NULL,
    evidence JSONB NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_geo_candidate_topic FOREIGN KEY(topic_id,user_id,space_id) REFERENCES business.geo_topic(id,user_id,space_id),
    CONSTRAINT fk_geo_candidate_competitor FOREIGN KEY(competitor_id,user_id,space_id) REFERENCES business.competitor(id,user_id,space_id),
    CONSTRAINT fk_geo_candidate_first_report FOREIGN KEY(first_report_id,user_id) REFERENCES business.geo_report(id,user_id),
    CONSTRAINT fk_geo_candidate_last_report FOREIGN KEY(last_report_id,user_id) REFERENCES business.geo_report(id,user_id),
    CONSTRAINT uk_geo_candidate_name UNIQUE(topic_id,name_normalized),
    CONSTRAINT ck_geo_candidate_name CHECK(length(btrim(name)) BETWEEN 2 AND 150 AND length(btrim(name_normalized)) BETWEEN 2 AND 150),
    CONSTRAINT ck_geo_candidate_kind CHECK(entity_kind IN ('BRAND','PRODUCT')),
    CONSTRAINT ck_geo_candidate_status CHECK(status IN ('PENDING','CONFIRMED','IGNORED')),
    CONSTRAINT ck_geo_candidate_version CHECK(version>0),
    CONSTRAINT ck_geo_candidate_decision CHECK((status='CONFIRMED')=(competitor_id IS NOT NULL)),
    CONSTRAINT ck_geo_candidate_evidence CHECK(jsonb_typeof(evidence)='array' AND jsonb_array_length(evidence)<=2 AND octet_length(evidence::text)<=8192)
);
CREATE INDEX idx_geo_candidate_page ON business.geo_competitor_candidate(user_id,topic_id,status,last_seen_at DESC,id DESC);

CREATE TABLE business.geo_report_competitive_insight (
    id BIGINT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    input_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    claim_token UUID,
    lease_expires_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ,
    result JSONB,
    usage JSONB,
    last_error_code VARCHAR(80),
    traceparent VARCHAR(128),
    available_at TIMESTAMPTZ NOT NULL,
    capacity_wait_until TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_geo_competitive_report FOREIGN KEY(report_id,user_id) REFERENCES business.geo_report(id,user_id),
    CONSTRAINT uk_geo_competitive_report UNIQUE(report_id),
    CONSTRAINT ck_geo_competitive_hash CHECK(input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_geo_competitive_status CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_geo_competitive_attempts CHECK(attempts BETWEEN 0 AND 2),
    CONSTRAINT ck_geo_competitive_lease CHECK(
        (status='RUNNING' AND claim_token IS NOT NULL AND lease_expires_at IS NOT NULL AND deadline_at IS NOT NULL AND deadline_at<=lease_expires_at)
        OR (status<>'RUNNING' AND claim_token IS NULL AND lease_expires_at IS NULL AND deadline_at IS NULL)),
    CONSTRAINT ck_geo_competitive_finished CHECK(
        (status IN ('SUCCEEDED','FAILED') AND finished_at IS NOT NULL)
        OR (status IN ('QUEUED','RUNNING') AND finished_at IS NULL)),
    CONSTRAINT ck_geo_competitive_work_attempt CHECK(status='SUCCEEDED' OR attempts>=1),
    CONSTRAINT ck_geo_competitive_error CHECK(
        (status='FAILED' AND last_error_code IS NOT NULL)
        OR status IN ('QUEUED','RUNNING') OR (status='SUCCEEDED' AND last_error_code IS NULL)),
    CONSTRAINT ck_geo_competitive_result CHECK((status='SUCCEEDED')=(result IS NOT NULL)),
    CONSTRAINT ck_geo_competitive_result_size CHECK(result IS NULL OR (jsonb_typeof(result)='object' AND octet_length(result::text)<=131072)),
    CONSTRAINT ck_geo_competitive_usage CHECK(usage IS NULL OR (jsonb_typeof(usage)='object' AND octet_length(usage::text)<=4096))
);
CREATE UNIQUE INDEX uk_geo_competitive_active_user ON business.geo_report_competitive_insight(user_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX idx_geo_competitive_queue ON business.geo_report_competitive_insight(available_at,id) WHERE status='QUEUED';
