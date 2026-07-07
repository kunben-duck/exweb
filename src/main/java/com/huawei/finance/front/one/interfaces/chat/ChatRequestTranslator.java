package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatRunMode;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatAttachmentDto;
import com.huawei.finance.front.one.interfaces.chat.dto.CreateChatRunRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 前端聊天协议到应用命令的翻译器。
 *
 * <p>正式版只有 {@code POST /chat/runs} 一个提问入口。这里仅做 DTO 到领域命令的
 * 边界转换：身份字段保持为空，附件 DTO 转成领域引用，metadata 做防御性拷贝。</p>
 */
@Component
public class ChatRequestTranslator {
    /**
     * 将前端提问请求转换为应用层聊天命令。
     *
     * @param request 前端提问请求；身份字段不会从该对象读取。
     * @return 应用层聊天命令，tenantId/userId 保持为空并由聊天编排用入口 UserContext 回填。
     */
    public ChatCommand toCommand(CreateChatRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("创建 run 请求体不能为空");
        }
        Map<String, Object> metadata = normalizeMetadata(request.metadata());
        // 身份字段留空进入 application，由 Controller 入口解析出的 UserContext 统一回填。
        // 这样前端无法通过 Header/Query/Body 改写租户或用户，后续接入企业权限框架也只替换身份防腐层。
        return new ChatCommand(request.commandId(), null, null, request.sessionId(), request.conversationId(), "web",
                request.message(), toAttachmentRefs(request.attachments()), metadata,
                request.targetType(), request.targetId(),
                ChatRunMode.from(request.runMode()), request.parentMessageId(), request.editedMessageId(),
                request.regeneratedMessageId());
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (metadata != null) {
            normalized.putAll(metadata);
        }
        return normalized;
    }

    private List<AttachmentRef> toAttachmentRefs(List<ChatAttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        // DTO 只在接口层存在，进入 application/domain 后统一使用 AttachmentRef。
        return attachments.stream()
                .filter(Objects::nonNull)
                .map(attachment -> new AttachmentRef(
                        attachment.documentId(),
                        attachment.name(),
                        attachment.contentType(),
                        attachment.sizeBytes(),
                        attachment.tokenSize(),
                        attachment.source()
                ))
                .toList();
    }
}
