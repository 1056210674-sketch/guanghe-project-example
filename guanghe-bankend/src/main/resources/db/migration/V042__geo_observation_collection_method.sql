-- Capture actual adapter/search path for future comparisons. Historical evidence stays unknown.
ALTER TABLE business.geo_observation ADD COLUMN collection_method varchar(128);
ALTER TABLE business.geo_observation ADD CONSTRAINT ck_geo_observation_collection_method
    CHECK (collection_method IS NULL OR collection_method ~ '^[a-z][a-z0-9-]{0,127}$');

COMMENT ON COLUMN business.geo_observation.collection_method IS
    'Immutable versioned actual collection/search path, supplied by the executing adapter. NULL means unknown legacy metadata.';
