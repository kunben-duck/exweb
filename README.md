# FinanceEXChatService

FinanceEXChatService 是 FinanceEX 前台聊天入口和 SuperAgent 主控服务。当前正式版本采用 DomainAgent 会话绑定：用例库、意图服务或前端显式选择命中的 `DomainAgentId` 会绑定为会话级 DomainAgent；复杂任务、未命中任务以及配置的敏感信息意图进入 Relay Delegate。Intent 的规范化 `accessName` 命中专家前缀时进入动态 Relay Domain Expert；前端也可用`targetType=DOMAIN_EXPERT`固定选择专家。普通 Relay 与动态专家正常完成后释放 active RuntimeBinding，固定专家则保持 ACTIVE 并续接。

## 核心链路

```text
用户请求
 -> Controller/WebSocket 入口通过 AuthContextProvider 解析租户和用户
 -> 将不可变 UserContext 显式传入 application
 -> 会话归一化与可选 MemoryContext 装配
 -> 按 runMode 写入或定位消息树节点
 -> 查询 RuntimeBinding
    -> 有 active DomainAgent RuntimeBinding：继续调用当前绑定的 DomainAgent
    -> 有未闭合的 Relay RuntimeBinding：继续当前 Relay 任务
    -> 无 active RuntimeBinding：读取可选路由信号
        -> 前端 targetType=DOMAIN_AGENT：绑定并调用指定 DomainAgent
        -> 前端 targetType=DOMAIN_EXPERT：固定并调用指定 Relay 专家
        -> 用例库/意图服务命中 DomainAgentId：绑定并调用对应 DomainAgent
        -> Intent accessName 精确命中敏感信息配置：使用 Relay Delegate
        -> Intent accessName 命中专家前缀：解析动态 roleName 后使用 Relay Domain Expert
        -> 两者关闭/未命中/不可用/复杂任务：创建一次性 Relay RuntimeBinding 并调用 Relay Runtime
```

DomainAgent 绑定会一直续接，直到下游返回明确拒答 code。若绑定来自意图或用例库，拒答后 ChatService 会在同一轮内重新意图并自动切换到新 DomainAgent；若绑定来自前端手动选择，拒答后命中新 Agent 时会进入 Interaction 确认，用户确认后才切换并用原问题继续回答。
ChatService 的长短期记忆是可选 SuperAgent 增强能力，默认关闭；关闭时不会读取最近历史、Redis 短期缓存或长期记忆服务，只把当前消息和附件传给绑定的 DomainAgent 或 Runtime。

## 分层边界

新开发人员建议先阅读 [开发导读](docs/onboarding.md)，再按具体需求进入下述分层。

- `interfaces`：`/v1/chat/runs`、WebSocket run topic subscribe、Event Resume、会话和文档上传协议适配。
- `application`：聊天主编排、会话、记忆、RuntimeBinding、DomainAgent 会话绑定调用和 Relay Runtime 调用。
- `application.integration`：应用层出站集成抽象，定义对 Relay Runtime、DomainAgent、IntentService、用例库、会话、记忆、文档、ID 和身份能力的依赖边界。
- `domain`：聊天事件、意图结果、路由结果、RuntimeBinding、用例匹配结果等核心模型。
- `infrastructure`：Redis、数据库/MyBatis、用例库 HTTP、DomainAgent HTTP、Relay Runtime WebSocket、DocumentProvider 和对象存储等适配。
- MyBatis Mapper 接口只保留方法签名，当前 openGauss SQL 统一维护在 `src/main/resources/mapper/**/*.opengauss.xml`；`db/init-20260718.sql` 只保留 DDL。后续适配其他数据库时，通过切换 `mybatis.mapper-locations` 选择对应方言 XML。

## 前端接入协议

完整接口和 WebSocket 联调说明见 [前端联调文档](docs/frontend-integration.md)。其中“逐接口最小入参示例”和“`/v1/chat/runs` 不同场景请求体示例”是前端请求体样例的维护入口。
Relay 普通协议见 [Relay WebSocket 文档](docs/relay.md)，Domain Expert 的 `config/chat_expert` 约定见 [专家模式文档](docs/relay-system.md)。

可由 Swagger UI、Redoc 或代码生成工具直接读取的 OpenAPI 3.0.3 定义见
[FinanceEX ChatService OpenAPI](docs/openapi/financeex-chatservice-v1.yaml)。该定义覆盖全部公开业务接口，
并为普通澄清、歧义路由选择、拒答切换确认、Relay 问卷、等待态 stop 和恢复流程提供命名示例。

单实例 `4C4G` 的流式任务、文档上传、普通查询、WebSocket 和 Event Resume 容量测试流程，参见
[单实例压测指导](docs/performance-testing-guide.md)。该文档包含环境准备、测试数据、负载阶梯、停止条件、
一致性检查和容量报告模板。

DomainAgent `isSaveSession` 对 assistant 历史投影的控制边界、企业技能配置防腐层和 Redis 缓存规则，参见
[DomainAgent Assistant 留存控制设计](docs/architecture/domain-agent-assistant-persistence.md)。

- `POST /v1/chat/runs`：唯一任务提交入口。普通提问创建后台 run；`runMode=CONTINUE_INTERACTION` 时提交澄清/审批/确认响应并启动续接 run；返回 `runId`、`sessionId`、`firstSeq` 和 `streamTopicId`。
- `POST /v1/chat/sessions`：显式创建会话；可选传 `appId/appName` 作为不可变分组标识和名称快照。也可以在 `/v1/chat/runs` 中不传 `sessionId`，由后端使用相同字段自动创建会话。
- `GET /v1/chat/sessions/apps?channel=mobile`：查询当前用户非删除会话中的全部 App 分类；`channel` 可选，返回去重后的 `appId/appName`，按分类最近活动时间倒序排列。
- `GET /v1/chat/sessions?appScope=MAIN_SITE&title=利润&channel=mobile&limit=20&cursor=...`：游标分页查询当前用户会话列表；`appScope=MAIN_SITE` 仅返回 `appId=null` 的主站会话，省略时保持全量语义；也可改传具体 `appId`，并返回每个会话第一条 assistant 的 `firstAssistantAnswer/firstAssistantMetadataJson`。
- `GET /v1/chat/sessions/page?appScope=MAIN_SITE&keyword=利润&channel=mobile&curPage=1&pageSize=20`：页码分页查询当前用户历史会话；`keyword`统一模糊匹配标题、已持久化user问题和assistant回答，返回 `totalRows/totalPages`、每个会话第一条 assistant 的正文及原始 metadata 字符串，并返回同一最后Run的`lastRunStatus/lastRunSkillId`。搜索事务默认限时2秒，超时返回`503/SESSION_SEARCH_TIMEOUT`。

页码会话关键字搜索、游标列表最后Run状态读取及页码列表最后Run摘要读取，共用`FINANCEEX_SESSION_SEARCH_DATABASE_QUERY_TIMEOUT_SECONDS`数据库事务超时，允许`1..30`秒，默认2秒；非法值会使服务启动失败。关键字搜索超时返回`503/SESSION_SEARCH_TIMEOUT`；最后Run辅助查询超时则保持列表成功，游标列表返回`lastRunStatus=null`，页码列表返回`lastRunStatus/lastRunSkillId=null`；普通列表主体查询不使用该超时配置。
- `GET /v1/chat/sessions/{sessionId}`：查询单个会话元数据，不返回历史消息和流式状态。
- `POST /v1/chat/sessions/{sessionId}/read`：提交前端已经实际展示到的 `readThroughSeq`，原子推进会话已读水位；不会改变会话列表排序。
- `GET /v1/chat/sessions/{sessionId}/messages?leafMessageId=...&limit=50`：选择会话后查询当前 active path 或指定 leaf path 的最近一页 user/assistant 消息；通过 `nextCursor`向更早消息翻页并在前端 prepend，有多个版本的页内消息会带 `versionInfo`。
- `GET /v1/chat/sessions/{sessionId}/messages/{messageId}/variants`：查询某条消息同父节点下的候选版本完整内容；普通聊天页优先使用 `/messages` 返回的 `versionInfo`。
- `POST /v1/chat/sessions/{sessionId}/path`：持久化会话当前 active path leaf；UI 切换可先使用 `/messages?leafMessageId=...` 刷新展示。
- `POST /v1/chat/sessions/{sessionId}/branches`：从指定消息创建只读历史快照分支。
- `POST /v1/chat/sessions/{sessionId}/archive|restore`：会话归档和恢复。
- `DELETE /v1/chat/sessions/{sessionId}`：软删除会话；若存在 active run，后端会先主动取消 run。
- `DELETE /v1/chat/sessions`：批量软删除会话；请求体传 `sessionIds[]`，运行中会话会先取消 run 后删除。
- `WS /v1/chat/ws`：用户级实时输出通道。客户端使用 `{"type":"subscribe","topicId":"chat-run-{runId}","afterSeq":0}` 订阅本轮 run topic；MVC/Servlet 模式会在 handshake 阶段固化用户身份。服务端 `message.payload` 为 `conversation-turn-stream`，真实聊天事件在 `message.payload.payload.encodedItem.data`。
- `GET /v1/chat/sessions/{sessionId}/events/resume?afterSeq={seq}`：会话级事件恢复有限补发，用于补齐整个会话缺失事件；SSE data 同样是 `conversation-turn-stream`。
- `GET /v1/chat/runs/{runId}/events/resume?afterSeq={seq}`：run 级事件恢复并接续 live，用于跨页签、跨浏览器或跨电脑续接正在输出的当前回答，直到 run 终态；长时间无业务事件时发送 turn stream `heartbeat`，终态后发送 `done`。live tail 异常时当前连接结束，前端从最后成功处理的 sequence 重新恢复。
- `GET /v1/chat/sessions/{sessionId}/stream-status`：查询当前会话最新事件序号、active run、`activeStreamTopicId`、是否可取消、是否等待用户澄清输入，以及当前 `bindingProvider/bindingTargetId/bindingIntentName/bindingRouteSource` 等绑定摘要。等待态返回 `waitingSourceRunId`，供前端调用统一 stop；`AMBIGUOUS_ROUTE` 等待态还会返回 `autoSelectAt/autoSelectTimeoutMs`。
- `POST /v1/chat/intent-candidates`：校验当前用户的user `messageId`后调用Intent置信度接口，直接返回有序候选数组；复用Intent单次超时和最大重试次数，但只重试网络异常、HTTP 408/5xx，并使用候选专用并发闸门、鉴权线程池和退避，不占用普通Intent流式鉴权资源。候选结果不缓存、不持久化。
- `POST /v1/chat/runs/{sourceRunId}/switch-domain-agent`：串行停止source Run后，复用其可信user消息和附件直连候选DomainAgent；不重复创建user消息。成功后返回replacement Run的`runId/streamTopicId`，source已有assistant时形成A/B版本。
- `POST /v1/chat/intent-preference-corrections`：在Run成功受理后独立记录候选技能或模糊意图的人工选择；同一用户、Intent入口和source消息只保留最后一次选择，写入失败不回滚已启动的Run。
- `POST /v1/chat/runs/{runId}/stop`：运行态传 active runId；等待态传 `waitingSourceRunId`。等待态 stop 取消当前 Interaction，并对其关联的 Relay/DomainAgent 执行 best-effort 真实取消；历史 run-A 仍保留 `WAITING_USER`。
- `POST /v1/chat/messages/{messageId}/feedback`：提交或切换 assistant 消息点赞/点踩。
- `DELETE /v1/chat/messages/{messageId}/feedback`：取消当前用户对 assistant 消息的点赞或点踩。
- `POST /v1/chat/messages/{messageId}/share`：为某条 assistant 消息创建单轮问答固定快照分享。
- `POST /v1/chat/shares`：为同一会话分支中明确选择的 user/assistant 消息创建多消息固定快照分享。
- `POST /v1/chat/shares/{shareId}/deliveries`：把已有分享发送到指定 provider，首版内置 `welink`。
- `POST /v1/chat/messages/{messageId}/share/deliveries`：一键创建分享快照并发送到指定 provider。
- `GET /v1/chat/shares/{shareId}`：登录后查看分享详情；默认策略允许同租户用户查看。
- `DELETE /v1/chat/shares/{shareId}`：撤销当前用户创建的分享。
- `GET /v1/chat/shares?curPage=1&pageSize=20`：分页查询当前用户创建的分享，便于管理和撤销。

前端流式模式：

```text
POST /v1/chat/runs
 -> 获取 runId/sessionId/firstSeq/streamTopicId
 -> 使用前端配置的 WebSocket 地址发送 subscribe(topicId=streamTopicId, afterSeq)
 -> 实时输出由 WebSocket run topic 承载
 -> 浏览器刷新/复制页签后，使用前端配置的 Event Resume 地址按 lastSeq 补齐缺失事件
 -> 新页签、新浏览器或跨电脑续接 active run 时，从 activeRunFirstSeq - 1 打开 run 级事件恢复
 -> Run 事件恢复先补发历史事件，再持续接续 live 事件，直到本轮 run 终态
 -> 用户点击停止时调用前端配置的 stop 接口，服务端在已有正文或用户可见 parts 时保存 partial assistant，并发布 run.cancelled 终态事件
```

