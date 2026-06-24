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

- `interfaces`：`/chat/runs`、WebSocket run topic subscribe、Event Resume、会话和文档上传协议适配。
- `application`：聊天主编排、会话、记忆、RuntimeBinding、SubAgent 单轮调用、显式技能兼容调用和 Relay Runtime 调用。
- `application.integration`：应用层出站集成抽象，定义对 Relay Runtime、SubAgent、IntentService、用例库、会话、记忆、文档、ID 和身份能力的依赖边界。
- `domain`：聊天事件、意图结果、路由结果、RuntimeBinding、用例匹配结果等核心模型。
- `infrastructure`：Redis、数据库/MyBatis、用例库 HTTP、SubAgent HTTP、Relay Runtime streamable HTTP、DocumentProvider、对象存储和 legacy skill HTTP 等适配。
- MyBatis Mapper 接口只保留方法签名，当前 openGauss SQL 统一维护在 `src/main/resources/mapper/**/*.opengauss.xml`；`db/schema.sql` 只保留 DDL。后续适配其他数据库时，通过切换 `mybatis.mapper-locations` 选择对应方言 XML。

## 前端接入协议

完整接口和 WebSocket 联调说明见 [前端联调文档](docs/frontend-integration.md)。

- `POST /api/v1/ex/chat/runs`：唯一提问入口。创建后台 run，返回 `runId`、`sessionId`、`firstSeq` 和 `streamTopicId`。
- `POST /api/v1/ex/chat/sessions`：显式创建会话；也可以在 `/chat/runs` 中不传 `sessionId` 由后端创建或归一化。
- `GET /api/v1/ex/chat/sessions?limit=20&cursor=...`：游标分页查询当前用户会话列表，并返回每个会话第一条 assistant 回答 `firstAssistantAnswer`。
- `GET /api/v1/ex/chat/sessions/page?curPage=1&pageSize=20`：页码分页查询当前用户历史会话，返回 `totalRows/totalPages` 和每个会话的 `firstAssistantAnswer`。
- `GET /api/v1/ex/chat/sessions/{sessionId}`：查询单个会话元数据，不返回历史消息和流式状态。
- `GET /api/v1/ex/chat/sessions/{sessionId}/messages?leafMessageId=...&limit=50`：选择会话后查询当前 active path 或指定 leaf path 的完整 user/assistant 消息；有多个版本的消息会带 `versionInfo`。
- `GET /api/v1/ex/chat/sessions/{sessionId}/messages/{messageId}/variants`：查询某条消息同父节点下的候选版本完整内容；普通聊天页优先使用 `/messages` 返回的 `versionInfo`。
- `POST /api/v1/ex/chat/sessions/{sessionId}/path`：持久化会话当前 active path leaf；UI 切换可先使用 `/messages?leafMessageId=...` 刷新展示。
- `POST /api/v1/ex/chat/sessions/{sessionId}/branches`：从指定消息创建只读历史快照分支。
- `POST /api/v1/ex/chat/sessions/{sessionId}/archive|restore`：会话归档和恢复。
- `DELETE /api/v1/ex/chat/sessions/{sessionId}`：软删除会话；只写 `status=DELETED`，不物理删除消息、run、event、反馈或附件引用。
- `DELETE /api/v1/ex/chat/sessions`：批量软删除会话；请求体传 `sessionIds[]`，任意会话存在 active run 时整体失败。
- `WS /api/v1/ex/chat/ws`：用户级实时输出通道。客户端使用 `{"type":"subscribe","topicId":"chat-run-{runId}","afterSeq":0}` 订阅本轮 run topic；MVC/Servlet 模式会在 handshake 阶段固化用户身份。服务端 `message.payload` 为 `conversation-turn-stream`，真实聊天事件在 `message.payload.payload.encodedItem.data`。
- `GET /api/v1/ex/chat/sessions/{sessionId}/events/resume?afterSeq={seq}`：会话级事件恢复有限补发，用于补齐整个会话缺失事件；SSE data 同样是 `conversation-turn-stream`。
- `GET /api/v1/ex/chat/runs/{runId}/events/resume?afterSeq={seq}`：run 级事件恢复并接续 live，用于跨页签、跨浏览器或跨电脑续接正在输出的当前回答，直到 run 终态；长时间无业务事件时发送 turn stream `heartbeat`，终态后发送 `done`。
- `GET /api/v1/ex/chat/sessions/{sessionId}/stream-status`：查询当前会话最新事件序号、active run、`activeStreamTopicId` 和是否可取消。
- `POST /api/v1/ex/chat/runs/{runId}/stop`：按 runId 停止当前回答，幂等返回 run 状态。
- `POST /api/v1/ex/chat/messages/{messageId}/feedback`：提交或切换 assistant 消息点赞/点踩。
- `DELETE /api/v1/ex/chat/messages/{messageId}/feedback`：取消当前用户对 assistant 消息的点赞或点踩。
- `POST /api/v1/ex/chat/messages/{messageId}/share`：为某条 assistant 消息创建单轮问答固定快照分享。
- `POST /api/v1/ex/chat/shares/{shareId}/deliveries`：把已有分享发送到指定 provider，首版内置 `welink`。
- `POST /api/v1/ex/chat/messages/{messageId}/share/deliveries`：一键创建分享快照并发送到指定 provider。
- `GET /api/v1/ex/chat/shares/{shareId}`：登录后查看分享详情；默认策略允许同租户用户查看。
- `DELETE /api/v1/ex/chat/shares/{shareId}`：撤销当前用户创建的分享。
- `GET /api/v1/ex/chat/shares?curPage=1&pageSize=20`：分页查询当前用户创建的分享，便于管理和撤销。

