# 本轮验证记录与证据边界

当前审查基线为`8f48d6cc084be91bcbaad90be43dac7181636cb4`（2026-09-21），本轮文档检查见[第8节](#stability-review-20260921)。**下方第1–7节及其开头说明为2026-09-18历史原记录**；当时的基线、179项测试、失败/重跑记录、旧路径和图数量均保持，不表示本轮重新运行。当前架构事实以[部署现状](deployment.md#dep01)为准，历史Tool/Admin独立描述已被本轮一体agentService事实替代。

---

源码基线：`00abae4f80b7e7e5b4d0ddca707035f1878a8ec8`。执行日期：2026-09-18（Asia/Shanghai）。本轮新增/更新高可用文档，未修改业务Java、测试Java、SQL、配置或协议；未运行生产操作、数据库迁移、压测或故障演练。

第1–4节保留首批场景审计及179项现有测试的原记录；第5节记录同日按用户确认拓扑补充部署/容灾文档的增量检查。增量阶段没有重复运行Java测试，不能把原结果算作新增平台测试通过。

## 1. 已执行检查

| 检查 | 本轮结果 | 证明范围 |
|---|---|---|
| 当前源码与旧审计差异 | 复核当前HEAD、工作区、Controller/配置、R01–R25相关代码；补充R26/R27 | 已识别当前结构和风险条件，不证明所有风险可在生产复现 |
| 接口覆盖 | 45个Controller handler、44个唯一HTTP操作，Controller/接口索引/OpenAPI操作集合一致 | 两种文档上传adapter互斥，HTTP操作只计一次；WS/后台任务另按场景登记 |
| OpenAPI内部引用 | Ruby YAML解析后递归检查，317个本地`$ref`均可解析 | 结构和引用完整，不代表真实网关/鉴权联调通过 |
| 初次定向测试 | 179项，0 failure、44 error、0 skipped，BUILD FAILURE | Mockito在受限环境无法通过外部进程self-attach，属于测试工具初始化失败；保留原记录 |
| 启动代理复测 | 同一12类，179项，0 failure、0 error、0 skipped，BUILD SUCCESS | 仅调整本次Maven JVM启动参数预加载Mockito代理；未修改测试或业务源码 |
| 新时序图 | 12场景、24张Mermaid图全部生成SVG；抽取启动、流式输出、恢复、标题和治理图生成PNG，视觉抽查启动/流式/治理 | 首轮标题参与者`TITLE`触发保留字解析错误，改为`TTL`后全量重渲染成功；未把首轮失败删去 |
| 文档结构 | 检查本审计目录全部本地文件链接、Markdown标题锚点、源码行号范围、场景步骤唯一性及R/T/W/RB/D编号完整性 | 行号存在不等于语义正确；关键状态和资源事实另做源码交叉审阅 |
| 改动范围与空白 | `git diff --check`及工作区文件清单检查 | 仅文档；不包含业务修复或故障注入的完成证明 |

没有再次执行`mvn clean verify`或全量打包。其他基线的1514项结果不叠加到本轮179项中；其旧版文档已按后续要求删除。首批T01–T27与D01–D08、后续扩展的[T01–T35规格](tests.md)和[D01–D12演练](operations.md)均未执行，不能用这些现有单元测试替代。

## 2. 定向测试及复现命令

| 测试类 | 本轮项数 | 关注点 |
|---|---|---|
| RunAdmissionControlServiceTest | 2 | 本机准入/窗口清理 |
| RuntimeBindingApplicationServiceTest | 66 | 当前专家/Delegate及Binding生命周期 |
| ChatRunStartFlowTest | 12 | 启动及首事件交接 |
| ChatRunTerminalCommitServiceTest | 21 | 原子终态、Binding及Interaction处理 |
| ChatRunLeaseApplicationServiceTest | 5 | claim/心跳与失权 |
| ChatStreamApplicationServiceTest | 21 | 恢复和订阅边界 |
| ChatEventPipelineRetentionTest | 8 | FULL/no-store事件管线 |
| RedisChatLiveEventBusTest | 13 | 发布与恢复信号 |
| ChatServletWebSocketHandlerAsyncSendTest | 2 | 慢发送隔离 |
| DomainAgentAsyncTaskCallbackApplicationServiceTest | 15 | 回调验证、结果标准化和发布 |
| DomainAgentAsyncTaskCallbackCommitServiceTest | 8 | 回调终态和正文原子写入 |
| DomainAgentAsyncTaskCallbackAdmissionFilterTest | 6 | 入口并发/体积 |

本机JDK21及Maven3.9.6使用已缓存依赖，离线执行。以下路径是本机位置，其他环境需使用对应安装路径。首轮命令与下方相同但不包含`-DargLine`，日志另存；使用启动代理是为消除self-attach环境依赖，不是跳过失败断言。

```sh
env JAVA_HOME=/Users/uben/.cache/codex-jdks/temurin-21/Contents/Home \
  /Users/uben/.m2/wrapper/apache-maven-3.9.6/bin/mvn -o -B \
  -DargLine=-javaagent:/Users/uben/.m2/repository/org/mockito/mockito-core/5.14.2/mockito-core-5.14.2.jar \
  -Dtest=RunAdmissionControlServiceTest,RuntimeBindingApplicationServiceTest,ChatRunStartFlowTest,ChatRunTerminalCommitServiceTest,ChatRunLeaseApplicationServiceTest,ChatStreamApplicationServiceTest,ChatEventPipelineRetentionTest,RedisChatLiveEventBusTest,ChatServletWebSocketHandlerAsyncSendTest,DomainAgentAsyncTaskCallbackApplicationServiceTest,DomainAgentAsyncTaskCallbackCommitServiceTest,DomainAgentAsyncTaskCallbackAdmissionFilterTest \
  test
```

## 3. 文档与图形检查方法

- 从Controller提取类级路由与方法级GET/POST/PATCH/DELETE，组合后与OpenAPI及44项索引比较集合；YAML结构解析后递归解析内部JSON Pointer。
- 扫描Markdown本地链接：文件存在、标题锚点存在、源码`#L`处于文件有效范围；扫描S01–S12每组各2图和图内步骤不重复。
- 使用本地已缓存的Mermaid CLI/Puppeteer，仅读取本地文档并输出到临时目录，不通过Figma或远程渲染服务上传代码。受限环境首次浏览器启动失败，随后通过自动审查允许的本地浏览器执行完成渲染。
- 图形检查使用可缩放SVG，PNG用于视觉抽查。当前最长图为流式代码图，建议在完整宽度或缩放视图阅读；Markdown中的Mermaid源是维护事实源。

在已安装`mmdc`的环境可复现渲染，不需要修改工程依赖：

```sh
mmdc -i docs/architecture/high-availability/scenario-sequences.md \
  -o /tmp/financeex-ha-scenarios.md -e svg -j 2
git diff --check
```

临时证据不纳入Git，也不保证跨机器存在；关键结论已经记录在本页，正式实施时需将原始证据转存团队证据库。

| 本机临时文件 | 内容 |
|---|---|
| `/tmp/financeex-ha-current-tests.log` | 首轮Mockito初始化失败记录 |
| `/tmp/financeex-ha-current-tests-agent.log` | 启动代理后179项成功记录 |
| `/tmp/financeex-ha-doc-check.py`、`/tmp/financeex-ha-doc-check.json` | 链接/编号/接口集合/引用检查脚本及结果 |
| `/tmp/financeex-ha-openapi.json` | YAML解析结果，用于内部引用核对 |
| `/tmp/financeex-ha-scenarios-render.log` | 首轮TITLE保留字解析失败，前21张成功 |
| `/tmp/financeex-ha-scenarios-render-final.log` | 修正后24张图渲染结果 |
| `/tmp/financeex-ha-scenarios-1.svg`至`/tmp/financeex-ha-scenarios-24.svg` | 按文档顺序的可缩放图形 |
| `/tmp/financeex-ha-sample-{2,6,14,22,24}.png` | 抽样视觉检查图 |

## 4. 尚不能由本轮证据关闭的风险

1. 实际openGauss锁/驱动期限、主备切换、持久化RPO与备份恢复；Redis Cluster切主/网络分区/重订阅。
2. 真Relay迟到Stop代次隔离、DomainAgent异步回调协议、企业鉴权黑洞、WeLink未知结果与远端去重。
3. CPU/内存/FD容量阈值、队列峰值、长期对象增长、临时磁盘、真实慢客户端和恢复风暴。
4. Jalor路由/重试/ACL/长连接期限、readiness/liveness、实际滚动摘流、实例故障域与余量。
5. OBS证书链/hostname与轮换验收、文档引用对账、生产前端恢复游标和业务错误处理。

这些项目已在[风险登记](risks.md#risk-register)、[工作包](risks.md#hardening)和测试/演练规格中给出责任与关闭条件；当前交付是落地蓝图，不是“高可用加固已上线”的验收报告。

## 5. 部署与容灾补充的增量验证

新增[部署与容灾设计](deployment.md)，同步当前蓝图、场景/资源、风险、加固、测试、运行册及导航。基础部署事实U与选定目标P分开，平台运行证据仍为E；R28–R35不能由文档检查关闭。

| 检查 | 本次结果与证明范围 |
|---|---|
| 拓扑与源码边界 | 重新核对统一skillId请求、独立Relay WS、技能属性API及UnsupportedAgentRuntimeRecoveryPort；Tool/Admin/Relay内部实现和平台参数未做源码审计 |
| 图及步骤 | 保留S01–S12共24图和原数字步骤；新增72个字母后缀步骤。DEP01–DEP06各1图，节点/时序步骤具有稳定编号；修改后30张图全部渲染为SVG |
| 首轮渲染失败及修正 | 沙箱首次无法启动本地Chromium；经自动审批允许本地渲染后，DEP04参与者ALT触发Mermaid保留字错误。改为BACKUP后6图成功；又调整DEP01布局并重渲染。原失败日志保留 |
| 可读性 | DEP01/DEP02/DEP03/DEP05及S02/S03/S12粗图生成PNG，抽样检查标签、故障域和分支；长宽图以可缩放SVG/完整宽度阅读，Markdown Mermaid源为维护事实源 |
| 闭环完整性 | R01–R35、T01–T35、W01–W14、RB01–RB14、D01–D12连续；R28–R35逐条关联对应W/T/RB/D及DEP图，未标CLOSED或VERIFIED |
| 本地引用与接口 | 全审计目录23份Markdown检查文件存在、标题锚点及源码行号范围；仍为45个Controller handler/44个唯一HTTP操作，OpenAPI内部317个引用可解析 |
| 关键一致性复核 | U/P/E不混用；旧区域隔离先于开放新写；故障注入TTL不解除主权屏障；回切以强制停写后的最终提交位点追平，失败按最后确认写权处置；业务附件对象与DB引用/权限纳入放行门槛；FULL受实际恢复点约束，no-store及未知副作用不盲重放 |
| 范围 | `git diff --check`通过；工作区改动仅docs/architecture文档，未修改Java/SQL/配置或执行部署。没有新增平台测试、演练或容量结论 |

23份文档共含61个Mermaid块，其中31个历史图本次未重渲染；本次渲染范围明确为当前24场景图和6部署图。链接检查确认引用可定位，不代替代码语义审阅、外部文档真实性或生产运行证明。

| 临时证据 | 内容 |
|---|---|
| `/tmp/financeex-ha-deployment-doc-check.py`、`/tmp/financeex-ha-deployment-doc-check.json` | 扩展后的链接/源码行/编号/30图步骤/新增风险闭环及接口检查 |
| `/tmp/financeex-ha-deployment-render.log` | 本次首轮本地浏览器沙箱启动失败 |
| `/tmp/financeex-ha-deployment-render-reviewed.log` | 首次获准渲染时ALT解析失败，前2图成功 |
| `/tmp/financeex-ha-deployment-render-final.log` | 6张部署图最终渲染通过 |
| `/tmp/financeex-ha-deployment-scenarios-render.log` | 更新后的24张场景图渲染通过 |
| `/tmp/financeex-ha-deployment-{1..6}.svg`、`/tmp/financeex-ha-deployment-scenarios-{1..24}.svg` | 本次最终SVG |
| `/tmp/financeex-ha-dep01.png`、`/tmp/financeex-ha-deployment-samples-{1..4}.png`、`/tmp/financeex-ha-deployment-scenario-samples-{1..3}.png` | 可读性抽样；DEP01最终布局以单独dep01.png为准 |

本地复现方式沿用第3节的Mermaid CLI；分别以`scenario-sequences.md`和`deployment-disaster-recovery.md`为输入。平台验收仍需CAP01–CAP10的实际证据、真实openGauss/Redis Cluster、ADS/ALB/WCM/独立静态源联调和数值目标签认，所有新增测试/演练保持NOT_RUN。

## 6. 目录精简与内容归并检查（2026-09-18，历史整理记录）

本次只整理文档，不重新审计业务实现，不执行Java测试、部署或故障注入。第1–5节中的旧文件名、23份文档/61图等数字与命令仍表示当时的检查快照，未改写为整理后的结果。

| 调整 | 整理后的唯一维护位置 |
|---|---|
| 导览与落地蓝图合并 | [README](README.md)：目标、服务口径、阶段和上线门槛 |
| 场景、资源状态、接口索引合并 | [scenarios](scenarios.md)：保留12场景/24图、44个HTTP操作及非REST入口 |
| 风险登记与加固任务合并 | [risks](risks.md)：35项风险及14个工作包；措施只在W维护，风险保留源码/触发/保护/关闭证据 |
| 部署、测试、预案、证据分工 | [deployment](deployment.md)、[tests](tests.md)、[operations](operations.md)、本页 |
| 当时将12篇旧资料收敛为3份归档 | 后续已按用户要求删除，见第7节；本行仅记录当时整理动作 |

检查结论：

- 目录由23份同层文件收敛为7份当前主文档＋3份归档，当时共10份；后续删除归档后仅保留7份。
- 当前30张图原文及顺序不变；保留16张旧启动/路由/控制/标题细图。删除15张已被当前图覆盖的历史总览，保留其步骤说明和条件表，合计46张图。
- 用整理前快照对比Mermaid内容哈希多重集：差集严格等于选定15张旧总览；未新增或误改其他图。图文本未变，本次没有重复渲染或将历史渲染冒充新运行。
- R01–R35、W01–W14、T01–T35、RB01–RB14、D01–D12、S01–S12及DEP01–DEP06编号连续；44个HTTP操作与OpenAPI集合一致。
- 当前风险的源码证据、触发影响、已有保护及关闭证据逐行保留；35项重复措施改指向同页W任务，独有幂等、路径切换、建连/队列和CPU约束已并入对应W。
- 本目录全部本地文件/标题/显式锚点/源码行号引用，以及目录外入链均校验；归档目录增加层级后的源码链接重新计算。历史与当前同号章节通过不同显式锚点区分。
- 原基线1514项、旧细化78项、当前179项及各自失败/重跑/跳过记录分开保留；历史shell命令内容逐块比对不变。新故障用例和演练仍未执行。
- 变更范围仅`docs/architecture`，并检查包含未跟踪新文档的行尾空白。未创建重复旧路径占位文件。

本机临时核对材料为`/tmp/financeex-ha-simplify-before.json`（整理前快照）、`/tmp/financeex-ha-simplify-map.json`（路径/锚点映射）、`/tmp/financeex-ha-removed-overviews.json`（去重图哈希）以及`/tmp/financeex-ha-simplify-check.py`和对应JSON结果。临时文件不是持久证据库；上述结果及维护结构以本页和README为准。

当前路径的图形复现入口如下，属于后续重渲染方式，不表示本次已运行：

```sh
mmdc -i docs/architecture/high-availability/scenarios.md -o /tmp/financeex-ha-current-scenarios.md -e svg -j 2
mmdc -i docs/architecture/high-availability/deployment.md -o /tmp/financeex-ha-current-deployment.md -e svg -j 2
```

## 7. 删除旧版资料（2026-09-18）

按用户要求删除3份旧版资料及其archive目录，不另建替代副本。第6节的10份文档、46张图等是删除前已执行的检查记录，不代表当前目录内容。

- 当前目录仅保留README、deployment、scenarios、risks、tests、operations、evidence共7份文档。
- 清理本目录及目录外入口的旧版链接，场景/接口/风险索引改为当前S01–S12及源码；不保留无法定位的旧图步骤编号。
- 保留当前24张场景图与6张部署图，Mermaid内容不变；R35/W14/T35/RB14/D12编号体系及44个HTTP操作仍完整。
- 772处本地链接（含200处源码行号引用）检查通过，无残留旧版链接，行尾空白检查通过；仅修改文档，未运行新的业务测试或故障演练。

当前业务测试记录仍为第1–2节的179项现有测试，不将已删除旧版的执行结果计入当前验收。


<a id="stability-review-20260921"></a>
## 8. 全面稳定性复核与服务隔离方案（2026-09-21）

起点提交：`8f48d6cc084be91bcbaad90be43dac7181636cb4`；从干净的`202605/fin_ex_web_v3`创建`codex/ha-stability-hardening`。与历史`00abae4`比较，`src`、`pom.xml`和联调前端没有变更，本轮仍重新核对关键调用链、事务、SQL、默认配置和证据边界。复用结论不等于重跑历史实验。本轮只修改7份高可用文档及3份相关架构文档；没有修改业务/测试Java、SQL、部署或配置。

| 检查 | 本轮结果 | 证明范围与限制 |
|---|---|---|
| 入口及覆盖 | 重新提取45个Controller handler，对应44个唯一HTTP操作；Controller、场景索引及当前OpenAPI集合一致；317个本地引用可解析 | 增补前端技能查询、admin管理、MCP三个外部入口组；不计入Chat接口数量。入口×场景×资源×十类故障矩阵有检查落点，不承诺消除所有未知风险 |
| 源码与锁/等待 | 核对受理、路由、事件、交互、Stop、回调、恢复、历史/删除、文件、旁路、治理及其适配器/Mapper/配置；新增14条事务锁路径、6类JVM锁对象和16段等待预算 | 保留Session排序、Run NOWAIT、短事务、CAS/fencing；区分同事务锁序与commit后阶段。记录兼容入口事务内Redis、部分期限未覆盖鉴权/排队/底层IO等条件，不宣称已复现死锁 |
| 当前与目标架构 | 当前一体agentService和两条执行链、独立intentService、共享DB/Redis按U/S纠正；DEP01分现状与目标 | 未来五服务、文档直传/隔离worker、跨AZ/Region与静态备用均属P；外部内部实现/平台参数仍E。修正目录外两个架构入口和零留存提案的物理调用边界 |
| 风险闭环 | R01–R38、T01–T38、W01–W14、RB01–RB14、D01–D12连续；38行风险矩阵全部具备W/T/RB/D和责任角色 | 新增R36一体服务争抢、R37 MCP放大/取消、R38跨路径锁等待/事务边界；原35项重核条件与保护，无CLOSED项 |
| 文档引用 | 1013处本地链接（含320处源码行号引用）、Markdown锚点、目录外入链和修改的相关文档检查通过；原显式锚点、场景步骤及DEP编号保留 | 行号存在不等于语义正确；关键新结论另外核对源码，外部服务无源码不补造内部实现 |
| 图语法与渲染 | 12场景24图＋7部署图共31张主图均经本地Mermaid CLI生成SVG；另渲染修改的目录外整体架构图1张 | 本轮渲染不沿用历史30图结果；未向第三方制图服务上传文档。长图以可缩放SVG或完整宽度阅读 |
| 图可读性 | 32张图生成PNG联系表检查整体布局；放大抽查DEP01目标、S02粗图、S03/S04/S09/S11细图、DEP06回切；长类名分行、资源名统一及缓存/DB/ACK边界拆分后复渲染 | 保留真实类/方法名、稳定步骤及U/S/P/E边界；PNG仅为临时视觉检查，仓库Markdown/Mermaid为事实源 |
| 交叉审阅修正 | 修正Redis发布执行器任务队列与每topic事件缓冲混淆、OBS期限作用域、现状与目标混用；补T12/D06文档迁移与T27/D07灰度子矩阵 | 500MiB不是当前默认允许或已压测通过；已有50MB/60MB及许可不代表该容量安全；灰度共库必须验证旧新应用与DDL兼容 |
| 范围与格式 | `git diff --check`通过；改动限定10份相关Markdown；高可用目录仍7份，无archive或重复方案 | 无生产操作、Java测试、压测或故障注入；文档检查不能关闭业务风险或代替容灾验收 |

本轮首次文档检查在并行编辑期间发现新锚点尚未落地、灰度锚点拼写及子标题重复T编号，均在最终检查前修正；没有将其当作业务测试失败。渲染后对三张细图的长参与者名称加换行，统一细图DB/Redis名称并拆开缓存和ACK边界，重新渲染确认。补充JVM锁内emit/dispose与已有锁外发送/释放保护，未取得反向锁环证据。历史Mockito错误及旧图解析失败原样保留在第1–7节，不计入本轮测试。

本轮用例和演练均为**NOT_RUN**；执行时若契约、平台能力或预算缺失，则相关子例标BLOCKED。仍待真实环境验证：

- 实际openGauss版本/驱动/隔离级别、锁等待/死锁实验、DDL影响、池耗尽与切主后的回滚及连接释放；Redis按真实部署拓扑验证，生产使用Cluster时不能以standalone替代。
- agentService各模块及Relay MCP的队列、扇出、嵌套重试、远端停止、幂等与UNKNOWN对账；DomainAgent/intentService的SLA数值和定位字段须双方签认。
- 4C4G等实际规格下的CPU/堆/native/线程/FD/临时盘上界、500MiB传输、稳态及故障恢复放量；未得到测量前不推定并发容量或服务必然崩溃。
- 前端/WCM备用源、ADS控制/运行面、ALB流式期限及摘流、跨AZ剩余容量、Region网络分区单写与回切；SLO、服务/任务RTO及数据/附件RPO尚未冻结。
- 文档迁移、直传授权/完成登记、EDM worker隔离、配置版本和灰度协调、跨服务trace/结果查询/取消协议等均为待实施任务，不是现有控制API。

复现文档检查时读取当前文件，不复用旧YAML导出或旧图快照。临时校验脚本和渲染产物只作本机证据，正式验收须归档原始指标/日志及执行单。

```sh
mmdc -i docs/architecture/high-availability/scenarios.md -o /tmp/financeex-ha-stability/scenarios.md -e svg -j 2
mmdc -i docs/architecture/high-availability/deployment.md -o /tmp/financeex-ha-stability/deployment.md -e svg -j 2
git diff --check
```

| 本机临时证据 | 内容 |
|---|---|
| `/tmp/financeex-ha-stability-check.py`、`/tmp/financeex-ha-stability/check.json` | 当前文件链接/锚点/源码行范围、稳定编号/步骤、38行闭环、全新解析OpenAPI、改动范围及空白检查 |
| `/tmp/financeex-ha-stability/scenarios-render.log`、`deployment-render.log`、`architecture-render.log` | 本轮最终31主图及目录外1图的本地渲染记录 |
| `/tmp/financeex-ha-stability/scenarios-{1..24}.svg`、`deployment-{1..7}.svg` | 当前主图SVG；同名PNG及`contact-{1..4}.png`用于可读性检查 |
| `/tmp/financeex-ha-stability/render-manifest.json` | 最终图源摘要与产物检查，区分图修改后的复渲染 |

本次分支提交/推送只发布方案，不表示W01–W14已实现或T/D已通过。后续关闭风险须补修复版本、有效参数、双方契约、资源与业务断言、测试/演练证据和具名复核。

<a id="convergence-review-20260922"></a>
## 9. 运行视图与关键依赖风险收敛（2026-09-22）

本轮从文档提交`975db46668c069a22c2f4323c142d9b52632c9d3`继续更新现有`codex/ha-stability-hardening`分支。源码基线仍为`8f48d6cc084be91bcbaad90be43dac7181636cb4`；核对`src`、`pom.xml`与联调前端相对该基线无变更。本轮只修改7份高可用文档及2份相关架构摘要，不修改业务代码、配置、SQL或部署。第1–8节原文逐字保留，以下为新一轮文档检查，不重算历史实验结果。

| 检查 | 本轮结果 | 证明范围与限制 |
|---|---|---|
| 文档与视图收敛 | 保持7份主文档，无archive；S01–S12由24张双层图改为12张服务级时序图，部署保持7图，主图共19张 | 删除代码级图，参与者只展示服务/入口/中间件/存储；稳定步骤统一为Sxx-01…，旧C/D步骤引用已替换 |
| 命名与架构 | 当前视图统一saas gateway（SaaS统一网关）、ALB、共享DB（openGauss）、Redis及intentService（第三方）；目录外两个当前架构入口同步 | 真实源码文件名不改名；当前一体agentService与未来admin/tool/agent拆分分开。各Region独立ALB与ADS运行/控制面为P设计约束，实际能力仍E，未冒充已部署事实 |
| 依赖策略核对 | 对照调用方源码/默认配置，核对Intent主路由即时重试及Relay兜底、统一chat原始chunk期限、Relay连接/握手/空闲/Run期限、技能、取消、存储、用例库、WeLink与标题调用 | 均为仓库默认，不是生产有效参数或下游SLA。补充WeLink首次+3次失败重试无退避，标题配置期限涵盖鉴权+HTTP；强调HTTP期限不含整文件读取、本地停止不证明远端停止 |
| 风险及追踪 | 21项活跃R/T、13个活跃W/RB、12组D；21行矩阵全部关联责任角色、措施、测试、预案及演练 | 新增R39/T39 DomainAgent、R40/T40 intentService、R41/T41 relayService；合并/移出编号仅保留去向，移出不等于整改。W10/RB10安全专项移出主清单 |
| 入口覆盖 | 45个Controller handler对应44个唯一HTTP操作，与场景索引及重新解析的OpenAPI集合一致；317个本地OpenAPI引用可解析 | WS、后台任务及前端技能查询/admin/MCP外部入口继续有归属；图和风险精简未删入口 |
| 文档引用与范围 | 659处本地链接（含230处源码行号引用）、标题/显式锚点、编号和步骤检查通过；`git diff --check`通过；变更限9份相关Markdown | 源码行号存在不替代语义核对；真实第三方内部策略保持待联合确认。历史内容作为原样前缀校验，不用当前数量覆盖旧数量 |
| Mermaid与可读性 | 12场景+7部署图全部由本地Mermaid CLI重新生成SVG，19张主图逐张查看PNG；修改的目录外架构/时序图另渲染2张 | 为长说明分行，复查复杂S02及依赖重试/旁路/部署图；最终图源与产物摘要、步骤ID存在性均校验。长图需按完整宽度或可缩放视图阅读；未上传第三方制图服务 |
| 交叉复核 | 独立只读复核数量、编号去向、R39–R41闭环及现状/目标界限，无阻断问题 | 修正事务配置期限与真实释放保证的混淆、Stop发送完成/paused语义、仅提前回调409的Retry-After、Run/WS可选live衔接及删除后清理的适用分支 |

本轮没有执行Java业务测试、压力测试、真实依赖故障注入或容灾演练，活跃T/D仍为**NOT_RUN**。19张图成功渲染只证明文档可解析和可读，不能证明高可用加固已经实现。仍待验证：真实openGauss锁等待/死锁/切换、Redis故障与恢复、下游SLA和逐跳重试/取消、混合负载及恢复放量、500MiB直传/隔离转发、JVM与容器资源上界，以及WCM/ALB/ADS/AZ/Region容灾；数值SLO/RTO/RPO和容量未冻结前不能签署上线验收。

本轮临时校验材料只用于本机复核，不是持久故障证据库：

| 本机临时证据 | 内容 |
|---|---|
| `/tmp/financeex-ha-convergence-check.py`、`/tmp/financeex-ha-convergence/check.json` | 当前文档链接/源码行、稳定编号/步骤、21行追踪、重新解析OpenAPI、历史原文前缀及改动范围检查 |
| `/tmp/financeex-ha-convergence/scenarios-render.log`、`deployment-render.log`、`architecture-render.log`、`architecture-sequence-render.log` | 最终19张主图及目录外2图的渲染记录 |
| `/tmp/financeex-ha-convergence/scenarios-{1..12}.svg`、`deployment-{1..7}.svg`及同名PNG | 最终服务级运行图和部署图；PNG用于视觉检查，Markdown/Mermaid为维护源 |
| `/tmp/financeex-ha-convergence/render-manifest.json` | 图源/产物SHA256、图中稳定步骤、产物晚于最终图源编辑的检查 |

当前路径可按以下方式重新渲染；输出目录需预先创建。本轮分支提交和普通推送只发布文档，不代表风险关闭或平台验收通过。

```sh
mmdc -i docs/architecture/high-availability/scenarios.md -o /tmp/financeex-ha-convergence/scenarios.md -e svg -j 2
mmdc -i docs/architecture/high-availability/deployment.md -o /tmp/financeex-ha-convergence/deployment.md -e svg -j 2
git diff --check
```

<a id="renumber-review-20260922"></a>
## 10. 风险与测试连续编号检查（2026-09-22）

本轮以文档提交`871ca17f70826651d898b6d89aebf2f9074c165b`为调整起点，在`codex/ha-stability-hardening`将21项活跃风险及对应测试按原内容顺序改为R01–R21、T01–T21。唯一新旧对照维护于`risks.md#risk-renumbering`；历史记录仍使用当时编号，不能按同名新编号解释。第1–9节原文作为字节不变前缀保留。

| 检查 | 本轮结果 |
|---|---|
| 连续性与对应 | 风险矩阵、21项风险明细、21项测试均按01–21顺序排列且唯一；风险与同号测试一一对应，R→W→T→RB→D关联完整 |
| 内容等价 | 按指定映射逐项对照基线：风险内容、优先级、状态、措施、测试、场景及预案语义不变；独立只读复核通过。W/RB/D/S/DEP编号序列均未改变 |
| 旧编号隔离 | 20个合并/移出的旧风险及旧测试编号使用legacy锚点并标明“旧”；去向链接指向当前编号，无重复显式锚点，不将移出项标为已整改 |
| 引用检查 | 703处本地链接（含230处源码行号引用）及Markdown锚点检查通过；目录外受控Markdown未发现需要同步的风险/测试编号引用 |
| 入口与文档数量 | 保持7份主文档、13个工作包、13份运行册、12组演练；45个Controller handler对应44个唯一HTTP操作，与场景索引及重新解析OpenAPI一致，317个本地OpenAPI引用有效 |
| 图形检查 | 12张场景图和7张部署图均重新渲染为SVG，XML解析和稳定步骤ID检查通过；19张Mermaid源与基线逐张摘要一致，编号引用变化发生在图外说明，未改变图布局 |
| 范围与格式 | 仅修改本目录7份Markdown，无新增归档；`git diff --check`通过。没有修改业务代码、配置、SQL或部署，没有执行新的业务测试或故障注入 |

本机临时校验材料位于`/tmp/financeex-ha-renumber/`：`mapping.json`记录编号及14处范围表达检查，`check.py/check.json`记录链接和结构检查，`semantic-check.json`记录内容等价，`scenarios-render.log`和`deployment-render.log`记录本轮19张图渲染，`render-manifest.json`记录图源和产物摘要。临时材料不是持久故障证据库；T/D继续保持NOT_RUN，历史业务验证结论未改写，编号整理不代表风险关闭。
