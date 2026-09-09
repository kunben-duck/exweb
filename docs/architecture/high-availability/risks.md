# 风险登记与最小加固方案

## 分级与适用范围

本轮不修复业务代码。S=源码/依赖实现确认，L=本地隔离验证，E=真实环境待验。P1表示对应条件下可导致服务级资源耗尽、安全边界破坏或关键一致性问题，应在相应功能上线前处理或明确风险接受；P2表示局部可用性、性能或恢复缺口。不把所有共享资源瓶颈判为代码缺陷，也不把本地模拟称为生产事故。

| 编号 | 等级 | 主要风险 | 证据 |
|---|---|---|---|
| R01 | P1，容量条件 | Relay及DomainAgent桥接流无界缓冲，可堆积到OOM | S；慢DB压力规模E |
| R02 | P2，架构容量 | Hikari10被主流程、辅助和治理共享，缺全实例准入预算 | S；承载量E |
| R03 | P1，长历史/恢复风暴 | HTTP Resume无独立配额且完整List回放 | S；堆/DB阈值E |
| R04 | P2，交付语义 | DB提交后到cache/publish间崩溃，no-store结果无法恢复 | S |
| R05 | P1，Relay上线依赖 | session级迟到stop可能影响同session后续Run | S；Relay隔离保证E |
| R06 | P2，集成启用条件 | 阻塞鉴权在HTTP计时外；主Intent广泛立即重试 | S；企业resolverE |
| R07 | P2，可升级资源耗尽 | 部分事务内Redis延长锁/连接占用 | S；故障持续性E |
| R08 | P2，未知响应窗口 | NEXT/分享等缺端到端幂等，超时后重复提交 | S |
| R09 | P1，突发fanout | Redis listener默认无界平台线程执行器 | S+L |
| R10 | P2，队列/SQL | 旧偏好等队列无排队期限或JDBC期限 | S+L（反馈新保护已测） |
| R11 | P2，数据增长 | 搜索、版本递归、首answer摘要/metadata读取成本 | S；计划/规模E |
| R12 | P2，存储容量 | 上传整文件缓冲、下载许可提前释放、孤儿对象 | S |
| R13 | P2，治理可靠性 | Watchdog SQL期限与scan预算不覆盖全部分支 | S |
| R14 | P2，终态恢复 | CANCELLING先提交后实例退出；删除后stop丢调度 | S；跨实例时序E |
| R15 | P2，上线条件 | 性能索引手工迁移，启动不验证全部索引 | S |
| R16 | P2，缓存miss及非默认组合 | 默认并发miss重复查询；cache关闭特定路径回调线程可能执行JDBC | S；配置组合E |
| R17 | P2，删除语义 | Resume/WS归属检查未排除已删除会话 | S |
| R18 | P2，外围一致性 | 分支拷贝非原子；分享创建可能越过删除撤销 | S |
| R19 | P2，外部副作用 | WeLink超时重试及投递记录后写可重复发送 | S；下游去重E |
| R20 | P2，仅联调样例 | local-test-frontend恢复游标/ACCESS_DENIED处理 | S；不代表生产前端 |
| R21 | P1，文档信任边界 | PATCH metadata可覆盖providerDocument引用 | S；下游鉴权影响E |
| R22 | P1，huawei-s3启用条件 | OBS缺证书及hostname验证 | S+L；TLS故障验收E |
| R23 | P2，数据库慢/锁等待 | 批量删除持Session锁但无显式本地事务期限 | S；数据库全局期限E |
| R24 | P2，消息路径并发 | path选择无active Run检查或Session锁后CAS | S；并发结果E |
| R25 | P2，标题功能启用条件 | 标题候选查询和提交排队不受生成8许可/HTTP期限保护 | S；混合容量与锁等待E |

## 上线前优先项

### R01：入站事件无界缓冲

