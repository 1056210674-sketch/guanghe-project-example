package com.guanghe.backend.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.access.AdminAccess;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The back office's read-only view of the media catalogue and its submissions.
 *
 * <p>Three properties are deliberate and worth stating where they can be reviewed:
 *
 * <p><b>It is not gated by {@code zhixiaohe.media.enabled}.</b> That switch governs whether tenants
 * may spend points on new submissions. Reconciling the submissions already in flight is precisely
 * what an operator has to do when the switch is off, so a console that disappeared along with the
 * tenant feature would take away the tool at the moment it is needed. Being ungated is also why the
 * mapper arrives through an {@link ObjectProvider}; see the constructor.
 *
 * <p><b>It never calls the procurement upstream.</b> Every method here reads our own tables. The
 * provider wallet balance is consequently reported as {@code null} rather than fetched: a page load
 * must not turn an upstream outage into an operator-console outage, and a read-only surface has no
 * business making outbound writes or metered calls at all.
 *
 * <p><b>It reads across accounts on purpose, and hides nothing about the price.</b> The commercial
 * console already crosses accounts, and the operator gate is the same one ({@link AdminAccess}). A
 * tenant never sees the purchase price or the provider identifiers; an operator reconciling a
 * rejected submission cannot work without them. What neither sees is a credential or a raw payload,
 * which is why nothing here reads {@code media_resource.raw_json}, the callback body or its digest.
 */
@Service
public class MediaAdminService {
    private static final Set<String> RESOURCE_STATUSES = Set.of("ACTIVE", "INACTIVE");
    /**
     * The receipts that still want a human: something arrived and has not been applied.
     *
     * <p>A {@code PROCESSED} receipt is a closed loop, and this tab exists to surface the loops that
     * are not closed. By-id lookup still resolves any receipt an operator was pointed at, so a
     * processed one is reachable when there is a reason to look at it.
     */
    private static final List<String> CALLBACK_ATTENTION_STATUSES = List.of("RECEIVED", "FAILED");
    private static final Map<String, List<String>> SUBMISSION_GROUPS = Map.of(
            "PENDING", MediaSubmissionService.PENDING_STATUSES,
            "EXCEPTION", MediaSubmissionService.EXCEPTION_STATUSES);
    private static final int MAX_SEARCH_LENGTH = 200;
    private static final int DECIMAL_SCALE = 4;

    private final MediaMapper media;
    private final ObjectMapper json;
    private final MediaProperties properties;

