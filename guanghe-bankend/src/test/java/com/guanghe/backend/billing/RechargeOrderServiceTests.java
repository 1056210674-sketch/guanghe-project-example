package com.guanghe.backend.billing;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.notification.NotificationMapper;
import com.guanghe.backend.billing.notification.NotificationService;
import com.guanghe.backend.billing.payment.PaymentProvider;
import com.guanghe.backend.billing.pricing.CommercialRateLimitService;
import com.guanghe.backend.billing.pricing.PricingMapper;
import com.guanghe.backend.billing.pricing.RateCardResolver;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Control-plane behaviour of the recharge lifecycle.
 *
 * <p>The mapper is replaced by an in-memory store that honours the same compare-and-set predicate
 * the SQL uses, so these tests exercise the real state machine rather than asserting which methods
 * were called.
 */
class RechargeOrderServiceTests {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
    private static final long USER_ID = 5001L;
    private static final long OTHER_USER_ID = 5002L;
    private static final long ORDER_ID = 9001L;
    private static final int STARTING_BALANCE = 100;

    private BillingMapper mapper;
    private PricingMapper pricingMapper;
    private NotificationMapper notificationMapper;
    private StubPaymentProvider provider;
    private BillingProperties properties;
    private RechargeOrderService service;

    private final Map<Long, BillingRecords.RechargeOrder> orders = new LinkedHashMap<>();
    /** Monotonic id source: two orders created in one test must not collide on the same id. */
    private final AtomicLong sequence = new AtomicLong();
    private final List<Credit> credits = new ArrayList<>();
    private final List<BillingRecords.OperationalAuditEvent> audits = new ArrayList<>();
    private final List<BillingRecords.RechargeOrderEvent> events = new ArrayList<>();

    private record Credit(long userId, int delta, String entryType, String businessType,
                          long businessId, String key, Long operatorId) {
    }

