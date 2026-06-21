package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.List;

/**
 * 单轮问答分享展示快照。
 *
 * <p>只包含分享页需要展示的业务内容：父 user 问题、assistant 回答和默认可见 parts。
 * 不包含 feedback、raw log、隐藏/debug parts 或任何鉴权凭据。</p>
 */
public record ChatShareSnapshot(
        ChatShareMessageSnapshot question,
        ChatShareMessageSnapshot answer,
        List<ChatShareSnapshotPart> parts,
        Instant createdAt
) {
    public ChatShareSnapshot {
        parts = parts == null ? List.of() : List.copyOf(parts);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
