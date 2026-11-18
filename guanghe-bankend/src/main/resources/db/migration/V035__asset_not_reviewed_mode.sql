-- The user may explicitly choose to make assets available without content review.
-- Preserve this as a separate audit state; it must never be recorded as APPROVED.
ALTER TABLE business.asset
    DROP CONSTRAINT ck_asset_safety_status,
    DROP CONSTRAINT ck_asset_ready_safety,
    ADD CONSTRAINT ck_asset_safety_status CHECK (
        safety_status IN ('PENDING', 'APPROVED', 'TEST_BYPASS', 'NOT_REVIEWED', 'REJECTED', 'UNAVAILABLE')
    ),
    ADD CONSTRAINT ck_asset_ready_safety CHECK (
        status <> 'READY' OR safety_status IN ('APPROVED', 'TEST_BYPASS', 'NOT_REVIEWED')
    );

COMMENT ON COLUMN business.asset.safety_status IS
    'APPROVED = reviewed and approved; TEST_BYPASS = test-only bypass; NOT_REVIEWED = explicitly available without review.';
