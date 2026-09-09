# 全接口与前后端交互索引

## 口径

核对Controller与OpenAPI：45个handler对应44个唯一HTTP操作。`POST /v1/documents`有Servlet/Reactive互斥实现，生产Servlet只计一次。WS不是第45个REST操作；Actuator管理端点另列。

公共处理：企业身份和权限检查后进入Controller；多数阻塞服务以全局boundedElastic执行，MVC异步返回。反馈、候选鉴权、Event与回调另有专用资源。参数错误通常400；身份缺失401；**资源越权沿现有协议为HTTP200、body.code=ACCESS_DENIED**，前端不能只检查response.ok。运行已受理后的错误多为Event，不再改变早已返回的启动HTTP状态。

运行视图缩写：C1/C2/C3/C4见[聊天](chat-flows.md)；T1/T2/T3/T4/T5见[终态恢复](completion-and-recovery.md)；H1/H2/H3/H4见[辅助](auxiliary-flows.md)。容量和超时分别见[矩阵](capacity.md)、[依赖](dependencies.md)。风险编号见[登记](risks.md)。

入站统一经过Jalor网关部署路由，其实际策略待验。主流程细化映射：操作01对应[启动E/L/P/M/A/X](run-startup-details.md)、[路由RT/IT/GA](run-routing-details.md)和[输出EV](run-control-details.md)；CONTINUE_INTERACTION另看IC/RF；操作02看CS，03看ST，异步回调看AS；操作06/07/08及WS复用T4/T5恢复图。标题不是新增接口，触发来自操作01中合格的NEXT/EDIT，见[TT专项](session-title-flow.md)；结果由操作11/12/13等会话读取返回。

## 44个操作逐项覆盖

