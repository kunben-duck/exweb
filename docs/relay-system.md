# Domain-Expert 专家模式 API 说明文档

## 概述

Domain-Expert 是一种专门的 Agent 模式，提供**单专业领域**的专家服务。该模式通过技能的 SKILL.md 文件获得明确的领域边界和拒答能力。

**核心特性**：
- ✅ 领域专业化：加载 SKILL.md 作为 prompt
- ✅ 拒答约束：内置 `reject_query` 工具
- ✅ 会话绑定：一个会话绑定一个专家
- ✅ 子代理委派：支持通过 sub_agent_names 委派

### ChatService 角色映射

ChatService 不再配置固定专家角色。Intent `items[].accessName` 先移除一次通用响应前缀，再区分大小写匹配显式配置的专家前缀；命中后移除一次专家前缀并 trim，剩余后缀直接作为本协议的 `role_name`。

```text
原始 accessName: domain_agent_domain_expert_system-awareness
通用前缀:       domain_agent_
专家前缀:       domain_expert_
role_name:       system-awareness
```

前缀命中但后缀为空时，ChatService 将其视为 Intent 协议错误，不调用 Relay。动态角色会固化在 Relay Binding 中；相同角色可以恢复原 session，不同角色分别维护自己的 Binding。前端不提交 `role_name`。

---

## 调用流程

```
1. WebSocket 连接 → ws://host:port/ws/{client_id}
2. 初始化配置 → 发送 config 消息，等待 session-ready 或明确的 Ready to chat
3. 调用专家 → 发送 chat_expert 消息
4. 接收响应 → 处理 agent/expert_rejection 等事件
5. 判断完成 → 等待终态 session-state
```

---

## 1. 初始化配置

### 请求消息

```json
{
  "type": "config",
  "config": {
    "sessionMode": "new",
    "appMode": "domain_expert",
    "sessionId": "session_xxx",
    "uid": "user_xxx",
    "traceId": "trace_xxx"
  }
}
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 | 示例值 |
|------|------|:----:|------|--------|
| `sessionMode` | string | ✅ | 会话模式 | `"new"` 新会话 / `"resume"` 恢复会话 |
| `appMode` | string | ✅ | 应用模式 | 固定值 `"domain_expert"` |
| `sessionId` | string | ⚠️ | 会话ID | 恢复会话时必填 |
| `uid` | string | ❌ | 用户ID | `"user_xxx"` |
| `traceId` | string | ❌ | ChatService 捕获的调用链标识 | `"trace_xxx"` |

### 初始化完成信号

监听以下任一事件：
- `session-ready` - 会话就绪
- `system` 消息 - 内容包含 "Ready to chat"

---

## 2. 调用专家

### 请求消息

```json
{
  "type": "chat_expert",
  "role_name": "system-awareness",
  "content": "资产负债率怎么计算？"
}
```

### 完整参数

```json
{
  "type": "chat_expert",
  "role_name": "system-awareness",
  "content": "你的问题",
  "messages": [],
  "traceId": "trace_xxx",
  "metadata": {}
}
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 | 示例值 |
|------|------|:----:|------|--------|
| `type` | string | ✅ | 消息类型 | 固定值 `"chat_expert"` |
| `role_name` | string | ✅ | 技能名称；ChatService 在 NEW/RESUME 中均发送 Binding 保存的值 | `"system-awareness"` |
| `content` | string | ✅ | 用户问题 | `"资产负债率怎么计算？"` |
| `messages` | array | ❌ | 开启短期记忆后发送的历史 user/assistant 消息 | `[]` |
| `traceId` | string | ❌ | ChatService 捕获的调用链标识 | `"trace_xxx"` |
| `metadata` | object | ❌ | 元数据对象 | 见下表 |

### config 参数

| 字段 | 类型 | 说明 |
|------|------|------|
| `plugins.disabled` | array\<string\> | 禁用的插件列表 |

### metadata 参数

| 字段 | 类型 | 说明 |
|------|------|------|
| `qaType` | string | 问题类型 |
| `userAccount` | string | 用户账号 |
| `globalUserId` | number | 全局用户ID |
| `platform` | string | 平台标识（PC/Mobile） |
| `sceneParam` | object | 场景参数 |

**metadata 说明**：
- 传递用户上下文信息
- 服务端会注入到 MCP 工具调用中
- 不体现在 WebSocket 响应中

---

## 3. 会话绑定规则

- **首次调用**：必须传 `role_name`，会话绑定到该专家
- **后续调用**：可省略 `role_name`，使用已绑定专家
- **切换专家**：必须创建新会话（`sessionMode: "new"`）

**错误示例**：尝试在已绑定会话中切换专家

```json
{
  "type": "system",
  "content": "当前会话已绑定专家 'system-awareness'，无法切换到 'tax-expert'。请创建新会话。",
  "level": "error"
}
```

---

## 4. 响应事件

### 事件流程

```
agent-call(is_start=true)   → Agent 开始处理
  ├─ agent (multiple)        → 流式文本输出（需拼接）
  ├─ tool-call-start         → 工具调用开始
  ├─ tool-call-end           → 工具调用结束
  ├─ expert_rejection        → 专家拒答（可选）
  ├─ agent-call(is_start=false) → Agent 过程结束
session-state(终态)        → ⭐ 轮次结束信号
```

### 核心事件

#### 4.1 文本输出（agent）

