package com.huawei.it.ex.one.interfaces.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * 创建多消息固定快照分享请求。
 */
public record CreateSelectedChatShareRequest(
        String sessionId,
        List<String> messageIds,
        String title,
        Instant expiresAt
) {}
