# FinanceEXChatService

FinanceEXChatService 是 FinanceEX 前台聊天入口和 SuperAgent 主控服务。当前正式版本采用干净的两段式架构：简单任务通过用例库或意图服务命中后按 `agentCode` 单轮调用 SubAgent；复杂任务、低置信任务和未命中任务统一进入 Relay Runtime，并仅对 Relay Runtime 做多轮会话保持。

## 核心链路

```text
用户请求
 -> Controller/WebSocket 入口通过 AuthContextProvider 解析租户和用户
 -> 将不可变 UserContext 显式传入 application
 -> 会话归一化与可选 MemoryContext 装配
 -> 按 runMode 写入或定位消息树节点
 -> 查询 RuntimeBinding
    -> 有 active RuntimeBinding：继续调用 Relay Runtime
    -> 无 active RuntimeBinding：读取可选路由信号
        -> 用例库开启且命中 subAgentCode：单轮调用指定 SubAgent
        -> 意图服务开启且识别为简单任务：单轮调用指定 SubAgent
        -> 两者关闭/未命中/不可用/复杂任务：创建 RuntimeBinding 并调用 Relay Runtime
```

SubAgent 不创建绑定、不续接会话、不维护任务状态。只有 Relay Runtime 拥有多轮能力，内部 session、上下文压缩和规划机制由 Relay Runtime 自己负责。
ChatService 的长短期记忆是可选 SuperAgent 增强能力，默认关闭；关闭时不会读取最近历史、Redis 短期缓存或长期记忆服务，只把当前消息和附件传给 Runtime/SubAgent。

## 分层边界

- `interfaces`：`/chat/runs`、WebSocket run topic subscribe、SSE resume、会话和文档上传协议适配。
- `application`：聊天主编排、会话、记忆、RuntimeBinding、SubAgent 单轮调用和 Relay Runtime 调用。
- `application.integration`：应用层出站集成抽象，定义对 Relay Runtime、SubAgent、IntentService、用例库、会话、记忆、文档、ID 和身份能力的依赖边界。
- `domain`：聊天事件、意图结果、路由结果、RuntimeBinding、用例匹配结果等核心模型。
- `infrastructure`：Redis、openGauss/MyBatis、用例库 HTTP、SubAgent HTTP、Relay Runtime HTTP/WebSocket、对象存储等适配。

## 前端接入协议

完整接口和 WebSocket 联调说明见 [前端联调文档](docs/frontend-integration.md)。

- `POST /api/v1/ex/chat/runs`：唯一提问入口。创建后台 run，返回 `runId`、`sessionId`、`firstSeq` 和 `streamTopicId`。
- `POST /api/v1/ex/chat/sessions`：显式创建会话；也可以在 `/chat/runs` 中不传 `sessionId` 由后端创建或归一化。
- `GET /api/v1/ex/chat/sessions?limit=20&cursor=...`：分页查询当前用户会话列表。
- `GET /api/v1/ex/chat/sessions/{sessionId}/state?messageLimit=50`：选择会话时聚合返回会话元数据、最近历史消息和流式状态。
- `GET /api/v1/ex/chat/sessions/{sessionId}/messages?leafMessageId=...&limit=50`：选择会话后查询当前 active path 或指定 leaf path 的完整 user/assistant 消息。
- `GET /api/v1/ex/chat/sessions/{sessionId}/messages/{messageId}/variants`：查询某条消息同父节点下的候选版本，用于前端切换编辑/重新生成后的版本。
- `POST /api/v1/ex/chat/sessions/{sessionId}/path`：把会话当前 active path 切换到指定 leaf。
- `POST /api/v1/ex/chat/sessions/{sessionId}/branches`：从指定消息创建只读历史快照分支。
- `POST /api/v1/ex/chat/sessions/{sessionId}/archive|restore|close`：会话归档、恢复和关闭。
- `WS /api/v1/ex/chat/ws`：用户级实时输出通道。客户端使用 `{"type":"subscribe","topicId":"chat-run-{runId}","afterSeq":0}` 订阅本轮 run topic；MVC/Servlet 模式会在 handshake 阶段固化用户身份。
- `GET /api/v1/ex/chat/sessions/{sessionId}/events/sse?afterSeq={seq}`：会话级 SSE 有限补发，用于补齐整个会话缺失事件。
- `GET /api/v1/ex/chat/runs/{runId}/events/sse?afterSeq={seq}`：run 级 SSE 补发并接续 live，用于跨页签、跨浏览器或跨电脑续接正在输出的当前回答，直到 run 终态。
- `GET /api/v1/ex/chat/sessions/{sessionId}/stream-status`：查询当前会话最新事件序号、服务端 read cursor、active run、`activeStreamTopicId` 和是否可取消。
- `POST /api/v1/ex/chat/runs/{runId}/stop`：按 runId 停止当前回答，幂等返回 run 状态。
- `POST /api/v1/ex/chat/messages/{messageId}/feedback`：提交 assistant 消息反馈。

