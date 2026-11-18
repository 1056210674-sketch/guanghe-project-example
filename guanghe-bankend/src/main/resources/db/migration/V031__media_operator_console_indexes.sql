-- M8 Stage 3: cross-account indexes for the operator media console.
--
-- Scope notes:
--   * Indexes only. The console reads tables that already exist (media_resource, media_submission,
--     media_submission_event, media_callback_event, media_provider_sync_state) and this migration
--     adds no table, no column and no constraint, so it cannot change what the application stores.
--   * Every media index so far is scoped to one tenant: idx_media_submission_user_created leads with
--     user_id and idx_media_submission_poll leads with next_poll_at. The operator listings cross
--     accounts and order by time, so without these they would sort the whole table on every page.
--     These are the three access paths the console actually uses; nothing is added speculatively.
--   * idx_media_callback_recovery already carries the (status IN ('RECEIVED','FAILED')) predicate for
--     the retry worker, but it is ordered by updated_at. The console orders receipts by arrival, and
--     updated_at moves on every retry — reusing it would neither satisfy the ORDER BY nor keep rows
--     from reordering under an operator mid-page. The third index keeps that predicate and changes
--     only the ordering key.

CREATE INDEX idx_media_submission_admin_created
    ON business.media_submission (created_at DESC, id DESC);
CREATE INDEX idx_media_submission_admin_status_created
    ON business.media_submission (status, created_at DESC, id DESC);
CREATE INDEX idx_media_callback_admin_received
    ON business.media_callback_event (received_at DESC, id DESC)
    WHERE status IN ('RECEIVED','FAILED');
