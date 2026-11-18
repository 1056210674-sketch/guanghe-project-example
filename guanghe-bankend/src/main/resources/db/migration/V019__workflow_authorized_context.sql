ALTER TABLE business.workflow_plan
    ADD COLUMN authorized_context_snapshot jsonb,
    ADD COLUMN authorized_context_hash char(64),
    ADD COLUMN authorized_context_schema_version integer,
    ADD COLUMN authorized_context_data_gap boolean NOT NULL DEFAULT true,
    ADD COLUMN authorized_context_size_bytes integer GENERATED ALWAYS AS (
        CASE WHEN authorized_context_snapshot IS NULL THEN NULL
             ELSE octet_length(authorized_context_snapshot::text) END
    ) STORED;

ALTER TABLE business.workflow_plan
    ADD CONSTRAINT ck_workflow_plan_authorized_context CHECK (
        (authorized_context_data_gap
            AND authorized_context_snapshot IS NULL
            AND authorized_context_hash IS NULL
            AND authorized_context_schema_version IS NULL
            AND authorized_context_size_bytes IS NULL)
        OR
        (NOT authorized_context_data_gap
            AND jsonb_typeof(authorized_context_snapshot) = 'object'
            AND authorized_context_hash ~ '^[0-9a-f]{64}$'
            AND authorized_context_schema_version = 1
            AND authorized_context_size_bytes BETWEEN 2 AND 196608)
    );

COMMENT ON COLUMN business.workflow_plan.authorized_context_snapshot IS
    'Immutable Java-authorized catalog, conversation memory and task workspace snapshot. Historical NULL means data gap.';
COMMENT ON COLUMN business.workflow_plan.authorized_context_data_gap IS
    'True only for plans created before V019; such non-terminal plans cannot start and must be regenerated.';
