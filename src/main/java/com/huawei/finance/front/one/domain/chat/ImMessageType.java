package com.huawei.finance.front.one.domain.chat;

import java.util.Locale;

/**
 * 前端 IM 消息类型。
 *
 * <p>该枚举用于在 HTTP/SSE/WebSocket 协议之间统一消息类型表达。</p>
 */
public enum ImMessageType {
    /** 普通文本消息。 */
    TEXT,
    /** 图片消息。 */
    IMAGE,
    /** 文件消息。 */
    FILE,
    /** 音频消息。 */
    AUDIO,
    /** 视频消息。 */
    VIDEO,
    /** 富文本消息。 */
    RICH_TEXT,
    /** 卡片消息。 */
    CARD,
    /** 位置消息。 */
    LOCATION,
    /** 系统消息。 */
    SYSTEM,
    /** 无法识别的前端消息类型。 */
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