当前请求体只有对话文本和可选文档附件，不暴露 IM 消息类型，也不让前端选择多套响应协议。文档不是消息类型，只是对话消息的上下文资源引用。
WebSocket、Event Resume 和 stop 的 URL 由前端 SDK 或网关配置管理，不随 `/v1/chat/runs` 响应返回。

`/v1/chat/runs` 支持消息树写入模式：`runMode=NEXT` 表示沿当前 leaf 继续提问；`EDIT_USER` 表示编辑历史 user 消息并创建新的 user sibling；`REGENERATE_ASSISTANT` 表示复用原 user 消息重新生成新的 assistant sibling；`CONTINUE_INTERACTION` 表示提交等待态澄清、审批或确认响应并启动续接 run。普通意图澄清采用完整消息链，每个澄清问题和回答分别保存为 assistant/user 节点；`AMBIGUOUS_ROUTE` 候选确认、Agent 澄清、审批和 DomainAgent 切换确认复用原等待态 assistant。历史版本不会被覆盖，前端通过 `/messages.versionInfo` 展示版本游标，并通过 `leafMessageId/path` 切换和保存展示路径。

会话 App Tag 仅用于产品分组和可选列表过滤，不替代 `tenantId + userId` 归属校验。`appId/appName` 均可省略；`appName` 不能脱离 `appId` 单独传入，空字符串按未传处理。未绑定 `appId` 的会话定义为主站会话，列表使用 `appScope=MAIN_SITE` 查询；省略 `appScope/appId` 仍查询全量，`MAIN_SITE` 不能与具体 `appId` 同时使用。已有会话中显式传入的 tag 必须与创建快照完全一致，分支会话继承源 tag，重命名、归档和恢复不会修改它。`/sessions/apps` 排除已删除和主站会话，相同 `appId` 只返回一次，展示名称取最近的非空 `appName` 快照。tag 不进入 run metadata、RouteMemory、IntentAgent、Relay 或 DomainAgent 请求。

移动端会话创建和列表隔离复用现有 `channel`：移动端在自动创建 run 及三个列表接口中统一传小写 `mobile`；PC 端省略该字段，新会话继续默认保存为 `web`，列表仍可查看全部渠道。已有会话只有在请求显式携带 `channel` 时才校验一致性，因此该过滤属于展示和创建隔离，不替代会话的 `tenantId + userId` 归属校验。带 channel 的游标使用绑定 `appId/title/channel` 的 v4 格式；无 channel 的 v2/v3 游标继续兼容。

启用 `financeex.session-title.enabled=true` 后，服务端会在有效 `NEXT/EDIT_USER` 用户消息提交后异步使用当前路径前三个业务问题总结会话标题。前三问完整总结尚未成功时，第四轮及后续有效问题会继续触发补偿调用，但请求内容仍固定为前三问；成功提交后不再因普通后续轮次调用。标题调用不阻塞首事件、Intent或Runtime，不产生实时事件；前端在后续会话列表或详情查询中读取结果。请求可选字段 `language` 最大32字符，空白时使用 `financeex.session-title.default-language`，且不进入 metadata 或 Agent 请求。自动结果只覆盖服务端默认或自动标题，显式标题、手动重命名、只读分支及没有私有状态标记的存量会话均受保护。标题编排通过`SessionTitleAppExclusionProvider`按当前可信会话AppId判断是否跳过；默认实现读取逗号分隔的`excluded-app-ids`，配置项会trim、去空并去重，再按大小写敏感的精确值匹配，`appId=null`的主站会话不受影响。企业可提供自定义Provider替换配置来源；查询失败或返回空结果时继续标题提炼。排除规则不会回滚已有自动标题，仅阻止后续提炼及晚轮补偿。开启功能时必须显式配置 `base-url`、正数且不超过30秒的 `timeout` 和有效的 `session-title` 鉴权 provider；默认路径为 `/session_title`，默认最大标题长度为50个Unicode码点。每实例默认最多保留8个在途标题请求，可通过 `max-concurrent-requests` 在1到64之间调整；容量已满时仅跳过本次标题总结，不阻塞或中止聊天主流程。

会话列表和详情通过 `hasUnread/latestMessageSeq/lastReadSeq` 返回未读状态。只有成功保存 assistant 的 `run.completed` 和产生用户交互内容的 `run.waiting_user` 会推进最新消息水位；失败、取消和没有 assistant 的完成态不会产生未读。前端应在对应历史消息或实时终态实际展示后调用 `/read`，并提交当时观察到的 sequence；服务端会单调推进且截断超前值，避免旧页签清除后来到达的新消息。

仓库提供独立本地联调台 `local-test-frontend/`。联调台通过 Node 代理访问后端，支持在页面中按 Postman 风格配置 `Cookie`、`Authorization`、`X-*` 等企业鉴权请求头；代理会在 HTTP、fetch Event Resume、文件下载和 WebSocket 握手时统一注入这些请求头。浏览器自身不会、也不能直接手写 `Cookie` 请求头或 WebSocket 自定义请求头。

当 `POST /v1/chat/runs`、`POST /v1/chat/runs/{runId}/stop` 或 `POST /v1/documents`
携带标准 `Cookie` 请求头时，ChatService 会在请求入口捕获一次，并只作为内存快照透传给可信下游：Relay WebSocket、DomainAgent chat/cancel，以及显式配置 `financeex.storage.api-store.forward-cookie=true` 的 api-store 文档上传。Cookie 不会写入 `metadata_json`、消息、事件、日志、前端响应、multipart form 或下游请求体。普通 local/huawei-s3 对象存储上传不会透传 Cookie。

外部 HTTP 服务调用还支持统一的集成服务鉴权请求头防腐层。`financeex.integration-auth.enabled=false`
时不注入任何鉴权头；开启后，`AuthHeaderProviderRegistry` 会按 `serviceCode` 选择 provider。
首版预置 `welink-share`、`intent-service`、`session-title`、`use-case-library` 可配置为 `sgov`，并由企业实现的
`SgovTokenResolver` 提供 `Authorization` 值。Relay Runtime、DomainAgent
和 DomainAgent 文档 provider 默认不接入该鉴权头，仍保持现有 Cookie/普通调用行为。

DomainAgent 调用前的技能配置由单一服务统一查询并缓存，快照包含`skillName/isSaveSession/attachmentType`。
带扩展名的可信附件会按技能`attachmentType`校验；任一格式不支持时不订阅DomainAgent，改为输出
`runtime.progress -> runtime.card -> message.completed -> run.completed`结构化业务完成事件。无扩展名文件、
空限制或不可解析配置按放行处理；仅附件校验所需的配置查询失败也按fail-open放行。

`FINANCEEX_AGENT_DATA_PERSISTENCE_ENABLED=true` 时，同一配置快照还用于assistant留存控制。仅明确返回
`isSaveSession=N` 时，业务 Event 只通过本机流和 Redis Pub/Sub 实时输出，不写入事件表；
run 生命周期、Intent、路由、拒答、澄清、确认和终态 Event 仍持久化。assistant 历史只保存配置化占位文案和
必要的交互控制 Parts；`Y`、空值、`null` 或未配置均使用原有 `FULL` 行为。策略默认按环境、租户和 skillId
在Redis缓存完整配置10分钟；设置`FINANCEEX_DOMAIN_AGENT_SKILL_CONFIG_CACHE_ENABLED=false`后完全跳过Redis读写，
每次新的策略解析都实时查询Provider。无有效缓存且配置查询失败时禁止调用DomainAgent，不降级为`FULL`。默认技能配置
Provider使用HTTP接口并透传当前run入口捕获的Cookie；接口地址和路径必须显式配置，调用超时默认2秒。
Cookie不进入请求体、缓存、事件、metadata、数据库或日志。Agent或Relay的Interaction continuation会从
可信source run继承策略和占位文案，但不会继承来源run的Runtime启动标记；相似命名的未知下游事件不会因
包含approval、clarification或confirmation字样而被当作控制事实落库。Intent过程Event仍按既定边界持久化。

租户和用户身份不从前端 Header/Query/Body 透传，统一由请求入口通过 `AuthContextProvider` 从服务端身份上下文解析一次，并以不可变 `UserContext` 传入应用层。系统内部 `user_id/owner_user_id` 写入值统一来自 `UserContext.ownerUserId()`，优先使用企业 `globalUserId`，缺省回退本地开发态 `userId`。应用层、后台 run 和 `boundedElastic` 阻塞线程不会再次读取请求 ThreadLocal。当前 `ApplicationAuthContextProvider` 直接构造完整 `UserContext`，接入企业身份源时替换该防腐层即可。

MVC/Servlet WebSocket 是一个特殊入口：用户身份必须在 `HandshakeInterceptor.beforeHandshake`
阶段从企业 ThreadLocal 解析并写入 WebSocket session attributes。`afterConnectionEstablished`、
subscribe 和连接关闭回调只读取该身份快照，不会再次调用 `AuthContextProvider`。

生产使用 MVC/Servlet 模式时，需要把长连接当作 Servlet 资源治理：Event Resume 使用
`spring.mvc.async.request-timeout` 和 run 级 heartbeat 防止空闲断流；WebSocket 使用
`financeex.websocket.allowed-origin-patterns` 做 Origin 白名单；该配置必须显式填写企业前端域名。
单用户连接数、单连接订阅数、单 topic 本机订阅数、出站缓冲、live buffer 和空闲超时都由
`financeex.websocket.*` 统一配置。慢客户端或实时缓冲溢出时，服务端会返回
`RECOVER_REQUIRED`，前端应通过 run event resume 补齐后再重新订阅。
run 级 WebSocket 与 Event Resume live tail 默认使用 `financeex.chat-stream.live-source-mode=redis-only`，
只消费 Redis Pub/Sub 实时通道，避免本机 local sink 与 Redis 双源合并导致同一 topic 乱序；
`merge` 和 `local-only` 仅作为兼容排障或单机调试回退。run 级恢复只做一次数据库 catchup；
live source 异常时结束当前实时 tail，由前端退避后重新 Event Resume，不在服务端循环查库。
实时订阅侧还会按 `financeex.chat-stream.live-reorder-*` 做短窗口排序：只对窗口内已到达事件按
`seq` 升序逐条输出，不合并 payload，也不等待不存在的连续 seq，默认最多增加约 20ms 实时延迟。
下游流式事件合并后，事件落库、run 状态推进和实时发布会切换到
`financeex.chat-stream.event-io-executor-*` 专用调度器，避免阻塞式 DB/Redis 调用落到
Reactor `parallel-*` 或 Servlet 请求线程。Redis Pub/Sub 发布使用
`financeex.websocket.redis-publish-*` 有界后台队列，同一 topic 串行发布并做短重试；发布最终失败时会标记
topic 需要恢复，恢复控制消息按较慢间隔重试，远端前端通过 `RECOVER_REQUIRED + Event Resume` 补齐缺口。
`seq` 是数据库事件游标，不是 run topic 内连续序号；多会话并发时同一 topic 看到
`19 -> 21` 不代表丢事件。服务端只在同 topic 更低且未见过的 seq 迟到、live buffer 溢出或
实时源异常时要求恢复，并在错误 envelope 的 `details.recoveryAfterSeq` 中给出更小范围的建议补发点。
同一 WebSocket 连接允许同时订阅多个 session 的多个 run topic。服务端不会因为切换会话而
自动释放旧 topic；隔离依赖订阅前的用户归属校验、事件事实源的 `tenantId/userId/sessionId/runId`
联合查询，以及投递前的 `topicId/runId/sessionId` 一致性校验。前端收到事件后必须按
`payload.sessionId` 分发到对应会话。删除会话时，后端会自动取消该会话的 active run；
前端删除成功后应移除本地会话状态并主动 unsubscribe 相关 topic，避免收到删除后的终态事件影响 UI。
事件写入也会校验 run 与 session 的 tenant/user 归属一致，避免下游 Runtime/DomainAgent 返回错误
`runId/sessionId` 时污染事件事实源。

`FULL` 策略下，Relay 和 DomainAgent 的普通运行事件默认按同一 run 批量落库，批次在条数、等待时间或序列化
字节数任一阈值命中时提交；默认值分别为
`financeex.chat-stream.event-batch-max-size=16`、`event-batch-max-wait=20ms` 和
`event-batch-max-bytes=256KB`。`run.started`、IntentAgent 路由事件、Interaction、DomainAgent
拒答、`message.completed` 和 run 终态仍立即单条提交，并会先刷新待处理普通事件。批量事务提交后
仍按原事件顺序逐条发布，前端事件数量、payload、sequence 和 Event Resume 协议不变；通过
`event-batch-enabled=false` 可恢复逐事件落库。

`ASSISTANT_PLACEHOLDER` 策略下，普通 `message.*` 与下游 `runtime.*` 业务 Event 使用同一数据库全局
sequence 分配顺序，但不执行 Event INSERT；`run.lastSeq` 和 `stream-status.latestSeq` 只表示最新持久化
位置。实时业务 sequence 与后续持久化控制/终态 sequence 之间允许出现缺口。Event Resume 不会补发这些
缺口，页面未订阅或断线期间遗漏的业务内容不可恢复。

