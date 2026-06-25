# FinanceEXChatService 主流程代码定位与排障分析

## 1. 文档用途

这份文档不是架构介绍，而是用于理解当前代码执行路径和快速定位问题。重点说明一次用户提问从 HTTP 入口进入后，如何创建后台 run、调用 Relay、写入消息与事件、发布到 Redis、本机订阅、WebSocket 推送、Event Resume 恢复，以及 stop、watchdog、消息树相关代码在哪里。

为避免后续代码调整导致行号漂移，本文档以“文件路径 + 类名 + 方法名”为定位方式。需要精确行号时，建议在本地使用：

```bash
rg -n "methodName|className" src/main/java/com/huawei/finance/front/one
```

## 2. 先区分三个容易混淆的对象

### 2.1 ChatMessage：完整历史消息

表：`fin_ex_chat_message_t`

用途：

- 保存用户可见的完整 user / assistant 历史消息。
- user message 在 run 调用 Runtime 前保存。
- assistant message 只在 `run.completed` 前保存完整内容。
- 流式 token / delta 不写入这张表。

关键代码：

- `application/service/chat/SessionApplicationService.java`
- `SessionApplicationService#prepareRunMessage(...)`
- `SessionApplicationService#createUserMessage(...)`
- `SessionApplicationService#saveAssistantMessage(...)`

### 2.2 ChatEvent：流式事件事实

表：`fin_ex_chat_event_t`

用途：

- 保存 `run.started`、`message.delta`、`message.snapshot`、`message.completed`、`runtime.progress`、`runtime.metadata`、`runtime.agent`、`runtime.thinking`、`runtime.tool`、`runtime.reference`、`runtime.card`、`runtime.event`、`run.completed`、`run.failed`、`run.cancelled` 等 ChatService 标准事件。
- WebSocket 实时输出和 Event Resume 断点恢复都基于这张表的事件。
- `seq` 是数据库生成的恢复游标。

表：`fin_ex_chat_message_part_t`

用途：

- 保存 run 正常完成后的 assistant 过程信息，例如进度、思考、工具调用、agent 调用和最终 ANSWER 快照。
- `ChatMessageDto.parts` 会返回这些 part，并提供 `title/status/channel/displayHint/visible`，前端不需要解析 Relay 私有 payload。
- `ANSWER` part 默认隐藏，正文仍由 `ChatMessageDto.content` 展示。

关键代码：

- `application/service/chat/ChatStreamApplicationService.java`
- `ChatStreamApplicationService#appendAndPublish(...)`
- `ChatStreamApplicationService#appendWithExecutionGuard(...)`
- `infrastructure/persistence/MyBatisChatEventStore.java`
- `MyBatisChatEventStore#appendWithExecutionGuard(...)`

### 2.3 RuntimeRawStreamLog：下游原始流日志

表：`fin_ex_runtime_raw_stream_log_t`

用途：

- 保存 Relay normalizer 之前的原始流响应片段。
- 只用于排障、协议分析和下游问题定位，不作为前端恢复事实源。
- 可能保存多个 raw chunk 的窗口合并结果，也可能保存单个超大 chunk 的分片。
- `truncated=true` 只表示确实丢弃了原始内容；普通分片不算截断。
- ChatService 主链路可选通过 `RuntimeRawStreamLogPublisher` 把 raw chunk 发布到企业 MQ；合并、脱敏、hash、分片和写表由 MQ 消费端异步完成。
- raw log 默认关闭；MQ 不可用、发送失败或 raw log 写表失败都不能影响 ChatEvent 入库、WebSocket 推送或 run 生命周期。

关键代码：

- `application/service/runtime/RuntimeRawStreamLogService.java`
- `RuntimeRawStreamLogService#capture(...)`
- `application/service/runtime/RuntimeRawStreamLogProcessor.java`
- `application/integration/conversation/RuntimeRawStreamLogPublisher.java`
- `application/integration/conversation/RuntimeRawStreamLogConsumer.java`
- `infrastructure/messaging/NoopRuntimeRawStreamLogPublisher.java`
- `infrastructure/persistence/MyBatisRuntimeRawStreamLogRepository.java`
- `infrastructure/persistence/RuntimeRawStreamLogMapper.java`

排查建议：

- 如果 Relay 返回内容看起来正确，但 ChatEvent 类型不对，先查 raw log 确认下游原始帧，再看 `RelayRuntimeResponseNormalizer` 的映射。
- raw log MQ 发布或写入失败不会影响 run 主链路，因此不能把 raw log 当作可靠恢复或前端展示来源。

### 2.4 ChatRun：一次后台回答

表：

- `fin_ex_chat_run_t`：业务 run 生命周期。
- `fin_ex_chat_run_execution_t`：执行控制面，保存实例、心跳、租约、fencing token。

用途：

- 一个 run 对应一次用户提问或一次重新生成。
- run 状态用于 stop、stream-status、恢复和排障。
- execution 表用于判断当前实例是否仍持有写事件权。

关键代码：

- `application/service/chat/ChatRunApplicationService.java`
- `ChatRunApplicationService#createRunning(...)`
- `ChatRunApplicationService#observeEvent(...)`
- `application/service/chat/ChatRunLeaseApplicationService.java`
- `ChatRunLeaseApplicationService#startRun(...)`
- `MyBatisChatEventStore#appendWithExecutionGuard(...)`

## 3. 用户发起提问的入口链路

### 3.1 HTTP 入口

文件：

```text
src/main/java/com/huawei/finance/front/one/interfaces/chat/ChatController.java
```

