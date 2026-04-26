package com.huawei.finance.front.one.interfaces.chat.dto;

import java.time.Instant;

public record FrontChatSessionDto(
        String sessionId,
        String tenantId,
        String userId,
        String title,
        String status,
        String channel,
        Instant createdAt,
        Instant updatedAt
) {}
