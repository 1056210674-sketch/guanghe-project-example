package com.guanghe.backend.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.task.TaskControlMapper;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Validates a real binary mask and enforces unchanged pixels outside it after generation. */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.asset", name = "enabled", havingValue = "true")
public class ImageRegionEditService {
    private static final long MAX_INPUT_BYTES = 10L * 1024 * 1024;
    private final ObjectStorage storage;
    private final AssetIngestionProperties properties;
    private final AssetMapper assets;
    private final TaskControlMapper tasks;
    private final ObjectMapper json;

    public ImageRegionEditService(ObjectStorage storage, AssetIngestionProperties properties,
                                  AssetMapper assets, TaskControlMapper tasks, ObjectMapper json) {
        this.storage = storage;
        this.properties = properties;
        this.assets = assets;
        this.tasks = tasks;
        this.json = json;
    }

    public void validateInputs(AssetRecords.Asset base, AssetRecords.Asset mask) {
        validateMetadata(base, mask);
        Path directory = null;
        try {
            Files.createDirectories(properties.getTempDirectory());
            directory = Files.createTempDirectory(properties.getTempDirectory(), "region-mask-");
            // Validate before quoting: browsers apply EXIF orientation while ImageIO does not.
            // A non-normalized source could move a mask onto the wrong original pixels.
            Path basePath = directory.resolve("base.bin");
            storage.readVerified(base.objectKey(), basePath, base.sizeBytes(), base.sha256());
            requireUnorientedInput(basePath);
            BufferedImage image = readOwned(mask, directory.resolve("mask.png"));
            validateMask(image, base.width(), base.height());
        } catch (IOException exception) {
            throw new AssetIngestionException("REGION_MASK_READ_FAILED", true, exception);
        } finally {
            ProviderArtifactDownloader.deleteDirectory(directory);
        }
    }

    public DownloadedArtifact composite(AssetRecords.IngestionWork work, DownloadedArtifact generated) {
        if (!"WAN_IMAGE_EDIT".equals(work.providerCode())) return generated;
        var task = tasks.findTask(work.taskId(), work.userId())
                .orElseThrow(() -> new AssetIngestionException("REGION_TASK_MISSING", false));
        Path directory = null;
        try {
            JsonNode snapshot = json.readTree(task.inputSnapshotJson());
            JsonNode parameters = snapshot.path("parameters");
            if (!"REGION_EDIT".equals(parameters.path("sourceMode").asText())
                    || parameters.path("referenceAssetIds").size() != 1) {
                throw new AssetIngestionException("REGION_SOURCE_MISMATCH", false);
            }
            AssetRecords.Asset base = owned(parameters.path("referenceAssetIds").get(0), work);
            AssetRecords.Asset mask = owned(parameters.path("maskAssetId"), work);
            validateMetadata(base, mask);
            directory = Files.createTempDirectory(generated.path().getParent(), "region-inputs-");
            BufferedImage original = readOwned(base, directory.resolve("base.bin"));
            BufferedImage maskImage = readOwned(mask, directory.resolve("mask.png"));
            validateMask(maskImage, original.getWidth(), original.getHeight());
            BufferedImage edited = decodeBounded(generated.path());
            BufferedImage result = compositePixels(original, maskImage, edited);
            Path target = generated.path().getParent().resolve("region-composited.png");
            if (!ImageIO.write(result, "png", target.toFile())) {
                throw new AssetIngestionException("REGION_OUTPUT_ENCODING_FAILED", false);
            }
            long size = Files.size(target);
            if (size > properties.getMaxImageBytes()) {
                throw new AssetIngestionException("REGION_OUTPUT_TOO_LARGE", false);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(target)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            }
            return new DownloadedArtifact(target, "image/png", size,
                    HexFormat.of().formatHex(digest.digest()), result.getWidth(), result.getHeight());
        } catch (AssetIngestionException exception) {
            throw exception;
        } catch (BusinessException exception) {
            throw new AssetIngestionException("REGION_INPUT_INVALID", false);
        } catch (Exception exception) {
            throw new AssetIngestionException("REGION_COMPOSITE_FAILED", false, exception);
        } finally {
            ProviderArtifactDownloader.deleteDirectory(directory);
        }
    }

    private AssetRecords.Asset owned(JsonNode id, AssetRecords.IngestionWork work) {
        if (id == null || !id.isTextual() || !id.asText().matches("^[1-9][0-9]{0,18}$")) {
            throw new AssetIngestionException("REGION_SOURCE_MISMATCH", false);
        }
        AssetRecords.Asset asset = assets.findAsset(Long.parseLong(id.asText()), work.userId())
                .orElseThrow(() -> new AssetIngestionException("REGION_SOURCE_UNAVAILABLE", false));
        if (asset.spaceId() != work.spaceId()) throw new AssetIngestionException("REGION_SOURCE_MISMATCH", false);
        return asset;
    }

