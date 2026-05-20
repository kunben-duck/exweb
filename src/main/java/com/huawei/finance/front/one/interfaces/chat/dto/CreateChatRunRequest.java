package com.huawei.finance.front.one.interfaces.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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
        @Size(max = 128, message = "commandId 长度不能超过 128")
        String commandId,
        @Size(max = 64, message = "sessionId 长度不能超过 64")
        String sessionId,
        @Size(max = 64, message = "conversationId 长度不能超过 64")
        String conversationId,
        @Size(max = 20000, message = "message 长度不能超过 20000")
        String message,
        @Size(max = 32, message = "runMode 长度不能超过 32")
        String runMode,
        @Size(max = 64, message = "parentMessageId 长度不能超过 64")
        String parentMessageId,
        @Size(max = 64, message = "editedMessageId 长度不能超过 64")
        String editedMessageId,
        @Size(max = 64, message = "regeneratedMessageId 长度不能超过 64")
        String regeneratedMessageId,
        @Valid
        @Size(max = 20, message = "单次聊天最多引用 20 个附件")
        List<ChatAttachmentDto> attachments,
        @Size(max = 50, message = "metadata 最多允许 50 个字段")
        Map<String, Object> metadata
) {
    /**
     * 创建普通继续提问请求。
     *
     * <p>普通 NEXT 提问不需要携带消息树编辑字段，因此保留该构造器让调用方只传核心会话、
     * 文本、附件和元数据。</p>
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
