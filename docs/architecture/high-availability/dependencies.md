# 依赖、超时、重试与数据库边界

## D1. 超时不是一个数字

一个请求可能经过：入口socket/网关 → 准入与调度排队 → 获取JDBC或HTTP连接 → token → HTTP握手 → 首帧 → 相邻帧空闲 → 总运行 → Event提交 → 发布排队 → WS发送。某一层timeout不覆盖其他层。

入站网关明确为Jalor；其转发、重试、ACL、连接和期限均待部署资料确认。阶段追踪见[E到X步骤](run-startup-details.md)、[RT/IT/GA步骤](run-routing-details.md)、[EV到AS步骤](run-control-details.md)，不要把多个异步阶段合并成单一接口timeout。

- `@Transactional(timeout=2/10)` 在事务开始后计时，Spring/MyBatis通常将剩余预算传给JDBC statement；不保证中断之前的队列等待、不可中断Java token调用或事务内Redis操作。
- Hikari500ms是借连接期限，不是SQL执行期限；数据库统一statement/lock timeout须环境确认，仓库无全局MyBatis default-statement-timeout。
- Reactive timeout取消订阅，不证明外部系统未执行，也不保证阻塞SDK/token已经退出。
- 重试次数表示额外重试，通常总尝试`1+retries`。DomainAgent拒答重意图属于业务状态机，不是同一HTTP请求重试。

## D2. 第三方调用矩阵

前缀除标明外为`financeex.`。表内时间是覆盖阶段预算，不是用户请求整体保证。

| 依赖与启用 | 配置及有效默认 | 执行/重试/降级 | 未覆盖阶段 |
|---|---|---|---|
| Intent阻塞；Intent默认关闭 | `intent.timeout=5s`；`max-retries=3`，归一上限10 | 逻辑循环默认4次；失败结果按现有策略立即重试，无退避；最终RELAY_FALLBACK或FAIL_RUN | preference准备、调度、构造headers中的同步鉴权不在HTTP5s内；无整体逻辑总deadline |
| Intent流式；启用后默认STREAMING | `stream-auth-timeout=5s`；`stream-first-event-timeout=5s`；`stream-idle-timeout=30s`；`stream-total-timeout=120s` | auth专用4/128；每次尝试重新auth，偏好读一次；默认3次立即重试 | first为映射后的业务首事件，idle为decoded SSE；120s不含auth且每次重置；无Intent总并发Semaphore |
| Intent候选 | `intent.timeout=5s/max-retries=3`；candidate backoff200ms到1s、jitter50% | 8许可；一次auth，耗时扣首次5s；仅网络/HTTP响应超时/408/5xx重试，其余4xx包括429不重试 | owner角色SQL和获取执行机会不在HTTP尝试预算；不可中断auth超时后底层线程仍可能占用 |
| 技能配置 | `domain-agent-skill-config.timeout=2s`；cache10m，默认true | 专用4/128读写Redis，未命中HTTP一次，无重试；附件-only失败开放，留存启用失败关闭 | Redis读写/队列不在HTTP2s内；无single-flight，同key并发miss仍会各查一次 |
| 用例库；默认关闭 | `use-case-library.timeout=5s` | 无重试，无独立并发池；失败继续Intent/既有fallback | headers同步auth在HTTP timeout之外 |
| DomainAgent；默认关闭 | `domain-agent.stream-idle-timeout=300s`；`stream-total-timeout=15m`；frame256KiB | raw chunk首个/相邻idle；从HTTP订阅计算绝对总时限；64许可，无HTTP重试 | 请求准备/Gate/DB不在这15m内；累计缓存和正文不由单frame限制 |
| DomainAgent取消 | `domain-agent.timeout=120s`；stop-path默认空 | detached best-effort；空路径不发取消；无重试 | 本地cancelled不证明后台任务已停；取消绕过query64许可 |
| Relay Upgrade/config | connect5s、`config-handshake-timeout=10s` | Upgrade和config-ready各自10s，无自动重连；64覆盖执行流 | TCP是Upgrade中的一阶段，不应机械算成5+10；临时stop还有后续控制等待 |
| Relay运行 | `max-run-duration=30m`；heartbeat20s、response90s | 业务dispatch后计时；任意入站刷新活性，周期检查有约20s检测量化；异常close不伪装完成 | 心跳活跃不代表业务在前进；无业务进展总量/累计缓冲限制 |
| Relay停止 | `interrupt-ack-timeout=5s`；临时连接`idle-timeout=60s` | 当前连接发送完成或paused均可结束；临时连接RESUME，异常吞并日志 | 不是整个stop≤5s，更不是后续Run的代次隔离 |
| API Store上传 | `storage.api-store.timeout=30s` | boundedElastic、storage32；整文件读入后block(Duration)，无应用重试；异步HTTP连接获取包含在该等待内 | 整文件读入和请求准备不在HTTP30s内；取消不能撤销远端已完成的副作用 |
| OBS S3 | maxconnections200、connection10s/socket30s | SDK3.25.10默认maxErrorRetry3，典型IO/5xx退避100/200/400ms；storage32保护获取操作 | SDK callTimeout=0；总上传/下载无统一截止；成功上传后DB失败有孤儿窗口 |
| local存储 | provider显式选择 | 文件IO在boundedElastic，无HTTP重试 | 无本地IO deadline；多实例存储一致性不由本服务提供 |
| WeLink；默认关闭 | `share.delivery.providers.welink.timeout=5s`、max-retries3 | provider20许可；每次还有5.1s block保护；任意失败立即重试 | eager auth无期限；本服务未传wire幂等键，下游去重未知；发送成功到记录保存有窗口 |
| 标题；功能默认关闭 | 默认HTTP Provider启用时`session-title.timeout`必须配置，正值≤30s | 专用4/128、8许可；无即时重试；失败以后续合格问题再触发；commit TX2s | 自定义Provider可绕过HTTP Bean校验，应用有效timeout回退30s；候选收集/提交不全在Provider期限内；任务未可靠排队 |

