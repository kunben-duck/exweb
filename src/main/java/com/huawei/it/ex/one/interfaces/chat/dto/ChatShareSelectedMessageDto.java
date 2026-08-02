package com.huawei.it.ex.one.interfaces.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * 多消息分享详情中的单条固定消息快照。
 */
public record ChatShareSelectedMessageDto(
        String messageId,
        String sessionId,
        String parentMessageId,
        Long nodeOrder,
        String role,
        String content,
        String runId,
        String metadataJson,
        List<ChatShareAttachmentSnapshotDto> attachments,
        List<ChatMessagePartDto> parts,
        Instant createdAt
) {}
