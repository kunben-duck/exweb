# ChatService System 错误日志编码规范

版本：`v1.0`

适用范围：ChatService 内部组件、基础设施和外部依赖发生技术异常时的日志错误编码、结构化字段、日志级别与根因记录规则。

---

## 1. 规范目标

本规范用于统一 ChatService 技术错误日志，保证错误可以被稳定检索、聚合、告警和追踪。

本规范只约束日志，不改变 API、WebSocket、`run.failed` 或 DomainAgent 控制事件的响应协议。DomainAgent 返回的拒答、澄清、审批与异常编码遵循 [DomainAgent 控制事件规范](domain-agent-control-contract.md)。

以下场景不使用本规范的技术错误编码：

```text
正常业务拒答
参数校验失败
权限不足或审批未通过
用户澄清和确认
资源不存在等预期业务分支
正常 WebSocket 关闭
主动取消且取消结果符合预期
```

---

## 2. 编码格式

ChatService 技术错误日志只使用 `SYS` 类型：

```text
FN-EX-CHAT-SYS-{来源对象}-{NNN}
```

完整编码必须满足：

```text
^FN-EX-CHAT-SYS-[A-Z0-9]{2,4}-[0-9]{3}$
```

字段说明：

| 段位 | 固定值或示例 | 含义 |
| --- | --- | --- |
| 固定前缀 | `FN` | FinanceEX 错误编码固定前缀 |
| 应用简称 | `EX` | 应用简称 |
| 模块简称 | `CHAT` | ChatService 模块标准简称 |
| 错误码类型 | `SYS` | 技术错误日志 |
| 来源对象 | `DBS`、`RED`、`RLY` | 技术故障实际发生边界 |
| 码号 | `001` | 来源对象内的三位稳定编号 |

`CHAT` 是唯一标准模块简称，历史拼写 `CAHT` 不得用于新增日志编码。技术日志属于服务内部观测数据，不需要为 `CAHT` 新增双写编码。

---

## 3. 来源对象

来源对象表示技术故障实际发生的边界，不表示打印日志的 Java 类或调用链最外层类。

| 来源对象 | 归属边界 | 示例 |
| --- | --- | --- |
| `SUP` | ChatService 主控、调度与未分类内部执行 | `FN-EX-CHAT-SYS-SUP-001` |
| `DBS` | 关系数据库、事务与持久化访问 | `FN-EX-CHAT-SYS-DBS-001` |
| `RED` | Redis 缓存、锁及 Pub/Sub | `FN-EX-CHAT-SYS-RED-001` |
| `WS` | ChatService 面向前端的 WebSocket 通道 | `FN-EX-CHAT-SYS-WS-001` |
| `RLY` | Relay Runtime 及其 WebSocket 通道 | `FN-EX-CHAT-SYS-RLY-001` |
| `SHR` | ChatService 分享编排模块 | `FN-EX-CHAT-SYS-SHR-001` |
| `WLK` | WeLink 分享发送服务 | `FN-EX-CHAT-SYS-WLK-001` |
| `DOC` | ChatService 文档编排与元数据处理 | `FN-EX-CHAT-SYS-DOC-001` |
| `APS` | API Store 文档上传服务 | `FN-EX-CHAT-SYS-APS-001` |
| `OBS` | 本地、S3 或华为 OBS 对象存储 provider | `FN-EX-CHAT-SYS-OBS-001` |
| `ITD` | IntentDecision 意图识别与路由服务 | `FN-EX-CHAT-SYS-ITD-001` |
| `DAG` | DomainAgent 调用、流式协议及执行 | `FN-EX-CHAT-SYS-DAG-001` |
| `MQS` | 下游问数能力 | `FN-EX-CHAT-SYS-MQS-001` |
| `MCP` | 下游 MCP 工具能力 | `FN-EX-CHAT-SYS-MCP-001` |
| `A2A` | 下游 Agent-to-Agent 能力 | `FN-EX-CHAT-SYS-A2A-001` |
| `LLM` | 下游大模型能力 | `FN-EX-CHAT-SYS-LLM-001` |
| `EDM` | 下游 EDM 文档能力 | `FN-EX-CHAT-SYS-EDM-001` |
| `LTM` | 下游长期记忆能力 | `FN-EX-CHAT-SYS-LTM-001` |

