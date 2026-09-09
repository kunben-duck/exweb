# 验证记录与高可用运行册

## 1. 初次审计执行记录（沿用基线）

基线`ab52f9bbef758bd4496761a6839f9c50daa20e15`；执行日期2026-09-10（Asia/Shanghai）。JDK21.0.11，Maven3.9.6。只新增本审计文档和架构入口，没有业务改动，也没有执行数据库迁移。

| 验证 | 结果与边界 |
|---|---|
| 基线/工作区 | 开始时HEAD与计划一致，工作区干净；最终仅文档改动 |
| 定向测试 | RunAdmissionControl、IntentFeedbackTaskDispatcher、ServletWS慢发送、RedisLiveEventBus、RunLease、CandidateSwitch、EventPipelineRetention通过 |
| 首次全量 `mvn clean verify` | 1514项，1 failure、0 error、1 skipped；未通过，不能删去这次记录 |
| 失败定向复测 | AmbiguousRouteInteractionFlowTest 9项通过 |
| 第二次全量 `mvn clean verify` | **1514项，0 failure、0 error、1 skipped；BUILD SUCCESS，jar/repackage完成** |
| 真实默认许可边界隔离实验 | tenant200后第201拒绝；user60后第61拒绝；DomainAgent64后第65拒绝；取消释放可重新进入；WS8/8/128边界及清理通过 |
| Servlet/依赖资源实验 | 实际当前依赖启动隔离Servlet服务，未连接DB/Redis/生产接口；结果见下一节 |
| 接口/OpenAPI | 44个唯一操作与目录逐项一致，OpenAPI本地引用全部解析 |
| Mermaid | 初次审计15张本地Mermaid图生成PNG；本次细化结果单列在1.1，源保留在Markdown |
| 本地链接与diff | 逐一检查本审计文档链接及 `git diff --check`，仅文档范围 |

首次失败：`AmbiguousRouteInteractionFlowTest.switchesRunningAmbiguousContinuationThroughRealStopAndEventPipeline:316`，`cancelledRunId`的AtomicReference在断言时仍为null，预期为续跑Run ID。该断言观察的是**异步DomainAgent取消副作用**，不是一次真实openGauss锁失败。单独复测及第二次全量通过，提示时序敏感；没有据此宣称业务缺陷已定位或修复。后续应对特定Run/取消确认建立确定性测试等待，不能仅延长任意sleep掩盖问题。本轮遵守范围不改测试/业务逻辑。

唯一skip为`HuaweiS3ObjectStorageIntegrationTest`，原因是未配置Huawei S3测试endpoint；它不能当作OBS/真实数据库成功验证。

### 命令与日志

本机工具路径：

```sh
export JAVA_HOME=/Users/uben/.cache/codex-jdks/temurin-21/Contents/Home
export MAVEN=/Users/uben/.m2/wrapper/apache-maven-3.9.6/bin/mvn
"$MAVEN" -q -Dtest=RunAdmissionControlServiceTest,IntentFeedbackTaskDispatcherTest,ChatServletWebSocketHandlerAsyncSendTest,RedisChatLiveEventBusTest,ChatRunLeaseApplicationServiceTest,CandidateDomainAgentSwitchApplicationServiceTest,ChatEventPipelineRetentionTest test
"$MAVEN" clean verify
"$MAVEN" -Dtest=AmbiguousRouteInteractionFlowTest test
"$MAVEN" clean verify
"$MAVEN" -q dependency:build-classpath -Dmdep.outputFile=/tmp/financeex-ha-classpath.txt -DincludeScope=test
git diff --check
```

本次临时证据（不纳入Git，不保证其他机器存在）：

