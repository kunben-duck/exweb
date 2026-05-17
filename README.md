# FinanceEXChatService

FinanceEXChatService 是 FinanceEX 前台聊天入口和 SuperAgent 主控服务。当前正式版本采用干净的两段式架构：简单任务通过用例库或意图服务命中后按 `agentCode` 单轮调用 SubAgent；复杂任务、低置信任务和未命中任务统一进入 Relay Runtime，并仅对 Relay Runtime 做多轮会话保持。

## 核心链路

```text
用户请求
 -> Controller/WebSocket 入口通过 AuthContextProvider 解析租户和用户
 -> 将不可变 UserContext 显式传入 application
 -> 会话归一化与 MemoryContext 装配
 -> 查询 RuntimeBinding
    -> 有 active RuntimeBinding：继续调用 Relay Runtime
    -> 无 active RuntimeBinding：读取可选路由信号
        -> 用例库开启且命中 subAgentCode：单轮调用指定 SubAgent
        -> 意图服务开启且识别为简单任务：单轮调用指定 SubAgent
        -> 两者关闭/未命中/不可用/复杂任务：创建 RuntimeBinding 并调用 Relay Runtime
```

SubAgent 不创建绑定、不续接会话、不维护任务状态。只有 Relay Runtime 拥有多轮能力，内部 session、上下文压缩和规划机制由 Relay Runtime 自己负责。

## 分层边界

- `interfaces`：`/chat/runs`、WebSocket run topic subscribe、SSE resume、会话和文档上传协议适配。
- `application`：聊天主编排、会话、记忆、RuntimeBinding、SubAgent 单轮调用和 Relay Runtime 调用。
- `application.integration`：应用层出站集成抽象，定义对 Relay Runtime、SubAgent、IntentService、用例库、会话、记忆、文档、ID 和身份能力的依赖边界。
- `domain`：聊天事件、意图结果、路由结果、RuntimeBinding、用例匹配结果等核心模型。
- `infrastructure`：Redis、openGauss/MyBatis、用例库 HTTP、SubAgent HTTP、Relay Runtime HTTP、对象存储等适配。

## 前端接入协议

完整接口和 WebSocket 联调说明见 [前端联调文档](docs/frontend-integration.md)。

- `POST /api/v1/ex/chat/runs`：唯一提问入口。创建后台 run，返回 `runId`、`sessionId`、`firstSeq` 和 `streamTopicId`。
- `GET /api/v1/ex/chat/sessions?limit=20&cursor=...`：分页查询当前用户会话列表。
- `GET /api/v1/ex/chat/sessions/{sessionId}/state?messageLimit=50`：选择会话时聚合返回会话元数据、最近历史消息和流式状态。
- `GET /api/v1/ex/chat/sessions/{sessionId}/messages?limit=50&cursor=...`：选择会话后分页查询历史消息，按时间正序返回完整 user/assistant 消息。
- `WS /api/v1/ex/chat/ws`：用户级实时输出通道。客户端使用 `{"type":"subscribe","topicId":"chat-run-{runId}","afterSeq":0}` 订阅本轮 run topic。
- `GET /api/v1/ex/chat/sessions/{sessionId}/events/sse?afterSeq={seq}`：SSE 事件补发和续传。
- `GET /api/v1/ex/chat/sessions/{sessionId}/stream-status`：查询当前会话最新事件序号、active run、`activeStreamTopicId` 和是否可取消。
- `POST /api/v1/ex/chat/runs/{runId}/stop`：按 runId 停止当前回答，幂等返回 run 状态。
- `POST /api/v1/ex/chat/runs/{runId}/retry`：基于原 run 所属会话重新生成回答。
- `POST /api/v1/ex/chat/messages/{messageId}/feedback`：提交 assistant 消息反馈。

前端流式模式：

```text
POST /chat/runs
 -> 获取 runId/sessionId/firstSeq/streamTopicId
 -> 使用前端配置的 WebSocket 地址发送 subscribe(topicId=streamTopicId, afterSeq)
 -> 实时输出由 WebSocket run topic 承载
 -> 浏览器刷新/复制页签后，使用前端配置的 SSE resume 地址或 WS afterSeq 补齐缺失事件
 -> 用户点击停止时调用前端配置的 stop 接口，服务端发布 run.cancelled 终态事件
```

当前请求体只有对话文本和可选文档附件，不暴露 IM 消息类型，也不让前端选择多套响应协议。文档不是消息类型，只是对话消息的上下文资源引用。
WebSocket、SSE resume 和 stop 的 URL 由前端 SDK 或网关配置管理，不随 `/chat/runs` 响应返回。

租户和用户身份不从前端 Header/Query/Body 透传，统一由请求入口通过 `AuthContextProvider` 从服务端身份上下文解析一次，并以不可变 `UserContext` 传入应用层。应用层、后台 run 和 `boundedElastic` 阻塞线程不会再次读取请求 ThreadLocal。本地开发态必须显式配置：

```bash
export FINANCEEX_DEV_TENANT_ID=tenant_dev
export FINANCEEX_DEV_USER_ID=user_dev
export FINANCEEX_DEV_USERNAME=developer
```

`metadata.forceNewTask=true` 会取消当前 active RuntimeBinding，并重新读取可选路由信号；如果用例库和意图服务都关闭，则直接进入 Relay Runtime。

