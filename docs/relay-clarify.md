# WebSocket 接口文档 - ask_user 交互

## 二、WebSocket 出参（后端→前端）

### 消息类型：approval-request

**场景1：标准问卷**
```json
{
  "type": "approval-request",
  "approval_id": "uuid-string",
  "operation_type": "questionnaire",
  "mode": "questionnaire",
  "message": "Please answer the following questions",
  "risk_level": "LOW",
  "agent_name": "agent-name",
  "parent_instance_id": "session_xxx",
  "timestamp": "2026-07-31T10:00:00",
  "questions": [
    {
      "question": "请选择技术方案",
      "options": [
        {"label": "方案A（推荐）", "description": "使用REST API"},
        {"label": "方案B", "description": "使用GraphQL"},
        {"label": "方案C", "description": "使用gRPC"}
      ],
      "multi_select": false
    },
    {
      "question": "请选择部署环境",
      "options": [
        {"label": "开发环境"},
        {"label": "测试环境"},
        {"label": "生产环境"}
      ],
      "multi_select": true
    }
  ]
}
```


### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 "approval-request" |
| approval_id | string | 是 | 请求唯一标识，用于响应时匹配 |
| operation_type | string | 是 | 固定值 "questionnaire" |
| mode | string | 是 | 固定值 "questionnaire" |
| message | string | 是 | 描述文本 |
| risk_level | string | 是 | 风险等级，固定值 "LOW" |
| agent_name | string | 否 | 触发问卷的Agent名称 |
| parent_instance_id | string | 是 | 父实例ID，用于前端路由 |
| timestamp | string | 是 | 时间戳 ISO格式 |
| questions | array | 是 | 问题列表（1-4个） |
| metadata | object | 是 | 元数据，包含confirmation_type、questions、file_path、file_content |

**questions[i] 参数说明**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | string | 是 | 问题文本 |
| options | array | 是 | 选项列表（2-6个） |
| multi_select | boolean | 否 | 是否多选，默认false |

**options[i] 参数说明**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| label | string | 是 | 选项标签 |
| description | string | 否 | 选项说明 |
| input_prompt | string | 否 | 输入提示，选中后需用户输入额外内容 |

## 三、WebSocket 入参（前端→后端）

### 消息类型：approval-response

**场景1：单选/多选问卷响应**
```json
{
  "type": "approval-response",
  "approval_id": "uuid-string",
  "approved": true,
  "scope": "once",
  "questionnaire_answers": {
    "label": {
      "请选择技术方案": "方案A（推荐）",
      "请选择部署环境": ["开发环境", "测试环境"]
    }
  }
}
```

**场景3：忽略问卷**
```json
{
  "type": "approval-response",
  "approval_id": "uuid-string",
  "approved": false,
  "scope": "once",
  "questionnaire_answers": {
    "ignore": true
  }
}
```

**场景4：选择Other自定义文本**
```json
{
  "type": "approval-response",
  "approval_id": "uuid-string",
  "approved": true,
  "scope": "once",
  "questionnaire_answers": {
    "label": {
      "请选择技术方案": "用户自定义答案"
    }
  }
}
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 "approval-response" |
| approval_id | string | 是 | 匹配approval-request的approval_id |
| approved | boolean | 是 | 是否批准/响应 |
| scope | string | 是 | 作用域，固定值 "once" |
| questionnaire_answers | object | 是 | 问卷答案对象 |

**questionnaire_answers 参数说明**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| label | object | 否 | 答案对象，key为问题文本，value为答案（单选为字符串，多选为数组） |
| ignore | boolean | 否 | 是否忽略问卷，为true时工具返回失败 |


## 五、超时处理

后端设置60秒超时：
- Agent调用ask_user时设置timeout_seconds=60
- 60秒内前端未响应 → 后端返回 `{"error": "Questionnaire timed out after 60 seconds", "failed": true}`
- Agent可根据failed字段判断失败原因并重试或使用默认方案

## 测试示例


```
生成一个调查问卷{
      "问题文本1": "单选答案",
      "问题文本2": [
        "多选答案1",
        "多选答案2"
      ]
    }
```

## 六、ChatService 集成约束

FinanceEXChatService 收到 `approval-request(operation_type=questionnaire)` 后，不保持当前下游 WebSocket：

```text
run-A 保存 runtime.card 和 AGENT_CLARIFICATION_REQUEST part
-> 原子保存 WAITING Interaction 和 ACTIVE Relay Binding
-> 输出 run.waiting_user
-> 关闭 run-A 下游连接
```

前端通过 `/v1/chat/runs` 提交 `CONTINUE_INTERACTION`。ChatService 创建 run-B，校验 Interaction 保存的
`runtimeBindingId/runtimeSessionId/approvalId` 及 execution owner/fencing 后，重新连接 Relay：

```text
config(sessionMode=resume, sessionId=<真实 Relay session>)
-> session-ready
-> approval-response
-> 剩余业务事件
```

ChatService 只发送本文件定义的 `approval_id/approved/scope/questionnaire_answers`，不发送旧
`request_id`、扁平答案、metadata 或 timestamp。Relay 必须在物理连接关闭后保留 pending questionnaire，
并允许同一 `runtimeSessionId + approval_id` 恢复；Relay 自身问卷超时应关闭，或大于前端等待和重连时间。
因此，上述 60 秒 Relay 内部超时不能直接作为 ChatService 集成环境的默认值，除非前端截止时间及重连窗口
明确小于该值。

ChatService 在连接 Relay 前先以 run/execution owner/fencing 条件持久化 run-B 的最终 Runtime 路由。
更新失败时不会输出回答确认事件，也不会建立 Relay 连接。续接失败按发送边界处理：

- `approval-response` 进入 WebSocket outbound 前失败：条件恢复 Binding 到 run-A，Interaction 恢复
  `WAITING`，允许使用同一 `interactionId` 重试。
- `approval-response` 进入 outbound 后失败：结果视为未知，不自动重发；Interaction 和仍由 run-B 持有的
  ACTIVE Binding 均取消，前端必须发起新的 `NEXT`。
- `RUNTIME_SESSION_UNAVAILABLE` 或 Binding 恢复失败：按不可重试处理并取消 Interaction 与 Binding。

发送阶段只在当前 JVM 请求链内记录，不写入 Relay 请求、ChatEvent、Redis 或数据库。

可选配置 `financeex.relay.questionnaire-wait-timeout` 默认 `0s`。零值表示永久等待；正数只生成供前端使用的
`autoActionAt/autoActionTimeoutMs/autoActionType=IGNORE_QUESTIONNAIRE`，ChatService 不启动后台定时任务。

等待期间取消统一使用 run stop 接口。前端从 `stream-status.waitingSourceRunId` 取得 run-A：

```http
POST /v1/chat/runs/{waitingSourceRunId}/stop
```

ChatService 原子取消 Interaction 和其引用的 ACTIVE Relay Binding；run-A 仍保留 `WAITING_USER` 历史状态。
物理问卷连接已经关闭时，ChatService 使用 Interaction 保存的真实 `runtimeSessionId` 建立临时连接，完成
`config(sessionMode=resume) -> session-ready -> stop_all_agents` 后释放连接。下游停止失败不会恢复本地等待，
用户仍可立即发起新的 `NEXT` 请求。
