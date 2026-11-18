ALTER TABLE business.business_task
    ADD CONSTRAINT uk_business_task_id_user_space UNIQUE (id, user_id, space_id);

CREATE TABLE business.asset (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    primary_product_id bigint,
    asset_type varchar(32) NOT NULL,
    source_type varchar(32) NOT NULL,
    source_task_id bigint,
    name varchar(255) NOT NULL,
    object_key varchar(1024) NOT NULL,
    content_type varchar(128) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 char(64) NOT NULL,
    width integer,
    height integer,
    duration_seconds numeric(12, 3),
    status varchar(32) NOT NULL,
    safety_status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_asset_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT fk_asset_space FOREIGN KEY (space_id, user_id)
        REFERENCES business.business_space (id, user_id),
    CONSTRAINT fk_asset_primary_product FOREIGN KEY (primary_product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id)
        ON DELETE SET NULL (primary_product_id),
    CONSTRAINT fk_asset_source_task FOREIGN KEY (source_task_id, user_id, space_id)
        REFERENCES business.business_task (id, user_id, space_id),
    CONSTRAINT uk_asset_owner UNIQUE (id, user_id, space_id),
    CONSTRAINT uk_asset_object_key UNIQUE (object_key),
    CONSTRAINT uk_asset_source_task UNIQUE (source_task_id),
    CONSTRAINT ck_asset_type CHECK (asset_type IN ('IMAGE', 'VIDEO', 'ARTICLE', 'GEO_REPORT')),
    CONSTRAINT ck_asset_source_type CHECK (source_type IN ('GENERATED', 'UPLOADED', 'IMPORTED')),
    CONSTRAINT ck_asset_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_asset_object_key CHECK (
        object_key LIKE 'zhixiaohe/%' AND object_key !~ '(^|/)\.\.(/|$)'
    ),
    CONSTRAINT ck_asset_content_type CHECK (content_type ~ '^[a-z0-9.+-]+/[a-z0-9.+-]+$'),
    CONSTRAINT ck_asset_size CHECK (size_bytes > 0),
    CONSTRAINT ck_asset_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_asset_dimensions CHECK (
        (asset_type = 'IMAGE' AND width BETWEEN 8 AND 8192 AND height BETWEEN 8 AND 8192
            AND duration_seconds IS NULL)
        OR (asset_type <> 'IMAGE' AND width IS NULL AND height IS NULL)
    ),
    CONSTRAINT ck_asset_duration CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    CONSTRAINT ck_asset_status CHECK (
        status IN ('UPLOADING', 'SCANNING', 'READY', 'SAFETY_REVIEW', 'HIDDEN',
                   'REJECTED', 'FAILED', 'DELETING')
    ),
    CONSTRAINT ck_asset_safety_status CHECK (
        safety_status IN ('PENDING', 'APPROVED', 'TEST_BYPASS', 'REJECTED', 'UNAVAILABLE')
    ),
    CONSTRAINT ck_asset_ready_safety CHECK (
        status <> 'READY' OR safety_status IN ('APPROVED', 'TEST_BYPASS')
    ),
    CONSTRAINT ck_asset_generated_source CHECK (
        source_type <> 'GENERATED' OR source_task_id IS NOT NULL
    )
);

CREATE INDEX idx_asset_user_created ON business.asset (user_id, created_at DESC, id DESC);
CREATE INDEX idx_asset_user_space_created ON business.asset (user_id, space_id, created_at DESC, id DESC);
CREATE INDEX idx_asset_user_status ON business.asset (user_id, status, asset_type);

CREATE TABLE business.asset_product_relation (
    asset_id bigint NOT NULL,
    product_id bigint NOT NULL,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_asset_product_relation PRIMARY KEY (asset_id, product_id),
    CONSTRAINT fk_asset_product_relation_asset FOREIGN KEY (asset_id, user_id, space_id)
        REFERENCES business.asset (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT fk_asset_product_relation_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id) ON DELETE CASCADE
);

CREATE INDEX idx_asset_product_relation_product
    ON business.asset_product_relation (user_id, product_id, asset_id);