方法：

```text
ChatController#startRun(...)
```

职责：

- 接收 `POST /api/v1/ex/chat/runs`。
- 解析企业身份上下文为 `UserContext`。
- 读取 HTTP `Cookie` 请求头，生成 `RuntimeForwardHeaders`。
- 把前端 DTO 转成 `ChatCommand`。
- 调用 `FinanceEXChatService#startRun(...)`。

重点排查：

- 如果企业鉴权后用户为空，先看 `ChatController#resolveChatUser()` 和 `AuthContextProvider`。
- 如果 Cookie 没有透传给 Relay 或显式技能 legacy Agent chat/cancel，先看 `ChatController#startRun(...)`
  或 stop 入口是否读取到了 `HttpHeaders.COOKIE`，再看 `RuntimeForwardHeaderExtractor`。
- 如果 legacy 文档上传没有权限，先看 `MvcDocumentUploadController/ReactiveDocumentUploadController`
  是否把 `Cookie` 传入 `DocumentUploadSupport`，再看 provider 配置 `forward-cookie` 是否为 true。

### 3.2 创建后台 run

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/chat/FinanceEXChatService.java
```

方法：

```text
FinanceEXChatService#startRun(...)
```

核心代码行为：

```text
executeRun(user, command, headerSnapshot)
    .subscribeOn(Schedulers.boundedElastic())
    .subscribe(...)
```

职责：

- 获取 run admission permit，限制用户/租户并发。
- 创建 `Sinks.One<ChatEvent>` 等待第一个事件，用于返回 `firstSeq`。
- 构造后台 `runFlux`。
- 服务端主动 `subscribe()` 执行 run。
- 将本机 `Disposable` 注册到 `LocalChatRunExecutionRegistry`，用于 stop 时命中本机后快速 dispose。
- 第一个事件持久化后返回 `ChatRunStartResult`，包含 `runId`、`sessionId`、`firstSeq`、`streamTopicId`。

重点排查：

- `/runs` 长时间不返回：通常说明第一个事件没有成功进入 `persistAndPublishRunEvents(...)`，要继续查 `executeRun(...)` 和事件落库。
- stop 无法立即中断本机执行：检查 `LocalChatRunExecutionRegistry#register(...)` 是否拿到了 runId 和 `Disposable`。
- run 返回后浏览器关闭但后台仍在跑：这是设计目标，后台 run 生命周期不依赖浏览器连接。

## 4. executeRun 主编排路径

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/chat/FinanceEXChatService.java
```

方法：

```text
FinanceEXChatService#executeRun(...)
```

执行顺序：

1. 用入口传入的 `UserContext` 重建 `ChatCommand`，覆盖前端 tenant/user。
2. `SessionApplicationService#loadOrCreate(...)` 加载或创建会话。
3. `ChatRunApplicationService#rejectIfActiveRunExists(...)` 快速拒绝同一 session 的并发 run。
4. `DocumentFacade#resolveAttachmentsForUser(...)` 解析附件引用。
5. `IdGenerator#newId("run", ...)` 生成 runId。
6. `MemoryApplicationService#loadForRun(...)` 按配置加载可选记忆。
7. `SessionApplicationService#prepareRunMessage(...)` 写入或定位本轮 user message。
8. 先检查 `metadata.selectedSkillId`；存在时进入 `EXPLICIT_SKILL` 路由，不读取 RuntimeBinding。
9. 未显式指定技能时查询或创建 RuntimeBinding。
10. `RouteSignalApplicationService#routeInitial(...)` 调用可选用例库和意图服务。
11. `ChatRunApplicationService#createRunning(...)` 创建业务 run。
12. 如果本轮实际调用了意图服务，`IntentRecognitionRecordService#recordAsync(...)` 用当前 `UserContext`、query、`IntentDecision`、最终 `RouteTarget` 和 runId 构造不可变快照，并提交到专用 Servlet/MVC 异步线程池；写入失败不影响主链路。
13. `ChatRunLeaseApplicationService#startRun(...)` 创建 execution lease。
14. 根据 `RouteType` 调用 LegacySkill、SubAgent、SystemResponse 或 AgentRuntime。
15. 外层补齐 `run.started` 和 `run.completed`。
16. 进入 `persistAndPublishRunEvents(...)`。

关键分支：

```text
RouteType.SUB_AGENT        -> SubAgentExecutor#execute(...)
RouteType.EXPLICIT_SKILL   -> LegacySkillExecutor#execute(...)
RouteType.SYSTEM_RESPONSE  -> SystemResponseExecutor#execute(...)
RouteType.AGENT_RUNTIME    -> AgentRuntimeExecutor#execute(...)
```

重点排查：

- 新问题没有进入 Relay：查看 `RouteSignalApplicationService#routeInitial(...)` 返回的 `RouteTarget`。
- 意图识别已调用但统计表没有记录：确认 `financeex.intent-record.enabled=true`，再看 `IntentRecognitionRecordService#recordAsync(...)` 是否被线程池拒绝或 repository 写库失败；该链路是 best-effort，不会向前端报错。
- 指定技能没有进入老 Agent 或 chat Cookie 未透传：查看 `FinanceEXChatService#selectedSkillId(...)`、`LegacySkillExecutor#execute(...)` 和 `ConfiguredLegacySkillAgentClient#query(...)`。
- 多轮没有续接 Runtime：查看 `RuntimeBindingApplicationService#findActive(...)` 是否命中当前 `leafMessageId`。
- 同一会话连续发两条报错：查看 `ChatRunApplicationService#rejectIfActiveRunExists(...)` 和 `ChatRunApplicationService#createRunning(...)`。
- user message 已写入但 run 没创建：异常可能发生在 `prepareRunMessage(...)` 之后、`createRunning(...)` 之前，需要看日志和事务边界。

