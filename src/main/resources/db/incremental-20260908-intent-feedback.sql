-- Apply before enabling the independent Intent feedback endpoints.
CREATE TABLE IF NOT EXISTS fin_ex_intent_feedback_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    source_message_id VARCHAR(64) NOT NULL,
    intent_access_name VARCHAR(128) NOT NULL,
    feedback_type VARCHAR(32) NOT NULL,
    comment_text TEXT,
    replacement_run_id VARCHAR(64),
    skill_id VARCHAR(128),
    intent_id VARCHAR(128),
    intent_name VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_fin_ex_intent_feedback_owner_run UNIQUE (tenant_id, user_id, run_id)
);