- 位置：[RelayWebSocketRuntimeAdapter](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java) L236/L265/L301/L649；[ConfiguredDomainAgentClient](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/domainagent/ConfiguredDomainAgentClient.java) L185；[ChatEventPipeline](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatEventPipeline.java) L90。
- 时序：下游高频发帧 → adapter内部subscribe持续消费 → Flux.create BUFFER堆积 → Event IO受慢DB限制。64许可、1MiB/256KiB单帧及总运行timeout不限制累计队列字节；Assembly累计正文也需预算。
- 影响：Run、Interaction及同进程所有接口可能因GC/OOM退化。现有帧大小、总运行时间和下游bulkhead有价值，但不能消除这个条件。
- 最小修复：优先保持端到端request demand；确需桥接时增加事件数+字节双上限和明确overflow失败，统一dispose socket/timer并按现有run.failed收口。禁止静默drop正文/控制帧。
- 成本：中，触及共享Runtime适配器，需逐事件语义和Stop回归。验收：DB挂起、下游持续输出，队列/堆稳定有界、只提交一个终态、正常流不丢帧。

### R03：Resume绕过连接配额且全量物化

- 位置：[ChatStreamApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatStreamApplicationService.java) L251/L276/L300/L433；[ChatEventMapper](../../../src/main/resources/mapper/persistence/ChatEventMapper.opengauss.xml) L124；[MyBatisChatEventStore](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/MyBatisChatEventStore.java) L346。
- 时序：多个客户端/标签页afterSeq=0 → 每次独立查询全部历史List → 大Event、JSON和live buffer并存。HTTP Resume不经过LocalWebSocketConnectionRegistry，无法由WS8条限制约束。
- 影响：历史SSE、Run Resume和WS补读均可能压DB/堆，HTTP还可独立增加live订阅。现有owner校验防越权，不防本人高成本重复恢复。
- 最小修复：Resume独立实例/用户许可，拒绝不排队；查询分页按sequence游标推进且限制总回放字节/时间，先建live有界缓冲维持无缝衔接。网关先限制恢复并发作为上线止血。
- 成本：中，分页必须保序、避免与live重复/遗漏；不把WS已有配额直接复用为同一计数。验收：长历史、多次刷新、取消释放、Redis故障后恢复风暴。

### R05：Relay session级停止的外部代次隔离

- 位置：[ChatRunStopCoordinator](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStopCoordinator.java) L358；[RelayWebSocketRuntimeAdapter](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java) L1379。
- 时序：A的stop发送失败/迟到，本地A已cancelled → B继续RESUME同runtimeSessionId → 迟到stop_all_agents落到B。当前等待发送完成或paused不是全局有序代次屏障。
- 影响：固定专家/Relay续接；当前明确要求stop异常后仍复用同session，因此不能建议悄悄新建session作为“无兼容成本修复”。
- 最小方案：Relay提供session内有序命令或代次校验，ChatService保留现有有界等待和CANCELLING约束；未验证前作为联合上线条件。若必须ChatService独立保证，需另议协议run generation或停机屏障，不是单行修改。
- 验收：两实例、网络延迟/重排、stop失败、快速B请求，验证B不被A取消；本地mock不能证明此保证。

### R09：Redis入站listener执行器无界

- 位置：[RedisChatLiveEventBus](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java) L86；Spring Data Redis3.4.6 `RedisMessageListenerContainer.createDefaultTaskExecutor()`。
- 当前手工new listener，只设置connectionFactory，未注入taskExecutor；库默认SimpleAsyncTaskExecutor、concurrency=-1、平台线程。本地只创建默认执行器即验证，无需真实Redis。
- 突发Pub/Sub消息或JSON处理变慢会持续创建线程，publisher2到8和WSsend4到16不保护接收端。影响跨实例实时与整个JVM native memory。
- 最小修复：为listener显式配置独立有界executor和subscription执行策略；拒绝时标记需恢复，不能在Netty线程CallerRuns阻塞，也不能静默丢持久化事件。不同topic顺序/短窗口重排仍需验证。
- 成本：小到中，需新增资源配置和故障信号测试。验收：固定注入大量消息并阻塞handler，线程数与队列恒定、拒绝可观察、FULL可补读、no-store明确降级。

