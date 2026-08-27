## 11. 置信度接口 `/getIntentConfidence`

0825 增加置信度接口

### 11.1 接口说明

置信度接口用于获取当前问题的意图置信度分布，返回 top6 候选意图及置信度（归一化 0~1）。

**调用时机**：

- 在路由接口（`/getIntentDecision`）之后调用
- 通过 `messageId` 复用路由接口的日志记录，避免重复计算
- 调用方自己决定什么时候调用，不阻塞路由接口的快速返回

**核心特性**：

- 通过 `messageId` 关联路由接口的日志，复用 ES 检索结果和 userPrompt（节省约 0.35s）
- 如果 `messageId` 对应的日志不存在，则正常执行 ES 检索和 userPrompt 生成
- 置信度统一由 LLM 输出，保证口径一致

### 11.2 请求格式

```json
{
  "messageId": "msg_d0a6c41dd1ed43efbc9ece562b66d44",
  // 以下字段直接查询数据库同一messageId的最新数据
  // "entranceID": "xxx",
  // "accessName": "financial_supervisor",
  // "query": "查一下3月19到20号各渠道支付成功率有没有明显下降",
  // "userId": "h008xxx",
  // "conversationContext": {
  //   "routeTrigger": "first_turn",
  //   "history": []
  // },
  "options": {
    "llmModelId": "xxx",
    "trace": false,
    "logEnabled": true
  }
}
```

**请求字段说明**：

| 字段 | 类型 | 是否必填 | 说明 |
|------|------|----------|------|
| `messageId` | string | 是 | 1号消息ID，用于关联路由接口的日志记录。如果日志已存在，复用 ES 检索结果和 userPrompt；如果不存在，正常执行。 |
| `entranceID` / `accessName` | string | 二选一 | 意图入口，同路由接口 |
| `query` | string | 是 | 用户问题，同路由接口 |
| `userId` | string | 否 | 用户ID |
| `conversationContext` | object | 否 | 多轮对话上下文，同路由接口 |
| `options` | object | 否 | 其他配置项 |

### 11.3 响应格式

```json
{
  "status": "success",
  "code": 200,
  "message": "success",
  "data": {
    "confidence": {
      "topCandidates": [
        {
          "intentId": "607294d4b2da408894fba3e92aad3c25",
          "accessName": "EX_xxxxxxxxxxxxid",
          "intentName": "对账差异分析",
          "confidence": 0.92
        },
        {
          "intentId": "9ecbdfcb7eb64782b20582c37c89f6b1",
          "accessName": "EX_xxxxxxxxxxxxid",
          "intentName": "智能文档",
          "confidence": 0.75
        },
        {
          "intentId": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
          "accessName": "EX_xxxxxxxxxxxxid",
          "intentName": "财经知识问答",
          "confidence": 0.61
        },
        {
          "intentId": "q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6",
          "accessName": "EX_xxxxxxxxxxxxid",
          "intentName": "深度分析",
          "confidence": 0.43
        },
        {
          "intentId": "z1x2c3v4b5n6m7l8k9j0h1g2f3d4s5a6",
          "accessName": "EX_xxxxxxxxxxxxid",
          "intentName": "报表查询",
          "confidence": 0.28
        },
        {
          "intentId": "p1o2i3u4y5t6r7e8w9q0a1s2d3f4g5h6",
          "accessName": "EX_xxxxxxxxxxxxid",
          "intentName": "系统配置",
          "confidence": 0.15
        }
      ]
    }
  }
}
```

**响应字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `confidence.topCandidates` | array | top6 候选意图及置信度，按置信度降序排列 |
| `topCandidates[].intentId` | string | 意图ID |
| `topCandidates[].intentName` | string | 意图名称 |
| `topCandidates[].confidence` | float | 置信度，归一化 0~1 |

### 11.4 与路由接口的关系

**日志关联**：

- 路由接口和置信度接口通过 `messageId` 关联同一条日志记录
- 路由接口在请求结束时写入路由结果
- 置信度接口在请求结束时写入置信度结果
- 两个接口各自 UPDATE 自己负责的字段，互不干扰

**复用机制**：

- 置信度接口通过 `messageId` 查询路由接口的日志
- 如果日志存在，复用 ES 检索结果和 userPrompt（节省约 0.35s）
- 如果日志不存在，正常执行 ES 检索和 userPrompt 生成

**调用示例**：