## 5. 用户消息如何入库

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/chat/SessionApplicationService.java
```

入口方法：

```text
SessionApplicationService#prepareRunMessage(...)
```

不同模式：

- `NEXT`：调用 `createNextUserMessage(...)`。
- `EDIT_USER`：调用 `createEditedUserMessage(...)`，不会覆盖原问题，而是创建 user sibling。
- `REGENERATE_ASSISTANT`：调用 `resolveRegeneratePlan(...)`，复用原 user message，不创建新的 user message。

真正写 user message 的方法：

```text
SessionApplicationService#createUserMessage(...)
```

写入动作：

1. 生成 `messageId`。
2. 计算 `parentMessageId`、`nodeOrder`、`treeDepth`、`siblingIndex`。
3. `messageRepository.save(message)` 写 `fin_ex_chat_message_t`。
4. `saveAttachments(...)` 写 `fin_ex_chat_message_attachment_t`。
5. `sessionRepository.updateCurrentLeaf(...)` 更新 session 当前叶子。

重点排查：

- 历史列表看不到用户输入：查 `createUserMessage(...)` 是否执行成功。
- 附件没有挂到消息上：查 `saveAttachments(...)` 和 `ChatMessageRepository#saveAttachment(...)`。
- 编辑历史消息覆盖了旧消息：当前设计不会覆盖，应检查是否错误调用了 `NEXT` 而不是 `EDIT_USER`。

## 6. Relay Runtime 调用路径

### 6.1 应用层 Runtime 执行器

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/runtime/AgentRuntimeExecutor.java
```

方法：

```text
AgentRuntimeExecutor#execute(...)
```

职责：

- 构造 `AgentRuntimeRequest`。
- 带上 `runtimeSessionId`、query、attachments、memory、intent、route、metadata、forwardHeaders。
- 通过 `WorkloadConcurrencyLimiter#protectAgentRuntime(...)` 做并发保护。
- 调用 `AgentRuntime#query(...)`。

重点排查：

- Relay 请求缺少 sessionId/query：先看这里构造的 `AgentRuntimeRequest`，再看 Relay adapter 是否正确映射为下游 wire DTO。
- Cookie 没透传：先确认 `forwardHeaders` 没有在这里丢失。
- Runtime 并发满：查看 `WorkloadConcurrencyLimiter` 和 `financeex.resource-isolation.agent-runtime-max-concurrent`。

### 6.2 Relay provider 选择 adapter

文件：

```text
src/main/java/com/huawei/finance/front/one/infrastructure/runtime/relay/RelayAgentRuntime.java
```

方法：

```text
RelayAgentRuntime#query(...)
RelayAgentRuntime#selectedAdapter(...)
```

职责：

- application 层只看到 `AgentRuntime`。
- Relay provider 当前固定委托 streamable HTTP adapter；不再暴露下游协议选择配置。

当前 adapter：

- `relay-stream-http`

### 6.3 streamable HTTP adapter

文件：

```text
src/main/java/com/huawei/finance/front/one/infrastructure/runtime/relay/RelayStreamHttpRuntimeAdapter.java
```

方法：

```text
RelayStreamHttpRuntimeAdapter#query(...)
RelayStreamHttpRuntimeAdapter#applyForwardedCookie(...)
```

职责：

- 通过 WebClient POST 到 Relay。
- 请求体由 `AgentRuntimeRequest` 映射为 Relay 专用 `RelayRuntimeQueryRequest`，只包含下游需要的 allowlist 字段。
- 可选透传 Cookie 到 HTTP header。
- 使用 `bodyToFlux(String.class)` 接收下游响应。
- 在 normalizer 之前调用 `RuntimeRawStreamLogService#capture(...)` 发布 raw chunk 到 MQ 旁路。
- 通过 `RelayRuntimeResponseNormalizer` 把 plain text、JSON chunk、SSE-like `data:` chunk 转成标准 ChatEvent。
- Relay `type=agent,is_streaming=true` 的 `content/context` 默认转成 `message.delta`；`type=agent,is_streaming=false` 转成 `message.snapshot`；`steam-complete/stream-complete/[DONE]` 转成 `message.completed`。
- Relay `type=tool-structured-result` 是 MCP 工具结构化结果帧，normalizer 会读取 `result_data/resultData.widget.data` 后按字段映射：`content` -> `message.delta(sourceType=relay-content)`，`processResult` -> `runtime.progress(sourceType=relay-processResult)`，`searchList/sourcesDocuments` -> `runtime.reference(sourceType=relay-*)`，`cardUrl/diyCardScene/cardList/openCard` -> `runtime.card(sourceType=relay-*)`。其中 `is_last` 只是工具分片上下文，不触发 `message.completed`。
- Relay 和 legacy-agent 过程帧按语义转成 `runtime.progress/runtime.metadata/runtime.agent/runtime.thinking/runtime.tool/runtime.reference/runtime.card`；legacy 的 `diyCardScene/openCard/searchList/sourcesDocuments/processResult` 这类对象如果跨网络 chunk，会继续使用对应稳定事件类型，并在 payload 中用 `fragment/itemId/delta/complete` 表达分片状态，避免半截 JSON 被误转成 `invalid-json`。当前 legacy 协议下 `cardUrl/diyCardScene/cardList/openCard` 通常不会在同一个 chunk 中同时出现，`runtime.card.payload.sourceType` 会保留原始字段名，例如 `diyCardScene` 或 `openCard`；未知完整 JSON 才转成 `runtime.event`。
- `message.delta` 代表 assistant 正文增量并参与草稿拼接；`message.snapshot` 代表下游最终回答快照，会覆盖草稿成为历史正文。
- 流结束时补 `MessageCompletedEvent`。

