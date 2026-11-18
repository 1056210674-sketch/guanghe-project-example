ALTER TABLE business.article
    ADD COLUMN workflow_stage varchar(40) NOT NULL DEFAULT 'TITLE_INPUT',
    ADD COLUMN current_title_batch_version integer,
    ADD COLUMN confirmed_title varchar(300),
    ADD COLUMN confirmed_title_source varchar(16),
    ADD COLUMN confirmed_title_batch_version integer,
    ADD COLUMN confirmed_title_candidate_index integer,
    ADD COLUMN confirmed_title_at timestamptz,
    ADD COLUMN current_plan_version integer,
    ADD COLUMN confirmed_plan_version integer;

UPDATE business.article
SET workflow_stage = CASE
    WHEN body_text <> '' OR status = 'COMPLETED' THEN 'BODY_READY'
    ELSE 'TITLE_INPUT'
END;

ALTER TABLE business.article
    ADD CONSTRAINT ck_article_workflow_stage CHECK (workflow_stage IN (
        'TITLE_INPUT','TITLE_GENERATING','TITLE_AWAITING_CONFIRMATION',
        'PLAN_READY','PLAN_GENERATING','PLAN_AWAITING_CONFIRMATION',
        'BODY_READY_TO_GENERATE','BODY_GENERATING',
        'BODY_READY','EDITING','READY_TO_SUBMIT'
    )),
    ADD CONSTRAINT ck_article_title_workflow CHECK (
        (confirmed_title IS NULL AND confirmed_title_source IS NULL
            AND confirmed_title_batch_version IS NULL
            AND confirmed_title_candidate_index IS NULL AND confirmed_title_at IS NULL)
        OR
        (confirmed_title IS NOT NULL AND length(btrim(confirmed_title)) BETWEEN 1 AND 300
            AND confirmed_title_source IN ('SELECTED','EDITED','CUSTOM')
            AND confirmed_title_batch_version > 0
            AND confirmed_title_at IS NOT NULL
            AND ((confirmed_title_source = 'CUSTOM' AND confirmed_title_candidate_index IS NULL)
                OR (confirmed_title_source IN ('SELECTED','EDITED')
                    AND confirmed_title_candidate_index BETWEEN 1 AND 5)))
    ),
    ADD CONSTRAINT ck_article_workflow_versions CHECK (
        (current_title_batch_version IS NULL OR current_title_batch_version > 0)
        AND (current_plan_version IS NULL OR current_plan_version > 0)
        AND (confirmed_plan_version IS NULL OR confirmed_plan_version > 0)
        AND (confirmed_plan_version IS NULL OR confirmed_plan_version = current_plan_version)
    );

CREATE TABLE business.article_title_candidate_batch (
    id bigint PRIMARY KEY,
    article_id bigint NOT NULL,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    batch_version integer NOT NULL,
    article_version integer NOT NULL,
    candidates jsonb NOT NULL,
    source_task_id bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_article_title_batch_article FOREIGN KEY (article_id, user_id, space_id)
        REFERENCES business.article (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT fk_article_title_batch_task FOREIGN KEY (source_task_id, user_id, space_id)
        REFERENCES business.business_task (id, user_id, space_id),
    CONSTRAINT uk_article_title_batch_version UNIQUE (article_id, batch_version),
    CONSTRAINT uk_article_title_batch_task UNIQUE (source_task_id),
    CONSTRAINT ck_article_title_batch_versions CHECK (batch_version > 0 AND article_version > 0),
    CONSTRAINT ck_article_title_candidates CHECK (
        jsonb_typeof(candidates) = 'array' AND jsonb_array_length(candidates) = 5
        AND octet_length(candidates::text) <= 8192
    )
);

CREATE INDEX idx_article_title_batch_current
    ON business.article_title_candidate_batch (article_id, batch_version DESC);

CREATE TABLE business.article_creative_plan_revision (
    id bigint PRIMARY KEY,
    article_id bigint NOT NULL,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    plan_version integer NOT NULL,
    article_version integer NOT NULL,
    plan jsonb NOT NULL,
    source varchar(16) NOT NULL,
    source_task_id bigint,
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_article_plan_article FOREIGN KEY (article_id, user_id, space_id)
        REFERENCES business.article (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT fk_article_plan_task FOREIGN KEY (source_task_id, user_id, space_id)
        REFERENCES business.business_task (id, user_id, space_id),
    CONSTRAINT uk_article_plan_version UNIQUE (article_id, plan_version),
    CONSTRAINT ck_article_plan_versions CHECK (plan_version > 0 AND article_version > 0),
    CONSTRAINT ck_article_plan_source CHECK (
        (source = 'GENERATED' AND source_task_id IS NOT NULL)
        OR (source = 'EDITED' AND source_task_id IS NULL)
    ),
    CONSTRAINT ck_article_plan_payload CHECK (
        jsonb_typeof(plan) = 'object' AND octet_length(plan::text) <= 131072
    )
);

CREATE INDEX idx_article_plan_current
    ON business.article_creative_plan_revision (article_id, plan_version DESC);

CREATE UNIQUE INDEX uk_article_plan_task
    ON business.article_creative_plan_revision (source_task_id)
    WHERE source_task_id IS NOT NULL;
