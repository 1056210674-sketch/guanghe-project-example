CREATE TABLE business.business_space (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    name varchar(100) NOT NULL,
    name_normalized varchar(100) NOT NULL,
    brand_name varchar(100) NOT NULL,
    brand_profile jsonb NOT NULL DEFAULT '{}'::jsonb,
    logo_asset_id bigint,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_business_space_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT uk_business_space_user_name UNIQUE (user_id, name_normalized),
    CONSTRAINT uk_business_space_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_business_space_brand_profile CHECK (jsonb_typeof(brand_profile) = 'object'),
    CONSTRAINT ck_business_space_version CHECK (version > 0)
);

CREATE INDEX idx_business_space_user_created
    ON business.business_space (user_id, created_at DESC);

CREATE TABLE business.space_brand_alias (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    alias varchar(100) NOT NULL,
    alias_normalized varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_space_brand_alias_space FOREIGN KEY (space_id, user_id)
        REFERENCES business.business_space (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uk_space_brand_alias UNIQUE (space_id, alias_normalized)
);

CREATE INDEX idx_space_brand_alias_user_space
    ON business.space_brand_alias (user_id, space_id);

CREATE TABLE business.product (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    name varchar(150) NOT NULL,
    name_normalized varchar(150) NOT NULL,
    category varchar(100),
    model_spec varchar(150),
    selling_points jsonb NOT NULL DEFAULT '[]'::jsonb,
    parameters jsonb NOT NULL DEFAULT '{}'::jsonb,
    audience jsonb NOT NULL DEFAULT '[]'::jsonb,
    usage_scenarios jsonb NOT NULL DEFAULT '[]'::jsonb,
    prohibited_claims jsonb NOT NULL DEFAULT '[]'::jsonb,
    primary_asset_id bigint,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_product_space FOREIGN KEY (space_id, user_id)
        REFERENCES business.business_space (id, user_id),
    CONSTRAINT uk_product_space_name UNIQUE (space_id, name_normalized),
    CONSTRAINT uk_product_id_user_space UNIQUE (id, user_id, space_id),
    CONSTRAINT ck_product_selling_points CHECK (jsonb_typeof(selling_points) = 'array'),
    CONSTRAINT ck_product_parameters CHECK (jsonb_typeof(parameters) = 'object'),
    CONSTRAINT ck_product_audience CHECK (jsonb_typeof(audience) = 'array'),
    CONSTRAINT ck_product_usage_scenarios CHECK (jsonb_typeof(usage_scenarios) = 'array'),
    CONSTRAINT ck_product_prohibited_claims CHECK (jsonb_typeof(prohibited_claims) = 'array'),
    CONSTRAINT ck_product_version CHECK (version > 0)
);

CREATE INDEX idx_product_user_space_created
    ON business.product (user_id, space_id, created_at DESC);

CREATE TABLE business.product_alias (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    product_id bigint NOT NULL,
    alias varchar(150) NOT NULL,
    alias_normalized varchar(150) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_product_alias_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT uk_product_alias_space UNIQUE (space_id, alias_normalized)
);

CREATE INDEX idx_product_alias_user_product
    ON business.product_alias (user_id, product_id);

CREATE TABLE business.competitor (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    name varchar(150) NOT NULL,
    name_normalized varchar(150) NOT NULL,
    competitor_type varchar(24) NOT NULL,
    description varchar(1000),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_competitor_space FOREIGN KEY (space_id, user_id)
        REFERENCES business.business_space (id, user_id),
    CONSTRAINT uk_competitor_space_name UNIQUE (space_id, name_normalized),
    CONSTRAINT uk_competitor_id_user_space UNIQUE (id, user_id, space_id),
    CONSTRAINT ck_competitor_type CHECK (competitor_type IN ('DIRECT', 'INDIRECT', 'SUBSTITUTE')),
    CONSTRAINT ck_competitor_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_competitor_version CHECK (version > 0)
);

CREATE INDEX idx_competitor_user_space_created
    ON business.competitor (user_id, space_id, created_at DESC);

CREATE TABLE business.competitor_alias (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    competitor_id bigint NOT NULL,
    alias varchar(150) NOT NULL,
    alias_normalized varchar(150) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_competitor_alias_competitor FOREIGN KEY (competitor_id, user_id, space_id)
        REFERENCES business.competitor (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT uk_competitor_alias_space UNIQUE (space_id, alias_normalized)
);

CREATE INDEX idx_competitor_alias_user_competitor
    ON business.competitor_alias (user_id, competitor_id);
