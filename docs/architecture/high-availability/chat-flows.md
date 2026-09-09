# 聊天主链路运行视图

本文的“线程”表示执行阶段，不表示一个 Run 永久占有该线程。所有 API 的错误外壳和前端重试规则见[接口索引](interfaces.md)。

本文保留端到端总览。逐步审计依次阅读[入口到启动](run-startup-details.md)、[标题提炼](session-title-flow.md)、[路由及第三方](run-routing-details.md)、[事件和控制](run-control-details.md)。详图的步骤编号与风险表一一关联，不能以总览中合并的箭头替代实际SQL/Redis及排队成本。

## C1. POST /runs：准入、启动与首事件

```mermaid
sequenceDiagram
    participant UI as 前端
    participant Jalor as Jalor网关 参数待确认
    participant MVC as Servlet virtual handler
    participant Start as 全局 boundedElastic 启动编排
    participant Prep as 附件与记忆准备
    participant DB as 数据库 Hikari
    participant Route as 后台路由 Flux
    participant Push as Event发布
    participant TitleSvc as 标题TIO 默认关闭
    participant Redis as Redis缓存
    UI->>Jalor: POST runs
    Jalor->>MVC: 转发 进入身份权限边界
    MVC->>Start: 参数规范化 身份与Cookie快照
    Start->>Start: 用户速率及租户permit 不等待
    Start->>Start: 独立订阅后台执行流 开始等待首事件
    Start->>Prep: 可信附件和短期记忆 事务外准备
    Prep->>DB: 归属及当前路径查询
    Prep-->>Start: 不可变输入
    Start->>DB: TX开始 锁Session并重读
    Note over Start,DB: active Run及Interaction校验 按模式新建或复用消息
    Start->>DB: 保存user消息计划及Run
    DB-->>Start: 准入TX commit
    Start->>Redis: 调度Binding缓存同步 同步Run缓存
    Start-->>TitleSvc: 条件调度标题 不等待结果
    Note over TitleSvc,DB: 候选查询与标题独立TX竞争共享Hikari及Session锁
    Start->>DB: 单独初始化Execution 获取owner claim
    Start->>Route: 进入事件执行Gate
    Route->>DB: 单独持久化run.started
    DB-->>Route: 开始事件commit
    Route->>Push: 开始事件提交后处理
    Route-->>Start: 首个已提交事件
    Start-->>MVC: first persisted event 对应启动结果
    MVC-->>Jalor: runId firstSeq streamTopicId
    Jalor-->>UI: 启动响应
    Note over MVC,Route: 启动响应与后续路由可并行 不依赖HTTP保持连接
    Note over Route,Push: 路由与Runtime事件异步落库和发布
    Route-->>Start: 完成 失败或取消
    Start->>Start: doFinally释放permit
```

要点：
- `first-event-timeout=30s` 约束启动确认，不是总运行时限。客户端HTTP超时并不证明Run未创建；先查`stream-status`再决定是否重新提交，避免重复问题。
- 附件解析和记忆准备在Session锁之外，但占用本机准入许可。附件最多20个，每唯一ID当前逐个查询，DB慢时放大准入延迟。
- 锁后Session重读决定聚合专家scope，避免旧准备快照覆盖专家选择。普通模式的query、可信userMessageId与Run关联后才调用Intent。
- Run metadata、客户端user metadata、下游metadata与私有控制字段不是同一个可信层次；请求translator清理私有字段，user metadata仅随新建user INSERT保存。
- 准入事务只原子保存消息计划和Run等准入事实，Execution初始化与`run.started`在其后分别执行，不是一个总事务。后续初始化失败由独立失败/超时补偿及治理收口，不能回滚已经提交的准入。首事件提交后才放行路由；启动结果交接不代表浏览器消费。

源码：[ChatRunStartCoordinator](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunStartCoordinator.java) L57/L132/L305、[StandardRunInputPreparer](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/StandardRunInputPreparer.java)、[ChatRunAdmissionCommitService](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRunAdmissionCommitService.java) L52。

### 各模式差异

