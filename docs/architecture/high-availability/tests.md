# 高可用测试用例与证据规范

基线：`00abae4f80b7e7e5b4d0ddca707035f1878a8ec8`，设计日期：2026-09-18。本文是待执行的测试规格，不是执行报告，不代表业务加固已经完成。实际执行结果见[当前验证证据](evidence.md)，其他基线的实验不继承为当前通过。R01–R35 对应[当前风险复核](risks.md#risk-register)，其中R26/R27补充 CPU 与部署验证，R28–R35按用户确认的四服务部署拓扑补充平台与区域容灾验证。所有新用例结果初始为 **NOT_RUN**，本轮已有单元测试的执行结果由主交付验证记录单独列出，不替代这些新故障用例。

## 1. 执行约定

### 环境、容量与停止条件

- L1：已有单元/编排测试及后续增加的故障测试；测试仓储证明程序行为，不能证明真实锁、驱动取消或网络故障。
- L2：隔离环境使用与生产相同版本、表存储类型和索引的 openGauss，以及相同拓扑的 Redis Cluster、真实 JDBC/Redis 客户端。PostgreSQL/standalone 仅用于开发反馈。
- L3：两台以上应用实例，独立 instance-id，经真实测试ALB路由；真实测试 Relay/Tool/DomainAgent、存储和鉴权环境。迟到 Stop、下游幂等、TLS与发布必须在相应真实集成上验收。Jalor相关身份/鉴权行为按实际集成验证，不再将其假设为本部署的路由网关。
- L4：T28–T35使用四服务及双Region隔离预演环境；每Region独立ADS控制面/运行面和ALB、至少两AZ，上层GSLB/DNS主备，跨Region预部署热备，DB异步复制、业务单写。前端主源WCM，独立非ADS静态托管源及对象存储预发布为备用；路由与拓扑见[部署容灾方案](deployment.md)。这些是已确认的架构约束，实际切换命令、源站接入、隔离能力和全部数据复制行为仍须现场验收。
- 全部数据使用专用租户、用户、对象前缀与可删除的测试会话。注入仅对隔离实例和依赖；代理规则必须精确到目标地址/端口，禁止在共享生产环境执行。
- 每例先运行 5 分钟无故障基线，再按用例注入，撤销后观察至少 10 分钟；此时间是测试窗口，**不是已承诺的恢复 SLO**。长周期例外另列。常规注入 TTL 默认 60 秒，最长 120 秒；程序屏障必须具超时并在 `finally` 释放。Region接管的写权/外呼隔离属于保护屏障，不得随故障注入TTL自动解除；T35/D12超时停止扩流并保持当前安全单写状态，不自动恢复旧主。
- 通用阶梯：1、4、8 个并发会话，每级 2 分钟。只有前一级满足资源门槛才扩到本例要求的更高档位；不能为达到配置上限而跨过停止线。生成器记录请求速率、实际在途数和拒绝数，拒绝不能当成成功吞吐。
- 始终保留观察流：另一个租户每 10 秒一次普通聊天、每 30 秒一次显式 Stop，观察 heartbeat、Watchdog、反馈和状态读取。以“主链路仍有明确结果”验证隔离，不能只观察被注入的请求。

执行前由应用、DBA、SRE和测试负责人填写并归档下列参数。任何必填值缺失时，相关容量/恢复验收为 **BLOCKED**，不得填 PASS；可先执行不依赖该值的功能断言。

| 参数 | 填值方式与用途 |
|---|---|
| `B_heap/B_rss/B_direct/B_threads/B_fd/B_disk` | 环境安全上限，依据容器/JVM/宿主配额减去观察及回滚余量；不是应用已有配置 |
| `B_queue_events/B_queue_bytes/B_body_bytes` | 加固设计确定的队列、正文上限；当前不存在的上限记录为缺口，不能伪装已经可配 |
| `B_db/B_control/B_replay` | 全实例 DB 总连接预算、治理预留与恢复并发目标；记录作用域和实例数 |
| `D_request/D_stop/D_release/D_recovery` | 请求总期限、取消收口、资源释放、服务及遗留任务恢复验收目标，分别由对应负责人签认 |
| `S_accept/S_first/S_complete/S_resume` | 受理、首事件、最终结果、恢复服务目标及统计窗口，分别统计业务拒绝与系统过载拒绝 |
| `Δsteady` | 稳态负载下允许的测量噪声区间，用于判断恢复后 heap/RSS/线程/FD/积压是否持续增长 |
| `B_db_all/B_redis_all/B_service[i]` | 四服务×最大计划副本及滚动发布重叠副本的连接/CPU/内存预算之和、治理/管理员余量及各服务限额；计入热备探测、复制和切换恢复峰值 |
| `D_static/D_entry/D_az/D_region/D_reconcile` | 静态页面、入口、AZ、Region业务恢复与遗留任务核对期限，分别签认；Region总预算覆盖隔离、数据提升、四服务检查和DNS实际生效 |
| `RPO_db/RPO_config/RPO_object` | 异步复制DB事实、Admin映射配置及对象/静态版本的实际允许损失窗口；先定义一致恢复点，再测标记集差异，不能默认RPO=0 |
| `C_cutover/C_failback/D_dns` | 接管/回切阶段最大等待与停写窗口、DNS TTL及实际缓存收敛容忍期限；给出超时后的安全停留状态和执行人 |

资源超过任一安全上限、观察流出现重复终态/数据不一致、控制链路超过签认期限或注入无法自动撤销时：停止增加负载并撤销注入，执行[对应运行册](operations.md)。安全停止属于有效失败证据，不要求实际触发 OOM。

### 公共断言与证据

每例同时记录 `commit/response/publish/consume` 时间线、run/session/execution/interaction 的关联标识、HTTP状态和业务错误码、事件 seq、下游命令/调用次数，以及 heap/RSS、分配率/GC、CPU、平台/虚拟线程、FD、JDBC pending/active/锁等待、队列长度/字节和许可。尚未暴露的指标通过测试探针、线程/JFR采样和数据库会话视图观测，监控缺口另外登记，不伪称已有指标接口。

正确性默认要求：一个逻辑 Run 最多一个最终终态；同一 Session 不出现两个有效活动 Run；失去 owner/fencing 的执行不再提交；恢复点内已持久化事件补读保序去重且不漏；HTTP/SSE断开本身不能被错误当成用户 Stop。Stop与自然完成竞争时以赢得原子提交的一方为准，不能要求所有Stop最终都是CANCELLED；WAIT交互停止按其独立状态合同验收。FULL 在同一完整事实库上可补读已持久化结果，跨Region异步复制切换时受实际恢复点/RPO限制，不能把尚未复制的已提交数据称为已恢复；no-store 不承诺补回未持久化正文，也不得为测试而偷偷落库正文。Runtime不具备可靠执行接管能力；历史恢复与执行重跑分开验收，Tool/Relay/DomainAgent执行结果未知时不得盲目重跑。

用例证据目录建议为 `artifacts/ha/<baseline>/<environment>/<Txx>/<run-id>/`（执行时创建，按项目证据存储策略归档，不在本文生成虚假文件），包含配置脱敏快照、负载清单、注入/撤销记录、断言结果、资源时序、SQL/线程采样与关联状态快照。日志不含 token、Cookie、完整问题/回答或真实文档内容。每例记录 `PASS/FAIL/BLOCKED/NOT_RUN`、环境版本、开始结束时间、实际峰值、实际恢复时间及负责人；PASS 只覆盖该配置和规模。

## 2. 风险逐项用例

<a id="t01"></a>
### T01 — 流桥接和累计正文（R01；L1→L2；RB01 / D01）

- **前置/负载**：Relay、DomainAgent各测一次；FULL/no-store分别运行。按 1/4/8 Run，每流 10/100/1000 帧每秒、每帧 1 KiB 逐级增加；另测每帧接近协议上限及长正文，始终受安全停止线约束。源码：[Relay桥接](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java)、[DomainAgent桥接](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/domainagent/ConfiguredDomainAgentClient.java)。
- **注入/步骤**：测试存储 adapter 在事件持久化点延迟 200 ms，后续完全阻塞 30 秒；下游继续发帧。先完整结束，再分别客户端断开、Stop、下游异常、总期限到期；另在最大累计正文档位结束。
- **断言**：记录当前 BUFFER 堆积速度，加固后队列事件数/字节和累计正文均不超预算；溢出明确失败且只一次终态，不静默丢正文或控制帧；取消后 socket/timer/订阅/permit 在 `D_release` 内释放。观察流首事件和治理满足目标。
- **清理/证据**：释放阻塞器、停止生成器、显式 Stop 遗留测试 Run，核对全部终态；保存堆趋势、JFR分配热点、源/库/客户端事件清单。当前无界实现不预置“测试会通过”。

<a id="t02"></a>
### T02 — 混合负载下共享数据库预算（R02；L2→L3；RB03 / D02）

- **前置/负载**：记录 Hikari 实际值（源码默认 maximum=10、acquire=500 ms）；两个租户按 1/4/8 活动 Run，叠加反馈、候选、恢复、搜索各 2 并发；两实例重复。源码：[准入](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/RunAdmissionControlService.java)。
- **注入/步骤**：先保留 1 个测试事务持连接 60 秒，再逐次增加至剩余连接低于治理预算；制造一个高流量租户，保持另一租户观察流。最后移除持连接事务，停止辅助突发。另在L1可信身份fixture中做租户基数100/1000/10000逐档的短Run：当前`tenantSemaphores`未见淘汰，区分已定时清理的用户窗口与租户map；检测条件性长期内存增长，不因此直接宣称已OOM，不通过伪造生产身份头创建租户。
- **断言**：借用等待有界、busy显式可见、拒绝未变成错误成功；Stop/heartbeat仍满足 `B_control/D_stop`；DB恢复后新Run可受理，无连接/许可泄漏。按实际租户倾斜验证本机配额，不能当作集群总配额。
- **清理/证据**：回滚持连接事务；记录池等待、DB总连接、各类吞吐/拒绝/长尾及残留Run；禁止仅扩大池来使测试“通过”。

<a id="t03"></a>
### T03 — 长历史及并发恢复（R03；L2→L3；RB06 / D05）

- **前置/负载**：FULL Run分别生成 1千/1万/10万事件，正文 1 KiB 与近上限两档分开测；独立测试 session SSE、run Resume与WS。每用户1/4/8连接后再以多用户增长至 `B_replay`，另加一次超限请求。源码：[恢复服务](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatStreamApplicationService.java)。
- **注入/步骤**：同时从 `afterSeq=0` 补读并保持新事件输出；在历史/live交接处迟到一帧，再断网30秒，恢复时模拟多页签刷新。校验取消中的补读释放资源。
- **断言**：分页/配额加固后单查询行数、回放字节与衔接缓冲有界；已持久化 seq 无缺口/重复应用，历史完成不会提前关闭应继续的实时流。超限恢复快速拒绝，主Run/Stop不受挤占。
- **清理/证据**：关闭全部订阅并核对注册数、DB查询行数、live buffer及FD回落；记录当前全量 List 实现的实际成本，不用WS8连接限额替代HTTP独立恢复限额。

<a id="t04"></a>
### T04 — 提交、广播及消费间崩溃（R04；L1→L3；RB04、RB09 / D03、D05）

- **前置/负载**：两实例，同Run观察端连B，执行端A；FULL/no-store分别各20轮，覆盖正常完成与异步回调完成。源码：[完成协调](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunCompletionCoordinator.java)。
- **注入/步骤**：用测试屏障依次定位 terminal commit前、commit后publish前、publish后consume前；每次只终止A一个实例，恢复至B。不能靠随机sleep宣称命中窗口；无法挂屏障的真实环境须用日志证明时点，否则记未覆盖。
- **断言**：commit前不能留下半个终态；commit后FULL状态与历史正确，客户端受控补读；no-store明确结果不可恢复，状态可对账且正文未持久化。广播可丢的事实不能被误判为DB回滚。
- **清理/证据**：启动A恢复副本数、清理屏障与测试记录；保存 kill时点、事务证据、最后seq与客户可见结果，并单独统计状态恢复/正文缺口。

<a id="t05"></a>
### T05 — Relay迟到Stop隔离（R05；L1→L3；RB05 / D04）

- **前置/负载**：真实测试Relay，两应用实例，同一runtimeSessionId顺序执行A、B；分别覆盖Intent动态选中专家、固定专家、聚合应用子专家三种来源，每种重复20次。当前各DomainExpert来源完成后均可保留ACTIVE Binding，不能只测固定专家；维持用户要求的session续接语义。源码：[Stop协调](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStopCoordinator.java)。
- **注入/步骤**：专用代理或Relay测试钩子延迟A的`stop_all_agents` 15秒（超过默认5秒ACK等待），分别在B首帧前后交付；再测Stop失败、重排、重复送达。若TLS代理无法识别命令，必须由Relay提供钩子，不能用全链路延迟冒充命令重排。
- **断言**：A本地有界收口且最多一终态；B不被A的迟到Stop停止；下游记录代次/有序屏障证据。ChatService mock通过不能关闭该联合风险；无远端隔离保证记BLOCKED。
- **清理/证据**：撤销命令延迟、核对Relay session中活跃agent、关闭测试session；保留双方命令时间线及B完整结果。不得默默改为新session绕过验收。

<a id="t06"></a>
### T06 — 鉴权和重试总期限（R06；L1→L3；RB05 / D04）

- **前置/负载**：分别启用BLOCKING/STREAMING Intent、用例库、WeLink；记录实际最大重试及鉴权实现，候选独立测。并发1/4/8，使用可释放的token resolver测试替身。源码：[Intent调用](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentService.java)。
- **注入/步骤**：鉴权阻塞60秒；随后分别返回401、429含Retry-After、503、连接失败、首帧后断流。每种故障独立60秒，记录从入队到最后底层任务结束的时间，不只看HTTP返回。
- **断言**：加固后整个逻辑请求不超过 `D_request`、非瞬态不重试、瞬态有退避且尝试次数符合预算；timeout/cancel后鉴权/HTTP线程与连接按 `D_release` 归还。流式已有5秒鉴权期限须保留，不将它误报缺失。
- **清理/证据**：释放resolver、撤销代理错误并等待底层任务退出；保存attempt/auth调用次数、队列等待与线程栈，区分“响应结束”和“任务真实结束”。

<a id="t07"></a>
### T07 — 缓存慢是否延长事务（R07；L1→L2；RB03、RB04 / D02、D03）

- **前置/负载**：Interaction兼容创建、固定专家Binding刷新各4并发；同时运行标准准入作为已有提交后路径对照。源码：[Interaction生命周期](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/InteractionRunLifecycle.java)、[Binding服务](../../../src/main/java/com/huawei/it/ex/one/application/service/runtime/RuntimeBindingApplicationService.java)。
- **注入/步骤**：分别阻塞Redis get/put/evict 30秒，在程序测试层记录事务active与commit回调；真实DB通过第二事务争取相同Session/Run锁观察占用，再测DB回滚。
- **断言**：加固后DB保护事务不调用Redis；提交后缓存失败不回滚事实，DB回滚不能对外暴露新缓存；缓存操作期间连接/锁释放符合设计。不得把afterCommit仍占连接和未提交持锁混成同一结论。
- **清理/证据**：释放Redis阻塞，按DB事实校验测试key并使用应用路径重建；保留TX开始/提交/连接归还时间、锁等待、缓存调用顺序。

<a id="t08"></a>
### T08 — 未知响应与重复提交（R08；L1→L3；RB09 / D08）

- **前置/负载**：NEXT、创建会话、分享创建、候选切换分别单测；每路径20组双请求，区分已幂等Intent反馈对照。源码：[启动协调](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStartCoordinator.java)。
- **注入/步骤**：服务已提交后丢弃HTTP响应30秒；先记录当前盲重试行为，再按加固协议“状态查询→确认已有结果→决定重试”。同请求并发落到不同实例，另测相同键但不同内容（仅新协议支持时）。
- **断言**：当前缺少幂等的路径记录重复风险；整改验收要求已受理请求被正确识别，无多余消息/新Run/分享；协议冲突不会返回另一个请求的结果。查询未能确认时保持未知，不把网络超时当成失败再执行。
- **清理/证据**：撤销丢响应规则，核对各逻辑请求产生的实体和外部调用数；删除测试实体，保留请求摘要、响应、DB事实及幂等兼容结论。

<a id="t09"></a>
### T09 — Redis接收线程与消息风暴（R09；L1→L3；RB01、RB04 / D01、D03）

- **前置/负载**：真实Redis Cluster，发布100/1000/5000条每秒，每条1 KiB，1/16/128 topic阶梯；FULL/no-store各跑一次。源码：[实时总线](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java)。
- **注入/步骤**：测试handler每条延迟100 ms并保持60秒，随后解除并触发一次订阅重建；线程增长达到安全线立即撤销，禁止刻意耗尽native memory。
- **断言**：加固后listener平台线程与队列不超明确上限，拒绝可观测且不会在Netty线程执行慢处理；顺序错误/拒绝触发恢复，FULL可补读；no-store不能虚称补回。publisher有界不能替代listener验收。
- **清理/证据**：停止publisher和handler延迟、核对订阅清理及线程回落；采集平台线程数、栈、队列拒绝、seq缺口和恢复次数。

<a id="t10"></a>
### T10 — 旧偏好/旁路排队及迟到JDBC（R10；L1→L2；RB03 / D08）

- **前置/负载**：旧偏好写入口write=1、queue=1000按当前配置；先5个请求制造“未满但很旧”的队列，再32/128阶梯；新反馈1+16/500ms作为对照。源码：[旧偏好写](../../../src/main/java/com/huawei/it/ex/one/application/service/routing/IntentPreferenceCorrectionApplicationService.java)。
- **注入/步骤**：阻塞首worker 30秒；提交后取消第二个调用，再放开；旧读JDBC延迟超过调用方300ms直至30秒。识别记录/记忆以相同方法逐池复核，启用与关闭分别记录。
- **断言**：加固后过期未执行任务不迟到访问DB；已执行写的未知结果可查询，不用Reactive timeout谎称回滚；读失败开放后底层SQL/连接仍在期限内释放，主Run不被旧任务拖垮。
- **清理/证据**：解除worker和SQL阻塞，检查测试偏好/记录实际写入次数，记录enqueue/start/expire/commit以及连接归还时点。

<a id="t11"></a>
### T11 — 长历史查询、全树和搜索（R11；L2；RB02、RB03 / D08）

- **前置/负载**：每用户200会话，单会话1千/1万/10万节点逐档，构造深版本链、大assistant及metadata；查询并发1/4/8。源码：[消息SQL](../../../src/main/resources/mapper/memory/ChatMessageMapper.opengauss.xml)。
- **注入/步骤**：1字符/128码点关键词、无命中/大量命中、消息树、variants和会话列表分别执行；冷缓存与热缓存对照，各2分钟。另叠加数据库慢读60秒，禁止对生产数据执行高成本分析。
- **断言**：查询超时显式失败不冒充空结果；最后Run可省略字段按协议验证；加固后读取行数/响应字节/SQL时间受预算，排序spill、递归成本可解释，观察流无持续CPU/DB饥饿。
- **清理/证据**：停止负载并删除专用数据；记录查询计划、读行数、临时磁盘、JSON分配、P95/P99及返回正确性。索引存在不等于contains查询可安全无限扩大。

<a id="t12"></a>
### T12 — 文档在途资源与孤儿对象（R12；L1→L3；RB07 / D06）

- **前置/负载**：API Store、对象存储分别测，1/8/32并发、1/10/50 MiB逐档；慢下载每连接16 KiB/s，超过32个持续流的场景必须先通过安全门槛。源码：[API上传](../../../src/main/java/com/huawei/it/ex/one/infrastructure/storage/api/ApiStoreDocumentStorage.java)、[下载入口](../../../src/main/java/com/huawei/it/ex/one/interfaces/document/DocumentController.java)。
- **注入/步骤**：上传断流、存储延迟60秒、存储成功后DB登记失败分别测试；下载得到InputStream后挂起30秒，再主动取消客户端；仅填充专用临时卷验证磁盘不足。
- **断言**：加固后上传在途字节与临时磁盘有界；下载许可直到关闭/取消才归还，FD和存储连接按 `D_release` 回落；失败对象有操作标识可对账并清理。OBS流式上传不能套用API Store整份数组结论。
- **清理/证据**：关闭客户端/流、释放专用卷填充物，按测试前缀比对对象与文档记录，清理孤儿前保留清单及checksum；记录heap/direct/FD/socket/permit峰值。

<a id="t13"></a>
### T13 — 治理扫描及claim预算（R13；L1→L3；RB03 / D02、D05）

- **前置/负载**：两实例，制造30个过期execution、30个初始化孤儿、30个RESPONDING孤儿和30个过期async任务，分两租户；记录默认scan30秒、batch100、maxClaims20、recovery4及tenant5。源码：[恢复编排](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunRecoveryOrchestrator.java)。
- **注入/步骤**：锁住待claim行60秒，分别让每个治理分支遇到慢SQL；记录尝试、成功、耗时及single-flight。解除后观察至少两个完整scan周期。
- **断言**：加固后各分支共享尝试数/时间预算，不能只计成功；慢claim有DB期限，下一轮可继续，permit/guard释放；同一条记录只被有效owner提交，租户之间有预算隔离。
- **清理/证据**：回滚锁事务，核对孤儿与异步最终状态，删除测试记录；保存scan开始/结束、分支计数、锁等待和owner变更记录。默认90秒lease不能当实际RTO。

<a id="t14"></a>
### T14 — CANCELLING及删除后调度丢失（R14；L1→L3；RB09 / D05）

- **前置/负载**：实例A执行有心跳但无业务帧的quiet Run，实例B接收Stop；20轮。另测删除commit后Stop调度。源码：[Stop协调](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStopCoordinator.java)。
- **注入/步骤**：B提交CANCELLING后、提交execution终态前停止B；A继续健康续lease；删除场景在commit与best-effort stop之间停止B。使用屏障确认位置。
- **断言**：当前记录是否长期CANCELLING/已删除仍active；整改后即使owner持续续lease也在 `D_stop` 内收口，旧owner后续帧不提交；与自然完成和回调竞争只产生一终态。
- **清理/证据**：恢复B，核对远端停止状态；对仍活动Run经支持接口显式Stop，不能直接篡改数据库终态；记录lease时间线、Run年龄、删除状态及下游副作用。

<a id="t15"></a>
### T15 — 性能索引部署与失败残留（R15；L2；RB03、RB08 / D07）

- **前置/负载**：两个可抛弃的同版本DB副本，一份完整索引，一份只缺测试目标性能索引；保留一致性约束。源码：[最后Run索引脚本](../../../src/main/resources/db/incremental-20260826-chat-run-last-status-index.sql)。
- **注入/步骤**：在副本执行T11负载，比较计划/时延；由DBA在隔离副本演练脚本重复执行、索引创建中止及残留清理，单次操作超过120秒则停止并记录。启动应用观察是否能识别该性能索引缺失。
- **断言**：发布前检查明确索引名称、定义与有效状态，应用能启动不能代表索引齐全；DDL执行方式符合目标DB版本，不假设可在事务内并发建索引。回滚jar不得破坏新旧查询兼容性。
- **清理/证据**：销毁测试副本或按DBA脚本恢复索引；保存DDL输出、系统目录快照、EXPLAIN与业务校验。不可为演练删除生产唯一约束。

<a id="t16"></a>
### T16 — 技能缓存击穿与线程切换（R16；L1→L3；RB02、RB05 / D04、D08）

- **前置/负载**：同skill并发1/8/32，覆盖cache on/off × 留存控制on/off × 有/无附件；记录其有效启用条件。源码：[技能配置](../../../src/main/java/com/huawei/it/ex/one/application/service/domainagentconfig/DomainAgentSkillConfigurationService.java)、[派发协调](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRuntimeDispatchCoordinator.java)。
- **注入/步骤**：清理仅测试key，Provider延迟1秒/超时；在cache off、留存on、无附件/deferred Binding组合记录persistResolvedRoute线程，并让仓储阻塞30秒。
- **断言**：加固后同key查询合并且快照每调用一致，失败不会留下永久inflight；阻塞JDBC运行于指定IO调度器，Netty event-loop不被占用；附件fail-open与留存策略按既有协议分别断言。
- **清理/证据**：释放仓储阻塞、恢复Provider、仅清理测试cache key；保存实际线程名/栈、并发远端次数与cache hit/miss，不能推广为所有默认请求都会网络线程JDBC。

<a id="t17"></a>
### T17 — 删除后的恢复访问（R17；L1→L3；RB09 / D05、D08）

- **前置/负载**：本人、同租户其他人、跨租户三种身份；FULL及no-store各一会话，删除前已有WS订阅和可恢复历史。源码：[恢复访问检查](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatStreamApplicationService.java)。
- **注入/步骤**：删除commit后立即请求session Resume、run Resume、WS新订阅，并观察已有订阅；并发删除与恢复重复20次。
- **断言**：整改后的访问政策统一，删除后不能通过恢复入口读出本应不可访问的数据；已有订阅按确定政策关闭/停止推送；跨用户/租户继续拒绝。当前发现定位为本人删除语义，不能宣称已发生跨租户泄露。
- **清理/证据**：关闭订阅，保留匿名化HTTP业务码、帧和删除commit先后；撤销测试权限与会话，核对连接注册数回落。

<a id="t18"></a>
### T18 — 分支复制及分享删除竞态（R18；L1→L2；RB09 / D08）

- **前置/负载**：含附件和多版本的100/1000节点祖先链，单条/选中分享分别测；两实例并发20轮。源码：[会话服务](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionApplicationService.java)、[分享服务](../../../src/main/java/com/huawei/it/ex/one/application/service/share/ChatShareApplicationService.java)。
- **注入/步骤**：复制第1/中间/最后节点时注入写失败；分享读取Session后暂停，删除/撤销提交后放行分享INSERT，反向次序另测；屏障最长30秒。
- **断言**：加固后分支要么完整可见、要么整体失败或明确未完成且不可使用；已删除Session不能产生遗漏撤销的新分享；事务期限与最大复制规模一致，权限和快照范围不扩大。
- **清理/证据**：释放屏障，核对新会话完整性及分享可访问状态，清理测试实体；保存事务/锁顺序和失败点清单。

<a id="t19"></a>
### T19 — WeLink未知结果与重复投递（R19；L1→L3；RB09 / D08）

- **前置/负载**：仅测试WeLink收件人，启用provider；单消息新分享投递、既有分享投递各20次。源码：[WeLink Provider](../../../src/main/java/com/huawei/it/ex/one/infrastructure/share/WelinkChatShareDeliveryProvider.java)。
- **注入/步骤**：远端确认收到后丢掉响应，持续超过配置5秒；另在远端成功后令本地投递记录写失败；统计默认重试造成的实际发送次数。
- **断言**：有远端幂等协议时固定deliveryId只产生一次副作用；无协议时按未知状态查询/人工对账，不自动重发。401等非瞬态不重试；本地记录缺失不代表未送达。
- **清理/证据**：恢复响应/DB，按provider查询对账；撤销本地分享不声称能召回已发送内容；保存测试收件箱数量、远端回执与本地记录映射。真实下游不支持查询/去重则明确未关闭。

<a id="t20"></a>
### T20 — 前端恢复游标及业务错误（R20；L1，生产前端另验；RB06 / D05）

- **前置/负载**：运行仓库[联调前端](../../../local-test-frontend/README.md)，使用可控WS/HTTP测试服务；生产前端需其负责人重复同场景。源码：[app.js](../../../local-test-frontend/public/app.js)。
- **注入/步骤**：先发送seq101–105，再发建议`recoveryAfterSeq=99`的RECOVER_REQUIRED并补回seq100；并行重复推送102。另返回HTTP200且body.code=ACCESS_DENIED，覆盖状态、列表及提交后读取。
- **断言**：加固后按建议较小游标补缺、按事件身份去重、订阅状态重置；UI不把业务错误当DTO、不误清会话或推进已读水位；重连有退避而非紧循环。
- **清理/证据**：关闭测试页/服务、清除测试缓存；保存网络记录、DOM状态及事件应用列表。本样例通过不能代替生产前端验收。

<a id="t21"></a>
### T21 — 文档可信字段不可被PATCH污染（R21；L1→L3；RB10 / D06）

- **前置/负载**：本人合法文档、其他用户测试文档、允许编辑的业务metadata；使用捕获附件参数的测试DomainAgent。源码：[文档服务](../../../src/main/java/com/huawei/it/ex/one/application/service/document/DocumentApplicationService.java)。
- **注入/步骤**：PATCH伪造`providerDocument.docId/url`及嵌套对象、null、整体替换后启动附件Run；URL只指向隔离接收器，不访问真实第三方；再测合法重命名。
- **断言**：加固后可信docList仅由服务端记录产生，客户端字段不能改变存储引用/下游目标；业务白名单字段正常编辑；权限错误不触发任何远端调用。
- **清理/证据**：恢复/删除测试metadata和文档，保留输入字段摘要、最终docList与接收器0/1调用记录。下游进一步越权能力必须另有证据，不能从可覆盖metadata直接推定泄露。

<a id="t22"></a>
### T22 — OBS TLS服务身份（R22；L1→L3；RB10 / D06）

- **前置/负载**：`storage.provider=huawei-s3`，专用测试endpoint及无敏感数据的小文件；正确CA/hostname、未受信自签、受信但错误hostname三套证书。源码：[OBS配置](../../../src/main/java/com/huawei/it/ex/one/infrastructure/storage/object/s3/HuaweiS3StorageConfiguration.java)。
- **注入/步骤**：每套证书执行上传、下载各3次；再更换过期证书。每次最长120秒、使用测试凭据；确认发起请求的是实际OBS SDK，而不是单独curl。
- **断言**：加固后错误证书/hostname必须失败且不传输业务内容，正确配置成功；记录当前配置和SDK实际校验行为，不能只检查URL以https开头。现有配置没有显式开启校验，测试预期需按整改前后区分。
- **清理/证据**：恢复有效证书、删除临时truststore/测试对象并撤销测试凭据；保存证书链/SAN/失败类别，不保存私钥。真实企业CA链未验证则不放行启用该provider。

<a id="t23"></a>
### T23 — 批量删除的锁与事务期限（R23；L1→L2；RB03 / D02）

- **前置/负载**：1/10/100个Session批次，数据库连接/锁期限记录完整；并发反向输入批次和一个准入请求。源码：[删除入口](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionApplicationService.java)。
- **注入/步骤**：外部事务持其中一个Session行锁60秒；分别在删除中间SQL失败、超时前后断开客户端，确认真实DB是否回滚；HTTP断开不被当作事务已取消。
- **断言**：加固后代理入口期限生效，单删和批删均有界，all-or-nothing保留，锁顺序稳定；超时后连接与所有已获取锁释放；未提交不能发送缓存清理/Stop。
- **清理/证据**：回滚外部锁事务；按状态查询确认未知提交结果，再清理测试数据；保存SQL时间、锁图、删除数量与提交后副作用次数。

<a id="t24"></a>
### T24 — 路径切换与运行并发（R24；L1→L2；RB09 / D08）

- **前置/负载**：同Session两个版本leaf、可继续Run；本轮采用保守合同：运行中允许浏览历史，真正切换active path必须在Session锁内重验无活动Run。若产品以后要求运行中改变active path，须单独评审协议及并发语义，不改变本轮默认验收。源码：[selectPath](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionApplicationService.java)。
- **注入/步骤**：path与NEXT准入、自然完成、候选切换、删除分别以两个事务屏障双向交错20轮，每屏障最长30秒；最后读取leaf、消息父子关系及候选source。
- **断言**：锁后存在活动Run则拒绝active path切换，历史浏览不修改leaf；无活动Run时按锁内最新状态原子更新。失败不改变leaf，终态不覆盖较新的合法路径；人工标题/专家scope/nodeOrder保持各自语义，不把leaf竞态夸大为全字段覆盖。
- **清理/证据**：释放屏障、终止遗留Run、清理会话；保存操作顺序、冲突响应及最终路径图，并回归旧客户端收到冲突后的刷新行为。

<a id="t25"></a>
### T25 — 标题完整生命周期（R25；L1→L3；RB03、RB05 / D08）

- **前置/负载**：标题关闭/开启对照；开启时显式timeout与鉴权；100/1000节点路径，1/8/32个不同Session以及同Session重复调度。源码：[标题服务](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionTitleApplicationService.java)。
- **注入/步骤**：分别阻塞候选查询30秒、token60秒、提交Session锁30秒；生成后到提交前终止一个实例；人工改名与旧生成结果晚到交错。每种单独复位。
- **断言**：候选行数/Run ID数、排队时间受完整任务预算；不能用8个生成许可证明候选阶段有界；提交TX2秒与人工标题/nodeOrder检查保留，Run首事件满足目标。当前生成后丢任务可保留原标题，不承诺可靠补跑。
- **清理/证据**：释放锁/token、恢复测试实例、核对任务排空；保存queued/generated/applied/skipped、candidate_rows、Hikari pending及标题前后值，区分建议指标与现有日志。

<a id="t26"></a>
### T26 — CPU、JSON与GC条件性风险（R26；兼验R01/R09/R11/R12/R25；L1→L2；RB02 / D01）

- **前置/负载**：JDK21/JVM配额固定；Relay接近1 MiB、DomainAgent接近256 KiB帧，嵌套JSON深度8/32/128逐档，以及大量小delta。每档1/4/8 Run；合成内容，无真实敏感数据。检查点：[协议映射](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/domainagent/ConfiguredDomainAgentClient.java)及事件序列化链路。
- **注入/步骤**：高频合法帧运行5分钟，边界+1字节/损坏JSON各100次；使用JFR采样30秒区分解析、复制、脱敏、日志、GC和锁热点。通过安全上限后才能运行4小时稳态，最后停止输入观察30分钟。
- **断言**：恶意/异常形状在有界成本内拒绝，不能出现持续忙循环或无限重试；合法输入资源随并发可解释且不持续累积，释放后回落 `Δsteady`。不能仅凭CPU高判定死循环，更不能在未profile时声称已存在特定热点。
- **清理/证据**：停生成器，核对任务/线程退出，保存JFR、CPU节流、分配火焰图/堆趋势与输入形状；记录首次超界点作为容量证据，不把该点写成安全容量。

<a id="t27"></a>
### T27 — 探针、网关、发布与恢复（R27；兼验R02/R04/R13/R14/R15；L3；RB08 / D07）

- **前置/负载**：两实例真实测试部署、ALB真实策略及启动参数归档，准备可回滚应用包与配置；持续Run、quiet WS、HTTP Resume、异步任务各一组。源码：[应用配置](../../../src/main/resources/application.yml)，当前DB health默认关闭、Redis health关闭，未在本仓声明完整readiness/摘流协议。跨四服务/AZ/Region场景进一步执行T28–T35，不以本例替代。
- **注入/步骤**：先只隔离一实例的DB60秒，记录health与业务实际状态差异；复位后先摘流再正常终止一实例，另一次直接异常退出；再模拟网关idle短于业务等待，确认重连。每种故障独立执行。
- **断言**：加固后liveness不因共享依赖抖动导致全实例重启，readiness真实反映关键能力；摘流后无新Run落入被撤实例，已脱离HTTP的Run按确定退出协议处理；FULL跨实例恢复、no-store缺口说明、lease/fencing和Stop收口均达签认目标。
- **清理/证据**：撤销隔离，恢复副本数和网关路由，核对所有遗留Run/Interaction/async状态；保存发布事件、请求实际路由、探针、租约、恢复时间和旧版本兼容记录。无实际部署探针/回滚清单则本例BLOCKED。

T27 另包含以下运维子项，均为ENV / NOT_RUN；执行单须明确平台/DBA操作、目标、停止条件及撤销方式，不能从源码推定现网已经具备备份、跨域冗余或动态轮换能力。

| 子项 | 前置、步骤与范围 | 断言、清理与证据 |
|---|---|---|
| 备份与恢复 | DBA在专用源库写入三组带提交时间的已知标记：备份截点前、截点附近、截点后，包含Session/Run/Event及引用关系；使用实际部署备份方案备份，恢复到全新隔离目标并用测试应用读取。源库只读采集/备份，绝不覆盖源库。长恢复操作的任务期限由执行单单独指定，超时停止目标恢复任务 | 对比明确应包含的已提交标记、约束/索引、sequence与引用完整性；按实际恢复点计算RPO，按开始恢复到业务校验通过计算RTO，再对照签认目标，不能把备份任务成功当可恢复。no-store正文不纳入可恢复承诺；保留恢复日志/标记差集后销毁隔离目标 |
| 故障域与剩余容量 | 仅在已经确认部署存在两个以上独立故障域、依赖拓扑和预演恢复路径时，停用测试环境一个故障域的应用路由/实例；明确影响哪些实例，保留独立观察域，按25%/50%/100%已测安全负载阶梯验证，默认60秒、上限120秒，平台无法保证则BLOCKED | 剩余副本承载、DB连接总预算、控制链路、租约治理与FULL恢复满足目标；不能仅有副本数便声称跨域高可用。撤销停用并逐实例回接，核对负载分布、遗留Run和恢复后二次峰值 |
| 配置与轮换 | 在独立灰度实例分别给缺失/无效启动配置，确认拒绝启动或明确失败；以专用测试凭据实施鉴权/DB/Redis/存储的实际轮换协议，旧/新凭据过渡只采用已支持机制。证书正负例复用T22，临时卷/日志增长告警复用T12，不操作真实凭据或宿主根盘 | 无效实例不接流量且不会触发其他副本重启；轮换期间错误/恢复有界，日志无明文secret，旧凭据退出和回滚行为按协议验证。复位配置、撤销测试凭据、清理专用卷；保留配置校验输出、证书/凭据版本标识和告警发现/恢复时间 |

<a id="t28"></a>
### T28 — 四服务共享数据库的故障传播（R28；L4；W11 / RB11 / D09）

- **前置/配置/角色**：Admin/Tool/Relay/Chat负责人分别提供实际账户、schema/表、连接池/事务/锁预算及只读业务探针；DBA提供跨服务连接总预算和保留管理连接。按实际调用链经过ALB验证，不推断本仓包含另外三服务实现；DEP01/DEP02见[部署方案](deployment.md)。
- **数据/负载**：专用租户，四服务各1/4/8并发阶梯；Admin映射读写、Tool测试调用、Relay低频输出、Chat Run/Resume并行，另保留独立观察租户及四服务健康探针。规模不得超过签认的`B_service[i]`。
- **注入/步骤/时长**：每轮仅选择一个服务制造60秒慢SQL/连接占用/限定行锁，依次轮换四服务；记录同库其他服务的等待。复位后演练一次已验证的区域内DB切换，故障规则上限120秒，切换操作另列执行单期限。
- **业务断言**：来源服务的事务未知结果可核对；其他服务保持受理/控制能力或明确有界拒绝，不形成级联重试；Admin映射不会半更新，Tool/Relay不因Chat超时重复外部任务。记录每个服务实际状态合同，不借用Chat Run状态替代另外三服务状态。
- **资源/恢复断言**：所有副本连接预算合计不超过`B_db_all`，包含热备探测、治理和切换峰值；DB恢复后旧连接/锁归还，四服务按治理→基础查询→新业务→辅助批处理顺序达到签认恢复目标，不能只验Chat。
- **清理/证据**：DBA回滚已登记测试事务，撤销延迟，恢复服务配额；保留逐服务SQL/锁/连接与业务完成量、失败数、提交标记差集。应用负责人确认业务事实，DBA确认无残留锁；执行前缺少任一服务预算或管理连接保障则BLOCKED。

<a id="t29"></a>
### T29 — 四服务共享Redis的容量、丢失及恢复（R29；L4；W11 / RB11 / D09）

- **前置/配置/角色**：缓存负责人和四服务负责人完成key前缀、数据语义、TTL、淘汰策略、持久化/复制及回源路径清单。Chat将DB作为事实源，不代表Admin/Tool/Relay也仅用Redis缓存；任何其他服务持久状态职责不明时，不执行清空/重建子项。
- **数据/负载**：四服务混合1/4/8并发；仅在独立测试Redis中构造专用热点key、1 KiB/64 KiB测试值及Pub/Sub 100/1000条每秒阶梯，保持各服务读写探针。每档先证明符合`B_redis_all`。
- **注入/步骤/时长**：分别制造一个服务的热点60秒、单实例到Redis链路中断60秒、受控订阅重建；复位后由缓存管理员实施测试Cluster切主，观察全部服务。逐项记录TTL/淘汰导致缓存缺失或业务状态丢失，禁止对共享生产Redis运行FLUSH操作。
- **业务断言**：区分缓存、锁、实时事件、可能的持久任务状态；失去缓存后回源受控，不能四服务同步洪峰压DB；FULL只补恢复点内事件，no-store缺口明确；持久状态必须按其独立恢复合同核对，不能无条件重建为空。
- **资源/恢复断言**：连接、热点吞吐、内存、线程和各服务回源预算有界，key命名隔离不能替代容量隔离；恢复订阅保序/去重，积压按预算分批释放，四服务控制路径满足签认目标。
- **清理/证据**：仅清理登记的测试key/频道和客户端；恢复拓扑、配额，逐服务校验引用、任务/锁及缓存版本。保存Redis配置、故障域、淘汰/命中、回源DB负载与状态差集；缓存管理员和四服务负责人共同签认。

<a id="t30"></a>
### T30 — Admin映射版本、Tool中转和未知执行（R30；L4；W11 / RB11 / D09）

- **前置/配置/角色**：调用链固定为Chat→Tool→DomainAgent，skillId映射来源Admin；Chat直连Relay，IntentService为第三方依赖。Admin/Tool及DomainAgent负责人提供映射查询/版本、禁用与删除语义、执行ID/状态查询和实际重试合同；没有可靠去重/查询能力则保留未知结果，不能推定幂等。
- **数据/负载**：专用skillId的版本v1/v2及删除/禁用状态，两个Tool实例；每个版本1/4/8并发，另保留Chat→Relay和Intent路由观察流，单逻辑请求带可关联的测试追踪标识。
- **注入/步骤/时长**：Admin不可用60秒、一个Tool映射缓存滞后60秒、v1→v2及删除与在途请求交错各20轮；随后由测试DomainAgent确认接收后丢响应60秒，再测Tool记录执行结果失败与迟到回调。屏障最长30秒，全部独立复位。
- **业务断言**：一次调用绑定同一已确认映射快照，不能重试时切到另一目标；新调用无法取得符合策略的可信映射时有界拒绝，存量缓存可否续服按明确有效期/撤销合同验证，禁用/权限撤销不能靠陈旧缓存绕过。Tool超时不等于DomainAgent未执行；未知状态不盲重跑、不跨provider兜底重复执行。
- **资源/恢复断言**：Admin故障不形成跨Tool实例击穿，重试总预算覆盖Chat、Tool和下游而非逐层相乘；数据库/线程/连接归还达到`D_release`，恢复后只对确认未执行或具可靠幂等的请求重试。Runtime不可靠接管须明确对用户收口。
- **清理/证据**：恢复映射、撤销错误规则、关闭测试任务；保存请求→skillId/映射版本→Tool执行→DomainAgent结果关联表、真实远端执行次数和UNKNOWN清单。三方负责人联合确认未知项及处置期限，禁止用本仓adapter单元测试代替。

<a id="t31"></a>
### T31 — WCM故障与独立静态备用源（R31；L4；W12 / RB12 / D10）

- **前置/配置/角色**：前端/WCM/静态托管及ALB负责人确认独立于ADS的备用托管源、对象存储预发布产物、域名/TLS、源站接入、文根、缓存与回滚配置。主备部署同一HTML/JS/CSS/运行配置manifest及保留的上一兼容版本，预演不触碰真实用户缓存。
- **数据/负载**：全新浏览器、旧版本缓存浏览器、多个DNS缓存来源各1/4/8会话；覆盖入口页、深链接刷新、静态chunk懒加载、登录后首个API、WS建立与完整测试Run。
- **注入/步骤/时长**：只阻断测试WCM源站60秒，通过已验证的ALB源站策略转备用；另测主WCM全不可用、单chunk404、旧HTML引用旧chunk、发布中途不完整、错误静态缓存。每子项故障TTL上限120秒，源站切换与DNS收敛另记录时点。
- **业务断言**：无需重新上传构建即可从已预发布备用产物启动完整页面；不依赖故障ADS提供静态文件；API错误不能被SPA首页200遮盖，静态/API文根与Origin保持正确；旧缓存客户端仍可加载兼容chunk，版本切换不把API指向错误Region。Admin动态页面配置另验证成功/不可用表现，不能因静态加载成功就宣称动态配置已容灾。
- **资源/恢复断言**：对象读取、备用源连接和ALB请求满足签认静态容量；`D_static`从故障发生量到真实浏览器可完整运行，不以首页200/源站健康代替；备用源不能只证明对象存在而未证明可托管/接入。
- **清理/证据**：撤销故障，验证WCM恢复后由负责人受控回切静态源，避免自动来回抖动；保留HTTP状态、manifest哈希、浏览器网络/控制台、切源时间与DNS缓存观察。删除只限测试版本和对象，保留上线回滚版本。

<a id="t32"></a>
### T32 — ALB全路径路由及区域入口切换（R32；L4；W12 / RB12 / D10）

- **前置/配置/角色**：每Region独立ALB，上层GSLB/DNS主备；入口负责人提供四服务所有文根/路径、静态源、回调、WS、健康检查、重试和idle/总期限表及可回滚版本。TLS/身份转发规则来自实际合同，不能猜测默认头处理。
- **数据/负载**：路由清单每项至少一成功和一拒绝请求，四服务1/4/8并发；含深链接、查询参数、上传、SSE、quiet WS、内部callback与来源鉴权，保持主备Region实际入口观测。
- **注入/步骤/时长**：分别修改测试路由文根/重写、摘除一个后端、WS Upgrade失败、idle短于业务等待、丢弃POST响应各60秒；复位后模拟当前Region ALB不可达及两个目标都不健康，观察GSLB/DNS缓存与fail-open/兜底行为。涉及写请求跨Region落点的子项只在T35先建立合法写权后放行，不能仅改DNS就启用备用Region写入。
- **业务断言**：所有路径落到正确服务，API不返回静态HTML伪成功；身份/租户边界和回调ACL不丢；ALB重试不重复NEXT/Tool调用/回调副作用；旧DNS缓存和已有连接到旧Region时写请求被隔离而不是两地均受理；全部目标不健康时只能提供明确失败/维护状态，不能由入口兜底绕过备用禁写屏障。
- **资源/恢复断言**：连接/重连和补读峰值受预算，ALB健康检查不会因一个旁路故障摘除全部服务；`D_entry`包含实际DNS收敛与新旧长连接处理，不能把TTL配置值当全部客户端生效时间。
- **清理/证据**：恢复已验证的ALB路由版本，移除注入规则，逐路径回归；未改变Region写权的实验可恢复原测试入口，已转移写权时只执行T35受控回切。保存ALB/GSLB配置diff、实际后端/Region日志、DNS查询、客户端帧和副作用计数；平台、应用、前端共同签认。

<a id="t33"></a>
### T33 — ADS控制面与运行面独立故障（R33；L4；W13 / RB13 / D11）

- **前置/配置/角色**：确认两Region的ADS控制面/运行面相互独立，四服务跨Region已热备；ADS负责人提供真实停调度/隔离/恢复操作、镜像仓库与配置/密钥/日志依赖、宿主与容器清单。未经验证的ADS开关不得写成已有API。
- **数据/负载**：四服务混合1/4/8并发，保持长Run、WAIT、async、Tool未知执行及quiet WS；每Region独立监控，备用Region仍无业务写权。记录本地文件、Relay内存session和持久状态位置。
- **注入/步骤/时长**：子项一终止单测试容器/宿主或隔离其运行网络60秒；子项二仅阻断测试控制面60秒，观察存量服务与扩缩/发布能力分别变化；子项三镜像仓库、配置或测试凭据依赖不可用各60秒。故障TTL上限120秒；区域级ADS运行面失败引发接管必须串联T35。
- **业务断言**：控制面不可用不应被误报为所有业务已中断，存量继续服务与能否重建分别判定；容器恢复不依赖已丢本地文件；重建Runtime不等于能继续旧执行，FULL恢复点内历史可读，未知任务有明确收口/对账，no-store不新增落库。
- **资源/恢复断言**：ADS重建/镜像拉取/配置读取不会产生共享DB/Redis重连风暴；健康Region容量和控制能力独立可用，恢复符合`D_recovery`，资源回落。跨Region“独立”必须以实际依赖隔离证据验收，不能仅凭两个控制台名称。
- **清理/证据**：平台恢复运行资源与控制面连接，核对容器/镜像digest/配置版本/凭据版本和实例ID；保存平台事件、四服务流量、遗留任务及外部副作用清单。恢复原Region平台不自动恢复其业务写权。

<a id="t34"></a>
### T34 — 单Region跨AZ故障及剩余容量（R34；L4；W13 / RB13 / D11）

- **前置/配置/角色**：每Region至少两AZ，记录四服务副本、ALB后端、DB/Redis实际AZ落位及共享网络/存储依赖；ADS/SRE、DBA和缓存负责人确认可隔离的测试AZ清单和独立管理通道。AZ数量已确认不代表四服务或数据层实际分散部署已验收。
- **数据/负载**：在已测安全负载25%/50%/100%阶梯下运行四服务混合业务，持续10分钟后再注入；记录失去一个AZ后的剩余容量，不能默认按副本数均分或允许无限扩容。
- **注入/步骤/时长**：先停止一个测试AZ中应用实例60秒，再单独验证同AZ网络/依赖故障；规则上限120秒，数据层切换须用管理员已验证方法。另测ALB未及时摘除该AZ和该AZ恢复后大量容器同时重连，独立子项复位。
- **业务断言**：区域内切换不改变Region写权；剩余AZ的四服务保持业务和控制能力，失联旧实例不得越权提交；跨AZ路由和回调不中转已失效节点，Runtime状态不可靠续接时按合同收口而非重复执行。
- **资源/恢复断言**：剩余资源在签认容量内，连接池总量、DB/Redis和外部配额满足`B_*`；`D_az`覆盖摘流、必要数据层切换、重新接入与遗留任务恢复。剩余容量不足必须有明确限流，不能把大比例拒绝算作SLO成功。
- **清理/证据**：逐批回接原AZ，验证无旧owner写入和二次流量峰值；保留拓扑、ALB后端变化、每AZ实际请求、资源峰值/拒绝量和任务差集，由四服务及平台共同确认。

<a id="t35"></a>
### T35 — 双Region单写接管、网络分区与受控回切（R35；L4；W14 / RB14 / D12）

- **前置/配置/角色**：预部署热备且DB异步复制，指定初始主Region A、备Region B；DBA和平台提供已验证的旧DB写端隔离、四服务写入/后台任务/外呼隔离及独立核验证据。跨Region单写屏障必须先实现并验收，不能用Chat本机fencing或GSLB改流量代替。全过程由IC指挥，DBA、业务附件存储、ADS/ALB/GSLB、四服务和下游负责人共同在场。
- **数据/负载**：四服务已提交标记集、Admin映射版本、Run/Event、Tool执行与外部回执、Relay任务和文档引用；每秒一组可关联标记，覆盖复制截点前/附近/后。保持RUNNING/WAIT/async/UNKNOWN以及FULL/no-store，1/4/8并发；B演练前仅受控探测，不执行业务写/外呼。
- **注入/步骤/时长**：分别演练计划切换、A仍活着的区域网络分区、复制滞后60秒、切换后A重连及迟到callback/Stop；另做“DB文档引用已复制，但业务附件对象尚未复制”的子项。可撤网络故障默认60秒/上限120秒；接管每阶段受`C_cutover`约束且超时暂停，旧写端保护屏障无自动TTL。顺序固定：停止新写/控制在途→取得A已隔离证据→确定B数据及附件对象恢复点/损失集→提升B数据端→验证四服务配置/缓存/单写、回调、附件引用/权限→允许B符合条件的业务→切GSLB/DNS→分批恢复用户访问。
- **业务断言**：任何时间至多一个有效写Region；A未被可靠隔离时B不得开放写入，即使DNS指向B也只允许明确的受限响应。A进程/定时任务仍活着、旧DNS未过期、旧连接/凭据/延迟消息均不能绕过屏障。新Region不能自动重跑Tool/Relay/DomainAgent未知执行；Runtime无法可靠接管时先对账并给出可解释终态/中断，不承诺无缝续跑。
- **数据/资源/恢复断言**：按B实际复制恢复点测量DB/Admin配置RPO；业务附件单独核对对象恢复点、版本/内容校验、DB引用及租户/访问权限，不以静态产物完整替代附件验收。DB引用已到而对象缺失/权限不可核对，或任一损失超过批准RPO时，阻断相应接管/回切放行并升级。批准损失也须列出对象/引用清单并隔离受影响附件上传/下载/引用功能，只能报告限定范围恢复，不能报全功能恢复。FULL只能补回恢复点内历史，no-store不追加正文持久化。B的DB/Redis连接、恢复、DNS陈旧流量及下游重试有界；分别测`D_static/D_entry/D_region/D_reconcile`。
- **回切步骤**：A恢复后先保持隔离，从当前B事实预先重建/追平；DEP06-05仅预收集恢复水位，不能作为最终一致证明。批准停写窗口内覆盖QUIESCING阶段迟到callback/终态提交，DEP06-06强制隔离B数据写入及外呼后，DEP06-07取得或证明**隔离后的最终提交位点**，要求A追平该位点并通过业务附件对象/DB引用/权限核对，才可DEP06-08激活A及后续切入口。分别验证隔离前迟到提交包含在最终位点、隔离后写入被拒；提前复制追平不能替代该检查。失败按最后确认主权处置：B未失权继续B；B已隔离/A未激活先证明无人写再恢复B；A已激活不能直接恢复B。禁止自动回切或合并独立写入历史。
- **清理/证据**：可撤故障移除与写权屏障解除分开记录；注入超时不恢复旧主写入，接管后不能用回滚DNS代替回切。保存隔离前预水位、隔离后最终提交位点、目标追平证明、QUIESCING迟到提交清单、双Region拒写/隔离回执、对象恢复点/缺失/引用及权限差集、映射/静态版本、外部执行对账、DNS流向和阶段时间；共同签认UNKNOWN、批准损失、受限功能和唯一主Region后才清理测试实体。缺少最终位点、对象或权限核对证明的子项BLOCKED，不以本地mock关闭。

### 部署新增风险追踪矩阵

R28–R35的场景、工作包、责任、用例、运行册与演练关系统一维护在[风险追踪矩阵](risks.md#risk-register)。本页T28–T35保留各自执行条件、断言与关闭证据，当前均为NOT_RUN。

### 场景分支补充覆盖

以下子项归入对应T编号，在相同测试配置和证据目录下逐项记录，不再新增风险编号。它们防止风险驱动用例漏掉正常/拒绝路径和状态交错。每项沿用公共基线、自动撤销与清理规则，业务身份只能通过测试环境可信身份系统生成。

| 场景分支 | 归属用例与明确执行动作 | 必须核对的结果 |
|---|---|---|
| 身份、准入与附件 | T02/T08/T21：无身份、错owner、错tenant、已删除Session、无效附件分别发起Run，1/4并发；再用本人有效身份成功发起 | 拒绝请求不创建消息/Run、不请求下游，不泄漏已有实体；HTTP200业务错误按协议识别；成功路径permit随后台任务结束释放 |
| 路由分支及开关 | T05/T06/T16：已绑定、显式Agent、Intent单选/模糊、用例库命中/未命中、Relay兜底分别执行；Intent/用例库/记忆/留存开关组合只在有效配置下测 | 关闭依赖不发生实际外呼，路由与绑定符合合同；错误不会触发重复下游任务；同次请求技能配置与留存快照一致 |
| Interaction | T07/T08/T13/T14：澄清、问卷、候选确认各制造WAIT；同答案/不同答案双实例并发回应20轮，RESPONDING claim后终止实例；拒答自动切换开关开/关各测，候选选择与Stop竞争 | 最多一个有效continuation，重复或过期回应不重复副作用，claim孤儿被有界治理；source/leaf陈旧时不切错Run；旧owner不能提交；候选拒绝或用户取消按合同收口 |
| 异步回调全状态 | T04/T13/T14：开启async；在挂起commit前发callback，commit后重试；过期、重复、COMPLETED/FAILED竞争及Stop竞争各20轮；APPEND/REPLACE/仅通知分别测；4个callback占满时发第5个 | early返回可重试409，已终态/过期返回accepted=false；忙为429，不吞掉结果；只有一方终态CAS成功；APPEND/REPLACE保留对应正文/parts语义，纯终态通知不清正文 |
| 回调容量/入口 | T01/T04/T26：5 MiB请求体、128帧/128事件边界及各+1；未知Content-Length的流式请求、256 KiB单帧及总事件字节限额分别测；注入30秒慢DB并取消客户端 | 超限在终态claim前拒绝且不消耗唯一完成机会，Servlet过滤许可释放；非法控制帧不触发新async/拒答状态机；FULL/no-store提交后行为分别验收，ALB内部回调路由及实际身份/ACL链真实验证 |
| 历史管理及旁路 | T10/T11/T17/T18/T23/T24/T25：分页/树/版本/选路/分支/归档/恢复/批删分别测试空集、小集和该例最大集；反馈相同请求/冲突请求、偏好写、标题人工改名分别并发主Run | 查询错误不伪装空结果，归档不等于Stop、恢复归档不复活DELETED；反馈只更新其自身结果，不重建主Run；旁路失败不覆盖人工标题或主事实 |
| 文档与分享生命周期 | T12/T18/T19/T21/T22：上传→登记→状态→附件→预览→下载→软删；单轮/选中分享创建→读取→投递→撤销，各步骤前后分别注入已定义的故障 | 预览URL与真实下载权限一致，软删后不可用于新附件，物理对象另对账；分享快照、租户权限与撤销一致，外部发送不承诺召回 |

回调行为复核来源：[回调应用服务](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/DomainAgentAsyncTaskCallbackApplicationService.java)、[终态提交](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/DomainAgentAsyncTaskCallbackCommitService.java)、[Servlet准入过滤](../../../src/main/java/com/huawei/it/ex/one/interfaces/chat/DomainAgentAsyncTaskCallbackAdmissionFilter.java)。测试实际配置覆盖默认值时，边界随配置调整并记录，不固定拿默认数值验收另一个部署。

## 3. 回归入口与放行

在具备JDK21及项目依赖的环境，可运行以下**既有测试入口**作为L1起点；它们不是本文所有故障注入的现成自动化实现。具体注入fixture、真实集成和负载脚本属于后续加固任务，未实现前相应用例保持NOT_RUN/BLOCKED。

```sh
mvn -B -Dtest=RunAdmissionControlServiceTest,ChatStreamApplicationServiceTest,RedisChatLiveEventBusTest,ChatRunRecoveryOrchestratorTest,DomainAgentAsyncTaskCallbackApplicationServiceTest,DocumentApplicationServiceTest,SessionTitleApplicationServiceTest test
```

运行前核对本机Maven/JDK实际路径和测试环境，不能复制历史机器绝对路径当作通用命令。已有测试对应源码都在 `src/test/java`，新增断言应集中验证真实故障语义，避免只镜像实现。

放行按风险逐项：先确定性L1，再L2真实依赖、L3多实例，最后L4跨服务/平台/双Region联合演练；必须同时通过业务正确性、资源有界、释放和恢复断言。mock通过不能关闭R05/R19/R22、真实锁/Cluster风险或R28–R35平台联合风险。某项环境故障无法安全注入时记录覆盖缺口、替代证据和风险接受人/到期日，不能省略它或填PASS。R35的旧主隔离证据缺失时，必须阻止备用Region开放新写，不能用风险接受代替单写保障。
