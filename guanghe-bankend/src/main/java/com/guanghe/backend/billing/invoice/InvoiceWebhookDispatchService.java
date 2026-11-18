package com.guanghe.backend.billing.invoice;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.BillingAccess;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Drains durable invoice intake rows. Feishu acceptance never changes invoice lifecycle status. */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InvoiceWebhookDispatchService {
    private final InvoiceMapper mapper;
    private final InvoiceWebhookClient client;
    private final InvoiceWebhookProperties properties;
    private final Clock clock;

    InvoiceWebhookDispatchService(InvoiceMapper mapper, InvoiceWebhookClient client,
                                  InvoiceWebhookProperties properties,
                                  @Qualifier("billingClock") Clock clock) {
        this.mapper = mapper;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    /** One row is locked across the short HTTP call so two workers cannot send concurrently. */
    @Transactional
    public void dispatchOne() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        InvoiceRecords.WebhookDelivery pending = mapper.lockNextWebhook(now).orElse(null);
        if (pending == null) return;
        InvoiceRecords.InvoiceApplication application = mapper.findById(pending.applicationId())
                .orElseThrow(() -> new IllegalStateException("Invoice webhook row has no application"));
        if (InvoiceApplicationService.STATUS_REJECTED.equals(application.status())
                || InvoiceApplicationService.STATUS_CANCELLED.equals(application.status())
                || InvoiceApplicationService.STATUS_DELIVERED.equals(application.status())) {
            mapper.skipWebhook(application.id(), now);
            return;
        }
        if (!properties.configured()) {
            mapper.webhookFailed(application.id(), "WAITING_CONFIG", now.plusMinutes(10),
                    "WEBHOOK_NOT_CONFIGURED", now);
            return;
        }
        try {
            client.send(application);
            mapper.webhookSent(application.id(), OffsetDateTime.now(clock));
        } catch (InvoiceWebhookException exception) {
            mapper.webhookFailed(application.id(), "PENDING",
                    now.plusSeconds(backoffSeconds(pending.attempts())), exception.code(), now);
        }
    }

    /** Manual expedite only changes the due time; it never pretends Feishu accepted anything. */
    @Transactional
    public InvoiceDtos.InvoiceApplicationResponse retry(AuthenticatedAccount actor, long id) {
        BillingAccess.requireAdmin(actor);
        InvoiceRecords.InvoiceApplication application = mapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVOICE_APPLICATION_NOT_FOUND));
        if (!InvoiceApplicationService.STATUS_SUBMITTED.equals(application.status())
                && !InvoiceApplicationService.STATUS_UNDER_REVIEW.equals(application.status())
                && !InvoiceApplicationService.STATUS_APPROVED.equals(application.status())) {
            throw new BusinessException(ErrorCode.INVOICE_STATE_CONFLICT);
        }
        if (mapper.retryWebhook(id, OffsetDateTime.now(clock)) != 1) {
            throw new BusinessException(ErrorCode.INVOICE_STATE_CONFLICT);
        }
        return InvoiceApplicationService.response(mapper.findById(id).orElseThrow());
    }

    private static long backoffSeconds(int attempts) {
        return Math.min(3600L, 60L << Math.min(attempts, 6));
    }
}
