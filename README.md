# FinanceEXChatService

FinanceEXChatService 是 FinanceEX 前台聊天入口和 SuperAgent 主控服务。当前 v2 架构已经从“简单任务直达旧能力”调整为“用例库/意图服务命中后直连 SubAgent，复杂任务进入统一 AgentRuntime”。

工程坐标：

```xml
<groupId>com.huawei.finance.front.one</groupId>
<artifactId>FinanceEXChatService</artifactId>
<version>1.0.0-RELEASE</version>
```

顶层包名：

```text
com.huawei.finance.front.one
```

## 核心链路

```text
用户请求
 -> 应用身份解析和会话归一化
 -> 统一上下文装配
 -> AgentBinding + TaskCard 查询
    -> 有 active AgentRuntime binding：继续调用 AgentRuntime
    -> 有 active SubAgent task：先执行 ContinuationGuard
        -> 补参数/确认/上传附件：继续调用原 SubAgent
        -> 明显新任务：挂起当前 TaskCard 并重新路由
        -> 不确定：返回用户澄清问题
    -> 无 active binding：调用用例库服务
        -> 命中并返回 subAgentCode：调用指定 SubAgent
        -> 未命中：调用 IntentService
            -> 简单任务且有 subAgentCode：调用指定 SubAgent
            -> 复杂/低置信/无 subAgentCode：进入 AgentRuntime
```

## 分层边界

- `interfaces`：SSE、NDJSON、WebSocket、会话和文档上传协议适配。
- `application`：聊天主编排、会话、记忆、AgentBinding、SubAgent 调用和 AgentRuntime 调用。
- `application.integration`：应用层出站集成抽象，定义对 AgentRuntime、SubAgent、IntentService、用例库、会话、记忆、文档、ID 和身份能力的依赖边界；具体 Redis、openGauss、HTTP 或对象存储实现仍放在 `infrastructure`。
- `domain`：聊天事件、意图结果、路由结果、AgentBinding、用例匹配结果等核心模型。
- `infrastructure`：Redis、openGauss/MyBatis、用例库 HTTP、SubAgent HTTP、AgentRuntime provider、对象存储等适配。

## 前端接入协议

聊天接口保持兼容：

- `POST /api/v1/finance/chat/sse`：SSE，返回 `text/event-stream`
- `POST /api/v1/finance/chat/stream`：HTTP Stream，返回 NDJSON
- `WS /api/v1/finance/chat/ws`：WebSocket，客户端发送请求 JSON，服务端逐条返回事件 JSON

租户和用户身份不再由前端 Header/Query/Body 传入，统一由应用层 `AuthContextProvider` 从当前服务端身份上下文解析。本地开发态必须通过 `FINANCEEX_DEV_TENANT_ID`、`FINANCEEX_DEV_USER_ID`、`FINANCEEX_DEV_USERNAME` 显式模拟企业身份上下文；缺失时服务会直接拒绝请求，不会兜底为默认租户或匿名用户。当前阶段暂不做 scope 权限控制，`UserContext` 只用于获取用户身份信息。

请求体示例：

```json
{
  "commandId": "cmd_001",
  "sessionId": "session_001",
  "conversationId": "conv_001",
  "messageType": "text",
  "responseMode": "stream",
  "message": "查询中国代表处信息",
  "attachments": [],
  "metadata": {
    "clientMessageId": "front_msg_001",
    "forceNewTask": false
  }
}
```

`metadata.forceNewTask=true` 会取消当前 active TaskCard 和 AgentBinding，并重新走用例库/意图服务路由。

## 会话与执行标识

- `sessionId`：前端聊天会话 ID，一次聊天会话内可以包含多轮用户请求。
- `runId`：SuperAgent 为每一轮用户请求生成的执行追踪 ID，用于把 `run.started`、`message.delta`、`message.completed`、`run.completed` 或 `run.failed` 串成同一次响应。
- `taskId`：SuperAgent 为一个可续接业务任务生成的 ID。员工报销等简单任务进入 SubAgent 后会创建 `TaskCard`，后续是否续接由 `ContinuationGuard` 判断。
- `agentSessionId`：SubAgent 自己的会话 ID，由下游返回后保存在 AgentBinding 中，下一轮续接时原样带回。
- `runtimeSessionId`：AgentRuntime 自己的会话 ID，由 Runtime provider 维护，SuperAgent 只负责保存和转发。

`runId` 不代表长期任务会话，也不是工具调用残留；它是单轮执行的 correlation id。事件表 `fin_ex_chat_event_t.run_id` 和绑定表 `fin_ex_agent_binding_t.last_run_id` 都用它做运行轨迹和排障定位。

## 存储命名

所有数据库表统一使用 `fin_ex_*_t`：

- `fin_ex_chat_session_t`
- `fin_ex_chat_message_t`
- `fin_ex_chat_event_t`
- `fin_ex_conversation_summary_t`
- `fin_ex_uploaded_document_t`
- `fin_ex_agent_binding_t`
- `fin_ex_task_card_t`
- `fin_ex_task_event_t`