当前 `ApplicationAuthContextProvider` 直接构造完整 `UserContext`，不再通过配置文件或环境变量模拟
tenant/user。接入企业身份源时，只需替换该防腐层的身份读取逻辑。

同一个 ChatService 会话下，Relay Delegate 与 Domain Expert 分别维护可恢复会话：各 Profile 首次进入时使用 `new`，再次命中相同 Profile 和相同 `appMode/roleName` 时使用 `resume`。敏感信息意图与普通复杂任务共用 Delegate Profile，但仅该 run 保留答案 `message.delta/message.snapshot`、问卷和必要会话状态；Relay 的 thinking、progress、agent、tool、reference、普通 card 及未知过程事件会在公共 Event 管线前丢弃，不推送、不落库且不生成历史 Parts。答案仍逐帧实时输出并可通过 Event Resume 恢复。该模式只保存在 run 私有 metadata，不写入 Binding，因此后续普通 Delegate run 恢复完整事件流。Relay 正常完成后只把 binding 从 `ACTIVE` 改为 `RESUMABLE`，让下一轮重新意图，但永久保留真实 `runtimeSessionId`；两个 Profile 不交叉复用。

`targetType=DOMAIN_AGENT` 用于前端显式选择财经领域 DomainAgent 的场景，`targetId` 为目标 DomainAgent ID。
该路径会跳过用例库和意图服务，创建或覆盖当前会话的 `provider=domain-agent` RuntimeBinding，并调用 DomainAgent Runtime。
所有 `runMode=NEXT` 请求都支持附件-only：当 `message` 为空或仅包含空白且至少有一个有效附件时，
ChatService 将历史 user 消息正文保存为 `""`，附件仍通过标准 `attachments[]` 返回。只有实际调用
IntentAgent 时，服务端才使用可信文件名派生临时 query：附件-only 为
`[用户上传文档] xxx.pdf，xxx.xls`，文本加附件为 `用户原文 [用户上传文档] xxx.pdf，xxx.xls`。
该临时 query 不覆盖消息正文或 run metadata；DomainAgent 直连、active binding 续接以及最终
Relay/DomainAgent 请求仍使用用户原文，附件-only 时 query 为 `""`。由 IntentAgent 形成的
RouteMemory 使用临时 Intent query，前端直选路由仍使用用户原文。前端传入的附件名称不参与生成。
当附件-only请求未传`sessionId`并由`/runs`自动创建会话时，服务端在原有附件校验中取得可信文件名，
仅使用第一个附件并移除其最后一个扩展名，直接作为现有会话INSERT的初始AUTO标题；不会增加附件查询、
标题UPDATE或数据库往返。预先创建的会话及后续附件-only轮次不修改标题，该轮仍不参与前三问标题提炼。
空 message 且没有有效附件仍返回“用户消息不能为空”，`EDIT_USER` 也仍要求提供文本。
当请求为 `runMode=NEXT` 时，该直连路径也可以从 `WAITING_USER` 会话直接发起：后端在同一个 admission
短事务中取消会话下所有 `WAITING/RESPONDING` Interaction，再保存本轮 user 消息与 RUNNING run。旧的
`WAITING_USER` run 和消息历史保持不变，新 user 消息挂在当前等待 assistant 后；已存在真正执行中的
`RUNNING/CANCELLING` run 时仍拒绝直连，不会自动 stop。直连只使用本轮 `message/metadata/attachments`，
不会携带旧澄清答案，也不会通知或续接旧 Relay；当前 ACTIVE binding 会被替换为 `front-selected`
DomainAgent binding，历史 `RESUMABLE` Relay session 保留。
前端可选传 `selectedIntent={intentId?,intentName}` 作为历史展示摘要；对象存在时 `intentName` 必填，且只能与
`targetType=DOMAIN_AGENT/DOMAIN_EXPERT,targetId=...` 同时使用。该摘要会写入 RuntimeBinding，并在后续自动续接该 binding 时继续返回，
但不参与路由、鉴权、RouteMemory 或意图统计，也不会进入 run metadata、用例库、IntentAgent 或 Runtime 请求。
DomainAgent 下游请求体会把 `metadata` 作为业务扩展，但服务端保留字段 `runId/messageId/skillId/query/sessionId`
始终以绑定的 DomainAgentId、本轮用户问题和 RuntimeBinding.runtimeSessionId 为准，前端传同名字段也不会覆盖。
`metadata.sceneParam.docList` 作为下游业务参数只校验基本结构，不要求与 `attachments[]` 匹配；其中引用的资源权限
由 DomainAgent 或其下游服务负责。标准 `attachments[]` 仍由 ChatService 独立校验当前用户归属、可用状态和数量上限。
实际调用过 IntentAgent 的路由由服务端生成
可信 `docList`：首轮使用本轮附件，意图澄清使用整条澄清链累计附件，并将每个文档已保存的完整
`providerDocument` 覆盖到 Runtime metadata 中；前端传入的 `docList` 不参与这些意图路由。
ChatService 不校验 `targetId` 是否可调用；DomainAgent 权限和 body 业务合法性由下游服务负责。
显式选择的 DomainAgent 会作为 `runtime.metadata` 写入事件流，并在历史 assistant 的 `parts` 中返回；
payload 包含 `targetType`、`targetId`、`domainAgentId`、可选 `intentId/intentName` 和
`intentResult.source=front-selected`，用于前端回显本轮调用的技能。后续复用 binding 时前端无需再次传
`selectedIntent`，历史 part 会从 binding metadata 恢复同一意图摘要。

`agentMode` 是非必填的多维 Agent 模式完整快照。前端可同时提交思考模式、执行模式或未来新增的
任意维度，服务端不维护枚举目录：

```json
{
  "agentMode": {
    "selections": [
      {"scheme":"thinking","code":"deep","displayName":"深度思考"},
      {"scheme":"execution","code":"long_task","displayName":"长任务执行"}
    ]
  }
}
```

对象存在时整体替换，不按 `scheme` 合并；显式 `selections=[]` 时清除当前 DomainAgent Binding
已有记录。对象缺失或为 `null` 时，仅在复用同一个 active DomainAgent Binding 的请求中表示“不更新”；
创建新 Binding 时不会从旧 DomainAgent、Relay 或 Interaction 继承。`selections` 最多 16 项，
同一请求内 `scheme` 不可重复，`scheme/code` 必填，`displayName` 可选。模式只记录在当前
DomainAgent RuntimeBinding metadata，不进入 Relay Binding、run metadata、RouteMemory、IntentAgent、
DomainAgent/Relay 请求或事件。意图澄清和路由切换确认不会暂存模式，最终请求需要由前端重新提交。
`stream-status.bindingAgentMode` 仅在当前 active binding 为 DomainAgent 且已记录模式时返回完整快照。
完整写入场景、数据流和下游隔离边界参见
[AgentMode 仅记录技术设计](docs/architecture/agent-mode-recording.md)。

## 会话与执行标识

- `sessionId`：前端聊天会话 ID，一次聊天会话内可以包含多轮用户请求。
- `messageId`：完整 user/assistant 历史消息 ID，组成会话内消息树。
- `currentLeafMessageId`：会话当前激活路径叶子，历史查询默认从该 leaf 回溯 root。
- `runId`：SuperAgent 为每一轮用户请求生成的执行追踪 ID。
- `streamTopicId`：本轮 run 的 WebSocket 订阅 topic，格式为 `chat-run-{runId}`。
- `runtimeSessionId`：当前 AgentRuntime provider 自己的会话 ID，由 Runtime 返回后保存在 RuntimeBinding 中，下一轮续接时带回。

`runId` 不是长期任务会话；它是单轮执行 correlation id。事件表 `fin_ex_chat_event_t.run_id` 和绑定表 `fin_ex_runtime_binding_t.last_run_id` 都用它做运行轨迹和排障定位。
run 生命周期事实源保存在 `fin_ex_chat_run_t`，状态包括 `RUNNING`、`CANCELLING`、`CANCELLED`、`COMPLETED`、`FAILED`。`CANCELLING` 不允许被迟到的通用 run 更新恢复为 `RUNNING`。运行态 stop 保持 RuntimeBinding 的既有生命周期；等待态 stop 会取消 Interaction 精确引用、仍属于该等待链的 `ACTIVE` Binding，不影响无关的历史 `RESUMABLE` Relay Binding。历史 run-A 仍保留 `WAITING_USER`，以保持事件和消息历史不变。如果用户主动 stop 前已经有 `message.delta`、`message.snapshot` 或卡片、引用、思考、工具、进度等用户可见 parts 成功落库，ChatService 会把截至 stop 时的内容保存为 partial assistant 历史消息，并在消息 `metadata_json` 中标记 `partial=true`、`finishReason=USER_STOP`。partial assistant 只由赢得外部终态 CAS 的实例在同一短事务中保存，CAS 失败者不会改写消息、parts 或 session leaf。
run 执行控制面保存在 `fin_ex_chat_run_execution_t`，只保存 owner 实例、心跳、租约、恢复状态和 `fencing_token`，不混入业务 run 表。后台执行流写入 run 事件时通过数据库 guarded insert 原子校验 execution owner 与 `fencing_token`；stop、watchdog 或未来 Runtime takeover 递增 token 后，旧实例迟到 delta/completed 会被拒绝。路由、Runtime Interaction 和 Relay/DomainAgent 调用前还会执行少量只读 owner 检查；检查只发生在外部副作用边界，不进入普通 chunk 写入热路径。
当前生产版本保持下游标准事件原粒度，不在 ChatService 内合并 `message.delta`；普通 Relay/DomainAgent
事件以及 IntentAgent 的 `intent-progress/intent-delta` 只在数据库提交层按三重阈值组批，
提交后仍逐条写入事件表并逐条推送。这样减少事务和 SQL 往返，
同时不改变前端事件及历史容量。`financeex.chat-stream.delta-coalesce-*` 仅作为事件内容合并的兼容预留。
assistant 终态保存时，message parts 使用多行 `INSERT ... VALUES` 分批写入，默认以
`assistant-part-batch-max-size=100` 或 `assistant-part-batch-max-bytes=1MB` 中先达到的阈值拆批；
单个超限 part 独立成批，parts 顺序及终态事务范围不变。
Relay `is_streaming=false` 或 `generate-response.content` 给出的最终回答会映射为 `message.snapshot`，
前端用它替换当前草稿，历史消息正文也优先使用最后一个快照。
assistant 的思考、工具、进度、agent 调用等过程信息保存到 `fin_ex_chat_message_part_t`，并通过 `ChatMessageDto.parts` 返回；每个 `message.snapshot` 也会保存为隐藏的 `MESSAGE_SNAPSHOT` part，用于历史消息恢复所有回答快照，最终 `ANSWER` part 仍只保存最终正文。用户消息关联的文档附件保存到 `fin_ex_chat_message_attachment_t`，历史消息、tree 和 variants 会通过 `ChatMessageDto.attachments` 返回附件展示快照；下载和预览仍走文档库接口重新鉴权。parts 会提供稳定的 `title/status/channel/displayHint/visible` 展示语义，前端不需要解析 Relay 私有 payload。启用短期记忆缓存时，assistant 先写数据库，Redis 热缓存只在事务提交后更新，事务回滚不会留下超前于数据库的消息。

历史assistant消息的原始 `metadataJson` 可包含服务端维护的单值 `skillId`，记录该消息当前 `runId` 最后一次实际调用的 DomainAgent、敏感信息或专家 Intent accessName，以及合法 Intent `NO_MATCH`。同一run拒答重路由时后一调用覆盖前一调用；最终路由没有可记录标识时不保留旧值。普通 Relay fallback、`ROUTE_MULTI`、Intent 异常降级和系统回复不写入该标记，user消息不写入该字段。SkillId随现有路由提交记录并在终态原有assistant写入中落库，不改变 Runtime 请求、Event、Parts 或路由事实。
集群部署时，取消正确性依赖 Redis cancel flag 和数据库 run 状态；实例故障治理依赖数据库 execution 条件抢占和 fencing token。JVM 内 subscription registry 只用于命中本机执行流时快速释放资源，不作为跨实例事实源。
同一 `tenantId + userId + sessionId` 同一时间只允许一个 active run。若会话已有
`RUNNING/CANCELLING` run，`POST /v1/chat/runs` 会返回 `ACTIVE_RUN_EXISTS`，前端应先调用 stop
或等待当前回答终态后再提交新问题。

## Run 故障治理

所有实例启动后都会运行 watchdog。watchdog 在应用 ready 后延迟启动，每轮带随机 jitter，扫描 `fin_ex_chat_run_execution_t` 中租约过期的 `RUNNING/CANCELLING` execution 和恢复租约过期的 `RECOVERING` execution。Redis recover lock 只用于减少多实例同时抢占同一 run 的 DB 冲突；即使 Redis 不可用，仍会走数据库条件更新，只有更新影响行数为 1 的实例获得恢复权。

同一会话的 `RUNNING/CANCELLING` run 由数据库部分唯一索引保证唯一；用户消息、附件关系、current leaf 与 run 创建处于同一个短事务中，并发失败不会留下无 run 的消息节点。Redis active run 仅作为热缓存，不承担准入正确性。普通流式事件写入前使用 run 行 `FOR SHARE NOWAIT` 与 owner/stop/watchdog 终态串行，终态已持锁时迟到事件立即拒绝。

