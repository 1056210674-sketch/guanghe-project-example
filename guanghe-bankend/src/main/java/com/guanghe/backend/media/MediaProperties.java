package com.guanghe.backend.media;

import java.net.URI;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhixiaohe.media")
public class MediaProperties {
    private boolean enabled;
    private boolean executionEnabled;
    private boolean resourceSyncEnabled;
    private boolean orderPollEnabled;
    private String baseUrl = "";
    private String appKey = "";
    private String providerCode = "MEDIA_PROCUREMENT";
    private Set<String> supportedModelIds = new LinkedHashSet<>(Set.of("1"));
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(20);
    private Duration quoteTtl = Duration.ofMinutes(10);
    private Duration resourceMaxAge = Duration.ofHours(36);
    private Duration unknownPollDelay = Duration.ofMinutes(2);
    private Duration acceptedPollDelay = Duration.ofMinutes(10);
    private int syncPageSize = 100;
    private int maxSyncPages = 500;
    // Uses the existing supplier HTML protocol; individual media still require an explicit image price.
    private boolean imageSubmissionEnabled;
    private Set<String> imageVerifiedResourceIds = new LinkedHashSet<>();
    private String imageContractVersion = "";
    private Map<String, ImagePricingRule> imagePricingRules = new LinkedHashMap<>();
    private int imageMaxCount = 10;
    private long imageMaxBytes = 10L * 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isExecutionEnabled() { return executionEnabled; }
    public void setExecutionEnabled(boolean value) { executionEnabled = value; }
    public boolean isResourceSyncEnabled() { return resourceSyncEnabled; }
    public void setResourceSyncEnabled(boolean value) { resourceSyncEnabled = value; }
    public boolean isOrderPollEnabled() { return orderPollEnabled; }
    public void setOrderPollEnabled(boolean value) { orderPollEnabled = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value == null ? "" : value.trim(); }
    public String getAppKey() { return appKey; }
    public void setAppKey(String value) { appKey = value == null ? "" : value; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String value) { providerCode = value; }
    public Set<String> getSupportedModelIds() { return Set.copyOf(supportedModelIds); }
    public void setSupportedModelIds(Set<String> value) {
        supportedModelIds = value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value);
    }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }
    public Duration getQuoteTtl() { return quoteTtl; }
    public void setQuoteTtl(Duration value) { quoteTtl = value; }
    public Duration getResourceMaxAge() { return resourceMaxAge; }
    public void setResourceMaxAge(Duration value) { resourceMaxAge = value; }
    public Duration getUnknownPollDelay() { return unknownPollDelay; }
    public void setUnknownPollDelay(Duration value) { unknownPollDelay = value; }
    public Duration getAcceptedPollDelay() { return acceptedPollDelay; }
    public void setAcceptedPollDelay(Duration value) { acceptedPollDelay = value; }
    public int getSyncPageSize() { return syncPageSize; }
    public void setSyncPageSize(int value) { syncPageSize = value; }
    public int getMaxSyncPages() { return maxSyncPages; }
    public void setMaxSyncPages(int value) { maxSyncPages = value; }
    public boolean isImageSubmissionEnabled() { return imageSubmissionEnabled; }
    public void setImageSubmissionEnabled(boolean value) { imageSubmissionEnabled = value; }
    public Set<String> getImageVerifiedResourceIds() { return Set.copyOf(imageVerifiedResourceIds); }
    public void setImageVerifiedResourceIds(Set<String> value) {
        imageVerifiedResourceIds = value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value);
    }
    public String getImageContractVersion() { return imageContractVersion; }
    public void setImageContractVersion(String value) { imageContractVersion = value == null ? "" : value.strip(); }
    public Map<String, ImagePricingRule> getImagePricingRules() { return Map.copyOf(imagePricingRules); }
    public void setImagePricingRules(Map<String, ImagePricingRule> value) {
        imagePricingRules = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public static final class ImagePricingRule {
        private String mode = "";
        private BigDecimal amount;
        private String version = "";
        public String getMode() { return mode; }
        public void setMode(String value) { mode = value == null ? "" : value.strip(); }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal value) { amount = value; }
        public String getVersion() { return version; }
        public void setVersion(String value) { version = value == null ? "" : value.strip(); }
        boolean valid() {
            return Set.of("INCLUDED", "PER_IMAGE", "PER_ARTICLE", "LEGACY_PER_IMAGE").contains(mode)
                    && version.matches("[A-Za-z0-9._:-]{1,100}")
                    && ("LEGACY_PER_IMAGE".equals(mode) ? amount == null : amount != null
                        && amount.scale() <= 4 && amount.signum() >= 0
                        && amount.compareTo(new BigDecimal("100000")) <= 0
                        && (!"INCLUDED".equals(mode) || amount.signum() == 0));
        }
    }
    public int getImageMaxCount() { return imageMaxCount; }
    public void setImageMaxCount(int value) { imageMaxCount = value; }
    public long getImageMaxBytes() { return imageMaxBytes; }
    public void setImageMaxBytes(long value) { imageMaxBytes = value; }

    public boolean imageContractConfigured() {
        return imageSubmissionEnabled
                && imageVerifiedResourceIds.stream().allMatch(id -> id.matches("[A-Za-z0-9._:-]{1,64}"))
                && imageContractVersion.matches("[A-Za-z0-9._:-]{1,100}")
                && imagePricingRules.entrySet().stream().allMatch(entry ->
                    entry.getKey().matches("1:[A-Za-z0-9._:-]{1,64}")
                        && entry.getValue() != null && entry.getValue().valid())
                && imageMaxCount >= 1 && imageMaxCount <= 10
                && imageMaxBytes >= 1 && imageMaxBytes <= 10L * 1024 * 1024;
    }

    public boolean providerConfigured() {
        return !baseUrl.isBlank() && StandardCharsets.US_ASCII.newEncoder().canEncode(appKey)
                && appKey.getBytes(StandardCharsets.US_ASCII).length == 32;
    }

    public void validate() {
        if (!enabled) {
            if (executionEnabled || resourceSyncEnabled || orderPollEnabled || imageSubmissionEnabled) {
                throw new IllegalStateException("MEDIA_ENABLED must be true before media background work is enabled");
            }
            return;
        }
        if (!providerConfigured()) {
            throw new IllegalStateException("MEDIA_API_BASE_URL and a 32-byte MEDIA_API_APP_KEY are required");
        }
        URI uri;
        try { uri = URI.create(baseUrl); } catch (RuntimeException exception) {
            throw new IllegalStateException("MEDIA_API_BASE_URL must be a valid HTTPS origin", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("MEDIA_API_BASE_URL must be a valid HTTPS origin");
        }
        if (providerCode == null || !providerCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalStateException("MEDIA_PROVIDER_CODE is invalid");
        }
        if (!Set.of("1").equals(supportedModelIds)) {
            throw new IllegalStateException("MEDIA_SUPPORTED_MODEL_IDS must contain only the verified model 1");
        }
        if (imageSubmissionEnabled && !imageContractConfigured()) {
            throw new IllegalStateException("Media images require an HTML contract reference, valid media pricing rules, image count and size bounds");
        }
        if (!positive(connectTimeout) || !positive(readTimeout) || !positive(quoteTtl)
                || !positive(resourceMaxAge)
                || !positive(unknownPollDelay) || !positive(acceptedPollDelay)
                || syncPageSize < 1 || syncPageSize > 500 || maxSyncPages < 1 || maxSyncPages > 1000) {
            throw new IllegalStateException("Media timeout and synchronization bounds must be positive");
        }
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
