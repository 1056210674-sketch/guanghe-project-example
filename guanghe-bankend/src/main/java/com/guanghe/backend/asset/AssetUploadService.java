package com.guanghe.backend.asset;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "enabled", havingValue = "true")
public class AssetUploadService {
    private static final int MAX_DIMENSION = 8192;
    private static final long MAX_PIXELS = 50_000_000L;

    private final AssetMapper mapper;
    private final BusinessSpaceService spaces;
    private final SnowflakeIdGenerator ids;
    private final ObjectStorage storage;
    private final AssetIngestionProperties properties;
    private final ContentSafetyPolicy safety;
    private final TransactionTemplate transactions;

    @Autowired
    public AssetUploadService(AssetMapper mapper, BusinessSpaceService spaces, SnowflakeIdGenerator ids,
                              ObjectStorage storage, AssetIngestionProperties properties,
                              ContentSafetyPolicy safety, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.spaces = spaces;
        this.ids = ids;
        this.storage = storage;
        this.properties = properties;
        this.safety = safety;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AssetDtos.UploadResponse upload(AuthenticatedAccount account, long spaceId,
                                           String idempotencyKey, String requestedName,
                                           MultipartFile file) {
        long userId = CatalogAccess.requireUser(account);
        spaces.requireOwnedStorageSpace(userId, spaceId);
        UUID key = uuid(idempotencyKey);
        if (file == null || file.isEmpty()) throw validation();
        Path directory = null;
        ObjectStorage.StoredObject stored = null;
        String requestHash = null;
        String newObjectKey = null;
        try {
            Files.createDirectories(properties.getTempDirectory());
            directory = Files.createTempDirectory(properties.getTempDirectory(), "browser-upload-");
            Path target = directory.resolve("image.bin");
            Copied copied = copyAndDigest(file, target);
            ImageMetadata image = inspect(target, copied.declaredContentType());
            String name = safeName(requestedName, file.getOriginalFilename());
            requestHash = sha256((spaceId + "\n" + name + "\n" + image.contentType()
                    + "\n" + copied.sizeBytes() + "\n" + copied.sha256()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            AssetRecords.UploadReceipt replay = mapper.findUploadReceipt(userId, key).orElse(null);
            if (replay != null) {
                if (!MessageDigest.isEqual(replay.requestHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        requestHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                }
                AssetRecords.Asset existing = mapper.findAsset(replay.assetId(), userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                return response(existing);
            }

            long assetId = ids.nextId();
            DownloadedArtifact artifact = new DownloadedArtifact(target, image.contentType(),
                    copied.sizeBytes(), copied.sha256(), image.width(), image.height());
            ContentSafetyPolicy.SafetyDecision decision = safety.inspectUpload(userId, assetId, artifact);
            if (decision == null || !decision.accepted()
                    || !properties.acceptsNewAssetStatus(decision.auditStatus())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            newObjectKey = properties.uploadedImageObjectKey(userId, assetId, image.contentType());
            stored = storage.storeOrConfirm(newObjectKey, target, image.contentType(),
                    copied.sizeBytes(), copied.sha256());
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            AssetRecords.Asset asset = new AssetRecords.Asset(assetId, userId, spaceId, null,
                    "IMAGE", "UPLOADED", null, name, stored.objectKey(), stored.contentType(),
                    stored.sizeBytes(), stored.sha256(), image.width(), image.height(), null,
                    "READY", decision.auditStatus(), now, now);
            AssetRecords.UploadReceipt receipt = new AssetRecords.UploadReceipt(
                    ids.nextId(), userId, key, requestHash, assetId, spaceId, now);
            transactions.executeWithoutResult(ignored -> {
                if (mapper.insertAsset(asset) != 1 || mapper.insertUploadReceipt(receipt) != 1) {
                    throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
                }
            });
            return response(asset);
        } catch (BusinessException exception) {
            cleanupStored(stored);
            throw exception;
        } catch (DuplicateKeyException exception) {
            cleanupStored(stored);
            return replayAfterRace(userId, key, requestHash);
        } catch (IOException exception) {
            cleanupStored(stored);
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        } catch (RuntimeException exception) {
            cleanupStored(stored, newObjectKey, exception);
            AssetRecords.UploadReceipt replay = requestHash == null
                    ? null : mapper.findUploadReceipt(userId, key).orElse(null);
            if (replay != null) return replay(replay, userId, requestHash);
            throw exception;
        } finally {
            deleteDirectory(directory);
        }
    }

    private Copied copyAndDigest(MultipartFile file, Path target) throws IOException {
        String declared = normalizeContentType(file.getContentType());
        MessageDigest digest = digest();
        long total = 0;
        try (InputStream input = new BufferedInputStream(file.getInputStream());
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > properties.getMaxImageBytes()) throw validation();
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (total == 0) throw validation();
        return new Copied(total, HexFormat.of().formatHex(digest.digest()), declared);
    }

    private ImageMetadata inspect(Path path, String declared) throws IOException {
        byte[] header = new byte[12];
        try (InputStream input = Files.newInputStream(path)) {
            if (input.readNBytes(header, 0, header.length) < 8) throw validation();
        }
        String actual;
        if ((header[0] & 0xff) == 0x89 && header[1] == 0x50 && header[2] == 0x4e && header[3] == 0x47) {
            actual = "image/png";
        } else if ((header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8) {
            actual = "image/jpeg";
        } else {
            throw validation();
        }
        if (!actual.equals(declared)) throw validation();
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(path.toFile())) {
            if (imageInput == null) throw validation();
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw validation();
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 8 || height < 8 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw validation();
                }
                return new ImageMetadata(actual, width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private String normalizeContentType(String value) {
        String normalized = value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(normalized)) normalized = "image/jpeg";
        if (!("image/png".equals(normalized) || "image/jpeg".equals(normalized))) throw validation();
        return normalized;
    }

    private String safeName(String requested, String original) {
        String value = requested == null || requested.isBlank() ? original : requested;
        if (value == null) value = "上传图片";
        value = value.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t]", " ").trim();
        if (value.isEmpty() || value.length() > 255) throw validation();
        return value;
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw validation();
        }
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String sha256(byte[] value) {
        return HexFormat.of().formatHex(digest().digest(value));
    }

    private AssetDtos.UploadResponse response(AssetRecords.Asset asset) {
        return new AssetDtos.UploadResponse(Long.toString(asset.id()), asset.assetType(), asset.sourceType(),
                asset.status(), asset.safetyStatus(), asset.name(), Long.toString(asset.spaceId()),
                asset.contentType(), asset.sizeBytes(), asset.sha256(), asset.width(), asset.height(),
                asset.createdAt());
    }

    private void cleanupStored(ObjectStorage.StoredObject stored) {
        if (stored != null && stored.createdByCaller()) storage.delete(stored.objectKey());
    }

    private void cleanupStored(ObjectStorage.StoredObject stored, String objectKey,
                               RuntimeException failure) {
        if (stored != null) {
            cleanupStored(stored);
        } else if (objectKey != null && failure instanceof AssetIngestionException ingestion
                && ingestion.objectCreatedByCaller()) {
            storage.delete(objectKey);
        }
    }

    private AssetDtos.UploadResponse replayAfterRace(long userId, UUID key, String requestHash) {
        AssetRecords.UploadReceipt receipt = mapper.findUploadReceipt(userId, key)
                .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        return replay(receipt, userId, requestHash);
    }

    private AssetDtos.UploadResponse replay(AssetRecords.UploadReceipt receipt, long userId,
                                             String requestHash) {
        if (!MessageDigest.isEqual(receipt.requestHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                requestHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return response(mapper.findAsset(receipt.assetId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    private void deleteDirectory(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private BusinessException validation() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }

    private record Copied(long sizeBytes, String sha256, String declaredContentType) { }
    private record ImageMetadata(String contentType, int width, int height) { }
}