    private void validateMetadata(AssetRecords.Asset base, AssetRecords.Asset mask) {
        if (base == null || mask == null || base.id() == mask.id()
                || base.userId() != mask.userId() || base.spaceId() != mask.spaceId()
                || !"IMAGE".equals(base.assetType()) || !"IMAGE".equals(mask.assetType())
                || !"READY".equals(base.status()) || !"READY".equals(mask.status())
                || !properties.allowsAssetUse(base.safetyStatus())
                || !properties.allowsAssetUse(mask.safetyStatus())
                || !"image/png".equals(mask.contentType())
                || !("image/png".equals(base.contentType()) || "image/jpeg".equals(base.contentType()))
                || base.sizeBytes() < 1 || base.sizeBytes() > MAX_INPUT_BYTES
                || mask.sizeBytes() < 1 || mask.sizeBytes() > MAX_INPUT_BYTES
                || base.width() == null || base.height() == null
                || base.width() < 512 || base.width() > 4096 || base.height() < 512 || base.height() > 4096
                || !base.width().equals(mask.width()) || !base.height().equals(mask.height())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "局部重绘需要 512–4096 像素、10MB 以内的底图，以及相同尺寸的黑白 PNG 蒙版", java.util.Map.of());
        }
    }

    private BufferedImage readOwned(AssetRecords.Asset asset, Path target) throws IOException {
        storage.readVerified(asset.objectKey(), target, asset.sizeBytes(), asset.sha256());
        requireUnorientedInput(target);
        BufferedImage image = decodeBounded(target);
        if (image.getWidth() != asset.width() || image.getHeight() != asset.height()) {
            throw new AssetIngestionException("REGION_SOURCE_DIMENSION_MISMATCH", false);
        }
        return image;
    }

    static void requireUnorientedInput(Path path) throws IOException {
        try (var input = new java.io.DataInputStream(Files.newInputStream(path))) {
            int signature = input.readUnsignedShort();
            if (signature == 0xffd8) {
                while (true) {
                    if (input.readUnsignedByte() != 0xff) throw orientationError();
                    int marker;
                    do { marker = input.readUnsignedByte(); } while (marker == 0xff);
                    if (marker == 0xda || marker == 0xd9) return;
                    if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd7) continue;
                    int length = input.readUnsignedShort() - 2;
                    if (length < 0) throw orientationError();
                    byte[] segment = input.readNBytes(length);
                    if (segment.length != length) throw orientationError();
                    if (marker == 0xe1 && length >= 6 && segment[0] == 'E' && segment[1] == 'x'
                            && segment[2] == 'i' && segment[3] == 'f' && segment[4] == 0 && segment[5] == 0) {
                        requireNormalExif(java.util.Arrays.copyOfRange(segment, 6, length));
                    }
                }
            }
            if (signature != 0x8950) return;
            input.skipNBytes(6);
            while (true) {
                int length = input.readInt();
                int type = input.readInt();
                if (length < 0 || length > MAX_INPUT_BYTES) throw orientationError();
                if (type == 0x65584966) { // PNG eXIf chunk
                    byte[] exif = input.readNBytes(length);
                    if (exif.length != length) throw orientationError();
                    requireNormalExif(exif);
                } else input.skipNBytes(length);
                input.skipNBytes(4);
                if (type == 0x49454e44) return;
            }
        }
    }

    private static void requireNormalExif(byte[] bytes) {
        if (bytes.length < 8) throw orientationError();
        var exif = java.nio.ByteBuffer.wrap(bytes);
        if (bytes[0] == 'I' && bytes[1] == 'I') exif.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        else if (!(bytes[0] == 'M' && bytes[1] == 'M')) throw orientationError();
        if (Short.toUnsignedInt(exif.getShort(2)) != 42) throw orientationError();
        int offset = exif.getInt(4);
        if (offset < 8 || offset > bytes.length - 2) throw orientationError();
        int entries = Short.toUnsignedInt(exif.getShort(offset));
        if (entries > (bytes.length - offset - 2) / 12) throw orientationError();
        for (int index = 0; index < entries; index++) {
            int start = offset + 2 + index * 12;
            if (Short.toUnsignedInt(exif.getShort(start)) == 0x0112
                    && (exif.getShort(start + 2) != 3 || exif.getInt(start + 4) != 1
                        || exif.getShort(start + 8) != 1)) throw orientationError();
        }
    }

    private static BusinessException orientationError() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED,
                "这张底图含旋转或镜像方向信息，请先另存为方向正常的 PNG 再涂抹，避免改错区域", java.util.Map.of());
    }

    static BufferedImage decodeBounded(Path path) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new AssetIngestionException("REGION_IMAGE_INVALID", false);
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!("png".equalsIgnoreCase(format) || "jpeg".equalsIgnoreCase(format))
                        || width < 8 || width > 4096 || height < 8 || height > 4096) {
                    throw new AssetIngestionException("REGION_IMAGE_INVALID", false);
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    static void validateMask(BufferedImage mask, int width, int height) {
        if (mask.getWidth() != width || mask.getHeight() != height) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "蒙版尺寸必须与底图一致", java.util.Map.of());
        }
        boolean anyWhite = false;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int pixel = mask.getRGB(x, y);
            if (pixel != 0xff000000 && pixel != 0xffffffff) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "蒙版必须是无透明区域的纯黑白图片", java.util.Map.of());
            }
            anyWhite |= pixel == 0xffffffff;
        }
        if (!anyWhite) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请先涂抹需要修改的区域", java.util.Map.of());
    }

    static BufferedImage compositePixels(BufferedImage original, BufferedImage mask, BufferedImage edited) {
        int width = original.getWidth(), height = original.getHeight();
        validateMask(mask, width, height);
        BufferedImage scaled = edited;
        if (edited.getWidth() != width || edited.getHeight() != height) {
            scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            var graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(edited, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
        }
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            // Copy original decoded RGBA exactly: never blend or resize protected pixels.
            result.setRGB(x, y, mask.getRGB(x, y) == 0xffffffff ? scaled.getRGB(x, y) : original.getRGB(x, y));
        }
        return result;
    }
}