### R21：可编辑metadata污染可信附件引用

- 位置：[DocumentApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/document/DocumentApplicationService.java) L117/L291；Document PATCH接口。
- 时序：本人合法文档 → PATCH整体metadataJson覆盖providerDocument → 下次resolve附件时将其中docId/url当服务端记录透传。已验证owner并不能证明这些引用仍可信。
- 影响：可能错误引用外部文档、破坏附件、改变下游访问目标；是否进一步越权/服务端取URL依赖下游，不在本审计中宣称已利用或跨租户泄露。
- 最小修复：保留数据库现有providerDocument等服务器字段，仅接受明确定义业务metadata白名单或独立命名空间；不能信任客户端替换全文。成本小，需约定允许编辑字段。
- 验收：伪造docId/url/嵌套字段不得改变最终可信docList；合法重命名及业务字段保持兼容。

### R22：OBS TLS服务端身份未验证

- 位置：[HuaweiS3StorageConfiguration](../../../src/main/java/com/huawei/it/ex/one/infrastructure/storage/object/s3/HuaweiS3StorageConfiguration.java) L28；[pom.xml](../../../pom.xml)固定OBS3.25.10。
- SDK默认validateCertificate=false、strictHostnameVerification=false；本仓未覆盖。本地getter与依赖字节码确认，SDK在该分支使用trust-all/宽松hostname verifier。仅在storage.provider=huawei-s3且走HTTPS时适用。
- 风险：网络中间人可冒充存储，影响文档机密性/完整性；HTTPS文本本身不提供身份保证。不能以企业内网代替验证。
- 最小修复：显式开启证书链与hostname校验，使用受信企业CA/truststore；上线前验证endpoint证书SAN。成本小，错误证书会从“可访问”变为明确失败，须协调证书。
- 验收：自签未受信、错误hostname必须失败，正确CA/hostname成功；不接生产上传敏感数据做测试。

## 容量与执行隔离

### R02：共享数据库与不完整总预算

- 位置：[application.yml](../../../src/main/resources/application.yml) L24/L316；[RunAdmissionControlService](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/RunAdmissionControlService.java) L55。
- 各租户200、两个下游64、候选8、回调4、反馈1、恢复4等共享10个JDBC连接；多租户/辅助突发会相互竞争，500ms借用超时快速失败但不提供公平性。
- 最小方案：先测每类连接占用与QPS，统一单实例准入预算、Resume/搜索/回调限额及网关公平配额；必要时隔离高成本读执行与连接预算。不要仅把Hikari从10调大，把故障推给数据库。
- 成本：小到中（配置和局部限流）；接口忙响应需前端退避。验收：混合负载下主Run首事件、Stop/heartbeat保留预算，依赖失败不引发连接耗尽。

### R06：鉴权期限与Intent重试放大

- 位置：[FinEurekaIntentService](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentService.java) L103/L151、[HttpUseCaseLibraryClient](../../../src/main/java/com/huawei/it/ex/one/infrastructure/usecase/HttpUseCaseLibraryClient.java) L54、[WelinkChatShareDeliveryProvider](../../../src/main/java/com/huawei/it/ex/one/infrastructure/share/WelinkChatShareDeliveryProvider.java) L79。
- eager headers鉴权在timed publisher订阅前，token阻塞不受5sHTTP deadline；阻塞/流式主Intent仍广泛立即重试，流式总预算每次重置，默认名义可达500s加准备。
- 现有候选已独立auth、只重试瞬态并退避，不应重复列为旧的“全部错误重试”缺陷。
- 最小修复：阻塞Intent/用例库/WeLink鉴权明确异步隔离及真实底层超时；流式Intent已有专用4/128及5s鉴权期限，不应重复当作缺失，只需核对不可中断调用、重复鉴权及总预算。每逻辑请求可安全复用Header时只取一次；主Intent重试按瞬态分类、退避、总预算。成本中，影响故障策略，须确认鉴权刷新和服务端幂等。
- 验收：token永不返回、401/429、响应断流、最大retry，核对尝试次数/总耗时及旧任务线程释放，不只看HTTP504。

