package com.guanghe.backend.billing.feedback;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.OperationalAuditService;
import com.guanghe.backend.billing.pricing.CommercialRateLimitService;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User feedback, from the author's side.
 *
 * <p>Submission is idempotent on the namespaced {@code Idempotency-Key}, and throttled by counting
 * the rows the author has actually created. Feedback is free text from a customer: it is treated as
 * data throughout and never rendered anywhere as markup.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class FeedbackService {

    public static final String STATUS_SUBMITTED = "SUBMITTED";

    private static final String TARGET_FEEDBACK = "USER_FEEDBACK";

    private final FeedbackMapper mapper;
    private final SnowflakeIdGenerator ids;
    private final CommercialRateLimitService rateLimits;
    private final OperationalAuditService audit;
    private final Clock clock;

    public FeedbackService(FeedbackMapper mapper, SnowflakeIdGenerator ids,
                           CommercialRateLimitService rateLimits, OperationalAuditService audit,
                           @Qualifier("billingClock") Clock clock) {
        this.mapper = mapper;
        this.ids = ids;
        this.rateLimits = rateLimits;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public FeedbackDtos.FeedbackResponse submit(AuthenticatedAccount account, String idempotencyKey,
                                                FeedbackDtos.CreateFeedbackRequest request) {
        long userId = CatalogAccess.requireUser(account);
        String key = userId + ":" + uuid(idempotencyKey);
        String requestHash = sha256(request.category() + "\n" + request.content().trim() + "\n"
                + nullToEmpty(request.contact()));

        rateLimits.enforce(CommercialRateLimitService.ACTION_FEEDBACK_SUBMIT,
                since -> mapper.countSubmittedSince(userId, since));

        FeedbackRecords.UserFeedback existing = mapper.findByIdempotencyKey(key).orElse(null);
        if (existing != null) return replay(existing, requestHash);

        OffsetDateTime now = OffsetDateTime.now(clock);
        long id = ids.nextId();
        FeedbackRecords.UserFeedback feedback = new FeedbackRecords.UserFeedback(id, userId,
                request.category(), request.content().trim(), blankToNull(request.contact()),
                STATUS_SUBMITTED, null, null, null, key, requestHash, 1, now, now);
        try {
            if (mapper.insert(feedback) != 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } catch (DuplicateKeyException exception) {
            FeedbackRecords.UserFeedback raced = mapper.findByIdempotencyKey(key).orElse(null);
            if (raced == null) throw exception;
            return replay(raced, requestHash);
        }
        audit.actorUser(OperationalAuditService.ACTOR_USER, userId, TARGET_FEEDBACK, id,
                "USER_FEEDBACK_SUBMITTED", OperationalAuditService.OUTCOME_SUCCESS,
                // The content itself is deliberately not copied into the audit trail: the audit
                // table is read by more operators than the feedback queue, and free text from a
                // customer does not need a second home.
                "{\"category\":\"" + request.category() + "\",\"contentLength\":"
                        + request.content().trim().length() + "}");
        return response(feedback);
    }

    @Transactional(readOnly = true)
    public PageResponse<FeedbackDtos.FeedbackResponse> list(AuthenticatedAccount account, int page,
                                                            int size) {
        long userId = CatalogAccess.requireUser(account);
        long offset = (long) (page - 1) * size;
        List<FeedbackDtos.FeedbackResponse> items = mapper.findByUser(userId, offset, size).stream()
                .map(FeedbackService::response).toList();
        return PageResponse.of(items, page, size, mapper.countByUser(userId));
    }

    @Transactional(readOnly = true)
    public FeedbackDtos.FeedbackResponse get(AuthenticatedAccount account, long id) {
        long userId = CatalogAccess.requireUser(account);
        // Somebody else's feedback is absent, not forbidden.
        return response(mapper.findById(id).filter(row -> row.userId() == userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FEEDBACK_NOT_FOUND)));
    }

    private FeedbackDtos.FeedbackResponse replay(FeedbackRecords.UserFeedback existing,
                                                 String requestHash) {
        if (!MessageDigest.isEqual(existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return response(existing);
    }

    static FeedbackDtos.FeedbackResponse response(FeedbackRecords.UserFeedback feedback) {
        return new FeedbackDtos.FeedbackResponse(Long.toString(feedback.id()), feedback.category(),
                feedback.content(), feedback.contact(), feedback.status(), feedback.reply(),
                feedback.handledAt(), feedback.createdAt(), feedback.updatedAt());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static java.util.UUID uuid(String value) {
        try {
            return java.util.UUID.fromString(value);
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
