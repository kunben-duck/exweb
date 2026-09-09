# 默认容量与计数口径

## 1. 可直接回答的容量问题

| 问题 | 基线结论 | 不代表什么 |
|---|---|---|
| 一个会话可同时运行几个任务 | 数据库最多 1 个 RUNNING/CANCELLING；异步等待仍占此名额 | 不等于 WAITING_USER 也占该部分唯一索引 |
| 一个用户可同时运行多少 | **无独立用户并发上限**；60 次/分钟是本实例、同租户用户的准入速率 | 不是 60 并发，更不是全集群统一 60 |
| 一个租户可同时运行多少 | 本实例最多 200 个后台 Run 订阅许可 | 不包括已经释放订阅的 WAIT/异步等待；不保证 200 个都在调用下游 |
| 单实例最多多少 Run | **无独立跨租户总 Run 业务上限**；下游、DB、调度和内存先形成共享瓶颈 | 不能把每租户 200 乘租户数当作可承载承诺 |
| HTTP 最多多少连接 | Tomcat Connector 配置 8192，accept backlog 200 | 不是 8192 同时执行请求，backlog 不是已准入任务队列 |
| 默认 200 个 Tomcat 线程是否有效 | 虚拟线程启用，实际使用 VirtualThreadExecutor，平台线程 max 设置不构成此模式执行上限 | 不能据此宣称无限请求；每请求仍需堆、FD、数据库等资源 |
| 前端 WS 最多多少 | 同实例、同租户用户 8 条；每连接 8 个 topic；每 topic 本机 128 个订阅 | 没有独立全实例 WS 数量上限；128 不适用于 HTTP Resume |
| Resume 最多多少 | **没有独立实例/用户/租户业务连接上限** | 不受上述 WS 注册表配额；仍受 Tomcat、网关、DB、内存约束 |
| Relay WS 最多多少 | 普通 Runtime 流受 AgentRuntime 64 并发保护；Stop 临时连接是另外的生命周期 | 不能把 64 当作所有出站 Relay socket 的绝对总数 |
| 数据库同时多少连接 | 每实例 Hikari 最多 10，获取等待 500ms | 不是只有 10 个 Run；每个 Run 多阶段短暂借还连接 |
| 每分钟能完成多少任务 | **未压测，未知** | 速率限额、连接数和虚拟线程数都不能替代完成量指标 |

## 2. Run 与功能许可矩阵

除数据库唯一约束外，以下许可均为 JVM 本地，不具有集群一致配额。配置键省略公共前缀 `financeex.`。

| 配置/规则 | 默认与限制对象 | 获取至释放 | 超限表现 / 源码 |
|---|---|---|---|
| `run-admission.enabled` | true | Run 启动编排中应用本机限制 | 可关闭，但数据库 active 约束仍存在 |
| `run-admission.max-runs-per-user-per-minute` | 60；tenantId+ownerUserId 的滑动一分钟窗口 | 准入尝试先记速率；时间滑出回收 | IllegalStateException，MVC 为 409/CONFLICT，message 含 RUN_RATE_LIMITED；[RunAdmissionControlService](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/RunAdmissionControlService.java) L55-L90 |
| `run-admission.max-concurrent-runs-per-tenant` | 200；每租户本机后台订阅 | 启动准备前 acquire；后台流 doFinally 释放，包括失败/取消 | 不排队；409/CONFLICT，message 含 TENANT_RUN_CONCURRENCY_EXCEEDED；租户拒绝之前已记用户速率 |
| active Run 部分唯一索引 | 每 tenant/user/session 一个 RUNNING/CANCELLING | Run 创建到退出活动状态 | 409/ACTIVE_RUN_EXISTS；[初始化DDL](../../../src/main/resources/db/init-20260718.sql) 中 active-run 索引 |
| `resource-isolation.agent-runtime-max-concurrent` | 64；AgentRuntime 执行流 | Runtime 订阅至流终止/取消 | AGENT_RUNTIME_BUSY；若已返回 RunId，通过 Run 失败事件呈现，不是入口 HTTP 429 |
| `resource-isolation.domain-agent-max-concurrent` | 64；DomainAgent 执行流 | query 订阅至结束、WAIT/async 边界或取消 | DOMAIN_AGENT_BUSY；不与 AgentRuntime 64 合并为一个池 |
| `resource-isolation.document-storage-max-concurrent` | 32；受包装的存储操作 | 同步try-with-resources持许可；上传涵盖文件读取/存储，不含后续文档DB保存；下载仅到获取InputStream | DOCUMENT_STORAGE_BUSY；不是完整上传接口或慢下载并发上限 |
| `intent.candidate.max-concurrency` | 8；候选逻辑查询 | 查询消息角色之前；包含鉴权、HTTP、重试退避；doFinally 释放 | 第9个立即 429/INTENT_CANDIDATES_BUSY，不查数据库 |
| `domain-agent.async-task-callback-max-concurrency` | 4；整个 Servlet 回调 | Filter 反序列化前至请求/async 结束 | 第5个 429/DOMAIN_AGENT_ASYNC_CALLBACK_BUSY；不对已挂起任务数计数 |
| `intent.feedback.worker-count / queue-capacity` | 1 执行 + 16 等待；GET/POST 共用独立池 | 提交任务至完成；WAITING 最多500ms | 满/排队过期503/INTENT_FEEDBACK_UNAVAILABLE，过期不得迟到写入 |
| `session-title.max-concurrent-requests` | 8；标题功能默认关闭 | 仅生成Publisher含鉴权/HTTP/解析持许可，非整个标题任务 | 不等待结果；前置候选Q及提交TX不受8许可保护，仍可竞争Hikari/Session锁；见TT/R25 |
| `share.delivery.max-concurrency` | 20；外部投递逻辑调用 | 包含 provider 重试 | 忙时失败，不排队无限执行 |

