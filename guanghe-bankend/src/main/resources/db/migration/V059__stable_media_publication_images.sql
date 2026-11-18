-- A confirmed order owns an independent public copy. Existing signed-link pins remain unchanged.
ALTER TABLE business.media_submission_image
    ADD COLUMN published_object_key varchar(256),
    ADD COLUMN publication_ready_at timestamptz,
    ADD CONSTRAINT ck_media_publication_key CHECK(published_object_key IS NULL OR
        published_object_key ~ '^media-public/[a-z][a-z0-9_-]{0,31}/[0-9a-f]{64}\.(png|jpg)$'),
    ADD CONSTRAINT ck_media_publication_ready CHECK(publication_ready_at IS NULL OR
        (published_object_key IS NOT NULL AND publication_ready_at >= created_at)),
    ADD CONSTRAINT uk_media_publication_key UNIQUE(published_object_key);

CREATE FUNCTION business.protect_media_publication_pin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'confirmed media image pins cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF ROW(NEW.submission_id,NEW.asset_id,NEW.user_id,NEW.space_id,NEW.object_key,
           NEW.sha256,NEW.size_bytes,NEW.content_type,NEW.created_at,NEW.published_object_key)
       IS DISTINCT FROM
       ROW(OLD.submission_id,OLD.asset_id,OLD.user_id,OLD.space_id,OLD.object_key,
           OLD.sha256,OLD.size_bytes,OLD.content_type,OLD.created_at,OLD.published_object_key)
       OR (OLD.publication_ready_at IS NOT NULL
           AND NEW.publication_ready_at IS DISTINCT FROM OLD.publication_ready_at) THEN
        RAISE EXCEPTION 'confirmed media image pins are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_media_publication_pin BEFORE UPDATE OR DELETE
    ON business.media_submission_image FOR EACH ROW EXECUTE FUNCTION business.protect_media_publication_pin();