标题完整细分见[TT表](session-title-flow.md)：应用排除默认本地判断；候选阶段正常非空路径为Session Q + 路径Q + 批量Run Q，尚未取得8个生成许可；提交另有Session锁Q + Session Q + 可选1W。候选/提交排队不在生成timeout内。内外两层生成timeout使用同一值，不能加成两次timeout，也不能推定token必响应中断。新增R25记录该启用条件下的资源风险。

**可计算预算：**
- 阻塞Intent：有界HTTP部分默认最多`4×5=20s`，加上无完整deadline的auth/准备，不能写“最坏20s”。
- 流式Intent：各次都走满auth和stream总时限时，名义预算`4×(5+120)=500s`，再加准备；first/idle是内部更早截止条件，不能再加到120s上。
- 候选：auth只一次且计入首次，HTTP/auth受限部分最多约20s，3次退避代码按`min(max, exponential×jitter)`得到约0.7到1.9s，加ownerSQL及调度。**不是整HTTP请求21.9s硬上限**。
- WeLink：HTTP/阻塞等待名义最多约20到20.4s，加auth与DB；超时可能已经送达，不建议无条件重复投递。
- Relay query：Upgrade≤10s，config≤10s，业务≤30m，外加前置准备；连接5s通常包含在Upgrade过程。任意入站维持90s活性，检测不是精确90s。
- DomainAgent：默认查询idle300s和total900s，取消120s。若只覆盖环境变量`FINANCEEX_DOMAIN_AGENT_TIMEOUT`，嵌套YAML回退会同时改变两个stream默认值；必须检查实际环境展开结果。

配置依据：[application.yml](../../../src/main/resources/application.yml) L145/L355/L429。实现：[FinEurekaIntentService](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentService.java) L103/L151、[FinEurekaIntentStreamClient](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentStreamClient.java) L139/L177/L311、[FinEurekaIntentCandidateProvider](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentCandidateProvider.java) L67/L215、[HttpUseCaseLibraryClient](../../../src/main/java/com/huawei/it/ex/one/infrastructure/usecase/HttpUseCaseLibraryClient.java) L54。

## D3. 数据库、Redis与旁路等待

| 阶段/配置 | 默认预算 | 队列、重试和范围 |
|---|---|---|
| JDBC借用 `spring.datasource.hikari.connection-timeout` | 500ms | 同池10；不约束SQL/事务内Java等待 |
| `chat-run.external-terminal-transaction-timeout-seconds` | 10s | Run准入、终态、重命名/归档/恢复及相关外部终态服务；不包含删除，不把所有repository调用自动算进此预算 |
| 单删/批删Session | 无显式本地事务timeout | 使用普通@Transactional，最多锁100个Session；受实际数据库/驱动设置影响，不能套用上行10s，见R23 |
| `chat-run.execution-owner-query-timeout-seconds` | 2s | owner查询事务；事件追加NOWAIT冲突会立即拒绝，不等待2s消除冲突 |
| `chat-run.heartbeat-transaction-timeout-seconds` | 2s/批 | 50claims批次，所有批合计可能大于2s |
| `runtime-binding.interaction-resume-transaction-timeout-seconds` | 2s | 固定专家/相关Binding保护；部分分支仍含Redis操作，见R07 |
| `domain-agent.binding-compensation-*` | TX2s，2attempts，50ms间隔 | 专用controlScheduler；排队和崩溃不受事务保护；已activation但Runtime未订阅时，完成/失败/取消清理均可能补偿 |
| `session-search.database-query-timeout-seconds` | 2s，合法1到30 | 有keyword的count/page共享预算；最后Run批量摘要独立只读事务失败null；不包括所有历史/enrichment |
| 短期记忆 `memory.short-term.storage.database-query-timeout-seconds` | 2s | 默认memory关闭；cache失败回DB，backoff30s；数据库必需默认true |
| `route-memory.read-timeout` | 300ms | 独立read1到2/queue64，breaker5次/open30s，失败空上下文；Reactive截止不等于JDBC已经停止 |
| Preference读取 | 复用RouteMemory参数300ms及线程数 | 自己的线程/熔断器，非共享实例；底层查询无统一JDBC时限 |
| 旧Preference写入 | write1/queue1000 | 无feedback式排队期限；没有独立事务/SQL deadline保证整次写入 |
| Feedback GET/POST | 排队500ms，事务2s | queue16、worker1；排队过期原子取消，开始后只受事务/DB配置 |
| History feedback批量 | read-only TX2s | 每200runIds一批，整次共享2s；失败省略反馈 |
| `redis.timeout/connect-timeout` | 各500ms | Lettuce command/TCP；一次业务可能多条get/put/evict，不能称业务Redis总耗时500ms |
| Redis发布 | retry-attempts2，backoff20ms | 最多3次command，再有限状态的recovery marker重试；队列等待在command timeout外 |
| 启动首Event | `chat-run.first-event-timeout=30s` | 覆盖启动结果等待；补偿2次重试250ms退避上限1s；不能防止所有已发生副作用 |
| Servlet/WS | MVC async30m，WSsend10s、idle10m | 与网关、浏览器及下游超时独立；idle检查60s |

