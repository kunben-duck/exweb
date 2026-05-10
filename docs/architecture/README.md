# FinanceEXChatService 正式版架构设计

## 架构目标

FinanceEXChatService 是前端聊天入口和 SuperAgent 主控服务。正式版只保留清晰的执行边界：

- 简单任务：用例库或意图服务命中后，按 `agentCode` 单轮调用一个 SubAgent。
- 复杂任务：进入 Relay Runtime，并由 Relay Runtime 负责多轮、规划、上下文和压缩。
- SuperAgent：负责身份、会话、上下文装配、路由、事件落库和 RuntimeBinding 续接。

## 全局流程图

```mermaid
flowchart TD
    User["用户请求"] --> Normalize["身份解析与会话归一化"]
    Normalize --> Memory["加载 MemoryContext"]
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
    EventStream --> Persist["事件与消息写入 openGauss"]
    Persist --> RuntimeObserve["观察 runtimeSessionId"]
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

    Frontend->>API: "发送聊天请求"
    API->>SuperAgent: "chat(command)"
    SuperAgent->>SuperAgent: "AuthContextProvider.resolve()"
    SuperAgent->>Session: "loadOrCreate(command)"
    Session->>DB: "读取或写入 fin_ex_chat_session_t"
    SuperAgent->>Memory: "loadForRun(command)"
    Memory->>Redis: "读取短期消息与工作记忆"
    Memory->>DB: "必要时回源消息和摘要"

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
        SuperAgent->>Binding: "observeEvent(runtimeSessionId)"
        SuperAgent-->>API: "转发事件"
        API-->>Frontend: "SSE / NDJSON / WebSocket"
    end

    SuperAgent->>Session: "保存完整 assistant 消息"
    Session->>DB: "写入 fin_ex_chat_message_t"
```

## 分层架构

```mermaid
flowchart TB
    subgraph Interfaces["interfaces"]
        ChatController["ChatController / WebSocket"]
        SessionController["ChatSessionController"]
        DocumentController["DocumentUploadController"]
    end

    subgraph Application["application"]
        ChatService["FinanceEXChatService"]
        RouteSignal["RouteSignalApplicationService"]
        RuntimeBinding["RuntimeBindingApplicationService"]
        SubAgentExecutor["SubAgentExecutor"]
        RuntimeExecutor["AgentRuntimeExecutor"]
        MemoryService["MemoryApplicationService"]
        SessionService["SessionApplicationService"]
    end

    subgraph Domain["domain"]
        ChatModel["ChatCommand / ChatEvent"]
        Routing["RouteTarget / RouteType"]
        RuntimeModel["RuntimeBinding"]
        IntentModel["IntentDecision"]
        UseCaseModel["UseCaseMatchResult"]
    end

    subgraph Infrastructure["infrastructure"]
        Redis["Redis RuntimeBinding / Memory Cache"]
        OpenGauss["openGauss + MyBatis"]
        UseCaseHttp["UseCase HTTP Adapter"]
        IntentHttp["Intent HTTP Adapter"]
        SubAgentHttp["SubAgent HTTP Adapter"]
        RelayHttp["Relay Runtime HTTP Adapter"]
        Storage["Local / S3 ObjectStorage"]
    end

    Interfaces --> ChatService
    ChatService --> RouteSignal
    ChatService --> RuntimeBinding
    ChatService --> SubAgentExecutor
    ChatService --> RuntimeExecutor
    ChatService --> MemoryService
    ChatService --> SessionService
    RouteSignal --> UseCaseHttp
    RouteSignal --> IntentHttp
    RuntimeBinding --> Redis
    RuntimeBinding --> OpenGauss
    SubAgentExecutor --> SubAgentHttp
    RuntimeExecutor --> RelayHttp
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

RuntimeBinding 只维护前端 chat session 与 Relay Runtime session 的关系。

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

## 外部 API 接入

- 用例库服务：`financeex.use-case-library.enabled`、`financeex.use-case-library.base-url`、`financeex.use-case-library.match-path`
- 意图服务：`financeex.intent.enabled`、`financeex.intent.base-url`、`financeex.intent.recognize-path`
- SubAgent：`financeex.sub-agent.agents.{agentCode}.endpoint`
- Relay Runtime：`financeex.agent-runtime.base-url`、`financeex.agent-runtime.stream-path`

SubAgent 当前只支持单轮 HTTP 文本流调用。Relay Runtime 是唯一 Runtime 实现，项目内不再包含其他进程内 Runtime 实现。

## 命名规范

所有表名必须匹配：

```text
^fin_ex_.*_t$
```

当前表：

- `fin_ex_chat_session_t`
- `fin_ex_chat_message_t`
- `fin_ex_chat_event_t`
- `fin_ex_conversation_summary_t`
- `fin_ex_uploaded_document_t`
- `fin_ex_runtime_binding_t`

Redis key 必须以 `fin_ex` 开头：

- `fin_ex:runtime_binding:{tenantId}:{userId}:{sessionId}`
- `fin_ex:memory:short_term:messages:{tenantId}:{userId}:{sessionId}`
- `fin_ex:memory:working:variables:{sessionId}`
