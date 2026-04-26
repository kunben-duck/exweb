package com.huawei.finance.front.one.domain.chat;

import java.util.Locale;

/**
 * 前端 IM 消息类型。
 *
 * <p>该枚举用于在 HTTP/SSE/WebSocket 协议之间统一消息类型表达。</p>
 */
public enum ImMessageType {
    TEXT,
    IMAGE,
    FILE,
    AUDIO,
    VIDEO,
    RICH_TEXT,
    CARD,
    LOCATION,
    SYSTEM,
    UNKNOWN;

    public static ImMessageType from(String value) {
        // 缺省按文本处理，兼容老版本前端只传 message 的请求。
        if (value == null || value.isBlank()) {
            return TEXT;
        }
        // 同时兼容 rich_text、rich-text、richText 等常见前端命名风格。
        String normalized = value.trim()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        try {
            return ImMessageType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
