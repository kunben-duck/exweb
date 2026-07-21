package com.huawei.it.ex.one.chat.infrastructure.persistence;

/**
 * 事件写入栅栏从数据库返回的可信归属上下文。
 */
public record ChatEventAppendContextRow(
        String tenantId,
        String userId,
        String sessionId,
        String runId
) {
}