| 文件 | 内容 |
|---|---|
| `/tmp/financeex-ha-targeted.log` | 定向测试 |
| `/tmp/financeex-ha-verify.log` | 第一次全量失败，完整保留 |
| `/tmp/financeex-ha-ambiguous-retest.log` | 9项定向复测 |
| `/tmp/financeex-ha-verify-repeat.log` | 第二次全量及打包 |
| `/tmp/FinanceExHaProbe.java`、`/tmp/financeex-ha-resource-probe.log` | Servlet/当前库默认资源实验源码及输出 |
| `/tmp/FinanceExHaLimits.java`、`/tmp/financeex-ha-limits.log` | 直接使用编译后的生产许可类验证边界 |
| `/tmp/financeex-ha-check.cjs`、`/tmp/financeex-ha-doc-check.log` | Ruby Psych结构化YAML解析、44项比对、引用/链接、Mermaid渲染 |
| `/tmp/financeex-ha-diagrams/` | 图的mmd/PNG与manifest；本次细化重生成后为33张，包含架构入口2张 |

复核资源实验：使用依赖classpath，`java --class-path "$(cat /tmp/financeex-ha-classpath.txt)" /tmp/FinanceExHaProbe.java`；许可实验在classpath前加`target/classes:`。实验只绑定127.0.0.1临时端口，退出关闭Tomcat/HTTP/WS与Schedulers。没有启动完整企业应用、没有加载生产凭证，证明的是同版本自动配置及默认库行为。

## 1.1 Jalor与Run分阶段细化验证

本次在同一`ab52f9bb`基线上仅修改文档。沿用上一轮第二次全量1514项及打包通过的记录；**本次没有再次执行全量或打包**，不把过去的结果记成新的验证，也保留前一轮首次全量失败记录。

| 检查 | 结果及证据边界 |
|---|---|
| 标题/启动/准入/记忆定向 | JDK21，12个现有测试类，78项通过，0失败、0错误、0跳过；日志`/tmp/financeex-ha-detail-targeted.log` |
| 接口覆盖 | OpenAPI结构化解析及Controller原清单复核，44唯一操作全部关联；无新增接口/协议 |
| 步骤对应 | 16张新增分阶段图，150个唯一步骤编号均有表格说明；跨文档公共步骤复用，不重复计数 |
| Mermaid | 审计目录31张+架构入口2张，共33张本地CLI渲染；初次渲染发现Title被解析为关键字，已改参与者标识并复验 |
| 链接与锚点 | 临时脚本校验本地文件存在、Markdown标题锚点、OpenAPI本地引用；无缺失；非永久测试插件 |
| 范围 | 14份审计文档及既有架构入口；只改Markdown，未修改业务/测试Java、SQL、配置、DTO或接口 |

本次定向命令：

```sh
env JAVA_HOME=/Users/uben/.cache/codex-jdks/temurin-21/Contents/Home /Users/uben/.m2/wrapper/apache-maven-3.9.6/bin/mvn -B -Dtest=SessionTitleApplicationServiceTest,SessionTitleCommitServiceTest,SessionTitleMetadataTest,SessionTitlePropertiesTest,DefaultSessionTitleProviderTest,DefaultSessionTitleAppExclusionProviderTest,SessionTitleProviderConfigurationTest,ChatRunStartFlowTest,ChatRunAdmissionCommitServiceTest,StandardRunRuntimeCoordinatorTest,RunAdmissionControlServiceTest,ShortTermMemoryContextAssemblerTest test
node /tmp/financeex-ha-check.cjs --render
git diff --check
```

文档检查日志：`/tmp/financeex-ha-detail-doc-check.log`。标题测试使用已有测试仓储/Provider/调度器，不是实际生产标题HTTP、openGauss事务锁或Jalor超时验证。新增R25是源码确认的期限/许可范围与资源竞争风险，不据此声称已经在生产测得吞吐下降。

新增详图覆盖：E/L/P/M/A/X（入口、许可、准备、记忆、准入和独立启动边界）；TT（本地初始标题、排除、候选、生成、提交）；RT/IT/GA（Binding、外部信号、Intent重试/ACK及技能Gate）；EV/IC/CS/RF/ST/AS（输出、续跑、候选、拒答、Stop和异步回调）。默认关闭分支、后台任务与前端恢复均单列，不把源码默认值当压测承诺。

