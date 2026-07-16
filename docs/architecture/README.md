# FinanceEXChatService 正式版架构设计

## 架构目标

FinanceEXChatService 是前端聊天入口和 SuperAgent 主控服务。正式版只保留清晰的执行边界：

- DomainAgent 任务：用例库、意图服务或前端显式选择命中后，绑定会话级 DomainAgent，并由 DomainAgent 维护自己的下游会话上下文。
- Relay Runtime 任务：复杂任务和未命中任务进入 Relay Runtime，并由 Relay Runtime 负责多轮、规划、上下文和压缩。
- SuperAgent：负责身份、会话、可选记忆上下文装配、路由、事件落库和 RuntimeBinding 续接。

## 全局流程图

```mermaid
flowchart TD
    User["用户请求"] --> Normalize["身份解析与会话归一化"]
    Normalize --> Memory["按配置加载 MemoryContext"]
    Memory --> ExplicitSkill{"targetType=DOMAIN_AGENT?"}
    ExplicitSkill -- "是" --> DomainAgent["DOMAIN_AGENT：DomainAgent 指定调用"]
    ExplicitSkill -- "否" --> FindRuntime["按会话查询 RuntimeBinding"]

    FindRuntime --> HasRuntime{"存在 active RuntimeBinding?"}
    HasRuntime -- "是：domain-agent" --> BoundDomainAgent["续接绑定 DomainAgent"]
    HasRuntime -- "是：relay" --> TouchRuntime["刷新 RuntimeBinding"]
    BoundDomainAgent --> EventStream["输出 ChatEvent 流"]
    TouchRuntime --> RuntimeQuery["AgentRuntime.query 防腐层"]

    HasRuntime -- "否" --> RouteSignal["RouteSignalApplicationService"]
    RouteSignal --> SignalEnabled{"用例库或意图服务已开启?"}
    SignalEnabled -- "否" --> CreateRuntime["创建 RuntimeBinding"]
    CreateRuntime --> RuntimeQuery

    SignalEnabled -- "是" --> UseCaseEnabled{"用例库开启?"}
    UseCaseEnabled -- "是" --> UseCase["UseCaseLibraryClient.match"]
    UseCaseEnabled -- "否" --> IntentEnabled{"意图服务开启?"}
    UseCase --> UseCaseHit{"命中且分数达标且有 DomainAgentId?"}
    UseCaseHit -- "是" --> BindDomainAgent["绑定并调用 DomainAgent"]
    UseCaseHit -- "否" --> IntentEnabled

    IntentEnabled -- "是" --> Intent["IntentAgentRuntime.route"]
    IntentEnabled -- "否" --> CreateRuntime
    Intent --> IntentRoute{"意图路由结果"}
    IntentRoute -- "命中 DomainAgentId" --> BindDomainAgent
    IntentRoute -- "不支持任务" --> SystemResponse["SYSTEM_RESPONSE"]
    IntentRoute -- "ROUTE_MULTI/NO_MATCH" --> CreateRuntime
    IntentRoute -- "技术/协议失败 + RELAY_FALLBACK" --> CreateRuntime
    IntentRoute -- "技术/协议失败 + FAIL_RUN" --> IntentFailed["run.failed: INTENT_ROUTING_FAILED"]

    BindDomainAgent --> EventStream
    DomainAgent --> EventStream
    RuntimeQuery --> RelayWS["RelayWebSocketRuntimeAdapter"]
    RelayWS --> EventStream
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
    participant DomainAgent as "DomainAgent"
    participant Runtime as "AgentRuntime Port"
    participant RelayWS as "RelayWebSocketRuntimeAdapter"
    participant RelayAgent as "RelayAgent Service"
    participant Stream as "ChatStreamApplicationService"
    participant Redis as "Redis"
    participant DB as "数据库"
    participant EventStore as "ChatEventStore"

    Frontend->>API: "POST /v1/chat/runs"
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

    alt "targetType=DOMAIN_AGENT"
        SuperAgent->>SuperAgent: "RouteTarget.DOMAIN_AGENT，绑定 routeSource=front-selected"
        SuperAgent->>DomainAgent: "chat(boundDomainAgentId, query, metadata)"
    else "未显式指定 DomainAgent"
        SuperAgent->>Binding: "findActive(sessionId)"
        Binding->>Redis: "读取 RuntimeBinding"
        alt "Redis miss"
            Binding->>DB: "查询 fin_ex_runtime_binding_t"
            Binding->>Redis: "回填 active RuntimeBinding"
        end
    end

    alt "存在 active DomainAgent RuntimeBinding"
        SuperAgent->>Binding: "touchForRun(runId)"
        Binding->>DB: "刷新 last_run_id 与 expires_at"
        SuperAgent->>DomainAgent: "chat(boundDomainAgentId, query, metadata)"
    else "存在 active Relay RuntimeBinding"
        SuperAgent->>Binding: "touchForRun(runId)"
        Binding->>DB: "刷新 last_run_id 与 expires_at"
        Binding->>Redis: "刷新 RuntimeBinding TTL"
        SuperAgent->>Runtime: "AgentRuntime.query(runtimeSessionId, message)"
        Runtime->>RelayWS: "delegate"
        RelayWS->>RelayAgent: "WebSocket config(RESUME) -> user-message"
    else "不存在 active RuntimeBinding"
        SuperAgent->>Signal: "routeInitial(command, memory)"
        alt "用例库或意图服务命中 DomainAgent"
            Signal->>UseCase: "可选 match"
            Signal->>Intent: "可选 recognize"
            Signal-->>SuperAgent: "DOMAIN_AGENT(domainAgentId)"
            SuperAgent->>Binding: "bindDomainAgent(routeSource)"
            SuperAgent->>DomainAgent: "chat(domainAgentId, query, metadata)"
        else "未命中/关闭/复杂任务"
            Signal-->>SuperAgent: "AGENT_RUNTIME"
            SuperAgent->>Binding: "create(runId)"
            Binding->>DB: "写入 fin_ex_runtime_binding_t"
            Binding->>Redis: "缓存 RuntimeBinding"
            SuperAgent->>Runtime: "AgentRuntime.query(message)"
            Runtime->>RelayWS: "delegate"
            RelayWS->>RelayAgent: "WebSocket config(NEW/RESUME) -> user-message"
        else "不支持任务"
            Signal-->>SuperAgent: "SYSTEM_RESPONSE"
            SuperAgent->>SuperAgent: "生成可控系统回复"
        end
    end

    loop "输出 ChatEvent"
        alt "DomainAgent route"
            DomainAgent-->>SuperAgent: "message.delta / message.snapshot / runtime.* / message.completed"
        else "Explicit skill route"
            RelayAgent-->>SuperAgent: "DomainAgent eventStream -> message.delta / runtime.* / message.completed"
        else "Relay WebSocket Runtime route"
            RelayAgent-->>RelayWS: "WebSocket text frame"
            RelayWS-->>Runtime: "ChatEvent"
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

下面的简化图只保留核心外部参与方和事实源，适合做整体链路评审。它省略了应用层内部服务、DomainAgent 细节、WebSocket/Event Resume handler 细节和 Runtime adapter 细节；前端 WebSocket 接入仍然保留，只是不在本图展开。

```mermaid
sequenceDiagram
    autonumber
    participant Frontend as "前端"
    participant EX as "EXChatService"
    participant UseCase as "案例库"
    participant Intent as "意图服务"
    participant DomainAgent as "下游 DomainAgent"
    participant Relay as "下游 RelayAgentRuntime"
    participant Redis as "Redis"
    participant DB as "数据库"
    participant S3 as "S3 / OBS"

    opt "上传文档"
        Frontend->>EX: "POST /v1/documents"
        alt "storage.provider=local/huawei-s3"
            EX->>S3: "写入文件对象"
            S3-->>EX: "bucket/objectKey"
        else "storage.provider=api-store"
            EX->>EX: "调用新文档上传接口(file, metadata.skillId?)"
        end
        EX->>DB: "写入 fin_ex_uploaded_document_t"
        EX-->>Frontend: "documentId/status"
    end

    Frontend->>EX: "POST /v1/chat/runs"
    EX->>DB: "创建/读取 session，写 user message、run、run.started"
    EX->>Redis: "写 active run / runtime binding / stream topic 热数据"

    opt "用例库开启"
        EX->>DB: "append runtime.progress(route-progress/use_case_matching)"
        EX->>UseCase: "match(query, context)"
        UseCase-->>EX: "matched/domainAgentId/score 或未命中"
    end

    opt "意图服务开启且用例未命中"
        EX->>DB: "append runtime.progress(intent-start/intent_calling)"
        EX->>Intent: "recognize(query, context)"
        Intent-->>EX: "routeAction/items/clarification"
        EX->>DB: "append runtime.progress(intent-result)"
    end

    alt "targetType=DOMAIN_AGENT"
        EX->>DomainAgent: "绑定前端指定 DomainAgent 并调用"
    else "存在 DomainAgent 绑定或路由信号命中"
        EX->>DomainAgent: "续接/绑定 DomainAgent"
    else "进入 Relay Runtime"
        EX->>Relay: "AgentRuntime.query(sessionId, query, attachments, Cookie snapshot)"
        Relay-->>EX: "message.delta / message.snapshot / runtime.* / message.completed"
    end

    loop "流式事件"
        EX->>DB: "写入 fin_ex_chat_event_t，生成 seq"
        EX->>Redis: "publish fin_ex:{env}:chat_stream:{topicId}"
        EX-->>Frontend: "WebSocket message 或 Event Resume event"
    end

    opt "停止回答"
        Frontend->>EX: "POST /v1/chat/runs/{runId}/stop"
        EX->>Redis: "写 cancel flag"
        EX->>DB: "run -> CANCELLING/CANCELLED，写 run.cancelled"
        EX->>Relay: "best-effort cancel"
        EX-->>Frontend: "ChatRunStopDto"
    end
