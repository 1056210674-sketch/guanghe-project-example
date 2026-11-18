CREATE INDEX idx_business_task_conversation_delivery
    ON business.business_task (
        user_id,
        conversation_id,
        (status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')) DESC,
        created_at DESC,
        id DESC
    )
    WHERE conversation_id IS NOT NULL;

CREATE INDEX idx_business_task_conversation_active
    ON business.business_task (user_id, conversation_id)
    WHERE conversation_id IS NOT NULL
      AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT');
