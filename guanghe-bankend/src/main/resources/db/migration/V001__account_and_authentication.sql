CREATE SCHEMA IF NOT EXISTS business;
CREATE SCHEMA IF NOT EXISTS agent;

CREATE TABLE business.account (
    id bigint PRIMARY KEY,
    username varchar(64) NOT NULL,
    username_normalized varchar(64) NOT NULL,
    phone_e164 varchar(20) NOT NULL,
    password_hash char(32) NOT NULL,
    password_algorithm varchar(32) NOT NULL DEFAULT 'MD5_FIXED_SALT_V1',
    token_version integer NOT NULL DEFAULT 1,
    role varchar(16) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    display_name varchar(64),
    email varchar(255),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_account_username_normalized UNIQUE (username_normalized),
    CONSTRAINT uk_account_phone_e164 UNIQUE (phone_e164),
    CONSTRAINT ck_account_password_hash CHECK (password_hash ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_account_password_algorithm CHECK (password_algorithm = 'MD5_FIXED_SALT_V1'),
    CONSTRAINT ck_account_token_version CHECK (token_version > 0),
    CONSTRAINT ck_account_role CHECK (role IN ('ADMIN', 'USER')),
    CONSTRAINT ck_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE business.sms_delivery_log (
    id bigint PRIMARY KEY,
    user_id bigint,
    phone_masked varchar(32) NOT NULL,
    phone_hash char(64) NOT NULL,
    purpose varchar(32) NOT NULL,
    provider_request_id varchar(128),
    result_code varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_sms_delivery_log_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT ck_sms_delivery_log_phone_hash CHECK (phone_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sms_delivery_log_purpose CHECK (purpose IN ('REGISTER', 'RESET_PASSWORD', 'CHANGE_PHONE'))
);

CREATE INDEX idx_sms_delivery_log_phone_created
    ON business.sms_delivery_log (phone_hash, created_at DESC);

CREATE TABLE business.user_preference (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    preference_key varchar(64) NOT NULL,
    preference_value jsonb NOT NULL,
    confirmed_message_id bigint,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_user_preference_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT uk_user_preference_key UNIQUE (user_id, preference_key),
    CONSTRAINT ck_user_preference_value_object CHECK (jsonb_typeof(preference_value) IN ('object', 'array', 'string', 'number', 'boolean'))
);

CREATE TABLE business.account_consent (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    consent_type varchar(32) NOT NULL,
    document_version varchar(64) NOT NULL,
    accepted_at timestamptz NOT NULL,
    source_ip_hash char(64),
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_account_consent_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT uk_account_consent_version UNIQUE (user_id, consent_type, document_version),
    CONSTRAINT ck_account_consent_type CHECK (consent_type IN ('TERMS', 'PRIVACY')),
    CONSTRAINT ck_account_consent_ip_hash CHECK (source_ip_hash IS NULL OR source_ip_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE business.account_deactivation_request (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'SUBMITTED',
    reason varchar(500),
    submitted_at timestamptz NOT NULL,
    processed_by bigint,
    processed_at timestamptz,
    process_note varchar(1000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_deactivation_request_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT fk_deactivation_request_operator FOREIGN KEY (processed_by) REFERENCES business.account (id),
    CONSTRAINT ck_deactivation_request_status CHECK (status IN ('SUBMITTED', 'PROCESSING', 'COMPLETED', 'REJECTED'))
);

CREATE UNIQUE INDEX uk_deactivation_request_open
    ON business.account_deactivation_request (user_id)
    WHERE status IN ('SUBMITTED', 'PROCESSING');

CREATE INDEX idx_deactivation_request_status_created
    ON business.account_deactivation_request (status, created_at DESC);

