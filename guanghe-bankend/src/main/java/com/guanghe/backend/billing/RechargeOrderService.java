package com.guanghe.backend.billing;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.notification.NotificationService;
import com.guanghe.backend.billing.payment.PaymentCapability;
import com.guanghe.backend.billing.payment.PaymentProvider;
import com.guanghe.backend.billing.pricing.CommercialRateLimitService;
import com.guanghe.backend.billing.pricing.RateCardResolver;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recharge order lifecycle.
 *
 * <p>The governing rule is that no order may exist in a state the provider cannot actually deliver.
 * Order creation is therefore refused outright — before a row is written — unless the configured
 * provider reports itself payable. A dead order a user can never pay would be worse than an honest
 * "recharge is not available yet".
 *
 * <p>Every state change is a compare-and-set on the previous status, so an illegal transition
 * updates zero rows instead of relying on callers to check first. Every change also appends an event
 * row, which is what makes a late or out-of-order provider message reconstructible.
 *
 * <p>Reconciliation methods never throw for a business outcome. A rejected callback must still leave
 * its audit trail committed, so they record and <em>return</em> what happened; callers translate
 * that into an HTTP response after the transaction has committed.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RechargeOrderService {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAYING = "PAYING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_FAILED = "FAILED";

    private static final Set<String> ACTIVE_STATUSES = Set.of(STATUS_CREATED, STATUS_PAYING);
    private static final Set<String> BROWSER_FILTERABLE_STATUSES =
            Set.of(STATUS_CREATED, STATUS_PAYING, STATUS_SUCCEEDED, STATUS_CLOSED, STATUS_EXPIRED,
                    STATUS_FAILED);

    private static final String SOURCE_BROWSER = "BROWSER";
    private static final String SOURCE_QUERY = "PROVIDER_QUERY";

    private static final String EVENT_ORDER_CREATED = "ORDER_CREATED";
    private static final String EVENT_PREPAY_ACCEPTED = "PREPAY_ACCEPTED";
    private static final String EVENT_ORDER_CLOSED = "ORDER_CLOSED";
    private static final String EVENT_PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    private static final String EVENT_PAYMENT_DUPLICATE = "PAYMENT_DUPLICATE";
    private static final String EVENT_PROVIDER_TERMINAL = "PROVIDER_TERMINAL";
    private static final String EVENT_QUERY_NO_CHANGE = "QUERY_NO_CHANGE";
    private static final String EVENT_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    private static final String TARGET_ORDER = "RECHARGE_ORDER";

    private final BillingMapper mapper;
    private final SnowflakeIdGenerator ids;
    private final BillingProperties properties;
    private final PaymentProvider provider;
    private final PointCreditService points;
    private final OperationalAuditService audit;
    private final RateCardResolver rateCards;
    private final CommercialRateLimitService rateLimits;
    private final NotificationService notifications;
    private final Clock clock;

    public RechargeOrderService(BillingMapper mapper, SnowflakeIdGenerator ids,
                                BillingProperties properties, PaymentProvider provider,
                                PointCreditService points, OperationalAuditService audit,
                                RateCardResolver rateCards, CommercialRateLimitService rateLimits,
                                NotificationService notifications,
                                @Qualifier("billingClock") Clock clock) {
        this.mapper = mapper;
        this.ids = ids;
        this.properties = properties;
        this.provider = provider;
        this.points = points;
        this.audit = audit;
        this.rateCards = rateCards;
        this.rateLimits = rateLimits;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** What a provider reconciliation did, or deliberately did not do. */
    public enum Settlement {
        /** The order was settled and the points were credited exactly once. */
        APPLIED,
        /** Already settled; nothing was credited again. */
        DUPLICATE,
        /** A success arrived for an order that had already closed, expired or failed. */
        STALE,
        /** The provider's amount disagrees with the order. Recorded, never credited. */
        AMOUNT_MISMATCH,
        /** The provider named an order we do not have. Recorded, never credited. */
        UNMATCHED,
        /** Informational: the provider reported no change worth acting on. */
        NO_CHANGE
    }

    /** The order as it stands after reconciliation, together with what reconciliation did. */
    public record QueryResult(BillingDtos.RechargeOrderResponse order, Settlement settlement) {
    }

    // ---------------------------------------------------------------- create

    @Transactional
    public BillingDtos.RechargeOrderResponse create(AuthenticatedAccount account,
                                                    String idempotencyKey,
                                                    BillingDtos.CreateRechargeOrderRequest request) {
        long userId = CatalogAccess.requireUser(account);
        // Fail closed before anything is written. A user must never be handed an order the provider
        // cannot accept, because that order could never become payable.
        requirePayableProvider();
        long amountCents = request.amountCents();
        // The rate card decides both the bounds and the points, so what the payer was shown and what
        // the order records come from the same object.
        RateCardResolver.RateCard card = rateCards.current();
        if (!card.withinBounds(amountCents)) {
            throw new BusinessException(ErrorCode.RECHARGE_AMOUNT_OUT_OF_RANGE);
        }
        int giftPoints = card.giftPointsFor(amountCents);
        int grantedPoints = card.totalPointsFor(amountCents);
        if (grantedPoints <= 0 || giftPoints >= grantedPoints) {
            throw new BusinessException(ErrorCode.RECHARGE_AMOUNT_OUT_OF_RANGE);
        }

        String returnContext = blankToNull(request.returnContext());
        // Namespaced by user, so the table's global unique index can never let one account's key
        // collide with, or replay, another account's order.
        String key = userId + ":" + uuid(idempotencyKey);
        // Only what the caller supplied goes into the hash. The rate card deliberately does not:
        // publishing a new rate between two calls with the same key is not the caller reusing a key
        // for a different request, and must not be reported as though it were.
        String requestHash = sha256(amountCents + "\n" + (returnContext == null ? "" : returnContext)
                + "\n" + provider.code());

        // Throttled before the idempotency lookup: a caller hammering a key they already used is
        // exactly the traffic this is for, and it must not be free.
        rateLimits.enforce(CommercialRateLimitService.ACTION_RECHARGE_ORDER_CREATE,
                since -> mapper.countOrdersCreatedSince(userId, since));

        BillingRecords.RechargeOrder existing = mapper.findOrderByIdempotencyKey(key).orElse(null);
        if (existing != null) return replay(existing, requestHash);

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expiresAt = now.plus(properties.getRechargeOrderTtl());
        long orderId = ids.nextId();
        String orderNo = "R" + orderId;
        BillingRecords.RechargeOrder order = new BillingRecords.RechargeOrder(orderId, orderNo,
                userId, STATUS_CREATED, provider.code(), amountCents, grantedPoints, giftPoints,
                null, null, null, key, requestHash, returnContext,
                card.versionId(), expiresAt, null,
                null, null, null, 1, now, now);
        try {
            if (mapper.insertOrder(order) != 1) {
                throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
            }
        } catch (DuplicateKeyException exception) {
            BillingRecords.RechargeOrder raced = mapper.findOrderByIdempotencyKey(key).orElse(null);
            if (raced == null) throw exception;
            return replay(raced, requestHash);
        }
        recordEvent(order, null, STATUS_CREATED, EVENT_ORDER_CREATED, SOURCE_BROWSER, null,
                amountCents, null, summary(amountCents, grantedPoints), browserEventId(key));

        // A provider that accepts the order moves it to PAYING. If this throws, the whole
        // transaction — row, event and audit — rolls back and no order survives.
        PaymentProvider.PrepayTicket ticket = provider.createPrepay(
                new PaymentProvider.PrepayCommand(orderId, orderNo, amountCents,
                        properties.getRechargeDescription(), expiresAt));
        if (ticket == null || ticket.providerOrderNo() == null || ticket.providerOrderNo().isBlank()) {
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE);
        }
        OffsetDateTime updated = OffsetDateTime.now(clock);
        if (mapper.transitionOrder(orderId, STATUS_CREATED, STATUS_PAYING, ticket.providerOrderNo(),
                ticket.prepayId(), ticket.codeUrl(), null, null, null, null, updated) != 1) {
            throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
        }
        BillingRecords.RechargeOrder paying = require(orderId);
        recordEvent(paying, STATUS_CREATED, STATUS_PAYING, EVENT_PREPAY_ACCEPTED, SOURCE_BROWSER,
                null, amountCents, null, summary(amountCents, grantedPoints), browserEventId(key));
        audit.actorUser(OperationalAuditService.ACTOR_USER, userId, TARGET_ORDER, orderId,
                OperationalAuditService.RECHARGE_ORDER_CREATED,
                OperationalAuditService.OUTCOME_SUCCESS,
                "{\"amountCents\":" + amountCents + ",\"points\":" + grantedPoints
                        + ",\"provider\":\"" + provider.code() + "\"}");
        return response(paying);
    }

    // ---------------------------------------------------------------- read

    @Transactional
    public PageResponse<BillingDtos.RechargeOrderResponse> list(AuthenticatedAccount account,
                                                               String status, int page, int size) {
        long userId = CatalogAccess.requireUser(account);
        String normalized = blankToNull(status);
        if (normalized != null && !BROWSER_FILTERABLE_STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        sweepExpiredOrders();
        long offset = (long) (page - 1) * size;
        List<BillingDtos.RechargeOrderResponse> items = mapper
                .findOrdersByUser(userId, normalized, offset, size).stream()
                .map(this::response).toList();
        return PageResponse.of(items, page, size, mapper.countOrdersByUser(userId, normalized));
    }

    @Transactional
    public BillingDtos.RechargeOrderResponse get(AuthenticatedAccount account, long orderId) {
        long userId = CatalogAccess.requireUser(account);
        sweepExpiredOrders();
        return response(ownedOrder(userId, orderId));
    }

    /**
     * Actively asks the provider for the authoritative state and reconciles against it.
     *
     * <p>Only ever reports what the provider actually said. With no provider configured this refuses
     * rather than guessing, and it never marks an order paid on its own.
     */
    @Transactional
    public QueryResult query(AuthenticatedAccount account, long orderId) {
        ownedOrder(CatalogAccess.requireUser(account), orderId);
        return reconcile(orderId);
    }

    /**
     * Operator-initiated reconciliation of any account's order. Same code path as the payer's own
     * query; it differs only in not being scoped to the caller.
     */
    @Transactional
    public QueryResult reconcile(long orderId) {
        sweepExpiredOrders();
        BillingRecords.RechargeOrder order = require(orderId);
        if (!ACTIVE_STATUSES.contains(order.status())) {
            return new QueryResult(response(order), Settlement.NO_CHANGE);
        }
        if (order.providerOrderNo() == null) {
            throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
        }
        requirePayableProvider();
        PaymentProvider.ProviderOrderSnapshot snapshot = provider.queryOrder(order.providerOrderNo());
        Settlement settlement = applySnapshot(order, snapshot, SOURCE_QUERY, null);
        return new QueryResult(response(require(orderId)), settlement);
    }

    @Transactional
    public BillingDtos.RechargeOrderResponse close(AuthenticatedAccount account, long orderId) {
        long userId = CatalogAccess.requireUser(account);
        sweepExpiredOrders();
        BillingRecords.RechargeOrder order = ownedOrder(userId, orderId);
        if (!ACTIVE_STATUSES.contains(order.status())) {
            throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (mapper.transitionOrder(orderId, order.status(), STATUS_CLOSED, null, null, null, null,
                now, null, null, now) != 1) {
            throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
        }
        BillingRecords.RechargeOrder closed = require(orderId);
        recordEvent(closed, order.status(), STATUS_CLOSED, EVENT_ORDER_CLOSED, SOURCE_BROWSER, null,
                order.amountCents(), null, summary(order.amountCents(), order.points()),
                browserEventId("close:" + orderId));
        audit.actorUser(OperationalAuditService.ACTOR_USER, userId, TARGET_ORDER, orderId,
                OperationalAuditService.RECHARGE_ORDER_CLOSED,
                OperationalAuditService.OUTCOME_SUCCESS,
                "{\"fromStatus\":\"" + order.status() + "\"}");
        return response(closed);
    }

    // ---------------------------------------------------------------- expiry

    /**
     * Expires every payable order past its deadline.
     *
     * <p>Idempotent: the predicate matches only rows still in a payable state, so a repeated sweep
     * is a no-op. Expiry is purely time-driven — no external actor is involved — so the transition
     * is recorded once as a system audit summarising the sweep rather than as a per-order event.
     */
    @Transactional
    public int sweepExpiredOrders() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int expired = mapper.expireDueOrders(now);
        if (expired > 0) {
            audit.actorSystem(OperationalAuditService.ACTOR_SYSTEM, "billing-expiry-sweep",
                    TARGET_ORDER, null, "RECHARGE_ORDER_EXPIRED_SWEEP",
                    OperationalAuditService.OUTCOME_SUCCESS,
                    "{\"expired\":" + expired + "}", null);
        }
        return expired;
    }

    // ---------------------------------------------------------------- provider reconciliation

    /**
     * Applies an authoritative provider snapshot. Shared by the callback boundary and the active
     * query so both settle through exactly one code path, and so a late callback cannot behave
     * differently from a poll that saw the same state.
     *
     * @param payloadSha256 hash of the raw provider payload when the state came from a callback;
     *                      kept so a disputed event can be matched to bytes we deliberately did not
     *                      store. Never the payload itself.
     */
    @Transactional
    public Settlement applySnapshot(BillingRecords.RechargeOrder order,
                                    PaymentProvider.ProviderOrderSnapshot snapshot, String source,
                                    String payloadSha256) {
        if (snapshot == null) throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE);
        UUID eventId = providerEventId(snapshot.providerEventId(), snapshot.tradeState().name());
        if (snapshot.tradeState().isSettled()) {
            return settle(order, snapshot.providerEventId(), snapshot.amountCents(),
                    snapshot.paidAt(), source, eventId);
        }
        if (snapshot.tradeState().isTerminal() && ACTIVE_STATUSES.contains(order.status())) {
            return fail(order, snapshot.providerEventId(), snapshot.tradeState().name(), source,
                    eventId);
        }
        recordEvent(order, order.status(), order.status(), EVENT_QUERY_NO_CHANGE, source,
                snapshot.providerEventId(), snapshot.amountCents(), null,
                "{\"tradeState\":\"" + snapshot.tradeState().name() + "\""
                        + hashFragment(payloadSha256) + "}", eventId);
        return Settlement.NO_CHANGE;
    }

    /**
     * Credits a paid order exactly once.
     *
     * <p>Two independent guards make a double credit impossible: the compare-and-set on the previous
     * status, and the ledger's unique idempotency key derived from the order id.
     */
    @Transactional
    public Settlement settle(BillingRecords.RechargeOrder order, String providerEventId,
                             Long amountCents, OffsetDateTime paidAt, String source, UUID eventId) {
        BillingRecords.RechargeOrder locked = mapper.lockOrderById(order.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!ACTIVE_STATUSES.contains(locked.status())) {
            // A late or repeated success for an order that already reached a terminal state. It is
            // recorded for reconciliation and never credited a second time.
            boolean duplicate = STATUS_SUCCEEDED.equals(locked.status());
            Settlement outcome = duplicate ? Settlement.DUPLICATE : Settlement.STALE;
            recordEvent(locked, locked.status(), locked.status(),
                    duplicate ? EVENT_PAYMENT_DUPLICATE : EVENT_PAYMENT_SUCCEEDED, source,
                    providerEventId, amountCents, duplicate ? null : "RECHARGE_ORDER_STATE_CONFLICT",
                    "{\"terminalStatus\":\"" + locked.status() + "\",\"providerEventId\":\""
                            + safe(providerEventId) + "\",\"needsReconciliation\":"
                            + !duplicate + "}", eventId);
            audit.actorSystem(OperationalAuditService.ACTOR_PAYMENT_PROVIDER, provider.code(),
                    TARGET_ORDER, locked.id(),
                    duplicate ? "RECHARGE_CALLBACK_DUPLICATE"
                            : OperationalAuditService.RECHARGE_CALLBACK_STALE,
                    duplicate ? OperationalAuditService.OUTCOME_SUCCESS
                            : OperationalAuditService.OUTCOME_FAILURE,
                    "{\"terminalStatus\":\"" + locked.status() + "\",\"providerEventId\":\""
                            + safe(providerEventId) + "\",\"amountCents\":"
                            + (amountCents == null ? "null" : amountCents) + ",\"orderNo\":\""
                            + locked.orderNo() + "\",\"needsReconciliation\":" + !duplicate + "}",
                    providerEventId == null ? null : "payment-event:" + providerEventId);
            return outcome;
        }
        if (amountCents == null || amountCents != locked.amountCents()) {
            recordEvent(locked, locked.status(), locked.status(), EVENT_AMOUNT_MISMATCH, source,
                    providerEventId, amountCents, "PAYMENT_AMOUNT_MISMATCH",
                    "{\"expected\":" + locked.amountCents() + ",\"received\":"
                            + (amountCents == null ? "null" : amountCents) + "}", eventId);
            audit.actorSystem(OperationalAuditService.ACTOR_PAYMENT_PROVIDER, provider.code(),
                    TARGET_ORDER, locked.id(), "RECHARGE_AMOUNT_MISMATCH",
                    OperationalAuditService.OUTCOME_FAILURE,
                    "{\"expected\":" + locked.amountCents() + ",\"received\":"
                            + (amountCents == null ? "null" : amountCents) + ",\"orderNo\":\""
                            + locked.orderNo() + "\"}",
                    providerEventId == null ? null : "payment-mismatch:" + providerEventId);
            return Settlement.AMOUNT_MISMATCH;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime settledAt = paidAt == null ? now : paidAt;
        if (mapper.transitionOrder(locked.id(), locked.status(), STATUS_SUCCEEDED, null, null, null,
                settledAt, null, null, null, now) != 1) {
            return Settlement.DUPLICATE;
        }
        points.credit(locked.userId(), locked.points(), PointCreditService.ENTRY_RECHARGE,
                PointCreditService.BUSINESS_RECHARGE, locked.id(), "recharge:" + locked.id(), null,
                "充值订单 " + locked.orderNo());
        BillingRecords.RechargeOrder succeeded = require(locked.id());
        recordEvent(succeeded, locked.status(), STATUS_SUCCEEDED, EVENT_PAYMENT_SUCCEEDED, source,
                providerEventId, amountCents, null,
                "{\"points\":" + locked.points() + ",\"amountCents\":" + amountCents + "}", eventId);
        // Announce the credit in the same transaction that made it true, so a notification can
        // never outlive a rolled-back settlement. The dedup key is derived from the order, so a
        // replayed callback cannot produce a second notification.
        notifications.publish(locked.userId(), NotificationService.CATEGORY_RECHARGE,
                "充值到账", "充值 " + yuan(locked.amountCents()) + " 元，积分 +" + locked.points()
                        + giftSuffix(locked.giftPoints()) + "，已到账。",
                returnTargetType(locked.returnContext()), returnTargetId(locked.returnContext()),
                "RECHARGE_SUCCEEDED:" + locked.id());
        audit.actorSystem(OperationalAuditService.ACTOR_PAYMENT_PROVIDER, provider.code(),
                TARGET_ORDER, locked.id(), OperationalAuditService.RECHARGE_ORDER_SUCCEEDED,
                OperationalAuditService.OUTCOME_SUCCESS,
                "{\"amountCents\":" + amountCents + ",\"points\":" + locked.points()
                        + ",\"orderNo\":\"" + locked.orderNo() + "\"}",
                providerEventId == null ? null : "payment-event:" + providerEventId);
        return Settlement.APPLIED;
    }

    /**
     * The resource a payer should be returned to, taken from the order's own return context.
     *
     * <p>Stored as {@code KIND:id}. The notification carries the two parts separately so the client
     * routes, rather than following a string the payer supplied.
     */
    private static String returnTargetType(String returnContext) {
        if (returnContext == null) return null;
        int separator = returnContext.indexOf(':');
        return separator <= 0 ? null : returnContext.substring(0, separator);
    }

    private static Long returnTargetId(String returnContext) {
        if (returnContext == null) return null;
        int separator = returnContext.indexOf(':');
        if (separator <= 0) return null;
        try {
            return Long.valueOf(returnContext.substring(separator + 1));
        } catch (NumberFormatException exception) {
            // The column's CHECK constraint makes this unreachable; a null is still the honest
            // answer rather than a link to somewhere arbitrary.
            return null;
        }
    }

    private static String giftSuffix(int giftPoints) {
        return giftPoints > 0 ? "（含赠送 " + giftPoints + "）" : "";
    }

    private static String yuan(long amountCents) {
        if (amountCents % 100 == 0) return Long.toString(amountCents / 100);
        return String.format(Locale.ROOT, "%.2f", amountCents / 100.0);
    }

    /** Records a provider-reported terminal failure. Never credits anything. */
    @Transactional
    public Settlement fail(BillingRecords.RechargeOrder order, String providerEventId,
                           String providerState, String source, UUID eventId) {
        BillingRecords.RechargeOrder locked = mapper.lockOrderById(order.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!ACTIVE_STATUSES.contains(locked.status())) {
            recordEvent(locked, locked.status(), locked.status(), EVENT_PAYMENT_DUPLICATE, source,
                    providerEventId, null, null,
                    "{\"terminalStatus\":\"" + locked.status() + "\"}", eventId);
            return Settlement.DUPLICATE;
        }
        String failureCode = sanitizeCode(providerState);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (mapper.transitionOrder(locked.id(), locked.status(), STATUS_FAILED, null, null, null,
                null, null, now, failureCode, now) != 1) {
            return Settlement.DUPLICATE;
        }
        recordEvent(require(locked.id()), locked.status(), STATUS_FAILED, EVENT_PROVIDER_TERMINAL,
                source, providerEventId, null, failureCode,
                "{\"providerState\":\"" + failureCode + "\"}", eventId);
        audit.actorSystem(OperationalAuditService.ACTOR_PAYMENT_PROVIDER, provider.code(),
                TARGET_ORDER, locked.id(), "RECHARGE_ORDER_FAILED",
                OperationalAuditService.OUTCOME_FAILURE,
                "{\"providerState\":\"" + failureCode + "\",\"orderNo\":\"" + locked.orderNo()
                        + "\"}", providerEventId == null ? null : "payment-event:" + providerEventId);
        return Settlement.NO_CHANGE;
    }

    /** Records a callback naming an order this system does not have. Never credits anything. */
    @Transactional
    public Settlement unmatched(String providerReference, String providerEventId) {
        audit.actorSystem(OperationalAuditService.ACTOR_PAYMENT_PROVIDER, provider.code(),
                TARGET_ORDER, null, "RECHARGE_CALLBACK_UNMATCHED",
                OperationalAuditService.OUTCOME_FAILURE,
                "{\"providerReference\":\"" + safe(providerReference) + "\",\"providerEventId\":\""
                        + safe(providerEventId) + "\",\"needsReconciliation\":true}",
                providerEventId == null ? null : "payment-unmatched:" + providerEventId);
        return Settlement.UNMATCHED;
    }

    // ---------------------------------------------------------------- helpers

    /** Looks an order up by what the provider called it, falling back to our own order number. */
    @Transactional(readOnly = true)
    public BillingRecords.RechargeOrder findByProviderReference(String providerCode,
                                                                String providerReference) {
        if (providerReference == null || providerReference.isBlank()) return null;
        return mapper.findOrderByProviderOrderNo(providerCode, providerReference)
                .or(() -> mapper.findOrderByOrderNo(providerReference))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public BillingRecords.RechargeOrder require(long orderId) {
        return mapper.findOrderById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public String providerCode() {
        return provider.code();
    }

    private BillingRecords.RechargeOrder ownedOrder(long userId, long orderId) {
        BillingRecords.RechargeOrder order = require(orderId);
        // Another account's order is reported as absent rather than forbidden: a caller must not be
        // able to probe which order ids exist.
        if (order.userId() != userId) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return order;
    }

    private void requirePayableProvider() {
        PaymentCapability capability = provider.capability();
        if (capability.payable()) return;
        if (PaymentCapability.NOT_CONFIGURED.equals(capability.status())) {
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
        }
        throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE);
    }

    private BillingDtos.RechargeOrderResponse replay(BillingRecords.RechargeOrder existing,
                                                     String requestHash) {
        if (!MessageDigest.isEqual(existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return response(existing);
    }

    private void recordEvent(BillingRecords.RechargeOrder order, String fromStatus, String toStatus,
                             String eventType, String source, String providerEventId,
                             Long amountCents, String errorCode, String details, UUID eventId) {
        BillingRecords.RechargeOrderEvent event = new BillingRecords.RechargeOrderEvent(ids.nextId(),
                order.id(), order.userId(), eventId, eventType, fromStatus, toStatus, source,
                providerEventId, amountCents, order.requestHash(), errorCode, details,
                OffsetDateTime.now(clock));
        mapper.insertEvent(event);
    }

    /** Row to payload. Public so the administrator surface renders orders identically. */
    public BillingDtos.RechargeOrderResponse toResponse(BillingRecords.RechargeOrder order) {
        return response(order);
    }

    private BillingDtos.RechargeOrderResponse response(BillingRecords.RechargeOrder order) {
        return new BillingDtos.RechargeOrderResponse(Long.toString(order.id()), order.orderNo(),
                order.status(), order.provider(), order.amountCents(), order.points(),
                order.giftPoints(), order.providerOrderNo(), order.providerCodeUrl(),
                order.returnContext(),
                order.pricingVersionId() == null ? null : Long.toString(order.pricingVersionId()),
                order.expiresAt(), order.paidAt(), order.closedAt(), order.failedAt(),
                order.failureCode(), order.createdAt(), order.updatedAt());
    }

    /**
     * Stable identity for a provider message. Deterministic, so replaying the same provider event
     * maps to the same row and the event table's unique index absorbs the duplicate.
     */
    public UUID providerEventId(String externalEventId, String discriminator) {
        String value = externalEventId == null || externalEventId.isBlank()
                ? "missing" : externalEventId;
        return UUID.nameUUIDFromBytes(
                (provider.code() + ":" + value + ":" + discriminator).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID browserEventId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hashFragment(String payloadSha256) {
        return payloadSha256 == null || payloadSha256.isBlank()
                ? "" : ",\"payloadSha256\":\"" + payloadSha256.replace("\"", "") + "\"";
    }

    private static String summary(long amountCents, int points) {
        return "{\"amountCents\":" + amountCents + ",\"points\":" + points + "}";
    }

    private static String sanitizeCode(String value) {
        if (value == null || value.isBlank()) return "PROVIDER_TERMINAL";
        String cleaned = value.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
        if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64);
        return cleaned.matches("^[A-Z][A-Z0-9_]{0,63}$") ? cleaned : "PROVIDER_TERMINAL";
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\"", "").replace("\\", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
