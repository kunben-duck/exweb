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
    Memory --> ExplicitSkill{"metadata.selectedSkillId 存在?"}
    ExplicitSkill -- "是" --> LegacySkill["EXPLICIT_SKILL：调用老 Agent 指定技能"]
    ExplicitSkill -- "否" --> ForceNew{"metadata.forceNewTask 为 true?"}
    ForceNew -- "是" --> CancelRuntime["取消 active RuntimeBinding"]
    ForceNew -- "否" --> FindRuntime["查询 RuntimeBinding"]
    CancelRuntime --> FindRuntime

    FindRuntime --> HasRuntime{"存在 active RuntimeBinding?"}
    HasRuntime -- "是" --> TouchRuntime["刷新 RuntimeBinding"]
    TouchRuntime --> RuntimeQuery["AgentRuntime.query 防腐层"]

    HasRuntime -- "否" --> RouteSignal["RouteSignalApplicationService"]
    RouteSignal --> SignalEnabled{"用例库或意图服务已开启?"}
    SignalEnabled -- "否" --> CreateRuntime["创建 RuntimeBinding"]
    CreateRuntime --> RuntimeQuery

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
    LegacySkill --> EventStream
    RuntimeQuery --> RelayHttp["RelayStreamHttpRuntimeAdapter"]
    RelayHttp --> EventStream
    SystemResponse --> EventStream
    EventStream --> Persist["事件写入 fin_ex_chat_event_t"]
    Persist --> Publish["发布到 run stream topic"]
    Publish --> FrontWS["前端 WebSocket 实时订阅"]
    Persist --> ResumeEvents["Event Resume 按 afterSeq 恢复：session 有限补发 / run tail 到终态"]
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
    participant Runtime as "AgentRuntime Port"
    participant RelayHttp as "RelayStreamHttpRuntimeAdapter"
    participant RelayAgent as "RelayAgent Service"
    participant Stream as "ChatStreamApplicationService"
    participant Redis as "Redis"
    participant DB as "数据库"
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
        Binding->>Redis: "删除 fin_ex:{env}:runtime_binding key"
    end

    alt "metadata.selectedSkillId 存在"
        SuperAgent->>SuperAgent: "RouteTarget.EXPLICIT_SKILL，不读取 RuntimeBinding"
        SuperAgent->>RelayAgent: "老 Agent chat(skillId, query, legacy docList)"
    else "未显式指定技能"
        SuperAgent->>Binding: "findActive(sessionId)"
        Binding->>Redis: "读取 RuntimeBinding"
        alt "Redis miss"
            Binding->>DB: "查询 fin_ex_runtime_binding_t"
            Binding->>Redis: "回填 active RuntimeBinding"
        end
    end

    alt "存在 active RuntimeBinding"
        SuperAgent->>Binding: "touchForRun(runId)"
        Binding->>DB: "刷新 last_run_id 与 expires_at"
        Binding->>Redis: "刷新 RuntimeBinding TTL"
        SuperAgent->>Runtime: "AgentRuntime.query(runtimeSessionId, message)"
        Runtime->>RelayHttp: "delegate"
        RelayHttp->>RelayAgent: "HTTP POST stream-path"
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
            SuperAgent->>Runtime: "AgentRuntime.query(message)"
            Runtime->>RelayHttp: "delegate"
            RelayHttp->>RelayAgent: "HTTP POST stream-path"
        else "不支持任务"
            Signal-->>SuperAgent: "SYSTEM_RESPONSE"
            SuperAgent->>SuperAgent: "生成可控系统回复"
        end
    end

    loop "输出 ChatEvent"
        alt "SubAgent route"
            SubAgent-->>SuperAgent: "message.delta / message.snapshot / message.completed"
        else "Explicit skill route"
            RelayAgent-->>SuperAgent: "legacy eventStream -> message.delta / runtime.* / message.completed"
        else "Relay HTTP Runtime route"
            RelayAgent-->>RelayHttp: "HTTP stream delta"
            RelayHttp-->>Runtime: "ChatEvent"
            Runtime-->>SuperAgent: "message.delta / message.snapshot / runtime.* / message.completed"
        end
        SuperAgent->>EventStore: "append(event)"
        EventStore->>DB: "写入 fin_ex_chat_event_t"
        EventStore-->>SuperAgent: "返回持久化 seq"
        SuperAgent->>Stream: "publish(run stream topic)"
        Stream-->>Frontend: "WebSocket envelope(message)"
        SuperAgent->>Binding: "observeEvent(runtimeSessionId)"
    end

    SuperAgent->>Session: "保存完整 assistant 消息"
    Session->>DB: "写入 fin_ex_chat_message_t"
```

## 简化版全局时序图

下面的简化图只保留核心外部参与方和事实源，适合做整体链路评审。它省略了应用层内部服务、SubAgent 细节、WebSocket/Event Resume handler 细节和 Runtime adapter 细节；前端 WebSocket 接入仍然保留，只是不在本图展开。

```mermaid
sequenceDiagram
    autonumber
    participant Frontend as "前端"
    participant EX as "EXChatService"
    participant UseCase as "案例库"
    participant Intent as "意图服务"
    participant Relay as "下游 RelayAgentRuntime"
    participant Redis as "Redis"
    participant DB as "数据库"
    participant S3 as "S3 / OBS"

    opt "上传文档"
        Frontend->>EX: "POST /api/v1/ex/documents"
        alt "targetProvider=default-storage"
            EX->>S3: "写入文件对象"
            S3-->>EX: "bucket/objectKey"
        else "targetProvider=legacy-agent"
            EX->>EX: "调用配置化 HTTP DocumentProviderAdapter"
        end
        EX->>DB: "写入 fin_ex_uploaded_document_t"
        EX-->>Frontend: "documentId/status"
    end

    Frontend->>EX: "POST /api/v1/ex/chat/runs"
    EX->>DB: "创建/读取 session，写 user message、run、run.started"
    EX->>Redis: "写 active run / runtime binding / stream topic 热数据"

    opt "用例库开启"
        EX->>UseCase: "match(query, context)"
        UseCase-->>EX: "matched/subAgentCode/score 或未命中"
    end

    opt "意图服务开启且用例未命中"
        EX->>Intent: "recognize(query, context)"
        Intent-->>EX: "simple/complex/candidateSubAgentCode"
    end

    alt "metadata.selectedSkillId 存在"
        EX->>EX: "EXPLICIT_SKILL：按 skillId 调用老 Agent，简图省略下游细节"
    else "进入 Relay Runtime"
        EX->>Relay: "AgentRuntime.query(sessionId, query, attachments, Cookie snapshot)"
        Relay-->>EX: "message.delta / message.snapshot / runtime.* / message.completed"
    else "简单任务命中"
        EX->>EX: "按 agentCode 单轮执行，细节在简图中省略"
    end

    loop "流式事件"
        EX->>DB: "写入 fin_ex_chat_event_t，生成 seq"
        EX->>Redis: "publish fin_ex:{env}:chat_stream:{topicId}"
        EX-->>Frontend: "WebSocket message 或 Event Resume event"
    end

    opt "停止回答"
        Frontend->>EX: "POST /api/v1/ex/chat/runs/{runId}/stop"
        EX->>Redis: "写 cancel flag"
        EX->>DB: "run -> CANCELLING/CANCELLED，写 run.cancelled"
        EX->>Relay: "best-effort cancel"
        EX-->>Frontend: "ChatRunStopDto"
    end
