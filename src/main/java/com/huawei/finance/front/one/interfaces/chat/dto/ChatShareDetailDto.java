package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.List;

/**
 * 单轮问答分享详情 DTO。
 *
 * @param share 分享元数据。
 * @param question 固定快照中的父 user 问题。
 * @param answer 固定快照中的 assistant 回答。
 * @param parts 默认可见的 assistant 过程信息。
 */
public record ChatShareDetailDto(
        ChatShareDto share,
        ChatShareSnapshotMessageDto question,
        ChatShareSnapshotMessageDto answer,
        List<ChatMessagePartDto> parts
) {}