stop 与 watchdog 写入 `run.cancelled/run.failed` 前会通过 run 行条件更新竞争唯一外部终态写入权，失败者不会再写事件或发布实时消息。stop 首次 `RUNNING -> CANCELLING` 也使用条件 CAS；终态事务失败后，`CANCELLING` run 允许再次 stop 重试。若最终由 watchdog 接管，也会按取消语义闭合为 `run.cancelled`。上述 run 协调短事务默认受 `financeex.chat-run.external-terminal-transaction-timeout-seconds=10` 限制，超时整体回滚，不长期占用数据库连接和工作线程。Interaction 提交后若实例在 continuation run/execution 创建完成前退出，watchdog 会在 `financeex.chat-interaction.responding-orphan-grace`（默认 `2m`）后回收孤儿 `RESPONDING` claim；普通 run 已创建但 execution 未创建时，则由 `financeex.chat-run.execution-init-orphan-grace`（默认 `2m`）控制回收。两类扫描均使用专用索引和既有 batch 上限，不进入普通聊天请求热路径。

默认恢复策略链是 `MANUAL_CONFIRMATION,FAIL_FAST`：

- `MANUAL_CONFIRMATION`：抢占 stale run 后写入 `run.failed` 终态事件，payload 包含 `RUN_EXECUTOR_LOST` 和前端可展示的恢复选项，例如重新生成回答或作为新 run 重试。
- `FAIL_FAST`：兜底把 stale run 置为失败并释放 active run，避免会话永久卡在 `RUNNING`。
- `RUNTIME_TAKEOVER`：预留给支持可靠断点恢复的 Runtime。当前默认 Runtime recovery port 不支持 takeover，因此会自动降级到后续策略。

如果业务 run 已创建，但 `fin_ex_chat_run_execution_t` 控制面初始化失败，服务端会通过与 stop/watchdog 相同的 run 行 CAS 追加唯一 `run.failed`，payload code 为 `RUN_EXECUTION_INIT_FAILED`；进程恰好在该窗口退出时，watchdog 后续以 `RUN_EXECUTION_INIT_ORPHANED` 收敛并释放 active run。

`POST /v1/chat/runs` 默认最多等待 `financeex.chat-run.first-event-timeout=30s` 获取首个持久化事件。该时限只保护 run 创建握手：超时会取消尚未 handoff 的本机订阅、释放 admission permit，并在后台通过终态 CAS 把已创建的 run/execution 收敛为 `run.failed(code=RUN_FIRST_EVENT_TIMEOUT)`；Interaction 尚未创建 continuation run 时则把 claim 退回 `WAITING`。首事件成功返回后的 Relay/DomainAgent 长任务不受该配置限制。配置为 `0` 或负数可禁用。

恢复负载受配置保护：每轮扫描候选数、每轮最大抢占数、每租户最大抢占数、本机恢复并发和 Runtime takeover 并发分别限制，避免单个实例一次性续接或关闭大量 stale run 导致过载。

## 消息树与只读分支

`fin_ex_chat_message_t.parent_message_id` 形成会话内消息树，`node_order/tree_depth/sibling_index` 用于稳定排序和版本切换。普通继续提问会在当前 leaf 后追加 `user -> assistant`；编辑历史问题会在原 user 的父节点下创建新的 user sibling；重新生成回答会在同一个 user 下创建新的 assistant sibling。`run.completed` 后保存完整 assistant 历史消息；如果没有正文但存在卡片、引用、思考、工具、进度等用户可见过程 parts，也会创建空正文 assistant 作为 parts 挂载点；用户主动 stop 时同样会保存已落库正文或用户可见 parts 作为 partial assistant；`run.failed`、watchdog 故障或只有 trace/metadata 等内部事件时不保存空 assistant。

前端点赞/点踩只能针对已落库 assistant 消息。流式阶段的 `message.delta/message.snapshot/message.completed` 只用于渲染草稿；`run.completed` 和用户主动 stop 后的 `run.cancelled` 在 `payload.messageReady=true` 时会携带 `assistantMessageId` 和 `feedbackTargetMessageId`，前端应使用该 ID 绑定反馈按钮。

历史消息接口分两层：`GET /v1/chat/sessions/{sessionId}/messages` 首次返回当前 active path 最近一页，后续使用 `nextCursor`向 root 翻页；每页内部保持 root 到 leaf 正序，前端把后续页 prepend 到已有列表。cursor 固定首次请求的 leaf，新消息或 current leaf 切换不会改变正在翻阅的路径，损坏、跨会话或与显式 `leafMessageId` 不匹配的 cursor 返回 400。接口只为当前页中有多个 sibling 版本的消息返回 `versionInfo`，前端可直接展示 `<currentIndex/total>`；`versionInfo.variants[].switchLeafMessageId` 是切换该版本时传给 `/messages?leafMessageId=` 和 `/path` 的 leaf。`GET /v1/chat/sessions/{sessionId}/messages/tree` 返回完整可见消息树 `mapping/currentLeafMessageId/rootMessageIds`，用于复杂版本树和联调排障。tree 视图只包含业务可见的 user/assistant 消息，不暴露 hidden system 或下游工具原始节点。

从某条消息新建分支时，服务端会复制 root 到该消息的可见路径到新 session，并将复制出的历史消息标记为 `origin_type=BRANCH_SNAPSHOT`、`locked=true`。这些快照消息只能展示和继续向后提问，不能编辑、删除或重新生成；分支后续新增消息仍为 `NORMAL`，可以参与消息树版本管理。

## 聊天消息分享

原有单轮分享面向“把某一轮问答发给同租户登录用户查看”的场景。前端对某条完整 `assistant`
消息调用 `POST /v1/chat/messages/{messageId}/share`，服务端会固定保存该 assistant
消息的直接父 `user` 问题、assistant 正文、附件展示快照，以及 `visible=true` 的 parts。分享内容是
创建时快照，原会话后续编辑、重新生成、反馈变化、路径切换或消息树分支都不会改变已经生成的分享。

多消息分享调用 `POST /v1/chat/shares`，请求携带 `sessionId` 和 `messageIds[]`。消息可以全部为 user、
全部为 assistant，或混合选择，因此运行失败后只有 user 消息的轮次也可以单独分享。服务端要求全部消息
属于当前用户、指定会话和同一条 root-to-leaf 分支，只保存明确选择的节点，并按消息路径排序；不会自动
补齐问答对或中间消息。多消息快照使用 `scope=SELECTED_MESSAGES`，详情在 `messages[]` 中按消息分别返回
附件和 `visible=true` 的 parts。单轮分享继续使用 `scope=SINGLE_TURN` 和原有
`question/answer/parts` 响应，且不增加空的 `messages` 字段。

分享访问仍要求登录，但权限判断不写死在 Controller 或业务编排里，而是通过
`ChatShareAccessPolicy` 防腐层完成。默认策略是：创建者必须拥有每一条来源消息；同租户登录用户
可查看；只有创建者可撤销。后续接企业 ACL、部门权限或外部授权服务时，只需要提供新的
`ChatShareAccessPolicy` bean 覆盖默认实现。

分享支持 `expiresAt` 过期和创建者撤销。会话软删除时，当前用户创建的该会话 `ACTIVE` 分享会被同步撤销。
分享快照只用于展示，不保存 feedback、下游原始响应、隐藏/debug parts、Cookie 或鉴权信息；附件只保存
名称、类型、大小和 `documentId` 展示字段，不授予文件下载权限。
多消息请求默认最多包含 50 个原始 `messageIds`，固定快照序列化后默认不超过 5MiB，分别由
`financeex.share.selected-messages.max-messages` 和 `max-snapshot-bytes` 配置。大小限制只在应用层执行，
数据库不增加快照大小 CHECK。

分享发送通过 `ChatShareDeliveryProvider` 防腐层完成。前端可先调用
`POST /v1/chat/messages/{messageId}/share` 创建快照，再调用
`POST /v1/chat/shares/{shareId}/deliveries` 发送；也可以用
`POST /v1/chat/messages/{messageId}/share/deliveries` 一键创建并发送。首版 `welink`
provider 会把分享链接转换为 WeLink 卡片请求，`linkUrl` 由 `financeex.share.share-url-prefix + shareId`
生成，`targetAccounts[]/groupIds[]` 会去空去重后以英文逗号拼接。发送正文严格使用前端本次请求的
`content`：空值发送空字符串且不回退分享快照，非空值移除HTML并转换为纯文本后按配置截断；发送记录与
WeLink请求保存相同的最终正文。原始`content`按UTF-16长度最多8192，超限请求返回400且不调用WeLink。
WeLink 出站请求会设置
`Referer`，默认取 `financeex.share.delivery.providers.welink.base-url`，也可通过
`financeex.share.delivery.providers.welink.referer` 覆盖；分享发送入口的标准 `Cookie` 请求头会作为
出站 header 透传给 WeLink，但不会进入请求体、发送记录或分享快照。发送失败只写
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
- `fin_ex_uploaded_document_t`
- `fin_ex_message_feedback_t`：保存当前用户对 assistant 消息的点赞/点踩状态；`status=CANCELLED` 表示已取消当前反馈。
- `fin_ex_chat_share_t`：保存单轮问答或多消息分享固定快照；访问权限由 `ChatShareAccessPolicy` 防腐层判断。
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
export FINANCEEX_MEMORY_SHORT_TERM_CACHE_ENABLED=true
export FINANCEEX_MEMORY_SHORT_TERM_CACHE_RECENT_TURNS=5
export FINANCEEX_MEMORY_SHORT_TERM_AGENT_RUNTIME_RECENT_TURNS=5
export FINANCEEX_MEMORY_SHORT_TERM_AGENT_RUNTIME_MAX_CONTEXT_TOKENS=4096
export FINANCEEX_MEMORY_SHORT_TERM_INTENT_RECENT_TURNS=5
export FINANCEEX_MEMORY_SHORT_TERM_INTENT_MAX_CONTEXT_TOKENS=4096
export FINANCEEX_MEMORY_SHORT_TERM_DATABASE_QUERY_TIMEOUT_SECONDS=2

