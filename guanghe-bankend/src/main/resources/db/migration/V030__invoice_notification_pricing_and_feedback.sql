-- M8 Stage 2: invoice control plane, in-app notifications, commercial pricing/rule versions,
-- operator bookkeeping support and user feedback.
--
-- Scope notes:
--   * Nothing here sends anything. There is no mail provider, no tax platform and no short-message
--     provider wired up in this environment, and this migration deliberately does not create a
--     table that would let one look configured. business.sms_delivery_log stays what it is — an
--     SMS delivery log for account security — and is NOT reused as a mail or invoice ledger.
--   * The invoice delivery state is modelled so that "reviewed" and "delivered" cannot be confused.
--     APPROVED means an operator approved the application; it does not mean the customer has the
--     invoice. A row can only reach DELIVERED with a delivery receipt recorded on it.
--   * pricing_version governs the commercial rate card, i.e. the points-per-yuan conversion and
--     the recharge tiers. It is not a second balance system and it does not touch
--     business.media_price_snapshot, which records upstream procurement quotes and is unrelated.
--     Until a version is published, the deployment has no production rate: the placeholder in
--     zhixiaohe.billing.points-per-yuan is used and reported as non-production.

-- ---------------------------------------------------------------------------- invoice

CREATE TABLE business.invoice_application (
    id bigint PRIMARY KEY,
    application_no varchar(40) NOT NULL,
    user_id bigint NOT NULL,
    status varchar(16) NOT NULL,
    invoice_type varchar(24) NOT NULL,
    title_type varchar(16) NOT NULL,
    title varchar(200) NOT NULL,
    tax_number varchar(64),
    amount_cents integer NOT NULL,
    delivery_email varchar(255) NOT NULL,
    remark varchar(500),
    reject_reason varchar(500),
    reviewer_id bigint,
    delivery_provider varchar(32),
    delivery_reference varchar(128),
    idempotency_key varchar(100) NOT NULL,
    request_hash char(64) NOT NULL,
    submitted_at timestamptz NOT NULL,
    reviewed_at timestamptz,
    approved_at timestamptz,
    delivered_at timestamptz,
    rejected_at timestamptz,
    cancelled_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_invoice_application_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT fk_invoice_application_reviewer FOREIGN KEY (reviewer_id) REFERENCES business.account (id),
    CONSTRAINT uk_invoice_application_no UNIQUE (application_no),
    -- Namespaced by user at write time, so one account's key can never collide with another's.
    CONSTRAINT uk_invoice_application_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_invoice_application_status CHECK (
        status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'DELIVERED', 'REJECTED', 'CANCELLED')
    ),
    -- Only the two invoice kinds a Chinese business customer can actually be issued are
    -- representable. Anything else would be a value the product cannot deliver.
    CONSTRAINT ck_invoice_application_type CHECK (
        invoice_type IN ('VAT_NORMAL', 'VAT_SPECIAL')
    ),
    -- A special VAT invoice is only issuable to a business, so it always carries a tax number.
    CONSTRAINT ck_invoice_application_title CHECK (
        (title_type = 'PERSONAL' AND invoice_type = 'VAT_NORMAL' AND tax_number IS NULL)
        OR (title_type = 'COMPANY' AND tax_number IS NOT NULL
            AND tax_number ~ '^[0-9A-Z]{15,20}$')
    ),
    CONSTRAINT ck_invoice_application_title_type CHECK (
        title_type IN ('PERSONAL', 'COMPANY')
    ),
    CONSTRAINT ck_invoice_application_amount CHECK (amount_cents > 0),
    CONSTRAINT ck_invoice_application_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_invoice_application_version CHECK (version > 0),
    CONSTRAINT ck_invoice_application_title_text CHECK (length(btrim(title)) > 0),
    -- The delivery address is an email; it is never used as an identity and never carries a name.
    CONSTRAINT ck_invoice_application_email CHECK (delivery_email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    CONSTRAINT ck_invoice_application_reject_reason CHECK (
        reject_reason IS NULL OR length(btrim(reject_reason)) > 0
    ),
    -- Reviewer-authored fields exist exactly when an operator has acted. A submission an operator
    -- never touched cannot claim a reviewer; every state past acceptance must have one. A
    -- withdrawal is the one state reachable both before and after an operator looked at it.
    CONSTRAINT ck_invoice_application_review_binding CHECK (
        (reviewed_at IS NOT NULL) = (reviewer_id IS NOT NULL)
        AND (status NOT IN ('SUBMITTED') OR reviewed_at IS NULL)
        AND (status NOT IN ('UNDER_REVIEW', 'APPROVED', 'DELIVERED', 'REJECTED')
             OR reviewed_at IS NOT NULL)
    ),
    -- Terminal timestamps are pinned to status, so an impossible row is unrepresentable.
    CONSTRAINT ck_invoice_application_lifecycle CHECK (
        (approved_at IS NOT NULL) = (status IN ('APPROVED', 'DELIVERED'))
        AND (delivered_at IS NOT NULL) = (status = 'DELIVERED')
        AND (rejected_at IS NOT NULL) = (status = 'REJECTED')
        AND (cancelled_at IS NOT NULL) = (status = 'CANCELLED')
    ),
    -- The invariant this migration exists for: DELIVERED requires a delivery receipt, and
    -- APPROVED does not. Internal review passing can never be mistaken for the customer holding
    -- an invoice.
    CONSTRAINT ck_invoice_application_delivery_receipt CHECK (
        status <> 'DELIVERED'
        OR (delivery_provider IS NOT NULL AND delivery_reference IS NOT NULL
            AND delivered_at IS NOT NULL)
    ),
    -- A delivery attempt that did not complete leaves no receipt behind.
    CONSTRAINT ck_invoice_application_delivery_binding CHECK (
        (delivery_provider IS NULL) = (delivery_reference IS NULL)
    ),
    CONSTRAINT ck_invoice_application_reject_reason_binding CHECK (
        (status = 'REJECTED') = (reject_reason IS NOT NULL)
    )
);