重点排查：

- Relay 返回了数据但前端没看到：先确认这里是否产生了 `MessageDeltaEvent` 或 `RuntimeEvent`。
- Relay 响应格式不是纯字符串片段：先看 raw log，再看 `RelayRuntimeResponseNormalizer` 是否把正文转为 `message.delta/message.snapshot`，或把非正文扩展帧转为对应 `runtime.*`。不要把 Relay 原始 JSON 作为 ChatService 顶层事件透传。
- 第三方 Cookie 泄漏风险：确认只有可信 Relay adapter、显式技能 legacy Agent adapter 和配置 `forward-cookie=true` 的 HTTP 文档 provider upload 调用 `applyForwardedCookie(...)`，且 Cookie 没有进入请求体、multipart form 或元数据。

## 7. Relay 事件如何变成可恢复事件流

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/chat/FinanceEXChatService.java
```

方法：

```text
FinanceEXChatService#persistAndPublishRunEvents(...)
```

处理步骤：

1. `eventBelongsToCurrentRun(...)`
   - 校验下游事件的 `runId` 和 `sessionId`。
   - 不匹配时生成 `RUN_EVENT_IDENTITY_MISMATCH`，防止串会话。

2. `ChatDeltaCoalescer#coalesce(...)`
   - 只合并连续 `message.delta`，降低逐 token 写库、Redis publish 和 WebSocket 投递放大。
  - 遇到 `message.snapshot`、`runtime.progress/runtime.metadata/runtime.agent/runtime.thinking/runtime.tool/runtime.reference/runtime.card/runtime.event`、`run.started`、`message.completed`、`run.completed`、`run.failed`、`run.cancelled` 会先 flush，再原样输出边界事件。

3. `ChatRunApplicationService#shouldAcceptEvent(...)`
   - 先看 Redis cancel flag。
   - `run.cancelled` 和 run 终态会回源 run 表做幂等状态判断。
   - 运行中的 `message.delta/message.completed` 不再逐条查 run 表；最终写入正确性由 guarded insert 的 run 状态与 execution fencing 条件保证。

4. `ChatStreamApplicationService#appendWithExecutionGuard(...)`
   - 进入 `MyBatisChatEventStore#appendWithExecutionGuard(...)`。
   - Mapper 使用 `INSERT ... SELECT ... JOIN fin_ex_chat_session_t + fin_ex_chat_run_t + fin_ex_chat_run_execution_t`。
   - 同一条 SQL 校验 run/session/tenant/user 归属、run 状态、execution owner 和 fencing token。
   - 条件不满足时抛出写入拒绝，后台流停止，不发布该事件。

5. `AssistantAssembly`
   - 累积 `message.delta` 草稿；收到 `message.snapshot` 时记录最终快照并覆盖草稿作为历史正文。
   - `runtime.*` 只作为运行态扩展事件落库和推送，不进入 assistant 正文；run 正常完成后会保存为 `fin_ex_chat_message_part_t`，供历史消息回显思考、工具、进度、引用、卡片和 agent 调用过程，并补齐稳定展示语义。
   - 如果本轮没有正文但存在卡片、引用、思考、工具、进度等用户可见 part，仍会创建一条空正文 assistant 消息作为 parts 挂载点；纯 trace/metadata 不会创建空 assistant。
   - 注意：只有 guarded insert 成功后的事件才会进入 assembly，不写未持久化的迟到 token。

6. run 完成前保存完整 assistant message
   - 当事件类型是 `run.completed` 且 assistant buffer 非空或存在用户可见 runtime parts 时：
     - `SessionApplicationService#saveAssistantMessage(...)`
     - `ChatRunApplicationService#bindAssistantMessage(...)`
     - `RuntimeBindingApplicationService#moveToLeaf(...)`

7. `ChatRunApplicationService#observeEvent(...)`
   - `run.started` 写 `firstSeq`。
   - `run.completed/run.failed/run.cancelled` 写 `lastSeq/status/finishedAt`。
   - `message.delta` 不再逐条更新 run 表；实时 latest seq 以 event 表为准。

8. `ChatStreamApplicationService#publishPersisted(...)`
   - 只发布已经成功写入数据库、带 seq 的事件。
   - 本机 live sink 与 Redis Pub/Sub 都不是事实源。

9. `RuntimeBindingApplicationService#observeEvent(...)`
   - 从 event payload 中观察并保存 `runtimeSessionId`。

重点排查：

- Relay delta 到了但 event 表没有：查 `eventBelongsToCurrentRun(...)`、`ChatRunApplicationService#shouldAcceptEvent(...)` 和 guarded insert 条件。
- stop 后还在写 token：查 `ChatRunApplicationService#shouldAcceptEvent(...)` 是否识别 Redis cancel flag。
- completed 到了但历史消息没有 assistant：查 `run.completed` 前的 `SessionApplicationService#saveAssistantMessage(...)`。
- Runtime 多轮没有 runtimeSessionId：查 `RuntimeBindingApplicationService#observeEvent(...)` 和 event payload。

