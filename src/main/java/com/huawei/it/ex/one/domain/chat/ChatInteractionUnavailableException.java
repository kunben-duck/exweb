package com.huawei.it.ex.one.domain.chat;

/**
 * Interaction 等待/续接状态冲突异常。
 */
public class ChatInteractionUnavailableException extends IllegalStateException {
    private final String code;

    public ChatInteractionUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ChatInteractionUnavailableException waitingRequired(String sessionId, String interactionId) {
        return new ChatInteractionUnavailableException("WAITING_USER_INPUT_REQUIRED",
                "会话正在等待用户输入，请先处理澄清或审批请求: sessionId=" + sessionId
                        + ", interactionId=" + interactionId);
    }

    public static ChatInteractionUnavailableException alreadyHandled(String interactionId) {
        return new ChatInteractionUnavailableException("INTERACTION_ALREADY_HANDLED",
                "等待请求已被处理或正在处理中: " + interactionId);
    }

    public static ChatInteractionUnavailableException expired(String interactionId) {
        return new ChatInteractionUnavailableException("INTERACTION_EXPIRED", "等待请求已过期: " + interactionId);
    }

    public static ChatInteractionUnavailableException attachmentUnavailable(String interactionId) {
        return new ChatInteractionUnavailableException("INTERACTION_ATTACHMENT_UNAVAILABLE",
                "历史澄清附件已不存在、无权限或不可用于聊天，请重新发起任务并重新选择附件: " + interactionId);
    }
}