### Jalor部署待确认清单

| 项目 | 当前证据与待验证内容 |
|---|---|
| 身份/ACL与路径 | 本服务有身份防腐层，JalorTraceContextProvider为扩展占位；需核对实际用户/租户、内部回调ACL、path rewrite及可信头过滤 |
| 超时 | 转发获取连接、后端请求、SSE/WS idle、首字节/总时限分别提供；必须确认HTTP30s首事件及长期WS不会被更短网关期限误截断 |
| 重试 | 不假设Jalor自动重试POST安全；NEXT、Stop、callback及查询分别核对，避免网关×服务端重试乘法放大 |
| 容量 | 获取实际连接池、每用户/租户/实例请求和长连接配额；Tomcat8192/本地WS8不代表Jalor上限 |
| 发布/摘流 | 验证Upgrade转发、滚动摘流、已有WS保活/关闭、客户端退避和Resume；不假设粘性路由能替代持久化fencing |

## 2. 实际依赖默认值

有效版本：Spring Boot3.4.6、Tomcat10.1.41、Reactor Core3.7.6、Reactor Netty1.2.6、Spring Data Redis3.4.6、OBS3.25.10。

```text
Tomcat executor=org.apache.tomcat.util.threads.VirtualThreadExecutor
maxThreads=-1 maxConnections=8192 acceptCount=200
HTTP handler virtual=true
connections beforeWs=2 afterThreeWs=4 afterWsAbort=1
global boundedElastic=loomBoundedElastic virtual=true
availableProcessors=10 boundedSize=100 queueParameter=100000
explicit newBoundedElastic virtual=false
HttpResources.maxConnections=500
generic ConnectionProvider.DEFAULT_POOL_MAX_CONNECTIONS=20
ConnectionProvider.DEFAULT_POOL_ACQUIRE_TIMEOUT=45000ms
Redis listener default=SimpleAsyncTaskExecutor concurrency=-1 virtual=false
ObsConfiguration validateCertificate=false strictHostnameVerification=false
ObsConfiguration maxErrorRetry=3 callTimeout=0
```

- 连接计数含acceptor预留/keep-alive复用，实验表明Upgrade连接仍受Connector计数，不表示before2就是两个业务请求。8192上限未做满载测试。
- CPU10是本机值，不是产品默认硬件；全局boundedElastic逻辑上限默认10×JVM可见CPU。Boot的ReactorEnvironmentPostProcessor在正常启动早期设置virtual开关；早期静态初始化/定制Scheduler或环境覆盖应在部署中重新核实。
- `ConnectionProvider.create(name)`的通用默认与`HttpResources`当前共享默认不是同一值，不能把前者20抄成实际WebClient池上限。HTTP池还按远端/配置分组，不是500全局连接上限。
- 单独new的Redis listener没有继承Boot虚拟线程设置。平台SimpleAsyncTaskExecutor默认不限并发；测试只检查类型/单个任务，不制造线程耗尽。
- OBS值通过实际类getter及字节码核实；没有对生产存储实施中间人或上传测试。

可用`javap -c -p -classpath "$CP"`复核：`org.springframework.boot.reactor.ReactorEnvironmentPostProcessor`、`org.springframework.boot.autoconfigure.web.embedded.TomcatVirtualThreadsWebServerFactoryCustomizer`、`org.springframework.data.redis.listener.RedisMessageListenerContainer`、`com.obs.services.ObsConfiguration`。这些本地依赖证据比泛引其他版本在线文档更适用于此基线。

## 3. 本地回归覆盖与未证实项

