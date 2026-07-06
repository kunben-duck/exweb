package com.huawei.finance.front.one.domain.chat;

/**
 * HITL 等待/续接状态冲突异常。
 */
public class ChatHitlUnavailableException extends IllegalStateException {
    private final String code;

    public ChatHitlUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ChatHitlUnavailableException waitingRequired(String sessionId, String hitlRequestId) {
        return new ChatHitlUnavailableException("WAITING_USER_INPUT_REQUIRED",
                "会话正在等待用户输入，请先处理澄清或审批请求: sessionId=" + sessionId
                        + ", hitlRequestId=" + hitlRequestId);
    }

    public static ChatHitlUnavailableException alreadyHandled(String hitlRequestId) {
        return new ChatHitlUnavailableException("HITL_ALREADY_HANDLED",
                "等待请求已被处理或正在处理中: " + hitlRequestId);
    }

    public static ChatHitlUnavailableException expired(String hitlRequestId) {
        return new ChatHitlUnavailableException("HITL_EXPIRED", "等待请求已过期: " + hitlRequestId);
    }
}
