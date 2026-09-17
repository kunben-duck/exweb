# 高可用风险与加固任务

本页统一维护风险和整改：[R01–R35风险登记](#risk-register) → [W01–W14实施任务](#hardening)。证据、触发条件和已有保护记录在R项；具体整改、兼容与回滚维护在W项；测试和故障操作分别见[tests](tests.md)、[operations](operations.md)。

<a id="risk-register"></a>

<a id="risk-register--当前实现高可用风险登记"></a>
## 当前实现高可用风险登记

复核日期：2026-09-18。代码基线：`00abae4f80b7e7e5b4d0ddca707035f1878a8ec8`。本页核对源码、Mapper、配置及本地依赖字节码，并结合用户确认的部署拓扑登记 R01–R35。不修改业务实现，未执行故障复现或平台演练。Admin、Tool、Relay、ADS、WCM 的实现和平台配置未在本仓库完成源码审计，相关风险不能当作已证明的实现缺陷。

<a id="risk-register--证据状态与关闭规则"></a>
### 证据、状态与关闭规则

- **S（源码确认）**：下述实现或配置事实可直接定位；故障发生的容量、概率和持续时间仍需实验。**L（本轮复现）**：本轮真实运行故障并有结果附件；本页没有这样的故障复现。**E（环境待验证）**：生产覆盖配置、部署、依赖协议或故障行为尚无证据。
- **U（用户确认）**：四服务Admin/Tool/Relay/Chat共享DB、Redis并运行于ADS；Chat→Tool→DA，Chat直接调用Relay，Intent为第三方；各Region的ADS运行面/控制面及ALB独立，WCM托管Web静态资源、ALB管理文根。**P（选定目标及措施）**：Region内至少两个AZ多副本、GSLB/DNS受控主备、跨Region预部署热备单写、DB区域内HA/跨Region异步复制、独立非ADS的预发布对象存储静态备用源，以及配额、版本对账、隔离/接管/回切控制；不启用全面读写分离。用户选定P不等于平台已具备；U同样不能代替配置导出、容量或故障行为证据，运行结果仍为E。
- **OPEN**：代码模式仍存在，等待整改或明确接受条件。**ENV**：上线条件或环境保证待验证。两者都不表示生产已经发生事故。P1 是启用对应功能或达到对应容量前必须解决的阻断项；P2 是恢复、性能或局部一致性缺口，环境证据可提高其优先级。
- 各条“关闭证据”均为**待取得**，目前没有 CLOSED 项。关闭须附修复提交、有效配置、对应 T 用例记录、关联演练结果、资源/业务指标、复核人及日期；对接口正确性的单元断言不能代替跨实例或真实下游验收。
- 无法立即修复的风险须填写接受人、功能/容量限制、监控、失效条件、到期日和复审日期。当前未收到风险接受记录，不能据此宣布可上线。
- 场景和步骤以[当前场景图](scenarios.md#flows)为准：S01–S12为场景，C为资源关系图步骤，D为代码图步骤；未在主图逐项展开的分支以风险明细的源码方法为准，不将合并箭头视为完整调用链。编号不是协议或日志字段。
- 本页为当前风险登记；已执行检查及证据边界见[验证记录](evidence.md)。源码链接行号对应上述提交，后续变更需更新链接。

<a id="risk-register--场景风险措施测试运行册演练追踪矩阵"></a>
### 场景—风险—措施—测试—运行册—演练追踪矩阵

措施见[加固任务清单](#hardening)，T 编号对应[测试用例](tests.md)，RB、D 对应[运行册和演练](operations.md)，S 对应[场景双层图](scenarios.md#flows)，DEP 对应[部署与容灾图](deployment.md)。一个测试可同时覆盖多个风险，但不得据共享测试名称省略该风险独有断言。新增DEP图为U/P的部署视图；其平台有效配置及故障行为仍须E验证。

| 风险 | 当前状态/优先级 | 当前场景、图步骤或代码入口 | 工作包 | 主责角色 | 测试 | 运行册 | 演练 |
|---|---|---|---|---|---|---|---|
| [R01](#r01) 流式队列及累计正文 | OPEN / P1 条件性 | [S03-D1–D5](scenarios.md#s03)、[S02-D6–D7](scenarios.md#s02) | [W01](#w01) | 应用 | [T01](tests.md#t01) | [RB01](operations.md#rb01) | [D01](operations.md#d01) |
| [R02](#r02) 共享连接与实例总预算 | OPEN / P2 | [S01–S12](scenarios.md#flows)；S01-D2、S03-D4、S11-D4 | [W04](#w04) | 应用、DBA | [T02](tests.md#t02) | [RB03](operations.md#rb03) | [D02](operations.md#d02) |
| [R03](#r03) 全量恢复与恢复风暴 | OPEN / P1 条件性 | [S07-D1–D7](scenarios.md#s07)；resumeSession/resumeRunTopic | [W03](#w03) | 应用、前端 | [T03](tests.md#t03) | [RB06](operations.md#rb06) | [D05](operations.md#d05) |
| [R04](#r04) 提交后交付窗口 | OPEN / P2 | [S03-D6–D9](scenarios.md#s03)、[S05-D6–D7](scenarios.md#s05)、[S06-D6–D7](scenarios.md#s06)、[S07](scenarios.md#s07) | [W07](#w07) | 应用、前端 | [T04](tests.md#t04) | [RB09](operations.md#rb09) | [D05](operations.md#d05) |
| [R05](#r05) Relay 迟到 Stop | ENV / P1 启用条件 | [S02](scenarios.md#s02)/[S04](scenarios.md#s04)/[S05-D1–D4](scenarios.md#s05)；Relay interrupt | [W07](#w07) | Relay、应用 | [T05](tests.md#t05) | [RB05](operations.md#rb05) | [D04](operations.md#d04) |
| [R06](#r06) 鉴权与重试总期限 | OPEN / P2 | [S02](scenarios.md#s02)/[S10-D4](scenarios.md#s10)/[S11-D6](scenarios.md#s11)；鉴权与attempt重试 | [W05](#w05) | 应用、鉴权/下游 | [T06](tests.md#t06) | [RB05](operations.md#rb05) | [D04](operations.md#d04) |
| [R07](#r07) 事务内缓存 | OPEN / P2 | [S02](scenarios.md#s02)/[S04](scenarios.md#s04)；兼容续跑创建及固定专家Binding事务，源码见R07 | [W04](#w04) | 应用 | [T07](tests.md#t07) | [RB03](operations.md#rb03)/[RB04](operations.md#rb04) | [D03](operations.md#d03) |
| [R08](#r08) 非幂等重提 | OPEN / P2 | [S01-D4–D7](scenarios.md#s01)、[S10-D1](scenarios.md#s10) | [W07](#w07) | 应用、前端 | [T08](tests.md#t08) | [RB09](operations.md#rb09) | [D08](operations.md#d08) |
| [R09](#r09) Redis 接收线程 | OPEN / P1 条件性 | [S03-D9](scenarios.md#s03)、[S06-D7](scenarios.md#s06)、[S07](scenarios.md#s07) | [W02](#w02) | 应用 | [T09](tests.md#t09) | [RB04](operations.md#rb04)/[RB01](operations.md#rb01) | [D03](operations.md#d03) |
| [R10](#r10) 旧旁路排队/SQL期限 | OPEN / P2 | [S02](scenarios.md#s02)/[S10-D8](scenarios.md#s10)/[S11](scenarios.md#s11)；偏好与记录旁路 | [W08](#w08) | 应用、DBA | [T10](tests.md#t10) | [RB03](operations.md#rb03) | [D08](operations.md#d08) |
| [R11](#r11) 历史查询放大 | OPEN / P2 | [S08-D1](scenarios.md#s08)、[S11-D4](scenarios.md#s11) | [W08](#w08) | 应用、DBA | [T11](tests.md#t11) | [RB03](operations.md#rb03)/[RB02](operations.md#rb02) | [D08](operations.md#d08) |
| [R12](#r12) 文件生命周期 | OPEN / P2，容量可升 P1 | [S09-D1–D7](scenarios.md#s09)；upload/download | [W06](#w06) | 应用、存储 | [T12](tests.md#t12) | [RB07](operations.md#rb07)/[RB01](operations.md#rb01) | [D06](operations.md#d06) |
| [R13](#r13) 治理预算不完整 | OPEN / P2 | [S12-D4–D8](scenarios.md#s12)；recoverExpiredRuns | [W04](#w04) | 应用、DBA | [T13](tests.md#t13) | [RB03](operations.md#rb03)/[RB06](operations.md#rb06) | [D02](operations.md#d02) |
| [R14](#r14) 取消/删除收口窗口 | OPEN / P2 | [S05-D1–D7](scenarios.md#s05)、[S08-D7](scenarios.md#s08)、[S12-D5–D8](scenarios.md#s12) | [W07](#w07) | 应用 | [T14](tests.md#t14) | [RB09](operations.md#rb09) | [D05](operations.md#d05) |
| [R15](#r15) 索引部署未验 | ENV / P2 | [S08](scenarios.md#s08)/[S12](scenarios.md#s12)；schema validator | [W04](#w04) | DBA、发布 | [T15](tests.md#t15) | [RB03](operations.md#rb03)/[RB08](operations.md#rb08) | [D07](operations.md#d07) |
| [R16](#r16) 配置 miss/线程边界 | OPEN / P2 条件性 | [S02-D2–D5](scenarios.md#s02) | [W05](#w05) | 应用 | [T16](tests.md#t16) | [RB05](operations.md#rb05)/[RB03](operations.md#rb03) | [D04](operations.md#d04) |
| [R17](#r17) 删除后的恢复访问 | OPEN / P2 | [S07-D2](scenarios.md#s07)/[S08](scenarios.md#s08)；ensureOwnedSession | [W07](#w07) | 应用、前端 | [T17](tests.md#t17) | [RB09](operations.md#rb09) | [D08](operations.md#d08) |
| [R18](#r18) 分支/分享并发原子性 | OPEN / P2 | [S08-D3](scenarios.md#s08)、[S10-D1](scenarios.md#s10)；createBranch/create | [W07](#w07) | 应用 | [T18](tests.md#t18) | [RB09](operations.md#rb09) | [D08](operations.md#d08) |
| [R19](#r19) WeLink 未知结果重发 | OPEN / P2 启用条件 | [S10-D2–D5](scenarios.md#s10)；deliver/callOnce | [W07](#w07) | 应用、WeLink | [T19](tests.md#t19) | [RB09](operations.md#rb09) | [D08](operations.md#d08) |
| [R20](#r20) 联调样例恢复/业务错误 | OPEN / P2，仅样例 | [S07](scenarios.md#s07)；handleWsEnvelope/requestJson | [W03](#w03) | 前端 | [T20](tests.md#t20) | [RB06](operations.md#rb06) | [D05](operations.md#d05) |
| [R21](#r21) 文档可信引用被覆盖 | OPEN / P1 条件性 | [S01-D3](scenarios.md#s01)、[S02-D2–D3](scenarios.md#s02)、[S09-D8](scenarios.md#s09) | [W10](#w10) | 应用、安全/下游 | [T21](tests.md#t21) | [RB10](operations.md#rb10) | [D06](operations.md#d06) |
| [R22](#r22) OBS TLS 校验 | OPEN / P1，huawei-s3 条件 | [S09](scenarios.md#s09)；financeExHuaweiObsClient | [W10](#w10) | 应用、存储/PKI | [T22](tests.md#t22) | [RB10](operations.md#rb10) | [D06](operations.md#d06) |
| [R23](#r23) 删除事务期限 | OPEN / P2 | [S08-D4–D5](scenarios.md#s08)；deleteSession/deleteSessions | [W04](#w04) | 应用、DBA | [T23](tests.md#t23) | [RB03](operations.md#rb03) | [D02](operations.md#d02) |
| [R24](#r24) 活动路径并发覆盖 | OPEN / P2 | [S01-D4](scenarios.md#s01)、[S03-D7](scenarios.md#s03)、[S08-D2](scenarios.md#s08)；selectPath | [W07](#w07) | 应用、前端 | [T24](tests.md#t24) | [RB09](operations.md#rb09) | [D08](operations.md#d08) |
| [R25](#r25) 标题完整生命周期预算 | OPEN / P2，启用条件 | [S11-D3–D8](scenarios.md#s11) | [W08](#w08) | 应用 | [T25](tests.md#t25) | [RB03](operations.md#rb03)/[RB02](operations.md#rb02) | [D08](operations.md#d08) |
| [R26](#r26) JSON/序列化 CPU 与分配 | OPEN / P2 条件性 | [S01](scenarios.md#s01)、[S03-D1–D5](scenarios.md#s03)、[S06-D2–D4](scenarios.md#s06)、[S11-D2](scenarios.md#s11) | [W01](#w01)/[W08](#w08) | 应用、性能测试 | [T26](tests.md#t26) | [RB02](operations.md#rb02)/[RB01](operations.md#rb01) | [D01](operations.md#d01) |
| [R27](#r27) 部署参数与业务健康未验 | ENV / P1 上线门槛 | [S01](scenarios.md#s01)/[S07](scenarios.md#s07)/[S12](scenarios.md#s12)；网关、连接与实例退出 | [W09](#w09) | 平台/SRE、应用 | [T27](tests.md#t27) | [RB08](operations.md#rb08) | [D07](operations.md#d07) |
| [R28](#r28) 四服务共享 DB 争用 | ENV / P1 条件性 | S01–S12；[DEP01-N14](deployment.md#dep01)、[DEP02-N07](deployment.md#dep02)、[DEP03-N05/N11](deployment.md#dep03) | [W11](#w11) | DBA、四服务负责人 | [T28](tests.md#t28) | [RB11](operations.md#rb11) | [D09](operations.md#d09) |
| [R29](#r29) 四服务共享 Redis 故障放大 | ENV / P1 条件性 | S02/S03/S07/S12；[DEP01-N15](deployment.md#dep01)、[DEP02-N08](deployment.md#dep02)、[DEP03-N06/N12](deployment.md#dep03) | [W11](#w11) | Redis平台、四服务负责人 | [T29](tests.md#t29) | [RB11](operations.md#rb11) | [D09](operations.md#d09) |
| [R30](#r30) Admin映射与Tool执行未知结果 | ENV / P1 条件性 | S02/S04/S05/S06；[DEP01-N09/N11/N12](deployment.md#dep01) | [W11](#w11) | Admin/Tool/DA、Chat | [T30](tests.md#t30) | [RB11](operations.md#rb11) | [D09](operations.md#d09) |
| [R31](#r31) WCM独立静态备用源不可用 | ENV / P2，访问入口条件 | S01/S07/S12；[DEP01-N03–N05](deployment.md#dep01)、[DEP04-01–04/10–12](deployment.md#dep04) | [W12](#w12) | WCM/对象存储、前端 | [T31](tests.md#t31) | [RB12](operations.md#rb12) | [D10](operations.md#d10) |
| [R32](#r32) ALB文根/流式/区域入口 | ENV / P1 条件性 | S01/S03/S07/S09；[DEP01-N02/N08](deployment.md#dep01)、[DEP04-05–09/11–12](deployment.md#dep04)、[DEP05-11–12](deployment.md#dep05) | [W12](#w12) | 网络/ALB、前端、应用 | [T32](tests.md#t32) | [RB12](operations.md#rb12) | [D10](operations.md#d10) |
| [R33](#r33) ADS控制面与运行面故障 | ENV / P1 条件性 | S01–S12；[DEP02-N02–N06/N09](deployment.md#dep02)、[DEP03-N04/N10/N15](deployment.md#dep03) | [W13](#w13) | ADS平台、四服务负责人 | [T33](tests.md#t33) | [RB13](operations.md#rb13) | [D11](operations.md#d11) |
| [R34](#r34) AZ失效后的安全容量不足 | ENV / P1 条件性 | S01–S12；[DEP02-N01–N09](deployment.md#dep02) | [W13](#w13) | ADS/网络/DBA、容量负责人 | [T34](tests.md#t34) | [RB13](operations.md#rb13) | [D11](operations.md#d11) |
| [R35](#r35) Region分区/接管/回切一致性 | ENV / P1 容灾门槛 | S01–S12；[DEP03-N15](deployment.md#dep03)、[DEP05-01–14](deployment.md#dep05)、[DEP06-01–13](deployment.md#dep06) | [W14](#w14) | 容灾指挥、DBA、平台、四服务负责人 | [T35](tests.md#t35) | [RB14](operations.md#rb14) | [D12](operations.md#d12) |

<a id="risk-register--风险复核明细"></a>
### 风险复核明细

<a id="risk-register--r01-流式桥接队列和累计正文缺少总量边界"></a>
<a id="r01"></a>
#### R01 流式桥接队列和累计正文缺少总量边界

- **证据 S**：[Relay BUFFER](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java#L236)在 L265/L301/L649 显式使用 BUFFER；[DomainAgent 总期限包装](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/domainagent/ConfiguredDomainAgentClient.java#L180)使用 `Flux.create` 并内部直接订阅上游；[事件流水线](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatEventPipeline.java#L76)切换 Event IO 后顺序持久化；[Assembly](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/AssistantAssembly.java#L123)持续 append，Parts 在 L120 增长。
- **触发与影响**：下游持续快于事件落库或生成超长回答，桥接排队、累计草稿和 Parts 同时驻留；可能引起堆耗尽、GC、首事件/Stop 变慢，影响同 JVM 全功能。未进行 OOM 压测，阈值 E。
- **已有保护/剩余缺口**：单帧大小、下游并发、总运行期限、批次边界及 dispose 清理存在；它们不等同每 Run/实例累计字节上限。placeholder/no-store在Assembly跳过真实正文和业务Parts累计，但帧解析、标准化及队列仍分配对象，不能套用FULL正文累计模型，也不能视为零内存开销。
- **加固措施**：[W01](#w01)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T01/D01 在快流＋慢 DB、长正文、取消/异常下证明内存和队列有界、正确终态一次、正常事件不丢；提供队列字节、堆/GC、许可与残留订阅曲线。

<a id="risk-register--r02-共享数据库竞争及实例总预算缺口"></a>
<a id="r02"></a>
#### R02 共享数据库竞争及实例总预算缺口

- **证据 S**：[Hikari 默认 10/借用 500ms](../../../src/main/resources/application.yml#L29)；[准入](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/RunAdmissionControlService.java#L55)仅用户速率和本机租户 semaphore，无跨租户总 Run semaphore；[旁路候选](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionTitleApplicationService.java#L164)也访问共享仓储。`tenantSemaphores.computeIfAbsent` 未见租户淘汰；用户窗口在 L90 定时清理，不能混称两者都未清理。
- **触发与影响**：多租户、回放、附件、旁路、治理叠加或单 SQL 变慢，有限连接被争抢；主 Run、Stop、心跳及辅助接口相互拖累。高租户基数另会保留 semaphore 对象，是否重要由真实租户规模决定。
- **已有保护/剩余缺口**：多处 bulkhead、SQL 短事务和连接借用超时可快速拒绝；默认 200 个租户 Run 许可与每类下游 64 不能相加当容量，也不保证治理公平。虚拟线程不增加 DB 容量。
- **加固措施**：[W04](#w04)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T02/D02 混合多租户稳态、慢库和大量短期租户，记录借用等待/拒绝、公平性、首事件与治理时延，证明总连接和内存回到预算；不以扩大池作为验收。

<a id="risk-register--r03-resume-全量物化且-http-恢复缺少独立配额"></a>
<a id="r03"></a>
#### R03 Resume 全量物化且 HTTP 恢复缺少独立配额

- **证据 S**：[resumeSession/resumeRunTopic](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatStreamApplicationService.java#L251)及 [resumeRunWithLiveTail](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatStreamApplicationService.java#L429)从完整 List 回放；[Store](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/MyBatisChatEventStore.java#L346)先转换所有行；[Mapper](../../../src/main/resources/mapper/persistence/ChatEventMapper.opengauss.xml#L124)对应两查询无 LIMIT。L364 已有局部有界窗口接口，可评估复用，不能把它当作 Resume 已分页。
- **触发与影响**：长 Run、afterSeq=0、多页签及故障后集中刷新，引起 DB/JSON/堆和 live 订阅放大；HTTP 路径不由 WS 连接数配额覆盖，影响全实例。
- **已有保护/剩余缺口**：归属检查、先订阅 live、live 有界缓冲、去重及短窗口重排已有实现；历史 List 和恢复并发仍需独立控制，不能按全局 sequence 连续性等待 `seq+1`。
- **加固措施**：[W03](#w03)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T03/D05 长历史恢复与 live 并发、取消、跨实例、Redis 重连风暴，逐事件比对无遗漏/重复、内存有界且正常 Run/Stop 仍达标。

<a id="risk-register--r04-db-commit-到客户端消费之间没有可靠交付承诺"></a>
<a id="r04"></a>
#### R04 DB commit 到客户端消费之间没有可靠交付承诺

- **证据 S**：[终态提交后处理](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunCompletionCoordinator.java#L189)先缓存再发布；[普通批次](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatEventPipeline.java#L169)按留存分段处理。独立 DB commit、Redis 入队、发送和消费并非同一事务。
- **触发与影响**：进程在 commit 后 publish 前退出、Redis 失败、客户端掉线；在线用户缺终态或业务结果。FULL 的已存事件可回放；no-store 业务 payload 本来不可恢复，不能承诺零结果丢失。
- **已有保护/剩余缺口**：DB 原子终态、Resume、发布端恢复标记已有；不等于前端 ACK 或持久投递队列。回调 accepted 表示本地提交，不表示用户已读。
- **加固措施**：[W07](#w07)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T04/D05 在 commit/cache/publish/consume 间分别 kill，FULL 恢复已提交事实；no-store 缺口可感知且不伪造正文，未恢复部分有已接受的产品承诺。

<a id="risk-register--r05-relay-session-级迟到-stop-可能影响后续-run"></a>
<a id="r05"></a>
#### R05 Relay session 级迟到 Stop 可能影响后续 Run

- **证据 S/E**：[停止编排](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStopCoordinator.java#L160)先 CANCELLING 再等控制发送；[Relay interrupt](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/relay/RelayWebSocketRuntimeAdapter.java#L1379)发送 `stop_all_agents`，L1412 可在 flushed 或 paused 时完成。服务端保证不能从本地确认推出。
- **触发与影响**：Run A stop 迟到/失败后本地收口，B 复用同 runtimeSessionId，再收到 A 的 session 级 stop；B 可能被误停。包括 Relay 可续接路径；当前 [Domain Expert 正常完成](../../../src/main/java/com/huawei/it/ex/one/application/service/runtime/RuntimeBindingApplicationService.java#L587)保持 ACTIVE，不限于前端固定专家。
- **已有保护/剩余缺口**：CANCELLING 阻止控制等待期间同会话准入；active socket 校验 requestedRunId、控制期限和本地 fencing 有效。它们不能证明 Relay 跨连接/实例代次隔离，故状态 ENV。
- **加固措施**：[W07](#w07)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T05/D04 真 Relay 双实例、控制帧延迟/连接中断/重排、快速下一 Run，B 不受 A stop 影响；mock 只证明客户端行为，不足以关闭。

<a id="risk-register--r06-鉴权重试与业务期限不组成总预算"></a>
<a id="r06"></a>
#### R06 鉴权、重试与业务期限不组成总预算

- **证据 S**：[阻塞 Intent](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentService.java#L142)、[用例库](../../../src/main/java/com/huawei/it/ex/one/infrastructure/usecase/HttpUseCaseLibraryClient.java#L54)、[WeLink](../../../src/main/java/com/huawei/it/ex/one/infrastructure/share/WelinkChatShareDeliveryProvider.java#L75)构建请求时同步取 header；HTTP timeout 在后。主 Intent [默认策略](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/DefaultIntentRetryPolicy.java#L19)广泛重试降级结果；[流式重试](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentStreamClient.java#L311)立即进入下一 attempt。
- **触发与影响**：企业 token resolver 阻塞、401/协议错误持续、429/断流引起多次尝试，占线程、网络和 Run 许可，放大故障下游压力。
- **已有保护/剩余缺口**：[流式 auth](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/FinEurekaIntentStreamClient.java#L154)已有独立 scheduler 和 timeout，候选查询也已有自己的策略；不能把二者写成无保护。流式总期限在每 attempt 重置，不含前置及 auth；逻辑取消不保证不可中断 resolver/JDBC 退出。
- **加固措施**：[W05](#w05)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T06/D04 阻塞 token、401、429、半开/断流，核对实际尝试次数和总耗时、线程/连接最终释放、原有降级政策；不能仅验收调用方收到超时。

<a id="risk-register--r07-仍有部分事务包含-redis-调用"></a>
<a id="r07"></a>
#### R07 仍有部分事务包含 Redis 调用

- **证据 S**：[兼容续跑创建](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunApplicationService.java#L136)锁 Session 后 `cache.putActive`；[固定专家 Binding](../../../src/main/java/com/huawei/it/ex/one/application/service/runtime/RuntimeBindingApplicationService.java#L144)锁 run/execution 后进入 L164 方法，L191 写 cache。
- **触发与影响**：Redis 慢或重试时持 DB 锁/连接等网络操作，热点会话等待扩大，严重时共享池耗尽。
- **已有保护/剩余缺口**：事务有 10s/2s，Redis command 默认 500ms；SQL timeout 不能直接停止 Java 缓存调用。标准准入 DB-only 和删除提交后缓存路径已存在，不能概称所有 Run 都在事务内调 Redis。
- **加固措施**：[W04](#w04)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T07/D03 Redis get/put/evict 阻塞、失败和事务回滚，证明事务期没有缓存访问，连接占用缩短且无旧缓存越权覆盖。

<a id="risk-register--r08-commandid-字段存在但不足以保证端到端幂等"></a>
<a id="r08"></a>
#### R08 commandId 字段存在但不足以保证端到端幂等

- **证据 S**：[请求 DTO](../../../src/main/java/com/huawei/it/ex/one/interfaces/chat/dto/CreateChatRunRequest.java#L48)有 commandId；[startStandard](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStartCoordinator.java#L57)每次订阅创建新 runId，当前 commandId 调用链未见以 owner＋commandId 去重的准入查询/唯一约束；[分享创建](../../../src/main/java/com/huawei/it/ex/one/application/service/share/ChatShareApplicationService.java#L76)生成新 shareId。
- **触发与影响**：已提交但 HTTP 响应丢失，原 Run 已完成后盲目 NEXT 重试或重复创建分享，产生重复消息、任务/快照。若原 Run 仍活动，唯一索引阻止并发不等于可返回原请求结果。
- **已有保护/剩余缺口**：会话活动唯一约束、Interaction CAS、部分反馈幂等均有效；不能推广为所有入口幂等。当前启动结果已有 userMessageId，但响应丢失时同样不可得。
- **加固措施**：[W07](#w07)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T08/D08 在提交前后丢响应并跨实例重复提交，原请求可识别、同键不重复创建、不同请求可正常执行；协议/前端兼容矩阵齐全。

<a id="risk-register--r09-redis-入站-listener-没有显式有界执行器"></a>
<a id="r09"></a>
#### R09 Redis 入站 listener 没有显式有界执行器

- **证据 S**：[构造器](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java#L73)手工创建 listener，仅设 connectionFactory；本轮对本地 Spring Data Redis 3.4.6 `createDefaultTaskExecutor` 及 Spring Core 6.2.7 字节码复核：默认 `SimpleAsyncTaskExecutor`，concurrencyLimit=-1，未设置 virtual delegate。属于静态依赖确认，不是本轮消息压力实验。
- **触发与影响**：突发 Pub/Sub fanout 或 handler 解析变慢，不断创建平台线程，消耗 native memory/CPU，影响整个 JVM 和跨实例实时链路。
- **已有保护/剩余缺口**：[发布端](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java#L152)有排队/恢复标记，前端 WS 发送端也有界；不能保护 Redis 接收执行器。
- **加固措施**：[W02](#w02)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T09/D03 阻塞 handler 后高 fanout，线程/队列恒定、顺序/去重正确、FULL 可补读、no-store 丢失信号明确；配置有效值必须纳入证据。

<a id="risk-register--r10-旧偏好及旁路缺少完整排队和-sql-期限"></a>
<a id="r10"></a>
#### R10 旧偏好及旁路缺少完整排队和 SQL 期限

- **证据 S**：[旧偏好写](../../../src/main/java/com/huawei/it/ex/one/application/service/routing/IntentPreferenceCorrectionApplicationService.java#L85)只 subscribeOn；[读取](../../../src/main/java/com/huawei/it/ex/one/infrastructure/intent/IntentPreferenceCorrectionLoader.java#L66)使用调用方 timeout；[执行器](../../../src/main/java/com/huawei/it/ex/one/application/config/IntentPreferenceExecutorConfiguration.java#L28)有界＋AbortPolicy，取 RouteMemory 配置。
- **触发与影响**：单 worker 被阻塞，队列未满但任务已无价值仍等待；读超时后 JDBC 可能继续占池；迟到写入使用户重提语义模糊。旁路可反向影响主路由和共享 DB。
- **已有保护/剩余缺口**：独立有界池、读失败开放/熔断已有；新 [反馈 dispatcher](../../../src/main/java/com/huawei/it/ex/one/application/service/routing/IntentFeedbackTaskDispatcher.java#L30)及读写短事务已有保护，不归入未加固旧入口。
- **加固措施**：[W08](#w08)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T10/D08 队列未满 worker 阻塞、排队过期/取消、SQL 不响应中断、重复 POST，验证任务不迟到启动、锁/连接释放、正常反馈兼容。

<a id="risk-register--r11-长历史版本及摘要查询随数据增长"></a>
<a id="r11"></a>
#### R11 长历史、版本及摘要查询随数据增长

- **证据 S**：[版本后继递归](../../../src/main/resources/mapper/memory/ChatMessageMapper.opengauss.xml#L402)、[首 assistant 查询](../../../src/main/resources/mapper/memory/ChatMessageMapper.opengauss.xml#L472)读取完整消息列；[列表](../../../src/main/java/com/huawei/it/ex/one/infrastructure/session/MyBatisSessionRepository.java#L169)最多 200 条但 count/offset/匹配成本不固定；[最后 Run](../../../src/main/resources/mapper/persistence/ChatRunMapper.opengauss.xml#L524)仍读完整 metadata。
- **触发与影响**：大量版本/深树、长正文/metadata、单字 contains 搜索或深分页，导致扫描/排序/JSON/堆增长，与主流程争连接和 CPU。执行计划/阈值 E。
- **已有保护/剩余缺口**：关键字查询已有专用短查询保护，最后 Run 窗口已先选索引字段后回表，不能描述为历史 metadata 全部参与排序；有页大小不等于查询和响应字节有界。
- **加固措施**：[W08](#w08)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T11/D08 深树/长历史/大 metadata/单字搜索混合主 Run，观测 rows examined、spill、CPU、Hikari 和响应体，验证内容和版本路径不变。

<a id="risk-register--r12-上传堆占用下载许可生命周期及孤儿对象"></a>
<a id="r12"></a>
#### R12 上传堆占用、下载许可生命周期及孤儿对象

- **证据 S**：[API Store upload](../../../src/main/java/com/huawei/it/ex/one/infrastructure/storage/api/ApiStoreDocumentStorage.java#L67)整份 readAllBytes；[对象存储 download](../../../src/main/java/com/huawei/it/ex/one/infrastructure/storage/object/ObjectStorageDocumentStorage.java#L74)获得输入流即归还许可，[Controller](../../../src/main/java/com/huawei/it/ex/one/interfaces/document/DocumentController.java#L179)之后交给响应输出；[上传服务](../../../src/main/java/com/huawei/it/ex/one/application/service/document/DocumentApplicationService.java#L74)存储完成后才写 DB。
- **触发与影响**：并发大上传、慢客户端持续下载、上传成功后 DB 失败/kill。默认 32×50MiB 约 1.56GiB 仅为 API Store 原始数组的条件估算，不是实测总堆；慢下载可超过许可覆盖的在途数，孤儿对象持续占存储。
- **已有保护/剩余缺口**：multipart 限额、存储调用许可、异常流关闭已有；OBS 上传直接传 InputStream，不能套用整文件 readAllBytes 结论；软删除不等于物理回收策略。
- **加固措施**：[W06](#w06)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T12/D06 大上传/慢下载/取消/DB失败/进程退出，堆、FD、连接、许可和临时文件归还，孤儿可对账且不误删历史引用。

<a id="risk-register--r13-watchdog-所有分支尚未共用完整治理预算"></a>
<a id="r13"></a>
#### R13 Watchdog 所有分支尚未共用完整治理预算

- **证据 S**：[recoverExpiredRuns](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunRecoveryOrchestrator.java#L116)先处理 Interaction/初始化孤儿/async，再过期 execution；[scan/claim 仓储](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/MyBatisChatRunExecutionRepository.java#L138)未设独立期限；[调度器](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunWatchdogScheduler.java#L55)single-flight 下实际扫描同步占 operational scheduler。
- **触发与影响**：慢扫描、claim 行锁、孤儿积压或不断失败候选；恢复数计数无法限制所有尝试，某分支可消耗本轮时间并阻塞后续轮次/治理工作。
- **已有保护/剩余缺口**：jitter、single-flight、常规 recovery permit、fencing、每租户成功数限制已有；Interaction 对账/async 等不全由相同 permit 覆盖，不可统称所有治理都有 4 并发/20 次预算。
- **加固措施**：[W04](#w04)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T13/D02 锁阻塞和混合孤儿积压后下一轮仍按预算执行，主业务和心跳不饥饿；计数覆盖失败尝试，旧 owner 不能写回。

<a id="risk-register--r14-停止方退出后-cancelling-或删除后的-run-收口延迟"></a>
<a id="r14"></a>
#### R14 停止方退出后 CANCELLING 或删除后的 Run 收口延迟

- **证据 S**：[Stop](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStopCoordinator.java#L160)分开提交 CANCELLING 和最终终态；[heartbeatBatch](../../../src/main/resources/mapper/persistence/ChatRunExecutionMapper.opengauss.xml#L127)仍可刷新匹配 claim；[恢复入口](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunRecoveryOrchestrator.java#L306)首先要求 lease 过期；[删除后 Stop](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionApplicationService.java#L483)是提交后调度。
- **触发与影响**：跨实例停止方在前一提交后 kill，而流 owner 仍活着持续续租且没有新业务事件；或 delete commit 后任务未调度即退出。会话可能长时间被活动 Run 占用或后台远端继续执行。
- **已有保护/剩余缺口**：当前恢复器 L356 已能处理扫描到的 CANCELLING，并非完全没有取消治理；但健康续租使其不进入 stale 候选，现有最终 CAS 也不保证主动发现这一窗口。
- **加固措施**：[W07](#w07)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T14/D05 两实例保持旧 owner 心跳且不发业务帧，kill 停止/删除实例，遗留 Run 在约定期限内进入唯一终态，远端执行状态另行确认。

<a id="risk-register--r15-性能索引迁移是未确认的上线依赖"></a>
<a id="r15"></a>
#### R15 性能索引迁移是未确认的上线依赖

- **证据 S/E**：[sql.init=never](../../../src/main/resources/application.yml#L35)；[启动校验](../../../src/main/java/com/huawei/it/ex/one/application/config/FinanceExDatabaseSchemaValidator.java#L52)检查 active-run 唯一索引；[最后 Run 索引脚本](../../../src/main/resources/db/incremental-20260826-chat-run-last-status-index.sql#L1)要求事务外并发创建。未取得目标库索引清单。
- **触发与影响**：新环境漏迁移、索引无效、重复执行失败未核对或旧表增长，历史/Run 状态查询变慢，阻塞其他数据库访问。
- **已有保护/剩余缺口**：活动唯一约束启动验证有效；不是所有性能索引存在性的证明，也不能断言生产已缺索引。
- **加固措施**：[W04](#w04)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T15/D07 目标等价库逐索引核验、缺失/无效/重复执行恢复及 EXPLAIN；附 DBA 执行单，不以单元测试替代。

<a id="risk-register--r16-配置并发-miss-放大及非默认组合线程风险"></a>
<a id="r16"></a>
#### R16 配置并发 miss 放大及非默认组合线程风险

- **证据 S**：[配置服务](../../../src/main/java/com/huawei/it/ex/one/application/service/domainagentconfig/DomainAgentSkillConfigurationService.java#L49)同 key 并发 miss 无合并，cache off 直接返回 provider；[Gate 后 dispatch](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRuntimeDispatchCoordinator.java#L204)在无 deferred binding 路径直接执行同步 persistResolvedRoute。
- **触发与影响**：缓存失效时热门 skill 多请求回源；留存开启、cache 关闭、无附件/deferred binding 等组合下网络回调可能继续做 JDBC，阻塞事件循环。后者为代码路径推断，实际线程需要测试确认。
- **已有保护/剩余缺口**：单次调用配置快照复用、Provider timeout、缓存 IO 专用调度已有；默认 cache 开并成功写缓存路径通常回 IO，不能说所有默认请求必在 Netty 阻塞。
- **加固措施**：[W05](#w05)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T16/D04 cache on/off、留存 on/off、附件/deferred binding 组合中记录实际线程；并发 miss 不放大、取消/失败后可重查，事件循环无 JDBC。

<a id="risk-register--r17-删除会话仍可能通过历史恢复访问"></a>
<a id="r17"></a>
#### R17 删除会话仍可能通过历史恢复访问

- **证据 S**：[ensureOwnedSession](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatStreamApplicationService.java#L341)只判归属存在，[WS topic 校验](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatStreamApplicationService.java#L324)只判 Run 归属；[owner 查询](../../../src/main/resources/mapper/session/ChatSessionMapper.opengauss.xml#L90)无 status 过滤。
- **触发与影响**：本人删除后使用保存的 sessionId/runId 恢复，入口行为与正常会话接口删除语义不一致。未证明跨用户读取，不将其扩大为租户隔离已被攻破。
- **已有保护/剩余缺口**：owner/tenant 检查有效；状态语义没有统一，已有 WS 订阅在删除后的行为也须核对。
- **加固措施**：[W07](#w07)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T17/D08 删除前后 HTTP、SSE、WS及已有连接一致处理，跨用户/租户权限回归、恢复不得返回已删除会话业务数据。

<a id="risk-register--r18-分支创建非原子及分享创建越过删除撤销"></a>
<a id="r18"></a>
#### R18 分支创建非原子及分享创建越过删除撤销

- **证据 S**：[createBranch](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionApplicationService.java#L848)无方法事务、逐条复制；[单轮分享](../../../src/main/java/com/huawei/it/ex/one/application/service/share/ChatShareApplicationService.java#L61)和[多选分享](../../../src/main/java/com/huawei/it/ex/one/application/service/share/SelectedChatShareApplicationService.java#L80)在事务中读取 Session 但未与 delete 同行锁后检查。
- **触发与影响**：拷贝中间写失败留下部分分支；分享先读 ACTIVE，delete 撤销并 commit 后分享才 INSERT，可留下删除后新 ACTIVE 分享。竞争尚未实库复现。
- **已有保护/剩余缺口**：owner、快照大小和创建时未删除检查有效；分享自己的事务不自动与 Session 删除串行，分支只读 snapshot 属性不提供创建原子性。
- **加固措施**：[W07](#w07)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T18/D08 每个复制点注错、分享/删除双实例并发，零部分可见分支、删除后无可见新分享、正常快照内容一致。

<a id="risk-register--r19-welink-投递未知结果被再次发送"></a>
<a id="r19"></a>
#### R19 WeLink 投递未知结果被再次发送

- **证据 S/E**：[deliver](../../../src/main/java/com/huawei/it/ex/one/infrastructure/share/WelinkChatShareDeliveryProvider.java#L59)按 maxAttempts 重试所有失败结果，[wire body](../../../src/main/java/com/huawei/it/ex/one/infrastructure/share/WelinkChatShareDeliveryProvider.java#L113)无稳定投递标识；[应用](../../../src/main/java/com/huawei/it/ex/one/application/service/share/ChatShareDeliveryApplicationService.java#L87)先发后生成/落库 deliveryId。
- **触发与影响**：对端已发但响应丢失、超时或本地记录失败，自动尝试/用户重提产生重复通知；启用 WeLink 才适用，实际下游去重能力 E。
- **已有保护/剩余缺口**：provider 开关、调用期限及 bulkhead 有效；逻辑 FAILED 不等于对端没执行，创建后的本地记录无法追踪发送前崩溃窗口。
- **加固措施**：[W07](#w07)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T19/D08 已送达丢响应、写 DB 失败、跨实例重试，人工和系统可识别未知结果；真 WeLink/协议桩契约证明稳定 ID 生效。

<a id="risk-register--r20-联调样例忽略恢复建议游标和业务错误"></a>
<a id="r20"></a>
#### R20 联调样例忽略恢复建议游标和业务错误

- **证据 S**：[handleWsEnvelope](../../../local-test-frontend/public/app.js#L641)RECOVER_REQUIRED 用 lastSeq，不用 details 建议游标；[requestJson](../../../local-test-frontend/public/app.js#L1628)仅检查 HTTP status。
- **触发与影响**：本地已到 seq105 而建议从99补读，重放仍从105开始可能跳过迟到100；HTTP200 ACCESS_DENIED 当作正常 DTO。仅确认仓库联调页面，未审计生产前端。
- **已有保护/剩余缺口**：现有 topic/sequence 跟踪及错误日志有用；不能替代服务端恢复锚点或业务错误判断。
- **加固措施**：[W03](#w03)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T20/D05 固定100迟到、105已见、建议99及 HTTP200权限错误，验证补齐/去重、重订阅、无错误状态更新。

<a id="risk-register--r21-可编辑-metadata-能改变可信附件引用"></a>
<a id="r21"></a>
#### R21 可编辑 metadata 能改变可信附件引用

- **证据 S/E**：[update](../../../src/main/java/com/huawei/it/ex/one/application/service/document/DocumentApplicationService.java#L110)直接整体替换 metadataJson；[providerDocumentReference](../../../src/main/java/com/huawei/it/ex/one/application/service/document/DocumentApplicationService.java#L291)随后信任其中 docId/url 并转为引用。
- **触发与影响**：合法 owner PATCH 文档 metadata 的 providerDocument，然后发起附件调用；引用可能被损坏或变成任意目标。是否形成下游越权或 SSRF 依赖下游访问/鉴权方式，未复现或宣称利用成功。
- **已有保护/剩余缺口**：owner、文档状态及可信文档库读取已有；可写入文档库的字段不自动成为可信服务器事实。
- **加固措施**：[W10](#w10)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T21/D06 伪造 docId/url/嵌套字段均不改变最终 docList，合法字段可编辑；与下游核对访问策略，确认既有污染修复范围。

<a id="risk-register--r22-huawei-s3-配置未开启证书和-hostname-验证"></a>
<a id="r22"></a>
#### R22 huawei-s3 配置未开启证书和 hostname 验证

- **证据 S/E**：[OBS 配置](../../../src/main/java/com/huawei/it/ex/one/infrastructure/storage/object/s3/HuaweiS3StorageConfiguration.java#L25)只设置 endpoint/连接参数；[版本](../../../pom.xml#L23)固定 3.25.10。本轮本地 `javap -c -p com.obs.services.ObsConfiguration` 确认构造器设置 `validateCertificate=false`、`isStrictHostnameVerification=false`，应用无覆盖。
- **触发与影响**：启用 huawei-s3 并走 HTTPS，网络路径被冒充/证书配置错误；可能影响文档机密性、完整性、可用性。当前未进行 TLS 对抗实验，实际 endpoint/CA 为 E。
- **已有保护/剩余缺口**：显式凭证和超时配置存在；HTTPS scheme 或内网不等于服务端身份验证。
- **加固措施**：[W10](#w10)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T22/D06 在隔离证书环境，错误 hostname/未知 CA/过期证书失败，可信有效证书成功，轮换演练可恢复；留存已脱敏配置及握手结果。

<a id="risk-register--r23-删除事务缺少显式本地期限"></a>
<a id="r23"></a>
#### R23 删除事务缺少显式本地期限

- **证据 S/E**：[deleteSession/deleteSessions](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionApplicationService.java#L400)为普通 `@Transactional`，对比归档/恢复的有界事务；L437 按 ID 排序锁会话并批量执行写操作。目标数据库全局 statement/lock/socket 期限未取得。
- **触发与影响**：批删最多100个会话遇慢 SQL 或外部持锁，已取得的锁和连接长时间占用，其他准入/终态/管理操作等待。
- **已有保护/剩余缺口**：稳定锁序、all-or-nothing、Redis 已移至提交后；本风险不是 Redis 未整改或已证明死锁。借用500ms不限制已借到连接上的 SQL。
- **加固措施**：[W04](#w04)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T23/D02 反向 ID 批次、慢 SQL、锁等待与准入/删除竞态，超时整体回滚、无部分删除、连接锁及时释放。

<a id="risk-register--r24-active-path-选择与准入完成并发覆盖"></a>
<a id="r24"></a>
#### R24 active path 选择与准入/完成并发覆盖

- **证据 S**：[selectPath](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionApplicationService.java#L836)读取归属和节点后直接更新 current leaf；没有 active Run 检查、Session 锁后重验或 expected leaf 条件。
- **触发与影响**：运行中切换路径或与 NEXT/终态/删除并发，选择值与终态落叶互相覆盖，用户路径跳回或后续候选 STALE_SOURCE。仅指 leaf 字段，不声称覆盖专家 scope/node order。
- **已有保护/剩余缺口**：owner、节点归属、正常会话状态检查已有；不提供多请求间的路径原子语义。
- **加固措施**：[W07](#w07)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T24/D08 path 与 NEXT/终态/delete 双实例竞争，成功变更可解释，失败不改变 leaf，既有版本导航兼容。

<a id="risk-register--r25-标题生成许可不覆盖候选和提交完整生命周期"></a>
<a id="r25"></a>
#### R25 标题生成许可不覆盖候选和提交完整生命周期

- **证据 S**：[schedule/collectCandidate](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionTitleApplicationService.java#L84)在生成许可前查询完整轻量路径及关联 Run（L164–L211）；[生成](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionTitleApplicationService.java#L110)许可仅覆盖 generate；[提交](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/SessionTitleCommitService.java#L26)另开2s事务；[调度器](../../../src/main/java/com/huawei/it/ex/one/infrastructure/sessiontitle/SessionTitleProviderConfiguration.java#L31)4线程/128排队参数。
- **触发与影响**：标题功能开启、长路径、多会话突发、token 阻塞或提交锁等待，旁路争共享连接/线程；生成后实例退出会丢提炼结果。未开启部署不受此路径影响。
- **已有保护/剩余缺口**：默认关闭；生成8许可/timeout、提交锁后 ACTIVE/人工标题/nodeOrder 重验有效，默认排除 Provider 只读本地配置；“前三问”不限制读取的路径和 Run ID 数量，也不保护排队时间。
- **加固措施**：[W08](#w08)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T25/D08 候选 SQL/token/提交锁分别阻塞及生成后 kill，主 Run 首事件达标、人工标题/新版本不被覆盖、丢任务指标可见。

<a id="risk-register--r26-高频-json序列化与长对象造成条件性-cpu分配压力"></a>
<a id="r26"></a>
#### R26 高频 JSON、序列化与长对象造成条件性 CPU/分配压力

- **证据 S，故障 E**：[DTO](../../../src/main/java/com/huawei/it/ex/one/interfaces/chat/dto/CreateChatRunRequest.java#L48)限制正文20000、metadata顶层50字段，但没有业务层嵌套总字节预算；[批次估算](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatEventBatcher.java#L158)完整序列化，发布端 [writeValueAsString](../../../src/main/java/com/huawei/it/ex/one/infrastructure/persistence/RedisChatLiveEventBus.java#L152)再次序列化；[正文/卡片物化](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/AssistantAssembly.java#L235)复制字符串和 payload；[协议解析](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/domainagent/DomainAgentResponseNormalizer.java#L71)也消耗解析/复制 CPU。
- **触发与影响**：大嵌套 metadata、合法上限帧的高频流、多 Parts/长草稿、扇出及重试叠加时，序列化、分配和 GC 可能形成 CPU 瓶颈，导致事件循环/治理延迟。尚无 profile，不声称 CPU 已飙升、算法必为二次复杂度或 JSON 无任何底层限制。R02 的高租户基数驻留对象作为长期分配观测维度。
- **已有保护/剩余缺口**：字段/帧/回调体积上限、批次和许可已有，Jackson 自带约束需按生效配置核对；单项上限不构成吞吐或累计字节预算，Reactive timeout 也不抢占同步 JSON 运算。
- **加固措施**：[W01](#w01)、[W08](#w08)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T26/D01 合法最大值和超限值、细碎帧、高频控制帧/长正文、旁路叠加，给出热点前后对比及资源上界，拒绝发生在昂贵操作前，正常结果不改变。

<a id="risk-register--r27-已确认拓扑的部署参数探针和运营行为未获环境验证"></a>
<a id="r27"></a>
#### R27 已确认拓扑的部署参数、探针和运营行为未获环境验证

- **证据 U/S/P/E**：U已确认四服务共享依赖、ADS各Region独立控制/运行面、各Region ALB及WCM职责；跨AZ多副本、跨Region热备单写和独立WCM备用源为选定目标P，见[部署依据](deployment.md)。基础拓扑不再列作“完全未知”。S：[本地配置](../../../src/main/resources/application.yml#L1)提供Tomcat/虚拟线程/连接等默认值；[健康配置](../../../src/main/resources/application.yml#L39)DB默认关闭、Redis关闭。E：尚未取得ADS实际部署/探针导出、ALB/Jalor重试/idle/ACL、有效资源参数及切换演练证据，不能据仓库缺少deployment文件推断平台没有这些能力。
- **触发与影响**：探针仍绿但关键依赖/后台任务失效，滚动发布未排空导致 WS/Run 大量中断，容器资源低于默认预算、实例/依赖同故障域、日志/临时磁盘满、证书/配置变更后不可恢复；可扩大为全服务故障。
- **已有保护/剩余缺口**：源码已有关闭回调、租约/Watchdog、Actuator依赖，U确认Region控制/运行面的独立性；多AZ、热备等P待实施/验收，不能据此证明真实readiness/liveness、容量余量、DB复制/备份恢复和SLO达标。共享依赖/入口/平台/AZ/Region专项问题分别由R28–R35跟踪，避免用一条“部署未知”掩盖具体边界。
- **加固措施**：[W09](#w09)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T27/D07 单实例终止、滚动发布/回滚、依赖切换、配置/证书轮换和容量/磁盘告警演练；同时证明服务恢复与遗留任务收口，提供批准的 SLI/SLO/RTO/RPO 和真实部署值。

<a id="risk-register--r28-四服务共享-db-的争用会跨服务传播"></a>
<a id="r28"></a>
#### R28 四服务共享 DB 的争用会跨服务传播

- **场景/图步骤、责任/预案**：S01–S12；[DEP01-N14](deployment.md#dep01)、[DEP02-N07](deployment.md#dep02)、[DEP03-N05/N11](deployment.md#dep03)；DBA与四服务负责人共同主责，RB11/D09，T28。
- **证据 U/S/P/E**：U确认Admin、Tool、Relay、Chat共享DB；P采用DB区域内HA、跨Region异步复制且不启用全面读写分离。S仅确认Chat [Hikari配置](../../../src/main/resources/application.yml#L24)及R02/R07/R13/R23中的连接、事务和治理行为；其余三服务的连接池、SQL、事务和迁移操作为E，不能将Chat默认10连接推广到四服务。
- **触发与影响**：任一服务扩容/慢SQL/批任务/DDL占用连接、CPU、IO或锁，其他服务即使实例正常也可能超时；AZ故障后剩余副本重连及Region接管时四服务同时建连接可放大冲击。影响聊天、路由、工具执行、配置管理及恢复治理。
- **已有保护/剩余缺口**：Chat局部限流/短事务不能限制其他服务或证明共享数据库有容量余量；P中的区域内DB HA须由实际拓扑/切换验证，不能提前计作已关闭风险。相同数据库内分账户/命名空间不能隔离底层IO和故障域。
- **加固措施**：[W11](#w11)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T28/D09逐一给Admin/Tool/Relay/Chat注入连接耗尽、慢SQL/锁等待，在其余三服务保持真实业务负载，证明止血可限定受影响服务、控制链路可用、DB总预算不被扩容绕过；提供四服务有效配置及共享库指标。

<a id="risk-register--r29-四服务共享-redis-的资源竞争与重建风暴"></a>
<a id="r29"></a>
#### R29 四服务共享 Redis 的资源竞争与重建风暴

- **场景/图步骤、责任/预案**：S02/S03/S07/S12；[DEP01-N15](deployment.md#dep01)、[DEP02-N08](deployment.md#dep02)、[DEP03-N06/N12](deployment.md#dep03)；Redis平台与四服务负责人共同主责，RB11/D09，T29。
- **证据 U/S/E**：U确认四服务共享Redis。S：[Chat配置](../../../src/main/resources/application.yml#L51)将Redis用于缓存、取消标记、恢复锁优化和实时fanout，DB为事实源；R09/R16仍有接收线程和并发miss风险。Admin/Tool/Relay的键、锁、队列及持久性要求未审计，属于E；不能一概断言四服务Redis都可丢弃重建。
- **触发与影响**：某服务大key/热key、Lua/批量操作、内存淘汰、Pub/Sub突发或Cluster切换拖慢所有服务；恢复时共同回源DB形成二次故障。跨Region盲复制锁、取消标记或持久任务队列可能造成错误执行权、漏处理或重复执行。
- **已有保护/剩余缺口**：Chat有Redis短期限、部分缓存回源及DB fencing；Pub/Sub不提供持久交付。逻辑key前缀/ACL不能保证共享实例的容量和延迟隔离，实际maxmemory/淘汰/持久化与各服务语义仍待核验。
- **加固措施**：[W11](#w11)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T29/D09逐服务热key/内存/订阅洪峰和Redis切换，确认其他服务响应、DB回源限额、恢复后无旧锁控制新执行；各类数据恢复均有owner和证据，no-store正文缺口明确。

<a id="risk-register--r30-admin映射变更及toolda执行未知结果缺少联合保证"></a>
<a id="r30"></a>
#### R30 Admin映射变更及Tool/DA执行未知结果缺少联合保证

- **场景/图步骤、责任/预案**：S02/S04/S05/S06；[DEP01-N09/N11/N12](deployment.md#dep01)；Admin/Tool/DA负责人主责、Chat协作，RB11/D09，T30。
- **证据 U/S/E**：U确认Chat→Tool→DA，Chat直接调用Relay，Intent为第三方，Admin参与mapping管理。S：[Chat出站适配器](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/domainagent/ConfiguredDomainAgentClient.java#L65)按配置base-url/chat-path发送HTTP流；[base-url与Stop配置](../../../src/main/resources/application.yml#L429)可由环境覆盖。源代码中的“DomainAgent直连”描述逻辑适配器，不证明部署绕过Tool；Tool转发、Admin映射生效和DA幂等/结果查询为E。
- **触发与影响**：Admin部分发布/缓存不同步造成请求路由至错误或旧DA；Tool超时/连接中断时DA可能已执行，Chat或Tool自动重试/Region接管重放可能重复任务；本地Stop也不代表Tool后DA已经终止。影响路由正确性、外部副作用和遗留任务收口。
- **已有保护/剩余缺口**：Chat有可信身份、运行ID、流期限、owner/fencing及状态CAS；这些保护本地写入，不能跨Tool/DA提供端到端exactly-once或映射原子发布。未取得其余服务源码，不将未验证能力描述为确定不存在。
- **加固措施**：[W11](#w11)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T30/D09同时覆盖映射更新/回滚、Tool已转发后断流、DA已执行丢响应、重复请求/迟到Stop及跨Region恢复；证明目标版本可追溯、未重复执行或明确进入人工对账，使用真下游契约证据。

<a id="risk-register--r31-wcm备用静态源可能缺失陈旧或共享故障域"></a>
<a id="r31"></a>
#### R31 WCM备用静态源可能缺失、陈旧或共享故障域

- **场景/图步骤、责任/预案**：S01/S07/S12；[DEP01-N03–N05](deployment.md#dep01)、[DEP04-01–04/10–12](deployment.md#dep04)；WCM/对象存储与前端主责，RB12/D10，T31。
- **证据 U/P/E**：U确认WCM托管Web静态资源；P选定独立于ADS的备用HTTPS静态源、预发布对象存储产物和DEP04切换/版本验证流程。该源用于网页/静态资源，不接管API和后台执行；对象存储API本身不等于具备HTTPS静态托管能力。E包括主备产物一致性、ALB源站接入、对象存储访问/跨Region能力、域名/证书及独立发布权限，仓库未验证WCM/对象存储配置。
- **触发与影响**：WCM异常时备用首页缺少bundle/运行配置、缓存混版、签名URL过期、备用域名证书失效，或备用实际依赖故障ADS上的动态拼装。网页空白、资源404、错误API地址或登录跳转使用户无法进入系统；静态页面可打开也不证明聊天可用。
- **已有保护/剩余缺口**：P选择独立托管和预发布以消除“故障时才依赖ADS发布备用”的前提；在完成源站接入、独立性及版本完整性验收前，不能将其列为已经可用的故障保护。
- **加固措施**：[W12](#w12)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T31/D10在WCM与ADS相关路径不可用时，仅用已预发布备用源完成首页/静态资源加载、登录跳转及目标API连通性核对；故障前已有版本清单，回切无混版/缓存污染，API本身失败单独报告。

<a id="risk-register--r32-alb文根路由流式连接和区域入口切换不一致"></a>
<a id="r32"></a>
#### R32 ALB文根路由、流式连接和区域入口切换不一致

- **场景/图步骤、责任/预案**：S01/S03/S07/S09；[DEP01-N02/N08](deployment.md#dep01)、[DEP04-05–09/11–12](deployment.md#dep04)、[DEP05-11–12](deployment.md#dep05)；网络/ALB主责、前端/应用协作，RB12/D10，T32。
- **证据 U/S/P/E**：U确认每Region ALB独立且负责文根，P采用GSLB/DNS受控主备入口；S确认Chat提供HTTP、SSE和WS，且[异步HTTP配置](../../../src/main/resources/application.yml#L11)不能替代ALB/Jalor期限。E为文根/路径前缀、静态源/API目标组、Upgrade、缓冲、idle timeout、摘流和DNS有效缓存行为。
- **触发与影响**：文根重写错误将API落到静态HTML或破坏资源路径；ALB缓冲/idle小于静默期截断SSE/WS；目标退出后老连接仍向旧Region写；DNS缓存和长连接使“入口已切换”与客户端实际落点不同，POST自动重试可能重复执行。
- **已有保护/剩余缺口**：U中的Region独立ALB及应用超时/Resume提供基础，GSLB/DNS受控切换为P；不等于流式转发正确或长连接可迁移。静态源、业务API和管理接口必须按各自路由验证，不能靠首页200判断服务健康。
- **加固措施**：[W12](#w12)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T32/D10测试嵌套文根、静态资源与API错误路由、静默流/WS、目标摘流、故意缓存旧DNS/保持旧连接及恢复风暴；实际Region落点、写拒绝、首事件与补读结果可核对。

<a id="risk-register--r33-ads控制面故障与运行面故障需要分别应对"></a>
<a id="r33"></a>
#### R33 ADS控制面故障与运行面故障需要分别应对

- **场景/图步骤、责任/预案**：S01–S12；[DEP02-N02–N06/N09](deployment.md#dep02)、[DEP03-N04/N10/N15](deployment.md#dep03)；ADS平台与四服务负责人主责，RB13/D11，T33。
- **证据 U/P/E**：U确认四服务都在ADS，两个Region的ADS控制面和运行面相互独立。P为按故障类型选择冻结发布、保留健康实例或区域接管。E为ADS控制失效时已有实例是否继续运行、调度/扩缩容/配置/密钥下发/镜像拉取的实际依赖及跨Region共用外围组件。
- **触发与影响**：控制面失效可能使发布、调度或证书/配置更新不可用但已有服务仍健康；运行面失效直接中断请求/流。将控制面故障误判为全站失效、或仍依赖失效控制面“紧急扩容/发布备用”，会扩大影响；两Region虽独立，公用制品/身份/网络依赖仍可能成为共同故障点。
- **已有保护/剩余缺口**：U中的Region间控制/运行面独立有利于接管；不能推断每Region内所有控制组件跨AZ或故障时具备管理能力。P中的跨Region预部署热备旨在避免故障时现部署，其版本、凭证和可运行状态仍需E。
- **加固措施**：[W13](#w13)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T33/D11分别隔离控制面和运行面，证明控制故障时服务能力及限制、备用Region可由独立入口运维、预部署版本/配置可用；禁止以单一控制探针自动切Region。

<a id="risk-register--r34-az失效后剩余容量和依赖拓扑可能不足"></a>
<a id="r34"></a>
#### R34 AZ失效后剩余容量和依赖拓扑可能不足

- **场景/图步骤、责任/预案**：S01–S12；[DEP02-N01–N09](deployment.md#dep02)；ADS/网络/DBA与四服务容量负责人共同主责，RB13/D11，T34。
- **证据 U/P/E**：U确认ADS承载服务及各Region平台独立；P要求每Region至少两个AZ、多副本，且AZ失效后的安全容量/依赖路径通过验收。E为副本/节点实际分布、反亲和、ALB目标/网络、DB/Redis区域内HA位置/仲裁成员和切换行为，以及各服务依赖配额。两个应用AZ是下限，不限定数据仲裁成员只能位于两个AZ，也不自动等于任何AZ退出仍承载原峰值。
- **触发与影响**：单AZ整体丢失后，剩余AZ处理四服务流量、连接重建和历史恢复，CPU/堆/DB/Redis/下游配额超载；副本看似分散但依赖主节点或出站网络集中也会阻断整Region。恢复时集中重连可二次雪崩。
- **已有保护/剩余缺口**：当前Chat本机限流不提供四服务全集群公平；P中的多副本和区域内DB HA尚需部署/仲裁验证，更不证明失效后的余量。此风险不预设必须扩容到某个固定倍数。
- **加固措施**：[W13](#w13)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T34/D11完整AZ隔离并保持峰值或经批准故障期负载，统计四服务成功率、队列/连接/CPU、控制与恢复时延；故障AZ恢复后的放量也通过，不能只停止一个Pod代替AZ演练。

<a id="risk-register--r35-region失效网络分区复制滞后与回切导致双写丢失"></a>
<a id="r35"></a>
#### R35 Region失效、网络分区、复制滞后与回切导致双写/丢失

- **场景/图步骤、责任/预案**：S01–S12；[DEP03-N15](deployment.md#dep03)、[DEP05-01–14](deployment.md#dep05)、[DEP06-01–13](deployment.md#dep06)；容灾指挥/DBA/平台与四服务负责人共同主责，RB14/D12，T35。
- **证据 U/S/P/E**：U确认各Region ADS与ALB独立；P采用双Region预部署热备单写、DB区域内HA/跨Region异步复制、GSLB/DNS受控主备、Redis分类恢复及DEP05接管/DEP06回切。S中的[execution fencing](../../../src/main/resources/mapper/persistence/ChatRunExecutionMapper.opengauss.xml#L227)保护同一DB事实上的owner竞争；不能在分裂成两个可写DB时成为跨Region排他证明。真实复制水位、外部执行对账、阻断旧写能力及RTO/RPO为E。
- **触发与影响**：Region失联但旧Region仍可写，备Region未经隔离就提升；复制滞后使已受理/已完成的Run或工具操作在新主不可见；DB与业务附件对象恢复点不一致使引用存在但文件缺失或权限不匹配；缓存/锁/任务队列盲恢复造成旧任务重放；回切以隔离前的水位作为最终门槛、未追平或先放开旧主导致双写、丢结果、重复副作用。影响四服务及外部DA/Relay/投递任务。
- **已有保护/剩余缺口**：P中的单写和预部署旨在降低接管复杂度，仍须建设和验证；异步复制可能丢最近已提交数据，不能承诺RPO=0。GSLB/DNS切流不撤销旧写连接，DB应用fencing不自动构成跨Region防双主。FULL只恢复实际到达新主的已存事件，no-store正文不因容灾而可回放；[默认Runtime恢复端口](../../../src/main/java/com/huawei/it/ex/one/infrastructure/runtime/UnsupportedAgentRuntimeRecoveryPort.java#L21)不支持接管，历史恢复不能被描述为远端流无缝续跑。
- **加固措施**：[W14](#w14)；具体实施、兼容性和回滚要求见本页工作包。
- **关闭证据（待取得）**：T35/D12分别覆盖Region真实故障、网络分区、可控复制滞后、旧DNS/旧连接持续请求、DB提升失败与回切中断；注入回切预收集水位后至隔离前的迟到提交，证明隔离后的最终位点已完整追平。验证业务附件对象落后于DB、引用/权限不一致及已批准损失的阻断/功能隔离；整个时间轴最多一个可写权威，RPO/UNKNOWN任务可量化，旧owner/迟到回调不覆盖新事实，回切后四服务、静态入口、业务附件、工具执行和遗留任务均完成核对。

<a id="risk-register--静态依赖复核的可重复命令"></a>
### 静态依赖复核的可重复命令

以下命令只读本地依赖。版本与当前 pom/依赖解析一致时有效；它们确认默认实现，不代替 T09/T22 的运行验收。若本地缓存缺失，由实施者在允许的依赖环境获取后执行，不修改生产连接配置。

```sh
javap -c -p -classpath "$HOME/.m2/repository/org/springframework/data/spring-data-redis/3.4.6/spring-data-redis-3.4.6.jar" org.springframework.data.redis.listener.RedisMessageListenerContainer
javap -c -p -classpath "$HOME/.m2/repository/org/springframework/spring-core/6.2.7/spring-core-6.2.7.jar" org.springframework.core.task.SimpleAsyncTaskExecutor
javap -c -p -classpath "$HOME/.m2/repository/org/springframework/spring-core/6.2.7/spring-core-6.2.7.jar" org.springframework.util.ConcurrencyThrottleSupport
javap -c -p -classpath "$HOME/.m2/repository/com/huaweicloud/esdk-obs-java-bundle/3.25.10/esdk-obs-java-bundle-3.25.10.jar" com.obs.services.ObsConfiguration
```

<a id="hardening"></a>

<a id="hardening--高可用加固任务清单与发布门槛"></a>
## 高可用加固任务清单与发布门槛

代码基线：`00abae4f80b7e7e5b4d0ddca707035f1878a8ec8`。本轮只交付落地蓝图；以下 W01–W14 均为**待实施**，不是已经具备的能力或可调用开关。W11–W14按用户已确认拓扑追加，保留原编号。风险事实见[当前登记](#risk-register)，部署基础见[部署与容灾图](deployment.md)，验证要求见[测试用例](tests.md)和[运行册与演练](operations.md)。

**部署边界与证据口径**统一见[部署设计](deployment.md)；以下只记录待实施措施，不把目标当作已部署能力。

<a id="hardening--实施顺序与公共验收"></a>
### 实施顺序与公共验收

| 顺序 | 工作包 | 前置条件 | 阶段出口 |
|---|---|---|---|
| 0 | W09 基线、有效配置、SLI 与可观测性 | 应用/平台/DBA/下游负责人到位，隔离环境 | 实例资源、流量、依赖版本/配额、业务目标有记录；可以判定注入是否越界 |
| 1，可并行 | W10 信任边界；W02 Redis 接收；W01 流式资源；W03 恢复流量 | 完成各自参数预算及兼容性清单 | 条件性 P1 风险完成组件测试与隔离演练；未满足则不扩大相关功能/容量 |
| 1，外部阻断同步推进 | W07 中 Relay Stop 契约 | Relay 协议负责人和真实联调环境 | 真下游证明迟到 A Stop 不伤害 B；本地测试通过不替代此门槛 |
| 2 | W04 DB与治理；W05 鉴权/期限；W06 文档；W08 查询/旁路 | 库端期限、资源预算和已定义的 busy/失败语义 | 混合流量下治理与主 Run 仍有资源，取消后底层工作释放 |
| 3 | W07 其余正确性/幂等任务 | 前端和下游契约评审、必要迁移方案 | 窗口故障和双实例竞态测试通过；持久事实可对账 |
| 1–3，并行平台工作 | W11共享依赖与Tool契约；W12静态源/入口；W13 ADS/AZ | 四服务及平台负责人，U基础拓扑的实际快照与P目标的建设任务 | D09–D11证明共享资源故障边界、独立静态入口、ADS控制/运行隔离及AZ失效余量 |
| 3，容灾专门门槛 | W14 Region接管/回切 | W11–W13基础验收、单写隔离能力、复制水位与数据损失政策 | D12含分区/复制滞后/旧连接/回切中断，证明单写和可核对恢复 |
| 4 | W09 综合演练与发布交接 | W01–W14 关联必需任务完成或有效风险接受 | 真实部署等价环境的服务/遗留任务恢复、回滚、告警和运行册验收 |

每个工作包拆成可独立评审的小提交，固定保存：关联 R/T/RB/D、责任人、配置作用域、迁移与兼容结果、灰度观测、回滚条件、修复后证据。实施后更新登记状态，不能因代码合并自动关闭风险。

容量参数先经 W09 测量冻结。至少记载堆/容器内存、native memory、CPU、FD、临时磁盘、DB 全集群连接配额、下游并发/速率及单项业务期限。各类“并发×高分位单任务常驻字节＋队列字节”的总和须落在为框架、缓存、GC 和 native memory 留出余量后的预算内；这是预算方法，不是可以从默认配置推出的最大用户数。

验收必须同时满足：业务状态/事件正确、资源有界、依赖恢复后积压可收敛、控制链路可用。新增数值上限须在配置说明中明确单位、默认值、作用域、拒绝行为、是否需重启；本蓝图不虚构已支持的配置名。

<a id="hardening--w01-流式队列累计正文与-cpu-保护"></a>
<a id="w01"></a>
### W01 流式队列、累计正文与 CPU 保护

**关联**：R01、R26；应用主责，性能测试协作；T01/T26，RB01/RB02，D01。

- **实施**：梳理 Relay 三个 BUFFER 桥接和 DomainAgent 总期限包装，优先用保持订阅 demand 的组合替代内部无界 subscribe。不能消除桥接时使用事件数＋字节双边界，按每 Run 及实例总量收费；同样约束草稿、Parts、卡片正文及队列中的大 payload。超限取消上游并提交一次有解释的失败终态，严禁 silent drop 控制/正文。
- **CPU**：先 JFR/profile 确认协议解析、字节估算、多次序列化、草稿物化和 GC 热点；在保序/ACK/留存语义相同前提下消除确认的重复工作，必要时隔离同步 CPU 工作。输入深度/字节与事件速率限制须在昂贵复制/序列化前执行，保留 Jackson 自有保护；不得删除鉴权、脱敏或owner校验来换取性能。
- **前置/接口**：冻结每帧、每 Run、实例预算及现有终态错误映射。保持 FULL/no-store、questionnaire、拒答、候选切换、异步挂起、snapshot 的现有事件顺序；新增错误 code 要同步 OpenAPI/前端，不能静默截断已承诺正文。
- **观测/测试**：队列事件/字节、草稿/Parts字节、拒绝、线程/堆/GC、event-loop lag、取消后残留订阅；测试慢 DB＋高速流、接近上限/超限、timeout/stop/kill，验证 timer/socket/permit 清理。
- **发布/回滚**：先隔离环境后单实例小流量，观察完整 Run 与恢复周期。回退旧版本会重新暴露无界队列，因此须同时收紧已验证的入口配额或摘流；不能把“关闭保护”当正常兜底。

<a id="hardening--w02-redis-接收执行器与拒绝恢复"></a>
<a id="w02"></a>
### W02 Redis 接收执行器与拒绝恢复

**关联**：R09，协同 R04；应用主责，SRE/前端协作；T09/T04，RB04，D03。

- **实施**：为 `RedisMessageListenerContainer` 显式提供有界消息执行器；连接订阅/重连任务与消息 handler 分配独立资源。拒绝路径打出 topic、原因和恢复锚点，必要时终止订阅要求恢复；禁止网络线程 CallerRuns 和无记录丢弃。
- **前置/接口**：核对 Spring Data Redis 的调度和 listener 调用模型，保持同 topic 消费顺序/现有重排策略。Pub/Sub 不是可靠存储；FULL 用现有 DB 补读，no-store 明确缺口，不能靠自动重放存不存在的内容。
- **观测/测试**：消息执行线程/队列、拒绝率、回调耗时、重连/恢复速率、topic积压；多个 topic 高 fanout、handler阻塞、Redis断网/Cluster切换、重排和销毁重建。
- **发布/回滚**：进程级灰度而非混用同一容器内两套无协调执行器。回退须限制扇出并保留恢复告警，确认连接容器关闭无残留线程。

<a id="hardening--w03-恢复分页预算与客户端协同"></a>
<a id="w03"></a>
### W03 恢复分页、预算与客户端协同

**关联**：R03、R20，协同 R04/R17；应用、前端共同主责；T03/T20/T04/T17，RB06，D05。

- **实施**：HTTP Session Resume、Run Resume、WS历史补读都使用游标分页，先建立 live 有界缓冲再读历史；复用仓储已有有界事件窗口能力时补齐会话级路径。按 sequence 单调推进，维持业务中止/异步边界语义；seq 是全局游标，不要求一个 topic 连续编号。
- **配额**：恢复设置实例和用户并发、回放总字节/时间边界，配额失败立即返回明确忙/恢复信号，取消/断线归还；与新 Run、Stop/heartbeat 预算分开。客户端按服务端恢复建议游标、指数退避/抖动及事件身份去重，避免多页签同步重连。
- **前置/接口**：优先保持现有 `afterSeq` 和流式响应；需要新增 continuation/busy code 时作为独立协议增量，旧前端不得把截断当回放完成。联调样例解析 HTTP200 业务错误；生产前端需单独契约确认，不能推定已有相同缺陷。
- **观测/测试**：恢复在途/排队/拒绝、页大小/总字节、补读与 live 重叠、缺口/重连次数；长历史、高并发取消、跨实例、Redis恢复风暴及丢响应。
- **发布/回滚**：新旧服务均读同一事实源，先部署兼容客户端再启新错误/游标行为；回滚服务器时保留网关已验证恢复并发限制，不回到无限恢复流量。

<a id="hardening--w04-db事务连接公平治理与索引"></a>
<a id="w04"></a>
### W04 DB事务、连接公平、治理与索引

**关联**：R02、R07、R13、R15、R23；应用/DBA共同主责；T02/T07/T13/T15/T23，RB03/RB04/RB08，D02/D03/D07。

- **事务**：Interaction兼容入口和固定专家 Binding 内部只操作 DB，提交后同步/调度缓存；删除两个代理入口加显式有界事务，保留稳定 Session锁序、owner/fencing 和批量原子性。核对 afterCommit 回调是否仍延迟连接归还。
- **预算**：以真实 DB 总容量分配实例/租户/功能准入并保留控制工作额度；借用、statement、lock、socket、事务是不同期限，逐项验证传播。无需默认拆多个物理池；只有预算及压测证明有效才引入，不能扩大总连接越过 DBA 配额。
- **治理**：scan/claim 有短期限；Interaction、初始化孤儿、async、stale recovery 共用本轮工作次数/时间预算并计失败尝试，公平处理租户，积压分批推进。清理 tenant semaphore 时防正在借用对象被替换，不移除现有用户窗口清理。
- **迁移**：DBA核验当前索引定义/有效性及数据库存储类型，事务外执行需要的并发索引脚本，记录中断残留/重跑方法；启动关键唯一约束检查保持，不把性能索引变成启动DDL。
- **前置/接口**：拿到 openGauss版本、驱动和全局期限；使用既有忙/超时语义，SQL超时必须回滚并释放资源。不得把搜索失败伪装为空结果或把事务超时直接断言未提交。
- **观测/测试**：借用等待/超时、active/pending、锁等待/慢SQL、治理上次完成时间/尝试/积压、事务内Redis检测；混合主Run/恢复/回调/删除/标题，验证控制链路仍达标。
- **发布/回滚**：期限/配额逐步收紧，灰度按业务成功率与治理时延判定；代码回滚保留兼容索引。删除索引必须由 DBA确认新旧查询均可接受，不自动回滚DDL。

<a id="hardening--w05-逻辑总期限鉴权隔离重试与配置查询"></a>
<a id="w05"></a>
### W05 逻辑总期限、鉴权隔离、重试与配置查询

**关联**：R06、R16；应用主责，鉴权/Intent/配置服务协作；T06/T16，RB05，D04。

- **实施**：为一次逻辑调用记录截止时间，排队、阻塞鉴权、HTTP、解析及重试共享剩余预算；阻塞 resolver 自己的网络层也必须有 deadline。保留现有流式 auth 池和候选策略，对尚缺失的阻塞 Intent/用例库/WeLink 补齐。
- **重试**：按依赖操作分类瞬态/永久/未知执行结果；仅安全可重试情况做有限退避抖动，遵循 Retry-After且不越过总预算。合法 NO_MATCH不重试；流已经产生不可撤销结果后不能盲目重启整段。WeLink未知结果由W07处理。
- **配置**：统一 Gate/Provider 完成后显式调度到阻塞 IO 才做仓储；按tenant＋skill合并并发miss，限制合并表基数/并发，成功、失败、取消都结束在途条目，不把缓存内容跨租户共享。
- **前置/接口**：下游/企业鉴权确认真实超时、响应代码和幂等能力；保留默认禁用功能及失败开放/关闭政策，不能把留存配置失败擅自降级到保存业务正文。
- **观测/测试**：auth/HTTP/排队/总耗时、尝试原因、取消后运行任务、实际线程；不响应中断token、401/429、半开连接、配置组合及并发miss。
- **发布/回滚**：按provider灰度、确保下游限额；回滚后仍需下游安全预算/入口限流，不能用无限重试恢复服务。鉴权失败必须保持失败，不能绕过鉴权。

<a id="hardening--w06-文档在途字节流生命周期与对账"></a>
<a id="w06"></a>
### W06 文档在途字节、流生命周期与对账

**关联**：R12，协同 R21/R22；应用/存储主责；T12/T21/T22，RB07/RB10，D06。

- **实施**：API Store替换整份`readAllBytes`，根据其multipart契约使用真正流式输入或受控临时文件；请求数许可之外增加实例在途字节预算。对象存储下载返回的包装流在close/cancel/读取失败时幂等归还许可，不能在拿到InputStream时释放。
- **对账**：为上传分配稳定操作标识，记录存储成功/DB保存/补偿结果；落库失败尽力补偿并保留可重试对账记录。物理对象回收以有效引用核对为前提，区分用户软删除、历史合法引用、孤儿和临时文件，不做全桶清理。
- **前置/接口**：确认 API Store 是否支持可重复流/分块及对象删除/查询，确认存储侧重试可能重复副作用。保持文档ID、下载响应和软删除合同；缺必要查询接口则记录UNKNOWN供人工对账，不编造自动补偿能力。
- **观测/测试**：在途文件/字节、实际打开流/FD、许可、堆、临时磁盘水位、孤儿年龄；大文件/慢客户端/断连/DB失败/kill、限额和上传重试。
- **发布/回滚**：先小文件/单provider灰度；数据迁移采用新增可兼容操作记录，旧版本不应误删新对象。回退下载实现时收紧下载流量并核对残留流，恢复上传前先解决磁盘/信任问题。

<a id="hardening--w07-状态收敛提交未知结果与外部副作用"></a>
<a id="w07"></a>
### W07 状态收敛、提交未知结果与外部副作用

**关联**：R04、R05、R08、R14、R17、R18、R19、R24；应用主责，前端/Relay/WeLink协作；T04/T05/T08/T14/T17/T18/T19/T24，RB05/RB09，D04/D05/D08。

将以下任务分开交付，避免一次事务/协议变更覆盖全系统：

| 子任务 | 具体行为和兼容约束 | 前置/关闭证据 |
|---|---|---|
| W07-A 取消对账 | 持久CANCELLING和删除后残留active在有界期限内重新治理，原终态CAS/fencing竞争保持 | 两实例健康owner持续心跳＋停止方kill；唯一终态与远端实际状态分别核对 |
| W07-B Relay代次 | 联合验证同session有序控制/代次屏障，必要时扩展协议；保留现有上下文复用 | 真Relay重排/迟到Stop实验，不能只依赖flushed/paused或本地mock |
| W07-C 受理幂等 | 优先复用现有commandId，持久绑定owner作用域、请求摘要及受理结果；同键不同载荷拒绝；分享另设操作标识，不按内容相同去重；客户端提交未知先查原结果 | 协议评审冻结保留期/空键兼容、唯一约束及迁移；双实例重复提交/杀进程 |
| W07-D 删除/外围写 | 普通Resume/WS统一验证未删除；分享创建锁Session后重验；小规模分支短事务原子复制，超规模明确拒绝 | 删除/分享/path/分支竞态和复制中断，无半可见数据 |
| W07-E active path | 默认运行中仅查看历史；真正切换active leaf需Session短事务内锁后检查节点与无活动Run，冲突返回明确错误；若要支持运行中切换，另立显示路径/执行路径分离协议 | 前端确认冲突展示，NEXT/终态/delete并发失败不改leaf |
| W07-F 外部投递 | 先记录deliveryId/操作状态；有下游契约才安全重试，无契约的超时结果为UNKNOWN并对账 | 对端送达丢响应、记录失败、同ID重试；状态/协议迁移兼容 |
| W07-G 结果交付 | FULL状态查询＋Resume，no-store提示不可恢复业务缺口；仅对有可靠通知SLO的特定场景另建持久交付任务 | commit/cache/publish/consume分界kill；不新增no-store正文留存 |

- **观测**：取消年龄、删除残留active、终态竞争拒绝、重复请求命中/冲突、UNKNOWN投递、DB提交到通知/消费间隔；日志标识需脱敏，指标标签不得直接使用用户/Run等高基数ID。
- **发布/回滚**：协议与表结构先兼容新增后启用，确保旧客户端/旧实例不绕过幂等或误读新状态；不能安全双版本共存的阶段按功能暂停/排空发布。未知外部副作用保留供对账，回滚不能自动重发或删除记录。

<a id="hardening--w08-高成本查询与可选旁路隔离"></a>
<a id="w08"></a>
### W08 高成本查询与可选旁路隔离

**关联**：R10、R11、R25、R26；应用主责，DBA/性能测试协作；T10/T11/T25/T26，RB03/RB02，D08/D01。

- **查询**：从真实计划优化版本递归、首assistant摘要、最后Run投影、完整tree/分支和分页计数；只传必要字段，限定行数/总字节和独立查询并发；数据库及应用CPU分别看profile，不预先假定某种索引有效。
- **旧旁路**：给旧偏好、RouteMemory、识别记录按使用场景设置排队期限和SQL预算；过期未启动任务不能随后执行。写任务已开始时要得到确定事务结果，不用整体Reactive timeout向前端制造不明迟到写。
- **标题**：任务入口按Session合并、限制完整生命周期在途数；候选读短期限且记录path rows/run IDs，生成许可和提交TX2s保留。生成失败/退出保留原标题，是否可靠补跑单独按产品SLO立项，默认不增加基础设施。
- **前置/接口**：实际数据分布/字段上限和启用开关快照；保持人工标题、版本、scope、排序时间和反馈语义。大历史新增分页/拒绝需前端契约更新，不能暗中减少用户看到的内容。
- **观测/测试**：队列年龄、排队过期/执行中、SQL实际时长、摘要字节、标题candidate/generated/applied/skipped、主Run首事件；慢worker/长历史/深树/锁竞争及多功能突发混合负载。
- **发布/回滚**：优先独立旁路灰度；标题等已具备开关的功能可按既有配置禁用并确认需重启，未实现的入口不能在运行册写成可用开关。回退查询实现必须保持容量配额，防止旧路径压垮DB。

<a id="hardening--w09-部署可观测性业务目标与交接"></a>
<a id="w09"></a>
### W09 部署、可观测性、业务目标与交接

**关联**：R27，统筹所有风险的环境证据；平台/SRE主责，应用/DBA/业务负责人协作；T27及全部环境测试，RB08，D07及D01–D12。

- **基线输入**：以U中的ADS各Region独立/四服务共享依赖/WCM及ALB职责，以及P中的跨AZ/热备/独立静态源目标为基线，收集实际有效配置与覆盖来源、CPU/堆/native/FD/磁盘、实例分布、ALB/Jalor重试/超时/连接/ACL、DB/Redis版本及切换行为、存储/下游配额、证书/日志/备份和鉴权证据。基础拓扑已确定，不再泛称未知；目标P未实证前仍待建设/验收，不能沿用默认值或用户架构陈述代替运行证据。
- **SLI/SLO**：分别测受理、首事件、最终完成、结果恢复、Stop收口；合法业务拒绝和系统过载分开统计，后者不从可用性分母隐藏。RTO分别计服务恢复和遗留任务收口，RPO分别计已提交数据与未持久化事件；先校准再由业务签署数值门槛。
- **监控**：JVM/容器CPU、GC、堆/native/线程/FD/磁盘；各队列深度、字节和年龄、许可/拒绝、DB连接/锁、Redis重连/恢复、遗留Run/Interaction、终态/投递缺口。新指标为待实现，日志不能当作已经存在的Prometheus指标；禁止未经控制使用runId/userId作为指标label。
- **探针/发布**：readiness反映能否受理业务，liveness只反映进程不可自愈失活，不将外部依赖短故障变成全实例重启；先停止新准入、处理在途流、摘流/退出并观察剩余实例恢复。平台终止宽限须与实际排空政策一致，不能用无限等待排空掩盖卡住任务。
- **测试/交接**：真实openGauss、Redis Cluster及配置等价环境演练依赖切换、单实例退出、滚动发布/回滚、证书轮换、告警和备份恢复。值班人员按运行册独立定位/止血/核对，联系人升级链和停止注入条件明确。
- **回滚**：冻结已知好版本/配置/协议/数据库兼容矩阵，触发门槛到达即停扩大发布；新增指标/运行册不要求回退。恢复依赖后逐步放量并观察积压和资源回落，不能仅以HTTP健康为成功。

<a id="hardening--w10-文档信任边界与-obs-tls"></a>
<a id="w10"></a>
### W10 文档信任边界与 OBS TLS

**关联**：R21、R22；应用主责，安全/存储/PKI协作；T21/T22，RB10，D06。

- **metadata**：服务器字段以服务端已有事实为准，客户端 PATCH 仅编辑约定业务命名空间；校验字段类型/字节、保留普通重命名。历史存量异常引用经owner及真实provider查询核实后修复，不从可疑url主动拉取来“验证”。
- **TLS**：显式证书链和hostname严格校验，企业CA进入受控truststore；核对endpoint/SAN、过期及轮换。错误信任链明确失败，不自动fallback为trust-all，也不将私钥或凭证写入文档/日志。
- **前置/接口**：确认可编辑metadata白名单及客户端兼容，确认OBS endpoint/CA和证书责任人；只在huawei-s3启用时适用OBS实现整改。文档引用下游的认证/URL抓取政策另行验证，不把源码模式直接说成已利用安全事故。
- **观测/测试**：metadata拒绝原因、异常历史引用数量、TLS校验失败类型/证书到期；伪造引用、合法重命名、未知CA/错误主机/过期证书、正确链、证书轮换与回退。
- **发布/回滚**：先准备CA和有效证书再启严格校验；旧客户端不兼容时更新客户端或暂停相应编辑操作，不能重新允许改服务器字段。TLS失败时修复证书/endpoint或暂停provider，不以关闭校验作为回滚路径。

<a id="hardening--w11-四服务共享依赖与admintoolda联合契约"></a>
<a id="w11"></a>
### W11 四服务共享依赖与Admin/Tool/DA联合契约

**关联**：R28、R29、R30，协同R02/R05/R06/R08/R14/R19；DBA、Redis平台及四服务负责人共同主责；T28–T30，RB11，D09；对应[DEP01-N09/N11–N15](deployment.md#dep01)、[DEP02-N07/N08](deployment.md#dep02)和[DEP03-N05/N06/N11/N12](deployment.md#dep03)。

- **DB预算**：收齐Admin/Tool/Relay/Chat每实例的池上限、实例数、峰值连接占用、SQL/事务/迁移操作及故障重试。为四服务、治理/取消/恢复、维护操作分配共享库安全预算，并覆盖AZ减少和Region接管后的建连峰值；批任务/DDL错峰，统一四服务的超时、重试与迁移协同；限制扩容建连速率，扩容不得绕过数据库总额度。保持当前主写结构，不默认用读写分离解决连接竞争。
- **Redis分类**：按服务登记缓存、锁/租约/取消标记、Pub/Sub及若存在的持久队列，记录key/频道、TTL、体积、owner和重建策略。前缀/ACL提供逻辑隔离，不能当作内存/CPU/故障隔离；分别限制大key/热key/脚本/订阅及回源速率。跨Region缓存有界重建，锁按新权威状态重新建立，Pub/Sub重订阅＋FULL补读；持久队列先冻结生产/消费到可核对恢复点，核对水位和幂等后再分批恢复，禁止整库盲复制后自动执行。
- **Admin映射**：明确定义mapping发布版本、校验、原子生效、缓存刷新与已知好版本回滚，联合Tool和Chat记录每请求实际解析版本/目标，Stop和回调沿原执行目标处理，不因mapping更新改发另一DA。发布失败不得留下部分节点的新旧混合映射未被发现；最后可用版本只在约定新鲜度和授权规则内续服，未知/撤销/越权配置拒绝执行，不擅自改变业务目标。Chat独立技能属性查询不等同Tool映射，其留存/附件失败语义保持。
- **Tool/DA契约**：沿Chat→Tool→DA传播稳定操作ID和Trace，明确已接收/已执行/已完成/取消及查询语义；超时、断流、区域切换后已发送未确认的操作记UNKNOWN并查询/对账。没有下游幂等保证时禁止自动重发；Chat直连Relay仍遵守W07-B的session停止代次验收，第三方Intent单独按W05验证。
- **前置/接口**：必须由其他三服务及DA提供实现/配置/协议证据；本仓库源代码不能替代。新增mapping版本、操作ID、UNKNOWN或查询接口是P，须评审schema/幂等作用域/保留期/旧客户端兼容，再更新OpenAPI与联调契约；不把未实现的“全服务暂停”开关写成运行册动作。
- **观测/测试**：服务维度连接/锁/SQL/Redis耗时与内存、回源速率、mapping版本不一致、UNKNOWN操作和重复执行数。D09依次让每个服务成为压力源，再组合共享DB/Redis故障与Tool已转发丢响应；其他服务的受理、终态和恢复须满足批准目标。
- **发布/回滚**：先预算/观测，再分服务灰度保护和协议兼容增量；mapping回到可核对的已知好版本。数据库/Redis故障止血用已验证的网关、任务暂停或部署手段，保留治理资源；未知外部操作不因回滚被自动重放，队列/执行记录不随代码回退删除。

<a id="hardening--w12-wcm独立静态备用源与alb区域入口"></a>
<a id="w12"></a>
### W12 WCM独立静态备用源与ALB区域入口

**关联**：R31、R32，协同R03/R04/R08；WCM、对象存储、网络/ALB及前端共同主责；T31/T32，RB12，D10；对应[DEP04-01–12](deployment.md#dep04)，关联[DEP01-N02–N05/N08](deployment.md#dep01)和[DEP03-N02/N03/N07/N09/N13](deployment.md#dep03)。

- **预发布静态源**：每次发布同时生成不可变版本目录、manifest/hash及HTML/bundle/公共运行配置，将备用产物提前放到独立于ADS的对象存储发布源，由独立HTTPS托管能力对外服务。对象存储API不自动具备静态HTTPS/SPA回退/私有源鉴权，须实测ALB可接入；不能临时加ADS内唯一代理后宣称仍独立。验证证书、发布权限和访问链路不依赖故障ADS；备用版本由独立探针持续核对，不在WCM失效后临时构建/上传。
- **兼容与安全**：保持文根/相对资源路径、缓存版本、登录回调、API区域地址及CORS/CSP；运行配置不得包含凭证。静态备用只提供页面与资源，API/WS/Tool/Relay仍走选定Region的服务链路，首页成功不计作聊天恢复；后台不可用时页面明确说明状态。
- **ALB配置**：产出各Region域名/文根/路径/目标组矩阵，API请求不得被SPA静态回退吞掉；分别验收HTTP上传下载、WS Upgrade、SSE flush/缓存及idle/heartbeat预算，禁止未经幂等保护的POST自动重试。ALB/Jalor/Servlet/下游期限的各段关系进入有效配置证据，不用应用30分钟期限推定入口能保持30分钟。
- **切换**：WCM故障可先切静态源，但不因此改动API写Region；区域切换由W14授权顺序执行。GSLB/DNS刷新仅影响重新解析和新连接，旧连接需摘流/关闭并引导客户端按游标、退避和恢复配额重连；旧Region必须先阻断写入，不能依赖TTL实现防双写。
- **前置/接口**：WCM/对象存储提供实际托管能力和URL，网络提供ALB/GSLB配置，前端提供base path/配置加载契约。保持既有API格式；备用页面缺依赖或版本不符时停止切换，不能把404资源或API HTML当作健康。
- **观测/测试**：版本/hash、静态资源失败率、文根命中、WS/SSE连接/断流、真实Region落点、DNS缓存及重连量。D10隔离WCM/ADS相关路径后测试独立备用、故意保留旧DNS/socket、文根嵌套、idle流和恢复风暴，分别报告页面与业务恢复。
- **发布/回滚**：先完成主备同版本预发布/验证再变更入口；保留上一不可变静态版本，回切先核对目标健康和缓存兼容再逐步切回。API写Region不可随静态源回滚自动变化；ALB错误路由回退后核对未误重放POST。

<a id="hardening--w13-ads故障域隔离和az失效容量"></a>
<a id="w13"></a>
### W13 ADS故障域隔离和AZ失效容量

**关联**：R33、R34，协同R27/R28/R29；ADS平台、网络/DBA和四服务容量负责人共同主责；T33/T34，RB13，D11；对应[DEP02-N01–N09](deployment.md#dep02)及[DEP03-N04/N08/N10/N14/N15](deployment.md#dep03)。

- **控制/运行面**：按U基线分别登记两个Region的ADS运行面与控制面入口、账号/权限、配置/密钥下发、镜像/制品、监控和网络依赖，列出仍共用的外围服务。控制面失效时先冻结发布/扩缩容/变更，确认健康实例可继续运行及其限制；运行面失效按业务探针、实例/依赖证据处理，不因单一控制探针故障立即切Region。
- **实际落点**：验证四服务副本、工作节点、ALB目标、DB/Redis区域内HA成员和出站依赖路径跨至少两个AZ分布，配置反亲和/故障域约束并检查调度结果。发布/维护窗口也不得短暂移除所需故障余量；“有多个副本”不能替代跨AZ证据。
- **容量**：针对最大故障AZ退出，逐服务测剩余安全承载量及共享DB/Redis/下游配额，要求覆盖批准的故障期业务量、治理和恢复预算；冻结正常/故障期限流值与优先级。必要容量预留到位，不能仅依赖故障后失效控制面扩容；任何动态降级开关都需先实现/演练，否则使用现有且验证过的入口/部署控制。
- **前置/接口**：平台需提供可验证的AZ映射、调度/摘流行为、容量及权限，未获证据不能把ADS抽象等同某个公开云产品保证。四服务已有状态/事件语义不变，AZ内故障不默认触发跨Region数据提升。
- **观测/测试**：控制API与业务请求分别监测，按AZ观察副本/目标、成功率、队列/许可/连接/CPU/FD、回源和恢复积压。D11分别控制面隔离、运行面失效、完整AZ网络隔离，并覆盖AZ恢复后的重连/放量，不能用单Pod退出代替AZ演练。
- **发布/回滚**：先单服务验证故障域策略，再四服务混合负载与共享依赖组合演练；错误调度策略回退时仍保留跨AZ最低分布。故障AZ恢复先验证副本/依赖健康后分批纳入目标组，避免重连和建连同时冲击数据库。

<a id="hardening--w14-region故障接管防双写与受控回切"></a>
<a id="w14"></a>
### W14 Region故障接管、防双写与受控回切

**关联**：R35，协同R04/R05/R08/R14/R19/R28–R34；容灾指挥、DBA、ADS/网络和四服务负责人共同主责；T35，RB14，D12；对应[DEP03-N15](deployment.md#dep03)、[DEP05-01–14](deployment.md#dep05)、[DEP06-01–13](deployment.md#dep06)。

- **目标/前置**：建设并验收P中的双Region预部署热备单写。两个Region的代码、schema兼容、Admin mapping、凭证/CA、静态产物版本、业务附件对象恢复能力及其DB引用/权限对账、下游/企业鉴权网络和独立控制入口必须在故障前验证；备Region后台任务和管理写入默认不得取得业务写权。DB按区域内HA、跨Region异步复制设计，不承诺RPO=0，不通过全面读写分离改变事实源。
- **单写控制**：定义所有写路径的阻断证据，覆盖四服务API、Admin变更、Tool执行、后台任务/Watchdog、异步回调及长连接。旧Region写入须由可独立验证的数据库/网络/凭证或平台隔离机制撤销，具体命令由平台实测固化；Chat的同库fencing、DNS TTL或“旧Region访问不到”不能替代防双主。无法证明旧写已阻断时停止提升，保持写不可用并升级处理。
- **接管顺序**：DEP05-01–02判定影响/受控接管、停旧准入且备端未开放→03–04隔离旧应用外部执行与旧DB写入并取证→05–06核对最终可恢复水位、业务附件对象恢复点及DB引用/权限缺口、批准RPO，超界或无法核对则停止→07提升唯一DB写端并按W11分类准备Redis→08按已核对的schema/映射/凭证和Tool/Relay任务策略激活有权服务→09–10用限定验证流量核对业务、附件读取/引用/权限和唯一写入→11切GSLB/DNS/ALB→12–13处置旧连接、分批Resume并对账遗留任务。任一阶段无法证明旧写/旧外部执行已隔离，执行14保持备用禁写；早期可预查复制延迟，但不能替代隔离后的恢复点确认。
- **数据/副作用**：FULL只回放新主实际存在的数据；no-store不补存正文。丢失水位覆盖的Run/消息/工具请求及业务附件对象需列清单核对，不因“新主没有记录”自动重新执行；外部副作用按稳定操作ID查询/UNKNOWN人工对账。业务附件对象恢复点与DB引用/权限须独立于静态产物核验；超出签认RPO或无法核对阻断激活/开放，已批准损失列明对象、引用、影响及责任人，并隔离受影响功能，仅声明通过验证的服务范围恢复。缓存可重建，锁/租约/取消标记不继承旧执行权；若存在持久队列，先核对两侧消费水位和幂等再恢复，禁止整库复制后盲重放。
- **回切顺序**：回切是独立变更，故障恢复不自动回切。DEP06-01–03修复旧Region并作为只读/被隔离目标提前反向同步、验证；04当前主停新写/调度并有界排空→05预收集恢复水位和未知任务，不能作为最终追平门槛→06强制隔离当前主DB写入、应用及外部执行→07取得或证明隔离后的最终提交位点，目标追平该位点并校验业务附件对象恢复点、DB引用/权限和批准损失边界→08目标激活为唯一写端→09四服务/Redis/映射/下游及附件验收→10切入口/关闭旧连接→11重建灾备复制→12观察。提前同步不替代隔离后的最终核对；未追平、附件超签认RPO或无法核对、任一侧仍可写或存在未决冲突则执行13停在已知安全阶段，不自动合并冲突数据。
- **接口/迁移**：持久恢复操作状态、写权epoch/操作ID、UNKNOWN或新查询能力若现有平台/协议不支持，单独作为实现任务；原OpenAPI/事件留存不默改。故障期间停止服务采用已验证的部署/网络/网关/数据库操作，不假设已存在应用动态总开关；DB提升本身不是普通代码回滚。
- **观测/测试**：统一故障时间线，记录复制水位/延迟、隔离后最终提交位点及追平证据、业务附件对象恢复点和引用/权限缺口、各侧写入探针、入口实际落点、DNS/连接残留、UNKNOWN操作、Run/Interaction/Binding和队列对账、实际服务RTO/遗留任务RTO/数据及附件RPO。D12覆盖Region失效、网络分区、复制滞后、保持旧DNS/socket/回调、提升失败、回切预收集后隔离前迟到提交、附件落后/权限不一致、回切中断和恢复后负载；每阶段证明最多一个写权威，并验证损失超界/无法核对时阻断以及已批准损失下的功能隔离。
- **失败与回退**：提升前失败可保持原权威且暂停切流；提升后不得直接恢复旧主写或仅切回DNS，须按同样单写/同步/对账原则重新决策。保留切换证据、潜在丢失和外部执行清单；服务恢复、数据核对及积压收敛全部满足才结束容灾，不以ALB健康200结案。

<a id="hardening--统一关闭与未完成项移交"></a>
### 统一关闭与未完成项移交

每个工作包交付时附一条机器可定位的风险证据记录：

| 字段 | 必填内容 |
|---|---|
| 版本/配置 | 修复提交、依赖版本、部署批次、脱敏后的有效参数和开关 |
| 执行 | T用例、数据规模、混合负载、注入对象/时长、起止时间及清理验证 |
| 结果 | 业务事件/DB状态比对、错误/重复/缺失数量、CPU/内存/连接/队列曲线、RTO/RPO实测 |
| 运维 | RB运行册版本、D演练记录、停止/回滚动作、告警到止血/恢复时间 |
| 决策 | 通过/失败/受限、剩余风险、责任人/复核人；风险接受需具名、范围和到期日 |

本轮资料不包含生产故障注入或加固后的性能实测。用户已确认基础拓扑U并选定目标P；平台有效配置、目标建设结果、故障域/容量证据、复制水位及单写隔离行为、业务数值目标、下游Stop/执行/投递契约尚未取得时，对应风险保持ENV/OPEN，并列为实施与上线门槛。
