# 意图服务接口文档

本文整理 ChatService / Supervisor 调用意图服务的新接口约定。接口用于把用户最新问题和路由上下文提交给意图服务，由意图服务返回最终路由动作：直接路由、进入规划、无匹配或继续澄清。

## 1. 接口概览

```http
POST {intent-base-url}/intent-recognition-configuration/getIntentDecision
Content-Type: application/json
Authorization: {dynamicToken}
```

文档中简称该接口为 `/getIntentDecision`。

调用方需要先通过企业鉴权服务获取动态 token，再把 token 放入 `Authorization` 请求头。`APP_ID`、静态 token、动态 token 获取地址由部署环境配置，不应写死在代码或文档示例中。

## 2. 请求结构

```json
{
  "accessName": "eureka2_260718",
  "query": "对账差异识别",
  "userId": "00859938",
  "conversationContext": {
    "routeTrigger": "first_turn",
    "lastIntentRejectReason": null,
    "history": []
  },
  "options": {
    "trace": false
  }
}
```

### 2.1 顶层字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `accessName` / `intentID` / `entranceID` | string | 三选一 | 意图入口，由意图服务配置决定。当前示例使用 `accessName`。 |
| `query` | string | 是 | 当前待分类用户问题。澄清回答场景下，填用户对澄清问题的最新回答。 |
| `userId` | string | 否 | 用户工号或用户标识，用于画像增强、审计或日志。 |
| `conversationContext` | object | 否 | 多轮路由上下文。首轮可为空，但建议显式传空结构。 |
| `options.trace` | boolean | 否 | 是否返回调试 trace。生产调用建议为 `false`。 |

### 2.2 conversationContext

