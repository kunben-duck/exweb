package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 聊天事件统一接口。
 *
 * <p>所有事件都带 runId/sessionId/type/payload，便于 SSE、NDJSON、WebSocket 共用同一套输出协议。</p>
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
     * @return 事件在本轮 run 内的序号。
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
