# 下游领域 Agent 控制事件契约设计

本文档定义 ChatService 与下游领域 Agent 之间的控制事件协议。目标是让下游 Agent 在无法直接完成用户请求时，返回机器可读的拒答、澄清、审批或路由建议信号，ChatService 据此执行重新路由、向用户澄清、发起审批或最终拒答。

本文档只约定 ChatService 到下游 Agent 的内部 wire contract，不作为前端直接依赖。前端仍只消费 ChatService 标准事件，例如 `runtime.refusal`、`runtime.input_required`、`runtime.approval_required`、`runtime.routing`、`message.*` 和 `run.*`。

## 设计目标

- 下游 Agent 不通过自然语言文案表达拒答原因，必须返回稳定的 `type`、`reasonCode` 和 `action`。
- 业务拒答不等同于技术失败。业务拒答用 `agent.refusal`、`agent.input_required`、`agent.approval_required` 表达；技术异常才使用 `agent.error`。
- 下游 Agent 只能给出建议动作，最终是否重路由、澄清、审批或结束本轮由 ChatService 根据路由策略决定。
- 所有控制事件必须可审计、可恢复、可观测，并能映射到 ChatService 标准事件事实源。
- 下游返回内容不得包含 Cookie、Authorization、token、password、secret 等敏感信息。

## 下游事件类型

下游 Agent 流式响应中至少支持以下事件类型：

| type | 场景 | ChatService 处理 |
| --- | --- | --- |
| `agent.delta` | 正常回答增量 | 映射为 `message.delta` |
| `agent.snapshot` | 最终回答快照，可选 | 映射为 `message.snapshot` |
| `agent.completed` | 当前 Agent 输出结束 | 映射为 `message.completed` 或作为本 Agent 分段结束信号 |
| `agent.refusal` | Agent 明确不能处理当前请求 | 根据 `action` 重路由、澄清、审批或最终拒答 |
| `agent.input_required` | 信息不足，需要用户补充 | 映射为澄清请求，并结束或挂起当前 run |
| `agent.approval_required` | 需要用户授权、审批或确认风险 | 映射为审批请求，并结束或挂起当前 run |
| `agent.error` | 技术失败 | 进入失败、重试或降级策略 |

如果下游暂时无法改造成多个具体类型，也可以过渡性返回 `agent.control`，并在 `controlType` 中区分 `REFUSAL`、`INPUT_REQUIRED`、`APPROVAL_REQUIRED`。新接入 Agent 推荐直接使用具体类型。

## 通用字段

所有事件建议包含以下通用字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | string | 是 | 事件类型，例如 `agent.refusal`。 |
| `eventId` | string | 否 | 下游事件 ID，用于排障和幂等去重。 |
| `agentId` | string | 是 | 当前领域 Agent 标识。 |
| `agentName` | string | 否 | 当前领域 Agent 展示名。 |
| `traceId` | string | 否 | 下游链路追踪 ID。 |
| `timestamp` | string | 否 | ISO-8601 时间。 |
| `schemaVersion` | string | 否 | 协议版本，当前建议 `agent-control-v1`。 |
| `metadata` | object | 否 | 非敏感扩展信息。不得放入凭据、Cookie 或 token。 |

控制类事件还应包含：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `reasonCode` | string | 是 | 机器可读原因码。 |
| `reason` | string | 否 | 给 ChatService 和排障使用的简短原因。 |
| `recoverable` | boolean | 是 | 是否可通过重路由、补充信息、审批或重试恢复。 |
| `action` | string | 是 | 下游建议动作。ChatService 可采纳或覆盖。 |
| `userMessage` | string | 是 | 可安全展示给用户的短文案。不得包含内部栈、敏感字段或完整下游 payload。 |

## reasonCode 枚举

| reasonCode | 含义 | 常见 action |
| --- | --- | --- |
| `OUT_OF_DOMAIN` | 当前问题超出该 Agent 领域范围 | `REROUTE` |
| `UNSUPPORTED_CAPABILITY` | 属于领域内问题，但当前 Agent 没有能力处理 | `REROUTE`、`ESCALATE_RUNTIME` |
| `AMBIGUOUS_INTENT` | 用户意图不明确 | `CLARIFY` |
| `MISSING_CONTEXT` | 缺少上下文，无法判断用户真实诉求 | `CLARIFY` |
| `MISSING_REQUIRED_SLOT` | 缺少必填业务槽位 | `CLARIFY` |
| `PERMISSION_DENIED` | 用户无权执行该查询或动作 | `FINAL_REFUSE`、`REQUEST_APPROVAL` |
| `PERMISSION_TOO_BROAD` | 请求权限范围过大，需要用户确认或缩小范围 | `REQUEST_APPROVAL`、`CLARIFY` |
| `AUTH_REQUIRED` | 需要重新认证或补充授权 | `REQUEST_APPROVAL` |
| `POLICY_RESTRICTED` | 合规、风控或安全策略限制 | `FINAL_REFUSE` |
| `DATA_NOT_FOUND` | 未找到数据 | `CLARIFY`、`FINAL_REFUSE` |
| `TEMPORARY_UNAVAILABLE` | 下游依赖临时不可用，但不是本次协议解析失败 | `RETRY`、`ESCALATE_RUNTIME` |

