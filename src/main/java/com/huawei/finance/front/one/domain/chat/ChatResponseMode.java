package com.huawei.finance.front.one.domain.chat;

import java.util.Locale;

/**
 * 聊天响应方式。
 *
 * <p>block 表示等待 Agent 完整回复后一次性返回；stream 表示通过事件流逐段返回。</p>
 */
public enum ChatResponseMode {
    STREAM,
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
