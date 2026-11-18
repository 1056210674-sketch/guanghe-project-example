-- M8 Stage 1 follow-up: two columns the control plane needs to be honest about state.
--
--   * recharge_order.return_context — where the payer was when they ran out of points. The product
--     requirement is that a user who recharges in order to resume a draft or a plan can be taken
--     back to it, including on a later visit after paying out-of-band. That only works if the
--     context is stored server-side rather than held in a browser tab.
--
--   * manual_point_adjustment.confirmation_expires_at — the two-step confirmation window is frozen
--     onto the row. Deriving it from created_at plus a mutable property would let a configuration
--     change silently extend or void a token that an operator is already holding.
--
-- Both columns are additive and nullable-tolerant; no existing row is invalidated.

ALTER TABLE business.recharge_order
    ADD COLUMN return_context varchar(200);

-- Opaque, self-describing client context: a resource kind and an existing id. Constrained so a
-- caller cannot smuggle a path, a query string, a URL or free text into a field the Web client
-- will navigate to.
ALTER TABLE business.recharge_order
    ADD CONSTRAINT ck_recharge_order_return_context CHECK (
        return_context IS NULL
        OR return_context ~ '^[A-Z][A-Z0-9_]{0,31}:[1-9][0-9]{0,18}$'
    );

ALTER TABLE business.manual_point_adjustment
    ADD COLUMN confirmation_expires_at timestamptz;

-- Historical rows (there are none in a clean environment) get a conservative window so the column
-- can be made NOT NULL without inventing an unbounded one.
UPDATE business.manual_point_adjustment
    SET confirmation_expires_at = created_at + interval '10 minutes'
    WHERE confirmation_expires_at IS NULL;

ALTER TABLE business.manual_point_adjustment
    ALTER COLUMN confirmation_expires_at SET NOT NULL;

-- An applied adjustment is final; its confirmation window is no longer meaningful, but it must
-- still not precede the moment the row was created.
ALTER TABLE business.manual_point_adjustment
    ADD CONSTRAINT ck_manual_point_adjustment_confirmation_expiry CHECK (
        confirmation_expires_at > created_at
    );

COMMENT ON COLUMN business.recharge_order.return_context IS
    'Opaque <KIND>:<id> context supplied by the client so a payer can be returned to the draft or '
    'plan that ran out of points. Never a URL, a path or free text.';
COMMENT ON COLUMN business.manual_point_adjustment.confirmation_expires_at IS
    'Frozen end of the two-step confirmation window. Set when the request is created and never '
    'recomputed, so a configuration change cannot revive a token.';