来源对象使用 2-4 位大写字母或数字。新增来源必须先登记来源对象并补齐码表，不得临时复用 `SUP` 或其他无关来源。

典型归属示例：

```text
分享结果写数据库失败：DBS，不是 SHR。
WeLink 返回 HTTP 5xx：WLK，不是 SHR。
分享编排在 provider 重试耗尽后最终失败：SHR，并通过 upstreamErrorCode 关联 WLK。
前端 WebSocket 发送失败：WS；Relay WebSocket 发送失败：RLY。
文档元数据处理失败：DOC；API Store 调用失败：APS；对象存储读写失败：OBS。
```

---

## 4. 编码分配规则

| 码号范围 | 用途 |
| --- | --- |
| `000` | 对应来源对象下无法继续分类的技术异常兜底 |
| `001-899` | 正式技术错误编码 |
| `900-999` | 专项、灰度或预留扩展编码 |

编码治理规则：

```text
errorCode 是日志唯一主编码，reasonCode 是与其一一对应的可读别名。
同一 errorCode 只能表达一种稳定的技术失败语义。
已发布编码不得改变语义、重新编号或分配给其他错误。
废弃编码只标记为 deprecated，不得复用。
无法识别来源时使用 SUP-000；来源已知但类型未知时使用该来源的 000。
新场景能够准确归入已有编码时不得重复新增同义编码。
```

---

## 5. 日志打印规则

### 5.1 日志级别

```text
可重试且尚未耗尽重试次数的技术失败通常记录 WARN。
重试耗尽、不可恢复、数据一致性受损或主流程最终失败记录 ERROR。
INFO 和 DEBUG 不得用于伪装应当告警的最终技术失败。
正常业务处理结果不得为了便于检索而提升为技术 WARN 或 ERROR。
```

码表中的“默认可重试”仅表示故障通常具有瞬态特征，用于日志和告警标签，不得直接驱动自动重试。自动重试还必须同时满足操作幂等、执行结果可判定和当前业务策略允许。

### 5.2 根因与堆栈

```text
同一异常链只由最终责任边界打印一次完整堆栈。
内部层只补充结构化上下文或继续抛出异常，不重复打印相同堆栈。
已识别的下游根因通过 upstreamErrorCode 关联。
聚合层记录最终失败时，不得把下游错误改写成不存在的本地根因。
不得仅根据异常 message 文本长期维持错误分类；应优先使用异常类型、HTTP 状态或稳定协议字段。
```

### 5.3 与对外编码的关系

现有 API、WebSocket 响应和 `run.failed` 短编码继续保持兼容，通过 `legacyCode` 关联。ChatService 自己识别出的 IntentDecision 和 DomainAgent 技术故障分别使用 `ITD` 和 `DAG`。

下游返回已登记的 `SYS-DAG/MQS/MCP/A2A/LLM/EDM/LTM` 编码时，可以直接作为日志主 `errorCode`。下游编码未知时，使用当前可确认来源的 `000` 编码，并把原始值写入 `upstreamErrorCode`；不得让未知字符串成为主编码。

ChatService 收到 `agent.refusal`、`agent.input_required` 或 `agent.approval_required` 等 `BIZ/VAL/AUTH/DATA/SAFE` 控制结果时，不记录技术错误日志。收到 `agent.error` 后，仅在该异常导致 ChatService 当前处理失败时记录技术日志。

---

## 6. 结构化字段

技术错误日志至少包含：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `errorCode` | 是 | 本规范定义的稳定技术错误编码 |
| `reasonCode` | 是 | 与 `errorCode` 一一对应的可读别名 |
| `message` | 是 | 稳定、简短且不包含敏感信息的错误摘要 |
| `component` | 是 | 打印日志的服务或组件，当前固定为 `chatservice` |
| `origin` | 是 | 与编码来源对象段一致，例如 `DBS`、`RED`、`RLY` |
| `retryable` | 是 | 本次失败在当前执行策略下是否仍可重试 |
| `traceId` | 否 | 链路追踪 ID |
| `runId` | 否 | Chat run ID |
| `sessionId` | 否 | Chat session ID |
| `operation` | 否 | 失败操作，例如 `relay.connect` 或 `document.upload` |
| `durationMs` | 否 | 失败前已执行时长 |
| `legacyCode` | 否 | 现有 API、WebSocket 或 `run.failed` 短编码 |
| `upstreamErrorCode` | 否 | 下游返回的原始稳定错误编码 |
| `exceptionClass` | 否 | 异常类型，不包含异常参数 |

