package com.guanghe.backend.billing;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.notification.NotificationService;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-initiated point credits, behind a mandatory two-step confirmation.
 *
 * <p>A request only ever records intent. Nothing is credited until the operator re-submits the
 * single-use token that was returned once at creation — so a mistaken or maliciously repeated
 * request cannot move a balance on its own, and an operator cannot credit an account by accident
 * while browsing.
 *
 * <p>The credit itself goes through {@link PointCreditService}, the same path recharge settlement
 * uses. There is no second way to increase a balance in this system.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ManualPointAdjustmentService {

    public static final String STATUS_PENDING = "PENDING_CONFIRMATION";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    private static final String DIRECTION_CREDIT = "CREDIT";

    private static final Set<String> FILTERABLE_STATUSES =
            Set.of(STATUS_PENDING, STATUS_APPLIED, STATUS_CANCELLED);

    private static final String TARGET_ADJUSTMENT = "POINT_ADJUSTMENT";
    private static final int TOKEN_BYTES = 32;

    private final BillingMapper mapper;
    private final SnowflakeIdGenerator ids;
    private final BillingProperties properties;
    private final PointCreditService points;
    private final OperationalAuditService audit;
    private final NotificationService notifications;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ManualPointAdjustmentService(BillingMapper mapper, SnowflakeIdGenerator ids,
                                        BillingProperties properties, PointCreditService points,
                                        OperationalAuditService audit, NotificationService notifications,
                                        @Qualifier("billingClock") Clock clock) {
        this.mapper = mapper;
        this.ids = ids;
        this.properties = properties;
        this.points = points;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * Requests a credit. Returns the confirmation token exactly once; only its hash is stored, so
     * the server cannot reproduce it and a replay of this call cannot either.
     */
    @Transactional
    public BillingDtos.PointAdjustmentResponse request(AuthenticatedAccount actor,
                                                       String idempotencyKey,
                                                       BillingDtos.CreatePointAdjustmentRequest body) {
        long operatorId = BillingAccess.requireAdmin(actor);
        long targetUserId = body.userId();
        int grantedPoints = body.points();
        if (grantedPoints <= 0 || grantedPoints > properties.getManualAdjustmentMaxPoints()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String reason = body.reason().strip();

        String key = "adjust:" + uuid(idempotencyKey);
        String requestHash = sha256(targetUserId + "\n" + grantedPoints + "\n" + reason);
        BillingRecords.ManualAdjustment existing =
                mapper.findAdjustmentByIdempotencyKey(key).orElse(null);
        if (existing != null) return replay(existing, requestHash);

        OffsetDateTime now = OffsetDateTime.now(clock);
        String token = token();
        long adjustmentId = ids.nextId();
        BillingRecords.ManualAdjustment adjustment = new BillingRecords.ManualAdjustment(adjustmentId,
                targetUserId, grantedPoints, DIRECTION_CREDIT, reason, operatorId, key, requestHash,
                sha256(token), now.plus(properties.getConfirmationTokenTtl()), STATUS_PENDING, null,
                null, now, now);
        try {
            if (mapper.insertAdjustment(adjustment) != 1) {
                throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
            }
        } catch (DuplicateKeyException exception) {
            BillingRecords.ManualAdjustment raced =
                    mapper.findAdjustmentByIdempotencyKey(key).orElse(null);
            if (raced == null) throw exception;
            return replay(raced, requestHash);
        }
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_ADJUSTMENT,
                adjustmentId, OperationalAuditService.POINT_ADJUSTMENT_REQUESTED,
                OperationalAuditService.OUTCOME_PENDING,
                "{\"targetUserId\":" + targetUserId + ",\"points\":" + grantedPoints + "}");
        return response(adjustment, token);
    }

    /**
     * Applies a previously requested credit.
     *
     * <p>The adjustment row is locked before anything is written, so two concurrent confirmations
     * of the same token serialise: the loser sees an already-applied row and is refused.
     */
    @Transactional
    public BillingDtos.PointAdjustmentResponse confirm(AuthenticatedAccount actor, long adjustmentId,
                                                       String confirmationToken) {
        long operatorId = BillingAccess.requireAdmin(actor);
        BillingRecords.ManualAdjustment adjustment = mapper.lockAdjustmentById(adjustmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!STATUS_PENDING.equals(adjustment.status())) {
            throw new BusinessException(ErrorCode.ADJUSTMENT_ALREADY_APPLIED);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!adjustment.confirmationExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.CONFIRMATION_TOKEN_INVALID);
        }
        if (!MessageDigest.isEqual(
                adjustment.confirmationTokenHash().getBytes(StandardCharsets.US_ASCII),
                sha256(confirmationToken).getBytes(StandardCharsets.US_ASCII))) {
            audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_ADJUSTMENT,
                    adjustmentId, OperationalAuditService.POINT_ADJUSTMENT_REJECTED,
                    OperationalAuditService.OUTCOME_FAILURE,
                    "{\"reason\":\"CONFIRMATION_TOKEN_INVALID\"}");
            throw new BusinessException(ErrorCode.CONFIRMATION_TOKEN_INVALID);
        }
        // The ledger key is derived from the adjustment id, so even a hypothetical double
        // confirmation cannot produce a second credit.
        long ledgerId = points.credit(adjustment.userId(), adjustment.points(),
                PointCreditService.ENTRY_ADMIN_ADJUSTMENT, PointCreditService.BUSINESS_ADMIN_ADJUSTMENT,
                adjustmentId, "adjustment:" + adjustmentId, operatorId, adjustment.reason());
        if (mapper.markAdjustmentApplied(adjustmentId, ledgerId, now) != 1) {
            throw new BusinessException(ErrorCode.ADJUSTMENT_ALREADY_APPLIED);
        }
        BillingRecords.ManualAdjustment applied = mapper.findAdjustmentById(adjustmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_ADJUSTMENT,
                adjustmentId, OperationalAuditService.POINT_ADJUSTMENT_APPLIED,
                OperationalAuditService.OUTCOME_SUCCESS,
                "{\"targetUserId\":" + applied.userId() + ",\"points\":" + applied.points()
                        + ",\"ledgerId\":" + ledgerId + "}");
        notifications.publish(applied.userId(), NotificationService.CATEGORY_POINTS, "积分已到账",
                "管理员已为您补充 " + applied.points() + " 积分，可在积分明细中查看本次入账。",
                "POINT_LEDGER", ledgerId, "POINT_ADJUSTMENT_APPLIED:" + adjustmentId);
        return response(applied, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<BillingDtos.PointAdjustmentResponse> list(AuthenticatedAccount actor,
                                                                  Long userId, String status,
                                                                  int page, int size) {
        BillingAccess.requireAdmin(actor);
        String normalized = status == null || status.isBlank() ? null : status;
        if (normalized != null && !FILTERABLE_STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long offset = (long) (page - 1) * size;
        List<BillingDtos.PointAdjustmentResponse> items = mapper
                .findAdjustmentsForAdmin(userId, normalized, offset, size).stream()
                .map(adjustment -> response(adjustment, null)).toList();
        return PageResponse.of(items, page, size,
                mapper.countAdjustmentsForAdmin(userId, normalized));
    }

    private BillingDtos.PointAdjustmentResponse replay(BillingRecords.ManualAdjustment existing,
                                                       String requestHash) {
        if (!MessageDigest.isEqual(existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        // The token is unrecoverable by design, so a replayed request returns the pending record
        // without one. The operator must use the token they were already given.
        return response(existing, null);
    }

    private BillingDtos.PointAdjustmentResponse response(BillingRecords.ManualAdjustment adjustment,
                                                         String confirmationToken) {
        return new BillingDtos.PointAdjustmentResponse(Long.toString(adjustment.id()),
                Long.toString(adjustment.userId()), adjustment.points(), adjustment.direction(),
                adjustment.reason(), adjustment.status(), Long.toString(adjustment.operatorId()),
                confirmationToken, adjustment.confirmationExpiresAt(),
                adjustment.ledgerId() == null ? null : Long.toString(adjustment.ledgerId()),
                adjustment.appliedAt(), adjustment.createdAt());
    }

    /** URL-safe, unpadded, 256 bits of entropy: 43 characters. */
    private String token() {
        byte[] value = new byte[TOKEN_BYTES];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
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
