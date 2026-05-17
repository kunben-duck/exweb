package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.List;
import java.util.Map;

/**
 * 前端聊天请求 DTO。
 *
 * <p>该 DTO 是正式版唯一提问入口 {@code POST /api/v1/ex/chat/runs} 的请求体。
 * 请求不再携带 IM 消息类型或响应模式；当前只有对话消息，文档通过 attachments
 * 作为上下文资源引用传入。身份信息统一由服务端请求入口解析，不允许前端透传。</p>
 *
 * @param commandId 前端命令标识，用于幂等和排障。
 * @param sessionId 前端聊天会话标识。
 * @param conversationId 前端对话标识，通常与 sessionId 一致或为空。
 * @param message 用户输入文本。
 * @param attachments 本轮关联附件列表。
 * @param metadata 前端扩展元数据，例如 clientMessageId、forceNewTask。
 */
public record FrontChatRequest(
        String commandId,
        String sessionId,
        String conversationId,
        String message,
        List<FrontAttachmentDto> attachments,
        Map<String, Object> metadata
) {}