示例：

```json
{
  "level": "ERROR",
  "message": "Relay config handshake timed out",
  "errorCode": "FN-EX-CHAT-SYS-RLY-003",
  "reasonCode": "RELAY_CONFIG_TIMEOUT",
  "component": "chatservice",
  "origin": "RLY",
  "retryable": false,
  "traceId": "trace_xxx",
  "runId": "run_xxx",
  "sessionId": "session_xxx",
  "operation": "relay.configure",
  "durationMs": 30000,
  "legacyCode": "RELAY_WS_CONFIG_TIMEOUT",
  "exceptionClass": "java.util.concurrent.TimeoutException"
}
```

不得记录 Cookie、Authorization、token、secret、password、credential、apiKey、accessKey、完整用户输入、完整文档内容或未经脱敏的下游响应。

---

## 7. ChatService 主控类 SUP

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-SUP-000` | `UNKNOWN_SYSTEM_ERROR` | false | 无法识别来源或类型的技术异常兜底。 |
| `FN-EX-CHAT-SYS-SUP-001` | `INTERNAL_EXECUTION_FAILED` | false | ChatService 内部执行发生未分类异常。 |
| `FN-EX-CHAT-SYS-SUP-002` | `CONFIGURATION_INVALID` | false | 必需配置缺失、格式错误或组合不合法。 |
| `FN-EX-CHAT-SYS-SUP-003` | `SERIALIZATION_FAILED` | false | ChatService 内部对象序列化失败。 |
| `FN-EX-CHAT-SYS-SUP-004` | `DESERIALIZATION_FAILED` | false | ChatService 内部对象反序列化失败。 |
| `FN-EX-CHAT-SYS-SUP-005` | `TASK_REJECTED` | true | 异步任务、调度任务或执行器拒绝提交。 |
| `FN-EX-CHAT-SYS-SUP-006` | `OPERATION_TIMEOUT` | true | ChatService 内部操作超过配置时限。 |
| `FN-EX-CHAT-SYS-SUP-007` | `RESOURCE_EXHAUSTED` | true | 本地线程、队列、许可或内存保护阈值耗尽。 |

---

## 8. 数据库与持久化类 DBS

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-DBS-000` | `DATABASE_ERROR` | false | 无法进一步分类的数据库技术异常。 |
| `FN-EX-CHAT-SYS-DBS-001` | `DATABASE_UNAVAILABLE` | true | 数据库不可用或连接建立失败。 |
| `FN-EX-CHAT-SYS-DBS-002` | `DATABASE_CONNECTION_TIMEOUT` | true | 获取或建立数据库连接超时。 |
| `FN-EX-CHAT-SYS-DBS-003` | `DATABASE_QUERY_TIMEOUT` | true | 数据库查询或更新执行超时。 |
| `FN-EX-CHAT-SYS-DBS-004` | `DATABASE_READ_FAILED` | true | 数据库读取失败。 |
| `FN-EX-CHAT-SYS-DBS-005` | `DATABASE_WRITE_FAILED` | true | 数据库新增、更新或删除失败。 |
| `FN-EX-CHAT-SYS-DBS-006` | `DATABASE_TRANSACTION_FAILED` | false | 事务提交、回滚或事务边界执行失败。 |
| `FN-EX-CHAT-SYS-DBS-007` | `DATABASE_CONSTRAINT_VIOLATION` | false | 非预期数据库约束冲突。 |
| `FN-EX-CHAT-SYS-DBS-008` | `DATABASE_SCHEMA_MISMATCH` | false | 数据库表、索引或字段与应用版本不匹配。 |
| `FN-EX-CHAT-SYS-DBS-009` | `DATABASE_CONNECTION_POOL_EXHAUSTED` | true | 数据库连接池没有可用连接。 |

