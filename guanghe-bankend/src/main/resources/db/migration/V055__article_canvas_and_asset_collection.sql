-- A collection entry points to the same editable article, never to a duplicate body/blob.
ALTER TABLE business.article
    ADD COLUMN saved_to_assets_at timestamptz,
    ADD COLUMN saved_article_version integer,
    ADD CONSTRAINT article_asset_collection_check CHECK (
        (saved_to_assets_at IS NULL AND saved_article_version IS NULL) OR
        (saved_to_assets_at IS NOT NULL AND saved_article_version BETWEEN 1 AND version));
CREATE INDEX article_asset_collection_idx ON business.article(user_id,space_id,created_at DESC,id DESC)
    WHERE saved_to_assets_at IS NOT NULL AND status='COMPLETED';

-- Non-authoritative partial provider output, fenced by the current paid-task execution.
-- Keeping this separate avoids altering the complete body or locking the parent preparation.
CREATE TABLE business.article_stream_preview (
    task_id bigint PRIMARY KEY REFERENCES business.business_task(id) ON DELETE CASCADE,
    execution_no integer NOT NULL CHECK (execution_no > 0),
    attempt integer NOT NULL CHECK (attempt BETWEEN 1 AND 10),
    sequence integer NOT NULL CHECK (sequence BETWEEN 1 AND 10000),
    markdown text NOT NULL CHECK (char_length(markdown) <= 120000),
    updated_at timestamptz NOT NULL
);
COMMENT ON TABLE business.article_stream_preview IS 'Untrusted, incomplete BODY streaming preview; never an article result, submission snapshot, or asset.';
