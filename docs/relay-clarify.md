----
文档包含两个模块：
approval-response 回复用户出参文档
用户回复 approval-response 入参文档
----



# approval-response 回复用户出参文档

## 1. 概述

`approval-response` 是前端发送给服务端的用户回复消息。服务端处理后会：

1. **返回给 Agent** - 通过 ask_user 工具返回值
2. **返回给前端** - 通过 `approval-result` 事件确认

本文档描述服务端返回给**前端**的出参（`approval-result` 事件）。

## 2. approval-result 事件格式

### 2.1 基本结构

```json
{
  "type": "approval-result",
  "approval_id": "uuid-string",
  "request_id": "uuid-string",
  "approved": true,
  "scope": "once",
  "timestamp": "2026-07-30T12:00:00Z",
  "auto_approved": false,
  "resolved_by": "user",
  "resolved_at": "2026-07-30T12:00:05Z"
}
```

### 2.2 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| type | string | 固定值 "approval-result" |
| approval_id | string | 审批请求ID，对应 approval-request 的 approval_id |
| request_id | string | 同 approval_id（前端路由需要） |
| approved | boolean | 是否批准 |
| scope | string | 批准范围（"once" / "always"） |
| timestamp | string | 结果时间戳（ISO 8601） |
| auto_approved | boolean | 是否自动批准（预授权） |
| resolved_by | string | 解决者（"user" / "im" / "auto"） |
| resolved_at | string | 解决时间（ISO 8601） |

## 3. ask_user 问卷场景出参

### 3.1 正常回答出参

**前端发送**：
```json
{
  "type": "approval-response",
  "approval_id": "550e8400-e29b-41d4-a716-446655440000",
  "approved": true,
  "questionnaire_answers": {
    "label": {
      "请选择技术方案": ["方案A"]
    },
    "ignore": false
  }
}
```

**服务端返回（approval-result）**：
```json
{
  "type": "approval-result",
  "approval_id": "550e8400-e29b-41d4-a716-446655440000",
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "approved": true,
  "scope": "once",
  "timestamp": "2026-07-30T12:00:05.123Z",
  "auto_approved": false,
  "resolved_by": "user",
  "resolved_at": "2026-07-30T12:00:05.123Z",
  "questionnaire_answers": {
    "label": {
      "请选择技术方案": ["方案A"]
    },
    "ignore": false
  }
}
```

### 3.2 用户忽略出参

**前端发送**：
```json
{
  "type": "approval-response",
  "approval_id": "550e8400-e29b-41d4-a716-446655440001",
  "approved": true,
  "questionnaire_answers": {
    "label": {},
    "ignore": true
  }
}
```

**服务端返回（approval-result）**：
```json
{
  "type": "approval-result",
  "approval_id": "550e8400-e29b-41d4-a716-446655440001",
  "request_id": "550e8400-e29b-41d4-a716-446655440001",
  "approved": true,
  "scope": "once",
  "timestamp": "2026-07-30T12:01:00.456Z",
  "auto_approved": false,
  "resolved_by": "user",
  "resolved_at": "2026-07-30T12:01:00.456Z",
  "questionnaire_answers": {
    "label": {},
    "ignore": true
  }
}
```

**注意**：
- `approved` 仍为 `true`（用户已响应）
- `questionnaire_answers.ignore` 为 `true` 表示用户选择忽略
- ask_user 工具会返回 `{"error": "User ignored the questionnaire", "failed": true}` 给 Agent

### 3.3 多问题回答出参

**前端发送**：
```json
{
  "type": "approval-response",
  "approval_id": "550e8400-e29b-41d4-a716-446655440002",
  "approved": true,
  "questionnaire_answers": {
    "label": {
      "选择编程语言": ["Python"],
      "是否需要单元测试": ["是"],
      "代码风格": ["简洁"]
    },
    "ignore": false
  }
}
```

**服务端返回（approval-result）**：
```json
{
  "type": "approval-result",
  "approval_id": "550e8400-e29b-41d4-a716-446655440002",
  "request_id": "550e8400-e29b-41d4-a716-446655440002",
  "approved": true,
  "scope": "once",
  "timestamp": "2026-07-30T12:02:15.789Z",
  "questionnaire_answers": {
    "label": {
      "选择编程语言": ["Python"],
      "是否需要单元测试": ["是"],
      "代码风格": ["简洁"]
    },
    "ignore": false
  }
}
```

### 3.4 带 file_path 的问卷出参

**前端发送**：
```json
{
  "type": "approval-response",
  "approval_id": "550e8400-e29b-41d4-a716-446655440003",
  "approved": true,
  "questionnaire_answers": {
    "label": {
      "这个方案是否合适？": ["合适，执行"]
    },
    "ignore": false
  }
}
```