业务唯一性冲突、乐观并发失败或数据不存在如属于预期业务分支，不使用 `DBS` 技术错误编码。

---

## 9. Redis 类 RED

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-RED-000` | `REDIS_ERROR` | false | 无法进一步分类的 Redis 技术异常。 |
| `FN-EX-CHAT-SYS-RED-001` | `REDIS_UNAVAILABLE` | true | Redis 不可用或连接失败。 |
| `FN-EX-CHAT-SYS-RED-002` | `REDIS_COMMAND_TIMEOUT` | true | Redis 命令执行超时。 |
| `FN-EX-CHAT-SYS-RED-003` | `REDIS_READ_FAILED` | true | Redis 缓存或状态读取失败。 |
| `FN-EX-CHAT-SYS-RED-004` | `REDIS_WRITE_FAILED` | true | Redis 缓存或状态写入失败。 |
| `FN-EX-CHAT-SYS-RED-005` | `REDIS_SERIALIZATION_FAILED` | false | 写入 Redis 前序列化失败。 |
| `FN-EX-CHAT-SYS-RED-006` | `REDIS_DESERIALIZATION_FAILED` | false | Redis 数据反序列化失败。 |
| `FN-EX-CHAT-SYS-RED-007` | `REDIS_PUBLISH_FAILED` | true | Redis Pub/Sub 消息发布失败。 |
| `FN-EX-CHAT-SYS-RED-008` | `REDIS_SUBSCRIBE_FAILED` | true | Redis Pub/Sub 订阅或恢复订阅失败。 |
| `FN-EX-CHAT-SYS-RED-009` | `REDIS_LOCK_FAILED` | true | Redis 分布式锁获取、续期或释放失败。 |
| `FN-EX-CHAT-SYS-RED-010` | `REDIS_CACHE_SYNC_FAILED` | true | 数据库提交后 Redis 热缓存同步失败。 |

---

## 10. 前端 WebSocket 类 WS

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-WS-000` | `WEBSOCKET_ERROR` | false | 无法进一步分类的 WebSocket 技术异常。 |
| `FN-EX-CHAT-SYS-WS-001` | `WEBSOCKET_HANDSHAKE_FAILED` | true | WebSocket Upgrade 或握手流程技术失败。 |
| `FN-EX-CHAT-SYS-WS-002` | `WEBSOCKET_MESSAGE_PARSE_FAILED` | false | 合法协议帧解析失败；客户端非法输入不使用该编码。 |
| `FN-EX-CHAT-SYS-WS-003` | `WEBSOCKET_SERIALIZATION_FAILED` | false | WebSocket 出站消息序列化失败。 |
| `FN-EX-CHAT-SYS-WS-004` | `WEBSOCKET_SEND_FAILED` | true | WebSocket 异步或阻塞发送失败。 |
| `FN-EX-CHAT-SYS-WS-005` | `WEBSOCKET_OUTBOUND_OVERFLOW` | true | 出站队列数量或字节数超过保护阈值。 |
| `FN-EX-CHAT-SYS-WS-006` | `WEBSOCKET_EXECUTOR_REJECTED` | true | WebSocket 发送执行器拒绝任务。 |
| `FN-EX-CHAT-SYS-WS-007` | `WEBSOCKET_TRANSPORT_ERROR` | true | WebSocket 底层传输异常。 |
| `FN-EX-CHAT-SYS-WS-008` | `WEBSOCKET_UNEXPECTED_CLOSED` | true | 未到达协议终态时连接异常关闭。 |
| `FN-EX-CHAT-SYS-WS-009` | `WEBSOCKET_SEQUENCE_MISMATCH` | true | 事件序列回退、跳号或 run/session 身份不一致。 |
| `FN-EX-CHAT-SYS-WS-010` | `WEBSOCKET_RECOVERY_FAILED` | true | Event Resume 或实时订阅恢复失败。 |

