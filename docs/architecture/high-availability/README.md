# 全接口运行视图与高可用审计

## 基线与阅读顺序

- 审计基线：`ab52f9bbef758bd4496761a6839f9c50daa20e15`，分支 `202605/fin_ex_web_v3`。
- 模型：JDK 21、Spring Boot 3.4.6、Tomcat Servlet；不是纯 WebFlux 服务。
- 范围：44 个业务 HTTP 操作、前端 WebSocket、下游连接和后台治理。网关部署前缀不计入本服务路由。
- 本轮只添加文档和执行验证，没有修改业务代码、SQL、配置或协议。
- 默认值指连接地址、身份和必要凭证已补齐，资源参数未被环境变量覆盖；默认关闭的集成不能视为已经在生产启用。

| 文档 | 内容 |
|---|---|
| [架构与运行资源](architecture.md) | 多实例部署、事实源、状态机、执行线程与连接 |
| [全接口索引](interfaces.md) | 逐项核对 44 个 HTTP 操作，关联视图、容量和风险 |
| [聊天与路由](chat-flows.md) | Run 模式、Intent、Gate、Binding、Interaction、候选切换 |
| [Run启动详图](run-startup-details.md) | E/L/P/M/A/X步骤：Jalor入口、许可、会话/附件/记忆、准入和独立启动提交 |
| [标题提炼专项](session-title-flow.md) | TT步骤：初始标题、触发/排除、完整候选查询、生成许可和条件提交 |
| [路由与依赖详图](run-routing-details.md) | RT/IT/GA步骤：Binding、用例库、Intent重试、Gate与真实Runtime订阅 |
| [事件与控制详图](run-control-details.md) | EV/IC/CS/RF/ST/AS步骤：落库、Interaction、候选回放、拒答、Stop和异步回调 |
| [终态与恢复](completion-and-recovery.md) | Stop、异步回调、Event、WS、Resume、Watchdog |
| [辅助接口](auxiliary-flows.md) | 会话、历史、搜索、候选、反馈、偏好、文档、分享 |
| [默认容量](capacity.md) | 配置上限、实际限制、作用域、释放时机和瓶颈模型 |
| [依赖与超时](dependencies.md) | 等待、重试、连接、事务、数据库和 Redis 边界 |
| [风险与加固](risks.md) | 按证据分级的风险、最小修复和验收 |
| [验证与运维](verification.md) | 命令、结果、局限、压测矩阵、探针和上线清单 |

## 先读结论

1. **没有经过压测的“单实例最大用户数/QPS/完成任务数”结论。** 租户 200 个 Run 许可、两个下游各 64 个许可、Hikari 10 个连接是不同资源，不能相加，也不能直接换算吞吐。
2. **一个会话的活动 Run 由数据库约束。** `RUNNING/CANCELLING` 互斥；异步挂起仍是 `RUNNING`，但本机流已结束并释放执行许可。`WAITING_USER` 不占该唯一索引，改由 Interaction 准入规则管理。
3. **默认 Tomcat 的 200 不是生效的请求执行线程上限。** 当前 Boot 自动配置使用虚拟线程。隔离实测 `VirtualThreadExecutor`、`maxThreads=-1`；全局 Reactor boundedElastic 同样使用虚拟线程，仍有调度并发/排队限制。
4. **前端 WS、Resume、Relay WS 是三类连接。** WS 的 8/8/128 限额不能套用于 Resume；HTTP/SSE/前端 Upgrade 共享 Tomcat 入口，Relay 则是出站 Netty 连接。
5. **数据库是恢复事实源，Redis Pub/Sub 不是可靠消息队列。** FULL 的已持久化结果可回放；no-store 业务事件丢失不可恢复；提交后崩溃窗口仍存在。
6. 优先验证和加固 Relay 无界入站缓冲、共享数据库/长回放压力、部分事务内缓存操作、下游会话级 Stop 隔离和故障时恢复流量。详见风险登记，不把所有风险泛化为“死锁”。
7. **标题旁路不等于资源无影响。** 默认关闭；启用后候选收集先查询Session、完整轻量路径及关联Run，随后才竞争8个生成许可，最后独立TX2s更新标题。HTTP期限不覆盖全部排队和数据库阶段，详见TT及R25。

主链路采用“总览 → 分阶段时序图 → 同编号风险表”阅读方式。各表列出源码、线程、数据库/Redis/外呼、时限空白及恢复方式；无确认缺陷的步骤列验收条件，不自动判为P1。入站网关统一称Jalor网关，其实际配置并未提供；出站企业鉴权Provider仍是独立概念。

## 证据口径

- **S：源码确认**，由当前实现、配置绑定、Mapper 或依赖字节码直接支持。
- **L：本地验证**，使用现有测试、真实本地线程池或隔离 Servlet/依赖实验；不等同生产负载。
- **E：环境待验证**，openGauss 锁竞争、Redis Cluster 故障、网关超时、Relay 顺序保证、部署资源与实际变量均需环境验收。
- 风险登记中的优先级表示在对应触发条件下的影响，不表示已在生产发生。容量缺少独立限制不等于无限能力。
- 时序图的 `DB commit`、`publish enqueue`、`client consume` 是不同阶段。HTTP 返回 RunId 不是任务完成；持久化 ACK 不是前端 ACK。

配置依据：[application.yml](../../../src/main/resources/application.yml)。协议依据：[OpenAPI](../../openapi/financeex-chatservice-v1.yaml)、[前端联调](../../frontend-integration.md)。本审计解释当前实现，不取代协议文档。