下游保护依据：[WorkloadConcurrencyLimiter](../../../src/main/java/com/huawei/it/ex/one/application/service/runtime/WorkloadConcurrencyLimiter.java)、[AgentRuntimeExecutor](../../../src/main/java/com/huawei/it/ex/one/application/service/runtime/AgentRuntimeExecutor.java)。取消RPC绕过query许可；存储下载的许可只覆盖获得InputStream，不覆盖完整传输。具体调用覆盖和旁路见[依赖](dependencies.md)。

### WAIT、异步与终态的计数

| 阶段 | DB active 约束 | 本机租户许可 | 下游连接 | 前端连接 |
|---|---|---|---|---|
| 准备/Intent/路由 | Run 持久化后占用 | 占用 | 按阶段借用 | 启动 HTTP 可已返回 |
| 正在 DomainAgent/Relay 输出 | 占用 | 占用 | 保持流连接和对应64许可 | WS 或 Run Resume 独立存在 |
| 等待用户 | WAITING_USER 不在部分唯一索引 | 流结束释放 | 本轮结束/断开 | WAIT 事件完成本轮订阅；用户提交续跑新Run |
| DomainAgent 异步挂起 | 仍 RUNNING，阻止同会话新Run | 原流结束释放 | 原 DomainAgent HTTP 释放 | Run Resume 遇边界结束；WS topic 可继续等回调 |
| CANCELLING | 继续占用 | 直到本机执行流清理 | 等待有界取消，非必然下游已停止 | 接收后续 run.cancelled |
| 终态/异常/订阅取消 | 以数据库最终状态为准 | doFinally 释放 | dispose/close | 不等于用户浏览器一定收到了终态 |
| 进程退出 | DB 中 Run/lease 可仍活动 | 内存许可全部消失 | 本机连接断开 | 重新连接其他实例，Watchdog处理过期Execution |

WAIT 的后续普通输入/显式目标选择还受 Interaction 规则约束，不能因唯一索引不覆盖 WAIT 就直接视为允许并行。多实例恢复任务使用自己的 recovery/takeover 限额，不计作原实例准入许可。

## 3. 入站、出站与存储连接

| 类型 | 有效配置/默认 | 范围和实际占用 |
|---|---|---|
| Tomcat Connector | `server.tomcat.max-connections=8192`、`accept-count=200`、连接超时20s | 单实例当前 Connector；HTTP Keep-Alive、SSE、前端 WebSocket Upgrade 共用。监听接受计数可能含 acceptor 预留，不能把监控快照机械等同业务连接数 |
| HTTP handler | `server.tomcat.threads.max=200/min-spare=10`，但 `spring.threads.virtual.enabled=true` | 当前 Boot 将 handler executor 替换为虚拟线程；实测 maxThreads=-1。改回平台线程才重新讨论200工作线程瓶颈 |
| MVC async | `spring.mvc.async.request-timeout=30m` | 异步 HTTP 请求生命周期；并非所有同步阶段/外部副作用的统一硬期限，也不是后台Run总时限 |
| 前端 WS | 8/user、8topic/connection、128subscriber/topic | 本机注册表；无独立 instance 总额。业务等待无永久占用 handler，但保留 socket、session、订阅和缓冲 |
| Session Resume | 无独立业务连接限额；读取后结束 | 一次性历史 List；同一用户可同时发起多个，见R03 |
| Run Resume | 无独立业务连接限额；历史+live | 达终态/WAIT/async边界、取消或网络结束释放；不是 WS 注册表成员 |
| WebClient HTTP/Relay WS | 默认 Reactor Netty shared HttpResources；实际 maxConnections=500/池，获取等待45s | 池按远端/配置分组，不是全应用500总连接。代码无逐依赖专属连接池；HTTP上层timeout通常更早取消。Upgrade占出站socket至关闭，不复用为普通HTTP请求 |
| Hikari JDBC | `maximum-pool-size=10/minimum-idle=0/connection-timeout=500ms` | 所有业务、治理任务共享同一实例连接池；新建物理连接及SQL socket行为还依赖驱动/数据库，不由500ms包办 |
| Redis Lettuce | `timeout=500ms/connect-timeout=500ms`；未配置 commons pool | 同步RedisTemplate复用Lettuce原生连接；Pub/Sub独立连接；Cluster按节点/拓扑新增连接。不是每个请求建立一个Redis连接，也没有可报告的固定“总Redis连接数” |
| S3 | `storage.huawei-s3.max-connections=200` | OBS SDK自己的HTTP连接资源，外层存储操作32保护；不是Tomcat/Hikari连接 |

