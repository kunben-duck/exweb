-- 先部署DDL，再受控启用观测；不改变现有业务表，不建立异步记录之间的强外键。
CREATE TABLE IF NOT EXISTS fin_ex_turn_trace_t (
    trace_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64),
    root_run_id VARCHAR(64) NOT NULL,
    root_user_message_id VARCHAR(64),
    source_run_id VARCHAR(64),
    source_trace_id VARCHAR(64),
    current_run_id VARCHAR(64),
    origin_type VARCHAR(32) NOT NULL,
    current_run_ordinal BIGINT NOT NULL,
    state_version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    is_refusal BOOLEAN NOT NULL DEFAULT false,
    started_at TIMESTAMPTZ NOT NULL,
    first_business_frame_at TIMESTAMPTZ,
    first_business_frame_latency_ms BIGINT,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    error_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE fin_ex_turn_trace_t IS '完整问答观测，包含人工及异步等待；best-effort，不作为业务裁决或计费依据。';
COMMENT ON COLUMN fin_ex_turn_trace_t.trace_id IS '问答观测主键，服务端生成turn_标识，与原日志Trace无关。';
COMMENT ON COLUMN fin_ex_turn_trace_t.tenant_id IS '服务端可信租户标识。';
COMMENT ON COLUMN fin_ex_turn_trace_t.user_id IS '服务端可信归属用户标识。';
COMMENT ON COLUMN fin_ex_turn_trace_t.session_id IS 'ChatService会话ID，Trace准入前可为空。';
COMMENT ON COLUMN fin_ex_turn_trace_t.root_run_id IS '首次问答启动分配的Run ID。';
COMMENT ON COLUMN fin_ex_turn_trace_t.root_user_message_id IS '最初可信user消息ID，后续澄清新增消息不覆盖。';
COMMENT ON COLUMN fin_ex_turn_trace_t.source_run_id IS '编辑、重新生成、候选切换或旧流程关联的来源Run ID，可空。';
COMMENT ON COLUMN fin_ex_turn_trace_t.source_trace_id IS '来源问答Trace ID，可空，不读取旧日志Trace。';
COMMENT ON COLUMN fin_ex_turn_trace_t.current_run_id IS '当前Run ID；ordinal为0时仅为启动预分配值，成功准入后按阶段递增更新。';
COMMENT ON COLUMN fin_ex_turn_trace_t.origin_type IS 'Trace来源：NEXT、EDIT_USER、REGENERATE_ASSISTANT、CANDIDATE_SWITCH、LEGACY_CONTINUATION、LEGACY_STOP。';
COMMENT ON COLUMN fin_ex_turn_trace_t.current_run_ordinal IS '问答内Run阶段序号：未准入0、首Run1、成功续跑递增。';
COMMENT ON COLUMN fin_ex_turn_trace_t.state_version IS '阶段状态版本：启动前0、运行1、等待2、最终结束3，防止迟到快照回退状态。';
COMMENT ON COLUMN fin_ex_turn_trace_t.status IS '问答状态：STARTED、RUNNING、WAITING_USER、ASYNC_WAITING、COMPLETED、FAILED、CANCELLED。';
COMMENT ON COLUMN fin_ex_turn_trace_t.is_refusal IS '是否曾记录到明确Agent拒答，累计逻辑或；false不保证没有丢失的拒答观测。';
COMMENT ON COLUMN fin_ex_turn_trace_t.started_at IS '业务开始采样UTC时间，不使用数据库插入时间。';
COMMENT ON COLUMN fin_ex_turn_trace_t.first_business_frame_at IS '首次有效业务帧UTC采样时间，无可靠样本时为空。';
COMMENT ON COLUMN fin_ex_turn_trace_t.first_business_frame_latency_ms IS '从本记录开始到首个有效业务帧的毫秒数；Trace跨Run不重置。';
COMMENT ON COLUMN fin_ex_turn_trace_t.finished_at IS '最终结束UTC采样时间；Trace人工及异步等待期间为空。';
COMMENT ON COLUMN fin_ex_turn_trace_t.duration_ms IS '完整问答非负耗时毫秒，包含准备、重试、人工及异步等待，最终结束时赋值。';
COMMENT ON COLUMN fin_ex_turn_trace_t.error_code IS 'Trace最终失败已有可信错误码，未知为空；不保存技术错误文本。';
COMMENT ON COLUMN fin_ex_turn_trace_t.created_at IS '快照首次创建UTC时间。';
COMMENT ON COLUMN fin_ex_turn_trace_t.updated_at IS '快照最近业务采样UTC时间。';

