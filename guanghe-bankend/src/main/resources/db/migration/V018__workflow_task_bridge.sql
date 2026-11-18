ALTER TABLE business.workflow_plan
    ADD COLUMN lease_recovery_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_workflow_plan_lease_recovery_count CHECK (
        lease_recovery_count BETWEEN 0 AND 10
    );

ALTER TABLE business.workflow_plan_step
    ADD COLUMN authorized_input_hash char(64),
    ADD COLUMN authorized_draft_expires_at timestamptz,
    ADD COLUMN activation_event_id uuid,
    ADD COLUMN activation_request_hash char(64),
    ADD COLUMN task_result_ref varchar(256);

UPDATE business.workflow_plan_step s
SET authorized_input_hash = d.input_hash,
    authorized_draft_expires_at = d.expires_at
FROM business.task_draft d
WHERE s.task_draft_id = d.id AND s.step_type = 'PREPARE';

ALTER TABLE business.workflow_plan_step
    ADD CONSTRAINT uk_workflow_step_activation_event UNIQUE (plan_id, activation_event_id),
    ADD CONSTRAINT ck_workflow_step_authorized_draft CHECK (
        (step_type = 'PREPARE'
            AND ((authorized_input_hash IS NULL AND authorized_draft_expires_at IS NULL)
                OR (authorized_input_hash ~ '^[0-9a-f]{64}$'
                    AND authorized_draft_expires_at IS NOT NULL)))
        OR (step_type <> 'PREPARE'
            AND authorized_input_hash IS NULL
            AND authorized_draft_expires_at IS NULL)
    ),
    ADD CONSTRAINT ck_workflow_step_activation CHECK (
        (task_id IS NULL AND activation_event_id IS NULL AND activation_request_hash IS NULL)
        OR (step_type = 'PREPARE' AND task_id IS NOT NULL
            AND activation_event_id IS NOT NULL
            AND activation_request_hash ~ '^[0-9a-f]{64}$'
            AND authorized_input_hash ~ '^[0-9a-f]{64}$'
            AND authorized_draft_expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT ck_workflow_step_task_result_ref CHECK (
        task_result_ref IS NULL
        OR task_result_ref ~ '^(task|asset|geo-report|article):[1-9][0-9]{0,18}$'
    );

ALTER TABLE business.workflow_execution
    DROP CONSTRAINT ck_workflow_execution_status,
    DROP CONSTRAINT ck_workflow_execution_lifecycle;

ALTER TABLE business.workflow_execution
    ADD CONSTRAINT ck_workflow_execution_status CHECK (
        status IN ('RUNNING', 'WAITING_INPUT', 'WAITING_TASK',
            'SUCCEEDED', 'FAILED', 'EXPIRED')
    ),
    ADD CONSTRAINT ck_workflow_execution_lifecycle CHECK (
        (status IN ('RUNNING', 'WAITING_INPUT') AND finished_at IS NULL)
        OR (status IN ('WAITING_TASK', 'SUCCEEDED', 'FAILED', 'EXPIRED')
            AND finished_at IS NOT NULL)
    );

ALTER TABLE business.point_ledger
    DROP CONSTRAINT ck_point_ledger_entry_type;

ALTER TABLE business.point_ledger
    ADD CONSTRAINT ck_point_ledger_entry_type CHECK (
        entry_type IN ('TASK_FREEZE', 'TASK_SETTLE', 'TASK_RELEASE',
            'WORKFLOW_FREEZE', 'WORKFLOW_ALLOCATE', 'WORKFLOW_TASK_SETTLE',
            'WORKFLOW_TASK_RELEASE', 'WORKFLOW_SETTLE', 'WORKFLOW_RELEASE')
    );

ALTER TABLE business.workflow_event
    DROP CONSTRAINT ck_workflow_event_type;

ALTER TABLE business.workflow_event
    ADD CONSTRAINT ck_workflow_event_type CHECK (
        event_type IN ('PLAN_PROPOSED', 'PLAN_WAITING_CONFIRMATION', 'PLAN_CONFIRMED',
            'PLAN_QUEUED', 'PLAN_STARTED', 'STEP_STATE_CHANGED', 'OBSERVATION_RECORDED',
            'STEP_TASK_ALLOCATED', 'PLAN_WAITING_TASK', 'STEP_TASK_SUCCEEDED',
            'STEP_TASK_FAILED', 'PLAN_TASK_RESUME_QUEUED', 'PLAN_REPLANNED',
            'PLAN_WAITING_INPUT', 'PLAN_RETRY_QUEUED', 'PLAN_SUCCEEDED',
            'PLAN_FAILED', 'PLAN_ABANDONED')
    );