前端流式模式：

```text
POST /chat/runs
 -> 获取 runId/sessionId/firstSeq/streamTopicId
 -> 使用前端配置的 WebSocket 地址发送 subscribe(topicId=streamTopicId, afterSeq)
 -> 实时输出由 WebSocket run topic 承载
 -> 浏览器刷新/复制页签后，使用前端配置的 Event Resume 地址按 lastSeq 补齐缺失事件
 -> 新页签、新浏览器或跨电脑续接 active run 时，从 activeRunFirstSeq - 1 打开 run 级事件恢复
 -> Run 事件恢复先补发历史事件，再持续接续 live 事件，直到本轮 run 终态
 -> 用户点击停止时调用前端配置的 stop 接口，服务端在已有正文或用户可见 parts 时保存 partial assistant，并发布 run.cancelled 终态事件
```

当前请求体只有对话文本和可选文档附件，不暴露 IM 消息类型，也不让前端选择多套响应协议。文档不是消息类型，只是对话消息的上下文资源引用。
WebSocket、Event Resume 和 stop 的 URL 由前端 SDK 或网关配置管理，不随 `/chat/runs` 响应返回。

`/chat/runs` 支持消息树写入模式：`runMode=NEXT` 表示沿当前 leaf 继续提问；`EDIT_USER` 表示编辑历史 user 消息并创建新的 user sibling；`REGENERATE_ASSISTANT` 表示复用原 user 消息重新生成新的 assistant sibling。历史版本不会被覆盖，前端通过 `/messages.versionInfo` 展示版本游标，并通过 `leafMessageId/path` 切换和保存展示路径。

仓库提供独立本地联调台 `local-test-frontend/`。联调台通过 Node 代理访问后端，支持在页面中按 Postman 风格配置 `Cookie`、`Authorization`、`X-*` 等企业鉴权请求头；代理会在 HTTP、fetch Event Resume、文件下载和 WebSocket 握手时统一注入这些请求头。浏览器自身不会、也不能直接手写 `Cookie` 请求头或 WebSocket 自定义请求头。

当 `POST /api/v1/ex/chat/runs`、`POST /api/v1/ex/chat/runs/{runId}/stop` 或 `POST /api/v1/ex/documents`
携带标准 `Cookie` 请求头时，ChatService 会在请求入口捕获一次，并只作为内存快照透传给可信下游 adapter：Relay streamable HTTP、显式技能 legacy Agent chat/cancel，以及显式配置 `forward-cookie=true` 的 legacy 文档 upload provider。Cookie 不会写入 `metadata_json`、消息、事件、日志、前端响应、multipart form 或下游请求体。普通 default-storage 对象存储上传不会透传 Cookie。

外部 HTTP 服务调用还支持统一的集成服务鉴权请求头防腐层。`financeex.integration-auth.enabled=false`
时不注入任何鉴权头；开启后，`AuthHeaderProviderRegistry` 会按 `serviceCode` 选择 provider。
首版预置 `welink-share`、`intent-service`、`use-case-library`、`sub-agent` 可配置为 `sgov`，
并由企业实现的 `SgovTokenResolver` 提供 `Authorization` 值。Relay Runtime、显式技能 legacy Agent
和 legacy 文档 provider 默认不接入该鉴权头，仍保持现有 Cookie/普通调用行为。

租户和用户身份不从前端 Header/Query/Body 透传，统一由请求入口通过 `AuthContextProvider` 从服务端身份上下文解析一次，并以不可变 `UserContext` 传入应用层。应用层、后台 run 和 `boundedElastic` 阻塞线程不会再次读取请求 ThreadLocal。本地开发态必须显式配置：

MVC/Servlet WebSocket 是一个特殊入口：用户身份必须在 `HandshakeInterceptor.beforeHandshake`
阶段从企业 ThreadLocal 解析并写入 WebSocket session attributes。`afterConnectionEstablished`、
subscribe 和连接关闭回调只读取该身份快照，不会再次调用 `AuthContextProvider`。

生产使用 MVC/Servlet 模式时，需要把长连接当作 Servlet 资源治理：Event Resume 使用
`spring.mvc.async.request-timeout` 和 run 级 heartbeat 防止空闲断流；WebSocket 使用
`financeex.websocket.allowed-origin-patterns` 做 Origin 白名单，默认只允许 localhost。
单用户连接数、单连接订阅数、单 topic 本机订阅数、出站缓冲、live buffer 和空闲超时都由
`financeex.websocket.*` 统一配置。慢客户端或实时缓冲溢出时，服务端会返回
`RECOVER_REQUIRED`，前端应通过 run event resume 补齐后再重新订阅。
`seq` 是数据库事件游标，不是 run topic 内连续序号；多会话并发时同一 topic 看到
`19 -> 21` 不代表丢事件。服务端只在同 topic 更低且未见过的 seq 迟到、live buffer 溢出或
实时源异常时要求恢复，并在错误 envelope 的 `details.recoveryAfterSeq` 中给出更小范围的建议补发点。
同一 WebSocket 连接允许同时订阅多个 session 的多个 run topic。服务端不会因为切换会话而
自动释放旧 topic；隔离依赖订阅前的用户归属校验、事件事实源的 `tenantId/userId/sessionId/runId`
联合查询，以及投递前的 `topicId/runId/sessionId` 一致性校验。前端收到事件后必须按
`payload.sessionId` 分发到对应会话。
事件写入也会校验 run 与 session 的 tenant/user 归属一致，避免下游 Runtime/SubAgent 返回错误
`runId/sessionId` 时污染事件事实源。

