package com.guanghe.backend.billing.invoice;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.BillingAccess;
import com.guanghe.backend.catalog.PageResponse;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator surface for invoice applications.
 *
 * <p>Thin on purpose: the state machine lives in {@link InvoiceApplicationService} so the payer's
 * withdrawal and an operator's approval go through the same compare-and-set. This class adds the
 * role gate and nothing else, which is what keeps the two surfaces from drifting apart.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InvoiceAdminService {

    static final Set<String> STATUSES = Set.of(
            InvoiceApplicationService.STATUS_SUBMITTED,
            InvoiceApplicationService.STATUS_UNDER_REVIEW,
            InvoiceApplicationService.STATUS_APPROVED,
            InvoiceApplicationService.STATUS_DELIVERED,
            InvoiceApplicationService.STATUS_REJECTED,
            InvoiceApplicationService.STATUS_CANCELLED);

    private final InvoiceApplicationService applications;

    public InvoiceAdminService(InvoiceApplicationService applications) {
        this.applications = applications;
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceDtos.InvoiceApplicationResponse> list(AuthenticatedAccount actor,
                                                                    Long userId, String status,
                                                                    int page, int size) {
        BillingAccess.requireAdmin(actor);
        return applications.listForOperator(userId, status, page, size);
    }

    @Transactional(readOnly = true)
    public InvoiceDtos.InvoiceApplicationResponse get(AuthenticatedAccount actor, long id) {
        BillingAccess.requireAdmin(actor);
        return applications.getForOperator(id);
    }

    @Transactional
    public InvoiceDtos.InvoiceApplicationResponse accept(AuthenticatedAccount actor, long id) {
        return applications.accept(BillingAccess.requireAdmin(actor), id);
    }

    @Transactional
    public InvoiceDtos.InvoiceApplicationResponse approve(AuthenticatedAccount actor, long id) {
        return applications.approve(BillingAccess.requireAdmin(actor), id);
    }

    @Transactional
    public InvoiceDtos.InvoiceApplicationResponse reject(AuthenticatedAccount actor, long id,
                                                         String reason) {
        return applications.reject(BillingAccess.requireAdmin(actor), id, reason);
    }

    /** Records the operator's confirmation after they have sent the issued invoice externally. */
    @Transactional
    public InvoiceDtos.InvoiceApplicationResponse deliver(AuthenticatedAccount actor, long id,
                                                          String invoiceNumber) {
        return applications.deliver(BillingAccess.requireAdmin(actor), id, invoiceNumber);
    }
}