前端流式模式：

```text
POST /chat/runs
 -> 获取 runId/sessionId/firstSeq/streamTopicId
 -> 使用前端配置的 WebSocket 地址发送 subscribe(topicId=streamTopicId, afterSeq)
 -> 实时输出由 WebSocket run topic 承载
 -> 浏览器刷新/复制页签后，使用前端配置的 SSE resume 地址按 lastSeq 补齐缺失事件
 -> 新页签、新浏览器或跨电脑续接 active run 时，从 activeRunFirstSeq - 1 打开 run SSE
 -> run SSE 先补发历史事件，再持续接续 live 事件，直到本轮 run 终态
 -> 用户点击停止时调用前端配置的 stop 接口，服务端发布 run.cancelled 终态事件
```

当前请求体只有对话文本和可选文档附件，不暴露 IM 消息类型，也不让前端选择多套响应协议。文档不是消息类型，只是对话消息的上下文资源引用。
WebSocket、SSE resume 和 stop 的 URL 由前端 SDK 或网关配置管理，不随 `/chat/runs` 响应返回。

`/chat/runs` 支持消息树写入模式：`runMode=NEXT` 表示沿当前 leaf 继续提问；`EDIT_USER` 表示编辑历史 user 消息并创建新的 user sibling；`REGENERATE_ASSISTANT` 表示复用原 user 消息重新生成新的 assistant sibling。历史版本不会被覆盖，前端通过 variants 与 path select 切换展示版本。

仓库提供独立本地联调台 `local-test-frontend/`。联调台通过 Node 代理访问后端，支持在页面中按 Postman 风格配置 `Cookie`、`Authorization`、`X-*` 等企业鉴权请求头；代理会在 HTTP、fetch SSE、文件下载和 WebSocket 握手时统一注入这些请求头。浏览器自身不会、也不能直接手写 `Cookie` 请求头或 WebSocket 自定义请求头。

租户和用户身份不从前端 Header/Query/Body 透传，统一由请求入口通过 `AuthContextProvider` 从服务端身份上下文解析一次，并以不可变 `UserContext` 传入应用层。应用层、后台 run 和 `boundedElastic` 阻塞线程不会再次读取请求 ThreadLocal。本地开发态必须显式配置：

MVC/Servlet WebSocket 是一个特殊入口：用户身份必须在 `HandshakeInterceptor.beforeHandshake`
阶段从企业 ThreadLocal 解析并写入 WebSocket session attributes。`afterConnectionEstablished`、
subscribe、ack 和连接关闭回调只读取该身份快照，不会再次调用 `AuthContextProvider`。

```bash
export FINANCEEX_DEV_TENANT_ID=tenant_dev
export FINANCEEX_DEV_USER_ID=user_dev
export FINANCEEX_DEV_USERNAME=developer
```

`metadata.forceNewTask=true` 会取消当前 active RuntimeBinding，并重新读取可选路由信号；如果用例库和意图服务都关闭，则直接进入 Relay Runtime。

## 会话与执行标识

- `sessionId`：前端聊天会话 ID，一次聊天会话内可以包含多轮用户请求。
- `messageId`：完整 user/assistant 历史消息 ID，组成会话内消息树。
- `currentLeafMessageId`：会话当前激活路径叶子，历史查询默认从该 leaf 回溯 root。
- `runId`：SuperAgent 为每一轮用户请求生成的执行追踪 ID。
- `streamTopicId`：本轮 run 的 WebSocket 订阅 topic，格式为 `chat-run-{runId}`。
- `runtimeSessionId`：当前 AgentRuntime provider 自己的会话 ID，由 Runtime 返回后保存在 RuntimeBinding 中，下一轮续接时带回。

`runId` 不是长期任务会话；它是单轮执行 correlation id。事件表 `fin_ex_chat_event_t.run_id` 和绑定表 `fin_ex_runtime_binding_t.last_run_id` 都用它做运行轨迹和排障定位。
run 生命周期事实源保存在 `fin_ex_chat_run_t`，状态包括 `RUNNING`、`CANCELLING`、`CANCELLED`、`COMPLETED`、`FAILED`。stop 只停止本轮回答，不删除 `RuntimeBinding`。
集群部署时，取消正确性依赖 Redis cancel flag 和 openGauss run 状态；JVM 内 subscription registry 只用于命中本机执行流时快速释放资源，不作为跨实例事实源。