| 模式 | 消息树与输入 | 并发/前端处理 |
|---|---|---|
| NEXT | 新user，包括附件-only；新assistant沿当前leaf延伸 | 不能与同会话active Run并行；成功后订阅新topic |
| EDIT_USER | 创建user新版本，不覆盖旧分支 | 必须满足当前路径/可编辑校验；刷新展示新路径 |
| REGENERATE_ASSISTANT | 复用历史user，创建assistant sibling | 不自动stop活动Run；仍受active Run冲突保护 |
| CONTINUE_INTERACTION | 先识别可信Interaction类型及claim，再决定新user/复用assistant | 不是自由指定任意历史assistant；参见C4 |
| target DOMAIN_AGENT | 可信targetId直连，不先查Intent | 仍应传本入口intentAccessName供拒答重意图 |
| target DOMAIN_EXPERT | Relay固定专家，profile+roleName隔离runtimeSession | 正常完成/运行中stop后保持ACTIVE；明确重选替换 |
| target INTENT_EXPERT | 锁内保存父专家scope，子技能由专属Intent入口选择 | 身份改变取消旧范围ACTIVE Binding；同身份续接，名称变化只更新展示 |
| forceReroute | 取消当前固定路由后重新选择 | 不等于stop；活动Run仍返回冲突。逻辑入口由请求/专家scope确定 |

## C2. 路由、配置Gate和下游

```mermaid
sequenceDiagram
    participant Run as 后台Run线程
    participant Binding as Binding服务
    participant Signal as 路由信号
    participant Intent as Intent HTTP
    participant Gate as 专用配置IO Scheduler
    participant DB as 数据库
    participant Redis as Redis
    participant Config as 技能配置服务
    participant Agent as DomainAgent或Relay
    Run->>Run: owner fencing检查
    alt 显式直连目标
        Run->>Run: 构造可信最终Route
    else 可续接ACTIVE Binding
        Run->>Binding: 当前leaf scope profile约束解析
        Binding->>DB: 查询可路由Binding
        Binding-->>Run: 目标及runtimeSession
    else 需要识别
        Run->>Signal: 可选用例库 普通入口才使用
        Signal->>Intent: 未直连且未命中时调用Intent
        Note over Signal,Intent: 偏好和RouteMemory有界读 鉴权与网络异步阶段
        Intent-->>Signal: progress及最终单意图 澄清或无匹配
        Signal-->>Run: Route和来源
    end
    alt DomainAgent需要配置
        Run->>Gate: 留存或有附件
        Gate->>Gate: 先校验附件数量
        Gate->>Redis: 完整技能配置缓存读取
        opt 未命中或缓存读失败
            Gate->>Config: 技能配置Provider HTTP 最多一次
            Gate->>Redis: 尝试写缓存10分钟
        end
        Gate-->>Run: ALLOW 或 UNSUPPORTED_ATTACHMENT
    end
    alt 允许调用
        Run->>Binding: owner保护后创建或续接Binding
        Run->>DB: 最终route及skillId Runtime dispatch标记
        Run->>Agent: 订阅Runtime 流 取得对应64许可
        Agent-->>Run: 原始帧 标准化为ChatEvent
    else 附件拒绝
        Run->>Run: 保存候选Binding内存草稿 不启动Runtime
        Run-->>Run: progress card message.completed
        Run->>DB: completed事务激活最终Binding及终态事件
    end
```

技能配置HTTP与Runtime是不同服务调用，不是向DomainAgent聊天地址重复查询配置。共享配置服务是唯一Provider/Redis入口，留存和附件校验共用一个快照。

### 意图与上下文

- 普通路径：显式目标优先；满足范围的ACTIVE Binding跳过用例库/Intent；无Binding再走信号选择。聚合专家无子Binding时跳过用例库，使用scope入口。
- Intent阻塞/流式共用请求mapper。前端逻辑`intentAccessName`trim后仅在出站拼接`request-access-name-prefix`；未传使用服务端默认入口且不加前缀。Run/偏好分组仍保留逻辑值。
- 同次重试复用可信user messageId和偏好快照，不重复偏好数据库读取。普通Run不会自动继承上一Run入口，前端每次提交当前入口；聚合专家按可信scope恢复。
- 短期历史默认关闭；开启后DomainAgent/Relay得到历史messages及可选skillId；当前query不混入历史。Intent的domainSessionMessages仍只有role/content。
- routeSource=runtime-binding不重复追加RouteMemory。附件拒绝仅在completed提交之后补记最终Route，失败开放，仍有提交后崩溃窗口。

