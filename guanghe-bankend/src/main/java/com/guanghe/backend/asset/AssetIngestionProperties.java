package com.guanghe.backend.asset;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhixiaohe.asset")
public class AssetIngestionProperties {
    public enum SafetyMode { REAL, TEST_BYPASS, NOT_REVIEWED }

    private boolean enabled;
    private boolean workerEnabled;
    private String environment = "local";
    private List<String> providerAllowedHosts = new ArrayList<>();
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(20);
    private Duration totalTimeout = Duration.ofSeconds(30);
    private Duration lease = Duration.ofMinutes(2);
    private Duration retryDelay = Duration.ofSeconds(10);
    private Duration signedUrlTtl = Duration.ofMinutes(5);
    private Duration referenceReadUrlTtl = Duration.ofMinutes(30);
    private long maxImageBytes = 20L * 1024L * 1024L;
    private long maxVideoBytes = 200L * 1024L * 1024L;
    private Duration videoReadTimeout = Duration.ofSeconds(60);
    private Duration videoTotalTimeout = Duration.ofMinutes(5);
    private Duration videoIngestionDeadline = Duration.ofMinutes(30);
    private String ffprobePath = "ffprobe";
    private Duration ffprobeTimeout = Duration.ofSeconds(15);
    private List<String> allowedVideoCodecs = new ArrayList<>(List.of("h264"));
    private Duration videoDurationTolerance = Duration.ofMillis(750);
    private int maxRedirects = 2;
    private int maxAttempts = 3;
    private Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"), "zhixiaohe-assets");
    private SafetyMode safetyMode = SafetyMode.REAL;
    private boolean testBypassDownloadEnabled;
    private final Oss oss = new Oss();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean workerEnabled) { this.workerEnabled = workerEnabled; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public List<String> getProviderAllowedHosts() { return providerAllowedHosts; }
    public void setProviderAllowedHosts(List<String> value) { providerAllowedHosts = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }
    public Duration getTotalTimeout() { return totalTimeout; }
    public void setTotalTimeout(Duration value) { totalTimeout = value; }
    public Duration getLease() { return lease; }
    public void setLease(Duration value) { lease = value; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration value) { retryDelay = value; }
    public Duration getSignedUrlTtl() { return signedUrlTtl; }
    public void setSignedUrlTtl(Duration value) { signedUrlTtl = value; }
    public Duration getReferenceReadUrlTtl() { return referenceReadUrlTtl; }
    public void setReferenceReadUrlTtl(Duration value) { referenceReadUrlTtl = value; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long value) { maxImageBytes = value; }
    public long getMaxVideoBytes() { return maxVideoBytes; }
    public void setMaxVideoBytes(long value) { maxVideoBytes = value; }
    public Duration getVideoReadTimeout() { return videoReadTimeout; }
    public void setVideoReadTimeout(Duration value) { videoReadTimeout = value; }
    public Duration getVideoTotalTimeout() { return videoTotalTimeout; }
    public void setVideoTotalTimeout(Duration value) { videoTotalTimeout = value; }
    public Duration getVideoIngestionDeadline() { return videoIngestionDeadline; }
    public void setVideoIngestionDeadline(Duration value) { videoIngestionDeadline = value; }
    public String getFfprobePath() { return ffprobePath; }
    public void setFfprobePath(String value) { ffprobePath = value; }
    public Duration getFfprobeTimeout() { return ffprobeTimeout; }
    public void setFfprobeTimeout(Duration value) { ffprobeTimeout = value; }
    public List<String> getAllowedVideoCodecs() { return allowedVideoCodecs; }
    public void setAllowedVideoCodecs(List<String> value) { allowedVideoCodecs = value; }
    public Duration getVideoDurationTolerance() { return videoDurationTolerance; }
    public void setVideoDurationTolerance(Duration value) { videoDurationTolerance = value; }
    public int getMaxRedirects() { return maxRedirects; }
    public void setMaxRedirects(int value) { maxRedirects = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = value; }
    public Path getTempDirectory() { return tempDirectory; }
    public void setTempDirectory(Path value) { tempDirectory = value; }
    public SafetyMode getSafetyMode() { return safetyMode; }
    public void setSafetyMode(SafetyMode value) { safetyMode = value; }
    public boolean isTestBypassDownloadEnabled() { return testBypassDownloadEnabled; }
    public void setTestBypassDownloadEnabled(boolean value) { testBypassDownloadEnabled = value; }
    public Oss getOss() { return oss; }

    public boolean acceptsNewAssetStatus(String status) {
        return switch (safetyMode) {
            case REAL -> "APPROVED".equals(status);
            case TEST_BYPASS -> "TEST_BYPASS".equals(status) && testBypassAllowed();
            case NOT_REVIEWED -> "NOT_REVIEWED".equals(status);
        };
    }

    public boolean allowsAssetUse(String status) {
        return "APPROVED".equals(status)
                || ("TEST_BYPASS".equals(status) && testBypassAllowed())
                || ("NOT_REVIEWED".equals(status) && safetyMode == SafetyMode.NOT_REVIEWED);
    }

    private boolean testBypassAllowed() {
        return (safetyMode == SafetyMode.TEST_BYPASS || safetyMode == SafetyMode.NOT_REVIEWED)
                && "test".equals(environment) && testBypassDownloadEnabled;
    }

    public String objectKey(long userId, long assetId) {
        return objectKey(userId, assetId, "IMAGE");
    }

    public String objectKey(long userId, long assetId, String assetType) {
        String extension = "VIDEO".equals(assetType) ? ".mp4" : ".png";
        return "zhixiaohe/" + environment + "/users/" + userId + "/assets/" + assetId + "/original" + extension;
    }

    public String uploadedImageObjectKey(long userId, long assetId, String contentType) {
        String extension = "image/jpeg".equals(contentType) ? ".jpg" : ".png";
        return "zhixiaohe/" + environment + "/users/" + userId + "/assets/" + assetId
                + "/original" + extension;
    }

    public void validate() {
        if (workerEnabled && !enabled) {
            throw new IllegalStateException(
                    "ASSET_INGESTION_WORKER_ENABLED requires ASSET_INGESTION_ENABLED=true");
        }
        if (!enabled) return;
        if (environment == null || !environment.matches("^[a-z][a-z0-9_-]{0,31}$")) {
            throw new IllegalStateException("APP_ENVIRONMENT must be a safe non-empty environment name");
        }
        if (safetyMode == SafetyMode.TEST_BYPASS && !"test".equals(environment)) {
            throw new IllegalStateException("TEST_BYPASS content safety is only allowed in environment=test");
        }
        if (testBypassDownloadEnabled && (safetyMode == SafetyMode.REAL || !"test".equals(environment))) {
            throw new IllegalStateException("TEST_BYPASS asset downloads require test environment and bypass or not-reviewed mode");
        }
        if (providerAllowedHosts == null || providerAllowedHosts.isEmpty()
                || providerAllowedHosts.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("ASSET_PROVIDER_ALLOWED_HOSTS must be configured");
        }
        if (!positive(connectTimeout) || !positive(readTimeout) || !positive(totalTimeout)
                || !positive(lease) || !positive(retryDelay) || !positive(signedUrlTtl)
                || referenceReadUrlTtl == null || referenceReadUrlTtl.compareTo(Duration.ofMinutes(20)) < 0
                || connectTimeout.toMillis() > Integer.MAX_VALUE
                || readTimeout.toMillis() > Integer.MAX_VALUE
                || totalTimeout.toMillis() > Integer.MAX_VALUE
                || !positive(videoReadTimeout) || !positive(videoTotalTimeout)
                || !positive(videoIngestionDeadline) || !positive(ffprobeTimeout)
                || !positive(videoDurationTolerance)
                || maxImageBytes <= 0 || maxVideoBytes <= 0
                || maxRedirects < 0 || maxRedirects > 5 || maxAttempts < 1
                || ffprobePath == null || ffprobePath.isBlank()
                || allowedVideoCodecs == null || allowedVideoCodecs.isEmpty()
                || allowedVideoCodecs.stream().map(value -> value == null ? "" : value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> !value.matches("^[a-z0-9_]{1,32}$"))) {
            throw new IllegalStateException("Asset ingestion limits must be positive and bounded");
        }
        oss.validate();
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    public static final class Oss {
        private String endpoint = "";
        private String region = "";
        private String bucket = "";
        private String accessKeyId = "";
        private String accessKeySecret = "";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String value) { endpoint = value; }
        public String getRegion() { return region; }
        public void setRegion(String value) { region = value; }
        public String getBucket() { return bucket; }
        public void setBucket(String value) { bucket = value; }
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String value) { accessKeyId = value; }
        public String getAccessKeySecret() { return accessKeySecret; }
        public void setAccessKeySecret(String value) { accessKeySecret = value; }

        private void validate() {
            if (isBlank(endpoint) || !endpoint.startsWith("https://") || isBlank(region)
                    || isBlank(bucket) || isBlank(accessKeyId) || isBlank(accessKeySecret)) {
                throw new IllegalStateException("HTTPS OSS endpoint, region, bucket and credentials are required");
            }
        }

        private boolean isBlank(String value) { return value == null || value.isBlank(); }
    }
}
