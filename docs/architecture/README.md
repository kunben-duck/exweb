# FinanceEXChatService 系统架构设计

本文档基于当前代码实现整理，描述系统分层、核心链路、记忆体系、Agent 路由和工具调用边界。

## 架构目标

- 面向前端提供统一聊天接入协议，支持 SSE、HTTP NDJSON Stream 和 WebSocket。
- 使用 `tenantId + userId + sessionId` 作为会话和记忆隔离主键。
- application 层只编排业务流程，不直接依赖 AgentScope、Redis、PostgreSQL 或第三方 Runtime。
- AgentScope 作为本地 Agent 引擎实现，Relay Runtime 作为复杂任务外部执行入口。
- 工具调用统一经过应用层网关，集中完成权限、确认、实现选择和审计。
- 短期记忆支持 Redis 热缓存和 PostgreSQL 事实源，长期记忆预留外部服务扩展点。

## 系统上下文

```mermaid
flowchart LR
    Frontend["前端 Web / IM / WebSocket 客户端"]
    Service["FinanceEXChatService"]
    AgentScope["AgentScope Local Agent"]
    Runtime["Relay Agent Runtime"]
    Redis["Redis 短期记忆缓存"]
    PG["PostgreSQL 短期记忆事实源"]
    MemorySvc["外部长期记忆服务"]
    ToolProvider["第三方工具 / 内部工具服务"]
    ObjectStore["本地对象存储"]

    Frontend -->|"SSE / NDJSON / WebSocket"| Service
    Service -->|"简单任务"| AgentScope
    Service -->|"复杂任务"| Runtime
    Service -->|"读写短期记忆"| Redis
    Service -->|"Redis miss / 持久化"| PG
    Service -->|"长期记忆检索 / 记录"| MemorySvc
    AgentScope -->|"统一工具桥"| Service
    Runtime -->|"内部工具调用接口"| Service
    Service -->|"工具执行"| ToolProvider
    Service -->|"文档上传"| ObjectStore
```

## 分层架构

```mermaid
flowchart TB
    subgraph Interfaces["interfaces 接口层"]
        ChatController["ChatController"]
        ChatWS["ChatWebSocketHandler"]
        SessionController["ChatSessionController"]
        ToolController["ToolInvocationController"]
        InternalToolController["InternalToolInvocationController"]
        DocumentController["DocumentUploadController"]
    end

    subgraph Application["application 应用层"]
        Facade["facade 对外用例门面"]
        ChatService["FinanceEXChatService 主编排"]
        SessionService["SessionApplicationService"]
        MemoryService["MemoryApplicationService"]
        LocalExecutor["LocalAgentExecutor"]
        RuntimeRelay["RuntimeRelayService"]
        ToolGateway["ToolGatewayApplicationService"]
        Gateway["gateway 抽象接口"]
    end

    subgraph Domain["domain 领域层"]
        ChatModel["ChatCommand / ChatEvent / ChatSession"]
        MemoryModel["MemoryContext / LongTermMemoryItem"]
        Routing["RoutingPolicy / RouteTarget"]
        ToolModel["ToolDefinition / ToolInvocationEvent"]
    end

    subgraph Infrastructure["infrastructure 基础设施层"]
        AgentScopeImpl["AgentScopeAgentEngine"]
        RuntimeClient["RelayAgentHttpStreamClient / MockRuntime"]
        RedisMemory["RedisShortTermMemoryCache"]
        MyBatisStore["MyBatisChatMessageStore"]
        PGMapper["ChatMessageMapper"]
        LTM["MockExternalLongTermMemoryStore"]
        ToolInvoker["MockThirdPartyToolInvoker"]
        Security["HeaderAuthContextProvider"]
        Storage["LocalObjectStorage"]
    end

    Interfaces --> Facade
    Facade --> ChatService
    ChatService --> SessionService
    ChatService --> MemoryService
    ChatService --> LocalExecutor
    ChatService --> RuntimeRelay
    ChatService --> Routing
    ToolController --> ToolGateway
    InternalToolController --> ToolGateway
    Application --> Gateway
    Application --> Domain
    Infrastructure --> Gateway
    AgentScopeImpl --> ToolGateway
    RedisMemory --> MyBatisStore
    MyBatisStore --> PGMapper
```