export FINANCEEX_MEMORY_LONG_TERM_ENABLED=false
export FINANCEEX_MEMORY_LONG_TERM_PROVIDER=disabled
export FINANCEEX_MEMORY_LONG_TERM_TOP_K=5
```

- 短期记忆开启后，Redis、Agent Runtime 和 Intent 使用独立窗口。`cache-recent-turns` 只控制 Redis 热缓存容量；Agent Runtime 按自身 `recent-turns` 读取 user/assistant，Intent 在拒答或用户纠偏链路按自身 `recent-turns` 读取 user。业务窗口大于缓存窗口、缓存失效或缓存关闭时直接从数据库当前消息路径读取，不会静默缩短上下文。
- Agent Runtime 和 Intent 各自使用 `max-context-tokens` 限制新增历史数组。默认计数器以序列化后的 UTF-8 字节数作保守估算，可通过 `MemoryTokenCounter` 替换为 GLM tokenizer。Relay 普通 `user-message` 和 DomainAgent 请求根节点使用 `messages`；其中每轮 user/assistant 会携带当前消息路径上服务端记录的可选 `skillId`，未知时省略。短期记忆关闭时不增加该字段。
- Intent 只在 `domain_reject`、`user_correction` 及其后续 `clarify_answer` 中，把 user-only 快照放入最近可见 route 的 `domainSessionMessages`。澄清链使用首次调用时冻结的私有快照，不修改 RouteMemory 事实或 `routeAction`。
- Redis miss 后的短期记忆数据库回源使用默认 2 秒只读事务和 Statement 超时。超时、连接获取失败或其他读取异常均以空记忆继续当前 run，并在默认 30 秒读退避期间避免重复回源；Redis 命中仍可正常使用。`database-required` 只约束消息事实写入，不改变该读取降级语义。
- 长期记忆开启后，通过 `LongTermMemoryStore` 防腐层按当前 query 检索 topK 条相关记忆；默认 `disabled` provider 返回空结果。
- 两者都关闭时，普通短期/长期 `MemoryContext` 为空上下文，且不会发生 memory 相关 Redis、历史消息读取或长期记忆调用。RouteMemory 是独立的路由事实源；只要意图服务开启，ChatService 仍会按会话加载最近成功路由和未完成意图澄清链路，用于组装意图服务 `conversationContext`。

## 外部服务接入

用例库和意图服务是可选路由信号，默认关闭；关闭时不会发生外部 HTTP 调用。意图服务 `ROUTE_SINGLE.items[0].accessName` 先按 `FINANCEEX_INTENT_RESPONSE_ACCESS_NAME_PREFIX` 移除一次匹配的通用前缀，再按固定优先级判断：先区分大小写精确匹配可选的 `FINANCEEX_INTENT_SENSITIVE_INFORMATION_ACCESS_NAME`，命中时使用 Relay Delegate；否则匹配显式配置的 `FINANCEEX_INTENT_DOMAIN_EXPERT_ACCESS_NAME_PREFIX`，命中时移除一次专家前缀并 trim，剩余值作为 Relay Domain Expert 的动态 `roleName`；均未命中时，规范化结果解释为 `DomainAgentId/skillId`。例如通用前缀为 `domain_agent_`、专家前缀为 `domain_expert_` 时，`domain_agent_domain_expert_system-awareness` 会路由到专家角色 `system-awareness`。敏感配置为空时规则关闭；若敏感值与专家前缀重叠，敏感精确匹配优先。`intentId` 保留为业务意图编码，`resourceInstruction.resourceId` 只记录到诊断字段。专家和敏感信息 RouteMemory 均保留原始 Intent 与 `ROUTE_SINGLE`，不改写为 Relay `no_match`。Relay Runtime 通过 AgentRuntime 防腐层接入，唯一通信方式为下游 Relay WebSocket。
意图服务支持阻塞和 SSE 流式两种调用模式。`FINANCEEX_INTENT_INVOCATION_MODE=BLOCKING|STREAMING` 默认取 `STREAMING`：流式模式调用 `/getIntentDecisionStream`，显式配置 `BLOCKING` 时调用 `/getIntentDecision`，不会根据响应 Content-Type 自动改调另一接口。两种模式使用逐字段相同的请求，并把最终完整响应交给同一个结果 mapper。`POST /v1/chat/runs`可选顶层`intentAccessName`会在trim后优先作为本次Intent请求的`accessName`，未传或空白时回退`FINANCEEX_INTENT_ACCESS_NAME`；该字段不进入metadata或Runtime请求，同一run的拒答重意图继续使用本次值，Interaction run-B不继承source run。ChatService 始终以 `data.result.routeAction` 作为唯一裁决点。`ROUTE_SINGLE` 直接取唯一 `items[0].accessName`，完成通用前缀归一化后调用 DomainAgent，或按上述规则调用 Relay Delegate/Domain Expert；缺少 item、有效 `accessName`、专家前缀后的角色、`routeAction` 缺失或未知均属于协议失败，不使用 `intentId/resourceId` 猜测路由。`ROUTE_MULTI` 和 `NO_MATCH` 是合法业务结果，始终进入 Relay Delegate；`NO_MATCH` 的 `intentName` 展示为“未识别到可用意图，进入 {Agent 名称}”，Agent 名称由 `FINANCEEX_INTENT_NO_MATCH_AGENT_NAME` 配置，默认 `FIN Supervisor Agent`，该配置不改变路由目标。合法 `CLARIFY` 进入意图澄清等待态且不会重试；其中 `AMBIGUOUS_ROUTE` 会把 `candidateIntents[].accessName` 按同一前缀规则规范化为候选 `skillId`，支持用户直接选择或由前端在 `autoSelectAt` 到达后调用代为选择，选中的敏感候选进入 Relay Delegate，专家候选携带动态角色进入 Relay Domain Expert；用户选择“其他”重新调用 Intent 时，对应的 `history.type=clarify` 还会按原顺序携带仅含 `intentId/intentName` 的可信候选摘要。只有专家前缀后角色为空的异常澄清响应按协议失败重试。`confidence` 对普通最终路由只用于记录和排障，在 `AMBIGUOUS_ROUTE` 自动选择中用于选取最高候选，相同值按响应顺序。外部路由已进入 run pipeline：后端会先落库并推送 `run.started`，再调用用例库/意图服务；调用意图服务前会先输出 `runtime.progress(payload.sourceType=intent-start, stage=intent_calling)`，用于前端展示“正在识别问题意图”，该事件不包含 prompt、history 或意图原始响应。流式模式还会把 `progress` 映射为 `runtime.progress(sourceType=intent-progress)`、把 `delta` 映射为 `runtime.thinking(sourceType=intent-delta)`；三类临时 Intent 事件均写入事件表并实时推送，但不进入历史 parts 或分享。`intent-progress/intent-delta` 使用普通事件批量阈值，`intent-result` 会先刷新待处理批次并继续保存为历史 part；只有完整 `result` 驱动路由。SSE 注释 ping 不生成 ChatEvent。企业鉴权 Header 在独立有界调度器获取，超时或队列拒绝进入现有重试和降级流程。意图服务 HTTP 入参和出参转换已收敛在 infrastructure intent mapper 中。技术失败和协议失败默认最多重试 3 次，可通过 `FINANCEEX_INTENT_MAX_RETRIES` 调整，运行时最多按 10 次生效；每次流式重试都会新建一条 SSE 连接，已落库的过程事件不撤回，并通过 `attempt/maxAttempts` 区分。重试耗尽后由 `FINANCEEX_INTENT_FAILURE_STRATEGY=RELAY_FALLBACK|FAIL_RUN` 决定进入 Relay Delegate 或直接生成 `INTENT_ROUTING_FAILED`。默认 `RELAY_FALLBACK` 保持兼容；`FAIL_RUN` 不调用 Runtime，并提示用户手动选择技能。超过最大意图澄清轮数仍直接进入 Relay Delegate，不按服务失败处理。
路由接口的阻塞和流式请求都会携带当前ChatRun可信user `messageId`，用于Intent侧复用日志；无Run兼容调用省略该字段。候选查询路径由`FINANCEEX_INTENT_CONFIDENCE_PATH`配置，默认`/intent-recognition-configuration/getIntentConfidence`。
每次实际调用Intent前，还会按`tenant + user + 有效accessName`读取最近的用户偏好并在顶层`userPreferenceCorrections`中发送；默认最多5条，读取失败固定退化为空数组，不影响聊天路由。偏好读取和写入使用与RouteMemory参数相同但彼此独立的有界执行器，偏好不会进入消息、Event、Runtime metadata、DomainAgent或Relay。

RouteMemory 负责为意图服务生成 `conversationContext`：普通无绑定首次路由使用 `routeTrigger=first_turn`；DomainAgent 结构化拒答后重路由使用 `routeTrigger=domain_reject` 并携带本次 `lastIntentRejectReason`；用户提交 `INTENT_CLARIFICATION` 后使用 `routeTrigger=clarify_answer`，若澄清由本次 DomainAgent 拒答触发，则每一轮都继续携带同一份当前拒答原因；前端顶层传 `forceReroute=true` 时由后端转成内部用户纠正触发原因；最新 Relay/no_match 路由的来源 run 正常完成时，下一轮自动使用 `routeTrigger=fallback_followup`。`ROUTE` 表示最终目标已确定且 RuntimeBinding 已成功持久化的路由决策，不要求 Runtime 任务执行成功：后端会在调用 Relay/DomainAgent 前异步写入，后续失败、取消或 DomainAgent 拒答不会删除该事实。`history` 由最近 TopK 可见 `ROUTE` 和当前未完成 `INTENT_CLARIFICATION` 的 `CLARIFY` 链组成；`routeSource=front-selected` 的前端直选仍保存为路由事实并参与最新路由判断，但在 TopK 限制前排除，不发送给 IntentAgent；`user-confirmed` 和 `intent-agent` 路由保持可见。精确 `NO_MATCH` 在意图请求中投影为 `type=NO_MATCH,intent=""`，不会伪装为命中意图；已有 binding 的普通续接和 Agent Interaction 续接没有产生新路由，因此不会写入 history。澄清得到最终目标时会在同一 best-effort 写任务中先折叠 clarify，再写 route；`DELEGATE` 的 `ROUTE_MULTI/NO_MATCH/RELAY_FALLBACK` 统一记录为 `intentName=no_match,intentId=relay,targetProvider=relay`，敏感信息 Delegate 与 Domain Expert 的 `ROUTE_SINGLE` 则保留原始 `intentId/intentName/query`。Relay 执行失败或取消时该 route 仍保留，但不会触发下一轮 `fallback_followup`；Relay 正常完成后仍只保留对应 Profile 的 `RESUMABLE` session，不保留 active 路由。`FAIL_RUN`、未确认候选和用户拒绝切换不写 route。RouteMemory 读写使用独立线程池，异常只降级上下文质量，不阻断 `/v1/chat/runs`。
意图识别记录是可选旁路能力，默认关闭。开启 `FINANCEEX_INTENT_RECORD_ENABLED=true` 后，仅在本轮实际调用意图服务时异步写入 `fin_ex_intent_recognition_t`，记录用户问题、routeAction、候选 items、最终路由是否采纳以及调用耗时，便于后续准确率统计和排障。该写入使用 Servlet/MVC 友好的专用线程池，不读取请求 ThreadLocal；线程池拒绝、序列化失败或 DB 写入失败只记录 warn，不影响 `/v1/chat/runs` 主链路。DomainAgent、RuntimeBinding 续接、用例库已命中、意图服务关闭时不会写意图记录。

意图澄清续接时允许提交答案、附件和 metadata。附件在 Interaction claim 前按 `documentId` 校验归属、状态和真实文件名；历史 user 消息只保存用户真实回答，附件-only 时正文为 `""`，附件通过标准 `attachments[]` 返回。仅发送给 IntentAgent 的本轮 query 会追加文件名：附件-only 为 `[用户上传文档] xxx.pdf`，文本加附件为 `答案 [用户上传文档] xxx.pdf，xxx.xls`。IntentAgent 不接收文档 ID、URL 或完整业务 metadata。最终目标确定后，DomainAgent/Relay 收到 `用户:原问题；系统追问:...；用户:...` 形式的完整折叠问题，其中各轮附件以可信文件名体现，并使用最终一轮 metadata；服务端以整条澄清链累计的可信文档覆盖 `sceneParam.docList`。每轮澄清 user/assistant 消息属于消息树事实，但不会单独写 RouteMemory `ROUTE`；最终 binding 成功后才折叠澄清链并记录一次路由。

WebSocket 边界如下：

- 前端 WebSocket：`/v1/chat/ws`，只连接 FinanceEXChatService，用于订阅 `streamTopicId` 并接收已经落库的 ChatEvent。
- 下游 Relay：FinanceEXChatService 通过出站 WebSocket 执行普通问答、Interaction 续接和 stop。前端 WebSocket 不直接连接 Relay，也不触发 `AgentRuntime.query`。

前端 WebSocket 入口同时兼容两种 Spring 启动模式：纯 WebFlux 启动时使用 WebFlux
`WebSocketHandler`；企业框架引入 `spring-boot-starter-web` 并以 MVC/Servlet 模式启动时，
使用 Servlet WebSocket handler 注册同一路径和同一套协议。如果 Servlet 应用配置
`server.servlet.context-path=/fin/ex`，前端最终连接地址是
`ws://host:port/fin/ex/v1/chat/ws`；如果是 WebFlux 应用，则使用
`spring.webflux.base-path=/fin/ex`。

