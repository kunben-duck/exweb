# FinanceEX 本地联调前端

这是一个独立的本地测试前端，只用于联调 FinanceEXChatService 的正式版接口。它不依赖后端工程构建，也不修改后端代码。

## 启动

先启动后端服务，并确保后端能从服务端身份上下文解析用户。当前开发环境可使用：

```bash
export FINANCEEX_DEV_TENANT_ID=tenant_dev
export FINANCEEX_DEV_USER_ID=user_dev
export FINANCEEX_DEV_USERNAME=developer
```

再启动联调前端：

```bash
cd local-test-frontend
BACKEND_URL=http://localhost:8080 PORT=5173 npm start
```

浏览器访问：

```text
http://localhost:5173
```

`BACKEND_URL` 可以换成任意本地或测试环境后端地址。前端统一请求自身的 `/api/**`，由 `server.mjs` 代理到真实后端，因此不需要额外配置 CORS。

## 覆盖范围

- 会话：创建、列表、切换、state 聚合、历史消息分页、重命名、归档、恢复、关闭。
- Run：创建、停止回答、重新生成、stream-status 查询。
- WebSocket：连接、connect、subscribe、ack、跨 run topic 订阅。
- SSE resume：按 `afterSeq` 补发缺失事件。
- 文档库：上传本地文件、列表、详情、状态、预览地址、下载、改名、删除。
- 附件：选择文档库中 `AVAILABLE` 文档作为聊天附件发送。
- 跨页签续接：使用 `localStorage + BroadcastChannel` 保存最近事件和 `latestSeq`，复制页签后可恢复同一会话的进行中输出。
- 反馈：历史 assistant 消息支持 LIKE/DISLIKE 反馈提交。

## 联调建议

1. 创建会话或直接发送第一条消息。
2. 发送 run 后观察 WebSocket 日志和消息增量输出。
3. 输出中途点击“复制页签”，新页签会读取同一 `sessionId`，先加载历史，再通过本地事件缓存、SSE resume 和 WebSocket subscribe 继续恢复。
4. 输出中途点击“停止回答”，观察 `run.cancelled` 是否通过 WebSocket 或 SSE resume 到达。
5. 上传文档，选择“作为附件”，再发送消息确认 `attachments[{documentId}]` 能进入请求。

## 跨页签续传测试

测试进行中输出恢复时，建议使用下面的顺序：

1. 在页签 A 中选择或创建会话，发送一条会产生较长输出的问题。
2. 在输出尚未完成时点击“复制页签”，打开页签 B。
3. 页签 B 会先读取 `state` 和本地已处理的 `lastSeq`，再调用 `events/sse?afterSeq=lastSeq` 补齐缺失事件。
4. 补齐完成后，页签 B 使用 `activeStreamTopicId` 发起 WebSocket `subscribe`，继续接收实时增量。
5. 也可以手动刷新页签 B，或关闭页签 A 后在页签 B 点击“SSE 补发”“订阅 active run”验证恢复链路。

这里的关键点是：`stream-status.latestSeq` 只表示服务端事件事实源的最新位置，不能直接作为客户端已消费游标；本测试台会用本页实际处理到的 `lastSeq` 作为 resume 的 `afterSeq`。

## 注意事项

- 该前端不会传 `tenantId/userId`，身份必须由后端入口解析。
- WebSocket 地址固定为同源代理路径 `/api/v1/ex/chat/ws`。
- SSE resume 只做缺失事件补发；实时输出仍走 WebSocket topic。
- 本地事件缓存只用于测试复制页签恢复，不是生产前端的持久化方案。
