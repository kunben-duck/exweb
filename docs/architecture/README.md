# FinanceEXChatService 正式版架构设计

## 架构目标

FinanceEXChatService 是前端聊天入口和 SuperAgent 主控服务。正式版只保留清晰的执行边界：

- 简单任务：用例库或意图服务命中后，按 `agentCode` 单轮调用一个 SubAgent。
- 复杂任务：进入 Relay Runtime，并由 Relay Runtime 负责多轮、规划、上下文和压缩。
- SuperAgent：负责身份、会话、可选记忆上下文装配、路由、事件落库和 RuntimeBinding 续接。

## 全局流程图

```mermaid
flowchart TD
    User["用户请求"] --> Normalize["身份解析与会话归一化"]
    Normalize --> Memory["按配置加载 MemoryContext"]
    Memory --> ForceNew{"metadata.forceNewTask 为 true?"}
    ForceNew -- "是" --> CancelRuntime["取消 active RuntimeBinding"]
    ForceNew -- "否" --> FindRuntime["查询 RuntimeBinding"]
    CancelRuntime --> FindRuntime

    FindRuntime --> HasRuntime{"存在 active RuntimeBinding?"}
    HasRuntime -- "是" --> TouchRuntime["刷新 RuntimeBinding"]
    TouchRuntime --> RelayRuntime["Relay Runtime query"]

    HasRuntime -- "否" --> RouteSignal["RouteSignalApplicationService"]
    RouteSignal --> SignalEnabled{"用例库或意图服务已开启?"}
    SignalEnabled -- "否" --> CreateRuntime["创建 RuntimeBinding"]
    CreateRuntime --> RelayRuntime

    SignalEnabled -- "是" --> UseCaseEnabled{"用例库开启?"}
    UseCaseEnabled -- "是" --> UseCase["UseCaseLibraryClient.match"]
    UseCaseEnabled -- "否" --> IntentEnabled{"意图服务开启?"}
    UseCase --> UseCaseHit{"命中且分数达标且有 subAgentCode?"}
    UseCaseHit -- "是" --> SingleSubAgent["单轮 SubAgentClient.query"]
    UseCaseHit -- "否" --> IntentEnabled

    IntentEnabled -- "是" --> Intent["IntentService.recognize"]
    IntentEnabled -- "否" --> CreateRuntime
    Intent --> IntentRoute{"意图路由结果"}
    IntentRoute -- "简单任务且有 subAgentCode" --> SingleSubAgent
    IntentRoute -- "不支持任务" --> SystemResponse["SYSTEM_RESPONSE"]
    IntentRoute -- "复杂/低置信/无 subAgentCode" --> CreateRuntime

    SingleSubAgent --> EventStream["输出 ChatEvent 流"]
    RelayRuntime --> EventStream
    SystemResponse --> EventStream
    EventStream --> Persist["事件写入 fin_ex_chat_event_t"]
    Persist --> Publish["发布到 run stream topic"]
    Publish --> RuntimeObserve["观察 runtimeSessionId"]
    RuntimeObserve --> RuntimeCache["刷新 RuntimeBinding Redis 热缓存"]
```

## 全局顺序图

