package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.List;
import java.util.Map;

/**
 * 前端聊天请求 DTO。
 *
 * <p>该 DTO 不再接收 tenantId/userId；身份信息统一由应用层 AuthContextProvider 解析。</p>
 *
 * @param commandId 前端命令标识，用于幂等和排障。
 * @param sessionId 前端聊天会话标识。
 * @param conversationId 前端或 IM 系统中的对话标识。
 * @param message 用户输入文本。
 * @param messageType 前端消息类型编码。
 * @param responseMode 前端期望响应模式，例如 stream 或 block。
 * @param attachments 本轮关联附件列表。
 * @param metadata 前端扩展元数据，例如 clientMessageId、forceNewTask。
 */
public record FrontChatRequest(
        String commandId,
        String sessionId,
        String conversationId,
        String message,
        String messageType,
        String responseMode,
        List<FrontAttachmentDto> attachments,
        Map<String, Object> metadata
) {}
