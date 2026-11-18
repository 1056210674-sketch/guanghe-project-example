package com.guanghe.backend.billing.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Feishu custom bot text webhook; a 2xx without a success code is not acceptance. */
@Component
final class FeishuInvoiceWebhookClient implements InvoiceWebhookClient {
    private final InvoiceWebhookProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http;

    @Autowired
    FeishuInvoiceWebhookClient(InvoiceWebhookProperties properties, ObjectMapper json,
                               @Qualifier("billingClock") Clock clock) {
        this(properties, json, clock, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    FeishuInvoiceWebhookClient(InvoiceWebhookProperties properties, ObjectMapper json,
                               Clock clock, HttpClient http) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.http = http;
    }

    @Override
    public void send(InvoiceRecords.InvoiceApplication application) {
        if (!properties.configured()) throw new InvoiceWebhookException("WEBHOOK_NOT_CONFIGURED");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "text");
        payload.put("content", Map.of("text", message(application)));
        if (!properties.getSigningSecret().isBlank()) {
            long timestamp = clock.instant().getEpochSecond();
            payload.put("timestamp", Long.toString(timestamp));
            payload.put("sign", sign(timestamp, properties.getSigningSecret()));
        }
        try {
            String body = json.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getUrl()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new InvoiceWebhookException("WEBHOOK_HTTP_REJECTED");
            }
            JsonNode result = json.readTree(response.body());
            JsonNode code = result == null ? null : result.get("code");
            if (code == null) code = result == null ? null : result.get("StatusCode");
            if (code == null || !code.isIntegralNumber() || code.intValue() != 0) {
                throw new InvoiceWebhookException("WEBHOOK_RESPONSE_REJECTED");
            }
        } catch (InvoiceWebhookException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InvoiceWebhookException("WEBHOOK_TRANSPORT_UNKNOWN");
        } catch (IOException exception) {
            throw new InvoiceWebhookException("WEBHOOK_TRANSPORT_UNKNOWN");
        } catch (RuntimeException exception) {
            throw new InvoiceWebhookException("WEBHOOK_RESPONSE_INVALID");
        }
    }

    private static String message(InvoiceRecords.InvoiceApplication a) {
        return String.join("\n", "光禾AI智能体框架发票申请（请以管理台最新状态为准）",
                "申请单号：" + a.applicationNo(),
                "发票类型：" + ("VAT_SPECIAL".equals(a.invoiceType()) ? "增值税专用发票" : "增值税普通发票"),
                "抬头类型：" + ("COMPANY".equals(a.titleType()) ? "单位" : "个人"),
                "抬头：" + oneLine(a.title()),
                "税号：" + oneLine(a.taxNumber()),
                "申请金额：" + String.format(java.util.Locale.ROOT, "%d.%02d 元",
                        a.amountCents() / 100, a.amountCents() % 100),
                "接收邮箱：" + oneLine(a.deliveryEmail()),
                "备注：" + oneLine(a.remark()),
                "处理步骤：线下开票并发至申请邮箱，随后在管理台确认已人工发出。",
                "飞书接收不等于发票已发送；重复通知按申请单号去重。");
    }

    private static String oneLine(String value) {
        return value == null ? "—" : value.replaceAll("[\\p{Cntrl}\\p{Z}]+", " ").trim();
    }

    private static String sign(long timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((timestamp + "\n" + secret)
                    .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (Exception exception) {
            throw new InvoiceWebhookException("WEBHOOK_SIGNING_FAILED");
        }
    }
}