### R07：仍有事务内缓存调用

- 位置：[InteractionRunLifecycle](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/InteractionRunLifecycle.java) L85、[ChatRunApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunApplicationService.java) L137、[RuntimeBindingApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/runtime/RuntimeBindingApplicationService.java) L144/L191。
- Interaction兼容创建持Session锁后putActive；固定专家事务持Run/Execution锁后touch/cache.put。Redis慢时Java等待叠加，SQL deadline不终止它；并发可升级为共享池耗尽。
- 删除事务及标准准入提交后缓存路径已经修复，不应扩大描述为“全部Run在事务内查Redis”。Layered message afterCommit是另一个连接释放延迟问题，不是未提交锁。
- 最小修复：受保护方法内部DB-only，提交后按事务结果同步/调度缓存；保持缓存失败不回滚事实，检查后续读是否重新验证DB状态。成本小到中，缓存时序需回归。
- 验收：Redis.get/put/evict注入阻塞，断言事务未访问它们或已commit才访问；验证回滚不暴露缓存。

### R10：旧旁路队列与JDBC时限

- 位置：[IntentPreferenceCorrectionApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/routing/IntentPreferenceCorrectionApplicationService.java) L85、[IntentPreferenceCorrectionLoader](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/IntentPreferenceCorrectionLoader.java) L66、[IntentPreferenceExecutorConfiguration](../../../src/main/java/com/huawei/it/ex/one/application/config/IntentPreferenceExecutorConfiguration.java)。
- write1/queue1000无排队期限；read300ms只是调用方timeout，JDBC可能继续占连接。RouteMemory/record类似需要逐池检查，不概称所有队列都有500ms保护。
- 新反馈GET/POST已独立1+16与500ms原子排队过期，不是当前漏修范围。最小修复复用同类调度封装到有必要的旧写入口，读写SQL各加独立事务/statement预算，避免POST整体Reactive timeout造成迟到提交歧义。
- 成本小到中；验收队列未满但worker阻塞、迟到执行、取消、数据库不可中断等，确认失败开放后不长期持连接。

### R11/R15：长历史查询与索引部署

- 位置：[ChatMessageMapper](../../../src/main/resources/mapper/memory/ChatMessageMapper.opengauss.xml) L402/L472、[ChatRunMapper](../../../src/main/resources/mapper/persistence/ChatRunMapper.opengauss.xml)、[MyBatisSessionRepository](../../../src/main/java/com/huawei/it/ex/one/infrastructure/session/MyBatisSessionRepository.java) L186；[最后Run索引脚本](../../../src/main/resources/db/incremental-20260826-chat-run-last-status-index.sql)。
- 1字符ILIKE、版本后继递归、首assistant全文、最后Run完整metadata和全tree都可随用户历史增长；有索引不表示contains无需扫描。lastRun窗口仍遍历当前页会话历史索引项。
- R11最小方案：高成本接口短DB期限/独立准入、摘要SQL截取所需字段、逐页/字节预算、版本摘要限制范围；最大metadata需另议兼容规则。先EXPLAIN与profile，不立即上GIN（Ustore不适用）。
- R15：sql.init=never，服务只检查部分关键约束，不自动执行全部性能索引。上线必须DBA事务外低峰执行并发索引，检查有效性/重复执行和失败残留；无须让服务启动自动DDL。
- 成本小到中；验收长历史、最多200会话、单字搜索、排序spill和索引命中、timeout回滚。搜索503不能伪装空结果；最后Run失败可null。