```bash
export FINANCEEX_DEV_TENANT_ID=tenant_dev
export FINANCEEX_DEV_USER_ID=user_dev
export FINANCEEX_DEV_USERNAME=developer
```

`metadata.forceNewTask=true` 会取消当前 active RuntimeBinding，并重新读取可选路由信号；如果用例库和意图服务都关闭，则直接进入 Relay Runtime。

`metadata.selectedSkillId` 用于兼容存量 Agent 的“前端显式选择技能”场景。该字段存在且非空时，本轮 run 进入
`EXPLICIT_SKILL` 路由，直接调用配置化老 Agent chat 接口，并使用文档库中 `targetProvider=legacy-agent`
上传后保存的 provider 文档元数据组装 `sceneParam.docList`。前端可以在 `metadata.legacyAgent.sceneParam`
传入其他业务扩展字段，但 `docList` 始终由后端可信生成并覆盖，避免伪造文档引用。该路径不会读取或创建 RuntimeBinding，
避免把不具备稳定 ChatService 多轮契约的历史技能误当成 Relay Runtime 续接会话。

## 会话与执行标识

- `sessionId`：前端聊天会话 ID，一次聊天会话内可以包含多轮用户请求。
- `messageId`：完整 user/assistant 历史消息 ID，组成会话内消息树。
- `currentLeafMessageId`：会话当前激活路径叶子，历史查询默认从该 leaf 回溯 root。
- `runId`：SuperAgent 为每一轮用户请求生成的执行追踪 ID。
- `streamTopicId`：本轮 run 的 WebSocket 订阅 topic，格式为 `chat-run-{runId}`。
- `runtimeSessionId`：当前 AgentRuntime provider 自己的会话 ID，由 Runtime 返回后保存在 RuntimeBinding 中，下一轮续接时带回。

`runId` 不是长期任务会话；它是单轮执行 correlation id。事件表 `fin_ex_chat_event_t.run_id` 和绑定表 `fin_ex_runtime_binding_t.last_run_id` 都用它做运行轨迹和排障定位。
run 生命周期事实源保存在 `fin_ex_chat_run_t`，状态包括 `RUNNING`、`CANCELLING`、`CANCELLED`、`COMPLETED`、`FAILED`。stop 只停止本轮回答，不删除 `RuntimeBinding`；如果用户主动 stop 前已经有 `message.delta`、`message.snapshot` 或卡片、引用、思考、工具、进度等用户可见 parts 成功落库，ChatService 会把截至 stop 时的内容保存为 partial assistant 历史消息，并在消息 `metadata_json` 中标记 `partial=true`、`finishReason=USER_STOP`。
run 执行控制面保存在 `fin_ex_chat_run_execution_t`，只保存 owner 实例、心跳、租约、恢复状态和 `fencing_token`，不混入业务 run 表。后台执行流写入 run 事件时通过数据库 guarded insert 原子校验 execution owner 与 `fencing_token`；stop、watchdog 或未来 Runtime takeover 递增 token 后，旧实例迟到 delta/completed 会被拒绝。
连续 `message.delta` 默认按 `financeex.chat-stream.delta-coalesce-*` 合并为几十毫秒级文本片段，减少数据库事件表、Redis Pub/Sub 和 WebSocket 的逐 token 写放大；`message.snapshot`、`runtime.*`、turn stream `heartbeat/done` 和 run 终态不参与合并。Relay `is_streaming=false` 的最终回答会映射为 `message.snapshot`，前端用它替换当前草稿，历史消息正文也优先使用该快照。
assistant 的思考、工具、进度、agent 调用等过程信息保存到 `fin_ex_chat_message_part_t`，并通过 `ChatMessageDto.parts` 返回。parts 会提供稳定的 `title/status/channel/displayHint/visible` 展示语义，前端不需要解析 Relay 私有 payload。
Relay 原始流响应可以在 normalizer 之前通过 `RuntimeRawStreamLogPublisher` best-effort 发布到企业 MQ；消费端异步合并、脱敏、分片后写入 `fin_ex_runtime_raw_stream_log_t`。raw log 默认关闭，只用于排障和协议分析，不用于前端恢复、不用于 WebSocket 推送，也不用于 assistant 历史消息拼接；MQ 或 raw log 写库失败不会影响 run 主链路。
集群部署时，取消正确性依赖 Redis cancel flag 和数据库 run 状态；实例故障治理依赖数据库 execution 条件抢占和 fencing token。JVM 内 subscription registry 只用于命中本机执行流时快速释放资源，不作为跨实例事实源。
同一 `tenantId + userId + sessionId` 同一时间只允许一个 active run。若会话已有
`RUNNING/CANCELLING` run，`POST /chat/runs` 会返回 `ACTIVE_RUN_EXISTS`，前端应先调用 stop
或等待当前回答终态后再提交新问题。

## Run 故障治理