用户未认证、Origin 被拒绝、消息过大、命令不支持以及正常空闲关闭属于协议或安全处理结果，不使用 `SYS-WS` 技术错误编码。

---

## 11. Relay Runtime 类 RLY

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-RLY-000` | `RELAY_ERROR` | false | 无法进一步分类的 Relay 技术异常。 |
| `FN-EX-CHAT-SYS-RLY-001` | `RELAY_UNAVAILABLE` | true | Relay 服务不可用或连接失败。 |
| `FN-EX-CHAT-SYS-RLY-002` | `RELAY_CONNECT_TIMEOUT` | true | Relay WebSocket opening handshake 超时。 |
| `FN-EX-CHAT-SYS-RLY-003` | `RELAY_CONFIG_TIMEOUT` | true | Relay config 到 session-ready 阶段超时。 |
| `FN-EX-CHAT-SYS-RLY-004` | `RELAY_CONFIG_HANDSHAKE_FAILED` | false | Relay 配置握手返回错误或不兼容状态。 |
| `FN-EX-CHAT-SYS-RLY-005` | `RELAY_PROTOCOL_INVALID` | false | Relay 帧类型、状态或字段违反协议。 |
| `FN-EX-CHAT-SYS-RLY-006` | `RELAY_RESPONSE_PARSE_FAILED` | false | Relay 响应解析失败。 |
| `FN-EX-CHAT-SYS-RLY-007` | `RELAY_SESSION_UNAVAILABLE` | false | Relay session 不存在、损坏或无法恢复。 |
| `FN-EX-CHAT-SYS-RLY-008` | `RELAY_HEARTBEAT_TIMEOUT` | true | Relay 心跳响应超时。 |
| `FN-EX-CHAT-SYS-RLY-009` | `RELAY_RUN_TIMEOUT` | true | Relay 单次 run 超过最大运行时长。 |
| `FN-EX-CHAT-SYS-RLY-010` | `RELAY_INTERRUPT_FAILED` | true | Relay interrupt 发送或确认失败。 |
| `FN-EX-CHAT-SYS-RLY-011` | `RELAY_OUTBOUND_FAILED` | true | Relay WebSocket 出站消息发送失败。 |
| `FN-EX-CHAT-SYS-RLY-012` | `RELAY_UNEXPECTED_CLOSED` | true | Relay 未发送终态状态即关闭连接。 |

---

## 12. 分享服务类

### 12.1 分享编排 SHR

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-SHR-000` | `SHARE_ERROR` | false | 无法进一步分类的分享模块技术异常。 |
| `FN-EX-CHAT-SYS-SHR-001` | `SHARE_CONFIGURATION_INVALID` | false | 分享 URL、provider 或发送参数配置缺失。 |
| `FN-EX-CHAT-SYS-SHR-002` | `SHARE_PROVIDER_NOT_FOUND` | false | 配置的分享 provider 未注册。 |
| `FN-EX-CHAT-SYS-SHR-003` | `SHARE_DELIVERY_FAILED` | true | 分享发送编排最终失败。 |
| `FN-EX-CHAT-SYS-SHR-004` | `SHARE_PAYLOAD_SERIALIZATION_FAILED` | false | 分享快照、请求或响应摘要序列化失败。 |
| `FN-EX-CHAT-SYS-SHR-005` | `SHARE_EXECUTOR_REJECTED` | true | 分享发送执行器拒绝任务。 |

分享已撤销、已过期、无权访问或目标为空属于业务处理结果，不使用 `SYS-SHR` 技术错误编码。

