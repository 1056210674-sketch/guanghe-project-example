ALTER TABLE business.article
    ADD COLUMN geo_report_id bigint;

ALTER TABLE business.article
    ADD CONSTRAINT fk_article_geo_report
        FOREIGN KEY (geo_report_id) REFERENCES business.geo_report (id) ON DELETE SET NULL;

CREATE INDEX idx_article_geo_report
    ON business.article (geo_report_id)
    WHERE geo_report_id IS NOT NULL;
