# 消息请求来源

## 口径

`fin_ex_chat_message_t.channel`记录首次创建消息的请求端侧，类型为可空`NVARCHAR2(64)`。
请求值trim后使用，缺失或空白默认`web`；保留大小写，允许自定义来源，不限制为web/mobile。
Session.channel仍是会话创建来源，Part.channel仍是展示分类，三者互不替代。

| 场景 | user | assistant |
| --- | --- | --- |
| NEXT、EDIT_USER | 新消息使用请求来源 | 新消息使用请求来源 |
| 普通Intent澄清产生新问答 | 新消息使用本次回答请求来源 | 新消息使用本次回答请求来源 |
| REGENERATE_ASSISTANT、候选切换 | 复用原消息，不覆盖 | 新版本使用本次请求来源 |
| 问卷、模糊候选确认、切换确认 | 复用原消息，不覆盖 | 复用原消息，不覆盖 |
| 异步APPEND/REPLACE、Stop固化 | 不覆盖 | 更新不覆盖；首次保存使用原Run来源 |
| 分支快照 | 复制源消息来源（含null） | 复制源消息来源（含null） |

旧消息和无可信来源的旧Run保持未知，不使用Session.channel或原user来源猜测。
新Run将可信来源放入私有metadata键`_messageChannel`，随原INSERT写入，供跨实例Stop等收尾使用。
客户端同名metadata被丢弃；新字段不作为下游业务参数、卡片或事件输出，不新增SQL往返、缓存结构或常驻上下文。

## 接口

原Run接口继续使用顶层channel；`POST /v1/chat/runs/{sourceRunId}/switch-domain-agent`新增同规则的可选channel。
历史`messages`、`messages/operations`、`messages/tree`和`messages/{messageId}/variants`共用DTO返回可空channel。
不增加消息渠道筛选，不改变权限、排序、分页、实时WebSocket、Event Resume或分享格式。

```json
{
  "messageId": "msg_new_assistant",
  "role": "assistant",
  "channel": "mobile"
}
```

## 部署

首次建表使用init-20260718.sql；已有环境由DBA手动执行
[incremental-20261107-message-channel.sql](../src/main/resources/db/incremental-20261107-message-channel.sql)。
先完成DDL，再部署新Mapper；未升级表结构时新INSERT及历史SELECT不能运行。
脚本不随应用启动执行，不回填千万级旧数据，不设DEFAULT、NOT NULL或新索引。
旧版本显式列INSERT会继续留下NULL，混合部署期间可能存在未知来源，不能视为完整端侧统计。

DDL仍需要表级排他锁，不能保证在线无阻塞。脚本先以NOWAIT尝试拿锁，失败必须ROLLBACK并停止，
不得循环重试；已拿锁期间读写仍可能受阻。维护窗口先备份，并由DBA设置适当的语句超时，
确认数据库版本、锁等待与实际执行耗时。此次本地测试仅检查SQL结构和Mapper绑定，未连接openGauss执行。
应用回退时可以保留可空列，不删除列或清除数据。

锁语义依据[openGauss LOCK说明](https://docs.opengauss.org/en/docs/7.0.0-RC3/sql_reference/lock.html)，
NOWAIT只限制获取该表锁时的等待，不是整个DDL或所有内部锁操作的硬期限。