### 12.2 WeLink WLK

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-WLK-000` | `WELINK_ERROR` | false | 无法进一步分类的 WeLink 技术异常。 |
| `FN-EX-CHAT-SYS-WLK-001` | `WELINK_UNAVAILABLE` | true | WeLink 服务不可用或连接失败。 |
| `FN-EX-CHAT-SYS-WLK-002` | `WELINK_TIMEOUT` | true | WeLink 调用超时。 |
| `FN-EX-CHAT-SYS-WLK-003` | `WELINK_HTTP_CLIENT_ERROR` | false | WeLink 返回除集成鉴权失败外的 HTTP 4xx。 |
| `FN-EX-CHAT-SYS-WLK-004` | `WELINK_HTTP_SERVER_ERROR` | true | WeLink 返回 HTTP 5xx。 |
| `FN-EX-CHAT-SYS-WLK-005` | `WELINK_EMPTY_RESPONSE` | true | WeLink 响应为空。 |
| `FN-EX-CHAT-SYS-WLK-006` | `WELINK_RESPONSE_INVALID` | false | WeLink 响应无法解析或缺少必需字段。 |
| `FN-EX-CHAT-SYS-WLK-007` | `WELINK_STATUS_FAILED` | false | HTTP 成功但 WeLink 返回未识别或不可处理的失败状态。 |
| `FN-EX-CHAT-SYS-WLK-008` | `WELINK_AUTH_FAILED` | false | WeLink 集成鉴权失败。 |

---

## 13. 文档服务类

### 13.1 文档编排 DOC

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-DOC-000` | `DOCUMENT_SERVICE_ERROR` | false | 无法进一步分类的文档模块技术异常。 |
| `FN-EX-CHAT-SYS-DOC-001` | `DOCUMENT_UPLOAD_FAILED` | true | 文档上传编排失败。 |
| `FN-EX-CHAT-SYS-DOC-002` | `DOCUMENT_DOWNLOAD_FAILED` | true | 文档下载或受控流输出编排失败。 |
| `FN-EX-CHAT-SYS-DOC-003` | `DOCUMENT_CONTENT_READ_FAILED` | true | ChatService 文档内容读取失败。 |
| `FN-EX-CHAT-SYS-DOC-004` | `DOCUMENT_CONTENT_WRITE_FAILED` | true | ChatService 文档内容写入失败。 |
| `FN-EX-CHAT-SYS-DOC-005` | `DOCUMENT_METADATA_SERIALIZATION_FAILED` | false | 文档元数据序列化失败。 |
| `FN-EX-CHAT-SYS-DOC-006` | `DOCUMENT_METADATA_DESERIALIZATION_FAILED` | false | 文档元数据反序列化失败。 |
| `FN-EX-CHAT-SYS-DOC-007` | `DOCUMENT_PROVIDER_ADAPTER_FAILED` | false | ChatService 文档 provider 适配或结果转换失败。 |
| `FN-EX-CHAT-SYS-DOC-008` | `DOCUMENT_URL_RESOLUTION_FAILED` | false | 文档 provider URL 定位符计算或解析失败。 |
| `FN-EX-CHAT-SYS-DOC-009` | `DOCUMENT_STREAM_FAILED` | true | 文档上传或下载流在传输过程中失败。 |

文档不存在、用户无权访问、文件为空、文件超限或文档业务状态不可用不使用 `SYS-DOC` 技术错误编码。已识别的 API Store 或对象存储根因分别使用 `SYS-APS` 或 `SYS-OBS`，文档编排最终失败日志通过 `upstreamErrorCode` 关联且不重复打印根因堆栈。