## 8. 事件如何入库

### 8.1 应用层发布入口

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/chat/ChatStreamApplicationService.java
```

方法：

```text
ChatStreamApplicationService#appendAndPublish(...)
```

顺序：

```text
eventStore.append(event)
registry.publish(persisted)
liveEventBus.publish(chat-run-{runId}, persisted)
```

注意：

- 必须先入库，再发布。
- 发布出去的事件是带数据库 `seq` 的 persisted event。
- 如果事件没有 runId，不发布 Redis topic。

### 8.2 数据库事件事实源

文件：

```text
src/main/java/com/huawei/finance/front/one/infrastructure/persistence/MyBatisChatEventStore.java
```

方法：

```text
MyBatisChatEventStore#append(...)
MyBatisChatEventStore#appendWithExecutionGuard(...)
```

写入步骤：

1. 生成 eventId。
2. 调 `ChatEventMapper#nextSeq()` 获取数据库 sequence。
3. 普通恢复/取消/系统补偿事件调用 `ChatEventMapper#insertFromSession(...)`。
4. 后台 run 流式事件调用 `ChatEventMapper#insertFromSessionWithExecutionGuard(...)`，在一条 SQL 内校验 run/session/tenant/user、run 状态、execution owner 和 fencing token。
5. insert 成功后直接用已知 seq/createdAt/payload 构造 `StoredChatEvent`，不再回读 `findById(...)`。

重点排查：

- `seq` 乱序或重复：查 `fin_ex_chat_event_seq` 和 `ChatEventMapper#nextSeq()`。
- 插入返回 0：通常是 run/session/tenant/user 归属不一致，查 `insertFromSession(...)` SQL。
- 事件恢复缺事件：先直接查 `fin_ex_chat_event_t` 是否存在对应 `run_id/session_id/seq`。

## 9. 事件如何发布

### 9.1 本机发布

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/LocalChatEventStreamRegistry.java
```

方法：

```text
LocalChatEventStreamRegistry#publish(...)
LocalChatEventStreamRegistry#publishTopic(...)
LocalChatEventStreamRegistry#subscribeRunTopic(...)
```

职责：

- 当前 JVM 内的 run topic 在线事件发布。
- 只负责推给连接在本实例上的 WebSocket。
- topic 是 `chat-run-{runId}`。
- sink 是有界 multicast live 通道，不保存历史事件；订阅建立时的补发统一从数据库事件表读取。
- live sink 溢出或异常时，上层会提示 `RECOVER_REQUIRED`，前端再用 Event Resume 补齐缺口。

重点排查：

- 同实例 WebSocket 收不到实时事件：查 `LocalChatEventStreamRegistry#publish(...)` 是否命中 topic sink。
- terminal 后 topic 没释放：查 `publishTopic(...)` 中 terminal 事件处理。

### 9.2 Redis 跨实例发布

文件：

```text
src/main/java/com/huawei/finance/front/one/infrastructure/persistence/RedisChatLiveEventBus.java
```

方法：

```text
RedisChatLiveEventBus#publish(...)
RedisChatLiveEventBus#subscribe(...)
RedisChatLiveEventBus#onMessage(...)
```

职责：

- 将已落库事件发布到 Redis Pub/Sub。
- channel 形态：`fin_ex:{env}:chat_stream:chat-run-{runId}`，其中 `{env}` 来自 `spring.profiles.active` 的第一个 profile。
- 其他实例收到 Redis 消息后，再投递给本实例订阅该 topic 的 WebSocket。

注意：

- Redis Pub/Sub 不是可靠消息源。
- Redis 不可用时，跨实例实时推送可能缺失，但 Event Resume 仍可从数据库补齐。

重点排查：

- 多实例下只有同实例能收到：查 Redis channel 订阅是否成功。
- Redis 收到错 topic 或错 session：下游还有 `liveBuffer(...)` 和 WebSocket 输出前的 run/session 防御过滤。

## 10. 前端 WebSocket 订阅与推送

### 10.1 MVC WebSocket 握手

文件：

```text
src/main/java/com/huawei/finance/front/one/interfaces/chat/websocket/ChatServletWebSocketAuthInterceptor.java
src/main/java/com/huawei/finance/front/one/interfaces/chat/websocket/ChatServletWebSocketHandler.java
src/main/java/com/huawei/finance/front/one/interfaces/chat/websocket/ChatServletWebSocketConfig.java
```

关键方法：

```text
ChatServletWebSocketAuthInterceptor#beforeHandshake(...)
ChatServletWebSocketHandler#afterConnectionEstablished(...)
ChatServletWebSocketHandler#handleTextMessage(...)
ChatServletWebSocketHandler#emit(...)
```

职责：

- `beforeHandshake(...)` 在 HTTP upgrade 前解析企业 ThreadLocal 身份，并写入 WebSocket attributes。
- `afterConnectionEstablished(...)` 从 attributes 读取 `UserContext`，调用 `ChatWebSocketProtocolService#open(...)`。
- `handleTextMessage(...)` 只处理 WebSocket 控制消息，不接收聊天 query。
- `emit(...)` 负责把 envelope 写到底层 WebSocket session。

重点排查：

- MVC 模式 WebSocket 404：查 `ChatServletWebSocketConfig` 是否生效，以及是否被 WebFlux/MVC 条件装配影响。
- WebSocket 握手后用户为空：查 `ChatServletWebSocketAuthInterceptor#beforeHandshake(...)` 是否在企业 ThreadLocal 有效阶段执行。
- 发送报错或连接关闭：查 `ChatServletWebSocketHandler#emit(...)` 和 `ConcurrentWebSocketSessionDecorator` 限制。

