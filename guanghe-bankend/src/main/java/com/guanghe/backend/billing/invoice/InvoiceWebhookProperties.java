package com.guanghe.backend.billing.invoice;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Feishu custom bot credentials. Values are never returned to a browser or logged. */
@ConfigurationProperties("zhixiaohe.billing.invoice-webhook")
public class InvoiceWebhookProperties {
    private String url = "";
    private String signingSecret = "";
    private boolean workerEnabled = true;

    public boolean configured() {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme())
                    && ("open.feishu.cn".equals(uri.getHost())
                        || "open.larksuite.com".equals(uri.getHost()))
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getPath().matches("/open-apis/bot/v2/hook/[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public String getUrl() { return url; }
    public void setUrl(String value) { url = value; }
    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String value) { signingSecret = value; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean value) { workerEnabled = value; }
}
