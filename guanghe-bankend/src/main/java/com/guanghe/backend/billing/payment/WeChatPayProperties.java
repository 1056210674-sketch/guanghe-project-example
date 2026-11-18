package com.guanghe.backend.billing.payment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WeChat Pay Native merchant configuration.
 *
 * <p>Every credential is optional at the type level so the application can boot without them, but
 * the provider stays fail-closed until all of them are present. Secret values never reach logs, the
 * Browser API or the database; only the <em>names</em> of missing prerequisites are reported.
 */
@ConfigurationProperties("zhixiaohe.billing.wechat-pay")
public class WeChatPayProperties {
    private boolean enabled;
    private String merchantId = "";
    private String appId = "";
    private String apiV3Key = "";
    private String merchantSerialNumber = "";
    private String privateKey = "";
    /** WeChat platform certificate (PEM) used to verify inbound callback signatures. */
    private String platformCertificate = "";
    private String notifyUrl = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(10);
    private Duration callbackTolerance = Duration.ofMinutes(5);

    /** True only when every value required to talk to WeChat Pay is present. */
    public boolean configured() {
        return missingRequirements().isEmpty();
    }

    /** Names of missing prerequisites. Names only — never values. */
    public List<String> missingRequirements() {
        List<String> missing = new ArrayList<>();
        if (!enabled) missing.add("enabled");
        if (merchantId.isBlank()) missing.add("merchantId");
        if (appId.isBlank()) missing.add("appId");
        if (apiV3Key.isBlank()) missing.add("apiV3Key");
        if (merchantSerialNumber.isBlank()) missing.add("merchantSerialNumber");
        if (privateKey.isBlank()) missing.add("privateKey");
        // Without the platform certificate a callback cannot be verified at all, so a merchant that
        // is otherwise fully credentialed is still not a usable payment provider.
        if (platformCertificate.isBlank()) missing.add("platformCertificate");
        if (notifyUrl.isBlank()) missing.add("notifyUrl");
        return List.copyOf(missing);
    }

    /** True when a callback can be cryptographically verified. */
    public boolean callbackVerifiable() {
        return !platformCertificate.isBlank();
    }

    /** Deliberately redacted: this object must survive being logged or dumped in a report. */
    @Override
    public String toString() {
        return "WeChatPayProperties{enabled=" + enabled + ", merchantId=" + redact(merchantId)
                + ", appId=" + appId + ", apiV3Key=" + redact(apiV3Key)
                + ", merchantSerialNumber=" + redact(merchantSerialNumber)
                + ", privateKey=" + redact(privateKey)
                + ", platformCertificate=" + redact(platformCertificate)
                + ", notifyUrl=" + notifyUrl + '}';
    }

    private static String redact(String value) {
        return value == null || value.isBlank() ? "<absent>" : "<set>";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId == null ? "" : merchantId;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId == null ? "" : appId;
    }

    public String getApiV3Key() {
        return apiV3Key;
    }

    public void setApiV3Key(String apiV3Key) {
        this.apiV3Key = apiV3Key == null ? "" : apiV3Key;
    }

    public String getMerchantSerialNumber() {
        return merchantSerialNumber;
    }

    public void setMerchantSerialNumber(String merchantSerialNumber) {
        this.merchantSerialNumber = merchantSerialNumber == null ? "" : merchantSerialNumber;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey == null ? "" : privateKey;
    }

    public String getPlatformCertificate() {
        return platformCertificate;
    }

    public void setPlatformCertificate(String platformCertificate) {
        this.platformCertificate = platformCertificate == null ? "" : platformCertificate;
    }

    public String getNotifyUrl() {
        return notifyUrl;
    }

    public void setNotifyUrl(String notifyUrl) {
        this.notifyUrl = notifyUrl == null ? "" : notifyUrl;
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

    public Duration getCallbackTolerance() {
        return callbackTolerance;
    }

    public void setCallbackTolerance(Duration callbackTolerance) {
        this.callbackTolerance = callbackTolerance;
    }
}
