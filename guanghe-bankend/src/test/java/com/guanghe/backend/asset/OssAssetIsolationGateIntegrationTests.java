package com.guanghe.backend.asset;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "OSS_GATE_ENABLED", matches = "true")
class OssAssetIsolationGateIntegrationTests {
    @TempDir Path temporaryDirectory;

    @Test
    void uploadsVerifiesDownloadsAndDeletesOnlyItsRandomTestPrefix() throws Exception {
        String endpoint = required("OSS_GATE_ENDPOINT");
        String region = required("OSS_GATE_REGION");
        String bucket = required("OSS_GATE_BUCKET");
        String accessKeyId = required("OSS_GATE_ACCESS_KEY_ID");
        String accessKeySecret = required("OSS_GATE_ACCESS_KEY_SECRET");
        String prefix = "zhixiaohe/test/gates/" + UUID.randomUUID() + "/";
        requireSafeGatePrefix(prefix);

        byte[] png = testPng(24, 18);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        Path source = temporaryDirectory.resolve("controlled-test.png");
        Files.write(source, png);

        AssetIngestionProperties properties = properties(endpoint, region, bucket, accessKeyId, accessKeySecret);
        OSS client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        AlibabaOssObjectStorage storage = new AlibabaOssObjectStorage(client, properties);
        String userAKey = prefix + "users/41001/assets/61001/original.png";
        String userBKey = prefix + "users/41002/assets/61002/original.png";
        assertThat(userAKey).isNotEqualTo(userBKey);
        List<String> createdKeys = List.of(userAKey, userBKey);

        try {
            for (String key : createdKeys) {
                requireKeyInsidePrefix(prefix, key);
                ObjectStorage.StoredObject stored = storage.storeOrConfirm(key, source, "image/png",
                        png.length, sha256);
                assertThat(stored.objectKey()).isEqualTo(key);
                assertThat(stored.sizeBytes()).isEqualTo(png.length);
                assertThat(stored.sha256()).isEqualTo(sha256);
                assertThat(stored.createdByCaller()).isTrue();
            }

            ObjectStorage.StoredObject confirmed = storage.storeOrConfirm(userAKey, source, "image/png",
                    png.length, sha256);
            assertThat(confirmed.createdByCaller()).isFalse();

            URI signedUrl = storage.signedGetUrl(userAKey,
                    OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2));
            assertThat(signedUrl.getScheme()).isEqualToIgnoringCase("https");
            byte[] downloaded = safeDownload(signedUrl);
            assertThat(downloaded).hasSize(png.length);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(downloaded)))
                    .isEqualTo(sha256);
            BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(downloaded));
            assertThat(decoded).isNotNull();
            assertThat(decoded.getWidth()).isEqualTo(24);
            assertThat(decoded.getHeight()).isEqualTo(18);
        } finally {
            for (String key : createdKeys) {
                requireKeyInsidePrefix(prefix, key);
                storage.delete(key);
            }
            assertThat(client.listObjects(bucket, prefix).getObjectSummaries()).isEmpty();
            client.shutdown();
        }
    }

    static void requireSafeGatePrefix(String prefix) {
        if (prefix == null || !prefix.matches(
                "^zhixiaohe/test/gates/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/$")) {
            throw new IllegalStateException("OSS Gate prefix is not an isolated random test prefix");
        }
    }

    private static void requireKeyInsidePrefix(String prefix, String key) {
        requireSafeGatePrefix(prefix);
        if (key == null || !key.startsWith(prefix) || key.contains("..")) {
            throw new IllegalStateException("OSS Gate key escaped its isolated prefix");
        }
    }

    private AssetIngestionProperties properties(String endpoint, String region, String bucket,
                                                  String accessKeyId, String accessKeySecret) {
        AssetIngestionProperties properties = new AssetIngestionProperties();
        properties.getOss().setEndpoint(endpoint);
        properties.getOss().setRegion(region);
        properties.getOss().setBucket(bucket);
        properties.getOss().setAccessKeyId(accessKeyId);
        properties.getOss().setAccessKeySecret(accessKeySecret);
        return properties;
    }

    private byte[] safeDownload(URI signedUrl) throws Exception {
        try {
            HttpRequest request = HttpRequest.newBuilder(signedUrl).GET()
                    .timeout(java.time.Duration.ofSeconds(20)).build();
            HttpResponse<byte[]> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new AssertionError("OSS Gate signed download did not return HTTP 200");
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("OSS Gate signed download was interrupted");
        } catch (Exception exception) {
            throw new AssertionError("OSS Gate signed download failed without exposing its URL");
        }
    }

    private byte[] testPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return output.toByteArray();
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the isolated OSS Gate");
        }
        return value;
    }
}
