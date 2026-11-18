-- Private, confirmed-order image pins. No public URL or supplier credential is stored here.
ALTER TABLE business.media_submission
    ADD CONSTRAINT uk_media_submission_image_owner UNIQUE(id,user_id,space_id);

CREATE TABLE business.media_submission_image (
    submission_id bigint NOT NULL,
    asset_id bigint NOT NULL,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    object_key varchar(1024) NOT NULL,
    sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520),
    content_type varchar(128) NOT NULL CHECK(content_type IN ('image/png','image/jpeg')),
    url_expires_at timestamptz,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(submission_id,asset_id),
    FOREIGN KEY(submission_id,user_id,space_id) REFERENCES business.media_submission(id,user_id,space_id),
    FOREIGN KEY(asset_id,user_id,space_id) REFERENCES business.asset(id,user_id,space_id),
    CHECK(url_expires_at IS NULL OR url_expires_at > created_at)
);
CREATE INDEX idx_media_submission_image_asset ON business.media_submission_image(asset_id);

-- A submitted draft must continue to refer to the exact approved image bytes, even after editing the live article.
CREATE FUNCTION business.protect_submitted_image_bytes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.object_key,NEW.sha256,NEW.size_bytes,NEW.content_type)
       IS DISTINCT FROM ROW(OLD.object_key,OLD.sha256,OLD.size_bytes,OLD.content_type)
       AND EXISTS(SELECT 1 FROM business.media_submission_image WHERE asset_id=OLD.id) THEN
        RAISE EXCEPTION 'submitted image bytes are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_submitted_image_bytes BEFORE UPDATE ON business.asset
    FOR EACH ROW EXECUTE FUNCTION business.protect_submitted_image_bytes();
