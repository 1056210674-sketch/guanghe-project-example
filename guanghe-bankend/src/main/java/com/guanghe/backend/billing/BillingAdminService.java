package com.guanghe.backend.billing;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read and reconciliation surface for administrators.
 *
 * <p>Every method re-checks the caller's role in the service layer. The audit query is read-only by
 * construction: there is no update or delete path anywhere in this class, which is what makes the
 * operational audit trail append-only in practice and not just in the schema comment.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BillingAdminService {

    private static final Set<String> ORDER_STATUSES = Set.of(
            RechargeOrderService.STATUS_CREATED, RechargeOrderService.STATUS_PAYING,
            RechargeOrderService.STATUS_SUCCEEDED, RechargeOrderService.STATUS_CLOSED,
            RechargeOrderService.STATUS_EXPIRED, RechargeOrderService.STATUS_FAILED);

    private static final Set<String> ACTOR_TYPES = Set.of(
            OperationalAuditService.ACTOR_USER, OperationalAuditService.ACTOR_ADMIN,
            OperationalAuditService.ACTOR_SYSTEM, OperationalAuditService.ACTOR_PAYMENT_PROVIDER,
            OperationalAuditService.ACTOR_MAIL_PROVIDER);

    private final BillingMapper mapper;
    private final RechargeOrderService orders;

    public BillingAdminService(BillingMapper mapper, RechargeOrderService orders) {
        this.mapper = mapper;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public PageResponse<BillingDtos.RechargeOrderResponse> listOrders(AuthenticatedAccount actor,
                                                                     Long userId, String status,
                                                                     int page, int size) {
        BillingAccess.requireAdmin(actor);
        String normalized = status == null || status.isBlank() ? null : status;
        if (normalized != null && !ORDER_STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long offset = (long) (page - 1) * size;
        List<BillingDtos.RechargeOrderResponse> items = mapper
                .findOrdersForAdmin(userId, normalized, offset, size).stream()
                .map(orders::toResponse).toList();
        return PageResponse.of(items, page, size, mapper.countOrdersForAdmin(userId, normalized));
    }

    @Transactional(readOnly = true)
    public BillingDtos.RechargeOrderResponse getOrder(AuthenticatedAccount actor, long orderId) {
        BillingAccess.requireAdmin(actor);
        return orders.toResponse(orders.require(orderId));
    }

    /**
     * Asks the provider for the authoritative state of any account's order.
     *
     * <p>Fail-closed: with no payable provider this refuses instead of assuming an outcome, and a
     * disagreement between the provider's amount and the order is reported, never credited.
     */
    @Transactional
    public RechargeOrderService.QueryResult reconcileOrder(AuthenticatedAccount actor, long orderId) {
        BillingAccess.requireAdmin(actor);
        return orders.reconcile(orderId);
    }

    @Transactional(readOnly = true)
    public PageResponse<BillingDtos.OperationalAuditEventResponse> listAuditEvents(
            AuthenticatedAccount actor, String actorType, String targetType, Long targetId,
            String eventType, int page, int size) {
        BillingAccess.requireAdmin(actor);
        String normalizedActor = optionalEnum(actorType, ACTOR_TYPES);
        String normalizedTarget = optionalPattern(targetType, "^[A-Z][A-Z0-9_]{0,31}$");
        String normalizedEvent = optionalPattern(eventType, "^[A-Z][A-Z0-9_]{0,47}$");
        long offset = (long) (page - 1) * size;
        List<BillingDtos.OperationalAuditEventResponse> items = mapper.findOperationalAudit(
                        normalizedActor, normalizedTarget, targetId, normalizedEvent, offset, size)
                .stream().map(BillingAdminService::auditResponse).toList();
        return PageResponse.of(items, page, size, mapper.countOperationalAudit(normalizedActor,
                normalizedTarget, targetId, normalizedEvent));
    }

    private static BillingDtos.OperationalAuditEventResponse auditResponse(
            BillingRecords.OperationalAuditEvent event) {
        return new BillingDtos.OperationalAuditEventResponse(Long.toString(event.id()),
                event.actorType(), event.actorUserId() == null ? null
                        : Long.toString(event.actorUserId()), event.actorRef(), event.targetType(),
                event.targetId() == null ? null : Long.toString(event.targetId()), event.eventType(),
                event.outcome(), event.traceId(), event.details(), event.createdAt());
    }

    private static String optionalEnum(String value, Set<String> allowed) {
        String normalized = value == null || value.isBlank() ? null : value;
        if (normalized != null && !allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private static String optionalPattern(String value, String pattern) {
        String normalized = value == null || value.isBlank() ? null : value;
        if (normalized != null && !normalized.matches(pattern)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }
}
