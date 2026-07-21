package com.huawei.it.ex.one.intent.application.model;

/**
 * 意图服务重试耗尽且配置为直接结束本轮时抛出的稳定应用异常。
 */
public class IntentRoutingFailedException extends RuntimeException {
    public static final String CODE = "INTENT_ROUTING_FAILED";
    public static final String USER_MESSAGE = "暂时无法自动识别合适的技能，请手动选择技能后重试";

    private final String diagnosticReason;

    public IntentRoutingFailedException(String diagnosticReason) {
        super(USER_MESSAGE);
        this.diagnosticReason = diagnosticReason;
    }

    public String diagnosticReason() {
        return diagnosticReason;
    }
}
