package com.guanghe.backend.billing.pricing;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.BillingMapper;
import com.guanghe.backend.billing.BillingProperties;
import com.guanghe.backend.billing.OperationalAuditService;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The commercial rate card and the throttles that protect it.
 *
 * <p>Two things are asserted that the rest of the module depends on: that an unpublished deployment
 * is visibly running on the placeholder rather than on settled pricing, and that the throttle
 * resolves the rule and the window from the same row, so a count can never be taken over one window
 * and judged against another rule's limit.
 */
class PricingAndRateLimitTests {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
    private static final long OPERATOR_ID = 1L;
    private static final long USER_ID = 5001L;

    private PricingMapper mapper;
    private BillingProperties properties;
    private RateCardResolver resolver;
    private PricingAdminService admin;
    private CommercialRateLimitService rateLimits;

    private final Map<Long, PricingRecords.PricingVersion> versions = new LinkedHashMap<>();
    private final Map<Long, List<PricingRecords.PricingTier>> tiers = new LinkedHashMap<>();
    private final Map<String, PricingRecords.RateLimitRule> rules = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong(300L);

    @BeforeEach
    void setUp() {
        mapper = mock(PricingMapper.class);
        properties = new BillingProperties();
        stubStore();
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        when(ids.nextId()).thenAnswer(inv -> sequence.incrementAndGet());
        resolver = new RateCardResolver(mapper, properties);
        rateLimits = new CommercialRateLimitService(mapper, ids, CLOCK);
        admin = new PricingAdminService(mapper, resolver, rateLimits,
                new OperationalAuditService(mock(BillingMapper.class), ids, CLOCK), ids, CLOCK);
    }

    // ---------------------------------------------------------------- the placeholder rate

    @Test
    void withNothingPublishedTheRateCardIsMarkedAsNotProduction() {
        RateCardResolver.RateCard card = resolver.current();

        assertThat(card.production()).isFalse();
        assertThat(card.versionId()).isNull();
        assertThat(card.versionNo()).isNull();
        assertThat(card.tiers()).isEmpty();
        // The numbers still come from configuration, so the control plane is usable and testable —
        // but the flag is what stops a client presenting them as an agreed exchange rate.
        assertThat(card.pointsPerYuan()).isEqualTo(properties.getPointsPerYuan());
        assertThat(card.minCents()).isEqualTo(properties.getMinRechargeAmountCents());
    }

    // ---------------------------------------------------------------- a published rate

    @Test
    void aPublishedVersionIsWhatMakesTheRateCardProduction() {
        long id = publishDraft(100, List.of(tier(10_000L, 500, "满 100 送 5")), 100L, 500_000L);

        RateCardResolver.RateCard card = resolver.current();

        assertThat(card.production()).isTrue();
        assertThat(card.versionId()).isEqualTo(id);
        assertThat(card.versionNo()).isEqualTo(1);
        assertThat(card.pointsPerYuan()).isEqualTo(100);
        assertThat(card.tiers()).hasSize(1);
    }

    @Test
    void basePointsAreFlooredToWholeYuanAndGiftPointsOnlyApplyToAnExactTierAmount() {
        publishDraft(100, List.of(tier(10_000L, 500, "满 100 送 5")), 100L, 500_000L);
        RateCardResolver.RateCard card = resolver.current();

        assertThat(card.basePointsFor(10_099L)).isEqualTo(10_000);
        assertThat(card.giftPointsFor(10_000L)).isEqualTo(500);
        assertThat(card.giftPointsFor(10_099L)).isZero();
        assertThat(card.totalPointsFor(10_000L)).isEqualTo(10_500);
        assertThat(card.totalPointsFor(10_099L)).isEqualTo(10_000);
    }

    @Test
    void anAmountOutsideThePublishedBoundsIsOutsideTheCard() {
        publishDraft(100, List.of(tier(10_000L, 500, "满 100 送 5")), 10_000L, 100_000L);
        RateCardResolver.RateCard card = resolver.current();

        assertThat(card.withinBounds(9_999L)).isFalse();
        assertThat(card.withinBounds(10_000L)).isTrue();
        assertThat(card.withinBounds(100_000L)).isTrue();
        assertThat(card.withinBounds(100_001L)).isFalse();
    }

