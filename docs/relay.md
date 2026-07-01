# RelayAgent WebSocket 接口文档

## 1. 接口链接

```
ws://{host}:{port}/ws/{client_id}
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| host | string | 是 | 服务地址，默认 `127.0.0.1`；外部访问需启动时指定 `--host 0.0.0.0` |
| port | int | 是 | 服务端口，默认 `8080` |
| client_id | string | 是 | 客户端唯一标识，建议使用 UUID |

---

## 2. 入参（客户端发送的消息）

### 2.1 config — 初始化会话

建立 WS 连接后，**必须首先发送此消息**以初始化会话。

**新建会话：**

```json
{
  "type": "config",
  "config": {
    "sessionMode": "new",
    "sessionId": "17ef2ed5-6a12-4d2a-8f4b-c9f1e3ae6ef7",
    "uid": "xxx"
  }
}
```

**恢复会话：**

```json
{
  "type": "config",
  "config": {
    "sessionMode": "resume",
    "sessionId": "17ef2ed5-6a12-4d2a-8f4b-c9f1e3ae6ef7",
    "uid": "xxx"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 `"config"` |
| config.sessionMode | string | 是 | `"new"` 新建会话 / `"resume"` 恢复已有会话 |
| config.sessionId | string | 否 | 会话 ID；`new` 模式下若提供则使用该 ID，否则后端自动生成 UUID；`resume` 模式下必填，用于指定要恢复的会话 |
| config.uid | string | 否 | 用户工号，用于用户隔离；后端存入 `ctx.uid`，可用于会话归属标识 |
| config.appMode | string | 否 | 运行模式：`delegate`(默认) / `roleplay` / `solo` / `groupchat` / `guarded` |

> **注意**：`project_home` 无需传入，后端自动使用默认路径 `~/tmp/xxx`。

### 2.2 user-message — 发送用户消息

会话初始化完成后发送。

```json
{
  "type": "user-message",
  "content": "你的问题"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 `"user-message"` |
| content | string | 是 | 用户输入内容 |

> **注意**：`app_mode` 无需传入，后端默认使用 `delegate` 模式。

### 2.3 approval-result — 回复审批/澄清问

当收到 `approval-request` 事件后，通过此消息回复用户选择。

```json
{
  "type": "approval-result",
  "approval_id": "c95ceb53-f66f-4da2-a3ba-2a190aec5d6f",
  "approved": true,
  "scope": "once",
  "questionnaire_answers": {
    "问题文本": "用户选择的选项"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 `"approval-result"` |
| approval_id | string | 是 | 对应 `approval-request` 中的 `approval_id` |
| approved | bool | 是 | 是否批准 |
| scope | string | 否 | `"once"` 单次 |
| questionnaire_answers | object | 否 | 澄清问的答案，key 为问题文本，value 为选项 |

---

## 3. 出参（服务端推送的事件汇总）

所有事件均为 JSON 对象，通过 `type` 字段区分。公共字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| type | string | 事件类型 |
| timestamp | string | ISO 8601 时间戳 |
| session_id | string | 会话 ID |
| version_id | int | 事件版本号（递增，可用于事件重放排序） |

### 3.1 初始化阶段事件

#### relay-start — 启动开始

```json
{
  "type": "relay-start",
  "instance_id": "relay_xxx_xxx",
  "content": "Initializing Relay Agent...",
  "timestamp": "...",
  "version_id": 2
}
```

#### relay-progress — 启动进度

```json
{
  "type": "relay-progress",
  "instance_id": "relay_xxx_xxx",
  "content": "Loading agent configurations...",
  "timestamp": "..."
}
```

| 字段 | 说明 |
|------|------|
| content | 进度描述文本，如 `"Creating application configuration..."` / `"Registering 21 skills..."` / `"✓ eurekax_chat MCP connected (42 tools)"` |

#### relay-end — 启动完成

```json
{
  "type": "relay-end",
  "instance_id": "relay_xxx_xxx",
  "status": "ready",
  "content": "Agent system ready!",
  "mcp_status": {
    "success": [{"server_name": "eurekax_chat", "tool_count": 42, "status": "connected"}],
    "failures": [],
    "total": 1,
    "success_count": 1,
    "failure_count": 0
  },
  "is_initialization": true,
  "timestamp": "...",
  "version_id": 3
}
```

| 字段 | 说明 |
|------|------|
| status | `"ready"` |
| mcp_status | MCP 连接状态汇总 |
| is_initialization | 是否首次初始化 |

#### project_home — 项目路径

```json
{
  "type": "project_home",
  "project_home": "~/tmp/xxx",
  "timestamp": "..."
}
```

#### available-modes — 可用模式列表

```json
{
  "type": "available-modes",
  "modes": [
    {"value": "delegate", "label": "通用助手", "description": "...", "source": "builtin"},
    {"value": "roleplay", "label": "角色扮演", "description": "...", "source": "builtin"},
    {"value": "solo", "label": "单 agent", "description": "...", "source": "builtin"},
    {"value": "groupchat", "label": "群聊模式", "description": "...", "source": "builtin"},
    {"value": "guarded", "label": "超长任务", "description": "...", "source": "plugin:guard_mode"}
  ],
  "timestamp": "..."
}
```

---

### 3.2 Agent 执行阶段事件

#### user — 用户消息回显

```json
{
  "type": "user",
  "content": "用户输入内容",
  "timestamp": "...",
  "images": [],
  "version_id": 1
}
```

#### agent-call — Agent 调用生命周期

```json
{
  "type": "agent-call",
  "agent_name": "delegate_agent_xxx",
  "is_start": true,
  "timestamp": "...",
  "instance_id": "delegate_agent_xxx_yyy",
  "parent_id": "relay-system-root",
  "agent_id": "delegate_agent_xxx",
  "task": "用户任务描述",
  "model_name": "maas-glm-5-aliyun-codeagent",
  "model_id": "maas-glm-5-aliyun-codeagent",
  "session_id": "...",
  "version_id": 4
}
```

| 字段 | 说明 |
|------|------|
| is_start | `true` 开始 / `false` 结束 |
| instance_id | 前端时间线渲染用（每次调用唯一，不可重用） |
| agent_id | Runtime 行为用（可重入复用，如 cancel/resume） |
| task | Agent 接收的任务描述（仅 is_start=true 时有） |
| end_timestamp | 结束时间（仅 is_start=false 时有） |

#### agent-reasoning — Agent 推理过程

```json
{
  "type": "agent-reasoning",
  "agent_name": "PlanAgent",
  "thought": "I need to analyze the code structure...",
  "is_start": true,
  "timestamp": "..."
}
```

| 字段 | 说明 |
|------|------|
| is_start | `true` 推理开始 / `false` 推理结束 |
| thought | 推理内容描述 |

#### thinking-operation-start — 思考开始

```json
{
  "type": "thinking-operation-start",
  "agent_name": "delegate_agent_xxx",
  "operation_id": "delegate_agent_xxx_xxx_f3d38abd",
  "available_tools": ["mcp__eurekax_chat__eurekaChat", "execute_command", "read", "..."],
  "timestamp": "...",
  "instance_id": "...",
  "version_id": 5,
  "prompt_omitted": true,
  "prompt_size": 76360
}
```

| 字段 | 说明 |
|------|------|
| operation_id | 本次思考操作的唯一标识 |
| available_tools | Agent 可用的工具列表 |
| prompt_omitted | prompt 是否省略（大 prompt 不传输） |
| prompt_size | prompt 字符数 |

#### thinking-content-update — 思考内容流式更新

```json
{
  "type": "thinking-content-update",
  "agent_name": "delegate_agent_xxx",
  "operation_id": "delegate_agent_xxx_xxx_f3d38abd",
  "content": "用户",
  "timestamp": "..."
}
```

| 字段 | 说明 |
|------|------|
| content | 流式输出的思考片段，前端需按 operation_id 拼接 |

#### thinking-operation-end — 思考结束

```json
{
  "type": "thinking-operation-end",
  "agent_name": "delegate_agent_xxx",
  "operation_id": "delegate_agent_xxx_xxx_f3d38abd",
  "input_tokens": 60154,
  "output_tokens": 244,
  "reasoning_tokens": 0,
  "cached_tokens": 0,
  "output": "",
  "end_timestamp": "...",
  "tool_call_count": 1,
  "has_tool_calls": true,
  "tool_call_ids": ["tool-59d1ba0b76f54f239679558d654ba792"],
  "timestamp": "...",
  "reasoning_content": "完整的思考内容文本",
  "instance_id": "...",
  "model_id": "maas-glm-5-aliyun-codeagent",
  "version_id": 7
}
```

| 字段 | 说明 |
|------|------|
| input_tokens / output_tokens | Token 用量 |
| tool_call_count | 本次思考决定调用的工具数 |
| tool_call_ids | 工具调用 ID 列表 |
| reasoning_content | 完整的思考内容（非流式） |

#### agent — Agent 文本输出（流式）

```json
{
  "type": "agent",
  "agent_name": "delegate_agent_xxx",
  "content": "我将使用",
  "is_streaming": true,
  "timestamp": "...",
  "operation_id": "...",
  "instance_id": "...",
  "session_id": "...",
  "version_id": 6
}
```

| 字段 | 说明 |
|------|------|
| content | 流式文本片段，前端按 instance_id + operation_id 拼接 |
| is_streaming | `true` 流式中间片段 / `false` 最终完整文本 |

---

### 3.3 工具调用阶段事件

#### tool-call-streaming — 工具调用参数流式输出

```json
{
  "type": "tool-call-streaming",
  "agent_name": "delegate_agent_xxx",
  "tool_name": "mcp__eurekax_chat__eurekaChat",
  "tool_id": "tool-59d1ba0b76f54f239679558d654ba792",
  "operation_id": "...",
  "input_preview": "",
  "timestamp": "...",
  "session_id": "..."
}
```

| 字段 | 说明 |
|------|------|
| tool_name | 工具名称，MCP 工具格式为 `mcp__{server}__{tool}` |
| tool_id | 工具调用唯一标识 |
| input_preview | 首次为空，第二次携带完整参数 JSON |

#### tool-execution — 工具执行生命周期

**开始事件：**

```json
{
  "type": "tool-execution",
  "agent_name": "delegate_agent_xxx",
  "tool_name": "mcp__eurekax_chat__eurekaChat",
  "is_start": true,
  "timestamp": "...",
  "tool_id": "tool-59d1ba0b76f54f239679558d654ba792",
  "parent_instance_id": "delegate_agent_xxx_yyy",
  "model_id": "maas-glm-5-aliyun-codeagent",
  "display_name": "eurekaChat",
  "args_summary": "{\"query\": \"财务三表的定义\"}",
  "thinking_operation_id": "...",
  "session_id": "...",
  "version_id": 8
}
```

**结束事件：**

```json
{
  "type": "tool-execution",
  "agent_name": "delegate_agent_xxx",
  "tool_name": "mcp__eurekax_chat__eurekaChat",
  "is_start": false,
  "timestamp": "...",
  "tool_id": "tool-59d1ba0b76f54f239679558d654ba792",
  "parent_instance_id": "delegate_agent_xxx_yyy",
  "model_id": "maas-glm-5-aliyun-codeagent",
  "display_name": "eurekaChat",
  "result_summary": "工具返回结果文本（可能很长）",
  "session_id": "...",
  "version_id": 9
}
```

| 字段 | 说明 |
|------|------|
| is_start | `true` 开始 / `false` 结束 |
| display_name | 工具显示名 |
| args_summary | 调用参数 JSON（仅 is_start=true） |
| result_summary | 工具返回结果摘要（仅 is_start=false） |

#### tool-structured-result — 工具结构化结果（MCP 专用）

MCP 工具返回 `structuredContent` 时，除 `tool-execution` 外还会推送此事件。**可能推送多条**（按 index 递增）。

```json
{
  "type": "tool-structured-result",
  "agent_name": "delegate_agent_xxx",
  "tool_name": "mcp__eurekax_chat__eurekaChat",
  "timestamp": "...",
  "tool_id": "tool-59d1ba0b76f54f239679558d654ba792",
  "parent_instance_id": "delegate_agent_xxx_yyy",
  "result_data": {
    "widget": { "data": { "traceId": "xxx" } },
    "index": 0,
    "total": 1112,
    "is_last": false
  },
  "session_id": "..."
}
```

| 字段 | 说明 |
|------|------|
| result_data | 结构化结果数据 |
| result_data.widget | 前端渲染用的 widget 数据 |
| result_data.index | 当前分片序号（从 0 开始） |
| result_data.total | 总分片数 |
| result_data.is_last | 是否最后一个分片 |

**典型 result_data.widget.data 结构（eurekaChat）：**

| 阶段 | widget.data 内容 |
|------|------------------|
| 初始化 | `{"traceId": "xxx"}` |
| 意图识别 | `{"processResult": {"dynamicResponse": [...], "fixedResponse": "识别到以下意图"}}` |
| 搜索中 | `{"state": "SEARCHING", "stateDesc": "搜索中"}` |
| 关键信息 | `{"processResult": {"dynamicResponse": [{"title": "财务三表", "type": "slot"}], "fixedResponse": "识别到以下关键信息"}}` |
| 搜索结果 | `{"searchList": [{...检索结果条目...}]}` |

---

### 3.4 Plan 管理事件

#### plan-update — 计划更新

```json
{
  "type": "plan-update",
  "agent_name": "PlanAgent",
  "plan_text": "1. Analyze code\n2. Generate report",
  "tasks": [
    {"task": "Analyze code", "status": "pending"},
    {"task": "Generate report", "status": "pending"}
  ],
  "timestamp": "..."
}
```

| 字段 | 说明 |
|------|------|
| plan_text | 计划文本 |
| tasks | 子任务列表，每个含 task + status |

#### subagent-plan-created — Sub-Agent 计划创建

```json
{
  "type": "subagent-plan-created",
  "agent_name": "developer_abc123",
  "plan_name": "Development Plan",
  "subtasks": [
    {"name": "Setup environment", "status": "pending"},
    {"name": "Write code", "status": "pending"}
  ],
  "timestamp": "..."
}
```

#### subagent-subtask — 子任务状态更新

```json
{
  "type": "subagent-subtask",
  "subtask_id": "subtask_1",
  "subtask_idx": 0,
  "subtask_name": "Analyze code",
  "status": "in_progress",
  "outcome": "Success",
  "timestamp": "..."
}
```

---

### 3.5 审批/澄清问阶段事件

#### approval-request — 审批请求/澄清问

```json
{
  "type": "approval-request",
  "approval_id": "c95ceb53-f66f-4da2-a3ba-2a190aec5d6f",
  "operation_type": "questionnaire",
  "target": "",
  "risk_level": "LOW",
  "timestamp": "...",
  "agent_name": "delegate_agent_xxx",
  "parent_instance_id": "...",
  "mode": "questionnaire",
  "message": "Please answer the following questions",
  "questions": [
    {
      "question": "你对架构设计三要素中的哪个最感兴趣？",
      "options": [
        {"label": "组件划分", "description": "如何合理分解系统..."},
        {"label": "连接关系", "description": "组件间如何通信..."},
        {"label": "约束规范", "description": "架构原则、分层规则..."}
      ]
    }
  ],
  "metadata": {
    "confirmation_type": "questionnaire",
    "questions": [...]
  },
  "version_id": 9
}
```

| 字段 | 说明 |
|------|------|
| approval_id | 审批唯一标识，回复时需携带 |
| operation_type | `"questionnaire"` 澄清问 |
| mode | `"questionnaire"` |
| questions | 问题列表，每个问题含 question + options |
| risk_level | 风险等级 |

#### approval-result — 审批结果（服务端确认）

客户端发送 `approval-result` 入参后，服务端也会推送此事件确认。

```json
{
  "type": "approval-result",
  "approval_id": "c95ceb53-f66f-4da2-a3ba-2a190aec5d6f",
  "request_id": "c95ceb53-f66f-4da2-a3ba-2a190aec5d6f",
  "approved": true,
  "scope": "once",
  "timestamp": "...",
  "auto_approved": false,
  "resolved_by": null,
  "resolved_at": "...",
  "questionnaire_answers": {
    "你对架构设计三要素中的哪个最感兴趣？": "约束规范"
  },
  "version_id": 10
}
```

#### clarified-query — 澄清后的查询

```json
{
  "type": "clarified-query",
  "clarified_query": "我对架构设计三要素中的约束规范最感兴趣...",
  "original_query": "告诉架构设计三要素是啥，然后问我对哪个感兴趣",
  "clarification_rounds": 1,
  "agent_name": "delegate_agent_xxx",
  "session_id": "...",
  "timestamp": "...",
  "version_id": 15
}
```

---

### 3.6 响应输出事件

#### generate-response — 最终响应

```json
{
  "type": "generate-response",
  "agent_name": "delegate_agent_xxx",
  "content": "CLARIFIED: 我对架构设计三要素中的约束规范最感兴趣...",
  "is_final": true,
  "timestamp": "...",
  "instance_id": "...",
  "session_id": "...",
  "version_id": 16
}
```

| 字段 | 说明 |
|------|------|
| content | Agent 最终输出的完整文本 |
| is_final | 是否最终响应 |

---

### 3.7 Token 与会话状态事件

#### token-update — Token 用量更新

```json
{
  "type": "token-update",
  "agent_name": "PlanAgent",
  "input_tokens": 1500,
  "output_tokens": 800,
  "model": "gpt-4",
  "timestamp": "..."
}
```

| 字段 | 说明 |
|------|------|
| input_tokens | 输入 Token 数 |
| output_tokens | 输出 Token 数 |
| model | 使用的模型 |

#### session-state — 会话状态变更

```json
{
  "type": "session-state",
  "state": "waiting_user_input",
  "detail": "Waiting for your next message",
  "timestamp": "..."
}
```

| state 值 | 说明 |
|-----------|------|
| `ready` / `running` / `idle` | 初始化完成 |
| `agent_thinking` | Agent 正在处理 |
| `waiting_user_input` | 等待用户输入（如澄清问回复） |
| `completed` | 对话完成 |

---

### 3.8 其他事件

#### self-evolution-status — 自演化状态

```json
{
  "type": "self-evolution-status",
  "data": {
    "kind": "skill_updated",
    "trigger_type": "sub_agent_completion",
    "summary": "💾 Skill updated",
    "session_id": "...",
    "run_id": "selfevo_xxx",
    "project_home_ref": "project:D:/...",
    "target_skill": null,
    "link_path": "/api/self-evolution/runs/xxx/closed-loop",
    "counter_snapshot": {"turns_since_memory": 0, "iters_since_skill": 0}
  }
}
```

#### session-mismatch — 会话路径不匹配

Resume 时，若客户端传入的 `project_home` 与存储值不一致，后端推送此事件。

```json
{
  "type": "session-mismatch",
  "session_id": "17ef2ed5-...",
  "expected": "~/tmp/xxx",
  "got": "/other/path",
  "timestamp": "..."
}
```

#### clear-session — 清除前端会话

会话不存在或已损坏时，后端要求前端清除状态。

```json
{
  "type": "clear-session",
  "reason": "session_not_found",
  "message": "Session 17ef2ed5... not found or corrupted",
  "timestamp": "..."
}
```

---

## 4. 典型事件流时序

### 4.1 MCP 工具调用场景

```
客户端                          服务端
  │                               │
  │──── config ──────────────────→│
  │←─── user ────────────────────│  (回显)
  │←─── relay-start ─────────────│
  │←─── relay-progress (×8) ─────│
  │←─── project_home ────────────│
  │←─── available-modes ─────────│
  │←─── relay-end ───────────────│
  │                               │
  │──── user-message ────────────→│
  │←─── agent-call (start) ──────│
  │←─── thinking-operation-start ─│
  │←─── thinking-content-update (×N) ──│  (流式思考)
  │←─── agent (streaming ×N) ────│  (流式文本)
  │←─── tool-call-streaming (×2) ─│  (工具参数)
  │←─── agent (is_streaming=false) ──│  (完整文本)
  │←─── thinking-operation-end ──│
  │←─── tool-execution (start) ──│
  │←─── tool-execution (end) ────│  (工具结果)
  │←─── tool-structured-result (×N) ──│  (结构化结果，可能很多条)
  │←─── generate-response ───────│
  │←─── agent-call (end) ────────│
  │←─── session-state (idle) ────│
  │←─── self-evolution-status (×2) ──│
```

### 4.2 澄清问场景

```
客户端                          服务端
  │                               │
  │──── config ──────────────────→│
  │←─── (初始化事件同上) ─────────│
  │                               │
  │──── user-message ────────────→│
  │←─── agent-call (start) ──────│
  │←─── thinking-operation-start ─│
  │←─── thinking-content-update (×N) ──│
  │←─── agent (streaming ×N) ────│
  │←─── tool-call-streaming (×2) ─│  (ask_user 工具)
  │←─── thinking-operation-end ──│
  │←─── tool-execution (start) ──│
  │←─── approval-request ────────│  ★ 澄清问，需用户回复
  │                               │
  │──── approval-result ────────→│  ★ 用户回复选择
  │←─── session-state (agent_thinking) ──│
  │←─── approval-result ────────│  (服务端确认)
  │←─── tool-execution (end) ────│
  │←─── thinking-operation-start ─│  (第二轮思考)
  │←─── thinking-content-update (×N) ──│
  │←─── agent (streaming ×N) ────│
  │←─── thinking-operation-end ──│
  │←─── clarified-query ─────────│  ★ 澄清后的查询
  │←─── generate-response ───────│
  │←─── agent-call (end) ────────│
  │←─── session-state (waiting_user_input) ──│
  │←─── self-evolution-status (×3) ──│
```

---

## 5. 事件类型速查表

| 事件类型 | 阶段 | 说明 |
|----------|------|------|
| `user` | 全局 | 用户消息回显 |
| `relay-start` | 初始化 | Agent 系统启动 |
| `relay-progress` | 初始化 | 启动进度 |
| `relay-end` | 初始化 | 启动完成 |
| `project_home` | 初始化 | 项目路径通知 |
| `available-modes` | 初始化 | 可用模式列表 |
| `agent-call` | 执行 | Agent 生命周期（start/end） |
| `agent-reasoning` | 执行 | Agent 推理过程（start/end） |
| `thinking-operation-start` | 思考 | 思考开始 |
| `thinking-content-update` | 思考 | 思考内容流式片段 |
| `thinking-operation-end` | 思考 | 思考结束 |
| `agent` | 输出 | Agent 文本流式输出 |
| `tool-call-streaming` | 工具 | 工具参数流式输出 |
| `tool-execution` | 工具 | 工具执行生命周期（start/end） |
| `tool-structured-result` | 工具 | MCP 结构化结果（可能多条） |
| `plan-update` | Plan | 计划更新 |
| `subagent-plan-created` | Plan | Sub-Agent 计划创建 |
| `subagent-subtask` | Plan | 子任务状态更新 |
| `approval-request` | 审批 | 审批请求/澄清问 |
| `approval-result` | 审批 | 审批结果确认 |
| `clarified-query` | 审批 | 澄清后的查询 |
| `generate-response` | 输出 | 最终响应 |
| `token-update` | Token | Token 用量更新 |
| `session-state` | 全局 | 会话状态变更 |
| `self-evolution-status` | 全局 | 自演化状态通知 |
| `session-mismatch` | 异常 | Resume 时 project_home 不匹配 |
| `clear-session` | 异常 | 会话不存在，要求前端清除状态 |