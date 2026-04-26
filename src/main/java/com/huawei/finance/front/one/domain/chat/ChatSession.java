package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;

public record ChatSession(
        String id,
        String tenantId,
        String userId,
        String title,
        String status,
        String channel,
        Instant createdAt,
        Instant updatedAt
) {}
