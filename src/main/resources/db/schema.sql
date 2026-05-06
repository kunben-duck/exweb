CREATE TABLE IF NOT EXISTS fin_ex_chat_session_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    title VARCHAR(256),
    status VARCHAR(32) NOT NULL,
    channel VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS fin_ex_chat_message_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT,
    token_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_message_session_created_at
    ON fin_ex_chat_message_t(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_message_owner_session_created_at
    ON fin_ex_chat_message_t(tenant_id, user_id, session_id, created_at);

CREATE TABLE IF NOT EXISTS fin_ex_chat_event_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(session_id, run_id, seq)
);

CREATE TABLE IF NOT EXISTS fin_ex_conversation_summary_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    summary_text TEXT NOT NULL,
    message_from_seq BIGINT,
    message_to_seq BIGINT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS fin_ex_uploaded_document_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64),
    original_name VARCHAR(512) NOT NULL,
    bucket VARCHAR(128) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(128),
    size_bytes BIGINT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS fin_ex_agent_binding_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    chat_session_id VARCHAR(64) NOT NULL,
    binding_type VARCHAR(64) NOT NULL,
    agent_code VARCHAR(128),
    provider VARCHAR(128),
    agent_session_id VARCHAR(128),
    runtime_session_id VARCHAR(128),
    status VARCHAR(64) NOT NULL,
    last_run_id VARCHAR(64),
    expires_at TIMESTAMPTZ,
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_agent_binding_owner_session_status
    ON fin_ex_agent_binding_t(tenant_id, user_id, chat_session_id, status);
CREATE INDEX IF NOT EXISTS idx_fin_ex_agent_binding_expires_at
    ON fin_ex_agent_binding_t(expires_at);

CREATE TABLE IF NOT EXISTS fin_ex_task_card_t (
    task_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    chat_session_id VARCHAR(64) NOT NULL,
    binding_id VARCHAR(64),
    task_goal VARCHAR(512) NOT NULL,
    task_domain VARCHAR(128) NOT NULL,
    agent_code VARCHAR(128) NOT NULL,
    agent_session_id VARCHAR(128),
    task_status VARCHAR(64) NOT NULL,
    raw_normalized_status VARCHAR(64) NOT NULL,
    required_inputs_json TEXT,
    collected_slots_json TEXT,
    last_agent_message TEXT,
    confirmation_question TEXT,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    metadata_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_task_card_owner_session_status
    ON fin_ex_task_card_t(tenant_id, user_id, chat_session_id, task_status);
CREATE INDEX IF NOT EXISTS idx_fin_ex_task_card_expires_at
    ON fin_ex_task_card_t(expires_at);

CREATE TABLE IF NOT EXISTS fin_ex_task_event_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    chat_session_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64),
    event_type VARCHAR(64) NOT NULL,
    from_status VARCHAR(64),
    to_status VARCHAR(64),
    payload_json TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_task_event_task_created_at
    ON fin_ex_task_event_t(task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_fin_ex_task_event_owner_session_created_at
    ON fin_ex_task_event_t(tenant_id, user_id, chat_session_id, created_at);