```

## 文档库与附件使用

文档能力采用“统一后端入口 + DocumentStorage 防腐层 + 文档库资产”的模式。

```mermaid
sequenceDiagram
    autonumber
    participant Frontend as "Frontend"
    participant DocAPI as "Document API"
    participant DocApp as "DocumentApplicationService"
    participant Storage as "DocumentStorage"
    participant ObjectStorage as "ObjectStorage"
    participant ApiStore as "api-store"
    participant DB as "数据库"
    participant Chat as "FinanceEXChatService"
    participant Executor as "DomainAgent / AgentRuntime"

    Frontend->>DocAPI: "POST /documents multipart file,metadata?"
    DocAPI->>DocAPI: "AuthContextProvider.resolve()"
    DocAPI->>DocApp: "upload(UserContext, DocumentUploadCommand)"
    DocApp->>DocApp: "会话归属校验"
    DocApp->>Storage: "upload by financeex.storage.provider"
    alt "local / huawei-s3"
        Storage->>ObjectStorage: "putObject(tenantId, file)"
        ObjectStorage-->>Storage: "bucket/objectKey"
    else "api-store"
        Storage->>ApiStore: "multipart file + optional metadata.skillId"
        ApiStore-->>Storage: "docId 或 url"
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
- 真实文件内容由 `DocumentStorage` 决定去向：`local/huawei-s3` 写入本服务对象存储，`api-store` 转发新文档上传接口。
- 聊天请求只引用 `documentId`，不携带文件正文。
- DomainAgent 或 Runtime 看到的是经过文档库回查后的可信附件元数据。
- `fin_ex_uploaded_document_t` 是文档库事实源，支持最近文档、库中文档选择和后续连接器文档扩展。

## 流式响应与断点恢复

正式版只保留后台 run 创建模式。`POST /v1/chat/runs` 是唯一任务提交入口：普通提问、编辑历史 user、重新生成 assistant，以及等待态 Interaction 续接都走该接口，通过 `runMode` 区分。
本页新创建的 run 默认通过 WebSocket topic 接实时事件；新页签、新浏览器或跨电脑恢复已经存在的 active run 时，使用 run 级事件恢复先补发历史事件，再持续接续 live 事件直到本轮 run 终态。会话级事件恢复 仍只负责有限缺失事件补发。

```text
POST /v1/chat/runs
POST /v1/chat/messages/{messageId}/feedback
DELETE /v1/chat/messages/{messageId}/feedback
POST /v1/chat/messages/{messageId}/share
GET  /v1/chat/shares/{shareId}
DELETE /v1/chat/shares/{shareId}
GET  /v1/chat/shares?curPage=1&pageSize=20
POST /v1/chat/sessions
GET  /v1/chat/sessions?appId=...&limit=20&cursor=...
GET  /v1/chat/sessions/page?appId=...&curPage=1&pageSize=20
GET  /v1/chat/sessions/{sessionId}
POST /v1/chat/sessions/{sessionId}/read
GET  /v1/chat/sessions/{sessionId}/messages?leafMessageId=...&limit=50
GET  /v1/chat/sessions/{sessionId}/messages/{messageId}/variants
POST /v1/chat/sessions/{sessionId}/path
POST /v1/chat/sessions/{sessionId}/branches
GET  /v1/chat/sessions/{sessionId}/events/resume?afterSeq={lastSeq}
GET  /v1/chat/runs/{runId}/events/resume?afterSeq={lastSeq}
GET  /v1/chat/sessions/{sessionId}/stream-status
WS   /v1/chat/ws subscribe(topicId=streamTopicId)
POST /v1/chat/runs/{runId}/stop
POST /v1/chat/sessions/{sessionId}/archive
POST /v1/chat/sessions/{sessionId}/restore
DELETE /v1/chat/sessions/{sessionId}
DELETE /v1/chat/sessions
```

各接口的最小入参示例、不同 `runMode` 场景请求体、Interaction 续接请求体、文档上传 multipart 示例和 WebSocket 控制消息示例，以 [前端联调文档](../frontend-integration.md) 的“逐接口最小入参示例”和“`/v1/chat/runs` 不同场景请求体示例”为准；本架构文档只维护流程边界和事实源职责，避免接口样例双写漂移。

`/v1/chat/runs` 只返回 run 运行标识和 run 级 `streamTopicId`，不返回 WebSocket、Event Resume 或 stop URL。
这些 URL 属于前端 SDK、网关或部署配置，避免后端业务响应承担客户端路由配置职责。

