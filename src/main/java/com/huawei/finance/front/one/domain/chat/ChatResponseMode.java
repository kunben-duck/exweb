package com.huawei.finance.front.one.domain.chat;

import java.util.Locale;

/**
 * 聊天响应方式。
 *
 * <p>block 表示等待 Agent 完整回复后一次性返回；stream 表示通过事件流逐段返回。</p>
 */
public enum ChatResponseMode {
    /** 通过 SSE、NDJSON 或 WebSocket 逐段返回事件。 */
    STREAM,
    /** 等待下游完整回复后返回单次消息事件。 */
    BLOCK;

    public static ChatResponseMode from(String value) {
        if (value == null || value.isBlank()) {
            return BLOCK;
        }
        String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        if ("STREAMING".equals(normalized)) {
            return STREAM;
        }
        if ("BLOCKING".equals(normalized)) {
            return BLOCK;
        }
        try {
            return ChatResponseMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return BLOCK;
        }
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
