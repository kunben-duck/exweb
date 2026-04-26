package com.huawei.finance.front.one.domain.chat;

import java.util.List;
import java.util.Map;

/**
 * 聊天用例的统一输入命令。
 *
 * <p>不同传输协议的前端请求都会先转换成该命令，再进入 application 编排层。</p>
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
