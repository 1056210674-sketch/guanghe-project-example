package com.guanghe.backend.billing;

import com.guanghe.backend.billing.payment.PaymentProvider;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The callback boundary is the only unauthenticated way into the commercial control plane, so these
 * tests are about what it refuses.
 */
class PaymentCallbackServiceTests {

    private static final String RAW_BODY = "{\"id\":\"evt-1\",\"resource\":{\"ciphertext\":\"...\"}}";

    private StubPaymentProvider provider;
    private RechargeOrderService orders;
    private PaymentCallbackService service;

    @BeforeEach
    void setUp() {
        provider = StubPaymentProvider.payable();
        orders = mock(RechargeOrderService.class);
        service = new PaymentCallbackService(provider, orders);
    }

    @Test
    void aCallbackThatFailsSignatureVerificationNeverReachesAnOrder() {
        provider.withCallbackFailure(new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID));

        assertThatThrownBy(() -> service.accept(Map.of("wechatpay-signature", "bad"), RAW_BODY))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_CALLBACK_INVALID);
        verifyNoInteractions(orders);
    }

    @Test
    void anUnverifiableCallbackIsRefusedRatherThanTreatedAsSuccess() {
        provider.withCallbackFailure(new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED));

        assertThatThrownBy(() -> service.accept(Map.of(), RAW_BODY))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
        verifyNoInteractions(orders);
    }

    @Test
    void aCallbackWithoutAnEventIdIsRefusedBecauseItCouldNeverBeDeduplicated() {
        provider.withEnvelope(new PaymentProvider.CallbackEnvelope("  ", "STUB-ORDER-1", 10_000L,
                PaymentProvider.TradeState.SUCCESS, null, "0".repeat(64), "{}"));

        assertThatThrownBy(() -> service.accept(Map.of(), RAW_BODY))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_CALLBACK_INVALID);
        verifyNoInteractions(orders);
    }

    @Test
    void aCallbackForAnUnknownOrderIsRecordedForReconciliationAndNeverCredited() {
        provider.withEnvelope(StubPaymentProvider.envelope("evt-unknown", "STUB-ORDER-404", 10_000L,
                PaymentProvider.TradeState.SUCCESS, null));
        when(orders.findByProviderReference(anyString(), anyString())).thenReturn(null);
        when(orders.unmatched(anyString(), anyString()))
                .thenReturn(RechargeOrderService.Settlement.UNMATCHED);

        assertThat(service.accept(Map.of(), RAW_BODY))
                .isEqualTo(RechargeOrderService.Settlement.UNMATCHED);
        verify(orders).unmatched("STUB-ORDER-404", "evt-unknown");
        verify(orders, never()).applySnapshot(any(), any(), anyString(), any());
    }

    @Test
    void anAuthenticatedCallbackIsReconciledThroughTheSharedSnapshotPath() {
        OffsetDateTime paidAt = OffsetDateTime.parse("2026-09-18T09:59:00Z");
        provider.withEnvelope(StubPaymentProvider.envelope("evt-1", "STUB-ORDER-1", 10_000L,
                PaymentProvider.TradeState.SUCCESS, paidAt));
        BillingRecords.RechargeOrder order = order();
        when(orders.findByProviderReference(StubPaymentProvider.CODE, "STUB-ORDER-1"))
                .thenReturn(order);
        when(orders.applySnapshot(any(), any(), anyString(), any()))
                .thenReturn(RechargeOrderService.Settlement.APPLIED);

        assertThat(service.accept(Map.of(), RAW_BODY))
                .isEqualTo(RechargeOrderService.Settlement.APPLIED);

        ArgumentCaptor<PaymentProvider.ProviderOrderSnapshot> captor =
                ArgumentCaptor.forClass(PaymentProvider.ProviderOrderSnapshot.class);
        verify(orders).applySnapshot(eq(order), captor.capture(),
                eq(PaymentCallbackService.SOURCE_PROVIDER_CALLBACK), eq("0".repeat(64)));
        PaymentProvider.ProviderOrderSnapshot snapshot = captor.getValue();
        assertThat(snapshot.providerOrderNo()).isEqualTo("STUB-ORDER-1");
        assertThat(snapshot.tradeState()).isEqualTo(PaymentProvider.TradeState.SUCCESS);
        assertThat(snapshot.amountCents()).isEqualTo(10_000L);
        assertThat(snapshot.providerEventId()).isEqualTo("evt-1");
        assertThat(snapshot.paidAt()).isEqualTo(paidAt);
    }

    @Test
    void onlyThePayloadHashIsForwardedNeverTheRawBody() {
        // The raw body may contain buyer identifiers. Nothing downstream may be able to store it.
        provider.withEnvelope(StubPaymentProvider.envelope("evt-1", "STUB-ORDER-1", 10_000L,
                PaymentProvider.TradeState.SUCCESS, null));
        when(orders.findByProviderReference(anyString(), anyString())).thenReturn(order());
        when(orders.applySnapshot(any(), any(), anyString(), any()))
                .thenReturn(RechargeOrderService.Settlement.APPLIED);

        service.accept(Map.of(), RAW_BODY);

        ArgumentCaptor<PaymentProvider.ProviderOrderSnapshot> captor =
                ArgumentCaptor.forClass(PaymentProvider.ProviderOrderSnapshot.class);
        verify(orders).applySnapshot(any(), captor.capture(), anyString(), eq("0".repeat(64)));
        assertThat(captor.getValue().toString()).doesNotContain(RAW_BODY).doesNotContain("ciphertext");
    }

    @Test
    void aProviderReportedTerminalStateIsHandedToTheSameReconciliationPath() {
        provider.withEnvelope(StubPaymentProvider.envelope("evt-closed", "STUB-ORDER-1", 10_000L,
                PaymentProvider.TradeState.CLOSED, null));
        when(orders.findByProviderReference(anyString(), anyString())).thenReturn(order());
        when(orders.applySnapshot(any(), any(), anyString(), any()))
                .thenReturn(RechargeOrderService.Settlement.NO_CHANGE);

        assertThat(service.accept(Map.of(), RAW_BODY))
                .isEqualTo(RechargeOrderService.Settlement.NO_CHANGE);

        ArgumentCaptor<PaymentProvider.ProviderOrderSnapshot> captor =
                ArgumentCaptor.forClass(PaymentProvider.ProviderOrderSnapshot.class);
        verify(orders).applySnapshot(any(), captor.capture(), anyString(), any());
        assertThat(captor.getValue().tradeState()).isEqualTo(PaymentProvider.TradeState.CLOSED);
    }

    private static BillingRecords.RechargeOrder order() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-18T10:00:00Z");
        return new BillingRecords.RechargeOrder(9001L, "R9001", 5001L,
                RechargeOrderService.STATUS_PAYING, StubPaymentProvider.CODE, 10_000L, 10_000, 0,
                "STUB-ORDER-1", null, null, "5001:key", "0".repeat(64), null, null,
                now.plusMinutes(30), null, null, null, null, 2, now, now);
    }
}