```mermaid
sequenceDiagram
    autonumber
    participant Frontend as "Frontend"
    participant API as "Chat API"
    participant SuperAgent as "FinanceEXChatService"
    participant Session as "SessionApplicationService"
    participant Memory as "MemoryApplicationService"
    participant Binding as "RuntimeBindingApplicationService"
    participant Signal as "RouteSignalApplicationService"
    participant UseCase as "UseCaseLibraryService"
    participant Intent as "IntentService"
    participant SubAgent as "SubAgent"
    participant Runtime as "Relay Runtime"
    participant Redis as "Redis"
    participant DB as "openGauss"
    participant EventStore as "ChatEventStore"

    Frontend->>API: "POST /chat/runs"
    API->>API: "AuthContextProvider.resolve()"
    API->>SuperAgent: "startRun(UserContext, command)"
    SuperAgent->>Session: "loadOrCreate(command)"
    Session->>DB: "读取或写入 fin_ex_chat_session_t"
    SuperAgent->>Memory: "loadForRun(command)"
    alt "短期或长期记忆开启"
        Memory->>Redis: "短期记忆优先读取最近问答缓存"
        Memory->>DB: "缓存 miss 时回源历史消息"
        Memory->>Memory: "长期记忆按 provider 检索"
    else "记忆全部关闭"
        Memory-->>SuperAgent: "返回空 MemoryContext"
    end

    alt "metadata.forceNewTask=true"
        SuperAgent->>Binding: "cancelActive(sessionId)"
        Binding->>DB: "写 RuntimeBinding=CANCELLED"
        Binding->>Redis: "删除 fin_ex:runtime_binding key"
    end

    SuperAgent->>Binding: "findActive(sessionId)"
    Binding->>Redis: "读取 RuntimeBinding"
    alt "Redis miss"
        Binding->>DB: "查询 fin_ex_runtime_binding_t"
        Binding->>Redis: "回填 active RuntimeBinding"
    end

    alt "存在 active RuntimeBinding"
        SuperAgent->>Binding: "touchForRun(runId)"
        Binding->>DB: "刷新 last_run_id 与 expires_at"
        Binding->>Redis: "刷新 RuntimeBinding TTL"
        SuperAgent->>Runtime: "query(runtimeSessionId, message)"
    else "不存在 active RuntimeBinding"
        SuperAgent->>Signal: "routeInitial(command, memory)"
        alt "用例库或意图服务命中简单任务"
            Signal->>UseCase: "可选 match"
            Signal->>Intent: "可选 recognize"
            Signal-->>SuperAgent: "SUB_AGENT(agentCode)"
            SuperAgent->>SubAgent: "query(AgentQueryRequest)"
        else "未命中/关闭/复杂任务"
            Signal-->>SuperAgent: "AGENT_RUNTIME"
            SuperAgent->>Binding: "create(runId)"
            Binding->>DB: "写入 fin_ex_runtime_binding_t"
            Binding->>Redis: "缓存 RuntimeBinding"
            SuperAgent->>Runtime: "query(message)"
        else "不支持任务"
            Signal-->>SuperAgent: "SYSTEM_RESPONSE"
            SuperAgent->>SuperAgent: "生成可控系统回复"
        end
    end

    loop "输出 ChatEvent"
        SubAgent-->>SuperAgent: "message.delta / message.completed"
        Runtime-->>SuperAgent: "message.delta / message.completed"
        SuperAgent->>EventStore: "append(event)"
        EventStore->>DB: "写入 fin_ex_chat_event_t"
        EventStore-->>SuperAgent: "返回持久化 seq"
        SuperAgent-->>Frontend: "WebSocket 实时事件"
        SuperAgent->>Binding: "observeEvent(runtimeSessionId)"
    end

    SuperAgent->>Session: "保存完整 assistant 消息"
    Session->>DB: "写入 fin_ex_chat_message_t"
```

## 文档库与附件使用

文档能力采用“统一后端入口 + 对象存储防腐层 + 文档库资产”的模式。

```mermaid
sequenceDiagram
    autonumber
    participant Frontend as "Frontend"
    participant DocAPI as "Document API"
    participant DocApp as "DocumentApplicationService"
    participant Storage as "ObjectStorage Port"
    participant HuaweiS3 as "Huawei OBS S3 Adapter"
    participant DB as "openGauss"
    participant Chat as "FinanceEXChatService"
    participant Runtime as "Relay Runtime"

    Frontend->>DocAPI: "POST /documents multipart file"
    DocAPI->>DocAPI: "AuthContextProvider.resolve()"
    DocAPI->>DocApp: "upload(UserContext, DocumentUploadCommand)"
    DocApp->>DocApp: "会话归属校验"
    DocApp->>Storage: "putObject(tenantId, file)"
    Storage->>HuaweiS3: "写入真实对象存储"
    HuaweiS3-->>Storage: "bucket/objectKey"
    DocApp->>DB: "写 fin_ex_uploaded_document_t"
    DocApp-->>Frontend: "UploadedDocument(id,status,source)"

    Frontend->>Chat: "聊天请求 attachments[{documentId}]"
    Chat->>DB: "回查 fin_ex_uploaded_document_t"
    Chat->>Chat: "校验归属、状态，补齐可信附件元数据"
    Chat->>Runtime: "AgentRuntimeRequest.attachments"
```

设计原则：

- 前端上传仍先进入 FinanceEXChatService，方便统一鉴权、审计、限流和企业网关接入。
- 真实文件内容通过 `ObjectStorage` port 写入对象存储；当前支持 local 和 huawei-s3 实现。
- 聊天请求只引用 `documentId`，不携带文件正文。
- Runtime 看到的是经过文档库回查后的可信附件元数据。
- `fin_ex_uploaded_document_t` 是文档库事实源，支持最近文档、库中文档选择和后续连接器文档扩展。

## 流式响应与断点恢复

正式版只保留后台 run 创建模式。`POST /chat/runs` 是唯一提问入口，
WebSocket 负责实时输出，SSE 只负责断线、刷新或复制页签后的缺失事件补发。

