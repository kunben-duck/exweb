# 当前验证状态与验收边界

源码基线：`8f48d6cc084be91bcbaad90be43dac7181636cb4`；文档检查日期：2026-09-22（Asia/Shanghai）。本页只维护当前方案的验证状态。当前架构以[部署现状与目标](deployment.md#dep01)为准，风险和测试使用R01–R22、T01–T22。

## 1. 当前检查结果

| 检查 | 结果 | 证明范围 |
|---|---|---|
| 文档结构 | 7份主文档，12个场景、19张主图、22项风险及测试、13个工作包、13份运行册、12组演练 | 当前范围和编号完整，不代表措施已实施 |
| 风险追踪 | R01–R22与T01–T22连续、唯一、一一对应；R→W→T→RB→D关联完整 | 每项风险具备措施、责任角色和验证入口，实际责任人及关闭证据仍须补齐 |
| 功能入口 | 45个Controller handler对应44个唯一HTTP操作，与场景索引及OpenAPI集合一致；317个本地OpenAPI引用有效 | WS、后台任务和外部入口另按场景登记；不代表真实网关联调通过 |
| 文档与源码链接 | 当前本地文件、标题、显式锚点及源码行号范围检查通过，无悬空引用 | 行号存在不代替代码语义验证，外部服务内部策略须联合确认 |
| Mermaid | 12张场景图与7张部署图、目录外3张修改图重新渲染为SVG，语法和步骤ID检查通过 | 场景图保留当前保护及缺口，待实施措施另列；不代表依赖故障和容灾测试通过 |
| 依赖静态行为 | 本地Spring Data Redis/Core默认接收执行器、Reactor同步发射调用路径已核对，结论见R04/R18 | 不证明生产线程增长、死锁或压力条件已复现，仍需T04/T18实验 |
| EDM链路核对 | api-store适配器实际调用agentService、后者向EDM分片上传为U；Chat整读/HTTP30s及托管内容不可经Chat下载为S；目标为前端经agent授权直传EDM | agent/EDM内部期限、分片/重试/取消和浏览器直传合同为E；T06/D06未执行，不以当前下游分片证明Chat有界 |
| WS源码核对 | 单用户本机连接/订阅限制、Servlet发送队列与线程拒绝、控制帧异步处理、注册表扫描和历史补读证据已核对，见R22 | 仅确认风险条件；T22与D05尚未执行，不声称已复现OOM或死锁 |
| 改动范围 | 仅Markdown文档，`git diff --check`通过 | 未修改业务代码、配置、SQL或部署 |

## 2. 测试与演练状态

- [T01–T22测试规格](tests.md)和[D01–D12演练](operations.md)均为 **NOT_RUN**；没有新的业务测试、混合压测、生产故障注入或容灾验收结果。
- 当前文档检查不关闭风险。风险仍按实际适用条件保持OPEN/ENV；源码中的保护、默认期限和本地静态结论不等于生产容量或SLA保证。
- 正式执行前必须提供有效配置、测试数据和负载、资源上限、期限、注入及撤销步骤、责任人和停止条件；缺少前置条件的用例标BLOCKED。

## 3. 待验证事项

| 验证方向 | 必须取得的证据 |
|---|---|
| 共享DB与Redis | 真实openGauss锁等待/死锁/回滚与连接释放、池耗尽及切换；Redis实际拓扑、重订阅、回源及恢复放量；生产Cluster不能由standalone结果替代 |
| 下游服务 | agentService统一chat/MCP及技能查询、DomainAgent、intentService、relayService的逐跳期限、重试总量、降级容量、取消传播及未知任务查询；双方SLA与责任边界签认 |
| 前端WS | R22/T22第一轮验证实例/租户连接、握手/控制/订阅前置校验限额、累计缓冲、慢消费、跨实例与集中重连；正常聊天/Stop/治理可用且故障后资源回落，不能由4C4G规格直接给出连接容量 |
| 进程与资源 | 实际实例规格下的CPU、堆/direct/native、GC、线程、FD、临时盘、队列和连接峰值；取消及故障恢复后真实资源回落，正常聊天和Stop仍可用 |
| 文档与旁路 | 500MiB前端直传EDM、agent授权/完成核验/登记、分片重试与合并未知、取消清理；现状转发及经验证worker另验，EDM不可用不得自动回流Chat；长历史查询、恢复风暴及旁路混合负载对主链路的影响 |
| 部署与容灾 | WCM备用源的完整浏览器业务、ALB/saas gateway长连接与摘流、ADS独立性、单AZ剩余容量、Region单写接管/复制恢复点/遗留任务收口/回切 |
| 上线目标 | SLO、服务及任务RTO、数据及附件RPO、容量和告警阈值；未签认前不能宣布上线验收通过 |

## 4. 检查方法与证据要求

从当前Controller和OpenAPI重新提取操作集合，对照44项入口索引；检查Markdown本地链接、源码行号范围、锚点唯一性、编号顺序和风险追踪。使用本地Mermaid CLI渲染当前图源，不修改工程依赖。

在已安装`mmdc`的环境中，可从仓库根目录执行：

```sh
mkdir -p /tmp/financeex-ha-edm
mmdc -i docs/architecture/high-availability/scenarios.md -o /tmp/financeex-ha-edm/scenarios.md -e svg -j 2
mmdc -i docs/architecture/high-availability/deployment.md -o /tmp/financeex-ha-edm/deployment.md -e svg -j 2
git diff --check
```

本机当前检查产物位于`/tmp/financeex-ha-edm/`，包括`check.json`、渲染日志、SVG和`render-manifest.json`；临时文件不保证跨机器存在。正式验收应保存实现版本、有效参数、双方契约、原始日志/指标、资源与业务断言、恢复结果及具名复核；只有文档通过检查不能作为验收结论。
