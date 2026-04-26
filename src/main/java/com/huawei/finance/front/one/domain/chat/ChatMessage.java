package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;

public record ChatMessage(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String role,
        String content,
        Integer tokenCount,
        Instant createdAt
) {}