## 消息树与只读分支

`fin_ex_chat_message_t.parent_message_id` 形成会话内消息树，`node_order/tree_depth/sibling_index` 用于稳定排序和版本切换。普通继续提问会在当前 leaf 后追加 `user -> assistant`；编辑历史问题会在原 user 的父节点下创建新的 user sibling；重新生成回答会在同一个 user 下创建新的 assistant sibling。只有 `run.completed` 后才保存完整 assistant 历史消息，stop/failed 的半截输出只保存在事件事实源中。

从某条消息新建分支时，服务端会复制 root 到该消息的可见路径到新 session，并将复制出的历史消息标记为 `origin_type=BRANCH_SNAPSHOT`、`locked=true`。这些快照消息只能展示和继续向后提问，不能编辑、删除或重新生成；分支后续新增消息仍为 `NORMAL`，可以参与消息树版本管理。

## 存储命名

所有数据库表统一使用 `fin_ex_*_t`：

- `fin_ex_chat_session_t`
- `fin_ex_chat_message_t`
- `fin_ex_chat_message_attachment_t`
- `fin_ex_chat_run_t`
- `fin_ex_chat_event_t`
- `fin_ex_chat_read_cursor_t`
- `fin_ex_uploaded_document_t`
- `fin_ex_message_feedback_t`
- `fin_ex_runtime_binding_t`

所有 Redis key 统一以 `fin_ex` 开头：

- RuntimeBinding：`fin_ex:runtime_binding:{tenantId:userId:sessionId}:{leafMessageId}`
- RuntimeBinding 会话索引：`fin_ex:runtime_binding:index:{tenantId:userId:sessionId}`
- Active run：`fin_ex:chat_run:active:{tenantId}:{userId}:{sessionId}`
- Cancel flag：`fin_ex:chat_run:cancel:{runId}`
- Read cursor：`fin_ex:chat_read_cursor:{tenantId}:{userId}:{sessionId}`
- WebSocket run topic：`fin_ex:chat_stream:{streamTopicId}`
- 短期消息：`fin_ex:memory:short_term:messages:{tenantId}:{userId}:{sessionId}`

RuntimeBinding key 使用 Redis hash tag（花括号部分）把同一会话的 leaf binding 和索引集合放到同一 slot，
会话级清理时不需要 `KEYS`，也不会触发 Redis Cluster 的跨 slot 批量删除问题。

## 可选记忆上下文

ChatService 保留未来演进为独立 SuperAgent 的记忆扩展点，但正式首版默认不启用：

```bash
export FINANCEEX_MEMORY_SHORT_TERM_ENABLED=false
export FINANCEEX_MEMORY_SHORT_TERM_RECENT_TURNS=5
export FINANCEEX_MEMORY_SHORT_TERM_CACHE_ENABLED=true

export FINANCEEX_MEMORY_LONG_TERM_ENABLED=false
export FINANCEEX_MEMORY_LONG_TERM_PROVIDER=disabled
export FINANCEEX_MEMORY_LONG_TERM_TOP_K=5
```

- 短期记忆开启后，按 `recent-turns` 装配最近几轮 user/assistant 问答，优先读 Redis 热缓存，miss 后回源 openGauss 历史消息并回填。
- 长期记忆开启后，通过 `LongTermMemoryStore` 防腐层按当前 query 检索 topK 条相关记忆；默认 `disabled` provider 返回空结果。
- 两者都关闭时，`MemoryContext` 为空上下文，且不会发生 memory 相关 Redis、历史消息读取或长期记忆调用。

## 外部服务接入

用例库和意图服务是可选路由信号，默认关闭；关闭时不会发生外部 HTTP 调用。SubAgent 当前通过单轮 HTTP 文本流接入；Relay Runtime 通过 AgentRuntime 防腐层接入，并在 Relay provider 内部通过 `api-adapter` 选择真实 Relay HTTP、DeepSeek 替身或 Relay WebSocket 接入实现。

这里有两条不同的 WebSocket 边界，不要混淆：

- 前端 WebSocket：`/api/v1/ex/chat/ws`，只连接 FinanceEXChatService，用于订阅 `streamTopicId` 并接收已经落库的 ChatEvent。
- RelayAgent WebSocket：仅当 `FINANCEEX_RELAY_AGENT_API_ADAPTER=relay-websocket` 时，由 FinanceEXChatService 后端作为客户端主动连接 RelayAgent；前端不直接连接 RelayAgent，也不通过前端 WebSocket 发起 `AgentRuntime.query`。

