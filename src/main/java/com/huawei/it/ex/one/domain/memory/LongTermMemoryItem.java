package com.huawei.it.ex.one.domain.memory;

import java.time.Instant;

/**
 * 长期记忆条目。
 *
 * @param id 记忆唯一标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param memoryType 记忆类型，例如 preference、business_fact。
 * @param content 记忆内容。
 * @param confidence 记忆可信度，范围 0 到 1。
 * @param createdAt 记忆创建时间。
 */
public record LongTermMemoryItem(
        String id,
        String tenantId,
        String userId,
        String memoryType,
        String content,
        double confidence,
        Instant createdAt
) {}
