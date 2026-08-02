package com.huawei.it.ex.one.interfaces.chat;

import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatAttachmentDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatSelectedIntentDto;
import com.huawei.it.ex.one.interfaces.chat.dto.CreateChatRunRequest;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 前端聊天协议到应用命令的翻译器。
 *
 * <p>正式版只有 {@code POST /chat/runs} 一个提问入口。这里仅做 DTO 到领域命令的
 * 边界转换：身份字段保持为空，附件 DTO 转成领域引用，metadata 做防御性拷贝。</p>
 */
@Component
public class ChatRequestTranslator {
    private static final String USER_CORRECTION = "user_correction";

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
        ChatRunMode runMode = ChatRunMode.from(request.runMode());
        if (Boolean.TRUE.equals(request.forceReroute()) && runMode == ChatRunMode.CONTINUE_INTERACTION) {
            throw new IllegalArgumentException("CONTINUE_INTERACTION 模式不支持 forceReroute");
        }
        if (Boolean.TRUE.equals(request.forceReroute()) && (hasText(request.targetType()) || hasText(request.targetId()))) {
            throw new IllegalArgumentException("forceReroute=true 时不能同时指定 targetType/targetId");
        }
        Map<String, Object> metadata = normalizeMetadata(request.metadata());
        metadata = SelectedIntentContext.removeReserved(metadata);
        if (request.selectedIntent() != null) {
            validateSelectedIntent(request.selectedIntent(), runMode, request.targetType(), request.targetId());
            metadata = SelectedIntentContext.attach(metadata,
                    request.selectedIntent().intentId(), request.selectedIntent().intentName());
        }
        // 身份字段留空进入 application，由 Controller 入口解析出的 UserContext 统一回填。
        // 这样前端无法通过 Header/Query/Body 改写租户或用户，后续接入企业权限框架也只替换身份防腐层。
        return new ChatCommand(request.commandId(), null, null, request.sessionId(), request.conversationId(), "web",
                request.message(), toAttachmentRefs(request.attachments()), metadata,
                request.targetType(), request.targetId(),
                runMode, request.parentMessageId(), request.editedMessageId(),
                request.regeneratedMessageId(), routeTrigger(request.forceReroute()),
                request.interactionId(), request.approved(), request.scope(),
                normalizeMetadata(request.questionnaireAnswers()), request.appId(), request.appName(),
                toAgentMode(request.agentMode()), request.interactionAction(), request.language());
    }

    private String routeTrigger(Boolean forceReroute) {
        return Boolean.TRUE.equals(forceReroute) ? USER_CORRECTION : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void validateSelectedIntent(ChatSelectedIntentDto selectedIntent, ChatRunMode runMode,
                                        String targetType, String targetId) {
        if (runMode == ChatRunMode.CONTINUE_INTERACTION) {
            throw new IllegalArgumentException("CONTINUE_INTERACTION 模式不支持 selectedIntent");
        }
        if (!"DOMAIN_AGENT".equalsIgnoreCase(targetType) || !hasText(targetId)) {
            throw new IllegalArgumentException("selectedIntent 仅允许与 targetType=DOMAIN_AGENT 和有效 targetId 同时使用");
        }
        if (!hasText(selectedIntent.intentName())) {
            throw new IllegalArgumentException("selectedIntent.intentName 不能为空");
        }
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

    private AgentModeProfile toAgentMode(com.huawei.it.ex.one.interfaces.chat.dto.ChatAgentModeDto agentMode) {
        if (agentMode == null) {
            return null;
        }
        if (agentMode.selections() == null) {
            throw new IllegalArgumentException("agentMode.selections 不能为空");
        }
        return new AgentModeProfile(agentMode.selections().stream()
                .map(selection -> {
                    if (selection == null) {
                        throw new IllegalArgumentException("agentMode.selections 不能包含 null");
                    }
                    return new AgentModeSelection(
                            selection.scheme(), selection.code(), selection.displayName());
                })
                .toList());
    }
}
