ALTER TABLE business.account
    ALTER COLUMN password_hash TYPE varchar(255),
    ADD COLUMN credential_state varchar(24) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE business.account DROP CONSTRAINT ck_account_password_hash;
ALTER TABLE business.account DROP CONSTRAINT ck_account_password_algorithm;
ALTER TABLE business.account
    ADD CONSTRAINT ck_account_password_algorithm CHECK (
        (password_algorithm = 'MD5_FIXED_SALT_V1' AND password_hash ~ '^[0-9a-f]{32}$')
        OR (password_algorithm = 'BCRYPT_V1' AND password_hash ~ '^\$2[aby]\$[0-9]{2}\$.{53}$')
    ),
    ADD CONSTRAINT ck_account_credential_state CHECK (
        credential_state IN ('ACTIVE', 'MUST_CHANGE_PASSWORD')
    );

CREATE TABLE business.account_activation (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    token_hash char(64) NOT NULL,
    token_version integer NOT NULL,
    status varchar(16) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_account_activation_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT uk_account_activation_token UNIQUE (token_hash),
    CONSTRAINT ck_account_activation_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_account_activation_version CHECK (token_version > 0),
    CONSTRAINT ck_account_activation_status CHECK (status IN ('AVAILABLE', 'CONSUMED', 'INVALIDATED', 'EXPIRED')),
    CONSTRAINT ck_account_activation_lifecycle CHECK (
        (status = 'CONSUMED' AND consumed_at IS NOT NULL)
        OR (status <> 'CONSUMED' AND consumed_at IS NULL)
    )
);

CREATE UNIQUE INDEX uk_account_activation_available
    ON business.account_activation (user_id) WHERE status = 'AVAILABLE';
CREATE INDEX idx_account_activation_expiry
    ON business.account_activation (expires_at) WHERE status = 'AVAILABLE';

CREATE TABLE business.account_security_audit (
    id bigint PRIMARY KEY,
    actor_user_id bigint NOT NULL,
    target_user_id bigint NOT NULL,
    event_type varchar(40) NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_account_security_audit_actor FOREIGN KEY (actor_user_id) REFERENCES business.account (id),
    CONSTRAINT fk_account_security_audit_target FOREIGN KEY (target_user_id) REFERENCES business.account (id),
    CONSTRAINT ck_account_security_audit_event CHECK (event_type IN (
        'USER_PROVISIONED', 'PASSWORD_RESET', 'USER_DISABLED', 'USER_RESTORED',
        'FIRST_LOGIN_COMPLETED', 'LEGACY_PASSWORD_UPGRADED'
    )),
    CONSTRAINT ck_account_security_audit_details CHECK (
        jsonb_typeof(details) = 'object' AND octet_length(details::text) <= 4096
    )
);

CREATE INDEX idx_account_security_audit_target
    ON business.account_security_audit (target_user_id, created_at DESC, id DESC);

ALTER TABLE business.point_ledger DROP CONSTRAINT ck_point_ledger_entry_type;
ALTER TABLE business.point_ledger DROP CONSTRAINT ck_point_ledger_business_type;
ALTER TABLE business.point_ledger
    ADD CONSTRAINT ck_point_ledger_entry_type CHECK (
        entry_type IN ('TASK_FREEZE', 'TASK_SETTLE', 'TASK_RELEASE',
            'WORKFLOW_FREEZE', 'WORKFLOW_ALLOCATE', 'WORKFLOW_TASK_SETTLE',
            'WORKFLOW_TASK_RELEASE', 'WORKFLOW_SETTLE', 'WORKFLOW_RELEASE',
            'ADMIN_INITIAL_GRANT')
    ),
    ADD CONSTRAINT ck_point_ledger_business_type CHECK (
        business_type IN ('TASK', 'WORKFLOW', 'ACCOUNT_PROVISIONING')
    );