| 能力 | 当前本地证据 | 还需环境验收 |
|---|---|---|
| 许可、排队超时、取消释放 | 真实Semaphore/有界executor，已有Dispatcher与WS慢发送测试 | 多实例倾斜、网关配额、CPU容器限制 |
| Event ACK屏障、候选续跑 | 实际编排/测试仓储、EventPipeline与异步调度测试 | openGauss SHARE NOWAIT与UPDATE真实冲突、磁盘commit延迟 |
| Redis故障降级 | mocked Redis失败、发布排队/恢复信号测试 | Cluster切主/网络分区、订阅重建、重定向与缓存回源风暴 |
| Stop/回调/Watchdog终态 | 单元/编排CAS与测试仓储 | 跨实例kill、真实事务回滚/锁、Relay迟到stop代次保证 |
| 慢客户端 | 模拟阻塞WS发送、溢出释放 | 真TLS/socket背压、网关idle、移动端重连、多个topic汇聚 |
| S3/文档/WeLink | adapter测试及依赖默认值 | 存储TLS、流关闭、远端幂等、上传后DB失败清理 |
| 全接口目录/协议 | Controller及OpenAPI44项核对、YAML与引用解析 | 实际企业路由前缀、ACL、Cookie/token注入、生产前端行为 |

本地单元测试不建立真实openGauss。仓库本地PostgreSQL/Redis standalone示例也不能证明Ustore/Redis Cluster故障行为。本轮不提供吞吐量、最大连接承载或SLA承诺。

## 4. 混合负载和故障演练矩阵

上线前提供：实例CPU/内存/JVM参数、网关连接/idle/请求限制、所有环境覆盖、DB最大连接与statement/lock/socket timeout、Redis拓扑、下游配额、业务P95/P99与恢复SLO。没有这些不能确定安全限额。

| 实验 | 注入与逐步增加维度 | 必须验收的结果 |
|---|---|---|
| 正常混合Run | 单/多租户，NEXT/EDIT/REGENERATE/Interaction，速率和持续时长 | 首Run事件P95/P99、正确终态、无重复active、连接借用余量 |
| 高速下游慢DB | 每流帧速率/大小逐级增，锁/磁盘延迟 | 队列与堆有界、不会native/OOM，超限正确run.failed |
| 恢复风暴 | 长历史，afterSeq0，多WS+Resume，多个标签页 | 恢复并发受限，Stop/heartbeat仍有资源；无遗漏/乱序误删 |
| Redis故障 | 连接超时、只读错误、Cluster切主、订阅中断 | DB事实继续正确；FULL能恢复；no-store丢失有明确指标，不承诺补回 |
| DB故障 | 池耗尽、锁长等待、提交失败、主备切换 | 请求有界失败、无半终态、恢复任务不永久single-flight |
| 快速stop和切换 | 双实例、下游stop延迟、空闲但保活流 | CANCELLING在SLO内收口，旧owner不能写，新Run不被旧stop停止 |
| 异步回调 | early409、重复/过期、最大结果、并发4/5、kill在commit后 | 只一次终态、错误不覆盖、FULL历史可恢复、回调忙快速拒绝 |
| 上传下载 | 32个大文件、慢下载、客户端取消、上传后DB失败 | 流/FD/存储连接归还、堆峰值与孤儿对象可追踪 |
| 旁路突发 | feedback/候选/search同时突增，token无限阻塞 | 不挤占主流程预算，期限覆盖队列/底层IO，有可操作503/429 |
| 标题旁路开启 | 多Session长路径、前三问重试、候选DB/token/提交锁分别阻塞 | 区分8生成许可与完整任务量；Run首事件、Hikari pending、Session锁等待可观测，人工标题及scope不被覆盖 |
| 发布/重启 | 滚动下线与kill -9分别测试 | 不再准入新任务、连接摘流、旧lease可恢复，无需手工清理永久RUNNING |

不要只测单接口QPS；至少统计每分钟完成任务量、平均/长尾持有时间和错误率。按Little定律`L≈λW`推算在途需求，再验证DB/下游/内存资源，不从配置直接倒推可用用户数。

