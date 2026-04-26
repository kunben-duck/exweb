package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.List;
import java.util.Map;

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
