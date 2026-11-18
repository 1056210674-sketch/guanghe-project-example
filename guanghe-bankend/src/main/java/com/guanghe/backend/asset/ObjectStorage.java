package com.guanghe.backend.asset;

import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;

public interface ObjectStorage {
    StoredObject storeOrConfirm(String objectKey, Path source, String contentType,
                                long sizeBytes, String sha256);
    URI signedGetUrl(String objectKey, OffsetDateTime expiresAt);
    /** Read only a caller-authorized immutable object, with an exact size and digest bound. */
    default void readVerified(String objectKey, Path target, long expectedSize, String expectedSha256) {
        throw new AssetIngestionException("OSS_READ_UNAVAILABLE", false);
    }
    /** Only for a confirmed media order's independent, randomly named publication copy. */
    default URI publishImageCopy(String objectKey, Path source, String contentType, long sizeBytes, String sha256) {
        throw new AssetIngestionException("OSS_PUBLICATION_UNAVAILABLE", false);
    }
    void delete(String objectKey);

    record StoredObject(String objectKey, String contentType, long sizeBytes, String sha256,
                        boolean createdByCaller) { }
}