```

## 文档库与附件使用

文档能力采用“统一后端入口 + DocumentProviderAdapter 防腐层 + 文档库资产”的模式。

```mermaid
sequenceDiagram
    autonumber
    participant Frontend as "Frontend"
    participant DocAPI as "Document API"
    participant DocApp as "DocumentApplicationService"
    participant Provider as "DocumentProviderAdapter"
    participant ObjectStorage as "ObjectStorage Provider"
    participant HttpProvider as "HTTP Provider"
    participant DB as "数据库"
    participant Chat as "FinanceEXChatService"
    participant Executor as "SubAgent / LegacySkill / AgentRuntime"

    Frontend->>DocAPI: "POST /documents multipart file,targetProvider?,skillId?"
    DocAPI->>DocAPI: "AuthContextProvider.resolve()"
    DocAPI->>DocApp: "upload(UserContext, DocumentUploadCommand)"
    DocApp->>DocApp: "会话归属校验"
    DocApp->>Provider: "resolve(targetProvider)"
    alt "default-storage"
        Provider->>ObjectStorage: "putObject(tenantId, file)"
        ObjectStorage-->>Provider: "bucket/objectKey"
    else "legacy-agent / future domain provider"
        Provider->>HttpProvider: "multipart upload by configured path"
        HttpProvider-->>Provider: "provider docId/docName/docSize"
    end
    DocApp->>DB: "写 fin_ex_uploaded_document_t"
    DocApp-->>Frontend: "UploadedDocument(id,status,source)"

    Frontend->>Chat: "聊天请求 attachments[{documentId}]"
    Chat->>DB: "回查 fin_ex_uploaded_document_t"
    Chat->>Chat: "校验归属、状态，补齐可信附件元数据"
    Chat->>Executor: "AgentQueryRequest / AgentRuntimeRequest.attachments"
```

设计原则：

- 前端上传仍先进入 FinanceEXChatService，方便统一鉴权、审计、限流和企业网关接入。
- 真实文件内容由 `DocumentProviderAdapter` 决定去向：默认 provider 仍写入 local 或 huawei-s3；老 Agent 或领域 Agent provider 可以转发自己的上传接口。
- 聊天请求只引用 `documentId`，不携带文件正文。
- SubAgent、显式技能或 Runtime 看到的是经过文档库回查后的可信附件元数据。
- `fin_ex_uploaded_document_t` 是文档库事实源，支持最近文档、库中文档选择和后续连接器文档扩展。

## 流式响应与断点恢复

正式版只保留后台 run 创建模式。`POST /chat/runs` 是唯一提问入口。
本页新创建的 run 默认通过 WebSocket topic 接实时事件；新页签、新浏览器或跨电脑恢复已经存在的 active run 时，使用 run 级事件恢复先补发历史事件，再持续接续 live 事件直到本轮 run 终态。会话级事件恢复 仍只负责有限缺失事件补发。

```text
POST /api/v1/ex/chat/runs
POST /api/v1/ex/chat/messages/{messageId}/feedback
DELETE /api/v1/ex/chat/messages/{messageId}/feedback
POST /api/v1/ex/chat/messages/{messageId}/share
GET  /api/v1/ex/chat/shares/{shareId}
DELETE /api/v1/ex/chat/shares/{shareId}
GET  /api/v1/ex/chat/shares?curPage=1&pageSize=20
POST /api/v1/ex/chat/sessions
GET  /api/v1/ex/chat/sessions?limit=20&cursor=...
GET  /api/v1/ex/chat/sessions/{sessionId}/state?messageLimit=50
GET  /api/v1/ex/chat/sessions/{sessionId}/messages?leafMessageId=...&limit=50
GET  /api/v1/ex/chat/sessions/{sessionId}/messages/{messageId}/variants
POST /api/v1/ex/chat/sessions/{sessionId}/path
POST /api/v1/ex/chat/sessions/{sessionId}/branches
GET  /api/v1/ex/chat/sessions/{sessionId}/events/resume?afterSeq={lastSeq}
GET  /api/v1/ex/chat/runs/{runId}/events/resume?afterSeq={lastSeq}
GET  /api/v1/ex/chat/sessions/{sessionId}/stream-status
WS   /api/v1/ex/chat/ws subscribe(topicId=streamTopicId)
POST /api/v1/ex/chat/runs/{runId}/stop
POST /api/v1/ex/chat/sessions/{sessionId}/archive
POST /api/v1/ex/chat/sessions/{sessionId}/restore
DELETE /api/v1/ex/chat/sessions/{sessionId}
DELETE /api/v1/ex/chat/sessions
```

`/chat/runs` 只返回 run 运行标识和 run 级 `streamTopicId`，不返回 WebSocket、Event Resume 或 stop URL。
这些 URL 属于前端 SDK、网关或部署配置，避免后端业务响应承担客户端路由配置职责。

## 消息树与只读分支

当前版本引入会话内消息树，但不改变现有流式协议。`POST /chat/runs` 创建后台 run 时会先根据 `runMode` 解析消息树写入计划：

- `NEXT`：在 `parentMessageId` 或会话 `current_leaf_message_id` 后追加新的 user 消息，run 完成后追加 assistant 消息。
- `EDIT_USER`：校验 `editedMessageId` 是未锁定 user 消息，在原父节点下创建新的 user sibling，旧消息不变。
- `REGENERATE_ASSISTANT`：校验 `regeneratedMessageId` 是未锁定 assistant 消息，复用其父 user 消息，run 完成后创建新的 assistant sibling。

`current_leaf_message_id` 表示当前会话激活路径叶子。历史消息查询默认返回 root 到 current leaf 的路径；指定 `leafMessageId` 时返回 root 到该 leaf 的路径。前端通过 `variants` 查询同父节点候选，通过 `path` 接口切换当前激活版本。

复杂前端或联调排障可以调用 `GET /api/v1/ex/chat/sessions/{sessionId}/messages/tree` 读取完整可见消息树。该接口返回 `currentLeafMessageId`、`rootMessageIds` 和 `mapping`，但只包含业务可见的 user/assistant 消息，不返回 hidden system、raw log 或下游工具原始节点；普通聊天页继续使用 `/messages` active path。

```mermaid
flowchart TD
    RootUser["user: 原始问题"] --> A1["assistant: 第一次回答"]
    RootUser --> A2["assistant: 重新生成回答"]
    RootUserEdit["user: 编辑后的问题"] --> B1["assistant: 编辑后回答"]
    RootUser -. "same parent sibling" .- RootUserEdit
