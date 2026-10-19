# 问答Trace与Agent调用观测

## 目标与边界

本功能是独立的best-effort观测旁路，不参与路由、Binding、Interaction、Stop、计费或成功裁决。
完整问答使用服务端生成的`turn_<32位UUID十六进制>`；每次实际Agent尝试使用`obs_<32位UUID十六进制>`。
不读取或保存旧`TraceContext.traceId`，不改变原日志Trace、Relay业务报文Trace、Intent `latencyMs`和`runDurationMs`。
不新增公开接口、前端字段或WebSocket事件；观测表不保存完整问题、回答、请求响应、Cookie或密钥。

## 开关与资源

```yaml
financeex:
  observability:
    enabled: ${FINANCEEX_OBSERVABILITY_ENABLED:false}
    writer:
      worker-count: 1
      queue-capacity: 1024
      queue-wait-timeout: 1s
      transaction-timeout: 2s
```

- 总开关按实例启动生效，没有子开关或动态热切换。关闭时不生成ID、不采样时间、不运行分类器、不添加头、不写观测表、不创建观测执行器。
- 写入线程数、队列容量、两个期限必须为正；Duration必须能表示为本地纳秒期限/事务秒数。
- 专用固定线程池默认1个线程、1024个排队位置，AbortPolicy；不使用请求线程或公共线程池兜底。
- 每项写入开始前检查从提交时算起的1秒排队期限，超期或满队列直接丢弃。过期任务在出队时释放队列位置，不保证到期立即移除。
- 写入使用独立REQUIRES_NEW事务，默认2秒；不足整秒向上取整。该期限不覆盖此前排队和全部连接获取等待，也不是整个任务的硬期限。
- 不重试、不增加Outbox或补齐任务。开启后仍共享Hikari、CPU和进程内存，不能保证资源故障对业务绝对零间接影响。
- Micrometer指标：`financeex.observation.queue.size`和`financeex.observation.dropped{reason=...}`；后者区分排队过期、拒绝、数据库及采样失败等原因，不统计Agent错误率。告警不携带正文或异常文本，组件内最多每60秒输出一次。

## 代码架构与关联

| 组件 | 职责 |
| --- | --- |
| `TurnTraceContext` / `AgentObservationContext` | 不可变可信关联，通过内部请求显式传递，JSON忽略，不依赖ThreadLocal |
| `TurnTraceCoordinator` | Run启动、续跑关联、首帧/拒答采样、业务提交后的阶段快照 |
| `AgentObservationRecorder` | 实际尝试的起止、重试关系、语义结果、请求头与拒答摘要 |
| `FirstBusinessFrameClassifier` | 可替换的两级首帧识别，默认bean可由部署提供的bean覆盖 |
| `ObservationWriter` | 有界排队、事务提交后登记、独立短事务、失败隔离 |
| `MyBatisObservationRepository` / `ObservationMapper` | 快照幂等upsert，阶段防回退、最早首帧及拒答OR合并 |

首次订阅Run启动协调器即开始计时，早于Run准入和Runtime。NEXT、EDIT_USER、REGENERATE_ASSISTANT及候选立即调用各建新Trace；派生问答记录来源Run/Trace。
普通澄清、OTHER、问卷和拒答切换确认继承同一Trace，成功准入才进入下一Run阶段。根user消息不因普通澄清新增user消息而变化。
WAIT和异步等待不结束Trace；最终完成、失败或取消结束Trace。启动HTTP返回、断开或超时不能替代后台业务终态。

可信快照仅合并到既有Run metadata和Interaction request payload中的`_turnObservation`私有命名空间；异步等待沿用Run快照。
其中保存根时间、阶段、根消息、首次响应、累计拒答及本进程时钟标识等，不保存Cookie或正文。
客户端提交的同名顶层字段会被剔除；内部请求上下文有`@JsonIgnore`，下游metadata及公开Interaction上下文另有过滤。
公开消息、Parts、分享及Resume不新增观测信息。恢复从业务记录读取，不查询观测表。
合并或移除私有命名空间时使用保留合法JSON null的不可变浅复制，不修改业务字段或输入Map，仍拒绝null key。
例如问卷中的`optionalDetail:null`不会阻断Trace继承；内部观测字段继续省略未赋值项。已丢失上下文的历史Interaction不推测关联或回填，仍按Legacy规则恢复。
编辑/重生成/候选切换的来源Trace尚未在内存中取得时，写入器在后台独立事务中查询可信来源Run并校验归属；该额外读取不在Run准入线程上。

存量续跑无上下文时创建`LEGACY_CONTINUATION`；存量Stop创建`LEGACY_STOP`，统计从本次恢复入口开始，不虚构历史耗时。
存量异步回调没有可信上下文则跳过观测。未提交的续跑不能结束或接管原Trace。

