-- Explicit test-only point balance. This is not a product price and is not a Flyway migration.
DO $$
BEGIN
    IF current_database() <> 'zhixiaohe_test' THEN
        RAISE EXCEPTION 'The test001 point account may only be initialized in zhixiaohe_test';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM business.account
         WHERE username_normalized = 'test001'
           AND role = 'USER'
           AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'The fixed active USER test001 must exist before point initialization';
    END IF;
END
$$;

INSERT INTO business.point_account (
    user_id,
    available_points,
    frozen_points,
    version,
    created_at,
    updated_at
)
SELECT
    id,
    10000,
    0,
    1,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM business.account
WHERE username_normalized = 'test001'
  AND role = 'USER'
  AND status = 'ACTIVE'
ON CONFLICT (user_id) DO NOTHING;