## 包职责

| 包 | 职责 |
| --- | --- |
| `interfaces` | HTTP、SSE、WebSocket、内部工具接口和 DTO 翻译 |
| `application.facade` | 前端或外部调用看到的应用门面 |
| `application.service` | 会话、记忆、路由、Agent、Runtime、工具调用编排 |
| `application.gateway` | 应用层依赖的外部能力抽象 |
| `domain` | 聊天、会话、记忆、路由、工具等核心模型和策略 |
| `infrastructure.agent.agentscope` | AgentScope Java SDK 适配 |
| `infrastructure.memory` | 短期记忆、长期记忆、摘要和工作记忆实现 |
| `infrastructure.runtime` | Relay Runtime HTTP Stream 和 mock 实现 |
| `infrastructure.tool` | 工具目录、工具调用和审计 mock 实现 |
| `infrastructure.security` | 当前 Header 身份解析实现 |
| `infrastructure.storage` | 文档对象存储和文档元数据实现 |

## 前端聊天请求流程

```mermaid
sequenceDiagram
    autonumber
    participant FE as 前端
    participant API as 接口层
    participant Chat as FinanceEXChatService
    participant Auth as AuthContextProvider
    participant Session as SessionApplicationService
    participant Memory as MemoryApplicationService
    participant Intent as IntentRecognizer
    participant Router as RoutingPolicy
    participant Local as LocalAgentExecutor
    participant Relay as RuntimeRelayService
    participant Store as ChatEventStore

    FE->>API: 发送 FrontChatRequest
    API->>Chat: 转换为 ChatCommand
    Chat->>Auth: 解析 tenantId / userId
    Chat->>Session: 加载或创建 ChatSession
    Chat->>Memory: 装配 MemoryContext
    Memory-->>Chat: 最近消息 / 摘要 / 工作变量 / 长期记忆
    Chat->>Intent: 识别意图复杂度
    Chat->>Router: 决定本地 Agent / Relay / 澄清 / 拒绝
    Chat-->>FE: run.started

    alt LOCAL_AGENT
        Chat->>Local: 执行本地 Agent
        Local-->>Chat: message.delta / message.completed
    else RELAY_AGENT
        Chat->>Relay: 转发 RuntimeRequest
        Relay-->>Chat: message.delta / message.completed
    else ASK_CLARIFICATION
        Chat-->>FE: 补充信息提示
    else REJECT
        Chat-->>FE: run.failed
    end

    Chat->>Store: 逐条追加 ChatEvent
    Chat-->>FE: run.completed
```

## 本地 Agent 执行流程

```mermaid
sequenceDiagram
    autonumber
    participant Chat as FinanceEXChatService
    participant Local as LocalAgentExecutor
    participant Catalog as ToolCatalogApplicationService
    participant Router as AgentEngineRouter
    participant Agent as AgentScopeAgentEngine
    participant MemoryFactory as AgentScopeMemoryFactory
    participant ToolGateway as ToolGatewayApplicationService
    participant LLM as OpenAI 兼容模型网关

    Chat->>Local: ChatCommand + MemoryContext + IntentDecision
    Local->>Catalog: 查询当前用户可见工具
    Local->>Router: AgentRunRequest
    Router->>Agent: 选择 AgentScope 引擎
    Agent->>MemoryFactory: 创建短期记忆和长期记忆适配器
    Agent->>Agent: 构建 ReActAgent / Toolkit / Prompt
    Agent->>LLM: call 或 stream
    Agent->>ToolGateway: 工具调用统一桥接
    ToolGateway-->>Agent: 工具事件 / 工具结果
    Agent-->>Chat: ChatEvent 流
```

