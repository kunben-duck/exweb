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
 -> 身份和会话解析
 -> 统一上下文装配
 -> AgentBinding 查询
    -> 有 active binding：继续调用绑定的 SubAgent 或 AgentRuntime
    -> 无 active binding：调用用例库服务
        -> 命中并返回 subAgentCode：调用指定 SubAgent
        -> 未命中：调用 IntentService
            -> 简单任务且有 subAgentCode：调用指定 SubAgent
            -> 复杂/低置信/无 subAgentCode：进入 AgentRuntime
```

## 分层边界

- `interfaces`：SSE、NDJSON、WebSocket、会话和文档上传协议适配。
- `application`：聊天主编排、会话、记忆、AgentBinding、SubAgent 调用和 AgentRuntime 调用。
- `domain`：聊天事件、意图结果、路由结果、AgentBinding、用例匹配结果等核心模型。
- `infrastructure`：Redis、openGauss/MyBatis、用例库 HTTP、SubAgent HTTP、AgentRuntime provider、对象存储等适配。

## 前端接入协议

聊天接口保持兼容：

- `POST /api/v1/finance/chat/sse`：SSE，返回 `text/event-stream`
- `POST /api/v1/finance/chat/stream`：HTTP Stream，返回 NDJSON
- `WS /api/v1/finance/chat/ws`：WebSocket，客户端发送请求 JSON，服务端逐条返回事件 JSON

HTTP Header：

```text
X-Tenant-Id: default
X-User-Id: anonymous
```

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

`metadata.forceNewTask=true` 会取消当前 active AgentBinding，并重新走用例库/意图服务路由。

## 存储命名

所有数据库表统一使用 `fin_ex_*_t`：

- `fin_ex_chat_session_t`
- `fin_ex_chat_message_t`
- `fin_ex_chat_event_t`
- `fin_ex_conversation_summary_t`
- `fin_ex_uploaded_document_t`
- `fin_ex_agent_binding_t`

所有 Redis key 统一以 `fin_ex` 开头：

- AgentBinding：`fin_ex:agent_binding:{tenantId}:{userId}:{sessionId}`
- 短期消息：`fin_ex:memory:short_term:messages:{tenantId}:{userId}:{sessionId}`
- 工作记忆：`fin_ex:memory:working:variables:{sessionId}`

## 重要边界

- 本服务不再暴露旧的目录和调用接口。
- 简单任务只通过 SubAgent 执行，不再通过旧网关或直连模型兜底。
- 复杂任务进入配置选定的 AgentRuntime provider，当前保留 `relay-agent` 和 `agentscope`。
- AgentRuntime 是独立 Agent，可以维护自己的内部 session、上下文和压缩机制。
- SuperAgent 侧通过 AgentBinding 维护“前端会话到 SubAgent/AgentRuntime 会话”的续接关系。

## 启动

```bash
mvn spring-boot:run
```