    // ---------------------------------------------------------------- version lifecycle

    @Test
    void publishingRetiresTheVersionItReplacesSoExactlyOneRateIsEverActive() {
        long first = publishDraft(100, List.of(tier(10_000L, 500, "满 100 送 5")), 100L, 500_000L);
        long second = createDraft(200, List.of(tier(10_000L, 900, "满 100 送 9")), 100L, 500_000L);

        admin.publish(admin(), second);

        assertThat(versions.get(first).status()).isEqualTo("RETIRED");
        assertThat(versions.get(first).retiredAt()).isNotNull();
        assertThat(versions.get(second).status()).isEqualTo("ACTIVE");
        assertThat(resolver.current().pointsPerYuan()).isEqualTo(200);
        assertThat(versions.values().stream().filter(row -> "ACTIVE".equals(row.status())).count())
                .isEqualTo(1);
    }

    @Test
    void retractingTheOnlyActiveVersionPutsThePlaceholderBackInForce() {
        long id = publishDraft(100, List.of(tier(10_000L, 500, "满 100 送 5")), 100L, 500_000L);

        admin.retire(admin(), id);

        assertThat(resolver.current().production()).isFalse();
        assertThat(resolver.current().pointsPerYuan()).isEqualTo(properties.getPointsPerYuan());
    }

