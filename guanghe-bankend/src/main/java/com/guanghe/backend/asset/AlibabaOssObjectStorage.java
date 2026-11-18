package com.guanghe.backend.asset;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.ObjectPermission;
import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Date;

final class AlibabaOssObjectStorage implements ObjectStorage {
    private final OSS client;
    private final AssetIngestionProperties properties;

    AlibabaOssObjectStorage(OSS client, AssetIngestionProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public StoredObject storeOrConfirm(String objectKey, Path source, String contentType,
                                       long sizeBytes, String sha256) {
        boolean createdByCaller = false;
        try {
            if (client.doesObjectExist(properties.getOss().getBucket(), objectKey)) {
                return verify(objectKey, contentType, sizeBytes, sha256, false);
            }
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(sizeBytes);
            metadata.setContentType(contentType);
            metadata.addUserMetadata("sha256", sha256);
            // Asset privacy must not inherit a legacy bucket's public-read ACL. Apply this
            // atomically with the new object; only publishImageCopy may expose its own copy.
            metadata.setObjectAcl(CannedAccessControlList.Private);
            PutObjectRequest request = new PutObjectRequest(properties.getOss().getBucket(), objectKey, source.toFile());
            request.setMetadata(metadata);
            client.putObject(request);
            createdByCaller = true;
            return verify(objectKey, contentType, sizeBytes, sha256, true);
        } catch (AssetIngestionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AssetIngestionException("OSS_UPLOAD_FAILED", true, createdByCaller, exception);
        }
    }

    private StoredObject verify(String objectKey, String contentType, long sizeBytes, String sha256,
                                boolean createdByCaller) {
        ObjectMetadata metadata = client.getObjectMetadata(properties.getOss().getBucket(), objectKey);
        String storedHash = metadata.getUserMetadata().get("sha256");
        if (metadata.getContentLength() != sizeBytes || !contentType.equals(metadata.getContentType())
                || !sha256.equals(storedHash)) {
            throw new AssetIngestionException("OSS_OBJECT_VERIFICATION_FAILED", false, createdByCaller);
        }
        return new StoredObject(objectKey, contentType, sizeBytes, sha256, createdByCaller);
    }

    @Override
    public URI signedGetUrl(String objectKey, OffsetDateTime expiresAt) {
        URI uri = URI.create(client.generatePresignedUrl(properties.getOss().getBucket(), objectKey,
                Date.from(expiresAt.toInstant()), HttpMethod.GET).toString());
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new AssetIngestionException("OSS_SIGNED_URL_NOT_HTTPS", false);
        }
        return uri;
    }

    @Override
    public void readVerified(String objectKey, Path target, long expectedSize, String expectedSha256) {
        if (expectedSize < 1 || expectedSize > properties.getMaxImageBytes()) {
            throw new AssetIngestionException("OSS_READ_SIZE_INVALID", false);
        }
        try (var object = client.getObject(properties.getOss().getBucket(), objectKey);
             var input = object.getObjectContent();
             var output = java.nio.file.Files.newOutputStream(target,
                     java.nio.file.StandardOpenOption.CREATE_NEW)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long total = 0;
            long deadline = System.nanoTime() + properties.getTotalTimeout().toNanos();
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (System.nanoTime() > deadline) throw new AssetIngestionException("OSS_READ_TIMEOUT", true);
                if (count == 0) continue;
                total += count;
                if (total > expectedSize) throw new AssetIngestionException("OSS_READ_SIZE_INVALID", false);
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
            if (total != expectedSize || !java.util.HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) {
                throw new AssetIngestionException("OSS_OBJECT_VERIFICATION_FAILED", false);
            }
        } catch (AssetIngestionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AssetIngestionException("OSS_READ_FAILED", true, exception);
        }
    }

    @Override
    public URI publishImageCopy(String objectKey, Path source, String contentType, long sizeBytes, String sha256) {
        String prefix = "media-public/" + properties.getEnvironment() + "/";
        String extension = "image/png".equals(contentType) ? ".png" : ".jpg";
        if (objectKey == null || !objectKey.startsWith(prefix)
                || !objectKey.substring(prefix.length()).matches("[0-9a-f]{64}\\.(png|jpg)")
                || !objectKey.endsWith(extension) || !java.util.Set.of("image/png", "image/jpeg").contains(contentType)
                || sizeBytes < 1 || sizeBytes > 10L * 1024 * 1024
                || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new AssetIngestionException("OSS_PUBLICATION_TARGET_INVALID", false);
        }
        URI endpoint = URI.create(properties.getOss().getEndpoint());
        String host = properties.getOss().getBucket() + "." + endpoint.getHost();
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || !host.endsWith(".aliyuncs.com")
                || host.contains("-internal.") || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)) {
            throw new AssetIngestionException("OSS_PUBLICATION_ENDPOINT_INVALID", false);
        }
        // Never alter the source object's ACL or the bucket ACL. IAM/block-public-access failures
        // propagate to the caller before supplier.submit; they are not bypassed.
        try {
            storeOrConfirm(objectKey, source, contentType, sizeBytes, sha256);
            client.setObjectAcl(properties.getOss().getBucket(), objectKey, CannedAccessControlList.PublicRead);
            if (client.getObjectAcl(properties.getOss().getBucket(), objectKey).getPermission() != ObjectPermission.PublicRead) {
                throw new AssetIngestionException("OSS_PUBLICATION_ACL_INVALID", false);
            }
            URI url = URI.create("https://" + host + "/" + objectKey);
            verifyPublicCopy(url, contentType, sizeBytes, sha256);
            return url;
        } catch (AssetIngestionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AssetIngestionException("OSS_PUBLICATION_FAILED", false);
        }
    }

    private void verifyPublicCopy(URI url, String contentType, long expectedSize, String expectedHash) {
        java.net.HttpURLConnection connection = null;
        try {
            // An unsigned request proves the supplier can fetch the copy. No redirects, credentials,
            // browser-supplied URL, bucket policy edits or public listing permissions are involved.
            connection = (java.net.HttpURLConnection) url.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
            connection.setReadTimeout(Math.toIntExact(properties.getReadTimeout().toMillis()));
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != 200 || !contentType.equals(connection.getContentType())
                    || connection.getContentLengthLong() != expectedSize) {
                throw new AssetIngestionException("OSS_PUBLICATION_NOT_READABLE", false);
            }
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            long total = 0;
            long deadline = System.nanoTime() + properties.getTotalTimeout().toNanos();
            try (var input = connection.getInputStream()) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (System.nanoTime() > deadline || (total += count) > expectedSize) {
                        throw new AssetIngestionException("OSS_PUBLICATION_VERIFICATION_FAILED", false);
                    }
                    digest.update(buffer, 0, count);
                }
            }
            if (total != expectedSize || !java.util.HexFormat.of().formatHex(digest.digest()).equals(expectedHash)) {
                throw new AssetIngestionException("OSS_PUBLICATION_VERIFICATION_FAILED", false);
            }
        } catch (AssetIngestionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AssetIngestionException("OSS_PUBLICATION_VERIFICATION_FAILED", false);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey != null && objectKey.startsWith("media-public/")) {
            throw new AssetIngestionException("OSS_PUBLICATION_IMMUTABLE", false);
        }
        client.deleteObject(properties.getOss().getBucket(), objectKey);
    }
}