```

从某条消息新建会话分支时，服务端使用只读物化快照方案：沿 `parent_message_id` 回溯 root，复制该路径到新 session，复制出的消息写入 `source_session_id/source_message_id`，并设置 `origin_type=BRANCH_SNAPSHOT`、`locked=true`。快照消息不能编辑、删除或重新生成；分支后续新增的 `NORMAL` 消息可以继续参与消息树版本管理。分支不继承源会话 RuntimeBinding，避免把源会话 Runtime session 错接到新分支。

## 单轮问答分享

分享功能不复用实时 event，也不在访问时回源读取原始会话路径，而是在创建时生成固定展示快照：

```mermaid
sequenceDiagram
    autonumber
    participant Frontend as "Frontend"
    participant ShareAPI as "ChatShareController"
    participant ShareApp as "ChatShareApplicationService"
    participant Policy as "ChatShareAccessPolicy"
    participant Msg as "ChatMessageRepository"
    participant ShareRepo as "ChatShareRepository"
    participant Delivery as "ChatShareDeliveryProvider"
    participant DB as "数据库"

    Frontend->>ShareAPI: "POST /chat/messages/{assistantMessageId}/share"
    ShareAPI->>ShareApp: "create(UserContext, command)"
    ShareApp->>Msg: "加载 assistant 与父 user 消息、附件、visible parts"
    ShareApp->>Policy: "canCreate(user, assistant)"
    Policy-->>ShareApp: "允许或拒绝"
    ShareApp->>ShareRepo: "保存 ChatShare snapshot"
    ShareRepo->>DB: "写 fin_ex_chat_share_t"
    ShareApp-->>Frontend: "ChatShareDto"

    Frontend->>ShareAPI: "GET /chat/shares/{shareId}"
    ShareAPI->>ShareApp: "get(UserContext, shareId)"
    ShareApp->>ShareRepo: "读取分享快照"
    ShareApp->>Policy: "canView(user, share)"
    ShareApp-->>Frontend: "ChatShareDetailDto"

    Frontend->>ShareAPI: "POST /chat/shares/{shareId}/deliveries"
    ShareAPI->>ShareApp: "deliver(UserContext, deliveryCommand)"
    ShareApp->>Policy: "canDeliver(user, share)"
    ShareApp->>ShareRepo: "读取分享快照"
    ShareApp->>Delivery: "deliver(providerRequest)"
    Delivery-->>ShareApp: "SUCCESS / FAILED"
    ShareApp->>DB: "写 fin_ex_chat_share_delivery_t"
    ShareApp-->>Frontend: "ChatShareDeliveryDto"