```text
POST /api/v1/ex/chat/runs
POST /api/v1/ex/chat/runs/{runId}/retry
POST /api/v1/ex/chat/messages/{messageId}/feedback
GET  /api/v1/ex/chat/sessions/{sessionId}/state?messageLimit=50
GET  /api/v1/ex/chat/sessions/{sessionId}/events/sse?afterSeq={lastSeq}
WS   /api/v1/ex/chat/ws subscribe(topicId=streamTopicId)
POST /api/v1/ex/chat/runs/{runId}/stop
```

`/chat/runs` 只返回 run 运行标识和 run 级 `streamTopicId`，不返回 WebSocket、SSE resume 或 stop URL。
这些 URL 属于前端 SDK、网关或部署配置，避免后端业务响应承担客户端路由配置职责。

```mermaid
sequenceDiagram
    autonumber
    participant Frontend as "Frontend"
    participant ChatAPI as "Chat API"
    participant SuperAgent as "FinanceEXChatService"
    participant EventStore as "ChatEventStore"
    participant RunStore as "ChatRunStore"
    participant Live as "LocalChatEventStreamRegistry"
    participant RedisBus as "Redis Pub/Sub"
    participant Runtime as "Relay Runtime"
    participant DB as "openGauss"

    Frontend->>ChatAPI: "POST /chat/runs"
    ChatAPI->>SuperAgent: "后台 start(command)"
    SuperAgent->>RunStore: "create RUNNING fin_ex_chat_run_t"
    SuperAgent->>EventStore: "append(run.started)"
    EventStore->>DB: "持久化 seq=firstSeq"
    EventStore-->>SuperAgent: "run.started(seq)"
    SuperAgent->>RunStore: "记录 firstSeq/lastSeq"
    SuperAgent->>Live: "publish(run.started)"
    ChatAPI-->>Frontend: "runId/sessionId/firstSeq/streamTopicId"

    Frontend->>ChatAPI: "WS subscribe(topicId=streamTopicId, afterSeq=firstSeq)"
    ChatAPI->>EventStore: "findByRunIdAndAfterSeq"
    EventStore->>DB: "补发 run 历史事件"
    ChatAPI->>Live: "订阅本机 run topic"
    ChatAPI->>RedisBus: "订阅远端 run topic"

    SuperAgent->>Runtime: "query"
    Runtime-->>SuperAgent: "message.delta"
    SuperAgent->>EventStore: "append(delta)"
    EventStore->>DB: "持久化 seq"
    SuperAgent->>RunStore: "刷新 lastSeq"
    SuperAgent->>Live: "publish(delta)"
    SuperAgent->>RedisBus: "publish(delta)"
    Live-->>Frontend: "WebSocket 实时事件"

    opt "用户点击停止"
        Frontend->>ChatAPI: "POST /chat/runs/{runId}/stop"
        ChatAPI->>RunStore: "RUNNING -> CANCELLING + cancel flag"
        ChatAPI->>Runtime: "best-effort cancel"
        ChatAPI->>EventStore: "append(run.cancelled)"
        EventStore->>DB: "持久化取消终态 seq"
        ChatAPI->>RunStore: "CANCELLED + evict active run"
        ChatAPI->>Live: "publish(run.cancelled)"
        ChatAPI-->>Frontend: "status=CANCELLED/latestSeq"
    end

    Frontend--xChatAPI: "浏览器刷新/断线"
    Frontend->>ChatAPI: "SSE resume afterSeq=lastReceivedSeq"
    ChatAPI->>DB: "补发缺失事件"
    ChatAPI-->>Frontend: "补发缺失事件"
```

关键约束：

- `fin_ex_chat_event_t.seq` 是前端恢复游标，实时输出和补发输出使用同一份 seq；该序号由 openGauss sequence/default 生成并随事件写入一起返回，应用层不再本地生成恢复游标。
- openGauss 是事件事实源，`LocalChatEventStreamRegistry` 是当前服务实例内在线发布器，Redis Pub/Sub 只做跨实例实时扇出。
- `fin_ex_chat_run_t` 是 run 生命周期事实源；Redis 只保存 active run 和 cancel flag。
- 后台 run 不依赖创建 run 的原始浏览器连接，刷新页面后用 `afterSeq` 恢复。
- WebSocket 订阅消息格式：`{"type":"subscribe","topicId":"chat-run-{runId}","afterSeq":0}`。
- WebSocket 不接受聊天请求；仅支持 `connect`、`presence`、`subscribe`、`unsubscribe`、`ack` 控制消息。
- stop 是 REST 生命周期接口，不是 WebSocket command；重复 stop 幂等返回当前 run 状态。
- retry 会创建新的 run，不覆盖旧 run 事件；message 为空时复用原会话最近一条用户消息。
- 会话 state 接口聚合会话元数据、最近历史消息和 `activeStreamTopicId`，用于前端切换会话后的恢复判断。

