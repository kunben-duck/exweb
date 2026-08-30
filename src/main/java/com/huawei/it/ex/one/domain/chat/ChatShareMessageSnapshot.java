/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.List;

/**
 * 分享快照中的单条消息展示信息。
 */
public record ChatShareMessageSnapshot(
        String messageId,
        String sessionId,
        String role,
        String content,
        String runId,
        String metadataJson,
        List<ChatShareAttachmentSnapshot> attachments,
        Instant createdAt
) {
    public ChatShareMessageSnapshot {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