### 附件Gate行为

数量先于配置访问：DomainAgent默认10个，超限沿现有run.failed，不能先改Binding。支持类型来自可信文件名的最后扩展名；大小写归一。

| 结果 | 行为 |
|---|---|
| 无附件且留存关闭 | 不查技能配置 |
| attachmentType为空/未匹配技能 | 有附件则全部拒绝，含无后缀附件 |
| 合法非空配置 | 检查后缀，无后缀按既有规则放行 |
| 非空但无法解析 | 告警并放行 |
| Provider错误，仅附件校验 | 告警并放行；不能当作“确认支持” |
| Provider错误，留存也启用 | 失败关闭 |
| 类型拒绝 | 不订阅Runtime；结构化progress/card、finishReason、skillInvocationStarted=false；业务以completed结束，终态事务决定最终Binding |

源码：[ChatRuntimeDispatchCoordinator](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/ChatRuntimeDispatchCoordinator.java)、[AgentDataPersistenceGate](../../../src/main/java/com/huawei/it/ex/one/application/service/agentdatapersistence/AgentDataPersistenceGate.java)、[DomainAgentSkillConfigurationService](../../../src/main/java/com/huawei/it/ex/one/application/service/domainagentconfig/DomainAgentSkillConfigurationService.java)。

## C3. 候选直接切换

```mermaid
sequenceDiagram
    participant UI as 前端
    participant Switch as 候选独立接口 boundedElastic
    participant Stop as 现有Stop
    participant DB as 数据库
    participant Event as Event IO pipeline
    participant Runtime as B复用的Run执行链
    UI->>Switch: sourceRunId messageId skillId selectedIntent
    Switch->>DB: 归属 当前leaf 有效复用assistant 可信附件
    opt A仍活动或WAIT
        Switch->>Stop: reason CANDIDATE_SWITCH
        alt A为RUNNING或CANCELLING
            Stop->>DB: CANCELLING再本地终态
            Stop-->>UI: 发布A topic标准终态
        else A为WAITING_USER
            Stop->>DB: 取消等待Interaction及引用Binding 保留A的WAIT历史
            Note over Stop,DB: 若已有实际continuation则停止该Run
        end
        Stop-->>Switch: 停止编排结果
    end
    Switch->>DB: 重读A及活动状态 在Session锁内重验路径
    Switch->>DB: 准入TX创建B 复用user及assistant版本计划
    DB-->>Switch: 准入commit
    Switch->>Runtime: 单独初始化Execution并进入开始事件Gate
    Runtime->>Event: run.started
    Event->>DB: 开始事件事务提交
    Event-->>Switch: 首事件交接
    Switch-->>UI: B runId firstSeq streamTopicId
    Runtime->>Event: 回放A的路由历史与candidate-skill-switch
    Event->>DB: 顺序持久化 最后标识ACK
    DB-->>Event: commit及后处理完成
    Event-->>Runtime: PersistenceAcknowledgedEvent ACK
    Runtime->>Runtime: Flux.defer后才解析Binding 更新route 调用B
    UI->>Event: 订阅B topic或Resume补漏
```

接口独立不代表实现完全解耦：B复用内部REGENERATE消息计划、准入、Stop、Binding、Runtime、Event和历史版本机制。正常`/runs`没有候选回放时不创建ACK屏障。

