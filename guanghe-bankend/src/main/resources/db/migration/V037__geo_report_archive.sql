ALTER TABLE business.geo_report
    ADD COLUMN archived_at timestamptz;

CREATE INDEX idx_geo_report_user_archive_generated
    ON business.geo_report (user_id, archived_at, generated_at DESC, id DESC);
