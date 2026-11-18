package com.guanghe.backend.billing.pricing;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.BillingAccess;
import com.guanghe.backend.billing.OperationalAuditService;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator surface for the commercial rate card.
 *
 * <p>The lifecycle is deliberately two-step: a version is created as a DRAFT, edited freely, and
 * only then published. Publishing retires its predecessor in the same transaction, because the
 * schema permits exactly one active version and because a gap in which no rate applies would be a
 * worse outcome than either rate.
 *
 * <p>A published version is immutable. Orders cite it, so editing it afterwards would silently
 * re-price decisions already taken.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PricingAdminService {

    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "RETIRED");
    private static final String TARGET_PRICING_VERSION = "PRICING_VERSION";
    private static final String TARGET_RATE_LIMIT = "COMMERCIAL_RATE_LIMIT";

    private final PricingMapper mapper;
    private final RateCardResolver resolver;
    private final CommercialRateLimitService rateLimits;
    private final OperationalAuditService audit;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;

    public PricingAdminService(PricingMapper mapper, RateCardResolver resolver,
                               CommercialRateLimitService rateLimits, OperationalAuditService audit,
                               SnowflakeIdGenerator ids, @Qualifier("billingClock") Clock clock) {
        this.mapper = mapper;
        this.resolver = resolver;
        this.rateLimits = rateLimits;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- versions

    @Transactional(readOnly = true)
    public PageResponse<PricingDtos.PricingVersionResponse> listVersions(AuthenticatedAccount actor,
                                                                        String status, int page,
                                                                        int size) {
        BillingAccess.requireAdmin(actor);
        String normalized = blankToNull(status);
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long offset = (long) (page - 1) * size;
        List<PricingDtos.PricingVersionResponse> items = mapper
                .findVersions(normalized, offset, size).stream()
                .map(version -> response(version, mapper.findTiers(version.id()))).toList();
        return PageResponse.of(items, page, size, mapper.countVersions(normalized));
    }

    @Transactional(readOnly = true)
    public PricingDtos.PricingVersionResponse getVersion(AuthenticatedAccount actor, long id) {
        BillingAccess.requireAdmin(actor);
        PricingRecords.PricingVersion version = requireVersion(id);
        return response(version, mapper.findTiers(version.id()));
    }

    @Transactional
    public PricingDtos.PricingVersionResponse createDraft(AuthenticatedAccount actor,
                                                         PricingDtos.CreatePricingVersionRequest request) {
        long operatorId = BillingAccess.requireAdmin(actor);
        validateBounds(request.minRechargeAmountCents(), request.maxRechargeAmountCents());
        List<PricingDtos.TierRequest> tiers = validateTiers(request.tiers(),
                request.minRechargeAmountCents(), request.maxRechargeAmountCents());
        OffsetDateTime now = OffsetDateTime.now(clock);
        long versionId = ids.nextId();
        PricingRecords.PricingVersion version = new PricingRecords.PricingVersion(versionId,
                mapper.nextVersionNo(), "DRAFT", request.pointsPerYuan(),
                request.minRechargeAmountCents(), request.maxRechargeAmountCents(),
                blankToNull(request.note()), operatorId, null, null, null, 1, now, now);
        try {
            mapper.insertVersion(version);
        } catch (DuplicateKeyException exception) {
            // Two operators drafted at once and derived the same version number.
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        insertTiers(versionId, tiers, now);
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_PRICING_VERSION,
                versionId, "PRICING_VERSION_DRAFTED", OperationalAuditService.OUTCOME_SUCCESS,
                "{\"versionNo\":" + version.versionNo() + ",\"pointsPerYuan\":"
                        + request.pointsPerYuan() + ",\"tiers\":" + tiers.size() + "}");
        return response(requireVersion(versionId), mapper.findTiers(versionId));
    }

    @Transactional
    public PricingDtos.PricingVersionResponse updateDraft(AuthenticatedAccount actor, long id,
                                                          PricingDtos.UpdatePricingVersionRequest request) {
        long operatorId = BillingAccess.requireAdmin(actor);
        validateBounds(request.minRechargeAmountCents(), request.maxRechargeAmountCents());
        List<PricingDtos.TierRequest> tiers = validateTiers(request.tiers(),
                request.minRechargeAmountCents(), request.maxRechargeAmountCents());
        PricingRecords.PricingVersion existing = requireVersion(id);
        if (!"DRAFT".equals(existing.status())) {
            // Published rates are cited by orders; editing one would re-price history.
            throw new BusinessException(ErrorCode.PRICING_VERSION_IN_USE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (mapper.updateDraftVersion(id, request.pointsPerYuan(), request.minRechargeAmountCents(),
                request.maxRechargeAmountCents(), blankToNull(request.note()),
                request.expectedVersion(), now) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        mapper.deleteTiers(id);
        insertTiers(id, tiers, now);
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_PRICING_VERSION, id,
                "PRICING_VERSION_UPDATED", OperationalAuditService.OUTCOME_SUCCESS,
                "{\"versionNo\":" + existing.versionNo() + ",\"tiers\":" + tiers.size() + "}");
        return response(requireVersion(id), mapper.findTiers(id));
    }

    /** Publishes a draft, retiring the version it replaces in the same transaction. */
    @Transactional
    public PricingDtos.PricingVersionResponse publish(AuthenticatedAccount actor, long id) {
        long operatorId = BillingAccess.requireAdmin(actor);
        PricingRecords.PricingVersion existing = requireVersion(id);
        if (!"DRAFT".equals(existing.status())) {
            throw new BusinessException(ErrorCode.PRICING_VERSION_CONFLICT);
        }
        if (mapper.findTiers(id).isEmpty()) {
            // A published rate with no tiers is a rate card the payer page cannot render.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        mapper.retireActiveVersion(now);
        if (mapper.activateDraftVersion(id, operatorId, now) != 1) {
            throw new BusinessException(ErrorCode.PRICING_VERSION_CONFLICT);
        }
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_PRICING_VERSION, id,
                "PRICING_VERSION_PUBLISHED", OperationalAuditService.OUTCOME_SUCCESS,
                "{\"versionNo\":" + existing.versionNo() + ",\"pointsPerYuan\":"
                        + existing.pointsPerYuan() + "}");
        return response(requireVersion(id), mapper.findTiers(id));
    }

    @Transactional
    public PricingDtos.PricingVersionResponse retire(AuthenticatedAccount actor, long id) {
        long operatorId = BillingAccess.requireAdmin(actor);
        PricingRecords.PricingVersion existing = requireVersion(id);
        if (!"ACTIVE".equals(existing.status())) {
            throw new BusinessException(ErrorCode.PRICING_VERSION_CONFLICT);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (mapper.retireVersion(id, now) != 1) {
            throw new BusinessException(ErrorCode.PRICING_VERSION_CONFLICT);
        }
        // Retiring the only active version leaves the deployment on the placeholder rate. That is a
        // legitimate state — and one the audit row records, so nobody has to guess why prices moved.
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_PRICING_VERSION, id,
                "PRICING_VERSION_RETIRED", OperationalAuditService.OUTCOME_SUCCESS,
                "{\"versionNo\":" + existing.versionNo() + ",\"placeholderRateNowInForce\":true}");
        return response(requireVersion(id), mapper.findTiers(id));
    }

    /** Whether the deployment is running on a published rate or the placeholder. */
    @Transactional(readOnly = true)
    public RateCardResolver.RateCard currentRateCard(AuthenticatedAccount actor) {
        BillingAccess.requireAdmin(actor);
        return resolver.current();
    }

    // ---------------------------------------------------------------- rate limits

    @Transactional(readOnly = true)
    public List<PricingDtos.RateLimitRuleResponse> listRateLimits(AuthenticatedAccount actor) {
        BillingAccess.requireAdmin(actor);
        return rateLimits.rules().stream().map(PricingAdminService::ruleResponse).toList();
    }

    @Transactional
    public PricingDtos.RateLimitRuleResponse updateRateLimit(AuthenticatedAccount actor, String action,
                                                             PricingDtos.UpdateRateLimitRequest request) {
        long operatorId = BillingAccess.requireAdmin(actor);
        CommercialRateLimitService.Rule updated = rateLimits.update(actor, action, request);
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_RATE_LIMIT, null,
                "COMMERCIAL_RATE_LIMIT_UPDATED", OperationalAuditService.OUTCOME_SUCCESS,
                "{\"action\":\"" + updated.action() + "\",\"windowSeconds\":"
                        + updated.windowSeconds() + ",\"maxAttempts\":" + updated.maxAttempts()
                        + ",\"enabled\":" + updated.enabled() + "}");
        return ruleResponse(updated);
    }

    // ---------------------------------------------------------------- helpers

    private PricingRecords.PricingVersion requireVersion(long id) {
        return mapper.findVersionById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRICING_VERSION_NOT_FOUND));
    }

    private void insertTiers(long versionId, List<PricingDtos.TierRequest> tiers,
                             OffsetDateTime now) {
        for (int index = 0; index < tiers.size(); index++) {
            PricingDtos.TierRequest tier = tiers.get(index);
            mapper.insertTier(new PricingRecords.PricingTier(ids.nextId(), versionId,
                    tier.amountCents(), tier.giftPoints() == null ? 0 : tier.giftPoints(),
                    tier.label(), tier.sortOrder() == null ? index : tier.sortOrder(), now));
        }
    }

    /**
     * The bounds, and the two things about them the rate card depends on.
     *
     * <p>The first is a mirror of the schema. {@code ck_pricing_version_bounds} already requires
     * {@code min > 0} and {@code max >= min}; checking them here as well is what keeps an operator's
     * typo a 422 instead of a driver-level integrity error, which this application reports as a 500.
     *
     * <p>The second comes from {@link RateCardResolver.RateCard#basePointsFor}: it prices in whole
     * yuan, because the rate is "points per yuan". An amount below one yuan therefore buys nothing,
     * and a minimum that is not a whole number of yuan would advertise a price whose remainder is
     * silently dropped — ¥1.50 at 100 points per yuan is 100 points, not 150. A card naming such an
     * amount is one whose own minimum the order endpoint answers with
     * {@code RECHARGE_AMOUNT_OUT_OF_RANGE}. Refusing it at authoring time is the only place the
     * operator can still see the mistake.
     */
    private static void validateBounds(long minCents, long maxCents) {
        if (minCents < 100 || minCents % 100 != 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "起充金额必须是不低于 1 元的整元金额",
                    java.util.Map.of("minRechargeAmountCents", minCents));
        }
        if (maxCents < minCents || maxCents % 100 != 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "单笔上限必须是不低于起充金额的整元金额",
                    java.util.Map.of("maxRechargeAmountCents", maxCents));
        }
    }

    /**
     * Tier amounts and gifts.
     *
     * <p>Duplicate amounts are refused because {@code giftPointsFor} looks a tier up by exact
     * amount, so two tiers for the same amount would make "the gift for this amount" ambiguous.
     *
     * <p>A tier outside the bounds is refused for the same reason: an exact-amount tier the payer
     * cannot pay is configuration that looks active on the admin page and can never fire. Tier
     * amounts must be whole yuan for the same reason the bounds must be.
     *
     * <p>Non-positive amounts and negative gifts are mirrors of
     * {@code ck_pricing_version_tier_amount} and {@code ck_pricing_version_tier_gift}, checked here
     * so they are refused rather than stored and then refused by the database.
     */
    private static List<PricingDtos.TierRequest> validateTiers(List<PricingDtos.TierRequest> tiers,
                                                               long minCents, long maxCents) {
        Set<Long> seen = new HashSet<>();
        for (PricingDtos.TierRequest tier : tiers) {
            long amountCents = tier.amountCents();
            int giftPoints = tier.giftPoints() == null ? 0 : tier.giftPoints();
            if (amountCents < 100 || amountCents % 100 != 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "档位金额必须是不低于 1 元的整元金额",
                        java.util.Map.of("amountCents", amountCents));
            }
            if (amountCents < minCents || amountCents > maxCents) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "档位金额必须落在起充金额与单笔上限之间",
                        java.util.Map.of("amountCents", amountCents, "minRechargeAmountCents", minCents,
                                "maxRechargeAmountCents", maxCents));
            }
            if (giftPoints < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "赠送积分数不能为负数",
                        java.util.Map.of("giftPoints", giftPoints));
            }
            if (!seen.add(amountCents)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "同一档位金额重复", java.util.Map.of("amountCents", amountCents));
            }
        }
        return List.copyOf(tiers);
    }

    static PricingDtos.PricingVersionResponse response(PricingRecords.PricingVersion version,
                                                       List<PricingRecords.PricingTier> tiers) {
        RateCardResolver.RateCard card = new RateCardResolver.RateCard(null, version.versionNo(),
                version.pointsPerYuan(), version.minRechargeAmountCents(),
                version.maxRechargeAmountCents(), true, version.note(), version.publishedAt(),
                tiers.stream().map(tier -> new RateCardResolver.RateCard.Tier(tier.amountCents(),
                        tier.giftPoints(), tier.label(), tier.sortOrder())).toList());
        return new PricingDtos.PricingVersionResponse(Long.toString(version.id()), version.versionNo(),
                version.status(), version.pointsPerYuan(), version.minRechargeAmountCents(),
                version.maxRechargeAmountCents(), version.note(),
                Long.toString(version.createdBy()),
                version.publishedBy() == null ? null : Long.toString(version.publishedBy()),
                version.publishedAt(), version.retiredAt(),
                card.tiers().stream().map(tier -> PricingService.tier(card, tier)).toList(),
                version.createdAt(), version.updatedAt());
    }

    static PricingDtos.RateLimitRuleResponse ruleResponse(CommercialRateLimitService.Rule rule) {
        return new PricingDtos.RateLimitRuleResponse(rule.id(), rule.action(), rule.windowSeconds(),
                rule.maxAttempts(), rule.enabled(), rule.configured(), rule.version(),
                rule.updatedBy(), rule.updatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