| ID | 方法与本地路径 | 视图/资源与差异 | 前端成功、失败及恢复规则 | 风险 |
|---|---|---|---|---|
| 01 | `POST /v1/chat/runs` | C1-C2/C4及E到EV详图；60次/用户/分钟、200订阅/租户；准入TX10s；可选TT旁路 | 返回RunStart后订阅topic；超时先查stream-status，不盲目重复NEXT；active409 | R01 R02 R06 R07 R08 R25 |
| 02 | `POST /v1/chat/runs/{sourceRunId}/switch-domain-agent` | C3；归属及附件、Stop、Session锁、新Run、32条回放ACK | 用当前sourceRun和可信user消息；成功换B topic；409 stale/pending先刷新，不重建已成功B | R02 R05 R08 |
| 03 | `POST /v1/chat/runs/{runId}/stop` | T2；CANCELLING、下游取消、本地终态TX10s | 收到响应后仍按终态事实刷新；不假设远端一定停止；重复不覆盖终态 | R05 R14 |
| 04 | `POST /v1/chat/messages/{messageId}/feedback` | H2；普通点赞/点踩，与Intent反馈独立 | 更新消息反馈状态；可再次修改 | R02 |
| 05 | `DELETE /v1/chat/messages/{messageId}/feedback` | H2；取消普通消息反馈 | 清理展示；不能用来撤销不可变Intent反馈 | R02 |
| 06 | `GET /v1/chat/sessions/{sessionId}/events/resume` | T4；有限历史SSE，无独立配额；无分页List | afterSeq补会话历史，结束不代表新Run终态；不可等待未来回调 | R03 R17 |
| 07 | `GET /v1/chat/runs/{runId}/events/resume` | T4；历史+有界live；无WS注册限额 | 终态/WAIT/async或异常可结束；异常断开需查状态后退避重连 | R03 R04 R17 R20 |
| 08 | `GET /v1/chat/sessions/{sessionId}/stream-status` | T5；Run/Execution/Binding/Interaction及懒恢复，不是纯缓存GET | 页面刷新发现activeRun、firstSeq、async phase、selectedExpert；不紧密轮询 | R02 R13 R14 |
| 09 | `POST /v1/chat/sessions` | H1；会话INSERT | 返回会话，不创建Run；客户端超时后先检索，未提供通用幂等键 | R08 |
| 10 | `GET /v1/chat/sessions/apps` | H1；本人会话应用范围查询 | 展示筛选项，不查Intent | R11 |
| 11 | `GET /v1/chat/sessions` | H1；cursor/title；批量最后状态与首answer摘要 | 保持游标过滤绑定；lastRunSkillId=null；最后Run查询失败可空，首answer查询错误仍失败 | R11 R15 |
| 12 | `GET /v1/chat/sessions/page` | H1；keyword1到128码点，count+page TX2s（有keyword） | title旧非空参数400；超时503不回退全量；前端防抖；同最后Run返回状态/skill | R02 R11 R15 |
| 13 | `GET /v1/chat/sessions/{sessionId}` | H1；归属会话读取 | 无Run结果查询；不是主流程状态替代 | R02 |
| 14 | `POST /v1/chat/sessions/{sessionId}/read` | H1；已读水位更新 | 以已消费水位标记，不能把建立WS连接视为已读所有消息 | R02 |
| 15 | `GET /v1/chat/sessions/{sessionId}/messages` | H1；当前路径分页、Parts/附件/版本与反馈批量装配 | 当前assistant版本；intentFeedback在DTO关联，失败省略；普通LIKE/DISLIKE查询错误不降级 | R11 R17 |
| 16 | `GET /v1/chat/sessions/{sessionId}/messages/tree` | H1；全树及子数据 | 不能频繁刷新大树代替分页；无总节点/字节保护 | R11 |
| 17 | `GET /v1/chat/sessions/{sessionId}/messages/{messageId}/variants` | H1；sibling及关联数据，不装配versionInfo | 可查看A/B，展示不等于已切换当前path | R11 |
| 18 | `POST /v1/chat/sessions/{sessionId}/path` | H1；归属/未删除/消息归属检查，更新leaf；无active检查或CAS | 后续消息/候选操作基于新leaf；运行中切换有竞态，前端应避免 | R02 R08 R24 |
| 19 | `POST /v1/chat/sessions/{sessionId}/branches` | H1；复制祖先链快照、新session | 创建独立分支，Intent反馈不复制；失败可能留下部分分支，勿无限重试 | R18 |
| 20 | `PATCH /v1/chat/sessions/{sessionId}` | H1；rename TX10s，锁后最新快照 | 只改标题/人工标记；不覆盖专家scope/leaf | R02 |
| 21 | `POST /v1/chat/sessions/{sessionId}/archive` | H1；TX10s、锁后快照 | 归档与Stop不是同义词；按状态限制后续访问 | R02 |
| 22 | `POST /v1/chat/sessions/{sessionId}/restore` | H1；TX10s、锁后快照 | 恢复归档会话，不复活DELETED，不自动重启Run | R02 |
| 23 | `DELETE /v1/chat/sessions/{sessionId}` | H1；锁后DB停止计划、软删及关联处理；无显式TX期限 | DB删除先提交，再best-effort stop；不保证响应时远端已停 | R04 R14 R17 R18 R23 |
| 24 | `DELETE /v1/chat/sessions` | H1；最多100，去重、按ID稳定加锁，all-or-nothing；无显式TX期限 | 响应按请求顺序；回滚不调度缓存清理；同会话准入互斥 | R02 R04 R14 R23 |
| 25 | `POST /v1/chat/intent-candidates` | H2；8许可，1条role点查、独立auth、瞬态HTTP重试 | 返回裸数组；400/ACCESS_DENIED不查下游；429忙、504超时、502失败 | R02 R06 R10 |
| 26 | `POST /v1/chat/intent-preference-corrections` | H2；旧偏好write1+1000，原子upsert | Run受理后独立调用，成功204；失败503只重试偏好，不重建Run | R10 |
| 27 | `POST /v1/chat/runs/{runId}/intent-feedback` | H2；独立worker1/queue16/排队500ms、TX2s | 同请求幂等；不同反馈409；自动偏好与反馈原子；503只重试反馈 | R02 R10 |
| 28 | `GET /v1/chat/runs/{runId}/intent-feedback` | H2；同一feedback执行器，read TX2s | 未反馈204；主要供assistant未生成场景；历史不逐消息调用 | R02 R10 |
| 29 | `POST /v1/internal/domain-agent/async-tasks/callback` | T3；网关ACL、Servlet Filter4并发/5MiB，结果TX10s | accepted；409按Retry-After重试；413缩小；400修协议；不是前端用户操作 | R02 R04 R09 |
| 30 | `POST /v1/chat/messages/{messageId}/share` | H4；父user问题及assistant回答的单轮固定快照 | 返回share，可重复创建；不动态跟随原消息 | R18 |
| 31 | `POST /v1/chat/shares` | H4；选中最多50消息/5MiB快照 | 同一归属会话及祖先路径，按路径排序；不复制Intent反馈 | R11 R18 |
| 32 | `POST /v1/chat/messages/{messageId}/share/deliveries` | H4；单消息分享并投递，provider20并发 | 外部结果不等于投递记录原子成功；超时不盲目重发 | R06 R19 |
| 33 | `GET /v1/chat/shares/{shareId}` | H4；生命周期/租户/分享权限读取 | 默认为认证的同租户受众，非匿名公开；到期/撤销不可访问 | R18 |
| 34 | `POST /v1/chat/shares/{shareId}/deliveries` | H4；已有快照外部投递 | WeLink默认关闭；失败可能已在远端产生副作用 | R06 R19 |
| 35 | `DELETE /v1/chat/shares/{shareId}` | H4；撤销分享 | 撤销本地读取资格，不召回已发外部消息 | R18 |
| 36 | `GET /v1/chat/shares` | H4；owner分页分享列表 | 不触发外部投递 | R02 |
| 37 | `POST /v1/documents` | H3；Servlet multipart50MiB/60MiB，存储32 | 获得AVAILABLE documentId后用于Run；失败有孤儿对象可能 | R12 R21 R22 |
| 38 | `GET /v1/documents` | H3；owner分页元数据 | 不下载所有文件，不表示已解析正文 | R02 |
| 39 | `GET /v1/documents/{documentId}` | H3；owner单元数据 | metadata包含provider信息，须区分可信与可编辑部分 | R21 |
| 40 | `PATCH /v1/documents/{documentId}` | H3；名称/metadata更新 | 当前可整体替换metadata，存在providerDocument信任边界风险 | R21 |
| 41 | `DELETE /v1/documents/{documentId}` | H3；软删除记录 | 不能继续作为新附件；不是立即物理删除存储对象 | R12 |
| 42 | `GET /v1/documents/{documentId}/status` | H3；记录状态读取 | 不是轮询领域Agent任务状态 | R02 |
| 43 | `GET /v1/documents/{documentId}/preview-url` | H3；返回本服务相对下载URL，mode=BACKEND_STREAM，expiresAt=null | 非预签名URL；不支持下载的provider管理文档被拒绝 | R12 R21 |
| 44 | `GET /v1/documents/{documentId}/download` | H3；流式响应，存储getObject后继续读流 | 取消必须关闭资源；现有限流不覆盖完整传输生命周期 | R12 R21 R22 |