**服务端返回（approval-result）**：
```json
{
  "type": "approval-result",
  "approval_id": "550e8400-e29b-41d4-a716-446655440003",
  "request_id": "550e8400-e29b-41d4-a716-446655440003",
  "approved": true,
  "scope": "once",
  "timestamp": "2026-07-30T12:03:30.123Z",
  "questionnaire_answers": {
    "label": {
      "这个方案是否合适？": ["合适，执行"]
    },
    "ignore": false
  },
  "file_path": "/project/plan.md",
  "file_content": "# 实施方案\n\n## 概述\n..."
}
```

**注意**：
- `file_path` 和 `file_content` 会从原始 approval-request 中透传
- 仅用于前端渲染，不影响工具返回给 Agent 的值

## 4. 其他确认类型出参（非 ask_user）

### 4.1 FILE_WRITE 确认

**前端发送**：
```json
{
  "type": "approval-response",
  "approval_id": "550e8400-e29b-41d4-a716-446655440004",
  "approved": true,
  "scope": "once"
}
```

**服务端返回**：
```json
{
  "type": "approval-result",
  "approval_id": "550e8400-e29b-41d4-a716-446655440004",
  "request_id": "550e8400-e29b-41d4-a716-446655440004",
  "approved": true,
  "scope": "once",
  "timestamp": "2026-07-30T12:04:00.000Z"
}
```

### 4.2 COMMAND_EXECUTION 确认

**前端发送**：
```json
{
  "type": "approval-response",
  "approval_id": "550e8400-e29b-41d4-a716-446655440005",
  "approved": false,
  "scope": "once"
}
```

**服务端返回**：
```json
{
  "type": "approval-result",
  "approval_id": "550e8400-e29b-41d4-a716-446655440005",
  "request_id": "550e8400-e29b-41d4-a716-446655440005",
  "approved": false,
  "scope": "once",
  "timestamp": "2026-07-30T12:05:00.000Z"
}
```

### 4.3 自动批准（预授权）

**服务端返回**：
```json
{
  "type": "approval-result",
  "approval_id": "550e8400-e29b-41d4-a716-446655440006",
  "request_id": "550e8400-e29b-41d4-a716-446655440006",
  "approved": true,
  "scope": "once",
  "timestamp": "2026-07-30T12:06:00.000Z",
  "auto_approved": true,
  "resolved_by": "auto",
  "resolved_at": "2026-07-30T12:06:00.000Z"
}
```

**注意**：
- `auto_approved: true` 表示自动批准，前端可显示绿色徽章
- `resolved_by: "auto"` 表示自动批准而非用户操作

### 4.4 IM 远程批准（竞争）

**服务端返回**：
```json
{
  "type": "approval-result",
  "approval_id": "550e8400-e29b-41d4-a716-446655440007",
  "request_id": "550e8400-e29b-41d4-a716-446655440007",
  "approved": true,
  "scope": "once",
  "timestamp": "2026-07-30T12:07:00.000Z",
  "auto_approved": false,
  "resolved_by": "im",
  "resolved_at": "2026-07-30T12:07:00.000Z"
}
```

**场景**：
- IM（即时消息）渠道和 Web 渠道同时处理同一审批
- IM 先响应，Web 收到竞争结果通知
- 前端可关闭弹窗并显示提示

## 5. 完整流程示例

### 5.1 正常问卷流程

```
1. Agent 调用 ask_user 工具
   ↓
2. 服务端发送 approval-request
   {
     "type": "approval-request",
     "approval_id": "xxx",
     "mode": "questionnaire",
     "questions": [...]
   }
   ↓
3. 前端显示问卷 UI
   ↓
4. 用户选择答案并提交
   前端发送 approval-response
   {
     "type": "approval-response",
     "approval_id": "xxx",
     "approved": true,
     "questionnaire_answers": {
       "label": {"问题": ["答案"]},
       "ignore": false
     }
   }
   ↓
5. 服务端处理
   - 更新 ConfirmationResponse
   - 触发 asyncio.Event 唤醒 Agent
   - 持久化 approval-result 到 chat_history
   - 广播 approval-result 给前端
   ↓
6. 前端收到 approval-result
   {
     "type": "approval-result",
     "approval_id": "xxx",
     "approved": true,
     "questionnaire_answers": {...}
   }
   ↓
7. Agent 继续执行（收到工具返回值）
   ask_user 返回: '{"问题": "答案"}'
```

### 5.2 用户忽略流程

```
1-3. 同上
   ↓
4. 用户点击"忽略"按钮
   前端发送 approval-response
   {
     "type": "approval-response",
     "approval_id": "xxx",
     "approved": true,
     "questionnaire_answers": {
       "label": {},
       "ignore": true
     }
   }
   ↓
5. 服务端处理
   - 检测到 ignore=true
   - 设置 response.metadata["ignore"] = True
   - 唤醒 Agent
   ↓
6. 前端收到 approval-result
   {
     "type": "approval-result",
     "approval_id": "xxx",
     "approved": true,
     "questionnaire_answers": {
       "label": {},
       "ignore": true
     }
   }
   ↓
7. Agent 收到失败返回
   ask_user 返回: '{"error": "User ignored the questionnaire", "failed": true}'
   ↓
8. Agent 重新规划执行路径
```

