-- Persisted evidence must use the same addressable bundle-version format as the signed internal
-- contract and the administrator Browser API. NOT VALID keeps the initial lock short; VALIDATE then
-- fails the migration closed if a legacy row cannot be represented safely.
ALTER TABLE business.task_execution
    ADD CONSTRAINT ck_task_execution_bundle_version
    CHECK (bundle_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$') NOT VALID;
ALTER TABLE business.task_execution
    VALIDATE CONSTRAINT ck_task_execution_bundle_version;

ALTER TABLE business.workflow_execution
    ADD CONSTRAINT ck_workflow_execution_bundle_version
    CHECK (bundle_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$') NOT VALID;
ALTER TABLE business.workflow_execution
    VALIDATE CONSTRAINT ck_workflow_execution_bundle_version;

-- Version history and its associated execution list are keyed by the exact bundle version and
-- ordered newest-first. Existing task and workflow indexes serve worker leasing or plan ownership;
-- neither can avoid scanning and sorting every execution for these cross-account read-only views.
CREATE INDEX idx_task_execution_bundle_started
    ON business.task_execution (bundle_version, started_at DESC, id DESC);
CREATE INDEX idx_workflow_execution_bundle_started
    ON business.workflow_execution (bundle_version, started_at DESC, id DESC);