### R12/R16：存储与配置服务资源

- R12位置：[ApiStoreDocumentStorage](../../../src/main/java/com/huawei/it/ex/one/infrastructure/storage/api/ApiStoreDocumentStorage.java) L67、[ObjectStorageDocumentStorage](../../../src/main/java/com/huawei/it/ex/one/infrastructure/storage/object/ObjectStorageDocumentStorage.java) L74、[DocumentController](../../../src/main/java/com/huawei/it/ex/one/interfaces/document/DocumentController.java) L192。
- API Store上传32个50MiB先整份readAllBytes可占1.56GiB原始数组，不能套用于OBS直接传InputStream的实现；下载permit在获得InputStream即释放，慢客户端可超过32个持续流。上传成功DB失败有孤儿对象。
- 最小方案：流式或临时文件上传、额外字节预算；下载permit随stream close/cancel释放；上传事务外预分配持久操作ID或失败清理/对账。成本中，需存储协议配合；慢客户端/超时/中断/DB失败逐项测资源归还。
- R16位置：[DomainAgentSkillConfigurationService](../../../src/main/java/com/huawei/it/ex/one/application/service/domainagentconfig/DomainAgentSkillConfigurationService.java) L59/L69、[ChatRuntimeDispatchCoordinator](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRuntimeDispatchCoordinator.java) L216/L241。
- 默认cache开启时同key并发miss也无single-flight，会重复查询。另一个独立问题是留存开启、cache关闭、无附件/deferred Binding时HTTP完成可直接调用同步persistResolvedRoute于网络线程；默认cache开启通常回到专用IO，不能称网络线程JDBC在所有默认请求必现。
- 最小方案：Gate返回后显式回IO再执行阻塞仓储；配置查询单key合并、短并发预算；保持每调用快照复用。成本小到中；验收cache on/off与留存/附件组合、记录实际线程、超时不重复查Provider。

## 恢复与外围一致性

### R04/R08：提交后窗口与重复请求

- 位置：[ChatRunCompletionCoordinator](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunCompletionCoordinator.java)、[DomainAgentAsyncTaskCallbackApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/DomainAgentAsyncTaskCallbackApplicationService.java)、[ChatRunStartCoordinator](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStartCoordinator.java)。
- 终态commit后未publish即退出：FULL历史正确但在线前端未通知；no-store业务结果本来不可回放。开始请求提交后HTTP响应丢失：盲目NEXT重试可能重复消息；不能借feedback幂等来推定全部接口幂等。
- R04最小运营措施：终态缺口监控，客户端按状态+Resume恢复、重连抖动；需要服务端可靠推送时再引入outbox/确认协议，成本较大。不能把no-store改为悄悄存真实结果。
- R08最小措施：前端保留提交状态先查询再重试；后续按user/requestId建立准入幂等需协议和持久化设计。验收kill在commit/response/publish各间隙，统计重复与恢复。

### R13/R14：治理不能保证所有挂起立即恢复

- 位置：[MyBatisChatRunExecutionRepository](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/MyBatisChatRunExecutionRepository.java) L139、[ChatRunExecutionMapper](../../../src/main/resources/mapper/persistence/ChatRunExecutionMapper.opengauss.xml) L127/L227、[ChatRunRecoveryOrchestrator](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunRecoveryOrchestrator.java) L116/L234/L318、[ChatRunWatchdogScheduler](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunWatchdogScheduler.java) L59。
- R13：scan/claim缺独立SQL期限，慢claim可持permit、single-flight及operational scheduler worker；巡检是延时投递后同步扫描，不是独立恢复工作池。Interaction对账/初始化孤儿/async到期不全受4并发、20/scan及5/tenant覆盖，预算计成功不等于尝试预算。
- 最小修复：claim/scan各有DB期限，所有治理分支共享本轮工作/时间预算并计尝试，保留session→run/execution锁顺序，避免治理压主业务。验收锁阻塞后下一轮可继续，孤儿积压不绕过预算。
- R14：跨实例stop先提交CANCELLING，stopping实例退出未提交execution终态，健康owner仍续lease；quiet流没有新业务Event，watchdog仅看过期lease不能马上收口。删除commit后stop调度也有同类丢失窗口。
- 最小修复：为CANCELLING持久状态加有界收口治理，或heartbeat同时识别取消；删除会话残留active单独对账。成本中，须不与自然完成/async回调形成双终态。验收kill停止实例、保活旧owner、没有业务帧，验证规定SLO内收口。

