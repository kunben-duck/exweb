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
    "uid": "xxx",
    "supports_incremental_recovery": true
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 `"config"` |
| config.sessionMode | string | 是 | `"new"` 新建会话 / `"resume"` 恢复已有会话 |
| config.sessionId | string | 否 | 会话 ID；`new` 模式下若提供则使用该 ID，否则后端自动生成 UUID；`resume` 模式下必填，用于指定要恢复的会话 |
| config.uid | string | 否 | 用户工号，用于用户隔离；后端存入 `ctx.uid`，可用于会话归属标识 |
| config.appMode | string | 否 | 运行模式：普通委托为 `delegate`（默认），ChatService 专家模式为 `domain_expert` |
| config.roleName | string | 专家模式必填 | Domain Expert角色，来自ChatService可信路由或Binding；Delegate省略 |
| config.supports_incremental_recovery | bool | 否 | 是否支持增量恢复；`true` 时服务端发送 `session-init(mode="incremental")`，客户端通过 `get-incremental-events` 查询驱动历史恢复；`false` 或缺省时走 legacy 全量回放路径 |

> **注意**：`project_home` 无需传入，后端自动使用默认路径 `~/tmp/xxx`。

### 2.2 user-message — 发送用户消息

会话初始化完成后发送。

```json
{
  "type": "user-message",
  "content": "你的问题",
  "metadata": {
    "clientTraceId": "trace-xxx"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 `"user-message"` |
| content | string | 是 | 用户输入内容 |
| metadata | object | 否 | 业务扩展元数据；ChatService 会从 `AgentRuntimeRequest.metadata()` 透传非敏感字段，过滤 Cookie、token、Authorization、secret、password 等敏感 key |

> **注意**：`app_mode` 无需传入，后端默认使用 `delegate` 模式。

ChatService 在 Intent 的规范化 `accessName` 区分大小写精确命中
`financeex.intent.sensitive-information-access-name` 时，也使用本节完全相同的 Delegate
`config + user-message` 协议。该规则不增加新的 Relay Profile，敏感信息任务与普通 Delegate
任务复用匹配的 `RESUMABLE` Binding；公开 `intent-result` 仍保留原始 `ROUTE_SINGLE` 和敏感意图信息。
敏感信息 run 仍实时输出并保存 `message.delta/message.snapshot`，同时保留 `session-ready/session-state`
和 questionnaire `approval-request`。其他 Relay 过程事件在进入公共 Event 管线前丢弃，不推送、
不落库且不生成历史 Parts。该输出模式只属于当前 run，不写入 Binding；复用同一 Delegate Binding
的普通 run 继续输出完整事件流。

> **ChatService 集成边界**：前端 `/v1/chat/runs.agentMode` 仅记录在 active DomainAgent Binding，
> 不写入 Relay Binding，也不映射到 Relay `config`、`user-message.metadata` 或 `approval-response`。
> `config.appMode` 是 Relay 自身运行配置，与前端 `agentMode` 无关。完整规则参见
> [AgentMode 仅记录技术设计](architecture/agent-mode-recording.md)。

### 2.2.1 chat_expert — Domain Expert 专家问答

当 Intent 的规范化 `accessName` 区分大小写命中 ChatService 配置的专家前缀时，ChatService
移除一次该前缀，将剩余后缀作为动态 `roleName`，不发送 `user-message`，而是发送：

```json
{
  "type": "chat_expert",
  "roleName": "system-awareness",
  "content": "资产负债率怎么计算？",
  "messages": [],
  "traceId": "trace_xxx",
  "metadata": {}
}
```

专家 NEW、RESUME、Interaction 和临时 Stop 的 Config 都发送 Binding 或Run可信档案中的`roleName`；正常业务帧继续发送相同字段和相同值。同一角色复用匹配的专家会话，不同角色分别创建 Binding，不能交叉恢复。`messages`、`traceId`、安全过滤后的
`metadata` 及 WebSocket Cookie Header 与普通问答沿用同一透传规则；当前问题只出现在 `content`。
专家模式和 Delegate 使用独立的可恢复 Binding，不交叉复用 Runtime session。

### 2.3 approval-response — 回复问卷

收到 `approval-request(operation_type=questionnaire)` 后，通过此消息提交单选、多选或自定义文本。

```json
{
  "type": "approval-response",
  "request_id": "c95ceb53-f66f-4da2-a3ba-2a190aec5d6f",
  "approved": true,
  "scope": "once",
  "questionnaire_answers": {
    "label": {
      "请选择技术方案": "方案A",
      "请选择部署环境": ["开发环境", "测试环境"]
    },
    "ignore": false
  }
}
```

忽略问卷：

```json
{
  "type": "approval-response",
  "request_id": "c95ceb53-f66f-4da2-a3ba-2a190aec5d6f",
  "approved": false,
  "scope": "once",
  "questionnaire_answers": {
    "ignore": true
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 `"approval-response"` |
| request_id | string | 是 | 值取自对应 `approval-request` 中的 `approval_id` |
| approved | bool | 是 | 是否批准 |
| scope | string | 是 | 当前固定为 `"once"` |
| questionnaire_answers | object | 是 | 正常回答包含 `label` 和 `ignore=false`；忽略问卷只包含 `ignore=true` |

> ChatService 不发送 `approval_id`、扁平答案、`metadata` 或 `timestamp`。完整问卷协议以
> [ask_user 交互文档](relay-clarify.md)为准。

### 2.4 interrupt / pause — 中断当前轮次

中断当前正在执行的 Agent 轮次（包括工具调用和同步 Sub-Agent），但**不影响**后台 Agent。

```json
{
  "type": "interrupt"
}
```

或等价的：

```json
{
  "type": "pause"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | `"interrupt"` 或 `"pause"` |

> **效果**：调用 `RelayApplication.interrupt_turn(reason="user_pause")`，取消当前轮次作用域，级联取消工具调用和同步 Sub-Agent，并终止已追踪的子进程。

### 2.5 stop_agent — 停止指定后台 Agent

```json
{
  "type": "stop_agent",
  "agent_id": "developer_abc123"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 `"stop_agent"` |
| agent_id | string | 是 | 要停止的 Agent 的 **agent_id**（Runtime 身份，非 instance_id） |

> **注意**：`agent_id` 是 Runtime 行为身份（可重入），不是前端时间线的 `instance_id`。详见 §2.8 Agent Identity。

### 2.6 stop_all_agents — 停止所有 Agent

停止所有后台 Agent + 当前活跃轮次。

```json
{
  "type": "stop_all_agents"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 `"stop_all_agents"` |

> **效果**：取消当前 WebSocket 任务，为所有活跃 invocation 发送 `agent-call` END 事件，然后调用 `RelayApplication.interrupt_all_background_agents()`。

### 2.7 stop_compression — 停止压缩 Agent

停止当前正在执行的压缩 Agent；若无压缩 Agent 在运行，则回退为 `interrupt` 行为。

```json
{
  "type": "stop_compression"
}
```

### 2.8 cancel-initialization — 取消初始化

在 MCP 初始化阶段取消初始化过程。

```json
{
  "type": "cancel-initialization"
}
```

### 2.9 get-incremental-events — 增量事件查询（断线续传）

客户端收到 `session-init(mode="incremental")` 后，通过此消息从服务端持久化存储中查询历史/遗漏事件。**支持正向（增量恢复）和反向（向上翻页）两种模式**。

**正向模式（增量恢复 / 断线续传）：**

```json
{
  "type": "get-incremental-events",
  "session_id": "17ef2ed5-6a12-4d2a-8f4b-c9f1e3ae6ef7",
  "since_version": 3,
  "limit": 500,
  "compact": true
}
```

**反向模式（向上滚动翻页）：**

```json
{
  "type": "get-incremental-events",
  "session_id": "17ef2ed5-6a12-4d2a-8f4b-c9f1e3ae6ef7",
  "before_version": 10,
  "limit": 50
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 `"get-incremental-events"` |
| session_id | string | 是 | 要查询的会话 ID，必须与当前连接的会话一致 |
| since_version | int | 否 | 正向模式：从哪个 `version_id` 之后开始查询（含），默认 0 表示从头开始；**断线续传时传入断线前最后收到的 `version_id`** |
| before_version | int | 否 | 反向模式：查询此 `version_id` 之前的消息（用于向上滚动翻页）；设置此参数时进入反向模式 |
| limit | int | 否 | 每页最大消息数，默认 500 |
| compact | bool | 否 | 是否压缩传输（去除冗余字段如大 prompt），默认 false |

> **断线续传关键**：`since_version` 的值应取自断线前最后收到的持久化事件的 `version_id`。服务端返回该值之后的所有持久化事件，确保无数据丢失。
>
> **注意**：`since_version` 和 `before_version` 互斥，同时设置时 `before_version` 优先（进入反向模式）。

---

## 3. 出参（服务端推送的事件汇总）

所有事件均为 JSON 对象，通过 `type` 字段区分。公共字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| type | string | 事件类型 |
| timestamp | string | ISO 8601 时间戳 |
| session_id | string | 会话 ID |
| version_id | int | 事件版本号（仅持久化事件有此字段，见下方说明） |

**`version_id` 语义说明：**

| 特性 | 说明 |
|------|------|
| 单调递增 | 每个 session 内 `version_id` 严格递增，不回退、不跳跃（持久化事件之间） |
| 作用域 | per-session，不同 session 的 `version_id` 独立 |
| 仅持久化事件有 | 短暂事件（`token-update`、`thinking-content-update`、`tool-call-streaming`、`tool-structured-result`、`session-state`、`heartbeat-response`）**没有** `version_id`，不可通过增量恢复获取 |
| 进程重启安全 | 服务端重启后 `version_id` 从磁盘最大值继续递增，不会重置 |
| 续传游标 | 断线续传时，客户端传入 `since_version` = 断线前最后收到的 `version_id`，服务端返回该值之后的所有持久化事件 |

### 3.1 初始化阶段事件

#### system — 系统消息

```json
{
  "type": "system",
  "content": "Establishing connection...",
  "level": "debug",
  "timestamp": "..."
}
```

| 字段 | 说明 |
|------|------|
| content | 消息内容，如 `"Establishing connection..."` / `"Connected to session xxx..."` / `"Ready to chat! Session ID: xxx"` |
| level | 日志级别，通常为 `"debug"` |

#### project_home — 项目路径

```json
{
  "type": "project_home",
  "project_home": "~/tmp/xxx",
  "timestamp": "..."
}
```

#### session-id — 会话 ID 确认

```json
{
  "type": "session-id",
  "session_id": "17ef2ed5-6a12-4d2a-8f4b-c9f1e3ae6ef7",
  "topic": null,
  "tags": {},
  "timestamp": "..."
}
```

| 字段 | 说明 |
|------|------|
| session_id | 服务端确认的会话 ID |
| topic | 会话主题（resume 时可能从持久化恢复） |
| tags | 会话标签（resume 时可能从持久化恢复） |

> **注意**：`session-id` 在初始化阶段可能发送两次（`initialize_session` 一次，`finalize_session_state` 一次），以第二次为准。

#### session-init — 增量恢复开始（仅 resume + `supports_incremental_recovery=true`）

```json
{
  "type": "session-init",
  "session_id": "17ef2ed5-...",
  "mode": "incremental",
  "timestamp": "..."
}
```

| 字段 | 说明 |
|------|------|
| mode | 恢复模式，当前固定为 `"incremental"` |

> 收到此事件后，前端通过 `get-incremental-events` 查询驱动历史恢复。

#### role-changed — 角色状态恢复（仅 resume 且有活跃角色时）

```json
{
  "type": "role-changed",
  "role_name": "developer",
  "app_mode": "delegate",
  "timestamp": "..."
}
```

#### session-ready — 初始化完成 ★

```json
{
  "type": "session-ready",
  "session_id": "17ef2ed5-6a12-4d2a-8f4b-c9f1e3ae6ef7",
  "session_mode": "new",
  "timestamp": "..."
}
```

| 字段 | 说明 |
|------|------|
| session_id | 会话 ID |
| session_mode | `"new"` 或 `"resume"` |

Delegate 只以该事件结束 config 阶段。Domain Expert 还兼容 `type=system` 且 `content` 明确包含
`Ready to chat` 的初始化完成帧。初始化完成后，ChatService 按调用档案发送 `user-message` 或
`chat_expert`。

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

客户端发送 `approval-response` 后，Relay 可推送 `approval-result` 事件确认。该事件是 Relay 出参，
不是 ChatService 发送的问卷回答帧。

```json
{
  "type": "approval-result",
  "approval_id": "c95ceb53-f66f-4da2-a3ba-2a190aec5d6f",
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

### 3.9 增量恢复事件（断线续传）

以下事件由客户端发送 `get-incremental-events` 触发，用于断线续传和增量恢复。

#### incremental-events-batch — 增量事件批次

服务端将查询结果按 50 条一批分片推送。

```json
{
  "type": "incremental-events-batch",
  "batch_index": 0,
  "total_batches": 2,
  "messages": [
    {"type": "agent-call", "version_id": 4, "agent_name": "plan_agent", "is_start": true, ...},
    {"type": "agent-call", "version_id": 5, "agent_name": "plan_agent", "is_start": false, ...},
    {"type": "tool-execution", "version_id": 6, "tool_name": "read", "is_start": true, ...}
  ],
  "timestamp": "..."
}
```

| 字段 | 说明 |
|------|------|
| batch_index | 当前批次序号（从 0 开始） |
| total_batches | 总批次数 |
| messages | 事件数组，每条事件含 `version_id` 字段；若请求时 `compact=true`，大字段（如 `prompt`）会被省略并标记 `prompt_omitted` |

#### incremental-events-complete — 增量查询完成

所有批次发送完毕后，服务端推送此事件表示查询结束。

```json
{
  "type": "incremental-events-complete",
  "total_messages": 3,
  "version_range": {"min": 4, "max": 6},
  "has_more": false,
  "mode": "forward",
  "raw_max_version_id": 6,
  "timestamp": "..."
}
```

| 字段 | 说明 |
|------|------|
| total_messages | 本轮查询返回的总消息数 |
| version_range | 返回消息的 `version_id` 范围，含 min 和 max |
| has_more | 是否还有更多消息（用于分页续查） |
| mode | `"forward"`（正向/增量恢复）或 `"reverse"`（反向/翻页） |
| raw_max_version_id | 正向模式下，磁盘上实际的最大 `version_id`（用于下一页分页游标） |
| raw_min_version_id | 反向模式下，磁盘上实际的最小 `version_id`（用于下一页分页游标） |

> **分页续查**：若 `has_more=true`，客户端应使用 `raw_max_version_id`（正向）或 `raw_min_version_id`（反向）作为下一次查询的 `since_version` 或 `before_version`，继续获取剩余事件。

#### incremental-events-error — 增量查询失败

```json
{
  "type": "incremental-events-error",
  "error_code": "MISSING_SESSION_ID",
  "error_message": "Missing session_id parameter",
  "timestamp": "..."
}
```

| error_code | 说明 |
|------------|------|
| MISSING_SESSION_ID | 缺少 `session_id` 参数 |
| SESSION_MISMATCH | 请求的 `session_id` 与当前连接的会话不匹配 |
| QUERY_FAILED | 服务端查询异常 |

---

## 4. 典型事件流时序

### 4.1 MCP 工具调用场景

```
客户端                          服务端
  │                               │
  │──── config ──────────────────→│
  │←─── system ──────────────────│  (Establishing connection...)
  │←─── project_home ────────────│
  │←─── session-id ──────────────│
  │←─── system ──────────────────│  (Connected to session...)
  │←─── session-id ──────────────│  (finalize 二次确认)
  │←─── session-ready ───────────│  ★ config 阶段结束，可发送 user-message
  │                               │
  │──── user-message ────────────→│
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
  │──── approval-response ──────→│  ★ 用户回复选择
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

### 4.3 断线续传场景

客户端因网络中断等原因断开连接后，重连并通过 `version_id` 增量恢复遗漏事件。

```
客户端                          服务端
  │                               │
  │  [已收到 version_id=1,2,3]    │
  │  [网络断开]                    │  [继续产生 v4,v5,v6 并持久化]
  │                               │
  │──── WebSocket 重连 ──────────→│
  │──── config ──────────────────→│
  │     sessionMode: "resume"
  │     supports_incremental_
  │     recovery: true            │
  │←─── system ──────────────────│
  │←─── project_home ────────────│
  │←─── session-id ──────────────│
  │←─── session-init ────────────│  mode="incremental"
  │←─── session-ready ───────────│  ★ config 完成
  │                               │
  │──── get-incremental-events ──→│  since_version=3
  │←─── incremental-events-batch ─│  [v4, v5, v6]
  │←─── incremental-events-complete│  has_more=false
  │                               │
  │  [版本缓冲区同步到 v6]         │
  │  [后续实时事件从 v7 开始]      │
  │                               │
  │──── user-message ────────────→│
  │←─── ... (正常事件流) ─────────│
```

**关键步骤说明：**

1. **config 阶段**：客户端发送 `config(sessionMode="resume", supports_incremental_recovery=true)`，服务端返回 `session-init(mode="incremental")` 而非全量回放
2. **增量查询**：客户端发送 `get-incremental-events(since_version=3)`，`3` 是断线前最后收到的 `version_id`
3. **事件恢复**：服务端从持久化存储中读取 `version_id > 3` 的事件，按批次返回
4. **版本同步**：恢复完成后，客户端将版本缓冲区游标同步到 `raw_max_version_id`，后续实时事件从该值之后正常接收

---

## 5. 事件类型速查表

| 事件类型 | 阶段 | 说明 |
|----------|------|------|
| `user` | 全局 | 用户消息回显 |
| `session-id` | 初始化 | 会话 ID 确认 |
| `session-init` | 初始化 | 增量恢复开始（仅 resume） |
| `role-changed` | 初始化 | 角色状态恢复（仅 resume） |
| `session-ready` | 初始化 | Delegate config 完成信号；Domain Expert 还兼容明确的 `Ready to chat` system 帧 |
| `expert_rejection` | 输出 | 专家拒答卡片；不是终态，继续等待 `session-state` |
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
| `interrupt` / `pause` | 控制 | 中断当前轮次 |
| `stop_agent` | 控制 | 停止指定后台 Agent |
| `stop_all_agents` | 控制 | 停止所有 Agent |
| `stop_compression` | 控制 | 停止压缩 Agent |
| `cancel-initialization` | 控制 | 取消初始化 |
| `heartbeat` | 控制 | 心跳请求 |
| `heartbeat-response` | 控制 | 心跳响应（含当前状态） |
| `get-incremental-events` | 恢复 | 增量事件查询（断线续传/翻页） |
| `incremental-events-batch` | 恢复 | 增量事件批次（50 条/批） |
| `incremental-events-complete` | 恢复 | 增量查询完成（含分页游标） |
| `incremental-events-error` | 恢复 | 增量查询失败 |

---

## 6. Config 阶段完成判断

### 6.1 核心定义

**Config 阶段**：从客户端发送 `config` 消息开始，到服务端确认会话初始化完成、客户端可以发送 `user-message` 为止。

### 6.2 Profile 对应完成信号

Delegate 只接受 `session-ready`。Domain Expert 接受 `session-ready`，也兼容
`type=system` 且 `content` 明确包含 `Ready to chat` 的帧。

```python
def is_config_complete(event: dict, runtime_profile: str) -> bool:
    if event.get("type") == "session-ready":
        return True
    return (
        runtime_profile == "DOMAIN_EXPERT"
        and event.get("type") == "system"
        and "Ready to chat" in event.get("content", "")
    )
```

### 6.3 各模式事件序列

**New 会话：**

```
← system (Establishing connection...)
← project_home
← session-id (含 topic/tags)
← system (Connected to session xxx...)
← session-id (finalize 二次确认)
← system (Ready to chat! Session ID: xxx)
← session-ready (session_mode="new")        ★ config 完成
```

**Resume 会话（supports_incremental_recovery=true）：**

```
← system (Establishing connection...)
← project_home
← session-id (含 topic/tags)
← system (Connected to session xxx...)
← role-changed (如有活跃角色)
← session-init (mode="incremental")
← session-id (finalize 二次确认)
← session-ready (session_mode="resume")     ★ config 完成
```

**Resume 会话（supports_incremental_recovery=false，legacy 路径）：**

```
← system (Establishing connection...)
← project_home
← session-id (含 topic/tags)
← system (Connected to session xxx...)
← system (Resuming session xxx...)
← [历史消息批量回放...]
← history-restore-complete
← session-id (finalize 二次确认)
← session-ready (session_mode="resume")     ★ config 完成
```

### 6.4 错误场景

| 场景 | 事件 | 说明 |
|------|------|------|
| sessionMode 无效 | `error` (Invalid sessionMode) | `sessionMode` 不是 `"new"` 或 `"resume"` |
| Resume 会话不存在 | `clear-session` + `error` | sessionId 对应的会话找不到或已损坏 |
| 初始化异常 | `error` (Initialization failed) | 服务端内部错误 |

> **注意**：错误场景下不会发送 `session-ready`，客户端应通过超时或收到 `error`/`clear-session` 判断初始化失败。

---

## 7. 用户轮次结束判断

### 7.1 核心定义

**用户轮次**：从客户端发送 `user-message` 开始，到服务端确认 Agent 执行完毕、用户可以发送下一条消息为止。

**关键区分**：用户轮次结束 ≠ 所有 WebSocket 事件停止。收到轮次结束信号后，仍可能有以下**后台异步事件**继续到达，客户端应忽略它们对轮次状态的影响：
- `self-evolution-status`（自演化通知）
- 后台 Agent 的 `thinking-content-update` / `agent` / `thinking-operation-end` 等（如 `topic_generator`）
- `token-update`（Token 用量更新）

### 7.2 终态与过程事件

| 信号 | 可靠性 | 说明 |
|------|--------|------|
| `session-state` 终态 | ✅ **唯一终态** | `completed/waiting_user_input/paused` 结束本轮；`idle` 不结束本轮 |
| `agent-call(is_start=false)` | ❌ **过程事件** | 统一映射为 `runtime.agent`，根 Agent 与子 Agent 均不得结束本轮 |
| `generate-response(is_final=true)` | ❌ **回答快照** | 可更新正文快照，但不得结束本轮 |
| `stream-complete/[DONE]` 等兼容帧 | ❌ **过程兼容事件** | 不结束 Relay WebSocket 用户轮次 |

### 7.3 正常路径事件顺序（代码保证）

```
agent-call(is_start=true)                 ← pre_reply hook
  ┊ [Agent 执行：LLM 调用、工具调用、Sub-Agent...]
generate-response(is_final=true)           ← post_reply hook（不保证一定有）
agent-call(is_start=false, error=null)     ← post_reply hook
session-state(state="waiting_user_input")  ← handle_user_message 返回后广播
  ┊ [后台异步事件：self-evolution-status、topic_generator 等，不影响轮次]
```

**顺序保证**：`agent-call(end)` → `session-state`（`session-state` 在 `handle_user_message` 返回后才广播，而 `agent-call(end)` 在 `post_reply` hook 中发出，先于返回）。

### 7.4 明确判断逻辑

```python
class TurnState:
    """用户轮次状态跟踪器"""

    TERMINAL_STATES = {"waiting_user_input", "completed", "paused"}
    # 非终态，收到这些不应改变轮次状态
    NON_TERMINAL_STATES = {"ready", "running", "idle", "agent_thinking", "waiting_approval"}
    # 后台异步事件类型，收到终态后应忽略
    BACKGROUND_EVENT_TYPES = {
        "self-evolution-status", "token-update",
        # 后台 Agent 事件（agent_name 非根 Agent 的 thinking/agent 事件）
    }

    def __init__(self):
        self.turn_active = False       # 当前轮次是否在进行中
        self.user_message_sent = False  # 是否已发送过 user-message（区分初始化阶段的 session-state）

    def on_send_user_message(self):
        """客户端发送 user-message 时调用"""
        self.turn_active = True
        self.user_message_sent = True

    def on_event(self, event: dict) -> str:
        """
        处理收到的 WebSocket 事件。
        返回值：
          "turn_ended"     - 用户轮次已结束，可启用输入框
          "turn_active"    - 轮次仍在进行中
          "background"     - 后台异步事件，不影响轮次状态
        """
        event_type = event.get("type")

        # --- 信号1（主）：session-state ---
        if event_type == "session-state":
            state = event.get("state")

            # 初始化阶段的 session-state，忽略
            if not self.user_message_sent:
                return "background"

            if state in self.TERMINAL_STATES:
                self.turn_active = False
                return "turn_ended"

            if state in self.NON_TERMINAL_STATES:
                # waiting_approval 等非终态，轮次仍在进行（只是需要用户回复审批）
                return "turn_active"

        # agent-call、generate-response 和其他过程事件只更新展示，不改变轮次状态。

        # --- 后台异步事件：终态后忽略 ---
        if not self.turn_active and event_type in self.BACKGROUND_EVENT_TYPES:
            return "background"

        return "turn_active"
```

### 7.5 各场景信号对照

| 场景 | 收到的事件序列 | 判断时机 |
|------|---------------|----------|
| **正常完成** | `agent-call(start)` → ... → `generate-response` → `agent-call(end)` → `session-state(waiting_user_input)` | `session-state` 到达时 → `turn_ended` |
| **澄清问** | `agent-call(start)` → ... → `approval-request` → `session-state(agent_thinking)` → [用户回复] → `agent-call(end)` → `session-state(waiting_user_input)` | `session-state(waiting_user_input)` 到达时 → `turn_ended` |
| **用户中断** | `agent-call(start)` → ... → `agent-call(end, error="...PAUSE")` → `session-state(paused)` | `session-state(paused)` 到达时 → `turn_ended` |
| **Agent 异常** | `agent-call(start)` → ... → `agent-call(end, error="...")` → `session-state(idle)` | `idle` 不结束本轮；继续等待有效终态，缺失时由超时失败收口 |
| **后台事件干扰**（反例） | `session-state(waiting_user_input)` → `self-evolution-status` → `thinking-content-update(topic_generator)` | `session-state` 到达时 → `turn_ended`；后续事件 → `background`，不改变状态 |

### 7.6 兜底机制

| 机制 | 说明 |
|------|------|
| **heartbeat 轮询** | 定期发送 `{"type": "heartbeat"}`，通过 `heartbeat-response.state` 确认服务端状态 |
| **超时** | 发送 `user-message` 后 60s 未收到 `session-state` 终态，视为异常，可主动 heartbeat 确认 |
| **重连恢复** | WebSocket 断连后重连，发送 `config(sessionMode="resume", supports_incremental_recovery=true)`，收到 `session-init(mode="incremental")` 后通过 `get-incremental-events(since_version=最后收到的version_id)` 增量恢复遗漏事件 |
