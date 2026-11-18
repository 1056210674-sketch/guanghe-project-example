CREATE TABLE business.article_submission_preference (
    article_id bigint PRIMARY KEY,
    user_id bigint NOT NULL,
    space_id bigint NOT NULL,
    article_version integer NOT NULL,
    goal varchar(32) NOT NULL,
    preferred_region varchar(120),
    max_total_points integer,
    requires_link boolean NOT NULL,
    confirmed_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_article_submission_preference_article
        FOREIGN KEY (article_id, user_id, space_id)
        REFERENCES business.article (id, user_id, space_id) ON DELETE CASCADE,
    CONSTRAINT ck_article_submission_preference_version CHECK (article_version > 0),
    CONSTRAINT ck_article_submission_preference_goal CHECK (
        goal IN ('BALANCED','INDUSTRY_MATCH','COST_EFFECTIVE','BROAD_REACH')
    ),
    CONSTRAINT ck_article_submission_preference_region CHECK (
        preferred_region IS NULL OR length(btrim(preferred_region)) BETWEEN 1 AND 120
    ),
    CONSTRAINT ck_article_submission_preference_budget CHECK (
        max_total_points IS NULL OR max_total_points > 0
    )
);

CREATE INDEX idx_article_submission_preference_owner
    ON business.article_submission_preference (user_id, article_id);