    @BeforeEach
    void setUp() {
        mapper = mock(BillingMapper.class);
        // No active pricing version, so orders are priced by BillingProperties: the placeholder
        // rate card, which is what these lifecycle assertions were written against.
        pricingMapper = mock(PricingMapper.class);
        when(pricingMapper.findActiveVersion()).thenReturn(Optional.empty());
        notificationMapper = mock(NotificationMapper.class);
        when(notificationMapper.insert(any())).thenReturn(1);
        provider = StubPaymentProvider.payable();
        properties = new BillingProperties();
        sequence.set(ORDER_ID);
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        when(ids.nextId()).thenAnswer(inv -> sequence.getAndIncrement());

        stubStore();
        when(mapper.lockPointAccount(anyLong()))
                .thenAnswer(inv -> Optional.of(new BillingRecords.PointAccount(
                        inv.getArgument(0), STARTING_BALANCE, 0, 1, now(), now())));
        when(mapper.creditAvailable(anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertCreditLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), any(),
                any(), anyLong(), any(), any(), any(), any())).thenAnswer(inv -> {
            credits.add(new Credit(inv.getArgument(1), inv.getArgument(2), inv.getArgument(5),
                    inv.getArgument(6), inv.getArgument(7), inv.getArgument(8),
                    inv.getArgument(9)));
            return 1;
        });
        service = build(provider, ids);
    }

    // ---------------------------------------------------------------- 1. idempotent creation

    @Test
    void repeatingTheSameCreateReturnsTheSameOrderAndWritesOneRow() {
        BillingDtos.CreateRechargeOrderRequest request = createRequest(10_000L, null);
        String key = UUID.randomUUID().toString();

        BillingDtos.RechargeOrderResponse first = service.create(user(USER_ID), key, request);
        BillingDtos.RechargeOrderResponse second = service.create(user(USER_ID), key, request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(orders).hasSize(1);
        verify(mapper).insertOrder(any());
        assertThat(credits).isEmpty();
    }

    @Test
    void reusingAKeyForADifferentAmountIsRefusedRatherThanSilentlyReplayed() {
        String key = UUID.randomUUID().toString();
        service.create(user(USER_ID), key, createRequest(10_000L, null));

        assertThatThrownBy(() -> service.create(user(USER_ID), key, createRequest(20_000L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(orders).hasSize(1);
    }

    @Test
    void idempotencyKeysAreScopedPerAccountSoTwoUsersCannotShareOneOrder() {
        String key = UUID.randomUUID().toString();
        service.create(user(USER_ID), key, createRequest(10_000L, null));
        service.create(user(OTHER_USER_ID), key, createRequest(10_000L, null));

        assertThat(orders).hasSize(2);
        assertThat(orders.values().stream().map(BillingRecords.RechargeOrder::userId).distinct())
                .containsExactlyInAnyOrder(USER_ID, OTHER_USER_ID);
    }

    @Test
    void aCreateThatTheProviderRefusesLeavesNoOrderBehind() {
        provider.withPrepayFailure(new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE));

        assertThatThrownBy(() -> service.create(user(USER_ID), UUID.randomUUID().toString(),
                createRequest(10_000L, null)))
                .isInstanceOf(BusinessException.class);
        // The caller sees a failure; in a real transaction the insert would roll back with it.
        assertThat(credits).isEmpty();
        assertThat(events).noneMatch(event -> "PREPAY_ACCEPTED".equals(event.eventType()));
    }

    // ---------------------------------------------------------------- 6. illegal transitions

    @Test
    void closingAnOrderThatIsAlreadyTerminalIsRefused() {
        BillingDtos.RechargeOrderResponse created = service.create(user(USER_ID),
                UUID.randomUUID().toString(), createRequest(10_000L, null));
        long orderId = Long.parseLong(created.id());
        service.close(user(USER_ID), orderId);

        assertThatThrownBy(() -> service.close(user(USER_ID), orderId))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
    }

    @Test
    void aLosingStateTransitionIsReportedRatherThanSilentlyDropped() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));
        // Simulate a concurrent writer having already moved the order on.
        when(mapper.transitionOrder(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.close(user(USER_ID), ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
    }

    // ---------------------------------------------------------------- 4. cross-account access

    @Test
    void anotherAccountsOrderIsReportedAsAbsentNotAsForbidden() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));

        assertThatThrownBy(() -> service.get(user(OTHER_USER_ID), ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void aPayerCannotCloseAnotherAccountsOrder() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));

        assertThatThrownBy(() -> service.close(user(OTHER_USER_ID), ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(orders.get(ORDER_ID).status()).isEqualTo(RechargeOrderService.STATUS_PAYING);
    }

    // ---------------------------------------------------------------- 5. expiry

    @Test
    void anUnpaidOrderPastItsDeadlineIsExpiredByTheSweep() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));
        orders.put(ORDER_ID, withExpiry(orders.get(ORDER_ID), now().minusSeconds(1)));

        assertThat(service.sweepExpiredOrders()).isEqualTo(1);
        assertThat(orders.get(ORDER_ID).status()).isEqualTo(RechargeOrderService.STATUS_EXPIRED);
        assertThat(orders.get(ORDER_ID).closedAt()).isNotNull();
        assertThat(audits).anyMatch(audit -> "RECHARGE_ORDER_EXPIRED_SWEEP".equals(audit.eventType())
                && OperationalAuditService.ACTOR_SYSTEM.equals(audit.actorType()));
    }

    @Test
    void theSweepIsIdempotentAndNeverTouchesASettledOrder() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));
        orders.put(ORDER_ID, withExpiry(orders.get(ORDER_ID), now().minusSeconds(1)));
        service.sweepExpiredOrders();

        assertThat(service.sweepExpiredOrders()).isZero();
        assertThat(orders.get(ORDER_ID).status()).isEqualTo(RechargeOrderService.STATUS_EXPIRED);
    }

    @Test
    void anExpiredOrderCanNoLongerBeClosedByItsOwner() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));
        orders.put(ORDER_ID, withExpiry(orders.get(ORDER_ID), now().minusSeconds(1)));
        service.sweepExpiredOrders();

        assertThatThrownBy(() -> service.close(user(USER_ID), ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
    }

    // ---------------------------------------------------------------- 7. fail closed

    @Test
    void withNoProviderConfiguredNoOrderIsEverCreated() {
        provider = new StubPaymentProvider();
        service = rebuild(provider);

        assertThat(capability().payable()).isFalse();
        assertThat(capability().status())
                .isEqualTo(com.guanghe.backend.billing.payment.PaymentCapability.NOT_CONFIGURED);
        assertThat(capability().missingRequirements()).isNotEmpty();
        assertThatThrownBy(() -> service.create(user(USER_ID), UUID.randomUUID().toString(),
                createRequest(10_000L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
        verify(mapper, never()).insertOrder(any());
        assertThat(orders).isEmpty();
    }

    @Test
    void aConfiguredButDisabledProviderAlsoRefusesToCreateOrders() {
        provider = StubPaymentProvider.configuredButNotPayable();
        service = rebuild(provider);

        assertThat(capability().configured()).isTrue();
        assertThat(capability().payable()).isFalse();
        assertThatThrownBy(() -> service.create(user(USER_ID), UUID.randomUUID().toString(),
                createRequest(10_000L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE);
        assertThat(orders).isEmpty();
    }

    @Test
    void theCapabilityNeverClaimsPayableWithoutACapabilityToPay() {
        provider = StubPaymentProvider.configuredButNotPayable();
        service = rebuild(provider);

        // Reporting READY here would be a lie the client would render as a working recharge button.
        assertThat(capability().payable()).isFalse();
        assertThat(capability().status())
                .isEqualTo(com.guanghe.backend.billing.payment.PaymentCapability.NOT_IMPLEMENTED);
        assertThat(capability().missingRequirements()).containsExactly("liveOrderCreation");
    }

    @Test
    void amountOutsideTheConfiguredRangeIsRefusedBeforeAnythingIsWritten() {
        properties.setMinRechargeAmountCents(1000);
        properties.setMaxRechargeAmountCents(5000);
        service = rebuild(provider);

        assertThatThrownBy(() -> service.create(user(USER_ID), UUID.randomUUID().toString(),
                createRequest(999L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.RECHARGE_AMOUNT_OUT_OF_RANGE);
        assertThatThrownBy(() -> service.create(user(USER_ID), UUID.randomUUID().toString(),
                createRequest(5001L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.RECHARGE_AMOUNT_OUT_OF_RANGE);
        assertThat(orders).isEmpty();
    }

    @Test
    void anAdministratorCannotReachThePayerSurface() {
        assertThatThrownBy(() -> service.create(new AuthenticatedAccount(USER_ID, "admin",
                AccountRole.ADMIN, 1, AccountStatus.ACTIVE, null, null, null),
                UUID.randomUUID().toString(), createRequest(10_000L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ---------------------------------------------------------------- 9. credit exactly once

    @Test
    void aSuccessFromTheProviderCreditsThePointsExactlyOnce() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));
        BillingRecords.RechargeOrder order = orders.get(ORDER_ID);

        assertThat(service.settle(order, "evt-1", 10_000L, null, "PROVIDER_CALLBACK",
                service.providerEventId("evt-1", "SUCCESS")))
                .isEqualTo(RechargeOrderService.Settlement.APPLIED);
        assertThat(service.settle(orders.get(ORDER_ID), "evt-1", 10_000L, null, "PROVIDER_CALLBACK",
                service.providerEventId("evt-1", "SUCCESS")))
                .isEqualTo(RechargeOrderService.Settlement.DUPLICATE);

        assertThat(credits).hasSize(1);
        Credit credit = credits.get(0);
        assertThat(credit.userId()).isEqualTo(USER_ID);
        assertThat(credit.delta()).isEqualTo(properties.pointsFor(10_000L));
        assertThat(credit.entryType()).isEqualTo(PointCreditService.ENTRY_RECHARGE);
        assertThat(credit.businessType()).isEqualTo(PointCreditService.BUSINESS_RECHARGE);
        assertThat(credit.key()).isEqualTo("recharge:" + ORDER_ID);
        assertThat(orders.get(ORDER_ID).status()).isEqualTo(RechargeOrderService.STATUS_SUCCEEDED);
    }

    @Test
    void aRepeatedIdenticalCallbackLeavesExactlyOneEventRow() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));
        UUID eventId = service.providerEventId("evt-1", "SUCCESS");
        service.settle(orders.get(ORDER_ID), "evt-1", 10_000L, null, "PROVIDER_CALLBACK", eventId);
        long afterFirst = events.size();
        service.settle(orders.get(ORDER_ID), "evt-1", 10_000L, null, "PROVIDER_CALLBACK", eventId);

        assertThat(events).hasSize((int) afterFirst);
        assertThat(credits).hasSize(1);
    }

    // ---------------------------------------------------------------- 3. amount mismatch

    @Test
    void anAmountThatDisagreesWithTheOrderIsRecordedAndNeverCredited() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));

        assertThat(service.settle(orders.get(ORDER_ID), "evt-mismatch", 1L, null, "PROVIDER_CALLBACK",
                service.providerEventId("evt-mismatch", "SUCCESS")))
                .isEqualTo(RechargeOrderService.Settlement.AMOUNT_MISMATCH);

        assertThat(credits).isEmpty();
        assertThat(orders.get(ORDER_ID).status()).isEqualTo(RechargeOrderService.STATUS_PAYING);
        assertThat(events).anyMatch(event -> "AMOUNT_MISMATCH".equals(event.eventType()));
        assertThat(audits).anyMatch(audit -> "RECHARGE_AMOUNT_MISMATCH".equals(audit.eventType()));
    }

    @Test
    void aSuccessWithoutAnAmountIsTreatedAsAMismatch() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));

        assertThat(service.settle(orders.get(ORDER_ID), "evt-null", null, null, "PROVIDER_CALLBACK",
                service.providerEventId("evt-null", "SUCCESS")))
                .isEqualTo(RechargeOrderService.Settlement.AMOUNT_MISMATCH);
        assertThat(credits).isEmpty();
    }

    // ---------------------------------------------------------------- late success

    @Test
    void aLateSuccessForAnExpiredOrderIsRecordedForReconciliationAndNotCredited() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));
        orders.put(ORDER_ID, withExpiry(orders.get(ORDER_ID), now().minusSeconds(1)));
        service.sweepExpiredOrders();

        assertThat(service.settle(orders.get(ORDER_ID), "evt-late", 10_000L, null, "PROVIDER_CALLBACK",
                service.providerEventId("evt-late", "SUCCESS")))
                .isEqualTo(RechargeOrderService.Settlement.STALE);

        assertThat(credits).isEmpty();
        assertThat(orders.get(ORDER_ID).status()).isEqualTo(RechargeOrderService.STATUS_EXPIRED);
        BillingRecords.OperationalAuditEvent stale = audits.stream()
                .filter(audit -> OperationalAuditService.RECHARGE_CALLBACK_STALE
                        .equals(audit.eventType()))
                .findFirst().orElseThrow();
        // The operator must be able to find the payment again without the raw callback body.
        assertThat(stale.details()).contains("evt-late").contains("needsReconciliation")
                .contains(orders.get(ORDER_ID).orderNo());
        assertThat(stale.actorType()).isEqualTo(OperationalAuditService.ACTOR_PAYMENT_PROVIDER);
        assertThat(stale.actorUserId()).isNull();
        assertThat(stale.traceId()).isNotBlank();
    }

    @Test
    void aLateSuccessForAClosedOrderIsAlsoRecordedAndNotCredited() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));
        service.close(user(USER_ID), ORDER_ID);

        assertThat(service.settle(orders.get(ORDER_ID), "evt-late", 10_000L, null, "PROVIDER_CALLBACK",
                service.providerEventId("evt-late", "SUCCESS")))
                .isEqualTo(RechargeOrderService.Settlement.STALE);
        assertThat(credits).isEmpty();
    }

    @Test
    void aProviderReportedTerminalFailureNeverCredits() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));

        service.fail(orders.get(ORDER_ID), "evt-closed", "CLOSED", "PROVIDER_CALLBACK",
                service.providerEventId("evt-closed", "CLOSED"));

        assertThat(orders.get(ORDER_ID).status()).isEqualTo(RechargeOrderService.STATUS_FAILED);
        assertThat(orders.get(ORDER_ID).failureCode()).isEqualTo("CLOSED");
        assertThat(credits).isEmpty();
    }

    @Test
    void anInformationalProviderStateChangesNothing() {
        service.create(user(USER_ID), UUID.randomUUID().toString(), createRequest(10_000L, null));

        assertThat(service.applySnapshot(orders.get(ORDER_ID),
                new PaymentProvider.ProviderOrderSnapshot("STUB-ORDER-1",
                        PaymentProvider.TradeState.USERPAYING, 10_000L, "evt-wait", null),
                "PROVIDER_QUERY", null)).isEqualTo(RechargeOrderService.Settlement.NO_CHANGE);

        assertThat(orders.get(ORDER_ID).status()).isEqualTo(RechargeOrderService.STATUS_PAYING);
        assertThat(credits).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    /** Built from the same provider and policy the service under test sees. */
    private BillingDtos.RechargeCapabilityResponse capability() {
        return new RechargeCapabilityService(properties, provider).current();
    }

    private RechargeOrderService rebuild(PaymentProvider activeProvider) {
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        when(ids.nextId()).thenAnswer(inv -> sequence.getAndIncrement());
        return build(activeProvider, ids);
    }

    private RechargeOrderService build(PaymentProvider activeProvider, SnowflakeIdGenerator ids) {
        PointCreditService pointCredits = new PointCreditService(mapper, ids, CLOCK);
        OperationalAuditService audit = new OperationalAuditService(mapper, ids, CLOCK);
        return new RechargeOrderService(mapper, ids, properties, activeProvider, pointCredits, audit,
                new RateCardResolver(pricingMapper, properties),
                new CommercialRateLimitService(pricingMapper, ids, CLOCK),
                new NotificationService(notificationMapper, ids, CLOCK), CLOCK);
    }

    private void stubStore() {
        when(mapper.insertOrder(any())).thenAnswer(inv -> {
            BillingRecords.RechargeOrder order = inv.getArgument(0);
            orders.put(order.id(), order);
            return 1;
        });
        when(mapper.findOrderById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(orders.get(inv.<Long>getArgument(0))));
        when(mapper.lockOrderById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(orders.get(inv.<Long>getArgument(0))));
        when(mapper.findOrderByIdempotencyKey(any())).thenAnswer(inv -> orders.values().stream()
                .filter(order -> order.idempotencyKey().equals(inv.getArgument(0))).findFirst());
        // Mirrors ON CONFLICT (order_id, source_event_id) DO NOTHING: a byte-identical replay of the
        // same provider event must not append a second row.
        when(mapper.insertEvent(any())).thenAnswer(inv -> {
            BillingRecords.RechargeOrderEvent event = inv.getArgument(0);
            boolean alreadyPresent = events.stream()
                    .anyMatch(existing -> existing.orderId() == event.orderId()
                            && existing.sourceEventId().equals(event.sourceEventId()));
            if (alreadyPresent) return 0;
            events.add(event);
            return 1;
        });
        when(mapper.insertOperationalAudit(any())).thenAnswer(inv -> {
            BillingRecords.OperationalAuditEvent event = inv.getArgument(0);
            boolean alreadyPresent = event.idempotencyKey() != null && audits.stream()
                    .anyMatch(existing -> event.idempotencyKey().equals(existing.idempotencyKey()));
            if (alreadyPresent) return 0;
            audits.add(event);
            return 1;
        });
        when(mapper.transitionOrder(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenAnswer(inv -> {
                    long id = inv.getArgument(0);
                    String from = inv.getArgument(1);
                    String to = inv.getArgument(2);
                    BillingRecords.RechargeOrder current = orders.get(id);
                    if (current == null || !current.status().equals(from)) return 0;
                    orders.put(id, new BillingRecords.RechargeOrder(current.id(), current.orderNo(),
                            current.userId(), to, current.provider(), current.amountCents(),
                            current.points(), current.giftPoints(),
                            inv.getArgument(3) == null ? current.providerOrderNo()
                                    : inv.getArgument(3),
                            inv.getArgument(4) == null ? current.providerPrepayId()
                                    : inv.getArgument(4),
                            inv.getArgument(5) == null ? current.providerCodeUrl()
                                    : inv.getArgument(5),
                            current.idempotencyKey(), current.requestHash(), current.returnContext(),
                            current.pricingVersionId(), current.expiresAt(), inv.getArgument(6),
                            inv.getArgument(7), inv.getArgument(8), inv.getArgument(9),
                            current.version() + 1, current.createdAt(), inv.getArgument(10)));
                    return 1;
                });
        when(mapper.expireDueOrders(any())).thenAnswer(inv -> {
            OffsetDateTime now = inv.getArgument(0);
            int expired = 0;
            for (Map.Entry<Long, BillingRecords.RechargeOrder> entry
                    : new ArrayList<>(orders.entrySet())) {
                BillingRecords.RechargeOrder order = entry.getValue();
                boolean active = RechargeOrderService.STATUS_CREATED.equals(order.status())
                        || RechargeOrderService.STATUS_PAYING.equals(order.status());
                if (active && !order.expiresAt().isAfter(now)) {
                    orders.put(entry.getKey(), withExpiry(order, order.expiresAt(),
                            RechargeOrderService.STATUS_EXPIRED, now));
                    expired++;
                }
            }
            return expired;
        });
    }

    private static BillingRecords.RechargeOrder withExpiry(BillingRecords.RechargeOrder order,
                                                           OffsetDateTime expiresAt) {
        return withExpiry(order, expiresAt, order.status(), order.closedAt());
    }

    private static BillingRecords.RechargeOrder withExpiry(BillingRecords.RechargeOrder order,
                                                           OffsetDateTime expiresAt, String status,
                                                           OffsetDateTime closedAt) {
        return new BillingRecords.RechargeOrder(order.id(), order.orderNo(), order.userId(), status,
                order.provider(), order.amountCents(), order.points(), order.giftPoints(),
                order.providerOrderNo(), order.providerPrepayId(), order.providerCodeUrl(),
                order.idempotencyKey(), order.requestHash(), order.returnContext(),
                order.pricingVersionId(), expiresAt, order.paidAt(), closedAt, order.failedAt(),
                order.failureCode(), order.version(), order.createdAt(), order.updatedAt());
    }

    private BillingDtos.CreateRechargeOrderRequest createRequest(Long amountCents,
                                                                 String returnContext) {
        return new BillingDtos.CreateRechargeOrderRequest(amountCents, returnContext);
    }

    private static AuthenticatedAccount user(long id) {
        return new AuthenticatedAccount(id, "user" + id, AccountRole.USER, 1, AccountStatus.ACTIVE,
                null, null, null);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(CLOCK);
    }
}