所有 Redis key 统一以 `fin_ex` 开头：

- AgentBinding：`fin_ex:agent_binding:{tenantId}:{userId}:{sessionId}`
- Active Task：`fin_ex:task:active:{tenantId}:{userId}:{sessionId}`
- TaskCard：`fin_ex:task:card:{tenantId}:{userId}:{sessionId}:{taskId}`
- 短期消息：`fin_ex:memory:short_term:messages:{tenantId}:{userId}:{sessionId}`
- 工作记忆：`fin_ex:memory:working:variables:{sessionId}`

## 重要边界

- 本服务不再暴露旧的目录和调用接口。
- 简单任务只通过 SubAgent 执行，不再通过旧网关或直连模型兜底。
- SubAgent binding 不是强粘性路由锁。存在 active task 时，只有用户补参数、确认上一轮问题、解释当前任务或上传当前任务附件，才继续调用原 SubAgent。
- 员工报销 SubAgent 使用 `natural-language-contract`：SuperAgent 生成增强任务 Prompt，要求下游返回 JSON；下游响应不规范时由 `SubAgentResponseNormalizer` 兜底解析。
- SubAgent 响应无法判断状态时不会默认完成或继续粘住，而是进入 `WAITING_USER_CONFIRMATION` 并向用户澄清。
- 复杂任务进入配置选定的 AgentRuntime provider，当前保留 `relay-agent` 和 `agentscope`。
- AgentRuntime 是独立 Agent，可以维护自己的内部 session、上下文和压缩机制。
- SuperAgent 侧通过 AgentBinding 维护“前端会话到 SubAgent/AgentRuntime 会话”的续接索引，通过 TaskCard 维护简单业务任务状态事实。

## 员工报销 SubAgent

员工报销 Agent 固定编码为 `employee_reimbursement_agent`，默认交互模式为自然语言契约：

```yaml
financeex.sub-agent.agents.employee_reimbursement_agent.interaction-mode: natural-language-contract
```

服务地址通过环境变量配置：

```bash
export FINANCEEX_EMPLOYEE_REIMBURSEMENT_AGENT_ENDPOINT=http://localhost:9300/v1/employee-reimbursement/query
```

SubAgent 返回标准 JSON 时会直接更新 TaskCard；返回 markdown JSON code block 或普通文本时，`SubAgentResponseNormalizer` 会执行字段别名映射和文本推断，例如“请上传发票图片”会推断为 `REQUIRES_USER_INPUT`，“好的，我先看一下”会转为 `WAITING_USER_CONFIRMATION`。

## 外部服务接入

生产代码不再包含本地 mock 服务。意图服务、用例库服务、SubAgent 和 RelayAgentRuntime 都通过 HTTP API 接入；切换环境时只需要替换配置中的 API 地址和路径。

```bash
export FINANCEEX_USE_CASE_LIBRARY_BASE_URL=http://use-case-library:9100
export FINANCEEX_USE_CASE_LIBRARY_MATCH_PATH=/v1/use-cases/match
export FINANCEEX_INTENT_BASE_URL=http://intent-service:9200
export FINANCEEX_INTENT_RECOGNIZE_PATH=/v1/intents/recognize
export FINANCEEX_EMPLOYEE_REIMBURSEMENT_AGENT_ENDPOINT=http://employee-reimbursement-agent:9300/v1/query
export FINANCEEX_RELAY_AGENT_BASE_URL=http://relay-agent:9000
export FINANCEEX_RELAY_AGENT_STREAM_PATH=/v1/agent/runs/stream
```

SubAgent endpoint 是完整 HTTP 地址，支持流式文本返回。员工报销默认使用 `natural-language-contract`，服务会聚合下游流式片段并标准化为统一任务状态。普通 SubAgent 可配置为 `raw-text`，事件协议仍统一输出 `message.delta` 和 `message.completed`。

长期记忆默认是 `disabled`，表示不启用外部长记忆服务，也不会注入伪造记忆；接入真实长记忆服务时新增对应 `LongTermMemoryStore` 适配器即可。

## 启动

本地没有 PostgreSQL/Redis/MinIO 时，可以先启动 Docker 依赖：

```bash
docker compose up -d postgres redis minio minio-init
```

PostgreSQL 容器会创建 `financeex` 数据库和 `supervisor_dev` schema，并执行 `src/main/resources/db/schema.sql`。
MinIO 控制台地址是 `http://localhost:9001`，本地账号密码为 `fin_supervisor / kunone123`，默认 bucket 为 `financeex-documents`。

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

也可以直接启用 `minio` profile：

```bash
SPRING_PROFILES_ACTIVE=minio mvn spring-boot:run
```

华为 OBS/S3 兼容存储也走同一套配置：把 `FINANCEEX_S3_ENDPOINT`、`FINANCEEX_S3_BUCKET`、`FINANCEEX_S3_REGION` 和 AK/SK 换成对应环境即可。如果运行环境提供默认凭证链，可以不配置 access key 和 secret key。

MinIO 集成测试会在 `localhost:9000` 可达时真实写入并读回对象；未启动 MinIO 时会自动跳过。
