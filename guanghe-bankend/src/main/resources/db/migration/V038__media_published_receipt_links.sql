-- The procurement receipt's return_info can contain several HTTP(S) article links.
-- return_file is an attachment and must never be mistaken for the published article URL.
ALTER TABLE business.media_submission
    ADD COLUMN published_urls jsonb NOT NULL DEFAULT '[]'::jsonb;

UPDATE business.media_submission
SET published_urls = jsonb_build_array(published_url)
WHERE published_url IS NOT NULL;

ALTER TABLE business.media_submission DROP CONSTRAINT ck_media_submission_url;
ALTER TABLE business.media_submission ADD CONSTRAINT ck_media_submission_url CHECK (
    (published_url IS NULL AND published_urls = '[]'::jsonb)
    OR (published_url IS NOT NULL
        AND length(published_url) <= 2048
        AND published_url ~* '^https?://[^/@?#[:space:]]+([/?#][^[:space:]]*)?$'
        AND jsonb_typeof(published_urls) = 'array'
        AND jsonb_array_length(published_urls) BETWEEN 1 AND 20
        AND length(published_urls::text) <= 16384
        AND published_urls ->> 0 = published_url)
);
