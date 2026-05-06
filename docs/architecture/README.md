# FinanceEXChatService v2 系统架构设计

## 架构目标

FinanceEXChatService 是前端聊天入口和 SuperAgent 主控服务。v2 的核心变化是删除旧的直接调用体系，把简单任务路由到第三方 SubAgent，把复杂任务路由到统一 AgentRuntime。

```text
用户请求
 -> 应用身份解析和会话归一化
 -> MemoryContext 装配
 -> AgentBinding + TaskCard 查询
    -> active AgentRuntime binding：续接 AgentRuntime
    -> active SubAgent task：ContinuationGuard 判断续接/挂起/取消/澄清
    -> 无 binding：用例库 match
        -> 命中 SubAgent：创建 TaskCard + AgentBinding 后 SubAgentClient.query
        -> 未命中：IntentService
            -> 简单任务 + subAgentCode：创建 TaskCard + AgentBinding 后 SubAgentClient.query
            -> 复杂/不确定：AgentRuntime.query
```

## 全局流程图

```mermaid
flowchart TD
    User["用户请求"] --> Normalize["应用身份解析与会话归一化"]
    Normalize --> Memory["加载 MemoryContext"]
    Memory --> ForceNew{"metadata.forceNewTask 为 true?"}
    ForceNew -- "是" --> CancelBinding["取消 active AgentBinding"]
    ForceNew -- "否" --> FindBinding["查询 AgentBinding"]
    CancelBinding --> FindBinding
    FindBinding --> HasBinding{"存在 active binding?"}

    HasBinding -- "是" --> BindingType{"binding_type"}
    BindingType -- "SUB_AGENT" --> FindTask["读取 active TaskCard"]
    FindTask --> Guard["ContinuationGuard + 必要时 shadow route"]
    Guard --> GuardDecision{"续接决策"}
    GuardDecision -- "CONTINUE_CURRENT" --> BoundSubAgent["续接 SubAgentClient.query"]
    GuardDecision -- "SUSPEND_AND_ROUTE_NEW" --> SuspendTask["TaskCard=SUSPENDED"]
    GuardDecision -- "CANCEL_CURRENT" --> CancelTask["TaskCard=CANCELLED"]
    GuardDecision -- "ASK_USER_CONFIRMATION" --> Clarify["SYSTEM_RESPONSE 澄清"]
    SuspendTask --> UseCase
    CancelTask --> EventStream
    Clarify --> EventStream
    BindingType -- "AGENT_RUNTIME" --> BoundRuntime["续接 AgentRuntime.query"]

    HasBinding -- "否" --> UseCase["UseCaseLibraryClient.match"]
    UseCase --> UseCaseHit{"命中且分数达标且有 subAgentCode?"}
    UseCaseHit -- "是" --> CreateSubBinding["创建 SUB_AGENT binding"]
    CreateSubBinding --> CreateTask["创建 TaskCard"]
    CreateTask --> CallSubAgent["调用 SubAgentClient.query"]

    UseCaseHit -- "否" --> Intent["IntentService.recognize"]
    Intent --> IntentRoute{"意图路由结果"}
    IntentRoute -- "UNSUPPORTED" --> SystemResponse["SYSTEM_RESPONSE"]
    IntentRoute -- "SIMPLE 且有 subAgentCode" --> CreateIntentBinding["创建 SUB_AGENT binding"]
    CreateIntentBinding --> CreateIntentTask["创建 TaskCard"]
    CreateIntentTask --> CallSubAgent
    IntentRoute -- "COMPLEX / 低置信 / 无 subAgentCode" --> CreateRuntimeBinding["创建 AGENT_RUNTIME binding"]
    CreateRuntimeBinding --> CallRuntime["调用 AgentRuntime.query"]

    BoundSubAgent --> EventStream["输出 ChatEvent 流"]
    BoundRuntime --> EventStream
    CallSubAgent --> EventStream
    CallRuntime --> EventStream
    SystemResponse --> EventStream
    EventStream --> Persist["事件与消息写入 openGauss"]
    Persist --> ObserveStatus["标准化并观察 taskStatus"]
    ObserveStatus --> TaskUpdate["写 TaskCard 与 TaskEvent"]
    TaskUpdate --> BindingUpdate{"任务是否终态或挂起?"}
    BindingUpdate -- "是" --> ReleaseBinding["释放 Redis active key 并写状态"]
    BindingUpdate -- "否" --> KeepBinding["刷新 Redis binding/task 与过期时间"]
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
    participant Task as "TaskCardApplicationService"
    participant Redis as "Redis"
    participant DB as "openGauss"
    participant UseCase as "UseCaseLibraryService"
    participant Intent as "IntentService"
    participant SubAgent as "SubAgent"
    participant Runtime as "AgentRuntime"
    participant EventStore as "ChatEventStore"

    Frontend->>API: "发送聊天请求"
    API->>SuperAgent: "chat(command)"
    SuperAgent->>SuperAgent: "AuthContextProvider.resolve()"
    SuperAgent->>SuperAgent: "生成 runId"
    SuperAgent->>Session: "loadOrCreate(command)"
    Session->>DB: "读取或写入 fin_ex_chat_session_t"
    SuperAgent->>Memory: "loadForRun(command)"
    Memory->>Redis: "读取短期消息与工作记忆"
    Memory->>DB: "Redis miss 时回源消息和摘要"

    alt "metadata.forceNewTask 为 true"
        SuperAgent->>Task: "取消 active TaskCard"
        Task->>DB: "写入 fin_ex_task_card_t / fin_ex_task_event_t"
        Task->>Redis: "删除 fin_ex:task:active key"
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
        SuperAgent->>Task: "findActive(sessionId)"
        Task->>Redis: "读取 fin_ex:task:active"
        alt "Redis miss"
            Task->>DB: "查询 fin_ex_task_card_t"
            Task->>Redis: "回填 active TaskCard"
        end
        SuperAgent->>SuperAgent: "ContinuationGuard 判断本轮输入"
        alt "继续当前任务"
            SuperAgent->>Binding: "touchForRun(runId)"
            Binding->>DB: "刷新 last_run_id 与 expires_at"
            Binding->>Redis: "刷新 binding TTL"
            SuperAgent->>Task: "touch(taskId)"
            Task->>DB: "续期 TaskCard 并记录事件"
            Task->>Redis: "刷新 task TTL"
            SuperAgent->>SubAgent: "query(SubAgentTaskRequest)"
        else "明显新任务"
            SuperAgent->>Task: "SUSPENDED"
            Task->>DB: "写 TaskCard 和 TaskEvent"
            Task->>Redis: "删除 active task"
            SuperAgent->>Binding: "SUSPENDED"
            Binding->>Redis: "删除 active binding"
            SuperAgent->>UseCase: "shadow/new route match"
        else "无法判断"
            SuperAgent->>Task: "WAITING_USER_CONFIRMATION"
            Task->>DB: "写 TaskCard 和 TaskEvent"
            SuperAgent->>SuperAgent: "返回澄清问题"
        end
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
            SuperAgent->>Task: "createForSubAgent(runId)"
            Task->>DB: "写入 fin_ex_task_card_t / fin_ex_task_event_t"
            Task->>Redis: "缓存 active task"
            SuperAgent->>SubAgent: "query(SubAgentTaskRequest)"
        else "用例库未命中"
            SuperAgent->>Intent: "recognize(command, memory)"
            alt "简单任务且有 subAgentCode"
                SuperAgent->>Binding: "createSubAgentBinding(runId)"
                Binding->>DB: "写入 fin_ex_agent_binding_t"
                Binding->>Redis: "缓存 binding"
                SuperAgent->>Task: "createForSubAgent(runId)"
                Task->>DB: "写入 fin_ex_task_card_t / fin_ex_task_event_t"
                Task->>Redis: "缓存 active task"
                SuperAgent->>SubAgent: "query(SubAgentTaskRequest)"
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
    SuperAgent->>Task: "observeEvent(taskStatus)"
    alt "COMPLETED / FAILED / CANCELLED"
        Task->>DB: "写任务终态与事件"
        Task->>Redis: "删除 active task"
        Binding->>DB: "写入终态"
        Binding->>Redis: "删除 binding"
    else "ACTIVE / REQUIRES_USER_INPUT / WAITING_*"
        Task->>DB: "保持可续接状态并记录事件"
        Task->>Redis: "刷新 task TTL"
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
        TaskService["TaskCardApplicationService"]
        Guard["ContinuationGuard"]
        RoutingPolicy["RoutingPolicy"]
        SubAgentExecutor["SubAgentExecutor"]
        RuntimeExecutor["AgentRuntimeExecutor"]
    end

    subgraph Integration["application.integration 出站集成抽象"]
        UseCaseClient["UseCaseLibraryClient"]
        IntentService["IntentService"]
        SubAgentClient["SubAgentClient"]
        AgentRuntime["AgentRuntime"]
        Repositories["Session / Memory / Document / Binding / Task Repositories"]
    end

    subgraph Domain["domain"]
        ChatModel["ChatCommand / ChatEvent"]
        AgentBinding["AgentBinding"]
        TaskCard["TaskCard / TaskStatus"]
        Routing["RouteTarget / RouteType"]
        Intent["IntentDecision"]
        UseCase["UseCaseMatchResult"]
    end

    subgraph Infrastructure["infrastructure"]
        Redis["Redis AgentBinding / TaskCard / ShortTerm Cache"]
        OpenGauss["openGauss + MyBatis"]
        UseCaseHttp["UseCase HTTP Adapter"]
        SubAgentHttp["SubAgent HTTP Adapter"]
        RuntimeProvider["RelayAgent / AgentScope Provider"]
    end

    Interfaces --> ChatService
    ChatService --> SessionService
    ChatService --> MemoryService
    ChatService --> BindingService
    ChatService --> TaskService
    ChatService --> Guard
    ChatService --> UseCaseClient
    ChatService --> IntentService
    ChatService --> RoutingPolicy
    ChatService --> SubAgentExecutor
    ChatService --> RuntimeExecutor
    Application --> Integration
    Application --> Domain
    Infrastructure --> Integration
    BindingService --> Redis
    BindingService --> OpenGauss
```

