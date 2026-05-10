# FinanceEXChatService

FinanceEXChatService 是 FinanceEX 前台聊天入口和 SuperAgent 主控服务。当前正式版本采用干净的两段式架构：简单任务通过用例库或意图服务命中后按 `agentCode` 单轮调用 SubAgent；复杂任务、低置信任务和未命中任务统一进入 Relay Runtime，并仅对 Relay Runtime 做多轮会话保持。

## 核心链路

```text
用户请求
 -> AuthContextProvider 解析租户和用户
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

- `interfaces`：SSE、NDJSON、WebSocket、会话和文档上传协议适配。
- `application`：聊天主编排、会话、记忆、RuntimeBinding、SubAgent 单轮调用和 Relay Runtime 调用。
- `application.integration`：应用层出站集成抽象，定义对 Relay Runtime、SubAgent、IntentService、用例库、会话、记忆、文档、ID 和身份能力的依赖边界。
- `domain`：聊天事件、意图结果、路由结果、RuntimeBinding、用例匹配结果等核心模型。
- `infrastructure`：Redis、openGauss/MyBatis、用例库 HTTP、SubAgent HTTP、Relay Runtime HTTP、对象存储等适配。

## 前端接入协议

- `POST /api/v1/finance/chat/sse`：SSE，返回 `text/event-stream`
- `POST /api/v1/finance/chat/stream`：HTTP Stream，返回 NDJSON
- `WS /api/v1/finance/chat/ws`：WebSocket，客户端发送请求 JSON，服务端逐条返回事件 JSON

租户和用户身份不从前端 Header/Query/Body 透传，统一由 `AuthContextProvider` 从服务端身份上下文解析。本地开发态必须显式配置：

```bash
export FINANCEEX_DEV_TENANT_ID=tenant_dev
export FINANCEEX_DEV_USER_ID=user_dev
export FINANCEEX_DEV_USERNAME=developer
```

`metadata.forceNewTask=true` 会取消当前 active RuntimeBinding，并重新读取可选路由信号；如果用例库和意图服务都关闭，则直接进入 Relay Runtime。

## 会话与执行标识

- `sessionId`：前端聊天会话 ID，一次聊天会话内可以包含多轮用户请求。
- `runId`：SuperAgent 为每一轮用户请求生成的执行追踪 ID。
- `runtimeSessionId`：当前 AgentRuntime provider 自己的会话 ID，由 Runtime 返回后保存在 RuntimeBinding 中，下一轮续接时带回。

`runId` 不是长期任务会话；它是单轮执行 correlation id。事件表 `fin_ex_chat_event_t.run_id` 和绑定表 `fin_ex_runtime_binding_t.last_run_id` 都用它做运行轨迹和排障定位。

## 存储命名

所有数据库表统一使用 `fin_ex_*_t`：

- `fin_ex_chat_session_t`
- `fin_ex_chat_message_t`
- `fin_ex_chat_event_t`
- `fin_ex_conversation_summary_t`
- `fin_ex_uploaded_document_t`
- `fin_ex_runtime_binding_t`

所有 Redis key 统一以 `fin_ex` 开头：

- RuntimeBinding：`fin_ex:runtime_binding:{tenantId}:{userId}:{sessionId}`
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

export FINANCEEX_AGENT_RUNTIME_PROVIDER=relay
export FINANCEEX_RELAY_AGENT_BASE_URL=http://relay-agent:9000
export FINANCEEX_RELAY_AGENT_STREAM_PATH=/v1/agent/runs/stream
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

文档上传通过 `ObjectStorage` 防腐层写入对象存储，openGauss 只保存文档元数据。默认使用本地文件系统：

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
