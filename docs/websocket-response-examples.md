# WebSocket 响应示例与字段说明

本文以 Servlet 实现及提交 `3f6ee883` 为核对基线，面向前端联调，不改变协议。所有 ID、时间、业务内容均为虚构示例，不是生产抓包，也不代表实际前端已联调通过。

入口：`/v1/chat/ws`，生产使用 `wss`，加上部署的 Servlet context path 和 Jalor 网关路由前缀。WebSocket 只传订阅控制和事件，不用它提交 Query、问卷答案、Stop 或异步回调。REST 字段完整约束见[OpenAPI](openapi/financeex-chatservice-v1.yaml)和[前端联调文档](frontend-integration.md)。

## 阅读约定与场景索引

- 标为“客户端 → 服务端”的 JSON 是控制请求；标为“服务端 → 客户端”的 JSON 是完整 WebSocket Envelope。
- **示例 JSON 数组仅用来排列多条独立消息，每个元素实际是一个 WebSocket 文本消息，不是服务端一次发送一个数组。** REST 示例单独标注，不是 WebSocket 消息。
- 顺序仅约束同一 Run 已展示的事件；场景中的公共开始/结束段可通过章节引用复用。下游可选过程帧、心跳、重试过程、其他 topic 的事件可能插入，不能把例子当成每次请求的固定帧数。
- 标准事件的 `payload` 保留该示例输入所产生的字段；下游业务字段及可选诊断字段可以增减。不要对业务 payload 使用“拒绝未知字段”的解码方式。
- `run.started` 是否能收到取决于游标。示例需要开始事件时使用 `afterSeq=firstSeq-1`，不是 `afterSeq=firstSeq`。
- 示例 ID 为易读名称；实际 ID、Runtime Session ID、序号和时间必须使用响应值。

