# FinanceEXChatService v2 系统架构设计

## 架构目标

FinanceEXChatService 是前端聊天入口和 SuperAgent 主控服务。v2 的核心变化是删除旧的直接调用体系，把简单任务路由到第三方 SubAgent，把复杂任务路由到统一 AgentRuntime。

```text
用户请求
 -> 会话和身份解析
 -> MemoryContext 装配
 -> AgentBinding 查询
    -> active binding：续接 SubAgent 或 AgentRuntime
    -> 无 binding：用例库 match
        -> 命中 SubAgent：SubAgentClient.query
        -> 未命中：IntentService
            -> 简单任务 + subAgentCode：SubAgentClient.query
            -> 复杂/不确定：AgentRuntime.query
```

## 全局流程图

```mermaid
flowchart TD
    User["用户请求"] --> Normalize["身份校验与会话归一化"]
    Normalize --> Memory["加载 MemoryContext"]
    Memory --> ForceNew{"metadata.forceNewTask 为 true?"}
    ForceNew -- "是" --> CancelBinding["取消 active AgentBinding"]
    ForceNew -- "否" --> FindBinding["查询 AgentBinding"]
    CancelBinding --> FindBinding
    FindBinding --> HasBinding{"存在 active binding?"}

    HasBinding -- "是" --> BindingType{"binding_type"}
    BindingType -- "SUB_AGENT" --> BoundSubAgent["续接 SubAgentClient.query"]
    BindingType -- "AGENT_RUNTIME" --> BoundRuntime["续接 AgentRuntime.query"]

    HasBinding -- "否" --> UseCase["UseCaseLibraryClient.match"]
    UseCase --> UseCaseHit{"命中且分数达标且有 subAgentCode?"}
    UseCaseHit -- "是" --> CreateSubBinding["创建 SUB_AGENT binding"]
    CreateSubBinding --> CallSubAgent["调用 SubAgentClient.query"]

    UseCaseHit -- "否" --> Intent["IntentService.recognize"]
    Intent --> IntentRoute{"意图路由结果"}
    IntentRoute -- "UNSUPPORTED" --> SystemResponse["SYSTEM_RESPONSE"]
    IntentRoute -- "SIMPLE 且有 subAgentCode" --> CreateIntentBinding["创建 SUB_AGENT binding"]
    CreateIntentBinding --> CallSubAgent
    IntentRoute -- "COMPLEX / 低置信 / 无 subAgentCode" --> CreateRuntimeBinding["创建 AGENT_RUNTIME binding"]
    CreateRuntimeBinding --> CallRuntime["调用 AgentRuntime.query"]

    BoundSubAgent --> EventStream["输出 ChatEvent 流"]
    BoundRuntime --> EventStream
    CallSubAgent --> EventStream
    CallRuntime --> EventStream
    SystemResponse --> EventStream
    EventStream --> Persist["事件与消息写入 openGauss"]
    Persist --> ObserveStatus["观察 taskStatus"]
    ObserveStatus --> BindingUpdate{"任务是否终态?"}
    BindingUpdate -- "是" --> ReleaseBinding["释放 Redis binding 并写终态"]
    BindingUpdate -- "否" --> KeepBinding["刷新 Redis binding 与过期时间"]
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
    participant Binding as "AgentBindingApplicationService"
    participant Redis as "Redis"
    participant DB as "openGauss"
    participant UseCase as "UseCaseLibraryService"
    participant Intent as "IntentService"
    participant SubAgent as "SubAgent"
    participant Runtime as "AgentRuntime"
    participant EventStore as "ChatEventStore"

    Frontend->>API: "发送聊天请求"
    API->>SuperAgent: "chat(command)"
    SuperAgent->>SuperAgent: "生成 runId"
    SuperAgent->>Session: "loadOrCreate(command)"
    Session->>DB: "读取或写入 fin_ex_chat_session_t"
    SuperAgent->>Memory: "loadForRun(command)"
    Memory->>Redis: "读取短期消息与工作记忆"
    Memory->>DB: "Redis miss 时回源消息和摘要"

    alt "metadata.forceNewTask 为 true"
        SuperAgent->>Binding: "cancelActive(sessionId)"
        Binding->>DB: "写入 CANCELLED 状态"
        Binding->>Redis: "删除 fin_ex:agent_binding key"
    end

    SuperAgent->>Binding: "findActive(sessionId)"
    Binding->>Redis: "读取 active binding"
    alt "Redis miss"
        Binding->>DB: "查询 fin_ex_agent_binding_t"
        DB-->>Binding: "返回 active binding 或空"
        Binding->>Redis: "回填 active binding"
    end

    alt "存在 SUB_AGENT binding"
        SuperAgent->>Binding: "touchForRun(runId)"
        Binding->>DB: "刷新 last_run_id 与 expires_at"
        Binding->>Redis: "刷新 binding TTL"
        SuperAgent->>SubAgent: "query(runId, agentSessionId, message)"
    else "存在 AGENT_RUNTIME binding"
        SuperAgent->>Binding: "touchForRun(runId)"
        Binding->>DB: "刷新 last_run_id 与 expires_at"
        Binding->>Redis: "刷新 binding TTL"
        SuperAgent->>Runtime: "query(runId, runtimeSessionId, message)"
    else "不存在 active binding"
        SuperAgent->>UseCase: "match(request)"
        alt "用例库命中 SubAgent"
            SuperAgent->>Binding: "createSubAgentBinding(runId)"
            Binding->>DB: "写入 fin_ex_agent_binding_t"
            Binding->>Redis: "缓存 binding"
            SuperAgent->>SubAgent: "query(runId, message)"
        else "用例库未命中"
            SuperAgent->>Intent: "recognize(command, memory)"
            alt "简单任务且有 subAgentCode"
                SuperAgent->>Binding: "createSubAgentBinding(runId)"
                Binding->>DB: "写入 fin_ex_agent_binding_t"
                Binding->>Redis: "缓存 binding"
                SuperAgent->>SubAgent: "query(runId, message)"
            else "复杂或不确定任务"
                SuperAgent->>Binding: "createRuntimeBinding(runId)"
                Binding->>DB: "写入 fin_ex_agent_binding_t"
                Binding->>Redis: "缓存 binding"
                SuperAgent->>Runtime: "query(runId, message)"
            else "不支持任务"
                SuperAgent->>SuperAgent: "生成 SYSTEM_RESPONSE 事件"
            end
        end
    end

    loop "输出 ChatEvent"
        SubAgent-->>SuperAgent: "message.delta / message.completed"
        Runtime-->>SuperAgent: "message.delta / message.completed"
        SuperAgent->>EventStore: "append(event)"
        EventStore->>DB: "写入 fin_ex_chat_event_t"
        SuperAgent-->>API: "转发事件"
        API-->>Frontend: "SSE / NDJSON / WebSocket"
    end

    SuperAgent->>Session: "保存完整 assistant 消息"
    Session->>DB: "写入 fin_ex_chat_message_t"
    SuperAgent->>Binding: "observeEvent(taskStatus)"
    alt "COMPLETED / FAILED / CANCELLED"
        Binding->>DB: "写入终态"
        Binding->>Redis: "删除 binding"
    else "ACTIVE / REQUIRES_USER_INPUT"
        Binding->>DB: "保持可续接状态"
        Binding->>Redis: "刷新 binding TTL"
    end
```

