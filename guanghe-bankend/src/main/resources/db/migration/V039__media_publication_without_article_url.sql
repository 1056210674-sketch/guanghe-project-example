-- A signed upstream publication receipt is a terminal business result even when return_info
-- contains prose, an attachment reference, or no publicly usable article URL. Keep the separate
-- ck_media_submission_url constraint: any URL that is stored must still be a safe public link.
ALTER TABLE business.media_submission
    DROP CONSTRAINT ck_media_submission_terminal_fields;

ALTER TABLE business.media_submission
    ADD CONSTRAINT ck_media_submission_terminal_fields CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL AND stable_error_code IS NULL)
        OR (status IN ('REJECTED','FAILED') AND published_url IS NULL AND rejected_at IS NOT NULL
            AND stable_error_code IS NOT NULL)
        OR (status NOT IN ('PUBLISHED','REJECTED','FAILED')
            AND published_at IS NULL AND rejected_at IS NULL)
    );
