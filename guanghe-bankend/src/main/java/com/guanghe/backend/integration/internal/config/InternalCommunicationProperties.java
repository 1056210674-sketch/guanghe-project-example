package com.guanghe.backend.integration.internal.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhixiaohe.internal")
public class InternalCommunicationProperties {

    private boolean enabled = true;
    private boolean javaM1Ready;
    private boolean javaM1ServerReady;
    private M0GateStatus m0GateStatus = M0GateStatus.NOT_RUN;
    private String keyId = "agent-service-v1";
    private String secret = "";
    private Duration allowedClockSkew = Duration.ofSeconds(60);
    private Duration nonceTtl = Duration.ofSeconds(120);
    private String nonceKeyPrefix = "zhixiaohe:internal:hmac:nonce:";
    private int maxBodyBytes = 1_048_576;
    private boolean reverseProbeEnabled;
    private final Python python = new Python();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isJavaM1Ready() {
        return javaM1Ready;
    }

    public void setJavaM1Ready(boolean javaM1Ready) {
        this.javaM1Ready = javaM1Ready;
    }

    public boolean isCodeCapabilitiesReady() {
        return javaM1Ready;
    }

    public boolean isTestServerDeployed() {
        return javaM1ServerReady;
    }

    public void setJavaM1ServerReady(boolean javaM1ServerReady) {
        this.javaM1ServerReady = javaM1ServerReady;
    }

    public M0GateStatus getM0GateStatus() {
        return m0GateStatus;
    }

    public void setM0GateStatus(M0GateStatus m0GateStatus) {
        this.m0GateStatus = m0GateStatus;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    public Duration getAllowedClockSkew() {
        return allowedClockSkew;
    }

    public void setAllowedClockSkew(Duration allowedClockSkew) {
        this.allowedClockSkew = allowedClockSkew;
    }

    public Duration getNonceTtl() {
        return nonceTtl;
    }

    public void setNonceTtl(Duration nonceTtl) {
        this.nonceTtl = nonceTtl;
    }

    public String getNonceKeyPrefix() {
        return nonceKeyPrefix;
    }

    public void setNonceKeyPrefix(String nonceKeyPrefix) {
        this.nonceKeyPrefix = nonceKeyPrefix;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public boolean isReverseProbeEnabled() {
        return reverseProbeEnabled;
    }

    public void setReverseProbeEnabled(boolean reverseProbeEnabled) {
        this.reverseProbeEnabled = reverseProbeEnabled;
    }

    public Python getPython() {
        return python;
    }

    public static class Python {

        private String baseUrl = "http://127.0.0.1:8000";
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(2);
        private Duration streamReadTimeout = Duration.ofMinutes(3);
        private int maxResponseBytes = 1_048_576;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Duration getStreamReadTimeout() {
            return streamReadTimeout;
        }

        public void setStreamReadTimeout(Duration streamReadTimeout) {
            this.streamReadTimeout = streamReadTimeout;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }
    }

    public enum M0GateStatus {
        NOT_RUN,
        PASS,
        FAIL
    }
}
