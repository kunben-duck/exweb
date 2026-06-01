package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Relay WebSocket 文本帧翻译器。
 *
 * <p>WebSocket 与 streamable-http 共用 {@link RelayRuntimeResponseNormalizer}，避免两个
 * adapter 对同一种下游 JSON chunk 做出不同解释。该类只保留 WebSocket 语义命名，便于排障时定位
 * “后端出站 WebSocket frame -> 标准 ChatEvent”的边界。</p>
 */
@Component
public class RelayWebSocketFrameTranslator {
    private final RelayRuntimeResponseNormalizer responseNormalizer;

    public RelayWebSocketFrameTranslator(RelayRuntimeResponseNormalizer responseNormalizer) {
        this.responseNormalizer = responseNormalizer;
    }

    /**
     * 将 Relay WebSocket 文本帧转换为标准聊天事件。
     *
     * @param runId 本轮 SuperAgent run 标识。
     * @param sessionId 前端聊天会话标识。
     * @param frame Relay WebSocket 返回的一帧文本。
     * @return 该帧对应的标准聊天事件列表；空白/heartbeat 帧返回空列表。
     */
    public List<ChatEvent> translate(String runId, String sessionId, String frame) {
        return responseNormalizer.normalize(runId, sessionId, frame);
    }
}