CREATE INDEX idx_invoice_application_user
    ON business.invoice_application (user_id, created_at DESC, id DESC);
CREATE INDEX idx_invoice_application_status
    ON business.invoice_application (status, submitted_at ASC, id ASC);

COMMENT ON TABLE business.invoice_application IS
    'Invoice application and acceptance control plane. APPROVED records that internal review '
    'passed; only DELIVERED, which requires a stored delivery receipt, records that the customer '
    'has the invoice. No tax platform is integrated and no mail is sent by this build.';

CREATE TABLE business.invoice_application_event (
    id bigint PRIMARY KEY,
    application_id bigint NOT NULL,
    user_id bigint NOT NULL,
    source_event_id uuid NOT NULL,
    event_type varchar(32) NOT NULL,
    from_status varchar(16),
    to_status varchar(16) NOT NULL,
    source varchar(32) NOT NULL,
    operator_id bigint,
    error_code varchar(64),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_invoice_application_event_application
        FOREIGN KEY (application_id) REFERENCES business.invoice_application (id),
    CONSTRAINT fk_invoice_application_event_user
        FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT fk_invoice_application_event_operator
        FOREIGN KEY (operator_id) REFERENCES business.account (id),
    CONSTRAINT uk_invoice_application_event_source UNIQUE (application_id, source_event_id),
    CONSTRAINT ck_invoice_application_event_type CHECK (event_type ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    CONSTRAINT ck_invoice_application_event_source CHECK (
        source IN ('BROWSER', 'OPERATOR', 'DELIVERY_PROVIDER', 'SYSTEM')
    ),
    CONSTRAINT ck_invoice_application_event_from_status CHECK (
        from_status IS NULL
        OR from_status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'DELIVERED', 'REJECTED', 'CANCELLED')
    ),
    CONSTRAINT ck_invoice_application_event_to_status CHECK (
        to_status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'DELIVERED', 'REJECTED', 'CANCELLED')
    ),
    CONSTRAINT ck_invoice_application_event_error CHECK (
        error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    CONSTRAINT ck_invoice_application_event_details CHECK (
        jsonb_typeof(details) = 'object' AND octet_length(details::text) <= 16384
    )
);

CREATE INDEX idx_invoice_application_event_application
    ON business.invoice_application_event (application_id, created_at ASC, id ASC);

COMMENT ON TABLE business.invoice_application_event IS
    'Append-only invoice state transitions. A refused delivery attempt is recorded here so the '
    'application can be retried once a delivery provider exists, without inventing a success.';

-- ---------------------------------------------------------------------------- notifications