### R17/R18：历史与外围写入的一致性

- R17位置：[ChatStreamApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatStreamApplicationService.java) L324/L344、[ChatSessionMapper](../../../src/main/resources/mapper/session/ChatSessionMapper.opengauss.xml) L90。Resume只查归属存在，WS只查Run归属，未统一排除DELETED；是本人删除语义绕过，不是已证明跨用户读取。
- 最小修复：共享可访问Session状态校验，WS也验证Run所属Session；已有连接删除后按既有取消/断订阅规则收口。成本小，先明确删除审计读取政策；验收删除后HTTP/SSE/WS一致拒绝。
- R18位置：[SessionApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionApplicationService.java) L848；[ChatShareApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/share/ChatShareApplicationService.java) L69；[SelectedChatShareApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/share/SelectedChatShareApplicationService.java) L90。
- 分支复制中途失败留下部分session；并发share先读旧session、delete撤销commit、share最后INSERT，使撤销未覆盖新share。
- 最小方案：分享创建遵循同Session锁及锁后未删除检查；分支短事务/有界复制或明确创建状态机，按最大快照限制确定是否可一个事务完成。成本中；故障注入每个复制点、删除/分享并发，两者均需回归权限。

### R19/R20：外部发送与联调样例

- R19位置：[WelinkChatShareDeliveryProvider](../../../src/main/java/com/huawei/it/ex/one/infrastructure/share/WelinkChatShareDeliveryProvider.java) L62/L113、[ChatShareDeliveryApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/share/ChatShareDeliveryApplicationService.java) L88。超时可能已送达，当前无wire幂等标识，投递记录在发送之后。
- 最小方案：先收敛非瞬态重试，固定deliveryId贯穿下游并对账；远端不支持幂等时将超时记UNKNOWN而非自动再次发送。成本中，需下游协议配合；验收已送达但响应丢失与DB记录失败。
- R20仅仓库[local-test-frontend/public/app.js](../../../local-test-frontend/public/app.js) L641/L1635：RECOVER_REQUIRED未使用建议较小游标；requestJson只看HTTP成功，ACCESS_DENIED可能当DTO。生产前端没有在本任务验证。
- 最小修复：采用details.recoveryAfterSeq重新订阅并重置订阅状态，按event身份去重；识别业务错误code。成本小；验收延迟seq100、本地105、建议99，以及HTTP200 ACCESS_DENIED，不能跳过缺失事件或误更新UI。

### R23/R24：Session管理的剩余边界