## 分层架构

```mermaid
flowchart TB
    subgraph Interfaces["interfaces"]
        ChatController["ChatController"]
        ChatWS["ChatWebSocketHandler"]
        SessionController["ChatSessionController"]
        DocumentController["DocumentUploadController"]
    end

    subgraph Application["application"]
        ChatService["FinanceEXChatService"]
        SessionService["SessionApplicationService"]
        MemoryService["MemoryApplicationService"]
        BindingService["AgentBindingApplicationService"]
        UseCaseClient["UseCaseLibraryClient"]
        IntentService["IntentService"]
        RoutingPolicy["RoutingPolicy"]
        SubAgentExecutor["SubAgentExecutor"]
        RuntimeExecutor["AgentRuntimeExecutor"]
    end

    subgraph Domain["domain"]
        ChatModel["ChatCommand / ChatEvent"]
        AgentBinding["AgentBinding"]
        Routing["RouteTarget / RouteType"]
        Intent["IntentDecision"]
        UseCase["UseCaseMatchResult"]
    end

    subgraph Infrastructure["infrastructure"]
        Redis["Redis AgentBinding / ShortTerm Cache"]
        OpenGauss["openGauss + MyBatis"]
        UseCaseHttp["UseCase HTTP Adapter"]
        SubAgentHttp["SubAgent HTTP Adapter"]
        RuntimeProvider["RelayAgent / AgentScope Provider"]
    end

    Interfaces --> ChatService
    ChatService --> SessionService
    ChatService --> MemoryService
    ChatService --> BindingService
    ChatService --> UseCaseClient
    ChatService --> IntentService
    ChatService --> RoutingPolicy
    ChatService --> SubAgentExecutor
    ChatService --> RuntimeExecutor
    Application --> Domain
    Infrastructure --> Application
    BindingService --> Redis
    BindingService --> OpenGauss
```