删除会话是软删除语义。若目标会话存在 active run，删除接口会复用 run stop 编排先写入取消标记、
发布 `run.cancelled` 并释放本服务 active run，再把会话置为 `DELETED`。前端删除会话时不需要串行调用
stop；删除成功后应立即移除会话并取消本地订阅。

## 消息树与只读分支

当前版本引入会话内消息树，但不改变现有流式协议。`POST /v1/chat/runs` 创建后台 run 时会先根据 `runMode` 解析消息树写入计划：

- `NEXT`：在 `parentMessageId` 或会话 `current_leaf_message_id` 后追加新的 user 消息，run 完成后追加 assistant 消息。
- `EDIT_USER`：校验 `editedMessageId` 是未锁定 user 消息，在原父节点下创建新的 user sibling，旧消息不变。
- `REGENERATE_ASSISTANT`：校验 `regeneratedMessageId` 是未锁定 assistant 消息，复用其父 user 消息，run 完成后创建新的 assistant sibling。
- `CONTINUE_INTERACTION`：提交 `interactionId` 对应的澄清、审批或确认响应。`INTENT_CLARIFICATION` 使用 `NEW_TURN` 消息策略，回答生成新的 user 节点，下一轮澄清或最终回答生成新的 assistant 节点；其他 Interaction 继续复用等待态 assistant。

会话表以显式 `app_id/app_name` 保存产品分组标签：`appId` 是大小写敏感的稳定查询键，`appName` 是创建时展示快照。两者不参与身份隔离，所有读取仍必须带 `tenantId + userId`；列表可按 `appId` 过滤并使用 `(tenant_id,user_id,app_id,updated_at,id)` 索引。已有会话只接受与快照一致的显式 tag，分支继承源 tag，其他会话更新不修改 tag。字段保留在会话边界，不进入 run metadata、RouteMemory 或 Agent 请求，也不增加主流程外部调用。

会话未读状态采用服务端水位：`latest_message_seq` 是最新已保存、需要用户查看的 assistant 消息终态事件 sequence，`last_read_seq` 是前端确认展示到的位置，`latest > last_read` 即未读。两个水位由专用 SQL 单调更新，通用 session save 不覆盖；`run.completed(messageReady=true)` 和 `run.waiting_user` 在保存 assistant 的同一短事务内推进最新水位。`POST /sessions/{sessionId}/read` 原子执行 `max(lastRead,min(readThrough,latest))` 且不修改 `updated_at`，因此多页签不会回退或越过水位，也不会因阅读操作改变列表顺序。

`current_leaf_message_id` 表示当前会话激活路径叶子。历史消息查询默认返回 root 到 current leaf 的路径；指定 `leafMessageId` 时返回 root 到该 leaf 的路径。`/messages` 会在有多个 sibling 版本的消息上返回 `versionInfo`，包含当前版本序号、版本总数和候选版本的 `switchLeafMessageId`。前端切换版本时可以先用 `GET /messages?leafMessageId={switchLeafMessageId}` 刷新聊天区，再用 `POST /path` 持久化当前选择；`/variants` 保留为查询完整候选内容和调试的接口。

复杂前端或联调排障可以调用 `GET /v1/chat/sessions/{sessionId}/messages/tree` 读取完整可见消息树。该接口返回 `currentLeafMessageId`、`rootMessageIds` 和 `mapping`，但只包含业务可见的 user/assistant 消息，不返回 hidden system 或下游工具原始节点；普通聊天页继续使用 `/messages` active path。历史消息、tree 和 variants 返回的 `ChatMessageDto.attachments` 是消息附件展示快照，文件下载和预览仍由文档库接口独立鉴权。

```mermaid
flowchart TD
    RootUser["user: 原始问题"] --> A1["assistant: 第一次回答"]
    RootUser --> A2["assistant: 重新生成回答"]
    RootUserEdit["user: 编辑后的问题"] --> B1["assistant: 编辑后回答"]
    RootUser -. "same parent sibling" .- RootUserEdit
```

从某条消息新建会话分支时，服务端使用只读物化快照方案：沿 `parent_message_id` 回溯 root，复制该路径到新 session，并继承源会话 `appId/appName`；复制出的消息写入 `source_session_id/source_message_id`，并设置 `origin_type=BRANCH_SNAPSHOT`、`locked=true`。快照消息不能编辑、删除或重新生成；分支后续新增的 `NORMAL` 消息可以继续参与消息树版本管理。分支不继承源会话 RuntimeBinding，避免把源会话 Runtime session 错接到新分支。

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

`snapshot_json` 只保存父 user 问题、assistant 回答、问题附件展示快照和 `visible=true` 的 parts；不保存 feedback、下游原始响应、隐藏/debug parts、Cookie、Authorization 或企业鉴权信息。附件快照只用于展示，不授予下载/预览权限。原会话后续编辑、重新生成、切换 path 或反馈变化都不会影响已经创建的分享。

分享发送通过 `ChatShareDeliveryProvider` 防腐层完成，首版 provider 为 `welink`。应用层只生成稳定的发送请求：分享人、标题、分享 URL、摘要、目标用户和目标群组；WeLink wire 字段如 `targetAccount/groupID` 只存在于 provider 实现中。WeLink 出站请求会设置 `Referer`，默认取 provider `base-url`，也可用 `financeex.share.delivery.providers.welink.referer` 覆盖；分享发送入口捕获到的标准 `Cookie` 请求头只作为出站 header 透传，不进入 wire body、发送记录或快照。发送失败不会回滚分享快照，只在 `fin_ex_chat_share_delivery_t` 中记录 `FAILED`、错误码和 provider 安全响应摘要，前端可按同一个 `shareId` 重试。分享发送使用 `financeex.share.delivery.max-concurrency` 做当前 JVM 内并发隔离，防止外部 provider 抖动时占满异步工作线程；WeLink provider 失败后默认最多重试 3 次，运行时最多按 10 次重试生效。

删除会话时，`SessionApplicationService` 会同步撤销当前用户创建的该会话 `ACTIVE` 分享，避免用户删除会话后外部仍访问其快照。

## 集成服务鉴权

外部 HTTP 调用统一通过 `AuthHeaderProviderRegistry` 获取服务对服务鉴权请求头。该能力是集成服务调用防腐层，不读取前端请求 ThreadLocal，也不要求前端传 token。默认 `financeex.integration-auth.enabled=false`，不会改变现有调用行为。

首版内置 `none` 和 `sgov` 两种 provider。`sgov` 只向出站 HTTP 请求注入 `Authorization` header，具体凭据获取由企业实现的 `SgovTokenResolver` bean 负责。本服务不会把 Authorization、服务 ID 或密钥写入请求 body、数据库、事件、metadata 或前端响应。