```

`ChatShareAccessPolicy` 是分享鉴权防腐层，默认规则为同租户可查看、仅创建者可撤销和发送。后续如果企业权限框架需要按组织、部门、用户白名单或外部 ACL 判断分享访问，只替换该 port 的 Spring bean，不修改 Controller、分享表或快照构造逻辑。

`snapshot_json` 只保存父 user 问题、assistant 回答、问题附件展示快照和 `visible=true` 的 parts；不保存 feedback、raw log、隐藏/debug parts、Cookie、Authorization 或企业鉴权信息。附件快照只用于展示，不授予下载/预览权限。原会话后续编辑、重新生成、切换 path 或反馈变化都不会影响已经创建的分享。

分享发送通过 `ChatShareDeliveryProvider` 防腐层完成，首版 provider 为 `welink`。应用层只生成稳定的发送请求：分享人、标题、分享 URL、摘要、目标用户和目标群组；WeLink wire 字段如 `targetAccount/groupID` 只存在于 provider 实现中。发送失败不会回滚分享快照，只在 `fin_ex_chat_share_delivery_t` 中记录 `FAILED`、错误码和 provider 安全响应摘要，前端可按同一个 `shareId` 重试。分享发送使用 `financeex.share.delivery.max-concurrency` 做当前 JVM 内并发隔离，防止外部 provider 抖动时占满异步工作线程；WeLink provider 失败后默认最多重试 3 次，运行时最多按 10 次重试生效。

删除会话时，`SessionApplicationService` 会同步撤销当前用户创建的该会话 `ACTIVE` 分享，避免用户删除会话后外部仍访问其快照。

## 集成服务鉴权

外部 HTTP 调用统一通过 `AuthHeaderProviderRegistry` 获取服务对服务鉴权请求头。该能力是集成服务调用防腐层，不读取前端请求 ThreadLocal，也不要求前端传 token。默认 `financeex.integration-auth.enabled=false`，不会改变现有调用行为。

首版内置 `none` 和 `sgov` 两种 provider。`sgov` 只向出站 HTTP 请求注入 `Authorization` header，具体凭据获取由企业实现的 `SgovTokenResolver` bean 负责。本服务不会把 Authorization、服务 ID 或密钥写入请求 body、数据库、事件、raw log、metadata 或前端响应。

当前只预置以下 serviceCode：`welink-share`、`intent-service`、`use-case-library`、`sub-agent`。Relay Runtime、显式技能 legacy Agent 和 legacy 文档 provider 默认不走该鉴权层，仍保持现有 Cookie 透传或普通调用行为；后续如需启用，只新增 `financeex.integration-auth.services.<serviceCode>.provider=sgov` 配置。

```mermaid
sequenceDiagram
    autonumber
    participant Frontend as "Frontend"
    participant ChatAPI as "Chat HTTP API"
    participant ChatWS as "Chat WebSocket"
    participant SuperAgent as "FinanceEXChatService"
    participant EventStore as "ChatEventStore"
    participant RunStore as "ChatRunStore"
    participant Live as "LocalChatEventStreamRegistry"
    participant RedisBus as "Redis Pub/Sub"
    participant Runtime as "AgentRuntime Adapter"
    participant RelayAgent as "RelayAgent Service"
    participant DB as "数据库"

    Frontend->>ChatAPI: "POST /chat/runs"
    ChatAPI->>SuperAgent: "后台 start(command)"
    SuperAgent->>DB: "按 runMode 写入或定位消息树 user node"
    SuperAgent->>RunStore: "create RUNNING fin_ex_chat_run_t"
    SuperAgent->>EventStore: "append(run.started)"
    EventStore->>DB: "持久化 seq=firstSeq"
    EventStore-->>SuperAgent: "run.started(seq)"
    SuperAgent->>RunStore: "记录 firstSeq/lastSeq"
    SuperAgent->>Live: "publish(run.started)"
    ChatAPI-->>Frontend: "runId/sessionId/firstSeq/streamTopicId"

    par "后台 run 执行链路，由 /chat/runs 创建后在服务端推进"
        SuperAgent->>Runtime: "AgentRuntime.query"
        Runtime->>RelayAgent: "HTTP POST stream-path"
        RelayAgent-->>Runtime: "HTTP stream chunk"
        Runtime-->>SuperAgent: "标准 ChatEvent(message.delta/message.snapshot/runtime.*)"
        SuperAgent->>SuperAgent: "连续 delta 合并"
        SuperAgent->>EventStore: "guarded append(delta)"
        EventStore->>DB: "INSERT...SELECT 校验 run/session/execution 并生成 seq"
        SuperAgent->>Live: "publish persisted delta"
        SuperAgent->>RedisBus: "publish persisted delta"
        SuperAgent->>DB: "run.completed 后保存完整 assistant message 并更新 current leaf"
    and "前端实时订阅链路，只订阅 ChatEvent，不触发 Runtime query"
        Frontend->>ChatWS: "WS subscribe(topicId=streamTopicId, afterSeq=firstSeq)"
        ChatWS->>EventStore: "findByOwnerAndRunAfterSeq"
        EventStore->>DB: "补发 run 历史事件"
        ChatWS->>Live: "订阅本机 run topic"
        ChatWS->>RedisBus: "订阅远端 run topic"
        Live-->>ChatWS: "ChatEvent"
        ChatWS-->>Frontend: "WebSocket 实时事件"
    end

    opt "用户点击停止"
        Frontend->>ChatAPI: "POST /chat/runs/{runId}/stop"
        ChatAPI->>RunStore: "RUNNING -> CANCELLING + cancel flag"
        ChatAPI->>Runtime: "best-effort cancel"
        ChatAPI->>EventStore: "读取本 run 已落库 message.delta/snapshot"
        ChatAPI->>Session: "已有正文或用户可见 parts 时保存 partial assistant message"
        ChatAPI->>EventStore: "append(run.cancelled)"
        EventStore->>DB: "持久化取消终态 seq"
        ChatAPI->>RunStore: "CANCELLED + evict active run"
        ChatAPI->>Live: "publish(run.cancelled)"
        ChatAPI-->>Frontend: "status=CANCELLED/latestSeq"
    end

    Frontend--xChatAPI: "浏览器刷新/断线/换电脑"
    Frontend->>ChatAPI: "GET stream-status"
    ChatAPI-->>Frontend: "activeRunId/topic/firstSeq/latestSeq"
    Frontend->>ChatAPI: "Run Event Resume afterSeq=activeRunFirstSeq-1"
    ChatAPI->>DB: "按 owner + runId 补发缺失事件"
    ChatAPI->>Live: "接入 run live topic"
    ChatAPI->>RedisBus: "接入跨实例 run topic"
    ChatAPI-->>Frontend: "事件恢复 + live tail 到 run 终态"
