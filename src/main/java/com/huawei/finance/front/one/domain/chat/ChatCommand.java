package com.huawei.finance.front.one.domain.chat;

import java.util.List;
import java.util.Map;

/**
 * 聊天用例的统一输入命令。
 *
 * <p>不同传输协议的前端请求都会先转换成该命令，再进入 application 编排层。</p>
 *
 * @param commandId 前端或调用方生成的命令标识，用于幂等和排障。
 * @param tenantId 租户标识；进入 application 后会被服务端身份上下文覆盖。
 * @param userId 用户标识；进入 application 后会被服务端身份上下文覆盖。
 * @param sessionId 前端聊天会话标识。
 * @param conversationId 前端会话或 IM 系统中的对话标识。
 * @param channel 请求来源渠道，例如 web、im、mobile。
 * @param protocol 前端传输协议，例如 sse、ndjson、websocket。
 * @param messageType 本轮消息类型。
 * @param responseMode 前端期望的响应方式。
 * @param message 本轮用户输入文本。
 * @param attachments 本轮关联附件引用。
 * @param metadata 前端或上游传入的扩展元数据。
 */
public record ChatCommand(
        String commandId,
        String tenantId,
        String userId,
        String sessionId,
        String conversationId,
        String channel,
        String protocol,
        ImMessageType messageType,
        ChatResponseMode responseMode,
        String message,
        List<AttachmentRef> attachments,
        Map<String, Object> metadata
) {}
