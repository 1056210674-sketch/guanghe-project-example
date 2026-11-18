package com.guanghe.backend.billing.mail;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mail transport settings.
 *
 * <p>Defaults to switched off with no host. That is the intended state of this build: nothing here
 * may send a real message, so the provider reports the missing prerequisites by name and every call
 * that would deliver refuses.
 *
 * <p>{@code missingRequirements()} returns property <em>names</em> only. Values — especially the
 * password — are never returned, logged or put in an error message.
 */
@ConfigurationProperties("zhixiaohe.mail-delivery")
public class MailDeliveryProperties {

    /** Master switch. Off means no message may leave this service by any route. */
    private boolean enabled = false;

    /** Transport code persisted on delivery receipts, e.g. {@code SMTP}. */
    private String provider = "SMTP";

    private String host = "";

    private int port = 465;

    /** Envelope sender. Must be a real address before delivery can be enabled. */
    private String from = "";

    private String username = "";

    private String password = "";

    /** Whether the transport uses implicit TLS. */
    private boolean ssl = true;

    /** Per-connection timeout, so a misconfigured host cannot hang a request thread. */
    private int timeoutMillis = 10_000;

    /** Every prerequisite that is currently absent, in a stable order. */
    public List<String> missingRequirements() {
        List<String> missing = new ArrayList<>();
        if (!enabled) missing.add("enabled");
        if (isBlank(host)) missing.add("host");
        if (port <= 0 || port > 65535) missing.add("port");
        if (isBlank(from)) missing.add("from");
        if (isBlank(username)) missing.add("username");
        if (isBlank(password)) missing.add("password");
        return List.copyOf(missing);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }
}