```

关键约束：

- `fin_ex_chat_event_t.seq` 是前端恢复游标，实时输出和补发输出使用同一份 seq；该序号由数据库 sequence/default 生成并随事件写入一起返回，应用层不再本地生成恢复游标。
- 数据库是事件事实源，`LocalChatEventStreamRegistry` 是当前服务实例内在线发布器，Redis Pub/Sub 只做跨实例实时扇出。
- 事件写入必须校验 `runId/sessionId/tenantId/userId` 一致；事件补发和 `latestSeq` 查询也必须携带 `tenantId/userId/sessionId/runId` owner 条件，不能按裸 runId 或 sessionId 查询。
- `fin_ex_chat_run_t` 是 run 生命周期事实源；Redis 只保存 active run 和 cancel flag。
- `fin_ex_chat_run_execution_t` 是 run 执行控制面事实源；实例 ID、心跳、租约、恢复状态和 `fencing_token` 都在该表中，避免把运维执行信息混入业务 run 表。
- 后台 run 不依赖创建 run 的原始浏览器连接，刷新页面后用 `afterSeq` 恢复。
- 前端 WebSocket 订阅消息格式：`{"type":"subscribe","topicId":"chat-run-{runId}","afterSeq":0}`。
- 前端 WebSocket 不触发 `AgentRuntime.query`，只补发和订阅 ChatEvent；它不接受聊天请求，仅支持 `connect`、`presence`、`subscribe`、`unsubscribe` 控制消息。
- 同一 WebSocket 连接允许同时订阅多个 session 的多个 run topic；协议层不会因切换会话自动释放旧 topic。订阅前按用户校验 `topicId -> run` 归属，live 流和 WebSocket envelope 投递前再按 `topicId + runId + sessionId` 校验，避免跨会话实时消息串线。
- stop 是 REST 生命周期接口，不是 WebSocket command；重复 stop 幂等返回当前 run 状态。
- 重新生成回答不再使用 run retry 接口，而是通过 `POST /chat/runs` 携带 `runMode=REGENERATE_ASSISTANT` 和 `regeneratedMessageId`，在同一 user 节点下生成新的 assistant sibling。
- 会话 state 接口聚合会话元数据、最近历史消息和 `activeStreamTopicId`，用于前端切换会话后的恢复判断。
- 新页签、新浏览器或跨电脑恢复 active run 时，前端应使用 `activeRunFirstSeq - 1` 打开 run 级事件恢复；该接口会先按数据库事实源补发历史事件，再接入 live topic 持续输出到 run 终态，不能把 `latestSeq` 当作当前渲染实例已消费游标。

### Run 控制面与故障恢复

后台 run 的业务状态和执行状态分离：

- `fin_ex_chat_run_t`：业务生命周期事实源，记录 run 状态、路由类型、Runtime 信息、first/last seq 和取消原因。
- `fin_ex_chat_run_execution_t`：运行控制面事实源，记录当前 owner 实例、心跳、租约、恢复状态、恢复租约和 `fencing_token`。

控制面初始化是 run 启动的必备步骤。若业务 run 已写入 `fin_ex_chat_run_t`，但创建
`fin_ex_chat_run_execution_t` 失败，服务端不会继续调用 Runtime/SubAgent，而是直接追加
`run.failed` 终态事件，payload code 为 `RUN_EXECUTION_INIT_FAILED`，并释放 active run。
此时没有可用 execution claim，因此不能绕过 fencing 继续输出。

```mermaid
sequenceDiagram
    autonumber
    participant Runner as "执行实例"
    participant ExecStore as "fin_ex_chat_run_execution_t"
    participant Watchdog as "任意实例 Watchdog"
    participant Lock as "Redis recover lock"
    participant EventStore as "fin_ex_chat_event_t"
    participant RunStore as "fin_ex_chat_run_t"

    Runner->>ExecStore: "create RUNNING execution, fencing_token=1"
    loop "heartbeat"
        Runner->>ExecStore: "owner + token 续租 lease_until"
    end
    Runner--xExecStore: "实例宕机，心跳停止"
    Watchdog->>ExecStore: "扫描 lease_until 过期"
    Watchdog->>Lock: "try lock fin_ex:{env}:chat_run:recover_lock:{runId}"
    Watchdog->>ExecStore: "条件抢占为 RECOVERING 并递增 fencing_token"
    alt "MANUAL_CONFIRMATION / FAIL_FAST"
        Watchdog->>EventStore: "append(run.failed)"
        Watchdog->>RunStore: "RUNNING -> FAILED，释放 active run"
        Watchdog->>ExecStore: "execution -> FAILED"
    else "RUNTIME_TAKEOVER supported"
        Watchdog->>ExecStore: "切换 owner，刷新 lease，保持 RUNNING"
        Watchdog->>EventStore: "append(run.recovered)"
        Watchdog->>Runner: "启动新的 Runtime subscription"
    end
    Runner->>ExecStore: "旧实例恢复后按旧 token 写事件"
    ExecStore-->>Runner: "fencing 校验失败，拒绝迟到事件"