所有实例启动后都会运行 watchdog。watchdog 在应用 ready 后延迟启动，每轮带随机 jitter，扫描 `fin_ex_chat_run_execution_t` 中租约过期的 `RUNNING/CANCELLING` execution 和恢复租约过期的 `RECOVERING` execution。Redis recover lock 只用于减少多实例同时抢占同一 run 的 DB 冲突；即使 Redis 不可用，仍会走数据库条件更新，只有更新影响行数为 1 的实例获得恢复权。

默认恢复策略链是 `MANUAL_CONFIRMATION,FAIL_FAST`：

- `MANUAL_CONFIRMATION`：抢占 stale run 后写入 `run.failed` 终态事件，payload 包含 `RUN_EXECUTOR_LOST` 和前端可展示的恢复选项，例如重新生成回答或作为新 run 重试。
- `FAIL_FAST`：兜底把 stale run 置为失败并释放 active run，避免会话永久卡在 `RUNNING`。
- `RUNTIME_TAKEOVER`：预留给支持可靠断点恢复的 Runtime。当前默认 Runtime recovery port 不支持 takeover，因此会自动降级到后续策略。

如果业务 run 已创建，但 `fin_ex_chat_run_execution_t` 控制面初始化失败，服务端会立即追加 `run.failed`，payload code 为 `RUN_EXECUTION_INIT_FAILED`，并释放 active run，避免前端永远停留在生成中。

恢复负载受配置保护：每轮扫描候选数、每轮最大抢占数、每租户最大抢占数、本机恢复并发和 Runtime takeover 并发分别限制，避免单个实例一次性续接或关闭大量 stale run 导致过载。

## 消息树与只读分支

`fin_ex_chat_message_t.parent_message_id` 形成会话内消息树，`node_order/tree_depth/sibling_index` 用于稳定排序和版本切换。普通继续提问会在当前 leaf 后追加 `user -> assistant`；编辑历史问题会在原 user 的父节点下创建新的 user sibling；重新生成回答会在同一个 user 下创建新的 assistant sibling。`run.completed` 后保存完整 assistant 历史消息；如果没有正文但存在卡片、引用、思考、工具、进度等用户可见过程 parts，也会创建空正文 assistant 作为 parts 挂载点；用户主动 stop 时同样会保存已落库正文或用户可见 parts 作为 partial assistant；`run.failed`、watchdog 故障或只有 trace/metadata 等内部事件时不保存空 assistant。

前端点赞/点踩只能针对已落库 assistant 消息。流式阶段的 `message.delta/message.snapshot/message.completed` 只用于渲染草稿；`run.completed.payload.messageReady=true` 时会携带 `assistantMessageId` 和 `feedbackTargetMessageId`，前端应使用该 ID 绑定反馈按钮。

历史消息接口分两层：`GET /api/v1/ex/chat/sessions/{sessionId}/messages` 返回当前 active path，并在有多个 sibling 版本的消息上返回 `versionInfo`，前端可直接展示 `<currentIndex/total>` 版本游标；`versionInfo.variants[].switchLeafMessageId` 是切换该版本时传给 `/messages?leafMessageId=` 和 `/path` 的 leaf。`GET /api/v1/ex/chat/sessions/{sessionId}/messages/tree` 返回完整可见消息树 `mapping/currentLeafMessageId/rootMessageIds`，用于复杂版本树和联调排障。tree 视图只包含业务可见的 user/assistant 消息，不暴露 hidden system、raw log 或下游工具原始节点。

从某条消息新建分支时，服务端会复制 root 到该消息的可见路径到新 session，并将复制出的历史消息标记为 `origin_type=BRANCH_SNAPSHOT`、`locked=true`。这些快照消息只能展示和继续向后提问，不能编辑、删除或重新生成；分支后续新增消息仍为 `NORMAL`，可以参与消息树版本管理。

## 单轮问答分享

分享能力面向“把某一轮问答发给同租户登录用户查看”的场景。前端对某条完整 `assistant`
消息调用 `POST /api/v1/ex/chat/messages/{messageId}/share`，服务端会固定保存该 assistant
消息的直接父 `user` 问题、assistant 正文、附件展示快照，以及 `visible=true` 的 parts。分享内容是
创建时快照，原会话后续编辑、重新生成、反馈变化、路径切换或消息树分支都不会改变已经生成的分享。

分享访问仍要求登录，但权限判断不写死在 Controller 或业务编排里，而是通过
`ChatShareAccessPolicy` 防腐层完成。默认策略是：创建者必须拥有来源 assistant 消息；同租户登录用户
可查看；只有创建者可撤销。后续接企业 ACL、部门权限或外部授权服务时，只需要提供新的
`ChatShareAccessPolicy` bean 覆盖默认实现。

分享支持 `expiresAt` 过期和创建者撤销。会话软删除时，当前用户创建的该会话 `ACTIVE` 分享会被同步撤销。
分享快照只用于展示，不保存 feedback、raw stream log、隐藏/debug parts、Cookie 或鉴权信息；附件只保存
名称、类型、大小和 `documentId` 展示字段，不授予文件下载权限。

