CREATE TABLE business.asset_upload_request (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    asset_id bigint NOT NULL,
    space_id bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uk_asset_upload_request_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT uk_asset_upload_request_asset UNIQUE (asset_id),
    CONSTRAINT fk_asset_upload_request_user FOREIGN KEY (user_id)
        REFERENCES business.account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_asset_upload_request_asset FOREIGN KEY (asset_id, user_id, space_id)
        REFERENCES business.asset (id, user_id, space_id) ON DELETE RESTRICT,
    CONSTRAINT ck_asset_upload_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE business.asset_upload_request IS
    'Idempotency receipt for bounded Browser image uploads; binary data and URLs are never stored here.';