### 本机上下文生命周期

- `states`只保存本机仍在执行的Run，或被Stop/异步回调临时作用域持有的状态；单纯读取业务metadata、组装metadata或登记提交快照不会隐式增加常驻记录。
- 后台执行订阅结束就释放执行持有关系，不要求本机已经提交WAIT或终态。远端Stop、执行权丢失及终态提交失败均遵循此规则，不因此虚构FAILED/CANCELLED。
- 续跑在订阅前登记一次性启动记录；`inherit()`登记状态和`finishStart()`关闭记录使用同一记录的短临界区协调。登记先完成时由结束路径释放；结束先完成时，迟到认领或附件准备不能重新登记状态，也不创建Legacy启动快照。
- 有可信Trace和`LEGACY_CONTINUATION`均要求有效启动记录。取消检查前移只是快速保护，检查后的取消由原子登记兜底；重复继承不覆盖首帧、拒答或已有临时作用域，记录消费或关闭后移除，不保留取消墓碑。
- Stop在取消执行订阅前取得作用域，保留最新Trace、首帧和拒答标记。并发操作各自释放；同步收尾任务实际结束前仍持有状态，HTTP取消不会提前清理正在执行的提交。
- 最后一个持有者退出后立即按状态实例移除；采样异常、事务回滚和写入失败不阻止清理，旧作用域不能删除后来登记的状态。
- `afterCommit`仅处理已捕获快照，不重新登记已释放的Map记录。WAIT及异步挂起结束本机执行后释放内存，后续从已有私有metadata恢复。
- 不增加TTL、清理线程或固定容量淘汰；生命周期约束不代表全实例固定容量上限，也不能终止业务操作本身的永久阻塞。审计仍为best-effort，释放内存不补齐远端尚未保存的采样。

## 调用边界

| 入口 | 记录与透传 |
| --- | --- |
| 阻塞/流式Intent | `INTENT/RECOGNIZE`，每次HTTP尝试独立记录，不添加两个新头 |
| DomainAgent问答 | `DOMAIN_AGENT/QUERY`，原HTTP请求附加两个头 |
| DomainAgent问卷回答或忽略 | `DOMAIN_AGENT/INTERACTION_RESPONSE`，原控制HTTP请求附加两个头 |
| DomainAgent Stop | `DOMAIN_AGENT/STOP`，原Stop HTTP请求附加两个头 |
| Relay问答/问卷续跑 | `RELAY/QUERY`或`INTERACTION_RESPONSE`，仅WS握手附加两个头 |
| Relay临时Stop连接 | `RELAY/STOP`，原恢复连接握手附加两个头，不另加连接 |
| Relay已有连接Stop | 独立`STOP`记录，不伪造握手、不修改Stop业务帧 |
| 异步回调 | `DOMAIN_AGENT/ASYNC_CALLBACK`，可信Run回读后开始记录处理耗时，无出站请求头 |

请求头为`X-Trace-Id: turn_...`和`X-Observation-Id: obs_...`，只由可信上下文生成。
重试生成新ID，`attempt_no`递增、`retry_of_observation_id`指向前次尝试；拒答后的新Intent为新的逻辑调用，尝试号重新从1开始。
鉴权、偏好和请求准备在实际调用边界之前，附件Gate等阻止调用时不伪造Agent尝试。调用原超时、重试条件、订阅次数和业务顺序保持不变。
明确拒答、问卷及异步受理优先于截流取消；只有明确完成事实才记COMPLETED，单纯流结束记STREAM_ENDED。
异步回调提交成功记CALLBACK_APPLIED，重复、过期或CAS未胜出记CALLBACK_IGNORED；技术异常仅归类，不保存异常文本。

## 计时与首帧

