package com.guanghe.backend.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskRecords;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageRegionEditServiceTests {
    @TempDir Path temp;

    @Test
    void preservesEveryProtectedRgbaPixelEvenWhenTheProviderChangedTheWholeImage() {
        BufferedImage original = filled(8, 8, 0x80335577);
        BufferedImage mask = filled(8, 8, 0xff000000);
        mask.setRGB(2, 3, 0xffffffff);
        BufferedImage result = ImageRegionEditService.compositePixels(original, mask, filled(8, 8, 0xffee3311));
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            assertThat(result.getRGB(x, y)).isEqualTo(x == 2 && y == 3 ? 0xffee3311 : original.getRGB(x, y));
        }
    }

    @Test
    void onlyGeneratedPixelsAreRescaledWhenProviderSizeDiffers() {
        BufferedImage original = filled(8, 12, 0xff345678);
        BufferedImage mask = filled(8, 12, 0xff000000);
        mask.setRGB(4, 6, 0xffffffff);
        BufferedImage result = ImageRegionEditService.compositePixels(original, mask, filled(16, 16, 0xffeeeeee));
        assertThat(result.getWidth()).isEqualTo(8);
        assertThat(result.getHeight()).isEqualTo(12);
        assertThat(result.getRGB(4, 6)).isEqualTo(0xffeeeeee);
        assertThat(result.getRGB(0, 0)).isEqualTo(0xff345678);
    }

    @Test
    void rejectsEmptyGrayAndTransparentMasks() {
        BufferedImage mask = filled(8, 8, 0xff000000);
        assertThatThrownBy(() -> ImageRegionEditService.validateMask(mask, 8, 8)).isInstanceOf(BusinessException.class);
        mask.setRGB(0, 0, 0xff808080);
        assertThatThrownBy(() -> ImageRegionEditService.validateMask(mask, 8, 8)).isInstanceOf(BusinessException.class);
        mask.setRGB(0, 0, 0x00ffffff);
        assertThatThrownBy(() -> ImageRegionEditService.validateMask(mask, 8, 8)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMismatchedMaskDimensions() {
        assertThatThrownBy(() -> ImageRegionEditService.validateMask(filled(8, 9, 0xffffffff), 8, 8))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void validatesRealMaskBytesAndAlwaysCleansItsPrivateTemporaryDirectory() throws Exception {
        ObjectStorage storage = mock(ObjectStorage.class);
        AssetIngestionProperties properties = new AssetIngestionProperties();
        properties.setTempDirectory(temp);
        doAnswer(invocation -> {
            ImageIO.write(filled(512, 512, 0xff000000), "png", ((Path) invocation.getArgument(1)).toFile());
            return null;
        }).when(storage).readVerified(anyString(), any(), anyLong(), anyString());
        ImageRegionEditService service = new ImageRegionEditService(storage, properties,
                mock(AssetMapper.class), mock(TaskControlMapper.class), new ObjectMapper());
        assertThatThrownBy(() -> service.validateInputs(asset(1, "base"), asset(2, "mask")))
                .isInstanceOf(BusinessException.class);
        verify(storage).readVerified(eq("mask"), any(), eq(500L), eq("a".repeat(64)));
        try (var files = Files.list(temp)) { assertThat(files).isEmpty(); }
    }

    @Test
    void compositeUsesOnlyOwnedReferencesAndReturnsVerifiedPermanentPngMetadata() throws Exception {
        AssetMapper assets = mock(AssetMapper.class);
        TaskControlMapper tasks = mock(TaskControlMapper.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        AssetIngestionProperties properties = new AssetIngestionProperties();
        properties.setTempDirectory(temp);
        var task = mock(TaskRecords.Task.class);
        when(task.inputSnapshotJson()).thenReturn("""
                {"parameters":{"sourceMode":"REGION_EDIT","referenceAssetIds":["1"],"maskAssetId":"2"}}
                """);
        when(tasks.findTask(100L, 7L)).thenReturn(Optional.of(task));
        when(assets.findAsset(1L, 7L)).thenReturn(Optional.of(asset(1, "base")));
        when(assets.findAsset(2L, 7L)).thenReturn(Optional.of(asset(2, "mask")));
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            BufferedImage input = filled(512, 512, key.equals("base") ? 0xff123456 : 0xff000000);
            if (key.equals("mask")) input.setRGB(200, 300, 0xffffffff);
            ImageIO.write(input, "png", ((Path) invocation.getArgument(1)).toFile());
            return null;
        }).when(storage).readVerified(anyString(), any(), anyLong(), anyString());
        var work = mock(AssetRecords.IngestionWork.class);
        when(work.providerCode()).thenReturn("WAN_IMAGE_EDIT");
        when(work.taskId()).thenReturn(100L);
        when(work.userId()).thenReturn(7L);
        when(work.spaceId()).thenReturn(9L);
        Path generated = temp.resolve("generated.png");
        ImageIO.write(filled(512, 512, 0xffaabbcc), "png", generated.toFile());
        var service = new ImageRegionEditService(storage, properties, assets, tasks, new ObjectMapper());
        var result = service.composite(work, new DownloadedArtifact(generated, "image/png", 1, "unknown", 512, 512));
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.sizeBytes()).isEqualTo(Files.size(result.path()));
        assertThat(result.sha256()).matches("[0-9a-f]{64}");
        var stored = ImageIO.read(result.path().toFile());
        assertThat(stored.getRGB(200, 300)).isEqualTo(0xffaabbcc);
        assertThat(stored.getRGB(199, 300)).isEqualTo(0xff123456);
        verify(assets).findAsset(1L, 7L);
        verify(assets).findAsset(2L, 7L);
    }

    @Test
    void rejectsUnownedReferenceBeforeReadingObjectStorage() {
        AssetMapper assets = mock(AssetMapper.class);
        TaskControlMapper tasks = mock(TaskControlMapper.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        var task = mock(TaskRecords.Task.class);
        when(task.inputSnapshotJson()).thenReturn("""
                {"parameters":{"sourceMode":"REGION_EDIT","referenceAssetIds":["1"],"maskAssetId":"2"}}
                """);
        when(tasks.findTask(100L, 7L)).thenReturn(Optional.of(task));
        when(assets.findAsset(1L, 7L)).thenReturn(Optional.empty());
        var work = mock(AssetRecords.IngestionWork.class);
        when(work.providerCode()).thenReturn("WAN_IMAGE_EDIT");
        when(work.taskId()).thenReturn(100L);
        when(work.userId()).thenReturn(7L);
        var service = new ImageRegionEditService(storage, new AssetIngestionProperties(), assets, tasks, new ObjectMapper());
        assertThatThrownBy(() -> service.composite(work,
                new DownloadedArtifact(temp.resolve("x"), "image/png", 1, "x", 512, 512)))
                .isInstanceOf(AssetIngestionException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void refusesExifRotationsAndMirrorsBeforeQuotingButAcceptsNormalOrientation() throws Exception {
        for (int orientation : new int[]{1, 2, 3, 6, 8}) {
            var bytes = new java.io.ByteArrayOutputStream();
            var output = new java.io.DataOutputStream(bytes);
            output.writeShort(0xffd8);
            output.writeShort(0xffe1);
            output.writeShort(34);
            output.write(new byte[]{'E','x','i','f',0,0});
            output.writeShort(0x4d4d);
            output.writeShort(42);
            output.writeInt(8);
            output.writeShort(1);
            output.writeShort(0x0112);
            output.writeShort(3);
            output.writeInt(1);
            output.writeShort(orientation);
            output.writeShort(0);
            output.writeInt(0);
            output.writeShort(0xffda);
            Path photo = temp.resolve("orientation-" + orientation + ".jpg");
            Files.write(photo, bytes.toByteArray());
            if (orientation == 1) ImageRegionEditService.requireUnorientedInput(photo);
            else assertThatThrownBy(() -> ImageRegionEditService.requireUnorientedInput(photo))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("旋转或镜像");
        }
    }

    private static AssetRecords.Asset asset(long id, String key) {
        return new AssetRecords.Asset(id, 7L, 9L, null, "IMAGE", "UPLOADED", null,
                key, key, "image/png", 500L, "a".repeat(64), 512, 512, null,
                "READY", "APPROVED", OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static BufferedImage filled(int width, int height, int pixel) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, pixel);
        return image;
    }
}
