package com.guanghe.backend.asset;

import java.awt.Dimension;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public class ProviderArtifactDownloader {
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final double VIDEO_ASPECT_RATIO_RELATIVE_TOLERANCE = 0.03d;
    private final AssetIngestionProperties properties;
    private final HttpClient http;
    private final Function<String, InetAddress[]> resolver;
    private final FfprobeVideoInspector videoInspector;

    public ProviderArtifactDownloader(AssetIngestionProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), ProviderArtifactDownloader::resolve, new FfprobeVideoInspector(properties));
    }

    ProviderArtifactDownloader(AssetIngestionProperties properties, HttpClient http,
                               Function<String, InetAddress[]> resolver) {
        this(properties, http, resolver, new FfprobeVideoInspector(properties));
    }

    ProviderArtifactDownloader(AssetIngestionProperties properties, HttpClient http,
                               Function<String, InetAddress[]> resolver,
                               FfprobeVideoInspector videoInspector) {
        this.properties = properties;
        this.http = http;
        this.resolver = resolver;
        this.videoInspector = videoInspector;
    }

    public DownloadedArtifact download(AssetRecords.IngestionWork work) {
        boolean video = "WAN_VIDEO".equals(work.providerCode());
        boolean regionEdit = "WAN_IMAGE_EDIT".equals(work.providerCode());
        Instant deadline = video
                ? requiredDeadline(work.ingestionDeadlineAt(), "VIDEO_INGESTION_DEADLINE_MISSING")
                : requiredDeadline(work.providerUrlExpiresAt(), "PROVIDER_URL_EXPIRY_MISSING");
        if (!Instant.now().isBefore(deadline)) {
            throw new AssetIngestionException(video
                    ? "VIDEO_INGESTION_DEADLINE_EXPIRED" : "PROVIDER_URL_EXPIRED", false);
        }
        Path directory = null;
        try {
            Files.createDirectories(properties.getTempDirectory());
            directory = Files.createTempDirectory(properties.getTempDirectory(),
                    "task-" + work.taskId() + "-asset-");
            Path target = directory.resolve(video ? "artifact.mp4" : "artifact.png");
            DownloadResponse downloaded = openValidated(URI.create(work.providerUrl()), 0, video, regionEdit);
            try (InputStream input = new BufferedInputStream(downloaded.body());
                 OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                long deadlineNanos = System.nanoTime() + (video
                        ? properties.getVideoTotalTimeout() : properties.getTotalTimeout()).toNanos();
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    if (System.nanoTime() > deadlineNanos) {
                        throw new AssetIngestionException("PROVIDER_DOWNLOAD_TIMEOUT", true);
                    }
                    total += read;
                    if (total > (video ? properties.getMaxVideoBytes() : properties.getMaxImageBytes())) {
                        throw new AssetIngestionException("PROVIDER_DOWNLOAD_TOO_LARGE", false);
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                if (total == 0) throw new AssetIngestionException("PROVIDER_ARTIFACT_INVALID", false);
                String sha256 = HexFormat.of().formatHex(digest.digest());
                validateExpected(work, downloaded.contentType(), total, sha256);
                if (video) validateMp4Signature(target);
                Dimension dimension;
                String duration = null;
                String codec = null;
                if (video) {
                    FfprobeVideoInspector.VideoMetadata metadata = videoInspector.inspect(target);
                    dimension = new Dimension(metadata.width(), metadata.height());
                    duration = metadata.durationSeconds();
                    codec = metadata.codec();
                    validateDuration(work, duration);
                    validateVideoGeometry(work, metadata.width(), metadata.height());
                } else if (regionEdit) {
                    dimension = inspectRegionImage(target, downloaded.contentType());
                } else {
                    dimension = inspectPng(target);
                }
                if ((work.expectedWidth() != null && dimension.width != work.expectedWidth())
                        || (work.expectedHeight() != null && dimension.height != work.expectedHeight())) {
                    throw new AssetIngestionException("PROVIDER_ARTIFACT_DIMENSION_MISMATCH", false);
                }
                return new DownloadedArtifact(target, downloaded.contentType(), total, sha256,
                        dimension.width, dimension.height, duration, codec);
            }
        } catch (AssetIngestionException exception) {
            deleteDirectory(directory);
            throw exception;
        } catch (java.net.http.HttpTimeoutException exception) {
            deleteDirectory(directory);
            throw new AssetIngestionException("PROVIDER_DOWNLOAD_TIMEOUT", true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deleteDirectory(directory);
            throw new AssetIngestionException("PROVIDER_DOWNLOAD_INTERRUPTED", true, exception);
        } catch (Exception exception) {
            deleteDirectory(directory);
            throw new AssetIngestionException("PROVIDER_DOWNLOAD_FAILED", true, exception);
        }
    }

    public void cleanup(DownloadedArtifact artifact) {
        if (artifact != null) deleteDirectory(artifact.path().getParent());
    }

    private DownloadResponse openValidated(URI uri, int redirects, boolean video, boolean regionEdit)
            throws IOException, InterruptedException {
        validateRemoteUri(uri);
        HttpRequest request = HttpRequest.newBuilder(uri).GET()
                .timeout(video ? properties.getVideoReadTimeout() : properties.getReadTimeout())
                .header("Accept", video ? "video/mp4" : regionEdit ? "image/png,image/jpeg" : "image/png").build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (REDIRECTS.contains(response.statusCode())) {
            response.body().close();
            if (redirects >= properties.getMaxRedirects()) {
                throw new AssetIngestionException("PROVIDER_REDIRECT_LIMIT_EXCEEDED", false);
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new AssetIngestionException("PROVIDER_REDIRECT_INVALID", false));
            URI target;
            try {
                target = uri.resolve(location);
            } catch (IllegalArgumentException exception) {
                throw new AssetIngestionException("PROVIDER_REDIRECT_INVALID", false, exception);
            }
            return openValidated(target, redirects + 1, video, regionEdit);
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new AssetIngestionException(response.statusCode() >= 500
                    ? "PROVIDER_DOWNLOAD_UNAVAILABLE" : "PROVIDER_DOWNLOAD_REJECTED",
                    response.statusCode() >= 500);
        }
        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declaredLength > (video ? properties.getMaxVideoBytes() : properties.getMaxImageBytes())) {
            response.body().close();
            throw new AssetIngestionException("PROVIDER_DOWNLOAD_TOO_LARGE", false);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("")
                .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!(regionEdit ? Set.of("image/png", "image/jpeg").contains(contentType)
                : java.util.Objects.equals(video ? "video/mp4" : "image/png", contentType))) {
            response.body().close();
            throw new AssetIngestionException("PROVIDER_ARTIFACT_MIME_MISMATCH", false);
        }
        return new DownloadResponse(response.body(), contentType);
    }

    private void validateRemoteUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !allowedHost(uri.getHost())) {
            throw new AssetIngestionException("PROVIDER_URL_FORBIDDEN", false);
        }
        for (InetAddress address : resolver.apply(uri.getHost())) {
            if (!isPublicAddress(address)) {
                throw new AssetIngestionException("PROVIDER_URL_FORBIDDEN", false);
            }
        }
    }

    private static InetAddress[] resolve(String host) {
        try { return InetAddress.getAllByName(host); }
        catch (IOException exception) { throw new AssetIngestionException("PROVIDER_DNS_FAILED", true, exception); }
    }

    private boolean allowedHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return properties.getProviderAllowedHosts().stream().map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.startsWith("*.")
                        ? normalized.endsWith(value.substring(1))
                        && normalized.length() > value.length() - 1
                        : normalized.equals(value));
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && second == 168)
                    && !(first == 192 && second == 0 && third == 0)
                    && !(first == 192 && second == 0 && third == 2)
                    && !(first == 192 && second == 88 && third == 99)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113);
        }
        if (address instanceof Inet6Address) {
            boolean uniqueLocal = (bytes[0] & 0xfe) == 0xfc;
            boolean documentation = (bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8;
            return !uniqueLocal && !documentation;
        }
        return false;
    }

    private void validateExpected(AssetRecords.IngestionWork work, String actualContentType,
                                  long size, String sha256) {
        if (!("WAN_IMAGE_EDIT".equals(work.providerCode()) && work.expectedContentType() == null)
                && !java.util.Objects.equals(work.expectedContentType(), actualContentType)) {
            throw new AssetIngestionException("PROVIDER_ARTIFACT_MIME_MISMATCH", false);
        }
        if (work.expectedSizeBytes() != null && work.expectedSizeBytes() != size) {
            throw new AssetIngestionException("PROVIDER_ARTIFACT_SIZE_MISMATCH", false);
        }
        if (work.expectedSha256() != null && !work.expectedSha256().equals(sha256)) {
            throw new AssetIngestionException("PROVIDER_ARTIFACT_HASH_MISMATCH", false);
        }
    }

    private Instant requiredDeadline(java.time.OffsetDateTime value, String code) {
        if (value == null) throw new AssetIngestionException(code, false);
        return value.toInstant();
    }

    private void validateMp4Signature(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(12);
            if (header.length < 12 || header[4] != 'f' || header[5] != 't'
                    || header[6] != 'y' || header[7] != 'p') {
                throw new AssetIngestionException("PROVIDER_ARTIFACT_INVALID", false);
            }
        }
    }

    private void validateDuration(AssetRecords.IngestionWork work, String actual) {
        if (work.expectedDurationSeconds() == null || actual == null) {
            throw new AssetIngestionException("VIDEO_DURATION_INVALID", false);
        }
        BigDecimal difference = new BigDecimal(actual)
                .subtract(work.expectedDurationSeconds()).abs();
        BigDecimal tolerance = BigDecimal.valueOf(properties.getVideoDurationTolerance().toMillis(), 3);
        if (difference.compareTo(tolerance) > 0) {
            throw new AssetIngestionException("VIDEO_DURATION_MISMATCH", false);
        }
    }

    private void validateVideoGeometry(AssetRecords.IngestionWork work, int width, int height) {
        if (work.expectedResolution() == null || work.expectedAspectRatio() == null) {
            throw new AssetIngestionException("VIDEO_GEOMETRY_EXPECTATION_MISSING", false);
        }
        int expectedShortEdge;
        try {
            expectedShortEdge = Integer.parseInt(work.expectedResolution().replace("P", ""));
        } catch (NumberFormatException exception) {
            throw new AssetIngestionException("VIDEO_GEOMETRY_EXPECTATION_INVALID", false, exception);
        }
        if (Math.min(width, height) != expectedShortEdge) {
            throw new AssetIngestionException("VIDEO_RESOLUTION_MISMATCH", false);
        }
        if ("adaptive".equals(work.expectedAspectRatio())) return;
        String[] ratio = work.expectedAspectRatio().split(":", 2);
        if (ratio.length != 2) throw new AssetIngestionException("VIDEO_GEOMETRY_EXPECTATION_INVALID", false);
        double expected = Double.parseDouble(ratio[0]) / Double.parseDouble(ratio[1]);
        double actual = (double) width / height;
        double relativeError = Math.abs(actual / expected - 1.0d);
        if (relativeError > VIDEO_ASPECT_RATIO_RELATIVE_TOLERANCE) {
            throw new AssetIngestionException("VIDEO_ASPECT_RATIO_MISMATCH", false);
        }
    }

    private Dimension inspectRegionImage(Path path, String contentType) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new AssetIngestionException("PROVIDER_ARTIFACT_INVALID", false);
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String expectedFormat = "image/png".equals(contentType) ? "png" : "jpeg";
                if (!expectedFormat.equalsIgnoreCase(reader.getFormatName())) {
                    throw new AssetIngestionException("PROVIDER_ARTIFACT_MIME_MISMATCH", false);
                }
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 8 || width > 4096 || height < 8 || height > 4096) {
                    throw new AssetIngestionException("PROVIDER_ARTIFACT_DIMENSION_INVALID", false);
                }
                return new Dimension(width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private Dimension inspectPng(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] signature = input.readNBytes(PNG_SIGNATURE.length);
            if (!MessageDigest.isEqual(signature, PNG_SIGNATURE)) {
                throw new AssetIngestionException("PROVIDER_ARTIFACT_INVALID", false);
            }
        }
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
            if (!readers.hasNext()) throw new AssetIngestionException("PROVIDER_ARTIFACT_INVALID", false);
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 8 || width > 8192 || height < 8 || height > 8192) {
                    throw new AssetIngestionException("PROVIDER_ARTIFACT_DIMENSION_INVALID", false);
                }
                return new Dimension(width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    static void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private record DownloadResponse(InputStream body, String contentType) { }
}