`application.integration` 是 application 层的出站集成抽象，用来表达应用服务依赖外部能力的边界；它不是具体基础设施实现。Redis、openGauss/MyBatis、HTTP 客户端、对象存储和 AgentRuntime provider 的落地代码仍归属 `infrastructure`。

## 路由规则

- active `AgentBinding` 只是未完成任务索引；SubAgent binding 必须先读取 `TaskCard` 并经过 `ContinuationGuard`。
- `ContinuationGuard` 只在用户补参数、确认上一轮问题、解释当前任务或上传当前任务附件时续接原 SubAgent。
- 用户明显切换任务时，当前 `TaskCard` 置为 `SUSPENDED`，本轮重新走用例库/意图路由。
- 判断不清时先做 shadow route；仍无法确认时进入 `WAITING_USER_CONFIRMATION`，向用户澄清。
- 用例库命中阈值默认 `0.85`，命中并返回 `subAgentCode` 后直接调用 SubAgent。
- 用例库未命中才调用 `IntentService`。
- `IntentService` 返回简单任务且有 `candidateSubAgentCode` 时调用 SubAgent。
- 复杂、低置信、缺少 SubAgent 的任务进入 AgentRuntime。
- 不支持任务走 `SYSTEM_RESPONSE`，返回可控说明。
- 前端可通过 `metadata.forceNewTask=true` 取消当前 TaskCard 和 binding 并重新路由。

