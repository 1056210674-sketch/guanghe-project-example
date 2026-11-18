-- Add the explicitly selected mask-edit provider without changing existing IMAGE/VIDEO rules.
ALTER TABLE business.asset_ingestion_work
    ALTER COLUMN expected_content_type DROP NOT NULL,
    DROP CONSTRAINT ck_asset_ingestion_provider,
    DROP CONSTRAINT ck_asset_ingestion_provider_url,
    DROP CONSTRAINT ck_asset_ingestion_content_type,
    DROP CONSTRAINT ck_asset_ingestion_dimensions,
    DROP CONSTRAINT ck_asset_ingestion_object_key,
    DROP CONSTRAINT ck_asset_ingestion_artifact_index;

ALTER TABLE business.asset_ingestion_work
    ADD CONSTRAINT ck_asset_ingestion_provider CHECK (
        (provider_code = 'QWEN_IMAGE' AND provider_model = 'qwen-image-3.0')
        OR (provider_code = 'WAN_IMAGE_EDIT' AND provider_model = 'wanx2.1-imageedit')
        OR (provider_code = 'WAN_VIDEO' AND provider_model = 'wan3.0-video')
    ),
    ADD CONSTRAINT ck_asset_ingestion_provider_url CHECK (
        (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT')
            AND provider_url IS NOT NULL AND provider_url ~ '^https://[^[:space:]]+$'
            AND octet_length(provider_url) <= 4096
            AND ((provider_code IN ('QWEN_IMAGE', 'WAN_IMAGE_EDIT')
                    AND provider_url_expires_at IS NOT NULL AND ingestion_deadline_at IS NULL)
                OR (provider_code = 'WAN_VIDEO' AND provider_url_expires_at IS NULL
                    AND ingestion_deadline_at IS NOT NULL)))
        OR (status IN ('SUCCEEDED', 'FAILED') AND provider_url IS NULL
            AND provider_url_expires_at IS NULL AND ingestion_deadline_at IS NULL)
    ),
    ADD CONSTRAINT ck_asset_ingestion_content_type CHECK (
        (provider_code = 'QWEN_IMAGE' AND expected_content_type IS NOT NULL AND expected_content_type = 'image/png')
        OR (provider_code = 'WAN_IMAGE_EDIT' AND expected_content_type IS NULL)
        OR (provider_code = 'WAN_VIDEO' AND expected_content_type IS NOT NULL AND expected_content_type = 'video/mp4')
    ),
    ADD CONSTRAINT ck_asset_ingestion_dimensions CHECK (
        (provider_code = 'QWEN_IMAGE' AND expected_width IS NOT NULL AND expected_height IS NOT NULL
            AND expected_width BETWEEN 8 AND 8192 AND expected_height BETWEEN 8 AND 8192
            AND expected_duration_seconds IS NULL AND expected_aspect_ratio IS NULL AND expected_resolution IS NULL)
        OR (provider_code = 'WAN_IMAGE_EDIT' AND expected_width IS NULL AND expected_height IS NULL
            AND expected_duration_seconds IS NULL AND expected_aspect_ratio IS NULL AND expected_resolution IS NULL)
        OR (provider_code = 'WAN_VIDEO' AND expected_width IS NULL AND expected_height IS NULL
            AND expected_duration_seconds > 0 AND expected_duration_seconds <= 30
            AND expected_aspect_ratio IN ('adaptive','16:9','4:3','1:1','3:4','9:16')
            AND expected_resolution IN ('480P','720P','1080P'))
    ),
    ADD CONSTRAINT ck_asset_ingestion_object_key CHECK (
        object_key LIKE 'zhixiaohe/%' AND object_key !~ '(^|/)\.\.(/|$)'
        AND ((provider_code IN ('QWEN_IMAGE', 'WAN_IMAGE_EDIT') AND object_key LIKE '%.png')
            OR (provider_code = 'WAN_VIDEO' AND object_key LIKE '%.mp4'))
    ),
    ADD CONSTRAINT ck_asset_ingestion_artifact_index CHECK (
        (provider_code IN ('QWEN_IMAGE', 'WAN_IMAGE_EDIT') AND artifact_index BETWEEN 1 AND 4)
        OR (provider_code = 'WAN_VIDEO' AND artifact_index = 1)
    );