当前只预置以下 serviceCode：`welink-share`、`intent-service`、`use-case-library`。Relay Runtime、DomainAgent 和文档存储 adapter 默认不走该鉴权层，仍保持现有 Cookie 透传或普通调用行为；后续如需启用，只新增 `financeex.integration-auth.services.<serviceCode>.provider=sgov` 配置。

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

    Frontend->>ChatAPI: "POST /v1/chat/runs"
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
        SuperAgent->>SuperAgent: "原粒度透传标准事件"
        SuperAgent->>EventStore: "guarded append(delta)"
        EventStore->>DB: "行栅栏校验 run/session/execution，分配 seq 后以 VALUES 写入"
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
        Frontend->>ChatAPI: "POST /v1/chat/runs/{runId}/stop"
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
- 数据库是事件事实源。生产默认使用 `financeex.chat-stream.live-source-mode=redis-only`，
  WebSocket 与 run 级 Event Resume live tail 只消费 Redis Pub/Sub，避免本机 local sink 与 Redis
  双源合并造成同一 topic seq 乱序；`LocalChatEventStreamRegistry` 保留为 `local-only/merge` 回退通道。
- 实时消费侧使用 `financeex.chat-stream.live-reorder-*` 做短窗口排序。排序只处理已经到达的事件，
  不合并事件、不改变 payload、不等待连续 seq，用于吸收 Redis listener 调度抖动带来的低 seq 迟到。
- 事件写入必须校验 `runId/sessionId/tenantId/userId` 一致；事件补发和 `latestSeq` 查询也必须携带 `tenantId/userId/sessionId/runId` owner 条件，不能按裸 runId 或 sessionId 查询。
- `fin_ex_chat_run_t` 是 run 生命周期事实源；Redis 只保存 active run 和 cancel flag。
- `fin_ex_chat_run_execution_t` 是 run 执行控制面事实源；实例 ID、心跳、租约、恢复状态和 `fencing_token` 都在该表中，避免把运维执行信息混入业务 run 表。
- 后台 run 不依赖创建 run 的原始浏览器连接，刷新页面后用 `afterSeq` 恢复。
- 前端 WebSocket 订阅消息格式：`{"type":"subscribe","topicId":"chat-run-{runId}","afterSeq":0}`。
- 前端 WebSocket 不触发 `AgentRuntime.query`，只补发和订阅 ChatEvent；它不接受聊天请求，仅支持 `connect`、`presence`、`subscribe`、`unsubscribe` 控制消息。
- 同一 WebSocket 连接允许同时订阅多个 session 的多个 run topic；协议层不会因切换会话自动释放旧 topic。订阅前按用户校验 `topicId -> run` 归属，live 流和 WebSocket envelope 投递前再按 `topicId + runId + sessionId` 校验，避免跨会话实时消息串线。
- stop 是 REST 生命周期接口，不是 WebSocket command；重复 stop 幂等返回当前 run 状态。
- 重新生成回答不再使用 run retry 接口，而是通过 `POST /v1/chat/runs` 携带 `runMode=REGENERATE_ASSISTANT` 和 `regeneratedMessageId`，在同一 user 节点下生成新的 assistant sibling。
- 会话 state 接口聚合会话元数据、最近历史消息和 `activeStreamTopicId`，用于前端切换会话后的恢复判断。
- 新页签、新浏览器或跨电脑恢复 active run 时，前端应使用 `activeRunFirstSeq - 1` 打开 run 级事件恢复；该接口会先按数据库事实源补发历史事件，再接入 live topic 持续输出到 run 终态，不能把 `latestSeq` 当作当前渲染实例已消费游标。

### Run 控制面与故障恢复

后台 run 的业务状态和执行状态分离：

- `fin_ex_chat_run_t`：业务生命周期事实源，记录 run 状态、路由类型、Runtime 信息、first/last seq 和取消原因。
- `fin_ex_chat_run_execution_t`：运行控制面事实源，记录当前 owner 实例、心跳、租约、恢复状态、恢复租约和 `fencing_token`。

控制面初始化是 run 启动的必备步骤。若业务 run 已写入 `fin_ex_chat_run_t`，但创建
`fin_ex_chat_run_execution_t` 失败，服务端不会继续调用 Runtime/DomainAgent，而是直接追加
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

stop/watchdog/execution 初始化失败使用 run 行 CAS 作为唯一外部终态写入准入：只有成功把 `RUNNING/CANCELLING` 条件更新为目标终态的调用方才能在同一短事务中写终态事件、释放 Interaction claim 并更新 execution；失败者不发布事件。`CANCELLING` 允许 stop 重试，stale `CANCELLING` 由 watchdog 闭合为 `run.cancelled`。外部终态事务默认限制为 10 秒，超时整体回滚。Interaction continuation 与普通 run 在 run 已创建但 execution 未创建时，分别由对应的默认 `2m` 宽限期 watchdog 扫描收敛；首事件握手超时还会主动异步提交 `RUN_FIRST_EVENT_TIMEOUT` 或释放尚未创建 run 的 Interaction claim。run.started 后的路由和 Runtime 外部调用边界会通过 run/execution 主键只读校验 owner 与 fencing token；普通流式事件在短事务中获取 run/execution 共享行栅栏，再以 `INSERT ... VALUES` 写入。

owner 的 completed/waiting 终态事务只写 OpenGauss 事实；短期记忆 Redis 缓存通过 Spring transaction synchronization 在提交后刷新。Redis 失败只影响热缓存，不能回滚或改写已提交的 run 终态。

所有治理类 `@Scheduled` 任务使用 `financeex.scheduler.pool-size` 配置的线程池调度器，避免 watchdog jitter 或慢巡检阻塞 run heartbeat、WebSocket 空闲清理和准入窗口清理。实例 ID 默认由 `GeneratedApplicationInstanceIdProvider` 在进程启动时生成；如需对接注册中心，提供新的 `ApplicationInstanceIdProvider` bean 即可替换默认实现。

恢复负载治理包含四层保护：每轮扫描候选上限、每轮最大 claim 上限、每租户 claim 上限、本机恢复和 takeover semaphore。没有恢复容量时不抢占，留给下一轮或其他实例处理，避免单实例在大批 stale run 场景下被恢复任务压垮。

### WebSocket 边界说明

当前系统存在两条职责隔离的 WebSocket：

- 前端 WebSocket：`/v1/chat/ws`，只连接 FinanceEXChatService。它是用户级连接，按 run 级 `streamTopicId` 订阅已经写入事件事实源的 ChatEvent；它不接受聊天请求，也不直接调用 RelayAgent。
- 下游 Relay WebSocket：FinanceEXChatService 主动连接 Relay，承载 `config -> user-message/approval-response`、流式 frame 和 `stop_all_agents`；前端不可见。