```

watchdog 是分层设计：`ChatRunWatchdogScheduler` 只负责按配置延迟、jitter 和周期触发；`ChatRunRecoveryOrchestrator` 负责候选拉取、容量检查、策略选择和指标日志；`ChatRunExecutionRepository` 负责数据库条件抢占；`StaleRunRecoveryStrategy` 负责具体恢复动作。默认策略链为 `MANUAL_CONFIRMATION,FAIL_FAST`，`RUNTIME_TAKEOVER` 仅在 Runtime 明确支持可靠恢复并提供 resume token 时使用。

所有治理类 `@Scheduled` 任务使用 `financeex.scheduler.pool-size` 配置的线程池调度器，避免 watchdog jitter 或慢巡检阻塞 run heartbeat、WebSocket 空闲清理和准入窗口清理。实例 ID 默认由 `GeneratedApplicationInstanceIdProvider` 在进程启动时生成；如需对接注册中心，提供新的 `ApplicationInstanceIdProvider` bean 即可替换默认实现。

恢复负载治理包含四层保护：每轮扫描候选上限、每轮最大 claim 上限、每租户 claim 上限、本机恢复和 takeover semaphore。没有恢复容量时不抢占，留给下一轮或其他实例处理，避免单实例在大批 stale run 场景下被恢复任务压垮。

### WebSocket 边界说明

当前系统只保留前端到 FinanceEXChatService 的 WebSocket：

- 前端 WebSocket：`/api/v1/ex/chat/ws`，只连接 FinanceEXChatService。它是用户级连接，按 run 级 `streamTopicId` 订阅已经写入事件事实源的 ChatEvent；它不接受聊天请求，也不直接调用 RelayAgent。

因此架构图中的 `AgentRuntime.query` 是应用层防腐层调用，不等价于前端 WebSocket。当前 `AgentRuntime.query` 使用 Relay HTTP 流式 adapter；FinanceEXChatService 到 RelayAgent 的出站 WebSocket adapter 已从正式版移除。

前端 WebSocket 的服务端入口按启动模式自动切换：

- Reactive WebFlux 应用：`ChatWebSocketConfig + ChatWebSocketHandler` 注册同一路径。
- Servlet/MVC 应用：`ChatServletWebSocketConfig + ChatServletWebSocketHandler` 注册同一路径。

两种 handler 都委托 `ChatWebSocketProtocolService` 执行 connect、presence、subscribe、
unsubscribe 和 recover-required 逻辑，避免协议实现分叉。企业框架自带
`spring-boot-starter-web` 时，Spring Boot 会默认选择 Servlet/MVC 启动，此时应使用
`server.servlet.context-path` 配置上下文根；纯 WebFlux 启动时才使用 `spring.webflux.base-path`。
Servlet/MVC WebSocket 会在 `HandshakeInterceptor.beforeHandshake` 阶段调用
`AuthContextProvider.resolve()`，并把不可变 `UserContext` 写入 WebSocket session attributes；
`afterConnectionEstablished` 和后续消息处理只读取该快照，不再访问企业 ThreadLocal。

MVC/Servlet 生产模式增加了长连接治理层：`financeex.websocket.allowed-origin-patterns`
限制握手来源，`max-connections-per-user`、`max-subscriptions-per-connection`、
`max-subscribers-per-topic`、`outbound-queue-size`、`live-buffer-capacity` 和 `idle-timeout`
限制本机连接资源。WebSocket 与 run 级事件恢复通过 `financeex.chat-stream.turn-heartbeat-interval`
发送 turn stream `heartbeat`，配合 `spring.mvc.async.request-timeout` 与 Tomcat 连接配置避免空闲断流。WebSocket 实时投递
出现慢客户端、缓冲溢出或乱序时返回 `RECOVER_REQUIRED`，可靠恢复仍走数据库事件 + Event Resume。
前端接收的 `message.payload` / SSE `data` 是 `conversation-turn-stream`，真实 ChatEvent 位于
`stream-item.encodedItem.data`；`heartbeat` 和 `done` 只是传输层状态，不写入 `fin_ex_chat_event_t`，
也不推进 `afterSeq`。

文档上传同样按启动模式做接口层适配：Servlet/MVC 注册 `MvcDocumentUploadController`
并接收 `MultipartFile`，纯 WebFlux 注册 `ReactiveDocumentUploadController` 并接收
`FilePart`。两种 Controller 都委托 `DocumentUploadSupport`，由它先把上传流写入临时文件，
再通过 `DocumentFacade -> DocumentProviderAdapterRegistry` 选择 default-storage、legacy-agent
或未来领域 Agent provider。前端看到的路径、字段和响应始终是同一套
`POST /api/v1/ex/documents` 契约。legacy-agent 等 HTTP provider 可通过 `forward-cookie=true`
允许上传入口 Cookie 作为下游 upload HTTP header 透传；普通对象存储 provider 不使用该 Cookie，
且 Cookie 不进入 multipart form、文档元数据或前端响应。

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
        IntentRecord["IntentRecognitionRecordService"]
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
        MyBatis["数据库 + MyBatis"]
        LiveBus["Redis Pub/Sub ChatLiveEventBus"]
        UseCaseHttp["UseCase HTTP Adapter"]
        IntentHttp["Intent HTTP Adapter"]
        SubAgentHttp["SubAgent HTTP Adapter"]
        RelayRuntime["RelayAgentRuntime Provider"]
        RelayHttp["RelayStreamHttpRuntimeAdapter"]
        Storage["Local / Huawei OBS S3 ObjectStorage"]
        HttpDocumentProvider["HTTP DocumentProviderAdapter"]
        LegacySkillHttp["LegacySkill HTTP Adapter"]
    end

    Interfaces --> ChatService
    ChatService --> RouteSignal
    ChatService --> IntentRecord
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
    IntentRecord --> MyBatis
    ChatRun --> Redis
    ChatRun --> MyBatis
    StreamService --> LiveBus
    RuntimeBinding --> Redis
    RuntimeBinding --> MyBatis
    SubAgentExecutor --> SubAgentHttp
    ChatService --> LegacySkillHttp
    RuntimeExecutor --> RelayRuntime
    RelayRuntime --> RelayHttp
    DocumentService --> Storage
    DocumentService --> HttpDocumentProvider
    Application --> Domain
```

## 路由规则

- `metadata.selectedSkillId` 优先级最高；存在时进入 `EXPLICIT_SKILL` 路由，直接调用老 Agent 指定技能，不读取或创建 RuntimeBinding。
- active RuntimeBinding 优先级次之；存在时本轮按当前消息树 leaf 续接 Relay Runtime。
- 用例库和意图服务是可选路由信号，默认关闭；关闭时不调用外部 API。
- 用例库开启时优先匹配；命中阈值默认 `0.85`，命中并返回 `subAgentCode` 后单轮调用 SubAgent。
- 用例库关闭或未命中后，只有意图服务开启才调用 `IntentService`。
- 意图服务 adapter 的 HTTP 请求体和响应体转换由 infrastructure intent mapper 承载；当前解析 `code/data/result/items[]` 包装响应，选择最高 `confidence` 的 item，并把 `resourceInstruction.resourceId` 映射为 `candidateSubAgentCode`。
- 意图服务调用失败后默认最多重试 3 次；配置误设过大时运行时最多按 10 次生效，重试耗尽后仍按原有降级策略进入 Relay Runtime。
- 意图服务返回简单任务、`confidence >= financeex.intent.confidence-threshold` 且有 `candidateSubAgentCode` 时单轮调用 SubAgent。
- `financeex.intent-record.enabled=true` 时，只有实际调用过意图服务的 run 会异步写入 `fin_ex_intent_recognition_t`。记录内容包含本轮 query、候选 items、最高置信结果、最终路由是否采纳和意图服务耗时；显式技能、RuntimeBinding 续接、用例库已命中、意图服务关闭时不会记录。
- 两个信号均关闭、服务失败、复杂、低置信或缺少 SubAgent 的任务进入 Relay Runtime。
- SubAgent 没有续接机制；如果用户下一轮继续提问，除非已经进入 Relay Runtime，否则重新走路由信号。

## RuntimeBinding

RuntimeBinding 只维护前端 chat session、当前消息树 leaf 与当前 AgentRuntime provider session 的关系。当前上线默认 provider 是 `relay`。leaf 维度隔离可以避免编辑历史问题、切换版本或从历史消息新建分支时误用另一条路径的 Runtime session。

