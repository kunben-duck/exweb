package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.List;

/**
 * ChatService 消息树节点 DTO。
 *
 * <p>该 DTO 只表达当前用户可见的 user/assistant 消息树关系，不承载隐藏 system
 * 或内部工具原始节点。</p>
 *
 * @param id 节点 ID，与 message.messageId 一致。
 * @param message 当前节点对应的历史消息 DTO。
 * @param parentMessageId 父消息 ID；根节点为空。
 * @param children 子消息 ID 列表，按会话内 nodeOrder 排序。
 */
public record ChatMessageTreeNodeDto(
        String id,
        ChatMessageDto message,
        String parentMessageId,
        List<String> children
) {}
