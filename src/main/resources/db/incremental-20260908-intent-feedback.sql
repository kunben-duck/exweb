-- 在启用独立意图反馈接口前执行；依赖已存在的 fin_ex_intent_preference_correction_t 偏好表。
-- 已建表环境可重复执行以补齐数据库注释，不修改现有表结构或业务数据。
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
    -- 同一租户、用户和Run仅允许一条反馈；不按Intent入口分别保存。
    CONSTRAINT uk_fin_ex_intent_feedback_owner_run UNIQUE (tenant_id, user_id, run_id)
);

COMMENT ON TABLE fin_ex_intent_feedback_t IS '按租户、用户和Run保存意图评价，首次有效提交后不可修改，相同提交幂等；用于历史回显及自动偏好联动，不代表系统实际路由事实。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.id IS '反馈记录主键，业务生成的intent_fb ID。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.tenant_id IS '租户标识，来自服务端可信身份上下文。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.user_id IS '系统归属用户标识，来自UserContext.ownerUserId。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.session_id IS '被评价Run所属的聊天会话ID，由服务端读取。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.run_id IS '被评价的ChatService Run ID，同一归属下仅允许首次反馈，不按Intent入口分别保存。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.source_message_id IS '被评价Run关联的可信user消息ID，不是assistant消息ID。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.intent_access_name IS '反馈及偏好使用的逻辑Intent入口，区分大小写，不拼接request-access-name-prefix；聚合专家使用被评价Run保存的专家入口。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.feedback_type IS '反馈类型：CORRECT表示认可意图，INCORRECT_COMMENT表示仅提交错误说明，INCORRECT_SWITCH表示改选候选技能。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.comment_text IS '仅INCORRECT_COMMENT必填的错误说明，trim后非空且最多1024个Unicode码点；其他反馈类型为空。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.replacement_run_id IS '仅INCORRECT_SWITCH必填的已受理替代Run ID，与被评价Run属于同一会话和user消息。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.skill_id IS '仅INCORRECT_SWITCH必填的用户改选目标DomainAgent技能ID，不代表替代Run重路由后的最终技能。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.intent_id IS '用户提交的selectedIntent意图摘要ID，可空，不作为系统实际路由事实。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.intent_name IS '用户认可或改选的意图名称；CORRECT有明确目标时提供，INCORRECT_SWITCH必填，纯文字错误为空；用于自动偏好，不作为系统实际路由事实。';
COMMENT ON COLUMN fin_ex_intent_feedback_t.created_at IS '首次有效反馈的服务端UTC创建时间；相同提交幂等重试不刷新。';

COMMENT ON COLUMN fin_ex_intent_preference_correction_t.intent_access_name IS '偏好生效的逻辑Intent入口，区分大小写，不拼接request-access-name-prefix。';
COMMENT ON COLUMN fin_ex_intent_preference_correction_t.source_type IS '偏好来源：INTENT_CANDIDATE为候选选择，AMBIGUOUS_ROUTE为模糊意图人工选择，INTENT_FEEDBACK_CORRECT为准确反馈，INTENT_FEEDBACK_SWITCH为候选改选反馈。';
