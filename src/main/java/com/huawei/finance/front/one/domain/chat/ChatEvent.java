package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 聊天事件统一接口。
 *
 * <p>所有事件都带 runId/sessionId/type/payload，便于 SSE、NDJSON、WebSocket 共用同一套输出协议。</p>
 */
public interface ChatEvent {
    String runId();
    String sessionId();
    long sequence();
    String type();
    Instant createdAt();
    Map<String, Object> payload();
}