## action 枚举

| action | 含义 | ChatService 建议行为 |
| --- | --- | --- |
| `REROUTE` | 建议换路由或换 Agent | 参考 `routeSuggestion` 重新进入路由策略 |
| `CLARIFY` | 建议向用户追问 | 生成澄清请求 |
| `REQUEST_APPROVAL` | 建议请求用户授权或审批 | 生成审批请求 |
| `FINAL_REFUSE` | 不应继续处理 | 给用户最终拒答，并结束本轮 |
| `RETRY` | 可重试当前 Agent 或依赖 | 按配置限次重试 |
| `ESCALATE_RUNTIME` | 建议升级到通用 Runtime | 路由到 `AGENT_RUNTIME` 或其他兜底能力 |

## agent.refusal

`agent.refusal` 表示下游 Agent 明确不能继续处理当前请求。它是业务控制信号，不是技术失败。

```json
{
  "type": "agent.refusal",
  "schemaVersion": "agent-control-v1",
  "eventId": "evt_001",
  "agentId": "tax-agent",
  "agentName": "税务 Agent",
  "traceId": "trace_xxx",
  "reasonCode": "OUT_OF_DOMAIN",
  "reason": "当前问题不属于税务 Agent 能力范围",
  "recoverable": true,
  "action": "REROUTE",
  "userMessage": "这个问题可能需要由其他 Agent 处理。",
  "routeSuggestion": {
    "targetType": "AGENT_RUNTIME",
    "targetAgentId": null,
    "confidence": 0.78,
    "reason": "问题更像综合财经咨询任务"
  }
}
```

### routeSuggestion

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `targetType` | string | 否 | 建议目标：`DOMAIN_AGENT`、`SUB_AGENT`、`AGENT_RUNTIME`、`INTENT_SERVICE`。 |
| `targetAgentId` | string | 否 | 建议目标 Agent。为空表示仅建议目标类型。 |
| `confidence` | number | 否 | 建议置信度，范围 0 到 1。 |
| `reason` | string | 否 | 建议原因。 |

ChatService 不应无条件信任 `routeSuggestion`。建议将其作为路由信号之一，再结合当前用户、会话、权限、历史路由和全局策略决策。

## agent.input_required

`agent.input_required` 表示信息不足，需要用户补充后才能继续。

```json
{
  "type": "agent.input_required",
  "schemaVersion": "agent-control-v1",
  "eventId": "evt_002",
  "agentId": "expense-agent",
  "traceId": "trace_xxx",
  "reasonCode": "MISSING_REQUIRED_SLOT",
  "reason": "缺少报销单号或报销时间范围",
  "recoverable": true,
  "action": "CLARIFY",
  "userMessage": "请补充报销单号或报销时间范围。",
  "inputRequest": {
    "inputType": "CLARIFICATION",
    "missingSlots": ["expenseNo", "period"],
    "minRequired": 1,
    "questions": [
      {
        "id": "expenseNo",
        "label": "报销单号",
        "type": "text",
        "required": false
      },
      {
        "id": "period",
        "label": "报销时间范围",
        "type": "date_range",
        "required": false
      }
    ]
  }
}
```

### inputRequest

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `inputType` | string | 是 | `CLARIFICATION`、`MISSING_SLOT`、`ROUTE_SELECTION`。 |
| `missingSlots` | array | 否 | 缺失槽位编码。 |
| `minRequired` | number | 否 | 至少需要回答几个问题。 |
| `questions` | array | 是 | 面向用户的澄清问题。 |

### questions[]

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 问题或槽位 ID。 |
| `label` | string | 是 | 展示给用户的问题。 |
| `type` | string | 是 | `text`、`number`、`date`、`date_range`、`single_select`、`multi_select`、`boolean`。 |
| `required` | boolean | 是 | 是否必填。 |
| `options` | array | 否 | 选择型问题的选项。 |
| `defaultValue` | any | 否 | 默认值。 |
| `placeholder` | string | 否 | 输入提示。 |

