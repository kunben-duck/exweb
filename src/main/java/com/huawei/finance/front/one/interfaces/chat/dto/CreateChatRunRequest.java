package com.huawei.finance.front.one.interfaces.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端聊天请求 DTO。
 *
 * <p>该 DTO 是正式版唯一提问入口 {@code POST /v1/chat/runs} 的请求体。
 * 请求不再携带 IM 消息类型或响应模式；当前只有对话消息，文档通过 attachments
 * 作为上下文资源引用传入。身份信息统一由服务端请求入口解析，不允许前端透传。</p>
 *
 * @param commandId 前端命令标识，用于幂等和排障。
 * @param sessionId 前端聊天会话标识。
 * @param conversationId 前端对话标识，通常与 sessionId 一致或为空。
 * @param message 用户输入文本。
 * @param runMode 消息树写入模式，默认 NEXT；可选 EDIT_USER、REGENERATE_ASSISTANT、CONTINUE_INTERACTION。
 * @param parentMessageId NEXT 模式显式父节点；为空时使用会话 current leaf。
 * @param editedMessageId EDIT_USER 模式被编辑的 user 消息。
 * @param regeneratedMessageId REGENERATE_ASSISTANT 模式被重新生成的 assistant 消息。
 * @param forceReroute 前端要求本轮强制重新路由；非必填，默认 false。
 * @param interactionId CONTINUE_INTERACTION 模式续接的 Interaction 请求 ID。
 * @param approved 审批、确认或切换确认结果；澄清类可省略。
 * @param scope 授权或确认范围；澄清类默认 once。
 * @param questionnaireAnswers 澄清问题答案。
 * @param attachments 本轮关联附件列表。
 * @param targetType 显式直连目标类型；当前支持 DOMAIN_AGENT，为空时走普通路由。
 * @param targetId 显式直连目标 ID；targetType=DOMAIN_AGENT 时表示 DomainAgent ID。
 * @param selectedIntent 前端显式选择 DomainAgent 时提供的展示用意图摘要；不参与路由判断。
 * @param metadata 前端扩展元数据；DomainAgent 直连时会作为下游请求 body 透传。
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
        Boolean forceReroute,
        @Size(max = 64, message = "interactionId 长度不能超过 64")
        String interactionId,
        Boolean approved,
        @Size(max = 32, message = "scope 长度不能超过 32")
        String scope,
        @Size(max = 50, message = "questionnaireAnswers 最多允许 50 个字段")
        Map<String, Object> questionnaireAnswers,
        @Valid
        @Size(max = 20, message = "单次聊天最多引用 20 个附件")
        List<ChatAttachmentDto> attachments,
        @Size(max = 32, message = "targetType 长度不能超过 32")
        String targetType,
        @Size(max = 128, message = "targetId 长度不能超过 128")
        String targetId,
        @Valid
        ChatSelectedIntentDto selectedIntent,
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
        this(commandId, sessionId, conversationId, message, null, null, null, null, null, null, null, null, null,
                attachments, null, null, null, copyMetadata(metadata));
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