分享发送通过 `ChatShareDeliveryProvider` 防腐层完成。前端可先调用
`POST /api/v1/ex/chat/messages/{messageId}/share` 创建快照，再调用
`POST /api/v1/ex/chat/shares/{shareId}/deliveries` 发送；也可以用
`POST /api/v1/ex/chat/messages/{messageId}/share/deliveries` 一键创建并发送。首版 `welink`
provider 会把分享链接转换为 WeLink 卡片请求，`linkUrl` 由 `financeex.share.share-url-prefix + shareId`
生成，`targetAccounts[]/groupIds[]` 会去空去重后以英文逗号拼接。发送失败只写
`fin_ex_chat_share_delivery_t.status=FAILED`，不会删除或撤销分享快照，前端可以重试。分享发送还有
`financeex.share.delivery.max-concurrency` 本机并发保护，避免外部发送 provider 抖动时占满异步工作线程。
WeLink 调用失败后默认最多重试 3 次，可通过 `financeex.share.delivery.providers.welink.max-retries` 调整；为避免误配拖住 Servlet 工作线程，运行时最多按 10 次重试生效。

## 存储命名

所有数据库表统一使用 `fin_ex_*_t`：

- `fin_ex_chat_session_t`
- `fin_ex_chat_message_t`
- `fin_ex_chat_message_attachment_t`
- `fin_ex_chat_run_t`
- `fin_ex_chat_run_execution_t`
- `fin_ex_chat_event_t`
- `fin_ex_intent_recognition_t`：保存实际调用意图服务后的输入、识别结果和最终路由采纳结果；旁路异步写入，不参与主链路决策。
- `fin_ex_runtime_raw_stream_log_t`：保存 Relay normalizer 之前的原始流响应片段，由 raw log MQ 消费端异步写入，仅用于排障。
- `fin_ex_uploaded_document_t`
- `fin_ex_message_feedback_t`：保存当前用户对 assistant 消息的点赞/点踩状态；`status=CANCELLED` 表示已取消当前反馈。
- `fin_ex_chat_share_t`：保存单轮问答分享固定快照；访问权限由 `ChatShareAccessPolicy` 防腐层判断。
- `fin_ex_chat_share_delivery_t`：保存分享发送到 WeLink 等 provider 的请求摘要和发送结果。
- `fin_ex_runtime_binding_t`

所有 Redis key 统一以 `fin_ex:{env}` 开头。`{env}` 由 `spring.profiles.active` 的第一个 profile
自动注入；没有 active profile 时使用 `default`：

- RuntimeBinding：`fin_ex:{env}:runtime_binding:{tenantId:userId:sessionId}:{leafMessageId}`
- RuntimeBinding 会话索引：`fin_ex:{env}:runtime_binding:index:{tenantId:userId:sessionId}`
- Active run：`fin_ex:{env}:chat_run:active:{tenantId}:{userId}:{sessionId}`
- Cancel flag：`fin_ex:{env}:chat_run:cancel:{runId}`
- Recover lock：`fin_ex:{env}:chat_run:recover_lock:{runId}`
- WebSocket run topic：`fin_ex:{env}:chat_stream:{streamTopicId}`
- 短期消息：`fin_ex:{env}:memory:short_term:messages:{tenantId}:{userId}:{sessionId}`

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

- 短期记忆开启后，按 `recent-turns` 装配最近几轮 user/assistant 问答，优先读 Redis 热缓存，miss 后回源数据库历史消息并回填。
- 长期记忆开启后，通过 `LongTermMemoryStore` 防腐层按当前 query 检索 topK 条相关记忆；默认 `disabled` provider 返回空结果。
- 两者都关闭时，`MemoryContext` 为空上下文，且不会发生 memory 相关 Redis、历史消息读取或长期记忆调用。

## 外部服务接入

用例库和意图服务是可选路由信号，默认关闭；关闭时不会发生外部 HTTP 调用。SubAgent 当前通过单轮 HTTP 文本流接入；Relay Runtime 通过 AgentRuntime 防腐层接入，当前上线版本只保留下游 Relay streamable HTTP 接入。
意图服务当前适配 `code/data/result/items[]` 包装响应，选择最高 `confidence` 的 item，并把 `resourceInstruction.resourceId` 映射为候选技能；只有 `confidence >= FINANCEEX_INTENT_CONFIDENCE_THRESHOLD` 时才采用该技能，否则进入 Relay Runtime。意图服务 HTTP 入参和出参转换已收敛在 infrastructure intent mapper 中，后续下游协议变化优先修改 mapper，不影响应用层 `IntentService` 端口和路由策略。意图服务调用失败后默认最多重试 3 次，可通过 `FINANCEEX_INTENT_MAX_RETRIES` 调整；运行时最多按 10 次重试生效。
意图识别记录是可选旁路能力，默认关闭。开启 `FINANCEEX_INTENT_RECORD_ENABLED=true` 后，仅在本轮实际调用意图服务时异步写入 `fin_ex_intent_recognition_t`，记录用户问题、候选 items、最高置信结果、最终路由是否采纳以及调用耗时，便于后续准确率统计和排障。该写入使用 Servlet/MVC 友好的专用线程池，不读取请求 ThreadLocal；线程池拒绝、序列化失败或 DB 写入失败只记录 warn，不影响 `/chat/runs` 主链路。显式技能、RuntimeBinding 续接、用例库已命中、意图服务关闭时不会写意图记录。

这里需要明确 WebSocket 边界：