## 路由规则

- active `AgentBinding` 优先级最高，避免多轮任务被重新分类打断。
- 用例库命中阈值默认 `0.85`，命中并返回 `subAgentCode` 后直接调用 SubAgent。
- 用例库未命中才调用 `IntentService`。
- `IntentService` 返回简单任务且有 `candidateSubAgentCode` 时调用 SubAgent。
- 复杂、低置信、缺少 SubAgent 的任务进入 AgentRuntime。
- 不支持任务走 `SYSTEM_RESPONSE`，返回可控说明。
- 前端可通过 `metadata.forceNewTask=true` 取消当前 binding 并重新路由。

## 会话与执行标识

v2 同时保留多种 ID，它们的职责不同：

```text
sessionId         前端聊天会话，一段持续对话
runId             SuperAgent 单轮执行追踪 ID
agentSessionId    SubAgent 内部会话 ID
runtimeSessionId  AgentRuntime 内部会话 ID
```

`runId` 在每轮用户请求进入 `FinanceEXChatService` 时生成，贯穿该轮的所有 ChatEvent。它用于：

- 前端把同一轮 SSE/NDJSON/WebSocket 事件聚合成一次响应。
- `fin_ex_chat_event_t.run_id` 按运行轮次回查事件轨迹。
- `fin_ex_agent_binding_t.last_run_id` 记录最近触发该 binding 的运行轮次。
- 传给 SubAgent/AgentRuntime，方便跨服务日志关联。

`runId` 不参与多轮保持决策。多轮保持由 `AgentBinding.status`、`agentSessionId` 和 `runtimeSessionId` 决定。

## AgentBinding

`AgentBinding` 维护前端 chat session 与下游 SubAgent/AgentRuntime session 的关系。Redis 是热缓存，openGauss 是事实源。

```text
Redis key:
fin_ex:agent_binding:{tenantId}:{userId}:{sessionId}

openGauss table:
fin_ex_agent_binding_t
```

字段包括：

```text
id
tenant_id
user_id
chat_session_id
binding_type
agent_code
provider
agent_session_id
runtime_session_id
status
last_run_id
expires_at
metadata_json
created_at
updated_at
```

状态包括 `ACTIVE`、`REQUIRES_USER_INPUT`、`COMPLETED`、`FAILED`、`CANCELLED`、`EXPIRED`。只有 `ACTIVE` 和 `REQUIRES_USER_INPUT` 会继续续接。

## AgentRuntime

`AgentRuntime` 只强制实现统一 `query(request)` 接口。不同 provider 通过防腐层适配：

- `relay-agent`：转发到远程完整 Agent 服务。
- `agentscope`：进程内 AgentScope 实现。
- `spring-ai`、`langchain`：保留枚举和扩展方向。

AgentRuntime 自己负责内部 session、上下文管理、压缩和规划。SuperAgent 只保存最小可见消息、摘要和 binding。

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
- `fin_ex_agent_binding_t`

Redis key 必须以 `fin_ex` 开头：

- `fin_ex:agent_binding:{tenantId}:{userId}:{sessionId}`
- `fin_ex:memory:short_term:messages:{tenantId}:{userId}:{sessionId}`
- `fin_ex:memory:working:variables:{sessionId}`
