-- Public-release fixture hashes were regenerated; they are not shared-environment credentials.
-- Explicit test-only initializer. This resource is not part of the production Flyway locations.
DO $$
BEGIN
    IF current_database() <> 'zhixiaohe_test' THEN
        RAISE EXCEPTION 'Fixed test accounts may only be initialized in zhixiaohe_test';
    END IF;
END
$$;

INSERT INTO business.account (
    id,
    username,
    username_normalized,
    phone_e164,
    password_hash,
    password_algorithm,
    token_version,
    role,
    status,
    display_name,
    email,
    created_at,
    updated_at
) VALUES
    (
        362387869790209,
        'Guanghe888',
        'guanghe888',
        '+8610000000001',
        '515d03cc2a3b132f848c3a66dc76edac',
        'MD5_FIXED_SALT_V1',
        1,
        'ADMIN',
        'ACTIVE',
        NULL,
        NULL,
        TIMESTAMPTZ '2026-01-02 00:00:00+00',
        TIMESTAMPTZ '2026-01-02 00:00:00+00'
    ),
    (
        362387869790210,
        'test001',
        'test001',
        '+8610000000002',
        'db41ae212deca56f2e167ba887d1de33',
        'MD5_FIXED_SALT_V1',
        1,
        'USER',
        'ACTIVE',
        NULL,
        NULL,
        TIMESTAMPTZ '2026-01-02 00:00:00+00',
        TIMESTAMPTZ '2026-01-02 00:00:00+00'
    )
ON CONFLICT (username_normalized) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM business.account
         WHERE id = 362387869790209
           AND username = 'Guanghe888'
           AND username_normalized = 'guanghe888'
           AND phone_e164 = '+8610000000001'
           AND password_hash = '515d03cc2a3b132f848c3a66dc76edac'
           AND password_algorithm = 'MD5_FIXED_SALT_V1'
           AND role = 'ADMIN'
           AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Fixed ADMIN test account conflicts with the audited test initializer';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM business.account
         WHERE id = 362387869790210
           AND username = 'test001'
           AND username_normalized = 'test001'
           AND phone_e164 = '+8610000000002'
           AND password_hash = 'db41ae212deca56f2e167ba887d1de33'
           AND password_algorithm = 'MD5_FIXED_SALT_V1'
           AND role = 'USER'
           AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Fixed USER test account conflicts with the audited test initializer';
    END IF;
END
$$;
