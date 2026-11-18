-- Browser uploads already validate PNG/JPEG bytes and MIME in AssetUploadService.
-- V007 predates uploads and only allowed generated PNGs, stranding valid JPEGs.
-- Broaden only the permanent IMAGE MIME constraint; provider ingestion, geometry,
-- safety, ownership and source/task constraints remain unchanged.
ALTER TABLE business.asset DROP CONSTRAINT ck_asset_media_content_type;
ALTER TABLE business.asset ADD CONSTRAINT ck_asset_media_content_type CHECK (
    (asset_type = 'IMAGE' AND content_type IN ('image/png', 'image/jpeg'))
    OR (asset_type = 'VIDEO' AND content_type = 'video/mp4')
    OR asset_type IN ('ARTICLE', 'GEO_REPORT')
);