ChatService 收到该事件后，建议映射为 `runtime.input_required`，并根据产品策略决定当前 run 是结束为 `CLARIFICATION_REQUIRED`，还是进入等待用户输入状态。当前 ChatService 更适合结束当前 run，用户补充后新建下一轮 run。

## agent.approval_required

`agent.approval_required` 表示继续处理需要用户确认授权、审批风险或扩大数据范围。

```json
{
  "type": "agent.approval_required",
  "schemaVersion": "agent-control-v1",
  "eventId": "evt_003",
  "agentId": "finance-data-agent",
  "traceId": "trace_xxx",
  "reasonCode": "PERMISSION_TOO_BROAD",
  "reason": "用户请求查询部门级财务数据，超过默认个人数据范围",
  "recoverable": true,
  "action": "REQUEST_APPROVAL",
  "userMessage": "该操作需要查询部门级财务数据，请确认授权范围。",
  "approvalRequest": {
    "approvalType": "DATA_SCOPE",
    "scope": {
      "resource": "finance_records",
      "operation": "read",
      "range": "department",
      "fields": ["amount", "vendor", "employeeName"]
    },
    "riskLevel": "MEDIUM",
    "expiresInSeconds": 300
  }
}
```

### approvalRequest

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `approvalType` | string | 是 | 审批类型。 |
| `scope` | object | 是 | 请求授权的资源、动作和范围。 |
| `riskLevel` | string | 是 | `LOW`、`MEDIUM`、`HIGH`、`CRITICAL`。 |
| `expiresInSeconds` | number | 否 | 授权请求有效期。 |
| `approvalId` | string | 否 | 下游审批请求 ID。没有时 ChatService 可生成。 |

`approvalType` 建议枚举：

| approvalType | 含义 |
| --- | --- |
| `DATA_SCOPE` | 扩大数据范围，例如个人到部门。 |
| `SENSITIVE_FIELD` | 访问敏感字段。 |
| `EXTERNAL_ACTION` | 调用外部系统动作。 |
| `WRITE_OPERATION` | 写入、修改或提交业务数据。 |
| `HIGH_RISK_OPERATION` | 高风险操作。 |

## agent.error

`agent.error` 只用于技术失败，例如超时、依赖异常、协议解析失败。业务上的“不能处理”不要使用 `agent.error`。

```json
{
  "type": "agent.error",
  "schemaVersion": "agent-control-v1",
  "eventId": "evt_004",
  "agentId": "tax-agent",
  "traceId": "trace_xxx",
  "errorCode": "UPSTREAM_TIMEOUT",
  "message": "Agent tool timeout",
  "retryable": true,
  "userMessage": "当前服务暂时不可用，请稍后重试。"
}
```

## ChatService 决策规则

ChatService 对控制事件的基础处理规则如下：

| 下游事件 | 条件 | ChatService 行为 |
| --- | --- | --- |
| `agent.refusal` | `action=REROUTE` 且 `recoverable=true` | 触发重路由策略，可参考 `routeSuggestion`。 |
| `agent.refusal` | `action=CLARIFY` | 转为澄清请求。 |
| `agent.refusal` | `action=REQUEST_APPROVAL` | 转为审批请求。 |
| `agent.refusal` | `action=FINAL_REFUSE` 或 `recoverable=false` | 给用户最终拒答，不再重路由。 |
| `agent.input_required` | 任意 | 转为澄清请求。 |
| `agent.approval_required` | 任意 | 转为审批请求。 |
| `agent.error` | `retryable=true` | 按配置限次重试或降级。 |
| `agent.error` | `retryable=false` | 转为 `run.failed` 或兜底系统响应。 |

ChatService 应避免重路由循环。建议在同一 run 或同一用户问题内记录已尝试的 route/agent，当同一 `reasonCode` 反复出现时，改为澄清或最终拒答。

## ChatService 标准事件映射

下游控制事件不应原样暴露给前端。建议映射如下：

| 下游事件 | ChatService 标准事件 |
| --- | --- |
| `agent.refusal` | `runtime.refusal` |
| 重路由开始/完成/失败 | `runtime.routing` |
| `agent.input_required` | `runtime.input_required` |
| `agent.approval_required` | `runtime.approval_required` |
| 最终拒答用户文案 | `message.snapshot` 或 `message.delta` |
| 控制类回答结束 | `message.completed` |
| 本轮结束 | `run.completed` |
| 技术失败 | `run.failed` |

建议在 `message.completed.payload.finishReason` 中记录：