## 会话与执行标识

v2 同时保留多种 ID，它们的职责不同：

```text
sessionId         前端聊天会话，一段持续对话
runId             SuperAgent 单轮执行追踪 ID
taskId            SuperAgent 侧可续接业务任务 ID
agentSessionId    SubAgent 内部会话 ID
runtimeSessionId  AgentRuntime 内部会话 ID
```

`runId` 在每轮用户请求进入 `FinanceEXChatService` 时生成，贯穿该轮的所有 ChatEvent。它用于：

- 前端把同一轮 SSE/NDJSON/WebSocket 事件聚合成一次响应。
- `fin_ex_chat_event_t.run_id` 按运行轮次回查事件轨迹。
- `fin_ex_agent_binding_t.last_run_id` 记录最近触发该 binding 的运行轮次。
- 传给 SubAgent/AgentRuntime，方便跨服务日志关联。

`runId` 不参与多轮保持决策。SubAgent 多轮保持由 `TaskCard.taskStatus`、`requiredInputs`、`AgentBinding.status` 和 `agentSessionId` 共同决定；AgentRuntime 多轮保持由 `AgentBinding.status` 和 `runtimeSessionId` 决定。

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

状态包括 `ACTIVE`、`REQUIRES_USER_INPUT`、`WAITING_EXTERNAL_SYSTEM`、`WAITING_USER_CONFIRMATION`、`SUSPENDED`、`COMPLETED`、`FAILED`、`CANCELLED`、`EXPIRED`、`UNKNOWN`。只有 `ACTIVE`、`REQUIRES_USER_INPUT`、`WAITING_EXTERNAL_SYSTEM`、`WAITING_USER_CONFIRMATION` 会进入 active 查询；`SUSPENDED` 保留事实但不作为当前路由。