```json
{
  "type": "agent",
  "content": "资产负债率 = 负债总额 / 资产总额 × 100%",
  "timestamp": "2025-01-23T10:30:00"
}
```

**说明**：流式输出，前端需拼接所有 `agent` 事件的 `content`。

#### 4.2 专家拒答（expert_rejection）

```json
{
  "type": "expert_rejection",
  "skill_name": "system-awareness",
  "reason": "该问题涉及编程实现，超出财务专家的专业范围",
  "suggested_expert": "developer",
  "timestamp": "2025-01-23T10:30:00"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `skill_name` | string | 当前专家名称 |
| `reason` | string | 拒答原因 |
| `suggested_expert` | string | 推荐的其他专家 |

**处理建议**：
1. 显示拒答原因给用户
2. 根据 `suggested_expert` 提供切换建议
3. 继续等待终态 `session-state`；ChatService 不把该事件解释为 DomainAgent 拒答重路由

#### 4.3 Agent 调用（agent-call）

```json
{
  "type": "agent-call",
  "is_start": true,
  "agent_name": "domain_expert_agent"
}
```

**关键判断**：
- `is_start: true` - Agent 开始处理
- `is_start: false` - 当前 Agent 调用过程结束，**不是轮次结束信号**
- 子 Agent 和根 Agent 的 `agent-call` 均按 `runtime.agent` 过程事件处理

#### 4.4 工具调用（tool-call-start/end）

```json
{
  "type": "tool-call-start",
  "tool_name": "tax-expert",
  "args_summary": {...}
}
```

**常见工具**：
- `reject_query` - 专家拒答工具
- `tax-expert` - 子专家工具（委派给税务专家）
- 其他自定义 MCP 工具

---

### 其他事件

| 事件类型 | 说明 | 处理建议 |
|---------|------|---------|
| `session-id` | 会话ID | 保存用于恢复会话 |
| `session-ready` | 会话就绪 | 初始化完成信号 |
| `session-state` | 会话状态 | `completed/waiting_user_input/paused` 是轮次结束信号；`idle` 是过程状态 |
| `system` | 系统消息 | 显示给用户 |
| `error` | 错误消息 | 显示给用户，记录日志 |
| `approval-request` | 需用户确认 | 回复 `approval-response` |
| `thinking-content-update` | 思考过程 | 可选显示 |
| `token-update` | Token 统计 | 可用于计费 |

### approval-request 处理

```json
// 收到
{
  "type": "approval-request",
  "approval_id": "xxx",
  "tool_name": "sensitive_operation"
}

// 回复
{
  "type": "approval-response",
  "request_id": "xxx",
  "approved": true,
  "scope": "once",
  "questionnaire_answers": {
    "label": {"请选择技术方案": "方案A"},
    "ignore": false
  }
}
```

---

## 5. 多轮对话

### 首次提问（需要 role_name）

```json
{
  "type": "chat_expert",
  "role_name": "system-awareness",
  "content": "资产负债率怎么计算？"
}
```

### 后续提问

```json
{
  "type": "chat_expert",
  "role_name": "system-awareness",
  "content": "那流动比率呢？"
}
```

**说明**：
- 会话持续绑定到 "system-awareness"
- 专家保持上下文记忆
- ChatService 在 NEW 和 RESUME 中均重复发送 Binding 保存的 `role_name`

---

## 6. 获取专家列表

### HTTP API

```
GET /api/skills?project_home=D:\project
```

### 响应示例

```json
[
  {
    "name": "system-awareness",
    "description": "系统认知专家",
    "skill_type": "role",
    "active": true
  }
]
```

**使用方式**：使用技能的 `name` 字段作为 `role_name`。

---

## 7. 常见问题

### Q1: 如何判断一轮对话完成？

只监听终态 `session-state`：
- `state=completed/waiting_user_input/paused` 表示本轮结束；`idle` 不结束本轮
- `agent-call(is_start=false)`、`generate-response(is_final=true)` 和 `relay-end` 都不是轮次结束信号
- 缺少终态 `session-state` 时，由 ChatService 现有心跳和最大运行时长按协议失败收口

### Q2: 用户能否在 Agent 运行时发送新消息？

**可以**。后台会排队处理，前端无需阻止用户输入。

### Q3: 如何处理图片输入？

在 `images` 字段传递图片列表：
- Base64 格式：`"data:image/png;base64,iVBORw0KGgo..."`
- URL 格式：`"https://example.com/image.png"`

### Q4: 如何恢复历史会话？

初始化时使用 `sessionMode: "resume"` 并传递 `sessionId`：

```json
{
  "type": "config",
  "config": {
    "sessionMode": "resume",
    "sessionId": "session_xxx",
    "appMode": "domain_expert"
  }
}
```

### Q5: metadata 的作用？

传递用户上下文信息（用户ID、平台、场景等），服务端会注入到 MCP 工具调用中，用于：
- 权限控制
- 审计日志
- 个性化处理

---

## 8. 错误处理

| 错误场景 | 错误消息 | 解决方案 |
|---------|---------|---------|
| 技能不存在 | `Role not found: xxx` | 检查 `role_name` 是否正确 |
| 会话冲突 | `当前会话已绑定专家 'xxx'，无法切换` | 创建新会话 |
| 空消息 | 无响应 | 确保 `content` 非空 |
| 连接失败 | WebSocket 连接失败 | 检查服务是否启动 |
| 初始化超时 | 未收到 `session-ready` | 检查 `project_home` 路径 |

---