## 非REST入口

| 入口 | 运行视图 | 协议、资源与故障 |
|---|---|---|
| `/v1/chat/ws` | T4 | 身份与origin握手；控制消息subscribe/unsubscribe及保活；max-inbound16KiB；不是用WS提交query或stop。8/8/128是本机限制 |
| Actuator | A1/T5 | 基线只暴露默认health，DB和Redis health默认关闭；不能把health=UP当作依赖可用证明。未提供Kubernetes实际探针配置 |
| heartbeat、watchdog、lazy recovery | T5 | owner/lease数据库治理；有本机scan guard，仍需约束全部分支SQL |
| Redis listener/publisher、WS发送/idle清理 | T4/T5 | 发布队列有界，不代表接收Executor有界；见R09 |
| 标题、RouteMemory、偏好读写、识别记录 | TT专项及RT/IT步骤、H2/C2 | TT区分初始标题、完整候选读取、生成8许可及TX2s；其他旁路也各有deadline/队列，不等于完全不影响主资源 |
| 提交后删除Stop、缓存同步、补偿 | T2/T5 | JVM内任务，崩溃无法保证继续；由状态查询/Watchdog部分兜底 |

## 前端统一约束

1. 每个Run使用自己的topic、runId和已消费seq；候选切换成功后才切B，旧A版本保留。
2. 意图候选只查询不写反馈。切换B成功后提交A的INCORRECT_SWITCH反馈，失败只补反馈；不要再次切换，也不要重复写旧偏好接口。
3. FIRST_EVENT/HTTP超时属于未知结果窗口，先GET状态/历史再重试有副作用请求。Feedback的同请求幂等不能推广为NEXT或share也幂等。
4. 用户关闭页面/取消SSE不是stop。显式stop调用独立接口；异步任务是否完成以Run/历史事实判断。
5. 搜索防抖约300ms，恢复/忙错误使用指数退避和抖动；不要用无间隔轮询补偿Redis故障。
6. 前端要识别body中的ACCESS_DENIED；恢复信号游标可能小于本地最大seq，必须按事件身份去重后补缺。生产前端需单独验收，不能以仓库样例自动推定已符合。
