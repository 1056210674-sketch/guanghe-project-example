package com.guanghe.backend.billing.pricing;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.BillingAccess;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Throttles the commercial write operations a user can trigger.
 *
 * <p>Attempts are counted from the rows the operation actually produces — recharge orders, invoice
 * applications, feedback — rather than from a parallel counter. A separate counter would be a second
 * source of truth that can drift from reality after a rollback; counting real rows cannot.
 *
 * <p>The rule is resolved per call and falls back to a built-in default when no row exists, so a
 * deployment is protected before an operator has ever opened the rules page. The administrator view
 * reports which rules are operator-set and which are still defaults.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CommercialRateLimitService {

    public static final String ACTION_RECHARGE_ORDER_CREATE = "RECHARGE_ORDER_CREATE";
    public static final String ACTION_INVOICE_APPLICATION_SUBMIT = "INVOICE_APPLICATION_SUBMIT";
    public static final String ACTION_FEEDBACK_SUBMIT = "FEEDBACK_SUBMIT";

    /** Every throttleable action, in the order the administrator page shows them. */
    public static final List<String> ACTIONS = List.of(ACTION_RECHARGE_ORDER_CREATE,
            ACTION_INVOICE_APPLICATION_SUBMIT, ACTION_FEEDBACK_SUBMIT);

    private static final int HOUR = 3600;

    /** The same bounds {@code ck_commercial_rate_limit_window} imposes, in seconds. */
    private static final int MAX_WINDOW_SECONDS = 86400;

    /** The same bound {@code ck_commercial_rate_limit_attempts} imposes. */
    private static final int MAX_ATTEMPTS = 100000;

    private final PricingMapper mapper;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;

    public CommercialRateLimitService(PricingMapper mapper, SnowflakeIdGenerator ids,
                                      @Qualifier("billingClock") Clock clock) {
        this.mapper = mapper;
        this.ids = ids;
        this.clock = clock;
    }

    /** The rule in force for an action, whether operator-set or built in. */
    @Transactional(readOnly = true)
    public Rule rule(String action) {
        requireKnownAction(action);
        return mapper.findRule(action)
                .map(row -> new Rule(row.action(), row.windowSeconds(), row.maxAttempts(),
                        row.enabled(), true, row.version(), Long.toString(row.id()),
                        Long.toString(row.updatedBy()), row.updatedAt()))
                .orElseGet(() -> defaultRule(action));
    }

    /** Every action and the rule that applies to it, defaults included. */
    @Transactional(readOnly = true)
    public List<Rule> rules() {
        Map<String, PricingRecords.RateLimitRule> stored = new LinkedHashMap<>();
        mapper.findRules().forEach(row -> stored.put(row.action(), row));
        return ACTIONS.stream().map(action -> {
            PricingRecords.RateLimitRule row = stored.get(action);
            if (row == null) return defaultRule(action);
            return new Rule(row.action(), row.windowSeconds(), row.maxAttempts(), row.enabled(),
                    true, row.version(), Long.toString(row.id()), Long.toString(row.updatedBy()),
                    row.updatedAt());
        }).toList();
    }

    @Transactional
    public Rule update(AuthenticatedAccount actor, String action,
                       PricingDtos.UpdateRateLimitRequest request) {
        long operatorId = BillingAccess.requireAdmin(actor);
        requireKnownAction(action);
        validateRuleBounds(request.windowSeconds(), request.maxAttempts());
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (request.expectedVersion() == 0) {
            PricingRecords.RateLimitRule row = new PricingRecords.RateLimitRule(ids.nextId(), action,
                    request.windowSeconds(), request.maxAttempts(), request.enabled(), operatorId, 1,
                    now, now);
            try {
                mapper.insertRule(row);
            } catch (DuplicateKeyException exception) {
                // Somebody created it between our read and this write; report a conflict, do not
                // overwrite their rule with ours.
                throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
            }
            return rule(action);
        }
        if (mapper.updateRule(action, request.windowSeconds(), request.maxAttempts(),
                request.enabled(), operatorId, request.expectedVersion(), now) != 1) {
            // The row moved on, or never existed. Either way the caller's view was stale.
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
        return rule(action);
    }

    /**
     * Refuses the attempt when the window is already full.
     *
     * <p>Called before the write, inside the caller's transaction. A request that is refused leaves
     * no row, so it does not itself consume budget.
     *
     * <p>The caller supplies the count rather than the window it was taken over: the rule is resolved
     * once here and its own window boundary is handed to {@code countSince}. A caller therefore
     * cannot take its count over one window and enforce another rule's limit against it, and a
     * disabled rule skips the counting query altogether.
     *
     * @param countSince given the start of the counting window, returns how many attempts the caller
     *                   has already recorded since then
     */
    @Transactional(readOnly = true)
    public void enforce(String action, ToLongFunction<OffsetDateTime> countSince) {
        Rule rule = rule(action);
        if (!rule.enabled()) return;
        long attemptsInWindow = countSince.applyAsLong(OffsetDateTime.now(clock).minusSeconds(
                rule.windowSeconds()));
        if (attemptsInWindow >= rule.maxAttempts()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.defaultMessage(),
                    Map.of("action", rule.action(), "windowSeconds", rule.windowSeconds(),
                            "maxAttempts", rule.maxAttempts()));
        }
    }

    private static void requireKnownAction(String action) {
        if (action == null || !ACTIONS.contains(action)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * The bounds the schema already imposes on a stored rule.
     *
     * <p>Mirrored here for the same reason {@link PricingAdminService} mirrors the pricing bounds: a
     * value outside them is refused by {@code ck_commercial_rate_limit_window} or
     * {@code ck_commercial_rate_limit_attempts} at the driver, and this application reports a
     * driver-level integrity error as a 500. A transposed digit in "how many attempts per hour" is
     * an operator typo, and a 422 is the answer that lets them see it.
     */
    private static void validateRuleBounds(int windowSeconds, int maxAttempts) {
        if (windowSeconds < 1 || windowSeconds > MAX_WINDOW_SECONDS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "统计窗口必须在 1 秒到 24 小时之间",
                    Map.of("windowSeconds", windowSeconds, "maxWindowSeconds", MAX_WINDOW_SECONDS));
        }
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "次数上限必须在 1 到 100000 之间",
                    Map.of("maxAttempts", maxAttempts, "maxAttemptsSupported", MAX_ATTEMPTS));
        }
    }

    private static Rule defaultRule(String action) {
        int maxAttempts = switch (action) {
            case ACTION_RECHARGE_ORDER_CREATE -> 20;
            case ACTION_INVOICE_APPLICATION_SUBMIT -> 5;
            case ACTION_FEEDBACK_SUBMIT -> 10;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
        return new Rule(action, HOUR, maxAttempts, true, false, 0, null, null, null);
    }

    /**
     * A rule as it applies right now.
     *
     * @param configured false when the built-in default is in force because no row exists
     * @param version    0 for a default; the optimistic version for a stored rule
     */
    public record Rule(String action, int windowSeconds, int maxAttempts, boolean enabled,
                       boolean configured, int version, String id, String updatedBy,
                       OffsetDateTime updatedAt) {
    }
}