库证据：Boot 3.4.6、Tomcat10.1.41、Reactor3.7.6、Netty Reactor1.2.6的本地 jar；隔离验证同时开启3条WS后Tomcat连接计数增加，abort后下降。无实际生产TLS/网关/HTTP2实验，不把一个TCP连接等同一个HTTP2请求。

## 4. 线程、队列与缓冲

| 配置键（`financeex.`）或来源 | 默认 | 容量单位、排队期限和故障效果 |
|---|---|---|
| 全局 Reactor boundedElastic | `10 × CPU`，queue100000；Boot启用virtual | 使用JVM可见CPU，不是机器物理核承诺；巨大排队仍占内存，非全局HTTP准入保护 |
| `chat-stream.event-io-executor-max-size/queue-capacity` | 16 / 10000 | newBoundedElastic：最多16平台线程，每backing thread排队参数10000；没有统一短排队期限 |
| `intent.stream-auth-io-max-size/queue-capacity` | 4 / 128 | 专用平台线程，每thread队列；auth5s取消不能保证不可中断token调用消失 |
| `intent.candidate.auth-io-max-size/queue-capacity` | 2 / 16 | 与stream auth隔离；不是整个Scheduler共16位置 |
| `domain-agent.control-io-executor-max-size/queue-capacity` | 2 / 128 | 拒答控制及相关IO；短事务2s不包括所有队列等待 |
| 异步回调Scheduler | 4 / 4，均取`domain-agent.async-task-callback-max-concurrency` | newBoundedElastic专用平台线程，每backing thread队列4；Servlet入口另以4许可保护整个请求，不应按线程乘队列当接口准入数 |
| `agentDataPersistenceIoScheduler`（代码固定） | 4 / 128 | 配置/留存Gate专用Scheduler；没有对应可调配置键 |
| `sessionTitleIoScheduler`（代码固定） | 4 / 128 | 标题功能默认关闭，但Scheduler Bean仍创建；专用平台线程；没有对应可调配置键 |
| RouteMemory read | core1/max2/queue64 | `route-memory.read-executor.*`；300ms读取截止和breaker5次/30s |
| RouteMemory write | core1/max1/queue1000 | `route-memory.write-executor.*`；异步失败开放，不应宣称1000是任务执行并发 |
| Preference read/write | 复用RouteMemory参数，**不共享实例** | 独立线程和熔断器；write1+1000，旧偏好没有反馈的500ms排队保护 |
| Feedback | worker1/queue16/queue-wait500ms | 原子WAITING/RUNNING/EXPIRED/CANCELLED；进入事务后取消排队计时，TX2s |
| Feedback排队定时及Reactive timeout | 复用全局`Schedulers.parallel()`，默认JVM可见CPU数量的平台worker | 不占反馈worker，仍共享全局定时调度；不是一个独立反馈定时线程池，不得在timer上执行阻塞数据库操作 |
| Intent record | core1/max2/queue1000 | 默认关闭；拒绝降级，不阻塞主结果 |
| `websocket.redis-publish-executor-core-size`、`redis-publish-executor-max-size`、`redis-publish-queue-capacity` | 2 / 8 / 4096 | 全池任务队列；队列先填后扩容，topic drain在此运行 |
| `websocket.redis-publish-topic-queue-size`、`redis-publish-topic-max-bytes` | 1024 / 8MiB | 每发布topic；溢出持久化事件提示恢复，live-only仅告警不能恢复 |
| Redis listener（库默认，无配置键） | SimpleAsyncTaskExecutor，concurrency=-1 | 平台线程，当前无显式数量限制；不是上述有界publisher，见R09 |
| `websocket.servlet-send-executor-core-size/max-size` | 4 / 16 | 平台线程 + SynchronousQueue，不再设全局等待队列；drain拒绝关闭对应连接 |
| `websocket.servlet-send-queue-capacity / servlet-send-queue-max-bytes` | 256 / 2MiB | 每Servlet WS连接的发送队列；多个topic共用，超限断连 |
| `websocket.send-time-limit/send-buffer-size-bytes` | 10s / 512KiB | Spring发送decorator保护；不能等同整个端到端消息送达deadline |
| `websocket.live-buffer-capacity` | 512事件 | 每次run topic订阅/Resume的live补偿缓冲；不是全局512 |
| `websocket.delivered-seq-window` | 2048 | 去重记录数量，不是可靠事件存储 |
| `chat-stream.live-reorder-window/max-events` | 20ms / 128 | 短窗口排序，不等待全局sequence连续 |
| `scheduler.pool-size` | 4 | 平台定时线程，至少2；心跳/巡检等共享；Watchdog延时投递后在此同步扫描/逐项恢复，慢数据库仍占worker |