```json
{
  "routeTrigger": "domain_reject",
  "lastIntentRejectReason": {
    "lastIntent": "财经深度研究",
    "domainRejectMessage": "深度研究无法给出支付成功率下降处理相关内容，返回主入口重新决策"
  },
  "history": [
    {
      "type": "route",
      "query": "查一下 3 月 19 到 20 号各渠道支付成功率有没有明显下降",
      "intent": "财经智能问数"
    }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `routeTrigger` | string | 否 | 触发重新分流的原因。 |
| `lastIntentRejectReason` | object | 否 | 上一个跳出的意图及拒答原因。仅传当前这次拒答，不累计历史拒答噪音。 |
| `history` | array | 否 | 历史成功路由记录和未完成的澄清链路，按时间从早到晚排列。 |

### 2.3 routeTrigger 枚举

| 枚举 | 说明 | 是否调用意图服务 |
| --- | --- | --- |
| `first_turn` | 首轮路由。 | 是 |
| `domain_reject` | 当前 DomainAgent 拒答、低置信或不属于当前领域，回到 Supervisor 重新分流。 | 是 |
| `user_correction` | 用户主动纠正路由，例如手动关闭当前领域能力后重新判断。 | 是 |
| `fallback_followup` | 上一轮已进入 Relay/no_match 兜底并正常完成，本轮作为兜底后的追问重新判断。 | 是 |
| `clarify_answer` | 用户回答了意图服务上一轮澄清问题，需要继续分类。 | 是 |
| `explicit_switch` | 用户通过前端显式选择目标能力。 | 否 |

`explicit_switch` 不走意图服务，但建议把用户选择结果追加到在线 history，作为后续路由上下文。ChatService 对外只允许前端通过 `/v1/chat/runs.forceReroute=true` 显式触发用户纠正；`first_turn/domain_reject/fallback_followup/clarify_answer` 都由后端根据会话状态自动生成。

## 3. history 结构

`conversationContext.history` 只放在线路由需要的摘要，默认取最新 TopK。完整链路、原始问题和澄清过程应保存在 ChatService 审计日志或消息历史中。

### 3.1 成功路由记录

```json
{
  "type": "route",
  "query": "支付成功率这个指标口径是怎么算的？",
  "intent": "财经知识助手"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | string | 固定为 `route`。 |
| `query` | string | 当时成功路由的用户问题。 |
| `intent` | string | 当时命中的意图或 DomainAgent 名称。 |

### 3.2 澄清过程记录

用户回答澄清问题时，用户回答放在本轮顶层 `query` 中；上一轮触发澄清的问题和澄清问题写入 `history.type=clarify`。

```json
{
  "type": "clarify",
  "query": "再帮我看下方案",
  "clarifyQuestion": "你想看处理方案还是项目方案？",
  "clarificationType": "AMBIGUOUS_ROUTE"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | string | 固定为 `clarify`。 |
| `query` | string | 触发这轮澄清的用户输入。 |
| `clarifyQuestion` | string | 意图服务上一轮返回、Supervisor 展示给用户的问题。 |
| `clarificationType` | string | 可选。当前为 `AMBIGUOUS_ROUTE` 或 `UNCLEAR_REFERENCE`。 |

多轮澄清时可以追加多条 `clarify`。澄清成功后，建议把多条澄清过程折叠成一条 `route` 记录，避免长期把完整澄清链路放入在线路由上下文。

## 4. 响应结构

```json
{
  "status": "success",
  "code": 200,
  "message": "success",
  "data": {
    "result": {
      "routeAction": "ROUTE_SINGLE",
      "items": [
        {
          "intentId": "domain_agent_finance_knowledge",
          "intentName": "财经知识助手",
          "confidence": 0.92,
          "source": "llm",
          "accessName": "ex_finance_knowledge_agent",
          "resourceInstruction": {
            "resourceId": "resource_finance_knowledge"
          }
        }
      ],
      "clarification": null
    },
    "trace": {}
  }
}
```

调用方必须优先读取 `data.result.routeAction`，不能只通过 `items.length` 判断结果。ChatService 固定把
`ROUTE_SINGLE.items[0].accessName` 经可选字面量前缀归一化后作为可调用的 `DomainAgentId/skillId`；
未配置前缀或前缀不匹配时使用原始 `accessName`。`intentId` 保留为意图编码，
`resourceInstruction.resourceId` 只进入诊断字段和统计记录；缺少 item 或有效 `accessName` 时视为协议失败，
按重试和 `financeex.intent.failure-strategy` 处理，不会使用 `intentId/resourceId` 兜底。

### 4.1 routeAction

| routeAction | items | clarification | ChatService / Supervisor 行为 |
| --- | --- | --- | --- |
| `ROUTE_SINGLE` | 1 个 | null | 直接路由到 `items[0]` 对应 DomainAgent。 |
| `ROUTE_MULTI` | 多个 | null | 进入 Supervisor / Relay 规划，适合复杂任务。 |
| `NO_MATCH` | 空 | null | 当前领域无匹配，进入兜底处理或默认 Runtime。 |
| `CLARIFY` | 空或候选建议 | 非空 | 展示 `clarification.clarifyQuestion`，等待用户回答后再次调用意图服务。 |

### 4.2 clarification

```json
{
  "type": "AMBIGUOUS_ROUTE",
  "clarifyQuestion": "你想看处理方案还是项目方案？",
  "candidateIntents": [
    {
      "intentId": "deep_analysis",
      "intentName": "深度分析",
      "confidence": 0.72
    }
  ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | string | 澄清类型。 |
| `clarifyQuestion` | string | 展示给用户的澄清问题，建议控制在 40 个中文字符以内。 |
| `candidateIntents` | array | 候选意图。`AMBIGUOUS_ROUTE` 可返回 Top2-3，`UNCLEAR_REFERENCE` 通常为空。 |

澄清类型：

| type | 含义 | candidateIntents |
| --- | --- | --- |
| `AMBIGUOUS_ROUTE` | 多个领域能力都可能承接，但证据不足。 | 可返回候选意图，用于前端展示选项。 |
| `UNCLEAR_REFERENCE` | 用户问题存在指代、附件、对象或上下文缺失。 | 通常为空。 |

澄清约束：

- `CLARIFY` 不是最终路由结果，不调用 DomainAgent，也不写成功 route 历史。
- 澄清只用于完成路由判断，不采集 DomainAgent 执行业务所需的详细参数。
- 用户回答澄清后，顶层 `query` 使用用户最新回答，`routeTrigger=clarify_answer`。

## 5. 典型调用场景

### 5.1 首轮路由

```json
{
  "accessName": "eureka2_260718",
  "query": "查一下 3 月 19 到 20 号各渠道支付成功率有没有明显下降",
  "userId": "00859938",
  "conversationContext": {
    "routeTrigger": "first_turn",
    "lastIntentRejectReason": null,
    "history": []
  },
  "options": {
    "trace": false
  }
}
```

预期：

- `ROUTE_SINGLE`：直接绑定并调用命中的 DomainAgent。
- `ROUTE_MULTI`：进入复杂任务规划。
- `CLARIFY`：创建意图澄清等待态。
- `NO_MATCH`：进入兜底 Runtime。

### 5.2 DomainAgent 拒答后重新分流

```json
{
  "accessName": "eureka2_260718",
  "query": "支付成功率这个指标口径是怎么算的？",
  "userId": "00859938",
  "conversationContext": {
    "routeTrigger": "domain_reject",
    "lastIntentRejectReason": {
      "lastIntent": "财经智能问数",
      "domainRejectMessage": "这是指标口径解释，不属于问数能力范围"
    },
    "history": [
      {
        "type": "route",
        "query": "查一下 3 月 19 到 20 号各渠道支付成功率有没有明显下降",
        "intent": "财经智能问数"
      }
    ]
  },
  "options": {
    "trace": false
  }
}
```

说明：

- `lastIntentRejectReason` 只放当前这一次拒答。
- 前几轮拒答不进入 `lastIntentRejectReason`，避免放大噪声。
- 历史成功路由放入 `history.type=route`。

### 5.3 意图澄清回答

上一轮意图服务返回：

```json
{
  "routeAction": "CLARIFY",
  "clarification": {
    "type": "AMBIGUOUS_ROUTE",
    "clarifyQuestion": "你想看处理方案还是项目方案？"
  }
}
```

用户回答“处理方案”后，再次调用：

```json
{
  "accessName": "eureka2_260718",
  "query": "处理方案",
  "userId": "00859938",
  "conversationContext": {
    "routeTrigger": "clarify_answer",
    "lastIntentRejectReason": null,
    "history": [
      {
        "type": "clarify",
        "query": "再帮我看下方案",
        "clarifyQuestion": "你想看处理方案还是项目方案？",
        "clarificationType": "AMBIGUOUS_ROUTE"
      }
    ]
  },
  "options": {
    "trace": false
  }
}
```

说明：

- 用户回答不写入 `history.answer`，而是作为本轮 `query`。
- 如果仍返回 `CLARIFY`，继续追加一条 `history.type=clarify`。
- 建议 ChatService 限制最大澄清轮数，超过后进入兜底 Runtime。

### 5.4 用户纠正路由

```json
{
  "accessName": "eureka2_260718",
  "query": "我是想看支付成功率下降后怎么处理",
  "userId": "00859938",
  "conversationContext": {
    "routeTrigger": "user_correction",
    "lastIntentRejectReason": {
      "lastIntent": "财经知识助手",
      "domainRejectMessage": "用户手动纠正当前路由"
    },
    "history": [
      {
        "type": "route",
        "query": "支付成功率这个指标口径是怎么算的？",
        "intent": "财经知识助手"
      }
    ]
  },
  "options": {
    "trace": false
  }
}
```

## 6. 响应示例

### 6.1 单意图命中

```json
{
  "status": "success",
  "code": 200,
  "data": {
    "result": {
      "routeAction": "ROUTE_SINGLE",
      "items": [
        {
          "intentId": "domain_agent_finance_data_query",
          "intentName": "财经智能问数",
          "confidence": 0.94,
          "source": "vector",
          "accessName": "eureka2_260718",
          "resourceInstruction": {
            "resourceId": "resource_finance_data_query"
          }
        }
      ],
      "clarification": null
    }
  }
}
```

### 6.2 多意图命中

```json
{
  "status": "success",
  "code": 200,
  "data": {
    "result": {
      "routeAction": "ROUTE_MULTI",
      "items": [
        {
            "intentId": "domain_agent_finance_data_query",
          "intentName": "财经智能问数",
          "confidence": 0.86,
          "resourceInstruction": {
              "resourceId": "resource_finance_data_query"
          }
        },
        {
            "intentId": "domain_agent_finance_knowledge",
          "intentName": "财经知识助手",
          "confidence": 0.81,
          "resourceInstruction": {
              "resourceId": "resource_finance_knowledge"
          }
        }
      ],
      "clarification": null
    }
  }
}
```

### 6.3 需要澄清

```json
{
  "status": "success",
  "code": 200,
  "data": {
    "result": {
      "routeAction": "CLARIFY",
      "items": [],
      "clarification": {
        "type": "AMBIGUOUS_ROUTE",
        "clarifyQuestion": "你想看处理方案还是项目方案？",
        "candidateIntents": [
          {
            "intentId": "deep_analysis",
            "intentName": "财经深度研究",
            "confidence": 0.72
          }
        ]
      }
    }
  }
}
```

### 6.4 无匹配

```json
{
  "status": "success",
  "code": 200,
  "data": {
    "result": {
      "routeAction": "NO_MATCH",
      "items": [],
      "clarification": null
    }
  }
}
```

## 7. trace 调试

`options.trace=true` 时，响应可能包含 `data.trace`，用于排查意图决策过程。常见字段：

| 字段 | 说明 |
| --- | --- |
| `vectorSearch` | 向量检索过程、候选和耗时。 |
| `promptExpansion` | Prompt 增强过程和耗时。 |
| `llmTraversal.branches[].rounds[]` | LLM 多轮遍历、systemPrompt、userPrompt、rawResult 和 parsedResult。 |
| `decision` | 中间裁决信息。 |
| `final` | 最终裁决结果。 |

调试建议：

- 本地联调可打开 `trace=true` 打印 prompt 和模型原始输出。
- 生产链路默认关闭 trace，避免响应过大和敏感上下文外泄。
- 如果 ES 高置信命中或规则提前触发，`llmTraversal.branches` 为空是正常现象。

## 8. ChatService 对接规则

1. 必须优先读取 `data.result.routeAction`。
2. `ROUTE_SINGLE`：读取唯一 `items[0].accessName`，按 `financeex.intent.response-access-name-prefix` 移除一次匹配的开头前缀后作为 DomainAgentId/skillId，创建或刷新 `provider=domain-agent` 的 RuntimeBinding；该配置为空时使用原始值。`intentId` 只作为意图编码，`resourceInstruction.resourceId` 只记录排障，均不参与路由；缺少 item 或有效 `accessName` 视为协议失败，按重试和 `financeex.intent.failure-strategy` 处理；`confidence` 只用于记录，不参与二次裁决。
3. `ROUTE_MULTI`：进入复杂任务规划，通常走 Relay Runtime。
4. `NO_MATCH`：进入兜底 Runtime 或通用处理。
5. `CLARIFY`：本轮 run 进入 `WAITING_USER`，写入 `run.waiting_user`，前端通过 `POST /v1/chat/runs` + `runMode=CONTINUE_INTERACTION` 提交澄清回答。
6. 意图澄清属于路由阶段，不创建 RuntimeBinding，不调用 AgentRuntime。
7. 用户回答意图澄清后，创建 continuation run，再次调用 `/getIntentDecision`。
8. DomainAgent 拒答回流时，`routeTrigger=domain_reject`，只传当前这一次拒答原因。
9. `history` 按时间顺序传最新 TopK；澄清链路未完成时，TopK 必须保留当前澄清上下文。