## 分层架构

```mermaid
flowchart TB
    subgraph Interfaces["interfaces"]
        ChatController["ChatController / WebSocket"]
        SessionController["ChatSessionController"]
        DocumentController["DocumentController"]
    end

    subgraph Application["application"]
        ChatService["FinanceEXChatService"]
        ChatRun["ChatRunApplicationService"]
        RouteSignal["RouteSignalApplicationService"]
        RuntimeBinding["RuntimeBindingApplicationService"]
        SubAgentExecutor["SubAgentExecutor"]
        RuntimeExecutor["AgentRuntimeExecutor"]
        StreamService["ChatStreamApplicationService"]
        DocumentService["DocumentApplicationService"]
        MemoryService["MemoryApplicationService"]
        SessionService["SessionApplicationService"]
    end

    subgraph Domain["domain"]
        ChatModel["ChatCommand / ChatEvent / ChatRun"]
        Routing["RouteTarget / RouteType"]
        RuntimeModel["RuntimeBinding"]
        DocumentModel["UploadedDocument / DocumentLibraryPage"]
        IntentModel["IntentDecision"]
        UseCaseModel["UseCaseMatchResult"]
    end

    subgraph Infrastructure["infrastructure"]
        Redis["Redis RuntimeBinding / ChatRun / Memory Cache"]
        OpenGauss["openGauss + MyBatis"]
        UseCaseHttp["UseCase HTTP Adapter"]
        IntentHttp["Intent HTTP Adapter"]
        SubAgentHttp["SubAgent HTTP Adapter"]
        RelayHttp["Relay Runtime HTTP Adapter"]
        Storage["Local / Huawei S3 ObjectStorage"]
    end

    Interfaces --> ChatService
    ChatService --> RouteSignal
    ChatService --> ChatRun
    ChatService --> RuntimeBinding
    ChatService --> SubAgentExecutor
    ChatService --> RuntimeExecutor
    ChatService --> StreamService
    ChatService --> DocumentService
    ChatService --> MemoryService
    ChatService --> SessionService
    RouteSignal --> UseCaseHttp
    RouteSignal --> IntentHttp
    ChatRun --> Redis
    ChatRun --> OpenGauss
    RuntimeBinding --> Redis
    RuntimeBinding --> OpenGauss
    SubAgentExecutor --> SubAgentHttp
    RuntimeExecutor --> RelayHttp
    DocumentService --> Storage
    Application --> Domain
```

## 路由规则

- active RuntimeBinding 优先级最高；存在时本轮直接续接 Relay Runtime。
- 用例库和意图服务是可选路由信号，默认关闭；关闭时不调用外部 API。
- 用例库开启时优先匹配；命中阈值默认 `0.85`，命中并返回 `subAgentCode` 后单轮调用 SubAgent。
- 用例库关闭或未命中后，只有意图服务开启才调用 `IntentService`。
- 意图服务返回简单任务、高置信且有 `candidateSubAgentCode` 时单轮调用 SubAgent。
- 两个信号均关闭、服务失败、复杂、低置信或缺少 SubAgent 的任务进入 Relay Runtime。
- SubAgent 没有续接机制；如果用户下一轮继续提问，除非已经进入 Relay Runtime，否则重新走路由信号。

## RuntimeBinding

RuntimeBinding 只维护前端 chat session 与当前 AgentRuntime provider session 的关系。当前上线默认 provider 是 `relay`。

```text
Redis key:
fin_ex:runtime_binding:{tenantId}:{userId}:{sessionId}

openGauss table:
fin_ex_runtime_binding_t
```

字段包括：

```text
id
tenant_id
user_id
chat_session_id
provider
runtime_session_id
status
last_run_id
expires_at
metadata_json
created_at
updated_at
```

## ChatRun 与 Stop

ChatRun 维护单轮回答生命周期，表为 `fin_ex_chat_run_t`，Redis key 为：

```text
fin_ex:chat_run:active:{tenantId}:{userId}:{sessionId}
fin_ex:chat_run:cancel:{runId}
fin_ex:chat_stream:{streamTopicId}
```

状态流转：