- Trace总耗时：`max(0, finished_at - started_at)`，含准备、重试、人工及异步等待；WAIT不填写结束时间。
- Observation总耗时：本次实际尝试从连接调用边界到结束，包含连接获取和握手，不包含此前准备/排队或下次重试退避。
- 同进程使用单调时钟计算耗时；跨实例恢复用可信UTC起点。UTC回拨时不会覆盖本机已采到的首帧；负时差钳制为0。
- DomainAgent默认首帧仅包括`content`产生的非空白正文，以及`runtime.card`顶层非空的`diyCardScene/contentAgent`。正文按现有`sourceType=domain-agent-content`的`message.delta`识别，不要求payload重复携带`content`。
- 两类卡片字段接受非空白字符串、非空对象或非空数组；缺失、null、空字符串/空白、空对象/数组、数值和布尔值不计入。仅判断顶层容器是否非空，不递归校验内容，不识别大小写别名或嵌套同名字段。
- DomainAgent的纯`<think>`内容、问卷、引用、单独`cardUrl`、推荐问及其他卡片不触发首帧；混合卡片只检查标准化器已保留的上述两类字段，不改变原始帧解析、字段透传或业务结果处理。异步回调复用同一规则。
- Relay和本地业务事件保持原首帧口径：非空正文delta/snapshot、业务卡片、引用，包括本地生成的Intent澄清、模糊候选及问卷回答确认等交互卡片；因此整轮Trace并非统一为“只计正文”。DomainAgent规则同时用于其Observation和Trace采样。
- 思考、工具、心跳、进度、元数据、纯终态及带`candidateSwitchReplay`标记的历史回放不计入首帧。
- Intent最终结果只计入自身Observation首帧，普通意图命中不计入Trace首帧。两级分类独立，不取各调用延迟的最小值。
- 首帧记录接收/接受事件时的采样，不代表数据库提交或前端已收到；没有可靠样本则为空，不虚构0。首帧不单独写SQL，随阶段/结束快照保存。
- 首帧一旦采集不因续跑或后续输出重置。规则调整只影响后续采样，不回填旧记录，也不重新计算已持久化的首帧；自定义`FirstBusinessFrameClassifier`仍可替换默认策略。

## 表定义

完整DDL及中文注释：[增量脚本](../src/main/resources/db/incremental-20261018-turn-observation.sql)，同步包含于[初始化DDL](../src/main/resources/db/init-20260718.sql)。
两表共50列，不修改既有业务表，不建立依赖异步写入顺序的强外键。下表“可空”表示尚未确定或未发生时允许NULL。

### fin_ex_turn_trace_t（22列）

| 字段 | SQL类型 | 可空 | 含义 |
| --- | --- | --- | --- |
| trace_id | VARCHAR(64) PK | 否 | 独立问答ID |
| tenant_id | VARCHAR(64) | 否 | 可信租户 |
| user_id | VARCHAR(64) | 否 | 可信归属用户 |
| session_id | VARCHAR(64) | 是 | ChatService会话，准入前可未知 |
| root_run_id | VARCHAR(64) | 否 | 首次启动分配的Run |
| root_user_message_id | VARCHAR(64) | 是 | 根user消息，澄清不会覆盖，准入失败可空 |
| source_run_id | VARCHAR(64) | 是 | 派生问答/存量续跑的来源Run |
| source_trace_id | VARCHAR(64) | 是 | 来源独立Trace，无法取得则空 |
| current_run_id | VARCHAR(64) | 是 | 当前阶段Run；ordinal=0仅为预分配ID |
| origin_type | VARCHAR(32) | 否 | NEXT/EDIT_USER/REGENERATE_ASSISTANT/CANDIDATE_SWITCH/LEGACY_CONTINUATION/LEGACY_STOP |
| current_run_ordinal | BIGINT | 否 | 未准入0、首Run1、成功续跑递增 |
| state_version | INTEGER | 否 | 启动0、运行1、等待2、结束3 |
| status | VARCHAR(32) | 否 | STARTED/RUNNING/WAITING_USER/ASYNC_WAITING/COMPLETED/FAILED/CANCELLED |
| is_refusal | BOOLEAN DEFAULT false | 否 | 曾记录明确Agent拒答，累计OR |
| started_at | TIMESTAMPTZ | 否 | 根启动采样UTC |
| first_business_frame_at | TIMESTAMPTZ | 是 | 最早有效业务帧采样UTC |
| first_business_frame_latency_ms | BIGINT | 是 | 从根开始至首帧的非负毫秒数 |
| finished_at | TIMESTAMPTZ | 是 | 最终结束采样UTC，等待时不结束 |
| duration_ms | BIGINT | 是 | 完整问答非负毫秒耗时 |
| error_code | VARCHAR(128) | 是 | 最终失败已有可信代码，未知为空 |
| created_at | TIMESTAMPTZ | 否 | 首次快照业务创建时间，不用入库时间补造 |
| updated_at | TIMESTAMPTZ | 否 | 最近业务采样时间 |

### fin_ex_agent_observation_t（28列）

