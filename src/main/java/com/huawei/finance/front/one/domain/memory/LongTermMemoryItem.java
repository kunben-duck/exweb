package com.huawei.finance.front.one.domain.memory;

import java.time.Instant;

public record LongTermMemoryItem(String id, String tenantId, String userId, String memoryType, String content, double confidence, Instant createdAt) {}
