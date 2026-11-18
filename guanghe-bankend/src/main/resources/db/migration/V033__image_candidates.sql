-- Keep the provider's stable candidate slot on the permanent asset so retries cannot duplicate it.
ALTER TABLE business.asset ADD COLUMN source_artifact_index integer;

UPDATE business.asset SET source_artifact_index = 1 WHERE source_task_id IS NOT NULL;

ALTER TABLE business.asset
    DROP CONSTRAINT uk_asset_source_task,
    ADD CONSTRAINT uk_asset_source_task_artifact UNIQUE (source_task_id, source_artifact_index),
    ADD CONSTRAINT ck_asset_source_artifact_index CHECK (
        (source_task_id IS NULL AND source_artifact_index IS NULL)
        OR (source_task_id IS NOT NULL AND source_artifact_index IS NOT NULL AND (
            (asset_type = 'IMAGE' AND source_artifact_index BETWEEN 1 AND 4)
            OR (asset_type <> 'IMAGE' AND source_artifact_index = 1)
        ))
    );

ALTER TABLE business.asset_ingestion_work
    DROP CONSTRAINT ck_asset_ingestion_artifact_index,
    ADD CONSTRAINT ck_asset_ingestion_artifact_index CHECK (
        (provider_code = 'QWEN_IMAGE' AND artifact_index BETWEEN 1 AND 4)
        OR (provider_code = 'WAN_VIDEO' AND artifact_index = 1)
    );
