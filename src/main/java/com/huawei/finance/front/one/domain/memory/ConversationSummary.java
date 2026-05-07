package com.huawei.finance.front.one.domain.memory;

import java.time.Instant;

/**
 * 会话历史摘要。
 *
 * @param id 摘要唯一标识。
 * @param sessionId 前端聊天会话标识。
 * @param summaryText 摘要文本。
 * @param messageFromSeq 摘要覆盖的起始消息序号。
 * @param messageToSeq 摘要覆盖的结束消息序号。
 * @param createdAt 摘要创建时间。
 */
public record ConversationSummary(
        String id,
        String sessionId,
        String summaryText,
        Long messageFromSeq,
        Long messageToSeq,
        Instant createdAt
) {}