```bash
export FINANCEEX_USE_CASE_LIBRARY_ENABLED=true
export FINANCEEX_USE_CASE_LIBRARY_BASE_URL=http://use-case-library:9100
export FINANCEEX_USE_CASE_LIBRARY_MATCH_PATH=/v1/use-cases/match

export FINANCEEX_INTENT_ENABLED=true
export FINANCEEX_INTENT_BASE_URL=http://intent-service:9200
export FINANCEEX_INTENT_ACCESS_NAME=eureka2_260718
# 可选：例如 accessName=ex_skill1、prefix=ex_ 时，真实 DomainAgent skillId=skill1
export FINANCEEX_INTENT_RESPONSE_ACCESS_NAME_PREFIX=ex_
# 可选：NO_MATCH 的展示目标名称；仍固定路由到 Relay
export FINANCEEX_INTENT_NO_MATCH_AGENT_NAME="FIN Supervisor Agent"
export FINANCEEX_INTENT_INVOCATION_MODE=STREAMING
export FINANCEEX_INTENT_RECOGNIZE_PATH=/intent-recognition-configuration/getIntentDecision
export FINANCEEX_INTENT_RECOGNIZE_STREAM_PATH=/intent-recognition-configuration/getIntentDecisionStream
export FINANCEEX_INTENT_CONFIDENCE_PATH=/intent-recognition-configuration/getIntentConfidence
export FINANCEEX_INTENT_USER_PREFERENCE_CORRECTIONS_LIMIT=5
export FINANCEEX_INTENT_CANDIDATE_MAX_CONCURRENCY=8
export FINANCEEX_INTENT_CANDIDATE_AUTH_IO_MAX_SIZE=2
export FINANCEEX_INTENT_CANDIDATE_AUTH_IO_QUEUE_CAPACITY=16
export FINANCEEX_INTENT_CANDIDATE_RETRY_MIN_BACKOFF=200ms
export FINANCEEX_INTENT_CANDIDATE_RETRY_MAX_BACKOFF=1s
export FINANCEEX_INTENT_STREAM_FIRST_EVENT_TIMEOUT=5s
export FINANCEEX_INTENT_STREAM_IDLE_TIMEOUT=30s
export FINANCEEX_INTENT_STREAM_TOTAL_TIMEOUT=120s
export FINANCEEX_INTENT_STREAM_AUTH_TIMEOUT=5s
export FINANCEEX_INTENT_STREAM_AUTH_IO_MAX_SIZE=4
export FINANCEEX_INTENT_STREAM_AUTH_IO_QUEUE_CAPACITY=128
# AMBIGUOUS_ROUTE 等待用户选择的时间；到期后由前端调用 AUTO_SELECT
export FINANCEEX_INTENT_AMBIGUOUS_ROUTE_WAIT_TIMEOUT=30s
# 可选：RELAY_FALLBACK（默认）或 FAIL_RUN
export FINANCEEX_INTENT_FAILURE_STRATEGY=RELAY_FALLBACK
# 可选：记录每次实际调用意图服务后的输入、结果和最终采纳情况；默认关闭
export FINANCEEX_INTENT_RECORD_ENABLED=false
export FINANCEEX_INTENT_RECORD_EXECUTOR_CORE_SIZE=1
export FINANCEEX_INTENT_RECORD_EXECUTOR_MAX_SIZE=2
export FINANCEEX_INTENT_RECORD_EXECUTOR_QUEUE_CAPACITY=1000
export FINANCEEX_ROUTE_MEMORY_TOP_K=5
export FINANCEEX_ROUTE_MEMORY_MAX_CLARIFICATION_ROUNDS=3

export FINANCEEX_DOMAIN_AGENT_BASE_URL=https://domain-agent.example.com
# 可选；未配置或为空时使用 FINANCEEX_DOMAIN_AGENT_BASE_URL
export FINANCEEX_DOMAIN_AGENT_REFERER=https://finance-portal.example.com
export FINANCEEX_DOMAIN_AGENT_CHAT_PATH=/v1/chat
export FINANCEEX_DOMAIN_AGENT_STOP_PATH=/v1/chat/{runId}/cancel
# 旧配置继续控制stop；未配置两个新值时也作为兼容回退值
export FINANCEEX_DOMAIN_AGENT_TIMEOUT=120s
# 首个原始响应chunk及相邻chunk的最大无数据时间，默认300s
export FINANCEEX_DOMAIN_AGENT_STREAM_IDLE_TIMEOUT=300s
# DomainAgent查询从HTTP订阅开始计算的绝对总时限，默认15m
export FINANCEEX_DOMAIN_AGENT_STREAM_TOTAL_TIMEOUT=15m
export FINANCEEX_DOMAIN_AGENT_MAX_REROUTES=3
# 默认 false；开启后手动选择或确认过的 DomainAgent 拒答时直接切换到重意图目标
export FINANCEEX_DOMAIN_AGENT_REFUSAL_AUTO_SWITCH_ENABLED=false
# 结构化拒答的 event + binding 原子提交使用独立 IO 池；Redis cache sync 也在该池异步执行
export FINANCEEX_DOMAIN_AGENT_CONTROL_IO_EXECUTOR_MAX_SIZE=2
export FINANCEEX_DOMAIN_AGENT_CONTROL_IO_EXECUTOR_QUEUE_CAPACITY=128
# Runtime 订阅前的 Binding 补偿使用 2 秒短事务，默认最多尝试 2 次
export FINANCEEX_DOMAIN_AGENT_BINDING_COMPENSATION_TRANSACTION_TIMEOUT_SECONDS=2
export FINANCEEX_DOMAIN_AGENT_BINDING_COMPENSATION_MAX_ATTEMPTS=2
export FINANCEEX_DOMAIN_AGENT_BINDING_COMPENSATION_RETRY_BACKOFF=50ms
# 可选DomainAgent后台任务协议；默认关闭
export FINANCEEX_DOMAIN_AGENT_ASYNC_TASK_ENABLED=false
export FINANCEEX_DOMAIN_AGENT_ASYNC_TASK_MAX_DURATION=24h
export FINANCEEX_DOMAIN_AGENT_ASYNC_TASK_CALLBACK_MAX_CONCURRENCY=4
export FINANCEEX_DOMAIN_AGENT_ASYNC_TASK_CALLBACK_REQUEST_MAX_BYTES=5242880
export FINANCEEX_DOMAIN_AGENT_ASYNC_TASK_CALLBACK_MAX_FRAMES=128
export FINANCEEX_DOMAIN_AGENT_ASYNC_TASK_CALLBACK_MAX_EVENTS=128
export FINANCEEX_DOMAIN_AGENT_ASYNC_TASK_CALLBACK_MAX_EVENT_BYTES=1048576

export FINANCEEX_AGENT_RUNTIME_DEFAULT_PROVIDER=relay
export FINANCEEX_RELAY_RUNTIME_ENABLED=true
export FINANCEEX_RELAY_WS_URL=wss://relay.example.com/ws
# 入口 Cookie 只透传给可信下游，不写入请求体或持久化数据
export FINANCEEX_AGENT_RUNTIME_FORWARD_COOKIE_ENABLED=true
export FINANCEEX_AGENT_RUNTIME_FORWARD_COOKIE_MAX_LENGTH=8192
# 文档上传入口 Cookie 最大长度；api-store 可单独开启 Cookie 请求头透传
export FINANCEEX_DOCUMENT_FORWARD_COOKIE_MAX_LENGTH=8192
export FINANCEEX_API_STORE_FORWARD_COOKIE_ENABLED=false
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
export FINANCEEX_WEBSOCKET_SERVLET_SEND_EXECUTOR_CORE_SIZE=4
export FINANCEEX_WEBSOCKET_SERVLET_SEND_EXECUTOR_MAX_SIZE=16
export FINANCEEX_WEBSOCKET_SERVLET_SEND_QUEUE_CAPACITY=256
export FINANCEEX_WEBSOCKET_SERVLET_SEND_QUEUE_MAX_BYTES=2MB
export FINANCEEX_WEBSOCKET_IDLE_TIMEOUT=10m

# run 准入与外部慢资源 bulkhead
export FINANCEEX_RUN_MAX_PER_USER_PER_MINUTE=60
export FINANCEEX_RUN_MAX_CONCURRENT_PER_TENANT=200
export FINANCEEX_AGENT_RUNTIME_MAX_CONCURRENT=64
export FINANCEEX_DOMAIN_AGENT_MAX_CONCURRENT=64
export FINANCEEX_DOCUMENT_STORAGE_MAX_CONCURRENT=32

# run 执行控制面、watchdog 与 stale run 恢复治理
export FINANCEEX_INSTANCE_ID=
export FINANCEEX_SCHEDULER_POOL_SIZE=4
export FINANCEEX_CHAT_RUN_LEASE_DURATION=90s
export FINANCEEX_CHAT_RUN_HEARTBEAT_INTERVAL=15s
export FINANCEEX_CHAT_RUN_HEARTBEAT_BATCH_SIZE=50
export FINANCEEX_CHAT_RUN_HEARTBEAT_TRANSACTION_TIMEOUT_SECONDS=2
export FINANCEEX_CHAT_RUN_WATCHDOG_ENABLED=true
export FINANCEEX_CHAT_RUN_WATCHDOG_SCAN_INTERVAL=30s
export FINANCEEX_CHAT_RUN_FIRST_EVENT_TIMEOUT=30s
export FINANCEEX_CHAT_RUN_EXTERNAL_TERMINAL_TRANSACTION_TIMEOUT_SECONDS=10
export FINANCEEX_CHAT_RUN_WATCHDOG_MAX_CLAIMS_PER_SCAN=20
export FINANCEEX_CHAT_RUN_RECOVERY_MAX_CONCURRENCY=4
export FINANCEEX_CHAT_RUN_TAKEOVER_MAX_CONCURRENCY=1
export FINANCEEX_CHAT_RUN_RECOVERY_MAX_CLAIMS_PER_TENANT_PER_SCAN=5
export FINANCEEX_CHAT_RUN_STALE_RECOVERY_STRATEGIES=MANUAL_CONFIRMATION,FAIL_FAST
export FINANCEEX_CHAT_INTERACTION_DEFAULT_EXPIRE_DURATION=24h

# delta 合并当前默认关闭；以下配置仅作为后续 demand-aware 合并器兼容预留
export FINANCEEX_CHAT_STREAM_DELTA_COALESCE_ENABLED=false
export FINANCEEX_CHAT_STREAM_DELTA_COALESCE_WINDOW=50ms
export FINANCEEX_CHAT_STREAM_DELTA_COALESCE_MAX_CHARS=512
export FINANCEEX_CHAT_STREAM_RESUME_POLL_INTERVAL=1s

# Relay WebSocket 与响应映射
export FINANCEEX_RELAY_WS_APP_MODE=delegate
export FINANCEEX_INTENT_DOMAIN_EXPERT_ACCESS_NAME_PREFIX=domain_expert_
# 可选：通用前缀归一化后的accessName精确命中时使用Relay Delegate
export FINANCEEX_INTENT_SENSITIVE_INFORMATION_ACCESS_NAME=sensitive_information
export FINANCEEX_RELAY_DOMAIN_EXPERT_APP_MODE=domain_expert
export FINANCEEX_RELAY_WS_CONNECT_TIMEOUT=5s
# 分别约束 HTTP Upgrade opening handshake 和后续 config -> session-ready，每个阶段独立计时
export FINANCEEX_RELAY_WS_CONFIG_HANDSHAKE_TIMEOUT=10s
export FINANCEEX_RELAY_WS_INTERRUPT_ACK_TIMEOUT=5s
export FINANCEEX_RELAY_WS_MAX_RUN_DURATION=30m
export FINANCEEX_RELAY_WS_HEARTBEAT_INTERVAL=20s
export FINANCEEX_RELAY_WS_HEARTBEAT_RESPONSE_TIMEOUT=90s
export FINANCEEX_RELAY_WS_IDLE_TIMEOUT=60s
export FINANCEEX_RELAY_WS_MAX_FRAME_BYTES=1MB
export FINANCEEX_RELAY_ANSWER_EVENT_TYPES=agent,message.delta,answer,output
export FINANCEEX_RELAY_ANSWER_CONTENT_FIELDS=content,context,delta,message,text,output_text
export FINANCEEX_RELAY_AGENT_CONTEXT_AS_ANSWER=true
```

DomainAgent endpoint 是完整 HTTP 地址。DomainAgent chat、绑定续接和 stop 都会发送服务端配置的标准 `Referer` 请求头；`FINANCEEX_DOMAIN_AGENT_REFERER` 未配置或为空时回退到 `FINANCEEX_DOMAIN_AGENT_BASE_URL`，前端 metadata 和 Cookie 不能覆盖该请求头，配置值也不会进入请求 body 或持久化数据。DomainAgent 查询使用两个独立边界：`FINANCEEX_DOMAIN_AGENT_STREAM_IDLE_TIMEOUT`限制首个原始响应 chunk 及相邻 chunk 的无数据时间，默认`300s`；`FINANCEEX_DOMAIN_AGENT_STREAM_TOTAL_TIMEOUT`限制从HTTP订阅开始的整轮绝对时长，默认`15m`。旧`FINANCEEX_DOMAIN_AGENT_TIMEOUT`继续控制stop，并在两个新变量未配置时分别作为兼容回退；空闲或总超时均以`DOMAIN_AGENT_TIMEOUT`失败收口。`/v1/chat/runs` 显式传 `targetType=DOMAIN_AGENT,targetId=...` 时会手动绑定该 DomainAgent，`routeSource=front-selected`；未显式传 target 时，当前 active `provider=domain-agent` 绑定优先续接。DomainAgent 下游 body 会以前端 `metadata` 为业务扩展，但服务端保留字段 `skillId/query/sessionId` 始终以绑定的 DomainAgentId、本轮用户问题和 RuntimeBinding.runtimeSessionId 为准，metadata 不能覆盖。没有 active binding 时会先走可选用例库和多轮意图服务：意图服务若返回 `WAITING_CLARIFICATION` 或兼容的 `TaskComplexity.NEED_CLARIFICATION`，本轮生成 `run.waiting_user`，`interactionType=INTENT_CLARIFICATION`，不创建 RuntimeBinding；用户通过 `POST /v1/chat/runs` + `runMode=CONTINUE_INTERACTION` 提交回答后继续调用意图服务，直到最终路由到 `domain-agent` 或 `relay`。DomainAgent 流式返回 `type=agent.refusal,code=FN-EX-CAHT-BIZ-DAG-001` 时，ChatService 会立即取消旧 Agent 流并以 `routeTrigger=domain_reject` 重新意图；自动路由来源直接切换。`front-selected/user-confirmed` 来源默认生成 `ROUTE_SWITCH_CONFIRMATION` Interaction，候选 DomainAgent 或 Relay 均须确认；配置 `FINANCEEX_DOMAIN_AGENT_REFUSAL_AUTO_SWITCH_ENABLED=true` 后，这两类来源也会在拒答事件落库时取消旧 Binding，并直接调用重意图得到的新 DomainAgent 或 Relay。拒答、确认和新 Runtime 输出复用同一 assistant，并通过有序 parts 保留过程，等待确认阶段不生成最终 `ANSWER`。Relay Runtime 唯一使用 WebSocket 短连接：每个 ChatService run 都新建一条下游 WebSocket，先发送 `config`，握手成功后发送 `user-message`，本轮输出结束、stop、异常或超出最大运行时长后立即释放物理连接。`FINANCEEX_RELAY_WS_CONFIG_HANDSHAKE_TIMEOUT` 会分别限制 HTTP Upgrade opening handshake 和 Upgrade 后的 `config -> session-ready`，两个阶段独立计时；opening 超时会取消待升级连接并以 `RELAY_WS_CONFIG_TIMEOUT` 结束本轮。Relay 会话语义由应用层传入的 `runtimeSessionMode=NEW|RESUME` 和 `RuntimeBinding.runtimeSessionId` 控制：同一个 ChatService 会话下第一次进入 Relay Runtime 发送 `sessionMode=new`，`config.sessionId` 使用 ChatService 自身 `sessionId`；收到 `session-ready.session_id` 后回填 run 和 RuntimeBinding 的真实 `runtimeSessionId`。后续提问即使重新建连也发送 `sessionMode=resume`，并携带回填后的 `runtimeSessionId` 和 `supports_incremental_recovery=true`。Relay WS 只以 `session-ready` 作为 config 阶段唯一完成信号；adapter 会将 `session-ready` 作为 `runtime.metadata` 输出，payload 保留 Relay 原始 `session_id/session_mode` 等字段，并补充 `runtimeSessionId` 用于跨实例 stop resume。其他配置阶段响应只用于握手判定，不作为用户回答事件；若收到 `error/clear-session/session-mismatch` 会立即失败。`user-message` 后、回答开始前的前置 `session-state=idle/ready/running/agent_thinking` 和迟到 `config` 会被丢弃；`relay-start` 或首个业务帧会打开回答阶段，`session-state=completed/waiting_user_input/paused` 即使没有前置业务帧也可闭合空输出轮次。普通问答阶段按 `FINANCEEX_RELAY_WS_HEARTBEAT_INTERVAL` 发送 `{ "type": "heartbeat" }` 保活；任意业务帧或 `heartbeat-response` 都会刷新连接活跃时间，超过 `FINANCEEX_RELAY_WS_HEARTBEAT_RESPONSE_TIMEOUT` 仍无回包时转 `run.failed`。`session-state=completed/waiting_user_input/paused` 会正常闭合本轮；`idle` 只作为过程状态，`FINANCEEX_RELAY_WS_MAX_RUN_DURATION` 作为最长运行时间兜底。Agent 对话澄清由 Relay `approval-request(operation_type=questionnaire)` 触发：该帧本身会闭合当前用户轮次并生成 `run.waiting_user`、`AGENT_CLARIFICATION_REQUEST` part 和 Interaction 请求；等待请求默认按 `FINANCEEX_CHAT_INTERACTION_DEFAULT_EXPIRE_DURATION=24h` 过期，配置为 `0` 或负数表示不过期。单独的 `session-state=waiting_user_input` 仅闭合本次 Relay WS，不创建等待态；`paused` 仅表示 Relay 对 stop 的确认。

