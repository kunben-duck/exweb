# 本轮验证记录与证据边界

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