CREATE TABLE business.user_notification (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    category varchar(32) NOT NULL,
    title varchar(120) NOT NULL,
    body varchar(1000) NOT NULL,
    target_type varchar(32),
    target_id bigint,
    -- Deterministic key describing the fact this notification announces. The unique index below is
    -- what makes notification creation idempotent: replaying the event cannot produce a second row.
    dedup_key varchar(160) NOT NULL,
    read_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_user_notification_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT uk_user_notification_dedup UNIQUE (user_id, dedup_key),
    CONSTRAINT ck_user_notification_category CHECK (
        category IN ('RECHARGE', 'POINTS', 'INVOICE', 'FEEDBACK', 'SYSTEM')
    ),
    CONSTRAINT ck_user_notification_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_user_notification_body CHECK (length(btrim(body)) > 0),
    -- A deep link is a resource kind plus an id, never a URL: the Web client builds the route.
    CONSTRAINT ck_user_notification_target CHECK (
        (target_type IS NULL) = (target_id IS NULL)
        AND (target_type IS NULL OR target_type ~ '^[A-Z][A-Z0-9_]{0,31}$')
    ),
    CONSTRAINT ck_user_notification_dedup CHECK (length(btrim(dedup_key)) > 0)
);

-- Serves the notification centre and its unread badge. Partial, because the badge only ever counts
-- unread rows.
CREATE INDEX idx_user_notification_inbox
    ON business.user_notification (user_id, created_at DESC, id DESC);
CREATE INDEX idx_user_notification_unread
    ON business.user_notification (user_id, id) WHERE read_at IS NULL;

COMMENT ON TABLE business.user_notification IS
    'In-app notification inbox. Rows are created inside the transaction of the business event they '
    'announce, so a notification can never exist for something that did not happen. read_at is '
    'written once; marking a notification read again is a no-op, not an error.';

-- ---------------------------------------------------------------------------- pricing and rules

CREATE TABLE business.pricing_version (
    id bigint PRIMARY KEY,
    version_no integer NOT NULL,
    status varchar(16) NOT NULL,
    points_per_yuan integer NOT NULL,
    min_recharge_amount_cents integer NOT NULL,
    max_recharge_amount_cents integer NOT NULL,
    note varchar(500),
    created_by bigint NOT NULL,
    published_by bigint,
    published_at timestamptz,
    retired_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_pricing_version_creator FOREIGN KEY (created_by) REFERENCES business.account (id),
    CONSTRAINT fk_pricing_version_publisher FOREIGN KEY (published_by) REFERENCES business.account (id),
    CONSTRAINT uk_pricing_version_no UNIQUE (version_no),
    CONSTRAINT ck_pricing_version_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_pricing_version_rate CHECK (points_per_yuan > 0),
    CONSTRAINT ck_pricing_version_bounds CHECK (
        min_recharge_amount_cents > 0
        AND max_recharge_amount_cents >= min_recharge_amount_cents
    ),
    CONSTRAINT ck_pricing_version_version CHECK (version > 0),
    CONSTRAINT ck_pricing_version_publish_binding CHECK (
        (published_at IS NOT NULL) = (published_by IS NOT NULL)
        AND (status = 'DRAFT') = (published_at IS NULL)
    ),
    CONSTRAINT ck_pricing_version_retire_binding CHECK (
        (retired_at IS NOT NULL) = (status = 'RETIRED')
    )
);

-- At most one version may be ACTIVE. A partial unique index is the strongest available expression
-- of "the deployment has exactly one production rate"; without it two operators could publish
-- concurrently and orders would silently use whichever row the query happened to return first.
CREATE UNIQUE INDEX uk_pricing_version_single_active
    ON business.pricing_version (status) WHERE status = 'ACTIVE';

CREATE INDEX idx_pricing_version_status
    ON business.pricing_version (status, version_no DESC);

COMMENT ON TABLE business.pricing_version IS
    'Versioned commercial rate card. While no row is ACTIVE the deployment has no production rate '
    'and the placeholder zhixiaohe.billing.points-per-yuan applies; recharge reports that it is '
    'running on a non-production rate rather than presenting it as settled pricing.';

CREATE TABLE business.pricing_version_tier (
    id bigint PRIMARY KEY,
    pricing_version_id bigint NOT NULL,
    amount_cents integer NOT NULL,
    gift_points integer NOT NULL DEFAULT 0,
    label varchar(64) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_pricing_version_tier_version
        FOREIGN KEY (pricing_version_id) REFERENCES business.pricing_version (id),
    CONSTRAINT uk_pricing_version_tier_amount UNIQUE (pricing_version_id, amount_cents),
    CONSTRAINT ck_pricing_version_tier_amount CHECK (amount_cents > 0),
    CONSTRAINT ck_pricing_version_tier_gift CHECK (gift_points >= 0),
    CONSTRAINT ck_pricing_version_tier_label CHECK (length(btrim(label)) > 0)
);

CREATE INDEX idx_pricing_version_tier_version
    ON business.pricing_version_tier (pricing_version_id, sort_order ASC, amount_cents ASC);

