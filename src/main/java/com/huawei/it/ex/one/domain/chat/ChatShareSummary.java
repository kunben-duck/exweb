package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;

/**
 * 分享列表使用的轻量元数据，不包含固定快照。
 *
 * <p>列表查询不得构造完整 {@link ChatShare}，避免读取和反序列化可能达到数 MiB 的 snapshot。</p>
 */
public record ChatShareSummary(
        String id,
        String title,
        String scope,
        String visibility,
        String status,
        Instant expiresAt,
        String sourceSessionId,
        String sourceUserMessageId,
        String sourceAssistantMessageId,
        String sourceRunId,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * 从完整分享记录提取列表元数据，供内存仓储和测试替身复用。
     *
     * @param share 完整分享记录。
     * @return 不包含快照的分享摘要。
     */
    public static ChatShareSummary from(ChatShare share) {
        if (share == null) {
            throw new IllegalArgumentException("share 不能为空");
        }
        return new ChatShareSummary(
                share.id(),
                share.title(),
                share.scope(),
                share.visibility(),
                share.status(),
                share.expiresAt(),
                share.sourceSessionId(),
                share.sourceUserMessageId(),
                share.sourceAssistantMessageId(),
                share.sourceRunId(),
                share.createdAt(),
                share.updatedAt()
        );
    }
}
