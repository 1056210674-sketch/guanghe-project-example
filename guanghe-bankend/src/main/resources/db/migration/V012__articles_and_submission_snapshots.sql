CREATE TABLE business.article (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    product_id bigint,
    title varchar(300) NOT NULL DEFAULT '',
    body_html text NOT NULL DEFAULT '',
    body_text text NOT NULL DEFAULT '',
    outline jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    source_task_id bigint,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_article_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT fk_article_space FOREIGN KEY (space_id, user_id)
        REFERENCES business.business_space (id, user_id),
    CONSTRAINT fk_article_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id),
    CONSTRAINT fk_article_source_task FOREIGN KEY (source_task_id, user_id, space_id)
        REFERENCES business.business_task (id, user_id, space_id),
    CONSTRAINT uk_article_owner UNIQUE (id, user_id),
    CONSTRAINT uk_article_space_owner UNIQUE (id, user_id, space_id),
    CONSTRAINT ck_article_title CHECK (length(title) <= 300),
    CONSTRAINT ck_article_body_html CHECK (octet_length(body_html) <= 524288),
    CONSTRAINT ck_article_body_text CHECK (octet_length(body_text) <= 524288),
    CONSTRAINT ck_article_outline CHECK (
        jsonb_typeof(outline) = 'array' AND octet_length(outline::text) <= 131072
    ),
    CONSTRAINT ck_article_status CHECK (
        status IN ('DRAFT','OUTLINING','BODY_QUEUED','BODY_GENERATING','COMPLETED','FAILED','ARCHIVED')
    ),
    CONSTRAINT ck_article_version CHECK (version > 0)
);

CREATE INDEX idx_article_user_created
    ON business.article (user_id, created_at DESC, id DESC);
CREATE INDEX idx_article_user_space_status
    ON business.article (user_id, space_id, status, updated_at DESC, id DESC);

CREATE TABLE business.article_asset_relation (
    id bigint PRIMARY KEY,
    article_id bigint NOT NULL,
    asset_id bigint NOT NULL,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    position_key varchar(128) NOT NULL,
    sort_order integer NOT NULL,
    alt_text varchar(500),
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_article_asset_article FOREIGN KEY (article_id, user_id, space_id)
        REFERENCES business.article (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT fk_article_asset_asset FOREIGN KEY (asset_id, user_id, space_id)
        REFERENCES business.asset (id, user_id, space_id),
    CONSTRAINT uk_article_asset UNIQUE (article_id, asset_id),
    CONSTRAINT uk_article_asset_position UNIQUE (article_id, position_key),
    CONSTRAINT uk_article_asset_order UNIQUE (article_id, sort_order),
    CONSTRAINT ck_article_asset_position CHECK (
        position_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    ),
    CONSTRAINT ck_article_asset_order CHECK (sort_order BETWEEN 1 AND 10),
    CONSTRAINT ck_article_asset_alt CHECK (alt_text IS NULL OR length(btrim(alt_text)) BETWEEN 1 AND 500)
);

CREATE INDEX idx_article_asset_asset ON business.article_asset_relation (asset_id, article_id);

CREATE TABLE business.article_submission_snapshot (
    id bigint PRIMARY KEY,
    article_id bigint NOT NULL,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    product_id bigint,
    article_version integer NOT NULL,
    title varchar(300) NOT NULL,
    body_html text NOT NULL,
    body_text text NOT NULL,
    body_hash char(64) NOT NULL,
    outline jsonb NOT NULL,
    asset_manifest jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_article_snapshot_article FOREIGN KEY (article_id, user_id, space_id)
        REFERENCES business.article (id, user_id, space_id),
    CONSTRAINT fk_article_snapshot_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id),
    CONSTRAINT uk_article_snapshot_owner UNIQUE (id, user_id, article_id),
    CONSTRAINT uk_article_snapshot_version UNIQUE (article_id, article_version),
    CONSTRAINT ck_article_snapshot_version CHECK (article_version > 0),
    CONSTRAINT ck_article_snapshot_title CHECK (length(title) <= 300),
    CONSTRAINT ck_article_snapshot_body_html CHECK (octet_length(body_html) <= 524288),
    CONSTRAINT ck_article_snapshot_body_text CHECK (octet_length(body_text) <= 524288),
    CONSTRAINT ck_article_snapshot_hash CHECK (body_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_article_snapshot_outline CHECK (
        jsonb_typeof(outline) = 'array' AND octet_length(outline::text) <= 131072
    ),
    CONSTRAINT ck_article_snapshot_assets CHECK (
        jsonb_typeof(asset_manifest) = 'array' AND jsonb_array_length(asset_manifest) <= 10
        AND octet_length(asset_manifest::text) <= 65536
    )
);

CREATE INDEX idx_article_snapshot_user_article_created
    ON business.article_submission_snapshot (user_id, article_id, created_at DESC, id DESC);
CREATE INDEX idx_business_task_article_created
    ON business.business_task (user_id, created_at DESC, id DESC)
    WHERE task_type = 'ARTICLE';
CREATE INDEX idx_task_draft_article_created
    ON business.task_draft (user_id, created_at DESC, id DESC)
    WHERE task_type = 'ARTICLE';