| finishReason | 含义 |
| --- | --- |
| `COMPLETED` | 正常完成。 |
| `REFUSED` | 最终拒答。 |
| `NEED_CLARIFICATION` | 需要用户澄清。 |
| `PERMISSION_REQUIRED` | 需要用户授权或审批。 |
| `REROUTED` | 本 Agent 拒答后已重路由。 |

建议在 `run.completed.payload.outcome` 中记录：

| outcome | 含义 |
| --- | --- |
| `ANSWERED` | 正常回答完成。 |
| `REROUTED_ANSWERED` | 重路由后完成回答。 |
| `CLARIFICATION_REQUIRED` | 本轮以澄清请求结束。 |
| `PERMISSION_REQUIRED` | 本轮以审批请求结束。 |
| `REFUSED` | 本轮最终拒答。 |
| `NO_ROUTE` | 没有可用路由。 |

## 典型流程序列

### 领域外问题重路由

```text
下游 DomainAgent -> agent.refusal(action=REROUTE, reasonCode=OUT_OF_DOMAIN)
ChatService -> runtime.refusal
ChatService -> runtime.routing(status=REROUTE_STARTED)
ChatService -> 重新选择 AgentRuntime 或其他 Agent
ChatService -> runtime.routing(status=REROUTE_COMPLETED)
ChatService -> message.delta/message.snapshot
ChatService -> message.completed(finishReason=COMPLETED)
ChatService -> run.completed(outcome=REROUTED_ANSWERED)
```

### 信息不足需要澄清

```text
下游 DomainAgent -> agent.input_required(reasonCode=MISSING_REQUIRED_SLOT)
ChatService -> runtime.input_required
ChatService -> message.snapshot(澄清问题)
ChatService -> message.completed(finishReason=NEED_CLARIFICATION)
ChatService -> run.completed(outcome=CLARIFICATION_REQUIRED)
```

### 权限过大需要审批

```text
下游 DomainAgent -> agent.approval_required(reasonCode=PERMISSION_TOO_BROAD)
ChatService -> runtime.approval_required
ChatService -> message.snapshot(授权确认文案)
ChatService -> message.completed(finishReason=PERMISSION_REQUIRED)
ChatService -> run.completed(outcome=PERMISSION_REQUIRED)
```

### 不可恢复的最终拒答

```text
下游 DomainAgent -> agent.refusal(action=FINAL_REFUSE, recoverable=false, reasonCode=POLICY_RESTRICTED)
ChatService -> runtime.refusal
ChatService -> message.snapshot(userMessage)
ChatService -> message.completed(finishReason=REFUSED)
ChatService -> run.completed(outcome=REFUSED)
```

## 兼容现有 DomainAgent 协议

当前 DomainAgent 可能返回 `content`、`processResult`、`searchList`、`sourcesDocuments`、`cardUrl`、`diyCardScene`、`openCard`、`endFlag` 等字段。新增控制事件时，建议优先使用显式 `type`：

```json
{
  "type": "agent.refusal",
  "reasonCode": "OUT_OF_DOMAIN",
  "recoverable": true,
  "action": "REROUTE",
  "userMessage": "这个问题需要其他 Agent 处理。"
}
```

如果短期内下游不能输出 `type`，可以临时把控制字段放在现有 JSON frame 中：

```json
{
  "controlType": "REFUSAL",
  "reasonCode": "OUT_OF_DOMAIN",
  "recoverable": true,
  "action": "REROUTE",
  "userMessage": "这个问题需要其他 Agent 处理。",
  "endFlag": true
}
```

ChatService normalizer 可以将该兼容格式归一化为 `agent.refusal` 语义，但新 Agent 不建议继续使用无 `type` 的兼容格式。

## 安全与审计要求

- `userMessage` 只能包含面向用户的安全文案。
- `reason` 用于排障，不应包含敏感数据。
- `metadata`、`routeSuggestion`、`inputRequest`、`approvalRequest` 都必须经过敏感字段过滤。
- 下游不得返回 Cookie、Authorization、token、secret、password、credential、apiKey、accessKey 等字段；ChatService 收到后也必须脱敏。
- 审计中至少记录 `runId`、`sessionId`、`agentId`、`traceId`、`type`、`reasonCode`、`action`、`recoverable` 和 ChatService 最终决策。

## 版本演进

当前建议版本为 `agent-control-v1`。后续新增字段应保持向后兼容：

- 新增字段允许。
- 已定义字段不得改变语义。
- `reasonCode` 和 `action` 新增枚举时，ChatService 对未知枚举应降级为 `runtime.event` 或 `FINAL_REFUSE`，不能抛出未处理异常导致 run 悬挂。
- 废弃字段至少保留一个灰度周期。
