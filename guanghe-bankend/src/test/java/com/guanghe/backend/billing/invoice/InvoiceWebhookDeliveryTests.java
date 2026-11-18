package com.guanghe.backend.billing.invoice;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Feishu acceptance is independent of manual invoice completion and requires a body code. */
class InvoiceWebhookDeliveryTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"),
            ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    @Test
    void missingWebhookKeepsTheSubmittedApplicationQueuedAndVisible() {
        InvoiceMapper mapper = mock(InvoiceMapper.class);
        InvoiceWebhookClient client = mock(InvoiceWebhookClient.class);
        InvoiceWebhookProperties properties = new InvoiceWebhookProperties();
        when(mapper.lockNextWebhook(NOW)).thenReturn(Optional.of(pending()));
        when(mapper.findById(10L)).thenReturn(Optional.of(application()));

        new InvoiceWebhookDispatchService(mapper, client, properties, CLOCK).dispatchOne();

        verify(mapper).webhookFailed(eq(10L), eq("WAITING_CONFIG"), any(),
                eq("WEBHOOK_NOT_CONFIGURED"), eq(NOW));
        verify(mapper, never()).webhookSent(eq(10L), any());
        verify(client, never()).send(any());
    }

    @Test
    void acceptedWebhookMarksOnlyTheOutboxRowSent() {
        InvoiceMapper mapper = mock(InvoiceMapper.class);
        InvoiceWebhookClient client = mock(InvoiceWebhookClient.class);
        InvoiceWebhookProperties properties = configured();
        when(mapper.lockNextWebhook(NOW)).thenReturn(Optional.of(pending()));
        when(mapper.findById(10L)).thenReturn(Optional.of(application()));

        new InvoiceWebhookDispatchService(mapper, client, properties, CLOCK).dispatchOne();

        verify(client).send(any());
        verify(mapper).webhookSent(10L, NOW);
        verify(mapper, never()).transition(anyLong(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void feishuRequiresBodyCodeZeroNotMerelyHttpTwoHundred() throws Exception {
        assertAccepted(200, "{\"code\":0}");
        assertAccepted(200, "{\"StatusCode\":0}");
        assertRejected(200, "{\"code\":19024}");
        assertRejected(200, "{}");
        assertRejected(503, "{\"code\":0}");
    }

    private static void assertAccepted(int status, String body) throws Exception {
        client(status, body).send(application());
    }

    private static void assertRejected(int status, String body) throws Exception {
        assertThatThrownBy(() -> client(status, body).send(application()))
                .isInstanceOf(InvoiceWebhookException.class);
    }

    @SuppressWarnings("unchecked")
    private static FeishuInvoiceWebhookClient client(int status, String body) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return new FeishuInvoiceWebhookClient(configured(), new ObjectMapper(), CLOCK, http);
    }

    private static InvoiceWebhookProperties configured() {
        InvoiceWebhookProperties properties = new InvoiceWebhookProperties();
        properties.setUrl("https://open.feishu.cn/open-apis/bot/v2/hook/abc123");
        return properties;
    }

    private static InvoiceRecords.WebhookDelivery pending() {
        return new InvoiceRecords.WebhookDelivery(10L, "PENDING", 0, NOW, null, null);
    }

    private static InvoiceRecords.InvoiceApplication application() {
        return new InvoiceRecords.InvoiceApplication(10L, "I10", 20L, "SUBMITTED",
                "VAT_NORMAL", "PERSONAL", "张三", null, 10000L, "payer@example.com",
                null, null, null, null, null, "key", "hash", NOW, null, null, null,
                null, null, 1, NOW, NOW, null, null, "PENDING", null);
    }
}