CREATE TABLE business.asset_ingestion_work (
    id bigint PRIMARY KEY,
    asset_id bigint NOT NULL,
    task_id bigint NOT NULL,
    execution_id bigint NOT NULL,
    execution_no integer NOT NULL,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    product_id bigint,
    artifact_index integer NOT NULL,
    provider_code varchar(64) NOT NULL,
    provider_model varchar(128) NOT NULL,
    provider_request_id uuid NOT NULL,
    provider_task_id varchar(256) NOT NULL,
    provider_url varchar(4096),
    provider_url_expires_at timestamptz,
    expected_content_type varchar(128) NOT NULL,
    expected_size_bytes bigint,
    expected_sha256 char(64),
    expected_width integer NOT NULL,
    expected_height integer NOT NULL,
    object_key varchar(1024) NOT NULL,
    status varchar(24) NOT NULL,
    attempt integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL,
    available_at timestamptz NOT NULL,
    lease_expires_at timestamptz,
    claim_token uuid,
    last_error_code varchar(64),
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_asset_ingestion_task FOREIGN KEY (task_id, user_id, space_id)
        REFERENCES business.business_task (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT fk_asset_ingestion_execution FOREIGN KEY (execution_id, user_id, task_id)
        REFERENCES business.task_execution (id, user_id, task_id),
    CONSTRAINT fk_asset_ingestion_space FOREIGN KEY (space_id, user_id)
        REFERENCES business.business_space (id, user_id),
    CONSTRAINT fk_asset_ingestion_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id),
    CONSTRAINT uk_asset_ingestion_task_artifact UNIQUE (task_id, artifact_index),
    CONSTRAINT uk_asset_ingestion_execution_artifact UNIQUE (execution_id, artifact_index),
    CONSTRAINT uk_asset_ingestion_asset UNIQUE (asset_id),
    CONSTRAINT uk_asset_ingestion_object_key UNIQUE (object_key),
    CONSTRAINT ck_asset_ingestion_execution_no CHECK (execution_no > 0),
    CONSTRAINT ck_asset_ingestion_artifact_index CHECK (artifact_index = 1),
    CONSTRAINT ck_asset_ingestion_provider CHECK (
        provider_code = 'QWEN_IMAGE' AND provider_model = 'qwen-image-3.0'
    ),
    CONSTRAINT ck_asset_ingestion_provider_task_id CHECK (
        length(provider_task_id) BETWEEN 1 AND 256
        AND provider_task_id ~ '^[A-Za-z0-9_-]+$'
    ),
    CONSTRAINT ck_asset_ingestion_provider_url CHECK (
        (
            status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT')
            AND provider_url IS NOT NULL
            AND provider_url ~ '^https://[^[:space:]]+$'
            AND octet_length(provider_url) <= 4096
            AND provider_url_expires_at IS NOT NULL
        )
        OR (
            status IN ('SUCCEEDED', 'FAILED')
            AND provider_url IS NULL
            AND provider_url_expires_at IS NULL
        )
    ),
    CONSTRAINT ck_asset_ingestion_content_type CHECK (expected_content_type = 'image/png'),
    CONSTRAINT ck_asset_ingestion_expected_size CHECK (
        expected_size_bytes IS NULL OR expected_size_bytes > 0
    ),
    CONSTRAINT ck_asset_ingestion_expected_sha256 CHECK (
        expected_sha256 IS NULL OR expected_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_asset_ingestion_dimensions CHECK (
        expected_width BETWEEN 8 AND 8192 AND expected_height BETWEEN 8 AND 8192
    ),
    CONSTRAINT ck_asset_ingestion_object_key CHECK (
        object_key LIKE 'zhixiaohe/%' AND object_key !~ '(^|/)\.\.(/|$)'
    ),
    CONSTRAINT ck_asset_ingestion_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT ck_asset_ingestion_attempts CHECK (
        attempt >= 0 AND max_attempts > 0 AND attempt <= max_attempts
    ),
    CONSTRAINT ck_asset_ingestion_lease CHECK (
        (status = 'PROCESSING' AND lease_expires_at IS NOT NULL AND claim_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_expires_at IS NULL AND claim_token IS NULL)
    ),
    CONSTRAINT ck_asset_ingestion_error_code CHECK (
        last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    )
);

CREATE INDEX idx_asset_ingestion_claim
    ON business.asset_ingestion_work (status, available_at, id)
    WHERE status IN ('PENDING', 'RETRY_WAIT', 'PROCESSING');
