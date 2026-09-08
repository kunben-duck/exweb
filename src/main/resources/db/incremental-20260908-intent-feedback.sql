-- Apply before enabling the independent Intent feedback endpoints.
-- 在与应用 FINANCEEX_DB_SCHEMA 一致的 schema 下执行；偏好表须已通过初始化或 20260827 增量脚本创建。
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

COMMENT ON TABLE fin_ex_intent_feedback_t IS '用户对单个 Run 的意图评价事实表，首次提交后应用不支持修改或撤销；独立于回答点赞点踩和可覆盖的意图偏好，不设置外键级联删除。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.id IS '反馈主键，业务生成的 intent_fb ID。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.tenant_id IS '租户标识，来自服务端身份上下文，用于多租户数据隔离。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.user_id IS '系统归属用户标识，取 UserContext.ownerUserId，用于用户级数据隔离。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.session_id IS '被评价 Run 所属会话 ID，由服务端从 source Run 获取。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.run_id IS '被评价的 source Run ID，对应 fin_ex_chat_run_t.id；同一租户、用户、Run 最多保存一条反馈。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.source_message_id IS 'source Run 关联的可信 user 消息 ID；自动偏好从该消息读取原问题，不使用前端提供的问题文本。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.intent_access_name IS '偏好分组的逻辑 Intent 入口，trim 后保留大小写，不拼接出站请求前缀；普通模式未传时取默认值，聚合专家取 source Run 保存的入口。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.feedback_type IS '意图评价类型：CORRECT 表示准确，INCORRECT_COMMENT 表示不准确并提交说明，INCORRECT_SWITCH 表示不准确并已改选技能。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.comment_text IS '仅 INCORRECT_COMMENT 保存的说明，trim 后非空，应用限制为最多 1024 个 Unicode 码点；其他类型为空。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.replacement_run_id IS '仅 INCORRECT_SWITCH 保存的替换 Run ID；须归属同一用户、会话和 user 消息，模式为 REGENERATE_ASSISTANT，创建时间不早于 source Run。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.skill_id IS '仅 INCORRECT_SWITCH 保存的用户改选技能 ID；是用户选择摘要，不代表替换 Run 最终执行的 agentCode。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.intent_id IS '用户提交的 selectedIntent.intentId，可为空；不作为系统意图识别事实，也不由服务端查询补齐。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.intent_name IS '用户提交的 selectedIntent.intentName；INCORRECT_SWITCH 必填，CORRECT 有明确目标时可填，INCORRECT_COMMENT 为空；存在时与反馈在同一事务中保存自动偏好。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.created_at IS '首次反馈提交时由服务端生成的时间，幂等重试保留原值。';
COMMENT ON CONSTRAINT uk_fin_ex_intent_feedback_owner_run ON fin_ex_intent_feedback_t IS '按租户、用户和 Run 限制首次反馈唯一，仲裁并发提交；相同请求的幂等比较由应用完成。';

-- 同步已有偏好表的字段说明，覆盖本次新增的自动偏好来源。
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.source_type IS '偏好来源：INTENT_CANDIDATE、AMBIGUOUS_ROUTE、INTENT_FEEDBACK_CORRECT（准确评价）或 INTENT_FEEDBACK_SWITCH（改选技能评价）。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.original_intent IS '原始识别意图名称；模糊路由或意图反馈自动生成的偏好为空，意图反馈流程不查询或补齐原始识别结果。';