| 场景 | 入口 |
|---|---|
| 连接、presence、订阅、退订及错误 | [S01](#s01) |
| 普通 DomainAgent、Intent、正文/思考、卡片与引用 | [S02](#s02) |
| Relay Delegate、专家、工具与技能广播 | [S03](#s03) |
| 聚合专家、Binding 续接、强制重意图 | [S04](#s04) |
| 普通 Intent 澄清、模糊候选、OTHER、代选 | [S05](#s05) |
| DomainAgent/Relay 问卷、回答、忽略及连续问卷 | [S06](#s06) |
| 拒答自动重路由、确认/拒绝切换 | [S07](#s07) |
| 候选立即调用、旧 Run 停止及路由回放 | [S08](#s08) |
| 不支持附件格式、不支持任何附件、数量超限 | [S09](#s09) |
| 异步等待、通知完成、APPEND/REPLACE/失败 | [S10](#s10) |
| Stop、超时、实例丢失、恢复提示及断连 | [S11](#s11) |
| FULL/no-store、心跳、done、多 topic 与刷新 | [S12](#s12) |

## 一、协议与字段字典

### 1. Envelope

服务端外层 `ChatWebSocketEnvelopeDto` 使用非 null 字段序列化；不适用的字段省略，不要求返回 `null`。业务 payload 内的 null 则由对应生产点决定。

| 字段 | 类型 | 出现条件 / 含义 |
|---|---|---|
| `id` | string | reply 或命令关联 error 回显客户端命令 ID；未传 ID、主动恢复错误、业务 message 通常省略。建议每个命令显式传 ID |
| `type` | string | `reply` 控制受理、`message` 流片段、`error` 连接/订阅层错误；不是 ChatEvent.type |
| `topicId` | string | message 及 RECOVER_REQUIRED；当前 Run topic 为 `chat-run-{runId}`，优先使用启动响应值 |
| `offset` | string | stream-item 等于 ChatEvent.sequence 的十进制字符串；RECOVER_REQUIRED 为诊断 actualSeq；heartbeat/done 省略 |
| `payload` | object | message 的 ConversationTurnStream；路径为外层 `payload.payload.encodedItem.data` |
| `reply` | object | reply 的受理结果；类型由 `reply.type` 区分 |
| `code` | string | error 错误码；与 `run.failed.payload.code` 不同层次 |
| `message` | string | error 说明，文本不作为业务分流键 |
| `details` | object | 恢复等诊断信息，字段按原因变化；普通命令错误可省略 |

`reply` 字段：

| reply.type | 字段及类型 | 语义 |
|---|---|---|
| connect | `connectionId:string, presence:string` | 本次物理连接及前后台状态 |
| presence | `state:string` | 受理的前后台状态；请求字段也是 `state` |
| subscribe | `topicId:string, recovered:boolean, lastSeq:int64` | `recovered = afterSeq > 0`，`lastSeq = 请求afterSeq`；**不表示补发完成，也不是数据库最新水位** |
| unsubscribe | `topicId:string` | 取消 topic 监听，不停止 Run |

### 2. 流式片段

外层 `payload.type` 固定为 `conversation-turn-stream`；下表为 `payload.payload`。

| 字段 | 类型 | 出现条件 / 含义 |
|---|---|---|
| `type` | string | `stream-item / heartbeat / done` |
| `conversationId` | string | 等于 ChatEvent.sessionId，非下游自定义 conversation ID |
| `turnId` | string | 等于 ChatEvent.runId；续跑通常是新 Run，不一定是新消息 |
| `streamItemId` | string | 仅 stream-item，当前为 `evt_<sequence>`，不是 `seq-<sequence>` |
| `serverTimestampMs` | int64 | 包装此片段时的服务端 Unix 毫秒；回放时重新产生，不是事件入库时间 |
| `encodedItem` | object | 仅 stream-item，含 `encoding/event/data` |
| `lastSeq` | int64 | heartbeat 为该订阅已送入出站通道的最新事件序号，未发送事件时为 afterSeq；done 为闭合事件序号。不代表客户端已消费，不能直接推进本地游标 |
| `terminalEventType` | string | 仅 done，当前为 run.completed、run.failed、run.cancelled、run.waiting_user 之一 |

`encodedItem.encoding=chat-event-json-v1`，`encodedItem.event=data.type`，`data` 是 ChatEvent DTO：

| 字段 | 类型 | 含义 |
|---|---|---|
| `runId/sessionId` | string | ChatService 可信身份，与 turnId/conversationId、topic 对应 |
| `sequence` | int64 | 数据库全局序号；同 topic 不要求连续。live-only 业务事件也分配序号，但不因此成为可恢复事实 |
| `type` | string | 如 message.delta、runtime.card、run.waiting_user |
| `payload` | object | 业务字段；DTO 不包含顶层 createdAt |

下游 `payload.run_id/seq/conv_id/session_id` 可作为业务字段保留，不能替代 ChatService 的 runId/sequence/sessionId。JS 前端应确认长整数解析策略；不要把 `offset` 当作独立于 sequence 的另一个事件游标。

### 3. 业务 payload 与终态

| 事件 | 关键字段 / 前端动作 |
|---|---|
| run.started | `status=STARTED, userMessageId?:string`；本次关联 user ID，与启动 REST 响应一致。旧事件可缺失 |
| runtime.progress | 按 `source/sourceType/stage/status/message/text` 展示路由/进度；`intent-result` **也是 progress，不是 metadata** |
| intent-result 的 payload | `routeAction/routeTrigger/latencyMs/intentCode/intentId/intentName/confidence/skillId/routeType/routeSource/targetProvider/targetId` 按结果出现；失败可含 failureStrategy/suggestedAction |
| runtime.metadata | selectedDomainAgent、selectedDomainExpert、selectedIntentExpert、拒答控制、切换事实等；按 sourceType/metadataType 分流，不全部当正文 |
| 技能选择 | `targetType/targetId/domainAgentId/roleName/routeSource/intentResult`；intentResult.accepted 是选中事实，不等于下游任务完成 |
| 专家来源 | 父专家选择为 `selectedExpert`，子技能/意图结果为 `sourceExpert`，对象字段 expertId/expertName/intentAccessName；入口是逻辑值，不是加部署前缀后的值 |
| message.delta | `delta:string` 追加正文；其他字段可选，不假定总有 source |
| message.snapshot | `content:string` 覆盖当前 Run 的正文草稿，不当成追加 delta；Relay generate-response 可能产生此事件 |
| runtime.thinking | DomainAgent 常见 status=STARTED/STREAMING/COMPLETED、text；Relay 保留 content 等原始字段，按 sourceType 解释 |
| runtime.agent/tool | Relay Agent 调用/工具过程，原始嵌套业务字段不统一改名；不自动计入正文 |
| runtime.card | cardType/cardSources/cardUrl/diyCardScene/recommendedQuestions 或问卷字段；不是所有 card 都需要等待 |
| runtime.reference | DomainAgent 常用 referenceType/references；Relay 保留 sources 等原字段 |
| runtime.event | 未归类的合法事件；不要推断为 ask-user、完成或正文，历史可能为隐藏调试 Part |
| message.completed | `status=MESSAGE_COMPLETED`，可能有 finishReason/skillInvocationStarted；**不等于 Run 终态，也不保证该帧已经提供历史 assistant ID** |
| run.completed | status=COMPLETED；messageReady、assistantMessageId、feedbackTargetMessageId 在可用时提供；可能附 routeType/routeSource/agentCode/runtimeBindingId/runtimeProvider/runtimeSessionId |
| run.failed | code/message，可能带恢复、消息关联或异步字段；不要求先收到 message.completed，也不要依赖 status=FAILED 字段 |
| run.cancelled | status=CANCELLED、reason、messageReady；partial 可用时给 assistantMessageId/feedbackTargetMessageId |
| run.waiting_user | status=WAITING_USER、interactionType/interactionId、messageReady、assistantMessageId、expiresAt，及场景相关的候选/切换/自动动作字段 |
| run.async_running | status=ASYNC_RUNNING、sourceType=agent.async_started、messageReady、assistantMessageId、expiresAt；数据库 Run 仍 RUNNING |
| run.async_result_started | status=ASYNC_RESULT_STARTED、resultMode=APPEND/REPLACE、assistantMessageId；只在有效业务结果非空时产生 |
| run.async_finished | status=COMPLETED/FAILED、asyncTask=true、消息关联；仍等待随后的 run.completed/run.failed |
| run.recovered | 接管扩展诊断，非浏览器断线重连 ACK；默认 Runtime 不支持真实接管，不应把它当必达通知 |

`messageReady=true` 表示有可定位的历史消息，不表示所有业务数据均已留存；no-store 可能只有占位正文和必要控制 Parts。`feedbackTargetMessageId` 是 assistant 关联，不是独立意图反馈接口的 runId。

Interaction、异步和路由的非通用字段还需注意以下类型和出现条件：

| 字段 | 类型 | 出现条件 / 含义 |
|---|---|---|
| `source/sourceType/metadataType` | string | 标识来源及事件子类，不作为 Run 身份；Relay 原始字段通常仍保留 |
| `intentResult` | object | 技能选择摘要；accepted:boolean，source/resourceId/skillId:string，intentId/intentName 可选 string |
| `selectedExpert/sourceExpert` | object | expertId/expertName/intentAccessName 均为 string；父专家不覆盖实际子技能 ID |
| `candidateSwitchReplay` | object | originRunId:string、originSequence:int64；回放来源，不是当前事件恢复游标 |
| `messageReady` | boolean | 是否有可定位消息；不能据此判断 FULL 或业务结果完整性 |
| `assistantMessageId/feedbackTargetMessageId` | string | 在消息可用的终态/等待/异步事件中出现；message.delta 通常不带 |
| `interactionId` | string | ChatService 持久化的交互 ID，CONTINUE_INTERACTION 使用它而非 approval_id |
| `interactionType` | string | INTENT_CLARIFICATION、AGENT_CLARIFICATION、ROUTE_SWITCH_CONFIRMATION；AMBIGUOUS_ROUTE 是 clarificationType，不是此枚举 |
| `approval_id` | string | 下游问卷 ID，问卷和答案卡片关联用，前端不自行转成 Run ID |
| `questions` | object[] | 问卷的问题列表；question:string、options:object[]（label:string）、multi_select:boolean；选项及其他可选字段按下游卡片 |
| `questionnaireAnswers` | object | 答案记录；Runtime 问卷为 label 对象（值为字符串或字符串数组）或 ignore:true；Intent 澄清按公开 REST 约束提交 |
| `approved/scope` | boolean / string | 问卷回答或切换确认结果；问卷正常回答 true、忽略 false，scope 默认 once；不表示 Run 完成 |
| `candidateIntents/actions` | object[] | 模糊候选及可执行动作，字段见 S05；没有有效候选不保证出现 AUTO_SELECT |
| `autoSelectAt/autoActionAt` | ISO-8601 string | 前端自动选择/自动动作截止时间，仅等待策略有效时出现 |
| `autoSelectTimeoutMs/autoActionTimeoutMs` | int64 | 对应超时毫秒，不是从每次收到回放帧重新计时 |
| `autoActionType` | string | 如 IGNORE_QUESTIONNAIRE；确认切换按该场景执行批准，不混用问卷忽略语义 |
| `expiresAt` | ISO-8601 string | Interaction 或异步等待的服务端到期时间，不是 WebSocket 过期时间 |
| `resultMode` | string | APPEND/REPLACE，仅有效回填结果的开始事件中出现 |
| `asyncTask` | boolean | 回调完成/失败等事件中的异步标识 |
| `supportedAttachmentTypes/unsupportedAttachmentTypes` | string[] | 小写点前缀扩展名；不支持上传时 supported 为空，unsupportedTypes 不包括无扩展名空值 |
| `unsupportedAttachments` | object[] | documentId/name/extension 均为 string；无后缀附件的 extension="" |
| `skillInvocationStarted` | boolean | 附件拒绝 message.completed 为 false，不把技能已选中等价成调用已开始 |
| `error` | string | 异步 FAILED 的简短失败说明，可省略；不是任意业务 JSON |

| 边界 | topic / 物理 WS | Run / 前端行为 |
|---|---|---|
| run.completed/failed/cancelled | 发出 done，退订该 topic；不关闭整条 WS | 本轮结束，按终态及 stream-status 更新按钮 |
| run.waiting_user | 发出 done，退订该 topic | 等待 Interaction，不能当普通完成解锁；回答走 CONTINUE_INTERACTION 的新 Run/topic |
| run.async_running | 不发 done，不自动退订 topic | 保持运行中及 Stop 能力；同会话不能新建普通 Query |
| message.completed / run.async_finished | 本身不触发 done | 不提前退出 Run 状态 |
| HTTP/SSE 流结束、WS 断开 | 仅传输结束 | 不推断业务完成或取消；重新读取状态并恢复 |

## 二、场景响应示例

<a id="s01"></a>
### S01 连接、控制与订阅失败

先建立经过身份/Origin 校验的 WebSocket。下面每组按“请求 → reply”展示；建议使用独立命令 ID。connect.presence 可以用字符串，生产也接受含 state 的对象；presence 更新命令必须使用 state。

客户端 → 服务端：


```json
[
  {
    "id": "c1",
    "type": "connect",
    "presence": "foreground"
  },
  {
    "id": "c2",
    "type": "presence",
    "state": "background"
  },
  {
    "id": "c3",
    "type": "subscribe",
    "topicId": "chat-run-run_da",
    "afterSeq": 1000
  },
  {
    "id": "c4",
    "type": "unsubscribe",
    "topicId": "chat-run-run_da"
  }
]
```

服务端 → 客户端，依次对应上述请求；实际先 subscribe 才会有业务 message，退订后停止该 topic：

```json
[
  {
    "id": "c1",
    "type": "reply",
    "reply": {
      "type": "connect",
      "connectionId": "ws_demo",
      "presence": "foreground"
    }
  },
  {
    "id": "c2",
    "type": "reply",
    "reply": {
      "type": "presence",
      "state": "background"
    }
  },
  {
    "id": "c3",
    "type": "reply",
    "reply": {
      "type": "subscribe",
      "topicId": "chat-run-run_da",
      "recovered": true,
      "lastSeq": 1000
    }
  },
  {
    "id": "c4",
    "type": "reply",
    "reply": {
      "type": "unsubscribe",
      "topicId": "chat-run-run_da"
    }
  }
]
```

非法命令、缺少 topic 的请求：

```json
[
  {
    "id": "bad1",
    "type": "chat"
  },
  {
    "id": "bad2",
    "type": "subscribe"
  }
]
```

对应错误，以及独立的订阅失败格式示例（第三条说明文本随实际归属/限额错误变化）：

```json
[
  {
    "id": "bad1",
    "type": "error",
    "code": "BAD_WS_MESSAGE",
    "message": "不支持的 WebSocket command type: chat"
  },
  {
    "id": "bad2",
    "type": "error",
    "code": "BAD_WS_MESSAGE",
    "message": "topicId 不能为空"
  },
  {
    "id": "sub_failed",
    "type": "error",
    "code": "SUBSCRIBE_ERROR",
    "message": "无法订阅请求的 Run topic"
  }
]
```

处理：reply 不是历史补发完成通知；没有专门的 catch-up-completed 帧。非法命令修正后重发；订阅失败核对 Run 归属、topic 和限额。Upgrade 前鉴权或 Origin 拒绝可能直接返回 HTTP 错误，不能保证收到 JSON error；不要将所有错误都当成 Run 失败。连接命令不进入历史或 Resume。

退订停止后续监听，但已经排入发送队列或在网络中的消息仍可能到达；前端需结合当前 topic/Run 选择丢弃过时展示更新。退订 reply 不是“所有在途消息已清空”的确认。

<a id="s02"></a>
### S02 普通 DomainAgent：识别、选择、交替输出及完成

REST：`POST /v1/chat/runs`，普通 NEXT；示例未命中 Binding/用例库，实际调用流式 Intent。启动响应独立于 WebSocket：


```json
{
  "runId": "run_da",
  "sessionId": "session_demo",
  "userMessageId": "msg_user",
  "firstSeq": 1001,
  "createdAt": "2026-09-16T00:00:00Z",
  "streamTopicId": "chat-run-run_da"
}
```

订阅 `afterSeq=1000` 后，服务端 → 客户端。过程帧是可选项；示例下游内容为 `第一段。<think>核对第二项。</think>第二段。`，最后给出推荐问、引用及完成信号：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1001",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1001",
        "serverTimestampMs": 1789516801001,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.started",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1001,
            "type": "run.started",
            "payload": {
              "status": "STARTED",
              "userMessageId": "msg_user"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1002",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1002",
        "serverTimestampMs": 1789516801002,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1002,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-start",
              "stage": "intent_calling",
              "message": "正在识别问题意图",
              "routeTrigger": ""
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1003",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1003",
        "serverTimestampMs": 1789516801003,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1003,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-progress",
              "attempt": 1,
              "maxAttempts": 2,
              "stage": "LLM_PROCESSING",
              "message": "正在分析问题"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1004",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1004",
        "serverTimestampMs": 1789516801004,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.thinking",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1004,
            "type": "runtime.thinking",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-delta",
              "attempt": 1,
              "maxAttempts": 2,
              "stage": "LLM_PROCESSING",
              "index": 0,
              "text": "匹配经营分析"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1005",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1005",
        "serverTimestampMs": 1789516801005,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1005,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-result",
              "stage": "intent_result",
              "message": "已完成意图识别",
              "routeAction": "ROUTE_SINGLE",
              "routeTrigger": "",
              "latencyMs": 150,
              "intentCode": "finance_analysis",
              "intentId": "finance_analysis",
              "intentName": "经营分析",
              "confidence": 0.95,
              "skillId": "skill_a",
              "routeType": "DOMAIN_AGENT",
              "routeSource": "intent-agent",
              "targetProvider": "domain-agent",
              "targetId": "skill_a"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1006",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1006",
        "serverTimestampMs": 1789516801006,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1006,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "selectedDomainAgent",
              "metadataType": "selected_domain_agent",
              "routeType": "DOMAIN_AGENT",
              "targetType": "DOMAIN_AGENT",
              "targetId": "skill_a",
              "domainAgentId": "skill_a",
              "routeSource": "intent-agent",
              "runtimeSessionId": "runtime_demo",
              "intentId": "finance_analysis",
              "intentName": "经营分析",
              "intentResult": {
                "accepted": true,
                "source": "intent-agent",
                "resourceId": "skill_a",
                "skillId": "skill_a",
                "intentId": "finance_analysis",
                "intentName": "经营分析"
              }
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1007",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1007",
        "serverTimestampMs": 1789516801007,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1007,
            "type": "message.delta",
            "payload": {
              "delta": "第一段。",
              "sourceType": "domain-agent-content"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1008",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1008",
        "serverTimestampMs": 1789516801008,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.thinking",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1008,
            "type": "runtime.thinking",
            "payload": {
              "source": "domain-agent",
              "sourceType": "content.think",
              "status": "STARTED"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1009",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1009",
        "serverTimestampMs": 1789516801009,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.thinking",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1009,
            "type": "runtime.thinking",
            "payload": {
              "source": "domain-agent",
              "sourceType": "content.think",
              "status": "STREAMING",
              "text": "核对第二项。"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1010",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1010",
        "serverTimestampMs": 1789516801010,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.thinking",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1010,
            "type": "runtime.thinking",
            "payload": {
              "source": "domain-agent",
              "sourceType": "content.think",
              "status": "COMPLETED"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1011",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1011",
        "serverTimestampMs": 1789516801011,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1011,
            "type": "message.delta",
            "payload": {
              "delta": "第二段。",
              "sourceType": "domain-agent-content"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1012",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1012",
        "serverTimestampMs": 1789516801012,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1012,
            "type": "runtime.card",
            "payload": {
              "source": "domain-agent",
              "sourceType": "recommended_questions",
              "cardType": "recommendedQuestions",
              "cardSources": [
                "recommendedQuestions"
              ],
              "recommendedQuestions": [
                "查看明细"
              ],
              "conv_id": "downstream_demo",
              "run_id": "downstream_run",
              "seq": 7
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1013",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1013",
        "serverTimestampMs": 1789516801013,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.reference",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1013,
            "type": "runtime.reference",
            "payload": {
              "source": "domain-agent",
              "sourceType": "searchList",
              "referenceType": "search_list",
              "references": [
                {
                  "title": "经营说明",
                  "url": "https://example.com/report"
                }
              ]
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1014",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1014",
        "serverTimestampMs": 1789516801014,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1014,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED",
              "sourceType": "domain-agent-end"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "offset": "1015",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "streamItemId": "evt_1015",
        "serverTimestampMs": 1789516801015,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_da",
            "sessionId": "session_demo",
            "sequence": 1015,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_answer",
              "feedbackTargetMessageId": "msg_answer",
              "routeType": "DOMAIN_AGENT",
              "routeSource": "intent-agent",
              "agentCode": "skill_a",
              "runtimeBindingId": "binding_a",
              "runtimeProvider": "domain-agent",
              "runtimeSessionId": "runtime_demo"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_da",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_da",
        "serverTimestampMs": 1789516801016,
        "lastSeq": 1015,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

前端：仅将 delta 拼入正文；thinking、card、reference 分区处理，以 run.completed 确认收口。FULL 下历史正文为 `第一段。<!--DOMAIN_AGENT_CONTENT_SEGMENT-->第二段。`，实时 delta 没有这个新增标识事件。历史 CARD/REFERENCE/THINKING Parts 可恢复；no-store 见 S12。独立 `state=THINKING` 的另一种输出如下，不要求与 content.think 同时出现：

```json
{
  "type": "message",
  "topicId": "chat-run-run_state",
  "offset": "1101",
  "payload": {
    "type": "conversation-turn-stream",
    "payload": {
      "type": "stream-item",
      "conversationId": "session_demo",
      "turnId": "run_state",
      "streamItemId": "evt_1101",
      "serverTimestampMs": 1789516801101,
      "encodedItem": {
        "encoding": "chat-event-json-v1",
        "event": "runtime.thinking",
        "data": {
          "runId": "run_state",
          "sessionId": "session_demo",
          "sequence": 1101,
          "type": "runtime.thinking",
          "payload": {
            "source": "domain-agent",
            "sourceType": "state",
            "state": "THINKING",
            "stateDesc": "正在核对资料",
            "status": "STARTED",
            "text": "正在核对资料"
          }
        }
      }
    }
  }
}
```

<a id="s03"></a>
### S03 Relay Delegate 与专家

REST 仍为创建 Run。Delegate 由现有路由选中；固定专家用 `targetType=DOMAIN_EXPERT,targetId=financial-analysis`，并可传 selectedIntent。不要将下游 config/chat_expert 出站帧当成前端 WS 响应。

Delegate 的一个业务段，开始/路由段同 S02，完成后按 S12 发 done。Relay 可有 session-ready、plan-update 等附加事件；业务字段保留原命名：


```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_relay",
    "offset": "1201",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_relay",
        "streamItemId": "evt_1201",
        "serverTimestampMs": 1789516801201,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.agent",
          "data": {
            "runId": "run_relay",
            "sessionId": "session_demo",
            "sequence": 1201,
            "type": "runtime.agent",
            "payload": {
              "type": "agent-call",
              "agent_name": "经营分析",
              "instance_id": "agent_demo",
              "source": "relay",
              "sourceType": "agent-call"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_relay",
    "offset": "1202",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_relay",
        "streamItemId": "evt_1202",
        "serverTimestampMs": 1789516801202,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.thinking",
          "data": {
            "runId": "run_relay",
            "sessionId": "session_demo",
            "sequence": 1202,
            "type": "runtime.thinking",
            "payload": {
              "type": "thinking-content-update",
              "content": "正在整理资料",
              "instance_id": "agent_demo",
              "source": "relay",
              "sourceType": "thinking-content-update"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_relay",
    "offset": "1203",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_relay",
        "streamItemId": "evt_1203",
        "serverTimestampMs": 1789516801203,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.tool",
          "data": {
            "runId": "run_relay",
            "sessionId": "session_demo",
            "sequence": 1203,
            "type": "runtime.tool",
            "payload": {
              "type": "tool-execution",
              "tool_name": "lookup",
              "status": "completed",
              "result": {
                "count": 2
              },
              "source": "relay",
              "sourceType": "tool-execution"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_relay",
    "offset": "1204",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_relay",
        "streamItemId": "evt_1204",
        "serverTimestampMs": 1789516801204,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_relay",
            "sessionId": "session_demo",
            "sequence": 1204,
            "type": "message.delta",
            "payload": {
              "type": "message.delta",
              "delta": "初步结论。",
              "source": "relay",
              "sourceType": "message.delta"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_relay",
    "offset": "1205",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_relay",
        "streamItemId": "evt_1205",
        "serverTimestampMs": 1789516801205,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.snapshot",
          "data": {
            "runId": "run_relay",
            "sessionId": "session_demo",
            "sequence": 1205,
            "type": "message.snapshot",
            "payload": {
              "type": "generate-response",
              "content": "最终结论。",
              "source": "relay",
              "sourceType": "generate-response"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_relay",
    "offset": "1206",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_relay",
        "streamItemId": "evt_1206",
        "serverTimestampMs": 1789516801206,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.reference",
          "data": {
            "runId": "run_relay",
            "sessionId": "session_demo",
            "sequence": 1206,
            "type": "runtime.reference",
            "payload": {
              "type": "sources",
              "sources": [
                {
                  "title": "数据说明",
                  "url": "https://example.com/data"
                }
              ],
              "source": "relay",
              "sourceType": "sources"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_relay",
    "offset": "1207",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_relay",
        "streamItemId": "evt_1207",
        "serverTimestampMs": 1789516801207,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_relay",
            "sessionId": "session_demo",
            "sequence": 1207,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_relay",
    "offset": "1208",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_relay",
        "streamItemId": "evt_1208",
        "serverTimestampMs": 1789516801208,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_relay",
            "sessionId": "session_demo",
            "sequence": 1208,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_relay",
              "feedbackTargetMessageId": "msg_relay",
              "routeType": "AGENT_RUNTIME",
              "routeSource": "intent-agent",
              "runtimeBindingId": "binding_relay",
              "runtimeProvider": "relay",
              "runtimeSessionId": "relay_demo"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_relay",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_relay",
        "serverTimestampMs": 1789516801209,
        "lastSeq": 1208,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

固定专家在 Relay 业务帧前增加选择事件；后续 Agent、thinking、tool、正文及完成仍为同一套事件，不另设 expert.delta：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_expert",
    "offset": "1250",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_expert",
        "streamItemId": "evt_1250",
        "serverTimestampMs": 1789516801250,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.started",
          "data": {
            "runId": "run_expert",
            "sessionId": "session_demo",
            "sequence": 1250,
            "type": "run.started",
            "payload": {
              "status": "STARTED",
              "userMessageId": "msg_user"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_expert",
    "offset": "1251",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_expert",
        "streamItemId": "evt_1251",
        "serverTimestampMs": 1789516801251,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_expert",
            "sessionId": "session_demo",
            "sequence": 1251,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "selectedDomainExpert",
              "metadataType": "selected_domain_expert",
              "routeType": "AGENT_RUNTIME",
              "targetType": "DOMAIN_EXPERT",
              "targetId": "financial-analysis",
              "roleName": "financial-analysis",
              "routeSource": "front-selected",
              "intentName": "经营分析专家",
              "intentResult": {
                "accepted": true,
                "source": "front-selected",
                "resourceId": "financial-analysis",
                "skillId": "financial-analysis",
                "intentName": "经营分析专家"
              }
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_expert",
    "offset": "1252",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_expert",
        "streamItemId": "evt_1252",
        "serverTimestampMs": 1789516801252,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_expert",
            "sessionId": "session_demo",
            "sequence": 1252,
            "type": "message.delta",
            "payload": {
              "type": "message.delta",
              "delta": "专家回答。",
              "source": "relay",
              "sourceType": "message.delta"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_expert",
    "offset": "1253",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_expert",
        "streamItemId": "evt_1253",
        "serverTimestampMs": 1789516801253,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_expert",
            "sessionId": "session_demo",
            "sequence": 1253,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_expert",
    "offset": "1254",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_expert",
        "streamItemId": "evt_1254",
        "serverTimestampMs": 1789516801254,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_expert",
            "sessionId": "session_demo",
            "sequence": 1254,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_expert",
              "feedbackTargetMessageId": "msg_expert",
              "routeType": "AGENT_RUNTIME",
              "routeSource": "front-selected",
              "agentCode": "financial-analysis",
              "runtimeBindingId": "binding_expert",
              "runtimeProvider": "relay",
              "runtimeSessionId": "relay_expert"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_expert",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_expert",
        "serverTimestampMs": 1789516801255,
        "lastSeq": 1254,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

两种技能广播帧均为可见卡片；以下是同一 Run 中连续收到的两条独立消息。脚本地址为示例，不保证可加载：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_cards",
    "offset": "1301",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_cards",
        "streamItemId": "evt_1301",
        "serverTimestampMs": 1789516801301,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_cards",
            "sessionId": "session_demo",
            "sequence": 1301,
            "type": "runtime.card",
            "payload": {
              "type": "skill-card-broadcast",
              "parent_instance_id": "expert_demo",
              "cardType": "url",
              "cardSources": [
                "cardUrl"
              ],
              "cardUrl": "https://example.com/cards/index.js",
              "intent": "CaseCheck",
              "domainAgentId": "skill_cases",
              "isSkillDiy": true,
              "session_id": "session_demo",
              "version_id": 10,
              "runtimeSessionId": "session_demo",
              "source": "relay",
              "sourceType": "skill-card-broadcast"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_cards",
    "offset": "1302",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_cards",
        "streamItemId": "evt_1302",
        "serverTimestampMs": 1789516801302,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_cards",
            "sessionId": "session_demo",
            "sequence": 1302,
            "type": "runtime.card",
            "payload": {
              "type": "skill-card-broadcast",
              "parent_instance_id": "expert_demo",
              "cardType": "diyCardScene",
              "cardSources": [
                "diyCardScene"
              ],
              "message": "请选择资料",
              "min_selections": 1,
              "diyCardScene": {
                "stepName": "selectTasks",
                "dataList": [
                  {
                    "item_id": "T1",
                    "title": "经营复盘",
                    "selected": false
                  }
                ]
              },
              "isSkillDiy": true,
              "session_id": "session_demo",
              "version_id": 11,
              "runtimeSessionId": "session_demo",
              "source": "relay",
              "sourceType": "skill-card-broadcast"
            }
          }
        }
      }
    }
  }
]
```

这两帧不因包含 message/min_selections 就成为 Ask User。FULL 模式生成可见 CARD Part，可进入新建的分享快照；旧隐藏历史和已生成快照不回填。真正的问卷以 S06 的识别及 run.waiting_user 为准。Relay snapshot 覆盖正文草稿；Relay 不保证具备 DomainAgent 专属的历史正文分段标识。

<a id="s04"></a>
### S04 聚合专家、Binding 与 forceReroute

REST 示例（首次选择或切换专家）：


```json
{
  "sessionId": "session_demo",
  "runMode": "NEXT",
  "message": "分析经营情况",
  "targetType": "INTENT_EXPERT",
  "targetId": "finance-expert",
  "selectedExpert": {
    "expertId": "finance-expert",
    "expertName": "财经专家"
  },
  "intentAccessName": "finance_entry",
  "forceReroute": true
}
```

首次选择父专家且识别到子技能，服务端 → 客户端路由段（之后接 S02 业务和终态）。父专家不是实际 skillId：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_scope",
    "offset": "1401",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_scope",
        "streamItemId": "evt_1401",
        "serverTimestampMs": 1789516801401,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.started",
          "data": {
            "runId": "run_scope",
            "sessionId": "session_demo",
            "sequence": 1401,
            "type": "run.started",
            "payload": {
              "status": "STARTED",
              "userMessageId": "msg_user"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_scope",
    "offset": "1402",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_scope",
        "streamItemId": "evt_1402",
        "serverTimestampMs": 1789516801402,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_scope",
            "sessionId": "session_demo",
            "sequence": 1402,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "selectedIntentExpert",
              "metadataType": "selected_intent_expert",
              "targetType": "INTENT_EXPERT",
              "targetId": "finance-expert",
              "selectedExpert": {
                "expertId": "finance-expert",
                "expertName": "财经专家",
                "intentAccessName": "finance_entry"
              }
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_scope",
    "offset": "1403",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_scope",
        "streamItemId": "evt_1403",
        "serverTimestampMs": 1789516801403,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_scope",
            "sessionId": "session_demo",
            "sequence": 1403,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-start",
              "stage": "intent_calling",
              "message": "正在识别问题意图",
              "routeTrigger": "user_correction"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_scope",
    "offset": "1404",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_scope",
        "streamItemId": "evt_1404",
        "serverTimestampMs": 1789516801404,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_scope",
            "sessionId": "session_demo",
            "sequence": 1404,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-result",
              "stage": "intent_result",
              "message": "已完成意图识别",
              "routeAction": "ROUTE_SINGLE",
              "routeTrigger": "user_correction",
              "latencyMs": 150,
              "intentCode": "finance_analysis",
              "intentId": "finance_analysis",
              "intentName": "经营分析",
              "confidence": 0.95,
              "skillId": "skill_a",
              "routeType": "DOMAIN_AGENT",
              "routeSource": "intent-expert",
              "targetProvider": "domain-agent",
              "targetId": "skill_a",
              "sourceExpert": {
                "expertId": "finance-expert",
                "expertName": "财经专家",
                "intentAccessName": "finance_entry"
              }
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_scope",
    "offset": "1405",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_scope",
        "streamItemId": "evt_1405",
        "serverTimestampMs": 1789516801405,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_scope",
            "sessionId": "session_demo",
            "sequence": 1405,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "selectedDomainAgent",
              "metadataType": "selected_domain_agent",
              "routeType": "DOMAIN_AGENT",
              "targetType": "DOMAIN_AGENT",
              "targetId": "skill_a",
              "domainAgentId": "skill_a",
              "routeSource": "intent-expert",
              "runtimeSessionId": "runtime_demo",
              "intentId": "finance_analysis",
              "intentName": "经营分析",
              "intentResult": {
                "accepted": true,
                "source": "intent-expert",
                "resourceId": "skill_a",
                "skillId": "skill_a",
                "intentId": "finance_analysis",
                "intentName": "经营分析"
              },
              "sourceExpert": {
                "expertId": "finance-expert",
                "expertName": "财经专家",
                "intentAccessName": "finance_entry"
              }
            }
          }
        }
      }
    }
  }
]
```

已有该专家范围内的 ACTIVE Binding、未传 forceReroute 或为 false 时，可以直接续接；以下路由段没有 intent-start/intent-result，之后接同样的 Runtime 业务段：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_bound",
    "offset": "1451",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_bound",
        "streamItemId": "evt_1451",
        "serverTimestampMs": 1789516801451,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.started",
          "data": {
            "runId": "run_bound",
            "sessionId": "session_demo",
            "sequence": 1451,
            "type": "run.started",
            "payload": {
              "status": "STARTED",
              "userMessageId": "msg_user_2"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_bound",
    "offset": "1452",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_bound",
        "streamItemId": "evt_1452",
        "serverTimestampMs": 1789516801452,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_bound",
            "sessionId": "session_demo",
            "sequence": 1452,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "selectedDomainAgent",
              "metadataType": "selected_domain_agent",
              "routeType": "DOMAIN_AGENT",
              "targetType": "DOMAIN_AGENT",
              "targetId": "skill_a",
              "domainAgentId": "skill_a",
              "routeSource": "runtime-binding",
              "runtimeSessionId": "runtime_demo",
              "intentId": "finance_analysis",
              "intentName": "经营分析",
              "intentResult": {
                "accepted": true,
                "source": "runtime-binding",
                "resourceId": "skill_a",
                "skillId": "skill_a",
                "intentId": "finance_analysis",
                "intentName": "经营分析"
              },
              "sourceExpert": {
                "expertId": "finance-expert",
                "expertName": "财经专家",
                "intentAccessName": "finance_entry"
              }
            }
          }
        }
      }
    }
  }
]
```

在已保存专家范围的会话中，仅提交 `sessionId/message/forceReroute=true` 也可重新意图；本轮取消续接资格，出现 intent-start 和结果，可能再次命中同一技能。父专家选择事件只在对应选择动作需要展示时产生，不要求每轮重放。false 不禁止无 Binding 或拒答后的 Intent。显式 DomainAgent/固定 Relay 专家直连通常同样没有 Intent 过程帧；但拒答后可能出现。前端以事件实际来源绘制，不能为了凑齐步骤自行补“意图完成”。FULL 保存对应 METADATA/过程 Parts；no-store 按必要控制事实保留。stream-status 的 selectedExpert 用来恢复父范围，不等同于 Binding 子技能。

<a id="s05"></a>
### S05 Intent 澄清、模糊候选与 OTHER

#### 普通澄清

REST：NEXT 得到 Run-A，Intent 要求补充；下列为 S02 意图结果后的等待段。普通澄清新一轮会创建澄清 user，不与模糊候选的复用规则混淆：


```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_clarify_a",
    "offset": "1501",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_clarify_a",
        "streamItemId": "evt_1501",
        "serverTimestampMs": 1789516801501,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_clarify_a",
            "sessionId": "session_demo",
            "sequence": 1501,
            "type": "runtime.card",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-clarification-request",
              "interactionType": "INTENT_CLARIFICATION",
              "routeAction": "CLARIFY",
              "clarificationType": "UNCLEAR_REFERENCE",
              "clarifyQuestion": "请补充分析期间"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_clarify_a",
    "offset": "1502",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_clarify_a",
        "streamItemId": "evt_1502",
        "serverTimestampMs": 1789516801502,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_clarify_a",
            "sessionId": "session_demo",
            "sequence": 1502,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_clarify_a",
    "offset": "1503",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_clarify_a",
        "streamItemId": "evt_1503",
        "serverTimestampMs": 1789516801503,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.waiting_user",
          "data": {
            "runId": "run_clarify_a",
            "sessionId": "session_demo",
            "sequence": 1503,
            "type": "run.waiting_user",
            "payload": {
              "status": "WAITING_USER",
              "interactionType": "INTENT_CLARIFICATION",
              "interactionId": "interaction_clarify",
              "messageReady": true,
              "assistantMessageId": "msg_clarify_a",
              "feedbackTargetMessageId": "msg_clarify_a",
              "expiresAt": "2026-09-17T00:00:00Z",
              "clarificationType": "UNCLEAR_REFERENCE"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_clarify_a",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_clarify_a",
        "serverTimestampMs": 1789516801504,
        "lastSeq": 1503,
        "terminalEventType": "run.waiting_user"
      }
    }
  }
]
```

REST `POST /v1/chat/runs`：

```json
{
  "sessionId": "session_demo",
  "runMode": "CONTINUE_INTERACTION",
  "interactionId": "interaction_clarify",
  "questionnaireAnswers": {
    "请补充分析期间": "本月"
  },
  "intentAccessName": "finance_entry"
}
```

拿到 Run-B 的启动响应，订阅它的 topic，服务端 → 客户端；后续为新的 Intent 结果、选中事件及业务段，也可能再次澄清：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_clarify_b",
    "offset": "1510",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_clarify_b",
        "streamItemId": "evt_1510",
        "serverTimestampMs": 1789516801510,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.started",
          "data": {
            "runId": "run_clarify_b",
            "sessionId": "session_demo",
            "sequence": 1510,
            "type": "run.started",
            "payload": {
              "status": "STARTED",
              "userMessageId": "msg_clarify_user"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_clarify_b",
    "offset": "1511",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_clarify_b",
        "streamItemId": "evt_1511",
        "serverTimestampMs": 1789516801511,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_clarify_b",
            "sessionId": "session_demo",
            "sequence": 1511,
            "type": "runtime.card",
            "payload": {
              "source": "chatservice",
              "sourceType": "intent-clarification-response",
              "interactionId": "interaction_clarify",
              "interactionType": "INTENT_CLARIFICATION",
              "approved": true,
              "scope": "once",
              "questionnaireAnswers": {
                "请补充分析期间": "本月"
              },
              "answerText": "本月",
              "metadata": {}
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_clarify_b",
    "offset": "1512",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_clarify_b",
        "streamItemId": "evt_1512",
        "serverTimestampMs": 1789516801512,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_clarify_b",
            "sessionId": "session_demo",
            "sequence": 1512,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-start",
              "stage": "intent_calling",
              "message": "正在识别问题意图",
              "routeTrigger": ""
            }
          }
        }
      }
    }
  }
]
```

前端以 B 返回的 userMessageId 建立新 turn；A 的问答和澄清记录保留。等待提交发生竞争或已过期时，处理 REST 错误并重新读 stream-status，不能继续提交旧卡片。

#### AMBIGUOUS_ROUTE 等待

REST 仍为 NEXT；识别为多个候选。Run-A 的等待段如下。自动选择截止时间只在满足条件并启用对应等待策略时出现，后端不会主动代替前端发起选择。


```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_amb_a",
    "offset": "1601",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_amb_a",
        "streamItemId": "evt_1601",
        "serverTimestampMs": 1789516801601,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_amb_a",
            "sessionId": "session_demo",
            "sequence": 1601,
            "type": "runtime.card",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-clarification-request",
              "interactionType": "INTENT_CLARIFICATION",
              "routeAction": "CLARIFY",
              "clarifyQuestion": "请选择处理技能",
              "clarificationType": "AMBIGUOUS_ROUTE",
              "candidateIntents": [
                {
                  "intentId": "intent_a",
                  "intentName": "经营分析",
                  "confidence": 0.9,
                  "accessName": "skill_a",
                  "skillId": "skill_a"
                },
                {
                  "intentId": "intent_b",
                  "intentName": "文档分析",
                  "confidence": 0.8,
                  "accessName": "skill_b",
                  "skillId": "skill_b"
                }
              ],
              "actions": [
                {
                  "type": "AUTO_SELECT",
                  "displayName": "代为选择"
                },
                {
                  "type": "OTHER",
                  "displayName": "其他"
                }
              ],
              "autoSelectAt": "2026-09-16T00:00:30Z",
              "autoSelectTimeoutMs": 30000
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_amb_a",
    "offset": "1602",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_amb_a",
        "streamItemId": "evt_1602",
        "serverTimestampMs": 1789516801602,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_amb_a",
            "sessionId": "session_demo",
            "sequence": 1602,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_amb_a",
    "offset": "1603",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_amb_a",
        "streamItemId": "evt_1603",
        "serverTimestampMs": 1789516801603,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.waiting_user",
          "data": {
            "runId": "run_amb_a",
            "sessionId": "session_demo",
            "sequence": 1603,
            "type": "run.waiting_user",
            "payload": {
              "status": "WAITING_USER",
              "interactionType": "INTENT_CLARIFICATION",
              "interactionId": "interaction_amb",
              "messageReady": true,
              "assistantMessageId": "msg_amb_answer",
              "feedbackTargetMessageId": "msg_amb_answer",
              "expiresAt": "2026-09-17T00:00:00Z",
              "clarificationType": "AMBIGUOUS_ROUTE",
              "candidateIntents": [
                {
                  "intentId": "intent_a",
                  "intentName": "经营分析",
                  "confidence": 0.9,
                  "accessName": "skill_a",
                  "skillId": "skill_a"
                },
                {
                  "intentId": "intent_b",
                  "intentName": "文档分析",
                  "confidence": 0.8,
                  "accessName": "skill_b",
                  "skillId": "skill_b"
                }
              ],
              "actions": [
                {
                  "type": "AUTO_SELECT",
                  "displayName": "代为选择"
                },
                {
                  "type": "OTHER",
                  "displayName": "其他"
                }
              ],
              "autoSelectAt": "2026-09-16T00:00:30Z",
              "autoSelectTimeoutMs": 30000
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_amb_a",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_amb_a",
        "serverTimestampMs": 1789516801604,
        "lastSeq": 1603,
        "terminalEventType": "run.waiting_user"
      }
    }
  }
]
```

三种互斥 REST 请求，各自从同一 WAITING 场景开始，不是连续提交给同一 Interaction：

```json
[
  {
    "sessionId": "session_demo",
    "runMode": "CONTINUE_INTERACTION",
    "interactionId": "interaction_amb",
    "targetType": "DOMAIN_AGENT",
    "targetId": "skill_b",
    "intentAccessName": "finance_entry"
  },
  {
    "sessionId": "session_demo",
    "runMode": "CONTINUE_INTERACTION",
    "interactionId": "interaction_amb",
    "interactionAction": "AUTO_SELECT",
    "intentAccessName": "finance_entry"
  },
  {
    "sessionId": "session_demo",
    "runMode": "CONTINUE_INTERACTION",
    "interactionId": "interaction_amb",
    "questionnaireAnswers": {
      "请选择处理技能": "请分析合同文档"
    },
    "intentAccessName": "finance_entry"
  }
]
```

手动候选的 Run-B 开始段，之后直接 Runtime，不伪造 intent-result：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_amb_selected",
    "offset": "1610",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_amb_selected",
        "streamItemId": "evt_1610",
        "serverTimestampMs": 1789516801610,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.started",
          "data": {
            "runId": "run_amb_selected",
            "sessionId": "session_demo",
            "sequence": 1610,
            "type": "run.started",
            "payload": {
              "status": "STARTED",
              "userMessageId": "msg_user"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_amb_selected",
    "offset": "1611",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_amb_selected",
        "streamItemId": "evt_1611",
        "serverTimestampMs": 1789516801611,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_amb_selected",
            "sessionId": "session_demo",
            "sequence": 1611,
            "type": "runtime.card",
            "payload": {
              "source": "chatservice",
              "sourceType": "intent-clarification-response",
              "interactionId": "interaction_amb",
              "interactionType": "INTENT_CLARIFICATION",
              "clarificationType": "AMBIGUOUS_ROUTE",
              "assistantMessageId": "msg_amb_answer",
              "sourceRunId": "run_amb_a",
              "questionnaireAnswers": {},
              "metadata": {},
              "selectionSource": "USER",
              "interactionAction": "SELECT_CANDIDATE",
              "selectedSkillId": "skill_b",
              "selectedIntentId": "intent_b",
              "selectedIntentName": "文档分析",
              "approved": true,
              "scope": "once",
              "answerText": "文档分析"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_amb_selected",
    "offset": "1612",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_amb_selected",
        "streamItemId": "evt_1612",
        "serverTimestampMs": 1789516801612,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_amb_selected",
            "sessionId": "session_demo",
            "sequence": 1612,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "selectedDomainAgent",
              "metadataType": "selected_domain_agent",
              "routeType": "DOMAIN_AGENT",
              "targetType": "DOMAIN_AGENT",
              "targetId": "skill_b",
              "domainAgentId": "skill_b",
              "routeSource": "user-confirmed",
              "runtimeSessionId": "runtime_demo",
              "intentId": "intent_b",
              "intentName": "文档分析",
              "intentResult": {
                "accepted": true,
                "source": "user-confirmed",
                "resourceId": "skill_b",
                "skillId": "skill_b",
                "intentId": "intent_b",
                "intentName": "文档分析"
              }
            }
          }
        }
      }
    }
  }
]
```

AUTO_SELECT（手动代选和到期代选使用相同协议）选择最高 confidence 有效候选；仅相应响应区别如下，前后仍为 run.started 和技能/业务事件：

```json
{
  "type": "message",
  "topicId": "chat-run-run_amb_auto",
  "offset": "1621",
  "payload": {
    "type": "conversation-turn-stream",
    "payload": {
      "type": "stream-item",
      "conversationId": "session_demo",
      "turnId": "run_amb_auto",
      "streamItemId": "evt_1621",
      "serverTimestampMs": 1789516801621,
      "encodedItem": {
        "encoding": "chat-event-json-v1",
        "event": "runtime.card",
        "data": {
          "runId": "run_amb_auto",
          "sessionId": "session_demo",
          "sequence": 1621,
          "type": "runtime.card",
          "payload": {
            "source": "chatservice",
            "sourceType": "intent-clarification-response",
            "interactionId": "interaction_amb",
            "interactionType": "INTENT_CLARIFICATION",
            "clarificationType": "AMBIGUOUS_ROUTE",
            "assistantMessageId": "msg_amb_answer",
            "sourceRunId": "run_amb_a",
            "questionnaireAnswers": {},
            "metadata": {},
            "selectionSource": "DELEGATED",
            "interactionAction": "AUTO_SELECT",
            "selectedSkillId": "skill_a",
            "selectedIntentId": "intent_a",
            "selectedIntentName": "经营分析",
            "approved": true,
            "scope": "once",
            "answerText": "代为选择：经营分析"
          }
        }
      }
    }
  }
}
```

OTHER 的 Run-B 开始段后重新 Intent，不直接调用技能：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_amb_other",
    "offset": "1630",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_amb_other",
        "streamItemId": "evt_1630",
        "serverTimestampMs": 1789516801630,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.started",
          "data": {
            "runId": "run_amb_other",
            "sessionId": "session_demo",
            "sequence": 1630,
            "type": "run.started",
            "payload": {
              "status": "STARTED",
              "userMessageId": "msg_user"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_amb_other",
    "offset": "1631",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_amb_other",
        "streamItemId": "evt_1631",
        "serverTimestampMs": 1789516801631,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_amb_other",
            "sessionId": "session_demo",
            "sequence": 1631,
            "type": "runtime.card",
            "payload": {
              "source": "chatservice",
              "sourceType": "intent-clarification-response",
              "interactionId": "interaction_amb",
              "interactionType": "INTENT_CLARIFICATION",
              "clarificationType": "AMBIGUOUS_ROUTE",
              "assistantMessageId": "msg_amb_answer",
              "sourceRunId": "run_amb_a",
              "questionnaireAnswers": {
                "请选择处理技能": "请分析合同文档"
              },
              "approved": true,
              "scope": "once",
              "metadata": {},
              "answerText": "请分析合同文档",
              "selectionSource": "USER",
              "interactionAction": "OTHER"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_amb_other",
    "offset": "1632",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_amb_other",
        "streamItemId": "evt_1632",
        "serverTimestampMs": 1789516801632,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_amb_other",
            "sessionId": "session_demo",
            "sequence": 1632,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-start",
              "stage": "intent_calling",
              "message": "正在识别问题意图",
              "routeTrigger": ""
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_amb_other",
    "offset": "1633",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_amb_other",
        "streamItemId": "evt_1633",
        "serverTimestampMs": 1789516801633,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_amb_other",
            "sessionId": "session_demo",
            "sequence": 1633,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-result",
              "stage": "intent_result",
              "message": "已完成意图识别",
              "routeAction": "ROUTE_SINGLE",
              "routeTrigger": "",
              "latencyMs": 150,
              "intentCode": "finance_analysis",
              "intentId": "finance_analysis",
              "intentName": "经营分析",
              "confidence": 0.95,
              "skillId": "skill_a",
              "routeType": "DOMAIN_AGENT",
              "routeSource": "intent-agent",
              "targetProvider": "domain-agent",
              "targetId": "skill_a"
            }
          }
        }
      }
    }
  }
]
```

三种模糊续跑都复用原 user/assistant；前端按 response 的 assistantMessageId 在原卡片后追加，不新建第二个问题区域。B 如再次模糊，使用新的 Interaction 重复此流程。之后候选立即调用使用 B 的 runId 与原 userMessageId，不使用 A 的 runId。历史保存 A/B Parts 各自 runId，Resume 也按对应 Run 恢复；不是所有续跑都创建新 assistant。

<a id="s06"></a>
### S06 DomainAgent 与 Relay 问卷

#### 发问与等待

REST 创建 Run 后，下游发问；DomainAgent 精确识别 approval-request + operation_type=questionnaire，并校验 approval_id、非空 questions 和唯一问题文本。问卷后本次 HTTP 输出截断（包括同 chunk 后续帧），此前正文保留。示例为两个 provider 的独立场景：


```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_question_da",
    "offset": "1701",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_question_da",
        "streamItemId": "evt_1701",
        "serverTimestampMs": 1789516801701,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_question_da",
            "sessionId": "session_demo",
            "sequence": 1701,
            "type": "runtime.card",
            "payload": {
              "type": "approval-request",
              "approval_id": "approval_demo",
              "operation_type": "questionnaire",
              "mode": "questionnaire",
              "message": "请补充分析范围",
              "questions": [
                {
                  "question": "请选择分析期间",
                  "options": [
                    {
                      "label": "本月"
                    },
                    {
                      "label": "上月"
                    }
                  ],
                  "multi_select": false
                }
              ],
              "source": "domain-agent",
              "sourceType": "approval-request"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_question_da",
    "offset": "1702",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_question_da",
        "streamItemId": "evt_1702",
        "serverTimestampMs": 1789516801702,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.waiting_user",
          "data": {
            "runId": "run_question_da",
            "sessionId": "session_demo",
            "sequence": 1702,
            "type": "run.waiting_user",
            "payload": {
              "status": "WAITING_USER",
              "interactionType": "AGENT_CLARIFICATION",
              "interactionId": "interaction_da",
              "messageReady": true,
              "assistantMessageId": "msg_question_da",
              "feedbackTargetMessageId": "msg_question_da",
              "expiresAt": "2026-09-17T00:00:00Z"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_question_da",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_question_da",
        "serverTimestampMs": 1789516801703,
        "lastSeq": 1702,
        "terminalEventType": "run.waiting_user"
      }
    }
  }
]
```

Relay 同样的 questions 输入对应：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_question_relay",
    "offset": "1711",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_question_relay",
        "streamItemId": "evt_1711",
        "serverTimestampMs": 1789516801711,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_question_relay",
            "sessionId": "session_demo",
            "sequence": 1711,
            "type": "runtime.card",
            "payload": {
              "type": "approval-request",
              "approval_id": "approval_demo",
              "operation_type": "questionnaire",
              "mode": "questionnaire",
              "message": "请补充分析范围",
              "questions": [
                {
                  "question": "请选择分析期间",
                  "options": [
                    {
                      "label": "本月"
                    },
                    {
                      "label": "上月"
                    }
                  ],
                  "multi_select": false
                }
              ],
              "session_id": "relay_question",
              "runtimeSessionId": "relay_question",
              "source": "relay",
              "sourceType": "approval-request"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_question_relay",
    "offset": "1712",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_question_relay",
        "streamItemId": "evt_1712",
        "serverTimestampMs": 1789516801712,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.waiting_user",
          "data": {
            "runId": "run_question_relay",
            "sessionId": "session_demo",
            "sequence": 1712,
            "type": "run.waiting_user",
            "payload": {
              "status": "WAITING_USER",
              "interactionType": "AGENT_CLARIFICATION",
              "interactionId": "interaction_relay",
              "messageReady": true,
              "assistantMessageId": "msg_question_relay",
              "feedbackTargetMessageId": "msg_question_relay",
              "expiresAt": "2026-09-17T00:00:00Z"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_question_relay",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_question_relay",
        "serverTimestampMs": 1789516801713,
        "lastSeq": 1712,
        "terminalEventType": "run.waiting_user"
      }
    }
  }
]
```

不要依赖卡片已经含有 interactionId；等待 run.waiting_user 才拿到服务端 Interaction。绑定保持 ACTIVE，但本轮下游连接已释放。问卷不一定有 cardType，也不需要前端根据 cardUrl 猜等待。Relay 的其他表单业务字段保持原结构，答案仍必须满足后端的现有问卷校验，不把任意带表单内容的卡片都当成可提交问卷。

#### 正常回答

REST `POST /v1/chat/runs`，DomainAgent/Relay 同一公开结构（示例为 DomainAgent）：


```json
{
  "sessionId": "session_demo",
  "runMode": "CONTINUE_INTERACTION",
  "interactionId": "interaction_da",
  "approved": true,
  "scope": "once",
  "questionnaireAnswers": {
    "label": {
      "请选择分析期间": "本月"
    }
  },
  "metadata": {}
}
```

返回新 Run `run_answer`，userMessageId 仍为原 user；订阅新 topic，服务端 → 客户端：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_answer",
    "offset": "1721",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_answer",
        "streamItemId": "evt_1721",
        "serverTimestampMs": 1789516801721,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.started",
          "data": {
            "runId": "run_answer",
            "sessionId": "session_demo",
            "sequence": 1721,
            "type": "run.started",
            "payload": {
              "status": "STARTED",
              "userMessageId": "msg_user"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_answer",
    "offset": "1722",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_answer",
        "streamItemId": "evt_1722",
        "serverTimestampMs": 1789516801722,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_answer",
            "sessionId": "session_demo",
            "sequence": 1722,
            "type": "runtime.card",
            "payload": {
              "source": "chatservice",
              "sourceType": "clarification-response",
              "interactionId": "interaction_da",
              "interactionType": "AGENT_CLARIFICATION",
              "approval_id": "approval_demo",
              "approved": true,
              "scope": "once",
              "questionnaireAnswers": {
                "label": {
                  "请选择分析期间": "本月"
                }
              },
              "metadata": {}
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_answer",
    "offset": "1723",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_answer",
        "streamItemId": "evt_1723",
        "serverTimestampMs": 1789516801723,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_answer",
            "sessionId": "session_demo",
            "sequence": 1723,
            "type": "message.delta",
            "payload": {
              "delta": "本月分析结果。",
              "sourceType": "domain-agent-content"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_answer",
    "offset": "1724",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_answer",
        "streamItemId": "evt_1724",
        "serverTimestampMs": 1789516801724,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_answer",
            "sessionId": "session_demo",
            "sequence": 1724,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_answer",
    "offset": "1725",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_answer",
        "streamItemId": "evt_1725",
        "serverTimestampMs": 1789516801725,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_answer",
            "sessionId": "session_demo",
            "sequence": 1725,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_question_da",
              "feedbackTargetMessageId": "msg_question_da"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_answer",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_answer",
        "serverTimestampMs": 1789516801726,
        "lastSeq": 1725,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

前端把 B 的正文/Parts 追加到原 assistant，不清空问卷前正文。Relay 回答后使用 Relay 业务 payload（见 S03），公开响应事件类型不变。多选答案值为数组；前端不发送 approval_id、Runtime Session、原 query、messages 或新附件来伪造续跑关联。首次续跑跳过 Intent，后续拒答仍可进入 S07。

#### 手动与自动忽略

手动忽略的 REST：


```json
{
  "sessionId": "session_demo",
  "runMode": "CONTINUE_INTERACTION",
  "interactionId": "interaction_da",
  "approved": false,
  "questionnaireAnswers": {
    "ignore": true
  },
  "metadata": {}
}
```

对应 Run 的答案记录（前面仍有 run.started），不是 Stop，后面继续接下游结果或新问卷：

```json
{
  "type": "message",
  "topicId": "chat-run-run_ignore",
  "offset": "1731",
  "payload": {
    "type": "conversation-turn-stream",
    "payload": {
      "type": "stream-item",
      "conversationId": "session_demo",
      "turnId": "run_ignore",
      "streamItemId": "evt_1731",
      "serverTimestampMs": 1789516801731,
      "encodedItem": {
        "encoding": "chat-event-json-v1",
        "event": "runtime.card",
        "data": {
          "runId": "run_ignore",
          "sessionId": "session_demo",
          "sequence": 1731,
          "type": "runtime.card",
          "payload": {
            "source": "chatservice",
            "sourceType": "clarification-response",
            "interactionId": "interaction_da",
            "interactionType": "AGENT_CLARIFICATION",
            "approval_id": "approval_demo",
            "approved": false,
            "scope": "once",
            "questionnaireAnswers": {
              "ignore": true
            },
            "metadata": {}
          }
        }
      }
    }
  }
}
```

自动忽略仅在等待策略启用时，等待帧额外包含以下事实；示例将 DomainAgent 配为 30 秒，**不是默认值**：

```json
{
  "type": "message",
  "topicId": "chat-run-run_auto_wait",
  "offset": "1741",
  "payload": {
    "type": "conversation-turn-stream",
    "payload": {
      "type": "stream-item",
      "conversationId": "session_demo",
      "turnId": "run_auto_wait",
      "streamItemId": "evt_1741",
      "serverTimestampMs": 1789516801741,
      "encodedItem": {
        "encoding": "chat-event-json-v1",
        "event": "run.waiting_user",
        "data": {
          "runId": "run_auto_wait",
          "sessionId": "session_demo",
          "sequence": 1741,
          "type": "run.waiting_user",
          "payload": {
            "status": "WAITING_USER",
            "interactionType": "AGENT_CLARIFICATION",
            "interactionId": "interaction_auto",
            "messageReady": true,
            "assistantMessageId": "msg_auto",
            "feedbackTargetMessageId": "msg_auto",
            "expiresAt": "2026-09-17T00:00:00Z",
            "autoActionAt": "2026-09-16T00:00:30Z",
            "autoActionTimeoutMs": 30000,
            "autoActionType": "IGNORE_QUESTIONNAIRE"
          }
        }
      }
    }
  }
}
```

前端到期重新确认 Interaction 仍 WAITING 且未过期，再发送与手动忽略完全相同的 REST。没有独立的“自动忽略已执行”WS 类型，以 clarification-response 为提交记录。DomainAgent 默认 `questionnaire-wait-timeout=0s` 关闭；Relay 使用自己的配置。expiresAt（通常 24 小时）是 Interaction 失效时间，不能作为自动忽略时间。页面关闭时服务端不主动提交。下游必须实现 ignore 语义，不能以界面倒计时替代下游支持。

#### 连续问卷与失败

回答 Run-B 若继续提问，示例只展示再次发问段：


```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_answer_again",
    "offset": "1751",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_answer_again",
        "streamItemId": "evt_1751",
        "serverTimestampMs": 1789516801751,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_answer_again",
            "sessionId": "session_demo",
            "sequence": 1751,
            "type": "runtime.card",
            "payload": {
              "type": "approval-request",
              "approval_id": "approval_next",
              "operation_type": "questionnaire",
              "mode": "questionnaire",
              "message": "请补充币种",
              "questions": [
                {
                  "question": "币种",
                  "options": [
                    {
                      "label": "CNY"
                    },
                    {
                      "label": "USD"
                    }
                  ],
                  "multi_select": false
                }
              ],
              "source": "domain-agent",
              "sourceType": "approval-request"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_answer_again",
    "offset": "1752",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_answer_again",
        "streamItemId": "evt_1752",
        "serverTimestampMs": 1789516801752,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.waiting_user",
          "data": {
            "runId": "run_answer_again",
            "sessionId": "session_demo",
            "sequence": 1752,
            "type": "run.waiting_user",
            "payload": {
              "status": "WAITING_USER",
              "interactionType": "AGENT_CLARIFICATION",
              "interactionId": "interaction_next",
              "messageReady": true,
              "assistantMessageId": "msg_question_da",
              "feedbackTargetMessageId": "msg_question_da",
              "expiresAt": "2026-09-17T00:00:00Z"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_answer_again",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_answer_again",
        "serverTimestampMs": 1789516801753,
        "lastSeq": 1752,
        "terminalEventType": "run.waiting_user"
      }
    }
  }
]
```

使用新 interaction_next 和 Run-C；旧 interaction_da 不可重复提交。发送前失败可能条件恢复原 WAITING，发送开始后超时/断连可能已送达，不自动重发答案，按最新 stream-status 决定重试还是新问题。FULL 保留正文、问卷与答案 Parts；no-store 保留问卷/答案/等待等必要控制事实，私有续跑上下文从不放入 WS、历史 DTO 或分享。恢复问卷不依赖一直保持旧 Run-A topic。

<a id="s07"></a>
### S07 拒答、自动重路由与人工确认

同一个 Run 内可信拒答控制事件不是 run.failed。以下示例使用受支持的拒答编码；普通文本“无法回答”不等价。控制事件经持久化屏障后才进入后续路由。自动重路由业务段：


```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_reroute",
    "offset": "1801",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_reroute",
        "streamItemId": "evt_1801",
        "serverTimestampMs": 1789516801801,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_reroute",
            "sessionId": "session_demo",
            "sequence": 1801,
            "type": "runtime.metadata",
            "payload": {
              "source": "domain-agent",
              "sourceType": "agent.refusal",
              "metadataType": "domain_agent_control",
              "supervisorAction": "REROUTE",
              "type": "agent.refusal",
              "code": "FN-EX-CAHT-BIZ-DAG-001",
              "reason": "当前技能不覆盖该问题",
              "domainAgentId": "skill_a",
              "targetId": "skill_a",
              "provider": "domain-agent"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_reroute",
    "offset": "1802",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_reroute",
        "streamItemId": "evt_1802",
        "serverTimestampMs": 1789516801802,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_reroute",
            "sessionId": "session_demo",
            "sequence": 1802,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-start",
              "stage": "intent_calling",
              "message": "正在识别问题意图",
              "routeTrigger": "domain_reject"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_reroute",
    "offset": "1803",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_reroute",
        "streamItemId": "evt_1803",
        "serverTimestampMs": 1789516801803,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_reroute",
            "sessionId": "session_demo",
            "sequence": 1803,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-result",
              "stage": "intent_result",
              "message": "已完成意图识别",
              "routeAction": "ROUTE_SINGLE",
              "routeTrigger": "domain_reject",
              "latencyMs": 150,
              "intentCode": "finance_analysis",
              "intentId": "finance_analysis",
              "intentName": "经营分析",
              "confidence": 0.95,
              "skillId": "skill_b",
              "routeType": "DOMAIN_AGENT",
              "routeSource": "intent-agent",
              "targetProvider": "domain-agent",
              "targetId": "skill_b"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_reroute",
    "offset": "1804",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_reroute",
        "streamItemId": "evt_1804",
        "serverTimestampMs": 1789516801804,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_reroute",
            "sessionId": "session_demo",
            "sequence": 1804,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "domain-agent-reroute",
              "metadataType": "domain_agent_reroute",
              "action": "AUTO_SWITCH",
              "currentDomainAgentId": "skill_a",
              "refusalCode": "FN-EX-CAHT-BIZ-DAG-001",
              "refusalReason": "当前技能不覆盖该问题",
              "candidateDomainAgentId": "skill_b",
              "candidateRouteSource": "intent-agent"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_reroute",
    "offset": "1805",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_reroute",
        "streamItemId": "evt_1805",
        "serverTimestampMs": 1789516801805,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_reroute",
            "sessionId": "session_demo",
            "sequence": 1805,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "selectedDomainAgent",
              "metadataType": "selected_domain_agent",
              "routeType": "DOMAIN_AGENT",
              "targetType": "DOMAIN_AGENT",
              "targetId": "skill_b",
              "domainAgentId": "skill_b",
              "routeSource": "intent-agent",
              "runtimeSessionId": "runtime_demo",
              "intentId": "intent_b",
              "intentName": "文档分析",
              "intentResult": {
                "accepted": true,
                "source": "intent-agent",
                "resourceId": "skill_b",
                "skillId": "skill_b",
                "intentId": "intent_b",
                "intentName": "文档分析"
              }
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_reroute",
    "offset": "1806",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_reroute",
        "streamItemId": "evt_1806",
        "serverTimestampMs": 1789516801806,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_reroute",
            "sessionId": "session_demo",
            "sequence": 1806,
            "type": "message.delta",
            "payload": {
              "delta": "新技能的回答。",
              "sourceType": "domain-agent-content"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_reroute",
    "offset": "1807",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_reroute",
        "streamItemId": "evt_1807",
        "serverTimestampMs": 1789516801807,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_reroute",
            "sessionId": "session_demo",
            "sequence": 1807,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_reroute",
    "offset": "1808",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_reroute",
        "streamItemId": "evt_1808",
        "serverTimestampMs": 1789516801808,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_reroute",
            "sessionId": "session_demo",
            "sequence": 1808,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_answer",
              "feedbackTargetMessageId": "msg_answer"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_reroute",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_reroute",
        "serverTimestampMs": 1789516801809,
        "lastSeq": 1808,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

可能改走 Relay（action=ROUTE_TO_RELAY/RELAY_FALLBACK）、再次澄清或结束，不保证总有 AUTO_SWITCH。前端按拒答/切换语义刷新当前草稿，不能把旧技能已失效正文当成新技能回答继续拼接。逻辑 Intent 入口与专家范围沿本 Run 上下文处理，不由 WS payload 反推配置。

需要用户确认切换时，在拒答及重意图结果之后收到下列等待段，而不是立刻出现 selectedDomainAgent(B)：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_confirm_a",
    "offset": "1811",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_confirm_a",
        "streamItemId": "evt_1811",
        "serverTimestampMs": 1789516801811,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_confirm_a",
            "sessionId": "session_demo",
            "sequence": 1811,
            "type": "runtime.card",
            "payload": {
              "source": "chatservice",
              "sourceType": "route-switch-confirmation-request",
              "interactionType": "ROUTE_SWITCH_CONFIRMATION",
              "currentProvider": "domain-agent",
              "currentTargetId": "skill_a",
              "currentRouteSource": "front-selected",
              "candidateProvider": "domain-agent",
              "candidateTargetId": "skill_b",
              "candidateIntentCode": "intent_b",
              "candidateIntentName": "文档分析",
              "message": "当前领域 Agent 无法处理该问题，是否切换到新的处理能力继续回答？",
              "refusalCode": "FN-EX-CAHT-BIZ-DAG-001",
              "refusalReason": "当前技能不覆盖该问题",
              "originalQuery": "分析资料"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_confirm_a",
    "offset": "1812",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_confirm_a",
        "streamItemId": "evt_1812",
        "serverTimestampMs": 1789516801812,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.waiting_user",
          "data": {
            "runId": "run_confirm_a",
            "sessionId": "session_demo",
            "sequence": 1812,
            "type": "run.waiting_user",
            "payload": {
              "status": "WAITING_USER",
              "interactionType": "ROUTE_SWITCH_CONFIRMATION",
              "interactionId": "interaction_switch",
              "messageReady": true,
              "assistantMessageId": "msg_confirm",
              "feedbackTargetMessageId": "msg_confirm",
              "expiresAt": "2026-09-17T00:00:00Z",
              "currentProvider": "domain-agent",
              "currentTargetId": "skill_a",
              "candidateProvider": "domain-agent",
              "candidateTargetId": "skill_b",
              "candidateIntentName": "文档分析"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_confirm_a",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_confirm_a",
        "serverTimestampMs": 1789516801813,
        "lastSeq": 1812,
        "terminalEventType": "run.waiting_user"
      }
    }
  }
]
```

REST `POST /v1/chat/runs`：

```json
{
  "sessionId": "session_demo",
  "runMode": "CONTINUE_INTERACTION",
  "interactionId": "interaction_switch",
  "approved": true,
  "intentAccessName": "finance_entry",
  "metadata": {}
}
```

批准且附件支持时新 Run 的前缀如下，随后接选中技能及 Runtime；confirmation-response 只表示确认受理，applied 才表示切换事实：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_confirm_b",
    "offset": "1821",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_confirm_b",
        "streamItemId": "evt_1821",
        "serverTimestampMs": 1789516801821,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.started",
          "data": {
            "runId": "run_confirm_b",
            "sessionId": "session_demo",
            "sequence": 1821,
            "type": "run.started",
            "payload": {
              "status": "STARTED",
              "userMessageId": "msg_user"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_confirm_b",
    "offset": "1822",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_confirm_b",
        "streamItemId": "evt_1822",
        "serverTimestampMs": 1789516801822,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_confirm_b",
            "sessionId": "session_demo",
            "sequence": 1822,
            "type": "runtime.card",
            "payload": {
              "source": "chatservice",
              "sourceType": "route-switch-confirmation-response",
              "interactionId": "interaction_switch",
              "interactionType": "ROUTE_SWITCH_CONFIRMATION",
              "approved": true,
              "currentProvider": "domain-agent",
              "currentTargetId": "skill_a",
              "currentRouteSource": "front-selected",
              "candidateProvider": "domain-agent",
              "candidateTargetId": "skill_b",
              "candidateIntentName": "文档分析",
              "metadata": {}
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_confirm_b",
    "offset": "1823",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_confirm_b",
        "streamItemId": "evt_1823",
        "serverTimestampMs": 1789516801823,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_confirm_b",
            "sessionId": "session_demo",
            "sequence": 1823,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "route-switch-applied",
              "metadataType": "route_switch_applied",
              "interactionId": "interaction_switch",
              "targetProvider": "domain-agent",
              "targetId": "skill_b",
              "routeSource": "user-confirmed"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_confirm_b",
    "offset": "1824",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_confirm_b",
        "streamItemId": "evt_1824",
        "serverTimestampMs": 1789516801824,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_confirm_b",
            "sessionId": "session_demo",
            "sequence": 1824,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "selectedDomainAgent",
              "metadataType": "selected_domain_agent",
              "routeType": "DOMAIN_AGENT",
              "targetType": "DOMAIN_AGENT",
              "targetId": "skill_b",
              "domainAgentId": "skill_b",
              "routeSource": "user-confirmed",
              "runtimeSessionId": "runtime_demo",
              "intentId": "intent_b",
              "intentName": "文档分析",
              "intentResult": {
                "accepted": true,
                "source": "user-confirmed",
                "resourceId": "skill_b",
                "skillId": "skill_b",
                "intentId": "intent_b",
                "intentName": "文档分析"
              }
            }
          }
        }
      }
    }
  }
]
```

拒绝时 REST 将 approved 改为 false；新 Run 记录拒绝，不调用 B：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_declined",
    "offset": "1831",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_declined",
        "streamItemId": "evt_1831",
        "serverTimestampMs": 1789516801831,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.started",
          "data": {
            "runId": "run_declined",
            "sessionId": "session_demo",
            "sequence": 1831,
            "type": "run.started",
            "payload": {
              "status": "STARTED",
              "userMessageId": "msg_user"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_declined",
    "offset": "1832",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_declined",
        "streamItemId": "evt_1832",
        "serverTimestampMs": 1789516801832,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_declined",
            "sessionId": "session_demo",
            "sequence": 1832,
            "type": "runtime.card",
            "payload": {
              "source": "chatservice",
              "sourceType": "route-switch-confirmation-response",
              "interactionId": "interaction_switch",
              "interactionType": "ROUTE_SWITCH_CONFIRMATION",
              "approved": false,
              "currentProvider": "domain-agent",
              "currentTargetId": "skill_a",
              "currentRouteSource": "front-selected",
              "candidateProvider": "domain-agent",
              "candidateTargetId": "skill_b",
              "candidateIntentName": "文档分析",
              "metadata": {}
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_declined",
    "offset": "1833",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_declined",
        "streamItemId": "evt_1833",
        "serverTimestampMs": 1789516801833,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_declined",
            "sessionId": "session_demo",
            "sequence": 1833,
            "type": "runtime.card",
            "payload": {
              "source": "chatservice",
              "sourceType": "route-switch-declined",
              "interactionId": "interaction_switch",
              "message": "已保留当前领域 Agent，本轮不切换处理能力。",
              "currentProvider": "domain-agent",
              "currentTargetId": "skill_a",
              "candidateProvider": "domain-agent",
              "candidateTargetId": "skill_b",
              "refusalCode": "FN-EX-CAHT-BIZ-DAG-001",
              "refusalReason": "当前技能不覆盖该问题"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_declined",
    "offset": "1835",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_declined",
        "streamItemId": "evt_1835",
        "serverTimestampMs": 1789516801835,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_declined",
            "sessionId": "session_demo",
            "sequence": 1835,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_declined",
              "feedbackTargetMessageId": "msg_declined"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_declined",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_declined",
        "serverTimestampMs": 1789516801836,
        "lastSeq": 1835,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

到达等待帧的 autoActionAt 时，确认切换的自动动作是前端提交 approved=true，不是后台定时器，也不是问卷的 ignore。只有批准切换支持重新提交标准附件；不传则不自动继承旧请求附件。若新技能附件不支持，顺序特殊：confirmation-response → 附件拒绝 progress/card → message.completed → **route-switch-applied → run.completed**，最后两事件与 B Binding 激活同一终态事务。终态失败/Stop 抢占时不得把早先 confirmation-response 展示成切换成功。FULL 历史及 Resume 顺序与已提交事实一致。

<a id="s08"></a>
### S08 候选技能立即调用与历史路由回放

先通过现有 REST 查询候选，再调用 `POST /v1/chat/runs/{sourceRunId}/switch-domain-agent`：


```json
{
  "messageId": "msg_user",
  "skillId": "skill_b",
  "selectedIntent": {
    "intentId": "intent_b",
    "intentName": "文档分析"
  },
  "intentAccessName": "finance_entry",
  "metadata": {}
}
```

Run-A 仍在运行时，接口先 Stop A。旧 topic 上可收到（若已有可保存输出）：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_switch_a",
    "offset": "1901",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_switch_a",
        "streamItemId": "evt_1901",
        "serverTimestampMs": 1789516801901,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.cancelled",
          "data": {
            "runId": "run_switch_a",
            "sessionId": "session_demo",
            "sequence": 1901,
            "type": "run.cancelled",
            "payload": {
              "status": "CANCELLED",
              "reason": "CANDIDATE_SWITCH",
              "messageReady": true,
              "assistantMessageId": "msg_switch_a",
              "feedbackTargetMessageId": "msg_switch_a"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_switch_a",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_switch_a",
        "serverTimestampMs": 1789516801902,
        "lastSeq": 1901,
        "terminalEventType": "run.cancelled"
      }
    }
  }
]
```

接口成功返回 B 的 ChatRunStartDto，前端改订阅 B；B 的完整执行示例如下。回放 payload 保持来源 Run，但外层 runId/sequence 属于 B。删除了回放中的旧 runtimeSessionId/runtimeBindingId：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_switch_b",
    "offset": "1910",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_switch_b",
        "streamItemId": "evt_1910",
        "serverTimestampMs": 1789516801910,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.started",
          "data": {
            "runId": "run_switch_b",
            "sessionId": "session_demo",
            "sequence": 1910,
            "type": "run.started",
            "payload": {
              "status": "STARTED",
              "userMessageId": "msg_user"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_switch_b",
    "offset": "1911",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_switch_b",
        "streamItemId": "evt_1911",
        "serverTimestampMs": 1789516801911,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_switch_b",
            "sessionId": "session_demo",
            "sequence": 1911,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-start",
              "stage": "intent_calling",
              "message": "正在识别问题意图",
              "routeTrigger": "",
              "candidateSwitchReplay": {
                "originRunId": "run_switch_a",
                "originSequence": 1880
              }
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_switch_b",
    "offset": "1912",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_switch_b",
        "streamItemId": "evt_1912",
        "serverTimestampMs": 1789516801912,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_switch_b",
            "sessionId": "session_demo",
            "sequence": 1912,
            "type": "runtime.progress",
            "payload": {
              "source": "intent-agent",
              "sourceType": "intent-result",
              "stage": "intent_result",
              "message": "已完成意图识别",
              "routeAction": "ROUTE_SINGLE",
              "routeTrigger": "",
              "latencyMs": 150,
              "intentCode": "finance_analysis",
              "intentId": "finance_analysis",
              "intentName": "经营分析",
              "confidence": 0.95,
              "skillId": "skill_a",
              "routeType": "DOMAIN_AGENT",
              "routeSource": "intent-agent",
              "targetProvider": "domain-agent",
              "targetId": "skill_a",
              "candidateSwitchReplay": {
                "originRunId": "run_switch_a",
                "originSequence": 1881
              }
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_switch_b",
    "offset": "1913",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_switch_b",
        "streamItemId": "evt_1913",
        "serverTimestampMs": 1789516801913,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_switch_b",
            "sessionId": "session_demo",
            "sequence": 1913,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "selectedDomainAgent",
              "metadataType": "selected_domain_agent",
              "routeType": "DOMAIN_AGENT",
              "targetType": "DOMAIN_AGENT",
              "targetId": "skill_a",
              "domainAgentId": "skill_a",
              "routeSource": "intent-agent",
              "intentId": "finance_analysis",
              "intentName": "经营分析",
              "intentResult": {
                "accepted": true,
                "source": "intent-agent",
                "resourceId": "skill_a",
                "skillId": "skill_a",
                "intentId": "finance_analysis",
                "intentName": "经营分析"
              },
              "candidateSwitchReplay": {
                "originRunId": "run_switch_a",
                "originSequence": 1882
              }
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_switch_b",
    "offset": "1914",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_switch_b",
        "streamItemId": "evt_1914",
        "serverTimestampMs": 1789516801914,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_switch_b",
            "sessionId": "session_demo",
            "sequence": 1914,
            "type": "runtime.progress",
            "payload": {
              "source": "chatservice",
              "sourceType": "candidate-skill-switch",
              "stage": "candidate_skill_switch",
              "status": "COMPLETED",
              "message": "已选择候选技能重新调用",
              "sourceRunId": "run_switch_a",
              "targetType": "DOMAIN_AGENT",
              "targetId": "skill_b",
              "skillId": "skill_b",
              "intentId": "intent_b",
              "intentName": "文档分析"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_switch_b",
    "offset": "1915",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_switch_b",
        "streamItemId": "evt_1915",
        "serverTimestampMs": 1789516801915,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.metadata",
          "data": {
            "runId": "run_switch_b",
            "sessionId": "session_demo",
            "sequence": 1915,
            "type": "runtime.metadata",
            "payload": {
              "source": "chatservice",
              "sourceType": "selectedDomainAgent",
              "metadataType": "selected_domain_agent",
              "routeType": "DOMAIN_AGENT",
              "targetType": "DOMAIN_AGENT",
              "targetId": "skill_b",
              "domainAgentId": "skill_b",
              "routeSource": "front-selected",
              "runtimeSessionId": "runtime_demo",
              "intentId": "intent_b",
              "intentName": "文档分析",
              "intentResult": {
                "accepted": true,
                "source": "front-selected",
                "resourceId": "skill_b",
                "skillId": "skill_b",
                "intentId": "intent_b",
                "intentName": "文档分析"
              }
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_switch_b",
    "offset": "1916",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_switch_b",
        "streamItemId": "evt_1916",
        "serverTimestampMs": 1789516801916,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_switch_b",
            "sessionId": "session_demo",
            "sequence": 1916,
            "type": "message.delta",
            "payload": {
              "delta": "候选技能的新回答。",
              "sourceType": "domain-agent-content"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_switch_b",
    "offset": "1917",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_switch_b",
        "streamItemId": "evt_1917",
        "serverTimestampMs": 1789516801917,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_switch_b",
            "sessionId": "session_demo",
            "sequence": 1917,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_switch_b",
    "offset": "1918",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_switch_b",
        "streamItemId": "evt_1918",
        "serverTimestampMs": 1789516801918,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_switch_b",
            "sessionId": "session_demo",
            "sequence": 1918,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_switch_b",
              "feedbackTargetMessageId": "msg_switch_b"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_switch_b",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_switch_b",
        "serverTimestampMs": 1789516801919,
        "lastSeq": 1918,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

最后的 candidate-skill-switch 持久化及后处理 ACK 完成后才放行后续路由/Runtime；这个 ACK 是内部控制，不额外发给前端。只回放意图路由、选择及既有切换标识，不复制旧正文、卡片、引用、拒答业务帧或终态。最多 32 条、256KiB 的路由继承可能被裁剪，不能假设总能看到完整无限回溯。

A 终态提交先于 B 创建，但不同连接及发送队列会影响到达顺序：B 的启动 HTTP 响应可能先于旧 topic 的 cancelled 到达浏览器。按 runId 分流，不要求先收到 A 的 WS 终态才能处理已成功受理的 B，也不能让迟到的 A 终态关闭 B 的运行状态。

A 已完成则跳过 Stop，不再发一个 A 的 cancelled。原 user 不复制，B 是新 assistant 版本，前端清空新版本的临时正文/思维链后消费 B，而不是往 A 的正文末尾追加；A/B 历史版本仍可查。B 再次拒答沿 S07 处理。source 不在当前允许路径、其他活动 Run 或 Stop 未完成等情况返回 REST 409，不能先假定 B 已启动。当前不支持任意更早非末尾历史回答分支重生成；该能力不在本手册描述范围内。FULL 可恢复继承 Parts；no-store 不强制复制不可留存的业务事件。意图反馈结果通过 REST/历史 DTO 回显，不新增 WS 通知。

<a id="s09"></a>
### S09 附件类型拒绝与数量失败

REST 上传文档并在 Run 中提交可信 documentId；最终选择 DomainAgent 后校验。技能只支持 xlsx，实际上传 pdf 时，不发起下游调用，也不生成拒绝说明的 message.delta。前端根据结构化字段生成文案，以下业务完成段：


```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_attach",
    "offset": "2001",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_attach",
        "streamItemId": "evt_2001",
        "serverTimestampMs": 1789516802001,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.progress",
          "data": {
            "runId": "run_attach",
            "sessionId": "session_demo",
            "sequence": 2001,
            "type": "runtime.progress",
            "payload": {
              "source": "chatservice",
              "sourceType": "domain-agent-attachment-validation",
              "code": "DOMAIN_AGENT_ATTACHMENT_TYPE_UNSUPPORTED",
              "skillId": "skill_a",
              "skillName": "经营分析",
              "supportedAttachmentTypes": [
                ".xlsx"
              ],
              "unsupportedAttachmentTypes": [
                ".pdf"
              ],
              "unsupportedAttachments": [
                {
                  "documentId": "doc_pdf",
                  "name": "report.pdf",
                  "extension": ".pdf"
                }
              ],
              "stage": "attachment_validation",
              "status": "FAILED"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_attach",
    "offset": "2002",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_attach",
        "streamItemId": "evt_2002",
        "serverTimestampMs": 1789516802002,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_attach",
            "sessionId": "session_demo",
            "sequence": 2002,
            "type": "runtime.card",
            "payload": {
              "source": "chatservice",
              "sourceType": "domain-agent-attachment-validation",
              "code": "DOMAIN_AGENT_ATTACHMENT_TYPE_UNSUPPORTED",
              "skillId": "skill_a",
              "skillName": "经营分析",
              "supportedAttachmentTypes": [
                ".xlsx"
              ],
              "unsupportedAttachmentTypes": [
                ".pdf"
              ],
              "unsupportedAttachments": [
                {
                  "documentId": "doc_pdf",
                  "name": "report.pdf",
                  "extension": ".pdf"
                }
              ],
              "cardType": "domainAgentAttachmentUnsupported",
              "cardSources": [
                "attachmentValidation"
              ]
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_attach",
    "offset": "2003",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_attach",
        "streamItemId": "evt_2003",
        "serverTimestampMs": 1789516802003,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_attach",
            "sessionId": "session_demo",
            "sequence": 2003,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED",
              "finishReason": "ATTACHMENT_TYPE_UNSUPPORTED",
              "skillInvocationStarted": false
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_attach",
    "offset": "2004",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_attach",
        "streamItemId": "evt_2004",
        "serverTimestampMs": 1789516802004,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_attach",
            "sessionId": "session_demo",
            "sequence": 2004,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_attach",
              "feedbackTargetMessageId": "msg_attach",
              "routeType": "DOMAIN_AGENT",
              "routeSource": "front-selected",
              "agentCode": "skill_a",
              "runtimeBindingId": "binding_attach",
              "runtimeProvider": "domain-agent"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_attach",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_attach",
        "serverTimestampMs": 1789516802005,
        "lastSeq": 2004,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

配置查询成功但 attachmentType 为 null/空白/未配置时，不支持任何附件；同样的事件顺序，其中 progress/card 的公共字段如下完整卡片示例（无扩展名也拒绝）：

```json
{
  "type": "message",
  "topicId": "chat-run-run_no_upload",
  "offset": "2012",
  "payload": {
    "type": "conversation-turn-stream",
    "payload": {
      "type": "stream-item",
      "conversationId": "session_demo",
      "turnId": "run_no_upload",
      "streamItemId": "evt_2012",
      "serverTimestampMs": 1789516802012,
      "encodedItem": {
        "encoding": "chat-event-json-v1",
        "event": "runtime.card",
        "data": {
          "runId": "run_no_upload",
          "sessionId": "session_demo",
          "sequence": 2012,
          "type": "runtime.card",
          "payload": {
            "source": "chatservice",
            "sourceType": "domain-agent-attachment-validation",
            "code": "DOMAIN_AGENT_ATTACHMENT_TYPE_UNSUPPORTED",
            "skillId": "skill_a",
            "skillName": "经营分析",
            "supportedAttachmentTypes": [],
            "unsupportedAttachmentTypes": [
              ".pdf"
            ],
            "unsupportedAttachments": [
              {
                "documentId": "doc_pdf",
                "name": "report.pdf",
                "extension": ".pdf"
              },
              {
                "documentId": "doc_plain",
                "name": "README",
                "extension": ""
              }
            ],
            "cardType": "domainAgentAttachmentUnsupported",
            "cardSources": [
              "attachmentValidation"
            ]
          }
        }
      }
    }
  }
}
```

前端：supportedAttachmentTypes 为空时展示“该技能不支持上传附件”，非空时展示不支持的类型。progress.status=FAILED 是**校验结果**，最终 Run 是 completed，不要误画成系统异常。无附件、Relay 不走此拒绝；配置接口异常仍按既有降级策略处理，不能和配置为空混同。Binding 按附件拒绝的完成事务生效；FULL 保存结构化 Parts，no-store 保留必要控制事实。

附件超过 DomainAgent 上限则是异常路径，不产生上述成功收口卡片。默认上限 10 时，Run 已启动后检测到超限的失败段示例：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_count",
    "offset": "2021",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_count",
        "streamItemId": "evt_2021",
        "serverTimestampMs": 1789516802021,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.failed",
          "data": {
            "runId": "run_count",
            "sessionId": "session_demo",
            "sequence": 2021,
            "type": "run.failed",
            "payload": {
              "code": "RUN_ERROR",
              "message": "DomainAgent 附件数量超过上限: 10"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_count",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_count",
        "serverTimestampMs": 1789516802022,
        "lastSeq": 2021,
        "terminalEventType": "run.failed"
      }
    }
  }
]
```

HTTP DTO 数量/权限校验若在准入前失败，则只有 REST 400 等错误，不保证有 Run 或 WS 事件。不能将所有附件问题统一等价为 run.completed。

<a id="s10"></a>
### S10 DomainAgent 异步等待与回填

该能力需启用异步任务配置。普通 Run 收到下游 agent.async_started 后结束当前 DomainAgent HTTP 执行；服务端输出并保存：


```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_async",
    "offset": "2101",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_async",
        "streamItemId": "evt_2101",
        "serverTimestampMs": 1789516802101,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.async_running",
          "data": {
            "runId": "run_async",
            "sessionId": "session_demo",
            "sequence": 2101,
            "type": "run.async_running",
            "payload": {
              "source": "domain-agent",
              "sourceType": "agent.async_started",
              "status": "ASYNC_RUNNING",
              "message": "任务已转入后台执行",
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async",
              "expiresAt": "2026-09-17T00:00:00Z"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_async",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "heartbeat",
        "conversationId": "session_demo",
        "turnId": "run_async",
        "serverTimestampMs": 1789516802102,
        "lastSeq": 2101
      }
    }
  }
]
```

这里**没有 message.completed、run.completed 或 done**。WS topic 可继续订阅，接收稍后的回调结果；数据库 Run 仍 RUNNING，前端维持运行中/Stop，同会话不发新 Query。原 Run Resume 在异步边界结束，不代表任务结束，也不应立即循环重连 Resume。

#### 空结果只通知完成

下游调用内部 REST `POST /v1/internal/domain-agent/async-tasks/callback`（不是浏览器接口，也不是 WS 命令）：


```json
{
  "runId": "run_async",
  "status": "COMPLETED",
  "error": null
}
```

原 topic 的后续响应如下，首个 async_result_started 被省略：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_async",
    "offset": "2110",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_async",
        "streamItemId": "evt_2110",
        "serverTimestampMs": 1789516802110,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.async_finished",
          "data": {
            "runId": "run_async",
            "sessionId": "session_demo",
            "sequence": 2110,
            "type": "run.async_finished",
            "payload": {
              "source": "domain-agent",
              "sourceType": "agent.async_finished",
              "status": "COMPLETED",
              "asyncTask": true,
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_async",
    "offset": "2111",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_async",
        "streamItemId": "evt_2111",
        "serverTimestampMs": 1789516802111,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_async",
            "sessionId": "session_demo",
            "sequence": 2111,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async",
              "asyncTask": true
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_async",
    "offset": "2112",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_async",
        "streamItemId": "evt_2112",
        "serverTimestampMs": 1789516802112,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_async",
            "sessionId": "session_demo",
            "sequence": 2112,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async",
              "asyncTask": true
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_async",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_async",
        "serverTimestampMs": 1789516802113,
        "lastSeq": 2112,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

前端结束运行状态，不凭空插入业务正文。即使 no-store，也能恢复这些控制及终态事实。

#### APPEND 追加结果

另一个仍在等待的 Run，回调请求：


```json
{
  "runId": "run_append",
  "status": "COMPLETED",
  "resultMode": "APPEND",
  "frames": [
    {
      "content": "追加结果。"
    },
    {
      "searchList": []
    }
  ],
  "error": null
}
```

服务端 → 客户端：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_append",
    "offset": "2120",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_append",
        "streamItemId": "evt_2120",
        "serverTimestampMs": 1789516802120,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.async_result_started",
          "data": {
            "runId": "run_append",
            "sessionId": "session_demo",
            "sequence": 2120,
            "type": "run.async_result_started",
            "payload": {
              "source": "domain-agent",
              "sourceType": "agent.async_result_started",
              "status": "ASYNC_RESULT_STARTED",
              "resultMode": "APPEND",
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_append",
    "offset": "2121",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_append",
        "streamItemId": "evt_2121",
        "serverTimestampMs": 1789516802121,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_append",
            "sessionId": "session_demo",
            "sequence": 2121,
            "type": "message.delta",
            "payload": {
              "delta": "追加结果。",
              "sourceType": "domain-agent-content"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_append",
    "offset": "2122",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_append",
        "streamItemId": "evt_2122",
        "serverTimestampMs": 1789516802122,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.reference",
          "data": {
            "runId": "run_append",
            "sessionId": "session_demo",
            "sequence": 2122,
            "type": "runtime.reference",
            "payload": {
              "source": "domain-agent",
              "sourceType": "searchList",
              "referenceType": "search_list",
              "references": []
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_append",
    "offset": "2123",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_append",
        "streamItemId": "evt_2123",
        "serverTimestampMs": 1789516802123,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.async_finished",
          "data": {
            "runId": "run_append",
            "sessionId": "session_demo",
            "sequence": 2123,
            "type": "run.async_finished",
            "payload": {
              "source": "domain-agent",
              "sourceType": "agent.async_finished",
              "status": "COMPLETED",
              "asyncTask": true,
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_append",
    "offset": "2124",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_append",
        "streamItemId": "evt_2124",
        "serverTimestampMs": 1789516802124,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_append",
            "sessionId": "session_demo",
            "sequence": 2124,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async",
              "asyncTask": true
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_append",
    "offset": "2125",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_append",
        "streamItemId": "evt_2125",
        "serverTimestampMs": 1789516802125,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_append",
            "sessionId": "session_demo",
            "sequence": 2125,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async",
              "asyncTask": true
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_append",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_append",
        "serverTimestampMs": 1789516802126,
        "lastSeq": 2125,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

保留当前 assistant 正文和 Parts，精确追加，不自动加换行。若旧正文非空、回调先有 thinking 再有 content，FULL 历史沿用 DomainAgent 分段标识，实时仍只有原业务事件。

#### REPLACE 覆盖结果

回调将 resultMode 改为 REPLACE，可只提供卡片而无正文：


```json
{
  "runId": "run_replace",
  "status": "COMPLETED",
  "resultMode": "REPLACE",
  "frames": [
    {
      "type": "recommended_questions",
      "recommendedQuestions": [
        "查看下一步"
      ]
    }
  ]
}
```

服务端 → 客户端：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_replace",
    "offset": "2130",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_replace",
        "streamItemId": "evt_2130",
        "serverTimestampMs": 1789516802130,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.async_result_started",
          "data": {
            "runId": "run_replace",
            "sessionId": "session_demo",
            "sequence": 2130,
            "type": "run.async_result_started",
            "payload": {
              "source": "domain-agent",
              "sourceType": "agent.async_result_started",
              "status": "ASYNC_RESULT_STARTED",
              "resultMode": "REPLACE",
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_replace",
    "offset": "2131",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_replace",
        "streamItemId": "evt_2131",
        "serverTimestampMs": 1789516802131,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "runtime.card",
          "data": {
            "runId": "run_replace",
            "sessionId": "session_demo",
            "sequence": 2131,
            "type": "runtime.card",
            "payload": {
              "source": "domain-agent",
              "sourceType": "recommended_questions",
              "cardType": "recommendedQuestions",
              "cardSources": [
                "recommendedQuestions"
              ],
              "recommendedQuestions": [
                "查看下一步"
              ]
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_replace",
    "offset": "2132",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_replace",
        "streamItemId": "evt_2132",
        "serverTimestampMs": 1789516802132,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.async_finished",
          "data": {
            "runId": "run_replace",
            "sessionId": "session_demo",
            "sequence": 2132,
            "type": "run.async_finished",
            "payload": {
              "source": "domain-agent",
              "sourceType": "agent.async_finished",
              "status": "COMPLETED",
              "asyncTask": true,
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_replace",
    "offset": "2133",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_replace",
        "streamItemId": "evt_2133",
        "serverTimestampMs": 1789516802133,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_replace",
            "sessionId": "session_demo",
            "sequence": 2133,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async",
              "asyncTask": true
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_replace",
    "offset": "2134",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_replace",
        "streamItemId": "evt_2134",
        "serverTimestampMs": 1789516802134,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_replace",
            "sessionId": "session_demo",
            "sequence": 2134,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async",
              "asyncTask": true
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_replace",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_replace",
        "serverTimestampMs": 1789516802135,
        "lastSeq": 2134,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

收到 result_started(REPLACE) 时清空当前 assistant 正文及**当前 run 产生的旧 Parts**，保留其他 Run 的 Parts，再消费新结果。这个例子最终正文为空，卡片有效。FULL 事件日志不会因 REPLACE 被删除，重放也必须执行清空语义，否则会把旧新结果重复拼接。只有终态帧且过滤后无业务结果时不触发 REPLACE、不清正文/Parts，也没有 result_started。

#### FAILED 仍可有部分结果

回调：


```json
{
  "runId": "run_async_failed",
  "status": "FAILED",
  "resultMode": "APPEND",
  "frames": [
    {
      "content": "已完成第一部分。"
    }
  ],
  "error": "第二部分数据暂不可用"
}
```

服务端 → 客户端：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_async_failed",
    "offset": "2140",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_async_failed",
        "streamItemId": "evt_2140",
        "serverTimestampMs": 1789516802140,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.async_result_started",
          "data": {
            "runId": "run_async_failed",
            "sessionId": "session_demo",
            "sequence": 2140,
            "type": "run.async_result_started",
            "payload": {
              "source": "domain-agent",
              "sourceType": "agent.async_result_started",
              "status": "ASYNC_RESULT_STARTED",
              "resultMode": "APPEND",
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_async_failed",
    "offset": "2141",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_async_failed",
        "streamItemId": "evt_2141",
        "serverTimestampMs": 1789516802141,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_async_failed",
            "sessionId": "session_demo",
            "sequence": 2141,
            "type": "message.delta",
            "payload": {
              "delta": "已完成第一部分。",
              "sourceType": "domain-agent-content"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_async_failed",
    "offset": "2142",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_async_failed",
        "streamItemId": "evt_2142",
        "serverTimestampMs": 1789516802142,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.async_finished",
          "data": {
            "runId": "run_async_failed",
            "sessionId": "session_demo",
            "sequence": 2142,
            "type": "run.async_finished",
            "payload": {
              "source": "domain-agent",
              "sourceType": "agent.async_finished",
              "status": "FAILED",
              "asyncTask": true,
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_async_failed",
    "offset": "2143",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_async_failed",
        "streamItemId": "evt_2143",
        "serverTimestampMs": 1789516802143,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.completed",
          "data": {
            "runId": "run_async_failed",
            "sessionId": "session_demo",
            "sequence": 2143,
            "type": "message.completed",
            "payload": {
              "status": "MESSAGE_COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async",
              "asyncTask": true
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_async_failed",
    "offset": "2144",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_async_failed",
        "streamItemId": "evt_2144",
        "serverTimestampMs": 1789516802144,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.failed",
          "data": {
            "runId": "run_async_failed",
            "sessionId": "session_demo",
            "sequence": 2144,
            "type": "run.failed",
            "payload": {
              "code": "DOMAIN_AGENT_ASYNC_FAILED",
              "message": "DomainAgent后台任务执行失败",
              "asyncTask": true,
              "messageReady": true,
              "assistantMessageId": "msg_async",
              "feedbackTargetMessageId": "msg_async",
              "error": "第二部分数据暂不可用"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_async_failed",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_async_failed",
        "serverTimestampMs": 1789516802145,
        "lastSeq": 2144,
        "terminalEventType": "run.failed"
      }
    }
  }
]
```

保留可展示的部分结果，但任务状态为失败。error 为可选文本，trim 后最多 1024 个 Unicode 码点，空白不出现；不解析成任意业务 JSON。没有 frames 的失败只省略开始及业务段。

前端与下游边界：回调是一次性终态提交，最多 128 帧/128 个业务事件/1MiB 事件数据，原始体和并发另有限制；不能分多次 APPEND。重复、取消后、到期回调可返回 accepted=false，不产生第二套事件；尚未进入异步等待的快速回调返回可重试 409。容量/协议错误返回 REST 413/400，保持等待供下游修正，不虚构前端 run.failed。问卷、拒答及再次 async_started 不在回调中执行控制状态机。FULL 可通过 Resume 恢复已提交结果；no-store 业务结果只实时发送，不能承诺断线补齐。

<a id="s11"></a>
### S11 Stop、超时、恢复提示与连接关闭

#### 运行中 Stop

REST `POST /v1/chat/runs/{runId}/stop`，使用当前运行的 Run ID。Run 先进入 CANCELLING，再做有界下游取消和本地终态；**没有专用 run.cancelling WS 事件**。取消竞争也可能先由自然完成赢得终态，按实际响应处理。

存在可保存 partial 时：


```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_stop",
    "offset": "2201",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_stop",
        "streamItemId": "evt_2201",
        "serverTimestampMs": 1789516802201,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.cancelled",
          "data": {
            "runId": "run_stop",
            "sessionId": "session_demo",
            "sequence": 2201,
            "type": "run.cancelled",
            "payload": {
              "status": "CANCELLED",
              "reason": "USER_STOP",
              "messageReady": true,
              "assistantMessageId": "msg_partial",
              "feedbackTargetMessageId": "msg_partial"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_stop",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_stop",
        "serverTimestampMs": 1789516802202,
        "lastSeq": 2201,
        "terminalEventType": "run.cancelled"
      }
    }
  }
]
```

无可保存内容时：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_stop_empty",
    "offset": "2211",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_stop_empty",
        "streamItemId": "evt_2211",
        "serverTimestampMs": 1789516802211,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.cancelled",
          "data": {
            "runId": "run_stop_empty",
            "sessionId": "session_demo",
            "sequence": 2211,
            "type": "run.cancelled",
            "payload": {
              "status": "CANCELLED",
              "reason": "USER_STOP",
              "messageReady": false
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_stop_empty",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_stop_empty",
        "serverTimestampMs": 1789516802212,
        "lastSeq": 2211,
        "terminalEventType": "run.cancelled"
      }
    }
  }
]
```

取消不保证下游立刻停止，也不保证前端收到所有在途帧。保留现有部分结果，messageReady=true 时可回读历史；无消息不要猜 assistant ID。关闭 WS、退订或隐藏页面都不是 Stop。

#### 等待态 Stop：不能等待旧 topic 再发 cancelled

Run-A 进入 WAITING_USER 时已经发送 done 并退订 topic。此后调用 Stop 主要取消 Interaction 及其引用的 Binding；未产生续跑时，原 Run 可仍保留 WAITING_USER 的历史事实，**不会额外向旧 Run 追加 run.cancelled**。前端检查 Stop REST 返回的 interactionStatus/interactionCancelledAt/effectiveRunId，并刷新 stream-status，禁用旧卡片。不要因历史里仍有 waiting_user 就再次启用已取消卡片。

若等待已被另一个页签领取并正在 Run-B 中续跑，Stop 可停止 effectiveRunId=B；B 的 topic 才出现 S11 的 run.cancelled。这不是原 Run-A 产生第二个 done。

#### 下游超时与普通运行失败

已启动的 Run 发生匹配超时异常时，示例失败段：


```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_timeout",
    "offset": "2221",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_timeout",
        "streamItemId": "evt_2221",
        "serverTimestampMs": 1789516802221,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.failed",
          "data": {
            "runId": "run_timeout",
            "sessionId": "session_demo",
            "sequence": 2221,
            "type": "run.failed",
            "payload": {
              "code": "RUNTIME_STREAM_TIMEOUT",
              "message": "Runtime execution timed out"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_timeout",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_timeout",
        "serverTimestampMs": 1789516802222,
        "lastSeq": 2221,
        "terminalEventType": "run.failed"
      }
    }
  }
]
```

Relay config 握手超时可用 code=RELAY_WS_CONFIG_TIMEOUT；会话不可恢复为 RUNTIME_SESSION_UNAVAILABLE；一般异常可为 RUN_ERROR。错误消息取实际异常，不依赖示例英文文案。失败前不一定有 message.completed，payload 也不一定包含 messageReady。HTTP 启动前的 400/409/429/503、首事件等待异常与已持久化 Run 的失败需分别处理，禁止收到 REST 错误后盲目重复创建相同工作。

异步等待到期由 Watchdog 收口：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_async_timeout",
    "offset": "2231",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_async_timeout",
        "streamItemId": "evt_2231",
        "serverTimestampMs": 1789516802231,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.failed",
          "data": {
            "runId": "run_async_timeout",
            "sessionId": "session_demo",
            "sequence": 2231,
            "type": "run.failed",
            "payload": {
              "code": "DOMAIN_AGENT_ASYNC_TIMEOUT",
              "message": "DomainAgent后台任务等待超时",
              "source": "chat-run-watchdog",
              "asyncTask": true,
              "messageReady": true,
              "assistantMessageId": "msg_async_timeout",
              "feedbackTargetMessageId": "msg_async_timeout"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_async_timeout",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_async_timeout",
        "serverTimestampMs": 1789516802232,
        "lastSeq": 2231,
        "terminalEventType": "run.failed"
      }
    }
  }
]
```

执行实例心跳丢失的默认人工恢复策略示例；它是失败事实，不是继续运行的确认：

```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_lost",
    "offset": "2241",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_demo",
        "turnId": "run_lost",
        "streamItemId": "evt_2241",
        "serverTimestampMs": 1789516802241,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.failed",
          "data": {
            "runId": "run_lost",
            "sessionId": "session_demo",
            "sequence": 2241,
            "type": "run.failed",
            "payload": {
              "code": "RUN_EXECUTOR_LOST",
              "message": "执行实例心跳超时，本轮回答已中断",
              "recoveryStrategy": "MANUAL_CONFIRMATION",
              "recoveryActionRequired": true,
              "recoveryOptions": [
                "REGENERATE_ASSISTANT",
                "RETRY_AS_NEW_RUN"
              ],
              "ownerInstanceId": "instance_old",
              "recoveredByInstanceId": "instance_new",
              "heartbeatAt": "2026-09-16T00:00:00Z",
              "leaseUntil": "2026-09-16T00:01:00Z"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_lost",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_demo",
        "turnId": "run_lost",
        "serverTimestampMs": 1789516802242,
        "lastSeq": 2241,
        "terminalEventType": "run.failed"
      }
    }
  }
]
```

recoveryOptions 为服务端建议，实际可用操作仍服从消息路径等校验。默认策略不承诺自动接管下游。run.recovered 是预留接管诊断事件，当前默认 Runtime 不会产生；不提供伪造的必达 payload 示例，也不要把 WS 重连或历史回放画成 run.recovered。未来启用接管扩展时再以其实际生产点定义载荷。

#### RECOVER_REQUIRED

以下为合法的序号回退提示：最高已发送 2252，迟到 2250，建议从 2249 恢复，而不是从本地最高游标恢复。


```json
{
  "type": "error",
  "topicId": "chat-run-run_recover",
  "offset": "2250",
  "code": "RECOVER_REQUIRED",
  "message": "实时事件需要恢复，请使用 Event Resume 从 afterSeq=2249 补齐",
  "details": {
    "reason": "SEQ_ROLLBACK",
    "topicId": "chat-run-run_recover",
    "runId": "run_recover",
    "sessionId": "session_demo",
    "subscribeAfterSeq": 2240,
    "recoveryAfterSeq": 2249,
    "actualSeq": 2250,
    "highestDeliveredSeq": 2252,
    "lastSentSeq": 2252
  }
}
```

停止该 topic 当前拼接，使用 details.recoveryAfterSeq 打开 Run Resume 并去重；该游标可能小于本地最高值，不取两者 max，也不把 error.offset 当作已处理业务事件。LIVE_BUFFER_OVERFLOW、Redis live 发布/注册故障等也可能要求恢复，details 随原因变化；没有 details 时才回退本地成功消费位置。

Servlet 单连接发送队列溢出、发送失败、空闲超时或网关断连可能直接关闭整条连接，**不保证还能发出 RECOVER_REQUIRED 或 done**。其他 topic 也一并断开，逐 Run 恢复。连接关闭帧不是上述 JSON Envelope；不能虚构额外的 connection.closed 业务事件。

<a id="s12"></a>
### S12 心跳、done、多 topic、留存与刷新恢复

服务端 → 客户端：同一物理连接订阅两个不同会话的 Run，消息可交错。B 的 seq=2302 插在 A 的2301/2303之间，不是 A 缺消息：


```json
[
  {
    "type": "message",
    "topicId": "chat-run-run_multi_a",
    "offset": "2301",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_multi_a",
        "turnId": "run_multi_a",
        "streamItemId": "evt_2301",
        "serverTimestampMs": 1789516802301,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_multi_a",
            "sessionId": "session_multi_a",
            "sequence": 2301,
            "type": "message.delta",
            "payload": {
              "delta": "A第一段"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_multi_b",
    "offset": "2302",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_multi_b",
        "turnId": "run_multi_b",
        "streamItemId": "evt_2302",
        "serverTimestampMs": 1789516802302,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_multi_b",
            "sessionId": "session_multi_b",
            "sequence": 2302,
            "type": "message.delta",
            "payload": {
              "delta": "B第一段"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_multi_a",
    "offset": "2303",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_multi_a",
        "turnId": "run_multi_a",
        "streamItemId": "evt_2303",
        "serverTimestampMs": 1789516802303,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "message.delta",
          "data": {
            "runId": "run_multi_a",
            "sessionId": "session_multi_a",
            "sequence": 2303,
            "type": "message.delta",
            "payload": {
              "delta": "A第二段"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_multi_b",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "heartbeat",
        "conversationId": "session_multi_b",
        "turnId": "run_multi_b",
        "serverTimestampMs": 1789516802303,
        "lastSeq": 2302
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_multi_a",
    "offset": "2304",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "stream-item",
        "conversationId": "session_multi_a",
        "turnId": "run_multi_a",
        "streamItemId": "evt_2304",
        "serverTimestampMs": 1789516802304,
        "encodedItem": {
          "encoding": "chat-event-json-v1",
          "event": "run.completed",
          "data": {
            "runId": "run_multi_a",
            "sessionId": "session_multi_a",
            "sequence": 2304,
            "type": "run.completed",
            "payload": {
              "status": "COMPLETED",
              "messageReady": true,
              "assistantMessageId": "msg_multi_a",
              "feedbackTargetMessageId": "msg_multi_a"
            }
          }
        }
      }
    }
  },
  {
    "type": "message",
    "topicId": "chat-run-run_multi_a",
    "payload": {
      "type": "conversation-turn-stream",
      "payload": {
        "type": "done",
        "conversationId": "session_multi_a",
        "turnId": "run_multi_a",
        "serverTimestampMs": 1789516802305,
        "lastSeq": 2304,
        "terminalEventType": "run.completed"
      }
    }
  }
]
```

每个 Run 独立保留已处理游标，不把一个 Run 的高序号拿去恢复另一个 Run。心跳和 done 没有 offset、streamItemId、encodedItem，不是持久化 ChatEvent，也不增加 sequence。A 的 done 只退订 A，B 继续接收。没有订阅的物理 WS 不保证收到 Run heartbeat；JSON heartbeat 与协议层 Ping/Pong 也不是同一个概念。

| 内容 | FULL | no-store / ASSISTANT_PLACEHOLDER |
|---|---|---|
| 已提交业务事件、正文及业务 Parts | 可按保留期及接口边界恢复 | 业务事件仅 live，历史正文占位；不能补回已丢业务结果 |
| 开始、终态、必要路由/问卷/切换/异步控制 | 持久化 | 仍保留必要控制事实，具体按事件留存策略 |
| WS Envelope、reply、heartbeat、done、error | 不落事件表 | 同样不落事件表 |
| 是否有独立“本帧已落库”字段 | 无 | 无；有 sequence 不代表业务帧可 Resume |

不要把“数据库先提交再发布”的说明套到 no-store 业务帧；也不要把 publish 成功理解为浏览器已渲染。FULL 的提交后发布崩溃窗口可以通过 Resume 恢复；no-store 仍为 best-effort。历史 Parts 是否可见由 Part 规则决定，ANSWER 快照 Part 不需要重复渲染为另一段正文。

## 三、前端处理与恢复

### 1. 新 Run 与通用渲染

1. 建立 WS 并完成 connect。用 REST 创建 Run；保存 runId/sessionId/userMessageId/firstSeq/streamTopicId。
2. 用返回 topic 订阅。已把启动信息作为 run.started 处理时可使用 afterSeq=firstSeq；需要实际开始事件则使用 firstSeq-1。afterSeq 是排他的，不保证每次订阅都能看到 run.started。
3. 校验 topic、turnId、conversationId 与 ChatEvent 身份一致；按 sessionId + sequence 去重，按 Run 分区维护正文、思考、Parts 和状态。
4. 只在业务事件成功处理后推进该 Run 的本地游标；reply.lastSeq、heartbeat.lastSeq、done.lastSeq、error.offset 不替代消费 ACK。数据库全局序号允许跳号。
5. 先分 Envelope.type，再分片段 type，最后分 ChatEvent.type/source/sourceType；失败、等待、异步分别驱动状态，不用“HTTP结束”或“出现卡片”推断。
6. 问卷等待、候选切换和重生成按各自 messageId/版本规则定位；不要认为 Run-B 就必须追加一个新 user。收到未知可选业务字段保留/忽略，不崩溃整个流。

### 2. 刷新页面与 Resume

刷新后物理 WS 已断开，旧订阅不会自动迁移。推荐：

1. 查询 `GET /v1/chat/sessions/{sessionId}/messages` 恢复历史路径，再读 `GET /v1/chat/sessions/{sessionId}/stream-status`。
2. 有 waitingInteraction 时按最新状态恢复卡片；不要仅靠旧 run.waiting_user 判断可提交。自动动作需同时检查期限和 Interaction 状态。
3. 普通活动 Run：用其 activeRunId、activeRunFirstSeq（或已消费游标）打开 `GET /v1/chat/runs/{runId}/events/resume?afterSeq=...`。首次重建当前 Run 临时输出通常从 activeRunFirstSeq-1 开始，避免在已有完整正文上再次无条件累加；复用 assistant 的续跑必须保留前轮内容并仅重建本轮。
4. Run Resume 先读已留存事件，再在适用时接续 live。同一个 Run 不同时消费 WS 与 Resume live；若切回 WS，先结束旧 live 消费，再按最后成功处理游标订阅，消除重复。恢复流异常结束且无 done 时，退避后重新查询状态/Resume。
5. 会话级 `GET /v1/chat/sessions/{sessionId}/events/resume` 是有限历史补发，不用于等待未来回调。Run 级与会话级游标口径不要混用。
6. 所有增量恢复仍按 sequence 去重，不能因网络重复就重复执行 REPLACE、追加卡片或提交问卷；幂等 UI 操作与消息去重一起实现。

SSE 的 data 是 WS 外层 payload，而不是完整 WS Envelope。例如：


```json
{
  "type": "conversation-turn-stream",
  "payload": {
    "type": "stream-item",
    "conversationId": "session_demo",
    "turnId": "run_resume",
    "streamItemId": "evt_2401",
    "serverTimestampMs": 1789516802401,
    "encodedItem": {
      "encoding": "chat-event-json-v1",
      "event": "message.delta",
      "data": {
        "runId": "run_resume",
        "sessionId": "session_demo",
        "sequence": 2401,
        "type": "message.delta",
        "payload": {
          "delta": "恢复片段"
        }
      }
    }
  }
}
```

实际 SSE 使用 `event: conversation-turn-stream`，不额外套 type=message/topicId/offset。普通 Run 恢复终态会产生 done；有限补发可能在没有终态时自然结束，不能据此推断任务完成。

### 3. 异步等待刷新专项

异步仍在运行时，stream-status 的**相关字段摘录**如下（不是完整 DTO，也不是 WS 帧）：


```json
{
  "activeRunId": "run_async",
  "activeRunStatus": "RUNNING",
  "activeRunPhase": "ASYNC_RUNNING",
  "asyncExpiresAt": "2026-09-17T00:00:00Z",
  "assistantMessageId": "msg_async",
  "activeStreamTopicId": "chat-run-run_async",
  "activeRunFirstSeq": 2090
}
```

先定位 msg_async；通过 Run Resume 恢复到异步边界后，保持运行中 UI，并使用 WS 订阅同一个 topic 等待后续回调。若 callback 恰好发生在 Resume 结束与订阅之间，FULL 的已提交事件可由 subscribe(afterSeq=最后已处理序号)补发；不要使用最新数据库水位跳过未知结果。订阅后仍需处理终态，而不是看到 ASYNC_RUNNING 就只监听一次。

页面关闭期间任务已完成：重新读取 /messages 获取最终正文/Parts及 metadata，stream-status 不再显示该任务为 active；有已知 runId 时可按需 Run Resume 读取控制/终态。如果 Run 从异步转终态正好落在历史查询与状态查询之间，再读取历史，避免界面保留旧占位。no-store 不保证找回关闭期间的真实业务内容，只能展示留存边界内的结果和状态。

### 4. 明确不存在的通知与安全边界

- 标题提炼更新、独立意图反馈/偏好保存、会话管理 REST 成功，不会凭空产生标题更新或反馈更新 WebSocket 事件；用现有查询接口刷新。
- 不把 ChatService 到 Relay 的 config/chat_expert/approval-response、DomainAgent 的请求体或内部异步回调当成前端收到的 WS 帧。
- 不将私有 metadata、Cookie、鉴权头或问卷私有上下文写入前端示例。业务 payload 中敏感字段会按 Normalizer 规则脱敏；这不是允许前端回传服务端关联标识。
- 本文保证的是当前后端封装和响应契约。卡片脚本、Markdown、思维链/问卷渲染及自动动作仍需前端联调；分享是否能加载外部组件取决于其实现与环境。

## 核对依据与验证记录

| 契约 | 主要源码 |
|---|---|
| WS 请求、reply、错误、done/退订 | [ChatWebSocketProtocolService](../src/main/java/com/huawei/it/ex/one/interfaces/chat/websocket/ChatWebSocketProtocolService.java)、[ChatWebSocketEnvelopeDto](../src/main/java/com/huawei/it/ex/one/interfaces/chat/dto/ChatWebSocketEnvelopeDto.java) |
| 封装、evt_、时间戳、终态集合 | [ChatTurnStreamTranslator](../src/main/java/com/huawei/it/ex/one/interfaces/chat/ChatTurnStreamTranslator.java) |
| Intent 过程与结果 | [StreamingIntentAgentRuntime](../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/intentagent/StreamingIntentAgentRuntime.java)、[RouteSignalApplicationService](../src/main/java/com/huawei/it/ex/one/application/service/routing/RouteSignalApplicationService.java) |
| DomainAgent/Relay 分类与脱敏 | [DomainAgentResponseNormalizer](../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/domainagent/DomainAgentResponseNormalizer.java)、[RelayRuntimeResponseNormalizer](../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayRuntimeResponseNormalizer.java) |
| 问卷/路由确认/等待事实 | [InteractionEventFactory](../src/main/java/com/huawei/it/ex/one/application/service/chat/InteractionEventFactory.java)、[ChatRunCompletionCoordinator](../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunCompletionCoordinator.java) |
| 候选回放及持久化屏障 | [CandidateSwitchRouteTraceService](../src/main/java/com/huawei/it/ex/one/application/service/chat/CandidateSwitchRouteTraceService.java)、[StandardRunRuntimeCoordinator](../src/main/java/com/huawei/it/ex/one/application/service/chat/StandardRunRuntimeCoordinator.java) |
| 异步结果事件 | [DomainAgentAsyncTaskCallbackCommitService](../src/main/java/com/huawei/it/ex/one/application/service/chat/DomainAgentAsyncTaskCallbackCommitService.java) |
| Stop 和 WAIT 取消差异 | [ChatRunStopCoordinator](../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStopCoordinator.java)、[ChatWaitingStopCommitService](../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatWaitingStopCommitService.java) |
| FULL/no-store 及 Parts | [AgentDataPersistenceEventPolicy](../src/main/java/com/huawei/it/ex/one/application/service/chat/AgentDataPersistenceEventPolicy.java)、[AssistantAssembly](../src/main/java/com/huawei/it/ex/one/application/service/chat/AssistantAssembly.java) |

验证范围：JSON 语法及封装/关联/游标一致性、OpenAPI 本地引用、文档链接、既有 WebSocket/流式封装与 Normalizer 定向测试、git diff --check。只验证后端契约和示例，不宣称真实浏览器、Jalor、openGauss、Redis Cluster 或下游 Agent 联调完成。

本次文档验证（2026-09-16）：58 个 JSON 块、167 条完整 WS 消息通过语法和关联/顺序检查；6 个 OpenAPI WS 命名示例与手册一致，107 个唯一 Schema/Example 本地引用及 43 个文件/锚点链接有效。现有前端文档 75 个 JSON 示例可解析。8 个 JDK 21 定向测试套件共 193 项通过，无失败、错误或跳过；另用实际编译的 DomainAgent/Relay Normalizer 对 25 个代表性 payload 做逐字段等值核对。临时验证脚本不纳入仓库，未新增测试插件或运行时逻辑。