`financeex.integration-auth.enabled`默认false。启用后SgovTokenResolver为企业扩展；仓库默认resolver不提供真实token。不能声称已验证token刷新频率、缓存命中、网络超时或中断响应，需企业实现补充证据。

## D4. SQL增长、事务和锁顺序

| 链路 | SQL增长/索引 | 锁和提交后动作 |
|---|---|---|
| Run准入 | Session点查/行锁，active查询，按模式消息/Run写入 | Session锁保护消息树、scope和准入；原子active索引兜底；缓存多数在提交后 |
| 准入后的执行初始化 | Execution创建，再单独提交run.started | 不与准入组成同一事务；开始事件提交后才能路由，初始化失败走独立收口 |
| 标题旁路 TT05-TT18 | 候选最多3次仓储Q，但读取行数随路径H增长；提交最多2Q+1W | 准入后调度，HTTP与提交非同TX；标题TX2s竞争同Session锁，不touch排序时间；生成8许可不覆盖全部DB操作 |
| Event批次 | 上下文与owner检查 + sequence分配 + INSERT，不能只数INSERT | Run FOR SHARE NOWAIT及fencing；批次大小减少往返，不减少行数 |
| completed/WAIT终态 | Session锁、owner/终态CAS、Event、assistant、Parts、Binding、Interaction/Execution | 同事务回滚；提交后cache、RouteMemory和publish有崩溃窗口 |
| failed/cancelled终态 | owner终态管线的terminal-only不保存assistant/Parts；外部Stop/Watchdog按是否partial选择消息锁/保存 | 不可套用completed的全部步骤；外部终态另有Run CAS及条件partial原子提交 |
| candidate回放 | 有界32条/256KiB，最后marker持久化ACK | 路由更新等ACK，避免回放SHARE NOWAIT与路由UPDATE竞争 |
| route-switch DomainAgent ALLOW | owner/fencing与取消A创建B同2s短事务 | stop先提交则无Binding写；切换先提交则B可保留，后置guard仍保护Runtime |
| async callback | 容量在锁前检查；Event≤128业务、Parts分批 | Session及ASYNC_WAITING/lease CAS；正文/Parts/终态同TX10s |
| delete ≤100 | owner检查、稳定Session锁、active DB直查、软删/Binding/Interaction/share更新 | 删除TX内不Redis，但无显式TX期限；提交后清缓存/stop。批次越大，锁/连接占用越久，R23 |
| history/search | owner过滤及IN批量、版本递归、ILIKE窗口 | 复合索引需DBA迁移；部分SQL无本地deadline，数据量与锁等待待真实DB验证 |
| Watchdog | 候选扫描、claim、恢复/失败CAS | 扫描/claim并非全有TXdeadline；single-flight可能被慢claim阻住，R13 |

没有发现可据静态分析直接证明“所有路径绝无死锁”的依据。已确认部分NOWAIT冲突是立即拒绝，不是永久等待；DB行锁等待、JVM同步/队列阻塞、Redis Java等待应分别计量。真实openGauss锁矩阵、事务提交失败、锁超时异常映射仍须故障注入。

## D5. 缓存与交付不能被误读

- Session删除已改DB-only事务，不应再将旧的批删事务内Redis问题列作当前缺陷。
- 但`createInteractionRunning`和固定专家Binding解析仍会事务内同步写Redis，锁持有可超过SQL执行时间。
- Layered消息仓储afterCommit缓存回调不持未提交的行锁，但若同步执行，会延后事务资源清理/连接归还，应单独测量。
- 默认live-source为redis-only；实现也处理本机投递/回声去重，不能简单推论Redis断开时所有本机事件必然消失。跨实例live交付仍依赖Pub/Sub，失败不提供持久可靠队列。
- 缓存过期/故障会回源数据库；所有实例同时回源可能把Redis故障变成DB雪崩。10分钟技能缓存无同key single-flight保护。
- Redis listener接收Executor与publisher executor不是同一个：前者当前为库默认无界SimpleAsyncTaskExecutor，后者2到8有界，见R09。
