# Agent 后端转发接口说明

本文面向下游 Agent 和联调人员，描述 **ChatService 当前实际发送** 的 HTTP/WS 请求，不是前端 API。
前端接口见 [OpenAPI](openapi/financeex-chatservice-v1.yaml)、[前端联调](frontend-integration.md)；
返回帧见 [DomainAgent](domain.md)、[Relay](relay.md) 和 [前端 WebSocket](websocket-response-examples.md)。
所有示例均为虚构数据；域名、技能、身份和超时以部署配置为准，不应直接用于生产。

## 1. 场景与标识

| 场景 / 前端入口 | 实际出站 | 下游会话与示例 |
| --- | --- | --- |
| `POST /v1/chat/runs`，Intent、用例库或显式目标命中 DomainAgent | POST chat-path，普通问答 | 使用 Binding 的 runtimeSessionId；[普通请求](#domain-query) |
| 同接口续接 ACTIVE DomainAgent Binding | 同一普通问答协议，不发送 sessionMode | 复用下游会话，新 runId/messageId 按本次可信消息；[普通请求](#domain-query) |
| `switch-domain-agent` 候选选 DomainAgent | 停止旧 Run 后普通问答 | 新 Run，复用原 user 消息；Binding 按既有匹配规则选择 |
| 可恢复拒答 `RETRY_CURRENT` | 普通问答，跳过 Intent 和用例库 | 新 Run，原 user、技能、Binding 及下游 session；[重试](#domain-retry) |
| 拒答确认 `approved=true`，不传 action | 先 Intent，再按结果调用对应 Agent | 不是新增下游提交类型；可能再次命中原技能 |
| 普通路由进入 Relay Delegate | WS config 后 user-message | NEW 或 RESUME；[Delegate](#relay-query) |
| 显式、Intent 或候选选 Relay 专家 | WS config 后 chat_expert | 最终 roleName，NEW 或 RESUME；[专家](#relay-expert) |
| `CONTINUE_INTERACTION` 回答或忽略问卷 | DomainAgent HTTP approval-response / Relay WS approval-response | 原下游 session，新 Run；[问卷](#questionnaires) |
| 活动 Run Stop（含 DomainAgent ASYNC_WAITING） | DomainAgent POST stop-path / Relay stop_all_agents | [停止](#stop) |
| 尚无活动续跑 Run 的 Ask User Stop | 先取消本地 Interaction，再后台尽力取消下游 | 不向 Agent 提交问卷答案或 ignore |
| 会话 promote / 历史读取 / 消息 channel | 无对应新增 Agent 请求 | 不修改当前 Binding，不自动在出站 body 中新增 channel |

### 标识不能互换

| 标识 | 来源与含义 |
| --- | --- |
| ChatService `sessionId` | 本地会话 ID，如 `session_demo`；前端历史、Run、Stop 入口使用它 |
| `runtimeSessionId` | Binding 的真实下游会话，如 `domain_session_demo` 或 `relay_session_demo`，可与本地 ID 不同 |
| `runId` | 本次本地执行；问卷续跑、原技能重试及候选切换会创建新 Run |
| `messageId` | 可信 user 消息 ID；问卷/拒答确认复用原消息，普通 Intent 澄清可创建新 user 消息 |
| `interactionId` | ChatService 持久化交互 ID，前端续跑使用；不是下游 request_id |
| `approval_id` / `request_id` | 下游问卷入站 approval_id 保存到 Interaction；回复时原样作为 request_id |
| DomainAgent `skillId` | 最终领域技能 ID，由可信路由/Binding 覆盖客户端 metadata |
| Relay `roleName` | 最终专家名；候选 `RE_national-cfo` 解析为 `national-cfo`，不是原 accessName |
| 原 `traceId` | 原 TraceContext；有值时放 Relay config 和问答业务帧，不与观测 ID 混用 |
| `X-Trace-Id` / `X-Observation-Id` | 观测开启且成功建立可信调用上下文时生成的 `turn_...` / `obs_...`；仅出站请求头 |

例如通用 Intent 前缀 `EX_`、专家前缀 `RE_` 时，候选查询将 `EX_RE_national-cfo` 返回为
`skillId=RE_national-cfo`。前端原值提交候选切换，后端只移除一次专家前缀。普通专家正常完成仍为
ACTIVE，后续无目标提问可直接 RESUME；non-sticky 专家完成转 RESUMABLE，下次恢复路由。
问卷等待不按正常完成释放 Binding；CANCELLED 不重新激活，跨聚合专家范围不复用。

## 2. 请求头与安全边界

以下只列适配器**显式设置**的头。Host、Content-Length/Transfer-Encoding、WS 的 Upgrade、Connection、
Sec-WebSocket-Key/Version 等由 HTTP/WS 客户端生成。网关、APM 和部署侧可能另加头，需抓包确认。

| 请求头 | DomainAgent query / 问卷 | DomainAgent Stop | Relay WS 建连 |
| --- | --- | --- | --- |
| Content-Type | `application/json` | `application/json` | 未显式设置，WS 文本帧是 JSON |
| Accept | `text/event-stream, application/x-ndjson, application/json` | 未显式设置 | 未显式设置 |
| Cookie | 本次入口可信 Cookie 快照有值时 | 同左 | 透传开关开启且快照有值时 |
| Referer | `domain-agent.referer` trim 后非空值，否则回退 trim 后 base-url；仍空则省略 | 同左 | 未显式设置 |
| X-Trace-Id | 可信观测 Scope 有值时 | 同左 | 建连时可信观测 Scope 有值时 |
| X-Observation-Id | 同一次调用的观测 ID | Stop 使用自己的观测 ID | 问答、问卷、临时 Stop 各自的观测 ID |
| Authorization / SGOV / API Key | 适配器不追加 | 不追加 | 不追加 |

Cookie 入口受 `financeex.agent-runtime.forward-cookie.enabled`（默认 true）及 `max-length`（默认8192字符）
约束，捕获后随内部请求传递，不从后台线程重新读取 HTTP ThreadLocal，不把 Cookie 自动写入业务 body。
问卷续跑和 Stop 使用**本次请求**的快照，不从原 Run 恢复凭据。客户端不得在 metadata 中放凭据。
观测默认关闭，或上下文缺失/初始化失败时不发送两个观测头；不能用客户端同名头伪造观测关联。
Intent 可参与观测，但不透传这两个新头。Relay 已建立连接上的 Stop 没有新握手，无法追加头。

条件均满足时，DomainAgent 问答头示意（仅应用层头，Cookie 为虚构值）：

```http
Content-Type: application/json
Accept: text/event-stream, application/x-ndjson, application/json
Cookie: DEMO_SESSION=example-only
Referer: https://agent.example.invalid
X-Trace-Id: turn_0123456789abcdef0123456789abcdef
X-Observation-Id: obs_0123456789abcdef0123456789abcdef
```

## 3. DomainAgent HTTP

### 地址与发送边界

配置前缀 `financeex.domain-agent`：`enabled`、`base-url`、`chat-path`（默认 `/api/chat`）、
`stop-path`（默认空）、`referer`。base-url/path 先 trim；path 以 `http://` 或 `https://` 开头时直接使用；
其他情况在连接处补齐或去除一个 `/`。配置必须来自可信部署，不接受前端指定出站 URL。
查询仍要求 enabled=true 且 base-url 非空；未启用或未配置 stop-path 时不发停止 HTTP。

<a id="domain-query"></a>
### 3.1 普通问答

`POST {chatUrl}`，body 是本轮已校验业务 metadata 的**顶层展开**，不是 `{metadata:{...}}` 包装。
标准 attachments 先进行可信解析及附件 Gate；Mapper 不把 documents 自动转换成 docList。
业务需要 docList 时应显式提供合法的 `metadata.sceneParam.docList`，不能把它当作附件权限绕过入口。

| 字段 | 类型 / 发送条件 | 取值与覆盖规则 |
| --- | --- | --- |
| runId | string，可信非空时 | 先移除 metadata 同名值，再写本次 Run ID |
| messageId | string，可信非 null 时 | 先移除 metadata 同名值，再写本 Run user 消息；正常公开入口有值 |
| skillId | string，固定写入 | 最终路由领域技能，覆盖 metadata |
| query | string，固定写入 | 本轮可信问题；重试原技能使用保存的原问题，覆盖 metadata |
| sessionId | string，固定写入 | 非空 runtimeSessionId，否则本地 sessionId；覆盖 metadata |
| messages | array，启用短期记忆时固定写入，可为空数组 | 服务端历史 `role/content/skillId`；覆盖 metadata.messages。未启用时 Mapper 不主动生成，也不专门删除同名业务扩展，前端不要自行伪造历史 |
| sceneParam.docList | array，可选 | metadata 业务字段；每项必须对象，docId 或 url 至少一个经现有文本转换后非空，其余字段保留 |
| 其他业务扩展 | JSON，可选 | 沿用入口清理后的本次 metadata；Mapper深复制保留嵌套合法 null，不扩大入口及内部请求对顶层null的原校验；移除私有观测命名空间，不自动补其他鉴权字段 |

短期记忆只发送选定路径和现有截断策略下的历史；不是数据库全部消息。历史 messages 的 skillId 来自对应
轮次保存的技能，不由本次 skillId 覆盖。DomainAgent 业务 metadata 不套用 Relay 专用递归过滤规则。

下面是一份启用记忆、显式携带 docList 的完整请求 body：

<!-- contract: domain-query -->
```json
{
  "sceneParam": {"docList": [{"docId": "doc_demo", "url": "https://files.example.invalid/demo.pdf"}]},
  "bizContext": {"contextVersion": 1, "page": {"pageId": "finance-dashboard"}},
  "runId": "run_A",
  "messageId": "msg_user_demo",
  "skillId": "skill_A",
  "query": "分析本月费用",
  "sessionId": "domain_session_demo",
  "messages": [
    {"role": "user", "content": "上月费用是多少", "skillId": "skill_history"},
    {"role": "assistant", "content": "上月费用为示例值", "skillId": "skill_history"}
  ]
}
```

不自动增加 `sessionMode`、`targetType`、`selectedIntent`、`agentMode`、`channel`、`retry` 或 `approved`。
附件 Gate 拒绝时不调用 Agent；请求上限、技能数量/类型限制见 [DomainAgent协议](domain.md)。
这里“不自动增加”不等于 Mapper 会移除任意同名业务扩展，前端仍需遵循 metadata 使用约束。

<a id="domain-retry"></a>
### 3.2 原技能重试与重新意图

`BEFORE_INTENT + refusalRecoverable=true` 时，前端用 `approved=true + interactionAction=RETRY_CURRENT`。
后端恢复原技能、原 Binding、真实下游 session 和原 user 消息，创建 Run-B；先持久化开始及确认响应，
再经 owner、Binding、附件 Gate 等既有屏障调用**普通 query**。仅使用本次显式 metadata/附件，记忆按原规则组装。
无业务扩展且短期记忆未启用的完整 body：

<!-- contract: domain-retry -->
```json
{
  "runId": "run_B",
  "messageId": "msg_user_demo",
  "skillId": "skill_A",
  "query": "分析本月费用",
  "sessionId": "domain_session_demo"
}
```

不会转成问卷答案协议，也不会自动传 `RETRY_CURRENT/retry/recoverable/approved`。
若前端不传 action、仅 approved=true，则沿用拒答重意图参数，先 Intent 再执行目标；不排除原技能。
逻辑重试/重意图共用 max-reroutes（默认3）并跨确认 Run 累计，不等于 HTTP 自动重试。

<a id="questionnaires"></a>
### 3.3 问卷回答与忽略（两种 Provider 共用答案语义）

仅可信 `approval-request + operation_type=questionnaire` 进入问卷续跑。前端提交的是 Interaction ID，
服务端从持久化 Interaction/Binding 恢复原 approval_id、技能和下游 session，不能由答案覆盖。
DomainAgent 仍 `POST {chatUrl}`，请求头同普通 query；**不重发 query、messages、附件或业务 metadata**。

| DomainAgent 字段 | 类型 / 条件 | 来源 |
| --- | --- | --- |
| type | string，必发 `approval-response` | 固定值 |
| runId / messageId | string，必发 | 续跑 Run / 原 user 消息 |
| sessionId / skillId | string，必发非空 | 原下游会话 / 可信技能 |
| request_id | string，必发非空 | 原问卷 approval_id，不是 interactionId |
| approved | boolean，必发 | 通过续跑校验的前端 approved |
| scope | string，必发 `once` | DomainAgent 固定 once；Relay 使用已校验 scope，缺失/空白回退 once |
| questionnaire_answers | object，必发 | 下表映射；不是 camelCase 的前端字段名 |

共同约束：scope 只接受 once（原校验允许大小写变体）；单选为非空白字符串，多选为非空字符串数组。
label 的 key 必须是问卷原题目全文，不必回答全部题；未列出的题表示未提交，不能推断忽略原因。

| 前端 questionnaireAnswers | approved | 下游 questionnaire_answers |
| --- | --- | --- |
| 旧 `label` 非空对象 | true | label 原值 + ignore=false；无 ignored_questions |
| 旧 `ignore=true` | false | 仅 ignore=true；不推断 USER/TIMEOUT |
| label + ignoredQuestions | true | 非空 label + ignore=false + ignored_questions |
| 仅 ignoredQuestions（label 缺失/空对象） | true | ignore=false + ignored_questions，省略 label |
| ignore=true + 全部题目 ignoredQuestions | false | ignore=true + ignored_questions，禁止 label |

新列表非空，每项**只允许**非空字符串 question/reason；question 精确匹配原题，无重复、无与 label 重叠。
reason 仅 `USER/TIMEOUT`，保持列表顺序，不重命名 question/reason。包含 TIMEOUT 时，必须已经到达该
Interaction 保存的 autoActionAt，且保存的 autoActionType=IGNORE_QUESTIONNAIRE；没有有效自动动作或
提前提交在 claim 前拒绝。过期、重复认领等校验仍独立生效。不根据当前配置重新计算截止时间。
旧请求不新增时间检查；新增字段启用前，两种下游均需确认兼容。

以下完整 body 以只有“请选择分析期间”和“请选择部署环境”两题的问卷为例。

**正常回答，兼容旧请求：**

<!-- contract: domain-answer -->
```json
{
  "type": "approval-response", "runId": "run_B", "messageId": "msg_user_demo",
  "sessionId": "domain_session_demo", "skillId": "skill_A", "request_id": "approval_demo",
  "approved": true, "scope": "once",
  "questionnaire_answers": {"label": {"请选择分析期间": "本月", "请选择部署环境": ["测试环境"]}, "ignore": false}
}
```

**整份人工忽略：**

<!-- contract: domain-ignore-user -->
```json
{
  "type": "approval-response", "runId": "run_B", "messageId": "msg_user_demo",
  "sessionId": "domain_session_demo", "skillId": "skill_A", "request_id": "approval_demo",
  "approved": false, "scope": "once",
  "questionnaire_answers": {"ignore": true, "ignored_questions": [
    {"question": "请选择分析期间", "reason": "USER"},
    {"question": "请选择部署环境", "reason": "USER"}
  ]}
}
```

**整份超时忽略：**

<!-- contract: domain-ignore-timeout -->
```json
{
  "type": "approval-response", "runId": "run_B", "messageId": "msg_user_demo",
  "sessionId": "domain_session_demo", "skillId": "skill_A", "request_id": "approval_demo",
  "approved": false, "scope": "once",
  "questionnaire_answers": {"ignore": true, "ignored_questions": [
    {"question": "请选择分析期间", "reason": "TIMEOUT"},
    {"question": "请选择部署环境", "reason": "TIMEOUT"}
  ]}
}
```

**一题人工忽略，剩余超时：**

<!-- contract: domain-ignore-mixed -->
```json
{
  "type": "approval-response", "runId": "run_B", "messageId": "msg_user_demo",
  "sessionId": "domain_session_demo", "skillId": "skill_A", "request_id": "approval_demo",
  "approved": false, "scope": "once",
  "questionnaire_answers": {"ignore": true, "ignored_questions": [
    {"question": "请选择分析期间", "reason": "USER"},
    {"question": "请选择部署环境", "reason": "TIMEOUT"}
  ]}
}
```

**部分回答，部分人工忽略：**

<!-- contract: domain-partial -->
```json
{
  "type": "approval-response", "runId": "run_B", "messageId": "msg_user_demo",
  "sessionId": "domain_session_demo", "skillId": "skill_A", "request_id": "approval_demo",
  "approved": true, "scope": "once",
  "questionnaire_answers": {"label": {"请选择分析期间": "本月"}, "ignore": false,
    "ignored_questions": [{"question": "请选择部署环境", "reason": "USER"}]}
}
```

**仅逐题忽略，未提交的题不推断：**

<!-- contract: domain-ignore-only -->
```json
{
  "type": "approval-response", "runId": "run_B", "messageId": "msg_user_demo",
  "sessionId": "domain_session_demo", "skillId": "skill_A", "request_id": "approval_demo",
  "approved": true, "scope": "once",
  "questionnaire_answers": {"ignore": false,
    "ignored_questions": [{"question": "请选择部署环境", "reason": "USER"}]}
}
```

前端超时动作不提交草稿答案：保留用户明确忽略的 USER，其余题目标记 TIMEOUT，使用整份 ignore=true。
即使部分提交协议允许有效 TIMEOUT 明细，也不能由后端推测来源或自动提交草稿。

### 3.4 接收、超时与异步

- query/问卷读原始响应流，支持 `data:`、`message:` 及裸 JSON；合法 type=heartbeat 仅保活。
- 原始 chunk 到达刷新 stream-idle-timeout（YAML 默认300s），不延长 stream-total-timeout（默认15m）。
  YAML 中未显式设置新流超时时可回退旧 timeout 环境变量，详见[配置说明](../README.md)。
- message.completed（如 endFlag）、合法问卷、run.async_running 是既有截流边界；不表示三者都业务完成。
- 此 HTTP 客户端无自动重试；问卷一旦开始提交，不能证明未送达时不会自动重发答案。
- 异步受理是 Agent → ChatService 返回 agent.async_started；回调也是 **Agent → ChatService**：
  `POST /v1/internal/domain-agent/async-tasks/callback`。不是 ChatService 新增出站 HTTP。
  回调鉴权、APPEND/REPLACE、幂等及 accepted 语义见[控制契约](domain-agent-control-contract.md)和 OpenAPI，
  不在本问答 body 中自动添加回调 URL 或凭据。旧结果查询/观测不是新的下游调用类型。

## 4. Relay WebSocket

### 地址与顺序

`financeex.agent-runtime.relay.websocket.url` trim 后，含 `{clientId}` 则替换，否则在末尾加 `/clientId`。
普通 query 的 clientId 为 runId（内部缺失时 UUID），问卷续跑为新 runId；临时 Stop 为
`<runId>-interrupt-<12位随机十六进制>`。示意 `wss://relay.example.invalid/ws/{clientId}`。

顺序固定：**Upgrade 握手 → config → 收到 session-ready → 一条业务帧 → 保活/回答流**。
只有 session-ready 放行业务发送；config 阶段 error/clear-session/session-mismatch 失败，不继续发问题。
下游返回真实 session_id 后保存为 runtimeSessionId，供以后建连 RESUME。新 WS 不等于 NEW 会话。

| config 字段 | 类型 / 条件 | 来源 |
| --- | --- | --- |
| sessionMode | string，必发 new/resume | 可信 Binding 解析；问卷与临时 Stop 固定 resume |
| sessionId | string，必发 | query NEW 用本地 ID，RESUME 用非空 runtimeSessionId，否则回退本地 ID |
| uid | string，必发 | 可信 userId，不取前端 metadata |
| traceId | string，可选 | 原 TraceContext 有值时写入，不是 turn_ 观测 ID |
| supports_incremental_recovery | boolean，固定 true | NEW/RESUME、问卷、临时 Stop 均发送；仅声明能力，不自动拉增量事件 |
| appMode | string，必发 | 可信档案；Delegate 默认 delegate，专家默认 domain_expert，可由既有配置调整 |
| roleName | string，仅专家 | 最终可信 roleName，Delegate 省略 |

<a id="relay-query"></a>
### 4.1 Delegate NEW / RESUME

两种 config 的完整示例，实际每次只发送其一：

<!-- contract: relay-delegate-new -->
```json
{"type":"config","config":{"sessionMode":"new","sessionId":"session_demo","uid":"user_demo","traceId":"request_trace_demo","supports_incremental_recovery":true,"appMode":"delegate"}}
```

<!-- contract: relay-delegate-resume -->
```json
{"type":"config","config":{"sessionMode":"resume","sessionId":"relay_session_demo","uid":"user_demo","traceId":"request_trace_demo","supports_incremental_recovery":true,"appMode":"delegate"}}
```

收到 session-ready 后发送（无记忆/扩展的最小例）：

<!-- contract: relay-query -->
```json
{"type":"user-message","content":"分析本月费用","traceId":"request_trace_demo"}
```

<a id="relay-expert"></a>
### 4.2 专家 NEW / RESUME

<!-- contract: relay-expert-new -->
```json
{"type":"config","config":{"sessionMode":"new","sessionId":"session_demo","uid":"user_demo","traceId":"request_trace_demo","supports_incremental_recovery":true,"appMode":"domain_expert","roleName":"national-cfo"}}
```

<!-- contract: relay-expert-resume -->
```json
{"type":"config","config":{"sessionMode":"resume","sessionId":"relay_session_demo","uid":"user_demo","traceId":"request_trace_demo","supports_incremental_recovery":true,"appMode":"domain_expert","roleName":"national-cfo"}}
```

对应业务帧（这里示范启用记忆及可信身份字段）：

<!-- contract: relay-expert-query -->
```json
{
  "type":"chat_expert", "roleName":"national-cfo", "content":"分析本月费用", "traceId":"request_trace_demo",
  "messages":[{"role":"user","content":"上月费用是多少","skillId":"national-cfo"},
              {"role":"assistant","content":"上月费用为示例值","skillId":"national-cfo"}],
  "metadata":{"bizContext":{"contextVersion":1},"userAccount":"demo_account","globalUserId":12345}
}
```

| 业务字段 | 条件及覆盖 |
| --- | --- |
| type / roleName | Delegate 为 user-message，无 roleName；专家为 chat_expert，roleName 与 config 一致，来自可信档案 |
| content | 当前问题，内部 null 回退空字符串 |
| messages | 仅 memoryContext.shortTermEnabled=true 时发送（可空数组），每项 role/content/可选 skillId；不从 metadata 提升 |
| traceId | 原 TraceContext 有值时发送；没有则省略 |
| metadata | 清理后加可信身份字段，非空才发送；不是顶层展开 |

Relay metadata 递归过滤：key trim/转小写后等于 traceid，或包含 cookie/token/authorization/secret/password
的字段移除；私有观测 key 移除；Map 中 null 值移除，数组顺序保留。身份上下文提供非空 userAccount 时
trim 后覆盖同名 metadata，globalUserId 非 null 时覆盖为数字；缺少可信值时此 Mapper 不补造替代值。
不把请求的 target/selectedIntent/agentMode 自动作为业务帧字段；不自动新增顶层附件数组。

### 4.3 问卷 RESUME

先按原 Binding 档案发上面的 RESUME config（Delegate 或专家），再等 session-ready。
**随后发送 approval-response，而不是 user-message/chat_expert**。问卷业务帧没有 runId、messageId、
sessionId、skillId、roleName、metadata、timestamp 或 traceId；会话/身份通过 config 关联。
request_id 使用原 approval_id。以下与 DomainAgent 六种场景逐一对应：

<!-- contract: relay-answer -->
```json
{"type":"approval-response","request_id":"approval_demo","approved":true,"scope":"once","questionnaire_answers":{"label":{"请选择分析期间":"本月","请选择部署环境":["测试环境"]},"ignore":false}}
```

<!-- contract: relay-ignore-user -->
```json
{"type":"approval-response","request_id":"approval_demo","approved":false,"scope":"once","questionnaire_answers":{"ignore":true,"ignored_questions":[{"question":"请选择分析期间","reason":"USER"},{"question":"请选择部署环境","reason":"USER"}]}}
```

<!-- contract: relay-ignore-timeout -->
```json
{"type":"approval-response","request_id":"approval_demo","approved":false,"scope":"once","questionnaire_answers":{"ignore":true,"ignored_questions":[{"question":"请选择分析期间","reason":"TIMEOUT"},{"question":"请选择部署环境","reason":"TIMEOUT"}]}}
```

<!-- contract: relay-ignore-mixed -->
```json
{"type":"approval-response","request_id":"approval_demo","approved":false,"scope":"once","questionnaire_answers":{"ignore":true,"ignored_questions":[{"question":"请选择分析期间","reason":"USER"},{"question":"请选择部署环境","reason":"TIMEOUT"}]}}
```

<!-- contract: relay-partial -->
```json
{"type":"approval-response","request_id":"approval_demo","approved":true,"scope":"once","questionnaire_answers":{"label":{"请选择分析期间":"本月"},"ignore":false,"ignored_questions":[{"question":"请选择部署环境","reason":"USER"}]}}
```

<!-- contract: relay-ignore-only -->
```json
{"type":"approval-response","request_id":"approval_demo","approved":true,"scope":"once","questionnaire_answers":{"ignore":false,"ignored_questions":[{"question":"请选择部署环境","reason":"USER"}]}}
```

旧整份忽略仍为 approved=false、questionnaire_answers 仅 `{"ignore":true}`，不新增明细。
Relay scope 使用原 responsePayload 值，缺失/空白回退 once；DomainAgent 固定 once，不能据示例假设两者均执行小写转换。

### 4.4 保活与完成边界

业务发送后按 heartbeat-interval（默认20s）发送以下文本帧，不是浏览器 WS 的保活协议：

<!-- contract: relay-heartbeat -->
```json
{"type":"heartbeat"}
```

任意入站帧刷新活跃时间；heartbeat/heartbeat-response 不作为正文。普通连接使用
heartbeat-response-timeout（默认90s）和 max-run-duration（默认30m）。session-state 为
completed/waiting_user_input/paused 可闭合连接；单独 waiting_user_input 不创建问卷 Interaction，
需要实际 approval-request；paused 不能当成正常回答成功。问卷帧截流仍进入本地 WAIT。
适配器没有自动重发问题或问卷的重试循环。

<a id="stop"></a>
## 5. Stop 请求与收尾

### 5.1 DomainAgent

`POST {stopUrl}`，只有配置了 stop-path 且 enabled=true 才发送；没有配置则直接本地收尾。
完整 body：

<!-- contract: domain-stop -->
```json
{"runId":"run_A","sessionId":"session_demo","skillId":"skill_A","reason":"USER_STOP"}
```

| 字段 | 类型 / 来源 |
| --- | --- |
| runId | string，本次被取消 Run |
| sessionId | string，**ChatService 本地会话 ID**，当前 cancel Mapper 不改用 runtimeSessionId |
| skillId | string，取消请求关联的领域技能；缺失回退空字符串 |
| reason | string，服务端取消原因；缺失回退空字符串；不是新增前端 Stop 请求字段 |

因此同一个 Run 的 query 可带 domain_session_demo，而 Stop 带 session_demo。本文说明现状，不修改这一协议。
Stop 不携带 messageId/query/messages/questionnaire_answers。仅 HTTP 2xx 按成功处理，不解析业务 body。
stop-timeout 优先，未配置时回退旧 timeout（默认120s）；可配置 `financeex.domain-agent.stop-timeout=10s`。
非2xx、网络错误、超时记录告警后继续本地取消，无自动重试。2xx 是否意味着停止计算需下游保证。

### 5.2 Relay 两种停止路径

完整控制帧只有：

<!-- contract: relay-stop -->
```json
{"type":"stop_all_agents"}
```

| 路径 | 发送与等待 |
| --- | --- |
| 本机已有该 Run 活动连接 | 原连接发送 stop_all_agents 并结束出站；发送完成 **或** 收到 session-state=paused 可结束等待。不是始终要求 paused；不新建连接、不补握手头 |
| 无本机活动连接 / 原连接已结束 | 创建临时 WS，按原 Profile 发 RESUME config；sessionId 优先 runtimeSessionId，缺失回退本地 ID。session-ready 后发 stop_all_agents，等待 paused；ACK 超时或配置后连接提前关闭会告警并结束尽力停止 |

临时专家 Stop config 可直接参考 relay-expert-resume，Delegate 参考 relay-delegate-resume；
不会发送额外问题或 approval-response。临时连接结束后关闭，不保留为新的业务连接。
opening handshake 和 config 各受 config-handshake-timeout（默认10s）保护，ACK 使用
interrupt-ack-timeout（默认5s），临时控制连接还使用 idle-timeout（默认60s）。TCP connect-timeout 默认5s。
这些期限不是一个累计5秒的整请求保证；DomainAgent stop-timeout 不作用于 Relay。

### 5.3 本地状态与断连

活动 Stop 的顺序为取得控制权 / CANCELLING → 等待下游成功、失败或超时 → 取消本机执行订阅 →
保存部分结果 / 终态 CAS → 返回实际结果。等待 HTTP 不进入数据库事务。
调用方取消 Stop 订阅后（DomainAgent 和 Relay 均适用），结束下游等待并继续后台本地收尾，
不等原等待期限；“进入收尾”不是保证数据库即时提交，也不保证 Agent 停止计算。

纯问卷等待态仍先取消本地 Interaction，再后台尽力停止下游；历史源 Run 可保持 WAITING_USER。
若已创建活动续跑 Run，则停止实际 Run，REST 返回 effectiveRunId。删除会话在提交后串行异步执行 Stop，
删除响应不等待网络；候选切换在等待 Stop 时断开，只继续旧 Run 停止，不创建替代 Run。
不因取消信号伪造下游成功，终态以原业务事务和竞争裁决为准。失败后排查或重连应查询 stream-status/Resume。

## 6. 联调与源码依据

- 实现：DomainAgentChatRequestMapper、ConfiguredDomainAgentClient、DomainAgentRuntime、
  RelayWebSocketRuntimeAdapter、RelayRuntimeWireRequestMapper、RuntimeQuestionnaireAnswerValidator、ChatRunStopCoordinator。
- 出站回归：DomainAgentChatRequestMapperTest、ConfiguredDomainAgentClientTest、RelayWebSocketRuntimeAdapterTest、
  RelayRuntimeWireRequestMapperTest；状态与恢复由各问卷、拒答、候选切换、Stop 流程测试覆盖。
- 本文不要求前端直接调 Agent。先确认全部后端实例和目标 Agent 支持新逐题忽略字段，再启用前端按钮。
- HTTP/WS 标准头、Cookie 可用性、下游超时与真实 RESUME 上下文需目标环境联调；本地测试不替代网关抓包或容量验证。
- 本轮只补文档和契约校验，不增加配置、数据库部署或新报文。
