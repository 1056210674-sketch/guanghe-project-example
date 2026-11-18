CREATE TABLE business.geo_topic (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    product_id bigint,
    name varchar(100) NOT NULL,
    name_normalized varchar(100) NOT NULL,
    region varchar(100),
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_topic_user FOREIGN KEY (user_id)
        REFERENCES business.account (id),
    CONSTRAINT fk_geo_topic_space FOREIGN KEY (space_id, user_id)
        REFERENCES business.business_space (id, user_id),
    CONSTRAINT fk_geo_topic_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id),
    CONSTRAINT uk_geo_topic_owner UNIQUE (id, user_id, space_id),
    CONSTRAINT uk_geo_topic_space_name UNIQUE (user_id, space_id, name_normalized),
    CONSTRAINT ck_geo_topic_version CHECK (version > 0)
);

CREATE INDEX idx_geo_topic_user_created
    ON business.geo_topic (user_id, created_at DESC, id DESC);

CREATE INDEX idx_geo_topic_user_space_product
    ON business.geo_topic (user_id, space_id, product_id);

CREATE TABLE business.geo_keyword (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    topic_id bigint NOT NULL,
    sequence_no integer NOT NULL,
    keyword_type varchar(16) NOT NULL,
    keyword_text varchar(200) NOT NULL,
    text_normalized varchar(200) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_keyword_topic FOREIGN KEY (topic_id, user_id, space_id)
        REFERENCES business.geo_topic (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT uk_geo_keyword_topic_sequence UNIQUE (topic_id, sequence_no),
    CONSTRAINT uk_geo_keyword_topic_text UNIQUE (topic_id, text_normalized),
    CONSTRAINT ck_geo_keyword_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_geo_keyword_type CHECK (
        keyword_type IN ('CORE', 'DISTILLED', 'BRAND', 'SCENE')
    )
);

CREATE INDEX idx_geo_keyword_owner_topic
    ON business.geo_keyword (user_id, topic_id, sequence_no);

CREATE TABLE business.geo_question (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    topic_id bigint NOT NULL,
    sequence_no integer NOT NULL,
    category varchar(32) NOT NULL,
    question_text varchar(1000) NOT NULL,
    text_normalized varchar(1000) NOT NULL,
    source varchar(16) NOT NULL,
    enabled boolean NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_question_topic FOREIGN KEY (topic_id, user_id, space_id)
        REFERENCES business.geo_topic (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT uk_geo_question_topic_sequence UNIQUE (topic_id, sequence_no),
    CONSTRAINT uk_geo_question_topic_text UNIQUE (topic_id, text_normalized),
    CONSTRAINT ck_geo_question_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_geo_question_category CHECK (category = 'BRAND_DISCOVERY'),
    CONSTRAINT ck_geo_question_source CHECK (source = 'MANUAL')
);

CREATE INDEX idx_geo_question_owner_topic
    ON business.geo_question (user_id, topic_id, sequence_no);

CREATE TABLE business.geo_competitor_set (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    topic_id bigint NOT NULL,
    version_no integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_competitor_set_topic FOREIGN KEY (topic_id, user_id, space_id)
        REFERENCES business.geo_topic (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT uk_geo_competitor_set_owner UNIQUE (id, user_id, space_id, topic_id),
    CONSTRAINT uk_geo_competitor_set_version UNIQUE (topic_id, version_no),
    CONSTRAINT ck_geo_competitor_set_version CHECK (version_no > 0)
);

CREATE INDEX idx_geo_competitor_set_current
    ON business.geo_competitor_set (topic_id, version_no DESC);

CREATE TABLE business.geo_competitor_set_member (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    topic_id bigint NOT NULL,
    competitor_set_id bigint NOT NULL,
    competitor_id bigint NOT NULL,
    sequence_no integer NOT NULL,
    competitor_name_snapshot varchar(150) NOT NULL,
    competitor_type_snapshot varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_geo_competitor_member_set
        FOREIGN KEY (competitor_set_id, user_id, space_id, topic_id)
        REFERENCES business.geo_competitor_set (id, user_id, space_id, topic_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_geo_competitor_member_competitor
        FOREIGN KEY (competitor_id, user_id, space_id)
        REFERENCES business.competitor (id, user_id, space_id),
    CONSTRAINT uk_geo_competitor_member_sequence UNIQUE (competitor_set_id, sequence_no),
    CONSTRAINT uk_geo_competitor_member_competitor UNIQUE (competitor_set_id, competitor_id),
    CONSTRAINT ck_geo_competitor_member_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_geo_competitor_member_type CHECK (
        competitor_type_snapshot IN ('DIRECT', 'INDIRECT', 'SUBSTITUTE')
    )
);

CREATE INDEX idx_geo_competitor_member_owner_topic
    ON business.geo_competitor_set_member (user_id, topic_id, competitor_set_id, sequence_no);