## TaskCard

`TaskCard` 是简单 SubAgent 任务状态事实。它记录任务目标、任务领域、当前 SubAgent、下游 `agentSessionId`、任务状态、待补充参数、已收集参数、最近一次 Agent 回复和澄清问题。

```text
Redis key:
fin_ex:task:active:{tenantId}:{userId}:{sessionId}
fin_ex:task:card:{tenantId}:{userId}:{sessionId}:{taskId}

openGauss tables:
fin_ex_task_card_t
fin_ex_task_event_t
```

状态模型：

- 可续接：`ACTIVE`、`REQUIRES_USER_INPUT`、`WAITING_EXTERNAL_SYSTEM`、`WAITING_USER_CONFIRMATION`
- 可恢复但非当前活跃：`SUSPENDED`
- 终态：`COMPLETED`、`FAILED`、`CANCELLED`、`EXPIRED`
- 内部诊断：`UNKNOWN`

`UNKNOWN` 不直接面向用户。SubAgent 响应无法判断时，`rawNormalizedStatus=UNKNOWN`，对外任务状态统一转为 `WAITING_USER_CONFIRMATION`。

## SubAgent 契约

员工报销 SubAgent 固定编码为 `employee_reimbursement_agent`，默认配置：

```yaml
financeex.sub-agent.agents.employee_reimbursement_agent.interaction-mode: natural-language-contract
financeex.sub-agent.agents.employee_reimbursement_agent.endpoint: ${FINANCEEX_EMPLOYEE_REIMBURSEMENT_AGENT_ENDPOINT:}
```

`SubAgentTaskPromptBuilder` 会基于 `TaskCard`、用户本轮 query、附件和上下文生成增强任务 Prompt，要求自然语言 Agent 只处理当前任务并返回 JSON。`SubAgentResponseNormalizer` 会按以下顺序兜底：

- 直接解析 JSON。
- 提取 markdown JSON code block。
- 执行字段别名映射：`reply/message/content`、`status/taskStatus`、`sessionId/agentSessionId`。
- 对普通文本做状态推断，例如“请上传/请提供”映射为 `REQUIRES_USER_INPUT`，“已完成/提交成功”映射为 `COMPLETED`，“处理中/请稍后”映射为 `WAITING_EXTERNAL_SYSTEM`。
- 无法判断时进入 `WAITING_USER_CONFIRMATION`，由 SuperAgent 询问用户继续当前报销任务还是开始新任务。

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
- `fin_ex_task_card_t`
- `fin_ex_task_event_t`

Redis key 必须以 `fin_ex` 开头：

- `fin_ex:agent_binding:{tenantId}:{userId}:{sessionId}`
- `fin_ex:task:active:{tenantId}:{userId}:{sessionId}`
- `fin_ex:task:card:{tenantId}:{userId}:{sessionId}:{taskId}`
- `fin_ex:memory:short_term:messages:{tenantId}:{userId}:{sessionId}`
- `fin_ex:memory:working:variables:{sessionId}`
