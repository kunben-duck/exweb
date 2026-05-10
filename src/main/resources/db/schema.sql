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

CREATE TABLE IF NOT EXISTS fin_ex_runtime_binding_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    chat_session_id VARCHAR(64) NOT NULL,
    provider VARCHAR(128) NOT NULL,
    runtime_session_id VARCHAR(128),
    status VARCHAR(64) NOT NULL,
    last_run_id VARCHAR(64),
    expires_at TIMESTAMPTZ,
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_runtime_binding_owner_session_provider_status
    ON fin_ex_runtime_binding_t(tenant_id, user_id, chat_session_id, provider, status);
CREATE INDEX IF NOT EXISTS idx_fin_ex_runtime_binding_expires_at
    ON fin_ex_runtime_binding_t(expires_at);