### 5.3 超时流程

```
1-3. 同上
   ↓
4. 用户 60 秒内未响应
   ↓
5. 服务端超时处理（plugin.py:225）
   - asyncio.wait_for 超时
   - 自动返回失败给 Agent
   ↓
6. Agent 收到失败返回
   ask_user 返回: '{"error": "Questionnaire timed out after 60 seconds", "failed": true}'
   
注意：超时不会发送 approval-result 给前端（因为用户未响应）
```

---

# 用户回复 approval-response 入参文档

## 1. 基本格式

用户通过 WebSocket 回复 approval-request 的完整入参格式：

```json
{
  "type": "approval-response",
  "approval_id": "uuid-string",
  "approved": true,
  "questionnaire_answers": {
    "label": {
      "问题文本1": ["选中的答案1"],
      "问题文本2": ["选中的答案2"]
    },
    "ignore": false
  }
}
```

## 2. 字段说明

### 2.1 顶层字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 "approval-response" |
| approval_id | string | 是 | 审批请求ID，对应 approval-request 事件中的 approval_id |
| approved | boolean | 是 | 是否批准（对于 ask_user 工具通常为 true） |
| questionnaire_answers | object | 是 | ask_user 问卷答案（仅用于 questionnaire 类型） |

### 2.2 questionnaire_answers 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| label | object | 是* | 实际的问答内容，key 为问题文本，value 为答案数组 |
| ignore | boolean | 否 | 是否忽略此问卷，默认 false |

**注意**：
- 如果 `ignore=true`，工具调用将失败，返回 `{"error": "User ignored the questionnaire", "failed": true}`
- 如果 `ignore=false` 或未提供，则从 `label` 字段提取实际答案

## 3. 使用场景示例

### 3.1 正常回答问卷

```json
{
  "type": "approval-response",
  "approval_id": "550e8400-e29b-41d4-a716-446655440000",
  "approved": true,
  "questionnaire_answers": {
    "label": {
      "请选择实现方案": ["方案A: 使用Redis缓存"],
      "是否需要添加日志": ["是"]
    },
    "ignore": false
  }
}
```

### 3.2 多选题回答

```json
{
  "type": "approval-response",
  "approval_id": "550e8400-e29b-41d4-a716-446655440001",
  "approved": true,
  "questionnaire_answers": {
    "label": {
      "选择需要的功能": ["用户认证", "数据缓存", "日志记录"]
    },
    "ignore": false
  }
}
```

### 3.3 用户忽略问卷

```json
{
  "type": "approval-response",
  "approval_id": "550e8400-e29b-41d4-a716-446655440002",
  "approved": true,
  "questionnaire_answers": {
    "label": {},
    "ignore": true
  }
}
```

**返回结果**（给 Agent）：
```json
{
  "error": "User ignored the questionnaire",
  "failed": true
}
```

### 3.4 用户选择"Other"自定义输入

```json
{
  "type": "approval-response",
  "approval_id": "550e8400-e29b-41d4-a716-446655440003",
  "approved": true,
  "questionnaire_answers": {
    "label": {
      "请选择编程语言": ["Other: Rust"]
    },
    "ignore": false
  }
}
```

## 4. 其他确认类型的简化格式

ask_user 工具只使用 QUESTIONNAIRE 类型。其他确认类型（FILE_WRITE、COMMAND_EXECUTION 等）的 approval-response 格式：

```json
{
  "type": "approval-response",
  "approval_id": "uuid-string",
  "approved": true,
  "scope": "once",
  "answer": "选项值",
  "text": "文本输入值"
}
```

## 5. WebSocket 发送示例

```javascript
// 前端通过 WebSocket 发送 approval-response
const ws = new WebSocket('ws://localhost:8000/ws');

ws.send(JSON.stringify({
  type: 'approval-response',
  approval_id: '550e8400-e29b-41d4-a716-446655440000',
  approved: true,
  questionnaire_answers: {
    label: {
      '选择技术方案': ['使用 REST API']
    },
    ignore: false
  }
}));
```

## 6. 超时处理

如果用户在 60 秒内未回复，系统会自动触发超时：

- 服务端自动返回给 Agent：
  ```json
  {
    "error": "Questionnaire timed out after 60 seconds",
    "failed": true
  }
  ```

- Agent 收到此错误后会重新规划执行路径

## 7. 处理流程

```
前端发送 approval-response
  ↓
WebSocket message_router 接收 (message_router.py:259)
  ↓
handle_approval_response 处理 (message_handlers.py:598)
  ↓
WebConfirmationStrategy.handle_approval_response (web_strategy.py:299)
  ↓
提取 questionnaire_answers
  ├─ ignore=true → response_metadata["ignore"] = True
  └─ ignore=false → response_metadata["answers"] = questionnaire_answers["label"]
  ↓
ConfirmationRequest 完成等待
  ↓
ask_user 工具返回结果给 Agent
```