- 已完成A可以切换，但必须仍是当前来源，不能用任意旧Run回退已经更新的路径。
- OTHER/手动模糊候选续跑应传新Run-B ID，不传初始A；assistant关联未回填时仅通过持久化Interaction验证REUSE_ASSISTANT，不能靠前端metadata绕过。
- 只复用可信user正文和附件；metadata仅本次显式值，intentAccessName不继承A。补充query继承不在当前修复范围。
- A到B到C最多32条/256KiB路由历史；按originRunId/sequence去重。不复制正文、普通卡片、拒答业务帧和终态。
- Stop完成前不创建B；仍活动返回STOP_PENDING；路径过期返回STALE_SOURCE。A已停止而B准入失败不会恢复A，前端只在确认当前路径后重试。
- `Flux.concat`本身不是持久化屏障；ACK失败/取消不放行B的路由，避免与Run锁更新竞争。

源码：[CandidateDomainAgentSwitchApplicationService](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/CandidateDomainAgentSwitchApplicationService.java)、[StandardRunRuntimeCoordinator](../../../src/main/java/com/huawei/it/ex/one/application/service/chat/StandardRunRuntimeCoordinator.java)。

## C4. Interaction与拒答

```mermaid
sequenceDiagram
    participant UI as 前端
    participant Continue as 续跑编排
    participant DB as 数据库
    participant Route as 路由 Intent
    participant Down as Runtime
    UI->>Continue: CONTINUE_INTERACTION 可信interactionId及回答
    Continue->>DB: 归属 类型 状态检查
    Continue->>Continue: 解析本次允许的附件 事务外
    Continue->>DB: claim WAITING到RESPONDING
    Continue->>DB: 准入新Run Session锁 active校验
    Note over Continue,DB: 普通澄清NEW_TURN在准入TX标记旧Interaction ANSWERED
    Continue->>DB: 准入提交后单独初始化Execution
    alt Intent澄清或AMBIGUOUS OTHER
        Continue->>Route: 可信原问题及回答 scope入口
        Route-->>Continue: 新路由或再次澄清
    else 人工候选或路由确认
        Continue->>Continue: 可信候选 Gate及Binding保护
    else Relay问卷
        Continue->>Down: 相同Profile及runtimeSession RESUME和回答
    end
    Continue->>Down: 可调用时复用原Runtime链
    Down-->>Continue: 标准事件
    Continue->>DB: 终态TX 保存原assistant或新assistant
    Note over Continue,DB: REUSE_ASSISTANT在completed或waiting终态标记旧Interaction ANSWERED
    opt 失败且满足释放条件
        Continue->>DB: 条件释放claim 可重试
    end
```

| 类型/触发 | 消息与路由差异 | 附件及前端 |
|---|---|---|
| INTENT_CLARIFICATION | 一般新建澄清user，折叠原问题后重意图 | 可显式传附件；继续传入口 |
| AMBIGUOUS_ROUTE SELECT_CANDIDATE | 从持久化候选选择，REUSE_ASSISTANT关联续跑 | 不等同候选switch接口；等待中的选择是Interaction回答 |
| AMBIGUOUS_ROUTE OTHER | 自定义回答重新意图；复用原assistant和user关联 | 返回的新runId用于后续switch/Resume |
| Relay questionnaire | approval/questionnaire卡片持久化为Interaction；续接原Relay session | 不放宽为任意附件输入；查协议允许字段 |
| ROUTE_SWITCH_CONFIRMATION批准 | 当前请求附件可信解析；ALLOW后受owner/fence保护的短TX切Binding | 不自动读取A旧附件；拒绝切换时不能带附件 |
| 确认切换附件不支持 | 延迟Binding及route-switch-applied直到completed TX | 确认受理事件不代表已经切换；stop/回滚不能留下applied成功事件 |
| DomainAgent可信拒答 | 原Binding不可路由，原上下文重意图；前端直连默认要求确认，auto-switch配置可跳过确认 | 自动替换沿用本轮可信附件；重意图不是原HTTP重试 |

直接DomainAgent当前没有通用 `agent.input_required/approval_required` 状态机实现；不能仅凭下游任意ask-user字段宣称会进入WAIT。已实现的直接DomainAgent控制主要是可信拒答和异步启动；经Relay输出的questionnaire由Relay适配器处理。

风险关联：R01/R02/R06/R07/R08/R10/R16。正常Runtime和附件拒绝是不同Binding提交边界，不应统一改为“任何失败都恢复旧Binding”。