因此架构图中的 `AgentRuntime.query` 是应用层防腐层调用，不等价于前端 WebSocket。Relay provider 只注册一个 `RelayRuntimeProtocolAdapter` WebSocket 实现。

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
`max-subscribers-per-topic`、`outbound-queue-size`、`live-buffer-capacity`、`idle-timeout`
以及 `servlet-send-*` 限制本机连接资源。Servlet WebSocket 的底层发送是阻塞调用，
服务端会先把 envelope 放入单连接有界队列，再由全局发送 executor 串行 drain 当前连接，
避免慢客户端把 Runtime、Redis fanout 或 scheduler 线程卡在 socket send 上。WebSocket 与 run 级事件恢复通过
`financeex.chat-stream.turn-heartbeat-interval` 发送 turn stream `heartbeat`，配合
`spring.mvc.async.request-timeout` 与 Tomcat 连接配置避免空闲断流。WebSocket 实时投递
出现慢客户端、发送队列溢出或乱序时关闭当前连接或返回 `RECOVER_REQUIRED`，可靠恢复仍走数据库事件 + Event Resume。
流式事件合并后的落库、run 状态推进和实时发布统一切到 `financeex.chat-stream.event-io-executor-*`
专用调度器，避免阻塞式 DB/Redis 调用占用 Reactor `parallel-*` timer 或 Servlet 请求线程。
Relay/DomainAgent 普通运行事件在该调度器上按同一 run 批量落库，默认以 `16 条 / 20ms / 256KB`
三重阈值中最先命中的条件提交。每个批次只获取一次 run/execution 共享栅栏、分配一次 sequence 集合并执行
一次批量 `INSERT ... VALUES`；控制事件和终态事件会先刷新批次再沿用原单事件事务。批量提交后事件仍按 seq
逐条发布，不跨 run 组批，也不改变 IntentAgent、Interaction、拒答和 owner 终态事务。
Redis Pub/Sub 是默认实时 fanout 通道，跨实例发布使用 `financeex.websocket.redis-publish-*` 有界后台队列；同一 run topic
串行发布并做短重试，发布缺口会通过恢复控制消息转成 `RECOVER_REQUIRED`。
实时消费侧默认用 `financeex.chat-stream.live-reorder-window=20ms` 和
`financeex.chat-stream.live-reorder-max-events=128` 对短窗口内事件按 seq 排序，避免同一 topic 中
后写事件先到达时误触发 seq rollback；该阶段不改变事件粒度。
run 级 Event Resume 正常优先接入 Redis live topic；如果 live source 异常，会以恢复错误结束当前实时 tail，
前端退避后重新 resume；服务端不做循环 DB polling，避免 Redis 抖动时把压力转移到数据库。
前端接收的 `message.payload` / SSE `data` 是 `conversation-turn-stream`，真实 ChatEvent 位于
`stream-item.encodedItem.data`；`heartbeat` 和 `done` 只是传输层状态，不写入 `fin_ex_chat_event_t`，
也不推进 `afterSeq`。

文档上传同样按启动模式做接口层适配：Servlet/MVC 注册 `MvcDocumentUploadController`
并接收 `MultipartFile`，纯 WebFlux 注册 `ReactiveDocumentUploadController` 并接收
`FilePart`。两种 Controller 都委托 `DocumentUploadSupport`，由它先把上传流写入临时文件，
再通过 `DocumentFacade -> DocumentStorage` 按 `financeex.storage.provider` 选择 `local`、`huawei-s3`
或 `api-store`。前端看到的路径、字段和响应始终是同一套
`POST /v1/documents` 契约。api-store 可通过 `financeex.storage.api-store.forward-cookie=true`
允许上传入口 Cookie 作为下游 upload HTTP header 透传；普通对象存储实现不使用该 Cookie，
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
        RelayRuntime["RelayAgentRuntime Provider"]
        DomainAgentRuntime["DomainAgentRuntime Provider"]
        RelayWS["RelayWebSocketRuntimeAdapter"]
        Storage["Local / Huawei OBS S3 / api-store DocumentStorage"]
        DomainAgentHttp["DomainAgent HTTP Adapter"]
    end

    Interfaces --> ChatService
    ChatService --> RouteSignal
    ChatService --> IntentRecord
    ChatService --> ChatRun
    ChatService --> RuntimeBinding
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
    RuntimeExecutor --> RelayRuntime
    RuntimeExecutor --> DomainAgentRuntime
    RelayRuntime --> RelayWS
    DomainAgentRuntime --> DomainAgentHttp
    DocumentService --> Storage
    DocumentService --> HttpDocumentProvider
    Application --> Domain
