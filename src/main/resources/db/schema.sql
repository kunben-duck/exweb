CREATE TABLE IF NOT EXISTS fin_ex_chat_session_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    title VARCHAR(256),
    status VARCHAR(32) NOT NULL,
    channel VARCHAR(64),
    current_leaf_message_id VARCHAR(64),
    root_session_id VARCHAR(64),
    branch_source_session_id VARCHAR(64),
    branch_source_message_id VARCHAR(64),
    last_node_order BIGINT NOT NULL DEFAULT 0,
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS fin_ex_chat_message_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    parent_message_id VARCHAR(64),
    node_order BIGINT NOT NULL DEFAULT 0,
    tree_depth INTEGER NOT NULL DEFAULT 0,
    sibling_index INTEGER NOT NULL DEFAULT 0,
    role VARCHAR(32) NOT NULL,
    content TEXT,
    token_count INTEGER,
    run_id VARCHAR(64),
    origin_type VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    source_session_id VARCHAR(64),
    source_message_id VARCHAR(64),
    edited_from_message_id VARCHAR(64),
    regenerated_from_message_id VARCHAR(64),
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_fin_ex_chat_message_owner_session_node_order
    ON fin_ex_chat_message_t(tenant_id, user_id, session_id, node_order);
CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_message_owner_session_parent
    ON fin_ex_chat_message_t(tenant_id, user_id, session_id, parent_message_id, role, sibling_index);
CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_message_owner_id
    ON fin_ex_chat_message_t(tenant_id, user_id, id);

CREATE TABLE IF NOT EXISTS fin_ex_chat_message_attachment_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    attachment_order INTEGER NOT NULL DEFAULT 0,
    name VARCHAR(512),
    content_type VARCHAR(128),
    size_bytes BIGINT,
    source_attachment_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_message_attachment_owner_message
    ON fin_ex_chat_message_attachment_t(tenant_id, user_id, message_id, attachment_order);

