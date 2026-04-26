package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.ImMessageType;
import com.huawei.finance.front.one.interfaces.chat.dto.FrontAttachmentDto;
import com.huawei.finance.front.one.interfaces.chat.dto.FrontChatRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 前端聊天协议到应用命令的翻译器。
 *
 * <p>HTTP、SSE、WebSocket 都通过这里归一化 messageType、附件和 metadata。</p>
 */
@Component
public class ChatRequestTranslator {
    public ChatCommand toCommand(FrontChatRequest request, String protocol, String tenantId, String userId) {
        ImMessageType messageType = ImMessageType.from(request.messageType());
        ChatResponseMode responseMode = ChatResponseMode.from(request.responseMode());
        Map<String, Object> metadata = normalizeMetadata(request.metadata(), protocol, messageType, responseMode, request.messageType());
        return new ChatCommand(request.commandId(), tenantId, userId, request.sessionId(), request.conversationId(), "web", protocol, messageType, responseMode, request.message(), toAttachmentRefs(request.attachments()), metadata);
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata, String protocol, ImMessageType messageType, ChatResponseMode responseMode, String originalMessageType) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (metadata != null) {
            normalized.putAll(metadata);
        }
        // 把传输协议和 IM 类型放入 metadata，便于 Runtime 或工具侧无需理解前端 DTO。
        normalized.putIfAbsent("transportProtocol", protocol);
        normalized.putIfAbsent("imMessageType", messageType.code());
        normalized.putIfAbsent("responseMode", responseMode.code());
        if (originalMessageType != null && !originalMessageType.isBlank()) {
            normalized.putIfAbsent("frontMessageType", originalMessageType);
        }
        return normalized;
    }

    private List<AttachmentRef> toAttachmentRefs(List<FrontAttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        // DTO 只在接口层存在，进入 application/domain 后统一使用 AttachmentRef。
        return attachments.stream()
                .filter(Objects::nonNull)
                .map(attachment -> new AttachmentRef(attachment.documentId(), attachment.name(), attachment.contentType(), attachment.sizeBytes()))
                .toList();
    }
}