```text
Redis key:
fin_ex:{env}:runtime_binding:{tenantId:userId:sessionId}:{leafMessageId}
fin_ex:{env}:runtime_binding:index:{tenantId:userId:sessionId}

数据库表:
fin_ex_runtime_binding_t
```

字段包括：

```text
id
tenant_id
user_id
chat_session_id
provider
leaf_message_id
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
fin_ex:{env}:chat_run:active:{tenantId}:{userId}:{sessionId}
fin_ex:{env}:chat_run:cancel:{runId}
fin_ex:{env}:chat_stream:{streamTopicId}
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

- 集群事实源优先：stop 先写 Redis cancel flag 与数据库 `CANCELLING` 状态，再发布 `run.cancelled`。
- JVM subscription registry 只是本机资源释放加速器；即使 stop 请求与输出流落在不同实例，输出实例也必须在追加事件前读取 Redis cancel flag。非终态事件不再逐条回源 run 表，最终写入正确性由数据库 guarded insert 同时校验 run 状态、session 归属和 execution fencing。
- 用户主动 stop 且已有 assistant 正文或用户可见 runtime parts 成功落库时，会从事件事实源重建并保存 partial assistant 历史消息，消息 metadata 标记 `partial=true`、`finishReason=USER_STOP`；只有 trace、legacy session 等内部 metadata 时不创建空 assistant。
- 下游尽力取消：Relay Runtime 和 SubAgent cancel 失败只记录日志，不影响前端收到取消终态。
- stop 不取消 RuntimeBinding；下一轮仍可续接 Runtime，除非请求 metadata 使用 `forceNewTask=true`。

## 外部 API 接入

- 用例库服务：`financeex.use-case-library.enabled`、`financeex.use-case-library.base-url`、`financeex.use-case-library.match-path`
- 意图服务：`financeex.intent.enabled`、`financeex.intent.base-url`、`financeex.intent.recognize-path`、`financeex.intent.confidence-threshold`、`financeex.intent.timeout`、`financeex.intent.max-retries`
- 意图识别记录：`financeex.intent-record.enabled`、`max-query-length`、`max-raw-json-length`、`executor.*`。默认关闭；开启后使用 Servlet/MVC 友好的专用线程池 best-effort 写库，线程池拒绝、JSON 序列化失败或数据库写入失败都只记录 warn，不阻塞 `/chat/runs`。
- SubAgent：`financeex.sub-agent.agents.{agentCode}.endpoint`
- SubAgent stop：`financeex.sub-agent.agents.{agentCode}.stop-endpoint`
- AgentRuntime provider：`financeex.agent-runtime.provider`，表示 Runtime 类型，当前默认 `relay`
- Relay HTTP Streamable adapter：`financeex.agent-runtime.base-url`、`financeex.agent-runtime.stream-path`、`financeex.agent-runtime.stop-path`
- 下游 Cookie 透传：`financeex.agent-runtime.forward-cookie.enabled`、`max-length`、`allowed-adapters` 控制 run/stop 到 Relay Runtime 的 Cookie 透传；显式技能 legacy Agent chat/cancel 也使用入口 Cookie 内存快照。文档 provider 上传另由 `financeex.document.forward-cookie-max-length` 与 `financeex.documents.providers.entries.{provider}.forward-cookie` 控制，默认只有 `legacy-agent` 开启 upload Cookie 透传。
- 流式 delta 合并：`financeex.chat-stream.delta-coalesce-enabled`、`delta-coalesce-window`、`delta-coalesce-max-chars`。默认开启，只把连续 `message.delta` 合并为标准 delta event，降低事件表和实时 fanout 写放大；`message.snapshot` 和 `runtime.progress/runtime.metadata/runtime.agent/runtime.thinking/runtime.tool/runtime.reference/runtime.card/runtime.event` 等非正文事件不会被合并。`financeex.chat-stream.turn-heartbeat-interval` 只控制传输层 heartbeat，不影响事件表。
- Legacy 大对象分片：`financeex.legacy-skill.max-pending-frame-bytes` 限制尚未识别完成的单个 legacy frame 缓冲，`financeex.legacy-skill.max-fragment-bytes` 限制 `runtime.card/runtime.reference/runtime.progress` 分片 payload 的单片大小。该机制避免 `diyCardScene/openCard/searchList/sourcesDocuments/processResult` 跨网络 chunk 时被误解析为 invalid-json，也避免为了完整 JSON 解析无限占用 JVM 内存。分片状态通过 `payload.fragment/itemId/delta/complete` 表达，不新增顶层 `.delta/.completed` 事件类型。
- Runtime 原始流日志：`financeex.runtime-raw-log.enabled`、`transport`、`coalesce-window`、`max-chars`、`hard-max-chars`、`max-rows-per-run`、`redact-sensitive-fields`。默认关闭；后续接入企业 MQ 时通过 `RuntimeRawStreamLogPublisher` 发布 raw chunk，消费端异步合并、脱敏、分片后写入 `fin_ex_runtime_raw_stream_log_t`。该日志仅用于排障，不参与前端恢复、WebSocket 推送或 assistant 历史拼接。
- Relay 响应映射：`financeex.agent-runtime.relay.answer-event-types`、`answer-content-fields`、`agent-context-as-answer`。默认把 Relay `type=agent,is_streaming=true` 的 `content/context` 映射为 assistant 正文增量 `message.delta`，把 `type=agent,is_streaming=false` 映射为最终回答快照 `message.snapshot`，把 `steam-complete/stream-complete/[DONE]` 映射为 `message.completed`。

SubAgent 当前只支持单轮 HTTP 文本流调用。当前上线版本内置一个 `RelayAgentRuntime` provider 和一个 `RelayRuntimeProtocolAdapter`：`relay-stream-http` 是 Relay HTTP 流式协议实现。新增下游协议时，应新增 adapter，而不是在 `RelayAgentRuntime` 主类里堆转换分支；未经过评审的出站 WebSocket adapter 不在正式版保留。

`Cookie` 是请求入口捕获的运行期内存快照，只会在 `AgentRuntimeRequest.forwardHeaders`、`LegacySkillAgentRequest.forwardHeaders`、`DocumentUploadCommand.forwardHeaders` 或 cancel 请求中向可信 adapter 传递；这些字段被 JSON 序列化忽略，且 adapter 会把内部请求映射为专用 wire DTO 或受控 multipart，不能进入下游请求体、form 字段、文档元数据、run metadata、事件 payload 或日志。该设计保证企业登录态不会因后台 run、Event Resume/WS 恢复、文档库管理或故障治理被持久化或回放。

当前上线版本只保留 Relay Runtime provider，不包含其他历史 Runtime provider 分支、专用 memory 分支或专用 prompt assembler 配置。复杂任务通过 Relay Runtime adapter 执行；后续如需替换 Runtime，应新增 `AgentRuntime` provider，而不是把新协议写进主编排。

AgentRuntime 防腐层仍然保留。应用层只依赖 `AgentRuntime` port 和 `AgentRuntimeRequest` 契约，当前 `relay` provider 是 Runtime 类型，下游 API 接入协议固定为 streamable HTTP。Relay adapter 内部负责请求 wire DTO 映射和响应 chunk 归一化，前端只通过 turn stream 的 `encodedItem.data` 消费 ChatService 标准 ChatEvent，不接触 Relay 原始 JSON。`message.delta` 是 assistant 正文增量；`message.snapshot` 是最终回答快照，前端 replace 草稿且历史正文优先使用它；`runtime.progress/runtime.metadata/runtime.agent/runtime.thinking/runtime.tool/runtime.reference/runtime.card` 是过程、引用或卡片事件，run 完成后进入 `ChatMessageDto.parts` 回显，parts 通过 `title/status/channel/displayHint/visible` 提供稳定展示语义；`run.completed.payload.messageReady=true` 时会携带 `assistantMessageId/feedbackTargetMessageId`，这是前端启用点赞点踩的唯一实时信号；大对象分片通过 payload 中的 `fragment/itemId/delta/complete` 表达。Relay 或 legacy-agent 未知 JSON object 才以 `runtime.event` 可控透传，`sourcePayload` 会脱敏限长。不能把下游任意 `type` 直接作为 ChatService 顶层事件类型。后续如果替换 Runtime 实现，应新增一个实现 `AgentRuntime` 的 provider；后续如果只新增 Relay 下游协议，应新增 `RelayRuntimeProtocolAdapter`，避免改动 `FinanceEXChatService` 主编排。

## 命名规范

所有表名必须匹配：

```text
^fin_ex_.*_t$
```

当前表：

- `fin_ex_chat_session_t`
- `fin_ex_chat_message_t`
- `fin_ex_chat_message_attachment_t`
- `fin_ex_chat_run_t`
- `fin_ex_chat_run_execution_t`
- `fin_ex_chat_event_t`
- `fin_ex_runtime_raw_stream_log_t`
- `fin_ex_uploaded_document_t`
- `fin_ex_message_feedback_t`：当前用户对 assistant 消息的点赞/点踩状态，支持 ACTIVE/CANCELLED。
- `fin_ex_chat_share_t`：单轮问答分享固定快照，支持 ACTIVE/REVOKED、过期和创建者撤销。
- `fin_ex_chat_share_delivery_t`：分享发送记录，保存发送 provider、目标、分享链接和 SUCCESS/FAILED 结果。
- `fin_ex_runtime_binding_t`

Redis key 必须以 `fin_ex:{env}` 开头。`{env}` 从 `spring.profiles.active` 第一个 profile 自动注入，
无 active profile 时为 `default`，非 `[a-z0-9_-]` 字符会规范化为 `_`：

- `fin_ex:{env}:runtime_binding:{tenantId:userId:sessionId}:{leafMessageId}`
- `fin_ex:{env}:runtime_binding:index:{tenantId:userId:sessionId}`
- `fin_ex:{env}:chat_run:active:{tenantId}:{userId}:{sessionId}`
- `fin_ex:{env}:chat_run:cancel:{runId}`
- `fin_ex:{env}:chat_run:recover_lock:{runId}`
- `fin_ex:{env}:chat_stream:{streamTopicId}`
- `fin_ex:{env}:memory:short_term:messages:{tenantId}:{userId}:{sessionId}`

同一会话的 active run 互斥由 Redis active key 和数据库 run 状态共同保护。创建 run 前会先查询
当前 active run，真正写入 `RUNNING` run 时通过 Redis set-if-absent 声明
`fin_ex:{env}:chat_run:active:{tenantId}:{userId}:{sessionId}`；声明失败时返回 `ACTIVE_RUN_EXISTS`。
run 进入 `COMPLETED/FAILED/CANCELLED` 后释放 active key。Redis 不可用时可退化为数据库
active run 检查，但生产集群应保证 Redis Cluster 可用以降低并发竞态窗口。

Redis 部署模式由 `financeex.redis.mode` 控制，默认 `standalone`；生产 Redis Cluster 设置为
`cluster` 并配置 `financeex.redis.cluster.nodes`。配置文件中的 Redis prefix 仍是逻辑前缀
`fin_ex:...`，不要手写 env，避免形成 `fin_ex:dev:dev:...` 这类双重环境段。RuntimeBinding 使用
Redis hash tag 保证同一会话 binding key 和索引集合落在同一 slot；env 位于 hash tag 外面，不影响
同 slot 设计，因此会话级清理不使用 `KEYS`，只通过索引集合删除明确 key。
ChatLiveEventBus 在本机出现 run topic 订阅者时动态订阅对应 Redis channel，Redis Pub/Sub 仍然只做
跨实例实时 fanout，可靠恢复继续依赖数据库事件 + Event Resume。

## 可选记忆上下文

- `financeex.memory.short-term.enabled=false` 时，不装配最近问答，也不访问 Redis 短期记忆缓存。
- `financeex.memory.short-term.recent-turns=5` 表示短期记忆开启后读取最近 5 轮问答，即最多 10 条消息。
- `financeex.memory.short-term.cache-enabled=true` 表示短期记忆开启时优先使用 Redis 热缓存，miss 后回源数据库。
- `financeex.memory.long-term.enabled=false` 时，不调用长期记忆服务。
- `financeex.memory.long-term.provider=disabled` 是默认安全 provider，开启长期记忆但未接真实服务时返回空结果。
