# 意图反馈与自动偏好联动

## 前后端流程
1. 原Run照常调用及订阅，意图节点展示准确/不准确。
2. 准确：POST `/v1/chat/runs/{runId}/intent-feedback`，提交`feedbackType=CORRECT`；有明确意图时携带`selectedIntent`。
3. 不准确：先调用现有`POST /v1/chat/intent-candidates`查询候选。打开或关闭面板不保存反馈。
4. 用户二选一：提交`INCORRECT_COMMENT + commentText`，或者先调用现有`switch-domain-agent`，新Run受理后提交原Run的`INCORRECT_SWITCH`。
5. 改选请求包含`replacementRunId/skillId/selectedIntent`；反馈失败只重试反馈，不重复切换或取消新Run。
6. 反馈POST返回200及反馈对象；相同规范化请求幂等返回原对象。GET同一路径有记录返回200，无记录返回204。
7. 前端在提交成功后更新本地节点，不等待WS反馈事件。刷新时历史`/messages`已装配，不需要逐条查询GET。

```json
{
  "feedbackType": "INCORRECT_SWITCH",
  "replacementRunId": "run_b",
  "skillId": "skill_b",
  "selectedIntent": {"intentId": "intent_b", "intentName": "技能B"},
  "intentAccessName": "fin"
}
```

## 反馈和偏好
- 每个Run只能首次提交，不允许修改或撤销；不同内容返回409/INTENT_FEEDBACK_ALREADY_SUBMITTED。
- `CORRECT`有明确目标，以及`INCORRECT_SWITCH`，自动保存偏好。新反馈流程不要再次调用旧偏好接口。
- `INCORRECT_COMMENT`及无单一目标的`CORRECT`（例如NO_MATCH、ROUTE_MULTI）只保存反馈，不清空已有偏好。
- 意图名称是用户偏好摘要，不伪装为可信识别结果；不查询或补齐`originalIntent`。
- 同一user消息、同一逻辑入口的不同Run反馈分别保存，偏好表只保留最后成功写入的选择。幂等重试不刷新偏好。
- 普通模式提交被评价Run的逻辑入口，未传回退服务端默认。聚合专家使用source Run保存的入口，不读取当前Session。
- 入口`fin`用于偏好分组，即使出站Intent配置为`EX_fin`也不改变分组；每次请求应提交未拼接前缀的入口。
- replacement Run校验当前用户、同Session、同user消息、REGENERATE_ASSISTANT及创建时间不早于source。Runtime可尚未完成或已拒答重路由，不要求最终agentCode等于用户偏好。
- 首次反馈和自动偏好使用独立2秒事务，任一写入失败全部回滚并返回503/INTENT_FEEDBACK_UNAVAILABLE，不影响已启动Run。

## 排队期限与资源隔离

- 反馈GET/POST共用独立`intentFeedbackExecutor`，不占用旧偏好读写队列；历史`/messages`批量反馈装配不变。
- 默认固定1个工作线程、16个排队位置，排队期限500ms。队列满、执行器拒绝或排队超时均返回`503/INTENT_FEEDBACK_UNAVAILABLE`，前端只重试反馈，不重新创建Run。
- 排队过期或客户端在排队阶段取消后，任务退出队列且不会随后进入事务。已开始执行的事务不受排队计时器中断，仍受原有2秒事务及数据库、连接池超时约束；客户端取消不保证已执行事务回滚。
- 排队期限不是整个HTTP请求的硬期限，独立线程池也不隔离共享数据库连接池。

```yaml
financeex:
  intent:
    feedback:
      worker-count: ${FINANCEEX_INTENT_FEEDBACK_WORKER_COUNT:1}
      queue-capacity: ${FINANCEEX_INTENT_FEEDBACK_QUEUE_CAPACITY:16}
      queue-wait-timeout: ${FINANCEEX_INTENT_FEEDBACK_QUEUE_WAIT_TIMEOUT:500ms}
```

三个配置均要求正值，非法值启动失败。调整反馈线程数会改变数据库并发，应结合共享连接池容量设置。
- commentText trim后必填，最多1024个Unicode码点，与候选字段互斥；无需把完整下游响应或凭据放入说明。

## 历史装配
- 消息级`intentFeedback`是当前assistant Run的评价；原`feedback`仍是回答点赞/点踩。
- 对应路由Part的`payload.intentFeedback`是该节点来源Run的评价：回放按`candidateSwitchReplay.originRunId`匹配，切换标识兜底按`sourceRunId`匹配。
- A→B→C时，C可以展示继承的A/B评价；C自己的消息级评价独立。最多继承32条/256KiB路由事件的现有边界不变，已裁剪节点不伪造。
- 每个来源Run只标注最后一个对应命中节点；没有节点时消息级反馈兜底。每个Run不细分多次重意图。
- `/messages`、消息树、版本接口在DTO层装配，不写回原Parts、正文、metadataJson、缓存payload或Event。
- 收集当前响应涉及的Run后去重，每200个ID一次轻量批量SELECT；所有批次共用2秒只读事务。空集合不查询。
- 读取失败仅省略反馈，不阻断历史。省略不等价于确认未反馈，需要时GET补查；提交仍以服务端唯一约束为准。
- no-store仍保存用户反馈，可在消息级显示；不会强制保存未持久化的业务Parts。
- 分享和分支快照不复制反馈。WS和Run Resume不增加反馈帧，断线后从历史或GET恢复。

## 部署与验证
- 先执行`src/main/resources/db/incremental-20260908-intent-feedback.sql`，初始化DDL也包含新表。
- 反馈新表以tenant/user/run唯一约束支持幂等和历史批量读取；无外键级联，不随会话删除自动清理。
- Run执行、候选切换、Intent请求及现有偏好读取不增加SQL或外部调用。
- Java测试覆盖事务拦截器及回滚模型、并发幂等、原入口隔离、轻量SQL、DTO节点归属和降级；模型测试不等同于真实openGauss并发验证。