## 会话与执行标识

- `sessionId`：前端聊天会话 ID，一次聊天会话内可以包含多轮用户请求。
- `runId`：SuperAgent 为每一轮用户请求生成的执行追踪 ID。
- `streamTopicId`：本轮 run 的 WebSocket 订阅 topic，格式为 `chat-run-{runId}`。
- `runtimeSessionId`：当前 AgentRuntime provider 自己的会话 ID，由 Runtime 返回后保存在 RuntimeBinding 中，下一轮续接时带回。

`runId` 不是长期任务会话；它是单轮执行 correlation id。事件表 `fin_ex_chat_event_t.run_id` 和绑定表 `fin_ex_runtime_binding_t.last_run_id` 都用它做运行轨迹和排障定位。
run 生命周期事实源保存在 `fin_ex_chat_run_t`，状态包括 `RUNNING`、`CANCELLING`、`CANCELLED`、`COMPLETED`、`FAILED`。stop 只停止本轮回答，不删除 `RuntimeBinding`。
集群部署时，取消正确性依赖 Redis cancel flag 和 openGauss run 状态；JVM 内 subscription registry 只用于命中本机执行流时快速释放资源，不作为跨实例事实源。

## 存储命名

所有数据库表统一使用 `fin_ex_*_t`：

- `fin_ex_chat_session_t`
- `fin_ex_chat_message_t`
- `fin_ex_chat_run_t`
- `fin_ex_chat_event_t`
- `fin_ex_uploaded_document_t`
- `fin_ex_message_feedback_t`
- `fin_ex_runtime_binding_t`

所有 Redis key 统一以 `fin_ex` 开头：

- RuntimeBinding：`fin_ex:runtime_binding:{tenantId}:{userId}:{sessionId}`
- Active run：`fin_ex:chat_run:active:{tenantId}:{userId}:{sessionId}`
- Cancel flag：`fin_ex:chat_run:cancel:{runId}`
- WebSocket run topic：`fin_ex:chat_stream:{streamTopicId}`
- 短期消息：`fin_ex:memory:short_term:messages:{tenantId}:{userId}:{sessionId}`
- 工作记忆：`fin_ex:memory:working:variables:{sessionId}`

## 外部服务接入

用例库和意图服务是可选路由信号，默认关闭；关闭时不会发生外部 HTTP 调用。SubAgent 与 Relay Runtime 都通过 HTTP API 接入，切换环境只需要替换配置。

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
export FINANCEEX_RELAY_AGENT_BASE_URL=http://relay-agent:9000
export FINANCEEX_RELAY_AGENT_STREAM_PATH=/v1/agent/runs/stream
export FINANCEEX_RELAY_AGENT_STOP_PATH=/v1/agent/runs/{runId}/stop
```

SubAgent endpoint 是完整 HTTP 地址，当前正式版本支持单轮 HTTP 文本流调用。Relay Runtime 是唯一 AgentRuntime 实现。

## 上线版本边界

当前上线版本明确不包含 AgentScope 设计和实现，也不包含 AgentScope memory、prompt assembler 或相关配置。复杂任务默认通过 Relay Runtime HTTP API 执行。

AgentRuntime 防腐层必须保留：应用层只依赖 `AgentRuntime` 接口和 `AgentRuntimeRequest` 契约，不依赖 Relay 的 HTTP 协议细节。当前 `financeex.agent-runtime.provider=relay` 只装配 Relay adapter；后续替换 Runtime 实现时，应新增另一个 `AgentRuntime` adapter，并通过 provider 配置切换。

## 启动

本地没有 PostgreSQL/Redis/MinIO 时，可以先启动 Docker 依赖：

```bash
docker compose up -d postgres redis minio minio-init
```

PostgreSQL 容器会创建 `financeex` 数据库和 `supervisor_dev` schema，并执行 `src/main/resources/db/schema.sql`。MinIO 控制台地址是 `http://localhost:9001`，本地账号密码为 `fin_supervisor / kunone123`，默认 bucket 为 `financeex-documents`。

```bash
mvn spring-boot:run
```

## 文档存储

文档能力分为“文档库资产”和“对象内容”两层：前端仍然把本地文件上传到 FinanceEXChatService 统一后端，
后端再通过 `ObjectStorage` 防腐层写入本地文件系统、MinIO、AWS S3 或华为 OBS S3 兼容接口。
openGauss 的 `fin_ex_uploaded_document_t` 保存文档库元数据，聊天请求只引用 `documentId`，不会把文件正文放进消息体。

文档接口：

- `POST /api/v1/ex/documents`：上传本地文件并登记到文档库。
- `GET /api/v1/ex/documents?limit=20&cursor=...`：分页查询当前用户文档库。
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

本地切换到 MinIO：

```bash
export FINANCEEX_STORAGE_PROVIDER=s3
export FINANCEEX_S3_BUCKET=financeex-documents
export FINANCEEX_S3_REGION=us-east-1
export FINANCEEX_S3_ENDPOINT=http://localhost:9000
export FINANCEEX_S3_ACCESS_KEY=fin_supervisor
export FINANCEEX_S3_SECRET_KEY=kunone123
export FINANCEEX_S3_PATH_STYLE_ACCESS_ENABLED=true
```
