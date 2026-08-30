/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.List;

/**
 * 多消息分享快照中的单条消息。
 *
 * <p>该模型独立于单轮分享的 question/answer，避免扩展多消息字段时改变旧分享的响应结构。</p>
 */
public record ChatShareSelectedMessageSnapshot(
        String messageId,
        String sessionId,
        String parentMessageId,
        Long nodeOrder,
        String role,
        String content,
        String runId,
        String metadataJson,
        List<ChatShareAttachmentSnapshot> attachments,
        List<ChatShareSnapshotPart> parts,
        Instant createdAt
) {
    public ChatShareSelectedMessageSnapshot {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
