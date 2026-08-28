package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

/**
 * DomainAgent 响应协议异常。
 *
 * <p>异常消息只包含稳定的阶段与大小信息，不携带下游原始响应，避免错误日志和终态事件泄漏业务内容。</p>
 */
public final class DomainAgentProtocolException extends RuntimeException {
    private static final String FRAME_TOO_LARGE = "DOMAIN_AGENT_FRAME_TOO_LARGE";
    private static final String INVALID_UTF8 = "DOMAIN_AGENT_INVALID_UTF8";

    private DomainAgentProtocolException(String message) {
        super(message);
    }

    private DomainAgentProtocolException(String message, Throwable cause) {
        super(message, cause);
    }

    public static DomainAgentProtocolException frameTooLarge(int actualBytes, int maxBytes) {
        return new DomainAgentProtocolException(FRAME_TOO_LARGE
                + ": DomainAgent frame exceeds limit, actualBytes=" + actualBytes + ", maxBytes=" + maxBytes);
    }

    public static DomainAgentProtocolException invalidUtf8(Throwable cause) {
        return new DomainAgentProtocolException(
                INVALID_UTF8 + ": DomainAgent response contains malformed UTF-8", cause);
    }

    public static DomainAgentProtocolException invalidFrame(String message) {
        String detail = message == null || message.isBlank() ? "invalid control frame" : message.trim();
        return new DomainAgentProtocolException("DOMAIN_AGENT_PROTOCOL_INVALID: " + detail);
    }
}
