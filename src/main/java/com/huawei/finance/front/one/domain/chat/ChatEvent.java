package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 聊天事件统一接口。
 *
 * <p>所有事件都带 runId/sessionId/type/payload，便于 WebSocket 实时订阅和 Event Resume 断点恢复
 * 使用同一套事件结构。</p>
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
     * @return 事件持久化后的恢复游标序号，由数据库事实源生成。
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
