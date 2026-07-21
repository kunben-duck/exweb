package com.huawei.it.ex.one.chat.interfaces.dto;

import java.util.List;

/**
 * 批量软删除会话响应 DTO。
 *
 * @param deletedCount 本次成功软删除的会话数量。
 * @param items 删除后的会话快照；每个会话状态均为 {@code DELETED}。
 */
public record BatchDeleteChatSessionsDto(
        int deletedCount,
        List<ChatSessionDto> items
) {}