启用`FINANCEEX_DOMAIN_AGENT_ASYNC_TASK_ENABLED=true`后，DomainAgent可返回
`{"type":"agent.async_started","message":"任务已转入后台执行"}`，把当前run转入后台执行。
ChatService关闭原HTTP流但保持run为`RUNNING`，持久化`run.async_running`并创建或更新原assistant；
execution进入`ASYNC_WAITING`且释放owner。Run Resume在该边界结束，WebSocket Run topic可继续等待回调。
DomainAgent使用请求中的可信`runId`调用`POST /v1/internal/domain-agent/async-tasks/callback`。回调可只通知
`COMPLETED/FAILED`，也可通过`resultMode=APPEND/REPLACE`和有序`frames`返回与实时流相同协议的业务结果；stop、回调和24小时超时竞争同一run终态CAS，同会话在终态前仍受
active run约束。若回调早于`ASYNC_WAITING`事务提交，接口返回
`409/DOMAIN_AGENT_ASYNC_NOT_READY`和`Retry-After: 1`，DomainAgent必须使用相同请求体至少重试15秒。
纯`message.completed/agent.async_finished`帧会作为无结果通知处理，即使指定`REPLACE`也不会清空正文或Parts；
同帧携带的正文、卡片或引用仍按业务结果处理。异步回调不接受`agent.async_started/agent.refusal`等状态机控制帧。
原始请求体有硬边界，最多128帧、128个标准业务事件和1MiB标准事件数据；可选`error`仅接受文本，trim后为空则省略，超过1024个Unicode码点时安全截断。本实例并发容量满时返回
`429/DOMAIN_AGENT_ASYNC_CALLBACK_BUSY`，请求体、帧或标准化事件容量超限返回413。该内部回调必须由企业网关ACL保护，
不依赖用户Cookie。有结果时按`run.async_result_started -> 业务标准事件 -> run.async_finished -> message.completed -> run终态`
提交；APPEND精确追加正文及Parts，REPLACE覆盖正文并仅替换当前run的Parts。FULL模式下结果、assistant和终态Event同事务提交；
no-store仅实时推送业务结果并保留占位历史。数据库提交成功但实时发布失败时，前端通过Run Resume恢复持久化结果和终态Event。

终态规则以本次统一协议为准：业务消息发送后，`session-state=completed/waiting_user_input/paused`
即使前面没有 `relay-start` 或正文事件也会正常闭合空输出轮次；`idle` 和其他非终态初始化状态以及迟到的
`config` 继续被隔离。

上一段的 `user-message/session-ready` 握手描述适用于 Delegate。Domain Expert 在相同 `config` 字段上覆盖 `appMode`并增加可信`roleName`，随后发送 `chat_expert(roleName/content/messages/traceId/metadata)`；两个阶段的角色字段取自同一专家档案，该值来自本次 Intent `accessName` 的专家前缀后缀并固化在 Binding 中。相同角色可 `RESUME` 原会话，不同角色分别创建和保留自己的 `RESUMABLE` Binding。配置阶段还兼容 `type=system` 且内容明确包含 `Ready to chat`。专家和 Delegate 的正常轮次都只由 `session-state.state=completed/waiting_user_input/paused` 结束；`idle`、`agent-call(is_start=false)`、`generate-response(is_final=true)`、`stream-complete` 和 `[DONE]` 均不生成轮次终态。`expert_rejection` 只映射为可见 `runtime.card`，继续等待后续 `session-state`。

`ROUTE_SWITCH_CONFIRMATION` 与 `AMBIGUOUS_ROUTE` 共用
`FINANCEEX_INTENT_AMBIGUOUS_ROUTE_WAIT_TIMEOUT`，默认 `30s`。等待事件和 `stream-status` 返回
`autoActionAt/autoActionTimeoutMs/autoActionType=APPROVE_ROUTE_SWITCH`；前端到期后使用现有
`approved=true` 续接请求自动同意切换。后端不注册定时任务，无在线前端时继续保持等待。

Relay 问卷等待继续使用 `WAITING_USER + CONTINUE_INTERACTION`。run-A 关闭物理 WebSocket，但保留真实 `runtimeSessionId` 和 ACTIVE Relay Binding；run-B 跳过 Intent，在 owner/fencing 栅栏内使用同一会话执行 `RESUME + approval-response`。Relay 入站问卷仍使用 `approval_id`，ChatService 出站时将该值映射为 `request_id`，响应只包含 `request_id/approved/scope/questionnaire_answers`，不发送 `approval_id`。前端继续提交互斥的 `{"label":{"问题":"答案"}}` 或 `{"ignore":true}`；正常答案转发时由后端补充 `ignore=false`，扁平答案仍不兼容。`FINANCEEX_RELAY_QUESTIONNAIRE_WAIT_TIMEOUT` 默认 `0s`，表示永久等待；配置正数时只生成 `autoActionAt/autoActionTimeoutMs/autoActionType=IGNORE_QUESTIONNAIRE` 供前端倒计时，不启动后端任务。页面到期、刷新或重新打开后，由前端提交忽略请求；run-A/run-B 使用不同 topic，但复用同一 assistant 消息。

Relay 问卷续接在建立下游连接前，必须先用 execution owner/fencing 条件保存 run-B 的最终 Runtime 路由。`approval-response` 进入 outbound 前失败时，Binding 条件恢复到 run-A，Interaction 恢复 `WAITING`，允许复用同一 interactionId；进入 outbound 后再失败时结果不可判定，服务端取消 Interaction 和仍由 run-B 持有的 ACTIVE Binding，不自动重发，前端需发起新的 `NEXT`。`RUNTIME_SESSION_UNAVAILABLE` 或 Binding 恢复失败同样按不可重试处理。

普通 `INTENT_CLARIFICATION` 的澄清问题会保存为独立 `assistantSource=intent-agent` 消息。前端使用 `CONTINUE_INTERACTION` 提交后，答案、可信附件关系与 continuation run 原子保存为新的 user 消息；下一轮澄清或最终 DomainAgent/Relay 回答挂在该 user 下。回答一旦受理，即使后续 run 失败、取消或首事件超时也不会重新开放旧 Interaction，避免重复答案节点。澄清附件只接受 `documentId` 作为事实引用，前端传入的名称、MIME、大小和来源不会被信任。

`clarificationType=AMBIGUOUS_ROUTE` 是意图澄清的消息策略例外。run-A 保存候选卡片并进入
`WAITING_USER`，卡片包含服务端候选 `skillId`、`AUTO_SELECT/OTHER` 操作和可配置的
`autoSelectAt`；默认等待 `30s`。用户可在 run-B 中直接提交候选 `targetId`，或传
`interactionAction=AUTO_SELECT` 选择最高置信度候选，这两种方式跳过 IntentAgent 并直接调用
DomainAgent；提交 questionnaire 文本或附件表示“其他”，此时才重新调用 IntentAgent。前端在
`autoSelectAt` 到达后使用同一 `AUTO_SELECT` 请求触发代选，多页签请求由 Interaction CAS 保证最多一个成功。
run-A 与 run-B 使用不同 runId，但复用同一 user/assistant 消息，选择响应和最终回答以不同来源 runId 的
parts 追加到该 assistant。后端不注册本机定时任务；没有在线前端时 Interaction 保持 `WAITING`，重新打开
会话后由前端根据 `stream-status.autoSelectAt` 立即触发。

Cookie 透传适用于 Relay WebSocket、DomainAgent chat/cancel、DomainAgent技能配置查询，以及 `forward-cookie=true`
的 HTTP 文档 provider upload 会把入口 Cookie 放入下游 HTTP 请求头。`AgentRuntimeRequest.forwardHeaders`、
`DomainAgentRequest.forwardHeaders`、`DocumentUploadCommand.forwardHeaders` 与 cancel 请求中的转发头均被 JSON 忽略，避免 Cookie 进入下游请求体、multipart form 或文档元数据。

Relay Runtime 请求与响应均经过 WebSocket 协议防腐层：应用层使用 `AgentRuntimeRequest`，adapter 映射为 Relay `config/user-message` 帧，并把 `AgentRuntimeRequest.metadata()` 中的非敏感业务扩展放入 `user-message.metadata`。Relay 出站 metadata 会额外注入服务端身份上下文中的 `globalUserId` 和 `userAccount`；如果前端传入同名字段，以 `UserContext` 为准。`POST /v1/chat/runs` 和 stop 入口还会通过可替换的 `TraceContextProvider` 捕获一次请求 traceId；该值仅作为内存快照传递，普通问答写入 `config.traceId` 和 `user-message.traceId`，Interaction 续接及跨实例 stop 临时连接写入 `config.traceId`。前端 metadata 中同名 `traceId` 会被过滤，不能覆盖服务端上下文；traceId 不写入 run、事件、消息或数据库。默认使用 `JalorTraceContextProvider`，当前 Jalor 取值方法返回安全空占位，因此不会发送伪造 traceId；接入企业 Jalor SDK 后只需替换该 Provider 内的一处取值表达式。Interaction 续接同样走 Runtime 防腐层：ChatService 使用已有 `runtimeSessionId` 短连接 `resume` 并发送 Relay `approval-response`，不会发送新的 `user-message`，`approval-response` 本身也不增加 traceId。Cookie、token、Authorization、secret、password 等敏感 key 不会进入下游请求体。`runtimeSessionMode=NEW|RESUME` 由应用层根据 RuntimeBinding 明确传给 adapter。WebSocket 文本 frame 由共享 normalizer 归一化为标准 `ChatEvent`；adapter 会隔离 `config` 握手阶段 frame，并在 `user-message` 或 `approval-response` 发送后从 `relay-start`、首个业务帧或 `waiting_user_input/paused` 开始处理业务事件。用户 stop 时，adapter 若命中本机 active WS，直接发送 `{"type":"stop_all_agents"}`；若 stop 请求落到其他实例或本机连接已清理，则新建临时 WS，使用 Relay `runtimeSessionId` 发送 `config(sessionMode=resume, supports_incremental_recovery=true)`，收到 `session-ready` 后发送 `stop_all_agents`，并等待 `session-state=paused` 或 ack 超时后释放连接。本服务取消正确性仍以 cancel flag、DB guarded insert 和 `run.cancelled` 为准。`heartbeat-response` 只作为连接保活回包消费，不写事件表、不推前端；超出活性超时会失败闭合并尽力发送 `stop_all_agents`。`FINANCEEX_RELAY_WS_MAX_FRAME_BYTES` 控制 Relay WebSocket 单帧上限，不承担超大事件拆分职责。前端通过 `ConversationTurnStreamDto.payload.encodedItem.data` 消费标准顶层事件；Relay payload 保留原字段名和嵌套结构，并只额外补充 `source=relay`、`sourceType=<Relay原始type>`、`runtimeSessionId`。

