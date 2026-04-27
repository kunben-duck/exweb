# FinanceEXChatService

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

## 目录结构

```text
FinanceEXChatService
├── pom.xml
└── src
    └── main
        ├── java/com/huawei/finance/front/one
        │   ├── domain
        │   ├── application
        │   ├── infrastructure
        │   ├── interfaces
        │   └── bootstrap
        └── resources
```

## 第一版实现范围与目标架构

- FinanceEXChatService 主控编排
- SSE / HTTP Stream 接入
- Session / Message / ChatEvent 持久化 Port
- MemoryContext 装配
- IntentService 意图识别
- RoutingPolicy 轻量路由裁决
- 统一 AgentRuntime 架构
- 高置信简单任务直达工具或模型响应
- 非 fast path 任务进入配置选定的 AgentRuntime
- AgentScope、RelayAgent、Spring AI、LangChain 等作为同级 AgentRuntime provider
- 工具列表查询
- 工具统一调用网关
- Relay Agent 内部工具调用接口
- 文档上传与对象存储 Port

## 架构设计

系统分层、意图识别、轻量路由、简单任务直达、统一 AgentRuntime、会话绑定、记忆体系和工具调用边界见：

- [系统架构设计文档](docs/architecture/README.md)

## 启动

```bash
mvn spring-boot:run
```

## 前端接入协议

聊天接口支持三种传输协议：

- `POST /api/v1/finance/chat/sse`：SSE，返回 `text/event-stream`
- `POST /api/v1/finance/chat/stream`：HTTP Stream，返回 NDJSON
- `WS /api/v1/finance/chat/ws`：WebSocket，客户端每发送一条请求 JSON，服务端逐条返回事件 JSON

HTTP 接口通过 Header 传入身份：

```text
X-Tenant-Id: default
X-User-Id: anonymous
```

WebSocket 同时支持 Header 与 query 参数：

```text
ws://localhost:8080/api/v1/finance/chat/ws?tenantId=default&userId=anonymous
```

请求体：

```json
{
  "commandId": "cmd_001",
  "sessionId": "session_001",
  "conversationId": "conv_001",
  "messageType": "text",
  "responseMode": "stream",
  "message": "查询中国代表处信息",
  "attachments": [
    {
      "documentId": "doc_001",
      "name": "report.pdf",
      "contentType": "application/pdf",
      "sizeBytes": 1024
    }
  ],
  "metadata": {
    "clientMessageId": "front_msg_001"
  }
}
```

`messageType` 支持：`text`、`image`、`file`、`audio`、`video`、`rich_text`、`card`、`location`、`system`。未知类型会归一为 `unknown`。

`responseMode` 支持：

- `stream`：流式返回多个 `message.delta`
- `block`：等待完整回复后返回一个 `message.delta`

未传 `responseMode` 时默认 `block`；兼容历史请求。

响应事件：

```json
{
  "runId": "run_xxx",
  "sessionId": "session_001",
  "sequence": 0,
  "type": "message.delta",
  "messageType": "text",
  "payload": {
    "delta": "..."
  }
}
```

## 重要边界

- `domain` 与 `application` 包不直接引用 `io.agentscope.*`
- AgentScope、RelayAgent、Spring AI、LangChain 等只是 AgentRuntime provider，不承载简单/复杂任务语义
- 所有工具调用必须经过 `ToolGatewayApplicationService`
- RelayAgent / Python Runtime 不直接调用第三方工具
- Memory 由 `MemoryApplicationService` 统一装配

## AgentRuntime 说明

当前实现中，不确定、低置信、需要规划或需要多轮交互的任务统一收敛到 `AgentRuntime` 抽象。AgentScope、远程 RelayAgent、Spring AI、LangChain 等都可以作为同级 provider，通过配置选择服务启动时启用哪一个。

AgentScope provider 使用 AgentScope Java 1.0.9 的 `ReActAgent`、`OpenAIChatModel`、`Toolkit`、`@Tool`、`@ToolParam` 接入。

如果内网 1.0.9 包中的 OpenAI SDK 版本与根 POM 中 `openai-java.version` 不一致，按内网制品仓版本调整根 POM 属性即可。