```

## 路由规则

- `targetType=DOMAIN_AGENT,targetId=...` 优先级最高；存在时进入 `DOMAIN_AGENT` 路由并绑定对应 DomainAgent，`routeSource=front-selected`。`runMode=NEXT` 直连可原子取消会话下开放的 `WAITING/RESPONDING` Interaction 并解除等待，但不抢占真正执行中的 run；旧等待 run/message 保留，新 user 节点挂在等待 assistant 后。直连只使用本轮 message/metadata/attachments，取消当前 ACTIVE binding、保留历史 RESUMABLE Relay session。可选 `selectedIntent` 只作为展示摘要写入 binding；后续复用 binding 时用于补齐 `selectedDomainAgent` 历史 part，不参与路由或下游请求。
- active RuntimeBinding 优先级次之；`provider=domain-agent` 时续接当前 DomainAgent，`provider=relay` 只用于未闭合或等待用户输入的 Relay 任务。Relay 正常完成后转为 `RESUMABLE`，下次普通提问仍重新路由；若路由再次选择 Relay，则恢复原 runtime session。
- 用例库和意图服务是可选路由信号，默认关闭；关闭时不调用外部 API。
- 用例库开启时优先匹配；命中阈值默认 `0.85`，命中并返回 DomainAgent 路由目标后绑定为 DomainAgent。
- 用例库关闭或未命中后，只有意图服务开启才调用 `IntentService`。
- 外部路由在 run pipeline 内执行，`run.started` 会先落库和推送；调用用例库前输出 `runtime.progress(sourceType=route-progress,stage=use_case_matching)`，调用 intent-agent 前输出 `runtime.progress(sourceType=intent-start,stage=intent_calling)`，意图裁决后再输出 `sourceType=intent-result`，避免慢路由阶段让前端长时间无反馈。
- 意图服务 adapter 的 HTTP 请求体和响应体转换由 infrastructure intent mapper 承载；当前对接 `/getIntentDecision`，以 `data.result.routeAction` 作为唯一裁决点。
- RouteMemory 是独立于普通短期/长期记忆的路由事实源。`ROUTE` 表示最终目标已确定且 RuntimeBinding 已持久化的路由决策，不要求下游任务完成。应用层在调用 Runtime 前通过独立 write executor 异步记录；任务失败、取消或 DomainAgent 拒答不撤销该事实。调用意图服务前加载最近 TopK `ROUTE` 和当前未完成的 `CLARIFY`，统一组装为 `conversationContext.history`；已有 binding 的普通续接和 Agent Interaction 续接不新增 route。
- `conversationContext.routeTrigger` 由 ChatService 生成：普通无绑定首次路由为 `first_turn`，上一轮有效 route 是 Relay/no_match 时为 `fallback_followup`，DomainAgent 结构化拒答后的重路由为 `domain_reject`，用户回答意图澄清后为 `clarify_answer`。前端可在 `/v1/chat/runs` 顶层传 `forceReroute=true` 表示用户主动纠正路由，后端会转成内部用户纠正触发原因；`AGENT_CLARIFICATION` 和 `ROUTE_SWITCH_CONFIRMATION` 不进入意图 history。
- 意图服务的技术失败和协议失败默认最多重试 3 次；配置误设过大时运行时最多按 10 次生效。重试耗尽后读取 `financeex.intent.failure-strategy`：`RELAY_FALLBACK`（默认）创建 Relay binding 并执行原问题；`FAIL_RUN` 不创建 RuntimeBinding、不调用 Runtime，以 `INTENT_ROUTING_FAILED` 结束并提示用户手动选择技能。该策略同样覆盖意图澄清续接和 DomainAgent 拒答后的重新意图。
- 意图服务返回 `WAITING_CLARIFICATION` 或兼容的 `TaskComplexity.NEED_CLARIFICATION` 时生成 `run.waiting_user(interactionType=INTENT_CLARIFICATION)`，不创建 RuntimeBinding；澄清问题以 `assistantSource=intent-agent` 的独立 assistant 消息保存。用户通过 `POST /v1/chat/runs` + `runMode=CONTINUE_INTERACTION` 提交答案、附件和本轮 metadata；文档事实解析在 Interaction claim 前完成，随后短事务原子保存新的 user 回答及附件、continuation run，并将旧 Interaction 标记为 `ANSWERED`。多轮附件 ID 仅保存在 Interaction 内部上下文，最终路由后以累计可信文档的完整 `providerDocument` 覆盖最终轮 metadata 的 `sceneParam.docList`，DomainAgent 与 Relay 使用相同对象。
- 意图服务返回 `routeAction=ROUTE_SINGLE` 时，取唯一 `items[0].accessName`，按可选 `response-access-name-prefix` 移除一次匹配的开头前缀后作为 DomainAgentId/skillId，绑定并调用 DomainAgent Runtime；未配置前缀或前缀不匹配时使用原始值。调用 Runtime 前，服务端以本轮可信附件的完整 `providerDocument` 覆盖 `sceneParam.docList`，前端无需为意图路由组装该字段；`ROUTE_MULTI/NO_MATCH/RELAY_FALLBACK` 进入 Relay 时使用同一规则。`intentId` 保留为意图编码，`resourceInstruction.resourceId` 只作为诊断字段记录，两者都不参与路由或兜底；缺少 item 或有效 `accessName` 是协议失败，重试耗尽后应用 failure strategy。`confidence` 只用于记录和排障，不参与二次裁决。
- 意图澄清可能多轮连续发生。每次 `CLARIFY` 会在 `run.waiting_user` 与 Interaction request 成功落库后追加一条 RouteMemory `CLARIFY`；最终目标 binding 成功后，在单个写任务中折叠 clarify 并追加 route。`ROUTE_SINGLE` 保存真实 `intentId/intentName/skillId`；`ROUTE_MULTI/NO_MATCH/RELAY_FALLBACK` 保存 `intent=no_match,intentCode=relay` 和真实 `routeAction`。最新 Relay route 只有关联的 source run 为 `COMPLETED` 才触发下一轮 `fallback_followup`，失败或取消只保留历史事实。DomainAgent 拒答后原 route 保留，自动切换成功会再记录新 route；手动候选必须确认且 binding 成功后才记录。`FAIL_RUN` 不写 route。
- 澄清续接调用 intent-agent 时顶层 `query` 是最新回答，`history` 是原始问题和已完成澄清链；最终进入 DomainAgent/Relay 时，Runtime `query` 改为折叠后的完整问题。用户回答 admission 成功后即成为消息事实，即使后续 run 失败、取消或首事件超时也不退回旧 Interaction；admission 提交前失败才恢复 `WAITING`。

```mermaid
flowchart LR
    U0["user 原始问题"] --> A1["assistant 意图澄清问题"]
    A1 --> U1["user 澄清回答"]
    U1 --> A2["assistant 下一轮澄清"]
    A2 --> U2["user 第二轮回答"]
    U2 --> AF["assistant DomainAgent/Relay 最终回答"]