COMMENT ON TABLE business.pricing_version_tier IS
    'Recharge tiers belonging to a pricing version. gift_points is a promotional bonus credited '
    'through the same ledger entry as the paid points; it is never a separate balance.';

CREATE TABLE business.commercial_rate_limit (
    id bigint PRIMARY KEY,
    action varchar(48) NOT NULL,
    window_seconds integer NOT NULL,
    max_attempts integer NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    updated_by bigint NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_commercial_rate_limit_operator FOREIGN KEY (updated_by) REFERENCES business.account (id),
    CONSTRAINT uk_commercial_rate_limit_action UNIQUE (action),
    CONSTRAINT ck_commercial_rate_limit_window CHECK (window_seconds BETWEEN 1 AND 86400),
    CONSTRAINT ck_commercial_rate_limit_attempts CHECK (max_attempts BETWEEN 1 AND 100000),
    CONSTRAINT ck_commercial_rate_limit_version CHECK (version > 0)
);

COMMENT ON TABLE business.commercial_rate_limit IS
    'Operator-tunable throttle for commercial write operations. Attempts are counted from the real '
    'rows the operation would have created, so the limit reflects what actually happened rather '
    'than a parallel counter that can drift. Absent a row, the built-in default applies and the '
    'administrator page reports the default as such.';

-- ---------------------------------------------------------------------------- user feedback

CREATE TABLE business.user_feedback (
    id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    category varchar(24) NOT NULL,
    content varchar(2000) NOT NULL,
    contact varchar(128),
    status varchar(16) NOT NULL,
    reply varchar(2000),
    handled_by bigint,
    handled_at timestamptz,
    idempotency_key varchar(100) NOT NULL,
    request_hash char(64) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_user_feedback_user FOREIGN KEY (user_id) REFERENCES business.account (id),
    CONSTRAINT fk_user_feedback_handler FOREIGN KEY (handled_by) REFERENCES business.account (id),
    CONSTRAINT uk_user_feedback_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_user_feedback_category CHECK (
        category IN ('PRODUCT', 'BILLING', 'BUG', 'CONTENT', 'OTHER')
    ),
    CONSTRAINT ck_user_feedback_status CHECK (
        status IN ('SUBMITTED', 'ACKNOWLEDGED', 'RESOLVED', 'CLOSED')
    ),
    CONSTRAINT ck_user_feedback_content CHECK (length(btrim(content)) > 0),
    CONSTRAINT ck_user_feedback_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_user_feedback_version CHECK (version > 0),
    -- A status beyond SUBMITTED always has an operator behind it, and a reply always has a handler.
    CONSTRAINT ck_user_feedback_handling CHECK (
        (status = 'SUBMITTED') = (handled_by IS NULL)
        AND (handled_at IS NOT NULL) = (handled_by IS NOT NULL)
        AND (reply IS NOT NULL) = (handled_by IS NOT NULL)
    )
);

CREATE INDEX idx_user_feedback_user
    ON business.user_feedback (user_id, created_at DESC, id DESC);
CREATE INDEX idx_user_feedback_status
    ON business.user_feedback (status, created_at ASC, id ASC);

COMMENT ON TABLE business.user_feedback IS
    'User-submitted feedback and the operator response to it. Submission is idempotent on the '
    'namespaced Idempotency-Key, exactly like recharge and invoice submission.';

-- ---------------------------------------------------------------------------- recharge linkage

-- Orders snapshot which rate card produced them. Null means the order was created on the
-- placeholder property rate, which is what every order in this environment is, and which the
-- administrator surface must show as non-production rather than as settled pricing.
ALTER TABLE business.recharge_order
    ADD COLUMN pricing_version_id bigint;

ALTER TABLE business.recharge_order
    ADD CONSTRAINT fk_recharge_order_pricing_version
        FOREIGN KEY (pricing_version_id) REFERENCES business.pricing_version (id);

-- Promotional points included in "points". Kept separate from the paid points so an operator can
-- tell revenue from give-away, while the ledger keeps crediting the two together in one entry.
ALTER TABLE business.recharge_order
    ADD COLUMN gift_points integer NOT NULL DEFAULT 0;

ALTER TABLE business.recharge_order
    ADD CONSTRAINT ck_recharge_order_gift CHECK (gift_points >= 0 AND gift_points < points);

COMMENT ON COLUMN business.recharge_order.pricing_version_id IS
    'Pricing version that produced this order, or null when the placeholder property rate was used.';
COMMENT ON COLUMN business.recharge_order.gift_points IS
    'Promotional points included in points. points - gift_points is what the payer bought.';
