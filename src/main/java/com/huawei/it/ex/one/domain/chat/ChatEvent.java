package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 聊天事件统一接口。
 *
 * <p>所有事件都带 runId/sessionId/type/payload，WebSocket 实时订阅和 Event Resume 使用同一套
 * 事件结构。数据留存策略允许部分业务事件只实时传输；这类事件具有全局 sequence，但不能从事件表恢复。</p>
 */
public interface ChatEvent {
    /**
     * @return 本轮执行追踪标识。
     */
    String runId();

    /**
     * @return 前端聊天会话标识。
     */
    String sessionId();

    /**
     * @return 数据库全局序列生成的事件顺序编号；仅持久化事件可将其作为恢复游标。
     */
    long sequence();

    /**
     * @return 事件类型，例如 run.started、message.delta。
     */
    String type();

    /**
     * @return 事件创建时间。
     */
    Instant createdAt();

    /**
     * @return 前端协议事件载荷。
     */
    Map<String, Object> payload();
}