- R23位置：[SessionApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionApplicationService.java) L401/L407。单删/批删是普通`@Transactional`，没有重命名/归档/恢复的10s配置；默认也未设置全局statement期限。最多100个Session锁内串行数据库操作，遇锁等待/慢DB可延长连接和所有已获取Session锁的占用。Redis已移到提交后，这不是原Redis问题复现，也不证明死锁。
- 最小修复：沿用现有有界事务配置覆盖两个删除代理入口，并核实驱动statement/lock/socket期限；保留稳定锁序与all-or-nothing，不随意拆批破坏协议。成本小，超时将整体回滚，需要前端按未知提交结果查询后重试。验收慢SQL、外部持锁、反向批次、删除与准入竞态，确认超时后连接/锁释放。
- R24位置：同文件L836 `selectPath()`；只检查归属/未删除/节点存在后直接更新leaf，没有active Run校验、Session锁后重验或预期leaf条件。运行中或与准入并发切换路径时，选择与终态落叶可相互覆盖，前端可看到路径跳回或旧候选STALE_SOURCE。这里是字段级leaf更新，不应夸大为已经证明覆盖专家scope或node order。
- 最小方案：先明确运行中是否允许切换历史视图；若要求变更实际active path，沿用Session短事务，锁后检查active Run及节点，再更新leaf。仅查看版本不必改变active path。成本小到中，增加并发冲突响应需前端配合；验收path与NEXT/终态/删除并发，失败不改变路径。未进行真实DB竞争测试。

### R25：标题旁路的完整生命周期缺少预算

- 位置：[SessionTitleApplicationService.schedule/collectCandidate/generateAndCommit](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionTitleApplicationService.java) L84/L110/L164；[SessionTitleCommitService.apply](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionTitleCommitService.java) L30；逐步证据见[TT01-TT19](session-title-flow.md)。
- 默认标题关闭，不影响未启用部署。开启后在Run准入提交及Run缓存同步后、Execution初始化前调度。结果不被主Run等待，但专用4平台线程/每线程128排队参数仍执行Session Q、完整轻量路径Q和关联Run批量Q；这些发生在8个生成许可之前，没有独立候选收集SQL/排队总期限。“最多前三个问题”限制出站问题条数，不限制路径读出行数和Run批量ID规模。
- 生成阶段具timeout/许可，默认HTTP Provider要求显式timeout≤30s，无即时重试；但不可中断token可在逻辑超时后继续占线程。默认App排除Provider仅查本地配置，没有外部HTTP；可替换Provider的期限需要另验，不能臆造为现有远程调用。
- 提交阶段独立TX2s锁Session并重读，正确校验ACTIVE/人工标题/nodeOrder。其排队和锁等待不受8个生成许可保护；生成后丢任务/进程退出没有可靠补跑。标题成功不更新排序时间、不发专用WS。上述正确性保护应保留，不能把所有旁路风险泛化成错误覆盖。
- 影响：长历史、多Session突发或慢DB时共享Hikari10可能被旁路占用，同Session准入与标题提交互相等待。当前证据不证明永久死锁或已达OOM；风险是排队、共享连接与锁占用，需要混合压测定级。
- 最小加固建议：先为候选收集加只读DB期限及调度等待预算，在完整任务入口限制/合并同Session候选，过期任务不迟到查DB；维持生成许可与TX2s，鉴权实现加底层IO期限。可靠补跑仅在业务SLO需要时另设计，不为标题直接引入通用Outbox。成本小到中，默认关闭保持兼容。
- 验收：阻塞候选Q、token和提交锁分别测试；统计candidate_rows/run_ids、queued/generated/applied/skipped、Hikari pending和Run首事件P99；人工改名/旧版本晚到不覆盖。kill在生成后到提交前，确认当前语义为保留原标题而非承诺自动重跑。

## 建议实施顺序

1. **上线前边界**：R21/R22信任边界；R09接收线程上限；R01/R03容量护栏；R05取得Relay代次隔离验收。可临时用网关配额降风险，但不宣称替代最终修复。
2. **资源隔离**：R07事务DB-only、R10/R23 SQL/排队/事务期限、R13治理预算；启用标题时同步验收R25完整旁路预算；R02/R11/R12按混合负载再调限额，优先减等待而非放大队列。
3. **恢复与一致性**：R14取消治理、R17/R18/R24删除与外围原子性；R04/R08/R19需要更强语义时另立协议/持久化设计，不在一次小修中引入全系统Outbox。
4. **运维闭环**：每项改动有指标、故障注入、开关/回滚、回归基线；见[验证和上线运行册](verification.md)。