- 前端 WebSocket：`/api/v1/ex/chat/ws`，只连接 FinanceEXChatService，用于订阅 `streamTopicId` 并接收已经落库的 ChatEvent。
- 下游 Relay：当前只通过 streamable HTTP 调用，不再保留 FinanceEXChatService 到 RelayAgent 的出站 WebSocket adapter。前端 WebSocket 不触发 `AgentRuntime.query`。

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
export FINANCEEX_INTENT_CONFIDENCE_THRESHOLD=0.85
# 可选：记录每次实际调用意图服务后的输入、结果和最终采纳情况；默认关闭
export FINANCEEX_INTENT_RECORD_ENABLED=false
export FINANCEEX_INTENT_RECORD_EXECUTOR_CORE_SIZE=1
export FINANCEEX_INTENT_RECORD_EXECUTOR_MAX_SIZE=2
export FINANCEEX_INTENT_RECORD_EXECUTOR_QUEUE_CAPACITY=1000

export FINANCEEX_EMPLOYEE_REIMBURSEMENT_AGENT_ENDPOINT=http://employee-reimbursement-agent:9300/v1/query
export FINANCEEX_EMPLOYEE_REIMBURSEMENT_AGENT_STOP_ENDPOINT=http://employee-reimbursement-agent:9300/v1/stop

export FINANCEEX_AGENT_RUNTIME_PROVIDER=relay
export FINANCEEX_RELAY_AGENT_BASE_URL=http://relay-agent:9000
export FINANCEEX_RELAY_AGENT_STREAM_PATH=/v1/agent/runs/stream
export FINANCEEX_RELAY_AGENT_STOP_PATH=/v1/agent/runs/{runId}/stop
# 入口 Cookie 只透传给可信下游 adapter，不写入请求体或持久化数据
export FINANCEEX_AGENT_RUNTIME_FORWARD_COOKIE_ENABLED=true
export FINANCEEX_AGENT_RUNTIME_FORWARD_COOKIE_MAX_LENGTH=8192
export FINANCEEX_AGENT_RUNTIME_FORWARD_COOKIE_ALLOWED_ADAPTERS=relay-stream-http
# legacy-agent 文档 provider upload 可单独开启 Cookie 请求头透传
export FINANCEEX_DOCUMENT_FORWARD_COOKIE_MAX_LENGTH=8192
export FINANCEEX_LEGACY_AGENT_DOCUMENT_FORWARD_COOKIE_ENABLED=true
```

### MVC 生产治理配置

```bash
# Servlet async / Tomcat 长连接容量
export FINANCEEX_MVC_ASYNC_REQUEST_TIMEOUT=30m
export FINANCEEX_TOMCAT_MAX_CONNECTIONS=8192
export FINANCEEX_TOMCAT_THREADS_MAX=200
export FINANCEEX_TOMCAT_ACCEPT_COUNT=200

# WebSocket 白名单和连接治理；生产必须替换为企业前端域名
export FINANCEEX_WEBSOCKET_ALLOWED_ORIGIN_PATTERNS=https://finex.example.com
export FINANCEEX_WEBSOCKET_MAX_CONNECTIONS_PER_USER=8
export FINANCEEX_WEBSOCKET_MAX_SUBSCRIPTIONS_PER_CONNECTION=8
export FINANCEEX_WEBSOCKET_LIVE_BUFFER_CAPACITY=512
export FINANCEEX_WEBSOCKET_IDLE_TIMEOUT=10m

# run 准入与外部慢资源 bulkhead
export FINANCEEX_RUN_MAX_PER_USER_PER_MINUTE=60
export FINANCEEX_RUN_MAX_CONCURRENT_PER_TENANT=200
export FINANCEEX_AGENT_RUNTIME_MAX_CONCURRENT=64
export FINANCEEX_SUB_AGENT_MAX_CONCURRENT=64
export FINANCEEX_DOCUMENT_STORAGE_MAX_CONCURRENT=32

# run 执行控制面、watchdog 与 stale run 恢复治理
export FINANCEEX_INSTANCE_ID=
export FINANCEEX_SCHEDULER_POOL_SIZE=4
export FINANCEEX_CHAT_RUN_LEASE_DURATION=90s
export FINANCEEX_CHAT_RUN_HEARTBEAT_INTERVAL=15s
export FINANCEEX_CHAT_RUN_WATCHDOG_ENABLED=true
export FINANCEEX_CHAT_RUN_WATCHDOG_SCAN_INTERVAL=30s
export FINANCEEX_CHAT_RUN_WATCHDOG_MAX_CLAIMS_PER_SCAN=20
export FINANCEEX_CHAT_RUN_RECOVERY_MAX_CONCURRENCY=4
export FINANCEEX_CHAT_RUN_TAKEOVER_MAX_CONCURRENCY=1
export FINANCEEX_CHAT_RUN_RECOVERY_MAX_CLAIMS_PER_TENANT_PER_SCAN=5
export FINANCEEX_CHAT_RUN_STALE_RECOVERY_STRATEGIES=MANUAL_CONFIRMATION,FAIL_FAST

# 流式 delta 合并降压，不改变前端协议和 Event Resume/WS 恢复语义
export FINANCEEX_CHAT_STREAM_DELTA_COALESCE_ENABLED=true
export FINANCEEX_CHAT_STREAM_DELTA_COALESCE_WINDOW=50ms
export FINANCEEX_CHAT_STREAM_DELTA_COALESCE_MAX_CHARS=512

