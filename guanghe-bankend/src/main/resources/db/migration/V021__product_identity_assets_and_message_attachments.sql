CREATE TABLE business.product_identity_asset (
    product_id bigint NOT NULL,
    asset_id bigint NOT NULL,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    role varchar(32) NOT NULL,
    sequence_no integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_product_identity_asset PRIMARY KEY (product_id, asset_id),
    CONSTRAINT fk_product_identity_asset_product FOREIGN KEY (product_id, user_id, space_id)
        REFERENCES business.product (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT fk_product_identity_asset_asset FOREIGN KEY (asset_id, user_id, space_id)
        REFERENCES business.asset (id, user_id, space_id) ON DELETE RESTRICT,
    CONSTRAINT uk_product_identity_asset_sequence UNIQUE (product_id, sequence_no),
    CONSTRAINT ck_product_identity_asset_role CHECK (
        role IN ('PRIMARY', 'WHITE_BACKGROUND', 'DETAIL', 'PACKAGE')
    ),
    CONSTRAINT ck_product_identity_asset_sequence CHECK (sequence_no BETWEEN 1 AND 20)
);

CREATE UNIQUE INDEX uk_product_identity_asset_primary
    ON business.product_identity_asset (product_id) WHERE role = 'PRIMARY';
CREATE INDEX idx_product_identity_asset_owner
    ON business.product_identity_asset (user_id, space_id, product_id, sequence_no);

CREATE TABLE business.conversation_message_asset (
    message_id bigint NOT NULL,
    asset_id bigint NOT NULL,
    user_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    space_id bigint NOT NULL,
    sequence_no integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_conversation_message_asset PRIMARY KEY (message_id, asset_id),
    CONSTRAINT fk_conversation_message_asset_message FOREIGN KEY (
        message_id, user_id, conversation_id
    ) REFERENCES business.conversation_message (id, user_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_message_asset_asset FOREIGN KEY (asset_id, user_id, space_id)
        REFERENCES business.asset (id, user_id, space_id) ON DELETE RESTRICT,
    CONSTRAINT uk_conversation_message_asset_sequence UNIQUE (message_id, sequence_no),
    CONSTRAINT ck_conversation_message_asset_sequence CHECK (sequence_no BETWEEN 1 AND 8)
);

CREATE INDEX idx_conversation_message_asset_owner
    ON business.conversation_message_asset (user_id, conversation_id, message_id, sequence_no);

ALTER TABLE business.product
    ADD CONSTRAINT fk_product_primary_asset FOREIGN KEY (primary_asset_id, user_id, space_id)
        REFERENCES business.asset (id, user_id, space_id) ON DELETE SET NULL (primary_asset_id);

COMMENT ON TABLE business.product_identity_asset IS
    'Ordered owned READY image references that identify one catalog product; no media URLs are persisted.';
COMMENT ON TABLE business.conversation_message_asset IS
    'Immutable current-message attachment provenance used by CURRENT_ATTACHMENTS creative tasks.';
