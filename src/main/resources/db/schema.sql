CREATE TABLE IF NOT EXISTS chat_session (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    title VARCHAR(256),
    status VARCHAR(32) NOT NULL,
    channel VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_message (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT,
    token_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_message_session_created_at ON chat_message(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_message_owner_session_created_at ON chat_message(tenant_id, user_id, session_id, created_at);

CREATE TABLE IF NOT EXISTS chat_event (
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

CREATE TABLE IF NOT EXISTS conversation_summary (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    summary_text TEXT NOT NULL,
    message_from_seq BIGINT,
    message_to_seq BIGINT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS tool_definition (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64),
    tool_code VARCHAR(128) NOT NULL,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    category VARCHAR(128),
    provider_code VARCHAR(128) NOT NULL,
    provider_tool_id VARCHAR(256),
    source_type VARCHAR(64) NOT NULL,
    invocation_mode VARCHAR(64) NOT NULL,
    risk_level VARCHAR(64) NOT NULL,
    input_schema_json TEXT,
    output_schema_json TEXT,
    required_scopes_json TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    extension_json TEXT,
    version VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(tenant_id, tool_code)
);

CREATE TABLE IF NOT EXISTS tool_invocation_record (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64),
    run_id VARCHAR(64),
    tool_code VARCHAR(128) NOT NULL,
    provider_code VARCHAR(128),
    provider_tool_id VARCHAR(256),
    idempotency_key VARCHAR(128),
    status VARCHAR(64) NOT NULL,
    input_json TEXT,
    output_json TEXT,
    error_code VARCHAR(128),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS uploaded_document (
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