Relay 响应映射的核心规则是：`type=agent,is_streaming=true` 且包含 `content/context` 时映射为 `message.delta`，用于流式草稿追加；`type=agent,is_streaming=false` 和 `type=generate-response,content非空` 映射为 `message.snapshot`，用于最终正文替换和历史消息保存；纯文本 `steam-complete`、`stream-complete`、`[DONE]` 等终态映射为 `message.completed`；`relay-start/relay-progress/relay-end/clarified-query/plan-update/subagent-plan-created/subagent-subtask/approval-result/approval-response` 映射为 `runtime.progress`；`session-ready/session-state/project-home/available-modes/self-evolution-status/token-update` 映射为 `runtime.metadata`；Relay WebSocket 普通问答的 `heartbeat-response` 在 adapter 内部过滤，不作为前端事件；`agent-call` 映射为 `runtime.agent`；`agent-reasoning/thinking-operation-*/thinking-content-update` 映射为 `runtime.thinking`；`tool-call-streaming/tool-execution/tool-structured-result` 映射为 `runtime.tool`；引用来源类事件映射为 `runtime.reference`；`approval-request` 映射为 `runtime.card`。所有 Relay JSON payload 都先递归脱敏，再按原字段复制到事件 payload 顶层，不再把 `agent_name/tool_id/result_data/is_start/version_id/session_id` 改写成后端风格字段。`tool-structured-result` 统一作为 `runtime.tool` 返回，完整保留 `result_data/resultData`，不再拆成正文、引用、进度或卡片。未知合法 JSON object 才进入脱敏后的 `runtime.event`。Relay 原始 `type` 只进入 payload 的 `sourceType`，不能作为 ChatService 顶层 `event_type`。

共享 normalizer 仍能识别部分旧完成帧，供独立兼容测试使用；正式 Relay WebSocket adapter 会过滤这些中间 `message.completed`，仅在终态 `session-state`（或问卷 `approval-request` 等待边界）之后生成且只生成一次 `message.completed`。

domain-agent DomainAgent 指定调用响应也遵守同一标准事件契约：`content` 中 `<think>...</think>` 片段映射为 `runtime.thinking`，不会写入 assistant 正文；非 think 内容映射为 `message.delta`。如果两段非空 DomainAgent 正文之间出现 `<think>`、`state=THINKING` 或标准 `thinkState/think_state` 思维链帧，实时事件保持原样，最终历史正文会在两段之间插入 `<!--DOMAIN_AGENT_CONTENT_SEGMENT-->`；后续短期历史上下文也原样携带该标识。独立流式 `contentAgent` 作为自定义卡片内部 MD，逐帧映射为 `runtime.card(sourceType=contentAgent,cardType=contentAgent)`，原文包括空串和 `<think>` 标记均保留，不拼入 assistant 正文；历史消息将同一段连续 `contentAgent` 合并为一个可见 `CARD` part，并在 DomainAgent 拒答或新结构化卡片处切断聚合；完整内容仅保存在 `payload.contentAgent`，该 Part 的 `contentText=null`。`processResult` 映射为 `runtime.progress`；`searchList/sourcesDocuments` 映射为 `runtime.reference`；`cardUrl/diyCardScene/cardList/openCard/specificSceneInfo` 映射为 `runtime.card`。独立的 `type=recommended_questions` 且 `recommendedQuestions` 为数组时映射为 `runtime.card(sourceType=recommended_questions,cardType=recommendedQuestions)`，并在 payload 中保留 `conv_id/run_id/seq`；这些字段只是下游业务数据，不影响 ChatService 的 runId 和事件 sequence。`specificSceneInfo` 保留为同名 payload 字段，授权业务字段不会因名称包含 `authorization` 被误脱敏，真实凭据字段仍会脱敏；对应历史 `CARD` part 默认可见，因此会进入新创建的分享快照。结构化 JSON 即使跨多个网络 DataBuffer 到达，也会在 `financeex.domain-agent.max-pending-frame-bytes` 上限内完成 UTF-8 解码和 JSON 闭合后输出一个完整事件；该上限默认 `256KB`，超过上限按协议错误结束，不输出截断或 fragment 事件。当前 domain-agent 协议下卡片字段通常不会在同一个 frame 中同时出现，卡片事件会保留原始 `sourceType`，同帧的 `intent/domainAgentId` 会保留在 card payload 中；`endFlag=true` 映射为 `message.completed`。

## 上线版本边界

当前上线版本支持多个 AgentRuntime provider 同时注册：`relay` 与 `domain-agent` 同级运行，`financeex.agent-runtime.default-provider` 只表示没有显式 RuntimeBinding 时的 fallback provider，默认 `relay`。复杂任务通过 Relay WebSocket Runtime 执行；普通 Relay 与Intent动态专家正常完成后不保持 active 路由绑定，但会永久保留 `RESUMABLE` 会话引用。前端显式 `targetType=DOMAIN_EXPERT,targetId=<roleName>` 的专家Binding正常完成后保持`ACTIVE`，直到手动切换、forceReroute、等待态stop、会话删除或Runtime session不可恢复。简单任务或前端显式 `targetType=DOMAIN_AGENT,targetId=...` 会绑定并调用 `domain-agent` Runtime。`FINANCEEX_RUNTIME_BINDING_TTL` 默认 `0s`，未配置、零值或负值表示不过期；配置正数时只对 DomainAgent 使用滑动 TTL，Redis `redis-ttl` 仍只是可重建热缓存的过期时间。

AgentRuntime 防腐层必须保留：应用层普通问答只依赖 `AgentRuntime` 和 `AgentRuntimeRequest` 契约，协议级澄清/审批/确认续接只依赖 `AgentRuntimeInteraction` 和 `AgentRuntimeInteractionResponseRequest` 契约，不依赖 Relay 或 DomainAgent 的 wire DTO、HTTP、WebSocket 或 chunk/frame 格式。`AgentRuntime.provider()` 是稳定 provider 编码；Relay provider 当前只注册一个 `RelayRuntimeProtocolAdapter` WebSocket 实现。后续新增 Runtime provider 时注册新的 `AgentRuntime` 实现；替换 Relay 下游协议时仍应在协议防腐层内完成，不把细节写入主编排。

HTTP 错误/提示响应统一为 `{timestamp,path,status,error,code,message}`。身份缺失仍返回 401；
资源不存在或不属于当前用户时返回 HTTP 200，并通过 `code=ACCESS_DENIED` 给出前端提示。
常见错误码包括：`AUTH_CONTEXT_MISSING`、`ACCESS_DENIED`、`BAD_REQUEST`、`VALIDATION_FAILED`、
`ACTIVE_RUN_EXISTS` 和 `CONFLICT`。WebSocket 错误通过 envelope 返回，常见 `code`
包括 `WS_AUTH_FAILED`、`WS_ORIGIN_FORBIDDEN`、`BAD_WS_MESSAGE`、`SUBSCRIBE_ERROR`、
`NOT_SUBSCRIBED` 和 `RECOVER_REQUIRED`。

## 启动

本地没有数据库/Redis 时，可以先启动 Docker 依赖。`docker-compose.yml` 使用 PostgreSQL 兼容容器做本地联调；生产环境必须显式配置数据库、Redis、WebSocket Origin、存储方式和启用集成的 endpoint，DDL 统一维护在 `src/main/resources/db/init-20260718.sql`：

主配置保持 `spring.sql.init.mode=never`，首次部署必须先完整执行 `init-20260718.sql`。应用启动时会校验同一会话 active run 唯一索引；建库脚本遗漏或索引定义不正确时直接拒绝启动，避免生产环境在 Redis 异常或跨实例并发下产生重复 active run。

```bash
docker compose up -d postgres redis
```

数据库容器会创建 `financeex` 数据库和 `supervisor_dev` schema，并执行 `src/main/resources/db/init-20260718.sql`。

主配置不再内置本地 Redis 地址、默认密码或本地存储兜底。Redis standalone/cluster 都必须显式配置；生产 Redis Cluster 可用以下环境变量切换：

```bash
export FINANCEEX_REDIS_MODE=cluster
export FINANCEEX_REDIS_CLUSTER_NODES=10.0.0.1:6379,10.0.0.2:6379,10.0.0.3:6379
export FINANCEEX_REDIS_PASSWORD=
export FINANCEEX_REDIS_CLUSTER_MAX_REDIRECTS=3
```

切到 cluster 后，业务代码仍然只使用 `StringRedisTemplate`。数据库仍是事实源；Redis Cluster
只负责热缓存、取消标记、恢复锁优化和 WebSocket 跨实例实时 fanout。

```bash
# 本地联调可先复制 src/main/resources/application-local.yml.example 为 application-local.yml，
# 再使用 local profile；生产环境不要依赖 local profile。
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## 文档存储

文档能力分为“文档库资产”和“存储实现”两层：前端始终把本地文件上传到
FinanceEXChatService 统一后端，后端只根据 `financeex.storage.provider` 选择 `local`、`huawei-s3`
或 `api-store`。
数据库的 `fin_ex_uploaded_document_t` 保存文档库元数据，聊天请求只引用 `documentId`，不会把文件正文放进消息体。
上传接口对外只有一条 `POST /v1/documents`，服务端会按启动模式自动选择适配器：
Servlet/MVC 使用 `MultipartFile`，纯 WebFlux 使用 `FilePart`，两者共用同一套临时落盘和存储逻辑。
`local` 和 `huawei-s3` 会把文件写入本服务对象存储；`api-store` 会转发新文档上传接口
`/fina/agent/fileOperate/upload`，固定发送 multipart `file`，并在 `metadata` 显式包含 `skillId` 字段时额外发送
`skillId`；`{"skillId":""}` 会原样向下游透传空字符串。下游返回的 `docId/docName/url/docSize/docRelativePath/docStatus/fileSize/serverName/docVersion/message/error`
等字段会写入统一文档库 `metadataJson.providerDocument`。如果 `financeex.storage.api-store.forward-cookie=true`，
上传入口捕获到的 Cookie 会作为下游 upload HTTP header 透传；Cookie 不会进入 form 字段或文档库元数据。
文档接口响应里的 `metadataJson` 会解析为 JSON object，便于前端直接读取；数据库表字段仍保存 JSON 字符串。
如果 api-store 响应没有 `docId` 但返回了 `url`，文档库仍视为上传成功：`objectKey` 保存
`api-store-url:{sha256(url)}` 这种短稳定定位符，完整 URL 只保存在 `metadataJson.providerDocument.url`。
API Store 文档的 `source` 按实际响应定位符确定：有效 `docId` 表示 `EDM_UPLOAD`，仅有 `url` 表示
`S3_UPLOAD`；该字段不依赖请求是否携带 `metadata.skillId`。
这类 URL-only 文档可以通过 `providerDocument.url` 进入 `sceneParam.docList`。标准附件引用仍独立接受当前用户归属和状态校验。

文档接口：

- `POST /v1/documents`：上传本地文件并登记到文档库；可选 multipart 字段包括 `sessionId`、`metadata`。
- `GET /v1/documents?sessionId=...&limit=20&cursor=...`：分页查询当前用户文档库，`sessionId` 可选。
- `GET /v1/documents/{documentId}`：查询单个文档。
- `PATCH /v1/documents/{documentId}`：更新文档展示名或扩展元数据。
- `GET /v1/documents/{documentId}/status`：查询文档处理状态。
- `GET /v1/documents/{documentId}/preview-url`：获取后端受控预览地址。
- `GET /v1/documents/{documentId}/download`：下载文档对象内容；provider 未启用下载时返回 `DOCUMENT_CONTENT_MANAGED_BY_PROVIDER`。
- `DELETE /v1/documents/{documentId}`：软删除文档。

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

指定 DomainAgent 且需要附件时，前端应先在上传请求 `metadata.skillId` 中放入对应技能 ID。若后端
`financeex.storage.provider=api-store`，服务端会把该 `skillId` 透传给下游新文档接口，并把返回的
`docId/url/docName/docSize/serverName/docVersion` 等字段保存到 `metadataJson.providerDocument`；随后
`/v1/chat/runs` 使用 `targetType=DOMAIN_AGENT,targetId=...` 触发 DomainAgent chat adapter。前端可按 DomainAgent
业务协议把 `docId/url` 放入 `metadata.sceneParam.docList`；ChatService 只校验该字段是对象数组且每项包含
`docId` 或 `url`，不要求它与 `attachments[]` 匹配。
普通提问实际进入 IntentAgent 后，服务端会在路由确定时用可信附件的完整 `providerDocument` 覆盖该字段；
`INTENT_CLARIFICATION` 续接使用整条澄清链累计的可信附件执行同样覆盖。

api-store 接入示例：

```bash
export FINANCEEX_STORAGE_PROVIDER=api-store
export FINANCEEX_API_STORE_BASE_URL=https://gce-b7.mfg.huawei.com
export FINANCEEX_API_STORE_UPLOAD_PATH=/fina/agent/fileOperate/upload
```

前端上传时：

```bash
curl -X POST http://localhost:8080/v1/documents \
  -F "file=@./report.pdf" \
  -F 'metadata={"skillId":"d3334be5e4c241ebb30b40d039919787"}'
```

显式配置为本地文件系统：

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
