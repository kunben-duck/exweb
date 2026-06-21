package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.List;
import java.util.Map;

/**
 * ChatService 消息树响应 DTO。
 *
 * <p>普通聊天页仍可使用 active path 的 {@code /messages}；该 DTO 用于复杂前端、
 * 调试台或版本树视图读取完整可见消息树。</p>
 *
 * @param sessionId 会话 ID。
 * @param currentLeafMessageId 当前激活路径叶子消息 ID。
 * @param rootMessageIds 根消息 ID 列表，通常是用户第一条消息。
 * @param mapping messageId 到树节点的映射。
 */
public record ChatMessageTreeDto(
        String sessionId,
        String currentLeafMessageId,
        List<String> rootMessageIds,
        Map<String, ChatMessageTreeNodeDto> mapping
) {}