| 字段 | SQL类型 | 可空 | 含义 |
| --- | --- | --- | --- |
| observation_id | VARCHAR(64) PK | 否 | 单次实际尝试ID |
| trace_id | VARCHAR(64) | 否 | 所属独立问答 |
| tenant_id | VARCHAR(64) | 否 | 可信租户 |
| user_id | VARCHAR(64) | 否 | 可信用户 |
| session_id | VARCHAR(64) | 否 | ChatService会话 |
| run_id | VARCHAR(64) | 否 | 本次调用关联Run |
| user_message_id | VARCHAR(64) | 是 | 本Run实际user消息，不必等于根消息 |
| parent_observation_id | VARCHAR(64) | 是 | 前序关联调用，如Intent到技能、拒答到Intent |
| retry_of_observation_id | VARCHAR(64) | 是 | 同一逻辑调用的前次尝试 |
| attempt_no | INTEGER | 否 | 从1开始的实际尝试次数 |
| agent_type | VARCHAR(32) | 否 | INTENT/DOMAIN_AGENT/RELAY |
| agent_mode | VARCHAR(32) | 是 | Relay的DELEGATE/DOMAIN_EXPERT，其他为空 |
| operation | VARCHAR(32) | 否 | RECOGNIZE/QUERY/INTERACTION_RESPONSE/STOP/ASYNC_CALLBACK |
| skill_id | VARCHAR(128) | 是 | DomainAgent技能ID或Relay最终roleName；Intent/Delegate为空 |
| runtime_session_id | VARCHAR(128) | 是 | 真实下游会话，未知为空 |
| status | VARCHAR(32) | 否 | STARTED/FINISHED |
| outcome | VARCHAR(32) | 是 | 下述调用结果，未结束时可空 |
| started_at | TIMESTAMPTZ | 否 | 实际尝试开始UTC |
| first_business_frame_at | TIMESTAMPTZ | 是 | 本尝试首帧UTC |
| first_business_frame_latency_ms | BIGINT | 是 | 本尝试开始至首帧的非负毫秒数 |
| finished_at | TIMESTAMPTZ | 是 | 本尝试结束UTC |
| duration_ms | BIGINT | 是 | 本尝试非负毫秒耗时 |
| reject_code | VARCHAR(128) | 是 | 明确拒答控制帧代码 |
| reject_reason_code | VARCHAR(128) | 是 | 拒答原因代码 |
| reject_message | TEXT | 是 | 脱敏摘要，最多1024 Unicode码点，no-store省略 |
| reject_recoverable | BOOLEAN | 是 | 下游明确声明的可恢复性，未声明为空 |
| created_at | TIMESTAMPTZ | 否 | 本尝试创建采样UTC |
| updated_at | TIMESTAMPTZ | 否 | 本尝试最近采样UTC |

outcome取值：COMPLETED、REJECTED、WAITING_USER、ASYNC_ACCEPTED、STREAM_ENDED、ERROR、TIMEOUT、CANCELLED、STOP_ACCEPTED、CALLBACK_APPLIED、CALLBACK_IGNORED。
不保存Observation的error_code/error_type/error_message，不另算错误次数/错误率。
A拒答后B成功，Trace仍为`is_refusal=true`；附件拒绝、Intent无匹配、技术异常及用户拒绝切换不算Agent拒答。
`is_refusal=false`只表示没有记录到拒答，不保证未发生丢失的观测。

索引：Trace `(tenant_id,user_id,started_at,trace_id)`；Observation `(tenant_id,user_id,trace_id,started_at,observation_id)`及`(tenant_id,user_id,run_id)`。

## 提交、一致性与失败

Run阶段/WAIT/最终状态在业务事务提交后入队；回滚、终态CAS失败不记成功。调用结束与Run提交分别记录，不能相互替代。
Trace按`(current_run_ordinal,state_version)`条件推进，相同ordinal还须匹配Run ID；首帧独立合并最早值，拒答独立OR合并。
失败准入不接管旧Trace；结束快照可补建丢失的开始记录，迟到开始不覆盖终态。Observation只允许STARTED到FINISHED。
采样、分类器、入队、序列化及数据库错误均隔离，不改写业务异常或取消规则。退出时放弃未完成观测写入，不保证落库完整性。
单实例进程崩溃可能丢失尚未写入业务metadata的采样；跨实例只能恢复已保存的事实。观测不能用于可靠账务或准确计费。

## 验证与上线

先执行DDL，再部署默认关闭版本，核实各实例配置一致后受控启用。停用时仅关闭开关，不删除业务或审计数据。
新头需DomainAgent HTTP服务及Relay握手链路接受；Intent不要求支持。没有前端切换或公开接口迁移。
本地覆盖默认关闭、真实HTTP/WS适配器的头和重试、问卷续跑、异步回调、事务同步、队列拒绝/过期、分类器失败及no-store边界。
MyBatis解析、内存仓储及本地事务模拟不等于真实openGauss的SQL执行/锁竞争，也不证明真实下游兼容性。
部署验证须补充openGauss upsert乱序/并发写入、共享连接池负载、下游握手和慢客户端故障测试。
数据保留周期、查询权限和容量由部署治理确定，本轮不新增清理任务、观测查询API、可靠补发或自动修复。