```
- `financeex.intent-record.enabled=true` 时，只有实际调用过意图服务的 run 会异步写入 `fin_ex_intent_recognition_t`。记录内容包含本轮 query、routeAction、候选 items、最终路由是否采纳和意图服务耗时；DomainAgent、RuntimeBinding 续接、用例库已命中、意图服务关闭时不会记录。
- `routeAction=ROUTE_MULTI` 和 `routeAction=NO_MATCH` 都是合法业务结果，无论 failure strategy 如何配置都进入 Relay Runtime。意图关闭时仍直接进入默认 Relay；只有已启用意图的技术/协议失败才应用 failure strategy。
- DomainAgent 绑定会一直续接，直到下游流式返回 `type=agent.refusal,code=FN-EX-CAHT-BIZ-DAG-001`。ChatService 通过控制事件防腐层归一化并立即取消旧流：意图/用例库绑定自动切换，`front-selected/user-confirmed` 绑定则生成 `ROUTE_SWITCH_CONFIRMATION`，候选 DomainAgent 或 Relay 均需用户确认。

## RuntimeBinding

RuntimeBinding 维护前端 chat session、当前消息树 leaf 与当前下游会话的关系。当前 provider 包括 `relay` 和 `domain-agent`。leaf 维度隔离可以避免编辑历史问题、切换版本或从历史消息新建分支时误用另一条路径的下游 session。

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
- JVM subscription registry 只是本机资源释放加速器；即使 stop 请求与输出流落在不同实例，输出实例也必须在追加事件前读取 Redis cancel flag。非终态事件通过数据库行栅栏同时校验 run 状态、session 归属和 execution fencing，栅栏持有到后续 `VALUES` 写入提交。
- 用户主动 stop 且已有 assistant 正文或用户可见 runtime parts 成功落库时，会从事件事实源重建并保存 partial assistant 历史消息，消息 metadata 标记 `partial=true`、`finishReason=USER_STOP`；只有 trace、domain-agent session 等内部 metadata 时不创建空 assistant。
- 下游尽力取消：Relay 本机命中 active WebSocket 时发送 `{"type":"stop_all_agents"}`；跨实例或本机连接已清理时用临时 WS `resume` 到 run 中已回填的 Relay `runtimeSessionId`，收到 `session-ready` 后发送 `stop_all_agents`；DomainAgent 走 DomainAgent cancel adapter。这些下游取消失败只记录日志，不影响前端收到本服务 `run.cancelled` 终态。
- stop 不取消 RuntimeBinding；下一轮仍可续接 Runtime。如果需要全新的 Relay 会话，应创建新的 ChatService 会话。

## 外部 API 接入

- 用例库服务：`financeex.use-case-library.enabled`、`financeex.use-case-library.base-url`、`financeex.use-case-library.match-path`
- 意图服务：`financeex.intent.enabled`、`financeex.intent.base-url`、`financeex.intent.access-name`、`financeex.intent.response-access-name-prefix`、`financeex.intent.recognize-path`、`financeex.intent.trace`、`financeex.intent.confidence-threshold`、`financeex.intent.timeout`、`financeex.intent.max-retries`、`financeex.intent.failure-strategy`。其中请求侧 `access-name` 是意图入口；可选的 `response-access-name-prefix` 仅用于把响应 `items[].accessName` 归一化为真实 DomainAgent skillId；`failure-strategy` 只接受 `RELAY_FALLBACK/FAIL_RUN`；`confidence-threshold` 仅保留给旧统计字段兼容，不参与 DomainAgent 路由裁决。
- 意图识别记录：`financeex.intent-record.enabled`、`max-query-length`、`max-raw-json-length`、`executor.*`。默认关闭；开启后使用 Servlet/MVC 友好的专用线程池 best-effort 写库，线程池拒绝、JSON 序列化失败或数据库写入失败都只记录 warn，不阻塞 `/v1/chat/runs`。
- RouteMemory：`financeex.route-memory.top-k` 控制传给意图服务的最近成功 route 数量，`financeex.route-memory.max-clarification-rounds` 控制单条澄清链路的最大轮数，超过后降级 Relay Runtime，避免无限澄清。读写线程池分别由 `read-executor.*`、`write-executor.*` 控制；`read-timeout` 超时后会取消读取 future，并按 `circuit-breaker.*` 短暂熔断，熔断期间直接返回空 history。
- DomainAgent：`financeex.domain-agent.base-url`、`financeex.domain-agent.referer`、`financeex.domain-agent.chat-path`、`financeex.domain-agent.stop-path`。chat、绑定续接与 stop 都发送可信服务端 `Referer`；未配置时回退到 `base-url`，不允许前端覆盖，也不写入 body 或持久化数据。
- DomainAgent 拒答重路由：固定控制编码 `FN-EX-CAHT-BIZ-DAG-001`、`financeex.domain-agent.max-reroutes`。自动来源拒答的 event 与 binding 取消在有超时的短事务中原子提交，并由独立 control IO scheduler 承接阻塞数据库操作；事务提交后先发布事件和放行重意图，Redis binding 缓存仅异步 best-effort 同步。
- AgentRuntime fallback provider：`financeex.agent-runtime.default-provider`，没有 active binding 时的兜底 provider，默认 `relay`
- Relay WebSocket：`financeex.agent-runtime.relay.websocket.url`、`app-mode`、`connect-timeout`、`config-handshake-timeout`、`max-run-duration`、`heartbeat-interval`、`heartbeat-response-timeout`、`interrupt-ack-timeout`、`idle-timeout`、`max-frame-bytes`。Relay 启用时 `url` 必填；`config-handshake-timeout` 分别约束 HTTP Upgrade opening handshake 和 Upgrade 后的 `config -> session-ready`，每个阶段独立计时。
- 下游 Cookie 透传：`financeex.agent-runtime.forward-cookie.enabled`、`max-length` 控制 run/stop 到 Relay WebSocket 的 Cookie 透传。DomainAgent chat/cancel 也使用入口 Cookie 内存快照。文档上传另由 `financeex.document.forward-cookie-max-length` 与 `financeex.storage.api-store.forward-cookie` 控制，默认关闭 api-store upload Cookie 透传。
- 流式事件粒度：当前生产版本不合并 `message.delta`，事件仍按下游标准粒度写入事件表并推送实时通道。Relay/DomainAgent 普通事件仅在提交层由 `financeex.chat-stream.event-batch-*` 按同 run 组批，默认 `16 条 / 20ms / 256KB`，关闭开关后恢复逐事件事务；IntentAgent、Interaction、拒答和终态不参与批量。`delta-coalesce-*` 仅作为内容合并兼容预留，`turn-heartbeat-interval` 只控制传输层 heartbeat。
- Servlet WebSocket 发送治理：`financeex.websocket.servlet-send-executor-core-size`、`servlet-send-executor-max-size`、`servlet-send-queue-capacity`、`servlet-send-queue-max-bytes`、`servlet-send-use-virtual-threads`。默认使用有界平台线程池和单连接有界队列；JDK 21 虚拟线程可按企业压测结果开启。
- DomainAgent 结构化帧：`financeex.domain-agent.max-pending-frame-bytes` 是完整及未完成单 frame 的硬上限，默认 `256KB`。`diyCardScene/openCard/searchList/sourcesDocuments/processResult` 跨网络 DataBuffer 时使用请求级增量 UTF-8 解码和有界 JSON 累积，闭合后输出单个完整 `runtime.card/runtime.reference/runtime.progress`；超限直接按协议错误结束，不输出残缺事件。`max-fragment-bytes` 仅为旧部署配置兼容保留，不再控制 DomainAgent 对外事件。
- Relay 响应映射：`financeex.agent-runtime.relay.answer-event-types`、`answer-content-fields`、`agent-context-as-answer`。默认把 Relay `type=agent,is_streaming=true` 的 `content/context` 映射为 assistant 正文增量 `message.delta`，把 `type=agent,is_streaming=false` 和 `type=generate-response,content非空` 映射为最终回答快照 `message.snapshot`，把 `steam-complete/stream-complete/[DONE]` 映射为 `message.completed`。

DomainAgent 当前通过 HTTP 文本流调用，并和 Relay Runtime 一样以 `AgentRuntime` provider 注册，使用 RuntimeBinding 保存下游会话 ID、绑定来源和意图摘要。DomainAgent 请求会把 `skillId/query/sessionId` 固定为服务端当前绑定和本轮问题，前端 metadata 只作为业务扩展，不能覆盖这些保留字段。当前上线版本内置 `RelayAgentRuntime` 与 `DomainAgentRuntime` 两个 provider；Relay provider 唯一委托 `RelayRuntimeProtocolAdapter` 的 WebSocket 实现。Relay WebSocket 始终使用短连接，每个 run 都重新建立下游 WS；首轮 `new` 的 `config.sessionId` 使用 ChatService `sessionId`，收到 `session-ready.session_id` 后回填 RuntimeBinding 中的 Relay 真实 `runtimeSessionId`。Relay 正常 `run.completed` 后将 binding 改为 `RESUMABLE`，后续普通提问重新走用例库/意图路由；若再次选择 Relay，则使用该 binding 的真实 session ID 执行 `resume`。如果 Relay 进入 Agent 澄清等待态，则保留 active binding 供 `CONTINUE_INTERACTION` 续接。

`Cookie` 是请求入口捕获的运行期内存快照，只会在 `AgentRuntimeRequest.forwardHeaders`、`DomainAgentRequest.forwardHeaders`、`DocumentUploadCommand.forwardHeaders` 或 cancel 请求中向可信 adapter 传递；这些字段被 JSON 序列化忽略，且 adapter 会把内部请求映射为专用 wire DTO 或受控 multipart，不能进入下游请求体、form 字段、文档元数据、run metadata、事件 payload 或日志。该设计保证企业登录态不会因后台 run、Event Resume/WS 恢复、文档库管理或故障治理被持久化或回放。

当前上线版本同时注册 Relay 与 DomainAgent Runtime provider，不包含其他历史 Runtime provider 分支、专用 memory 分支或专用 prompt assembler 配置。复杂任务通过 Relay WebSocket Runtime 执行；后续如需新增 Runtime，应注册新的 `AgentRuntime` provider，而不是把新协议写进主编排。

AgentRuntime 防腐层仍然保留。应用层普通问答只依赖 `AgentRuntime` 和 `AgentRuntimeRequest` 契约，等待用户输入后的续接只依赖 `AgentRuntimeInteraction` 和 `AgentRuntimeInteractionResponseRequest` 契约。当前 `relay` provider 唯一使用 WebSocket 协议防腐层；后续替换 Runtime 时应新增 `AgentRuntime` provider，Relay 协议细节继续限制在 `RelayRuntimeProtocolAdapter` 内，避免改动主编排。

Relay WebSocket adapter 每个 run 都建立一条短生命周期下游连接，并把 `AgentRuntimeRequest.metadata()` 过滤后的非敏感字段放入 `user-message.metadata`；Cookie、token、Authorization、secret、password 等敏感 key 会被递归移除。应用层通过会话级 RuntimeBinding 显式传递 `runtimeSessionMode=NEW|RESUME`：同一 ChatService 会话只首次发送 `new`，此时 `config.sessionId` 使用 ChatService `sessionId`；Relay 返回 `session-ready.session_id` 后回填真实 `runtimeSessionId`，后续提问即使重新建连也全部发送 `resume`，并在 resume config 中声明 `supports_incremental_recovery=true`。

Relay WS 配置阶段只以 `session-ready` 作为唯一完成信号；adapter 会将 `session-ready` 映射为 `runtime.metadata`，payload 保留 Relay 原始 `session_id/session_mode` 等字段，并补充 `runtimeSessionId` 用于尽早回填真实会话 ID。其他配置阶段 frame 只用于握手判定，不会成为用户回答事件；若收到 `error/clear-session/session-mismatch` 则立即失败。`user-message` 后会丢弃 `relay-start` 前的前置 `session-state=idle/completed/ready/running/agent_thinking` 和迟到 `config`，并从 `relay-start`、首个业务帧或 `session-state=waiting_user_input/paused` 开始映射标准事件；普通问答阶段定时向 Relay 发送 `{ "type": "heartbeat" }` 保持连接活跃，`heartbeat-response` 不落库、不推送，任意业务帧或 heartbeat 回包都会刷新下游连接活跃时间。回答阶段内的 `session-state=idle/completed/waiting_user_input/paused` 会补齐 `message.completed`。普通问答不再用 `idle-timeout` 判定 60s 无业务帧失败；若超过 `heartbeat-response-timeout` 没有任何回包则失败闭合，`max-run-duration` 控制最长执行时间；`idle-timeout` 仅保留给 stop 临时控制连接等等待下一帧的场景。

协议级澄清只由 Relay `approval-request(operation_type=questionnaire)` 触发，ChatService 会固化 assistant、保存 Interaction 请求并以 `run.waiting_user` 终止本轮；等待请求默认按 `financeex.chat-interaction.default-expire-duration=24h` 过期，配置为 `0` 或负数表示不过期，过期由 stream-status/提交响应路径懒标记。`responding-orphan-grace=2m` 只用于 watchdog 回收 claim 后启动中断的续接任务，不改变正常等待有效期。单独的 `waiting_user_input` 只闭合本次 Relay WS 连接，不生成 Interaction 等待状态；`paused` 只表示 Relay 对 `stop_all_agents` 的确认。Interaction 续接通过 `AgentRuntimeInteraction` 防腐层，应用层发送通用响应上下文，Relay WS adapter 转换为 `approval-response`，不创建新的普通 user 消息。stop/delete 触发本地取消时，Relay WS adapter 会优先向本机 active run 对应下游短连接 best-effort 发送 `{"type":"stop_all_agents"}`；若 stop 落到其他实例或本机连接已清理，则新建独立 clientId 的临时 WS，使用 run 中已回填的 Relay `runtimeSessionId` 发送 `config(sessionMode=resume, supports_incremental_recovery=true)`，收到 `session-ready` 后发送 `stop_all_agents`，等待 `session-state=paused` 或 `interrupt-ack-timeout` 到期后释放临时连接。

前端通过 turn stream 的 `encodedItem.data` 消费 ChatService 标准顶层事件，同时可以按 Relay 文档解析 Relay payload：Relay JSON payload 保留原字段名和嵌套结构，仅额外补充 `source=relay`、`sourceType=<Relay原始type>`、`runtimeSessionId`。`FINANCEEX_RELAY_WS_MAX_FRAME_BYTES` 控制 Relay WebSocket 单帧上限，不承担超大事件拆分职责。`message.delta` 是 assistant 正文增量；`message.snapshot` 是回答快照，历史正文使用最后一个 snapshot，同时每个 snapshot 都会进入 `ChatMessageDto.parts` 的 `MESSAGE_SNAPSHOT` part；`runtime.progress/runtime.metadata/runtime.agent/runtime.thinking/runtime.tool/runtime.reference/runtime.card` 是过程、引用或卡片事件，run 完成后进入 `ChatMessageDto.parts` 回显。Relay `tool-structured-result` 统一映射为 `runtime.tool`，完整保留 `result_data/resultData`，不再拆成正文、引用、进度或卡片。Relay 或 domain-agent 未知 JSON object 才以 `runtime.event` 可控透传。不能把下游任意 `type` 直接作为 ChatService 顶层事件类型。

协议级等待用户输入的续接能力独立为 `AgentRuntimeInteraction`。Relay WebSocket 支持 questionnaire 澄清续接，续接时发送 Relay `approval-response`。

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

同一会话的 active run 最终互斥由数据库部分唯一索引保证，`RUNNING/CANCELLING` run、用户消息、
附件关系和 current leaf 在同一短事务中提交；并发失败会整体回滚并返回 `ACTIVE_RUN_EXISTS`。
Redis active key 仅作为跨实例查询热缓存，run 进入终态后释放。应用启动时会校验该唯一索引，
首次建库脚本遗漏或索引定义不正确时拒绝启动。

Redis 部署模式由 `financeex.redis.mode` 控制，默认 `standalone`；生产 Redis Cluster 设置为
`cluster` 并配置 `financeex.redis.cluster.nodes`。配置文件中的 Redis prefix 仍是逻辑前缀
`fin_ex:...`，不要手写 env，避免形成 `fin_ex:dev:dev:...` 这类双重环境段。RuntimeBinding 使用
Redis hash tag 保证同一会话 binding key 和索引集合落在同一 slot；env 位于 hash tag 外面，不影响
同 slot 设计，因此会话级清理不使用 `KEYS`，只通过索引集合删除明确 key。
ChatLiveEventBus 在本机出现 run topic 订阅者时动态订阅对应 Redis channel，Redis Pub/Sub 仍然只做
跨实例实时 fanout，可靠恢复继续依赖数据库事件 + Event Resume。同一 topic 的本机 live sink 写入需要串行化，
避免 Redis listener 并发投递时触发 Reactor sink `FAIL_NON_SERIALIZED`。

## 可选记忆上下文

- `financeex.memory.short-term.enabled=false` 时，不装配最近问答，也不访问 Redis 短期记忆缓存。
- `financeex.memory.short-term.recent-turns=5` 表示短期记忆开启后读取最近 5 轮问答，即最多 10 条消息。
- `financeex.memory.short-term.cache-enabled=true` 表示短期记忆开启时优先使用 Redis 热缓存，miss 后回源数据库。
- `financeex.memory.long-term.enabled=false` 时，不调用长期记忆服务。
- `financeex.memory.long-term.provider=disabled` 是默认安全 provider，开启长期记忆但未接真实服务时返回空结果。
