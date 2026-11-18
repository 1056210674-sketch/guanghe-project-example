package com.guanghe.backend.billing.feedback;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.BillingAccess;
import com.guanghe.backend.billing.OperationalAuditService;
import com.guanghe.backend.billing.notification.NotificationService;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator handling of user feedback.
 *
 * <p>Handling moves strictly forward: acknowledged, then resolved, then closed. A resolved item
 * cannot be sent back to acknowledged, because the user has already been told it was resolved and
 * silently reversing that would be worse than opening a new reply. Closing is always allowed, so an
 * item can be put down whatever state it is in.
 *
 * <p>Every handling action requires a reply, and the reply is what the author is notified about.
 */
@Service
@ConditionalOnProperty(prefix = "zhixiaohe.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class FeedbackAdminService {

    public static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_CLOSED = "CLOSED";

    static final Set<String> STATUSES = Set.of(FeedbackService.STATUS_SUBMITTED,
            STATUS_ACKNOWLEDGED, STATUS_RESOLVED, STATUS_CLOSED);

    /** Forward-only ordering. Closing is reachable from anywhere, so it sorts highest. */
    private static final Map<String, Integer> RANK = Map.of(
            FeedbackService.STATUS_SUBMITTED, 0, STATUS_ACKNOWLEDGED, 1, STATUS_RESOLVED, 2,
            STATUS_CLOSED, 3);

    private static final String TARGET_FEEDBACK = "USER_FEEDBACK";

    private final FeedbackMapper mapper;
    private final NotificationService notifications;
    private final OperationalAuditService audit;
    private final Clock clock;

    public FeedbackAdminService(FeedbackMapper mapper, NotificationService notifications,
                                OperationalAuditService audit,
                                @Qualifier("billingClock") Clock clock) {
        this.mapper = mapper;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<FeedbackDtos.FeedbackResponse> list(AuthenticatedAccount actor, Long userId,
                                                            String status, int page, int size) {
        BillingAccess.requireAdmin(actor);
        String normalized = status == null || status.isBlank() ? null : status;
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long offset = (long) (page - 1) * size;
        List<FeedbackDtos.FeedbackResponse> items = mapper
                .findForAdmin(userId, normalized, offset, size).stream()
                .map(FeedbackService::response).toList();
        return PageResponse.of(items, page, size, mapper.countForAdmin(userId, normalized));
    }

    @Transactional
    public FeedbackDtos.FeedbackResponse handle(AuthenticatedAccount actor, long id,
                                                FeedbackDtos.HandleFeedbackRequest request) {
        long operatorId = BillingAccess.requireAdmin(actor);
        FeedbackRecords.UserFeedback feedback = mapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.FEEDBACK_NOT_FOUND));
        String to = request.status();
        if (RANK.get(to) < RANK.get(feedback.status())) {
            throw new BusinessException(ErrorCode.FEEDBACK_STATE_CONFLICT);
        }
        if (STATUS_CLOSED.equals(feedback.status())) {
            throw new BusinessException(ErrorCode.FEEDBACK_STATE_CONFLICT);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (mapper.applyHandling(id, feedback.status(), to, request.reply().trim(), operatorId,
                now) != 1) {
            // Somebody handled it between the read and this write.
            throw new BusinessException(ErrorCode.FEEDBACK_STATE_CONFLICT);
        }
        FeedbackRecords.UserFeedback handled = mapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.FEEDBACK_NOT_FOUND));
        audit.actorUser(OperationalAuditService.ACTOR_ADMIN, operatorId, TARGET_FEEDBACK, id,
                "USER_FEEDBACK_HANDLED", OperationalAuditService.OUTCOME_SUCCESS,
                "{\"fromStatus\":\"" + feedback.status() + "\",\"toStatus\":\"" + to + "\"}");
        notifications.publish(handled.userId(), NotificationService.CATEGORY_FEEDBACK, "反馈已回复",
                "您提交的反馈（" + handled.createdAt().toLocalDate() + "）已有回复：" + handled.reply(),
                TARGET_FEEDBACK, id, "FEEDBACK_HANDLED:" + id + ":" + to);
        return FeedbackService.response(handled);
    }
}