CREATE TABLE IF NOT EXISTS fin_ex_message_feedback_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64),
    rating VARCHAR(32) NOT NULL,
    reason_code VARCHAR(128),
    comment_text TEXT,
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_message_feedback_owner_message
    ON fin_ex_message_feedback_t(tenant_id, user_id, message_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS fin_ex_chat_run_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    route_type VARCHAR(64),
    agent_code VARCHAR(128),
    runtime_provider VARCHAR(128),
    runtime_session_id VARCHAR(128),
    run_mode VARCHAR(32) NOT NULL DEFAULT 'NEXT',
    parent_message_id VARCHAR(64),
    user_message_id VARCHAR(64),
    assistant_message_id VARCHAR(64),
    first_seq BIGINT,
    last_seq BIGINT,
    cancel_reason VARCHAR(256),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_run_owner_session_status_updated_at
    ON fin_ex_chat_run_t(tenant_id, user_id, session_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_run_session_last_seq
    ON fin_ex_chat_run_t(session_id, last_seq);

CREATE TABLE IF NOT EXISTS fin_ex_chat_run_execution_t (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    execution_status VARCHAR(32) NOT NULL,
    owner_instance_id VARCHAR(256),
    heartbeat_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 1,
    recovery_strategy VARCHAR(64),
    recovered_by_instance_id VARCHAR(256),
    recovery_attempts INTEGER NOT NULL DEFAULT 0,
    recovery_lease_until TIMESTAMPTZ,
    runtime_resume_token VARCHAR(512),
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(run_id)
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_run_execution_status_lease_until
    ON fin_ex_chat_run_execution_t(execution_status, lease_until);
CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_run_execution_status_recovery_lease_until
    ON fin_ex_chat_run_execution_t(execution_status, recovery_lease_until);
CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_run_execution_owner_status
    ON fin_ex_chat_run_execution_t(owner_instance_id, execution_status);

CREATE SEQUENCE IF NOT EXISTS fin_ex_chat_event_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS fin_ex_chat_event_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL DEFAULT nextval('fin_ex_chat_event_seq'),
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(session_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_event_run_seq
    ON fin_ex_chat_event_t(run_id, seq);
CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_event_owner_session_seq
    ON fin_ex_chat_event_t(tenant_id, user_id, session_id, seq);
CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_event_owner_run_seq
    ON fin_ex_chat_event_t(tenant_id, user_id, run_id, seq);

CREATE TABLE IF NOT EXISTS fin_ex_chat_read_cursor_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    last_consumed_seq BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(tenant_id, user_id, session_id)
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_read_cursor_owner_session
    ON fin_ex_chat_read_cursor_t(tenant_id, user_id, session_id);

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
    source VARCHAR(32) NOT NULL DEFAULT 'LOCAL_UPLOAD',
    token_size BIGINT,
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_uploaded_document_owner_status_updated_at
    ON fin_ex_uploaded_document_t(tenant_id, user_id, status, updated_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_fin_ex_uploaded_document_owner_session_status_updated_at
    ON fin_ex_uploaded_document_t(tenant_id, user_id, session_id, status, updated_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS fin_ex_runtime_binding_t (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    chat_session_id VARCHAR(64) NOT NULL,
    provider VARCHAR(128) NOT NULL,
    leaf_message_id VARCHAR(64),
    runtime_session_id VARCHAR(128),
    status VARCHAR(64) NOT NULL,
    last_run_id VARCHAR(64),
    expires_at TIMESTAMPTZ,
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fin_ex_runtime_binding_owner_session_provider_leaf_status
    ON fin_ex_runtime_binding_t(tenant_id, user_id, chat_session_id, provider, leaf_message_id, status);
CREATE INDEX IF NOT EXISTS idx_fin_ex_runtime_binding_expires_at
    ON fin_ex_runtime_binding_t(expires_at);

COMMENT ON TABLE fin_ex_chat_session_t IS '聊天会话表，保存前端用户可见的会话元数据和会话生命周期状态。';
COMMENT ON COLUMN fin_ex_chat_session_t.id IS '会话主键，业务生成的 sessionId。';
COMMENT ON COLUMN fin_ex_chat_session_t.tenant_id IS '租户标识，来自服务端身份上下文，用于多租户数据隔离。';
COMMENT ON COLUMN fin_ex_chat_session_t.user_id IS '用户标识，来自服务端身份上下文，用于用户级数据隔离。';
COMMENT ON COLUMN fin_ex_chat_session_t.title IS '会话标题，默认由用户首轮输入截断生成，也可由前端重命名。';
COMMENT ON COLUMN fin_ex_chat_session_t.status IS '会话状态，例如 ACTIVE、ARCHIVED、CLOSED。';
COMMENT ON COLUMN fin_ex_chat_session_t.channel IS '会话来源渠道，例如 web。';
COMMENT ON COLUMN fin_ex_chat_session_t.current_leaf_message_id IS '当前会话激活路径的叶子消息 ID；历史查询默认沿该节点回溯 active path。';
COMMENT ON COLUMN fin_ex_chat_session_t.root_session_id IS '分支族根会话 ID；普通会话等于自身，分支会话继承源会话根。';
COMMENT ON COLUMN fin_ex_chat_session_t.branch_source_session_id IS '当前会话从哪个源会话分支而来；普通会话为空。';
COMMENT ON COLUMN fin_ex_chat_session_t.branch_source_message_id IS '当前会话从源会话哪条消息分支而来；普通会话为空。';
COMMENT ON COLUMN fin_ex_chat_session_t.last_node_order IS '当前会话内最大消息节点序号，写入新消息时递增生成 node_order。';
COMMENT ON COLUMN fin_ex_chat_session_t.metadata_json IS '会话扩展元数据 JSON，保存分支、展示或诊断扩展信息。';
COMMENT ON COLUMN fin_ex_chat_session_t.created_at IS '会话创建时间。';
COMMENT ON COLUMN fin_ex_chat_session_t.updated_at IS '会话最后更新时间，列表排序和最近访问使用。';

COMMENT ON TABLE fin_ex_chat_message_t IS '聊天消息历史表，保存已经完整落库的用户消息和完整 assistant 回复。流式 delta 不直接写入本表。';
COMMENT ON COLUMN fin_ex_chat_message_t.id IS '消息主键，业务生成的 messageId。';
COMMENT ON COLUMN fin_ex_chat_message_t.tenant_id IS '租户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_chat_message_t.user_id IS '用户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_chat_message_t.session_id IS '所属聊天会话 ID，对应 fin_ex_chat_session_t.id。';
COMMENT ON COLUMN fin_ex_chat_message_t.parent_message_id IS '消息树父节点 ID；用于编辑历史问题、重新生成和 active path 回溯。';
COMMENT ON COLUMN fin_ex_chat_message_t.node_order IS '会话内消息节点创建序号，保证历史展示和分支复制排序稳定。';
COMMENT ON COLUMN fin_ex_chat_message_t.tree_depth IS '消息树深度，根可见消息为 0。';
COMMENT ON COLUMN fin_ex_chat_message_t.sibling_index IS '同一父节点下同角色候选序号，用于前端显示历史版本切换。';
COMMENT ON COLUMN fin_ex_chat_message_t.role IS '消息角色，当前主要为 user 或 assistant。';
COMMENT ON COLUMN fin_ex_chat_message_t.content IS '消息完整文本内容。assistant 消息只在 run.completed 后保存完整内容。';
COMMENT ON COLUMN fin_ex_chat_message_t.token_count IS '消息 token 数量，供上下文压缩、统计或成本分析使用，可为空。';
COMMENT ON COLUMN fin_ex_chat_message_t.run_id IS '产生该消息的 runId；分支快照消息不复制 run，因此可为空。';
COMMENT ON COLUMN fin_ex_chat_message_t.origin_type IS '消息来源类型，NORMAL 表示当前会话产生，BRANCH_SNAPSHOT 表示从源会话复制的只读快照。';
COMMENT ON COLUMN fin_ex_chat_message_t.locked IS '消息是否只读；分支历史快照为 true，不能编辑、删除或重新生成。';
COMMENT ON COLUMN fin_ex_chat_message_t.source_session_id IS '分支快照来源会话 ID；普通消息为空。';
COMMENT ON COLUMN fin_ex_chat_message_t.source_message_id IS '分支快照来源消息 ID；普通消息为空。';
COMMENT ON COLUMN fin_ex_chat_message_t.edited_from_message_id IS '编辑历史 user 消息时，新 user 消息对应的原 user 消息 ID。';
COMMENT ON COLUMN fin_ex_chat_message_t.regenerated_from_message_id IS '重新生成 assistant 回复时，新 assistant 消息对应的原 assistant 消息 ID。';
COMMENT ON COLUMN fin_ex_chat_message_t.metadata_json IS '消息扩展元数据 JSON，保存前端展示或诊断扩展信息。';
COMMENT ON COLUMN fin_ex_chat_message_t.created_at IS '消息创建时间。';

COMMENT ON TABLE fin_ex_chat_message_attachment_t IS '聊天消息附件引用表，保存用户消息与文档库资产的关联事实。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.id IS '消息附件引用主键，业务生成的 attachmentRefId。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.tenant_id IS '租户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.user_id IS '用户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.session_id IS '附件引用所属聊天会话 ID。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.message_id IS '附件引用所属消息 ID。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.document_id IS '被引用的文档库资产 ID。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.attachment_order IS '同一消息内附件展示顺序。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.name IS '附件展示名称快照，通常为原始文件名。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.content_type IS '附件 MIME 类型快照。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.size_bytes IS '附件字节大小快照。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.source_attachment_id IS '分支复制时的来源附件引用 ID；普通附件为空。';
COMMENT ON COLUMN fin_ex_chat_message_attachment_t.created_at IS '附件引用创建时间。';

COMMENT ON TABLE fin_ex_message_feedback_t IS '消息反馈表，保存用户对 assistant 消息的点赞、点踩和原因说明。';
COMMENT ON COLUMN fin_ex_message_feedback_t.id IS '反馈主键，业务生成的 feedbackId。';
COMMENT ON COLUMN fin_ex_message_feedback_t.tenant_id IS '租户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_message_feedback_t.user_id IS '用户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_message_feedback_t.session_id IS '被反馈消息所属会话 ID。';
COMMENT ON COLUMN fin_ex_message_feedback_t.message_id IS '被反馈的 assistant 消息 ID，对应 fin_ex_chat_message_t.id。';
COMMENT ON COLUMN fin_ex_message_feedback_t.run_id IS '反馈关联的 runId，可为空；存在时必须与消息属于同一会话。';
COMMENT ON COLUMN fin_ex_message_feedback_t.rating IS '反馈评级，例如 LIKE、DISLIKE。';
COMMENT ON COLUMN fin_ex_message_feedback_t.reason_code IS '结构化反馈原因编码，便于统计归因。';
COMMENT ON COLUMN fin_ex_message_feedback_t.comment_text IS '用户补充的反馈说明文本。';
COMMENT ON COLUMN fin_ex_message_feedback_t.metadata_json IS '反馈扩展元数据 JSON，保存前端或诊断信息。';
COMMENT ON COLUMN fin_ex_message_feedback_t.created_at IS '反馈创建时间。';
COMMENT ON COLUMN fin_ex_message_feedback_t.updated_at IS '反馈最后更新时间。';

COMMENT ON TABLE fin_ex_chat_run_t IS '单轮聊天运行表，保存一次用户提问对应的后台 run 生命周期事实。';
COMMENT ON COLUMN fin_ex_chat_run_t.id IS 'run 主键，业务生成的 runId，用于 stop、retry、事件关联和排障。';
COMMENT ON COLUMN fin_ex_chat_run_t.tenant_id IS '租户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_chat_run_t.user_id IS '用户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_chat_run_t.session_id IS 'run 所属前端聊天会话 ID。';
COMMENT ON COLUMN fin_ex_chat_run_t.status IS 'run 生命周期状态，包括 RUNNING、CANCELLING、CANCELLED、COMPLETED、FAILED。';
COMMENT ON COLUMN fin_ex_chat_run_t.route_type IS '本轮路由类型，例如 SUB_AGENT、AGENT_RUNTIME、SYSTEM_RESPONSE。';
COMMENT ON COLUMN fin_ex_chat_run_t.agent_code IS '本轮命中的 SubAgent 编码；非 SubAgent 路由时为空。';
COMMENT ON COLUMN fin_ex_chat_run_t.runtime_provider IS '本轮使用的 AgentRuntime provider，例如 relay。';
COMMENT ON COLUMN fin_ex_chat_run_t.runtime_session_id IS 'AgentRuntime 内部会话 ID，用于 Runtime 多轮续接。';
COMMENT ON COLUMN fin_ex_chat_run_t.run_mode IS '本轮消息树写入模式，包括 NEXT、EDIT_USER、REGENERATE_ASSISTANT。';
COMMENT ON COLUMN fin_ex_chat_run_t.parent_message_id IS '本轮 run 挂接的消息树父节点。';
COMMENT ON COLUMN fin_ex_chat_run_t.user_message_id IS '本轮输入对应的用户消息 ID；重新生成时指向原用户消息。';
COMMENT ON COLUMN fin_ex_chat_run_t.assistant_message_id IS 'run.completed 后保存的完整 assistant 消息 ID。';
COMMENT ON COLUMN fin_ex_chat_run_t.first_seq IS 'run.started 持久化后的首个事件序号。';
COMMENT ON COLUMN fin_ex_chat_run_t.last_seq IS '该 run 当前最后一个已持久化事件序号。';
COMMENT ON COLUMN fin_ex_chat_run_t.cancel_reason IS 'run 被 stop 或系统取消时记录的取消原因。';
COMMENT ON COLUMN fin_ex_chat_run_t.started_at IS 'run 开始执行时间。';
COMMENT ON COLUMN fin_ex_chat_run_t.finished_at IS 'run 进入终态时间。';
COMMENT ON COLUMN fin_ex_chat_run_t.metadata_json IS 'run 扩展诊断元数据 JSON，例如 retryOfRunId、路由诊断信息。';
COMMENT ON COLUMN fin_ex_chat_run_t.created_at IS 'run 记录创建时间。';
COMMENT ON COLUMN fin_ex_chat_run_t.updated_at IS 'run 记录最后更新时间。';

COMMENT ON TABLE fin_ex_chat_run_execution_t IS '聊天 run 执行控制面表，保存实例归属、心跳、租约、恢复策略和 fencing token，不承载用户业务状态。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.id IS '执行控制面记录主键，业务生成的 executionId。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.run_id IS '关联的业务 runId，对应 fin_ex_chat_run_t.id；唯一。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.tenant_id IS '租户标识，冗余自 run，用于多租户扫描、排障和恢复负载治理。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.user_id IS '用户标识，冗余自 run，用于用户级排障。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.session_id IS 'run 所属聊天会话 ID，冗余自 run。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.execution_status IS '执行控制面状态，包括 RUNNING、CANCELLING、RECOVERING、COMPLETED、FAILED、CANCELLED。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.owner_instance_id IS '当前拥有该 run 执行权的应用实例运行 ID，由 ApplicationInstanceIdProvider 提供。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.heartbeat_at IS 'owner 实例最后一次刷新心跳的时间。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.lease_until IS 'owner 实例运行租约到期时间；watchdog 以该字段判定 stale run。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.fencing_token IS '写事件栅栏令牌；接管或恢复抢占时递增，用于拒绝旧实例迟到输出。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.recovery_strategy IS '最近一次 stale run 恢复使用的策略，例如 MANUAL_CONFIRMATION、FAIL_FAST、RUNTIME_TAKEOVER。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.recovered_by_instance_id IS '最近一次执行恢复动作的实例运行 ID。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.recovery_attempts IS 'stale run 恢复尝试次数。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.recovery_lease_until IS 'RECOVERING 状态的恢复租约到期时间，防止恢复实例再次挂掉后永久卡住。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.runtime_resume_token IS 'Runtime 可靠断点接管所需的恢复 token；当前 Runtime 不支持时为空。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.metadata_json IS '执行控制面扩展元数据 JSON，用于排障和未来策略扩展。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.created_at IS '执行控制面记录创建时间。';
COMMENT ON COLUMN fin_ex_chat_run_execution_t.updated_at IS '执行控制面记录最后更新时间。';

COMMENT ON SEQUENCE fin_ex_chat_event_seq IS '聊天事件恢复游标序号生成器，由 openGauss 统一生成 seq，供 WebSocket/SSE 断点恢复使用。';

COMMENT ON TABLE fin_ex_chat_event_t IS '聊天事件事实表，保存 run.started、message.delta、run.completed、run.failed、run.cancelled 等流式事件；tenant_id/user_id/session_id/run_id 是防止多用户、多会话串线的事实边界。';
COMMENT ON COLUMN fin_ex_chat_event_t.id IS '事件主键，业务生成的 eventId。';
COMMENT ON COLUMN fin_ex_chat_event_t.tenant_id IS '租户标识，来自服务端身份上下文；SSE/WS 恢复查询必须携带该字段。';
COMMENT ON COLUMN fin_ex_chat_event_t.user_id IS '用户标识，来自服务端身份上下文；SSE/WS 恢复查询必须携带该字段。';
COMMENT ON COLUMN fin_ex_chat_event_t.session_id IS '事件所属聊天会话 ID；写入时必须与 run 所属 session 一致。';
COMMENT ON COLUMN fin_ex_chat_event_t.run_id IS '事件所属 runId，对应 fin_ex_chat_run_t.id；写入时必须与 session、tenant、user 归属一致。';
COMMENT ON COLUMN fin_ex_chat_event_t.seq IS '事件恢复游标序号，由 openGauss sequence 生成；同一会话内按 seq 补发。';
COMMENT ON COLUMN fin_ex_chat_event_t.event_type IS '事件类型，例如 run.started、message.delta、message.completed、run.completed、run.failed、run.cancelled。';
COMMENT ON COLUMN fin_ex_chat_event_t.payload_json IS '事件载荷 JSON，保存前端可消费的 delta、状态和诊断字段。';
COMMENT ON COLUMN fin_ex_chat_event_t.created_at IS '事件创建并落库时间。';

COMMENT ON TABLE fin_ex_chat_read_cursor_t IS '聊天事件消费游标表，保存用户在会话中已经处理完成的最大事件 seq，用于展示消费进度、诊断和非 active 场景减少重复补发。';
COMMENT ON COLUMN fin_ex_chat_read_cursor_t.id IS '游标记录主键，业务生成的 cursorId。';
COMMENT ON COLUMN fin_ex_chat_read_cursor_t.tenant_id IS '租户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_chat_read_cursor_t.user_id IS '用户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_chat_read_cursor_t.session_id IS '游标所属聊天会话 ID。';
COMMENT ON COLUMN fin_ex_chat_read_cursor_t.last_consumed_seq IS '当前用户已确认消费的最大事件序号；写入必须单调递增。';
COMMENT ON COLUMN fin_ex_chat_read_cursor_t.updated_at IS '游标最后更新时间。';

COMMENT ON TABLE fin_ex_uploaded_document_t IS '用户文档库表，保存上传文件的对象存储位置、处理状态和可引用元数据。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.id IS '文档主键，业务生成的 documentId。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.tenant_id IS '租户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.user_id IS '用户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.session_id IS '文档关联的聊天会话 ID，可为空；为空表示用户文档库资产。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.original_name IS '用户上传或文档库展示的原始文件名。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.bucket IS '对象存储 bucket 名称。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.object_key IS '对象存储中的对象 key。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.content_type IS '文档 MIME 类型。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.size_bytes IS '文档字节大小。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.status IS '文档状态，例如 AVAILABLE、PROCESSING、FAILED、DELETED。只有 AVAILABLE 可作为聊天附件。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.source IS '文档来源，例如 LOCAL_UPLOAD、LIBRARY。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.token_size IS '文档解析后的 token 数量，供上下文预算和检索使用。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.metadata_json IS '文档扩展元数据 JSON，例如存储 provider、来源会话或处理诊断。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.created_at IS '文档记录创建时间。';
COMMENT ON COLUMN fin_ex_uploaded_document_t.updated_at IS '文档记录最后更新时间。';

COMMENT ON TABLE fin_ex_runtime_binding_t IS 'Runtime 续接绑定表，保存会话到 Relay Runtime 内部 session 的多轮绑定关系。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.id IS '绑定主键，业务生成的 bindingId。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.tenant_id IS '租户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.user_id IS '用户标识，来自服务端身份上下文。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.chat_session_id IS '前端聊天会话 ID，对应 fin_ex_chat_session_t.id。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.provider IS 'AgentRuntime provider 编码，例如 relay。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.leaf_message_id IS '该 Runtime 内部会话对应的前端消息树叶子，避免历史编辑后复用错误上下文。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.runtime_session_id IS 'AgentRuntime 内部会话 ID，用于后续请求续接上下文。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.status IS '绑定状态，例如 ACTIVE、CANCELLED、EXPIRED。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.last_run_id IS '最近一次使用该绑定的 runId。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.expires_at IS '绑定过期时间，超过后不再自动续接。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.metadata_json IS '绑定扩展元数据 JSON，保存 provider 诊断或运行信息。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.created_at IS '绑定创建时间。';
COMMENT ON COLUMN fin_ex_runtime_binding_t.updated_at IS '绑定最后更新时间。';
