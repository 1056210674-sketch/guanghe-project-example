package com.guanghe.backend.asset;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderArtifactDownloaderTests {
    @TempDir Path temp;
    private AssetIngestionProperties properties;
    private HttpClient http;
    private Function<String, InetAddress[]> publicResolver;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AssetIngestionProperties();
        properties.setTempDirectory(temp);
        properties.setProviderAllowedHosts(List.of("cdn.example.com", "redirect.example.com", "internal.example.com"));
        properties.setMaxImageBytes(1024 * 1024);
        http = mock(HttpClient.class);
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34});
        publicResolver = ignored -> new InetAddress[]{publicAddress};
    }

    @Test
    void streamsAndValidatesPngWithoutLeavingTemporaryFilesAfterCleanup() throws Exception {
        byte[] png = png(16, 12);
        HttpResponse<java.io.InputStream> response = response(200, "image/png", png, Map.of());
        doReturn(response).when(http).send(any(), any());
        ProviderArtifactDownloader downloader = new ProviderArtifactDownloader(properties, http, publicResolver);

        DownloadedArtifact artifact = downloader.download(work(URI.create("https://cdn.example.com/a.png"),
                16, 12, null, null));

        assertThat(artifact.sizeBytes()).isEqualTo(png.length);
        assertThat(artifact.sha256()).matches("[0-9a-f]{64}");
        assertThat(artifact.width()).isEqualTo(16);
        assertThat(artifact.height()).isEqualTo(12);
        assertThat(Files.exists(artifact.path())).isTrue();
        downloader.cleanup(artifact);
        try (var files = Files.list(temp)) { assertThat(files).isEmpty(); }
    }

    @Test
    void rejectsPrivateAddressBeforeSendingARequest() throws Exception {
        InetAddress privateAddress = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        Function<String, InetAddress[]> privateResolver = ignored -> new InetAddress[]{privateAddress};
        ProviderArtifactDownloader downloader = new ProviderArtifactDownloader(properties, http, privateResolver);

        assertCode(() -> downloader.download(work(URI.create("https://internal.example.com/a.png"),
                16, 12, null, null)), "PROVIDER_URL_FORBIDDEN");
        verify(http, never()).send(any(), any());
        assertTempEmpty();
    }

    @Test
    void revalidatesEveryRedirectAndRejectsPrivateTarget() throws Exception {
        HttpResponse<java.io.InputStream> redirect = response(302, "text/plain", new byte[0],
                Map.of("Location", List.of("https://internal.example.com/a.png")));
        doReturn(redirect).when(http).send(any(), any());
        Function<String, InetAddress[]> resolver = host -> {
            try {
                return new InetAddress[]{InetAddress.getByAddress(host.startsWith("internal")
                        ? new byte[]{10, 0, 0, 1} : new byte[]{93, (byte) 184, (byte) 216, 34})};
            } catch (Exception exception) { throw new RuntimeException(exception); }
        };
        ProviderArtifactDownloader downloader = new ProviderArtifactDownloader(properties, http, resolver);

        assertCode(() -> downloader.download(work(URI.create("https://redirect.example.com/a.png"),
                16, 12, null, null)), "PROVIDER_URL_FORBIDDEN");
        assertTempEmpty();
    }

    @Test
    void rejectsMimeForgeryAndDimensionMismatchAndCleansFiles() throws Exception {
        byte[] png = png(16, 12);
        doReturn(response(200, "application/octet-stream", png, Map.of())).when(http).send(any(), any());
        ProviderArtifactDownloader downloader = new ProviderArtifactDownloader(properties, http, publicResolver);
        assertCode(() -> downloader.download(work(URI.create("https://cdn.example.com/a.png"),
                16, 12, null, null)), "PROVIDER_ARTIFACT_MIME_MISMATCH");
        assertTempEmpty();

        doReturn(response(200, "image/png", png, Map.of())).when(http).send(any(), any());
        assertCode(() -> downloader.download(work(URI.create("https://cdn.example.com/a.png"),
                20, 20, null, null)), "PROVIDER_ARTIFACT_DIMENSION_MISMATCH");
        assertTempEmpty();
    }

    @Test
    void rejectsForgedPngContentAndExpectedHashMismatchAndCleansFiles() throws Exception {
        doReturn(response(200, "image/png", "not-a-png".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of())).when(http).send(any(), any());
        ProviderArtifactDownloader downloader = new ProviderArtifactDownloader(properties, http, publicResolver);
        assertCode(() -> downloader.download(work(URI.create("https://cdn.example.com/a.png"),
                16, 12, null, null)), "PROVIDER_ARTIFACT_INVALID");
        assertTempEmpty();

        byte[] png = png(16, 12);
        doReturn(response(200, "image/png", png, Map.of())).when(http).send(any(), any());
        assertCode(() -> downloader.download(work(URI.create("https://cdn.example.com/a.png"),
                16, 12, null, "f".repeat(64))), "PROVIDER_ARTIFACT_HASH_MISMATCH");
        assertTempEmpty();
    }

    @Test
    void enforcesWholeBodyDeadlineAndCleansFiles() throws Exception {
        properties.setTotalTimeout(java.time.Duration.ofNanos(1));
        doReturn(response(200, "image/png", png(16, 12), Map.of())).when(http).send(any(), any());
        ProviderArtifactDownloader downloader = new ProviderArtifactDownloader(properties, http, publicResolver);

        assertCode(() -> downloader.download(work(URI.create("https://cdn.example.com/a.png"),
                16, 12, null, null)), "PROVIDER_DOWNLOAD_TIMEOUT");
        assertTempEmpty();
    }

    @Test
    void rejectsOversizeAndExpiredUrlsWithStableCodes() throws Exception {
        properties.setMaxImageBytes(10);
        doReturn(response(200, "image/png", new byte[0], Map.of("Content-Length", List.of("11"))))
                .when(http).send(any(), any());
        ProviderArtifactDownloader downloader = new ProviderArtifactDownloader(properties, http, publicResolver);
        assertCode(() -> downloader.download(work(URI.create("https://cdn.example.com/a.png"),
                16, 12, null, null)), "PROVIDER_DOWNLOAD_TOO_LARGE");
        assertTempEmpty();

        AssetRecords.IngestionWork expired = work(URI.create("https://cdn.example.com/a.png"),
                16, 12, null, null, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        assertCode(() -> downloader.download(expired), "PROVIDER_URL_EXPIRED");
    }

    @Test
    void acceptsWan480pWidescreenGeometryAndUsesStrictFfprobeWithoutShellExecution() throws Exception {
        byte[] mp4 = new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0};
        doReturn(response(200,"video/mp4",mp4,Map.of())).when(http).send(any(),any());
        FfprobeVideoInspector inspector=mock(FfprobeVideoInspector.class);
        when(inspector.inspect(any())).thenReturn(
                new FfprobeVideoInspector.VideoMetadata(832,480,"5.000","h264"));
        ProviderArtifactDownloader downloader=new ProviderArtifactDownloader(
                properties,http,publicResolver,inspector);
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
        AssetRecords.IngestionWork work=new AssetRecords.IngestionWork(1,2,3,4,1,5,6,7L,1,
                "WAN_VIDEO","wan3.0-video",UUID.randomUUID(),"wan-task",
                "https://cdn.example.com/a.mp4",null,"video/mp4",null,null,null,null,
                java.math.BigDecimal.valueOf(5),"16:9","480P",now.plusMinutes(30),
                "zhixiaohe/test/users/5/assets/2/original.mp4",
                "PROCESSING",1,3,now,now.plusMinutes(1),UUID.randomUUID(),null,null,now,now);

        DownloadedArtifact artifact=downloader.download(work);

        assertThat(artifact.contentType()).isEqualTo("video/mp4");
        assertThat(artifact.durationSeconds()).isEqualTo("5.000");
        assertThat(artifact.videoCodec()).isEqualTo("h264");
        List<String> command=new FfprobeVideoInspector(properties).command(Path.of("video;rm -rf.mp4"));
        assertThat(command).hasSize(10);
        assertThat(command.getLast()).endsWith("video;rm -rf.mp4");
        downloader.cleanup(artifact);
        assertTempEmpty();
    }

    @Test
    void rejectsWrongOrientationAndClearlyWrongWanAspectRatio() throws Exception {
        byte[] mp4 = new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0};
        for (int[] geometry : List.of(new int[]{480,832}, new int[]{720,480})) {
            doReturn(response(200,"video/mp4",mp4,Map.of())).when(http).send(any(),any());
            FfprobeVideoInspector inspector=mock(FfprobeVideoInspector.class);
            when(inspector.inspect(any())).thenReturn(new FfprobeVideoInspector.VideoMetadata(
                    geometry[0], geometry[1], "5.000", "h264"));
            ProviderArtifactDownloader downloader=new ProviderArtifactDownloader(
                    properties,http,publicResolver,inspector);

            assertCode(() -> downloader.download(videoWork("16:9", "480P")),
                    "VIDEO_ASPECT_RATIO_MISMATCH");
            assertTempEmpty();
        }
    }

    @Test
    void regionEditSniffsPngAndJpegWithoutInventingProviderGeometry() throws Exception {
        ProviderArtifactDownloader downloader = new ProviderArtifactDownloader(properties, http, publicResolver);
        for (String format : List.of("png", "jpeg")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), format, bytes);
            doReturn(response(200, "image/" + format, bytes.toByteArray(), Map.of())).when(http).send(any(), any());
            DownloadedArtifact artifact = downloader.download(regionWork());
            assertThat(artifact.contentType()).isEqualTo("image/" + format);
            assertThat(artifact.width()).isEqualTo(32);
            assertThat(artifact.height()).isEqualTo(24);
            downloader.cleanup(artifact);
            assertTempEmpty();
        }
    }

    @Test
    void regionEditRejectsMimeForgeryDespiteUnknownExpectedContentType() throws Exception {
        doReturn(response(200, "image/jpeg", png(32, 24), Map.of())).when(http).send(any(), any());
        ProviderArtifactDownloader downloader = new ProviderArtifactDownloader(properties, http, publicResolver);
        assertCode(() -> downloader.download(regionWork()), "PROVIDER_ARTIFACT_MIME_MISMATCH");
        assertTempEmpty();
    }

    private AssetRecords.IngestionWork regionWork() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AssetRecords.IngestionWork(1, 2, 3, 4, 1, 5, 6, 7L, 1, "WAN_IMAGE_EDIT",
                "wanx2.1-imageedit", UUID.randomUUID(), "provider-task", "https://cdn.example.com/edit",
                now.plusHours(24), null, null, null, null, null, null, null, null, null,
                "zhixiaohe/test/users/5/assets/2/original.png", "PROCESSING", 1, 3,
                now, now.plusMinutes(1), UUID.randomUUID(), null, null, now, now);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable).isInstanceOf(AssetIngestionException.class)
                .extracting(exception -> ((AssetIngestionException) exception).errorCode())
                .isEqualTo(code);
    }

    private void assertTempEmpty() throws Exception {
        try (var files = Files.list(temp)) { assertThat(files).isEmpty(); }
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<java.io.InputStream> response(int status, String contentType, byte[] body,
                                                       Map<String, List<String>> extraHeaders) {
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>(extraHeaders);
        headers.putIfAbsent("Content-Type", List.of(contentType));
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (left, right) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body));
        return response;
    }

    private byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private AssetRecords.IngestionWork work(URI uri, int width, int height, Long size, String hash) {
        return work(uri, width, height, size, hash, OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
    }

    private AssetRecords.IngestionWork work(URI uri, int width, int height, Long size, String hash,
                                             OffsetDateTime expiresAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AssetRecords.IngestionWork(1, 2, 3, 4, 1, 5, 6, 7L, 1, "QWEN_IMAGE",
                "qwen-image-3.0", UUID.randomUUID(), "provider-task", uri.toString(), expiresAt,
                "image/png", size, hash, width, height,
                "zhixiaohe/test/users/5/assets/2/original.png", "PROCESSING", 1, 3,
                now, now.plusMinutes(1), UUID.randomUUID(), null, null, now, now);
    }

    private AssetRecords.IngestionWork videoWork(String aspectRatio, String resolution) {
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
        return new AssetRecords.IngestionWork(1,2,3,4,1,5,6,7L,1,
                "WAN_VIDEO","wan3.0-video",UUID.randomUUID(),"wan-task",
                "https://cdn.example.com/a.mp4",null,"video/mp4",null,null,null,null,
                java.math.BigDecimal.valueOf(5),aspectRatio,resolution,now.plusMinutes(30),
                "zhixiaohe/test/users/5/assets/2/original.mp4",
                "PROCESSING",1,3,now,now.plusMinutes(1),UUID.randomUUID(),null,null,now,now);
    }
}
