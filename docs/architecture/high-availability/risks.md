# 高可用风险与加固任务

本轮聚焦OOM、CPU、线程/锁停滞、洪峰和依赖失效引起的服务不稳定，收敛为 **21项主风险、13个待实施工作包**。每项按“触发 → 故障传播 → 资源/服务影响 → 已有保护与缺口 → 措施验收”记录。[唯一追踪矩阵](#traceability)连接风险、工作包、测试、运行册和演练；源码细节见本页，调用关系见[场景图](scenarios.md#flows)。

<a id="risk-register"></a>
<a id="risk-register--当前实现高可用风险登记"></a>
## 当前风险与证据边界

复核日期 **2026-09-22**；文档修改起点 `975db466`，代码基线 `8f48d6cc084be91bcbaad90be43dac7181636cb4`。本轮只修订落地方案，未改业务代码、未执行大文件/OOM或故障注入。默认值不是生产有效配置；容量阈值和真实故障行为均待验证，已执行检查见[证据](evidence.md)。

<a id="risk-register--证据状态与关闭规则"></a>
### 证据与范围

- **S源码确认**：实现/默认配置可定位；不能仅凭模式认定已经OOM、死锁或泄漏。**E环境待验**：有效配置、容量、外部实现/协议及故障行为未验。本轮没有新增故障复现证据。**OPEN/ENV**均未关闭；P1是在相应功能/容量启用前的阻断项，P2为条件性优化/治理项，可由实际影响上调。
- **U用户确认现状**：ChatService、relayService、agentService三服务运行于ADS，共享DB（openGauss）及Redis；agentService含admin技能管理、运行时技能查询、统一chat转发DomainAgent、供Relay调用的MCP。Chat直接调用Relay和agentService，intentService（第三方）可选。WCM托管静态资源，ALB管理文根，上游包括saas gateway（SaaS统一网关）。
- **P目标/措施**：未来adminService/toolService/agentService拆分，加Chat/Relay共五服务；文档管理迁agentService，内容直传合适存储或独立worker。各Region ADS控制/运行面与ALB独立、至少双AZ多副本、双Region预部署热备单写、DB区域内HA/跨区异步复制、GSLB/DNS受控切流、独立非ADS静态备用源均须实施/验证，不能作为已存在保护；不默认全面读写分离。
- 资源收口需要终态、取消、恢复及防双主边界，但本轮不展开纯业务完整性、通用幂等/副作用补偿或安全专项。移出的旧编号**不代表已整改或接受风险**，去向见[编号变更](#risk-dispositions)。
- 全盘范围由[场景及资源覆盖](scenarios.md)衡量，不能证明不存在未知风险。源码确认、真实依赖实验、生产证据分别保存；关闭需修复提交/有效配置、关联T/D结果、资源曲线、复核人与日期。未完成项须具名接受、容量/功能限制、监控及到期日，当前无已签认接受记录。

<a id="traceability"></a>
<a id="risk-register--场景风险措施测试运行册演练追踪矩阵"></a>
### 唯一追踪矩阵

S链接定位[当前场景](scenarios.md)，DEP链接定位[部署方案](deployment.md)；不借用旧版步骤号。T/RB/D分别定位[测试](tests.md)及[运行册/演练](operations.md)。主责以角色登记，实施前须补具名负责人。

| 风险 | 状态/优先级 | 场景 | 工作包 | 主责 | 测试 | 运行册 | 演练 |
|---|---|---|---|---|---|---|---|
| [R01](#r01) 流式队列/正文 | OPEN/P1条件性 | [S03](scenarios.md#s03) | [W01](#w01) | 应用 | [T01](tests.md#t01) | [RB01](operations.md#rb01) | [D01](operations.md#d01) |
| [R02](#r02) 全量恢复/重连风暴 | OPEN/P1条件性 | [S07](scenarios.md#s07) | [W03](#w03) | 应用、前端 | [T02](tests.md#t02) | [RB06](operations.md#rb06) | [D05](operations.md#d05) |
| [R03](#r03) 完整等待预算 | OPEN/P2 | [S02](scenarios.md#s02)、[S10](scenarios.md#s10)、[S11](scenarios.md#s11) | [W05](#w05)/[W07](#w07) | 应用、鉴权/下游 | [T03](tests.md#t03) | [RB05](operations.md#rb05) | [D04](operations.md#d04) |
| [R04](#r04) Redis接收线程 | OPEN/P1条件性 | [S03](scenarios.md#s03)、[S07](scenarios.md#s07) | [W02](#w02) | 应用 | [T04](tests.md#t04) | [RB04](operations.md#rb04)/[RB01](operations.md#rb01) | [D03](operations.md#d03) |
| [R05](#r05) 查询/旁路资源 | OPEN/P2 | [S08](scenarios.md#s08)、[S10](scenarios.md#s10)、[S11](scenarios.md#s11) | [W08](#w08) | 应用、DBA | [T05](tests.md#t05) | [RB03](operations.md#rb03)/[RB02](operations.md#rb02) | [D08](operations.md#d08) |
| [R06](#r06) 文件完整生命周期 | OPEN/P2；大文件门槛P1 | [S09](scenarios.md#s09) | [W06](#w06) | 应用、存储 | [T06](tests.md#t06) | [RB07](operations.md#rb07)/[RB01](operations.md#rb01) | [D06](operations.md#d06) |
| [R07](#r07) CPU/JSON/分配 | OPEN/P2条件性 | [S01](scenarios.md#s01)、[S03](scenarios.md#s03)、[S06](scenarios.md#s06) | [W01](#w01)/[W08](#w08) | 应用、性能测试 | [T07](tests.md#t07) | [RB02](operations.md#rb02)/[RB01](operations.md#rb01) | [D01](operations.md#d01) |
| [R08](#r08) 部署/健康/退出 | ENV/P1上线门槛 | [S12](scenarios.md#s12) | [W09](#w09)/[W07](#w07) | 平台、应用 | [T08](tests.md#t08) | [RB08](operations.md#rb08) | [D07](operations.md#d07) |
| [R09](#r09) 共享DB/洪峰/治理 | OPEN+ENV/P1条件性 | [S01](scenarios.md#s01)、[S12](scenarios.md#s12)、[DEP01](deployment.md#dep01) | [W04](#w04)/[W11](#w11) | DBA、三服务负责人 | [T09](tests.md#t09) | [RB03](operations.md#rb03)/[RB11](operations.md#rb11) | [D09](operations.md#d09) |
| [R10](#r10) 共享Redis/回源 | ENV/P1条件性 | [S03](scenarios.md#s03)、[S07](scenarios.md#s07)、[DEP01](deployment.md#dep01) | [W11](#w11) | Redis平台、三服务负责人 | [T10](tests.md#t10) | [RB11](operations.md#rb11)/[RB04](operations.md#rb04) | [D09](operations.md#d09) |
| [R11](#r11) 独立静态备用源 | ENV/P2入口条件 | [S01](scenarios.md#s01)、[DEP04](deployment.md#dep04) | [W12](#w12) | WCM、存储、前端 | [T11](tests.md#t11) | [RB12](operations.md#rb12) | [D10](operations.md#d10) |
| [R12](#r12) ALB/网关/入口 | ENV/P1条件性 | [S03](scenarios.md#s03)、[S07](scenarios.md#s07)、[DEP04](deployment.md#dep04) | [W12](#w12) | 网络、ALB、应用 | [T12](tests.md#t12) | [RB12](operations.md#rb12) | [D10](operations.md#d10) |
| [R13](#r13) ADS控制/运行面 | ENV/P1条件性 | [S12](scenarios.md#s12)、[DEP02](deployment.md#dep02) | [W13](#w13) | ADS、服务负责人 | [T13](tests.md#t13) | [RB13](operations.md#rb13) | [D11](operations.md#d11) |
| [R14](#r14) AZ失效余量 | ENV/P1条件性 | [S12](scenarios.md#s12)、[DEP02](deployment.md#dep02) | [W13](#w13) | 平台、容量、DBA | [T14](tests.md#t14) | [RB13](operations.md#rb13) | [D11](operations.md#d11) |
| [R15](#r15) Region接管/回切 | ENV/P1容灾门槛 | [S12](scenarios.md#s12)、[DEP05](deployment.md#dep05)、[DEP06](deployment.md#dep06) | [W14](#w14) | 容灾指挥、平台、DBA | [T15](tests.md#t15) | [RB14](operations.md#rb14) | [D12](operations.md#d12) |
| [R16](#r16) agent模块/配置回源 | OPEN+ENV/P1条件性 | [S02](scenarios.md#s02)、[S12](scenarios.md#s12) | [W11](#w11)/[W05](#w05) | agent、应用、平台 | [T16](tests.md#t16) | [RB11](operations.md#rb11)/[RB05](operations.md#rb05) | [D09](operations.md#d09)/[D04](operations.md#d04) |
| [R17](#r17) MCP扇出/取消残留 | ENV/P1条件性 | [S02](scenarios.md#s02)、[S05](scenarios.md#s05) | [W11](#w11)/[W05](#w05)/[W07](#w07) | Relay、agent、下游 | [T17](tests.md#t17) | [RB11](operations.md#rb11)/[RB05](operations.md#rb05) | [D09](operations.md#d09)/[D04](operations.md#d04) |
| [R18](#r18) DB/JVM锁与事务 | OPEN+ENV/P2条件性 | [S03](scenarios.md#s03)、[S05](scenarios.md#s05)、[S06](scenarios.md#s06)、[S08](scenarios.md#s08) | [W04](#w04) | 应用、DBA、平台 | [T18](tests.md#t18) | [RB02](operations.md#rb02)/[RB03](operations.md#rb03) | [D02](operations.md#d02) |
| [R19](#r19) DomainAgent故障 | OPEN+ENV/P1启用条件 | [S02](scenarios.md#s02)、[S03](scenarios.md#s03)、[S05](scenarios.md#s05) | [W05](#w05)/[W07](#w07)/[W11](#w11) | 应用、agent/DA | [T19](tests.md#t19) | [RB05](operations.md#rb05) | [D04](operations.md#d04) |
| [R20](#r20) Intent重试/兜底放大 | OPEN/P1启用条件 | [S02](scenarios.md#s02) | [W05](#w05)/[W11](#w11) | 应用、Intent、Relay | [T20](tests.md#t20) | [RB05](operations.md#rb05) | [D04](operations.md#d04) |
| [R21](#r21) Relay连接/取消残留 | OPEN+ENV/P1条件性 | [S02](scenarios.md#s02)、[S03](scenarios.md#s03)、[S05](scenarios.md#s05) | [W05](#w05)/[W07](#w07)/[W11](#w11) | 应用、Relay | [T21](tests.md#t21) | [RB05](operations.md#rb05) | [D04](operations.md#d04) |

<a id="risk-register--风险复核明细"></a>
## 风险明细

<a id="risk-register--r01-流式桥接队列和累计正文缺少总量边界"></a>
<a id="r01"></a>
#### R01 流式桥接队列和累计正文缺少总量边界

- **证据 S**：[Relay BUFFER](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java#L236)在 L265/L301/L649 显式使用 BUFFER；[DomainAgent 总期限包装](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/domainagent/ConfiguredDomainAgentClient.java#L180)使用 `Flux.create` 并内部直接订阅上游；[事件流水线](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatEventPipeline.java#L76)切换 Event IO 后顺序持久化；[Assembly](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/AssistantAssembly.java#L123)持续 append，Parts 在 L120 增长。
- **触发与影响**：下游持续快于事件落库或生成超长回答，桥接排队、累计草稿和 Parts 同时驻留；可能引起堆耗尽、GC、首事件/Stop 变慢，影响同 JVM 全功能。未进行 OOM 压测，阈值 E。
- **已有保护/剩余缺口**：单帧大小、下游并发、总运行期限、批次边界及 dispose 清理存在；它们不等同每 Run/实例累计字节上限。placeholder/no-store在Assembly跳过真实正文和业务Parts累计，但帧解析、标准化及队列仍分配对象，不能套用FULL正文累计模型，也不能视为零内存开销。
- **加固措施**：[W01](#w01)；验收条件见下。
- **关闭证据（待取得）**：T01/D01 在快流＋慢 DB、长正文、取消/异常下证明内存和队列有界、超限可解释地收口、正常事件不丢；提供队列字节、堆/GC、许可与残留订阅曲线。

<a id="risk-register--r02-resume-全量物化且-http-恢复缺少独立配额"></a>
<a id="r02"></a>
#### R02 历史全量恢复与重连洪峰放大资源占用

- **证据 S**：[resumeSession/resumeRunTopic](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatStreamApplicationService.java#L251)及[resumeRunWithLiveTail](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatStreamApplicationService.java#L429)从完整List回放；[Store](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/MyBatisChatEventStore.java#L346)先转换所有行，[SQL](../../../src/main/resources/mapper/persistence/ChatEventMapper.opengauss.xml#L124)无LIMIT。L364已有有界窗口，但不表示上述Resume已分页。[提交后缓存/发布](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunCompletionCoordinator.java#L189)有进程退出窗口；[联调样例RECOVER_REQUIRED](../../../local-test-frontend/public/app.js#L641)未采用建议游标，生产前端行为为E。
- **触发→传播→影响**：长Run、afterSeq=0、多页签或commit后未通知使客户端集中补读/反复重连；全量查询、JSON物化和live订阅同时增加，占DB、堆、CPU及连接，拖慢新Run和Stop。HTTP恢复不受WS连接数配额充分约束。
- **已有保护/缺口**：归属检查、先订阅live、有界实时缓冲、去重/短窗口重排已有；历史总量、恢复并发与客户端退避仍需约束。sequence是全局游标，不能按每topic的seq+1等待。FULL可补读实际已存事件；no-store不可恢复正文，不能因恢复需求增加正文持久化。
- **措施/验收**：[W03](#w03)。T02/D05覆盖长历史、提交后退出、HTTP/WS混合恢复、跨实例与Redis重连；证明历史及衔接缓冲有界、无循环补读，取消归还资源且新Run/Stop达标。阈值、生产前端和容量证据待取得。

<a id="risk-register--r03-鉴权重试与业务期限不组成总预算"></a>
<a id="r03"></a>
#### R03 鉴权、排队及同步外呼未组成完整等待预算

- **证据 S**：[阻塞Intent鉴权](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentService.java#L142)、[用例库](../../../src/main/java/com/huawei/it/ex/one/infrastructure/usecase/HttpUseCaseLibraryClient.java#L54)、[WeLink](../../../src/main/java/com/huawei/it/ex/one/infrastructure/share/WelinkChatShareDeliveryProvider.java#L75)在HTTP期限开始前同步取header；WeLink[调用循环](../../../src/main/java/com/huawei/it/ex/one/infrastructure/share/WelinkChatShareDeliveryProvider.java#L59)还会重试失败尝试。流式Intent[鉴权](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentStreamClient.java#L154)已使用独立scheduler及timeout。
- **触发→传播→影响**：token resolver、排队、DNS/网络或同步SDK阻塞，上层超时后底层仍运行；重提和重试叠加，累积线程、连接和许可，最终挤占聊天和治理。该资源风险包含外部投递失败重试，不扩展为完整投递正确性工程。
- **已有保护/缺口**：多个provider有bulkhead、HTTP超时，流式鉴权有独立有界资源，候选查询有自己的退避策略；这些不能限制所有前置等待，也不证明不可中断resolver/JDBC/SDK已停止。单次HTTP、单Run与一次逻辑请求的时钟不同，依赖专项事实见R19–R21。
- **措施/验收**：[W05](#w05)，残留清理协同[W07](#w07)。T03/D04分别阻塞鉴权、排队和网络，记录总耗时、实际尝试及超时后线程/连接/许可；证明底层有界退出或进入受限治理，不只验收调用方收到timeout。有效配置与实测待取得。

<a id="risk-register--r04-redis-入站-listener-没有显式有界执行器"></a>
<a id="r04"></a>
#### R04 Redis 入站 listener 没有显式有界执行器

- **证据 S**：[构造器](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java#L73)手工创建 listener，仅设 connectionFactory；前次对本地 Spring Data Redis 3.4.6 `createDefaultTaskExecutor` 及 Spring Core 6.2.7 字节码复核：默认 `SimpleAsyncTaskExecutor`，concurrencyLimit=-1，未设置 virtual delegate。属于静态依赖确认，不是本轮消息压力实验。
- **触发与影响**：突发 Pub/Sub fanout 或 handler 解析变慢，默认逐任务线程模型可能大量创建平台线程，消耗 native memory/CPU，影响整个 JVM 和跨实例实时链路。
- **已有保护/剩余缺口**：[发布端](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java#L152)有排队/恢复标记，前端 WS 发送端也有界；不能保护 Redis 接收执行器。
- **加固措施**：[W02](#w02)；验收条件见下。
- **关闭证据（待取得）**：T04/D03 阻塞 handler 后高 fanout，线程/队列有界、顺序/去重正确、FULL 可补读、no-store 丢失信号明确；配置有效值必须纳入证据。

<a id="risk-register--r05-长历史版本及摘要查询随数据增长"></a>
<a id="r05"></a>
#### R05 长历史查询与可选旁路抢占CPU、线程和数据库

- **证据 S**：[版本递归](../../../src/main/resources/mapper/memory/ChatMessageMapper.opengauss.xml#L402)、[首assistant](../../../src/main/resources/mapper/memory/ChatMessageMapper.opengauss.xml#L472)读取完整列；[列表](../../../src/main/java/com/huawei/it/ex/one/infrastructure/session/MyBatisSessionRepository.java#L169)页上限200仍有count/offset/匹配成本；[最后Run](../../../src/main/resources/mapper/persistence/ChatRunMapper.opengauss.xml#L524)返回完整metadata。旧[偏好写](../../../src/main/java/com/huawei/it/ex/one/application/service/routing/IntentPreferenceCorrectionApplicationService.java#L85)仅subscribeOn，读侧[timeout](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/IntentPreferenceCorrectionLoader.java#L66)不证明JDBC取消。标题[schedule/collectCandidate](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionTitleApplicationService.java#L84)在生成许可前读取完整轻量路径及Run（L164–211）。
- **触发→传播→影响**：深树/多版本、大正文、模糊搜索、深分页或启用旁路后的突发，使扫描、递归、序列化、排队及锁等待增长；主聊天、Stop和治理争共享CPU、worker与DB连接，过期任务仍执行又加剧恢复积压。
- **已有保护/缺口**：关键字短查询、最后Run先索引字段后回表、旧偏好[有界池](../../../src/main/java/com/huawei/it/ex/one/application/config/IntentPreferenceExecutorConfiguration.java#L28)、新[反馈dispatcher](../../../src/main/java/com/huawei/it/ex/one/application/service/routing/IntentFeedbackTaskDispatcher.java#L30)的排队期限须保留。标题默认关闭，生成8许可/timeout、[提交2s事务](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionTitleCommitService.java#L26)、[4线程/128排队](../../../src/main/java/com/huawei/it/ex/one/infrastructure/sessiontitle/SessionTitleProviderConfiguration.java#L31)已有；“前三问”不限制前置读取规模，生成许可不覆盖候选/提交全程。有行数上限不等于SQL成本或响应字节有界。
- **措施/验收**：[W08](#w08)。T05/D08覆盖长历史/深树/大metadata/搜索，以及旧旁路worker阻塞、标题候选/生成/提交分别变慢；记录计划、扫描量、队列龄、CPU、连接与首事件，保证旁路启用/关闭均符合预算、过期未启动任务不再执行。执行计划与阈值待验证。

<a id="risk-register--r06-上传堆占用下载许可生命周期及孤儿对象"></a>
<a id="r06"></a>
#### R06 上传堆占用、下载许可生命周期及孤儿对象

- **证据 S**：[API Store upload](../../../src/main/java/com/huawei/it/ex/one/infrastructure/storage/api/ApiStoreDocumentStorage.java#L67)许可内 L71 整份 `readAllBytes`、L97 将同一数组交给 multipart，L112 阻塞等待 HTTP；[入口辅助](../../../src/main/java/com/huawei/it/ex/one/interfaces/document/upload/DocumentUploadSupport.java#L63)在 MVC multipart 已解析后，L120 再复制临时文件，L140 才检查业务大小并传入流，此阶段早于存储许可。[对象存储 download](../../../src/main/java/com/huawei/it/ex/one/infrastructure/storage/object/ObjectStorageDocumentStorage.java#L74)获得输入流即归还许可，[Controller](../../../src/main/java/com/huawei/it/ex/one/interfaces/document/DocumentController.java#L179)之后响应输出；[上传服务](../../../src/main/java/com/huawei/it/ex/one/application/service/document/DocumentApplicationService.java#L74)存储完成后才写 DB。
- **触发与影响**：放大限额到 **500MiB（524288000 字节）** 时，单次 API Store 上传至少存在一份 500MiB 内容数组；32 个同时持有此数组的请求约 **15.625GiB**，仅是内容数组的条件下界，不是总堆测量或已支持并发数。额外有解析/HTTP/native buffer、框架与应用临时盘、FD 等消耗，实际乘数取决于 JDK、容器、客户端和配置，不能固定宣称两倍。当前[默认 multipart 50MB/请求60MB](../../../src/main/resources/application.yml#L19)并非已允许500MiB；只改限额可能把磁盘、堆和带宽风险带入整个 Chat JVM。慢下载可超过许可覆盖在途数；存储成功而DB失败/进程退出会留下孤儿对象。
- **已有保护/剩余缺口**：multipart 限额、存储32许可、正常/异常流关闭与 `usingWhen` 清理已有；API Store的阻塞期限不覆盖此前本地完整读入。OBS/本地文件上传使用 InputStream/流复制，不能套用整文件数组结论，也仍须核对SDK阻塞取消、临时盘及下载生命周期。清理异常在辅助类 L155 被忽略、kill 无法执行 finally；软删除不是物理回收策略。文件迁 agentService、直传或独立 worker 是 P，当前仍由 Chat 实现。
- **加固措施**：[W06](#w06)；验收条件见下。
- **关闭证据（待取得）**：T06/D06 在批准容量下测试500MiB及边界、未知长度/分块、慢上传/下载、取消、DB失败/进程退出；分别测堆/native/临时盘/FD/连接，不在生产尝试OOM。准入发生于昂贵接收/复制前，许可和临时文件最终归还，孤儿可对账且不误删历史引用。

<a id="risk-register--r07-高频-json序列化与长对象造成条件性-cpu分配压力"></a>
<a id="r07"></a>
#### R07 高频 JSON、序列化与长对象造成条件性 CPU/分配压力

- **证据 S，故障 E**：[DTO](../../../src/main/java/com/huawei/it/ex/one/interfaces/chat/dto/CreateChatRunRequest.java#L48)限制正文20000、metadata顶层50字段，但没有业务层嵌套总字节预算；[批次估算](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatEventBatcher.java#L158)完整序列化，发布端 [writeValueAsString](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java#L152)再次序列化；[正文/卡片物化](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/AssistantAssembly.java#L235)复制字符串和 payload；[协议解析](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/domainagent/DomainAgentResponseNormalizer.java#L71)也消耗解析/复制 CPU。
- **触发与影响**：大嵌套 metadata、合法上限帧的高频流、多 Parts/长草稿、扇出及重试叠加时，序列化、分配和 GC 可能形成 CPU 瓶颈，导致事件循环/治理延迟。尚无 profile，不声称 CPU 已飙升、算法必为二次复杂度或 JSON 无任何底层限制。R09 的高租户基数驻留对象作为长期分配观测维度。
- **已有保护/剩余缺口**：字段/帧/回调体积上限、批次和许可已有，Jackson 自带约束需按生效配置核对；单项上限不构成吞吐或累计字节预算，Reactive timeout 也不抢占同步 JSON 运算。异步回调[标准化在事务前](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/DomainAgentAsyncTaskCallbackApplicationService.java#L109)，但[assembly与事件/消息持久化](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/DomainAgentAsyncTaskCallbackCommitService.java#L75)仍在锁后事务内，不能统称所有CPU工作均在锁外；持锁时间需与 R18 联合测量。
- **加固措施**：[W01](#w01)、[W08](#w08)；验收条件见下。
- **关闭证据（待取得）**：T07/D01 合法最大值和超限值、细碎帧、高频控制帧/长正文、旁路叠加，给出热点前后对比及资源上界，拒绝发生在昂贵操作前，正常结果不改变。

<a id="risk-register--r08-已确认拓扑的部署参数探针和运营行为未获环境验证"></a>
<a id="r08"></a>
#### R08 已确认拓扑的部署参数、探针和运营行为未获环境验证

- **证据 U/S/P/E**：U已确认当前三服务在ADS并共享DB（openGauss）/Redis，以及ALB文根与WCM托管职责；各Region ADS控制/运行面与ALB独立、跨AZ多副本、跨Region热备单写和独立WCM备用源为目标P，见[部署依据](deployment.md)。基础拓扑不再列作“完全未知”。S：[本地配置](../../../src/main/resources/application.yml#L1)提供Tomcat/虚拟线程/连接等默认值；[健康配置](../../../src/main/resources/application.yml#L39)DB默认关闭、Redis关闭。E：尚未取得ADS实际部署/探针导出、ALB/saas gateway重试/idle/ACL、有效资源参数及切换演练证据，不能据仓库缺少deployment文件推断平台没有这些能力。
- **触发与影响**：探针仍绿但关键依赖/后台任务失效，滚动发布未排空导致 WS/Run 大量中断，容器资源低于默认预算、实例/依赖同故障域、日志/临时磁盘满、证书/配置变更后不可恢复；可扩大为全服务故障。
- **已有保护/剩余缺口**：源码已有关闭回调、租约/Watchdog、Actuator依赖；Region控制/运行面及ALB独立、多AZ、热备等P待实施/验收，不能据此证明真实readiness/liveness、容量余量、DB复制/备份恢复和SLO达标。共享依赖/入口/平台/AZ/Region专项问题分别由R09/R10/R11–R15跟踪，避免用一条“部署未知”掩盖具体边界。
- **加固措施**：[W09](#w09)；验收条件见下。
- **关闭证据（待取得）**：T08/D07 单实例终止、滚动发布/回滚、依赖切换、配置/证书轮换和容量/磁盘告警演练；同时证明服务恢复与遗留任务收口，提供批准的 SLI/SLO/RTO/RPO 和真实部署值。

<a id="risk-register--r09-四服务共享-db-的争用会跨服务传播"></a>
<a id="r09"></a>
#### R09 三服务共享DB、洪峰准入与治理争用

- **证据 U/S/E**：U确认ChatService、relayService、agentService共享DB（openGauss）。S：[Chat Hikari](../../../src/main/resources/application.yml#L29)默认10连接、借用500ms；[Run准入](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/RunAdmissionControlService.java#L55)为用户窗口＋本机租户semaphore，无跨租户总Run许可，L60租户条目未见淘汰（用户窗口L90已有清理）。[Watchdog](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunRecoveryOrchestrator.java#L116)先处理Interaction/初始化孤儿/async再stale，[scan/claim](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/MyBatisChatRunExecutionRepository.java#L138)未有独立期限；[single-flight调度](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunWatchdogScheduler.java#L55)同步占operational线程。外部服务SQL/池与有效容量为E。
- **部署证据 S/E**：[sql.init=never](../../../src/main/resources/application.yml#L35)，[启动校验](../../../src/main/java/com/huawei/it/ex/one/application/config/FinanceExDatabaseSchemaValidator.java#L52)只覆盖关键active唯一索引；[性能索引迁移](../../../src/main/resources/db/incremental-20260826-chat-run-last-status-index.sql#L1)需事务外执行。不能断言生产缺索引，也不能当作已验证完成。
- **触发→传播→影响**：多租户洪峰、慢SQL/锁、DDL、批任务、辅助接口、治理积压叠加，或扩容/AZ/Region切换同时建连，耗尽共享连接/CPU/IO；三服务和Stop/心跳/终态相互拖累。高租户基数还可能持续保留准入对象。流量在进入Run前也可能已占解析、鉴权及文件资源。
- **已有保护/缺口**：Chat本机bulkhead、短事务、借用超时、Watchdog jitter/single-flight/fencing和常规恢复限额已有；不能推成全集群预算或所有治理分支均受同一许可/次数限制。借用超时不限制已借到连接的SQL，虚拟线程不增加DB容量；账号隔离不能隔离底层资源。HA、性能索引和外部池均需环境取证。
- **措施/验收**：[W04](#w04)落实Chat准入/公平治理/索引，[W11](#w11)落实三服务总预算。T09/D09逐服务及agent模块施压，含失败尝试积压、扩缩容、恢复回压、索引有效性；证明DB总额不被实例数绕过、控制链路不饥饿、下一轮治理按期完成、连接和准入表回到预算。

<a id="risk-register--r10-四服务共享-redis-的资源竞争与重建风暴"></a>
<a id="r10"></a>
#### R10 当前三服务共享 Redis 的资源竞争与重建风暴

- **证据 U/S/E**：U确认当前三服务共享Redis。S：[Chat配置](../../../src/main/resources/application.yml#L51)将Redis用于缓存、取消标记、恢复锁优化和实时fanout，DB为事实源；R04/R16仍有接收线程和并发miss风险。relayService/agentService各模块的键、锁、队列及持久性要求未审计，属于E；不能一概断言所有服务的Redis数据都可丢弃重建。
- **触发与影响**：某服务大key/热key、Lua/批量操作、内存淘汰、Pub/Sub突发或实际部署拓扑下的故障切换拖慢所有服务；恢复时共同回源DB形成二次故障。跨Region盲复制锁、取消标记或持久任务队列可能造成旧任务重新执行和恢复流量失控。
- **已有保护/剩余缺口**：Chat有Redis短期限、部分缓存回源及DB fencing；Pub/Sub不提供持久交付。逻辑key前缀/ACL不能保证共享实例的容量和延迟隔离，实际maxmemory/淘汰/持久化与各服务语义仍待核验。
- **加固措施**：[W11](#w11)；验收条件见下。
- **关闭证据（待取得）**：T10/D09逐服务热key/内存/订阅洪峰和Redis切换，确认其他服务响应、DB回源限额、恢复后无旧锁控制新执行；各类数据恢复均有owner和证据，no-store正文缺口明确。

<a id="risk-register--r11-wcm备用静态源可能缺失陈旧或共享故障域"></a>
<a id="r11"></a>
#### R11 WCM备用静态源可能缺失、陈旧或共享故障域

- **证据 U/P/E**：U确认WCM托管Web静态资源；P选定独立于ADS的备用HTTPS静态源、预发布对象存储产物和DEP04切换/版本验证流程。该源用于网页/静态资源，不接管API和后台执行；对象存储API本身不等于具备HTTPS静态托管能力。E包括主备产物一致性、ALB源站接入、对象存储访问/跨Region能力、域名/证书及独立发布权限，仓库未验证WCM/对象存储配置。
- **触发与影响**：WCM异常时备用首页缺少bundle/运行配置、缓存混版、签名URL过期、备用域名证书失效，或备用实际依赖故障ADS上的动态拼装。网页空白、资源404、错误API地址或登录跳转使用户无法进入系统；静态页面可打开也不证明聊天可用。
- **已有保护/剩余缺口**：P选择独立托管和预发布以消除“故障时才依赖ADS发布备用”的前提；在完成源站接入、独立性及版本完整性验收前，不能将其列为已经可用的故障保护。
- **加固措施**：[W12](#w12)；验收条件见下。
- **关闭证据（待取得）**：T11/D10在WCM与ADS相关路径不可用时，仅用已预发布备用源完成首页/静态资源加载、登录跳转及目标API连通性核对；故障前已有版本清单，回切无混版/缓存污染，API本身失败单独报告。

<a id="risk-register--r12-alb文根路由流式连接和区域入口切换不一致"></a>
<a id="r12"></a>
#### R12 ALB文根路由、流式连接和区域入口切换不一致

- **证据 U/S/P/E**：U确认ALB负责文根，P要求各Region独立ALB并采用GSLB/DNS受控主备入口；S确认Chat提供HTTP、SSE和WS，且[异步HTTP配置](../../../src/main/resources/application.yml#L11)不能替代ALB/saas gateway期限。E为文根/路径前缀、静态源/API目标组、Upgrade、缓冲、idle timeout、摘流和DNS有效缓存行为。
- **触发与影响**：文根重写错误将API落到静态HTML或破坏资源路径；ALB缓冲/idle小于静默期截断SSE/WS；目标退出后老连接仍向旧Region写；DNS缓存和长连接使“入口已切换”与客户端实际落点不同，POST自动重试可能重复执行。
- **已有保护/剩余缺口**：应用超时/Resume提供局部保护，各Region独立ALB及GSLB/DNS受控切换为P；不等于流式转发正确或长连接可迁移。静态源、业务API和管理接口必须按各自路由验证，不能靠首页200判断服务健康。
- **加固措施**：[W12](#w12)；验收条件见下。
- **关闭证据（待取得）**：T12/D10测试嵌套文根、静态资源与API错误路由、静默流/WS、目标摘流、故意缓存旧DNS/保持旧连接及恢复风暴；实际Region落点、写拒绝、首事件与补读结果可核对。

<a id="risk-register--r13-ads控制面故障与运行面故障需要分别应对"></a>
<a id="r13"></a>
#### R13 ADS控制面故障与运行面故障需要分别应对

- **证据 U/P/E**：U确认当前三服务都在ADS；P要求两个Region的ADS控制面/运行面独立，并按故障类型选择冻结发布、保留健康实例或区域接管。E为ADS控制失效时已有实例是否继续运行、调度/扩缩容/配置/密钥下发/镜像拉取的实际依赖及跨Region共用外围组件。
- **触发与影响**：控制面失效可能使发布、调度或证书/配置更新不可用但已有服务仍健康；运行面失效直接中断请求/流。将控制面故障误判为全站失效、或仍依赖失效控制面“紧急扩容/发布备用”，会扩大影响；即使按目标隔离两Region，公用制品/身份/网络依赖仍可能成为共同故障点。
- **已有保护/剩余缺口**：P中的Region间控制/运行面独立需证明，不能据设计推断实际隔离、每Region内控制组件跨AZ或故障时具备管理能力。P中的跨Region预部署热备旨在避免故障时现部署，其版本、凭证和可运行状态仍需E。
- **加固措施**：[W13](#w13)；验收条件见下。
- **关闭证据（待取得）**：T13/D11分别隔离控制面和运行面，证明控制故障时服务能力及限制、备用Region可由独立入口运维、预部署版本/配置可用；禁止以单一控制探针自动切Region。

<a id="risk-register--r14-az失效后剩余容量和依赖拓扑可能不足"></a>
<a id="r14"></a>
#### R14 AZ失效后剩余容量和依赖拓扑可能不足

- **证据 U/P/E**：U确认ADS承载服务；P要求各Region平台独立、每Region至少两个AZ、多副本，且AZ失效后的安全容量/依赖路径通过验收。E为副本/节点实际分布、反亲和、ALB目标/网络、DB/Redis区域内HA位置/仲裁成员和切换行为，以及各服务依赖配额。两个应用AZ是下限，不限定数据仲裁成员只能位于两个AZ，也不自动等于任何AZ退出仍承载原峰值。
- **触发与影响**：单AZ整体丢失后，剩余AZ处理参与服务流量、连接重建和历史恢复，CPU/堆/DB/Redis/下游配额超载；副本看似分散但依赖主节点或出站网络集中也会阻断整Region。恢复时集中重连可二次雪崩。
- **已有保护/剩余缺口**：当前Chat本机限流不提供参与服务全集群公平；P中的多副本和区域内DB HA尚需部署/仲裁验证，更不证明失效后的余量。此风险不预设必须扩容到某个固定倍数。
- **加固措施**：[W13](#w13)；验收条件见下。
- **关闭证据（待取得）**：T14/D11完整AZ隔离并保持峰值或经批准故障期负载，统计参与服务成功率、队列/连接/CPU、控制与恢复时延；故障AZ恢复后的放量也通过，不能只停止一个Pod代替AZ演练。

<a id="risk-register--r15-region失效网络分区复制滞后与回切导致双写丢失"></a>
<a id="r15"></a>
#### R15 Region失效、网络分区、复制滞后与回切导致双写/丢失

- **证据 U/S/P/E**：U确认当前ADS/ALB部署关系；P要求各Region ADS控制/运行面与ALB独立，并采用双Region预部署热备单写、DB区域内HA/跨Region异步复制、GSLB/DNS受控主备、Redis分类恢复及DEP05接管/DEP06回切。S中的[execution fencing](../../../src/main/resources/mapper/persistence/ChatRunExecutionMapper.opengauss.xml#L227)保护同一DB事实上的owner竞争；不能在分裂成两个可写DB时成为跨Region排他证明。真实复制水位、外部执行对账、阻断旧写能力及RTO/RPO为E。
- **触发与影响**：Region失联但旧Region仍可写，备Region未经隔离就提升；复制滞后使已受理/已完成的Run或工具操作在新主不可见；DB与业务附件对象恢复点不一致使引用存在但文件缺失或权限不匹配；缓存/锁/任务队列盲恢复造成旧任务重放；回切以隔离前的水位作为最终门槛、未追平或先放开旧主导致双写、丢结果、重复副作用。影响参与服务及外部DA/Relay/投递任务。
- **已有保护/剩余缺口**：P中的单写和预部署旨在降低接管复杂度，仍须建设和验证；异步复制可能丢最近已提交数据，不能承诺RPO=0。GSLB/DNS切流不撤销旧写连接，DB应用fencing不自动构成跨Region防双主。FULL只恢复实际到达新主的已存事件，no-store正文不因容灾而可回放；[默认Runtime恢复端口](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/UnsupportedAgentRuntimeRecoveryPort.java#L21)不支持接管，历史恢复不能被描述为远端流无缝续跑。
- **加固措施**：[W14](#w14)；验收条件见下。
- **关闭证据（待取得）**：T15/D12分别覆盖Region真实故障、网络分区、可控复制滞后、旧DNS/旧连接持续请求、DB提升失败与回切中断；注入回切预收集水位后至隔离前的迟到提交，证明隔离后的最终位点已完整追平。验证业务附件对象落后于DB、引用/权限不一致及已批准损失的阻断/功能隔离；整个时间轴最多一个可写权威，RPO/UNKNOWN任务可量化，旧owner/迟到回调不覆盖新事实，回切后参与服务、静态入口、业务附件、工具执行和遗留任务均完成核对。

<a id="r16"></a>
#### R16 一体agentService模块争抢与技能配置回源放大

- **证据 U/S/E**：U确认agentService同时承担admin技能管理、运行时技能查询、统一chat→DomainAgent、Relay调用的MCP，与Chat/Relay共享DB和Redis。S：[技能缓存](../../../src/main/java/com/huawei/it/ex/one/application/service/domainagentconfig/DomainAgentSkillConfigurationService.java#L69)失败视为miss、未见并发miss合并；[HTTP provider](../../../src/main/java/com/huawei/it/ex/one/infrastructure/domainagentconfig/DefaultDomainAgentSkillConfigurationProvider.java#L54)有[默认2s期限/10m缓存](../../../src/main/resources/application.yml#L99)，无应用重试，但HTTP期限不覆盖前置缓存/排队。[Gate](../../../src/main/java/com/huawei/it/ex/one/application/service/agentdatapersistence/AgentDataPersistenceGate.java#L91)共用本次配置快照。实际agent内部线程/队列/隔离及配置发布能力为E。
- **触发→传播→影响**：管理导入/批任务、缓存失效的技能查询、长chat和MCP扇出争同一进程及共享依赖；一模块过载拖慢另两条执行链及运行前配置检查。技能映射/缓存发布不一致可能引发错误重试/重路由流量。将500MiB文件转移到同一管理进程只会转移共同故障范围。
- **已有保护/缺口**：Chat的缓存、provider期限、下游许可不能限制外部管理/MCP流量。Gate在留存开关启用时配置失败阻止执行，仅附件检查失败则按现实现开放（L117–128），不能统一当作可丢旁路或关闭留存来降级；成功无配置、畸形附件配置和故障须区分。缓存关闭后provider完成线程还可能进入[同步仓储路由](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRuntimeDispatchCoordinator.java#L204)，应核对调度边界，不能断言默认一定阻塞event loop。
- **措施/验收**：[W11](#w11)建立模块预算、配置合同及拆分，配置等待/回源协同[W05](#w05)，文件协同[W06](#w06)。T16/D09/D04逐模块施压、缓存失效、配置组合和慢库，证明其他模块及Stop达标且留存/附件语义未变；目标拆分为P，必须以同负载复测证明隔离收益。

<a id="r17"></a>
#### R17 Relay经MCP调用下游的扇出、重试及取消残留

- **证据 U/S/E**：U确认relayService→agentService MCP→实际下游；内部扇出、深度、重试和取消为E。S仅确认Chat的[Relay流桥接](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java#L236)及[Stop编排](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStopCoordinator.java#L164)，不能据本地取消完成推断所有MCP子调用停止。
- **触发→传播→影响**：一个Run展开大量工具任务、多层重试相乘；黑洞/慢响应/高频大响应或仅断上游连接后，子任务继续占CPU、堆、线程、连接和共享DB/Redis。重复请求、重连与恢复盲重放未知结果可再次扩大任务数，同时拖累统一chat路径。
- **已有保护/缺口**：Chat的Run期限和Relay许可仅是局部保护，R21控制ACK不代表全树停止；未取得外部证据不能宣称MCP无任何保护，也不能计作已验证总预算。不可取消任务需要独立容量上限、owner、到期和受限清理，不能无限留存或无差别重发。
- **措施/验收**：[W11](#w11)签认总调用数/并发/深度/字节与重试责任，[W05](#w05)控制依赖预算，[W07](#w07)验证取消级联。T17/D09/D04统计真实子任务/尝试数，覆盖多层429/黑洞/断流、Stop/kill/重连；超时后资源回落或可追踪地有界收口，UNKNOWN不被当作未执行盲重跑。本轮不建设通用副作用补偿平台。

<a id="r18"></a>
#### R18 数据库/JVM锁等待、潜在锁环与异步事务边界需按真实锁图验证

- **证据 S/E**：[事件追加SQL](../../../src/main/resources/mapper/persistence/ChatEventMapper.opengauss.xml#L63)对Run `FOR SHARE NOWAIT`再Execution共享锁；[owner终态](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunTerminalCommitService.java#L136)先Session再执行权校验，后续写事件/消息/Binding/Interaction；[异步回调](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/DomainAgentAsyncTaskCallbackCommitService.java#L63)先Session再外部终态CAS并组装/落库；[批量删除](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionApplicationService.java#L400)先排序锁Session，再更新Session/Binding/分享/Interaction。显式锁之外还涉及UPDATE、唯一索引及关联写入产生的隐式锁，真实openGauss锁图、隔离级别和受害事务处理为E。
- **多资源持有证据 S**：[兼容createRunning/续跑](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunApplicationService.java#L115)锁Session后L121/L142写Redis；[固定专家Binding](../../../src/main/java/com/huawei/it/ex/one/application/service/runtime/RuntimeBindingApplicationService.java#L144)在短事务中L191写缓存，Redis变慢会延长DB锁/连接占用。标准insertRunning为DB-only，删除缓存已移提交后；删除入口虽按Session ID排序，普通@Transactional仍未声明自身期限，不能用借用500ms代替。
- **JVM锁内回调证据 S**：[WS连接注册表](../../../src/main/java/com/huawei/it/ex/one/interfaces/chat/websocket/LocalWebSocketConnectionRegistry.java#L49)的register/subscribe/unregister（L49/L99/L146）共用实例monitor，锁内扫描连接、替换/释放旧订阅；L236–261经L302调用dispose，实际[取消句柄](../../../src/main/java/com/huawei/it/ex/one/interfaces/chat/websocket/ChatWebSocketProtocolService.java#L350)同步`tryEmitEmpty`。[本机TopicSink](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/LocalChatEventStreamRegistry.java#L145)和[Redis TopicSink](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java#L780)均持每topic monitor调用`tryEmitNext/Complete/Error`；既有本地Reactor 3.7.6静态字节码检查确认`SinkManyEmitterProcessor.tryEmitNext→drain→subscriber.onNext`可以在当前调用栈执行，不能假定signal就是异步边界。Redis[订阅doFinally](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java#L209)还会调用listener注销，实际传播线程和库内等待需E。
- **Relay锁证据 S**：[ShortRunExchange](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java#L1319)（ActiveRelayWebSocketExchange实现）在L1343/L1355/L1420的对象monitor内发出outbound/interrupt终止信号；`interrupt`先同步send再于L1402返回带ACK timeout的Mono，该期限不能限制此前获取monitor或同步signal耗时。`close`在L1439已把subscription.dispose移到锁外，不能描述为所有清理都持锁执行。
- **触发与影响**：终态、回调、WAIT后Stop、删除及Watchdog并发，大回调/长事务/外部持锁延长锁占用；若某条新增或遗漏路径反向获取重叠锁，可能形成锁环，锁等待也可能在没有死锁时耗尽池并阻塞治理。WS重连/替换订阅/终止洪峰叠加慢取消或同步订阅回调，可能使topic或Relay发送/终止等待；WS实例级monitor内释放变慢还会影响无关连接的注册/订阅/注销。实际有无反向锁序、耗时回调或跨线程互等尚未复现；同线程重入可重入monitor不是死锁。同步内存/CPU工作、嵌套事务或异步切换也可能把资源保留到调用方超时之后。
- **已有保护/剩余缺口**：Session排序、同会话Session锁串行、Run NOWAIT、短事务、CAS/fencing均须保留；不能只看局部“Binding与Interaction顺序不同”就认定死锁，因为共同先持Session可能已消除并发环。当前未取得反向锁环或故障复现，状态ENV表示需要验证该条件性路径，不表示源码已证明死锁。标准化在回调事务前、发布在事务后；[Stop](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStopCoordinator.java#L164)也分开提交CANCELLING、远端取消、最终提交，不能误写成远端等待全在DB事务内。
- **JVM已有保护 S**：[用户窗口](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/RunAdmissionControlService.java#L68)仅按用户Deque锁做内存清理/计数，tenant许可使用非等待tryAcquire；[订阅去重](../../../src/main/java/com/huawei/it/ex/one/interfaces/chat/websocket/LocalWebSocketConnectionRegistry.java#L290)锁内仅更新有界map，向客户端发射在返回后。[Servlet出站队列](../../../src/main/java/com/huawei/it/ex/one/interfaces/chat/websocket/ServletWebSocketOutboundQueue.java#L29)的monitor只保护内存队列，真实[sendMessage](../../../src/main/java/com/huawei/it/ex/one/interfaces/chat/websocket/ChatServletWebSocketHandler.java#L205)在poll返回后锁外执行；Redis[TopicPublisher](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java#L668)也在poll返回后才[网络发布/重试](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java#L448)。不能把这些短临界区统称持锁网络阻塞，亦不能只因存在synchronized就判定死锁。
- **加固措施**：[W04](#w04)。按真实锁对象、调用栈、线程和事务边界取证后缩小临界区、移出外部等待；保留排序/NOWAIT/fencing与事件顺序。JVM线程停滞使用RB02，DB锁/事务使用RB03；不直接删锁或增加全局大锁。
- **关闭证据（待取得）**：T18/D02用确定性屏障覆盖上述成对并发及批次反序，采集真实DB锁等待图、SQL/事务ID、受害事务/超时完整回滚；JVM子项覆盖同topic并发emit/终止、慢取消回调、订阅替换/注销、Relay interrupt/close和其他连接同时注册，记录monitor owner/等待栈、持锁时长、回调线程、跨连接时延及释放。夹具故意阻塞回调不等于已复现产品死锁；必须提供可达链和反向持锁证据。若未形成锁环须记录已有保护和覆盖范围；数据库死锁、普通锁超时、连接耗尽与JVM锁/事件循环阻塞分别归因。

<a id="r19"></a>
#### R19 DomainAgent慢、黑洞、断流和异常响应占满执行资源

- **证据 U/S**：U路径为ChatService→agentService统一chat→DomainAgent，源码类名不代表绕过中间服务。[query](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/domainagent/ConfiguredDomainAgentClient.java#L65)HTTP流在原始DataBuffer处L84–85检查空闲，再L89标准化；L97收到completed/async主动闭流，L100设置订阅起总期限。该路径默认关闭；启用后的默认[原始chunk空闲300s、总15m](../../../src/main/resources/application.yml#L429)，无应用层chat重试；旧TIMEOUT环境变量可同时覆盖这两个默认值，生效值须核验。
- **触发→传播→影响**：agentService或真实DomainAgent连接黑洞、慢响应、429/5xx/断流，使长调用占执行许可和连接；持续无效原始字节可推迟空闲失败，高频合法/异常帧增加解析、日志、CPU/堆及R01队列。集中失败后用户重提又扩大故障流量，影响其他skill和共享治理。
- **已有保护/缺口**：下游许可、[256KiB待处理帧上限](../../../src/main/resources/application.yml#L444)、原始chunk空闲及总期限、结束主动闭流已有；未单独按首个有效业务事件/进展计时，也未证明每skill隔离或取消传透agentService。默认[stop-path为空](../../../src/main/resources/application.yml#L436)，[cancel](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/domainagent/ConfiguredDomainAgentClient.java#L110)直接完成；配置后默认120s，错误L129–136记录后空完成，不证明远端释放。外部实现和资源阈值为E。
- **措施/验收**：[W05](#w05)，取消协同[W07](#w07)、联合契约协同[W11](#w11)。T19/D04覆盖连接/首帧/流中黑洞、空字节、429/5xx、断流和高频/过大响应；分别核对当前无重试事实、预算耗尽、其他skill及Stop、真实socket/许可/远端任务释放。保留错误来源，不因通用timeout直接认定真实DA根因；不自动转Relay重跑已开始任务。

<a id="r20"></a>
#### R20 intentService故障的即时重试及Relay兜底放大

- **证据 S**：Intent[默认关闭](../../../src/main/resources/application.yml#L124)；启用时默认STREAMING，[期限及重试](../../../src/main/resources/application.yml#L145)为auth5s、首有效事件5s、SSE空闲30s、每次HTTP流120s，首次＋3次重试。[recognize](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentStreamClient.java#L116)创建尝试数，[recoverAttempt](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentStreamClient.java#L311)按策略L322–323立即进入下一次，无退避；[默认策略](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/DefaultIntentRetryPolicy.java#L19)广泛重试degraded结果。耗尽后[路由](../../../src/main/java/com/huawei/it/ex/one/application/service/routing/RouteSignalApplicationService.java#L276)默认RELAY_FALLBACK，也可配置FAIL_RUN。
- **触发→传播→影响**：第三方intentService黑洞、429/5xx、断流或持续协议错误，使同时到来的请求在默认配置下各最多四次立即尝试；每次HTTP总时限重置且不包含鉴权/前置。失败群再转Relay，使故障从Intent连接/线程/许可传播到Relay及MCP，下游余量不足时形成二次过载。
- **已有保护/缺口**：功能关闭时不调用该HTTP入口；启用后已有有界鉴权scheduler、首有效事件/空闲/单流总期限、有限次数及FAIL_RUN策略。合法NO_MATCH与技术失败不同，候选查询已有单独退避，不应笼统称全部Intent无保护。既有每次期限不等于逻辑请求总预算，默认兜底不证明Relay有预留容量。
- **措施/验收**：[W05](#w05)，容量合同协同[W11](#w11)。T20/D04分别验证关闭和开启、各阶段黑洞、429/5xx/协议错、预算耗尽、RELAY_FALLBACK/FAIL_RUN；记录真实尝试、退避、Relay新增负载和控制链路。按错误分类及剩余预算实现有限退避，兜底受独立容量准入，不把所有失败无限转Relay。

<a id="r21"></a>
#### R21 Relay握手、半开连接与取消残留占用

- **证据 S/E**：[短连接执行](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java#L234)注册active exchange、发送/接收并在doFinally/onDispose清理；[Upgrade期限](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java#L373)、config定时器及[心跳/最长Run](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java#L661)分别保护不同阶段。默认[连接5s、Upgrade10s、config10s、入站存活90s、Run30m](../../../src/main/resources/application.yml#L358)，无应用层重试；心跳默认20s，Stop临时连接idle60s不能等同普通chat空闲。
- **触发→传播→影响**：连接/Upgrade/config不返回、仅有心跳无业务进展、半开/断流、慢消费者或远端不停止，长期占socket、timer、许可、exchange和子任务；重复连接/取消风暴扩大线程、FD与队列压力。停止方在CANCELLING提交后退出，健康owner继续心跳，或删除提交后Stop未调度，可延迟残留发现。
- **已有保护/缺口**：[Stop](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStopCoordinator.java#L164)分段提交并执行取消；[ACK](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java#L1379)默认5s，L1412只要求发送完成或paused任一，不代表所有远端任务停止，且不包含此前monitor等待。既有清理、fencing、[过期恢复](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunRecoveryOrchestrator.java#L306)有效，但健康续租可能不进入stale扫描。session级迟到Stop仍可能打断后续复用Run并诱发重提/重连；真实Relay代次/命令顺序为E，mock不能关闭。
- **措施/验收**：[W05](#w05)分阶段隔离/期限，[W07](#w07)清理残留，[W11](#w11)签认Relay/MCP取消边界。T21/D04覆盖握手黑洞、半开/心跳无进展、慢消费、Stop超时/迟到及停止方退出；证明连接/timer/许可回落、残留任务受限治理，后续Run和其他session仍可用。不得把flushed/paused或本地terminal当成远端全停证据。

<a id="risk-renumbering"></a>
### 连续编号对照

以文档提交`871ca17f`为调整起点，保持21项风险原顺序，连续编号为R01–R21，测试同步为T01–T21；内容、优先级和状态不变。当前文档使用新编号，历史验证记录仍按其原提交和旧编号解释。S/DEP/W/RB/D编号不变。

| 旧风险 / 测试 | 当前风险 / 测试 |
|---|---|
| 旧R01 / 旧T01 | [R01](#r01) / [T01](tests.md#t01) |
| 旧R03 / 旧T03 | [R02](#r02) / [T02](tests.md#t02) |
| 旧R06 / 旧T06 | [R03](#r03) / [T03](tests.md#t03) |
| 旧R09 / 旧T09 | [R04](#r04) / [T04](tests.md#t04) |
| 旧R11 / 旧T11 | [R05](#r05) / [T05](tests.md#t05) |
| 旧R12 / 旧T12 | [R06](#r06) / [T06](tests.md#t06) |
| 旧R26 / 旧T26 | [R07](#r07) / [T07](tests.md#t07) |
| 旧R27 / 旧T27 | [R08](#r08) / [T08](tests.md#t08) |
| 旧R28 / 旧T28 | [R09](#r09) / [T09](tests.md#t09) |
| 旧R29 / 旧T29 | [R10](#r10) / [T10](tests.md#t10) |
| 旧R31 / 旧T31 | [R11](#r11) / [T11](tests.md#t11) |
| 旧R32 / 旧T32 | [R12](#r12) / [T12](tests.md#t12) |
| 旧R33 / 旧T33 | [R13](#r13) / [T13](tests.md#t13) |
| 旧R34 / 旧T34 | [R14](#r14) / [T14](tests.md#t14) |
| 旧R35 / 旧T35 | [R15](#r15) / [T15](tests.md#t15) |
| 旧R36 / 旧T36 | [R16](#r16) / [T16](tests.md#t16) |
| 旧R37 / 旧T37 | [R17](#r17) / [T17](tests.md#t17) |
| 旧R38 / 旧T38 | [R18](#r18) / [T18](tests.md#t18) |
| 旧R39 / 旧T39 | [R19](#r19) / [T19](tests.md#t19) |
| 旧R40 / 旧T40 | [R20](#r20) / [T20](tests.md#t20) |
| 旧R41 / 旧T41 | [R21](#r21) / [T21](tests.md#t21) |

新编号复用了部分旧编号，当前`#rxx/#txx`统一表示新编号；旧书签须按本表、下方去向或原提交定位，不能直接按同名短锚点解释旧风险。合并/移出条目统一标“旧Rxx/旧Txx”，使用`legacy-rxx/legacy-txx`锚点，不属于当前21项清单，也不代表已整改。

<a id="risk-dispositions"></a>
### 合并或移出的旧编号去向

以下旧编号沿用`871ca17f`中的含义；正文链接指向当前连续编号。不属于主清单，不表示已关闭；被移出部分后续另行排期。

<a id="risk-register--legacy-r02-共享数据库竞争及实例总预算缺口"></a>
<a id="legacy-r02"></a>
**旧R02**：共享连接、准入及租户表增长并入[R09](#r09)。

<a id="risk-register--legacy-r04-db-commit-到客户端消费之间没有可靠交付承诺"></a>
<a id="legacy-r04"></a>
**旧R04**：提交后缺通知引起的恢复流量并入[R02](#r02)；可靠投递/消费承诺专项移出本轮。

<a id="risk-register--legacy-r05-relay-session-级迟到-stop-可能影响后续-run"></a>
<a id="legacy-r05"></a>
**旧R05**：Relay迟到Stop对连续服务及重试负载的影响并入[R21](#r21)；不另设纯执行正确性工作包。

<a id="risk-register--legacy-r07-仍有部分事务包含-redis-调用"></a>
<a id="legacy-r07"></a>
**旧R07**：事务内Redis持有多种资源并入[R18](#r18)。

<a id="risk-register--legacy-r08-commandid-字段存在但不足以保证端到端幂等"></a>
<a id="legacy-r08"></a>
**旧R08**：重复受理造成的流量放大纳入[R02](#r02)/[R17](#r17)的配额与受控恢复；通用受理幂等专项移出。

<a id="risk-register--legacy-r10-旧偏好及旁路缺少完整排队和-sql-期限"></a>
<a id="legacy-r10"></a>
**旧R10**：旧旁路排队/SQL期限并入[R05](#r05)。

<a id="risk-register--legacy-r13-watchdog-所有分支尚未共用完整治理预算"></a>
<a id="legacy-r13"></a>
**旧R13**：后台治理预算并入[R09](#r09)。

<a id="risk-register--legacy-r14-停止方退出后-cancelling-或删除后的-run-收口延迟"></a>
<a id="legacy-r14"></a>
**旧R14**：取消/删除后的资源残留并入[R21](#r21)/[R17](#r17)，发布退出归[R08](#r08)；非资源类状态完整性专项移出。

<a id="risk-register--legacy-r15-性能索引迁移是未确认的上线依赖"></a>
<a id="legacy-r15"></a>
**旧R15**：性能索引有效性及迁移门槛并入[R09](#r09)。

<a id="risk-register--legacy-r16-配置并发-miss-放大及非默认组合线程风险"></a>
<a id="legacy-r16"></a>
**旧R16**：技能回源/线程及失败边界并入[R16](#r16)。

<a id="risk-register--legacy-r17-删除会话仍可能通过历史恢复访问"></a>
<a id="legacy-r17"></a>
**旧R17**：删除后恢复访问的业务/权限完整性专项移出本轮，未整改。

<a id="risk-register--legacy-r18-分支创建非原子及分享创建越过删除撤销"></a>
<a id="legacy-r18"></a>
**旧R18**：分支/分享原子性专项移出本轮，未整改。

<a id="risk-register--legacy-r19-welink-投递未知结果被再次发送"></a>
<a id="legacy-r19"></a>
**旧R19**：同步投递阻塞/重试资源放大并入[R03](#r03)；可靠送达及未知结果补偿专项移出。

<a id="risk-register--legacy-r20-联调样例忽略恢复建议游标和业务错误"></a>
<a id="legacy-r20"></a>
**旧R20**：联调样例恢复游标/重连放大并入[R02](#r02)；HTTP200业务错误展示专项移出。

<a id="risk-register--legacy-r21-可编辑-metadata-能改变可信附件引用"></a>
<a id="legacy-r21"></a>
**旧R21**：文档可信引用安全专项移出本轮，未整改。

<a id="risk-register--legacy-r22-huawei-s3-配置未开启证书和-hostname-验证"></a>
<a id="legacy-r22"></a>
**旧R22**：OBS TLS信任校验专项移出本轮，未整改；证书失效造成的可用性仍由W09/W12验证。

<a id="risk-register--legacy-r23-删除事务缺少显式本地期限"></a>
<a id="legacy-r23"></a>
**旧R23**：删除事务期限及锁等待并入[R18](#r18)。

<a id="risk-register--legacy-r24-active-path-选择与准入完成并发覆盖"></a>
<a id="legacy-r24"></a>
**旧R24**：活动路径并发完整性专项移出本轮，未整改。

<a id="risk-register--legacy-r25-标题生成许可不覆盖候选和提交完整生命周期"></a>
<a id="legacy-r25"></a>
**旧R25**：标题完整生命周期预算并入[R05](#r05)。

<a id="risk-register--legacy-r30-admin映射变更及toolda执行未知结果缺少联合保证"></a>
<a id="legacy-r30"></a>
**旧R30**：技能映射/配置及统一chat故障传播并入[R16](#r16)/[R19](#r19)；跨服务通用exactly-once专项移出。

<a id="risk-register--静态依赖复核的可重复命令"></a>
### 静态依赖证据

R04的Spring Data Redis 3.4.6、Spring Core 6.2.7默认执行器与R18的Reactor 3.7.6同步发射结论来自既有本地字节码检查；只能证明实现可能执行的路径，不能证明生产线程数或死锁。复核版本及命令保存在[验证记录](evidence.md)，运行验收分别由T04/T18承担。

<a id="hardening"></a>
<a id="hardening--高可用加固任务清单与发布门槛"></a>
## 加固任务与实施顺序

13个活跃工作包为 **W01–W09、W11–W14**，均待实施。新增限额/开关/协议须注明单位、作用域（请求/用户/租户/实例/集群）、生效方式和拒绝行为，不虚构配置名或已有能力。

<a id="hardening--实施顺序与公共验收"></a>
| 阶段 | 工作 | 前置及出口 |
|---|---|---|
| A 基线与定位 | W09有效配置、资源预算、SLI/观测 | 具名负责人、真实规格/峰值/配额和隔离环境；签认数值目标后才能判定通过 |
| B 资源与阻塞治理 | W01–W06、W08：流/恢复/锁与等待、文件、查询和旁路 | 资源和积压有界，正常聊天/Stop/心跳可运行；W06在放开500MiB前完成字节/磁盘/全流生命周期验收 |
| C 任务清理与服务隔离 | W07取消残留；W11共享预算、模块隔离、联合契约和目标拆分 | 真实任务/连接释放，混合负载主链路不被拖累；拆分不能放大共享额度，启用依赖前取得合同和隔离证据 |
| D 入口与容灾 | W12入口、W13 ADS/AZ、W14 Region | 先独立静态源/故障域/余量，再验收隔离旧写、恢复点、单写接管与回切 |

公共交接门槛由W09维护：关联任务完成或具名限期接受，真实依赖环境演练、告警、回滚和运行册可执行；没有证据不能因文档完成而放行。

预算同时覆盖堆/native、CPU、线程、FD、临时盘、队列字节/年龄、连接及下游速率。各类“并发×单任务高分位驻留字节＋队列字节”之和须留足框架/缓存/GC余量，不能从默认许可数推定最大安全用户数。验收要求资源回落、控制可用、负载恢复后积压收敛，并保留既有事件/留存语义。每包按独立可评审提交实施，修复合并不自动关闭风险。

<a id="hardening--w01-流式队列累计正文与-cpu-保护"></a>
<a id="w01"></a>
### W01 流式队列、累计正文与 CPU 保护

**关联**：R01、R07；应用主责，性能测试协作；T01/T07，RB01/RB02，D01。

- **实施**：梳理 Relay 三个 BUFFER 桥接和 DomainAgent 总期限包装，优先用保持订阅 demand 的组合替代内部无界 subscribe。不能消除桥接时使用事件数＋字节双边界，按每 Run 及实例总量收费；同样约束草稿、Parts、卡片正文及队列中的大 payload。超限取消上游并提交一次有解释的失败终态，严禁 silent drop 控制/正文。
- **CPU**：先 JFR/profile 确认协议解析、字节估算、多次序列化、草稿物化和 GC 热点；在保序/ACK/留存语义相同前提下消除确认的重复工作，必要时隔离同步 CPU 工作。输入深度/字节与事件速率限制须在昂贵复制/序列化前执行，保留 Jackson 自有保护；不得删除鉴权、脱敏或owner校验来换取性能。
- **前置/接口**：冻结每帧、每 Run、实例预算及现有终态错误映射。保持 FULL/no-store、questionnaire、拒答、候选切换、异步挂起、snapshot 的现有事件顺序；新增错误 code 要同步 OpenAPI/前端，不能静默截断已承诺正文。
- **观测/测试**：队列事件/字节、草稿/Parts字节、拒绝、线程/堆/GC、event-loop lag、取消后残留订阅；测试慢 DB＋高速流、接近上限/超限、timeout/stop/kill，验证 timer/socket/permit 清理。
- **发布/回滚**：先隔离环境后单实例小流量，观察完整 Run 与恢复周期。回退旧版本会重新暴露无界队列，因此须同时收紧已验证的入口配额或摘流；不能把“关闭保护”当正常兜底。

<a id="hardening--w02-redis-接收执行器与拒绝恢复"></a>
<a id="w02"></a>
### W02 Redis 接收执行器与拒绝恢复

**责任**：应用主责，SRE/前端协作；R04，协同R02；测试与演练以追踪矩阵为准。

- **实施**：为 `RedisMessageListenerContainer` 显式提供有界消息执行器；连接订阅/重连任务与消息 handler 分配独立资源。拒绝路径打出 topic、原因和恢复锚点，必要时终止订阅要求恢复；禁止网络线程 CallerRuns 和无记录丢弃。
- **前置/接口**：核对 Spring Data Redis 的调度和 listener 调用模型，保持同 topic 消费顺序/现有重排策略。Pub/Sub 不是可靠存储；FULL 用现有 DB 补读，no-store 明确缺口，不能靠自动重放存不存在的内容。
- **观测/测试**：消息执行线程/队列、拒绝率、回调耗时、重连/恢复速率、topic积压；多个 topic 高 fanout、handler阻塞、Redis断网/实际拓扑故障切换、重排和销毁重建。
- **发布/回滚**：进程级灰度而非混用同一容器内两套无协调执行器。回退须限制扇出并保留恢复告警，确认连接容器关闭无残留线程。

<a id="hardening--w03-恢复分页预算与客户端协同"></a>
<a id="w03"></a>
### W03 恢复分页、预算与客户端协同

**责任**：应用、前端共同主责；R02。

- **实施**：HTTP Session Resume、Run Resume、WS历史补读都使用游标分页，先建立 live 有界缓冲再读历史；复用仓储已有有界事件窗口能力时补齐会话级路径。按 sequence 单调推进，维持业务中止/异步边界语义；seq 是全局游标，不要求一个 topic 连续编号。
- **配额**：恢复设置实例和用户并发、回放总字节/时间边界，配额失败立即返回明确忙/恢复信号，取消/断线归还；与新 Run、Stop/heartbeat 预算分开。客户端按服务端恢复建议游标、指数退避/抖动及事件身份去重，避免多页签同步重连。
- **前置/接口**：优先保持现有 `afterSeq` 和流式响应；需要新增 continuation/busy code 时作为独立协议增量，旧前端不得把截断当回放完成。联调样例修复建议游标处理；生产前端需单独核验，不能推定已有相同缺陷。
- **交付窗口**：DB提交、Redis广播、客户端消费独立；FULL以状态查询及有界Resume补读，no-store明确缺口，不新增正文留存或默认引入消息中间件。缺终态不能导致客户端无限重试。
- **观测/测试**：恢复在途/排队/拒绝、页大小/总字节、补读与 live 重叠、缺口/重连次数；长历史、高并发取消、跨实例、Redis恢复风暴及丢响应。
- **发布/回滚**：新旧服务均读同一事实源，先部署兼容客户端再启新错误/游标行为；回滚服务器时保留网关已验证恢复并发限制，不回到无限恢复流量。

<a id="hardening--w04-db事务连接公平治理与索引"></a>
<a id="w04"></a>
### W04 事务/JVM锁、等待预算与Chat侧DB保护

**责任**：应用/DBA共同主责，JVM线程问题由平台协作；R18、R09；JVM停滞用RB02，DB等待用RB03，跨服务预算归W11。

- **事务**：createRunning/Interaction兼容入口和固定专家Binding只操作DB，缓存移到确认提交之后；删除两个代理入口加显式有界事务，保留稳定Session锁序、owner/fencing和批量原子性。逐个验证`@Transactional`是否经过代理、自调用与MANDATORY/嵌套传播；Reactive订阅、线程切换和独立`subscribe`不默认继承原事务。afterCommit不等于底层连接已归还，耗时回调应明确调度和失败重试边界，不能把跨服务外呼包进数据库事务。
- **锁顺序**：以Session/Run/Execution/Interaction/Binding/Message/Share等资源列出每条写路径的实际顺序、锁模式、超时、释放和失败回滚点，覆盖UPDATE/唯一索引等隐式锁；对成对并发绘制等待关系。优先保持已验证的Session排序、Run NOWAIT和fencing，仅对证实的环或顺序缺口整改。区分数据库死锁、锁等待、连接耗尽、Java锁等待和事件循环阻塞；死锁受害事务只能在确认整体回滚且操作幂等时按剩余预算重试，提交响应丢失先查事实。
- **JVM临界区**：记录monitor对象、作用域、持有线程、等待栈和同步回调。WS实例锁内扫描/替换订阅先测耗时；需要改造时锁内原子更新并剥离句柄、锁外幂等dispose。topic/Relay信号采用保序的串行发射或经验证的锁外方案，保留拒绝/终止/清理语义；不把真实send本已在锁外的Servlet队列改成大锁，不因synchronized存在就删锁。
- **完整等待预算**：登记入口准入/解析、worker排队、许可、连接借用、statement、lock、socket、事务提交、Redis/HTTP/SDK及结果发送的单段与总预算；列当前生效值、外层截止时间、超时动作和取消后实际释放条件。数据库事务期限不能替代网络或同步运算期限，连接借用期限不能限制已借出连接。大回调组装和大对象序列化应尽可能在锁前受控完成；移出事务仍需锁后校验版本，不能以缩短事务破坏原子性。
- **公平准入**：以真实DB/CPU/内存总容量分配实例、租户、功能及集群额度，明确扩容后的总上限，为Stop/心跳/终态/恢复留预算；请求/恢复/文件/旁路分别计费，突发流量尽早拒绝并提供受控退避。无需默认拆多个物理池；只有预算及压测证明有效才引入，不能扩大总连接越过DBA配额。洪峰时不依赖无限排队、加线程或重试维持表面成功率。
- **治理**：scan/claim 有短期限；Interaction、初始化孤儿、async、stale recovery 共用本轮工作次数/时间预算并计失败尝试，公平处理租户，积压分批推进。清理 tenant semaphore 时防正在借用对象被替换，不移除现有用户窗口清理。
- **迁移**：DBA核验当前索引定义/有效性及数据库存储类型，事务外执行需要的并发索引脚本，记录中断残留/重跑方法；启动关键唯一约束检查保持，不把性能索引变成启动DDL。
- **前置/接口**：拿到 openGauss版本、驱动和全局期限；使用既有忙/超时语义，SQL超时必须回滚并释放资源。不得把搜索失败伪装为空结果或把事务超时直接断言未提交。
- **观测/测试**：借用等待/超时、active/pending、锁等待图/死锁受害事务/慢SQL、事务龄、任务排队龄、线程dump、治理上次完成时间/尝试/积压和事务内Redis检测；确定性并发屏障覆盖主Run/事件/终态/回调/WAIT Stop/删除/Watchdog，并覆盖WS注册/替换/注销、topic并发emit/终止和Relay interrupt/close的同步慢回调，再进行突发混合负载。证明控制链路达标、超时整体回滚、锁/连接/线程释放且无迟到提交；真实openGauss行为不可用H2/PostgreSQL结果替代。
- **发布/回滚**：期限/配额逐步收紧，灰度按业务成功率与治理时延判定；代码回滚保留兼容索引。删除索引必须由 DBA确认新旧查询均可接受，不自动回滚DDL。

<a id="hardening--w05-逻辑总期限鉴权隔离重试与配置查询"></a>
<a id="w05"></a>
### W05 依赖隔离、逻辑总期限、有限重试与受控降级

**责任**：应用主责，鉴权、intentService、agentService/DomainAgent、relayService负责人联合签认；R03/R19/R20/R21，协同R16/R17。

- **预算与隔离**：一次逻辑操作有绝对截止时间，覆盖准入/排队、鉴权、DNS/连接/TLS、写出、首有效事件、空闲、解析、重试及取消；每阶段只使用剩余预算。按provider/必要的skill和操作分配并发、字节及队列预算，给Stop/状态查询留容量；阻塞resolver/SDK必须在底层设置期限，有界worker和拒绝，不能只包Reactive timeout。
- **DomainAgent**：保留当前无chat应用重试、原始chunk空闲/总期限/帧上限和结束闭流。补首有效业务事件与进展观测，区分agentService转发和真实DA阶段，429/5xx/协议/流断开分别归因；空字节/高频响应仍受速率、CPU/字节预算。按契约定义可否重试/降级及错误码，已执行或结果未知不自动重启整段或转Relay；默认空stop-path及吞错行为必须显式暴露为“远端停止未确认”，资源清理按W07。
- **Intent**：保留默认关闭及候选查询独立退避。启用主路由后以错误分类限定重试，永久鉴权/协议错误不重复打满依赖；仅安全瞬态错误退避、抖动并遵循Retry-After，首次和全部重试共用逻辑预算，合法NO_MATCH不重试。技术失败RELAY_FALLBACK与FAIL_RUN分别验收；Relay兜底有独立配额与容量余量，满载明确失败，不能把故障流量无上限转移。新退避/总预算/容量策略为待实现配置，不假装已有熔断开关。
- **Relay**：连接、Upgrade、config、业务进展/入站心跳、总Run及Stop分别观测和设置预算；不把90s入站存活当作首有效业务事件承诺。保留当前无应用重试；半开/断流恢复先控制重连速率和在途数，握手/配置失败必须释放全部阶段资源，避免一个失败阶段留下下一阶段timer。需要熔断/探测恢复时，先实现有界半开探测与恢复放量，再写入运行册。
- **技能与同步旁路**：缓存命中/回源共享完整预算；按tenant＋skill合并miss并约束合并表基数，失败/取消移除在途条目。配置完成后显式调度才进入阻塞仓储。保留不可变快照；留存开启时配置查询失败阻止执行，仅附件检查时的配置查询失败按现实现开放。成功返回空支持类型仍拒绝附件，畸形类型保持现有告警/处理语义；新鲜度/最后可用值由W11签认。补阻塞Intent/用例库/WeLink的前置鉴权期限；未知外部结果不盲重试以防重复负载，不建立通用投递补偿工程。
- **观测/验收**：记录阶段耗时/剩余预算、重试原因/实际次数、拒绝/降级量、Relay增量负载、取消后真实线程/socket/许可/任务数。T03/T19–T21覆盖慢、黑洞、半开、断流、429/5xx、异常/高频响应及两种失败策略，并与T16/T17联合验证其他模块及Stop不受拖累；只返回timeout不算通过。
- **接口/发布/回滚**：先取得提供方真实期限、限额、取消/重试与错误来源合同；新增字段/错误码独立更新OpenAPI及前端兼容。按provider小流量发布；回滚后保留已验证的入口/依赖配额，必要时暂停对应功能，不能通过加重试、绕过鉴权或关闭留存恢复吞吐。

<a id="hardening--w06-文档在途字节流生命周期与对账"></a>
<a id="w06"></a>
### W06 文档在途字节、流生命周期与对账

**责任**：应用/存储主责，agentService与前端协作；R06。

- **500MiB前置门槛**：先固定单位、有效multipart/业务/网关/provider限额、上传总期限与单实例/集群安全容量，再允许500MiB；不能把32个请求许可视为可同时安全接收32个500MiB。入口许可必须在multipart解析和落盘前取得（网关或经验证的Filter/解析层），不能读取表单后才决定是否准入。对未知Content-Length/分块按实际接收字节递增收费，设每请求、实例和租户字节/速率/临时盘边界；请求数、字节、磁盘预留、FD及出站连接预算独立核对。
- **最小实现路径**：API Store替换`readAllBytes`为受控临时文件及`FileSystemResource`或经provider验证的流式multipart。当前[DocumentUploadCommand](../../../src/main/java/com/huawei/it/ex/one/application/command/DocumentUploadCommand.java#L24)只有InputStream，需新增内部可重开资源/Path抽象、清晰所有权及兼容适配，不得通过强转流猜路径。保留`file`/`skillId`/Cookie出站头及返回元数据合同；验证WebClient/SDK不会再聚合整份文件，取消/超时后停止复制和外呼，关闭流并删除临时文件。独立worker采用有界队列/字节和过期清理，不把OOM变为磁盘或队列耗尽。
- **目标职责与迁移**：文档管理迁agentService属于P，先落实文档事实/权限/对象key与现有Chat附件引用的唯一所有者、登记/状态查询/删除契约及路由兼容。文件内容优先由浏览器直接上传合适对象存储，或独立worker承载转发/处理，管理进程只处理有限元数据；短期兼容Chat上传仍须受上述预算。直传需授权范围/有效期、大小/校验和、完成确认、重复完成与孤儿清理。`skillId→API Store/EDM`如含业务解析/登记，不能用普通S3直传替换；须由下游确认等价导入流程，否则走有界worker转发。迁移不默认跨库双写文档事实。
- **流生命周期**：对象存储下载包装流在close/cancel/读取失败时幂等归还许可，覆盖完整响应发送；HTTP取消、异步超时和SDK实际结束分别核对。明确临时目录配额、框架与应用临时文件叠加、清理失败告警、实例kill后的恢复扫描及安全过期规则；软删除不等于物理清理。
- **对账**：为上传分配稳定操作标识，记录存储成功/DB保存/补偿结果；落库失败尽力补偿并保留可重试对账记录。重试前确认对象是否已创建及provider是否幂等。物理回收先核对有效引用，区分用户软删除、历史合法引用、孤儿和临时文件；所有权或结果未知先隔离对账，不做全桶清理。
- **前置/接口**：确认API Store可重复流/分块、EDM导入、对象删除/查询能力，以及下载/状态合同；缺必要接口记UNKNOWN，不能编造自动补偿。异步worker/直传如改变同步返回或增加上传状态/完成接口，先更新OpenAPI、前端和兼容方案，原路径在新链路验收前有界保留。
- **观测/测试**：在途文件/实际字节、接收与转发速率、队列龄、堆/native/临时盘、FD/连接/许可、取消残留及孤儿年龄；500MiB及边界/未知长度、慢两端/断连/DB失败/kill、重复完成、worker退出和迁移双版本兼容。证据必须证明Chat及agent管理/运行时主链路不受大文件拖累。
- **发布/回滚**：先小文件/单provider与少量租户灰度；新增操作记录向旧版本兼容，旧版本不得误删新对象。若500MiB新路径失败，阻断新大文件或摘流，已受理文件继续按操作记录收口；不能把大文件重新送回原`readAllBytes`路径。路由回滚保持文档事实单写、历史引用和未完成上传可查询。

<a id="hardening--w07-状态收敛提交未知结果与外部副作用"></a>
<a id="w07"></a>
### W07 取消残留与完整资源生命周期收口

**责任**：应用、relayService、agentService/DomainAgent共同负责；协同R03/R17/R19/R21及发布退出R08。此包只处理资源占用与任务收口，不展开受理幂等、分支/分享原子性、活动路径或通用投递补偿。

- **清理清单**：按正常、异常、超时、取消、进程退出列出socket、HTTP body、订阅、timer、future、worker、临时文件、许可/队列字节与远端子任务的取得/释放点；清理幂等，取消回调不阻塞网络线程或持有全局锁。关闭本地流、收到取消ACK、远端任务停止分别记录，依赖不可取消时保留独立上限、owner、年龄和到期收口责任。
- **遗留治理**：CANCELLING、删除提交后未Stop、停止方退出但owner继续续租均须在约定期限内发现，不只等待租约过期。治理与原执行的CAS/fencing保持，扫描/确认/再次控制有限次数和时间预算；无远端确认不无限轮询或重发，不把业务Run终态当作资源已释放。
- **Relay与MCP**：联合验证session复用下的有序控制或代次屏障；5s flushed/paused不能充当远端全停证明。Stop需沿原执行目标触及MCP子任务，迟到控制不得触发后续任务反复失败和重提；必要协议字段另立兼容任务，真实下游验证前保持ENV。域代理空stop-path/错误吞回需可观察，未实现远端取消时使用已验证的受限自然终止策略。
- **验收**：关联T03/T17/T19/T21和发布T08分别在创建资源后、取消前后及清理时注入断线/kill；记录资源回落时间、未释放原因与受控残留数量。跨实例保持旧owner心跳验证治理可进展，真Relay/DA/MCP核对远端任务；本地mock只验本地路径。
- **前置/兼容/回滚**：先约定各资源owner和远端状态/取消契约，新增查询、残留状态和代次字段保持旧客户端/旧实例兼容。按执行链灰度；回滚先摘流并有界排空，保留残留任务记录和限制，不能重新放开未知任务或自动重跑。

<a id="hardening--w08-高成本查询与可选旁路隔离"></a>
<a id="w08"></a>
### W08 高成本查询与可选旁路隔离

**责任**：应用主责，DBA/性能测试协作；R05/R07。

- **查询**：从真实计划优化版本递归、首assistant摘要、最后Run投影、完整tree/分支和分页计数；只传必要字段，限定行数/总字节和独立查询并发；数据库及应用CPU分别看profile，不预先假定某种索引有效。
- **旧旁路**：给旧偏好、RouteMemory、识别记录按使用场景设置排队期限和SQL预算；过期未启动任务不能随后执行。写任务已开始时要得到确定事务结果，不用整体Reactive timeout向前端制造不明迟到写。
- **标题**：任务入口按Session合并、限制完整生命周期在途数；候选读短期限且记录path rows/run IDs，生成许可和提交TX2s保留。生成失败/退出保留原标题，是否可靠补跑单独按产品SLO立项，默认不增加基础设施。
- **前置/接口**：实际数据分布/字段上限和启用开关快照；保持人工标题、版本、scope、排序时间和反馈语义。大历史新增分页/拒绝需前端契约更新，不能暗中减少用户看到的内容。
- **观测/测试**：队列年龄、排队过期/执行中、SQL实际时长、摘要字节、标题candidate/generated/applied/skipped、主Run首事件；慢worker/长历史/深树/锁竞争及多功能突发混合负载。
- **发布/回滚**：优先独立旁路灰度；标题等已具备开关的功能可按既有配置禁用并确认需重启，未实现的入口不能在运行册写成可用开关。回退查询实现必须保持容量配额，防止旧路径压垮DB。

<a id="hardening--w09-部署可观测性业务目标与交接"></a>
<a id="w09"></a>
### W09 部署、可观测性、业务目标与交接

**关联**：R08，统筹所有风险的环境证据；平台/SRE主责，应用/DBA/业务负责人协作；T08及全部环境测试，RB08，D07及D01–D12。

- **基线输入**：以U中的ADS部署/参与服务共享依赖/WCM及ALB职责，以及P中的Region控制及运行面/ALB独立、跨AZ/热备/独立静态源目标为基线，收集实际有效配置与覆盖来源、CPU/堆/native/FD/磁盘、实例分布、ALB/saas gateway重试/超时/连接/ACL、DB/Redis版本及切换行为、存储/下游配额、证书/日志/备份和鉴权证据。基础拓扑已确定，不再泛称未知；目标P未实证前仍待建设/验收，不能沿用默认值或用户架构陈述代替运行证据。
- **SLI/SLO**：分别测受理、首事件、最终完成、结果恢复、Stop收口；合法业务拒绝和系统过载分开统计，后者不从可用性分母隐藏。RTO分别计服务恢复和遗留任务收口，RPO分别计已提交数据与未持久化事件；先校准再由业务签署数值门槛。
- **监控**：JVM/容器CPU、GC、堆/native/线程/FD/磁盘；各队列深度、字节和年龄、许可/拒绝、DB连接/锁、Redis重连/恢复、遗留Run/Interaction、取消残留及依赖失败/降级负载。新指标为待实现，日志不能当作已经存在的Prometheus指标；禁止未经控制使用runId/userId作为指标label。
- **探针/发布**：readiness反映能否受理业务，liveness只反映进程不可自愈失活，不将外部依赖短故障变成全实例重启；先停止新准入、处理在途流、摘流/退出并观察剩余实例恢复。平台终止宽限须与实际排空政策一致，不能用无限等待排空掩盖卡住任务。
- **测试/交接**：使用真实openGauss，以及与实际Redis部署拓扑和有效配置等价的环境，演练依赖切换、单实例退出、滚动发布/回滚、证书轮换、告警和备份恢复；生产若采用Redis Cluster，必须使用真实Cluster验证，不能用standalone替代。值班人员按运行册独立定位/止血/核对，联系人升级链和停止注入条件明确。
- **回滚**：冻结已知好版本/配置/协议/数据库兼容矩阵，触发门槛到达即停扩大发布；新增指标/运行册不要求回退。恢复依赖后逐步放量并观察积压和资源回落，不能仅以HTTP健康为成功。

<a id="hardening--w11-四服务共享依赖与admintoolda联合契约"></a>
<a id="w11"></a>
### W11 三服务共享预算、agent职责拆分与联合契约

**责任**：DBA、Redis平台、Chat/Relay/agent及下游负责人共同主责；R09/R10/R16/R17，协同W05/W06/W07；当前拓扑与目标分别见[DEP01](deployment.md#dep01)。

- **当前模块隔离**：为agentService的admin/skill-query/chat/MCP分别签认CPU、堆/native、线程/队列、连接、并发/速率/字节及排队龄预算，为Stop/状态查询/配置校验留资源；过载拒绝限定故障来源，禁止共享无限队列或仅扩大线程池。模块暂停/熔断等未实现能力先建设验证，不能直接列作运行命令。
- **DB集群预算**：收齐三服务池上限×实例数、SQL/事务/DDL、峰值与重连速率，agent按模块归因；W04负责Chat内部治理。共享DB（openGauss）为控制/恢复/维护保留额度，覆盖扩容、AZ减少和Region接管；批任务/DDL错峰，核验索引和双版本schema兼容。未来五服务额度重分配，不能叠加原池上限；不默认全面读写分离或跨库双写。
- **Redis分类及恢复**：按服务登记缓存、锁/租约/取消、Pub/Sub及若存在的持久队列，记录大小、TTL、owner与恢复规则；限制大key/热key/脚本/订阅/回源速率，前缀/ACL不等于容量隔离。故障恢复分批重建缓存、重订阅及FULL补读，按新权威建立锁；持久队列先冻结到可核对水位，再按已确认重放策略恢复，禁止整库盲复制锁与任务后自动执行。真实部署拓扑、maxmemory/淘汰/持久化必须取证。
- **目标拆分P**：定义adminService/toolService/agentService的技能管理/发布、运行时查询、统一chat、MCP/工具及文档元数据归属，加Chat/Relay共五服务。文件内容按W06直传或独立worker。先兼容契约与唯一事实写者，再按模块迁流、排空、复测共享依赖；旧Run/回调/Stop稳定到原执行者，不用双库双写制造假隔离。
- **技能合同**：管理发布、属性查询与执行mapping区分版本/目标、新鲜度、缓存失效及已知好版本回退，执行者记录实际使用版本。成功无配置、未知/撤销、缓存过期与故障分别定义；留存启用时配置查询失败阻止执行；仅附件检查的查询失败按现有开放政策，成功空支持类型仍拒绝附件。最后可用值仅在批准有效期内使用，不以关闭留存切回FULL降级；进行中的Stop仍到原目标。
- **两条执行链合同**：Chat→agent chat→DomainAgent、Chat→Relay→agent MCP→下游各自限制总调用次数、扇出、嵌套深度、结果字节与截止时间；重试由唯一层负责并记录放大比，具体策略归W05。区分请求已发/已受理/停止确认/结果未知，约定取消级联及不可取消任务预算；未知结果先查询或受限人工核对，避免重连/恢复反复执行。当前外部源码缺失的能力不得假装已存在。
- **SLA与定位**：双方签认受理、首有效事件、空闲/进展、完成、Stop真实停止、恢复的SLI及数值目标、错误来源和升级联系人。统一可信trace/operation/run/session/message/parent-call/remote-task、服务/模块/实例/Region/skill/配置版本、阶段耗时/尝试/剩余预算；[trace占位](../../../src/main/java/com/huawei/it/ex/one/infrastructure/trace/JalorTraceContextProvider.java#L13)及未透传字段为待实施协议任务，优先复用现有可信ID。脱敏日志，不用高基数ID做指标label；观察到超时不直接当作已确定根因。
- **验收/发布/回滚**：T09/T10/T16/T17逐服务/模块施压和共享依赖故障，联合T19–T21核对两链资源/重试/取消和定位时间。先预算/观测/契约，后拆分迁流；无提供方证据的子项BLOCKED。回滚保持schema兼容、同一事实唯一写者及Run稳定归属，先核对在途量与旧模块容量，不自动重放未知任务。

<a id="hardening--w12-wcm独立静态备用源与alb区域入口"></a>
<a id="w12"></a>
### W12 WCM独立静态备用源与ALB区域入口

**责任**：WCM、对象存储、网络/ALB及前端共同主责；R11/R12，协同R02；对应[DEP04](deployment.md#dep04)。

- **预发布静态源**：每次发布同时生成不可变版本目录、manifest/hash及HTML/bundle/公共运行配置，将备用产物提前放到独立于ADS的对象存储发布源，由独立HTTPS托管能力对外服务。对象存储API不自动具备静态HTTPS/SPA回退/私有源鉴权，须实测ALB可接入；不能临时加ADS内唯一代理后宣称仍独立。验证证书、发布权限和访问链路不依赖故障ADS；备用版本由独立探针持续核对，不在WCM失效后临时构建/上传。
- **兼容与安全**：保持文根/相对资源路径、缓存版本、登录回调、API区域地址及CORS/CSP；运行配置不得包含凭证。静态备用只提供页面与资源，API/WS/agentService/relayService仍走选定Region的服务链路，首页成功不计作聊天恢复；后台不可用时页面明确说明状态。
- **ALB配置**：产出各Region域名/文根/路径/目标组矩阵，API请求不得被SPA静态回退吞掉；分别验收HTTP上传下载、WS Upgrade、SSE flush/缓存及idle/heartbeat预算，禁止未经幂等保护的POST自动重试。ALB/saas gateway/Servlet/下游期限的各段关系进入有效配置证据，不用应用30分钟期限推定入口能保持30分钟。
- **切换**：WCM故障可先切静态源，但不因此改动API写Region；区域切换由W14授权顺序执行。GSLB/DNS刷新仅影响重新解析和新连接，旧连接需摘流/关闭并引导客户端按游标、退避和恢复配额重连；旧Region必须先阻断写入，不能依赖TTL实现防双写。
- **前置/接口**：WCM/对象存储提供实际托管能力和URL，网络提供ALB/GSLB配置，前端提供base path/配置加载契约。保持既有API格式；备用页面缺依赖或版本不符时停止切换，不能把404资源或API HTML当作健康。
- **观测/测试**：版本/hash、静态资源失败率、文根命中、WS/SSE连接/断流、真实Region落点、DNS缓存及重连量。D10隔离WCM/ADS相关路径后测试独立备用、故意保留旧DNS/socket、文根嵌套、idle流和恢复风暴，分别报告页面与业务恢复。
- **发布/回滚**：先完成主备同版本预发布/验证再变更入口；保留上一不可变静态版本，回切先核对目标健康和缓存兼容再逐步切回。API写Region不可随静态源回滚自动变化；ALB错误路由回退后核对未误重放POST。

<a id="hardening--w13-ads故障域隔离和az失效容量"></a>
<a id="w13"></a>
### W13 ADS故障域隔离和AZ失效容量

**责任**：ADS平台、网络/DBA与参与服务容量负责人共同主责；R13/R14，协同R08/R09/R10；对应[DEP02](deployment.md#dep02)、[DEP03](deployment.md#dep03)。

- **控制/运行面**：为验证P中的Region独立，分别登记两个Region的ADS运行面与控制面入口、账号/权限、配置/密钥下发、镜像/制品、监控和网络依赖，列出仍共用的外围服务。控制面失效时先冻结发布/扩缩容/变更，确认健康实例可继续运行及其限制；运行面失效按业务探针、实例/依赖证据处理，不因单一控制探针故障立即切Region。
- **实际落点**：验证参与服务副本、工作节点、ALB目标、DB/Redis区域内HA成员和出站依赖路径跨至少两个AZ分布，配置反亲和/故障域约束并检查调度结果。发布/维护窗口也不得短暂移除所需故障余量；“有多个副本”不能替代跨AZ证据。
- **容量**：针对最大故障AZ退出，逐服务测剩余安全承载量及共享DB/Redis/下游配额，要求覆盖批准的故障期业务量、治理和恢复预算；冻结正常/故障期限流值与优先级。必要容量预留到位，不能仅依赖故障后失效控制面扩容；任何动态降级开关都需先实现/演练，否则使用现有且验证过的入口/部署控制。
- **前置/接口**：平台需提供可验证的AZ映射、调度/摘流行为、容量及权限，未获证据不能把ADS抽象等同某个公开云产品保证。参与服务已有状态/事件语义不变，AZ内故障不默认触发跨Region数据提升。
- **观测/测试**：控制API与业务请求分别监测，按AZ观察副本/目标、成功率、队列/许可/连接/CPU/FD、回源和恢复积压。D11分别控制面隔离、运行面失效、完整AZ网络隔离，并覆盖AZ恢复后的重连/放量，不能用单Pod退出代替AZ演练。
- **发布/回滚**：先单服务验证故障域策略，再参与服务混合负载与共享依赖组合演练；错误调度策略回退时仍保留跨AZ最低分布。故障AZ恢复先验证副本/依赖健康后分批纳入目标组，避免重连和建连同时冲击数据库。

<a id="hardening--w14-region故障接管防双写与受控回切"></a>
<a id="w14"></a>
### W14 Region故障接管、防双写与受控回切

**责任**：容灾指挥、DBA、ADS/网络和参与服务负责人共同主责；R15，协同R08/R09/R10/R11–R14；对应[DEP05](deployment.md#dep05)、[DEP06](deployment.md#dep06)。

- **目标/前置**：建设并验收P中的双Region预部署热备单写。两个Region的代码、schema兼容、技能mapping、凭证/CA、静态产物版本、业务附件对象恢复能力及其DB引用/权限对账、下游/企业鉴权网络和独立控制入口必须在故障前验证；备Region后台任务和管理写入默认不得取得业务写权。DB按区域内HA、跨Region异步复制设计，不承诺RPO=0，不通过全面读写分离改变事实源。
- **单写控制**：定义所有写路径的阻断证据，覆盖参与服务API、技能管理写入、agentService chat/MCP执行、后台任务/Watchdog、异步回调及长连接。旧Region写入须由可独立验证的数据库/网络/凭证或平台隔离机制撤销，具体命令由平台实测固化；Chat的同库fencing、DNS TTL或“旧Region访问不到”不能替代防双主。无法证明旧写已阻断时停止提升，保持写不可用并升级处理。
- **接管顺序**：DEP05-01–02判定影响/受控接管、停旧准入且备端未开放→03–04隔离旧应用外部执行与旧DB写入并取证→05–06核对最终可恢复水位、业务附件对象恢复点及DB引用/权限缺口、批准RPO，超界或无法核对则停止→07提升唯一DB写端并按W11分类准备Redis→08按已核对的schema/映射/凭证和agentService/relayService任务策略激活有权服务→09–10用限定验证流量核对业务、附件读取/引用/权限和唯一写入→11切GSLB/DNS/ALB→12–13处置旧连接、分批Resume并对账遗留任务。任一阶段无法证明旧写/旧外部执行已隔离，执行14保持备用禁写；早期可预查复制延迟，但不能替代隔离后的恢复点确认。
- **数据/副作用**：FULL只回放新主实际存在的数据；no-store不补存正文。丢失水位覆盖的Run/消息/工具请求及业务附件对象需列清单核对，不因“新主没有记录”自动重新执行；外部副作用按稳定操作ID查询/UNKNOWN人工对账。业务附件对象恢复点与DB引用/权限须独立于静态产物核验；超出签认RPO或无法核对阻断激活/开放，已批准损失列明对象、引用、影响及责任人，并隔离受影响功能，仅声明通过验证的服务范围恢复。缓存可重建，锁/租约/取消标记不继承旧执行权；若存在持久队列，先核对两侧消费水位和幂等再恢复，禁止整库复制后盲重放。
- **回切顺序**：回切是独立变更，故障恢复不自动回切。DEP06-01–03修复旧Region并作为只读/被隔离目标提前反向同步、验证；04当前主停新写/调度并有界排空→05预收集恢复水位和未知任务，不能作为最终追平门槛→06强制隔离当前主DB写入、应用及外部执行→07取得或证明隔离后的最终提交位点，目标追平该位点并校验业务附件对象恢复点、DB引用/权限和批准损失边界→08目标激活为唯一写端→09参与服务/Redis/技能映射/下游及附件验收→10切入口/关闭旧连接→11重建灾备复制→12观察。提前同步不替代隔离后的最终核对；未追平、附件超签认RPO或无法核对、任一侧仍可写或存在未决冲突则执行13停在已知安全阶段，不自动合并冲突数据。
- **接口/迁移**：持久恢复操作状态、写权epoch/操作ID、UNKNOWN或新查询能力若现有平台/协议不支持，单独作为实现任务；原OpenAPI/事件留存不默改。故障期间停止服务采用已验证的部署/网络/网关/数据库操作，不假设已存在应用动态总开关；DB提升本身不是普通代码回滚。
- **观测/测试**：统一故障时间线，记录复制水位/延迟、隔离后最终提交位点及追平证据、业务附件对象恢复点和引用/权限缺口、各侧写入探针、入口实际落点、DNS/连接残留、UNKNOWN操作、Run/Interaction/Binding和队列对账、实际服务RTO/遗留任务RTO/数据及附件RPO。D12覆盖Region失效、网络分区、复制滞后、保持旧DNS/socket/回调、提升失败、回切预收集后隔离前迟到提交、附件落后/权限不一致、回切中断和恢复后负载；每阶段证明最多一个写权威，并验证损失超界/无法核对时阻断以及已批准损失下的功能隔离。
- **失败与回退**：提升前失败不自动撤销已经执行的隔离；只有重新证明原权威有效、备用仍无写权且数据/外部任务可核对，才能恢复原侧服务，否则保持受控不可写。提升后不得直接恢复旧主写或仅切回DNS，须按同样单写/同步/对账原则重新决策。保留切换证据、潜在丢失和外部执行清单；服务恢复、数据核对及积压收敛全部满足才结束容灾，不以ALB健康200结案。

<a id="hardening--w10-文档信任边界与-obs-tls"></a>
<a id="w10"></a>
**W10去向**：文档信任边界与OBS TLS专项移出本轮，未实施、未关闭；不计入13个活跃工作包。

<a id="hardening--统一关闭与未完成项移交"></a>
### 关闭与交接

每项提交修复/配置版本、T用例与D演练、规模/注入/持续时间、CPU/内存/线程/连接/队列曲线、清理/恢复结果及复核人；与S/E/U/P对应，不以本地mock、HTTP200或注入撤销代替验收。未完成项登记责任人、功能/容量限制、监控、接受人及到期日。数值SLO/RTO/RPO、有效平台配置或真实下游合同缺失时保持OPEN/ENV，对应用例标明BLOCKED/NOT_RUN，不能宣布上线验收完成。