### 10.2 WebSocket 协议服务

文件：

```text
src/main/java/com/huawei/finance/front/one/interfaces/chat/websocket/ChatWebSocketProtocolService.java
```

关键方法：

```text
ChatWebSocketProtocolService#open(...)
ChatWebSocketProtocolService#handleTextMessage(...)
ChatWebSocketProtocolService#subscribe(...)
ChatWebSocketProtocolService#emitTopicEvent(...)
ChatWebSocketProtocolService#close(...)
```

控制消息：

```json
{"id":"1","type":"connect","presence":"foreground"}
{"id":"2","type":"subscribe","topicId":"chat-run-run_xxx","afterSeq":0}
{"id":"3","type":"unsubscribe","topicId":"chat-run-run_xxx"}
{"id":"4","type":"presence","state":"background"}
```

订阅流程：

1. `subscribe(...)` 读取 `topicId` 和 `afterSeq`。
2. `ChatStreamApplicationService#ensureRunTopicAccessible(...)` 校验 run 属于当前用户。
3. `LocalWebSocketConnectionRegistry#subscribe(...)` 记录连接与 topic 关系。
4. 发送 subscribe reply。
5. 调 `ChatStreamApplicationService#resumeRunTopic(...)`。
6. 每个事件先转 `ChatEventDto`，再由 `ChatTurnStreamTranslator` 包装成 `conversation-turn-stream.stream-item`。
7. `emitTopicEvent(...)` 校验 runId/sessionId，去重和 gap 检测后推给前端；如果事件是 run 终态，会额外发送一个 turn stream `done`。
8. 订阅空闲期间按 `financeex.chat-stream.turn-heartbeat-interval` 发送 turn stream `heartbeat`，heartbeat 不落事件表、不推进 `afterSeq`。

重点排查：

- 前端订阅返回 `SUBSCRIBE_ERROR`：查 topic 是否为 `chat-run-{runId}`，run 是否属于当前用户。
- 多会话串显示：先确认 `emitTopicEvent(...)` 是否丢弃了错 run/session；再检查前端是否按 `message.payload.payload.encodedItem.data.sessionId` 分发。
- 前端收到 `RECOVER_REQUIRED`：说明 seq 有缺口或乱序，应调用 Event Resume 后重新 subscribe。

### 10.3 连接注册表

文件：

```text
src/main/java/com/huawei/finance/front/one/interfaces/chat/websocket/LocalWebSocketConnectionRegistry.java
```

关键方法：

```text
LocalWebSocketConnectionRegistry#register(...)
LocalWebSocketConnectionRegistry#subscribe(...)
LocalWebSocketConnectionRegistry#markDelivered(...)
LocalWebSocketConnectionRegistry#unregister(...)
```

职责：

- 保存当前 JVM 内 WebSocket 连接状态。
- 限制单用户连接数、单连接 topic 数、单 topic 本机订阅数。
- 记录每个 topic 的订阅起点和已投递 seq 窗口。
- 做有限窗口去重和 seq gap 判断；缺口发生时提示前端用 Event Resume 补齐。
- 连接关闭时释放所有 topic subscription。

注意：

- 这是运行态注册表，不是事实源。
- 多实例场景下，不同实例各有自己的本地连接注册表。

## 11. WebSocket 订阅如何拿到历史 + 实时事件

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/chat/ChatStreamApplicationService.java
```

方法：

```text
ChatStreamApplicationService#resumeRunTopic(...)
ChatStreamApplicationService#liveBuffer(...)
ChatStreamApplicationService#deduplicate(...)
```

执行顺序：

1. `ensureRunTopicAccessible(...)` 校验 topic 对应 run 归属。
2. 先创建 `liveBuffer(...)`，避免查库期间产生事件空窗。
3. 从数据库查询 `findByOwnerAndRunAfterSeq(...)` 补发历史事件。
4. 计算 replay 的最大 seq。
5. `Flux.concat(replay, liveBuffer.events().filter(seq > liveAfterSeq))`。

`liveBuffer(...)` 内部合并：

```text
LocalChatEventStreamRegistry#subscribeRunTopic(...)
RedisChatLiveEventBus#subscribe(...)
```

然后做：

- 按 `expectedRunId + expectedSessionId` 过滤。
- 按 seq 有限窗口去重。
- sink 有界缓冲，溢出时触发 `StreamRecoveryRequiredException`。

重点排查：

- 新开页签只收到 WebSocket、没有走 Event Resume：这是前端策略问题；服务端 WebSocket subscribe 本身也会先查数据库补历史。
- 需要严格执行当前恢复协议：前端应先调用 run 级事件恢复补齐已落库事件，再根据页面需要订阅 WebSocket 实时 topic。
- 跨实例实时缺事件：看 Redis Pub/Sub；但最终以 run 级事件恢复 是否能补齐为准。

## 12. Event Resume 恢复路径

HTTP 入口文件：

```text
src/main/java/com/huawei/finance/front/one/interfaces/chat/ChatController.java
```

入口方法：

```text
ChatController#resumeSessionEvents(...)
ChatController#resumeRunEvents(...)
```

应用层方法：

```text
ChatStreamApplicationService#resumeSession(...)
ChatStreamApplicationService#resumeRun(...)
ChatStreamApplicationService#resumeRunWithLiveTail(...)
```

两类接口：

```text
GET /api/v1/ex/chat/sessions/{sessionId}/events/resume?afterSeq={seq}
GET /api/v1/ex/chat/runs/{runId}/events/resume?afterSeq={seq}
```

区别：

- session 级事件恢复：只补发会话维度历史事件。
- run 级事件恢复：补发指定 run，并在 run 未终态时接 live tail，直到终态。

HTTP resume 的 SSE `data` 与 WebSocket `message.payload` 一样，都是 `ConversationTurnStreamDto`：

- `payload.type=stream-item`：真实聊天事件在 `payload.encodedItem.data`。
- `payload.type=heartbeat`：连接保活，不写入 `fin_ex_chat_event_t`，不推进 `afterSeq`。
- `payload.type=done`：当前 turn 传输闭合，不代表新的 ChatEvent。

重点排查：

- Event Resume 没数据：先确认 `afterSeq` 是否已经大于等于最新 seq。
- 跨电脑恢复缺前半段：前端没有本地 cursor 时应从 `activeRunFirstSeq - 1` 开始。
- Event Resume 一直不断：检查 run 是否一直未产生 terminal event。

## 13. stop 取消路径

HTTP 入口：

```text
ChatController#stopRun(...)
```

应用层：

```text
FinanceEXChatService#stopRun(...)
ChatRunApplicationService#requestStop(...)
LocalChatRunExecutionRegistry#cancel(...)
AgentRuntimeExecutor#cancel(...)
ChatStreamApplicationService#appendAndPublish(...)
```

执行顺序：

1. 校验 run 属于当前用户。
2. `ChatRunApplicationService#requestStop(...)` 写 Redis cancel flag，并把 run 置为 `CANCELLING`。
3. `LocalChatRunExecutionRegistry#cancel(...)` 如果命中本机，立即 dispose 后台 subscription。
4. `AgentRuntimeExecutor#cancel(...)` 尽力取消下游 Relay。
5. 写入 `run.cancelled` 事件。
6. `ChatRunLeaseApplicationService#markTerminal(...)` 标记 execution 终态。