配置路径以 [application.yml](../../../src/main/resources/application.yml) 和各 `*Properties` 绑定为最终依据；pool与queue单位来自构造器而非字段命名。专用池均不能隔离共享 Hikari 10 个连接。

标题4/128的128是每个backing thread的队列参数，不是整个系统最多128个标题任务；该池处理应用排除检查、候选收集、鉴权及提交，不等于8个HTTP许可。其完整资源与等待模型见[标题专项](session-title-flow.md#t4-配置与端到端预算)，与Run生命周期对应见[启动X03](run-startup-details.md#s5-准入后副作用execution与开始事件)。Jalor网关实际入站配额未提供，不应以Tomcat或本地Semaphore默认值替代网关容量。

`RuntimeBindingCacheSynchronizer`复用`chatStreamEventScheduler`，不是独占缓存线程池；删除后的缓存清理虽不持数据库事务，也可能与Event IO争用该16线程资源。恢复4/takeover1是许可数，不表示Watchdog创建4条专用恢复工作线程。

### 消息、批次和请求大小

- Event batch 默认16条/256KiB/20ms；Parts batch100条/1MiB。关闭batch转逐事件写入，不等于减少总DB负载。
- DomainAgent单frame256KiB；Relay单frame1MiB；它们是单帧限制，**不限制整个回答累计长度**。
- 异步回调原始请求5MiB、128帧、128业务事件、1MiB事件数据，最多4并发。终态等控制事件还有少量额外开销；132条不能证明多个topic汇聚时永不溢出。
- HTTP附件最多20个；DomainAgent下游默认最多10，Gate先检查数量；文件50MiB，multipart请求60MiB。
- 候选路由回放最多32条/256KiB；不复制正文和普通业务卡片。
- 这些是各自边界，不能将其相加得出进程内存上限。Java对象、JSON字符串、副本、Parts、多个连接fanout还会放大内存。

## 5. 容量模型与压测入口

令 `R` 为活跃流数，`e` 为每流每秒标准事件，`b` 为平均落库批大小，`tDB` 为每批借用连接时间，`qOther` 为其他业务连接占用。粗略需求为 `R × e / b × tDB + qOther`，与Hikari10比较；这只是排队模型，不是QPS验收值。

| 场景 | 首先观察 | 不能只调整什么 |
|---|---|---|
| 普通短问答 | 首事件P95、Intent首帧、DB每事件批借用时间 | 仅放大tenant200会增加DB排队 |
| 长任务/高速输出 | Relay队列、assembly累计字节、GC、事件lag | 单帧1MiB不能保护累计无界buffer |
| 异步后台任务 | DB RUNNING/ASYNC_WAITING数、到期扫描吞吐 | 64下游并发已释放，不能当挂起任务总量上限 |
| 慢浏览器多topic | 每连接发送字节、16个drain线程占用、close原因 | 2MiB是连接共享，不能乘8当单连接许可 |
| 大量刷新/Resume | 每请求回放行数、DB10、堆峰值、live buffer溢出 | WS每用户8不能控制HTTP Resume风暴 |
| 辅助接口突发 | feedback队列、candidate许可、search耗时、DB等待 | 单独线程池不等于单独数据库池 |
| Redis故障 | publish queue、recovery_required、DB补读量 | 短timeout会把压力转移到DB，须限重连/补读速率 |

多实例本地配额可以分别消耗，且粘性或负载倾斜会使一个实例先饱和。数据库连接理论配置总和为实例数×10，但数据库可用连接预算还要扣除维护、其他服务和扩容余量；不能以配额总和承诺集群容量。