## 5. 指标与告警建议

以下为建议采集项，不代表当前已经全部暴露。基线只有默认Actuator health暴露，DB health默认关闭、Redis health关闭，没有声明Prometheus scrape配置。

| 层 | 应采集 | 建议告警条件（阈值需SLO校准） |
|---|---|---|
| HTTP/准入 | 各endpoint P95/P99、活跃/排队/拒绝、用户/租户维度 | 首事件接近30s；busy/active冲突异常突增；避免高基数用户原文标签 |
| Run事实 | RUNNING/CANCELLING/WAIT/ASYNC按age分布，terminal延迟 | CANCELLING超控制预算，ASYNC超lease未收口，已删除session仍active |
| Execution | heartbeat耗时/失败、lease余量、fencing拒绝、scan耗时/最后成功时间 | lease余量低于多次heartbeat，Watchdog single-flight长期不结束 |
| JDBC | active/idle/pending/acquire timeout、TX时长、锁wait、每接口SQL量 | active接近10且pending增长、heartbeat借不到连接、慢查询spill |
| 队列/线程 | event/auth/feedback/Redis listener/send的active/queued/rejected，virtual/platform分别 | 队列持续增长、反馈排队接近500ms、native线程数非业务比例增长 |
| 实时 | topic/connection数、queued bytes、发送超时、RECOVER_REQUIRED、replay行数/字节 | 重连/补读风暴、全局live lag增长、no-store掉事件 |
| 依赖 | token/HTTP connect/first/idle/total/attempts、429/5xx、每logical query放大比 | retry放大与下游5xx同时增长，stop未知结果积压 |
| 进程/存储 | heap/RSS/direct memory、GC、FD、socket、multipart磁盘、孤儿对象 | OOM前趋势、FD接近限制、存储对象与DB记录偏差 |

日志用trace/run/session关联，避免记录完整query、候选、token、Cookie和大业务payload。慢查询采样脱敏；指标标签不放runId/userId等高基数字段。

## 6. 探针、摘流、上线与回滚

1. **探针分层**：liveness只看进程活性；readiness按关键DB可用性、准入饱和与治理健康决定摘流。Redis失败可以按FULL/no-store及跨实例交付要求选择降级，不能无条件以健康绿灯对外承诺正常实时。
2. **退出顺序**：网关先摘流/停止新Run准入，再按限时等待本机执行和持久化关键动作；WS通知重连；超过deadline释放连接，由lease/Watchdog收口。Boot graceful HTTP drain并不自动等待所有已脱离HTTP的后台Run。
3. **发布检查**：对比实际变量与容量矩阵；DBA校验active唯一索引和性能索引有效；独立检查TLS/ACL/企业token实现；记录每实例连接预算与DB总配额。
4. **灰度**：先低流量租户，观察Stop、首事件、事件lag、恢复量和辅助接口占池；再扩流。涉及缓存时序或协议的修复要同时验证旧客户端和下游。
5. **回滚**：本轮只有文档无需运行时回滚。后续加固按功能小提交、兼容配置、可回退协议部署；数据库DDL不能假定随jar回滚，先确认前后版本兼容。不能以放大队列或关闭fencing作为回滚手段。
6. **可靠性演进边界**：集群配额、Outbox、投递幂等、分块回调/持久化stage属于后续独立设计。先做有界资源与观测，避免把所有问题混入一次主流程重构。

## 7. 文档与已有契约差异

旧[前端联调文档](../../frontend-integration.md)存在“删除前完成stop”的强表述；实际实现是数据库软删提交后调度best-effort stop，本审计已按源码说明。未在本轮扩展修改原协议文案，以免把文档审计混成协议变更；后续应统一措辞。

所有加固建议都尚未实施。通过测试只说明基线回归状态，不证明风险登记为空；需要真实环境验收的项应保留在上线清单，不能用第二次测试通过将其关闭。