重点排查：

- stop 后还出 delta：查 `ChatRunApplicationService#shouldAcceptEvent(...)` 是否拦截。
- stop 没有通知前端：查 `run.cancelled` 是否写入 `fin_ex_chat_event_t`。
- 下游 Relay 没停：查对应 adapter 的 `cancel(...)` 和 `financeex.agent-runtime.stop-path`。

## 14. 后台任务与故障治理

### 14.1 本机后台 run 注册表

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/LocalChatRunExecutionRegistry.java
```

关键方法：

```text
register(...)
registerClaim(...)
cancel(...)
complete(...)
activeClaims(...)
```

职责：

- 保存当前 JVM 内正在执行的 Reactor subscription。
- stop 命中本机时快速 dispose。
- heartbeat 扫描本机 active claim。

注意：

- 它不是跨实例事实源。
- 跨实例一致性依赖 Redis cancel flag、数据库 execution lease 和 fencing token。

### 14.2 execution lease 和 heartbeat

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/chat/ChatRunLeaseApplicationService.java
```

关键方法：

```text
startRun(...)
heartbeat(...)
markTerminal(...)
heartbeatActiveRuns(...)
```

职责：

- run 创建后写 `fin_ex_chat_run_execution_t`。
- 定时刷新本机 active run heartbeat。
- 事件写入权不再先单独查询 execution；由 `MyBatisChatEventStore#appendWithExecutionGuard(...)`
  在事件插入 SQL 中原子校验 owner 和 fencing token。
- run 终态后把 execution 置为终态。

重点排查：

- 实例挂了 run 一直 RUNNING：查 watchdog 是否开启，以及 `lease_until` 是否过期。
- 旧实例恢复后继续写事件：查 guarded insert 是否因 owner/fencing token 不匹配而拒绝写入。

### 14.3 watchdog 和恢复策略

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/chat/ChatRunWatchdogScheduler.java
src/main/java/com/huawei/finance/front/one/application/service/chat/ChatRunRecoveryOrchestrator.java
src/main/java/com/huawei/finance/front/one/application/service/ManualConfirmationRecoveryStrategy.java
src/main/java/com/huawei/finance/front/one/application/service/FailFastRecoveryStrategy.java
src/main/java/com/huawei/finance/front/one/application/service/RuntimeTakeoverRecoveryStrategy.java
```

职责：

- Scheduler 只负责定时触发和 jitter。
- Orchestrator 查询 stale execution、做容量治理、Redis recover lock、数据库条件抢占。
- Strategy 负责具体恢复动作。

当前主要策略：

- `MANUAL_CONFIRMATION`：写 `run.failed`，payload 带恢复建议。
- `FAIL_FAST`：直接失败。
- `RUNTIME_TAKEOVER`：预留，需要 Runtime 支持可靠恢复 token。

重点排查：

- 多实例重复恢复同一个 run：查 `ChatRunExecutionMapper#tryClaimRecovering(...)` 条件更新和 `fencing_token`。
- 某实例一次抢太多 run：查 `ChatRunRecoveryCapacityLimiter` 和 `financeex.chat-run.watchdog-*` 配置。

## 15. stream-status 查询路径

入口：

```text
ChatController#streamStatus(...)
```

应用层：

```text
ChatRunApplicationService#streamStatus(...)
```

职责：

- 查询当前 session 最新事件 seq。
- 查询 active run。
- 如果 active run lease 已过期，触发一次轻量懒恢复。
- 返回 `activeRunId`、`activeRunStatus`、`streamTopicId`、`activeRunFirstSeq`、`activeRunLastSeq`、`cancellable`。