前端 WebSocket 入口同时兼容两种 Spring 启动模式：纯 WebFlux 启动时使用 WebFlux
`WebSocketHandler`；企业框架引入 `spring-boot-starter-web` 并以 MVC/Servlet 模式启动时，
使用 Servlet WebSocket handler 注册同一路径和同一套协议。如果 Servlet 应用配置
`server.servlet.context-path=/fin/ex`，前端最终连接地址是
`ws://host:port/fin/ex/api/v1/ex/chat/ws`；如果是 WebFlux 应用，则使用
`spring.webflux.base-path=/fin/ex`。

```bash
export FINANCEEX_USE_CASE_LIBRARY_ENABLED=true
export FINANCEEX_USE_CASE_LIBRARY_BASE_URL=http://use-case-library:9100
export FINANCEEX_USE_CASE_LIBRARY_MATCH_PATH=/v1/use-cases/match

export FINANCEEX_INTENT_ENABLED=true
export FINANCEEX_INTENT_BASE_URL=http://intent-service:9200
export FINANCEEX_INTENT_RECOGNIZE_PATH=/v1/intents/recognize

export FINANCEEX_EMPLOYEE_REIMBURSEMENT_AGENT_ENDPOINT=http://employee-reimbursement-agent:9300/v1/query
export FINANCEEX_EMPLOYEE_REIMBURSEMENT_AGENT_STOP_ENDPOINT=http://employee-reimbursement-agent:9300/v1/stop

export FINANCEEX_AGENT_RUNTIME_PROVIDER=relay
export FINANCEEX_RELAY_AGENT_API_ADAPTER=relay-stream-http
export FINANCEEX_RELAY_AGENT_BASE_URL=http://relay-agent:9000
export FINANCEEX_RELAY_AGENT_STREAM_PATH=/v1/agent/runs/stream
export FINANCEEX_RELAY_AGENT_STOP_PATH=/v1/agent/runs/{runId}/stop
# 如果 RelayAgent 对话接口使用 WebSocket，则保持 provider=relay，只切换 api-adapter：
export FINANCEEX_RELAY_AGENT_API_ADAPTER=relay-websocket
export FINANCEEX_RELAY_AGENT_WEBSOCKET_PATH=/v1/agent/runs/ws
```

SubAgent endpoint 是完整 HTTP 地址，当前正式版本支持单轮 HTTP 文本流调用。Relay Runtime 作为 AgentRuntime 实现支持三个 API adapter：`relay-stream-http` 使用真实 Relay HTTP 流式协议，`deepseek-chat-completions` 使用 DeepSeek/OpenAI-compatible Chat Completions 替身，`relay-websocket` 使用 RelayAgent WebSocket 对话协议。

真实 Relay streamable-http 服务未就绪时，可以把 Relay HTTP adapter 切到 DeepSeek/OpenAI-compatible Chat Completions wire format 做本地联调。API key 必须只通过环境变量或密钥系统注入，不要写入仓库：

```bash
export DEEPSEEK_API_KEY='<your-local-secret>'
export FINANCEEX_AGENT_RUNTIME_PROVIDER=relay
export FINANCEEX_RELAY_AGENT_API_ADAPTER=deepseek-chat-completions
export FINANCEEX_RELAY_AGENT_BASE_URL=https://api.deepseek.com
export FINANCEEX_RELAY_AGENT_STREAM_PATH=/chat/completions
export FINANCEEX_RELAY_AGENT_API_KEY="${DEEPSEEK_API_KEY}"
export FINANCEEX_RELAY_AGENT_MODEL=deepseek-v4-pro
export FINANCEEX_RELAY_AGENT_STREAM=true
export FINANCEEX_RELAY_AGENT_THINKING_ENABLED=true
export FINANCEEX_RELAY_AGENT_REASONING_EFFORT=high
export FINANCEEX_RELAY_AGENT_CANCEL_SUPPORTED=false
export FINANCEEX_RELAY_AGENT_STOP_PATH=
```

该模式只改变后端出站 Runtime API adapter：前端仍然使用 `/chat/runs + WebSocket subscribe` 接收本页新建 run 的实时输出，使用 run SSE resume 接续已经存在的 active run；前端不会直接调用 DeepSeek，也不会看到下游 API key。本地验证流式体验时建议保持 `FINANCEEX_RELAY_AGENT_STREAM=true`；如果改成 `false`，DeepSeek 会等完整响应返回后才由后端一次性拆成事件，页面在首个可展示 token 前会像“卡住”。

