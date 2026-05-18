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

`BACKEND_URL` 可以换成任意本地或测试环境后端地址，也可以包含后端 context root，例如
`BACKEND_URL=http://localhost:8080/fin/ex`。前端统一请求自身的 `/api/**`，
由 `server.mjs` 代理到真实后端，因此不需要额外配置 CORS。

## 企业鉴权请求头

本地调试企业鉴权框架时，可以在页面左侧“鉴权请求头”区域按 Postman 风格填写请求头：

```text
Cookie: your_enterprise_cookie
Authorization: Bearer your-token
X-User-Ticket: value
```

点击“保存请求头”后，配置会保存到浏览器 `localStorage`，并同步到本地 Node 代理内存。后续所有 `/api/v1/ex/**` HTTP 请求、fetch 方式的 SSE resume、文件上传/下载，以及 `/api/v1/ex/chat/ws` WebSocket 握手都会由本地代理自动注入这些请求头。

需要注意：浏览器出于安全限制，不允许前端 JavaScript 直接设置 `Cookie` 请求头，也不允许给原生 `WebSocket` 设置自定义请求头。因此这里采用本地代理 profile 机制：浏览器只携带非敏感的 profileId，真正的 `Cookie/Authorization/X-*` 由 `server.mjs` 转发到后端。该能力仅用于本地联调，不要把真实 Cookie 提交到仓库或日志。

## 覆盖范围

- 会话：创建、列表、切换、state 聚合、历史消息分页、重命名、归档、恢复、关闭。
- Run：创建、停止回答、重新生成、stream-status 查询。
- WebSocket：连接、connect、subscribe、ack、跨 run topic 订阅。
- SSE resume：会话级 SSE 按 `afterSeq` 有限补发缺失事件；run 级 SSE 在 active run 恢复时补发并接续 live 事件到终态。
- 文档库：上传本地文件、列表、详情、状态、预览地址、下载、改名、删除。
- 附件：选择文档库中 `AVAILABLE` 文档作为聊天附件发送。
- 跨页签续接：复制页签后通过 run SSE 从 `activeRunFirstSeq - 1` 补发当前 active run 已生成事件，并继续接收 live 事件直到本轮 run 终态；active run 恢复期间不会先 replay 本地缓存，避免把同浏览器缓存误认为服务端续传结果。
- 运行态按钮：active run 存在时发送按钮显示“生成中”并禁用，停止按钮保持可用；刷新、复制页签或切换会话后通过 `stream-status` 恢复同样状态。
- 反馈：历史 assistant 消息支持 LIKE/DISLIKE 反馈提交。
- 企业鉴权联调：通过本地代理为 HTTP/SSE/WebSocket 统一注入自定义请求头和 Cookie。

## 联调建议

1. 创建会话或直接发送第一条消息。
2. 发送 run 后观察 WebSocket 日志和消息增量输出。
3. 输出中途点击“复制页签”，新页签会读取同一 `sessionId`，先加载历史，再通过 run SSE resume 持续恢复到本轮 run 终态。
4. 输出中途点击“停止回答”，观察 `run.cancelled` 是否通过 WebSocket 或 SSE resume 到达。
5. 上传文档，选择“作为附件”，再发送消息确认 `attachments[{documentId}]` 能进入请求。

## 跨页签续传测试

测试进行中输出恢复时，建议使用下面的顺序：

1. 在页签 A 中选择或创建会话，发送一条会产生较长输出的问题。
2. 在输出尚未完成时点击“复制页签”，打开页签 B。
3. 页签 B 会先读取 `state`，如果发现 active run，则不会 replay `localStorage` 中的未完成 run 事件，而是调用 `runs/{activeRunId}/events/sse?afterSeq=activeRunFirstSeq-1` 补齐当前回答已经生成的事件，并继续接收后续 live event 直到 run 终态。
4. 本轮 run 恢复期间，页签 B 不会再对同一个 run 发 WebSocket `subscribe`；后续新提问仍按 `/chat/runs + WebSocket subscribe` 走实时通道。
5. 也可以手动刷新页签 B，或关闭页签 A 后在页签 B 点击“SSE 补发”“恢复 active run”验证恢复链路。

这里的关键点是：`stream-status.latestSeq` 只表示服务端事件事实源的最新位置，不能直接作为客户端已消费游标；`readCursorSeq` 也可能来自另一台设备或另一个页签，不能证明当前页面已经渲染到该位置。active run 恢复统一从 `activeRunFirstSeq - 1` 做 run SSE catchup，并通过 `sessionId + sequence` 去重。

## 注意事项

- 该前端不会传 `tenantId/userId`，身份必须由后端入口解析。
- 自定义鉴权请求头只存在于浏览器 localStorage 和本地代理内存；代理重启后刷新页面或重新点击“保存请求头”即可重新同步。
- WebSocket 地址固定为同源代理路径 `/api/v1/ex/chat/ws`。
- 本页新建 run 的实时输出走 WebSocket topic；active run 恢复走 run SSE，先补发再接续 live 到终态。
- 本地事件缓存只用于非 active 场景的测试辅助；active run 恢复必须以服务端 run SSE 为准，不是生产前端的持久化方案。
