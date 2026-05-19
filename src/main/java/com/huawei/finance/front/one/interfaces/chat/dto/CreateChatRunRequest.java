package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.List;
import java.util.LinkedHashMap;
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
 * @param runMode 消息树写入模式，默认 NEXT；可选 EDIT_USER、REGENERATE_ASSISTANT。
 * @param parentMessageId NEXT 模式显式父节点；为空时使用会话 current leaf。
 * @param editedMessageId EDIT_USER 模式被编辑的 user 消息。
 * @param regeneratedMessageId REGENERATE_ASSISTANT 模式被重新生成的 assistant 消息。
 * @param attachments 本轮关联附件列表。
 * @param metadata 前端扩展元数据，例如 clientMessageId、forceNewTask。
 */
public record CreateChatRunRequest(
        String commandId,
        String sessionId,
        String conversationId,
        String message,
        String runMode,
        String parentMessageId,
        String editedMessageId,
        String regeneratedMessageId,
        List<ChatAttachmentDto> attachments,
        Map<String, Object> metadata
) {
    /**
     * 兼容普通继续提问的测试/调用构造器。
     */
    public CreateChatRunRequest(String commandId, String sessionId, String conversationId, String message,
                                List<ChatAttachmentDto> attachments, Map<String, ?> metadata) {
        this(commandId, sessionId, conversationId, message, null, null, null, null, attachments,
                copyMetadata(metadata));
    }

    private static Map<String, Object> copyMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        metadata.forEach(copy::put);
        return copy;
    }
}