    @Test
    void aPublishedRateCannotBeEditedBecauseOrdersAlreadyCiteIt() {
        long id = publishDraft(100, List.of(tier(10_000L, 500, "满 100 送 5")), 100L, 500_000L);

        assertThatThrownBy(() -> admin.updateDraft(admin(), id,
                new PricingDtos.UpdatePricingVersionRequest(999, 100L, 500_000L, null,
                        List.of(tier(10_000L, 0, "满 100 送 0")), 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PRICING_VERSION_IN_USE);
        assertThat(versions.get(id).pointsPerYuan()).isEqualTo(100);
    }

    @Test
    void aRateCardWithNoTiersIsNotPublishedBecauseThePayerPageCouldNotRenderIt() {
        long id = createDraft(100, List.of(), 100L, 500_000L);

        assertThatThrownBy(() -> admin.publish(admin(), id))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(versions.get(id).status()).isEqualTo("DRAFT");
    }

    @Test
    void publishingAVersionThatIsNotADraftIsRefused() {
        long id = publishDraft(100, List.of(tier(10_000L, 500, "满 100 送 5")), 100L, 500_000L);

        assertThatThrownBy(() -> admin.publish(admin(), id))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PRICING_VERSION_CONFLICT);
    }

    @Test
    void twoTiersForTheSameAmountWouldMakeTheGiftAmbiguousSoTheyAreRefused() {
        assertThatThrownBy(() -> createDraft(100,
                List.of(tier(10_000L, 500, "满 100 送 5"), tier(10_000L, 900, "满 100 送 9")),
                100L, 500_000L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- whole-yuan bounds

    /**
     * A card whose minimum is below one yuan is refused, because the rate it cites prices whole yuan:
     * {@code basePointsFor} floors, so its own minimum would be priced as zero points and the order
     * endpoint would answer a payer who paid it with {@code RECHARGE_AMOUNT_OUT_OF_RANGE}. Publishing
     * is the last point at which the operator can still see the mistake.
     */
    @Test
    void aMinimumBelowOneYuanIsRefusedBecauseTheRatePricesWholeYuan() {
        assertThatThrownBy(() -> createDraft(100, List.of(tier(100L, 5, "送 5")), 99L, 500_000L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(versions).isEmpty();
    }

    /** The same defect a yuan and a half above the floor: ¥1.50 would be priced as 100 points. */
    @Test
    void aMinimumThatIsNotAWholeNumberOfYuanIsRefusedForTheSameReason() {
        assertThatThrownBy(() -> createDraft(100, List.of(tier(200L, 5, "送 5")), 150L, 500_000L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void aMaximumThatIsNotAWholeNumberOfYuanIsRefusedSoTheRangeStaysInTheUnitItPricesIn() {
        assertThatThrownBy(() -> createDraft(100, List.of(tier(10_000L, 500, "满 100 送 5")), 100L,
                500_050L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void aMaximumBelowTheMinimumIsRefused() {
        assertThatThrownBy(() -> createDraft(100, List.of(tier(10_000L, 500, "满 100 送 5")), 10_000L,
                9_900L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /**
     * A gift applies only to the exact amount it names, so a tier the payer cannot pay is
     * configuration that would look active on the operator page and could never fire.
     */
    @Test
    void aTierOutsideThePublishedBoundsIsRefusedBecauseAnExactAmountTierCouldNeverBePaid() {
        assertThatThrownBy(() -> createDraft(100, List.of(tier(200_000L, 500, "越界")), 100L,
                100_000L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> createDraft(100, List.of(tier(100L, 500, "越界")), 10_000L,
                100_000L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /**
     * The schema refuses these with {@code ck_pricing_version_tier_amount} and
     * {@code ck_pricing_version_tier_gift}. Reaching them as a driver-level integrity error would
     * report an operator's typo as a 500, so they are refused before the insert instead.
     */
    @Test
    void aNonPositiveTierAmountOrNegativeGiftIsRefusedHereRatherThanByTheSchema() {
        assertThatThrownBy(() -> createDraft(100, List.of(tier(0L, 500, "零")), 100L, 500_000L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> createDraft(100, List.of(tier(10_000L, -1, "负")), 100L, 500_000L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> createDraft(100, List.of(tier(10_000L, 500, "满 100 送 5")), 0L,
                500_000L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** The knobs are untouched: a whole-yuan card whose tiers sit on the bounds still publishes. */
    @Test
    void aWholeYuanCardWhoseTiersSitOnTheBoundsIsStillAccepted() {
        long id = publishDraft(100, List.of(tier(100L, 5, "起充档"), tier(500_000L, 5_000, "上限档")),
                100L, 500_000L);

        RateCardResolver.RateCard card = resolver.current();

        assertThat(card.production()).isTrue();
        assertThat(card.versionId()).isEqualTo(id);
        assertThat(card.tiers()).hasSize(2);
    }

    @Test
    void aStaleDraftEditIsRefusedRatherThanClobberingAColleaguesChange() {
        long id = createDraft(100, List.of(tier(10_000L, 500, "满 100 送 5")), 100L, 500_000L);
        admin.updateDraft(admin(), id, new PricingDtos.UpdatePricingVersionRequest(150, 100L,
                500_000L, null, List.of(tier(10_000L, 700, "满 100 送 7")), 1));

        assertThatThrownBy(() -> admin.updateDraft(admin(), id,
                new PricingDtos.UpdatePricingVersionRequest(200, 100L, 500_000L, null,
                        List.of(tier(10_000L, 900, "满 100 送 9")), 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_VERSION_CONFLICT);
        assertThat(versions.get(id).pointsPerYuan()).isEqualTo(150);
    }

    @Test
    void theOperatorSurfaceIsClosedToNonAdministrators() {
        assertThatThrownBy(() -> admin.listVersions(user(USER_ID), null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ---------------------------------------------------------------- throttles

    @Test
    void anActionWithNoConfiguredRuleIsStillProtectedByABuiltInDefault() {
        CommercialRateLimitService.Rule rule =
                rateLimits.rule(CommercialRateLimitService.ACTION_RECHARGE_ORDER_CREATE);

        assertThat(rule.configured()).isFalse();
        assertThat(rule.maxAttempts()).isEqualTo(20);
        assertThat(rule.windowSeconds()).isEqualTo(3600);
        assertThat(rule.enabled()).isTrue();
    }

    @Test
    void everyThrottleableActionHasADefaultSoNoneIsEverUnprotected() {
        assertThat(rateLimits.rules())
                .hasSameSizeAs(CommercialRateLimitService.ACTIONS)
                .allSatisfy(rule -> assertThat(rule.maxAttempts()).isPositive());
    }

    @Test
    void anUnknownActionIsRefusedRatherThanSilentlyUnthrottled() {
        assertThatThrownBy(() -> rateLimits.rule("SOMETHING_ELSE"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void theLimitIsEnforcedAgainstTheRuleThatWasResolved() {
        OffsetDateTime windowStart = OffsetDateTime.now(CLOCK).minusSeconds(3600);

        // One below the default of 20 is allowed; at the limit it is refused.
        rateLimits.enforce(CommercialRateLimitService.ACTION_RECHARGE_ORDER_CREATE,
                since -> {
                    assertThat(since).isEqualTo(windowStart);
                    return 19L;
                });
        assertThatThrownBy(() -> rateLimits.enforce(
                CommercialRateLimitService.ACTION_RECHARGE_ORDER_CREATE, since -> 20L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void anOperatorsRuleOverridesTheDefaultAndADisabledRuleStopsCounting() {
        admin.updateRateLimit(admin(), CommercialRateLimitService.ACTION_FEEDBACK_SUBMIT,
                new PricingDtos.UpdateRateLimitRequest(60, 1, false, 0));

        CommercialRateLimitService.Rule configured =
                rateLimits.rule(CommercialRateLimitService.ACTION_FEEDBACK_SUBMIT);
        assertThat(configured.configured()).isTrue();
        assertThat(configured.maxAttempts()).isEqualTo(1);

        // A disabled rule refuses nothing, and never runs the caller's counting query.
        rateLimits.enforce(CommercialRateLimitService.ACTION_FEEDBACK_SUBMIT, since -> {
            throw new AssertionError("a disabled rule must not count attempts");
        });
    }

    @Test
    void tighteningARuleTakesEffectImmediatelyAndTheWindowTravelsWithIt() {
        admin.updateRateLimit(admin(), CommercialRateLimitService.ACTION_RECHARGE_ORDER_CREATE,
                new PricingDtos.UpdateRateLimitRequest(120, 2, true, 0));

        assertThatThrownBy(() -> rateLimits.enforce(
                CommercialRateLimitService.ACTION_RECHARGE_ORDER_CREATE, since -> {
                    assertThat(since).isEqualTo(OffsetDateTime.now(CLOCK).minusSeconds(120));
                    return 2L;
                }))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void aWindowLongerThanTheSchemaAllowsIsRefusedHereRatherThanByTheDatabase() {
        // ck_commercial_rate_limit_window stops at 24 hours; past that the driver would refuse the
        // row and this application would answer 500 instead of telling the operator what was wrong.
        assertThatThrownBy(() -> admin.updateRateLimit(admin(),
                CommercialRateLimitService.ACTION_FEEDBACK_SUBMIT,
                new PricingDtos.UpdateRateLimitRequest(86401, 10, true, 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> admin.updateRateLimit(admin(),
                CommercialRateLimitService.ACTION_FEEDBACK_SUBMIT,
                new PricingDtos.UpdateRateLimitRequest(0, 10, true, 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void anAttemptCeilingBeyondTheSchemaIsRefusedForTheSameReason() {
        assertThatThrownBy(() -> admin.updateRateLimit(admin(),
                CommercialRateLimitService.ACTION_RECHARGE_ORDER_CREATE,
                new PricingDtos.UpdateRateLimitRequest(3600, 100001, true, 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void theBoundsThemselvesAreStillAcceptedSoTheRuleStaysTunable() {
        PricingDtos.RateLimitRuleResponse rule = admin.updateRateLimit(admin(),
                CommercialRateLimitService.ACTION_FEEDBACK_SUBMIT,
                new PricingDtos.UpdateRateLimitRequest(86400, 100000, true, 0));

        assertThat(rule.windowSeconds()).isEqualTo(86400);
        assertThat(rule.maxAttempts()).isEqualTo(100000);
        assertThat(rule.configured()).isTrue();
    }

    // ---------------------------------------------------------------- infrastructure

    private void stubStore() {
        when(mapper.findActiveVersion()).thenAnswer(inv -> versions.values().stream()
                .filter(row -> "ACTIVE".equals(row.status())).findFirst());
        when(mapper.findVersionById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(versions.get(inv.<Long>getArgument(0))));
        when(mapper.nextVersionNo()).thenAnswer(inv -> versions.values().stream()
                .mapToInt(PricingRecords.PricingVersion::versionNo).max().orElse(0) + 1);
        when(mapper.insertVersion(any())).thenAnswer(inv -> {
            PricingRecords.PricingVersion version = inv.getArgument(0);
            if (versions.values().stream()
                    .anyMatch(row -> row.versionNo() == version.versionNo())) {
                throw new DuplicateKeyException("uk_pricing_version_no");
            }
            versions.put(version.id(), version);
            tiers.put(version.id(), new ArrayList<>());
            return 1;
        });
        when(mapper.updateDraftVersion(anyLong(), anyInt(), anyLong(), anyLong(), any(), anyInt(),
                any())).thenAnswer(inv -> {
                    PricingRecords.PricingVersion current = versions.get(inv.<Long>getArgument(0));
                    if (current == null || !"DRAFT".equals(current.status())
                            || current.version() != inv.<Integer>getArgument(5)) {
                        return 0;
                    }
                    versions.put(current.id(), new PricingRecords.PricingVersion(current.id(),
                            current.versionNo(), current.status(), inv.getArgument(1),
                            inv.getArgument(2), inv.getArgument(3), inv.getArgument(4),
                            current.createdBy(), current.publishedBy(), current.publishedAt(),
                            current.retiredAt(), current.version() + 1, current.createdAt(),
                            inv.getArgument(6)));
                    return 1;
                });
        when(mapper.retireActiveVersion(any())).thenAnswer(inv -> {
            int retired = 0;
            for (PricingRecords.PricingVersion row : List.copyOf(versions.values())) {
                if ("ACTIVE".equals(row.status())) {
                    versions.put(row.id(), withStatus(row, "RETIRED", null, inv.getArgument(0)));
                    retired++;
                }
            }
            return retired;
        });
        when(mapper.activateDraftVersion(anyLong(), anyLong(), any())).thenAnswer(inv -> {
            PricingRecords.PricingVersion current = versions.get(inv.<Long>getArgument(0));
            if (current == null || !"DRAFT".equals(current.status())) return 0;
            versions.put(current.id(), new PricingRecords.PricingVersion(current.id(),
                    current.versionNo(), "ACTIVE", current.pointsPerYuan(),
                    current.minRechargeAmountCents(), current.maxRechargeAmountCents(),
                    current.note(), current.createdBy(), inv.getArgument(1), inv.getArgument(2),
                    null, current.version() + 1, current.createdAt(), inv.getArgument(2)));
            return 1;
        });
        when(mapper.retireVersion(anyLong(), any())).thenAnswer(inv -> {
            PricingRecords.PricingVersion current = versions.get(inv.<Long>getArgument(0));
            if (current == null || !"ACTIVE".equals(current.status())) return 0;
            versions.put(current.id(), withStatus(current, "RETIRED", null, inv.getArgument(1)));
            return 1;
        });
        when(mapper.findVersions(any(), anyLong(), anyInt())).thenAnswer(inv -> versions.values()
                .stream().filter(row -> inv.getArgument(0) == null
                        || row.status().equals(inv.getArgument(0)))
                .sorted((left, right) -> Integer.compare(right.versionNo(), left.versionNo()))
                .toList());
        when(mapper.countVersions(any())).thenAnswer(inv -> (long) versions.values().stream()
                .filter(row -> inv.getArgument(0) == null
                        || row.status().equals(inv.getArgument(0))).count());
        when(mapper.findTiers(anyLong()))
                .thenAnswer(inv -> List.copyOf(tiers.getOrDefault(inv.<Long>getArgument(0),
                        List.of())));
        when(mapper.findTierByAmount(anyLong(), anyLong())).thenAnswer(inv ->
                tiers.getOrDefault(inv.<Long>getArgument(0), List.of()).stream()
                        .filter(tier -> tier.amountCents() == inv.<Long>getArgument(1))
                        .findFirst());
        when(mapper.insertTier(any())).thenAnswer(inv -> {
            PricingRecords.PricingTier tier = inv.getArgument(0);
            List<PricingRecords.PricingTier> existing =
                    tiers.computeIfAbsent(tier.pricingVersionId(), key -> new ArrayList<>());
            existing.add(tier);
            return 1;
        });
        when(mapper.deleteTiers(anyLong())).thenAnswer(inv -> {
            List<PricingRecords.PricingTier> removed =
                    tiers.put(inv.<Long>getArgument(0), new ArrayList<>());
            return removed == null ? 0 : removed.size();
        });
        when(mapper.findRule(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(rules.get(inv.getArgument(0))));
        when(mapper.findRules()).thenAnswer(inv -> List.copyOf(rules.values()));
        when(mapper.insertRule(any())).thenAnswer(inv -> {
            PricingRecords.RateLimitRule rule = inv.getArgument(0);
            if (rules.putIfAbsent(rule.action(), rule) != null) {
                throw new DuplicateKeyException("uk_commercial_rate_limit_action");
            }
            return 1;
        });
        when(mapper.updateRule(anyString(), anyInt(), anyInt(), anyBoolean(), anyLong(), anyInt(),
                any()))
                .thenAnswer(inv -> {
                    PricingRecords.RateLimitRule current = rules.get(inv.<String>getArgument(0));
                    if (current == null || current.version() != inv.<Integer>getArgument(5)) return 0;
                    rules.put(current.action(), new PricingRecords.RateLimitRule(current.id(),
                            current.action(), inv.getArgument(1), inv.getArgument(2),
                            inv.getArgument(3), inv.getArgument(4), current.version() + 1,
                            current.createdAt(), inv.getArgument(6)));
                    return 1;
                });
    }

    private long createDraft(int pointsPerYuan, List<PricingDtos.TierRequest> tierRequests,
                             long minCents, long maxCents) {
        return Long.parseLong(admin.createDraft(admin(), new PricingDtos.CreatePricingVersionRequest(
                pointsPerYuan, minCents, maxCents, "测试版本", tierRequests)).id());
    }

    private long publishDraft(int pointsPerYuan, List<PricingDtos.TierRequest> tierRequests,
                              long minCents, long maxCents) {
        long id = createDraft(pointsPerYuan, tierRequests, minCents, maxCents);
        admin.publish(admin(), id);
        return id;
    }

    private static PricingDtos.TierRequest tier(long amountCents, int giftPoints, String label) {
        return new PricingDtos.TierRequest(amountCents, giftPoints, label, null);
    }

    private static PricingRecords.PricingVersion withStatus(PricingRecords.PricingVersion version,
                                                            String status, Long publishedBy,
                                                            OffsetDateTime retiredAt) {
        return new PricingRecords.PricingVersion(version.id(), version.versionNo(), status,
                version.pointsPerYuan(), version.minRechargeAmountCents(),
                version.maxRechargeAmountCents(), version.note(), version.createdBy(), publishedBy,
                version.publishedAt(), retiredAt, version.version() + 1, version.createdAt(),
                version.updatedAt());
    }

    private static AuthenticatedAccount admin() {
        return account(OPERATOR_ID, AccountRole.ADMIN);
    }

    private static AuthenticatedAccount user(long id) {
        return account(id, AccountRole.USER);
    }

    private static AuthenticatedAccount account(long id, AccountRole role) {
        return new AuthenticatedAccount(id, "user" + id, role, 1, AccountStatus.ACTIVE, null, null,
                null);
    }
}
