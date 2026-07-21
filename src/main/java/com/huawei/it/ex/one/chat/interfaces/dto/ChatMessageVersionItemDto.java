package com.huawei.it.ex.one.chat.interfaces.dto;

import java.time.Instant;

/**
 * 消息候选版本轻量项。
 *
 * @param messageId 候选消息 ID。
 * @param index 候选版本序号，从 1 开始。
 * @param selected 是否为当前 active path 选中的版本。
 * @param switchLeafMessageId 切换该版本时应使用的 leaf message ID。
 * @param locked 候选消息是否只读。
 * @param originType 候选消息来源类型。
 * @param editedFromMessageId 编辑 user 消息的来源消息。
 * @param regeneratedFromMessageId 重新生成 assistant 消息的来源消息。
 * @param createdAt 候选消息创建时间。
 */
public record ChatMessageVersionItemDto(
        String messageId,
        int index,
        boolean selected,
        String switchLeafMessageId,
        boolean locked,
        String originType,
        String editedFromMessageId,
        String regeneratedFromMessageId,
        Instant createdAt
) {}