### 13.2 API Store APS

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-APS-000` | `API_STORE_ERROR` | false | 无法进一步分类的 API Store 技术异常。 |
| `FN-EX-CHAT-SYS-APS-001` | `API_STORE_UNAVAILABLE` | true | API Store 不可用或连接失败。 |
| `FN-EX-CHAT-SYS-APS-002` | `API_STORE_TIMEOUT` | true | API Store 调用超时。 |
| `FN-EX-CHAT-SYS-APS-003` | `API_STORE_HTTP_CLIENT_ERROR` | false | API Store 返回无法归入预期校验或鉴权结果的 HTTP 4xx。 |
| `FN-EX-CHAT-SYS-APS-004` | `API_STORE_HTTP_SERVER_ERROR` | true | API Store 返回 HTTP 5xx。 |
| `FN-EX-CHAT-SYS-APS-005` | `API_STORE_EMPTY_RESPONSE` | true | API Store 响应为空。 |
| `FN-EX-CHAT-SYS-APS-006` | `API_STORE_RESPONSE_INVALID` | false | API Store 响应无法解析或缺少必需字段。 |
| `FN-EX-CHAT-SYS-APS-007` | `API_STORE_STATUS_FAILED` | false | HTTP 成功但 API Store 返回无法作为业务结果处理的技术失败状态。 |
| `FN-EX-CHAT-SYS-APS-008` | `API_STORE_AUTH_FAILED` | false | API Store 服务集成凭据或认证转发机制异常。 |

用户登录态失效、权限不足、文件格式不支持、文件超限等可识别结果不使用 `SYS-APS` 技术错误编码。

### 13.3 对象存储 OBS

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-OBS-000` | `OBJECT_STORAGE_ERROR` | false | 无法进一步分类的对象存储技术异常。 |
| `FN-EX-CHAT-SYS-OBS-001` | `OBJECT_STORAGE_UNAVAILABLE` | true | 对象存储 provider 不可用或连接失败。 |
| `FN-EX-CHAT-SYS-OBS-002` | `OBJECT_STORAGE_WRITE_FAILED` | true | 对象写入失败。 |
| `FN-EX-CHAT-SYS-OBS-003` | `OBJECT_STORAGE_READ_FAILED` | true | 对象读取失败。 |
| `FN-EX-CHAT-SYS-OBS-004` | `OBJECT_STORAGE_DELETE_FAILED` | true | 对象删除失败。 |
| `FN-EX-CHAT-SYS-OBS-005` | `OBJECT_STORAGE_CONFIGURATION_INVALID` | false | 对象存储 endpoint、bucket 或凭据引用配置无效。 |
| `FN-EX-CHAT-SYS-OBS-006` | `OBJECT_STORAGE_PATH_RESOLUTION_FAILED` | false | 对象 key 或本地安全路径解析失败。 |
| `FN-EX-CHAT-SYS-OBS-007` | `OBJECT_STORAGE_TIMEOUT` | true | 对象存储调用超时。 |

---

## 14. 路由与 DomainAgent