    /**
     * The mapper is optional for the same reason the diagnostics console's is: this class carries no
     * feature switch, so it is constructed in every context, including ones that run with the
     * database autoconfiguration excluded and therefore have no mapper bean to inject. Failing the
     * whole context there would take down every unrelated bean that shares it. The cost is that a
     * missing mapper surfaces per request instead of at startup, which is acceptable because there is
     * nothing to read without tables anyway — {@link #media()} then answers 500.
     */
    @Autowired
    public MediaAdminService(ObjectProvider<MediaMapper> media, ObjectMapper json, MediaProperties properties) {
        this.media = media.getIfAvailable();
        this.json = json;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public MediaAdminDtos.MediaOverview overview(AuthenticatedAccount account) {
        AdminAccess.requireAdmin(account);
        return new MediaAdminDtos.MediaOverview(
                media().countResources(null, null, null, "ACTIVE", null, null,
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minus(properties.getResourceMaxAge()),
                        properties.getProviderCode(), "1"),
                media().countSubmissionsForAdmin(MediaSubmissionService.PENDING_STATUSES),
                media().countSubmissionsForAdmin(MediaSubmissionService.EXCEPTION_STATUSES),
                null);
    }

    @Transactional(readOnly = true)
    public PageResponse<MediaAdminDtos.AdminResource> resources(AuthenticatedAccount account, String search,
            String modelId, String type, String status, int page, int size) {
        AdminAccess.requireAdmin(account);
        long offset = (long) (page - 1) * size;
        String normalizedStatus = status == null || status.isBlank()
                ? null : enumValue(status, RESOURCE_STATUSES);
        String normalizedModel = optionalToken(modelId, 32);
        String normalizedType = optionalToken(type, 64);
        String normalizedSearch = searchTerm(search);
        List<MediaAdminDtos.AdminResource> items = media()
                .listResources(normalizedSearch, normalizedModel, normalizedType, normalizedStatus,
                        null, null, null, null, null, size, offset)
                .stream().map(this::resource).toList();
        long total = media().countResources(normalizedSearch, normalizedModel, normalizedType,
                normalizedStatus, null, null, null, null, null);
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public MediaAdminDtos.AdminResource resource(AuthenticatedAccount account, long resourceId) {
        AdminAccess.requireAdmin(account);
        return resource(media().findResource(resourceId).orElseThrow(this::notFound));
    }

    @Transactional(readOnly = true)
    public PageResponse<MediaAdminDtos.AdminSubmissionSummary> submissions(AuthenticatedAccount account,
            String status, String group, int page, int size) {
        AdminAccess.requireAdmin(account);
        long offset = (long) (page - 1) * size;
        // Two filters that can disagree are rejected rather than resolved by precedence: silently
        // preferring one would hide the submissions the operator asked about with the other.
        if (status != null && group != null) throw validation();
        List<String> statuses = status != null ? List.of(enumValue(status, MediaSubmissionService.STATUSES))
                : group != null ? groupStatuses(group) : null;
        List<MediaAdminDtos.AdminSubmissionSummary> items = media()
                .listSubmissionsForAdmin(statuses, size, offset)
                .stream().map(this::submissionSummary).toList();
        return PageResponse.of(items, page, size, media().countSubmissionsForAdmin(statuses));
    }

    @Transactional(readOnly = true)
    public MediaAdminDtos.AdminSubmissionDetail submission(AuthenticatedAccount account, long submissionId) {
        AdminAccess.requireAdmin(account);
        MediaRecords.Submission row = media().findSubmissionForAdmin(submissionId).orElseThrow(this::notFound);
        List<MediaDtos.SubmissionEvent> events = media().eventsForAdmin(submissionId).stream()
                .map(event -> new MediaDtos.SubmissionEvent(Long.toString(event.id()), event.sequenceNo(),
                        event.eventType(), event.fromStatus(), event.toStatus(), event.stableErrorCode(),
                        event.createdAt())).toList();
        return new MediaAdminDtos.AdminSubmissionDetail(Long.toString(row.id()), Long.toString(row.batchId()),
                Long.toString(row.taskId()), Long.toString(row.userId()),
                Long.toString(row.mediaResourceId()), row.mediaName(), row.providerCode(),
                row.upstreamModelId(), row.upstreamResourceId(), row.customerOrderId(), row.upstreamOrderId(),
                row.upstreamOrderNo(), row.upstreamStatus(), row.status(), row.submissionState(),
                row.publishedUrl(), MediaPublishedLinks.fromStored(json, row.publishedUrlsJson(), row.publishedUrl()),
                row.stableErrorCode(), decimal(row.upstreamPrice()),
                decimal(row.markupRate()), row.saleAmountCents(), row.chargedPoints(), row.remark(),
                events, row.submitAttemptedAt(), row.nextPollAt(), row.publishedAt(), row.rejectedAt(),
                row.createdAt(), row.updatedAt());
    }

    @Transactional(readOnly = true)
    public PageResponse<MediaAdminDtos.AdminCallback> callbacks(AuthenticatedAccount account, int page,
            int size) {
        AdminAccess.requireAdmin(account);
        long offset = (long) (page - 1) * size;
        List<MediaAdminDtos.AdminCallback> items = media()
                .listCallbacksForAdmin(CALLBACK_ATTENTION_STATUSES, size, offset)
                .stream().map(this::callback).toList();
        return PageResponse.of(items, page, size,
                media().countCallbacksForAdmin(CALLBACK_ATTENTION_STATUSES));
    }

    @Transactional(readOnly = true)
    public MediaAdminDtos.AdminCallback callback(AuthenticatedAccount account, long callbackId) {
        AdminAccess.requireAdmin(account);
        return callback(media().findCallbackForAdmin(callbackId).orElseThrow(this::notFound));
    }

    @Transactional(readOnly = true)
    public List<MediaAdminDtos.AdminSyncState> syncStates(AuthenticatedAccount account) {
        AdminAccess.requireAdmin(account);
        return media().listSyncStates().stream()
                .map(row -> new MediaAdminDtos.AdminSyncState(row.providerCode(), row.upstreamModelId(),
                        row.status(), row.lastAttemptedAt(), row.lastSucceededAt(), row.lastErrorCode(),
                        row.lastResourceCount(), row.version(), row.updatedAt()))
                .toList();
    }

    private MediaAdminDtos.AdminResource resource(MediaRecords.Resource row) {
        // The policy's current price for this resource, computed here so the browser never has to do
        // pricing arithmetic of its own. Nothing is persisted by showing it: a price is written only
        // when a tenant requests a quote.
        int referenceAmountCents = MediaSubmissionService.saleAmountCents(row.upstreamPrice());
        return new MediaAdminDtos.AdminResource(Long.toString(row.id()), row.providerCode(),
                row.upstreamModelId(), row.upstreamResourceId(), row.name(), row.status(),
                row.resourceType(), row.channelCategory(), row.region(), row.estimatedDays(),
                read(row.capabilitiesJson()), decimal(row.upstreamPrice()),
                decimal(MediaSubmissionService.MARKUP_RATE), referenceAmountCents, referenceAmountCents,
                row.lastSeenAt(), row.syncedAt(), row.version());
    }

    private MediaAdminDtos.AdminSubmissionSummary submissionSummary(MediaRecords.Submission row) {
        return new MediaAdminDtos.AdminSubmissionSummary(Long.toString(row.id()), Long.toString(row.batchId()),
                Long.toString(row.taskId()), Long.toString(row.userId()),
                Long.toString(row.mediaResourceId()), row.mediaName(), row.providerCode(),
                row.upstreamModelId(), row.upstreamResourceId(), row.customerOrderId(), row.upstreamOrderId(),
                row.upstreamOrderNo(), row.upstreamStatus(), row.status(), row.submissionState(),
                row.publishedUrl(), MediaPublishedLinks.fromStored(json, row.publishedUrlsJson(), row.publishedUrl()),
                row.stableErrorCode(), decimal(row.upstreamPrice()),
                decimal(row.markupRate()), row.saleAmountCents(), row.chargedPoints(), row.remark(),
                row.submitAttemptedAt(), row.nextPollAt(), row.publishedAt(), row.rejectedAt(),
                row.createdAt(), row.updatedAt());
    }

    private MediaAdminDtos.AdminCallback callback(MediaRecords.Callback row) {
        return new MediaAdminDtos.AdminCallback(Long.toString(row.id()), row.providerCode(),
                row.customerOrderId(), row.upstreamModelId(), row.upstreamResourceId(), row.eventType(),
                row.status(), row.attempts(), row.stableErrorCode(), row.receivedAt(), row.processedAt(),
                row.updatedAt());
    }

    private List<String> groupStatuses(String group) {
        List<String> statuses = SUBMISSION_GROUPS.get(group.strip().toUpperCase(Locale.ROOT));
        if (statuses == null) throw validation();
        return statuses;
    }

    /** Exact decimal text: a purchase price is {@code numeric(12,4)} yuan and must not become a float. */
    private String decimal(BigDecimal value) {
        return value.setScale(DECIMAL_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private String searchTerm(String search) {
        if (search == null || search.isBlank()) return null;
        String normalized = search.strip();
        if (normalized.length() > MAX_SEARCH_LENGTH) throw validation();
        return "%" + normalized + "%";
    }

    private String enumValue(String value, Set<String> allowed) {
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw validation();
        return normalized;
    }

    private String optionalToken(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max || !normalized.matches("[A-Za-z0-9._:-]+")) throw validation();
        return normalized;
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * The console is only useful with the media tables, so a missing mapper is a deployment fault
     * rather than something an operator can act on: 500, not 503.
     */
    private MediaMapper media() {
        if (media == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        return media;
    }

    private BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    private BusinessException validation() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
}
