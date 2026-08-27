CREATE TABLE fin_ex_intent_preference_correction_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    intent_access_name VARCHAR(128) NOT NULL,
    session_id VARCHAR(64),
    source_message_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    query_text TEXT,
    preference_intent TEXT NOT NULL,
    original_intent TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_fin_ex_intent_preference_owner_access_source
        UNIQUE (tenant_id, user_id, intent_access_name, source_message_id)
);

CREATE INDEX idx_fin_ex_intent_preference_owner_access_updated
    ON fin_ex_intent_preference_correction_t(
        tenant_id, user_id, intent_access_name, updated_at DESC, id DESC);

COMMENT ON TABLE fin_ex_intent_preference_correction_t IS '用户显式选择的意图路由偏好，按租户、用户和Intent入口跨会话提供给后续意图识别。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.id IS '偏好记录主键，业务生成的intent_pref ID。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.tenant_id IS '租户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.user_id IS '系统归属用户标识。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.intent_access_name IS '偏好生效的Intent服务入口名称，区分大小写。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.session_id IS '产生本次选择的聊天会话。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.source_message_id IS '产生本次偏好的可信user消息ID，同一来源重复提交会覆盖。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.source_type IS '偏好来源：INTENT_CANDIDATE或AMBIGUOUS_ROUTE。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.query_text IS '产生本次偏好的原始可信用户问题。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.preference_intent IS '用户最终选择的意图名称。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.original_intent IS '原始识别意图名称；模糊意图没有唯一原始结果时为空。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.created_at IS '记录首次创建时间。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.updated_at IS '记录最后一次选择更新时间，也是发送给Intent的timestamp。';
