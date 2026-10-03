# 用户级公共实时通知

## 边界与架构

本组件仅供本服务内部调用，是轻量 best-effort 变更提示，不是通知中心或可靠消息队列。
业务依赖 `UserNotificationPublisher`；公共发布器完成校验、JSON快照及提交后调度，
`RedisUserNotificationBus` 负责按环境、租户、用户隔离的跨实例投递。既有 Run 事件总线不变。

首批仅发布自动标题更新和 DomainAgent 异步任务终态；普通 Run 的启动、完成及 WAIT 不发布用户通知。
待办到期判断、定时调度、离线记录、已读及业务权限仍由业务模块负责，本轮不提供这些功能或对外发送API。

## 业务接入

```java
notificationPublisher.publish(
    new UserNotificationRecipient(trustedTenantId, trustedUserId),
    new UserNotification("session.title.updated", Map.of("sessionId", sessionId))
);
```

- 接收用户来自可信业务记录，后台调用不依赖 HTTP ThreadLocal。
- 存在实际事务时仅登记 `afterCommit`；回滚不发送。无事务时直接异步提交。
- 通知在调用时序列化冻结，嵌套数据后续修改不改变通知；JSON编码上限16KiB，类型非空且最多128个UTF-16单元。
- 发布、序列化或队列拒绝不传播为业务失败，不在调用线程执行Redis网络操作。
- 返回不代表前端收到。通知不落库，不提供ACK、离线补发、自动发布重试或Run sequence。
- 未来可增加 `task.completed`、`todo.due` 等业务类型；业务数据应只含轻量资源标识，不能放正文、凭据或敏感详情。

## 订阅协议

连接完成已有握手和 `connect` 后，客户端发送：

```json
{"id":"req_1","type":"subscribe-user-notifications"}
```

服务端完成监听注册、收到该用户频道的 Redis 订阅确认后才响应；仅容器处于监听状态不代表该频道就绪：

```json
{"id":"req_1","type":"reply","reply":{"type":"subscribe-user-notifications"}}
```

`id` 由前端自行生成，用于匹配reply/error；`type` 固定，用户身份由可信握手确定。
客户端传入的userId、tenantId或channel不会作为通知订阅身份。
每条物理连接订阅一次，重连后重新订阅；重复订阅幂等，不占每连接8个Run topic名额。
通知订阅与Run topic独立，Run完成或退订不影响通知监听。

退订请求：

```json
{"id":"req_2","type":"unsubscribe-user-notifications"}
```

退订响应：

```json
{"id":"req_2","type":"reply","reply":{"type":"unsubscribe-user-notifications"}}
```

注册失败或容量不足返回：

```json
{"id":"req_1","type":"error","code":"USER_NOTIFICATIONS_UNAVAILABLE","message":"用户通知暂不可用，请稍后重试"}
```

失败只影响用户通知订阅，前端退避重试，不能重新创建Run。
注册失败、超时或取消会释放本次订阅；迟到的频道确认不会恢复旧订阅，也不会确认之后重试的新订阅。
这里的频道确认仅证明注册就绪，不是通知消息的送达ACK，不提供离线补发保证。

## 通知与处理

自动标题实际更新并提交后：

```json
{"type":"notification","notification":{"type":"session.title.updated","data":{"sessionId":"session_example"}}}
```

前端调用 `GET /v1/chat/sessions/{sessionId}`，只更新该会话标题，不标记已读、不调用模型。
人工重命名、版本保护跳过及保存失败不发布此通知。

DomainAgent 异步回调、Stop或Watchdog成功收口后：

```json
{"type":"notification","notification":{"type":"session.async.finished","data":{"sessionId":"session_example","runId":"run_example","status":"COMPLETED"}}}
```

`status` 为 `COMPLETED/FAILED/CANCELLED`，超时为FAILED。
异步阶段在终态事务清理metadata前识别；重复回调、CAS竞争失败及回滚不发送。
前端合并刷新已加载列表，绿点取 `hasUnread`；详情通过原Run流、Resume或历史获取业务结果。
用户通知与Run topic之间不保证到达顺序，不能直接用旧Run终态覆盖同会话新Run状态。

| 字段 | 含义 |
| --- | --- |
| 外层type | 固定notification，与message/reply/error并列 |
| notification.type | 固定业务类型；前端忽略未知类型 |
| notification.data | 各业务定义的轻量对象，不要求都有sessionId/runId |

通知不含topicId、offset、sequence或ConversationTurnStream，不进入历史Parts、分享或Event Resume。
FULL/no-store均仅推送这些控制摘要；不能依靠通知恢复未订阅时丢失的no-store业务结果。

## 恢复与容量