## 上线版本边界

当前上线版本明确不包含 AgentScope 设计和实现，也不包含 AgentScope memory、prompt assembler 或相关配置。复杂任务通过 Relay Runtime adapter 执行，默认 `provider=relay`、`api-adapter=relay-stream-http`，也可以切换为 `deepseek-chat-completions` 或 `relay-websocket`。

AgentRuntime 防腐层必须保留：应用层只依赖 `AgentRuntime` 接口和 `AgentRuntimeRequest` 契约，不依赖 Relay 的 HTTP、DeepSeek 或 WebSocket 协议细节。`financeex.agent-runtime.provider` 表示 Runtime 类型，当前为 `relay`；`financeex.agent-runtime.api-adapter` 表示 relay provider 下的 API 接入 adapter。后续替换 Runtime 实现时，应新增另一个 `AgentRuntime` provider；后续只替换 Relay 下游协议时，应新增 `RelayRuntimeProtocolAdapter` 实现。

## 启动

本地没有 PostgreSQL/Redis 时，可以先启动 Docker 依赖：

```bash
docker compose up -d postgres redis
```

PostgreSQL 容器会创建 `financeex` 数据库和 `supervisor_dev` schema，并执行 `src/main/resources/db/schema.sql`。

Redis 默认使用本地 standalone；生产 Redis Cluster 可用以下环境变量切换：

```bash
export FINANCEEX_REDIS_MODE=cluster
export FINANCEEX_REDIS_CLUSTER_NODES=10.0.0.1:6379,10.0.0.2:6379,10.0.0.3:6379
export FINANCEEX_REDIS_PASSWORD=kunone123
export FINANCEEX_REDIS_CLUSTER_MAX_REDIRECTS=3
```

切到 cluster 后，业务代码仍然只使用 `StringRedisTemplate`。openGauss 仍是事实源；Redis Cluster
只负责热缓存、取消标记、read cursor 加速和 WebSocket 跨实例实时 fanout。

```bash
mvn spring-boot:run
```

## 文档存储

文档能力分为“文档库资产”和“对象内容”两层：前端仍然把本地文件上传到 FinanceEXChatService 统一后端，
后端再通过 `ObjectStorage` 防腐层写入本地文件系统或华为 OBS S3 对象存储。
openGauss 的 `fin_ex_uploaded_document_t` 保存文档库元数据，聊天请求只引用 `documentId`，不会把文件正文放进消息体。
上传接口对外只有一条 `POST /api/v1/ex/documents`，服务端会按启动模式自动选择适配器：
Servlet/MVC 使用 `MultipartFile`，纯 WebFlux 使用 `FilePart`，两者共用同一套临时落盘和 ObjectStorage 写入逻辑。

文档接口：

- `POST /api/v1/ex/documents`：上传本地文件并登记到文档库。
- `GET /api/v1/ex/documents?sessionId=...&limit=20&cursor=...`：分页查询当前用户文档库，`sessionId` 可选。
- `GET /api/v1/ex/documents/{documentId}`：查询单个文档。
- `PATCH /api/v1/ex/documents/{documentId}`：更新文档展示名或扩展元数据。
- `GET /api/v1/ex/documents/{documentId}/status`：查询文档处理状态。
- `GET /api/v1/ex/documents/{documentId}/preview-url`：获取后端受控预览地址。
- `GET /api/v1/ex/documents/{documentId}/download`：下载文档对象内容。
- `DELETE /api/v1/ex/documents/{documentId}`：软删除文档。

聊天附件应使用文档库返回的 `id`：

```json
{
  "message": "分析一下这个文件",
  "attachments": [
    {
      "documentId": "doc_xxx"
    }
  ]
}
```

服务端会在进入 Runtime 前回查文档库，补齐可信的文件名、MIME、大小、来源和 tokenSize，并校验文档归属和状态。

默认使用本地文件系统：

```yaml
financeex.storage.provider: local
```

切换到华为 OBS S3：

```bash
export FINANCEEX_STORAGE_PROVIDER=huawei-s3
export FINANCEEX_HUAWEI_S3_BUCKET=financeex-documents
export FINANCEEX_HUAWEI_S3_ENDPOINT=https://obs.cn-north-4.myhuaweicloud.com
export FINANCEEX_HUAWEI_S3_ACCESS_KEY=your-access-key
export FINANCEEX_HUAWEI_S3_SECRET_KEY=your-secret-key
export FINANCEEX_HUAWEI_S3_KEY_PREFIX=documents
```