## Relay Runtime 流程

```mermaid
flowchart TD
    Complex["复杂任务或非 simpleTask"]
    RelayService["RuntimeRelayService"]
    Request["RuntimeRequest"]
    ClientSelect["按 RuntimeProtocol 选择 AgentRuntimeClient"]
    MockRuntime["MockRelayAgentRuntimeClient"]
    HttpRuntime["RelayAgentHttpStreamClient"]
    InternalTool["InternalToolInvocationController"]
    ToolGateway["ToolGatewayApplicationService"]
    ThirdTool["第三方工具 / 内部工具"]

    Complex --> RelayService
    RelayService --> Request
    Request --> ClientSelect
    ClientSelect --> MockRuntime
    ClientSelect --> HttpRuntime
    HttpRuntime -->|"Runtime 需要工具时回调"| InternalTool
    InternalTool --> ToolGateway
    ToolGateway --> ThirdTool
```

## 记忆体系

```mermaid
flowchart LR
    ChatRun["一次 Chat Run"]
    MemoryService["MemoryApplicationService"]
    Recent["最近 20 条短期消息"]
    Summary["ConversationSummary 最新摘要"]
    Working["WorkingMemoryStore 工作变量"]
    LTM["LongTermMemoryStore 长期记忆"]
    Redis["RedisShortTermMemoryCache"]
    PG["PostgreSQL chat_message"]
    MyBatis["MyBatisChatMessageStore + ChatMessageMapper"]
    External["外部长期记忆服务 mock"]
    AgentMemory["FinanceAgentScopeMemory"]
    AgentLTM["FinanceAgentScopeLongTermMemory"]

    ChatRun --> MemoryService
    MemoryService --> Recent
    MemoryService --> Summary
    MemoryService --> Working
    MemoryService --> LTM
    Recent --> Redis
    Redis -->|"命中"| Recent
    Redis -->|"为空 / 过期 / 不可用"| MyBatis
    MyBatis --> PG
    MyBatis -->|"回填缓存"| Redis
    LTM --> External
    MemoryService --> AgentMemory
    MemoryService --> AgentLTM
```

### 当前存储实现状态

| 数据 | 当前实现 | 说明 |
| --- | --- | --- |
| 聊天消息 / 短期记忆 | Redis + PostgreSQL + MyBatis | Redis 是热缓存，PostgreSQL 是事实源 |
| 会话元数据 | `InMemorySessionRepository` | 当前版本内存实现，后续可替换为 PostgreSQL |
| 运行事件 | `InMemoryChatEventStore` | 当前版本内存实现，后续可替换为 PostgreSQL 或消息日志 |
| 会话摘要 | `InMemorySummaryRepository` | 目前只有摘要读取占位，未实现自动压缩 |
| 工作记忆变量 | `InMemoryWorkingMemoryStore` | 保存轻量运行变量，例如最近一次 runId |
| 长期记忆 | `MockExternalLongTermMemoryStore` | 外部长期记忆服务占位实现，当前不返回假记忆 |

## 短期记忆读写流程

```mermaid
sequenceDiagram
    autonumber
    participant Agent as AgentScope / Relay / 应用编排
    participant Repo as LayeredChatMessageRepository
    participant Redis as RedisShortTermMemoryCache
    participant DB as MyBatisChatMessageStore
    participant PG as PostgreSQL

    Agent->>Repo: save(ChatMessage)
    Repo->>Redis: append(message)
    Repo->>DB: save(message)
    DB->>PG: INSERT chat_message

    Agent->>Repo: findRecentMessages(tenantId, userId, sessionId, limit)
    Repo->>Redis: 查询最近消息
    alt Redis 命中
        Redis-->>Repo: 返回最近消息
    else Redis 为空 / 过期 / 不可用
        Repo->>DB: 查询最近消息
        DB->>PG: SELECT chat_message
        DB-->>Repo: 返回数据库消息
        Repo->>Redis: replaceSessionMessages 预热缓存
    end
    Repo-->>Agent: 返回最近消息
```