### 14.1 IntentDecision ITD

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-ITD-000` | `INTENT_DECISION_ERROR` | false | 无法进一步分类的意图服务技术异常。 |
| `FN-EX-CHAT-SYS-ITD-001` | `INTENT_DECISION_UNAVAILABLE` | true | 意图服务不可用或连接失败。 |
| `FN-EX-CHAT-SYS-ITD-002` | `INTENT_DECISION_TIMEOUT` | true | 意图服务调用超时。 |
| `FN-EX-CHAT-SYS-ITD-003` | `INTENT_DECISION_RATE_LIMITED` | true | 意图服务限流。 |
| `FN-EX-CHAT-SYS-ITD-004` | `INTENT_DECISION_HTTP_CLIENT_ERROR` | false | 意图服务返回 HTTP 4xx。 |
| `FN-EX-CHAT-SYS-ITD-005` | `INTENT_DECISION_HTTP_SERVER_ERROR` | true | 意图服务返回 HTTP 5xx。 |
| `FN-EX-CHAT-SYS-ITD-006` | `INTENT_DECISION_EMPTY_RESPONSE` | true | 意图服务返回空响应。 |
| `FN-EX-CHAT-SYS-ITD-007` | `INTENT_DECISION_PROTOCOL_INVALID` | false | 意图响应缺少 routeAction、目标或其他必需协议字段。 |
| `FN-EX-CHAT-SYS-ITD-008` | `INTENT_DECISION_RESPONSE_PARSE_FAILED` | false | 意图响应 JSON 或映射解析失败。 |
| `FN-EX-CHAT-SYS-ITD-009` | `INTENT_DECISION_STATUS_FAILED` | false | HTTP 成功但意图服务返回非成功状态。 |
| `FN-EX-CHAT-SYS-ITD-010` | `INTENT_DECISION_STREAM_FAILED` | true | IntentAgent 事件流异常终止。 |

意图正常返回 `NO_MATCH/ROUTE_MULTI/CLARIFY` 不属于技术失败。可替换的本地重试策略自身抛错使用 `SUP`，不误记为 `ITD`。

### 14.2 DomainAgent DAG

| code | reasonCode | 默认可重试 | 场景 |
| --- | --- | --- | --- |
| `FN-EX-CHAT-SYS-DAG-000` | `DOMAIN_AGENT_ERROR` | false | 无法进一步分类的 DomainAgent 技术异常。 |
| `FN-EX-CHAT-SYS-DAG-001` | `AGENT_OVERLOADED` | true | DomainAgent 过载。 |
| `FN-EX-CHAT-SYS-DAG-002` | `DOMAIN_AGENT_TIMEOUT` | true | DomainAgent 调用或流式响应超时。 |
| `FN-EX-CHAT-SYS-DAG-003` | `DOMAIN_AGENT_RATE_LIMITED` | true | DomainAgent 限流。 |
| `FN-EX-CHAT-SYS-DAG-004` | `DOMAIN_AGENT_EXECUTION_FAILED` | true | DomainAgent 执行失败。 |
| `FN-EX-CHAT-SYS-DAG-005` | `PROTOCOL_INVALID` | false | DomainAgent 控制事件或响应协议不合法。 |
| `FN-EX-CHAT-SYS-DAG-006` | `RESPONSE_PARSE_FAILED` | false | DomainAgent 响应解析失败。 |
| `FN-EX-CHAT-SYS-DAG-007` | `DOMAIN_AGENT_UNAVAILABLE` | true | DomainAgent 服务不可用或连接失败。 |
| `FN-EX-CHAT-SYS-DAG-008` | `DOMAIN_AGENT_HTTP_CLIENT_ERROR` | false | DomainAgent 返回 HTTP 4xx。 |
| `FN-EX-CHAT-SYS-DAG-009` | `DOMAIN_AGENT_HTTP_SERVER_ERROR` | true | DomainAgent 返回 HTTP 5xx。 |
| `FN-EX-CHAT-SYS-DAG-010` | `DOMAIN_AGENT_STREAM_FAILED` | true | DomainAgent 响应流异常终止。 |
| `FN-EX-CHAT-SYS-DAG-011` | `DOMAIN_AGENT_CANCEL_FAILED` | true | DomainAgent stop/cancel 调用失败。 |
| `FN-EX-CHAT-SYS-DAG-012` | `DOMAIN_AGENT_UNEXPECTED_CLOSED` | true | DomainAgent 未到达协议终态即关闭连接。 |

`DAG-001...006` 属于既有稳定语义，不得重新编号。`agent.refusal` 属于业务控制事件，不因触发重新路由而记录为技术故障。

---

## 15. 已登记下游来源

这些来源码可由 DomainAgent 或后续协议直接返回。ChatService 只有在其导致当前技术处理失败时记录；已登记编码可作为主编码，未知编码回退到同来源 `000`。

| 来源 | `000` reasonCode | 已登记扩展 |
| --- | --- | --- |
| `MQS` | `MQS_ERROR` | `001 UNAVAILABLE`、`002 TIMEOUT`、`003 RATE_LIMITED`、`004 RESPONSE_INVALID` |
| `MCP` | `MCP_ERROR` | `001 UNAVAILABLE`、`002 TIMEOUT`、`003 TOOL_FAILED` |
| `A2A` | `A2A_ERROR` | `001 UNAVAILABLE`、`002 TIMEOUT`、`003 RESPONSE_INVALID` |
| `LLM` | `LLM_ERROR` | `001 UNAVAILABLE`、`002 TIMEOUT`、`003 CONTEXT_EXCEEDED` |
| `EDM` | `EDM_ERROR` | `001 UNAVAILABLE`、`002 TIMEOUT`、`003 DOCUMENT_NOT_FOUND`、`004 RESPONSE_INVALID` |
| `LTM` | `LTM_ERROR` | `001 UNAVAILABLE` |

示例：下游返回 `FN-EX-CHAT-SYS-LLM-002` 时，该值直接成为 `errorCode`；返回未知的 `VENDOR-42` 且只能确认是 DomainAgent 边界时，使用 `FN-EX-CHAT-SYS-DAG-000`，并设置 `upstreamErrorCode=VENDOR-42`。

本规范与 DomainAgent 控制事件规范共同构成 ChatService 可识别码表：本规范负责技术日志，控制事件规范负责拒答、澄清、审批等业务状态。二者不得互相替代。

---