```text
1. Supervisor 调用路由接口 /getIntentDecision
   - 传入 messageId: "msg_xxx"
   - 返回路由结果

2. Supervisor 调用置信度接口 /getIntentConfidence
   - 传入相同的 messageId: "msg_xxx"
   - 复用路由接口的日志，快速返回置信度结果
```

### 11.5 异常处理

| 场景 | 处理 |
|------|------|
| `messageId` 为空 | 正常执行 ES 检索和 userPrompt 生成，新建日志记录 |
| `messageId` 对应的日志不存在 | 正常执行 ES 检索和 userPrompt 生成，新建日志记录 |
| `messageId` 对应的日志已存在 | 复用 ES 检索结果和 userPrompt，直接调用 LLM |
| LLM 调用失败 | 返回错误，不写入置信度结果 |

## 12. ChatService 前端代理接口

ChatService 对前端暴露：

```http
POST /v1/chat/intent-candidates
Content-Type: application/json
```

请求只允许传入当前用户拥有的user消息ID：

```json
{"messageId":"msg_d0a6c41dd1ed43efbc9ece562b66d44"}
```

ChatService调用本章下游接口时，请求体严格只包含`messageId`，成功后仅提取
`data.confidence.topCandidates`并直接返回数组。`accessName`保留下游原值；额外返回的
`skillId`只按`financeex.intent.response-access-name-prefix`区分大小写移除一次并trim，
不执行敏感意图或Domain Expert解析。

```json
[
  {
    "intentId": "607294d4b2da408894fba3e92aad3c25",
    "accessName": "EX_finance_query",
    "skillId": "finance_query",
    "intentName": "财经智能问数",
    "confidence": 0.92
  }
]
```

该代理复用`financeex.intent.base-url/timeout/max-retries`、企业鉴权Header Provider及
`financeex.intent.confidence-path`，不缓存、不持久化候选结果。每次逻辑查询只获取一次
企业鉴权Header；仅网络异常、HTTP响应超时、HTTP 408和5xx按指数退避重试，所有其他4xx、
鉴权、配置和协议错误均立即失败。候选查询使用独立的本机并发闸门和鉴权线程池，不与普通
Intent路由共享流式鉴权资源。

候选专用配置如下：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `financeex.intent.candidate.max-concurrency` | `8` | 单实例候选查询最大并发；不排队。 |
| `financeex.intent.candidate.auth-io-max-size` | `2` | 候选鉴权专用阻塞IO线程数。 |
| `financeex.intent.candidate.auth-io-queue-capacity` | `16` | 候选鉴权专用队列容量。 |
| `financeex.intent.candidate.retry-min-backoff` | `200ms` | 第一次重试的基础退避。 |
| `financeex.intent.candidate.retry-max-backoff` | `1s` | 指数退避上限；应用50%抖动。 |

本机容量不足返回`429/INTENT_CANDIDATES_BUSY`；重试耗尽且最终HTTP尝试超时返回
`504/INTENT_CANDIDATES_TIMEOUT`；其他下游、鉴权或协议失败返回
`502/INTENT_CANDIDATES_UPSTREAM_FAILED`。Intent服务返回的HTTP 429不在ChatService内重试，
并按上游失败映射为502，避免与本机容量不足混淆。

## 13. 用户偏好独立记录

前端从本接口候选中选择技能并勾选“记录我的偏好”时，应先提交Run；Run成功受理后再调用：

```http
POST /v1/chat/intent-preference-corrections
Content-Type: application/json
```

```json
{
  "selectionType": "INTENT_CANDIDATE",
  "sourceMessageId": "msg_original",
  "selectedIntent": {
    "intentId": "intent_xxx",
    "intentName": "支付成功率分析"
  },
  "intentAccessName": "finance_pc_entry"
}
```

模糊意图人工选择使用已成功受理或已完成的Interaction作为可信来源：

```json
{
  "selectionType": "AMBIGUOUS_ROUTE",
  "interactionId": "interaction_xxx",
  "intentAccessName": "finance_pc_entry"
}
```

成功返回`204`。偏好按租户、用户和有效Intent入口跨会话生效；同一source user消息重复提交时，
原子更新为最后一次选择。偏好保存失败返回`503/INTENT_PREFERENCE_UNAVAILABLE`，不影响已经受理的Run。
后续阻塞和流式Intent调用都会在请求顶层携带最近记录的`userPreferenceCorrections`；默认最多5条，
由`FINANCEEX_INTENT_USER_PREFERENCE_CORRECTIONS_LIMIT`控制，设为`0`时跳过数据库读取并发送`[]`。