# Relay 原始流日志，仅用于排障；默认关闭。
# 后续接入企业 MQ 时，提供 RuntimeRawStreamLogPublisher bean，并把 enabled 打开。
export FINANCEEX_RUNTIME_RAW_LOG_ENABLED=false
export FINANCEEX_RUNTIME_RAW_LOG_TRANSPORT=disabled
export FINANCEEX_RUNTIME_RAW_LOG_COALESCE_WINDOW=100ms
export FINANCEEX_RUNTIME_RAW_LOG_MAX_CHARS=4096
export FINANCEEX_RUNTIME_RAW_LOG_HARD_MAX_CHARS=65536
export FINANCEEX_RUNTIME_RAW_LOG_MAX_ROWS_PER_RUN=1000
export FINANCEEX_RUNTIME_RAW_LOG_REDACT_SENSITIVE_FIELDS=true

# Relay 响应映射，决定哪些下游 type/字段可成为 assistant 正文
export FINANCEEX_RELAY_ANSWER_EVENT_TYPES=agent,message.delta,answer,output
export FINANCEEX_RELAY_ANSWER_CONTENT_FIELDS=content,context,delta,message,text,output_text
export FINANCEEX_RELAY_AGENT_CONTEXT_AS_ANSWER=true
```

SubAgent endpoint 是完整 HTTP 地址，当前正式版本支持单轮 HTTP 文本流调用。Relay Runtime 作为 AgentRuntime 实现只保留 `relay-stream-http` API adapter，使用 Relay HTTP 流式协议。

Cookie 透传是 adapter 级能力：`relay-stream-http`、显式技能 legacy Agent chat/cancel，以及 `forward-cookie=true`
的 HTTP 文档 provider upload 会把入口 Cookie 放入下游 HTTP 请求头。`AgentRuntimeRequest.forwardHeaders`、
`LegacySkillAgentRequest.forwardHeaders`、`DocumentUploadCommand.forwardHeaders` 与 cancel 请求中的转发头均被 JSON 忽略，避免 Cookie 进入下游请求体、multipart form 或文档元数据。

Relay Runtime 请求与响应均经过 adapter 防腐层：应用层使用 `AgentRuntimeRequest`，但下游请求体会映射为 Relay 专用 wire DTO，只保留 `runId/sessionId/runtimeSessionId/query/attachments/metadata` 等必要字段；下游 plain text、JSON chunk 或 SSE-like `data:` chunk 可选进入 raw log MQ 旁路，再归一化为 ChatService 标准 `ChatEvent`。前端通过 `ConversationTurnStreamDto.payload.encodedItem.data` 消费 `message.delta.payload.delta`、`message.snapshot.payload.content`、`runtime.progress`、`runtime.metadata`、`runtime.agent`、`runtime.thinking`、`runtime.tool`、`runtime.reference`、`runtime.card`、`runtime.event`、`message.completed`、`run.failed` 等稳定事件，不需要理解 Relay 或 legacy-agent 原始响应格式。大对象分片不新增顶层事件类型，而是通过 `payload.fragment/itemId/delta/complete` 表达。

Relay 响应映射的核心规则是：`type=agent,is_streaming=true` 且包含 `content/context` 时映射为 `message.delta`，用于流式草稿追加；`type=agent,is_streaming=false` 映射为 `message.snapshot`，用于最终正文替换和历史消息保存；纯文本 `steam-complete`、`stream-complete`、`[DONE]` 等终态映射为 `message.completed`；`relay-progress`、`project_home`、`available-modes`、`agent-call`、`thinking-operation-*`、`tool_call_streaming`、引用来源类事件等运行过程分别映射为对应 `runtime.*` 事件，并在 run 完成后保存到 `fin_ex_chat_message_part_t`，供历史消息回显；未知合法 JSON object 才进入脱敏限长后的 `runtime.event.payload.sourcePayload`。Relay 原始 `type` 只进入 payload 的 `sourceType` 或 raw log，不能作为 ChatService 顶层 `event_type`。

legacy-agent 指定技能响应也遵守同一标准事件契约：`content` 中 `<think>...</think>` 片段映射为 `runtime.thinking`，不会写入 assistant 正文；非 think 内容映射为 `message.delta`；`processResult` 映射为 `runtime.progress`；`searchList/sourcesDocuments` 映射为 `runtime.reference`；`cardUrl/diyCardScene/cardList/openCard` 映射为 `runtime.card`。如果 `diyCardScene/openCard/searchList/sourcesDocuments/processResult` 等对象被下游网络 chunk 截断，服务端不会把半截内容解析为 invalid-json，而是在对应的 `runtime.card/runtime.reference/runtime.progress` payload 中携带 `fragment=true`、`itemId`、`delta` 和 `complete`；前端可按 `payload.itemId` 拼接，不应把这些 runtime 片段拼入 assistant 正文。当前 legacy 协议下卡片字段通常不会在同一个 chunk 中同时出现，卡片事件会保留原始 `sourceType`，同帧的 `intent/skillId` 会保留在 card payload 中；`endFlag=true` 映射为 `message.completed`。

## 上线版本边界

当前上线版本只内置 Relay Runtime provider，不保留其他历史 Runtime 分支、专用 prompt assembler 或相关配置。复杂任务通过 Relay Runtime adapter 执行，默认 `provider=relay`，下游固定使用 streamable HTTP，不再提供后端到 Relay 的 WebSocket adapter。

AgentRuntime 防腐层必须保留：应用层只依赖 `AgentRuntime` 接口和 `AgentRuntimeRequest` 契约，不依赖 Relay 的 HTTP、wire DTO 或 chunk 格式。`financeex.agent-runtime.provider` 表示 Runtime 类型，当前为 `relay`；Relay provider 当前固定走 streamable HTTP。后续替换 Runtime 实现时，应新增另一个 `AgentRuntime` provider；后续只替换 Relay 下游协议时，应新增 `RelayRuntimeProtocolAdapter` 实现。

HTTP 错误/提示响应统一为 `{timestamp,path,status,error,code,message}`。身份缺失仍返回 401；
资源不存在或不属于当前用户时返回 HTTP 200，并通过 `code=ACCESS_DENIED` 给出前端提示。
常见错误码包括：`AUTH_CONTEXT_MISSING`、`ACCESS_DENIED`、`BAD_REQUEST`、`VALIDATION_FAILED`、
`ACTIVE_RUN_EXISTS` 和 `CONFLICT`。WebSocket 错误通过 envelope 返回，常见 `code`
包括 `WS_AUTH_FAILED`、`WS_ORIGIN_FORBIDDEN`、`BAD_WS_MESSAGE`、`SUBSCRIBE_ERROR`、
`NOT_SUBSCRIBED` 和 `RECOVER_REQUIRED`。

## 启动

本地没有数据库/Redis 时，可以先启动 Docker 依赖。`docker-compose.yml` 使用 PostgreSQL 兼容容器做本地联调；生产环境可以把 `FINANCEEX_DB_URL` 指向目标数据库，DDL 统一维护在 `src/main/resources/db/schema.sql`：

```bash
docker compose up -d postgres redis
```

数据库容器会创建 `financeex` 数据库和 `supervisor_dev` schema，并执行 `src/main/resources/db/schema.sql`。

Redis 默认使用本地 standalone；生产 Redis Cluster 可用以下环境变量切换：

```bash
export FINANCEEX_REDIS_MODE=cluster
export FINANCEEX_REDIS_CLUSTER_NODES=10.0.0.1:6379,10.0.0.2:6379,10.0.0.3:6379
export FINANCEEX_REDIS_PASSWORD=kunone123
export FINANCEEX_REDIS_CLUSTER_MAX_REDIRECTS=3
```

切到 cluster 后，业务代码仍然只使用 `StringRedisTemplate`。数据库仍是事实源；Redis Cluster
只负责热缓存、取消标记、恢复锁优化和 WebSocket 跨实例实时 fanout。

```bash
mvn spring-boot:run
```

## 文档存储

文档能力分为“文档库资产”和“provider 托管内容”两层：前端始终把本地文件上传到
FinanceEXChatService 统一后端，后端再根据 `targetProvider` 选择对象存储、老 Agent 或未来领域
Agent 的文档 provider adapter。
数据库的 `fin_ex_uploaded_document_t` 保存文档库元数据，聊天请求只引用 `documentId`，不会把文件正文放进消息体。
上传接口对外只有一条 `POST /api/v1/ex/documents`，服务端会按启动模式自动选择适配器：
Servlet/MVC 使用 `MultipartFile`，纯 WebFlux 使用 `FilePart`，两者共用同一套临时落盘和 provider 上传逻辑。
不传 `targetProvider` 时走默认 `default-storage`，即当前 S3/OBS/local 对象存储；传
`targetProvider=legacy-agent` 时会转发老 Agent upload 接口，并把老 Agent 返回的 docId 或 url、docName、docSize
等写入统一文档库 `metadataJson.providerDocument`。如果该 provider 配置 `forward-cookie=true`，上传入口捕获到的
Cookie 会作为下游 upload HTTP header 透传，用于老 Agent 文件服务的企业鉴权；Cookie 不会进入 form 字段或文档库元数据。
文档接口响应里的 `metadataJson` 会解析为 JSON object，便于前端直接读取；数据库表字段仍保存 JSON 字符串。
如果老 Agent 上传响应没有 `docId` 但返回了 `url`，文档库仍视为上传成功：`objectKey` 保存
`legacy-url:{sha256(url)}` 这种短稳定定位符，完整 URL 只保存在 `metadataJson.providerDocument.url`。
这类 URL-only 文档可用于文档库展示和下载/跳转扩展，但第一版不会自动进入指定技能 `sceneParam.docList`。

文档接口：

- `POST /api/v1/ex/documents`：上传本地文件并登记到文档库；可选 multipart 字段包括 `targetProvider`、`skillId`、`metadata`。
- `GET /api/v1/ex/documents?sessionId=...&limit=20&cursor=...`：分页查询当前用户文档库，`sessionId` 可选。
- `GET /api/v1/ex/documents/{documentId}`：查询单个文档。
- `PATCH /api/v1/ex/documents/{documentId}`：更新文档展示名或扩展元数据。
- `GET /api/v1/ex/documents/{documentId}/status`：查询文档处理状态。
- `GET /api/v1/ex/documents/{documentId}/preview-url`：获取后端受控预览地址。
- `GET /api/v1/ex/documents/{documentId}/download`：下载文档对象内容；provider 未启用下载时返回 `DOCUMENT_CONTENT_MANAGED_BY_PROVIDER`。
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

指定历史技能时，前端应先使用同一个上传接口并传 `targetProvider=legacy-agent`。服务端会调用配置中的
老 Agent upload path，把返回的 `docid/docname/docsize/levelCode/serverName/version` 等 allowlist 字段
保存到 `metadataJson.providerDocument`；随后 `/chat/runs.metadata.selectedSkillId` 会触发老 Agent chat
adapter，并只允许引用这些 legacy provider 文档。普通 default-storage 文档不会被自动转传给老 Agent。

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