重点排查：

- 前端按钮状态不对：先查这个接口返回。
- 跨设备不知道从哪里恢复：看 `activeRunFirstSeq`，从 `activeRunFirstSeq - 1` 打开 run 级 Event Resume。
- active run 已经失败但前端仍显示运行中：查 `streamStatus(...)` 是否触发了懒恢复。

## 16. RuntimeBinding 多轮续接路径

文件：

```text
src/main/java/com/huawei/finance/front/one/application/service/runtime/RuntimeBindingApplicationService.java
```

关键方法：

```text
findActive(...)
create(...)
touchForRun(...)
observeEvent(...)
moveToLeaf(...)
cancelActive(...)
```

职责：

- 只有 AgentRuntime 创建绑定，SubAgent 不创建绑定。
- 绑定维度包含 tenant、user、session、provider、leafMessageId。
- 避免编辑历史问题后误复用当前最新路径的 Runtime session。
- 从 Runtime event payload 中观察 `runtimeSessionId` 并保存。

重点排查：

- 多轮没有上下文：查 `findActive(...)` 是否按当前 leaf 命中。
- 编辑历史问题串到最新上下文：查 `leafMessageId` 是否正确。
- forceNewTask 不生效：查 `cancelActive(...)`。

## 17. 常见问题定位速查

| 问题 | 优先查看 |
| --- | --- |
| `/runs` 不返回 | `FinanceEXChatService#startRun(...)`、`executeRun(...)`、`persistAndPublishRunEvents(...)` |
| 用户消息入库但没有回答 | `AgentRuntimeExecutor#execute(...)`、Relay adapter、`persistAndPublishRunEvents(...)` |
| Relay 有响应但前端没有 | `ChatStreamApplicationService#appendWithExecutionGuard(...)`、`ChatStreamApplicationService#publishPersisted(...)`、`LocalChatEventStreamRegistry#publish(...)`、`RedisChatLiveEventBus#publish(...)`、`ChatWebSocketProtocolService#emitTopicEvent(...)` |
| event 表没有 delta | `FinanceEXChatService#eventBelongsToCurrentRun(...)`、`ChatRunApplicationService#shouldAcceptEvent(...)`、`MyBatisChatEventStore#appendWithExecutionGuard(...)` |
| WebSocket 收不到实时消息 | `ChatWebSocketProtocolService#subscribe(...)`、`ChatStreamApplicationService#resumeRunTopic(...)`、`liveBuffer(...)` |
| 事件恢复没有补发 | `ChatController#resumeRunEvents(...)`、`ChatStreamApplicationService#resumeRun(...)`、`MyBatisChatEventStore#findByOwnerAndRunAfterSeq(...)` |
| 多会话串显示 | `emitTopicEvent(...)` 的 run/session 校验、前端按 `payload.sessionId` 分发 |
| stop 后还在输出 | `ChatRunApplicationService#requestStop(...)`、`shouldAcceptEvent(...)`、`LocalChatRunExecutionRegistry#cancel(...)` |
| 实例挂掉 run 不结束 | `ChatRunLeaseApplicationService#heartbeatActiveRuns(...)`、`ChatRunWatchdogScheduler`、`ChatRunRecoveryOrchestrator` |
| 跨电脑续接缺内容 | `stream-status`、run 级事件恢复 `afterSeq`、`fin_ex_chat_event_t` |
| assistant 历史消息没保存 | `persistAndPublishRunEvents(...)` 处理 `run.completed` 的分支、`SessionApplicationService#saveAssistantMessage(...)` |
| 文档附件没有进 Runtime 或指定技能 | `DocumentFacade#resolveAttachmentsForUser(...)`、`LegacySkillChatRequestMapper#sceneParam(...)` / `docList(...)`、`SessionApplicationService#saveAttachments(...)`、`AgentRuntimeRequest.attachments`。指定技能路径会保留 `metadata.legacyAgent.sceneParam` 的扩展字段，但 `docList` 始终由后端附件元数据覆盖生成。 |

## 18. 推荐调试顺序

当你要排查一次完整提问时，建议按下面顺序看：

1. `ChatController#startRun(...)`：入口身份、Cookie、DTO 转换。
2. `FinanceEXChatService#startRun(...)`：后台 run 是否启动。
3. `FinanceEXChatService#executeRun(...)`：session、message、route、run、execution 是否创建。
4. `SessionApplicationService#prepareRunMessage(...)`：user message 是否入库。
5. `AgentRuntimeExecutor#execute(...)`：Runtime 请求是否构造正确。
6. `RelayStreamHttpRuntimeAdapter#query(...)`：下游是否返回。
7. `FinanceEXChatService#persistAndPublishRunEvents(...)`：事件是否被拦截。
8. `MyBatisChatEventStore#appendWithExecutionGuard(...)`：事件是否写入数据库并生成 seq，owner/fencing 是否匹配。
9. `ChatStreamApplicationService#publishPersisted(...)`：是否本机发布和 Redis 发布。
10. `ChatWebSocketProtocolService#subscribe(...)`：前端是否订阅正确 topic。
11. `ChatStreamApplicationService#resumeRunTopic(...)`：是否 replay + live 正常。
12. `ChatWebSocketProtocolService#emitTopicEvent(...)`：是否推给前端。
13. `ChatRunApplicationService#observeEvent(...)`：run 状态是否正确推进。
14. `SessionApplicationService#saveAssistantMessage(...)`：completed 后完整 assistant 是否入库。