## 工具调用边界

```mermaid
flowchart TD
    Caller["前端 / AgentScope / Relay Runtime"]
    ToolFacade["ToolInvokeFacade"]
    Gateway["ToolGatewayApplicationService"]
    Auth["AuthContextProvider"]
    Catalog["ToolCatalogApplicationService"]
    Permission["PermissionChecker"]
    Confirm["确认事件"]
    Invoker["ToolInvoker 实现"]
    Audit["ToolAuditRecorder"]
    Events["ToolInvocationEvent 流"]

    Caller --> ToolFacade
    ToolFacade --> Gateway
    Gateway --> Auth
    Gateway --> Catalog
    Gateway --> Permission
    Permission -->|"需要确认且未确认"| Confirm
    Permission -->|"允许执行"| Invoker
    Invoker --> Events
    Events --> Audit
```

工具调用规则：

- 所有工具调用必须进入 `ToolGatewayApplicationService`。
- AgentScope 通过 `AgentScopeToolBridge` 调用工具。
- Relay Runtime 通过内部接口 `InternalToolInvocationController` 调用工具。
- 高风险或需要确认的工具先返回 `ToolConfirmationRequiredEvent`。
- 审计跟随工具事件流记录，便于还原执行过程。

## 会话隔离设计

```mermaid
flowchart LR
    Headers["X-Tenant-Id / X-User-Id"]
    Auth["HeaderAuthContextProvider"]
    UserContext["UserContext"]
    Session["ChatSession"]
    Command["ChatCommand"]
    MemoryKey["tenantId + userId + sessionId"]
    AgentRequest["AgentRunRequest / RuntimeRequest"]

    Headers --> Auth
    Auth --> UserContext
    UserContext --> Command
    Command --> Session
    Session --> MemoryKey
    MemoryKey --> AgentRequest
```

当前版本的 userId 来自前端 Header 或 WebSocket query。后续替换为服务端 Session、SSO 或网关 Token 时，只需要替换 `UserIdResolver` / `AuthContextProvider` 实现。

## 部署依赖

| 依赖 | 当前配置 |
| --- | --- |
| JDK | Java 21 编译目标 |
| Web 框架 | Spring Boot WebFlux |
| Agent SDK | AgentScope Java 1.0.9 |
| 数据库 | PostgreSQL，默认 `jdbc:postgresql://localhost:5432/financeex` |
| ORM | MyBatis Spring Boot Starter |
| 缓存 | Redis，默认 `localhost:6379` |
| 模型网关 | OpenAI 兼容接口，默认 `http://localhost:8000/v1` |
| Relay Runtime | 默认 mock，真实 HTTP Stream 由 `financeex.runtime.enabled=true` 开启 |

## 关键配置

```yaml
spring:
  datasource:
    url: ${FINANCEEX_DB_URL:jdbc:postgresql://localhost:5432/financeex}
    username: ${FINANCEEX_DB_USERNAME:financeex}
    password: ${FINANCEEX_DB_PASSWORD:financeex}

financeex:
  memory:
    short-term:
      redis:
        ttl: 3d
        max-cached-messages: 200
    long-term:
      provider: mock-external
  agent:
    engine: AGENTSCOPE
  runtime:
    enabled: false
```

## 后续演进建议

- 将 `ChatSession`、`ChatEvent`、`ConversationSummary` 和 `WorkingMemoryStore` 逐步迁移到 PostgreSQL 或可审计事件存储。
- 接入真实外部长期记忆服务，替换 `MockExternalLongTermMemoryStore`。
- 接入真实工具目录和工具审计表，替换当前 mock / noop 实现。
- 引入数据库迁移工具，例如 Flyway 或 Liquibase，管理 `schema.sql` 的版本演进。
- 如需启用 AgentScope 原生 AutoContext 压缩，可在项目存储边界外再增加压缩事件和 offload 存储。