CREATE TABLE IF NOT EXISTS fin_ex_agent_observation_t (
    observation_id VARCHAR(64) PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    user_message_id VARCHAR(64),
    parent_observation_id VARCHAR(64),
    retry_of_observation_id VARCHAR(64),
    attempt_no INTEGER NOT NULL,
    agent_type VARCHAR(32) NOT NULL,
    agent_mode VARCHAR(32),
    operation VARCHAR(32) NOT NULL,
    skill_id VARCHAR(128),
    runtime_session_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    outcome VARCHAR(32),
    started_at TIMESTAMPTZ NOT NULL,
    first_business_frame_at TIMESTAMPTZ,
    first_business_frame_latency_ms BIGINT,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    reject_code VARCHAR(128),
    reject_reason_code VARCHAR(128),
    reject_message TEXT,
    reject_recoverable BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE fin_ex_agent_observation_t IS '单次实际Agent调用尝试观测；不保存技术错误文本、请求或响应正文。';
COMMENT ON COLUMN fin_ex_agent_observation_t.observation_id IS '单次实际调用尝试主键，服务端生成obs_标识。';
COMMENT ON COLUMN fin_ex_agent_observation_t.trace_id IS '所属问答的turn_观测标识，不设强外键，不依赖异步写入先后顺序。';
COMMENT ON COLUMN fin_ex_agent_observation_t.tenant_id IS '服务端可信租户标识。';
COMMENT ON COLUMN fin_ex_agent_observation_t.user_id IS '服务端可信归属用户标识。';
COMMENT ON COLUMN fin_ex_agent_observation_t.session_id IS '本次调用所属的可信ChatService会话ID，不是下游会话ID。';
COMMENT ON COLUMN fin_ex_agent_observation_t.run_id IS '本次调用关联Run ID。';
COMMENT ON COLUMN fin_ex_agent_observation_t.user_message_id IS '本Run实际关联user消息ID，普通意图澄清可能不同于根消息。';
COMMENT ON COLUMN fin_ex_agent_observation_t.parent_observation_id IS '引发本逻辑调用的上游Observation ID，可空。';
COMMENT ON COLUMN fin_ex_agent_observation_t.retry_of_observation_id IS '同一逻辑调用前一次重试的Observation ID，首次为空。';
COMMENT ON COLUMN fin_ex_agent_observation_t.attempt_no IS '同一逻辑调用尝试序号，从1开始，实际重试递增。';
COMMENT ON COLUMN fin_ex_agent_observation_t.agent_type IS '调用对象：INTENT、DOMAIN_AGENT、RELAY。';
COMMENT ON COLUMN fin_ex_agent_observation_t.agent_mode IS 'Relay模式：DELEGATE或DOMAIN_EXPERT，其他为空。';
COMMENT ON COLUMN fin_ex_agent_observation_t.operation IS '调用操作：RECOGNIZE、QUERY、INTERACTION_RESPONSE、STOP、ASYNC_CALLBACK。';
COMMENT ON COLUMN fin_ex_agent_observation_t.skill_id IS 'DomainAgent技能ID或Relay专家最终roleName；Intent及Delegate为空。';
COMMENT ON COLUMN fin_ex_agent_observation_t.runtime_session_id IS '真实下游会话ID，尚未返回时为空。';
COMMENT ON COLUMN fin_ex_agent_observation_t.status IS '调用采样状态：STARTED、FINISHED，结束快照可补建缺失的开始记录。';
COMMENT ON COLUMN fin_ex_agent_observation_t.outcome IS '结果：COMPLETED、REJECTED、WAITING_USER、ASYNC_ACCEPTED、STREAM_ENDED、ERROR、TIMEOUT、CANCELLED、STOP_ACCEPTED、CALLBACK_APPLIED、CALLBACK_IGNORED。';
COMMENT ON COLUMN fin_ex_agent_observation_t.started_at IS '业务开始采样UTC时间，不使用数据库插入时间。';
COMMENT ON COLUMN fin_ex_agent_observation_t.first_business_frame_at IS '首次有效业务帧UTC采样时间，无可靠样本时为空。';
COMMENT ON COLUMN fin_ex_agent_observation_t.first_business_frame_latency_ms IS '本次调用开始到本调用首次有效业务帧的非负毫秒数；每次尝试独立计算。';
COMMENT ON COLUMN fin_ex_agent_observation_t.finished_at IS '本次调用结束UTC采样时间，包括已明确发问、拒答或异步受理的截流结束。';
COMMENT ON COLUMN fin_ex_agent_observation_t.duration_ms IS '单次实际尝试的非负毫秒耗时，包含连接获取和握手，不含前置准备及后续重试退避。';
COMMENT ON COLUMN fin_ex_agent_observation_t.reject_code IS '明确拒答控制帧中的拒答代码，可空。';
COMMENT ON COLUMN fin_ex_agent_observation_t.reject_reason_code IS '明确拒答控制帧中的原因代码，可空。';
COMMENT ON COLUMN fin_ex_agent_observation_t.reject_message IS '脱敏拒答摘要，最多1024个Unicode码点；no-store不保存文本。';
COMMENT ON COLUMN fin_ex_agent_observation_t.reject_recoverable IS '下游明确声明的拒答是否可恢复，未声明为空。';
COMMENT ON COLUMN fin_ex_agent_observation_t.created_at IS '快照首次创建UTC时间。';
COMMENT ON COLUMN fin_ex_agent_observation_t.updated_at IS '快照最近业务采样UTC时间。';

CREATE INDEX IF NOT EXISTS idx_fin_ex_trace_owner_time ON fin_ex_turn_trace_t (tenant_id, user_id, started_at, trace_id);
CREATE INDEX IF NOT EXISTS idx_fin_ex_obs_trace_time ON fin_ex_agent_observation_t (tenant_id, user_id, trace_id, started_at, observation_id);
CREATE INDEX IF NOT EXISTS idx_fin_ex_obs_run ON fin_ex_agent_observation_t (tenant_id, user_id, run_id);