```mermaid
stateDiagram-v2
    [*] --> RUNNING
    RUNNING --> COMPLETED: "run.completed"
    RUNNING --> FAILED: "run.failed"
    RUNNING --> CANCELLING: "POST /runs/{runId}/stop"
    CANCELLING --> CANCELLED: "run.cancelled"
```

stop 语义：

- 集群事实源优先：stop 先写 Redis cancel flag 与 openGauss `CANCELLING` 状态，再发布 `run.cancelled`。
- JVM subscription registry 只是本机资源释放加速器；即使 stop 请求与输出流落在不同实例，输出实例也必须在追加事件前读取 Redis cancel flag，并周期性回源 `fin_ex_chat_run_t` 校验 run 状态。
- 下游尽力取消：Relay Runtime 和 SubAgent cancel 失败只记录日志，不影响前端收到取消终态。
- stop 不取消 RuntimeBinding；下一轮仍可续接 Runtime，除非请求 metadata 使用 `forceNewTask=true`。

## 外部 API 接入

- 用例库服务：`financeex.use-case-library.enabled`、`financeex.use-case-library.base-url`、`financeex.use-case-library.match-path`
- 意图服务：`financeex.intent.enabled`、`financeex.intent.base-url`、`financeex.intent.recognize-path`
- SubAgent：`financeex.sub-agent.agents.{agentCode}.endpoint`
- SubAgent stop：`financeex.sub-agent.agents.{agentCode}.stop-endpoint`
- AgentRuntime provider：`financeex.agent-runtime.provider`，表示 Runtime 类型，当前默认 `relay`
- AgentRuntime protocol：`financeex.agent-runtime.protocol`，表示 Relay 传输协议，默认 `http-streamable`，可选 `websocket`
- Relay HTTP Streamable Runtime：`financeex.agent-runtime.base-url`、`financeex.agent-runtime.stream-path`、`financeex.agent-runtime.stop-path`
- Relay WebSocket Runtime：设置 `financeex.agent-runtime.provider=relay`、`financeex.agent-runtime.protocol=websocket`，并配置 `financeex.agent-runtime.websocket-url` 或 `financeex.agent-runtime.websocket-path`

SubAgent 当前只支持单轮 HTTP 文本流调用。当前上线版本只内置 Relay Runtime adapter，其中 `provider=relay, protocol=http-streamable` 是 HTTP 流式协议实现，`provider=relay, protocol=websocket` 是 RelayAgent WebSocket 对话协议实现。

当前上线版本明确去掉 AgentScope 设计和实现，也不包含 AgentScope memory、AgentScope prompt assembler 或相关配置。复杂任务通过 Relay Runtime adapter 执行；项目内不再包含任何 AgentScope 架构分支。

AgentRuntime 防腐层仍然保留。应用层只依赖 `AgentRuntime` port 和 `AgentRuntimeRequest` 契约，当前 `relay` provider 是 Runtime 类型，协议由 `financeex.agent-runtime.protocol` 选择。后续如果替换 Runtime 实现，应新增一个实现 `AgentRuntime` 的 adapter，并通过 `financeex.agent-runtime.provider` 选择装配，避免改动 `FinanceEXChatService` 主编排。

## 命名规范

所有表名必须匹配：

```text
^fin_ex_.*_t$
```

当前表：

- `fin_ex_chat_session_t`
- `fin_ex_chat_message_t`
- `fin_ex_chat_run_t`
- `fin_ex_chat_event_t`
- `fin_ex_uploaded_document_t`
- `fin_ex_message_feedback_t`
- `fin_ex_runtime_binding_t`

Redis key 必须以 `fin_ex` 开头：

- `fin_ex:runtime_binding:{tenantId}:{userId}:{sessionId}`
- `fin_ex:chat_run:active:{tenantId}:{userId}:{sessionId}`
- `fin_ex:chat_run:cancel:{runId}`
- `fin_ex:chat_stream:{streamTopicId}`
- `fin_ex:memory:short_term:messages:{tenantId}:{userId}:{sessionId}`

## 可选记忆上下文

- `financeex.memory.short-term.enabled=false` 时，不装配最近问答，也不访问 Redis 短期记忆缓存。
- `financeex.memory.short-term.recent-turns=5` 表示短期记忆开启后读取最近 5 轮问答，即最多 10 条消息。
- `financeex.memory.short-term.cache-enabled=true` 表示短期记忆开启时优先使用 Redis 热缓存，miss 后回源 openGauss。
- `financeex.memory.long-term.enabled=false` 时，不调用长期记忆服务。
- `financeex.memory.long-term.provider=disabled` 是默认安全 provider，开启长期记忆但未接真实服务时返回空结果。