- 订阅成功后再查列表；查询期间收到通知，标记待刷新，防止旧查询覆盖新结果。
- 同资源查询串行合并；请求期间又收到通知，结束后再查询一次。Run事件与通知共用刷新去重。
- 重连后重新订阅并查列表校准，不新增常态轮询；404/权限失败不无限重试。继续使用已有presence保活。
- 同实例同用户共享Redis监听；最后一个连接退出时释放，监听生命周期不依赖Run是否完成。
- 应用侧注册和退订使用Bus管理的专用执行器：最多1个工作线程、5个排队位置、AbortPolicy拒绝策略，无新增配置项。4个注册许可保持不变，清理合并为单一调度链，最多一个待执行清理批次，不再为每个用户占一个公共`boundedElastic`线程等待锁。
- 通知容器的注册、移除和销毁仍使用同一生命周期锁，不锁定Run容器或连接注册表。取消立即关闭本地消费者，再按Listener实例登记清理；每批最多16项，批次结束后重新排队，让等待中的注册获得执行机会。迟到清理不删除同用户的新监听。
- 待清理集合只保存已有Listener引用并去重，不保存通知payload，也不代表全实例监听数量已有独立硬上限。单项失败不阻断其他项，不在当前清理循环无限重试；后续订阅或取消可重新触发失败项。调度拒绝保留待清理状态并告警，不在请求线程或公共线程池同步兜底。关闭时先拒绝新注册，再串行销毁容器、清除引用并关闭专用执行器；已排队注册检查关闭状态后退出并归还许可。
- Spring容器可能在底层退订失败前已删除本地映射，重复移除不一定再次执行退订。因此移除失败后保留Listener级修复标记，后续重试先通过公开接口重新注册已关闭的原Listener，再按原实例移除；两步全部成功才清除失败引用。旧Listener始终无消费者、不响应就绪ACK，也不向前端回复成功；同频道的新Listener和其他用户频道继续保留。不重启容器，不按频道整体删除，正常退订无额外调用，仅失败修复可能增加SUBSCRIBE/UNSUBSCRIBE。
- 每个新共享监听从创建开始使用同一2秒就绪期限，包含调度、等待注册锁、注册及频道确认；成功共享监听的后续连接不重新注册。超时返回失败，不宣称能强制中断底层Redis阻塞操作，滞留注册仍受4个许可约束。
- 频道确认回调不获取注册锁或执行网络等待。Servlet控制请求异步处理；WebFlux沿用命令串行处理，因此等待通知就绪时后续控制命令最多等待该就绪期限，已订阅Run的输出不等待通知注册。
- 发布和Redis消息分发复用现有有界发布执行器；Spring容器内部订阅及频道ACK不迁入上述单线程，避免注册等待自身回调。通知生命周期新增最多1个专用线程，仍共享Redis、CPU及WS资源。
- 仅用户通知容器包装任务提交：执行器拒绝派发时丢弃并记录脱敏告警，不同步执行、重试或追加队列，也不修改共享执行器的拒绝策略。异常不再打断Spring内部订阅确认，避免已收到Redis确认的注册长期持锁。
- 被丢弃的频道ACK不会被视为业务订阅成功，仍在原2秒就绪期限内失败并清理；执行器恢复后可重试或注册其他用户。此保护不覆盖网络始终不返回ACK等其他底层阻塞，不将外层期限当作Redis操作的硬超时。
- 慢Redis退订仍可能阻塞通知注册和清理，但不会使大量清理任务占满公共调度线程；底层永久阻塞和关闭期限不在本次线程隔离的保证范围内。
- Servlet出站队列正在发送或有排队消息时跳过通知；如果通知已入队，后到Run或控制消息触及条数/字节上限，则先淘汰尚未发送的通知再检查容量，不改变Run的FIFO顺序或发送任务状态。已经开始写入socket的通知不强行中断。
- WebFlux的Run/控制消息保留原有有界Sink；通知使用独立的无积压Sink，经`prefetch=1`有界合流输出到同一socket。Run积压、通知无需求或并发发送失败时丢弃通知，不与Run争用同一个生产者入口。
- WebFlux接收正常结束，或异常已转换为协议错误且socket仍可用时，进入排空状态：拒绝新输出、释放业务订阅并取消通知支路；等待已进入发送入口的操作退出后完成Run/control Sink，让既有reply、错误和Run事件按FIFO排空。通过在途计数避免完成信号与生产者竞争，不增加全局发送锁。排空从进入该状态起最多2秒，包含socket发送完成；到期按原1013过载路径关闭，不限制正常长连接时长，也不保证对端已接收。
- 外部取消、socket不可用、发送失败或Run真实溢出时仍立即取消两条输出支路，释放未发送消息，包括发送流尚未订阅时已入队的消息。已失效的网络不保证错误响应可送达；通知仍是best-effort，不增加可靠投递或离线补发。
- 通知序列化或入队失败不主动关闭原Run连接；Run自身真正溢出仍执行原有断连和Resume恢复规则。合流新增少量有界缓冲，且物理连接的带宽和发送时间仍共享，不承诺通知对Run零延迟影响。
- 实际底层socket失效仍可按现有规则断连；不承诺慢客户端下通知必达。
- 提交后实例退出、Redis故障、拥塞丢弃都可能漏通知，页面可能直到后续查询才校准。
- 日志只记录操作及异常类别，不输出通知payload。生产应关注订阅失败、发布拒绝及发送队列压力。

示例为协议说明，非生产抓包。测试中的跨实例转发使用模拟Redis，不代表已验证真实Redis Cluster故障。
