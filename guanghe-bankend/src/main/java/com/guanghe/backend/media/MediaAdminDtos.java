package com.guanghe.backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The operator-facing media contract.
 *
 * <p>It is deliberately a separate shape from {@link MediaDtos}: the user-facing media DTOs hide the
 * purchase price, the provider identifiers and the upstream order numbers, because a tenant has no
 * business seeing the margin or the supply relationship. An operator troubleshooting a stuck
 * submission needs exactly those fields, so the two audiences cannot share one schema — but the
 * operator schema still stops short of anything that identifies a credential or reproduces a raw
 * payload. Concretely, no field here carries the provider app key, the outbound request body, the
 * received callback body, or {@code media_resource.raw_json}; and the callback receipt exposes its
 * own id instead of the payload digest.
 *
 * <p>Money keeps the codebase-wide convention of integer cents, with one exception: the purchase
 * price is stored as {@code numeric(12,4)} yuan and converting it to cents would silently round it.
 * {@code upstreamPrice} and {@code markupRate} are therefore exact decimal strings, and any amount a
 * screen shows is computed here rather than by the browser.
 */
public final class MediaAdminDtos {
    private MediaAdminDtos() { }

    /**
     * The console header's counters.
     *
     * <p>{@code walletBalanceCents} is always {@code null} in this version: the upstream balance is a
     * live call to the provider, and a read-only console that fans out to the procurement upstream
     * just because a page was opened would make an outage on our side into an outage on theirs. The
     * field exists so the browser contract does not have to change when the balance is eventually
     * read from a provider sync.
     */
    public record MediaOverview(long activeResources, long pendingSubmissions,
                                long exceptionSubmissions, Long walletBalanceCents) { }

    /**
     * A catalogued upstream resource as the back office sees it.
     *
     * <p>{@code referenceSaleAmountCents} and {@code referenceChargedPoints} are the price the pricing
     * policy produces for this resource right now, not a stored quotation: nothing is persisted until
     * a tenant requests a quote, and the price a given submission actually paid lives on that
     * submission. Showing the reference price is what lets an operator answer "what would this cost?"
     * without impersonating a tenant.
     */
    public record AdminResource(String id, String providerCode, String upstreamModelId,
                                String upstreamResourceId, String name, String status,
                                String resourceType, String channelCategory, String region,
                                Integer estimatedDays, JsonNode capabilities, String upstreamPrice,
                                String markupRate, int referenceSaleAmountCents,
                                int referenceChargedPoints, OffsetDateTime lastSeenAt,
                                OffsetDateTime syncedAt, int version) { }

    public record AdminSubmissionSummary(String id, String batchId, String taskId, String userId,
                                         String mediaResourceId, String mediaName, String providerCode,
                                         String upstreamModelId, String upstreamResourceId,
                                         String customerOrderId, String upstreamOrderId,
                                         String upstreamOrderNo, String upstreamStatus, String status,
                                         String submissionState, String publishedUrl,
                                         List<String> publishedUrls,
                                         String stableErrorCode, String upstreamPrice,
                                         String markupRate, int saleAmountCents, int chargedPoints,
                                         String remark, OffsetDateTime submitAttemptedAt,
                                         OffsetDateTime nextPollAt, OffsetDateTime publishedAt,
                                         OffsetDateTime rejectedAt, OffsetDateTime createdAt,
                                         OffsetDateTime updatedAt) { }

    /**
     * The summary plus the submission's own event trail.
     *
     * <p>The trail reuses the tenant-facing {@link MediaDtos.SubmissionEvent}: it carries no account
     * or payload data, so there is nothing to redact and no reason to define a second identical shape.
     */
    public record AdminSubmissionDetail(String id, String batchId, String taskId, String userId,
                                        String mediaResourceId, String mediaName, String providerCode,
                                        String upstreamModelId, String upstreamResourceId,
                                        String customerOrderId, String upstreamOrderId,
                                        String upstreamOrderNo, String upstreamStatus, String status,
                                        String submissionState, String publishedUrl,
                                        List<String> publishedUrls,
                                        String stableErrorCode, String upstreamPrice,
                                        String markupRate, int saleAmountCents, int chargedPoints,
                                        String remark, List<MediaDtos.SubmissionEvent> events,
                                        OffsetDateTime submitAttemptedAt, OffsetDateTime nextPollAt,
                                        OffsetDateTime publishedAt, OffsetDateTime rejectedAt,
                                        OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    /**
     * A received upstream callback, without the digest of the body it carried.
     *
     * <p>An operator needs to know that a receipt arrived, what it claimed and whether we managed to
     * apply it. The raw body is intentionally unavailable on this surface, and so is its hash:
     * publishing the hash of an unredacted payload invites re-identification attempts against a
     * value we deliberately do not show. The receipt id is the handle operators get instead — the
     * same id the request log will name.
     */
    public record AdminCallback(String id, String providerCode, String customerOrderId,
                                String upstreamModelId, String upstreamResourceId, String eventType,
                                String status, int attempts, String stableErrorCode,
                                OffsetDateTime receivedAt, OffsetDateTime processedAt,
                                OffsetDateTime updatedAt) { }

    /**
     * The last known outcome of syncing one upstream model's resource catalogue.
     *
     * <p>This is the one row per (provider, model) that the scheduler actually maintains. The console
     * shows it as-is: there is no batch, operator or per-run counters because nothing records them,
     * and a screen that invented them would report work that never happened.
     */
    public record AdminSyncState(String providerCode, String upstreamModelId, String status,
                                 OffsetDateTime lastAttemptedAt, OffsetDateTime lastSucceededAt,
                                 String lastErrorCode, int lastResourceCount, int version,
                                 OffsetDateTime updatedAt) { }
}
