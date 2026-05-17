package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatAttachmentDto;
import com.huawei.finance.front.one.interfaces.chat.dto.CreateChatRunRequest;
import com.huawei.finance.front.one.interfaces.chat.dto.RetryChatRunRequest;
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
        Map<String, Object> metadata = normalizeMetadata(request.metadata());
        // 身份字段留空进入 application，由 Controller 入口解析出的 UserContext 统一回填。
        // 这样前端无法通过 Header/Query/Body 改写租户或用户，后续接入企业权限框架也只替换身份防腐层。
        return new ChatCommand(request.commandId(), null, null, request.sessionId(), request.conversationId(), "web",
                request.message(), toAttachmentRefs(request.attachments()), metadata);
    }

    /**
     * 将 retry 请求转换成应用命令。
     *
     * <p>retry 不接收 sessionId，原会话由服务端根据 runId 回查，避免前端把一个 run 重试到
     * 另一个不相关会话中。</p>
     *
     * @param request 前端重试请求，可为空。
     * @return 应用层聊天命令，sessionId 为空并由 retry 服务根据 runId 回填。
     */
    public ChatCommand toRetryCommand(RetryChatRunRequest request) {
        RetryChatRunRequest safeRequest = request == null
                ? new RetryChatRunRequest(null, null, null, List.of(), Map.of())
                : request;
        return new ChatCommand(safeRequest.commandId(), null, null, null, safeRequest.conversationId(), "web",
                safeRequest.message(), toAttachmentRefs(safeRequest.attachments()), normalizeMetadata(safeRequest.metadata()));
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
