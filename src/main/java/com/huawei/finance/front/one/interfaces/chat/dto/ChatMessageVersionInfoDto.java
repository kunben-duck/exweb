package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.List;

/**
 * 单条消息的轻量版本摘要。
 *
 * <p>该 DTO 只服务历史消息页的版本游标展示。完整 sibling 内容仍可通过 variants 接口查询。</p>
 *
 * @param role 当前消息角色。
 * @param currentMessageId 当前 active path 中的消息 ID。
 * @param currentIndex 当前版本序号，从 1 开始。
 * @param total 同父同角色候选版本总数。
 * @param variants 候选版本轻量列表，按版本序号排列。
 */
public record ChatMessageVersionInfoDto(
        String role,
        String currentMessageId,
        int currentIndex,
        int total,
        List<ChatMessageVersionItemDto> variants
) {}
